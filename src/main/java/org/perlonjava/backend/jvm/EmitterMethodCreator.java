package org.perlonjava.backend.jvm;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.TraceClassVisitor;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.backend.jvm.astrefactor.LargeBlockRefactorer;
import org.perlonjava.backend.bytecode.BytecodeCompiler;
import org.perlonjava.backend.bytecode.InterpretedCode;
import org.perlonjava.frontend.analysis.DepthFirstLiteralRefactorVisitor;
import org.perlonjava.frontend.analysis.TempLocalCountVisitor;
import org.perlonjava.frontend.astnode.BlockNode;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.runtime.runtimetypes.*;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;

/**
 * EmitterMethodCreator is a utility class that uses the ASM library to dynamically generate Java
 * classes with specific methods. It is designed to create classes with methods that can be used for
 * runtime evaluation of expressions or statements in a simulated Perl environment.
 */
public class EmitterMethodCreator implements Opcodes {

    // Feature flags for control flow implementation
    // Set to true to enable tail call trampoline (Phase 3)
    private static final boolean ENABLE_TAILCALL_TRAMPOLINE = true;
    
    // Set to true to enable debug output for control flow
    private static final boolean DEBUG_CONTROL_FLOW = false;

    // Number of local variables to skip when processing a closure (this, @_, wantarray)
    public static int skipVariables = 3;

    // Counter for generating unique class names
    public static int classCounter = 0;

    // Generate a unique internal class name
    public static String generateClassName() {
        return "org/perlonjava/anon" + classCounter++;
    }

    private static String insnToString(AbstractInsnNode n) {
        if (n == null) {
            return "<null>";
        }
        int op = n.getOpcode();
        String opName = (op >= 0 && op < Printer.OPCODES.length) ? Printer.OPCODES[op] : "<no-opcode>";

        if (n instanceof org.objectweb.asm.tree.VarInsnNode vn) {
            return opName + " " + vn.var;
        }
        if (n instanceof org.objectweb.asm.tree.MethodInsnNode mn) {
            return opName + " " + mn.owner + "." + mn.name + mn.desc;
        }
        if (n instanceof org.objectweb.asm.tree.FieldInsnNode fn) {
            return opName + " " + fn.owner + "." + fn.name + " : " + fn.desc;
        }
        if (n instanceof org.objectweb.asm.tree.TypeInsnNode tn) {
            return opName + " " + tn.desc;
        }
        if (n instanceof org.objectweb.asm.tree.LdcInsnNode ln) {
            return opName + " " + String.valueOf(ln.cst);
        }
        if (n instanceof org.objectweb.asm.tree.IntInsnNode in) {
            return opName + " " + in.operand;
        }
        if (n instanceof org.objectweb.asm.tree.IincInsnNode ii) {
            return opName + " " + ii.var + " " + ii.incr;
        }
        if (n instanceof org.objectweb.asm.tree.LineNumberNode ln) {
            return "LINE " + ln.line;
        }
        if (n instanceof org.objectweb.asm.tree.LabelNode) {
            return "LABEL";
        }
        if (n instanceof org.objectweb.asm.tree.JumpInsnNode) {
            return opName + " <label>";
        }
        return opName;
    }

    private static void debugAnalyzeWithBasicInterpreter(ClassReader cr, PrintWriter out) {
        try {
            ClassNode cn = new ClassNode();
            cr.accept(cn, ClassReader.EXPAND_FRAMES);
            for (Object m : cn.methods) {
                MethodNode mn = (MethodNode) m;
                try {
                    Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
                    analyzer.analyze(cn.name, mn);
                } catch (AnalyzerException ae) {
                    int insnIndex = (ae.node != null) ? mn.instructions.indexOf(ae.node) : -1;
                    if (insnIndex < 0) {
                        try {
                            String msg = String.valueOf(ae);
                            int atPos = msg.indexOf("Error at instruction ");
                            if (atPos >= 0) {
                                int start = atPos + "Error at instruction ".length();
                                int end = start;
                                while (end < msg.length() && Character.isDigit(msg.charAt(end))) {
                                    end++;
                                }
                                if (end > start) {
                                    insnIndex = Integer.parseInt(msg.substring(start, end));
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    out.println("BasicInterpreter failure in " + cn.name + "." + mn.name + mn.desc + " at instruction " + insnIndex);
                    if (insnIndex >= 0) {
                        int from = Math.max(0, insnIndex - 10);
                        int to = Math.min(mn.instructions.size() - 1, insnIndex + 10);
                        for (int i = from; i <= to; i++) {
                            org.objectweb.asm.tree.AbstractInsnNode n = mn.instructions.get(i);
                            if (n instanceof org.objectweb.asm.tree.JumpInsnNode j) {
                                int target = mn.instructions.indexOf(j.label);
                                out.println("  [" + i + "] " + insnToString(n) + " -> [" + target + "]");
                            } else {
                                out.println("  [" + i + "] " + insnToString(n));
                            }
                        }

                        org.objectweb.asm.tree.AbstractInsnNode failing = mn.instructions.get(insnIndex);
                        if (failing instanceof org.objectweb.asm.tree.JumpInsnNode j) {
                            int target = mn.instructions.indexOf(j.label);
                            if (target >= 0) {
                                out.println("  --- jump target window: [" + target + "] ---");
                                int tFrom = Math.max(0, target - 10);
                                int tTo = Math.min(mn.instructions.size() - 1, target + 10);
                                for (int i = tFrom; i <= tTo; i++) {
                                    org.objectweb.asm.tree.AbstractInsnNode n = mn.instructions.get(i);
                                    if (n instanceof org.objectweb.asm.tree.JumpInsnNode tj) {
                                        int tTarget = mn.instructions.indexOf(tj.label);
                                        out.println("  [" + i + "] " + insnToString(n) + " -> [" + tTarget + "]");
                                    } else {
                                        out.println("  [" + i + "] " + insnToString(n));
                                    }
                                }

                                out.println("  --- other predecessors targeting [" + target + "] ---");
                                java.util.ArrayList<Integer> predecessors = new java.util.ArrayList<>();
                                for (int i = 0; i < mn.instructions.size(); i++) {
                                    if (i == insnIndex) {
                                        continue;
                                    }
                                    org.objectweb.asm.tree.AbstractInsnNode n = mn.instructions.get(i);
                                    if (n instanceof org.objectweb.asm.tree.JumpInsnNode pj && pj.label == j.label) {
                                        out.println("  [" + i + "] " + insnToString(n) + " -> [" + target + "]");
                                        predecessors.add(i);
                                    }
                                }

                                try {
                                    Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
                                    try {
                                        analyzer.analyze(cn.name, mn);
                                    } catch (AnalyzerException ignored) {
                                    }

                                    org.objectweb.asm.tree.analysis.Frame<BasicValue>[] frames = analyzer.getFrames();

                                    org.objectweb.asm.tree.analysis.Frame<SourceValue>[] sourceFrames = null;
                                    try {
                                        Analyzer<SourceValue> sourceAnalyzer = new Analyzer<>(new SourceInterpreter());
                                        try {
                                            sourceAnalyzer.analyze(cn.name, mn);
                                        } catch (AnalyzerException ignored) {
                                        }
                                        sourceFrames = sourceAnalyzer.getFrames();
                                    } catch (Throwable ignored) {
                                    }
                                    java.util.ArrayList<Integer> framePoints = new java.util.ArrayList<>();
                                    framePoints.add(insnIndex);
                                    framePoints.add(target);
                                    framePoints.addAll(predecessors);

                                    out.println("  --- frame stack sizes (if available) ---");
                                    for (Integer idx : framePoints) {
                                        if (idx == null || idx < 0 || idx >= frames.length) {
                                            continue;
                                        }
                                        org.objectweb.asm.tree.analysis.Frame<BasicValue> f = frames[idx];
                                        if (f == null) {
                                            out.println("  [" + idx + "] <no frame>");
                                            continue;
                                        }
                                        out.println("  [" + idx + "] stack=" + f.getStackSize() + " locals=" + f.getLocals());
                                        for (int s = 0; s < f.getStackSize(); s++) {
                                            out.println("    stack[" + s + "]=" + f.getStack(s));
                                        }

                                        if (sourceFrames != null && idx >= 0 && idx < sourceFrames.length) {
                                            org.objectweb.asm.tree.analysis.Frame<SourceValue> sf = sourceFrames[idx];
                                            if (sf != null) {
                                                out.println("    --- stack sources at [" + idx + "] ---");
                                                java.util.LinkedHashSet<Integer> sourceInsnsToPrint = new java.util.LinkedHashSet<>();
                                                for (int s = 0; s < sf.getStackSize(); s++) {
                                                    SourceValue sv = sf.getStack(s);
                                                    if (sv == null || sv.insns == null) {
                                                        continue;
                                                    }
                                                    java.util.ArrayList<Integer> srcIdxs = new java.util.ArrayList<>();
                                                    for (org.objectweb.asm.tree.AbstractInsnNode src : sv.insns) {
                                                        int si = mn.instructions.indexOf(src);
                                                        if (si >= 0) {
                                                            srcIdxs.add(si);
                                                            sourceInsnsToPrint.add(si);
                                                        }
                                                    }
                                                    if (!srcIdxs.isEmpty()) {
                                                        out.println("      stack[" + s + "] sources=" + srcIdxs);
                                                    }
                                                }

                                                int printed = 0;
                                                for (Integer srcIdx : sourceInsnsToPrint) {
                                                    if (srcIdx == null) {
                                                        continue;
                                                    }
                                                    if (printed++ >= 12) {
                                                        out.println("      (additional source windows omitted)");
                                                        break;
                                                    }
                                                    out.println("    --- source instruction window: [" + srcIdx + "] ---");
                                                    int sFrom = Math.max(0, srcIdx - 20);
                                                    int sTo = Math.min(mn.instructions.size() - 1, srcIdx + 20);
                                                    for (int i = sFrom; i <= sTo; i++) {
                                                        org.objectweb.asm.tree.AbstractInsnNode n = mn.instructions.get(i);
                                                        if (n instanceof org.objectweb.asm.tree.JumpInsnNode pj) {
                                                            int sTarget = mn.instructions.indexOf(pj.label);
                                                            out.println("    [" + i + "] " + insnToString(n) + " -> [" + sTarget + "]");
                                                        } else {
                                                            out.println("    [" + i + "] " + insnToString(n));
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (Throwable t) {
                                    out.println("  <frame dump failed: " + t + ">");
                                }

                                for (Integer p : predecessors) {
                                    out.println("  --- predecessor window: [" + p + "] -> [" + target + "] ---");
                                    int pFrom = Math.max(0, p - 6);
                                    int pTo = Math.min(mn.instructions.size() - 1, p + 6);
                                    for (int i = pFrom; i <= pTo; i++) {
                                        org.objectweb.asm.tree.AbstractInsnNode n = mn.instructions.get(i);
                                        if (n instanceof org.objectweb.asm.tree.JumpInsnNode pj) {
                                            int pTarget = mn.instructions.indexOf(pj.label);
                                            out.println("  [" + i + "] " + insnToString(n) + " -> [" + pTarget + "]");
                                        } else {
                                            out.println("  [" + i + "] " + insnToString(n));
                                        }
                                    }
                                }
                            }
                        }
                    }
                    ae.printStackTrace(out);
                    return;
                }
            }
        } catch (Throwable t) {
            t.printStackTrace(out);
        }
    }

    /**
     * Generates a descriptor string based on the prefix of a Perl variable name.
     *
     * @param varName The Perl variable name, which typically starts with a special character
     *                indicating its type (e.g., '%', '@', or '$').
     * @return A descriptor string representing the type of the Perl variable.
     */
    public static String getVariableDescriptor(String varName) {
        // Handle null or empty variable names (gaps in symbol table)
        // These represent unused slots in the local variable array
        if (varName == null || varName.isEmpty()) {
            return "Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;";
        }

        // Extract the first character of the variable name
        char firstChar = varName.charAt(0);

        // Use a switch statement to determine the descriptor based on the first character
        return switch (firstChar) {
            case '%' -> "Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;";
            case '@' -> "Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;";
            default -> "Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;";
        };
    }

    /**
     * Generates a class name based on the prefix of a Perl variable name.
     *
     * @param varName The Perl variable name, which typically starts with a special character
     *                indicating its type (e.g., '%', '@', or '$').
     * @return A class name string representing the type of the Perl variable.
     */
    public static String getVariableClassName(String varName) {
        // Handle null or empty variable names (gaps in symbol table)
        // These represent unused slots in the local variable array
        if (varName == null || varName.isEmpty()) {
            return "org/perlonjava/runtime/runtimetypes/RuntimeScalar";
        }

        // Extract the first character of the variable name
        char firstChar = varName.charAt(0);

        // Use a switch statement to determine the class name based on the first character
        return switch (firstChar) {
            case '%' -> "org/perlonjava/runtime/runtimetypes/RuntimeHash";
            case '@' -> "org/perlonjava/runtime/runtimetypes/RuntimeArray";
            default -> "org/perlonjava/runtime/runtimetypes/RuntimeScalar";
        };
    }

    /**
     * Creates a new class with a method based on the provided context, environment, and abstract
     * syntax tree (AST).
     *
     * @param ctx         The emitter context containing information for code generation.
     * @param ast         The abstract syntax tree representing the method body.
     * @param useTryCatch Flag to enable try-catch in the generated class. This is used in `eval` operator.
     * @return The generated class.
     */
    public static Class<?> createClassWithMethod(EmitterContext ctx, Node ast, boolean useTryCatch) {
        byte[] classData = getBytecode(ctx, ast, useTryCatch);
        return loadBytecode(ctx, classData);
    }

    public static byte[] getBytecode(EmitterContext ctx, Node ast, boolean useTryCatch) {
        boolean asmDebug = System.getenv("JPERL_ASM_DEBUG") != null;
        boolean showFallback = System.getenv("JPERL_SHOW_FALLBACK") != null;
        boolean useInterpreterFallback = System.getenv("JPERL_DISABLE_INTERPRETER_FALLBACK") == null;

        try {
            return getBytecodeInternal(ctx, ast, useTryCatch, false);
        } catch (MethodTooLargeException tooLarge) {
            // When interpreter fallback is enabled, skip AST splitter and let exception propagate
            // The interpreter has no size limits, so AST splitting is unnecessary
            if (useInterpreterFallback) {
                if (showFallback) {
                    System.err.println("Note: Method too large, skipping AST splitter (interpreter fallback enabled).");
                }
                throw tooLarge;  // Propagate to createRuntimeCode() which will use interpreter
            }

            // Automatic retry with AST splitting when interpreter fallback is not enabled
            try {
                // Notify user that automatic refactoring is happening
                if (showFallback) {
                    System.err.println("Note: Method too large, retrying with AST splitter (automatic refactoring).");
                }

                // First, try depth-first literal refactoring (refactors nested structures first)
                DepthFirstLiteralRefactorVisitor.refactor(ast);

                // Then, if it's a BlockNode, also try block-level refactoring
                if (ast instanceof BlockNode blockAst) {
                    LargeBlockRefactorer.forceRefactorForCodegen(blockAst);
                }

                // Reset JavaClassInfo to avoid reusing partially-resolved Labels.
                if (ctx != null && ctx.javaClassInfo != null) {
                    String previousName = ctx.javaClassInfo.javaClassName;
                    ctx.javaClassInfo = new JavaClassInfo();
                    ctx.javaClassInfo.javaClassName = previousName;
                    ctx.clearContextCache();
                }

                byte[] result = getBytecodeInternal(ctx, ast, useTryCatch, false);
                if (showFallback) {
                    System.err.println("Note: AST splitter succeeded.");
                }
                return result;
            } catch (MethodTooLargeException retryTooLarge) {
                // Refactoring didn't help enough - give up
                if (showFallback) {
                    System.err.println("Note: AST splitter failed, propagating exception.");
                }
                throw retryTooLarge;
            } catch (Throwable retryError) {
                // Refactoring caused a different error - report both
                System.err.println("Warning: Automatic refactoring failed: " + retryError.getMessage());
                retryError.printStackTrace();
                // Fall through to throw original exception
            }
            throw tooLarge;
        } catch (ArrayIndexOutOfBoundsException frameComputeCrash) {
            // In normal operation we MUST NOT fall back to no-frames output, as that will fail
            // verification on modern JVMs ("Expecting a stackmap frame...").
            //
            // When JPERL_ASM_DEBUG is enabled, do a diagnostic pass without COMPUTE_FRAMES so we can
            // disassemble + analyze the produced bytecode.
            frameComputeCrash.printStackTrace();
            try {
                String failingClass = (ctx != null && ctx.javaClassInfo != null)
                        ? ctx.javaClassInfo.javaClassName
                        : "<unknown>";
                int failingIndex = ast != null ? ast.getIndex() : -1;
                String fileName = (ctx != null && ctx.errorUtil != null) ? ctx.errorUtil.getFileName() : "<unknown>";
                int lineNumber = -1;
                if (ctx != null && ctx.errorUtil != null && failingIndex >= 0) {
                    // ErrorMessageUtil caches line-number scanning state; reset it for an accurate lookup here.
                    ctx.errorUtil.setTokenIndex(-1);
                    ctx.errorUtil.setLineNumber(1);
                    lineNumber = ctx.errorUtil.getLineNumber(failingIndex);
                }
                String at = lineNumber >= 0 ? (fileName + ":" + lineNumber) : fileName;
                System.err.println("ASM frame compute crash in generated class: " + failingClass + " (astIndex=" + failingIndex + ", at " + at + ")");
            } catch (Throwable ignored) {
            }
            if (asmDebug) {
                try {
                    // Reset JavaClassInfo to avoid reusing partially-resolved Labels.
                    if (ctx != null && ctx.javaClassInfo != null) {
                        String previousName = ctx.javaClassInfo.javaClassName;
                        ctx.javaClassInfo = new JavaClassInfo();
                        ctx.javaClassInfo.javaClassName = previousName;
                        ctx.clearContextCache();
                    }
                    getBytecodeInternal(ctx, ast, useTryCatch, true);
                } catch (Throwable diagErr) {
                    diagErr.printStackTrace();
                }
            }
            throw new PerlCompilerException(
                    ast.getIndex(),
                    "Internal compiler error: ASM frame computation failed. " +
                            "Re-run with JPERL_ASM_DEBUG=1 to print disassembly and analysis. " +
                            "Original error: " + frameComputeCrash.getMessage(),
                    ctx.errorUtil,
                    frameComputeCrash);
        }
    }

    private static byte[] getBytecodeInternal(EmitterContext ctx, Node ast, boolean useTryCatch, boolean disableFrames) {
        String className = ctx.javaClassInfo.javaClassName;
        String methodName = "apply";
        byte[] classData = null;
        boolean asmDebug = System.getenv("JPERL_ASM_DEBUG") != null;
        String asmDebugClassFilter = System.getenv("JPERL_ASM_DEBUG_CLASS");
        boolean asmDebugClassMatches = asmDebugClassFilter == null
                || asmDebugClassFilter.isEmpty()
                || className.contains(asmDebugClassFilter)
                || className.replace('/', '.').contains(asmDebugClassFilter);
        
        try {
            // Use capturedEnv if available (for eval), otherwise get from symbol table
            String[] env = (ctx.capturedEnv != null) ? ctx.capturedEnv : ctx.symbolTable.getVariableNames();

            // Create a ClassWriter with COMPUTE_FRAMES for automatic frame computation
            // Only disable for explicit diagnostic pass
            int cwFlags = disableFrames
                    ? ClassWriter.COMPUTE_MAXS
                    : (ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassWriter cw = new ClassWriter(cwFlags);
            ctx.cw = cw;

            // The context type is determined by the caller.
            ctx.contextType = RuntimeContextType.RUNTIME;

            ByteCodeSourceMapper.setDebugInfoFileName(ctx);

            // Define the class with version, access flags, name, signature, superclass, and interfaces
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
            ctx.logDebug("Create class: " + className);

            // Add instance fields to the class for closure variables
            for (String fieldName : env) {
                // Skip null entries (gaps in sparse symbol table)
                if (fieldName == null || fieldName.isEmpty()) {
                    continue;
                }
                String descriptor = getVariableDescriptor(fieldName);
                ctx.logDebug("Create instance field: " + descriptor);
                cw.visitField(Opcodes.ACC_PUBLIC, fieldName, descriptor, null, null).visitEnd();
            }

            // Add instance field for __SUB__ code reference
            cw.visitField(Opcodes.ACC_PUBLIC, "__SUB__", "Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", null, null).visitEnd();

            // Add a constructor with parameters for initializing the fields
            // Include ALL env slots (even nulls) so signature matches caller expectations
            StringBuilder constructorDescriptor = new StringBuilder("(");
            for (int i = skipVariables; i < env.length; i++) {
                String descriptor = getVariableDescriptor(env[i]);  // handles nulls gracefully
                constructorDescriptor.append(descriptor);
            }
            constructorDescriptor.append(")V");
            ctx.logDebug("constructorDescriptor: " + constructorDescriptor);
            ctx.mv =
                    cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", constructorDescriptor.toString(), null, null);
            MethodVisitor mv = ctx.mv;
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0); // Load 'this'
            mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "java/lang/Object",
                    "<init>",
                    "()V",
                    false); // Call the superclass constructor
            for (int i = skipVariables; i < env.length; i++) {
                // Skip null entries (gaps in sparse symbol table)
                if (env[i] == null || env[i].isEmpty()) {
                    continue;
                }
                String descriptor = getVariableDescriptor(env[i]);

                mv.visitVarInsn(Opcodes.ALOAD, 0); // Load 'this'
                mv.visitVarInsn(Opcodes.ALOAD, i - 2); // Load the constructor argument
                mv.visitFieldInsn(
                        Opcodes.PUTFIELD, ctx.javaClassInfo.javaClassName, env[i], descriptor); // Set the instance field
            }
            mv.visitInsn(Opcodes.RETURN); // Return void
            mv.visitMaxs(0, 0); // Automatically computed
            mv.visitEnd();

            // Create the public "apply" method for the generated class
            ctx.logDebug("Create the method");
            ctx.mv =
                    cw.visitMethod(
                            Opcodes.ACC_PUBLIC,
                            "apply",
                            "(Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;",
                            null,
                            new String[]{"java/lang/Exception"});
            mv = ctx.mv;

            // Generate the subroutine block
            mv.visitCode();

            // Initialize local variables with closure values from instance fields
            // Skip some indices because they are reserved for special arguments (this, "@_" and call
            // context)
            for (int i = skipVariables; i < env.length; i++) {
                // Skip null entries (gaps in sparse array)
                if (env[i] == null) {
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    mv.visitVarInsn(Opcodes.ASTORE, i);
                    continue;
                }
                String descriptor = getVariableDescriptor(env[i]);
                mv.visitVarInsn(Opcodes.ALOAD, 0); // Load 'this'
                ctx.logDebug("Init closure variable: " + descriptor);
                mv.visitFieldInsn(Opcodes.GETFIELD, ctx.javaClassInfo.javaClassName, env[i], descriptor);
                mv.visitVarInsn(Opcodes.ASTORE, i);
            }

            // IMPORTANT (JVM verifier): captured/lexical variables may live in *sparse* local slots,
            // because their indices come from the symbol table (pad) and can include gaps.
            //
            // During bytecode emission we also allocate temporary locals via
            // ctx.symbolTable.allocateLocalVariable(). If that allocator's current index is still
            // below env.length, temporaries could be assigned into slots that are reserved for
            // captured variables (even if those slots are currently "null" gaps in env[]), or into
            // slots that will be accessed later as references.
            //
            // That overlap can produce invalid stack frames such as: locals[n] == TOP at an ALOAD,
            // which the JVM rejects with VerifyError: Bad local variable type.
            //
            // Ensure temporaries start *after* the captured variable range.
            //
            // NOTE: ctx.symbolTable.allocateLocalVariable() mutates the symbol table's internal
            // index counter. In eval-string compilation we may reuse the captured symbol table
            // instance across many eval invocations. If we don't reset the counter for each
            // generated method, the local slot numbers will grow without bound (eventually
            // producing invalid stack map frames / VerifyError).
            ctx.symbolTable.resetLocalVariableIndex(env.length);

            // Pre-initialize temporary local slots to avoid VerifyError
            // Temporaries are allocated dynamically during bytecode emission via
            // ctx.symbolTable.allocateLocalVariable(). We pre-initialize slots to ensure
            // they're not in TOP state when accessed. Use a visitor to estimate the
            // actual number needed based on AST structure rather than a fixed count.
            int preInitTempLocalsStart = ctx.symbolTable.getCurrentLocalVariableIndex();
            TempLocalCountVisitor tempCountVisitor =
                new TempLocalCountVisitor();
            ast.accept(tempCountVisitor);
            int preInitTempLocalsCount = tempCountVisitor.getMaxTempCount() + 64;  // Optimized: removed min-128 baseline
            for (int i = preInitTempLocalsStart; i < preInitTempLocalsStart + preInitTempLocalsCount; i++) {
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitVarInsn(Opcodes.ASTORE, i);
            }

            // Manual frames removed - using COMPUTE_FRAMES for automatic frame computation

            // Allocate slots for tail call trampoline (codeRef and args)
            // These are used at returnLabel for TAILCALL handling
            int tailCallCodeRefSlot = ctx.symbolTable.allocateLocalVariable();
            int tailCallArgsSlot = ctx.symbolTable.allocateLocalVariable();
            ctx.javaClassInfo.tailCallCodeRefSlot = tailCallCodeRefSlot;
            ctx.javaClassInfo.tailCallArgsSlot = tailCallArgsSlot;
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitVarInsn(Opcodes.ASTORE, tailCallCodeRefSlot);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitVarInsn(Opcodes.ASTORE, tailCallArgsSlot);
            
            // Allocate slot for control flow check temp storage
            // This is used at call sites to temporarily store marked RuntimeControlFlowList
            int controlFlowTempSlot = ctx.symbolTable.allocateLocalVariable();
            ctx.javaClassInfo.controlFlowTempSlot = controlFlowTempSlot;
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitVarInsn(Opcodes.ASTORE, controlFlowTempSlot);

            int controlFlowActionSlot = ctx.symbolTable.allocateLocalVariable();
            ctx.javaClassInfo.controlFlowActionSlot = controlFlowActionSlot;
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, controlFlowActionSlot);

            int spillSlotCount = System.getenv("JPERL_SPILL_SLOTS") != null
                    ? Integer.parseInt(System.getenv("JPERL_SPILL_SLOTS"))
                    : 16;
            ctx.javaClassInfo.spillSlots = new int[spillSlotCount];
            ctx.javaClassInfo.spillTop = 0;
            for (int i = 0; i < spillSlotCount; i++) {
                int slot = ctx.symbolTable.allocateLocalVariable();
                ctx.javaClassInfo.spillSlots[i] = slot;
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitVarInsn(Opcodes.ASTORE, slot);
            }
            
            // Create a label for the return point
            ctx.javaClassInfo.returnLabel = new Label();

            // Prepare to visit the AST to generate bytecode
            EmitterVisitor visitor = new EmitterVisitor(ctx);

            // Setup local variables and environment for the method
            Local.localRecord localRecord = Local.localSetup(ctx, ast, mv);

            // Store the computed RuntimeList return value in a dedicated local slot.
            // This keeps the operand stack empty at join labels (endCatch), avoiding
            // inconsistent stack map frames when multiple control-flow paths merge.
            int returnListSlot = ctx.symbolTable.allocateLocalVariable();

            // Spill the raw RuntimeBase return value for stack-neutral joins at returnLabel.
            // Any path that jumps to returnLabel must arrive with an empty operand stack.
            int returnValueSlot = ctx.symbolTable.allocateLocalVariable();
            ctx.javaClassInfo.returnValueSlot = returnValueSlot;
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitVarInsn(Opcodes.ASTORE, returnValueSlot);

            // Labels for eval-block try/catch wrapping (used only when useTryCatch=true)
            Label tryStart = null;
            Label tryEnd = null;
            Label catchBlock = null;
            Label endCatch = null;

            if (useTryCatch) {
                ctx.logDebug("useTryCatch");

                // --------------------------------
                // Start of try-catch block
                // --------------------------------

                tryStart = new Label();
                tryEnd = new Label();
                catchBlock = new Label();
                endCatch = new Label();

                // Define the try-catch block
                mv.visitTryCatchBlock(tryStart, tryEnd, catchBlock, "java/lang/Throwable");

                mv.visitLabel(tryStart);
                // --------------------------------
                // Start of the try block
                // --------------------------------

                // Set $@ to an empty string if no exception occurs
                mv.visitLdcInsn("main::@");
                mv.visitLdcInsn("");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/GlobalVariable",
                        "setGlobalVariable",
                        "(Ljava/lang/String;Ljava/lang/String;)V", false);

                ast.accept(visitor);

                // Normal fallthrough return: spill and jump with empty operand stack.
                mv.visitVarInsn(Opcodes.ASTORE, returnValueSlot);
                mv.visitJumpInsn(Opcodes.GOTO, ctx.javaClassInfo.returnLabel);

                // Handle the return value
                ctx.logDebug("Return the last value");


                // --------------------------------
                // End of the try block
                // --------------------------------
                // NOTE: We intentionally delay tryEnd/endCatch labels until after the
                // return-value materialization and trampoline checks below.
                // This ensures eval BLOCK catches control-flow marker errors raised
                // during epilogue processing (e.g. bad goto), instead of escaping and
                // terminating top-level execution.

                // --------------------------------
                // End of try-catch block is emitted AFTER the epilogue/trampoline.
                // --------------------------------
            } else {
                // No try-catch block is used

                ast.accept(visitor);

                // Normal fallthrough return: spill and jump with empty operand stack.
                mv.visitVarInsn(Opcodes.ASTORE, returnValueSlot);
                mv.visitJumpInsn(Opcodes.GOTO, ctx.javaClassInfo.returnLabel);

                // Handle the return value
                ctx.logDebug("Return the last value");
            }

            // Join point for all returns/gotos. Must be stack-neutral.
            mv.visitLabel(ctx.javaClassInfo.returnLabel);
            mv.visitVarInsn(Opcodes.ALOAD, returnValueSlot);

            // Transform the value in the stack to RuntimeList BEFORE local teardown.
            // Materialize it into a local slot immediately so all subsequent control-flow
            // checks operate from locals and join points don't depend on operand stack shape.
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeBase", "getList", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeList;", false);
            mv.visitVarInsn(Opcodes.ASTORE, returnListSlot);

            // Phase 3: Check for control flow markers
            // RuntimeList is on stack after getList()
            
            if (ENABLE_TAILCALL_TRAMPOLINE) {
            // First, check if it's a TAILCALL (global trampoline)
            Label tailcallLoop = new Label();
            Label notTailcall = new Label();
            Label normalReturn = new Label();
            
            mv.visitVarInsn(Opcodes.ALOAD, returnListSlot);
            mv.visitInsn(Opcodes.DUP);  // Duplicate for checking
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeList",
                    "isNonLocalGoto", 
                    "()Z", 
                    false);
            mv.visitJumpInsn(Opcodes.IFEQ, normalReturn);  // Not marked, return normally
            
            // Marked: check if TAILCALL
            // Cast to RuntimeControlFlowList to access getControlFlowType()
            mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList");
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList",
                    "getControlFlowType",
                    "()Lorg/perlonjava/runtime/runtimetypes/ControlFlowType;",
                    false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/ControlFlowType",
                    "ordinal",
                    "()I",
                    false);
            mv.visitInsn(Opcodes.ICONST_4);  // TAILCALL.ordinal() = 4
            mv.visitJumpInsn(Opcodes.IF_ICMPNE, notTailcall);
            
            // TAILCALL trampoline loop
            mv.visitLabel(tailcallLoop);
            // Cast to RuntimeControlFlowList to access getTailCallCodeRef/getTailCallArgs
            mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList");
            
            if (DEBUG_CONTROL_FLOW) {
                // Debug: print what we're about to process
                mv.visitInsn(Opcodes.DUP);
                mv.visitFieldInsn(Opcodes.GETFIELD,
                        "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList",
                        "marker",
                        "Lorg/perlonjava/runtime/runtimetypes/ControlFlowMarker;");
                mv.visitLdcInsn("TRAMPOLINE_LOOP");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/ControlFlowMarker",
                        "debugPrint",
                        "(Ljava/lang/String;)V",
                        false);
            }
            
            // Extract codeRef and args
            // Use allocated slots from symbol table
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList",
                    "getTailCallCodeRef",
                    "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                    false);
            mv.visitVarInsn(Opcodes.ASTORE, ctx.javaClassInfo.tailCallCodeRefSlot);
            
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList",
                    "getTailCallArgs",
                    "()Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;",
                    false);
            mv.visitVarInsn(Opcodes.ASTORE, ctx.javaClassInfo.tailCallArgsSlot);
            
            // Re-invoke: RuntimeCode.apply(codeRef, "tailcall", args, context)
            mv.visitVarInsn(Opcodes.ALOAD, ctx.javaClassInfo.tailCallCodeRefSlot);
            mv.visitLdcInsn("tailcall");
            mv.visitVarInsn(Opcodes.ALOAD, ctx.javaClassInfo.tailCallArgsSlot);
            mv.visitVarInsn(Opcodes.ILOAD, 2);  // context (from parameter)
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/RuntimeCode",
                    "apply",
                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Ljava/lang/String;Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;",
                    false);
            
            // Check if result is another TAILCALL
            mv.visitInsn(Opcodes.DUP);
            
            if (DEBUG_CONTROL_FLOW) {
                // Debug: print the result before checking
                mv.visitInsn(Opcodes.DUP);
                mv.visitFieldInsn(Opcodes.GETSTATIC,
                        "java/lang/System",
                        "err",
                        "Ljava/io/PrintStream;");
                mv.visitInsn(Opcodes.SWAP);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "java/io/PrintStream",
                        "println",
                        "(Ljava/lang/Object;)V",
                        false);
            }
            
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeList",
                    "isNonLocalGoto",
                    "()Z",
                    false);
            
            if (DEBUG_CONTROL_FLOW) {
                // Debug: print the isNonLocalGoto result
                mv.visitInsn(Opcodes.DUP);
                mv.visitFieldInsn(Opcodes.GETSTATIC,
                        "java/lang/System",
                        "err",
                        "Ljava/io/PrintStream;");
                mv.visitInsn(Opcodes.SWAP);
                mv.visitLdcInsn("isNonLocalGoto: ");
                mv.visitInsn(Opcodes.SWAP);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "java/io/PrintStream",
                        "print",
                        "(Ljava/lang/String;)V",
                        false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "java/io/PrintStream",
                        "println",
                        "(Z)V",
                        false);
            }
            
            mv.visitJumpInsn(Opcodes.IFEQ, normalReturn);  // Not marked, done
            
            // Cast to RuntimeControlFlowList to access getControlFlowType()
            mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList");
            mv.visitInsn(Opcodes.DUP);
            
            if (DEBUG_CONTROL_FLOW) {
                // Debug: print the control flow type
                mv.visitInsn(Opcodes.DUP);
                mv.visitFieldInsn(Opcodes.GETFIELD,
                        "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList",
                        "marker",
                        "Lorg/perlonjava/runtime/runtimetypes/ControlFlowMarker;");
                mv.visitLdcInsn("TRAMPOLINE_CHECK");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/ControlFlowMarker",
                        "debugPrint",
                        "(Ljava/lang/String;)V",
                        false);
            }
            
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList",
                    "getControlFlowType",
                    "()Lorg/perlonjava/runtime/runtimetypes/ControlFlowType;",
                    false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/ControlFlowType",
                    "ordinal",
                    "()I",
                    false);
            mv.visitInsn(Opcodes.ICONST_4);
            mv.visitJumpInsn(Opcodes.IF_ICMPEQ, tailcallLoop);  // Loop if still TAILCALL
            // Not TAILCALL: check if we're inside a loop and should jump to loop handler
            mv.visitLabel(notTailcall);
            if (useTryCatch) {
                // For eval BLOCK, any marked non-TAILCALL result is an eval failure.
                // Stack here: [RuntimeControlFlowList]
                int msgSlot = ctx.symbolTable.allocateLocalVariable();

                // msg = marker.buildErrorMessage()
                mv.visitInsn(Opcodes.DUP);
                mv.visitFieldInsn(Opcodes.GETFIELD,
                        "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList",
                        "marker",
                        "Lorg/perlonjava/runtime/runtimetypes/ControlFlowMarker;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/ControlFlowMarker",
                        "buildErrorMessage",
                        "()Ljava/lang/String;",
                        false);
                mv.visitVarInsn(Opcodes.ASTORE, msgSlot);

                // $@ = msg
                mv.visitLdcInsn("main::@");
                mv.visitVarInsn(Opcodes.ALOAD, msgSlot);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/GlobalVariable",
                        "setGlobalVariable",
                        "(Ljava/lang/String;Ljava/lang/String;)V",
                        false);

                // Replace marker with undef/empty list
                mv.visitInsn(Opcodes.POP);
                Label evalBlockList = new Label();
                Label evalBlockDone = new Label();
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                mv.visitInsn(Opcodes.ICONST_2); // RuntimeContextType.LIST
                mv.visitJumpInsn(Opcodes.IF_ICMPEQ, evalBlockList);

                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeList");
                mv.visitInsn(Opcodes.DUP);
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeScalar");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "<init>", "()V", false);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/runtimetypes/RuntimeList", "<init>", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)V", false);
                mv.visitJumpInsn(Opcodes.GOTO, evalBlockDone);

                mv.visitLabel(evalBlockList);
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeList");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/runtimetypes/RuntimeList", "<init>", "()V", false);
                mv.visitLabel(evalBlockDone);

                // Materialize return value in local slot and jump to endCatch with empty stack.
                mv.visitVarInsn(Opcodes.ASTORE, returnListSlot);

                // Skip the success epilogue that clears $@.
                // This path represents an eval failure (bad goto/other marker),
                // so $@ must be preserved.
                mv.visitJumpInsn(Opcodes.GOTO, endCatch);
            }
            // TODO: Check ctx.javaClassInfo loop stack, if non-empty, jump to innermost loop handler
            // For now, just propagate (return to caller)
            
            // Normal return
            mv.visitLabel(normalReturn);

            // The RuntimeList is currently on stack when coming from the trampoline checks.
            // When jumping here from the initial isNonLocalGoto check, we need to reload it.
            // To normalize both paths, store any on-stack value and then reload from the slot.
            mv.visitVarInsn(Opcodes.ASTORE, returnListSlot);
            }  // End of if (ENABLE_TAILCALL_TRAMPOLINE)

            if (useTryCatch) {
                // --------------------------------
                // End of the try block (includes epilogue/trampoline)
                // --------------------------------
                mv.visitLabel(tryEnd);

                // Clear $@ on successful completion of eval (nested evals may have set it).
                mv.visitLdcInsn("main::@");
                mv.visitLdcInsn("");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/GlobalVariable",
                        "setGlobalVariable",
                        "(Ljava/lang/String;Ljava/lang/String;)V", false);

                // Jump over the catch block if no exception occurs
                mv.visitJumpInsn(Opcodes.GOTO, endCatch);

                // Start of the catch block
                mv.visitLabel(catchBlock);

                // The throwable object is on the stack
                // Catch the throwable
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/operators/WarnDie",
                        "catchEval",
                        "(Ljava/lang/Throwable;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);

                // Discard catchEval() return value; it only sets $@
                mv.visitInsn(Opcodes.POP);

                // Return undef/empty list from eval on error.
                Label evalCatchList = new Label();
                Label evalCatchDone = new Label();
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                mv.visitInsn(Opcodes.ICONST_2); // RuntimeContextType.LIST
                mv.visitJumpInsn(Opcodes.IF_ICMPEQ, evalCatchList);

                // Scalar/void: RuntimeList(new RuntimeScalar())
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeList");
                mv.visitInsn(Opcodes.DUP);
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeScalar");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "<init>", "()V", false);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/runtimetypes/RuntimeList", "<init>", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)V", false);
                mv.visitJumpInsn(Opcodes.GOTO, evalCatchDone);

                // List: new RuntimeList()
                mv.visitLabel(evalCatchList);
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeList");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/runtimetypes/RuntimeList", "<init>", "()V", false);
                mv.visitLabel(evalCatchDone);

                // Materialize return value in local slot.
                mv.visitVarInsn(Opcodes.ASTORE, returnListSlot);

                // End of the catch block
                mv.visitLabel(endCatch);

                // Load the return value for the method epilogue.
                mv.visitVarInsn(Opcodes.ALOAD, returnListSlot);
            } else {
                // No try/catch: ensure the method epilogue sees the return value
                // on the operand stack.
                mv.visitVarInsn(Opcodes.ALOAD, returnListSlot);
            }
            
            // Teardown local variables and environment after the return value is materialized
            Local.localTeardown(localRecord, mv);

            mv.visitInsn(Opcodes.ARETURN); // Returns an Object
            mv.visitMaxs(0, 0); // Automatically computed
            mv.visitEnd();

            // Complete the class
            cw.visitEnd();
            classData = cw.toByteArray(); // Generate the bytecode

            String bytecodeSizeDebug = System.getenv("JPERL_BYTECODE_SIZE_DEBUG");
            if (bytecodeSizeDebug != null && !bytecodeSizeDebug.isEmpty()) {
                try {
                    System.err.println("BYTECODE_SIZE class=" + className + " bytes=" + classData.length);

                    java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(classData));
                    int magic = in.readInt();
                    if (magic != 0xCAFEBABE) {
                        throw new RuntimeException("Bad class magic");
                    }
                    in.readUnsignedShort(); // minor
                    in.readUnsignedShort(); // major

                    int cpCount = in.readUnsignedShort();
                    String[] utf8 = new String[cpCount];
                    for (int i = 1; i < cpCount; i++) {
                        int tag = in.readUnsignedByte();
                        switch (tag) {
                            case 1: { // Utf8
                                int len = in.readUnsignedShort();
                                byte[] buf = new byte[len];
                                in.readFully(buf);
                                utf8[i] = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
                                break;
                            }
                            case 3: // Integer
                            case 4: // Float
                                in.readInt();
                                break;
                            case 5: // Long
                            case 6: // Double
                                in.readLong();
                                i++; // takes two cp slots
                                break;
                            case 7: // Class
                            case 8: // String
                            case 16: // MethodType
                            case 19: // Module
                            case 20: // Package
                                in.readUnsignedShort();
                                break;
                            case 9: // Fieldref
                            case 10: // Methodref
                            case 11: // InterfaceMethodref
                            case 12: // NameAndType
                            case 18: // InvokeDynamic
                                in.readUnsignedShort();
                                in.readUnsignedShort();
                                break;
                            case 15: // MethodHandle
                                in.readUnsignedByte();
                                in.readUnsignedShort();
                                break;
                            case 17: // Dynamic
                                in.readUnsignedShort();
                                in.readUnsignedShort();
                                break;
                            default:
                                throw new RuntimeException("Unknown constant pool tag: " + tag);
                        }
                    }

                    in.readUnsignedShort(); // access_flags
                    in.readUnsignedShort(); // this_class
                    in.readUnsignedShort(); // super_class

                    int interfacesCount = in.readUnsignedShort();
                    for (int i = 0; i < interfacesCount; i++) {
                        in.readUnsignedShort();
                    }

                    int fieldsCount = in.readUnsignedShort();
                    for (int i = 0; i < fieldsCount; i++) {
                        in.readUnsignedShort();
                        in.readUnsignedShort();
                        in.readUnsignedShort();
                        int ac = in.readUnsignedShort();
                        for (int a = 0; a < ac; a++) {
                            in.readUnsignedShort();
                            int alen = in.readInt();
                            in.skipBytes(alen);
                        }
                    }

                    int methodsCount = in.readUnsignedShort();
                    long maxCodeLen = -1;
                    for (int i = 0; i < methodsCount; i++) {
                        in.readUnsignedShort(); // access
                        int nameIdx = in.readUnsignedShort();
                        int descIdx = in.readUnsignedShort();
                        String mName = (nameIdx > 0 && nameIdx < utf8.length) ? utf8[nameIdx] : ("#" + nameIdx);
                        String mDesc = (descIdx > 0 && descIdx < utf8.length) ? utf8[descIdx] : ("#" + descIdx);
                        int ac = in.readUnsignedShort();
                        long codeLen = -1;
                        for (int a = 0; a < ac; a++) {
                            int attrNameIdx = in.readUnsignedShort();
                            String attrName = (attrNameIdx > 0 && attrNameIdx < utf8.length) ? utf8[attrNameIdx] : null;
                            int alen = in.readInt();
                            if ("Code".equals(attrName)) {
                                in.readUnsignedShort(); // max_stack
                                in.readUnsignedShort(); // max_locals
                                codeLen = Integer.toUnsignedLong(in.readInt());
                                // Skip rest of Code attribute
                                in.skipBytes((int) codeLen);
                                int exCount = in.readUnsignedShort();
                                in.skipBytes(exCount * 8);
                                int codeAttrCount = in.readUnsignedShort();
                                for (int ca = 0; ca < codeAttrCount; ca++) {
                                    in.readUnsignedShort();
                                    int calen = in.readInt();
                                    in.skipBytes(calen);
                                }
                            } else {
                                in.skipBytes(alen);
                            }
                        }

                        if (codeLen >= 0) {
                            if (codeLen > maxCodeLen) {
                                maxCodeLen = codeLen;
                            }
                            boolean isApply = "apply".equals(mName);
                            boolean isLarge = codeLen >= 30000;
                            if (isApply || isLarge) {
                                System.err.println("BYTECODE_SIZE method=" + mName + mDesc + " code_bytes=" + codeLen);
                            }
                        }
                    }

                    if (maxCodeLen >= 0) {
                        System.err.println("BYTECODE_SIZE max_code_bytes=" + maxCodeLen);
                    }
                } catch (Throwable ignored) {
                }
            }

            if (asmDebug && asmDebugClassMatches && asmDebugClassFilter != null && !asmDebugClassFilter.isEmpty()) {
                try {
                    Path outDir = Paths.get(System.getProperty("java.io.tmpdir"), "perlonjava-asm");
                    Path outFile = outDir.resolve(className + ".class");
                    Files.createDirectories(outFile.getParent());
                    Files.write(outFile, classData);
                } catch (Throwable ignored) {
                }
            }

            boolean interpreterActive = ctx.compilerOptions.useInterpreter
                    || System.getenv("JPERL_EVAL_USE_INTERPRETER") != null;
            if (ctx.compilerOptions.disassembleEnabled && !interpreterActive) {
                // Disassemble the JVM bytecode for debugging purposes.
                // Skip when interpreter mode is active — PerlLanguageProvider prints
                // the interpreter bytecode instead.
                ClassReader cr = new ClassReader(classData);
                PrintWriter pw = new PrintWriter(System.out);
                TraceClassVisitor tcv = new TraceClassVisitor(pw);
                cr.accept(tcv, ClassReader.EXPAND_FRAMES);
                pw.flush();
            }

            if (asmDebug && !disableFrames && asmDebugClassMatches && asmDebugClassFilter != null && !asmDebugClassFilter.isEmpty()) {
                try {
                    ClassReader cr = new ClassReader(classData);

                    PrintWriter pw = new PrintWriter(System.err);
                    TraceClassVisitor tcv = new TraceClassVisitor(pw);
                    cr.accept(tcv, ClassReader.EXPAND_FRAMES);
                    pw.flush();

                    PrintWriter verifyPw = new PrintWriter(System.err);
                    String thisClassNameDot = className.replace('/', '.');
                    final byte[] verifyClassData = classData;
                    ClassLoader verifyLoader = new ClassLoader(GlobalVariable.globalClassLoader) {
                        @Override
                        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                            if (name.equals(thisClassNameDot)) {
                                synchronized (getClassLoadingLock(name)) {
                                    Class<?> loaded = findLoadedClass(name);
                                    if (loaded == null) {
                                        loaded = defineClass(name, verifyClassData, 0, verifyClassData.length);
                                    }
                                    if (resolve) {
                                        resolveClass(loaded);
                                    }
                                    return loaded;
                                }
                            }
                            return super.loadClass(name, resolve);
                        }
                    };

                    try {
                        CheckClassAdapter.verify(cr, verifyLoader, true, verifyPw);
                    } catch (Throwable verifyErr) {
                        verifyErr.printStackTrace(verifyPw);
                    }
                    verifyPw.flush();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }

            if (asmDebug && disableFrames && asmDebugClassMatches) {
                try {
                    ClassReader cr = new ClassReader(classData);

                    PrintWriter pw = new PrintWriter(System.err);
                    TraceClassVisitor tcv = new TraceClassVisitor(pw);
                    cr.accept(tcv, ClassReader.EXPAND_FRAMES);
                    pw.flush();

                    PrintWriter verifyPw = new PrintWriter(System.err);
                    String thisClassNameDot = className.replace('/', '.');
                    final byte[] verifyClassData = classData;
                    ClassLoader verifyLoader = new ClassLoader(GlobalVariable.globalClassLoader) {
                        @Override
                        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                            if (name.equals(thisClassNameDot)) {
                                synchronized (getClassLoadingLock(name)) {
                                    Class<?> loaded = findLoadedClass(name);
                                    if (loaded == null) {
                                        loaded = defineClass(name, verifyClassData, 0, verifyClassData.length);
                                    }
                                    if (resolve) {
                                        resolveClass(loaded);
                                    }
                                    return loaded;
                                }
                            }
                            return super.loadClass(name, resolve);
                        }
                    };

                    boolean verified = false;
                    try {
                        CheckClassAdapter.verify(cr, verifyLoader, true, verifyPw);
                        verified = true;
                    } catch (Throwable verifyErr) {
                        verifyErr.printStackTrace(verifyPw);
                    }
                    verifyPw.flush();

                    // Always run a classloader-free verifier pass to get a concrete method/instruction index.
                    // CheckClassAdapter.verify() can fail or be noisy when it cannot load generated anon classes.
                    debugAnalyzeWithBasicInterpreter(cr, verifyPw);
                    verifyPw.flush();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }

            if (asmDebug && !disableFrames && asmDebugClassMatches
                    && asmDebugClassFilter != null && !asmDebugClassFilter.isEmpty()) {
                try {
                    ClassReader cr = new ClassReader(classData);

                    PrintWriter pw = new PrintWriter(System.err);
                    TraceClassVisitor tcv = new TraceClassVisitor(pw);
                    cr.accept(tcv, ClassReader.EXPAND_FRAMES);
                    pw.flush();

                    PrintWriter verifyPw = new PrintWriter(System.err);
                    String thisClassNameDot = className.replace('/', '.');
                    final byte[] verifyClassData = classData;
                    ClassLoader verifyLoader = new ClassLoader(GlobalVariable.globalClassLoader) {
                        @Override
                        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                            if (name.equals(thisClassNameDot)) {
                                synchronized (getClassLoadingLock(name)) {
                                    Class<?> loaded = findLoadedClass(name);
                                    if (loaded == null) {
                                        loaded = defineClass(name, verifyClassData, 0, verifyClassData.length);
                                    }
                                    if (resolve) {
                                        resolveClass(loaded);
                                    }
                                    return loaded;
                                }
                            }
                            return super.loadClass(name, resolve);
                        }
                    };

                    try {
                        CheckClassAdapter.verify(cr, verifyLoader, true, verifyPw);
                    } catch (Throwable verifyErr) {
                        verifyErr.printStackTrace(verifyPw);
                    }
                    verifyPw.flush();

                    debugAnalyzeWithBasicInterpreter(cr, verifyPw);
                    verifyPw.flush();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
            return classData;
        } catch (ArrayIndexOutOfBoundsException e) {
            if (!disableFrames) {
                throw e;
            }
            e.printStackTrace();
            throw new PerlCompilerException(
                    ast.getIndex(),
                    "Internal compiler error: Failed to generate bytecode. " +
                            "This may indicate an issue with the generated code structure. " +
                            "Original error: " + e.getMessage(),
                    ctx.errorUtil);
        } catch (NegativeArraySizeException e) {
            // Special handling for ASM frame computation errors
            // Print stack trace to help debug the issue
            e.printStackTrace();
            if (asmDebug) {
                try {
                    String failingClass = (ctx != null && ctx.javaClassInfo != null)
                            ? ctx.javaClassInfo.javaClassName
                            : "<unknown>";
                    int failingIndex = ast != null ? ast.getIndex() : -1;
                    String fileName = (ctx != null && ctx.errorUtil != null) ? ctx.errorUtil.getFileName() : "<unknown>";
                    System.err.printf(
                            "ASM frame compute crash in generated class: %s (astIndex=%d, at %s)\n",
                            failingClass,
                            failingIndex,
                            fileName);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                try {
                    if (ctx != null && ctx.javaClassInfo != null) {
                        String previousName = ctx.javaClassInfo.javaClassName;
                        ctx.javaClassInfo = new JavaClassInfo();
                        ctx.javaClassInfo.javaClassName = previousName;
                        ctx.clearContextCache();
                    }
                    getBytecodeInternal(ctx, ast, useTryCatch, true);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
            String formattedError = String.format(
                    "ASM bytecode generation failed: %s\n" +
                            "This typically occurs when:\n" +
                            "  - The method is too complex (too many local variables or deep nesting)\n" +
                            "  - Invalid bytecode was generated (incorrect stack manipulation)\n" +
                            "  - The computed max stack/locals size is incorrect\n" +
                            "Consider simplifying the subroutine or breaking it into smaller parts.",
                    e.getMessage()
            );
            throw new PerlCompilerException(
                    ast.getIndex(),
                    formattedError,
                    ctx.errorUtil);
        } catch (PerlCompilerException e) {
            throw e;
        } catch (MethodTooLargeException e) {
            // Let this propagate so getBytecode() can attempt large-code refactoring and retry.
            throw e;
        } catch (RuntimeException e) {
            // Enhanced error message with debugging information
            StringBuilder errorMsg = new StringBuilder();
            errorMsg.append(String.format(
                    "Unexpected runtime error during bytecode generation\n" +
                    "Class: %s\n" +
                    "Method: %s\n" +
                    "AST Node: %s\n" +
                    "Actual bytecode size: %d bytes (limit: 65535)\n" +
                    "Error: %s\n",
                    className,
                    methodName,
                    ast.getClass().getSimpleName(),
                    classData != null ? classData.length : 0,
                    e.getMessage()
            ));
            
            // Add refactoring information if available
            if (ast instanceof BlockNode) {
                BlockNode blockNode = (BlockNode) ast;
                Object estimatedSize = blockNode.getAnnotation("estimatedBytecodeSize");
                Object skipReason = blockNode.getAnnotation("refactorSkipReason");
                
                if (estimatedSize != null) {
                    errorMsg.append(String.format("Estimated bytecode size: %s bytes\n", estimatedSize));
                }
                if (skipReason != null) {
                    errorMsg.append(String.format("Refactoring status: %s\n", skipReason));
                }
            }

            errorMsg.append("Hint: If this is a 'Method too large' error after automatic refactoring,\n");
            errorMsg.append("      the code may be too complex to compile. Consider splitting into smaller methods.");

            throw new PerlCompilerException(
                    ast.getIndex(),
                    errorMsg.toString(),
                    ctx.errorUtil,
                    e);
        }

    }

    /**
     * Loads the generated bytecode into the JVM using a global class loader.
     *
     * <p>This method uses a shared global class loader to define classes, which enables
     * direct instantiation of generated classes using NEW bytecode instructions instead
     * of reflection. This significantly improves performance for operations that create
     * many anonymous subroutines (like map, grep, etc.).</p>
     *
     * <p>The tradeoff is that classes remain loaded until the global class loader is
     * replaced (via {@link GlobalVariable#resetAllGlobals()}). This is acceptable for
     * most use cases but should be considered for long-running applications that
     * generate many unique anonymous subroutines.</p>
     *
     * @param ctx       The emitter context containing the class information
     * @param classData The bytecode of the generated class
     * @return The loaded Class object
     */
    public static Class<?> loadBytecode(EmitterContext ctx, byte[] classData) {
        // Use the global class loader to ensure all generated classes are in the same namespace
        CustomClassLoader loader = GlobalVariable.globalClassLoader;

        // Create a "Java" class name with dots instead of slashes
        String javaClassNameDot = ctx.javaClassInfo.javaClassName.replace('/', '.');

        // Define the class using the global class loader
        return loader.defineClass(javaClassNameDot, classData);
    }

    // Feature flag for interpreter fallback (enabled by default, can be disabled)
    private static final boolean USE_INTERPRETER_FALLBACK =
        System.getenv("JPERL_DISABLE_INTERPRETER_FALLBACK") == null;
    private static final boolean SHOW_FALLBACK =
        System.getenv("JPERL_SHOW_FALLBACK") != null;

    /**
     * Unified factory method that returns RuntimeCode (either CompiledCode or InterpretedCode).
     *
     * This is the NEW API that replaces createClassWithMethod() for most use cases.
     * It handles the "Method too large" exception by falling back to the interpreter.
     * The interpreter fallback is ENABLED BY DEFAULT and can be disabled by setting
     * JPERL_DISABLE_INTERPRETER_FALLBACK environment variable.
     *
     * DESIGN:
     * - Try compiler first (createClassWithMethod)
     * - On MethodTooLargeException: fall back to interpreter (unless disabled)
     * - Return CompiledCode or InterpretedCode (both extend RuntimeCode)
     * - Call sites work with RuntimeCode interface, don't need to know which backend was used
     *
     * @param ctx         The emitter context containing information for code generation
     * @param ast         The abstract syntax tree representing the method body
     * @param useTryCatch Flag to enable try-catch in the generated class (for eval operator)
     * @return RuntimeCode that can be either CompiledCode or InterpretedCode
     */
    public static RuntimeCode createRuntimeCode(
            EmitterContext ctx, Node ast, boolean useTryCatch) {
        try {
            // Try compiler path
            Class<?> generatedClass = createClassWithMethod(ctx, ast, useTryCatch);
            if (SHOW_FALLBACK) {
                System.err.println("Note: JVM compilation succeeded.");
            }
            return wrapAsCompiledCode(generatedClass, ctx);

        } catch (MethodTooLargeException e) {
            if (USE_INTERPRETER_FALLBACK) {
                // Fall back to interpreter
                System.err.println("Note: Method too large after AST splitting, using interpreter backend.");
                return compileToInterpreter(ast, ctx, useTryCatch);
            }

            // If interpreter fallback disabled, re-throw to use existing AST splitter logic
            throw e;
        }
    }

    /**
     * Wrap a compiled Class<?> as CompiledCode.
     *
     * This performs the same reflection steps that SubroutineParser.java currently does:
     * 1. Get constructor
     * 2. Create instance (codeObject)
     * 3. Get MethodHandle for apply method
     * 4. Set __SUB__ field
     * 5. Return CompiledCode wrapper
     *
     * @param generatedClass The compiled JVM class
     * @param ctx            The compiler context
     * @return CompiledCode wrapping the compiled class
     */
    private static CompiledCode wrapAsCompiledCode(Class<?> generatedClass, EmitterContext ctx) {
        try {
            // Get the constructor (may have parameters for captured variables)
            String[] env = (ctx.capturedEnv != null) ? ctx.capturedEnv : ctx.symbolTable.getVariableNames();

            // Build parameter types for constructor
            Class<?>[] parameterTypes = new Class<?>[Math.max(0, env.length - skipVariables)];
            for (int i = skipVariables; i < env.length; i++) {
                String descriptor = getVariableDescriptor(env[i]);
                String className = descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
                parameterTypes[i - skipVariables] = Class.forName(className);
            }

            Constructor<?> constructor = generatedClass.getConstructor(parameterTypes);

            // For now, we don't instantiate - that happens later when captured vars are available
            // This is used for the factory pattern where the caller provides the parameters
            // So we return a CompiledCode with null codeObject and null methodHandle
            // The caller will instantiate it with the captured variables

            // Actually, let's check if there are NO captured variables, then we can instantiate now
            Object codeObject = null;
            java.lang.invoke.MethodHandle methodHandle = null;

            if (parameterTypes.length == 0) {
                // No captured variables, can instantiate now
                codeObject = constructor.newInstance();

                // Get MethodHandle for apply method
                methodHandle = RuntimeCode.lookup.findVirtual(
                    generatedClass, "apply", RuntimeCode.methodType
                );

                // Set __SUB__ field
                java.lang.reflect.Field field = generatedClass.getDeclaredField("__SUB__");
                RuntimeScalar selfRef = new RuntimeScalar();
                selfRef.type = RuntimeScalarType.CODE;
                // Note: ctx doesn't have prototype field, it's set separately by caller
                selfRef.value = new CompiledCode(methodHandle, codeObject, null, generatedClass, ctx);
                field.set(codeObject, selfRef);

                return (CompiledCode) selfRef.value;
            } else {
                // Has captured variables - caller must instantiate later
                // Return a CompiledCode with null codeObject/methodHandle
                // The caller will fill these in via reflection (see SubroutineParser pattern)
                return new CompiledCode(null, null, null, generatedClass, ctx);
            }

        } catch (Exception e) {
            throw new PerlCompilerException(
                "Failed to wrap compiled class: " + e.getMessage());
        }
    }

    /**
     * Compile AST to interpreter bytecode.
     *
     * This is the fallback path when JVM bytecode generation hits the 65535 byte limit.
     * The interpreter has no size limits because it doesn't generate JVM bytecode.
     *
     * @param ast         The AST to compile
     * @param ctx         The compiler context
     * @param useTryCatch Whether to use try-catch (for eval)
     * @return InterpretedCode ready to execute
     */
    private static InterpretedCode compileToInterpreter(
            Node ast, EmitterContext ctx, boolean useTryCatch) {

        // Create bytecode compiler
        BytecodeCompiler compiler =
            new BytecodeCompiler(
                ctx.errorUtil.getFileName(),
                1,  // line number
                ctx.errorUtil
            );

        // Compile AST to interpreter bytecode
        InterpretedCode code = compiler.compile(ast);

        // Handle captured variables if needed (for closures)
        if (ctx.capturedEnv != null && ctx.capturedEnv.length > skipVariables) {
            // Extract captured variables from context
            // Note: This is a simplified version - full implementation would need to
            // access the actual RuntimeBase objects from the symbol table
            RuntimeBase[] capturedVars =
                new RuntimeBase[ctx.capturedEnv.length - skipVariables];

            // For now, initialize with undef (actual values will be set by caller)
            for (int i = 0; i < capturedVars.length; i++) {
                capturedVars[i] = new RuntimeScalar();
            }

            code = code.withCapturedVars(capturedVars);
        }

        // Note: prototype will be set by caller if needed
        // code.prototype is set via RuntimeCode fields

        return code;
    }

    public static void debugInspectClass(Class<?> generatedClass) {
        System.out.println("Class Information for: " + generatedClass.getName());
        System.out.println("===========================================");

        // Print superclass
        System.out.println("\nSuperclass:");
        System.out.println(generatedClass.getSuperclass().getName());

        // Print implemented interfaces
        System.out.println("\nImplemented Interfaces:");
        for (Class<?> iface : generatedClass.getInterfaces()) {
            System.out.println(iface.getName());
        }

        // Print fields
        System.out.println("\nFields:");
        for (Field field : generatedClass.getDeclaredFields()) {
            // Get modifiers (public, private, etc)
            String modifiers = Modifier.toString(field.getModifiers());

            System.out.printf("%s %s %s%n",
                    modifiers,
                    field.getType().getName(),
                    field.getName()
            );

            // Print annotations if any
            for (Annotation annotation : field.getAnnotations()) {
                System.out.println("  @" + annotation.annotationType().getSimpleName());
            }
        }

        // Print constructors
        System.out.println("\nConstructors:");
        for (Constructor<?> constructor : generatedClass.getDeclaredConstructors()) {
            System.out.println(Modifier.toString(constructor.getModifiers()) + " " +
                    generatedClass.getSimpleName() + "(");

            // Print parameter types
            Parameter[] params = constructor.getParameters();
            for (int i = 0; i < params.length; i++) {
                System.out.println("  " + params[i].getType().getName() + " " + params[i].getName() +
                        (i < params.length - 1 ? "," : ""));
            }
            System.out.println(")");
        }

        // Print methods
        System.out.println("\nMethods:");
        for (Method method : generatedClass.getDeclaredMethods()) {
            // Get modifiers
            String modifiers = Modifier.toString(method.getModifiers());

            // Get return type
            String returnType = method.getReturnType().getName();

            System.out.printf("%s %s %s(%n",
                    modifiers,
                    returnType,
                    method.getName()
            );

            // Print parameters
            Parameter[] params = method.getParameters();
            for (int i = 0; i < params.length; i++) {
                System.out.println("  " + params[i].getType().getName() + " " + params[i].getName() +
                        (i < params.length - 1 ? "," : ""));
            }
            System.out.println(")");

            // Print annotations if any
            for (Annotation annotation : method.getAnnotations()) {
                System.out.println("  @" + annotation.annotationType().getSimpleName());
            }
        }
    }
}

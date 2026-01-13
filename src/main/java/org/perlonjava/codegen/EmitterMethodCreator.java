package org.perlonjava.codegen;

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
import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.astrefactor.LargeBlockRefactorer;
import org.perlonjava.parser.Parser;
import org.perlonjava.runtime.GlobalVariable;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;

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
            return "Lorg/perlonjava/runtime/RuntimeScalar;";
        }

        // Extract the first character of the variable name
        char firstChar = varName.charAt(0);

        // Use a switch statement to determine the descriptor based on the first character
        return switch (firstChar) {
            case '%' -> "Lorg/perlonjava/runtime/RuntimeHash;";
            case '@' -> "Lorg/perlonjava/runtime/RuntimeArray;";
            default -> "Lorg/perlonjava/runtime/RuntimeScalar;";
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
            return "org/perlonjava/runtime/RuntimeScalar";
        }

        // Extract the first character of the variable name
        char firstChar = varName.charAt(0);

        // Use a switch statement to determine the class name based on the first character
        return switch (firstChar) {
            case '%' -> "org/perlonjava/runtime/RuntimeHash";
            case '@' -> "org/perlonjava/runtime/RuntimeArray";
            default -> "org/perlonjava/runtime/RuntimeScalar";
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
        try {
            return getBytecodeInternal(ctx, ast, useTryCatch, false);
        } catch (MethodTooLargeException tooLarge) {
            // Best-effort: try a more aggressive large-block refactor pass, then retry once.
            // This is only enabled when refactoring is explicitly requested.
            String largecode = System.getenv("JPERL_LARGECODE");
            boolean refactorEnabled = largecode != null && largecode.equalsIgnoreCase("refactor");
            if (refactorEnabled && ast instanceof BlockNode blockAst) {
                try {
                    LargeBlockRefactorer.forceRefactorForCodegen(blockAst);

                    // Reset JavaClassInfo to avoid reusing partially-resolved Labels.
                    if (ctx != null && ctx.javaClassInfo != null) {
                        String previousName = ctx.javaClassInfo.javaClassName;
                        ctx.javaClassInfo = new JavaClassInfo();
                        ctx.javaClassInfo.javaClassName = previousName;
                        ctx.clearContextCache();
                    }

                    return getBytecodeInternal(ctx, ast, useTryCatch, false);
                } catch (MethodTooLargeException retryTooLarge) {
                    throw retryTooLarge;
                } catch (Throwable ignored) {
                    // Fall through to the original exception.
                }
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

            // Create a ClassWriter with COMPUTE_FRAMES and COMPUTE_MAXS options for automatic frame and max
            // stack size calculation
            // Only disable COMPUTE_FRAMES for the explicit diagnostic pass (disableFrames=true).
            // In normal operation (even when JPERL_ASM_DEBUG is enabled) we want COMPUTE_FRAMES,
            // otherwise the generated class may fail verification on modern JVMs.
            // For the diagnostic (disableFrames=true) pass we disable COMPUTE_FRAMES so ASM does not
            // attempt to compute stack map frames (which may crash if invalid bytecode was generated).
            // Keep COMPUTE_MAXS enabled so max stack/locals are correct and we can run analyzers.
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
            cw.visitField(Opcodes.ACC_PUBLIC, "__SUB__", "Lorg/perlonjava/runtime/RuntimeScalar;", null, null).visitEnd();

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
                            "(Lorg/perlonjava/runtime/RuntimeArray;I)Lorg/perlonjava/runtime/RuntimeList;",
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
            org.perlonjava.astvisitor.TempLocalCountVisitor tempCountVisitor = 
                new org.perlonjava.astvisitor.TempLocalCountVisitor();
            ast.accept(tempCountVisitor);
            int preInitTempLocalsCount = Math.max(128, tempCountVisitor.getMaxTempCount() + 64);  // Add buffer
            for (int i = preInitTempLocalsStart; i < preInitTempLocalsStart + preInitTempLocalsCount; i++) {
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitVarInsn(Opcodes.ASTORE, i);
            }

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

            if (useTryCatch) {
                ctx.logDebug("useTryCatch");

                // --------------------------------
                // Start of try-catch block
                // --------------------------------

                Label tryStart = new Label();
                Label tryEnd = new Label();
                Label catchBlock = new Label();
                Label endCatch = new Label();

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
                        "org/perlonjava/runtime/GlobalVariable",
                        "setGlobalVariable",
                        "(Ljava/lang/String;Ljava/lang/String;)V", false);

                ast.accept(visitor);

                // Handle the return value
                ctx.logDebug("Return the last value");
                mv.visitLabel(ctx.javaClassInfo.returnLabel); // "return" from other places arrive here

                // --------------------------------
                // End of the try block
                // --------------------------------
                mv.visitLabel(tryEnd);

                // Jump over the catch block if no exception occurs
                mv.visitJumpInsn(Opcodes.GOTO, endCatch);

                // Start of the catch block
                mv.visitLabel(catchBlock);

                // The throwable object is on the stack
                // Catch the throwable
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/operators/WarnDie",
                        "catchEval",
                        "(Ljava/lang/Throwable;)Lorg/perlonjava/runtime/RuntimeScalar;", false);

                // End of the catch block
                mv.visitLabel(endCatch);

                // --------------------------------
                // End of try-catch block
                // --------------------------------
            } else {
                // No try-catch block is used

                ast.accept(visitor);

                // Handle the return value
                ctx.logDebug("Return the last value");
                mv.visitLabel(ctx.javaClassInfo.returnLabel); // "return" from other places arrive here
            }

            // Transform the value in the stack to RuntimeList BEFORE local teardown
            // This ensures that array/hash elements are expanded before local variables are restored
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "getList", "()Lorg/perlonjava/runtime/RuntimeList;", false);

            // Phase 3: Check for control flow markers
            // RuntimeList is on stack after getList()
            
            if (ENABLE_TAILCALL_TRAMPOLINE) {
            // First, check if it's a TAILCALL (global trampoline)
            Label tailcallLoop = new Label();
            Label notTailcall = new Label();
            Label normalReturn = new Label();
            
            mv.visitInsn(Opcodes.DUP);  // Duplicate for checking
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 
                    "org/perlonjava/runtime/RuntimeList", 
                    "isNonLocalGoto", 
                    "()Z", 
                    false);
            mv.visitJumpInsn(Opcodes.IFEQ, normalReturn);  // Not marked, return normally
            
            // Marked: check if TAILCALL
            // Cast to RuntimeControlFlowList to access getControlFlowType()
            mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/RuntimeControlFlowList");
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/RuntimeControlFlowList",
                    "getControlFlowType",
                    "()Lorg/perlonjava/runtime/ControlFlowType;",
                    false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/ControlFlowType",
                    "ordinal",
                    "()I",
                    false);
            mv.visitInsn(Opcodes.ICONST_4);  // TAILCALL.ordinal() = 4
            mv.visitJumpInsn(Opcodes.IF_ICMPNE, notTailcall);
            
            // TAILCALL trampoline loop
            mv.visitLabel(tailcallLoop);
            // Cast to RuntimeControlFlowList to access getTailCallCodeRef/getTailCallArgs
            mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/RuntimeControlFlowList");
            
            if (DEBUG_CONTROL_FLOW) {
                // Debug: print what we're about to process
                mv.visitInsn(Opcodes.DUP);
                mv.visitFieldInsn(Opcodes.GETFIELD,
                        "org/perlonjava/runtime/RuntimeControlFlowList",
                        "marker",
                        "Lorg/perlonjava/runtime/ControlFlowMarker;");
                mv.visitLdcInsn("TRAMPOLINE_LOOP");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/ControlFlowMarker",
                        "debugPrint",
                        "(Ljava/lang/String;)V",
                        false);
            }
            
            // Extract codeRef and args
            // Use allocated slots from symbol table
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/RuntimeControlFlowList",
                    "getTailCallCodeRef",
                    "()Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
            mv.visitVarInsn(Opcodes.ASTORE, ctx.javaClassInfo.tailCallCodeRefSlot);
            
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/RuntimeControlFlowList",
                    "getTailCallArgs",
                    "()Lorg/perlonjava/runtime/RuntimeArray;",
                    false);
            mv.visitVarInsn(Opcodes.ASTORE, ctx.javaClassInfo.tailCallArgsSlot);
            
            // Re-invoke: RuntimeCode.apply(codeRef, "tailcall", args, context)
            mv.visitVarInsn(Opcodes.ALOAD, ctx.javaClassInfo.tailCallCodeRefSlot);
            mv.visitLdcInsn("tailcall");
            mv.visitVarInsn(Opcodes.ALOAD, ctx.javaClassInfo.tailCallArgsSlot);
            mv.visitVarInsn(Opcodes.ILOAD, 2);  // context (from parameter)
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/RuntimeCode",
                    "apply",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;Lorg/perlonjava/runtime/RuntimeBase;I)Lorg/perlonjava/runtime/RuntimeList;",
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
                    "org/perlonjava/runtime/RuntimeList",
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
            mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/RuntimeControlFlowList");
            mv.visitInsn(Opcodes.DUP);
            
            if (DEBUG_CONTROL_FLOW) {
                // Debug: print the control flow type
                mv.visitInsn(Opcodes.DUP);
                mv.visitFieldInsn(Opcodes.GETFIELD,
                        "org/perlonjava/runtime/RuntimeControlFlowList",
                        "marker",
                        "Lorg/perlonjava/runtime/ControlFlowMarker;");
                mv.visitLdcInsn("TRAMPOLINE_CHECK");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/ControlFlowMarker",
                        "debugPrint",
                        "(Ljava/lang/String;)V",
                        false);
            }
            
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/RuntimeControlFlowList",
                    "getControlFlowType",
                    "()Lorg/perlonjava/runtime/ControlFlowType;",
                    false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/ControlFlowType",
                    "ordinal",
                    "()I",
                    false);
            mv.visitInsn(Opcodes.ICONST_4);
            mv.visitJumpInsn(Opcodes.IF_ICMPEQ, tailcallLoop);  // Loop if still TAILCALL
            // Otherwise fall through to normalReturn (propagate other control flow)
            
            // Not TAILCALL: check if we're inside a loop and should jump to loop handler
            mv.visitLabel(notTailcall);
            // TODO: Check ctx.javaClassInfo loop stack, if non-empty, jump to innermost loop handler
            // For now, just propagate (return to caller)
            
            // Normal return
            mv.visitLabel(normalReturn);
            }  // End of if (ENABLE_TAILCALL_TRAMPOLINE)
            
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

            if (ctx.compilerOptions.disassembleEnabled) {
                // Disassemble the bytecode for debugging purposes
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
            if (ast instanceof org.perlonjava.astnode.BlockNode) {
                org.perlonjava.astnode.BlockNode blockNode = (org.perlonjava.astnode.BlockNode) ast;
                Object estimatedSize = blockNode.getAnnotation("estimatedBytecodeSize");
                Object skipReason = blockNode.getAnnotation("refactorSkipReason");
                
                if (estimatedSize != null) {
                    errorMsg.append(String.format("Estimated bytecode size: %s bytes\n", estimatedSize));
                }
                if (skipReason != null) {
                    errorMsg.append(String.format("Refactoring status: %s\n", skipReason));
                }
            }
            
            errorMsg.append("Hint: If this is a 'Method too large' error, try enabling JPERL_LARGECODE=refactor");
            
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

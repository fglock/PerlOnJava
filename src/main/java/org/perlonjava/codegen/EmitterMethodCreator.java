package org.perlonjava.codegen;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodTooLargeException;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;
import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.codegen.TempSlotPlan;
import org.perlonjava.astrefactor.LargeBlockRefactorer;
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
            // This is safe for small code (it won't run) and unblocks very large Perl5 tests.
            if (ast instanceof BlockNode blockAst) {
                MethodTooLargeException last = tooLarge;
                for (int attempt = 0; attempt < 3; attempt++) {
                    try {
                        LargeBlockRefactorer.forceRefactorForCodegenEvenIfDisabled(blockAst);

                        // Reset JavaClassInfo to avoid reusing partially-resolved Labels.
                        if (ctx != null && ctx.javaClassInfo != null) {
                            String previousName = ctx.javaClassInfo.javaClassName;
                            ctx.javaClassInfo = new JavaClassInfo();
                            ctx.javaClassInfo.javaClassName = previousName;
                            ctx.clearContextCache();
                        }

                        return getBytecodeInternal(ctx, ast, useTryCatch, false);
                    } catch (MethodTooLargeException retryTooLarge) {
                        last = retryTooLarge;
                    } catch (Throwable ignored) {
                        // Fall through to the original exception.
                        break;
                    }
                }
                throw last;
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

            // Add a simple no-arg constructor so reflection-based instantiation works
            MethodVisitor noArgCtor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            noArgCtor = ctx.javaClassInfo.wrapWithLocalSlotTracking(noArgCtor, Opcodes.ACC_PUBLIC, "()V");
            noArgCtor.visitCode();
            noArgCtor.visitVarInsn(Opcodes.ALOAD, 0);
            noArgCtor.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "java/lang/Object",
                    "<init>",
                    "()V",
                    false);
            noArgCtor.visitInsn(Opcodes.RETURN);
            noArgCtor.visitMaxs(0, 0);
            noArgCtor.visitEnd();

            // Add a constructor with parameters for initializing the fields
            // Include ALL env slots (even nulls) so signature matches caller expectations
            StringBuilder constructorDescriptor = new StringBuilder("(");
            for (int i = skipVariables; i < env.length; i++) {
                String descriptor = getVariableDescriptor(env[i]);  // handles nulls gracefully
                constructorDescriptor.append(descriptor);
            }
            constructorDescriptor.append(")V");
            ctx.logDebug("constructorDescriptor: " + constructorDescriptor);
            if (!"()V".contentEquals(constructorDescriptor)) {
                ctx.mv =
                        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", constructorDescriptor.toString(), null, null);
                MethodVisitor mv = ctx.javaClassInfo.wrapWithLocalSlotTracking(ctx.mv, Opcodes.ACC_PUBLIC, constructorDescriptor.toString());
                ctx.mv = mv;
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
            }

            // Create the public "apply" method for the generated class
            ctx.logDebug("Create the method");
            // Emit apply into a MethodNode so we can insert local initializers at the top
            // based on the observed slot-kind hashmap (no random ranges, no ceilings).
            ctx.javaClassInfo.clearLocalSlotKinds();
            MethodNode applyMethod =
                    new MethodNode(
                            Opcodes.ASM9,
                            Opcodes.ACC_PUBLIC,
                            "apply",
                            "(Lorg/perlonjava/runtime/RuntimeArray;I)Lorg/perlonjava/runtime/RuntimeList;",
                            null,
                            new String[]{"java/lang/Exception"});
            MethodVisitor mv = ctx.javaClassInfo.wrapWithLocalSlotTracking(
                    applyMethod,
                    Opcodes.ACC_PUBLIC,
                    "(Lorg/perlonjava/runtime/RuntimeArray;I)Lorg/perlonjava/runtime/RuntimeList;");
            ctx.mv = mv;

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
            // Never allow temporaries to start in the reserved parameter slots.
            // Slot 0 = this, slot 1 = @_, slot 2 = wantarray/context.
            int startIndex = Math.max(env.length, skipVariables);
            ctx.symbolTable.resetLocalVariableIndex(startIndex);

            // AST prepass: reserve temp slots for hotspot nodes so codegen reuses stable slots.
            // This avoids mixing int/ref in the same JVM local and avoids TOP reads.
            org.perlonjava.astvisitor.ApplyTempSlotPlannerVisitor planner =
                    new org.perlonjava.astvisitor.ApplyTempSlotPlannerVisitor(ctx);
            ast.accept(planner);

            // Initialize preplanned temp slots with the correct kind.
            for (TempSlotPlan.SlotInfo slotInfo : ctx.tempSlotPlan.allAssignedSlots()) {
                if (slotInfo.kind() == TempSlotPlan.TempKind.INT) {
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitVarInsn(Opcodes.ISTORE, slotInfo.slot());
                } else {
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    mv.visitVarInsn(Opcodes.ASTORE, slotInfo.slot());
                }
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

            int intSpillSlotCount = System.getenv("JPERL_INT_SPILL_SLOTS") != null
                    ? Integer.parseInt(System.getenv("JPERL_INT_SPILL_SLOTS"))
                    : 16;
            ctx.javaClassInfo.intSpillSlots = new int[intSpillSlotCount];
            ctx.javaClassInfo.intSpillTop = 0;
            for (int i = 0; i < intSpillSlotCount; i++) {
                int slot = ctx.symbolTable.allocateLocalVariable();
                ctx.javaClassInfo.intSpillSlots[i] = slot;
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitVarInsn(Opcodes.ISTORE, slot);
            }

            // Setup local variables and environment for the method BEFORE pre-initializing
            // generic temp locals. localSetup may allocate INT locals (dynamicIndex) and write
            // them with ISTORE; if we pre-initialize a broad temp-local range first using ASTORE,
            // we can accidentally mark the same slot as REF and later as INT, producing MIXED.
            Local.localRecord localRecord = Local.localSetup(ctx, ast, mv);
            
            // Create a label for the return point
            ctx.javaClassInfo.returnLabel = new Label();

            // Prepare to visit the AST to generate bytecode
            EmitterVisitor visitor = new EmitterVisitor(ctx);

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
            Local.localTeardown(localRecord, ctx, mv);

            mv.visitInsn(Opcodes.ARETURN); // Returns an Object
            mv.visitMaxs(0, 0); // Automatically computed
            mv.visitEnd();

            // Deterministic local initialization (no random ranges):
            // Run ASM's control-flow analyzer on the emitted method and initialize exactly the
            // local slots that are TOP at any ALOAD/ILOAD site.
            InsnList initLocals = new InsnList();
            enum InitValueKind { INT_ZERO, REF_NULL, RUNTIME_SCALAR_UNDEF }
            java.util.Map<Integer, InitValueKind> initSlots = new java.util.HashMap<>();
            Throwable initSlotsFailure = null;

            // Analyzer needs a correct maxLocals and can get confused by stale FrameNodes.
            // Strip FRAME nodes and compute maxLocals from the instruction stream.
            int computedMaxLocals = skipVariables;
            for (AbstractInsnNode n = applyMethod.instructions.getFirst(); n != null; ) {
                AbstractInsnNode next = n.getNext();
                if (n.getType() == AbstractInsnNode.FRAME) {
                    applyMethod.instructions.remove(n);
                    n = next;
                    continue;
                }
                if (n instanceof VarInsnNode vin) {
                    computedMaxLocals = Math.max(computedMaxLocals, vin.var + 1);
                } else if (n instanceof IincInsnNode iinc) {
                    computedMaxLocals = Math.max(computedMaxLocals, iinc.var + 1);
                }
                n = next;
            }
            applyMethod.maxLocals = Math.max(applyMethod.maxLocals, computedMaxLocals);

            // Analyzer allocates frames based on maxStack. The MethodNode currently has maxStack=0
            // (we emitted visitMaxs(0,0) and let ClassWriter compute it later). Provide a
            // deterministic upper bound so Analyzer can run.
            int computedMaxStack = 4096;
            applyMethod.maxStack = Math.max(applyMethod.maxStack, computedMaxStack);

            // Cheap prepass: find locals that are used as the receiver for auto-increment
            // operations (x++ / ++x), so we can initialize them as undef RuntimeScalar.
            java.util.Set<Integer> autoIncReceiverSlots = new java.util.HashSet<>();
            java.util.Set<Integer> runtimeScalarReceiverSlots = new java.util.HashSet<>();
            for (AbstractInsnNode n = applyMethod.instructions.getFirst(); n != null; n = n.getNext()) {
                if (!(n instanceof VarInsnNode vin) || vin.getOpcode() != Opcodes.ALOAD) {
                    continue;
                }
                AbstractInsnNode next = n.getNext();
                if (!(next instanceof MethodInsnNode min)) {
                    continue;
                }
                if (min.getOpcode() != Opcodes.INVOKEVIRTUAL) {
                    continue;
                }

                if ("org/perlonjava/runtime/RuntimeScalar".equals(min.owner)) {
                    runtimeScalarReceiverSlots.add(vin.var);
                }

                // Only treat auto-inc/-dec receiver slots specially (need RuntimeScalar object instead of null).
                if (!("postAutoIncrement".equals(min.name)
                        || "preAutoIncrement".equals(min.name)
                        || "postAutoDecrement".equals(min.name)
                        || "preAutoDecrement".equals(min.name))) {
                    continue;
                }

                AbstractInsnNode prev = n.getPrevious();
                for (int back = 0; back < 32 && prev != null; back++, prev = prev.getPrevious()) {
                    int t = prev.getType();
                    if (t == AbstractInsnNode.LABEL || t == AbstractInsnNode.LINE || t == AbstractInsnNode.FRAME) {
                        continue;
                    }
                    if (prev.getOpcode() == Opcodes.CHECKCAST) {
                        continue;
                    }
                    if (prev instanceof VarInsnNode prevVin && prevVin.getOpcode() == Opcodes.ALOAD) {
                        if (prevVin.var >= skipVariables) {
                            autoIncReceiverSlots.add(prevVin.var);
                        }
                        break;
                    }
                    // Stop if we hit another call or a store; receiver is no longer directly traceable.
                    int op = prev.getOpcode();
                    if (op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKESTATIC || op == Opcodes.INVOKEINTERFACE
                            || op == Opcodes.ASTORE || op == Opcodes.ISTORE) {
                        break;
                    }
                }
            }

            try {
                // Cheap prepass: find locals that are used as the receiver for auto-increment
                // operations (x++ / ++x), so we can initialize them as undef RuntimeScalar.
                org.objectweb.asm.tree.analysis.Frame<BasicValue>[] frames = null;
                AnalyzerException lastAnalyzerException = null;
                int[] maxStackAttempts = new int[]{applyMethod.maxStack, 16384, 65536};
                for (int attempt : maxStackAttempts) {
                    applyMethod.maxStack = Math.max(applyMethod.maxStack, attempt);
                    try {
                        Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
                        @SuppressWarnings("unchecked")
                        org.objectweb.asm.tree.analysis.Frame<BasicValue>[] analyzed = analyzer.analyze(ctx.javaClassInfo.javaClassName, applyMethod);
                        frames = analyzed;
                        lastAnalyzerException = null;
                        break;
                    } catch (AnalyzerException ae) {
                        lastAnalyzerException = ae;
                    }
                }

                if (frames == null) {
                    throw lastAnalyzerException;
                }

                for (int i = 0; i < applyMethod.instructions.size(); i++) {
                    AbstractInsnNode insn = applyMethod.instructions.get(i);
                    if (!(insn instanceof VarInsnNode vin)) {
                        continue;
                    }
                    int op = vin.getOpcode();
                    if (op != Opcodes.ALOAD && op != Opcodes.ILOAD) {
                        continue;
                    }
                    int slot = vin.var;
                    if (slot < skipVariables) {
                        continue;
                    }

                    boolean isAutoIncReceiver = (op == Opcodes.ALOAD) && autoIncReceiverSlots.contains(slot);
                    boolean isRuntimeScalarReceiver = (op == Opcodes.ALOAD) && runtimeScalarReceiverSlots.contains(slot);

                    org.objectweb.asm.tree.analysis.Frame<BasicValue> f = frames[i];
                    if (f == null) {
                        continue;
                    }
                    if (slot >= f.getLocals()) {
                        InitValueKind kind = (op == Opcodes.ILOAD) ? InitValueKind.INT_ZERO : InitValueKind.REF_NULL;
                        if (op == Opcodes.ALOAD && isAutoIncReceiver) {
                            kind = InitValueKind.RUNTIME_SCALAR_UNDEF;
                        } else if (op == Opcodes.ALOAD && isRuntimeScalarReceiver) {
                            kind = InitValueKind.RUNTIME_SCALAR_UNDEF;
                        }
                        initSlots.put(slot, kind);
                        continue;
                    }
                    BasicValue v = f.getLocal(slot);
                    if (v != null && v != BasicValue.UNINITIALIZED_VALUE) {
                        continue;
                    }
                    InitValueKind kind = (op == Opcodes.ILOAD) ? InitValueKind.INT_ZERO : InitValueKind.REF_NULL;
                    if (op == Opcodes.ALOAD && isAutoIncReceiver) {
                        kind = InitValueKind.RUNTIME_SCALAR_UNDEF;
                    } else if (op == Opcodes.ALOAD && isRuntimeScalarReceiver) {
                        kind = InitValueKind.RUNTIME_SCALAR_UNDEF;
                    }
                    initSlots.put(slot, kind);
                }
            } catch (AnalyzerException ae) {
                // Fall back to legacy behavior (no init insertion) if analysis fails.
                initSlotsFailure = ae;
            } catch (RuntimeException re) {
                // Defensive: avoid losing required initializers due to unexpected analyzer/runtime issues.
                initSlotsFailure = re;
            }

            // Deterministic fallback for huge methods where ASM analysis is unreliable:
            // initialize exactly the local slots that are actually loaded (ALOAD/ILOAD/IINC).
            if (initSlotsFailure != null) {
                java.util.Map<Integer, InitValueKind> fallbackSlots = new java.util.HashMap<>();
                for (AbstractInsnNode n = applyMethod.instructions.getFirst(); n != null; n = n.getNext()) {
                    if (n instanceof VarInsnNode vin) {
                        int op = vin.getOpcode();
                        int slot = vin.var;
                        if (slot < skipVariables) {
                            continue;
                        }
                        if (op == Opcodes.ALOAD) {
                            fallbackSlots.put(slot, (autoIncReceiverSlots.contains(slot) || runtimeScalarReceiverSlots.contains(slot))
                                    ? InitValueKind.RUNTIME_SCALAR_UNDEF
                                    : InitValueKind.REF_NULL);
                        } else if (op == Opcodes.ILOAD) {
                            fallbackSlots.put(slot, InitValueKind.INT_ZERO);
                        }
                    } else if (n instanceof IincInsnNode iinc) {
                        int slot = iinc.var;
                        if (slot >= skipVariables) {
                            fallbackSlots.put(slot, InitValueKind.INT_ZERO);
                        }
                    }
                }
                if (!fallbackSlots.isEmpty()) {
                    initSlotsFailure = null;
                    initSlots.clear();
                    initSlots.putAll(fallbackSlots);
                }
            }

            String initDebug = System.getenv("JPERL_INIT_LOCALS_DEBUG");
            if (initDebug != null && !initDebug.isEmpty()) {
                System.err.println("[jperl] init-locals class=" + ctx.javaClassInfo.javaClassName
                        + " computedMaxLocals=" + computedMaxLocals
                        + " maxLocals=" + applyMethod.maxLocals
                        + " slots=" + new java.util.TreeMap<>(initSlots)
                        + (initSlotsFailure == null ? "" : " failure=" + initSlotsFailure.getClass().getSimpleName() + ":" + initSlotsFailure.getMessage()));
            }

java.util.function.Supplier<InsnList> buildInitInsnList = () -> {
    InsnList list = new InsnList();
    for (java.util.Map.Entry<Integer, InitValueKind> e : initSlots.entrySet()) {
        int slot = e.getKey();
        InitValueKind kind = e.getValue();
        switch (kind) {
            case REF_NULL -> {
                list.add(new InsnNode(Opcodes.ACONST_NULL));
                list.add(new VarInsnNode(Opcodes.ASTORE, slot));
            }
            case INT_ZERO -> {
                list.add(new InsnNode(Opcodes.ICONST_0));
                list.add(new VarInsnNode(Opcodes.ISTORE, slot));
            }
            case RUNTIME_SCALAR_UNDEF -> {
                list.add(new TypeInsnNode(Opcodes.NEW, "org/perlonjava/runtime/RuntimeScalar"));
                list.add(new InsnNode(Opcodes.DUP));
                list.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        "org/perlonjava/runtime/RuntimeScalar",
                        "<init>",
                        "()V",
                        false));
                list.add(new VarInsnNode(Opcodes.ASTORE, slot));
            }
        }
    }
    return list;
};

// 1) Method entry: insert before the first instruction node. This ensures initialization
// runs on the normal entry path.
AbstractInsnNode methodEntry = applyMethod.instructions.getFirst();
if (methodEntry != null && !initSlots.isEmpty()) {
    applyMethod.instructions.insertBefore(methodEntry, buildInitInsnList.get());
}

// 2) Exception handler entry: jumps to handler labels bypass method entry init.
// Insert the same init list right after each handler label.
if (applyMethod.tryCatchBlocks != null && !applyMethod.tryCatchBlocks.isEmpty() && !initSlots.isEmpty()) {
    for (TryCatchBlockNode tcb : applyMethod.tryCatchBlocks) {
        if (tcb == null || tcb.handler == null) {
            continue;
        }
        AbstractInsnNode handlerLabel = tcb.handler;
        AbstractInsnNode afterHandlerLabel = handlerLabel.getNext();
        if (afterHandlerLabel == null) {
            continue;
        }
        applyMethod.instructions.insertBefore(afterHandlerLabel, buildInitInsnList.get());
    }
}


// Write apply into the class.
applyMethod.accept(cw);

// Complete the class
cw.visitEnd();
classData = cw.toByteArray(); // Generate the bytecode

String bytecodeSizeDebug = System.getenv("JPERL_BYTECODE_SIZE_DEBUG");
if (bytecodeSizeDebug != null && !bytecodeSizeDebug.isEmpty()) {
    try {
        System.err.println("BYTECODE_SIZE class=" + className + " bytes=" + classData.length);
    } catch (Throwable ignored) {
    }
}

            // Optional: persist generated class bytes for external inspection.
            if (asmDebug && asmDebugClassMatches && asmDebugClassFilter != null && !asmDebugClassFilter.isEmpty()) {
                try {
                    Path outDir = Paths.get(System.getProperty("java.io.tmpdir"), "perlonjava-asm");
                    Path outFile = outDir.resolve(className + ".class");
                    Files.createDirectories(outFile.getParent());
                    Files.write(outFile, classData);
                } catch (Throwable ignored) {
                }
            }

            // Optional: print disassembly.
            if (ctx.compilerOptions.disassembleEnabled) {
                try {
                    ClassReader cr = new ClassReader(classData);
                    PrintWriter pw = new PrintWriter(System.out);
                    TraceClassVisitor tcv = new TraceClassVisitor(pw);
                    cr.accept(tcv, ClassReader.EXPAND_FRAMES);
                    pw.flush();
                } catch (Throwable ignored) {
                }
            }

            // Optional: verify/analyze the generated class.
            if (asmDebug && asmDebugClassMatches) {
                try {
                    ClassReader cr = new ClassReader(classData);
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

                    if (disableFrames) {
                        debugAnalyzeWithBasicInterpreter(cr, verifyPw);
                        verifyPw.flush();
                    }
                } catch (Throwable ignored) {
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

package org.perlonjava.codegen;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.util.TraceClassVisitor;
import org.perlonjava.astnode.Node;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.runtime.GlobalVariable;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.HashSet;
import java.util.Set;

/**
 * EmitterMethodCreator is a utility class that uses the ASM library to dynamically generate Java
 * classes with specific methods. It is designed to create classes with methods that can be used for
 * runtime evaluation of expressions or statements in a simulated Perl environment.
 */
public class EmitterMethodCreator implements Opcodes {

    // Number of local variables to skip when processing a closure (this, @_, wantarray)
    public static int skipVariables = 3;

    // Counter for generating unique class names
    public static int classCounter = 0;

    // Generate a unique internal class name
    public static String generateClassName() {
        return "org/perlonjava/anon" + classCounter++;
    }

    /**
     * Generates a descriptor string based on the prefix of a Perl variable name.
     *
     * @param varName The Perl variable name, which typically starts with a special character
     *                indicating its type (e.g., '%', '@', or '$').
     * @return A descriptor string representing the type of the Perl variable.
     */
    public static String getVariableDescriptor(String varName) {
        // Ensure the variable name is not empty
        if (varName == null || varName.isEmpty()) {
            throw new PerlCompilerException("Variable name cannot be null or empty");
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
        // Ensure the variable name is not empty
        if (varName == null || varName.isEmpty()) {
            throw new PerlCompilerException("Variable name cannot be null or empty");
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
        try {
            String[] env = ctx.symbolTable.getVariableNames();

            // Create a ClassWriter with COMPUTE_FRAMES and COMPUTE_MAXS options for automatic frame and max
            // stack size calculation
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ctx.cw = cw;

            // The context type is determined by the caller.
            ctx.contextType = RuntimeContextType.RUNTIME;

            ByteCodeSourceMapper.setDebugInfoFileName(ctx);

            // Define the class with version, access flags, name, signature, superclass, and interfaces
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, ctx.javaClassInfo.javaClassName, null, "java/lang/Object", null);
            ctx.logDebug("Create class: " + ctx.javaClassInfo.javaClassName);

            // Add instance fields to the class for closure variables
            for (String fieldName : env) {
                String descriptor = getVariableDescriptor(fieldName);
                ctx.logDebug("Create instance field: " + descriptor);
                cw.visitField(Opcodes.ACC_PUBLIC, fieldName, descriptor, null, null).visitEnd();
            }

            // Add instance field for __SUB__ code reference
            cw.visitField(Opcodes.ACC_PUBLIC, "__SUB__", "Lorg/perlonjava/runtime/RuntimeScalar;", null, null).visitEnd();

            // Add a constructor with parameters for initializing the fields
            StringBuilder constructorDescriptor = new StringBuilder("(");
            for (int i = skipVariables; i < env.length; i++) {
                String descriptor = getVariableDescriptor(env[i]);
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
            
            // Use MethodNode to capture instructions for analysis and modification
            MethodNode methodNode = new MethodNode(
                    Opcodes.ACC_PUBLIC,
                    "apply",
                    "(Lorg/perlonjava/runtime/RuntimeArray;I)Lorg/perlonjava/runtime/RuntimeList;",
                    null,
                    new String[]{"java/lang/Exception"});
            
            ctx.mv = methodNode;
            mv = ctx.mv;

            // Generate the subroutine block
            mv.visitCode();

            // Initialize local variables with closure values from instance fields
            // Skip some indices because they are reserved for special arguments (this, "@_" and call
            // context)
            for (int i = skipVariables; i < env.length; i++) {
                // Skip null entries (gaps in sparse array)
                if (env[i] == null) {
                    continue;
                }
                String descriptor = getVariableDescriptor(env[i]);
                mv.visitVarInsn(Opcodes.ALOAD, 0); // Load 'this'
                ctx.logDebug("Init closure variable: " + descriptor);
                mv.visitFieldInsn(Opcodes.GETFIELD, ctx.javaClassInfo.javaClassName, env[i], descriptor);
                mv.visitVarInsn(Opcodes.ASTORE, i);
            }

            // Note: We no longer pre-initialize slots with NULL values.
            // Each allocateLocalVariable() call is immediately followed by a store instruction,
            // so slots are properly initialized when allocated during bytecode emission.

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
                mv.visitTryCatchBlock(tryStart, tryEnd, catchBlock, "java/lang/Exception");

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

                // The exception object is on the stack
                // Catch the exception
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/operators/WarnDie",
                        "catchEval",
                        "(Ljava/lang/Exception;)Lorg/perlonjava/runtime/RuntimeScalar;", false);

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

            // Teardown local variables and environment after the return value is materialized
            Local.localTeardown(localRecord, mv);

            mv.visitInsn(Opcodes.ARETURN); // Returns an Object
            mv.visitMaxs(0, 0); // Automatically computed
            mv.visitEnd();

            // Inject prologue initialization for local variables
            // Start index is env.length because slots 0..env.length-1 are used by 'this', arguments, and closure variables
            injectPrologueInitialization(methodNode, env.length);

            // Write the generated method to the ClassWriter
            methodNode.accept(cw);

            // Complete the class
            cw.visitEnd();
            byte[] classData = cw.toByteArray(); // Generate the bytecode

            if (ctx.compilerOptions.disassembleEnabled) {
                // Disassemble the bytecode for debugging purposes
                ClassReader cr = new ClassReader(classData);
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                TraceClassVisitor tcv = new TraceClassVisitor(pw);
                cr.accept(tcv, 0);

                System.out.println(sw);
            }
            return classData;
        } catch (ArrayIndexOutOfBoundsException e) {
            // Print full stack trace for debugging
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
        } catch (RuntimeException e) {
            throw new PerlCompilerException(
                    ast.getIndex(),
                    "Unexpected runtime error during bytecode generation",
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

    /**
     * Injects prologue initialization code for local variable slots in the generated method.
     * 
     * <p>This method addresses the JVM bytecode verification requirement that all local variables
     * must be initialized before use. The JVM verifier performs data-flow analysis and will reject
     * bytecode where a variable might be read before being written, resulting in a {@code VerifyError}
     * with message "Bad local variable type".
     * 
     * <h3>Problem Background</h3>
     * <p>In dynamically generated bytecode for Perl subroutines, control flow constructs like loops
     * with {@code next}, {@code last}, or {@code redo} can create paths where a local variable slot
     * is accessed before any value has been stored. The JVM verifier cannot determine the type of
     * such uninitialized slots, causing verification failure.
     * 
     * <h3>Solution Approach</h3>
     * <p>This method uses ASM's {@link Analyzer} with {@link SourceInterpreter} to perform data-flow
     * analysis on the generated bytecode. It categorizes each local variable slot by its usage type:
     * <ul>
     *   <li><b>Scalar slots:</b> Used with {@code RuntimeScalar} or {@code RuntimeScalarReadOnly} methods</li>
     *   <li><b>Other reference slots:</b> Object references not identified as scalars</li>
     *   <li><b>Primitive slots:</b> int, boolean, byte, char, short (use ILOAD/ISTORE)</li>
     *   <li><b>Double slots:</b> double values (use DLOAD/DSTORE, occupy 2 slots)</li>
     *   <li><b>Long slots:</b> long values (use LLOAD/LSTORE, occupy 2 slots)</li>
     *   <li><b>Float slots:</b> float values (use FLOAD/FSTORE)</li>
     * </ul>
     * 
     * <p>Based on this analysis, appropriate initialization instructions are injected at the
     * beginning of the method:
     * <ul>
     *   <li>Scalars: {@code new RuntimeScalar()} - creates a mutable undef value</li>
     *   <li>Other references: {@code null}</li>
     *   <li>Primitives: {@code 0} (ICONST_0)</li>
     *   <li>Doubles: {@code 0.0d} (DCONST_0)</li>
     *   <li>Longs: {@code 0L} (LCONST_0)</li>
     *   <li>Floats: {@code 0.0f} (FCONST_0)</li>
     * </ul>
     * 
     * <h3>Important: Mutable Scalars</h3>
     * <p>Scalar slots are initialized with {@code new RuntimeScalar()} rather than the cached
     * {@code RuntimeScalarCache.scalarUndef} because the latter is a {@code RuntimeScalarReadOnly}
     * instance. When Perl code like {@code my $x = 0; $x++;} executes, the assignment uses
     * {@code addToScalar()} which calls {@code set()} on the target. Calling {@code set()} on a
     * read-only scalar throws "Modification of a read-only value attempted".
     * 
     * @param methodNode the ASM MethodNode containing the method's bytecode instructions
     * @param startIndex the first local variable slot index to initialize (slots before this
     *                   are reserved for 'this', method parameters, and closure variables)
     * @see Analyzer
     * @see SourceInterpreter
     */
    private static void injectPrologueInitialization(MethodNode methodNode, int startIndex) {
        // 1. Compute Max Locals
        int maxLocal = 0;
        for (AbstractInsnNode insn : methodNode.instructions) {
            if (insn instanceof VarInsnNode) {
                int var = ((VarInsnNode) insn).var;
                if (var > maxLocal) maxLocal = var;
            }
        }

        if (maxLocal < startIndex) return;

        // Set maxLocals and maxStack for the Analyzer
        // We need to set them because we used visitMaxs(0, 0) which sets them to 0 in the MethodNode.
        // Analyzer relies on these values to initialize Frames.
        methodNode.maxLocals = maxLocal + 1;
        methodNode.maxStack = 2048; // Safe upper bound for analysis

        // 2. Analyze usage - track slot types to detect conflicts
        // A slot may be reused for different types in different code paths.
        // We track all types seen for each slot and only initialize slots with a single consistent type.
        Set<Integer> refSlots = new HashSet<>();      // slots used with ALOAD/ASTORE
        Set<Integer> scalarSlots = new HashSet<>();   // ref slots confirmed as RuntimeScalar
        Set<Integer> primitiveSlots = new HashSet<>(); // slots used with ILOAD/ISTORE
        Set<Integer> doubleSlots = new HashSet<>();   // slots used with DLOAD/DSTORE
        Set<Integer> floatSlots = new HashSet<>();    // slots used with FLOAD/FSTORE
        Set<Integer> longSlots = new HashSet<>();     // slots used with LLOAD/LSTORE
        Set<Integer> conflictSlots = new HashSet<>(); // slots with conflicting types - skip these

        // First pass: categorize all slot usages and detect conflicts
        for (AbstractInsnNode insn : methodNode.instructions) {
            if (insn instanceof VarInsnNode) {
                VarInsnNode varInsn = (VarInsnNode) insn;
                int var = varInsn.var;
                if (var >= startIndex) {
                    int op = varInsn.getOpcode();
                    boolean isRef = (op == Opcodes.ALOAD || op == Opcodes.ASTORE);
                    boolean isDouble = (op == Opcodes.DLOAD || op == Opcodes.DSTORE);
                    boolean isFloat = (op == Opcodes.FLOAD || op == Opcodes.FSTORE);
                    boolean isLong = (op == Opcodes.LLOAD || op == Opcodes.LSTORE);
                    boolean isPrimitive = !isRef && !isDouble && !isFloat && !isLong && op != Opcodes.RET;

                    // Check for conflicts with existing categorization
                    if (isRef) {
                        if (primitiveSlots.contains(var) || doubleSlots.contains(var) || 
                            floatSlots.contains(var) || longSlots.contains(var)) {
                            conflictSlots.add(var);
                        }
                        refSlots.add(var);
                    } else if (isDouble) {
                        if (refSlots.contains(var) || primitiveSlots.contains(var) || 
                            floatSlots.contains(var) || longSlots.contains(var)) {
                            conflictSlots.add(var);
                        }
                        doubleSlots.add(var);
                    } else if (isFloat) {
                        if (refSlots.contains(var) || primitiveSlots.contains(var) || 
                            doubleSlots.contains(var) || longSlots.contains(var)) {
                            conflictSlots.add(var);
                        }
                        floatSlots.add(var);
                    } else if (isLong) {
                        if (refSlots.contains(var) || primitiveSlots.contains(var) || 
                            doubleSlots.contains(var) || floatSlots.contains(var)) {
                            conflictSlots.add(var);
                        }
                        longSlots.add(var);
                    } else if (isPrimitive) {
                        if (refSlots.contains(var) || doubleSlots.contains(var) || 
                            floatSlots.contains(var) || longSlots.contains(var)) {
                            conflictSlots.add(var);
                        }
                        primitiveSlots.add(var);
                    }
                }
            }
        }

        // Second pass: use Analyzer to identify RuntimeScalar slots among reference slots
        Analyzer<SourceValue> analyzer = new Analyzer<>(new SourceInterpreter());
        try {
            Frame<SourceValue>[] frames = analyzer.analyze("org/perlonjava/anon/Generated", methodNode);
            AbstractInsnNode[] insns = methodNode.instructions.toArray();

            for (int i = 0; i < insns.length; i++) {
                AbstractInsnNode insn = insns[i];
                if (insn == null) continue;

                if (insn.getType() == AbstractInsnNode.METHOD_INSN) {
                    MethodInsnNode methodInsn = (MethodInsnNode) insn;
                    Frame<SourceValue> frame = frames[i];
                    if (frame == null) continue; // unreachable code

                    // Check Receiver for instance methods
                    if (methodInsn.getOpcode() != Opcodes.INVOKESTATIC) {
                        org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(methodInsn.desc);
                        int stackIndex = frame.getStackSize() - args.length - 1;
                        if (stackIndex >= 0) {
                            SourceValue receiverValue = frame.getStack(stackIndex);
                            if ("org/perlonjava/runtime/RuntimeScalar".equals(methodInsn.owner) ||
                                    "org/perlonjava/runtime/RuntimeScalarReadOnly".equals(methodInsn.owner)) {
                                markSlots(receiverValue, scalarSlots);
                            }
                        }
                    }
                }
            }
        } catch (AnalyzerException e) {
            // Analysis failed - proceed with basic type detection only
            System.err.println("Analyzer failed: " + e.getMessage());
        }

        // 3. Inject Initialization - only initialize reference slots, skip primitives
        // Primitive slots don't need prologue initialization - they just need consistent types.
        // The original VerifyError was about uninitialized reference types in control flow paths.
        // Initializing primitive slots can cause conflicts when slots are reused for different types.
        InsnList prologue = new InsnList();
        for (int i = startIndex; i <= maxLocal; i++) {
            // Skip slots with conflicting types - they cannot be safely initialized
            if (conflictSlots.contains(i)) {
                continue;
            }
            
            // Only initialize reference slots - skip primitive types entirely
            if (scalarSlots.contains(i)) {
                // Create a new mutable RuntimeScalar() instead of using the read-only scalarUndef
                // This is necessary because the slot may be assigned to via addToScalar which calls set()
                prologue.add(new TypeInsnNode(Opcodes.NEW, "org/perlonjava/runtime/RuntimeScalar"));
                prologue.add(new InsnNode(Opcodes.DUP));
                prologue.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/RuntimeScalar", "<init>", "()V", false));
                prologue.add(new VarInsnNode(Opcodes.ASTORE, i));
            } else if (refSlots.contains(i)) {
                // Non-scalar reference slot - initialize to null
                prologue.add(new InsnNode(Opcodes.ACONST_NULL));
                prologue.add(new VarInsnNode(Opcodes.ASTORE, i));
            }
            // Skip primitive slots (int, long, float, double) - they don't cause VerifyError
            // and initializing them can conflict with slot reuse for different types
        }

        methodNode.instructions.insert(prologue);
    }

    /**
     * Marks local variable slots that are associated with a given {@link SourceValue}.
     * 
     * <p>This helper method is used during data-flow analysis to track which local variable
     * slots contribute to a particular value on the operand stack. When we identify that a
     * value is used as a receiver for {@code RuntimeScalar} method calls, we trace back to
     * find which local variable slots produced that value.
     * 
     * <p>The {@link SourceValue} from ASM's {@link SourceInterpreter} contains a set of
     * instructions that could have produced the value. This method extracts the variable
     * indices from any {@link VarInsnNode} instructions (ALOAD, ILOAD, etc.) in that set
     * and adds them to the provided slots collection.
     * 
     * <h3>Example Usage</h3>
     * <p>When analyzing a method call like {@code $scalar.set(...)}, the receiver on the
     * stack might have been loaded from local variable slot 5. The SourceValue for that
     * stack position would contain the ALOAD instruction, and this method would add 5
     * to the scalar slots set, ensuring slot 5 gets initialized as a RuntimeScalar.
     * 
     * @param value the SourceValue from data-flow analysis representing a stack value
     * @param slots the set to which local variable indices will be added
     * @see SourceValue
     * @see SourceInterpreter
     */
    private static void markSlots(SourceValue value, Set<Integer> slots) {
        for (AbstractInsnNode source : value.insns) {
            if (source instanceof VarInsnNode) {
                slots.add(((VarInsnNode) source).var);
            }
        }
    }
}

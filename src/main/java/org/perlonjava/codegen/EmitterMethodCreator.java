package org.perlonjava.codegen;

import org.objectweb.asm.*;
import org.objectweb.asm.util.TraceClassVisitor;
import org.perlonjava.astnode.Node;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * EmitterMethodCreator is a utility class that uses the ASM library to dynamically generate Java
 * classes with specific methods. It is designed to create classes with methods that can be used for
 * runtime evaluation of expressions or statements in a simulated Perl environment.
 */
public class EmitterMethodCreator implements Opcodes {

    // Number of local variables to skip when processing a closure (this, @_, wantarray)
    public static int skipVariables = 3;

    // Counter for generating unique class names
    static int classCounter = 0;

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

        String[] env = ctx.symbolTable.getVariableNames();

        // Create a ClassWriter with COMPUTE_FRAMES and COMPUTE_MAXS options for automatic frame and max
        // stack size calculation
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        ctx.cw = cw;

        // The context type is determined by the caller.
        ctx.contextType = RuntimeContextType.RUNTIME;

        DebugInfo.setDebugInfoFileName(ctx);

        // Define the class with version, access flags, name, signature, superclass, and interfaces
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, ctx.javaClassInfo.javaClassName, null, "java/lang/Object", null);
        ctx.logDebug("Create class: " + ctx.javaClassInfo.javaClassName);

        // Add instance fields to the class for closure variables
        for (String fieldName : env) {
            String descriptor = getVariableDescriptor(fieldName);
            ctx.logDebug("Create instance field: " + descriptor);
            cw.visitField(Opcodes.ACC_PUBLIC, fieldName, descriptor, null, null).visitEnd();
        }

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
            String descriptor = getVariableDescriptor(env[i]);
            mv.visitVarInsn(Opcodes.ALOAD, 0); // Load 'this'
            ctx.logDebug("Init closure variable: " + descriptor);
            mv.visitFieldInsn(Opcodes.GETFIELD, ctx.javaClassInfo.javaClassName, env[i], descriptor);
            mv.visitVarInsn(Opcodes.ASTORE, i);
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
            mv.visitTryCatchBlock(tryStart, tryEnd, catchBlock, "java/lang/Exception");

            mv.visitLabel(tryStart);
            // --------------------------------
            // Start of the try block
            // --------------------------------

            // Set $@ to an empty string if no exception occurs
            mv.visitLdcInsn("main::@");
            mv.visitLdcInsn("");
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/GlobalContext",
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
            // Example: print the stack trace of the caught exception
            //   mv.visitMethodInsn(
            //      Opcodes.INVOKEVIRTUAL, "java/lang/Exception", "printStackTrace", "()V", false);

            // Convert the exception to a string
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/ErrorMessageUtil",
                    "stringifyException",
                    "(Ljava/lang/Exception;)Ljava/lang/String;", false);

            // Set the global error variable "$@" using GlobalContext.setGlobalVariable(key, value)
            mv.visitLdcInsn("main::@");
            mv.visitInsn(Opcodes.SWAP);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/GlobalContext",
                    "setGlobalVariable",
                    "(Ljava/lang/String;Ljava/lang/String;)V", false);

            // Restore the stack state to match the end of the try block if needed
            // Return "undef"
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/RuntimeScalar",
                    "undef",
                    "()Lorg/perlonjava/runtime/RuntimeScalar;", false);

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

        // Teardown local variables and environment after the method execution
        Local.localTeardown(localRecord, mv);

        // Transform the value in the stack to RuntimeList
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "getList", "()Lorg/perlonjava/runtime/RuntimeList;", true);

        mv.visitInsn(Opcodes.ARETURN); // Returns an Object
        mv.visitMaxs(0, 0); // Automatically computed
        mv.visitEnd();

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

        // Custom class loader to load generated classes.
        //
        // Note: This class loader is not cached to allow for garbage collection of
        // anonymous subroutines. This is particularly useful when creating a large
        // number of anonymous subroutines, as it helps manage memory usage by
        // allowing unused classes to be collected by the garbage collector.
        //
        CustomClassLoader loader = new CustomClassLoader(EmitterMethodCreator.class.getClassLoader());

        // Create a "Java" class name with dots instead of slashes
        String javaClassNameDot = ctx.javaClassInfo.javaClassName.replace('/', '.');

        // Define the class using the custom class loader
        return loader.defineClass(javaClassNameDot, classData);
    }
}

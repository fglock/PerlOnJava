import java.util.*;
import org.objectweb.asm.*;

/**
 * ASMMethodCreator is a utility class that uses the ASM library to dynamically generate Java
 * classes with specific methods. It is designed to create classes with methods that can be used for
 * runtime evaluation of expressions or statements in a simulated Perl environment.
 */
public class ASMMethodCreator implements Opcodes {

  // Counter for generating unique class names
  static int classCounter = 0;

  // Generate a unique internal class name
  public static String generateClassName() {
    return "org/perlito/anon" + classCounter++;
  }

  /**
   * Creates a new class with a method based on the provided context, environment, and abstract
   * syntax tree (AST).
   *
   * @param ctx The emitter context containing information for code generation.
   * @param env An array of environment variable names to be included as instance fields in the
   *     class.
   * @param ast The abstract syntax tree representing the method body.
   * @param useTryCatch Flag to enable try-catch
   * @return The generated class.
   * @throws Exception If an error occurs during class creation.
   */
  public static Class<?> createClassWithMethod(EmitterContext ctx, String[] env, Node ast, boolean useTryCatch)
      throws Exception {
    // Create a ClassWriter with COMPUTE_FRAMES and COMPUTE_MAXS options for automatic frame and max
    // stack size calculation
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

    // Ensure the context type is not VOID
    if (ctx.contextType == ContextType.VOID) {
      ctx.contextType = ContextType.SCALAR;
    }

    // Create a "Java" class name with dots instead of slashes
    String javaClassNameDot = ctx.javaClassName.replace('/', '.');

    // Set the source file name for runtime error messages
    cw.visitSource(ctx.fileName, null);

    // Define the class with version, access flags, name, signature, superclass, and interfaces
    cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, ctx.javaClassName, null, "java/lang/Object", null);

    // Add instance fields to the class for closure variables
    for (String fieldName : env) {
      if (fieldName.startsWith("%")) {
        ctx.logDebug("Create instance field: hash  " + fieldName);
        cw.visitField(Opcodes.ACC_PUBLIC, fieldName, "LRuntimeHash;", null, null).visitEnd();
      } else if (fieldName.startsWith("@")) {
        ctx.logDebug("Create instance field: array  " + fieldName);
        cw.visitField(Opcodes.ACC_PUBLIC, fieldName, "LRuntimeArray;", null, null).visitEnd();
      } else {
        ctx.logDebug("Create instance field: scalar " + fieldName);
        cw.visitField(Opcodes.ACC_PUBLIC, fieldName, "LRuntime;", null, null).visitEnd();
      }
    }

    // Add a constructor with parameters for initializing the fields
    StringBuilder constructorDescriptor = new StringBuilder("(");
    for (int i = 3; i < env.length; i++) {
      String fieldName = env[i];

      if (fieldName.startsWith("%")) {
        constructorDescriptor.append("LRuntimeHash;");
      } else if (fieldName.startsWith("@")) {
        constructorDescriptor.append("LRuntimeArray;");
      } else {
        constructorDescriptor.append("LRuntime;");
      }

    }
    constructorDescriptor.append(")V");
    ctx.logDebug("constructorDescriptor: " + constructorDescriptor.toString());
    ctx.mv =
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", constructorDescriptor.toString(), null, null);
    ctx.mv.visitCode();
    ctx.mv.visitVarInsn(Opcodes.ALOAD, 0); // Load 'this'
    ctx.mv.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        "java/lang/Object",
        "<init>",
        "()V",
        false); // Call the superclass constructor
    for (int i = 3; i < env.length; i++) {
      ctx.mv.visitVarInsn(Opcodes.ALOAD, 0); // Load 'this'
      ctx.mv.visitVarInsn(Opcodes.ALOAD, i - 2); // Load the constructor argument
      ctx.mv.visitFieldInsn(
          Opcodes.PUTFIELD, ctx.javaClassName, env[i], "LRuntime;"); // Set the instance field
    }
    ctx.mv.visitInsn(Opcodes.RETURN); // Return void
    ctx.mv.visitMaxs(0, 0); // Automatically computed
    ctx.mv.visitEnd();

    // Create the public "apply" method for the generated class
    ctx.logDebug("Create the method");
    ctx.mv =
        cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "apply",
            "(LRuntimeArray;LContextType;)LRuntimeList;",
            null,
            new String[] {"java/lang/Exception"});

    MethodVisitor mv = ctx.mv;

    // Generate the subroutine block
    mv.visitCode();

    // Initialize local variables with closure values from instance fields
    // Skip indices 0 to 2 because they are reserved for special arguments (this, "@_" and call
    // context)
    for (int i = 3; i < env.length; i++) {
      String fieldName = env[i];
      mv.visitVarInsn(Opcodes.ALOAD, 0); // Load 'this'

      if (fieldName.startsWith("%")) {
        ctx.logDebug("Init closure variable: hash  " + fieldName);
        mv.visitFieldInsn(Opcodes.GETFIELD, ctx.javaClassName, env[i], "LRuntimeHash;");
      } else if (fieldName.startsWith("@")) {
        ctx.logDebug("Init closure variable: array  " + fieldName);
        mv.visitFieldInsn(Opcodes.GETFIELD, ctx.javaClassName, env[i], "LRuntimeArray;");
      } else {
        ctx.logDebug("Init closure variable: scalar " + fieldName);
        mv.visitFieldInsn(Opcodes.GETFIELD, ctx.javaClassName, env[i], "LRuntime;");
      }

      mv.visitVarInsn(Opcodes.ASTORE, i);
    }

    // Create a label for the return point
    ctx.returnLabel = new Label();

    // Prepare to visit the AST to generate bytecode
    EmitterVisitor visitor = new EmitterVisitor(ctx);

    if (useTryCatch) {
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

      ast.accept(visitor);

      // Handle the return value
      ctx.logDebug("Return the last value");
      mv.visitLabel(ctx.returnLabel); // "return" from other places arrive here

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
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, "ErrorMessageUtil", "stringifyException", "(Ljava/lang/Exception;)Ljava/lang/String;", false);

      // Set the global error variable "$@" using Runtime.setGlobalVariable(key, value)
      mv.visitLdcInsn("$@");
      mv.visitInsn(Opcodes.SWAP);
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, "Runtime", "setGlobalVariable", "(Ljava/lang/String;Ljava/lang/String;)LRuntime;", false);
      mv.visitInsn(Opcodes.POP);    // throw away the Runtime result

      // Restore the stack state to match the end of the try block if needed
      // Return "undef"
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, "Runtime", "undef", "()LRuntime;", false);

      // End of the catch block
      mv.visitLabel(endCatch);

      // --------------------------------
      // End of try-catch block
      // --------------------------------
    } else {
      // no try-catch

      ast.accept(visitor);

      // Handle the return value
      ctx.logDebug("Return the last value");
      mv.visitLabel(ctx.returnLabel); // "return" from other places arrive here
    }

    // Transform the value in the stack to RuntimeList
    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "ContextProvider", "getList", "()LRuntimeList;", true);

    mv.visitInsn(Opcodes.ARETURN); // returns an Object
    mv.visitMaxs(0, 0); // Automatically computed
    mv.visitEnd();

    // Complete the class
    cw.visitEnd();
    byte[] classData = cw.toByteArray(); // Generate the bytecode

    // Custom class loader to load generated classes
    CustomClassLoader loader = new CustomClassLoader();
    // Define the class using the custom class loader
    return loader.defineClass(javaClassNameDot, classData);
  }

  // // Custom class loader
  // static class CustomClassLoader extends ClassLoader {
  //     public Class<?> defineClass(String name, byte[] b) {
  //         return defineClass(name, b, 0, b.length);
  //     }
  // }
}

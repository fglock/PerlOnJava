package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.perlonjava.astnode.BinaryOperatorNode;
import org.perlonjava.astnode.IdentifierNode;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.astnode.SubroutineNode;
import org.perlonjava.runtime.*;
import org.perlonjava.symbols.ScopedSymbolTable;
import org.perlonjava.symbols.SymbolTable;

import java.util.Arrays;
import java.util.Map;

/**
 * The EmitSubroutine class is responsible for handling subroutine-related operations
 * and generating the corresponding bytecode using ASM.
 */
public class EmitSubroutine {

    /**
     * Emits bytecode for a subroutine, including handling of closure variables.
     *
     * @param ctx  The context used for code emission.
     * @param node The subroutine node representing the subroutine.
     */
    static void emitSubroutine(EmitterContext ctx, SubroutineNode node) {
        ctx.logDebug("SUB start");
        if (ctx.contextType == RuntimeContextType.VOID) {
            return;
        }
        MethodVisitor mv = ctx.mv;

        // Retrieve closure variable list
        // Alternately, scan the AST for variables and capture only the ones that are used
        Map<Integer, SymbolTable.SymbolEntry> visibleVariables = ctx.symbolTable.getAllVisibleVariables();
        ctx.logDebug("AnonSub ctx.symbolTable.getAllVisibleVariables");

        // Create a new symbol table for the subroutine
        ScopedSymbolTable newSymbolTable = ctx.symbolTable.snapShot();

        String[] newEnv = newSymbolTable.getVariableNames();
        ctx.logDebug("AnonSub " + newSymbolTable);

        // Create the new method context
        EmitterContext subCtx =
                new EmitterContext(
                        new JavaClassInfo(), // Internal Java class name
                        newSymbolTable, // Closure symbolTable
                        null, // Method visitor
                        null, // Class writer
                        RuntimeContextType.RUNTIME, // Call context
                        true, // Is boxed
                        ctx.errorUtil, // Error message utility
                        ctx.compilerOptions,
                        null);
        Class<?> generatedClass =
                EmitterMethodCreator.createClassWithMethod(
                        subCtx, node.block, node.useTryCatch
                );
        String newClassNameDot = subCtx.javaClassInfo.javaClassName.replace('/', '.');
        ctx.logDebug("Generated class name: " + newClassNameDot + " internal " + subCtx.javaClassInfo.javaClassName);
        ctx.logDebug("Generated class env:  " + Arrays.toString(newEnv));
        RuntimeCode.anonSubs.put(subCtx.javaClassInfo.javaClassName, generatedClass); // Cache the class

        /* The following ASM code is equivalent to:
         *  // Get the class:
         *  Class<?> generatedClass = RuntimeCode.anonSubs.get("java.Class.Name");
         *  // Find the constructor:
         *  Constructor<?> constructor = generatedClass.getConstructor(RuntimeScalar.class, RuntimeScalar.class);
         *  // Instantiate the class:
         *  Object instance = constructor.newInstance();
         *  // Find the apply method:
         *  Method applyMethod = generatedClass.getMethod("apply", RuntimeArray.class, int.class);
         *  // Construct a CODE variable:
         *  RuntimeScalar.new(applyMethod);
         */

        int skipVariables = EmitterMethodCreator.skipVariables; // Skip (this, @_, wantarray)

        // 1. Get the class from RuntimeCode.anonSubs
        mv.visitFieldInsn(Opcodes.GETSTATIC, "org/perlonjava/runtime/RuntimeCode", "anonSubs", "Ljava/util/HashMap;");
        mv.visitLdcInsn(subCtx.javaClassInfo.javaClassName);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/util/HashMap",
                "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Class");

        // Stack after this step: [Class]

        // 2. Find the constructor (RuntimeScalar, RuntimeScalar, ...)
        mv.visitInsn(Opcodes.DUP);
        mv.visitIntInsn(
                Opcodes.BIPUSH, newEnv.length - skipVariables); // Push the length of the array
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class"); // Create a new array of Class
        for (int i = 0; i < newEnv.length - skipVariables; i++) {
            mv.visitInsn(Opcodes.DUP); // Duplicate the array reference
            mv.visitIntInsn(Opcodes.BIPUSH, i); // Push the index

            // Select Array/Hash/Scalar depending on env value
            String descriptor = EmitterMethodCreator.getVariableDescriptor(newEnv[i + skipVariables]);

            mv.visitLdcInsn(Type.getType(descriptor)); // Push the Class object for RuntimeScalar
            mv.visitInsn(Opcodes.AASTORE); // Store the Class object in the array
        }
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Class",
                "getConstructor",
                "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
                false);

        // Stack after this step: [Class, Constructor]

        // 3. Instantiate the class
        mv.visitIntInsn(
                Opcodes.BIPUSH, newEnv.length - skipVariables); // Push the length of the array
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); // Create a new array of Object

        // Load the closure variables.
        // Here we translate the "local variable" index from the current symbol table to the new symbol table
        int newIndex = 0;  // New variable index
        for (Integer currentIndex : visibleVariables.keySet()) {
            if (newIndex >= skipVariables) {
                mv.visitInsn(Opcodes.DUP); // Duplicate the array reference
                mv.visitIntInsn(Opcodes.BIPUSH, newIndex - skipVariables); // Push the new index
                mv.visitVarInsn(Opcodes.ALOAD, currentIndex); // Load the constructor argument
                mv.visitInsn(Opcodes.AASTORE); // Store the argument in the array
            }
            newIndex++;
        }
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/reflect/Constructor",
                "newInstance",
                "([Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Object");

        // Stack after this step: [Class, Constructor, Object]

        // 4. Create a CODE variable using RuntimeCode.makeCodeObject
        if (node.prototype != null) {
            mv.visitLdcInsn(node.prototype);
            mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeCode", "makeCodeObject", "(Ljava/lang/Object;Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        } else {
            mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeCode", "makeCodeObject", "(Ljava/lang/Object;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        }

        // Stack after this step: [Class, Constructor, RuntimeScalar]
        mv.visitInsn(Opcodes.SWAP); // Move the RuntimeScalar object up
        mv.visitInsn(Opcodes.POP); // Remove the Constructor

        // 5. Clean up the stack if context is VOID
        if (ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP); // Remove the RuntimeScalar object from the stack
        }

        // If the context is not VOID, the stack should contain [RuntimeScalar] (the CODE variable)
        // If the context is VOID, the stack should be empty
        ctx.logDebug("SUB end");
    }

    /**
     * Handles the postfix `()` node, which runs a subroutine.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The binary operator node representing the apply operation.
     */
    static void handleApplyOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        emitterVisitor.ctx.logDebug("handleApplyElementOperator " + node + " in context " + emitterVisitor.ctx.contextType);

        String subroutineName = "";
        if (node.left instanceof OperatorNode operatorNode && operatorNode.operator.equals("&")) {
            if (operatorNode.operand instanceof IdentifierNode identifierNode) {
                subroutineName = NameNormalizer.normalizeVariableName(identifierNode.name, emitterVisitor.ctx.symbolTable.getCurrentPackage());
                emitterVisitor.ctx.logDebug("handleApplyElementOperator subroutine " + subroutineName);
            }
        }

        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR)); // Target - left parameter: Code ref
        emitterVisitor.ctx.mv.visitLdcInsn(subroutineName);
        node.right.accept(emitterVisitor.with(RuntimeContextType.LIST)); // Right parameter: parameter list

        // Transform the value in the stack to RuntimeArray
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "getArrayOfAlias", "()Lorg/perlonjava/runtime/RuntimeArray;", true);
        emitterVisitor.pushCallContext();   // Push call context to stack
        emitterVisitor.ctx.mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/RuntimeCode",
                "apply",
                "(Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;Lorg/perlonjava/runtime/RuntimeArray;I)Lorg/perlonjava/runtime/RuntimeList;",
                false); // Generate an .apply() call
        if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
            // Transform the value in the stack to RuntimeScalar
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeList", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
        } else if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            // Remove the value from the stack
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    /**
     * Handles the `__SUB__` operator, which refers to the current subroutine.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The operator node representing the `__SUB__` operation.
     */
    static void handleSelfCallOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        emitterVisitor.ctx.logDebug("handleSelfCallOperator " + node + " in context " + emitterVisitor.ctx.contextType);

        MethodVisitor mv = emitterVisitor.ctx.mv;

        String className = emitterVisitor.ctx.javaClassInfo.javaClassName;

        // Load 'this' (the current RuntimeCode instance)
        mv.visitVarInsn(Opcodes.ALOAD, 0); // Assuming 'this' is at index 0

        // Retrieve this.__SUB__
        mv.visitFieldInsn(Opcodes.GETFIELD,
                className,    // The class containing the field (e.g., "com/example/MyClass")
                "__SUB__",     // Field name
                "Lorg/perlonjava/runtime/RuntimeScalar;");    // Field descriptor

        // Create a Perl undef if the value in the stack is null
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/RuntimeCode",
                "selfReferenceMaybeNull",
                "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                false);

        // Now we have a RuntimeScalar representing the current subroutine (__SUB__)
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            // Remove the value from the stack
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }
}

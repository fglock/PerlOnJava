package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.perlonjava.astnode.BinaryOperatorNode;
import org.perlonjava.astnode.SubroutineNode;
import org.perlonjava.runtime.RuntimeCode;
import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.ScopedSymbolTable;

import java.util.Arrays;
import java.util.Map;

public class EmitSubroutine {
    static void emitSubroutine(EmitterContext ctx, SubroutineNode node) {
        ctx.logDebug("SUB start");
        if (ctx.contextType == RuntimeContextType.VOID) {
            return;
        }
        MethodVisitor mv = ctx.mv;

        // retrieve closure variable list
        // alternately, scan the AST for variables and capture only the ones that are used
        Map<Integer, String> visibleVariables = ctx.symbolTable.getAllVisibleVariables();
        ctx.logDebug("AnonSub ctx.symbolTable.getAllVisibleVariables");

        ScopedSymbolTable newSymbolTable = new ScopedSymbolTable();
        newSymbolTable.enterScope();
        newSymbolTable.setCurrentPackage(ctx.symbolTable.getCurrentPackage());
        for (Integer index : visibleVariables.keySet()) {
            newSymbolTable.addVariable(visibleVariables.get(index));
        }
        String[] newEnv = newSymbolTable.getVariableNames();
        ctx.logDebug("AnonSub " + newSymbolTable);

        // create the new method
        EmitterContext subCtx =
                new EmitterContext(
                        EmitterMethodCreator.generateClassName(), // internal java class name
                        newSymbolTable.clone(), // closure symbolTable
                        null, // return label
                        null, // method visitor
                        null, // class writer
                        RuntimeContextType.RUNTIME, // call context
                        true, // is boxed
                        ctx.errorUtil, // error message utility
                        ctx.compilerOptions);
        Class<?> generatedClass =
                EmitterMethodCreator.createClassWithMethod(
                        subCtx, node.block, node.useTryCatch
                );
        String newClassNameDot = subCtx.javaClassName.replace('/', '.');
        ctx.logDebug("Generated class name: " + newClassNameDot + " internal " + subCtx.javaClassName);
        ctx.logDebug("Generated class env:  " + Arrays.toString(newEnv));
        RuntimeCode.anonSubs.put(subCtx.javaClassName, generatedClass); // cache the class

        /* The following ASM code is equivalent to:
         *  // get the class:
         *  Class<?> generatedClass = RuntimeCode.anonSubs.get("java.Class.Name");
         *  // Find the constructor:
         *  Constructor<?> constructor = generatedClass.getConstructor(RuntimeScalar.class, RuntimeScalar.class);
         *  // Instantiate the class:
         *  Object instance = constructor.newInstance();
         *  // Find the apply method:
         *  Method applyMethod = generatedClass.getMethod("apply", RuntimeArray.class, int.class);
         *  // construct a CODE variable:
         *  RuntimeScalar.new(applyMethod);
         */

        int skipVariables = EmitterMethodCreator.skipVariables; // skip (this, @_, wantarray)

        // 1. Get the class from RuntimeCode.anonSubs
        mv.visitFieldInsn(Opcodes.GETSTATIC, "org/perlonjava/runtime/RuntimeCode", "anonSubs", "Ljava/util/HashMap;");
        mv.visitLdcInsn(subCtx.javaClassName);
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

            // select Array/Hash/Scalar depending on env value
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
        int newIndex = 0;  // new variable index
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
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeCode", "makeCodeObject", "(Ljava/lang/Object;)Lorg/perlonjava/runtime/RuntimeScalar;", false);

        // Stack after this step: [Class, Constructor, RuntimeScalar]
        mv.visitInsn(Opcodes.SWAP); // move the RuntimeScalar object up
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
     * Handles the postfix `()` node.
     */
    static void handleApplyOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        emitterVisitor.ctx.logDebug("handleApplyElementOperator " + node + " in context " + emitterVisitor.ctx.contextType);
        EmitterVisitor scalarVisitor =
                emitterVisitor.with(RuntimeContextType.SCALAR); // execute operands in scalar context

        node.left.accept(scalarVisitor); // target - left parameter: Code ref
        node.right.accept(emitterVisitor.with(RuntimeContextType.LIST)); // right parameter: parameter list

        // Transform the value in the stack to RuntimeArray
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "getArrayOfAlias", "()Lorg/perlonjava/runtime/RuntimeArray;", true);
        emitterVisitor.pushCallContext();   // push call context to stack
        emitterVisitor.ctx.mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/RuntimeScalar",
                "apply",
                "(Lorg/perlonjava/runtime/RuntimeArray;I)Lorg/perlonjava/runtime/RuntimeList;",
                false); // generate an .apply() call
        if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
            // Transform the value in the stack to RuntimeScalar
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeList", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
        } else if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            // Remove the value from the stack
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }
}

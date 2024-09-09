package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.perlonjava.ArgumentParser;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.runtime.RuntimeCode;
import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.ScopedSymbolTable;

import java.util.Map;

public class EmitEval {
    static void handleEvalOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        // eval string

        // TODO - this can be cached and reused at runtime for performance
        // retrieve the closure variable list into "newEnv" array
        // we save all variables, because we don't yet what code we are going to compile.
        Map<Integer, String> visibleVariables = emitterVisitor.ctx.symbolTable.getAllVisibleVariables();
        emitterVisitor.ctx.logDebug("(eval) ctx.symbolTable.getAllVisibleVariables");

        ScopedSymbolTable newSymbolTable = new ScopedSymbolTable();
        newSymbolTable.enterScope();
        newSymbolTable.setCurrentPackage(emitterVisitor.ctx.symbolTable.getCurrentPackage());
        for (Integer index : visibleVariables.keySet()) {
            newSymbolTable.addVariable(visibleVariables.get(index));
        }
        String[] newEnv = newSymbolTable.getVariableNames();
        emitterVisitor.ctx.logDebug("evalStringHelper " + newSymbolTable);

        ArgumentParser.CompilerOptions compilerOptions = emitterVisitor.ctx.compilerOptions.clone();
        compilerOptions.fileName = "(eval)";

        // save the eval context in a HashMap in RuntimeScalar class
        String evalTag = "eval" + EmitterMethodCreator.classCounter++;
        // create the eval context
        EmitterContext evalCtx =
                new EmitterContext(
                        null, // internal java class name will be created at runtime
                        newSymbolTable.clone(), // clone the symbolTable
                        null, // return label
                        null, // method visitor
                        emitterVisitor.ctx.contextType, // call context
                        true, // is boxed
                        emitterVisitor.ctx.errorUtil, // error message utility
                        compilerOptions);
        RuntimeCode.evalContext.put(evalTag, evalCtx);

        // Here the compiled code will call RuntimeCode.evalStringHelper(code, evalTag) method.
        // It will compile the string and return a new Class.
        //
        // The generated method closure variables are going to be initialized in the next step.
        // Then we can call the method.

        // Retrieve the eval argument and push to the stack
        // This is the code string that we will compile into a class.
        // The string is evaluated outside the try-catch block.
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        int skipVariables = EmitterMethodCreator.skipVariables; // skip (this, @_, wantarray)

        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Stack at this step: [RuntimeScalar(String)]

        // 1. Call RuntimeCode.evalStringHelper(code, evalTag)

        // Push the evalTag String to the stack
        // the compiled code will use this tag to retrieve the compiler environment
        mv.visitLdcInsn(evalTag);
        // Stack: [RuntimeScalar(String), String]

        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/RuntimeCode",
                "evalStringHelper",
                "(Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;)Ljava/lang/Class;",
                false);

        // Stack after this step: [Class]

        // 2. Find the constructor (RuntimeScalar, RuntimeScalar, ...)
        mv.visitIntInsn(
                Opcodes.BIPUSH, newEnv.length - skipVariables); // Push the length of the array
        // Stack: [Class, int]
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class"); // Create a new array of Class
        // Stack: [Class, Class[]]

        for (int i = 0; i < newEnv.length - skipVariables; i++) {
            mv.visitInsn(Opcodes.DUP); // Duplicate the array reference
            // Stack: [Class, Class[], Class[]]

            mv.visitIntInsn(Opcodes.BIPUSH, i); // Push the index
            // Stack: [Class, Class[], Class[], int]

            // select Array/Hash/Scalar depending on env value
            String descriptor = EmitterMethodCreator.getVariableDescriptor(newEnv[i + skipVariables]);

            mv.visitLdcInsn(Type.getType(descriptor)); // Push the Class object for RuntimeScalar
            // Stack: [Class, Class[], Class[], int, Class]

            mv.visitInsn(Opcodes.AASTORE); // Store the Class object in the array
            // Stack: [Class, Class[]]
        }
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Class",
                "getConstructor",
                "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
                false);
        // Stack: [Constructor]

        // 3. Instantiate the class
        mv.visitIntInsn(
                Opcodes.BIPUSH, newEnv.length - skipVariables); // Push the length of the array
        // Stack: [Constructor, int]
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); // Create a new array of Object
        // Stack: [Constructor, Object[]]


        // Load the closure variables.
        // Here we translate the "local variable" index from the current symbol table to the new symbol table
        for (Integer index : newSymbolTable.getAllVisibleVariables().keySet()) {
            if (index >= skipVariables) {
                String varName = newEnv[index];
                mv.visitInsn(Opcodes.DUP); // Duplicate the array reference
                mv.visitIntInsn(Opcodes.BIPUSH, index - skipVariables); // Push the new index
                mv.visitVarInsn(Opcodes.ALOAD, emitterVisitor.ctx.symbolTable.getVariableIndex(varName)); // Load the constructor argument
                mv.visitInsn(Opcodes.AASTORE); // Store the argument in the array
                emitterVisitor.ctx.logDebug("Put variable " + emitterVisitor.ctx.symbolTable.getVariableIndex(varName) + " at parameter #" + (index - skipVariables) + " " + varName);
            }
        }

        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/reflect/Constructor",
                "newInstance",
                "([Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Object");

        // Stack after this step: [initialized class Instance]

        // 4. Create a CODE variable using RuntimeCode.makeCodeObject
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeCode", "makeCodeObject", "(Ljava/lang/Object;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        // Stack: [RuntimeScalar(Code)]

        mv.visitVarInsn(Opcodes.ALOAD, 1); // push @_ to the stack
        // Transform the value in the stack to RuntimeArray
        // XXX not needed
        // mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "getArrayOfAlias", "()Lorg/perlonjava/runtime/RuntimeArray;", true);

        emitterVisitor.pushCallContext();   // push call context to stack
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/RuntimeScalar",
                "apply",
                "(Lorg/perlonjava/runtime/RuntimeArray;I)Lorg/perlonjava/runtime/RuntimeList;",
                false); // generate an .apply() call

        // 5. Clean up the stack according to context
        if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
            // Transform the value in the stack to RuntimeScalar
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeList", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
        } else if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            // Remove the value from the stack
            mv.visitInsn(Opcodes.POP);
        }

        // If the context is LIST or RUNTIME, the stack should contain [RuntimeList]
        // If the context is SCALAR, the stack should contain [RuntimeScalar]
        // If the context is VOID, the stack should be empty
    }
}

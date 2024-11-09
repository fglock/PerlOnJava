package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.perlonjava.ArgumentParser;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeCode;
import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.ScopedSymbolTable;

public class EmitEval {
    /**
     * Handles the emission of bytecode for the Perl 'eval' operator.
     * This method compiles the eval string at runtime and executes it.
     *
     * @param emitterVisitor The visitor that traverses the AST
     * @param node           The OperatorNode representing the eval operation
     */
    static void handleEvalOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        emitterVisitor.ctx.logDebug("(eval) ctx.symbolTable.getAllVisibleVariables");

        // Freeze the scoped symbol table for the eval context
        ScopedSymbolTable newSymbolTable = emitterVisitor.ctx.symbolTable.snapShot();

        String[] newEnv = newSymbolTable.getVariableNames();
        emitterVisitor.ctx.logDebug("evalStringHelper " + newSymbolTable);

        // Create compiler options for the eval context
        ArgumentParser.CompilerOptions compilerOptions = emitterVisitor.ctx.compilerOptions.clone();
        compilerOptions.fileName = "(eval)";

        // Explanation of evalTag:
        // The evalTag is used as a key in RuntimeCode.evalContext map.
        // This allows us to store the EmitterContext, which can be retrieved later
        // during the actual compilation of the eval string.
        // When RuntimeCode.evalStringHelper is called at runtime,
        // it uses this evalTag to retrieve the EmitterContext.

        // Generate a unique tag for this eval operation
        String evalTag = "eval" + EmitterMethodCreator.classCounter++;

        // Create an EmitterContext for the eval.
        // This context is used to compile the eval string with the correct
        // symbol table and compiler options.
        EmitterContext evalCtx = new EmitterContext(
                null, // internal java class name will be created at runtime
                newSymbolTable,
                null, // method visitor
                null, // class writer
                emitterVisitor.ctx.contextType,
                true, // is boxed
                emitterVisitor.ctx.errorUtil,
                compilerOptions,
                new RuntimeArray());
        // Save the eval context in the static map RuntimeCode.evalContext
        RuntimeCode.evalContext.put(evalTag, evalCtx);

        // Emit code to push the eval string onto the stack
        node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        // Stack: [RuntimeScalar(String)]

        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Push the evalTag onto the stack
        mv.visitLdcInsn(evalTag);
        // Stack: [RuntimeScalar(String), String]

        // Call RuntimeCode.evalStringHelper to compile the eval string.
        // Use the evalTag to retrieve the EmitterContext from RuntimeCode.evalContext.
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/RuntimeCode",
                "evalStringHelper",
                "(Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;)Ljava/lang/Class;",
                false);
        // Stack: [Class]

        int skipVariables = EmitterMethodCreator.skipVariables; // skip (this, @_, wantarray)

        // Prepare to create the constructor for the compiled eval class
        mv.visitIntInsn(Opcodes.BIPUSH, newEnv.length - skipVariables);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
        // Stack: [Class, Class[]]

        // Push the parameter types for the constructor
        for (int i = 0; i < newEnv.length - skipVariables; i++) {
            mv.visitInsn(Opcodes.DUP);
            mv.visitIntInsn(Opcodes.BIPUSH, i);
            String descriptor = EmitterMethodCreator.getVariableDescriptor(newEnv[i + skipVariables]);
            mv.visitLdcInsn(Type.getType(descriptor));
            mv.visitInsn(Opcodes.AASTORE);
        }
        // Stack: [Class, Class[]]

        // Get the constructor for the compiled eval class
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Class",
                "getConstructor",
                "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
                false);
        // Stack: [Constructor]

        // Prepare to create an instance of the compiled eval class
        mv.visitIntInsn(Opcodes.BIPUSH, newEnv.length - skipVariables);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        // Stack: [Constructor, Object[]]

        // Push the closure variables onto the stack
        for (Integer index : newSymbolTable.getAllVisibleVariables().keySet()) {
            if (index >= skipVariables) {
                String varName = newEnv[index];
                mv.visitInsn(Opcodes.DUP);
                mv.visitIntInsn(Opcodes.BIPUSH, index - skipVariables);
                mv.visitVarInsn(Opcodes.ALOAD, emitterVisitor.ctx.symbolTable.getVariableIndex(varName));
                mv.visitInsn(Opcodes.AASTORE);
                emitterVisitor.ctx.logDebug("Put variable " + emitterVisitor.ctx.symbolTable.getVariableIndex(varName) + " at parameter #" + (index - skipVariables) + " " + varName);
            }
        }
        // Stack: [Constructor, Object[]]

        // Create an instance of the compiled eval class
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/reflect/Constructor",
                "newInstance",
                "([Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Object");
        // Stack: [Object]

        // Create a CODE variable from the compiled eval class instance
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeCode", "makeCodeObject", "(Ljava/lang/Object;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        // Stack: [RuntimeScalar(Code)]

        // Push @_ onto the stack for the eval execution
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        // Stack: [RuntimeScalar(Code), RuntimeArray(@_)]

        // Push the call context onto the stack
        emitterVisitor.pushCallContext();
        // Stack: [RuntimeScalar(Code), RuntimeArray(@_), int]

        // Call the apply method to execute the eval
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/RuntimeScalar",
                "apply",
                "(Lorg/perlonjava/runtime/RuntimeArray;I)Lorg/perlonjava/runtime/RuntimeList;",
                false);
        // Stack: [RuntimeList]

        // Handle the result based on the context
        if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeList", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
            // Stack: [RuntimeScalar]
        } else if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
            // Stack: []
        }
        // If the context is LIST or RUNTIME, the stack remains as [RuntimeList]
    }
}

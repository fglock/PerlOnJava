package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.BinaryOperatorNode;
import org.perlonjava.astnode.IdentifierNode;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.astnode.SubroutineNode;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.runtime.RuntimeCode;
import org.perlonjava.runtime.RuntimeContextType;
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
    public static void emitSubroutine(EmitterContext ctx, SubroutineNode node) {
        ctx.logDebug("SUB start");
        if (ctx.contextType == RuntimeContextType.VOID) {
            return;
        }
        MethodVisitor mv = ctx.mv;

        // Mark the block as subroutine block,
        // this prevents the "code too large" transform in emitBlock()
        node.block.setAnnotation("blockIsSubroutine", true);

        // Retrieve closure variable list
        // Alternately, scan the AST for variables and capture only the ones that are used
        Map<Integer, SymbolTable.SymbolEntry> visibleVariables = ctx.symbolTable.getAllVisibleVariables();
        ctx.logDebug("AnonSub ctx.symbolTable.getAllVisibleVariables");

        // Create a new symbol table for the subroutine
        ScopedSymbolTable newSymbolTable = ctx.symbolTable.snapShot();

        String[] newEnv = newSymbolTable.getVariableNames();
        ctx.logDebug("AnonSub " + newSymbolTable);

        // Reset the index counter to start after the closure variables
        // This prevents allocateLocalVariable() from creating slots that overlap with uninitialized slots
        // We need to use the MAXIMUM of newEnv.length and the current index to avoid conflicts
        int currentVarIndex = newSymbolTable.getCurrentLocalVariableIndex();
        int resetTo = Math.max(newEnv.length, currentVarIndex);
        newSymbolTable.resetLocalVariableIndex(resetTo);

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

        int skipVariables = EmitterMethodCreator.skipVariables; // Skip (this, @_, wantarray)

        // Direct instantiation approach - no reflection needed!

        // 1. NEW - Create new instance
        mv.visitTypeInsn(Opcodes.NEW, subCtx.javaClassInfo.javaClassName);
        mv.visitInsn(Opcodes.DUP);

        // 2. Load all captured variables for the constructor
        int newIndex = 0;
        for (Integer currentIndex : visibleVariables.keySet()) {
            if (newIndex >= skipVariables) {
                mv.visitVarInsn(Opcodes.ALOAD, currentIndex); // Load the captured variable
            }
            newIndex++;
        }

        // 3. Build the constructor descriptor
        StringBuilder constructorDescriptor = new StringBuilder("(");
        for (int i = skipVariables; i < newEnv.length; i++) {
            String descriptor = EmitterMethodCreator.getVariableDescriptor(newEnv[i]);
            constructorDescriptor.append(descriptor);
        }
        constructorDescriptor.append(")V");

        // 4. INVOKESPECIAL - Call the constructor
        mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                subCtx.javaClassInfo.javaClassName,
                "<init>",
                constructorDescriptor.toString(),
                false);

        // 5. Create a CODE variable using RuntimeCode.makeCodeObject
        if (node.prototype != null) {
            mv.visitLdcInsn(node.prototype);
            mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/RuntimeCode",
                    "makeCodeObject",
                    "(Ljava/lang/Object;Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        } else {
            mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/RuntimeCode",
                    "makeCodeObject",
                    "(Ljava/lang/Object;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        }

        // 6. Clean up the stack if context is VOID
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
        emitterVisitor.pushCallContext();   // Push call context to stack
        emitterVisitor.ctx.mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/RuntimeCode",
                "apply",
                "(Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;Lorg/perlonjava/runtime/RuntimeBase;I)Lorg/perlonjava/runtime/RuntimeList;",
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
        EmitOperator.handleVoidContext(emitterVisitor);
    }
}

package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.astvisitor.ReturnTypeVisitor;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.RuntimeScalarType;

import static org.perlonjava.perlmodule.Strict.STRICT_SUBS;
import static org.perlonjava.runtime.ScalarUtils.isInteger;

/**
 * This class contains static methods for emitting bytecode for various literal types
 * in the Perl-to-Java compiler.
 */
public class EmitLiteral {

    /**
     * Emits bytecode for an array literal.
     *
     * @param emitterVisitor The visitor for emitting bytecode
     * @param node           The ArrayLiteralNode to be processed
     */
    public static void emitArrayLiteral(EmitterVisitor emitterVisitor, ArrayLiteralNode node) {
        emitterVisitor.ctx.logDebug("visit(ArrayLiteralNode) in context " + emitterVisitor.ctx.contextType);
        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Elements in array literals are always evaluated in LIST context
        EmitterVisitor elementContext = emitterVisitor.with(RuntimeContextType.LIST);

        // In VOID context, evaluate elements for side effects and pop the results
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            for (Node element : node.elements) {
                element.accept(elementContext);
                // Pop the list result
                mv.visitInsn(Opcodes.POP);
            }
            emitterVisitor.ctx.logDebug("visit(ArrayLiteralNode) end");
            return;
        }

        // Create a new instance of RuntimeArray
        mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeArray");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/RuntimeArray", "<init>", "()V", false);

        // The stack now has the new RuntimeArray instance

        for (Node element : node.elements) {
            // Visit each element to generate code for it

            // Duplicate the RuntimeArray instance to keep it on the stack
            mv.visitInsn(Opcodes.DUP);
            // stack: [RuntimeArray] [RuntimeArray]

            emitterVisitor.ctx.javaClassInfo.incrementStackLevel(2);

            // emit the array element
            element.accept(elementContext);

            emitterVisitor.ctx.javaClassInfo.decrementStackLevel(2);

            // Call the add method to add the element to the RuntimeArray
            addElementToArray(mv, element);

            // The stack now has the RuntimeArray instance again
        }

        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "createReference", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);

        EmitOperator.handleVoidContext(emitterVisitor);
        emitterVisitor.ctx.logDebug("visit(ArrayLiteralNode) end");
    }

    /**
     * Emits bytecode for a hash literal.
     *
     * @param emitterVisitor The visitor for emitting bytecode
     * @param node           The HashLiteralNode to be processed
     */
    public static void emitHashLiteral(EmitterVisitor emitterVisitor, HashLiteralNode node) {
        emitterVisitor.ctx.logDebug("visit(HashLiteralNode) in context " + emitterVisitor.ctx.contextType);
        MethodVisitor mv = emitterVisitor.ctx.mv;

        // In VOID context, evaluate elements for side effects and pop the results
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            EmitterVisitor elementContext = emitterVisitor.with(RuntimeContextType.LIST);
            for (Node element : node.elements) {
                element.accept(elementContext);
                // Pop the list result
                mv.visitInsn(Opcodes.POP);
            }
            emitterVisitor.ctx.logDebug("visit(HashLiteralNode) end");
            return;
        }

        // Create a RuntimeList with elements in LIST context
        ListNode listNode = new ListNode(node.elements, node.tokenIndex);
        listNode.accept(emitterVisitor.with(RuntimeContextType.LIST));

        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/RuntimeHash",
                "createHashRef",
                "(Lorg/perlonjava/runtime/RuntimeDataProvider;)Lorg/perlonjava/runtime/RuntimeScalar;", false);

        EmitOperator.handleVoidContext(emitterVisitor);
        emitterVisitor.ctx.logDebug("visit(HashLiteralNode) end");
    }

    /**
     * Emits bytecode for a string literal.
     *
     * @param ctx  The emission context
     * @param node The StringNode to be processed
     */
    public static void emitString(EmitterContext ctx, StringNode node) {
        if (ctx.contextType == RuntimeContextType.VOID) {
            return;
        }
        MethodVisitor mv = ctx.mv;
        if (ctx.isBoxed) { // expect a RuntimeScalar object
            mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeScalar");
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(node.value); // emit string
            mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "org/perlonjava/runtime/RuntimeScalar",
                    "<init>",
                    "(Ljava/lang/String;)V",
                    false); // Call new RuntimeScalar(String)

            // Check if the StringNode is a v-string
            if (node.isVString) {
                // Set the RuntimeScalar type to VSTRING
                mv.visitInsn(Opcodes.DUP); // Duplicate the RuntimeScalar reference
                mv.visitLdcInsn(RuntimeScalarType.VSTRING); // emit "VSTRING" constant
                mv.visitFieldInsn(Opcodes.PUTFIELD, "org/perlonjava/runtime/RuntimeScalar", "type", "I");
            }
        } else {
            mv.visitLdcInsn(node.value); // emit string
        }
    }

    /**
     * Emits bytecode for a list literal.
     *
     * @param emitterVisitor The visitor for emitting bytecode
     * @param node           The ListNode to be processed
     */
    public static void emitList(EmitterVisitor emitterVisitor, ListNode node) {
        emitterVisitor.ctx.logDebug("visit(ListNode) in context " + emitterVisitor.ctx.contextType);
        MethodVisitor mv = emitterVisitor.ctx.mv;
        int contextType = emitterVisitor.ctx.contextType;

        // In VOID context, just visit elements for side effects
        if (contextType == RuntimeContextType.VOID) {
            for (Node element : node.elements) {
                element.accept(emitterVisitor);
            }
            emitterVisitor.ctx.logDebug("visit(ListNode) end");
            return;
        }

        // Create a new instance of RuntimeList
        mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeList");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/RuntimeList", "<init>", "()V", false);
        // stack: [RuntimeList]

        for (Node element : node.elements) {
            // Visit each element to generate code for it

            // Duplicate the RuntimeList instance to keep it on the stack
            mv.visitInsn(Opcodes.DUP);
            // stack: [RuntimeList] [RuntimeList]

            emitterVisitor.ctx.javaClassInfo.incrementStackLevel(2);

            // emit the list element
            // The context for list elements is the same as the list node context
            element.accept(emitterVisitor);

            emitterVisitor.ctx.javaClassInfo.decrementStackLevel(2);

            // Call the add method to add the element to the RuntimeList
            addElementToList(mv, element, contextType);

            // The stack now has the RuntimeList instance again
        }

        // At this point, the stack has the fully populated RuntimeList instance
        if (contextType == RuntimeContextType.SCALAR) {
            // Transform the value in the stack to RuntimeScalar
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeList", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
        }
        emitterVisitor.ctx.logDebug("visit(ListNode) end");
    }

    /**
     * Emits bytecode for a number literal.
     *
     * @param ctx  The emission context
     * @param node The NumberNode to be processed
     */
    public static void emitNumber(EmitterContext ctx, NumberNode node) {
        ctx.logDebug("visit(NumberNode) in context " + ctx.contextType);
        if (ctx.contextType == RuntimeContextType.VOID) {
            return;
        }
        MethodVisitor mv = ctx.mv;
        String value = node.value.replace("_", "");
        boolean isInteger = isInteger(value);
        if (ctx.isBoxed) { // expect a RuntimeScalar object
            if (isInteger) {
                ctx.logDebug("visit(NumberNode) emit boxed integer");
                mv.visitLdcInsn(
                        Integer.valueOf(value)); // Push the integer argument onto the stack
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/RuntimeScalarCache",
                        "getScalarInt",
                        "(I)Lorg/perlonjava/runtime/RuntimeScalar;", false); // Call new getScalarInt(int)
            } else {
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeScalar");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(Double.valueOf(value)); // Push the double argument onto the stack
                mv.visitMethodInsn(
                        Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/RuntimeScalar", "<init>", "(D)V", false); // Call new RuntimeScalar(double)
            }
        } else {
            if (isInteger) {
                mv.visitLdcInsn(Integer.parseInt(value)); // emit native integer
            } else {
                mv.visitLdcInsn(Double.parseDouble(value)); // emit native double
            }
        }
    }

    /**
     * Emits bytecode for an identifier.
     *
     * @param visitor The emitter visitor
     * @param ctx     The emission context
     * @param node    The IdentifierNode to be processed
     * @throws PerlCompilerException if the bare word is not implemented
     */
    public static void emitIdentifier(EmitterVisitor visitor, EmitterContext ctx, IdentifierNode node) {
        // In VOID context, barewords have no side effects
        if (ctx.contextType == RuntimeContextType.VOID) {
            return;
        }

        if (ctx.symbolTable.isStrictOptionEnabled(STRICT_SUBS)) {
            throw new PerlCompilerException(
                    node.tokenIndex, "Bareword \"" + node.name + "\" not allowed while \"strict subs\" in use", ctx.errorUtil);
        } else {
            // Emit identifier as string
            new StringNode(node.name, node.tokenIndex).accept(visitor);
        }
    }

    /**
     * Optimized method to add an element to a RuntimeList.
     * Uses specific method calls based on the element's return type to avoid interface dispatch.
     * Stack before: [RuntimeList] [element]
     * Stack after: [RuntimeList]
     *
     * @param mv      The method visitor
     * @param element The element node being added
     */
    private static void addElementToList(MethodVisitor mv, Node element, int contextType) {
        String returnType;

        // Stack is currently: [RuntimeList] [element]
        // We need to swap to get: [element] [RuntimeList]
        mv.visitInsn(Opcodes.SWAP);

        if (contextType == RuntimeContextType.SCALAR) {
            // Special case for list in scalar context
            returnType = "RuntimeScalar;";
        } else {
            // Use ReturnTypeVisitor to determine the element's return type
            returnType = ReturnTypeVisitor.getReturnType(element);
        }

        // Optimize based on return type
        switch (returnType) {
            case "RuntimeScalar;" -> {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar",
                        "addToList", "(Lorg/perlonjava/runtime/RuntimeList;)V", false);
            }
            case "RuntimeArray;" -> {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray",
                        "addToList", "(Lorg/perlonjava/runtime/RuntimeList;)V", false);
            }
            case "RuntimeHash;" -> {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeHash",
                        "addToList", "(Lorg/perlonjava/runtime/RuntimeList;)V", false);
            }
            case "RuntimeList;" -> {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeList",
                        "addToList", "(Lorg/perlonjava/runtime/RuntimeList;)V", false);
            }
            case null, default -> {
                // Default case: use the interface for unknown types
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider",
                        "addToList", "(Lorg/perlonjava/runtime/RuntimeList;)V", true);
            }
        }
    }

    /**
     * Optimized method to add an element to a RuntimeArray.
     * Uses specific method calls based on the element's return type to avoid interface dispatch.
     * Stack before: [RuntimeArray] [element]
     * Stack after: [RuntimeArray]
     *
     * @param mv      The method visitor
     * @param element The element node being added
     */
    private static void addElementToArray(MethodVisitor mv, Node element) {
        // Use ReturnTypeVisitor to determine the element's return type
        String returnType = ReturnTypeVisitor.getReturnType(element);

        // Stack is currently: [RuntimeArray] [element]
        // We need to swap to get: [element] [RuntimeArray]
        mv.visitInsn(Opcodes.SWAP);

        // Optimize based on return type
        switch (returnType) {
            case "RuntimeScalar;" -> {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar",
                        "addToArray", "(Lorg/perlonjava/runtime/RuntimeArray;)V", false);
            }
            case "RuntimeArray;" -> {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray",
                        "addToArray", "(Lorg/perlonjava/runtime/RuntimeArray;)V", false);
            }
            case "RuntimeHash;" -> {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeHash",
                        "addToArray", "(Lorg/perlonjava/runtime/RuntimeArray;)V", false);
            }
            case "RuntimeList;" -> {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeList",
                        "addToArray", "(Lorg/perlonjava/runtime/RuntimeArray;)V", false);
            }
            case null, default -> {
                // Default case: use the interface for unknown types
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider",
                        "addToArray", "(Lorg/perlonjava/runtime/RuntimeArray;)V", true);
            }
        }
    }
}

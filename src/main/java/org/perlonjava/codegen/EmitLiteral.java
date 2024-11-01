package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;

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
    static void emitArrayLiteral(EmitterVisitor emitterVisitor, ArrayLiteralNode node) {
        emitterVisitor.ctx.logDebug("visit(ArrayLiteralNode) in context " + emitterVisitor.ctx.contextType);
        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Create a new instance of RuntimeArray
        mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeArray");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/RuntimeArray", "<init>", "()V", false);

        // The stack now has the new RuntimeArray instance

        for (Node element : node.elements) {
            // Visit each element to generate code for it

            // Duplicate the RuntimeArray instance to keep it on the stack
            mv.visitInsn(Opcodes.DUP);

            // emit the list element
            element.accept(emitterVisitor.with(RuntimeContextType.LIST));

            // Call the add method to add the element to the RuntimeArray
            // This calls RuntimeDataProvider.addToArray() in order to allow [ 1, 2, $x, @x, %x ]
            mv.visitInsn(Opcodes.SWAP);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "addToArray", "(Lorg/perlonjava/runtime/RuntimeArray;)V", true);

            // The stack now has the RuntimeArray instance again
        }
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "createReference", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);

        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
        emitterVisitor.ctx.logDebug("visit(ArrayLiteralNode) end");
    }

    /**
     * Emits bytecode for a hash literal.
     *
     * @param emitterVisitor The visitor for emitting bytecode
     * @param node           The HashLiteralNode to be processed
     */
    static void emitHashLiteral(EmitterVisitor emitterVisitor, HashLiteralNode node) {
        emitterVisitor.ctx.logDebug("visit(HashLiteralNode) in context " + emitterVisitor.ctx.contextType);
        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Create a RuntimeList
        ListNode listNode = new ListNode(node.elements, node.tokenIndex);
        listNode.accept(emitterVisitor.with(RuntimeContextType.LIST));

        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/RuntimeHash",
                "createHashRef",
                "(Lorg/perlonjava/runtime/RuntimeDataProvider;)Lorg/perlonjava/runtime/RuntimeScalar;", false);

        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
        emitterVisitor.ctx.logDebug("visit(HashLiteralNode) end");
    }

    /**
     * Emits bytecode for a string literal.
     *
     * @param ctx  The emission context
     * @param node The StringNode to be processed
     */
    static void emitString(EmitterContext ctx, StringNode node) {
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
                mv.visitFieldInsn(Opcodes.GETSTATIC, "org/perlonjava/runtime/RuntimeScalarType", "VSTRING", "Lorg/perlonjava/runtime/RuntimeScalarType;");
                mv.visitFieldInsn(Opcodes.PUTFIELD, "org/perlonjava/runtime/RuntimeScalar", "type", "Lorg/perlonjava/runtime/RuntimeScalarType;");
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
    static void emitList(EmitterVisitor emitterVisitor, ListNode node) {
        emitterVisitor.ctx.logDebug("visit(ListNode) in context " + emitterVisitor.ctx.contextType);
        MethodVisitor mv = emitterVisitor.ctx.mv;

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
            element.accept(emitterVisitor.with(RuntimeContextType.LIST));

            emitterVisitor.ctx.javaClassInfo.decrementStackLevel(2);

            // Call the add method to add the element to the RuntimeList
            // This calls RuntimeDataProvider.addToList() in order to allow (1, 2, $x, @x, %x)
            mv.visitInsn(Opcodes.SWAP);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "addToList", "(Lorg/perlonjava/runtime/RuntimeList;)V", true);

            // The stack now has the RuntimeList instance again
        }

        // At this point, the stack has the fully populated RuntimeList instance
        if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
            // Transform the value in the stack to RuntimeScalar
            emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeList", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
        } else if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
        emitterVisitor.ctx.logDebug("visit(ListNode) end");
    }

    /**
     * Emits bytecode for a number literal.
     *
     * @param ctx  The emission context
     * @param node The NumberNode to be processed
     */
    static void emitNumber(EmitterContext ctx, NumberNode node) {
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
     * @param ctx  The emission context
     * @param node The IdentifierNode to be processed
     * @throws PerlCompilerException if the bare word is not implemented
     */
    static void emitIdentifier(EmitterContext ctx, IdentifierNode node) {
        // Emit code for identifier
        throw new PerlCompilerException(
                node.tokenIndex, "Bareword \"" + node.name + "\" not allowed while \"strict subs\" in use", ctx.errorUtil);
    }
}

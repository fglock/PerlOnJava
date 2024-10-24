package org.perlonjava.codegen;

import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.runtime.RuntimeContextType;

public class EmitRegex {
    static void handleBindRegex(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        //
        //  BinaryOperatorNode: =~
        //    OperatorNode: $
        //      IdentifierNode: a
        //    OperatorNode: matchRegex (or `qr` object)
        //      ListNode:
        //        StringNode: 'abc'
        //        StringNode: 'i'
        //
        EmitterVisitor scalarVisitor =
                emitterVisitor.with(RuntimeContextType.SCALAR); // execute operands in scalar context
        if (node.right instanceof OperatorNode right) {
            if (right.operand instanceof ListNode) {
                // regex operator:  $v =~ /regex/;
                // bind the variable to the regex operation
                ((ListNode) right.operand).elements.add(node.left);
                right.accept(emitterVisitor);
                return;
            }
        }
        // not a regex operator:  $v =~ $qr;
        node.right.accept(scalarVisitor);
        node.left.accept(scalarVisitor);
        emitterVisitor.pushCallContext();
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/RuntimeRegex", "matchRegex",
                "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    static void handleNotBindRegex(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        emitterVisitor.visit(
                new OperatorNode("not",
                        new OperatorNode("scalar",
                                new BinaryOperatorNode(
                                        "=~",
                                        node.left,
                                        node.right,
                                        node.tokenIndex
                                ), node.tokenIndex
                        ), node.tokenIndex
                ));
    }

    static void handleRegex(EmitterVisitor emitterVisitor, OperatorNode node) {
        ListNode operand = (ListNode) node.operand;
        EmitterVisitor scalarVisitor = emitterVisitor.with(RuntimeContextType.SCALAR);
        Node variable = null;

        switch (node.operator) {
            case "qx" -> {
                // static RuntimeScalar systemCommand(RuntimeScalar command)
                operand.elements.getFirst().accept(scalarVisitor);
                emitterVisitor.pushCallContext();
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/RuntimeIO",
                        "systemCommand",
                        "(Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);
                if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                    emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
                }
                return;
            }
            case "tr", "y" -> {
                // static RuntimeTransliterate compile(RuntimeScalar search, RuntimeScalar replace, RuntimeScalar modifiers)
                operand.elements.get(0).accept(scalarVisitor);
                operand.elements.get(1).accept(scalarVisitor);
                operand.elements.get(2).accept(scalarVisitor);
                if (operand.elements.size() > 3) {
                    variable = operand.elements.get(3);
                }
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/operators/RuntimeTransliterate", "compile",
                        "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/operators/RuntimeTransliterate;", false);

                // RuntimeScalar transliterate(RuntimeScalar originalString)
                if (variable == null) {
                    // use `$_`
                    variable = new OperatorNode("$", new IdentifierNode("_", node.tokenIndex), node.tokenIndex);
                }
                variable.accept(scalarVisitor);
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/operators/RuntimeTransliterate", "transliterate", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
                if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                    emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
                }
                return;
            }
            case "replaceRegex" -> {
                // RuntimeBaseEntity replaceRegex(RuntimeScalar quotedRegex, RuntimeScalar string, RuntimeScalar replacement, int ctx)
                operand.elements.get(0).accept(scalarVisitor);
                operand.elements.get(1).accept(scalarVisitor);
                operand.elements.get(2).accept(scalarVisitor);
                if (operand.elements.size() > 3) {
                    variable = operand.elements.get(3);
                }
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/RuntimeRegex", "getReplacementRegex",
                        "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
            }
            default -> {
                // RuntimeRegex.getQuotedRegex(RuntimeScalar patternString, RuntimeScalar modifiers)
                operand.elements.get(0).accept(scalarVisitor);
                operand.elements.get(1).accept(scalarVisitor);
                if (operand.elements.size() > 2) {
                    variable = operand.elements.get(2);
                }
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/RuntimeRegex", "getQuotedRegex",
                        "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
            }
        }

        if (node.operator.equals("quoteRegex")) {
            // do not execute  `qr//`
            return;
        }

        if (variable == null) {
            // use `$_`
            variable = new OperatorNode("$", new IdentifierNode("_", node.tokenIndex), node.tokenIndex);
        }
        variable.accept(scalarVisitor);

        emitterVisitor.pushCallContext();
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/RuntimeRegex", "matchRegex",
                "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;", false);

        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP);
        }
    }
}

package org.perlonjava.codegen;

import org.perlonjava.astnode.*;
import org.perlonjava.runtime.RuntimeContextType;

/**
 * Is this Node assignable (Lvalue) and is it Scalar-like or List-like
 * <p>
 * getResult() returns a RuntimeContextType.SCALAR, LIST, or VOID (not assignable)
 * <p>
 * Usage:
 * <p>
 * LValueVisitor.getContext(node);
 */
public class LValueVisitor implements Visitor {
    private RuntimeContextType context = RuntimeContextType.VOID;

    public static RuntimeContextType getContext(Node node) throws Exception {
        LValueVisitor lvVisitor = new LValueVisitor();
        node.accept(lvVisitor);
        return lvVisitor.context;
    }

    @Override
    public void visit(NumberNode node) throws Exception {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(IdentifierNode node) throws Exception {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(BinaryOperatorNode node) throws Exception {
        switch (node.operator) {
            case "=":   // $a = ...
                node.left.accept(this);
                break;
            case "[":   // $a[]
            case "{":   // $a{}
                context = RuntimeContextType.SCALAR;
                break;
            case "->":  // $a->() $a->[] $a->()
                context = RuntimeContextType.SCALAR;
                break;
            default:
                context = RuntimeContextType.VOID;  // Not an L-value
        }
    }

    @Override
    public void visit(OperatorNode node) throws Exception {
        switch (node.operator) {
            case "my":  // 'my' depends on the operand's context, can be SCALAR or LIST
                node.operand.accept(this);
                break;
            case "@":   // @a
            case "%":   // %a
                context = RuntimeContextType.LIST;
                break;
            case "$":   // $a $$a
            case "substr":
                context = RuntimeContextType.SCALAR;
                break;
            default:
                context = RuntimeContextType.VOID;  // Not an L-value
        }
    }

    @Override
    public void visit(For1Node node) throws Exception {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(For3Node node) throws Exception {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(IfNode node) throws Exception {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(AnonSubNode node) throws Exception {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(TernaryOperatorNode node) throws Exception {
        // XXX FIXME
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(StringNode node) throws Exception {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(BlockNode node) throws Exception {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(ListNode node) throws Exception {
        context = RuntimeContextType.LIST; // ($a, $b)
    }

    @Override
    public void visit(ArrayLiteralNode node) throws Exception {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(HashLiteralNode node) throws Exception {
        context = RuntimeContextType.VOID;
    }
}


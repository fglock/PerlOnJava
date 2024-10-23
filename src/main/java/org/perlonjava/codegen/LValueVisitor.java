package org.perlonjava.codegen;

import org.perlonjava.astnode.*;
import org.perlonjava.runtime.PerlCompilerException;
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
    private int context = RuntimeContextType.VOID;

    public static int getContext(Node node) {
        LValueVisitor lvVisitor = new LValueVisitor();
        node.accept(lvVisitor);
        return lvVisitor.context;
    }

    @Override
    public void visit(NumberNode node) {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(IdentifierNode node) {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        switch (node.operator) {
            case "=":   // $a = ...
                node.left.accept(this);
                break;
            case "[":   // $a[]  @a[]
            case "{":   // $a{}  @a{}
                node.left.accept(this);
                break;
            case "->":  // $a->() $a->[] $a->()
                context = RuntimeContextType.SCALAR;
                break;
            case "substr":
                context = RuntimeContextType.SCALAR;
                break;
            default:
                context = RuntimeContextType.VOID;  // Not an L-value
        }
    }

    @Override
    public void visit(OperatorNode node) {
        switch (node.operator) {
            case "local":
            case "our":
            case "state":
            case "my":  // 'my' depends on the operand's context, can be SCALAR or LIST
                node.operand.accept(this);
                break;
            case "@":   // @a
            case "%":   // %a
                context = RuntimeContextType.LIST;
                break;
            case "&":
//                if (node.attributes.contains("lvalue")) {
//                    // lvalue subroutine
//                    context = RuntimeContextType.SCALAR;
//                } else {
//                }
                break;
            case "$":   // $a $$a
            case "*":  // typeglob
            case "vec":
            case "pos":
                context = RuntimeContextType.SCALAR;
                break;
            default:
                context = RuntimeContextType.VOID;  // Not an L-value
        }
    }

    @Override
    public void visit(For1Node node) {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(For3Node node) {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(IfNode node) {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(SubroutineNode node) {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        node.trueExpr.accept(this);
        int context1 = context;
        node.falseExpr.accept(this);
        int context2 = context;
        if (context1 != context2) {
            throw new PerlCompilerException("Assignment to both a list and a scalar");
        }
    }

    @Override
    public void visit(StringNode node) {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(BlockNode node) {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(ListNode node) {
        context = RuntimeContextType.LIST; // ($a, $b)
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        context = RuntimeContextType.VOID;
    }

    @Override
    public void visit(HashLiteralNode node) {
        context = RuntimeContextType.VOID;
    }
}


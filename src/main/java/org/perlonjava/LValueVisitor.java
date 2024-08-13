package org.perlonjava;

/**
 * Is this Node assignable (Lvalue) and is it Scalar-like or List-like
 * <p>
 * getResult() returns a ContextType.SCALAR, LIST, or VOID (not assignable)
 * <p>
 * Usage:
 * <p>
 *   LValueVisitor.getContext(node);
 *
 */
public class LValueVisitor implements Visitor {
    private ContextType context = ContextType.VOID;

    public static ContextType getContext(Node node) throws Exception {
        LValueVisitor lvVisitor = new LValueVisitor();
        node.accept(lvVisitor);
        return lvVisitor.context;
    }

    @Override
    public void visit(NumberNode node) throws Exception {
        context = ContextType.VOID;
    }

    @Override
    public void visit(IdentifierNode node) throws Exception {
        context = ContextType.VOID;
    }

    @Override
    public void visit(BinaryOperatorNode node) throws Exception {
        switch (node.operator) {
            case "=":   // $a = ...
                node.left.accept(this);
                break;
            case "[":   // $a[]
            case "{":   // $a{}
                context = ContextType.SCALAR;
                break;
            case "->":  // $a->() $a->[] $a->()
                context = ContextType.SCALAR;
                break;
            default:
                context = ContextType.VOID;  // Not an L-value
        }
    }

    @Override
    public void visit(UnaryOperatorNode node) throws Exception {
        switch (node.operator) {
            case "my":  // 'my' depends on the operand's context, can be SCALAR or LIST
                node.operand.accept(this);
                break;
            case "@":   // @a
            case "%":   // %a
                context = ContextType.LIST;
                break;
            case "$":   // $a $$a
            case "substr":
                context = ContextType.SCALAR;
                break;
            default:
                context = ContextType.VOID;  // Not an L-value
        }
    }

    @Override
    public void visit(For1Node node) throws Exception {
        context = ContextType.VOID;
    }

    @Override
    public void visit(For3Node node) throws Exception {
        context = ContextType.VOID;
    }

    @Override
    public void visit(IfNode node) throws Exception {
        context = ContextType.VOID;
    }

    @Override
    public void visit(AnonSubNode node) throws Exception {
        context = ContextType.VOID;
    }

    @Override
    public void visit(TernaryOperatorNode node) throws Exception {
        // XXX FIXME
        context = ContextType.VOID;
    }

    @Override
    public void visit(PostfixOperatorNode node) throws Exception {
        context = ContextType.VOID;
    }

    @Override
    public void visit(StringNode node) throws Exception {
        context = ContextType.VOID;
    }

    @Override
    public void visit(BlockNode node) throws Exception {
        context = ContextType.VOID;
    }

    @Override
    public void visit(ListNode node) throws Exception {
        context = ContextType.LIST; // ($a, $b)
    }

    @Override
    public void visit(ArrayLiteralNode node) throws Exception {
        context = ContextType.VOID;
    }

    @Override
    public void visit(HashLiteralNode node) throws Exception {
        context = ContextType.VOID;
    }

    // Add other visit methods as needed
}


/**
 * Is this Node assignable (Lvalue) and is it Scalar-like or List-like
 *
 * getResult() returns a ContextType.SCALAR, LIST, or VOID (not assignable)
 *
 * Usage:
 *
 *   LValueVisitor lvVisitor = new LValueVisitor();
 *   node.accept(lvVisitor);
 *   return lvVisitor.getResult();

 */
public class LValueVisitor implements Visitor {
    private ContextType context = ContextType.VOID;

    public ContextType getResult() {
        return context;
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
            case "@":
            case "%":
                context = ContextType.LIST;
                break;
            case "$":
            case "[":
            case "{":
                context = ContextType.SCALAR;
                break;
            default:
                context = ContextType.VOID;  // Not an L-value
        }
    }

    @Override
    public void visit(UnaryOperatorNode node) throws Exception {
        switch (node.operator) {
            case "my":
                // 'my' depends on the operand's context, can be SCALAR or LIST
                node.operand.accept(this);
                break;
            case "@":
            case "%":
                context = ContextType.LIST;
                break;
            case "$":
                context = ContextType.SCALAR;
                break;
            default:
                context = ContextType.VOID;  // Not an L-value
        }
    }

    @Override
    public void visit(ForNode node) throws Exception {
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
        context = ContextType.LIST;
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


package org.perlonjava.frontend.analysis;

import org.perlonjava.frontend.astnode.*;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

/**
 * AST transformation pass that propagates execution context (SCALAR, LIST, VOID)
 * through the AST. This ensures both JVM and interpreter backends use identical
 * context decisions.
 *
 * <p>Context propagation rules follow Perl semantics:
 * <ul>
 *   <li>Assignment RHS gets context from LHS type (scalar vs list)</li>
 *   <li>Hash/array literal elements are always in LIST context</li>
 *   <li>Logical operators (||, &&, //) propagate outer context to RHS</li>
 *   <li>Comma operator: LHS is VOID in scalar context, both LIST in list context</li>
 *   <li>Subroutine arguments are evaluated in LIST context</li>
 *   <li>Condition expressions are in SCALAR context</li>
 *   <li>Last statement of block inherits block's context</li>
 * </ul>
 * </p>
 */
public class ContextResolver extends ASTTransformPass {

    private int currentContext = RuntimeContextType.VOID;

    /**
     * Transform the AST starting with a given context.
     *
     * @param root    The root node
     * @param context The initial context (usually VOID for top-level)
     */
    public void transformWithContext(Node root, int context) {
        if (root == null) return;
        int saved = currentContext;
        currentContext = context;
        root.accept(this);
        currentContext = saved;
    }

    @Override
    public void transform(Node root) {
        transformWithContext(root, RuntimeContextType.VOID);
    }

    @Override
    public void visit(BlockNode node) {
        setContext(node, currentContext);

        int size = node.elements.size();
        for (int i = 0; i < size; i++) {
            Node element = node.elements.get(i);
            if (element == null) continue;

            // Last statement inherits block's context, others are VOID
            int stmtContext = (i == size - 1) ? currentContext : RuntimeContextType.VOID;
            int saved = currentContext;
            currentContext = stmtContext;
            element.accept(this);
            currentContext = saved;
        }
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        setContext(node, currentContext);

        switch (node.operator) {
            case "=" -> visitAssignment(node);
            case "||", "&&", "//", "or", "and" -> visitLogicalOp(node);
            case "=~", "!~" -> visitBindingOp(node);
            case "," -> visitCommaOp(node);
            case "?", ":" -> visitTernaryPart(node);
            case "[", "{" -> visitSubscript(node);
            case "->" -> visitArrow(node);
            case "(" -> visitCall(node);
            case "print", "say", "printf", "warn", "die" -> visitPrintBinary(node);
            case "map", "grep", "sort" -> visitMapBinary(node);
            default -> visitBinaryDefault(node);
        }
    }

    private void visitAssignment(BinaryOperatorNode node) {
        // LHS determines context for RHS
        int lhsContext = LValueVisitor.getContext(node.left);
        int rhsContext = (lhsContext == RuntimeContextType.LIST)
                ? RuntimeContextType.LIST
                : RuntimeContextType.SCALAR;

        // LHS context matches its lvalue type (SCALAR for $x, LIST for @x/(%h)/($a,$b))
        int saved = currentContext;
        currentContext = lhsContext;
        if (node.left != null) node.left.accept(this);

        currentContext = rhsContext;
        if (node.right != null) node.right.accept(this);
        currentContext = saved;
    }

    private void visitLogicalOp(BinaryOperatorNode node) {
        // LHS is scalar (for boolean test), RHS inherits outer context
        int saved = currentContext;
        currentContext = RuntimeContextType.SCALAR;
        if (node.left != null) node.left.accept(this);

        currentContext = saved; // RHS gets outer context (for return value)
        if (node.right != null) node.right.accept(this);
    }

    private void visitBindingOp(BinaryOperatorNode node) {
        // =~ and !~: LHS is scalar, RHS is the regex (scalar)
        int saved = currentContext;
        currentContext = RuntimeContextType.SCALAR;
        if (node.left != null) node.left.accept(this);
        if (node.right != null) node.right.accept(this);
        currentContext = saved;
    }

    private void visitCommaOp(BinaryOperatorNode node) {
        int saved = currentContext;
        if (currentContext == RuntimeContextType.LIST) {
            // In list context, both sides contribute to the list
            if (node.left != null) node.left.accept(this);
            if (node.right != null) node.right.accept(this);
        } else {
            // In scalar/void context, LHS is void, RHS is the result
            currentContext = RuntimeContextType.VOID;
            if (node.left != null) node.left.accept(this);
            currentContext = saved;
            if (node.right != null) node.right.accept(this);
        }
        currentContext = saved;
    }

    private void visitTernaryPart(BinaryOperatorNode node) {
        // This handles the ":" part of ternary - both branches inherit context
        int saved = currentContext;
        if (node.left != null) node.left.accept(this);
        if (node.right != null) node.right.accept(this);
        currentContext = saved;
    }

    private void visitSubscript(BinaryOperatorNode node) {
        // $a[idx] or $a{key}: index/key is scalar, container depends on sigil
        // @a[list] or @a{list}: slice - subscript is list context
        int saved = currentContext;
        if (node.left != null) node.left.accept(this);

        // Check if this is a slice operation (@ or % sigil means list context for subscript)
        boolean isSlice = node.left instanceof OperatorNode opNode &&
                ("@".equals(opNode.operator) || "%".equals(opNode.operator));
        currentContext = isSlice ? RuntimeContextType.LIST : RuntimeContextType.SCALAR;
        if (node.right != null) node.right.accept(this);
        currentContext = saved;
    }

    private void visitArrow(BinaryOperatorNode node) {
        // ->[] ->{} ->() : LHS is scalar (the reference)
        int saved = currentContext;
        currentContext = RuntimeContextType.SCALAR;
        if (node.left != null) node.left.accept(this);

        // RHS depends on what follows the arrow
        if (node.right != null) node.right.accept(this);
        currentContext = saved;
    }

    private void visitCall(BinaryOperatorNode node) {
        // Subroutine call: LHS is the sub reference, RHS is args (LIST)
        int saved = currentContext;
        currentContext = RuntimeContextType.SCALAR;
        if (node.left != null) node.left.accept(this);

        currentContext = RuntimeContextType.LIST;
        if (node.right != null) node.right.accept(this);
        currentContext = saved;
    }

    private void visitBinaryDefault(BinaryOperatorNode node) {
        // Most binary operators take scalar operands
        int saved = currentContext;
        currentContext = RuntimeContextType.SCALAR;
        if (node.left != null) node.left.accept(this);
        if (node.right != null) node.right.accept(this);
        currentContext = saved;
    }

    private void visitMapBinary(BinaryOperatorNode node) {
        // map/grep/sort: left is block (scalar context per iteration), right is list (LIST context)
        int saved = currentContext;
        currentContext = RuntimeContextType.SCALAR;
        if (node.left != null) node.left.accept(this);

        currentContext = RuntimeContextType.LIST;
        if (node.right != null) node.right.accept(this);
        currentContext = saved;
    }

    private void visitPrintBinary(BinaryOperatorNode node) {
        // print/say/etc: LHS is filehandle (scalar), RHS is arguments (list)
        int saved = currentContext;
        currentContext = RuntimeContextType.SCALAR;
        if (node.left != null) node.left.accept(this);

        currentContext = RuntimeContextType.LIST;
        if (node.right != null) node.right.accept(this);
        currentContext = saved;
    }

    @Override
    public void visit(OperatorNode node) {
        setContext(node, currentContext);

        switch (node.operator) {
            case "$", "*" -> visitScalarDeref(node);
            case "@" -> visitArrayDeref(node);
            case "%" -> visitHashDeref(node);
            case "\\" -> visitReference(node);
            case "my", "our", "local", "state" -> visitDeclaration(node);
            case "return" -> visitReturn(node);
            case "scalar" -> visitScalarForce(node);
            case "wantarray" -> visitWantarray(node);
            case "print", "say", "printf", "warn", "die" -> visitPrintLike(node);
            case "push", "unshift" -> visitPushLike(node);
            case "pop", "shift" -> visitPopLike(node);
            case "keys", "values", "each" -> visitHashListOp(node);
            case "map", "grep", "sort" -> visitMapLike(node);
            case "split" -> visitSplit(node);
            case "join" -> visitJoin(node);
            case "select", "gmtime", "localtime", "caller", "reset", "times" -> visitListOperand(node);
            default -> visitOperatorDefault(node);
        }
    }

    private void visitScalarDeref(OperatorNode node) {
        // $ and * dereference: operand is scalar (the reference)
        int saved = currentContext;
        currentContext = RuntimeContextType.SCALAR;
        if (node.operand != null) node.operand.accept(this);
        currentContext = saved;
    }

    private void visitArrayDeref(OperatorNode node) {
        // @ dereference: the operand is scalar (array ref or name)
        int saved = currentContext;
        currentContext = RuntimeContextType.SCALAR;
        if (node.operand != null) node.operand.accept(this);
        currentContext = saved;
    }

    private void visitHashDeref(OperatorNode node) {
        // % dereference: the operand is scalar (hash ref or name)
        int saved = currentContext;
        currentContext = RuntimeContextType.SCALAR;
        if (node.operand != null) node.operand.accept(this);
        currentContext = saved;
    }

    private void visitReference(OperatorNode node) {
        // \ (reference): operand context doesn't matter - we take reference to the value
        // Use LIST context to avoid scalar-context evaluation of %hash or @array
        int saved = currentContext;
        currentContext = RuntimeContextType.LIST;
        if (node.operand != null) node.operand.accept(this);
        currentContext = saved;
    }

    private void visitDeclaration(OperatorNode node) {
        // my/our/local/state: pass through current context
        if (node.operand != null) node.operand.accept(this);
    }

    private void visitReturn(OperatorNode node) {
        // return passes caller's context (RUNTIME) to its argument
        int saved = currentContext;
        currentContext = RuntimeContextType.RUNTIME;
        if (node.operand != null) node.operand.accept(this);
        currentContext = saved;
    }

    private void visitScalarForce(OperatorNode node) {
        // scalar() forces scalar context
        int saved = currentContext;
        currentContext = RuntimeContextType.SCALAR;
        if (node.operand != null) node.operand.accept(this);
        currentContext = saved;
    }

    private void visitWantarray(OperatorNode node) {
        // wantarray takes no arguments
        setContext(node, currentContext);
    }

    private void visitPrintLike(OperatorNode node) {
        // print/say/etc take list context arguments
        int saved = currentContext;
        currentContext = RuntimeContextType.LIST;
        if (node.operand != null) node.operand.accept(this);
        currentContext = saved;
    }

    private void visitPushLike(OperatorNode node) {
        // push/unshift: first arg is scalar (array), rest is list
        // The operand is typically a ListNode
        if (node.operand instanceof ListNode list && list.elements.size() > 0) {
            int saved = currentContext;
            currentContext = RuntimeContextType.SCALAR;
            list.elements.get(0).accept(this);

            currentContext = RuntimeContextType.LIST;
            for (int i = 1; i < list.elements.size(); i++) {
                list.elements.get(i).accept(this);
            }
            currentContext = saved;
        } else {
            visitOperatorDefault(node);
        }
    }

    private void visitPopLike(OperatorNode node) {
        // pop/shift: argument is scalar (the array)
        int saved = currentContext;
        currentContext = RuntimeContextType.SCALAR;
        if (node.operand != null) node.operand.accept(this);
        currentContext = saved;
    }

    private void visitHashListOp(OperatorNode node) {
        // keys/values/each: argument is list context (to evaluate the hash/array)
        int saved = currentContext;
        currentContext = RuntimeContextType.LIST;
        if (node.operand != null) node.operand.accept(this);
        currentContext = saved;
    }

    private void visitMapLike(OperatorNode node) {
        // map/grep/sort: block is scalar context per iteration, list arg is list
        if (node.operand instanceof ListNode list && list.elements.size() >= 2) {
            int saved = currentContext;
            // First element (block/expr) executes in scalar context
            currentContext = RuntimeContextType.SCALAR;
            list.elements.get(0).accept(this);

            // Rest is the list to iterate
            currentContext = RuntimeContextType.LIST;
            for (int i = 1; i < list.elements.size(); i++) {
                list.elements.get(i).accept(this);
            }
            currentContext = saved;
        } else {
            visitOperatorDefault(node);
        }
    }

    private void visitSplit(OperatorNode node) {
        // split: pattern and string are scalar, limit is scalar
        int saved = currentContext;
        currentContext = RuntimeContextType.SCALAR;
        if (node.operand != null) node.operand.accept(this);
        currentContext = saved;
    }

    private void visitJoin(OperatorNode node) {
        // join: first arg (separator) is scalar, rest is list
        if (node.operand instanceof ListNode list && list.elements.size() > 0) {
            int saved = currentContext;
            currentContext = RuntimeContextType.SCALAR;
            list.elements.get(0).accept(this);

            currentContext = RuntimeContextType.LIST;
            for (int i = 1; i < list.elements.size(); i++) {
                list.elements.get(i).accept(this);
            }
            currentContext = saved;
        } else {
            visitOperatorDefault(node);
        }
    }

    private void visitOperatorDefault(OperatorNode node) {
        // Default: most unary operators use scalar context
        int saved = currentContext;
        currentContext = RuntimeContextType.SCALAR;
        if (node.operand != null) node.operand.accept(this);
        currentContext = saved;
    }

    private void visitListOperand(OperatorNode node) {
        // Operators that take list context operands: select, gmtime, localtime, caller, reset, times
        int saved = currentContext;
        currentContext = RuntimeContextType.LIST;
        if (node.operand != null) node.operand.accept(this);
        currentContext = saved;
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        setContext(node, currentContext);

        int saved = currentContext;
        // Condition is always scalar
        currentContext = RuntimeContextType.SCALAR;
        if (node.condition != null) node.condition.accept(this);

        // Both branches inherit outer context
        currentContext = saved;
        if (node.trueExpr != null) node.trueExpr.accept(this);
        if (node.falseExpr != null) node.falseExpr.accept(this);
    }

    @Override
    public void visit(IfNode node) {
        setContext(node, currentContext);

        int saved = currentContext;
        // Condition is scalar
        currentContext = RuntimeContextType.SCALAR;
        if (node.condition != null) node.condition.accept(this);

        // Branches inherit outer context
        currentContext = saved;
        if (node.thenBranch != null) node.thenBranch.accept(this);
        if (node.elseBranch != null) node.elseBranch.accept(this);
    }

    @Override
    public void visit(For1Node node) {
        setContext(node, currentContext);

        int saved = currentContext;
        // Variable declaration is void (side effect only)
        currentContext = RuntimeContextType.VOID;
        if (node.variable != null) node.variable.accept(this);

        // List is list context
        currentContext = RuntimeContextType.LIST;
        if (node.list != null) node.list.accept(this);

        // Body is void context (unless loop is used as expression)
        currentContext = RuntimeContextType.VOID;
        if (node.body != null) node.body.accept(this);
        if (node.continueBlock != null) node.continueBlock.accept(this);

        currentContext = saved;
    }

    @Override
    public void visit(For3Node node) {
        setContext(node, currentContext);

        int saved = currentContext;
        // Init, condition, increment are scalar/void
        currentContext = RuntimeContextType.VOID;
        if (node.initialization != null) node.initialization.accept(this);

        currentContext = RuntimeContextType.SCALAR;
        if (node.condition != null) node.condition.accept(this);

        currentContext = RuntimeContextType.VOID;
        if (node.increment != null) node.increment.accept(this);

        // Body is void context
        if (node.body != null) node.body.accept(this);
        if (node.continueBlock != null) node.continueBlock.accept(this);

        currentContext = saved;
    }

    @Override
    public void visit(SubroutineNode node) {
        setContext(node, currentContext);

        // Subroutine body executes in RUNTIME context (decided by caller)
        int saved = currentContext;
        currentContext = RuntimeContextType.RUNTIME;
        if (node.block != null) node.block.accept(this);
        currentContext = saved;
    }

    @Override
    public void visit(TryNode node) {
        setContext(node, currentContext);

        // try/catch/finally blocks inherit outer context for their last expression
        if (node.tryBlock != null) node.tryBlock.accept(this);

        int saved = currentContext;
        currentContext = RuntimeContextType.SCALAR;
        if (node.catchParameter != null) node.catchParameter.accept(this);
        currentContext = saved;

        if (node.catchBlock != null) node.catchBlock.accept(this);
        if (node.finallyBlock != null) node.finallyBlock.accept(this);
    }

    @Override
    public void visit(ListNode node) {
        setContext(node, currentContext);
        // List elements stay in current context (usually LIST)
        for (Node element : node.elements) {
            if (element != null) element.accept(this);
        }
        if (node.handle != null) {
            int saved = currentContext;
            currentContext = RuntimeContextType.SCALAR;
            node.handle.accept(this);
            currentContext = saved;
        }
    }

    @Override
    public void visit(HashLiteralNode node) {
        setContext(node, currentContext);
        // Hash literal elements are always in LIST context
        int saved = currentContext;
        currentContext = RuntimeContextType.LIST;
        for (Node element : node.elements) {
            if (element != null) element.accept(this);
        }
        currentContext = saved;
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        setContext(node, currentContext);
        // Array literal elements are always in LIST context
        int saved = currentContext;
        currentContext = RuntimeContextType.LIST;
        for (Node element : node.elements) {
            if (element != null) element.accept(this);
        }
        currentContext = saved;
    }

    @Override
    public void visit(IdentifierNode node) {
        setContext(node, currentContext);
    }

    @Override
    public void visit(NumberNode node) {
        setContext(node, currentContext);
    }

    @Override
    public void visit(StringNode node) {
        setContext(node, currentContext);
    }

    @Override
    public void visit(LabelNode node) {
        setContext(node, currentContext);
    }

    @Override
    public void visit(CompilerFlagNode node) {
        setContext(node, currentContext);
    }
}

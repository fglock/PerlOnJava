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

    /**
     * Visit a child node in the specified context, automatically saving and restoring currentContext.
     */
    private void visitInContext(Node node, int context) {
        if (node == null) return;
        int saved = currentContext;
        currentContext = context;
        node.accept(this);
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
            // Last statement inherits block's context, others are VOID
            int stmtContext = (i == size - 1) ? currentContext : RuntimeContextType.VOID;
            visitInContext(element, stmtContext);
        }
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        // Don't pre-set context here - some operators (like subscripts) need to compute their own context
        // Each case handler must call setContext or forceContext as appropriate

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
            case "push", "unshift" -> visitPushBinary(node);
            case "map", "grep", "sort", "all", "any" -> visitMapBinary(node);
            case "join", "sprintf", "split", "binmode", "seek" -> visitJoinBinary(node);
            case "x" -> visitRepeat(node);
            default -> { setContext(node, currentContext); visitBinaryDefault(node); }
        }
    }

    private void visitAssignment(BinaryOperatorNode node) {
        setContext(node, currentContext);
        // LHS determines context for RHS
        int lhsContext = LValueVisitor.getContext(node.left);
        int rhsContext = (lhsContext == RuntimeContextType.LIST)
                ? RuntimeContextType.LIST
                : RuntimeContextType.SCALAR;

        visitInContext(node.left, lhsContext);
        visitInContext(node.right, rhsContext);
    }

    private void visitLogicalOp(BinaryOperatorNode node) {
        setContext(node, currentContext);
        // LHS is scalar (for boolean test)
        visitInContext(node.left, RuntimeContextType.SCALAR);
        // RHS: In LIST context, evaluated in LIST; otherwise SCALAR for short-circuit mechanics
        int rhsContext = (currentContext == RuntimeContextType.LIST) 
                ? RuntimeContextType.LIST 
                : RuntimeContextType.SCALAR;
        visitInContext(node.right, rhsContext);
    }

    private void visitBindingOp(BinaryOperatorNode node) {
        setContext(node, currentContext);
        // =~ and !~: LHS is scalar, RHS is the regex (scalar)
        visitInContext(node.left, RuntimeContextType.SCALAR);
        visitInContext(node.right, RuntimeContextType.SCALAR);
    }

    private void visitCommaOp(BinaryOperatorNode node) {
        setContext(node, currentContext);
        if (currentContext == RuntimeContextType.LIST) {
            // In list context, both sides contribute to the list
            visitInContext(node.left, RuntimeContextType.LIST);
            visitInContext(node.right, RuntimeContextType.LIST);
        } else {
            // In scalar/void context, LHS is void, RHS is the result
            visitInContext(node.left, RuntimeContextType.VOID);
            visitInContext(node.right, currentContext);
        }
    }

    private void visitTernaryPart(BinaryOperatorNode node) {
        setContext(node, currentContext);
        // This handles the ":" part of ternary - both branches inherit context
        visitInContext(node.left, currentContext);
        visitInContext(node.right, currentContext);
    }

    private void visitSubscript(BinaryOperatorNode node) {
        // $a[idx] or $a{key}: returns scalar element
        // @a[list] or @a{list}: slice - returns list
        boolean isSlice = node.left instanceof OperatorNode opNode &&
                ("@".equals(opNode.operator) || "%".equals(opNode.operator));
        
        // Set node context: slice returns LIST, single element returns SCALAR
        // Exception: if parent wants RUNTIME, keep RUNTIME (emitter may pass RUNTIME for return)
        int subscriptContext = isSlice ? RuntimeContextType.LIST : RuntimeContextType.SCALAR;
        if (currentContext == RuntimeContextType.RUNTIME && !isSlice) {
            subscriptContext = RuntimeContextType.RUNTIME;
        }
        setContext(node, subscriptContext);
        
        // Left side of subscript: the container reference
        // For non-slices, emitter always needs SCALAR (the reference to subscript into)
        // For slices, emitter needs LIST (the array/hash itself)
        int leftContext = isSlice ? RuntimeContextType.LIST : RuntimeContextType.SCALAR;
        visitInContext(node.left, leftContext);
        
        // For subscript indices, visit elements directly (mirroring emitter behavior)
        // The emitter accesses node.right.elements directly, not visiting ArrayLiteralNode/HashLiteralNode
        int indexContext = isSlice ? RuntimeContextType.LIST : RuntimeContextType.SCALAR;
        if (node.right instanceof ArrayLiteralNode aln) {
            setContext(aln, indexContext);
            for (Node element : aln.elements) {
                visitInContext(element, indexContext);
            }
        } else if (node.right instanceof HashLiteralNode hln) {
            setContext(hln, indexContext);
            for (Node element : hln.elements) {
                visitInContext(element, indexContext);
            }
        } else {
            visitInContext(node.right, indexContext);
        }
    }

    private void visitArrow(BinaryOperatorNode node) {
        setContext(node, currentContext);
        // ->[] ->{} ->() : LHS is scalar (the reference)
        visitInContext(node.left, RuntimeContextType.SCALAR);
        
        // RHS context depends on what follows:
        // ->[] or ->{} : subscript indices are SCALAR (emitter accesses elements directly in handleArrowArrayDeref/HashDeref)
        // ->() : call args are LIST
        // Everything else: inherit outer context
        if (node.right instanceof ArrayLiteralNode aln) {
            // Subscript: visit elements directly in SCALAR (mirroring emitter behavior)
            setContext(aln, RuntimeContextType.SCALAR);
            for (Node element : aln.elements) {
                visitInContext(element, RuntimeContextType.SCALAR);
            }
        } else if (node.right instanceof HashLiteralNode hln) {
            // Subscript: visit elements directly in SCALAR
            setContext(hln, RuntimeContextType.SCALAR);
            for (Node element : hln.elements) {
                visitInContext(element, RuntimeContextType.SCALAR);
            }
        } else if (node.right instanceof ListNode) {
            // Method call args: LIST
            visitInContext(node.right, RuntimeContextType.LIST);
        } else {
            // Other cases: inherit outer context
            visitInContext(node.right, currentContext);
        }
    }

    private void visitCall(BinaryOperatorNode node) {
        setContext(node, currentContext);
        // Subroutine call: LHS is the sub reference, RHS is args (LIST)
        visitInContext(node.left, RuntimeContextType.SCALAR);
        visitInContext(node.right, RuntimeContextType.LIST);
    }

    private void visitBinaryDefault(BinaryOperatorNode node) {
        // Most binary operators take scalar operands
        // setContext already called at call site
        visitInContext(node.left, RuntimeContextType.SCALAR);
        visitInContext(node.right, RuntimeContextType.SCALAR);
    }

    private void visitJoinBinary(BinaryOperatorNode node) {
        setContext(node, currentContext);
        // join/sprintf: left (separator/format) is SCALAR, right (list to join/args) is LIST
        visitInContext(node.left, RuntimeContextType.SCALAR);
        visitInContext(node.right, RuntimeContextType.LIST);
    }

    private void visitRepeat(BinaryOperatorNode node) {
        setContext(node, currentContext);
        // x operator: left context depends on outer context and operand type
        // In LIST context with ListNode left operand: left=LIST (repeat list)
        // Otherwise: left=SCALAR (repeat string)
        if (currentContext != RuntimeContextType.SCALAR && node.left instanceof ListNode) {
            visitInContext(node.left, RuntimeContextType.LIST);
        } else {
            visitInContext(node.left, RuntimeContextType.SCALAR);
        }
        visitInContext(node.right, RuntimeContextType.SCALAR);
    }

    private void visitPushBinary(BinaryOperatorNode node) {
        setContext(node, currentContext);
        // push/unshift as BinaryOperatorNode: left=array (LIST), right=values (LIST)
        visitInContext(node.left, RuntimeContextType.LIST);
        visitInContext(node.right, RuntimeContextType.LIST);
    }

    private void visitMapBinary(BinaryOperatorNode node) {
        setContext(node, currentContext);
        // map/grep/sort: left is block (scalar context per iteration), right is list (LIST context)
        visitInContext(node.left, RuntimeContextType.SCALAR);
        visitInContext(node.right, RuntimeContextType.LIST);
    }

    private void visitPrintBinary(BinaryOperatorNode node) {
        setContext(node, currentContext);
        // print/say/etc: LHS is filehandle (scalar), RHS is arguments (list)
        visitInContext(node.left, RuntimeContextType.SCALAR);
        visitInContext(node.right, RuntimeContextType.LIST);
    }

    @Override
    public void visit(OperatorNode node) {
        switch (node.operator) {
            // Sigil operators - context depends on sigil type
            case "$", "*" -> { setContext(node, currentContext); visitScalarDeref(node); }
            case "@" -> { setContext(node, currentContext); visitArrayDeref(node); }
            case "%" -> { setContext(node, currentContext); visitHashDeref(node); }
            case "\\" -> { setContext(node, currentContext); visitReference(node); }
            
            // Declarations pass through context
            case "my", "our", "local", "state" -> { setContext(node, currentContext); visitDeclaration(node); }
            case "return" -> { setContext(node, currentContext); visitReturn(node); }
            case "undef" -> { setContext(node, currentContext); visitUndef(node); }
            case "scalar" -> { setContext(node, currentContext); visitScalarForce(node); }
            case "wantarray" -> { setContext(node, RuntimeContextType.SCALAR); visitWantarray(node); }
            
            // Print-like operators
            case "print", "say", "printf", "warn", "die" -> { setContext(node, currentContext); visitPrintLike(node); }
            
            // Array manipulation - produce SCALAR, but inherit RUNTIME inside subs
            case "push", "unshift" -> { 
                int ctx = (currentContext == RuntimeContextType.RUNTIME) 
                        ? RuntimeContextType.RUNTIME 
                        : RuntimeContextType.SCALAR;
                setContext(node, ctx); visitPushLike(node); 
            }
            case "pop", "shift" -> { 
                int ctx = (currentContext == RuntimeContextType.RUNTIME) 
                        ? RuntimeContextType.RUNTIME 
                        : RuntimeContextType.SCALAR;
                setContext(node, ctx); visitPopLike(node); 
            }
            
            // Hash/array operators that return lists
            case "keys", "values", "each" -> { setContext(node, currentContext); visitHashListOp(node); }
            case "map", "grep", "sort" -> { setContext(node, currentContext); visitMapLike(node); }
            case "split" -> { setContext(node, currentContext); visitSplit(node); }
            case "join" -> { setContext(node, RuntimeContextType.SCALAR); visitJoin(node); }
            case "sprintf" -> { setContext(node, RuntimeContextType.SCALAR); visitSprintfOp(node); }
            
            // Operators with LIST operands
            case "select", "gmtime", "localtime", "caller", "reset", "times" -> { 
                setContext(node, currentContext); visitListOperand(node); 
            }
            case "pack", "mkdir", "opendir", "seekdir", "crypt", "vec", "read", "chmod",
                 "chop", "chomp", "system", "exec", "$#", "splice", "reverse",
                 "chown", "kill", "unlink", "utime" -> { 
                setContext(node, currentContext); visitListOperand(node); 
            }
            
            // Numeric/string operators - inherit context from parent
            // These produce scalar values but the node itself should have parent's context
            // (the emitter passes parent context, not forced SCALAR)
            case "unaryMinus", "unaryPlus", "~", "!", "not",
                 "abs", "int", "sqrt", "sin", "cos", "exp", "log", "rand",
                 "length", "defined", "exists", "ref",
                 "ord", "chr", "hex", "oct",
                 "lc", "uc", "lcfirst", "ucfirst", "quotemeta",
                 "++", "--", "++postfix", "--postfix" -> { 
                setContext(node, currentContext); 
                visitOperatorDefault(node); 
            }
            
            // Default: inherit context, operand is SCALAR
            default -> { setContext(node, currentContext); visitOperatorDefault(node); }
        }
    }

    private void visitScalarDeref(OperatorNode node) {
        // $ and * dereference: operand is scalar (the reference)
        visitInContext(node.operand, RuntimeContextType.SCALAR);
    }

    private void visitArrayDeref(OperatorNode node) {
        // @ dereference: the operand is scalar (array ref or name)
        visitInContext(node.operand, RuntimeContextType.SCALAR);
    }

    private void visitHashDeref(OperatorNode node) {
        // % dereference: the operand is scalar (hash ref or name)
        visitInContext(node.operand, RuntimeContextType.SCALAR);
    }

    private void visitReference(OperatorNode node) {
        // \ (reference): operand context doesn't matter - we take reference to the value
        // Use LIST context to avoid scalar-context evaluation of %hash or @array
        visitInContext(node.operand, RuntimeContextType.LIST);
    }

    private void visitDeclaration(OperatorNode node) {
        // my/our/local/state: pass through current context
        visitInContext(node.operand, currentContext);
    }

    private void visitReturn(OperatorNode node) {
        // return passes caller's context (RUNTIME) to its argument
        // Mirror emitter behavior: unwrap single-element ListNodes
        if (node.operand instanceof ListNode list && list.elements.size() == 1) {
            setContext(list, RuntimeContextType.RUNTIME);
            visitInContext(list.elements.getFirst(), RuntimeContextType.RUNTIME);
        } else {
            visitInContext(node.operand, RuntimeContextType.RUNTIME);
        }
    }

    private void visitUndef(OperatorNode node) {
        // undef: operand is evaluated in RUNTIME context (to handle list assignment)
        visitInContext(node.operand, RuntimeContextType.RUNTIME);
    }

    private void visitScalarForce(OperatorNode node) {
        // scalar() forces scalar context
        visitInContext(node.operand, RuntimeContextType.SCALAR);
    }

    private void visitWantarray(OperatorNode node) {
        // wantarray takes no arguments
        setContext(node, currentContext);
    }

    private void visitPrintLike(OperatorNode node) {
        // print/say/etc take list context arguments
        visitInContext(node.operand, RuntimeContextType.LIST);
    }

    private void visitPushLike(OperatorNode node) {
        // push/unshift: first arg is scalar (array), rest is list
        // The operand is typically a ListNode which the emitter visits in LIST context
        if (node.operand instanceof ListNode list && list.elements.size() > 0) {
            // The ListNode itself is visited in LIST context by the emitter
            setContext(list, RuntimeContextType.LIST);
            visitInContext(list.elements.get(0), RuntimeContextType.SCALAR);
            for (int i = 1; i < list.elements.size(); i++) {
                visitInContext(list.elements.get(i), RuntimeContextType.LIST);
            }
        } else {
            visitOperatorDefault(node);
        }
    }

    private void visitPopLike(OperatorNode node) {
        // pop/shift: argument needs LIST context to get the array object
        // (handleArrayUnaryBuiltin passes LIST to get RuntimeArray)
        visitInContext(node.operand, RuntimeContextType.LIST);
    }

    private void visitHashListOp(OperatorNode node) {
        // keys/values/each: argument is list context (to evaluate the hash/array)
        visitInContext(node.operand, RuntimeContextType.LIST);
    }

    private void visitMapLike(OperatorNode node) {
        // map/grep/sort: block is scalar context per iteration, list arg is list
        if (node.operand instanceof ListNode list && list.elements.size() >= 2) {
            // First element (block/expr) executes in scalar context
            visitInContext(list.elements.get(0), RuntimeContextType.SCALAR);
            // Rest is the list to iterate
            for (int i = 1; i < list.elements.size(); i++) {
                visitInContext(list.elements.get(i), RuntimeContextType.LIST);
            }
        } else {
            visitOperatorDefault(node);
        }
    }

    private void visitSplit(OperatorNode node) {
        // split: pattern and string are scalar, limit is scalar
        visitInContext(node.operand, RuntimeContextType.SCALAR);
    }

    private void visitJoin(OperatorNode node) {
        // join: first arg (separator) is scalar, rest is list
        if (node.operand instanceof ListNode list && list.elements.size() > 0) {
            visitInContext(list.elements.get(0), RuntimeContextType.SCALAR);
            for (int i = 1; i < list.elements.size(); i++) {
                visitInContext(list.elements.get(i), RuntimeContextType.LIST);
            }
        } else {
            visitOperatorDefault(node);
        }
    }

    private void visitSprintfOp(OperatorNode node) {
        // sprintf: first arg (format) is scalar, rest are list
        // Mirrors interpreter's visitSprintf which compiles args[1..n] with LIST
        if (node.operand instanceof ListNode list && list.elements.size() > 0) {
            visitInContext(list.elements.get(0), RuntimeContextType.SCALAR);
            for (int i = 1; i < list.elements.size(); i++) {
                visitInContext(list.elements.get(i), RuntimeContextType.LIST);
            }
        } else {
            visitOperatorDefault(node);
        }
    }

    private void visitOperatorDefault(OperatorNode node) {
        // Default: most unary operators use scalar context
        visitInContext(node.operand, RuntimeContextType.SCALAR);
    }

    private void visitListOperand(OperatorNode node) {
        // Operators that take list context operands: select, gmtime, localtime, caller, reset, times
        visitInContext(node.operand, RuntimeContextType.LIST);
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        setContext(node, currentContext);
        // Condition is always scalar
        visitInContext(node.condition, RuntimeContextType.SCALAR);
        // Both branches inherit outer context
        visitInContext(node.trueExpr, currentContext);
        visitInContext(node.falseExpr, currentContext);
    }

    @Override
    public void visit(IfNode node) {
        setContext(node, currentContext);
        // Condition is scalar
        visitInContext(node.condition, RuntimeContextType.SCALAR);
        // Branches inherit outer context
        visitInContext(node.thenBranch, currentContext);
        visitInContext(node.elseBranch, currentContext);
    }

    @Override
    public void visit(For1Node node) {
        setContext(node, currentContext);
        // Variable declaration is void (side effect only)
        visitInContext(node.variable, RuntimeContextType.VOID);
        // List is list context
        visitInContext(node.list, RuntimeContextType.LIST);
        // Body is void context (unless loop is used as expression)
        visitInContext(node.body, RuntimeContextType.VOID);
        visitInContext(node.continueBlock, RuntimeContextType.VOID);
    }

    @Override
    public void visit(For3Node node) {
        setContext(node, currentContext);
        // Init, condition, increment are scalar/void
        visitInContext(node.initialization, RuntimeContextType.VOID);
        visitInContext(node.condition, RuntimeContextType.SCALAR);
        visitInContext(node.increment, RuntimeContextType.VOID);
        // Body is void context
        visitInContext(node.body, RuntimeContextType.VOID);
        visitInContext(node.continueBlock, RuntimeContextType.VOID);
    }

    @Override
    public void visit(SubroutineNode node) {
        setContext(node, currentContext);
        // Subroutine body executes in RUNTIME context (decided by caller)
        visitInContext(node.block, RuntimeContextType.RUNTIME);
    }

    @Override
    public void visit(TryNode node) {
        setContext(node, currentContext);
        // try/catch/finally blocks inherit outer context for their last expression
        visitInContext(node.tryBlock, currentContext);
        visitInContext(node.catchParameter, RuntimeContextType.SCALAR);
        visitInContext(node.catchBlock, currentContext);
        visitInContext(node.finallyBlock, currentContext);
    }

    @Override
    public void visit(ListNode node) {
        setContext(node, currentContext);
        // List elements stay in current context (usually LIST)
        for (Node element : node.elements) {
            visitInContext(element, currentContext);
        }
        visitInContext(node.handle, RuntimeContextType.SCALAR);
    }

    @Override
    public void visit(HashLiteralNode node) {
        setContext(node, currentContext);
        // Hash literal elements are always in LIST context
        // (subscript indices are handled directly in visitSubscript/visitArrow, not here)
        // Emitter's emitHashLiteral always uses LIST: acceptChild(listNode, LIST)
        for (Node element : node.elements) {
            visitInContext(element, RuntimeContextType.LIST);
        }
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        setContext(node, currentContext);
        // Array literal elements are always in LIST context
        // (subscript indices are handled directly in visitSubscript, not here)
        // Emitter's emitArrayLiteral always uses LIST: elementContext = emitterVisitor.with(LIST)
        for (Node element : node.elements) {
            visitInContext(element, RuntimeContextType.LIST);
        }
    }

    @Override
    public void visit(IdentifierNode node) {
        setContext(node, currentContext);
    }

    @Override
    public void visit(NumberNode node) {
        // Numbers inherit parent's visitation context to match emitter
        setContext(node, currentContext);
    }

    @Override
    public void visit(StringNode node) {
        // Strings inherit parent's visitation context to match emitter
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

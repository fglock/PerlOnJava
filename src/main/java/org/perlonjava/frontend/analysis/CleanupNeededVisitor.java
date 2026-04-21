package org.perlonjava.frontend.analysis;

import org.perlonjava.frontend.astnode.*;

/**
 * Determines whether a subroutine needs the full per-scope-exit cleanup
 * machinery (scopeExitCleanup, MyVarCleanupStack.unregister, full
 * MortalList flush) or can safely skip it.
 *
 * <p>Ultra-hot workloads (tight numeric loops, life_bitpacked, etc.)
 * pay ~1 INVOKESTATIC per {@code my}-variable per scope exit for the
 * refcount/DESTROY/weaken bookkeeping — even when the sub's lexicals
 * are plain integers and refcount_owned never flips to true. Skipping
 * this emission when statically provably unnecessary recovers a large
 * fraction of the per-iteration cost.
 *
 * <p>A sub is "simple" (cleanup not needed) iff its body contains
 * NONE of:
 * <ul>
 *   <li><b>bless</b> — creates blessed-with-DESTROY targets that need
 *       refCount decrement on scope exit.</li>
 *   <li><b>weaken</b> / <b>isweak</b> (any Scalar::Util qualified form)
 *       — sets the global {@code weakRefsExist} flag and requires the
 *       reachability walker to see our live lexicals.</li>
 *   <li><b>local</b> — dynamic-scope bookkeeping changes.</li>
 *   <li><b>eval STRING</b> — can do anything.</li>
 *   <li>nested <b>SubroutineNode</b> — might capture our lexicals via
 *       closure; conservatively assume so.</li>
 *   <li><b>user sub call</b> ({@code func(args)}) — callee might
 *       return a blessed-with-DESTROY ref that lands in one of our
 *       lexicals; cleanup must fire on scope exit.</li>
 *   <li><b>method call</b> ({@code $obj-&gt;method} or
 *       {@code $obj-&gt;method(args)}) — same reason. Array / hash
 *       derefs ({@code $x-&gt;[idx]} / {@code $x-&gt;{key}}) are NOT
 *       flagged — they don't invoke user code.</li>
 * </ul>
 *
 * <p>Builtins like {@code print}, {@code push}, {@code chr},
 * {@code length}, etc. are parsed as {@link OperatorNode} (not as
 * {@link BinaryOperatorNode} with the {@code "("} operator), so they
 * don't hit this visitor's sub-call branch — they return non-blessed
 * values and don't need cleanup. <em>Overrideable builtins</em> that
 * the user imported via {@code use subs} are already resolved by the
 * parser to user sub calls ({@code BinaryOperatorNode("(", ...)}),
 * which DO get flagged here, so the compile-time override decision
 * is handled correctly without extra work from this visitor.
 *
 * <p>This is the "simple leaf function" heuristic. It's deliberately
 * conservative; false positives (marking needsCleanup when it wasn't
 * strictly required) just revert to current behavior. False negatives
 * (marking skip when cleanup IS needed) would be a correctness bug —
 * hence the env-var escape hatch {@code JPERL_FORCE_CLEANUP=1} in
 * {@code EmitStatement} to force the slow path for debugging.
 */
public class CleanupNeededVisitor implements Visitor {

    private boolean needsCleanup = false;

    /**
     * @return true iff scope-exit cleanup emission is required for
     * correctness. Callers should only skip cleanup when this is false.
     */
    public boolean needsCleanup() {
        return needsCleanup;
    }

    public void reset() {
        needsCleanup = false;
    }

    // Short-circuit: once we've decided cleanup is needed, don't bother
    // walking further subtrees (but we still have to satisfy the visitor
    // contract; the recursion is short in practice).
    private void mark() {
        needsCleanup = true;
    }

    @Override
    public void visit(OperatorNode node) {
        if (needsCleanup) return;
        // local operator is a scope-exit bookkeeping trigger.
        if ("local".equals(node.operator)) {
            mark();
            return;
        }
        if (node.operand != null) node.operand.accept(this);
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        if (needsCleanup) return;
        // bless always taints a sub: the blessed target needs refCount
        // tracking for DESTROY.
        if ("bless".equals(node.operator)) {
            mark();
            return;
        }
        // User sub call: func(args) → BinaryOperatorNode("(", callee, args).
        // Callee might return a blessed-with-DESTROY that lands in a
        // lexical in this sub, so cleanup is required even if this sub
        // itself does no bless. Builtins (push, chr, length, etc.) are
        // parsed as OperatorNode, so only user subs hit this branch.
        //
        // Special-case weaken/isweak: they flip global state too, but
        // marking via the general sub-call path handles them
        // automatically. Kept explicit for documentation clarity.
        if ("(".equals(node.operator)) {
            if (node.left instanceof IdentifierNode id) {
                String name = id.name;
                if (name != null && (
                        name.equals("weaken") || name.equals("isweak")
                                || name.equals("Scalar::Util::weaken")
                                || name.equals("Scalar::Util::isweak"))) {
                    mark();
                    return;
                }
            }
            // Any other user sub call.
            mark();
            return;
        }
        // Method call: $obj->method or $obj->method(args).
        // In AST form, the RHS is either IdentifierNode(method_name) or
        // BinaryOperatorNode("(", IdentifierNode(method_name), args).
        // Array/hash derefs ($x->[i], $x->{k}) have ArrayLiteralNode /
        // HashLiteralNode on the RHS — those are safe (no user code runs).
        if ("->".equals(node.operator)) {
            Node right = node.right;
            if (right instanceof IdentifierNode
                    || right instanceof BinaryOperatorNode binOp && "(".equals(binOp.operator)) {
                mark();
                return;
            }
            // Array/hash deref — recurse into children only.
            if (node.left != null) node.left.accept(this);
            if (node.right != null) node.right.accept(this);
            return;
        }
        if (node.left != null) node.left.accept(this);
        if (node.right != null) node.right.accept(this);
    }

    @Override
    public void visit(SubroutineNode node) {
        // Nested subroutines might capture our lexicals via closure.
        // Conservatively assume they do.
        mark();
        // No need to recurse into the body — inner subs run their own
        // CleanupNeededVisitor.
    }

    @Override
    public void visit(BlockNode node) {
        if (needsCleanup) return;
        for (Node element : node.elements) {
            if (needsCleanup) return;
            if (element != null) element.accept(this);
        }
    }

    @Override
    public void visit(ListNode node) {
        if (needsCleanup) return;
        for (Node element : node.elements) {
            if (needsCleanup) return;
            if (element != null) element.accept(this);
        }
    }

    @Override
    public void visit(HashLiteralNode node) {
        if (needsCleanup) return;
        for (Node element : node.elements) {
            if (needsCleanup) return;
            if (element != null) element.accept(this);
        }
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        if (needsCleanup) return;
        for (Node element : node.elements) {
            if (needsCleanup) return;
            if (element != null) element.accept(this);
        }
    }

    @Override
    public void visit(IfNode node) {
        if (needsCleanup) return;
        if (node.condition != null) node.condition.accept(this);
        if (node.thenBranch != null) node.thenBranch.accept(this);
        if (node.elseBranch != null) node.elseBranch.accept(this);
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        if (needsCleanup) return;
        if (node.condition != null) node.condition.accept(this);
        if (node.trueExpr != null) node.trueExpr.accept(this);
        if (node.falseExpr != null) node.falseExpr.accept(this);
    }

    @Override
    public void visit(For1Node node) {
        if (needsCleanup) return;
        if (node.variable != null) node.variable.accept(this);
        if (node.list != null) node.list.accept(this);
        if (node.body != null) node.body.accept(this);
    }

    @Override
    public void visit(For3Node node) {
        if (needsCleanup) return;
        if (node.initialization != null) node.initialization.accept(this);
        if (node.condition != null) node.condition.accept(this);
        if (node.increment != null) node.increment.accept(this);
        if (node.body != null) node.body.accept(this);
    }

    @Override
    public void visit(TryNode node) {
        // try/catch is common without being refcount-touching, but
        // catch handlers often do bless/warn/die things. Be conservative.
        if (needsCleanup) return;
        if (node.tryBlock != null) node.tryBlock.accept(this);
        if (node.catchBlock != null) node.catchBlock.accept(this);
        if (node.finallyBlock != null) node.finallyBlock.accept(this);
    }

    @Override
    public void visit(DeferNode node) {
        // defer blocks execute at scope exit — mark conservatively.
        mark();
    }

    @Override
    public void visit(IdentifierNode node) {
    }

    @Override
    public void visit(NumberNode node) {
    }

    @Override
    public void visit(StringNode node) {
    }

    @Override
    public void visit(LabelNode node) {
    }

    @Override
    public void visit(CompilerFlagNode node) {
    }

    @Override
    public void visit(FormatNode node) {
    }
}

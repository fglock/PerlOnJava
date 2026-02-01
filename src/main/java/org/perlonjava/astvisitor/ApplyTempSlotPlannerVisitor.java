package org.perlonjava.astvisitor;

import org.perlonjava.astnode.*;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.codegen.TempSlotPlan;

/**
 * Prepass to reserve temp slots (in a side table) for hotspot nodes.
 * Currently targets postfix apply operator nodes (BinaryOperatorNode with operator "(").
 */
public class ApplyTempSlotPlannerVisitor implements Visitor {
    private final EmitterContext ctx;

    public ApplyTempSlotPlannerVisitor(EmitterContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        if (node != null && "(".equals(node.operator)) {
            // Reserve the temps used by EmitSubroutine.handleApplyOperator
            ctx.tempSlotPlan.getOrAssign(node, "callContext", TempSlotPlan.TempKind.INT, ctx);
            ctx.tempSlotPlan.getOrAssign(node, "codeRef", TempSlotPlan.TempKind.REF, ctx);
            ctx.tempSlotPlan.getOrAssign(node, "name", TempSlotPlan.TempKind.REF, ctx);
            ctx.tempSlotPlan.getOrAssign(node, "argsArray", TempSlotPlan.TempKind.REF, ctx);
        }
        if (node != null) {
            if (node.left != null) node.left.accept(this);
            if (node.right != null) node.right.accept(this);
        }
    }

    @Override
    public void visit(BlockNode node) {
        if (node == null) return;
        for (Node el : node.elements) {
            if (el != null) el.accept(this);
        }
    }

    @Override
    public void visit(ListNode node) {
        if (node == null) return;
        for (Node el : node.elements) {
            if (el != null) el.accept(this);
        }
    }

    @Override public void visit(IdentifierNode node) {}
    @Override public void visit(NumberNode node) {}
    @Override public void visit(StringNode node) {}
    @Override public void visit(HashLiteralNode node) {
        if (node == null) return;
        for (Node el : node.elements) if (el != null) el.accept(this);
    }
    @Override public void visit(ArrayLiteralNode node) {
        if (node == null) return;
        for (Node el : node.elements) if (el != null) el.accept(this);
    }
    @Override public void visit(TernaryOperatorNode node) {
        if (node == null) return;
        if (node.condition != null) node.condition.accept(this);
        if (node.trueExpr != null) node.trueExpr.accept(this);
        if (node.falseExpr != null) node.falseExpr.accept(this);
    }
    @Override public void visit(IfNode node) {
        if (node == null) return;
        if (node.condition != null) node.condition.accept(this);
        if (node.thenBranch != null) node.thenBranch.accept(this);
        if (node.elseBranch != null) node.elseBranch.accept(this);
    }
    @Override public void visit(For1Node node) {
        if (node == null) return;
        if (node.variable != null) node.variable.accept(this);
        if (node.list != null) node.list.accept(this);
        if (node.body != null) node.body.accept(this);
    }
    @Override public void visit(For3Node node) {
        if (node == null) return;
        if (node.initialization != null) node.initialization.accept(this);
        if (node.condition != null) node.condition.accept(this);
        if (node.increment != null) node.increment.accept(this);
        if (node.body != null) node.body.accept(this);
    }
    @Override public void visit(OperatorNode node) {
        if (node != null && node.operand != null) node.operand.accept(this);
    }
    @Override public void visit(SubroutineNode node) {
        // Do not recurse into nested sub bodies (separate compilation unit)
    }
    @Override public void visit(TryNode node) {
        if (node == null) return;
        if (node.tryBlock != null) node.tryBlock.accept(this);
        if (node.catchBlock != null) node.catchBlock.accept(this);
        if (node.finallyBlock != null) node.finallyBlock.accept(this);
    }
    @Override public void visit(LabelNode node) {}
    @Override public void visit(CompilerFlagNode node) {}
    @Override public void visit(FormatNode node) {}
    @Override public void visit(FormatLine node) {}
}

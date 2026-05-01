package org.perlonjava.frontend.analysis;

import org.perlonjava.frontend.astnode.*;

/**
 * Visitor that counts the maximum number of temporary local variables
 * that will be needed during bytecode emission.
 * <p>
 * This is used to pre-initialize the correct number of slots to avoid
 * VerifyError when slots are in TOP state.
 * <p>
 * Counting rules — only patterns that call allocateLocalVariable() directly
 * (bypassing the spill pool) are counted here.  Operators that go through
 * acquireSpillSlot() first are covered by the fixed +N buffer in
 * EmitterMethodCreator.  Infrastructure slots (tailCallCodeRefSlot,
 * controlFlowActionSlot, 16 spill slots, returnValueSlot, etc.) are also
 * absorbed by that buffer.
 */
public class TempLocalCountVisitor implements Visitor {
    private int tempCount = 0;

    /**
     * Get the estimated number of temporary locals needed.
     *
     * @return The temp count
     */
    public int getMaxTempCount() {
        return tempCount;
    }

    /**
     * Reset the counter for reuse.
     */
    public void reset() {
        tempCount = 0;
    }

    private void countTemp() {
        tempCount++;
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        switch (node.operator) {
            // Logical operators (&&, ||, //) store LHS in a spill slot for short-circuit.
            // Using acquireSpillSlot() first, but count conservatively in case pool is full.
            case "&&", "||", "//" -> countTemp();

            // Dereference (->) conservatively counts 1 temp for method/element access.
            case "->" -> countTemp();

            // Sub-call apply operator: always allocates callContextSlot directly.
            case "(" -> countTemp();

            // Flip-flop operators in scalar context always allocate 3 slots directly:
            // flipFlopIdSlot, leftSlot, rightSlot.  Overcounts for list-context range
            // usage, but that is safe.
            case "..", "..." -> {
                countTemp(); // flipFlopIdSlot
                countTemp(); // leftSlot
                countTemp(); // rightSlot
            }

            // xor/^^ always allocates leftVar directly (no spill).
            case "xor", "^^" -> countTemp();
        }
        if (node.left != null) node.left.accept(this);
        if (node.right != null) node.right.accept(this);
    }

    @Override
    public void visit(BlockNode node) {
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
            }
        }
    }

    @Override
    public void visit(For1Node node) {
        // foreach loop.  Guaranteed direct allocations:
        //   preEvalListLocal  (always, unless preEvaluatedArrayIndex >= 0)
        //   iteratorIndex     (always)
        // Common conditional direct allocations:
        //   savedLoopVarIndex (when loop var is an existing lexical)
        //   foreachRegexStateLocal (when body contains regex)
        // Count 4 conservatively to cover the most common cases.
        countTemp(); // preEvalListLocal
        countTemp(); // iteratorIndex
        countTemp(); // savedLoopVarIndex / dynamicIndex (common)
        countTemp(); // foreachRegexStateLocal (if body has regex)
        if (node.variable != null) node.variable.accept(this);
        if (node.list != null) node.list.accept(this);
        if (node.body != null) node.body.accept(this);
    }

    @Override
    public void visit(For3Node node) {
        // while/for/do-while loop.  Conditional direct allocations:
        //   regexStateLocal      (when body contains regex)
        //   conditionResultReg   (non-void context with a condition)
        // Count 1 conservatively.
        countTemp();
        if (node.initialization != null) node.initialization.accept(this);
        if (node.condition != null) node.condition.accept(this);
        if (node.increment != null) node.increment.accept(this);
        if (node.body != null) node.body.accept(this);
    }

    @Override
    public void visit(ListNode node) {
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
            }
        }
    }

    @Override
    public void visit(OperatorNode node) {
        switch (node.operator) {
            // local() allocates a temp for dynamic variable tracking.
            case "local" -> countTemp();

            // eval/evalbytes always allocate 4 slots directly:
            //   evalResultSlot, cfSlot, labelSlot, typeSlot.
            case "eval", "evalbytes" -> {
                countTemp(); // evalResultSlot
                countTemp(); // cfSlot
                countTemp(); // labelSlot
                countTemp(); // typeSlot
            }
        }
        if (node.operand != null) {
            node.operand.accept(this);
        }
    }

    @Override
    public void visit(SubroutineNode node) {
        // Nested subroutines have their own EmitterMethodCreator context
        // and separate temp local space, so we don't need to count their temps
        // Don't recurse into the subroutine body
    }

    // Default implementations for other node types
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
    public void visit(HashLiteralNode node) {
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
            }
        }
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
            }
        }
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        if (node.condition != null) node.condition.accept(this);
        if (node.trueExpr != null) node.trueExpr.accept(this);
        if (node.falseExpr != null) node.falseExpr.accept(this);
    }

    @Override
    public void visit(IfNode node) {
        if (node.condition != null) node.condition.accept(this);
        if (node.thenBranch != null) node.thenBranch.accept(this);
        if (node.elseBranch != null) node.elseBranch.accept(this);
    }

    @Override
    public void visit(TryNode node) {
        if (node.tryBlock != null) node.tryBlock.accept(this);
        if (node.catchBlock != null) node.catchBlock.accept(this);
        if (node.finallyBlock != null) node.finallyBlock.accept(this);
    }

    @Override
    public void visit(DeferNode node) {
        if (node.block != null) node.block.accept(this);
    }

    @Override
    public void visit(LabelNode node) {
        // LabelNode only has a label string, no child nodes to visit
    }

    @Override
    public void visit(CompilerFlagNode node) {
    }

    @Override
    public void visit(FormatNode node) {
    }
}

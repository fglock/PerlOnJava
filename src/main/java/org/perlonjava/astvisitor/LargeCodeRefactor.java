package org.perlonjava.astvisitor;

import org.perlonjava.astnode.*;
import org.perlonjava.astrefactor.LargeBlockRefactorer;
import org.perlonjava.parser.Parser;

import static org.perlonjava.astrefactor.BlockRefactor.MIN_CHUNK_SIZE;

/**
 * AST processor that applies second-pass refactoring to all blocks.
 */
public class LargeCodeRefactor implements Visitor {
    private final Parser parser;

    public LargeCodeRefactor(Parser parser) {
        this.parser = parser;
    }

    @Override
    public void visit(BlockNode node) {
        // Skip if this block was already refactored to prevent infinite recursion
        if (node.getBooleanAnnotation("blockAlreadyRefactored")) {
            // Continue to children
            for (Node element : node.elements) {
                if (element != null) element.accept(this);
            }
            return;
        }

        // Skip if block is already a subroutine or is a special block
        if (node.getBooleanAnnotation("blockIsSubroutine")) {
            for (Node element : node.elements) {
                if (element != null) element.accept(this);
            }
            return;
        }

        // Determine if we need to refactor (same logic as shouldRefactorBlock)
        boolean needsRefactoring = node.elements.size() > MIN_CHUNK_SIZE;
        // Refactoring is enabled (we're in second pass)

        if (needsRefactoring && !LargeBlockRefactorer.isSpecialContext(node)) {
            // Try whole-block refactoring (same as tryWholeBlockRefactoring but at parse time)
            LargeBlockRefactorer.tryWholeBlockRefactoringAtParseTime(node, parser);
        }

        // Continue walking into child blocks
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
            }
        }
    }

    // Traverse the AST
    @Override
    public void visit(OperatorNode node) {
        if (node.operand != null) node.operand.accept(this);
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        if (node.left != null) node.left.accept(this);
        if (node.right != null) node.right.accept(this);
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
    public void visit(For1Node node) {
        if (node.variable != null) node.variable.accept(this);
        if (node.list != null) node.list.accept(this);
        if (node.body != null) node.body.accept(this);
    }

    @Override
    public void visit(For3Node node) {
        if (node.initialization != null) node.initialization.accept(this);
        if (node.condition != null) node.condition.accept(this);
        if (node.increment != null) node.increment.accept(this);
        if (node.body != null) node.body.accept(this);
    }

    @Override
    public void visit(TryNode node) {
        if (node.tryBlock != null) node.tryBlock.accept(this);
        if (node.catchBlock != null) node.catchBlock.accept(this);
        if (node.finallyBlock != null) node.finallyBlock.accept(this);
    }

    @Override
    public void visit(ListNode node) {
        for (Node element : node.elements) {
            if (element != null) element.accept(this);
        }
    }

    @Override
    public void visit(SubroutineNode node) {
        if (node.block != null) node.block.accept(this);
    }

    @Override
    public void visit(HashLiteralNode node) {
        for (Node element : node.elements) {
            if (element != null) element.accept(this);
        }
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        for (Node element : node.elements) {
            if (element != null) element.accept(this);
        }
    }

    // Leaf nodes
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

    @Override
    public void visit(FormatLine node) {
    }
}

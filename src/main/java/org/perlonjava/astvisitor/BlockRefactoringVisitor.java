package org.perlonjava.astvisitor;

import org.perlonjava.astnode.*;
import org.perlonjava.parser.Parser;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.parser.ParserNodeUtils.variableAst;

/**
 * Visitor that refactors large blocks by splitting them into smaller closures.
 * This visitor is applied after parsing is complete, allowing it to see the full AST
 * and make intelligent decisions about how to split blocks while preserving semantics.
 */
public class BlockRefactoringVisitor implements Visitor {
    
    private static final int LARGE_BLOCK_THRESHOLD = 50;
    private static final int MIN_CHUNK_SIZE = 2;
    private final ControlFlowDetectorVisitor controlFlowDetector = new ControlFlowDetectorVisitor();
    private final Parser parser;
    
    public BlockRefactoringVisitor(Parser parser) {
        this.parser = parser;
    }
    
    /**
     * Apply refactoring to the entire AST starting from the root node.
     */
    public Node refactor(Node root) {
        root.accept(this);
        return root;
    }
    
    @Override
    public void visit(BlockNode node) {
        // First, recursively visit all child nodes
        for (int i = 0; i < node.elements.size(); i++) {
            Node element = node.elements.get(i);
            if (element != null) {
                element.accept(this);
            }
        }
        
        // Then try to refactor this block if it's large
        if (node.elements.size() > LARGE_BLOCK_THRESHOLD) {
            refactorBlock(node);
        }
    }
    
    private void refactorBlock(BlockNode node) {
        // Check if block contains control flow
        controlFlowDetector.reset();
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(controlFlowDetector);
            }
        }
        
        if (controlFlowDetector.hasUnsafeControlFlow()) {
            // Block contains control flow - cannot safely wrap in closures
            // Skip refactoring for now
            return;
        }
        
        // Block is safe to refactor - apply chunking
        applyChunking(node);
    }
    
    private void applyChunking(BlockNode node) {
        List<Node> newElements = new ArrayList<>();
        List<Node> currentChunk = new ArrayList<>();
        
        for (Node element : node.elements) {
            if (element == null) {
                continue;
            }
            
            // Check if this element has control flow or is a label
            boolean shouldBreak = false;
            if (element instanceof LabelNode) {
                shouldBreak = true;
            } else {
                controlFlowDetector.reset();
                element.accept(controlFlowDetector);
                if (controlFlowDetector.hasUnsafeControlFlow()) {
                    shouldBreak = true;
                }
            }
            
            if (shouldBreak) {
                // Finalize current chunk
                if (currentChunk.size() >= MIN_CHUNK_SIZE) {
                    newElements.add(createClosure(currentChunk, node.tokenIndex));
                    currentChunk.clear();
                } else if (!currentChunk.isEmpty()) {
                    newElements.addAll(currentChunk);
                    currentChunk.clear();
                }
                newElements.add(element);
            } else {
                currentChunk.add(element);
            }
        }
        
        // Handle remaining chunk
        if (currentChunk.size() >= MIN_CHUNK_SIZE) {
            newElements.add(createClosure(currentChunk, node.tokenIndex));
        } else if (!currentChunk.isEmpty()) {
            newElements.addAll(currentChunk);
        }
        
        // Replace elements if we reduced the count
        if (newElements.size() < node.elements.size()) {
            node.elements.clear();
            node.elements.addAll(newElements);
        }
    }
    
    private Node createClosure(List<Node> elements, int tokenIndex) {
        BlockNode block = new BlockNode(new ArrayList<>(elements), tokenIndex, parser);
        SubroutineNode sub = new SubroutineNode(null, null, null, block, false, tokenIndex);
        ListNode args = new ListNode(new ArrayList<>(List.of(variableAst("@", "_", tokenIndex))), tokenIndex);
        return new BinaryOperatorNode("->", sub, args, tokenIndex);
    }
    
    // Default implementations for other node types - just visit children
    
    @Override
    public void visit(IfNode node) {
        if (node.condition != null) node.condition.accept(this);
        if (node.thenBranch != null) node.thenBranch.accept(this);
        if (node.elseBranch != null) node.elseBranch.accept(this);
    }
    
    @Override
    public void visit(For1Node node) {
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
    
    // Stub implementations for all other node types
    @Override public void visit(NumberNode node) {}
    @Override public void visit(IdentifierNode node) {}
    @Override public void visit(BinaryOperatorNode node) {
        if (node.left != null) node.left.accept(this);
        if (node.right != null) node.right.accept(this);
    }
    @Override public void visit(OperatorNode node) {
        if (node.operand != null) node.operand.accept(this);
    }
    @Override public void visit(ListNode node) {
        for (Node element : node.elements) {
            if (element != null) element.accept(this);
        }
    }
    @Override public void visit(ArrayLiteralNode node) {
        for (Node element : node.elements) {
            if (element != null) element.accept(this);
        }
    }
    @Override public void visit(HashLiteralNode node) {}
    @Override public void visit(SubroutineNode node) {
        if (node.block != null) node.block.accept(this);
    }
    @Override public void visit(TernaryOperatorNode node) {
        if (node.condition != null) node.condition.accept(this);
        if (node.trueExpr != null) node.trueExpr.accept(this);
        if (node.falseExpr != null) node.falseExpr.accept(this);
    }
    @Override public void visit(LabelNode node) {}
    @Override public void visit(StringNode node) {}
    @Override public void visit(CompilerFlagNode node) {}
    @Override public void visit(FormatNode node) {}
}

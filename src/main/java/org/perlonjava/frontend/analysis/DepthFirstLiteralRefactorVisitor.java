package org.perlonjava.frontend.analysis;

import org.perlonjava.astnode.*;
import org.perlonjava.backend.jvm.astrefactor.BlockRefactor;
import org.perlonjava.backend.jvm.astrefactor.LargeNodeRefactorer;

import java.util.List;

/**
 * Visitor that refactors large literals in a depth-first manner.
 * <p>
 * This visitor traverses the AST and refactors large ListNode, HashLiteralNode,
 * and ArrayLiteralNode structures by splitting them into smaller chunks wrapped
 * in closures. The depth-first approach ensures that nested structures are
 * refactored first, which naturally reduces the size of parent structures.
 * <p>
 * Example: For a hash like:
 * <pre>
 * %hash = (
 *   key1 => { nested => { deeply => 'nested' } },
 *   key2 => { another => { structure => 'here' } },
 *   ...
 * )
 * </pre>
 * The inner hashes are refactored first, making the outer hash smaller and
 * potentially avoiding the need to refactor it at all.
 * <p>
 * Control flow (next/last/redo) is safe to wrap in closures since master
 * now supports non-local gotos in subroutines.
 */
public class DepthFirstLiteralRefactorVisitor implements Visitor {

    /**
     * Minimum number of elements before considering refactoring.
     * Avoids refactoring small but complex structures.
     * Set conservatively high to only refactor truly massive structures.
     */
    private static final int MIN_ELEMENTS_FOR_REFACTORING = 500;

    /**
     * Debug flag for controlling debug output.
     */
    private static final boolean DEBUG = false;

    /**
     * Refactor an AST starting from the given node.
     * Traverses depth-first and refactors large literals in-place.
     *
     * @param root the root node to start refactoring from
     */
    public static void refactor(Node root) {
        if (root != null) {
            DepthFirstLiteralRefactorVisitor visitor = new DepthFirstLiteralRefactorVisitor();
            root.accept(visitor);
        }
    }

    @Override
    public void visit(ListNode node) {
        // First, recursively refactor all children (depth-first)
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
            }
        }
        if (node.handle != null) {
            node.handle.accept(this);
        }

        // Then, refactor this node if it's too large
        if (shouldRefactor(node.elements)) {
            if (DEBUG) {
                System.err.println("DEBUG: Refactoring ListNode with " + node.elements.size() + " elements");
                System.err.println("DEBUG: First few elements: " +
                    node.elements.stream().limit(3).map(Node::toString).collect(java.util.stream.Collectors.joining(", ")));
            }
            List<Node> original = node.elements;
            node.elements = LargeNodeRefactorer.forceRefactorElements(node.elements, node.getIndex());
            if (DEBUG) {
                System.err.println("DEBUG: After refactoring: " + node.elements.size() + " elements");
                System.err.println("DEBUG: Refactored structure: " + node.elements.stream().limit(2)
                    .map(n -> n.getClass().getSimpleName()).collect(java.util.stream.Collectors.joining(", ")));
            }
        }
    }

    @Override
    public void visit(HashLiteralNode node) {
        // First, recursively refactor all children (depth-first)
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
            }
        }

        // Then, refactor this node if it's too large
        if (shouldRefactor(node.elements)) {
            node.elements = LargeNodeRefactorer.forceRefactorElements(node.elements, node.getIndex());
        }
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        // First, recursively refactor all children (depth-first)
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
            }
        }

        // Then, refactor this node if it's too large
        if (shouldRefactor(node.elements)) {
            node.elements = LargeNodeRefactorer.forceRefactorElements(node.elements, node.getIndex());
        }
    }

    @Override
    public void visit(BlockNode node) {
        // Recursively visit all elements
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
            }
        }
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        if (node.left != null) {
            node.left.accept(this);
        }
        if (node.right != null) {
            node.right.accept(this);
        }
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        if (node.condition != null) {
            node.condition.accept(this);
        }
        if (node.trueExpr != null) {
            node.trueExpr.accept(this);
        }
        if (node.falseExpr != null) {
            node.falseExpr.accept(this);
        }
    }

    @Override
    public void visit(IfNode node) {
        if (node.condition != null) {
            node.condition.accept(this);
        }
        if (node.thenBranch != null) {
            node.thenBranch.accept(this);
        }
        if (node.elseBranch != null) {
            node.elseBranch.accept(this);
        }
    }

    @Override
    public void visit(For1Node node) {
        if (node.variable != null) {
            node.variable.accept(this);
        }
        if (node.list != null) {
            node.list.accept(this);
        }
        if (node.body != null) {
            node.body.accept(this);
        }
        if (node.continueBlock != null) {
            node.continueBlock.accept(this);
        }
    }

    @Override
    public void visit(For3Node node) {
        if (node.initialization != null) {
            node.initialization.accept(this);
        }
        if (node.condition != null) {
            node.condition.accept(this);
        }
        if (node.increment != null) {
            node.increment.accept(this);
        }
        if (node.body != null) {
            node.body.accept(this);
        }
        if (node.continueBlock != null) {
            node.continueBlock.accept(this);
        }
    }

    @Override
    public void visit(TryNode node) {
        if (node.tryBlock != null) {
            node.tryBlock.accept(this);
        }
        if (node.catchBlock != null) {
            node.catchBlock.accept(this);
        }
        if (node.finallyBlock != null) {
            node.finallyBlock.accept(this);
        }
    }

    @Override
    public void visit(OperatorNode node) {
        if (node.operand != null) {
            node.operand.accept(this);
        }
    }

    @Override
    public void visit(SubroutineNode node) {
        // DO traverse into subroutines - we want to refactor large literals everywhere
        if (node.block != null) {
            node.block.accept(this);
        }
    }

    // Leaf nodes - no traversal needed
    @Override
    public void visit(IdentifierNode node) {
        // No children to traverse
    }

    @Override
    public void visit(NumberNode node) {
        // No children to traverse
    }

    @Override
    public void visit(StringNode node) {
        // No children to traverse
    }

    @Override
    public void visit(LabelNode node) {
        // No children to traverse
    }

    @Override
    public void visit(CompilerFlagNode node) {
        // No children to traverse
    }

    @Override
    public void visit(FormatNode node) {
        // Formats typically don't contain large literals
    }

    @Override
    public void visit(FormatLine node) {
        // Format lines don't contain large literals
    }

    /**
     * Determine if a list of elements should be refactored.
     * Uses the same logic as LargeNodeRefactorer for consistency.
     *
     * @param elements the elements to check
     * @return true if refactoring is needed
     */
    private boolean shouldRefactor(List<Node> elements) {
        if (elements == null || elements.isEmpty()) {
            return false;
        }

        // Only refactor if we have a significant number of elements
        // Avoids refactoring small but complex structures
        if (elements.size() < MIN_ELEMENTS_FOR_REFACTORING) {
            return false;
        }

        // Use BlockRefactor.LARGE_BYTECODE_SIZE for consistency
        long totalSize = 0;
        for (Node element : elements) {
            totalSize += BytecodeSizeEstimator.estimateSnippetSize(element);
            if (totalSize > BlockRefactor.LARGE_BYTECODE_SIZE) {
                return true;
            }
        }
        return false;
    }
}

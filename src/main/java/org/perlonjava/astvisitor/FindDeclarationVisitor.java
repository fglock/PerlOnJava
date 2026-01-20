package org.perlonjava.astvisitor;

import org.perlonjava.astnode.*;

/**
 * A visitor that traverses the AST to find specific operator declarations.
 * Used primarily to locate 'local' and 'my' declarations within code blocks.
 * This visitor is utilized by Local.java for managing dynamic variable scopes
 * and by EmitLogicalOperator for handling variable declarations in logical operations.
 */
public class FindDeclarationVisitor implements Visitor {

    /**
     * Tracks whether the target operator has been found
     */
    private boolean containsLocalOperator = false;
    /**
     * The name of the operator we are searching for (e.g., "local", "my")
     */
    private String operatorName = null;
    /**
     * Stores the found operator node when located
     */
    private OperatorNode operatorNode = null;

    /**
     * Static factory method to find a specific operator within an AST node.
     *
     * @param blockNode    The AST node to search within
     * @param operatorName The name of the operator to find
     * @return The found OperatorNode, or null if not found
     */
    public static OperatorNode findOperator(Node blockNode, String operatorName) {
        if (blockNode == null || operatorName == null) {
            return null;
        }

        // IMPORTANT: This must be iterative, not recursive.
        // Deeply nested closure/refactor ASTs can overflow the JVM stack when traversed via accept()/visit().
        java.util.ArrayDeque<Node> stack = new java.util.ArrayDeque<>();
        stack.push(blockNode);

        while (!stack.isEmpty()) {
            Node node = stack.pop();
            if (node == null) {
                continue;
            }

            if (node instanceof OperatorNode opNode) {
                if (operatorName.equals(opNode.operator)) {
                    return opNode;
                }
                if (opNode.operand != null) {
                    stack.push(opNode.operand);
                }
                continue;
            }

            if (node instanceof BlockNode block) {
                for (int i = block.elements.size() - 1; i >= 0; i--) {
                    stack.push(block.elements.get(i));
                }
                continue;
            }

            if (node instanceof BinaryOperatorNode bin) {
                stack.push(bin.right);
                stack.push(bin.left);
                continue;
            }

            if (node instanceof IfNode ifNode) {
                if (ifNode.elseBranch != null) {
                    stack.push(ifNode.elseBranch);
                }
                stack.push(ifNode.thenBranch);
                stack.push(ifNode.condition);
                continue;
            }

            if (node instanceof TernaryOperatorNode ternary) {
                stack.push(ternary.falseExpr);
                stack.push(ternary.trueExpr);
                stack.push(ternary.condition);
                continue;
            }

            if (node instanceof For1Node for1) {
                if (for1.continueBlock != null) {
                    stack.push(for1.continueBlock);
                }
                stack.push(for1.body);
                stack.push(for1.list);
                stack.push(for1.variable);
                continue;
            }

            if (node instanceof For3Node for3) {
                stack.push(for3.body);
                if (for3.increment != null) {
                    stack.push(for3.increment);
                }
                if (for3.condition != null) {
                    stack.push(for3.condition);
                }
                if (for3.initialization != null) {
                    stack.push(for3.initialization);
                }
                continue;
            }

            if (node instanceof SubroutineNode sub) {
                stack.push(sub.block);
                continue;
            }

            if (node instanceof TryNode tryNode) {
                if (tryNode.finallyBlock != null) {
                    stack.push(tryNode.finallyBlock);
                }
                if (tryNode.catchBlock != null) {
                    stack.push(tryNode.catchBlock);
                }
                if (tryNode.tryBlock != null) {
                    stack.push(tryNode.tryBlock);
                }
                continue;
            }

            if (node instanceof ListNode list) {
                for (int i = list.elements.size() - 1; i >= 0; i--) {
                    stack.push(list.elements.get(i));
                }
                if (list.handle != null) {
                    stack.push(list.handle);
                }
                continue;
            }

            if (node instanceof ArrayLiteralNode arr) {
                for (int i = arr.elements.size() - 1; i >= 0; i--) {
                    stack.push(arr.elements.get(i));
                }
                continue;
            }

            if (node instanceof HashLiteralNode hash) {
                for (int i = hash.elements.size() - 1; i >= 0; i--) {
                    stack.push(hash.elements.get(i));
                }
                continue;
            }

            // Leaf nodes: NumberNode, IdentifierNode, StringNode, LabelNode, CompilerFlagNode, FormatLine, FormatNode
        }

        return null;
    }

    @Override
    public void visit(FormatLine node) {
        // Default implementation - no action needed for format lines
    }

    @Override
    public void visit(FormatNode node) {
        // Default implementation - no action needed for format nodes
    }

    /**
     * Visits a block node and searches through its elements.
     * Stops searching once an operator is found.
     */
    @Override
    public void visit(BlockNode node) {
        if (!containsLocalOperator) {
            for (Node element : node.elements) {
                if (element != null) {
                    element.accept(this);
                    if (containsLocalOperator) {
                        break;
                    }
                }
            }
        }
    }

    /**
     * Visits an operator node and checks if it matches the target operator.
     */
    @Override
    public void visit(OperatorNode node) {
        if (this.operatorName.equals(node.operator)) {
            containsLocalOperator = true;
            operatorNode = node;
            return;
        }

        if (!containsLocalOperator && node.operand != null) {
            node.operand.accept(this);
        }
    }

    @Override
    public void visit(For1Node node) {
        node.variable.accept(this);
        node.list.accept(this);
        node.body.accept(this);
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
        node.body.accept(this);
    }

    // Implement other visit methods for nodes that can contain OperatorNodes
    @Override
    public void visit(NumberNode node) {
        // No action needed
    }

    @Override
    public void visit(IdentifierNode node) {
        // No action needed
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        node.left.accept(this);
        node.right.accept(this);
    }

    @Override
    public void visit(IfNode node) {
        node.condition.accept(this);
        node.thenBranch.accept(this);
        if (node.elseBranch != null) {
            node.elseBranch.accept(this);
        }
    }

    @Override
    public void visit(SubroutineNode node) {
        node.block.accept(this);
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        node.condition.accept(this);
        node.trueExpr.accept(this);
        node.falseExpr.accept(this);
    }

    @Override
    public void visit(StringNode node) {
        // No action needed
    }

    @Override
    public void visit(ListNode node) {
        for (Node element : node.elements) {
            element.accept(this);
        }
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        for (Node element : node.elements) {
            element.accept(this);
        }
    }

    @Override
    public void visit(HashLiteralNode node) {
        for (Node element : node.elements) {
            element.accept(this);
        }
    }

    @Override
    public void visit(TryNode node) {
        // Visit the try block
        if (node.tryBlock != null) {
            node.tryBlock.accept(this);
        }

        // Visit the catch block
        if (node.catchBlock != null) {
            node.catchBlock.accept(this);
        }

        // Visit the finally block, if it exists
        if (node.finallyBlock != null) {
            node.finallyBlock.accept(this);
        }
    }

    @Override
    public void visit(LabelNode node) {
    }

    @Override
    public void visit(CompilerFlagNode node) {
    }
}

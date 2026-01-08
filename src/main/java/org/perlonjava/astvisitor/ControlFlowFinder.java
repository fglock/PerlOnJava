package org.perlonjava.astvisitor;

import org.perlonjava.astnode.*;

/**
 * Simple visitor that finds ANY control flow statement, ignoring loop depth.
 */
public class ControlFlowFinder implements Visitor {
    public boolean foundControlFlow = false;

    private Node[] nodeStack = new Node[256];
    private int[] stateStack = new int[256];
    private int[] indexStack = new int[256];
    private int[] extraStack = new int[256];

    private void ensureCapacity(int top) {
        if (top < nodeStack.length) {
            return;
        }
        int newCap = nodeStack.length * 2;
        while (top >= newCap) {
            newCap *= 2;
        }
        nodeStack = java.util.Arrays.copyOf(nodeStack, newCap);
        stateStack = java.util.Arrays.copyOf(stateStack, newCap);
        indexStack = java.util.Arrays.copyOf(indexStack, newCap);
        extraStack = java.util.Arrays.copyOf(extraStack, newCap);
    }

    /**
     * Iterative (non-recursive) scan for control flow.
     *
     * <p>Used by large-block refactoring to decide chunk boundaries without risking
     * StackOverflowError on huge ASTs.
     */
    public void scan(Node root) {
        foundControlFlow = false;
        if (root == null) {
            return;
        }

        int top = 0;

        ensureCapacity(0);
        nodeStack[0] = root;
        stateStack[0] = 0;
        indexStack[0] = 0;
        extraStack[0] = 0;

        while (top >= 0 && !foundControlFlow) {
            Node node = nodeStack[top];
            int state = stateStack[top];

            if (node == null) {
                top--;
                continue;
            }

            if (node instanceof SubroutineNode) {
                top--;
                continue;
            }
            if (node instanceof LabelNode) {
                top--;
                continue;
            }

            if (node instanceof OperatorNode op) {
                if (state == 0) {
                    if ("last".equals(op.operator) ||
                            "next".equals(op.operator) ||
                            "redo".equals(op.operator) || "goto".equals(op.operator)) {
                        foundControlFlow = true;
                        continue;
                    }
                    stateStack[top] = 1;
                    if (op.operand != null) {
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = op.operand;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                } else {
                    top--;
                }
                continue;
            }

            if (node instanceof BlockNode block) {
                if (state == 0) {
                    stateStack[top] = 1;
                    indexStack[top] = block.elements.size() - 1;
                    continue;
                }
                int idx = indexStack[top];
                while (idx >= 0) {
                    Node child = block.elements.get(idx);
                    idx--;
                    if (child != null) {
                        indexStack[top] = idx;
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = child;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                        break;
                    }
                }
                if (idx < 0) {
                    top--;
                }
                continue;
            }

            if (node instanceof ListNode list) {
                if (state == 0) {
                    stateStack[top] = 1;
                    indexStack[top] = list.elements.size() - 1;
                    extraStack[top] = 0; // handlePushed: 0=no, 1=yes
                    continue;
                }

                int idx = indexStack[top];
                while (idx >= 0) {
                    Node child = list.elements.get(idx);
                    idx--;
                    if (child != null) {
                        indexStack[top] = idx;
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = child;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                        break;
                    }
                }

                if (idx < 0) {
                    if (extraStack[top] == 0) {
                        extraStack[top] = 1;
                        if (list.handle != null) {
                            top++;
                            ensureCapacity(top);
                            nodeStack[top] = list.handle;
                            stateStack[top] = 0;
                            indexStack[top] = 0;
                            extraStack[top] = 0;
                        }
                    } else {
                        top--;
                    }
                } else {
                    indexStack[top] = idx;
                }
                continue;
            }

            if (node instanceof BinaryOperatorNode bin) {
                if (state == 0) {
                    stateStack[top] = 1;
                    if (bin.right != null) {
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = bin.right;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 1) {
                    stateStack[top] = 2;
                    if (bin.left != null) {
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = bin.left;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                top--;
                continue;
            }

            if (node instanceof TernaryOperatorNode tern) {
                if (state == 0) {
                    stateStack[top] = 1;
                    if (tern.falseExpr != null) {
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = tern.falseExpr;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 1) {
                    stateStack[top] = 2;
                    if (tern.trueExpr != null) {
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = tern.trueExpr;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 2) {
                    stateStack[top] = 3;
                    if (tern.condition != null) {
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = tern.condition;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                top--;
                continue;
            }

            if (node instanceof IfNode ifNode) {
                if (state == 0) {
                    stateStack[top] = 1;
                    if (ifNode.elseBranch != null) {
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = ifNode.elseBranch;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 1) {
                    stateStack[top] = 2;
                    if (ifNode.thenBranch != null) {
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = ifNode.thenBranch;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 2) {
                    stateStack[top] = 3;
                    if (ifNode.condition != null) {
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = ifNode.condition;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                top--;
                continue;
            }

            if (node instanceof For1Node for1) {
                if (state == 0) {
                    stateStack[top] = 1;
                    if (for1.body != null) {
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = for1.body;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 1) {
                    stateStack[top] = 2;
                    if (for1.list != null) {
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = for1.list;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 2) {
                    stateStack[top] = 3;
                    if (for1.variable != null) {
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = for1.variable;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                top--;
                continue;
            }

            if (node instanceof For3Node for3) {
                if (state == 0) {
                    stateStack[top] = 1;
                    if (for3.body != null) {
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = for3.body;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 1) {
                    stateStack[top] = 2;
                    if (for3.increment != null) {
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = for3.increment;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 2) {
                    stateStack[top] = 3;
                    if (for3.condition != null) {
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = for3.condition;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 3) {
                    stateStack[top] = 4;
                    if (for3.initialization != null) {
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = for3.initialization;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                top--;
                continue;
            }

            if (node instanceof TryNode tryNode) {
                if (state == 0) {
                    stateStack[top] = 1;
                    if (tryNode.finallyBlock != null) {
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = tryNode.finallyBlock;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 1) {
                    stateStack[top] = 2;
                    if (tryNode.catchBlock != null) {
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = tryNode.catchBlock;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 2) {
                    stateStack[top] = 3;
                    if (tryNode.tryBlock != null) {
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = tryNode.tryBlock;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                top--;
                continue;
            }

            if (node instanceof HashLiteralNode hash) {
                if (state == 0) {
                    stateStack[top] = 1;
                    indexStack[top] = hash.elements.size() - 1;
                    continue;
                }
                int idx = indexStack[top];
                while (idx >= 0) {
                    Node child = hash.elements.get(idx);
                    idx--;
                    if (child != null) {
                        indexStack[top] = idx;
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = child;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                        break;
                    }
                }
                if (idx < 0) {
                    top--;
                }
                continue;
            }

            if (node instanceof ArrayLiteralNode array) {
                if (state == 0) {
                    stateStack[top] = 1;
                    indexStack[top] = array.elements.size() - 1;
                    continue;
                }
                int idx = indexStack[top];
                while (idx >= 0) {
                    Node child = array.elements.get(idx);
                    idx--;
                    if (child != null) {
                        indexStack[top] = idx;
                        top++;
                        ensureCapacity(top);
                        nodeStack[top] = child;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                        break;
                    }
                }
                if (idx < 0) {
                    top--;
                }
                continue;
            }

            // Leaf nodes
            top--;
        }
    }

    @Override
    public void visit(OperatorNode node) {
        if (foundControlFlow) return;
        if ("last".equals(node.operator) ||
                "next".equals(node.operator) ||
                "redo".equals(node.operator) || "goto".equals(node.operator)) {
            foundControlFlow = true;
            return;
        }
        if (node.operand != null) {
            node.operand.accept(this);
        }
    }

    @Override
    public void visit(BlockNode node) {
        if (foundControlFlow) return;
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
                if (foundControlFlow) return;
            }
        }
    }

    @Override
    public void visit(ListNode node) {
        if (foundControlFlow) return;
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
                if (foundControlFlow) return;
            }
        }
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        if (foundControlFlow) return;
        if (node.left != null) node.left.accept(this);
        if (!foundControlFlow && node.right != null) node.right.accept(this);
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        if (foundControlFlow) return;
        if (node.condition != null) node.condition.accept(this);
        if (!foundControlFlow && node.trueExpr != null) node.trueExpr.accept(this);
        if (!foundControlFlow && node.falseExpr != null) node.falseExpr.accept(this);
    }

    @Override
    public void visit(IfNode node) {
        if (foundControlFlow) return;
        if (node.condition != null) node.condition.accept(this);
        if (!foundControlFlow && node.thenBranch != null) node.thenBranch.accept(this);
        if (!foundControlFlow && node.elseBranch != null) node.elseBranch.accept(this);
    }

    @Override
    public void visit(For1Node node) {
        // Traverse into loops to find ANY control flow for chunking purposes
        if (foundControlFlow) return;
        if (node.variable != null) node.variable.accept(this);
        if (!foundControlFlow && node.list != null) node.list.accept(this);
        if (!foundControlFlow && node.body != null) node.body.accept(this);
    }

    @Override
    public void visit(For3Node node) {
        // Traverse into loops to find ANY control flow for chunking purposes
        if (foundControlFlow) return;
        if (node.initialization != null) node.initialization.accept(this);
        if (!foundControlFlow && node.condition != null) node.condition.accept(this);
        if (!foundControlFlow && node.increment != null) node.increment.accept(this);
        if (!foundControlFlow && node.body != null) node.body.accept(this);
    }

    @Override
    public void visit(TryNode node) {
        if (foundControlFlow) return;
        if (node.tryBlock != null) node.tryBlock.accept(this);
        if (!foundControlFlow && node.catchBlock != null) node.catchBlock.accept(this);
        if (!foundControlFlow && node.finallyBlock != null) node.finallyBlock.accept(this);
    }

    @Override
    public void visit(HashLiteralNode node) {
        if (foundControlFlow) return;
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
                if (foundControlFlow) return;
            }
        }
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        if (foundControlFlow) return;
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
                if (foundControlFlow) return;
            }
        }
    }

    @Override
    public void visit(SubroutineNode node) {
        // Do not traverse into subroutines - control flow inside is scoped to that subroutine
    }

    // Default implementations for leaf nodes
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

package org.perlonjava.frontend.analysis;

import org.perlonjava.astnode.*;

import java.util.Set;

/**
 * Visitor that detects control flow statements (next, last, redo, goto)
 * that could potentially jump outside of a refactored block.
 */
public class ControlFlowDetectorVisitor implements Visitor {
    private boolean hasUnsafeControlFlow = false;
    private int loopDepth = 0;
    private Set<String> allowedGotoLabels = null;
    private static final boolean DEBUG = "1".equals(System.getenv("JPERL_TRACE_CONTROLFLOW"));

    /**
     * Check if unsafe control flow was detected during traversal.
     *
     * @return true if unsafe control flow statements were found
     */
    public boolean hasUnsafeControlFlow() {
        return hasUnsafeControlFlow;
    }

    /**
     * Reset the detector for reuse.
     */
    public void reset() {
        hasUnsafeControlFlow = false;
        loopDepth = 0;
        allowedGotoLabels = null;
    }

    /**
     * Iterative (non-recursive) scan for unsafe control flow.
     *
     * <p>This avoids StackOverflowError when scanning huge ASTs during parse-time refactoring.
     */
    public void scan(Node root) {
        hasUnsafeControlFlow = false;
        loopDepth = 0;
        if (root == null) {
            return;
        }

        // Object-free iterative DFS: avoid allocating a frame object per visited node.
        Node[] nodeStack = new Node[256];
        int[] loopDepthStack = new int[256];
        int[] stateStack = new int[256];
        int[] indexStack = new int[256];
        int[] extraStack = new int[256];
        int top = 0;

        nodeStack[0] = root;
        loopDepthStack[0] = 0;
        stateStack[0] = 0;
        indexStack[0] = 0;
        extraStack[0] = 0;

        while (top >= 0 && !hasUnsafeControlFlow) {
            Node node = nodeStack[top];
            int currentLoopDepth = loopDepthStack[top];
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
                    String oper = op.operator;

                    if ("goto".equals(oper)) {
                        if (allowedGotoLabels != null && op.operand instanceof ListNode labelNode && !labelNode.elements.isEmpty()) {
                            Node arg = labelNode.elements.getFirst();
                            if (arg instanceof IdentifierNode identifierNode && allowedGotoLabels.contains(identifierNode.name)) {
                                if (DEBUG) System.err.println("ControlFlowDetector(scan): goto " + identifierNode.name + " allowed (in allowedGotoLabels)");
                            } else {
                                if (DEBUG) System.err.println("ControlFlowDetector(scan): UNSAFE goto at tokenIndex=" + op.tokenIndex);
                                hasUnsafeControlFlow = true;
                                continue;
                            }
                        } else {
                            if (DEBUG) System.err.println("ControlFlowDetector(scan): UNSAFE goto at tokenIndex=" + op.tokenIndex);
                            hasUnsafeControlFlow = true;
                            continue;
                        }
                    }

                    if ("next".equals(oper) || "last".equals(oper) || "redo".equals(oper)) {
                        boolean isLabeled = false;
                        String label = null;
                        if (op.operand instanceof ListNode labelNode && !labelNode.elements.isEmpty()) {
                            isLabeled = true;
                            if (labelNode.elements.getFirst() instanceof IdentifierNode id) {
                                label = id.name;
                            }
                        }

                        if (isLabeled) {
                            if (DEBUG) System.err.println("ControlFlowDetector(scan): UNSAFE " + oper + " (labeled) at tokenIndex=" + op.tokenIndex + " label=" + label);
                            hasUnsafeControlFlow = true;
                            continue;
                        } else if (currentLoopDepth == 0) {
                            if (DEBUG) System.err.println("ControlFlowDetector(scan): UNSAFE " + oper + " at tokenIndex=" + op.tokenIndex + " loopDepth=" + currentLoopDepth + " isLabeled=" + isLabeled + " label=" + label);
                            hasUnsafeControlFlow = true;
                            continue;
                        }
                    }

                    stateStack[top] = 1;
                    if (op.operand != null) {
                        // push operand
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = op.operand;
                        loopDepthStack[top] = currentLoopDepth;
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
                    extraStack[top] = currentLoopDepth + (block.isLoop ? 1 : 0);
                    indexStack[top] = block.elements.size() - 1;
                    continue;
                }
                int idx = indexStack[top];
                while (idx >= 0) {
                    Node child = block.elements.get(idx);
                    idx--;
                    if (child != null) {
                        indexStack[top] = idx;
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = child;
                        loopDepthStack[top] = extraStack[top - 1];
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
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = child;
                        loopDepthStack[top] = currentLoopDepth;
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
                            if (top + 1 == nodeStack.length) {
                                int newCap = nodeStack.length * 2;
                                Node[] newNodeStack = new Node[newCap];
                                int[] newLoopDepthStack = new int[newCap];
                                int[] newStateStack = new int[newCap];
                                int[] newIndexStack = new int[newCap];
                                int[] newExtraStack = new int[newCap];
                                System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                                System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                                System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                                System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                                System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                                nodeStack = newNodeStack;
                                loopDepthStack = newLoopDepthStack;
                                stateStack = newStateStack;
                                indexStack = newIndexStack;
                                extraStack = newExtraStack;
                            }
                            top++;
                            nodeStack[top] = list.handle;
                            loopDepthStack[top] = currentLoopDepth;
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
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = bin.right;
                        loopDepthStack[top] = currentLoopDepth;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 1) {
                    stateStack[top] = 2;
                    if (bin.left != null) {
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = bin.left;
                        loopDepthStack[top] = currentLoopDepth;
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
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = tern.falseExpr;
                        loopDepthStack[top] = currentLoopDepth;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 1) {
                    stateStack[top] = 2;
                    if (tern.trueExpr != null) {
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = tern.trueExpr;
                        loopDepthStack[top] = currentLoopDepth;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 2) {
                    stateStack[top] = 3;
                    if (tern.condition != null) {
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = tern.condition;
                        loopDepthStack[top] = currentLoopDepth;
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
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = ifNode.elseBranch;
                        loopDepthStack[top] = currentLoopDepth;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 1) {
                    stateStack[top] = 2;
                    if (ifNode.thenBranch != null) {
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = ifNode.thenBranch;
                        loopDepthStack[top] = currentLoopDepth;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 2) {
                    stateStack[top] = 3;
                    if (ifNode.condition != null) {
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = ifNode.condition;
                        loopDepthStack[top] = currentLoopDepth;
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
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = for1.body;
                        loopDepthStack[top] = currentLoopDepth + 1;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 1) {
                    stateStack[top] = 2;
                    if (for1.list != null) {
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = for1.list;
                        loopDepthStack[top] = currentLoopDepth;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 2) {
                    stateStack[top] = 3;
                    if (for1.variable != null) {
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = for1.variable;
                        loopDepthStack[top] = currentLoopDepth;
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
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = for3.body;
                        loopDepthStack[top] = currentLoopDepth + 1;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 1) {
                    stateStack[top] = 2;
                    if (for3.increment != null) {
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = for3.increment;
                        loopDepthStack[top] = currentLoopDepth;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 2) {
                    stateStack[top] = 3;
                    if (for3.condition != null) {
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = for3.condition;
                        loopDepthStack[top] = currentLoopDepth;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 3) {
                    stateStack[top] = 4;
                    if (for3.initialization != null) {
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = for3.initialization;
                        loopDepthStack[top] = currentLoopDepth;
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
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = tryNode.finallyBlock;
                        loopDepthStack[top] = currentLoopDepth;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 1) {
                    stateStack[top] = 2;
                    if (tryNode.catchBlock != null) {
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = tryNode.catchBlock;
                        loopDepthStack[top] = currentLoopDepth;
                        stateStack[top] = 0;
                        indexStack[top] = 0;
                        extraStack[top] = 0;
                    }
                    continue;
                }
                if (state == 2) {
                    stateStack[top] = 3;
                    if (tryNode.tryBlock != null) {
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = tryNode.tryBlock;
                        loopDepthStack[top] = currentLoopDepth;
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
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = child;
                        loopDepthStack[top] = currentLoopDepth;
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
                        if (top + 1 == nodeStack.length) {
                            int newCap = nodeStack.length * 2;
                            Node[] newNodeStack = new Node[newCap];
                            int[] newLoopDepthStack = new int[newCap];
                            int[] newStateStack = new int[newCap];
                            int[] newIndexStack = new int[newCap];
                            int[] newExtraStack = new int[newCap];
                            System.arraycopy(nodeStack, 0, newNodeStack, 0, nodeStack.length);
                            System.arraycopy(loopDepthStack, 0, newLoopDepthStack, 0, loopDepthStack.length);
                            System.arraycopy(stateStack, 0, newStateStack, 0, stateStack.length);
                            System.arraycopy(indexStack, 0, newIndexStack, 0, indexStack.length);
                            System.arraycopy(extraStack, 0, newExtraStack, 0, extraStack.length);
                            nodeStack = newNodeStack;
                            loopDepthStack = newLoopDepthStack;
                            stateStack = newStateStack;
                            indexStack = newIndexStack;
                            extraStack = newExtraStack;
                        }
                        top++;
                        nodeStack[top] = child;
                        loopDepthStack[top] = currentLoopDepth;
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

            top--;
        }
    }

    @Override
    public void visit(OperatorNode node) {
        // Check for control flow operators
        if ("goto".equals(node.operator)) {
            if (allowedGotoLabels != null && node.operand instanceof ListNode labelNode && !labelNode.elements.isEmpty()) {
                Node arg = labelNode.elements.getFirst();
                if (arg instanceof IdentifierNode identifierNode && allowedGotoLabels.contains(identifierNode.name)) {
                    if (DEBUG) System.err.println("ControlFlowDetector: goto " + identifierNode.name + " allowed (in allowedGotoLabels)");
                    return;
                }
            }
            if (DEBUG) System.err.println("ControlFlowDetector: UNSAFE goto at tokenIndex=" + node.tokenIndex);
            hasUnsafeControlFlow = true;
            return;
        }
        if ("next".equals(node.operator) || "last".equals(node.operator) || "redo".equals(node.operator)) {
            boolean isLabeled = false;
            String label = null;
            if (node.operand instanceof ListNode labelNode && !labelNode.elements.isEmpty()) {
                isLabeled = true;
                if (labelNode.elements.getFirst() instanceof IdentifierNode id) {
                    label = id.name;
                }
            }
            if ("next".equals(node.operator) && isLabeled) {
                if (DEBUG) System.err.println("ControlFlowDetector: safe labeled next at tokenIndex=" + node.tokenIndex + " label=" + label);
            } else
            if (loopDepth == 0 || isLabeled) {
                if (DEBUG) System.err.println("ControlFlowDetector: UNSAFE " + node.operator + " at tokenIndex=" + node.tokenIndex + " loopDepth=" + loopDepth + " isLabeled=" + isLabeled + " label=" + label);
                hasUnsafeControlFlow = true;
                return;
            }
            if (DEBUG) System.err.println("ControlFlowDetector: safe " + node.operator + " at tokenIndex=" + node.tokenIndex + " loopDepth=" + loopDepth);
        }
        if (hasUnsafeControlFlow) {
            return;
        }
        // Continue traversing
        if (node.operand != null) {
            node.operand.accept(this);
        }
    }

    @Override
    public void visit(BlockNode node) {
        boolean isLoop = node.isLoop;
        if (isLoop) {
            loopDepth++;
        }
        try {
            for (Node element : node.elements) {
                if (element != null) {
                    element.accept(this);
                    if (hasUnsafeControlFlow) {
                        return; // Early exit once found
                    }
                }
            }
        } finally {
            if (isLoop) {
                loopDepth--;
            }
        }
    }

    @Override
    public void visit(ListNode node) {
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
                if (hasUnsafeControlFlow) {
                    return; // Early exit once found
                }
            }
        }
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        if (node.left != null) {
            node.left.accept(this);
        }
        if (!hasUnsafeControlFlow && node.right != null) {
            node.right.accept(this);
        }
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        if (node.condition != null) {
            node.condition.accept(this);
        }
        if (!hasUnsafeControlFlow && node.trueExpr != null) {
            node.trueExpr.accept(this);
        }
        if (!hasUnsafeControlFlow && node.falseExpr != null) {
            node.falseExpr.accept(this);
        }
    }

    @Override
    public void visit(IfNode node) {
        if (node.condition != null) {
            node.condition.accept(this);
        }
        if (!hasUnsafeControlFlow && node.thenBranch != null) {
            node.thenBranch.accept(this);
        }
        if (!hasUnsafeControlFlow && node.elseBranch != null) {
            node.elseBranch.accept(this);
        }
    }

    // For loops can contain control flow
    @Override
    public void visit(For1Node node) {
        if (node.variable != null) {
            node.variable.accept(this);
        }
        if (!hasUnsafeControlFlow && node.list != null) {
            node.list.accept(this);
        }
        if (!hasUnsafeControlFlow && node.body != null) {
            // Always increment loopDepth for for loops, even if labeled
            // This allows unlabeled last/next/redo inside labeled loops to be safe
            loopDepth++;
            try {
                node.body.accept(this);
            } finally {
                loopDepth--;
            }
        }
    }

    @Override
    public void visit(For3Node node) {
        if (node.initialization != null) {
            node.initialization.accept(this);
        }
        if (!hasUnsafeControlFlow && node.condition != null) {
            node.condition.accept(this);
        }
        if (!hasUnsafeControlFlow && node.increment != null) {
            node.increment.accept(this);
        }
        if (!hasUnsafeControlFlow && node.body != null) {
            // Always increment loopDepth for for loops, even if labeled
            // This allows unlabeled last/next/redo inside labeled loops to be safe
            loopDepth++;
            try {
                node.body.accept(this);
            } finally {
                loopDepth--;
            }
        }
    }

    @Override
    public void visit(TryNode node) {
        if (node.tryBlock != null) {
            node.tryBlock.accept(this);
        }
        if (!hasUnsafeControlFlow && node.catchBlock != null) {
            node.catchBlock.accept(this);
        }
        if (!hasUnsafeControlFlow && node.finallyBlock != null) {
            node.finallyBlock.accept(this);
        }
    }

    // Simple implementations for other node types
    @Override
    public void visit(IdentifierNode node) {
        // No control flow in identifiers
    }

    @Override
    public void visit(NumberNode node) {
        // No control flow in numbers
    }

    @Override
    public void visit(StringNode node) {
        // No control flow in strings
    }

    @Override
    public void visit(HashLiteralNode node) {
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
                if (hasUnsafeControlFlow) return;
            }
        }
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
                if (hasUnsafeControlFlow) return;
            }
        }
    }

    @Override
    public void visit(SubroutineNode node) {
        // DO NOT traverse into subroutines!
        // Control flow statements (last/next/redo) inside a subroutine are scoped to that subroutine
        // and won't be affected by refactoring the outer block.
        // Traversing into subroutines causes false positives where we think a block has unsafe
        // control flow when it actually doesn't (the control flow is inside a nested subroutine).
    }

    @Override
    public void visit(LabelNode node) {
        // Labels themselves don't have control flow
        // The labeled statement is handled separately in the AST
    }

    @Override
    public void visit(CompilerFlagNode node) {
        // Compiler flags don't have control flow
    }

    @Override
    public void visit(FormatNode node) {
        // Formats don't have control flow statements
    }

    @Override
    public void visit(FormatLine node) {
        // Format lines don't have control flow statements
    }
}

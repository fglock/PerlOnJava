package org.perlonjava.backend.bytecode;

import org.perlonjava.frontend.analysis.Visitor;
import org.perlonjava.frontend.astnode.*;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Deque;
import java.util.Set;

/**
 * AST visitor that collects all variable references.
 * Used by BytecodeCompiler to detect closure variables.
 *
 * <p>This visitor traverses the entire AST and records every variable reference.
 * Variables are represented as OperatorNode with sigil operators ($, @, %, &)
 * wrapping an IdentifierNode.</p>
 *
 * <p>Example: $x is represented as OperatorNode("$", IdentifierNode("x"))</p>
 */
public class VariableCollectorVisitor implements Visitor {
    private final Set<String> variables;
    private boolean hasEvalString = false;
    private final Deque<Set<String>> localScopes = new ArrayDeque<>();

    /**
     * Create a new VariableCollectorVisitor.
     *
     * @param variables Set to populate with variable names (will be modified)
     */
    public VariableCollectorVisitor(Set<String> variables) {
        this.variables = variables;
        this.localScopes.push(new HashSet<>());
    }

    /**
     * Returns true if the traversed AST contains an eval STRING (not eval BLOCK).
     * When eval STRING is present, any variable could be referenced at runtime,
     * so closure variable filtering should be disabled.
     */
    public boolean hasEvalString() {
        return hasEvalString;
    }

    private boolean isDeclarationOperator(String op) {
        return op.equals("my") || op.equals("state") || op.equals("our");
    }

    private boolean isVariableOperator(String op) {
        return op.equals("$") || op.equals("@") || op.equals("%") || op.equals("&");
    }

    private boolean isDeclaredLocal(String varName) {
        for (Set<String> scope : localScopes) {
            if (scope.contains(varName)) return true;
        }
        return false;
    }

    private void declare(String varName) {
        localScopes.peek().add(varName);
    }

    private void declareFrom(Node node) {
        if (node == null) return;
        if (node instanceof OperatorNode opNode) {
            if (isDeclarationOperator(opNode.operator)) {
                declareFrom(opNode.operand);
            } else if (isVariableOperator(opNode.operator)
                    && opNode.operand instanceof IdentifierNode idNode) {
                declare(opNode.operator + idNode.name);
            } else {
                declareFrom(opNode.operand);
            }
        } else if (node instanceof ListNode listNode && listNode.elements != null) {
            for (Node element : listNode.elements) {
                declareFrom(element);
            }
        }
    }

    private boolean containsDeclaration(Node node) {
        if (node == null) return false;
        if (node instanceof OperatorNode opNode) {
            return isDeclarationOperator(opNode.operator) || containsDeclaration(opNode.operand);
        }
        if (node instanceof ListNode listNode && listNode.elements != null) {
            for (Node element : listNode.elements) {
                if (containsDeclaration(element)) return true;
            }
        }
        return false;
    }

    private void visitAssignmentTarget(Node node) {
        if (node == null) return;
        if (node instanceof OperatorNode opNode && isDeclarationOperator(opNode.operator)) {
            declareFrom(opNode.operand);
            return;
        }
        if (node instanceof ListNode listNode && listNode.elements != null) {
            for (Node element : listNode.elements) {
                visitAssignmentTarget(element);
            }
            return;
        }
        node.accept(this);
    }

    @Override
    public void visit(IdentifierNode node) {
        // Leaf node - nothing to traverse
    }

    @Override
    public void visit(OperatorNode node) {
        String op = node.operator;

        // Detect eval STRING (eval BLOCK is represented as a SubroutineNode, not here).
        // When eval STRING is present, any variable could be referenced dynamically
        // at runtime, so we must capture all visible variables.
        if (op.equals("eval") || op.equals("evalbytes")) {
            hasEvalString = true;
        }

        // Check if this is a variable reference (sigil + identifier)
        if (isDeclarationOperator(op)) {
            declareFrom(node.operand);
            return;
        }

        if (isVariableOperator(op) && node.operand instanceof IdentifierNode idNode) {
            // This is a variable reference
            String varName = op + idNode.name;
            if (!isDeclaredLocal(varName)) {
                variables.add(varName);
            }
        }

        // $#arr references @arr (array last index)
        if (op.equals("$#") && node.operand instanceof IdentifierNode idNode) {
            String varName = "@" + idNode.name;
            if (!isDeclaredLocal(varName)) {
                variables.add(varName);
            }
        }

        // Visit operand if it exists
        if (node.operand != null) {
            node.operand.accept(this);
        }
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        if ("=".equals(node.operator) && containsDeclaration(node.left)) {
            if (node.right != null) {
                node.right.accept(this);
            }
            visitAssignmentTarget(node.left);
            return;
        }

        // $a{key}, @a{keys}, %a{keys} all access hash %a
        if ("{".equals(node.operator) && node.left instanceof OperatorNode leftOp
                && ("$".equals(leftOp.operator) || "@".equals(leftOp.operator) || "%".equals(leftOp.operator))
                && leftOp.operand instanceof IdentifierNode idNode) {
            String varName = "%" + idNode.name;
            if (!isDeclaredLocal(varName)) {
                variables.add(varName);
            }
        }
        // $a[idx], @a[indices], %a[indices] all access array @a
        if ("[".equals(node.operator) && node.left instanceof OperatorNode leftOp
                && ("$".equals(leftOp.operator) || "@".equals(leftOp.operator) || "%".equals(leftOp.operator))
                && leftOp.operand instanceof IdentifierNode idNode) {
            String varName = "@" + idNode.name;
            if (!isDeclaredLocal(varName)) {
                variables.add(varName);
            }
        }
        if (node.left != null) {
            node.left.accept(this);
        }
        if (node.right != null) {
            node.right.accept(this);
        }
    }

    @Override
    public void visit(BlockNode node) {
        localScopes.push(new HashSet<>());
        if (node.elements != null) {
            for (Node element : node.elements) {
                if (element != null) {
                    element.accept(this);
                }
            }
        }
        localScopes.pop();
    }

    @Override
    public void visit(ListNode node) {
        if (node.handle != null) {
            node.handle.accept(this);
        }
        if (node.elements != null) {
            for (Node element : node.elements) {
                if (element != null) {
                    element.accept(this);
                }
            }
        }
    }

    @Override
    public void visit(HashLiteralNode node) {
        if (node.elements != null) {
            for (Node element : node.elements) {
                if (element != null) {
                    element.accept(this);
                }
            }
        }
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        if (node.elements != null) {
            for (Node element : node.elements) {
                if (element != null) {
                    element.accept(this);
                }
            }
        }
    }

    @Override
    public void visit(NumberNode node) {
        // Leaf node - nothing to traverse
    }

    @Override
    public void visit(StringNode node) {
        // Leaf node - nothing to traverse
    }

    @Override
    public void visit(For1Node node) {
        localScopes.push(new HashSet<>());
        if (node.list != null) {
            node.list.accept(this);
        }
        if (node.variable != null) {
            visitAssignmentTarget(node.variable);
        }
        if (node.body != null) {
            node.body.accept(this);
        }
        if (node.continueBlock != null) {
            node.continueBlock.accept(this);
        }
        localScopes.pop();
    }

    @Override
    public void visit(For3Node node) {
        localScopes.push(new HashSet<>());
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
        // continueBlock holds variables referenced by `while {} continue { ... }`.
        // Forgetting this caused the selective-capture optimisation in
        // SubroutineParser to drop those lexicals from the closure, which
        // tripped HTML/Element.pm's look_down at runtime with a
        // "Global symbol $nillio requires explicit package name" error.
        if (node.continueBlock != null) {
            node.continueBlock.accept(this);
        }
        localScopes.pop();
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
    public void visit(SubroutineNode node) {
        if (node.block != null) {
            node.block.accept(this);
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
    public void visit(DeferNode node) {
        if (node.block != null) {
            node.block.accept(this);
        }
    }

    @Override
    public void visit(LabelNode node) {
        // LabelNode is just a label marker with no children
    }

    @Override
    public void visit(CompilerFlagNode node) {
        // Leaf node - nothing to traverse
    }

    @Override
    public void visit(FormatNode node) {
        // Don't traverse format contents
    }

    @Override
    public void visit(FormatLine node) {
        // Don't traverse format line contents
    }
}

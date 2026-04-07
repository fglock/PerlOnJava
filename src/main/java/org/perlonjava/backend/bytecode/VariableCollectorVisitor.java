package org.perlonjava.backend.bytecode;

import org.perlonjava.frontend.analysis.Visitor;
import org.perlonjava.frontend.astnode.*;

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

    /**
     * Create a new VariableCollectorVisitor.
     *
     * @param variables Set to populate with variable names (will be modified)
     */
    public VariableCollectorVisitor(Set<String> variables) {
        this.variables = variables;
    }

    /**
     * Returns true if the traversed AST contains an eval STRING (not eval BLOCK).
     * When eval STRING is present, any variable could be referenced at runtime,
     * so closure variable filtering should be disabled.
     */
    public boolean hasEvalString() {
        return hasEvalString;
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
        if ((op.equals("$") || op.equals("@") || op.equals("%") || op.equals("&"))
                && node.operand instanceof IdentifierNode idNode) {
            // This is a variable reference
            String varName = op + idNode.name;
            variables.add(varName);
        }

        // $#arr references @arr (array last index)
        if (op.equals("$#") && node.operand instanceof IdentifierNode idNode) {
            variables.add("@" + idNode.name);
        }

        // Visit operand if it exists
        if (node.operand != null) {
            node.operand.accept(this);
        }
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        if ("{".equals(node.operator) && node.left instanceof OperatorNode leftOp
                && "$".equals(leftOp.operator) && leftOp.operand instanceof IdentifierNode idNode) {
            variables.add("%" + idNode.name);
        }
        if ("[".equals(node.operator) && node.left instanceof OperatorNode leftOp
                && "$".equals(leftOp.operator) && leftOp.operand instanceof IdentifierNode idNode) {
            variables.add("@" + idNode.name);
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
        if (node.elements != null) {
            for (Node element : node.elements) {
                if (element != null) {
                    element.accept(this);
                }
            }
        }
    }

    @Override
    public void visit(ListNode node) {
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

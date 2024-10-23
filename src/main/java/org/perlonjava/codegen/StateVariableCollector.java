package org.perlonjava.codegen;

import org.perlonjava.astnode.*;
import java.util.HashSet;
import java.util.Set;

public class StateVariableCollector implements Visitor {
    private final Set<String> stateVariables = new HashSet<>();

    public Set<String> getStateVariables() {
        return stateVariables;
    }

    @Override
    public void visit(OperatorNode node) {
        if ("state".equals(node.operator) && node.operand instanceof OperatorNode sigilNode) {
            String sigil = sigilNode.operator;
            if ("$@%".contains(sigil) && sigilNode.operand instanceof IdentifierNode) {
                String name = ((IdentifierNode) sigilNode.operand).name;
                stateVariables.add(sigil + name);
            }
        }
        // Continue visiting the operand
        node.operand.accept(this);
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
        node.initialization.accept(this);
        node.condition.accept(this);
        node.increment.accept(this);
        node.body.accept(this);
        if (node.continueBlock != null) {
            node.continueBlock.accept(this);
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
        // Assuming HashLiteralNode has a similar structure to ArrayLiteralNode
        for (Node element : node.elements) {
            element.accept(this);
        }
    }

    @Override
    public void visit(ListNode node) {
        for (Node element : node.elements) {
            element.accept(this);
        }
    }

    @Override
    public void visit(NumberNode node) {
        // No action needed for leaf nodes
    }

    @Override
    public void visit(IdentifierNode node) {
        // No action needed for leaf nodes
    }

    @Override
    public void visit(SubroutineNode node) {
        // Assuming SubroutineNode has a body or similar structure
        node.block.accept(this);
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        node.trueExpr.accept(this);
        node.falseExpr.accept(this);
    }

    @Override
    public void visit(StringNode node) {
        // No action needed for leaf nodes
    }

    @Override
    public void visit(BlockNode node) {
        for (Node statement : node.elements) {
            statement.accept(this);
        }
    }
}

package org.perlonjava.codegen;

import org.perlonjava.astnode.*;
import org.perlonjava.runtime.RuntimeList;

public class ExtractValueVisitor implements Visitor {
    private final RuntimeList values = new RuntimeList();

    public static RuntimeList getValues(Node node) {
        ExtractValueVisitor visitor = new ExtractValueVisitor();
        node.accept(visitor);
        return visitor.values;
    }

    @Override
    public void visit(NumberNode node) {
        values.add(node.value);
    }

    @Override
    public void visit(StringNode node) {
        values.add(node.value);
    }

    @Override
    public void visit(IdentifierNode node) {
        // not implemented
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        node.left.accept(this);
        node.right.accept(this);
    }

    @Override
    public void visit(OperatorNode node) {
        if (node.operand != null) {
            node.operand.accept(this);
        }
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        node.condition.accept(this);
        node.trueExpr.accept(this);
        node.falseExpr.accept(this);
    }

    @Override
    public void visit(BlockNode node) {
        for (Node element : node.elements) {
            element.accept(this);
        }
    }

    @Override
    public void visit(ListNode node) {
        if (node.handle != null) {
            node.handle.accept(this);
        }
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
    public void visit(SubroutineNode node) {
        if (node.name != null) {
            values.add(node.name);
        }
        if (node.prototype != null) {
            values.add(node.prototype);
        }
        if (node.attributes != null) {
            for (String attribute : node.attributes) {
                values.add(attribute);
            }
        }
        if (node.block != null) {
            node.block.accept(this);
        }
    }

    // Implement other visit methods as needed
    @Override
    public void visit(For1Node node) {
        if (node.variable != null) node.variable.accept(this);
        if (node.list != null) node.list.accept(this);
        if (node.body != null) node.body.accept(this);
        if (node.continueBlock != null) node.continueBlock.accept(this);
    }

    @Override
    public void visit(For3Node node) {
        if (node.initialization != null) node.initialization.accept(this);
        if (node.condition != null) node.condition.accept(this);
        if (node.increment != null) node.increment.accept(this);
        if (node.body != null) node.body.accept(this);
        if (node.continueBlock != null) node.continueBlock.accept(this);
    }

    @Override
    public void visit(IfNode node) {
        node.condition.accept(this);
        node.thenBranch.accept(this);
        if (node.elseBranch != null) {
            node.elseBranch.accept(this);
        }
    }
}

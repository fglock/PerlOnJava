package org.perlonjava.astvisitor;

import org.perlonjava.astnode.*;
import org.perlonjava.operators.OperatorHandler;

/**
 * A visitor that determines the return type descriptor of AST nodes.
 * Returns type descriptors like "RuntimeScalar;", "RuntimeList;", etc.
 * Returns null if the type cannot be determined.
 */
public class ReturnTypeVisitor implements Visitor {
    private String returnType = null;

    /**
     * Static factory method to get the return type descriptor of a node.
     *
     * @param node The AST node to analyze
     * @return The return type descriptor (e.g., "RuntimeScalar;"), or null if unknown
     */
    public static String getReturnType(Node node) {
        ReturnTypeVisitor visitor = new ReturnTypeVisitor();
        node.accept(visitor);
        return visitor.returnType;
    }

    @Override
    public void visit(NumberNode node) {
        returnType = "RuntimeScalar;";
    }

    @Override
    public void visit(StringNode node) {
        returnType = "RuntimeScalar;";
    }

    @Override
    public void visit(IdentifierNode node) {
        // Identifier type depends on context, cannot determine statically
        returnType = null;
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        returnType = null;
        // XXX - some operators have special cases that are not handled by OperatorHandler
//        OperatorHandler handler = OperatorHandler.get(node.operator);
//        returnType = (handler != null) ? handler.getReturnTypeDescriptor() : null;
    }

    @Override
    public void visit(OperatorNode node) {
        returnType = null;
        // XXX - some operators have special cases that are not handled by OperatorHandler
//        OperatorHandler handler = OperatorHandler.get(node.operator);
//        returnType = (handler != null) ? handler.getReturnTypeDescriptor() : null;

        // Handle special cases not in OperatorHandler
        if (returnType == null) {
            switch (node.operator) {
                case "$":
                case "*":
                case "$#":
                    returnType = "RuntimeScalar;";
                    break;
                case "@":
                    returnType = "RuntimeArray;";
                    break;
                case "%":
                    returnType = "RuntimeHash;";
                    break;
            }
        }
    }

    @Override
    public void visit(ListNode node) {
        returnType = "RuntimeList;";
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        returnType = "RuntimeScalar;";
    }

    @Override
    public void visit(HashLiteralNode node) {
        returnType = "RuntimeScalar;";
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        // Try to determine from true expression
        node.trueExpr.accept(this);
        String trueType = returnType;

        // Check false expression
        node.falseExpr.accept(this);
        String falseType = returnType;

        // If both branches have the same type, use it
        if (trueType != null && trueType.equals(falseType)) {
            returnType = trueType;
        } else {
            // Cannot determine consistent type
            returnType = null;
        }
    }

    @Override
    public void visit(BlockNode node) {
        // Block returns the value of its last expression
        if (!node.elements.isEmpty()) {
            node.elements.get(node.elements.size() - 1).accept(this);
        } else {
            returnType = null;
        }
    }

    @Override
    public void visit(SubroutineNode node) {
        // Subroutine definition itself doesn't have a return type
        returnType = null;
    }

    @Override
    public void visit(IfNode node) {
        // Cannot statically determine return type of if statement
        returnType = null;
    }

    @Override
    public void visit(For1Node node) {
        // For loops don't have a meaningful return type
        returnType = null;
    }

    @Override
    public void visit(For3Node node) {
        // For loops don't have a meaningful return type
        returnType = null;
    }

    @Override
    public void visit(TryNode node) {
        // Cannot statically determine return type of try block
        returnType = null;
    }

    @Override
    public void visit(LabelNode node) {
        // Labels don't have a return type
        returnType = null;
    }

    @Override
    public void visit(CompilerFlagNode node) {
        // Compiler flags don't have a return type
        returnType = null;
    }
}
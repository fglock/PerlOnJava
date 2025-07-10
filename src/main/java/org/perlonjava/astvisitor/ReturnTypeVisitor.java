package org.perlonjava.astvisitor;

import org.perlonjava.astnode.*;
import org.perlonjava.operators.OperatorHandler;
import org.perlonjava.runtime.RuntimeTypeConstants;

/**
 * A visitor that determines the return type descriptor of AST nodes.
 * Returns JVM type descriptors like "Lorg/perlonjava/runtime/RuntimeScalar;",
 * "Lorg/perlonjava/runtime/RuntimeList;", etc.
 * Returns null if the type cannot be determined statically.
 */
public class ReturnTypeVisitor implements Visitor {

    private String returnType = null;

    /**
     * Static factory method to get the return type descriptor of a node.
     *
     * @param node The AST node to analyze
     * @return The JVM type descriptor (e.g., "Lorg/perlonjava/runtime/RuntimeScalar;"), or null if unknown
     */
    public static String getReturnType(Node node) {
        ReturnTypeVisitor visitor = new ReturnTypeVisitor();
        node.accept(visitor);
        return visitor.returnType;
    }

    @Override
    public void visit(NumberNode node) {
        returnType = RuntimeTypeConstants.SCALAR_TYPE;
    }

    @Override
    public void visit(StringNode node) {
        returnType = RuntimeTypeConstants.SCALAR_TYPE;
    }

    @Override
    public void visit(IdentifierNode node) {
        // Identifier type depends on context, cannot determine statically
        returnType = null;
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        // Most binary operators return scalar, but some might return list
        // For now, we can't determine this statically without more context
        returnType = null;

        // XXX - some operators have special cases that are not handled by OperatorHandler
        // Once OperatorHandler is updated, we could do:
        // OperatorHandler handler = OperatorHandler.get(node.operator);
        // if (handler != null) {
        //     returnType = handler.getReturnTypeDescriptor();
        // }

        // Handle special cases not in OperatorHandler
        switch (node.operator) {
            case "(":      // subroutine call
                returnType = RuntimeTypeConstants.LIST_TYPE;
                break;
            case "+", "-", "/", "*", ".":
                returnType = RuntimeTypeConstants.SCALAR_TYPE;
                break;
        }

    }

    @Override
    public void visit(OperatorNode node) {
        // Default to null for most operators
        returnType = null;

        // XXX - some operators have special cases that are not handled by OperatorHandler
        // OperatorHandler handler = OperatorHandler.get(node.operator);
        // if (handler != null) {
        //     returnType = handler.getReturnTypeDescriptor();
        // }

        // Handle special cases not in OperatorHandler
        switch (node.operator) {
            case "$":      // scalar dereference
            case "*":      // glob dereference
            case "$#":     // array last index
            case "scalar": // scalar context
                returnType = RuntimeTypeConstants.SCALAR_TYPE;
                break;
            case "@":      // array dereference
                returnType = RuntimeTypeConstants.ARRAY_TYPE;
                break;
            case "%":      // hash dereference
                returnType = RuntimeTypeConstants.HASH_TYPE;
                break;
        }
    }

    @Override
    public void visit(ListNode node) {
        returnType = RuntimeTypeConstants.LIST_TYPE;
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        // Array literals produce array references (which are scalars)
        returnType = RuntimeTypeConstants.SCALAR_TYPE;
    }

    @Override
    public void visit(HashLiteralNode node) {
        // Hash literals produce hash references (which are scalars)
        returnType = RuntimeTypeConstants.SCALAR_TYPE;
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
        // (The subroutine call would return RuntimeList)
        returnType = null;
    }

    @Override
    public void visit(IfNode node) {
        // Cannot statically determine return type of if statement
        // Would need to analyze all branches
        returnType = null;
    }

    @Override
    public void visit(For1Node node) {
        // For loops don't have a meaningful return type in Perl
        returnType = null;
    }

    @Override
    public void visit(For3Node node) {
        // For loops don't have a meaningful return type in Perl
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

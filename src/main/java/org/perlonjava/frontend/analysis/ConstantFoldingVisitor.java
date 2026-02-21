package org.perlonjava.frontend.analysis;

import org.perlonjava.astnode.*;
import org.perlonjava.runtime.operators.BitwiseOperators;
import org.perlonjava.runtime.operators.MathOperators;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.util.ArrayList;
import java.util.List;

/**
 * AST visitor that performs constant folding optimization.
 * This visitor evaluates constant expressions at compile time,
 * replacing them with their computed values.
 */
public class ConstantFoldingVisitor implements Visitor {

    private Node result;
    private boolean isConstant;

    /**
     * Performs constant folding on the given AST node.
     *
     * @param node The AST node to optimize
     * @return The optimized node (either folded or original)
     */
    public static Node foldConstants(Node node) {
        if (node == null) {
            return null;
        }
        ConstantFoldingVisitor visitor = new ConstantFoldingVisitor();
        node.accept(visitor);
        return visitor.result;
    }

    /**
     * Gets the constant value from a node if it represents a constant.
     * Supports NumberNode, StringNode, and undef OperatorNode.
     *
     * @param node The node to extract a constant value from
     * @return A RuntimeScalar representation of the constant, or null if not a constant
     */
    public static RuntimeScalar getConstantValue(Node node) {
        if (node instanceof NumberNode) {
            return new RuntimeScalar(((NumberNode) node).value);
        } else if (node instanceof StringNode strNode) {
            RuntimeScalar scalar = new RuntimeScalar(strNode.value);
            if (strNode.isVString) {
                scalar.type = RuntimeScalarType.VSTRING;
            }
            return scalar;
        } else if (node instanceof BlockNode blockNode) {
            // Handle empty blocks - they return undef in Perl
            if (blockNode.elements.isEmpty()) {
                return RuntimeScalarCache.scalarUndef;
            }
        } else if (node instanceof ListNode listNode) {
            // Handle empty list/statement - returns undef in scalar context
            if (listNode.elements.isEmpty()) {
                return RuntimeScalarCache.scalarUndef;
            }
        } else if (node instanceof OperatorNode opNode) {
            // Handle undef
            if ("undef".equals(opNode.operator) && opNode.operand == null) {
                return RuntimeScalarCache.scalarUndef;
            }
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

    @Override
    public void visit(NumberNode node) {
        result = node;
        isConstant = true;
    }

    @Override
    public void visit(StringNode node) {
        result = node;
        isConstant = true;
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        // Handle function calls
        if (node.operator.equals("(") && node.left instanceof IdentifierNode) {
            Node folded = foldFunctionCall((IdentifierNode) node.left, node.right, node.tokenIndex);
            if (folded != null) {
                result = folded;
                isConstant = true;
                return;
            }
        }

        // First, recursively fold the operands
        Node foldedLeft = foldConstants(node.left);
        Node foldedRight = foldConstants(node.right);

        // Check if both operands are constants
        if (isConstantNode(foldedLeft) && isConstantNode(foldedRight)) {
            Node folded = foldBinaryOperation(node.operator, foldedLeft, foldedRight, node.tokenIndex);
            if (folded != null) {
                result = folded;
                isConstant = true;
                return;
            }
        }

        // If we can't fold, create a new node with folded operands
        if (foldedLeft != node.left || foldedRight != node.right) {
            result = new BinaryOperatorNode(node.operator, foldedLeft, foldedRight, node.tokenIndex);
        } else {
            result = node;
        }
        isConstant = false;
    }

    @Override
    public void visit(OperatorNode node) {
        if (node.operand == null) {
            result = node;
            isConstant = false;
            return;
        }

        Node foldedOperand = foldConstants(node.operand);

        // Handle unary operators on constants
        if (isConstantNode(foldedOperand)) {
            Node folded = foldUnaryOperation(node.operator, foldedOperand, node.tokenIndex);
            if (folded != null) {
                result = folded;
                isConstant = true;
                return;
            }
        }

        // If we can't fold, create a new node with folded operand
        if (foldedOperand != node.operand) {
            OperatorNode newNode = new OperatorNode(node.operator, foldedOperand, node.tokenIndex);
            newNode.id = node.id;
            result = newNode;
        } else {
            result = node;
        }
        isConstant = false;
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        Node foldedCondition = foldConstants(node.condition);
        Node foldedTrue = foldConstants(node.trueExpr);
        Node foldedFalse = foldConstants(node.falseExpr);

        // If condition is constant, we can eliminate the ternary
        if (isConstantNode(foldedCondition)) {
            RuntimeScalar condValue = getConstantValue(foldedCondition);
            if (condValue.getBoolean()) {
                result = foldedTrue;
                isConstant = isConstantNode(foldedTrue);
            } else {
                result = foldedFalse;
                isConstant = isConstantNode(foldedFalse);
            }
            return;
        }

        // Otherwise, create new node with folded operands
        if (foldedCondition != node.condition || foldedTrue != node.trueExpr || foldedFalse != node.falseExpr) {
            result = new TernaryOperatorNode(node.operator, foldedCondition, foldedTrue, foldedFalse, node.tokenIndex);
        } else {
            result = node;
        }
        isConstant = false;
    }

    @Override
    public void visit(BlockNode node) {
        List<Node> foldedElements = new ArrayList<>();
        boolean changed = false;

        for (Node element : node.elements) {
            Node folded = foldConstants(element);
            if (folded != element) {
                changed = true;
            }
            foldedElements.add(folded);
        }

        if (changed) {
            result = new BlockNode(foldedElements, node.tokenIndex);
        } else {
            result = node;
        }
        isConstant = false;
    }

    @Override
    public void visit(ListNode node) {
        List<Node> foldedElements = new ArrayList<>();
        boolean changed = false;
        boolean allConstant = true;

        for (Node element : node.elements) {
            Node folded = foldConstants(element);
            if (folded != element) {
                changed = true;
            }
            if (!isConstantNode(folded)) {
                allConstant = false;
            }
            foldedElements.add(folded);
        }

        if (changed) {
            result = new ListNode(foldedElements, node.tokenIndex);
        } else {
            result = node;
        }
        isConstant = allConstant && node.elements.size() > 0;
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        List<Node> foldedElements = new ArrayList<>();
        boolean changed = false;

        for (Node element : node.elements) {
            Node folded = foldConstants(element);
            if (folded != element) {
                changed = true;
            }
            foldedElements.add(folded);
        }

        if (changed) {
            result = new ArrayLiteralNode(foldedElements, node.tokenIndex);
        } else {
            result = node;
        }
        isConstant = false;
    }

    // Helper methods

    @Override
    public void visit(HashLiteralNode node) {
        List<Node> foldedElements = new ArrayList<>();
        boolean changed = false;

        for (Node element : node.elements) {
            Node folded = foldConstants(element);
            if (folded != element) {
                changed = true;
            }
            foldedElements.add(folded);
        }

        if (changed) {
            result = new HashLiteralNode(foldedElements, node.tokenIndex);
        } else {
            result = node;
        }
        isConstant = false;
    }

    private boolean isConstantNode(Node node) {
        return node instanceof NumberNode || node instanceof StringNode;
    }

    private Node foldFunctionCall(IdentifierNode function, Node args, int tokenIndex) {
        String funcName = function.name;

        // Only fold if we have a constant argument list
        if (!(args instanceof ListNode argList)) {
            return null;
        }

        // First fold all arguments
        List<Node> foldedArgs = new ArrayList<>();
        for (Node arg : argList.elements) {
            Node folded = foldConstants(arg);
            if (!isConstantNode(folded)) {
                return null; // Can't fold if any argument is not constant
            }
            foldedArgs.add(folded);
        }

        try {
            switch (funcName) {
                // Mathematical functions with one argument
                case "sqrt":
                    if (foldedArgs.size() == 1) {
                        RuntimeScalar arg = getConstantValue(foldedArgs.get(0));
                        double value = arg.getDouble();
                        if (value < 0) {
                            return null; // Don't fold negative sqrt
                        }
                        return new NumberNode(String.valueOf(Math.sqrt(value)), tokenIndex);
                    }
                    break;

                case "sin":
                    if (foldedArgs.size() == 1) {
                        RuntimeScalar arg = getConstantValue(foldedArgs.get(0));
                        return new NumberNode(String.valueOf(Math.sin(arg.getDouble())), tokenIndex);
                    }
                    break;

                case "cos":
                    if (foldedArgs.size() == 1) {
                        RuntimeScalar arg = getConstantValue(foldedArgs.get(0));
                        return new NumberNode(String.valueOf(Math.cos(arg.getDouble())), tokenIndex);
                    }
                    break;

                case "tan":
                    if (foldedArgs.size() == 1) {
                        RuntimeScalar arg = getConstantValue(foldedArgs.get(0));
                        return new NumberNode(String.valueOf(Math.tan(arg.getDouble())), tokenIndex);
                    }
                    break;

                case "exp":
                    if (foldedArgs.size() == 1) {
                        RuntimeScalar arg = getConstantValue(foldedArgs.get(0));
                        return new NumberNode(String.valueOf(Math.exp(arg.getDouble())), tokenIndex);
                    }
                    break;

                case "log":
                    if (foldedArgs.size() == 1) {
                        RuntimeScalar arg = getConstantValue(foldedArgs.get(0));
                        double value = arg.getDouble();
                        if (value <= 0) {
                            return null; // Don't fold non-positive log
                        }
                        return new NumberNode(String.valueOf(Math.log(value)), tokenIndex);
                    }
                    break;

                case "abs":
                    if (foldedArgs.size() == 1) {
                        RuntimeScalar arg = getConstantValue(foldedArgs.get(0));
                        return new NumberNode(String.valueOf(Math.abs(arg.getDouble())), tokenIndex);
                    }
                    break;

                case "int":
                    if (foldedArgs.size() == 1) {
                        RuntimeScalar arg = getConstantValue(foldedArgs.get(0));
                        return new NumberNode(String.valueOf(arg.getInt()), tokenIndex);
                    }
                    break;

                case "atan2":
                    if (foldedArgs.size() == 2) {
                        RuntimeScalar y = getConstantValue(foldedArgs.get(0));
                        RuntimeScalar x = getConstantValue(foldedArgs.get(1));
                        return new NumberNode(String.valueOf(Math.atan2(y.getDouble(), x.getDouble())), tokenIndex);
                    }
                    break;

                case "length":
                    if (foldedArgs.size() == 1) {
                        RuntimeScalar arg = getConstantValue(foldedArgs.get(0));
                        return new NumberNode(String.valueOf(arg.toString().length()), tokenIndex);
                    }
                    break;

                case "ord":
                    if (foldedArgs.size() == 1) {
                        RuntimeScalar arg = getConstantValue(foldedArgs.get(0));
                        String str = arg.toString();
                        if (str.length() > 0) {
                            return new NumberNode(String.valueOf((int) str.charAt(0)), tokenIndex);
                        }
                    }
                    break;

                case "chr":
                    if (foldedArgs.size() == 1) {
                        RuntimeScalar arg = getConstantValue(foldedArgs.get(0));
                        int codePoint = arg.getInt();
                        if (codePoint >= 0 && codePoint <= 0x10FFFF) {
                            return new StringNode(String.valueOf((char) codePoint), tokenIndex);
                        }
                    }
                    break;
            }
        } catch (Exception e) {
            // If any operation fails, don't fold
            return null;
        }

        return null;
    }

    private Node foldBinaryOperation(String operator, Node left, Node right, int tokenIndex) {
        RuntimeScalar leftValue = getConstantValue(left);
        RuntimeScalar rightValue = getConstantValue(right);

        if (leftValue == null || rightValue == null) {
            return null;
        }

        try {
            RuntimeScalar result;
            switch (operator) {
                // Math operations using MathOperators
                case "+":
                    result = MathOperators.add(leftValue, rightValue);
                    return new NumberNode(result.toString(), tokenIndex);

                case "-":
                    result = MathOperators.subtract(leftValue, rightValue);
                    return new NumberNode(result.toString(), tokenIndex);

                case "*":
                    result = MathOperators.multiply(leftValue, rightValue);
                    return new NumberNode(result.toString(), tokenIndex);

                case "/":
                    // Don't fold division by zero
                    if (rightValue.getDouble() == 0.0) {
                        return null;
                    }
                    result = MathOperators.divide(leftValue, rightValue);
                    return new NumberNode(result.toString(), tokenIndex);

                case "%":
                    // Don't fold modulo by zero
                    if (rightValue.getDouble() == 0.0) {
                        return null;
                    }
                    result = MathOperators.modulus(leftValue, rightValue);
                    return new NumberNode(result.toString(), tokenIndex);

                case "**":
                    result = MathOperators.pow(leftValue, rightValue);
                    return new NumberNode(result.toString(), tokenIndex);

                // String operations
                case ".":
                    // String concatenation
                    result = new RuntimeScalar(leftValue.toString() + rightValue);
                    return new StringNode(result.toString(), tokenIndex);

                case "x":
                    // String repetition
                    int count = rightValue.getInt();
                    if (count < 0) count = 0;
                    String str = leftValue.toString();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < count; i++) {
                        sb.append(str);
                    }
                    return new StringNode(sb.toString(), tokenIndex);

                // Bitwise operators using BitwiseOperators
                case "&":
                    result = BitwiseOperators.bitwiseAnd(leftValue, rightValue);
                    if (result.isString()) {
                        return new StringNode(result.toString(), tokenIndex);
                    } else {
                        return new NumberNode(result.toString(), tokenIndex);
                    }

                case "|":
                    result = BitwiseOperators.bitwiseOr(leftValue, rightValue);
                    if (result.isString()) {
                        return new StringNode(result.toString(), tokenIndex);
                    } else {
                        return new NumberNode(result.toString(), tokenIndex);
                    }

//                case "^":
//                    result = BitwiseOperators.bitwiseXor(leftValue, rightValue);
//                    if (result.isString()) {
//                        return new StringNode(result.toString(), tokenIndex);
//                    } else {
//                        // Format the number properly
//                        if (result.type == RuntimeScalarType.INTEGER) {
//                            return new NumberNode(String.valueOf(result.getInt()), tokenIndex);
//                        } else {
//                            double d = result.getDouble();
//                            if (d == Math.floor(d) && !Double.isInfinite(d)) {
//                                return new NumberNode(String.valueOf((long)d), tokenIndex);
//                            } else {
//                                return new NumberNode(String.valueOf(d), tokenIndex);
//                            }
//                        }
//                    }

                case "<<":
                    result = BitwiseOperators.shiftLeft(leftValue, rightValue);
                    return new NumberNode(result.toString(), tokenIndex);

                case ">>":
                    result = BitwiseOperators.shiftRight(leftValue, rightValue);
                    return new NumberNode(result.toString(), tokenIndex);

                // XXX TODO: This must account for Chained operators like `4 < 5 < 3`

//                // Numeric comparison operators using CompareOperators
//                case "<":
//                    result = CompareOperators.lessThan(leftValue, rightValue);
//                    return new NumberNode(result.getBoolean() ? "1" : "", tokenIndex);
//
//                case ">":
//                    result = CompareOperators.greaterThan(leftValue, rightValue);
//                    return new NumberNode(result.getBoolean() ? "1" : "", tokenIndex);
//
//                case "<=":
//                    result = CompareOperators.lessThanOrEqual(leftValue, rightValue);
//                    return new NumberNode(result.getBoolean() ? "1" : "", tokenIndex);
//
//                case ">=":
//                    result = CompareOperators.greaterThanOrEqual(leftValue, rightValue);
//                    return new NumberNode(result.getBoolean() ? "1" : "", tokenIndex);
//
//                case "==":
//                    result = CompareOperators.equalTo(leftValue, rightValue);
//                    return new NumberNode(result.getBoolean() ? "1" : "", tokenIndex);
//
//                case "!=":
//                    result = CompareOperators.notEqualTo(leftValue, rightValue);
//                    return new NumberNode(result.getBoolean() ? "1" : "", tokenIndex);
//
//                case "<=>":
//                    result = CompareOperators.spaceship(leftValue, rightValue);
//                    return new NumberNode(result.toString(), tokenIndex);
//
//                // String comparison operators using CompareOperators
//                case "lt":
//                    result = CompareOperators.lt(leftValue, rightValue);
//                    return new NumberNode(result.getBoolean() ? "1" : "", tokenIndex);
//
//                case "gt":
//                    result = CompareOperators.gt(leftValue, rightValue);
//                    return new NumberNode(result.getBoolean() ? "1" : "", tokenIndex);
//
//                case "le":
//                    result = CompareOperators.le(leftValue, rightValue);
//                    return new NumberNode(result.getBoolean() ? "1" : "", tokenIndex);
//
//                case "ge":
//                    result = CompareOperators.ge(leftValue, rightValue);
//                    return new NumberNode(result.getBoolean() ? "1" : "", tokenIndex);
//
//                case "eq":
//                    result = CompareOperators.eq(leftValue, rightValue);
//                    return new NumberNode(result.getBoolean() ? "1" : "", tokenIndex);
//
//                case "ne":
//                    result = CompareOperators.ne(leftValue, rightValue);
//                    return new NumberNode(result.getBoolean() ? "1" : "", tokenIndex);
//
//                case "cmp":
//                    result = CompareOperators.cmp(leftValue, rightValue);
//                    return new NumberNode(result.toString(), tokenIndex);

                default:
                    return null;
            }
        } catch (Exception e) {
            // If any operation fails, don't fold
            return null;
        }
    }

    private Node foldUnaryOperation(String operator, Node operand, int tokenIndex) {
        RuntimeScalar value = getConstantValue(operand);
        if (value == null) {
            return null;
        }

        try {
            switch (operator) {
                case "-":
                case "unaryMinus":
                    // Unary minus
                    RuntimeScalar result = MathOperators.unaryMinus(value);
                    return new NumberNode(result.toString(), tokenIndex);

                case "!":
                    // Logical not
                    return new NumberNode(value.getBoolean() ? "" : "1", tokenIndex);

//                case "~":
//                    // Bitwise not using BitwiseOperators
//                    RuntimeScalar bitwiseNot = BitwiseOperators.bitwiseNot(value);
//                    if (bitwiseNot.isString()) {
//                        return new StringNode(bitwiseNot.toString(), tokenIndex);
//                    } else {
//                        // Format the number properly
//                        if (bitwiseNot.type == RuntimeScalarType.INTEGER) {
//                            return new NumberNode(String.valueOf(bitwiseNot.getInt()), tokenIndex);
//                        } else {
//                            double d = bitwiseNot.getDouble();
//                            if (d == Math.floor(d) && !Double.isInfinite(d)) {
//                                return new NumberNode(String.valueOf((long)d), tokenIndex);
//                            } else {
//                                return new NumberNode(String.valueOf(d), tokenIndex);
//                            }
//                        }
//                    }

                default:
                    return null;
            }
        } catch (Exception e) {
            // If operation fails, don't fold
            return null;
        }
    }

    // Visit methods for nodes we don't fold

    @Override
    public void visit(IdentifierNode node) {
        result = node;
        isConstant = false;
    }

    @Override
    public void visit(For1Node node) {
        result = node;
        isConstant = false;
    }

    @Override
    public void visit(For3Node node) {
        result = node;
        isConstant = false;
    }

    @Override
    public void visit(IfNode node) {
        result = node;
        isConstant = false;
    }

    @Override
    public void visit(SubroutineNode node) {
        result = node;
        isConstant = false;
    }

    @Override
    public void visit(TryNode node) {
        result = node;
        isConstant = false;
    }

    @Override
    public void visit(LabelNode node) {
        result = node;
        isConstant = false;
    }

    @Override
    public void visit(CompilerFlagNode node) {
        result = node;
        isConstant = false;
    }
}

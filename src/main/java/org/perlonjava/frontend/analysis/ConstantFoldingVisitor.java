package org.perlonjava.frontend.analysis;

import org.perlonjava.frontend.astnode.*;
import org.perlonjava.runtime.operators.BitwiseOperators;
import org.perlonjava.runtime.operators.MathOperators;
import org.perlonjava.runtime.runtimetypes.*;

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
    /** Current package name for resolving bare constant identifiers. May be null. */
    private String currentPackage;

    /**
     * Performs constant folding on the given AST node.
     * Does not resolve user-defined constant subroutines (no package context).
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
     * Performs constant folding on the given AST node, with package context
     * for resolving user-defined constant subroutines (e.g., from {@code use constant}).
     *
     * @param node           The AST node to optimize
     * @param currentPackage The current package name (e.g., "main") for resolving identifiers
     * @return The optimized node (either folded or original)
     */
    public static Node foldConstants(Node node, String currentPackage) {
        if (node == null) {
            return null;
        }
        ConstantFoldingVisitor visitor = new ConstantFoldingVisitor();
        visitor.currentPackage = currentPackage;
        node.accept(visitor);
        return visitor.result;
    }

    /**
     * Recursively folds a child node, propagating the current package context.
     */
    private Node foldChild(Node node) {
        if (node == null) {
            return null;
        }
        if (currentPackage != null) {
            return foldConstants(node, currentPackage);
        }
        return foldConstants(node);
    }

    /**
     * Evaluates whether a condition node is a compile-time constant boolean.
     * Used for dead code elimination in if/unless statements.
     *
     * <p>Handles: literal numbers ({@code if (0)}), literal strings ({@code if ("")}),
     * bare constant sub identifiers ({@code if (WINDOWS)}), and explicit constant
     * sub calls ({@code if (WINDOWS())}).</p>
     *
     * @param condition      The condition node to evaluate
     * @param currentPackage The current package name for resolving identifiers
     * @return {@code Boolean.TRUE} if constant true, {@code Boolean.FALSE} if constant false,
     *         {@code null} if not a compile-time constant
     */
    public static Boolean getConstantConditionValue(Node condition, String currentPackage) {
        // Handle literal numbers (e.g., if (0), if (1))
        if (condition instanceof NumberNode numNode) {
            try {
                double value = Double.parseDouble(numNode.value);
                return value != 0;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        // Handle literal strings (e.g., if (""), if ("0"), if ("true"))
        if (condition instanceof StringNode strNode) {
            String value = strNode.value;
            return !value.isEmpty() && !value.equals("0");
        }

        // Handle bare identifiers that might be constant subroutines (e.g., if (WINDOWS))
        if (condition instanceof IdentifierNode idNode) {
            Boolean result = resolveConstantSubBoolean(idNode.name, currentPackage);
            if (result != null) return result;
        }

        // Handle explicit subroutine calls like WINDOWS()
        // AST: BinaryOperatorNode("(", OperatorNode("&", IdentifierNode("WINDOWS")), ListNode())
        if (condition instanceof BinaryOperatorNode binNode && "(".equals(binNode.operator)) {
            String subName = null;
            if (binNode.left instanceof OperatorNode opNode && "&".equals(opNode.operator)
                    && opNode.operand instanceof IdentifierNode idNode) {
                subName = idNode.name;
            } else if (binNode.left instanceof IdentifierNode idNode) {
                subName = idNode.name;
            }
            if (subName != null) {
                boolean hasNoArgs = binNode.right == null
                        || (binNode.right instanceof ListNode listNode && listNode.elements.isEmpty());
                if (hasNoArgs) {
                    Boolean result = resolveConstantSubBoolean(subName, currentPackage);
                    if (result != null) return result;
                }
            }
        }

        // Handle not/! operators (used for `until` conditions and explicit negation)
        if (condition instanceof OperatorNode opNode && opNode.operand != null) {
            if ("not".equals(opNode.operator) || "!".equals(opNode.operator)) {
                Boolean innerValue = getConstantConditionValue(opNode.operand, currentPackage);
                if (innerValue != null) return !innerValue;
            }
        }

        return null;
    }

    /**
     * Resolves a subroutine name to a constant boolean value, if it's a constant sub.
     */
    private static Boolean resolveConstantSubBoolean(String name, String currentPackage) {
        try {
            String fullName = NameNormalizer.normalizeVariableName(name, currentPackage);
            // Use direct map lookup to avoid side effects of getGlobalCodeRef(),
            // which auto-vivifies empty CODE entries and pins references
            RuntimeScalar codeRef = GlobalVariable.globalCodeRefs.get(fullName);
            if (codeRef != null && codeRef.value instanceof RuntimeCode code) {
                if (code.constantValue != null) {
                    RuntimeList constList = code.constantValue;
                    if (constList.elements.isEmpty()) {
                        return false;
                    }
                    RuntimeBase firstElement = constList.elements.getFirst();
                    if (firstElement instanceof RuntimeScalar scalar) {
                        // References are always truthy in Perl — don't call getBoolean()
                        // which could trigger overloaded bool at compile time
                        if (RuntimeScalarType.isReference(scalar)) {
                            return true;
                        }
                        return scalar.getBoolean();
                    }
                }
            }
        } catch (Exception e) {
            // If lookup fails, treat as non-constant
        }
        return null;
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
            String value = ((NumberNode) node).value;
            // Parse NumberNode values as proper numeric types so arithmetic
            // operations use the correct paths (e.g., double modulus for 13e21 % 4e21).
            // Mirrors BytecodeCompiler.visit(NumberNode) logic for consistency.
            try {
                if (ScalarUtils.isInteger(value)) {
                    return new RuntimeScalar(Integer.parseInt(value));
                } else if (value.matches("^-?\\d+$")) {
                    // Large integer that doesn't fit in int - keep as string
                    // to preserve precision (32-bit Perl emulation)
                    return new RuntimeScalar(value);
                } else {
                    return new RuntimeScalar(Double.parseDouble(value));
                }
            } catch (NumberFormatException e) {
                // Fallback to string for unusual number formats
                return new RuntimeScalar(value);
            }
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

        // Handle constant subroutine calls: CONSTANT() or CONSTANT
        // AST pattern: BinaryOperatorNode("(", OperatorNode("&", IdentifierNode(name)), ListNode())
        if (node.operator.equals("(") && currentPackage != null) {
            String constSubName = null;
            int constTokenIndex = node.tokenIndex;

            if (node.left instanceof OperatorNode opNode && "&".equals(opNode.operator)
                    && opNode.operand instanceof IdentifierNode idNode) {
                constSubName = idNode.name;
                constTokenIndex = idNode.getIndex();
            } else if (node.left instanceof IdentifierNode idNode) {
                // Older AST form: BinaryOperatorNode("(", IdentifierNode(name), ListNode())
                constSubName = idNode.name;
                constTokenIndex = idNode.getIndex();
            }

            if (constSubName != null) {
                // Check if arguments are empty (no-arg call like CONSTANT())
                boolean hasNoArgs = node.right == null
                        || (node.right instanceof ListNode listNode && listNode.elements.isEmpty());
                if (hasNoArgs) {
                    Node resolved = resolveConstantSubValue(constSubName, constTokenIndex);
                    if (resolved != null) {
                        result = resolved;
                        isConstant = true;
                        return;
                    }
                }
            }
        }

        // First, recursively fold the operands
        Node foldedLeft = foldChild(node.left);
        Node foldedRight = foldChild(node.right);

        // Short-circuit constant folding for logical operators.
        // Only the LHS needs to be constant for these.
        // This matches Perl's behavior: `1 && expr` folds to `expr`, `0 && expr` folds to `0`, etc.
        if (isConstantNode(foldedLeft)) {
            RuntimeScalar leftVal = getConstantValue(foldedLeft);
            if (leftVal != null) {
                switch (node.operator) {
                    case "&&": case "and":
                        // true && expr → expr; false && expr → false constant
                        if (leftVal.getBoolean()) {
                            result = foldedRight;
                            isConstant = isConstantNode(foldedRight);
                        } else {
                            // Check for "my() in false conditional" - error since Perl 5.30
                            checkBareDeclarationInFalseConditional(foldedRight, node.tokenIndex);
                            result = foldedLeft;
                            isConstant = true;
                        }
                        return;
                    case "||": case "or":
                        // true || expr → true constant; false || expr → expr
                        if (leftVal.getBoolean()) {
                            // Check for "my() in false conditional" (unless case)
                            checkBareDeclarationInFalseConditional(foldedRight, node.tokenIndex);
                            result = foldedLeft;
                            isConstant = true;
                        } else {
                            result = foldedRight;
                            isConstant = isConstantNode(foldedRight);
                        }
                        return;
                    case "//":
                        // defined // expr → defined constant; undef // expr → expr
                        if (leftVal.getDefinedBoolean()) {
                            result = foldedLeft;
                            isConstant = true;
                        } else {
                            result = foldedRight;
                            isConstant = isConstantNode(foldedRight);
                        }
                        return;
                }
            }
        }

        // Check if both operands are constants
        if (isConstantNode(foldedLeft) && isConstantNode(foldedRight)) {
            // Skip arithmetic folding under 'use integer' - the runtime uses different
            // semantics (truncating division, overflow wrapping, etc.)
            boolean useInteger = node.getBooleanAnnotation("useInteger");
            Node folded = foldBinaryOperation(node.operator, foldedLeft, foldedRight, node.tokenIndex, useInteger);
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
            // undef is a constant
            isConstant = "undef".equals(node.operator);
            return;
        }

        // Don't fold identifiers under the & sigil operator.
        // &Name refers to the subroutine itself (e.g., exists(&Errno::EINVAL), \&sub),
        // not a call. Folding would replace the name with its constant value, breaking
        // exists/defined checks. Calls with parens (&Name()) are handled separately
        // in visit(BinaryOperatorNode) via the "(" operator.
        if ("&".equals(node.operator)) {
            result = node;
            isConstant = false;
            return;
        }

        Node foldedOperand = foldChild(node.operand);

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
        Node foldedCondition = foldChild(node.condition);
        Node foldedTrue = foldChild(node.trueExpr);
        Node foldedFalse = foldChild(node.falseExpr);

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
            Node folded = foldChild(element);
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
            Node folded = foldChild(element);
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
            Node folded = foldChild(element);
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
            Node folded = foldChild(element);
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
        return node instanceof NumberNode || node instanceof StringNode
                || (node instanceof OperatorNode opNode
                    && "undef".equals(opNode.operator) && opNode.operand == null);
    }

    /**
     * Tries to resolve a subroutine name to a constant literal node.
     * Only inlines scalar constants (single RuntimeScalar element with INTEGER, DOUBLE, STRING, or UNDEF type).
     * List constants, reference constants, and non-scalar values are NOT inlined.
     *
     * @param name       The subroutine name (may or may not include package)
     * @param tokenIndex The token index for the created node
     * @return A NumberNode, StringNode, or undef OperatorNode if the sub is a scalar constant, null otherwise
     */
    private Node resolveConstantSubValue(String name, int tokenIndex) {
        if (currentPackage == null) {
            return null;
        }
        try {
            String fullName = NameNormalizer.normalizeVariableName(name, currentPackage);
            // Use direct map lookup to avoid side effects of getGlobalCodeRef(),
            // which auto-vivifies empty CODE entries and pins references
            RuntimeScalar codeRef = GlobalVariable.globalCodeRefs.get(fullName);
            if (codeRef != null && codeRef.value instanceof RuntimeCode code) {
                if (code.constantValue != null) {
                    RuntimeList constList = code.constantValue;
                    // Only inline scalar constants (single element)
                    if (constList.elements.size() == 1) {
                        RuntimeBase firstElement = constList.elements.getFirst();
                        if (firstElement instanceof RuntimeScalar scalar) {
                            // Only inline simple types, not references
                            if (scalar.type == RuntimeScalarType.INTEGER) {
                                return new NumberNode(String.valueOf(scalar.getInt()), tokenIndex);
                            } else if (scalar.type == RuntimeScalarType.DOUBLE) {
                                return new NumberNode(String.valueOf(scalar.getDouble()), tokenIndex);
                            } else if (scalar.type == RuntimeScalarType.STRING) {
                                return new StringNode(scalar.toString(), tokenIndex);
                            } else if (scalar.type == RuntimeScalarType.UNDEF) {
                                return new OperatorNode("undef", null, tokenIndex);
                            }
                            // REFERENCE, GLOB, CODE, etc. are NOT inlined
                        }
                    }
                    // List constants (size > 1) are NOT inlined
                }
            }
        } catch (Exception e) {
            // If lookup fails, don't fold
        }
        return null;
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
            Node folded = foldChild(arg);
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

    /**
     * Creates the appropriate AST node from a RuntimeScalar result.
     * Maps INTEGER/DOUBLE to NumberNode (with Inf/NaN normalization) and STRING to StringNode.
     * Returns null for types that shouldn't be folded (REFERENCE, CODE, etc.).
     */
    private static Node createResultNode(RuntimeScalar result, int tokenIndex) {
        switch (result.type) {
            case RuntimeScalarType.INTEGER:
                return new NumberNode(String.valueOf(result.getInt()), tokenIndex);
            case RuntimeScalarType.DOUBLE:
                double d = result.getDouble();
                String str;
                if (Double.isInfinite(d)) {
                    str = d > 0 ? "Infinity" : "-Infinity";
                } else if (Double.isNaN(d)) {
                    str = "NaN";
                } else {
                    str = String.valueOf(d);
                }
                return new NumberNode(str, tokenIndex);
            case RuntimeScalarType.STRING:
                return new StringNode(result.toString(), tokenIndex);
            case RuntimeScalarType.UNDEF:
                return new OperatorNode("undef", null, tokenIndex);
            default:
                return null;
        }
    }

    private Node foldBinaryOperation(String operator, Node left, Node right, int tokenIndex, boolean useInteger) {
        RuntimeScalar leftValue = getConstantValue(left);
        RuntimeScalar rightValue = getConstantValue(right);

        if (leftValue == null || rightValue == null) {
            return null;
        }

        // Under 'use integer', arithmetic has different semantics (truncating division,
        // overflow wrapping, signed shifts). Don't fold arithmetic in integer scope.
        if (useInteger) {
            switch (operator) {
                case "+": case "-": case "*": case "/": case "%": case "**":
                    return null;
            }
        }

        try {
            RuntimeScalar result;
            switch (operator) {
                // Math operations using MathOperators
                case "+":
                    result = MathOperators.add(leftValue, rightValue);
                    return createResultNode(result, tokenIndex);

                case "-":
                    result = MathOperators.subtract(leftValue, rightValue);
                    return createResultNode(result, tokenIndex);

                case "*":
                    result = MathOperators.multiply(leftValue, rightValue);
                    return createResultNode(result, tokenIndex);

                case "/":
                    // Don't fold division by zero
                    if (rightValue.getDouble() == 0.0) {
                        return null;
                    }
                    result = MathOperators.divide(leftValue, rightValue);
                    return createResultNode(result, tokenIndex);

                case "%":
                    // Don't fold modulo by zero
                    if (rightValue.getDouble() == 0.0) {
                        return null;
                    }
                    result = MathOperators.modulus(leftValue, rightValue);
                    return createResultNode(result, tokenIndex);

                case "**":
                    result = MathOperators.pow(leftValue, rightValue);
                    return createResultNode(result, tokenIndex);

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

                // Bitwise operators - don't fold because results depend on runtime
                // integer/string context and PerlOnJava's 32-bit emulation semantics
                //case "&":
                //case "|":
                //case "<<":
                //case ">>":

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

                //case "<<":
                //    result = BitwiseOperators.shiftLeft(leftValue, rightValue);
                //    return createResultNode(result, tokenIndex);

                //case ">>":
                //    result = BitwiseOperators.shiftRight(leftValue, rightValue);
                //    return createResultNode(result, tokenIndex);

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
                    // Unary minus - may produce string result (e.g., -"hello" → "-hello")
                    RuntimeScalar result = MathOperators.unaryMinus(value);
                    return createResultNode(result, tokenIndex);

                case "!":
                    // Don't fold logical not - Perl's runtime ! returns cached read-only
                    // values ($PL_sv_yes / $PL_sv_no) with reference identity guarantees.
                    // Folding would break: \!0 == \$yes, read-only checks, etc.
                    return null;

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
        // Try to resolve bare identifiers as constant subroutines (e.g., `PI` from `use constant PI => 3.14`)
        if (currentPackage != null) {
            Node resolved = resolveConstantSubValue(node.name, node.getIndex());
            if (resolved != null) {
                result = resolved;
                isConstant = true;
                return;
            }
        }
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
    public void visit(DeferNode node) {
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

    /**
     * Checks if a node is a bare my/state/our declaration (without assignment)
     * being discarded by constant folding in a false conditional context.
     * Throws a compile error for patterns like "my $x if 0;" or "0 && my $x;"
     * which were deprecated in Perl 5.10 and made fatal in Perl 5.30.
     *
     * @param node       The node being discarded
     * @param tokenIndex The source position for error reporting
     */
    private static void checkBareDeclarationInFalseConditional(Node node, int tokenIndex) {
        if (node instanceof OperatorNode opNode) {
            String op = opNode.operator;
            if (op.equals("my") || op.equals("state") || op.equals("our")) {
                throw new PerlCompilerException(
                        "This use of my() in false conditional is no longer allowed");
            }
        }
    }
}

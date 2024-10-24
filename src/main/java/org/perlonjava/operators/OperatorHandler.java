package org.perlonjava.operators;

import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles the mapping of operators to their corresponding method implementations.
 * This class provides a mechanism to associate operators with specific methods
 * in designated classes, allowing for dynamic operator handling.
 */
public class OperatorHandler {
    static Map<String, OperatorHandler> operatorHandlers = new HashMap<>();

    // Static block to initialize operator handlers
    static {
        // Scalar operators
        put("+", "add", "org/perlonjava/operators/ArithmeticOperators");
        put("-", "subtract", "org/perlonjava/operators/ArithmeticOperators");
        put("*", "multiply", "org/perlonjava/operators/ArithmeticOperators");
        put("/", "divide", "org/perlonjava/operators/ArithmeticOperators");
        put("%", "modulus", "org/perlonjava/operators/ArithmeticOperators");

        put("**", "pow");
        put(".", "stringConcat");
        put("&", "bitwiseAnd");
        put("|", "bitwiseOr");
        put("^", "bitwiseXor");
        put("<<", "shiftLeft");
        put(">>", "shiftRight");
        put("x", "repeat");
        put("&.", "bitwiseStringAnd");
        put("&&", "logicalAnd");
        put("|.", "bitwiseStringOr");
        put("||", "logicalOr");
        put("^.", "bitwiseStringXor");
        put("//", "logicalDefinedOr");
        put("isa", "isa");
        put("bless", "bless");

        put("<", "lessThan", "org/perlonjava/operators/CompareOperators");
        put("<=", "lessThanOrEqual", "org/perlonjava/operators/CompareOperators");
        put(">", "greaterThan", "org/perlonjava/operators/CompareOperators");
        put(">=", "greaterThanOrEqual", "org/perlonjava/operators/CompareOperators");
        put("==", "equalTo", "org/perlonjava/operators/CompareOperators");
        put("!=", "notEqualTo", "org/perlonjava/operators/CompareOperators");
        put("<=>", "spaceship", "org/perlonjava/operators/CompareOperators");
        put("eq", "eq", "org/perlonjava/operators/CompareOperators");
        put("ne", "ne", "org/perlonjava/operators/CompareOperators");
        put("lt", "lt", "org/perlonjava/operators/CompareOperators");
        put("le", "le", "org/perlonjava/operators/CompareOperators");
        put("gt", "gt", "org/perlonjava/operators/CompareOperators");
        put("ge", "ge", "org/perlonjava/operators/CompareOperators");
        put("cmp", "cmp", "org/perlonjava/operators/CompareOperators");

        // List operators
        put("map", "map",
                "org/perlonjava/operators/ListOperators",
                "(Lorg/perlonjava/runtime/RuntimeList;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeList;");
        put("grep", "grep",
                "org/perlonjava/operators/ListOperators",
                "(Lorg/perlonjava/runtime/RuntimeList;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeList;");
        put("sort", "sort",
                "org/perlonjava/operators/ListOperators",
                "(Lorg/perlonjava/runtime/RuntimeList;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeList;");
    }

    private final String className;
    private final String methodName;
    private final int methodType; // Opcodes.INVOKESTATIC
    private final String descriptor;

    /**
     * Constructs an OperatorHandler with the specified class name, method name, method type, and descriptor.
     *
     * @param className  The name of the class containing the method.
     * @param methodName The name of the method to invoke.
     * @param methodType The type of method invocation (e.g., INVOKESTATIC).
     * @param descriptor The method descriptor indicating the method signature.
     */
    public OperatorHandler(String className, String methodName, int methodType, String descriptor) {
        this.className = className;
        this.methodName = methodName;
        this.methodType = methodType;
        this.descriptor = descriptor;
    }

    /**
     * Associates an operator with a method in the RuntimeScalar class.
     *
     * @param operator   The operator symbol (e.g., "+").
     * @param methodName The name of the method to associate with the operator.
     */
    static void put(String operator, String methodName) {
        operatorHandlers.put(operator,
                new OperatorHandler("org/perlonjava/runtime/RuntimeScalar",
                        methodName,
                        Opcodes.INVOKEVIRTUAL,
                        "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;"));
    }

    /**
     * Associates an operator with a method in a specified class.
     *
     * @param operator   The operator symbol (e.g., "+").
     * @param methodName The name of the method to associate with the operator.
     * @param className  The name of the class containing the method.
     */
    static void put(String operator, String methodName, String className) {
        operatorHandlers.put(operator,
                new OperatorHandler(className,
                        methodName,
                        Opcodes.INVOKESTATIC,
                        "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;"));
    }

    /**
     * Associates an operator with a method in a specified class.
     *
     * @param operator   The operator symbol (e.g., "+").
     * @param methodName The name of the method to associate with the operator.
     * @param className  The name of the class containing the method.
     * @param descriptor The JVM parameter descriptor
     */
    static void put(String operator, String methodName, String className, String descriptor) {
        operatorHandlers.put(operator,
                new OperatorHandler(className,
                        methodName,
                        Opcodes.INVOKESTATIC,
                        descriptor));
    }

    /**
     * Retrieves the OperatorHandler associated with the specified operator.
     *
     * @param operator The operator symbol.
     * @return The OperatorHandler associated with the operator, or null if not found.
     */
    public static OperatorHandler get(String operator) {
        return operatorHandlers.get(operator);
    }

    /**
     * Gets the class name containing the method associated with the operator.
     *
     * @return The class name.
     */
    public String getClassName() {
        return className;
    }

    /**
     * Gets the method name associated with the operator.
     *
     * @return The method name.
     */
    public String getMethodName() {
        return methodName;
    }

    /**
     * Gets the method type (e.g., INVOKESTATIC) for the operator.
     *
     * @return The method type.
     */
    public int getMethodType() {
        return methodType;
    }

    /**
     * Gets the method descriptor indicating the method signature.
     *
     * @return The method descriptor.
     */
    public String getDescriptor() {
        return descriptor;
    }

    /**
     * Gets the method descriptor with an integer parameter, replacing the last argument with an integer.
     *
     * @return The modified method descriptor.
     */
    public String getDescriptorWithIntParameter() {
        String descriptor = this.descriptor;
        // replace last argument with `I`
        return descriptor.replace("Lorg/perlonjava/runtime/RuntimeScalar;)", "I)");
    }
}

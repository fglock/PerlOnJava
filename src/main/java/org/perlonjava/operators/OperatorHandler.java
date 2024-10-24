package org.perlonjava.operators;

import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;

public class OperatorHandler {
    private final String className;
    private final String methodName;
    private final int methodType; // Opcodes.INVOKESTATIC

    static Map<String, OperatorHandler> operatorHandlers = new HashMap<>();

    // Static block to initialize operator handlers
    static {
        put("**", "pow");
        put("+", "add");
        put("-", "subtract");
        put("*", "multiply");
        put("/", "divide");
        put("%", "modulus");
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
        put("<", "lessThan");
        put("<=", "lessThanOrEqual");
        put(">", "greaterThan");
        put(">=", "greaterThanOrEqual");
        put("==", "equalTo");
        put("!=", "notEqualTo");
        put("<=>", "spaceship");
        put("eq", "eq");
        put("ne", "ne");
        put("lt", "lt");
        put("le", "le");
        put("gt", "gt");
        put("ge", "ge");
        put("cmp", "cmp");
        put("bless", "bless");
    }

    public OperatorHandler(String className, String methodName, int methodType) {
        this.className = className;
        this.methodName = methodName;
        this.methodType = methodType;
    }

    // OperatorHandler.put("+", "add");
    static void put(String operator, String methodName) {
        operatorHandlers.put(operator, new OperatorHandler("org/perlonjava/runtime/RuntimeScalar", methodName, Opcodes.INVOKEVIRTUAL));
    }

    // OperatorHandler.put("+", "add", "org.perlonjava.operators.ArithmeticOperators");
    static void put(String operator, String methodName, String className) {
        operatorHandlers.put(operator, new OperatorHandler(className, methodName, Opcodes.INVOKESTATIC));
    }

    public static OperatorHandler get(String operator) {
        return operatorHandlers.get(operator);
    }

    public String getClassName() {
        return className;
    }

    public String getMethodName() {
        return methodName;
    }

    public int getMethodType() {
        return methodType;
    }
}


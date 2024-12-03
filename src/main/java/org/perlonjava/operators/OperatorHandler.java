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

        // Arithmetic
        put("+", "add", "org/perlonjava/operators/MathOperators");
        put("-", "subtract", "org/perlonjava/operators/MathOperators");
        put("*", "multiply", "org/perlonjava/operators/MathOperators");
        put("/", "divide", "org/perlonjava/operators/MathOperators");
        put("%", "modulus", "org/perlonjava/operators/MathOperators");
        put("unaryMinus", "unaryMinus", "org/perlonjava/operators/MathOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");

        put("log", "log", "org/perlonjava/operators/MathOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("sqrt", "sqrt", "org/perlonjava/operators/MathOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("cos", "cos", "org/perlonjava/operators/MathOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("sin", "sin", "org/perlonjava/operators/MathOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("exp", "exp", "org/perlonjava/operators/MathOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("abs", "abs", "org/perlonjava/operators/MathOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("**", "pow", "org/perlonjava/operators/MathOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("atan2", "atan2", "org/perlonjava/operators/MathOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");

        // Bitwise
        put("&", "bitwiseAnd", "org/perlonjava/operators/BitwiseOperators");
        put("|", "bitwiseOr", "org/perlonjava/operators/BitwiseOperators");
        put("^", "bitwiseXor", "org/perlonjava/operators/BitwiseOperators");
        put("<<", "shiftLeft", "org/perlonjava/operators/BitwiseOperators");
        put(">>", "shiftRight", "org/perlonjava/operators/BitwiseOperators");
        put("bitwiseNot", "bitwiseNot", "org/perlonjava/operators/BitwiseOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("&.", "bitwiseAndDot", "org/perlonjava/operators/BitwiseOperators");
        put("|.", "bitwiseOrDot", "org/perlonjava/operators/BitwiseOperators");
        put("^.", "bitwiseXorDot", "org/perlonjava/operators/BitwiseOperators");
        put("bitwiseNotDot", "bitwiseNotDot", "org/perlonjava/operators/BitwiseOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("binary&", "bitwiseAndBinary", "org/perlonjava/operators/BitwiseOperators");
        put("binary|", "bitwiseOrBinary", "org/perlonjava/operators/BitwiseOperators");
        put("binary^", "bitwiseXorBinary", "org/perlonjava/operators/BitwiseOperators");
        put("bitwiseNotBinary", "bitwiseNotBinary", "org/perlonjava/operators/BitwiseOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");

        // Scalar
        put("ord", "ord", "org/perlonjava/operators/ScalarOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("oct", "oct", "org/perlonjava/operators/ScalarOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("hex", "hex", "org/perlonjava/operators/ScalarOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");

        // Compare
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

        // String
        put("length", "length", "org/perlonjava/operators/StringOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("quotemeta", "quotemeta", "org/perlonjava/operators/StringOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("fc", "fc", "org/perlonjava/operators/StringOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("lc", "lc", "org/perlonjava/operators/StringOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("lcfirst", "lcfirst", "org/perlonjava/operators/StringOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("uc", "uc", "org/perlonjava/operators/StringOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("ucfirst", "ucfirst", "org/perlonjava/operators/StringOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("rindex", "rindex", "org/perlonjava/operators/StringOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("index", "index", "org/perlonjava/operators/StringOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");

        // Time
        put("time", "time", "org/perlonjava/operators/Time", "()Lorg/perlonjava/runtime/RuntimeScalar;");
        put("times", "times", "org/perlonjava/operators/Time", "()Lorg/perlonjava/runtime/RuntimeList;");
        put("gmtime", "gmtime", "org/perlonjava/operators/Time", "(Lorg/perlonjava/runtime/RuntimeList;I)Lorg/perlonjava/runtime/RuntimeList;");
        put("localtime", "localtime", "org/perlonjava/operators/Time", "(Lorg/perlonjava/runtime/RuntimeList;I)Lorg/perlonjava/runtime/RuntimeList;");
        put("sleep", "sleep", "org/perlonjava/operators/Time", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");

        // Directory
        put("rmdir", "rmdir", "org/perlonjava/operators/Directory", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("closedir", "closedir", "org/perlonjava/operators/Directory", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("rewinddir", "rewinddir", "org/perlonjava/operators/Directory", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("telldir", "telldir", "org/perlonjava/operators/Directory", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("readdir", "readdir", "org/perlonjava/operators/Directory", "(Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;");
        put("mkdir", "mkdir", "org/perlonjava/operators/Directory", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("seekdir", "seekdir", "org/perlonjava/operators/Directory", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("chdir", "chdir", "org/perlonjava/operators/Directory", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("opendir", "opendir", "org/perlonjava/operators/Directory", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("glob", "evaluate", "org/perlonjava/operators/ScalarGlobOperator", "(ILorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeDataProvider;");

        // Modules
        put("doFile", "doFile", "org/perlonjava/operators/ModuleOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("require", "require", "org/perlonjava/operators/ModuleOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");

        // Misc
        put(".", "stringConcat");
        put("x", "repeat");
        put("&&", "logicalAnd");
        put("||", "logicalOr");
        put("//", "logicalDefinedOr");
        put("isa", "isa");
        put("bless", "bless");

        put("select", "select", "org/perlonjava/operators/Operator", "(Lorg/perlonjava/runtime/RuntimeList;I)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("caller", "caller", "org/perlonjava/runtime/RuntimeCode", "(Lorg/perlonjava/runtime/RuntimeList;I)Lorg/perlonjava/runtime/RuntimeList;");
        put("reset", "reset", "org/perlonjava/runtime/RuntimeScalar", "(Lorg/perlonjava/runtime/RuntimeList;I)Lorg/perlonjava/runtime/RuntimeList;");
        put("warn", "warn", "org/perlonjava/operators/WarnDie", "(Lorg/perlonjava/runtime/RuntimeDataProvider;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeDataProvider;");
        put("die", "die", "org/perlonjava/operators/WarnDie", "(Lorg/perlonjava/runtime/RuntimeDataProvider;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeDataProvider;");
        put("reverse", "reverse", "org/perlonjava/operators/Operator", "(Lorg/perlonjava/runtime/RuntimeDataProvider;I)Lorg/perlonjava/runtime/RuntimeDataProvider;");
        put("crypt", "crypt", "org/perlonjava/operators/Crypt", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("unlink", "unlink", "org/perlonjava/operators/Operator", "(Lorg/perlonjava/runtime/RuntimeDataProvider;I)Lorg/perlonjava/runtime/RuntimeDataProvider;");
        put("stat", "stat", "org/perlonjava/operators/Stat", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeList;");
        put("lstat", "lstat", "org/perlonjava/operators/Stat", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeList;");
        put("vec", "vec", "org/perlonjava/operators/Vec", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;");

        put("unpack", "unpack", "org/perlonjava/operators/Unpack", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeList;");
        put("pack", "pack", "org/perlonjava/operators/Pack", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;");

        put("join", "join", "org/perlonjava/operators/Operator", "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeDataProvider;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("split", "split", "org/perlonjava/operators/Operator", "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeList;");

        // List operators
        put("map", "map",
                "org/perlonjava/operators/ListOperators",
                "(Lorg/perlonjava/runtime/RuntimeList;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeList;");
        put("grep", "grep",
                "org/perlonjava/operators/ListOperators",
                "(Lorg/perlonjava/runtime/RuntimeList;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeList;");
        put("sort", "sort",
                "org/perlonjava/operators/ListOperators",
                "(Lorg/perlonjava/runtime/RuntimeList;Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeList;");

        operatorHandlers.put("scalar",
                new OperatorHandler("org/perlonjava/runtime/RuntimeDataProvider",
                        "scalar",
                        Opcodes.INVOKEINTERFACE,
                        "()Lorg/perlonjava/runtime/RuntimeScalar;"));
        operatorHandlers.put("each",
                new OperatorHandler("org/perlonjava/runtime/RuntimeDataProvider",
                        "each",
                        Opcodes.INVOKEINTERFACE,
                        "()Lorg/perlonjava/runtime/RuntimeList;"));
        operatorHandlers.put("keys",
                new OperatorHandler("org/perlonjava/runtime/RuntimeDataProvider",
                        "keys",
                        Opcodes.INVOKEINTERFACE,
                        "()Lorg/perlonjava/runtime/RuntimeArray;"));
        operatorHandlers.put("values",
                new OperatorHandler("org/perlonjava/runtime/RuntimeDataProvider",
                        "values",
                        Opcodes.INVOKEINTERFACE,
                        "()Lorg/perlonjava/runtime/RuntimeArray;"));


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

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
        put("!", "not", "org/perlonjava/operators/MathOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("not", "not", "org/perlonjava/operators/MathOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");

        put("^^", "xor", "org/perlonjava/operators/Operator", "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("xor", "xor", "org/perlonjava/operators/Operator", "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");

        put("int", "integer", "org/perlonjava/operators/MathOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
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
        put("~", "bitwiseNot", "org/perlonjava/operators/BitwiseOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("&.", "bitwiseAndDot", "org/perlonjava/operators/BitwiseOperators");
        put("|.", "bitwiseOrDot", "org/perlonjava/operators/BitwiseOperators");
        put("^.", "bitwiseXorDot", "org/perlonjava/operators/BitwiseOperators");
        put("~.", "bitwiseNotDot", "org/perlonjava/operators/BitwiseOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("binary&", "bitwiseAndBinary", "org/perlonjava/operators/BitwiseOperators");
        put("binary|", "bitwiseOrBinary", "org/perlonjava/operators/BitwiseOperators");
        put("binary^", "bitwiseXorBinary", "org/perlonjava/operators/BitwiseOperators");
        put("binary~", "bitwiseNotBinary", "org/perlonjava/operators/BitwiseOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");

        // Scalar
        put("ord", "ord", "org/perlonjava/operators/ScalarOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("oct", "oct", "org/perlonjava/operators/ScalarOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("hex", "hex", "org/perlonjava/operators/ScalarOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");

        put("srand", "srand", "org/perlonjava/operators/Random", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("rand", "rand", "org/perlonjava/operators/Random", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");

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
        put("chr", "chr", "org/perlonjava/operators/StringOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("length", "length", "org/perlonjava/operators/StringOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("quotemeta", "quotemeta", "org/perlonjava/operators/StringOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("fc", "fc", "org/perlonjava/operators/StringOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("lc", "lc", "org/perlonjava/operators/StringOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("lcfirst", "lcfirst", "org/perlonjava/operators/StringOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("uc", "uc", "org/perlonjava/operators/StringOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("ucfirst", "ucfirst", "org/perlonjava/operators/StringOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("rindex", "rindex", "org/perlonjava/operators/StringOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("index", "index", "org/perlonjava/operators/StringOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put(".", "stringConcat", "org/perlonjava/operators/StringOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");

        // Time
        put("time", "time", "org/perlonjava/operators/Time", "()Lorg/perlonjava/runtime/RuntimeScalar;");
        put("times", "times", "org/perlonjava/operators/Time", "()Lorg/perlonjava/runtime/RuntimeList;");
        put("gmtime", "gmtime", "org/perlonjava/operators/Time", "(Lorg/perlonjava/runtime/RuntimeList;I)Lorg/perlonjava/runtime/RuntimeList;");
        put("localtime", "localtime", "org/perlonjava/operators/Time", "(Lorg/perlonjava/runtime/RuntimeList;I)Lorg/perlonjava/runtime/RuntimeList;");
        put("sleep", "sleep", "org/perlonjava/operators/Time", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");

        // File
        put("readline", "readline", "org/perlonjava/operators/Readline", "(Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeBase;");
        put("close", "close", "org/perlonjava/operators/IOOperator", "([Lorg/perlonjava/runtime/RuntimeBase;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("tell", "tell", "org/perlonjava/operators/IOOperator", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("fileno", "fileno", "org/perlonjava/operators/IOOperator", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("getc", "getc", "org/perlonjava/operators/IOOperator", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("binmode", "binmode", "org/perlonjava/operators/IOOperator", "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("seek", "seek", "org/perlonjava/operators/IOOperator", "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("select", "select", "org/perlonjava/operators/IOOperator", "(Lorg/perlonjava/runtime/RuntimeList;I)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("truncate", "truncate", "org/perlonjava/operators/IOOperator", "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("sysread", "sysread", "org/perlonjava/operators/IOOperator", "([Lorg/perlonjava/runtime/RuntimeBase;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("syswrite", "syswrite", "org/perlonjava/operators/IOOperator", "([Lorg/perlonjava/runtime/RuntimeBase;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("sysopen", "sysopen", "org/perlonjava/operators/IOOperator", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;");

        // Directory
        put("rmdir", "rmdir", "org/perlonjava/operators/Directory", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("closedir", "closedir", "org/perlonjava/operators/Directory", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("rewinddir", "rewinddir", "org/perlonjava/operators/Directory", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("telldir", "telldir", "org/perlonjava/operators/Directory", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("readdir", "readdir", "org/perlonjava/operators/Directory", "(Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeBase;");
        put("mkdir", "mkdir", "org/perlonjava/operators/Directory", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("seekdir", "seekdir", "org/perlonjava/operators/Directory", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("chdir", "chdir", "org/perlonjava/operators/Directory", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("opendir", "opendir", "org/perlonjava/operators/Directory", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("glob", "evaluate", "org/perlonjava/operators/ScalarGlobOperator", "(ILorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeBase;");

        // Modules
        put("doFile", "doFile", "org/perlonjava/operators/ModuleOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("require", "require", "org/perlonjava/operators/ModuleOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;Z)Lorg/perlonjava/runtime/RuntimeScalar;");

        // Misc
        put("isa", "isa", "org/perlonjava/operators/ReferenceOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("bless", "bless", "org/perlonjava/operators/ReferenceOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("ref", "ref", "org/perlonjava/operators/ReferenceOperators", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");

        put("caller", "caller", "org/perlonjava/runtime/RuntimeCode", "(Lorg/perlonjava/runtime/RuntimeList;I)Lorg/perlonjava/runtime/RuntimeList;");
        put("reset", "reset", "org/perlonjava/operators/Operator", "(Lorg/perlonjava/runtime/RuntimeList;I)Lorg/perlonjava/runtime/RuntimeList;");
        put("warn", "warn", "org/perlonjava/operators/WarnDie", "(Lorg/perlonjava/runtime/RuntimeBase;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeBase;");
        put("die", "die", "org/perlonjava/operators/WarnDie", "(Lorg/perlonjava/runtime/RuntimeBase;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeBase;");
        put("exit", "exit", "org/perlonjava/operators/WarnDie", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("reverse", "reverse", "org/perlonjava/operators/Operator", "(Lorg/perlonjava/runtime/RuntimeBase;I)Lorg/perlonjava/runtime/RuntimeBase;");
        put("crypt", "crypt", "org/perlonjava/operators/Crypt", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("unlink", "unlink", "org/perlonjava/operators/Operator", "(Lorg/perlonjava/runtime/RuntimeBase;I)Lorg/perlonjava/runtime/RuntimeBase;");
        put("stat", "stat", "org/perlonjava/operators/Stat", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeList;");
        put("lstat", "lstat", "org/perlonjava/operators/Stat", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeList;");
        put("vec", "vec", "org/perlonjava/operators/Vec", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("chmod", "chmod", "org/perlonjava/operators/Operator", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;");

        put("unpack", "unpack", "org/perlonjava/operators/Unpack", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeList;");
        put("pack", "pack", "org/perlonjava/operators/Pack", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("read", "read", "org/perlonjava/operators/Readline", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;");

        put("x", "repeat", "org/perlonjava/operators/Operator", "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("join", "join", "org/perlonjava/operators/Operator", "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeBase;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("split", "split", "org/perlonjava/operators/Operator", "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeList;");

        put("..", "createRange", "org/perlonjava/runtime/PerlRange", "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/PerlRange;");

        put("substr", "substr", "org/perlonjava/operators/Operator", "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("sprintf", "sprintf", "org/perlonjava/operators/SprintfOperator", "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeScalar;");

        put("systemCommand", "systemCommand", "org/perlonjava/operators/SystemOperator", "(Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeBase;");
        put("system", "system", "org/perlonjava/operators/SystemOperator", "(Lorg/perlonjava/runtime/RuntimeList;ZI)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("exec", "exec", "org/perlonjava/operators/SystemOperator", "(Lorg/perlonjava/runtime/RuntimeList;ZI)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("fork", "fork", "org/perlonjava/operators/SystemOperator", "(Lorg/perlonjava/runtime/RuntimeList;I)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("kill", "kill", "org/perlonjava/operators/KillOperator", "([Lorg/perlonjava/runtime/RuntimeBase;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("waitpid", "waitpid", "org/perlonjava/operators/WaitpidOperator", "([Lorg/perlonjava/runtime/RuntimeBase;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("utime", "utime", "org/perlonjava/operators/UtimeOperator", "([Lorg/perlonjava/runtime/RuntimeBase;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("chown", "chown", "org/perlonjava/operators/ChownOperator", "([Lorg/perlonjava/runtime/RuntimeBase;)Lorg/perlonjava/runtime/RuntimeScalar;");

        put("$#", "indexLastElem", "org/perlonjava/runtime/RuntimeArray", "(Lorg/perlonjava/runtime/RuntimeArray;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("pop", "pop", "org/perlonjava/runtime/RuntimeArray", "(Lorg/perlonjava/runtime/RuntimeArray;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("shift", "shift", "org/perlonjava/runtime/RuntimeArray", "(Lorg/perlonjava/runtime/RuntimeArray;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("splice", "splice", "org/perlonjava/operators/Operator", "(Lorg/perlonjava/runtime/RuntimeArray;Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeList;");
        put("push", "push", "org/perlonjava/runtime/RuntimeArray", "(Lorg/perlonjava/runtime/RuntimeArray;Lorg/perlonjava/runtime/RuntimeBase;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("unshift", "unshift", "org/perlonjava/runtime/RuntimeArray", "(Lorg/perlonjava/runtime/RuntimeArray;Lorg/perlonjava/runtime/RuntimeBase;)Lorg/perlonjava/runtime/RuntimeScalar;");

        put("undef", "undef", "org/perlonjava/operators/Operator", "()Lorg/perlonjava/runtime/RuntimeScalar;");
        put("wantarray", "wantarray", "org/perlonjava/operators/Operator", "(I)Lorg/perlonjava/runtime/RuntimeScalar;");

        put("prototype", "prototype", "org/perlonjava/runtime/RuntimeCode", "(Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;");

        // Tie
        put("tie", "tie", "org/perlonjava/operators/TieOperators", "([Lorg/perlonjava/runtime/RuntimeBase;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("untie", "untie", "org/perlonjava/operators/TieOperators", "([Lorg/perlonjava/runtime/RuntimeBase;)Lorg/perlonjava/runtime/RuntimeScalar;");
        put("tied", "tied", "org/perlonjava/operators/TieOperators", "([Lorg/perlonjava/runtime/RuntimeBase;)Lorg/perlonjava/runtime/RuntimeScalar;");

        // List operators
        put("map", "map",
                "org/perlonjava/operators/ListOperators",
                "(Lorg/perlonjava/runtime/RuntimeList;Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeList;");
        put("grep", "grep",
                "org/perlonjava/operators/ListOperators",
                "(Lorg/perlonjava/runtime/RuntimeList;Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeList;");
        put("sort", "sort",
                "org/perlonjava/operators/ListOperators",
                "(Lorg/perlonjava/runtime/RuntimeList;Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeList;");
        put("all", "all",
                "org/perlonjava/operators/ListOperators",
                "(Lorg/perlonjava/runtime/RuntimeList;Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeList;");
        put("any", "any",
                "org/perlonjava/operators/ListOperators",
                "(Lorg/perlonjava/runtime/RuntimeList;Lorg/perlonjava/runtime/RuntimeScalar;I)Lorg/perlonjava/runtime/RuntimeList;");

        operatorHandlers.put("scalar",
                new OperatorHandler("org/perlonjava/runtime/RuntimeBase",
                        "scalar",
                        Opcodes.INVOKEVIRTUAL,
                        "()Lorg/perlonjava/runtime/RuntimeScalar;"));
        operatorHandlers.put("each",
                new OperatorHandler("org/perlonjava/runtime/RuntimeBase",
                        "each",
                        Opcodes.INVOKEVIRTUAL,
                        "()Lorg/perlonjava/runtime/RuntimeList;"));
        operatorHandlers.put("keys",
                new OperatorHandler("org/perlonjava/runtime/RuntimeBase",
                        "keys",
                        Opcodes.INVOKEVIRTUAL,
                        "()Lorg/perlonjava/runtime/RuntimeArray;"));
        operatorHandlers.put("values",
                new OperatorHandler("org/perlonjava/runtime/RuntimeBase",
                        "values",
                        Opcodes.INVOKEVIRTUAL,
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

    /**
     * Gets the return type descriptor from this handler's method descriptor.
     *
     * @return The return type class name with semicolon (e.g., "RuntimeScalar;"),
     *         or null if return type cannot be determined
     */
    public String getReturnTypeDescriptor() {
        if (descriptor == null) {
            return null;
        }

        // Extract return type from descriptor
        int lastParen = descriptor.lastIndexOf(')');
        if (lastParen == -1 || lastParen >= descriptor.length() - 1) {
            return null;
        }

        String fullReturnType = descriptor.substring(lastParen + 1);

        // Handle object types (start with 'L' and end with ';')
        if (fullReturnType.startsWith("L") && fullReturnType.endsWith(";")) {
            // Remove the 'L' prefix
            fullReturnType = fullReturnType.substring(1);

            // Extract just the class name with semicolon
            int lastSlash = fullReturnType.lastIndexOf('/');
            if (lastSlash != -1) {
                return fullReturnType.substring(lastSlash + 1);
            } else {
                return fullReturnType;
            }
        }

        // For primitive types or other cases
        return null;
    }
}

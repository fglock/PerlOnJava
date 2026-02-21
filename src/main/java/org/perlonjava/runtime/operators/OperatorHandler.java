package org.perlonjava.runtime.operators;

import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles the mapping of operators to their corresponding method implementations.
 * This class provides a mechanism to associate operators with specific methods
 * in designated classes, allowing for dynamic operator handling.
 *
 * @param methodType Opcodes.INVOKESTATIC
 */
public record OperatorHandler(String className, String methodName, int methodType, String descriptor) {
    static Map<String, OperatorHandler> operatorHandlers = new HashMap<>();

    // Static block to initialize operator handlers
    static {
        // Scalar operators

        // Arithmetic
        put("+", "add", "org/perlonjava/runtime/operators/MathOperators");
        put("-", "subtract", "org/perlonjava/runtime/operators/MathOperators");
        put("*", "multiply", "org/perlonjava/runtime/operators/MathOperators");
        put("/", "divide", "org/perlonjava/runtime/operators/MathOperators");
        put("%", "modulus", "org/perlonjava/runtime/operators/MathOperators");
        put("unaryMinus", "unaryMinus", "org/perlonjava/runtime/operators/MathOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("!", "not", "org/perlonjava/runtime/operators/MathOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("not", "not", "org/perlonjava/runtime/operators/MathOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        put("^^", "xor", "org/perlonjava/runtime/operators/Operator", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("xor", "xor", "org/perlonjava/runtime/operators/Operator", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        put("int", "integer", "org/perlonjava/runtime/operators/MathOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("log", "log", "org/perlonjava/runtime/operators/MathOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("sqrt", "sqrt", "org/perlonjava/runtime/operators/MathOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("cos", "cos", "org/perlonjava/runtime/operators/MathOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("sin", "sin", "org/perlonjava/runtime/operators/MathOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("exp", "exp", "org/perlonjava/runtime/operators/MathOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("abs", "abs", "org/perlonjava/runtime/operators/MathOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("**", "pow", "org/perlonjava/runtime/operators/MathOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("atan2", "atan2", "org/perlonjava/runtime/operators/MathOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        // Compound assignment operators (with overload support)
        put("+=", "addAssign", "org/perlonjava/runtime/operators/MathOperators");
        put("-=", "subtractAssign", "org/perlonjava/runtime/operators/MathOperators");
        put("*=", "multiplyAssign", "org/perlonjava/runtime/operators/MathOperators");
        put("/=", "divideAssign", "org/perlonjava/runtime/operators/MathOperators");
        put("%=", "modulusAssign", "org/perlonjava/runtime/operators/MathOperators");

        // Bitwise
        put("&", "bitwiseAnd", "org/perlonjava/runtime/operators/BitwiseOperators");
        put("|", "bitwiseOr", "org/perlonjava/runtime/operators/BitwiseOperators");
        put("^", "bitwiseXor", "org/perlonjava/runtime/operators/BitwiseOperators");
        put("<<", "shiftLeft", "org/perlonjava/runtime/operators/BitwiseOperators");
        put(">>", "shiftRight", "org/perlonjava/runtime/operators/BitwiseOperators");
        put("~", "bitwiseNot", "org/perlonjava/runtime/operators/BitwiseOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("&.", "bitwiseAndDot", "org/perlonjava/runtime/operators/BitwiseOperators");
        put("|.", "bitwiseOrDot", "org/perlonjava/runtime/operators/BitwiseOperators");
        put("^.", "bitwiseXorDot", "org/perlonjava/runtime/operators/BitwiseOperators");
        put("~.", "bitwiseNotDot", "org/perlonjava/runtime/operators/BitwiseOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("binary&", "bitwiseAndBinary", "org/perlonjava/runtime/operators/BitwiseOperators");
        put("binary|", "bitwiseOrBinary", "org/perlonjava/runtime/operators/BitwiseOperators");
        put("binary^", "bitwiseXorBinary", "org/perlonjava/runtime/operators/BitwiseOperators");
        put("binary~", "bitwiseNotBinary", "org/perlonjava/runtime/operators/BitwiseOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("integerBitwiseNot", "integerBitwiseNot", "org/perlonjava/runtime/operators/BitwiseOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        // Scalar
        put("ord", "ord", "org/perlonjava/runtime/operators/ScalarOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("ordBytes", "ordBytes", "org/perlonjava/runtime/operators/ScalarOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("oct", "oct", "org/perlonjava/runtime/operators/ScalarOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("hex", "hex", "org/perlonjava/runtime/operators/ScalarOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        put("srand", "srand", "org/perlonjava/runtime/operators/Random", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("rand", "rand", "org/perlonjava/runtime/operators/Random", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        // Compare
        put("<", "lessThan", "org/perlonjava/runtime/operators/CompareOperators");
        put("<=", "lessThanOrEqual", "org/perlonjava/runtime/operators/CompareOperators");
        put(">", "greaterThan", "org/perlonjava/runtime/operators/CompareOperators");
        put(">=", "greaterThanOrEqual", "org/perlonjava/runtime/operators/CompareOperators");
        put("==", "equalTo", "org/perlonjava/runtime/operators/CompareOperators");
        put("!=", "notEqualTo", "org/perlonjava/runtime/operators/CompareOperators");
        put("<=>", "spaceship", "org/perlonjava/runtime/operators/CompareOperators");
        put("eq", "eq", "org/perlonjava/runtime/operators/CompareOperators");
        put("ne", "ne", "org/perlonjava/runtime/operators/CompareOperators");
        put("lt", "lt", "org/perlonjava/runtime/operators/CompareOperators");
        put("le", "le", "org/perlonjava/runtime/operators/CompareOperators");
        put("gt", "gt", "org/perlonjava/runtime/operators/CompareOperators");
        put("ge", "ge", "org/perlonjava/runtime/operators/CompareOperators");
        put("cmp", "cmp", "org/perlonjava/runtime/operators/CompareOperators");
        put("~~", "smartmatch", "org/perlonjava/runtime/operators/CompareOperators");

        // String
        put("chr", "chr", "org/perlonjava/runtime/operators/StringOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("chrBytes", "chrBytes", "org/perlonjava/runtime/operators/StringOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("length", "length", "org/perlonjava/runtime/operators/StringOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("lengthBytes", "lengthBytes", "org/perlonjava/runtime/operators/StringOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("quotemeta", "quotemeta", "org/perlonjava/runtime/operators/StringOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("fc", "fc", "org/perlonjava/runtime/operators/StringOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("lc", "lc", "org/perlonjava/runtime/operators/StringOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("lcfirst", "lcfirst", "org/perlonjava/runtime/operators/StringOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("uc", "uc", "org/perlonjava/runtime/operators/StringOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("ucfirst", "ucfirst", "org/perlonjava/runtime/operators/StringOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("rindex", "rindex", "org/perlonjava/runtime/operators/StringOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("index", "index", "org/perlonjava/runtime/operators/StringOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put(".", "stringConcat", "org/perlonjava/runtime/operators/StringOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        // Time
        put("time", "time", "org/perlonjava/runtime/operators/Time", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("times", "times", "org/perlonjava/runtime/operators/Time", "(I)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;");
        put("gmtime", "gmtime", "org/perlonjava/runtime/operators/Time", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;");
        put("localtime", "localtime", "org/perlonjava/runtime/operators/Time", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;");
        put("sleep", "sleep", "org/perlonjava/runtime/operators/Time", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("alarm", "alarm", "org/perlonjava/runtime/operators/Time", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        // File
        put("open", "open", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("readline", "readline", "org/perlonjava/runtime/operators/Readline", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;");
        put("close", "close", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("tell", "tell", "org/perlonjava/runtime/operators/IOOperator", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("fileno", "fileno", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("getc", "getc", "org/perlonjava/runtime/operators/IOOperator", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("binmode", "binmode", "org/perlonjava/runtime/operators/IOOperator", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("seek", "seek", "org/perlonjava/runtime/operators/IOOperator", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("select", "select", "org/perlonjava/runtime/operators/IOOperator", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("truncate", "truncate", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("sysread", "sysread", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("syswrite", "syswrite", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("write", "write", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("formline", "formline", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("sysopen", "sysopen", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("getc", "getc", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        // Socket I/O operators
        put("socket", "socket", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("bind", "bind", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("connect", "connect", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("listen", "listen", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("accept", "accept", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("getsockname", "getsockname", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("getpeername", "getpeername", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("send", "send", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("recv", "recv", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("shutdown", "shutdown", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("setsockopt", "setsockopt", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("getsockopt", "getsockopt", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("socketpair", "socketpair", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("pipe", "pipe", "org/perlonjava/runtime/operators/IOOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        // Directory
        put("rmdir", "rmdir", "org/perlonjava/runtime/operators/Directory", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("closedir", "closedir", "org/perlonjava/runtime/operators/Directory", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("rewinddir", "rewinddir", "org/perlonjava/runtime/operators/Directory", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("telldir", "telldir", "org/perlonjava/runtime/operators/Directory", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("readdir", "readdir", "org/perlonjava/runtime/operators/Directory", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;");
        put("mkdir", "mkdir", "org/perlonjava/runtime/operators/Directory", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("seekdir", "seekdir", "org/perlonjava/runtime/operators/Directory", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("chdir", "chdir", "org/perlonjava/runtime/operators/Directory", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("opendir", "opendir", "org/perlonjava/runtime/operators/Directory", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("glob", "evaluate", "org/perlonjava/runtime/operators/ScalarGlobOperator", "(ILorg/perlonjava/runtime/runtimetypes/RuntimeScalar;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;");

        // Modules
        put("doFile", "doFile", "org/perlonjava/runtime/operators/ModuleOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;");
        put("require", "require", "org/perlonjava/runtime/operators/ModuleOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        // User/Group Information Functions
        put("getlogin", "getlogin", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("getpwnam", "getpwnam", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;");
        put("getpwuid", "getpwuid", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;");
        put("getgrnam", "getgrnam", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;");
        put("getgrgid", "getgrgid", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;");
        put("getpwent", "getpwent", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;");
        put("getgrent", "getgrent", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;");
        put("setpwent", "setpwent", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("setgrent", "setgrent", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("endpwent", "endpwent", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("endgrent", "endgrent", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        // Network Information Functions
        put("gethostbyname", "gethostbyname", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;");
        put("gethostbyaddr", "gethostbyaddr", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;");
        put("getservbyname", "getservbyname", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;");
        put("getservbyport", "getservbyport", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;");
        put("getprotobyname", "getprotobyname", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;");
        put("getprotobynumber", "getprotobynumber", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;");

        // Network Enumeration Functions
        put("endhostent", "endhostent", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("endnetent", "endnetent", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("endprotoent", "endprotoent", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("endservent", "endservent", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("gethostent", "gethostent", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;");
        put("getnetbyaddr", "getnetbyaddr", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;");
        put("getnetbyname", "getnetbyname", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;");
        put("getnetent", "getnetent", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;");
        put("getprotoent", "getprotoent", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;");
        put("getservent", "getservent", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;");
        put("sethostent", "sethostent", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("setnetent", "setnetent", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("setprotoent", "setprotoent", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("setservent", "setservent", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        // System V IPC Functions
        put("msgctl", "msgctl", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("msgget", "msgget", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("msgrcv", "msgrcv", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("msgsnd", "msgsnd", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("semctl", "semctl", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("semget", "semget", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("semop", "semop", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("shmctl", "shmctl", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("shmget", "shmget", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("shmread", "shmread", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("shmwrite", "shmwrite", "org/perlonjava/nativ/ExtendedNativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        // Misc
        put("isa", "isa", "org/perlonjava/runtime/operators/ReferenceOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("bless", "bless", "org/perlonjava/runtime/operators/ReferenceOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("ref", "ref", "org/perlonjava/runtime/operators/ReferenceOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        put("caller", "caller", "org/perlonjava/runtime/runtimetypes/RuntimeCode", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;");
        put("reset", "reset", "org/perlonjava/runtime/operators/Operator", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;");
        put("warn", "warn", "org/perlonjava/runtime/operators/WarnDie", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Ljava/lang/String;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;");
        put("die", "die", "org/perlonjava/runtime/operators/WarnDie", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Ljava/lang/String;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;");
        put("exit", "exit", "org/perlonjava/runtime/operators/WarnDie", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("reverse", "reverse", "org/perlonjava/runtime/operators/Operator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;");
        put("crypt", "crypt", "org/perlonjava/runtime/operators/Crypt", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("unlink", "unlink", "org/perlonjava/runtime/operators/UnlinkOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;");
        put("stat", "stat", "org/perlonjava/runtime/operators/Stat", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;");
        put("lstat", "lstat", "org/perlonjava/runtime/operators/Stat", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;");
        put("vec", "vec", "org/perlonjava/runtime/operators/Vec", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("chmod", "chmod", "org/perlonjava/runtime/operators/Operator", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("link", "link", "org/perlonjava/nativ/NativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("symlink", "symlink", "org/perlonjava/nativ/NativeUtils", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        put("unpack", "unpack", "org/perlonjava/runtime/operators/Unpack", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;");
        put("pack", "pack", "org/perlonjava/runtime/operators/Pack", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("read", "read", "org/perlonjava/runtime/operators/Readline", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        put("x", "repeat", "org/perlonjava/runtime/operators/Operator", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("join", "join", "org/perlonjava/runtime/operators/StringOperators", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("split", "split", "org/perlonjava/runtime/operators/Operator", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeList;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;");

        put("..", "createRange", "org/perlonjava/runtime/runtimetypes/PerlRange", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/PerlRange;");

        put("substr", "substr", "org/perlonjava/runtime/operators/Operator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("sprintf", "sprintf", "org/perlonjava/runtime/operators/SprintfOperator", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        put("systemCommand", "systemCommand", "org/perlonjava/runtime/operators/SystemOperator", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;");
        put("system", "system", "org/perlonjava/runtime/operators/SystemOperator", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;ZI)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("exec", "exec", "org/perlonjava/runtime/operators/SystemOperator", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;ZI)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("fork", "fork", "org/perlonjava/runtime/operators/SystemOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("kill", "kill", "org/perlonjava/runtime/operators/KillOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("umask", "umask", "org/perlonjava/runtime/operators/UmaskOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("wait", "waitForChild", "org/perlonjava/runtime/operators/WaitpidOperator", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("waitpid", "waitpid", "org/perlonjava/runtime/operators/WaitpidOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("utime", "utime", "org/perlonjava/runtime/operators/UtimeOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("chown", "chown", "org/perlonjava/runtime/operators/ChownOperator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("readlink", "readlink", "org/perlonjava/runtime/operators/Operator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("rename", "rename", "org/perlonjava/runtime/operators/Operator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        put("$#", "indexLastElem", "org/perlonjava/runtime/runtimetypes/RuntimeArray", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("pop", "pop", "org/perlonjava/runtime/runtimetypes/RuntimeArray", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("shift", "shift", "org/perlonjava/runtime/runtimetypes/RuntimeArray", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("splice", "splice", "org/perlonjava/runtime/operators/Operator", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;");
        put("push", "push", "org/perlonjava/runtime/runtimetypes/RuntimeArray", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("unshift", "unshift", "org/perlonjava/runtime/runtimetypes/RuntimeArray", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        put("undef", "undef", "org/perlonjava/runtime/operators/Operator", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("wantarray", "wantarray", "org/perlonjava/runtime/operators/Operator", "(I)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        // Process-related operators
        put("getppid", "getppid", "org/perlonjava/runtime/operators/Operator", "(I)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("getpgrp", "getpgrp", "org/perlonjava/runtime/operators/Operator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("setpgrp", "setpgrp", "org/perlonjava/runtime/operators/Operator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("getpriority", "getpriority", "org/perlonjava/runtime/operators/Operator", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        put("prototype", "prototype", "org/perlonjava/runtime/runtimetypes/RuntimeCode", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        // Tie
        put("tie", "tie", "org/perlonjava/runtime/operators/TieOperators", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("untie", "untie", "org/perlonjava/runtime/operators/TieOperators", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");
        put("tied", "tied", "org/perlonjava/runtime/operators/TieOperators", "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");

        // List operators
        put("map", "map",
                "org/perlonjava/runtime/operators/ListOperators",
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;");
        put("grep", "grep",
                "org/perlonjava/runtime/operators/ListOperators",
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;");
        put("sort", "sort",
                "org/perlonjava/runtime/operators/ListOperators",
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;");
        put("all", "all",
                "org/perlonjava/runtime/operators/ListOperators",
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;");
        put("any", "any",
                "org/perlonjava/runtime/operators/ListOperators",
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;");

        operatorHandlers.put("scalar",
                new OperatorHandler("org/perlonjava/runtime/runtimetypes/RuntimeBase",
                        "scalar",
                        Opcodes.INVOKEVIRTUAL,
                        "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;"));
        operatorHandlers.put("each",
                new OperatorHandler("org/perlonjava/runtime/runtimetypes/RuntimeBase",
                        "each",
                        Opcodes.INVOKEVIRTUAL,
                        "(I)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;"));
        operatorHandlers.put("keys",
                new OperatorHandler("org/perlonjava/runtime/runtimetypes/RuntimeBase",
                        "keys",
                        Opcodes.INVOKEVIRTUAL,
                        "(I)Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;"));
        operatorHandlers.put("values",
                new OperatorHandler("org/perlonjava/runtime/runtimetypes/RuntimeBase",
                        "values",
                        Opcodes.INVOKEVIRTUAL,
                        "()Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;"));

        // Format-related operators
        operatorHandlers.put("write",
                new OperatorHandler("org/perlonjava/runtime/operators/IOOperator",
                        "write",
                        Opcodes.INVOKESTATIC,
                        "(I[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;"));

    }

    /**
     * Constructs an OperatorHandler with the specified class name, method name, method type, and descriptor.
     *
     * @param className  The name of the class containing the method.
     * @param methodName The name of the method to invoke.
     * @param methodType The type of method invocation (e.g., INVOKESTATIC).
     * @param descriptor The method descriptor indicating the method signature.
     */
    public OperatorHandler {
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
                        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;"));
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
    @Override
    public String className() {
        return className;
    }

    /**
     * Gets the method name associated with the operator.
     *
     * @return The method name.
     */
    @Override
    public String methodName() {
        return methodName;
    }

    /**
     * Gets the method type (e.g., INVOKESTATIC) for the operator.
     *
     * @return The method type.
     */
    @Override
    public int methodType() {
        return methodType;
    }

    /**
     * Gets the method descriptor indicating the method signature.
     *
     * @return The method descriptor.
     */
    @Override
    public String descriptor() {
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
        return descriptor.replace("Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)", "I)");
    }

    /**
     * Gets the return type descriptor from this handler's method descriptor.
     *
     * @return The return type class name with semicolon (e.g., "RuntimeScalar;"),
     * or null if return type cannot be determined
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

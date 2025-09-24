package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;
import java.math.BigInteger;
import java.math.BigDecimal;

import static org.perlonjava.runtime.RuntimeScalarCache.*;

/**
 * Math::BigInt module implementation for PerlonJava.
 * This class provides low-level BigInteger operations for the Perl Math::BigInt module.
 * The high-level API and exports are handled in Math/BigInt.pm
 */
public class MathBigInt extends PerlModuleBase {

    /**
     * Constructor initializes the Math::BigInt module.
     */
    public MathBigInt() {
        super("Math::BigInt", false);
    }

    /**
     * Initializes and registers all Math::BigInt methods.
     */
    public static void initialize() {
        MathBigInt mathBigInt = new MathBigInt();
        try {
            // Register core BigInteger operations
            mathBigInt.registerMethod("_new", null);
            mathBigInt.registerMethod("_add", null);
            mathBigInt.registerMethod("_sub", null);
            mathBigInt.registerMethod("_mul", null);
            mathBigInt.registerMethod("_div", null);
            mathBigInt.registerMethod("_pow", null);
            mathBigInt.registerMethod("_cmp", null);
            mathBigInt.registerMethod("_str", null);
            mathBigInt.registerMethod("_from_string", null);
            // High-level operations that handle sign automatically
            mathBigInt.registerMethod("_badd", null);
            mathBigInt.registerMethod("_bsub", null);
            mathBigInt.registerMethod("_bmul", null);
            mathBigInt.registerMethod("_bdiv", null);
            mathBigInt.registerMethod("_bpow", null);
            // Utility methods
            mathBigInt.registerMethod("_sign", null);
            mathBigInt.registerMethod("_is_zero", null);
            mathBigInt.registerMethod("_is_positive", null);
            mathBigInt.registerMethod("_is_negative", null);
            mathBigInt.registerMethod("_is_odd", null);
            mathBigInt.registerMethod("_is_even", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Math::BigInt method: " + e.getMessage());
        }
    }

    /**
     * Create a new BigInteger from string input.
     * Handles decimal, hex (0x), octal (0o), binary (0b), and scientific notation.
     */
    public static RuntimeList _new(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return new RuntimeScalar(BigInteger.ZERO).getList();
        }

        String input = args.get(1).toString().trim();
        BigInteger value;

        try {
            if (input.startsWith("0x") || input.startsWith("-0x")) {
                // Hexadecimal
                boolean negative = input.startsWith("-");
                String hexPart = input.replaceFirst("^[+-]?0x", "");
                value = new BigInteger(hexPart, 16);
                if (negative) value = value.negate();
            } else if (input.startsWith("0o") || input.startsWith("-0o")) {
                // Octal
                boolean negative = input.startsWith("-");
                String octPart = input.replaceFirst("^[+-]?0o", "");
                value = new BigInteger(octPart, 8);
                if (negative) value = value.negate();
            } else if (input.startsWith("0b") || input.startsWith("-0b")) {
                // Binary
                boolean negative = input.startsWith("-");
                String binPart = input.replaceFirst("^[+-]?0b", "");
                value = new BigInteger(binPart, 2);
                if (negative) value = value.negate();
            } else if (input.contains("e") || input.contains("E")) {
                // Scientific notation - convert via BigDecimal
                BigDecimal bd = new BigDecimal(input);
                value = bd.toBigInteger();
            } else {
                // Regular decimal
                value = new BigInteger(input);
            }
        } catch (NumberFormatException e) {
            value = BigInteger.ZERO;
        }

        return new RuntimeScalar(value).getList();
    }

    /**
     * Create BigInteger from string (same as _new but different name for clarity).
     */
    public static RuntimeList _from_string(RuntimeArray args, int ctx) {
        return _new(args, ctx);
    }

    /**
     * Addition: _add(x, y) returns x + y
     */
    public static RuntimeList _add(RuntimeArray args, int ctx) {
        BigInteger x = (BigInteger) args.get(1).value;
        BigInteger y = (BigInteger) args.get(2).value;
        BigInteger result = x.add(y);
        return new RuntimeScalar(result).getList();
    }

    /**
     * Subtraction: _sub(x, y) returns x - y
     */
    public static RuntimeList _sub(RuntimeArray args, int ctx) {
        BigInteger x = (BigInteger) args.get(1).value;
        BigInteger y = (BigInteger) args.get(2).value;
        BigInteger result = x.subtract(y);
        return new RuntimeScalar(result).getList();
    }

    /**
     * Multiplication: _mul(x, y) returns x * y
     */
    public static RuntimeList _mul(RuntimeArray args, int ctx) {
        BigInteger x = (BigInteger) args.get(1).value;
        BigInteger y = (BigInteger) args.get(2).value;
        BigInteger result = x.multiply(y);
        return new RuntimeScalar(result).getList();
    }

    /**
     * Division: _div(x, y) returns x / y
     */
    public static RuntimeList _div(RuntimeArray args, int ctx) {
        BigInteger x = (BigInteger) args.get(1).value;
        BigInteger y = (BigInteger) args.get(2).value;
        if (y.equals(BigInteger.ZERO)) {
            throw new ArithmeticException("Division by zero");
        }
        BigInteger result = x.divide(y);
        return new RuntimeScalar(result).getList();
    }

    /**
     * Power: _pow(x, y) returns x ** y
     */
    public static RuntimeList _pow(RuntimeArray args, int ctx) {
        BigInteger x = (BigInteger) args.get(1).value;
        BigInteger y = (BigInteger) args.get(2).value;
        
        // BigInteger.pow() only accepts int, so we need to check range
        if (y.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new ArithmeticException("Exponent too large");
        }
        if (y.signum() < 0) {
            throw new ArithmeticException("Negative exponent not supported for integers");
        }
        
        BigInteger result = x.pow(y.intValue());
        return new RuntimeScalar(result).getList();
    }

    /**
     * Comparison: _cmp(x, y) returns -1, 0, or 1
     */
    public static RuntimeList _cmp(RuntimeArray args, int ctx) {
        BigInteger x = (BigInteger) args.get(1).value;
        BigInteger y = (BigInteger) args.get(2).value;
        int result = x.compareTo(y);
        return new RuntimeScalar(result).getList();
    }

    /**
     * String conversion: _str(x) returns string representation
     */
    public static RuntimeList _str(RuntimeArray args, int ctx) {
        BigInteger x = (BigInteger) args.get(1).value;
        return new RuntimeScalar(x.toString()).getList();
    }

    // High-level operations that handle conversion and return BigInteger
    
    /**
     * High-level addition: _badd(x, y) - converts y if needed, returns x + y
     */
    public static RuntimeList _badd(RuntimeArray args, int ctx) {
        BigInteger x = (BigInteger) args.get(1).value;
        BigInteger y = convertToBigInteger(args.get(2));
        BigInteger result = x.add(y);
        return new RuntimeScalar(result).getList();
    }

    /**
     * High-level subtraction: _bsub(x, y) - converts y if needed, returns x - y
     */
    public static RuntimeList _bsub(RuntimeArray args, int ctx) {
        BigInteger x = (BigInteger) args.get(1).value;
        BigInteger y = convertToBigInteger(args.get(2));
        BigInteger result = x.subtract(y);
        return new RuntimeScalar(result).getList();
    }

    /**
     * High-level multiplication: _bmul(x, y) - converts y if needed, returns x * y
     */
    public static RuntimeList _bmul(RuntimeArray args, int ctx) {
        BigInteger x = (BigInteger) args.get(1).value;
        BigInteger y = convertToBigInteger(args.get(2));
        BigInteger result = x.multiply(y);
        return new RuntimeScalar(result).getList();
    }

    /**
     * High-level division: _bdiv(x, y) - converts y if needed, returns x / y
     */
    public static RuntimeList _bdiv(RuntimeArray args, int ctx) {
        BigInteger x = (BigInteger) args.get(1).value;
        BigInteger y = convertToBigInteger(args.get(2));
        if (y.equals(BigInteger.ZERO)) {
            throw new ArithmeticException("Division by zero");
        }
        BigInteger result = x.divide(y);
        return new RuntimeScalar(result).getList();
    }

    /**
     * High-level power: _bpow(x, y) - converts y if needed, returns x ** y
     */
    public static RuntimeList _bpow(RuntimeArray args, int ctx) {
        BigInteger x = (BigInteger) args.get(1).value;
        BigInteger y = convertToBigInteger(args.get(2));
        
        if (y.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new ArithmeticException("Exponent too large");
        }
        if (y.signum() < 0) {
            throw new ArithmeticException("Negative exponent not supported for integers");
        }
        
        BigInteger result = x.pow(y.intValue());
        return new RuntimeScalar(result).getList();
    }

    // Utility methods for efficient sign and property detection

    /**
     * Get sign: _sign(x) returns "+", "-", or "0"
     */
    public static RuntimeList _sign(RuntimeArray args, int ctx) {
        BigInteger x = (BigInteger) args.get(1).value;
        int signum = x.signum();
        String sign = (signum > 0) ? "+" : (signum < 0) ? "-" : "0";
        return new RuntimeScalar(sign).getList();
    }

    /**
     * Check if zero: _is_zero(x)
     */
    public static RuntimeList _is_zero(RuntimeArray args, int ctx) {
        BigInteger x = (BigInteger) args.get(1).value;
        return new RuntimeScalar(x.equals(BigInteger.ZERO)).getList();
    }

    /**
     * Check if positive: _is_positive(x)
     */
    public static RuntimeList _is_positive(RuntimeArray args, int ctx) {
        BigInteger x = (BigInteger) args.get(1).value;
        return new RuntimeScalar(x.signum() > 0).getList();
    }

    /**
     * Check if negative: _is_negative(x)
     */
    public static RuntimeList _is_negative(RuntimeArray args, int ctx) {
        BigInteger x = (BigInteger) args.get(1).value;
        return new RuntimeScalar(x.signum() < 0).getList();
    }

    /**
     * Check if odd: _is_odd(x)
     */
    public static RuntimeList _is_odd(RuntimeArray args, int ctx) {
        BigInteger x = (BigInteger) args.get(1).value;
        return new RuntimeScalar(!x.remainder(BigInteger.valueOf(2)).equals(BigInteger.ZERO)).getList();
    }

    /**
     * Check if even: _is_even(x)
     */
    public static RuntimeList _is_even(RuntimeArray args, int ctx) {
        BigInteger x = (BigInteger) args.get(1).value;
        return new RuntimeScalar(x.remainder(BigInteger.valueOf(2)).equals(BigInteger.ZERO)).getList();
    }

    /**
     * Helper method to convert RuntimeScalar to BigInteger
     */
    private static BigInteger convertToBigInteger(RuntimeScalar scalar) {
        if (scalar.value instanceof BigInteger) {
            return (BigInteger) scalar.value;
        } else {
            String str = scalar.toString().trim();
            try {
                if (str.contains("e") || str.contains("E")) {
                    BigDecimal bd = new BigDecimal(str);
                    return bd.toBigInteger();
                } else {
                    return new BigInteger(str);
                }
            } catch (NumberFormatException e) {
                return BigInteger.ZERO;
            }
        }
    }
}

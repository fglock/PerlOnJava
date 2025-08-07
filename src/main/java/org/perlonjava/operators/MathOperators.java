package org.perlonjava.operators;

import org.perlonjava.parser.NumberParser;
import org.perlonjava.runtime.*;

import static org.perlonjava.runtime.RuntimeScalarCache.*;
import static org.perlonjava.runtime.RuntimeScalarType.*;

/**
 * Provides basic arithmetic operations for RuntimeScalar objects.
 * This class includes methods for addition, subtraction, multiplication,
 * division, modulus, and various mathematical functions.
 */
public class MathOperators {

    /**
     * Adds an integer to a RuntimeScalar and returns the result.
     *
     * @param arg1 The RuntimeScalar to add to.
     * @param arg2 The integer value to add.
     * @return A new RuntimeScalar representing the sum.
     */
    public static RuntimeScalar add(RuntimeScalar arg1, int arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = arg1.blessedId();
        if (blessId != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, new RuntimeScalar(arg2), blessId, 0, "(+", "+");
            if (result != null) return result;
        }

        // Convert string type to number if necessary
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = NumberParser.parseNumber(arg1);
        }
        // Perform addition based on the type of RuntimeScalar
        if (arg1.type == RuntimeScalarType.DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() + arg2);
        } else {
            return getScalarInt(arg1.getLong() + arg2);
        }
    }

    /**
     * Adds two RuntimeScalar objects and returns the result.
     *
     * @param arg1 The first RuntimeScalar to add.
     * @param arg2 The second RuntimeScalar to add.
     * @return A new RuntimeScalar representing the sum.
     */
    public static RuntimeScalar add(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = arg1.blessedId();
        int blessId2 = arg2.blessedId();
        if (blessId != 0 || blessId2 != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(+", "+");
            if (result != null) return result;
        }

        // Convert string type to number if necessary
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = NumberParser.parseNumber(arg1);
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = NumberParser.parseNumber(arg2);
        }
        // Perform addition based on the type of RuntimeScalar
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() + arg2.getDouble());
        } else {
            return getScalarInt(arg1.getLong() + arg2.getLong());
        }
    }

    /**
     * Subtracts an integer from a RuntimeScalar and returns the result.
     *
     * @param arg1 The RuntimeScalar to subtract from.
     * @param arg2 The integer value to subtract.
     * @return A new RuntimeScalar representing the difference.
     */
    public static RuntimeScalar subtract(RuntimeScalar arg1, int arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = arg1.blessedId();
        if (blessId != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, new RuntimeScalar(arg2), blessId, 0, "(-", "-");
            if (result != null) return result;
        }

        // Convert string type to number if necessary
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = NumberParser.parseNumber(arg1);
        }
        // Perform subtraction based on the type of RuntimeScalar
        if (arg1.type == RuntimeScalarType.DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() - arg2);
        } else {
            return getScalarInt(arg1.getLong() - arg2);
        }
    }

    /**
     * Subtracts one RuntimeScalar from another and returns the result.
     *
     * @param arg1 The RuntimeScalar to subtract from.
     * @param arg2 The RuntimeScalar to subtract.
     * @return A new RuntimeScalar representing the difference.
     */
    public static RuntimeScalar subtract(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = arg1.blessedId();
        int blessId2 = arg2.blessedId();
        if (blessId != 0 || blessId2 != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(-", "-");
            if (result != null) return result;
        }

        // Convert string type to number if necessary
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = NumberParser.parseNumber(arg1);
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = NumberParser.parseNumber(arg2);
        }
        // Perform subtraction based on the type of RuntimeScalar
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() - arg2.getDouble());
        } else {
            return getScalarInt(arg1.getLong() - arg2.getLong());
        }
    }

    /**
     * Multiplies two RuntimeScalar objects and returns the result.
     *
     * @param arg1 The first RuntimeScalar to multiply.
     * @param arg2 The second RuntimeScalar to multiply.
     * @return A new RuntimeScalar representing the product.
     */
    public static RuntimeScalar multiply(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = arg1.blessedId();
        int blessId2 = arg2.blessedId();
        if (blessId != 0 || blessId2 != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(*", "*");
            if (result != null) return result;
        }

        // Convert string type to number if necessary
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = NumberParser.parseNumber(arg1);
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = NumberParser.parseNumber(arg2);
        }
        // Perform multiplication based on the type of RuntimeScalar
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() * arg2.getDouble());
        } else {
            return getScalarInt((long) arg1.getInt() * (long) arg2.getInt());
        }
    }

    /**
     * Divides one RuntimeScalar by another and returns the result.
     *
     * @param arg1 The RuntimeScalar to divide.
     * @param arg2 The RuntimeScalar to divide by.
     * @return A new RuntimeScalar representing the quotient.
     * @throws PerlCompilerException if division by zero occurs.
     */
    public static RuntimeScalar divide(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = arg1.blessedId();
        if (blessId != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, 0, "(/", "/");
            if (result != null) return result;
        }

        // Convert string type to number if necessary
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = NumberParser.parseNumber(arg1);
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = NumberParser.parseNumber(arg2);
        }
        double divisor = arg2.getDouble();
        // Check for division by zero
        if (divisor == 0.0) {
            throw new PerlCompilerException("Illegal division by zero");
        }
        // Perform division
        double result = arg1.getDouble() / divisor;

        // Fix negative zero to positive zero
        if (result == 0.0 && Double.doubleToRawLongBits(result) == Double.doubleToRawLongBits(-0.0)) {
            result = 0.0;
        }

        return new RuntimeScalar(result);
    }

    /**
     * Computes the modulus of one RuntimeScalar by another and returns the result.
     *
     * @param arg1 The RuntimeScalar to divide.
     * @param arg2 The RuntimeScalar to divide by.
     * @return A new RuntimeScalar representing the modulus.
     */
    public static RuntimeScalar modulus(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = arg1.blessedId();
        if (blessId != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, 0, "(%", "%");
            if (result != null) return result;
        }

        if (arg1.type == DOUBLE || arg2.type == DOUBLE) {
            // Use double arithmetic when either argument is a double
            double dividend = arg1.getDouble();
            double divisor = arg2.getDouble();

            // Handle division by zero
            if (divisor == 0.0) {
                throw new PerlCompilerException("Division by zero in modulus operation");
            }

            // Calculate modulus using double precision
            double result = truncate(dividend) % truncate(divisor);

            // Adjust result for Perl-style modulus behavior
            // In Perl, the result has the same sign as the divisor
            if (result != 0.0 && ((divisor > 0.0 && result < 0.0) || (divisor < 0.0 && result > 0.0))) {
                result += divisor;
            }
            return new RuntimeScalar(result);
        }
        int divisor = arg2.getInt();
        int result = arg1.getInt() % divisor;
        // Adjust result for negative modulus
        if (result != 0.0 && ((divisor > 0.0 && result < 0.0) || (divisor < 0.0 && result > 0.0))) {
            result += divisor;
        }
        return new RuntimeScalar(result);
    }

    private static double truncate(double value) {
        double result = (value >= 0) ? Math.floor(value) : Math.ceil(value);
        // Fix negative zero to positive zero
        if (result == 0.0 && Double.doubleToRawLongBits(result) == Double.doubleToRawLongBits(-0.0)) {
            result = 0.0;
        }
        return result;
    }

    /**
     * Computes the natural logarithm of a RuntimeScalar.
     *
     * @param runtimeScalar The RuntimeScalar to compute the logarithm for.
     * @return A new RuntimeScalar representing the natural logarithm.
     */
    public static RuntimeScalar log(RuntimeScalar runtimeScalar) {
        // Check if object is eligible for overloading
        int blessId = runtimeScalar.blessedId();
        if (blessId != 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(runtimeScalar, blessId, "(log", "log", MathOperators::log);
            if (result != null) return result;
        }

        double v = runtimeScalar.getDouble();
        if (v == 0) {
            throw new PerlCompilerException("Can't take log of 0");
        }
        return new RuntimeScalar(Math.log(v));
    }

    /**
     * Computes the square root of a RuntimeScalar.
     *
     * @param runtimeScalar The RuntimeScalar to compute the square root for.
     * @return A new RuntimeScalar representing the square root.
     */
    public static RuntimeScalar sqrt(RuntimeScalar runtimeScalar) {
        // Check if object is eligible for overloading
        int blessId = runtimeScalar.blessedId();
        if (blessId != 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(runtimeScalar, blessId, "(sqrt", "sqrt", MathOperators::sqrt);
            if (result != null) return result;
        }

        return new RuntimeScalar(Math.sqrt(runtimeScalar.getDouble()));
    }

    /**
     * Computes the cosine of a RuntimeScalar.
     *
     * @param runtimeScalar The RuntimeScalar to compute the cosine for.
     * @return A new RuntimeScalar representing the cosine.
     */
    public static RuntimeScalar cos(RuntimeScalar runtimeScalar) {
        // Check if object is eligible for overloading
        int blessId = runtimeScalar.blessedId();
        if (blessId != 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(runtimeScalar, blessId, "(cos", "cos", MathOperators::cos);
            if (result != null) return result;
        }

        return new RuntimeScalar(Math.cos(runtimeScalar.getDouble()));
    }

    /**
     * Computes the sine of a RuntimeScalar.
     *
     * @param runtimeScalar The RuntimeScalar to compute the sine for.
     * @return A new RuntimeScalar representing the sine.
     */
    public static RuntimeScalar sin(RuntimeScalar runtimeScalar) {
        // Check if object is eligible for overloading
        int blessId = runtimeScalar.blessedId();
        if (blessId != 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(runtimeScalar, blessId, "(sin", "sin", MathOperators::sin);
            if (result != null) return result;
        }

        return new RuntimeScalar(Math.sin(runtimeScalar.getDouble()));
    }

    /**
     * Computes the exponential of a RuntimeScalar.
     *
     * @param runtimeScalar The RuntimeScalar to compute the exponential for.
     * @return A new RuntimeScalar representing the exponential.
     */
    public static RuntimeScalar exp(RuntimeScalar runtimeScalar) {
        // Check if object is eligible for overloading
        int blessId = runtimeScalar.blessedId();
        if (blessId != 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(runtimeScalar, blessId, "(exp", "exp", MathOperators::exp);
            if (result != null) return result;
        }

        return new RuntimeScalar(Math.exp(runtimeScalar.getDouble()));
    }

    /**
     * Raises a RuntimeScalar to the power of another RuntimeScalar.
     *
     * @param arg1 The base RuntimeScalar.
     * @param arg2 The exponent RuntimeScalar.
     * @return A new RuntimeScalar representing the power.
     */
    public static RuntimeScalar pow(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = arg1.blessedId();
        if (blessId != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, new RuntimeScalar(arg2), blessId, 0, "(**", "**");
            if (result != null) return result;
        }

        return new RuntimeScalar(Math.pow(arg1.getDouble(), arg2.getDouble()));
    }

    /**
     * Computes the angle theta (in radians) from the conversion of rectangular
     * coordinates (x, y) to polar coordinates (r, theta). This method returns
     * the angle whose tangent is the quotient of two specified numbers,
     * effectively calculating the arc-tangent of y/x.
     *
     * @param arg1 The y-coordinate as a RuntimeScalar.
     * @param arg2 The x-coordinate as a RuntimeScalar.
     * @return A new RuntimeScalar representing the angle theta in radians.
     */
    public static RuntimeScalar atan2(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = arg1.blessedId();
        if (blessId != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, new RuntimeScalar(arg2), blessId, 0, "(atan2", "atan2");
            if (result != null) return result;
        }

        return new RuntimeScalar(Math.atan2(arg1.getDouble(), arg2.getDouble()));
    }

    /**
     * Computes the absolute value of a RuntimeScalar.
     *
     * @param runtimeScalar The RuntimeScalar to compute the absolute value for.
     * @return A new RuntimeScalar representing the absolute value.
     */
    public static RuntimeScalar abs(RuntimeScalar runtimeScalar) {
        // Check if object is eligible for overloading
        int blessId = runtimeScalar.blessedId();
        if (blessId != 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(runtimeScalar, blessId, "(abs", "abs", MathOperators::abs);
            if (result != null) return result;
        }

        RuntimeScalar arg1 = runtimeScalar;
        // Convert string type to number if necessary
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = NumberParser.parseNumber(arg1);
        }
        // Compute absolute value based on the type of RuntimeScalar
        if (arg1.type == RuntimeScalarType.DOUBLE) {
            return new RuntimeScalar(Math.abs(arg1.getDouble()));
        } else {
            return new RuntimeScalar(Math.abs(arg1.getInt()));
        }
    }

    public static RuntimeScalar unaryMinus(RuntimeScalar runtimeScalar) {
        // Check if object is eligible for overloading
        int blessId = runtimeScalar.blessedId();
        if (blessId != 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(runtimeScalar, blessId, "(neg", "neg", MathOperators::unaryMinus);
            if (result != null) return result;
        }

        if (runtimeScalar.type == RuntimeScalarType.STRING) {
            String input = runtimeScalar.toString();
            if (input.length() < 2) {
                if (input.isEmpty()) {
                    return getScalarInt(0);
                }
                if (input.equals("-")) {
                    return new RuntimeScalar("+");
                }
                if (input.equals("+")) {
                    return new RuntimeScalar("-");
                }
            }
            if (input.matches("^[-+]?[_A-Za-z].*")) {
                if (input.startsWith("-")) {
                    // Handle case where string starts with "-"
                    return new RuntimeScalar("+" + input.substring(1));
                } else if (input.startsWith("+")) {
                    // Handle case where string starts with "+"
                    return new RuntimeScalar("-" + input.substring(1));
                } else {
                    return new RuntimeScalar("-" + input);
                }
            }
        }
        return subtract(getScalarInt(0), runtimeScalar);
    }

    public static RuntimeScalar integer(RuntimeScalar runtimeScalar) {
        // Check if object is eligible for overloading
        int blessId = runtimeScalar.blessedId();
        if (blessId != 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(runtimeScalar, blessId, "(int", "int", MathOperators::integer);
            if (result != null) return result;
        }

        // Convert string type to number if necessary
        if (runtimeScalar.type == RuntimeScalarType.STRING) {
            runtimeScalar = NumberParser.parseNumber(runtimeScalar);
        }

        // Already an integer
        if (runtimeScalar.type == RuntimeScalarType.INTEGER) {
            return runtimeScalar;
        }

        // Handle DOUBLE type
        double value = runtimeScalar.getDouble();

        // Check for infinity and NaN values
        if (Double.isInfinite(value) || Double.isNaN(value)) {
            return new RuntimeScalar(value);  // Return infinity or NaN as-is
        }

        // Use truncate to get integer part (truncates towards zero)
        return new RuntimeScalar(truncate(value));
    }


    public static RuntimeScalar not(RuntimeScalar runtimeScalar) {
        return switch (runtimeScalar.type) {
            case INTEGER -> getScalarBoolean((int) runtimeScalar.value == 0);
            case DOUBLE -> getScalarBoolean((double) runtimeScalar.value == 0.0);
            case STRING -> {
                String s = (String) runtimeScalar.value;
                yield getScalarBoolean(s.isEmpty() || s.equals("0"));
            }
            case UNDEF -> scalarTrue;
            case VSTRING -> scalarFalse;
            case BOOLEAN -> getScalarBoolean(!(boolean) runtimeScalar.value);
            case GLOB -> scalarFalse;
            case REGEX -> scalarFalse;
            case JAVAOBJECT -> scalarFalse;
            case TIED_SCALAR -> not(runtimeScalar.tiedFetch());
            default -> Overload.bool_not(runtimeScalar);
        };
    }
}
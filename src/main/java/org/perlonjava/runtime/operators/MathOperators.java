package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.runtimetypes.*;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;

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
        int blessId = blessedId(arg1);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, new RuntimeScalar(arg2), blessId, 0, "(+", "+");
            if (result != null) return result;
        }

        // Convert string type to number if necessary
        arg1 = arg1.getNumber("addition (+)");
        // Perform addition based on the type of RuntimeScalar
        if (arg1.type == DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() + arg2);
        } else {
            long a = arg1.getLong();
            try {
                // Note: do not cache, because the result of addition is mutable - t/comp/fold.t
                return new RuntimeScalar(Math.addExact(a, arg2));
            } catch (ArithmeticException ignored) {
                // Overflow: promote to double (Perl NV semantics)
                return new RuntimeScalar((double) a + (double) arg2);
            }
        }
    }

    /**
     * Adds an integer to a RuntimeScalar with uninitialized value warnings.
     * Called when 'use warnings "uninitialized"' is in effect.
     *
     * @param arg1 The RuntimeScalar to add to.
     * @param arg2 The integer value to add.
     * @return A new RuntimeScalar representing the sum.
     */
    public static RuntimeScalar addWarn(RuntimeScalar arg1, int arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, new RuntimeScalar(arg2), blessId, 0, "(+", "+");
            if (result != null) return result;
        }

        // Convert to number with warning for uninitialized values
        arg1 = arg1.getNumberWarn("addition (+)");

        // Perform addition based on the type of RuntimeScalar
        if (arg1.type == DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() + arg2);
        } else {
            long a = arg1.getLong();
            try {
                // Note: do not cache, because the result of addition is mutable - t/comp/fold.t
                return new RuntimeScalar(Math.addExact(a, arg2));
            } catch (ArithmeticException ignored) {
                // Overflow: promote to double (Perl NV semantics)
                return new RuntimeScalar((double) a + (double) arg2);
            }
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
        // Fast path: both INTEGER - skip blessedId check, getNumber(), type checks
        if (arg1.type == INTEGER && arg2.type == INTEGER) {
            int a = (int) arg1.value;
            int b = (int) arg2.value;
            try {
                return getScalarInt(Math.addExact(a, b));
            } catch (ArithmeticException ignored) {
                return new RuntimeScalar((double) a + (double) b);
            }
        }

        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(+", "+");
            if (result != null) return result;
        }

        // Convert string type to number if necessary
        arg1 = arg1.getNumber("addition (+)");
        arg2 = arg2.getNumber("addition (+)");
        // Perform addition based on the type of RuntimeScalar
        if (arg1.type == DOUBLE || arg2.type == DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() + arg2.getDouble());
        } else {
            long a = arg1.getLong();
            long b = arg2.getLong();
            try {
                return getScalarInt(Math.addExact(a, b));
            } catch (ArithmeticException ignored) {
                // Overflow: promote to double (Perl NV semantics)
                return new RuntimeScalar((double) a + (double) b);
            }
        }
    }

    /**
     * Adds two RuntimeScalar objects with uninitialized value warnings.
     * Called when 'use warnings "uninitialized"' is in effect.
     *
     * @param arg1 The first RuntimeScalar to add.
     * @param arg2 The second RuntimeScalar to add.
     * @return A new RuntimeScalar representing the sum.
     */
    public static RuntimeScalar addWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Fast path: both INTEGER - skip blessedId check, getNumber(), type checks
        if (arg1.type == INTEGER && arg2.type == INTEGER) {
            int a = (int) arg1.value;
            int b = (int) arg2.value;
            try {
                return getScalarInt(Math.addExact(a, b));
            } catch (ArithmeticException ignored) {
                return new RuntimeScalar((double) a + (double) b);
            }
        }

        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(+", "+");
            if (result != null) return result;
        }

        // Convert to number with warning for uninitialized values
        arg1 = arg1.getNumberWarn("addition (+)");
        arg2 = arg2.getNumberWarn("addition (+)");

        // Perform addition based on the type of RuntimeScalar
        if (arg1.type == DOUBLE || arg2.type == DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() + arg2.getDouble());
        } else {
            long a = arg1.getLong();
            long b = arg2.getLong();
            try {
                return getScalarInt(Math.addExact(a, b));
            } catch (ArithmeticException ignored) {
                // Overflow: promote to double (Perl NV semantics)
                return new RuntimeScalar((double) a + (double) b);
            }
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
        int blessId = blessedId(arg1);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, new RuntimeScalar(arg2), blessId, 0, "(-", "-");
            if (result != null) return result;
        }

        // Convert string type to number if necessary
        arg1 = arg1.getNumber("subtraction (-)");
        // Perform subtraction based on the type of RuntimeScalar
        if (arg1.type == DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() - arg2);
        } else {
            long a = arg1.getLong();
            try {
                return getScalarInt(Math.subtractExact(a, arg2));
            } catch (ArithmeticException ignored) {
                // Overflow: promote to double (Perl NV semantics)
                return new RuntimeScalar((double) a - (double) arg2);
            }
        }
    }

    /**
     * Subtracts an integer from a RuntimeScalar with uninitialized value warnings.
     * Called when 'use warnings "uninitialized"' is in effect.
     *
     * @param arg1 The RuntimeScalar to subtract from.
     * @param arg2 The integer value to subtract.
     * @return A new RuntimeScalar representing the difference.
     */
    public static RuntimeScalar subtractWarn(RuntimeScalar arg1, int arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, new RuntimeScalar(arg2), blessId, 0, "(-", "-");
            if (result != null) return result;
        }

        // Convert to number with warning for uninitialized values
        arg1 = arg1.getNumberWarn("subtraction (-)");

        // Perform subtraction based on the type of RuntimeScalar
        if (arg1.type == DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() - arg2);
        } else {
            long a = arg1.getLong();
            try {
                return getScalarInt(Math.subtractExact(a, arg2));
            } catch (ArithmeticException ignored) {
                // Overflow: promote to double (Perl NV semantics)
                return new RuntimeScalar((double) a - (double) arg2);
            }
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
        // Fast path: both INTEGER - skip blessedId check, getNumber(), type checks
        if (arg1.type == INTEGER && arg2.type == INTEGER) {
            int a = (int) arg1.value;
            int b = (int) arg2.value;
            try {
                return getScalarInt(Math.subtractExact(a, b));
            } catch (ArithmeticException ignored) {
                return new RuntimeScalar((double) a - (double) b);
            }
        }

        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(-", "-");
            if (result != null) return result;
        }

        // Convert string type to number if necessary
        arg1 = arg1.getNumber("subtraction (-)");
        arg2 = arg2.getNumber("subtraction (-)");
        // Perform subtraction based on the type of RuntimeScalar
        if (arg1.type == DOUBLE || arg2.type == DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() - arg2.getDouble());
        } else {
            long a = arg1.getLong();
            long b = arg2.getLong();
            try {
                return getScalarInt(Math.subtractExact(a, b));
            } catch (ArithmeticException ignored) {
                // Overflow: promote to double (Perl NV semantics)
                return new RuntimeScalar((double) a - (double) b);
            }
        }
    }

    /**
     * Subtracts one RuntimeScalar from another with uninitialized value warnings.
     * Called when 'use warnings "uninitialized"' is in effect.
     *
     * @param arg1 The RuntimeScalar to subtract from.
     * @param arg2 The RuntimeScalar to subtract.
     * @return A new RuntimeScalar representing the difference.
     */
    public static RuntimeScalar subtractWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Fast path: both INTEGER - skip blessedId check, getNumber(), type checks
        if (arg1.type == INTEGER && arg2.type == INTEGER) {
            int a = (int) arg1.value;
            int b = (int) arg2.value;
            try {
                return getScalarInt(Math.subtractExact(a, b));
            } catch (ArithmeticException ignored) {
                return new RuntimeScalar((double) a - (double) b);
            }
        }

        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(-", "-");
            if (result != null) return result;
        }

        // Convert to number with warning for uninitialized values
        arg1 = arg1.getNumberWarn("subtraction (-)");
        arg2 = arg2.getNumberWarn("subtraction (-)");

        // Perform subtraction based on the type of RuntimeScalar
        if (arg1.type == DOUBLE || arg2.type == DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() - arg2.getDouble());
        } else {
            long a = arg1.getLong();
            long b = arg2.getLong();
            try {
                return getScalarInt(Math.subtractExact(a, b));
            } catch (ArithmeticException ignored) {
                // Overflow: promote to double (Perl NV semantics)
                return new RuntimeScalar((double) a - (double) b);
            }
        }
    }

    /**
     * Multiplies two RuntimeScalar objects and returns the result.
     * Fast path - no warning checks.
     *
     * @param arg1 The first RuntimeScalar to multiply.
     * @param arg2 The second RuntimeScalar to multiply.
     * @return A new RuntimeScalar representing the product.
     */
    public static RuntimeScalar multiply(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Fast path: both INTEGER - skip blessedId check, getNumber(), type checks
        if (arg1.type == INTEGER && arg2.type == INTEGER) {
            int a = (int) arg1.value;
            int b = (int) arg2.value;
            try {
                return getScalarInt(Math.multiplyExact(a, b));
            } catch (ArithmeticException ignored) {
                return new RuntimeScalar((double) a * (double) b);
            }
        }

        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(*", "*");
            if (result != null) return result;
        }

        // Convert string type to number if necessary
        arg1 = arg1.getNumber("multiplication (*)");
        arg2 = arg2.getNumber("multiplication (*)");
        // Perform multiplication based on the type of RuntimeScalar
        if (arg1.type == DOUBLE || arg2.type == DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() * arg2.getDouble());
        } else {
            long a = arg1.getLong();
            long b = arg2.getLong();
            try {
                return getScalarInt(Math.multiplyExact(a, b));
            } catch (ArithmeticException ignored) {
                // Overflow: promote to double (Perl NV semantics)
                return new RuntimeScalar((double) a * (double) b);
            }
        }
    }

    /**
     * Multiplies two RuntimeScalar objects with uninitialized value warnings.
     * Called when 'use warnings "uninitialized"' is in effect.
     *
     * @param arg1 The first RuntimeScalar to multiply.
     * @param arg2 The second RuntimeScalar to multiply.
     * @return A new RuntimeScalar representing the product.
     */
    public static RuntimeScalar multiplyWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Fast path: both INTEGER - skip blessedId check, getNumber(), type checks
        if (arg1.type == INTEGER && arg2.type == INTEGER) {
            int a = (int) arg1.value;
            int b = (int) arg2.value;
            try {
                return getScalarInt(Math.multiplyExact(a, b));
            } catch (ArithmeticException ignored) {
                return new RuntimeScalar((double) a * (double) b);
            }
        }

        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(*", "*");
            if (result != null) return result;
        }

        // Convert to number with warning for uninitialized values
        arg1 = arg1.getNumberWarn("multiplication (*)");
        arg2 = arg2.getNumberWarn("multiplication (*)");

        // Perform multiplication based on the type of RuntimeScalar
        if (arg1.type == DOUBLE || arg2.type == DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() * arg2.getDouble());
        } else {
            long a = arg1.getLong();
            long b = arg2.getLong();
            try {
                return getScalarInt(Math.multiplyExact(a, b));
            } catch (ArithmeticException ignored) {
                // Overflow: promote to double (Perl NV semantics)
                return new RuntimeScalar((double) a * (double) b);
            }
        }
    }

    /**
     * Divides one RuntimeScalar by another and returns the result.
     * Fast path - no warning checks.
     *
     * @param arg1 The RuntimeScalar to divide.
     * @param arg2 The RuntimeScalar to divide by.
     * @return A new RuntimeScalar representing the quotient.
     * @throws PerlCompilerException if division by zero occurs.
     */
    public static RuntimeScalar divide(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, 0, "(/", "/");
            if (result != null) return result;
        }

        // Convert string type to number if necessary
        arg1 = arg1.getNumber("division (/)");
        arg2 = arg2.getNumber("division (/)");
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
     * Divides one RuntimeScalar by another with uninitialized value warnings.
     * Called when 'use warnings "uninitialized"' is in effect.
     *
     * @param arg1 The RuntimeScalar to divide.
     * @param arg2 The RuntimeScalar to divide by.
     * @return A new RuntimeScalar representing the quotient.
     * @throws PerlCompilerException if division by zero occurs.
     */
    public static RuntimeScalar divideWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, 0, "(/", "/");
            if (result != null) return result;
        }

        // Convert to number with warning for uninitialized values
        arg1 = arg1.getNumberWarn("division (/)");
        arg2 = arg2.getNumberWarn("division (/)");
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
     * Fast path - no warning checks.
     *
     * @param arg1 The RuntimeScalar to divide.
     * @param arg2 The RuntimeScalar to divide by.
     * @return A new RuntimeScalar representing the modulus.
     */
    public static RuntimeScalar modulus(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        if (blessId < 0) {
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

        // Use long arithmetic to handle large integers (beyond int range)
        long dividend = arg1.getLong();
        long divisor = arg2.getLong();
        long result = dividend % divisor;

        // Adjust result for Perl-style modulus behavior
        // In Perl, the result has the same sign as the divisor
        if (result != 0 && ((divisor > 0 && result < 0) || (divisor < 0 && result > 0))) {
            result += divisor;
        }

        // Return as int if it fits, otherwise as long
        if (result >= Integer.MIN_VALUE && result <= Integer.MAX_VALUE) {
            return new RuntimeScalar((int) result);
        }
        return new RuntimeScalar(result);
    }

    /**
     * Computes the modulus of one RuntimeScalar by another with uninitialized value warnings.
     * Called when 'use warnings "uninitialized"' is in effect.
     *
     * @param arg1 The RuntimeScalar to divide.
     * @param arg2 The RuntimeScalar to divide by.
     * @return A new RuntimeScalar representing the modulus.
     */
    public static RuntimeScalar modulusWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, 0, "(%", "%");
            if (result != null) return result;
        }

        // Convert to number with warning for uninitialized values
        arg1 = arg1.getNumberWarn("modulus (%)");
        arg2 = arg2.getNumberWarn("modulus (%)");

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

        // Use long arithmetic to handle large integers (beyond int range)
        long dividend = arg1.getLong();
        long divisor = arg2.getLong();
        long result = dividend % divisor;

        // Adjust result for Perl-style modulus behavior
        // In Perl, the result has the same sign as the divisor
        if (result != 0 && ((divisor > 0 && result < 0) || (divisor < 0 && result > 0))) {
            result += divisor;
        }

        // Return as int if it fits, otherwise as long
        if (result >= Integer.MIN_VALUE && result <= Integer.MAX_VALUE) {
            return new RuntimeScalar((int) result);
        }
        return new RuntimeScalar(result);
    }

    /**
     * Compound assignment: +=
     * Checks for (+= overload first, then falls back to (+ overload.
     * Assigns the result back to the lvalue.
     *
     * @param arg1 The lvalue RuntimeScalar (will be modified).
     * @param arg2 The rvalue RuntimeScalar.
     * @return The modified arg1.
     */
    public static RuntimeScalar addAssign(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Check for (+= overload first
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(+=", "+=", "(+");
            if (result != null) {
                // Compound overload found - assign result back to lvalue
                arg1.set(result);
                return arg1;
            }
        }
        // Fall back to base operator (which already has (+ overload support)
        RuntimeScalar result = add(arg1, arg2);
        arg1.set(result);
        return arg1;
    }

    /**
     * Compound assignment: -=
     * Checks for (-= overload first, then falls back to (- overload.
     * Assigns the result back to the lvalue.
     *
     * @param arg1 The lvalue RuntimeScalar (will be modified).
     * @param arg2 The rvalue RuntimeScalar.
     * @return The modified arg1.
     */
    public static RuntimeScalar subtractAssign(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Check for (-= overload first
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(-=", "-=", "(-");
            if (result != null) {
                // Compound overload found - assign result back to lvalue
                arg1.set(result);
                return arg1;
            }
        }
        // Fall back to base operator (which already has (- overload support)
        RuntimeScalar result = subtract(arg1, arg2);
        arg1.set(result);
        return arg1;
    }

    /**
     * Compound assignment: *=
     * Checks for (*= overload first, then falls back to (* overload.
     * Assigns the result back to the lvalue.
     *
     * @param arg1 The lvalue RuntimeScalar (will be modified).
     * @param arg2 The rvalue RuntimeScalar.
     * @return The modified arg1.
     */
    public static RuntimeScalar multiplyAssign(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Check for (*= overload first
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(*=", "*=", "(*");
            if (result != null) {
                // Compound overload found - assign result back to lvalue
                arg1.set(result);
                return arg1;
            }
        }
        // Fall back to base operator (which already has (* overload support)
        RuntimeScalar result = multiply(arg1, arg2);
        arg1.set(result);
        return arg1;
    }

    /**
     * Compound assignment: /=
     * Checks for (/= overload first, then falls back to (/ overload.
     * Assigns the result back to the lvalue.
     *
     * @param arg1 The lvalue RuntimeScalar (will be modified).
     * @param arg2 The rvalue RuntimeScalar.
     * @return The modified arg1.
     */
    public static RuntimeScalar divideAssign(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Check for (/= overload first
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(/=", "/=", "(/");
            if (result != null) {
                // Compound overload found - assign result back to lvalue
                arg1.set(result);
                return arg1;
            }
        }
        // Fall back to base operator (which already has (/ overload support)
        RuntimeScalar result = divide(arg1, arg2);
        arg1.set(result);
        return arg1;
    }

    /**
     * Compound assignment: %=
     * Checks for (%= overload first, then falls back to (% overload.
     * Assigns the result back to the lvalue.
     *
     * @param arg1 The lvalue RuntimeScalar (will be modified).
     * @param arg2 The rvalue RuntimeScalar.
     * @return The modified arg1.
     */
    public static RuntimeScalar modulusAssign(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Check for (%= overload first
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(%=", "%=", "(%");
            if (result != null) {
                // Compound overload found - assign result back to lvalue
                arg1.set(result);
                return arg1;
            }
        }
        // Fall back to base operator (which already has (% overload support)
        RuntimeScalar result = modulus(arg1, arg2);
        arg1.set(result);
        return arg1;
    }

    // ========== WARN VARIANTS FOR COMPOUND ASSIGNMENT ==========
    // These are called when 'use warnings "uninitialized"' is in effect

    /**
     * Compound assignment: += with uninitialized value warnings.
     */
    public static RuntimeScalar addAssignWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(+=", "+=", "(+");
            if (result != null) {
                arg1.set(result);
                return arg1;
            }
        }
        RuntimeScalar result = addWarn(arg1, arg2);
        arg1.set(result);
        return arg1;
    }

    /**
     * Compound assignment: -= with uninitialized value warnings.
     */
    public static RuntimeScalar subtractAssignWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(-=", "-=", "(-");
            if (result != null) {
                arg1.set(result);
                return arg1;
            }
        }
        RuntimeScalar result = subtractWarn(arg1, arg2);
        arg1.set(result);
        return arg1;
    }

    /**
     * Compound assignment: *= with uninitialized value warnings.
     */
    public static RuntimeScalar multiplyAssignWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(*=", "*=", "(*");
            if (result != null) {
                arg1.set(result);
                return arg1;
            }
        }
        RuntimeScalar result = multiplyWarn(arg1, arg2);
        arg1.set(result);
        return arg1;
    }

    /**
     * Compound assignment: /= with uninitialized value warnings.
     */
    public static RuntimeScalar divideAssignWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(/=", "/=", "(/");
            if (result != null) {
                arg1.set(result);
                return arg1;
            }
        }
        RuntimeScalar result = divideWarn(arg1, arg2);
        arg1.set(result);
        return arg1;
    }

    /**
     * Compound assignment: %= with uninitialized value warnings.
     */
    public static RuntimeScalar modulusAssignWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(%=", "%=", "(%");
            if (result != null) {
                arg1.set(result);
                return arg1;
            }
        }
        RuntimeScalar result = modulusWarn(arg1, arg2);
        arg1.set(result);
        return arg1;
    }

    /**
     * Performs integer division operation on two RuntimeScalars.
     * This is used when "use integer" pragma is in effect.
     *
     * @param arg1 The dividend RuntimeScalar.
     * @param arg2 The divisor RuntimeScalar.
     * @return A new RuntimeScalar representing the integer division result.
     */
    public static RuntimeScalar integerDivide(RuntimeScalar arg1, RuntimeScalar arg2) {
        long dividend = arg1.getLong();
        long divisor = arg2.getLong();

        if (divisor == 0) {
            throw new PerlCompilerException("Illegal division by zero");
        }

        long result = dividend / divisor;
        return new RuntimeScalar(result);
    }

    /**
     * Performs integer division with uninitialized value warnings.
     * This is used when "use integer" pragma is in effect and warnings are enabled.
     *
     * @param arg1 The dividend RuntimeScalar.
     * @param arg2 The divisor RuntimeScalar.
     * @return A new RuntimeScalar representing the integer division result.
     */
    public static RuntimeScalar integerDivideWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Convert to number with warning for uninitialized values
        arg1 = arg1.getNumberWarn("integer division (/)");
        arg2 = arg2.getNumberWarn("integer division (/)");
        long dividend = arg1.getLong();
        long divisor = arg2.getLong();

        if (divisor == 0) {
            throw new PerlCompilerException("Illegal division by zero");
        }

        long result = dividend / divisor;
        return new RuntimeScalar(result);
    }

    /**
     * Compound assignment: /= with uninitialized value warnings under "use integer".
     */
    public static RuntimeScalar integerDivideAssignWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(/=", "/=", "(/");
            if (result != null) {
                arg1.set(result);
                return arg1;
            }
        }
        RuntimeScalar result = integerDivideWarn(arg1, arg2);
        arg1.set(result);
        return arg1;
    }

    /**
     * Performs integer modulus operation on two RuntimeScalars.
     * This is used when "use integer" pragma is in effect.
     *
     * @param arg1 The RuntimeScalar to divide.
     * @param arg2 The RuntimeScalar to divide by.
     * @return A new RuntimeScalar representing the integer modulus.
     */
    public static RuntimeScalar integerModulus(RuntimeScalar arg1, RuntimeScalar arg2) {
        long dividend = arg1.getLong();
        long divisor = arg2.getLong();

        if (divisor == 0) {
            throw new PerlCompilerException("Illegal modulus zero");
        }

        long result = dividend % divisor;
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
        int blessId = blessedId(runtimeScalar);
        if (blessId < 0) {
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
        int blessId = blessedId(runtimeScalar);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(runtimeScalar, blessId, "(sqrt", "sqrt", MathOperators::sqrt);
            if (result != null) return result;
        }

        double d = runtimeScalar.getDouble();
        if (d < 0) {
            throw new PerlCompilerException("Can't take sqrt of " + ScalarUtils.formatLikePerl(d));
        }
        return new RuntimeScalar(Math.sqrt(d));
    }

    /**
     * Computes the cosine of a RuntimeScalar.
     *
     * @param runtimeScalar The RuntimeScalar to compute the cosine for.
     * @return A new RuntimeScalar representing the cosine.
     */
    public static RuntimeScalar cos(RuntimeScalar runtimeScalar) {
        // Check if object is eligible for overloading
        int blessId = blessedId(runtimeScalar);
        if (blessId < 0) {
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
        int blessId = blessedId(runtimeScalar);
        if (blessId < 0) {
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
        int blessId = blessedId(runtimeScalar);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(runtimeScalar, blessId, "(exp", "exp", MathOperators::exp);
            if (result != null) return result;
        }

        return new RuntimeScalar(Math.exp(runtimeScalar.getDouble()));
    }

    /**
     * Raises a RuntimeScalar to the power of another RuntimeScalar.
     * Fast path - no warning checks.
     *
     * @param arg1 The base RuntimeScalar.
     * @param arg2 The exponent RuntimeScalar.
     * @return A new RuntimeScalar representing the power.
     */
    public static RuntimeScalar pow(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, new RuntimeScalar(arg2), blessId, 0, "(**", "**");
            if (result != null) return result;
        }

        return new RuntimeScalar(Math.pow(arg1.getDouble(), arg2.getDouble()));
    }

    /**
     * Raises a RuntimeScalar to the power of another RuntimeScalar with uninitialized value warnings.
     * Called when 'use warnings "uninitialized"' is in effect.
     *
     * @param arg1 The base RuntimeScalar.
     * @param arg2 The exponent RuntimeScalar.
     * @return A new RuntimeScalar representing the power.
     */
    public static RuntimeScalar powWarn(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, new RuntimeScalar(arg2), blessId, 0, "(**", "**");
            if (result != null) return result;
        }

        // Convert to number with warning for uninitialized values
        arg1 = arg1.getNumberWarn("exponentiation (**)");
        arg2 = arg2.getNumberWarn("exponentiation (**)");

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
        int blessId = blessedId(arg1);
        if (blessId < 0) {
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
        int blessId = blessedId(runtimeScalar);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(runtimeScalar, blessId, "(abs", "abs", MathOperators::abs);
            if (result != null) return result;
        }

        RuntimeScalar arg1 = runtimeScalar;
        // Convert string type to number if necessary
        arg1 = arg1.getNumber("abs");
        // Compute absolute value based on the type of RuntimeScalar
        if (arg1.type == DOUBLE) {
            return new RuntimeScalar(Math.abs(arg1.getDouble()));
        } else {
            long v = arg1.getLong();
            if (v == Long.MIN_VALUE) {
                // Can't represent abs(Long.MIN_VALUE) as a signed long; Perl falls back to NV.
                return new RuntimeScalar(Math.abs((double) v));
            }
            return new RuntimeScalar(Math.abs(v));
        }
    }

    /**
     * Unary minus operator.
     * Fast path - no warning checks.
     */
    public static RuntimeScalar unaryMinus(RuntimeScalar runtimeScalar) {
        // Check if object is eligible for overloading
        int blessId = blessedId(runtimeScalar);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(runtimeScalar, blessId, "(neg", "neg", MathOperators::unaryMinus);
            if (result != null) return result;
        }

        if (runtimeScalar.isString()) {
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
            // Check if string has non-numeric trailing characters (not purely numeric)
            // Purely numeric: "10", "-10", "10.0", "-10.0", "1e5"
            // Non-numeric: "-10foo", "+xyz", "abc"
            // Don't match: whitespace-prefixed numbers like " -10"
            if (!input.matches("^\\s*[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?\\s*$")) {
                // String is not purely numeric
                if (input.startsWith("-")) {
                    // Handle case where string starts with "-"
                    return new RuntimeScalar("+" + input.substring(1));
                } else if (input.startsWith("+")) {
                    // Handle case where string starts with "+"
                    return new RuntimeScalar("-" + input.substring(1));
                } else if (input.matches("^[_A-Za-z].*")) {
                    // Only add "-" prefix for strings starting with letter/underscore
                    return new RuntimeScalar("-" + input);
                }
            }
        }
        return subtract(getScalarInt(0), runtimeScalar);
    }

    /**
     * Unary minus operator with uninitialized value warnings.
     * Called when 'use warnings "uninitialized"' is in effect.
     */
    public static RuntimeScalar unaryMinusWarn(RuntimeScalar runtimeScalar) {
        // Check if object is eligible for overloading
        int blessId = blessedId(runtimeScalar);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(runtimeScalar, blessId, "(neg", "neg", MathOperators::unaryMinusWarn);
            if (result != null) return result;
        }

        if (runtimeScalar.isString()) {
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
            // Check if string has non-numeric trailing characters (not purely numeric)
            if (!input.matches("^\\s*[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?\\s*$")) {
                // String is not purely numeric
                if (input.startsWith("-")) {
                    return new RuntimeScalar("+" + input.substring(1));
                } else if (input.startsWith("+")) {
                    return new RuntimeScalar("-" + input.substring(1));
                } else if (input.matches("^[_A-Za-z].*")) {
                    return new RuntimeScalar("-" + input);
                }
            }
        }
        // Use subtractWarn to check for uninitialized values
        return subtractWarn(getScalarInt(0), runtimeScalar);
    }

    public static RuntimeScalar integer(RuntimeScalar arg1) {
        // Check if object is eligible for overloading
        int blessId = blessedId(arg1);
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryOneArgumentOverload(arg1, blessId, "(int", "int", MathOperators::integer);
            if (result != null) return result;
        }

        // Convert string type to number if necessary
        arg1 = arg1.getNumber("int");

        // Already an integer
        if (arg1.type == RuntimeScalarType.INTEGER) {
            return arg1;
        }

        // Handle DOUBLE type
        double value = arg1.getDouble();

        // Check for infinity and NaN values
        if (Double.isInfinite(value) || Double.isNaN(value)) {
            return new RuntimeScalar(value);  // Return infinity or NaN as-is
        }

        // Use truncate to get integer part (truncates towards zero)
        return new RuntimeScalar(truncate(value));
    }


    public static RuntimeScalar not(RuntimeScalar runtimeScalar) {
        int blessId = blessedId(runtimeScalar);
        if (blessId < 0) {
            return Overload.bool_not(runtimeScalar);
        }
        return switch (runtimeScalar.type) {
            case INTEGER -> getScalarBoolean((int) runtimeScalar.value == 0);
            case DOUBLE -> getScalarBoolean((double) runtimeScalar.value == 0.0);
            case STRING, BYTE_STRING -> {
                String s = (String) runtimeScalar.value;
                yield getScalarBoolean(s.isEmpty() || s.equals("0"));
            }
            case BOOLEAN -> getScalarBoolean(!(boolean) runtimeScalar.value);
            case GLOB -> scalarFalse;
            case REGEX -> scalarFalse;
            case JAVAOBJECT -> scalarFalse;
            case VSTRING -> scalarFalse;
            case TIED_SCALAR -> not(runtimeScalar.tiedFetch());
            default -> getScalarBoolean(!runtimeScalar.getBoolean());
        };
    }

    // =====================================================================
    // NoOverload variants - used when 'no overloading' pragma is in effect.
    // These skip overload dispatch entirely and treat blessed references
    // as if unblessed (using refaddr-style numeric conversion).
    // =====================================================================

    private static RuntimeScalar arith(RuntimeScalar a, RuntimeScalar b, int op) {
        a = a.getNumberNoOverload();
        b = b.getNumberNoOverload();
        if (a.type == DOUBLE || b.type == DOUBLE) {
            double x = a.getDouble();
            double y = b.getDouble();
            return switch (op) {
                case 0 -> new RuntimeScalar(x + y);
                case 1 -> new RuntimeScalar(x - y);
                case 2 -> new RuntimeScalar(x * y);
                case 3 -> new RuntimeScalar(x / y);
                case 4 -> new RuntimeScalar(x % y);
                case 5 -> new RuntimeScalar(Math.pow(x, y));
                default -> throw new IllegalStateException();
            };
        }
        long x = a.getLong();
        long y = b.getLong();
        try {
            return switch (op) {
                case 0 -> getScalarInt(Math.addExact(x, y));
                case 1 -> getScalarInt(Math.subtractExact(x, y));
                case 2 -> getScalarInt(Math.multiplyExact(x, y));
                case 3 -> y != 0 && x % y == 0
                        ? getScalarInt(x / y)
                        : new RuntimeScalar((double) x / (double) y);
                case 4 -> y != 0 ? getScalarInt(x % y)
                        : new RuntimeScalar((double) x % (double) y);
                case 5 -> new RuntimeScalar(Math.pow(x, y));
                default -> throw new IllegalStateException();
            };
        } catch (ArithmeticException ignored) {
            return new RuntimeScalar((double) x + (double) y);
        }
    }

    public static RuntimeScalar addNoOverload(RuntimeScalar a, RuntimeScalar b)      { return arith(a, b, 0); }
    public static RuntimeScalar subtractNoOverload(RuntimeScalar a, RuntimeScalar b) { return arith(a, b, 1); }
    public static RuntimeScalar multiplyNoOverload(RuntimeScalar a, RuntimeScalar b) { return arith(a, b, 2); }
    public static RuntimeScalar divideNoOverload(RuntimeScalar a, RuntimeScalar b)   { return arith(a, b, 3); }
    public static RuntimeScalar modulusNoOverload(RuntimeScalar a, RuntimeScalar b)  { return arith(a, b, 4); }
    public static RuntimeScalar powNoOverload(RuntimeScalar a, RuntimeScalar b)      { return arith(a, b, 5); }

    public static RuntimeScalar unaryMinusNoOverload(RuntimeScalar a) {
        RuntimeScalar n = a.getNumberNoOverload();
        if (n.type == DOUBLE) return new RuntimeScalar(-n.getDouble());
        long v = n.getLong();
        try {
            return getScalarInt(Math.negateExact(v));
        } catch (ArithmeticException ignored) {
            return new RuntimeScalar(-(double) v);
        }
    }
}
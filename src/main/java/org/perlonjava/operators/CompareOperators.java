package org.perlonjava.operators;

import org.perlonjava.runtime.OverloadContext;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarType;

import static org.perlonjava.runtime.RuntimeScalarCache.getScalarBoolean;
import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;
import static org.perlonjava.runtime.RuntimeScalarType.blessedId;

/**
 * This class provides comparison operators for RuntimeScalar objects.
 * It includes both numeric and string comparison methods.
 */
public class CompareOperators {

    /**
     * Checks if the first RuntimeScalar is less than the second.
     *
     * @param arg1 The first scalar to compare.
     * @param arg2 The second scalar to compare.
     * @return A RuntimeScalar representing a boolean value (true if arg1 < arg2).
     */
    public static RuntimeScalar lessThan(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId != 0 || blessId2 != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(<", "<");
            if (result != null) return result;

            // Try fallback to spaceship operator
            result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(<=>", "<=>");
            if (result != null) {
                return getScalarBoolean(result.getInt() < 0);
            }
        }

        // Convert strings to numbers if necessary
        arg1 = arg1.getNumber();
        arg2 = arg2.getNumber();
        // Perform comparison based on type
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return getScalarBoolean(arg1.getDouble() < arg2.getDouble());
        } else {
            return getScalarBoolean(arg1.getInt() < arg2.getInt());
        }
    }

    /**
     * Checks if the first RuntimeScalar is less than or equal to the second.
     *
     * @param arg1 The first scalar to compare.
     * @param arg2 The second scalar to compare.
     * @return A RuntimeScalar representing a boolean value (true if arg1 <= arg2).
     */
    public static RuntimeScalar lessThanOrEqual(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId != 0 || blessId2 != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(<=", "<=");
            if (result != null) return result;

            // Try fallback to spaceship operator
            result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(<=>", "<=>");
            if (result != null) {
                return getScalarBoolean(result.getInt() <= 0);
            }
        }

        // Convert strings to numbers if necessary
        arg1 = arg1.getNumber();
        arg2 = arg2.getNumber();
        // Perform comparison based on type
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return getScalarBoolean(arg1.getDouble() <= arg2.getDouble());
        } else {
            return getScalarBoolean(arg1.getInt() <= arg2.getInt());
        }
    }

    /**
     * Checks if the first RuntimeScalar is greater than the second.
     *
     * @param arg1 The first scalar to compare.
     * @param arg2 The second scalar to compare.
     * @return A RuntimeScalar representing a boolean value (true if arg1 > arg2).
     */
    public static RuntimeScalar greaterThan(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId != 0 || blessId2 != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(>", ">");
            if (result != null) return result;

            // Try fallback to spaceship operator
            result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(<=>", "<=>");
            if (result != null) {
                return getScalarBoolean(result.getInt() > 0);
            }
        }

        // Convert strings to numbers if necessary
        arg1 = arg1.getNumber();
        arg2 = arg2.getNumber();
        // Perform comparison based on type
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return getScalarBoolean(arg1.getDouble() > arg2.getDouble());
        } else {
            return getScalarBoolean(arg1.getInt() > arg2.getInt());
        }
    }

    /**
     * Checks if the first RuntimeScalar is greater than or equal to the second.
     *
     * @param arg1 The first scalar to compare.
     * @param arg2 The second scalar to compare.
     * @return A RuntimeScalar representing a boolean value (true if arg1 >= arg2).
     */
    public static RuntimeScalar greaterThanOrEqual(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId != 0 || blessId2 != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(>=", ">=");
            if (result != null) return result;

            // Try fallback to spaceship operator
            result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(<=>", "<=>");
            if (result != null) {
                return getScalarBoolean(result.getInt() >= 0);
            }
        }

        // Convert strings to numbers if necessary
        arg1 = arg1.getNumber();
        arg2 = arg2.getNumber();
        // Perform comparison based on type
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return getScalarBoolean(arg1.getDouble() >= arg2.getDouble());
        } else {
            return getScalarBoolean(arg1.getInt() >= arg2.getInt());
        }
    }

    /**
     * Checks if the first RuntimeScalar is equal to the second integer.
     *
     * @param arg1 The scalar to compare.
     * @param arg2 The integer to compare.
     * @return A RuntimeScalar representing a boolean value (true if arg1 == arg2).
     */
    public static RuntimeScalar equalTo(RuntimeScalar arg1, int arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        if (blessId != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, new RuntimeScalar(arg2), blessId, 0, "(==", "==");
            if (result != null) return result;

            // Try fallback to spaceship operator
            result = OverloadContext.tryTwoArgumentOverload(arg1, new RuntimeScalar(arg2), blessId, 0, "(<=>", "<=>");
            if (result != null) {
                return getScalarBoolean(result.getInt() == 0);
            }
        }

        // Convert strings to numbers if necessary
        arg1 = arg1.getNumber();
        // Perform comparison based on type
        if (arg1.type == RuntimeScalarType.DOUBLE) {
            return getScalarBoolean(arg1.getDouble() == (double) arg2);
        } else {
            return getScalarBoolean(arg1.getInt() == arg2);
        }
    }

    /**
     * Checks if the first RuntimeScalar is equal to the second.
     *
     * @param arg1 The first scalar to compare.
     * @param arg2 The second scalar to compare.
     * @return A RuntimeScalar representing a boolean value (true if arg1 == arg2).
     */
    public static RuntimeScalar equalTo(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId != 0 || blessId2 != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(==", "==");
            if (result != null) return result;

            // Try fallback to spaceship operator
            result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(<=>", "<=>");
            if (result != null) {
                return getScalarBoolean(result.getInt() == 0);
            }
        }

        // Convert strings to numbers if necessary
        arg1 = arg1.getNumber();
        arg2 = arg2.getNumber();
        // Perform comparison based on type
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return getScalarBoolean(arg1.getDouble() == arg2.getDouble());
        } else {
            return getScalarBoolean(arg1.getInt() == arg2.getInt());
        }
    }

    /**
     * Checks if the first RuntimeScalar is not equal to the second.
     *
     * @param arg1 The first scalar to compare.
     * @param arg2 The second scalar to compare.
     * @return A RuntimeScalar representing a boolean value (true if arg1 != arg2).
     */
    public static RuntimeScalar notEqualTo(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId != 0 || blessId2 != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(!=", "!=");
            if (result != null) return result;

            // Try fallback to spaceship operator
            result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(<=>", "<=>");
            if (result != null) {
                return getScalarBoolean(result.getInt() != 0);
            }
        }

        // Convert strings to numbers if necessary
        arg1 = arg1.getNumber();
        arg2 = arg2.getNumber();
        // Perform comparison based on type
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return getScalarBoolean(arg1.getDouble() != arg2.getDouble());
        } else {
            return getScalarBoolean(arg1.getInt() != arg2.getInt());
        }
    }

    /**
     * Compares two RuntimeScalars using the spaceship operator.
     *
     * @param arg1 The first scalar to compare.
     * @param arg2 The second scalar to compare.
     * @return A RuntimeScalar representing an integer (-1, 0, 1) based on comparison.
     */
    public static RuntimeScalar spaceship(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId != 0 || blessId2 != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(<=>", "<=>");
            if (result != null) {
                // Normalize the overloaded result to -1, 0, or 1
                int cmpResult = result.getInt();
                return getScalarInt(Integer.compare(cmpResult, 0));
            }
        }

        // Convert strings to numbers if necessary
        arg1 = arg1.getNumber();
        arg2 = arg2.getNumber();
        // Perform comparison based on type
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return getScalarInt(Double.compare(arg1.getDouble(), arg2.getDouble()));
        } else {
            return getScalarInt(Integer.compare(arg1.getInt(), arg2.getInt()));
        }
    }

    /**
     * Compares two RuntimeScalars lexicographically.
     *
     * @param runtimeScalar The first scalar to compare.
     * @param arg2          The second scalar to compare.
     * @return A RuntimeScalar representing an integer (-1, 0, 1) based on comparison.
     */
    public static RuntimeScalar cmp(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(runtimeScalar);
        int blessId2 = blessedId(arg2);
        if (blessId != 0 || blessId2 != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(runtimeScalar, arg2, blessId, blessId2, "(cmp", "cmp");
            if (result != null) {
                // Normalize the overloaded result to -1, 0, or 1
                int cmpResult = result.getInt();
                return getScalarInt(Integer.compare(cmpResult, 0));
            }
        }

        return getScalarInt(Integer.compare(runtimeScalar.toString().compareTo(arg2.toString()), 0));
    }

    /**
     * Checks if two RuntimeScalars are equal as strings.
     *
     * @param runtimeScalar The first scalar to compare.
     * @param arg2          The second scalar to compare.
     * @return A RuntimeScalar representing a boolean value (true if arg1 equals arg2).
     */
    public static RuntimeScalar eq(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(runtimeScalar);
        int blessId2 = blessedId(arg2);
        if (blessId != 0 || blessId2 != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(runtimeScalar, arg2, blessId, blessId2, "(eq", "eq");
            if (result != null) return result;

            // Try fallback to cmp operator
            result = OverloadContext.tryTwoArgumentOverload(runtimeScalar, arg2, blessId, blessId2, "(cmp", "cmp");
            if (result != null) {
                return getScalarBoolean(result.getInt() == 0);
            }
        }

        return getScalarBoolean(runtimeScalar.toString().equals(arg2.toString()));
    }

    /**
     * Checks if two RuntimeScalars are not equal as strings.
     *
     * @param runtimeScalar The first scalar to compare.
     * @param arg2          The second scalar to compare.
     * @return A RuntimeScalar representing a boolean value (true if arg1 does not equal arg2).
     */
    public static RuntimeScalar ne(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(runtimeScalar);
        int blessId2 = blessedId(arg2);
        if (blessId != 0 || blessId2 != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(runtimeScalar, arg2, blessId, blessId2, "(ne", "ne");
            if (result != null) return result;

            // Try fallback to cmp operator
            result = OverloadContext.tryTwoArgumentOverload(runtimeScalar, arg2, blessId, blessId2, "(cmp", "cmp");
            if (result != null) {
                return getScalarBoolean(result.getInt() != 0);
            }
        }

        return getScalarBoolean(!runtimeScalar.toString().equals(arg2.toString()));
    }

    /**
     * Checks if the first RuntimeScalar is less than the second as strings.
     *
     * @param runtimeScalar The first scalar to compare.
     * @param arg2          The second scalar to compare.
     * @return A RuntimeScalar representing a boolean value (true if arg1 < arg2).
     */
    public static RuntimeScalar lt(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(runtimeScalar);
        int blessId2 = blessedId(arg2);
        if (blessId != 0 || blessId2 != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(runtimeScalar, arg2, blessId, blessId2, "(lt", "lt");
            if (result != null) return result;

            // Try fallback to cmp operator
            result = OverloadContext.tryTwoArgumentOverload(runtimeScalar, arg2, blessId, blessId2, "(cmp", "cmp");
            if (result != null) {
                return getScalarBoolean(result.getInt() < 0);
            }
        }

        return getScalarBoolean(runtimeScalar.toString().compareTo(arg2.toString()) < 0);
    }

    /**
     * Checks if the first RuntimeScalar is less than or equal to the second as strings.
     *
     * @param runtimeScalar The first scalar to compare.
     * @param arg2          The second scalar to compare.
     * @return A RuntimeScalar representing a boolean value (true if arg1 <= arg2).
     */
    public static RuntimeScalar le(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(runtimeScalar);
        int blessId2 = blessedId(arg2);
        if (blessId != 0 || blessId2 != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(runtimeScalar, arg2, blessId, blessId2, "(le", "le");
            if (result != null) return result;

            // Try fallback to cmp operator
            result = OverloadContext.tryTwoArgumentOverload(runtimeScalar, arg2, blessId, blessId2, "(cmp", "cmp");
            if (result != null) {
                return getScalarBoolean(result.getInt() <= 0);
            }
        }

        return getScalarBoolean(runtimeScalar.toString().compareTo(arg2.toString()) <= 0);
    }

    /**
     * Checks if the first RuntimeScalar is greater than the second as strings.
     *
     * @param runtimeScalar The first scalar to compare.
     * @param arg2          The second scalar to compare.
     * @return A RuntimeScalar representing a boolean value (true if arg1 > arg2).
     */
    public static RuntimeScalar gt(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(runtimeScalar);
        int blessId2 = blessedId(arg2);
        if (blessId != 0 || blessId2 != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(runtimeScalar, arg2, blessId, blessId2, "(gt", "gt");
            if (result != null) return result;

            // Try fallback to cmp operator
            result = OverloadContext.tryTwoArgumentOverload(runtimeScalar, arg2, blessId, blessId2, "(cmp", "cmp");
            if (result != null) {
                return getScalarBoolean(result.getInt() > 0);
            }
        }

        return getScalarBoolean(runtimeScalar.toString().compareTo(arg2.toString()) > 0);
    }

    /**
     * Checks if the first RuntimeScalar is greater than or equal to the second as strings.
     *
     * @param runtimeScalar The first scalar to compare.
     * @param arg2          The second scalar to compare.
     * @return A RuntimeScalar representing a boolean value (true if arg1 >= arg2).
     */
    public static RuntimeScalar ge(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(runtimeScalar);
        int blessId2 = blessedId(arg2);
        if (blessId != 0 || blessId2 != 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(runtimeScalar, arg2, blessId, blessId2, "(ge", "ge");
            if (result != null) return result;

            // Try fallback to cmp operator
            result = OverloadContext.tryTwoArgumentOverload(runtimeScalar, arg2, blessId, blessId2, "(cmp", "cmp");
            if (result != null) {
                return getScalarBoolean(result.getInt() >= 0);
            }
        }

        return getScalarBoolean(runtimeScalar.toString().compareTo(arg2.toString()) >= 0);
    }
}

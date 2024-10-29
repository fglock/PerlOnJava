package org.perlonjava.operators;

import org.perlonjava.parser.NumberParser;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarType;

import static org.perlonjava.runtime.RuntimeScalarCache.getScalarBoolean;
import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;

/**
 * This class provides comparison operators for RuntimeScalar objects.
 * It includes both numeric and string comparison methods.
 */
public class CompareOperators {

    /**
     * Checks if the first RuntimeScalar is less than the second.
     *
     * @param runtimeScalar The first scalar to compare.
     * @param arg2          The second scalar to compare.
     * @return A RuntimeScalar representing a boolean value (true if arg1 < arg2).
     */
    public static RuntimeScalar lessThan(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        RuntimeScalar arg1 = runtimeScalar;
        // Convert strings to numbers if necessary
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = NumberParser.parseNumber(arg1);
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = NumberParser.parseNumber(arg2);
        }
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
     * @param runtimeScalar The first scalar to compare.
     * @param arg2          The second scalar to compare.
     * @return A RuntimeScalar representing a boolean value (true if arg1 <= arg2).
     */
    public static RuntimeScalar lessThanOrEqual(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        RuntimeScalar arg1 = runtimeScalar;
        // Convert strings to numbers if necessary
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = NumberParser.parseNumber(arg1);
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = NumberParser.parseNumber(arg2);
        }
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
     * @param runtimeScalar The first scalar to compare.
     * @param arg2          The second scalar to compare.
     * @return A RuntimeScalar representing a boolean value (true if arg1 > arg2).
     */
    public static RuntimeScalar greaterThan(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        RuntimeScalar arg1 = runtimeScalar;
        // Convert strings to numbers if necessary
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = NumberParser.parseNumber(arg1);
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = NumberParser.parseNumber(arg2);
        }
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
     * @param runtimeScalar The first scalar to compare.
     * @param arg2          The second scalar to compare.
     * @return A RuntimeScalar representing a boolean value (true if arg1 >= arg2).
     */
    public static RuntimeScalar greaterThanOrEqual(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        RuntimeScalar arg1 = runtimeScalar;
        // Convert strings to numbers if necessary
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = NumberParser.parseNumber(arg1);
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = NumberParser.parseNumber(arg2);
        }
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
     * @param runtimeScalar The scalar to compare.
     * @param arg2          The integer to compare.
     * @return A RuntimeScalar representing a boolean value (true if arg1 == arg2).
     */
    public static RuntimeScalar equalTo(RuntimeScalar runtimeScalar, int arg2) {
        RuntimeScalar arg1 = runtimeScalar;
        // Convert strings to numbers if necessary
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = NumberParser.parseNumber(arg1);
        }
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
     * @param runtimeScalar The first scalar to compare.
     * @param arg2          The second scalar to compare.
     * @return A RuntimeScalar representing a boolean value (true if arg1 == arg2).
     */
    public static RuntimeScalar equalTo(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        RuntimeScalar arg1 = runtimeScalar;
        // Convert strings to numbers if necessary
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = NumberParser.parseNumber(arg1);
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = NumberParser.parseNumber(arg2);
        }
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
     * @param runtimeScalar The first scalar to compare.
     * @param arg2          The second scalar to compare.
     * @return A RuntimeScalar representing a boolean value (true if arg1 != arg2).
     */
    public static RuntimeScalar notEqualTo(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        RuntimeScalar arg1 = runtimeScalar;
        // Convert strings to numbers if necessary
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = NumberParser.parseNumber(arg1);
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = NumberParser.parseNumber(arg2);
        }
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
     * @param runtimeScalar The first scalar to compare.
     * @param arg2          The second scalar to compare.
     * @return A RuntimeScalar representing an integer (-1, 0, 1) based on comparison.
     */
    public static RuntimeScalar spaceship(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        RuntimeScalar arg1 = runtimeScalar;
        // Convert strings to numbers if necessary
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = NumberParser.parseNumber(arg1);
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = NumberParser.parseNumber(arg2);
        }
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
        return getScalarInt(runtimeScalar.toString().compareTo(arg2.toString()));
    }

    /**
     * Checks if two RuntimeScalars are equal as strings.
     *
     * @param runtimeScalar The first scalar to compare.
     * @param arg2          The second scalar to compare.
     * @return A RuntimeScalar representing a boolean value (true if arg1 equals arg2).
     */
    public static RuntimeScalar eq(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
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
        return getScalarBoolean(runtimeScalar.toString().compareTo(arg2.toString()) >= 0);
    }
}

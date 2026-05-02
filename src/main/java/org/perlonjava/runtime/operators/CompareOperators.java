package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.runtimetypes.*;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.blessedId;

/**
 * This class provides comparison operators for RuntimeScalar objects.
 * It includes both numeric and string comparison methods.
 */
public class CompareOperators {

    /**
     * Gets the location string for warning messages using caller().
     * Uses caller(1) to skip past internal frames and find user code location.
     */
    private static RuntimeScalar callerWhere() {
        // Try different caller levels to find a non-internal frame
        for (int level = 0; level <= 2; level++) {
            RuntimeList caller = RuntimeCode.caller(new RuntimeList(RuntimeScalarCache.getScalarInt(level)), RuntimeContextType.LIST);
            if (caller.size() >= 3) {
                String fileName = caller.elements.get(1).toString();
                // Skip internal Perl modules (Test::*, runtime modules)
                if (fileName != null && !fileName.isEmpty() 
                    && !fileName.contains("/Test/") 
                    && !fileName.contains("\\Test\\")) {
                    int line = ((RuntimeScalar) caller.elements.get(2)).getInt();
                    return new RuntimeScalar(" at " + fileName + " line " + line);
                }
            }
        }
        // Fallback: use caller(0) result if no better frame found
        RuntimeList caller = RuntimeCode.caller(new RuntimeList(RuntimeScalarCache.getScalarInt(0)), RuntimeContextType.LIST);
        if (caller.size() >= 3) {
            String fileName = caller.elements.get(1).toString();
            int line = ((RuntimeScalar) caller.elements.get(2)).getInt();
            return new RuntimeScalar(" at " + fileName + " line " + line);
        }
        return new RuntimeScalar("\n");
    }

    /**
     * Checks for uninitialized values and emits warnings.
     */
    private static void checkUninitialized(RuntimeScalar arg1, RuntimeScalar arg2, String op) {
        // Use getDefinedBoolean() to handle tied scalars correctly
        if (!arg1.getDefinedBoolean()) {
            WarnDie.warnWithCategory(new RuntimeScalar("Use of uninitialized value in numeric " + op),
                    callerWhere(), "uninitialized");
        }
        if (!arg2.getDefinedBoolean()) {
            WarnDie.warnWithCategory(new RuntimeScalar("Use of uninitialized value in numeric " + op),
                    callerWhere(), "uninitialized");
        }
    }

    /**
     * Checks if the spaceship result is undefined and emits a warning.
     * In Perl, when <=> returns undef and it's used by a derived operator (>, <, etc.),
     * a warning should be emitted because undef is being used in a numeric context.
     */
    private static void checkSpaceshipResult(RuntimeScalar result, String op) {
        if (!result.getDefinedBoolean()) {
            WarnDie.warnWithCategory(new RuntimeScalar("Use of uninitialized value in numeric " + op),
                    callerWhere(), "uninitialized");
        }
    }

    /**
     * Checks if the first RuntimeScalar is less than the second.
     *
     * @param arg1 The first scalar to compare.
     * @param arg2 The second scalar to compare.
     * @return A RuntimeScalar representing a boolean value (true if arg1 < arg2).
     */
    public static RuntimeScalar lessThan(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Fast path: both INTEGER - skip blessedId check, getNumber()
        if (arg1.type == RuntimeScalarType.INTEGER && arg2.type == RuntimeScalarType.INTEGER) {
            return getScalarBoolean((int) arg1.value < (int) arg2.value);
        }

        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverloadDirect(arg1, arg2, blessId, blessId2, "(<");
            if (result != null) return result;

            // Try autogeneration via spaceship operator
            result = OverloadContext.tryTwoArgumentOverloadDirect(arg1, arg2, blessId, blessId2, "(<=>");
            if (result != null) {
                checkSpaceshipResult(result, "lt (<)");
                return getScalarBoolean(result.getInt() < 0);
            }

            // Try nomethod fallback (may throw if fallback=0)
            result = OverloadContext.tryTwoArgumentNomethod(arg1, arg2, blessId, blessId2, "<");
            if (result != null) return result;
        }

        // Convert strings to numbers if necessary
        arg1 = arg1.getNumber("numeric lt (<)");
        arg2 = arg2.getNumber("numeric lt (<)");
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
        // Fast path: both INTEGER - skip blessedId check, getNumber()
        if (arg1.type == RuntimeScalarType.INTEGER && arg2.type == RuntimeScalarType.INTEGER) {
            return getScalarBoolean((int) arg1.value <= (int) arg2.value);
        }

        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverloadDirect(arg1, arg2, blessId, blessId2, "(<=");
            if (result != null) return result;

            // Try autogeneration via spaceship operator
            result = OverloadContext.tryTwoArgumentOverloadDirect(arg1, arg2, blessId, blessId2, "(<=>");
            if (result != null) {
                checkSpaceshipResult(result, "le (<=)");
                return getScalarBoolean(result.getInt() <= 0);
            }

            // Try nomethod fallback (may throw if fallback=0)
            result = OverloadContext.tryTwoArgumentNomethod(arg1, arg2, blessId, blessId2, "<=");
            if (result != null) return result;
        }

        // Convert strings to numbers if necessary
        arg1 = arg1.getNumber("numeric le (<=)");
        arg2 = arg2.getNumber("numeric le (<=)");
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
        // Fast path: both INTEGER - skip blessedId check, getNumber()
        if (arg1.type == RuntimeScalarType.INTEGER && arg2.type == RuntimeScalarType.INTEGER) {
            return getScalarBoolean((int) arg1.value > (int) arg2.value);
        }

        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverloadDirect(arg1, arg2, blessId, blessId2, "(>");
            if (result != null) return result;

            // Try autogeneration via spaceship operator
            result = OverloadContext.tryTwoArgumentOverloadDirect(arg1, arg2, blessId, blessId2, "(<=>");
            if (result != null) {
                checkSpaceshipResult(result, "gt (>)");
                return getScalarBoolean(result.getInt() > 0);
            }

            // Try nomethod fallback (may throw if fallback=0)
            result = OverloadContext.tryTwoArgumentNomethod(arg1, arg2, blessId, blessId2, ">");
            if (result != null) return result;
        }

        // Check for uninitialized values (only when using numeric comparison fallback)
        checkUninitialized(arg1, arg2, "gt (>)");

        // Convert strings to numbers if necessary
        arg1 = arg1.getNumber("numeric gt (>)");
        arg2 = arg2.getNumber("numeric gt (>)");
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
        // Fast path: both INTEGER - skip blessedId check, getNumber()
        if (arg1.type == RuntimeScalarType.INTEGER && arg2.type == RuntimeScalarType.INTEGER) {
            return getScalarBoolean((int) arg1.value >= (int) arg2.value);
        }

        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverloadDirect(arg1, arg2, blessId, blessId2, "(>=");
            if (result != null) return result;

            // Try autogeneration via spaceship operator
            result = OverloadContext.tryTwoArgumentOverloadDirect(arg1, arg2, blessId, blessId2, "(<=>");
            if (result != null) {
                checkSpaceshipResult(result, "ge (>=)");
                return getScalarBoolean(result.getInt() >= 0);
            }

            // Try nomethod fallback (may throw if fallback=0)
            result = OverloadContext.tryTwoArgumentNomethod(arg1, arg2, blessId, blessId2, ">=");
            if (result != null) return result;
        }

        // Convert strings to numbers if necessary
        arg1 = arg1.getNumber("numeric ge (>=)");
        arg2 = arg2.getNumber("numeric ge (>=)");
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
        if (blessId < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverloadDirect(arg1, new RuntimeScalar(arg2), blessId, 0, "(==");
            if (result != null) return result;

            // Try autogeneration via spaceship operator
            result = OverloadContext.tryTwoArgumentOverloadDirect(arg1, new RuntimeScalar(arg2), blessId, 0, "(<=>");
            if (result != null) {
                checkSpaceshipResult(result, "eq (==)");
                return getScalarBoolean(result.getInt() == 0);
            }

            // Try nomethod fallback (may throw if fallback=0)
            result = OverloadContext.tryTwoArgumentNomethod(arg1, new RuntimeScalar(arg2), blessId, 0, "==");
            if (result != null) return result;
        }

        // Convert strings to numbers if necessary
        arg1 = arg1.getNumber("numeric eq (==)");
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
        // Fast path: both INTEGER - skip blessedId check, getNumber()
        if (arg1.type == RuntimeScalarType.INTEGER && arg2.type == RuntimeScalarType.INTEGER) {
            return getScalarBoolean((int) arg1.value == (int) arg2.value);
        }

        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverloadDirect(arg1, arg2, blessId, blessId2, "(==");
            if (result != null) return result;

            // Try autogeneration via spaceship operator
            result = OverloadContext.tryTwoArgumentOverloadDirect(arg1, arg2, blessId, blessId2, "(<=>");
            if (result != null) {
                checkSpaceshipResult(result, "eq (==)");
                return getScalarBoolean(result.getInt() == 0);
            }

            // Try nomethod fallback (may throw if fallback=0)
            result = OverloadContext.tryTwoArgumentNomethod(arg1, arg2, blessId, blessId2, "==");
            if (result != null) return result;
        }

        // Convert strings to numbers if necessary
        arg1 = arg1.getNumber("numeric eq (==)");
        arg2 = arg2.getNumber("numeric eq (==)");
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
        // Fast path: both INTEGER - skip blessedId check, getNumber()
        if (arg1.type == RuntimeScalarType.INTEGER && arg2.type == RuntimeScalarType.INTEGER) {
            return getScalarBoolean((int) arg1.value != (int) arg2.value);
        }

        // Prepare overload context and check if object is eligible for overloading
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverloadDirect(arg1, arg2, blessId, blessId2, "(!=");
            if (result != null) return result;

            // Try autogeneration via spaceship operator
            result = OverloadContext.tryTwoArgumentOverloadDirect(arg1, arg2, blessId, blessId2, "(<=>");
            if (result != null) {
                checkSpaceshipResult(result, "ne (!=)");
                return getScalarBoolean(result.getInt() != 0);
            }

            // Try nomethod fallback (may throw if fallback=0)
            result = OverloadContext.tryTwoArgumentNomethod(arg1, arg2, blessId, blessId2, "!=");
            if (result != null) return result;
        }

        // Convert strings to numbers if necessary
        arg1 = arg1.getNumber("numeric ne (!=)");
        arg2 = arg2.getNumber("numeric ne (!=)");
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
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, "(<=>", "<=>");
            if (result != null) {
                // Normalize the overloaded result to -1, 0, or 1
                int cmpResult = result.getInt();
                return getScalarInt(Integer.compare(cmpResult, 0));
            }
        }

        // Convert strings to numbers if necessary
        arg1 = arg1.getNumber("numeric comparison (<=>)");
        arg2 = arg2.getNumber("numeric comparison (<=>)");
        // Perform comparison based on type
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            var d1 = arg1.getDouble();
            var d2 = arg2.getDouble();
            if (Double.isNaN(d1) || Double.isNaN(d2)) {
                return scalarUndef;
            }
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
        if (blessId < 0 || blessId2 < 0) {
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
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverloadDirect(runtimeScalar, arg2, blessId, blessId2, "(eq");
            if (result != null) return result;

            // Try autogeneration via cmp operator
            result = OverloadContext.tryTwoArgumentOverloadDirect(runtimeScalar, arg2, blessId, blessId2, "(cmp");
            if (result != null) {
                return getScalarBoolean(result.getInt() == 0);
            }

            // Try nomethod fallback (may throw if fallback=0)
            result = OverloadContext.tryTwoArgumentNomethod(runtimeScalar, arg2, blessId, blessId2, "eq");
            if (result != null) return result;
            // tryTwoArgumentNomethod only throws when fallback=>0.
            // When at least one operator is defined but fallback is undef/absent,
            // Perl 5 reports "no method found" — throwIfFallbackDenied enforces
            // this (DBIC t/storage/txn.t test 90, commit 1869badd2).
            // Exception: if the package defines NO operators at all (e.g.
            // "use overload;"), Perl silently falls through to native string
            // comparison; allowsFallbackAutogen() returns true in that case and
            // throwIfFallbackDenied is a no-op.
            throwIfFallbackDenied(runtimeScalar, blessId, arg2, blessId2, "eq");
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
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverloadDirect(runtimeScalar, arg2, blessId, blessId2, "(ne");
            if (result != null) return result;

            // Try autogeneration via cmp operator
            result = OverloadContext.tryTwoArgumentOverloadDirect(runtimeScalar, arg2, blessId, blessId2, "(cmp");
            if (result != null) {
                return getScalarBoolean(result.getInt() != 0);
            }

            // Try nomethod fallback (may throw if fallback=0)
            result = OverloadContext.tryTwoArgumentNomethod(runtimeScalar, arg2, blessId, blessId2, "ne");
            if (result != null) return result;
            // See eq() above — same semantics: only throw when at least one
            // operator is defined and fallback is not 1 (DBIC t/storage/txn.t
            // test 90, commit 1869badd2).  No-op when package has no operators.
            throwIfFallbackDenied(runtimeScalar, blessId, arg2, blessId2, "ne");
        }

        return getScalarBoolean(!runtimeScalar.toString().equals(arg2.toString()));
    }

    /**
     * Throws a Perl-5-style "Operation '<op>': no method found" error when
     * the overloaded package on either side does not permit fallback
     * autogeneration (fallback=undef or missing). Called by string- and
     * numeric-comparison operators after their direct overload lookups
     * fail.
     * <p>
     * If neither argument is overloaded, or the overloaded side(s) allow
     * autogeneration ({@code fallback => 1}), this method returns silently
     * and the caller proceeds with its stringification-based default.
     */
    private static void throwIfFallbackDenied(
            RuntimeScalar left, int leftBlessId,
            RuntimeScalar right, int rightBlessId,
            String opName) {
        OverloadContext lctx = leftBlessId < 0
                ? OverloadContext.prepare(leftBlessId) : null;
        OverloadContext rctx = rightBlessId < 0
                ? OverloadContext.prepare(rightBlessId) : null;
        if (lctx == null && rctx == null) return;

        // If any overloaded side allows fallback autogeneration, we allow
        // the default stringification path.
        if (lctx != null && lctx.allowsFallbackAutogen()) return;
        if (rctx != null && rctx.allowsFallbackAutogen()) return;

        String leftClause = (lctx != null)
                ? "left argument in overloaded package " + lctx.getPerlClassName()
                : "left argument has no overloaded magic";
        String rightClause = (rctx != null)
                ? "right argument in overloaded package " + rctx.getPerlClassName()
                : "right argument has no overloaded magic";
        throw new org.perlonjava.runtime.runtimetypes.PerlCompilerException(
                "Operation \"" + opName + "\": no method found,\n\t"
                        + leftClause + ",\n\t" + rightClause);
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
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverloadDirect(runtimeScalar, arg2, blessId, blessId2, "(lt");
            if (result != null) return result;

            // Try autogeneration via cmp operator
            result = OverloadContext.tryTwoArgumentOverloadDirect(runtimeScalar, arg2, blessId, blessId2, "(cmp");
            if (result != null) {
                return getScalarBoolean(result.getInt() < 0);
            }

            // Try nomethod fallback (may throw if fallback=0)
            result = OverloadContext.tryTwoArgumentNomethod(runtimeScalar, arg2, blessId, blessId2, "lt");
            if (result != null) return result;
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
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverloadDirect(runtimeScalar, arg2, blessId, blessId2, "(le");
            if (result != null) return result;

            // Try autogeneration via cmp operator
            result = OverloadContext.tryTwoArgumentOverloadDirect(runtimeScalar, arg2, blessId, blessId2, "(cmp");
            if (result != null) {
                return getScalarBoolean(result.getInt() <= 0);
            }

            // Try nomethod fallback (may throw if fallback=0)
            result = OverloadContext.tryTwoArgumentNomethod(runtimeScalar, arg2, blessId, blessId2, "le");
            if (result != null) return result;
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
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverloadDirect(runtimeScalar, arg2, blessId, blessId2, "(gt");
            if (result != null) return result;

            // Try autogeneration via cmp operator
            result = OverloadContext.tryTwoArgumentOverloadDirect(runtimeScalar, arg2, blessId, blessId2, "(cmp");
            if (result != null) {
                return getScalarBoolean(result.getInt() > 0);
            }

            // Try nomethod fallback (may throw if fallback=0)
            result = OverloadContext.tryTwoArgumentNomethod(runtimeScalar, arg2, blessId, blessId2, "gt");
            if (result != null) return result;
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
        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverloadDirect(runtimeScalar, arg2, blessId, blessId2, "(ge");
            if (result != null) return result;

            // Try autogeneration via cmp operator
            result = OverloadContext.tryTwoArgumentOverloadDirect(runtimeScalar, arg2, blessId, blessId2, "(cmp");
            if (result != null) {
                return getScalarBoolean(result.getInt() >= 0);
            }

            // Try nomethod fallback (may throw if fallback=0)
            result = OverloadContext.tryTwoArgumentNomethod(runtimeScalar, arg2, blessId, blessId2, "ge");
            if (result != null) return result;
        }

        return getScalarBoolean(runtimeScalar.toString().compareTo(arg2.toString()) >= 0);
    }

    /**
     * Smartmatch operator (~~).
     * This is a simplified implementation that performs basic equality checking.
     * Full Perl smartmatch has complex dispatch rules based on operand types.
     * For now, we implement basic scalar equality comparison.
     *
     * @param arg1 The left operand
     * @param arg2 The right operand
     * @return A RuntimeScalar representing true if they match, false otherwise
     */
    public static RuntimeScalar smartmatch(RuntimeScalar arg1, RuntimeScalar arg2) {
        // Simplified smartmatch: try string equality first, then numeric
        // This handles the basic case in the state.t test

        // Check if both are defined
        if (!arg1.getDefinedBoolean() && !arg2.getDefinedBoolean()) {
            return scalarTrue;  // undef ~~ undef is true
        }
        if (!arg1.getDefinedBoolean() || !arg2.getDefinedBoolean()) {
            return scalarFalse;  // one is undef, one is not
        }

        // Try string comparison
        if (arg1.toString().equals(arg2.toString())) {
            return scalarTrue;
        }

        // Try numeric comparison if both look like numbers
        try {
            if (arg1.type == RuntimeScalarType.INTEGER || arg1.type == RuntimeScalarType.DOUBLE ||
                    arg2.type == RuntimeScalarType.INTEGER || arg2.type == RuntimeScalarType.DOUBLE) {
                RuntimeScalar num1 = arg1.getNumber();
                RuntimeScalar num2 = arg2.getNumber();
                if (num1.type == RuntimeScalarType.DOUBLE || num2.type == RuntimeScalarType.DOUBLE) {
                    return getScalarBoolean(num1.getDouble() == num2.getDouble());
                } else {
                    return getScalarBoolean(num1.getInt() == num2.getInt());
                }
            }
        } catch (Exception e) {
            // Not numeric, fall through
        }

        return scalarFalse;
    }
}

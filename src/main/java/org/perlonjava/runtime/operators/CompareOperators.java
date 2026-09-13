package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.WarningBitsRegistry;
import org.perlonjava.runtime.perlmodule.Strict;
import org.perlonjava.runtime.regex.RuntimeRegex;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.blessedId;

/**
 * This class provides comparison operators for RuntimeScalar objects.
 * It includes both numeric and string comparison methods.
 */
public class CompareOperators {
    /**
     * Array-reference smartmatch is structural and can encounter cyclic Perl
     * arrays.  Keep the pairs currently being compared (not a global memo):
     * seeing the same pair again means the two structures only match if they
     * are the identical referent.  This mirrors Perl's terminating behaviour
     * for $a = []; push @$a, $a; $a ~~ $a.
     */
    private static final ThreadLocal<IdentityHashMap<Object, IdentityHashMap<Object, Boolean>>>
            smartmatchArrayPairs = ThreadLocal.withInitial(IdentityHashMap::new);

    private static boolean arrayPairActive(Object left, Object right) {
        IdentityHashMap<Object, IdentityHashMap<Object, Boolean>> pairs = smartmatchArrayPairs.get();
        IdentityHashMap<Object, Boolean> rights = pairs.get(left);
        return rights != null && rights.containsKey(right);
    }

    private static void enterArrayPair(Object left, Object right) {
        smartmatchArrayPairs.get()
                .computeIfAbsent(left, ignored -> new IdentityHashMap<>())
                .put(right, Boolean.TRUE);
    }

    private static void leaveArrayPair(Object left, Object right) {
        IdentityHashMap<Object, IdentityHashMap<Object, Boolean>> pairs = smartmatchArrayPairs.get();
        IdentityHashMap<Object, Boolean> rights = pairs.get(left);
        if (rights == null) return;
        rights.remove(right);
        if (rights.isEmpty()) pairs.remove(left);
        if (pairs.isEmpty()) smartmatchArrayPairs.remove();
    }

    private static int compareIntegers(RuntimeScalar left, RuntimeScalar right) {
        return left.getBigint().compareTo(right.getBigint());
    }
    private static boolean bytesHintActive() {
        return (WarningBitsRegistry.getCallSiteHints() & Strict.HINT_BYTES) != 0;
    }

    private static String bytesForStringCompare(RuntimeScalar scalar) {
        String value = scalar.toString();
        if (scalar.type == RuntimeScalarType.BYTE_STRING) {
            return value;
        }
        if (isLatin1(value)) {
            return value;
        }
        return new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    private static boolean isLatin1(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0xFF) {
                return false;
            }
        }
        return true;
    }

    private static boolean stringEquals(RuntimeScalar arg1, RuntimeScalar arg2) {
        if (bytesHintActive()) {
            return bytesForStringCompare(arg1).equals(bytesForStringCompare(arg2));
        }
        return arg1.toString().equals(arg2.toString());
    }

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
            return getScalarBoolean(compareIntegers(arg1, arg2) < 0);
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
            return getScalarBoolean(compareIntegers(arg1, arg2) < 0);
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
            return getScalarBoolean(compareIntegers(arg1, arg2) <= 0);
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
            return getScalarBoolean(compareIntegers(arg1, arg2) <= 0);
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
            return getScalarBoolean(compareIntegers(arg1, arg2) > 0);
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

        // Convert strings to numbers if necessary
        arg1 = arg1.getNumber("numeric gt (>)");
        arg2 = arg2.getNumber("numeric gt (>)");
        // Perform comparison based on type
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return getScalarBoolean(arg1.getDouble() > arg2.getDouble());
        } else {
            return getScalarBoolean(compareIntegers(arg1, arg2) > 0);
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
            return getScalarBoolean(compareIntegers(arg1, arg2) >= 0);
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
            return getScalarBoolean(compareIntegers(arg1, arg2) >= 0);
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
            return getScalarBoolean(arg1.getBigint().equals(java.math.BigInteger.valueOf(arg2)));
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
            return getScalarBoolean(compareIntegers(arg1, arg2) == 0);
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
            return getScalarBoolean(compareIntegers(arg1, arg2) == 0);
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
            return getScalarBoolean(compareIntegers(arg1, arg2) != 0);
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
            return getScalarBoolean(compareIntegers(arg1, arg2) != 0);
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
            return getScalarInt(compareIntegers(arg1, arg2));
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

        return getScalarInt(
                Integer.signum(
                        PerlUtfString.comparePerlLogical(
                                runtimeScalar.toString(), arg2.toString())));
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

        return getScalarBoolean(stringEquals(runtimeScalar, arg2));
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

        return getScalarBoolean(!stringEquals(runtimeScalar, arg2));
    }

    /**
     * Defined string equality ({@code equ}).  Unlike {@code eq}, undef is a
     * value in its own right: two undefs compare equal, while undef and any
     * defined value compare unequal without producing an uninitialized warning.
     */
    public static RuntimeScalar equ(RuntimeScalar arg1, RuntimeScalar arg2) {
        arg1 = fetchDefinedComparisonOperand(arg1);
        arg2 = fetchDefinedComparisonOperand(arg2);
        boolean defined1 = arg1.getDefinedBoolean();
        boolean defined2 = arg2.getDefinedBoolean();
        if (!defined1 || !defined2) {
            return getScalarBoolean(defined1 == defined2);
        }
        return eq(arg1, arg2);
    }

    /** Defined string inequality ({@code neu}), the inverse of {@code equ}. */
    public static RuntimeScalar neu(RuntimeScalar arg1, RuntimeScalar arg2) {
        arg1 = fetchDefinedComparisonOperand(arg1);
        arg2 = fetchDefinedComparisonOperand(arg2);
        boolean defined1 = arg1.getDefinedBoolean();
        boolean defined2 = arg2.getDefinedBoolean();
        if (!defined1 || !defined2) {
            return getScalarBoolean(defined1 != defined2);
        }
        return ne(arg1, arg2);
    }

    /**
     * Defined numeric equality ({@code ===}).  It has the same overload
     * behavior as {@code ==}, but does not coerce undef or warn about it.
     */
    public static RuntimeScalar strictEqual(RuntimeScalar arg1, RuntimeScalar arg2) {
        arg1 = fetchDefinedComparisonOperand(arg1);
        arg2 = fetchDefinedComparisonOperand(arg2);
        boolean defined1 = arg1.getDefinedBoolean();
        boolean defined2 = arg2.getDefinedBoolean();
        if (!defined1 || !defined2) {
            return getScalarBoolean(defined1 == defined2);
        }
        return equalTo(arg1, arg2);
    }

    /** Defined numeric inequality ({@code !==}), the inverse of {@code ===}. */
    public static RuntimeScalar strictNotEqual(RuntimeScalar arg1, RuntimeScalar arg2) {
        arg1 = fetchDefinedComparisonOperand(arg1);
        arg2 = fetchDefinedComparisonOperand(arg2);
        boolean defined1 = arg1.getDefinedBoolean();
        boolean defined2 = arg2.getDefinedBoolean();
        if (!defined1 || !defined2) {
            return getScalarBoolean(defined1 != defined2);
        }
        return notEqualTo(arg1, arg2);
    }

    /**
     * Fetch a tied operand once before the definedness check and subsequent
     * comparison.  Calling {@code getDefinedBoolean()} on the tied wrapper and
     * then passing that same wrapper to the ordinary comparison would invoke
     * FETCH twice.
     */
    private static RuntimeScalar fetchDefinedComparisonOperand(RuntimeScalar arg) {
        return arg.type == RuntimeScalarType.TIED_SCALAR ? arg.tiedFetch() : arg;
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

        return getScalarBoolean(
                PerlUtfString.comparePerlLogical(runtimeScalar.toString(), arg2.toString()) < 0);
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

        return getScalarBoolean(
                PerlUtfString.comparePerlLogical(runtimeScalar.toString(), arg2.toString()) <= 0);
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

        return getScalarBoolean(
                PerlUtfString.comparePerlLogical(runtimeScalar.toString(), arg2.toString()) > 0);
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

        return getScalarBoolean(
                PerlUtfString.comparePerlLogical(runtimeScalar.toString(), arg2.toString()) >= 0);
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
        int blessId = blessedId(arg1);
        int blessId2 = blessedId(arg2);
        // A blessed RHS is only meaningful when it provides a smartmatch
        // overload.  Check the actual blessing, rather than its effective
        // overload id: ordinary blessed references intentionally have an
        // effective id of zero.
        if (rawBlessId(arg2) != 0 && !OverloadContext.hasDirectOverload(arg2, "(~~")) {
            throw new PerlCompilerException("Smart matching a non-overloaded object is not supported");
        }
        // A RHS object with an explicit ~~ overload owns dispatch even when
        // its referent happens to be a hash or array.
        if (OverloadContext.hasDirectOverload(arg2, "(~~")) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverloadDirect(
                    arg1, arg2, blessId, blessId2, "(~~");
            if (result != null) return result;
        }

        RuntimeArray leftArray = arrayReferent(arg1);
        RuntimeArray rightArray = arrayReferent(arg2);
        RuntimeHash leftHash = hashReferent(arg1);
        RuntimeHash rightHash = hashReferent(arg2);

        // Hash smartmatch is key-oriented.  Values deliberately do not
        // participate: { a => 1 } ~~ { a => 2 } is true in core Perl.
        // A code reference on the right is a predicate.  Aggregate operands
        // distribute their candidate keys/elements; an empty aggregate matches
        // without calling the predicate, just as core Perl does.
        if (arg2.type == RuntimeScalarType.CODE) {
            if (leftArray != null) return predicateMatchesArray(arg2, leftArray);
            if (leftHash != null) return predicateMatchesHash(arg2, leftHash);
            return predicate(arg2, arg1);
        }

        // A left array against a regex compares its individual elements. This
        // must precede generic RHS-regex dispatch so nested array comparison
        // does not stringify the aggregate to ARRAY(...).
        if (arg2.type == RuntimeScalarType.REGEX && leftArray != null
                && arg2.value instanceof RuntimeRegex regex) {
            for (RuntimeScalar candidate : leftArray) {
                if (smartmatch(candidate, arg2).getBoolean()) return scalarTrue;
            }
            return scalarFalse;
        }

        // Regex and hash dispatch use the original object identity or its
        // string form; they do not call a left object's ~~ overload first.
        if (arg2.type == RuntimeScalarType.REGEX && leftHash == null
                && arg2.value instanceof RuntimeRegex regex) {
            RuntimeScalar string = smartmatchString(arg1);
            return getScalarBoolean(regex.matcher(string, string.toString()).find());
        }

        // Regex/hash combinations inspect hash keys, not HASH(...) stringification.
        if (arg2.type == RuntimeScalarType.REGEX && leftHash != null) return regexMatchesHash((RuntimeRegex) arg2.value, leftHash);
        if (arg1.type == RuntimeScalarType.REGEX && rightHash != null) return regexMatchesHash((RuntimeRegex) arg1.value, rightHash);

        // A left-side smartmatch overload applies to a scalar RHS, but not to
        // the code/regex/hash forms already dispatched above.
        if (blessId < 0 && OverloadContext.hasDirectOverload(arg1, "(~~")
                && rightArray == null && rightHash == null) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverloadDirect(
                    arg1, arg2, blessId, blessId2, "(~~");
            if (result != null) return result;
        }

        if (leftHash != null && rightHash != null) return hashKeysEqual(leftHash, rightHash) ? scalarTrue : scalarFalse;
        if (leftHash != null && rightArray != null) return arrayKeysInHash(rightArray, leftHash) ? scalarTrue : scalarFalse;
        if (leftArray != null && rightHash != null) return arrayKeysInHash(leftArray, rightHash) ? scalarTrue : scalarFalse;
        // undef has no hash-key candidate, even for a hash with an empty key.
        if (rightHash != null) return arg1.getDefinedBoolean() && hashContainsKey(rightHash, arg1) ? scalarTrue : scalarFalse;
        if (leftHash != null) {
            // A hash on the left also matches the string form of its own
            // reference (for example, %h ~~ "" . \%h).  This is distinct
            // from the ordinary key-membership rule and intentionally
            // asymmetric, matching core Perl.
            if (smartmatchString(arg1).toString().equals(smartmatchString(arg2).toString())) {
                return scalarTrue;
            }
            return arg2.getDefinedBoolean() && hashContainsKey(leftHash, arg2) ? scalarTrue : scalarFalse;
        }

        if (blessId < 0 || blessId2 < 0) {
            RuntimeScalar result = OverloadContext.tryTwoArgumentOverloadDirect(arg1, arg2, blessId, blessId2, "(~~");
            if (result != null) return result;

            result = OverloadContext.tryTwoArgumentNomethod(arg1, arg2, blessId, blessId2, "~~");
            if (result != null) return result;
        }

        // ARRAY ~~ ARRAY is structural: each element in the left array must
        // smartmatch the corresponding right element.  Handle this before the
        // general RHS-array distribution rule below.
        if (leftArray != null && rightArray != null) {
            if (leftArray == rightArray) return scalarTrue;
            if (arrayPairActive(leftArray, rightArray)) return scalarFalse;
            if (leftArray.size() != rightArray.size()) return scalarFalse;

            enterArrayPair(leftArray, rightArray);
            try {
                for (int i = 0; i < leftArray.size(); i++) {
                    if (!smartmatch(leftArray.get(i), rightArray.get(i)).getBoolean()) {
                        return scalarFalse;
                    }
                }
                return scalarTrue;
            } finally {
                leaveArrayPair(leftArray, rightArray);
            }
        }

        // A regex on the left remains a regex when the right operand is an
        // array, even though regex-left scalar matching otherwise follows
        // ordinary string comparison.  Match any array element here before
        // the general RHS-array distribution below.
        if (arg1.type == RuntimeScalarType.REGEX
                && arg1.value instanceof RuntimeRegex regex
                && rightArray != null) {
            for (RuntimeScalar candidate : rightArray) {
                RuntimeScalar string = smartmatchString(candidate);
                if (regex.matcher(string, string.toString()).find()) return scalarTrue;
            }
            return scalarFalse;
        }

        // Scalar ~~ ARRAY matches when the scalar smartmatches any array
        // element. Keep the original scalar intact across candidates: tainted
        // strings must not have their backing value consumed by a failed
        // comparison before a later element matches.
        if (rightArray != null) {
            for (RuntimeScalar candidate : rightArray) {
                // A scalar does not recurse into a nested aggregate merely
                // because that aggregate happens to contain the scalar.
                if (!arg1.getDefinedBoolean()
                        && (arrayReferent(candidate) != null || hashReferent(candidate) != null)) continue;
                if (smartmatch(arg1, candidate).getBoolean()) {
                    return scalarTrue;
                }
            }
            return scalarFalse;
        }

        // A regex on either side tests the other operand as a string.  This
        // follows RHS-array distribution so qr/x/ ~~ [ 'x' ] tests each
        // candidate, rather than matching the array reference's string form.
        // Check if both are defined
        if (!arg1.getDefinedBoolean() && !arg2.getDefinedBoolean()) {
            return scalarTrue;  // undef ~~ undef is true
        }
        if (!arg1.getDefinedBoolean() || !arg2.getDefinedBoolean()) {
            return scalarFalse;  // one is undef, one is not
        }

        // Try string comparison
        if (smartmatchString(arg1).toString().equals(smartmatchString(arg2).toString())) {
            return scalarTrue;
        }

        // Try numeric comparison if both look like numbers
        try {
            if (ScalarUtils.looksLikeNumber(arg1) && ScalarUtils.looksLikeNumber(arg2)) {
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

    private static RuntimeArray arrayReferent(RuntimeScalar value) {
        return rawBlessId(value) == 0
                && value.type == RuntimeScalarType.ARRAYREFERENCE
                && value.value instanceof RuntimeArray array ? array : null;
    }

    private static RuntimeScalar smartmatchString(RuntimeScalar value) {
        // RuntimeScalar.toString() is the runtime's normal Perl string
        // conversion path: it includes a class name for ordinary objects and
        // invokes "" overloads when present.
        return new RuntimeScalar(value.toString());
    }

    private static int rawBlessId(RuntimeScalar value) {
        if (value.blessId != 0) return value.blessId;
        return value.value instanceof RuntimeBase base ? base.blessId : 0;
    }

    private static RuntimeHash hashReferent(RuntimeScalar value) {
        return rawBlessId(value) == 0
                && value.type == RuntimeScalarType.HASHREFERENCE
                && value.value instanceof RuntimeHash hash ? hash : null;
    }

    private static boolean hashKeysEqual(RuntimeHash left, RuntimeHash right) {
        RuntimeArray leftKeys = left.keys();
        RuntimeArray rightKeys = right.keys();
        if (leftKeys.size() != rightKeys.size()) return false;
        for (RuntimeScalar key : leftKeys) if (!hashContainsKey(right, key)) return false;
        return true;
    }

    private static boolean arrayKeysInHash(RuntimeArray array, RuntimeHash hash) {
        // Array/hash smartmatch asks whether any array value names a hash key.
        // In particular, ['foo', 'bar'] ~~ { foo => 1 } is true, while an
        // empty array has no candidate and is false.
        for (RuntimeScalar value : array) if (hashContainsKey(hash, value)) return true;
        return false;
    }

    private static boolean hashContainsKey(RuntimeHash hash, RuntimeScalar key) {
        return hash.exists(key).getBoolean();
    }

    private static RuntimeScalar predicate(RuntimeScalar code, RuntimeScalar value) {
        // Predicate arguments are a one-element @_.  Preserve a reference
        // scalar as that element instead of exposing its referent's list
        // value through an aliasing call frame.
        RuntimeScalar argument = new RuntimeScalar(value);
        return RuntimeCode.apply(code, new RuntimeArray(argument), RuntimeContextType.SCALAR).scalar();
    }

    private static RuntimeScalar predicateMatchesArray(RuntimeScalar code, RuntimeArray array) {
        // Smartmatch applies a code predicate to every candidate; an aggregate
        // matches only when no candidate is rejected (and an empty aggregate
        // consequently matches without invoking the code).
        for (RuntimeScalar value : array) if (!predicate(code, value).getBoolean()) return scalarFalse;
        return scalarTrue;
    }

    private static RuntimeScalar predicateMatchesHash(RuntimeScalar code, RuntimeHash hash) {
        for (RuntimeScalar key : hash.keys()) if (!predicate(code, key).getBoolean()) return scalarFalse;
        return scalarTrue;
    }

    private static RuntimeScalar regexMatchesHash(RuntimeRegex regex, RuntimeHash hash) {
        for (RuntimeScalar key : hash.keys()) if (regex.matcher(key, key.toString()).find()) return scalarTrue;
        return scalarFalse;
    }

    /**
     * Entry point used by the compilers.  Unlike ordinary binary operators,
     * smartmatch gives a bare array or hash aggregate semantics rather than
     * its scalar size.  Preserve that information by turning the aggregate
     * into the same reference representation used by explicit \@ and \%.
     */
    public static RuntimeScalar smartmatch(RuntimeBase arg1, RuntimeBase arg2) {
        return smartmatch(smartmatchOperand(arg1), smartmatchOperand(arg2));
    }

    private static RuntimeScalar smartmatchOperand(RuntimeBase operand) {
        if (operand instanceof RuntimeArray array) return array.createReference();
        if (operand instanceof RuntimeHash hash) return hash.createReference();
        if (operand instanceof RuntimeList list) {
            RuntimeArray array = new RuntimeArray();
            for (RuntimeBase value : list.elements) array.add(value.scalar());
            return array.createAnonymousReference();
        }
        return operand == null ? scalarUndef : operand.scalar();
    }
}

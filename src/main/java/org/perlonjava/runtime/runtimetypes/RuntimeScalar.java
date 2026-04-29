package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.frontend.parser.NumberParser;
import org.perlonjava.runtime.io.ClosedIOHandle;
import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.operators.StringOperators;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.perlmodule.Warnings;
import org.perlonjava.runtime.regex.RuntimeRegex;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.runtimetypes.RuntimeArray.*;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;

/**
 * The RuntimeScalar class simulates Perl scalar variables.
 *
 * <p>In Perl, a scalar variable can hold: - An integer - A double (floating-point number) - A
 * string - A reference (to arrays, hashes, subroutines, etc.) - A code reference (anonymous
 * subroutine) - Undefined value - Special literals (like filehandles, typeglobs, regular
 * expressions, etc.)
 *
 * <p>Perl scalars are dynamically typed, meaning their type can change at runtime. This class tries
 * to mimic this behavior by using an enum `RuntimeScalarType` to track the type of the value stored in the
 * scalar.
 */
public class RuntimeScalar extends RuntimeBase implements RuntimeScalarReference, DynamicState {

    // Static stack to store saved "local" states of RuntimeScalar instances
    private static final Stack<RuntimeScalar> dynamicStateStack = new Stack<>();

    // Pre-compiled regex pattern for decimal numification fast-path
    // INTEGER_PATTERN replaced with isIntegerString() for better performance
    private static final Pattern DECIMAL_PATTERN = Pattern.compile("^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?$");

    // Fast check if string might be a parseable integer
    // Returns true if first char suggests it could be an integer (digit or minus)
    // This avoids exception overhead for strings like "hello" while allowing
    // Long.parseLong to handle edge cases like overflow
    private static boolean mightBeInteger(String s) {
        if (s.isEmpty()) return false;
        char c = s.charAt(0);
        return (c >= '0' && c <= '9') || c == '-';
    }

    // Type map for scalar types to their corresponding enum
    private static final Map<Class<?>, Integer> typeMap = new HashMap<>();

    static {
        typeMap.put(Integer.class, RuntimeScalarType.INTEGER);
        typeMap.put(String.class, RuntimeScalarType.STRING);
        typeMap.put(Double.class, RuntimeScalarType.DOUBLE);
        typeMap.put(Boolean.class, RuntimeScalarType.BOOLEAN);
        typeMap.put(RuntimeCode.class, RuntimeScalarType.CODE);
        typeMap.put(RuntimeRegex.class, RuntimeScalarType.REGEX);
        // Add other known types if necessary
    }

    // Fields to store the type and value of the scalar variable
    public int type;
    public Object value;

    /**
     * True if this scalar was the direct target of an {@code open()} call that
     * created a new anonymous filehandle glob. Used by {@link #scopeExitCleanup}
     * to distinguish "owned" filehandles (should be closed at scope exit) from
     * copies/aliases of shared handles (should NOT be closed, as other variables
     * still reference the same glob).
     * <p>
     * Set by {@link org.perlonjava.runtime.operators.IOOperator#open} after creating
     * a new anonymous glob. NOT copied by {@link #set(RuntimeScalar)}, so copies
     * like {@code my $io = $handles->[$hid]} remain {@code false}.
     */
    public boolean ioOwner;

    /**
     * Number of closures that have captured this RuntimeScalar variable.
     * When {@code captureCount > 0}, {@link #scopeExitCleanup} skips the
     * blessed ref decrement because a closure still holds a reference to
     * this variable. The count is incremented in
     * {@link RuntimeCode#makeCodeObject} and decremented in
     * {@link RuntimeCode#releaseCaptures}.
     */
    public int captureCount;

    /**
     * True if {@link #scopeExitCleanup} has been called for this variable
     * (i.e., the variable's declaring scope has exited), but cleanup was
     * deferred because {@code captureCount > 0}. Used by
     * {@link RuntimeCode#releaseCaptures} to know when it's safe to call
     * {@link MortalList#deferDecrementIfTracked}: only if the scope has
     * already exited (otherwise the variable is still alive and its refCount
     * will be decremented later by scopeExitCleanup when the scope exits).
     */
    public boolean scopeExited;

    /**
     * True if this scalar "owns" a refCount increment on its referent.
     * Set to true by {@link #setLarge} after incrementing the referent's refCount.
     * Cleared when the matching decrement fires (scope exit, overwrite, undef, weaken).
     * <p>
     * This prevents spurious decrements from copies that were created via the
     * copy constructor (which does NOT increment refCount). Without this flag,
     * scope exit cleanup would decrement refCount for every scalar holding a
     * tracked reference, even if that scalar never incremented it.
     */
    public boolean refCountOwned;

    // Constructors
    public RuntimeScalar() {
        this.type = UNDEF;
    }

    public RuntimeScalar(long value) {
        initializeWithLong(value);
    }

    public RuntimeScalar(Long value) {
        initializeWithLong(value);
    }

    public RuntimeScalar(int value) {
        this.type = RuntimeScalarType.INTEGER;
        this.value = value;
    }

    public RuntimeScalar(Integer value) {
        this.type = RuntimeScalarType.INTEGER;
        this.value = value;
    }

    public RuntimeScalar(double value) {
        this.type = DOUBLE;
        this.value = value;
    }

    public RuntimeScalar(Double value) {
        this.type = DOUBLE;
        this.value = value;
    }

    public RuntimeScalar(String value) {
        if (value == null) {
            this.type = UNDEF;
        } else {
            this.type = RuntimeScalarType.STRING;
        }
        this.value = value;
    }

    public RuntimeScalar(boolean value) {
        this.type = RuntimeScalarType.BOOLEAN;
        this.value = value;
    }

    public RuntimeScalar(Boolean value) {
        this.type = RuntimeScalarType.BOOLEAN;
        this.value = value;
    }

    public RuntimeScalar(RuntimeScalar scalar) {
        if (scalar instanceof ScalarSpecialVariable ssv) {
            scalar = ssv.getValueAsScalar();
        } else if (scalar.type == TIED_SCALAR) {
            scalar = scalar.tiedFetch();
        } else if (scalar.type == READONLY_SCALAR) {
            scalar = (RuntimeScalar) scalar.value;
        }
        this.type = scalar.type;
        this.value = scalar.value;
    }

    public RuntimeScalar(RuntimeCode value) {
        this.type = RuntimeScalarType.CODE;
        this.value = value;
    }

    public RuntimeScalar(RuntimeRegex value) {
        this.type = RuntimeScalarType.REGEX;
        this.value = value;
    }

    public RuntimeScalar(RuntimeIO value) {
        if (value == null) {
            this.type = UNDEF;
        } else {
            this.type = RuntimeScalarType.GLOB;
        }
        this.value = value;
    }

    public RuntimeScalar(RuntimeGlob value) {
        if (value == null) {
            this.type = UNDEF;
        } else {
            this.type = value.type;
            // Create a detached copy so that `local *GLOB` restore doesn't affect
            // scalars that captured the glob value during the local scope.
            // This implements Perl's behavior where `my $fh = *FH` inside a local
            // scope retains the IO even after the scope ends.
            // Skip detached copy for anonymous globs (null globName) since they
            // don't participate in local/global scope and we need to preserve
            // their local slots (e.g., from stash delete).
            if (value.globName != null) {
                value = value.createDetachedCopy();
            }
        }
        this.value = value;
    }

    public RuntimeScalar(byte[] bytes) {
        this.value = new String(bytes, StandardCharsets.ISO_8859_1);
        this.type = BYTE_STRING;
    }

    /**
     * Returns a new RuntimeScalar instance with the given value.
     * Tries to store the value as a known type if possible, otherwise stores it as Object.
     *
     * @param value the value to store in the new RuntimeScalar instance
     */
    public RuntimeScalar(Object value) {
        // System.out.println("RuntimeScalar " + (value == null ? "null" : value.getClass()));
        switch (value) {
            case null -> {
                this.type = UNDEF;
                this.value = null;
            }
            case RuntimeGlob v -> {
                RuntimeScalar tmp = new RuntimeScalar(v);
                this.type = tmp.type;
                this.value = tmp.value;  // Use the detached copy from the constructor
            }
            case RuntimeIO v -> {
                RuntimeScalar tmp = new RuntimeScalar(v);
                this.type = tmp.type;
                this.value = v;
            }
            case RuntimeScalar scalar -> {
                this.type = scalar.type;
                this.value = scalar.value;
            }
            case Long longValue -> initializeWithLong(longValue);
            default -> {
                // Look for a known type, default to JAVAOBJECT if not found
                this.type = typeMap.getOrDefault(value.getClass(), RuntimeScalarType.JAVAOBJECT);
                this.value = value;
            }
        }
    }

    /**
     * Returns a new RuntimeScalar instance with the given value.
     * Tries to store the value as a known type if possible, otherwise stores it as String.
     *
     * @param value the value to store in the new RuntimeScalar instance
     * @return a new RuntimeScalar instance with the given value
     */
    public static RuntimeScalar newScalarOrString(Object value) {
        RuntimeScalar newScalar = new RuntimeScalar(value);
        if (newScalar.type == RuntimeScalarType.JAVAOBJECT) {
            // Unknown type, convert to string
            newScalar.type = RuntimeScalarType.STRING;
            newScalar.value = value.toString();
        }
        return newScalar;
    }

    public static RuntimeScalar undef() {
        return scalarUndef;
    }

    /**
     * Stores a value into the tied variable.
     * This method must be implemented by subclasses to provide the appropriate
     * arguments to the STORE method.
     *
     * @param v the value to store
     * @return the result of the STORE operation
     */
    public RuntimeScalar tiedStore(RuntimeScalar v) {
        if (value instanceof TieHandle) {
            // Tied handles don't support scalar STORE; handle operations
            // go through TieHandle's own methods (tiedPrint, etc.)
            return v;
        }
        return ((TiedVariableBase) value).tiedStore(v);
    }

    /**
     * Fetches the current value from the tied variable.
     * This method must be implemented by subclasses to provide the appropriate
     * arguments to the FETCH method.
     *
     * @return the fetched value
     */
    public RuntimeScalar tiedFetch() {
        if (value instanceof TieHandle tieHandle) {
            // Tied handles don't support scalar FETCH.
            // Return the tied object so callers get a usable blessed reference.
            return tieHandle.getSelf();
        }
        return ((TiedVariableBase) value).fetch();
    }

    public boolean isString() {
        int t = this.type;
        if (t == READONLY_SCALAR) return ((RuntimeScalar) this.value).isString();
        return t == STRING || t == BYTE_STRING || t == VSTRING;
    }

    private void initializeWithLong(Long value) {
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            // Java double can only exactly represent integers up to 2^53.
            // Beyond that, storing as DOUBLE loses precision and breaks exact pack/unpack
            // semantics for 64-bit formats (q/Q/j/J) and BER compression (w).
            long lv = value;
            // Note: avoid Math.abs(lv) which overflows for Long.MIN_VALUE
            if (lv <= 9007199254740992L && lv >= -9007199254740992L) { // within 2^53
                this.type = DOUBLE;
                this.value = (double) lv;
            } else {
                this.type = RuntimeScalarType.STRING;
                this.value = Long.toString(lv);
            }
        } else {
            this.type = RuntimeScalarType.INTEGER;
            this.value = value.intValue();
        }
    }

    public RuntimeScalar study() {
        return scalarUndef;
    }

    public RuntimeScalar clone() {
        return new RuntimeScalar(this);
    }

    public int countElements() {
        return 1;
    }

    // Inlineable fast path for getNumber()
    public RuntimeScalar getNumber() {
        if (type == INTEGER || type == DOUBLE) {
            return this;
        }
        return getNumberLarge();
    }

    // Inlineable fast path for getNumber() with operation context for "isn't numeric" warnings
    public RuntimeScalar getNumber(String operation) {
        if (type == INTEGER || type == DOUBLE) {
            return this;
        }
        // For string types, pass operation context so warnings include "in <operation>"
        if (type == STRING || type == BYTE_STRING || type == VSTRING) {
            return NumberParser.parseNumber(this, operation);
        }
        return getNumberLarge();
    }

    // Slow path for getNumber()
    public RuntimeScalar getNumberLarge() {
        // Cases 0-8 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER, DOUBLE -> this;
            case STRING, BYTE_STRING -> NumberParser.parseNumber(this);
            case UNDEF -> scalarZero;
            case VSTRING -> NumberParser.parseNumber(this);
            case BOOLEAN -> (boolean) value ? scalarOne : scalarZero;
            case GLOB -> scalarOne;  // Assuming globs are truthy, so 1
            case JAVAOBJECT -> value != null ? scalarOne : scalarZero;
            case TIED_SCALAR -> this.tiedFetch().getNumber();
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).getNumber();
            case DUALVAR -> ((DualVar) this.value).numericValue();
            default -> {
                RuntimeScalar result = Overload.numify(this);
                // Overload may return a string (e.g., "3.1" from 0+ handler);
                // ensure it's converted to a proper numeric type
                yield (result.type == INTEGER || result.type == DOUBLE)
                        ? result : result.getNumber();
            }
        };
    }

    /**
     * Convert to number without triggering overload dispatch.
     * Used by {@code no overloading} pragma to bypass the {@code 0+} overload.
     * For blessed references, returns the identity hash code as an integer.
     */
    public RuntimeScalar getNumberNoOverload() {
        return switch (type) {
            case INTEGER, DOUBLE -> this;
            case STRING, BYTE_STRING -> NumberParser.parseNumber(this);
            case UNDEF -> scalarZero;
            case VSTRING -> NumberParser.parseNumber(this);
            case BOOLEAN -> (boolean) value ? scalarOne : scalarZero;
            case GLOB -> scalarOne;
            case JAVAOBJECT -> value != null ? scalarOne : scalarZero;
            case TIED_SCALAR -> this.tiedFetch().getNumberNoOverload();
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).getNumberNoOverload();
            case DUALVAR -> ((DualVar) this.value).numericValue();
            default -> {
                // For references (blessed or not), return the identity hash code
                // of the referent, matching Scalar::Util::refaddr semantics.
                if (value != null) {
                    yield new RuntimeScalar(System.identityHashCode(value));
                }
                yield scalarZero;
            }
        };
    }

    /**
     * Converts scalar to number with uninitialized value warning.
     * Called when 'use warnings "uninitialized"' is in effect.
     *
     * @param operation The operation name for the warning message (e.g., "addition (+)")
     * @return A RuntimeScalar representing the numeric value
     */
    public RuntimeScalar getNumberWarn(String operation) {
        // Fast path for defined numeric types
        if (type == INTEGER || type == DOUBLE) {
            return this;
        }
        // Check for UNDEF and emit warning if warnings are enabled
        if (type == UNDEF) {
            WarnDie.warnWithCategory(new RuntimeScalar("Use of uninitialized value in " + operation),
                    scalarEmptyString, "uninitialized");
            return scalarZero;
        }
        // For tied scalars, fetch first then check the fetched value
        if (type == TIED_SCALAR) {
            return this.tiedFetch().getNumberWarn(operation);
        }
        // For string types, pass operation context so "isn't numeric" warnings include it
        if (type == STRING || type == BYTE_STRING || type == VSTRING) {
            return NumberParser.parseNumber(this, operation);
        }
        // All other types are defined, just convert to number
        return getNumberLarge();
    }

    /**
     * Postfix glob dereference helper used by the parser for `->**` and `->*{...}`.
     *
     * <p>In Perl, postfix glob deref is allowed to resolve plain strings as symbol names
     * even when strict refs is enabled (see perl5_t/t/op/postfixderef.t), but should still
     * reject non-glob references.
     */
    public RuntimeGlob globDerefPostfix(String packageName) {
        return switch (type) {
            case STRING, BYTE_STRING -> {
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalIO(varName);
            }
            default -> globDeref();
        };
    }

    // Inlineable fast path for getInt()
    public int getInt() {
        if (type == INTEGER) {
            return (int) this.value;
        }
        return getIntLarge();
    }

    // Slow path for getInt()
    private int getIntLarge() {
        // Cases 0-8 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> (int) value;
            case DOUBLE -> (int) ((double) value);
            case STRING, BYTE_STRING -> {
                // Avoid recursion when NumberParser.parseNumber() returns a cached scalar
                // that is also STRING. Add fast-path for plain integer strings.
                String s = (String) value;
                if (s != null) {
                    String t = s.trim();
                    if (mightBeInteger(t)) {
                        try {
                            yield (int) Long.parseLong(t);
                        } catch (NumberFormatException ignored) {
                            // Fall through to full numification (handles "1.5", overflow, etc.)
                        }
                    }
                }
                yield NumberParser.parseNumber(this).getInt();
            }
            case UNDEF -> 0;
            case VSTRING -> 0;
            case BOOLEAN -> (boolean) value ? 1 : 0;
            case GLOB -> 1;  // Assuming globs are truthy, so 1
            case JAVAOBJECT -> value != null ? 1 : 0;
            case TIED_SCALAR -> this.tiedFetch().getInt();
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).getInt();
            case DUALVAR -> ((DualVar) this.value).numericValue().getInt();
            default -> Overload.numify(this).getInt();
        };
    }

    /**
     * Get the BigInteger value of this scalar for exact arithmetic operations.
     * This method preserves full precision for large integer strings.
     * Used primarily for pack/unpack checksum calculations.
     *
     * @return the value as a BigInteger with full precision
     */
    public BigInteger getBigint() {
        if (type == RuntimeScalarType.DOUBLE) {
            // For doubles, convert to BigInteger
            double d = (double) value;
            if (d < 0) {
                // Negative value interpreted as unsigned
                return new BigInteger(Long.toUnsignedString((long) d));
            } else {
                return BigInteger.valueOf((long) d);
            }
        } else if (type == RuntimeScalarType.INTEGER) {
            // For regular integers
            return BigInteger.valueOf((int) value);
        } else if (type == RuntimeScalarType.UNDEF) {
            return BigInteger.ZERO;
        } else {
            // String types - parse exactly without precision loss
            String str = this.toString().trim();

            // Handle empty strings
            if (str.isEmpty()) {
                return BigInteger.ZERO;
            }

            try {
                // Check if it's a plain integer (most important case for checksums)
                // This is critical for preserving exact values like "9223372036854775807"
                if (str.matches("^-?\\d+$")) {
                    // Parse directly as BigInteger to preserve all digits
                    return new BigInteger(str);
                }

                // Handle scientific notation or decimal numbers
                // These require double conversion which may lose precision
                double d = Double.parseDouble(str);

                // Check for special values
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    return BigInteger.ZERO;
                }

                // For very large values, try to preserve more precision
                if (Math.abs(d) > 9007199254740992.0) { // > 2^53
                    // Format without scientific notation to get more digits
                    String formatted = String.format("%.0f", d);
                    return new BigInteger(formatted);
                }

                // Convert to long then BigInteger
                return BigInteger.valueOf((long) d);
            } catch (NumberFormatException e) {
                // Not a number, return 0
                return BigInteger.ZERO;
            }
        }
    }

    /**
     * Get the unsigned long value of this scalar.
     * Used for unsigned integer formats like Q and J.
     *
     * @return the unsigned long value as a BigInteger
     */
    public BigInteger getUnsignedLong() {
        if (type == RuntimeScalarType.DOUBLE) {
            // For doubles, emulate 32-bit Perl behavior with precision loss
            double d = (double) value;
            // Just use the double value as-is, with precision loss
            // This emulates what 32-bit Perl does
            long lval = (long) d;
            if (lval < 0) {
                // Negative value interpreted as unsigned
                return new BigInteger(Long.toUnsignedString(lval));
            } else {
                return BigInteger.valueOf(lval);
            }
        } else if (type == RuntimeScalarType.INTEGER) {
            // For regular integers - need to handle as unsigned
            long val = (int) value;  // Cast to long keeping sign extension
            if (val < 0) {
                // Convert negative to unsigned BigInteger
                return new BigInteger(Long.toUnsignedString(val));
            } else {
                return BigInteger.valueOf(val);
            }
        } else if (type == RuntimeScalarType.UNDEF) {
            return BigInteger.ZERO;
        } else {
            // String types - parse carefully to preserve precision
            String str = this.toString().trim();

            // Handle empty strings
            if (str.isEmpty()) {
                return BigInteger.ZERO;
            }

            try {
                // First, try to parse as an exact integer (no decimal point or scientific notation)
                // This preserves full precision for large integer strings
                if (str.matches("^-?\\d+$")) {
                    return new BigInteger(str);
                }

                // Handle scientific notation or decimal numbers
                // These require double conversion which may lose precision
                double d = Double.parseDouble(str);

                // Check for special values
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    return BigInteger.ZERO;
                }

                // For large values > 2^53, precision is already lost in the double
                // Just convert directly to avoid further issues
                if (d < 0 && d < Long.MIN_VALUE) {
                    // Very large negative double, treat as unsigned
                    return new BigInteger(Long.toUnsignedString((long) d));
                } else if (d < 0) {
                    // Regular negative that fits in long
                    return new BigInteger(Long.toUnsignedString((long) d));
                } else {
                    // Positive value
                    return BigInteger.valueOf((long) d);
                }
            } catch (NumberFormatException e) {
                // Not a number, return 0
                return BigInteger.ZERO;
            }
        }
    }

    public long getLong() {
        // Cases 0-8 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> (int) value;
            case DOUBLE -> (long) ((double) value);
            case STRING, BYTE_STRING -> {
                // Avoid recursion when large integer strings are preserved as STRING to keep
                // precision (e.g. values > 2^53). NumberParser.parseNumber() may return a scalar
                // that is also STRING, and calling getLong() on it would recurse indefinitely.
                String s = (String) value;
                if (s != null) {
                    String t = s.trim();
                    if (mightBeInteger(t)) {
                        try {
                            yield Long.parseLong(t);
                        } catch (NumberFormatException ignored) {
                            // Fall through to full numification (handles "1.5", overflow, etc.)
                        }
                    }
                }
                yield NumberParser.parseNumber(this).getLong();
            }
            case UNDEF -> 0L;
            case VSTRING -> 0L;
            case BOOLEAN -> (boolean) value ? 1L : 0L;
            case GLOB -> 1L;
            case JAVAOBJECT -> value != null ? 1L : 0L;
            case TIED_SCALAR -> this.tiedFetch().getLong();
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).getLong();
            case DUALVAR -> ((DualVar) this.value).numericValue().getLong();
            default -> Overload.numify(this).getLong();
        };
    }

    // Inlineable fast path for getDouble()
    public double getDouble() {
        if (type == INTEGER) {
            return (int) this.value;
        }
        return getDoubleLarge();
    }

    // Slow path for getDouble()
    private double getDoubleLarge() {
        // Cases 0-8 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> (int) value;
            case DOUBLE -> (double) value;
            case STRING, BYTE_STRING -> {
                // Avoid recursion when numeric values are preserved as STRING and also stored in
                // NumberParser's numification cache. If parseNumber() returns a scalar whose
                // conversion path leads back to getDouble(), this can recurse indefinitely.
                String s = (String) value;
                if (s != null) {
                    String t = s.trim();
                    if (!t.isEmpty() && DECIMAL_PATTERN.matcher(t).matches()) {
                        try {
                            yield Double.parseDouble(t);
                        } catch (NumberFormatException ignored) {
                            // Fall through to full numification.
                        }
                    }
                }
                yield NumberParser.parseNumber(this).getDouble();
            }
            case UNDEF -> 0.0;
            case VSTRING -> 0.0;
            case BOOLEAN -> (boolean) value ? 1.0 : 0.0;
            case GLOB -> 1.0;
            case JAVAOBJECT -> value != null ? 1.0 : 0.0;
            case TIED_SCALAR -> this.tiedFetch().getDouble();
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).getDouble();
            case DUALVAR -> ((DualVar) this.value).numericValue().getDouble();
            default -> Overload.numify(this).getDouble();
        };
    }

    // Inlineable fast path for getBoolean()
    public boolean getBoolean() {
        if (type == INTEGER) {
            return (int) value != 0;
        }
        return getBooleanLarge();
    }

    // Slow path for getBoolean()
    private boolean getBooleanLarge() {
        // Cases 0-8 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> (int) value != 0;
            case DOUBLE -> (double) value != 0.0;
            case STRING, BYTE_STRING -> {
                String s = (String) value;
                yield !s.isEmpty() && !s.equals("0");
            }
            case UNDEF -> false;
            case VSTRING -> true;
            case BOOLEAN -> (boolean) value;
            case GLOB -> true;
            case JAVAOBJECT -> value != null;
            case TIED_SCALAR -> this.tiedFetch().getBoolean();
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).getBoolean();
            case DUALVAR -> ((DualVar) this.value).stringValue().getBoolean();
            case CODE -> {
                if (value == null) yield false;
                RuntimeCode code = (RuntimeCode) value;
                yield code.packageName != null || code.subName != null || code.defined();
            }
            default -> Overload.boolify(this).getBoolean();
        };
    }

    // Get the Scalar alias into an Array
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        arr.elements.add(this);
        return arr;
    }

    // Get the list value of the Scalar
    public RuntimeList getList() {
        return new RuntimeList(this);
    }

    // Get the scalar value of the Scalar
    public RuntimeScalar scalar() {
        return this;
    }

    /**
     * Returns whether this scalar is tainted.
     * Will be updated to check type == TAINTED when taint mode is fully implemented.
     *
     * @return false for regular scalars, true for tainted scalars
     */
    public boolean isTainted() {
        return false;
    }

    // Add itself to a RuntimeArray.
    //
    // ─── WARNING: refCount accounting is intentionally asymmetric here ───
    //
    // Do NOT add an `incrementRefCountForContainerStore(copy)` call to the
    // PLAIN_ARRAY branch below, even though its sister method
    // `RuntimeArray.add(RuntimeScalar)` (used for anon-array-literal
    // construction) *does* incref. There is a reason — but the asymmetry
    // is a workaround for a DEEPER architectural mismatch with real Perl.
    //
    // ─── The deeper issue: PerlOnJava COPIES where real Perl ALIASES ───
    //
    // In system (XS) Perl, arg passing uses SV aliasing: `f($g)` stores
    // the caller's $g SV pointer directly into @_. No copy, no SvREFCNT_inc,
    // no SvREFCNT_dec. The callee's @_[0] IS the caller's $g. Lifetime is
    // bound to the caller.
    //
    // Anon-array-literal construction in real Perl uses `newSVsv` which
    // DOES copy and DOES incref the referent — because the AV owns the
    // elements and must release them when the AV dies.
    //
    // Real Perl's "symmetry" is thus: each primitive has well-defined
    // refcount semantics; aliasing is used where ownership stays with
    // the caller, copying is used where ownership transfers to the
    // container. The two Perl-level operations (pass-by-value vs
    // anon-array-literal) use different primitives on purpose.
    //
    // PerlOnJava currently copies in both cases (this method, and
    // RuntimeArray.add). So the same *runtime primitive* needs to have
    // DIFFERENT ownership semantics depending on which Perl-level
    // operation called it — which is structurally awkward and the reason
    // for the asymmetric incref policy. The asymmetry is not a property
    // of Perl; it's a property of our copy-everywhere implementation
    // choice trying to emulate Perl's alias-vs-copy distinction.
    //
    // ─── What went wrong historically ───
    //
    // Commit c8f669b14 (Template fix — "incref anon-array elements on
    // add — fixes TT directive.t") added incref to BOTH sites. The
    // anon-array-literal path has a matching createReferenceWithTrackedElements
    // at the end of the literal so its incref is balanced. This method,
    // however, is also reached from arg-passing — and the args array has
    // NO matching decref: it's popped off argsStack without walking its
    // elements. A blessed referent's refCount leaked by +1 per function
    // call. Most tests didn't notice (process-exit GC catches it), but
    // anything relying on SYNCHRONOUS DESTROY — e.g. DBIC
    // `t/storage/txn_scope_guard.t#18` (zombie-ref double-DESTROY
    // detection via @DB::args capture) — broke.
    //
    // Minimal repro (before the narrow fix below, DESTROY fires late at
    // process exit; after the fix, it fires from `@capture = ()`):
    //
    //   package Guard;
    //   sub new { bless { id => $_[1] }, "Guard" }
    //   sub DESTROY { warn "DESTROY id=$_[0]->{id}\n" }
    //   package main;
    //   my @capture;
    //   sub inner {
    //       package DB;
    //       my $f = 0;
    //       while (my @fr = caller(++$f)) { push @capture, @DB::args }
    //   }
    //   sub call_with_guard { inner() }
    //   { my $g = Guard->new("A"); call_with_guard($g); }
    //   warn "--- before clear\n";
    //   @capture = ();
    //   warn "--- after clear (DESTROY must have fired by here)\n";
    //
    // ─── Fixes, in increasing order of cleanliness ───
    //
    //   1. [CURRENT — pragmatic workaround] Drop the incref from this
    //      method (arg-passing path), keep it in RuntimeArray.add
    //      (anon-array-literal path). Both Template and DBIC pass.
    //      But the two copy-sites have different ownership semantics,
    //      which is confusing and fragile.
    //
    //   2. [LOCAL FIX] Keep the incref here and teach popArgs() to
    //      walk the args array's elements and decref each one. Keeps
    //      the "arrays always own their elements" invariant clean.
    //      Per-call cost proportional to arg count — usually small.
    //
    //   3. [SEMANTICALLY CORRECT — bigger refactor] Implement
    //      alias-on-pass to match Perl's @_. Arg passing stores the
    //      caller's RuntimeScalar pointer directly; no copy, no
    //      refcount traffic. The scalar's lifetime is tied to the
    //      caller, as in Perl. @_-mutation semantics (shift/pop/slice)
    //      would need to reflect this, and everything reading @_ would
    //      need to handle aliased values. This is the RIGHT answer
    //      long-term but touches many files.
    //
    // If you're auditing for refcount symmetry, or you hit a leak
    // regression that seems to point here: prefer option 2 or 3
    // over re-adding the incref. See dev/design/perf-dbic-safe-port.md
    // and commit history for context.
    //
    // Regression test: t/destroy_zombie_captured_by_db_args.t
    public void addToArray(RuntimeArray runtimeArray) {
        switch (runtimeArray.type) {
            case PLAIN_ARRAY -> {
                runtimeArray.elements.add(new RuntimeScalar(this));
            }
            case AUTOVIVIFY_ARRAY -> {
                AutovivificationArray.vivify(runtimeArray);
                RuntimeArray.push(runtimeArray, this);
            }
            case TIED_ARRAY -> TieArray.tiedPush(runtimeArray, this);
            default -> throw new IllegalStateException("Unknown array type: " + runtimeArray.type);
        }
    }

    /**
     * Add itself to a RuntimeScalar.
     *
     * @param scalar The RuntimeScalar object
     */
    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        return scalar.set(this);
    }

    /**
     * Vivifies this scalar as an lvalue. For plain scalars this is a no-op.
     * For hash/array element proxies (RuntimeBaseProxy subclasses), this creates
     * the actual entry in the parent container, matching Perl 5's behavior where
     * hash element lvalue access in ||=/&&=//= creates the entry before evaluating the RHS.
     */
    public void vivifyLvalue() {
        // No-op for plain scalars
    }

    // Setters

    /**
     * Increment refCount for a scalar that was just stored in a container (array/hash).
     * Container stores use the copy constructor which doesn't increment refCount
     * (to avoid over-counting for temporary copies). This method should be called
     * after storing a tracked reference in a container, if MortalList is active.
     * <p>
     * Skips elements that already have {@code refCountOwned == true}, meaning they
     * were created via {@code set()} / {@code setLarge()} rather than the copy
     * constructor, and their refCount was already incremented at creation time.
     */
    public static void incrementRefCountForContainerStore(RuntimeScalar scalar) {
        if (scalar != null && !scalar.refCountOwned
                && (scalar.type & REFERENCE_BIT) != 0 && scalar.value instanceof RuntimeBase base
                && base.refCount >= 0) {
            base.refCount++;
            base.recordOwner(scalar, "incrementRefCountForContainerStore");
            scalar.refCountOwned = true;
            // Phase B1 (refcount_alignment_52leaks_plan.md): track the
            // container element so ReachabilityWalker can see it via
            // ScalarRefRegistry.
            ScalarRefRegistry.registerRef(scalar);
        }
    }

    // Inlineable fast path for set(RuntimeScalar)
    // Types < TIED_SCALAR (0-8) never have REFERENCE_BIT (0x8000), so no
    // reference check is needed here — all reference types route to setLarge().
    public RuntimeScalar set(RuntimeScalar value) {
        if (this.type < TIED_SCALAR & value.type < TIED_SCALAR) {
            this.type = value.type;
            this.value = value.value;
            return this;
        }
        return setLarge(value);
    }

    /**
     * Set value while preserving BYTE_STRING type when possible.
     * Used by .= (string concat-assign) to prevent UTF-8 flag contamination
     * of binary buffers. Only preserves BYTE_STRING when the concat result
     * itself is BYTE_STRING (both operands were non-UTF-8). When the concat
     * result is STRING (at least one operand was UTF-8), the UTF-8 flag is
     * preserved, matching Perl's behavior where concatenation with a UTF-8
     * string upgrades the result.
     */
    public RuntimeScalar setPreservingByteString(RuntimeScalar value) {
        boolean wasByteString = (this.type == BYTE_STRING);
        this.set(value);
        // Only preserve BYTE_STRING when the concat result was also BYTE_STRING.
        // If concat produced STRING (because an operand was UTF-8), don't downgrade.
        if (wasByteString && value.type == BYTE_STRING && this.type == STRING) {
            // Check if all chars fit in Latin-1 (single byte)
            String s = this.toString();
            boolean allLatin1 = true;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) > 255) {
                    allLatin1 = false;
                    break;
                }
            }
            if (allLatin1) {
                this.type = BYTE_STRING;
            }
        }
        return this;
    }

    // Slow path for set(RuntimeScalar) — kept small for JIT inlining of set().
    // Reference-type assignments are dispatched to setLargeRefCounted() which
    // handles refCount tracking, IO lifecycle, and weak ref bookkeeping.
    private RuntimeScalar setLarge(RuntimeScalar value) {
        if (value == null) {
            closeIOOnDrop();
            this.type = RuntimeScalarType.UNDEF;
            this.value = null;
            return this;
        }
        // Unwrap source special types via switch dispatcher
        switch (value.type) {
            case TIED_SCALAR -> {
                return set(value.tiedFetch());
            }
            case READONLY_SCALAR -> {
                return set((RuntimeScalar) value.value);
            }
        }
        // Resolve ScalarSpecialVariable
        if (value instanceof ScalarSpecialVariable ssv) {
            value = ssv.getValueAsScalar();
        }
        // Handle target special types via switch dispatcher
        switch (this.type) {
            case TIED_SCALAR -> {
                return this.tiedStore(value);
            }
            case READONLY_SCALAR ->
                    throw new PerlCompilerException("Modification of a read-only value attempted");
        }

        // Reference types (or overwriting a reference) need refCount + IO tracking.
        // All reference types have REFERENCE_BIT (0x8000) set, so a single
        // bitwise OR + AND check covers both old and new values.
        if (((this.type | value.type) & REFERENCE_BIT) != 0) {
            return setLargeRefCounted(value);
        }

        // Simple non-reference assignment (no refCount tracking needed).
        // No MortalList.flush() here — neither old nor new value is a reference,
        // so no refCount was incremented/decremented, and no mortal entries were added.
        this.type = value.type;
        this.value = value.value;
        return this;
    }

    /**
     * RefCount-aware slow path for reference assignments.
     * Called from setLarge() when either the old or new value involves a reference type.
     * Separated to keep setLarge() small enough for JIT inlining of set().
     */
    private RuntimeScalar setLargeRefCounted(RuntimeScalar value) {
        // Fast path for untracked references (refCount == -1).
        // Most reference assignments involve untracked objects (named variables,
        // anonymous arrays/hashes that were never blessed). Skip all refCount
        // tracking, WeakRefRegistry checks, and MortalList flush.
        if (!this.refCountOwned && this.type != GLOBREFERENCE && value.type != GLOBREFERENCE) {
            // Both old and new are non-GLOB references. Check if referents are untracked.
            boolean oldUntracked = (this.type & REFERENCE_BIT) == 0
                    || this.value == null
                    || ((RuntimeBase) this.value).refCount == -1;
            boolean newUntracked = (value.type & REFERENCE_BIT) == 0
                    || value.value == null
                    || ((RuntimeBase) value.value).refCount == -1;
            if (oldUntracked && newUntracked) {
                this.type = value.type;
                this.value = value.value;
                return this;
            }
        }

        // ──────────────────────────────────────────────────────────────────
        // closeIOOnDrop() was REMOVED from this assignment path.
        //
        // ROOT CAUSE (Capture::Tiny failure):
        //   Capture::Tiny's _copy_std() saves STDOUT/STDERR handles like this:
        //     my $h;
        //     $h = IO::Handle->new();  open($h, ">&STDOUT"); $old{stdout} = $h;
        //     $h = IO::Handle->new();  open($h, ">&STDERR"); $old{stderr} = $h;
        //
        //   When $h is reassigned on the second iteration, setLarge() was called.
        //   The old value of $h was a GLOBREFERENCE to a gensym'd glob (Symbol::GEN*)
        //   whose IO slot held the dup'd STDOUT handle.
        //
        //   closeIOOnDrop() saw that the glob was no longer in any stash (gensym
        //   deletes it) and closed the IO.  This set ioHandle = ClosedIOHandle on
        //   the RuntimeIO.  But $old{stdout} STILL referenced the same glob/IO —
        //   so fileno($old{stdout}) now returned undef, and the later restore
        //   open(*STDOUT, ">&" . fileno($old{stdout})) failed with "Bad fd".
        //
        // WHY WE CAN'T FIX THIS WITH closeIOOnDrop:
        //   Without reference counting we cannot know whether other variables
        //   still hold a GLOBREFERENCE to the same RuntimeGlob.  The stash check
        //   only catches named globs; anonymous (gensym'd) globs are invisible.
        //
        // TRADEOFF:
        //   Removing this call means anonymous file handles that are overwritten
        //   (not explicitly closed) will leak until JVM GC / program exit.
        //   Explicit close($fh), undef $fh, and program exit still close handles.
        //   This matches the pre-existing behavior of set(int/long/double/String)
        //   which also never called closeIOOnDrop.
        //
        // See also: closeIOOnDrop() javadoc, dev/design/io_handle_lifecycle.md
        // ──────────────────────────────────────────────────────────────────

        // Track ioHolderCount for anonymous glob IO lifecycle management.
        // When a GLOBREFERENCE is copied to another variable, increment the glob's
        // holder count so scopeExitCleanup won't close the IO prematurely.
        // When a GLOBREFERENCE is overwritten, decrement the old glob's holder count.
        if (value.type == GLOBREFERENCE && value.value instanceof RuntimeGlob newGlob
                && newGlob.globName == null) {
            newGlob.ioHolderCount++;
        }
        if (this.type == GLOBREFERENCE && this.value instanceof RuntimeGlob oldGlob
                && oldGlob.globName == null) {
            oldGlob.ioHolderCount--;
        }

        // NOTE: Do NOT release captures here on CODE overwrite.
        // releaseCaptures() must only fire when the CODE ref's refCount truly
        // reaches 0 (via DestroyDispatch.callDestroy). Releasing on every
        // overwrite is wrong because other variables may still hold the same
        // CODE ref — e.g., the stash entry *Foo::bar holds the constructor
        // while a local variable also holds it. Overwriting the local should
        // not release the captures that the stash's copy still needs.
        // For untracked CODE refs (refCount == -1), the JVM GC handles cleanup.

        // Track refCount for blessed objects with DESTROY.
        // Save old referent BEFORE the assignment (for correct DESTROY ordering —
        // Perl 5 semantics: DESTROY sees the new state of the variable, not the old)
        RuntimeBase oldBase = null;
        if ((this.type & RuntimeScalarType.REFERENCE_BIT) != 0 && this.value != null) {
            oldBase = (RuntimeBase) this.value;
        }

        // If this scalar was a weak ref, remove from weak tracking before overwriting.
        // Weak refs don't count toward refCount, so skip refCount decrement later.
        boolean thisWasWeak = (oldBase != null && WeakRefRegistry.removeWeakRef(this, oldBase));

        // Increment new value's refCount (>= 0 means tracked; -1 means untracked).
        // Only increment for objects already being tracked (refCount >= 0).
        // Objects born via createReferenceWithTrackedElements or closures with
        // captures start at 0 and are always tracked. Named variables (\$x, \@a)
        // have refCount = -1 (untracked) since they have a JVM local slot that
        // isn't counted. Transitioning -1→1 would undercount.
        boolean newOwned = false;
        if ((value.type & RuntimeScalarType.REFERENCE_BIT) != 0 && value.value != null) {
            RuntimeBase nb = (RuntimeBase) value.value;
            if (nb.refCount >= 0) {
                nb.traceRefCount(+1, "RuntimeScalar.setLargeRefCounted (increment on store)");
                nb.recordOwner(this, "setLargeRefCounted store");
                nb.refCount++;
                newOwned = true;
            }
        }

        // Phase B1 (refcount_alignment_52leaks_plan.md): track this
        // scalar so the reachability walker can enumerate live lexicals.
        if (newOwned) {
            ScalarRefRegistry.registerRef(this);
        }

        // Do the assignment
        this.type = value.type;
        this.value = value.value;

        // DESTROY rescue detection for reference types.
        // Only trigger when the OLD value was a reference to the DESTROY target
        // (e.g., a weak ref being overwritten by a strong ref to the same object).
        // This detects Schema::DESTROY's self-save pattern where:
        //   $source->{schema} = $self  (overwriting weak ref with strong ref)
        // But avoids false positives from:
        //   my $self = shift  (new local variable, oldBase is null)
        if (DestroyDispatch.currentDestroyTarget != null
                && oldBase == DestroyDispatch.currentDestroyTarget
                && this.value instanceof RuntimeBase base
                && base == DestroyDispatch.currentDestroyTarget) {
            DestroyDispatch.destroyTargetRescued = true;
            // Transition from destroyed (MIN_VALUE) to tracked so that when the
            // rescuing reference is eventually released (e.g., source goes out of
            // scope at the end of DESTROY), cascading cleanup brings the refCount
            // back to 0 and triggers weak ref clearing. Without this, the rescued
            // object stays untracked (-1) and weak refs are never cleared, causing
            // leak detection failures (DBIC t/52leaks.t tests 12-20).
            //
            // Set to 1: the rescue container's single counted reference.
            // When the rescue source dies and DESTROY weakens source->{schema},
            // refCount goes 1→0→callDestroy. That callDestroy is intercepted by
            // the rescuedObjects check in callDestroy's destroyFired path (no
            // clearWeakRefsTo or cascade), keeping Schema's internals intact.
            // Proper cleanup happens at END time via clearRescuedWeakRefs.
            if (base.refCount == Integer.MIN_VALUE) {
                base.traceRefCount(0, "RuntimeScalar.setLargeRefCounted (rescue MIN_VALUE -> 1)");
                base.refCount = 1;
            } else if (base.refCount >= 0) {
                base.traceRefCount(+1, "RuntimeScalar.setLargeRefCounted (rescue increment)");
                base.refCount++;
            }
            base.recordOwner(this, "rescue from currentDestroyTarget");
            newOwned = true;
        }

        // Phase B1 (refcount_alignment_52leaks_plan.md): register this
        // scalar in ScalarRefRegistry so the reachability walker can
        // enumerate live ref-holding RuntimeScalars on demand. No-op
        // when no weaken() has ever been called.
        if (newOwned) {
            ScalarRefRegistry.registerRef(this);
        }

        // Decrement old value's refCount AFTER assignment (skip for weak refs
        // and for scalars that didn't own a refCount increment).
        if (oldBase != null && !thisWasWeak && this.refCountOwned) {
            if (oldBase.refCount > 0) {
                oldBase.traceRefCount(-1, "RuntimeScalar.setLargeRefCounted (decrement on overwrite)");
                oldBase.releaseOwner(this, "setLargeRefCounted overwrite");
            }
            if (oldBase.refCount > 0 && --oldBase.refCount == 0) {
                if (oldBase.localBindingExists) {
                    // Named container (my %hash / my @array): the local variable
                    // slot holds a strong reference not counted in refCount.
                    // Don't call callDestroy — the container is still alive.
                    // Cleanup will happen at scope exit (scopeExitCleanupHash/Array).
                } else if (oldBase.blessId != 0
                        && WeakRefRegistry.hasWeakRefsTo(oldBase)
                        && DestroyDispatch.classNeedsWalkerGate(oldBase.blessId)
                        && ReachabilityWalker.isReachableFromRoots(oldBase)) {
                    // Phase D / Step W3-Path 2: mirror of the gate in
                    // MortalList.flush(). Blessed object with outstanding
                    // weak refs whose cooperative refCount dipped to 0
                    // under an overwrite, but the walker says it's still
                    // reachable from roots (e.g. held by `our %METAS`).
                    // Treat as transient refCount drift; don't fire
                    // DESTROY; don't clear weak refs.
                    //
                    // See MortalList.flush() for full rationale and
                    // dev/modules/moose_support.md (Phase D / Step W).
                } else {
                    oldBase.refCount = Integer.MIN_VALUE;
                    DestroyDispatch.callDestroy(oldBase);
                }
            } else if (oldBase.refCount > 0 && value.type == UNDEF
                    && oldBase.blessId != 0
                    && DestroyDispatch.isInsideDestroy()
                    && WeakRefRegistry.weakRefsExist) {
                // Phase D: inside a DESTROY body, an explicit undef
                // assignment released our strong ref to another
                // blessed-with-DESTROY object but cooperative refCount
                // didn't drop to 0 (cycles). Flag a deferred sweep to
                // run once at the end of the outermost DESTROY.
                // Narrow gating (only inside DESTROY, only value==UNDEF,
                // only blessed) keeps per-set() cost to an int compare
                // and one BitSet lookup.
                String cn = NameNormalizer.getBlessStr(oldBase.blessId);
                if (cn != null && DestroyDispatch.classHasDestroy(oldBase.blessId, cn)) {
                    DestroyDispatch.sweepPendingAfterOuterDestroy = true;
                }
            }
        }

        // WEAKLY_TRACKED objects: do NOT clear weak refs on overwrite.
        // These objects have refCount == -2 and their strong refs don't have
        // refCountOwned=true (they were set before tracking started).
        // Overwriting ONE reference doesn't mean no other strong refs exist —
        // closures may capture copies (e.g., Sub::Quote's $_QUOTED capture).
        // This is the same rationale as in scopeExitCleanup.
        // Weak refs for WEAKLY_TRACKED objects are cleared only via explicit
        // undefine() of a strong reference.

        // Update ownership: this scalar now owns a refCount iff we incremented.
        this.refCountOwned = newOwned;

        // Flush deferred mortal decrements. This is the primary flush point for
        // the mortal mechanism — called after every assignment involving references.
        // Cost when MortalList.active is false: one boolean check (trivially predicted).
        MortalList.flush();

        return this;
    }

    public RuntimeScalar set(int value) {
        if (this.type == TIED_SCALAR) {
            return this.tiedStore(new RuntimeScalar(value));
        }
        if (this.type == READONLY_SCALAR) {
            throw new PerlCompilerException("Modification of a read-only value attempted");
        }
        this.type = RuntimeScalarType.INTEGER;
        this.value = value;
        return this;
    }

    public RuntimeScalar set(long value) {
        if (this.type == TIED_SCALAR) {
            return this.tiedStore(new RuntimeScalar(value));
        }
        if (this.type == READONLY_SCALAR) {
            throw new PerlCompilerException("Modification of a read-only value attempted");
        }
        this.initializeWithLong(value);
        return this;
    }

    /**
     * Set this scalar to a BigInteger value.
     * This method preserves full precision for large integers by storing them as strings.
     *
     * @param value the BigInteger value to set
     * @return this RuntimeScalar instance
     */
    public RuntimeScalar set(BigInteger value) {
        if (this.type == TIED_SCALAR) {
            return this.tiedStore(new RuntimeScalar(value.toString()));
        }
        if (this.type == READONLY_SCALAR) {
            throw new PerlCompilerException("Modification of a read-only value attempted");
        }

        // Check if the value fits in an int
        if (value.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0
                && value.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) >= 0) {
            // Fits in int
            this.type = RuntimeScalarType.INTEGER;
            this.value = value.intValue();
        }
        // Check if the value can be exactly represented as a double (up to 2^53)
        else if (value.abs().compareTo(BigInteger.valueOf(9007199254740992L)) <= 0) { // 2^53
            // Can be exactly represented as double
            this.type = RuntimeScalarType.DOUBLE;
            this.value = value.doubleValue();
        } else {
            // Too large for exact numeric representation
            // Store as string to preserve precision (like 32-bit Perl does)
            this.type = RuntimeScalarType.STRING;
            this.value = value.toString();
        }
        return this;
    }

    public RuntimeScalar set(boolean value) {
        if (this.type == TIED_SCALAR) {
            return this.tiedStore(new RuntimeScalar(value));
        }
        if (this.type == READONLY_SCALAR) {
            throw new PerlCompilerException("Modification of a read-only value attempted");
        }
        this.type = RuntimeScalarType.BOOLEAN;
        this.value = value;
        return this;
    }

    public RuntimeScalar set(String value) {
        if (this.type == TIED_SCALAR) {
            return this.tiedStore(new RuntimeScalar(value));
        }
        if (this.type == READONLY_SCALAR) {
            throw new PerlCompilerException("Modification of a read-only value attempted");
        }
        if (value == null) {
            this.type = UNDEF;
        } else {
            this.type = RuntimeScalarType.STRING;
        }
        this.value = value;
        return this;
    }

    public RuntimeScalar set(RuntimeGlob value) {
        return set(new RuntimeScalar(value));
    }

    public RuntimeScalar set(RuntimeIO value) {
        return set(new RuntimeScalar(value));
    }

    public RuntimeScalar set(Object value) {
        return set(new RuntimeScalar(value));
    }

    public RuntimeArray setFromList(RuntimeList value) {
        return new RuntimeArray(this.set(value.scalar()));
    }

    @Override
    // Inlineable fast path for toString()
    public String toString() {
        if (type == STRING || type == BYTE_STRING) {
            return (String) this.value;
        }
        return toStringLarge();
    }

    // Slow path for toString()
    private String toStringLarge() {
        // Cases 0-8 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> Integer.toString((int) value);
            case DOUBLE -> ScalarUtils.formatLikePerl((double) value);
            case STRING, BYTE_STRING -> (String) value;
            case UNDEF -> "";
            case VSTRING -> (String) value;
            case BOOLEAN -> (boolean) value ? "1" : "";
            case GLOB -> value == null ? "" : value.toString();
            case JAVAOBJECT -> value.toString();
            case TIED_SCALAR -> this.tiedFetch().toString();
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).toString();
            case DUALVAR -> ((DualVar) this.value).stringValue().toString();
            case CODE -> Overload.stringify(this).toString();
            default -> {
                if (type == REGEX) yield value.toString();
                // Overload.stringify calls the ("" method. If it returns THIS
                // exact scalar (or another object whose ("" points back here),
                // naively calling .toString() on the result would recurse. Perl
                // falls back to the default reference form in that case; so do
                // we. Detect by identity first, then by depth via a ThreadLocal
                // guard inside Overload.stringify (handles the transitive case).
                RuntimeScalar overloaded = Overload.stringify(this);
                if (overloaded == this) yield toStringRef();
                yield overloaded.toString();
            }
        };
    }

    /**
     * Convert to string without triggering overload dispatch.
     * Used by {@code no overloading} pragma to bypass the {@code ""} overload.
     * For blessed references, returns the raw "Class=TYPE(0xADDR)" string directly.
     */
    public String toStringNoOverload() {
        if (type == STRING || type == BYTE_STRING) {
            return (String) this.value;
        }
        return switch (type) {
            case INTEGER -> Integer.toString((int) value);
            case DOUBLE -> ScalarUtils.formatLikePerl((double) value);
            case STRING, BYTE_STRING -> (String) value;
            case UNDEF -> "";
            case VSTRING -> (String) value;
            case BOOLEAN -> (boolean) value ? "1" : "";
            case GLOB -> value == null ? "" : value.toString();
            case JAVAOBJECT -> value.toString();
            case TIED_SCALAR -> this.tiedFetch().toStringNoOverload();
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).toStringNoOverload();
            case DUALVAR -> ((DualVar) this.value).stringValue().toStringNoOverload();
            case CODE -> toStringRef();
            default -> {
                if (type == REGEX) yield value.toString();
                yield toStringRef();
            }
        };
    }

    public String toStringRef() {
        String ref = switch (type) {
            case UNDEF -> "SCALAR(0x" + scalarUndef.hashCode() + ")";
            case CODE -> {
                if (value == null) {
                    yield "CODE(0x" + scalarUndef.hashCode() + ")";
                }
                yield ((RuntimeCode) value).toStringRef();
            }
            case GLOB -> {
                if (value == null) {
                    yield "GLOB(0x" + scalarUndef.hashCode() + ")";
                }
                yield ((RuntimeGlob) value).toStringRef();
            }
            case VSTRING -> "VSTRING(0x" + value.hashCode() + ")";
            case ARRAYREFERENCE -> {
                if (value == null) {
                    yield "ARRAY(0x" + scalarUndef.hashCode() + ")";
                }
                yield ((RuntimeArray) value).toStringRef();
            }
            case HASHREFERENCE -> {
                if (value == null) {
                    yield "HASH(0x" + scalarUndef.hashCode() + ")";
                }
                yield ((RuntimeHash) value).toStringRef();
            }
            case GLOBREFERENCE -> {
                if (value == null) {
                    yield "GLOB(0x" + scalarUndef.hashCode() + ")";
                }
                yield ((RuntimeBase) value).toStringRef();
            }
            case REFERENCE -> {
                // Determine the proper type name for the reference
                // References to references show as "REF", references to plain scalars show as "SCALAR"
                String typeName = "SCALAR";
                int valueBlessId = 0;
                if (value instanceof RuntimeScalar scalar) {
                    valueBlessId = ((RuntimeBase) value).blessId;
                    typeName = switch (scalar.type) {
                        case VSTRING -> "VSTRING";
                        case REGEX, ARRAYREFERENCE, HASHREFERENCE, CODE, GLOBREFERENCE, REFERENCE -> "REF";
                        case GLOB -> "GLOB";
                        default -> "SCALAR";
                    };
                }
                String refStr = typeName + "(0x" + Integer.toHexString(value.hashCode()) + ")";
                // For REFERENCE type, the blessId is on the value (referent), not on the
                // reference itself. We handle it here; the outer blessId check is skipped
                // for REFERENCE type to avoid double-prepending the class name for circular
                // self-references like: $x = bless \$x, 'Foo'
                yield (valueBlessId == 0 ? refStr : NameNormalizer.getBlessStr(valueBlessId) + "=" + refStr);
            }
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).toStringRef();
            default -> "SCALAR(0x" + Integer.toHexString(value.hashCode()) + ")";
        };
        // Only apply outer blessId for non-REFERENCE types.
        // REFERENCE type already handles blessing through valueBlessId above.
        if (type == REFERENCE) {
            return ref;
        }
        return (blessId == 0 ? ref : NameNormalizer.getBlessStr(blessId) + "=" + ref);
    }

    public int getIntRef() {
        return this.hashCode();
    }

    public double getDoubleRef() {
        return this.hashCode();
    }

    public boolean getBooleanRef() {
        return true;
    }

    // Method to implement `$v->{key}`
    public RuntimeScalar hashDerefGet(RuntimeScalar index) {
        return this.hashDeref().get(index);
    }

    // Method to implement `$v->{key}`, when "no strict refs" is in effect
    public RuntimeScalar hashDerefGetNonStrict(RuntimeScalar index, String packageName) {
        return this.hashDerefNonStrict(packageName).get(index);
    }

    // Method to implement `local $v->{key}` - returns a proxy that survives hash reassignment
    public RuntimeScalar hashDerefGetForLocal(RuntimeScalar index) {
        return this.hashDeref().getForLocal(index);
    }

    // Method to implement `local $v->{key}`, when "no strict refs" is in effect
    public RuntimeScalar hashDerefGetForLocalNonStrict(RuntimeScalar index, String packageName) {
        return this.hashDerefNonStrict(packageName).getForLocal(index);
    }

    // Method to implement `delete $v->{key}`
    public RuntimeScalar hashDerefDelete(RuntimeScalar index) {
        return this.hashDeref().delete(index);
    }

    // Method to implement `delete $v->{key}`, when "no strict refs" is in effect
    public RuntimeScalar hashDerefDeleteNonStrict(RuntimeScalar index, String packageName) {
        return this.hashDerefNonStrict(packageName).delete(index);
    }

    // Method to implement `delete local $v->{key}`
    public RuntimeScalar hashDerefDeleteLocal(RuntimeScalar index) {
        return this.hashDeref().deleteLocal(index);
    }

    // Method to implement `delete local $v->{key}`, when "no strict refs" is in effect
    public RuntimeScalar hashDerefDeleteLocalNonStrict(RuntimeScalar index, String packageName) {
        return this.hashDerefNonStrict(packageName).deleteLocal(index);
    }

    // Method to implement `exists $v->{key}`
    public RuntimeScalar hashDerefExists(RuntimeScalar index) {
        return this.hashDeref().exists(index);
    }

    // Method to implement `exists $v->{key}`, when "no strict refs" is in effect
    public RuntimeScalar hashDerefExistsNonStrict(RuntimeScalar index, String packageName) {
        return this.hashDerefNonStrict(packageName).exists(index);
    }

    // Method to implement `$v->[10]`
    public RuntimeScalar arrayDerefGet(RuntimeScalar index) {
        return this.arrayDeref().get(index);
    }

    // Method to implement `$v->[10]`, when "no strict refs" is in effect
    public RuntimeScalar arrayDerefGetNonStrict(RuntimeScalar index, String packageName) {
        return this.arrayDerefNonStrict(packageName).get(index);
    }

    // Method to implement `$v->[10, 20]` (slice)
    public RuntimeList arrayDerefGetSlice(RuntimeList indices) {
        return this.arrayDeref().getSlice(indices);
    }

    // Method to implement `$v->[10, 20]` (slice), when "no strict refs" is in effect
    public RuntimeList arrayDerefGetSliceNonStrict(RuntimeList indices, String packageName) {
        return this.arrayDerefNonStrict(packageName).getSlice(indices);
    }

    // Method to implement `delete $v->[10]`
    public RuntimeScalar arrayDerefDelete(RuntimeScalar index) {
        return this.arrayDeref().delete(index);
    }

    // Method to implement `delete $v->[10]`, when "no strict refs" is in effect
    public RuntimeScalar arrayDerefDeleteNonStrict(RuntimeScalar index, String packageName) {
        return this.arrayDerefNonStrict(packageName).delete(index);
    }

    // Method to implement `delete local $v->[10]`
    public RuntimeScalar arrayDerefDeleteLocal(RuntimeScalar index) {
        return this.arrayDeref().deleteLocal(index);
    }

    // Method to implement `delete local $v->[10]`, when "no strict refs" is in effect
    public RuntimeScalar arrayDerefDeleteLocalNonStrict(RuntimeScalar index, String packageName) {
        return this.arrayDerefNonStrict(packageName).deleteLocal(index);
    }

    // Method to implement `exists $v->[10]`
    public RuntimeScalar arrayDerefExists(RuntimeScalar index) {
        return this.arrayDeref().exists(index);
    }

    // Method to implement `exists $v->[10]`, when "no strict refs" is in effect
    public RuntimeScalar arrayDerefExistsNonStrict(RuntimeScalar index, String packageName) {
        return this.arrayDerefNonStrict(packageName).exists(index);
    }

    // Method to implement `@$v`
    public RuntimeArray arrayDeref() {
        // Check if object is eligible for overloading
        int blessId = blessedId(this);
        if (blessId < 0) {
            // Prepare overload context and check if object is eligible for overloading
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try overload method
                RuntimeScalar result = ctx.tryOverload("(@{}", new RuntimeArray(this));
                // If the subroutine returns the object itself then it will not be called again
                if (result != null && result.value.hashCode() != this.value.hashCode()) {
                    return result.arrayDeref();
                }
            }
        }

        // ARRAYREFERENCE is first as the most common case
        if (type == ARRAYREFERENCE) {
            return (RuntimeArray) value;
        }

        // Cases 0-11 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> // 0
                // Under strict refs, dereferencing a non-readonly numeric scalar as an ARRAY ref
                // is a strict-refs violation. (Read-only constants like `1->[0]` take the
                // RuntimeScalarReadOnly.arrayDerefGet() override instead and stay quiet.)
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as an ARRAY ref while \"strict refs\" in use");
            case DOUBLE -> // 1
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as an ARRAY ref while \"strict refs\" in use");
            case STRING -> // 2
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as an ARRAY ref while \"strict refs\" in use");
            case BYTE_STRING -> // 3
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as an ARRAY ref while \"strict refs\" in use");
            case UNDEF -> { // 4
                // Don't autovivify read-only scalars (like constants)
                // This matches Perl's behavior where 1->[0] returns undef without error
                if (this instanceof RuntimeScalarReadOnly) {
                    yield new RuntimeArray();
                }
                RuntimeArray arr = AutovivificationArray.createAutovivifiedArray(this);
                arr.strictAutovivify = true;
                yield arr;
            }
            case VSTRING -> // 5
                    throw new PerlCompilerException("Not an ARRAY reference");
            case BOOLEAN -> // 6
                    throw new PerlCompilerException("Not an ARRAY reference");
            case GLOB -> { // 7
                // When dereferencing a typeglob as an array, return the array slot.
                // PVIO (e.g. *STDOUT{IO}) is also represented with type GLOB but holds a RuntimeIO.
                if (value instanceof RuntimeIO) {
                    throw new PerlCompilerException("Not an ARRAY reference");
                }
                RuntimeGlob glob = (RuntimeGlob) value;
                // For anonymous globs, use the getGlobArray method which handles local slots
                yield glob.getGlobArray();
            }
            case JAVAOBJECT -> // 8
                    throw new PerlCompilerException("Not an ARRAY reference");
            case TIED_SCALAR -> { // 9
                RuntimeScalar fetched = tiedFetch();
                if (fetched.type == RuntimeScalarType.UNDEF) {
                    // Autovivify: create array ref, store back to tied var, re-fetch
                    RuntimeArray arr = new RuntimeArray();
                    arr.strictAutovivify = true;
                    tiedStore(arr.createReference());
                    yield arr;
                }
                yield fetched.arrayDeref();
            }
            case DUALVAR -> // 10
                    throw new PerlCompilerException("Not an ARRAY reference");
            case FORMAT -> // 11
                    throw new PerlCompilerException("Not an ARRAY reference");
            case READONLY_SCALAR -> // 12
                    ((RuntimeScalar) this.value).arrayDeref();
            default -> throw new PerlCompilerException("Not an ARRAY reference");
        };
    }

    /**
     * Dereferences this scalar as a hash reference using the `%$v` operator.
     *
     * <p>This method implements Perl's hash dereference operator `%$v`, which treats
     * the scalar as a hash reference and returns the underlying hash. If the scalar
     * is undefined, it performs autovivification by creating a new hash and converting
     * this scalar into a reference to that hash.
     *
     * <p><b>Autovivification:</b> When dereferencing an undefined scalar as a hash,
     * Perl automatically creates a new hash and makes the scalar a reference to it.
     * This is implemented using a {@link AutovivificationHash} that intercepts the first write
     * operation to trigger the conversion.
     *
     * <p><b>Overloading:</b> If the scalar is a blessed object, this method first
     * checks for overloaded hash dereference behavior using the `(%{}` overload key.
     *
     * @return The dereferenced RuntimeHash object
     * @throws PerlCompilerException if the scalar contains a string (when strict refs
     *                               is in use) or any other non-hash-reference value
     */
    public RuntimeHash hashDeref() {
        // Check if object is eligible for overloading (blessed objects)
        int blessId = blessedId(this);
        if (blessId < 0) {
            // Prepare overload context and check if object is eligible for overloading
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try to call the overloaded hash dereference method `(%{}`
                RuntimeScalar result = ctx.tryOverload("(%{}", new RuntimeArray(this));
                // If the overload method returns a different object (not self),
                // recursively dereference the returned value
                // This prevents infinite recursion when the overload returns the same object
                if (result != null && result.value.hashCode() != this.value.hashCode()) {
                    return result.hashDeref();
                }
            }
        }

        // HASHREFERENCE is first as the most common case
        if (type == HASHREFERENCE) {
            return (RuntimeHash) value;
        }

        // Cases 0-11 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> // 0
                // Under strict refs, dereferencing a non-readonly numeric scalar as a HASH ref
                // is a strict-refs violation. (Read-only constants like `1->{a}` take the
                // RuntimeScalarReadOnly.hashDerefGet() override instead.)
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as a HASH ref while \"strict refs\" in use");
            case DOUBLE -> // 1
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as a HASH ref while \"strict refs\" in use");
            case STRING -> // 2
                // Strict refs violation: attempting to use a string as a hash ref
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as a HASH ref while \"strict refs\" in use");
            case BYTE_STRING -> // 3
                // Strict refs violation: attempting to use a string as a hash ref
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as a HASH ref while \"strict refs\" in use");
            case UNDEF -> // 4
                    AutovivificationHash.createAutovivifiedHash(this);
            case VSTRING -> // 5
                    throw new PerlCompilerException("Not a HASH reference");
            case BOOLEAN -> // 6
                    throw new PerlCompilerException("Not a HASH reference");
            case GLOB -> { // 7
                // When dereferencing a typeglob as a hash, return the hash slot.
                // PVIO (e.g. *STDOUT{IO}) is also represented with type GLOB but holds a RuntimeIO.
                if (value instanceof RuntimeIO) {
                    throw new PerlCompilerException("Not a HASH reference");
                }
                RuntimeGlob glob = (RuntimeGlob) value;
                // For anonymous globs, use the getGlobHash method which handles local slots
                yield glob.getGlobHash();
            }
            case JAVAOBJECT -> // 8
                    throw new PerlCompilerException("Not a HASH reference");
            case TIED_SCALAR -> { // 9
                RuntimeScalar fetched = tiedFetch();
                if (fetched.type == RuntimeScalarType.UNDEF) {
                    // Autovivify: create hash ref, store back to tied var
                    RuntimeHash hash = new RuntimeHash();
                    tiedStore(hash.createReference());
                    yield hash;
                }
                yield fetched.hashDeref();
            }
            case FORMAT -> // 11
                    throw new PerlCompilerException("Not a HASH reference");
            case READONLY_SCALAR -> // 12
                    ((RuntimeScalar) this.value).hashDeref();
            default -> throw new PerlCompilerException("Not a HASH reference");
        };
    }

    // Method to implement `$$v`
    public RuntimeScalar scalarDeref() {
        // Check if object is eligible for overloading
        int blessId = blessedId(this);
        if (blessId < 0) {
            // Prepare overload context and check if object is eligible for overloading
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try overload method
                RuntimeScalar result = ctx.tryOverload("(${}", new RuntimeArray(this));
                // If the subroutine returns the object itself then it will not be called again
                if (result != null && result.value.hashCode() != this.value.hashCode()) {
                    return result.scalarDeref();
                }
            }
        }

        return switch (type) {
            case UNDEF -> {
                // Autovivify: create a new scalar reference for undefined values
                RuntimeScalar newScalar = new RuntimeScalar();
                this.value = newScalar;
                this.type = RuntimeScalarType.REFERENCE;
                yield newScalar;
            }
            case REFERENCE -> (RuntimeScalar) value;
            case REGEX -> {
                // Dereferencing a Regexp (qr//) returns its stringified form
                // In Perl, ${qr/foo/} returns "(?^:foo)"
                RuntimeScalar result = new RuntimeScalar();
                result.type = RuntimeScalarType.STRING;
                result.value = this.value.toString();
                yield result;
            }
            case GLOB -> {
                // Dereferencing a glob as scalar returns the scalar slot
                // e.g., ${*Foo::VERSION} or ${$glob} where $glob is a glob
                if (value instanceof RuntimeGlob glob) {
                    // Use the glob's getGlobSlot method which handles anonymous globs
                    yield glob.getGlobSlot(new RuntimeScalar("SCALAR"));
                }
                throw new PerlCompilerException("Not a SCALAR reference");
            }
            case STRING, BYTE_STRING ->
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as a SCALAR ref while \"strict refs\" in use");
            case TIED_SCALAR -> tiedFetch().scalarDeref();
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).scalarDeref();
            case GLOBREFERENCE -> {
                // Dereferencing a glob reference (e.g., $${\*Foo::bar}) returns the glob itself
                // This is Perl semantics: $$glob_ref returns the glob, not the scalar slot
                if (value instanceof RuntimeGlob glob) {
                    RuntimeScalar result = new RuntimeScalar();
                    result.type = RuntimeScalarType.GLOB;
                    result.value = glob;
                    yield result;
                }
                // For IO handles stored as GLOBREFERENCE (like *STDOUT{IO})
                throw new PerlCompilerException("Not a SCALAR reference");
            }
            default -> throw new PerlCompilerException("Not a SCALAR reference");
        };
    }

    // Method to implement `$$v`, when "no strict refs" is in effect
    public RuntimeScalar scalarDerefNonStrict(String packageName) {
        // Check if object is eligible for overloading
        int blessId = blessedId(this);
        if (blessId < 0) {
            // Prepare overload context and check if object is eligible for overloading
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try overload method
                RuntimeScalar result = ctx.tryOverload("(${}", new RuntimeArray(this));
                // If the subroutine returns the object itself then it will not be called again
                if (result != null && result.value.hashCode() != this.value.hashCode()) {
                    return result.scalarDerefNonStrict(packageName);
                }
            }
        }

        return switch (type) {
            case REFERENCE -> (RuntimeScalar) value;
            case GLOB -> {
                // Dereferencing a glob as scalar returns the scalar slot
                if (value instanceof RuntimeGlob glob) {
                    // Use the glob's getGlobSlot method which handles anonymous globs
                    yield glob.getGlobSlot(new RuntimeScalar("SCALAR"));
                }
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalVariable(varName);
            }
            case GLOBREFERENCE -> {
                // Dereferencing a glob reference (e.g., $${\*Foo::bar}) returns the glob itself
                // This is Perl semantics: $$glob_ref returns the glob, not the scalar slot
                if (value instanceof RuntimeGlob glob) {
                    RuntimeScalar result = new RuntimeScalar();
                    result.type = RuntimeScalarType.GLOB;
                    result.value = glob;
                    yield result;
                }
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalVariable(varName);
            }
            case TIED_SCALAR -> tiedFetch().scalarDerefNonStrict(packageName);
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).scalarDerefNonStrict(packageName);
            default -> {
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalVariable(varName);
            }
        };
    }

    // Method to implement `%$v`, when "no strict refs" is in effect
    public RuntimeHash hashDerefNonStrict(String packageName) {
        // Check if object is eligible for overloading
        int blessId = blessedId(this);
        if (blessId < 0) {
            // Prepare overload context and check if object is eligible for overloading
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try overload method
                RuntimeScalar result = ctx.tryOverload("(%{}", new RuntimeArray(this));
                // If the subroutine returns the object itself then it will not be called again
                if (result != null && result.value.hashCode() != this.value.hashCode()) {
                    return result.hashDerefNonStrict(packageName);
                }
            }
        }

        // HASHREFERENCE is first as the most common case
        if (type == HASHREFERENCE) {
            return (RuntimeHash) value;
        }

        // Cases 0-11 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER, DOUBLE, BOOLEAN, DUALVAR -> { // 0, 1, 6, 10
                // Symbolic reference: convert number to string and treat as variable name
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalHash(varName);
            }
            case STRING -> { // 2
                // Symbolic reference: treat the scalar's string value as a variable name
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalHash(varName);
            }
            case BYTE_STRING -> { // 3
                // Symbolic reference: treat the scalar's string value as a variable name
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalHash(varName);
            }
            case UNDEF -> { // 4
                // Don't autovivify read-only scalars (like constants)
                if (this instanceof RuntimeScalarReadOnly) {
                    yield new RuntimeHash();
                }
                yield AutovivificationHash.createAutovivifiedHash(this);
            }
            case VSTRING -> // 5
                    throw new PerlCompilerException("Not a HASH reference");
            case GLOB -> { // 7
                // When dereferencing a typeglob as a hash, return the hash slot
                RuntimeGlob glob = (RuntimeGlob) value;
                // For anonymous globs, use the getGlobHash method which handles local slots
                yield glob.getGlobHash();
            }
            case JAVAOBJECT -> // 8
                    throw new PerlCompilerException("Not a HASH reference");
            case TIED_SCALAR -> { // 9
                RuntimeScalar fetched = tiedFetch();
                if (fetched.type == RuntimeScalarType.UNDEF) {
                    RuntimeHash hash = new RuntimeHash();
                    tiedStore(hash.createReference());
                    yield hash;
                }
                yield fetched.hashDerefNonStrict(packageName);
            }
            case FORMAT -> // 11
                    throw new PerlCompilerException("Not a HASH reference");
            case READONLY_SCALAR -> // 12
                    ((RuntimeScalar) this.value).hashDerefNonStrict(packageName);
            default -> throw new PerlCompilerException("Not a HASH reference");
        };
    }

    // Method to implement `@$v`, when "no strict refs" is in effect
    public RuntimeArray arrayDerefNonStrict(String packageName) {
        // Check if object is eligible for overloading
        int blessId = blessedId(this);
        if (blessId < 0) {
            // Prepare overload context and check if object is eligible for overloading
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try overload method
                RuntimeScalar result = ctx.tryOverload("(@{}", new RuntimeArray(this));
                // If the subroutine returns the object itself then it will not be called again
                if (result != null && result.value.hashCode() != this.value.hashCode()) {
                    return result.arrayDerefNonStrict(packageName);
                }
            }
        }

        // ARRAYREFERENCE is first as the most common case
        if (type == ARRAYREFERENCE) {
            return (RuntimeArray) value;
        }

        // Cases 0-11 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER, DOUBLE, BOOLEAN, DUALVAR -> { // 0, 1, 6, 10
                // Symbolic reference: convert number to string and treat as variable name
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalArray(varName);
            }
            case STRING -> { // 2
                // Symbolic reference: treat the scalar's string value as a variable name
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalArray(varName);
            }
            case BYTE_STRING -> { // 3
                // Symbolic reference: treat the scalar's string value as a variable name
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalArray(varName);
            }
            case UNDEF -> { // 4
                // Don't autovivify read-only scalars (like constants)
                // This matches Perl's behavior where 1->[0] returns undef without error
                if (this instanceof RuntimeScalarReadOnly) {
                    yield new RuntimeArray();
                }
                yield AutovivificationArray.createAutovivifiedArray(this);
            }
            case VSTRING -> // 5
                    throw new PerlCompilerException("Not an ARRAY reference");
            case GLOB -> { // 7
                // When dereferencing a typeglob as an array, return the array slot
                RuntimeGlob glob = (RuntimeGlob) value;
                // For anonymous globs, use the getGlobArray method which handles local slots
                yield glob.getGlobArray();
            }
            case JAVAOBJECT -> // 8
                    throw new PerlCompilerException("Not an ARRAY reference");
            case TIED_SCALAR -> { // 9
                RuntimeScalar fetched = tiedFetch();
                if (fetched.type == RuntimeScalarType.UNDEF) {
                    RuntimeArray arr = new RuntimeArray();
                    arr.strictAutovivify = true;
                    tiedStore(arr.createReference());
                    yield arr;
                }
                yield fetched.arrayDerefNonStrict(packageName);
            }
            case READONLY_SCALAR -> // 12
                    ((RuntimeScalar) this.value).arrayDerefNonStrict(packageName);
            default -> throw new PerlCompilerException("Not an ARRAY reference");
        };
    }

    // Method to implement `*$v`
    public RuntimeGlob globDeref() {
        // Check if object is eligible for overloading
        int blessId = blessedId(this);
        if (blessId < 0) {
            // Prepare overload context and check if object is eligible for overloading
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try overload method
                RuntimeScalar result = ctx.tryOverload("(*{}", new RuntimeArray(this));
                // If the subroutine returns the object itself then it will not be called again
                if (result != null && result.value.hashCode() != this.value.hashCode()) {
                    return result.globDeref();
                }
            }
        }

        return switch (type) {
            case TIED_SCALAR -> tiedFetch().globDeref();
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).globDeref();
            case UNDEF -> throw new PerlCompilerException("Can't use an undefined value as a GLOB reference");
            case GLOBREFERENCE -> {
                // Some internal representations store PVIO as GLOBREFERENCE with a RuntimeIO value.
                if (value instanceof RuntimeIO io) {
                    if (io.globName != null) {
                        RuntimeGlob actual = GlobalVariable.getExistingGlobalIO(io.globName);
                        if (actual != null) {
                            yield actual;
                        }
                    }
                    RuntimeGlob tmp = new RuntimeGlob("__ANON__::__ANONIO__");
                    tmp.setIO(io);
                    yield tmp;
                }
                yield (RuntimeGlob) value;
            }
            case GLOB -> {
                // PVIO (like *STDOUT{IO}) is stored as type GLOB with a RuntimeIO value.
                // Perl allows postfix glob deref (->**) of PVIO by creating a temporary glob
                // with the IO slot set to that handle.
                if (value instanceof RuntimeIO io) {
                    // If the IO has a known glob name (e.g., "main::STDOUT"), look up the
                    // actual global glob so that operations like tie *{select()}, 'Class'
                    // affect the real handle, not a temporary copy.
                    if (io.globName != null) {
                        RuntimeGlob actual = GlobalVariable.getExistingGlobalIO(io.globName);
                        if (actual != null) {
                            yield actual;
                        }
                    }
                    RuntimeGlob tmp = new RuntimeGlob("__ANON__::__ANONIO__");
                    tmp.setIO(io);
                    yield tmp;
                }
                // When glob-dereferencing a stash entry, return a plain RuntimeGlob.
                // This prevents *{$stash->{name}} = \$scalar from creating PCS constant subs.
                // PCS (Proxy Constant Subroutine) creation should only happen via direct
                // stash hash assignment ($stash->{name} = \$scalar), handled by RuntimeStashEntry.set().
                if (value instanceof RuntimeStashEntry stashEntry) {
                    yield new RuntimeGlob(stashEntry.globName);
                }
                yield (RuntimeGlob) value;
            }
            case STRING, BYTE_STRING ->
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as a symbol ref while \"strict refs\" in use");
            default -> throw new PerlCompilerException("Not a GLOB reference");
        };
    }

    // Method to implement `*$v`, when "no strict refs" is in effect
    public RuntimeGlob globDerefNonStrict(String packageName) {
        // Check if object is eligible for overloading
        int blessId = blessedId(this);
        if (blessId < 0) {
            // Prepare overload context and check if object is eligible for overloading
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try overload method
                RuntimeScalar result = ctx.tryOverload("(*{}", new RuntimeArray(this));
                // If the subroutine returns the object itself then it will not be called again
                if (result != null && result.value.hashCode() != this.value.hashCode()) {
                    return result.globDerefNonStrict(packageName);
                }
            }
        }

        return switch (type) {
            case TIED_SCALAR -> tiedFetch().globDerefNonStrict(packageName);
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).globDerefNonStrict(packageName);
            case GLOBREFERENCE -> {
                // Some internal representations store PVIO as GLOBREFERENCE with a RuntimeIO value.
                if (value instanceof RuntimeIO io) {
                    if (io.globName != null) {
                        RuntimeGlob actual = GlobalVariable.getExistingGlobalIO(io.globName);
                        if (actual != null) {
                            yield actual;
                        }
                    }
                    RuntimeGlob tmp = new RuntimeGlob("__ANON__::__ANONIO__");
                    tmp.setIO(io);
                    yield tmp;
                }
                yield (RuntimeGlob) value;
            }
            case GLOB -> {
                // PVIO (like *STDOUT{IO}) is stored as type GLOB with a RuntimeIO value.
                // Perl allows postfix glob deref (->**) of PVIO by creating a temporary glob
                // with the IO slot set to that handle.
                if (value instanceof RuntimeIO io) {
                    // If the IO has a known glob name (e.g., "main::STDOUT"), look up the
                    // actual global glob so that operations like tie *{select()}, 'Class'
                    // affect the real handle, not a temporary copy.
                    if (io.globName != null) {
                        RuntimeGlob actual = GlobalVariable.getExistingGlobalIO(io.globName);
                        if (actual != null) {
                            yield actual;
                        }
                    }
                    RuntimeGlob tmp = new RuntimeGlob("__ANON__::__ANONIO__");
                    tmp.setIO(io);
                    yield tmp;
                }
                // When glob-dereferencing a stash entry, return a plain RuntimeGlob.
                // This prevents *{$stash->{name}} = \$scalar from creating PCS constant subs.
                if (value instanceof RuntimeStashEntry stashEntry) {
                    yield new RuntimeGlob(stashEntry.globName);
                }
                yield (RuntimeGlob) value;
            }
            default -> {
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                // Use the canonical glob object for this symbol name.
                // This ensures the IO slot is shared/visible across operations like:
                //   *{"\3"} = *DATA; readline v3
                // where readline resolves the handle via GlobalVariable.getGlobalIO("main::\x03").
                yield GlobalVariable.getGlobalIO(varName);
            }
        };
    }

    // Method to implement `&$v`, when "no strict refs" is in effect
    // This looks up the CODE slot from a glob when the scalar contains a string
    public RuntimeScalar codeDerefNonStrict(String packageName) {
        // Check if object is eligible for overloading
        int blessId = blessedId(this);
        if (blessId < 0) {
            // Prepare overload context and check if object is eligible for overloading
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try overload method
                RuntimeScalar result = ctx.tryOverload("(&{}", new RuntimeArray(this));
                // If the subroutine returns the object itself then it will not be called again
                if (result != null && result.value.hashCode() != this.value.hashCode()) {
                    return result.codeDerefNonStrict(packageName);
                }
            }
        }

        return switch (type) {
            case TIED_SCALAR -> tiedFetch().codeDerefNonStrict(packageName);
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).codeDerefNonStrict(packageName);
            case CODE -> this;  // Already a CODE reference - return unchanged
            case UNDEF -> this; // UNDEF - return unchanged to preserve error behavior
            case REFERENCE -> {
                // Dereference and check if it's a CODE reference
                RuntimeScalar deref = (RuntimeScalar) this.value;
                if (deref.type == RuntimeScalarType.CODE) {
                    yield deref;
                }
                // Not a CODE reference - fall through to symbolic reference handling
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalCodeRef(varName);
            }
            case GLOB, GLOBREFERENCE -> {
                // Get the CODE slot from the glob
                RuntimeGlob glob = (RuntimeGlob) value;
                // For detached globs (null globName, from stash delete), use local code slot
                if (glob.globName == null) {
                    yield glob.codeSlot != null ? glob.codeSlot : new RuntimeScalar();
                }
                yield GlobalVariable.getGlobalCodeRef(glob.globName);
            }
            default -> {
                // Symbolic reference: treat the scalar's string value as a subroutine name
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalCodeRef(varName);
            }
        };
    }

    // Return a reference to this scalar.
    //
    // Special case for GLOB-typed scalars: when a glob passes through @_,
    // array storage, or other copy operations, the RuntimeGlob is wrapped
    // inside a RuntimeScalar (type=GLOB, value=RuntimeGlob).  Java virtual
    // dispatch then calls THIS method instead of RuntimeGlob.createReference().
    //
    // In Perl 5, \*glob always produces a glob reference:
    //   ref(\*FH)                      → "GLOB"
    //   UNIVERSAL::isa(\*FH, 'GLOB')   → 1
    //
    // We return type=REFERENCE with value=this (the RuntimeScalar).
    // ReferenceOperators.ref() already handles this: when a REFERENCE points
    // to a RuntimeScalar with type GLOB, it returns "GLOB".
    // Universal.isa() also handles this for unblessed refs.
    //
    // We deliberately do NOT set type=GLOBREFERENCE here because that would
    // store the RuntimeGlob directly, losing the reference to this container.
    // Internals::SvREADONLY needs the container to set/get readonly status.
    public RuntimeScalar createReference() {
        RuntimeScalar result = new RuntimeScalar();
        result.type = RuntimeScalarType.REFERENCE;
        result.value = this;
        return result;
    }

    public RuntimeScalar undefine() {
        // Special handling for CODE type - don't set the ref to undef,
        // just clear the code from the global symbol table
        if (type == RuntimeScalarType.CODE && value instanceof RuntimeCode code) {
            // Release captured variables before discarding this CODE ref
            code.releaseCaptures();
            // Clear the code value but keep the type as CODE
            this.value = new RuntimeCode((String) null, null);
            // Invalidate the method resolution cache
            InheritanceResolver.invalidateCache();
            return this;
        }

        // Decrement refCount for blessed references with DESTROY or weakly-tracked refs
        RuntimeBase oldBase = null;
        if ((this.type & RuntimeScalarType.REFERENCE_BIT) != 0 && this.value instanceof RuntimeBase base
                && base.refCount != -1 && base.refCount != Integer.MIN_VALUE) {
            oldBase = base;
        }

        // Close IO handles when dropping a glob reference.
        // This mimics Perl's internal sv_clear behavior where IO handles are closed
        // when the glob's reference count drops to zero (independent of DESTROY).
        closeIOOnDrop();
        // For all other types, set to undef
        this.type = UNDEF;
        this.value = null;

        // Decrement AFTER clearing (Perl 5 semantics: DESTROY sees the new state)
        boolean undefOnBlessedWithDestroy = false;
        if (oldBase != null) {
            if (oldBase.refCount == WeakRefRegistry.WEAKLY_TRACKED) {
                // Weakly-tracked object (unblessed, birth-tracked, with weak refs):
                // clear weak refs on explicit undef. These objects transitioned to
                // WEAKLY_TRACKED in weaken() because their refCount was unreliable
                // (closure captures bypass setLarge). Clearing on undef is a heuristic
                // but safe since unblessed objects have no DESTROY.
                oldBase.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(oldBase);
            } else if (this.refCountOwned && oldBase.refCount > 0) {
                this.refCountOwned = false;
                if (--oldBase.refCount == 0) {
                    if (oldBase.localBindingExists) {
                        // Named container: local variable may still exist. Skip callDestroy.
                    } else {
                        oldBase.refCount = Integer.MIN_VALUE;
                        DestroyDispatch.callDestroy(oldBase);
                        // Phase H (t/storage/error.t test 49): if the DESTROY self-
                        // saved the object (rescued), user's explicit undef still
                        // means their lexical handle is gone — weak refs pointing
                        // to the rescued object (e.g. HandleError closure's weak
                        // $schema) must be cleared so callbacks that fire AFTER
                        // this point can detect "schema is gone".
                        if (oldBase.blessId != 0 && WeakRefRegistry.weakRefsExist) {
                            String cn = NameNormalizer.getBlessStr(oldBase.blessId);
                            if (cn != null && DestroyDispatch.classHasDestroy(oldBase.blessId, cn)) {
                                undefOnBlessedWithDestroy = true;
                            }
                        }
                    }
                } else if (oldBase.blessId != 0 && oldBase.refCount > 0
                        && WeakRefRegistry.weakRefsExist) {
                    // Phase D: cooperative refCount suggests this object still has
                    // strong references, but those may all be internal cycles
                    // (e.g. DBIC's Schema <-> source_registrations). Defer to the
                    // reachability walker if the class has DESTROY — it's the
                    // canonical decider of liveness once the user has explicitly
                    // released their lexical handle.
                    String cn = NameNormalizer.getBlessStr(oldBase.blessId);
                    if (cn != null && DestroyDispatch.classHasDestroy(oldBase.blessId, cn)) {
                        undefOnBlessedWithDestroy = true;
                    }
                }
            } else if (oldBase.blessId != 0 && WeakRefRegistry.weakRefsExist) {
                // Phase D: no owned-count decrement (refCountOwned was false, or
                // refCount was already 0 from prior cooperative drift). The
                // object is blessed — if its class has DESTROY, let the walker
                // decide whether this undef just released the last live lexical
                // handle.
                String cn = NameNormalizer.getBlessStr(oldBase.blessId);
                if (cn != null && DestroyDispatch.classHasDestroy(oldBase.blessId, cn)) {
                    undefOnBlessedWithDestroy = true;
                }
            }
        }

        // Flush deferred mortal decrements. Without this, pending DECs from
        // scope exit of locals (e.g., `my ($a,$b) = @_` inside a sub) would
        // not be processed until the next setLarge/apply, making the refCount
        // appear inflated at the point of `undef $ref`. This matches Perl 5
        // where FREETMPS runs at statement boundaries.
        MortalList.flush();

        // Phase D: undef-of-blessed auto-trigger for the reachability walker.
        // When the user explicitly undef's a blessed ref with DESTROY but
        // cooperative refCount stays > 0 (internal cycles), ask the walker
        // to determine real reachability. Bypasses the MortalList auto-sweep
        // throttle because this is an explicit release, not an opportunistic
        // check. Skips when we're in module-init to avoid clearing weak refs
        // that require/use chains still depend on.
        if (undefOnBlessedWithDestroy && !ModuleInitGuard.inModuleInit()) {
            if (System.getenv("JPERL_PHASE_D_DBG") != null) {
                System.err.println("DBG Phase D undef-of-blessed trigger for " +
                        (oldBase != null ? org.perlonjava.runtime.runtimetypes.NameNormalizer.getBlessStr(oldBase.blessId) : "?") +
                        " refCount=" + (oldBase != null ? oldBase.refCount : -1));
            }
            ReachabilityWalker.sweepWeakRefs(false);
        }

        return this;
    }

    /**
     * Close any IO handle associated with a GLOBREFERENCE value being dropped.
     * <p>
     * This is called ONLY from explicit user actions ({@code undef $fh} and
     * {@code $fh = undef}), NOT from automatic scope-exit cleanup. For automatic
     * cleanup of abandoned handles, see {@link RuntimeIO#registerGlobForFdRecycling}
     * which uses PhantomReference-based GC tracking.
     * <p>
     * <b>Where this is called:</b>
     * <ul>
     *   <li>{@code undefine()} — when {@code undef $fh} is called explicitly</li>
     *   <li>{@code setLarge(null)} — when a variable is set to null/undef</li>
     * </ul>
     * <p>
     * <b>Where this is NOT called (by design):</b>
     * <ul>
     *   <li>{@code setLarge(value)} for non-null values — variable reassignment
     *       cannot safely close the old IO when other variables may reference
     *       the same glob (see the Capture::Tiny comment in setLarge())</li>
     *   <li>Scope exit — removed because without reference counting there is
     *       no way to know if the glob is shared. See {@code scopeExitCleanup()}
     *       javadoc for the full explanation.</li>
     * </ul>
     * <p>
     * <b>Safety heuristic:</b> We only close IO for globs that are NOT currently
     * in any stash (symbol table). Named globs still in the stash (like *MYFILE)
     * may have other references (including detached copies created by
     * {@code \*MYFILE}) and should not be closed.
     */
    private void closeIOOnDrop() {
        if (type == GLOBREFERENCE && value instanceof RuntimeGlob glob) {
            // If the glob has a name and a stash entry still exists for that name,
            // don't close — the IO may be shared with the stash glob or its copies.
            // Note: \*MYFILE creates a detached copy (different Java object) that
            // shares the IO slot, so identity checks don't work here.
            if (glob.globName != null && GlobalVariable.existsGlobalIO(glob.globName)) {
                return; // Glob name is still in the stash — don't close
            }
            RuntimeScalar ioSlot = glob.getIO();
            if (ioSlot != null && ioSlot.value instanceof RuntimeIO io
                    && !(io.ioHandle instanceof ClosedIOHandle)) {
                io.close();
            }
        }
    }

    /**
     * Called from JVM bytecode at scope exit to eagerly free fd numbers
     * for anonymous lexical filehandles ({@code open(my $fh, ...)}).
     * <p>
     * This only <b>unregisters the fileno</b> (returning the fd number to the
     * recycle pool) — it does NOT close the underlying IO stream. This is safe
     * for shared handles: if another variable references the same RuntimeGlob,
     * the IO stream stays open and functional. If the other reference calls
     * {@code fileno()}, a new fd number is assigned via {@code assignFileno()}.
     * <p>
     * The actual IO close is still handled by PhantomReference-based GC in
     * {@link RuntimeIO#processAbandonedGlobs()}, which only fires when the
     * RuntimeGlob is truly unreachable (no variables reference it).
     * <p>
     * <b>Why we don't close IO here:</b> Without reference counting, there is
     * no way to know if other variables still reference the same RuntimeGlob.
     * Closing IO at scope exit broke Test2::Formatter::TAP and Capture::Tiny
     * (see git history). Unregistering the fd is safe because:
     * <ul>
     *   <li>The IO stream stays open for reading/writing</li>
     *   <li>Other references can still use the handle normally</li>
     *   <li>{@code fileno()} on other references will assign a fresh fd</li>
     * </ul>
     *
     * @param scalar the RuntimeScalar being cleaned up (may be null)
     * @see RuntimeIO#registerGlobForFdRecycling
     * @see RuntimeIO#processAbandonedGlobs()
     */
    public static void scopeExitCleanup(RuntimeScalar scalar) {
        if (scalar == null) return;

        // Fast path: skip if no special state (most common case for integer/string vars).
        // When all three conditions are true, the entire method body is a no-op:
        // - refCountOwned=false → deferDecrementIfTracked returns immediately
        // - captureCount=0 → capture handling branch not taken
        // - ioOwner=false → IO fd recycling branch not taken
        if (!scalar.refCountOwned && scalar.captureCount == 0 && !scalar.ioOwner) {
            // Special case: CODE refs with unreleased captures that were never
            // stored via set() (e.g., anonymous subs passed directly as arguments).
            // These have refCount=0 (from makeCodeObject) and refCountOwned=false
            // (never went through setLargeRefCounted). Without this check,
            // releaseCaptures() would never fire, permanently elevating
            // captureCount on captured variables and leaking blessed objects.
            // The null check on capturedScalars ensures we only fire once
            // (releaseCaptures sets capturedScalars=null to prevent re-entry).
            if (scalar.type == RuntimeScalarType.CODE
                    && scalar.value instanceof RuntimeCode code
                    && code.capturedScalars != null
                    && code.refCount == 0) {
                code.releaseCaptures();
            }
            return;
        }

        // If this variable is captured by a closure, mark it so releaseCaptures
        // knows the scope has exited. But still proceed with refCount cleanup below
        // so that blessed ref refCounts and weak refs are handled properly.
        if (scalar.captureCount > 0) {
            // Self-referential capture cycle detection: if this variable holds
            // a CODE ref that captures this same variable, we have a cycle that
            // will never resolve on its own. This happens when eval STRING creates
            // closures that capture ALL visible lexicals (including the variable
            // the closure is assigned to). Break the cycle by decrementing our own
            // captureCount and removing ourselves from the CODE's captures array.
            // The full release of other captures will happen when the CODE ref's
            // refCount reaches 0 (via callDestroy/releaseCaptures).
            if (scalar.type == RuntimeScalarType.CODE
                    && scalar.value instanceof RuntimeCode code
                    && code.capturedScalars != null) {
                boolean selfRef = false;
                for (RuntimeScalar s : code.capturedScalars) {
                    if (s == scalar) { selfRef = true; break; }
                }
                if (selfRef) {
                    // Decrement our captureCount (the closure captured us)
                    scalar.captureCount--;
                    // Remove self from capturedScalars to prevent double-decrement
                    // when releaseCaptures runs later during CODE ref destruction
                    RuntimeScalar[] old = code.capturedScalars;
                    if (old.length == 1) {
                        code.capturedScalars = null;
                    } else {
                        RuntimeScalar[] updated = new RuntimeScalar[old.length - 1];
                        int j = 0;
                        for (RuntimeScalar cap : old) {
                            if (cap != scalar && j < updated.length) updated[j++] = cap;
                        }
                        code.capturedScalars = updated;
                    }
                }
            }
            // Mark that this variable's scope has exited. When releaseCaptures
            // later decrements captureCount to 0, it will know the scope is gone.
            scalar.scopeExited = true;
            // For CODE refs: still decrement the VALUE's refCount so the RuntimeCode
            // is eventually destroyed and its releaseCaptures fires (decrementing
            // captureCount on all the variables IT captured). This is critical for
            // eval STRING closures that capture all visible lexicals — without this,
            // the inner sub's captures (including $got in cmp_ok) are never released,
            // preventing weak refs from being cleared.
            // For non-CODE refs: do NOT decrement. The closure holds a strong reference
            // to this variable's value, and decrementing would prematurely clear weak
            // refs (breaks Sub::Quote where closures legitimately keep values alive).
            if (scalar.type == RuntimeScalarType.CODE
                    && scalar.value instanceof RuntimeCode) {
                // Fall through to deferDecrementIfTracked below
            } else {
                // For non-CODE blessed refs with DESTROY: register for deferred
                // cleanup after the main script returns. The interpreter captures
                // ALL visible lexicals for eval STRING support, inflating
                // captureCount on variables that closures don't actually use.
                // At inner scope exit we can't decrement (closures in outer scopes
                // may legitimately keep the object alive), but after the main
                // script finishes ALL scopes have exited, so it's safe.
                if (scalar.refCountOwned
                        && (scalar.type & RuntimeScalarType.REFERENCE_BIT) != 0
                        && scalar.value instanceof RuntimeBase rb
                        && rb.blessId != 0) {
                    MortalList.addDeferredCapture(scalar);
                }
                return;
            }
        }

        // NOTE: Do NOT call releaseCaptures() on CODE refs here.
        // When a local variable holding a CODE ref goes out of scope, the
        // RuntimeCode may still be alive in other locations (e.g., a glob's
        // CODE slot installed via *glob = $code, or another variable).
        // Premature releaseCaptures() would decrement captureCount on captured
        // variables, causing those variables' scope exit to add birth-tracked
        // objects to the mortal list and prematurely clear weak refs.
        // Captures are properly released when the CODE ref is overwritten
        // (via setLarge) or undef'd (via undefine).

        // Existing: IO fd recycling for anonymous filehandle globs
        if (scalar.ioOwner && scalar.type == GLOBREFERENCE
                && scalar.value instanceof RuntimeGlob glob
                && glob.globName == null) {
            RuntimeScalar ioSlot = glob.getIO();
            if (ioSlot != null && ioSlot.value instanceof RuntimeIO io
                    && !(io.ioHandle instanceof ClosedIOHandle)) {
                // Only unregister the fd number — do NOT close the IO stream.
                // This frees the fd for reuse while keeping the IO functional
                // for any other variables that reference the same glob.
                io.unregisterFileno();
            }
        }

        // Defer refCount decrement for blessed references with DESTROY.
        // Uses MortalList to defer the decrement until the next safe point
        // (setLarge or RuntimeCode.apply). This prevents premature DESTROY
        // when the same referent is on the JVM stack as a return value.
        MortalList.deferDecrementIfTracked(scalar);

        // WEAKLY_TRACKED objects: do NOT clear weak refs on scope exit.
        // These objects transitioned from untracked (-1) to WEAKLY_TRACKED (-2) in
        // weaken(), but scope exit of ONE reference doesn't mean no other strong
        // references exist — closures may capture copies of the same reference
        // (e.g., Sub::Quote's $_QUOTED capture keeps $quoted_info alive even after
        // unquote_sub's local exits scope). Clearing weak refs here would break
        // Sub::Quote/Moo constructor inlining.
        // Weak refs for WEAKLY_TRACKED objects are cleared only via:
        //   - explicit undefine() of a strong reference
        // Since unblessed objects have no DESTROY, delayed clearing is safe.
    }

    public RuntimeScalar defined() {
        return getScalarBoolean(getDefinedBoolean());
    }

    public boolean getDefinedBoolean() {
        // Cases 0-11 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> true;       // 0
            case DOUBLE -> true;        // 1
            case STRING -> true;        // 2
            case BYTE_STRING -> true;   // 3
            case UNDEF -> false;        // 4
            case VSTRING -> true;       // 5
            case BOOLEAN -> true;       // 6
            case GLOB -> true;          // 7
            case JAVAOBJECT -> true;    // 8
            case TIED_SCALAR -> this.tiedFetch().getDefinedBoolean(); // 9
            case DUALVAR -> ((DualVar) this.value).stringValue().getDefinedBoolean(); // 10
            case FORMAT -> ((RuntimeFormat) value).getDefinedBoolean(); // 11
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).getDefinedBoolean(); // 12
            // Reference types (with REFERENCE_BIT) fall through to default
            default -> type != CODE || ((RuntimeCode) value).defined();
        };
    }

    public RuntimeScalar preAutoIncrement() {
        // Cases 0-11 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        switch (type) {
            case INTEGER -> { // 0
                int intValue = (int) this.value;
                // Check for overflow - upgrade to double if needed
                if (intValue == Integer.MAX_VALUE) {
                    this.type = RuntimeScalarType.DOUBLE;
                    this.value = (double) intValue + 1;
                } else {
                    this.value = intValue + 1;
                }
            }
            case DOUBLE -> // 1
                    this.value = (double) this.value + 1;
            case STRING, BYTE_STRING -> // 2
                    ScalarUtils.stringIncrement(this);
            case UNDEF -> { // 3
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case VSTRING -> { // 4
                ScalarUtils.stringIncrement(this);
                this.type = RuntimeScalarType.STRING;  // ++ flattens vstrings
            }
            case BOOLEAN -> { // 5
                int intVal = (boolean) this.value ? 1 : 0;
                this.type = RuntimeScalarType.INTEGER;
                this.value = intVal + 1;
            }
            case GLOB -> { // 6
                if (this instanceof RuntimeGlob) {
                    throw new PerlCompilerException("Modification of a read-only value attempted");
                }
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case JAVAOBJECT -> { // 7
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case TIED_SCALAR -> { // 8
                RuntimeScalar variable = this.tiedFetch();
                variable.preAutoIncrement();
                this.tiedStore(variable);
                return variable;
            }
            case DUALVAR -> { // 9
                int dualVal = this.getInt();
                this.type = RuntimeScalarType.INTEGER;
                this.value = dualVal + 1;
            }
            case FORMAT -> { // 10
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case READONLY_SCALAR -> // 12
                    throw new PerlCompilerException("Modification of a read-only value attempted");
            default -> { // All reference types (CODE, REFERENCE, ARRAYREFERENCE, etc.)
                // Check if object is eligible for overloading
                int blessId = blessedId(this);
                if (blessId < 0) {
                    // Prepare overload context and check if object is eligible for overloading
                    OverloadContext ctx = OverloadContext.prepare(blessId);
                    if (ctx != null) {
                        // Copy-on-write: If the object has the = overload, call it to create
                        // a copy BEFORE any mutation. This implements Perl's COW semantics
                        // where shared references are copied before modification.
                        // Example: my $b = $a; $b++; should NOT modify $a
                        RuntimeScalar copyResult = ctx.tryOverload("(=", new RuntimeArray(this));
                        if (copyResult != null) {
                            // Copy the cloned object's fields into this
                            this.type = copyResult.type;
                            this.value = copyResult.value;
                        }

                        // Try direct overload method for ++
                        RuntimeScalar result = ctx.tryOverload("(++", new RuntimeArray(this));
                        if (result != null) {
                            // The ++ operator has already modified this operand
                            // Just return this (which has been modified)
                            return this;
                        }

                        // Try fallback to + operator with undef as third argument (mutator indicator)
                        result = ctx.tryOverload("(+", new RuntimeArray(this, scalarOne, scalarUndef));
                        if (result != null) {
                            // For fallback, + should NOT modify operand, so we handle assignment
                            this.type = result.type;
                            this.value = result.value;
                            return this;
                        }
                    }
                }

                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
        }
        return this;
    }

    // Inlineable fast path for $v++
    public RuntimeScalar postAutoIncrement() {
        if (this.type == INTEGER) {
            int intValue = (int) this.value;
            if (intValue < Integer.MAX_VALUE) {
                this.value = intValue + 1;
                return new RuntimeScalar(intValue); // return old value directly
            }
        }
        return postAutoIncrementLarge();
    }

    // Slow path for $v++
    private RuntimeScalar postAutoIncrementLarge() {
        // For undef, the old value should be 0, not undef
        RuntimeScalar old = this.type == RuntimeScalarType.UNDEF ?
                new RuntimeScalar(0) : new RuntimeScalar(this);

        // Cases 0-11 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        switch (type) {
            case INTEGER -> { // 0
                int intValue = (int) this.value;
                // Check for overflow - upgrade to double if needed
                if (intValue == Integer.MAX_VALUE) {
                    this.type = RuntimeScalarType.DOUBLE;
                    this.value = (double) intValue + 1;
                } else {
                    this.value = intValue + 1;
                }
            }
            case DOUBLE -> // 1
                    this.value = (double) this.value + 1;
            case STRING, BYTE_STRING -> // 2
                    ScalarUtils.stringIncrement(this);
            case UNDEF -> { // 3
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case VSTRING -> { // 4
                ScalarUtils.stringIncrement(this);
                this.type = RuntimeScalarType.STRING;  // ++ flattens vstrings
            }
            case BOOLEAN -> { // 5
                this.type = RuntimeScalarType.INTEGER;
                this.value = old.getInt() + 1;
            }
            case GLOB -> { // 6
                if (this instanceof RuntimeGlob) {
                    throw new PerlCompilerException("Modification of a read-only value attempted");
                }
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case JAVAOBJECT -> { // 7
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case TIED_SCALAR -> { // 8
                RuntimeScalar variable = new RuntimeScalar(old);
                variable.preAutoIncrement();
                this.tiedStore(variable);
            }
            case DUALVAR -> { // 9
                this.type = RuntimeScalarType.INTEGER;
                this.value = old.getInt() + 1;
            }
            case FORMAT -> { // 10
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case READONLY_SCALAR -> // 12
                    throw new PerlCompilerException("Modification of a read-only value attempted");
            default -> { // All reference types
                // Check if object is eligible for overloading
                int blessId = blessedId(this);
                if (blessId < 0) {
                    // Prepare overload context and check if object is eligible for overloading
                    OverloadContext ctx = OverloadContext.prepare(blessId);
                    if (ctx != null) {
                        // Copy-on-write: If the object has the = overload, call it to create
                        // a copy BEFORE any mutation. This implements Perl's COW semantics
                        // where shared references are copied before modification.
                        RuntimeScalar copyResult = ctx.tryOverload("(=", new RuntimeArray(this));
                        if (copyResult != null) {
                            // Copy the cloned object's fields into this
                            this.type = copyResult.type;
                            this.value = copyResult.value;
                        }

                        // Try direct overload method for ++
                        RuntimeScalar result = ctx.tryOverload("(++", new RuntimeArray(this));
                        if (result != null) {
                            // The ++ operator has already modified this operand
                            // Return the old value
                            return old;
                        }

                        // Try fallback to + operator with undef as third argument (mutator indicator)
                        result = ctx.tryOverload("(+", new RuntimeArray(this, scalarOne, scalarUndef));
                        if (result != null) {
                            // For fallback, + should NOT modify operand, so we handle assignment
                            this.type = result.type;
                            this.value = result.value;
                            return old;
                        }
                    }
                }

                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
        }
        return old;
    }

    public RuntimeScalar preAutoDecrement() {
        // Cases 0-11 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        switch (type) {
            case INTEGER -> // 0
                    this.value = (int) this.value - 1;
            case DOUBLE -> // 1
                    this.value = (double) this.value - 1;
            case STRING, BYTE_STRING -> { // 2
                // Handle numeric decrement
                this.set(NumberParser.parseNumber(this));
                return this.preAutoDecrement();
            }
            case UNDEF -> { // 3
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
            case VSTRING -> { // 4
                // Handle as numeric
                this.set(NumberParser.parseNumber(this));
                return this.preAutoDecrement();
            }
            case BOOLEAN -> { // 5
                int intVal = (boolean) this.value ? 1 : 0;
                this.type = RuntimeScalarType.INTEGER;
                this.value = intVal - 1;
            }
            case GLOB -> { // 6
                if (this instanceof RuntimeGlob) {
                    throw new PerlCompilerException("Modification of a read-only value attempted");
                }
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
            case JAVAOBJECT -> { // 7
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
            case TIED_SCALAR -> { // 8
                RuntimeScalar variable = this.tiedFetch();
                variable.preAutoDecrement();
                this.tiedStore(variable);
                return variable;
            }
            case DUALVAR -> { // 9
                int dualVal = this.getInt();
                this.type = RuntimeScalarType.INTEGER;
                this.value = dualVal - 1;
            }
            case FORMAT -> { // 10
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
            case READONLY_SCALAR -> // 12
                    throw new PerlCompilerException("Modification of a read-only value attempted");
            default -> { // All reference types
                // Check if object is eligible for overloading
                int blessId = blessedId(this);
                if (blessId < 0) {
                    // Prepare overload context and check if object is eligible for overloading
                    OverloadContext ctx = OverloadContext.prepare(blessId);
                    if (ctx != null) {
                        // Copy-on-write: If the object has the = overload, call it to create
                        // a copy BEFORE any mutation. This implements Perl's COW semantics
                        // where shared references are copied before modification.
                        RuntimeScalar copyResult = ctx.tryOverload("(=", new RuntimeArray(this));
                        if (copyResult != null) {
                            // Copy the cloned object's fields into this
                            this.type = copyResult.type;
                            this.value = copyResult.value;
                        }

                        // Try direct overload method for --
                        RuntimeScalar result = ctx.tryOverload("(--", new RuntimeArray(this));
                        if (result != null) {
                            // The -- operator has already modified this operand
                            // Just return this (which has been modified)
                            return this;
                        }

                        // Try fallback to - operator with undef as third argument (mutator indicator)
                        result = ctx.tryOverload("(-", new RuntimeArray(this, scalarOne, scalarUndef));
                        if (result != null) {
                            // For fallback, - should NOT modify operand, so we handle assignment
                            this.type = result.type;
                            this.value = result.value;
                            return this;
                        }
                    }
                }

                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
        }
        return this;
    }

    public boolean isBlessed() {
        return blessedId(this) != 0;
    }

    public RuntimeScalar postAutoDecrement() {
        RuntimeScalar old = new RuntimeScalar(this);

        // Cases 0-11 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        switch (type) {
            case INTEGER -> // 0
                    this.value = (int) this.value - 1;
            case DOUBLE -> // 1
                    this.value = (double) this.value - 1;
            case STRING, BYTE_STRING -> { // 2
                // Handle numeric decrement
                this.set(NumberParser.parseNumber(this));
                this.preAutoDecrement();
            }
            case UNDEF -> { // 3
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
            case VSTRING -> { // 4
                // Handle as numeric
                this.set(NumberParser.parseNumber(this));
                this.preAutoDecrement();
            }
            case BOOLEAN -> { // 5
                this.type = RuntimeScalarType.INTEGER;
                this.value = old.getInt() - 1;
            }
            case GLOB -> { // 6
                if (this instanceof RuntimeGlob) {
                    throw new PerlCompilerException("Modification of a read-only value attempted");
                }
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
            case JAVAOBJECT -> { // 7
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
            case TIED_SCALAR -> { // 8
                RuntimeScalar variable = new RuntimeScalar(old);
                variable.preAutoDecrement();
                this.tiedStore(variable);
            }
            case DUALVAR -> { // 9
                this.type = RuntimeScalarType.INTEGER;
                this.value = old.getInt() - 1;
            }
            case FORMAT -> { // 10
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
            case READONLY_SCALAR -> // 12
                    throw new PerlCompilerException("Modification of a read-only value attempted");
            default -> { // All reference types
                // Check if object is eligible for overloading
                int blessId = blessedId(this);
                if (blessId < 0) {
                    // Prepare overload context and check if object is eligible for overloading
                    OverloadContext ctx = OverloadContext.prepare(blessId);
                    if (ctx != null) {
                        // Copy-on-write: If the object has the = overload, call it to create
                        // a copy BEFORE any mutation. This implements Perl's COW semantics
                        // where shared references are copied before modification.
                        RuntimeScalar copyResult = ctx.tryOverload("(=", new RuntimeArray(this));
                        if (copyResult != null) {
                            // Copy the cloned object's fields into this
                            this.type = copyResult.type;
                            this.value = copyResult.value;
                        }

                        // Try direct overload method for --
                        RuntimeScalar result = ctx.tryOverload("(--", new RuntimeArray(this));
                        if (result != null) {
                            // The -- operator has already modified this operand
                            // Return the old value
                            return old;
                        }

                        // Try fallback to - operator with undef as third argument (mutator indicator)
                        result = ctx.tryOverload("(-", new RuntimeArray(this, scalarOne, scalarUndef));
                        if (result != null) {
                            // For fallback, - should NOT modify operand, so we handle assignment
                            this.type = result.type;
                            this.value = result.value;
                            return old;
                        }
                    }
                }

                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
        }
        return old;
    }

    public RuntimeScalar chop() {
        return StringOperators.chopScalar(this);
    }

    public RuntimeScalar chomp() {
        return StringOperators.chompScalar(this);
    }

    public RuntimeScalar pos() {
        return RuntimePosLvalue.pos(this);
    }

    // keys() operator
    public RuntimeArray keys() {
        throw new PerlCompilerException("Type of arg 1 to keys must be hash or array");
    }

    // values() operator
    public RuntimeArray values() {
        throw new PerlCompilerException("Type of arg 1 to values must be hash or array");
    }

    public RuntimeList each(int ctx) {
        throw new PerlCompilerException("Type of arg 1 to each must be hash or array");
    }

    // Method to return an iterator
    public Iterator<RuntimeScalar> iterator() {
        return new RuntimeScalarIterator(this);
    }

    public RuntimeIO getRuntimeIO() {
        return RuntimeIO.getRuntimeIO(this);
    }

    /**
     * Saves the current state of the RuntimeScalar instance.
     *
     * <p>This method creates a snapshot of the current type and value of the scalar,
     * and pushes it onto a static stack for later restoration.
     */
    @Override
    public void dynamicSaveState() {
        // Create a new RuntimeScalar to save the current state
        RuntimeScalar currentState = new RuntimeScalar();
        // Copy the current type and value to the new state
        currentState.type = this.type;
        currentState.value = this.value;
        currentState.blessId = this.blessId;
        // Push the current state onto the stack
        dynamicStateStack.push(currentState);
        // Clear the current type and value
        this.type = UNDEF;
        this.value = null;
        this.blessId = 0;
    }

    /**
     * Restores the most recently saved state of the RuntimeScalar instance.
     *
     * <p>This method pops the most recent state from the static stack and restores
     * the type and value to the current scalar. If no state is saved, it does nothing.
     */
    @Override
    public void dynamicRestoreState() {
        if (!dynamicStateStack.isEmpty()) {
            // Pop the most recent saved state from the stack
            RuntimeScalar previousState = dynamicStateStack.pop();

            // Decrement refCount of the CURRENT value being displaced.
            // Do NOT increment the restored value — it already has the correct
            // refCount from its original counting (it was never decremented during save).
            if ((this.type & RuntimeScalarType.REFERENCE_BIT) != 0
                    && this.value instanceof RuntimeBase displacedBase
                    && displacedBase.refCount > 0 && --displacedBase.refCount == 0) {
                displacedBase.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(displacedBase);
            }

            // Restore the type, value from the saved state
            this.type = previousState.type;
            this.value = previousState.value;
            this.blessId = previousState.blessId;
        }
    }

    private static class RuntimeScalarIterator implements Iterator<RuntimeScalar> {
        private final RuntimeScalar scalar;
        private boolean hasNext = true;

        public RuntimeScalarIterator(RuntimeScalar scalar) {
            this.scalar = scalar;
        }

        @Override
        public boolean hasNext() {
            return hasNext;
        }

        @Override
        public RuntimeScalar next() {
            if (!hasNext) {
                throw new NoSuchElementException();
            }
            hasNext = false;
            return scalar;
        }

        public void reset() {
            hasNext = true;
        }
    }
}

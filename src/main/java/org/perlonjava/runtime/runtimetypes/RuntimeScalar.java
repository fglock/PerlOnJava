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
            default -> Overload.numify(this);
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

    // Inlineable fast path for set(RuntimeScalar)
    public RuntimeScalar set(RuntimeScalar value) {
        if (this.type < TIED_SCALAR & value.type < TIED_SCALAR) {
            this.type = value.type;
            this.value = value.value;
            return this;
        }
        return setLarge(value);
    }

    // Slow path for set(RuntimeScalar)
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

        this.type = value.type;
        this.value = value.value;
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
                yield Overload.stringify(this).toString();
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
                // For numeric constants (like 1->[0]), return an empty array
                // This matches Perl's behavior where 1->[0] returns undef without error
                    new RuntimeArray();
            case DOUBLE -> // 1
                // For numeric constants (like 1->[0]), return an empty array
                    new RuntimeArray();
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
            case TIED_SCALAR -> // 9
                    tiedFetch().arrayDeref();
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
                    throw new PerlCompilerException("Not a HASH reference");
            case DOUBLE -> // 1
                    throw new PerlCompilerException("Not a HASH reference");
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
            case TIED_SCALAR -> // 9
                    tiedFetch().hashDeref();
            case DUALVAR -> // 10
                    throw new PerlCompilerException("Not a HASH reference");
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
            case TIED_SCALAR -> // 9
                    tiedFetch().hashDerefNonStrict(packageName);
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
            case TIED_SCALAR -> // 9
                    tiedFetch().arrayDerefNonStrict(packageName);
            case FORMAT -> // 11
                    throw new PerlCompilerException("Not an ARRAY reference");
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

    // Return a reference to this
    public RuntimeScalar createReference() {
        RuntimeScalar result = new RuntimeScalar();
        result.type = RuntimeScalarType.REFERENCE;
        result.value = this;
        return result;
    }

    public RuntimeScalar undefine() {
        // Special handling for CODE type - don't set the ref to undef,
        // just clear the code from the global symbol table
        if (type == RuntimeScalarType.CODE && value instanceof RuntimeCode) {
            // Clear the code value but keep the type as CODE
            this.value = new RuntimeCode((String) null, null);
            // Invalidate the method resolution cache
            InheritanceResolver.invalidateCache();
            return this;
        }
        // Call DESTROY on blessed references before clearing.
        // undef $obj explicitly discards the reference, so DESTROY should fire.
        callDestroyIfNeeded(this);
        // Close IO handles when dropping a glob reference.
        // This mimics Perl's internal sv_clear behavior where IO handles are closed
        // when the glob's reference count drops to zero (independent of DESTROY).
        closeIOOnDrop();
        // For all other types, set to undef
        this.type = UNDEF;
        this.value = null;
        return this;
    }

    /**
     * Close any IO handle associated with a GLOBREFERENCE value being dropped.
     * <p>
     * In Perl 5, IO handles are closed by the interpreter's sv_clear/gp_free when
     * the glob's reference count reaches zero. Since jperl doesn't implement
     * reference counting or DESTROY, we close IO handles eagerly in specific cases.
     * <p>
     * <b>Where this is called:</b>
     * <ul>
     *   <li>{@code undefine()} — when {@code undef $fh} is called explicitly</li>
     *   <li>{@code setLarge(null)} — when a variable is set to null/undef</li>
     * </ul>
     * <p>
     * <b>Where this is NOT called (by design):</b>
     * <ul>
     *   <li>{@code setLarge(value)} for non-null values — removed because variable
     *       reassignment (e.g. {@code $h = IO::Handle->new()}) cannot safely close
     *       the old IO when other variables may still reference the same glob.
     *       See the detailed comment in setLarge() for the Capture::Tiny failure
     *       that motivated this change.</li>
     *   <li>{@code set(int/long/double/String)} — these primitive-type setters
     *       never called closeIOOnDrop (pre-existing behavior).</li>
     * </ul>
     * <p>
     * <b>Safety heuristic:</b> We only close IO for globs that are NOT currently
     * in any stash (symbol table). Named globs still in the stash (like *MYFILE)
     * may have other references (including detached copies created by
     * {@code \*MYFILE}) and should not be closed. Globs that have been removed
     * from the stash (e.g., by gensym's delete) or that were never in a stash
     * are candidates for closing — but even this is only safe from undefine()
     * where the caller is explicitly requesting cleanup.
     * <p>
     * <b>Known limitation:</b> Without reference counting, anonymous file handles
     * that are overwritten (not explicitly closed or undef'd) will leak their
     * underlying IO until JVM garbage collection or program exit.
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
     * Called by JVM bytecode at scope exit to deterministically close IO handles
     * on anonymous lexical filehandles ({@code open(my $fh, ...)}).
     * <p>
     * This is the JVM-backend equivalent of Perl 5's DESTROY: when a {@code my $fh}
     * goes out of scope, its IO handle should be closed and its fd recycled.
     * Since the JVM's tracing GC is non-deterministic, we call this explicitly
     * from the emitted scope-exit bytecode to ensure timely fd recycling.
     * <p>
     * Only closes IO on anonymous globs (globName == null) — named globs like
     * {@code *STDOUT} or gensym'd handles may be referenced by other variables
     * (e.g., in Capture::Tiny's save/restore pattern).
     *
     * @param scalar the RuntimeScalar being cleaned up (may be null if slot was already nulled)
     */
    public static void scopeExitCleanup(RuntimeScalar scalar) {
        if (scalar != null && scalar.ioOwner && scalar.type == GLOBREFERENCE
                && scalar.value instanceof RuntimeGlob glob
                && glob.globName == null) {
            // Decrement the holder count. Only close IO when no more holders exist.
            // This prevents closing IO that is still referenced by variables in outer scopes
            // (e.g., `my $io = $fh` inside a for loop where $fh is from an outer scope).
            glob.ioHolderCount--;
            if (glob.ioHolderCount <= 0) {
                RuntimeScalar ioSlot = glob.getIO();
                if (ioSlot != null && ioSlot.value instanceof RuntimeIO io
                        && !(io.ioHandle instanceof ClosedIOHandle)) {
                    io.close();
                }
            }
        }
        // Also handle blessed references with DESTROY methods.
        // When a lexical variable holding a blessed reference goes out of scope,
        // call DESTROY on the object. This is an approximation of Perl's
        // reference-counted DESTROY: without ref counting, we may call DESTROY
        // early if other references exist. The destroyCalled flag prevents
        // double-DESTROY.
        callDestroyIfNeeded(scalar);
    }

    /**
     * Calls DESTROY on a blessed object if:
     * 1. The scalar holds a blessed reference (blessId != 0)
     * 2. DESTROY hasn't been called yet on this object (destroyCalled flag)
     * 3. The class hierarchy defines a DESTROY method
     * <p>
     * Perl semantics: DESTROY exceptions are caught and warned "(in cleanup)".
     * DESTROY is called with the blessed reference as $_[0].
     * <p>
     * Note: Without reference counting, this may call DESTROY while other
     * references to the object still exist. The destroyCalled flag on
     * RuntimeBase prevents double-DESTROY.
     */
    public static void callDestroyIfNeeded(RuntimeScalar scalar) {
        if (scalar == null) return;
        int blessId = RuntimeScalarType.blessedId(scalar);
        if (blessId == 0) return;
        RuntimeBase base = (RuntimeBase) scalar.value;
        if (base.destroyCalled) return;
        base.destroyCalled = true;

        String className = NameNormalizer.getBlessStr(blessId);
        RuntimeScalar destroyMethod = InheritanceResolver.findMethodInHierarchy(
                "DESTROY", className, null, 0);
        if (destroyMethod == null || destroyMethod.type != CODE) {
            base.destroyCalled = false;  // No DESTROY found, allow future attempts
            return;
        }

        try {
            // Call DESTROY($self) in void context
            RuntimeArray args = new RuntimeArray();
            args.push(scalar);
            RuntimeCode.apply(destroyMethod, args, RuntimeContextType.VOID);
        } catch (Exception e) {
            // Perl: DESTROY exceptions are turned into warnings
            String msg = e.getMessage();
            if (msg == null) msg = e.getClass().getName();
            Warnings.warn(new RuntimeArray(new RuntimeScalar("(in cleanup) " + msg + "\n")), RuntimeContextType.VOID);
        }
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
                this.type = RuntimeScalarType.INTEGER;
                this.value = this.getInt() + 1;
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
                this.type = RuntimeScalarType.INTEGER;
                this.value = this.getInt() + 1;
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
                this.type = RuntimeScalarType.INTEGER;
                this.value = this.getInt() - 1;
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
                this.type = RuntimeScalarType.INTEGER;
                this.value = this.getInt() - 1;
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

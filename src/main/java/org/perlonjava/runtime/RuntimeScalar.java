package org.perlonjava.runtime;

import org.perlonjava.operators.Operator;
import org.perlonjava.operators.StringOperators;
import org.perlonjava.parser.NumberParser;
import org.perlonjava.regex.RuntimeRegex;

import java.util.*;

import static org.perlonjava.runtime.RuntimeScalarCache.*;
import static org.perlonjava.runtime.RuntimeScalarType.*;

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
        }
        this.value = value;
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
                this.value = v;
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

    public static RuntimeScalar wantarray(int ctx) {
        return ctx == RuntimeContextType.VOID ? scalarUndef : new RuntimeScalar(ctx == RuntimeContextType.LIST ? scalarOne : scalarZero);
    }

    public static RuntimeList reset(RuntimeList args, int ctx) {
        if (args.isEmpty()) {
            RuntimeRegex.reset();
        } else {
            throw new PerlCompilerException("not implemented: reset(args)");
        }
        return getScalarInt(1).getList();
    }

    public static RuntimeScalar repeat(RuntimeScalar runtimeScalar, RuntimeScalar arg) {
        return (RuntimeScalar) Operator.repeat(runtimeScalar, arg, RuntimeContextType.SCALAR);
    }

    private void initializeWithLong(Long value) {
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            this.type = DOUBLE;
            this.value = (double) value;
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

    // Getters
    public int getInt() {
        // Cases 0-8 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> (int) value;
            case DOUBLE -> (int) ((double) value);
            case STRING -> NumberParser.parseNumber(this).getInt();
            case UNDEF -> 0;
            case VSTRING -> 0;
            case BOOLEAN -> (boolean) value ? 1 : 0;
            case GLOB -> 1;  // Assuming globs are truthy, so 1
            case REGEX -> 1; // Assuming regexes are truthy, so 1
            case JAVAOBJECT -> value != null ? 1 : 0;
            default -> Overload.numify(this).getInt();
        };
    }

    public long getLong() {
        // Cases 0-8 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> (int) value;
            case DOUBLE -> (long) ((double) value);
            case STRING -> NumberParser.parseNumber(this).getLong();
            case UNDEF -> 0L;
            case VSTRING -> 0L;
            case BOOLEAN -> (boolean) value ? 1L : 0L;
            case GLOB -> 1L;
            case REGEX -> 1L;
            case JAVAOBJECT -> value != null ? 1L : 0L;
            default -> Overload.numify(this).getLong();
        };
    }

    public double getDouble() {
        // Cases 0-8 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> (int) value;
            case DOUBLE -> (double) value;
            case STRING -> NumberParser.parseNumber(this).getDouble();
            case UNDEF -> 0.0;
            case VSTRING -> 0.0;
            case BOOLEAN -> (boolean) value ? 1.0 : 0.0;
            case GLOB -> 1.0;
            case REGEX -> 1.0;
            case JAVAOBJECT -> value != null ? 1.0 : 0.0;
            default -> Overload.numify(this).getDouble();
        };
    }

    public boolean getBoolean() {
        // Cases 0-8 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> (int) value != 0;
            case DOUBLE -> (double) value != 0.0;
            case STRING -> {
                String s = (String) value;
                yield !s.isEmpty() && !s.equals("0");
            }
            case UNDEF -> false;
            case VSTRING -> true;
            case BOOLEAN -> (boolean) value;
            case GLOB -> true;
            case REGEX -> true;
            case JAVAOBJECT -> value != null;
            default -> Overload.boolify(this).getBoolean();
        };
    }

    // Get blessing ID as an integer
    public int blessedId() {
        return (type & REFERENCE_BIT) != 0 ? ((RuntimeBase) value).blessId : 0;
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

    // Add itself to a RuntimeArray.
    public void addToArray(RuntimeArray array) {
        array.elements.add(new RuntimeScalar(this));
    }

    /**
     * Add itself to a RuntimeScalar.
     *
     * @param scalar The RuntimeScalar object
     */
    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        return scalar.set(this);
    }

    // Setters
    public RuntimeScalar set(RuntimeScalar value) {
        this.type = value.type;
        this.value = value.value;
        return this;
    }

    public RuntimeScalar set(int value) {
        this.type = RuntimeScalarType.INTEGER;
        this.value = value;
        return this;
    }

    public RuntimeScalar set(long value) {
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            this.type = DOUBLE;
            this.value = (double) value;
        } else {
            this.type = RuntimeScalarType.INTEGER;
            this.value = (int) value;
        }
        return this;
    }

    public RuntimeScalar set(boolean value) {
        this.type = RuntimeScalarType.BOOLEAN;
        this.value = value;
        return this;
    }

    public RuntimeScalar set(String value) {
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
    public String toString() {
        // Cases 0-8 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> Integer.toString((int) value);
            case DOUBLE -> formatLikePerl((double) value);
            case STRING -> (String) value;
            case UNDEF -> "";
            case VSTRING -> (String) value;
            case BOOLEAN -> (boolean) value ? "1" : "";
            case GLOB -> value == null ? "" : value.toString();
            case REGEX -> value.toString();
            case JAVAOBJECT -> value.toString();
            default -> Overload.stringify(this).toString();
        };
    }

    private String formatLikePerl(double value) {
        if (Double.isInfinite(value)) {
            return value > 0 ? "Inf" : "-Inf";
        }
        if (Double.isNaN(value)) {
            return "NaN";
        }

        double absValue = Math.abs(value);

        if (absValue >= 1e15 || (absValue < 1e-4 && absValue != 0.0)) {
            // Use scientific notation like Perl
            String result = String.format("%.14e", value);
            // Clean up the scientific notation to match Perl's format
            result = result.replaceAll("e\\+0*", "e+").replaceAll("e-0*", "e-");
            // Remove trailing zeros in the mantissa
            result = result.replaceAll("(\\d)\\.?0+e", "$1e");
            return result;
        } else {
            // Use fixed-point notation
            String result = String.format("%.15f", value);
            // Remove trailing zeros and decimal point if not needed
            result = result.replaceAll("0+$", "").replaceAll("\\.$", "");
            return result;
        }
    }

    public String toStringRef() {
        String ref = switch (type) {
            case UNDEF -> "SCALAR(0x" + scalarUndef.hashCode() + ")";
            case CODE, GLOB -> {
                if (value == null) {
                    yield "CODE(0x" + scalarUndef.hashCode() + ")";
                }
                yield ((RuntimeCode) value).toStringRef();
            }
            case VSTRING -> "VSTRING(0x" + value.hashCode() + ")";
            default -> "SCALAR(0x" + Integer.toHexString(value.hashCode()) + ")";
        };
        return (blessId == 0 ? ref : NameNormalizer.getBlessStr(blessId) + "=" + ref);
    }

    public int getIntRef() {
        return value.hashCode();
    }

    public double getDoubleRef() {
        return value.hashCode();
    }

    public boolean getBooleanRef() {
        return true;
    }

    // Method to implement `$v->{key}`
    public RuntimeScalar hashDerefGet(RuntimeScalar index) {
        // Check if object is eligible for overloading
        int blessId = this.blessedId();
        if (blessId != 0) {
            return this.hashDeref().get(index);
        }

        return switch (type) {
            case UNDEF -> {
                // hash autovivification
                type = RuntimeScalarType.HASHREFERENCE;
                value = new RuntimeHash();
                yield ((RuntimeHash) value).get(index.toString());
            }
            case HASHREFERENCE -> ((RuntimeHash) value).get(index.toString());
            case STRING -> throw new PerlCompilerException("Can't use string (\"" + this + "\") as a HASH ref");
            case GLOB -> ((RuntimeGlob) value).hashDerefGet(index);
            default -> throw new PerlCompilerException("Not a HASH reference");
        };
    }

    // Method to implement `delete $v->{key}`
    public RuntimeScalar hashDerefDelete(RuntimeScalar index) {
        // Check if object is eligible for overloading
        int blessId = this.blessedId();
        if (blessId != 0) {
            return this.hashDeref().delete(index);
        }

        return switch (type) {
            case UNDEF -> {
                // hash autovivification
                type = RuntimeScalarType.HASHREFERENCE;
                value = new RuntimeHash();
                yield ((RuntimeHash) value).delete(index);
            }
            case HASHREFERENCE -> ((RuntimeHash) value).delete(index);
            case STRING -> throw new PerlCompilerException("Can't use string (\"" + this + "\") as a HASH ref");
            default -> throw new PerlCompilerException("Not a HASH reference");
        };
    }

    // Method to implement `exists $v->{key}`
    public RuntimeScalar hashDerefExists(RuntimeScalar index) {
        // Check if object is eligible for overloading
        int blessId = this.blessedId();
        if (blessId != 0) {
            return this.hashDeref().exists(index);
        }

        return switch (type) {
            case UNDEF -> {
                // hash autovivification
                type = RuntimeScalarType.HASHREFERENCE;
                value = new RuntimeHash();
                yield ((RuntimeHash) value).exists(index);
            }
            case HASHREFERENCE -> ((RuntimeHash) value).exists(index);
            case STRING -> throw new PerlCompilerException("Can't use string (\"" + this + "\") as a HASH ref");
            default -> throw new PerlCompilerException("Not a HASH reference");
        };
    }

    // Method to implement `$v->[10]`
    public RuntimeScalar arrayDerefGet(RuntimeScalar index) {
        // Check if object is eligible for overloading
        int blessId = this.blessedId();
        if (blessId != 0) {
            return this.arrayDeref().get(index);
        }

        switch (type) {
            case UNDEF:
                // array autovivification
                type = RuntimeScalarType.ARRAYREFERENCE;
                value = new RuntimeArray();
            case ARRAYREFERENCE:
                return ((RuntimeArray) value).get(index.getInt());
            default:
                throw new PerlCompilerException("Not an ARRAY reference");
        }
    }

    // Method to implement `delete $v->[10]`
    public RuntimeScalar arrayDerefDelete(RuntimeScalar index) {
        return this.arrayDeref().delete(index);
    }

    // Method to implement `exists $v->[10]`
    public RuntimeScalar arrayDerefExists(RuntimeScalar index) {
        return this.arrayDeref().exists(index);
    }

    // Method to implement `@$v`
    public RuntimeArray arrayDeref() {
        // Check if object is eligible for overloading
        int blessId = this.blessedId();
        if (blessId != 0) {
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

        return switch (type) {
            case UNDEF -> {
                // Autovivification: When dereferencing an undefined scalar as an array,
                // Perl automatically creates a new array reference.
                var newArray = new RuntimeArray();

                // Create a special array that knows about this scalar. When the array
                // receives its first assignment (e.g., @$ref = (...)), it will
                // automatically convert this scalar from UNDEF to a proper array reference.
                // This implements Perl's autovivification behavior where undefined
                // scalars become references when used as such.
                newArray.elements = new AutovivificationArray(this);

                // Return the newly created array. At this point, the scalar is still UNDEF,
                // but will be autovivified to an array reference on first write operation.
                yield newArray;
            }
            case ARRAYREFERENCE -> (RuntimeArray) value;
            case STRING ->
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as an ARRAY ref while \"strict refs\" in use");
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
        int blessId = this.blessedId();
        if (blessId != 0) {
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

        return switch (type) {
            case UNDEF -> {
                // Autovivification: When dereferencing an undefined scalar as a hash,
                // Perl automatically creates a new hash reference.
                var newHash = new RuntimeHash();

                // Create a special hash that knows about this scalar. When the hash
                // receives its first assignment (e.g., %$ref = (...)), it will
                // automatically convert this scalar from UNDEF to a proper hash reference.
                // This implements Perl's autovivification behavior where undefined
                // scalars become references when used as such.
                newHash.elements = new AutovivificationHash(this);

                // Return the newly created hash. At this point, the scalar is still UNDEF,
                // but will be autovivified to a hash reference on first write operation.
                yield newHash;
            }
            case HASHREFERENCE ->
                // Simple case: already a hash reference, just return the hash
                    (RuntimeHash) value;
            case STRING ->
                // Strict refs violation: attempting to use a string as a hash ref
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as a HASH ref while \"strict refs\" in use");
            default ->
                // All other types (INTEGER, DOUBLE, etc.) cannot be dereferenced as hashes
                    throw new PerlCompilerException("Variable does not contain a hash reference");
        };
    }

    // Method to implement `$$v`
    public RuntimeScalar scalarDeref() {
        // Check if object is eligible for overloading
        int blessId = this.blessedId();
        if (blessId != 0) {
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
            case UNDEF -> throw new PerlCompilerException("Can't use an undefined value as a SCALAR reference");
            case REFERENCE -> (RuntimeScalar) value;
            case STRING ->
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as a SCALAR ref while \"strict refs\" in use");
            default -> throw new PerlCompilerException("Variable does not contain a scalar reference");
        };
    }

    // Method to implement `$$v`, when "no strict refs" is in effect
    public RuntimeScalar scalarDerefNonStrict(String packageName) {
        // Check if object is eligible for overloading
        int blessId = this.blessedId();
        if (blessId != 0) {
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
            default -> {
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalVariable(varName);
            }
        };
    }

    // Method to implement `*$v`
    public RuntimeGlob globDeref() {
        // Check if object is eligible for overloading
        int blessId = this.blessedId();
        if (blessId != 0) {
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
            case UNDEF -> throw new PerlCompilerException("Can't use an undefined value as a GLOB reference");
            case GLOB, GLOBREFERENCE -> (RuntimeGlob) value;
            case STRING ->
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as a symbol ref while \"strict refs\" in use");
            default -> throw new PerlCompilerException("Variable does not contain a glob reference");
        };
    }

    // Method to implement `*$v`, when "no strict refs" is in effect
    public RuntimeGlob globDerefNonStrict(String packageName) {
        // Check if object is eligible for overloading
        int blessId = this.blessedId();
        if (blessId != 0) {
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
            case GLOB, GLOBREFERENCE -> (RuntimeGlob) value;
            default -> {
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield new RuntimeGlob(varName);
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
        this.type = UNDEF;
        this.value = null;
        return this;
    }

    public RuntimeScalar defined() {
        return getScalarBoolean(getDefinedBoolean());
    }

    public boolean getDefinedBoolean() {
        return switch (type) {
            case CODE -> ((RuntimeCode) value).defined();
            case BOOLEAN -> (boolean) value;
            default -> type != UNDEF;
        };
    }

    public RuntimeScalar preAutoIncrement() {
        switch (type) {
            case INTEGER -> this.value = (int) this.value + 1;
            case DOUBLE -> this.value = (double) this.value + 1;
            case STRING -> {
                this.value = ScalarUtils.stringIncrement(this);
                return this;
            }
            case BOOLEAN -> {
                this.type = RuntimeScalarType.INTEGER;
                this.value = this.getInt() + 1;
            }
            default -> {
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
        }
        return this;
    }

    public RuntimeScalar postAutoIncrement() {
        RuntimeScalar old = new RuntimeScalar(this);
        switch (type) {
            case INTEGER -> this.value = (int) this.value + 1;
            case DOUBLE -> this.value = (double) this.value + 1;
            case STRING -> ScalarUtils.stringIncrement(this);
            case BOOLEAN -> {
                this.type = RuntimeScalarType.INTEGER;
                this.value = this.getInt() + 1;
            }
            default -> {
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
        }
        return old;
    }

    public RuntimeScalar preAutoDecrement() {
        switch (type) {
            case INTEGER -> this.value = (int) this.value - 1;
            case DOUBLE -> this.value = (double) this.value - 1;
            case STRING -> {
                // Handle numeric decrement
                this.set(NumberParser.parseNumber(this));
                return this.preAutoDecrement();
            }
            case BOOLEAN -> {
                this.type = RuntimeScalarType.INTEGER;
                this.value = this.getInt() - 1;
            }
            default -> {
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
        }
        return this;
    }

    public RuntimeScalar postAutoDecrement() {
        RuntimeScalar old = new RuntimeScalar(this);
        switch (type) {
            case INTEGER -> this.value = (int) this.value - 1;
            case DOUBLE -> this.value = (double) this.value - 1;
            case STRING -> {
                // Handle numeric decrement
                this.set(NumberParser.parseNumber(this));
                this.preAutoDecrement();
            }
            case BOOLEAN -> {
                this.type = RuntimeScalarType.INTEGER;
                this.value = this.getInt() - 1;
            }
            default -> {
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
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

    public RuntimeList each() {
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

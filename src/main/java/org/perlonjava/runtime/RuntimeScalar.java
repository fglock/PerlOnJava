package org.perlonjava.runtime;

import org.perlonjava.operators.MathOperators;
import org.perlonjava.operators.StringOperators;
import org.perlonjava.parser.NumberParser;
import org.perlonjava.regex.RuntimeRegex;

import java.util.*;

import static org.perlonjava.runtime.RuntimeArray.*;
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
        if (scalar.type == TIED_SCALAR) {
            RuntimeScalar temp = scalar.tiedFetch();
            this.type = temp.type;
            this.value = temp.value;
        } else {
            this.type = scalar.type;
            this.value = scalar.value;
        }
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
        // TODO optimization: group the string type ids to simplify the isString test
        int t = this.type;
        return t == STRING || t == BYTE_STRING || t == VSTRING;
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
    public RuntimeScalar getNumber() {
        // Cases 0-8 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER, DOUBLE -> this;
            case STRING, BYTE_STRING -> NumberParser.parseNumber(this);
            case UNDEF -> scalarZero;
            case VSTRING -> NumberParser.parseNumber(this);
            case BOOLEAN -> (boolean) value ? scalarOne : scalarZero;
            case GLOB -> scalarOne;  // Assuming globs are truthy, so 1
            case REGEX -> scalarOne; // Assuming regexes are truthy, so 1
            case JAVAOBJECT -> value != null ? scalarOne : scalarZero;
            case TIED_SCALAR -> this.tiedFetch().getNumber();
            case DUALVAR -> ((DualVar) this.value).numericValue;
            default -> Overload.numify(this);
        };
    }

    public int getInt() {
        // Cases 0-8 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> (int) value;
            case DOUBLE -> (int) ((double) value);
            case STRING, BYTE_STRING -> NumberParser.parseNumber(this).getInt();
            case UNDEF -> 0;
            case VSTRING -> 0;
            case BOOLEAN -> (boolean) value ? 1 : 0;
            case GLOB -> 1;  // Assuming globs are truthy, so 1
            case REGEX -> 1; // Assuming regexes are truthy, so 1
            case JAVAOBJECT -> value != null ? 1 : 0;
            case TIED_SCALAR -> this.tiedFetch().getInt();
            case DUALVAR -> ((DualVar) this.value).numericValue.getInt();
            default -> Overload.numify(this).getInt();
        };
    }

    public long getLong() {
        // Cases 0-8 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> (int) value;
            case DOUBLE -> (long) ((double) value);
            case STRING, BYTE_STRING -> NumberParser.parseNumber(this).getLong();
            case UNDEF -> 0L;
            case VSTRING -> 0L;
            case BOOLEAN -> (boolean) value ? 1L : 0L;
            case GLOB -> 1L;
            case REGEX -> 1L;
            case JAVAOBJECT -> value != null ? 1L : 0L;
            case TIED_SCALAR -> this.tiedFetch().getLong();
            case DUALVAR -> ((DualVar) this.value).numericValue.getLong();
            default -> Overload.numify(this).getLong();
        };
    }

    public double getDouble() {
        // Cases 0-8 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> (int) value;
            case DOUBLE -> (double) value;
            case STRING, BYTE_STRING -> NumberParser.parseNumber(this).getDouble();
            case UNDEF -> 0.0;
            case VSTRING -> 0.0;
            case BOOLEAN -> (boolean) value ? 1.0 : 0.0;
            case GLOB -> 1.0;
            case REGEX -> 1.0;
            case JAVAOBJECT -> value != null ? 1.0 : 0.0;
            case TIED_SCALAR -> this.tiedFetch().getDouble();
            case DUALVAR -> ((DualVar) this.value).numericValue.getDouble();
            default -> Overload.numify(this).getDouble();
        };
    }

    public boolean getBoolean() {
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
            case REGEX -> true;
            case JAVAOBJECT -> value != null;
            case TIED_SCALAR -> this.tiedFetch().getBoolean();
            case DUALVAR -> ((DualVar) this.value).stringValue.getBoolean();
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

    // Setters
    public RuntimeScalar set(RuntimeScalar value) {
        if (value.type == TIED_SCALAR) {
            return set(value.tiedFetch());
        }
        if (this.type == TIED_SCALAR) {
            return this.tiedStore(value);
        }
        this.type = value.type;
        this.value = value.value;
        return this;
    }

    public RuntimeScalar set(int value) {
        if (this.type == TIED_SCALAR) {
            return this.tiedStore(new RuntimeScalar(value));
        }
        this.type = RuntimeScalarType.INTEGER;
        this.value = value;
        return this;
    }

    public RuntimeScalar set(long value) {
        if (this.type == TIED_SCALAR) {
            return this.tiedStore(new RuntimeScalar(value));
        }
        this.initializeWithLong(value);
        return this;
    }

    public RuntimeScalar set(boolean value) {
        if (this.type == TIED_SCALAR) {
            return this.tiedStore(new RuntimeScalar(value));
        }
        this.type = RuntimeScalarType.BOOLEAN;
        this.value = value;
        return this;
    }

    public RuntimeScalar set(String value) {
        if (this.type == TIED_SCALAR) {
            return this.tiedStore(new RuntimeScalar(value));
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
    public String toString() {
        // Cases 0-8 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> Integer.toString((int) value);
            case DOUBLE -> ScalarUtils.formatLikePerl((double) value);
            case STRING, BYTE_STRING -> (String) value;
            case UNDEF -> "";
            case VSTRING -> (String) value;
            case BOOLEAN -> (boolean) value ? "1" : "";
            case GLOB -> value == null ? "" : value.toString();
            case REGEX -> value.toString();
            case JAVAOBJECT -> value.toString();
            case TIED_SCALAR -> this.tiedFetch().toString();
            case DUALVAR -> ((DualVar) this.value).stringValue.toString();
            default -> Overload.stringify(this).toString();
        };
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
        return this.hashDeref().get(index);
    }

    // Method to implement `delete $v->{key}`
    public RuntimeScalar hashDerefDelete(RuntimeScalar index) {
        return this.hashDeref().delete(index);
    }

    // Method to implement `exists $v->{key}`
    public RuntimeScalar hashDerefExists(RuntimeScalar index) {
        return this.hashDeref().exists(index);
    }

    // Method to implement `$v->[10]`
    public RuntimeScalar arrayDerefGet(RuntimeScalar index) {
        return this.arrayDeref().get(index);
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
        int blessId = blessedId(this);
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
            case UNDEF -> AutovivificationArray.createAutovivifiedArray(this);
            case ARRAYREFERENCE -> (RuntimeArray) value;
            case STRING, BYTE_STRING ->
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as an ARRAY ref while \"strict refs\" in use");
            case TIED_SCALAR -> tiedFetch().arrayDeref();
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
            case UNDEF ->
                //                var hash = new RuntimeHash();
                //                this.type = RuntimeScalarType.HASHREFERENCE;
                //                this.value = hash;
                //                yield hash;
                    AutovivificationHash.createAutovivifiedHash(this);
            case HASHREFERENCE ->
                // Simple case: already a hash reference, just return the hash
                    (RuntimeHash) value;
            case STRING, BYTE_STRING ->
                // Strict refs violation: attempting to use a string as a hash ref
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as a HASH ref while \"strict refs\" in use");
            case TIED_SCALAR -> tiedFetch().hashDeref();
            default ->
                // All other types (INTEGER, DOUBLE, etc.) cannot be dereferenced as hashes
                    throw new PerlCompilerException("Not a HASH reference");
        };
    }

    // Method to implement `$$v`
    public RuntimeScalar scalarDeref() {
        // Check if object is eligible for overloading
        int blessId = blessedId(this);
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
            case STRING, BYTE_STRING ->
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as a SCALAR ref while \"strict refs\" in use");
            case TIED_SCALAR -> tiedFetch().scalarDeref();
            default -> throw new PerlCompilerException("Variable does not contain a scalar reference");
        };
    }

    // Method to implement `$$v`, when "no strict refs" is in effect
    public RuntimeScalar scalarDerefNonStrict(String packageName) {
        // Check if object is eligible for overloading
        int blessId = blessedId(this);
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
            case TIED_SCALAR -> tiedFetch().scalarDerefNonStrict(packageName);
            default -> {
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalVariable(varName);
            }
        };
    }

    // Method to implement `*$v`
    public RuntimeGlob globDeref() {
        // Check if object is eligible for overloading
        int blessId = blessedId(this);
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
            case STRING, BYTE_STRING ->
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as a symbol ref while \"strict refs\" in use");
            default -> throw new PerlCompilerException("Variable does not contain a glob reference");
        };
    }

    // Method to implement `*$v`, when "no strict refs" is in effect
    public RuntimeGlob globDerefNonStrict(String packageName) {
        // Check if object is eligible for overloading
        int blessId = blessedId(this);
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
            case BOOLEAN -> (boolean) value;
            case CODE -> ((RuntimeCode) value).defined();
            case TIED_SCALAR -> this.tiedFetch().getDefinedBoolean();
            case DUALVAR -> ((DualVar) this.value).stringValue.getDefinedBoolean();
            default -> type != UNDEF;
        };
    }

    public RuntimeScalar preAutoIncrement() {
        // Cases 0-10 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        switch (type) {
            case INTEGER -> // 0
                    this.value = (int) this.value + 1;
            case DOUBLE -> // 1
                    this.value = (double) this.value + 1;
            case STRING, BYTE_STRING -> // 2
                    ScalarUtils.stringIncrement(this);
            case UNDEF -> { // 3
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case VSTRING -> // 4
                    ScalarUtils.stringIncrement(this);
            case BOOLEAN -> { // 5
                this.type = RuntimeScalarType.INTEGER;
                this.value = this.getInt() + 1;
            }
            case GLOB -> { // 6
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case REGEX -> { // 7
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case JAVAOBJECT -> { // 8
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case TIED_SCALAR -> { // 9
                RuntimeScalar variable = this.tiedFetch();
                variable.preAutoIncrement();
                this.tiedStore(variable);
                return variable;
            }
            case DUALVAR -> // 10
                    this.set(MathOperators.add(this, 1));
            default -> { // All reference types (CODE, REFERENCE, ARRAYREFERENCE, etc.)
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
        }
        return this;
    }

    public RuntimeScalar postAutoIncrement() {
        RuntimeScalar old = new RuntimeScalar(this);
        // Cases 0-10 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        switch (type) {
            case INTEGER -> // 0
                    this.value = (int) this.value + 1;
            case DOUBLE -> // 1
                    this.value = (double) this.value + 1;
            case STRING, BYTE_STRING -> // 2
                    ScalarUtils.stringIncrement(this);
            case UNDEF -> { // 3
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case VSTRING -> // 4
                    ScalarUtils.stringIncrement(this);
            case BOOLEAN -> { // 5
                this.type = RuntimeScalarType.INTEGER;
                this.value = old.getInt() + 1;
            }
            case GLOB -> { // 6
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case REGEX -> { // 7
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case JAVAOBJECT -> { // 8
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case TIED_SCALAR -> { // 9
                RuntimeScalar variable = new RuntimeScalar(old);
                variable.preAutoIncrement();
                this.tiedStore(variable);
            }
            case DUALVAR -> // 10
                    this.set(MathOperators.add(this, 1));
            default -> { // All reference types
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
        }
        return old;
    }

    public RuntimeScalar preAutoDecrement() {
        // Cases 0-10 are listed in order from RuntimeScalarType, and compile to fast tableswitch
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
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
            case REGEX -> { // 7
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
            case JAVAOBJECT -> { // 8
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
            case TIED_SCALAR -> { // 9
                RuntimeScalar variable = this.tiedFetch();
                variable.preAutoDecrement();
                this.tiedStore(variable);
                return variable;
            }
            case DUALVAR -> // 10
                    this.set(MathOperators.add(this, -1));
            default -> { // All reference types
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
        }
        return this;
    }

    public RuntimeScalar postAutoDecrement() {
        RuntimeScalar old = new RuntimeScalar(this);
        // Cases 0-10 are listed in order from RuntimeScalarType, and compile to fast tableswitch
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
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
            case REGEX -> { // 7
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
            case JAVAOBJECT -> { // 8
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
            case TIED_SCALAR -> { // 9
                RuntimeScalar variable = new RuntimeScalar(old);
                variable.preAutoDecrement();
                this.tiedStore(variable);
            }
            case DUALVAR -> // 10
                    this.set(MathOperators.add(this, -1));
            default -> { // All reference types
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

    public static RuntimeScalar undef () {
        return scalarUndef;
    }
}

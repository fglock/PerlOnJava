package org.perlonjava.runtime;

import org.perlonjava.operators.Operator;
import org.perlonjava.parser.NumberParser;

import java.util.*;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.*;

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
public class RuntimeScalar extends RuntimeBaseEntity implements RuntimeScalarReference, DynamicState {

    // Static stack to store saved "local" states of RuntimeScalar instances
    private static final Stack<RuntimeScalar> dynamicStateStack = new Stack<>();

    // Type map for scalar types to their corresponding enum
    private static final Map<Class<?>, RuntimeScalarType> typeMap = new HashMap<>();

    static {
        typeMap.put(Integer.class, RuntimeScalarType.INTEGER);
        typeMap.put(String.class, RuntimeScalarType.STRING);
        typeMap.put(Double.class, RuntimeScalarType.DOUBLE);
        typeMap.put(Boolean.class, RuntimeScalarType.BOOLEAN);
        // Add other known types if necessary
    }

    // Fields to store the type and value of the scalar variable
    public RuntimeScalarType type;
    public Object value;

    // Constructors
    public RuntimeScalar() {
        this.type = RuntimeScalarType.UNDEF;
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
        this.type = RuntimeScalarType.DOUBLE;
        this.value = value;
    }

    public RuntimeScalar(Double value) {
        this.type = RuntimeScalarType.DOUBLE;
        this.value = value;
    }

    public RuntimeScalar(String value) {
        if (value == null) {
            this.type = RuntimeScalarType.UNDEF;
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
            this.type = RuntimeScalarType.UNDEF;
        } else {
            this.type = RuntimeScalarType.GLOB;
        }
        this.value = value;
    }

    public RuntimeScalar(RuntimeGlob value) {
        if (value == null) {
            this.type = RuntimeScalarType.UNDEF;
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
     * @return a new RuntimeScalar instance with the given value
     */
    public RuntimeScalar(Object value) {
        // System.out.println("RuntimeScalar " + (value == null ? "null" : value.getClass()));
        if (value == null) {
            this.type = RuntimeScalarType.UNDEF;
            this.value = null;
        } else if (value instanceof RuntimeScalar scalar) {
            this.type = scalar.type;
            this.value = scalar.value;
        } else if (value instanceof Long longValue) {
            initializeWithLong(longValue);
        } else {
            // Look for a known type, default to OBJECT if not found
            this.type = typeMap.getOrDefault(value.getClass(), RuntimeScalarType.OBJECT);
            this.value = value;
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
        if (newScalar.type == RuntimeScalarType.OBJECT) {
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
        return ctx == RuntimeContextType.VOID ? new RuntimeScalar() : new RuntimeScalar(ctx == RuntimeContextType.LIST ? 1 : 0);
    }

    public static RuntimeList reset(RuntimeList args, int ctx) {
        if (args.isEmpty()) {
            RuntimeRegex.reset();
        } else {
            throw new PerlCompilerException("not implemented: reset(args)");
        }
        return getScalarInt(1).getList();
    }

    public static RuntimeScalar stringConcat(RuntimeScalar runtimeScalar, RuntimeScalar b) {
        return new RuntimeScalar(runtimeScalar + b.toString());
    }

    public static RuntimeScalar repeat(RuntimeScalar runtimeScalar, RuntimeScalar arg) {
        return (RuntimeScalar) Operator.repeat(runtimeScalar, arg, RuntimeContextType.SCALAR);
    }

    public static RuntimeScalar not(RuntimeScalar runtimeScalar) {
        return getScalarBoolean(!runtimeScalar.getBoolean());
    }

    public RuntimeScalar refaddr() {
        switch (type) {
            case REFERENCE, ARRAYREFERENCE, HASHREFERENCE, CODE, GLOB, GLOBREFERENCE:
                return getScalarInt(System.identityHashCode(value));
            default:
                return scalarUndef;
        }
    }

    public RuntimeScalar reftype() {
        String type = switch (this.type) {
            case REFERENCE -> {
                if (value instanceof RuntimeScalar scalar) {
                    yield switch (scalar.type) {
                        // case ARRAYREFERENCE -> "ARRAY";
                        // case HASHREFERENCE -> "HASH";
                        case CODE -> "CODE";
                        case GLOB, GLOBREFERENCE -> "GLOB";
                        default -> "SCALAR";
                    };
                }
                yield "REF";
            }
            case ARRAYREFERENCE -> "ARRAY";
            case HASHREFERENCE -> "HASH";
            case CODE -> "CODE";
            case GLOB, GLOBREFERENCE -> "GLOB";
            default -> null;
        };

        return type != null ? new RuntimeScalar(type) : scalarUndef;
    }

    private void initializeWithLong(Long value) {
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            this.type = RuntimeScalarType.DOUBLE;
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
        return switch (type) {
            case INTEGER -> (int) value;
            case DOUBLE -> (int) ((double) value);
            case STRING -> NumberParser.parseNumber(this).getInt();
            case UNDEF -> 0;
            case VSTRING -> 0;
            case BOOLEAN -> (boolean) value ? 1 : 0;
            case REFERENCE, ARRAYREFERENCE, HASHREFERENCE -> Overload.numify(this).getInt();
            default -> ((RuntimeScalarReference) value).getIntRef();
        };
    }

    public long getLong() {
        return switch (type) {
            case INTEGER -> (int) value;
            case DOUBLE -> (long) ((double) value);
            case STRING -> NumberParser.parseNumber(this).getLong();
            case UNDEF -> 0L;
            case VSTRING -> 0L;
            case BOOLEAN -> (boolean) value ? 1L : 0L;
            case REFERENCE, ARRAYREFERENCE, HASHREFERENCE -> Overload.numify(this).getLong();
            default -> ((RuntimeScalarReference) value).getIntRef();
        };
    }

    public double getDouble() {
        return switch (this.type) {
            case INTEGER -> (int) this.value;
            case DOUBLE -> (double) this.value;
            case STRING -> NumberParser.parseNumber(this).getDouble();
            case UNDEF -> 0.0;
            case VSTRING -> 0.0;
            case BOOLEAN -> (boolean) value ? 1.0D : 0.0D;
            case REFERENCE, ARRAYREFERENCE, HASHREFERENCE -> Overload.numify(this).getDouble();
            default -> ((RuntimeScalarReference) value).getDoubleRef();
        };
    }

    public boolean getBoolean() {
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
            case REFERENCE, ARRAYREFERENCE, HASHREFERENCE -> Overload.boolify(this).getBoolean();
            default -> ((RuntimeScalarReference) value).getBooleanRef();
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
            this.type = RuntimeScalarType.DOUBLE;
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
            this.type = RuntimeScalarType.UNDEF;
        } else {
            this.type = RuntimeScalarType.STRING;
        }
        this.value = value;
        return this;
    }

    public RuntimeScalar set(RuntimeGlob val) {
        this.type = val.type;
        this.value = val;
        return this;
    }

    public RuntimeScalar set(RuntimeIO value) {
        this.type = RuntimeScalarType.GLOB;
        this.value = value;
        return this;
    }

    public RuntimeArray setFromList(RuntimeList value) {
        return new RuntimeArray(this.set(value.scalar()));
    }

    @Override
    public String toString() {
        return switch (type) {
            case INTEGER -> Integer.toString((int) value);
            case DOUBLE -> {
                String doubleString = Double.toString((double) value);
                // Remove trailing ".0" if present
                if (doubleString.endsWith(".0")) {
                    doubleString = doubleString.substring(0, doubleString.length() - 2);
                }
                yield doubleString;
            }
            case STRING -> (String) value;
            case UNDEF -> "";
            case GLOB -> value == null ? "" : value.toString();
            case REGEX -> value.toString();
            case VSTRING -> (String) value;
            case BOOLEAN -> (boolean) value ? "1" : "";
            case REFERENCE, ARRAYREFERENCE, HASHREFERENCE -> Overload.stringify(this).toString();
            case CODE -> this.toStringRef();
            case OBJECT -> value.toString();
            default -> ((RuntimeScalarReference) value).toStringRef();
        };
    }

    public String toStringRef() {
        String ref = switch (type) {
            case UNDEF -> "SCALAR(0x14500834042)";
            case CODE, GLOB -> {
                if (value == null) {
                    yield "CODE(0x14500834042)";
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
        switch (type) {
            case UNDEF:
                // hash autovivification
                type = RuntimeScalarType.HASHREFERENCE;
                value = new RuntimeHash();
            case HASHREFERENCE:
                return ((RuntimeHash) value).get(index.toString());
            case STRING:
                throw new PerlCompilerException("Can't use string (\"" + this + "\") as a HASH ref");
            case GLOB:
                ((RuntimeGlob) value).hashDerefGet(index);
            default:
                throw new PerlCompilerException("Not a HASH reference");
        }
    }

    // Method to implement `delete $v->{key}`
    public RuntimeScalar hashDerefDelete(RuntimeScalar index) {
        return switch (type) {
            case UNDEF -> new RuntimeScalar();
            case HASHREFERENCE -> ((RuntimeHash) value).delete(index);
            case STRING -> throw new PerlCompilerException("Can't use string (\"" + this + "\") as a HASH ref");
            default -> throw new PerlCompilerException("Not a HASH reference");
        };
    }

    // Method to implement `exists $v->{key}`
    public RuntimeScalar hashDerefExists(RuntimeScalar index) {
        return switch (type) {
            case UNDEF -> new RuntimeScalar();
            case HASHREFERENCE -> ((RuntimeHash) value).exists(index);
            case STRING -> throw new PerlCompilerException("Can't use string (\"" + this + "\") as a HASH ref");
            default -> throw new PerlCompilerException("Not a HASH reference");
        };
    }

    // Method to implement `$v->[10]`
    public RuntimeScalar arrayDerefGet(RuntimeScalar index) {
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

    // Method to implement `@$v`
    public RuntimeArray arrayDeref() {
        return switch (type) {
            case UNDEF -> throw new PerlCompilerException("Can't use an undefined value as an ARRAY reference");
            case ARRAYREFERENCE -> (RuntimeArray) value;
            case STRING ->
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as an ARRAY ref while \"strict refs\" in use");
            default -> throw new PerlCompilerException("Not an ARRAY reference");
        };
    }

    // Method to implement `%$v`
    public RuntimeHash hashDeref() {
        return switch (type) {
            case UNDEF -> throw new PerlCompilerException("Can't use an undefined value as an HASH reference");
            case HASHREFERENCE -> (RuntimeHash) value;
            case STRING ->
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as a HASH ref while \"strict refs\" in use");
            default -> throw new PerlCompilerException("Variable does not contain a hash reference");
        };
    }

    // Method to implement `$$v`
    public RuntimeScalar scalarDeref() {
        return switch (type) {
            case UNDEF -> throw new PerlCompilerException("Can't use an undefined value as a SCALAR reference");
            case REFERENCE -> (RuntimeScalar) value;
            case STRING ->
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as a SCALAR ref while \"strict refs\" in use");
            default -> throw new PerlCompilerException("Variable does not contain a scalar reference");
        };
    }

    // Method to implement `*$v`
    public RuntimeGlob globDeref() {
        return switch (type) {
            case UNDEF -> throw new PerlCompilerException("Can't use an undefined value as a GLOB reference");
            case GLOB, GLOBREFERENCE -> (RuntimeGlob) value;
            case STRING ->
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as a symbol ref while \"strict refs\" in use");
            default -> throw new PerlCompilerException("Variable does not contain a glob reference");
        };
    }

    public RuntimeScalar blessed() {
        return switch (type) {
            case REFERENCE, ARRAYREFERENCE, HASHREFERENCE: {
                int id = ((RuntimeBaseEntity) value).blessId;
                yield (id != 0 ? new RuntimeScalar(NameNormalizer.getBlessStr(id)) : scalarUndef);
            }
            default:
                yield scalarUndef;
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
        this.type = RuntimeScalarType.UNDEF;
        this.value = null;
        return this;
    }

    public RuntimeScalar defined() {
        if (type == RuntimeScalarType.CODE && !((RuntimeCode) value).defined()) {
            return scalarFalse;
        }
        if (type == RuntimeScalarType.BOOLEAN) {
            return (boolean) value ? scalarTrue : scalarFalse;
        }
        return getScalarBoolean(type != RuntimeScalarType.UNDEF);
    }

    public boolean getDefinedBoolean() {
        if (type == RuntimeScalarType.GLOB && value == null) {
            // getGlobalCodeRef returns an "empty glob" if the codeRef is not set
            // XXX TODO implement a better response in getGlobalCodeRef
            return false;
        }
        if (type == RuntimeScalarType.BOOLEAN) {
            return (boolean) value;
        }
        return type != RuntimeScalarType.UNDEF;
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
        String str = this.toString();
        if (str.isEmpty()) {
            return new RuntimeScalar();
        }
        String lastChar = str.substring(str.length() - 1);
        this.type = RuntimeScalarType.STRING;
        this.value = str.substring(0, str.length() - 1);
        return new RuntimeScalar(lastChar);
    }

    public RuntimeScalar chomp() {
        String str = this.toString();
        if (str.isEmpty()) {
            return getScalarInt(0);
        }

        RuntimeScalar separatorScalar = getGlobalVariable("main::/");
        if (separatorScalar.type == RuntimeScalarType.UNDEF) {
            // Slurp mode: don't remove anything
            return getScalarInt(0);
        }

        String separator = separatorScalar.toString();
        int charsRemoved = 0;

        if (separator.isEmpty()) {
            // Paragraph mode: remove all trailing newlines
            int endIndex = str.length();
            while (endIndex > 0 && str.charAt(endIndex - 1) == '\n') {
                endIndex--;
                charsRemoved++;
            }
            if (charsRemoved > 0) {
                str = str.substring(0, endIndex);
            }
        } else if (!separator.equals("\0")) {
            // Normal mode: remove trailing separator
            if (str.endsWith(separator)) {
                str = str.substring(0, str.length() - separator.length());
                charsRemoved = separator.length();
            }
        }
        // Note: In slurp mode ($/ = undef) or fixed-length record mode, we don't remove anything

        if (charsRemoved > 0) {
            this.type = RuntimeScalarType.STRING;
            this.value = str;
        }
        return getScalarInt(charsRemoved);
    }

    public RuntimeScalar integer() {
        return getScalarInt(getInt());
    }

    public RuntimeScalar pos() {
        return RuntimePosLvalue.pos(this);
    }

    public RuntimeScalar chr() {
        return new RuntimeScalar(String.valueOf((char) this.getInt()));
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
        this.type = RuntimeScalarType.UNDEF;
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

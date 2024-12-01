package org.perlonjava.runtime;

import org.perlonjava.ArgumentParser;
import org.perlonjava.operators.Operator;
import org.perlonjava.parser.NumberParser;
import org.perlonjava.perlmodule.Universal;
import org.perlonjava.scriptengine.PerlLanguageProvider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.perlonjava.runtime.GlobalVariable.getGlobalHash;
import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.*;
import static org.perlonjava.runtime.SpecialBlock.runEndBlocks;

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
    private static long currentSeed = System.currentTimeMillis();
    private static final Random random = new Random(currentSeed);
    // Fields to store the type and value of the scalar variable
    public RuntimeScalarType type;
    public Object value;

    // Constructors
    public RuntimeScalar() {
        this.type = RuntimeScalarType.UNDEF;
    }

    public RuntimeScalar(long value) {
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            this.type = RuntimeScalarType.DOUBLE;
            this.value = (double) value;
        } else {
            this.type = RuntimeScalarType.INTEGER;
            this.value = (int) value;
        }
    }

    public RuntimeScalar(int value) {
        this.type = RuntimeScalarType.INTEGER;
        this.value = value;
    }

    public RuntimeScalar(double value) {
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

    public RuntimeScalar(RuntimeGlob val) {
        if (value == null) {
            this.type = RuntimeScalarType.UNDEF;
        } else {
            this.type = val.type;
        }
        this.value = val;
    }

    public static RuntimeScalar undef() {
        return scalarUndef;
    }

    public static RuntimeScalar wantarray(int ctx) {
        return ctx == RuntimeContextType.VOID ? new RuntimeScalar() :
                new RuntimeScalar(ctx == RuntimeContextType.LIST ? 1 : 0);
    }

    public static RuntimeList reset(RuntimeList args, int ctx) {
        if (args.elements.isEmpty()) {
            RuntimeRegex.reset();
        } else {
            throw new PerlCompilerException("not implemented: reset(args)");
        }
        return getScalarInt(1).getList();
    }

    public RuntimeScalar exit() {
        try {
            runEndBlocks();
            RuntimeIO.flushFileHandles();
        } catch (Throwable t) {
            RuntimeIO.flushFileHandles();
            String errorMessage = ErrorMessageUtil.stringifyException(t);
            System.out.println(errorMessage);
            System.exit(1);
        }
        System.exit(this.getInt());
        return new RuntimeScalar(); // This line will never be reached
    }

    public RuntimeScalar study() {
        return scalarUndef;
    }

    public RuntimeScalar srand() {
        long oldSeed = currentSeed;
        if (this.type != RuntimeScalarType.UNDEF) {
            currentSeed = this.getInt();
        } else {
            // Semi-randomly choose a seed if no argument is provided
            currentSeed = System.nanoTime() ^ System.identityHashCode(Thread.currentThread());
        }
        random.setSeed(currentSeed);
        return new RuntimeScalar(oldSeed);
    }

    public RuntimeScalar clone() {
        return new RuntimeScalar(this);
    }

    public int countElements() {
        return 1;
    }

    // Implements the isa operator
    public RuntimeScalar isa(RuntimeScalar className) {
        RuntimeArray args = new RuntimeArray();
        args.push(this);
        args.push(className);
        return Universal.isa(args, RuntimeContextType.SCALAR).scalar();
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
            case DOUBLE -> Double.toString((double) value);
            case STRING -> (String) value;
            case UNDEF -> "";
            case GLOB -> value == null ? "" : value.toString();
            case REGEX -> value.toString();
            case VSTRING -> (String) value;
            case BOOLEAN -> (boolean) value ? "1" : "";
            default -> ((RuntimeScalarReference) value).toStringRef();
        };
    }

    public String toStringRef() {
        String ref = switch (type) {
            case UNDEF -> "SCALAR(0x14500834042)";
            case CODE -> ((RuntimeCode) value).toStringRef();
            case GLOB -> {
                if (value == null) {
                    yield "CODE(0x14500834042)";
                }
                yield ((RuntimeCode) value).toStringRef();
            }
            case VSTRING -> "VSTRING(0x" + value.hashCode() + ")";
            default -> "SCALAR(0x" + Integer.toHexString(value.hashCode()) + ")";
        };
        return (blessId == 0
                ? ref
                : NameNormalizer.getBlessStr(blessId) + "=" + ref);
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

    // Method to apply (execute) a subroutine reference
    public RuntimeList apply(RuntimeArray a, int callContext) {
        // Check if the type of this RuntimeScalar is CODE
        if (this.type == RuntimeScalarType.CODE) {
            // Cast the value to RuntimeCode and call apply()
            return ((RuntimeCode) this.value).apply(a, callContext);
        } else {
            // If the type is not CODE, throw an exception indicating an invalid state
            throw new PerlCompilerException("Not a CODE reference");
        }
    }

    // Method to apply (execute) a subroutine reference
    public RuntimeList apply(String subroutineName, RuntimeArray a, int callContext) {
        // Check if the type of this RuntimeScalar is CODE
        if (this.type == RuntimeScalarType.CODE) {
            // Cast the value to RuntimeCode and call apply()
            return ((RuntimeCode) this.value).apply(subroutineName, a, callContext);
        } else {
            // If the type is not CODE, throw an exception indicating an invalid state

            // Does AUTOLOAD exist?
            if (!subroutineName.isEmpty()) {
                // Check if AUTOLOAD exists
                String autoloadString = subroutineName.substring(0, subroutineName.lastIndexOf("::") + 2) + "AUTOLOAD";
                // System.err.println("AUTOLOAD: " + fullName);
                RuntimeScalar autoload = GlobalVariable.getGlobalCodeRef(autoloadString);
                if (autoload.getDefinedBoolean()) {
                    // System.err.println("AUTOLOAD exists: " + fullName);
                    // Set $AUTOLOAD name
                    getGlobalVariable(autoloadString).set(subroutineName);
                    // Call AUTOLOAD
                    return autoload.apply(a, callContext);
                }
            }

            if (!subroutineName.isEmpty() && this.type == RuntimeScalarType.GLOB && this.value == null) {
                throw new PerlCompilerException("Undefined subroutine &" + subroutineName + " called at ");
            }

            throw new PerlCompilerException("Not a CODE reference");
        }
    }

    /**
     * "Blesses" a Perl reference into an object by associating it with a class name.
     * This method is used to convert a Perl reference into an object of a specified class.
     *
     * @param className A RuntimeScalar representing the name of the class to bless the reference into.
     * @return A RuntimeScalar representing the blessed object.
     */
    public RuntimeScalar bless(RuntimeScalar className) {
        switch (type) {
            case REFERENCE:
            case ARRAYREFERENCE:
            case HASHREFERENCE:
                String str = className.toString();
                if (str.isEmpty()) {
                    str = "main";
                }
                ((RuntimeBaseEntity) value).setBlessId(NameNormalizer.getBlessId(str));
                break;
            default:
                throw new PerlCompilerException("Can't bless non-reference value");
        }
        return this;
    }

    public RuntimeScalar blessed() {
        return switch (type) {
            case REFERENCE:
            case ARRAYREFERENCE:
            case HASHREFERENCE: {
                int id = ((RuntimeBaseEntity) value).blessId;
                yield (id != 0
                        ? new RuntimeScalar(NameNormalizer.getBlessStr(id))
                        : scalarUndef);
            }
            default:
                yield scalarUndef;
        };
    }

    public RuntimeScalar ref() {
        String str;
        int blessId;
        switch (type) {
            case CODE:
                str = "CODE";
                break;
            case GLOB:
                str = "";
                break;
            case REGEX:
                str = "Regexp";
                break;
            case REFERENCE:
                String ref = "REF";
                if (value instanceof RuntimeScalar scalar) {
                    ref = switch (scalar.type) {
                        case VSTRING -> "VSTRING";
                        case REGEX, ARRAYREFERENCE, HASHREFERENCE, CODE, GLOBREFERENCE -> "REF";
                        case GLOB -> "GLOB";
                        default -> "SCALAR";
                    };
                }
                blessId = ((RuntimeBaseEntity) value).blessId;
                str = blessId == 0 ? ref : NameNormalizer.getBlessStr(blessId);
                break;
            case ARRAYREFERENCE:
                blessId = ((RuntimeBaseEntity) value).blessId;
                str = blessId == 0 ? "ARRAY" : NameNormalizer.getBlessStr(blessId);
                break;
            case HASHREFERENCE:
                blessId = ((RuntimeBaseEntity) value).blessId;
                str = blessId == 0 ? "HASH" : NameNormalizer.getBlessStr(blessId);
                break;
            case GLOBREFERENCE:
                str = "GLOB";
                break;
            default:
                return scalarEmptyString;
        }
        return new RuntimeScalar(str);
    }

    // Return a reference to this
    public RuntimeScalar createReference() {
        RuntimeScalar result = new RuntimeScalar();
        result.type = RuntimeScalarType.REFERENCE;
        result.value = this;
        return result;
    }

    // Return a reference to the subroutine with this name: \&$a
    public RuntimeScalar createCodeReference(String packageName) {
        String name = NameNormalizer.normalizeVariableName(this.toString(), packageName);
        // System.out.println("Creating code reference: " + name + " got: " + GlobalContext.getGlobalCodeRef(name));
        return GlobalVariable.getGlobalCodeRef(name);
    }

    public RuntimeScalar undefine() {
        this.type = RuntimeScalarType.UNDEF;
        this.value = null;
        return this;
    }

    public RuntimeScalar defined() {
        if (type == RuntimeScalarType.GLOB && value == null) {
            // getGlobalCodeRef returns an "empty glob" if the codeRef is not set
            // XXX TODO implement a better response in getGlobalCodeRef
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

    public RuntimeScalar stringConcat(RuntimeScalar b) {
        return new RuntimeScalar(this + b.scalar().toString());
    }

    public RuntimeScalar stringConcat(RuntimeDataProvider b) {
        return new RuntimeScalar(this + b.scalar().toString());
    }

    public RuntimeScalar not() {
        return getScalarBoolean(!this.getBoolean());
    }

    public RuntimeScalar prototype(String packageName) {
        RuntimeScalar code = this;
        if (code.type != RuntimeScalarType.CODE) {
            String name = NameNormalizer.normalizeVariableName(code.toString(), packageName);
            // System.out.println("Looking for prototype: " + name);
            code = GlobalVariable.getGlobalCodeRef(name);
        }
        // System.out.println("type: " + code.type);
        if (code.type == RuntimeScalarType.CODE) {
            // System.out.println("prototype: " + ((RuntimeCode) code.value).prototype);
            return new RuntimeScalar(((RuntimeCode) code.value).prototype);
        }
        return scalarUndef;
    }

    public RuntimeScalar repeat(RuntimeScalar arg) {
        return (RuntimeScalar) Operator.repeat(this, arg, RuntimeContextType.SCALAR);
    }

    public RuntimeScalar preAutoIncrement() {
        switch (type) {
            case INTEGER:
                this.value = (int) this.value + 1;
                return this;
            case DOUBLE:
                this.value = (double) this.value + 1;
                return this;
            case STRING:
                return ScalarUtils.stringIncrement(this);
            case BOOLEAN:
                this.type = RuntimeScalarType.INTEGER;
                this.value = this.getInt() + 1;
                return this;
        }
        this.type = RuntimeScalarType.INTEGER;
        this.value = 1;
        return this;
    }

    public RuntimeScalar postAutoIncrement() {
        RuntimeScalar old = new RuntimeScalar().set(this);
        switch (type) {
            case INTEGER:
                this.value = (int) this.value + 1;
                break;
            case DOUBLE:
                this.value = (double) this.value + 1;
                break;
            case STRING:
                ScalarUtils.stringIncrement(this);
                break;
            case BOOLEAN:
                this.type = RuntimeScalarType.INTEGER;
                this.value = this.getInt() + 1;
                break;
            default:
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
        }
        return old;
    }

    public RuntimeScalar preAutoDecrement() {
        switch (type) {
            case INTEGER:
                this.value = (int) this.value - 1;
                return this;
            case DOUBLE:
                this.value = (double) this.value - 1;
                return this;
            case STRING:
                // Handle numeric decrement
                this.set(NumberParser.parseNumber(this));
                return this.preAutoDecrement();
            case BOOLEAN:
                this.type = RuntimeScalarType.INTEGER;
                this.value = this.getInt() - 1;
                return this;
        }
        this.type = RuntimeScalarType.INTEGER;
        this.value = -1;
        return this;
    }

    public RuntimeScalar postAutoDecrement() {
        RuntimeScalar old = new RuntimeScalar().set(this);
        switch (type) {
            case INTEGER:
                this.value = (int) this.value - 1;
                break;
            case DOUBLE:
                this.value = (double) this.value - 1;
                break;
            case STRING:
                // Handle numeric decrement
                this.set(NumberParser.parseNumber(this));
                this.preAutoDecrement();
                break;
            case BOOLEAN:
                this.type = RuntimeScalarType.INTEGER;
                this.value = this.getInt() - 1;
                break;
            default:
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
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

    public RuntimeScalar rand() {
        return new RuntimeScalar(random.nextDouble() * this.getDouble());
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

    public RuntimeScalar require() {
        // https://perldoc.perl.org/functions/require

        if (this.type == RuntimeScalarType.INTEGER || this.type == RuntimeScalarType.DOUBLE || this.type == RuntimeScalarType.VSTRING || this.type == RuntimeScalarType.BOOLEAN) {
            // `require VERSION`
            Universal.compareVersion(
                    new RuntimeScalar(GlobalContext.perlVersion),
                    this,
                    "Perl");
            return getScalarInt(1);
        }

        // Look up the file name in %INC
        String fileName = this.toString();
        if (getGlobalHash("main::INC").elements.containsKey(fileName)) {
            // module was already loaded
            return getScalarInt(1);
        }

        // Call `do` operator
        RuntimeScalar result = this.doFile(); // `do "fileName"`
        // Check if `do` returned a true value
        if (!result.defined().getBoolean()) {
            // `do FILE` returned undef
            String err = getGlobalVariable("main::@").toString();
            String ioErr = getGlobalVariable("main::!").toString();
            throw new PerlCompilerException(err.isEmpty() ? "Can't locate " + fileName + ": " + ioErr : "Compilation failed in require: " + err);
        }
        return result;
    }

    public RuntimeScalar doFile() {
        // `do` file
        String fileName = this.toString();
        Path fullName = null;
        Path filePath = Paths.get(fileName);
        String code = null;

        // If the filename is an absolute path or starts with ./ or ../, use it directly
        if (filePath.isAbsolute() || fileName.startsWith("./") || fileName.startsWith("../")) {
            fullName = Files.exists(filePath) ? filePath : null;
        } else {
            // Otherwise, search in INC directories
            List<RuntimeScalar> inc = GlobalVariable.getGlobalArray("main::INC").elements;
            for (RuntimeBaseEntity dir : inc) {
                Path fullPath = Paths.get(dir.toString(), fileName);
                if (Files.exists(fullPath)) {
                    fullName = fullPath;
                    break;
                }
            }
        }
        if (fullName == null) {
            // If not found in file system, try to find in jar at "src/main/perl/lib"
            String resourcePath = "/lib/" + fileName;
            URL resource = RuntimeScalar.class.getResource(resourcePath);
            // System.out.println("Found resource " + resource);
            if (resource != null) {

                String path = resource.getPath();
                // Remove leading slash if on Windows
                if (System.getProperty("os.name").toLowerCase().contains("win") && path.startsWith("/")) {
                    path = path.substring(1);
                }
                fullName = Paths.get(path);

                try (InputStream is = resource.openStream();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                    StringBuilder content = new StringBuilder();
                    String line = null;
                    while ((line = reader.readLine()) != null) {
                        content.append(line).append("\n");
                    }
                    // System.out.println("Content of " + resourcePath + ": " + content.toString());
                    code = content.toString();
                } catch (IOException e1) {
                    GlobalVariable.setGlobalVariable("main::!", "No such file or directory");
                    return new RuntimeScalar();
                }
            }
        }
        if (fullName == null) {
            GlobalVariable.setGlobalVariable("main::!", "No such file or directory");
            return new RuntimeScalar();
        }

        ArgumentParser.CompilerOptions parsedArgs = new ArgumentParser.CompilerOptions();
        parsedArgs.fileName = fullName.toString();
        if (code == null) {
            try {
                code = new String(Files.readAllBytes(Paths.get(parsedArgs.fileName)));
            } catch (IOException e) {
                GlobalVariable.setGlobalVariable("main::!", "Unable to read file " + parsedArgs.fileName);
                return new RuntimeScalar();
            }
        }
        parsedArgs.code = code;

        // set %INC
        getGlobalHash("main::INC").put(fileName, new RuntimeScalar(parsedArgs.fileName));

        RuntimeList result;
        try {
            result = PerlLanguageProvider.executePerlCode(parsedArgs, false);
        } catch (Throwable t) {
            GlobalVariable.setGlobalVariable("main::@", "Error in file " + parsedArgs.fileName +
                    "\n" + t);
            return new RuntimeScalar();
        }

        return result == null ? scalarUndef : result.scalar();
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
        RuntimeIO fh;
        if (type == RuntimeScalarType.GLOBREFERENCE) {
            // my $fh2 = \*STDOUT;
            // System.out.println("GLOBREFERENCE");
            String globName = ((RuntimeGlob) value).globName;
            fh = (RuntimeIO) GlobalVariable.getGlobalIO(globName).value;
        } else if (type == RuntimeScalarType.GLOB) {
            // my $fh = *STDOUT;
            if (value instanceof RuntimeGlob) {
                // System.out.println("GLOB");
                String globName = ((RuntimeGlob) value).globName;
                fh = (RuntimeIO) GlobalVariable.getGlobalIO(globName).value;
            } else {
                // System.out.println("GLOB but IO");
                fh = (RuntimeIO) value;
            }
        } else {
            // print STDOUT ...
            // System.out.println("IO");
            fh = (RuntimeIO) value;
            // throw  new PerlCompilerException("Invalid fileHandle type: " + fileHandle.type);
        }
        return fh;
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

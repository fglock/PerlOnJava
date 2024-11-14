package org.perlonjava.runtime;

import org.perlonjava.ArgumentParser;
import org.perlonjava.operators.MathOperators;
import org.perlonjava.operators.Operator;
import org.perlonjava.operators.WarnDie;
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
import java.util.concurrent.TimeUnit;

import static org.perlonjava.runtime.ErrorMessageUtil.stringifyException;
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
        if (value) {
            this.type = RuntimeScalarType.INTEGER;
            this.value = 1;
        } else {
            this.type = RuntimeScalarType.UNDEF;
        }
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
        this.type = RuntimeScalarType.GLOB;
        this.value = value;
    }

    public static RuntimeScalar undef() {
        return scalarUndef;
    }

    public static RuntimeScalar wantarray(int ctx) {
        return ctx == RuntimeContextType.VOID ? new RuntimeScalar() :
                new RuntimeScalar(ctx == RuntimeContextType.LIST ? 1 : 0);
    }

    public static RuntimeList caller(RuntimeList args, int ctx) {
        RuntimeList res = new RuntimeList();
        int frame = 0;
        if (!args.elements.isEmpty()) {
            frame = ((RuntimeScalar) args.elements.getFirst()).getInt();
        }
        CallerStack.CallerInfo info = CallerStack.peek();
        if (info == null) {
            // Runtime stack trace
            Throwable t = new Throwable();
            ArrayList<ArrayList<String>> stackTrace = ExceptionFormatter.formatException(t);
            frame++;  // frame 0 is the current method, so we need to increment it
            if (frame >= 0 && frame < stackTrace.size()) {
                if (ctx == RuntimeContextType.SCALAR) {
                    res.add(new RuntimeScalar(stackTrace.get(frame).getFirst()));
                } else {
                    res.add(new RuntimeScalar(stackTrace.get(frame).get(0)));
                    res.add(new RuntimeScalar(stackTrace.get(frame).get(1)));
                    res.add(new RuntimeScalar(stackTrace.get(frame).get(2)));
                }
            }
        } else {
            // Compile-time stack trace
            if (ctx == RuntimeContextType.SCALAR) {
                res.add(new RuntimeScalar(info.packageName));
            } else {
                res.add(new RuntimeScalar(info.packageName));
                res.add(new RuntimeScalar(info.filename));
                res.add(new RuntimeScalar(info.line));
            }
        }
        return res;
    }

    public static RuntimeList reset(RuntimeList args, int ctx) {
        RuntimeList res = new RuntimeList();
        if (args.elements.isEmpty()) {
            RuntimeRegex.reset();
        } else {
            throw new PerlCompilerException("not implemented: reset(args)");
        }
        res.add(getScalarInt(1));
        return res;
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

    public RuntimeScalar set(String value) {
        if (value == null) {
            this.type = RuntimeScalarType.UNDEF;
        } else {
            this.type = RuntimeScalarType.STRING;
        }
        this.value = value;
        return this;
    }

    public RuntimeScalar set(RuntimeGlob value) {
        this.type = RuntimeScalarType.GLOB;
        this.value = value;
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
            case GLOB -> value.toString();
            case REGEX -> value.toString();
            case VSTRING -> (String) value;
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
            default -> "SCALAR(0x" + value.hashCode() + ")";
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
            default -> throw new PerlCompilerException("Not an ARRAY reference");
        };
    }

    // Method to implement `%$v`
    public RuntimeHash hashDeref() {
        return switch (type) {
            case UNDEF -> throw new PerlCompilerException("Can't use an undefined value as an HASH reference");
            case HASHREFERENCE -> (RuntimeHash) value;
            default -> throw new PerlCompilerException("Variable does not contain an hash reference");
        };
    }

    // Method to implement `$$v`
    public RuntimeScalar scalarDeref() {
        return switch (type) {
            case UNDEF -> throw new PerlCompilerException("Can't use an undefined value as a SCALAR reference");
            case REFERENCE -> (RuntimeScalar) value;
            default -> throw new PerlCompilerException("Variable does not contain a scalar reference");
        };
    }

    // Method to implement `*$v`
    public RuntimeGlob globDeref() {
        return switch (type) {
            case UNDEF -> throw new PerlCompilerException("Can't use an undefined value as a GLOB reference");
            case GLOB, GLOBREFERENCE -> (RuntimeGlob) value;
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

    // Method to "bless" a Perl reference into an object
    public RuntimeScalar bless(RuntimeScalar className) {
        switch (type) {
            case REFERENCE:
            case ARRAYREFERENCE:
            case HASHREFERENCE:
                String str = className.toString();
                if (str.isEmpty()) {
                    str = "main";
                }
                ((RuntimeBaseEntity) value).blessId = NameNormalizer.getBlessId(str);
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
                str = "GLOB";
                break;
            case REGEX:
                str = "Regexp";
                break;
            case REFERENCE:
                String ref = "REF";
                if (value instanceof RuntimeScalar scalar) {
                    if (scalar.type == RuntimeScalarType.VSTRING) {
                        ref = "VSTRING";
                    }
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
            default:
                return scalarEmptyString;
        }
        return new RuntimeScalar(str);
    }

    /**
     * Call a method in a Perl-like class hierarchy using the C3 linearization algorithm.
     *
     * @param method      The method to resolve.
     * @param args        The arguments to pass to the method.
     * @param callContext The call context.
     * @return The result of the method call.
     */
    public RuntimeList call(RuntimeScalar method, RuntimeArray args, int callContext) throws Exception {
        // insert `this` into the parameter list
        args.elements.addFirst(this);

        if (method.type == RuntimeScalarType.CODE) {
            // If method is a subroutine reference, just call it
            return method.apply(args, callContext);
        }

        String methodName = method.toString();

        // Retrieve Perl class name
        String perlClassName;
        switch (type) {
            case REFERENCE:
            case ARRAYREFERENCE:
            case HASHREFERENCE:
                int blessId = ((RuntimeBaseEntity) value).blessId;
                if (blessId == 0) {
                    throw new PerlCompilerException("Can't call method \"" + methodName + "\" on unblessed reference");
                }
                perlClassName = NameNormalizer.getBlessStr(blessId);
                break;
            case UNDEF:
                throw new PerlCompilerException("Can't call method \"" + methodName + "\" on an undefined value");
            default:
                perlClassName = this.toString();
                if (perlClassName.isEmpty()) {
                    throw new PerlCompilerException("Can't call method \"" + methodName + "\" on an undefined value");
                }
                if (perlClassName.endsWith("::")) {
                    perlClassName = perlClassName.substring(0, perlClassName.length() - 2);
                }
        }

        // Method name can be:
        // - Fully qualified
        // - A variable or dereference (e.g., $file->${ \'save' })

        // Class name can be:
        // - A string
        // - STDOUT
        // - A subroutine (e.g., Class->new() is Class()->new() if Class is a subroutine)
        // - Class::->new() is the same as Class->new()

        // Check the method cache
        String normalizedMethodName = NameNormalizer.normalizeVariableName(methodName, perlClassName);
        RuntimeScalar cachedMethod = InheritanceResolver.getCachedMethod(normalizedMethodName);
        if (cachedMethod != null) {
            return cachedMethod.apply(args, callContext);
        }

        // Get the linearized inheritance hierarchy using C3
        List<String> linearizedClasses = InheritanceResolver.linearizeC3(perlClassName);

        // Iterate over the linearized classes to find the method
        for (String className : linearizedClasses) {
            String normalizedClassMethodName = NameNormalizer.normalizeVariableName(methodName, className);
            if (GlobalVariable.existsGlobalCodeRef(normalizedClassMethodName)) {
                // If the method is found, retrieve and apply it
                RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(normalizedClassMethodName);

                // Save the method in the cache
                InheritanceResolver.cacheMethod(normalizedMethodName, codeRef);

                return codeRef.apply(args, callContext);
            }
        }

        // If the method is not found in any class, throw an exception
        throw new PerlCompilerException("Can't locate object method \"" + methodName + "\" via package \"" + perlClassName + "\" (perhaps you forgot to load \"" + perlClassName + "\"?)");
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
        return getScalarBoolean(type != RuntimeScalarType.UNDEF);
    }

    public boolean getDefinedBoolean() {
        return type != RuntimeScalarType.UNDEF;
    }

    public RuntimeScalar stringConcat(RuntimeScalar b) {
        return new RuntimeScalar(this + b.scalar().toString());
    }

    public RuntimeScalar stringConcat(RuntimeDataProvider b) {
        return new RuntimeScalar(this + b.scalar().toString());
    }

    public RuntimeScalar unaryMinus() {
        if (this.type == RuntimeScalarType.STRING) {
            String input = this.toString();
            if (input.length() < 2) {
                if (input.isEmpty()) {
                    return getScalarInt(0);
                }
                if (input.equals("-")) {
                    return new RuntimeScalar("+");
                }
                if (input.equals("+")) {
                    return new RuntimeScalar("-");
                }
            }
            if (input.matches("^[-+]?[_A-Za-z].*")) {
                if (input.startsWith("-")) {
                    // Handle case where string starts with "-"
                    return new RuntimeScalar("+" + input.substring(1));
                } else if (input.startsWith("+")) {
                    // Handle case where string starts with "+"
                    return new RuntimeScalar("-" + input.substring(1));
                } else {
                    return new RuntimeScalar("-" + input);
                }
            }
        }
        return MathOperators.subtract(getScalarInt(0), this);
    }

    public RuntimeScalar not() {
        return getScalarBoolean(!this.getBoolean());
    }

    public RuntimeScalar prototype(String packageName) {
        RuntimeScalar code = this;
        if (code.type != RuntimeScalarType.CODE) {
            String name = NameNormalizer.normalizeVariableName(code.toString(), packageName);
            System.out.println("Looking for prototype: " + name);
            code = GlobalVariable.getGlobalCodeRef(name);
        }
        System.out.println("type: " + code.type);
        if (code.type == RuntimeScalarType.CODE) {
            System.out.println("prototype: " + ((RuntimeCode) code.value).prototype);
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

    public RuntimeScalar oct() {
        String expr = this.toString();
        int result = 0;

        // Remove leading and trailing whitespace
        expr = expr.trim();

        // Remove underscores as they are ignored in Perl's oct()
        expr = expr.replace("_", "");

        int length = expr.length();
        int start = 0;
        if (expr.startsWith("0")) {
            start++;
        }

        if (expr.charAt(start) == 'x' || expr.charAt(start) == 'X') {
            // Hexadecimal string
            start++;
            for (int i = start; i < length; i++) {
                char c = expr.charAt(i);
                int digit = Character.digit(c, 16); // Converts '0'-'9', 'A'-'F', 'a'-'f' to 0-15

                // Stop if an invalid character is encountered
                if (digit == -1) {
                    break;
                }
                result = result * 16 + digit;
            }
        } else if (expr.charAt(start) == 'b' || expr.charAt(start) == 'B') {
            // Binary string
            start++;
            for (int i = start; i < length; i++) {
                char c = expr.charAt(i);
                if (c < '0' || c > '1') {
                    break;
                }
                result = result * 2 + (c - '0');
            }
        } else {
            // Octal string
            if (expr.charAt(start) == 'o' || expr.charAt(start) == 'O') {
                start++;
            }
            for (int i = start; i < length; i++) {
                char c = expr.charAt(i);
                if (c < '0' || c > '7') {
                    break;
                }
                result = result * 8 + (c - '0');
            }
        }
        return getScalarInt(result);
    }

    public RuntimeScalar ord() {
        String str = this.toString();
        int i;
        if (str.isEmpty()) {
            i = 0;
        } else {
            i = str.charAt(0);
        }
        return getScalarInt(i);
    }

    public RuntimeScalar chr() {
        return new RuntimeScalar(String.valueOf((char) this.getInt()));
    }

    public RuntimeScalar hex() {
        String expr = this.toString();
        int result = 0;

        // Remove underscores as they are ignored in Perl's hex()
        expr = expr.replace("_", "");

        int start = 0;
        if (expr.startsWith("0")) {
            start++;
        }
        if (expr.charAt(start) == 'x' || expr.charAt(start) == 'X') {
            start++;
        }
        // Convert each valid hex character
        for (int i = start; i < expr.length(); i++) {
            char c = expr.charAt(i);
            int digit = Character.digit(c, 16); // Converts '0'-'9', 'A'-'F', 'a'-'f' to 0-15

            // Stop if an invalid character is encountered
            if (digit == -1) {
                break;
            }

            result = result * 16 + digit;
        }
        return getScalarInt(result);
    }

    public RuntimeScalar sleep() {
        RuntimeIO.flushFileHandles();

        long s = (long) this.getDouble() * 1000;

        if (s < 0) {
            getGlobalVariable("main::!").set("Invalid argument");
            WarnDie.warn(
                    new RuntimeScalar(stringifyException(
                            new PerlCompilerException("sleep() with negative argument")
                    )),
                    new RuntimeScalar());
            return getScalarInt(0);
        }

        long startTime = System.currentTimeMillis();
        try {
            TimeUnit.MILLISECONDS.sleep(s);
        } catch (InterruptedException e) {
            // Handle interruption if needed
            RuntimeScalar alarmHandler = getGlobalHash("main::SIG").get("ALRM");
            if (alarmHandler.getDefinedBoolean()) {
                RuntimeArray args = new RuntimeArray();
                alarmHandler.apply(args, RuntimeContextType.SCALAR);
            }
        }
        long endTime = System.currentTimeMillis();
        long actualSleepTime = endTime - startTime;
        return new RuntimeScalar(actualSleepTime / 1000.0);
    }

    public RuntimeScalar require() {
        // https://perldoc.perl.org/functions/require

        if (this.type == RuntimeScalarType.INTEGER || this.type == RuntimeScalarType.DOUBLE || this.type == RuntimeScalarType.VSTRING) {
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
        throw new PerlCompilerException("Type of arg 1 to values must be hash or array");
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

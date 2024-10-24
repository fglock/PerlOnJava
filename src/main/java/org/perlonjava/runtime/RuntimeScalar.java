package org.perlonjava.runtime;

import com.ibm.icu.text.CaseMap;
import com.ibm.icu.text.Normalizer2;
import org.perlonjava.ArgumentParser;
import org.perlonjava.codegen.DynamicState;
import org.perlonjava.operators.ArithmeticOperators;
import org.perlonjava.operators.Operator;
import org.perlonjava.perlmodule.Universal;
import org.perlonjava.scriptengine.PerlLanguageProvider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.perlonjava.runtime.GlobalContext.getGlobalVariable;
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

    private static final int MAX_NUMIFICATION_CACHE_SIZE = 1000;
    private static final Map<String, RuntimeScalar> numificationCache = new LinkedHashMap<String, RuntimeScalar>(MAX_NUMIFICATION_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RuntimeScalar> eldest) {
            return size() > MAX_NUMIFICATION_CACHE_SIZE;
        }
    };
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

    public static RuntimeScalar undef() {
        return scalarUndef;
    }

    public static RuntimeScalar wantarray(int ctx) {
        return ctx == RuntimeContextType.VOID ? new RuntimeScalar() :
                new RuntimeScalar(ctx == RuntimeContextType.LIST ? 1 : 0);
    }

    public static RuntimeScalar time() {
        return new RuntimeScalar(System.currentTimeMillis() / 1000L);
    }

    public static RuntimeList times() {
        RuntimeList res = new RuntimeList();

        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        long cpu = bean.isCurrentThreadCpuTimeSupported() ?
                bean.getCurrentThreadCpuTime() : 0L;
        long user = bean.isCurrentThreadCpuTimeSupported() ?
                bean.getCurrentThreadUserTime() : 0L;
        long system = cpu - user;

        res.add(user / 1.0E9); // user CPU time
        res.add(system / 1.0E9); // System CPU time
        res.add(0); // we don't have this information
        res.add(0); // we don't have this information
        return res;
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

    public static RuntimeList localtime(RuntimeList args, int ctx) {
        RuntimeList res = new RuntimeList();
        ZonedDateTime date;
        if (args.elements.isEmpty()) {
            date = ZonedDateTime.now();
        } else {
            long arg = ((RuntimeScalar) args.elements.getFirst()).getInt();
            date = Instant.ofEpochSecond(arg).atZone(ZoneId.systemDefault());
        }
        if (ctx == RuntimeContextType.SCALAR) {
            res.add(date.format(DateTimeFormatter.RFC_1123_DATE_TIME));
            return res;
        }
        //      0    1    2     3     4    5     6     7     8
        //   ($sec,$min,$hour,$mday,$mon,$year,$wday,$yday,$isdst)
        res.add(date.getSecond());
        res.add(date.getMinute());
        res.add(date.getHour());
        res.add(date.getDayOfMonth());
        res.add(date.getMonth().getValue() - 1);
        res.add(date.getYear() - 1900);
        res.add(date.getDayOfWeek().getValue());
        res.add(date.getDayOfYear() - 1);
        res.add(date.getZone().getRules().isDaylightSavings(date.toInstant()) ? 1 : 0);
        return res;
    }

    public static RuntimeList gmtime(RuntimeList args, int ctx) {
        RuntimeList res = new RuntimeList();
        ZonedDateTime date;
        if (args.elements.isEmpty()) {
            date = ZonedDateTime.now(ZoneOffset.UTC);
        } else {
            long arg = ((RuntimeScalar) args.elements.getFirst()).getInt();
            date = Instant.ofEpochSecond(arg).atZone(ZoneId.of("UTC"));
        }
        if (ctx == RuntimeContextType.SCALAR) {
            res.add(date.format(DateTimeFormatter.RFC_1123_DATE_TIME));
            return res;
        }
        //      0    1    2     3     4    5     6     7     8
        //   ($sec,$min,$hour,$mday,$mon,$year,$wday,$yday,$isdst)
        res.add(date.getSecond());
        res.add(date.getMinute());
        res.add(date.getHour());
        res.add(date.getDayOfMonth());
        res.add(date.getMonth().getValue() - 1);
        res.add(date.getYear() - 1900);
        res.add(date.getDayOfWeek().getValue());
        res.add(date.getDayOfYear() - 1);
        res.add(date.getZone().getRules().isDaylightSavings(date.toInstant()) ? 1 : 0);
        return res;
    }

    public RuntimeScalar exit() {
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

    public RuntimeScalar rmdir() {
        String dirName = value.toString();

        try {
            Path path = Paths.get(dirName);
            Files.delete(path);
            return scalarTrue;
        } catch (IOException e) {
            // Set $! (errno) in case of failure
            getGlobalVariable("main::!").set(e.getMessage());
            return scalarFalse;
        }
    }

    public RuntimeScalar closedir() {
        if (type != RuntimeScalarType.GLOB) {
            throw new PerlCompilerException("Invalid directory handle");
        }

        RuntimeIO dirIO = (RuntimeIO) value;
        return dirIO.closedir();
    }

    public RuntimeScalar rewinddir() {
        if (type != RuntimeScalarType.GLOB) {
            throw new PerlCompilerException("Invalid directory handle");
        }

        RuntimeIO dirIO = (RuntimeIO) value;
        dirIO.rewinddir();
        return scalarTrue;
    }

    public RuntimeScalar telldir() {
        if (type != RuntimeScalarType.GLOB) {
            throw new PerlCompilerException("Invalid directory handle");
        }

        RuntimeIO dirIO = (RuntimeIO) value;
        return new RuntimeScalar(dirIO.telldir());
    }

    public boolean looksLikeNumber() {
        switch (this.type) {
            case INTEGER:
            case DOUBLE:
                return true;
            case STRING:
                String str = this.toString().trim();
                if (str.isEmpty()) {
                    return false;
                }
                // Check for Inf and NaN
                if (str.equalsIgnoreCase("Inf") || str.equalsIgnoreCase("Infinity") || str.equalsIgnoreCase("NaN")) {
                    return true;
                }
                // Check for decimal (integer or float)
                try {
                    Double.parseDouble(str);
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }
            case UNDEF:
                return false;
            case GLOB:
            case ARRAYREFERENCE:
            case HASHREFERENCE:
            case CODE:
                // These types don't look like numbers in Perl
                return false;
            default:
                throw new PerlCompilerException("Unexpected value: " + this.type);
        }
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
            case STRING -> this.parseNumber().getInt();
            case UNDEF -> 0;
            default -> ((RuntimeScalarReference) value).getIntRef();
        };
    }

    public long getLong() {
        return switch (type) {
            case INTEGER -> (int) value;
            case DOUBLE -> (long) ((double) value);
            case STRING -> this.parseNumber().getLong();
            case UNDEF -> 0L;
            default -> ((RuntimeScalarReference) value).getIntRef();
        };
    }

    public double getDouble() {
        return switch (this.type) {
            case INTEGER -> (int) this.value;
            case DOUBLE -> (double) this.value;
            case STRING -> this.parseNumber().getDouble();
            case UNDEF -> 0.0;
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
        this.type = RuntimeScalarType.STRING;
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
            default -> ((RuntimeScalarReference) value).toStringRef();
        };
    }

    public String toStringRef() {
        return switch (type) {
            case UNDEF -> "REF(0x14500834042)";
            case CODE -> ((RuntimeCode) value).toStringRef();
            case GLOB -> {
                if (value == null) {
                    yield "CODE(0x14500834042)";
                }
                yield ((RuntimeCode) value).toStringRef();
            }
            default -> "REF(" + value.hashCode() + ")";
        };
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
                throw new PerlCompilerException("Can't use string (\"" + this.toString() + "\") as a HASH ref");
            default:
                throw new PerlCompilerException("Not a HASH reference");
        }
    }

    // Method to implement `delete $v->{key}`
    public RuntimeScalar hashDerefDelete(RuntimeScalar index) {
        return switch (type) {
            case UNDEF -> new RuntimeScalar();
            case HASHREFERENCE -> ((RuntimeHash) value).delete(index);
            case STRING ->
                    throw new PerlCompilerException("Can't use string (\"" + this.toString() + "\") as a HASH ref");
            default -> throw new PerlCompilerException("Not a HASH reference");
        };
    }

    // Method to implement `exists $v->{key}`
    public RuntimeScalar hashDerefExists(RuntimeScalar index) {
        return switch (type) {
            case UNDEF -> new RuntimeScalar();
            case HASHREFERENCE -> ((RuntimeHash) value).exists(index);
            case STRING ->
                    throw new PerlCompilerException("Can't use string (\"" + this.toString() + "\") as a HASH ref");
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
            case REFERENCE:
                blessId = ((RuntimeBaseEntity) value).blessId;
                str = blessId == 0 ? "REF" : NameNormalizer.getBlessStr(blessId);
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
            if (GlobalContext.existsGlobalCodeRef(normalizedClassMethodName)) {
                // If the method is found, retrieve and apply it
                RuntimeScalar codeRef = GlobalContext.getGlobalCodeRef(normalizedClassMethodName);

                // Save the method in the cache
                InheritanceResolver.cacheMethod(normalizedMethodName, codeRef);

                return codeRef.apply(args, callContext);
            }
        }

        // If the method is not found in any class, throw an exception
        throw new PerlCompilerException("Can't locate object method \"" + methodName + "\" via package \"" + perlClassName + "\" (perhaps you forgot to load \"" + perlClassName + "\"?)");
    }

    // Helper method to autoincrement a String variable
    private RuntimeScalar stringIncrement() {
        // Retrieve the current value as a String
        String str = (String) this.value;

        // Check if the string is empty
        if (str.isEmpty()) {
            // If empty, set the value to 1 and update type to INTEGER
            this.value = 1;
            this.type = RuntimeScalarType.INTEGER; // RuntimeScalarType is an enum that holds different scalar types
            return this; // Return the current instance
        }

        // Get the first character of the string
        char c = str.charAt(0);

        // Check if the first character is a letter (either uppercase or lowercase)
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
            this.value = ScalarUtils.incrementPlainString(str);
            return this; // Return the current instance after increment
        }

        // Handle numeric increment: parse the number and increment it
        this.set(this.parseNumber()); // parseNumber parses the current string to a number
        return this.preAutoIncrement(); // preAutoIncrement handles the actual incrementing logic
    }

    public RuntimeScalar parseNumber() {
        String str = (String) this.value;

        // Check cache first
        RuntimeScalar result = numificationCache.get(str);
        if (result != null) {
            return result;
        }

        int length = str.length();
        int start = 0, end = length;

        // Trim leading and trailing whitespace
        while (start < length && Character.isWhitespace(str.charAt(start))) start++;
        while (end > start && Character.isWhitespace(str.charAt(end - 1))) end--;

        if (start == end) {
            result = getScalarInt(0);
        } else {

            boolean hasDecimal = false;
            boolean hasExponent = false;
            boolean isNegative = false;
            int exponentPos = -1;
            int numberEnd = start;

            char firstChar = str.charAt(start);
            if (firstChar == '-' || firstChar == '+') {
                isNegative = (firstChar == '-');
                start++;
            }

            for (int i = start; i < end; i++) {
                char c = str.charAt(i);
                if (Character.isDigit(c)) {
                    numberEnd = i + 1;
                } else if (c == '.' && !hasDecimal && !hasExponent) {
                    hasDecimal = true;
                    numberEnd = i + 1;
                } else if ((c == 'e' || c == 'E') && !hasExponent) {
                    hasExponent = true;
                    exponentPos = i;
                    if (i + 1 < end && (str.charAt(i + 1) == '-' || str.charAt(i + 1) == '+')) {
                        i++;
                    }
                } else {
                    break;
                }
            }

            if (hasExponent && exponentPos == numberEnd - 1) {
                // Invalid exponent, remove it
                hasExponent = false;
                numberEnd = exponentPos;
            }

            if (numberEnd == start) return getScalarInt(0);

            try {
                String numberStr = str.substring(start, numberEnd);
                if (hasDecimal || hasExponent) {
                    double value = Double.parseDouble(numberStr);
                    result = new RuntimeScalar(isNegative ? -value : value);
                } else {
                    int value = Integer.parseInt(numberStr);
                    result = getScalarInt(isNegative ? -value : value);
                }
            } catch (NumberFormatException e) {
                // If integer parsing fails, try parsing as double
                try {
                    double value = Double.parseDouble(str.substring(start, numberEnd));
                    result = new RuntimeScalar(isNegative ? -value : value);
                } catch (NumberFormatException e2) {
                    result = getScalarInt(0);
                }
            }
        }

        // Cache the result if the cache is not full
        if (numificationCache.size() < MAX_NUMIFICATION_CACHE_SIZE) {
            numificationCache.put(str, result);
        }

        return result;
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
        return GlobalContext.getGlobalCodeRef(name);
    }

    public RuntimeScalar undefine() {
        this.type = RuntimeScalarType.UNDEF;
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
        return ArithmeticOperators.subtract(getScalarInt(0), this);
    }

    public RuntimeScalar not() {
        return getScalarBoolean(!this.getBoolean());
    }

    public RuntimeScalar repeat(RuntimeScalar arg) {
        return (RuntimeScalar) Operator.repeat(this, arg, RuntimeContextType.SCALAR);
    }

    public RuntimeScalar lessThan(RuntimeScalar arg2) {
        RuntimeScalar arg1 = this;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return getScalarBoolean(arg1.getDouble() < arg2.getDouble());
        } else {
            return getScalarBoolean(arg1.getInt() < arg2.getInt());
        }
    }

    public RuntimeScalar lessThanOrEqual(RuntimeScalar arg2) {
        RuntimeScalar arg1 = this;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return getScalarBoolean(arg1.getDouble() <= arg2.getDouble());
        } else {
            return getScalarBoolean(arg1.getInt() <= arg2.getInt());
        }
    }

    public RuntimeScalar greaterThan(RuntimeScalar arg2) {
        RuntimeScalar arg1 = this;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return getScalarBoolean(arg1.getDouble() > arg2.getDouble());
        } else {
            return getScalarBoolean(arg1.getInt() > arg2.getInt());
        }
    }

    public RuntimeScalar greaterThanOrEqual(RuntimeScalar arg2) {
        RuntimeScalar arg1 = this;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return getScalarBoolean(arg1.getDouble() >= arg2.getDouble());
        } else {
            return getScalarBoolean(arg1.getInt() >= arg2.getInt());
        }
    }

    public RuntimeScalar equalTo(int arg2) {
        RuntimeScalar arg1 = this;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE) {
            return getScalarBoolean(arg1.getDouble() == (double) arg2);
        } else {
            return getScalarBoolean(arg1.getInt() == arg2);
        }
    }

    public RuntimeScalar equalTo(RuntimeScalar arg2) {
        RuntimeScalar arg1 = this;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return getScalarBoolean(arg1.getDouble() == arg2.getDouble());
        } else {
            return getScalarBoolean(arg1.getInt() == arg2.getInt());
        }
    }

    public RuntimeScalar notEqualTo(RuntimeScalar arg2) {
        RuntimeScalar arg1 = this;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return getScalarBoolean(arg1.getDouble() != arg2.getDouble());
        } else {
            return getScalarBoolean(arg1.getInt() != arg2.getInt());
        }
    }

    public RuntimeScalar spaceship(RuntimeScalar arg2) {
        RuntimeScalar arg1 = this;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return getScalarInt(Double.compare(arg1.getDouble(), arg2.getDouble()));
        } else {
            return getScalarInt(Integer.compare(arg1.getInt(), arg2.getInt()));
        }
    }

    public RuntimeScalar cmp(RuntimeScalar arg2) {
        return getScalarInt(this.toString().compareTo(arg2.toString()));
    }

    public RuntimeScalar eq(RuntimeScalar arg2) {
        return getScalarBoolean(this.toString().equals(arg2.toString()));
    }

    public RuntimeScalar ne(RuntimeScalar arg2) {
        return getScalarBoolean(!this.toString().equals(arg2.toString()));
    }

    public RuntimeScalar lt(RuntimeScalar arg2) {
        return getScalarBoolean(this.toString().compareTo(arg2.toString()) < 0);
    }

    public RuntimeScalar le(RuntimeScalar arg2) {
        return getScalarBoolean(this.toString().compareTo(arg2.toString()) <= 0);
    }

    public RuntimeScalar gt(RuntimeScalar arg2) {
        return getScalarBoolean(this.toString().compareTo(arg2.toString()) > 0);
    }

    public RuntimeScalar ge(RuntimeScalar arg2) {
        return getScalarBoolean(this.toString().compareTo(arg2.toString()) >= 0);
    }

    public RuntimeScalar bitwiseAnd(RuntimeScalar arg2) {
        if (this.type == RuntimeScalarType.STRING && arg2.type == RuntimeScalarType.STRING) {
            String s1 = this.toString();
            String s2 = arg2.toString();
            int len = Math.min(s1.length(), s2.length());
            StringBuilder result = new StringBuilder(len);

            for (int i = 0; i < len; i++) {
                char c1 = s1.charAt(i);
                char c2 = s2.charAt(i);
                if (c1 > 0xFF || c2 > 0xFF) {
                    throw new PerlCompilerException("Use of strings with code points over 0xFF as arguments to bitwise and (&) operator is not allowed");
                }
                result.append((char) (c1 & c2));
            }

            return new RuntimeScalar(result.toString());
        }
        return new RuntimeScalar(this.getLong() & arg2.getLong());
    }

    public RuntimeScalar bitwiseOr(RuntimeScalar arg2) {
        if (this.type == RuntimeScalarType.STRING && arg2.type == RuntimeScalarType.STRING) {
            String s1 = this.toString();
            String s2 = arg2.toString();
            int len = Math.max(s1.length(), s2.length());
            StringBuilder result = new StringBuilder(len);

            for (int i = 0; i < len; i++) {
                char c1 = i < s1.length() ? s1.charAt(i) : 0;
                char c2 = i < s2.length() ? s2.charAt(i) : 0;
                if (c1 > 0xFF || c2 > 0xFF) {
                    throw new PerlCompilerException("Use of strings with code points over 0xFF as arguments to bitwise or (|) operator is not allowed");
                }
                result.append((char) (c1 | c2));
            }

            return new RuntimeScalar(result.toString());
        }
        return new RuntimeScalar(this.getLong() | arg2.getLong());
    }

    public RuntimeScalar bitwiseXor(RuntimeScalar arg2) {
        if (this.type == RuntimeScalarType.STRING && arg2.type == RuntimeScalarType.STRING) {
            String s1 = this.toString();
            String s2 = arg2.toString();
            int len = Math.max(s1.length(), s2.length());
            StringBuilder result = new StringBuilder(len);

            for (int i = 0; i < len; i++) {
                char c1 = i < s1.length() ? s1.charAt(i) : 0;
                char c2 = i < s2.length() ? s2.charAt(i) : 0;
                if (c1 > 0xFF || c2 > 0xFF) {
                    throw new PerlCompilerException("Use of strings with code points over 0xFF as arguments to bitwise xor (^) operator is not allowed");
                }
                result.append((char) (c1 ^ c2));
            }

            return new RuntimeScalar(result.toString());
        }
        return new RuntimeScalar(this.getLong() ^ arg2.getLong());
    }

    public RuntimeScalar bitwiseNot() {
        if (this.type == RuntimeScalarType.STRING) {
            String s = this.toString();
            StringBuilder result = new StringBuilder(s.length());

            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c > 0xFF) {
                    throw new PerlCompilerException("Use of strings with code points over 0xFF as arguments to bitwise not (~) operator is not allowed");
                }
                // Perform bitwise NOT and mask with 0xFF to match Perl behavior
                result.append((char) ((~c) & 0xFF));
            }

            return new RuntimeScalar(result.toString());
        }
        // force 32-bit unsigned to match Perl behavior
        return new RuntimeScalar(~this.getLong() & 0xFFFFFFFFL);
    }

    public RuntimeScalar shiftLeft(RuntimeScalar arg2) {
        return new RuntimeScalar(this.getInt() << arg2.getInt());
    }

    public RuntimeScalar shiftRight(RuntimeScalar arg2) {
        return new RuntimeScalar(this.getInt() >> arg2.getInt());
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
                return this.stringIncrement();
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
                this.stringIncrement();
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
                this.set(this.parseNumber());
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
                this.set(this.parseNumber());
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

    public RuntimeScalar index(RuntimeScalar substr, RuntimeScalar position) {
        String str = this.toString();
        String sub = substr.toString();
        int pos = position.type == RuntimeScalarType.UNDEF
                ? 0 : position.getInt(); // if position is not provided, start from 0

        // Bound the position to be within the valid range of the string
        if (pos < 0) {
            pos = 0;
        } else if (pos >= str.length()) {
            return getScalarInt(-1);
        }

        // Find the index of the substring starting from the specified position
        int result = str.indexOf(sub, pos);

        // Return the index or -1 if not found
        return getScalarInt(result);
    }

    public RuntimeScalar rindex(RuntimeScalar substr, RuntimeScalar position) {
        String str = this.toString();
        String sub = substr.toString();
        int pos = position.type == RuntimeScalarType.UNDEF
                ? str.length() : position.getInt(); // Default to search from the end of the string

        // Bound the position to be within the valid range of the string
        if (pos >= str.length()) {
            pos = str.length() - 1;
        } else if (pos < 0) {
            return getScalarInt(-1);
        }

        // Find the last index of the substring before or at the specified position
        int result = str.lastIndexOf(sub, pos);

        // Return the index or -1 if not found
        return getScalarInt(result);
    }

    public RuntimeScalar log() {
        return new RuntimeScalar(Math.log(this.getDouble()));
    }

    public RuntimeScalar sqrt() {
        return new RuntimeScalar(Math.sqrt(this.getDouble()));
    }

    public RuntimeScalar cos() {
        return new RuntimeScalar(Math.cos(this.getDouble()));
    }

    public RuntimeScalar sin() {
        return new RuntimeScalar(Math.sin(this.getDouble()));
    }

    public RuntimeScalar exp() {
        return new RuntimeScalar(Math.exp(this.getDouble()));
    }

    public RuntimeScalar pow(RuntimeScalar arg) {
        return new RuntimeScalar(Math.pow(this.getDouble(), arg.getDouble()));
    }

    public RuntimeScalar atan2(RuntimeScalar arg) {
        return new RuntimeScalar(Math.atan2(this.getDouble(), arg.getDouble()));
    }

    public RuntimeScalar abs() {
        RuntimeScalar arg1 = this;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE) {
            return new RuntimeScalar(Math.abs(arg1.getDouble()));
        } else {
            return new RuntimeScalar(Math.abs(arg1.getInt()));
        }
    }

    public RuntimeScalar rand() {
        return new RuntimeScalar(random.nextDouble() * this.getDouble());
    }

    public RuntimeScalar integer() {
        return getScalarInt(getInt());
    }

    public RuntimeScalar length() {
        return getScalarInt(toString().length());
    }

    public RuntimeScalar quotemeta() {
        StringBuilder quoted = new StringBuilder();
        for (char c : this.value.toString().toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                quoted.append(c);
            } else {
                quoted.append("\\").append(c);
            }
        }
        return new RuntimeScalar(quoted.toString());
    }

    public RuntimeScalar fc() {
        String str = this.toString();
        // Step 1: Normalize the string to NFKC form (Compatibility Composition)
        Normalizer2 normalizer = Normalizer2.getNFKCInstance();
        String normalized = normalizer.normalize(str);

        // Step 2: Perform full Unicode case folding using ICU4J CaseMap
        str = CaseMap.fold().apply(normalized);

        return new RuntimeScalar(str);
    }

    public RuntimeScalar lc() {
        return new RuntimeScalar(this.toString().toLowerCase());
    }

    public RuntimeScalar lcfirst() {
        String str = this.toString();
        if (str.isEmpty()) {
            return new RuntimeScalar(str);
        }
        return new RuntimeScalar(str.substring(0, 1).toLowerCase() + str.substring(1));
    }

    public RuntimeScalar uc() {
        return new RuntimeScalar(this.toString().toUpperCase());
    }

    public RuntimeScalar ucfirst() {
        String str = this.toString();
        if (str.isEmpty()) {
            return new RuntimeScalar(str);
        }
        return new RuntimeScalar(str.substring(0, 1).toUpperCase() + str.substring(1));
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
        try {
            TimeUnit.MILLISECONDS.sleep(s);
        } catch (InterruptedException e) {
            // TODO
        }
        return new RuntimeScalar(s / 1000.0);
    }

    public RuntimeScalar require() {
        // https://perldoc.perl.org/functions/require

        // Look up the file name in %INC
        String fileName = this.toString();
        if (GlobalContext.getGlobalHash("main::INC").elements.containsKey(fileName)) {
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
            List<RuntimeScalar> inc = GlobalContext.getGlobalArray("main::INC").elements;
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
                fullName = Paths.get(resource.getPath());
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
                    GlobalContext.setGlobalVariable("main::!", "No such file or directory");
                    return new RuntimeScalar();
                }
            }
        }
        if (fullName == null) {
            GlobalContext.setGlobalVariable("main::!", "No such file or directory");
            return new RuntimeScalar();
        }

        ArgumentParser.CompilerOptions parsedArgs = new ArgumentParser.CompilerOptions();
        parsedArgs.fileName = fullName.toString();
        if (code == null) {
            try {
                code = new String(Files.readAllBytes(Paths.get(parsedArgs.fileName)));
            } catch (IOException e) {
                GlobalContext.setGlobalVariable("main::!", "Unable to read file " + parsedArgs.fileName);
                return new RuntimeScalar();
            }
        }
        parsedArgs.code = code;

        // set %INC
        GlobalContext.getGlobalHash("main::INC").put(fileName, new RuntimeScalar(parsedArgs.fileName));

        RuntimeList result;
        try {
            result = PerlLanguageProvider.executePerlCode(parsedArgs);
        } catch (Throwable t) {
            GlobalContext.setGlobalVariable("main::@", "Error in file " + parsedArgs.fileName +
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
            fh = (RuntimeIO) GlobalContext.getGlobalIO(globName).value;
        } else if (type == RuntimeScalarType.GLOB) {
            // my $fh = *STDOUT;
            if (value instanceof RuntimeGlob) {
                // System.out.println("GLOB");
                String globName = ((RuntimeGlob) value).globName;
                fh = (RuntimeIO) GlobalContext.getGlobalIO(globName).value;
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

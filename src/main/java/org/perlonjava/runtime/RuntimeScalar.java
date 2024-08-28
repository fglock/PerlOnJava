package org.perlonjava.runtime;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

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
public class RuntimeScalar extends RuntimeBaseEntity implements RuntimeScalarReference {

    // Fields to store the type and value of the scalar variable
    // TODO add cache for integer/string values
    public RuntimeScalarType type;
    public Object value;

    // Note: possible optimization, but this is not safe, because the values are mutable - need to create an immutable version
    //    public static zero = new RuntimeScalar(0);
    //    public static one = new RuntimeScalar(1);

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
        this.type = RuntimeScalarType.STRING;
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

    // Helper method to autoincrement a String variable
    private static String _string_increment(String s) {
        // Check if the string length is less than 2
        if (s.length() < 2) {
            // Get the Unicode code point of the first character
            final int c = s.codePointAt(0);

            // Check if the character is a digit from '0' to '8'
            if ((c >= '0' && c <= '8') || (c >= 'A' && c <= 'Y') || (c >= 'a' && c <= 'y')) {
                // If so, increment the character and return it as a new String
                return "" + (char) (c + 1);
            }

            // Special case: if the character is '9', return "10"
            if (c == '9') {
                return "10";
            }

            // Special case: if the character is 'Z', return "AA"
            if (c == 'Z') {
                return "AA";
            }

            // Special case: if the character is 'z', return "aa"
            if (c == 'z') {
                return "aa";
            }

            // For any other character, return "1" as the incremented value
            return "1";
        }

        // Recursive case: increment the last character of the string
        String c = _string_increment(s.substring(s.length() - 1));

        // Check if the result of the increment is a single character
        if (c.length() == 1) {
            // If the result is a single character, replace the last character of the original string
            // Example: If input is "AAAC", incrementing last character gives "AAAD"
            return s.substring(0, s.length() - 1) + c;
        }

        // If the result of incrementing the last character causes a carry (e.g., "AAAZ" becomes "AABA")
        // Increment the rest of the string (all characters except the last one)
        // and concatenate it with the last character of the incremented value
        return _string_increment(s.substring(0, s.length() - 1)) + c.substring(c.length() - 1);
    }

    public static RuntimeScalar undef() {
        return new RuntimeScalar();
    }

    // Checks if the object is of a given class or a subclass
    // Note this is a Perl method, it expects `this` to be the first argument
    public static RuntimeList isa(RuntimeArray args, RuntimeContextType ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for isa() method");
        }
        RuntimeScalar object = (RuntimeScalar) args.get(0);
        String argString = args.get(1).toString();

        // Retrieve Perl class name
        String perlClassName = "";
        switch (object.type) {
            case REFERENCE:
            case ARRAYREFERENCE:
            case HASHREFERENCE:
                int blessId = ((RuntimeBaseEntity) object.value).blessId;
                if (blessId == 0) {
                    return new RuntimeScalar(false).getList();
                }
                perlClassName = GlobalContext.getBlessStr(blessId);
                break;
            case UNDEF:
                return new RuntimeScalar(false).getList();
            default:
                perlClassName = object.toString();
                if (perlClassName.isEmpty()) {
                    return new RuntimeScalar(false).getList();
                }
                if (perlClassName.endsWith("::")) {
                    perlClassName = perlClassName.substring(0, perlClassName.length() - 2);
                }
        }

        // Get the linearized inheritance hierarchy using C3
        List<String> linearizedClasses = InheritanceResolver.linearizeC3(perlClassName);

        return new RuntimeScalar(linearizedClasses.contains(argString)).getList();
    }

    // Checks if the object can perform a given method
    // Note this is a Perl method, it expects `this` to be the first argument
    public static RuntimeList can(RuntimeArray args, RuntimeContextType ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for can() method");
        }
        RuntimeScalar object = (RuntimeScalar) args.get(0);
        String methodName = args.get(1).toString();

        // Retrieve Perl class name
        String perlClassName = "";
        switch (object.type) {
            case REFERENCE:
            case ARRAYREFERENCE:
            case HASHREFERENCE:
                int blessId = ((RuntimeBaseEntity) object.value).blessId;
                if (blessId == 0) {
                    return new RuntimeScalar(false).getList();
                }
                perlClassName = GlobalContext.getBlessStr(blessId);
                break;
            case UNDEF:
                return new RuntimeScalar(false).getList();
            default:
                perlClassName = object.toString();
                if (perlClassName.isEmpty()) {
                    return new RuntimeScalar(false).getList();
                }
                if (perlClassName.endsWith("::")) {
                    perlClassName = perlClassName.substring(0, perlClassName.length() - 2);
                }
        }

        // Check the method cache
        String normalizedMethodName = GlobalContext.normalizeVariableName(methodName, perlClassName);
        RuntimeScalar cachedMethod = InheritanceResolver.getCachedMethod(normalizedMethodName);
        if (cachedMethod != null) {
            return cachedMethod.getList();
        }

        // Get the linearized inheritance hierarchy using C3
        for (String className : InheritanceResolver.linearizeC3(perlClassName)) {
            String normalizedClassMethodName = GlobalContext.normalizeVariableName(methodName, className);
            if (GlobalContext.existsGlobalCodeRef(normalizedClassMethodName)) {
                // If the method is found, return it
                return GlobalContext.getGlobalCodeRef(normalizedClassMethodName).getList();
            }
        }
        return new RuntimeScalar(false).getList();
    }

    // Implements the isa operator
    public RuntimeScalar isa(RuntimeScalar className) {
        RuntimeArray args = new RuntimeArray();
        args.push(this);
        args.push(className);
        return isa(args, RuntimeContextType.SCALAR).scalar();
    }

    // Getters
    public int getInt() {
        switch (type) {
            case INTEGER:
                return (int) value;
            case DOUBLE:
                return (int) ((double) value);
            case STRING:
                return this.parseNumber().getInt();
            case UNDEF:
                return 0;
            default:
                return ((RuntimeScalarReference) value).getIntRef();
        }
    }

    private double getDouble() {
        switch (this.type) {
            case INTEGER:
                return (int) this.value;
            case DOUBLE:
                return (double) this.value;
            case STRING:
                return this.parseNumber().getDouble();
            case UNDEF:
                return 0.0;
            default:
                return ((RuntimeScalarReference) value).getDoubleRef();
        }
    }

    public boolean getBoolean() {
        switch (type) {
            case INTEGER:
                return (int) value != 0;
            case DOUBLE:
                return (double) value != 0.0;
            case STRING:
                String s = (String) value;
                return !s.isEmpty() && !s.equals("0");
            case UNDEF:
                return false;
            default:
                return ((RuntimeScalarReference) value).getBooleanRef();
        }
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
        this.value = this;
        return this;
    }

    public RuntimeList set(RuntimeList value) {
        return new RuntimeList(this.set(value.scalar()));
    }

    @Override
    public String toString() {
        switch (type) {
            case INTEGER:
                return Integer.toString((int) value);
            case DOUBLE:
                return Double.toString((double) value);
            case STRING:
                return (String) value;
            case UNDEF:
                return "";
            default:
                return ((RuntimeScalarReference) value).toStringRef();
        }
    }

    public String toStringRef() {
        switch (type) {
            case UNDEF:
                return "REF(0x14500834042)";
            case CODE:
                return ((RuntimeCode) value).toStringRef();
            case GLOB:
                if (value == null) {
                    return "CODE(0x14500834042)";
                }
                return ((RuntimeCode) value).toStringRef();
            default:
                return "REF(" + value.hashCode() + ")";
        }
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
            default:
                throw new IllegalStateException("Variable does not contain a hash reference");
        }
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
                throw new IllegalStateException("Variable does not contain an array reference");
        }
    }

    // Method to implement `@$v`
    public RuntimeArray arrayDeref() {
        switch (type) {
            case UNDEF:
                throw new IllegalStateException("Can't use an undefined value as an ARRAY reference");
            case ARRAYREFERENCE:
                return (RuntimeArray) value;
            default:
                throw new IllegalStateException("Variable does not contain an array reference");
        }
    }

    // Method to implement `%$v`
    public RuntimeHash hashDeref() {
        switch (type) {
            case UNDEF:
                throw new IllegalStateException("Can't use an undefined value as an HASH reference");
            case HASHREFERENCE:
                return (RuntimeHash) value;
            default:
                throw new IllegalStateException("Variable does not contain an hash reference");
        }
    }

    // Method to implement `$$v`
    public RuntimeScalar scalarDeref() {
        switch (type) {
            case UNDEF:
                throw new IllegalStateException("Can't use an undefined value as a SCALAR reference");
            case REFERENCE:
                return (RuntimeScalar) value;
            default:
                throw new IllegalStateException("Variable does not contain a scalar reference");
        }
    }

    // Method to apply (execute) a subroutine reference
    public RuntimeList apply(RuntimeArray a, RuntimeContextType callContext) throws Exception {
        // Check if the type of this RuntimeScalar is CODE
        if (this.type == RuntimeScalarType.CODE) {
            // Cast the value to RuntimeCode and call apply()
            return ((RuntimeCode) this.value).apply(a, callContext);
        } else {
            // If the type is not CODE, throw an exception indicating an invalid state
            throw new IllegalStateException("Variable does not contain a code reference");
        }
    }

    // Method to "bless" a Perl reference into an object
    public RuntimeScalar bless(RuntimeScalar className) throws Exception {
        switch (type) {
            case REFERENCE:
            case ARRAYREFERENCE:
            case HASHREFERENCE:
                String str = className.toString();
                if (str.isEmpty()) {
                    str = "main";
                }
                ((RuntimeBaseEntity) value).blessId = GlobalContext.getBlessId(str);
                break;
            default:
                throw new IllegalStateException("Can't bless non-reference value");
        }
        return this;
    }

    public RuntimeScalar ref() {
        String str;
        switch (type) {
            case CODE:
                str = "CODE";
                break;
            case GLOB:
                str = "GLOB";
                break;
            case REFERENCE:
                int blessId = ((RuntimeBaseEntity) value).blessId;
                str = blessId == 0 ? "REF" : GlobalContext.getBlessStr(blessId);
                break;
            case ARRAYREFERENCE:
                blessId = ((RuntimeBaseEntity) value).blessId;
                str = blessId == 0 ? "ARRAY" : GlobalContext.getBlessStr(blessId);
                break;
            case HASHREFERENCE:
                blessId = ((RuntimeBaseEntity) value).blessId;
                str = blessId == 0 ? "HASH" : GlobalContext.getBlessStr(blessId);
                break;
            default:
                str = "";
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
    public RuntimeList call(RuntimeScalar method, RuntimeArray args, RuntimeContextType callContext) throws Exception {
        // insert `this` into the parameter list
        args.elements.add(0, this);

        if (method.type == RuntimeScalarType.CODE) {
            // If method is a subroutine reference, just call it
            return method.apply(args, callContext);
        }

        String methodName = method.toString();

        // Retrieve Perl class name
        String perlClassName = "";
        switch (type) {
            case REFERENCE:
            case ARRAYREFERENCE:
            case HASHREFERENCE:
                int blessId = ((RuntimeBaseEntity) value).blessId;
                if (blessId == 0) {
                    throw new IllegalStateException("Can't call method \"" + methodName + "\" on unblessed reference");
                }
                perlClassName = GlobalContext.getBlessStr(blessId);
                break;
            case UNDEF:
                throw new IllegalStateException("Can't call method \"" + methodName + "\" on an undefined value");
            default:
                perlClassName = this.toString();
                if (perlClassName.isEmpty()) {
                    throw new IllegalStateException("Can't call method \"" + methodName + "\" on an undefined value");
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
        String normalizedMethodName = GlobalContext.normalizeVariableName(methodName, perlClassName);
        RuntimeScalar cachedMethod = InheritanceResolver.getCachedMethod(normalizedMethodName);
        if (cachedMethod != null) {
            return cachedMethod.apply(args, callContext);
        }

        // Get the linearized inheritance hierarchy using C3
        List<String> linearizedClasses = InheritanceResolver.linearizeC3(perlClassName);

        // Iterate over the linearized classes to find the method
        for (String className : linearizedClasses) {
            String normalizedClassMethodName = GlobalContext.normalizeVariableName(methodName, className);
            if (GlobalContext.existsGlobalCodeRef(normalizedClassMethodName)) {
                // If the method is found, retrieve and apply it
                RuntimeScalar codeRef = GlobalContext.getGlobalCodeRef(normalizedClassMethodName);

                // Save the method in the cache
                InheritanceResolver.cacheMethod(normalizedMethodName, codeRef);

                return codeRef.apply(args, callContext);
            }
        }

        // If the method is not found in any class, throw an exception
        throw new IllegalStateException("Can't locate object method \"" + methodName + "\" via package \"" + perlClassName + "\" (perhaps you forgot to load \"" + perlClassName + "\"?)");
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
            // Handle non-numeric increment for alphabetical characters
            int length = str.length(); // Get the length of the string
            c = str.charAt(length - 1); // Get the last character of the string

            // Check if the last character is a valid character for incrementing
            if ((c >= '0' && c <= '8') || (c >= 'A' && c <= 'Y') || (c >= 'a' && c <= 'y')) {
                // If valid, increment the last character and update the value
                this.value = str.substring(0, length - 1) + (char) (c + 1);
            } else {
                // If not valid (like '9', 'Z', or 'z'), use a helper function to handle incrementing
                this.value = _string_increment(str);
            }
            return this; // Return the current instance after increment
        }

        // Handle numeric increment: parse the number and increment it
        this.set(this.parseNumber()); // parseNumber parses the current string to a number
        return this.preAutoIncrement(); // preAutoIncrement handles the actual incrementing logic
    }

    // Methods that implement Perl operators

    // Helper method to convert String to Integer or Double
    private RuntimeScalar parseNumber() {
        String str = (String) this.value;

        // Remove leading and trailing spaces from the input string
        str = str.trim();

        // StringBuilder to accumulate the numeric part of the string
        StringBuilder number = new StringBuilder();
        boolean hasDecimal = false;
        boolean hasExponent = false;
        boolean hasSign = false;
        boolean inExponent = false;
        boolean validExponent = false;

        // Iterate through each character in the string
        for (char c : str.toCharArray()) {
            // Check if the character is a digit, decimal point, exponent, or sign
            if (Character.isDigit(c)
                    || (c == '.' && !hasDecimal)
                    || ((c == 'e' || c == 'E') && !hasExponent)
                    || (c == '-' && !hasSign)) {
                number.append(c);

                // Update flags based on the character
                if (c == '.') {
                    hasDecimal = true; // Mark that a decimal point has been encountered
                } else if (c == 'e' || c == 'E') {
                    hasExponent = true; // Mark that an exponent has been encountered
                    hasSign = false; // Reset the sign flag for the exponent part
                    inExponent = true; // Mark that we are now in the exponent part
                } else if (c == '-') {
                    if (!inExponent) {
                        hasSign = true; // Mark that a sign has been encountered
                    }
                } else if (Character.isDigit(c) && inExponent) {
                    validExponent = true; // Mark that the exponent part has valid digits
                }
            } else {
                // Stop parsing at the first invalid character in the exponent part
                if (inExponent && !Character.isDigit(c) && c != '-') {
                    break;
                }
                // Stop parsing at the first non-numeric character
                if (!inExponent) {
                    break;
                }
            }
        }

        // If the exponent part is invalid, remove it
        if (hasExponent && !validExponent) {
            int exponentIndex = number.indexOf("e");
            if (exponentIndex == -1) {
                exponentIndex = number.indexOf("E");
            }
            if (exponentIndex != -1) {
                number.setLength(exponentIndex); // Truncate the string at the exponent
            }
        }

        try {
            // Convert the accumulated numeric part to a string
            String numberStr = number.toString();

            // Determine if the number should be parsed as a double or int
            if (hasDecimal || hasExponent) {
                double parsedValue = Double.parseDouble(numberStr);
                return new RuntimeScalar(parsedValue);
            } else {
                int parsedValue = Integer.parseInt(numberStr);
                return new RuntimeScalar(parsedValue);
            }
        } catch (NumberFormatException e) {
            // Return a RuntimeScalar object with value of 0 if parsing fails
            return new RuntimeScalar(0);
        }
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
        return this;
    }

    public RuntimeScalar stringConcat(RuntimeScalar b) {
        return new RuntimeScalar(this + b.toString());
    }

    public RuntimeScalar unaryMinus() {
        return new RuntimeScalar(0).subtract(this);
    }

    public RuntimeScalar not() {
        if (this.getBoolean()) {
            return new RuntimeScalar(0);
        }
        return new RuntimeScalar(1);
    }

    public RuntimeScalar add(RuntimeScalar arg2) {
        RuntimeScalar arg1 = this;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() + arg2.getDouble());
        } else {
            return new RuntimeScalar(arg1.getInt() + arg2.getInt());
        }
    }

    public RuntimeScalar subtract(RuntimeScalar arg2) {
        RuntimeScalar arg1 = this;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() - arg2.getDouble());
        } else {
            return new RuntimeScalar(arg1.getInt() - arg2.getInt());
        }
    }

    public RuntimeScalar multiply(RuntimeScalar arg2) {
        RuntimeScalar arg1 = this;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() * arg2.getDouble());
        } else {
            return new RuntimeScalar((long) arg1.getInt() * (long) arg2.getInt());
        }
    }

    public RuntimeScalar divide(RuntimeScalar arg2) {
        RuntimeScalar arg1 = this;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() / arg2.getDouble());
        } else {
            return new RuntimeScalar(arg1.getInt() / arg2.getInt());
        }
    }

    public RuntimeScalar modulus(RuntimeScalar arg2) {
        int divisor = arg2.getInt();
        int result = this.getInt() % divisor;
        if (result != 0.0 && ((divisor > 0.0 && result < 0.0) || (divisor < 0.0 && result > 0.0))) {
            result += divisor;
        }
        return new RuntimeScalar(result);
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
            return new RuntimeScalar(arg1.getDouble() < arg2.getDouble());
        } else {
            return new RuntimeScalar(arg1.getInt() < arg2.getInt());
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
            return new RuntimeScalar(arg1.getDouble() <= arg2.getDouble());
        } else {
            return new RuntimeScalar(arg1.getInt() <= arg2.getInt());
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
            return new RuntimeScalar(arg1.getDouble() > arg2.getDouble());
        } else {
            return new RuntimeScalar(arg1.getInt() > arg2.getInt());
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
            return new RuntimeScalar(arg1.getDouble() >= arg2.getDouble());
        } else {
            return new RuntimeScalar(arg1.getInt() >= arg2.getInt());
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
            return new RuntimeScalar(arg1.getDouble() == arg2.getDouble());
        } else {
            return new RuntimeScalar(arg1.getInt() == arg2.getInt());
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
            return new RuntimeScalar(arg1.getDouble() != arg2.getDouble());
        } else {
            return new RuntimeScalar(arg1.getInt() != arg2.getInt());
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
            return new RuntimeScalar(Double.compare(arg1.getDouble(), arg2.getDouble()));
        } else {
            return new RuntimeScalar(Integer.compare(arg1.getInt(), arg2.getInt()));
        }
    }

    public RuntimeScalar cmp(RuntimeScalar arg2) {
        return new RuntimeScalar(this.toString().compareTo(arg2.toString()));
    }

    public RuntimeScalar eq(RuntimeScalar arg2) {
        return new RuntimeScalar(this.toString().equals(arg2.toString()));
    }

    public RuntimeScalar ne(RuntimeScalar arg2) {
        return new RuntimeScalar(!this.toString().equals(arg2.toString()));
    }

    public RuntimeScalar lt(RuntimeScalar arg2) {
        return new RuntimeScalar(this.toString().compareTo(arg2.toString()) < 0);
    }

    public RuntimeScalar le(RuntimeScalar arg2) {
        return new RuntimeScalar(this.toString().compareTo(arg2.toString()) <= 0);
    }

    public RuntimeScalar gt(RuntimeScalar arg2) {
        return new RuntimeScalar(this.toString().compareTo(arg2.toString()) > 0);
    }

    public RuntimeScalar ge(RuntimeScalar arg2) {
        return new RuntimeScalar(this.toString().compareTo(arg2.toString()) >= 0);
    }

// Bitwise AND (&)

    public RuntimeScalar bitwiseAnd(RuntimeScalar arg2) {
        return new RuntimeScalar(this.getInt() & arg2.getInt());
    }

// Bitwise OR (|)

    public RuntimeScalar bitwiseOr(RuntimeScalar arg2) {
        return new RuntimeScalar(this.getInt() | arg2.getInt());
    }

// Bitwise XOR (^)

    public RuntimeScalar bitwiseXor(RuntimeScalar arg2) {
        return new RuntimeScalar(this.getInt() ^ arg2.getInt());
    }

// Bitwise NOT (~)

    public RuntimeScalar bitwiseNot() {
        return new RuntimeScalar(~this.getInt());
    }

// Shift Left (<<)

    public RuntimeScalar shiftLeft(RuntimeScalar arg2) {
        return new RuntimeScalar(this.getInt() << arg2.getInt());
    }

// Shift Right (>>)

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

    public RuntimeScalar log() {
        return new RuntimeScalar(Math.log(this.getDouble()));
    }

    public RuntimeScalar pow(RuntimeScalar arg) {
        return new RuntimeScalar(Math.pow(this.getDouble(), arg.getDouble()));
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
        return new RuntimeScalar(Math.random() * this.getDouble());
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

    public RuntimeScalar join(RuntimeList list) {
        String delimiter = this.toString();
        // Join the list into a string
        StringBuilder sb = new StringBuilder();
        int size = list.elements.size();
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                sb.append(delimiter);
            }
            sb.append(list.elements.get(i).toString());
        }
        return new RuntimeScalar(sb.toString());
    }

    // keys() operator
    public RuntimeArray keys() {
        throw new IllegalStateException("Type of arg 1 to values must be hash or array");
    }

    // values() operator
    public RuntimeArray values() {
        throw new IllegalStateException("Type of arg 1 to values must be hash or array");
    }

    // Method to return an iterator
    public Iterator<RuntimeScalar> iterator() {
        return new RuntimeScalarIterator(this);
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

package org.perlonjava;

import java.lang.reflect.Method;
import java.lang.Math;
import java.util.Iterator;

/**
 * The RuntimeScalar class simulates Perl scalar variables.
 *
 * <p>In Perl, a scalar variable can hold: - An integer - A double (floating-point number) - A
 * string - A reference (to arrays, hashes, subroutines, etc.) - A code reference (anonymous
 * subroutine) - Undefined value - Special literals (like filehandles, typeglobs, regular
 * expressions, etc.)
 *
 * <p>Perl scalars are dynamically typed, meaning their type can change at runtime. This class tries
 * to mimic this behavior by using an enum `ScalarType` to track the type of the value stored in the
 * scalar.
 */
public class RuntimeScalar extends AbstractRuntimeObject implements RuntimeScalarReference {

  // Fields to store the type and value of the scalar variable
  // TODO add cache for integer/string values
  public ScalarType type;
  public Object value;

  // this is not safe, because the values are mutable - need to create an immutable version
  // public static zero = new RuntimeScalar(0);
  // public static one = new RuntimeScalar(1);

  // Constructors
  public RuntimeScalar() {
    this.type = ScalarType.UNDEF;
  }

  public RuntimeScalar(long value) {
    this.type = ScalarType.INTEGER;
    this.value = value;
  }

  public RuntimeScalar(int value) {
    this.type = ScalarType.INTEGER;
    this.value = (long) value;
  }

  public RuntimeScalar(double value) {
    this.type = ScalarType.DOUBLE;
    this.value = value;
  }

  public RuntimeScalar(String value) {
    this.type = ScalarType.STRING;
    this.value = value;
  }

  public RuntimeScalar(boolean value) {
    this.type = ScalarType.INTEGER;
    this.value = (long) (value ? 1 : 0);
  }

  public RuntimeScalar(RuntimeScalar scalar) {
    this.type = scalar.type;
    this.value = scalar.value;
  }

  // Getters
  public long getLong() {
    switch (type) {
      case INTEGER:
        return (long) value;
      case DOUBLE:
        return (long) ((double) value);
      case STRING:
        return this.parseNumber().getLong();
      case UNDEF:
        return 0;
      default:
        return ((RuntimeScalarReference)value).getLongRef();
    }
  }

  private double getDouble() {
    switch (this.type) {
      case INTEGER:
        return (long) this.value;
      case DOUBLE:
        return (double) this.value;
      case STRING:
        return this.parseNumber().getDouble();
      case UNDEF:
        return 0.0;
      default:
        return ((RuntimeScalarReference)value).getDoubleRef();
    }
  }

  public boolean getBoolean() {
    switch (type) {
      case INTEGER:
        return (long) value != 0;
      case DOUBLE:
        return (double) value != 0.0;
      case STRING:
        String s = (String) value;
        return !s.isEmpty() && !s.equals("0");
      case UNDEF:
        return false;
      default:
        return ((RuntimeScalarReference)value).getBooleanRef();
    }
  }

  // Get the array value of the Scalar
  public RuntimeArray getArray() {
    return new RuntimeArray(this);
  }

  // Get the list value of the Scalar
  public RuntimeList getList() {
    return new RuntimeList(this);
  }

  // Get the scalar value of the Scalar
  public RuntimeScalar getScalar() {
      return this;
  }

  // Add itself to a RuntimeArray.
  public void addToArray(RuntimeArray array) {
    array.push(new RuntimeScalar(this));
  }

  // Setters
  public RuntimeScalar set(RuntimeScalar value) {
    this.type = value.type;
    this.value = value.value;
    return this;
  }

  public RuntimeScalar set(long value) {
    this.type = ScalarType.INTEGER;
    this.value = value;
    return this;
  }

  public RuntimeScalar set(String value) {
    this.type = ScalarType.STRING;
    this.value = value;
    return this;
  }

  public RuntimeList set(RuntimeList value) {
    return new RuntimeList(this.set(value.getScalar()));
  }

  @Override
  public String toString() {
    switch (type) {
      case INTEGER:
        return Long.toString((long) value);
      case DOUBLE:
        return Double.toString((double) value);
      case STRING:
        return (String) value;
      case UNDEF:
        return "";
      default:
        return ((RuntimeScalarReference)value).toStringRef();
    }
  }

  public String toStringRef() {
    return "REF(" + value.hashCode() + ")";
  }

  public long getLongRef() {
    return value.hashCode();
  }

  public double getDoubleRef() {
    return value.hashCode();
  }

  public boolean getBooleanRef() {
    return true;
  }

  public RuntimeScalar hashDerefGet(RuntimeScalar index) {
    switch (type) {
      case UNDEF:
        // hash autovivification
        type = ScalarType.HASHREFERENCE;
        value = new RuntimeHash();
      case HASHREFERENCE:
        return ((RuntimeHash) value).get(index.toString());
      default:
        throw new IllegalStateException("Variable does not contain a hash reference");
    }
  }

  public RuntimeScalar arrayDerefGet(RuntimeScalar index) {
    switch (type) {
      case UNDEF:
        // array autovivification
        type = ScalarType.ARRAYREFERENCE;
        value = new RuntimeArray();
      case ARRAYREFERENCE:
        return ((RuntimeArray) value).get((int) index.getLong());
      default:
        throw new IllegalStateException("Variable does not contain an array reference");
    }
  }

  // Factory method to create a CODE object (anonymous subroutine)
  public static RuntimeScalar make_sub(Object codeObject) throws Exception {
    // finish setting up a CODE object
    Class<?> clazz = codeObject.getClass();
    Method mm = clazz.getMethod("apply", RuntimeArray.class, ContextType.class);
    RuntimeScalar r = new RuntimeScalar();
    r.value = new RuntimeCode(mm, codeObject);
    r.type = ScalarType.CODE;
    return r;
  }

  // Method to apply (execute) a subroutine reference
  public RuntimeList apply(RuntimeArray a, ContextType callContext) throws Exception {
    if (type == ScalarType.CODE) {
      RuntimeCode code = (RuntimeCode) this.value;
      return (RuntimeList) code.methodObject.invoke(code.codeObject, a, callContext);
    } else {
      throw new IllegalStateException("Variable does not contain a code reference");
    }
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
            return "" + (char)(c + 1);
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
    String c = _string_increment(s.substring(s.length()-1));

    // Check if the result of the increment is a single character
    if (c.length() == 1) {
        // If the result is a single character, replace the last character of the original string
        // Example: If input is "AAAC", incrementing last character gives "AAAD"
        return s.substring(0, s.length()-1) + c;
    }

    // If the result of incrementing the last character causes a carry (e.g., "AAAZ" becomes "AABA")
    // Increment the rest of the string (all characters except the last one)
    // and concatenate it with the last character of the incremented value
    return _string_increment(s.substring(0, s.length()-1)) + c.substring(c.length()-1);
  }

  // Helper method to autoincrement a String variable
  private RuntimeScalar stringIncrement() {
    // Retrieve the current value as a String
    String str = (String) this.value;

    // Check if the string is empty
    if (str.isEmpty()) {
        // If empty, set the value to 1 (as a Long) and update type to INTEGER
        this.value = (long) 1;
        this.type = ScalarType.INTEGER; // ScalarType is an enum that holds different scalar types
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
            this.value = str.substring(0, length - 1) + (char)(c + 1);
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

      // Determine if the number should be parsed as a double or long
      if (hasDecimal || hasExponent) {
        double parsedValue = Double.parseDouble(numberStr);
        return new RuntimeScalar(parsedValue);
      } else {
        long parsedValue = Long.parseLong(numberStr);
        return new RuntimeScalar(parsedValue);
      }
    } catch (NumberFormatException e) {
      // Return a RuntimeScalar object with value of 0 if parsing fails
      return new RuntimeScalar(0);
    }
  }

  // Methods that implement Perl operators
  public RuntimeScalar createReference() {
    RuntimeScalar result = new RuntimeScalar();
    result.type = ScalarType.REFERENCE;
    result.value = this;
    return result;
  }

  public static RuntimeScalar undef() {
    return new RuntimeScalar();
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
    if (arg1.type == ScalarType.STRING) {
      arg1 = arg1.parseNumber();
    }
    if (arg2.type == ScalarType.STRING) {
      arg2 = arg2.parseNumber();
    }
    if (arg1.type == ScalarType.DOUBLE || arg2.type == ScalarType.DOUBLE) {
      return new RuntimeScalar(arg1.getDouble() + arg2.getDouble());
    } else {
      return new RuntimeScalar(arg1.getLong() + arg2.getLong());
    }
  }

  public RuntimeScalar subtract(RuntimeScalar arg2) {
    RuntimeScalar arg1 = this;
    if (arg1.type == ScalarType.STRING) {
      arg1 = arg1.parseNumber();
    }
    if (arg2.type == ScalarType.STRING) {
      arg2 = arg2.parseNumber();
    }
    if (arg1.type == ScalarType.DOUBLE || arg2.type == ScalarType.DOUBLE) {
      return new RuntimeScalar(arg1.getDouble() - arg2.getDouble());
    } else {
      return new RuntimeScalar(arg1.getLong() - arg2.getLong());
    }
  }

  public RuntimeScalar multiply(RuntimeScalar arg2) {
    RuntimeScalar arg1 = this;
    if (arg1.type == ScalarType.STRING) {
      arg1 = arg1.parseNumber();
    }
    if (arg2.type == ScalarType.STRING) {
      arg2 = arg2.parseNumber();
    }
    if (arg1.type == ScalarType.DOUBLE || arg2.type == ScalarType.DOUBLE) {
      return new RuntimeScalar(arg1.getDouble() * arg2.getDouble());
    } else {
      return new RuntimeScalar(arg1.getLong() * arg2.getLong());
    }
  }

  public RuntimeScalar divide(RuntimeScalar arg2) {
    RuntimeScalar arg1 = this;
    if (arg1.type == ScalarType.STRING) {
      arg1 = arg1.parseNumber();
    }
    if (arg2.type == ScalarType.STRING) {
      arg2 = arg2.parseNumber();
    }
    if (arg1.type == ScalarType.DOUBLE || arg2.type == ScalarType.DOUBLE) {
      return new RuntimeScalar(arg1.getDouble() / arg2.getDouble());
    } else {
      return new RuntimeScalar(arg1.getLong() / arg2.getLong());
    }
  }

  public RuntimeScalar lessEqualThan(RuntimeScalar arg2) {
    RuntimeScalar arg1 = this;
    if (arg1.type == ScalarType.STRING) {
      arg1 = arg1.parseNumber();
    }
    if (arg2.type == ScalarType.STRING) {
      arg2 = arg2.parseNumber();
    }
    if (arg1.type == ScalarType.DOUBLE || arg2.type == ScalarType.DOUBLE) {
      return new RuntimeScalar(arg1.getDouble() <= arg2.getDouble());
    } else {
      return new RuntimeScalar(arg1.getLong() <= arg2.getLong());
    }
  }

  public RuntimeScalar lessThan(RuntimeScalar arg2) {
    RuntimeScalar arg1 = this;
    if (arg1.type == ScalarType.STRING) {
      arg1 = arg1.parseNumber();
    }
    if (arg2.type == ScalarType.STRING) {
      arg2 = arg2.parseNumber();
    }
    if (arg1.type == ScalarType.DOUBLE || arg2.type == ScalarType.DOUBLE) {
      return new RuntimeScalar(arg1.getDouble() < arg2.getDouble());
    } else {
      return new RuntimeScalar(arg1.getLong() < arg2.getLong());
    }
  }

  public RuntimeScalar preAutoIncrement() {
    switch (type) {
      case INTEGER:
        this.value = (long) this.value + 1;
        return this;
      case DOUBLE:
        this.value = (double) this.value + 1;
        return this;
      case STRING:
        return this.stringIncrement();
    }
    this.type = ScalarType.INTEGER;
    this.value = 1;
    return this;
  }

  public RuntimeScalar postAutoIncrement() {
    RuntimeScalar old = new RuntimeScalar().set(this);
    switch (type) {
      case INTEGER:
        this.value = (long) this.value + 1;
        break;
      case DOUBLE:
        this.value = (double) this.value + 1;
        break;
      case STRING:
        this.stringIncrement();
        break;
      default:
        this.type = ScalarType.INTEGER;
        this.value = 1;
    }
    return old;
  }

  public RuntimeScalar preAutoDecrement() {
    switch (type) {
      case INTEGER:
        this.value = (long) this.value - 1;
        return this;
      case DOUBLE:
        this.value = (double) this.value - 1;
        return this;
      case STRING:
        // Handle numeric decrement
        this.set(this.parseNumber());
        return this.preAutoDecrement();
    }
    this.type = ScalarType.INTEGER;
    this.value = -1;
    return this;
  }

  public RuntimeScalar postAutoDecrement() {
    RuntimeScalar old = new RuntimeScalar().set(this);
    switch (type) {
      case INTEGER:
        this.value = (long) this.value - 1;
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
        this.type = ScalarType.INTEGER;
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
    if (arg1.type == ScalarType.STRING) {
      arg1 = arg1.parseNumber();
    }
    if (arg1.type == ScalarType.DOUBLE) {
      return new RuntimeScalar(Math.abs(arg1.getDouble()));
    } else {
      return new RuntimeScalar(Math.abs(arg1.getLong()));
    }
  }

  public RuntimeScalar rand() {
    return new RuntimeScalar(Math.random() * this.getDouble());
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
      return this.getArray().iterator();
  }
}

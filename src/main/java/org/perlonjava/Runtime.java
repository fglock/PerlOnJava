import java.lang.reflect.Method;
import java.util.*;

/**
 * The Runtime class simulates Perl scalar variables.
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
public class Runtime extends AbstractRuntimeObject {

  // Fields to store the type and value of the scalar variable
  // TODO add cache for integer/string values
  public ScalarType type;
  public Object value;

  private static Map<String, Runtime> globalVariables = new HashMap<>();

  // this is not safe, because the values are mutable - need to create an immutable version
  // public static zero = new Runtime(0);
  // public static one = new Runtime(1);

  // Static methods
  public static Runtime setGlobalVariable(String key, Runtime value) {
      Runtime var = globalVariables.get(key);
      if (var == null) {
        var = new Runtime();
        globalVariables.put(key, var);
      }
      return var.set(value);
  }

  public static Runtime setGlobalVariable(String key, String value) {
      Runtime var = globalVariables.get(key);
      if (var == null) {
        var = new Runtime();
        globalVariables.put(key, var);
      }
      return var.set(value);
  }

  public static Runtime getGlobalVariable(String key) {
      Runtime var = globalVariables.get(key);
      if (var == null) {
        var = new Runtime();
        globalVariables.put(key, var);
      }
      return var;
  }

  public static boolean existsGlobalVariable(String key) {
      return globalVariables.containsKey(key);
  }

  // Constructors
  public Runtime() {
    this.type = ScalarType.UNDEF;
  }

  public Runtime(long value) {
    this.type = ScalarType.INTEGER;
    this.value = value;
  }

  public Runtime(int value) {
    this.type = ScalarType.INTEGER;
    this.value = (long) value;
  }

  public Runtime(double value) {
    this.type = ScalarType.DOUBLE;
    this.value = value;
  }

  public Runtime(String value) {
    this.type = ScalarType.STRING;
    this.value = value;
  }

  public Runtime(boolean value) {
    this.type = ScalarType.INTEGER;
    this.value = (long) (value ? 1 : 0);
  }

  public Runtime(Runtime scalar) {
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
      case CODE:
        return value.hashCode(); // Use hashCode as the ID
      case UNDEF:
        return 0;
      default:
        return 0;
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
      case CODE:
        return value.hashCode(); // Use hashCode as the ID
      case UNDEF:
        return 0.0;
      default:
        return 0.0;
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
        return !s.equals("") && !s.equals("0");
      case CODE:
        return true;
      case UNDEF:
        return false;
      default:
        return true;
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
  public Runtime getScalar() {
      return this;
  }

  // Add itself to a RuntimeArray.
  public void addToArray(RuntimeArray array) {
    array.push(new Runtime(this));
  }

  // Setters
  public Runtime set(Runtime value) {
    this.type = value.type;
    this.value = value.value;
    return this;
  }

  public Runtime set(long value) {
    this.type = ScalarType.INTEGER;
    this.value = value;
    return this;
  }

  public Runtime set(String value) {
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
      case CODE:
        return "CODE(" + value.hashCode() + ")";
      case UNDEF:
        return "";
      case REFERENCE:
        return "REF(" + value.hashCode() + ")";
      case ARRAYREFERENCE:
        return "ARRAY(" + value.hashCode() + ")";
      case HASHREFERENCE:
        return "HASH(" + value.hashCode() + ")";
      default:
        return "Undefined";
    }
  }

  public Runtime hashDerefGet(Runtime index) {
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

  public Runtime arrayDerefGet(Runtime index) {
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
  public static Runtime make_sub(Object codeObject) throws Exception {
    // finish setting up a CODE object
    Class<?> clazz = codeObject.getClass();
    Method mm = clazz.getMethod("apply", RuntimeArray.class, ContextType.class);
    Runtime r = new Runtime();
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
    private static final String _string_increment(String s) {
        if (s.length() < 2) {
            final int c = s.codePointAt(0);
            if ((c >= '0' && c <= '8') || (c >= 'A' && c <= 'Y') || (c >= 'a' && c <= 'y')) {
                return "" + (char)(c + 1);
            }
            if (c == '9') {
                return "10";
            }
            if (c == 'Z') {
                return "AA";
            }
            if (c == 'z') {
                return "aa";
            }
            return "1";
        }
        String c = _string_increment(s.substring(s.length()-1, s.length()));
        if (c.length() == 1) {
            // AAAC => AAAD
            return s.substring(0, s.length()-1) + c;
        }
        // AAAZ => AABA
        return _string_increment(s.substring(0, s.length()-1)) + c.substring(c.length()-1, c.length());
    }

  // Helper method to autoincrement a String variable
  private Runtime stringIncrement() {
    String str = (String) this.value;

    if (str.isEmpty()) {
        this.value = (long) 1;
        this.type = ScalarType.INTEGER;
        return this;
    }
    char c = ((String) this.value).charAt(0);
    if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
        // Handle non-numeric increment
        String s = (String) this.value;
        int length = s.length();
        c = s.charAt(length - 1);
        if ((c >= '0' && c <= '8') || (c >= 'A' && c <= 'Y') || (c >= 'a' && c <= 'y')) {
            this.value = s.substring(0, length-1) + (char)(c + 1);
        } else {
            this.value = _string_increment(s);
        }
        return this;
    }
    // Handle numeric increment
    this.set(this.parseNumber());
    return this.preAutoIncrement();
  }

  // Helper method to convert String to Integer or Double
  private Runtime parseNumber() {
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
        return new Runtime(parsedValue);
      } else {
        long parsedValue = Long.parseLong(numberStr);
        return new Runtime(parsedValue);
      }
    } catch (NumberFormatException e) {
      // Return a Runtime object with value of 0 if parsing fails
      return new Runtime(0);
    }
  }

  // Methods that implement Perl operators
  public Runtime createReference() {
    Runtime result = new Runtime();
    result.type = ScalarType.REFERENCE;
    result.value = this;
    return result;
  }

  public static Runtime undef() {
    return new Runtime();
  }

  public Runtime print() {
    System.out.print(this.toString());
    return new Runtime(1);
  }

  public Runtime say() {
    System.out.println(this.toString());
    return new Runtime(1);
  }

  public Runtime stringConcat(Runtime b) {
    return new Runtime(this.toString() + b.toString());
  }

  public Runtime unaryMinus() {
    return new Runtime(0).subtract(this);
  }

  public Runtime not() {
    if (this.getBoolean()) {
      return new Runtime(0);
    }
    return new Runtime(1);
  }

  public Runtime add(Runtime arg2) {
    Runtime arg1 = this;
    if (arg1.type == ScalarType.STRING) {
      arg1 = arg1.parseNumber();
    }
    if (arg2.type == ScalarType.STRING) {
      arg2 = arg2.parseNumber();
    }
    if (arg1.type == ScalarType.DOUBLE || arg2.type == ScalarType.DOUBLE) {
      return new Runtime(arg1.getDouble() + arg2.getDouble());
    } else {
      return new Runtime(arg1.getLong() + arg2.getLong());
    }
  }

  public Runtime subtract(Runtime arg2) {
    Runtime arg1 = this;
    if (arg1.type == ScalarType.STRING) {
      arg1 = arg1.parseNumber();
    }
    if (arg2.type == ScalarType.STRING) {
      arg2 = arg2.parseNumber();
    }
    if (arg1.type == ScalarType.DOUBLE || arg2.type == ScalarType.DOUBLE) {
      return new Runtime(arg1.getDouble() - arg2.getDouble());
    } else {
      return new Runtime(arg1.getLong() - arg2.getLong());
    }
  }

  public Runtime multiply(Runtime arg2) {
    Runtime arg1 = this;
    if (arg1.type == ScalarType.STRING) {
      arg1 = arg1.parseNumber();
    }
    if (arg2.type == ScalarType.STRING) {
      arg2 = arg2.parseNumber();
    }
    if (arg1.type == ScalarType.DOUBLE || arg2.type == ScalarType.DOUBLE) {
      return new Runtime(arg1.getDouble() * arg2.getDouble());
    } else {
      return new Runtime(arg1.getLong() * arg2.getLong());
    }
  }

  public Runtime divide(Runtime arg2) {
    Runtime arg1 = this;
    if (arg1.type == ScalarType.STRING) {
      arg1 = arg1.parseNumber();
    }
    if (arg2.type == ScalarType.STRING) {
      arg2 = arg2.parseNumber();
    }
    if (arg1.type == ScalarType.DOUBLE || arg2.type == ScalarType.DOUBLE) {
      return new Runtime(arg1.getDouble() / arg2.getDouble());
    } else {
      return new Runtime(arg1.getLong() / arg2.getLong());
    }
  }

  public Runtime lessEqualThan(Runtime arg2) {
    Runtime arg1 = this;
    if (arg1.type == ScalarType.STRING) {
      arg1 = arg1.parseNumber();
    }
    if (arg2.type == ScalarType.STRING) {
      arg2 = arg2.parseNumber();
    }
    if (arg1.type == ScalarType.DOUBLE || arg2.type == ScalarType.DOUBLE) {
      return new Runtime(arg1.getDouble() <= arg2.getDouble());
    } else {
      return new Runtime(arg1.getLong() <= arg2.getLong());
    }
  }

  public Runtime lessThan(Runtime arg2) {
    Runtime arg1 = this;
    if (arg1.type == ScalarType.STRING) {
      arg1 = arg1.parseNumber();
    }
    if (arg2.type == ScalarType.STRING) {
      arg2 = arg2.parseNumber();
    }
    if (arg1.type == ScalarType.DOUBLE || arg2.type == ScalarType.DOUBLE) {
      return new Runtime(arg1.getDouble() < arg2.getDouble());
    } else {
      return new Runtime(arg1.getLong() < arg2.getLong());
    }
  }

  public Runtime preAutoIncrement() {
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

  public Runtime postAutoIncrement() {
    Runtime old = new Runtime().set(this);
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

  public Runtime preAutoDecrement() {
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

  public Runtime postAutoDecrement() {
    Runtime old = new Runtime().set(this);
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

}

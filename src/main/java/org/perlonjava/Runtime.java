import java.lang.reflect.Method;
import java.util.*;

/**
 * The Runtime class simulates Perl scalar variables.
 * 
 * In Perl, a scalar variable can hold:
 * - An integer
 * - A double (floating-point number)
 * - A string
 * - A reference (to arrays, hashes, subroutines, etc.)
 * - A code reference (anonymous subroutine)
 * - Undefined value
 * - Special literals (like filehandles, typeglobs, regular expressions, etc.)
 *
 * Perl scalars are dynamically typed, meaning their type can change at runtime.
 * This class tries to mimic this behavior by using an enum `Type` to track the type of the value stored in the scalar.
 */
public class Runtime {
    // Enum to represent the type of value stored in the scalar
    private enum Type {
        INTEGER, DOUBLE, STRING, CODE, UNDEF, REFERENCE
        // also BLESSED and special literals like filehandles, typeglobs, and regular expressions
    }

    // Fields to store the type and value of the scalar variable
    // TODO add cache for integer/string values
    public Type type;
    public Object value;

    public Object codeObject;  // apply() needs this

    // Temporary storage for anonymous subroutines and eval string compiler context
    public static HashMap<String, Class<?>> anonSubs =
        new HashMap<String, Class<?>>(); // temp storage for make_sub()
    public static HashMap<String, EmitterContext> evalContext =
        new HashMap<String, EmitterContext>(); // storage for eval string compiler context

    // this is not safe, because the values are mutable - need to create an immutable version
    // public static zero = new Runtime(0);
    // public static one = new Runtime(1);

    // Constructors
    public Runtime() {
        this.type = Type.UNDEF;
        this.value = value;
    }

    public Runtime(long value) {
        this.type = Type.INTEGER;
        this.value = value;
    }

    public Runtime(int value) {
        this.type = Type.INTEGER;
        this.value = (long) value;
    }

    public Runtime(double value) {
        this.type = Type.DOUBLE;
        this.value = value;
    }

    public Runtime(String value) {
        this.type = Type.STRING;
        this.value = value;
    }

    public Runtime(Method value) {
        this.type = Type.CODE;
        this.value = value;
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
                return ((Method) this.value).hashCode(); // Use Method's hashCode as the ID
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
                return ((Method) this.value).hashCode(); // Use Method's hashCode as the ID
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

    // Setters
    public Runtime set(Runtime value) {
        this.type = value.type;
        this.value = value.value;
        this.codeObject = value.codeObject;
        return this;
    }

    public Runtime set(long value) {
        this.type = Type.INTEGER;
        this.value = value;
        return this;
    }

    public Runtime set(String value) {
        this.type = Type.STRING;
        this.value = value;
        return this;
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
            case REFERENCE:
                return "REF(" + value.hashCode() + ")";
            case CODE:
                return "CODE(" + value.hashCode() + ")";
            case UNDEF:
                return "";
            default:
                return "Undefined";
        }
    }

  // Factory method to create a CODE object (anonymous subroutine)
  public static Runtime make_sub(Object codeObject) throws Exception {
    // finish setting up a CODE object
    Class<?> clazz = codeObject.getClass();
    Method mm = clazz.getMethod("apply", Runtime.class, ContextType.class);
    Runtime r = new Runtime();
    r.value = mm;
    r.type = Type.CODE;
    r.codeObject = codeObject;  // XXX TODO Cleanup - store a single object instead of two
    return r;
  }

  // Method to apply (execute) a subroutine reference
  public Runtime apply(Runtime a, ContextType callContext) throws Exception {
    if (type == Type.CODE) {
        return (Runtime) ((Method) value).invoke(this.codeObject, a, callContext);
    } else {
        throw new IllegalStateException("Variable does not contain a code reference");
    }
  }

  // Method to compile the text of eval string into an anonymous subroutine
  public static void eval_string(Runtime code, String evalTag) throws Exception {
    // retrieve the eval context that was saved at program compile-time
    EmitterContext evalCtx = Runtime.evalContext.get(evalTag);

    // TODO - this can be cached for performance
    // retrieve closure variable list
    // alternately, scan the AST for variables and capture only the ones that are used
    Map<Integer, String> visibleVariables = evalCtx.symbolTable.getAllVisibleVariables();
    String[] newEnv = new String[visibleVariables.size()];
    for (Integer index : visibleVariables.keySet()) {
      String variableName = visibleVariables.get(index);
      newEnv[index] = variableName;
    }

    // Process the string source code to create the Token list
    Lexer lexer = new Lexer(code.toString());
    List<Token> tokens = lexer.tokenize(); // Tokenize the Perl code
    // Create the AST
    // Create an instance of ErrorMessageUtil with the file name and token list
    ErrorMessageUtil errorUtil = new ErrorMessageUtil(evalCtx.fileName, tokens);
    Parser parser = new Parser(errorUtil, tokens); // Parse the tokens
    Node ast = parser.parse(); // Generate the abstract syntax tree (AST)

    evalCtx.errorUtil = new ErrorMessageUtil(evalCtx.fileName, tokens);
    Class<?> generatedClass = ASMMethodCreator.createClassWithMethod(
            evalCtx,
            newEnv, // Closure variables
            ast
    );
    return;
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
            if (Character.isDigit(c) || (c == '.' && !hasDecimal) || ((c == 'e' || c == 'E') && !hasExponent) || (c == '-' && !hasSign)) {
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
  public Runtime print() {
    System.out.print(this.toString());
    return new Runtime(1);
  }

  public Runtime stringConcat(Runtime b) {
    return new Runtime(this.toString() + b.toString());
  }

  public Runtime unaryMinus() {
    return new Runtime(0).subtract(this);
  }

    public Runtime not() {
        if ( this.getBoolean() ) {
            return new Runtime(0);
        }
        return new Runtime(1);
    }

    public Runtime or(Runtime arg2) {
        if ( this.getBoolean() ) {
            return this;
        }
        return arg2;
    }

    public Runtime and(Runtime arg2) {
        if ( this.getBoolean() ) {
            return arg2;
        }
        return this;
    }

    public Runtime add(Runtime arg2) {
        Runtime arg1 = this;
        if (arg1.type == Type.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == Type.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == Type.DOUBLE || arg2.type == Type.DOUBLE) {
            return new Runtime(arg1.getDouble() + arg2.getDouble());
        } else {
            return new Runtime(arg1.getLong() + arg2.getLong());
        }
    }

    public Runtime subtract(Runtime arg2) {
        Runtime arg1 = this;
        if (arg1.type == Type.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == Type.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == Type.DOUBLE || arg2.type == Type.DOUBLE) {
            return new Runtime(arg1.getDouble() - arg2.getDouble());
        } else {
            return new Runtime(arg1.getLong() - arg2.getLong());
        }
    }

    public Runtime multiply(Runtime arg2) {
        Runtime arg1 = this;
        if (arg1.type == Type.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == Type.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == Type.DOUBLE || arg2.type == Type.DOUBLE) {
            return new Runtime(arg1.getDouble() * arg2.getDouble());
        } else {
            return new Runtime(arg1.getLong() * arg2.getLong());
        }
    }

    public Runtime divide(Runtime arg2) {
        Runtime arg1 = this;
        if (arg1.type == Type.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == Type.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == Type.DOUBLE || arg2.type == Type.DOUBLE) {
            return new Runtime(arg1.getDouble() / arg2.getDouble());
        } else {
            return new Runtime(arg1.getLong() / arg2.getLong());
        }
    }
}

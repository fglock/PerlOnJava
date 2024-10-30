package org.perlonjava.runtime;

import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.codegen.EmitterMethodCreator;
import org.perlonjava.codegen.JavaClassInfo;
import org.perlonjava.lexer.Lexer;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.parser.Parser;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * The RuntimeCode class represents a compiled code object in the runtime environment.
 * It provides functionality to compile, store, and execute Perl subroutines and eval strings.
 */
public class RuntimeCode implements RuntimeScalarReference {

    // Temporary storage for anonymous subroutines and eval string compiler context
    public static HashMap<String, Class<?>> anonSubs = new HashMap<>(); // temp storage for makeCodeObject()
    public static HashMap<String, EmitterContext> evalContext = new HashMap<>(); // storage for eval string compiler context

    // Method object representing the compiled subroutine
    public Method methodObject;
    // Code object instance used during execution
    public Object codeObject;
    // Prototype of the subroutine
    public String prototype;
    // Attributes associated with the subroutine
    public List<String> attributes = new ArrayList<>();

    /**
     * Constructs a RuntimeCode instance with the specified prototype and attributes.
     *
     * @param prototype  the prototype of the subroutine
     * @param attributes the attributes associated with the subroutine
     */
    public RuntimeCode(String prototype, List<String> attributes) {
        this.prototype = prototype;
        this.attributes = attributes;
    }

    /**
     * Constructs a RuntimeCode instance with the specified method object and code object.
     *
     * @param methodObject the method object representing the compiled subroutine
     * @param codeObject   the code object instance used during execution
     */
    public RuntimeCode(Method methodObject, Object codeObject) {
        this.methodObject = methodObject;
        this.codeObject = codeObject;
    }

    /**
     * Constructs a RuntimeCode instance with the specified method object, code object, and prototype.
     *
     * @param methodObject the method object representing the compiled subroutine
     * @param codeObject   the code object instance used during execution
     * @param prototype    the prototype of the subroutine
     */
    public RuntimeCode(Method methodObject, Object codeObject, String prototype) {
        this.methodObject = methodObject;
        this.codeObject = codeObject;
        this.prototype = prototype;
    }

    /**
     * Compiles the text of an eval string into a Class that represents an anonymous subroutine.
     * After the Class is returned to the caller, an instance of the Class will be populated
     * with closure variables, and then makeCodeObject() will be called to transform the Class
     * instance into a Perl CODE object.
     *
     * @param code    the RuntimeScalar containing the eval string
     * @param evalTag the tag used to retrieve the eval context
     * @return the compiled Class representing the anonymous subroutine
     * @throws Exception if an error occurs during compilation
     */
    public static Class<?> evalStringHelper(RuntimeScalar code, String evalTag) throws Exception {
        // Retrieve the eval context that was saved at program compile-time
        EmitterContext ctx = RuntimeCode.evalContext.get(evalTag);
        ScopedSymbolTable symbolTable = ctx.symbolTable.clone();

        EmitterContext evalCtx = new EmitterContext(
                new JavaClassInfo(),  // internal java class name
                ctx.symbolTable.clone(), // symbolTable
                null, // method visitor
                null, // class writer
                ctx.contextType, // call context
                true, // is boxed
                ctx.errorUtil, // error message utility
                ctx.compilerOptions);
        // evalCtx.logDebug("evalStringHelper EmitterContext: " + evalCtx);
        // evalCtx.logDebug("evalStringHelper Code: " + code);

        // Process the string source code to create the LexerToken list
        Lexer lexer = new Lexer(code.toString());
        List<LexerToken> tokens = lexer.tokenize(); // Tokenize the Perl code
        Node ast;
        Class<?> generatedClass;
        try {
            // Create the AST
            // Create an instance of ErrorMessageUtil with the file name and token list
            evalCtx.errorUtil = new ErrorMessageUtil(evalCtx.compilerOptions.fileName, tokens);
            Parser parser = new Parser(evalCtx, tokens); // Parse the tokens
            ast = parser.parse(); // Generate the abstract syntax tree (AST)

            // Create a new instance of ErrorMessageUtil, resetting the line counter
            evalCtx.errorUtil = new ErrorMessageUtil(ctx.compilerOptions.fileName, tokens);
            evalCtx.symbolTable = symbolTable.clone(); // reset the symboltable
            generatedClass = EmitterMethodCreator.createClassWithMethod(
                    evalCtx,
                    ast,
                    true  // use try-catch
            );
        } catch (Exception e) {
            // Compilation error in eval-string

            // Set the global error variable "$@" using GlobalContext.setGlobalVariable(key, value)
            GlobalContext.getGlobalVariable("main::@").set(e.getMessage());

            // In case of error return an "undef" ast and class
            ast = new OperatorNode("undef", null, 1);
            evalCtx.errorUtil = new ErrorMessageUtil(ctx.compilerOptions.fileName, tokens);
            evalCtx.symbolTable = symbolTable.clone(); // reset the symboltable
            generatedClass = EmitterMethodCreator.createClassWithMethod(
                    evalCtx,
                    ast,
                    true  // use try-catch
            );
        }
        return generatedClass;
    }

    /**
     * Factory method to create a CODE object (anonymous subroutine).
     * This is called right after a new Class is compiled.
     * The codeObject is an instance of the new Class, with the closure variables in place.
     *
     * @param codeObject the instance of the compiled Class
     * @return a RuntimeScalar representing the CODE object
     * @throws Exception if an error occurs during method retrieval
     */
    public static RuntimeScalar makeCodeObject(Object codeObject) throws Exception {
        // Retrieve the class of the provided code object
        Class<?> clazz = codeObject.getClass();

        // Get the 'apply' method from the class.
        // This method takes RuntimeArray and RuntimeContextType as parameters.
        Method mm = clazz.getMethod("apply", RuntimeArray.class, int.class);

        // Wrap the method and the code object in a RuntimeCode instance
        // This allows us to store both the method and the object it belongs to
        // Create a new RuntimeScalar instance to hold the CODE object
        return new RuntimeScalar(new RuntimeCode(mm, codeObject));
    }

    /**
     * Method to apply (execute) a subroutine reference.
     * Invokes the method associated with the code object, passing the RuntimeArray and RuntimeContextType as arguments.
     *
     * @param a           the RuntimeArray containing the arguments for the subroutine
     * @param callContext the context in which the subroutine is called
     * @return the result of the subroutine execution as a RuntimeList
     */
    public RuntimeList apply(RuntimeArray a, int callContext) {
        try {
            return (RuntimeList) this.methodObject.invoke(this.codeObject, a, callContext);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns a string representation of the CODE reference.
     *
     * @return a string representing the CODE reference
     */
    public String toStringRef() {
        return "CODE(0x" + this.hashCode() + ")";
    }

    /**
     * Returns an integer representation of the CODE reference.
     *
     * @return an integer representing the CODE reference
     */
    public int getIntRef() {
        return this.hashCode();
    }

    /**
     * Returns a double representation of the CODE reference.
     *
     * @return a double representing the CODE reference
     */
    public double getDoubleRef() {
        return this.hashCode();
    }

    /**
     * Returns a boolean representation of the CODE reference.
     *
     * @return true, indicating the presence of the CODE reference
     */
    public boolean getBooleanRef() {
        return true;
    }
}

package org.perlonjava.runtime;

import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.codegen.EmitterMethodCreator;
import org.perlonjava.lexer.Lexer;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.parser.Parser;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RuntimeCode implements RuntimeScalarReference {

    // Temporary storage for anonymous subroutines and eval string compiler context
    public static HashMap<String, Class<?>> anonSubs = new HashMap<>(); // temp storage for makeCodeObject()
    public static HashMap<String, EmitterContext> evalContext = new HashMap<>(); // storage for eval string compiler context
    public Method methodObject;
    public Object codeObject; // apply() needs this
    public String prototype;

    public RuntimeCode(String prototype) {
        this.prototype = prototype;
    }

    public RuntimeCode(Method methodObject, Object codeObject) {
        this.methodObject = methodObject;
        this.codeObject = codeObject;
    }

    // Method to compile the text of eval string into a Class that
    // represents an anonymous subroutine.
    //
    // After the Class returns to the caller, an instance of the Class
    // will be populated with closure variables, and then
    // makeCodeObject() will be called to transform the Class instance
    // into a Perl CODE object
    //
    public static Class<?> evalStringHelper(RuntimeScalar code, String evalTag) throws Exception {

        // retrieve the eval context that was saved at program compile-time
        EmitterContext ctx = RuntimeCode.evalContext.get(evalTag);
        EmitterContext evalCtx = new EmitterContext(
                EmitterMethodCreator.generateClassName(), // internal java class name
                ctx.symbolTable.clone(), // clone the symbolTable
                null, // return label
                null, // method visitor
                ctx.contextType, // call context
                true, // is boxed
                ctx.errorUtil, // error message utility
                ctx.compilerOptions);

        // TODO - this can be cached for performance
        // retrieve closure variable list
        // alternately, scan the AST for variables and capture only the ones that are used
        String[] newEnv = evalCtx.symbolTable.getVisibleVariableNames();

        Node ast;

        // Process the string source code to create the LexerToken list
        Lexer lexer = new Lexer(code.toString());
        List<LexerToken> tokens = lexer.tokenize(); // Tokenize the Perl code
        try {
            // Create the AST
            // Create an instance of ErrorMessageUtil with the file name and token list
            evalCtx.errorUtil = new ErrorMessageUtil(evalCtx.compilerOptions.fileName, tokens);
            Parser parser = new Parser(evalCtx, tokens); // Parse the tokens
            ast = parser.parse(); // Generate the abstract syntax tree (AST)
        } catch (Exception e) {
            // compilation error in eval-string

            // Set the global error variable "$@" using GlobalContext.setGlobalVariable(key, value)
            GlobalContext.getGlobalVariable("main::@").set(e.toString());

            // In case of error return an "undef" ast
            ast = new OperatorNode("undef", null, 1);
        }

        // Create a new instance of ErrorMessageUtil, resetting the line counter
        evalCtx.errorUtil = new ErrorMessageUtil(ctx.compilerOptions.fileName, tokens);
        evalCtx.symbolTable = ctx.symbolTable.clone(); // reset the symboltable
        Class<?> generatedClass = EmitterMethodCreator.createClassWithMethod(
                evalCtx,
                newEnv, // Closure variables
                ast,
                true  // use try-catch
        );
        return generatedClass;
    }

    // Factory method to create a CODE object (anonymous subroutine)
    //
    // This is called right after a new Class is compiled.
    //
    // codeObject is an instance of the new Class, with the closure variables in place.
    //
    public static RuntimeScalar makeCodeObject(Object codeObject) throws Exception {
        // Retrieve the class of the provided code object
        Class<?> clazz = codeObject.getClass();

        // Get the 'apply' method from the class.
        // This method takes RuntimeArray and RuntimeContextType as parameters.
        Method mm = clazz.getMethod("apply", RuntimeArray.class, RuntimeContextType.class);

        // Wrap the method and the code object in a RuntimeCode instance
        // This allows us to store both the method and the object it belongs to
        // Create a new RuntimeScalar instance to hold the CODE object
        return new RuntimeScalar(new RuntimeCode(mm, codeObject));
    }

    // Method to apply (execute) a subroutine reference
    public RuntimeList apply(RuntimeArray a, RuntimeContextType callContext) throws Exception {
        // Invoke the method associated with the code object, passing the RuntimeArray and RuntimeContextType as arguments
        // This executes the subroutine and returns the result, which is expected to be a RuntimeList
        return (RuntimeList) this.methodObject.invoke(this.codeObject, a, callContext);
    }

    public String toStringRef() {
        return "CODE(" + this.hashCode() + ")";
    }

    public int getIntRef() {
        return this.hashCode();
    }

    public double getDoubleRef() {
        return this.hashCode();
    }

    public boolean getBooleanRef() {
        return true;
    }
}

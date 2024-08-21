package org.perlonjava;

import org.perlonjava.node.Node;
import org.perlonjava.node.OperatorNode;

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

        EmitterContext evalCtx = new EmitterContext("(eval)", // filename
                EmitterMethodCreator.generateClassName(), // internal java class name
                ctx.symbolTable.clone(), // clone the symbolTable
                null, // return label
                null, // method visitor
                ctx.contextType, // call context
                true, // is boxed
                ctx.errorUtil, // error message utility
                ctx.debugEnabled, ctx.tokenizeOnly, ctx.compileOnly, ctx.parseOnly);

        // TODO - this can be cached for performance
        // retrieve closure variable list
        // alternately, scan the AST for variables and capture only the ones that are used
        Map<Integer, String> visibleVariables = evalCtx.symbolTable.getAllVisibleVariables();
        String[] newEnv = new String[visibleVariables.size()];
        for (Integer index : visibleVariables.keySet()) {
            String variableName = visibleVariables.get(index);
            newEnv[index] = variableName;
        }

        Node ast;

        // Process the string source code to create the LexerToken list
        Lexer lexer = new Lexer(code.toString());
        List<LexerToken> tokens = lexer.tokenize(); // Tokenize the Perl code
        try {
            // Create the AST
            // Create an instance of ErrorMessageUtil with the file name and token list
            ErrorMessageUtil errorUtil = new ErrorMessageUtil(evalCtx.fileName, tokens);
            Parser parser = new Parser(errorUtil, tokens); // Parse the tokens
            ast = parser.parse(); // Generate the abstract syntax tree (AST)
        } catch (Exception e) {
            // compilation error in eval-string

            // Set the global error variable "$@" using Namespace.setGlobalVariable(key, value)
            Namespace.setGlobalVariable("$main::@", e.toString());

            ast = new OperatorNode("undef", null, 1); // return an "undef" ast
        }

        evalCtx.errorUtil = new ErrorMessageUtil(evalCtx.fileName, tokens);
        Class<?> generatedClass = EmitterMethodCreator.createClassWithMethod(evalCtx, newEnv, // Closure variables
                ast, true  // use try-catch
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

        // Create a new RuntimeScalar instance to hold the CODE object
        RuntimeScalar r = new RuntimeScalar();

        // Wrap the method and the code object in a RuntimeCode instance
        // This allows us to store both the method and the object it belongs to
        r.value = new RuntimeCode(mm, codeObject);

        // Set the type of the RuntimeScalar to CODE to indicate it holds a code reference
        r.type = RuntimeScalarType.CODE;

        // Return the fully constructed RuntimeScalar object
        return r;
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

package org.perlonjava;

import org.perlonjava.node.Node;
import org.perlonjava.RuntimeArray;
import org.perlonjava.RuntimeContextType;
import org.perlonjava.RuntimeList;
import org.perlonjava.ErrorMessageUtil;

import java.lang.invoke.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

public class PerlLanguageProvider {

    private static final MethodHandles.Lookup lookup = MethodHandles.lookup();

    /**
     * Executes the given Perl code and returns the result.
     *
     * @param code The Perl code to execute.
     * @param fileName The source filename.
     * @param debugEnabled Flag to enable debugging.
     * @param tokenizeOnly Flag to enable tokenization only.
     * @param compileOnly Flag to enable compilation only.
     * @param parseOnly Flag to enable parsing only.
     * @return The result of the Perl code execution.
     * @throws Throwable If an error occurs during execution.
     */
    public static RuntimeList executePerlCode(String code, String fileName, boolean debugEnabled, boolean tokenizeOnly, boolean compileOnly, boolean parseOnly) throws Throwable {
        // Create the compiler context
        EmitterContext ctx = new EmitterContext(
                fileName, // Source filename
                EmitterMethodCreator.generateClassName(), // internal java class name
                new ScopedSymbolTable(), // Top-level symbol table
                null, // Return label
                null, // Method visitor
                RuntimeContextType.VOID, // Call context
                true, // Is boxed
                null,  // errorUtil
                debugEnabled,   // debugEnabled flag
                tokenizeOnly,
                compileOnly,
                parseOnly
        );

        // Enter a new scope in the symbol table and add special Perl variables
        ctx.symbolTable.enterScope();
        ctx.symbolTable.addVariable("this"); // anon sub instance is local variable 0
        ctx.symbolTable.addVariable("@_"); // Argument list is local variable 1
        ctx.symbolTable.addVariable("wantarray"); // Call context is local variable 2

        Namespace.initializeGlobals();

        ctx.logDebug("parse code: " + code);
        ctx.logDebug("  call context " + ctx.contextType);

        // Create the LexerToken list
        Lexer lexer = new Lexer(code);
        List<LexerToken> tokens = lexer.tokenize(); // Tokenize the Perl code
        if (ctx.tokenizeOnly) {
            // Printing the tokens
            for (LexerToken token : tokens) {
              System.out.println(token);
            }
            return null; // success
        }

        // Create the AST
        // Create an instance of ErrorMessageUtil with the file name and token list
        ErrorMessageUtil errorUtil = new ErrorMessageUtil(ctx.fileName, tokens);
        Parser parser = new Parser(errorUtil, tokens); // Parse the tokens
        Node ast = parser.parse(); // Generate the abstract syntax tree (AST)
        if (ctx.parseOnly) {
            // Printing the ast
            System.out.println(ast);
            return null; // success
        }
        ctx.logDebug("-- AST:\n" + ast + "--\n");

        // Create the Java class from the AST
        ctx.logDebug("createClassWithMethod");
        // Create a new instance of ErrorMessageUtil, resetting the line counter
        ctx.errorUtil = new ErrorMessageUtil(ctx.fileName, tokens);
        Class<?> generatedClass = EmitterMethodCreator.createClassWithMethod(
                ctx,
                new String[] {}, // Closure variables
                ast,
                false   // no try-catch
        );
        if (ctx.compileOnly) {
            return null; // success
        }

        // Find the constructor
        Constructor<?> constructor = generatedClass.getConstructor();

        // Instantiate the class
        Object instance = constructor.newInstance();

        // Find the apply method
        Method applyMethod = generatedClass.getMethod("apply", RuntimeArray.class, RuntimeContextType.class);

        // Define the method type
        MethodType methodType = MethodType.methodType(RuntimeList.class, RuntimeArray.class, RuntimeContextType.class);

        // Use invokedynamic to invoke the method
        CallSite callSite = new ConstantCallSite(lookup.findVirtual(generatedClass, "apply", methodType));
        MethodHandle invoker = callSite.dynamicInvoker();

        // Invoke the method
        RuntimeList result = (RuntimeList) invoker.invoke(instance, new RuntimeArray(), RuntimeContextType.SCALAR);

        // Print the result of the execution
        ctx.logDebug("Result of generatedMethod: " + result);

        return result;
    }
}


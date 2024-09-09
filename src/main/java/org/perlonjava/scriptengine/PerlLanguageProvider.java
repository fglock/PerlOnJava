package org.perlonjava.scriptengine;

import org.perlonjava.ArgumentParser;
import org.perlonjava.astnode.Node;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.codegen.EmitterMethodCreator;
import org.perlonjava.lexer.Lexer;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.parser.Parser;
import org.perlonjava.runtime.*;

import java.lang.invoke.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * The PerlLanguageProvider class is responsible for executing Perl code within the Java environment.
 * It provides methods to execute, tokenize, compile, and parse Perl code.
 * <p>
 * This class uses Java's MethodHandles and reflection to dynamically invoke methods and constructors.
 * It also integrates with the runtime classes such as RuntimeArray, RuntimeContextType, and RuntimeList
 * to manage the execution context and results.
 * <p>
 * Key functionalities include:
 * - Executing Perl code and returning the result.
 * - Enabling debugging, tokenization, compilation, and parsing modes.
 * - Handling errors and providing meaningful error messages.
 * <p>
 * Why is this class needed?
 * <p>
 * The PerlLanguageProvider class abstracts the complexity of executing Perl code within a Java environment.
 * Directly invoking Perl code execution involves intricate setup and handling of various runtime contexts,
 * error management, and integration with Java's MethodHandles and reflection APIs. By encapsulating these
 * details within a single class, we provide a simpler and more manageable interface for executing Perl code.
 */
public class PerlLanguageProvider {

    // Lookup object for performing method handle operations
    private static final MethodHandles.Lookup lookup = MethodHandles.lookup();

    /**
     * Executes the given Perl code and returns the result.
     *
     * @param compilerOptions Compiler flags, file name and source code
     * @return The result of the Perl code execution.
     * @throws Throwable If an error occurs during execution.
     */
    public static RuntimeList executePerlCode(ArgumentParser.CompilerOptions compilerOptions) throws Exception {

        ScopedSymbolTable globalSymbolTable = new ScopedSymbolTable();
        // Enter a new scope in the symbol table and add special Perl variables
        globalSymbolTable.enterScope();
        globalSymbolTable.addVariable("this"); // anon sub instance is local variable 0
        globalSymbolTable.addVariable("@_"); // Argument list is local variable 1
        globalSymbolTable.addVariable("wantarray"); // Call context is local variable 2

        // Create the compiler context
        EmitterContext ctx = new EmitterContext(
                EmitterMethodCreator.generateClassName(), // internal java class name
                globalSymbolTable.clone(), // Top-level symbol table
                null, // Return label
                null, // Method visitor
                RuntimeContextType.VOID, // Call context
                true, // Is boxed
                null,  // errorUtil
                compilerOptions
        );


        GlobalContext.initializeGlobals();
        GlobalContext.getGlobalArray("main::ARGV").elements = compilerOptions.argumentList.elements;
        GlobalContext.getGlobalVariable("main::0").set(compilerOptions.fileName);

        ctx.logDebug("parse code: " + compilerOptions.code);
        ctx.logDebug("  call context " + ctx.contextType);

        // Create the LexerToken list
        Lexer lexer = new Lexer(compilerOptions.code);
        List<LexerToken> tokens = lexer.tokenize(); // Tokenize the Perl code
        if (ctx.compilerOptions.tokenizeOnly) {
            // Printing the tokens
            for (LexerToken token : tokens) {
                System.out.println(token);
            }
            return null; // success
        }
        compilerOptions.code = null;    // Throw away the source code to spare memory

        // Create the AST
        // Create an instance of ErrorMessageUtil with the file name and token list
        ctx.errorUtil = new ErrorMessageUtil(ctx.compilerOptions.fileName, tokens);
        Parser parser = new Parser(ctx, tokens); // Parse the tokens
        Node ast = parser.parse(); // Generate the abstract syntax tree (AST)
        if (ctx.compilerOptions.parseOnly) {
            // Printing the ast
            System.out.println(ast);
            return null; // success
        }
        ctx.logDebug("-- AST:\n" + ast + "--\n");

        // Create the Java class from the AST
        ctx.logDebug("createClassWithMethod");
        // Create a new instance of ErrorMessageUtil, resetting the line counter
        ctx.errorUtil = new ErrorMessageUtil(ctx.compilerOptions.fileName, tokens);
        ctx.symbolTable = globalSymbolTable.clone(); // reset the symboltable
        Class<?> generatedClass = EmitterMethodCreator.createClassWithMethod(
                ctx,
                ast,
                false   // no try-catch
        );
        if (ctx.compilerOptions.compileOnly) {
            return null; // success
        }

        // Find the constructor
        Constructor<?> constructor = generatedClass.getConstructor();

        // Instantiate the class
        Object instance = constructor.newInstance();

        // Find the apply method
        Method applyMethod = generatedClass.getMethod("apply", RuntimeArray.class, int.class);

        // Define the method type
        MethodType methodType = MethodType.methodType(RuntimeList.class, RuntimeArray.class, int.class);

        // Use invokedynamic to invoke the method
        CallSite callSite = new ConstantCallSite(lookup.findVirtual(generatedClass, "apply", methodType));
        MethodHandle invoker = callSite.dynamicInvoker();

        // Invoke the method
        RuntimeList result;
        try {
            result = (RuntimeList) invoker.invoke(instance, new RuntimeArray(), RuntimeContextType.SCALAR);
        } catch (Throwable t) {
            throw new RuntimeException("Error executing Perl code for " + ctx.compilerOptions.fileName + ": " + t.getMessage(), t);
        }

        // Print the result of the execution
        ctx.logDebug("Result of generatedMethod: " + result);

        return result;
    }
}


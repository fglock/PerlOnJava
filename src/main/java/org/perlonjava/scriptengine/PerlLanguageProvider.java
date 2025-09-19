package org.perlonjava.scriptengine;

import org.perlonjava.CompilerOptions;
import org.perlonjava.astnode.Node;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.codegen.EmitterMethodCreator;
import org.perlonjava.codegen.JavaClassInfo;
import org.perlonjava.lexer.Lexer;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.parser.DataSection;
import org.perlonjava.parser.Parser;
import org.perlonjava.perlmodule.Strict;
import org.perlonjava.runtime.*;
import org.perlonjava.symbols.ScopedSymbolTable;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.util.List;

import static org.perlonjava.runtime.GlobalVariable.resetAllGlobals;
import static org.perlonjava.runtime.SpecialBlock.*;

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

    private static boolean globalInitialized = false;

    public static void resetAll() {
        globalInitialized = false;
        resetAllGlobals();
    }

    /**
     * Executes the given Perl code and returns the result.
     *
     * @param compilerOptions  Compiler flags, file name and source code
     * @param isTopLevelScript Whether this is the top-level script (affects BEGIN/END/etc handling)
     * @return The result of the Perl code execution.
     */
    public static RuntimeList executePerlCode(CompilerOptions compilerOptions,
                                              boolean isTopLevelScript) throws Exception {

        ScopedSymbolTable globalSymbolTable = new ScopedSymbolTable();
        // Enter a new scope in the symbol table and add special Perl variables
        globalSymbolTable.enterScope();
        globalSymbolTable.addVariable("this", "", null); // anon sub instance is local variable 0
        globalSymbolTable.addVariable("@_", "our", null); // Argument list is local variable 1
        globalSymbolTable.addVariable("wantarray", "", null); // Call context is local variable 2

        if (compilerOptions.codeHasEncoding) {
            globalSymbolTable.enableStrictOption(Strict.HINT_UTF8);
        }

        // For files loaded via require/do, we want scalar context
        int contextType = isTopLevelScript ? RuntimeContextType.VOID : RuntimeContextType.SCALAR;

        // Create the compiler context
        EmitterContext ctx = new EmitterContext(
                new JavaClassInfo(), // internal java class name
                globalSymbolTable.snapShot(), // Top-level symbol table
                null, // Method visitor
                null, // Class writer
                contextType, // Call context - scalar for require/do, void for top-level
                true, // Is boxed
                null,  // errorUtil
                compilerOptions,
                new RuntimeArray()
        );

        if (!globalInitialized) {
            GlobalContext.initializeGlobals(compilerOptions);
            globalInitialized = true;
        }

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
            RuntimeIO.closeAllHandles();
            return null; // success
        }
        compilerOptions.code = null;    // Throw away the source code to spare memory

        // Create the AST
        // Create an instance of ErrorMessageUtil with the file name and token list
        ctx.errorUtil = new ErrorMessageUtil(ctx.compilerOptions.fileName, tokens);
        Parser parser = new Parser(ctx, tokens); // Parse the tokens
        parser.isTopLevelScript = isTopLevelScript;
        
        // Create placeholder DATA filehandle early so it's available during BEGIN block execution
        // This ensures *ARGV = *DATA aliasing works correctly in BEGIN blocks
        DataSection.createPlaceholderDataHandle(parser);

        Node ast;
        if (isTopLevelScript) {
            CallerStack.push(
                    "main",
                    ctx.compilerOptions.fileName,
                    ctx.errorUtil.getLineNumber(parser.tokenIndex));
        }
        try {
            ast = parser.parse(); // Generate the abstract syntax tree (AST)
        } finally {
            if (isTopLevelScript) {
                CallerStack.pop();
            }
        }

        // ast = ConstantFoldingVisitor.foldConstants(ast);

        if (ctx.compilerOptions.parseOnly) {
            // Printing the ast
            System.out.println(ast);
            RuntimeIO.closeAllHandles();
            return null; // success
        }
        ctx.logDebug("-- AST:\n" + ast + "--\n");

        // Create the Java class from the AST
        ctx.logDebug("createClassWithMethod");
        // Create a new instance of ErrorMessageUtil, resetting the line counter
        ctx.errorUtil = new ErrorMessageUtil(ctx.compilerOptions.fileName, tokens);
        ctx.symbolTable = globalSymbolTable.snapShot(); // reset the symbol table
        Class<?> generatedClass = EmitterMethodCreator.createClassWithMethod(
                ctx,
                ast,
                false   // no try-catch
        );
        return executeGeneratedClass(generatedClass, ctx, isTopLevelScript);
    }

    /**
     * Executes the given Perl code using a syntax tree and returns the result.
     *
     * @param ast             The abstract syntax tree representing the Perl code.
     * @param tokens          The list of tokens representing the Perl code.
     * @param compilerOptions Compiler flags, file name and source code.
     * @return The result of the Perl code execution.
     */
    public static RuntimeList executePerlAST(Node ast,
                                             List<LexerToken> tokens,
                                             CompilerOptions compilerOptions) throws Exception {

        ScopedSymbolTable globalSymbolTable = new ScopedSymbolTable();
        globalSymbolTable.enterScope();
        globalSymbolTable.addVariable("this", "", null);
        globalSymbolTable.addVariable("@_", "our", null);
        globalSymbolTable.addVariable("wantarray", "", null);

        EmitterContext ctx = new EmitterContext(
                new JavaClassInfo(),
                globalSymbolTable.snapShot(),
                null,
                null,
                RuntimeContextType.VOID,
                true,
                null,
                compilerOptions,
                new RuntimeArray()
        );

        if (!globalInitialized) {
            GlobalContext.initializeGlobals(compilerOptions);
            globalInitialized = true;
        }

        ctx.logDebug("Using provided AST");
        ctx.logDebug("  call context " + ctx.contextType);

        // Create the Java class from the AST
        ctx.logDebug("createClassWithMethod");
        ctx.errorUtil = new ErrorMessageUtil(ctx.compilerOptions.fileName, tokens);
        ctx.symbolTable = globalSymbolTable.snapShot();
        Class<?> generatedClass = EmitterMethodCreator.createClassWithMethod(
                ctx,
                ast,
                false
        );

        return executeGeneratedClass(generatedClass, ctx, false);
    }

    /**
     * Common method to execute the generated class and return the result.
     *
     * @param generatedClass The generated Java class.
     * @param ctx            The emitter context.
     * @param isMainProgram  Indicates if this is the main program.
     * @return The result of the Perl code execution.
     */
    private static RuntimeList executeGeneratedClass(Class<?> generatedClass, EmitterContext ctx, boolean isMainProgram) throws Exception {
        runUnitcheckBlocks(ctx.unitcheckBlocks);
        if (isMainProgram) {
            runCheckBlocks();
        }
        if (ctx.compilerOptions.compileOnly) {
            RuntimeIO.closeAllHandles();
            return null;
        }

        Constructor<?> constructor = generatedClass.getConstructor();
        Object instance = constructor.newInstance();

        MethodHandle invoker = RuntimeCode.lookup.findVirtual(generatedClass, "apply", RuntimeCode.methodType);

        RuntimeList result;
        try {
            if (isMainProgram) {
                runInitBlocks();
            }

            // For require/do (non-main programs), always use SCALAR context
            // This ensures wantarray returns '' inside the required file
            int executionContext = isMainProgram ? RuntimeContextType.VOID : RuntimeContextType.SCALAR;
            result = (RuntimeList) invoker.invoke(instance, new RuntimeArray(), executionContext);

            try {
                if (isMainProgram) {
                    runEndBlocks();
                }
            } catch (Throwable endException) {
                RuntimeIO.closeAllHandles();
                String errorMessage = ErrorMessageUtil.stringifyException(endException);
                System.out.println(errorMessage);
                System.out.println("END failed--call queue aborted.");
            }
        } catch (Throwable t) {
            if (isMainProgram) {
                runEndBlocks();
            }
            RuntimeIO.closeAllHandles();
            if (t instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(t);
        }

        ctx.logDebug("Result of generatedMethod: " + result);

        RuntimeIO.flushAllHandles();
        return result;
    }
}


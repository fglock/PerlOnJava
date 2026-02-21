package org.perlonjava.scriptengine;

import org.perlonjava.CompilerOptions;
import org.perlonjava.astnode.Node;
import org.perlonjava.backend.jvm.CompiledCode;
import org.perlonjava.backend.jvm.EmitterContext;
import org.perlonjava.backend.jvm.EmitterMethodCreator;
import org.perlonjava.backend.jvm.JavaClassInfo;
import org.perlonjava.backend.bytecode.BytecodeCompiler;
import org.perlonjava.backend.bytecode.InterpretedCode;
import org.perlonjava.frontend.lexer.Lexer;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.parser.DataSection;
import org.perlonjava.frontend.parser.Parser;
import org.perlonjava.frontend.parser.SpecialBlockParser;
import org.perlonjava.perlmodule.Strict;
import org.perlonjava.runtime.runtimetypes.*;
import org.perlonjava.symbols.ScopedSymbolTable;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.util.List;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.resetAllGlobals;
import static org.perlonjava.runtime.runtimetypes.SpecialBlock.*;

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
        // Default behavior: use SCALAR context for non-top-level scripts
        return executePerlCode(compilerOptions, isTopLevelScript, -1);
    }

    /**
     * Executes the given Perl code with specified context and returns the result.
     *
     * @param compilerOptions  Compiler flags, file name and source code
     * @param isTopLevelScript Whether this is the top-level script (affects BEGIN/END/etc handling)
     * @param callerContext    The calling context (VOID, SCALAR, LIST) or -1 for default
     * @return The result of the Perl code execution.
     */
    public static RuntimeList executePerlCode(CompilerOptions compilerOptions,
                                              boolean isTopLevelScript,
                                              int callerContext) throws Exception {

        // Store the isMainProgram flag in CompilerOptions for use during code generation
        compilerOptions.isMainProgram = isTopLevelScript;

        ScopedSymbolTable globalSymbolTable = new ScopedSymbolTable();
        // Enter a new scope in the symbol table and add special Perl variables
        globalSymbolTable.enterScope();
        globalSymbolTable.addVariable("this", "", null); // anon sub instance is local variable 0
        globalSymbolTable.addVariable("@_", "our", null); // Argument list is local variable 1
        globalSymbolTable.addVariable("wantarray", "", null); // Call context is local variable 2

        if (compilerOptions.codeHasEncoding) {
            globalSymbolTable.enableStrictOption(Strict.HINT_UTF8);
        }

        // Use caller's context if specified, otherwise default based on script type
        int contextType = callerContext >= 0 ? callerContext :
                (isTopLevelScript ? RuntimeContextType.VOID : RuntimeContextType.SCALAR);

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

        // Apply any BEGIN-block filters before tokenization if requested
        // This is a workaround for the limitation that our architecture tokenizes all source upfront
        if (compilerOptions.applySourceFilters) {
            compilerOptions.code = org.perlonjava.perlmodule.FilterUtilCall.preprocessWithBeginFilters(compilerOptions.code);
        }

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
        // Snapshot the symbol table after parsing.
        // The parser records lexical declarations (e.g., `for my $p (...)`) and pragma state
        // (strict/warnings/features) into ctx.symbolTable. Resetting to a fresh global snapshot
        // loses those declarations and causes strict-vars failures during codegen.
        ctx.symbolTable = ctx.symbolTable.snapShot();
        SpecialBlockParser.setCurrentScope(ctx.symbolTable);

        // Compile to executable (compiler or interpreter based on flag)
        RuntimeCode runtimeCode = compileToExecutable(ast, ctx);

        // Execute (unified path for both backends)
        return executeCode(runtimeCode, ctx, isTopLevelScript, callerContext);
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
        // Snapshot the symbol table as seen by the parser (includes lexical decls + pragma state).
        ctx.symbolTable = ctx.symbolTable.snapShot();
        SpecialBlockParser.setCurrentScope(ctx.symbolTable);

        // Compile to executable (compiler or interpreter based on flag)
        RuntimeCode runtimeCode = compileToExecutable(ast, ctx);

        // executePerlAST is always called from BEGIN blocks which use VOID context
        return executeCode(runtimeCode, ctx, false, RuntimeContextType.VOID);
    }

    /**
     * Common method to execute compiled code and return the result.
     * Works with both interpreter (InterpretedCode) and compiler (CompiledCode).
     *
     * @param runtimeCode   The compiled RuntimeCode instance (InterpretedCode or CompiledCode)
     * @param ctx           The emitter context.
     * @param isMainProgram Indicates if this is the main program.
     * @param callerContext The calling context (VOID, SCALAR, LIST) or -1 for default
     * @return The result of the Perl code execution.
     */
    private static RuntimeList executeCode(RuntimeCode runtimeCode, EmitterContext ctx, boolean isMainProgram, int callerContext) throws Exception {
        runUnitcheckBlocks(ctx.unitcheckBlocks);
        if (isMainProgram) {
            runCheckBlocks();
        }
        if (ctx.compilerOptions.compileOnly) {
            RuntimeIO.closeAllHandles();
            return null;
        }

        RuntimeList result;
        try {
            if (isMainProgram) {
                runInitBlocks();
            }

            // Use the caller's context if specified, otherwise use default behavior
            int executionContext = callerContext >= 0 ? callerContext :
                    (isMainProgram ? RuntimeContextType.VOID : RuntimeContextType.SCALAR);

            // Call apply() directly - works for both InterpretedCode and CompiledCode
            result = runtimeCode.apply(new RuntimeArray(), executionContext);

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

    /**
     * Compiles Perl AST to an executable RuntimeCode instance (compiler or interpreter).
     * This method provides a unified compilation path that chooses the backend
     * based on CompilerOptions.useInterpreter, with automatic fallback to interpreter
     * when compilation exceeds JVM method size limits.
     *
     * @param ast The abstract syntax tree to compile
     * @param ctx The emitter context
     * @return RuntimeCode instance - either InterpretedCode or CompiledCode
     * @throws Exception if compilation fails
     */
    private static RuntimeCode compileToExecutable(Node ast, EmitterContext ctx) throws Exception {
        if (ctx.compilerOptions.useInterpreter) {
            // Interpreter path - returns InterpretedCode (extends RuntimeCode)
            ctx.logDebug("Compiling to bytecode interpreter");
            BytecodeCompiler compiler = new BytecodeCompiler(
                ctx.compilerOptions.fileName,
                1,  // sourceLine (legacy parameter)
                ctx.errorUtil  // Pass errorUtil for proper error formatting with line numbers
            );
            InterpretedCode interpretedCode = compiler.compile(ast);

            // If --disassemble is enabled, print the bytecode
            if (ctx.compilerOptions.disassembleEnabled) {
                System.out.println("=== Interpreter Bytecode ===");
                System.out.println(interpretedCode.disassemble());
                System.out.println("=== End Bytecode ===");
            }

            return interpretedCode;
        } else {
            // Compiler path - returns CompiledCode (wrapper around generated class)
            ctx.logDebug("Compiling to JVM bytecode");
            try {
                Class<?> generatedClass = EmitterMethodCreator.createClassWithMethod(
                    ctx,
                    ast,
                    false  // no try-catch
                );
                Constructor<?> constructor = generatedClass.getConstructor();
                Object instance = constructor.newInstance();

                // Create MethodHandle for the apply() method
                MethodHandle methodHandle = RuntimeCode.lookup.findVirtual(
                    generatedClass,
                    "apply",
                    RuntimeCode.methodType
                );

                // Wrap in CompiledCode for type safety and consistency
                // Main scripts don't have prototypes, so pass null
                CompiledCode compiled = new CompiledCode(
                    methodHandle,
                    instance,
                    null,  // prototype (main scripts don't have one)
                    generatedClass,
                    ctx
                );
                return compiled;

            } catch (RuntimeException e) {
                // Check if this is a "Method too large" error from ASM
                if (e.getMessage() != null && e.getMessage().contains("Method too large")) {
                    // Interpreter fallback is enabled by default and can be disabled with JPERL_DISABLE_INTERPRETER_FALLBACK
                    // automatically fall back to the interpreter backend
                    boolean showFallback = System.getenv("JPERL_SHOW_FALLBACK") != null;
                    if (showFallback) {
                        System.err.println("Note: Method too large after AST splitting, using interpreter backend.");
                    }

                    // Fall back to interpreter path
                    ctx.logDebug("Falling back to bytecode interpreter due to method size");
                    BytecodeCompiler compiler = new BytecodeCompiler(
                        ctx.compilerOptions.fileName,
                        1,  // sourceLine (legacy parameter)
                        ctx.errorUtil  // Pass errorUtil for proper error formatting with line numbers
                    );
                    InterpretedCode interpretedCode = compiler.compile(ast);

                    // If --disassemble is enabled, print the bytecode
                    if (ctx.compilerOptions.disassembleEnabled) {
                        System.out.println("=== Interpreter Bytecode ===");
                        System.out.println(interpretedCode.disassemble());
                        System.out.println("=== End Bytecode ===");
                    }

                    return interpretedCode;
                } else {
                    // Not a size error, rethrow
                    throw e;
                }
            }
        }
    }

    /**
     * Compiles Perl code to RuntimeCode without executing it.
     * This allows compilation once and execution multiple times for better performance.
     *
     * @param compilerOptions Compiler flags, file name and source code
     * @return The compiled code instance (can be used with RuntimeCode.apply via MethodHandle)
     * @throws Exception if compilation fails
     */
    public static Object compilePerlCode(CompilerOptions compilerOptions) throws Exception {
        ScopedSymbolTable globalSymbolTable = new ScopedSymbolTable();
        globalSymbolTable.enterScope();
        globalSymbolTable.addVariable("this", "", null); // anon sub instance is local variable 0
        globalSymbolTable.addVariable("@_", "our", null); // Argument list is local variable 1
        globalSymbolTable.addVariable("wantarray", "", null); // Call context is local variable 2

        if (compilerOptions.codeHasEncoding) {
            globalSymbolTable.enableStrictOption(Strict.HINT_UTF8);
        }

        EmitterContext ctx = new EmitterContext(
                new JavaClassInfo(),
                globalSymbolTable.snapShot(),
                null,
                null,
                RuntimeContextType.SCALAR,  // Default to SCALAR context
                true,
                null,
                compilerOptions,
                new RuntimeArray()
        );

        if (!globalInitialized) {
            GlobalContext.initializeGlobals(compilerOptions);
            globalInitialized = true;
        }

        // Tokenize
        Lexer lexer = new Lexer(compilerOptions.code);
        List<LexerToken> tokens = lexer.tokenize();
        compilerOptions.code = null;  // Free memory

        // Parse
        ctx.errorUtil = new ErrorMessageUtil(ctx.compilerOptions.fileName, tokens);
        Parser parser = new Parser(ctx, tokens);
        parser.isTopLevelScript = false;  // Not top-level for compiled script
        Node ast = parser.parse();

        // Compile to class or bytecode based on flag
        ctx.errorUtil = new ErrorMessageUtil(ctx.compilerOptions.fileName, tokens);
        ctx.symbolTable = ctx.symbolTable.snapShot();
        SpecialBlockParser.setCurrentScope(ctx.symbolTable);

        // Use unified compilation path (works for JSR 223 too!)
        return compileToExecutable(ast, ctx);
    }
}


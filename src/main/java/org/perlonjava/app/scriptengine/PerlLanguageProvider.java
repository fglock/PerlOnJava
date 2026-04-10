package org.perlonjava.app.scriptengine;

import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.backend.bytecode.BytecodeCompiler;
import org.perlonjava.backend.bytecode.Disassemble;
import org.perlonjava.backend.bytecode.InterpretedCode;
import org.perlonjava.backend.jvm.CompiledCode;
import org.perlonjava.backend.jvm.EmitterContext;
import org.perlonjava.backend.jvm.EmitterMethodCreator;
import org.perlonjava.backend.jvm.InterpreterFallbackException;
import org.perlonjava.backend.jvm.JavaClassInfo;
import org.perlonjava.frontend.analysis.ConstantFoldingVisitor;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.lexer.Lexer;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.parser.DataSection;
import org.perlonjava.frontend.parser.Parser;
import org.perlonjava.frontend.parser.SpecialBlockParser;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.runtime.perlmodule.BHooksEndOfScope;
import org.perlonjava.runtime.perlmodule.FilterUtilCall;
import org.perlonjava.runtime.perlmodule.Strict;
import org.perlonjava.runtime.runtimetypes.*;
import org.perlonjava.runtime.WarningBitsRegistry;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

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

    /**
     * Global compile lock. The parser and emitter have shared mutable static state
     * (SpecialBlockParser.symbolTable, ByteCodeSourceMapper collections, etc.) that
     * is not yet thread-safe. All compilation paths — initial compilePerlCode() and
     * runtime eval "string" via EvalStringHandler — must acquire this lock.
     * <p>
     * This serializes compilation across threads but allows concurrent execution
     * of already-compiled code. Future work (Phase 0 completion) will migrate the
     * remaining shared state to per-PerlRuntime instances, eliminating this lock.
     */
    public static final ReentrantLock COMPILE_LOCK = new ReentrantLock();

    private static boolean globalInitialized = false;

    /**
     * Ensures a PerlRuntime is bound to the current thread.
     * Called at the start of every entry point (executePerlCode, compilePerlCode, etc.)
     * to support both CLI (where Main.main() initializes) and JSR-223 (where the
     * ScriptEngine may be called from any thread).
     */
    private static void ensureRuntimeInitialized() {
        if (PerlRuntime.currentOrNull() == null) {
            PerlRuntime.initialize();
        }
    }

    public static void resetAll() {
        ensureRuntimeInitialized();
        globalInitialized = false;
        resetAllGlobals();
        DataSection.reset();
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

        ensureRuntimeInitialized();

        // Save the current scope so we can restore it after execution.
        // This is critical because require/do should not leak their scope to the caller.
        ScopedSymbolTable savedCurrentScope = SpecialBlockParser.getCurrentScope();

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

        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("parse code: " + compilerOptions.code);
        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("  call context " + ctx.contextType);

        // Apply any BEGIN-block filters before tokenization if requested
        // This is a workaround for the limitation that our architecture tokenizes all source upfront
        if (compilerOptions.applySourceFilters) {
            compilerOptions.code = FilterUtilCall.preprocessWithBeginFilters(compilerOptions.code);
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
            // Push the main script onto BHooksEndOfScope's loading stack so that
            // on_scope_end callbacks (e.g., from namespace::clean) are deferred
            // until end of parsing, matching Perl 5 behavior.
            BHooksEndOfScope.beginFileLoad(ctx.compilerOptions.fileName);
        }
        try {
            ast = parser.parse(); // Generate the abstract syntax tree (AST)
        } finally {
            if (isTopLevelScript) {
                // Fire on_scope_end callbacks now that parsing is complete.
                // This is the "end of compilation scope" equivalent.
                BHooksEndOfScope.endFileLoad(ctx.compilerOptions.fileName);
                CallerStack.pop();
            }
        }

        // ast = ConstantFoldingVisitor.foldConstants(ast);

        // Constant folding: inline user-defined constant subs and fold constant expressions.
        // This runs after parsing (so BEGIN blocks have executed and constants are defined)
        // and before code emission. The package from the symbol table is used to resolve
        // bare constant identifiers (e.g., PI from `use constant PI => 3.14`).
        ast = ConstantFoldingVisitor.foldConstants(ast, ctx.symbolTable.getCurrentPackage());

        if (ctx.compilerOptions.parseOnly) {
            // Printing the ast
            System.out.println(ast);
            RuntimeIO.closeAllHandles();
            return null; // success
        }
        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("-- AST:\n" + ast + "--\n");

        // Create the Java class from the AST
        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("createClassWithMethod");
        // Create a new instance of ErrorMessageUtil, resetting the line counter
        ctx.errorUtil = new ErrorMessageUtil(ctx.compilerOptions.fileName, tokens);
        // Snapshot the symbol table after parsing.
        // The parser records lexical declarations (e.g., `for my $p (...)`) and pragma state
        // (strict/warnings/features) into ctx.symbolTable. Resetting to a fresh global snapshot
        // loses those declarations and causes strict-vars failures during codegen.
        ctx.symbolTable = ctx.symbolTable.snapShot();
        SpecialBlockParser.setCurrentScope(ctx.symbolTable);

        try {
            // Compile to executable (compiler or interpreter based on flag)
            RuntimeCode runtimeCode = compileToExecutable(ast, ctx);

            // Execute (unified path for both backends)
            return executeCode(runtimeCode, ctx, isTopLevelScript, callerContext);
        } finally {
            // Restore the caller's scope so require/do doesn't leak its scope to the caller.
            // But do NOT restore for top-level scripts - we want the main script's pragmas to persist.
            if (savedCurrentScope != null && !isTopLevelScript) {
                SpecialBlockParser.setCurrentScope(savedCurrentScope);
            }
        }
    }

    /**
     * Executes the given Perl code using a syntax tree and returns the result.
     * Uses VOID context by default.
     *
     * @param ast             The abstract syntax tree representing the Perl code.
     * @param tokens          The list of tokens representing the Perl code.
     * @param compilerOptions Compiler flags, file name and source code.
     * @return The result of the Perl code execution.
     */
    public static RuntimeList executePerlAST(Node ast,
                                             List<LexerToken> tokens,
                                             CompilerOptions compilerOptions) throws Exception {
        return executePerlAST(ast, tokens, compilerOptions, RuntimeContextType.VOID);
    }

    /**
     * Executes the given Perl code using a syntax tree with specified context.
     *
     * @param ast             The abstract syntax tree representing the Perl code.
     * @param tokens          The list of tokens representing the Perl code.
     * @param compilerOptions Compiler flags, file name and source code.
     * @param contextType     The context to use for execution (VOID, SCALAR, LIST).
     * @return The result of the Perl code execution.
     */
    public static RuntimeList executePerlAST(Node ast,
                                             List<LexerToken> tokens,
                                             CompilerOptions compilerOptions,
                                             int contextType) throws Exception {

        ensureRuntimeInitialized();

        // Save the current scope so we can restore it after execution.
        ScopedSymbolTable savedCurrentScope = SpecialBlockParser.getCurrentScope();

        ScopedSymbolTable globalSymbolTable = new ScopedSymbolTable();
        globalSymbolTable.enterScope();
        globalSymbolTable.addVariable("this", "", null);
        globalSymbolTable.addVariable("@_", "our", null);
        globalSymbolTable.addVariable("wantarray", "", null);

        // Inherit $^H (strictOptions) from the caller's scope so BEGIN blocks
        // can see and modify the enclosing scope's compile-time hints
        if (savedCurrentScope != null) {
            globalSymbolTable.setStrictOptions(savedCurrentScope.getStrictOptions());
            // Inherit warning flags so ${^WARNING_BITS} returns correct values in BEGIN blocks
            if (!savedCurrentScope.warningFlagsStack.isEmpty()) {
                globalSymbolTable.warningFlagsStack.pop();
                globalSymbolTable.warningFlagsStack.push((java.util.BitSet) savedCurrentScope.warningFlagsStack.peek().clone());
            }
            if (!savedCurrentScope.warningDisabledStack.isEmpty()) {
                globalSymbolTable.warningDisabledStack.pop();
                globalSymbolTable.warningDisabledStack.push((java.util.BitSet) savedCurrentScope.warningDisabledStack.peek().clone());
            }
            if (!savedCurrentScope.warningFatalStack.isEmpty()) {
                globalSymbolTable.warningFatalStack.pop();
                globalSymbolTable.warningFatalStack.push((java.util.BitSet) savedCurrentScope.warningFatalStack.peek().clone());
            }
        }

        EmitterContext ctx = new EmitterContext(
                new JavaClassInfo(),
                globalSymbolTable.snapShot(),
                null,
                null,
                contextType,
                true,
                null,
                compilerOptions,
                new RuntimeArray()
        );

        if (!globalInitialized) {
            GlobalContext.initializeGlobals(compilerOptions);
            globalInitialized = true;
        }

        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("Using provided AST");
        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("  call context " + ctx.contextType);

        // Create the Java class from the AST
        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("createClassWithMethod");
        ctx.errorUtil = new ErrorMessageUtil(ctx.compilerOptions.fileName, tokens);
        // Snapshot the symbol table as seen by the parser (includes lexical decls + pragma state).
        ctx.symbolTable = ctx.symbolTable.snapShot();
        SpecialBlockParser.setCurrentScope(ctx.symbolTable);

        try {
            // Compile to executable (compiler or interpreter based on flag)
            RuntimeCode runtimeCode = compileToExecutable(ast, ctx);

            return executeCode(runtimeCode, ctx, false, contextType);
        } finally {
            // Propagate $^H changes back to the caller's scope so subsequent
            // code in the same lexical block sees the updated hints
            if (savedCurrentScope != null) {
                savedCurrentScope.setStrictOptions(ctx.symbolTable.getStrictOptions());
                // Also update per-call-site hints so caller()[8] and caller()[10] are correct
                WarningBitsRegistry.setCallSiteHints(ctx.symbolTable.getStrictOptions());
                WarningBitsRegistry.snapshotCurrentHintHash();
                SpecialBlockParser.setCurrentScope(savedCurrentScope);
            }
        }
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
            // Push a CallerStack entry so caller() inside CHECK/INIT/END blocks
            // sees the main program as their caller, matching Perl 5 behavior
            // where these blocks run from the main program scope.
            CallerStack.push("main", ctx.compilerOptions.fileName, 0);
            try {
                runCheckBlocks();
            } finally {
                CallerStack.pop();
            }
        }
        if (ctx.compilerOptions.compileOnly) {
            RuntimeIO.closeAllHandles();
            return null;
        }

        RuntimeList result;
        try {
            if (isMainProgram) {
                CallerStack.push("main", ctx.compilerOptions.fileName, 0);
                try {
                    runInitBlocks();
                } finally {
                    CallerStack.pop();
                }
            }

            // Use the caller's context if specified, otherwise use default behavior
            int executionContext = callerContext >= 0 ? callerContext :
                    (isMainProgram ? RuntimeContextType.VOID : RuntimeContextType.SCALAR);

            // Call apply() directly - works for both InterpretedCode and CompiledCode
            result = runtimeCode.apply(new RuntimeArray(), executionContext);

            try {
                if (isMainProgram) {
                    CallerStack.push("main", ctx.compilerOptions.fileName, 0);
                    try {
                        runEndBlocks();
                    } finally {
                        CallerStack.pop();
                    }
                }
            } catch (Throwable endException) {
                RuntimeIO.closeAllHandles();
                String errorMessage = ErrorMessageUtil.stringifyException(endException);
                System.out.println(errorMessage);
                System.out.println("END failed--call queue aborted.");
            }
        } catch (PerlExitException e) {
            // PerlExitException already ran END blocks and closed handles in WarnDie.exit()
            // Just re-throw for the caller to handle
            throw e;
        } catch (PerlNonLocalReturnException e) {
            // A non-local return escaped to the top level (e.g., return inside map/grep
            // at the top level). In Perl 5, this produces "Can't return outside a subroutine".
            // Consume the exception and treat the return value as the program result.
            result = e.returnValue != null ? e.returnValue.getList() : new RuntimeList();
        } catch (Throwable t) {
            if (isMainProgram) {
                CallerStack.push("main", ctx.compilerOptions.fileName, 0);
                try {
                    runEndBlocks(false);  // Don't reset $? on exception path
                } finally {
                    CallerStack.pop();
                }
                RuntimeIO.closeAllHandles();
            }
            if (t instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(t);
        }

        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("Result of generatedMethod: " + result);

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
        if (ctx.compilerOptions.useInterpreter || RuntimeCode.FORCE_INTERPRETER) {
            // Interpreter path - returns InterpretedCode (extends RuntimeCode)
            if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("Compiling to bytecode interpreter");
            BytecodeCompiler compiler = new BytecodeCompiler(
                    ctx.compilerOptions.fileName,
                    1,  // sourceLine (legacy parameter)
                    ctx.errorUtil  // Pass errorUtil for proper error formatting with line numbers
            );
            InterpretedCode interpretedCode = compiler.compile(ast, ctx);

            // If --disassemble is enabled, print the bytecode
            if (ctx.compilerOptions.disassembleEnabled) {
                System.out.println("=== Interpreter Bytecode ===");
                System.out.println(Disassemble.disassemble(interpretedCode));
                System.out.println("=== End Bytecode ===");
            }

            return interpretedCode;
        } else {
            // Compiler path - returns CompiledCode (wrapper around generated class)
            if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("Compiling to JVM bytecode");
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

            } catch (InterpreterFallbackException fallback) {
                // getBytecode() already compiled interpreter code as fallback
                // when ASM frame computation failed (e.g., high fan-in to shared labels).
                // Use the pre-compiled interpreter code directly.
                boolean showFallback = System.getenv("JPERL_SHOW_FALLBACK") != null;
                if (showFallback) {
                    System.err.println("Note: Using interpreter fallback (ASM frame compute crash).");
                }
                return fallback.interpretedCode;
            } catch (Throwable e) {
                // Check if this is a recoverable compilation error that can use interpreter fallback
                // Catch Throwable (not just RuntimeException) because ClassFormatError
                // ("Too many arguments in method signature") extends Error, not Exception
                if (needsInterpreterFallback(e)) {
                    boolean showFallback = System.getenv("JPERL_SHOW_FALLBACK") != null;
                    if (showFallback) {
                        System.err.println("Note: Method too large after AST splitting, using interpreter backend.");
                    }

                    if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("Falling back to bytecode interpreter due to method size");
                    // Reset strict/feature/warning flags before fallback compilation.
                    // The JVM compiler already processed BEGIN blocks (use strict, etc.)
                    // which set these flags on ctx.symbolTable. But the interpreter will
                    // re-process those pragmas during execution, so inheriting them causes
                    // false strict violations (e.g. bareword filehandles rejected).
                    if (ctx.symbolTable != null) {
                        ctx.symbolTable.strictOptionsStack.pop();
                        ctx.symbolTable.strictOptionsStack.push(0);
                    }
                    BytecodeCompiler compiler = new BytecodeCompiler(
                            ctx.compilerOptions.fileName,
                            1,
                            ctx.errorUtil
                    );
                    InterpretedCode interpretedCode = compiler.compile(ast, ctx);

                    if (ctx.compilerOptions.disassembleEnabled) {
                        System.out.println("=== Interpreter Bytecode ===");
                        System.out.println(Disassemble.disassemble(interpretedCode));
                        System.out.println("=== End Bytecode ===");
                    }

                    return interpretedCode;
                } else if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                } else {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private static boolean needsInterpreterFallback(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            // VerifyError means the JVM rejected the generated bytecode
            // (e.g., invalid stack map frames from complex control flow).
            // Fall back to interpreter instead of crashing.
            if (t instanceof VerifyError) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && (
                    msg.contains("Method too large") ||
                            msg.contains("Too many arguments in method signature") ||
                            msg.contains("ASM frame computation failed") ||
                            msg.contains("Unexpected runtime error during bytecode generation") ||
                            msg.contains("dstFrame") ||
                            msg.contains("requires interpreter fallback"))) {
                return true;
            }
        }
        return false;
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
        ensureRuntimeInitialized();

        COMPILE_LOCK.lock();
        try {
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
        } finally {
            COMPILE_LOCK.unlock();
        }
    }
}


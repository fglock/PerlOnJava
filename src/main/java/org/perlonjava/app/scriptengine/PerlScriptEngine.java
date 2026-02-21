package org.perlonjava.app.scriptengine;

import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.runtime.runtimetypes.RuntimeList;

import javax.script.*;
import java.io.Reader;
import java.io.StringWriter;

/**
 * The PerlScriptEngine class is a custom implementation of the AbstractScriptEngine
 * that supports the Compilable interface for improved performance.
 *
 * It allows the execution of Perl scripts within the Java environment using the Java Scripting API (JSR 223).
 * <p>
 * This class provides the necessary methods to evaluate Perl scripts, manage script contexts, and integrate
 * with the scripting engine framework.
 * <p>
 * Key functionalities include:
 * - Evaluating Perl scripts from strings or readers.
 * - Compiling Perl scripts once and executing multiple times (via Compilable interface).
 * - Managing script contexts to handle variable bindings and input/output streams.
 * - Providing a factory method to obtain the associated ScriptEngineFactory.
 * <p>
 * By extending AbstractScriptEngine and implementing Compilable, PerlScriptEngine provides both
 * direct evaluation and pre-compilation capabilities for optimal performance.
 */
public class PerlScriptEngine extends AbstractScriptEngine implements Compilable {

    private final ScriptEngineFactory factory;

    public PerlScriptEngine(ScriptEngineFactory factory) {
        this.factory = factory;
    }

    @Override
    public Object eval(String script, ScriptContext context) throws ScriptException {
        try {
            CompilerOptions options = new CompilerOptions();
            options.fileName = "<STDIN>";
            options.code = script;

            RuntimeList result = PerlLanguageProvider.executePerlCode(options, true);
            return result != null ? result.toString() : null;
        } catch (Throwable t) {
            ScriptException scriptException = new ScriptException("Error executing Perl script: " + t.getMessage());
            scriptException.initCause(t);
            throw scriptException;
        }
    }

    @Override
    public Object eval(Reader reader, ScriptContext context) throws ScriptException {
        StringWriter writer = new StringWriter();
        char[] buffer = new char[1024];
        int numRead;
        try {
            while ((numRead = reader.read(buffer)) != -1) {
                writer.write(buffer, 0, numRead);
            }
        } catch (Exception e) {
            ScriptException scriptException = new ScriptException("Error reading script");
            scriptException.initCause(e);
            throw scriptException;
        }
        return eval(writer.toString(), context);
    }

    /**
     * Compile a Perl script for reuse.
     * This allows compilation once and execution multiple times, improving performance.
     *
     * @param script The Perl code to compile
     * @return A CompiledScript that can be executed multiple times
     * @throws ScriptException if compilation fails
     */
    @Override
    public CompiledScript compile(String script) throws ScriptException {
        try {
            CompilerOptions options = new CompilerOptions();
            options.fileName = "<compiled>";
            options.code = script;

            // Compile to code instance (Object to avoid ClassLoader issues)
            Object compiledCode = PerlLanguageProvider.compilePerlCode(options);

            // Create PerlCompiledScript with MethodHandle for invocation
            return new PerlCompiledScript(this, compiledCode);
        } catch (Throwable t) {
            ScriptException scriptException = new ScriptException("Error compiling Perl script: " + t.getMessage());
            scriptException.initCause(t);
            throw scriptException;
        }
    }

    /**
     * Compile a Perl script from a Reader.
     *
     * @param reader The Reader containing Perl code
     * @return A CompiledScript that can be executed multiple times
     * @throws ScriptException if compilation fails
     */
    @Override
    public CompiledScript compile(Reader reader) throws ScriptException {
        StringWriter writer = new StringWriter();
        char[] buffer = new char[1024];
        int numRead;
        try {
            while ((numRead = reader.read(buffer)) != -1) {
                writer.write(buffer, 0, numRead);
            }
        } catch (Exception e) {
            ScriptException scriptException = new ScriptException("Error reading script");
            scriptException.initCause(e);
            throw scriptException;
        }
        return compile(writer.toString());
    }

    @Override
    public Bindings createBindings() {
        return new SimpleBindings();
    }

    @Override
    public ScriptEngineFactory getFactory() {
        return factory;
    }
}
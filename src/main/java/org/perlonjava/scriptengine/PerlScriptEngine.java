package org.perlonjava.scriptengine;

import org.perlonjava.CompilerOptions;
import org.perlonjava.runtime.RuntimeList;

import javax.script.*;
import java.io.Reader;
import java.io.StringWriter;

/**
 * The PerlScriptEngine class is a custom implementation of the AbstractScriptEngine.
 * It allows the execution of Perl scripts within the Java environment using the Java Scripting API (JSR 223).
 * <p>
 * This class provides the necessary methods to evaluate Perl scripts, manage script contexts, and integrate
 * with the scripting engine framework.
 * <p>
 * Key functionalities include:
 * - Evaluating Perl scripts from strings or readers.
 * - Managing script contexts to handle variable bindings and input/output streams.
 * - Providing a factory method to obtain the associated ScriptEngineFactory.
 * <p>
 * By extending AbstractScriptEngine, PerlScriptEngine inherits basic script engine functionalities and focuses on
 * implementing the specifics of Perl script execution.
 */
public class PerlScriptEngine extends AbstractScriptEngine {

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

    @Override
    public Bindings createBindings() {
        return new SimpleBindings();
    }

    @Override
    public ScriptEngineFactory getFactory() {
        return factory;
    }
}


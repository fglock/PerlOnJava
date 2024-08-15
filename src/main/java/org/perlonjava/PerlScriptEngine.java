package org.perlonjava;

import javax.script.*;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.lang.invoke.*;

public class PerlScriptEngine extends AbstractScriptEngine {

    private final ScriptEngineFactory factory;
    private static final MethodHandles.Lookup lookup = MethodHandles.lookup();

    public PerlScriptEngine(ScriptEngineFactory factory) {
        this.factory = factory;
    }

    @Override
    public Object eval(String script, ScriptContext context) throws ScriptException {
        // Extract necessary parameters from the context or set defaults
        String fileName = (String) context.getAttribute("fileName");
        boolean debugEnabled = Boolean.TRUE.equals(context.getAttribute("debugEnabled"));
        boolean tokenizeOnly = Boolean.TRUE.equals(context.getAttribute("tokenizeOnly"));
        boolean compileOnly = Boolean.TRUE.equals(context.getAttribute("compileOnly"));
        boolean parseOnly = Boolean.TRUE.equals(context.getAttribute("parseOnly"));

        try {
            RuntimeList result = PerlLanguageProvider.executePerlCode(
                script, 
                fileName != null ? fileName : "<unknown>", 
                debugEnabled, 
                tokenizeOnly, 
                compileOnly, 
                parseOnly
            );
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


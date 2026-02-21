package org.perlonjava.app.scriptengine;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import java.util.Arrays;
import java.util.List;

import static org.perlonjava.core.Configuration.perlVersion;

/**
 * The PerlScriptEngineFactory class is an implementation of the ScriptEngineFactory interface.
 * It is responsible for creating instances of the PerlScriptEngine and providing metadata about the engine.
 * <p>
 * This class provides the necessary methods to:
 * - Create new instances of the PerlScriptEngine.
 * - Provide information about the scripting engine, such as its name, version, and supported MIME types.
 * - List the names and extensions associated with the Perl scripting language.
 * <p>
 * By implementing ScriptEngineFactory, this class allows the PerlScriptEngine to be discovered and used
 * by the Java Scripting API (JSR 223) framework.
 */
public class PerlScriptEngineFactory implements ScriptEngineFactory {

    @Override
    public String getEngineName() {
        return "Perl5";
    }

    @Override
    public String getEngineVersion() {
        return "1.0";
    }

    @Override
    public List<String> getExtensions() {
        return Arrays.asList("pl", "perl");
    }

    @Override
    public List<String> getMimeTypes() {
        return List.of("application/x-perl");
    }

    @Override
    public List<String> getNames() {
        return Arrays.asList("Perl5", "perl");
    }

    @Override
    public String getLanguageName() {
        return "Perl";
    }

    @Override
    public String getLanguageVersion() {
        return perlVersion;
    }

    @Override
    public Object getParameter(String key) {
        switch (key) {
            case ScriptEngine.NAME:
            case ScriptEngine.ENGINE:
                return getEngineName();
            case ScriptEngine.ENGINE_VERSION:
                return getEngineVersion();
            case ScriptEngine.LANGUAGE:
                return getLanguageName();
            case ScriptEngine.LANGUAGE_VERSION:
                return getLanguageVersion();
            default:
                return null;
        }
    }

    @Override
    public String getMethodCallSyntax(String obj, String m, String... args) {
        return obj + "." + m + "(" + String.join(", ", args) + ")";
    }

    @Override
    public String getOutputStatement(String toDisplay) {
        return "print \"" + toDisplay + "\";";
    }

    @Override
    public String getProgram(String... statements) {
        return String.join("\n", statements);
    }

    @Override
    public ScriptEngine getScriptEngine() {
        return new PerlScriptEngine(this);
    }
}

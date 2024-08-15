package org.perlonjava;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import java.util.Arrays;
import java.util.List;

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
        return Arrays.asList("application/x-perl");
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
        return "5.32.0";
    }

    @Override
    public Object getParameter(String key) {
        switch (key) {
            case ScriptEngine.NAME:
                return getEngineName();
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

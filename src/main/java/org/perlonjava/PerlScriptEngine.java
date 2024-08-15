package org.perlonjava;

import javax.script.*;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.lang.invoke.*;
import java.util.*;

/**
 * The PerlScriptEngine class is a custom implementation of the AbstractScriptEngine.
 * It allows the execution of Perl scripts within the Java environment using the Java Scripting API (JSR 223).
 *
 * This class provides the necessary methods to evaluate Perl scripts, manage script contexts, and integrate
 * with the scripting engine framework.
 *
 * Key functionalities include:
 * - Evaluating Perl scripts from strings or readers.
 * - Managing script contexts to handle variable bindings and input/output streams.
 * - Providing a factory method to obtain the associated ScriptEngineFactory.
 *
 * By extending AbstractScriptEngine, PerlScriptEngine inherits basic script engine functionalities and focuses on
 * implementing the specifics of Perl script execution.
 */
public class PerlScriptEngine extends AbstractScriptEngine {

    private final ScriptEngineFactory factory;
    private static final MethodHandles.Lookup lookup = MethodHandles.lookup();

    public PerlScriptEngine(ScriptEngineFactory factory) {
        this.factory = factory;
    }

    @Override
    public Object eval(String script, ScriptContext context) throws ScriptException {

        // // debug
        // System.out.println("ScriptContext attributes:");
        // for (int scope : new int[]{ScriptContext.GLOBAL_SCOPE, ScriptContext.ENGINE_SCOPE}) {
        //     for (Map.Entry<String, Object> entry : context.getBindings(scope).entrySet()) {
        //         System.out.print("Scope: " + (scope == ScriptContext.GLOBAL_SCOPE ? "GLOBAL" : "ENGINE") + ", Key: " + entry.getKey() + ", Value: ");
        //         Object value = entry.getValue();
        //         if (value instanceof Object[]) {
        //             System.out.println(Arrays.toString((Object[]) value));
        //         } else {
        //             System.out.println(value);
        //         }
        //     }
        // }

        // Extract necessary parameters from the context or set defaults
        String[] args = (String[]) context.getAttribute("javax.script.argv");

        String filename = (String) context.getAttribute("javax.script.filename");

        // Handle the case where args might be null
        if (args == null) {
            args = new String[0]; // Provide a default empty array
        }

        // Add filename to the args array if filename is not null
        String[] newArgs;
        if (filename != null) {
            newArgs = new String[args.length + 1];
            newArgs[0] = filename;
            System.arraycopy(args, 0, newArgs, 1, args.length);
        } else {
            newArgs = args; // No need to modify args if filename is null
        }

        ArgumentParser.ParsedArguments parsedArgs = ArgumentParser.parseArguments(newArgs);

        if (parsedArgs.code == null) {
            parsedArgs.code = script;
        }

        try {
            RuntimeList result = PerlLanguageProvider.executePerlCode(
                parsedArgs.code,
                filename != null ? filename : "<unknown>",
                parsedArgs.debugEnabled,
                parsedArgs.tokenizeOnly,
                parsedArgs.compileOnly,
                parsedArgs.parseOnly
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


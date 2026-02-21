package org.perlonjava.app.scriptengine;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;

import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.lang.invoke.MethodHandle;

/**
 * PerlCompiledScript represents a pre-compiled Perl script that can be executed multiple times.
 *
 * This class implements the CompiledScript interface from JSR 223, allowing Perl code to be
 * compiled once and executed many times, which significantly improves performance when the same
 * script needs to be run repeatedly.
 *
 * The compiled code is stored as a generated class instance and invoked via MethodHandle to
 * avoid ClassLoader issues.
 */
public class PerlCompiledScript extends CompiledScript {

    private final PerlScriptEngine engine;
    private final Object compiledInstance;  // Compiled code instance
    private final MethodHandle invoker;     // MethodHandle to invoke apply()

    /**
     * Creates a new PerlCompiledScript.
     *
     * @param engine           The script engine that compiled this script
     * @param compiledInstance The compiled code instance
     * @throws Exception if MethodHandle creation fails
     */
    public PerlCompiledScript(PerlScriptEngine engine, Object compiledInstance) throws Exception {
        this.engine = engine;
        this.compiledInstance = compiledInstance;

        // Create MethodHandle for apply() method
        Class<?> instanceClass = compiledInstance.getClass();
        this.invoker = RuntimeCode.lookup.findVirtual(instanceClass, "apply", RuntimeCode.methodType);
    }

    /**
     * Executes the compiled script.
     *
     * @param context The script context (bindings, readers, writers)
     * @return The result of script execution
     * @throws ScriptException if execution fails
     */
    @Override
    public Object eval(ScriptContext context) throws ScriptException {
        try {
            // Execute the compiled code with empty arguments via MethodHandle
            RuntimeArray args = new RuntimeArray();
            RuntimeList result = (RuntimeList) invoker.invoke(compiledInstance, args, RuntimeContextType.SCALAR);
            return result != null ? result.toString() : null;
        } catch (Throwable t) {
            ScriptException scriptException = new ScriptException("Error executing compiled Perl script: " + t.getMessage());
            scriptException.initCause(t);
            throw scriptException;
        }
    }

    /**
     * Returns the script engine that created this compiled script.
     *
     * @return The PerlScriptEngine instance
     */
    @Override
    public ScriptEngine getEngine() {
        return engine;
    }

    /**
     * Gets the underlying compiled code instance.
     * This can be useful for advanced use cases.
     *
     * @return The compiled code instance
     */
    public Object getCompiledInstance() {
        return compiledInstance;
    }
}

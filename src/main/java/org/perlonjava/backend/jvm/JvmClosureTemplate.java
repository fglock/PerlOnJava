package org.perlonjava.backend.jvm;

import org.perlonjava.runtime.runtimetypes.PerlSubroutine;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * Template for creating JVM-compiled closures from interpreter bytecode.
 * <p>
 * When an anonymous sub inside eval STRING is compiled to a JVM class
 * (instead of InterpretedCode), this template holds the generated class
 * and knows how to instantiate it with captured variables at runtime.
 * <p>
 * The CREATE_CLOSURE interpreter opcode checks for this type in the
 * constant pool and delegates to {@link #instantiate(RuntimeBase[])}
 * to create the closure.
 */
public class JvmClosureTemplate {

    /**
     * The JVM-compiled class implementing PerlSubroutine
     */
    public final Class<?> generatedClass;

    /**
     * Cached constructor for the generated class.
     * Takes captured variables as typed parameters (RuntimeScalar, RuntimeArray, RuntimeHash).
     */
    public final Constructor<?> constructor;

    /**
     * Perl prototype string (e.g., "$$@"), or null
     */
    public final String prototype;

    /**
     * Package where the sub was compiled (CvSTASH)
     */
    public final String packageName;

    /**
     * Creates a JvmClosureTemplate for a generated class.
     *
     * @param generatedClass the JVM class implementing PerlSubroutine
     * @param prototype      Perl prototype string, or null
     * @param packageName    package where the sub was compiled
     */
    public JvmClosureTemplate(Class<?> generatedClass, String prototype, String packageName) {
        this.generatedClass = generatedClass;
        this.prototype = prototype;
        this.packageName = packageName;

        // Cache the constructor - there should be exactly one with closure parameters
        Constructor<?>[] constructors = generatedClass.getDeclaredConstructors();
        // Find the constructor that takes the most parameters (the closure one)
        Constructor<?> best = null;
        for (Constructor<?> c : constructors) {
            if (best == null || c.getParameterCount() > best.getParameterCount()) {
                best = c;
            }
        }
        this.constructor = best;
    }

    /**
     * Instantiate the JVM-compiled closure with captured variables.
     * <p>
     * This is called at runtime by the interpreter's CREATE_CLOSURE opcode
     * when it encounters a JvmClosureTemplate in the constant pool.
     * <p>
     * The cost of reflection here is amortized: this is called once per
     * closure creation (each time the sub {} expression is evaluated),
     * not per call to the closure.
     *
     * @param capturedVars the captured variable values from the interpreter's registers
     * @return a RuntimeScalar wrapping the RuntimeCode for this closure
     */
    public RuntimeScalar instantiate(RuntimeBase[] capturedVars) {
        try {
            // Convert RuntimeBase[] to Object[] for reflection
            Object[] args = new Object[capturedVars.length];
            System.arraycopy(capturedVars, 0, args, 0, capturedVars.length);

            // Instantiate the JVM class with captured variables as constructor args
            PerlSubroutine instance = (PerlSubroutine) constructor.newInstance(args);

            // Create RuntimeCode wrapping and set __SUB__
            RuntimeCode code = new RuntimeCode(instance, prototype);
            if (packageName != null) {
                code.packageName = packageName;
            }
            RuntimeScalar codeRef = new RuntimeScalar(code);

            // Set __SUB__ on the generated class instance for self-reference
            Field subField = generatedClass.getDeclaredField("__SUB__");
            subField.set(instance, codeRef);

            return codeRef;
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate JVM closure: " + e.getMessage(), e);
        }
    }

    /**
     * Instantiate the JVM-compiled sub with no captured variables.
     *
     * @return a RuntimeScalar wrapping the RuntimeCode
     */
    public RuntimeScalar instantiateNoClosure() {
        try {
            PerlSubroutine instance = (PerlSubroutine) generatedClass.getDeclaredConstructor().newInstance();

            RuntimeCode code = new RuntimeCode(instance, prototype);
            if (packageName != null) {
                code.packageName = packageName;
            }
            RuntimeScalar codeRef = new RuntimeScalar(code);

            Field subField = generatedClass.getDeclaredField("__SUB__");
            subField.set(instance, codeRef);

            return codeRef;
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate JVM sub: " + e.getMessage(), e);
        }
    }
}

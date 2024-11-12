package org.perlonjava.runtime;

import java.util.HashMap;
import java.util.Map;

import static org.perlonjava.runtime.GlobalContext.*;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarTrue;

/**
 * The StateVariable class manages the initialization and retrieval of Perl-like state variables.
 * State variables maintain their state between calls and are initialized only once.
 */
public class StateVariable {
    // A map to track whether a state variable has been initialized.
    static Map<String, Boolean> stateVariableInitialized = new HashMap<>();

    /**
     * Checks if a state variable has been initialized.
     *
     * @param codeRef Reference to the runtime code context.
     * @param var     The name of the variable.
     * @param id      The ID of the variable.
     * @return A RuntimeScalar indicating whether the variable is initialized (true or false).
     */
    public static RuntimeScalar isInitializedStateVariable(RuntimeScalar codeRef, String var, int id) {
        String beginVar = PersistentVariable.beginVariable(id, var.substring(1));
        if (!codeRef.getDefinedBoolean()) {
            // For top-level code without __SUB__, check global initialization.
            Boolean initialized = stateVariableInitialized.get(beginVar);
            if (initialized == null || !initialized) {
                return scalarFalse;
            }
            return scalarTrue;
        } else {
            // For sub-instance code, check the specific code context.
            RuntimeCode code = (RuntimeCode) codeRef.value;
            Boolean initialized = code.stateVariableInitialized.get(beginVar);
            if (initialized == null || !initialized) {
                return scalarFalse;
            }
            return scalarTrue;
        }
    }

    /**
     * Initializes a state scalar variable.
     *
     * @param codeRef Reference to the runtime code context.
     * @param var     The name of the variable.
     * @param id      The ID of the variable.
     * @param value   The value to initialize the variable with.
     */
    public static void initializeStateVariable(RuntimeScalar codeRef, String var, int id, RuntimeScalar value) {
        String beginVar = PersistentVariable.beginVariable(id, var.substring(1));
        if (!codeRef.getDefinedBoolean()) {
            // Initialize global variable for top-level code.
            RuntimeScalar variable = getGlobalVariable(beginVar);
            stateVariableInitialized.put(beginVar, true);
            variable.set(value);
        } else {
            // Initialize variable in the specific code context.
            RuntimeCode code = (RuntimeCode) codeRef.value;
            RuntimeScalar variable = code.stateVariable.get(beginVar);
            code.stateVariableInitialized.put(beginVar, true);
            variable.set(value);
        }
    }

    /**
     * Initializes a state array variable.
     *
     * @param codeRef Reference to the runtime code context.
     * @param var     The name of the variable.
     * @param id      The ID of the variable.
     * @param value   The value to initialize the array with.
     */
    public static void initializeStateArray(RuntimeScalar codeRef, String var, int id, RuntimeArray value) {
        String beginVar = PersistentVariable.beginVariable(id, var.substring(1));
        if (!codeRef.getDefinedBoolean()) {
            // Initialize global array for top-level code.
            RuntimeArray variable = getGlobalArray(beginVar);
            stateVariableInitialized.put(beginVar, true);
            variable.setFromList(value.getList());
        } else {
            // Initialize array in the specific code context.
            RuntimeCode code = (RuntimeCode) codeRef.value;
            RuntimeArray variable = code.stateArray.get(beginVar);
            code.stateVariableInitialized.put(beginVar, true);
            variable.setFromList(value.getList());
        }
    }

    /**
     * Initializes a state hash variable.
     *
     * @param codeRef Reference to the runtime code context.
     * @param var     The name of the variable.
     * @param id      The ID of the variable.
     * @param value   The value to initialize the hash with.
     */
    public static void initializeStateHash(RuntimeScalar codeRef, String var, int id, RuntimeArray value) {
        String beginVar = PersistentVariable.beginVariable(id, var.substring(1));
        if (!codeRef.getDefinedBoolean()) {
            // Initialize global hash for top-level code.
            RuntimeHash variable = getGlobalHash(beginVar);
            stateVariableInitialized.put(beginVar, true);
            variable.setFromList(value.getList());
        } else {
            // Initialize hash in the specific code context.
            RuntimeCode code = (RuntimeCode) codeRef.value;
            RuntimeHash variable = code.stateHash.get(beginVar);
            code.stateVariableInitialized.put(beginVar, true);
            variable.setFromList(value.getList());
        }
    }

    /**
     * Retrieves a "state" scalar variable.
     *
     * @param codeRef Reference to the runtime code context.
     * @param var     The name of the variable.
     * @param id      The ID of the variable.
     * @return The retrieved RuntimeScalar.
     */
    public static RuntimeScalar retrieveStateScalar(RuntimeScalar codeRef, String var, int id) {
        String beginVar = PersistentVariable.beginVariable(id, var.substring(1));
        if (!codeRef.getDefinedBoolean()) {
            // Retrieve global variable for top-level code.
            return getGlobalVariable(beginVar);
        } else {
            // Retrieve variable in the specific code context.
            RuntimeCode code = (RuntimeCode) codeRef.value;
            RuntimeScalar variable = code.stateVariable.get(beginVar);
            if (variable == null) {
                variable = new RuntimeScalar();
                code.stateVariable.put(beginVar, variable);
            }
            return variable;
        }
    }

    /**
     * Retrieves a "state" array variable.
     *
     * @param codeRef Reference to the runtime code context.
     * @param var     The name of the variable.
     * @param id      The ID of the variable.
     * @return The retrieved RuntimeArray.
     */
    public static RuntimeArray retrieveStateArray(RuntimeScalar codeRef, String var, int id) {
        String beginVar = PersistentVariable.beginVariable(id, var.substring(1));
        if (!codeRef.getDefinedBoolean()) {
            // Retrieve global array for top-level code.
            return getGlobalArray(beginVar);
        } else {
            // Retrieve array in the specific code context.
            RuntimeCode code = (RuntimeCode) codeRef.value;
            RuntimeArray variable = code.stateArray.get(beginVar);
            if (variable == null) {
                variable = new RuntimeArray();
                code.stateArray.put(beginVar, variable);
            }
            return variable;
        }
    }

    /**
     * Retrieves a "state" hash variable.
     *
     * @param codeRef Reference to the runtime code context.
     * @param var     The name of the variable.
     * @param id      The ID of the variable.
     * @return The retrieved RuntimeHash.
     */
    public static RuntimeHash retrieveStateHash(RuntimeScalar codeRef, String var, int id) {
        String beginVar = PersistentVariable.beginVariable(id, var.substring(1));
        if (!codeRef.getDefinedBoolean()) {
            // Retrieve global hash for top-level code.
            return getGlobalHash(beginVar);
        } else {
            // Retrieve hash in the specific code context.
            RuntimeCode code = (RuntimeCode) codeRef.value;
            RuntimeHash variable = code.stateHash.get(beginVar);
            if (variable == null) {
                variable = new RuntimeHash();
                code.stateHash.put(beginVar, variable);
            }
            return variable;
        }
    }
}

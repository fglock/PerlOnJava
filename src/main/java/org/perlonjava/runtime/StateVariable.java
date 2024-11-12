package org.perlonjava.runtime;

import java.util.HashMap;
import java.util.Map;

import static org.perlonjava.runtime.GlobalContext.*;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarTrue;

public class StateVariable {
    static Map<String, Boolean> stateVariableInitialized = new HashMap<>();

    public static RuntimeScalar isInitializedStateVariable(RuntimeScalar codeRef, String var, int id) {
        // System.out.println("isInitializedStateVariable " + var + " " + id + " = " + value);
        String beginVar = PersistentVariable.beginVariable(id, var.substring(1));
        if (!codeRef.getDefinedBoolean()) {
            // top-level code doesn't have __SUB__
            // System.out.println("isInitializedStateVariable top level " + codeRef);
            Boolean initialized = stateVariableInitialized.get(beginVar);
            if (initialized == null || !initialized) {
                return scalarFalse;
            }
            return scalarTrue;
        } else {
            // System.out.println("isInitializedStateVariable sub instance " + codeRef);
            RuntimeCode code = (RuntimeCode) codeRef.value;
            Boolean initialized = code.stateVariableInitialized.get(beginVar);
            if (initialized == null || !initialized) {
                return scalarFalse;
            }
            return scalarTrue;
        }
    }

    public static void initializeStateVariable(RuntimeScalar codeRef, String var, int id, RuntimeScalar value) {
        // System.out.println("initializeStateVariable " + var + " " + id + " = " + value);
        String beginVar = PersistentVariable.beginVariable(id, var.substring(1));
        if (!codeRef.getDefinedBoolean()) {
            // top-level code doesn't have __SUB__
            // System.out.println("initializeStateVariable top level " + codeRef);
            RuntimeScalar variable = getGlobalVariable(beginVar);
            stateVariableInitialized.put(beginVar, true);
            variable.set(value);
        } else {
            // System.out.println("initializeStateVariable sub instance " + codeRef);
            RuntimeCode code = (RuntimeCode) codeRef.value;
            RuntimeScalar variable = code.stateVariable.get(beginVar);
            code.stateVariableInitialized.put(beginVar, true);
            // System.out.println("initializeStateVariable set " + value);
            variable.set(value);
        }
    }

    public static void initializeStateArray(RuntimeScalar codeRef, String var, int id, RuntimeArray value) {
        // System.out.println("initializeStateArray " + var + " " + id + " = " + value);
        String beginVar = PersistentVariable.beginVariable(id, var.substring(1));
        if (!codeRef.getDefinedBoolean()) {
            // top-level code doesn't have __SUB__
            // System.out.println("initializeStateArray top level " + codeRef);
            RuntimeArray variable = getGlobalArray(beginVar);
            stateVariableInitialized.put(beginVar, true);
            variable.setFromList(value.getList());
        } else {
            // System.out.println("initializeStateArray sub instance " + codeRef);
            RuntimeCode code = (RuntimeCode) codeRef.value;
            RuntimeArray variable = code.stateArray.get(beginVar);
            code.stateVariableInitialized.put(beginVar, true);
            // System.out.println("initializeStateArray set " + value);
            variable.setFromList(value.getList());
        }
    }

    public static void initializeStateHash(RuntimeScalar codeRef, String var, int id, RuntimeArray value) {
        // System.out.println("initializeStateHash " + var + " " + id + " = " + value);
        String beginVar = PersistentVariable.beginVariable(id, var.substring(1));
        if (!codeRef.getDefinedBoolean()) {
            // top-level code doesn't have __SUB__
            // System.out.println("initializeStateHash top level " + codeRef);
            RuntimeHash variable = getGlobalHash(beginVar);
            stateVariableInitialized.put(beginVar, true);
            variable.setFromList(value.getList());
        } else {
            // System.out.println("initializeStateHash sub instance " + codeRef);
            RuntimeCode code = (RuntimeCode) codeRef.value;
            RuntimeHash variable = code.stateHash.get(beginVar);
            code.stateVariableInitialized.put(beginVar, true);
            // System.out.println("initializeStateHash set " + value);
            variable.setFromList(value.getList());
        }
    }

    /**
     * Retrieves a "state" scalar variable.
     *
     * @param var The name of the variable.
     * @param id  The ID of the variable.
     * @return The retrieved RuntimeScalar.
     */
    public static RuntimeScalar retrieveStateScalar(RuntimeScalar codeRef, String var, int id) {
        String beginVar = PersistentVariable.beginVariable(id, var.substring(1));
        if (!codeRef.getDefinedBoolean()) {
            // top-level code doesn't have __SUB__
            // System.out.println("retrieveStateScalar top level " + codeRef);
            return getGlobalVariable(beginVar);
        } else {
            // System.out.println("retrieveStateScalar sub instance " + codeRef);
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
     * @param var The name of the variable.
     * @param id  The ID of the variable.
     * @return The retrieved RuntimeArray.
     */
    public static RuntimeArray retrieveStateArray(RuntimeScalar codeRef, String var, int id) {
        String beginVar = PersistentVariable.beginVariable(id, var.substring(1));
        if (!codeRef.getDefinedBoolean()) {
            // top-level code doesn't have __SUB__
            // System.out.println("retrieveStateArray top level " + codeRef);
            return getGlobalArray(beginVar);
        } else {
            // System.out.println("retrieveStateArray sub instance " + codeRef);
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
     * @param var The name of the variable.
     * @param id  The ID of the variable.
     * @return The retrieved RuntimeHash.
     */
    public static RuntimeHash retrieveStateHash(RuntimeScalar codeRef, String var, int id) {
        String beginVar = PersistentVariable.beginVariable(id, var.substring(1));
        if (!codeRef.getDefinedBoolean()) {
            // top-level code doesn't have __SUB__
            // System.out.println("retrieveStateHash top level " + codeRef);
            return getGlobalHash(beginVar);
        } else {
            // System.out.println("retrieveStateHash sub instance " + codeRef);
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

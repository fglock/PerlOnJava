package org.perlonjava.runtime;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.perlonjava.runtime.GlobalContext.*;

public class PersistentVariable {
    static Map<String, Boolean> stateVariableInitialized = new HashMap<>();

    /**
     * Constructs a package name for storing compile-time variables, with the given ID.
     *
     * @param id The ID of the BEGIN block.
     * @return The package name for the BEGIN block.
     */
    public static String beginPackage(int id) {
        return "PerlOnJava::_BEGIN_" + id;
    }

    /**
     * Constructs a compile-time variable name for a BEGIN block, with the given ID and name.
     *
     * @param id   The ID of the BEGIN block.
     * @param name The name of the variable.
     * @return The variable name for the BEGIN block.
     */
    static String beginVariable(int id, String name) {
        return beginPackage(id) + "::" + name;
    }

    public static RuntimeScalar initializeStateVariable(RuntimeScalar codeRef, String var, int id, RuntimeScalar value) {
        // System.out.println("initializeStateVariable not implemented " + var + " " + id + " = " + value);

        String beginVar = beginVariable(id, var.substring(1));
        if (!codeRef.getDefinedBoolean()) {
            // top-level code doesn't have __SUB__
            // System.out.println("initializeStateVariable top level " + codeRef);
            RuntimeScalar variable = getGlobalVariable(beginVar);
            if (stateVariableInitialized.getOrDefault(beginVar, false)) {
                stateVariableInitialized.put(beginVar, true);
                variable.set(value);
            }
            return variable;
        } else {
            // System.out.println("initializeStateVariable sub instance " + codeRef);
            RuntimeCode code = (RuntimeCode) codeRef.value;
            RuntimeScalar variable = code.stateVariable.get(beginVar);
            if (variable == null) {
                variable = new RuntimeScalar();
                code.stateVariable.put(beginVar, variable);
            }

            Boolean initialized = code.stateVariableInitialized.get(beginVar);
            if (initialized == null || !initialized) {
                code.stateVariableInitialized.put(beginVar, true);
                // System.out.println("initializeStateVariable set " + value);
                variable.set(value);
            }

            return variable;
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
        String beginVar = beginVariable(id, var.substring(1));
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
        String beginVar = beginVariable(id, var.substring(1));
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
        String beginVar = beginVariable(id, var.substring(1));
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

    /**
     * Retrieves a compile-time scalar variable.
     *
     * @param var The name of the variable.
     * @param id  The ID of the BEGIN block.
     * @return The retrieved RuntimeScalar.
     */
    public static RuntimeScalar retrieveBeginScalar(String var, int id) {
        String beginVar = beginVariable(id, var.substring(1));
        RuntimeScalar temp = removeGlobalVariable(beginVar);
        return temp == null ? new RuntimeScalar() : temp;
    }

    /**
     * Retrieves a compile-time array variable.
     *
     * @param var The name of the variable.
     * @param id  The ID of the BEGIN block.
     * @return The retrieved RuntimeArray.
     */
    public static RuntimeArray retrieveBeginArray(String var, int id) {
        String beginVar = beginVariable(id, var.substring(1));
        RuntimeArray temp = removeGlobalArray(beginVar);
        return temp == null ? new RuntimeArray() : temp;
    }

    /**
     * Retrieves a compile-time hash variable.
     *
     * @param var The name of the variable.
     * @param id  The ID of the BEGIN block.
     * @return The retrieved RuntimeHash.
     */
    public static RuntimeHash retrieveBeginHash(String var, int id) {
        String beginVar = beginVariable(id, var.substring(1));
        RuntimeHash temp = removeGlobalHash(beginVar);
        return temp == null ? new RuntimeHash() : temp;
    }
}

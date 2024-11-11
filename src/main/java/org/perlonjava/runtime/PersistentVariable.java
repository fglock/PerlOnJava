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

    public static RuntimeScalar initializeStateVariable(String var, int id, RuntimeScalar value) {
        System.out.println("initializeStateVariable not implemented " + var + " " + id + " = " + value);

        String beginVar = beginVariable(id, var.substring(1));
        RuntimeScalar variable = getGlobalVariable(beginVar);
        if (stateVariableInitialized.getOrDefault(beginVar, false)) {
            stateVariableInitialized.put(beginVar, true);
            variable.set(value);
        }
        return variable;
    }

    /**
     * Retrieves a "state" scalar variable.
     *
     * @param var The name of the variable.
     * @param id  The ID of the variable.
     * @return The retrieved RuntimeScalar.
     */
    public static RuntimeScalar retrieveStateScalar(Object codeObject, String var, int id) {
        RuntimeScalar codeRef = null;
        try {
            // Retrieve the class of the provided code object
            Class<?> clazz = codeObject.getClass();
            // Get the __SUB__ instance field
            Field field = clazz.getDeclaredField("__SUB__");
            codeRef = (RuntimeScalar) field.get(codeObject);
        } catch (Exception e) {
            throw new PerlCompilerException("Error retrieving state variable " + var + ": " + e.getMessage());
        }

        String beginVar = beginVariable(id, var.substring(1));
        if (codeRef == null) {
            // top-level code doesn't have __SUB__
            return getGlobalVariable(beginVar);
        } else {
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
    public static RuntimeArray retrieveStateArray(String var, int id) {
        String beginVar = beginVariable(id, var.substring(1));
        return getGlobalArray(beginVar);
    }

    /**
     * Retrieves a "state" hash variable.
     *
     * @param var The name of the variable.
     * @param id  The ID of the variable.
     * @return The retrieved RuntimeHash.
     */
    public static RuntimeHash retrieveStateHash(String var, int id) {
        String beginVar = beginVariable(id, var.substring(1));
        return getGlobalHash(beginVar);
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

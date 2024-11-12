package org.perlonjava.runtime;

import static org.perlonjava.runtime.GlobalContext.*;

public class PersistentVariable {

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

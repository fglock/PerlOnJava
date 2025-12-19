package org.perlonjava.runtime;

/**
 * The PersistentVariable class provides methods to construct and retrieve
 * compile-time variables captured within BEGIN blocks. These variables
 * are persistent to run time.
 * The variables can be scalars, arrays, or hashes, and are identified by a unique ID and name.
 */
public class PersistentVariable {

    /**
     * Constructs a package name for storing compile-time variables, with the given ID.
     * This package name is used at run time to import the variable to the lexical scope.
     *
     * @param id The ID of the BEGIN block.
     * @return The package name for the BEGIN block.
     */
    public static String beginPackage(int id) {
        return "PerlOnJava::_BEGIN_" + id;
    }

    /**
     * Constructs a compile-time variable name, with the given ID and name.
     * This variable name is used to store and retrieve variables captured by a BEGIN block,
     * that are persistent to run time.
     *
     * @param id   The ID of the BEGIN block.
     * @param name The name of the variable.
     * @return The variable name for the BEGIN block.
     */
    static String beginVariable(int id, String name) {
        return beginPackage(id) + "::" + name;
    }

    /**
     * Retrieves a compile-time scalar variable associated with a BEGIN block.
     * If the variable does not exist, fetch the global variable with the original variable name.
     * This ensures 'our' variables correctly alias the global variable.
     *
     * @param var The name of the variable (prefixed with a character indicating type).
     * @param id  The ID of the BEGIN block.
     * @return The retrieved RuntimeScalar.
     */
    public static RuntimeScalar retrieveBeginScalar(String var, int id) {
        String beginVar = beginVariable(id, var.substring(1));
        RuntimeScalar temp = GlobalVariable.removeGlobalVariable(beginVar);
        // If BEGIN variable doesn't exist, fetch the global variable with the original name
        // This is crucial for 'our' variables which should alias the global
        return temp == null ? GlobalVariable.getGlobalVariable(var) : temp;
    }

    /**
     * Retrieves a compile-time array variable associated with a BEGIN block.
     * If the variable does not exist, fetch the global array with the original variable name.
     * This ensures 'our' variables correctly alias the global array.
     *
     * @param var The name of the variable (prefixed with a character indicating type).
     * @param id  The ID of the BEGIN block.
     * @return The retrieved RuntimeArray.
     */
    public static RuntimeArray retrieveBeginArray(String var, int id) {
        String beginVar = beginVariable(id, var.substring(1));
        RuntimeArray temp = GlobalVariable.removeGlobalArray(beginVar);
        // If BEGIN variable doesn't exist, fetch the global array with the original name
        // This is crucial for 'our' variables which should alias the global
        return temp == null ? GlobalVariable.getGlobalArray(var) : temp;
    }

    /**
     * Retrieves a compile-time hash variable associated with a BEGIN block.
     * If the variable does not exist, fetch the global hash with the original variable name.
     * This ensures 'our' variables correctly alias the global hash.
     *
     * @param var The name of the variable (prefixed with a character indicating type).
     * @param id  The ID of the BEGIN block.
     * @return The retrieved RuntimeHash.
     */
    public static RuntimeHash retrieveBeginHash(String var, int id) {
        String beginVar = beginVariable(id, var.substring(1));
        RuntimeHash temp = GlobalVariable.removeGlobalHash(beginVar);
        // If BEGIN variable doesn't exist, fetch the global hash with the original name
        // This is crucial for 'our' variables which should alias the global
        return temp == null ? GlobalVariable.getGlobalHash(var) : temp;
    }
}

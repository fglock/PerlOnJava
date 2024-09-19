package org.perlonjava.runtime;


public class ModuleLoader {

    public static String moduleToFilename(String moduleName) {
        if (moduleName == null || moduleName.isEmpty()) {
            throw new IllegalArgumentException("Module name cannot be null or empty");
        }

        // Replace '::' with '/' and append '.pm'
        return moduleName.replace("::", "/") + ".pm";
    }

}


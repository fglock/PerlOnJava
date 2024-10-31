package org.perlonjava.perlmodule;

import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeCode;
import org.perlonjava.runtime.RuntimeScalar;

import java.lang.reflect.Method;

import static org.perlonjava.runtime.GlobalContext.*;

/**
 * Abstract base class for Perl modules in the Java environment.
 * This class provides functionalities to initialize Perl modules,
 * register methods, and define exports.
 */
public abstract class PerlModuleBase {

    // The name of the Perl module
    protected String moduleName;

    /**
     * Constructor for PerlModuleBase.
     *
     * @param moduleName The name of the Perl module.
     */
    public PerlModuleBase(String moduleName) {
        this.moduleName = moduleName;
        initializeModule();
    }

    /**
     * Initializes the Perl module by setting the %INC hash to indicate
     * that the module is loaded.
     */
    private void initializeModule() {
        // Set %INC to indicate the module is loaded
        getGlobalHash("main::INC").put(moduleName.replace("::", "/") + ".pm", new RuntimeScalar(moduleName + ".pm"));
    }

    /**
     * Registers a method in the Perl module.
     *
     * @param methodName The name of the method to register.
     * @param signature  The signature of the method.
     * @throws NoSuchMethodException If the method does not exist.
     */
    protected void registerMethod(String methodName, String signature) throws NoSuchMethodException {
        // Create a new RuntimeScalar instance
        RuntimeScalar instance = new RuntimeScalar();

        // Retrieve the method from the current class
        Method method = this.getClass().getMethod(methodName, RuntimeArray.class, int.class);

        // Set the method as a global code reference in the Perl namespace
        getGlobalCodeRef(moduleName + "::" + methodName).set(new RuntimeScalar(
                new RuntimeCode(method, instance, signature)));
    }

    /**
     * Defines symbols to be exported by the Perl module.
     *
     * @param exportType The type of export (e.g., EXPORT, EXPORT_OK).
     * @param symbols    The symbols to be exported.
     */
    protected void defineExport(String exportType, String... symbols) {
        // Retrieve the global array for the specified export type
        RuntimeArray exportArray = getGlobalArray(moduleName + "::" + exportType);

        // Add each symbol to the export array
        for (String symbol : symbols) {
            exportArray.push(new RuntimeScalar(symbol));
        }
    }

    /**
     * Initializes the exporter by importing the import() method
     * from the Exporter class.
     */
    protected void initializeExporter() {
        // Imports the import() method from Exporter class
        RuntimeScalar instance = new RuntimeScalar();
        Method method = null;
        try {
            // Retrieve the importSymbols method from the Exporter class
            method = Exporter.class.getMethod("importSymbols", RuntimeArray.class, int.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }

        // Set the import method as a global code reference in the Perl namespace
        getGlobalCodeRef(moduleName + "::import").set(new RuntimeScalar(
                new RuntimeCode(method, instance, null)));
    }
}

package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import java.lang.invoke.MethodHandle;

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

    public PerlModuleBase(String moduleName, boolean setInc) {
        this.moduleName = moduleName;
        if (setInc) {
            initializeModule();
        }
    }

    /**
     * Initializes the Perl module by setting the %INC hash to indicate
     * that the module is loaded.
     */
    private void initializeModule() {
        // Set %INC to indicate the module is loaded
        GlobalVariable.getGlobalHash("main::INC").put(moduleName.replace("::", "/") + ".pm", new RuntimeScalar(moduleName + ".pm"));
    }

    /**
     * Registers a method in the Perl module with a different name than the Java method.
     *
     * @param perlMethodName The name of the method in Perl.
     * @param javaMethodName The name of the method in Java.
     * @param signature      The signature of the method.
     * @throws NoSuchMethodException If the method does not exist.
     */
    protected void registerMethod(String perlMethodName, String javaMethodName, String signature) throws NoSuchMethodException {
        try {
            // Retrieve the method from the current class using the Java method name
            MethodHandle methodHandle = RuntimeCode.lookup.findStatic(this.getClass(), javaMethodName, RuntimeCode.methodType);

            RuntimeCode code = new RuntimeCode(methodHandle, this, signature);
            code.isStatic = true;

            String fullMethodName = NameNormalizer.normalizeVariableName(perlMethodName, moduleName);

            // Set the method as a global code reference in the Perl namespace using the Perl method name
            GlobalVariable.getGlobalCodeRef(fullMethodName).set(new RuntimeScalar(code));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Registers a method in the Perl module.
     *
     * @param methodName The name of the method to register.
     * @param signature  The signature of the method.
     * @throws NoSuchMethodException If the method does not exist.
     */
    protected void registerMethod(String methodName, String signature) throws NoSuchMethodException {
        registerMethod(methodName, methodName, signature);
    }

    /**
     * Defines symbols to be exported by the Perl module.
     *
     * @param exportType The type of export (e.g., EXPORT, EXPORT_OK).
     * @param symbols    The symbols to be exported.
     */
    protected void defineExport(String exportType, String... symbols) {
        // Retrieve the global array for the specified export type
        RuntimeArray exportArray = GlobalVariable.getGlobalArray(moduleName + "::" + exportType);

        // Add each symbol to the export array
        for (String symbol : symbols) {
            RuntimeArray.push(exportArray, new RuntimeScalar(symbol));
        }
    }

    /**
     * Defines a tag bundle of exportable symbols for the Perl module.
     *
     * @param tagName The name of the export tag (without the ':' prefix)
     * @param symbols The symbols to be included in this tag bundle
     */
    protected void defineExportTag(String tagName, String... symbols) {
        // Get the EXPORT_TAGS hash
        RuntimeHash exportTags = GlobalVariable.getGlobalHash(moduleName + "::EXPORT_TAGS");

        // Create new array for the tag symbols
        RuntimeArray tagArray = new RuntimeArray();
        for (String symbol : symbols) {
            RuntimeArray.push(tagArray, new RuntimeScalar(symbol));
        }

        // Add the tag array to EXPORT_TAGS hash
        exportTags.put(tagName, tagArray.createReference());
    }

    /**
     * Initializes the exporter by importing the import() method
     * from the Exporter class.
     */
    protected void initializeExporter() {
        try {
            // Imports the import() method from Exporter class
            Exporter instance = new Exporter();

            // Retrieve the 'importSymbols' method from the Exporter class
            MethodHandle methodHandle = RuntimeCode.lookup.findStatic(Exporter.class, "importSymbols", RuntimeCode.methodType);

            RuntimeCode code = new RuntimeCode(methodHandle, instance, null);
            code.isStatic = true;

            // Set the import method as a global code reference in the Perl namespace
            GlobalVariable.getGlobalCodeRef(moduleName + "::import").set(new RuntimeScalar(code));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}

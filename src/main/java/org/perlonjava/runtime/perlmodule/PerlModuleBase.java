package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ModuleOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.lang.invoke.MethodHandle;
import java.net.URL;

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
     * If a .pm stub file exists in the JAR, use the jar:PERL5LIB path format
     * so that code opening %INC entries can find a real file.
     */
    private void initializeModule() {
        String pmFileName = moduleName.replace("::", "/") + ".pm";
        String incValue;
        
        // Check if there's a .pm file in the bundled lib (JAR)
        String resourcePath = "/lib/" + pmFileName;
        URL resource = PerlModuleBase.class.getResource(resourcePath);
        if (resource != null) {
            // Use jar:PERL5LIB path format - this can be opened by the runtime
            incValue = GlobalContext.JAR_PERLLIB + "/" + pmFileName;
        } else {
            // No .pm stub file - use simple name (backwards compatible)
            incValue = moduleName + ".pm";
        }
        
        // Set %INC to indicate the module is loaded
        GlobalVariable.getGlobalHash("main::INC").put(pmFileName, new RuntimeScalar(incValue));
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
            code.packageName = moduleName;
            code.subName = perlMethodName;

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
     * Registers a method in the Perl module under a specific target package,
     * overriding the default moduleName-derived package. Useful for modules
     * that need to register methods under multiple Perl packages (e.g. a DBI
     * driver registering under DBD::Foo::dr, DBD::Foo::db, DBD::Foo::st).
     *
     * @param targetPackage   The Perl package to register the method under.
     * @param perlMethodName  The name of the method in Perl.
     * @param javaMethodName  The name of the corresponding Java method.
     * @throws NoSuchMethodException If the Java method does not exist.
     */
    protected void registerMethodInPackage(String targetPackage,
                                           String perlMethodName,
                                           String javaMethodName) throws NoSuchMethodException {
        try {
            MethodHandle methodHandle = RuntimeCode.lookup.findStatic(
                    this.getClass(), javaMethodName, RuntimeCode.methodType);
            RuntimeCode code = new RuntimeCode(methodHandle, this, null);
            code.isStatic = true;
            code.packageName = targetPackage;
            code.subName = perlMethodName;
            String fullName = NameNormalizer.normalizeVariableName(perlMethodName, targetPackage);
            GlobalVariable.getGlobalCodeRef(fullName).set(new RuntimeScalar(code));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
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
     * Requires a Perl module and adds it to this module's @ISA.
     * This allows the current module to inherit methods from the parent module.
     * The parent module is loaded via require if not already loaded.
     *
     * @param parentModule The name of the parent module (e.g., "Exporter", "DynaLoader")
     */
    protected void inheritFrom(String parentModule) {
        // Convert module name to file path (e.g., "Exporter" -> "Exporter.pm", "Foo::Bar" -> "Foo/Bar.pm")
        String modulePath = parentModule.replace("::", "/") + ".pm";
        
        // Require the module if not already loaded
        RuntimeHash inc = GlobalVariable.getGlobalHash("main::INC");
        if (!inc.exists(new RuntimeScalar(modulePath)).getBoolean()) {
            ModuleOperators.require(new RuntimeScalar(modulePath));
        }
        
        // Add to @ISA
        RuntimeArray isa = GlobalVariable.getGlobalArray(moduleName + "::ISA");
        RuntimeArray.push(isa, new RuntimeScalar(parentModule));
    }

    /**
     * Initializes the exporter by inheriting from the Exporter module.
     * This makes the module inherit Exporter's import() method from pure Perl.
     */
    protected void initializeExporter() {
        inheritFrom("Exporter");
    }
}

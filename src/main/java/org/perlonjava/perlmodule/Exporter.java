package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

import java.lang.reflect.Method;

import static org.perlonjava.runtime.GlobalContext.*;

/**
 * The Exporter class is responsible for managing the export of symbols from one Perl package to another.
 * It mimics the behavior of Perl's Exporter module, allowing symbols to be imported into other namespaces.
 */
public class Exporter {

    /**
     * Initializes the Exporter class by setting up the necessary global variables and methods.
     * This includes setting the %INC hash and loading the Exporter methods into the Perl namespace.
     */
    public static void initialize() {
        // Initialize Exporter class

        // Set %INC to indicate that Exporter.pm has been loaded
        getGlobalHash("main::INC").put("Exporter.pm", new RuntimeScalar("Exporter.pm"));

        try {
            // Load Exporter methods into Perl namespace
            Class<?> clazz = Exporter.class;
            RuntimeScalar instance = new RuntimeScalar();
            Method mm;

            // Get the importSymbols method and set it as a global code reference for Exporter::import
            mm = clazz.getMethod("importSymbols", RuntimeArray.class, int.class);
            getGlobalCodeRef("Exporter::import").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, null)));

            // Set up @EXPORTER::EXPORT_OK = ("import");
            getGlobalArray("Exporter::EXPORT_OK").push(new RuntimeScalar("import"));
        } catch (Exception e) {
            System.err.println("Warning: Failed to initialize Exporter: " + e.getMessage());
        }
    }

    /**
     * Imports symbols from a specified package into the caller's namespace.
     *
     * @param args The arguments specifying the package and symbols to import.
     * @param ctx  The context in which the import is being performed.
     * @return A RuntimeList representing the result of the import operation.
     * @throws PerlCompilerException if there are issues with the import process.
     */
    public static RuntimeList importSymbols(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new PerlCompilerException("Not enough arguments for import");
        }

        // Extract the package name from the arguments
        RuntimeScalar packageScalar = args.shift();
        String packageName = packageScalar.scalar().toString();

        // Determine the caller's namespace
        RuntimeList callerList = RuntimeScalar.caller(new RuntimeList(), RuntimeContextType.SCALAR);
        String caller = callerList.scalar().toString();

        // Retrieve the export lists and tags from the package
        RuntimeArray export = GlobalContext.getGlobalArray(packageScalar + "::EXPORT");
        RuntimeArray exportOk = GlobalContext.getGlobalArray(packageScalar + "::EXPORT_OK");
        RuntimeHash exportTags = GlobalContext.getGlobalHash(packageScalar + "::EXPORT_TAGS");

        // If no specific symbols are requested, default to exporting all symbols in @EXPORT
        if (args.size() == 0) {
            args = export;
        }

        // Process the requested symbols and tags
        RuntimeArray tagArray = new RuntimeArray();
        for (RuntimeScalar symbolObj : args.elements) {
            String symbolString = symbolObj.toString();

            if (symbolString.startsWith(":")) {
                // This is a tag, retrieve the associated symbols
                String tagName = symbolString.substring(1);
                RuntimeArray tagSymbols = exportTags.get(tagName).arrayDeref();
                if (tagSymbols != null) {
                    tagArray.elements.addAll(tagSymbols.elements);
                } else {
                    throw new PerlCompilerException("Unknown export tag: " + tagName);
                }
            } else {
                tagArray.elements.add(symbolObj);
            }
        }

        // Import the requested symbols into the caller's namespace
        for (RuntimeBaseEntity symbolObj : tagArray.elements) {
            String symbolString = symbolObj.toString();

            boolean isExported = export.elements.stream()
                    .anyMatch(e -> e.toString().equals(symbolString));
            boolean isExportOk = exportOk.elements.stream()
                    .anyMatch(e -> e.toString().equals(symbolString));

            if (isExported || isExportOk) {
                if (symbolString.startsWith("&")) {
                    importFunction(packageName, caller, symbolString.substring(1));
                } else if (symbolString.startsWith("$")) {
                    importScalar(packageName, caller, symbolString.substring(1));
                } else if (symbolString.startsWith("@")) {
                    importArray(packageName, caller, symbolString.substring(1));
                } else if (symbolString.startsWith("%")) {
                    importHash(packageName, caller, symbolString.substring(1));
                } else if (symbolString.startsWith("*")) {
                    importTypeglob(packageName, caller, symbolString.substring(1));
                } else {
                    importFunction(packageName, caller, symbolString);
                }
            } else {
                throw new PerlCompilerException("Subroutine " + symbolString + " not allowed for export in package " + packageName);
            }
        }

        return new RuntimeList();
    }

    private static void importFunction(String packageName, String caller, String functionName) {
        RuntimeScalar exportSymbol = getGlobalCodeRef(packageName + "::" + functionName);
        if (exportSymbol.type == RuntimeScalarType.CODE) {
            getGlobalCodeRef(caller + "::" + functionName).set(exportSymbol);
        } else {
            throw new PerlCompilerException("Function " + functionName + " not found in package " + packageName);
        }
    }

    private static void importScalar(String packageName, String caller, String scalarName) {
        RuntimeScalar exportScalar = getGlobalVariable(packageName + "::" + scalarName);
        getGlobalVariable(caller + "::" + scalarName).set(exportScalar);
    }

    private static void importArray(String packageName, String caller, String arrayName) {
        RuntimeArray exportArray = getGlobalArray(packageName + "::" + arrayName);
        RuntimeArray array = getGlobalArray(caller + "::" + arrayName);
        array.setFromList(exportArray.getList());
    }

    private static void importHash(String packageName, String caller, String hashName) {
        RuntimeHash exportHash = getGlobalHash(packageName + "::" + hashName);
        RuntimeHash hash = getGlobalHash(caller + "::" + hashName);
        hash.setFromList(exportHash.getList());
    }

    private static void importTypeglob(String packageName, String caller, String typeglobName) {
        // Handle typeglob import logic here
        // This is a placeholder for typeglob handling
        throw new PerlCompilerException("Typeglob import not implemented for " + typeglobName);
    }
}

package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

import static org.perlonjava.runtime.RuntimeContextType.SCALAR;

/**
 * The Exporter class is responsible for managing the export of symbols from one Perl package to another.
 * It mimics the behavior of Perl's Exporter module, allowing symbols to be imported into other namespaces.
 */
public class Exporter extends PerlModuleBase {

    public Exporter() {
        super("Exporter");
    }

    /**
     * Initializes the Exporter class by setting up the necessary global variables and methods.
     * This includes setting the %INC hash and loading the Exporter methods into the Perl namespace.
     */
    public static void initialize() {
        Exporter exporter = new Exporter();
        GlobalVariable.getGlobalVariable("Exporter::VERSION").set(new RuntimeScalar("5.78"));
        try {
            // Load Exporter methods into Perl namespace
            exporter.registerMethod("import", "importSymbols", null);
            exporter.registerMethod("export_tags", "exportTags", null);
            exporter.registerMethod("export_ok_tags", "exportOkTags", null);

            // Set up @EXPORTER::EXPORT_OK = ("import");
            RuntimeArray.push(GlobalVariable.getGlobalArray("Exporter::EXPORT_OK"), new RuntimeScalar("import"));
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
        RuntimeScalar packageScalar = RuntimeArray.shift(args);
        String packageName = packageScalar.scalar().toString();

        RuntimeScalar exportLevel = GlobalVariable.getGlobalVariable("Exporter::ExportLevel");

        // Determine the caller's namespace
        RuntimeList callerList = RuntimeCode.caller(new RuntimeList(exportLevel), SCALAR);
        String caller = callerList.scalar().toString();

        // Retrieve the export lists and tags from the package
        RuntimeArray export = GlobalVariable.getGlobalArray(packageScalar + "::EXPORT");
        RuntimeArray exportOk = GlobalVariable.getGlobalArray(packageScalar + "::EXPORT_OK");
        RuntimeHash exportTags = GlobalVariable.getGlobalHash(packageScalar + "::EXPORT_TAGS");

        // If no specific symbols are requested, default to exporting all symbols in @EXPORT
        if (args.size() == 0) {
            if (export != null && export.elements != null) {
                args = export;
            } else {
                args = new RuntimeArray();
            }
        }

        // Process the requested symbols and tags
        RuntimeArray tagArray = new RuntimeArray();
        for (RuntimeScalar symbolObj : args.elements) {
            String symbolString = symbolObj.toString();

            if (symbolString.startsWith(":")) {
                String tagName = symbolString.substring(1);
                RuntimeScalar tagValue = exportTags.get(tagName);
                if (tagValue == null || tagValue.type != RuntimeScalarType.ARRAYREFERENCE) {
                    throw new PerlCompilerException("Invalid or unknown export tag: " + tagName);
                }
                RuntimeArray tagSymbols = tagValue.arrayDeref();
                if (tagSymbols != null) {
                    tagArray.elements.addAll(tagSymbols.elements);
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

            if (!isExported && !isExportOk && !symbolString.matches("^[$@%*]")) {
                // try with/without "&"
                String finalSymbolString;
                if (symbolString.startsWith("&")) {
                    finalSymbolString = symbolString.substring(1);
                } else {
                    finalSymbolString = "&" + symbolString;
                }
                isExported = export.elements.stream()
                        .anyMatch(e -> e.toString().equals(finalSymbolString));
                isExportOk = exportOk.elements.stream()
                        .anyMatch(e -> e.toString().equals(finalSymbolString));
            }

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
                throw new PerlCompilerException("Symbol " + symbolString + " not allowed for export in package " + packageName);
            }
        }

        return new RuntimeList();
    }

    public static RuntimeList exportTags(RuntimeArray args, int ctx) {
        // Extract the package name from caller
        RuntimeScalar packageScalar = RuntimeCode.caller(new RuntimeList(), SCALAR).getFirst().scalar();
        // Retrieve the export lists and tags from the package
        RuntimeArray export = GlobalVariable.getGlobalArray(packageScalar + "::EXPORT");
        RuntimeHash exportTags = GlobalVariable.getGlobalHash(packageScalar + "::EXPORT_TAGS");
        for (RuntimeBaseEntity elem : args.elements) {
            RuntimeArray tags = exportTags.get(elem.toString()).arrayDeref();
            for (RuntimeScalar tag : tags.elements) {
                RuntimeArray.push(export, tag);
            }
        }
        return new RuntimeList();
    }

    public static RuntimeList exportOkTags(RuntimeArray args, int ctx) {
        // Extract the package name from caller
        RuntimeScalar packageScalar = RuntimeCode.caller(new RuntimeList(), SCALAR).getFirst().scalar();
        // System.out.println("exportOkTags " + packageScalar + "::EXPORT_OK " + packageScalar + "::EXPORT_TAGS");

        // Retrieve the export lists and tags from the package
        RuntimeArray exportOk = GlobalVariable.getGlobalArray(packageScalar + "::EXPORT_OK");
        RuntimeHash exportTags = GlobalVariable.getGlobalHash(packageScalar + "::EXPORT_TAGS");
        for (RuntimeBaseEntity elem : args.elements) {
            RuntimeArray tags = exportTags.get(elem.toString()).arrayDeref();
            for (RuntimeScalar tag : tags.elements) {
                RuntimeArray.push(exportOk, tag);
            }
        }
        return new RuntimeList();
    }

    private static void importFunction(String packageName, String caller, String functionName) {
        RuntimeScalar exportSymbol = GlobalVariable.getGlobalCodeRef(packageName + "::" + functionName);
        if (exportSymbol.type == RuntimeScalarType.CODE) {
            GlobalVariable.getGlobalCodeRef(caller + "::" + functionName).set(exportSymbol);
        } else {
            throw new PerlCompilerException("Function " + functionName + " not found in package " + packageName);
        }
    }

    private static void importScalar(String packageName, String caller, String scalarName) {
        RuntimeScalar exportScalar = GlobalVariable.getGlobalVariable(packageName + "::" + scalarName);
        GlobalVariable.getGlobalVariable(caller + "::" + scalarName).set(exportScalar);
    }

    private static void importArray(String packageName, String caller, String arrayName) {
        RuntimeArray exportArray = GlobalVariable.getGlobalArray(packageName + "::" + arrayName);
        RuntimeArray array = GlobalVariable.getGlobalArray(caller + "::" + arrayName);
        array.setFromList(exportArray.getList());
    }

    private static void importHash(String packageName, String caller, String hashName) {
        RuntimeHash exportHash = GlobalVariable.getGlobalHash(packageName + "::" + hashName);
        RuntimeHash hash = GlobalVariable.getGlobalHash(caller + "::" + hashName);
        hash.setFromList(exportHash.getList());
    }

    private static void importTypeglob(String packageName, String caller, String typeglobName) {
        // Handle typeglob import logic here
        // This is a placeholder for typeglob handling
        throw new PerlCompilerException("Typeglob import not implemented for " + typeglobName);
    }
}

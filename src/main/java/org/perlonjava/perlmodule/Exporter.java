package org.perlonjava.perlmodule;

import org.perlonjava.operators.MathOperators;
import org.perlonjava.parser.ParserTables;
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
            exporter.registerMethod("export", null);
            exporter.registerMethod("export_to_level", "exportToLevel", null);
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
        // MyPackage->import(@what_to_export)
        if (args.size() < 1) {
            throw new PerlCompilerException("Not enough arguments for import");
        }
        RuntimeScalar exportLevel = GlobalVariable.getGlobalVariable("Exporter::ExportLevel");
        args.elements.add(1, MathOperators.add(exportLevel, 1));  // add 1 to the current export level, to hide the import() call

        RuntimeScalar packageScalar = args.get(0);
        args.elements.add(2, packageScalar);

        return exportToLevel(args, ctx);
    }

    public static RuntimeList export(RuntimeArray args, int ctx) {
        // Exporter::export($pkg, $callpkg, @symbols)
        // Export symbols from $pkg into $callpkg namespace
        if (args.size() < 2) {
            throw new PerlCompilerException("Not enough arguments for export");
        }
        
        RuntimeScalar packageScalar = args.get(0);  // Source package
        RuntimeScalar targetPackage = args.get(1);  // Target package (caller)
        String caller = targetPackage.toString();
        String packageName = packageScalar.toString();
        
        // Get symbols to export (everything after the first two args)
        RuntimeArray symbolsToExport = new RuntimeArray();
        for (int i = 2; i < args.size(); i++) {
            symbolsToExport.elements.add(args.get(i));
        }
        
        // If no symbols specified, export @EXPORT
        if (symbolsToExport.isEmpty()) {
            RuntimeArray export = GlobalVariable.getGlobalArray(packageName + "::EXPORT");
            if (export != null && !export.elements.isEmpty()) {
                symbolsToExport = export;
            }
        }
        
        // Import the symbols into the target package
        for (RuntimeBase symbolObj : symbolsToExport.elements) {
            String symbolString = symbolObj.toString();
            
            // Check if symbol is exported
            RuntimeArray export = GlobalVariable.getGlobalArray(packageName + "::EXPORT");
            RuntimeArray exportOk = GlobalVariable.getGlobalArray(packageName + "::EXPORT_OK");
            
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
                throw new PerlCompilerException("\"" + symbolString + "\" is not exported by the " + packageName + " module\nCan't continue after import errors");
            }
        }
        
        return new RuntimeList();
    }

    public static RuntimeList exportToLevel(RuntimeArray args, int ctx) {
        // MyPackage->export_to_level($where_to_export, $package, @what_to_export)
        if (args.size() < 2) {
            throw new PerlCompilerException("Not enough arguments for export_to_level");
        }
        RuntimeArray.shift(args);   // $self

        RuntimeScalar exportLevel = RuntimeArray.shift(args); // $where_to_export
        // add 1 to the current export level, to hide the export_to_level() call
        // exportLevel = MathOperators.add(exportLevel, 1);

        // Extract the package name from the arguments
        RuntimeScalar packageScalar = RuntimeArray.shift(args); // $package
        String packageName = packageScalar.scalar().toString();

        // Determine the caller's namespace
        RuntimeList callerList = RuntimeCode.caller(new RuntimeList(exportLevel), SCALAR);
        String caller = callerList.scalar().toString();
        if (caller == null || caller.isEmpty()) {
            // In standard Perl, missing/empty caller context behaves like package 'main'.
            // This is critical for `use Some::Module qw(...)` at top-level.
            caller = "main";
        }

        // Retrieve the export lists and tags from the package
        RuntimeArray export = GlobalVariable.getGlobalArray(packageName + "::EXPORT");
        RuntimeArray exportOk = GlobalVariable.getGlobalArray(packageName + "::EXPORT_OK");
        RuntimeHash exportTags = GlobalVariable.getGlobalHash(packageName + "::EXPORT_TAGS");

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
                
                // Handle special :DEFAULT tag - it means "use @EXPORT"
                if ("DEFAULT".equals(tagName)) {
                    if (export != null && !export.elements.isEmpty()) {
                        tagArray.elements.addAll(export.elements);
                    }
                } else {
                    RuntimeScalar tagValue = exportTags.get(tagName);
                    if (tagValue == null || tagValue.type != RuntimeScalarType.ARRAYREFERENCE) {
                        throw new PerlCompilerException("Invalid or unknown export tag: " + tagName);
                    }
                    RuntimeArray tagSymbols = tagValue.arrayDeref();
                    if (tagSymbols != null) {
                        tagArray.elements.addAll(tagSymbols.elements);
                    }
                }
            } else {
                tagArray.elements.add(symbolObj);
            }
        }

        // Import the requested symbols into the caller's namespace
        for (RuntimeBase symbolObj : tagArray.elements) {
            String symbolString = symbolObj.toString();

            boolean isExported = export != null && export.elements.stream()
                    .anyMatch(e -> e.toString().equals(symbolString));
            boolean isExportOk = exportOk != null && exportOk.elements.stream()
                    .anyMatch(e -> e.toString().equals(symbolString));

            if (!isExported && !isExportOk && !symbolString.matches("^[$@%*]")) {
                // try with/without "&"
                String finalSymbolString;
                if (symbolString.startsWith("&")) {
                    finalSymbolString = symbolString.substring(1);
                } else {
                    finalSymbolString = "&" + symbolString;
                }
                isExported = export != null && export.elements.stream()
                        .anyMatch(e -> e.toString().equals(finalSymbolString));
                isExportOk = exportOk != null && exportOk.elements.stream()
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
        for (RuntimeBase elem : args.elements) {
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
        for (RuntimeBase elem : args.elements) {
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
            String fullName = caller + "::" + functionName;
            RuntimeScalar importedRef = GlobalVariable.getGlobalCodeRef(fullName);
            
            if (exportSymbol.value instanceof RuntimeCode exportedCode) {
                if (exportedCode.defined()) {
                    // Fully defined sub: import by aliasing the CODE ref.
                    importedRef.set(exportSymbol);
                } else {
                    // Forward declaration: standard Perl semantics allow this to be called and
                    // resolved via AUTOLOAD in the defining package.
                    //
                    // The importedRef and exportSymbol point to DIFFERENT RuntimeCode objects
                    // (one for main::GetAllTags, one for Package::GetAllTags).
                    // We need to make the imported one resolve via the exporting package's AUTOLOAD.
                    if (importedRef.value instanceof RuntimeCode importedCode) {
                        importedCode.sourcePackage = packageName;
                        // Also copy the subName to ensure it's set correctly
                        importedCode.subName = functionName;
                    }
                }
            }
            
            // If this function name is an overridable operator (like 'time'), mark it in isSubs
            // so the parser knows to treat it as a subroutine call instead of the builtin
            if (ParserTables.OVERRIDABLE_OP.contains(functionName)) {
                GlobalVariable.isSubs.put(fullName, true);
            }
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

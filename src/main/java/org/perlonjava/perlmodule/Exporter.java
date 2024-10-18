package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

import java.lang.reflect.Method;

import static org.perlonjava.runtime.GlobalContext.*;

public class Exporter {

    public static void initialize() {
        // Initialize Exporter class

        // Set %INC
        getGlobalHash("main::INC").put("Exporter.pm", new RuntimeScalar("Exporter.pm"));

        try {
            // load Exporter methods into Perl namespace
            Class<?> clazz = Exporter.class;
            RuntimeScalar instance = new RuntimeScalar();
            Method mm;

            mm = clazz.getMethod("importSymbols", RuntimeArray.class, int.class);
            getGlobalCodeRef("Exporter::import").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, null)));

            // set up @EXPORTER::EXPORT_OK = ("import");
            getGlobalArray("Exporter::EXPORT_OK").push(new RuntimeScalar("import"));
        } catch (Exception e) {
            System.err.println("Warning: Failed to initialize Exporter: " + e.getMessage());
        }
    }

    public static RuntimeList importSymbols(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new PerlCompilerException("Not enough arguments for import");
        }

        // System.out.println("importSymbols: " + args);
        RuntimeScalar packageScalar = args.shift();
        String packageName = packageScalar.scalar().toString();
        // System.out.println("Importing symbols from package " + packageName);

        RuntimeList callerList = RuntimeScalar.caller(new RuntimeList(), RuntimeContextType.SCALAR);
        String caller = callerList.scalar().toString();

        RuntimeArray export = GlobalContext.getGlobalArray(packageScalar + "::EXPORT");
        RuntimeArray exportOk = GlobalContext.getGlobalArray(packageScalar + "::EXPORT_OK");
        RuntimeHash exportTags = GlobalContext.getGlobalHash(packageScalar + "::EXPORT_TAGS");
        // System.out.println("export: " + packageScalar + "::EXPORT " + export);
        // System.out.println("exportOk: " + exportOk);

        if (args.size() == 0) {
            args = export;
        }

        RuntimeArray tagArray = new RuntimeArray();
        for (RuntimeScalar symbolObj : args.elements) {
            String symbolString = symbolObj.toString();

            if (symbolString.startsWith(":")) {
                // This is a tag
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

        for (RuntimeBaseEntity symbolObj : tagArray.elements) {
            String symbolString = symbolObj.toString();
            // System.out.println("Importing symbol " + symbolString);

            boolean isExported = export.elements.stream()
                    .anyMatch(e -> e.toString().equals(symbolString));
            boolean isExportOk = exportOk.elements.stream()
                    .anyMatch(e -> e.toString().equals(symbolString));

            if (isExported || isExportOk) {
                RuntimeScalar symbolRef = getGlobalCodeRef(packageName + "::" + symbolString);
                if (symbolRef.type == RuntimeScalarType.CODE) {
                    getGlobalCodeRef(caller + "::" + symbolString).set(symbolRef);
                } else {
                    throw new PerlCompilerException("Subroutine " + symbolString + " not found in package " + packageName);
                }
            } else {
                throw new PerlCompilerException("Subroutine " + symbolString + " not allowed for export in package " + packageName);
            }
        }

        return new RuntimeList();
    }
}

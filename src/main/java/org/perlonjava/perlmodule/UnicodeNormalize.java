package org.perlonjava.perlmodule;

import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.text.Normalizer;
import java.text.Normalizer.Form;

/**
 * Utility class for Unicode::Normalize operations in Perl.
 *
 * <p>Extends {@link PerlModuleBase} to leverage module initialization and method registration.</p>
 */
public class UnicodeNormalize extends PerlModuleBase {

    /**
     * Constructor for UnicodeNormalize.
     * Initializes the module with the name "Unicode::Normalize".
     */
    public UnicodeNormalize() {
        super("Unicode::Normalize", true);
    }

    /**
     * Static initializer to set up the Unicode::Normalize module.
     * This method initializes the exporter and defines the symbols that can be exported.
     * It also registers methods that can be called from the Perl environment.
     */
    public static void initialize() {
        UnicodeNormalize unicodeNormalize = new UnicodeNormalize();
        unicodeNormalize.initializeExporter();
        unicodeNormalize.defineExport("EXPORT_OK", "normalize");
        unicodeNormalize.defineExport("EXPORT", "NFD", "NFC", "NFKD", "NFKC");
        try {
            unicodeNormalize.registerMethod("normalize", "$");
            unicodeNormalize.registerMethod("NFD", "$");
            unicodeNormalize.registerMethod("NFC", "$");
            unicodeNormalize.registerMethod("NFKD", "$");
            unicodeNormalize.registerMethod("NFKC", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Unicode::Normalize method: " + e.getMessage());
        }
    }

    // Normalization Form D
    public static RuntimeList NFD(RuntimeArray args, int ctx) {
        RuntimeScalar arg = args.get(0);
        return new RuntimeList(new RuntimeScalar(
                Normalizer.normalize(arg.toString(), Form.NFD)
        ));
    }

    // Normalization Form C
    public static RuntimeList NFC(RuntimeArray args, int ctx) {
        RuntimeScalar arg = args.get(0);
        return new RuntimeList(new RuntimeScalar(
                Normalizer.normalize(arg.toString(), Form.NFC)
        ));
    }

    // Normalization Form KD
    public static RuntimeList NFKD(RuntimeArray args, int ctx) {
        RuntimeScalar arg = args.get(0);
        return new RuntimeList(new RuntimeScalar(
                Normalizer.normalize(arg.toString(), Form.NFKD)
        ));
    }

    // Normalization Form KC
    public static RuntimeList NFKC(RuntimeArray args, int ctx) {
        RuntimeScalar arg = args.get(0);
        return new RuntimeList(new RuntimeScalar(
                Normalizer.normalize(arg.toString(), Form.NFKC)
        ));
    }

    // Normalize based on the form specified
    public static RuntimeList normalize(RuntimeArray args, int ctx) {
        RuntimeScalar formArg = args.get(0);
        RuntimeScalar inputArg = args.get(1);

        String form = formArg.toString();
        return switch (form.toUpperCase()) {
            case "D" -> NFD(new RuntimeArray(inputArg), ctx);
            case "C" -> NFC(new RuntimeArray(inputArg), ctx);
            case "KD" -> NFKD(new RuntimeArray(inputArg), ctx);
            case "KC" -> NFKC(new RuntimeArray(inputArg), ctx);
            default -> throw new IllegalArgumentException("Invalid normalization form: " + form);
        };
    }
}

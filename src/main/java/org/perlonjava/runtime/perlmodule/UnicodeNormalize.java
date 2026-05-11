package org.perlonjava.runtime.perlmodule;

import com.ibm.icu.lang.UCharacter;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.text.Normalizer;
import java.text.Normalizer.Form;

/**
 * Utility class for Unicode::Normalize operations in Perl.
 *
 * <p>Extends {@link PerlModuleBase} to leverage module initialization and method registration.</p>
 */
public class UnicodeNormalize extends PerlModuleBase {

    /** Kept in sync with {@code Unicode/Normalize.pm} for {@code XSLoader::load} checks. */
    public static final String XS_VERSION = "1.32";

    /**
     * Constructor for UnicodeNormalize.
     * Initializes the module with the name "Unicode::Normalize".
     * Note: We pass false to allow the Perl module to also load, since it contains
     * utility functions and will create stubs for XS functions that we don't implement.
     */
    public UnicodeNormalize() {
        super("Unicode::Normalize", false);
    }

    /**
     * Static initializer to set up the Unicode::Normalize module.
     * This method initializes the exporter and defines the symbols that can be exported.
     * It also registers methods that can be called from the Perl environment.
     */
    public static void initialize() {
        UnicodeNormalize unicodeNormalize = new UnicodeNormalize();
        unicodeNormalize.initializeExporter();

        // Define @EXPORT - the functions exported by default
        unicodeNormalize.defineExport("EXPORT", "NFD", "NFC", "NFKD", "NFKC");

        // Define @EXPORT_OK - all functions that can be exported on request
        // This matches the full list from perl5/dist/Unicode-Normalize/Normalize.pm lines 14-22
        unicodeNormalize.defineExport("EXPORT_OK",
                "normalize", "decompose", "reorder", "compose",
                "checkNFD", "checkNFKD", "checkNFC", "checkNFKC", "check",
                "getCanon", "getCompat", "getComposite", "getCombinClass",
                "isExclusion", "isSingleton", "isNonStDecomp", "isComp2nd", "isComp_Ex",
                "isNFD_NO", "isNFC_NO", "isNFC_MAYBE", "isNFKD_NO", "isNFKC_NO", "isNFKC_MAYBE",
                "FCD", "checkFCD", "FCC", "checkFCC", "composeContiguous", "splitOnLastStarter",
                "normalize_partial", "NFC_partial", "NFD_partial", "NFKC_partial", "NFKD_partial"
        );

        // Define %EXPORT_TAGS - This matches perl5/dist/Unicode-Normalize/Normalize.pm lines 23-28
        // :all tag exports everything from @EXPORT and @EXPORT_OK
        unicodeNormalize.defineExportTag("all",
                "NFD", "NFC", "NFKD", "NFKC",  // @EXPORT
                "normalize", "decompose", "reorder", "compose",
                "checkNFD", "checkNFKD", "checkNFC", "checkNFKC", "check",
                "getCanon", "getCompat", "getComposite", "getCombinClass",
                "isExclusion", "isSingleton", "isNonStDecomp", "isComp2nd", "isComp_Ex",
                "isNFD_NO", "isNFC_NO", "isNFC_MAYBE", "isNFKD_NO", "isNFKC_NO", "isNFKC_MAYBE",
                "FCD", "checkFCD", "FCC", "checkFCC", "composeContiguous", "splitOnLastStarter",
                "normalize_partial", "NFC_partial", "NFD_partial", "NFKC_partial", "NFKD_partial"
        );

        // :normalize tag
        unicodeNormalize.defineExportTag("normalize",
                "NFD", "NFC", "NFKD", "NFKC", "normalize", "decompose", "reorder", "compose"
        );

        // :check tag
        unicodeNormalize.defineExportTag("check",
                "checkNFD", "checkNFKD", "checkNFC", "checkNFKC", "check"
        );

        // :fast tag
        unicodeNormalize.defineExportTag("fast",
                "FCD", "checkFCD", "FCC", "checkFCC", "composeContiguous"
        );

        try {
            unicodeNormalize.registerMethod("normalize", "$$");
            unicodeNormalize.registerMethod("NFD", "$");
            unicodeNormalize.registerMethod("NFC", "$");
            unicodeNormalize.registerMethod("NFKD", "$");
            unicodeNormalize.registerMethod("NFKC", "$");
            unicodeNormalize.registerMethod("getCombinClass", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Unicode::Normalize method: " + e.getMessage());
        }
    }

    /**
     * Canonical combining class (UCD) for a single code point.
     * {@link Unicode::Collate} calls this with numeric code points from {@code unpack_U}.
     */
    public static RuntimeList getCombinClass(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeList(new RuntimeScalar(0));
        }
        int cp = args.get(0).getInt();
        if (cp < 0 || cp > Character.MAX_CODE_POINT) {
            return new RuntimeList(new RuntimeScalar(0));
        }
        int cc = UCharacter.getCombiningClass(cp);
        return new RuntimeList(new RuntimeScalar(cc));
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

        String form = formArg.toString().toUpperCase();
        // Perl Unicode::Normalize accepts both short names (D, C, …) and long (NFD, NFC, …).
        return switch (form) {
            case "D", "NFD" -> NFD(new RuntimeArray(inputArg), ctx);
            case "C", "NFC" -> NFC(new RuntimeArray(inputArg), ctx);
            case "KD", "NFKD" -> NFKD(new RuntimeArray(inputArg), ctx);
            case "KC", "NFKC" -> NFKC(new RuntimeArray(inputArg), ctx);
            default -> throw new IllegalArgumentException("Invalid normalization form: " + form);
        };
    }
}

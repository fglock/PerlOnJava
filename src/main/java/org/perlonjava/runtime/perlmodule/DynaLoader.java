package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

public class DynaLoader extends PerlModuleBase {

    public DynaLoader() {
        super("DynaLoader");
    }

    public static void initialize() {
        DynaLoader dynaLoader = new DynaLoader();
        dynaLoader.initializeExporter();
        dynaLoader.defineExport("EXPORT", "bootstrap");
        // Set $DynaLoader::VERSION so CPAN dependency checks are satisfied
        GlobalVariable.getGlobalVariable("DynaLoader::VERSION").set("1.54");
        try {
            dynaLoader.registerMethod("bootstrap", null);
            dynaLoader.registerMethod("boot_DynaLoader", null);

            // PerlOnJava has no shared-library loading support. Some CPAN
            // Makefile.PL files (e.g. Geo::IP's) probe for native C libraries
            // via DynaLoader::dl_findfile/dl_load_file/dl_find_symbol to
            // decide between XS and pure-Perl (PP) code paths. We register
            // "not found" stubs so those probes succeed (returning empty/undef)
            // and the modules fall through to their pure-Perl implementations.
            dynaLoader.registerMethod("dl_findfile", "dl_empty", null);
            dynaLoader.registerMethod("dl_load_file", "dl_empty", null);
            dynaLoader.registerMethod("dl_find_symbol", "dl_empty", null);
            dynaLoader.registerMethod("dl_find_symbol_anywhere", "dl_empty", null);
            dynaLoader.registerMethod("dl_install_xsub", "dl_empty", null);
            dynaLoader.registerMethod("dl_undef_symbols", "dl_empty", null);
            dynaLoader.registerMethod("dl_error", "dl_error", null);

            // Set $DynaLoader::VERSION so CPAN dependency checking works
            GlobalVariable.getGlobalVariable("DynaLoader::VERSION").set("1.56");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing DynaLoader method: " + e.getMessage());
        }
    }

    public static RuntimeList bootstrap(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            return WarnDie.die(
                    new RuntimeScalar("Usage: DynaLoader::bootstrap(module)"),
                    new RuntimeScalar("\n")
            ).getList();
        }

        // Delegate to XSLoader::load() which has a multi-stage fallback:
        //   1. Java XS class found       → initialize and return true
        //   2. @ISA has functional parent → return true (inheritance fallback)
        //   3. ::PP companion loaded      → return true
        //   4. die with "Can't load loadable object..." (matches /loadable object/
        //      pattern that CPAN modules use to detect XS failure and fall back)
        //
        // This makes DynaLoader-based modules (Image::Magick, Tk, etc.)
        // behave the same as XSLoader-based modules in PerlOnJava.
        return XSLoader.load(args, ctx);
    }

    public static RuntimeList boot_DynaLoader(RuntimeArray args, int ctx) {
        return new RuntimeList();
    }

    /**
     * No-op stub used for all DynaLoader dl_* probe functions. Returns an
     * empty list (undef in scalar context). See initialize() for why.
     */
    public static RuntimeList dl_empty(RuntimeArray args, int ctx) {
        return new RuntimeList();
    }

    public static RuntimeList dl_error(RuntimeArray args, int ctx) {
        return new RuntimeScalar("DynaLoader is not supported in PerlOnJava").getList();
    }
}

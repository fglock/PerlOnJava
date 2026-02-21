package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

/**
 * The Re class provides functionalities similar to the Perl re module.
 */
public class Re extends PerlModuleBase {

    /**
     * Constructor initializes the module.
     */
    public Re() {
        super("re");
    }

    /**
     * Static initializer to set up the module.
     */
    public static void initialize() {
        Re re = new Re();
        try {
            re.registerMethod("is_regexp", "isRegexp", "$");
            re.registerMethod("import", "importRe", null);
            re.registerMethod("unimport", "unimportRe", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing re method: " + e.getMessage());
        }
    }

    /**
     * Method to check if the given argument is a regular expression.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return Empty list
     */
    public static RuntimeList isRegexp(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for isRegexp() method");
        }
        return new RuntimeList(
                new RuntimeScalar(args.get(0).type == RuntimeScalarType.REGEX)
        );
    }

    /**
     * Handle `use re ...` import. Recognizes: 'strict'.
     * Enables appropriate experimental warning categories so our regex preprocessor can emit them.
     */
    public static RuntimeList importRe(RuntimeArray args, int ctx) {
        for (int i = 0; i < args.size(); i++) {
            String opt = args.get(i).toString();
            // Normalize quotes if present
            opt = opt.replace("\"", "").replace("'", "").trim();
            if (opt.equalsIgnoreCase("strict")) {
                // Enable categories used by our preprocessor warnings
                Warnings.warningManager.enableWarning("experimental::re_strict");
                Warnings.warningManager.enableWarning("experimental::uniprop_wildcards");
                Warnings.warningManager.enableWarning("experimental::vlb");
            }
        }
        return new RuntimeList();
    }

    /**
     * Handle `no re ...` unimport. Recognizes: 'strict'.
     */
    public static RuntimeList unimportRe(RuntimeArray args, int ctx) {
        for (int i = 0; i < args.size(); i++) {
            String opt = args.get(i).toString();
            opt = opt.replace("\"", "").replace("'", "").trim();
            if (opt.equalsIgnoreCase("strict")) {
                Warnings.warningManager.disableWarning("experimental::re_strict");
                Warnings.warningManager.disableWarning("experimental::uniprop_wildcards");
                Warnings.warningManager.disableWarning("experimental::vlb");
            }
        }
        return new RuntimeList();
    }
}

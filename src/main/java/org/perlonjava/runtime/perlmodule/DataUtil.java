package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

/**
 * XS-compatible primitive scalar classification for Data::Util.
 *
 * <p>The distribution's pure-Perl fallback checks {@code ref(\$_[0])} to
 * distinguish globs.  When the argument aliases a missing array element, that
 * reference intentionally vivifies the slot in standard Perl.  The XS backend
 * inspects the SV flags without vivification; these methods provide the same
 * behavior for PerlOnJava.</p>
 */
public final class DataUtil extends PerlModuleBase {
    public static final String XS_VERSION = "0.67";

    private DataUtil() {
        super("Data::Util", false);
    }

    public static void initialize() {
        DataUtil module = new DataUtil();
        try {
            module.registerMethod("is_value", null);
            module.registerMethod("is_string", null);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }

        // Install the distribution's reusable pure-Perl implementation for
        // the remaining API, then let the shim restore these XS-like methods.
        XSLoader.loadJarShimOverrides("Data::Util");
    }

    public static RuntimeList is_value(RuntimeArray args, int ctx) {
        RuntimeScalar value = args.get(0);
        boolean result = value != null
                && value.type != RuntimeScalarType.UNDEF
                && value.type != RuntimeScalarType.GLOB
                && !RuntimeScalarType.isReference(value);
        return new RuntimeScalar(result).getList();
    }

    public static RuntimeList is_string(RuntimeArray args, int ctx) {
        RuntimeScalar value = args.get(0);
        boolean result = value != null && switch (value.type) {
            case RuntimeScalarType.STRING, RuntimeScalarType.BYTE_STRING,
                    RuntimeScalarType.VSTRING -> !value.toString().isEmpty();
            default -> false;
        };
        return new RuntimeScalar(result).getList();
    }
}

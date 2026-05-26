package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.util.HashSet;
import java.util.Set;

/**
 * The Lib class provides functionalities similar to the Perl lib module.
 * It allows manipulation of a search path list, similar to Perl's @INC.
 */
public class Lib extends PerlModuleBase {

    private static RuntimeArray ORIG_INC = null;

    /**
     * Resets static state for test isolation.
     * Called from GlobalVariable.resetAllGlobals().
     */
    public static void resetState() {
        ORIG_INC = null;
    }

    /**
     * Constructor for Lib.
     * Initializes the module with the name "lib".
     */
    public Lib() {
        super("lib");
    }

    /**
     * Static initializer to set up the Lib module.
     */
    public static void initialize() {
        Lib lib = new Lib();
        // Set $VERSION so CPAN.pm can detect our bundled version
        GlobalVariable.getGlobalVariable("lib::VERSION").set(new RuntimeScalar("0.65"));
        lib.initializeExporter();
        lib.defineExport("EXPORT_OK", "useLib", "noLib", "restoreOrigInc");
        try {
            lib.registerMethod("import", "useLib", ";$");
            lib.registerMethod("unimport", "noLib", ";$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Lib method: " + e.getMessage());
        }
    }

    /**
     * Adds directories to the search path.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList useLib(RuntimeArray args, int ctx) {
        RuntimeArray INC = GlobalVariable.getGlobalArray("main::INC");
        initOrigInc(INC);
        // Process in reverse order and unshift, matching Perl's lib.pm behavior:
        // directories are prepended to @INC so they take precedence over existing paths
        for (int i = args.size() - 1; i >= 1; i--) {
            RuntimeScalar entry = args.get(i);
            if (isIncHook(entry)) {
                RuntimeArray.unshift(INC, entry);
            } else {
                String dir = entry.toString();
                // Remove any existing occurrence first (dedup), then prepend.
                // Only compare non-hook entries; @INC hooks may stringify like
                // CODE(0x...), but must remain callable references.
                INC.elements.removeIf(path -> !isIncHook(path) && path.toString().equals(dir));
                RuntimeArray.unshift(INC, new RuntimeScalar(dir));
            }
        }
        return new RuntimeList();
    }

    private static boolean isIncHook(RuntimeScalar scalar) {
        if (scalar == null) {
            return false;
        }
        if (scalar.type == RuntimeScalarType.TIED_SCALAR) {
            scalar = scalar.tiedFetch();
        }
        return RuntimeScalarType.isReference(scalar);
    }

    private static void initOrigInc(RuntimeArray INC) {
        if (ORIG_INC == null) {
            ORIG_INC = GlobalVariable.getGlobalArray("lib::ORIG_INC");
            for (RuntimeScalar elem : INC.elements) {
                RuntimeArray.push(ORIG_INC, elem);
            }
        }
    }

    /**
     * Removes directories from the search path.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList noLib(RuntimeArray args, int ctx) {
        RuntimeArray INC = GlobalVariable.getGlobalArray("main::INC");
        initOrigInc(INC);
        for (int i = 1; i < args.size(); i++) {
            RuntimeScalar entry = args.get(i);
            if (isIncHook(entry)) {
                INC.elements.removeIf(path -> sameIncHook(path, entry));
            } else {
                String dir = entry.toString();
                INC.elements.removeIf(path -> !isIncHook(path) && path.toString().equals(dir));
            }
        }
        return new RuntimeScalar().getList();
    }

    private static boolean sameIncHook(RuntimeScalar left, RuntimeScalar right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.type == RuntimeScalarType.TIED_SCALAR) {
            left = left.tiedFetch();
        }
        if (right.type == RuntimeScalarType.TIED_SCALAR) {
            right = right.tiedFetch();
        }
        return left.type == right.type && left.value == right.value;
    }

    /**
     * Removes trailing duplicate entries in the search path.
     *
     * @param inc The RuntimeArray representing the search path.
     */
    private static void removeTrailingDuplicates(RuntimeArray inc) {
        Set<String> seen = new HashSet<>();
        RuntimeArray unique = new RuntimeArray();
        for (int i = 0; i < inc.size(); i++) {
            String path = inc.get(i).toString();
            if (seen.add(path)) {
                RuntimeArray.push(unique, new RuntimeScalar(path));
            }
        }
        inc.setFromList(unique.getList());
    }

    /**
     * Checks if a RuntimeArray contains a specific string.
     *
     * @param array The RuntimeArray to check.
     * @param value The string value to look for.
     * @return True if the array contains the value, false otherwise.
     */
    private static boolean contains(RuntimeArray array, String value) {
        for (int i = 0; i < array.size(); i++) {
            if (array.get(i).toString().equals(value)) {
                return true;
            }
        }
        return false;
    }
}

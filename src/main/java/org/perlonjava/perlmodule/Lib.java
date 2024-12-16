package org.perlonjava.perlmodule;

import org.perlonjava.runtime.GlobalVariable;
import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.util.HashSet;
import java.util.Set;

/**
 * The Lib class provides functionalities similar to the Perl lib module.
 * It allows manipulation of a search path list, similar to Perl's @INC.
 */
public class Lib extends PerlModuleBase {

    private static RuntimeArray ORIG_INC = null;

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
        for (int i = 1; i < args.size(); i++) {
            String dir = args.get(i).toString();
            if (!contains(INC, dir)) {
                RuntimeArray.push(INC, new RuntimeScalar(dir));
            }
        }
        return new RuntimeList();
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
            String dir = args.get(i).toString();
            INC.elements.removeIf(path -> path.toString().equals(dir));
        }
        return new RuntimeScalar().getList();
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

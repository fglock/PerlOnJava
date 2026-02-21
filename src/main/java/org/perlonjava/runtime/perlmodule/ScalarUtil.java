package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;

/**
 * Utility class for Scalar operations in Perl.
 * Extends PerlModuleBase to leverage module initialization and method registration.
 */
public class ScalarUtil extends PerlModuleBase {

    /**
     * Constructor for ScalarUtil.
     * Initializes the module with the name "Scalar::Util".
     */
    public ScalarUtil() {
        super("Scalar::Util");
    }

    /**
     * Static initializer to set up the Scalar::Util module.
     * This method initializes the exporter and defines the symbols that can be exported.
     */
    public static void initialize() {
        ScalarUtil scalarUtil = new ScalarUtil();
        scalarUtil.initializeExporter(); // Use the base class method to initialize the exporter
        scalarUtil.defineExport("EXPORT_OK", "blessed", "refaddr", "reftype", "weaken", "unweaken", "isweak",
                "dualvar", "isdual", "isvstring", "looks_like_number", "openhandle", "readonly",
                "set_prototype", "tainted");
        try {
            // Register methods with their respective signatures
            scalarUtil.registerMethod("blessed", "$");
            scalarUtil.registerMethod("refaddr", "$");
            scalarUtil.registerMethod("reftype", "$");
            scalarUtil.registerMethod("weaken", "$");
            scalarUtil.registerMethod("unweaken", "$");
            scalarUtil.registerMethod("isweak", "$");
            scalarUtil.registerMethod("dualvar", "$$");
            scalarUtil.registerMethod("isdual", "$");
            scalarUtil.registerMethod("isvstring", "$");
            scalarUtil.registerMethod("looks_like_number", "$");
            scalarUtil.registerMethod("openhandle", "$");
            scalarUtil.registerMethod("readonly", "$");
            scalarUtil.registerMethod("set_prototype", "$");
            scalarUtil.registerMethod("tainted", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Scalar::Util method: " + e.getMessage());
        }
    }

    /**
     * Checks if a scalar is blessed and returns the blessing information.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return If args is a blessed reference, the name of the package that it is blessed into is returned. Otherwise "undef" is returned.
     */
    public static RuntimeList blessed(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for blessed() method");
        }

        int blessId = blessedId(args.get(0));
        return new RuntimeScalar(NameNormalizer.getBlessStr(blessId)).getList();
    }

    /**
     * Returns the memory address of a reference.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing the memory address.
     */
    public static RuntimeList refaddr(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for refaddr() method");
        }
        RuntimeScalar scalar = args.get(0);
        return new RuntimeScalar(System.identityHashCode(scalar)).getList();
    }

    /**
     * Returns the type of reference.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing the reference type.
     */
    public static RuntimeList reftype(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for reftype() method");
        }
        RuntimeScalar scalar = args.get(0);
        String type = switch (scalar.type) {
            case REFERENCE -> "REF";
            case ARRAYREFERENCE -> "ARRAY";
            case HASHREFERENCE -> "HASH";
            case CODE -> "CODE";
            case GLOB -> "GLOB";
            case FORMAT -> "FORMAT";
            default -> "";
        };
        return new RuntimeScalar(type).getList();
    }

    /**
     * Placeholder for the weaken functionality.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList weaken(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for weaken() method");
        }
        // Placeholder for weaken functionality
        return new RuntimeScalar().getList();
    }

    /**
     * Placeholder for the unweaken functionality.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList unweaken(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for unweaken() method");
        }
        // Placeholder for unweaken functionality
        return new RuntimeScalar().getList();
    }

    /**
     * Placeholder for the isweak functionality.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList indicating if the reference is weak.
     */
    public static RuntimeList isweak(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for isweak() method");
        }
        // Placeholder for isweak functionality
        return new RuntimeScalar(false).getList();
    }

    /**
     * Dualvar functionality.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList dualvar(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for dualvar() method");
        }
        var scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.DUALVAR;
        scalar.value = new DualVar(args.get(0), args.get(1));
        return scalar.getList();
    }

    /**
     * Placeholder for the isdual functionality.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList indicating if the scalar is dual.
     */
    public static RuntimeList isdual(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for isdual() method");
        }
        return new RuntimeScalar(args.get(0).type == DUALVAR).getList();
    }

    /**
     * Placeholder for the isvstring functionality.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList indicating if the scalar is a vstring.
     */
    public static RuntimeList isvstring(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for isvstring() method");
        }
        // Placeholder for isvstring functionality
        return new RuntimeScalar(false).getList();
    }

    /**
     * Checks if a scalar looks like a number.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList indicating if the scalar looks like a number.
     */
    public static RuntimeList looks_like_number(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for looks_like_number() method");
        }
        RuntimeScalar scalar = args.get(0);
        boolean isNumber = scalar.type == RuntimeScalarType.INTEGER || scalar.type == RuntimeScalarType.DOUBLE;
        return new RuntimeScalar(isNumber).getList();
    }

    /**
     * Placeholder for the openhandle functionality.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList indicating if the scalar is an open handle.
     */
    public static RuntimeList openhandle(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for openhandle() method");
        }
        // Placeholder for openhandle functionality
        return new RuntimeScalar(false).getList();
    }

    /**
     * Placeholder for the readonly functionality.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList indicating if the scalar is readonly.
     */
    public static RuntimeList readonly(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for readonly() method");
        }
        // Placeholder for readonly functionality
        return new RuntimeScalar(false).getList();
    }

    /**
     * Sets the prototype for a subroutine.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList set_prototype(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for set_prototype() method");
        }

        RuntimeScalar scalar = args.get(0);
        RuntimeScalar prototypeScalar = args.get(1);

        if (scalar.type != CODE) {
            throw new IllegalArgumentException("First argument must be a CODE reference");
        }

        RuntimeCode runtimeCode = (RuntimeCode) scalar.value;

        runtimeCode.prototype = prototypeScalar.toString();

        return new RuntimeScalar().getList();
    }


    /**
     * Placeholder for the tainted functionality.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList indicating if the scalar is tainted.
     */
    public static RuntimeList tainted(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for tainted() method");
        }
        // Placeholder for tainted functionality
        return new RuntimeScalar(false).getList();
    }
}

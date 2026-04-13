package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.io.ClosedIOHandle;
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
        // Set $VERSION so CPAN.pm can detect our bundled version
        GlobalVariable.getGlobalVariable("Scalar::Util::VERSION").set(new RuntimeScalar("1.63"));
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
            scalarUtil.registerMethod("set_prototype", "$$");
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

        RuntimeScalar scalar = args.get(0);
        if (scalar.type == READONLY_SCALAR) scalar = (RuntimeScalar) scalar.value;
        int blessId = blessedId(scalar);
        // Return undef for unblessed references (blessId == 0)
        if (blessId == 0) {
            // In Perl, qr// objects are implicitly blessed into "Regexp"
            if (scalar.type == RuntimeScalarType.REGEX) {
                return new RuntimeScalar("Regexp").getList();
            }
            return new RuntimeScalar().getList();  // undef
        }
        return new RuntimeScalar(NameNormalizer.getBlessStr(blessId)).getList();
    }

    /**
     * Returns the memory address of a reference.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing the memory address, or undef if not a reference.
     */
    public static RuntimeList refaddr(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for refaddr() method");
        }
        RuntimeScalar scalar = args.get(0);
        if (scalar.type == READONLY_SCALAR) scalar = (RuntimeScalar) scalar.value;
        // refaddr returns undef for non-references
        // For references, return the identity hash code of the underlying referenced object
        switch (scalar.type) {
            case REFERENCE:
            case ARRAYREFERENCE:
            case HASHREFERENCE:
            case CODE:
            case GLOB:
            case GLOBREFERENCE:
            case FORMAT:
            case REGEX:
                // Return identity of the underlying value object
                return new RuntimeScalar(System.identityHashCode(scalar.value)).getList();
            default:
                // Return undef for non-references
                return new RuntimeScalar().getList();
        }
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
        if (scalar.type == READONLY_SCALAR) scalar = (RuntimeScalar) scalar.value;
        String type = switch (scalar.type) {
            case REFERENCE -> {
                // Inspect the referent to distinguish SCALAR refs from REF (ref-to-ref)
                if (scalar.value instanceof RuntimeScalar inner) {
                    if (inner.type == READONLY_SCALAR) inner = (RuntimeScalar) inner.value;
                    yield switch (inner.type) {
                        case VSTRING -> "VSTRING";
                        case REGEX, ARRAYREFERENCE, HASHREFERENCE, CODE, GLOBREFERENCE, REFERENCE -> "REF";
                        case GLOB -> "GLOB";
                        default -> "SCALAR";
                    };
                }
                yield "SCALAR";
            }
            case ARRAYREFERENCE -> "ARRAY";
            case HASHREFERENCE -> "HASH";
            case CODE -> "CODE";
            case GLOB, GLOBREFERENCE -> "GLOB";
            case FORMAT -> "FORMAT";
            case REGEX -> "REGEXP";
            default -> null;
        };
        return (type != null ? new RuntimeScalar(type) : new RuntimeScalar()).getList();
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
        RuntimeScalar ref = args.get(0);
        WeakRefRegistry.weaken(ref);
        return new RuntimeScalar().getList();
    }

    /**
     * Restore a weak reference to a strong reference.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList unweaken(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for unweaken() method");
        }
        RuntimeScalar ref = args.get(0);
        WeakRefRegistry.unweaken(ref);
        return new RuntimeScalar().getList();
    }

    /**
     * Check if a reference has been weakened via weaken().
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList indicating if the reference is weak.
     */
    public static RuntimeList isweak(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for isweak() method");
        }
        RuntimeScalar ref = args.get(0);
        return new RuntimeScalar(WeakRefRegistry.isweak(ref)).getList();
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
        RuntimeScalar s = args.get(0);
        if (s.type == READONLY_SCALAR) s = (RuntimeScalar) s.value;
        return new RuntimeScalar(s.type == DUALVAR).getList();
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
        RuntimeScalar s = args.get(0);
        if (s.type == READONLY_SCALAR) s = (RuntimeScalar) s.value;
        return new RuntimeScalar(s.type == VSTRING).getList();
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
        boolean isNumber = ScalarUtils.looksLikeNumber(args.get(0));
        return new RuntimeScalar(isNumber).getList();
    }

    /**
     * Checks if a value is an open filehandle.
     * Returns the filehandle itself if it's open, undef otherwise.
     *
     * @param args The arguments passed to the method (a single value).
     * @param ctx  The context in which the method is called.
     * @return The filehandle if open, undef otherwise.
     */
    public static RuntimeList openhandle(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for openhandle() method");
        }
        RuntimeScalar arg = args.get(0);
        if (arg.type == READONLY_SCALAR) arg = (RuntimeScalar) arg.value;
        
        // Check if it's a GLOB or GLOBREFERENCE (filehandle)
        if (arg.type == GLOB || arg.type == GLOBREFERENCE) {
            if (isOpenGlob(arg.value)) {
                return arg.getList();  // Return the filehandle itself
            }
        }
        
        // Check for blessed object with *{} overload
        // In Perl 5, openhandle() recognizes objects with *{} overloading
        // (e.g., File::Temp objects) as filehandles.
        int blessId = RuntimeScalarType.blessedId(arg);
        if (blessId < 0) {
            // Blessed object with overloading - try *{} dereference
            try {
                RuntimeGlob glob = arg.globDeref();
                if (glob != null) {
                    RuntimeScalar io = glob.getIO();
                    if (io != null && io.value instanceof RuntimeIO runtimeIO) {
                        if (!(runtimeIO.ioHandle instanceof ClosedIOHandle)) {
                            return arg.getList();  // Return the original object
                        }
                    }
                }
            } catch (Exception e) {
                // globDeref failed - not a glob-like object, fall through to return undef
            }
        }
        
        return new RuntimeScalar().getList();  // Return undef
    }
    
    /**
     * Helper to check if a glob/IO value represents an open filehandle.
     */
    private static boolean isOpenGlob(Object value) {
        if (value instanceof RuntimeGlob glob) {
            RuntimeScalar io = glob.getIO();
            if (io != null && io.value instanceof RuntimeIO runtimeIO) {
                return !(runtimeIO.ioHandle instanceof ClosedIOHandle);
            }
        } else if (value instanceof RuntimeIO runtimeIO) {
            return !(runtimeIO.ioHandle instanceof ClosedIOHandle);
        }
        return false;
    }

    /**
     * Check if a scalar is read-only.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList indicating if the scalar is readonly.
     */
    public static RuntimeList readonly(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for readonly() method");
        }
        RuntimeScalar arg = args.get(0);
        return new RuntimeScalar(arg instanceof RuntimeScalarReadOnly).getList();
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
        if (scalar.type == READONLY_SCALAR) scalar = (RuntimeScalar) scalar.value;
        RuntimeScalar prototypeScalar = args.get(1);

        if (scalar.type != CODE) {
            throw new IllegalArgumentException("First argument must be a CODE reference");
        }

        RuntimeCode runtimeCode = (RuntimeCode) scalar.value;

        // Set prototype to null if prototypeScalar is undef, otherwise use the string value
        runtimeCode.prototype = prototypeScalar.getDefinedBoolean() ? prototypeScalar.toString() : null;

        // Return the code reference (not undef)
        return scalar.getList();
    }


    /**
     * Checks if a scalar is tainted (contains data from external sources).
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList indicating if the scalar is tainted.
     */
    public static RuntimeList tainted(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for tainted() method");
        }
        return new RuntimeScalar(args.get(0).isTainted()).getList();
    }
}

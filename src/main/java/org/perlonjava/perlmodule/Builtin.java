package org.perlonjava.perlmodule;

import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarType;

import static org.perlonjava.runtime.RuntimeScalarCache.*;
import static org.perlonjava.runtime.RuntimeScalarType.*;

/**
 * The Builtin class provides functionalities similar to the Perl builtin module.
 */
public class Builtin extends PerlModuleBase {

    /**
     * Constructor initializes the module.
     */
    public Builtin() {
        super("builtin");
    }

    /**
     * Static initializer to set up the module.
     */
    public static void initialize() {
        Builtin builtin = new Builtin();

        // Initialize as an Exporter module
        builtin.initializeExporter();

        // Define EXPORT_OK array with all exportable functions
        builtin.defineExport("EXPORT_OK",
                "is_bool", "true", "false", "weaken", "unweaken", "is_weak",
                "blessed", "refaddr", "reftype", "ceil", "floor",
                "is_tainted", "trim", "indexed", "inf", "nan",
                "created_as_string", "created_as_number", "stringify"
                // TODO "export_lexically", "load_module"
        );

        // Define the :5.40 tag bundle
        builtin.defineExportTag("5.40",
                "true", "false", "weaken", "unweaken", "is_weak",
                "blessed", "refaddr", "reftype", "ceil", "floor",
                "is_tainted", "trim", "indexed");

        try {
            builtin.registerMethod("is_bool", "isBoolean", "$");
            builtin.registerMethod("true", "scalarTrue", "");
            builtin.registerMethod("false", "scalarFalse", "");
            builtin.registerMethod("inf", "scalarInf", "");
            builtin.registerMethod("nan", "scalarNan", "");
            builtin.registerMethod("weaken", "weaken", "$");
            builtin.registerMethod("unweaken", "unweaken", "$");
            builtin.registerMethod("is_weak", "isWeak", "$");
            builtin.registerMethod("blessed", "blessed", "$");
            builtin.registerMethod("refaddr", "refaddr", "$");
            builtin.registerMethod("reftype", "reftype", "$");
            builtin.registerMethod("ceil", "ceil", "$");
            builtin.registerMethod("floor", "floor", "$");
            builtin.registerMethod("trim", "trim", "$");
            builtin.registerMethod("is_tainted", "isTainted", "$");
            builtin.registerMethod("indexed", "indexed", "@");
            builtin.registerMethod("created_as_string", "createdAsString", "$");
            builtin.registerMethod("created_as_number", "createdAsNumber", "$");
            builtin.registerMethod("stringify", "stringify", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Internals method: " + e.getMessage());
        }
    }

    /**
     * Returns true when given a distinguished boolean value, or false if not.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return Empty list
     */
    public static RuntimeList isBoolean(RuntimeArray args, int ctx) {
        RuntimeScalar res = args.getFirst();
        return new RuntimeList(getScalarBoolean(res.type == RuntimeScalarType.BOOLEAN));
    }

    public static RuntimeList scalarTrue(RuntimeArray args, int ctx) {
        return new RuntimeList(scalarTrue);
    }

    public static RuntimeList scalarFalse(RuntimeArray args, int ctx) {
        return new RuntimeList(scalarFalse);
    }

    public static RuntimeList scalarInf(RuntimeArray args, int ctx) {
        return new RuntimeList(new RuntimeScalar(Double.POSITIVE_INFINITY));
    }

    public static RuntimeList scalarNan(RuntimeArray args, int ctx) {
        return new RuntimeList(new RuntimeScalar(Double.NaN));
    }

    public static RuntimeList weaken(RuntimeArray args, int ctx) {
        RuntimeScalar ref = args.get(0);
        // Implementation for reference weakening
        return new RuntimeList();
    }

    public static RuntimeList unweaken(RuntimeArray args, int ctx) {
        RuntimeScalar ref = args.get(0);
        // Implementation for reference strengthening
        return new RuntimeList();
    }

    public static RuntimeList isWeak(RuntimeArray args, int ctx) {
        RuntimeScalar ref = args.get(0);
        // Implementation to check if reference is weak
        return new RuntimeList(scalarFalse);
    }

    public static RuntimeList blessed(RuntimeArray args, int ctx) {
        return ScalarUtil.blessed(args, ctx);
    }

    public static RuntimeList refaddr(RuntimeArray args, int ctx) {
        RuntimeScalar ref = args.get(0);
        // Return memory address for reference
        RuntimeScalar result;
        switch (ref.type) {
            case REFERENCE, ARRAYREFERENCE, HASHREFERENCE, CODE, GLOB, GLOBREFERENCE:
                result = getScalarInt(System.identityHashCode(ref.value));
                break;
            default:
                result = scalarUndef;
                break;
        }
        return new RuntimeList(result);
    }

    public static RuntimeList reftype(RuntimeArray args, int ctx) {
        RuntimeScalar ref = args.get(0);
        
        // Check if this is a blessed object (class instance)
        // For Perl 5.38+ class syntax, blessed objects should return "OBJECT"
        int blessId = RuntimeScalarType.blessedId(ref);
        if (blessId != 0) {
            // This is a blessed object - return "OBJECT" for class instances
            // This is specifically for the new class syntax
            return new RuntimeList(new RuntimeScalar("OBJECT"));
        }
        
        // Return reference type in capitals
        String type = switch (ref.type) {
            case REFERENCE -> {
                if (ref.value instanceof RuntimeScalar scalar) {
                    yield switch (scalar.type) {
                        // case ARRAYREFERENCE -> "ARRAY";
                        // case HASHREFERENCE -> "HASH";
                        case CODE -> "CODE";
                        case GLOB, GLOBREFERENCE -> "GLOB";
                        default -> "SCALAR";
                    };
                }
                yield "REF";
            }
            case ARRAYREFERENCE -> "ARRAY";
            case HASHREFERENCE -> "HASH";
            case CODE -> "CODE";
            case GLOB, GLOBREFERENCE -> "GLOB";
            default -> null;
        };

        return new RuntimeList(type != null ? new RuntimeScalar(type) : scalarUndef);
    }

    public static RuntimeList ceil(RuntimeArray args, int ctx) {
        RuntimeScalar num = args.get(0);
        return new RuntimeList(new RuntimeScalar(Math.ceil(num.getDouble())));
    }

    public static RuntimeList floor(RuntimeArray args, int ctx) {
        RuntimeScalar num = args.get(0);
        return new RuntimeList(new RuntimeScalar(Math.floor(num.getDouble())));
    }

    public static RuntimeList trim(RuntimeArray args, int ctx) {
        RuntimeScalar str = args.get(0);
        return new RuntimeList(new RuntimeScalar(str.toString().trim()));
    }

    public static RuntimeList isTainted(RuntimeArray args, int ctx) {
        RuntimeScalar var = args.get(0);
        // Implementation for taint checking
        return new RuntimeList(scalarFalse);
    }

    public static RuntimeList indexed(RuntimeArray args, int ctx) {
        RuntimeList result = new RuntimeList();
        for (int i = 0; i < args.size(); i++) {
            result.add(new RuntimeScalar(i));
            result.add(args.get(i));
        }
        return result;
    }

    public static RuntimeList createdAsString(RuntimeArray args, int ctx) {
        RuntimeScalar scalar = args.get(0);
        return new RuntimeList(getScalarBoolean(scalar.isString()));
    }

    public static RuntimeList createdAsNumber(RuntimeArray args, int ctx) {
        RuntimeScalar scalar = args.get(0);
        return new RuntimeList(getScalarBoolean(scalar.type == RuntimeScalarType.INTEGER || scalar.type == RuntimeScalarType.DOUBLE));
    }

    public static RuntimeList stringify(RuntimeArray args, int ctx) {
        RuntimeScalar scalar = args.get(0);
        return new RuntimeList(new RuntimeScalar(scalar.toString()));
    }
}

package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

/**
 * The Strict class provides functionalities similar to the Perl strict module.
 */
public class Internals extends PerlModuleBase {

    /**
     * Constructor initializes the module.
     */
    public Internals() {
        super("Internals");
    }

    /**
     * Static initializer to set up the module.
     */
    public static void initialize() {
        Internals internals = new Internals();
        try {
            internals.registerMethod("SvREADONLY", "svReadonly", "\\[$@%];$");
            internals.registerMethod("SvREFCNT", "svRefcount", "$;$");
            internals.registerMethod("dump_value", "dumpValue", "$;$");
            internals.registerMethod("initialize_state_variable", "initializeStateVariable", "$$");
            internals.registerMethod("initialize_state_array", "initializeStateArray", "$$");
            internals.registerMethod("initialize_state_hash", "initializeStateHash", "$$");
            internals.registerMethod("is_initialized_state_variable", "isInitializedStateVariable", "$$");
            internals.registerMethod("stack_refcounted", null);
            internals.registerMethod("V", "V", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Internals method: " + e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "<null>";
        }
        if (max < 0 || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
    }

    private static void dumpArray(RuntimeArray a, String indent, int maxElems) {
        if (a == null) {
            System.err.println(indent + "array: <null>");
            return;
        }
        try {
            System.err.println(indent + "array.class=" + a.getClass().getName());
            System.err.println(indent + "array.hash=" + a.hashCode());
            System.err.println(indent + "array.size=" + a.size());
        } catch (Throwable t) {
            System.err.println(indent + "array=<error " + t + ">");
            return;
        }

        try {
            int size = a.size();
            int limit = Math.min(size, Math.max(0, maxElems));
            for (int i = 0; i < limit; i++) {
                RuntimeScalar elem = a.elements.get(i);
                if (elem == null) {
                    System.err.println(indent + "array[" + i + "]=<null>");
                } else {
                    String es;
                    try {
                        es = elem.toString();
                    } catch (Throwable t) {
                        es = "<error " + t + ">";
                    }
                    System.err.println(indent + "array[" + i + "].class=" + elem.getClass().getName());
                    System.err.println(indent + "array[" + i + "].type=" + elem.type);
                    System.err.println(indent + "array[" + i + "].toString=\"" + truncate(es, 200) + "\"");
                }
            }
            if (size > limit) {
                System.err.println(indent + "array.elements=(truncated)");
            }
        } catch (Throwable t) {
            System.err.println(indent + "array.elements=<error " + t + ">");
        }
    }

    private static void dumpScalar(RuntimeScalar s, String indent, int depth) {
        if (s == null) {
            System.err.println(indent + "scalar: <null>");
            return;
        }

        System.err.println(indent + "scalar.class=" + s.getClass().getName());
        System.err.println(indent + "scalar.hash=" + s.hashCode());
        System.err.println(indent + "scalar.type=" + s.type);
        System.err.println(indent + "scalar.blessId=" + s.blessId);
        try {
            System.err.println(indent + "scalar.defined=" + s.getDefinedBoolean());
        } catch (Throwable t) {
            System.err.println(indent + "scalar.defined=<error " + t + ">");
        }
        try {
            System.err.println(indent + "scalar.truthy=" + s.getBoolean());
        } catch (Throwable t) {
            System.err.println(indent + "scalar.truthy=<error " + t + ">");
        }
        try {
            String sv = s.toString();
            System.err.println(indent + "scalar.toString.len=" + sv.length());
            System.err.println(indent + "scalar.toString=\"" + truncate(sv, 200) + "\"");
        } catch (Throwable t) {
            System.err.println(indent + "scalar.toString=<error " + t + ">");
        }
        try {
            System.err.println(indent + "scalar.toStringRef=" + s.toStringRef());
        } catch (Throwable t) {
            System.err.println(indent + "scalar.toStringRef=<error " + t + ">");
        }
        try {
            System.err.println(indent + "scalar.value.class=" + (s.value != null ? s.value.getClass().getName() : "<null>"));
        } catch (Throwable t) {
            System.err.println(indent + "scalar.value.class=<error " + t + ">");
        }

        if (depth <= 0) {
            return;
        }

        if (s.type == RuntimeScalarType.REFERENCE && s.value instanceof RuntimeScalar ref) {
            System.err.println(indent + "scalar.ref ->");
            dumpScalar(ref, indent + "  ", depth - 1);
        } else if (s.type == RuntimeScalarType.HASHREFERENCE && s.value instanceof RuntimeHash h) {
            try {
                System.err.println(indent + "scalar.hashref.size=" + h.size());
                System.err.println(indent + "scalar.hashref.hasKey(DataPt)=" + h.containsKey("DataPt"));
                int printed = 0;
                for (String k : h.elements.keySet()) {
                    if (printed++ >= 20) {
                        System.err.println(indent + "scalar.hashref.keys=(truncated)");
                        break;
                    }
                    System.err.println(indent + "scalar.hashref.key=" + k);
                }
            } catch (Throwable t) {
                System.err.println(indent + "scalar.hashref=<error " + t + ">");
            }
        } else if (s.type == RuntimeScalarType.ARRAYREFERENCE && s.value instanceof RuntimeArray a) {
            try {
                System.err.println(indent + "scalar.arrayref.size=" + a.size());
                dumpArray(a, indent + "scalar.arrayref.", 30);
            } catch (Throwable t) {
                System.err.println(indent + "scalar.arrayref=<error " + t + ">");
            }
        }
    }

    public static RuntimeList dumpValue(RuntimeArray args, int ctx) {
        RuntimeBase value = args.size() >= 1 ? args.get(0) : RuntimeScalarCache.scalarUndef;
        String label = args.size() >= 2 ? args.get(1).toString() : "";

        String caller = "";
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            if (st.length >= 2) {
                caller = st[1].getClassName() + "." + st[1].getMethodName() + ":" + st[1].getLineNumber();
            }
        } catch (Throwable ignored) {
        }

        System.err.println("[Internals::dump_value] " + (label.isEmpty() ? "" : (label + " ")) + "ctx=" + ctx + (caller.isEmpty() ? "" : (" caller=" + caller)));
        if (value == null) {
            System.err.println("[Internals::dump_value] <null RuntimeBase>");
            return new RuntimeList();
        }

        System.err.println("[Internals::dump_value] value.class=" + value.getClass().getName());
        System.err.println("[Internals::dump_value] value.hash=" + value.hashCode());
        try {
            System.err.println("[Internals::dump_value] value.truthy=" + value.getBoolean());
        } catch (Throwable t) {
            System.err.println("[Internals::dump_value] value.truthy=<error " + t + ">");
        }
        try {
            System.err.println("[Internals::dump_value] value.defined=" + value.getDefinedBoolean());
        } catch (Throwable t) {
            System.err.println("[Internals::dump_value] value.defined=<error " + t + ">");
        }
        try {
            String vs = value.toString();
            System.err.println("[Internals::dump_value] value.toString.len=" + vs.length());
            System.err.println("[Internals::dump_value] value.toString=\"" + truncate(vs, 200) + "\"");
        } catch (Throwable t) {
            System.err.println("[Internals::dump_value] value.toString=<error " + t + ">");
        }

        if (value instanceof RuntimeScalar scalar) {
            dumpScalar(scalar, "[Internals::dump_value] ", 2);
        } else if (value instanceof RuntimeHash hash) {
            try {
                System.err.println("[Internals::dump_value] hash.size=" + hash.size());
                System.err.println("[Internals::dump_value] hash.hasKey(DataPt)=" + hash.containsKey("DataPt"));
                int printed = 0;
                for (String k : hash.elements.keySet()) {
                    if (printed++ >= 20) {
                        System.err.println("[Internals::dump_value] hash.keys=(truncated)");
                        break;
                    }
                    System.err.println("[Internals::dump_value] hash.key=" + k);
                }
            } catch (Throwable t) {
                System.err.println("[Internals::dump_value] hash=<error " + t + ">");
            }
        } else if (value instanceof RuntimeArray array) {
            dumpArray(array, "[Internals::dump_value] ", 30);
        }

        return new RuntimeList();
    }

    public static RuntimeList stack_refcounted(RuntimeArray args, int ctx) {

        // XXX TODO placeholder

        return new RuntimeList();
    }

    /**
     * No-op, returns false.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return Empty list
     */
    public static RuntimeList V(RuntimeArray args, int ctx) {

        // XXX TODO

        return new RuntimeList();
    }

    /**
     * No-op, returns false.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return Empty list
     */
    public static RuntimeList svRefcount(RuntimeArray args, int ctx) {

        // XXX TODO rewrite this to emit a RuntimeScalarReadOnly
        // It needs to happen at the emitter, because the variable container needs to be replaced.

        return new RuntimeList();
    }
    
    /**
     * Sets or gets the read-only status of a variable.
     *
     * @param args The arguments passed to the method: variable, optional flag to set readonly
     * @param ctx  The context in which the method is called.
     * @return Empty list
     */
    public static RuntimeList svReadonly(RuntimeArray args, int ctx) {
        if (args.size() >= 2) {
            RuntimeBase variable = args.get(0);
            RuntimeBase flag = args.get(1);

            // If flag is true (non-zero), make the variable readonly
            if (flag.getBoolean()) {
                if (variable instanceof RuntimeArray array) {
                    array.type = RuntimeArray.READONLY_ARRAY;
                } else if (variable instanceof RuntimeScalar scalar) {
                    // Check if it's a scalar reference (from \$var)
                    if (scalar.type == RuntimeScalarType.REFERENCE && scalar.value instanceof RuntimeScalar targetScalar) {
                        // Replace the target scalar with a readonly version
                        RuntimeScalarReadOnly readonlyScalar;
                        if (targetScalar.type == RuntimeScalarType.INTEGER) {
                            readonlyScalar = new RuntimeScalarReadOnly(targetScalar.getInt());
                        } else if (targetScalar.type == RuntimeScalarType.BOOLEAN) {
                            readonlyScalar = new RuntimeScalarReadOnly(targetScalar.getBoolean());
                        } else if (targetScalar.type == RuntimeScalarType.STRING) {
                            readonlyScalar = new RuntimeScalarReadOnly(targetScalar.toString());
                        } else {
                            readonlyScalar = new RuntimeScalarReadOnly(); // undef
                        }
                        // Copy readonly value into the actual target scalar (replace variable contents)
                        targetScalar.type = readonlyScalar.type;
                        targetScalar.value = readonlyScalar.value;
                    }
                }
            }
        }
        return new RuntimeList();
    }

    /**
     * Initialize a state variable exactly once
     *
     * @param args Args: variable name with sigil; persistent variable id; value to initialize.
     * @param ctx  The context in which the method is called.
     * @return Empty list
     */
    public static RuntimeList initializeStateVariable(RuntimeArray args, int ctx) {
        StateVariable.initializeStateVariable(
                args.get(0),
                args.get(1).toString(),
                args.get(2).getInt(),
                args.get(3));
        return new RuntimeList();
    }

    public static RuntimeList initializeStateArray(RuntimeArray args, int ctx) {
        StateVariable.initializeStateArray(
                RuntimeArray.shift(args),
                RuntimeArray.shift(args).toString(),
                RuntimeArray.shift(args).getInt(),
                args);
        return new RuntimeList();
    }

    public static RuntimeList initializeStateHash(RuntimeArray args, int ctx) {
        StateVariable.initializeStateHash(
                RuntimeArray.shift(args),
                RuntimeArray.shift(args).toString(),
                RuntimeArray.shift(args).getInt(),
                args);
        return new RuntimeList();
    }

    /**
     * Check is a state variable was initialized
     *
     * @param args Args: variable name with sigil; persistent variable id.
     * @param ctx  The context in which the method is called.
     * @return RuntimeScalar with true or false.
     */
    public static RuntimeList isInitializedStateVariable(RuntimeArray args, int ctx) {
        RuntimeScalar var = StateVariable.isInitializedStateVariable(
                args.get(0),
                args.get(1).toString(),
                args.get(2).getInt());
        return var.getList();
    }
}

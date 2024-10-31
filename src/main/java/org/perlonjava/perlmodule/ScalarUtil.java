package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

import java.lang.reflect.Method;

import static org.perlonjava.runtime.GlobalContext.getGlobalCodeRef;
import static org.perlonjava.runtime.GlobalContext.getGlobalHash;

public class ScalarUtil {

    public static void initialize() {
        // Initialize Scalar::Util class

        // Set %INC to indicate the module is loaded
        getGlobalHash("main::INC").put("Scalar/Util.pm", new RuntimeScalar("Scalar/Util.pm"));

        // Define @EXPORT_OK array
        RuntimeArray exportOk = GlobalContext.getGlobalArray("Scalar::Util::EXPORT_OK");
        exportOk.push(new RuntimeScalar("blessed"));
        exportOk.push(new RuntimeScalar("refaddr"));
        exportOk.push(new RuntimeScalar("reftype"));
        exportOk.push(new RuntimeScalar("weaken"));
        exportOk.push(new RuntimeScalar("unweaken"));
        exportOk.push(new RuntimeScalar("isweak"));
        exportOk.push(new RuntimeScalar("dualvar"));
        exportOk.push(new RuntimeScalar("isdual"));
        exportOk.push(new RuntimeScalar("isvstring"));
        exportOk.push(new RuntimeScalar("looks_like_number"));
        exportOk.push(new RuntimeScalar("openhandle"));
        exportOk.push(new RuntimeScalar("readonly"));
        exportOk.push(new RuntimeScalar("set_prototype"));
        exportOk.push(new RuntimeScalar("tainted"));

        try {
            // Load Scalar::Util methods into Perl namespace
            Class<?> clazz = ScalarUtil.class;
            RuntimeScalar instance = new RuntimeScalar();
            Method mm;

            mm = clazz.getMethod("importSymbols", RuntimeArray.class, int.class);
            getGlobalCodeRef("Scalar::Util::import").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, "*")));

            mm = clazz.getMethod("blessed", RuntimeArray.class, int.class);
            getGlobalCodeRef("Scalar::Util::blessed").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, "$")));

            mm = clazz.getMethod("refaddr", RuntimeArray.class, int.class);
            getGlobalCodeRef("Scalar::Util::refaddr").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, "$")));

            mm = clazz.getMethod("reftype", RuntimeArray.class, int.class);
            getGlobalCodeRef("Scalar::Util::reftype").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, "$")));

            mm = clazz.getMethod("weaken", RuntimeArray.class, int.class);
            getGlobalCodeRef("Scalar::Util::weaken").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, "$")));

            mm = clazz.getMethod("unweaken", RuntimeArray.class, int.class);
            getGlobalCodeRef("Scalar::Util::unweaken").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, "$")));

            mm = clazz.getMethod("isweak", RuntimeArray.class, int.class);
            getGlobalCodeRef("Scalar::Util::isweak").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, "$")));

            mm = clazz.getMethod("dualvar", RuntimeArray.class, int.class);
            getGlobalCodeRef("Scalar::Util::dualvar").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, "$$")));

            mm = clazz.getMethod("isdual", RuntimeArray.class, int.class);
            getGlobalCodeRef("Scalar::Util::isdual").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, "$")));

            mm = clazz.getMethod("isvstring", RuntimeArray.class, int.class);
            getGlobalCodeRef("Scalar::Util::isvstring").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, "$")));

            mm = clazz.getMethod("looks_like_number", RuntimeArray.class, int.class);
            getGlobalCodeRef("Scalar::Util::looks_like_number").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, "$")));

            mm = clazz.getMethod("openhandle", RuntimeArray.class, int.class);
            getGlobalCodeRef("Scalar::Util::openhandle").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, "$")));

            mm = clazz.getMethod("readonly", RuntimeArray.class, int.class);
            getGlobalCodeRef("Scalar::Util::readonly").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, "$")));

            mm = clazz.getMethod("set_prototype", RuntimeArray.class, int.class);
            getGlobalCodeRef("Scalar::Util::set_prototype").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, "$$")));

            mm = clazz.getMethod("tainted", RuntimeArray.class, int.class);
            getGlobalCodeRef("Scalar::Util::tainted").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, "$")));

        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Scalar::Util method: " + e.getMessage());
        }
    }

    public static RuntimeList importSymbols(RuntimeArray args, int ctx) {
        // Use the Exporter class to import symbols
        return Exporter.importSymbols(args, ctx);
    }

    public static RuntimeList blessed(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for blessed() method");
        }
        RuntimeScalar scalar = args.get(0);
        System.out.println("ScalarUtil.blessed: " + scalar + " : " + scalar.blessId);
        return scalar.blessed().getList();
    }

    public static RuntimeList refaddr(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for refaddr() method");
        }
        RuntimeScalar scalar = args.get(0);
        return new RuntimeScalar(System.identityHashCode(scalar)).getList();
    }

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
            default -> "";
        };
        return new RuntimeScalar(type).getList();
    }

    public static RuntimeList weaken(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for weaken() method");
        }
        // Placeholder for weaken functionality
        return new RuntimeScalar().getList();
    }

    public static RuntimeList unweaken(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for unweaken() method");
        }
        // Placeholder for unweaken functionality
        return new RuntimeScalar().getList();
    }

    public static RuntimeList isweak(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for isweak() method");
        }
        // Placeholder for isweak functionality
        return new RuntimeScalar(false).getList();
    }

    public static RuntimeList dualvar(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for dualvar() method");
        }
        // Placeholder for dualvar functionality
        return new RuntimeScalar().getList();
    }

    public static RuntimeList isdual(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for isdual() method");
        }
        // Placeholder for isdual functionality
        return new RuntimeScalar(false).getList();
    }

    public static RuntimeList isvstring(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for isvstring() method");
        }
        // Placeholder for isvstring functionality
        return new RuntimeScalar(false).getList();
    }

    public static RuntimeList looks_like_number(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for looks_like_number() method");
        }
        RuntimeScalar scalar = args.get(0);
        boolean isNumber = scalar.type == RuntimeScalarType.INTEGER || scalar.type == RuntimeScalarType.DOUBLE;
        return new RuntimeScalar(isNumber).getList();
    }

    public static RuntimeList openhandle(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for openhandle() method");
        }
        // Placeholder for openhandle functionality
        return new RuntimeScalar(false).getList();
    }

    public static RuntimeList readonly(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for readonly() method");
        }
        // Placeholder for readonly functionality
        return new RuntimeScalar(false).getList();
    }

    public static RuntimeList set_prototype(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for set_prototype() method");
        }
        // Placeholder for set_prototype functionality
        return new RuntimeScalar().getList();
    }

    public static RuntimeList tainted(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for tainted() method");
        }
        // Placeholder for tainted functionality
        return new RuntimeScalar(false).getList();
    }
}

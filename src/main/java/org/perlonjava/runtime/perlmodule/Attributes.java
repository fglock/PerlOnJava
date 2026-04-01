package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;

/**
 * Java backend for the Perl {@code attributes} pragma.
 *
 * <p>Provides the XS-equivalent functions that {@code attributes.pm} calls:
 * {@code _modify_attrs}, {@code _fetch_attrs}, {@code _guess_stash}, and {@code reftype}.
 *
 * <p>The Perl-level logic ({@code import}, {@code get}, warnings) lives in
 * {@code src/main/perl/lib/attributes.pm}.
 */
public class Attributes extends PerlModuleBase {

    public Attributes() {
        // Don't set %INC — the Perl attributes.pm file handles that
        super("attributes", false);
    }

    /**
     * Register the XS-equivalent functions in the {@code attributes::} namespace.
     * Called eagerly from GlobalContext so functions are available when attributes.pm loads.
     */
    public static void initialize() {
        Attributes attrs = new Attributes();
        try {
            attrs.registerMethod("_modify_attrs", "modifyAttrs", null);
            attrs.registerMethod("_fetch_attrs", "fetchAttrs", null);
            attrs.registerMethod("_guess_stash", "guessStash", null);
            attrs.registerMethod("reftype", "reftype", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing attributes method: " + e.getMessage());
        }
    }

    /**
     * {@code _modify_attrs($svref, @attrs)} — Apply built-in attributes.
     *
     * <p>For CODE refs: recognizes {@code lvalue}, {@code method}, {@code const},
     * {@code prototype(...)}, and {@code -attr} removal. Applies them to the
     * {@link RuntimeCode#attributes} list.
     *
     * <p>For SCALAR/ARRAY/HASH refs: recognizes {@code shared} (no-op in PerlOnJava).
     *
     * @return A list of unrecognized attributes (those not built-in).
     */
    public static RuntimeList modifyAttrs(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            return new RuntimeList();
        }
        RuntimeScalar svref = args.get(0);
        String svtype = getRefType(svref);

        List<String> unrecognized = new ArrayList<>();

        for (int i = 1; i < args.size(); i++) {
            String attr = args.get(i).toString();
            if (!applyBuiltinAttribute(svref, svtype, attr)) {
                unrecognized.add(attr);
            }
        }

        RuntimeArray result = new RuntimeArray();
        for (String attr : unrecognized) {
            RuntimeArray.push(result, new RuntimeScalar(attr));
        }
        return result.getList();
    }

    /**
     * Try to apply a single built-in attribute.
     *
     * @return {@code true} if the attribute was recognized (built-in), {@code false} otherwise.
     */
    private static boolean applyBuiltinAttribute(RuntimeScalar svref, String svtype, String attr) {
        boolean negate = attr.startsWith("-");
        String attrName = negate ? attr.substring(1) : attr;

        if ("CODE".equals(svtype)) {
            return applyCodeAttribute(svref, attrName, negate);
        } else if ("SCALAR".equals(svtype) || "ARRAY".equals(svtype) || "HASH".equals(svtype)) {
            return applyVariableAttribute(attrName, negate);
        }
        return false;
    }

    /**
     * Apply a built-in CODE attribute.
     */
    private static boolean applyCodeAttribute(RuntimeScalar svref, String attrName, boolean negate) {
        // Handle prototype(...)
        if (attrName.startsWith("prototype(") && attrName.endsWith(")")) {
            if (svref.type == CODE) {
                RuntimeCode code = (RuntimeCode) svref.value;
                String proto = attrName.substring(10, attrName.length() - 1);
                code.prototype = negate ? null : proto;
            }
            return true;
        }

        switch (attrName) {
            case "lvalue":
            case "method":
            case "const":
                if (svref.type == CODE) {
                    RuntimeCode code = (RuntimeCode) svref.value;
                    if (code.attributes == null) {
                        code.attributes = new ArrayList<>();
                    }
                    if (negate) {
                        code.attributes.remove(attrName);
                    } else if (!code.attributes.contains(attrName)) {
                        code.attributes.add(attrName);
                    }
                }
                return true;
            default:
                return false;
        }
    }

    /**
     * Apply a built-in variable attribute.
     * {@code shared} is recognized (no-op in PerlOnJava).
     * {@code -shared} triggers a "may not be unshared" error.
     */
    private static boolean applyVariableAttribute(String attrName, boolean negate) {
        if ("shared".equals(attrName)) {
            if (negate) {
                throw new RuntimeException("A variable may not be unshared");
            }
            return true;
        }
        return false;
    }

    /**
     * {@code _fetch_attrs($svref)} — Retrieve built-in attributes from a reference.
     *
     * <p>For CODE refs: returns the built-in attributes (lvalue, method, const) from
     * {@link RuntimeCode#attributes}.
     *
     * <p>For other ref types: returns an empty list (no built-in variable attributes
     * are tracked in PerlOnJava).
     */
    public static RuntimeList fetchAttrs(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            return new RuntimeList();
        }
        RuntimeScalar svref = args.get(0);

        if (svref.type == CODE) {
            RuntimeCode code = (RuntimeCode) svref.value;
            if (code.attributes == null) {
                return new RuntimeList();
            }
            RuntimeArray result = new RuntimeArray();
            for (String attr : code.attributes) {
                // Only return built-in attributes from _fetch_attrs
                // (lvalue, method, const are the built-in CODE attrs)
                if ("lvalue".equals(attr) || "method".equals(attr) || "const".equals(attr)) {
                    RuntimeArray.push(result, new RuntimeScalar(attr));
                }
            }
            return result.getList();
        }

        return new RuntimeList();
    }

    /**
     * {@code _guess_stash($svref)} — Determine the package for FETCH_*_ATTRIBUTES lookup.
     *
     * <p>For CODE refs: returns {@link RuntimeCode#packageName} (the original compilation
     * package). For anonymous subs, this is the package they were compiled in.
     *
     * <p>For other ref types: returns {@code undef} (caller will use {@code caller()} as fallback).
     */
    public static RuntimeList guessStash(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            return new RuntimeScalar().getList();
        }
        RuntimeScalar svref = args.get(0);

        if (svref.type == CODE) {
            RuntimeCode code = (RuntimeCode) svref.value;
            if (code.packageName != null) {
                return new RuntimeScalar(code.packageName).getList();
            }
        }

        // For non-CODE refs, return undef — caller will use caller() as fallback
        return new RuntimeScalar().getList();
    }

    /**
     * {@code reftype($ref)} — Returns the underlying reference type, ignoring bless.
     *
     * <p>Delegates to the same logic as {@link ScalarUtil#reftype}.
     */
    public static RuntimeList reftype(RuntimeArray args, int ctx) {
        return ScalarUtil.reftype(args, ctx);
    }

    /**
     * Get the uppercase reference type string for a RuntimeScalar.
     */
    private static String getRefType(RuntimeScalar scalar) {
        return switch (scalar.type) {
            case CODE -> "CODE";
            case REFERENCE -> "SCALAR";
            case ARRAYREFERENCE -> "ARRAY";
            case HASHREFERENCE -> "HASH";
            case GLOBREFERENCE -> "GLOB";
            case REGEX -> "REGEXP";
            default -> "";
        };
    }
}

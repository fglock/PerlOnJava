package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

            // Check for unterminated attribute parameter
            if (attr.contains("(") && !attr.endsWith(")")) {
                throw new org.perlonjava.runtime.runtimetypes.PerlCompilerException(
                        "Unterminated attribute parameter in attribute list");
            }

            int result = applyBuiltinAttribute(svref, svtype, attr);
            if (result == ATTR_UNRECOGNIZED || result == ATTR_WARN) {
                // ATTR_WARN: attribute was applied but should be returned so
                // _modify_attrs_and_deprecate can emit the appropriate warning
                unrecognized.add(attr);
            }
        }

        RuntimeArray result = new RuntimeArray();
        for (String attr : unrecognized) {
            RuntimeArray.push(result, new RuntimeScalar(attr));
        }
        return result.getList();
    }

    // Return values for applyBuiltinAttribute
    private static final int ATTR_UNRECOGNIZED = 0;   // Not a built-in attribute
    private static final int ATTR_APPLIED = 1;         // Applied silently (no warning needed)
    private static final int ATTR_WARN = 2;            // Applied, but should warn (state change on defined sub)

    /**
     * Try to apply a single built-in attribute.
     *
     * @return ATTR_UNRECOGNIZED if not built-in, ATTR_APPLIED if applied silently,
     *         ATTR_WARN if applied but the original attr name should be returned
     *         so _modify_attrs_and_deprecate can emit a warning.
     */
    private static int applyBuiltinAttribute(RuntimeScalar svref, String svtype, String attr) {
        boolean negate = attr.startsWith("-");
        String attrName = negate ? attr.substring(1) : attr;

        if ("CODE".equals(svtype)) {
            return applyCodeAttribute(svref, attrName, negate);
        } else if ("SCALAR".equals(svtype) || "ARRAY".equals(svtype) || "HASH".equals(svtype)) {
            return applyVariableAttribute(attrName, negate) ? ATTR_APPLIED : ATTR_UNRECOGNIZED;
        }
        return ATTR_UNRECOGNIZED;
    }

    /**
     * Apply a built-in CODE attribute.
     *
     * <p>For {@code lvalue} and {@code const}: returns ATTR_WARN when there's a meaningful
     * state change on an already-defined subroutine. This causes the attribute name to be
     * returned by {@code _modify_attrs}, allowing {@code _modify_attrs_and_deprecate} in
     * attributes.pm to emit the appropriate warning.
     *
     * <p>Only attributes with entries in attributes.pm's {@code %msg} hash should return
     * ATTR_WARN: {@code lvalue} (adding), {@code -lvalue} (removing), and {@code const} (adding).
     */
    private static int applyCodeAttribute(RuntimeScalar svref, String attrName, boolean negate) {
        // Handle prototype(...)
        if (attrName.startsWith("prototype(") && attrName.endsWith(")")) {
            if (svref.type == CODE) {
                RuntimeCode code = (RuntimeCode) svref.value;
                String newProto = attrName.substring(10, attrName.length() - 1);
                String oldProto = code.prototype;

                // Emit "Illegal character in prototype" warning
                if (newProto != null && !newProto.isEmpty()) {
                    boolean hasIllegal = false;
                    for (int i = 0; i < newProto.length(); i++) {
                        char c = newProto.charAt(i);
                        if ("$@%&*;+\\[]_ ".indexOf(c) < 0) {
                            hasIllegal = true;
                            break;
                        }
                    }
                    if (hasIllegal) {
                        // Use *PKG::name format for the warning when called via use attributes
                        String name = code.subName != null ? "*" + code.packageName + "::" + code.subName : "?";
                        String msg = "Illegal character in prototype for " + name + " : " + newProto;
                        Warnings.emitCategoryWarning("illegalproto", msg);
                    }
                }

                // Emit "Prototype mismatch" warning
                if (oldProto != null || newProto != null) {
                    String oldDisplay = oldProto == null ? ": none" : " (" + oldProto + ")";
                    String newDisplay = newProto == null ? "none" : "(" + newProto + ")";
                    String oldForCompare = oldProto == null ? "none" : "(" + oldProto + ")";
                    if (!oldForCompare.equals(newDisplay)) {
                        String subName = code.subName != null
                                ? code.packageName + "::" + code.subName : "__ANON__";
                        String msg = "Prototype mismatch: sub " + subName + oldDisplay + " vs " + newDisplay;
                        Warnings.emitWarningFromCaller(msg);
                    }
                }

                code.prototype = negate ? null : newProto;
            }
            return ATTR_APPLIED;
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
                    boolean hadAttr = code.attributes.contains(attrName);
                    // Check if sub has a callable body (can actually be invoked)
                    boolean hasCallableBody = code.subroutine != null || code.methodHandle != null
                            || code instanceof org.perlonjava.backend.bytecode.InterpretedCode;
                    // Check if sub has an actual body (not just a stub from \&foo)
                    boolean isDefinedSub = hasCallableBody
                            || code.constantValue != null || code.compilerSupplier != null
                            || code.isBuiltin;
                    if (negate) {
                        code.attributes.remove(attrName);
                        if ("const".equals(attrName)) {
                            code.constantValue = null;  // Clear constant value on removal
                        }
                        // Only lvalue has a removal warning in %msg ("-lvalue")
                        // Only warn for already-defined subroutines with a state change
                        if ("lvalue".equals(attrName) && hadAttr && isDefinedSub) {
                            return ATTR_WARN;
                        }
                        return ATTR_APPLIED;
                    } else {
                        if (!hadAttr) {
                            code.attributes.add(attrName);
                        }
                        // lvalue warns on state change for already-defined subs
                        if ("lvalue".equals(attrName) && !hadAttr && isDefinedSub) {
                            return ATTR_WARN;
                        }
                        // const: invoke and store result if callable, else warn "useless"
                        if ("const".equals(attrName) && !hadAttr) {
                            if (hasCallableBody) {
                                // Const folding: call the sub with no args and store the result
                                // Deep-copy: the result may contain aliases to mutable variables
                                // (e.g. sub :const { $_ } — the result aliases $_, which may change later)
                                RuntimeArray emptyArgs = new RuntimeArray();
                                RuntimeList result = code.apply(emptyArgs, RuntimeContextType.LIST);
                                RuntimeList frozen = new RuntimeList();
                                for (RuntimeBase elem : result.elements) {
                                    if (elem instanceof RuntimeScalar rs) {
                                        frozen.elements.add(new RuntimeScalar(rs));
                                    } else {
                                        frozen.elements.add(elem);
                                    }
                                }
                                code.constantValue = frozen;
                                return ATTR_APPLIED;
                            }
                            // No callable body — const is useless
                            return ATTR_WARN;
                        }
                        return ATTR_APPLIED;
                    }
                }
                return ATTR_APPLIED;
            default:
                return ATTR_UNRECOGNIZED;
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
            // For blessed CODE refs, return the blessed class (like Perl's SvSTASH)
            if (code.blessId != 0) {
                return new RuntimeScalar(NameNormalizer.getBlessStr(code.blessId)).getList();
            }
            // For non-blessed CODE refs, return the compilation package
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
     * Dispatch MODIFY_CODE_ATTRIBUTES at runtime for anonymous subs.
     *
     * <p>Called from generated bytecode when an anonymous sub has non-builtin
     * attributes (e.g., {@code sub : Const { ... }}). Filters out built-in
     * attributes (already applied directly) and calls the package's
     * MODIFY_CODE_ATTRIBUTES handler for the rest.
     *
     * @param packageName The package to look up MODIFY_CODE_ATTRIBUTES in
     * @param codeRef     The RuntimeScalar wrapping the anonymous sub's RuntimeCode
     */
    public static void runtimeDispatchModifyCodeAttributes(String packageName, RuntimeScalar codeRef) {
        runtimeDispatchModifyCodeAttributes(packageName, codeRef, false);
    }

    /**
     * Dispatch MODIFY_CODE_ATTRIBUTES at runtime for anonymous subs.
     * When {@code isClosure} is true, marks the original code as a closure prototype
     * (non-callable) and replaces the RuntimeScalar's value with a callable clone.
     *
     * @param packageName The package to look up MODIFY_CODE_ATTRIBUTES in
     * @param codeRef     The RuntimeScalar wrapping the anonymous sub's RuntimeCode
     * @param isClosure   Whether the sub captures lexical variables (is a closure)
     */
    public static void runtimeDispatchModifyCodeAttributes(String packageName, RuntimeScalar codeRef, boolean isClosure) {
        if (codeRef.type != CODE) return;
        RuntimeCode code = (RuntimeCode) codeRef.value;
        if (code.attributes == null || code.attributes.isEmpty()) return;

        // Filter non-builtin attributes
        Set<String> builtinAttrs = Set.of("lvalue", "method", "const");
        List<String> nonBuiltinAttrs = new ArrayList<>();
        for (String attr : code.attributes) {
            String name = attr.startsWith("-") ? attr.substring(1) : attr;
            int parenIdx = name.indexOf('(');
            String baseName = parenIdx >= 0 ? name.substring(0, parenIdx) : name;
            if (!builtinAttrs.contains(baseName) && !baseName.equals("prototype")) {
                nonBuiltinAttrs.add(attr);
            }
        }
        if (nonBuiltinAttrs.isEmpty()) return;

        // Remove non-builtin attrs from the list (they're handled by the handler)
        code.attributes.removeAll(nonBuiltinAttrs);

        // Check if the package has MODIFY_CODE_ATTRIBUTES
        RuntimeArray canArgs = new RuntimeArray();
        RuntimeArray.push(canArgs, new RuntimeScalar(packageName));
        RuntimeArray.push(canArgs, new RuntimeScalar("MODIFY_CODE_ATTRIBUTES"));

        InheritanceResolver.autoloadEnabled = false;
        RuntimeList codeList;
        try {
            codeList = Universal.can(canArgs, RuntimeContextType.SCALAR);
        } finally {
            InheritanceResolver.autoloadEnabled = true;
        }

        boolean hasHandler = codeList.size() == 1 && codeList.getFirst().getBoolean();

        if (hasHandler) {
            RuntimeScalar method = codeList.getFirst();
            RuntimeArray callArgs = new RuntimeArray();
            RuntimeArray.push(callArgs, new RuntimeScalar(packageName));
            RuntimeArray.push(callArgs, codeRef);
            for (String attr : nonBuiltinAttrs) {
                RuntimeArray.push(callArgs, new RuntimeScalar(attr));
            }

            RuntimeList result = RuntimeCode.apply(method, callArgs, RuntimeContextType.LIST);

            // If MODIFY_CODE_ATTRIBUTES returns any values, they are unrecognized
            RuntimeArray resultArray = result.getArrayOfAlias();
            if (resultArray.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < resultArray.size(); i++) {
                    if (i > 0) sb.append(" : ");
                    sb.append(resultArray.get(i).toString());
                }
                throw new PerlCompilerException(
                        "Invalid CODE attribute" + (resultArray.size() > 1 ? "s" : "") + ": " + sb);
            }

            // For closures: mark the original code as a prototype and replace
            // codeRef's value with a callable clone. The MODIFY_CODE_ATTRIBUTES
            // handler may have captured codeRef (e.g., $proto = $_[1]), so the
            // handler's captured reference will point to the prototype (non-callable),
            // while the expression result (codeRef) gets the callable clone.
            if (isClosure) {
                RuntimeCode originalCode = (RuntimeCode) codeRef.value;
                RuntimeCode clone = originalCode.cloneForClosure();
                clone.__SUB__ = new RuntimeScalar(clone);
                originalCode.isClosurePrototype = true;
                codeRef.type = CODE;
                codeRef.value = clone;
            }
        } else {
            // No MODIFY_CODE_ATTRIBUTES handler — all non-builtin attrs are invalid
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < nonBuiltinAttrs.size(); i++) {
                if (i > 0) sb.append(" : ");
                sb.append(nonBuiltinAttrs.get(i));
            }
            throw new PerlCompilerException(
                    "Invalid CODE attribute" + (nonBuiltinAttrs.size() > 1 ? "s" : "") + ": " + sb);
        }
    }

    /**
     * Dispatch MODIFY_*_ATTRIBUTES at runtime for {@code my}/{@code state} variables.
     *
     * <p>Called from generated bytecode when a lexical variable declaration has
     * non-builtin attributes (e.g., {@code my $x : TieLoop}). At compile time,
     * the parser validates that the handler exists and emits the reserved-word
     * warning. At runtime, after the variable is allocated, this method creates
     * a reference to the actual lexical and calls the handler.
     *
     * @param packageName The package to look up MODIFY_*_ATTRIBUTES in
     * @param variable    The actual runtime variable (RuntimeScalar/RuntimeArray/RuntimeHash)
     * @param sigil       The variable sigil ("$", "@", or "%")
     * @param attributes  The attribute strings from the declaration
     * @param fileName    Source file name for CallerStack (used by Attribute::Handlers)
     * @param lineNum     Source line number for CallerStack
     */
    public static void runtimeDispatchModifyVariableAttributes(
            String packageName, RuntimeBase variable, String sigil,
            String[] attributes, String fileName, int lineNum) {

        String svtype = switch (sigil) {
            case "$" -> "SCALAR";
            case "@" -> "ARRAY";
            case "%" -> "HASH";
            default -> throw new PerlCompilerException("Unknown sigil: " + sigil);
        };

        // Filter built-in attributes
        List<String> nonBuiltinAttrs = new ArrayList<>();
        for (String attr : attributes) {
            if ("shared".equals(attr)) continue;
            nonBuiltinAttrs.add(attr);
        }
        if (nonBuiltinAttrs.isEmpty()) return;

        // Check if the package has MODIFY_*_ATTRIBUTES
        String modifyMethod = "MODIFY_" + svtype + "_ATTRIBUTES";
        RuntimeArray canArgs = new RuntimeArray();
        RuntimeArray.push(canArgs, new RuntimeScalar(packageName));
        RuntimeArray.push(canArgs, new RuntimeScalar(modifyMethod));

        InheritanceResolver.autoloadEnabled = false;
        RuntimeList codeList;
        try {
            codeList = Universal.can(canArgs, RuntimeContextType.SCALAR);
        } finally {
            InheritanceResolver.autoloadEnabled = true;
        }

        boolean hasHandler = codeList.size() == 1 && codeList.getFirst().getBoolean();

        if (hasHandler) {
            // Create reference to the actual variable
            RuntimeScalar varRef = variable.createReference();

            RuntimeScalar method = codeList.getFirst();
            RuntimeArray callArgs = new RuntimeArray();
            RuntimeArray.push(callArgs, new RuntimeScalar(packageName));
            RuntimeArray.push(callArgs, varRef);
            for (String attr : nonBuiltinAttrs) {
                RuntimeArray.push(callArgs, new RuntimeScalar(attr));
            }

            // Push caller frames so Attribute::Handlers can find source file/line
            CallerStack.push(packageName, fileName, lineNum);
            CallerStack.push(packageName, fileName, lineNum);
            try {
                RuntimeList result = RuntimeCode.apply(method, callArgs, RuntimeContextType.LIST);

                // If handler returns any values, they are unrecognized attributes
                RuntimeArray resultArray = result.getArrayOfAlias();
                if (resultArray.size() > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < resultArray.size(); i++) {
                        if (i > 0) sb.append(" : ");
                        sb.append(resultArray.get(i).toString());
                    }
                    throw new PerlCompilerException(
                            "Invalid " + svtype + " attribute"
                                    + (resultArray.size() > 1 ? "s" : "") + ": " + sb);
                }
            } finally {
                CallerStack.pop();
                CallerStack.pop();
            }
        } else {
            // No handler found at runtime — throw error.
            // For 'our' variables, this is caught at compile time.
            // For 'my'/'state', the compile-time check is deferred to here
            // so that dynamically-set handlers (via glob in eval) are found.
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < nonBuiltinAttrs.size(); i++) {
                if (i > 0) sb.append(" : ");
                sb.append(nonBuiltinAttrs.get(i));
            }
            throw new PerlCompilerException(
                    "Invalid " + svtype + " attribute"
                            + (nonBuiltinAttrs.size() > 1 ? "s" : "") + ": " + sb);
        }
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
            case READONLY_SCALAR -> getRefType((RuntimeScalar) scalar.value);
            default -> "";
        };
    }
}

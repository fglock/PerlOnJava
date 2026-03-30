package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;

/**
 * Java built-in for overload::StrVal and overload::AddrRef.
 *
 * <p>These must bypass overloaded stringification to avoid infinite recursion.
 * In Perl 5, overload::AddrRef uses {@code no overloading; "$_[0]"} to get the raw
 * "Class=TYPE(0xADDR)" string without triggering the overloaded {@code ""} operator.
 * Since PerlOnJava does not yet implement the {@code no overloading} pragma, we provide
 * Java built-ins that call toStringRef() directly.
 */
public class OverloadModule extends PerlModuleBase {

    public OverloadModule() {
        // false: don't set %INC — overload.pm is loaded from the .pm file
        super("overload", false);
    }

    public static void initialize() {
        OverloadModule mod = new OverloadModule();
        try {
            mod.registerMethod("StrVal", "$");
            mod.registerMethod("AddrRef", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing overload method: " + e.getMessage());
        }
    }

    /**
     * overload::StrVal($obj) — returns the raw "Class=TYPE(0xADDR)" string
     * WITHOUT triggering overloaded "" operator.
     */
    public static RuntimeList StrVal(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for overload::StrVal");
        }
        RuntimeScalar scalar = args.get(0);

        return switch (scalar.type) {
            case REFERENCE -> new RuntimeScalar(scalar.toStringRef()).getList();
            case ARRAYREFERENCE, HASHREFERENCE, CODE, GLOB, GLOBREFERENCE, FORMAT, REGEX ->
                    new RuntimeScalar(((RuntimeBase) scalar.value).toStringRef()).getList();
            default ->
                    // For non-references, return the raw string value
                    new RuntimeScalar(scalar.toStringRef()).getList();
        };
    }

    /**
     * overload::AddrRef is an alias for StrVal.
     */
    public static RuntimeList AddrRef(RuntimeArray args, int ctx) {
        return StrVal(args, ctx);
    }
}

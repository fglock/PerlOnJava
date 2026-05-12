package org.perlonjava.runtime.runtimetypes;

import java.util.regex.Pattern;

/**
 * Supports Perl-style {@code Package::Sub} barewords under {@code use strict 'subs'}
 * when the symbol is installed only at runtime (e.g. after {@code use_ok} loads a
 * module that defines {@code use constant} subs).
 *
 * <p>Stock Perl 5 rejects these at compile time under {@code strict subs}. PerlOnJava
 * resolves them at runtime so modules loaded after {@code use_ok} (e.g. {@code use constant})
 * behave as users expect in smoke tests.</p>
 */
public final class QualifiedBarewordSubCall {

    private static final Pattern QUALIFIED_SUB_LIKE =
            Pattern.compile("^[A-Za-z_]\\w*(::\\w+)+\\z");

    private QualifiedBarewordSubCall() {}

    public static boolean isDeferredStrictSubsBareword(String name) {
        return name != null && QUALIFIED_SUB_LIKE.matcher(name).matches();
    }

    /**
     * Invokes a nullary package subroutine by fully qualified name.
     *
     * @param fullyQualifiedSub normalized name (e.g. {@code Cpanel::JSON::XS::Type::JSON_TYPE_INT})
     * @param callContext       {@link RuntimeContextType} value passed to {@link RuntimeCode#apply}
     */
    public static RuntimeBase invoke(String fullyQualifiedSub, int callContext) {
        RuntimeScalar cref = GlobalVariable.getGlobalCodeRef(fullyQualifiedSub);
        RuntimeArray args = new RuntimeArray();
        RuntimeList out = RuntimeCode.apply(cref, args, callContext);
        if (callContext == RuntimeContextType.LIST) {
            return out;
        }
        if (callContext == RuntimeContextType.VOID) {
            return out;
        }
        return out.scalar();
    }
}

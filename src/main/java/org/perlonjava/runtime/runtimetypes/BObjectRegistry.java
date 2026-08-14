package org.perlonjava.runtime.runtimetypes;

import java.lang.ref.WeakReference;
import java.util.Map;

/**
 * Resolves the pseudo-addresses exposed by reference stringification back to
 * live Perl referents for {@code B::SV::object_2svref()}.
 *
 * <p>The registry is scoped to a {@link PerlRuntime} and holds weak values, so
 * exposing an address does not extend the referent's lifetime.</p>
 */
public final class BObjectRegistry {
    private BObjectRegistry() {}

    private static Map<Integer, WeakReference<RuntimeBase>> state() {
        return PerlRuntime.current().bObjectState;
    }

    public static void register(RuntimeBase referent) {
        if (referent == null) return;
        state().put(referent.hashCode(), new WeakReference<>(referent));
    }

    public static RuntimeScalar resolve(long address) {
        WeakReference<RuntimeBase> reference = state().get((int) address);
        RuntimeBase referent = reference == null ? null : reference.get();
        if (referent == null) {
            state().remove((int) address);
            return new RuntimeScalar();
        }
        return referent.createReference();
    }
}

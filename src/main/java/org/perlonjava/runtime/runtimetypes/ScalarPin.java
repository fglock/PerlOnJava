package org.perlonjava.runtime.runtimetypes;

/**
 * A compiled access site's binding to a package scalar.
 *
 * <p>While the name stays linked in its stash, the pin reads the live scalar, as an
 * ordinary name lookup does. When the stash entry is deleted, the pin is detached and
 * keeps the scalar it had, so code compiled against the old glob still sees its
 * variable, as in Perl. Code compiled after the delete gets a new pin and resolves the
 * name again.</p>
 */
public final class ScalarPin {
    private record BindingCache(GlobalRuntimeState runtimeState, long version, RuntimeScalar scalar) {}

    private final String name;
    /** Non-null once the name has been deleted from its stash. */
    private RuntimeScalar detached;
    private volatile BindingCache bindingCache;

    ScalarPin(String name) {
        this.name = name;
    }

    /** The scalar this site currently reads. */
    public RuntimeScalar scalar() {
        RuntimeScalar frozen = detached;
        if (frozen != null) return frozen;

        GlobalRuntimeState runtimeState = GlobalVariable.currentGlobalRuntimeState();
        long version = runtimeState.scalarBindingVersion();
        BindingCache cached = bindingCache;
        if (cached != null
                && cached.runtimeState() == runtimeState
                && cached.version() == version) {
            return cached.scalar();
        }

        RuntimeScalar scalar = GlobalVariable.getGlobalVariable(name);
        if (runtimeState.scalarBindingVersion() == version) {
            bindingCache = new BindingCache(runtimeState, version, scalar);
        }
        return scalar;
    }

    void detach(RuntimeScalar frozen) {
        this.detached = frozen;
    }
}

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
    private final String name;
    /** Non-null once the name has been deleted from its stash. */
    private RuntimeScalar detached;

    ScalarPin(String name) {
        this.name = name;
    }

    /** The scalar this site currently reads. */
    public RuntimeScalar scalar() {
        RuntimeScalar frozen = detached;
        return frozen != null ? frozen : GlobalVariable.getGlobalVariable(name);
    }

    void detach(RuntimeScalar frozen) {
        this.detached = frozen;
    }
}

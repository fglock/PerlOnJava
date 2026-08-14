package org.perlonjava.runtime.io;

/**
 * Declares how an {@link IOHandle} crosses a Perl ithread snapshot boundary.
 *
 * <p>The graph cloner, rather than individual callers, applies these policies so
 * aliases are preserved consistently.  New native/resource-backed handle types
 * must opt in explicitly; the default is deliberately {@link #UNSUPPORTED}.</p>
 */
public enum ThreadInheritancePolicy {
    /** Share one transport while giving parent and child independently closable leases. */
    SHARED_TRANSPORT,
    /** Rebuild the wrapper in the child after inheriting its delegate. */
    WRAPPER_COPY,
    /** The handle owns only Perl values and is copied into the child value graph. */
    VALUE_COPY,
    /** The implementation already provides an independently closable inherited endpoint. */
    IMPLEMENTATION_COPY,
    /** A closed handle remains closed in the child. */
    CLOSED,
    /** No safe ithread inheritance contract has been defined. */
    UNSUPPORTED
}

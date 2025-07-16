package org.perlonjava.runtime;

import java.util.HashMap;
import java.util.Map;

import static org.perlonjava.runtime.RuntimeScalarType.HASHREFERENCE;

/**
 * AutovivificationHash extends HashMap to support Perl's autovivification feature.
 *
 * <p>In Perl, autovivification is the automatic creation of data structures when
 * they are accessed in a way that assumes they exist. This class holds a reference
 * to the RuntimeScalar that should be autovivified when the hash is assigned a list.
 *
 * <p>For example, in Perl code like {@code %$hash = ()}, if $hash
 * doesn't exist, it will be automatically created. This class
 * facilitates such behavior by holding the scalar that needs to be autovivified.
 *
 */
public class AutovivificationHash extends HashMap<String, RuntimeScalar> {

    /**
     * The RuntimeScalar that should be autovivified when this hash is assigned a list.
     * This typically refers to the scalar variable that holds the reference to this hash.
     */
    public RuntimeScalar scalarToAutovivify;

    /**
     * Constructs an AutovivificationHash with a reference to the scalar that needs autovivification.
     *
     * @param scalarToAutovivify The RuntimeScalar that should be autovivified when the hash
     *                          is assigned a list, typically the scalar holding the hash reference.
     */
    public AutovivificationHash(RuntimeScalar scalarToAutovivify) {
        this.scalarToAutovivify = scalarToAutovivify;
    }

    public void vivify(RuntimeHash hash) {
        // Trigger autovivification: Convert the undefined scalar to a hash reference.
        // This happens when code like %$undef_scalar = (...) is executed.
        // The AutovivificationHash was created when the undefined scalar was first
        // dereferenced as a hash, and now we complete the autovivification by
        // setting the scalar's type to HASHREFERENCE and its value to this hash.

        hash.elements = new HashMap<>();

        scalarToAutovivify.value = hash;
        scalarToAutovivify.type = HASHREFERENCE;
    }
}

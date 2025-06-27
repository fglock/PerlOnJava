package org.perlonjava.runtime;

import java.util.ArrayList;

/**
 * AutovivificationArray extends ArrayList to support Perl's autovivification feature.
 *
 * <p>In Perl, autovivification is the automatic creation of data structures when
 * they are accessed in a way that assumes they exist. This class holds a reference
 * to the RuntimeScalar that should be autovivified when the array is assigned a list.
 *
 * <p>For example, in Perl code like {@code @$array = ()}, if $array
 * doesn't exist, it will be automatically created. This class
 * facilitates such behavior by holding the scalar that needs to be autovivified.
 *
 */
public class AutovivificationArray extends ArrayList<RuntimeScalar> {

    /**
     * The RuntimeScalar that should be autovivified when this array is assigned a list.
     * This typically refers to the scalar variable that holds the reference to this array.
     */
    public RuntimeScalar scalarToAutovivify;

    /**
     * Constructs an AutovivificationArray with a reference to the scalar that needs autovivification.
     *
     * @param scalarToAutovivify The RuntimeScalar that should be autovivified when the array
     *                          is assigned a list, typically the scalar holding the array reference.
     */
    public AutovivificationArray(RuntimeScalar scalarToAutovivify) {
        this.scalarToAutovivify = scalarToAutovivify;
    }
}
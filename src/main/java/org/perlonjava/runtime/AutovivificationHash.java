package org.perlonjava.runtime;

import java.util.HashMap;

/**
 * AutovivificationHash extends HashMap to support Perl's autovivification feature.
 *
 * <p>In Perl, autovivification is the automatic creation of data structures when
 * they are accessed in a way that assumes they exist. This class provides a hook
 * to execute custom logic when the hash is assigned a list, which is typically
 * when autovivification needs to occur.
 *
 * <p>For example, in Perl code like {@code %$hash = ()}, if $hash
 * doesn't exist, it will be automatically created. This class
 * facilitates such behavior by allowing a callback to be executed during list assignment.
 *
 */
public class AutovivificationHash extends HashMap<String, RuntimeScalar> {

    /**
     * Callback executed when this hash is assigned a list.
     * This typically triggers autovivification logic to ensure
     * the hash is properly initialized in its containing structure.
     */
    public Runnable autovivifyCallback;

    /**
     * Constructs an AutovivificationHash with an optional callback.
     *
     * @param autovivifyCallback Callback to execute when the hash is assigned a list,
     *                          typically to trigger autovivification in the parent structure.
     */
    public AutovivificationHash(Runnable autovivifyCallback) {
        this.autovivifyCallback = autovivifyCallback;
    }
}

package org.perlonjava.runtime.perlmodule;

/**
 * Stub Cwd module for XSLoader compatibility.
 * 
 * The actual implementation is in the Perl Cwd.pm which has pure Perl
 * fallbacks. This stub exists so that XSLoader::load('Cwd') succeeds,
 * allowing the Perl module to fall back to its pure Perl implementations.
 */
public class Cwd extends PerlModuleBase {

    public Cwd() {
        super("Cwd");
    }

    /**
     * Empty initializer - lets XSLoader::load succeed without defining methods.
     * The Perl Cwd.pm will detect that &getcwd is not defined and use its
     * pure Perl fallback implementations.
     */
    public static void initialize() {
        // Intentionally empty - pure Perl fallbacks in Cwd.pm will be used
    }
}

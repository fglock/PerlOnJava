package org.perlonjava;

/**
 * Central configuration class for the Perl-to-Java compiler.
 * Contains constants that control compiler behavior and runtime settings.
 *
 * Note: Configuration values can be set using the Configure.pl script.
 * For example, to set the PERL_VERSION, you can run:
 *
 *     ./Configure.pl -D PERL_VERSION=v5.32.0
 *
 * This will update the PERL_VERSION constant in this configuration class.
 */
public final class Configuration {
    // Perl version information
    public static final String PERL_VERSION = "v5.38.0";
    public static final String PERL_VERSION_NO_V = "5.38.0";
    public static final String PERL_VERSION_OLD = "5.038000";

    // Compiler behavior flags
//    public static final boolean ENABLE_OPTIMIZATIONS = true;
//    public static final boolean PRESERVE_COMMENTS = false;
//    public static final boolean STRICT_MODE = true;

    // Runtime settings
//    public static final int MAX_RECURSION_DEPTH = 1000;
//    public static final int DEFAULT_ARRAY_CAPACITY = 16;

    // Prevent instantiation
    private Configuration() {}
}

package org.perlonjava.runtime.regex;

/**
 * Temporary migration policy for comparing the legacy Java-first routing with
 * the canonical Joni matcher. Ordinary lookbehind also remains on Java until
 * Joni's nested-lookahead admission is complete. Branch-reset subroutine calls
 * also temporarily use Java pending Joni's native named-call patch. Other
 * Joni-only constructs in the same pattern still force Joni. This class and its
 * controls are removed when the Java matching backend is retired.
 */
final class RegexBackendPolicy {
    static final String PROPERTY = "jperl.regex.backend";
    static final String ENVIRONMENT = "JPERL_REGEX_BACKEND";

    enum Mode {
        JAVA,
        JONI
    }

    private RegexBackendPolicy() {
    }

    static Mode current() {
        String configured = System.getProperty(PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(ENVIRONMENT);
        }
        if (configured == null || configured.isBlank()
                || configured.equalsIgnoreCase("auto")
                || configured.equalsIgnoreCase("java")) {
            return Mode.JAVA;
        }
        if (configured.equalsIgnoreCase("joni")) {
            return Mode.JONI;
        }
        throw new IllegalArgumentException("Invalid " + ENVIRONMENT + " value '"
                + configured + "' (expected java or joni)");
    }

    static boolean useJoni(String pattern) {
        return useJoni(pattern, pattern == null ? null : RegexFlags.fromModifiers("", pattern));
    }

    static boolean useJoni(String pattern, RegexFlags flags) {
        return current() == Mode.JONI || JoniRegexPattern.requiresJoniBackend(pattern, flags);
    }

    static String cacheTag() {
        return current().name().toLowerCase(java.util.Locale.ROOT);
    }
}

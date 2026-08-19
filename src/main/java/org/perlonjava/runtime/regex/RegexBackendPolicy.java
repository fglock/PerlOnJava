package org.perlonjava.runtime.regex;

/**
 * Temporary migration policy for comparing the canonical Joni matcher with
 * the legacy Java matcher. Default and auto modes use Joni; explicit Java mode
 * remains only for differential diagnosis. Constructs unavailable in Java may
 * still force Joni even in explicit Java mode. This class and its controls are
 * removed when the Java matching backend is retired.
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
                || configured.equalsIgnoreCase("joni")) {
            return Mode.JONI;
        }
        if (configured.equalsIgnoreCase("java")) {
            return Mode.JAVA;
        }
        throw new IllegalArgumentException("Invalid " + ENVIRONMENT + " value '"
                + configured + "' (expected auto, java, or joni)");
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

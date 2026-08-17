package org.perlonjava.runtime.regex;

/**
 * Temporary migration policy for comparing the legacy Java-first routing with
 * the canonical Joni matcher. This class and its controls are removed when the
 * Java matching backend is retired.
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
        return current() == Mode.JONI || JoniRegexPattern.requiresJoniBackend(pattern);
    }

    static String cacheTag() {
        return current().name().toLowerCase(java.util.Locale.ROOT);
    }
}

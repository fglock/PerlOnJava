package org.perlonjava.runtime.regex;

/**
 * Test-only model of the retired regex-backend selector.
 *
 * <p>The historical routing tests remain useful migration documentation, but
 * this class is deliberately absent from production artifacts. Production
 * matching is unconditionally implemented by Joni.</p>
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

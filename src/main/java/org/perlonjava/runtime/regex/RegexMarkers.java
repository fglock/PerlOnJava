package org.perlonjava.runtime.regex;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source-only markers that carry frontend regex diagnostics into native Joni
 * compilation without changing executable pattern text.
 */
public final class RegexMarkers {
    /**
     * Source-only marker for a literal {@code \\E} encountered without an
     * active quote or case-modification region. The frontend consumes that
     * escape, but RuntimeRegex still needs its provenance to emit Perl's
     * construction-time diagnostic. It is removed before backend compilation.
     */
    public static final String LITERAL_USELESS_CASE_ESCAPE = "\uFDD0\uFDEF";

    private static final String LITERAL_DIAGNOSTIC_PREFIX = "\uFDD0\uFDD2";
    private static final String LITERAL_DIAGNOSTIC_SUFFIX = "\uFDD3\uFDEF";
    private static final Pattern LITERAL_DIAGNOSTIC = Pattern.compile(
            Pattern.quote(LITERAL_DIAGNOSTIC_PREFIX) + "([A-Za-z0-9_-]+)"
                    + Pattern.quote(LITERAL_DIAGNOSTIC_SUFFIX));

    /** Encode a frontend diagnostic while retaining the literal regex spelling. */
    public static String literalDiagnostic(String diagnostic) {
        return LITERAL_DIAGNOSTIC_PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(
                        diagnostic.getBytes(StandardCharsets.UTF_8))
                + LITERAL_DIAGNOSTIC_SUFFIX;
    }

    /** Return the first frontend diagnostic carried by a literal pattern. */
    public static String firstLiteralDiagnostic(String pattern) {
        if (pattern == null) return null;
        Matcher matcher = LITERAL_DIAGNOSTIC.matcher(pattern);
        if (!matcher.find()) return null;
        return new String(Base64.getUrlDecoder().decode(matcher.group(1)),
                StandardCharsets.UTF_8);
    }

    /** Remove all source-only diagnostic markers before backend compilation. */
    public static String stripLiteralDiagnostics(String pattern) {
        return pattern == null ? null : LITERAL_DIAGNOSTIC.matcher(pattern).replaceAll("");
    }

    private RegexMarkers() {}
}

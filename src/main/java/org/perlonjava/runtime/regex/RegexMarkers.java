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
    // Keep identity markers inside the byte domain. Their Latin-1 members
    // preserve the source's lexical utf8 flag through literal materialization:
    // byte source stays octets, while `use utf8` source stays Unicode.
    private static final String LITERAL_IDENTITY_PREFIX = "\u001D\u00FE";
    private static final String LITERAL_IDENTITY_SUFFIX = "\u00FF\u001C";
    private static final Pattern LITERAL_DIAGNOSTIC = Pattern.compile(
            Pattern.quote(LITERAL_DIAGNOSTIC_PREFIX) + "([A-Za-z0-9_-]+)"
                    + Pattern.quote(LITERAL_DIAGNOSTIC_SUFFIX));
    private static final Pattern LITERAL_IDENTITY = Pattern.compile(
            Pattern.quote(LITERAL_IDENTITY_PREFIX) + "[0-9]+"
                    + Pattern.quote(LITERAL_IDENTITY_SUFFIX));

    /** Distinguish two lexical custom-charname literals without changing matcher source. */
    public static String literalIdentity(long identity) {
        return LITERAL_IDENTITY_PREFIX + Math.max(0, identity)
                + LITERAL_IDENTITY_SUFFIX;
    }

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
        if (pattern == null) return null;
        return LITERAL_IDENTITY.matcher(
                LITERAL_DIAGNOSTIC.matcher(pattern).replaceAll("")).replaceAll("");
    }

    private RegexMarkers() {}
}

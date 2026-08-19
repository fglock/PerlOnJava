package org.perlonjava.runtime.regex;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared placeholder markers used by the string-interpolation parser to
 * stand in for regex constructs that PerlOnJava cannot compile literally
 * (because they require features unsupported by the underlying Java regex
 * engine — e.g. arbitrary {@code (?{ CODE })} code blocks).
 *
 * <p>The markers are emitted by {@code StringSegmentParser} when a code
 * block can't be constant-folded. {@link RegexPreprocessor} detects them
 * and reports "not implemented":
 * <ul>
 *   <li>{@link #CODE_BLOCK} — a hard error under default die mode,
 *       or a no-op fallback only when {@link #CODE_BLOCK_NOOP_ENV} is set.
 *       Plain {@code JPERL_UNIMPLEMENTED=warn} still reports the unsupported
 *       feature without pretending the callback ran.</li>
 * </ul>
 *
 * <p><b>Why these specific spellings?</b> The preprocessor performs some
 * {@code /i} case-fold expansions (notably for {@code K}↔{@code k}↔
 * Kelvin sign U+212A, {@code µ}↔U+00B5↔U+03BC, and {@code Å}↔
 * U+212B↔{@code å}) by rewriting matching code points into alternations.
 * If the marker contained any of these "problem" letters it would be
 * silently rewritten under {@code /i}, bypassing the detection check and
 * leaving a garbled placeholder embedded in the compiled pattern (observed
 * bug: {@code (?{UNIMPLEMENTED_CODE_BLOC(?:\QK\E|\Qk\E|\QK\E)})}). Keeping
 * the markers free of {@code k}, {@code K}, {@code µ}, {@code å} (and
 * their Unicode counterparts) guarantees the detection check always
 * matches regardless of flags.
 */
public final class RegexMarkers {
    /**
     * Environment variable that permits non-constant {@code (?{ CODE })}
     * blocks to compile as empty no-op groups. This is deliberately opt-in:
     * normal PerlOnJava code must fail loudly because callbacks are not
     * executed.
     */
    public static final String CODE_BLOCK_NOOP_ENV = "JPERL_REGEX_CODE_BLOCK_NOOP";

    /**
     * Marker for a {@code (?{ CODE })} code block that could not be
     * constant-folded at parse time. Contains no fold-affected letters.
     */
    public static final String CODE_BLOCK = "(?{UNIMPLEMENTED_CODE_BLOC})";

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

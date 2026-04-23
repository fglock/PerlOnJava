package org.perlonjava.runtime.regex;

/**
 * Shared placeholder markers used by the string-interpolation parser to
 * stand in for regex constructs that PerlOnJava cannot compile literally
 * (because they require features unsupported by the underlying Java regex
 * engine — e.g. arbitrary {@code (?{ CODE })} code blocks and
 * {@code (??{ CODE })} recursive/dynamic patterns).
 *
 * <p>The markers are emitted by {@code StringSegmentParser} when a code
 * block can't be constant-folded. {@link RegexPreprocessor} detects them
 * and reports "not implemented":
 * <ul>
 *   <li>{@link #CODE_BLOCK} — a hard error; the surrounding regex can't
 *       usefully run without the code block.</li>
 *   <li>{@link #RECURSIVE_PATTERN} — a hard error under default die mode,
 *       or a warning under {@code JPERL_UNIMPLEMENTED=warn} followed by
 *       the soft {@code (?:} fallback so the surrounding pattern still
 *       compiles (many CPAN modules build dynamic patterns that happen
 *       to work with the empty-group fallback; under warn mode we want
 *       tests to continue but the user must see a diagnostic).</li>
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
     * Marker for a {@code (?{ CODE })} code block that could not be
     * constant-folded at parse time. Contains no fold-affected letters.
     */
    public static final String CODE_BLOCK = "(?{UNIMPLEMENTED_CODE_BLOC})";

    /**
     * Marker for a {@code (??{ CODE })} recursive/dynamic pattern that
     * could not be constant-folded at parse time. Contains no
     * fold-affected letters.
     */
    public static final String RECURSIVE_PATTERN = "(??{UNIMPLEMENTED_RECURSIVE_PATTERN})";

    private RegexMarkers() {}
}

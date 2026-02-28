package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.regex.RuntimeRegex;

import java.util.regex.Matcher;

/**
 * Immutable snapshot of all regex-related global state (Perl's $1, $&amp;, $`, $', etc.).
 *
 * <p>In Perl 5, regex match variables are dynamically scoped: each subroutine and
 * each block that contains regex operations saves the current state on entry and
 * restores it on exit.  This ensures that a caller's match variables are not
 * clobbered by callees or inner blocks.
 *
 * <p>Two levels of scoping use this class:
 * <ul>
 *   <li><b>Subroutine-level</b> (unconditional): saved at method entry, restored at exit.
 *       In JVM-compiled code: {@code EmitterMethodCreator} ({@code regexStateSlot}).
 *       In interpreted code: {@code BytecodeInterpreter} ({@code savedRegexState} + finally block).</li>
 *   <li><b>Block-level</b> (conditional, gated by {@link org.perlonjava.frontend.analysis.RegexUsageDetector}):
 *       saved/restored around blocks that contain regex ops.
 *       In JVM-compiled code: {@code EmitBlock} / {@code EmitForeach}.
 *       In interpreted code: {@code SAVE_REGEX_STATE} / {@code RESTORE_REGEX_STATE} opcodes.</li>
 * </ul>
 *
 * <p><b>Important ordering constraint:</b> When a subroutine returns a value containing
 * lazy {@link org.perlonjava.runtime.specialvariables.ScalarSpecialVariable} references (e.g., $1),
 * those must be materialized via {@link RuntimeCode#materializeSpecialVarsInResult}
 * BEFORE calling {@link #restore()}, otherwise the restored (caller's) state would be
 * read instead of the subroutine's state.
 *
 * @see org.perlonjava.runtime.regex.RuntimeRegex for the global static fields being snapshotted
 */
public class RegexState {
    public final Matcher globalMatcher;
    public final String globalMatchString;
    public final String lastMatchedString;
    public final int lastMatchStart;
    public final int lastMatchEnd;
    public final String lastSuccessfulMatchedString;
    public final int lastSuccessfulMatchStart;
    public final int lastSuccessfulMatchEnd;
    public final String lastSuccessfulMatchString;
    public final RuntimeRegex lastSuccessfulPattern;
    public final String[] lastCaptureGroups;

    public RegexState() {
        this.globalMatcher = RuntimeRegex.globalMatcher;
        this.globalMatchString = RuntimeRegex.globalMatchString;
        this.lastMatchedString = RuntimeRegex.lastMatchedString;
        this.lastMatchStart = RuntimeRegex.lastMatchStart;
        this.lastMatchEnd = RuntimeRegex.lastMatchEnd;
        this.lastSuccessfulMatchedString = RuntimeRegex.lastSuccessfulMatchedString;
        this.lastSuccessfulMatchStart = RuntimeRegex.lastSuccessfulMatchStart;
        this.lastSuccessfulMatchEnd = RuntimeRegex.lastSuccessfulMatchEnd;
        this.lastSuccessfulMatchString = RuntimeRegex.lastSuccessfulMatchString;
        this.lastSuccessfulPattern = RuntimeRegex.lastSuccessfulPattern;
        this.lastCaptureGroups = RuntimeRegex.lastCaptureGroups;
    }

    public void restore() {
        RuntimeRegex.globalMatcher = this.globalMatcher;
        RuntimeRegex.globalMatchString = this.globalMatchString;
        RuntimeRegex.lastMatchedString = this.lastMatchedString;
        RuntimeRegex.lastMatchStart = this.lastMatchStart;
        RuntimeRegex.lastMatchEnd = this.lastMatchEnd;
        RuntimeRegex.lastSuccessfulMatchedString = this.lastSuccessfulMatchedString;
        RuntimeRegex.lastSuccessfulMatchStart = this.lastSuccessfulMatchStart;
        RuntimeRegex.lastSuccessfulMatchEnd = this.lastSuccessfulMatchEnd;
        RuntimeRegex.lastSuccessfulMatchString = this.lastSuccessfulMatchString;
        RuntimeRegex.lastSuccessfulPattern = this.lastSuccessfulPattern;
        RuntimeRegex.lastCaptureGroups = this.lastCaptureGroups;
    }
}

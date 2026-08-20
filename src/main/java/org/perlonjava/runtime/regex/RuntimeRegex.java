package org.perlonjava.runtime.regex;

import org.joni.exception.SyntaxException;
import org.perlonjava.backend.bytecode.InterpreterState;
import org.perlonjava.runtime.WarningBitsRegistry;
import org.perlonjava.runtime.operators.PerlUtfString;
import org.perlonjava.runtime.operators.Time;
import org.perlonjava.runtime.operators.StringOperators;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.perlmodule.Utf8;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.perlonjava.runtime.regex.RegexFlags.fromModifiers;
import static org.perlonjava.runtime.regex.RegexFlags.validateModifiers;
import static org.perlonjava.runtime.regex.RegexQuoteMeta.escapeQ;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.getScalarInt;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/**
 * RuntimeRegex class to implement Perl's qr// operator for regular expression handling,
 * including support for regex modifiers like /i, /g, and /e.
 * This class provides methods to compile, cache, and apply regular expressions
 * with Perl-like syntax and behavior.
 */
public class RuntimeRegex extends RuntimeBase implements RuntimeScalarReference {

    /**
     * Keep a literal target's SV stable across repeated execution of one regex call site.
     * Perl stores pos() on the literal SV in the compiled optree; rematerializing it on
     * every loop iteration would restart /g at zero forever.
     */
    public static RuntimeScalar stabilizeLiteralTarget(RuntimeScalar literal, int callsiteId) {
        RuntimeRegexState state = PerlRuntime.current().regexState;
        RuntimeScalar stable = state.literalRegexTargets.get(callsiteId);
        if (stable == null) {
            stable = new RuntimeScalar(literal.toString());
            stable.type = literal.type;
            stable.tainted = literal.isTainted();
            state.literalRegexTargets.put(callsiteId, stable);
        }
        return stable;
    }

    /** Private AST/runtime modifier markers; removed before Perl modifier parsing. */
    public static final char INTERNAL_DEBUG_MARKER = '\u0001';
    public static final char INTERNAL_DEBUGCOLOR_MARKER = '\u0002';
    public static final char INTERNAL_RE_STRICT_MARKER = '\u0003';

    // Debug flag for regex compilation (set at class load time)
    private static final boolean DEBUG_REGEX = System.getenv("DEBUG_REGEX") != null;

    private static final Pattern USER_DEFINED_PROPERTY_PATTERN =
            Pattern.compile("\\\\([pP])\\{((?:[A-Za-z_][A-Za-z0-9_]*::)*(?:Is|In)[A-Za-z_][A-Za-z0-9_]*)}");
    private static final Pattern MALFORMED_USER_DEFINED_PROPERTY_PATTERN =
            Pattern.compile("(?:^|::)(?:Is|In)::");
    private static final Pattern UNICODE_PROPERTY_PATTERN =
            Pattern.compile("\\\\[pP]\\{");
    // Maximum size for each runtime's regex cache.
    private static final int MAX_REGEX_CACHE_SIZE = RuntimeRegexState.MAX_REGEX_CACHE_SIZE;
    // Literal CVs are shared by ithreads. A child runtime may populate its own
    // regex cache, but that must not look like a second compilation of the
    // already-compiled literal in re-debug output.
    private static final Set<String> REPORTED_DEBUG_COMPILATIONS =
            ConcurrentHashMap.newKeySet();

    private static String makeDelimitedTokenRepetitionsPossessive(String pattern) {
        Deque<DelimitedGroup> groups = new ArrayDeque<>();
        List<Integer> insertions = new ArrayList<>();
        boolean escaped = false;
        boolean inClass = false;

        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '[') {
                inClass = true;
                continue;
            }
            if (ch == ']' && inClass) {
                inClass = false;
                continue;
            }
            if (inClass) continue;

            if (ch == '(') {
                groups.push(new DelimitedGroup(i));
            } else if (ch == '|' && !groups.isEmpty()) {
                groups.peek().hasAlternation = true;
            } else if (ch == ')' && !groups.isEmpty()) {
                DelimitedGroup group = groups.pop();
                if (!group.hasAlternation || i + 1 >= pattern.length()
                        || pattern.charAt(i + 1) != '*') {
                    continue;
                }

                String body = pattern.substring(group.start, i + 1);
                char delimiter = body.contains("[^\"") || body.contains("[^\\\"") ? '"'
                        : body.contains("[^'") || body.contains("[^\\'") ? '\'' : 0;
                if (delimiter == 0) continue;

                int suffix = i + 2;
                while (suffix < pattern.length() && pattern.charAt(suffix) == ')') suffix++;
                if (suffix < pattern.length() && pattern.charAt(suffix) == delimiter) {
                    insertions.add(i + 2);
                }
            }
        }

        if (insertions.isEmpty()) return pattern;
        StringBuilder stackSafe = new StringBuilder(pattern);
        for (int i = insertions.size() - 1; i >= 0; i--) {
            stackSafe.insert(insertions.get(i).intValue(), '+');
        }
        return stackSafe.toString();
    }

    private static final class DelimitedGroup {
        final int start;
        boolean hasAlternation;

        DelimitedGroup(int start) {
            this.start = start;
        }
    }

    private static RuntimeRegexState state() {
        return PerlRuntime.current().regexState;
    }

    static void updateControlVerbVariables(String mark, String error) {
        RuntimeScalar markValue = mark == null
                ? RuntimeScalarCache.scalarEmptyString : new RuntimeScalar(mark);
        RuntimeScalar errorValue = error == null
                ? RuntimeScalarCache.scalarEmptyString : new RuntimeScalar(error);
        String currentPackage = InterpreterState.currentPackage.get().toString();
        if (currentPackage == null || currentPackage.isEmpty()) currentPackage = "main";
        GlobalVariable.getGlobalVariable(currentPackage + "::REGMARK").set(markValue);
        GlobalVariable.getGlobalVariable(currentPackage + "::REGERROR").set(errorValue);
        // Perl activates these otherwise ordinary package variables through
        // local(). Also update localized scalar identities directly because the
        // interpreter does not keep its runtime current-package facade
        // synchronized with every lexical package statement.
        for (Map.Entry<String, RuntimeScalar> entry
                : DynamicVariableManager.activeLocalizedGlobalScalars().entrySet()) {
            if (entry.getKey().endsWith("::REGMARK")) {
                entry.getValue().set(markValue);
            } else if (entry.getKey().endsWith("::REGERROR")) {
                entry.getValue().set(errorValue);
            }
        }
    }
    JoniRegexPattern recursivePattern;
    JoniRegexPattern recursivePatternUnicode;
    JoniRegexPattern recursivePatternBytes;
    private JoniRegexPattern.NamedCharacterCache namedCharacterCache;
    List<RuntimeRegexCallback> executableCallbacks = List.of();
    private boolean executableCallbacksReleased;
    public String patternString;
    // Source scalar provenance needed when a compiled qr// is reused under
    // lexical use bytes: byte-backed source characters are already octets.
    private boolean patternByteBacked;
    boolean hasPreservesMatch = false;  // True if /p was used (outer or inline (?p))
    // Indicates if \G assertion is used (set from regexFlags during compilation)
    private boolean useGAssertion = false;
    // Flags for regex behavior
    private RegexFlags regexFlags;
    // Replacement string for substitutions
    private RuntimeScalar replacement = null;
    // Lexical `use bytes` was active at this substitution call site.
    private boolean bytesSubstitution = false;
    // Caller's @_ for replacement code evaluation (so $_[0] etc. work in s/// replacement)
    private RuntimeArray callerArgs = null;
    // Tracks if a match has occurred: this is used as a counter for m?PAT?
    private boolean matched = false;
    private boolean hasCodeBlockCaptures = false;  // True if regex has (?{...}) code blocks
    private boolean deferredUserDefinedUnicodeProperties = false;
    // An empty qr// object keeps its own empty pattern when interpolated;
    // only empty match/substitution string syntax reuses the previous match.
    private boolean quoteConstruction = false;
    private List<String> warningsOnUse = new ArrayList<>();
    private List<String> inlineModifierWarnings = new ArrayList<>();
    // 0 = off, 1 = debug, 2 = debugcolor. Captured at the regex call site.
    private int lexicalDebugMode;
    private boolean lexicalReStrict;
    public RuntimeRegex() {
        this.regexFlags = null;
    }

    /**
     * Creates a tracked copy of this RuntimeRegex for use as a qr// value.
     * The copy shares compiled native regex objects but has its own refCount = 0,
     * enabling proper reference counting when assigned to user variables.
     * This mirrors Perl 5 where qr// always creates a new SV wrapper around
     * the shared compiled regex.
     */
    public RuntimeRegex cloneTracked() {
        RuntimeRegex copy = new RuntimeRegex();
        copy.recursivePattern = this.recursivePattern;
        copy.recursivePatternUnicode = this.recursivePatternUnicode;
        copy.recursivePatternBytes = this.recursivePatternBytes;
        copy.namedCharacterCache = this.namedCharacterCache;
        copy.setExecutableCallbacks(this.executableCallbacks);
        copy.patternString = this.patternString;
        copy.patternByteBacked = this.patternByteBacked;
        copy.hasPreservesMatch = this.hasPreservesMatch;
        copy.useGAssertion = this.useGAssertion;
        copy.regexFlags = this.regexFlags;
        copy.hasCodeBlockCaptures = this.hasCodeBlockCaptures;
        copy.deferredUserDefinedUnicodeProperties = this.deferredUserDefinedUnicodeProperties;
        copy.quoteConstruction = this.quoteConstruction;
        copy.warningsOnUse = new ArrayList<>(this.warningsOnUse);
        copy.inlineModifierWarnings = new ArrayList<>(this.inlineModifierWarnings);
        copy.lexicalDebugMode = this.lexicalDebugMode;
        copy.lexicalReStrict = this.lexicalReStrict;
        // replacement and callerArgs are not copied — they are set per-substitution
        // matched is not copied — each qr// object tracks its own m?PAT? state
        copy.refCount = 0;  // Enable refCount tracking
        return copy;
    }

    /** Returns the regex flags for this compiled pattern. */
    public RegexFlags getRegexFlags() {
        return regexFlags;
    }

    boolean isPatternByteBacked() {
        return patternByteBacked;
    }

    private void setExecutableCallbacks(List<RuntimeRegexCallback> callbacks) {
        List<RuntimeRegexCallback> retained = List.copyOf(callbacks);
        for (RuntimeRegexCallback callback : retained) callback.retainOwner();
        executableCallbacks = retained;
        executableCallbacksReleased = false;
    }

    /** Release closure captures owned by this tracked qr// value. */
    public void releaseExecutableCallbacks() {
        if (executableCallbacksReleased || executableCallbacks.isEmpty()) return;
        executableCallbacksReleased = true;
        for (RuntimeRegexCallback callback : executableCallbacks) callback.releaseOwner();
    }

    /**
     * Create a backend-neutral matcher for callers that implement Perl
     * operations around regex matches (for example {@code split}).
     */
    public RegexMatcher matcher(RuntimeScalar string, String input) {
        return selectRecursivePattern(string).matcher(input, executableCallbacks, string);
    }

    private JoniRegexPattern selectRecursivePattern(RuntimeScalar string) {
        boolean byteDefaultSemantics = patternByteBacked && regexFlags != null
                && regexFlags.isCaseInsensitive() && !regexFlags.isUnicode()
                && !regexFlags.isAscii() && !hasInlineUnicodeCaseFoldModifier(patternString);
        if ((bytesSubstitution || byteDefaultSemantics) && recursivePatternBytes != null
                && !Utf8.isUtf8(string)) {
            return recursivePatternBytes;
        }
        if (recursivePatternUnicode != null && recursivePatternUnicode != recursivePattern
                && regexFlags != null && !regexFlags.isAscii() && Utf8.isUtf8(string)) {
            return recursivePatternUnicode;
        }
        return recursivePattern;
    }

    /** The Perl source pattern, before backend-specific translation. */
    public String sourcePattern() {
        return patternString;
    }

    private void emitWarningsOnUse() {
        // These warnings belong to the regex use site, not the earlier qr//
        // construction site. The active Perl code supplies the baseline lexical
        // warning bits.  Each retained diagnostic keeps its Perl warning
        // category; WarnDie additionally applies a narrower runtime lexical
        // scope at the individual match site.
        String activeCodeBits = WarningBitsRegistry.getCurrent();
        for (String warning : warningsOnUse) {
            String category = RegexQuoteMeta.warningCategory(warning);
            if (!WarningFlags.areWarningsForcedOn()
                    && InterpreterState.current() == null
                    && activeCodeBits != null
                    && !WarningFlags.isEnabledInBits(activeCodeBits, category)) {
                continue;
            }
            WarnDie.warnWithCategory(new RuntimeScalar(warning),
                    RuntimeScalarCache.scalarEmptyString, category);
        }
    }

    /**
     * Perl accepts scalar values above Unicode's maximum code point. Applying
     * a Unicode property to one emits a use-site {@code non_unicode} warning,
     * even though the value is carried internally as a Java-safe marker.
     */
    private void emitNonUnicodePropertyWarning(RuntimeScalar subject, String input) {
        if (patternString == null || input == null
                || !UNICODE_PROPERTY_PATTERN.matcher(patternString).find()) {
            return;
        }
        if (recursivePattern != null
                && selectRecursivePattern(subject)
                        .hasOnlyAuthoritativeWideCharacterClasses()) {
            return;
        }
        for (int offset = 0; offset < input.length(); ) {
            PerlUtfString.PerlStep step = PerlUtfString.readOnePerlLogical(input, offset);
            if (Long.compareUnsigned(step.codePoint(), 0x10FFFFL) > 0) {
                String warning = "Matched non-Unicode code point 0x"
                        + Long.toUnsignedString(step.codePoint(), 16).toUpperCase(java.util.Locale.ROOT)
                        + " against Unicode property; may not be portable";
                // A match warning belongs to the statement executing the
                // match, not to RuntimeRegex's Java helper frame. The JVM
                // backend records that lexical state as call-site bits; make
                // them the direct warning context for this synchronous call.
                String previousRuntimeBits = WarningBitsRegistry.getRuntimeWarningBits();
                String callSiteBits = WarningBitsRegistry.getCallSiteBits();
                if (callSiteBits != null) {
                    WarningBitsRegistry.setRuntimeWarningBits(callSiteBits);
                }
                try {
                    WarnDie.warnWithCategory(
                            new RuntimeScalar(warning), RuntimeScalarCache.scalarEmptyString,
                            "non_unicode");
                } finally {
                    WarningBitsRegistry.setRuntimeWarningBits(previousRuntimeBits);
                }
                return;
            }
            offset = step.nextJavaIndex();
        }
    }

    /**
     * Compiles a regex pattern string with optional modifiers into a RuntimeRegex object.
     *
     * @param patternString The regex pattern string with optional modifiers.
     * @param modifiers     Modifiers for the regex pattern (e.g., "i", "g").
     * @return A RuntimeRegex object.
     * @throws IllegalStateException if regex compilation fails.
     */
    public static RuntimeRegex compile(String patternString, String modifiers) {
        return compile(patternString, modifiers, debugMode(modifiers));
    }

    private static RuntimeRegex compile(String patternString, String modifiers, int lexicalDebugMode) {
        return compile(patternString, modifiers, lexicalDebugMode, 0, false);
    }

    private static RuntimeRegex compile(String patternString, String modifiers, int lexicalDebugMode,
                                        int trustedCalloutCount) {
        return compile(patternString, modifiers, lexicalDebugMode, trustedCalloutCount, false);
    }

    private static RuntimeRegex compile(String patternString, String modifiers, int lexicalDebugMode,
                                        int trustedCalloutCount, boolean patternByteBacked) {
        return compile(patternString, modifiers, lexicalDebugMode, trustedCalloutCount,
                patternByteBacked, reStrictMode(modifiers));
    }

    private static RuntimeRegex compile(String patternString, String modifiers, int lexicalDebugMode,
                                        int trustedCalloutCount, boolean patternByteBacked,
                                        boolean lexicalReStrict) {
        RuntimeScalar namedCharacterTranslator =
                org.perlonjava.runtime.HintHashRegistry.getCompileTimeHint("charnames");
        modifiers = stripInternalMarkers(modifiers);
        // Dynamic/interpolated qr// compilation can begin during ordinary
        // execution, outside the Perl compiler lock. User-defined Unicode
        // properties execute arbitrary Perl and may block, so resolve them
        // before entering the process-wide regex compiler monitor. Calls made
        // during source compilation remain deferred by UnicodeResolver.
        RegexFlags preloadFlags = fromModifiers(modifiers, patternString);
        UnicodeResolver.preloadUserDefinedProperties(
                patternString, preloadFlags.isCaseInsensitive());
        return compileSynchronized(patternString, modifiers, lexicalDebugMode,
                trustedCalloutCount, false, patternByteBacked, lexicalReStrict,
                namedCharacterTranslator);
    }

    /** User properties execute Perl code and therefore cannot be validated while compiling a CV. */
    public static boolean requiresRuntimeUnicodePropertyResolution(String patternString) {
        if (patternString == null) return false;
        return java.util.regex.Pattern.compile(
                "\\\\[pP]\\{\\^?\\s*(?:[A-Za-z_][A-Za-z0-9_]*::)*(?:Is|In)[A-Za-z_][A-Za-z0-9_]*\\s*}")
                .matcher(patternString).find();
    }

    /**
     * Validate literal syntax without executing user-property callbacks.
     *
     * <p>CV compilation must reject malformed literals, but it must not turn a
     * valid Perl feature that this backend cannot yet execute into an early
     * compile-time fatal. Runtime compilation retains the existing
     * JPERL_UNIMPLEMENTED policy for those patterns.</p>
     */
    public static void validateLiteralSyntax(String patternString, String modifiers) {
        try {
            compileSynchronized(patternString, stripInternalMarkers(modifiers),
                    debugMode(modifiers), 0, true, false, reStrictMode(modifiers),
                    org.perlonjava.runtime.HintHashRegistry.getCompileTimeHint("charnames"));
        } catch (PerlJavaUnimplementedException unsupported) {
            String message = unsupported.getMessage();
            if (message != null && (message.contains("premature end of char-class")
                    || message.contains("Unclosed character class"))) {
                throw new PerlCompilerException(
                        unmatchedCharacterClassDiagnostic(patternString) + "\n");
            }
            if (message != null && (message.contains("Unclosed group")
                    || message.contains("Dangling meta character")
                    || message.contains("Unmatched closing")
                    || message.contains("Illegal repetition"))) {
                throw new PerlCompilerException(message);
            }
        }
    }

    private static synchronized RuntimeRegex compileSynchronized(
            String patternString, String modifiers, int lexicalDebugMode,
            int trustedCalloutCount, boolean literalSyntaxValidation,
            boolean patternByteBacked, boolean lexicalReStrict,
            RuntimeScalar namedCharacterTranslator) {
        // Debug logging
        if (DEBUG_REGEX) {
            System.err.println("RuntimeRegex.compile: pattern=" + patternString + " modifiers=" + modifiers);
            System.err.println("  caller stack: " + Thread.currentThread().getStackTrace()[2]);
        }

        boolean literalUselessCaseEscape = patternString != null
                && patternString.contains(RegexMarkers.LITERAL_USELESS_CASE_ESCAPE);
        String literalFrontendDiagnostic =
                RegexMarkers.firstLiteralDiagnostic(patternString);
        String originalPatternString = RegexMarkers.stripLiteralDiagnostics(patternString);
        if (literalUselessCaseEscape) {
            originalPatternString = originalPatternString.replace(
                    RegexMarkers.LITERAL_USELESS_CASE_ESCAPE, "");
        }
        String compilePatternString = originalPatternString;
        List<String> quoteMetaWarningsOnUse = new ArrayList<>();
        if (compilePatternString != null && compilePatternString.contains("\\Q")) {
            // Interpolated-pattern warnings are lexical diagnostics for each
            // construction, even when the compiled regex itself is cached.
            compilePatternString = escapeQ(compilePatternString);
            quoteMetaWarningsOnUse = RegexQuoteMeta.getWarningsOnUse();
        }

        // Lexical regex debugging changes the compiled representation.
        // A lexical charname translator may return a different expansion for
        // each compilation of the same spelling. Literal syntax validation is
        // the first leg of one logical compilation: refresh the raw-source
        // cache entry there, then let the runtime leg reuse that exact result.
        boolean refreshLexicalNamedCharacter = literalSyntaxValidation
                && org.perlonjava.runtime.NamedCharacterExpansion
                        .usesCustomTranslator(namedCharacterTranslator)
                && patternString != null
                && patternString.contains("\\N{");
        boolean effectivePatternByteBacked = patternByteBacked
                && !hasUnicodePromotingPatternSyntax(patternString);
        String cacheKey = patternString + "/" + modifiers
                + "#debug=" + lexicalDebugMode
                + "#callouts=" + trustedCalloutCount
                + "#bytepattern=" + effectivePatternByteBacked
                + "#strict=" + lexicalReStrict
                + (namedCharacterTranslator == null ? "" : "#charnames="
                        + namedCharacterTranslator.toString());

        // Check if the regex is already cached
        RuntimeRegex regex = refreshLexicalNamedCharacter
                ? null : state().compiledRegexCache.get(cacheKey);
        if (regex == null) {
            if (DEBUG_REGEX) {
                System.err.println("  cache miss, compiling new regex");
            }
            regex = new RuntimeRegex();
            regex.patternByteBacked = effectivePatternByteBacked;
            regex.namedCharacterCache =
                    new JoniRegexPattern.NamedCharacterCache(namedCharacterTranslator);
            regex.lexicalDebugMode = lexicalDebugMode;
            regex.lexicalReStrict = lexicalReStrict;

            // Note: flags /e /ee are processed at parse time, in parseRegexReplace()

            validateModifiers(modifiers);

            regex.regexFlags = fromModifiers(modifiers, compilePatternString);
            regex.useGAssertion = regex.regexFlags.useGAssertion();

            LeftBraceIssue leftBraceIssue = unescapedLeftBraceIssue(
                    originalPatternString);
            String sourcePolicyWarning = null;
            String constructionPolicyWarning = literalUselessCaseEscape
                    ? "Useless use of \\E"
                    : null;
            if (leftBraceIssue != null) {
                String message = leftBraceIssue.alwaysFatal || lexicalReStrict
                        ? "Unescaped left brace in regex is illegal here"
                        : "Unescaped left brace in regex is passed through";
                String diagnostic = RegexDiagnosticFormatter.markedPerl(
                        originalPatternString, leftBraceIssue.offset + 1, message);
                if (leftBraceIssue.alwaysFatal || lexicalReStrict) {
                    throw new PerlCompilerException(diagnostic);
                }
                sourcePolicyWarning = diagnostic;
            }
            NonHexIssue nonHexIssue = nonHexEscapeIssue(originalPatternString);
            if (nonHexIssue != null) {
                if (lexicalReStrict) {
                    throw new PerlCompilerException(RegexDiagnosticFormatter.markedPerl(
                            originalPatternString, nonHexIssue.offset + 1,
                            "Non-hex character"));
                }
                if (!nonHexIssue.braced) {
                    char invalid = originalPatternString.charAt(nonHexIssue.offset);
                    char digit = originalPatternString.charAt(nonHexIssue.offset - 1);
                    String message = "Non-hex character '" + invalid
                            + "' terminates \\x early.  Resolved as \"\\x0"
                            + digit + invalid + "\"";
                    constructionPolicyWarning = RegexDiagnosticFormatter.markedPerl(
                            originalPatternString, nonHexIssue.offset, message);
                }
            }
            
            try {
                // All executable regexes use the native Joni compiler.  The
                // remaining Java cache construction below is retired in the
                // next cutover step; this flag keeps that mechanical removal
                // separate from the native-policy transition.
                if (compilePatternString.contains("(?&")
                        && (compilePatternString.contains("(?<=")
                                || compilePatternString.contains("(?<!"))) {
                    throw new PerlJavaUnimplementedException(
                            "Lookbehind longer than 255 not implemented in regex m/"
                                    + compilePatternString + "/");
                }
                regex.warningsOnUse = new ArrayList<>(quoteMetaWarningsOnUse);
                if (sourcePolicyWarning != null) {
                    regex.inlineModifierWarnings.add(sourcePolicyWarning);
                    regex.warningsOnUse.add(sourcePolicyWarning);
                }
                if (constructionPolicyWarning != null) {
                    regex.inlineModifierWarnings.add(constructionPolicyWarning);
                }
                regex.recursivePattern = new JoniRegexPattern(compilePatternString,
                            regex.regexFlags, trustedCalloutCount,
                            !regex.regexFlags.isUnicode(), false, false,
                            regex.namedCharacterCache);
                    regex.recursivePatternUnicode = regex.regexFlags.isAscii()
                            ? regex.recursivePattern
                            : new JoniRegexPattern(compilePatternString,
                                    regex.regexFlags, trustedCalloutCount, false,
                                    false, false, regex.namedCharacterCache);
                    if (effectivePatternByteBacked && regex.regexFlags.isCaseInsensitive()
                            && !regex.regexFlags.isUnicode() && !regex.regexFlags.isAscii()) {
                        regex.recursivePatternBytes = new JoniRegexPattern(
                                compilePatternString, regex.regexFlags, trustedCalloutCount,
                                true, true, true, regex.namedCharacterCache);
                    }
                    regex.deferredUserDefinedUnicodeProperties =
                            regex.recursivePattern.hasDeferredUserDefinedUnicodeProperty()
                                    || regex.recursivePatternUnicode
                                            .hasDeferredUserDefinedUnicodeProperty();
                    regex.inlineModifierWarnings.addAll(normalizeNonHexWarningCase(
                            regex.recursivePattern.compileWarnings(),
                            originalPatternString, nonHexIssue));
                    regex.warningsOnUse.addAll(regex.inlineModifierWarnings);
                regex.hasPreservesMatch = regex.regexFlags.preservesMatch()
                        || RegexFlags.hasInlinePreserveModifier(compilePatternString);
                regex.patternString = originalPatternString;

                // Check if pattern has code block captures for $^R optimization
                // Code blocks are encoded as named captures like (?<cb010...>)
                Map<String, Integer> namedGroups = regex.recursivePattern.namedGroups();
                if (namedGroups != null) {
                    for (String groupName : namedGroups.keySet()) {
                        if (CaptureNameEncoder.isCodeBlockCapture(groupName)) {
                            regex.hasCodeBlockCaptures = true;
                            break;
                        }
                    }
                }

            } catch (Exception e) {
                if (literalFrontendDiagnostic != null) {
                    String backendDiagnostic = e.getMessage();
                    if (backendDiagnostic != null
                            && backendDiagnostic.startsWith("Unknown charname '")
                            && literalFrontendDiagnostic.startsWith("Unknown charname '")) {
                        // Literal source validation and native compilation are
                        // two legs of one compile. Perl reports the lexical
                        // source diagnostic once, not again as a backend error.
                        throw new PerlCompilerException(literalFrontendDiagnostic + "\n");
                    }
                    if (backendDiagnostic != null
                            && backendDiagnostic.startsWith("Unknown charname '")
                            && !backendDiagnostic.endsWith(" in regex")) {
                        backendDiagnostic += " in regex";
                    }
                    throw new PerlCompilerException(literalFrontendDiagnostic
                            + "\n" + backendDiagnostic);
                }
                if (e instanceof PatternSyntaxException syntaxError
                        && "Illegal character range".equals(syntaxError.getDescription())) {
                    throw new PerlCompilerException("Invalid [] range");
                }
                if ("invalid backref number/name".equals(e.getMessage())
                        || "invalid backref number".equals(e.getMessage())) {
                    throw new PerlCompilerException(
                            "Reference to nonexistent group in regex");
                }
                if (e instanceof IllegalArgumentException
                        && e.getMessage() != null
                        && e.getMessage().startsWith("Unknown charname '")
                        && !e.getMessage().endsWith(" in regex")) {
                    throw new PerlCompilerException(e.getMessage() + " in regex");
                }
                String invalidProperty = invalidUnicodePropertyName(e.getMessage());
                if (invalidProperty != null) {
                    if (MALFORMED_USER_DEFINED_PROPERTY_PATTERN.matcher(invalidProperty).find()) {
                        throw new PerlCompilerException(
                                "Illegal user-defined property name \"" + invalidProperty + "\"");
                    }
                    throw new PerlCompilerException(
                            "Can't find Unicode property definition \"" + invalidProperty + "\"");
                }
                // Joni reports malformed patterns with SyntaxException (including its
                // ValueException subclass). These are real compile errors, not missing
                // PerlOnJava features, so JPERL_UNIMPLEMENTED=warn must not downgrade them.
                boolean validatesExecutableSource = literalSyntaxValidation
                        && containsExecutableSource(originalPatternString,
                                regex.regexFlags.isExtended());
                if (e instanceof SyntaxException && !validatesExecutableSource) {
                    String message = e.getMessage();
                    if ("unmatched close parenthesis".equals(message)) {
                        message = "Unmatched )";
                    }
                    if ("Empty \\N{}".equals(message)) {
                        throw new PerlCompilerException("Unknown charname ''");
                    }
                    if (message != null
                            && (message.contains("premature end of char-class")
                                    || message.contains("Unclosed character class"))) {
                        String diagnostic = unmatchedCharacterClassDiagnostic(originalPatternString);
                        if (literalSyntaxValidation) {
                            diagnostic += "\n";
                        }
                        throw new PerlCompilerException(diagnostic);
                    }
                    int bytePosition = ((SyntaxException) e).getPatternPosition();
                    if (bytePosition != SyntaxException.UNKNOWN_PATTERN_POSITION) {
                        int characterPosition = utf8ByteOffsetToCharacterOffset(
                                compilePatternString, bytePosition);
                        if ("undefined group option".equals(message)
                                && originalPatternString != null
                                && characterPosition >= 3
                                && originalPatternString.regionMatches(
                                        characterPosition - 3, "(?\\", 0, 3)) {
                            message = "Sequence (?\\...) not recognized";
                        } else if ("too big number for repeat range".equals(message)) {
                            message = "Quantifier in {,} bigger than 2147483646";
                        } else if ("end pattern with unmatched parenthesis".equals(message)) {
                            int unmatched = ordinaryUnmatchedOpeningParenthesis(
                                    originalPatternString);
                            if (unmatched >= 0) {
                                message = "Unmatched (";
                                characterPosition = unmatched + 1;
                            }
                        }
                        throw new PerlCompilerException(RegexDiagnosticFormatter.markedPerl(
                                originalPatternString, characterPosition, message));
                    }
                    throw new PerlCompilerException(message);
                }
                // PerlJavaUnimplementedException extends PerlCompilerException, so check
                // the more specific type first. Real syntax errors (PerlCompilerException
                // but NOT PerlJavaUnimplementedException) are always fatal.
                // Java PatternSyntaxException etc. are wrapped as unimplemented.
                boolean isUnimplemented = e instanceof PerlJavaUnimplementedException;
                boolean isRealSyntaxError = !isUnimplemented && e instanceof PerlCompilerException;

                if (isRealSyntaxError) {
                    throw (PerlCompilerException) e;
                }

                // Wrap non-Perl exceptions (PatternSyntaxException etc.) as unimplemented
                PerlJavaUnimplementedException unimplEx;
                if (isUnimplemented) {
                    unimplEx = (PerlJavaUnimplementedException) e;
                } else {
                    unimplEx = new PerlJavaUnimplementedException("Regex compilation failed: " + e.getMessage());
                }

                // With JPERL_UNIMPLEMENTED=warn, downgrade to warning and use a never-matching pattern
                if (GlobalVariable.getGlobalHash("main::ENV").get("JPERL_UNIMPLEMENTED").toString().equals("warn")) {
                    String base = unimplEx.getMessage();
                    // Include original and preprocessed patterns to aid debugging
                    String patternInfo = " [pattern='" + (originalPatternString == null ? "" : originalPatternString) + "']";
                    String errorMessage = base + patternInfo;
                    // Ensure error message ends with newline to prevent running into test output
                    if (!errorMessage.endsWith("\n")) {
                        errorMessage += "\n";
                    }
                    WarnDie.warn(new RuntimeScalar(errorMessage), new RuntimeScalar());
                    regex.recursivePattern = new JoniRegexPattern("(?!)", regex.regexFlags);
                    regex.recursivePatternUnicode = regex.recursivePattern;
                    // Ensure patternString is set so downstream code doesn't NPE
                    if (regex.patternString == null) {
                        regex.patternString = originalPatternString != null ? originalPatternString : "";
                    }
                } else {
                    throw unimplEx;
                }
            }

            // Cache the result if the cache is not full
            if (state().compiledRegexCache.size() < MAX_REGEX_CACHE_SIZE
                    || state().compiledRegexCache.containsKey(cacheKey)) {
                state().compiledRegexCache.put(cacheKey, regex);
            }
            if (lexicalDebugMode != 0 && REPORTED_DEBUG_COMPILATIONS.add(cacheKey)) {
                regex.emitCompileDebugTrace();
            }
        } else {
            // Debug logging for cache hit
            if (DEBUG_REGEX) {
                System.err.println("  cache hit, reusing cached regex");
            }
        }
        return regex;
    }

    private static String unmatchedCharacterClassDiagnostic(String pattern) {
        int open = pattern == null ? -1 : pattern.indexOf('[');
        return RegexDiagnosticFormatter.markedPerl(
                pattern, open < 0 ? 0 : open + 1, "Unmatched [");
    }

    private static String invalidUnicodePropertyName(String message) {
        if (message == null) return null;
        String prefix = "invalid character property name <";
        int start = message.indexOf(prefix);
        if (start < 0) return null;
        start += prefix.length();
        int end = message.indexOf('>', start);
        return end < 0 ? null : message.substring(start, end);
    }

    private static int utf8ByteOffsetToCharacterOffset(String pattern, int byteOffset) {
        if (pattern == null || byteOffset <= 0) return 0;
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        int boundedOffset = Math.min(byteOffset, bytes.length);
        return new String(bytes, 0, boundedOffset, StandardCharsets.UTF_8).length();
    }

    /** Locate an unmatched ordinary group opener, excluding Perl's specialized {@code (?...} forms. */
    private static int ordinaryUnmatchedOpeningParenthesis(String pattern) {
        if (pattern == null) return -1;
        Deque<Integer> openings = new ArrayDeque<>();
        boolean escaped = false;
        boolean inClass = false;
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '[') {
                inClass = true;
                continue;
            }
            if (ch == ']' && inClass) {
                inClass = false;
                continue;
            }
            if (inClass) continue;
            if (ch == '(') {
                openings.push(i);
            } else if (ch == ')' && !openings.isEmpty()) {
                openings.pop();
            }
        }
        while (!openings.isEmpty()) {
            int opening = openings.removeLast();
            if (opening + 1 >= pattern.length() || pattern.charAt(opening + 1) != '?') {
                return opening;
            }
        }
        return -1;
    }

    private static LeftBraceIssue unescapedLeftBraceIssue(String pattern) {
        if (pattern == null || pattern.isEmpty()) return null;
        boolean escaped = false;
        boolean inClass = false;
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '[') {
                inClass = true;
                continue;
            }
            if (ch == ']' && inClass) {
                inClass = false;
                continue;
            }
            if (inClass || ch != '{') continue;
            if (isEscapeArgumentBrace(pattern, i)
                    || isValidQuantifier(pattern, i)
                    || isAllowedLiteralLeftBrace(pattern, i)) {
                continue;
            }
            boolean followsAlphanumericEscape = i >= 2
                    && Character.isLetterOrDigit(pattern.charAt(i - 1))
                    && pattern.charAt(i - 2) == '\\'
                    && (i < 3 || pattern.charAt(i - 3) != '\\');
            return new LeftBraceIssue(i, followsAlphanumericEscape);
        }
        return null;
    }

    private static boolean isEscapeArgumentBrace(String pattern, int offset) {
        if (offset < 2 || pattern.charAt(offset - 2) != '\\'
                || (offset >= 3 && pattern.charAt(offset - 3) == '\\')) {
            return false;
        }
        return "pPxXoONkgbB".indexOf(pattern.charAt(offset - 1)) >= 0;
    }

    private static boolean isValidQuantifier(String pattern, int offset) {
        int cursor = offset + 1;
        while (cursor < pattern.length()
                && isPerlIntervalWhitespace(pattern.charAt(cursor))) cursor++;
        int digitStart = cursor;
        while (cursor < pattern.length() && Character.isDigit(pattern.charAt(cursor))) {
            cursor++;
        }
        boolean hasLow = cursor > digitStart;
        while (cursor < pattern.length()
                && isPerlIntervalWhitespace(pattern.charAt(cursor))) cursor++;
        if (cursor < pattern.length() && pattern.charAt(cursor) == ',') {
            cursor++;
            while (cursor < pattern.length()
                    && isPerlIntervalWhitespace(pattern.charAt(cursor))) cursor++;
            int highStart = cursor;
            while (cursor < pattern.length() && Character.isDigit(pattern.charAt(cursor))) {
                cursor++;
            }
            boolean hasHigh = cursor > highStart;
            while (cursor < pattern.length()
                    && isPerlIntervalWhitespace(pattern.charAt(cursor))) cursor++;
            return (hasLow || hasHigh) && cursor < pattern.length()
                    && pattern.charAt(cursor) == '}';
        }
        return hasLow && cursor < pattern.length() && pattern.charAt(cursor) == '}';
    }

    private static boolean isPerlIntervalWhitespace(char ch) {
        return ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\f';
    }

    private static boolean isAllowedLiteralLeftBrace(String pattern, int offset) {
        if (offset == 0) return true;
        char previous = pattern.charAt(offset - 1);
        if (previous == '^' || previous == '|' || previous == '(' || previous == '*'
                || previous == '+' || previous == '?') {
            return true;
        }
        if (offset >= 3 && (pattern.regionMatches(offset - 3, "(?:", 0, 3)
                || pattern.regionMatches(offset - 3, "(:?", 0, 3))) {
            return true;
        }
        if (previous != '}') return false;
        int opening = pattern.lastIndexOf('{', offset - 2);
        return opening >= 0 && isValidQuantifier(pattern, opening)
                && pattern.indexOf('}', opening + 1) == offset - 1;
    }

    private static final class LeftBraceIssue {
        final int offset;
        final boolean alwaysFatal;

        LeftBraceIssue(int offset, boolean alwaysFatal) {
            this.offset = offset;
            this.alwaysFatal = alwaysFatal;
        }
    }

    private static NonHexIssue nonHexEscapeIssue(String pattern) {
        if (pattern == null || pattern.contains("(?[")) return null;
        for (int i = 0; i + 2 < pattern.length(); i++) {
            if (pattern.charAt(i) != '\\' || pattern.charAt(i + 1) != 'x'
                    || (i > 0 && pattern.charAt(i - 1) == '\\')) {
                continue;
            }
            int cursor = i + 2;
            if (pattern.charAt(cursor) != '{') {
                if (isHexDigit(pattern.charAt(cursor))
                        && cursor + 1 < pattern.length()
                        && Character.isLetterOrDigit(pattern.charAt(cursor + 1))
                        && !isHexDigit(pattern.charAt(cursor + 1))) {
                    return new NonHexIssue(cursor + 1, false);
                }
                continue;
            }
            int close = pattern.indexOf('}', cursor + 1);
            if (close < 0) continue;
            cursor++;
            while (cursor < close && pattern.charAt(cursor) == ' ') cursor++;
            int digits = cursor;
            while (cursor < close) {
                if (isHexDigit(pattern.charAt(cursor))) {
                    cursor++;
                    continue;
                }
                if (pattern.charAt(cursor) == '_' && cursor > digits
                        && cursor + 1 < close
                        && isHexDigit(pattern.charAt(cursor + 1))) {
                    cursor++;
                    continue;
                }
                break;
            }
            if (cursor == digits || cursor == close) {
                i = close;
                continue;
            }
            if (pattern.charAt(cursor) != ' ') return new NonHexIssue(cursor, true);
            int whitespace = cursor;
            while (cursor < close && pattern.charAt(cursor) == ' ') cursor++;
            if (cursor < close) return new NonHexIssue(whitespace, true);
            i = close;
        }
        return null;
    }

    private static final class NonHexIssue {
        final int offset;
        final boolean braced;

        NonHexIssue(int offset, boolean braced) {
            this.offset = offset;
            this.braced = braced;
        }
    }

    private static List<String> normalizeNonHexWarningCase(
            List<String> warnings, String pattern, NonHexIssue issue) {
        if (issue == null || !issue.braced || warnings.isEmpty()) return warnings;
        int opening = pattern.lastIndexOf('{', issue.offset);
        if (opening < 0) return warnings;
        String sourceDigits = pattern.substring(opening + 1, issue.offset).trim();
        if (sourceDigits.isEmpty()) return warnings;
        List<String> normalized = new ArrayList<>(warnings.size());
        String prefix = "Resolved as \"\\x{";
        for (String warning : warnings) {
            int valueStart = warning.indexOf(prefix);
            int valueEnd = valueStart < 0 ? -1 : warning.indexOf('}', valueStart + prefix.length());
            if (valueEnd >= 0) {
                String resolved = warning.substring(valueStart + prefix.length(), valueEnd);
                if (resolved.equalsIgnoreCase(sourceDigits)) {
                    warning = warning.substring(0, valueStart + prefix.length())
                            + sourceDigits + warning.substring(valueEnd);
                }
            }
            normalized.add(warning);
        }
        return normalized;
    }

    private static boolean isHexDigit(char ch) {
        return ch >= '0' && ch <= '9' || ch >= 'a' && ch <= 'f'
                || ch >= 'A' && ch <= 'F';
    }

    private static int debugMode(String modifiers) {
        if (modifiers == null) return 0;
        if (modifiers.indexOf(INTERNAL_DEBUGCOLOR_MARKER) >= 0) return 2;
        return modifiers.indexOf(INTERNAL_DEBUG_MARKER) >= 0 ? 1 : 0;
    }

    private static boolean reStrictMode(String modifiers) {
        return modifiers != null && modifiers.indexOf(INTERNAL_RE_STRICT_MARKER) >= 0;
    }

    private static String stripInternalMarkers(String modifiers) {
        if (modifiers == null || modifiers.isEmpty()) return modifiers == null ? "" : modifiers;
        return modifiers.replace(String.valueOf(INTERNAL_DEBUG_MARKER), "")
                .replace(String.valueOf(INTERNAL_DEBUGCOLOR_MARKER), "")
                .replace(String.valueOf(INTERNAL_RE_STRICT_MARKER), "");
    }

    private void emitCompileDebugTrace() {
        if (lexicalDebugMode == 0) return;
        registerDebugLifecycle();
        String patternDescription = patternString == null ? "" : patternString;
        debugWrite("Compiling REx \"" + patternDescription + "\"\n"
                + "Final program:\n"
                + "   1: JAVA_PATTERN <" + patternDescription + "> (2)\n"
                + "   2: END (0)\n");
    }

    public void emitExecutionDebugTrace(String input) {
        if (lexicalDebugMode == 0) return;
        registerDebugLifecycle();
        StringBuilder trace = new StringBuilder(Math.max(96, input.length() * 72));
        trace.append("Matching REx \"").append(patternString == null ? "" : patternString)
                .append("\" against input of length ").append(input.length()).append('\n');
        // Java's matcher does not publish its start-class instruction stream.
        // Report the three observable stages of consuming each candidate input
        // position: reading it, testing it, and advancing to the next position.
        for (int offset = 0; offset < input.length(); offset++) {
            trace.append("  ").append(offset).append(": READ U+")
                    .append(String.format("%04X", (int) input.charAt(offset))).append('\n')
                    .append("  ").append(offset).append(": TEST START_CLASS\n")
                    .append("  ").append(offset).append(": ADVANCE\n");
        }
        debugWrite(trace.toString());
    }

    private void registerDebugLifecycle() {
        List<RuntimeRegex> active = state().activeDebugRegexes;
        for (RuntimeRegex regex : active) {
            if (regex == this) return;
        }
        active.add(this);
    }

    /** Emit Perl-style lifecycle records after END and before runtime teardown. */
    public static void emitCurrentRuntimeDebugFreeTraces() {
        List<RuntimeRegex> active = state().activeDebugRegexes;
        for (RuntimeRegex regex : active) {
            if (regex.lexicalDebugMode == 0) continue;
            String patternDescription = regex.patternString == null ? "" : regex.patternString;
            regex.debugWrite("Freeing REx: \"" + patternDescription + "\"\n");
        }
        active.clear();
    }

    private void debugWrite(String message) {
        if (lexicalDebugMode == 2) {
            message = "\u001b[36m" + message + "\u001b[0m";
        }
        RuntimeIO.getStderr().write(message);
        RuntimeIO.getStderr().flush();
    }

    private static RuntimeRegex ensureCompiledForRuntime(RuntimeRegex regex) {
        if (!regex.deferredUserDefinedUnicodeProperties) {
            return regex;
        }

        // Recompile once, now that runtime may have defined user properties.
        // To avoid infinite loops if recompilation still can't resolve, clear the flag first.
        regex.deferredUserDefinedUnicodeProperties = false;

        // Evict the old cached entry so compile() will actually recompile
        // instead of returning the stale regex with deferred placeholders.
        String cacheKey = regex.patternString + "/" + (regex.regexFlags == null ? "" : regex.regexFlags.toFlagString());
        // Deferred Unicode properties cannot contain executable callouts, so
        // this is the same zero-callout key used by compile(). Keep the suffix
        // in sync with compileSynchronized(): leaving the placeholder cached
        // makes a later qr/\\p{Property}/ reuse its match-any stand-in.
        state().compiledRegexCache.remove(cacheKey + "#debug=" + regex.lexicalDebugMode
                + "#callouts=0"
                + "#bytepattern=" + regex.patternByteBacked
                + "#strict=" + regex.lexicalReStrict);

        // User property subs can execute arbitrary Perl and block. Resolve them
        // before compile() takes its process-wide monitor; only simultaneous
        // definitions of the same property coordinate in PerlThreadRegistry.
        UnicodeResolver.preloadDeferredUserDefinedProperties(regex.patternString,
                regex.regexFlags != null && regex.regexFlags.isCaseInsensitive());
        RuntimeRegex recompiled = compile(regex.patternString,
                regex.regexFlags == null ? "" : regex.regexFlags.toFlagString(),
                regex.lexicalDebugMode, 0, regex.patternByteBacked,
                regex.lexicalReStrict);
        regex.recursivePattern = recompiled.recursivePattern;
        regex.recursivePatternUnicode = recompiled.recursivePatternUnicode;
        regex.recursivePatternBytes = recompiled.recursivePatternBytes;
        regex.regexFlags = recompiled.regexFlags;
        regex.useGAssertion = recompiled.useGAssertion;
        regex.deferredUserDefinedUnicodeProperties = recompiled.deferredUserDefinedUnicodeProperties;
        regex.warningsOnUse = new ArrayList<>(recompiled.warningsOnUse);
        regex.inlineModifierWarnings = new ArrayList<>(recompiled.inlineModifierWarnings);
        regex.lexicalDebugMode = recompiled.lexicalDebugMode;
        regex.lexicalReStrict = recompiled.lexicalReStrict;
        return regex;
    }

    private static String findTopLevelRequiredLiteral(String pattern, RegexFlags flags) {
        if (pattern == null || pattern.isEmpty() || flags == null || flags.isCaseInsensitive()
                || pattern.contains("(?i") || pattern.contains("(?[") || hasTopLevelAlternation(pattern)) {
            return null;
        }

        int depth = 0;
        boolean inCharClass = false;
        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);

            if (escaped) {
                if (depth == 0 && !inCharClass && isEscapedRequiredLiteral(ch)
                        && !isOptionalQuantifiedAt(pattern, i + 1)) {
                    return Character.toString(ch);
                }
                if (!isEscapedRequiredLiteral(ch)) {
                    i = skipEscapePayload(pattern, i);
                }
                escaped = false;
                continue;
            }

            if (ch == '\\') {
                escaped = true;
                continue;
            }

            if (inCharClass) {
                if (ch == '[' && i + 1 < pattern.length() && isPosixBracketStart(pattern.charAt(i + 1))) {
                    int posixEnd = findPosixBracketEnd(pattern, i);
                    if (posixEnd >= 0) {
                        i = posixEnd;
                    }
                    continue;
                }
                if (ch == ']') {
                    inCharClass = false;
                }
                continue;
            }

            if (ch == '[') {
                inCharClass = true;
                continue;
            }

            if (ch == '(') {
                depth++;
                continue;
            }

            if (ch == ')' && depth > 0) {
                depth--;
                continue;
            }

            if (depth != 0) {
                continue;
            }

            if (ch == '{') {
                int quantifierEnd = findQuantifierEnd(pattern, i);
                if (quantifierEnd >= 0) {
                    i = quantifierEnd;
                    continue;
                }
            }

            if ((flags.isExtended() || flags.isExtendedWhitespace()) && Character.isWhitespace(ch)) {
                continue;
            }
            if ((flags.isExtended() || flags.isExtendedWhitespace()) && ch == '#') {
                while (i < pattern.length() && pattern.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }

            if (!isTopLevelLiteralMetaCharacter(ch) && !isOptionalQuantifiedAt(pattern, i + 1)) {
                return Character.toString(ch);
            }
        }

        return null;
    }

    private static boolean isPosixBracketStart(char ch) {
        return ch == ':' || ch == '=' || ch == '.';
    }

    private static int findPosixBracketEnd(String pattern, int bracketOffset) {
        if (bracketOffset + 1 >= pattern.length()) {
            return -1;
        }
        char delimiter = pattern.charAt(bracketOffset + 1);
        if (!isPosixBracketStart(delimiter)) {
            return -1;
        }
        for (int i = bracketOffset + 2; i + 1 < pattern.length(); i++) {
            if (pattern.charAt(i) == delimiter && pattern.charAt(i + 1) == ']') {
                return i + 1;
            }
        }
        return -1;
    }

    private static int findQuantifierEnd(String pattern, int offset) {
        int close = pattern.indexOf('}', offset + 1);
        if (close < 0) {
            return -1;
        }

        String body = pattern.substring(offset + 1, close).trim();
        if (body.isEmpty()) {
            return -1;
        }

        int comma = body.indexOf(',');
        if (comma < 0) {
            return isAsciiDigits(body) ? close : -1;
        }

        if (body.indexOf(',', comma + 1) >= 0) {
            return -1;
        }

        String min = body.substring(0, comma).trim();
        String max = body.substring(comma + 1).trim();
        if (!min.isEmpty() && !isAsciiDigits(min)) {
            return -1;
        }
        if (min.isEmpty() && max.isEmpty()) {
            return -1;
        }
        return max.isEmpty() || isAsciiDigits(max) ? close : -1;
    }

    private static boolean isAsciiDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean hasTopLevelAlternation(String pattern) {
        int depth = 0;
        boolean inCharClass = false;
        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (inCharClass) {
                if (ch == '[' && i + 1 < pattern.length() && isPosixBracketStart(pattern.charAt(i + 1))) {
                    int posixEnd = findPosixBracketEnd(pattern, i);
                    if (posixEnd >= 0) {
                        i = posixEnd;
                    }
                    continue;
                }
                if (ch == ']') {
                    inCharClass = false;
                }
                continue;
            }
            if (ch == '[') {
                inCharClass = true;
                continue;
            }
            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')' && depth > 0) {
                depth--;
                continue;
            }
            if (ch == '|' && depth == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEscapedRequiredLiteral(char ch) {
        return switch (ch) {
            case 'A', 'B', 'C', 'D', 'G', 'H', 'K', 'N', 'P', 'R', 'S', 'V', 'W', 'X', 'Z',
                 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'k', 'n', 'o', 'p', 'r', 's', 't', 'v', 'w', 'x', 'z',
                 '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> false;
            default -> true;
        };
    }

    private static int skipEscapePayload(String pattern, int escapeNameOffset) {
        char ch = pattern.charAt(escapeNameOffset);
        if ((ch == 'p' || ch == 'P' || ch == 'x' || ch == 'o' || ch == 'N' || ch == 'g')
                && escapeNameOffset + 1 < pattern.length()
                && pattern.charAt(escapeNameOffset + 1) == '{') {
            int close = pattern.indexOf('}', escapeNameOffset + 2);
            return close >= 0 ? close : escapeNameOffset;
        }
        if ((ch == 'b' || ch == 'B')
                && escapeNameOffset + 1 < pattern.length()
                && pattern.charAt(escapeNameOffset + 1) == '{') {
            int close = pattern.indexOf('}', escapeNameOffset + 2);
            return close >= 0 ? close : escapeNameOffset;
        }
        if (ch == 'c' && escapeNameOffset + 1 < pattern.length()) {
            return escapeNameOffset + 1;
        }
        if (ch == 'x') {
            int end = skipHexDigits(pattern, escapeNameOffset + 1, 2);
            return end > escapeNameOffset + 1 ? end - 1 : escapeNameOffset;
        }
        if ((ch == 'p' || ch == 'P')
                && escapeNameOffset + 1 < pattern.length()
                && Character.isLetter(pattern.charAt(escapeNameOffset + 1))) {
            return escapeNameOffset + 1;
        }
        if (ch == 'g' && escapeNameOffset + 1 < pattern.length()) {
            int pos = escapeNameOffset + 1;
            if (pattern.charAt(pos) == '-') {
                pos++;
            }
            int end = skipAsciiDigits(pattern, pos);
            return end > pos ? end - 1 : escapeNameOffset;
        }
        if (ch >= '0' && ch <= '9') {
            int end = skipAsciiDigits(pattern, escapeNameOffset);
            return end > escapeNameOffset ? end - 1 : escapeNameOffset;
        }
        if (ch == 'k' && escapeNameOffset + 1 < pattern.length()) {
            char opener = pattern.charAt(escapeNameOffset + 1);
            char closer = opener == '<' ? '>' : (opener == '\'' ? '\'' : 0);
            if (closer != 0) {
                int close = pattern.indexOf(closer, escapeNameOffset + 2);
                return close >= 0 ? close : escapeNameOffset;
            }
        }
        return escapeNameOffset;
    }

    private static int skipAsciiDigits(String s, int offset) {
        int pos = offset;
        while (pos < s.length()) {
            char ch = s.charAt(pos);
            if (ch < '0' || ch > '9') {
                break;
            }
            pos++;
        }
        return pos;
    }

    private static int skipHexDigits(String s, int offset, int maxDigits) {
        int pos = offset;
        int digits = 0;
        while (pos < s.length() && digits < maxDigits) {
            if (Character.digit(s.charAt(pos), 16) < 0) {
                break;
            }
            pos++;
            digits++;
        }
        return pos;
    }

    private static boolean isTopLevelLiteralMetaCharacter(char ch) {
        return ch == '^' || ch == '$' || ch == '.' || ch == '*' || ch == '+'
                || ch == '?' || ch == '{' || ch == '}';
    }

    private static boolean isOptionalQuantifiedAt(String pattern, int offset) {
        if (offset >= pattern.length()) {
            return false;
        }
        char ch = pattern.charAt(offset);
        if (ch == '?' || ch == '*') {
            return true;
        }
        if (ch != '{') {
            return false;
        }
        int close = pattern.indexOf('}', offset + 1);
        if (close < 0) {
            return false;
        }
        String body = pattern.substring(offset + 1, close).trim();
        return body.equals("0") || body.startsWith("0,") || body.startsWith(",");
    }

    /**
     * Helper method to merge regex flags
     *
     * @param baseFlags     Existing flags (can be null)
     * @param newModifiers  New modifiers to add
     * @param patternString The pattern string (for flag parsing)
     * @return Merged RegexFlags
     */
    private static RegexFlags mergeRegexFlags(RegexFlags baseFlags, String newModifiers, String patternString) {
        if (newModifiers.isEmpty()) {
            // No new modifiers, return base flags
            return baseFlags != null ? baseFlags : fromModifiers("", patternString);
        }

        if (baseFlags == null) {
            // No base flags, just parse new ones
            return fromModifiers(newModifiers, patternString);
        }

        // Merge existing flags with new ones
        String existingFlags = baseFlags.toFlagString();
        StringBuilder mergedFlags = new StringBuilder();

        // Add all existing flags
        for (char c : existingFlags.toCharArray()) {
            if (mergedFlags.indexOf(String.valueOf(c)) == -1) {
                mergedFlags.append(c);
            }
        }

        // Add new flags (these override if duplicate)
        for (char c : newModifiers.toCharArray()) {
            if (mergedFlags.indexOf(String.valueOf(c)) == -1) {
                mergedFlags.append(c);
            }
        }

        return fromModifiers(mergedFlags.toString(), patternString);
    }

    /**
     * Creates a Perl "qr" object from a regex pattern string with optional modifiers.
     * `my $v = qr/abc/i;`
     * Also handles cases where the pattern is already a regex or has qr overloading.
     *
     * @param patternString The regex pattern string, regex object, or object with qr overloading
     * @param modifiers     Modifiers for the regex pattern (e.g., "i", "g").
     * @return A RuntimeScalar.
     */
    /**
     * Under {@code use feature 'unicode_strings'}, Perl applies Unicode semantics to regex
     * literals as if {@code /u} were present, so stringified {@code qr//} shows {@code (?^u:...)}.
     * Do not add implicit {@code u} when {@code /a} (ASCII restrict), explicit {@code /u}, or
     * {@code /d} (deprecated default rules) is already in the modifier string.
     */
    public static RuntimeScalar applyUnicodeStringsFeatureToModifiers(RuntimeScalar modifiers) {
        String m = modifiers == null ? "" : modifiers.toString();
        if (m.indexOf('a') >= 0 || m.indexOf('u') >= 0 || m.indexOf('d') >= 0) {
            return modifiers;
        }
        if (m.isEmpty()) {
            return new RuntimeScalar("u");
        }
        return new RuntimeScalar(m + "u");
    }

    public static RuntimeScalar getQuotedRegex(RuntimeScalar patternString, RuntimeScalar modifiers) {
        String rawModifierStr = modifiers.toString();
        int callSiteDebugMode = debugMode(rawModifierStr);
        String modifierStr = stripInternalMarkers(rawModifierStr);

        // Unwrap readonly scalar
        if (patternString.type == RuntimeScalarType.READONLY_SCALAR) patternString = (RuntimeScalar) patternString.value;

        validateTaintedPatternSecurity(patternString);

        if (!(patternString.value instanceof RuntimeRegexTemplate)
                && patternString.type != RuntimeScalarType.REGEX
                && containsExecutableSource(patternString.toString(), modifierStr.indexOf('x') >= 0)) {
            if (modifierStr.indexOf('E') < 0 && !patternString.firstClassRegexScalar) {
                throw new PerlCompilerException(
                        "Eval-group not allowed at runtime, use re 'eval'");
            }
            return RuntimeRegexSourceCompiler.compile(patternString, rawModifierStr);
        }

        if (patternString.value instanceof RuntimeRegexTemplate template
                && template.containsRuntimeExecutableSource(modifierStr)) {
            if (modifierStr.indexOf('E') < 0) {
                throw new PerlCompilerException(
                        "Eval-group not allowed at runtime, use re 'eval'");
            }
            return RuntimeRegexSourceCompiler.compileTemplate(
                    patternString, template, rawModifierStr);
        }

        if (patternString.value instanceof RuntimeRegexTemplate template) {
            RuntimeRegex regex = compile(template.pattern(), rawModifierStr, callSiteDebugMode,
                    template.callbacks().size(), template.byteBackedPattern()).cloneTracked();
            regex.setExecutableCallbacks(template.callbacks());
            return new RuntimeScalar(regex).propagateTaint(patternString);
        }

        // Check if patternString is already a compiled regex
        if (patternString.type == RuntimeScalarType.REGEX) {
            RuntimeRegex originalRegex = (RuntimeRegex) patternString.value;

            if (modifierStr.isEmpty() && callSiteDebugMode == 0) {
                // No new modifiers, return the original regex as-is
                return patternString;
            }

            // Create a new regex with merged flags
            RuntimeRegex regex = new RuntimeRegex();
            regex.recursivePattern = originalRegex.recursivePattern;
            regex.recursivePatternUnicode = originalRegex.recursivePatternUnicode;
            regex.recursivePatternBytes = originalRegex.recursivePatternBytes;
            regex.setExecutableCallbacks(originalRegex.executableCallbacks);
            regex.patternString = originalRegex.patternString;
            regex.patternByteBacked = originalRegex.patternByteBacked;
            regex.deferredUserDefinedUnicodeProperties =
                    originalRegex.deferredUserDefinedUnicodeProperties;
            regex.hasPreservesMatch = originalRegex.hasPreservesMatch;
            regex.quoteConstruction = originalRegex.quoteConstruction;
            regex.warningsOnUse = new ArrayList<>(originalRegex.warningsOnUse);
            regex.inlineModifierWarnings = new ArrayList<>(originalRegex.inlineModifierWarnings);
            regex.lexicalDebugMode = callSiteDebugMode != 0
                    ? callSiteDebugMode : originalRegex.lexicalDebugMode;
            regex.lexicalReStrict = originalRegex.lexicalReStrict;
            regex.regexFlags = mergeRegexFlags(originalRegex.regexFlags, modifierStr, originalRegex.patternString);
            regex.hasPreservesMatch = regex.hasPreservesMatch || regex.regexFlags.preservesMatch();
            regex.useGAssertion = regex.regexFlags.useGAssertion();
            regex.refCount = 0;  // Track for proper weak ref handling

            return new RuntimeScalar(regex).propagateTaint(patternString);
        }

        // Check for qr overloading
        int blessId = RuntimeScalarType.blessedId(patternString);
        if (blessId < 0) {
            OverloadContext overloadCtx = OverloadContext.prepare(blessId);
            if (overloadCtx != null) {
                // Try qr overload
                RuntimeScalar overloadedResult = overloadCtx.tryOverload("(qr", new RuntimeArray(patternString));
                if (overloadedResult != null && overloadedResult.type == RuntimeScalarType.REGEX) {
                    RuntimeRegex originalRegex = (RuntimeRegex) overloadedResult.value;

                    if (modifierStr.isEmpty() && callSiteDebugMode == 0) {
                        // No new modifiers, return the overloaded regex as-is
                        return overloadedResult;
                    }

                    // Create a new regex with merged flags
                    RuntimeRegex regex = new RuntimeRegex();
                    regex.recursivePattern = originalRegex.recursivePattern;
                    regex.recursivePatternUnicode = originalRegex.recursivePatternUnicode;
                    regex.recursivePatternBytes = originalRegex.recursivePatternBytes;
                    regex.setExecutableCallbacks(originalRegex.executableCallbacks);
                    regex.patternString = originalRegex.patternString;
                    regex.patternByteBacked = originalRegex.patternByteBacked;
                    regex.deferredUserDefinedUnicodeProperties =
                            originalRegex.deferredUserDefinedUnicodeProperties;
                    regex.hasPreservesMatch = originalRegex.hasPreservesMatch;
                    regex.quoteConstruction = originalRegex.quoteConstruction;
                    regex.warningsOnUse = new ArrayList<>(originalRegex.warningsOnUse);
                    regex.inlineModifierWarnings = new ArrayList<>(originalRegex.inlineModifierWarnings);
                    regex.lexicalDebugMode = callSiteDebugMode != 0
                            ? callSiteDebugMode : originalRegex.lexicalDebugMode;
                    regex.lexicalReStrict = originalRegex.lexicalReStrict;
                    regex.regexFlags = mergeRegexFlags(originalRegex.regexFlags, modifierStr, originalRegex.patternString);
                    regex.hasPreservesMatch = regex.hasPreservesMatch || regex.regexFlags.preservesMatch();
                    regex.useGAssertion = regex.regexFlags.useGAssertion();
                    regex.refCount = 0;  // Track for proper weak ref handling

                    return new RuntimeScalar(regex).propagateTaint(patternString, overloadedResult);
                }
                if (overloadedResult != null) {
                    throw new PerlCompilerException("Overloaded qr did not return a REGEXP");
                }

                // Try fallback to string conversion
                RuntimeScalar fallbackResult = overloadCtx.tryOverloadFallback(patternString, "(\"\"");
                if (fallbackResult != null) {
                    return new RuntimeScalar(compile(fallbackResult.toString(), rawModifierStr,
                            callSiteDebugMode).cloneTracked())
                            .propagateTaint(patternString, fallbackResult);
                }
            }
        }

        // Default: compile as string (cloneTracked() creates a tracked copy
        // so the cached RuntimeRegex is not corrupted by refCount changes)
        RuntimeRegex compiled = compile(patternString.toString(), rawModifierStr,
                callSiteDebugMode, 0,
                patternString.type == RuntimeScalarType.BYTE_STRING).cloneTracked();
        return new RuntimeScalar(compiled).propagateTaint(patternString);
    }

    static boolean containsExecutableSource(String pattern) {
        return containsExecutableSource(pattern, false);
    }

    static boolean containsExecutableSource(String pattern, boolean extended) {
        if (pattern == null || pattern.isEmpty()) return false;
        boolean escaped = false;
        boolean characterClass = false;
        boolean lineComment = false;
        for (int i = 0; i < pattern.length(); i++) {
            char current = pattern.charAt(i);
            if (lineComment) {
                if (current == '\n') lineComment = false;
                continue;
            }
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (characterClass) {
                if (current == ']') characterClass = false;
                continue;
            }
            if (current == '[') {
                characterClass = true;
                continue;
            }
            if (pattern.startsWith("(?#", i)) {
                i += 3;
                boolean commentEscape = false;
                while (i < pattern.length()) {
                    char commentChar = pattern.charAt(i);
                    if (commentEscape) {
                        commentEscape = false;
                    } else if (commentChar == '\\') {
                        commentEscape = true;
                    } else if (commentChar == ')') {
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (extended && current == '#') {
                lineComment = true;
                continue;
            }
            if (pattern.startsWith("(?{", i)
                    || pattern.startsWith("(??{", i)
                    || pattern.startsWith("(*{", i)
                    || pattern.startsWith("(?(?{", i)
                    || pattern.startsWith("(?(*{", i)) {
                return true;
            }
        }
        return false;
    }

    static RuntimeScalar compileExecutableTemplate(
            String executablePattern, String modifiers,
            List<RuntimeRegexCallback> callbacks, RuntimeScalar original,
            boolean patternByteBacked) {
        int lexicalDebugMode = debugMode(modifiers);
        RuntimeRegex regex = compile(executablePattern, modifiers,
                lexicalDebugMode, callbacks.size(), patternByteBacked).cloneTracked();
        regex.setExecutableCallbacks(callbacks);
        return new RuntimeScalar(regex).propagateTaint(original);
    }

    /** Mark a compiled value as originating from Perl's qr// constructor. */
    public static RuntimeScalar markQuoteConstruction(RuntimeScalar quotedRegex) {
        RuntimeRegex regex = resolveRegex(quotedRegex);
        regex.quoteConstruction = true;
        return quotedRegex;
    }

    /** Mark a syntactic qr// value and emit diagnostics that belong to its construction. */
    public static RuntimeScalar markSyntacticQuoteConstruction(RuntimeScalar quotedRegex) {
        RuntimeRegex regex = resolveRegex(quotedRegex);
        regex.quoteConstruction = true;
        for (String warning : regex.inlineModifierWarnings) {
            RegexQuoteMeta.warnAtConstruction(warning);
            regex.warningsOnUse.remove(warning);
        }
        regex.inlineModifierWarnings.clear();
        return quotedRegex;
    }

    private static void validateTaintedPatternSecurity(RuntimeScalar patternString) {
        if (!GlobalContext.isTaintModeActive() || patternString == null
                || !patternString.isTainted() || patternString.type == RuntimeScalarType.REGEX) {
            return;
        }

        String pattern = patternString.toString();
        if (pattern.contains("(?{") || pattern.contains("(??{")) {
            throw new PerlCompilerException("Eval-group in insecure regular expression");
        }

        Matcher matcher = USER_DEFINED_PROPERTY_PATTERN.matcher(pattern);
        if (!matcher.find()) {
            return;
        }

        String property = matcher.group(2);
        String qualified = property.contains("::") ? property : "main::" + property;
        if (GlobalVariable.isGlobalCodeRefDefined(qualified)) {
            throw new PerlCompilerException(
                    "Insecure user-defined property \"" + property + "\" in regex");
        }
        throw new PerlCompilerException(
                "Insecure user-defined property \\" + matcher.group(1) + "{" + qualified + "}");
    }

    /**
     * Variant of getQuotedRegex that supports the /o modifier.
     * When callsiteId is provided and modifiers contain 'o', the regex is compiled only once
     * and cached for subsequent calls from the same callsite.
     *
     * @param patternString The regex pattern string.
     * @param modifiers     Modifiers for the regex pattern (may include 'o').
     * @param callsiteId    Unique identifier for this callsite (used for /o caching).
     * @return A RuntimeScalar representing the compiled regex.
     */
    public static RuntimeScalar getQuotedRegex(RuntimeScalar patternString, RuntimeScalar modifiers, int callsiteId) {
        String rawModifierStr = modifiers.toString();
        String modifierStr = stripInternalMarkers(rawModifierStr);
        
        // Check if /o or m?PAT? modifier is present (both need per-callsite caching
        // to preserve state: /o caches the compiled pattern, m?PAT? preserves the
        // 'matched' flag that tracks whether the pattern has already matched once)
        if (modifierStr.contains("o") || modifierStr.contains("?")) {
            // Check if we already have a cached regex for this callsite
            RuntimeScalar cached = state().optimizedRegexCache.get(callsiteId);
            if (cached != null) {
                return cached;
            }
            
            // Compile the regex and cache it
            RuntimeScalar result = getQuotedRegex(patternString, modifiers);
            state().optimizedRegexCache.put(callsiteId, result);
            return result;
        }
        
        // No /o or m?PAT? modifier, use normal compilation
        return getQuotedRegex(patternString, modifiers);
    }

    /**
     * Internal variant of qr// that includes a `replacement`.
     * This is the internal representation of the `s///` operation.
     *
     * @param patternString The regex pattern string.
     * @param replacement   The replacement string.
     * @param modifiers     Modifiers for the regex pattern.
     * @return A RuntimeScalar representing the compiled regex with replacement.
     */
    public static RuntimeScalar getReplacementRegex(RuntimeScalar patternString, RuntimeScalar replacement, RuntimeScalar modifiers) {
        // Use resolveRegex to properly handle qr objects and qr overloading
        ResolvedRegex resolved = resolveRegexWithOrigin(patternString);
        RuntimeRegex resolvedRegex = resolved.regex();
        String rawModifierStr = modifiers.toString();
        int callSiteDebugMode = debugMode(rawModifierStr);
        String modifierStr = stripInternalMarkers(rawModifierStr);

        // Create a new regex instance with the replacement
        RuntimeRegex regex = new RuntimeRegex();

        // Always start with the resolved regex properties
        regex.recursivePattern = resolvedRegex.recursivePattern;
        regex.recursivePatternUnicode = resolvedRegex.recursivePatternUnicode;
        regex.recursivePatternBytes = resolvedRegex.recursivePatternBytes;
        regex.executableCallbacks = resolvedRegex.executableCallbacks;
        regex.patternString = resolvedRegex.patternString;
        regex.patternByteBacked = resolvedRegex.patternByteBacked;
        regex.deferredUserDefinedUnicodeProperties =
                resolvedRegex.deferredUserDefinedUnicodeProperties;
        regex.regexFlags = resolvedRegex.regexFlags;
        regex.hasPreservesMatch = resolvedRegex.hasPreservesMatch;
        regex.quoteConstruction = resolvedRegex.quoteConstruction;
        regex.useGAssertion = resolvedRegex.useGAssertion;
        regex.hasCodeBlockCaptures = resolvedRegex.hasCodeBlockCaptures;
        regex.warningsOnUse = new ArrayList<>(resolvedRegex.warningsOnUse);
        regex.inlineModifierWarnings = new ArrayList<>(resolvedRegex.inlineModifierWarnings);
        regex.lexicalDebugMode = callSiteDebugMode != 0
                ? callSiteDebugMode : resolvedRegex.lexicalDebugMode;
        regex.lexicalReStrict = resolvedRegex.lexicalReStrict;

        // Only recompile if we have new modifiers that actually change the flags
        if (!modifierStr.isEmpty()) {
            RegexFlags newFlags = resolved.fromCompiledRegex()
                    ? mergeOperationFlags(resolvedRegex.regexFlags, modifierStr, resolvedRegex.patternString)
                    : mergeRegexFlags(resolvedRegex.regexFlags, modifierStr, resolvedRegex.patternString);

            // Check if the merged flags are actually different
            boolean flagsChanged = false;
            if (resolvedRegex.regexFlags == null) {
                flagsChanged = !newFlags.toFlagString().isEmpty();
            } else {
                flagsChanged = !resolvedRegex.regexFlags.toFlagString().equals(newFlags.toFlagString());
            }

            // Only recompile if flags actually changed (this is needed for /x preprocessing)
            if (flagsChanged && !resolved.fromCompiledRegex()) {
                RuntimeRegex recompiledRegex = compile(resolvedRegex.patternString,
                        newFlags.toFlagString(), regex.lexicalDebugMode,
                        resolvedRegex.executableCallbacks.size(),
                        resolvedRegex.patternByteBacked, regex.lexicalReStrict);
                regex.recursivePattern = recompiledRegex.recursivePattern;
                regex.recursivePatternUnicode = recompiledRegex.recursivePatternUnicode;
                regex.recursivePatternBytes = recompiledRegex.recursivePatternBytes;
                regex.executableCallbacks = resolvedRegex.executableCallbacks;
                regex.patternString = recompiledRegex.patternString;
                regex.regexFlags = recompiledRegex.regexFlags;
                regex.hasPreservesMatch = recompiledRegex.hasPreservesMatch;
                regex.useGAssertion = recompiledRegex.useGAssertion;
                regex.hasCodeBlockCaptures = recompiledRegex.hasCodeBlockCaptures;
                regex.warningsOnUse = new ArrayList<>(recompiledRegex.warningsOnUse);
                regex.inlineModifierWarnings = new ArrayList<>(
                        recompiledRegex.inlineModifierWarnings);
            } else {
                // Just update the flags without recompiling.  A compiled qr// used
                // as the whole substitution pattern keeps its own pattern flags;
                // outer s/// flags like /x or /i must not reinterpret its source.
                regex.regexFlags = newFlags;
                regex.hasPreservesMatch = regex.hasPreservesMatch || newFlags.preservesMatch();
                regex.useGAssertion = newFlags.useGAssertion();
            }
        }

        regex.replacement = replacement;
        return new RuntimeScalar(regex).propagateTaint(patternString);
    }

    /**
     * Internal variant of qr// that includes a `replacement` and caller's @_.
     * This overload passes the caller's @_ so that $_[0] etc. work in s/// replacement.
     *
     * @param patternString The regex pattern string.
     * @param replacement   The replacement string.
     * @param modifiers     Modifiers for the regex pattern.
     * @param callerArgs    The caller's @_ array for replacement code evaluation.
     * @return A RuntimeScalar representing the compiled regex with replacement.
     */
    public static RuntimeScalar getReplacementRegex(RuntimeScalar patternString, RuntimeScalar replacement, RuntimeScalar modifiers, RuntimeArray callerArgs) {
        RuntimeScalar result = getReplacementRegex(patternString, replacement, modifiers);
        RuntimeRegex regex = (RuntimeRegex) result.value;
        regex.callerArgs = callerArgs;
        return result;
    }

    /** Create a replacement regex whose target and captures are viewed as UTF-8 octets. */
    public static RuntimeScalar getBytesReplacementRegex(RuntimeScalar patternString,
                                                         RuntimeScalar replacement,
                                                         RuntimeScalar modifiers,
                                                         RuntimeArray callerArgs) {
        RuntimeScalar result = getReplacementRegex(patternString, replacement, modifiers, callerArgs);
        RuntimeRegex regex = (RuntimeRegex) result.value;
        regex.bytesSubstitution = true;
        String sourcePattern = patternString.type == RuntimeScalarType.REGEX
                ? regex.patternString : patternString.toString();
        if (regex.recursivePattern != null && containsNonAscii(sourcePattern)) {
            boolean byteBackedPattern = patternString.type == RuntimeScalarType.REGEX
                    ? regex.patternByteBacked
                    : patternString.type == RuntimeScalarType.BYTE_STRING;
            regex.recursivePatternBytes = new JoniRegexPattern(sourcePattern,
                    regex.regexFlags, regex.executableCallbacks.size(), true,
                    true, byteBackedPattern, regex.namedCharacterCache);
        }
        return result;
    }

    private static boolean containsNonAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7f) return true;
        }
        return false;
    }

    /** Perl named characters and wide numeric escapes make an ASCII source Unicode. */
    private static boolean hasUnicodePromotingPatternSyntax(String pattern) {
        if (pattern == null) return false;
        boolean quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch > 0xff) return true;
            if (ch != '\\' || i + 1 >= pattern.length()) continue;

            char escape = pattern.charAt(++i);
            if (escape == 'Q' && !quoted) {
                quoted = true;
                continue;
            }
            if (escape == 'E' && quoted) {
                quoted = false;
                continue;
            }
            if (quoted || escape == '\\') continue;
            if (escape == 'N' && i + 1 < pattern.length()
                    && pattern.charAt(i + 1) == '{') {
                return true;
            }
            if ((escape != 'x' && escape != 'o') || i + 1 >= pattern.length()
                    || pattern.charAt(i + 1) != '{') {
                continue;
            }

            int radix = escape == 'x' ? 16 : 8;
            boolean sawDigit = false;
            long value = 0;
            int cursor = i + 2;
            for (; cursor < pattern.length() && pattern.charAt(cursor) != '}'; cursor++) {
                char digitCharacter = pattern.charAt(cursor);
                if (digitCharacter == '_') continue;
                int digit = Character.digit(digitCharacter, radix);
                if (digit < 0) break;
                sawDigit = true;
                value = value * radix + digit;
                if (value > 0xff) return true;
            }
            if (sawDigit && cursor < pattern.length() && pattern.charAt(cursor) == '}') {
                i = cursor;
            }
        }
        return false;
    }

    /**
     * Applies a Perl "qr" object on a string; returns true/false or a list,
     * and produces side-effects.
     * `my $v =~ /$qr/;`
     *
     * @param quotedRegex The regex pattern object, created by getQuotedRegex().
     * @param string      The string to be matched.
     * @param ctx         The context LIST, SCALAR, VOID.
     * @return A RuntimeScalar or RuntimeList.
     */
    public static RuntimeBase matchRegex(RuntimeScalar quotedRegex, RuntimeScalar string, int ctx) {
        if (string.type == RuntimeScalarType.UNDEF) {
            String lexicalName = RegexQuoteMeta.getMatchTargetName();
            if (lexicalName == null) {
                lexicalName = RuntimeCode.findActiveLexicalName(string);
            }
            String message = "Use of uninitialized value"
                    + (lexicalName == null ? "" : " " + lexicalName)
                    + " in pattern match (m//)";
            WarnDie.warnWithCategory(new RuntimeScalar(message),
                    RuntimeScalarCache.scalarEmptyString, "uninitialized");
        }
        RuntimeRegex regex = resolveRegex(quotedRegex);
        regex = ensureCompiledForRuntime(regex);
        if (regex.replacement != null) {
            if (regex.bytesSubstitution) {
                return replaceRegexBytes(quotedRegex, string, ctx);
            }
            return replaceRegex(quotedRegex, string, ctx);
        }

        // Check if alarm is active - if so, use timeout wrapper to prevent catastrophic backtracking
        if (Time.hasActiveAlarm()) {
            int timeoutSeconds = Time.getAlarmRemainingSeconds();
            if (timeoutSeconds > 0) {
                return matchRegexWithTimeout(quotedRegex, string, ctx, timeoutSeconds + 1);
            }
        }

        // Fast path: no alarm active, use direct matching
        RuntimeBase result = matchRegexDirect(quotedRegex, string, ctx);
        return result;
    }

    /** Apply a regex under lexical {@code use bytes} while preserving an s/// lvalue. */
    public static RuntimeBase matchRegexBytes(RuntimeScalar quotedRegex, RuntimeScalar string, int ctx) {
        RuntimeScalar byteView = StringOperators.toBytesString(string);
        if (byteView == string) {
            return matchRegex(quotedRegex, string, ctx);
        }

        RuntimeRegex regex = resolveRegex(quotedRegex);
        boolean destructiveReplacement = regex.replacement != null
                && (regex.regexFlags == null || !regex.regexFlags.isNonDestructive());
        if (!destructiveReplacement) {
            RuntimePosLvalue.copyPositionState(string, byteView);
        }
        RuntimeBase result = matchRegex(quotedRegex, byteView, ctx);
        if (destructiveReplacement && result.scalar().getBoolean()) {
            string.set(byteView);
        } else if (!destructiveReplacement) {
            RuntimePosLvalue.copyPositionState(byteView, string);
        }
        return result;
    }

    /**
     * Perl GH #16894 / Java JDK-7145888: under a quantified non-capturing group, alternating captures
     * inside a positive lookahead can leave a stale Java {@code Matcher} value for the left branch
     * even when Perl clears it. Mirror Perl for the re/pat.t regression case.
     */
    private static void fixPerl16894AlternateCaptureInLookahead(RuntimeRegex regex, String inputStr) {
        RuntimeRegexState regexState = state();
        if (regexState.lastCaptureGroups == null || regex == null || regex.patternString == null) {
            return;
        }
        if ("(?:[^b]*(?=(b)|(a))ab)*".equals(regex.patternString)
                && "abab".equals(inputStr)
                && regexState.lastCaptureGroups.length >= 2) {
            regexState.lastCaptureGroups[0] = null;
        }
    }

    private static void updateLastNamedCaptureGroups(RegexMatcher matcher) {
        RuntimeRegexState regexState = state();
        Map<String, Integer> namedGroups = matcher.namedGroups();
        Map<String, List<String>> byPerlName = new LinkedHashMap<>();
        if (namedGroups == null || namedGroups.isEmpty()) {
            regexState.lastNamedCaptureGroups = byPerlName;
            return;
        }

        Map<String, List<String>> javaNamesByPerlName = new LinkedHashMap<>();
        for (String javaName : namedGroups.keySet()) {
            if (CaptureNameEncoder.isInternalCapture(javaName)) {
                continue;
            }
            String perlName = CaptureNameEncoder.decodeGroupName(javaName);
            javaNamesByPerlName.computeIfAbsent(perlName, k -> new ArrayList<>()).add(javaName);
        }

        for (List<String> javaNames : javaNamesByPerlName.values()) {
            javaNames.sort(java.util.Comparator.comparingInt(namedGroups::get));
        }

        for (Map.Entry<String, List<String>> entry : javaNamesByPerlName.entrySet()) {
            List<String> values = new ArrayList<>();
            for (String javaName : entry.getValue()) {
                values.add(matcher.group(javaName));
            }
            byPerlName.put(entry.getKey(), values);
        }
        regexState.lastNamedCaptureGroups = byPerlName;
    }

    /** Populate Perl's numbered capture state from the backend-neutral view. */
    private static void updateNumberedCaptureGroups(RegexMatcher matcher) {
        RuntimeRegexState regexState = state();
        regexState.lastParenMatchOverrideActive = false;
        regexState.lastParenMatchOverride = null;
        regexState.manualCaptureStarts = null;
        regexState.manualCaptureEnds = null;
        int captureCount = matcher.groupCount();
        int lastClosedCapture = matcher.lastClosedCapture();
        regexState.lastClosedCapture = lastClosedCapture > 0
                && lastClosedCapture <= captureCount ? matcher.group(lastClosedCapture) : null;
        if (captureCount == 0) {
            regexState.lastCaptureGroups = null;
            return;
        }

        regexState.lastCaptureGroups = new String[captureCount];
        for (int group = 1; group <= captureCount; group++) {
            regexState.lastCaptureGroups[group - 1] = matcher.group(group);
        }
    }

    /**
     * Direct regex matching without timeout wrapper (fast path).
     */
    /**
     * Resolve Perl's empty-pattern reuse without retaining Java matcher state.
     * A changed modifier set needs a complete native recompilation; otherwise a
     * tracked wrapper around the last native compiled form is sufficient.
     */
    private static RuntimeRegex emptyPatternReuse(RuntimeRegex previous,
                                                  RegexFlags flags,
                                                  int lexicalDebugMode,
                                                  boolean lexicalReStrict,
                                                  RuntimeScalar replacement) {
        RuntimeRegex reused;
        if (previous != null && (flags == null || flags.equals(previous.regexFlags))) {
            reused = previous.cloneTracked();
        } else {
            String source = previous == null ? "" : previous.patternString;
            String modifiers = flags == null ? "" : flags.toFlagString();
            int debugMode = lexicalDebugMode != 0
                    ? lexicalDebugMode
                    : (previous == null ? 0 : previous.lexicalDebugMode);
            reused = compile(source, modifiers, debugMode, 0,
                    previous != null && previous.patternByteBacked,
                    lexicalReStrict || (previous != null && previous.lexicalReStrict));
        }
        reused.regexFlags = flags == null ? reused.regexFlags : flags;
        reused.hasPreservesMatch = reused.hasPreservesMatch
                || (flags != null && flags.preservesMatch());
        reused.lexicalDebugMode = lexicalDebugMode != 0 ? lexicalDebugMode : reused.lexicalDebugMode;
        reused.lexicalReStrict = lexicalReStrict || reused.lexicalReStrict;
        reused.useGAssertion = flags != null && flags.useGAssertion();
        reused.replacement = replacement;
        return reused;
    }

    private static RuntimeBase matchRegexDirect(RuntimeScalar quotedRegex, RuntimeScalar string, int ctx) {
        RuntimeRegexState regexState = state();
        RuntimeRegex regex = resolveRegex(quotedRegex);
        regex = ensureCompiledForRuntime(regex);
        
        // Save original flags before potentially changing regex
        RegexFlags originalFlags = regex.regexFlags;

        // Handle empty pattern - reuse last successful pattern or use empty pattern
        if (!regex.quoteConstruction
                && (regex.patternString == null || regex.patternString.isEmpty())) {
            if (regexState.lastSuccessfulPattern != null) {
                // Use the pattern from last successful match
                // But keep the current flags (especially /g and /i)
                regex = emptyPatternReuse(regexState.lastSuccessfulPattern, originalFlags,
                        regex.lexicalDebugMode, regex.lexicalReStrict, null);
            } else {
                regex = emptyPatternReuse(null, originalFlags,
                        regex.lexicalDebugMode, regex.lexicalReStrict, null);
            }
        }

        regex.emitWarningsOnUse();

        // Debug logging
        if (DEBUG_REGEX) {
            String description = regex.recursivePattern.patternDescription();
            System.err.println("matchRegexDirect: pattern=" + description +
                    " input=" + string.toString() + " ctx=" + ctx);
        }

        if (regex.regexFlags.isMatchExactlyOnce() && regex.matched) {
            // m?PAT? already matched once; now return false
            if (ctx == RuntimeContextType.LIST) {
                return new RuntimeList();
            } else if (ctx == RuntimeContextType.SCALAR) {
                return RuntimeScalarCache.scalarFalse;
            } else {
                return scalarUndef;
            }
        }

        String inputStr = string.toString();
        regex.emitNonUnicodePropertyWarning(string, inputStr);
        regex.emitExecutionDebugTrace(inputStr);
        RegexMatcher matcher = regex.selectRecursivePattern(string)
                .matcher(inputStr, regex.executableCallbacks, string);

        // hexPrinter(inputStr);

        // Look up pos() for /g matches and for non-/g matches that use \G.
        // In Perl, \G anchors at pos() even in non-/g matches (e.g. $str =~ /\Gfoo/).
        RuntimeScalar posScalar = null;
        boolean isPosDefined = false;
        int startPos = 0;
        boolean nativeGlobalPosition = false;
        // Flag to skip the first find() when the notempty variant already found a match
        boolean skipFirstFind = false;
        
        if (regex.regexFlags.isGlobalMatch() || regex.useGAssertion) {
            // Use RuntimePosLvalue to get the current position
            posScalar = RuntimePosLvalue.pos(string);
            isPosDefined = posScalar.getDefinedBoolean();
            startPos = isPosDefined
                    ? RuntimePosLvalue.toMatcherOffset(string, inputStr, posScalar.getInt())
                    : 0;

            RuntimeBase fastXmlElementResult = tryFastXmlElementScan(regex, string, inputStr, startPos, posScalar, ctx);
            if (fastXmlElementResult != null) {
                return fastXmlElementResult;
            }

            RuntimeBase fastXmpResult = tryFastXmpMetaElementScan(regex, string, inputStr, startPos, posScalar, ctx);
            if (fastXmpResult != null) {
                return fastXmpResult;
            }
            
            // Check if previous call had zero-length match at this position (for SCALAR context)
            // This prevents infinite loops in: while ($str =~ /pat/g)
            // In Perl, after a zero-length match, the next attempt stays at the same position
            // but uses NOTEMPTY (forbidding zero-length results). Java lacks NOTEMPTY, so we
            // use a precompiled "notempty" variant that converts ?? to ? (lazy→greedy) and
            // adds (?=[\s\S]) to prevent matching at end of string.
            if (regex.regexFlags.isGlobalMatch() && ctx == RuntimeContextType.SCALAR) {
                String patternKey = regex.patternString;
                if (RuntimePosLvalue.hadZeroLengthMatchAt(
                        string, posScalar.getInt(), patternKey)) {
                    // First, try the notempty variant at the SAME position (Perl behavior)
                    RegexMatcher notemptyMatcher = findNonEmptyGlobalRetry(
                            regex, string, inputStr, startPos);
                    boolean notemptySucceeded = notemptyMatcher != null;
                    if (notemptySucceeded) {
                        matcher = notemptyMatcher;
                        skipFirstFind = true;
                        RuntimePosLvalue.recordNonZeroLengthMatch(string);
                    }
                    
                    if (!notemptySucceeded) {
                        // Notempty variant didn't find a match; fall back to bumpalong
                        startPos = bumpGlobalMatchPosition(inputStr, startPos);
                        if (startPos > inputStr.length()) {
                            // Past end of string, fail
                            if (!regex.regexFlags.keepCurrentPosition()) {
                                posScalar.set(scalarUndef);
                            }
                            return RuntimeScalarCache.scalarFalse;
                        }
                        posScalar.set(RuntimePosLvalue.fromMatcherOffset(
                                string, inputStr, startPos));
                        RuntimePosLvalue.recordNonZeroLengthMatch(string);
                        isPosDefined = true;
                    }
                }
            }
        }

        if (regex.useGAssertion) {
            nativeGlobalPosition = matcher.setGlobalPosition(startPos);
        }

        // Start matching from the current position if defined
        // (skip if notempty variant already found a match - region() would reset the matcher)
        if (isPosDefined && !skipFirstFind) {
            matcher.region(nativeGlobalPosition ? 0 : startPos, inputStr.length());
            // Disable anchoring bounds so ^ and $ in /m mode anchor only at real
            // line breaks in the input, not at the artificial region boundary.
            // Java's default useAnchoringBounds(true) would let ^ match at startPos
            // even when startPos is not preceded by \n, producing spurious matches
            // for patterns like /^(.*)/mg.
            matcher.useAnchoringBounds(false);
        }

        boolean found = false;
        RuntimeList result = new RuntimeList();
        List<RuntimeBase> matchedGroups = result.elements;

        int capture = 1;
        int previousPos = startPos; // Track the previous position  
        int previousZeroLengthStart = -1;
        boolean previousMatchUsedPFlag = regexState.lastMatchUsedPFlag;
        // Inline /p is Perl match-variable policy rather than a Joni option.
        // Publish it while callouts execute; restore the preceding successful
        // match's policy if this matcher ultimately fails.
        regexState.lastMatchUsedPFlag = regex.hasPreservesMatch;
        // NOTE: Do NOT clear global match variables here.
        //
        // Perl preserves $1, @-, @+, $&, etc. from the last *successful* match even if a
        // subsequent regex operation fails. Test libraries (notably Test::Builder/Test2)
        // frequently run internal regexes (some of which fail) between user assertions.
        // Clearing these variables would incorrectly erase the previous successful capture
        // state and break tests that rely on @-/@+.

        try {
            while (skipFirstFind || matcher.find()) {
                skipFirstFind = false;
                // If \G is used, ensure the match starts at the expected position.
                // When pos() is undefined, \G anchors at 0 (the default startPos).
                if (regex.useGAssertion && !nativeGlobalPosition
                        && matcher.consumedStart() != startPos) {
                    break;
                }

                found = true;
                regexState.lastMatchResultsTainted = GlobalContext.isTaintModeActive()
                        && (quotedRegex.isTainted()
                        || (regex.regexFlags.taintResults() && string.isTainted()));
                regexState.lastMatchWasByteString = (string.type == RuntimeScalarType.BYTE_STRING);
                int captureCount = matcher.groupCount();

                // Always initialize $1, $2, @+, @-, $`, $&, $' for every successful match
                regexState.globalMatcher = matcher;
                regexState.globalMatchString = inputStr;
                regexState.lastMatchUsedBackslashK = false;
                updateLastNamedCaptureGroups(matcher);
                updateNumberedCaptureGroups(matcher);

                regexState.lastMatchedString = matcher.group(0);
                regexState.lastMatchStart = matcher.start();
                regexState.lastMatchEnd = matcher.end();

                if (regex.regexFlags.isGlobalMatch() && captureCount < 1 && ctx == RuntimeContextType.LIST) {
                    // Global match and no captures, in list context return the matched string
                    matchedGroups.add(makeMatchResultScalar(matcher.group(0)));
                } else {
                    // save captures in return list if needed
                    if (ctx == RuntimeContextType.LIST) {
                        for (int i = 1; i <= captureCount; i++) {
                            String matchedStr = matcher.group(i);
                            // Include undef for groups that didn't participate in the match.
                            // The matcher adapter exposes native Perl numbering for (?|...).
                            matchedGroups.add(makeMatchResultScalar(matchedStr));
                        }
                    }
                }

                if (regex.regexFlags.isGlobalMatch()) {
                    // Update the position for the next match
                    int matchStart = matcher.start();
                    int consumedStart = matcher.consumedStart();
                    int matchEnd = matcher.end();
                    boolean zeroLengthMatch = matchEnd == consumedStart;
                    boolean forcedAdvance = false;

                    // Detect zero-length match that would cause infinite loop
                    if (zeroLengthMatch && matchStart == previousZeroLengthStart) {
                        // Consecutive zero-length match at same position - advance by 1 or stop
                        if (matchEnd >= inputStr.length()) {
                            // At end of string, stop matching
                            break;
                        }
                        // In middle of string, advance by 1 to avoid infinite loop
                        matchEnd = bumpGlobalMatchPosition(inputStr, matchStart);
                        forcedAdvance = true;
                    }

                    previousZeroLengthStart = zeroLengthMatch ? matchStart : -1;

                    if (ctx == RuntimeContextType.SCALAR || ctx == RuntimeContextType.VOID) {
                        // Set pos to the end of the current match to prepare for the next search
                        // (only for global matches - posScalar is null for non-global)
                        if (posScalar != null) {
                            int perlMatchEnd = RuntimePosLvalue.fromMatcherOffset(
                                    string, inputStr, matchEnd);
                            posScalar.set(perlMatchEnd);
                            // Record zero-length match for cross-call tracking
                            if (matchEnd == matchStart) {
                                RuntimePosLvalue.recordZeroLengthMatch(
                                        string, perlMatchEnd, regex.patternString);
                            } else {
                                RuntimePosLvalue.recordNonZeroLengthMatch(string);
                            }
                        }
                        break; // Break out of the loop after the first match in SCALAR context
                    } else {
                        startPos = matchEnd;
                        if (nativeGlobalPosition) matcher.setGlobalPosition(startPos);
                        if (posScalar != null) {
                            posScalar.set(RuntimePosLvalue.fromMatcherOffset(
                                    string, inputStr, startPos));
                        }
                        // Only redirect the matcher when we forcibly advanced past
                        // a zero-length match. In every other case Java's find()
                        // already continues from matcher.end() naturally, and
                        // calling region() here would (a) re-enable anchoring
                        // bounds at an arbitrary offset (breaking ^/$ semantics
                        // under /m -- e.g. "ab\ncd\n" =~ /^(.*)/mg producing
                        // spurious empty matches) and (b) reset internal state.
                        if (forcedAdvance) {
                            matcher.region(startPos, inputStr.length());
                            matcher.useAnchoringBounds(false);
                        }
                        if (zeroLengthMatch) {
                            RegexMatcher notemptyMatcher = findNonEmptyGlobalRetry(
                                    regex, string, inputStr, startPos);
                            if (notemptyMatcher != null) {
                                matcher = notemptyMatcher;
                                skipFirstFind = true;
                                nativeGlobalPosition = regex.useGAssertion
                                        && matcher.setGlobalPosition(startPos);
                            }
                        }
                    }
                }

                if (!regex.regexFlags.isGlobalMatch()) {
                    break;
                }
            }
        } catch (RegexTimeoutException e) {
            WarnDie.warn(new RuntimeScalar(e.getMessage() + "\n"), RuntimeScalarCache.scalarEmptyString);
            found = false;
        } catch (StackOverflowError e) {
            // Java's backtracking engine uses the native stack for some nested
            // quantifiers. Perl reports an unsuccessful match when its own
            // recursion ceiling is reached; do not let the JVM error abort the
            // whole program for the equivalent pathological failure case.
            found = false;
        }

        if (!found) {
            regexState.lastMatchUsedPFlag = previousMatchUsedPFlag;
        }

        // Reset pos() on failed match with /g, unless /c is set
        if (!found && regex.regexFlags.isGlobalMatch() && !regex.regexFlags.keepCurrentPosition() && posScalar != null) {
            posScalar.set(scalarUndef);
        }

        // Debug logging
        if (DEBUG_REGEX) {
            System.err.println("  match result: found=" + found);
        }

        // A failed match preserves all match variables from the preceding
        // successful match, including $&, $`, $', and numbered captures.

        if (found) {
            fixPerl16894AlternateCaptureInLookahead(regex, inputStr);
            if (regex.regexFlags.isMatchExactlyOnce()) {
                regex.matched = true; // m?PAT? — remember we consumed the one allowed match
            }
            regexState.lastMatchUsedPFlag = regex.hasPreservesMatch;
            regexState.lastSuccessfulPattern = regex;
            // Store last successful match information (persists across failed matches)
            regexState.lastSuccessfulMatchedString = regexState.lastMatchedString;
            regexState.lastSuccessfulMatchStart = regexState.lastMatchStart;
            regexState.lastSuccessfulMatchEnd = regexState.lastMatchEnd;
            regexState.lastSuccessfulMatchString = regexState.globalMatchString;

            // Update $^R if this regex has code block captures (performance optimization)
            if (regex.hasCodeBlockCaptures) {
                RuntimeScalar codeBlockResult = regex.getLastCodeBlockResult();
                // Set $^R to the code block result (or undef if no code blocks matched)
                GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("R"))
                        .set(codeBlockResult != null ? codeBlockResult : RuntimeScalarCache.scalarUndef);
            }

            // Reset pos() after global match in LIST context (matches Perl behavior),
            // unless /c is set. The /c flag means "keep current position" and
            // applies to both scalar and list-context /g matches.
            if (regex.regexFlags.isGlobalMatch()
                    && ctx == RuntimeContextType.LIST
                    && !regex.regexFlags.keepCurrentPosition()
                    && posScalar != null) {
                posScalar.set(scalarUndef);
            }
            // System.err.println("DEBUG: Match completed, regexState.globalMatcher is " + (regexState.globalMatcher == null ? "null" : "set"));
        } else {
            // System.err.println("DEBUG: No match found, regexState.globalMatcher is " + (regexState.globalMatcher == null ? "null" : "set"));
        }

        if (ctx == RuntimeContextType.LIST) {
            // In LIST context: return captured groups, or (1) for success with no captures (non-global)
            if (found && result.elements.isEmpty() && !regex.regexFlags.isGlobalMatch()) {
                // Non-global match with no captures in LIST context returns (1)
                result.elements.add(RuntimeScalarCache.getScalarInt(1));
            }
            return result;
        } else if (ctx == RuntimeContextType.SCALAR) {
            return RuntimeScalarCache.getScalarBoolean(found);
        } else {
            return scalarUndef;
        }
    }

    /**
     * Avoid Java Pattern recursion for ExifTool's XMP element scan:
     * {@code <(/?)x:xmpmeta([-\w:.\x80-\xff]*)(.*?(/?))>}.
     *
     * <p>On Google extended-XMP payloads, Java's backtracking engine can
     * recurse deeply enough to throw {@link StackOverflowError}, while Perl
     * handles the scan iteratively. This narrow path preserves the captures
     * ExifTool uses while keeping other regexes on the normal engine.</p>
     */
    private static RuntimeBase tryFastXmpMetaElementScan(RuntimeRegex regex,
                                                         RuntimeScalar string,
                                                         String inputStr,
                                                         int startPos,
                                                         RuntimeScalar posScalar,
                                                         int ctx) {
        if (ctx == RuntimeContextType.LIST || regex.regexFlags == null) {
            return null;
        }
        if (!isXmpMetaElementScanPattern(regex)) {
            return null;
        }

        int tagStart = findXmpMetaTagStart(inputStr, startPos);
        if (tagStart < 0) {
            if (regex.regexFlags.isGlobalMatch() && !regex.regexFlags.keepCurrentPosition() && posScalar != null) {
                posScalar.set(scalarUndef);
            }
            state().globalMatchString = null;
            state().lastMatchedString = null;
            state().lastMatchStart = -1;
            state().lastMatchEnd = -1;
            state().manualCaptureStarts = null;
            state().manualCaptureEnds = null;
            return ctx == RuntimeContextType.SCALAR ? RuntimeScalarCache.scalarFalse : scalarUndef;
        }

        boolean closing = inputStr.charAt(tagStart + 1) == '/';
        int literalStart = tagStart + (closing ? 2 : 1);
        int nameEnd = literalStart + "x:xmpmeta".length();
        int suffixEnd = nameEnd;
        while (suffixEnd < inputStr.length() && isXmpMetaNameSuffixChar(inputStr.charAt(suffixEnd))) {
            suffixEnd++;
        }
        int tagEnd = inputStr.indexOf('>', suffixEnd);
        if (tagEnd < 0) {
            if (regex.regexFlags.isGlobalMatch() && !regex.regexFlags.keepCurrentPosition() && posScalar != null) {
                posScalar.set(scalarUndef);
            }
            state().globalMatchString = null;
            state().lastMatchedString = null;
            state().lastMatchStart = -1;
            state().lastMatchEnd = -1;
            state().manualCaptureStarts = null;
            state().manualCaptureEnds = null;
            return ctx == RuntimeContextType.SCALAR ? RuntimeScalarCache.scalarFalse : scalarUndef;
        }

        String group1 = closing ? "/" : "";
        String group2 = inputStr.substring(nameEnd, suffixEnd);
        String group3 = inputStr.substring(suffixEnd, tagEnd);
        String group4 = group3.endsWith("/") ? "/" : "";

        state().lastMatchWasByteString = (string.type == RuntimeScalarType.BYTE_STRING);
        state().globalMatcher = null;
        state().globalMatchString = inputStr;
        state().lastMatchUsedBackslashK = false;
        state().lastNamedCaptureGroups = new LinkedHashMap<>();
        state().lastCaptureGroups = new String[]{group1, group2, group3, group4};
        state().lastParenMatchOverrideActive = false;
        state().lastParenMatchOverride = null;
        state().manualCaptureStarts = new int[]{
                closing ? tagStart + 1 : literalStart,
                nameEnd,
                suffixEnd,
                group4.equals("/") ? tagEnd - 1 : tagEnd
        };
        state().manualCaptureEnds = new int[]{
                closing ? tagStart + 2 : literalStart,
                suffixEnd,
                tagEnd,
                group4.equals("/") ? tagEnd : tagEnd
        };
        state().lastMatchedString = inputStr.substring(tagStart, tagEnd + 1);
        state().lastMatchStart = tagStart;
        state().lastMatchEnd = tagEnd + 1;
        state().lastMatchUsedPFlag = regex.hasPreservesMatch;
        state().lastSuccessfulPattern = regex;
        state().lastSuccessfulMatchedString = state().lastMatchedString;
        state().lastSuccessfulMatchStart = state().lastMatchStart;
        state().lastSuccessfulMatchEnd = state().lastMatchEnd;
        state().lastSuccessfulMatchString = state().globalMatchString;
        regex.matched = true;

        if (regex.regexFlags.isGlobalMatch() && posScalar != null) {
            posScalar.set(RuntimePosLvalue.fromMatcherOffset(
                    string, inputStr, state().lastMatchEnd));
            RuntimePosLvalue.recordNonZeroLengthMatch(string);
        }

        return ctx == RuntimeContextType.SCALAR ? RuntimeScalarCache.scalarTrue : scalarUndef;
    }

    private static boolean isXmpMetaElementScanPattern(RuntimeRegex regex) {
        String perlPattern = "<(/?)x:xmpmeta([-\\w:.\\x80-\\xff]*)(.*?(/?))>";
        return perlPattern.equals(regex.patternString);
    }

    /**
     * Avoid Java Pattern recursion for ExifTool's generic XML element scan:
     * {@code <([?/]?)([-\w:.\x80-\xff]+|!--)([^>]*)>}.
     */
    private static RuntimeBase tryFastXmlElementScan(RuntimeRegex regex,
                                                     RuntimeScalar string,
                                                     String inputStr,
                                                     int startPos,
                                                     RuntimeScalar posScalar,
                                                     int ctx) {
        if (ctx == RuntimeContextType.LIST || regex.regexFlags == null) {
            return null;
        }
        if (!isXmlElementScanPattern(regex)) {
            return null;
        }

        XmlElementMatch match = findXmlElement(inputStr, startPos);
        if (match == null) {
            if (regex.regexFlags.isGlobalMatch() && !regex.regexFlags.keepCurrentPosition() && posScalar != null) {
                posScalar.set(scalarUndef);
            }
            state().globalMatchString = null;
            state().lastMatchedString = null;
            state().lastMatchStart = -1;
            state().lastMatchEnd = -1;
            state().manualCaptureStarts = null;
            state().manualCaptureEnds = null;
            return ctx == RuntimeContextType.SCALAR ? RuntimeScalarCache.scalarFalse : scalarUndef;
        }

        String group1 = inputStr.substring(match.group1Start, match.group1End);
        String group2 = inputStr.substring(match.group2Start, match.group2End);
        String group3 = inputStr.substring(match.group3Start, match.group3End);

        state().lastMatchWasByteString = (string.type == RuntimeScalarType.BYTE_STRING);
        state().globalMatcher = null;
        state().globalMatchString = inputStr;
        state().lastMatchUsedBackslashK = false;
        state().lastNamedCaptureGroups = new LinkedHashMap<>();
        state().lastCaptureGroups = new String[]{group1, group2, group3};
        state().lastParenMatchOverrideActive = false;
        state().lastParenMatchOverride = null;
        state().manualCaptureStarts = new int[]{match.group1Start, match.group2Start, match.group3Start};
        state().manualCaptureEnds = new int[]{match.group1End, match.group2End, match.group3End};
        state().lastMatchedString = inputStr.substring(match.matchStart, match.matchEnd);
        state().lastMatchStart = match.matchStart;
        state().lastMatchEnd = match.matchEnd;
        state().lastMatchUsedPFlag = regex.hasPreservesMatch;
        state().lastSuccessfulPattern = regex;
        state().lastSuccessfulMatchedString = state().lastMatchedString;
        state().lastSuccessfulMatchStart = state().lastMatchStart;
        state().lastSuccessfulMatchEnd = state().lastMatchEnd;
        state().lastSuccessfulMatchString = state().globalMatchString;
        regex.matched = true;

        if (regex.regexFlags.isGlobalMatch() && posScalar != null) {
            posScalar.set(RuntimePosLvalue.fromMatcherOffset(
                    string, inputStr, state().lastMatchEnd));
            RuntimePosLvalue.recordNonZeroLengthMatch(string);
        }

        return ctx == RuntimeContextType.SCALAR ? RuntimeScalarCache.scalarTrue : scalarUndef;
    }

    private static boolean isXmlElementScanPattern(RuntimeRegex regex) {
        String perlPattern = "<([?/]?)([-\\w:.\\x80-\\xff]+|!--)([^>]*)>";
        return perlPattern.equals(regex.patternString);
    }

    private static XmlElementMatch findXmlElement(String inputStr, int startPos) {
        int search = Math.max(0, startPos);
        while (search < inputStr.length()) {
            int tagStart = inputStr.indexOf('<', search);
            if (tagStart < 0 || tagStart + 1 >= inputStr.length()) {
                return null;
            }

            int index = tagStart + 1;
            int group1Start = index;
            int group1End = index;
            char marker = inputStr.charAt(index);
            if (marker == '?' || marker == '/') {
                group1End = ++index;
            }

            int group2Start = index;
            int group2End;
            if (index + 3 <= inputStr.length() && inputStr.startsWith("!--", index)) {
                group2End = index + 3;
            } else {
                while (index < inputStr.length() && isXmlElementNameChar(inputStr.charAt(index))) {
                    index++;
                }
                group2End = index;
                if (group2End == group2Start) {
                    search = tagStart + 1;
                    continue;
                }
            }

            int group3Start = group2End;
            int tagEnd = inputStr.indexOf('>', group3Start);
            if (tagEnd < 0) {
                return null;
            }

            return new XmlElementMatch(tagStart, tagEnd + 1,
                    group1Start, group1End,
                    group2Start, group2End,
                    group3Start, tagEnd);
        }
        return null;
    }

    private static boolean isXmlElementNameChar(char ch) {
        return ch == '-' || ch == ':' || ch == '.'
                || ch == '_' || Character.isLetterOrDigit(ch)
                || (ch >= 0x80 && ch <= 0xff);
    }

    private static class XmlElementMatch {
        final int matchStart;
        final int matchEnd;
        final int group1Start;
        final int group1End;
        final int group2Start;
        final int group2End;
        final int group3Start;
        final int group3End;

        XmlElementMatch(int matchStart, int matchEnd,
                        int group1Start, int group1End,
                        int group2Start, int group2End,
                        int group3Start, int group3End) {
            this.matchStart = matchStart;
            this.matchEnd = matchEnd;
            this.group1Start = group1Start;
            this.group1End = group1End;
            this.group2Start = group2Start;
            this.group2End = group2End;
            this.group3Start = group3Start;
            this.group3End = group3End;
        }
    }

    private static int findXmpMetaTagStart(String inputStr, int startPos) {
        int search = Math.max(0, startPos);
        while (search < inputStr.length()) {
            int literal = inputStr.indexOf("x:xmpmeta", search);
            if (literal < 0) {
                return -1;
            }
            int openStart = literal - 1;
            if (openStart >= startPos && openStart >= 0 && inputStr.charAt(openStart) == '<') {
                return openStart;
            }
            int closeStart = literal - 2;
            if (closeStart >= startPos && closeStart >= 0
                    && inputStr.charAt(closeStart) == '<'
                    && inputStr.charAt(closeStart + 1) == '/') {
                return closeStart;
            }
            search = literal + 1;
        }
        return -1;
    }

    private static boolean isXmpMetaNameSuffixChar(char ch) {
        return ch == '-' || ch == ':' || ch == '.'
                || ch == '_' || Character.isLetterOrDigit(ch)
                || (ch >= 0x80 && ch <= 0xff);
    }

    /**
     * Regex matching with timeout wrapper to handle catastrophic backtracking.
     * Runs the regex in a separate thread with a timeout.
     *
     * @param quotedRegex    The regex pattern object
     * @param string         The string to match against
     * @param ctx            The context (LIST, SCALAR, VOID)
     * @param timeoutSeconds Maximum seconds to allow for matching
     * @return Match result, or throws exception if timeout
     */
    private static RuntimeBase matchRegexWithTimeout(RuntimeScalar quotedRegex, RuntimeScalar string, int ctx, int timeoutSeconds) {
        PerlRuntime owner = PerlRuntime.current();
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
            Thread worker = new Thread(task, "PerlRegexTimeout");
            worker.setDaemon(true);
            return worker;
        });
        java.util.concurrent.Future<RuntimeBase> future = executor.submit(() -> {
            try (PerlRuntime.Binding ignored = owner.bind()) {
                return matchRegexDirect(quotedRegex, string, ctx);
            }
        });

        try {
            // Wait for result with timeout
            RuntimeBase result = future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            return result;
        } catch (java.util.concurrent.TimeoutException e) {
            // Regex timed out - cancel it and process alarm signal
            future.cancel(true);
            // Check for pending signals - alarm handler will fire here
            PerlSignalQueue.checkPendingSignals();
            // If we get here, no alarm handler or it didn't die - return false
            if (ctx == RuntimeContextType.LIST) {
                return new RuntimeList();
            } else {
                return RuntimeScalarCache.scalarFalse;
            }
        } catch (java.util.concurrent.ExecutionException e) {
            // Exception thrown during regex matching - unwrap and rethrow
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new PerlCompilerException("Regex matching failed: " + cause.getMessage());
        } catch (InterruptedException e) {
            // The alarm owner was interrupted after its signal was queued. Deliver
            // that signal before cancelling the regex worker: cancellation also
            // interrupts the worker, and a callback blocked in select/sleep could
            // otherwise consume the owner's queued ALRM and strand its exception
            // inside this already-cancelled Future. The finally block owns worker
            // cancellation for both normal-returning and throwing handlers.
            PerlSignalQueue.checkPendingSignals();
            if (ctx == RuntimeContextType.LIST) {
                return new RuntimeList();
            } else {
                return RuntimeScalarCache.scalarFalse;
            }
        } finally {
            future.cancel(true);
            executor.shutdownNow();
            try {
                executor.awaitTermination(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Applies a Perl "s///" substitution on a string.
     * `my $v =~ s/$pattern/$replacement/;`
     *
     * @param quotedRegex The regex pattern object, created by getReplacementRegex().
     * @param string      The string to be modified.
     * @param ctx         The context LIST, SCALAR, VOID.
     * @return A RuntimeScalar or RuntimeList.
     */
    private static int bumpGlobalMatchPosition(String inputStr, int offset) {
        if (offset >= inputStr.length()) {
            return inputStr.length() + 1;
        }
        return offset + Character.charCount(inputStr.codePointAt(offset));
    }

    private static RegexMatcher findNonEmptyGlobalRetry(RuntimeRegex regex,
                                                         RuntimeScalar string,
                                                         String inputStr,
                                                         int startPos) {
        RegexMatcher retryMatcher = regex.selectRecursivePattern(string)
                .matcher(inputStr, regex.executableCallbacks, string);

        retryMatcher.region(startPos, inputStr.length());
        retryMatcher.useAnchoringBounds(false);
        if (regex.useGAssertion) {
            retryMatcher.setGlobalPosition(startPos);
        }
        boolean found = retryMatcher.findNotEmpty();
        return found
                && retryMatcher.consumedStart() == startPos
                && retryMatcher.end() > startPos
                ? retryMatcher : null;
    }

    private static void setSubstitutionRegion(RegexMatcher matcher, int start, int end, boolean transparentBounds) {
        matcher.region(start, end);
        // The substitution loop drives the matcher by changing regions. Keep
        // ^/$ anchored to the real input, not to each artificial region start.
        matcher.useAnchoringBounds(false);
        // Lookbehind/lookahead assertions should still inspect the full input
        // around the current search start, matching Perl's pos()-style scan.
        matcher.useTransparentBounds(transparentBounds);
    }

    /**
     * Run a substitution against Perl's byte view without losing the bound
     * scalar. Destructive substitutions copy the byte result back through the
     * original lvalue; {@code /r} is handled by {@link #replaceRegex} and
     * leaves the original untouched.
     */
    private static RuntimeBase replaceRegexBytes(RuntimeScalar quotedRegex,
                                                 RuntimeScalar original,
                                                 int ctx) {
        RuntimeScalar byteView = StringOperators.toBytesString(original);
        RuntimeBase result = replaceRegex(quotedRegex, byteView, ctx);

        RuntimeRegex regex = resolveRegex(quotedRegex);
        boolean nonDestructive = regex.regexFlags != null && regex.regexFlags.isNonDestructive();
        if (!nonDestructive && byteView != original && result.getBoolean()) {
            original.set(byteView);
        }
        return result;
    }

    private static void updateReplacementMatchState(RuntimeRegex regex, RegexMatcher matcher,
                                                    String inputStr, RuntimeScalar string,
                                                    boolean resultsTainted) {
        state().lastMatchWasByteString = (string.type == RuntimeScalarType.BYTE_STRING);
        state().lastMatchResultsTainted = resultsTainted;

        // Initialize $1, $2, @+, @- only when we have a match
        state().globalMatcher = matcher;
        state().globalMatchString = inputStr;
        state().lastMatchUsedBackslashK = false;
        updateLastNamedCaptureGroups(matcher);
        updateNumberedCaptureGroups(matcher);

        state().lastMatchStart = matcher.start();
        state().lastMatchedString = matcher.group(0);
        state().lastMatchEnd = matcher.end();
    }

    public static RuntimeBase replaceRegex(RuntimeScalar quotedRegex, RuntimeScalar string, int ctx) {
        // Resolve a tied target exactly once for all reads.  Keep `string` as
        // the lvalue used for STORE after the substitution.
        RuntimeScalar inputValue = string.type == RuntimeScalarType.TIED_SCALAR
                ? string.tiedFetch() : string;
        String inputStr = inputValue.toString();
        boolean wasByteString = (inputValue.type == RuntimeScalarType.BYTE_STRING);
        boolean taintMode = GlobalContext.isTaintModeActive();
        boolean inputTainted = taintMode && inputValue.isTainted();
        boolean patternTainted = taintMode && quotedRegex.isTainted();
        boolean resultNeedsUtf8 = !wasByteString;

        // Extract the regex pattern from the quotedRegex object
        RuntimeRegex regex = resolveRegex(quotedRegex);

        // Save the original replacement and flags before potentially changing regex
        RuntimeScalar replacement = regex.replacement;
        RuntimeArray callerArgs = regex.callerArgs;
        RegexFlags originalFlags = regex.regexFlags;

        // Clear the replacement and callerArgs from the regex object to release closure
        // references. The replacement code reference may capture lexical variables from
        // the calling scope; holding it in the persistent regex object would prevent those
        // variables (and any tracked objects they reference) from being freed at scope exit.
        // The local variables above hold the references for the duration of this method.
        regex.replacement = null;
        regex.callerArgs = null;

        // Handle empty pattern - reuse last successful pattern or use empty pattern
        if (!regex.quoteConstruction
                && (regex.patternString == null || regex.patternString.isEmpty())) {
            if (state().lastSuccessfulPattern != null) {
                // Use the pattern from last successful match
                // But keep the current replacement and flags (especially /g and /i)
                regex = emptyPatternReuse(state().lastSuccessfulPattern, originalFlags,
                        regex.lexicalDebugMode, regex.lexicalReStrict, replacement);
            } else {
                // No previous regex - use empty pattern (matches empty string at start)
                // This matches Perl's behavior: s//x/ inserts 'x' at the beginning
                regex = emptyPatternReuse(null, originalFlags,
                        regex.lexicalDebugMode, regex.lexicalReStrict, replacement);
            }
        }

        regex.emitWarningsOnUse();

        RegexMatcher matcher = regex.selectRecursivePattern(inputValue)
                .matcher(inputStr, regex.executableCallbacks, inputValue);
        int searchStart = 0;
        int globalPosition = 0;
        boolean nativeGlobalPosition = false;

        // Honor pos() when \G is used. `s/\G.../.../` should anchor at
        // pos($string) so a substitution inserted right after a previous /g
        // match takes effect at the right offset (e.g. the
        // DateTime::Format::Natural rewrite idiom: `$s =~ /pat/g; $s =~ s/\G/:00/`).
        // Without setting region(), Java's matcher would scan from offset 0
        // and \G would anchor at 0, prepending the replacement.
        if (regex.useGAssertion) {
            RuntimeScalar posScalar = RuntimePosLvalue.pos(string);
            if (posScalar.getDefinedBoolean()) {
                int startPos = RuntimePosLvalue.toMatcherOffset(
                        string, inputStr, posScalar.getInt());
                if (startPos >= 0 && startPos <= inputStr.length()) {
                    globalPosition = startPos;
                }
            }
            nativeGlobalPosition = matcher.setGlobalPosition(globalPosition);
            if (!nativeGlobalPosition) searchStart = globalPosition;
        }

        // The result string after substitutions
        StringBuilder resultBuffer = new StringBuilder();
        int found = 0;
        boolean previousMatchUsedPFlag = state().lastMatchUsedPFlag;
        state().lastMatchUsedPFlag = regex.hasPreservesMatch;

        // Unwrap readonly scalar
        if (replacement.type == RuntimeScalarType.READONLY_SCALAR) replacement = (RuntimeScalar) replacement.value;

        // Determine if the replacement is a code that needs to be evaluated
        boolean replacementIsCode = (replacement.type == RuntimeScalarType.CODE);
        boolean replacementResultTainted = false;
        boolean captureResultsTainted = patternTainted
                || (regex.regexFlags.taintResults() && inputTainted);
        boolean destructiveReplacement = !regex.regexFlags.isNonDestructive();

        // Don't reset state().globalMatcher here - only reset it if we actually find a match
        // This preserves capture variables from previous matches when substitution doesn't match

        int lastAppendEnd = 0;

        // Perform the substitution. Java's Matcher.find() skips ahead after a
        // zero-length match; Perl's global substitution first retries at the
        // same offset with a non-empty match. Track append/search positions
        // explicitly so nullable patterns like /(.*?)(x)?/g behave like Perl.
        try {
            while (searchStart <= inputStr.length()) {
                setSubstitutionRegion(matcher, searchStart, inputStr.length(), true);
                if (nativeGlobalPosition) matcher.setGlobalPosition(globalPosition);
                if (!matcher.find()) {
                    break;
                }

                found++;
                updateReplacementMatchState(regex, matcher, inputStr, inputValue, captureResultsTainted);

                String replacementStr;
                if (replacementIsCode) {
                    // Evaluate the replacement as code
                    // During a destructive s///e, Perl exposes the current
                    // match start through pos($target) to replacement code.
                    // The final string mutation invalidates pos again. A
                    // non-destructive /r substitution leaves the target's
                    // original pos untouched.
                    if (destructiveReplacement) {
                        RuntimePosLvalue.pos(string).set(RuntimePosLvalue.fromMatcherOffset(
                                string, inputStr, matcher.start()));
                    }
                    // Use callerArgs (the enclosing subroutine's @_) so $_[0] etc. work
                    RuntimeArray args = (callerArgs != null) ? callerArgs : new RuntimeArray();
                    RuntimeList result = RuntimeCode.apply(replacement, args, RuntimeContextType.SCALAR);
                    RuntimeScalar replacementValue = stringifyReplacementValue(result.scalar());
                    if (Utf8.isUtf8(replacementValue)) {
                        resultNeedsUtf8 = true;
                    }
                    replacementResultTainted |= taintMode && replacementValue.isTainted();
                    replacementStr = replacementValue.toString();
                } else {
                    // Replace the match with the replacement string
                    RuntimeScalar replacementValue = stringifyReplacementValue(replacement);
                    if (Utf8.isUtf8(replacementValue)) {
                        resultNeedsUtf8 = true;
                    }
                    replacementResultTainted |= taintMode && replacementValue.isTainted();
                    replacementStr = replacementValue.toString();
                }

                if (destructiveReplacement
                        && (inputTainted || patternTainted || replacementResultTainted)) {
                    string.tainted = true;
                }

                if (replacementStr != null) {
                    resultBuffer.append(inputStr, lastAppendEnd, matcher.start());
                    resultBuffer.append(replacementStr);
                    lastAppendEnd = matcher.end();
                }

                // If not a global match, break after the first replacement
                if (!regex.regexFlags.isGlobalMatch()) {
                    break;
                }

                if (matcher.end() > matcher.consumedStart()) {
                    searchStart = matcher.end();
                    globalPosition = searchStart;
                    continue;
                }

                int zeroLengthOffset = matcher.end();
                boolean consumedNonEmptyRetry = false;
                if (zeroLengthOffset <= inputStr.length()) {
                    RegexMatcher retryMatcher = regex.selectRecursivePattern(inputValue)
                            .matcher(inputStr, regex.executableCallbacks, inputValue);
                    // The synthetic (?<=[\s\S]) suffix relies on opaque bounds
                    // so a zero-length match at the region start is rejected.
                    setSubstitutionRegion(retryMatcher, zeroLengthOffset, inputStr.length(), false);
                    if (nativeGlobalPosition) retryMatcher.setGlobalPosition(zeroLengthOffset);
                    boolean retryFound = retryMatcher.findNotEmpty();
                    if (retryFound
                            && retryMatcher.start() == zeroLengthOffset
                            && retryMatcher.end() > zeroLengthOffset) {
                        found++;
                        updateReplacementMatchState(regex, retryMatcher, inputStr, inputValue, captureResultsTainted);

                        String retryReplacementStr;
                        if (replacementIsCode) {
                            if (destructiveReplacement) {
                                RuntimePosLvalue.pos(string).set(RuntimePosLvalue.fromMatcherOffset(
                                        string, inputStr, retryMatcher.start()));
                            }
                            RuntimeArray args = (callerArgs != null) ? callerArgs : new RuntimeArray();
                            RuntimeList result = RuntimeCode.apply(replacement, args, RuntimeContextType.SCALAR);
                            RuntimeScalar replacementValue = stringifyReplacementValue(result.scalar());
                            if (Utf8.isUtf8(replacementValue)) {
                                resultNeedsUtf8 = true;
                            }
                            replacementResultTainted |= taintMode && replacementValue.isTainted();
                            retryReplacementStr = replacementValue.toString();
                        } else {
                            RuntimeScalar replacementValue = stringifyReplacementValue(replacement);
                            if (Utf8.isUtf8(replacementValue)) {
                                resultNeedsUtf8 = true;
                            }
                            replacementResultTainted |= taintMode && replacementValue.isTainted();
                            retryReplacementStr = replacementValue.toString();
                        }

                        if (destructiveReplacement
                                && (inputTainted || patternTainted || replacementResultTainted)) {
                            string.tainted = true;
                        }

                        if (retryReplacementStr != null) {
                            resultBuffer.append(inputStr, lastAppendEnd, retryMatcher.start());
                            resultBuffer.append(retryReplacementStr);
                            lastAppendEnd = retryMatcher.end();
                        }
                        searchStart = retryMatcher.end();
                        globalPosition = searchStart;
                        consumedNonEmptyRetry = true;
                    }
                }

                if (!consumedNonEmptyRetry) {
                    searchStart = bumpGlobalMatchPosition(inputStr, zeroLengthOffset);
                    globalPosition = searchStart;
                }
            }
        } catch (RegexTimeoutException e) {
            WarnDie.warn(new RuntimeScalar(e.getMessage() + "\n"), RuntimeScalarCache.scalarEmptyString);
            found = 0;
        }
        if (found == 0) {
            state().lastMatchUsedPFlag = previousMatchUsedPFlag;
        }
        // Append the remaining text after the last match to the result buffer
        resultBuffer.append(inputStr, lastAppendEnd, inputStr.length());

        // Release captures from the replacement closure to unblock DESTROY.
        // The s///eg replacement is compiled as an anonymous sub that captures
        // lexical variables from the enclosing scope (incrementing their captureCount).
        // Since this closure is a JVM stack temporary (not a Perl 'my' variable),
        // scopeExitCleanup is never called for it, so releaseCaptures() would never
        // fire. Without this, captured variables' captureCount stays elevated,
        // preventing refCount decrement at scope exit, and DESTROY never fires.
        if (replacementIsCode && replacement.value instanceof RuntimeCode code) {
            code.releaseCaptures();
        }

        if (found > 0) {
            String finalResult = resultBuffer.toString();

            // Store as last successful pattern for empty pattern reuse
            state().lastMatchUsedPFlag = regex.hasPreservesMatch;
            state().lastSuccessfulPattern = regex;

            if (regex.regexFlags.isNonDestructive()) {
                // /r modifier: return the modified string
                RuntimeScalar rv = new RuntimeScalar(finalResult);
                rv.tainted = inputTainted || patternTainted || replacementResultTainted;
                if (wasByteString && !resultNeedsUtf8 && !containsWideChars(finalResult)) {
                    rv.type = RuntimeScalarType.BYTE_STRING;
                }
                return rv;
            } else {
                // Save the modified string back to the original scalar
                RuntimeScalar substitutedValue = new RuntimeScalar(finalResult);
                substitutedValue.tainted = inputTainted || patternTainted || replacementResultTainted;
                if (wasByteString && !resultNeedsUtf8 && !containsWideChars(finalResult)) {
                    substitutedValue.type = RuntimeScalarType.BYTE_STRING;
                }
                string.set(substitutedValue);
                string.tainted = substitutedValue.tainted;
                // Return the number of substitutions made
                RuntimeScalar count = RuntimeScalarCache.getScalarInt(found);
                if (regex.regexFlags.isGlobalMatch() && (inputTainted || patternTainted)) {
                    count = count.propagateTaint(inputValue, quotedRegex);
                }
                return count;
            }
        } else {
            if (regex.regexFlags.isNonDestructive()) {
                // /r modifier with no matches: return the original string
                return string;
            } else {
                // Perl returns a defined false value for a destructive s/// that
                // does not match.
                return RuntimeScalarCache.scalarEmptyString;
            }
        }
    }

    private static RuntimeScalar stringifyReplacementValue(RuntimeScalar value) {
        return RuntimeScalarType.blessedId(value) != 0 ? Overload.stringify(value) : value;
    }

    /**
     * Method to implement Perl's reset() function.
     * Resets the `matched` flag for each cached regex.
     */
    public static void reset() {
        // Iterate over the state().compiledRegexCache and reset the `matched` flag for each cached regex
        for (Map.Entry<String, RuntimeRegex> entry : state().compiledRegexCache.entrySet()) {
            RuntimeRegex regex = entry.getValue();
            regex.matched = false; // Reset the matched field
        }
        // Also reset m?PAT? patterns cached per-callsite in state().optimizedRegexCache
        for (Map.Entry<Integer, RuntimeScalar> entry : state().optimizedRegexCache.entrySet()) {
            RuntimeScalar scalar = entry.getValue();
            if (scalar.value instanceof RuntimeRegex regex) {
                regex.matched = false;
            }
        }
    }

    /**
     * Initialize/reset all regex state including special variables.
     * This should be called at the start of each script execution to ensure clean state.
     */
    public static void initialize() {
        state().clearMatchState();

        // Reset regex cache matched flags
        reset();
    }

    public static String matchString() {
        if (state().lastMatchedString != null) {
            // Current match data available
            return state().lastMatchedString;
        }
        return null;
    }

    public static String preMatchString() {
        if (state().globalMatchString != null && state().lastMatchStart != -1) {
            // Current match data available
            String result = state().globalMatchString.substring(0, state().lastMatchStart);
            return result;
        }
        return null;
    }

    public static String postMatchString() {
        if (state().globalMatchString != null && state().lastMatchEnd != -1) {
            // Current match data available
            String result = state().globalMatchString.substring(state().lastMatchEnd);
            return result;
        }
        return null;
    }

    public static String captureString(int group) {
        if (group <= 0) {
            return state().lastMatchedString;
        }
        if (state().lastCaptureGroups == null || group > state().lastCaptureGroups.length) {
            return null;
        }
        return state().lastCaptureGroups[group - 1];
    }

    public static String lastCaptureString() {
        if (state().lastCaptureGroups == null || state().lastCaptureGroups.length == 0) {
            return null;
        }
        // $+ returns the highest-numbered capture group that actually participated
        // in the match (i.e., is non-null). Non-participating groups in alternations
        // have null values from Java's Matcher.group().
        for (int i = state().lastCaptureGroups.length - 1; i >= 0; i--) {
            if (state().lastCaptureGroups[i] != null) {
                return state().lastCaptureGroups[i];
            }
        }
        return null;
    }

    /**
     * Creates a RuntimeScalar from a regex match result string, preserving
     * BYTE_STRING type if the matched input was a byte string.
     */
    public static RuntimeScalar makeMatchResultScalar(String value) {
        if (value == null) {
            return RuntimeScalarCache.scalarUndef;
        }
        RuntimeScalar scalar = new RuntimeScalar(value);
        if (state().lastMatchWasByteString) {
            scalar.type = RuntimeScalarType.BYTE_STRING;
        }
        scalar.tainted = state().lastMatchResultsTainted;
        return scalar;
    }

    public static RuntimeScalar matcherStart(int group) {
        if (group == 0) {
            return publicMatcherOffset(state().lastMatchStart);
        }
        if (state().manualCaptureStarts != null && group > 0 && group <= state().manualCaptureStarts.length) {
            return publicMatcherOffset(state().manualCaptureStarts[group - 1]);
        }
        if (state().globalMatcher == null) {
            return scalarUndef;
        }
        try {
            if (group < 0 || group > state().globalMatcher.groupCount()) {
                return scalarUndef;
            }
            int start = state().globalMatcher.start(group);
            if (start == -1) {
                return scalarUndef;
            }
            return publicMatcherOffset(start);
        } catch (IllegalStateException e) {
            return scalarUndef;
        }
    }

    public static RuntimeScalar matcherEnd(int group) {
        if (group == 0) {
            return publicMatcherOffset(state().lastMatchEnd);
        }
        if (state().manualCaptureEnds != null && group > 0 && group <= state().manualCaptureEnds.length) {
            return publicMatcherOffset(state().manualCaptureEnds[group - 1]);
        }
        if (state().globalMatcher == null) {
            return scalarUndef;
        }
        try {
            if (group < 0 || group > state().globalMatcher.groupCount()) {
                return scalarUndef;
            }
            int end = state().globalMatcher.end(group);
            if (end == -1) {
                return scalarUndef;
            }
            return publicMatcherOffset(end);
        } catch (IllegalStateException e) {
            return scalarUndef;
        }
    }

    private static RuntimeScalar publicMatcherOffset(int matcherOffset) {
        RuntimeRegexState regexState = state();
        if (matcherOffset < 0) {
            return scalarUndef;
        }
        if (regexState.lastMatchWasByteString || regexState.globalMatchString == null) {
            return getScalarInt(matcherOffset);
        }
        return getScalarInt(PerlUtfString.perlOffsetForJavaIndex(
                regexState.globalMatchString, matcherOffset));
    }

    public static int matcherSize() {
        if (state().manualCaptureStarts != null) {
            return state().manualCaptureStarts.length + 1;
        }
        if (state().globalMatcher == null) {
            return 0;
        }
        int size = state().globalMatcher.groupCount();
        // +1 because groupCount is zero-based, and we include the entire match
        return size + 1;
    }

    /** Perl trims trailing non-participating captures from {@code @-}, but not {@code @+}. */
    public static int matcherStartSize() {
        int size = matcherSize();
        while (size > 1 && !matcherStart(size - 1).getDefinedBoolean()) {
            size--;
        }
        return size;
    }

    /**
     * Check if a string contains any non-ASCII characters (code point > 127).
     * Used to determine if Unicode matching should be used.
     * 
     * @param s The string to check
     * @return true if the string contains non-ASCII characters
     */
    private static boolean hasNonAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                return true;  // Early exit at first non-ASCII
            }
        }
        return false;
    }

    /**
     * Check if a string contains any Unicode characters (code point > 255).
     * Characters 128-255 are extended ASCII and don't require Unicode semantics.
     * Characters > 255 are true Unicode and should trigger Unicode \w, \d, \s.
     * 
     * @param s The string to check
     * @return true if the string contains Unicode characters (> 255)
     */
    private static boolean hasUnicodeChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 255) {
                return true;  // Early exit at first Unicode char
            }
        }
        return false;
    }

    /**
     * Check if a pattern contains inline ASCII modifier (?a...).
     * When present, we should use ASCII matching even for UTF-8 strings with non-ASCII content.
     * 
     * @param pattern The pattern string to check
     * @return true if the pattern contains inline (?a...) modifier
     */
    private static boolean hasInlineAsciiModifier(String pattern) {
        if (pattern == null) {
            return false;
        }
        // Check for (?a...) inline modifier - matches (?a, (?a:, (?ai, (?ia, etc.
        // The 'a' must appear in the modifier position after (?
        int idx = 0;
        while ((idx = pattern.indexOf("(?", idx)) >= 0) {
            idx += 2;
            // Scan modifier characters until we hit : or )
            while (idx < pattern.length()) {
                char c = pattern.charAt(idx);
                if (c == 'a') {
                    return true;  // Found inline ASCII modifier
                }
                if (c == ':' || c == ')' || c == '-' || c == '<' || c == '=' || c == '!' || c == '{' || c == '#') {
                    break;  // End of modifier section
                }
                idx++;
            }
        }
        return false;
    }

    /**
     * A scoped {@code /u}, {@code /a}, or {@code /aa} group must use the UTF-8
     * Joni variant even when its containing qr// originated in a byte scalar.
     * The byte variant carries {@code PERL_BYTE_PATTERN}, whose global
     * case-fold policy deliberately excludes Latin-1 folds; that policy cannot
     * be relaxed by a nested charset group.
     */
    private static boolean hasInlineUnicodeCaseFoldModifier(String pattern) {
        if (pattern == null) return false;

        boolean escaped = false;
        boolean inClass = false;
        for (int i = 0; i + 2 < pattern.length(); i++) {
            char current = pattern.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '[') {
                inClass = true;
                continue;
            }
            if (current == ']' && inClass) {
                inClass = false;
                continue;
            }
            if (inClass || current != '(' || pattern.charAt(i + 1) != '?') continue;

            boolean negated = false;
            for (int j = i + 2; j < pattern.length(); j++) {
                char modifier = pattern.charAt(j);
                if (modifier == ':' || modifier == ')') break;
                if (modifier == '-') {
                    negated = true;
                    continue;
                }
                if (modifier == '^') continue;
                if (modifier < 'a' || modifier > 'z') break;
                if ((modifier == 'u' || modifier == 'a') && !negated) return true;
            }
        }
        return false;
    }

    private record ResolvedRegex(RuntimeRegex regex, boolean fromCompiledRegex) {}

    private static RegexFlags mergeOperationFlags(RegexFlags baseFlags, String modifiers, String patternString) {
        RegexFlags base = baseFlags != null ? baseFlags : fromModifiers("", patternString);
        RegexFlags operation = fromModifiers(modifiers == null ? "" : modifiers, patternString);

        return new RegexFlags(
                base.isGlobalMatch() || operation.isGlobalMatch(),
                base.keepCurrentPosition() || operation.keepCurrentPosition(),
                base.isNonDestructive() || operation.isNonDestructive(),
                base.isMatchExactlyOnce(),
                base.useGAssertion(),
                base.isExtendedWhitespace(),
                base.isNonCapturing(),
                base.isOptimized() || operation.isOptimized(),
                base.isCaseInsensitive(),
                base.isMultiLine(),
                base.isDotAll(),
                base.isExtended(),
                base.preservesMatch() || operation.preservesMatch(),
                base.isUnicode(),
                base.isAscii(),
                base.isAsciiStrict(),
                base.allowEvalGroup() || operation.allowEvalGroup(),
                base.taintResults() || operation.taintResults()
        );
    }

    /**
     * Resolves a scalar to a RuntimeRegex, handling qr overloading if necessary.
     *
     * @param quotedRegex The scalar that might be a regex or have qr overloading
     * @return The resolved RuntimeRegex
     * @throws PerlCompilerException if qr overload doesn't return proper regex
     */
    private static RuntimeRegex resolveRegex(RuntimeScalar quotedRegex) {
        return resolveRegexWithOrigin(quotedRegex).regex();
    }

    private static ResolvedRegex resolveRegexWithOrigin(RuntimeScalar quotedRegex) {
        // Unwrap readonly scalar
        if (quotedRegex.type == RuntimeScalarType.READONLY_SCALAR) quotedRegex = (RuntimeScalar) quotedRegex.value;

        if (quotedRegex.value instanceof RuntimeRegexTemplate) {
            RuntimeScalar compiled = getQuotedRegex(
                    quotedRegex, RuntimeScalarCache.scalarEmptyString);
            return new ResolvedRegex((RuntimeRegex) compiled.value, false);
        }

        if (quotedRegex.type == RuntimeScalarType.REGEX) {
            return new ResolvedRegex((RuntimeRegex) quotedRegex.value, true);
        }

        // Check if the object has qr overloading
        int blessId = RuntimeScalarType.blessedId(quotedRegex);
        if (blessId < 0) {
            OverloadContext overloadCtx = OverloadContext.prepare(blessId);
            if (overloadCtx != null) {
                // Try qr overload
                RuntimeScalar overloadedResult = overloadCtx.tryOverload("(qr", new RuntimeArray(quotedRegex));
                if (overloadedResult != null) {
                    // The result must be a compiled regex
                    if (overloadedResult.type == RuntimeScalarType.REGEX) {
                        return new ResolvedRegex((RuntimeRegex) overloadedResult.value, true);
                    }
                    throw new PerlCompilerException("Overloaded qr did not return a REGEXP");
                }

                // Try fallback to string conversion
                RuntimeScalar fallbackResult = overloadCtx.tryOverloadFallback(quotedRegex, "(\"\"");
                if (fallbackResult != null) {
                    return new ResolvedRegex(compile(fallbackResult.toString(), ""), false);
                }
            }
        }

        // Default: compile as string
        return new ResolvedRegex(compile(quotedRegex.toString(), ""), false);
    }

    @Override
    public String toString() {
        // Construct the Perl-like regex string with flags
        String displayPattern = executableCallbacks.isEmpty()
                ? patternString
                : RuntimeRegexTemplate.displayPattern(patternString, executableCallbacks);
        if (regexFlags.isExtended() && endsInExtendedModeComment(displayPattern)) {
            // Perl terminates a trailing /x comment before adding the synthetic
            // close parenthesis of the canonical (?^flags:pattern) wrapper.
            displayPattern += "\n";
        }
        return "(?^" + regexFlags.toFlagString() + ":" + displayPattern + ")";
    }

    private static boolean endsInExtendedModeComment(String pattern) {
        boolean escaped = false;
        boolean inClass = false;
        boolean inComment = false;
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (inComment) {
                if (ch == '\n' || ch == '\r') inComment = false;
                continue;
            }
            if (escaped) {
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == '[') {
                inClass = true;
            } else if (ch == ']' && inClass) {
                inClass = false;
            } else if (ch == '(' && !inClass
                    && (pattern.startsWith("(?{", i) || pattern.startsWith("(??{", i))) {
                i = skipExecutableRegexBlock(pattern,
                        i + (pattern.startsWith("(??{", i) ? 3 : 2));
            } else if (ch == '(' && !inClass && i + 2 < pattern.length()
                    && pattern.charAt(i + 1) == '?' && pattern.charAt(i + 2) == '#') {
                i += 3;
                while (i < pattern.length() && pattern.charAt(i) != ')') {
                    if (pattern.charAt(i) == '\\' && i + 1 < pattern.length()) i++;
                    i++;
                }
            } else if (ch == '#' && !inClass) {
                inComment = true;
            }
        }
        return inComment;
    }

    private static int skipExecutableRegexBlock(String pattern, int braceIndex) {
        int depth = 1;
        char quote = 0;
        boolean escaped = false;
        for (int i = braceIndex + 1; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (quote != 0) {
                if (ch == quote) quote = 0;
            } else if (ch == '\'' || ch == '"') {
                quote = ch;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return i;
            }
        }
        return pattern.length() - 1;
    }

    String toExecutableString() {
        return "(?^" + regexFlags.toFlagString() + ":" + patternString + ")";
    }

    /**
     * Returns just the extended character class content if this is an extended character class,
     * otherwise returns the full stringified pattern. This is used when interpolating into
     * another extended character class.
     *
     * @return The extended character class content or full pattern
     */
    public String toExtendedCharClassString() {
        // Check if this is an extended character class pattern
        if (patternString != null && patternString.startsWith("(?[") && patternString.endsWith("])")) {
            // Return just the (?[...]) part without the outer (?^FLAGS:...)
            return patternString;
        }
        // Not an extended character class, return full stringified form
        return toString();
    }

    /**
     * Returns a string representation of the regex reference.
     *
     * @return A string representing the regex reference.
     */
    public String toStringRef() {
        return "REGEXP(0x" + Integer.toHexString(this.hashCode()) + ")";
    }

    /**
     * Returns the integer representation of the regex reference.
     *
     * @return The hash code of the regex.
     */
    public int getIntRef() {
        return this.hashCode();
    }

    /**
     * Returns the double representation of the regex reference.
     *
     * @return The hash code of the regex.
     */
    public double getDoubleRef() {
        return this.hashCode();
    }

    /**
     * Evaluates the boolean representation of the regex reference.
     *
     * @return Always true for regex references.
     */
    public boolean getBooleanRef() {
        return true;
    }

    // Abstract methods from RuntimeBase that need to be implemented

    @Override
    public void addToArray(RuntimeArray array) {
        array.add(new RuntimeScalar(this));
    }

    @Override
    public RuntimeScalar scalar() {
        return new RuntimeScalar(this);
    }

    @Override
    public RuntimeList getList() {
        RuntimeList list = new RuntimeList();
        list.add(new RuntimeScalar(this));
        return list;
    }

    @Override
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        // For regex objects, we don't support array aliasing
        return arr;
    }

    @Override
    public int countElements() {
        return 1; // A regex object counts as 1 element
    }

    @Override
    public boolean getBoolean() {
        return true; // Regex objects are always true
    }

    @Override
    public boolean getDefinedBoolean() {
        return true; // Regex objects are always defined
    }

    @Override
    public RuntimeScalar createReference() {
        return new RuntimeScalar(this);
    }

    @Override
    public RuntimeBase undefine() {
        // Cannot undefine a regex object, return as-is
        return this;
    }

    @Override
    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        return scalar.addToScalar(new RuntimeScalar(this));
    }

    @Override
    public RuntimeArray setFromList(RuntimeList list) {
        // Regex objects don't support setting from list
        return new RuntimeArray();
    }

    @Override
    public RuntimeArray keys() {
        // Regex objects don't have keys
        return new RuntimeArray();
    }

    @Override
    public RuntimeArray values() {
        RuntimeArray arr = new RuntimeArray();
        arr.add(new RuntimeScalar(this));
        return arr;
    }

    @Override
    public RuntimeList each(int ctx) {
        // Regex objects don't support each operation
        return new RuntimeList();
    }

    @Override
    public RuntimeScalar chop() {
        // Cannot chop a regex object
        return scalarUndef;
    }

    @Override
    public RuntimeScalar chomp() {
        // Cannot chomp a regex object
        return scalarUndef;
    }

    @Override
    public Iterator<RuntimeScalar> iterator() {
        // Return a single-element iterator containing this regex as a scalar
        return new Iterator<RuntimeScalar>() {
            private boolean hasNext = true;

            @Override
            public boolean hasNext() {
                return hasNext;
            }

            @Override
            public RuntimeScalar next() {
                if (hasNext) {
                    hasNext = false;
                    return new RuntimeScalar(RuntimeRegex.this);
                }
                throw new java.util.NoSuchElementException();
            }
        };
    }

    // DynamicState interface methods

    @Override
    public void dynamicSaveState() {
        // For regex objects, we don't need to save state as they are immutable
        // The only mutable state is the 'matched' flag for match-once regexes
        // which is handled internally
    }

    @Override
    public void dynamicRestoreState() {
        // For regex objects, we don't need to restore state as they are immutable
        // The only mutable state is the 'matched' flag for match-once regexes
        // which is handled internally
    }

    /**
     * Gets the last matched code block constant value for $^R.
     * The value is encoded in the capture group name itself (e.g., cb00000340032).
     *
     * @return The constant value for $^R, or null if no code block was matched
     */
    public RuntimeScalar getLastCodeBlockResult() {
        RegexMatcher matcher = state().globalMatcher;
        if (matcher == null) {
            return null;
        }

        // Get named groups from the pattern (same as %CAPTURE does)
        Map<String, Integer> namedGroups = matcher.namedGroups();
        if (namedGroups == null) {
            return null;
        }

        // Find the code block capture with the HIGHEST counter that matched
        // For multiple code blocks like a(?{1})b(?{2})c, we want cb011 (counter 11), not cb010 (counter 10)
        String lastMatchedCapture = null;
        int maxCounter = -1;

        for (String groupName : namedGroups.keySet()) {
            if (CaptureNameEncoder.isCodeBlockCapture(groupName)) {
                try {
                    String value = matcher.group(groupName);
                    // If this group matched (even if empty string)
                    if (value != null) {
                        // Extract counter from name: cb010... -> 10
                        int counter = Integer.parseInt(groupName.substring(2, 5));
                        if (counter > maxCounter) {
                            maxCounter = counter;
                            lastMatchedCapture = groupName;
                        }
                    }
                } catch (Exception e) {
                    // Group doesn't exist or didn't match, or parse error
                }
            }
        }

        // Decode the value from the capture name using CaptureNameEncoder
        if (lastMatchedCapture != null) {
            return CaptureNameEncoder.decodeCodeBlockValue(lastMatchedCapture);
        }

        return null;
    }

    /**
     * Check if a string contains any characters with codepoints > 255.
     * Used to determine if a substitution result should be upgraded from
     * BYTE_STRING to STRING (e.g., when the replacement introduced wide characters).
     */
    private static boolean containsWideChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 255) {
                return true;
            }
        }
        return false;
    }

}

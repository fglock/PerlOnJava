package org.perlonjava.runtime.regex;

import org.perlonjava.runtime.operators.Time;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.perlmodule.Utf8;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.regex.RegexFlags.fromModifiers;
import static org.perlonjava.runtime.regex.RegexFlags.validateModifiers;
import static org.perlonjava.runtime.regex.RegexPreprocessor.preProcessRegex;
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

    // Debug flag for regex compilation (set at class load time)
    private static final boolean DEBUG_REGEX = System.getenv("DEBUG_REGEX") != null;

    // Constants for regex pattern flags
    private static final int CASE_INSENSITIVE = Pattern.CASE_INSENSITIVE;
    private static final int MULTILINE = Pattern.MULTILINE;
    private static final int DOTALL = Pattern.DOTALL;
    // Maximum size for the regex cache
    private static final int MAX_REGEX_CACHE_SIZE = 1000;
    // Cache to store compiled regex patterns (synchronized for multiplicity thread-safety)
    private static final Map<String, RuntimeRegex> regexCache = Collections.synchronizedMap(
            new LinkedHashMap<String, RuntimeRegex>(MAX_REGEX_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, RuntimeRegex> eldest) {
                    return size() > MAX_REGEX_CACHE_SIZE;
                }
            });
    // Cache for /o modifier is now per-PerlRuntime (regexOptimizedCache field)

    // ---- Regex match state accessors (delegating to PerlRuntime.current()) ----

    /** Gets the global Matcher from current runtime. */
    public static Matcher getGlobalMatcher() { return PerlRuntime.current().regexGlobalMatcher; }
    /** Sets the global Matcher on current runtime. */
    public static void setGlobalMatcher(Matcher m) { PerlRuntime.current().regexGlobalMatcher = m; }

    /** Gets the global match string from current runtime. */
    public static String getGlobalMatchString() { return PerlRuntime.current().regexGlobalMatchString; }
    /** Sets the global match string on current runtime. */
    public static void setGlobalMatchString(String s) { PerlRuntime.current().regexGlobalMatchString = s; }

    /** Gets lastMatchedString from current runtime. */
    public static String getLastMatchedString() { return PerlRuntime.current().regexLastMatchedString; }
    /** Sets lastMatchedString on current runtime. */
    public static void setLastMatchedString(String s) { PerlRuntime.current().regexLastMatchedString = s; }

    /** Gets lastMatchStart from current runtime. */
    public static int getLastMatchStart() { return PerlRuntime.current().regexLastMatchStart; }
    /** Sets lastMatchStart on current runtime. */
    public static void setLastMatchStart(int v) { PerlRuntime.current().regexLastMatchStart = v; }

    /** Gets lastMatchEnd from current runtime. */
    public static int getLastMatchEnd() { return PerlRuntime.current().regexLastMatchEnd; }
    /** Sets lastMatchEnd on current runtime. */
    public static void setLastMatchEnd(int v) { PerlRuntime.current().regexLastMatchEnd = v; }

    /** Gets lastSuccessfulMatchedString from current runtime. */
    public static String getLastSuccessfulMatchedString() { return PerlRuntime.current().regexLastSuccessfulMatchedString; }
    /** Sets lastSuccessfulMatchedString on current runtime. */
    public static void setLastSuccessfulMatchedString(String s) { PerlRuntime.current().regexLastSuccessfulMatchedString = s; }

    /** Gets lastSuccessfulMatchStart from current runtime. */
    public static int getLastSuccessfulMatchStart() { return PerlRuntime.current().regexLastSuccessfulMatchStart; }
    /** Sets lastSuccessfulMatchStart on current runtime. */
    public static void setLastSuccessfulMatchStart(int v) { PerlRuntime.current().regexLastSuccessfulMatchStart = v; }

    /** Gets lastSuccessfulMatchEnd from current runtime. */
    public static int getLastSuccessfulMatchEnd() { return PerlRuntime.current().regexLastSuccessfulMatchEnd; }
    /** Sets lastSuccessfulMatchEnd on current runtime. */
    public static void setLastSuccessfulMatchEnd(int v) { PerlRuntime.current().regexLastSuccessfulMatchEnd = v; }

    /** Gets lastSuccessfulMatchString from current runtime. */
    public static String getLastSuccessfulMatchString() { return PerlRuntime.current().regexLastSuccessfulMatchString; }
    /** Sets lastSuccessfulMatchString on current runtime. */
    public static void setLastSuccessfulMatchString(String s) { PerlRuntime.current().regexLastSuccessfulMatchString = s; }

    /** Gets lastSuccessfulPattern from current runtime. */
    public static RuntimeRegex getLastSuccessfulPattern() { return PerlRuntime.current().regexLastSuccessfulPattern; }
    /** Sets lastSuccessfulPattern on current runtime. */
    public static void setLastSuccessfulPattern(RuntimeRegex p) { PerlRuntime.current().regexLastSuccessfulPattern = p; }

    /** Gets lastMatchUsedPFlag from current runtime. */
    public static boolean getLastMatchUsedPFlag() { return PerlRuntime.current().regexLastMatchUsedPFlag; }
    /** Sets lastMatchUsedPFlag on current runtime. */
    public static void setLastMatchUsedPFlag(boolean v) { PerlRuntime.current().regexLastMatchUsedPFlag = v; }

    /** Gets lastMatchUsedBackslashK from current runtime. */
    public static boolean getLastMatchUsedBackslashK() { return PerlRuntime.current().regexLastMatchUsedBackslashK; }
    /** Sets lastMatchUsedBackslashK on current runtime. */
    public static void setLastMatchUsedBackslashK(boolean v) { PerlRuntime.current().regexLastMatchUsedBackslashK = v; }

    /** Gets lastCaptureGroups from current runtime. */
    public static String[] getLastCaptureGroups() { return PerlRuntime.current().regexLastCaptureGroups; }
    /** Sets lastCaptureGroups on current runtime. */
    public static void setLastCaptureGroups(String[] g) { PerlRuntime.current().regexLastCaptureGroups = g; }

    /** Gets lastMatchWasByteString from current runtime. */
    public static boolean getLastMatchWasByteString() { return PerlRuntime.current().regexLastMatchWasByteString; }
    /** Sets lastMatchWasByteString on current runtime. */
    public static void setLastMatchWasByteString(boolean v) { PerlRuntime.current().regexLastMatchWasByteString = v; }
    // Compiled regex pattern (for byte strings - ASCII-only \w, \d)
    public Pattern pattern;
    // Compiled regex pattern for Unicode strings (Unicode \w, \d)
    public Pattern patternUnicode;
    // "Notempty" variant patterns for zero-length match guard retry.
    // In Perl, after a zero-length /gc match at position P, the next attempt
    // stays at P but uses NOTEMPTY (forbidding zero-length results, causing
    // backtracking from lazy quantifiers like ??). Java lacks this, so we
    // compile a variant where ?? is converted to ? (greedy) and (?=[\s\S])
    // is prepended to prevent matching at end of string.
    Pattern notemptyPattern;
    Pattern notemptyPatternUnicode;
    int patternFlags;
    int patternFlagsUnicode;
    public String patternString;
    String javaPatternString; // Preprocessed Java-compatible pattern for recompilation
    boolean hasPreservesMatch = false;  // True if /p was used (outer or inline (?p))
    // Indicates if \G assertion is used (set from regexFlags during compilation)
    private boolean useGAssertion = false;
    // Flags for regex behavior
    private RegexFlags regexFlags;
    // Replacement string for substitutions
    private RuntimeScalar replacement = null;
    // Caller's @_ for replacement code evaluation (so $_[0] etc. work in s/// replacement)
    private RuntimeArray callerArgs = null;
    // Tracks if a match has occurred: this is used as a counter for m?PAT?
    private boolean matched = false;
    private boolean hasCodeBlockCaptures = false;  // True if regex has (?{...}) code blocks
    private boolean deferredUserDefinedUnicodeProperties = false;
    private boolean hasBranchReset = false;  // True if pattern uses (?|...) branch reset
    private boolean hasBackslashK = false;   // True if pattern uses \K (keep assertion)

    public RuntimeRegex() {
        this.regexFlags = null;
    }

    /**
     * Creates a tracked copy of this RuntimeRegex for use as a qr// value.
     * The copy shares compiled Pattern objects but has its own refCount = 0,
     * enabling proper reference counting when assigned to user variables.
     * This mirrors Perl 5 where qr// always creates a new SV wrapper around
     * the shared compiled regex.
     */
    public RuntimeRegex cloneTracked() {
        RuntimeRegex copy = new RuntimeRegex();
        copy.pattern = this.pattern;
        copy.patternUnicode = this.patternUnicode;
        copy.notemptyPattern = this.notemptyPattern;
        copy.notemptyPatternUnicode = this.notemptyPatternUnicode;
        copy.patternFlags = this.patternFlags;
        copy.patternFlagsUnicode = this.patternFlagsUnicode;
        copy.patternString = this.patternString;
        copy.javaPatternString = this.javaPatternString;
        copy.hasPreservesMatch = this.hasPreservesMatch;
        copy.useGAssertion = this.useGAssertion;
        copy.regexFlags = this.regexFlags;
        copy.hasCodeBlockCaptures = this.hasCodeBlockCaptures;
        copy.deferredUserDefinedUnicodeProperties = this.deferredUserDefinedUnicodeProperties;
        copy.hasBranchReset = this.hasBranchReset;
        copy.hasBackslashK = this.hasBackslashK;
        // replacement and callerArgs are not copied — they are set per-substitution
        // matched is not copied — each qr// object tracks its own m?PAT? state
        copy.refCount = 0;  // Enable refCount tracking
        return copy;
    }

    /** Returns the regex flags for this compiled pattern. */
    public RegexFlags getRegexFlags() {
        return regexFlags;
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
        // Debug logging
        if (DEBUG_REGEX) {
            System.err.println("RuntimeRegex.compile: pattern=" + patternString + " modifiers=" + modifiers);
            System.err.println("  caller stack: " + Thread.currentThread().getStackTrace()[2]);
        }

        String cacheKey = patternString + "/" + modifiers;

        // Check if the regex is already cached
        RuntimeRegex regex = regexCache.get(cacheKey);
        if (regex == null) {
            if (DEBUG_REGEX) {
                System.err.println("  cache miss, compiling new regex");
            }
            regex = new RuntimeRegex();

            if (patternString != null && patternString.contains("\\Q")) {
                patternString = escapeQ(patternString);
            }

            // Note: flags /e /ee are processed at parse time, in parseRegexReplace()

            validateModifiers(modifiers);

            regex.regexFlags = fromModifiers(modifiers, patternString);
            regex.useGAssertion = regex.regexFlags.useGAssertion();
            regex.patternFlags = regex.regexFlags.toPatternFlags();
            
            // Always compute Unicode flags - we need the Unicode variant for when
            // the input string contains non-ASCII characters (auto-Unicode detection)
            // Only skip Unicode variant if /a flag is explicitly used
            if (!regex.regexFlags.isAscii()) {
                regex.patternFlagsUnicode = regex.patternFlags | Pattern.UNICODE_CHARACTER_CLASS;
            } else {
                regex.patternFlagsUnicode = regex.patternFlags;
            }

            String javaPattern = null;
            try {
                javaPattern = preProcessRegex(patternString, regex.regexFlags);

                // Debug logging
                if (DEBUG_REGEX) {
                    System.err.println("  preprocessed pattern=" + javaPattern);
                }

                // Track if preprocessing deferred user-defined Unicode properties.
                // These need to be resolved later, once the corresponding Perl subs are defined.
                regex.deferredUserDefinedUnicodeProperties = RegexPreprocessor.hadDeferredUnicodePropertyEncountered();
                regex.hasPreservesMatch = regex.regexFlags.preservesMatch() || RegexPreprocessor.hadInlinePFlag();
                regex.hasBranchReset = RegexPreprocessor.hadBranchReset();
                regex.hasBackslashK = RegexPreprocessor.hadBackslashK();

                regex.patternString = patternString;
                regex.javaPatternString = javaPattern;

                // Compile the regex pattern for byte strings (ASCII-only \w, \d)
                regex.pattern = Pattern.compile(javaPattern, regex.patternFlags);
                
                // Compile the Unicode variant for Unicode strings
                // Only compile separately if the flags differ (saves memory when /a or /u is used)
                if (regex.patternFlagsUnicode != regex.patternFlags) {
                    // Fix POSIX [:punct:] for Unicode mode: Java's UNICODE_CHARACTER_CLASS flag
                    // changes \p{Punct} from ASCII punct+symbols to only \p{P} (Unicode Punctuation).
                    // Perl's [:punct:] should match both Punctuation and Symbols in Unicode mode.
                    String javaPatternUnicode = javaPattern
                            .replace("\\p{Punct}", "[\\p{P}\\p{S}]")
                            .replace("\\P{Punct}", "[^\\p{P}\\p{S}]");
                    regex.patternUnicode = Pattern.compile(javaPatternUnicode, regex.patternFlagsUnicode);
                } else {
                    regex.patternUnicode = regex.pattern;
                }

                // Check if pattern has code block captures for $^R optimization
                // Code blocks are encoded as named captures like (?<cb010...>)
                Map<String, Integer> namedGroups = regex.pattern.namedGroups();
                if (namedGroups != null) {
                    for (String groupName : namedGroups.keySet()) {
                        if (CaptureNameEncoder.isCodeBlockCapture(groupName)) {
                            regex.hasCodeBlockCaptures = true;
                            break;
                        }
                    }
                }

                // Compile "notempty" variant for /g patterns.
                // This is used after a zero-length match to retry at the same position
                // with a regex that prefers non-zero-length matches (like Perl's NOTEMPTY).
                // Transform: prepend (?=[\s\S]) and convert ?? to ? (lazy→greedy).
                if (regex.regexFlags.isGlobalMatch() && javaPattern != null) {
                    try {
                        String notemptyJava = "(?=[\\s\\S])" + javaPattern.replace("??", "?");
                        regex.notemptyPattern = Pattern.compile(notemptyJava, regex.patternFlags);
                        if (regex.patternFlagsUnicode != regex.patternFlags) {
                            String notemptyUnicode = "(?=[\\s\\S])" + javaPattern
                                    .replace("\\p{Punct}", "[\\p{P}\\p{S}]")
                                    .replace("\\P{Punct}", "[^\\p{P}\\p{S}]")
                                    .replace("??", "?");
                            regex.notemptyPatternUnicode = Pattern.compile(notemptyUnicode, regex.patternFlagsUnicode);
                        } else {
                            regex.notemptyPatternUnicode = regex.notemptyPattern;
                        }
                    } catch (Exception ignore) {
                        // If notempty compilation fails, fall back to bumpalong
                        regex.notemptyPattern = null;
                        regex.notemptyPatternUnicode = null;
                    }
                }
            } catch (Exception e) {
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
                    String patternInfo = " [pattern='" + (patternString == null ? "" : patternString) + "'" +
                            (javaPattern != null ? ", java='" + javaPattern + "'" : "") + "]";
                    String errorMessage = base + patternInfo;
                    // Ensure error message ends with newline to prevent running into test output
                    if (!errorMessage.endsWith("\n")) {
                        errorMessage += "\n";
                    }
                    WarnDie.warn(new RuntimeScalar(errorMessage), new RuntimeScalar());
                    regex.pattern = Pattern.compile(Character.toString(0) + "ERROR" + Character.toString(0), Pattern.DOTALL);
                    regex.patternUnicode = regex.pattern;  // Error pattern - same for both
                    // Ensure patternString is set so downstream code doesn't NPE
                    if (regex.patternString == null) {
                        regex.patternString = patternString != null ? patternString : "";
                    }
                } else {
                    throw unimplEx;
                }
            }

            // Cache the result if the cache is not full
            if (regexCache.size() < MAX_REGEX_CACHE_SIZE) {
                regexCache.put(cacheKey, regex);
            }
        } else {
            // Debug logging for cache hit
            if (DEBUG_REGEX) {
                System.err.println("  cache hit, reusing cached regex");
            }
        }
        return regex;
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
        regexCache.remove(cacheKey);

        RuntimeRegex recompiled = compile(regex.patternString, regex.regexFlags == null ? "" : regex.regexFlags.toFlagString());
        regex.pattern = recompiled.pattern;
        regex.patternUnicode = recompiled.patternUnicode;
        regex.patternFlags = recompiled.patternFlags;
        regex.regexFlags = recompiled.regexFlags;
        regex.useGAssertion = recompiled.useGAssertion;
        regex.deferredUserDefinedUnicodeProperties = recompiled.deferredUserDefinedUnicodeProperties;
        return regex;
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
    public static RuntimeScalar getQuotedRegex(RuntimeScalar patternString, RuntimeScalar modifiers) {
        String modifierStr = modifiers.toString();

        // Unwrap readonly scalar
        if (patternString.type == RuntimeScalarType.READONLY_SCALAR) patternString = (RuntimeScalar) patternString.value;

        // Check if patternString is already a compiled regex
        if (patternString.type == RuntimeScalarType.REGEX) {
            RuntimeRegex originalRegex = (RuntimeRegex) patternString.value;

            if (modifierStr.isEmpty()) {
                // No new modifiers, return the original regex as-is
                return patternString;
            }

            // Create a new regex with merged flags
            RuntimeRegex regex = new RuntimeRegex();
            regex.pattern = originalRegex.pattern;
            regex.patternUnicode = originalRegex.patternUnicode;
            regex.patternString = originalRegex.patternString;
            regex.hasPreservesMatch = originalRegex.hasPreservesMatch;
            regex.regexFlags = mergeRegexFlags(originalRegex.regexFlags, modifierStr, originalRegex.patternString);
            regex.hasPreservesMatch = regex.hasPreservesMatch || regex.regexFlags.preservesMatch();
            regex.useGAssertion = regex.regexFlags.useGAssertion();
            regex.patternFlags = regex.regexFlags.toPatternFlags();
            regex.refCount = 0;  // Track for proper weak ref handling

            return new RuntimeScalar(regex);
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

                    if (modifierStr.isEmpty()) {
                        // No new modifiers, return the overloaded regex as-is
                        return overloadedResult;
                    }

                    // Create a new regex with merged flags
                    RuntimeRegex regex = new RuntimeRegex();
                    regex.pattern = originalRegex.pattern;
                    regex.patternUnicode = originalRegex.patternUnicode;
                    regex.patternString = originalRegex.patternString;
                    regex.hasPreservesMatch = originalRegex.hasPreservesMatch;
                    regex.regexFlags = mergeRegexFlags(originalRegex.regexFlags, modifierStr, originalRegex.patternString);
                    regex.hasPreservesMatch = regex.hasPreservesMatch || regex.regexFlags.preservesMatch();
                    regex.useGAssertion = regex.regexFlags.useGAssertion();
                    regex.patternFlags = regex.regexFlags.toPatternFlags();
                    regex.refCount = 0;  // Track for proper weak ref handling

                    return new RuntimeScalar(regex);
                }

                // Try fallback to string conversion
                RuntimeScalar fallbackResult = overloadCtx.tryOverloadFallback(patternString, "(\"\"");
                if (fallbackResult != null) {
                    return new RuntimeScalar(compile(fallbackResult.toString(), modifierStr).cloneTracked());
                }
            }
        }

        // Default: compile as string (cloneTracked() creates a tracked copy
        // so the cached RuntimeRegex is not corrupted by refCount changes)
        return new RuntimeScalar(compile(patternString.toString(), modifierStr).cloneTracked());
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
        String modifierStr = modifiers.toString();
        
        // Check if /o or m?PAT? modifier is present (both need per-callsite caching
        // to preserve state: /o caches the compiled pattern, m?PAT? preserves the
        // 'matched' flag that tracks whether the pattern has already matched once)
        if (modifierStr.contains("o") || modifierStr.contains("?")) {
            Map<Integer, RuntimeScalar> cache = PerlRuntime.current().regexOptimizedCache;
            // Check if we already have a cached regex for this callsite
            RuntimeScalar cached = cache.get(callsiteId);
            if (cached != null) {
                return cached;
            }
            
            // Compile the regex and cache it
            RuntimeScalar result = getQuotedRegex(patternString, modifiers);
            cache.put(callsiteId, result);
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
        RuntimeRegex resolvedRegex = resolveRegex(patternString);
        String modifierStr = modifiers.toString();

        // Create a new regex instance with the replacement
        RuntimeRegex regex = new RuntimeRegex();

        // Always start with the resolved regex properties
        regex.pattern = resolvedRegex.pattern;
        regex.patternUnicode = resolvedRegex.patternUnicode;
        regex.patternString = resolvedRegex.patternString;
        regex.regexFlags = resolvedRegex.regexFlags;
        regex.hasPreservesMatch = resolvedRegex.hasPreservesMatch;
        regex.useGAssertion = resolvedRegex.useGAssertion;
        regex.patternFlags = resolvedRegex.patternFlags;
        regex.hasBranchReset = resolvedRegex.hasBranchReset;
        regex.hasBackslashK = resolvedRegex.hasBackslashK;
        regex.hasCodeBlockCaptures = resolvedRegex.hasCodeBlockCaptures;

        // Only recompile if we have new modifiers that actually change the flags
        if (!modifierStr.isEmpty()) {
            RegexFlags newFlags = mergeRegexFlags(resolvedRegex.regexFlags, modifierStr, resolvedRegex.patternString);

            // Check if the merged flags are actually different
            boolean flagsChanged = false;
            if (resolvedRegex.regexFlags == null) {
                flagsChanged = !newFlags.toFlagString().isEmpty();
            } else {
                flagsChanged = !resolvedRegex.regexFlags.toFlagString().equals(newFlags.toFlagString());
            }

            // Only recompile if flags actually changed (this is needed for /x preprocessing)
            if (flagsChanged) {
                RuntimeRegex recompiledRegex = compile(resolvedRegex.patternString, newFlags.toFlagString());
                regex.pattern = recompiledRegex.pattern;
                regex.patternUnicode = recompiledRegex.patternUnicode;
                regex.patternString = recompiledRegex.patternString;
                regex.regexFlags = recompiledRegex.regexFlags;
                regex.hasPreservesMatch = recompiledRegex.hasPreservesMatch;
                regex.useGAssertion = recompiledRegex.useGAssertion;
                regex.patternFlags = recompiledRegex.patternFlags;
                regex.hasBranchReset = recompiledRegex.hasBranchReset;
                regex.hasBackslashK = recompiledRegex.hasBackslashK;
                regex.hasCodeBlockCaptures = recompiledRegex.hasCodeBlockCaptures;
            } else {
                // Just update the flags without recompiling
                regex.regexFlags = newFlags;
                regex.hasPreservesMatch = regex.hasPreservesMatch || newFlags.preservesMatch();
                regex.useGAssertion = newFlags.useGAssertion();
                regex.patternFlags = newFlags.toPatternFlags();
            }
        }

        regex.replacement = replacement;
        return new RuntimeScalar(regex);
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
        RuntimeRegex regex = resolveRegex(quotedRegex);
        regex = ensureCompiledForRuntime(regex);
        if (regex.replacement != null) {
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

    /**
     * Direct regex matching without timeout wrapper (fast path).
     */
    private static RuntimeBase matchRegexDirect(RuntimeScalar quotedRegex, RuntimeScalar string, int ctx) {
        RuntimeRegex regex = resolveRegex(quotedRegex);
        regex = ensureCompiledForRuntime(regex);
        
        // Save original flags before potentially changing regex
        RegexFlags originalFlags = regex.regexFlags;

        // Handle empty pattern - reuse last successful pattern or use empty pattern
        if (regex.patternString == null || regex.patternString.isEmpty()) {
            if (PerlRuntime.current().regexLastSuccessfulPattern != null) {
                // Use the pattern from last successful match
                // But keep the current flags (especially /g and /i)
                Pattern pattern = PerlRuntime.current().regexLastSuccessfulPattern.pattern;
                // Re-apply current flags if they differ
                if (originalFlags != null && !originalFlags.equals(PerlRuntime.current().regexLastSuccessfulPattern.regexFlags)) {
                    // Need to recompile with current flags using preprocessed pattern
                    int newFlags = originalFlags.toPatternFlags();
                    String recompilePattern = PerlRuntime.current().regexLastSuccessfulPattern.javaPatternString != null
                            ? PerlRuntime.current().regexLastSuccessfulPattern.javaPatternString : PerlRuntime.current().regexLastSuccessfulPattern.patternString;
                    pattern = Pattern.compile(recompilePattern, newFlags);
                }
                // Create a temporary regex with the right pattern and current flags
                RuntimeRegex tempRegex = new RuntimeRegex();
                tempRegex.pattern = pattern;
                tempRegex.patternUnicode = PerlRuntime.current().regexLastSuccessfulPattern.patternUnicode;
                tempRegex.patternString = PerlRuntime.current().regexLastSuccessfulPattern.patternString;
                tempRegex.javaPatternString = PerlRuntime.current().regexLastSuccessfulPattern.javaPatternString;
                tempRegex.hasPreservesMatch = PerlRuntime.current().regexLastSuccessfulPattern.hasPreservesMatch || (originalFlags != null && originalFlags.preservesMatch());
                tempRegex.regexFlags = originalFlags;
                tempRegex.useGAssertion = originalFlags != null && originalFlags.useGAssertion();
                regex = tempRegex;
            }
            // If no previous pattern, the empty pattern matches empty string at start (default behavior)
        }

        // Debug logging
        if (DEBUG_REGEX) {
            System.err.println("matchRegexDirect: pattern=" + regex.pattern.pattern() +
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

        Pattern pattern = regex.pattern;
        String inputStr = string.toString();
        
        // Select appropriate pattern based on string's UTF-8 flag:
        // - /a flag or inline (?a): always use ASCII-only pattern
        // - BYTE_STRING: use ASCII-only pattern (Perl's "bytes" semantics)
        // - UTF-8 string: use Unicode pattern (Perl uses Unicode semantics for \w, \d, \s
        //   whenever the string has the UTF-8 flag, even for Latin-1 characters like é)
        if (regex.patternUnicode != null && regex.patternUnicode != regex.pattern) {
            if (regex.regexFlags != null && regex.regexFlags.isAscii()) {
                // /a flag - always ASCII
                pattern = regex.pattern;
            } else if (hasInlineAsciiModifier(regex.patternString)) {
                // Inline (?a...) in pattern - use ASCII to be safe
                pattern = regex.pattern;
            } else if (Utf8.isUtf8(string)) {
                // UTF-8 string - use Unicode matching for \w, \d, \s semantics
                pattern = regex.patternUnicode;
            }
            // else: BYTE_STRING - keep ASCII pattern (default)
        }
        
        // Workaround for Java MULTILINE quirk: Java's Pattern.MULTILINE changes ^ to only
        // match after line terminators, so "^" fails on empty strings. In Perl, /m makes ^
        // and $ match at line boundaries AND at start/end of string. Since empty strings have
        // no line breaks, MULTILINE is irrelevant and we can safely strip it.
        if (inputStr.isEmpty() && (pattern.flags() & Pattern.MULTILINE) != 0) {
            pattern = Pattern.compile(pattern.pattern(), pattern.flags() & ~Pattern.MULTILINE);
        }

        CharSequence matchInput = new RegexTimeoutCharSequence(inputStr);
        Matcher matcher = pattern.matcher(matchInput);

        // hexPrinter(inputStr);

        // Look up pos() for /g matches and for non-/g matches that use \G.
        // In Perl, \G anchors at pos() even in non-/g matches (e.g. $str =~ /\Gfoo/).
        RuntimeScalar posScalar = null;
        boolean isPosDefined = false;
        int startPos = 0;
        // Flag to skip the first find() when the notempty variant already found a match
        boolean skipFirstFind = false;
        
        if (regex.regexFlags.isGlobalMatch() || regex.useGAssertion) {
            // Use RuntimePosLvalue to get the current position
            posScalar = RuntimePosLvalue.pos(string);
            isPosDefined = posScalar.getDefinedBoolean();
            startPos = isPosDefined ? posScalar.getInt() : 0;
            
            // Check if previous call had zero-length match at this position (for SCALAR context)
            // This prevents infinite loops in: while ($str =~ /pat/g)
            // In Perl, after a zero-length match, the next attempt stays at the same position
            // but uses NOTEMPTY (forbidding zero-length results). Java lacks NOTEMPTY, so we
            // use a precompiled "notempty" variant that converts ?? to ? (lazy→greedy) and
            // adds (?=[\s\S]) to prevent matching at end of string.
            if (regex.regexFlags.isGlobalMatch() && ctx == RuntimeContextType.SCALAR) {
                String patternKey = regex.patternString;
                if (RuntimePosLvalue.hadZeroLengthMatchAt(string, startPos, patternKey)) {
                    // First, try the notempty variant at the SAME position (Perl behavior)
                    boolean notemptySucceeded = false;
                    if (regex.notemptyPattern != null) {
                        // Select the right notempty pattern variant (byte/unicode)
                        Pattern notemptyPat = regex.notemptyPattern;
                        if (regex.notemptyPatternUnicode != null && regex.notemptyPatternUnicode != regex.notemptyPattern) {
                            if (!(regex.regexFlags != null && regex.regexFlags.isAscii())
                                    && !hasInlineAsciiModifier(regex.patternString)
                                    && Utf8.isUtf8(string)) {
                                notemptyPat = regex.notemptyPatternUnicode;
                            }
                        }
                        Matcher notemptyMatcher = notemptyPat.matcher(matchInput);
                        notemptyMatcher.region(startPos, inputStr.length());
                        if (notemptyMatcher.find()) {
                            // Check \G constraint: match must start at startPos
                            if (!regex.useGAssertion || notemptyMatcher.start() == startPos) {
                                // Verify it's actually non-zero-length
                                if (notemptyMatcher.end() > notemptyMatcher.start()) {
                                    // Success! Use the notempty matcher's result
                                    matcher = notemptyMatcher;
                                    skipFirstFind = true;
                                    notemptySucceeded = true;
                                    RuntimePosLvalue.recordNonZeroLengthMatch(string);
                                }
                            }
                        }
                    }
                    
                    if (!notemptySucceeded) {
                        // Notempty variant didn't find a match; fall back to bumpalong
                        startPos++;
                        if (startPos > inputStr.length()) {
                            // Past end of string, fail
                            if (!regex.regexFlags.keepCurrentPosition()) {
                                posScalar.set(scalarUndef);
                            }
                            return RuntimeScalarCache.scalarFalse;
                        }
                        posScalar.set(startPos);
                        RuntimePosLvalue.recordNonZeroLengthMatch(string);
                        isPosDefined = true;
                    }
                }
            }
        }

        // Start matching from the current position if defined
        // (skip if notempty variant already found a match - region() would reset the matcher)
        if (isPosDefined && !skipFirstFind) {
            matcher.region(startPos, inputStr.length());
        }

        boolean found = false;
        RuntimeList result = new RuntimeList();
        List<RuntimeBase> matchedGroups = result.elements;

        int capture = 1;
        int previousPos = startPos; // Track the previous position  
        int previousMatchEnd = -1;  // Track end of previous match
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
                if (regex.useGAssertion && matcher.start() != startPos) {
                    break;
                }

                found = true;
                PerlRuntime.current().regexLastMatchWasByteString = (string.type == RuntimeScalarType.BYTE_STRING);
                int captureCount = matcher.groupCount();

                // Always initialize $1, $2, @+, @-, $`, $&, $' for every successful match
                PerlRuntime.current().regexGlobalMatcher = matcher;
                PerlRuntime.current().regexGlobalMatchString = inputStr;
                PerlRuntime.current().regexLastMatchUsedBackslashK = regex.hasBackslashK;
                if (captureCount > 0) {
                    if (regex.hasBackslashK) {
                        // Skip the internal perlK capture group
                        int perlKGroup = getPerlKGroup(matcher);
                        int userGroupCount = captureCount - 1;
                        if (userGroupCount > 0) {
                            PerlRuntime.current().regexLastCaptureGroups = new String[userGroupCount];
                            int destIdx = 0;
                            for (int i = 1; i <= captureCount; i++) {
                                if (i == perlKGroup) continue;
                                PerlRuntime.current().regexLastCaptureGroups[destIdx++] = matcher.group(i);
                            }
                        } else {
                            PerlRuntime.current().regexLastCaptureGroups = null;
                        }
                    } else {
                        PerlRuntime.current().regexLastCaptureGroups = new String[captureCount];
                        for (int i = 0; i < captureCount; i++) {
                            PerlRuntime.current().regexLastCaptureGroups[i] = matcher.group(i + 1);
                        }
                    }
                } else {
                    PerlRuntime.current().regexLastCaptureGroups = null;
                }

                // For \K, adjust match start/string so $& is only the post-\K portion
                if (regex.hasBackslashK) {
                    int keepEnd = matcher.end("perlK");
                    PerlRuntime.current().regexLastMatchedString = inputStr.substring(keepEnd, matcher.end());
                    PerlRuntime.current().regexLastMatchStart = keepEnd;
                } else {
                    PerlRuntime.current().regexLastMatchedString = matcher.group(0);
                    PerlRuntime.current().regexLastMatchStart = matcher.start();
                }
                PerlRuntime.current().regexLastMatchEnd = matcher.end();

                if (regex.regexFlags.isGlobalMatch() && captureCount < 1 && ctx == RuntimeContextType.LIST) {
                    // Global match and no captures, in list context return the matched string
                    String matchedStr = regex.hasBackslashK ? PerlRuntime.current().regexLastMatchedString : matcher.group(0);
                    matchedGroups.add(makeMatchResultScalar(matchedStr));
                } else {
                    // save captures in return list if needed
                    if (ctx == RuntimeContextType.LIST) {
                        int perlKGroup = regex.hasBackslashK ? getPerlKGroup(matcher) : -1;
                        for (int i = 1; i <= captureCount; i++) {
                            if (i == perlKGroup) continue; // skip internal \K marker group
                            String matchedStr = matcher.group(i);
                            if (regex.hasBranchReset) {
                                // For branch reset patterns (?|...), skip null groups
                                // because Java creates separate groups for each alternative
                                // but Perl reuses group numbers across alternatives
                                if (matchedStr != null) {
                                    matchedGroups.add(makeMatchResultScalar(matchedStr));
                                }
                            } else {
                                // Include undef for groups that didn't participate in the match
                                // This is important for patterns like m{^(.*/)?(.*)}s where
                                // the optional group returns undef when it doesn't match
                                matchedGroups.add(makeMatchResultScalar(matchedStr));
                            }
                        }
                    }
                }

                if (regex.regexFlags.isGlobalMatch()) {
                    // Update the position for the next match
                    int matchStart = matcher.start();
                    int matchEnd = matcher.end();

                    // Detect zero-length match that would cause infinite loop
                    if (matchEnd == matchStart && matchStart == previousMatchEnd) {
                        // Consecutive zero-length match at same position - advance by 1 or stop
                        if (matchEnd >= inputStr.length()) {
                            // At end of string, stop matching
                            break;
                        }
                        // In middle of string, advance by 1 to avoid infinite loop
                        matchEnd = matchStart + 1;
                    }

                    previousMatchEnd = matchEnd;

                    if (ctx == RuntimeContextType.SCALAR || ctx == RuntimeContextType.VOID) {
                        // Set pos to the end of the current match to prepare for the next search
                        // (only for global matches - posScalar is null for non-global)
                        if (posScalar != null) {
                            posScalar.set(matchEnd);
                            // Record zero-length match for cross-call tracking
                            if (matchEnd == matchStart) {
                                RuntimePosLvalue.recordZeroLengthMatch(string, matchEnd, regex.patternString);
                            } else {
                                RuntimePosLvalue.recordNonZeroLengthMatch(string);
                            }
                        }
                        break; // Break out of the loop after the first match in SCALAR context
                    } else {
                        startPos = matchEnd;
                        if (posScalar != null) {
                            posScalar.set(startPos);
                        }
                        // Update matcher region if we advanced past a zero-length match
                        if (startPos > matchStart) {
                            matcher.region(startPos, inputStr.length());
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
        }

        // Reset pos() on failed match with /g, unless /c is set
        if (!found && regex.regexFlags.isGlobalMatch() && !regex.regexFlags.keepCurrentPosition() && posScalar != null) {
            posScalar.set(scalarUndef);
        }

        // Debug logging
        if (DEBUG_REGEX) {
            System.err.println("  match result: found=" + found);
        }

        if (!found) {
            // No match: scalar match vars ($`, $&, $') should become undef.
            // Keep lastSuccessful* and the previous PerlRuntime.current().regexGlobalMatcher intact so @-/@+ do not get clobbered
            // by internal regex checks that fail (e.g. in test libraries).
            PerlRuntime.current().regexGlobalMatchString = null;
            PerlRuntime.current().regexLastMatchedString = null;
            PerlRuntime.current().regexLastMatchStart = -1;
            PerlRuntime.current().regexLastMatchEnd = -1;
            // Don't clear PerlRuntime.current().regexLastCaptureGroups - Perl preserves $1 across failed matches
        }

        if (found) {
            regex.matched = true; // Counter for m?PAT?
            PerlRuntime.current().regexLastMatchUsedPFlag = regex.hasPreservesMatch;
            PerlRuntime.current().regexLastSuccessfulPattern = regex;
            // Store last successful match information (persists across failed matches)
            PerlRuntime.current().regexLastSuccessfulMatchedString = PerlRuntime.current().regexLastMatchedString;
            PerlRuntime.current().regexLastSuccessfulMatchStart = PerlRuntime.current().regexLastMatchStart;
            PerlRuntime.current().regexLastSuccessfulMatchEnd = PerlRuntime.current().regexLastMatchEnd;
            PerlRuntime.current().regexLastSuccessfulMatchString = PerlRuntime.current().regexGlobalMatchString;

            // Update $^R if this regex has code block captures (performance optimization)
            if (regex.hasCodeBlockCaptures) {
                RuntimeScalar codeBlockResult = regex.getLastCodeBlockResult();
                // Set $^R to the code block result (or undef if no code blocks matched)
                GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("R"))
                        .set(codeBlockResult != null ? codeBlockResult : RuntimeScalarCache.scalarUndef);
            }

            // Reset pos() after global match in LIST context (matches Perl behavior)
            if (regex.regexFlags.isGlobalMatch() && ctx == RuntimeContextType.LIST && posScalar != null) {
                posScalar.set(scalarUndef);
            }
            // System.err.println("DEBUG: Match completed, PerlRuntime.current().regexGlobalMatcher is " + (PerlRuntime.current().regexGlobalMatcher == null ? "null" : "set"));
        } else {
            // System.err.println("DEBUG: No match found, PerlRuntime.current().regexGlobalMatcher is " + (PerlRuntime.current().regexGlobalMatcher == null ? "null" : "set"));
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
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<RuntimeBase> future = executor.submit(() -> {
            return matchRegexDirect(quotedRegex, string, ctx);
        });

        try {
            // Wait for result with timeout
            RuntimeBase result = future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            return result;
        } catch (java.util.concurrent.TimeoutException e) {
            // Regex timed out - cancel it and process alarm signal
            future.cancel(true);
            executor.shutdownNow();
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
            executor.shutdownNow();
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new PerlCompilerException("Regex matching failed: " + cause.getMessage());
        } catch (InterruptedException e) {
            // Thread was interrupted - clean up and check signals
            future.cancel(true);
            executor.shutdownNow();
            PerlSignalQueue.checkPendingSignals();
            if (ctx == RuntimeContextType.LIST) {
                return new RuntimeList();
            } else {
                return RuntimeScalarCache.scalarFalse;
            }
        } finally {
            executor.shutdown();
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
    public static RuntimeBase replaceRegex(RuntimeScalar quotedRegex, RuntimeScalar string, int ctx) {
        // Convert the input string to a Java string
        String inputStr = string.toString();

        // Extract the regex pattern from the quotedRegex object
        RuntimeRegex regex = resolveRegex(quotedRegex);

        // Save the original replacement and flags before potentially changing regex
        RuntimeScalar replacement = regex.replacement;
        RegexFlags originalFlags = regex.regexFlags;

        // Handle empty pattern - reuse last successful pattern or use empty pattern
        if (regex.patternString == null || regex.patternString.isEmpty()) {
            if (PerlRuntime.current().regexLastSuccessfulPattern != null) {
                // Use the pattern from last successful match
                // But keep the current replacement and flags (especially /g and /i)
                Pattern pattern = PerlRuntime.current().regexLastSuccessfulPattern.pattern;
                // Re-apply current flags if they differ
                if (originalFlags != null && !originalFlags.equals(PerlRuntime.current().regexLastSuccessfulPattern.regexFlags)) {
                    // Need to recompile with current flags using preprocessed pattern
                    int newFlags = originalFlags.toPatternFlags();
                    String recompilePattern = PerlRuntime.current().regexLastSuccessfulPattern.javaPatternString != null
                            ? PerlRuntime.current().regexLastSuccessfulPattern.javaPatternString : PerlRuntime.current().regexLastSuccessfulPattern.patternString;
                    pattern = Pattern.compile(recompilePattern, newFlags);
                }
                // Create a temporary regex with the right pattern and current flags
                RuntimeRegex tempRegex = new RuntimeRegex();
                tempRegex.pattern = pattern;
                tempRegex.patternUnicode = PerlRuntime.current().regexLastSuccessfulPattern.patternUnicode;
                tempRegex.patternString = PerlRuntime.current().regexLastSuccessfulPattern.patternString;
                tempRegex.javaPatternString = PerlRuntime.current().regexLastSuccessfulPattern.javaPatternString;
                tempRegex.hasPreservesMatch = PerlRuntime.current().regexLastSuccessfulPattern.hasPreservesMatch || (originalFlags != null && originalFlags.preservesMatch());
                tempRegex.regexFlags = originalFlags;
                tempRegex.useGAssertion = originalFlags != null && originalFlags.useGAssertion();
                tempRegex.replacement = replacement;
                regex = tempRegex;
            } else {
                // No previous regex - use empty pattern (matches empty string at start)
                // This matches Perl's behavior: s//x/ inserts 'x' at the beginning
                RuntimeRegex tempRegex = new RuntimeRegex();
                int flags = originalFlags != null ? originalFlags.toPatternFlags() : 0;
                tempRegex.pattern = Pattern.compile("", flags);
                tempRegex.patternUnicode = tempRegex.pattern;  // Empty pattern - same for both
                tempRegex.patternString = "";
                tempRegex.regexFlags = originalFlags;
                tempRegex.useGAssertion = originalFlags != null && originalFlags.useGAssertion();
                tempRegex.replacement = replacement;
                regex = tempRegex;
            }
        }

        Pattern pattern = regex.pattern;
        
        // Select appropriate pattern based on string's UTF-8 flag (same logic as matchRegex)
        if (regex.patternUnicode != null && regex.patternUnicode != regex.pattern) {
            if (regex.regexFlags != null && regex.regexFlags.isAscii()) {
                // /a flag - always ASCII
                pattern = regex.pattern;
            } else if (hasInlineAsciiModifier(regex.patternString)) {
                // Inline (?a...) in pattern - use ASCII to be safe
                pattern = regex.pattern;
            } else if (Utf8.isUtf8(string)) {
                // UTF-8 string - use Unicode matching for \w, \d, \s semantics
                pattern = regex.patternUnicode;
            }
            // else: BYTE_STRING - keep ASCII pattern (default)
        }
        
        // Workaround for Java MULTILINE quirk (same as matchRegexDirect)
        if (inputStr.isEmpty() && (pattern.flags() & Pattern.MULTILINE) != 0) {
            pattern = Pattern.compile(pattern.pattern(), pattern.flags() & ~Pattern.MULTILINE);
        }

        CharSequence matchInput = new RegexTimeoutCharSequence(inputStr);
        Matcher matcher = pattern.matcher(matchInput);

        // The result string after substitutions
        StringBuilder resultBuffer = new StringBuilder();
        int found = 0;

        // Unwrap readonly scalar
        if (replacement.type == RuntimeScalarType.READONLY_SCALAR) replacement = (RuntimeScalar) replacement.value;

        // Determine if the replacement is a code that needs to be evaluated
        boolean replacementIsCode = (replacement.type == RuntimeScalarType.CODE);

        // Don't reset PerlRuntime.current().regexGlobalMatcher here - only reset it if we actually find a match
        // This preserves capture variables from previous matches when substitution doesn't match

        // Track position for manual replacement when \K is used
        int lastAppendEnd = 0;

        // Perform the substitution
        try {
            while (matcher.find()) {
                found++;
                PerlRuntime.current().regexLastMatchWasByteString = (string.type == RuntimeScalarType.BYTE_STRING);

                // Initialize $1, $2, @+, @- only when we have a match
                PerlRuntime.current().regexGlobalMatcher = matcher;
                PerlRuntime.current().regexGlobalMatchString = inputStr;
                PerlRuntime.current().regexLastMatchUsedBackslashK = regex.hasBackslashK;
                if (matcher.groupCount() > 0) {
                    if (regex.hasBackslashK) {
                        // Skip the internal perlK capture group when populating $1, $2, etc.
                        int perlKGroup = getPerlKGroup(matcher);
                        int userGroupCount = matcher.groupCount() - 1;
                        if (userGroupCount > 0) {
                            PerlRuntime.current().regexLastCaptureGroups = new String[userGroupCount];
                            int destIdx = 0;
                            for (int i = 1; i <= matcher.groupCount(); i++) {
                                if (i == perlKGroup) continue;
                                PerlRuntime.current().regexLastCaptureGroups[destIdx++] = matcher.group(i);
                            }
                        } else {
                            PerlRuntime.current().regexLastCaptureGroups = null;
                        }
                    } else {
                        PerlRuntime.current().regexLastCaptureGroups = new String[matcher.groupCount()];
                        for (int i = 0; i < matcher.groupCount(); i++) {
                            PerlRuntime.current().regexLastCaptureGroups[i] = matcher.group(i + 1);
                        }
                    }
                } else {
                    PerlRuntime.current().regexLastCaptureGroups = null;
                }

                // For \K, adjust match start so $& is only the post-\K portion
                if (regex.hasBackslashK) {
                    int keepEnd = matcher.end("perlK");
                    PerlRuntime.current().regexLastMatchStart = keepEnd;
                    PerlRuntime.current().regexLastMatchedString = inputStr.substring(keepEnd, matcher.end());
                } else {
                    PerlRuntime.current().regexLastMatchStart = matcher.start();
                    PerlRuntime.current().regexLastMatchedString = matcher.group(0);
                }
                PerlRuntime.current().regexLastMatchEnd = matcher.end();

                String replacementStr;
                if (replacementIsCode) {
                    // Evaluate the replacement as code
                    // Use callerArgs (the enclosing subroutine's @_) so $_[0] etc. work
                    RuntimeArray args = (regex.callerArgs != null) ? regex.callerArgs : new RuntimeArray();
                    RuntimeList result = RuntimeCode.apply(replacement, args, RuntimeContextType.SCALAR);
                    replacementStr = result.toString();
                } else {
                    // Replace the match with the replacement string
                    replacementStr = replacement.toString();
                }

                if (replacementStr != null) {
                    if (regex.hasBackslashK) {
                        // \K: preserve text before \K position, only replace after it
                        int keepEnd = matcher.end("perlK");
                        resultBuffer.append(inputStr, lastAppendEnd, keepEnd);
                        resultBuffer.append(replacementStr);
                        lastAppendEnd = matcher.end();
                    } else {
                        // Normal replacement: replace the entire match
                        matcher.appendReplacement(resultBuffer, Matcher.quoteReplacement(replacementStr));
                    }
                }

                // If not a global match, break after the first replacement
                if (!regex.regexFlags.isGlobalMatch()) {
                    break;
                }
            }
        } catch (RegexTimeoutException e) {
            WarnDie.warn(new RuntimeScalar(e.getMessage() + "\n"), RuntimeScalarCache.scalarEmptyString);
            found = 0;
        }
        // Append the remaining text after the last match to the result buffer
        if (regex.hasBackslashK) {
            resultBuffer.append(inputStr, lastAppendEnd, inputStr.length());
        } else {
            matcher.appendTail(resultBuffer);
        }

        if (found > 0) {
            String finalResult = resultBuffer.toString();
            boolean wasByteString = (string.type == RuntimeScalarType.BYTE_STRING);

            // Store as last successful pattern for empty pattern reuse
            PerlRuntime.current().regexLastMatchUsedPFlag = regex.hasPreservesMatch;
            PerlRuntime.current().regexLastSuccessfulPattern = regex;

            if (regex.regexFlags.isNonDestructive()) {
                // /r modifier: return the modified string
                RuntimeScalar rv = new RuntimeScalar(finalResult);
                if (wasByteString && !containsWideChars(finalResult)) {
                    rv.type = RuntimeScalarType.BYTE_STRING;
                }
                return rv;
            } else {
                // Save the modified string back to the original scalar
                string.set(finalResult);
                if (wasByteString && !containsWideChars(finalResult)) {
                    string.type = RuntimeScalarType.BYTE_STRING;
                }
                // Return the number of substitutions made
                return RuntimeScalarCache.getScalarInt(found);
            }
        } else {
            if (regex.regexFlags.isNonDestructive()) {
                // /r modifier with no matches: return the original string
                return string;
            } else {
                // Return `undef`
                return scalarUndef;
            }
        }
    }

    /**
     * Method to implement Perl's reset() function.
     * Resets the `matched` flag for each cached regex.
     */
    public static void reset() {
        // Iterate over the regexCache and reset the `matched` flag for each cached regex
        // Synchronized because Collections.synchronizedMap requires manual sync for iteration
        synchronized (regexCache) {
            for (Map.Entry<String, RuntimeRegex> entry : regexCache.entrySet()) {
                RuntimeRegex regex = entry.getValue();
                regex.matched = false; // Reset the matched field
            }
        }
        // Also reset m?PAT? patterns cached per-callsite in regexOptimizedCache
        for (Map.Entry<Integer, RuntimeScalar> entry : PerlRuntime.current().regexOptimizedCache.entrySet()) {
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
        // Reset all match state
        PerlRuntime.current().regexGlobalMatcher = null;
        PerlRuntime.current().regexGlobalMatchString = null;

        // Reset current match information
        PerlRuntime.current().regexLastMatchedString = null;
        PerlRuntime.current().regexLastMatchStart = -1;
        PerlRuntime.current().regexLastMatchEnd = -1;

        // Reset last successful match information
        PerlRuntime.current().regexLastSuccessfulPattern = null;
        PerlRuntime.current().regexLastSuccessfulMatchedString = null;
        PerlRuntime.current().regexLastSuccessfulMatchStart = -1;
        PerlRuntime.current().regexLastSuccessfulMatchEnd = -1;
        PerlRuntime.current().regexLastSuccessfulMatchString = null;
        PerlRuntime.current().regexLastMatchUsedPFlag = false;
        PerlRuntime.current().regexLastCaptureGroups = null;

        // Reset regex cache matched flags
        reset();
    }

    public static String matchString() {
        if (PerlRuntime.current().regexGlobalMatcher != null && PerlRuntime.current().regexLastMatchedString != null) {
            // Current match data available
            return PerlRuntime.current().regexLastMatchedString;
        }
        return null;
    }

    public static String preMatchString() {
        if (PerlRuntime.current().regexGlobalMatcher != null && PerlRuntime.current().regexGlobalMatchString != null && PerlRuntime.current().regexLastMatchStart != -1) {
            // Current match data available
            String result = PerlRuntime.current().regexGlobalMatchString.substring(0, PerlRuntime.current().regexLastMatchStart);
            return result;
        }
        return null;
    }

    public static String postMatchString() {
        if (PerlRuntime.current().regexGlobalMatcher != null && PerlRuntime.current().regexGlobalMatchString != null && PerlRuntime.current().regexLastMatchEnd != -1) {
            // Current match data available
            String result = PerlRuntime.current().regexGlobalMatchString.substring(PerlRuntime.current().regexLastMatchEnd);
            return result;
        }
        return null;
    }

    public static String captureString(int group) {
        if (group <= 0) {
            return PerlRuntime.current().regexLastMatchedString;
        }
        if (PerlRuntime.current().regexLastCaptureGroups == null || group > PerlRuntime.current().regexLastCaptureGroups.length) {
            return null;
        }
        return PerlRuntime.current().regexLastCaptureGroups[group - 1];
    }

    public static String lastCaptureString() {
        if (PerlRuntime.current().regexLastCaptureGroups == null || PerlRuntime.current().regexLastCaptureGroups.length == 0) {
            return null;
        }
        // $+ returns the highest-numbered capture group that actually participated
        // in the match (i.e., is non-null). Non-participating groups in alternations
        // have null values from Java's Matcher.group().
        for (int i = PerlRuntime.current().regexLastCaptureGroups.length - 1; i >= 0; i--) {
            if (PerlRuntime.current().regexLastCaptureGroups[i] != null) {
                return PerlRuntime.current().regexLastCaptureGroups[i];
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
        if (PerlRuntime.current().regexLastMatchWasByteString) {
            scalar.type = RuntimeScalarType.BYTE_STRING;
        }
        return scalar;
    }

    public static RuntimeScalar matcherStart(int group) {
        if (group == 0) {
            return PerlRuntime.current().regexLastMatchStart >= 0 ? getScalarInt(PerlRuntime.current().regexLastMatchStart) : scalarUndef;
        }
        if (PerlRuntime.current().regexGlobalMatcher == null) {
            return scalarUndef;
        }
        try {
            // Adjust group number to skip the internal perlK group
            int javaGroup = adjustGroupForBackslashK(group);
            if (javaGroup < 0 || javaGroup > PerlRuntime.current().regexGlobalMatcher.groupCount()) {
                return scalarUndef;
            }
            int start = PerlRuntime.current().regexGlobalMatcher.start(javaGroup);
            if (start == -1) {
                return scalarUndef;
            }
            return getScalarInt(start);
        } catch (IllegalStateException e) {
            return scalarUndef;
        }
    }

    public static RuntimeScalar matcherEnd(int group) {
        if (group == 0) {
            return PerlRuntime.current().regexLastMatchEnd >= 0 ? getScalarInt(PerlRuntime.current().regexLastMatchEnd) : scalarUndef;
        }
        if (PerlRuntime.current().regexGlobalMatcher == null) {
            return scalarUndef;
        }
        try {
            // Adjust group number to skip the internal perlK group
            int javaGroup = adjustGroupForBackslashK(group);
            if (javaGroup < 0 || javaGroup > PerlRuntime.current().regexGlobalMatcher.groupCount()) {
                return scalarUndef;
            }
            int end = PerlRuntime.current().regexGlobalMatcher.end(javaGroup);
            if (end == -1) {
                return scalarUndef;
            }
            return getScalarInt(end);
        } catch (IllegalStateException e) {
            return scalarUndef;
        }
    }

    public static int matcherSize() {
        if (PerlRuntime.current().regexGlobalMatcher == null) {
            return 0;
        }
        int size = PerlRuntime.current().regexGlobalMatcher.groupCount();
        // Subtract the internal perlK group if \K was used
        if (PerlRuntime.current().regexLastMatchUsedBackslashK) {
            size--;
        }
        // +1 because groupCount is zero-based, and we include the entire match
        return size + 1;
    }

    /**
     * Adjust a Perl capture group number to a Java matcher group number,
     * skipping the internal perlK named group when \K is active.
     */
    private static int adjustGroupForBackslashK(int perlGroup) {
        if (!PerlRuntime.current().regexLastMatchUsedBackslashK || PerlRuntime.current().regexGlobalMatcher == null) {
            return perlGroup;
        }
        int perlKGroup = getPerlKGroup(PerlRuntime.current().regexGlobalMatcher);
        if (perlKGroup < 0) return perlGroup;
        // Perl groups before perlK: same number. At or after: add 1.
        return perlGroup >= perlKGroup ? perlGroup + 1 : perlGroup;
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
     * Resolves a scalar to a RuntimeRegex, handling qr overloading if necessary.
     *
     * @param quotedRegex The scalar that might be a regex or have qr overloading
     * @return The resolved RuntimeRegex
     * @throws PerlCompilerException if qr overload doesn't return proper regex
     */
    private static RuntimeRegex resolveRegex(RuntimeScalar quotedRegex) {
        // Unwrap readonly scalar
        if (quotedRegex.type == RuntimeScalarType.READONLY_SCALAR) quotedRegex = (RuntimeScalar) quotedRegex.value;

        if (quotedRegex.type == RuntimeScalarType.REGEX) {
            return (RuntimeRegex) quotedRegex.value;
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
                        return (RuntimeRegex) overloadedResult.value;
                    }
                    throw new PerlCompilerException("Overloaded qr did not return a REGEXP");
                }

                // Try fallback to string conversion
                RuntimeScalar fallbackResult = overloadCtx.tryOverloadFallback(quotedRegex, "(\"\"");
                if (fallbackResult != null) {
                    return compile(fallbackResult.toString(), "");
                }
            }
        }

        // Default: compile as string
        return compile(quotedRegex.toString(), "");
    }

    @Override
    public String toString() {
        // Construct the Perl-like regex string with flags
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
        return "REF(0x" + this.hashCode() + ")";
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
        Matcher matcher = PerlRuntime.current().regexGlobalMatcher;
        if (matcher == null) {
            return null;
        }

        // Get named groups from the pattern (same as %CAPTURE does)
        Map<String, Integer> namedGroups = matcher.pattern().namedGroups();
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

    /**
     * Get the group number of the internal perlK named capture group.
     * This group is inserted by the preprocessor at the \K position.
     */
    private static int getPerlKGroup(Matcher matcher) {
        Map<String, Integer> namedGroups = matcher.pattern().namedGroups();
        Integer group = namedGroups.get("perlK");
        return group != null ? group : -1;
    }
}

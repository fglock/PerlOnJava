package org.perlonjava.regex;

import org.perlonjava.operators.WarnDie;
import org.perlonjava.runtime.*;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.perlonjava.regex.RegexFlags.fromModifiers;
import static org.perlonjava.regex.RegexFlags.validateModifiers;
import static org.perlonjava.regex.RegexPreprocessor.preProcessRegex;
import static org.perlonjava.regex.RegexQuoteMeta.escapeQ;
import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

/**
 * RuntimeRegex class to implement Perl's qr// operator for regular expression handling,
 * including support for regex modifiers like /i, /g, and /e.
 * This class provides methods to compile, cache, and apply regular expressions
 * with Perl-like syntax and behavior.
 */
public class RuntimeRegex extends RuntimeBase implements RuntimeScalarReference {

    // Constants for regex pattern flags
    private static final int CASE_INSENSITIVE = Pattern.CASE_INSENSITIVE;
    private static final int MULTILINE = Pattern.MULTILINE;
    private static final int DOTALL = Pattern.DOTALL;
    // Maximum size for the regex cache
    private static final int MAX_REGEX_CACHE_SIZE = 1000;
    // Cache to store compiled regex patterns
    private static final Map<String, RuntimeRegex> regexCache = new LinkedHashMap<String, RuntimeRegex>(MAX_REGEX_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RuntimeRegex> eldest) {
            return size() > MAX_REGEX_CACHE_SIZE;
        }
    };
    // Global matcher used for regex operations
    public static Matcher globalMatcher;    // Provides Perl regex variables like %+, %-
    public static String globalMatchString; // Provides Perl regex variables like $&
    // Store match information to avoid IllegalStateException from Matcher
    public static String lastMatchedString = null;
    public static int lastMatchStart = -1;
    public static int lastMatchEnd = -1;
    // Store match information from last successful pattern (persists across failed matches)
    public static String lastSuccessfulMatchedString = null;
    public static int lastSuccessfulMatchStart = -1;
    public static int lastSuccessfulMatchEnd = -1;
    public static String lastSuccessfulMatchString = null;
    // ${^LAST_SUCCESSFUL_PATTERN}
    public static RuntimeRegex lastSuccessfulPattern = null;
    // Indicates if \G assertion is used
    private final boolean useGAssertion = false;
    // Compiled regex pattern
    public Pattern pattern;
    int patternFlags;
    String patternString;
    // Flags for regex behavior
    private RegexFlags regexFlags;
    // Replacement string for substitutions
    private RuntimeScalar replacement = null;
    // Tracks if a match has occurred: this is used as a counter for m?PAT?
    private boolean matched = false;
    private boolean hasCodeBlockCaptures = false;  // True if regex has (?{...}) code blocks

    public RuntimeRegex() {
        this.regexFlags = null;
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
        String cacheKey = patternString + "/" + modifiers;

        // Check if the regex is already cached
        RuntimeRegex regex = regexCache.get(cacheKey);
        if (regex == null) {
            regex = new RuntimeRegex();

            if (patternString != null && patternString.contains("\\Q")) {
                patternString = escapeQ(patternString);
            }

            // Note: flags /e /ee are processed at parse time, in parseRegexReplace()

            validateModifiers(modifiers);

            regex.regexFlags = fromModifiers(modifiers, patternString);
            regex.patternFlags = regex.regexFlags.toPatternFlags();

            String javaPattern = null;
            try {
                javaPattern = preProcessRegex(patternString, regex.regexFlags);

                regex.patternString = patternString;

                // Compile the regex pattern
                regex.pattern = Pattern.compile(javaPattern, regex.patternFlags);

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
            } catch (Exception e) {
                if (GlobalVariable.getGlobalHash("main::ENV").get("JPERL_UNIMPLEMENTED").toString().equals("warn")
                ) {
                    // Warn for unimplemented features and Java regex compilation errors
                    String base = (e instanceof PerlJavaUnimplementedException) ? e.getMessage() : ("Regex compilation failed: " + e.getMessage());
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
                } else {
                    if (e instanceof PerlCompilerException) {
                        throw e;
                    }
                    throw new PerlJavaUnimplementedException("Regex compilation failed: " + e.getMessage());
                }
            }

            // Cache the result if the cache is not full
            if (regexCache.size() < MAX_REGEX_CACHE_SIZE) {
                regexCache.put(cacheKey, regex);
            }
        }
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
            regex.patternString = originalRegex.patternString;
            regex.regexFlags = mergeRegexFlags(originalRegex.regexFlags, modifierStr, originalRegex.patternString);
            regex.patternFlags = regex.regexFlags.toPatternFlags();

            return new RuntimeScalar(regex);
        }

        // Check for qr overloading
        int blessId = RuntimeScalarType.blessedId(patternString);
        if (blessId != 0) {
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
                    regex.patternString = originalRegex.patternString;
                    regex.regexFlags = mergeRegexFlags(originalRegex.regexFlags, modifierStr, originalRegex.patternString);
                    regex.patternFlags = regex.regexFlags.toPatternFlags();

                    return new RuntimeScalar(regex);
                }

                // Try fallback to string conversion
                RuntimeScalar fallbackResult = overloadCtx.tryOverloadFallback(patternString, "(\"\"");
                if (fallbackResult != null) {
                    return new RuntimeScalar(compile(fallbackResult.toString(), modifierStr));
                }
            }
        }

        // Default: compile as string
        return new RuntimeScalar(compile(patternString.toString(), modifierStr));
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
        regex.patternString = resolvedRegex.patternString;
        regex.regexFlags = resolvedRegex.regexFlags;
        regex.patternFlags = resolvedRegex.patternFlags;

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
                regex.patternString = recompiledRegex.patternString;
                regex.regexFlags = recompiledRegex.regexFlags;
                regex.patternFlags = recompiledRegex.patternFlags;
            } else {
                // Just update the flags without recompiling
                regex.regexFlags = newFlags;
                regex.patternFlags = newFlags.toPatternFlags();
            }
        }

        regex.replacement = replacement;
        return new RuntimeScalar(regex);
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
        if (regex.replacement != null) {
            return replaceRegex(quotedRegex, string, ctx);
        }

        // Check if alarm is active - if so, use timeout wrapper to prevent catastrophic backtracking
        if (org.perlonjava.operators.Time.hasActiveAlarm()) {
            int timeoutSeconds = org.perlonjava.operators.Time.getAlarmRemainingSeconds();
            if (timeoutSeconds > 0) {
                return matchRegexWithTimeout(quotedRegex, string, ctx, timeoutSeconds + 1);
            }
        }

        // Fast path: no alarm active, use direct matching
        return matchRegexDirect(quotedRegex, string, ctx);
    }

    /**
     * Direct regex matching without timeout wrapper (fast path).
     */
    private static RuntimeBase matchRegexDirect(RuntimeScalar quotedRegex, RuntimeScalar string, int ctx) {
        RuntimeRegex regex = resolveRegex(quotedRegex);

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
        Matcher matcher = pattern.matcher(inputStr);

        // hexPrinter(inputStr);

        // Use RuntimePosLvalue to get the current position
        RuntimeScalar posScalar = RuntimePosLvalue.pos(string);
        boolean isPosDefined = posScalar.getDefinedBoolean();
        int startPos = isPosDefined ? posScalar.getInt() : 0;
        
        // Check if previous call had zero-length match at this position (for SCALAR context)
        // This prevents infinite loops in: while ($str =~ /pat/g)  
        if (regex.regexFlags.isGlobalMatch() && ctx == RuntimeContextType.SCALAR) {
            String patternKey = regex.patternString;
            if (RuntimePosLvalue.hadZeroLengthMatchAt(string, startPos, patternKey)) {
                // Previous match was zero-length at this position - fail to break loop
                posScalar.set(scalarUndef);
                return RuntimeScalarCache.scalarFalse;
            }
        }

        // Start matching from the current position if defined
        if (isPosDefined) {
            matcher.region(startPos, inputStr.length());
        }

        boolean found = false;
        RuntimeList result = new RuntimeList();
        List<RuntimeBase> matchedGroups = result.elements;

        int capture = 1;
        int previousPos = startPos; // Track the previous position  
        int previousMatchEnd = -1;  // Track end of previous match
        // System.err.println("DEBUG: Resetting globalMatcher to null at start of matchRegex");
        globalMatcher = null;
        // Reset stored match information (but preserve last successful match info)
        lastMatchedString = null;
        lastMatchStart = -1;
        lastMatchEnd = -1;

        while (matcher.find()) {
            // If \G is used, ensure the match starts at the expected position
            if (regex.useGAssertion && isPosDefined && matcher.start() != startPos) {
                break;
            }

            found = true;
            int captureCount = matcher.groupCount();

            // Always initialize $1, $2, @+, @-, $`, $&, $' for every successful match
            globalMatcher = matcher;
            globalMatchString = inputStr;
            // Store match information to avoid IllegalStateException later
            lastMatchedString = matcher.group(0);
            lastMatchStart = matcher.start();
            lastMatchEnd = matcher.end();
            // System.err.println("DEBUG: Set globalMatcher for match at position " + matcher.start() + "-" + matcher.end());
            // System.err.println("DEBUG: Stored match info - matched: '" + lastMatchedString + "', start: " + lastMatchStart + ", end: " + lastMatchEnd);

            if (regex.regexFlags.isGlobalMatch() && captureCount < 1 && ctx == RuntimeContextType.LIST) {
                // Global match and no captures, in list context return the matched string
                String matchedStr = matcher.group(0);
                matchedGroups.add(new RuntimeScalar(matchedStr));
            } else {
                // save captures in return list if needed
                if (ctx == RuntimeContextType.LIST) {
                    for (int i = 1; i <= captureCount; i++) {
                        String matchedStr = matcher.group(i);
                        if (matchedStr != null) {
                            matchedGroups.add(new RuntimeScalar(matchedStr));
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
                    posScalar.set(matchEnd);
                    // Record zero-length match for cross-call tracking
                    if (matchEnd == matchStart) {
                        RuntimePosLvalue.recordZeroLengthMatch(string, matchEnd, regex.patternString);
                    } else {
                        RuntimePosLvalue.recordNonZeroLengthMatch(string);
                    }
                    break; // Break out of the loop after the first match in SCALAR context
                } else {
                    startPos = matchEnd;
                    posScalar.set(startPos);
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

        // Reset pos() on failed match with /g, unless /c is set
        if (!found && regex.regexFlags.isGlobalMatch() && !regex.regexFlags.keepCurrentPosition()) {
            posScalar.set(scalarUndef);
        }

        // Reset special variables on failed match (Perl behavior)
        if (!found) {
            lastSuccessfulPattern = null;
            lastSuccessfulMatchedString = null;
            lastSuccessfulMatchStart = -1;
            lastSuccessfulMatchEnd = -1;
            lastSuccessfulMatchString = null;
        }

        if (found) {
            regex.matched = true; // Counter for m?PAT?
            lastSuccessfulPattern = regex;
            // Store last successful match information (persists across failed matches)
            lastSuccessfulMatchedString = lastMatchedString;
            lastSuccessfulMatchStart = lastMatchStart;
            lastSuccessfulMatchEnd = lastMatchEnd;
            lastSuccessfulMatchString = globalMatchString;

            // Update $^R if this regex has code block captures (performance optimization)
            if (regex.hasCodeBlockCaptures) {
                RuntimeScalar codeBlockResult = regex.getLastCodeBlockResult();
                // Set $^R to the code block result (or undef if no code blocks matched)
                GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("R"))
                        .set(codeBlockResult != null ? codeBlockResult : RuntimeScalarCache.scalarUndef);
            }

            // Reset pos() after global match in LIST context (matches Perl behavior)
            if (regex.regexFlags.isGlobalMatch() && ctx == RuntimeContextType.LIST) {
                posScalar.set(scalarUndef);
            }
            // System.err.println("DEBUG: Match completed, globalMatcher is " + (globalMatcher == null ? "null" : "set"));
        } else {
            // System.err.println("DEBUG: No match found, globalMatcher is " + (globalMatcher == null ? "null" : "set"));
        }

        if (ctx == RuntimeContextType.LIST) {
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
     * @param quotedRegex The regex pattern object
     * @param string      The string to match against
     * @param ctx         The context (LIST, SCALAR, VOID)
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
            org.perlonjava.runtime.PerlSignalQueue.checkPendingSignals();
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
            org.perlonjava.runtime.PerlSignalQueue.checkPendingSignals();
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

        // Handle empty pattern - reuse last successful pattern
        if (regex.patternString == null || regex.patternString.isEmpty()) {
            if (lastSuccessfulPattern != null) {
                // Use the pattern from last successful match
                // But keep the current replacement and flags (especially /g and /i)
                Pattern pattern = lastSuccessfulPattern.pattern;
                // Re-apply current flags if they differ
                if (originalFlags != null && !originalFlags.equals(lastSuccessfulPattern.regexFlags)) {
                    // Need to recompile with current flags
                    int newFlags = originalFlags.toPatternFlags();
                    pattern = Pattern.compile(lastSuccessfulPattern.patternString, newFlags);
                }
                // Create a temporary regex with the right pattern and current flags
                RuntimeRegex tempRegex = new RuntimeRegex();
                tempRegex.pattern = pattern;
                tempRegex.patternString = lastSuccessfulPattern.patternString;
                tempRegex.regexFlags = originalFlags;
                tempRegex.replacement = replacement;
                regex = tempRegex;
            } else {
                throw new PerlCompilerException("No previous regular expression");
            }
        }

        Pattern pattern = regex.pattern;
        Matcher matcher = pattern.matcher(inputStr);

        // The result string after substitutions
        StringBuilder resultBuffer = new StringBuilder();
        int found = 0;

        // Determine if the replacement is a code that needs to be evaluated
        boolean replacementIsCode = (replacement.type == RuntimeScalarType.CODE);

        // Don't reset globalMatcher here - only reset it if we actually find a match
        // This preserves capture variables from previous matches when substitution doesn't match

        // Perform the substitution
        while (matcher.find()) {
            found++;

            // Initialize $1, $2, @+, @- only when we have a match
            globalMatcher = matcher;
            globalMatchString = inputStr;
            // Store match information
            lastMatchedString = matcher.group(0);
            lastMatchStart = matcher.start();
            lastMatchEnd = matcher.end();

            String replacementStr;
            if (replacementIsCode) {
                // Evaluate the replacement as code
                RuntimeList result = RuntimeCode.apply(replacement, new RuntimeArray(), RuntimeContextType.SCALAR);
                replacementStr = result.toString();
            } else {
                // Replace the match with the replacement string
                replacementStr = replacement.toString();
            }

            if (replacementStr != null) {
                // In Java regex replacement strings:
                //
                // - $1, $2, etc. refer to capture groups from the pattern
                // - $0 refers to the entire match
                // - \ is used for escaping
                //
                // When you pass $x as the replacement string, Java interprets it as trying to reference capture group "x", which doesn't exist (capture groups are numbered, not named with letters in basic Java regex).

                // replacementStr = replacementStr.replaceAll("\\\\", "\\\\\\\\");

                // Append the text before the match and the replacement to the result buffer
                // matcher.appendReplacement(resultBuffer, replacementStr);
                matcher.appendReplacement(resultBuffer, Matcher.quoteReplacement(replacementStr));
            }

            // If not a global match, break after the first replacement
            if (!regex.regexFlags.isGlobalMatch()) {
                break;
            }
        }
        // Append the remaining text after the last match to the result buffer
        matcher.appendTail(resultBuffer);

        if (found > 0) {
            String finalResult = resultBuffer.toString();

            // Store as last successful pattern for empty pattern reuse
            lastSuccessfulPattern = regex;

            if (regex.regexFlags.isNonDestructive()) {
                // /r modifier: return the modified string
                return new RuntimeScalar(finalResult);
            } else {
                // Save the modified string back to the original scalar
                string.set(finalResult);
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
        for (Map.Entry<String, RuntimeRegex> entry : regexCache.entrySet()) {
            RuntimeRegex regex = entry.getValue();
            regex.matched = false; // Reset the matched field
        }
    }

    /**
     * Initialize/reset all regex state including special variables.
     * This should be called at the start of each script execution to ensure clean state.
     */
    public static void initialize() {
        // Reset all match state
        globalMatcher = null;
        globalMatchString = null;

        // Reset current match information
        lastMatchedString = null;
        lastMatchStart = -1;
        lastMatchEnd = -1;

        // Reset last successful match information
        lastSuccessfulPattern = null;
        lastSuccessfulMatchedString = null;
        lastSuccessfulMatchStart = -1;
        lastSuccessfulMatchEnd = -1;
        lastSuccessfulMatchString = null;

        // Reset regex cache matched flags
        reset();
    }

    public static String matchString() {
        if (globalMatcher != null && lastMatchedString != null) {
            // Current match data available
            return lastMatchedString;
        } else if (lastSuccessfulMatchedString != null) {
            // Fall back to last successful match
            return lastSuccessfulMatchedString;
        }
        return null;
    }

    public static String preMatchString() {
        if (globalMatcher != null && globalMatchString != null && lastMatchStart != -1) {
            // Current match data available
            String result = globalMatchString.substring(0, lastMatchStart);
            return result;
        } else if (lastSuccessfulMatchString != null && lastSuccessfulMatchStart != -1) {
            // Fall back to last successful match
            String result = lastSuccessfulMatchString.substring(0, lastSuccessfulMatchStart);
            return result;
        }
        return null;
    }

    public static String postMatchString() {
        if (globalMatcher != null && globalMatchString != null && lastMatchEnd != -1) {
            // Current match data available
            String result = globalMatchString.substring(lastMatchEnd);
            return result;
        } else if (lastSuccessfulMatchString != null && lastSuccessfulMatchEnd != -1) {
            // Fall back to last successful match
            String result = lastSuccessfulMatchString.substring(lastSuccessfulMatchEnd);
            return result;
        }
        return null;
    }

    public static String captureString(int group) {
        if (globalMatcher == null || group < 0 || group > globalMatcher.groupCount()) {
            return null;
        }
        return globalMatcher.group(group);
    }

    public static String lastCaptureString() {
        if (globalMatcher == null) {
            return null;
        }
        int lastGroup = globalMatcher.groupCount();
        return globalMatcher.group(lastGroup);
    }

    public static RuntimeScalar matcherStart(int group) {
        if (globalMatcher == null) {
            return scalarUndef;
        }
        if (group < 0 || group > globalMatcher.groupCount()) {
            return scalarUndef;
        }
        int start = globalMatcher.start(group);
        // If the group didn't participate in the match, start() returns -1
        // Perl returns undef in this case
        if (start == -1) {
            return scalarUndef;
        }
        return getScalarInt(start);
    }

    public static RuntimeScalar matcherEnd(int group) {
        if (globalMatcher == null) {
            return scalarUndef;
        }
        if (group < 0 || group > globalMatcher.groupCount()) {
            return scalarUndef;
        }
        int end = globalMatcher.end(group);
        // If the group didn't participate in the match, end() returns -1
        // Perl returns undef in this case
        if (end == -1) {
            return scalarUndef;
        }
        return getScalarInt(end);
    }

    public static int matcherSize() {
        if (globalMatcher == null) {
            return 0;
        }
        // +1 because groupCount is zero-based, and we include the entire match
        return globalMatcher.groupCount() + 1;
    }

    /**
     * Resolves a scalar to a RuntimeRegex, handling qr overloading if necessary.
     *
     * @param quotedRegex The scalar that might be a regex or have qr overloading
     * @return The resolved RuntimeRegex
     * @throws PerlCompilerException if qr overload doesn't return proper regex
     */
    private static RuntimeRegex resolveRegex(RuntimeScalar quotedRegex) {
        if (quotedRegex.type == RuntimeScalarType.REGEX) {
            return (RuntimeRegex) quotedRegex.value;
        }

        // Check if the object has qr overloading
        int blessId = RuntimeScalarType.blessedId(quotedRegex);
        if (blessId != 0) {
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
        Matcher matcher = globalMatcher;
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
}

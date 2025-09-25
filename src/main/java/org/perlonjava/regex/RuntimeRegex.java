package org.perlonjava.regex;

import org.perlonjava.operators.WarnDie;
import org.perlonjava.runtime.*;

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
public class RuntimeRegex implements RuntimeScalarReference {

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
            if (patternString.contains("\\Q")) {
                patternString = escapeQ(patternString);
            }

            // Note: flags /e /ee are processed at parse time, in parseRegexReplace()

            validateModifiers(modifiers);

            regex.regexFlags = fromModifiers(modifiers, patternString);
            regex.patternFlags = regex.regexFlags.toPatternFlags();

            try {
                String javaPattern = preProcessRegex(patternString, regex.regexFlags);

                regex.patternString = patternString;

                // Compile the regex pattern
                regex.pattern = Pattern.compile(javaPattern, regex.patternFlags);
            } catch (Exception e) {
                if (GlobalVariable.getGlobalHash("main::ENV").get("JPERL_UNIMPLEMENTED").toString().equals("warn")
                ) {
//                    // Always throw known invalid Perl syntax errors (PerlCompilerException from targeted validation)
//                    if (e instanceof PerlCompilerException && !(e instanceof PerlJavaUnimplementedException)) {
//                        throw e; // Always throw for known invalid patterns
//                    }

                    // Warn for unimplemented features and Java regex compilation errors
                    String errorMessage = (e instanceof PerlJavaUnimplementedException) ? e.getMessage() : "Regex compilation failed: " + e.getMessage();
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

        // Start matching from the current position if defined
        if (isPosDefined) {
            matcher.region(startPos, inputStr.length());
        }

        boolean found = false;
        RuntimeList result = new RuntimeList();
        List<RuntimeBase> matchedGroups = result.elements;

        int capture = 1;
        int previousPos = startPos; // Track the previous position
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
                if (ctx == RuntimeContextType.SCALAR || ctx == RuntimeContextType.VOID) {
                    // Set pos to the end of the current match to prepare for the next search
                    posScalar.set(matcher.end());
                    break; // Break out of the loop after the first match in SCALAR context
                } else {
                    startPos = matcher.end();
                    posScalar.set(startPos);
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

        Pattern pattern = regex.pattern;
        RuntimeScalar replacement = regex.replacement;
        Matcher matcher = pattern.matcher(inputStr);

        // The result string after substitutions
        StringBuilder resultBuffer = new StringBuilder();
        int found = 0;

        // Determine if the replacement is a code that needs to be evaluated
        boolean replacementIsCode = (replacement.type == RuntimeScalarType.CODE);
        globalMatcher = null;
        // Reset stored match information
        lastMatchedString = null;
        lastMatchStart = -1;
        lastMatchEnd = -1;

        // Perform the substitution
        while (matcher.find()) {
            found++;

            // Initialize $1, $2, @+, @-
            globalMatcher = matcher;
            globalMatchString = inputStr;

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
        return getScalarInt(globalMatcher.start(group));
    }

    public static RuntimeScalar matcherEnd(int group) {
        if (globalMatcher == null) {
            return scalarUndef;
        }
        if (group < 0 || group > globalMatcher.groupCount()) {
            return scalarUndef;
        }
        return getScalarInt(globalMatcher.end(group));
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

}

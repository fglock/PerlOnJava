package org.perlonjava.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.COMMENTS;

/**
 * RuntimeRegex class to implement Perl's qr// operator for regular expression handling,
 * including support for regex modifiers like /i, /g, and /e.
 * This class provides methods to compile, cache, and apply regular expressions
 * with Perl-like syntax and behavior.
 */
public class RuntimeRegex implements RuntimeScalarReference {

    private static final int CASE_INSENSITIVE = Pattern.CASE_INSENSITIVE;
    private static final int MULTILINE = Pattern.MULTILINE;
    private static final int DOTALL = Pattern.DOTALL;

    private static final int MAX_REGEX_CACHE_SIZE = 1000;
    private static final Map<String, RuntimeRegex> regexCache = new LinkedHashMap<String, RuntimeRegex>(MAX_REGEX_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RuntimeRegex> eldest) {
            return size() > MAX_REGEX_CACHE_SIZE;
        }
    };
    public static Matcher globalMatcher;

    public Pattern pattern;  // Compiled regex pattern
    boolean isGlobalMatch;   // Flag for global matching
    boolean isNonDestructive; // Flag for non-destructive substitution
    boolean isMatchExactlyOnce; // Flag for matching exactly once
    private RuntimeScalar replacement = null;  // Replacement string for substitutions
    private boolean matched = false; // Tracks if a match has occurred: this is used as a counter for m?PAT?
    private boolean useGAssertion = false;  // Indicates if \G assertion is used

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

        RuntimeRegex regex = regexCache.get(cacheKey);
        if (regex == null) {
            regex = new RuntimeRegex();
            try {
                int flags = regex.convertModifiers(modifiers);
                regex.isGlobalMatch = modifiers.contains("g");
                regex.isNonDestructive = modifiers.contains("r");
                regex.isMatchExactlyOnce = modifiers.contains("?");

                // Check for \G and set useGAssertion
                regex.useGAssertion = patternString.contains("\\G");

                // Remove \G from the pattern string for Java compilation
                String javaPatternString = patternString.replace("\\G", "");

                regex.pattern = Pattern.compile(javaPatternString, flags);
            } catch (Exception e) {
                throw new IllegalStateException("Regex compilation failed: " + e.getMessage());
            }

            // Cache the result if the cache is not full
            if (regexCache.size() < MAX_REGEX_CACHE_SIZE) {
                regexCache.put(cacheKey, regex);
            }
        }
        return regex;
    }

    /**
     * Creates a Perl "qr" object from a regex pattern string with optional modifiers.
     * `my $v = qr/abc/i;`
     *
     * @param patternString The regex pattern string with optional modifiers.
     * @param modifiers     Modifiers for the regex pattern (e.g., "i", "g").
     * @return A RuntimeScalar.
     */
    public static RuntimeScalar getQuotedRegex(RuntimeScalar patternString, RuntimeScalar modifiers) {
        return new RuntimeScalar(compile(patternString.toString(), modifiers.toString()));
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
        RuntimeRegex regex = compile(patternString.toString(), modifiers.toString());
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
    public static RuntimeDataProvider matchRegex(RuntimeScalar quotedRegex, RuntimeScalar string, int ctx) {
        RuntimeRegex regex = (RuntimeRegex) quotedRegex.value;
        if (regex.replacement != null) {
            return replaceRegex(quotedRegex, string, ctx);
        }

        if (regex.isMatchExactlyOnce && regex.matched) {
            // m?PAT? already matched once; now return false
            if (ctx == RuntimeContextType.LIST) {
                return new RuntimeList();
            } else if (ctx == RuntimeContextType.SCALAR) {
                return RuntimeScalarCache.scalarFalse;
            } else {
                return RuntimeScalarCache.scalarUndef;
            }
        }

        Pattern pattern = regex.pattern;
        String inputStr = string.toString();
        Matcher matcher = pattern.matcher(inputStr);

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
        List<RuntimeBaseEntity> matchedGroups = result.elements;

        int capture = 1;
        int previousPos = startPos; // Track the previous position
        globalMatcher = null;

        while (matcher.find()) {
            // If \G is used, ensure the match starts at the expected position
            if (regex.useGAssertion && isPosDefined && matcher.start() != startPos) {
                break;
            }

            found = true;
            int captureCount = matcher.groupCount();
            if (regex.isGlobalMatch && captureCount < 1 && ctx == RuntimeContextType.LIST) {
                // Global match and no captures, in list context return the matched string
                String matchedStr = matcher.group(0);
                matchedGroups.add(new RuntimeScalar(matchedStr));
            } else {
                // Initialize $1, $2 and save captures in return list if needed
                // Also initialize @+, @-
                globalMatcher = matcher;

                // Store start and end positions in @- and @+
                RuntimeArray atMinus = GlobalContext.getGlobalArray("main::-");
                RuntimeArray atPlus = GlobalContext.getGlobalArray("main::+");
                atMinus.elements.clear(); // Clear previous matches
                atPlus.elements.clear();  // Clear previous matches
                atMinus.set(0, new RuntimeScalar(matcher.start(0)));
                atPlus.set(0, new RuntimeScalar(matcher.end(0)));

                for (int i = 1; i <= captureCount; i++) {
                    String matchedStr = matcher.group(i);
                    if (matchedStr != null) {
                        if (i > 10) {
                            GlobalContext.setGlobalVariable("main::" + capture++, matchedStr);
                        }

                        // Store start and end positions in @- and @+
                        atMinus.set(i, new RuntimeScalar(matcher.start(i)));
                        atPlus.set(i, new RuntimeScalar(matcher.end(i)));

                        if (ctx == RuntimeContextType.LIST) {
                            matchedGroups.add(new RuntimeScalar(matchedStr));
                        }
                    }
                }
            }

            if (regex.isGlobalMatch) {
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

            if (!regex.isGlobalMatch) {
                break;
            }
        }

        // Undefine capture variables beyond the last match
        if (capture > 10) {
            GlobalContext.getGlobalVariable("main::" + capture++).set(RuntimeScalarCache.scalarUndef);
        }

        if (found) {
            regex.matched = true; // Counter for m?PAT?
        }

        if (ctx == RuntimeContextType.LIST) {
            return result;
        } else if (ctx == RuntimeContextType.SCALAR) {
            return RuntimeScalarCache.getScalarBoolean(found);
        } else {
            return RuntimeScalarCache.scalarUndef;
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
    public static RuntimeBaseEntity replaceRegex(RuntimeScalar quotedRegex, RuntimeScalar string, int ctx) {
        // Convert the input string to a Java string
        String inputStr = string.toString();

        // Extract the regex pattern from the quotedRegex object
        RuntimeRegex regex = (RuntimeRegex) quotedRegex.value;
        Pattern pattern = regex.pattern;
        RuntimeScalar replacement = regex.replacement;
        Matcher matcher = pattern.matcher(inputStr);

        // The result string after substitutions
        StringBuilder resultBuffer = new StringBuilder();
        int found = 0;

        // Determine if the replacement is a code that needs to be evaluated
        boolean replacementIsCode = (replacement.type == RuntimeScalarType.CODE);
        globalMatcher = null;

        // Perform the substitution
        while (matcher.find()) {
            found++;

            // Initialize $1, $2 if needed
            int captureCount = matcher.groupCount();
            if (captureCount > 0) {
                globalMatcher = matcher;

                int capture = 1;
                for (int i = 1; i <= captureCount; i++) {
                    String matchedStr = matcher.group(i);
                    if (matchedStr != null && i > 10) {
                        GlobalContext.setGlobalVariable("main::" + capture++, matchedStr);
                    }
                }
                if (capture > 10) {
                    GlobalContext.getGlobalVariable("main::" + capture++).set(RuntimeScalarCache.scalarUndef);
                }
            }

            String replacementStr;
            if (replacementIsCode) {
                // Evaluate the replacement as code
                replacementStr = replacement.apply(new RuntimeArray(), RuntimeContextType.SCALAR).toString();
            } else {
                // Replace the match with the replacement string
                replacementStr = replacement.toString();
            }

            if (replacementStr != null) {
                // Append the text before the match and the replacement to the result buffer
                matcher.appendReplacement(resultBuffer, replacementStr);
            }

            // If not a global match, break after the first replacement
            if (!regex.isGlobalMatch) {
                break;
            }
        }
        // Append the remaining text after the last match to the result buffer
        matcher.appendTail(resultBuffer);

        if (found > 0) {
            if (regex.isNonDestructive) {
                return new RuntimeScalar(resultBuffer.toString());
            }
            // Save the modified string back to the original scalar
            string.set(resultBuffer.toString());
            // Return the number of substitutions made
            return RuntimeScalarCache.getScalarInt(found);
        } else {
            if (regex.isNonDestructive) {
                return string;
            }
            // Return `undef`
            return RuntimeScalarCache.scalarUndef;
        }
    }

    // Method to implement Perl's reset() function
    public static void reset() {
        // Iterate over the regexCache and reset the `matched` flag for each cached regex
        for (Map.Entry<String, RuntimeRegex> entry : regexCache.entrySet()) {
            RuntimeRegex regex = entry.getValue();
            regex.matched = false; // Reset the matched field
        }
    }

    @Override
    public String toString() {
        // TODO add (?idmsux-idmsux:X) around the regex
        return pattern.toString();
    }

    public String toStringRef() {
        return "REF(" + this.hashCode() + ")";
    }

    public int getIntRef() {
        return this.hashCode();
    }

    public double getDoubleRef() {
        return this.hashCode();
    }

    public boolean getBooleanRef() {
        return true;
    }

    /**
     * Converts modifier string to Pattern flags.
     *
     * @param modifiers The string of modifiers (e.g., "i", "g").
     * @return The Pattern flags corresponding to the modifiers.
     */
    private int convertModifiers(String modifiers) {
        int flags = 0;
        if (modifiers.contains("i")) {
            flags |= CASE_INSENSITIVE;
        }
        if (modifiers.contains("m")) {
            flags |= MULTILINE;
        }
        if (modifiers.contains("s")) {
            flags |= DOTALL;
        }
        if (modifiers.contains("x")) {
            flags |= COMMENTS;
        }
        // /g (global) is not an actual flag for Pattern, it's used for matching multiple occurrences.
        // /r (non-destructive) is also not an actual flag for Pattern, it returns the replacement.
        return flags;
    }
}

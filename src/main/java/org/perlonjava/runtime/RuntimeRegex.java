package org.perlonjava.runtime;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RuntimeRegex class to implement Perl's qr// operator for regular expression handling,
 * including support for regex modifiers like /i, /g, and /e.
 */
public class RuntimeRegex implements RuntimeScalarReference {

    private static final int CASE_INSENSITIVE = Pattern.CASE_INSENSITIVE;
    private static final int MULTILINE = Pattern.MULTILINE;
    private static final int DOTALL = Pattern.DOTALL;
    boolean isGlobalMatch;
    boolean isNonDestructive;
    public Pattern pattern;  // first part of `m//` and `s///`
    private RuntimeScalar replacement = null;  // second part of `s///`

    /**
     * Creates a RuntimeRegex object from a regex pattern string with optional modifiers.
     *
     * @param patternString The regex pattern string with optional modifiers.
     * @param modifiers     Modifiers for the regex pattern (e.g., "i", "g").
     * @return A RuntimeRegex object.
     */
    public static RuntimeRegex compile(String patternString, String modifiers) {
        RuntimeRegex regex = new RuntimeRegex();
        try {
            int flags = regex.convertModifiers(modifiers);
            regex.isGlobalMatch = modifiers.contains("g");
            regex.isNonDestructive = modifiers.contains("r");
            regex.pattern = Pattern.compile(patternString, flags);
        } catch (Exception e) {
            throw new IllegalStateException("Regex compilation failed: " + e.getMessage());
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

    // Internal variant of qr// that includes a `replacement`
    // This is the internal representation of the `s///` operation
    public static RuntimeScalar getReplacementRegex(RuntimeScalar patternString, RuntimeScalar replacement, RuntimeScalar modifiers) {
        RuntimeRegex regex = new RuntimeRegex();
        regex = compile(patternString.toString(), modifiers.toString());
        regex.replacement = replacement;
        return new RuntimeScalar(regex);
    }

    /**
     * Applies a Perl "qr" object on a string; returns true/false or a list,
     * and produces side-effects
     * `my $v =~ /$qr/;`
     *
     * @param quotedRegex The regex pattern object, created by getQuotedRegex()
     * @param string      The string to be matched.
     * @param ctx         The context LIST, SCALAR, VOID
     * @return A RuntimeScalar or RuntimeList
     */
    public static RuntimeDataProvider matchRegex(RuntimeScalar quotedRegex, RuntimeScalar string, int ctx) {
        RuntimeRegex regex = (RuntimeRegex) quotedRegex.value;
        if (regex.replacement != null) {
            return replaceRegex(quotedRegex, string, ctx);
        }

        Pattern pattern = regex.pattern;

        String inputStr = string.toString();
        Matcher matcher = pattern.matcher(inputStr);

        boolean found = false;
        RuntimeList result = new RuntimeList();
        List<RuntimeBaseEntity> matchedGroups = result.elements;

        int capture = 1;
        while (matcher.find()) {
            found = true;
            int captureCount = matcher.groupCount();
            if (regex.isGlobalMatch && captureCount < 1 && ctx == RuntimeContextType.LIST) {
                // global match and no captures, in list context return the matched string
                // capture++;
                String matchedStr = matcher.group(0);
                matchedGroups.add(new RuntimeScalar(matchedStr));
            } else {
                // initialize $1, $2 are save captures in return list if needed
                for (int i = 1; i <= captureCount; i++) {
                    String matchedStr = matcher.group(i);
                    if (matchedStr != null) {
                        // System.out.println("Set capture $" + capture + " to <" + matchedStr + ">");
                        GlobalContext.setGlobalVariable("main::" + capture++, matchedStr);
                        if (ctx == RuntimeContextType.LIST) {
                            matchedGroups.add(new RuntimeScalar(matchedStr));
                        }
                    }
                }
            }
            if (!regex.isGlobalMatch) {
                break;
            }
        }
        // System.out.println("Undefine capture $" + capture);
        GlobalContext.getGlobalVariable("main::" + capture++).set(new RuntimeScalar());

        if (ctx == RuntimeContextType.LIST) {
            return result;
        } else if (ctx == RuntimeContextType.SCALAR) {
            return new RuntimeScalar(found ? 1 : 0);
        } else {
            return new RuntimeScalar();
        }
    }

    /**
     * Applies a Perl "s///" substitution on a string.
     * `my $v =~ s/$pattern/$replacement/;`
     *
     * @param quotedRegex The regex pattern object, created by getReplacementRegex()
     * @param string      The string to be modified.
     * @param ctx         The context LIST, SCALAR, VOID
     * @return A RuntimeScalar or RuntimeList
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

        // Perform the substitution
        while (matcher.find()) {
            found++;

            // Initialize $1, $2 if needed
            int captureCount = matcher.groupCount();
            if (captureCount > 0) {
                int capture = 1;
                for (int i = 1; i <= captureCount; i++) {
                    String matchedStr = matcher.group(i);
                    if (matchedStr != null) {
                        // System.out.println("Set capture $" + capture + " to <" + matchedStr + ">");
                        GlobalContext.setGlobalVariable("main::" + capture++, matchedStr);
                    }
                }
                // System.out.println("Undefine capture $" + capture);
                GlobalContext.getGlobalVariable("main::" + capture++).set(new RuntimeScalar());
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
            return new RuntimeScalar(found);
        } else {
            if (regex.isNonDestructive) {
                return string;
            }
            // Return `undef`
            return new RuntimeScalar();
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
        // /g (global) is not an actual flag for Pattern, it's used for matching multiple occurrences.
        // /r (non-destructive) is also not an actual flag for Pattern, it returns the replacement.
        return flags;
    }

    /**
     * Matches the given input against the compiled regex pattern.
     *
     * @param input The input string to match.
     * @return A Matcher object containing the result of the match operation.
     */
    public Matcher match(String input) {
        if (pattern == null) {
            throw new IllegalStateException("Pattern not compiled");
        }
        return pattern.matcher(input);
    }

    /**
     * Finds all matches in the given input.
     *
     * @param input The input string to search.
     * @return A String array containing all matches.
     */
    public String[] findAll(String input) {
        Matcher matcher = match(input);
        StringBuilder matches = new StringBuilder();
        while (matcher.find()) {
            matches.append(matcher.group()).append("\n");
        }
        return matches.toString().split("\n");
    }

    // Getters and setters for the pattern can be added as needed

    /**
     * Gets the compiled pattern.
     *
     * @return The compiled Pattern.
     */
    public Pattern getPattern() {
        return pattern;
    }

    /**
     * Sets a new pattern.
     *
     * @param pattern The new Pattern to set.
     */
    public void setPattern(Pattern pattern) {
        this.pattern = pattern;
    }
}


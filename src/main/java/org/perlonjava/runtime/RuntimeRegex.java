package org.perlonjava.runtime;

import java.util.ArrayList;
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
    private Pattern pattern;
    boolean isGlobalMatch;

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
    public static RuntimeBaseEntity matchRegex(RuntimeScalar quotedRegex, RuntimeScalar string, int ctx) {
        String inputStr = string.toString();
        RuntimeRegex regex = (RuntimeRegex) quotedRegex.value;
        Pattern pattern = regex.pattern;
        Matcher matcher = pattern.matcher(inputStr);

        boolean found = false;
        RuntimeList result = new RuntimeList();
        List<RuntimeBaseEntity> matchedGroups = result.elements;

        int capture = 0;
        while (matcher.find()) {
            found = true;
            for (int i = 1; i <= matcher.groupCount(); i++) {
                capture++;
                GlobalContext.setGlobalVariable("main::" + capture, matcher.group(i));
                if (ctx == RuntimeContextType.LIST) {
                    matchedGroups.add(new RuntimeScalar(matcher.group(i)));
                }
            }
            if (!regex.isGlobalMatch) {
                break;
            }
        }

        if (!found) {
            GlobalContext.setGlobalVariable("main::1", "");
        }

        if (ctx == RuntimeContextType.LIST) {
            return result;
        } else if (ctx == RuntimeContextType.SCALAR) {
            return new RuntimeScalar(found ? 1 : 0);
        } else {
            return new RuntimeScalar();
        }
    }

    @Override
    public String toString() {
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


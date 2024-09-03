package org.perlonjava.runtime;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.GlobalContext.getGlobalVariable;

/**
 * RuntimeRegex class to implement Perl's qr// operator for regular expression handling,
 * including support for regex modifiers like /i, /g, and /e.
 */
public class RuntimeRegex implements RuntimeScalarReference {

    private static final int CASE_INSENSITIVE = Pattern.CASE_INSENSITIVE;
    private static final int MULTILINE = Pattern.MULTILINE;
    private static final int DOTALL = Pattern.DOTALL;
    private Pattern pattern;

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
            regex.pattern = Pattern.compile(patternString, flags);
        } catch (Exception e) {
            getGlobalVariable("main::@").set("Regex compilation failed: " + e.getMessage());
            regex = null;
        }
        return regex;
    }

    /**
     * Creates a Perl "qr" object from a regex pattern string with optional modifiers.
     *
     * @param patternString The regex pattern string with optional modifiers.
     * @param modifiers     Modifiers for the regex pattern (e.g., "i", "g").
     * @return A RuntimeScalar.
     */
    public static RuntimeScalar getQuotedRegex(RuntimeScalar patternString, RuntimeScalar modifiers) {
        return new RuntimeScalar(compile(patternString.toString(), modifiers.toString()));
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


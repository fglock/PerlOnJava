package org.perlonjava.operators;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class ScalarGlobOperatorHelper {
    /**
     * Checks if a pattern contains glob wildcard characters.
     *
     * @param pattern the pattern to check
     * @return true if pattern contains unescaped glob characters
     */
    static boolean containsGlobChars(String pattern) {
        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '*' || c == '?' || c == '[' || c == '{') {
                return true;
            }
        }
        return false;
    }

    /**
     * Converts a glob pattern to a regular expression.
     *
     * @param scalarGlobOperator
     * @param glob the glob pattern
     * @return compiled regex pattern, or null on error
     */
    static Pattern globToRegex(ScalarGlobOperator scalarGlobOperator, String glob) {
        StringBuilder regex = new StringBuilder("^");
        boolean escaped = false;
        boolean inCharClass = false;
        int charClassStart = -1;

        try {
            for (int i = 0; i < glob.length(); i++) {
                char c = glob.charAt(i);

                if (escaped) {
                    if (inCharClass) {
                        // Inside character class, handle escaped characters
                        // In glob character class: \[ means literal [, \{ means literal {
                        // These need to be escaped in Java regex character class too
                        if (c == '[' || c == ']' || c == '\\') {
                            regex.append('\\');
                        }
                        regex.append(c);
                    } else {
                        // Outside character class, escaped char is literal
                        if (".$^*+?{}()[]|\\".indexOf(c) >= 0) {
                            regex.append('\\');
                        }
                        regex.append(c);
                    }
                    escaped = false;
                    continue;
                }

                if (c == '\\') {
                    escaped = true;
                    continue;
                }

                if (inCharClass) {
                    if (c == ']' && i > charClassStart + 1) {
                        // End of character class (not at start position)
                        regex.append("]");
                        inCharClass = false;
                        charClassStart = -1;
                    } else {
                        // Inside character class - handle special chars
                        if (c == '^' && i == charClassStart + 1) {
                            // ^ at start of char class is negation
                            regex.append('^');
                        } else if (c == '-' && i > charClassStart + 1 && i + 1 < glob.length() &&
                                glob.charAt(i + 1) != ']') {
                            // - in middle is range operator
                            regex.append('-');
                        } else if (c == '\\' || c == ']' || c == '[') {
                            // These need escaping in Java regex char class
                            regex.append('\\').append(c);
                        } else {
                            regex.append(c);
                        }
                    }
                } else {
                    switch (c) {
                        case '*':
                            regex.append(".*");
                            break;
                        case '?':
                            regex.append(".");
                            break;
                        case '[':
                            if (hasMatchingBracket(glob, i)) {
                                regex.append("[");
                                inCharClass = true;
                                charClassStart = i;
                            } else {
                                regex.append("\\[");
                            }
                            break;
                        case '.':
                        case '^':
                        case '$':
                        case '+':
                        case '{':
                        case '}':
                        case '(':
                        case ')':
                        case '|':
                            regex.append('\\').append(c);
                            break;
                        default:
                            regex.append(c);
                            break;
                    }
                }
            }

            regex.append("$");
            return Pattern.compile(regex.toString());

        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /**
     * Checks if a '[' at the given position has a matching ']'.
     */
    private static boolean hasMatchingBracket(String glob, int startPos) {
        boolean escaped = false;
        for (int i = startPos + 1; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == ']') {
                return true;
            }
        }
        return false;
    }

    /**
     * Determines if a backslash at the given position is likely a path separator.
     * On Windows, a backslash is a path separator when:
     * - It's part of a drive letter path (C:\)
     * - It's between directory/file names
     * - It's NOT followed by a glob meta character that could be escaped
     */
    static boolean isLikelyPathSeparator(String pattern, int pos) {
        // Check for drive letter pattern (e.g., C:\)
        if (pos == 2 && pos >= 2 &&
                Character.isLetter(pattern.charAt(0)) &&
                pattern.charAt(1) == ':') {
            return true;
        }

        if (pos + 1 >= pattern.length()) {
            return true; // Trailing backslash
        }

        char next = pattern.charAt(pos + 1);

        // In Windows paths, these characters after backslash indicate path separator
        // not escape sequence (except in character classes which are handled elsewhere)
        // Common path patterns: \file, \dir, \123, etc.
        if (Character.isLetterOrDigit(next) || next == '_' || next == '-' || next == '.') {
            return true;
        }

        // Check if we're in a path context by looking at surrounding characters
        // Look for patterns like "dir\file" or "C:\temp\*"
        if (pos > 0) {
            char prev = pattern.charAt(pos - 1);
            if (Character.isLetterOrDigit(prev) || prev == '_' || prev == '-' || prev == '.') {
                // Previous char suggests we're in a filename/dirname
                return true;
            }
        }

        // These characters after backslash are typically escape sequences
        if (next == '*' || next == '?' || next == '[' || next == ']' ||
                next == '{' || next == '}' || next == '\\' || next == ' ') {
            // However, in Windows absolute paths like C:\*.txt, the backslash
            // before * is still a path separator
            if (pos == 2 && pos >= 2 &&
                    Character.isLetter(pattern.charAt(0)) &&
                    pattern.charAt(1) == ':') {
                return true; // Drive letter path
            }
            return false; // Likely an escape sequence
        }

        // Default to path separator for other cases
        return true;
    }

    /**
     * Checks if a character at the given position is escaped.
     */
    static boolean isEscapedAt(String str, int pos) {
        if (pos == 0) return false;
        int backslashCount = 0;
        int checkPos = pos - 1;
        while (checkPos >= 0 && str.charAt(checkPos) == '\\') {
            backslashCount++;
            checkPos--;
        }
        return (backslashCount % 2) == 1;
    }

    /**
     * Normalizes path separators in a pattern for consistent internal processing.
     * On Windows, converts backslashes to forward slashes except when they're escape sequences.
     *
     * @param pattern the pattern to normalize
     * @return normalized pattern with forward slashes
     */
    static String normalizePathSeparators(String pattern) {
        // On Unix/Mac, don't change anything
        if (File.separatorChar == '/') {
            return pattern;
        }

        // On Windows, we need to intelligently handle backslashes
        StringBuilder result = new StringBuilder();
        boolean inCharClass = false;

        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);

            if (c == '[' && !isEscapedAt(pattern, i)) {
                inCharClass = true;
                result.append(c);
            } else if (c == ']' && inCharClass && !isEscapedAt(pattern, i)) {
                inCharClass = false;
                result.append(c);
            } else if (c == '\\' && !isEscapedAt(pattern, i)) {
                // Check if this backslash is followed by another backslash (UNC path)
                if (i == 0 && i + 1 < pattern.length() && pattern.charAt(i + 1) == '\\') {
                    // UNC path start - convert both to forward slashes
                    result.append("//");
                    i++; // Skip next backslash
                } else if (i + 1 < pattern.length()) {
                    char next = pattern.charAt(i + 1);
                    // In a file path context, backslash before these chars is a path separator
                    // not an escape (unless we're in a character class)
                    if (!inCharClass && isLikelyPathSeparator(pattern, i)) {
                        result.append('/');
                    } else {
                        // This is an escape sequence, keep the backslash
                        result.append('\\');
                    }
                } else {
                    // Trailing backslash - convert to forward slash
                    result.append('/');
                }
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

    /**
     * Checks if a pattern is an absolute path (Windows or Unix).
     * Must check against the original pattern, not normalized.
     */
    static boolean isAbsolutePath(String pattern) {
        if (pattern.isEmpty()) {
            return false;
        }

        // Check for Unix absolute path
        if (pattern.startsWith("/")) {
            return true;
        }

        // Check for Windows absolute path (C:\, D:/, etc.)
        if (pattern.length() >= 3 &&
                Character.isLetter(pattern.charAt(0)) &&
                pattern.charAt(1) == ':' &&
                (pattern.charAt(2) == '\\' || pattern.charAt(2) == '/')) {
            return true;
        }

        // Check for UNC path (\\server\share)
        if (pattern.startsWith("\\\\") || pattern.startsWith("//")) {
            return true;
        }

        return false;
    }

    /**
     * Splits brace content by commas, respecting nested braces.
     */
    static String[] splitBraceContent(String content) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean escaped = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);

            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }

            if (c == '\\') {
                current.append(c);
                escaped = true;
            } else if (c == '{') {
                depth++;
                current.append(c);
            } else if (c == '}') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                parts.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            parts.add(current.toString());
        }

        return parts.toArray(new String[0]);
    }

    /**
     * Finds the matching closing brace for an opening brace, skipping character classes.
     */
    static int findMatchingBrace(String str, int start) {
        int depth = 1;
        boolean escaped = false;
        boolean inCharClass = false;

        for (int i = start + 1; i < str.length(); i++) {
            char c = str.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }

            // Handle character classes
            if (c == '[' && !inCharClass) {
                inCharClass = true;
                continue;
            }
            if (c == ']' && inCharClass) {
                inCharClass = false;
                continue;
            }

            // Only process braces if not in character class
            if (!inCharClass) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * Finds the first unescaped occurrence of a character, skipping character classes.
     */
    static int findUnescapedChar(String str, char target, int start) {
        boolean escaped = false;
        boolean inCharClass = false;

        for (int i = start; i < str.length(); i++) {
            char c = str.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }

            // Handle character classes
            if (c == '[' && !inCharClass) {
                inCharClass = true;
                continue;
            }
            if (c == ']' && inCharClass) {
                inCharClass = false;
                continue;
            }

            // Only match target if not in character class
            if (c == target && !inCharClass) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Expands brace expressions in a pattern (e.g., {a,b,c} -> a, b, c).
     *
     * @param pattern the pattern containing brace expressions
     * @return list of expanded patterns
     */
    static List<String> expandBraces(String pattern) {
        List<String> results = new ArrayList<>();
        results.add(pattern);

        boolean hasMoreBraces = true;
        while (hasMoreBraces) {
            hasMoreBraces = false;
            List<String> newResults = new ArrayList<>();

            for (String current : results) {
                int braceStart = findUnescapedChar(current, '{', 0);
                if (braceStart == -1) {
                    newResults.add(current);
                    continue;
                }

                hasMoreBraces = true;
                int braceEnd = findMatchingBrace(current, braceStart);
                if (braceEnd == -1) {
                    newResults.add(current);
                    continue;
                }

                String prefix = current.substring(0, braceStart);
                String braceContent = current.substring(braceStart + 1, braceEnd);
                String suffix = current.substring(braceEnd + 1);

                String[] options = splitBraceContent(braceContent);
                for (String option : options) {
                    newResults.add(prefix + option + suffix);
                }
            }

            results = newResults;
        }

        return results;
    }

    /**
     * Processes a glob pattern and returns all matching file paths.
     */
    static List<String> processPattern(ScalarGlobOperator scalarGlobOperator, String pattern) {
        List<String> results = new ArrayList<>();

        // Parse pattern for multiple patterns separated by whitespace
        List<String> patterns = parsePatterns(pattern);

        // Process each pattern
        for (String singlePattern : patterns) {
            // Expand braces first
            List<String> expandedPatterns = expandBraces(singlePattern);

            for (String expandedPattern : expandedPatterns) {
                results.addAll(ScalarGlobOperator.globSinglePattern(scalarGlobOperator, expandedPattern));
            }
        }

        return results;
    }

    /**
     * Parses a pattern string into individual patterns, handling quoted sections.
     *
     * @param pattern the pattern string to parse
     * @return list of individual patterns
     */
    private static List<String> parsePatterns(String pattern) {
        List<String> patterns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;

        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);

            if (!inQuotes && (c == '"' || c == '\'')) {
                inQuotes = true;
                quoteChar = c;
            } else if (inQuotes && c == quoteChar) {
                inQuotes = false;
                quoteChar = 0;
            } else if (!inQuotes && Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    patterns.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            patterns.add(current.toString());
        }

        return patterns;
    }
}

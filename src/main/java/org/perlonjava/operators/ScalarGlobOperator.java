package org.perlonjava.operators;

import org.perlonjava.runtime.*;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

/**
 * Implements Perl's glob operator functionality for file pattern matching.
 */
public class ScalarGlobOperator {

    /** Map storing glob operator instances by their unique ID for state management */
    private static final Map<Integer, ScalarGlobOperator> globOperators = new HashMap<>();

    /** Counter for generating unique operator IDs */
    public static Integer currentId = 0;

    /** Iterator over the current glob results */
    private Iterator<String> iterator;

    /** The current pattern being processed */
    private String currentPattern;

    /** Flag indicating if the iterator has been exhausted */
    private boolean isExhausted = false;

    /**
     * Creates a new glob operator for the given pattern.
     *
     * @param pattern the glob pattern to match files against
     */
    public ScalarGlobOperator(String pattern) {
        this.currentPattern = pattern;
        initializeIterator(pattern);
    }

    /**
     * Evaluates a glob pattern in the given context.
     *
     * <p>In scalar context, returns one result at a time, maintaining state
     * between calls. In list context, returns all matching results at once.</p>
     *
     * @param id the unique identifier for this glob operator instance
     * @param patternArg the glob pattern as a RuntimeScalar
     * @param ctx the runtime context (scalar or list)
     * @return RuntimeBase containing the results
     */
    public static RuntimeBase evaluate(int id, RuntimeScalar patternArg, int ctx) {
        String pattern = patternArg.toString();

        if (ctx == RuntimeContextType.SCALAR) {
            return evaluateInScalarContext(id, pattern);
        } else {
            return evaluateInListContext(pattern);
        }
    }

    /**
     * Evaluates glob in scalar context, returning one result per call.
     */
    private static RuntimeBase evaluateInScalarContext(int id, String pattern) {
        ScalarGlobOperator globOperator = globOperators.get(id);

        if (globOperator == null) {
            // First call - create new operator
            globOperator = new ScalarGlobOperator(pattern);
            globOperators.put(id, globOperator);
        } else if (!globOperator.currentPattern.equals(pattern)) {
            // Pattern changed - reinitialize
            globOperator.initializeIterator(pattern);
            globOperator.currentPattern = pattern;
        } else if (globOperator.isExhausted && !globOperator.iterator.hasNext()) {
            // Iterator exhausted - restart for new iteration
            globOperator.initializeIterator(pattern);
            globOperator.isExhausted = false;
        }

        if (globOperator.iterator.hasNext()) {
            String result = globOperator.iterator.next();
            getGlobalVariable("main::_").set(result);
            return new RuntimeScalar(result);
        } else {
            globOperator.isExhausted = true;
            getGlobalVariable("main::_").set(scalarUndef);
            return scalarUndef;
        }
    }

    /**
     * Evaluates glob in list context, returning all results at once.
     */
    private static RuntimeList evaluateInListContext(String pattern) {
        RuntimeList resultList = new RuntimeList();
        ScalarGlobOperator globOperator = new ScalarGlobOperator(pattern);
        globOperator.iterator.forEachRemaining(path ->
                resultList.elements.add(new RuntimeScalar(path)));
        return resultList;
    }

    /**
     * Initializes the iterator with results from the given pattern.
     *
     * @param pattern the glob pattern to process
     */
    private void initializeIterator(String pattern) {
        this.isExhausted = false;

        if (pattern.isEmpty()) {
            this.iterator = Collections.emptyIterator();
            return;
        }

        List<String> results = processPattern(pattern);

        // Sort results and remove duplicates
        results = new ArrayList<>(new TreeSet<>(results));
        this.iterator = results.iterator();
    }

    /**
     * Processes a glob pattern and returns all matching file paths.
     */
    private List<String> processPattern(String pattern) {
        List<String> results = new ArrayList<>();

        // Parse pattern for multiple patterns separated by whitespace
        List<String> patterns = parsePatterns(pattern);

        // Process each pattern
        for (String singlePattern : patterns) {
            // Expand braces first
            List<String> expandedPatterns = expandBraces(singlePattern);

            for (String expandedPattern : expandedPatterns) {
                results.addAll(globSinglePattern(expandedPattern));
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
    private List<String> parsePatterns(String pattern) {
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

    /**
     * Expands brace expressions in a pattern (e.g., {a,b,c} -> a, b, c).
     *
     * @param pattern the pattern containing brace expressions
     * @return list of expanded patterns
     */
    private List<String> expandBraces(String pattern) {
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
     * Finds the first unescaped occurrence of a character, skipping character classes.
     */
    private int findUnescapedChar(String str, char target, int start) {
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
 * Finds the matching closing brace for an opening brace, skipping character classes.
 */
private int findMatchingBrace(String str, int start) {
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
     * Splits brace content by commas, respecting nested braces.
     */
    private String[] splitBraceContent(String content) {
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
     * Performs glob matching for a single pattern.
     *
     * @param pattern the glob pattern to match
     * @return list of matching file paths
     */
    private List<String> globSinglePattern(String pattern) {
        List<String> results = new ArrayList<>();

        try {
            // Preserve the original pattern format for result formatting
            String originalPattern = pattern;

            // Normalize path separators for Windows compatibility
            String normalizedPattern = normalizePathSeparators(pattern);

            // Check if pattern is absolute
            boolean patternIsAbsolute = isAbsolutePath(originalPattern);

            // Extract directory and file pattern
            PathComponents components = extractPathComponents(normalizedPattern, patternIsAbsolute);

            if (!components.baseDir.exists() || components.filePattern.isEmpty()) {
                // For non-existent paths or empty patterns, return literal if no glob chars
                if (!containsGlobChars(pattern)) {
                    results.add(pattern);
                }
                return results;
            }

            // Convert glob pattern to regex
            Pattern regex = globToRegex(components.filePattern);
            if (regex == null) {
                return results;
            }

            // Match files against pattern
            matchFiles(components, regex, results, originalPattern, patternIsAbsolute);

            // For exact matches that don't exist (from brace expansion)
            if (results.isEmpty() && !containsGlobChars(pattern)) {
                results.add(pattern);
            }
        } catch (Exception e) {
            // Return empty results on error
        }

        return results;
    }

    /**
     * Checks if a pattern is an absolute path (Windows or Unix).
     * Must check against the original pattern, not normalized.
     */
    private boolean isAbsolutePath(String pattern) {
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
     * Holds path components after parsing.
     */
    private static class PathComponents {
        final File baseDir;
        final String filePattern;
        final boolean hasDirectory;
        final String directoryPart;

        PathComponents(File baseDir, String filePattern, boolean hasDirectory, String directoryPart) {
            this.baseDir = baseDir;
            this.filePattern = filePattern;
            this.hasDirectory = hasDirectory;
            this.directoryPart = directoryPart;
        }
    }

    /**
     * Extracts directory and file pattern components from a path.
     */
    private PathComponents extractPathComponents(String normalizedPattern, boolean isAbsolute) {
        File baseDir;
        String filePattern = normalizedPattern;
        boolean hasDirectory = false;
        String directoryPart = "";

        // Use forward slash as canonical separator internally
        int lastSep = normalizedPattern.lastIndexOf('/');

        if (lastSep >= 0) {
            hasDirectory = true;
            directoryPart = normalizedPattern.substring(0, lastSep);

            if (directoryPart.isEmpty()) {
                // Root directory case
                baseDir = RuntimeIO.resolveFile("/");
            } else {
                // Use RuntimeIO.resolveFile for proper path resolution
                baseDir = RuntimeIO.resolveFile(directoryPart);
            }

            filePattern = normalizedPattern.substring(lastSep + 1);
        } else {
            // No directory separator - use current directory
            baseDir = new File(System.getProperty("user.dir"));
        }

        return new PathComponents(baseDir, filePattern, hasDirectory, directoryPart);
    }

    /**
     * Matches files in a directory against a pattern.
     */
    private void matchFiles(PathComponents components, Pattern regex, List<String> results,
                            String originalPattern, boolean patternIsAbsolute) {
        File[] files;
        try {
            files = components.baseDir.listFiles();
        } catch (SecurityException e) {
            getGlobalVariable("main::!").set(new RuntimeScalar("Permission denied"));
            return;
        }

        if (files == null) {
            return;
        }

        for (File file : files) {
            String fileName = file.getName();

            // Skip hidden files unless pattern starts with dot
            if (!components.filePattern.startsWith(".") && fileName.startsWith(".")) {
                continue;
            }

            boolean matches = regex.matcher(fileName).matches();

            if (matches) {
                String result = formatResult(file, components, originalPattern, patternIsAbsolute);
                results.add(result);
            }
        }
    }

    /**
     * Normalizes path separators in a pattern for consistent internal processing.
     * On Windows, converts backslashes to forward slashes except when they're escape sequences.
     *
     * @param pattern the pattern to normalize
     * @return normalized pattern with forward slashes
     */
    private String normalizePathSeparators(String pattern) {
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
     * Checks if a character at the given position is escaped.
     */
    private boolean isEscapedAt(String str, int pos) {
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
     * Determines if a backslash at the given position is likely a path separator.
     * On Windows, a backslash is a path separator when:
     * - It's part of a drive letter path (C:\)
     * - It's between directory/file names
     * - It's NOT followed by a glob meta character that could be escaped
     */
    private boolean isLikelyPathSeparator(String pattern, int pos) {
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
     * Converts a glob pattern to a regular expression.
     *
     * @param glob the glob pattern
     * @return compiled regex pattern, or null on error
     */
    private Pattern globToRegex(String glob) {
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
                        // In glob character class: \[ means literal [, \] means literal ]
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
    private boolean hasMatchingBracket(String glob, int startPos) {
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
     * Formats a file result based on Perl's glob behavior.
     * For relative paths, returns results in platform-native format.
     * For absolute paths, preserves the original pattern's style.
     */
    private String formatResult(File file, PathComponents components, String originalPattern, boolean patternIsAbsolute) {
        String fileName = file.getName();

        if (patternIsAbsolute) {
            // For absolute patterns, return the full absolute path
            String absPath = file.getAbsolutePath();

            // On Windows, if the original pattern used forward slashes, convert result to forward slashes
            if (File.separatorChar == '\\' && originalPattern.indexOf('/') >= 0) {
                absPath = absPath.replace('\\', '/');
            }

            return absPath;
        } else if (components.hasDirectory) {
            // Relative pattern with directory
            // Return in platform-native format (like Perl does)
            if (File.separatorChar == '\\') {
                // Windows - use backslashes for result
                return components.directoryPart.replace('/', '\\') + '\\' + fileName;
            } else {
                // Unix/Mac - use forward slashes
                return components.directoryPart + '/' + fileName;
            }
        } else {
            // Pattern has no directory separators - return just filename
            return fileName;
        }
    }

    /**
     * Checks if a pattern contains glob wildcard characters.
     *
     * @param pattern the pattern to check
     * @return true if pattern contains unescaped glob characters
     */
    private boolean containsGlobChars(String pattern) {
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
}

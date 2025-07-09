package org.perlonjava.operators;

import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.RuntimeDataProvider;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

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
 *
 * <p>The glob operator expands file patterns containing wildcards (* ? []) and
 * brace expansions ({a,b,c}) into lists of matching file paths. It maintains
 * state between scalar context calls to iterate through results.</p>
 *
 * <p>Supported pattern features:
 * <ul>
 *   <li>* - matches any sequence of characters</li>
 *   <li>? - matches any single character</li>
 *   <li>[...] - character class matching</li>
 *   <li>{a,b,c} - brace expansion for alternatives</li>
 *   <li>Escape sequences with backslash</li>
 * </ul>
 * </p>
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
     * @return RuntimeDataProvider containing the results
     */
    public static RuntimeDataProvider evaluate(int id, RuntimeScalar patternArg, int ctx) {
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
    private static RuntimeDataProvider evaluateInScalarContext(int id, String pattern) {
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
     * Finds the first unescaped occurrence of a character.
     */
    private int findUnescapedChar(String str, char target, int start) {
        boolean escaped = false;
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
            if (c == target) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Finds the matching closing brace for an opening brace.
     */
    private int findMatchingBrace(String str, int start) {
        int depth = 1;
        boolean escaped = false;

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
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
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
            String cwd = System.getProperty("user.dir");

            // Check for Windows absolute path
            boolean isWindowsAbsolute = isWindowsAbsolutePath(pattern);

            // Normalize path separators
            String normalizedPattern = normalizePathSeparators(pattern);

            // Extract directory and file pattern
            PathComponents components = extractPathComponents(normalizedPattern, cwd, isWindowsAbsolute);

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
            matchFiles(components, regex, results, cwd);

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
     * Checks if a pattern is a Windows absolute path.
     */
    private boolean isWindowsAbsolutePath(String pattern) {
        return pattern.length() >= 3 &&
                Character.isLetter(pattern.charAt(0)) &&
                pattern.charAt(1) == ':' &&
                (pattern.charAt(2) == '\\' || pattern.charAt(2) == '/');
    }

    /**
     * Holds path components after parsing.
     */
    private static class PathComponents {
        final File baseDir;
        final String filePattern;
        final boolean hasDirectory;
        final boolean isAbsolute;

        PathComponents(File baseDir, String filePattern, boolean hasDirectory, boolean isAbsolute) {
            this.baseDir = baseDir;
            this.filePattern = filePattern;
            this.hasDirectory = hasDirectory;
            this.isAbsolute = isAbsolute;
        }
    }

    /**
     * Extracts directory and file pattern components from a path.
     */
    private PathComponents extractPathComponents(String normalizedPattern, String cwd, boolean isWindowsAbsolute) {
        File baseDir = new File(cwd);
        String filePattern = normalizedPattern;
        boolean hasDirectory = false;
        boolean isAbsolute = isWindowsAbsolute || normalizedPattern.startsWith("/");

        int lastSep = normalizedPattern.lastIndexOf('/');

        if (lastSep >= 0) {
            hasDirectory = true;
            String dirPart = normalizedPattern.substring(0, lastSep);

            if (dirPart.isEmpty()) {
                baseDir = new File("/");
            } else {
                dirPart = dirPart.replace('/', File.separatorChar);
                baseDir = new File(dirPart);
                if (!baseDir.isAbsolute()) {
                    baseDir = new File(cwd, dirPart);
                }
            }

            filePattern = normalizedPattern.substring(lastSep + 1);
        }

        return new PathComponents(baseDir, filePattern, hasDirectory, isAbsolute);
    }

    /**
     * Matches files in a directory against a pattern.
     */
    private void matchFiles(PathComponents components, Pattern regex, List<String> results, String cwd) {
        File[] files = components.baseDir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            String fileName = file.getName();

            // Skip hidden files unless pattern starts with dot
            if (!components.filePattern.startsWith(".") && fileName.startsWith(".")) {
                continue;
            }

            if (regex.matcher(fileName).matches()) {
                String result = formatResult(file, components.hasDirectory, components.isAbsolute, cwd);
                results.add(result);
            }
        }
    }

    /**
     * Normalizes path separators in a pattern, handling escape sequences.
     *
     * @param pattern the pattern to normalize
     * @return normalized pattern with forward slashes
     */
    private String normalizePathSeparators(String pattern) {
        if (File.separatorChar != '\\') {
            return pattern.replace('\\', '/');
        }

        // On Windows, distinguish between path separators and escape sequences
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);

            if (c == '\\' && i + 1 < pattern.length()) {
                char next = pattern.charAt(i + 1);
                // Check if this is an escape sequence
                if (isGlobSpecialChar(next) || next == '\\') {
                    result.append('\\').append(next);
                    i++; // Skip the next character
                } else {
                    // Treat as path separator
                    result.append('/');
                }
            } else if (c == '\\') {
                // Trailing backslash - treat as path separator
                result.append('/');
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

    /**
     * Checks if a character is a glob special character.
     */
    private boolean isGlobSpecialChar(char c) {
        return c == '*' || c == '?' || c == '[' || c == ']' || c == '{' || c == '}';
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

        try {
            for (int i = 0; i < glob.length(); i++) {
                char c = glob.charAt(i);

                if (escaped) {
                    regex.append(Pattern.quote(String.valueOf(c)));
                    escaped = false;
                    continue;
                }

                if (c == '\\' && i + 1 < glob.length()) {
                    escaped = true;
                    continue;
                }

                if (inCharClass) {
                    handleCharClassChar(c, regex);
                    if (c == ']') {
                        inCharClass = false;
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
                            regex.append("[");
                            inCharClass = true;
                            break;
                        default:
                            regex.append(Pattern.quote(String.valueOf(c)));
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
     * Handles a character inside a character class in regex conversion.
     */
    private void handleCharClassChar(char c, StringBuilder regex) {
        // Inside character class, most chars are literal
        if (c == '\\' || c == '-' || c == '^' || c == '[') {
            regex.append('\\');
        }
        regex.append(c);
    }

    /**
     * Formats a file result based on context.
     */
    private String formatResult(File file, boolean hasDirectory, boolean isAbsolute, String cwd) {
        String result;

        if (isAbsolute) {
            result = file.getAbsolutePath();
        } else if (hasDirectory) {
            result = getRelativePath(file, cwd);
        } else {
            result = file.getName();
        }

        // Normalize to forward slashes for Perl compatibility
        return result.replace('\\', '/');
    }

    /**
     * Gets the relative path of a file from the specified base directory.
     *
     * @param file the file to get the relative path for
     * @param baseDir the base directory to calculate the relative path from (typically cwd)
     * @return the relative path as a string
     */
    private String getRelativePath(File file, String baseDir) {
        try {
            Path basePath = Paths.get(baseDir);
            Path filePath = file.toPath();

            if (filePath.startsWith(basePath)) {
                return basePath.relativize(filePath).toString();
            }
        } catch (Exception e) {
            // Fall back to absolute path on error
        }
        return file.getPath();
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

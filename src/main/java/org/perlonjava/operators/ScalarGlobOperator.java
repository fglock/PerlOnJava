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

public class ScalarGlobOperator {

    // Map to store the state (glob iterator) for each operator instance
    public static final Map<Integer, ScalarGlobOperator> globOperators = new HashMap<>();
    public static Integer currentId = 0;

    private Iterator<String> iterator;
    private String currentPattern;
    private boolean isExhausted = false;

    public ScalarGlobOperator(String pattern) {
        this.currentPattern = pattern;
        initializeIterator(pattern);
    }

    public static RuntimeDataProvider evaluate(int id, RuntimeScalar patternArg, int ctx) {
        String pattern = patternArg.toString();

        if (ctx == RuntimeContextType.SCALAR) {
            ScalarGlobOperator globOperator = globOperators.get(id);

            if (globOperator == null) {
                globOperator = new ScalarGlobOperator(pattern);
                globOperators.put(id, globOperator);
            } else if (!globOperator.currentPattern.equals(pattern)) {
                globOperator.initializeIterator(pattern);
                globOperator.currentPattern = pattern;
            } else if (globOperator.isExhausted && !globOperator.iterator.hasNext()) {
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
        } else {
            RuntimeList resultList = new RuntimeList();
            ScalarGlobOperator globOperator = new ScalarGlobOperator(pattern);
            globOperator.iterator.forEachRemaining(path -> resultList.elements.add(new RuntimeScalar(path)));
            return resultList;
        }
    }

    private void initializeIterator(String pattern) {
        this.isExhausted = false;
        List<String> results = new ArrayList<>();

        if (pattern.isEmpty()) {
            this.iterator = Collections.emptyIterator();
            return;
        }

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

        // Sort results and remove duplicates
        results = new ArrayList<>(new TreeSet<>(results));
        this.iterator = results.iterator();
    }

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
                continue;
            }

            if (c == '{') {
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

    private List<String> globSinglePattern(String pattern) {
        List<String> results = new ArrayList<>();

        try {
            // Get current working directory
            String cwd = System.getProperty("user.dir");

            // Check if this is a Windows absolute path BEFORE any normalization
            boolean isWindowsAbsolute = pattern.length() >= 3 &&
                    Character.isLetter(pattern.charAt(0)) &&
                    pattern.charAt(1) == ':' &&
                    (pattern.charAt(2) == '\\' || pattern.charAt(2) == '/');

            // For Windows, we need to be careful about path separators vs escape chars
            // Only convert backslashes that are clearly path separators
            String normalizedPattern = normalizePathSeparators(pattern);

            // Separate directory from file pattern
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
                    // Convert back to native separators for File operations
                    dirPart = dirPart.replace('/', File.separatorChar);
                    baseDir = new File(dirPart);
                    if (!baseDir.isAbsolute()) {
                        baseDir = new File(cwd, dirPart);
                    }
                }

                filePattern = normalizedPattern.substring(lastSep + 1);
            }

            if (!baseDir.exists() || filePattern.isEmpty()) {
                if (!containsGlobChars(pattern)) {
                    results.add(pattern);
                }
                return results;
            }

            // Convert glob pattern to regex
            Pattern regex = globToRegex(filePattern);
            if (regex == null) {
                return results;
            }

            // List files and match against pattern
            File[] files = baseDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    String fileName = file.getName();

                    // Handle hidden files
                    if (!filePattern.startsWith(".") && fileName.startsWith(".")) {
                        continue;
                    }

                    if (regex.matcher(fileName).matches()) {
                        String result = formatResult(file, hasDirectory, isAbsolute, cwd);
                        results.add(result);
                    }
                }
            }

            // For exact matches that don't exist (from brace expansion)
            if (results.isEmpty() && !containsGlobChars(pattern)) {
                results.add(pattern);
            }
        } catch (Exception e) {
            // Don't propagate exceptions - just return empty results
            System.err.println("Glob error for pattern '" + pattern + "': " + e.getMessage());
        }

        return results;
    }

    private String normalizePathSeparators(String pattern) {
        // On Windows, we need to distinguish between path separators and escape backslashes
        // This is tricky because both use backslash

        // If not on Windows, just convert backslashes to forward slashes
        if (File.separatorChar != '\\') {
            return pattern.replace('\\', '/');
        }

        // On Windows, we need to be smarter
        StringBuilder result = new StringBuilder();
        boolean prevWasBackslash = false;

        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);

            if (c == '\\') {
                if (prevWasBackslash) {
                    // Double backslash - this is an escaped backslash
                    result.append("\\\\");
                    prevWasBackslash = false;
                } else {
                    // Single backslash - could be path separator or escape
                    // Look ahead to see what follows
                    if (i + 1 < pattern.length()) {
                        char next = pattern.charAt(i + 1);
                        // If next char is a glob special char, it's an escape
                        if (next == '*' || next == '?' || next == '[' || next == ']' || next == '{' || next == '}') {
                            result.append('\\');
                            prevWasBackslash = false;
                        } else if (next == '\\') {
                            // Could be escaped backslash or path with multiple separators
                            prevWasBackslash = true;
                        } else {
                            // Probably a path separator
                            result.append('/');
                            prevWasBackslash = false;
                        }
                    } else {
                        // Trailing backslash - treat as path separator
                        result.append('/');
                        prevWasBackslash = false;
                    }
                }
            } else {
                if (prevWasBackslash) {
                    // Previous was single backslash followed by non-special char
                    result.append('/');
                    prevWasBackslash = false;
                }
                result.append(c);
            }
        }

        if (prevWasBackslash) {
            result.append('/');
        }

        return result.toString();
    }

    private Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        boolean escaped = false;
        boolean inCharClass = false;

        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);

            if (escaped) {
                // Add the escaped character literally
                if (c == 'n') {
                    regex.append('\n');
                } else if (c == 't') {
                    regex.append('\t');
                } else if (c == 'r') {
                    regex.append('\r');
                } else {
                    regex.append(Pattern.quote(String.valueOf(c)));
                }
                escaped = false;
                continue;
            }

            if (c == '\\' && i + 1 < glob.length()) {
                escaped = true;
                continue;
            } else if (c == '\\') {
                // Trailing backslash - don't add it as an escape character
                // Instead, treat it as a literal backslash
                regex.append("\\\\");
                continue;
            }

            if (inCharClass) {
                if (c == ']') {
                    regex.append(']');
                    inCharClass = false;
                } else {
                    // Inside character class, most chars are literal
                    // Only need to escape certain regex metacharacters
                    if (c == '\\' || c == '-' || c == '^' || c == '[' || c == ']') {
                        regex.append('\\');
                    }
                    regex.append(c);
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
                    case '.':
                    case '(':
                    case ')':
                    case '+':
                    case '|':
                    case '^':
                    case '$':
                    case '{':
                    case '}':
                        regex.append('\\').append(c);
                        break;
                    default:
                        regex.append(c);
                        break;
                }
            }
        }

        regex.append("$");

        try {
            return Pattern.compile(regex.toString());
        } catch (PatternSyntaxException e) {
            System.err.println("Failed to compile regex from glob '" + glob + "': " + regex.toString());
            return null;
        }
    }

    private String formatResult(File file, boolean hasDirectory, boolean isAbsolute, String cwd) {
        String result;

        if (isAbsolute) {
            result = file.getAbsolutePath();
        } else if (hasDirectory) {
            try {
                Path cwdPath = Paths.get(cwd);
                Path filePath = file.toPath();

                if (filePath.startsWith(cwdPath)) {
                    result = cwdPath.relativize(filePath).toString();
                } else {
                    result = file.getPath();
                }
            } catch (Exception e) {
                result = file.getPath();
            }
        } else {
            result = file.getName();
        }

        // Always normalize to forward slashes for Perl compatibility
        return result.replace('\\', '/');
    }

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

package org.perlonjava.operators;

import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.RuntimeDataProvider;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeIO.flushFileHandles;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

public class ScalarGlobOperator {

    // Map to store the state (glob iterator) for each operator instance
    public static final Map<Integer, ScalarGlobOperator> globOperators = new HashMap<>();
    public static Integer currentId = 0;

    private Iterator<String> iterator;
    private String currentPattern;
    private boolean isExhausted = false;  // Add this field to track exhausted state

    public ScalarGlobOperator(String pattern) throws IOException {
        this.currentPattern = pattern;
        initializeIterator(pattern);
    }

    public static RuntimeDataProvider evaluate(int id, RuntimeScalar patternArg, int ctx) {
        String pattern = patternArg.toString();

        if (ctx == RuntimeContextType.SCALAR) {
            ScalarGlobOperator globOperator = globOperators.get(id);

            if (globOperator == null) {
                try {
                    globOperator = new ScalarGlobOperator(pattern);
                    globOperators.put(id, globOperator);
                } catch (IOException e) {
                    getGlobalVariable("main::!").set("Glob operation failed: " + e.getMessage());
                    return scalarUndef;
                }
            } else if (!globOperator.currentPattern.equals(pattern)) {
                try {
                    globOperator.initializeIterator(pattern);
                    globOperator.currentPattern = pattern;
                } catch (IOException e) {
                    getGlobalVariable("main::!").set("Glob operation failed: " + e.getMessage());
                    return scalarUndef;
                }
            } else if (globOperator.isExhausted && !globOperator.iterator.hasNext()) {
                // Reset exhausted state when called from a new context
                // This mimics Perl's behavior where glob state resets in new execution contexts
                try {
                    globOperator.initializeIterator(pattern);
                    globOperator.isExhausted = false;
                } catch (IOException e) {
                    getGlobalVariable("main::!").set("Glob operation failed: " + e.getMessage());
                    return scalarUndef;
                }
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
            try {
                ScalarGlobOperator globOperator = new ScalarGlobOperator(pattern);
                globOperator.iterator.forEachRemaining(path -> resultList.elements.add(new RuntimeScalar(path)));
            } catch (IOException e) {
                getGlobalVariable("main::!").set("Glob operation failed: " + e.getMessage());
            }
            return resultList;
        }
    }

    private void initializeIterator(String pattern) throws IOException {
        this.isExhausted = false;  // Reset exhausted state when reinitializing
        List<String> results = new ArrayList<>();

        // Handle empty pattern
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

        // Sort results to match Perl's behavior
        Collections.sort(results);

        // Remove duplicates while preserving order
        LinkedHashSet<String> uniqueResults = new LinkedHashSet<>(results);

        this.iterator = uniqueResults.iterator();
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
                int braceStart = findNextBraceOutsideCharClass(current, 0);
                if (braceStart == -1) {
                    newResults.add(current);
                    continue;
                }

                hasMoreBraces = true;
                int braceEnd = findClosingBrace(current, braceStart);
                if (braceEnd == -1) {
                    // Malformed brace, treat as literal
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

    private int findNextBraceOutsideCharClass(String str, int start) {
        boolean inCharClass = false;
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

            if (c == '[' && !inCharClass) {
                inCharClass = true;
            } else if (c == ']' && inCharClass) {
                inCharClass = false;
            } else if (c == '{' && !inCharClass) {
                return i;
            }
        }

        return -1;
    }

    private int findClosingBrace(String str, int start) {
        int depth = 1;
        boolean inCharClass = false;
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

            if (c == '[' && !inCharClass) {
                inCharClass = true;
            } else if (c == ']' && inCharClass) {
                inCharClass = false;
            } else if (!inCharClass) {
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
        return -1; // No matching closing brace
    }

    private String[] splitBraceContent(String content) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inCharClass = false;
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

            if (c == '[' && !inCharClass) {
                inCharClass = true;
                current.append(c);
            } else if (c == ']' && inCharClass) {
                inCharClass = false;
                current.append(c);
            } else if (!inCharClass) {
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
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            parts.add(current.toString());
        }

        return parts.toArray(new String[0]);
    }

    private List<String> globSinglePattern(String pattern) throws IOException {
        List<String> results = new ArrayList<>();

        // Get current working directory
        Path cwd = Paths.get(System.getProperty("user.dir"));

        // Separate directory path from glob pattern
        int lastSlash = pattern.lastIndexOf('/');
        Path targetDir;
        String globPattern;
        boolean isAbsolute = pattern.startsWith("/");

        if (lastSlash >= 0) {
            // Pattern includes directory path
            String dirPath = pattern.substring(0, lastSlash);
            if (dirPath.isEmpty()) {
                targetDir = Paths.get("/");
            } else {
                Path path = Paths.get(dirPath);
                if (path.isAbsolute()) {
                    targetDir = path;
                } else {
                    targetDir = cwd.resolve(dirPath);
                }
            }
            globPattern = pattern.substring(lastSlash + 1);
        } else {
            // No directory separator, use current directory
            targetDir = cwd;
            globPattern = pattern;
        }

        // Check if directory exists
        if (!Files.exists(targetDir) || !Files.isDirectory(targetDir)) {
            // For brace expansion results that don't match files, return the pattern as-is
            if (!containsGlobChars(pattern)) {
                results.add(pattern);
            }
            return results;
        }

        // Handle exact matches (no wildcards)
        if (!containsGlobChars(globPattern)) {
            Path exactPath = targetDir.resolve(globPattern);
            if (Files.exists(exactPath)) {
                results.add(formatPath(exactPath, cwd, isAbsolute, pattern));
            } else if (!containsGlobChars(pattern)) {
                // For brace expansion results that don't match files, return the pattern as-is
                results.add(pattern);
            }
            return results;
        }

        // Use Files.list() for non-recursive listing
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);

        try (var stream = Files.list(targetDir)) {
            stream.filter(path -> {
                        String fileName = path.getFileName().toString();
                        // Handle hidden files
                        if (globPattern.equals(".*")) {
                            // Special case: .* should match hidden files but not . and ..
                            return fileName.startsWith(".") && !fileName.equals(".") && !fileName.equals("..");
                        } else if (!globPattern.startsWith(".") && fileName.startsWith(".")) {
                            // Exclude hidden files unless pattern explicitly includes them
                            return false;
                        }
                        return matcher.matches(path.getFileName());
                    })
                    .sorted()
                    .forEach(path -> results.add(formatPath(path, cwd, isAbsolute, pattern)));
        }

        return results;
    }

    private boolean containsGlobChars(String pattern) {
        return pattern.contains("*") || pattern.contains("?") ||
                pattern.contains("[") || pattern.contains("{");
    }

    private String formatPath(Path path, Path cwd, boolean patternIsAbsolute, String originalPattern) {
        // If pattern contains directory separator, preserve the directory structure
        if (originalPattern.contains("/")) {
            if (patternIsAbsolute) {
                return path.toString();
            } else {
                // Return relative path from current directory
                try {
                    Path relativePath = cwd.relativize(path);
                    return relativePath.toString();
                } catch (IllegalArgumentException e) {
                    // If paths have different roots, return absolute
                    return path.toString();
                }
            }
        } else {
            // Pattern has no directory separator, return just the filename
            return path.getFileName().toString();
        }
    }
}

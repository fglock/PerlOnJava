package org.perlonjava.operators;

import org.perlonjava.runtime.*;

import java.io.File;
import java.util.*;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

/**
 * Implements Perl's glob operator functionality for file pattern matching.
 */
public class ScalarGlobOperator {

    /**
     * Map storing glob operator instances by their unique ID for state management
     */
    private static final Map<Integer, ScalarGlobOperator> globOperators = new HashMap<>();

    /**
     * Counter for generating unique operator IDs
     */
    public static Integer currentId = 0;

    /**
     * Iterator over the current glob results
     */
    private Iterator<String> iterator;

    /**
     * The current pattern being processed
     */
    private String currentPattern;

    /**
     * Flag indicating if the iterator has been exhausted
     */
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
     * @param id         the unique identifier for this glob operator instance
     * @param patternArg the glob pattern as a RuntimeScalar
     * @param ctx        the runtime context (scalar or list)
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
     * Performs glob matching for a single pattern.
     *
     * @param scalarGlobOperator
     * @param pattern            the glob pattern to match
     * @return list of matching file paths
     */
    static List<String> globSinglePattern(ScalarGlobOperator scalarGlobOperator, String pattern) {
        List<String> results = new ArrayList<>();

        try {
            // Preserve the original pattern format for result formatting
            String originalPattern = pattern;

            // Normalize path separators for Windows compatibility
            String normalizedPattern = ScalarGlobOperatorHelper.normalizePathSeparators(pattern);

            // Check if pattern is absolute
            boolean patternIsAbsolute = ScalarGlobOperatorHelper.isAbsolutePath(originalPattern);

            // Extract directory and file pattern
            PathComponents components = scalarGlobOperator.extractPathComponents(normalizedPattern, patternIsAbsolute);

            if (!components.baseDir.exists() || components.filePattern.isEmpty()) {
                // For non-existent paths or empty patterns, return literal if no glob chars
                if (!ScalarGlobOperatorHelper.containsGlobChars(pattern)) {
                    results.add(pattern);
                }
                return results;
            }

            // Convert glob pattern to regex
            Pattern regex = ScalarGlobOperatorHelper.globToRegex(scalarGlobOperator, components.filePattern);
            if (regex == null) {
                return results;
            }

            // Match files against pattern
            scalarGlobOperator.matchFiles(components, regex, results, originalPattern, patternIsAbsolute);

            // For exact matches that don't exist (from brace expansion)
            if (results.isEmpty() && !ScalarGlobOperatorHelper.containsGlobChars(pattern)) {
                results.add(pattern);
            }
        } catch (Exception e) {
            // Return empty results on error
        }

        return results;
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

        List<String> results = ScalarGlobOperatorHelper.processPattern(this, pattern);

        // Sort results and remove duplicates
        results = new ArrayList<>(new TreeSet<>(results));
        this.iterator = results.iterator();
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
                // Windows - convert the directory part back to native backslashes
                String nativeDir = components.directoryPart.replace('/', '\\');
                return nativeDir + '\\' + fileName;
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
     * Holds path components after parsing.
     */
    private record PathComponents(File baseDir, String filePattern, boolean hasDirectory, String directoryPart) {
    }

}

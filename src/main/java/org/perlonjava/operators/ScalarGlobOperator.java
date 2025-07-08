package org.perlonjava.operators;

import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.RuntimeDataProvider;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.io.IOException;
import java.nio.file.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

public class ScalarGlobOperator {

    // Map to store the state (glob iterator) for each operator instance
    public static final Map<Integer, ScalarGlobOperator> globOperators = new HashMap<>();
    public static Integer currentId = 0;

    private Iterator<Path> iterator;
    private String currentPattern;

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
            }

            if (globOperator.iterator.hasNext()) {
                return new RuntimeScalar(globOperator.iterator.next().toString());
            }
            return scalarUndef;
        } else {
            RuntimeList resultList = new RuntimeList();
            try {
                ScalarGlobOperator globOperator = new ScalarGlobOperator(pattern);
                globOperator.iterator.forEachRemaining(path -> resultList.elements.add(new RuntimeScalar(path.toString())));
            } catch (IOException e) {
                getGlobalVariable("main::!").set("Glob operation failed: " + e.getMessage());
            }
            return resultList;
        }
    }

    private void initializeIterator(String pattern) throws IOException {
        // Handle empty pattern
        if (pattern.isEmpty()) {
            this.iterator = Collections.emptyIterator();
            return;
        }

        // Separate directory path from glob pattern
        int lastSlash = pattern.lastIndexOf('/');
        Path targetDir;
        String globPattern;

        if (lastSlash >= 0) {
            // Pattern includes directory path
            String dirPath = pattern.substring(0, lastSlash);
            targetDir = Paths.get(dirPath.isEmpty() ? "/" : dirPath);
            globPattern = pattern.substring(lastSlash + 1);
        } else {
            // No directory separator, use current directory
            targetDir = Paths.get(".");
            globPattern = pattern;
        }

        // Check if directory exists
        if (!Files.exists(targetDir) || !Files.isDirectory(targetDir)) {
            this.iterator = Collections.emptyIterator();
            return;
        }

        // Handle simple wildcards non-recursively (matching Perl's behavior)
        if (!globPattern.contains("**")) {
            // Use Files.list() for non-recursive listing
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);
            this.iterator = Files.list(targetDir)
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        // Exclude hidden files unless pattern explicitly includes them
                        if (!globPattern.startsWith(".") && fileName.startsWith(".")) {
                            return false;
                        }
                        return matcher.matches(path.getFileName());
                    })
                    .sorted()
                    .iterator();;
        } else {
            // Handle recursive patterns with Files.walk()
            // (This would be for extended glob patterns if supported)
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);
            this.iterator = Files.walk(targetDir)
                    .filter(path -> matcher.matches(targetDir.relativize(path)))
                    .sorted()
                    .iterator();
        }
    }
}
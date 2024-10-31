package org.perlonjava.operators;

import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.RuntimeDataProvider;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static org.perlonjava.runtime.GlobalContext.getGlobalVariable;
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
        Path currentDir = Paths.get("").toAbsolutePath();
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        this.iterator = Files.walk(currentDir)
                .filter(path -> matcher.matches(currentDir.relativize(path)))
                .map(currentDir::relativize)
                .iterator();
    }
}
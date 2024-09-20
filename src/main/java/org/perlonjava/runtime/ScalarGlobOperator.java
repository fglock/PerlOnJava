package org.perlonjava.runtime;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

public class ScalarGlobOperator {

    // Map to store the state (glob iterator) for each operator instance
    public static final Map<Integer, ScalarGlobOperator> globOperators = new HashMap<>();
    public static Integer currentId = 0;

    private final Iterator<Path> iterator;

    public ScalarGlobOperator(String pattern) throws IOException {
        Path currentDir = Paths.get("").toAbsolutePath();
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        this.iterator = Files.walk(currentDir)
                .filter(path -> matcher.matches(currentDir.relativize(path)))
                .map(currentDir::relativize)
                .iterator();
    }

    public static RuntimeScalar evaluate(int id) {
        ScalarGlobOperator globOperator = globOperators.get(id);
        if (globOperator != null && globOperator.iterator.hasNext()) {
            return new RuntimeScalar(globOperator.iterator.next().toString());
        }
        return scalarUndef;
    }
}

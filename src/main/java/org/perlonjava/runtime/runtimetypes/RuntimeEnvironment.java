package org.perlonjava.runtime.runtimetypes;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Runtime-owned process-like environment that Perl may change independently. */
public final class RuntimeEnvironment {
    private RuntimeEnvironment() {
    }

    public static String currentDirectory() {
        return PerlRuntime.current().currentDirectory;
    }

    public static void setCurrentDirectory(String directory) {
        PerlRuntime.current().currentDirectory = Paths.get(directory).toAbsolutePath().normalize().toString();
    }

    public static Path resolve(String path) {
        Path candidate = Paths.get(path);
        return candidate.isAbsolute() ? candidate : Paths.get(currentDirectory()).resolve(candidate);
    }

    public static long pid() {
        return PerlRuntime.current().pid;
    }
}

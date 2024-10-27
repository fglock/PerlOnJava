package org.perlonjava.operators;

import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeIO;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.perlonjava.runtime.GlobalContext.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarTrue;

public class Directory {
    public static RuntimeScalar rmdir(RuntimeScalar runtimeScalar) {
        String dirName = runtimeScalar.value.toString();

        try {
            Path path = Paths.get(dirName);
            Files.delete(path);
            return scalarTrue;
        } catch (IOException e) {
            // Set $! (errno) in case of failure
            getGlobalVariable("main::!").set(e.getMessage());
            return scalarFalse;
        }
    }

    public static RuntimeScalar closedir(RuntimeScalar runtimeScalar) {
        if (runtimeScalar.type != RuntimeScalarType.GLOB) {
            throw new PerlCompilerException("Invalid directory handle");
        }

        RuntimeIO dirIO = (RuntimeIO) runtimeScalar.value;
        return dirIO.closedir();
    }

    public static RuntimeScalar rewinddir(RuntimeScalar runtimeScalar) {
        if (runtimeScalar.type != RuntimeScalarType.GLOB) {
            throw new PerlCompilerException("Invalid directory handle");
        }

        RuntimeIO dirIO = (RuntimeIO) runtimeScalar.value;
        dirIO.rewinddir();
        return scalarTrue;
    }

    public static RuntimeScalar telldir(RuntimeScalar runtimeScalar) {
        if (runtimeScalar.type != RuntimeScalarType.GLOB) {
            throw new PerlCompilerException("Invalid directory handle");
        }

        RuntimeIO dirIO = (RuntimeIO) runtimeScalar.value;
        return new RuntimeScalar(dirIO.telldir());
    }
}

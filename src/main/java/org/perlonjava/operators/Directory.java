package org.perlonjava.operators;

import org.perlonjava.runtime.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
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

    public static RuntimeScalar opendir(RuntimeList args) {
        return RuntimeIO.openDir(args);
    }

    public static Set<PosixFilePermission> getPosixFilePermissions(int mode) {
        Set<PosixFilePermission> permissions = new HashSet<>();

        // Owner permissions
        if ((mode & 0400) != 0) permissions.add(PosixFilePermission.OWNER_READ);
        if ((mode & 0200) != 0) permissions.add(PosixFilePermission.OWNER_WRITE);
        if ((mode & 0100) != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE);

        // Group permissions
        if ((mode & 0040) != 0) permissions.add(PosixFilePermission.GROUP_READ);
        if ((mode & 0020) != 0) permissions.add(PosixFilePermission.GROUP_WRITE);
        if ((mode & 0010) != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE);

        // Others permissions
        if ((mode & 0004) != 0) permissions.add(PosixFilePermission.OTHERS_READ);
        if ((mode & 0002) != 0) permissions.add(PosixFilePermission.OTHERS_WRITE);
        if ((mode & 0001) != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE);

        return permissions;
    }

    public static RuntimeDataProvider readdir(RuntimeScalar dirHandle, int ctx) {
        return RuntimeIO.readdir(dirHandle, ctx);
    }

    public static RuntimeScalar seekdir(RuntimeScalar dirHandle, RuntimeScalar position) {
        if (dirHandle.type != RuntimeScalarType.GLOB) {
            throw new RuntimeException("Invalid directory handle");
        }

        RuntimeIO dirIO = (RuntimeIO) dirHandle.value;
        dirIO.seekdir(position.getInt());
        return scalarTrue;
    }

    public static RuntimeScalar mkdir(RuntimeList args) {
        String fileName;
        int mode;

        if (args.elements.isEmpty()) {
            // If no arguments are provided, use $_
            fileName = getGlobalVariable("main::_").toString();
            mode = 0777;
        } else if (args.elements.size() == 1) {
            // If only filename is provided
            fileName = args.elements.getFirst().toString();
            mode = 0777;
        } else {
            // If both filename and mode are provided
            fileName = args.elements.get(0).toString();
            mode = ((RuntimeScalar) args.elements.get(1)).getInt();
        }

        // Remove trailing slashes
        fileName = fileName.replaceAll("/+$", "");

        try {
            Path path = Paths.get(fileName);
            Files.createDirectories(path);

            // Set permissions
            Set<PosixFilePermission> permissions = getPosixFilePermissions(mode);
            Files.setPosixFilePermissions(path, permissions);

            return scalarTrue;
        } catch (IOException e) {
            // Set $! (errno) in case of failure
            getGlobalVariable("main::!").set(e.getMessage());
            return scalarFalse;
        }
    }
}

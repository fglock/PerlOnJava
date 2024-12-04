package org.perlonjava.operators;

import org.perlonjava.runtime.*;

import java.io.File;
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
    public static RuntimeScalar chdir(RuntimeScalar runtimeScalar) {
        //    chdir EXPR
        //    chdir FILEHANDLE
        //    chdir DIRHANDLE
        //    chdir   Changes the working directory to EXPR, if possible. If EXPR is
        //            omitted, changes to the directory specified by $ENV{HOME}, if
        //            set; if not, changes to the directory specified by $ENV{LOGDIR}.
        //            (Under VMS, the variable $ENV{'SYS$LOGIN'} is also checked, and
        //            used if it is set.) If neither is set, "chdir" does nothing and
        //            fails. It returns true on success, false otherwise. See the
        //            example under "die".
        //
        //            On systems that support fchdir(2), you may pass a filehandle or
        //            directory handle as the argument. On systems that don't support
        //            fchdir(2), passing handles raises an exception.

        String dirName = runtimeScalar.toString();
        File newDir = new File(dirName);

        // Resolve the directory to an absolute path
        File absoluteDir = newDir.isAbsolute() ? newDir : new File(System.getProperty("user.dir"), dirName);

        if (absoluteDir.exists() && absoluteDir.isDirectory()) {
            System.setProperty("user.dir", absoluteDir.getAbsolutePath());
            return scalarTrue;
        } else {
            getGlobalVariable("main::!").set("chdir failed: No such directory '" + dirName + "'");
            return scalarFalse;
        }
    }

    public static RuntimeScalar rmdir(RuntimeScalar runtimeScalar) {
        String dirName = runtimeScalar.toString();

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
        RuntimeIO dirIO = runtimeScalar.getRuntimeIO();
        if (dirIO.directoryIO != null) {
            dirIO.directoryIO.closedir();
            dirIO.directoryIO = null;
            return scalarTrue;
        }
        return scalarFalse; // Not a directory handle
    }

    public static RuntimeScalar rewinddir(RuntimeScalar runtimeScalar) {
        RuntimeIO dirIO = runtimeScalar.getRuntimeIO();
        if (dirIO.directoryIO == null) {
            return RuntimeIO.handleIOError("seekdir is not supported for non-directory streams");
        } else {
            return dirIO.directoryIO.seekdir(1);
        }
    }

    public static RuntimeScalar telldir(RuntimeScalar runtimeScalar) {
        RuntimeIO dirIO = runtimeScalar.getRuntimeIO();
        if (dirIO.directoryIO == null) {
            return RuntimeIO.handleIOError("telldir is not supported for non-directory streams");
        }
        return dirIO.directoryIO.telldir();
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
        RuntimeIO runtimeIO = dirHandle.getRuntimeIO();
        if (runtimeIO.directoryIO != null) {
            return runtimeIO.directoryIO.readdir(ctx);
        }
        return scalarFalse;
    }

    public static RuntimeScalar seekdir(RuntimeList args) {
        if (args.elements.size() != 2) {
            throw new PerlCompilerException("Invalid arguments for seekdir");
        }
        RuntimeScalar dirHandle = (RuntimeScalar) args.elements.getFirst();
        RuntimeScalar position = (RuntimeScalar) args.elements.getLast();

        if (dirHandle.type != RuntimeScalarType.GLOB) {
            throw new RuntimeException("Invalid directory handle");
        }

        RuntimeIO dirIO = (RuntimeIO) dirHandle.value;
        int position1 = position.getInt();
        if (dirIO.directoryIO == null) {
            RuntimeIO.handleIOError("seekdir is not supported for non-directory streams");
        } else {
            dirIO.directoryIO.seekdir(position1);
        }
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

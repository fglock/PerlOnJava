package org.perlonjava.operators;

import org.perlonjava.io.DirectoryIO;
import org.perlonjava.runtime.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeIO.handleIOException;
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

        String dirName;
        
        // Check if argument is a filehandle or dirhandle
        if (runtimeScalar.value instanceof RuntimeIO || runtimeScalar.value instanceof RuntimeGlob) {
            // Try to get RuntimeIO from the scalar
            RuntimeIO io = RuntimeIO.getRuntimeIO(runtimeScalar);
            if (io != null) {
                // This is a filehandle or dirhandle - fchdir is not supported
                throw new PerlCompilerException("The fchdir function is unimplemented");
            }
        }
        
        // Handle chdir() with no arguments - check environment variables
        if (!runtimeScalar.defined().getBoolean()) {
            // Try HOME, then LOGDIR, then SYS$LOGIN (for VMS only)
            RuntimeHash envHash = GlobalVariable.getGlobalHash("main::ENV");
            RuntimeScalar homeDir = envHash.get("HOME");
            if (homeDir != null && homeDir.defined().getBoolean() && !homeDir.toString().isEmpty()) {
                dirName = homeDir.toString();
            } else {
                RuntimeScalar logDir = envHash.get("LOGDIR");
                if (logDir != null && logDir.defined().getBoolean() && !logDir.toString().isEmpty()) {
                    dirName = logDir.toString();
                } else {
                    // Check SYS$LOGIN only on VMS
                    String osName = GlobalVariable.getGlobalVariable("main::^O").toString();
                    if ("VMS".equalsIgnoreCase(osName)) {
                        RuntimeScalar sysLogin = envHash.get("SYS$LOGIN");
                        if (sysLogin != null && sysLogin.defined().getBoolean() && !sysLogin.toString().isEmpty()) {
                            dirName = sysLogin.toString();
                        } else {
                            // No environment variable set - fail with EINVAL
                            getGlobalVariable("main::!").set(22);  // EINVAL
                            return scalarFalse;
                        }
                    } else {
                        // Not VMS and no HOME/LOGDIR - fail with EINVAL
                        getGlobalVariable("main::!").set(22);  // EINVAL
                        return scalarFalse;
                    }
                }
            }
        } else {
            dirName = runtimeScalar.toString();
        }
        
        // Check for empty string - should fail with ENOENT
        if (dirName.isEmpty()) {
            getGlobalVariable("main::!").set(2);  // ENOENT
            return scalarFalse;
        }
        
        File absoluteDir = RuntimeIO.resolveFile(dirName);

        if (absoluteDir.exists() && absoluteDir.isDirectory()) {
            System.setProperty("user.dir", absoluteDir.getAbsolutePath());
            return scalarTrue;
        } else {
            // Set errno to ENOENT (No such file or directory)
            getGlobalVariable("main::!").set(2);  // ENOENT
            return scalarFalse;
        }
    }

    public static RuntimeScalar rmdir(RuntimeScalar runtimeScalar) {
        String dirName = runtimeScalar.toString();

        try {
            Path path = RuntimeIO.resolvePath(dirName);
            Files.delete(path);
            return scalarTrue;
        } catch (IOException e) {
            // Set $! (errno) in case of failure
            getGlobalVariable("main::!").set(e.getMessage());
            return scalarFalse;
        }
    }

    public static RuntimeScalar opendir(RuntimeList args) {
        RuntimeScalar dirHandle = (RuntimeScalar) args.elements.get(0);
        String dirPath = args.elements.get(1).toString();

        try {
            // Close existing directory stream if present
            if ((dirHandle.type == RuntimeScalarType.GLOB || dirHandle.type == RuntimeScalarType.GLOBREFERENCE)
                    && dirHandle.value instanceof RuntimeGlob glob) {
                RuntimeIO existingIO = glob.getRuntimeIO();
                if (existingIO != null && existingIO.directoryIO != null) {
                    if (existingIO.directoryIO.directoryStream != null) {
                        existingIO.directoryIO.directoryStream.close();
                    }
                }
            }

            Path fullDirPath = RuntimeIO.resolvePath(dirPath);
            DirectoryStream<Path> stream = Files.newDirectoryStream(fullDirPath);
            DirectoryIO dirIO = new DirectoryIO(stream, dirPath);

            if ((dirHandle.type == RuntimeScalarType.GLOB || dirHandle.type == RuntimeScalarType.GLOBREFERENCE) && dirHandle.value instanceof RuntimeGlob glob) {
                glob.setIO(new RuntimeIO(dirIO));
            } else {
                dirHandle.type = RuntimeScalarType.GLOBREFERENCE;
                dirHandle.value = new RuntimeGlob(null).setIO(new RuntimeIO(dirIO));
            }

            return scalarTrue;
        } catch (IOException e) {
            handleIOException(e, "Directory operation failed");
            return scalarFalse;
        }
    }

    public static RuntimeScalar closedir(RuntimeScalar runtimeScalar) {
        RuntimeIO dirIO = runtimeScalar.getRuntimeIO();
        if (dirIO.directoryIO != null) {
            try {
                if (dirIO.directoryIO.directoryStream != null) {
                    dirIO.directoryIO.directoryStream.close();
                    dirIO.directoryIO.directoryStream = null;
                }
            } catch (IOException e) {
                handleIOException(e, "Directory operation failed");
            }
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
            return dirIO.directoryIO.seekdir(0);
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

    public static RuntimeBase readdir(RuntimeScalar dirHandle, int ctx) {
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
        RuntimeScalar dirHandle = args.getFirst();
        RuntimeScalar position = (RuntimeScalar) args.elements.getLast();

        RuntimeIO dirIO = dirHandle.getRuntimeIO();
        int position1 = position.getInt();
        if (dirIO.directoryIO == null) {
            RuntimeIO.handleIOError("seekdir() attempted on handle opened with open");
            return scalarFalse;  // Return false, not true
        } else {
            dirIO.directoryIO.seekdir(position1);
            return scalarTrue;
        }
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
            Path path = RuntimeIO.resolvePath(fileName);
            Files.createDirectories(path);

            // Set permissions only if the file system supports POSIX permissions
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Set<PosixFilePermission> permissions = getPosixFilePermissions(mode);
                Files.setPosixFilePermissions(path, permissions);
            }
            // On Windows and other non-POSIX systems, permissions are handled by the OS

            return scalarTrue;
        } catch (IOException e) {
            // Set $! (errno) in case of failure
            getGlobalVariable("main::!").set(e.getMessage());
            return scalarFalse;
        }
    }
}

package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.io.DirectoryIO;
import org.perlonjava.runtime.nativ.NativeUtils;
import org.perlonjava.runtime.perlmodule.Warnings;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeIO.handleIOException;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;
import static org.perlonjava.runtime.operators.UmaskOperator.applyUmask;

public class Directory {

    public static RuntimeScalar chdir(RuntimeScalar runtimeScalar) {
        runtimeScalar = RuntimeScalar.dereferenceAndFetchOnce(runtimeScalar);
        RuntimeScalar.checkTaint(runtimeScalar, "chdir");
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
        //            On systems that support fchdir(2), you may pass a filehandle as
        //            the argument. Directory handles additionally require dirfd(3).

        String dirName;

        // Resolve named as well as lexical filehandles. Bareword handles
        // reach this operator as their string name, while lexical handles
        // carry the RuntimeIO directly.
        RuntimeIO io = RuntimeIO.getRuntimeIO(runtimeScalar);
        boolean handleArgument = runtimeScalar.type == RuntimeScalarType.GLOB
                || runtimeScalar.type == RuntimeScalarType.GLOBREFERENCE
                || runtimeScalar.value instanceof RuntimeGlob;
        if (io != null) {
            String name = filehandleName(runtimeScalar, io);
            if (io.directoryIO != null) {
                if (NativeUtils.IS_WINDOWS) {
                    getGlobalVariable("main::!").set(9);
                    return scalarFalse;
                }
                // DirectoryStream does not expose its native dirfd through the
                // Java API.  PerlOnJava resolves filesystem operations against
                // RuntimeEnvironment's current directory, so use the stable
                // absolute path captured when opendir() opened this handle.
                // This gives directory handles the same chdir behavior for
                // ordinary path operations without reaching into JDK internals.
                Path openedDirectory = io.directoryIO.getAbsoluteDirectoryPath();
                if (openedDirectory == null || !Files.isDirectory(openedDirectory)) {
                    getGlobalVariable("main::!").set(9);
                    return scalarFalse;
                }
                try {
                    RuntimeEnvironment.setCurrentDirectory(
                            openedDirectory.toFile().getCanonicalPath());
                    return scalarTrue;
                } catch (IOException e) {
                    handleIOException(e, "chdir failed");
                    return scalarFalse;
                }
            }
            if (io.ioHandle instanceof org.perlonjava.runtime.io.ClosedIOHandle
                    || io.ioHandle == null) {
                getGlobalVariable("main::!").set(9);
                if (IOOperator.unopenedWarningsEnabled()
                        || Warnings.warningManager.isWarningEnabled("unopened")) {
                    String state = io.explicitlyClosed ? "closed" : "unopened";
                    WarnDie.warn(new RuntimeScalar("chdir() on " + state
                            + " filehandle " + name), new RuntimeScalar(""));
                }
                return scalarFalse;
            }
            Path opened = io.openedPath;
            if (opened == null) {
                getGlobalVariable("main::!").set(9);
                return scalarFalse;
            }
            if (!Files.isDirectory(opened)) {
                getGlobalVariable("main::!").set(20); // ENOTDIR
                return scalarFalse;
            }
            try {
                RuntimeEnvironment.setCurrentDirectory(opened.toFile().getCanonicalPath());
                return scalarTrue;
            } catch (IOException e) {
                handleIOException(e, "chdir failed");
                return scalarFalse;
            }
        }
        if (handleArgument) {
            getGlobalVariable("main::!").set(9);
            if (IOOperator.unopenedWarningsEnabled()
                    || Warnings.warningManager.isWarningEnabled("unopened")) {
                WarnDie.warn(new RuntimeScalar("chdir() on unopened filehandle "
                        + filehandleName(runtimeScalar, null)), new RuntimeScalar(""));
            }
            return scalarFalse;
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
            try {
                // Match getcwd(3): collapse . and .., and resolve symlinks like
                // macOS /var -> /private/var after chdir().
                RuntimeEnvironment.setCurrentDirectory(absoluteDir.getCanonicalPath());
            } catch (IOException e) {
                handleIOException(e, "chdir failed");
                return scalarFalse;
            }
            return scalarTrue;
        } else {
            // Set errno to ENOENT (No such file or directory)
            getGlobalVariable("main::!").set(2);  // ENOENT
            return scalarFalse;
        }
    }

    private static String filehandleName(RuntimeScalar handle, RuntimeIO io) {
        String name = io != null ? io.globName : null;
        if (name == null && handle.value instanceof RuntimeGlob glob) name = glob.globName;
        if (name == null) name = handle.lexicalDisplayName;
        if (name == null || name.isEmpty()) return "$fh";
        int separator = name.lastIndexOf("::");
        return separator >= 0 ? name.substring(separator + 2) : name;
    }

    public static RuntimeScalar rmdir(RuntimeScalar runtimeScalar) {
        RuntimeScalar.checkTaint(runtimeScalar, "rmdir");
        String dirName = runtimeScalar.toString();

        try {
            Path path = RuntimeIO.resolvePath(dirName);
            Files.delete(path);
            return scalarTrue;
        } catch (IOException e) {
            // Preserve errno identity (for example ENOENT) as well as the
            // platform's localized message in $!.
            return handleIOException(e, dirName, 2);
        }
    }

    public static RuntimeScalar opendir(RuntimeList args) {
        RuntimeScalar dirHandle = (RuntimeScalar) args.elements.get(0);
        String dirPath = args.elements.get(1).toString();

        RuntimeIO existingHandle = dirHandle.getRuntimeIO();
        if (existingHandle != null
                && existingHandle.ioHandle != null
                && !(existingHandle.ioHandle instanceof org.perlonjava.runtime.io.ClosedIOHandle)) {
            throw new PerlCompilerException("Cannot open " + filehandleName(dirHandle)
                    + " as a dirhandle: it is already open as a filehandle");
        }

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
                RuntimeScalar directoryGlob = new RuntimeGlob(null)
                        .setIO(new RuntimeIO(dirIO)).createReference();
                dirHandle.set(directoryGlob);
            }

            return scalarTrue;
        } catch (IOException e) {
            handleIOException(e, "Directory operation failed");
            return scalarFalse;
        }
    }

    private static String filehandleName(RuntimeScalar handle) {
        RuntimeIO io = handle.getRuntimeIO();
        String name = io != null ? io.globName : null;
        if (name == null && handle.value instanceof RuntimeGlob glob) {
            name = glob.globName;
        }
        if (name == null) {
            name = handle.lexicalDisplayName;
        }
        if (name == null || name.isEmpty()) {
            return "$fh";
        }
        int separator = name.lastIndexOf("::");
        return separator >= 0 ? name.substring(separator + 2) : name;
    }

    public static RuntimeScalar closedir(RuntimeScalar runtimeScalar) {
        RuntimeIO dirIO = runtimeScalar.getRuntimeIO();
        if (dirIO == null || dirIO.directoryIO == null) {
            getGlobalVariable("main::!").set(9);
            warnIfNotDirectoryHandle(runtimeScalar, "closedir");
            return RuntimeScalarCache.scalarUndef;
        }
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
        warnIfNotDirectoryHandle(runtimeScalar, "closedir");
        return scalarFalse; // Not a directory handle
    }

    public static RuntimeScalar rewinddir(RuntimeScalar runtimeScalar) {
        RuntimeIO dirIO = runtimeScalar.getRuntimeIO();
        if (dirIO.directoryIO == null) {
            if (warnIfNotDirectoryHandle(runtimeScalar, "rewinddir")) {
                return scalarFalse;
            }
            return RuntimeIO.handleIOError("seekdir is not supported for non-directory streams");
        } else {
            return dirIO.directoryIO.seekdir(0);
        }
    }

    public static RuntimeScalar telldir(RuntimeScalar runtimeScalar) {
        RuntimeIO dirIO = runtimeScalar.getRuntimeIO();
        if (dirIO.directoryIO == null) {
            if (warnIfNotDirectoryHandle(runtimeScalar, "telldir")) {
                return scalarFalse;
            }
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
        dirHandle = RuntimeScalar.dereferenceAndFetchOnce(dirHandle);
        RuntimeIO runtimeIO = dirHandle.getRuntimeIO();
        if (runtimeIO != null && runtimeIO.directoryIO != null) {
            return runtimeIO.directoryIO.readdir(ctx);
        }
        warnIfNotDirectoryHandle(dirHandle, "readdir");
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
            if (!warnIfNotDirectoryHandle(dirHandle, "seekdir")) {
                RuntimeIO.handleIOError("seekdir is not supported for non-directory streams");
            }
            return scalarFalse;  // Return false, not true
        } else {
            dirIO.directoryIO.seekdir(position1);
            return scalarTrue;
        }
    }

    private static boolean warnIfNotDirectoryHandle(RuntimeScalar handle, String operation) {
        RuntimeIO io = handle.getRuntimeIO();
        if (io == null || io.ioHandle == null || io.directoryIO != null) {
            return false;
        }
        String name = filehandleName(handle);
        if (!name.startsWith("$")) {
            name = "$" + name;
        }
        WarnDie.warn(new RuntimeScalar(operation + "() attempted on handle " + name
                + " opened with open"), new RuntimeScalar(""));
        return true;
    }

    public static RuntimeScalar mkdir(RuntimeList args) {
        if (!args.elements.isEmpty()) {
            RuntimeScalar.checkTaint(args.elements.getFirst().scalar(), "mkdir");
        } else {
            RuntimeScalar.checkTaint(getGlobalVariable("main::_"), "mkdir");
        }
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
            // Use createDirectory (not createDirectories) so it throws FileAlreadyExistsException
            // when the directory exists. This matches Perl's behavior where mkdir() fails
            // with EEXIST if the directory already exists.
            Files.createDirectory(path);

            // Set permissions only if the file system supports POSIX permissions
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                // Apply umask to the mode (Perl: effective_mode = mode & ~umask)
                int effectiveMode = applyUmask(mode);
                Set<PosixFilePermission> permissions = getPosixFilePermissions(effectiveMode);
                Files.setPosixFilePermissions(path, permissions);
            }
            // On Windows and other non-POSIX systems, permissions are handled by the OS

            return scalarTrue;
        } catch (IOException e) {
            // Set $! (errno) properly using handleIOException which maps
            // FileAlreadyExistsException to EEXIST (17), etc.
            return handleIOException(e, fileName, 0);
        }
    }
}

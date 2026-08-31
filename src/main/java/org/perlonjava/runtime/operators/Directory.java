package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.io.CustomFileChannel;
import org.perlonjava.runtime.io.DirectoryIO;
import org.perlonjava.runtime.io.IOHandle;
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

    /**
     * Implements the {@code chdir FILEHANDLE} / {@code chdir DIRHANDLE} forms.
     *
     * <p>Perl uses {@code fchdir(2)} here, which the JVM does not expose. The
     * equivalent is to recover the pathname the descriptor was opened with and
     * {@code chdir} to that: {@code opendir} keeps the absolute directory path
     * in {@link DirectoryIO}, and {@code open} keeps it in
     * {@link org.perlonjava.runtime.io.CustomFileChannel}.
     *
     * <p>No taint check happens on this path. {@code fchdir} takes a descriptor,
     * not a pathname, so Perl has nothing to taint-check; the pathname recovered
     * here is an implementation detail and must not turn a legal
     * {@code chdir($dh)} into an "Insecure dependency" death under {@code -T}.
     * The pathname form of {@code chdir} is still taint-checked below.
     *
     * @param io the handle the caller passed to chdir
     * @return true on success, false with {@code $!} set on failure
     */
    private static RuntimeScalar fchdir(RuntimeIO io) {
        Path target = null;
        if (io.directoryIO != null) {
            target = io.directoryIO.getAbsoluteDirectoryPath();
        } else {
            IOHandle base = RuntimeIO.baseHandle(io.ioHandle);
            if (base instanceof CustomFileChannel channel) {
                target = channel.getFilePath();
            }
        }

        if (target == null) {
            // Closed handle, or a handle with no recoverable pathname (a pipe,
            // socket or in-memory handle). Perl's fchdir() fails with EBADF.
            getGlobalVariable("main::!").set(RuntimeIO.EBADF);
            return scalarFalse;
        }

        File dir = target.toFile();
        if (!dir.exists()) {
            getGlobalVariable("main::!").set(2);  // ENOENT
            return scalarFalse;
        }
        if (!dir.isDirectory()) {
            // fchdir() on a descriptor that is not a directory: ENOTDIR.
            getGlobalVariable("main::!").set(20);  // ENOTDIR
            return scalarFalse;
        }
        try {
            RuntimeEnvironment.setCurrentDirectory(dir.getCanonicalPath());
        } catch (IOException e) {
            handleIOException(e, "chdir failed");
            return scalarFalse;
        }
        return scalarTrue;
    }

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
            RuntimeIO io = RuntimeIO.getRuntimeIO(runtimeScalar);
            if (io != null) {
                return fchdir(io);
            }
        }

        RuntimeScalar.checkTaint(runtimeScalar, "chdir");

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

    public static RuntimeScalar rmdir(RuntimeScalar runtimeScalar) {
        RuntimeScalar.checkTaint(runtimeScalar, "rmdir");
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

    /**
     * Resolves a scalar to the directory handle behind it.
     *
     * <p>Perl dies with "Bad symbol for dirhandle" when the argument cannot be a
     * dirhandle at all (undef, a plain string); it fails the operator with
     * {@code $!} = EBADF when the argument is a handle whose {@code DIRP} is
     * gone — a closed dirhandle, or a filehandle that was never a dirhandle.
     *
     * @param runtimeScalar the operator's dirhandle argument
     * @return the live {@link DirectoryIO}, or null after setting {@code $!} to EBADF
     */
    private static DirectoryIO resolveDirectoryIO(RuntimeScalar runtimeScalar) {
        RuntimeIO dirIO = runtimeScalar.getRuntimeIO();
        if (dirIO == null) {
            throw new PerlCompilerException("Bad symbol for dirhandle");
        }
        if (dirIO.directoryIO == null) {
            getGlobalVariable("main::!").set(RuntimeIO.EBADF);
            return null;
        }
        return dirIO.directoryIO;
    }

    public static RuntimeScalar closedir(RuntimeScalar runtimeScalar) {
        RuntimeIO dirIO = runtimeScalar.getRuntimeIO();
        if (dirIO == null) {
            throw new PerlCompilerException("Bad symbol for dirhandle");
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
        // Not (or no longer) a directory handle: Perl reports EBADF.
        getGlobalVariable("main::!").set(RuntimeIO.EBADF);
        return scalarFalse;
    }

    public static RuntimeScalar rewinddir(RuntimeScalar runtimeScalar) {
        DirectoryIO directoryIO = resolveDirectoryIO(runtimeScalar);
        if (directoryIO == null) {
            return scalarFalse;
        }
        return directoryIO.seekdir(0);
    }

    public static RuntimeScalar telldir(RuntimeScalar runtimeScalar) {
        DirectoryIO directoryIO = resolveDirectoryIO(runtimeScalar);
        if (directoryIO == null) {
            return scalarFalse;
        }
        return directoryIO.telldir();
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
        if (runtimeIO != null && runtimeIO.directoryIO != null) {
            return runtimeIO.directoryIO.readdir(ctx);
        }
        // Invalid or closed dirhandle: Perl reports EBADF.
        getGlobalVariable("main::!").set(RuntimeIO.EBADF);
        return scalarFalse;
    }

    public static RuntimeScalar seekdir(RuntimeList args) {
        if (args.elements.size() != 2) {
            throw new PerlCompilerException("Invalid arguments for seekdir");
        }
        RuntimeScalar dirHandle = args.getFirst();
        RuntimeScalar position = (RuntimeScalar) args.elements.getLast();

        int position1 = position.getInt();
        DirectoryIO directoryIO = resolveDirectoryIO(dirHandle);
        if (directoryIO == null) {
            return scalarFalse;  // Return false, not true
        }
        directoryIO.seekdir(position1);
        return scalarTrue;
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

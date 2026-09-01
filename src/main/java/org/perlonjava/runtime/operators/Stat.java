package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.io.*;
import org.perlonjava.runtime.nativ.NativeUtils;
import org.perlonjava.runtime.nativ.ffm.FFMPosix;
import org.perlonjava.runtime.nativ.ffm.FFMPosixInterface;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.perlonjava.runtime.operators.FileTestOperator.*;
import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeIO.resolvePath;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;

public class Stat {

    // FFM POSIX implementation
    private static final FFMPosixInterface posix = FFMPosix.get();

    private record WindowsMode(Object fileKey, long creationMillis, int mode) {}

    /**
     * Java's DOS attribute view can represent read-only but cannot distinguish
     * Perl modes such as 0400 from 0444.  Keep the exact mode for files whose
     * permissions PerlOnJava changed, fenced by file identity so a later file
     * reusing the same path does not inherit stale metadata.
     */
    private static final ConcurrentHashMap<Path, WindowsMode> WINDOWS_MODES =
            new ConcurrentHashMap<>();

    public static void rememberWindowsMode(Path path, int mode) {
        if (!NativeUtils.IS_WINDOWS || path == null) return;
        try {
            BasicFileAttributes attrs = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            WINDOWS_MODES.put(path.toAbsolutePath().normalize(), new WindowsMode(
                    attrs.fileKey(), attrs.creationTime().toMillis(), mode & 07777));
        } catch (IOException ignored) {
            // The filesystem operation already reports its own success/failure;
            // exact-mode emulation is best effort on non-POSIX filesystems.
        }
    }

    private static Integer rememberedWindowsMode(Path path, BasicFileAttributes attrs) {
        if (!NativeUtils.IS_WINDOWS || path == null || attrs == null) return null;
        Path key = path.toAbsolutePath().normalize();
        WindowsMode remembered = WINDOWS_MODES.get(key);
        if (remembered == null) return null;
        if (!java.util.Objects.equals(remembered.fileKey(), attrs.fileKey())
                || remembered.creationMillis() != attrs.creationTime().toMillis()) {
            WINDOWS_MODES.remove(key, remembered);
            return null;
        }
        return remembered.mode();
    }

    /**
     * Checks if a glob argument is the special underscore glob (*_ or \*_).
     */
    private static boolean isUnderscoreGlob(RuntimeScalar arg) {
        if (arg.value instanceof RuntimeGlob rg) {
            return rg.globName != null && rg.globName.endsWith("::_");
        }
        if (arg.value instanceof RuntimeIO rio) {
            return rio.globName != null && rio.globName.endsWith("::_");
        }
        return false;
    }

    static NativeStatFields nativeStat(String path, boolean followLinks) {
        try {
            if (NativeUtils.IS_WINDOWS) return null;
            FFMPosixInterface.StatResult sr = followLinks
                    ? posix.stat(path)
                    : posix.lstat(path);
            if (sr == null) return null;
            return new NativeStatFields(
                    sr.dev(), sr.ino(), sr.mode(), sr.nlink(),
                    sr.uid(), sr.gid(), sr.rdev(), sr.size(),
                    sr.atime(), sr.mtime(), sr.ctime(),
                    sr.blksize(), sr.blocks()
            );
        } catch (Throwable e) {
            return null;
        }
    }

    private static int getPermissionsOctal(BasicFileAttributes basicAttr, PosixFileAttributes attr) {
        int permissions = 0;
        if (basicAttr.isDirectory()) permissions |= 0040000;
        if (basicAttr.isRegularFile()) permissions |= 0100000;
        if (basicAttr.isSymbolicLink()) permissions |= 0120000;
        Set<PosixFilePermission> perms = attr.permissions();
        if (perms.contains(PosixFilePermission.OWNER_READ)) permissions |= 0400;
        if (perms.contains(PosixFilePermission.OWNER_WRITE)) permissions |= 0200;
        if (perms.contains(PosixFilePermission.OWNER_EXECUTE)) permissions |= 0100;
        if (perms.contains(PosixFilePermission.GROUP_READ)) permissions |= 0040;
        if (perms.contains(PosixFilePermission.GROUP_WRITE)) permissions |= 0020;
        if (perms.contains(PosixFilePermission.GROUP_EXECUTE)) permissions |= 0010;
        if (perms.contains(PosixFilePermission.OTHERS_READ)) permissions |= 0004;
        if (perms.contains(PosixFilePermission.OTHERS_WRITE)) permissions |= 0002;
        if (perms.contains(PosixFilePermission.OTHERS_EXECUTE)) permissions |= 0001;
        return permissions;
    }

    public static RuntimeList statLastHandle() {
        FileTestOperator.State state = state();
        if (!state.lastStatOk) {
            getGlobalVariable("main::!").set(9);
            return new RuntimeList();
        }
        RuntimeList res = new RuntimeList();
        if (state.lastNativeStatFields != null) {
            statInternalNative(res, state.lastNativeStatFields);
        } else {
            statInternal(res, state.lastBasicAttr, state.lastPosixAttr);
        }
        getGlobalVariable("main::!").set(0);
        return res;
    }

    public static RuntimeBase statLastHandle(int ctx) {
        if (ctx == RuntimeContextType.SCALAR) {
            if (!state().lastStatOk) {
                getGlobalVariable("main::!").set(9);
                return new RuntimeScalar("");
            }
            getGlobalVariable("main::!").set(0);
            return scalarTrue;
        }
        return statLastHandle();
    }

    public static RuntimeList lstatLastHandle() {
        FileTestOperator.State state = state();
        if (!state.lastStatWasLstat) {
            throw new PerlCompilerException("The stat preceding lstat() wasn't an lstat");
        }
        if (!state.lastStatOk) {
            getGlobalVariable("main::!").set(9);
            return new RuntimeList();
        }
        RuntimeList res = new RuntimeList();
        if (state.lastNativeStatFields != null) {
            statInternalNative(res, state.lastNativeStatFields);
        } else {
            statInternal(res, state.lastBasicAttr, state.lastPosixAttr);
        }
        getGlobalVariable("main::!").set(0);
        return res;
    }

    public static RuntimeBase lstatLastHandle(int ctx) {
        if (!state().lastStatWasLstat) {
            throw new PerlCompilerException("The stat preceding lstat() wasn't an lstat");
        }
        if (ctx == RuntimeContextType.SCALAR) {
            if (!state().lastStatOk) {
                getGlobalVariable("main::!").set(9);
                return new RuntimeScalar("");
            }
            getGlobalVariable("main::!").set(0);
            return scalarTrue;
        }
        return lstatLastHandle();
    }

    public static RuntimeBase stat(RuntimeScalar arg, int ctx) {
        RuntimeList result = stat(arg);
        if (ctx == RuntimeContextType.SCALAR) {
            return result.isEmpty() ? new RuntimeScalar("") : scalarTrue;
        }
        return result;
    }

    public static RuntimeBase lstat(RuntimeScalar arg, int ctx) {
        RuntimeList result = lstat(arg);
        if (ctx == RuntimeContextType.SCALAR) {
            return result.isEmpty() ? new RuntimeScalar("") : scalarTrue;
        }
        return result;
    }

    public static RuntimeList stat(RuntimeScalar arg) {
        state().lastFileHandle.set(arg);
        RuntimeList res = new RuntimeList();

        if (arg.type == RuntimeScalarType.GLOB || arg.type == RuntimeScalarType.GLOBREFERENCE) {
            RuntimeIO fh = arg.getRuntimeIO();
            if (fh == null) {
                getGlobalVariable("main::!").set(9);
                updateLastStat(arg, false, 9, false);
                return res;
            }
            if ((fh.ioHandle == null || fh.ioHandle instanceof ClosedIOHandle) &&
                    fh.directoryIO == null) {
                getGlobalVariable("main::!").set(9);
                updateLastStat(arg, false, 9, false);
                return res;
            }
            IOHandle innerHandle = fh.ioHandle;
            while (true) {
                if (innerHandle instanceof LayeredIOHandle lh) {
                    innerHandle = lh.getDelegate();
                } else if (innerHandle instanceof DupIOHandle dh) {
                    innerHandle = dh.getDelegate();
                } else if (innerHandle instanceof BorrowedIOHandle bh) {
                    innerHandle = bh.getDelegate();
                } else if (innerHandle instanceof SharedTransportIOHandle sh) {
                    innerHandle = sh.getDelegate();
                } else {
                    break;
                }
            }
            if (innerHandle instanceof CustomFileChannel cfc) {
                FFMPosixInterface.StatResult opened = cfc.getOpenedStat();
                if (opened != null) {
                    try {
                        // Keep the open-time identity when a pathname has been
                        // renamed and replaced, but refresh metadata while it
                        // still names the opened inode.  In particular,
                        // sysopen(..., O_CREAT, PERMS) applies PERMS after it
                        // creates the writable descriptor.
                        Path openedPath = cfc.getFilePath();
                        NativeStatFields openedFields = new NativeStatFields(
                                opened.dev(), opened.ino(), opened.mode(), opened.nlink(),
                                opened.uid(), opened.gid(), opened.rdev(), opened.size(),
                                opened.atime(), opened.mtime(), opened.ctime(),
                                opened.blksize(), opened.blocks());
                        NativeStatFields current = openedPath == null
                                ? null : nativeStat(openedPath.toString(), true);
                        if (current != null && current.dev() == opened.dev()
                                && current.ino() == opened.ino()) {
                            openedFields = current;
                        }
                        NativeStatFields nf = new NativeStatFields(
                                openedFields.dev(), openedFields.ino(), openedFields.mode(), openedFields.nlink(),
                                openedFields.uid(), openedFields.gid(), openedFields.rdev(), cfc.size(),
                                openedFields.atime(), openedFields.mtime(), openedFields.ctime(),
                                openedFields.blksize(), openedFields.blocks());
                        statInternalNative(res, nf);
                        getGlobalVariable("main::!").set(0);
                        updateLastStat(arg, true, 0, false);
                        state().lastNativeStatFields = nf;
                        return res;
                    } catch (IOException e) {
                        getGlobalVariable("main::!").set(5);
                        updateLastStat(arg, false, 5, false);
                        return res;
                    }
                }
                Path path = cfc.getFilePath();
                if (path != null) {
                    return stat(new RuntimeScalar(path.toString()));
                }
            }
            // Check for directory handle
            if (fh.directoryIO != null) {
                Path dirPath = fh.directoryIO.getAbsoluteDirectoryPath();
                if (dirPath != null) {
                    return stat(new RuntimeScalar(dirPath.toString()));
                }
            }
            getGlobalVariable("main::!").set(9);
            updateLastStat(arg, false, 9, false);
            return res;
        }

        String filename = arg.toString();
        try {
            Path path = resolvePath(filename);
            if (path == null) {
                getGlobalVariable("main::!").set(2);
                updateLastStat(arg, false, 2, false);
                return res;
            }

            NativeStatFields nf = nativeStat(path.toString(), true);

            BasicFileAttributes basicAttr = Files.readAttributes(path, BasicFileAttributes.class);
            PosixFileAttributes posixAttr = null;
            try {
                posixAttr = Files.readAttributes(path, PosixFileAttributes.class);
            } catch (UnsupportedOperationException e) {
                // Not available on Windows
            }

            if (nf != null) {
                statInternalNative(res, nf);
            } else if (posixAttr != null) {
                statInternal(res, basicAttr, posixAttr);
            } else {
                statInternalBasic(res, path, basicAttr);
            }

            getGlobalVariable("main::!").set(0);
            updateLastStat(arg, true, 0, false);
            // Set attributes after updateLastStat (which resets them to null)
            FileTestOperator.State state = state();
            state.lastBasicAttr = basicAttr;
            state.lastPosixAttr = posixAttr;
            state.lastNativeStatFields = nf;
        } catch (NoSuchFileException e) {
            getGlobalVariable("main::!").set(2);
            updateLastStat(arg, false, 2, false);
        } catch (IOException e) {
            getGlobalVariable("main::!").set(5);
            updateLastStat(arg, false, 5, false);
        }
        return res;
    }

    public static RuntimeList lstat(RuntimeScalar arg) {
        state().lastFileHandle.set(arg);
        RuntimeList res = new RuntimeList();

        if (arg.type == RuntimeScalarType.GLOB || arg.type == RuntimeScalarType.GLOBREFERENCE) {
            // Check if this is the special underscore glob (*_ or \*_)
            if (isUnderscoreGlob(arg)) {
                // lstat on *_ or \*_ after stat should croak
                if (!state().lastStatWasLstat) {
                    throw new PerlCompilerException("The stat preceding lstat() wasn't an lstat");
                }
                return lstatLastHandle();
            }
            // Perl: lstat on a filehandle reverts to regular stat (fstat)
            return stat(arg);
        }

        String filename = arg.toString();
        try {
            Path path = resolvePath(filename);
            if (path == null) {
                getGlobalVariable("main::!").set(2);
                updateLastStat(arg, false, 2, true);
                return res;
            }

            NativeStatFields nf = nativeStat(path.toString(), false);

            BasicFileAttributes basicAttr = Files.readAttributes(path,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            PosixFileAttributes posixAttr = null;
            try {
                posixAttr = Files.readAttributes(path,
                        PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (UnsupportedOperationException e) {
                // Not available on Windows
            }

            if (nf != null) {
                statInternalNative(res, nf);
            } else if (posixAttr != null) {
                statInternal(res, basicAttr, posixAttr);
            } else {
                statInternalBasic(res, path, basicAttr);
            }

            getGlobalVariable("main::!").set(0);
            updateLastStat(arg, true, 0, true);
            // Set attributes after updateLastStat (which resets them to null)
            FileTestOperator.State state = state();
            state.lastBasicAttr = basicAttr;
            state.lastPosixAttr = posixAttr;
            state.lastNativeStatFields = nf;
        } catch (NoSuchFileException e) {
            getGlobalVariable("main::!").set(2);
            updateLastStat(arg, false, 2, true);
        } catch (IOException e) {
            getGlobalVariable("main::!").set(5);
            updateLastStat(arg, false, 5, true);
        }
        return res;
    }

    private static void statInternalNative(RuntimeList res, NativeStatFields nf) {
        res.add(getScalarInt(nf.dev));
        res.add(getScalarInt(nf.ino));
        res.add(getScalarInt(nf.mode));
        res.add(getScalarInt(nf.nlink));
        res.add(getScalarInt(nf.uid));
        res.add(getScalarInt(nf.gid));
        res.add(getScalarInt(nf.rdev));
        res.add(getScalarInt(nf.size));
        res.add(getScalarInt(nf.atime));
        res.add(getScalarInt(nf.mtime));
        res.add(getScalarInt(nf.ctime));
        res.add(getScalarInt(nf.blksize));
        res.add(getScalarInt(nf.blocks));
    }

    public static void statInternal(RuntimeList res, BasicFileAttributes basicAttr, PosixFileAttributes posixAttr) {
        res.add(scalarUndef);
        res.add(scalarUndef);
        res.add(getScalarInt(getPermissionsOctal(basicAttr, posixAttr)));
        res.add(getScalarInt(1));
        res.add(scalarUndef);
        res.add(scalarUndef);
        res.add(scalarUndef);
        res.add(getScalarInt(basicAttr.size()));
        res.add(getScalarInt(basicAttr.lastAccessTime().toMillis() / 1000));
        res.add(getScalarInt(basicAttr.lastModifiedTime().toMillis() / 1000));
        res.add(getScalarInt(basicAttr.creationTime().toMillis() / 1000));
        res.add(scalarUndef);
        res.add(scalarUndef);
    }

    private static void statInternalBasic(
            RuntimeList res, Path path, BasicFileAttributes basicAttr) {
        int mode = 0;
        Integer rememberedMode = rememberedWindowsMode(path, basicAttr);
        if (basicAttr.isDirectory()) mode = 0040000 | (rememberedMode == null ? 0755 : rememberedMode);
        else if (basicAttr.isRegularFile()) mode = 0100000 | (rememberedMode == null ? 0644 : rememberedMode);
        else if (basicAttr.isSymbolicLink()) mode = 0120000 | (rememberedMode == null ? 0777 : rememberedMode);
        res.add(scalarUndef);
        res.add(scalarUndef);
        res.add(getScalarInt(mode));
        res.add(getScalarInt(1));
        res.add(scalarUndef);
        res.add(scalarUndef);
        res.add(scalarUndef);
        res.add(getScalarInt(basicAttr.size()));
        res.add(getScalarInt(basicAttr.lastAccessTime().toMillis() / 1000));
        res.add(getScalarInt(basicAttr.lastModifiedTime().toMillis() / 1000));
        res.add(getScalarInt(basicAttr.creationTime().toMillis() / 1000));
        res.add(scalarUndef);
        res.add(scalarUndef);
    }

    public record NativeStatFields(
            long dev, long ino, long mode, long nlink,
            long uid, long gid, long rdev, long size,
            long atime, long mtime, long ctime,
            long blksize, long blocks
    ) {
    }
}

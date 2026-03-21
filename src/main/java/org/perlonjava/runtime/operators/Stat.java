package org.perlonjava.runtime.operators;

import jnr.posix.FileStat;
import org.perlonjava.runtime.io.ClosedIOHandle;
import org.perlonjava.runtime.io.CustomFileChannel;
import org.perlonjava.runtime.io.IOHandle;
import org.perlonjava.runtime.io.LayeredIOHandle;
import org.perlonjava.runtime.nativ.NativeUtils;
import org.perlonjava.runtime.nativ.PosixLibrary;
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

import static org.perlonjava.runtime.operators.FileTestOperator.*;
import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeIO.resolvePath;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;

public class Stat {

    static NativeStatFields lastNativeStatFields;

    static NativeStatFields nativeStat(String path, boolean followLinks) {
        try {
            if (NativeUtils.IS_WINDOWS) return null;
            FileStat fs = followLinks
                    ? PosixLibrary.INSTANCE.stat(path)
                    : PosixLibrary.INSTANCE.lstat(path);
            if (fs == null) return null;
            return new NativeStatFields(
                    fs.dev(), fs.ino(), fs.mode(), fs.nlink(),
                    fs.uid(), fs.gid(), fs.rdev(), fs.st_size(),
                    fs.atime(), fs.mtime(), fs.ctime(),
                    fs.blockSize(), fs.blocks()
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
        if (!lastStatOk) {
            getGlobalVariable("main::!").set(9);
            return new RuntimeList();
        }
        RuntimeList res = new RuntimeList();
        if (lastNativeStatFields != null) {
            statInternalNative(res, lastNativeStatFields);
        } else {
            statInternal(res, lastBasicAttr, lastPosixAttr);
        }
        getGlobalVariable("main::!").set(0);
        return res;
    }

    public static RuntimeBase statLastHandle(int ctx) {
        if (ctx == RuntimeContextType.SCALAR) {
            if (!lastStatOk) {
                getGlobalVariable("main::!").set(9);
                return new RuntimeScalar("");
            }
            getGlobalVariable("main::!").set(0);
            return scalarTrue;
        }
        return statLastHandle();
    }

    public static RuntimeList lstatLastHandle() {
        if (!lastStatOk) {
            getGlobalVariable("main::!").set(9);
            return new RuntimeList();
        }
        RuntimeList res = new RuntimeList();
        if (lastNativeStatFields != null) {
            statInternalNative(res, lastNativeStatFields);
        } else {
            statInternal(res, lastBasicAttr, lastPosixAttr);
        }
        getGlobalVariable("main::!").set(0);
        return res;
    }

    public static RuntimeBase lstatLastHandle(int ctx) {
        if (ctx == RuntimeContextType.SCALAR) {
            if (!lastStatOk) {
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
        lastFileHandle.set(arg);
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
            while (innerHandle instanceof LayeredIOHandle lh) {
                innerHandle = lh.getDelegate();
            }
            if (innerHandle instanceof CustomFileChannel cfc) {
                Path path = cfc.getFilePath();
                if (path != null) {
                    return stat(new RuntimeScalar(path.toString()));
                }
            }
            getGlobalVariable("main::!").set(9);
            updateLastStat(arg, false, 9, false);
            return res;
        }

        String filename = arg.toString();
        try {
            Path path = resolvePath(filename);

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
                statInternalBasic(res, basicAttr);
            }

            getGlobalVariable("main::!").set(0);
            updateLastStat(arg, true, 0, false);
            // Set attributes after updateLastStat (which resets them to null)
            lastBasicAttr = basicAttr;
            lastPosixAttr = posixAttr;
            lastNativeStatFields = nf;
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
        lastFileHandle.set(arg);
        RuntimeList res = new RuntimeList();

        if (arg.type == RuntimeScalarType.GLOB || arg.type == RuntimeScalarType.GLOBREFERENCE) {
            RuntimeIO fh = arg.getRuntimeIO();
            if (fh == null) {
                getGlobalVariable("main::!").set(9);
                updateLastStat(arg, false, 9, true);
                return res;
            }
            if ((fh.ioHandle == null || fh.ioHandle instanceof ClosedIOHandle) &&
                    fh.directoryIO == null) {
                getGlobalVariable("main::!").set(9);
                updateLastStat(arg, false, 9, true);
                return res;
            }
            getGlobalVariable("main::!").set(9);
            updateLastStat(arg, false, 9, true);
            return res;
        }

        String filename = arg.toString();
        try {
            Path path = resolvePath(filename);

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
                statInternalBasic(res, basicAttr);
            }

            getGlobalVariable("main::!").set(0);
            updateLastStat(arg, true, 0, true);
            // Set attributes after updateLastStat (which resets them to null)
            lastBasicAttr = basicAttr;
            lastPosixAttr = posixAttr;
            lastNativeStatFields = nf;
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

    private static void statInternalBasic(RuntimeList res, BasicFileAttributes basicAttr) {
        int mode = 0;
        if (basicAttr.isDirectory()) mode = 0040755;
        else if (basicAttr.isRegularFile()) mode = 0100644;
        else if (basicAttr.isSymbolicLink()) mode = 0120777;
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

    private record NativeStatFields(
            long dev, long ino, long mode, long nlink,
            long uid, long gid, long rdev, long size,
            long atime, long mtime, long ctime,
            long blksize, long blocks
    ) {
    }
}

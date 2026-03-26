package org.perlonjava.runtime.nativ.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Linux/macOS implementation of FFM POSIX interface.
 * 
 * <p>This class uses Java's Foreign Function & Memory (FFM) API to call
 * native Linux/glibc functions directly, without JNR-POSIX.</p>
 */
public class FFMPosixLinux implements FFMPosixInterface {
    
    // Platform detection
    private static final boolean IS_MACOS = System.getProperty("os.name").toLowerCase().contains("mac");
    private static final boolean IS_LINUX = System.getProperty("os.name").toLowerCase().contains("linux");
    
    // Thread-local errno storage (used as fallback)
    private static final ThreadLocal<Integer> threadErrno = ThreadLocal.withInitial(() -> 0);
    
    // Lazy-initialized FFM components
    private static volatile boolean initialized = false;
    private static Linker linker;
    private static SymbolLookup stdlib;
    
    // Method handles for simple functions (no errno capture needed)
    private static MethodHandle getuidHandle;
    private static MethodHandle geteuidHandle;
    private static MethodHandle getgidHandle;
    private static MethodHandle getegidHandle;
    private static MethodHandle getppidHandle;
    private static MethodHandle isattyHandle;
    private static MethodHandle umaskHandle;
    
    // Method handles that need errno capture
    private static MethodHandle killHandle;
    private static MethodHandle chmodHandle;
    private static MethodHandle statHandle;
    private static MethodHandle lstatHandle;
    
    // Linker options for errno capture
    private static Linker.Option captureErrno;
    private static long errnoOffset;
    
    // Struct stat size and field offsets (platform-dependent)
    private static int STAT_SIZE;
    private static long ST_DEV_OFFSET;
    private static long ST_INO_OFFSET;
    private static long ST_MODE_OFFSET;
    private static long ST_NLINK_OFFSET;
    private static long ST_UID_OFFSET;
    private static long ST_GID_OFFSET;
    private static long ST_RDEV_OFFSET;
    private static long ST_SIZE_OFFSET;
    private static long ST_BLKSIZE_OFFSET;
    private static long ST_BLOCKS_OFFSET;
    private static long ST_ATIME_OFFSET;
    private static long ST_MTIME_OFFSET;
    private static long ST_CTIME_OFFSET;
    
    /**
     * Initialize FFM components lazily.
     */
    private static synchronized void ensureInitialized() {
        if (initialized) return;
        
        try {
            linker = Linker.nativeLinker();
            stdlib = linker.defaultLookup();
            
            // Set up errno capture
            captureErrno = Linker.Option.captureCallState("errno");
            errnoOffset = Linker.Option.captureStateLayout()
                .byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("errno"));
            
            // Initialize platform-specific struct stat offsets
            initStatOffsets();
            
            // Simple functions (return value only, no errno)
            getuidHandle = linker.downcallHandle(
                stdlib.find("getuid").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT)
            );
            
            geteuidHandle = linker.downcallHandle(
                stdlib.find("geteuid").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT)
            );
            
            getgidHandle = linker.downcallHandle(
                stdlib.find("getgid").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT)
            );
            
            getegidHandle = linker.downcallHandle(
                stdlib.find("getegid").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT)
            );
            
            getppidHandle = linker.downcallHandle(
                stdlib.find("getppid").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT)
            );
            
            isattyHandle = linker.downcallHandle(
                stdlib.find("isatty").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            );
            
            umaskHandle = linker.downcallHandle(
                stdlib.find("umask").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            );
            
            // Functions that need errno capture
            killHandle = linker.downcallHandle(
                stdlib.find("kill").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                captureErrno
            );
            
            chmodHandle = linker.downcallHandle(
                stdlib.find("chmod").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                captureErrno
            );
            
            // stat and lstat - take path pointer and stat buffer pointer
            statHandle = linker.downcallHandle(
                stdlib.find("stat").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                captureErrno
            );
            
            lstatHandle = linker.downcallHandle(
                stdlib.find("lstat").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                captureErrno
            );
            
            initialized = true;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to initialize FFM POSIX bindings", e);
        }
    }
    
    /**
     * Initialize platform-specific struct stat field offsets.
     * 
     * These offsets are determined by the C struct layout on each platform.
     * Linux x86_64 and macOS x86_64/arm64 have different layouts.
     */
    private static void initStatOffsets() {
        if (IS_MACOS) {
            // macOS struct stat layout (64-bit)
            // Based on sys/stat.h from macOS SDK
            STAT_SIZE = 144;
            ST_DEV_OFFSET = 0;       // int32_t st_dev
            ST_MODE_OFFSET = 4;      // mode_t st_mode (uint16_t)
            ST_NLINK_OFFSET = 6;     // nlink_t st_nlink (uint16_t)
            ST_INO_OFFSET = 8;       // ino_t st_ino (uint64_t)
            ST_UID_OFFSET = 16;      // uid_t st_uid
            ST_GID_OFFSET = 20;      // gid_t st_gid
            ST_RDEV_OFFSET = 24;     // dev_t st_rdev
            // struct timespec st_atimespec at offset 32 (16 bytes: sec + nsec)
            ST_ATIME_OFFSET = 32;    // time_t st_atime
            // struct timespec st_mtimespec at offset 48
            ST_MTIME_OFFSET = 48;    // time_t st_mtime
            // struct timespec st_ctimespec at offset 64
            ST_CTIME_OFFSET = 64;    // time_t st_ctime
            // struct timespec st_birthtimespec at offset 80
            ST_SIZE_OFFSET = 96;     // off_t st_size
            ST_BLOCKS_OFFSET = 104;  // blkcnt_t st_blocks
            ST_BLKSIZE_OFFSET = 112; // blksize_t st_blksize
        } else {
            // Linux x86_64 struct stat layout
            // Based on /usr/include/bits/stat.h
            STAT_SIZE = 144;
            ST_DEV_OFFSET = 0;       // dev_t st_dev (8 bytes)
            ST_INO_OFFSET = 8;       // ino_t st_ino (8 bytes)
            ST_NLINK_OFFSET = 16;    // nlink_t st_nlink (8 bytes)
            ST_MODE_OFFSET = 24;     // mode_t st_mode (4 bytes)
            ST_UID_OFFSET = 28;      // uid_t st_uid (4 bytes)
            ST_GID_OFFSET = 32;      // gid_t st_gid (4 bytes)
            // 4 bytes padding
            ST_RDEV_OFFSET = 40;     // dev_t st_rdev (8 bytes)
            ST_SIZE_OFFSET = 48;     // off_t st_size (8 bytes)
            ST_BLKSIZE_OFFSET = 56;  // blksize_t st_blksize (8 bytes)
            ST_BLOCKS_OFFSET = 64;   // blkcnt_t st_blocks (8 bytes)
            // struct timespec st_atim at offset 72 (16 bytes)
            ST_ATIME_OFFSET = 72;
            // struct timespec st_mtim at offset 88
            ST_MTIME_OFFSET = 88;
            // struct timespec st_ctim at offset 104
            ST_CTIME_OFFSET = 104;
        }
    }
    
    // ==================== Process Functions ====================
    
    @Override
    public int kill(int pid, int signal) {
        ensureInitialized();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capturedState = arena.allocate(Linker.Option.captureStateLayout());
            int result = (int) killHandle.invokeExact(capturedState, pid, signal);
            if (result == -1) {
                int err = capturedState.get(ValueLayout.JAVA_INT, errnoOffset);
                setErrno(err);
            }
            return result;
        } catch (Throwable e) {
            setErrno(1); // EPERM
            return -1;
        }
    }
    
    @Override
    public int getppid() {
        ensureInitialized();
        try {
            return (int) getppidHandle.invokeExact();
        } catch (Throwable e) {
            return 1; // Return init's PID as fallback
        }
    }
    
    @Override
    public long waitpid(int pid, int[] status, int options) {
        // TODO: Implement with FFM in Phase 3
        throw new UnsupportedOperationException("FFM waitpid() not yet implemented - use JNR-POSIX");
    }
    
    // ==================== User/Group Functions ====================
    
    @Override
    public int getuid() {
        ensureInitialized();
        try {
            return (int) getuidHandle.invokeExact();
        } catch (Throwable e) {
            return -1;
        }
    }
    
    @Override
    public int geteuid() {
        ensureInitialized();
        try {
            return (int) geteuidHandle.invokeExact();
        } catch (Throwable e) {
            return -1;
        }
    }
    
    @Override
    public int getgid() {
        ensureInitialized();
        try {
            return (int) getgidHandle.invokeExact();
        } catch (Throwable e) {
            return -1;
        }
    }
    
    @Override
    public int getegid() {
        ensureInitialized();
        try {
            return (int) getegidHandle.invokeExact();
        } catch (Throwable e) {
            return -1;
        }
    }
    
    @Override
    public PasswdEntry getpwnam(String name) {
        // TODO: Implement with FFM in Phase 3
        throw new UnsupportedOperationException("FFM getpwnam() not yet implemented - use JNR-POSIX");
    }
    
    @Override
    public PasswdEntry getpwuid(int uid) {
        // TODO: Implement with FFM in Phase 3
        throw new UnsupportedOperationException("FFM getpwuid() not yet implemented - use JNR-POSIX");
    }
    
    @Override
    public PasswdEntry getpwent() {
        // TODO: Implement with FFM in Phase 3
        throw new UnsupportedOperationException("FFM getpwent() not yet implemented - use JNR-POSIX");
    }
    
    @Override
    public void setpwent() {
        // TODO: Implement with FFM in Phase 3
        throw new UnsupportedOperationException("FFM setpwent() not yet implemented - use JNR-POSIX");
    }
    
    @Override
    public void endpwent() {
        // TODO: Implement with FFM in Phase 3
        throw new UnsupportedOperationException("FFM endpwent() not yet implemented - use JNR-POSIX");
    }
    
    // ==================== File Functions ====================
    
    @Override
    public StatResult stat(String path) {
        return statInternal(path, true);
    }
    
    @Override
    public StatResult lstat(String path) {
        return statInternal(path, false);
    }
    
    /**
     * Internal stat/lstat implementation.
     * @param path File path
     * @param followLinks true for stat, false for lstat
     * @return StatResult or null on error
     */
    private StatResult statInternal(String path, boolean followLinks) {
        ensureInitialized();
        try (Arena arena = Arena.ofConfined()) {
            // Allocate path string in native memory
            MemorySegment pathSegment = arena.allocateFrom(path);
            
            // Allocate stat buffer
            MemorySegment statBuf = arena.allocate(STAT_SIZE);
            
            // Allocate errno capture state
            MemorySegment capturedState = arena.allocate(Linker.Option.captureStateLayout());
            
            // Call stat or lstat
            int result;
            if (followLinks) {
                result = (int) statHandle.invokeExact(capturedState, pathSegment, statBuf);
            } else {
                result = (int) lstatHandle.invokeExact(capturedState, pathSegment, statBuf);
            }
            
            if (result == -1) {
                int err = capturedState.get(ValueLayout.JAVA_INT, errnoOffset);
                setErrno(err);
                return null;
            }
            
            // Read struct fields based on platform
            return readStatResult(statBuf);
        } catch (Throwable e) {
            setErrno(5); // EIO
            return null;
        }
    }
    
    /**
     * Read stat struct fields from memory segment.
     */
    private StatResult readStatResult(MemorySegment statBuf) {
        if (IS_MACOS) {
            // macOS: some fields are smaller
            return new StatResult(
                statBuf.get(ValueLayout.JAVA_INT, ST_DEV_OFFSET) & 0xFFFFFFFFL,  // dev (4 bytes)
                statBuf.get(ValueLayout.JAVA_LONG, ST_INO_OFFSET),                 // ino (8 bytes)
                statBuf.get(ValueLayout.JAVA_SHORT, ST_MODE_OFFSET) & 0xFFFF,      // mode (2 bytes)
                statBuf.get(ValueLayout.JAVA_SHORT, ST_NLINK_OFFSET) & 0xFFFFL,    // nlink (2 bytes)
                statBuf.get(ValueLayout.JAVA_INT, ST_UID_OFFSET),                  // uid (4 bytes)
                statBuf.get(ValueLayout.JAVA_INT, ST_GID_OFFSET),                  // gid (4 bytes)
                statBuf.get(ValueLayout.JAVA_INT, ST_RDEV_OFFSET) & 0xFFFFFFFFL,   // rdev (4 bytes)
                statBuf.get(ValueLayout.JAVA_LONG, ST_SIZE_OFFSET),                // size (8 bytes)
                statBuf.get(ValueLayout.JAVA_LONG, ST_ATIME_OFFSET),               // atime (8 bytes, sec part of timespec)
                statBuf.get(ValueLayout.JAVA_LONG, ST_MTIME_OFFSET),               // mtime
                statBuf.get(ValueLayout.JAVA_LONG, ST_CTIME_OFFSET),               // ctime
                statBuf.get(ValueLayout.JAVA_INT, ST_BLKSIZE_OFFSET) & 0xFFFFFFFFL, // blksize (4 bytes)
                statBuf.get(ValueLayout.JAVA_LONG, ST_BLOCKS_OFFSET)               // blocks (8 bytes)
            );
        } else {
            // Linux x86_64: larger field sizes
            return new StatResult(
                statBuf.get(ValueLayout.JAVA_LONG, ST_DEV_OFFSET),                 // dev (8 bytes)
                statBuf.get(ValueLayout.JAVA_LONG, ST_INO_OFFSET),                 // ino (8 bytes)
                statBuf.get(ValueLayout.JAVA_INT, ST_MODE_OFFSET),                 // mode (4 bytes)
                statBuf.get(ValueLayout.JAVA_LONG, ST_NLINK_OFFSET),               // nlink (8 bytes)
                statBuf.get(ValueLayout.JAVA_INT, ST_UID_OFFSET),                  // uid (4 bytes)
                statBuf.get(ValueLayout.JAVA_INT, ST_GID_OFFSET),                  // gid (4 bytes)
                statBuf.get(ValueLayout.JAVA_LONG, ST_RDEV_OFFSET),                // rdev (8 bytes)
                statBuf.get(ValueLayout.JAVA_LONG, ST_SIZE_OFFSET),                // size (8 bytes)
                statBuf.get(ValueLayout.JAVA_LONG, ST_ATIME_OFFSET),               // atime
                statBuf.get(ValueLayout.JAVA_LONG, ST_MTIME_OFFSET),               // mtime
                statBuf.get(ValueLayout.JAVA_LONG, ST_CTIME_OFFSET),               // ctime
                statBuf.get(ValueLayout.JAVA_LONG, ST_BLKSIZE_OFFSET),             // blksize (8 bytes)
                statBuf.get(ValueLayout.JAVA_LONG, ST_BLOCKS_OFFSET)               // blocks (8 bytes)
            );
        }
    }
    
    @Override
    public int chmod(String path, int mode) {
        ensureInitialized();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathSegment = arena.allocateFrom(path);
            MemorySegment capturedState = arena.allocate(Linker.Option.captureStateLayout());
            int result = (int) chmodHandle.invokeExact(capturedState, pathSegment, mode);
            if (result == -1) {
                int err = capturedState.get(ValueLayout.JAVA_INT, errnoOffset);
                setErrno(err);
            }
            return result;
        } catch (Throwable e) {
            setErrno(1); // EPERM
            return -1;
        }
    }
    
    @Override
    public int link(String oldPath, String newPath) {
        // Can use Java NIO for this
        try {
            Files.createLink(Path.of(newPath), Path.of(oldPath));
            return 0;
        } catch (Exception e) {
            setErrno(getErrnoForException(e));
            return -1;
        }
    }
    
    @Override
    public int utimes(String path, long atime, long mtime) {
        // TODO: Implement with FFM in Phase 3
        throw new UnsupportedOperationException("FFM utimes() not yet implemented - use JNR-POSIX");
    }
    
    // ==================== Terminal Functions ====================
    
    @Override
    public int isatty(int fd) {
        ensureInitialized();
        try {
            return (int) isattyHandle.invokeExact(fd);
        } catch (Throwable e) {
            return 0;
        }
    }
    
    // ==================== File Control Functions ====================
    
    @Override
    public int fcntl(int fd, int cmd, int arg) {
        // TODO: Implement with FFM in Phase 3
        throw new UnsupportedOperationException("FFM fcntl() not yet implemented - use JNR-POSIX");
    }
    
    @Override
    public int umask(int mask) {
        ensureInitialized();
        try {
            return (int) umaskHandle.invokeExact(mask);
        } catch (Throwable e) {
            return 022; // Default umask
        }
    }
    
    // ==================== Error Handling ====================
    
    @Override
    public int errno() {
        return threadErrno.get();
    }
    
    @Override
    public void setErrno(int errno) {
        threadErrno.set(errno);
    }
    
    @Override
    public String strerror(int errno) {
        // Common POSIX error messages
        return switch (errno) {
            case 0 -> "Success";
            case 1 -> "Operation not permitted";
            case 2 -> "No such file or directory";
            case 3 -> "No such process";
            case 4 -> "Interrupted system call";
            case 5 -> "I/O error";
            case 9 -> "Bad file descriptor";
            case 10 -> "No child processes";
            case 12 -> "Out of memory";
            case 13 -> "Permission denied";
            case 17 -> "File exists";
            case 20 -> "Not a directory";
            case 21 -> "Is a directory";
            case 22 -> "Invalid argument";
            case 28 -> "No space left on device";
            case 30 -> "Read-only file system";
            default -> "Unknown error " + errno;
        };
    }
    
    // ==================== Helper Methods ====================
    
    /**
     * Map Java exceptions to errno values.
     */
    protected int getErrnoForException(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return 5; // EIO
        msg = msg.toLowerCase();
        
        if (msg.contains("no such file") || msg.contains("not found")) return 2; // ENOENT
        if (msg.contains("permission denied") || msg.contains("access denied")) return 13; // EACCES
        if (msg.contains("file exists")) return 17; // EEXIST
        if (msg.contains("not a directory")) return 20; // ENOTDIR
        if (msg.contains("is a directory")) return 21; // EISDIR
        if (msg.contains("no space")) return 28; // ENOSPC
        if (msg.contains("read-only")) return 30; // EROFS
        
        return 5; // EIO - generic I/O error
    }
}

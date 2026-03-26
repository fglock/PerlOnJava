package org.perlonjava.runtime.nativ.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Linux implementation of FFM POSIX interface.
 * 
 * <p>This class uses Java's Foreign Function & Memory (FFM) API to call
 * native Linux/glibc functions directly, without JNR-POSIX.</p>
 */
public class FFMPosixLinux implements FFMPosixInterface {
    
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
    
    // Linker options for errno capture
    private static Linker.Option captureErrno;
    private static long errnoOffset;
    
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
            
            initialized = true;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to initialize FFM POSIX bindings", e);
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
        // TODO: Implement with FFM in Phase 3
        throw new UnsupportedOperationException("FFM stat() not yet implemented - use JNR-POSIX");
    }
    
    @Override
    public StatResult lstat(String path) {
        // TODO: Implement with FFM in Phase 3
        throw new UnsupportedOperationException("FFM lstat() not yet implemented - use JNR-POSIX");
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

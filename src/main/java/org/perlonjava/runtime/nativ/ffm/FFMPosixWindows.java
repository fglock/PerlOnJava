package org.perlonjava.runtime.nativ.ffm;

import java.io.Console;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;

/**
 * Windows implementation of FFM POSIX interface.
 * 
 * <p>Windows is not POSIX-compliant, so this class provides Windows-specific
 * implementations or emulations for POSIX functions:</p>
 * <ul>
 *   <li>Some functions use Windows API via FFM (kernel32.dll)</li>
 *   <li>Some functions use pure Java alternatives</li>
 *   <li>Some functions return simulated/default values</li>
 *   <li>Some functions are not supported (throw UnsupportedOperationException)</li>
 * </ul>
 * 
 * <p><b>Note:</b> This is a stub implementation for Phase 1. Windows-specific
 * implementations will be added in Phase 4.</p>
 */
public class FFMPosixWindows implements FFMPosixInterface {
    
    // Thread-local errno storage
    private static final ThreadLocal<Integer> threadErrno = ThreadLocal.withInitial(() -> 0);
    
    // Default UID/GID values for Windows (simulated)
    private static final int DEFAULT_UID = 1000;
    private static final int DEFAULT_GID = 1000;
    private static final int ID_RANGE = 65536;
    
    // Cached user info
    private static final int CURRENT_UID;
    private static final int CURRENT_GID;
    private static final String CURRENT_USER;
    
    static {
        CURRENT_USER = System.getProperty("user.name", "user");
        CURRENT_UID = Math.abs(CURRENT_USER.hashCode()) % ID_RANGE;
        
        String computerName = System.getenv("COMPUTERNAME");
        CURRENT_GID = computerName != null ? 
            Math.abs(computerName.hashCode()) % ID_RANGE : DEFAULT_GID;
    }
    
    // Lazy-initialized FFM components for MSVCRT calls
    private static volatile boolean fdOpsInitialized = false;
    private static MethodHandle winPipeHandle;
    private static MethodHandle winDupHandle;
    private static MethodHandle winOpenHandle;
    private static MethodHandle winCloseHandle;
    private static MethodHandle winReadHandle;
    private static MethodHandle winWriteHandle;
    private static MethodHandle winLseekHandle;
    
    /**
     * Initialize FFM bindings for MSVCRT low-level FD operations on Windows.
     */
    private static synchronized void ensureFdOpsInitialized() {
        if (fdOpsInitialized) return;
        
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup ucrt = SymbolLookup.libraryLookup("ucrtbase", Arena.global());
            
            // int _pipe(int *pfds, unsigned int psize, int textmode)
            winPipeHandle = linker.downcallHandle(
                ucrt.find("_pipe").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            );
            
            // int _dup(int fd)
            winDupHandle = linker.downcallHandle(
                ucrt.find("_dup").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            );
            
            // int _open(const char *filename, int oflag, int pmode)
            winOpenHandle = linker.downcallHandle(
                ucrt.find("_open").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            );
            
            // int _close(int fd)
            winCloseHandle = linker.downcallHandle(
                ucrt.find("_close").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            );
            
            // int _read(int fd, void *buffer, unsigned int count)
            winReadHandle = linker.downcallHandle(
                ucrt.find("_read").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
            );
            
            // int _write(int fd, const void *buffer, unsigned int count)
            winWriteHandle = linker.downcallHandle(
                ucrt.find("_write").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
            );
            
            // long _lseek(int fd, long offset, int origin)
            winLseekHandle = linker.downcallHandle(
                ucrt.find("_lseek").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            );
            
            fdOpsInitialized = true;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to initialize Windows FFM MSVCRT bindings", e);
        }
    }
    
    // ==================== Process Functions ====================
    
    @Override
    public int kill(int pid, int signal) {
        // Windows implementation using ProcessHandle
        try {
            if (signal == 0) {
                // Signal 0 = check if process exists
                return ProcessHandle.of(pid).map(ph -> ph.isAlive() ? 0 : -1).orElse(-1);
            }
            
            var proc = ProcessHandle.of(pid);
            if (proc.isEmpty()) {
                setErrno(3); // ESRCH - No such process
                return -1;
            }
            
            boolean destroyed = switch (signal) {
                case 9 -> proc.get().destroyForcibly(); // SIGKILL
                case 2, 3, 15 -> proc.get().destroy();  // SIGINT, SIGQUIT, SIGTERM
                default -> {
                    setErrno(22); // EINVAL - not supported
                    yield false;
                }
            };
            
            return destroyed ? 0 : -1;
        } catch (Exception e) {
            setErrno(1); // EPERM
            return -1;
        }
    }
    
    @Override
    public int getppid() {
        return ProcessHandle.current().parent()
            .map(ph -> (int) ph.pid())
            .orElse(0);
    }
    
    @Override
    public long waitpid(int pid, int[] status, int options) {
        // Windows doesn't have waitpid - use ProcessHandle
        try {
            var proc = ProcessHandle.of(pid);
            if (proc.isEmpty()) {
                setErrno(10); // ECHILD
                return -1;
            }
            
            // Check WNOHANG
            boolean noHang = (options & 1) != 0;
            if (noHang && proc.get().isAlive()) {
                return 0;
            }
            
            proc.get().onExit().join();
            if (status != null && status.length > 0) {
                status[0] = 0; // Exit status not available via ProcessHandle
            }
            return pid;
        } catch (Exception e) {
            setErrno(10); // ECHILD
            return -1;
        }
    }
    
    // ==================== User/Group Functions ====================
    
    @Override
    public int getuid() {
        return CURRENT_UID;
    }
    
    @Override
    public int geteuid() {
        return CURRENT_UID;
    }
    
    @Override
    public int getgid() {
        return CURRENT_GID;
    }
    
    @Override
    public int getegid() {
        return CURRENT_GID;
    }
    
    @Override
    public PasswdEntry getpwnam(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        
        // Return info for current user or simulated info
        if (name.equals(CURRENT_USER)) {
            return new PasswdEntry(
                CURRENT_USER,
                "x",
                CURRENT_UID,
                CURRENT_GID,
                "",
                System.getProperty("user.home", "C:\\Users\\" + CURRENT_USER),
                "cmd.exe",
                0,
                0
            );
        }
        
        // Simulated entry for other users
        int uid = name.equals("Administrator") ? 500 : 1001;
        return new PasswdEntry(
            name,
            "x",
            uid,
            513,
            "",
            "C:\\Users\\" + name,
            "cmd.exe",
            0,
            0
        );
    }
    
    @Override
    public PasswdEntry getpwuid(int uid) {
        if (uid == CURRENT_UID) {
            return getpwnam(CURRENT_USER);
        }
        return null;
    }
    
    @Override
    public PasswdEntry getpwent() {
        // Not supported on Windows
        return null;
    }
    
    @Override
    public void setpwent() {
        // No-op on Windows
    }
    
    @Override
    public void endpwent() {
        // No-op on Windows
    }
    
    // ==================== File Functions ====================
    
    @Override
    public StatResult stat(String path) {
        try {
            Path p = Path.of(path);
            BasicFileAttributes basic = Files.readAttributes(p, BasicFileAttributes.class);
            DosFileAttributes dos = null;
            try {
                dos = Files.readAttributes(p, DosFileAttributes.class);
            } catch (UnsupportedOperationException ignored) {
            }
            
            int mode = calculateMode(basic, dos, p);
            
            return new StatResult(
                0,                                          // dev
                basic.fileKey() != null ? 
                    basic.fileKey().hashCode() : 0,         // ino (simulated)
                mode,                                        // mode
                1,                                          // nlink
                CURRENT_UID,                                // uid
                CURRENT_GID,                                // gid
                0,                                          // rdev
                basic.size(),                               // size
                basic.lastAccessTime().toMillis() / 1000,   // atime
                basic.lastModifiedTime().toMillis() / 1000, // mtime
                basic.creationTime().toMillis() / 1000,     // ctime
                4096,                                       // blksize
                (basic.size() + 511) / 512                  // blocks
            );
        } catch (Exception e) {
            setErrno(getErrnoForException(e));
            return null;
        }
    }
    
    @Override
    public StatResult lstat(String path) {
        // Windows doesn't have symlink distinction in the same way
        return stat(path);
    }
    
    @Override
    public int chmod(String path, int mode) {
        // Windows only supports read-only attribute
        try {
            Path p = Path.of(path);
            boolean readOnly = (mode & 0200) == 0; // No owner write = read-only
            Files.setAttribute(p, "dos:readonly", readOnly);
            return 0;
        } catch (Exception e) {
            setErrno(getErrnoForException(e));
            return -1;
        }
    }
    
    @Override
    public int link(String oldPath, String newPath) {
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
        try {
            Path p = Path.of(path);
            Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(mtime * 1000));
            // Access time not easily settable on Windows via NIO
            return 0;
        } catch (Exception e) {
            setErrno(getErrnoForException(e));
            return -1;
        }
    }
    
    // ==================== Terminal Functions ====================
    
    @Override
    public int isatty(int fd) {
        // Use Java Console detection
        Console console = System.console();
        if (console != null) {
            // If Console exists, stdin/stdout/stderr are likely terminals
            return (fd >= 0 && fd <= 2) ? 1 : 0;
        }
        return 0;
    }
    
    // ==================== PTY/Terminal Functions ====================
    // Pseudo-terminals are not supported on Windows.
    // The Perl shim checks $^O and dies before reaching these.
    
    private static final String PTY_UNSUPPORTED = "Pseudo-terminals are not supported on Windows";
    
    @Override
    public int posix_openpt(int flags) {
        throw new UnsupportedOperationException(PTY_UNSUPPORTED);
    }
    
    @Override
    public int grantpt(int masterFd) {
        throw new UnsupportedOperationException(PTY_UNSUPPORTED);
    }
    
    @Override
    public int unlockpt(int masterFd) {
        throw new UnsupportedOperationException(PTY_UNSUPPORTED);
    }
    
    @Override
    public String ptsname(int masterFd) {
        throw new UnsupportedOperationException(PTY_UNSUPPORTED);
    }
    
    @Override
    public int setsid() {
        throw new UnsupportedOperationException("setsid is not supported on Windows");
    }
    
    @Override
    public String ttyname(int fd) {
        return null;  // No tty device names on Windows
    }
    
    @Override
    public int nativeOpen(String path, int flags) {
        throw new UnsupportedOperationException("nativeOpen is not supported on Windows");
    }
    
    @Override
    public int nativeClose(int fd) {
        throw new UnsupportedOperationException("nativeClose is not supported on Windows");
    }
    
    @Override
    public int nativeRead(int fd, byte[] buf, int count) {
        throw new UnsupportedOperationException("nativeRead is not supported on Windows");
    }
    
    @Override
    public int nativeWrite(int fd, byte[] buf, int count) {
        throw new UnsupportedOperationException("nativeWrite is not supported on Windows");
    }
    
    @Override
    public int nativeDup(int fd) {
        throw new UnsupportedOperationException("nativeDup is not supported on Windows");
    }
    
    @Override
    public int fcntlDupFd(int fd, int minFd) {
        throw new UnsupportedOperationException("fcntlDupFd is not supported on Windows");
    }
    
    @Override
    public int ioctlWithPointer(int fd, long request, byte[] buf) {
        throw new UnsupportedOperationException("ioctl is not supported on Windows");
    }
    
    @Override
    public int ioctlWithInt(int fd, long request, int arg) {
        throw new UnsupportedOperationException("ioctl is not supported on Windows");
    }
    
    @Override
    public int tcgetattr(int fd, byte[] termios) {
        throw new UnsupportedOperationException("tcgetattr is not supported on Windows");
    }
    
    @Override
    public int tcsetattr(int fd, int optionalActions, byte[] termios) {
        throw new UnsupportedOperationException("tcsetattr is not supported on Windows");
    }
    
    // ==================== Low-level FD Functions ====================
    
    @Override
    public int pipe(int[] fds) {
        ensureFdOpsInitialized();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pipeBuf = arena.allocate(ValueLayout.JAVA_INT, 2);
            // _pipe(pfds, 4096, _O_BINARY) - 0x8000 is _O_BINARY on Windows
            int result = (int) winPipeHandle.invoke(pipeBuf, 4096, 0x8000);
            if (result == -1) {
                setErrno(24); // EMFILE
                return -1;
            }
            fds[0] = pipeBuf.getAtIndex(ValueLayout.JAVA_INT, 0);
            fds[1] = pipeBuf.getAtIndex(ValueLayout.JAVA_INT, 1);
            return 0;
        } catch (Throwable e) {
            setErrno(24); // EMFILE
            return -1;
        }
    }
    
    @Override
    public int dup(int fd) {
        ensureFdOpsInitialized();
        try {
            int result = (int) winDupHandle.invoke(fd);
            if (result == -1) {
                setErrno(9); // EBADF
            }
            return result;
        } catch (Throwable e) {
            setErrno(9); // EBADF
            return -1;
        }
    }
    
    @Override
    public int open(String path, int flags, int mode) {
        ensureFdOpsInitialized();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathSegment = arena.allocateFrom(path);
            // Add _O_BINARY flag (0x8000) to prevent text-mode translation
            int result = (int) winOpenHandle.invoke(pathSegment, flags | 0x8000, mode);
            if (result == -1) {
                setErrno(2); // ENOENT
            }
            return result;
        } catch (Throwable e) {
            setErrno(5); // EIO
            return -1;
        }
    }
    
    @Override
    public int close(int fd) {
        ensureFdOpsInitialized();
        try {
            int result = (int) winCloseHandle.invoke(fd);
            if (result == -1) {
                setErrno(9); // EBADF
            }
            return result;
        } catch (Throwable e) {
            setErrno(9); // EBADF
            return -1;
        }
    }
    
    @Override
    public long read(int fd, byte[] buf, long count) {
        ensureFdOpsInitialized();
        try (Arena arena = Arena.ofConfined()) {
            int intCount = (int) Math.min(count, Integer.MAX_VALUE);
            MemorySegment nativeBuf = arena.allocate(intCount);
            int result = (int) winReadHandle.invoke(fd, nativeBuf, intCount);
            if (result == -1) {
                setErrno(5); // EIO
                return -1;
            }
            if (result > 0) {
                MemorySegment.copy(nativeBuf, ValueLayout.JAVA_BYTE, 0, buf, 0, result);
            }
            return result;
        } catch (Throwable e) {
            setErrno(5); // EIO
            return -1;
        }
    }
    
    @Override
    public long write(int fd, byte[] buf, long count) {
        ensureFdOpsInitialized();
        try (Arena arena = Arena.ofConfined()) {
            int intCount = (int) Math.min(count, Integer.MAX_VALUE);
            MemorySegment nativeBuf = arena.allocateFrom(ValueLayout.JAVA_BYTE, buf);
            int result = (int) winWriteHandle.invoke(fd, nativeBuf, intCount);
            if (result == -1) {
                setErrno(5); // EIO
            }
            return result;
        } catch (Throwable e) {
            setErrno(5); // EIO
            return -1;
        }
    }
    
    @Override
    public long lseek(int fd, long offset, int whence) {
        ensureFdOpsInitialized();
        try {
            int result = (int) winLseekHandle.invoke(fd, (int) offset, whence);
            if (result == -1) {
                setErrno(9); // EBADF
            }
            return result;
        } catch (Throwable e) {
            setErrno(9); // EBADF
            return -1;
        }
    }
    
    // ==================== File Control Functions ====================
    
    @Override
    public int fcntl(int fd, int cmd, int arg) {
        // Not supported on Windows
        setErrno(38); // ENOSYS
        return -1;
    }
    
    @Override
    public int umask(int mask) {
        // Simulated - Windows doesn't have umask
        // Just return a reasonable default
        return 022;
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
        // MSVCRT errno values (compatible with POSIX for basic errors)
        // Winsock errors (10000+) mapped to POSIX-like messages for Perl compatibility
        return switch (errno) {
            case 0 -> "Success";
            case 1 -> "Operation not permitted";
            case 2 -> "No such file or directory";
            case 3 -> "No such process";
            case 4 -> "Interrupted function call";
            case 5 -> "Input/output error";
            case 6 -> "No such device or address";
            case 7 -> "Arg list too long";
            case 8 -> "Exec format error";
            case 9 -> "Bad file descriptor";
            case 10 -> "No child processes";
            case 11 -> "Resource temporarily unavailable";
            case 12 -> "Not enough space";
            case 13 -> "Permission denied";
            case 14 -> "Bad address";
            case 16 -> "Resource device";
            case 17 -> "File exists";
            case 18 -> "Improper link";
            case 19 -> "No such device";
            case 20 -> "Not a directory";
            case 21 -> "Is a directory";
            case 22 -> "Invalid argument";
            case 23 -> "Too many open files in system";
            case 24 -> "Too many open files";
            case 25 -> "Inappropriate I/O control operation";
            case 27 -> "File too large";
            case 28 -> "No space left on device";
            case 29 -> "Invalid seek";
            case 30 -> "Read-only file system";
            case 31 -> "Too many links";
            case 32 -> "Broken pipe";
            case 33 -> "Domain error";
            case 34 -> "Result too large";
            case 36 -> "Resource deadlock avoided";
            case 38 -> "Filename too long";
            case 39 -> "No locks available";
            case 40 -> "Function not implemented";
            case 41 -> "Directory not empty";
            case 42 -> "Illegal byte sequence";
            // Winsock errno values mapped to POSIX-like messages
            case 100 -> "Address already in use";
            case 101 -> "Can't assign requested address";
            case 102 -> "Address family not supported";
            case 103 -> "Connection already in progress";
            case 104 -> "Bad message";
            case 105 -> "Operation canceled";
            case 106 -> "Connection aborted";
            case 107 -> "Connection refused";
            case 108 -> "Connection reset";
            case 109 -> "Destination address required";
            case 110 -> "Host is unreachable";
            case 111 -> "Identifier removed";
            case 112 -> "Operation now in progress";
            case 113 -> "Socket is connected";
            case 114 -> "Too many levels of symbolic links";
            case 115 -> "Message too long";
            case 116 -> "Network is down";
            case 117 -> "Connection aborted by network";
            case 118 -> "Network is unreachable";
            case 119 -> "No buffer space available";
            case 121 -> "No message available";
            case 122 -> "No protocol option";
            case 124 -> "Not connected";
            case 126 -> "Not a socket";
            case 127 -> "Operation not supported";
            case 130 -> "Protocol not available";
            case 131 -> "Protocol not supported";
            case 132 -> "Protocol wrong type for socket";
            case 133 -> "Connection timed out";
            case 138 -> "Operation would block";
            default -> "Unknown error " + errno;
        };
    }
    
    // ==================== Helper Methods ====================
    
    /**
     * Calculate Unix-style mode bits from Windows attributes.
     */
    private int calculateMode(BasicFileAttributes basic, DosFileAttributes dos, Path path) {
        int mode = 0;
        
        // File type
        if (basic.isDirectory()) {
            mode |= 0040000; // S_IFDIR
        } else if (basic.isRegularFile()) {
            mode |= 0100000; // S_IFREG
        } else if (basic.isSymbolicLink()) {
            mode |= 0120000; // S_IFLNK
        }
        
        // Default permissions (Windows doesn't have Unix permissions)
        // Check if read-only
        boolean readOnly = dos != null && dos.isReadOnly();
        
        if (basic.isDirectory()) {
            mode |= readOnly ? 0555 : 0755;
        } else {
            mode |= readOnly ? 0444 : 0644;
            
            // Check if executable (by extension)
            String name = path.getFileName().toString().toLowerCase();
            if (name.endsWith(".exe") || name.endsWith(".bat") || 
                name.endsWith(".cmd") || name.endsWith(".com")) {
                mode |= 0111;
            }
        }
        
        return mode;
    }
    
    /**
     * Map Java exceptions to errno values.
     */
    private int getErrnoForException(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return 5; // EIO
        msg = msg.toLowerCase();
        
        if (msg.contains("no such file") || msg.contains("cannot find")) return 2;
        if (msg.contains("access") && msg.contains("denied")) return 13;
        if (msg.contains("already exists")) return 17;
        
        return 5; // EIO
    }
}

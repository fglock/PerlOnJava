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
    
    // strerror handle (no errno capture needed)
    private static MethodHandle strerrorHandle;
    
    // Method handles for passwd functions
    private static MethodHandle getpwnamHandle;
    private static MethodHandle getpwuidHandle;
    private static MethodHandle getpwentHandle;
    private static MethodHandle setpwentHandle;
    private static MethodHandle endpwentHandle;
    
    // Method handles for PTY/terminal functions
    private static MethodHandle posixOpenptHandle;
    private static MethodHandle grantptHandle;
    private static MethodHandle unlockptHandle;
    private static MethodHandle ptsnameHandle;
    private static MethodHandle setsidHandle;
    private static MethodHandle ttynameHandle;
    private static MethodHandle openHandle;
    private static MethodHandle closeHandle;
    private static MethodHandle readHandle;
    private static MethodHandle writeHandle;
    private static MethodHandle dupHandle;
    private static MethodHandle fcntlHandle;
    private static MethodHandle ioctlPtrHandle;
    private static MethodHandle ioctlIntHandle;
    private static MethodHandle tcgetattrHandle;
    private static MethodHandle tcsetattrHandle;
    
    // Platform-specific constants for PTY operations
    public static final int O_RDWR;
    public static final int O_NOCTTY;
    public static final long TIOCGWINSZ;
    public static final long TIOCSWINSZ;
    public static final long TIOCSCTTY;
    public static final long TIOCNOTTY;
    public static final int F_DUPFD = 0;  // Same on both platforms
    public static final int TERMIOS_SIZE;
    
    static {
        if (IS_MACOS) {
            O_RDWR = 0x0002;
            O_NOCTTY = 0x20000;
            TIOCGWINSZ = 0x40087468L;
            TIOCSWINSZ = 0x80087467L;
            TIOCSCTTY = 0x20007461L;
            TIOCNOTTY = 0x20007471L;
            TERMIOS_SIZE = 72;  // macOS struct termios
        } else {
            O_RDWR = 0x0002;
            O_NOCTTY = 0x0100;
            TIOCGWINSZ = 0x5413L;
            TIOCSWINSZ = 0x5414L;
            TIOCSCTTY = 0x540EL;
            TIOCNOTTY = 0x5422L;
            TERMIOS_SIZE = 60;  // Linux struct termios
        }
    }
    
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
    
    // Struct passwd field offsets (platform-dependent)
    private static long PW_NAME_OFFSET;
    private static long PW_PASSWD_OFFSET;
    private static long PW_UID_OFFSET;
    private static long PW_GID_OFFSET;
    private static long PW_CHANGE_OFFSET;   // macOS only
    private static long PW_CLASS_OFFSET;    // macOS only
    private static long PW_GECOS_OFFSET;
    private static long PW_DIR_OFFSET;
    private static long PW_SHELL_OFFSET;
    private static long PW_EXPIRE_OFFSET;   // macOS only
    
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
            
            // strerror: char* strerror(int errnum)
            strerrorHandle = linker.downcallHandle(
                stdlib.find("strerror").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
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
            
            // passwd functions - return pointers to static passwd struct
            getpwnamHandle = linker.downcallHandle(
                stdlib.find("getpwnam").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            
            getpwuidHandle = linker.downcallHandle(
                stdlib.find("getpwuid").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
            );
            
            getpwentHandle = linker.downcallHandle(
                stdlib.find("getpwent").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS)
            );
            
            setpwentHandle = linker.downcallHandle(
                stdlib.find("setpwent").orElseThrow(),
                FunctionDescriptor.ofVoid()
            );
            
            endpwentHandle = linker.downcallHandle(
                stdlib.find("endpwent").orElseThrow(),
                FunctionDescriptor.ofVoid()
            );
            
            // Initialize passwd struct offsets
            initPasswdOffsets();
            
            // PTY/Terminal functions (all need errno capture)
            posixOpenptHandle = linker.downcallHandle(
                stdlib.find("posix_openpt").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                captureErrno
            );
            
            grantptHandle = linker.downcallHandle(
                stdlib.find("grantpt").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                captureErrno
            );
            
            unlockptHandle = linker.downcallHandle(
                stdlib.find("unlockpt").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                captureErrno
            );
            
            // ptsname: char* ptsname(int fd)
            ptsnameHandle = linker.downcallHandle(
                stdlib.find("ptsname").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                captureErrno
            );
            
            // setsid: pid_t setsid(void)
            setsidHandle = linker.downcallHandle(
                stdlib.find("setsid").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT),
                captureErrno
            );
            
            // ttyname: char* ttyname(int fd)
            ttynameHandle = linker.downcallHandle(
                stdlib.find("ttyname").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
            );
            
            // open: int open(const char *path, int flags)
            openHandle = linker.downcallHandle(
                stdlib.find("open").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                captureErrno
            );
            
            // close: int close(int fd)
            closeHandle = linker.downcallHandle(
                stdlib.find("close").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                captureErrno
            );
            
            // read: ssize_t read(int fd, void *buf, size_t count)
            readHandle = linker.downcallHandle(
                stdlib.find("read").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
                captureErrno
            );
            
            // write: ssize_t write(int fd, const void *buf, size_t count)
            writeHandle = linker.downcallHandle(
                stdlib.find("write").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
                captureErrno
            );
            
            // dup: int dup(int fd)
            dupHandle = linker.downcallHandle(
                stdlib.find("dup").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                captureErrno
            );
            
            // fcntl: int fcntl(int fd, int cmd, ...)
            fcntlHandle = linker.downcallHandle(
                stdlib.find("fcntl").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                Linker.Option.firstVariadicArg(2), captureErrno
            );
            
            // ioctl with pointer arg: int ioctl(int fd, unsigned long request, void *arg)
            ioctlPtrHandle = linker.downcallHandle(
                stdlib.find("ioctl").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
                Linker.Option.firstVariadicArg(2), captureErrno
            );
            
            // ioctl with int arg: int ioctl(int fd, unsigned long request, int arg)
            ioctlIntHandle = linker.downcallHandle(
                stdlib.find("ioctl").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT),
                Linker.Option.firstVariadicArg(2), captureErrno
            );
            
            // tcgetattr: int tcgetattr(int fd, struct termios *termios_p)
            tcgetattrHandle = linker.downcallHandle(
                stdlib.find("tcgetattr").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                captureErrno
            );
            
            // tcsetattr: int tcsetattr(int fd, int optional_actions, const struct termios *termios_p)
            tcsetattrHandle = linker.downcallHandle(
                stdlib.find("tcsetattr").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
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
    
    /**
     * Initialize platform-specific struct passwd field offsets.
     * 
     * The struct passwd layout differs between Linux and macOS.
     * - Linux: simpler struct with just the basics
     * - macOS: includes additional fields like pw_change, pw_class, pw_expire
     */
    private static void initPasswdOffsets() {
        // Pointer size on 64-bit systems
        int ptrSize = 8;
        
        if (IS_MACOS) {
            // macOS struct passwd layout (from pwd.h)
            // struct passwd {
            //     char *pw_name;      // 0
            //     char *pw_passwd;    // 8
            //     uid_t pw_uid;       // 16 (4 bytes)
            //     gid_t pw_gid;       // 20 (4 bytes)
            //     time_t pw_change;   // 24 (8 bytes) - macOS specific
            //     char *pw_class;     // 32 (pointer) - macOS specific
            //     char *pw_gecos;     // 40
            //     char *pw_dir;       // 48
            //     char *pw_shell;     // 56
            //     time_t pw_expire;   // 64 (8 bytes) - macOS specific
            //     int pw_fields;      // 72 (4 bytes) - macOS specific
            // };
            PW_NAME_OFFSET = 0;
            PW_PASSWD_OFFSET = 8;
            PW_UID_OFFSET = 16;
            PW_GID_OFFSET = 20;
            PW_CHANGE_OFFSET = 24;
            PW_CLASS_OFFSET = 32;
            PW_GECOS_OFFSET = 40;
            PW_DIR_OFFSET = 48;
            PW_SHELL_OFFSET = 56;
            PW_EXPIRE_OFFSET = 64;
        } else {
            // Linux struct passwd layout (from pwd.h)
            // struct passwd {
            //     char *pw_name;      // 0
            //     char *pw_passwd;    // 8
            //     uid_t pw_uid;       // 16 (4 bytes)
            //     gid_t pw_gid;       // 20 (4 bytes)
            //     char *pw_gecos;     // 24
            //     char *pw_dir;       // 32
            //     char *pw_shell;     // 40
            // };
            PW_NAME_OFFSET = 0;
            PW_PASSWD_OFFSET = 8;
            PW_UID_OFFSET = 16;
            PW_GID_OFFSET = 20;
            PW_GECOS_OFFSET = 24;
            PW_DIR_OFFSET = 32;
            PW_SHELL_OFFSET = 40;
            PW_CHANGE_OFFSET = -1;  // Not available on Linux
            PW_CLASS_OFFSET = -1;   // Not available on Linux
            PW_EXPIRE_OFFSET = -1;  // Not available on Linux
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
        ensureInitialized();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nameSegment = arena.allocateFrom(name);
            MemorySegment result = (MemorySegment) getpwnamHandle.invokeExact(nameSegment);
            if (result.address() == 0) {
                return null;
            }
            return readPasswdEntry(result);
        } catch (Throwable e) {
            return null;
        }
    }
    
    @Override
    public PasswdEntry getpwuid(int uid) {
        ensureInitialized();
        try {
            MemorySegment result = (MemorySegment) getpwuidHandle.invokeExact(uid);
            if (result.address() == 0) {
                return null;
            }
            return readPasswdEntry(result);
        } catch (Throwable e) {
            return null;
        }
    }
    
    @Override
    public PasswdEntry getpwent() {
        ensureInitialized();
        try {
            MemorySegment result = (MemorySegment) getpwentHandle.invokeExact();
            if (result.address() == 0) {
                return null;
            }
            return readPasswdEntry(result);
        } catch (Throwable e) {
            return null;
        }
    }
    
    @Override
    public void setpwent() {
        ensureInitialized();
        try {
            setpwentHandle.invokeExact();
        } catch (Throwable e) {
            // Ignore errors
        }
    }
    
    @Override
    public void endpwent() {
        ensureInitialized();
        try {
            endpwentHandle.invokeExact();
        } catch (Throwable e) {
            // Ignore errors
        }
    }
    
    /**
     * Read a passwd entry from a native struct pointer.
     */
    private PasswdEntry readPasswdEntry(MemorySegment passwdPtr) {
        // Reinterpret the pointer as having enough size to read all fields
        MemorySegment passwd = passwdPtr.reinterpret(128);  // Should be big enough for the struct
        
        // Read string pointers and convert to Java strings
        String name = readCString(passwd.get(ValueLayout.ADDRESS, PW_NAME_OFFSET));
        String passwdField = readCString(passwd.get(ValueLayout.ADDRESS, PW_PASSWD_OFFSET));
        int uid = passwd.get(ValueLayout.JAVA_INT, PW_UID_OFFSET);
        int gid = passwd.get(ValueLayout.JAVA_INT, PW_GID_OFFSET);
        String gecos = readCString(passwd.get(ValueLayout.ADDRESS, PW_GECOS_OFFSET));
        String dir = readCString(passwd.get(ValueLayout.ADDRESS, PW_DIR_OFFSET));
        String shell = readCString(passwd.get(ValueLayout.ADDRESS, PW_SHELL_OFFSET));
        
        // macOS-specific fields
        long change = 0;
        long expire = 0;
        if (IS_MACOS && PW_CHANGE_OFFSET >= 0) {
            change = passwd.get(ValueLayout.JAVA_LONG, PW_CHANGE_OFFSET);
            expire = passwd.get(ValueLayout.JAVA_LONG, PW_EXPIRE_OFFSET);
        }
        
        return new PasswdEntry(name, passwdField, uid, gid, gecos, dir, shell, change, expire);
    }
    
    /**
     * Read a C string from a native pointer.
     */
    private String readCString(MemorySegment ptr) {
        if (ptr.address() == 0) {
            return "";
        }
        // Reinterpret with max size to find null terminator
        return ptr.reinterpret(1024).getString(0);
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
        ensureInitialized();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capturedState = arena.allocate(Linker.Option.captureStateLayout());
            int result = (int) fcntlHandle.invokeExact(capturedState, fd, cmd, arg);
            if (result == -1) {
                int err = capturedState.get(ValueLayout.JAVA_INT, errnoOffset);
                setErrno(err);
            }
            return result;
        } catch (Throwable e) {
            setErrno(22); // EINVAL
            return -1;
        }
    }
    
    // ==================== PTY/Terminal Functions ====================
    
    @Override
    public int posix_openpt(int flags) {
        ensureInitialized();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capturedState = arena.allocate(Linker.Option.captureStateLayout());
            int result = (int) posixOpenptHandle.invokeExact(capturedState, flags);
            if (result == -1) {
                int err = capturedState.get(ValueLayout.JAVA_INT, errnoOffset);
                setErrno(err);
            }
            return result;
        } catch (Throwable e) {
            setErrno(38); // ENOSYS
            return -1;
        }
    }
    
    @Override
    public int grantpt(int masterFd) {
        ensureInitialized();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capturedState = arena.allocate(Linker.Option.captureStateLayout());
            int result = (int) grantptHandle.invokeExact(capturedState, masterFd);
            if (result == -1) {
                int err = capturedState.get(ValueLayout.JAVA_INT, errnoOffset);
                setErrno(err);
            }
            return result;
        } catch (Throwable e) {
            setErrno(9); // EBADF
            return -1;
        }
    }
    
    @Override
    public int unlockpt(int masterFd) {
        ensureInitialized();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capturedState = arena.allocate(Linker.Option.captureStateLayout());
            int result = (int) unlockptHandle.invokeExact(capturedState, masterFd);
            if (result == -1) {
                int err = capturedState.get(ValueLayout.JAVA_INT, errnoOffset);
                setErrno(err);
            }
            return result;
        } catch (Throwable e) {
            setErrno(9); // EBADF
            return -1;
        }
    }
    
    @Override
    public String ptsname(int masterFd) {
        ensureInitialized();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capturedState = arena.allocate(Linker.Option.captureStateLayout());
            MemorySegment result = (MemorySegment) ptsnameHandle.invokeExact(capturedState, masterFd);
            if (result.address() == 0) {
                int err = capturedState.get(ValueLayout.JAVA_INT, errnoOffset);
                setErrno(err);
                return null;
            }
            return result.reinterpret(256).getString(0);
        } catch (Throwable e) {
            setErrno(9); // EBADF
            return null;
        }
    }
    
    @Override
    public int setsid() {
        ensureInitialized();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capturedState = arena.allocate(Linker.Option.captureStateLayout());
            int result = (int) setsidHandle.invokeExact(capturedState);
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
    public String ttyname(int fd) {
        ensureInitialized();
        try {
            MemorySegment result = (MemorySegment) ttynameHandle.invokeExact(fd);
            if (result.address() == 0) {
                return null;
            }
            return result.reinterpret(256).getString(0);
        } catch (Throwable e) {
            return null;
        }
    }
    
    @Override
    public int nativeOpen(String path, int flags) {
        ensureInitialized();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathSegment = arena.allocateFrom(path);
            MemorySegment capturedState = arena.allocate(Linker.Option.captureStateLayout());
            int result = (int) openHandle.invokeExact(capturedState, pathSegment, flags);
            if (result == -1) {
                int err = capturedState.get(ValueLayout.JAVA_INT, errnoOffset);
                setErrno(err);
            }
            return result;
        } catch (Throwable e) {
            setErrno(5); // EIO
            return -1;
        }
    }
    
    @Override
    public int nativeClose(int fd) {
        ensureInitialized();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capturedState = arena.allocate(Linker.Option.captureStateLayout());
            int result = (int) closeHandle.invokeExact(capturedState, fd);
            if (result == -1) {
                int err = capturedState.get(ValueLayout.JAVA_INT, errnoOffset);
                setErrno(err);
            }
            return result;
        } catch (Throwable e) {
            setErrno(9); // EBADF
            return -1;
        }
    }
    
    @Override
    public int nativeRead(int fd, byte[] buf, int count) {
        ensureInitialized();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nativeBuf = arena.allocate(count);
            MemorySegment capturedState = arena.allocate(Linker.Option.captureStateLayout());
            long result = (long) readHandle.invokeExact(capturedState, fd, nativeBuf, (long) count);
            if (result == -1) {
                int err = capturedState.get(ValueLayout.JAVA_INT, errnoOffset);
                setErrno(err);
                return -1;
            }
            // Copy data from native buffer to Java array
            MemorySegment.copy(nativeBuf, ValueLayout.JAVA_BYTE, 0, buf, 0, (int) result);
            return (int) result;
        } catch (Throwable e) {
            setErrno(5); // EIO
            return -1;
        }
    }
    
    @Override
    public int nativeWrite(int fd, byte[] buf, int count) {
        ensureInitialized();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nativeBuf = arena.allocateFrom(ValueLayout.JAVA_BYTE, buf);
            MemorySegment capturedState = arena.allocate(Linker.Option.captureStateLayout());
            long result = (long) writeHandle.invokeExact(capturedState, fd, nativeBuf, (long) count);
            if (result == -1) {
                int err = capturedState.get(ValueLayout.JAVA_INT, errnoOffset);
                setErrno(err);
                return -1;
            }
            return (int) result;
        } catch (Throwable e) {
            setErrno(5); // EIO
            return -1;
        }
    }
    
    @Override
    public int nativeDup(int fd) {
        ensureInitialized();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capturedState = arena.allocate(Linker.Option.captureStateLayout());
            int result = (int) dupHandle.invokeExact(capturedState, fd);
            if (result == -1) {
                int err = capturedState.get(ValueLayout.JAVA_INT, errnoOffset);
                setErrno(err);
            }
            return result;
        } catch (Throwable e) {
            setErrno(9); // EBADF
            return -1;
        }
    }
    
    @Override
    public int fcntlDupFd(int fd, int minFd) {
        return fcntl(fd, F_DUPFD, minFd);
    }
    
    @Override
    public int ioctlWithPointer(int fd, long request, byte[] buf) {
        ensureInitialized();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nativeBuf = arena.allocate(buf.length);
            // Copy input data to native buffer
            MemorySegment.copy(buf, 0, nativeBuf, ValueLayout.JAVA_BYTE, 0, buf.length);
            MemorySegment capturedState = arena.allocate(Linker.Option.captureStateLayout());
            int result = (int) ioctlPtrHandle.invokeExact(capturedState, fd, request, nativeBuf);
            if (result == -1) {
                int err = capturedState.get(ValueLayout.JAVA_INT, errnoOffset);
                setErrno(err);
                return -1;
            }
            // Copy output data back to Java array
            MemorySegment.copy(nativeBuf, ValueLayout.JAVA_BYTE, 0, buf, 0, buf.length);
            return result;
        } catch (Throwable e) {
            setErrno(22); // EINVAL
            return -1;
        }
    }
    
    @Override
    public int ioctlWithInt(int fd, long request, int arg) {
        ensureInitialized();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capturedState = arena.allocate(Linker.Option.captureStateLayout());
            int result = (int) ioctlIntHandle.invokeExact(capturedState, fd, request, arg);
            if (result == -1) {
                int err = capturedState.get(ValueLayout.JAVA_INT, errnoOffset);
                setErrno(err);
                return -1;
            }
            return result;
        } catch (Throwable e) {
            setErrno(22); // EINVAL
            return -1;
        }
    }
    
    @Override
    public int tcgetattr(int fd, byte[] termios) {
        ensureInitialized();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nativeBuf = arena.allocate(termios.length);
            MemorySegment capturedState = arena.allocate(Linker.Option.captureStateLayout());
            int result = (int) tcgetattrHandle.invokeExact(capturedState, fd, nativeBuf);
            if (result == -1) {
                int err = capturedState.get(ValueLayout.JAVA_INT, errnoOffset);
                setErrno(err);
                return -1;
            }
            MemorySegment.copy(nativeBuf, ValueLayout.JAVA_BYTE, 0, termios, 0, termios.length);
            return result;
        } catch (Throwable e) {
            setErrno(25); // ENOTTY
            return -1;
        }
    }
    
    @Override
    public int tcsetattr(int fd, int optionalActions, byte[] termios) {
        ensureInitialized();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nativeBuf = arena.allocate(termios.length);
            MemorySegment.copy(termios, 0, nativeBuf, ValueLayout.JAVA_BYTE, 0, termios.length);
            MemorySegment capturedState = arena.allocate(Linker.Option.captureStateLayout());
            int result = (int) tcsetattrHandle.invokeExact(capturedState, fd, optionalActions, nativeBuf);
            if (result == -1) {
                int err = capturedState.get(ValueLayout.JAVA_INT, errnoOffset);
                setErrno(err);
                return -1;
            }
            return result;
        } catch (Throwable e) {
            setErrno(25); // ENOTTY
            return -1;
        }
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
        if (errno == 0) return "Success";
        ensureInitialized();
        try {
            MemorySegment ptr = (MemorySegment) strerrorHandle.invoke(errno);
            if (ptr.equals(MemorySegment.NULL)) {
                return "Unknown error " + errno;
            }
            return ptr.reinterpret(256).getString(0);
        } catch (Throwable t) {
            return "Unknown error " + errno;
        }
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

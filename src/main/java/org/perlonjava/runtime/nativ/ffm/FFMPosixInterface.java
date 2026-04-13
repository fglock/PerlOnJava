package org.perlonjava.runtime.nativ.ffm;

/**
 * Interface defining POSIX functions to be implemented via Java FFM API.
 * This interface abstracts platform-specific native calls, allowing different
 * implementations for Linux, macOS, and Windows.
 * 
 * <p>All methods should handle errors by returning appropriate error codes
 * and setting errno via {@link #errno()} / {@link #setErrno(int)}.</p>
 */
public interface FFMPosixInterface {

    // ==================== Process Functions ====================
    
    /**
     * Send a signal to a process.
     * @param pid Process ID (negative for process group)
     * @param signal Signal number
     * @return 0 on success, -1 on error (check errno)
     */
    int kill(int pid, int signal);
    
    /**
     * Get the parent process ID.
     * @return Parent process ID
     */
    int getppid();
    
    /**
     * Wait for a child process.
     * @param pid Process ID to wait for (-1 for any child)
     * @param status Array to store exit status (at least 1 element)
     * @param options Wait options (WNOHANG, WUNTRACED, etc.)
     * @return Process ID of terminated child, 0 if WNOHANG and no child, -1 on error
     */
    long waitpid(int pid, int[] status, int options);
    
    // ==================== User/Group Functions ====================
    
    /**
     * Get the real user ID.
     * @return User ID
     */
    int getuid();
    
    /**
     * Get the effective user ID.
     * @return Effective user ID
     */
    int geteuid();
    
    /**
     * Get the real group ID.
     * @return Group ID
     */
    int getgid();
    
    /**
     * Get the effective group ID.
     * @return Effective group ID
     */
    int getegid();
    
    /**
     * Get password entry by username.
     * @param name Username
     * @return Password entry or null if not found
     */
    PasswdEntry getpwnam(String name);
    
    /**
     * Get password entry by user ID.
     * @param uid User ID
     * @return Password entry or null if not found
     */
    PasswdEntry getpwuid(int uid);
    
    /**
     * Get next password entry (for iteration).
     * @return Next password entry or null if end of database
     */
    PasswdEntry getpwent();
    
    /**
     * Reset password database to beginning.
     */
    void setpwent();
    
    /**
     * Close password database.
     */
    void endpwent();
    
    // ==================== File Functions ====================
    
    /**
     * Get file status.
     * @param path File path
     * @return Stat result or null on error (check errno)
     */
    StatResult stat(String path);
    
    /**
     * Get file status (don't follow symlinks).
     * @param path File path
     * @return Stat result or null on error (check errno)
     */
    StatResult lstat(String path);
    
    /**
     * Change file permissions.
     * @param path File path
     * @param mode Permission mode (octal)
     * @return 0 on success, -1 on error
     */
    int chmod(String path, int mode);
    
    /**
     * Create a hard link.
     * @param oldPath Existing file path
     * @param newPath New link path
     * @return 0 on success, -1 on error
     */
    int link(String oldPath, String newPath);
    
    /**
     * Set file access and modification times.
     * @param path File path
     * @param atime Access time (seconds since epoch)
     * @param mtime Modification time (seconds since epoch)
     * @return 0 on success, -1 on error
     */
    int utimes(String path, long atime, long mtime);
    
    // ==================== Terminal Functions ====================
    
    /**
     * Check if file descriptor is a terminal.
     * @param fd File descriptor (0=stdin, 1=stdout, 2=stderr)
     * @return 1 if terminal, 0 if not
     */
    int isatty(int fd);
    
    // ==================== PTY/Terminal Functions ====================
    
    /**
     * Open a pseudo-terminal master device.
     * @param flags Open flags (O_RDWR, O_NOCTTY, etc.)
     * @return Master file descriptor on success, -1 on error
     */
    int posix_openpt(int flags);
    
    /**
     * Change ownership and permissions of slave pty device.
     * @param masterFd Master pty file descriptor
     * @return 0 on success, -1 on error
     */
    int grantpt(int masterFd);
    
    /**
     * Unlock a slave pty for opening.
     * @param masterFd Master pty file descriptor
     * @return 0 on success, -1 on error
     */
    int unlockpt(int masterFd);
    
    /**
     * Get the name of the slave pty device.
     * @param masterFd Master pty file descriptor
     * @return Slave device path (e.g., "/dev/pts/3") or null on error
     */
    String ptsname(int masterFd);
    
    /**
     * Create a new session and set the calling process as session leader.
     * @return Session ID (process ID) on success, -1 on error
     */
    int setsid();
    
    /**
     * Get the terminal device name for a file descriptor.
     * @param fd File descriptor
     * @return Device name (e.g., "/dev/pts/3") or null if not a terminal
     */
    String ttyname(int fd);
    
    /**
     * Open a file by path and flags, returning a raw POSIX file descriptor.
     * @param path File path
     * @param flags Open flags (O_RDWR, O_NOCTTY, etc.)
     * @return File descriptor on success, -1 on error
     */
    int nativeOpen(String path, int flags);
    
    /**
     * Close a raw POSIX file descriptor.
     * @param fd File descriptor
     * @return 0 on success, -1 on error
     */
    int nativeClose(int fd);
    
    /**
     * Read from a raw file descriptor.
     * @param fd File descriptor
     * @param buf Buffer to read into
     * @param count Maximum bytes to read
     * @return Number of bytes read, 0 on EOF, -1 on error
     */
    int nativeRead(int fd, byte[] buf, int count);
    
    /**
     * Write to a raw file descriptor.
     * @param fd File descriptor
     * @param buf Buffer to write from
     * @param count Number of bytes to write
     * @return Number of bytes written, -1 on error
     */
    int nativeWrite(int fd, byte[] buf, int count);
    
    /**
     * Duplicate a file descriptor to one >= minFd (fcntl F_DUPFD).
     * @param fd File descriptor to duplicate
     * @param minFd Minimum value for the new descriptor
     * @return New file descriptor, or -1 on error
     */
    int fcntlDupFd(int fd, int minFd);
    
    /**
     * Perform ioctl with a pointer argument.
     * Used for TIOCGWINSZ/TIOCSWINSZ (struct winsize) and similar.
     * @param fd File descriptor
     * @param request ioctl request code
     * @param buf Buffer for input/output data
     * @return 0 on success, -1 on error
     */
    int ioctlWithPointer(int fd, long request, byte[] buf);
    
    /**
     * Perform ioctl with an integer argument.
     * Used for TIOCSCTTY and similar.
     * @param fd File descriptor
     * @param request ioctl request code
     * @param arg Integer argument
     * @return 0 on success, -1 on error
     */
    int ioctlWithInt(int fd, long request, int arg);
    
    /**
     * Get terminal attributes.
     * @param fd File descriptor of terminal
     * @param termios Buffer for termios struct (platform-dependent size)
     * @return 0 on success, -1 on error
     */
    int tcgetattr(int fd, byte[] termios);
    
    /**
     * Set terminal attributes.
     * @param fd File descriptor of terminal
     * @param optionalActions When to apply (TCSANOW, TCSADRAIN, TCSAFLUSH)
     * @param termios Buffer containing termios struct
     * @return 0 on success, -1 on error
     */
    int tcsetattr(int fd, int optionalActions, byte[] termios);
    
    /**
     * Duplicate a file descriptor (like POSIX dup).
     * @param fd File descriptor to duplicate
     * @return New file descriptor, or -1 on error
     */
    int nativeDup(int fd);
    
    // ==================== Low-level File Descriptor Functions ====================
    
    /**
     * Create a pipe.
     * @param fds Array of at least 2 ints: fds[0] = read end, fds[1] = write end
     * @return 0 on success, -1 on error (check errno)
     */
    int pipe(int[] fds);
    
    /**
     * Duplicate a file descriptor.
     * @param fd File descriptor to duplicate
     * @return New file descriptor, or -1 on error (check errno)
     */
    int dup(int fd);
    
    /**
     * Open a file.
     * @param path File path
     * @param flags Open flags (O_RDONLY, O_WRONLY, O_RDWR, O_CREAT, etc.)
     * @param mode Permission mode (used with O_CREAT)
     * @return File descriptor, or -1 on error (check errno)
     */
    int open(String path, int flags, int mode);
    
    /**
     * Close a file descriptor.
     * @param fd File descriptor to close
     * @return 0 on success, -1 on error (check errno)
     */
    int close(int fd);
    
    /**
     * Read from a file descriptor.
     * @param fd File descriptor
     * @param buf Buffer to read into
     * @param count Maximum number of bytes to read
     * @return Number of bytes read, 0 at EOF, -1 on error (check errno)
     */
    long read(int fd, byte[] buf, long count);
    
    /**
     * Write to a file descriptor.
     * @param fd File descriptor
     * @param buf Buffer to write from
     * @param count Number of bytes to write
     * @return Number of bytes written, -1 on error (check errno)
     */
    long write(int fd, byte[] buf, long count);
    
    /**
     * Reposition read/write file offset.
     * @param fd File descriptor
     * @param offset Offset in bytes
     * @param whence SEEK_SET (0), SEEK_CUR (1), or SEEK_END (2)
     * @return Resulting offset from beginning of file, -1 on error
     */
    long lseek(int fd, long offset, int whence);
    
    // ==================== File Control Functions ====================
    
    /**
     * File control operations.
     * @param fd File descriptor
     * @param cmd Command (F_GETFL, F_SETFL, etc.)
     * @param arg Command argument
     * @return Result depends on command, -1 on error
     */
    int fcntl(int fd, int cmd, int arg);
    
    /**
     * Get/set file creation mask.
     * @param mask New umask value
     * @return Previous umask value
     */
    int umask(int mask);
    
    // ==================== Error Handling ====================
    
    /**
     * Get the last error number.
     * @return errno value
     */
    int errno();
    
    /**
     * Set the error number (for testing/simulation).
     * @param errno Error number to set
     */
    void setErrno(int errno);
    
    /**
     * Get error message for errno.
     * @param errno Error number
     * @return Error message string
     */
    String strerror(int errno);
    
    // ==================== Data Structures ====================
    
    /**
     * Password database entry (struct passwd equivalent).
     */
    record PasswdEntry(
        String name,      // pw_name - username
        String passwd,    // pw_passwd - password (usually "x")
        int uid,          // pw_uid - user ID
        int gid,          // pw_gid - group ID
        String gecos,     // pw_gecos - user info (real name, etc.)
        String dir,       // pw_dir - home directory
        String shell,     // pw_shell - login shell
        long change,      // pw_change - password change time (BSD/macOS)
        long expire       // pw_expire - account expiration (BSD/macOS)
    ) {}
    
    /**
     * File status result (struct stat equivalent).
     */
    record StatResult(
        long dev,      // st_dev - device ID
        long ino,      // st_ino - inode number
        int mode,      // st_mode - file mode (type and permissions)
        long nlink,    // st_nlink - number of hard links
        int uid,       // st_uid - owner user ID
        int gid,       // st_gid - owner group ID
        long rdev,     // st_rdev - device ID (if special file)
        long size,     // st_size - file size in bytes
        long atime,    // st_atime - last access time
        long mtime,    // st_mtime - last modification time
        long ctime,    // st_ctime - last status change time
        long blksize,  // st_blksize - preferred I/O block size
        long blocks    // st_blocks - number of 512-byte blocks
    ) {}
}

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

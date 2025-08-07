package org.perlonjava.nativ;

import com.sun.jna.*;
import com.sun.jna.ptr.*;

/**
 * JNA interface to POSIX C library functions
 */
public interface PosixLibrary extends Library {
    PosixLibrary INSTANCE = Native.load("c", PosixLibrary.class);

    // Process management
    int kill(int pid, int sig) throws LastErrorException;
    int getpid();
    int getppid();
    int getuid();
    int geteuid();
    int getgid();
    int getegid();
    int setuid(int uid) throws LastErrorException;
    int setgid(int gid) throws LastErrorException;

    // Process control
    int fork() throws LastErrorException;
    int execve(String path, String[] argv, String[] envp) throws LastErrorException;
    int waitpid(int pid, IntByReference status, int options) throws LastErrorException;

    // File operations
    int chmod(String path, int mode) throws LastErrorException;
    int chown(String path, int uid, int gid) throws LastErrorException;
    int umask(int mask);
    int unlink(String path) throws LastErrorException;
    int rename(String oldpath, String newpath) throws LastErrorException;

    // Symbolic link operations
    int readlink(String path, byte[] buf, int bufsiz) throws LastErrorException;
    int link(String oldPath, String newPath);

    // Signal handling
    Pointer signal(int sig, Pointer handler) throws LastErrorException;
    int raise(int sig) throws LastErrorException;
    int sigaction(int sig, Pointer act, Pointer oldact) throws LastErrorException;

    // Time functions
    long time(LongByReference tloc);
    int sleep(int seconds);
    int usleep(int microseconds) throws LastErrorException;

    // Environment
    String getenv(String name);
    int setenv(String name, String value, int overwrite) throws LastErrorException;
    int unsetenv(String name) throws LastErrorException;

    // Error handling
    int errno();
    String strerror(int errnum);
}
package org.perlonjava.runtime.runtimetypes;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents the special Perl variable $! (errno).
 * This variable is a dualvar - it has both a numeric value (errno number)
 * and a string value (error message).
 * 
 * When set to a number, it stores the errno and looks up the message.
 * When set to a string, it stores 0 as errno and the string as message.
 */
public class ErrnoVariable extends RuntimeScalar {
    
    private int errno = 0;
    private String message = "";
    
    // Map of errno numbers to messages (POSIX standard messages)
    private static final Map<Integer, String> ERRNO_MESSAGES = new HashMap<>();
    
    static {
        // Standard POSIX errno values and messages
        ERRNO_MESSAGES.put(1, "Operation not permitted");
        ERRNO_MESSAGES.put(2, "No such file or directory");
        ERRNO_MESSAGES.put(3, "No such process");
        ERRNO_MESSAGES.put(4, "Interrupted system call");
        ERRNO_MESSAGES.put(5, "Input/output error");
        ERRNO_MESSAGES.put(6, "No such device or address");
        ERRNO_MESSAGES.put(7, "Argument list too long");
        ERRNO_MESSAGES.put(8, "Exec format error");
        ERRNO_MESSAGES.put(9, "Bad file descriptor");
        ERRNO_MESSAGES.put(10, "No child processes");
        ERRNO_MESSAGES.put(11, "Resource temporarily unavailable");
        ERRNO_MESSAGES.put(12, "Cannot allocate memory");
        ERRNO_MESSAGES.put(13, "Permission denied");
        ERRNO_MESSAGES.put(14, "Bad address");
        ERRNO_MESSAGES.put(15, "Block device required");
        ERRNO_MESSAGES.put(16, "Device or resource busy");
        ERRNO_MESSAGES.put(17, "File exists");
        ERRNO_MESSAGES.put(18, "Invalid cross-device link");
        ERRNO_MESSAGES.put(19, "No such device");
        ERRNO_MESSAGES.put(20, "Not a directory");
        ERRNO_MESSAGES.put(21, "Is a directory");
        ERRNO_MESSAGES.put(22, "Invalid argument");
        ERRNO_MESSAGES.put(23, "Too many open files in system");
        ERRNO_MESSAGES.put(24, "Too many open files");
        ERRNO_MESSAGES.put(25, "Inappropriate ioctl for device");
        ERRNO_MESSAGES.put(26, "Text file busy");
        ERRNO_MESSAGES.put(27, "File too large");
        ERRNO_MESSAGES.put(28, "No space left on device");
        ERRNO_MESSAGES.put(29, "Illegal seek");
        ERRNO_MESSAGES.put(30, "Read-only file system");
        ERRNO_MESSAGES.put(31, "Too many links");
        ERRNO_MESSAGES.put(32, "Broken pipe");
        ERRNO_MESSAGES.put(33, "Numerical argument out of domain");
        ERRNO_MESSAGES.put(34, "Numerical result out of range");
        ERRNO_MESSAGES.put(35, "Resource deadlock avoided");
        ERRNO_MESSAGES.put(36, "File name too long");
        ERRNO_MESSAGES.put(37, "No locks available");
        ERRNO_MESSAGES.put(38, "Function not implemented");
        ERRNO_MESSAGES.put(39, "Directory not empty");
        ERRNO_MESSAGES.put(40, "Too many levels of symbolic links");
    }
    
    public ErrnoVariable() {
        super();
        this.type = RuntimeScalarType.INTEGER;
        this.value = 0;
    }
    
    /**
     * Set errno from an integer value.
     */
    @Override
    public RuntimeScalar set(int value) {
        this.errno = value;
        this.message = ERRNO_MESSAGES.getOrDefault(value, value == 0 ? "" : "Unknown error " + value);
        this.type = RuntimeScalarType.INTEGER;
        this.value = value;
        return this;
    }
    
    /**
     * Set errno from a string value.
     * If the string is a number, treat it as errno.
     * Otherwise, set errno to 0 and use the string as the message.
     */
    @Override
    public RuntimeScalar set(String value) {
        if (value == null || value.isEmpty()) {
            this.errno = 0;
            this.message = "";
            this.type = RuntimeScalarType.INTEGER;
            this.value = 0;
            return this;
        }
        
        // Try to parse as integer
        try {
            int num = Integer.parseInt(value.trim());
            return set(num);
        } catch (NumberFormatException e) {
            // Not a number - store as message with errno 0
            // This is legacy behavior for code that sets $! = "message"
            this.errno = 0;
            this.message = value;
            this.type = RuntimeScalarType.STRING;
            this.value = value;
            return this;
        }
    }
    
    /**
     * Set from another RuntimeScalar.
     */
    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        if (value.type == RuntimeScalarType.INTEGER) {
            return set(value.getInt());
        } else if (value.type == RuntimeScalarType.DOUBLE) {
            return set((int) value.getDouble());
        } else {
            return set(value.toString());
        }
    }
    
    @Override
    public RuntimeScalar set(Object value) {
        if (value instanceof Integer) {
            return set((int) value);
        } else if (value instanceof Number) {
            return set(((Number) value).intValue());
        } else {
            return set(value.toString());
        }
    }
    
    /**
     * Get the numeric value (errno number).
     */
    @Override
    public int getInt() {
        return errno;
    }
    
    /**
     * Get the numeric value as double.
     */
    @Override
    public double getDouble() {
        return errno;
    }
    
    /**
     * Get the string value (error message).
     */
    @Override
    public String toString() {
        return message;
    }
    
    /**
     * Get boolean value - true if errno is non-zero.
     */
    @Override
    public boolean getBoolean() {
        return errno != 0;
    }
    
    /**
     * Clear the error (set to 0).
     */
    public void clear() {
        set(0);
    }
}

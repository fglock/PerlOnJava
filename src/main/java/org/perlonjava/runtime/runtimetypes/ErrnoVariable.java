package org.perlonjava.runtime.runtimetypes;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents the special Perl variable $! (errno).
 * This variable is a dualvar - it has both a numeric value (errno number)
 * and a string value (error message).
 * 
 * When set to a number, it stores the errno and looks up the message.
 * When set to a string (known errno message), it looks up the errno code.
 * When set to an unknown string, it stores 0 as errno and the string as message.
 */
public class ErrnoVariable extends RuntimeScalar {
    
    private int errno = 0;
    private String message = "";
    
    // Map of errno numbers to messages (POSIX standard messages)
    private static final Map<Integer, String> ERRNO_MESSAGES = new HashMap<>();
    // Reverse map of messages to errno numbers
    private static final Map<String, Integer> MESSAGE_TO_ERRNO = new HashMap<>();
    
    static {
        // Standard POSIX errno values and messages
        addErrno(1, "Operation not permitted");
        addErrno(2, "No such file or directory");
        addErrno(3, "No such process");
        addErrno(4, "Interrupted system call");
        addErrno(5, "Input/output error");
        addErrno(6, "No such device or address");
        addErrno(7, "Argument list too long");
        addErrno(8, "Exec format error");
        addErrno(9, "Bad file descriptor");
        addErrno(10, "No child processes");
        addErrno(11, "Resource temporarily unavailable");
        addErrno(12, "Cannot allocate memory");
        addErrno(13, "Permission denied");
        addErrno(14, "Bad address");
        addErrno(15, "Block device required");
        addErrno(16, "Device or resource busy");
        addErrno(17, "File exists");
        addErrno(18, "Invalid cross-device link");
        addErrno(19, "No such device");
        addErrno(20, "Not a directory");
        addErrno(21, "Is a directory");
        addErrno(22, "Invalid argument");
        addErrno(23, "Too many open files in system");
        addErrno(24, "Too many open files");
        addErrno(25, "Inappropriate ioctl for device");
        addErrno(26, "Text file busy");
        addErrno(27, "File too large");
        addErrno(28, "No space left on device");
        addErrno(29, "Illegal seek");
        addErrno(30, "Read-only file system");
        addErrno(31, "Too many links");
        addErrno(32, "Broken pipe");
        addErrno(33, "Numerical argument out of domain");
        addErrno(34, "Numerical result out of range");
        addErrno(35, "Resource deadlock avoided");
        addErrno(36, "File name too long");
        addErrno(37, "No locks available");
        addErrno(38, "Function not implemented");
        addErrno(39, "Directory not empty");
        addErrno(40, "Too many levels of symbolic links");
        addErrno(48, "Address already in use");
        addErrno(49, "Cannot assign requested address");
        addErrno(61, "Connection refused");
        addErrno(111, "Connection refused");
        // Additional messages used in PerlOnJava code
        addErrno(5, "I/O error");
        addErrno(21, "Is a directory");
    }
    
    private static void addErrno(int code, String msg) {
        ERRNO_MESSAGES.put(code, msg);
        MESSAGE_TO_ERRNO.putIfAbsent(msg, code);
    }
    
    public ErrnoVariable() {
        super();
        this.type = RuntimeScalarType.DUALVAR;
        this.value = new DualVar(new RuntimeScalar(0), new RuntimeScalar(""));
    }
    
    /**
     * Set errno from an integer value.
     */
    @Override
    public RuntimeScalar set(int value) {
        this.errno = value;
        this.message = ERRNO_MESSAGES.getOrDefault(value, value == 0 ? "" : "Unknown error " + value);
        this.type = RuntimeScalarType.DUALVAR;
        this.value = new DualVar(new RuntimeScalar(value), new RuntimeScalar(this.message));
        return this;
    }
    
    /**
     * Set errno from a string value.
     * If the string is a known errno message, looks up and stores the errno code.
     * If the string is a number, treats it as errno code.
     * Otherwise, stores 0 as errno with the string as message.
     */
    @Override
    public RuntimeScalar set(String value) {
        if (value == null || value.isEmpty()) {
            this.errno = 0;
            this.message = "";
            this.type = RuntimeScalarType.DUALVAR;
            this.value = new DualVar(new RuntimeScalar(0), new RuntimeScalar(""));
            return this;
        }
        
        // Check if the string is a known errno message (reverse lookup)
        Integer code = MESSAGE_TO_ERRNO.get(value);
        if (code != null) {
            this.errno = code;
            this.message = value;
            this.type = RuntimeScalarType.DUALVAR;
            this.value = new DualVar(new RuntimeScalar(code), new RuntimeScalar(value));
            return this;
        }
        
        // Try to parse as integer
        try {
            int num = Integer.parseInt(value.trim());
            return set(num);
        } catch (NumberFormatException e) {
            // Not a number and not a known message - store as message with errno 0
            this.errno = 0;
            this.message = value;
            this.type = RuntimeScalarType.DUALVAR;
            this.value = new DualVar(new RuntimeScalar(0), new RuntimeScalar(value));
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

    // Stack to save errno/message during local()
    private static final java.util.Stack<int[]> errnoStack = new java.util.Stack<>();
    private static final java.util.Stack<String> messageStack = new java.util.Stack<>();

    @Override
    public void dynamicSaveState() {
        errnoStack.push(new int[]{errno});
        messageStack.push(message);
        super.dynamicSaveState();
    }

    @Override
    public void dynamicRestoreState() {
        super.dynamicRestoreState();
        if (!errnoStack.isEmpty()) {
            errno = errnoStack.pop()[0];
        }
        if (!messageStack.isEmpty()) {
            message = messageStack.pop();
        }
    }
}


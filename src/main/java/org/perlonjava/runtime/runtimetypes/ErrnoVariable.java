package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.nativ.ffm.FFMPosix;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents the special Perl variable $! (errno).
 * This variable is a dualvar - it has both a numeric value (errno number)
 * and a string value (error message).
 * 
 * When set to a number, it stores the errno and looks up the message.
 * When set to a string (known errno message), it looks up the errno code.
 * When set to an unknown string, it stores 0 as errno and the string as message.
 *
 * Errno messages are obtained from the native C strerror() function via FFM,
 * which ensures correct platform-specific messages on macOS, Linux, and Windows.
 * Results are cached lazily.
 *
 * Named errno constants (EINPROGRESS, etc.) are read from the Perl Errno module
 * at runtime, so they match the platform's header values.
 */
public class ErrnoVariable extends RuntimeScalar {
    
    private int errno = 0;
    private String message = "";
    
    // Lazy cache: errno number -> strerror() message
    private static final ConcurrentHashMap<Integer, String> STRERROR_CACHE = new ConcurrentHashMap<>();
    // Reverse map of messages to errno numbers (built lazily)
    private static final Map<String, Integer> MESSAGE_TO_ERRNO = new HashMap<>();

    // Named errno constants — populated lazily from Perl's Errno module
    private static volatile int _EINPROGRESS = -1;
    private static volatile int _ECONNREFUSED = -1;
    private static volatile int _ETIMEDOUT = -1;
    private static volatile int _ENETUNREACH = -1;
    private static volatile int _ECONNRESET = -1;
    private static volatile int _ECONNABORTED = -1;
    private static volatile int _EADDRINUSE = -1;
    private static volatile int _EADDRNOTAVAIL = -1;

    /**
     * Look up the strerror() message for a given errno, caching the result.
     */
    private static String nativeStrerror(int errnum) {
        return STRERROR_CACHE.computeIfAbsent(errnum, n -> {
            try {
                String msg = FFMPosix.get().strerror(n);
                if (msg != null && !msg.isEmpty() && !msg.startsWith("Unknown error")) {
                    MESSAGE_TO_ERRNO.putIfAbsent(msg, n);
                    return msg;
                }
            } catch (Exception ignored) {
            }
            return "Unknown error " + n;
        });
    }

    /**
     * Look up an errno constant by name from Perl's Errno module.
     * Falls back to -1 if not available.
     */
    private static int lookupErrnoConstant(String name) {
        try {
            RuntimeScalar result = GlobalVariable.getGlobalHash("Errno::err").get(name);
            if (result != null && result.getDefinedBoolean()) {
                return result.getInt();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    // Public accessors for named constants — lazy init from Errno module
    public static int EINPROGRESS() {
        int v = _EINPROGRESS;
        if (v == -1) { v = _EINPROGRESS = lookupErrnoConstant("EINPROGRESS"); }
        return v;
    }
    public static int ECONNREFUSED() {
        int v = _ECONNREFUSED;
        if (v == -1) { v = _ECONNREFUSED = lookupErrnoConstant("ECONNREFUSED"); }
        return v;
    }
    public static int ETIMEDOUT() {
        int v = _ETIMEDOUT;
        if (v == -1) { v = _ETIMEDOUT = lookupErrnoConstant("ETIMEDOUT"); }
        return v;
    }
    public static int ENETUNREACH() {
        int v = _ENETUNREACH;
        if (v == -1) { v = _ENETUNREACH = lookupErrnoConstant("ENETUNREACH"); }
        return v;
    }
    public static int ECONNRESET() {
        int v = _ECONNRESET;
        if (v == -1) { v = _ECONNRESET = lookupErrnoConstant("ECONNRESET"); }
        return v;
    }
    public static int ECONNABORTED() {
        int v = _ECONNABORTED;
        if (v == -1) { v = _ECONNABORTED = lookupErrnoConstant("ECONNABORTED"); }
        return v;
    }
    public static int EADDRINUSE() {
        int v = _EADDRINUSE;
        if (v == -1) { v = _EADDRINUSE = lookupErrnoConstant("EADDRINUSE"); }
        return v;
    }
    public static int EADDRNOTAVAIL() {
        int v = _EADDRNOTAVAIL;
        if (v == -1) { v = _EADDRNOTAVAIL = lookupErrnoConstant("EADDRNOTAVAIL"); }
        return v;
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
        this.message = value == 0 ? "" : nativeStrerror(value);
        this.type = RuntimeScalarType.INTEGER;
        this.value = value;
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


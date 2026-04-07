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
 * Named errno constants (EINPROGRESS, etc.) are resolved by probing native
 * strerror() to find which errno value produces the expected message pattern.
 * This works correctly on any POSIX platform without hardcoded values.
 */
public class ErrnoVariable extends RuntimeScalar {
    
    private int errno = 0;
    private String message = "";
    
    // Lazy cache: errno number -> strerror() message
    private static final ConcurrentHashMap<Integer, String> STRERROR_CACHE = new ConcurrentHashMap<>();
    // Reverse map of messages to errno numbers (built lazily)
    private static final Map<String, Integer> MESSAGE_TO_ERRNO = new HashMap<>();

    // Named errno constants — resolved lazily by probing native strerror()
    private static volatile int _EAGAIN = -1;
    private static volatile int _EINPROGRESS = -1;
    private static volatile int _ECONNREFUSED = -1;
    private static volatile int _ETIMEDOUT = -1;
    private static volatile int _ENETUNREACH = -1;
    private static volatile int _ECONNRESET = -1;
    private static volatile int _ECONNABORTED = -1;
    private static volatile int _EADDRINUSE = -1;
    private static volatile int _EADDRNOTAVAIL = -1;
    private static volatile int _EISCONN = -1;

    // Map of errno constant names to substring patterns in strerror() messages.
    // Used to probe the native strerror() and discover platform-correct values.
    private static final Map<String, String> ERRNO_MSG_PATTERNS = Map.ofEntries(
        Map.entry("EAGAIN", "resource temporarily unavailable"),
        Map.entry("EINPROGRESS", "in progress"),
        Map.entry("ECONNREFUSED", "connection refused"),
        Map.entry("ETIMEDOUT", "timed out"),
        Map.entry("ENETUNREACH", "network is unreachable"),
        Map.entry("ECONNRESET", "connection reset"),
        Map.entry("ECONNABORTED", "connection abort"),
        Map.entry("EADDRINUSE", "address already in use"),
        Map.entry("EADDRNOTAVAIL", "assign requested address"),
        Map.entry("EISCONN", "already connected")
    );

    // Cache of resolved errno constants (probed once, cached forever)
    private static final ConcurrentHashMap<String, Integer> ERRNO_CONSTANTS = new ConcurrentHashMap<>();

    // Whether the full MESSAGE_TO_ERRNO map has been populated
    private static volatile boolean messageMapPopulated = false;

    /**
     * Ensure the MESSAGE_TO_ERRNO map is fully populated by probing
     * strerror() for all errno values 1-200. Called lazily on first
     * reverse lookup (set(String)) to enable message-to-errno resolution.
     */
    static void ensureMessageMapPopulated() {
        if (!messageMapPopulated) {
            synchronized (MESSAGE_TO_ERRNO) {
                if (!messageMapPopulated) {
                    for (int i = 1; i <= 250; i++) {
                        nativeStrerror(i);
                    }
                    messageMapPopulated = true;
                }
            }
        }
    }

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
     * Look up an errno constant by probing native strerror().
     * Scans errno values 1-200 looking for a message that matches the
     * expected pattern for the given constant name.
     * Returns 0 if the constant cannot be resolved.
     */
    private static int lookupErrnoConstant(String name) {
        return ERRNO_CONSTANTS.computeIfAbsent(name, n -> {
            String pattern = ERRNO_MSG_PATTERNS.get(n);
            if (pattern == null) return 0;
            String lowerPattern = pattern.toLowerCase();
            for (int i = 1; i <= 250; i++) {
                String msg = nativeStrerror(i);
                if (msg.toLowerCase().contains(lowerPattern)) {
                    return i;
                }
            }
            return 0;
        });
    }

    // Public accessors for named constants — lazy init by probing strerror
    public static int EAGAIN() {
        int v = _EAGAIN;
        if (v == -1) { v = _EAGAIN = lookupErrnoConstant("EAGAIN"); }
        return v;
    }
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
    public static int EISCONN() {
        int v = _EISCONN;
        if (v == -1) { v = _EISCONN = lookupErrnoConstant("EISCONN"); }
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
        // Must use DUALVAR so reference dereference paths that read type/value
        // directly will see the string message, not just the numeric errno.
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
        
        // Ensure the message-to-errno map is fully populated before reverse lookup
        ensureMessageMapPopulated();
        
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
            // Always maintain INTEGER type so numeric operations use the fast path
            // and never trigger "isn't numeric" warnings via NumberParser.parseNumber()
            this.errno = 0;
            this.message = value;
            this.type = RuntimeScalarType.INTEGER;
            this.value = 0;
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
     * Get the numeric value as RuntimeScalar.
     * ErrnoVariable is a dualvar — numeric context always returns the errno number
     * without going through string parsing (no "isn't numeric" warning).
     */
    @Override
    public RuntimeScalar getNumber() {
        return RuntimeScalarCache.getScalarInt(errno);
    }
    
    /**
     * Get the numeric value with uninitialized warning check.
     * ErrnoVariable is always "defined" numerically, so no warning is emitted.
     */
    @Override
    public RuntimeScalar getNumberWarn(String operation) {
        return RuntimeScalarCache.getScalarInt(errno);
    }
    
    /**
     * Get the numeric value as long.
     * Ensures numeric operations bypass string parsing.
     */
    @Override
    public long getLong() {
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


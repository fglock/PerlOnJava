package org.perlonjava.runtime.runtimetypes;

import java.util.List;
import java.util.Set;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;

/**
 * Special hash for %SIG that auto-qualifies signal handler names with the caller's package.
 * In Perl, assigning a string handler to a known signal entry (like $SIG{INT} = "handler")
 * automatically qualifies it to "main::handler". This only applies to known signal names
 * (__WARN__, __DIE__, and OS signals), not to unknown names.
 *
 * <p>Like Perl, %SIG is pre-populated with OS signal names as keys (with undef values).
 * This allows modules like POE to discover available signals via {@code keys %SIG}.
 */
public class RuntimeSigHash extends RuntimeHash {

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private static final Set<String> KNOWN_SIGNALS;

    static {
        if (IS_WINDOWS) {
            // Windows only supports a limited set of signals
            KNOWN_SIGNALS = Set.of(
                    "__WARN__", "__DIE__",
                    "INT", "ILL", "ABRT", "FPE", "SEGV", "TERM", "BREAK",
                    "ZERO"
            );
        } else {
            KNOWN_SIGNALS = Set.of(
                    "__WARN__", "__DIE__",
                    "HUP", "INT", "QUIT", "ILL", "TRAP", "ABRT", "BUS", "FPE",
                    "KILL", "USR1", "SEGV", "USR2", "PIPE", "ALRM", "TERM",
                    "STKFLT", "CHLD", "CLD", "CONT", "STOP", "TSTP", "TTIN",
                    "TTOU", "URG", "XCPU", "XFSZ", "VTALRM", "PROF", "WINCH",
                    "IO", "PWR", "SYS", "EMT", "INFO", "ZERO", "NUM32", "NUM33"
            );
        }
    }

    // Standard POSIX signals present on most Unix systems
    private static final List<String> POSIX_SIGNALS = List.of(
            "HUP", "INT", "QUIT", "ILL", "TRAP", "ABRT", "BUS", "FPE",
            "KILL", "USR1", "SEGV", "USR2", "PIPE", "ALRM", "TERM",
            "CHLD", "CONT", "STOP", "TSTP", "TTIN", "TTOU", "URG",
            "XCPU", "XFSZ", "VTALRM", "PROF", "WINCH", "IO", "SYS"
    );

    // macOS-specific signals
    private static final List<String> MACOS_SIGNALS = List.of("EMT", "INFO", "IOT");

    // Linux-specific signals
    private static final List<String> LINUX_SIGNALS = List.of("CLD", "STKFLT", "PWR", "IOT");

    // Windows-only signals
    private static final List<String> WINDOWS_SIGNALS = List.of(
            "INT", "TERM", "ABRT", "FPE", "ILL", "SEGV", "BREAK"
    );

    /**
     * Construct a pre-populated %SIG hash. Like Perl, all available OS signal
     * names appear as keys with undef values. __WARN__ and __DIE__ are Perl
     * hooks and are NOT pre-populated (they only appear after being set).
     */
    public RuntimeSigHash() {
        super();
        if (IS_WINDOWS) {
            for (String sig : WINDOWS_SIGNALS) {
                elements.put(sig, new RuntimeScalar());
            }
        } else {
            // Pre-populate with POSIX signals
            for (String sig : POSIX_SIGNALS) {
                elements.put(sig, new RuntimeScalar());
            }
            // Add platform-specific signals
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("mac") || os.contains("darwin")) {
                for (String sig : MACOS_SIGNALS) {
                    elements.put(sig, new RuntimeScalar());
                }
            } else if (os.contains("linux")) {
                for (String sig : LINUX_SIGNALS) {
                    elements.put(sig, new RuntimeScalar());
                }
            }
        }
    }

    /**
     * Get an element by key, auto-qualifying string handler values for known signals.
     */
    @Override
    public RuntimeScalar get(String key) {
        if (type == TIED_HASH) {
            return get(new RuntimeScalar(key));
        }
        var value = elements.get(key);
        if (value != null) {
            qualifyIfNeeded(key, value);
            return value;
        }
        return new RuntimeHashProxyEntry(this, key);
    }

    /**
     * Get an element by RuntimeScalar key, auto-qualifying string handler values for known signals.
     */
    @Override
    public RuntimeScalar get(RuntimeScalar keyScalar) {
        return switch (this.type) {
            case PLAIN_HASH, AUTOVIVIFY_HASH -> {
                String key = keyScalar.toString();
                var value = elements.get(key);
                if (value != null) {
                    qualifyIfNeeded(key, value);
                    yield value;
                }
                yield new RuntimeHashProxyEntry(this, key);
            }
            case TIED_HASH -> {
                RuntimeScalar v = new RuntimeScalar();
                v.type = TIED_SCALAR;
                v.value = new RuntimeTiedHashProxyEntry(this, keyScalar);
                yield v;
            }
            default -> throw new IllegalStateException("Unknown hash type: " + this.type);
        };
    }

    /**
     * If the key is a known signal and the value is a plain string identifier
     * that isn't already qualified, qualify it with "main::".
     * Modifies the value in place so subsequent reads and deletes see the qualified name.
     */
    private void qualifyIfNeeded(String key, RuntimeScalar value) {
        if (!KNOWN_SIGNALS.contains(key)) {
            return;
        }
        if (value.type != STRING && value.type != BYTE_STRING) {
            return;
        }
        String s = value.toString();
        if (s.isEmpty() || s.contains("::")) {
            return;
        }
        if ("IGNORE".equals(s) || "DEFAULT".equals(s)) {
            return;
        }
        // Only qualify valid Perl identifiers
        if (isIdentifier(s)) {
            value.set("main::" + s);
        }
    }

    /**
     * Check if a string looks like a valid Perl identifier (for auto-qualification).
     */
    private static boolean isIdentifier(String s) {
        if (s.isEmpty()) return false;
        char c = s.charAt(0);
        if (c != '_' && !Character.isLetter(c)) return false;
        for (int i = 1; i < s.length(); i++) {
            c = s.charAt(i);
            if (c != '_' && !Character.isLetterOrDigit(c)) return false;
        }
        return true;
    }
}

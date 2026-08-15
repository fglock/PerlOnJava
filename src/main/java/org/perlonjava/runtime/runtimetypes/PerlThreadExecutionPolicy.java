package org.perlonjava.runtime.runtimetypes;

import java.util.Locale;
import java.util.Objects;

/** Selects the Java thread implementation used to execute Perl ithreads. */
public final class PerlThreadExecutionPolicy {
    static final String MODE_PROPERTY = "jperl.thread.mode";
    static final String MODE_ENVIRONMENT = "JPERL_THREAD_MODE";
    private static final PerlThreadExecutionPolicy CONFIGURED =
            resolve(System.getProperty(MODE_PROPERTY), System.getenv(MODE_ENVIRONMENT));

    public enum Mode { PLATFORM, VIRTUAL }

    private final Mode mode;

    private PerlThreadExecutionPolicy(Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    /** Resolve the process-wide opt-in. The stable default remains platform threads. */
    public static PerlThreadExecutionPolicy configured() {
        return CONFIGURED;
    }

    static PerlThreadExecutionPolicy resolve(String propertyValue, String environmentValue) {
        String value = propertyValue != null ? propertyValue : environmentValue;
        if (value == null || value.isBlank()) return new PerlThreadExecutionPolicy(Mode.PLATFORM);

        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "platform" -> new PerlThreadExecutionPolicy(Mode.PLATFORM);
            case "virtual" -> new PerlThreadExecutionPolicy(Mode.VIRTUAL);
            default -> throw new IllegalArgumentException(
                    "Unsupported Perl thread mode '" + value + "'; expected platform or virtual");
        };
    }

    public Mode mode() {
        return mode;
    }

    /** Select a platform carrier when Perl requests an explicit stack size. */
    PerlThreadExecutionPolicy effectiveForStackSize(long stackSize) {
        if (stackSize < 0) throw new IllegalArgumentException("Thread stack size must not be negative");
        if (mode == Mode.VIRTUAL && stackSize != 0) {
            return new PerlThreadExecutionPolicy(Mode.PLATFORM);
        }
        return this;
    }

    public Thread unstarted(long id, Runnable task) {
        return unstarted(id, 0, task);
    }

    public Thread unstarted(long id, long stackSize, Runnable task) {
        Objects.requireNonNull(task, "task");
        if (stackSize < 0) throw new IllegalArgumentException("Thread stack size must not be negative");
        String name = "perl-ithread-" + id;
        if (mode == Mode.VIRTUAL) {
            if (stackSize != 0) {
                throw new IllegalArgumentException(
                        "Per-thread stack sizing is not supported by Java virtual threads");
            }
            return Thread.ofVirtual().name(name).unstarted(task);
        }
        // Perl terminates detached children when the main interpreter exits.
        // A Java daemon carrier preserves that process-lifecycle rule; attached
        // children remain application-owned through the Perl registry/join API.
        Thread.Builder.OfPlatform builder = Thread.ofPlatform().name(name).daemon(true);
        if (stackSize != 0) builder = builder.stackSize(stackSize);
        return builder.unstarted(task);
    }
}

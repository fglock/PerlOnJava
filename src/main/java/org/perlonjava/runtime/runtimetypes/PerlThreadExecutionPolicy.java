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

    public Thread unstarted(long id, Runnable task) {
        Objects.requireNonNull(task, "task");
        String name = "perl-ithread-" + id;
        return mode == Mode.VIRTUAL
                ? Thread.ofVirtual().name(name).unstarted(task)
                : Thread.ofPlatform().name(name).unstarted(task);
    }
}

package org.perlonjava.runtime.runtimetypes;

import java.util.Locale;
import java.util.Objects;

/**
 * Per-runtime LC_CTYPE publication state.
 *
 * <p>This is deliberately independent of {@link Locale#setDefault(Locale)}:
 * changing Perl's locale must neither mutate the JVM process default nor leak
 * into another managed Perl runtime.  Regex matchers take immutable snapshots
 * of this state at construction time, so a later {@code POSIX::setlocale}
 * affects the next match without changing an in-flight match.</p>
 */
public final class RuntimeLocaleState {
    private String ctypeName = "C";
    private long generation;

    public record Snapshot(String ctypeName, long generation) {}

    public synchronized Snapshot snapshot() {
        return new Snapshot(ctypeName, generation);
    }

    public synchronized String currentCtype() {
        return ctypeName;
    }

    /** Publishes an already-validated LC_CTYPE name and returns it. */
    public synchronized String publishCtype(String localeName) {
        String normalized = normalize(localeName);
        if (!Objects.equals(ctypeName, normalized)) {
            ctypeName = normalized;
            generation++;
        }
        return ctypeName;
    }

    void snapshotInto(RuntimeLocaleState target) {
        Snapshot snapshot = snapshot();
        target.ctypeName = snapshot.ctypeName;
        target.generation = snapshot.generation;
    }

    private static String normalize(String localeName) {
        if (localeName == null || localeName.isBlank()) return "C";
        return localeName.trim();
    }
}

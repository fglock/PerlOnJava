package org.perlonjava.runtime.runtimetypes;

import java.util.Locale;
import java.util.Objects;

/**
 * Per-runtime LC_CTYPE publication state.
 *
 * <p>This is deliberately independent of {@link Locale#setDefault(Locale)}:
 * changing Perl's locale must neither mutate the JVM process default nor leak
 * into another managed Perl runtime. Regex matchers retain this runtime-owned
 * publication object (never process-global locale state), so a
 * {@code POSIX::setlocale} performed by an embedded regex code block is visible
 * to subsequent operations in that same match, as it is in Perl.</p>
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

package org.perlonjava.runtime.runtimetypes;

import java.util.Locale;
import java.util.Objects;

/**
 * Per-runtime locale publication state.
 *
 * <p>This is deliberately independent of {@link Locale#setDefault(Locale)}:
 * changing Perl's locale must neither mutate the JVM process default nor leak
 * into another managed Perl runtime. Regex matchers retain this runtime-owned
 * publication object (never process-global locale state), so a
 * {@code POSIX::setlocale} performed by an embedded regex code block is visible
 * to subsequent operations in that same match, as it is in Perl.</p>
 */
public final class RuntimeLocaleState {
    private String ctypeName = environmentCtype();
    private String timeName = environmentTime();
    private long generation;

    public record Snapshot(String ctypeName, String timeName, long generation) {}

    public synchronized Snapshot snapshot() {
        return new Snapshot(ctypeName, timeName, generation);
    }

    public synchronized String currentCtype() {
        return ctypeName;
    }

    public synchronized String currentTime() {
        return timeName;
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

    /** Publishes an already-validated LC_TIME name and returns it. */
    public synchronized String publishTime(String localeName) {
        timeName = normalize(localeName);
        return timeName;
    }

    /** LC_ALL updates the categories represented by this runtime state. */
    public synchronized String publishAll(String localeName) {
        String normalized = normalize(localeName);
        publishCtype(normalized);
        publishTime(normalized);
        return normalized;
    }

    void snapshotInto(RuntimeLocaleState target) {
        Snapshot snapshot = snapshot();
        target.ctypeName = snapshot.ctypeName;
        target.timeName = snapshot.timeName;
        target.generation = snapshot.generation;
    }

    private static String normalize(String localeName) {
        if (localeName == null || localeName.isBlank()) return environmentCtype();
        return localeName.trim();
    }

    private static String environmentCtype() {
        for (String variable : new String[] { "LC_ALL", "LC_CTYPE", "LANG" }) {
            String value = System.getenv(variable);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "C";
    }

    private static String environmentTime() {
        for (String variable : new String[] { "LC_ALL", "LC_TIME", "LANG" }) {
            String value = System.getenv(variable);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "C";
    }
}

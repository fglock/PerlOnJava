package org.perlonjava.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable lexical results for the named-character escapes in one regex literal.
 *
 * <p>The parser attaches this value to the literal AST after executing a custom
 * {@code %^H{charnames}} translator. Backends carry the same typed value into
 * the compiled CV, allowing an ithread child to compile the literal without
 * executing or sharing the parent's lexical callable.</p>
 */
public record NamedCharacterExpansionMap(
        LiteralIdentity literalIdentity,
        CallableIdentity callableIdentity,
        Map<Key, NamedCharacterExpansion> expansions) {

    public record LiteralIdentity(String value) {}

    public record CallableIdentity(String value) {}

    public record Key(
            String sourceSpelling,
            NamedCharacterExpansion.SourceMode sourceMode) {}

    public NamedCharacterExpansionMap {
        expansions = Collections.unmodifiableMap(new LinkedHashMap<>(expansions));
    }

    public NamedCharacterExpansion get(
            String sourceSpelling, NamedCharacterExpansion.SourceMode sourceMode) {
        return expansions.get(new Key(sourceSpelling, sourceMode));
    }

    public boolean isEmpty() {
        return expansions.isEmpty();
    }

    /** Lexical source mode captured before literal scalar materialization. */
    public NamedCharacterExpansion.SourceMode sourceMode() {
        return expansions.isEmpty() ? null
                : expansions.keySet().iterator().next().sourceMode();
    }

    /** Rebuild a typed value from constants carried by generated JVM bytecode. */
    public static NamedCharacterExpansionMap fromFlat(
            String literalIdentity, String callableIdentity, String[] flat) {
        if (flat.length % 7 != 0) {
            throw new IllegalArgumentException("invalid named-character metadata");
        }
        Map<Key, NamedCharacterExpansion> entries = new LinkedHashMap<>();
        for (int index = 0; index < flat.length; index += 7) {
            NamedCharacterExpansion.SourceMode keyMode =
                    NamedCharacterExpansion.SourceMode.valueOf(flat[index + 1]);
            entries.put(new Key(flat[index], keyMode), new NamedCharacterExpansion(
                    flat[index + 2],
                    NamedCharacterExpansion.SourceMode.valueOf(flat[index + 3]),
                    Boolean.parseBoolean(flat[index + 4]),
                    NamedCharacterExpansion.Status.valueOf(flat[index + 5]),
                    flat[index + 6]));
        }
        return new NamedCharacterExpansionMap(
                new LiteralIdentity(literalIdentity),
                new CallableIdentity(callableIdentity), entries);
    }
}

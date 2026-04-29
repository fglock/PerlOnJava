package org.perlonjava.runtime.perlmodule.storable;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/**
 * Stub for the {@code SX_REGEXP} encoder.
 * <p>
 * <strong>Owner: regexp-agent.</strong>
 * <p>
 * Wire format (Storable.xs {@code store_regexp}, search for
 * {@code SX_REGEXP}):
 * <pre>
 *   SX_REGEXP &lt;pat-len&gt; &lt;pat-bytes&gt; &lt;flags-len&gt; &lt;flags-bytes&gt;
 * </pre>
 * Both lengths use the small/large convention (1 byte if &le;
 * {@link Opcodes#LG_SCALAR LG_SCALAR}; otherwise high-bit + U32).
 * <p>
 * Source the pattern + flags from the
 * {@code RuntimeRegex} held in {@code v.value} when
 * {@code v.type == RuntimeScalarType.REGEX}. See
 * {@code src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeRegex.java}
 * for accessors.
 * <p>
 * The corresponding read side stub is {@link Misc#readRegexp}; the
 * regexp-agent owns both. Tests gate is enabling
 * {@code regexp.t} in {@code dev/import-perl5/config.yaml}.
 */
public final class RegexpEncoder {
    private RegexpEncoder() {}

    /** Emit {@code SX_REGEXP} followed by the pattern + flags bytes. */
    public static void write(StorableContext c, RuntimeScalar v) {
        throw new StorableFormatException("regexp-agent: SX_REGEXP encoder not yet implemented");
    }
}

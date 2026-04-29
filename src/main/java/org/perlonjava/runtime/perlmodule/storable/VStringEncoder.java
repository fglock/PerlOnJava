package org.perlonjava.runtime.perlmodule.storable;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/**
 * Stub for the {@code SX_VSTRING} / {@code SX_LVSTRING} encoder.
 * <p>
 * <strong>Owner: vstring-agent.</strong>
 * <p>
 * Wire format (Storable.xs {@code retrieve_vstring} L5833, {@code
 * retrieve_lvstring} L5864):
 * <pre>
 *   SX_VSTRING  &lt;vstr-len 1 byte&gt;  &lt;vstr-bytes&gt;  &lt;regular scalar body&gt;
 *   SX_LVSTRING &lt;vstr-len U32&gt;     &lt;vstr-bytes&gt;  &lt;regular scalar body&gt;
 * </pre>
 * The v-string bytes come <em>first</em>, then a recursive scalar
 * opcode for the textual scalar (typically SX_SCALAR/SX_LSCALAR with
 * the same bytes). On retrieve, the regular scalar gets the v-string
 * magic attached.
 * <p>
 * Source the v-string portion from the {@code RuntimeScalar} when
 * {@code v.type == RuntimeScalarType.VSTRING}. See
 * {@code src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeScalar.java}
 * for the v-string accessor (likely a method that returns the
 * v-string bytes distinct from {@code toString()}).
 * <p>
 * The corresponding read-side stubs are {@link Misc#readVString} and
 * {@link Misc#readLVString}; the vstring-agent owns both.
 */
public final class VStringEncoder {
    private VStringEncoder() {}

    /** Emit SX_VSTRING / SX_LVSTRING + body. */
    public static void write(StorableContext c, RuntimeScalar v) {
        throw new StorableFormatException("vstring-agent: SX_VSTRING encoder not yet implemented");
    }
}

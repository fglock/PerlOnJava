package org.perlonjava.runtime.perlmodule.storable;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;
import org.perlonjava.runtime.runtimetypes.TieArray;
import org.perlonjava.runtime.runtimetypes.TieHash;
import org.perlonjava.runtime.runtimetypes.TieScalar;

/**
 * Tied-container encoder
 * ({@code SX_TIED_ARRAY} / {@code SX_TIED_HASH} / {@code SX_TIED_SCALAR}).
 * <p>
 * Wire format (Storable.xs L5502-L5610):
 * <pre>
 *   SX_TIED_ARRAY  &lt;object&gt;
 *   SX_TIED_HASH   &lt;object&gt;
 *   SX_TIED_SCALAR &lt;object&gt;
 * </pre>
 * The body is a single recursive opcode tree producing the tying
 * object (typically a blessed ref to whatever class implements TIE*).
 * <p>
 * Detection looks at the underlying {@code RuntimeArray}/
 * {@code RuntimeHash}/{@code RuntimeScalar} held in
 * {@code refScalar.value}: PerlOnJava marks tied containers by
 * setting their {@code type} field to one of {@code TIED_ARRAY},
 * {@code TIED_HASH}, or {@code TIED_SCALAR} and storing a
 * {@link TieArray}/{@link TieHash}/{@link TieScalar} in
 * {@code elements}/{@code value}. Those Tie* objects expose the
 * tying object via {@code getSelf()}.
 */
public final class TiedEncoder {
    private TiedEncoder() {}

    /** Detect tied magic and emit SX_TIED_*. Returns true if the
     *  encoder consumed {@code refScalar} (caller should not fall
     *  through to plain bless / container body). */
    public static boolean tryEmit(StorableContext c, RuntimeScalar refScalar,
                                  StorableWriter writer) {
        Object value = refScalar.value;
        RuntimeScalar tying = null;
        int opcode = 0;

        if (refScalar.type == RuntimeScalarType.ARRAYREFERENCE
                && value instanceof RuntimeArray av
                && av.type == RuntimeArray.TIED_ARRAY
                && av.elements instanceof TieArray ta) {
            tying = ta.getSelf();
            opcode = Opcodes.SX_TIED_ARRAY;
        } else if (refScalar.type == RuntimeScalarType.HASHREFERENCE
                && value instanceof RuntimeHash hv
                && hv.type == RuntimeHash.TIED_HASH
                && hv.elements instanceof TieHash th) {
            tying = th.getSelf();
            opcode = Opcodes.SX_TIED_HASH;
        } else if (refScalar.type == RuntimeScalarType.REFERENCE
                && value instanceof RuntimeScalar inner
                && inner.type == RuntimeScalarType.TIED_SCALAR
                && inner.value instanceof TieScalar ts) {
            tying = ts.getSelf();
            opcode = Opcodes.SX_TIED_SCALAR;
        }

        if (tying == null) {
            return false;
        }

        c.writeByte(opcode);
        // Register the seen-tag for the tied container so that
        // backref tags inside the tying object can resolve to it.
        // We key on the underlying AV/HV/scalar to mirror the
        // sharedKey logic in StorableWriter.
        c.recordWriteSeen(value);
        writer.dispatch(c, tying);
        return true;
    }
}

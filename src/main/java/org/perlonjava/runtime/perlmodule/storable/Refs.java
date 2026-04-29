package org.perlonjava.runtime.perlmodule.storable;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/**
 * Reference opcode readers/writers (regular, weak, and overloaded
 * variants), plus the backref opcode {@link Opcodes#SX_OBJECT}.
 * <p>
 * <strong>OWNER: refs-agent</strong>
 * <p>
 * Opcodes covered (Storable.xs L141-177):
 * <ul>
 *   <li>{@link Opcodes#SX_REF} — body is a recursive opcode tree
 *       producing a value; result is a reference to that value.
 *       See {@code retrieve_ref} (Storable.xs L5321).</li>
 *   <li>{@link Opcodes#SX_WEAKREF} — like SX_REF + weaken().</li>
 *   <li>{@link Opcodes#SX_OVERLOAD} — like SX_REF + bless preserves
 *       overload magic; the wrapped value must be a blessed ref.</li>
 *   <li>{@link Opcodes#SX_WEAKOVERLOAD} — combination.</li>
 *   <li>{@link Opcodes#SX_OBJECT} — backref. Body is a U32 tag (file
 *       byte order: see {@link StorableContext#readU32Length()}).
 *       Returns {@link StorableContext#getSeen(long)} <em>without</em>
 *       calling recordSeen — the original was already counted.</li>
 * </ul>
 * <p>
 * <strong>Important seen-table semantics for refs.</strong> See
 * {@code retrieve_ref} in Storable.xs: the SV that holds the reference
 * itself is registered first (with a placeholder), then the referent
 * is retrieved, then the ref is plumbed to point at it. This matters
 * because the referent may itself contain a backref to the ref.
 * Easier shape in our codebase: allocate a RuntimeScalar that will
 * become the ref, recordSeen it, then dispatch the body, then set the
 * ref to point at the result.
 */
public final class Refs {
    private Refs() {}

    /** SX_OBJECT body: read U32 tag, return the seen entry as-is.
     *  Does NOT recordSeen (the original entry already counted). */
    public static RuntimeScalar readObject(StorableReader r, StorableContext c) {
        long tag = c.readU32Length();
        return c.getSeen(tag);
    }

    public static RuntimeScalar readRef(StorableReader r, StorableContext c) {
        throw new StorableFormatException("refs-agent: SX_REF not yet implemented");
    }

    public static RuntimeScalar readWeakRef(StorableReader r, StorableContext c) {
        throw new StorableFormatException("refs-agent: SX_WEAKREF not yet implemented");
    }

    public static RuntimeScalar readOverload(StorableReader r, StorableContext c) {
        throw new StorableFormatException("refs-agent: SX_OVERLOAD not yet implemented");
    }

    public static RuntimeScalar readWeakOverload(StorableReader r, StorableContext c) {
        throw new StorableFormatException("refs-agent: SX_WEAKOVERLOAD not yet implemented");
    }
}

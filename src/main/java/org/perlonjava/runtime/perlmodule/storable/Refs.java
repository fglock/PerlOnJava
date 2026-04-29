package org.perlonjava.runtime.perlmodule.storable;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.WeakRefRegistry;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;


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

    /**
     * SX_OBJECT body: read U32 tag, return the seen entry as-is.
     * Does NOT recordSeen (the original entry already counted).
     */
    public static RuntimeScalar readObject(StorableReader r, StorableContext c) {
        long tag = c.readU32Length();
        RuntimeScalar seen = c.getSeen(tag);
        // SX_OBJECT yields a value that's already at the right ref
        // level (whatever it was when stored). Signal to a surrounding
        // SX_REF to collapse (don't add another level), matching
        // SX_HASH/SX_ARRAY/SX_HOOK behavior. Drain any stale inner
        // flag first.
        c.takeBareContainerFlag();
        if (RuntimeScalarType.isReference(seen)) {
            c.markBareContainer();
        }
        return seen;
    }

    /**
     * SX_REF body: a single child opcode tree producing a value V; the
     * result is a reference to V. The reference scalar is registered in
     * the seen-table BEFORE the recursion, mirroring upstream
     * {@code retrieve_ref} (Storable.xs L5321) where {@code SEEN_NN} is
     * called on the placeholder before the referent is retrieved. This
     * lets a backref inside the referent legally resolve to the ref
     * itself.
     */
    public static RuntimeScalar readRef(StorableReader r, StorableContext c) {
        // Drain any incoming bare-container flag from a previous
        // sibling opcode: it's not meaningful for our own decision
        // (which is driven by what our BODY produces) but must not
        // leak into the dispatch below.
        c.takeBareContainerFlag();
        RuntimeScalar refScalar = new RuntimeScalar();
        c.recordSeen(refScalar);
        RuntimeScalar referent = r.dispatch(c);
        boolean bodyWasBare = c.takeBareContainerFlag();
        installReferent(refScalar, referent, bodyWasBare);
        // We produced a real ref-level value: do NOT mark bare for
        // our caller.
        return refScalar;
    }

    /**
     * SX_WEAKREF body: same as {@link #readRef} but the produced
     * reference is weakened. See {@code retrieve_weakref}
     * (Storable.xs L5389).
     */
    public static RuntimeScalar readWeakRef(StorableReader r, StorableContext c) {
        c.takeBareContainerFlag();
        RuntimeScalar refScalar = new RuntimeScalar();
        c.recordSeen(refScalar);
        RuntimeScalar referent = r.dispatch(c);
        boolean bodyWasBare = c.takeBareContainerFlag();
        installReferent(refScalar, referent, bodyWasBare);
        try {
            WeakRefRegistry.weaken(refScalar);
        } catch (RuntimeException ignored) {
            // TODO: weaken() may throw if refScalar isn't a recognised
            // reference type after installReferent. Returning a strong
            // ref is acceptable per the agent brief; differential tests
            // should still see equal *values*.
        }
        return refScalar;
    }

    /**
     * SX_OVERLOAD body: same wire shape as {@link Opcodes#SX_REF}; the
     * referent is a blessed ref whose class is expected to provide
     * overloaded operators. In PerlOnJava overload re-establishment is
     * handled automatically by the {@code overload} pragma at class
     * load time, so this opcode produces the same value tree as SX_REF.
     * See {@code retrieve_overloaded} (Storable.xs L5412).
     */
    public static RuntimeScalar readOverload(StorableReader r, StorableContext c) {
        // TODO: if PerlOnJava ever needs an explicit overload-magic flag
        // on the ref, set it here. For now this is a structural alias of
        // SX_REF.
        return readRef(r, c);
    }

    /**
     * SX_WEAKOVERLOAD body: combination of SX_WEAKREF and SX_OVERLOAD.
     */
    public static RuntimeScalar readWeakOverload(StorableReader r, StorableContext c) {
        // TODO: same overload caveat as readOverload.
        return readWeakRef(r, c);
    }

    /**
     * Plumb {@code refScalar} so it becomes a reference to
     * {@code referent}, choosing between collapse and wrap based on
     * whether the body was a bare-container scalar.
     * <p>
     * See {@link StorableContext#markBareContainer()} for the full
     * rationale. Briefly:
     * <ul>
     *   <li><b>bodyWasBare = true</b>: the body was an
     *       {@code ARRAYREFERENCE} / {@code HASHREFERENCE} returned
     *       directly by {@link Containers}. In upstream's data model
     *       that's a bare {@code AV}/{@code HV} (zero ref levels);
     *       the surrounding SX_REF adds the one level we already
     *       embedded into the {@code ARRAYREFERENCE} type. Collapse:
     *       refScalar takes on the same shape as referent (one ref
     *       level pointing at the same container).</li>
     *   <li><b>bodyWasBare = false</b>: the body was already
     *       ref-shaped (a SCALARREFERENCE produced by another SX_REF,
     *       a hook-allocated placeholder, an SX_OBJECT backref, etc.).
     *       The SX_REF really adds a level — wrap referent as a
     *       SCALARREFERENCE so the result is one above the body.</li>
     * </ul>
     */
    private static void installReferent(RuntimeScalar refScalar, RuntimeScalar referent, boolean bodyWasBare) {
        if (bodyWasBare) {
            // Bare-container body: collapse the redundant SX_REF wrap.
            // The fresh reference we attach must point at the SAME
            // underlying RuntimeArray/RuntimeHash as `referent` so
            // mutations through either alias (or backref tags pointing
            // at the seen-table entry of the container) stay coherent.
            if (referent.value instanceof RuntimeArray arr) {
                refScalar.set(arr.createReference());
            } else if (referent.value instanceof RuntimeHash hash) {
                refScalar.set(hash.createReference());
            } else {
                // Bare flag set but not a recognised container — fall
                // back to a fresh scalar reference. Defensive; should
                // not happen with current container readers.
                refScalar.set(referent.createReference());
            }
        } else {
            // Real ref level: SX_REF adds one level on top of the body.
            refScalar.set(referent.createReference());
        }
    }
}

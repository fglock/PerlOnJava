package org.perlonjava.runtime.perlmodule.storable;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.nio.charset.StandardCharsets;

/**
 * Container opcode readers/writers (arrays, hashes, flag-hashes).
 * <p>
 * <strong>OWNER: containers-agent</strong>
 * <p>
 * Opcodes covered (Storable.xs L141-177):
 * <ul>
 *   <li>{@link Opcodes#SX_ARRAY} &mdash; U32 size + {@code size} child
 *       opcode trees. See {@code retrieve_array} (Storable.xs L6247).</li>
 *   <li>{@link Opcodes#SX_HASH} &mdash; U32 size + {@code size} pairs of
 *       (value-tree, U32 keylen, key-bytes). The value comes
 *       <em>before</em> the key on the wire. See {@code retrieve_hash}
 *       (Storable.xs L6439).</li>
 *   <li>{@link Opcodes#SX_FLAG_HASH} &mdash; 1 byte global flags + U32 size
 *       + {@code size} triplets of (value-tree, 1-byte flags, U32 keylen,
 *       key). Per-key flags include {@code SHV_K_UTF8} = 0x01.</li>
 *   <li>{@link Opcodes#SX_SVUNDEF_ELEM} &mdash; placeholder for an array
 *       slot that was {@code &PL_sv_undef}; for our purposes equivalent
 *       to {@code SX_UNDEF}.</li>
 * </ul>
 * <p>
 * <strong>Seen-table:</strong> the container scalar is registered
 * <em>before</em> recursing into its children, so that backreferences
 * inside the children can resolve to the in-progress container.
 * <p>
 * <strong>Container as a scalar.</strong> Storable's {@code retrieve_array}
 * / {@code retrieve_hash} return the AV/HV body itself; the surrounding
 * {@code SX_REF} is what creates a reference. PerlOnJava cannot put a
 * bare {@code RuntimeArray}/{@code RuntimeHash} into a {@code RuntimeScalar}
 * other than via the reference types {@code ARRAYREFERENCE} /
 * {@code HASHREFERENCE}, so we return a ref-shaped scalar here. The
 * surrounding {@code SX_REF} reader (refs-agent) is responsible for
 * unwrapping/passing through accordingly.
 */
public final class Containers {
    private Containers() {}

    // Per-key flag bits in SX_FLAG_HASH; mirrors SHV_K_* in Storable.xs.
    private static final int SHV_K_UTF8 = 0x01;
    @SuppressWarnings("unused") private static final int SHV_K_WASUTF8     = 0x02;
    @SuppressWarnings("unused") private static final int SHV_K_LOCKED      = 0x04;
    @SuppressWarnings("unused") private static final int SHV_K_PLACEHOLDER = 0x08;

    /**
     * Read an {@code SX_ARRAY} body: a {@code U32} element count followed
     * by that many child opcode trees. Returns a {@code RuntimeScalar}
     * carrying the freshly-built {@link RuntimeArray} (as an
     * {@code ARRAYREFERENCE}). The container scalar is recorded in the
     * seen table before child recursion.
     */
    public static RuntimeScalar readArray(StorableReader r, StorableContext c) {
        long size = c.readU32Length();
        if (size < 0 || size > Integer.MAX_VALUE) {
            throw new StorableFormatException("SX_ARRAY size " + size + " out of range");
        }
        RuntimeArray av = new RuntimeArray();
        RuntimeScalar result = av.createAnonymousReference();
        c.recordSeen(result);
        int n = (int) size;
        for (int i = 0; i < n; i++) {
            RuntimeScalar elem = r.dispatch(c);
            // Drain any bare-container flag the child opcode left
            // behind: only Refs.readRef / Storable.thaw need to see
            // it, and an array element is consumed here so the flag
            // must not leak to the next sibling.
            c.takeBareContainerFlag();
            RuntimeArray.push(av, elem);
        }
        // Signal the surrounding SX_REF (if any) that we returned a
        // bare-container scalar — i.e. this ARRAYREFERENCE structurally
        // stands in for upstream's bare AV. See StorableContext for
        // the full rationale.
        c.markBareContainer();
        return result;
    }

    /**
     * Read an {@code SX_HASH} body: a {@code U32} pair count followed by
     * that many (value, keylen, key-bytes) records. Per Storable.xs the
     * value precedes the key on the wire. Keys are interpreted as
     * ISO-8859-1 byte strings (no UTF-8 flag info is stored in the bare
     * {@code SX_HASH} opcode).
     */
    public static RuntimeScalar readHash(StorableReader r, StorableContext c) {
        long size = c.readU32Length();
        if (size < 0 || size > Integer.MAX_VALUE) {
            throw new StorableFormatException("SX_HASH size " + size + " out of range");
        }
        RuntimeHash hv = new RuntimeHash();
        RuntimeScalar result = hv.createAnonymousReference();
        c.recordSeen(result);
        int n = (int) size;
        for (int i = 0; i < n; i++) {
            // VALUE first, KEY second (Storable.xs retrieve_hash).
            RuntimeScalar value = r.dispatch(c);
            // Drain any bare flag the child opcode produced — see
            // readArray for the rationale.
            c.takeBareContainerFlag();
            long keylen = c.readU32Length();
            if (keylen < 0 || keylen > Integer.MAX_VALUE) {
                throw new StorableFormatException("SX_HASH keylen " + keylen + " out of range");
            }
            byte[] keyBytes = c.readBytes((int) keylen);
            String key = new String(keyBytes, StandardCharsets.ISO_8859_1);
            hv.put(key, value);
        }
        c.markBareContainer();
        return result;
    }

    /**
     * Read an {@code SX_FLAG_HASH} body: 1 byte of hash-level flags, then
     * a {@code U32} pair count, then that many (value, key-flags,
     * keylen, key-bytes) records. Per-key {@code SHV_K_UTF8} selects
     * UTF-8 decoding for the key; otherwise the key is treated as a
     * binary (ISO-8859-1) string. Hash-level flags
     * (e.g. {@code SHV_RESTRICTED}) are read but not modelled.
     */
    public static RuntimeScalar readFlagHash(StorableReader r, StorableContext c) {
        int hashFlags = c.readU8();          // hash-level flags; not modelled
        @SuppressWarnings("unused") int _hf = hashFlags;
        long size = c.readU32Length();
        if (size < 0 || size > Integer.MAX_VALUE) {
            throw new StorableFormatException("SX_FLAG_HASH size " + size + " out of range");
        }
        RuntimeHash hv = new RuntimeHash();
        RuntimeScalar result = hv.createAnonymousReference();
        c.recordSeen(result);
        int n = (int) size;
        for (int i = 0; i < n; i++) {
            RuntimeScalar value = r.dispatch(c);
            c.takeBareContainerFlag();
            int keyFlags = c.readU8();
            long keylen = c.readU32Length();
            if (keylen < 0 || keylen > Integer.MAX_VALUE) {
                throw new StorableFormatException("SX_FLAG_HASH keylen " + keylen + " out of range");
            }
            byte[] keyBytes = c.readBytes((int) keylen);
            String key = ((keyFlags & SHV_K_UTF8) != 0)
                    ? new String(keyBytes, StandardCharsets.UTF_8)
                    : new String(keyBytes, StandardCharsets.ISO_8859_1);
            hv.put(key, value);
        }
        c.markBareContainer();
        return result;
    }

    /**
     * Read an {@code SX_SVUNDEF_ELEM} body (none): an array slot that was
     * specifically {@code &PL_sv_undef} on the producing side. We model
     * it as a regular undef. Recorded in the seen table like any other
     * fresh scalar.
     */
    public static RuntimeScalar readSvUndefElem(StorableReader r, StorableContext c) {
        RuntimeScalar sv = new RuntimeScalar();
        c.recordSeen(sv);
        return sv;
    }
}

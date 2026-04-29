package org.perlonjava.runtime.perlmodule.storable;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/**
 * Container opcode readers/writers (arrays, hashes, flag-hashes).
 * <p>
 * <strong>OWNER: containers-agent</strong>
 * <p>
 * Opcodes covered (Storable.xs L141-177):
 * <ul>
 *   <li>{@link Opcodes#SX_ARRAY} — U32 size + {@code size} child
 *       opcode trees. See {@code retrieve_array} (Storable.xs L6247).</li>
 *   <li>{@link Opcodes#SX_HASH} — U32 size + {@code size} pairs of
 *       (value-tree, U32 keylen, key-bytes). The value comes
 *       <em>before</em> the key on the wire. See {@code retrieve_hash}
 *       (Storable.xs L6439).</li>
 *   <li>{@link Opcodes#SX_FLAG_HASH} — U32 size + 1 byte global flags
 *       + {@code size} triplets of (value-tree, 1-byte flags, U32 keylen, key).
 *       Flags include {@code SHV_K_UTF8} = 0x01,
 *       {@code SHV_K_WASUTF8} = 0x02, etc.</li>
 *   <li>{@link Opcodes#SX_SVUNDEF_ELEM} — placeholder for an array slot
 *       that was {@code &PL_sv_undef} (vs. a regular {@code undef}
 *       value). For our purposes equivalent to undef; only meaningful
 *       inside SX_ARRAY.</li>
 * </ul>
 * <p>
 * <strong>Seen-table:</strong> register the container reference (the
 * RuntimeScalar that holds the {@code RuntimeArray}/{@code RuntimeHash})
 * <em>before</em> recursing into its children. See {@code SEEN_NN} call
 * order in {@code retrieve_array}.
 * <p>
 * <strong>Container as a scalar.</strong> Storable returns the contained
 * SV (the AV/HV). PerlOnJava's {@link RuntimeScalar} can carry a
 * {@code RuntimeArray}/{@code RuntimeHash} via its
 * {@code ARRAYREFERENCE}/{@code HASHREFERENCE} types. However note the
 * container retrievers in Storable.xs (e.g. {@code retrieve_array})
 * return the AV itself, NOT a reference to it; the surrounding
 * {@code SX_REF} adds the reference. So the array opcode produces a
 * scalar whose payload <em>is</em> the array (you can think of it as
 * an unblessed AV slot that the caller will wrap).
 */
public final class Containers {
    private Containers() {}

    public static RuntimeScalar readArray(StorableReader r, StorableContext c) {
        throw new StorableFormatException("containers-agent: SX_ARRAY not yet implemented");
    }

    public static RuntimeScalar readHash(StorableReader r, StorableContext c) {
        throw new StorableFormatException("containers-agent: SX_HASH not yet implemented");
    }

    public static RuntimeScalar readFlagHash(StorableReader r, StorableContext c) {
        throw new StorableFormatException("containers-agent: SX_FLAG_HASH not yet implemented");
    }

    public static RuntimeScalar readSvUndefElem(StorableReader r, StorableContext c) {
        throw new StorableFormatException("containers-agent: SX_SVUNDEF_ELEM not yet implemented");
    }
}

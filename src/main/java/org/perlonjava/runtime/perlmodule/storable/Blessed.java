package org.perlonjava.runtime.perlmodule.storable;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/**
 * Bless opcode readers/writers.
 * <p>
 * <strong>OWNER: blessed-agent</strong>
 * <p>
 * Opcodes covered (Storable.xs L141-177):
 * <ul>
 *   <li>{@link Opcodes#SX_BLESS} — body: 1 byte length (or 5 bytes if
 *       the high bit of the length byte is set: 0x80 means "next 4
 *       bytes are the real U32 length"), then classname bytes, then a
 *       child opcode tree producing the value to bless. The classname
 *       is recorded in the context's class table at the next index
 *       ({@link StorableContext#recordClass(String)}). See
 *       {@code retrieve_bless} in Storable.xs.</li>
 *   <li>{@link Opcodes#SX_IX_BLESS} — body: 1 byte index (or 5 bytes
 *       if high bit set), then a child opcode tree. The classname is
 *       looked up via {@link StorableContext#getClass(long)}.</li>
 * </ul>
 * <p>
 * <strong>PerlOnJava blessing.</strong> The result must be a blessed
 * reference. PerlOnJava's blessing API is on
 * {@code RuntimeScalar} (look for {@code blessedClassName} or the
 * {@code bless} operator implementation). The blessed entity is the
 * <em>referent</em> of the value produced by the child tree (because
 * SX_BLESS wraps the inner SX_REF / SX_HASH / SX_ARRAY).
 */
public final class Blessed {
    private Blessed() {}

    public static RuntimeScalar readBless(StorableReader r, StorableContext c) {
        throw new StorableFormatException("blessed-agent: SX_BLESS not yet implemented");
    }

    public static RuntimeScalar readIxBless(StorableReader r, StorableContext c) {
        throw new StorableFormatException("blessed-agent: SX_IX_BLESS not yet implemented");
    }
}

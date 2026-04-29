package org.perlonjava.runtime.perlmodule.storable;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.nio.charset.StandardCharsets;

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
 * <strong>Seen-table semantics.</strong> The recursive call into
 * {@link StorableReader#dispatch(StorableContext)} already registers
 * the inner SV in the seen table; {@code bless} mutates that entry in
 * place, so we do <em>not</em> call {@code recordSeen} again here.
 */
public final class Blessed {
    private Blessed() {}

    /**
     * Reads {@link Opcodes#SX_BLESS}: a length-prefixed classname
     * followed by a child opcode tree. Registers the classname in the
     * context's class table, then blesses the inner value.
     *
     * @param r the top-level reader (used for recursing into child)
     * @param c the active context (cursor + class table)
     * @return the blessed inner value
     */
    public static RuntimeScalar readBless(StorableReader r, StorableContext c) {
        int len = c.readU8();
        if ((len & 0x80) != 0) {
            long longLen = c.readU32Length();
            if (longLen < 0 || longLen > Integer.MAX_VALUE) {
                throw new StorableFormatException(
                        "SX_BLESS classname length out of range: " + longLen);
            }
            len = (int) longLen;
        }
        byte[] nameBytes = c.readBytes(len);
        String classname = new String(nameBytes, StandardCharsets.UTF_8);
        c.recordClass(classname);
        RuntimeScalar inner = r.dispatch(c);
        return ReferenceOperators.bless(inner, new RuntimeScalar(classname));
    }

    /**
     * Reads {@link Opcodes#SX_IX_BLESS}: a length-prefixed (re-used)
     * classname index followed by a child opcode tree. Looks up the
     * classname previously registered by an {@link Opcodes#SX_BLESS},
     * then blesses the inner value. Does <em>not</em> add a new entry
     * to the class table — this is a re-use.
     *
     * @param r the top-level reader (used for recursing into child)
     * @param c the active context (cursor + class table)
     * @return the blessed inner value
     */
    public static RuntimeScalar readIxBless(StorableReader r, StorableContext c) {
        int ix = c.readU8();
        if ((ix & 0x80) != 0) {
            long longIx = c.readU32Length();
            if (longIx < 0 || longIx > Integer.MAX_VALUE) {
                throw new StorableFormatException(
                        "SX_IX_BLESS classname index out of range: " + longIx);
            }
            ix = (int) longIx;
        }
        String classname = c.getClass(ix);
        RuntimeScalar inner = r.dispatch(c);
        return ReferenceOperators.bless(inner, new RuntimeScalar(classname));
    }
}

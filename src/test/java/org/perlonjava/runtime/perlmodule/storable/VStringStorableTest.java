package org.perlonjava.runtime.perlmodule.storable;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code SX_VSTRING} / {@code SX_LVSTRING} round-trip
 * via {@link VStringEncoder} (write) and {@link Misc#readVString} /
 * {@link Misc#readLVString} (read).
 * <p>
 * V-strings in PerlOnJava are modeled as a {@link RuntimeScalar} with
 * {@code type = VSTRING} and a {@link String} value holding the raw
 * v-string content (e.g. for {@code v1.2.3} the value is
 * {@code "\u0001\u0002\u0003"}). The textual source form is not
 * preserved across a Storable round-trip — see the note on
 * {@link VStringEncoder}.
 */
@Tag("unit")
public class VStringStorableTest {

    /** Convert encoded char-string back to a real byte array. */
    private static byte[] toBytes(String encoded) {
        byte[] out = new byte[encoded.length()];
        for (int i = 0; i < encoded.length(); i++) {
            out[i] = (byte) encoded.charAt(i);
        }
        return out;
    }

    private static RuntimeScalar makeVString(String content) {
        RuntimeScalar v = new RuntimeScalar(content);
        v.type = RuntimeScalarType.VSTRING;
        return v;
    }

    /**
     * Round-trip a v-string like {@code v1.2.3}. We wrap it in a scalar
     * ref so the writer's {@code emitTopLevel} (which strips one outer
     * ref like upstream {@code do_store}) hands the v-string to
     * {@link VStringEncoder}.
     */
    @Test
    void roundTripShortVString() {
        RuntimeScalar v = makeVString("\u0001\u0002\u0003");
        RuntimeScalar ref = v.createReference();

        StorableWriter w = new StorableWriter();
        String encoded = w.writeTopLevelToMemory(ref, false);

        StorableContext c = new StorableContext(toBytes(encoded));
        Header.parseInMemory(c);
        StorableReader r = new StorableReader();
        RuntimeScalar got = r.dispatch(c);

        assertNotNull(got);
        assertEquals(RuntimeScalarType.VSTRING, got.type, "type preserved");
        assertEquals("\u0001\u0002\u0003", got.toString(), "content preserved");
    }

    /**
     * Wire-level check: after the in-memory header, the first body byte
     * for a short v-string is {@link Opcodes#SX_VSTRING} (0x1D).
     */
    @Test
    void firstBodyByteIsSxVString() {
        RuntimeScalar v = makeVString("\u0005\u0006\u0007");
        RuntimeScalar ref = v.createReference();

        StorableWriter w = new StorableWriter();
        byte[] bytes = toBytes(w.writeTopLevelToMemory(ref, false));

        // In-memory header is 2 bytes (useNetorderByte + minor).
        assertTrue(bytes.length > 2, "encoded body present");
        assertEquals(Opcodes.SX_VSTRING, bytes[2] & 0xFF,
                "first body byte is SX_VSTRING (0x1D)");
        // Next byte is magic length = 3.
        assertEquals(3, bytes[3] & 0xFF, "magic length");
    }

    /**
     * Synthetic SX_LVSTRING reader test. Constructing a >255-byte
     * v-string literal in Perl source is awkward, so build the wire
     * format by hand and verify the reader returns a VSTRING-typed
     * scalar with the expected content.
     */
    @Test
    void readSxLVStringSynthetic() {
        // 300-byte v-string content; magic blob and scalar body share
        // the same bytes (matching what our writer emits).
        int n = 300;
        byte[] content = new byte[n];
        for (int i = 0; i < n; i++) content[i] = (byte) (i & 0xFF);

        // Layout:
        //   SX_LVSTRING | U32(n) | content[n] | SX_LSCALAR | U32(n) | content[n]
        // U32 is 4 bytes big-endian (default fileBigEndian=true after
        // forWrite/parseInMemory; for our raw test we set it explicitly).
        int total = 1 + 4 + n + 1 + 4 + n;
        byte[] stream = new byte[total];
        int p = 0;
        stream[p++] = (byte) Opcodes.SX_LVSTRING;
        // U32 BE
        stream[p++] = (byte) ((n >>> 24) & 0xFF);
        stream[p++] = (byte) ((n >>> 16) & 0xFF);
        stream[p++] = (byte) ((n >>> 8)  & 0xFF);
        stream[p++] = (byte) ( n         & 0xFF);
        System.arraycopy(content, 0, stream, p, n); p += n;
        stream[p++] = (byte) Opcodes.SX_LSCALAR;
        stream[p++] = (byte) ((n >>> 24) & 0xFF);
        stream[p++] = (byte) ((n >>> 16) & 0xFF);
        stream[p++] = (byte) ((n >>> 8)  & 0xFF);
        stream[p++] = (byte) ( n         & 0xFF);
        System.arraycopy(content, 0, stream, p, n); p += n;
        assertEquals(total, p, "synthetic stream sized correctly");

        StorableContext c = new StorableContext(stream);
        // No header — defaults (netorder=false, fileBigEndian=true)
        // match the BE U32 we encoded above.
        StorableReader r = new StorableReader();
        RuntimeScalar got = r.dispatch(c);

        assertNotNull(got);
        assertEquals(RuntimeScalarType.VSTRING, got.type, "type promoted to VSTRING");
        // Content recovered as ISO-8859-1 string of n bytes.
        String expected = new String(content, StandardCharsets.ISO_8859_1);
        assertEquals(expected, got.toString(), "content preserved across SX_LVSTRING");
    }
}

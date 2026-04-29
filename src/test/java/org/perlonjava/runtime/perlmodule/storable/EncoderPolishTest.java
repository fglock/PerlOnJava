package org.perlonjava.runtime.perlmodule.storable;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the encoder polish items:
 *   1. {@code $Storable::canonical} sorted hash-key emission.
 *   6. {@code SX_WEAKREF} / {@code SX_WEAKOVERLOAD} writer.
 *   7. {@code SX_FLAG_HASH} writer for utf8-flagged keys.
 */
@Tag("unit")
public class EncoderPolishTest {

    /** Convert the encoded String (chars 0..255) returned by
     *  {@link StorableWriter} into a real byte array. */
    private static byte[] toBytes(String encoded) {
        byte[] out = new byte[encoded.length()];
        for (int i = 0; i < encoded.length(); i++) {
            out[i] = (byte) encoded.charAt(i);
        }
        return out;
    }

    /** Locate the first occurrence of {@code needle} in {@code haystack}.
     *  Returns -1 if not found. */
    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /**
     * Item 1 acceptance: under {@code $Storable::canonical}, hash keys
     * appear in sorted order in the output bytes regardless of insertion
     * order.
     */
    @Test
    void canonicalSortsHashKeys() {
        RuntimeHash h = new RuntimeHash();
        h.put("c", new RuntimeScalar(3));
        h.put("a", new RuntimeScalar(1));
        h.put("b", new RuntimeScalar(2));
        RuntimeScalar ref = h.createReference();

        StorableWriter w = new StorableWriter();
        w.setCanonical(true);
        byte[] bytes = toBytes(w.writeTopLevelToMemory(ref, false));

        int ia = indexOf(bytes, "a".getBytes(StandardCharsets.UTF_8));
        int ib = indexOf(bytes, "b".getBytes(StandardCharsets.UTF_8));
        int ic = indexOf(bytes, "c".getBytes(StandardCharsets.UTF_8));
        assertTrue(ia >= 0 && ib >= 0 && ic >= 0,
                "all keys present");
        assertTrue(ia < ib, "a before b");
        assertTrue(ib < ic, "b before c");
    }

    /**
     * Item 7 acceptance: a hash with one ASCII key and one Unicode key is
     * emitted via {@code SX_FLAG_HASH} with {@code SHV_K_UTF8} on the
     * Unicode key.
     */
    @Test
    void flagHashUtf8KeyEmitsSHV_K_UTF8() {
        RuntimeHash h = new RuntimeHash();
        h.put("ascii", new RuntimeScalar(1));
        h.put("\u00e9", new RuntimeScalar(2));      // é, U+00E9
        RuntimeScalar ref = h.createReference();

        StorableWriter w = new StorableWriter();
        w.setCanonical(true);
        byte[] bytes = toBytes(w.writeTopLevelToMemory(ref, false));

        // Find the SX_FLAG_HASH opcode in the body.
        int flagIdx = -1;
        for (int i = 2; i < bytes.length; i++) {
            if ((bytes[i] & 0xFF) == Opcodes.SX_FLAG_HASH) { flagIdx = i; break; }
        }
        assertTrue(flagIdx >= 0,
                "expected SX_FLAG_HASH opcode (0x19) in output");

        // Locate the UTF-8 bytes of "é" (0xC3 0xA9) and verify the byte
        // immediately before the U32(keylen=2) header is SHV_K_UTF8.
        byte[] eUtf8 = "\u00e9".getBytes(StandardCharsets.UTF_8);
        int eIdx = indexOf(bytes, eUtf8);
        assertTrue(eIdx >= 0, "unicode key bytes present");
        // Layout: <key-flags 1 byte> <U32 keylen=4 bytes> <key bytes>
        int kfIdx = eIdx - 5;
        assertTrue(kfIdx >= 0, "key-flag byte position is in range");
        assertEquals(0x01, bytes[kfIdx] & 0xFF,
                "SHV_K_UTF8 set on the Unicode key");
    }
}

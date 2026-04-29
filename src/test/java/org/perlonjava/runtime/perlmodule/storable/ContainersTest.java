package org.perlonjava.runtime.perlmodule.storable;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the container opcode readers in {@link Containers}.
 * <p>
 * Mixes (a) real fixtures produced by upstream perl/Storable that
 * exercise only opcodes implemented in this stage and (b) hand-built
 * synthetic byte streams that lift the dependency on opcodes owned by
 * other parallel agents (scalars, refs).
 */
@Tag("unit")
public class ContainersTest {

    private static final Path FIXTURES =
            Paths.get("src/test/resources/storable_fixtures").toAbsolutePath();

    private static RuntimeScalar readFixture(String name) throws IOException {
        byte[] data = Files.readAllBytes(FIXTURES.resolve(name + ".bin"));
        StorableContext c = new StorableContext(data);
        Header.parseFile(c);
        StorableReader r = new StorableReader();
        RuntimeScalar v = r.dispatch(c);
        assertNotNull(v);
        return v;
    }

    /**
     * Build an in-memory {@code pst0} netorder byte stream from a body
     * payload. Mirrors the synthetic header used in
     * {@link StorableReaderTest#misc_coderef_refused()}.
     */
    private static StorableContext synthetic(byte[] body) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write('p'); baos.write('s'); baos.write('t'); baos.write('0');
        baos.write((2 << 1) | 1);   // major=2, netorder=1
        baos.write(11);             // minor
        baos.write(body, 0, body.length);
        StorableContext c = new StorableContext(baos.toByteArray());
        Header.parseFile(c);
        return c;
    }

    /** Encode a 4-byte big-endian length (the netorder U32 form). */
    private static void writeBeU32(ByteArrayOutputStream out, int n) {
        out.write((n >>> 24) & 0xFF);
        out.write((n >>> 16) & 0xFF);
        out.write((n >>>  8) & 0xFF);
        out.write( n         & 0xFF);
    }

    // -------- fixture-based tests (no scalar children needed) --------

    @Test
    void array_empty_fixture() throws IOException {
        RuntimeScalar v = readFixture("containers/array_empty");
        assertEquals(RuntimeScalarType.ARRAYREFERENCE, v.type);
        RuntimeArray av = (RuntimeArray) v.value;
        assertEquals(0, av.elements.size());
    }

    @Test
    void hash_empty_fixture() throws IOException {
        RuntimeScalar v = readFixture("containers/hash_empty");
        assertEquals(RuntimeScalarType.HASHREFERENCE, v.type);
        RuntimeHash hv = (RuntimeHash) v.value;
        assertEquals(0, hv.elements.size());
    }

    // -------- synthetic tests independent of scalars-agent --------

    @Test
    void array_of_two_undefs_synthetic() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(Opcodes.SX_ARRAY);
        writeBeU32(body, 2);
        body.write(Opcodes.SX_UNDEF);
        body.write(Opcodes.SX_UNDEF);

        StorableContext c = synthetic(body.toByteArray());
        StorableReader r = new StorableReader();
        RuntimeScalar v = r.dispatch(c);

        assertEquals(RuntimeScalarType.ARRAYREFERENCE, v.type);
        RuntimeArray av = (RuntimeArray) v.value;
        assertEquals(2, av.elements.size());
        assertFalse(av.elements.get(0).getDefinedBoolean());
        assertFalse(av.elements.get(1).getDefinedBoolean());
    }

    @Test
    void array_with_svundef_elem_synthetic() {
        // SX_SVUNDEF_ELEM is only valid inside an array; verify we
        // accept it as a slot value and treat it as undef.
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(Opcodes.SX_ARRAY);
        writeBeU32(body, 1);
        body.write(Opcodes.SX_SVUNDEF_ELEM);

        StorableContext c = synthetic(body.toByteArray());
        StorableReader r = new StorableReader();
        RuntimeScalar v = r.dispatch(c);

        assertEquals(RuntimeScalarType.ARRAYREFERENCE, v.type);
        RuntimeArray av = (RuntimeArray) v.value;
        assertEquals(1, av.elements.size());
        assertFalse(av.elements.get(0).getDefinedBoolean());
    }

    @Test
    void hash_one_key_undef_synthetic() {
        // {"k" => undef} on the wire: VALUE first, then keylen + key bytes.
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(Opcodes.SX_HASH);
        writeBeU32(body, 1);
        body.write(Opcodes.SX_UNDEF);
        writeBeU32(body, 1);
        body.write('k');

        StorableContext c = synthetic(body.toByteArray());
        StorableReader r = new StorableReader();
        RuntimeScalar v = r.dispatch(c);

        assertEquals(RuntimeScalarType.HASHREFERENCE, v.type);
        RuntimeHash hv = (RuntimeHash) v.value;
        assertEquals(1, hv.elements.size());
        assertTrue(hv.elements.containsKey("k"));
        assertFalse(hv.elements.get("k").getDefinedBoolean());
    }

    @Test
    void hash_multiple_undef_values_synthetic() {
        // {"a"=>undef, "bb"=>undef} — exercise the loop body.
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(Opcodes.SX_HASH);
        writeBeU32(body, 2);

        body.write(Opcodes.SX_UNDEF);
        writeBeU32(body, 1);
        body.write('a');

        body.write(Opcodes.SX_UNDEF);
        writeBeU32(body, 2);
        body.write('b'); body.write('b');

        StorableContext c = synthetic(body.toByteArray());
        StorableReader r = new StorableReader();
        RuntimeScalar v = r.dispatch(c);

        RuntimeHash hv = (RuntimeHash) v.value;
        assertEquals(2, hv.elements.size());
        assertTrue(hv.elements.containsKey("a"));
        assertTrue(hv.elements.containsKey("bb"));
    }

    @Test
    void flag_hash_with_utf8_key_synthetic() {
        // SX_FLAG_HASH: hashFlags=0, size=1, then VALUE, keyFlags, keylen, keyBytes.
        // Key "école" = bytes 0xC3 0xA9 0x63 0x6F 0x6C 0x65 (UTF-8).
        byte[] keyBytes = "\u00e9cole".getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(Opcodes.SX_FLAG_HASH);
        body.write(0);                         // hash-level flags (ignored)
        writeBeU32(body, 1);
        body.write(Opcodes.SX_UNDEF);          // VALUE
        body.write(0x01);                      // SHV_K_UTF8
        writeBeU32(body, keyBytes.length);
        body.write(keyBytes, 0, keyBytes.length);

        StorableContext c = synthetic(body.toByteArray());
        StorableReader r = new StorableReader();
        RuntimeScalar v = r.dispatch(c);

        assertEquals(RuntimeScalarType.HASHREFERENCE, v.type);
        RuntimeHash hv = (RuntimeHash) v.value;
        assertEquals(1, hv.elements.size());
        assertTrue(hv.elements.containsKey("\u00e9cole"),
                "expected UTF-8-decoded key 'école', got: " + hv.elements.keySet());
    }

    @Test
    void flag_hash_with_binary_key_synthetic() {
        // Same shape but key flags=0 -> ISO-8859-1 decoding.
        byte[] keyBytes = new byte[] { (byte) 0xE9 };   // Latin-1 'é' raw byte

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(Opcodes.SX_FLAG_HASH);
        body.write(0);
        writeBeU32(body, 1);
        body.write(Opcodes.SX_UNDEF);
        body.write(0x00);
        writeBeU32(body, keyBytes.length);
        body.write(keyBytes, 0, keyBytes.length);

        StorableContext c = synthetic(body.toByteArray());
        StorableReader r = new StorableReader();
        RuntimeScalar v = r.dispatch(c);

        RuntimeHash hv = (RuntimeHash) v.value;
        assertEquals(1, hv.elements.size());
        assertTrue(hv.elements.containsKey("\u00e9"),
                "expected ISO-8859-1-decoded key 'é', got: " + hv.elements.keySet());
    }

    // -------- fixtures that depend on scalars-agent (kept disabled) --------

    @Test
    // formerly @Disabled — scalars-agent + containers-agent integration
    void array_mixed_fixture() throws IOException {
        RuntimeScalar v = readFixture("containers/array_mixed");
        assertEquals(RuntimeScalarType.ARRAYREFERENCE, v.type);
        RuntimeArray av = (RuntimeArray) v.value;
        assertEquals(5, av.elements.size());
    }

    @Test
    // formerly @Disabled — scalars-agent integration
    void hash_mixed_fixture() throws IOException {
        RuntimeScalar v = readFixture("containers/hash_mixed");
        assertEquals(RuntimeScalarType.HASHREFERENCE, v.type);
        RuntimeHash hv = (RuntimeHash) v.value;
        assertEquals(3, hv.elements.size());
    }

    @Test
    // formerly @Disabled — scalars-agent integration
    void hash_utf8_keys_fixture() throws IOException {
        RuntimeScalar v = readFixture("containers/hash_utf8_keys");
        assertEquals(RuntimeScalarType.HASHREFERENCE, v.type);
        RuntimeHash hv = (RuntimeHash) v.value;
        assertEquals(2, hv.elements.size());
    }
}

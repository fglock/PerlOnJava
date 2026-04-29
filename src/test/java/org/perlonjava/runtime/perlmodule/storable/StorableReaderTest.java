package org.perlonjava.runtime.perlmodule.storable;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-1 reader tests for the native Storable binary format.
 * <p>
 * Drives the foundation classes (Opcodes, StorableContext, Header,
 * StorableReader) against real fixtures produced by upstream
 * {@code perl} via {@code dev/tools/storable_gen_fixtures.pl}.
 * <p>
 * Tests are organized to mirror the parallel-agent partition:
 * each opcode group has its own test method, marked
 * {@code @Disabled}-equivalent (skipped via assumeTrue) until the
 * corresponding {@code <group>-agent} fills in the implementations.
 * <p>
 * In Stage A we only assert two things:
 * <ol>
 *   <li>The header parser correctly identifies a network-order
 *       {@code pst0} stream and advances the cursor to the body.</li>
 *   <li>The two simplest opcodes — SX_UNDEF and SX_SV_YES/NO — round
 *       trip end-to-end (these are implemented as canaries in
 *       {@link Scalars}).</li>
 * </ol>
 * Everything else throws {@link StorableFormatException} with the
 * agent-name marker, which the assertions below match against. As
 * agents implement opcodes, the corresponding tests flip from
 * "throws not-implemented" to "round-trips successfully".
 */
@Tag("unit")
public class StorableReaderTest {

    private static final Path FIXTURES =
            Paths.get("src/test/resources/storable_fixtures").toAbsolutePath();

    private static StorableContext open(String name) throws IOException {
        byte[] data = Files.readAllBytes(FIXTURES.resolve(name + ".bin"));
        StorableContext c = new StorableContext(data);
        Header.parseFile(c);
        return c;
    }

    // -------- header --------

    @Test
    void header_pst0_netorder_minor11() throws IOException {
        StorableContext c = open("scalars/undef");
        // Header should report netorder=true (nstore) and minor=11
        // (current upstream minor as of perl 5.42).
        assertTrue(c.isNetorder(), "nstore output must be netorder");
        assertEquals(2, c.getVersionMajor());
        assertTrue(c.getVersionMinor() >= 11);
    }

    @Test
    void header_native_byteorder_present() throws IOException {
        StorableContext c = open("scalars_native/integer_big");
        assertTrue(!c.isNetorder(), "store (not nstore) output must be native");
        // sizeofIV/NV got populated from the header
        assertEquals(8, c.getSizeofNV());
    }

    @Test
    void header_rejects_non_pst0() {
        StorableContext c = new StorableContext("not a storable file".getBytes());
        StorableFormatException ex = assertThrows(StorableFormatException.class,
                () -> Header.parseFile(c));
        assertTrue(ex.getMessage().contains("not a perl storable"),
                "should mirror upstream wording, got: " + ex.getMessage());
    }

    // -------- scalars (canary opcodes implemented in Stage A) --------

    @Test
    void scalars_undef() throws IOException {
        StorableContext c = open("scalars/undef");
        StorableReader r = new StorableReader();
        RuntimeScalar v = r.dispatch(c);
        assertNotNull(v);
        // Stored data is the dereferenced undef.
        assertTrue(!v.getDefinedBoolean(), "expected undef, got: " + v);
    }

    @Test
    void scalars_sv_yes() throws IOException {
        StorableContext c = open("scalars/sv_yes");
        StorableReader r = new StorableReader();
        RuntimeScalar v = r.dispatch(c);
        assertNotNull(v);
        assertTrue(v.getBoolean(), "SV_YES should be truthy");
    }

    @Test
    void scalars_sv_no() throws IOException {
        StorableContext c = open("scalars/sv_no");
        StorableReader r = new StorableReader();
        RuntimeScalar v = r.dispatch(c);
        assertNotNull(v);
        assertTrue(!v.getBoolean(), "SV_NO should be falsy");
    }

    // -------- integration round-trips (post-Stage-B) --------

    @Test
    void scalars_byte_pos_roundtrip() throws IOException {
        StorableContext c = open("scalars/byte_pos");
        RuntimeScalar v = new StorableReader().dispatch(c);
        assertEquals(42, v.getInt());
    }

    @Test
    void containers_array_mixed_roundtrip() throws IOException {
        StorableContext c = open("containers/array_mixed");
        RuntimeScalar v = new StorableReader().dispatch(c);
        // [1, "two", 3.0, undef, [4,5]]  (5 elements)
        assertNotNull(v);
        assertTrue(v.toString().startsWith("ARRAY("),
                "expected ARRAY ref, got: " + v.toString());
    }

    @Test
    void blessed_single_roundtrip() throws IOException {
        StorableContext c = open("blessed/single");
        RuntimeScalar v = new StorableReader().dispatch(c);
        assertNotNull(v);
        assertTrue(v.toString().contains("Foo::Bar="),
                "expected Foo::Bar=...; got: " + v.toString());
    }

    @Test
    void misc_coderef_refused() throws IOException {
        // CODE is a refusal — its expect file says "Can't retrieve code references".
        // We don't have a .bin for this (we never asked perl to nstore a coderef
        // because that itself dies under default config). The opcode-level test
        // hits the dispatcher with a synthetic stream.
        StorableContext c = new StorableContext(new byte[]{
                'p','s','t','0',
                (2 << 1) | 1,   // major=2, netorder
                11,             // minor
                Opcodes.SX_CODE
        });
        Header.parseFile(c);
        StorableReader r = new StorableReader();
        StorableFormatException ex = assertThrows(StorableFormatException.class,
                () -> r.dispatch(c));
        assertEquals("Can't retrieve code references", ex.getMessage());
    }
}

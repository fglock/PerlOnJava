package org.perlonjava.runtime.perlmodule.storable;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the reference / backref opcode readers in {@link Refs}.
 * <p>
 * Each test either loads a fixture produced by upstream perl/Storable
 * from {@code src/test/resources/storable_fixtures/refs/} or builds a
 * tiny synthetic byte stream in-test (used when the corresponding
 * container readers haven't landed yet).
 */
@Tag("unit")
public class RefsTest {

    private static final Path FIXTURES =
            Paths.get("src/test/resources/storable_fixtures").toAbsolutePath();

    /** Build a "pst0" file-style header for major=2, minor=11, netorder. */
    private static byte[] netorderHeader() {
        return new byte[] {
                'p', 's', 't', '0',
                (byte) ((Opcodes.STORABLE_BIN_MAJOR << 1) | 1),
                (byte) 11,
        };
    }

    /** Concatenate a header and a body into a single stream. */
    private static byte[] concat(byte[] header, byte... body) {
        byte[] out = new byte[header.length + body.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(body, 0, out, header.length, body.length);
        return out;
    }

    private static RuntimeScalar readFixture(String name) throws IOException {
        byte[] data = Files.readAllBytes(FIXTURES.resolve(name + ".bin"));
        StorableContext c = new StorableContext(data);
        Header.parseFile(c);
        StorableReader r = new StorableReader();
        RuntimeScalar v = r.dispatch(c);
        assertNotNull(v);
        return v;
    }

    // -------- SX_REF over SX_BYTE (synthetic) --------

    /**
     * SX_REF wrapping SX_BYTE: the simplest possible reference. Build
     * the stream by hand so this test does not depend on the
     * containers-agent's work.
     */
    @Test
    void refToByteSynthetic() {
        byte[] stream = concat(netorderHeader(),
                (byte) Opcodes.SX_REF,
                (byte) Opcodes.SX_BYTE,
                (byte) 0xAA);              // 0xAA - 128 = 42

        StorableContext c = new StorableContext(stream);
        Header.parseFile(c);
        StorableReader r = new StorableReader();
        RuntimeScalar ref = r.dispatch(c);

        assertNotNull(ref);
        assertEquals(RuntimeScalarType.REFERENCE, ref.type,
                "SX_REF over a scalar must produce a SCALAR reference");
        RuntimeScalar referent = ref.scalarDeref();
        assertEquals(42, referent.getInt());
    }

    // -------- SX_REF + SX_OBJECT backref (synthetic) --------

    /**
     * Confirms that the SX_REF reader registers the placeholder ref in
     * the seen-table BEFORE recursing into the body, by emitting a
     * second top-level value that is an SX_OBJECT(tag=0) backref. Both
     * dispatches must yield the same reference instance.
     */
    @Test
    void refIsRegisteredBeforeBodyForBackref() {
        // SX_OBJECT body is a U32 tag in big-endian (netorder).
        byte[] stream = concat(netorderHeader(),
                (byte) Opcodes.SX_REF,
                (byte) Opcodes.SX_BYTE,
                (byte) 0xAA,                                  // first value: ref(42)
                (byte) Opcodes.SX_OBJECT,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00); // tag 0

        StorableContext c = new StorableContext(stream);
        Header.parseFile(c);
        StorableReader r = new StorableReader();

        RuntimeScalar firstRef = r.dispatch(c);
        RuntimeScalar backref = r.dispatch(c);

        assertSame(firstRef, backref,
                "SX_OBJECT(0) should return the very same RuntimeScalar registered as tag 0");
        assertEquals(42, firstRef.scalarDeref().getInt());

        // Sanity: tag 1 is the byte itself, not the ref.
        assertNotSame(firstRef, c.getSeen(1));
    }

    // -------- SX_REF over SX_OBJECT pointing at the ref itself --------

    /**
     * Self-referential ref: the body of SX_REF is SX_OBJECT(0), which
     * resolves to the placeholder ref allocated by readRef itself. The
     * resulting ref points at itself; dereferencing once yields the
     * same RuntimeScalar.
     */
    @Test
    void selfReferentialRefViaBackref() {
        byte[] stream = concat(netorderHeader(),
                (byte) Opcodes.SX_REF,
                (byte) Opcodes.SX_OBJECT,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00);

        StorableContext c = new StorableContext(stream);
        Header.parseFile(c);
        StorableReader r = new StorableReader();
        RuntimeScalar ref = r.dispatch(c);

        assertNotNull(ref);
        assertEquals(RuntimeScalarType.REFERENCE, ref.type);
        assertSame(ref, ref.scalarDeref(),
                "Self-referential ref should dereference to itself");
    }

    // -------- Fixture: scalar_ref.bin (\\42) --------

    /**
     * The fixture is exactly the synthetic stream above; this test
     * proves the on-disk format produced by upstream Storable's
     * {@code nstore} agrees with our reader.
     */
    @Test
    void scalarRefFixture() throws IOException {
        RuntimeScalar ref = readFixture("refs/scalar_ref");
        assertEquals(RuntimeScalarType.REFERENCE, ref.type);
        assertEquals(42, ref.scalarDeref().getInt());
    }

    // -------- Fixtures that depend on containers-agent --------

    @Test
    // formerly @Disabled — containers-agent integration
    void refToArrayFixture() throws IOException {
        RuntimeScalar v = readFixture("refs/ref_to_array");
        assertTrue(RuntimeScalarType.isReference(v));
    }

    @Test
    // formerly @Disabled — containers-agent integration
    void refToHashFixture() throws IOException {
        RuntimeScalar v = readFixture("refs/ref_to_hash");
        assertTrue(RuntimeScalarType.isReference(v));
    }

    @Test
    // formerly @Disabled — containers-agent integration
    void cycleFixture() throws IOException {
        readFixture("refs/cycle");
    }

    @Test
    // formerly @Disabled — containers-agent integration
    void sharedStructFixture() throws IOException {
        readFixture("refs/shared_struct");
    }

    @Test
    // formerly @Disabled — containers-agent integration
    void weakrefFixture() throws IOException {
        readFixture("refs/weakref");
    }
}

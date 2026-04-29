package org.perlonjava.runtime.perlmodule.storable;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.NameNormalizer;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Blessed}: SX_BLESS and SX_IX_BLESS opcode bodies.
 * <p>
 * These tests are self-contained: they construct synthetic byte
 * streams for the body of the opcode (the opcode byte itself is
 * already consumed by the dispatcher) and pre-populate the seen
 * table with a real reference {@link RuntimeScalar} so the recursive
 * dispatch resolves to a blessable referent via {@code SX_OBJECT}.
 * This avoids any dependency on container/ref opcode readers that
 * may not yet be implemented in parallel branches.
 */
@Tag("unit")
public class BlessedTest {

    /**
     * Helper: append a 4-byte big-endian unsigned 32-bit integer to
     * the given output. Matches {@link StorableContext#readU32Length()}
     * behavior with the default {@code fileBigEndian=true} setting.
     */
    private static void writeBeU32(ByteArrayOutputStream out, long v) {
        out.write((int) ((v >>> 24) & 0xFF));
        out.write((int) ((v >>> 16) & 0xFF));
        out.write((int) ((v >>> 8) & 0xFF));
        out.write((int) (v & 0xFF));
    }

    /**
     * Build a fresh reader/context pair seeded with one reference at
     * seen-table index 0 so {@code SX_OBJECT} U32(0) returns it.
     */
    private static StorableContext makeContextWithSeenRef(byte[] body, RuntimeScalar seenRef) {
        StorableContext c = new StorableContext(body);
        c.recordSeen(seenRef);
        return c;
    }

    /** Returns the canonical blessed-class name attached to the
     *  referent of {@code ref}, or {@code null} if not blessed. */
    private static String blessedNameOf(RuntimeScalar ref) {
        if (!(ref.value instanceof RuntimeBase)) {
            return null;
        }
        RuntimeBase referent = (RuntimeBase) ref.value;
        if (referent.blessId == 0) {
            return null;
        }
        return NameNormalizer.getBlessStr(referent.blessId);
    }

    @Test
    void shortClassname_blessesInnerReference() throws IOException {
        // body: len=8, "Foo::Bar", SX_OBJECT, U32(0)
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] name = "Foo::Bar".getBytes(StandardCharsets.UTF_8);
        body.write(name.length);
        body.write(name);
        body.write(Opcodes.SX_OBJECT);
        writeBeU32(body, 0L);

        RuntimeScalar hashref = new RuntimeHash().createReference();
        StorableContext c = makeContextWithSeenRef(body.toByteArray(), hashref);
        StorableReader r = new StorableReader();

        RuntimeScalar result = Blessed.readBless(r, c);
        assertNotNull(result);
        assertEquals("Foo::Bar", blessedNameOf(result));
        assertTrue(result.toString().startsWith("Foo::Bar="),
                "expected Foo::Bar=...; got: " + result);
        assertEquals("Foo::Bar", c.getClass(0),
                "SX_BLESS should register the classname at index 0");
    }

    @Test
    void ixBless_reusesPreviouslyRecordedClass() throws IOException {
        // First populate the class table as if an SX_BLESS preceded.
        // Then exercise SX_IX_BLESS, ix=0, SX_OBJECT, U32(0).
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0); // ix = 0
        body.write(Opcodes.SX_OBJECT);
        writeBeU32(body, 0L);

        RuntimeScalar hashref = new RuntimeHash().createReference();
        StorableContext c = makeContextWithSeenRef(body.toByteArray(), hashref);
        c.recordClass("My::Class");
        StorableReader r = new StorableReader();

        RuntimeScalar result = Blessed.readIxBless(r, c);
        assertNotNull(result);
        assertEquals("My::Class", blessedNameOf(result));
        // The class table must NOT have grown; SX_IX_BLESS is a re-use.
        assertEquals("My::Class", c.getClass(0));
    }

    @Test
    void longClassname_highBitLengthThen4Bytes() throws IOException {
        // Build a classname of 200 bytes ("A" repeated). 200 < 256 but
        // we deliberately encode it via the long form to exercise the
        // U32-length branch: first byte 0x80, then U32(200), then bytes.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append('A');
        byte[] name = sb.toString().getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x80);              // high-bit set: long form
        writeBeU32(body, name.length); // real length
        body.write(name);
        body.write(Opcodes.SX_OBJECT);
        writeBeU32(body, 0L);

        RuntimeScalar hashref = new RuntimeHash().createReference();
        StorableContext c = makeContextWithSeenRef(body.toByteArray(), hashref);
        StorableReader r = new StorableReader();

        RuntimeScalar result = Blessed.readBless(r, c);
        assertNotNull(result);
        assertEquals(sb.toString(), blessedNameOf(result));
        assertEquals(sb.toString(), c.getClass(0));
    }

    @Test
    void blessRecordsThenIxBlessReusesInSequence() throws IOException {
        // First call: SX_BLESS body -> records "Foo" at index 0.
        ByteArrayOutputStream b1 = new ByteArrayOutputStream();
        byte[] foo = "Foo".getBytes(StandardCharsets.UTF_8);
        b1.write(foo.length);
        b1.write(foo);
        b1.write(Opcodes.SX_OBJECT);
        writeBeU32(b1, 0L);

        RuntimeScalar firstRef = new RuntimeHash().createReference();
        StorableContext c1 = makeContextWithSeenRef(b1.toByteArray(), firstRef);
        StorableReader r = new StorableReader();
        RuntimeScalar r1 = Blessed.readBless(r, c1);
        assertEquals("Foo", blessedNameOf(r1));

        // Second call on the SAME context: SX_IX_BLESS body referencing
        // the freshly recorded class. We simulate this by feeding a
        // separate body but reusing the class table from c1 by recording
        // it on a new context.
        ByteArrayOutputStream b2 = new ByteArrayOutputStream();
        b2.write(0); // ix=0 -> "Foo"
        b2.write(Opcodes.SX_OBJECT);
        writeBeU32(b2, 0L);

        RuntimeScalar secondRef = new RuntimeHash().createReference();
        StorableContext c2 = new StorableContext(b2.toByteArray());
        c2.recordClass("Foo"); // mirror of c1's class table at the same index
        c2.recordSeen(secondRef);
        RuntimeScalar r2 = Blessed.readIxBless(r, c2);
        assertEquals("Foo", blessedNameOf(r2));
    }
}

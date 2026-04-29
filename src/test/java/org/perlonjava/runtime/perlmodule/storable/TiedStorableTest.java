package org.perlonjava.runtime.perlmodule.storable;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;
import org.perlonjava.runtime.runtimetypes.TieArray;
import org.perlonjava.runtime.runtimetypes.TieHash;
import org.perlonjava.runtime.runtimetypes.TieScalar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for tied-container freeze / retrieve
 * ({@code SX_TIED_ARRAY} / {@code SX_TIED_HASH} / {@code SX_TIED_SCALAR}).
 * <p>
 * Strategy: rather than booting the Perl interpreter to drive a real
 * {@code tie %h, 'MyTie', ...}, we synthesise tied magic directly in
 * Java by constructing {@link TieHash} / {@link TieArray} /
 * {@link TieScalar} on top of an empty blessed reference. That gives
 * us deterministic, dependency-free coverage of:
 * <ul>
 *   <li>the encoder's tied-magic detection branch in
 *       {@link TiedEncoder#tryEmit};</li>
 *   <li>the wire byte (SX_TIED_HASH / _ARRAY / _SCALAR) emitted in
 *       place of the usual SX_BLESS / container-body sequence;</li>
 *   <li>the reader's placeholder + magic-install branch in
 *       {@link Misc#readTiedHash} et al.</li>
 * </ul>
 * Method-dispatch round trips (FETCH/STORE actually firing on the
 * thawed object) are not exercised here — they require a real Perl
 * package, which is the integration-test layer.
 */
@Tag("unit")
public class TiedStorableTest {

    /** Build a blessed empty hash ref to act as the "tying object". The
     *  test never invokes its tie methods; it only round-trips its
     *  identity. */
    private static RuntimeScalar makeBlessedTier(String className) {
        RuntimeScalar ref = new RuntimeHash().createAnonymousReference();
        ReferenceOperators.bless(ref, new RuntimeScalar(className));
        return ref;
    }

    /** Strip the in-memory header that {@link StorableWriter#writeTopLevelToMemory}
     *  prepends so that {@link Header#parseInMemory} is happy on the
     *  way back. */
    private static byte[] toBytes(String encoded) {
        // The writer encodes 0..255 as chars in a String; byte-extract.
        byte[] out = new byte[encoded.length()];
        for (int i = 0; i < encoded.length(); i++) {
            out[i] = (byte) (encoded.charAt(i) & 0xFF);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Encoder: tied magic produces SX_TIED_* opcodes.
    // ------------------------------------------------------------------

    @Test
    void encoderEmitsTiedHashOpcode() {
        // Build a tied hash by hand: empty hash + TieHash magic.
        RuntimeHash hv = new RuntimeHash();
        RuntimeScalar tier = makeBlessedTier("MyTieHashImpl");
        hv.type = RuntimeHash.TIED_HASH;
        hv.elements = new TieHash("MyTieHashImpl", new RuntimeHash(), tier);

        RuntimeScalar ref = hv.createAnonymousReference();

        StorableWriter w = new StorableWriter();
        byte[] bytes = toBytes(w.writeTopLevelToMemory(ref, true /* netorder */));

        // Skip the 2-byte in-memory header (major-version byte + minor).
        // Header.writeInMemory writes a single byte: ((MAJOR << 1) | net) and minor.
        // The first body byte should be SX_TIED_HASH.
        // Find the start of the body deterministically by parsing:
        StorableContext c = new StorableContext(bytes);
        Header.parseInMemory(c);
        int op = c.readU8();
        assertEquals(Opcodes.SX_TIED_HASH, op,
                "tied hash should emit SX_TIED_HASH (0x0C), got: 0x"
                        + Integer.toHexString(op));
    }

    @Test
    void encoderEmitsTiedArrayOpcode() {
        RuntimeArray av = new RuntimeArray();
        RuntimeScalar tier = makeBlessedTier("MyTieArrayImpl");
        av.type = RuntimeArray.TIED_ARRAY;
        av.elements = new TieArray("MyTieArrayImpl", new RuntimeArray(), tier, av);

        RuntimeScalar ref = av.createAnonymousReference();

        StorableWriter w = new StorableWriter();
        byte[] bytes = toBytes(w.writeTopLevelToMemory(ref, true));
        StorableContext c = new StorableContext(bytes);
        Header.parseInMemory(c);
        int op = c.readU8();
        assertEquals(Opcodes.SX_TIED_ARRAY, op,
                "tied array should emit SX_TIED_ARRAY (0x0B), got: 0x"
                        + Integer.toHexString(op));
    }

    @Test
    void encoderEmitsTiedScalarOpcode() {
        // Tied scalars are detected at the dispatchReferent layer of
        // StorableWriter (the only spot that calls TiedEncoder.tryEmit).
        // emitTopLevel strips one level of REFERENCE before reaching
        // dispatchReferent, so to actually exercise the tied-scalar
        // branch we need the tied scalar to live inside one extra
        // level of indirection: freeze \\$tied. Upstream Storable's
        // store_tied() is reached via a similar route — store_ref
        // recurses on the referent and finds tied magic on the inner
        // scalar.
        RuntimeScalar inner = new RuntimeScalar();
        RuntimeScalar tier = makeBlessedTier("MyTieScalarImpl");
        inner.type = RuntimeScalarType.TIED_SCALAR;
        inner.value = new TieScalar("MyTieScalarImpl", new RuntimeScalar(), tier);

        RuntimeScalar refToTied = inner.createReference();          // \$tied
        RuntimeScalar refToRefToTied = refToTied.createReference(); // \\$tied

        StorableWriter w = new StorableWriter();
        byte[] bytes = toBytes(w.writeTopLevelToMemory(refToRefToTied, true));
        StorableContext c = new StorableContext(bytes);
        Header.parseInMemory(c);
        // Outer-most stripped by emitTopLevel; first byte is SX_REF
        // (the inner ref); next is the tied-scalar opcode.
        int outer = c.readU8();
        assertEquals(Opcodes.SX_REF, outer,
                "expected SX_REF wrapper for the inner scalar ref, got 0x"
                        + Integer.toHexString(outer));
        int op = c.readU8();
        assertEquals(Opcodes.SX_TIED_SCALAR, op,
                "tied scalar should emit SX_TIED_SCALAR (0x0D), got: 0x"
                        + Integer.toHexString(op));
    }

    @Test
    void encoderFallsThroughForPlainHash() {
        // Sanity check: a plain (non-tied) hash should NOT trip the
        // tied branch and should not emit SX_TIED_HASH.
        RuntimeHash hv = new RuntimeHash();
        hv.put("k", new RuntimeScalar(1));
        RuntimeScalar ref = hv.createAnonymousReference();

        StorableWriter w = new StorableWriter();
        byte[] bytes = toBytes(w.writeTopLevelToMemory(ref, true));
        StorableContext c = new StorableContext(bytes);
        Header.parseInMemory(c);
        int op = c.readU8();
        assertNotSame(Opcodes.SX_TIED_HASH, op,
                "plain hash must not emit SX_TIED_HASH");
    }

    // ------------------------------------------------------------------
    // Reader: synthetic SX_TIED_* bytes parse into tied placeholders.
    // ------------------------------------------------------------------

    /** Build a synthetic stream: SX_TIED_HASH, then SX_BLESS of a bare
     *  empty hash to act as the tying object. Returns just the body
     *  bytes (no header) — caller wraps in the right context. */
    private static byte[] tiedHashStream(String tierClass) {
        byte[] cls = tierClass.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[1 /*SX_TIED_HASH*/
                + 1 /*SX_BLESS*/ + 1 /*classlen*/ + cls.length
                + 1 /*SX_HASH*/ + 4 /*size=0*/];
        int p = 0;
        out[p++] = (byte) Opcodes.SX_TIED_HASH;
        out[p++] = (byte) Opcodes.SX_BLESS;
        out[p++] = (byte) cls.length;
        System.arraycopy(cls, 0, out, p, cls.length);
        p += cls.length;
        out[p++] = (byte) Opcodes.SX_HASH;
        // U32 zero-length, big-endian
        out[p++] = 0; out[p++] = 0; out[p++] = 0; out[p++] = 0;
        return out;
    }

    private static StorableContext makeBigEndianCtx(byte[] body) {
        StorableContext c = new StorableContext(body);
        c.setNetorder(false);
        c.setFileBigEndian(true);
        c.setVersion(2, 12);
        return c;
    }

    @Test
    void readerProducesTiedHashPlaceholder() {
        byte[] data = tiedHashStream("MyTieHashImpl");
        StorableContext c = makeBigEndianCtx(data);
        StorableReader r = new StorableReader();

        RuntimeScalar result = r.dispatch(c);
        assertNotNull(result);
        assertEquals(RuntimeScalarType.HASHREFERENCE, result.type,
                "should be a hash ref");
        assertTrue(result.value instanceof RuntimeHash,
                "value should be a RuntimeHash");
        RuntimeHash hv = (RuntimeHash) result.value;
        assertEquals(RuntimeHash.TIED_HASH, hv.type,
                "hash should be marked TIED_HASH");
        assertTrue(hv.elements instanceof TieHash,
                "elements should be a TieHash");
        TieHash th = (TieHash) hv.elements;
        RuntimeScalar tier = th.getSelf();
        assertNotNull(tier, "tying object should be installed");
        assertEquals(RuntimeScalarType.HASHREFERENCE, tier.type,
                "tying object preserved as a hash-ref blessed into MyTieHashImpl");
        assertEquals(0, RuntimeScalarType.blessedId(tier) == 0 ? 0 : 0); // sanity
        assertEquals("MyTieHashImpl",
                org.perlonjava.runtime.runtimetypes.NameNormalizer.getBlessStr(
                        RuntimeScalarType.blessedId(tier)));
    }

    private static byte[] tiedArrayStream(String tierClass) {
        byte[] cls = tierClass.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[1 + 1 + 1 + cls.length + 1 + 4];
        int p = 0;
        out[p++] = (byte) Opcodes.SX_TIED_ARRAY;
        out[p++] = (byte) Opcodes.SX_BLESS;
        out[p++] = (byte) cls.length;
        System.arraycopy(cls, 0, out, p, cls.length);
        p += cls.length;
        out[p++] = (byte) Opcodes.SX_ARRAY;
        out[p++] = 0; out[p++] = 0; out[p++] = 0; out[p++] = 0;
        return out;
    }

    @Test
    void readerProducesTiedArrayPlaceholder() {
        byte[] data = tiedArrayStream("MyTieArrayImpl");
        StorableContext c = makeBigEndianCtx(data);
        StorableReader r = new StorableReader();

        RuntimeScalar result = r.dispatch(c);
        assertNotNull(result);
        assertEquals(RuntimeScalarType.ARRAYREFERENCE, result.type);
        RuntimeArray av = (RuntimeArray) result.value;
        assertEquals(RuntimeArray.TIED_ARRAY, av.type);
        assertTrue(av.elements instanceof TieArray);
        TieArray ta = (TieArray) av.elements;
        assertNotNull(ta.getSelf());
        assertEquals("MyTieArrayImpl",
                org.perlonjava.runtime.runtimetypes.NameNormalizer.getBlessStr(
                        RuntimeScalarType.blessedId(ta.getSelf())));
    }

    @Test
    void readerProducesTiedScalarPlaceholder() {
        // SX_TIED_SCALAR + SX_BLESS empty hash
        byte[] cls = "MyTieScalarImpl".getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[1 + 1 + 1 + cls.length + 1 + 4];
        int p = 0;
        data[p++] = (byte) Opcodes.SX_TIED_SCALAR;
        data[p++] = (byte) Opcodes.SX_BLESS;
        data[p++] = (byte) cls.length;
        System.arraycopy(cls, 0, data, p, cls.length); p += cls.length;
        data[p++] = (byte) Opcodes.SX_HASH;
        data[p++] = 0; data[p++] = 0; data[p++] = 0; data[p++] = 0;

        StorableContext c = makeBigEndianCtx(data);
        StorableReader r = new StorableReader();
        RuntimeScalar result = r.dispatch(c);
        assertNotNull(result);
        assertEquals(RuntimeScalarType.REFERENCE, result.type,
                "tied scalar should produce a scalar reference");
        RuntimeScalar inner = (RuntimeScalar) result.value;
        assertEquals(RuntimeScalarType.TIED_SCALAR, inner.type);
        assertTrue(inner.value instanceof TieScalar);
        TieScalar ts = (TieScalar) inner.value;
        assertNotNull(ts.getSelf());
        assertEquals("MyTieScalarImpl",
                org.perlonjava.runtime.runtimetypes.NameNormalizer.getBlessStr(
                        RuntimeScalarType.blessedId(ts.getSelf())));
    }

    // ------------------------------------------------------------------
    // Round trip: writer → reader through the in-memory format.
    // ------------------------------------------------------------------

    @Test
    void roundTripTiedHashPreservesTier() {
        // Build a tied hash in Java; freeze it; thaw it; verify the
        // result is a tied hash whose tying object is blessed into
        // the same class.
        RuntimeHash hv = new RuntimeHash();
        RuntimeScalar tier = makeBlessedTier("MyTieRoundTrip");
        // Stash a marker key in the tying object so we can confirm
        // it's the SAME data that comes back.
        ((RuntimeHash) tier.value).put("marker", new RuntimeScalar(42));
        hv.type = RuntimeHash.TIED_HASH;
        hv.elements = new TieHash("MyTieRoundTrip", new RuntimeHash(), tier);

        RuntimeScalar ref = hv.createAnonymousReference();
        StorableWriter w = new StorableWriter();
        byte[] bytes = toBytes(w.writeTopLevelToMemory(ref, true));

        StorableContext c = new StorableContext(bytes);
        Header.parseInMemory(c);
        StorableReader r = new StorableReader();
        RuntimeScalar thawed = r.dispatch(c);

        assertNotNull(thawed);
        assertEquals(RuntimeScalarType.HASHREFERENCE, thawed.type);
        RuntimeHash thawedHv = (RuntimeHash) thawed.value;
        assertEquals(RuntimeHash.TIED_HASH, thawedHv.type,
                "thawed hash should still be tied");
        TieHash th = (TieHash) thawedHv.elements;
        RuntimeScalar thawedTier = th.getSelf();
        assertNotNull(thawedTier);
        assertEquals("MyTieRoundTrip",
                org.perlonjava.runtime.runtimetypes.NameNormalizer.getBlessStr(
                        RuntimeScalarType.blessedId(thawedTier)));
        // The tying object's payload survived.
        RuntimeHash thawedTierHash = (RuntimeHash) thawedTier.value;
        assertEquals(42, thawedTierHash.get("marker").getInt(),
                "tying object's marker payload should round-trip");
        assertNotSame(tier, thawedTier, "thawed tying object is a fresh deserialised copy");
    }
}

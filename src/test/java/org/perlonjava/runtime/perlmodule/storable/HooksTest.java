package org.perlonjava.runtime.perlmodule.storable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Hooks#readHook}.
 * <p>
 * The full round-trip test exercising a real {@code STORABLE_thaw}
 * method requires booting the Perl interpreter and defining the
 * producing class; that wiring belongs to the Stage C integration
 * suite, so it is kept here marked {@link Disabled} as a reference.
 * <p>
 * The active tests assemble synthetic SX_HOOK byte streams and check
 * that the frame parser correctly:
 * <ul>
 *   <li>reads the flags byte and decodes the object kind (SCALAR /
 *       ARRAY / HASH);</li>
 *   <li>reads inline classnames AND indexed classnames;</li>
 *   <li>reads the frozen-string length in both 1-byte and U32 form;</li>
 *   <li>reads the sub-object list (length + tags) when SHF_HAS_LIST is
 *       set;</li>
 *   <li>raises a clean error when the named class has no
 *       {@code STORABLE_thaw} method (the path that proves the rest
 *       of the frame was parsed without consuming the wrong bytes).</li>
 * </ul>
 */
@Tag("unit")
public class HooksTest {

    private static final Path FIXTURES =
            Paths.get("src/test/resources/storable_fixtures").toAbsolutePath();

    /**
     * Build a context configured for non-netorder big-endian U32 fields
     * (matches what the synthetic byte builders below produce).
     */
    private static StorableContext makeCtx(byte[] bytes) {
        StorableContext c = new StorableContext(bytes);
        c.setNetorder(false);
        c.setFileBigEndian(true);
        c.setVersion(2, 12);
        return c;
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    private static byte[] u8(int v) { return new byte[] {(byte) v}; }

    private static byte[] u32be(long v) {
        return new byte[] {
                (byte) (v >>> 24), (byte) (v >>> 16),
                (byte) (v >>> 8),  (byte) v
        };
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    // ------------------------------------------------------------------
    // Frame-parsing tests with no real STORABLE_thaw method.
    // The error message proves the frame was parsed to completion.
    // ------------------------------------------------------------------

    @Test
    void hashHookInlineClassNoThawMethod() {
        // flags = HASH (0x02) | SHF_HAS_LIST (0x80) = 0x82
        // inline class "NoSuchHookClass1" (16 bytes)
        // frozen "cookie-A" (8 bytes), 1-byte len
        // list len 0
        byte[] data = cat(
                u8(0x82),
                u8(16), ascii("NoSuchHookClass1"),
                u8(8),  ascii("cookie-A"),
                u8(0)
        );
        StorableContext c = makeCtx(data);
        StorableReader r = new StorableReader();
        StorableFormatException ex = assertThrows(StorableFormatException.class,
                () -> Hooks.readHook(r, c));
        String m = ex.getMessage();
        assertTrue(m.contains("no STORABLE_thaw"),
                "expected missing-method message, got: " + m);
        assertTrue(m.contains("NoSuchHookClass1"),
                "expected classname in message, got: " + m);
    }

    @Test
    void scalarHookNoListNoThawMethod() {
        // flags = SCALAR (0x00), no list, inline class.
        byte[] data = cat(
                u8(0x00),
                u8(16), ascii("NoSuchHookClassB"),
                u8(3),  ascii("xyz")
        );
        StorableContext c = makeCtx(data);
        StorableReader r = new StorableReader();
        StorableFormatException ex = assertThrows(StorableFormatException.class,
                () -> Hooks.readHook(r, c));
        assertTrue(ex.getMessage().contains("NoSuchHookClassB"),
                "expected classname, got: " + ex.getMessage());
    }

    @Test
    void arrayHookLargeStrLenNoThawMethod() {
        // flags = ARRAY (0x01) | SHF_LARGE_STRLEN (0x08) = 0x09
        // inline class "NoSuchHookClassC" (16 bytes)
        // frozen len U32=5 (big-endian), bytes "abcde"
        byte[] data = cat(
                u8(0x09),
                u8(16), ascii("NoSuchHookClassC"),
                u32be(5), ascii("abcde")
        );
        StorableContext c = makeCtx(data);
        StorableReader r = new StorableReader();
        StorableFormatException ex = assertThrows(StorableFormatException.class,
                () -> Hooks.readHook(r, c));
        assertTrue(ex.getMessage().contains("NoSuchHookClassC"));
    }

    @Test
    void indexedClassnameNoThawMethod() {
        // First, pre-register a class at index 0 in a fresh context.
        // We then build an SX_HOOK frame using SHF_IDX_CLASSNAME.
        // flags = HASH (0x02) | SHF_IDX_CLASSNAME (0x20) = 0x22
        byte[] data = cat(
                u8(0x22),
                u8(0),               // class index 0
                u8(4), ascii("data") // frozen string "data"
        );
        StorableContext c = makeCtx(data);
        c.recordClass("PreRegisteredHookCls");
        StorableReader r = new StorableReader();
        StorableFormatException ex = assertThrows(StorableFormatException.class,
                () -> Hooks.readHook(r, c));
        assertTrue(ex.getMessage().contains("PreRegisteredHookCls"),
                "indexed classname should resolve via class table; got: "
                        + ex.getMessage());
    }

    @Test
    void recurseChainConsumesFlagsAgainNoThawMethod() {
        // First flags byte sets SHF_NEED_RECURSE; the recursed value is
        // an SX_SV_UNDEF (opcode 14) which records itself in the seen
        // table. The next flags byte (0x02 = plain HASH) actually
        // dictates the rest of the frame.
        byte[] data = cat(
                u8(0x02 | 0x40),       // HASH | SHF_NEED_RECURSE
                u8(14),                // SX_SV_UNDEF — recursion target
                u8(0x02),              // post-recursion flags: HASH, no list
                u8(16), ascii("NoSuchHookClassD"),
                u8(2), ascii("zz")
        );
        StorableContext c = makeCtx(data);
        StorableReader r = new StorableReader();
        StorableFormatException ex = assertThrows(StorableFormatException.class,
                () -> Hooks.readHook(r, c));
        assertTrue(ex.getMessage().contains("NoSuchHookClassD"),
                "recursion drain should land on second flags byte; got: "
                        + ex.getMessage());
    }

    @Test
    void rejectsTiedExtraSubType() {
        // flags = SHT_EXTRA (0x03) — not supported.
        byte[] data = cat(u8(0x03));
        StorableContext c = makeCtx(data);
        StorableReader r = new StorableReader();
        StorableFormatException ex = assertThrows(StorableFormatException.class,
                () -> Hooks.readHook(r, c));
        assertTrue(ex.getMessage().contains("SHT_EXTRA")
                        || ex.getMessage().contains("tied"),
                "expected SHT_EXTRA rejection, got: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // Real-fixture round trip — needs the producer class loaded into
    // the Perl interpreter. Stage C integration will enable this.
    // ------------------------------------------------------------------

    @Test
    @Disabled("Stage C integration: requires Perl-side Hookey class with"
            + " STORABLE_thaw to be loaded. The frame itself parses; it is"
            + " only the dispatch to STORABLE_thaw that is unverified here.")
    void simpleHookFixtureRoundTrip() throws IOException {
        byte[] data = Files.readAllBytes(
                FIXTURES.resolve("hooks/simple_hook.bin"));
        StorableContext c = new StorableContext(data);
        Header.parseFile(c);
        StorableReader r = new StorableReader();
        RuntimeScalar v = r.dispatch(c);
        assertNotNull(v);
        // The expected value is a HASH ref blessed into 'Hookey' with
        // {v => "xyzzy"}. Validating that requires reflecting through
        // the bless/hash APIs and is out of scope for this stage.
        assertEquals(0, c.remaining(),
                "all bytes should be consumed by the hook frame");
    }
}

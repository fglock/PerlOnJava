package org.perlonjava.runtime.perlmodule.storable;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.regex.RuntimeRegex;
import org.perlonjava.runtime.runtimetypes.NameNormalizer;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RegexpEncoder} (writer) and {@link Misc#readRegexp}
 * (reader) — both halves of {@code SX_REGEXP}.
 * <p>
 * The wire format is {@code SX_REGEXP <op_flags:u8> <re_len> <re_bytes>
 * <flags_len:u8> <flag_bytes>} where {@code re_len} is one byte unless
 * {@code op_flags & 0x01} (SHR_U32_RE_LEN) is set, in which case it is a
 * 4-byte U32 in the file's byte order. See {@code store_regexp} /
 * {@code retrieve_regexp} in upstream {@code Storable.xs}.
 */
@Tag("unit")
public class RegexpStorableTest {

    private static final Path FIXTURES =
            Paths.get("src/test/resources/storable_fixtures").toAbsolutePath();

    /** Convert the encoded String (chars 0..255) returned by
     *  {@link StorableWriter} into a real byte array. */
    private static byte[] toBytes(String encoded) {
        byte[] out = new byte[encoded.length()];
        for (int i = 0; i < encoded.length(); i++) {
            out[i] = (byte) encoded.charAt(i);
        }
        return out;
    }

    /** Build a blessed {@code Regexp} scalar mirroring what {@code qr//}
     *  produces in real perlonjava (a REGEX-typed scalar wrapping a
     *  RuntimeRegex blessed into class "Regexp"). */
    private static RuntimeScalar makeQrLike(String pattern, String flags) {
        // cloneTracked so we don't pollute the global regex cache with our
        // blessId mutation.
        RuntimeRegex regex = RuntimeRegex.compile(pattern, flags).cloneTracked();
        regex.blessId = NameNormalizer.getBlessId("Regexp");
        return new RuntimeScalar(regex);
    }

    /** Locate a unique byte sub-sequence in {@code haystack}; return the
     *  start index, or -1 if not found. */
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

    /** Round-trip helper: freeze a regex via the writer, then thaw it
     *  via the reader. Returns the recovered RuntimeScalar (REGEX type). */
    private static RuntimeScalar roundTrip(RuntimeScalar regexScalar, boolean netorder) {
        // freeze \$qr produces a SCALAR_REF wrapping the qr//. We mimic
        // that shape by calling createReference() on the regex scalar.
        RuntimeScalar refToRegex = regexScalar.createReference();
        StorableWriter w = new StorableWriter();
        byte[] frozen = toBytes(w.writeTopLevelToMemory(refToRegex, netorder));

        StorableContext c = new StorableContext(frozen);
        Header.parseInMemory(c);
        StorableReader r = new StorableReader();
        RuntimeScalar top = r.dispatch(c);
        assertNotNull(top, "thaw returned null");
        // The frozen value was a scalar-ref to the regex; after thaw the
        // top-level is a REFERENCE to the regex scalar.
        assertEquals(RuntimeScalarType.REFERENCE, top.type,
                "expected top-level REFERENCE, got type=" + top.type);
        RuntimeScalar inner = (RuntimeScalar) top.value;
        return inner;
    }

    // -------- 1. simple pattern, no flags --------

    @Test
    void roundTripSimplePattern() {
        RuntimeScalar qr = makeQrLike("foo", "");
        RuntimeScalar back = roundTrip(qr, true);
        assertEquals(RuntimeScalarType.REGEX, back.type);
        RuntimeRegex backRegex = (RuntimeRegex) back.value;
        assertEquals("foo", backRegex.patternString);
        assertEquals("", backRegex.getRegexFlags().toFlagString().replaceAll("[^msixn]", ""));
    }

    // -------- 2. one flag --------

    @Test
    void roundTripCaseInsensitive() {
        RuntimeScalar qr = makeQrLike("abc", "i");
        RuntimeScalar back = roundTrip(qr, true);
        assertEquals(RuntimeScalarType.REGEX, back.type);
        RuntimeRegex backRegex = (RuntimeRegex) back.value;
        assertEquals("abc", backRegex.patternString);
        assertTrue(backRegex.getRegexFlags().isCaseInsensitive(),
                "expected /i to round-trip");
    }

    // -------- 3. multiple flags --------

    @Test
    void roundTripMultipleFlags() {
        RuntimeScalar qr = makeQrLike("x", "ims");
        RuntimeScalar back = roundTrip(qr, true);
        assertEquals(RuntimeScalarType.REGEX, back.type);
        RuntimeRegex backRegex = (RuntimeRegex) back.value;
        assertEquals("x", backRegex.patternString);
        assertTrue(backRegex.getRegexFlags().isCaseInsensitive(), "/i preserved");
        assertTrue(backRegex.getRegexFlags().isMultiLine(),       "/m preserved");
        assertTrue(backRegex.getRegexFlags().isDotAll(),          "/s preserved");
    }

    // -------- 4. wire format: SX_REGEXP byte appears at the right place --------

    @Test
    void wireFormatContainsSxRegexp() {
        RuntimeScalar qr = makeQrLike("foo", "i");
        RuntimeScalar refToRegex = qr.createReference();
        StorableWriter w = new StorableWriter();
        byte[] bytes = toBytes(w.writeTopLevelToMemory(refToRegex, true));

        // Expect: <header2> SX_REF SX_BLESS 6 "Regexp" SX_REGEXP 0 3 foo 1 i
        // for nfreeze(\qr/foo/i). Locate SX_REGEXP and check the body.
        int sxRegexpIdx = -1;
        for (int i = 0; i < bytes.length; i++) {
            if ((bytes[i] & 0xFF) == Opcodes.SX_REGEXP) { sxRegexpIdx = i; break; }
        }
        assertTrue(sxRegexpIdx >= 0, "SX_REGEXP byte present");
        // Right after SX_REGEXP: op_flags=0, then re_len=3, then "foo".
        assertEquals(0x00, bytes[sxRegexpIdx + 1] & 0xFF, "op_flags=0 (small re_len)");
        assertEquals(3,    bytes[sxRegexpIdx + 2] & 0xFF, "re_len=3");
        assertArrayEquals("foo".getBytes(StandardCharsets.US_ASCII),
                java.util.Arrays.copyOfRange(bytes, sxRegexpIdx + 3, sxRegexpIdx + 6),
                "re_bytes='foo'");
        assertEquals(1, bytes[sxRegexpIdx + 6] & 0xFF, "flags_len=1");
        assertEquals('i', bytes[sxRegexpIdx + 7] & 0xFF, "flag='i'");
    }

    // -------- 5. cross-perl interop: byte-exact match against upstream --------

    /**
     * Upstream {@code nfreeze \qr/abc/i} produces a body equivalent to:
     * <pre>05 &lt;minor&gt; 04 11 06 52 65 67 65 78 70 20 00 03 61 62 63 01 69</pre>
     * <ul>
     *   <li>{@code 05} — header byte: (major=2)&lt;&lt;1 | netorder=1</li>
     *   <li>{@code &lt;minor&gt;} — Storable binary minor (varies by perl version
     *       — 11 on the perl we tested with, 12 on perlonjava's table)</li>
     *   <li>{@code 04} — SX_REF</li>
     *   <li>{@code 11} — SX_BLESS</li>
     *   <li>{@code 06} "Regexp" — class name (length 6 + 6 bytes)</li>
     *   <li>{@code 20} — SX_REGEXP</li>
     *   <li>{@code 00} — op_flags (no SHR_U32_RE_LEN)</li>
     *   <li>{@code 03} "abc" — pat-len + pattern</li>
     *   <li>{@code 01} "i" — flags-len + flags</li>
     * </ul>
     * Compare bytes from offset 2 (skipping the minor) to keep the
     * assertion stable against perlonjava's MINOR=12 vs upstream's
     * MINOR=11. Captured via:
     * {@code perl -MStorable=nfreeze -e 'print unpack("H*", nfreeze \qr/abc/i)'}
     */
    @Test
    void interopBytesExactMatchUpstream() {
        RuntimeScalar qr = makeQrLike("abc", "i");
        RuntimeScalar refToRegex = qr.createReference();
        StorableWriter w = new StorableWriter();
        byte[] got = toBytes(w.writeTopLevelToMemory(refToRegex, true));

        byte[] expected = hex("050b0411065265676578702000036162630169");
        // First byte: major-and-netorder is identical (05). Skip second
        // byte (minor) which differs by perl version. Body must match.
        assertEquals(expected[0], got[0], "header byte 0 (major|netorder)");
        assertArrayEquals(
                java.util.Arrays.copyOfRange(expected, 2, expected.length),
                java.util.Arrays.copyOfRange(got, 2, got.length),
                "post-header bytes of nfreeze \\qr/abc/i should be "
                        + "byte-identical to upstream");
    }

    /** Same idea for a flagless pattern — flags_len=0. */
    @Test
    void interopBytesNoFlagsMatchUpstream() {
        RuntimeScalar qr = makeQrLike("foo", "");
        RuntimeScalar refToRegex = qr.createReference();
        StorableWriter w = new StorableWriter();
        byte[] got = toBytes(w.writeTopLevelToMemory(refToRegex, true));

        // perl -MStorable=nfreeze -e 'print unpack("H*", nfreeze \qr/foo/)'
        // => 050b041106526567657870200003666f6f00
        byte[] expected = hex("050b041106526567657870200003666f6f00");
        assertEquals(expected[0], got[0], "header byte 0 (major|netorder)");
        assertArrayEquals(
                java.util.Arrays.copyOfRange(expected, 2, expected.length),
                java.util.Arrays.copyOfRange(got, 2, got.length),
                "post-header bytes of nfreeze \\qr/foo/ should be "
                        + "byte-identical to upstream");
    }

    // -------- 6. existing fixture round-trip (reader-only) --------

    /**
     * The pre-committed fixture {@code misc/regexp.bin} was produced by
     * {@code nstore qr/^foo.*bar$/i, ...}. Verify the reader recovers the
     * pattern and flags. This exercises the read path in isolation —
     * no writer involved.
     */
    @Test
    void readsExistingRegexpFixture() throws Exception {
        byte[] data = Files.readAllBytes(FIXTURES.resolve("misc/regexp.bin"));
        StorableContext c = new StorableContext(data);
        Header.parseFile(c);
        StorableReader r = new StorableReader();
        RuntimeScalar top = r.dispatch(c);
        assertNotNull(top);
        // The fixture is `nstore qr/^foo.*bar$/i, ...` — top-level is a
        // REGEX-typed scalar (qr// is itself a reference in perl).
        assertEquals(RuntimeScalarType.REGEX, top.type,
                "expected REGEX top-level, got type=" + top.type);
        RuntimeRegex regex = (RuntimeRegex) top.value;
        assertEquals("^foo.*bar$", regex.patternString);
        assertTrue(regex.getRegexFlags().isCaseInsensitive(), "/i flag preserved");
    }

    /** Decode a hex string into a byte array. */
    private static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}

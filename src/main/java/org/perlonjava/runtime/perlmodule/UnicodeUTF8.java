package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.PerlUtfString;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.perlonjava.runtime.runtimetypes.RuntimeContextType.SCALAR;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.BYTE_STRING;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.CODE;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.STRING;

/**
 * Java-backed implementation of CPAN {@code Unicode::UTF8} (XSLoader).
 *
 * <p>Validation, replacement, and maximal-subpart behaviour follow the logic shipped with
 * Unicode-UTF8 0.70 (c-utf8 DFA), not ICU4J — plain UTF-8 is entirely handled in Java.</p>
 */
public class UnicodeUTF8 extends PerlModuleBase {

    public static final String XS_VERSION = "0.70";

    private static final int S_ERROR = 0;
    private static final int S_ACCEPT = 6;

    /*
     * Table from Unicode-UTF8-0.70 utf8_dfa32.h (BSD-licensed, Christian Hansen).
     * utf8_dfa_step(state, b) == (UTF8_DFA[b] >> state) & 31
     */
    private static final int[] UTF8_DFA = {
            384, 384, 384, 384, 384, 384, 384, 384,
            384, 384, 384, 384, 384, 384, 384, 384,
            384, 384, 384, 384, 384, 384, 384, 384,
            384, 384, 384, 384, 384, 384, 384, 384,
            384, 384, 384, 384, 384, 384, 384, 384,
            384, 384, 384, 384, 384, 384, 384, 384,
            384, 384, 384, 384, 384, 384, 384, 384,
            384, 384, 384, 384, 384, 384, 384, 384,
            384, 384, 384, 384, 384, 384, 384, 384,
            384, 384, 384, 384, 384, 384, 384, 384,
            384, 384, 384, 384, 384, 384, 384, 384,
            384, 384, 384, 384, 384, 384, 384, 384,
            384, 384, 384, 384, 384, 384, 384, 384,
            384, 384, 384, 384, 384, 384, 384, 384,
            384, 384, 384, 384, 384, 384, 384, 384,
            384, 384, 384, 384, 384, 384, 384, 384,
            554041376, 554041376, 554041376, 554041376, 554041376, 554041376, 554041376, 554041376,
            554041376, 554041376, 554041376, 554041376, 554041376, 554041376, 554041376, 554041376,
            537266208, 537266208, 537266208, 537266208, 537266208, 537266208, 537266208, 537266208,
            537266208, 537266208, 537266208, 537266208, 537266208, 537266208, 537266208, 537266208,
            8783904, 8783904, 8783904, 8783904, 8783904, 8783904, 8783904, 8783904,
            8783904, 8783904, 8783904, 8783904, 8783904, 8783904, 8783904, 8783904,
            8783904, 8783904, 8783904, 8783904, 8783904, 8783904, 8783904, 8783904,
            8783904, 8783904, 8783904, 8783904, 8783904, 8783904, 8783904, 8783904,
            0, 0, 1024, 1024, 1024, 1024, 1024, 1024,
            1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024,
            1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024,
            1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024,
            1216, 64, 64, 64, 64, 64, 64, 64,
            64, 64, 64, 64, 64, 1600, 64, 64,
            704, 1152, 1152, 1152, 1536, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0,
    };

    public UnicodeUTF8() {
        super("Unicode::UTF8", false);
    }

    public static void initialize() {
        UnicodeUTF8 m = new UnicodeUTF8();
        m.initializeExporter();
        GlobalVariable.getGlobalVariable("Unicode::UTF8::VERSION").set(new RuntimeScalar(XS_VERSION));
        m.defineExport("EXPORT_OK", "decode_utf8", "encode_utf8", "valid_utf8");
        m.defineExportTag("all", "decode_utf8", "encode_utf8", "valid_utf8");
        try {
            m.registerMethod("decode_utf8", null);
            m.registerMethod("encode_utf8", null);
            m.registerMethod("valid_utf8", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Unicode::UTF8 method: " + e.getMessage());
        }
    }

    private static int dfaStep(int state, int b) {
        return (UTF8_DFA[b] >> state) & 31;
    }

    private static boolean utf8BufferValid(byte[] b, int pos, int len) {
        int state = S_ACCEPT;
        for (int i = pos; i < pos + len; i++) {
            state = dfaStep(state, b[i] & 0xFF);
            if (state == S_ERROR) {
                return false;
            }
        }
        return state == S_ACCEPT;
    }

    private static int utf8MaximalSubpart(byte[] b, int pos, int rem) {
        int state = S_ACCEPT;
        for (int i = 0; i < rem; i++) {
            state = dfaStep(state, b[pos + i] & 0xFF);
            if (state == S_ACCEPT) {
                return i + 1;
            }
            if (state == S_ERROR) {
                return i > 0 ? i : 1;
            }
        }
        return rem;
    }

    private static int utf8MaximalPrefix(byte[] b, int pos, int len) {
        int state = S_ACCEPT;
        int prefix = 0;
        for (int i = pos; i < pos + len; i++) {
            state = dfaStep(state, b[i] & 0xFF);
            if (state == S_ACCEPT) {
                prefix = i - pos + 1;
            } else if (state == S_ERROR) {
                break;
            }
        }
        return prefix;
    }

    private static String hexByteSeq(byte[] b, int pos, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", b[pos + i] & 0xFF));
        }
        return sb.toString();
    }

    /** Perl xs_utf8_downgrade over UTF-8 bytes of a UTF-8-flagged string (C2/C3 only non-ASCII). */
    private static byte[] perlUtf8DowngradeOctets(RuntimeScalar in, String wideMessage) {
        byte[] u8 = in.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(u8.length);
        int i = 0;
        while (i < u8.length) {
            int c = u8[i] & 0xFF;
            if (c < 0x80) {
                out.write(c);
                i++;
            } else {
                if (i + 1 >= u8.length || (c & 0xFE) != 0xC2) {
                    dieWideChar(wideMessage);
                }
                int c2 = u8[i + 1] & 0xFF;
                if ((c2 & 0xC0) != 0x80) {
                    dieWideChar(wideMessage);
                }
                out.write((c & 0x1F) << 6 | (c2 & 0x3F));
                i += 2;
            }
        }
        return out.toByteArray();
    }

    private static void dieWideChar(String message) {
        WarnDie.die(new RuntimeScalar(message), new RuntimeScalar("\n"));
    }

    private static byte[] inputOctetsDecode(RuntimeScalar in) {
        if (in.type == STRING) {
            return perlUtf8DowngradeOctets(in, "Can't decode a wide character string");
        }
        String s = in.toString();
        byte[] b = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch > 0xFF) {
                dieWideChar("Can't decode a wide character string");
            }
            b[i] = (byte) ch;
        }
        return b;
    }

    private static byte[] inputOctetsValid(RuntimeScalar in) {
        if (in.type == STRING) {
            return perlUtf8DowngradeOctets(in, "Can't validate a wide character string");
        }
        String s = in.toString();
        byte[] b = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch > 0xFF) {
                dieWideChar("Can't validate a wide character string");
            }
            b[i] = (byte) ch;
        }
        return b;
    }

    /**
     * UTF-8 byte triple that encodes a surrogate scalar (U+D800..U+DFFF) — always illegal in UTF-8.
     * Matches XS tests for {@code decode_utf8} under {@code use warnings FATAL => 'utf8'}.
     */
    private static boolean isSurrogateIllegalUtf83At(byte[] b, int pos) {
        if (pos + 3 > b.length) {
            return false;
        }
        int b0 = b[pos] & 0xFF;
        int b1 = b[pos + 1] & 0xFF;
        int b2 = b[pos + 2] & 0xFF;
        if (b0 != 0xED || b1 < 0xA0 || b1 > 0xBF || b2 < 0x80 || b2 > 0xBF) {
            return false;
        }
        int cp = 0xD800 + ((b1 - 0xA0) << 6) + (b2 - 0x80);
        return cp >= 0xD800 && cp <= 0xDFFF;
    }

    private static void emitUtf8WarnOrDie(String message) {
        if (WarningFlags.isWarningSuppressedAtRuntime("utf8")) {
            return;
        }
        // Do not route through warnings::warnif from Java: warnIf's caller(0) is wrong here.
        // Use the bits saved for this XS call site (same source as caller()[9] in Perl).
        String bits = Warnings.getJavaNativeXsCallSiteWarningBits();
        boolean categoryEnabled = bits != null && WarningFlags.isEnabledInBits(bits, "utf8");
        if (!categoryEnabled) {
            if (Warnings.isWarnFlagSet()) {
                WarnDie.warn(new RuntimeScalar(message), new RuntimeScalar(""));
            }
            return;
        }
        if (WarningFlags.isFatalInBits(bits, "utf8")) {
            WarnDie.die(new RuntimeScalar(message), new RuntimeScalar("\n"));
        } else {
            WarnDie.warn(new RuntimeScalar(message), new RuntimeScalar(""));
        }
    }

    public static RuntimeList decode_utf8(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            throw new IllegalStateException("decode_utf8: need octets");
        }
        RuntimeScalar oct = args.get(0);
        RuntimeScalar fallback =
                args.size() > 1 && args.get(1).type == CODE ? args.get(1) : null;

        byte[] b = inputOctetsDecode(oct);
        if (utf8BufferValid(b, 0, b.length)) {
            RuntimeScalar r = new RuntimeScalar(new String(b, StandardCharsets.UTF_8));
            r.type = STRING;
            return new RuntimeList(r);
        }

        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (pos < b.length) {
            int good = utf8MaximalPrefix(b, pos, b.length - pos);
            if (good > 0) {
                out.append(new String(b, pos, good, StandardCharsets.UTF_8));
                pos += good;
            }
            if (pos >= b.length) {
                break;
            }
            int skip = utf8MaximalSubpart(b, pos, b.length - pos);
            if (!WarningFlags.isWarningSuppressedAtRuntime("utf8")
                    && pos + 3 <= b.length
                    && isSurrogateIllegalUtf83At(b, pos)) {
                String hex3 = hexByteSeq(b, pos, 3);
                WarnDie.die(
                        new RuntimeScalar("Can't decode ill-formed UTF-8 octet sequence <" + hex3 + "> in position " + pos),
                        new RuntimeScalar("\n"));
            }
            String hex = hexByteSeq(b, pos, skip);
            emitUtf8WarnOrDie("Can't decode ill-formed UTF-8 octet sequence <" + hex + "> in position " + pos);
            if (fallback != null) {
                byte[] slice = new byte[skip];
                System.arraycopy(b, pos, slice, 0, skip);
                RuntimeScalar octSlice = byteArrayToByteString(slice);
                RuntimeList fr = RuntimeCode.apply(
                        fallback,
                        new RuntimeArray(octSlice, new RuntimeScalar(0), new RuntimeScalar(pos)),
                        SCALAR);
                appendDecodeFallbackChunk(out, fr);
            } else {
                out.append('\uFFFD');
            }
            pos += skip;
        }
        RuntimeScalar r = new RuntimeScalar(out.toString());
        r.type = STRING;
        return new RuntimeList(r);
    }

    private static void appendDecodeFallbackChunk(StringBuilder out, RuntimeList fr) {
        if (fr.isEmpty()) {
            return;
        }
        RuntimeScalar v = fr.scalar();
        if (v.type == STRING) {
            out.append(v.toString());
        } else {
            String s = v.toString();
            for (int i = 0; i < s.length(); i++) {
                int c = s.charAt(i) & 0xFF;
                out.append((char) c);
            }
        }
    }

    private static RuntimeScalar byteArrayToByteString(byte[] raw) {
        RuntimeScalar r = new RuntimeScalar(new String(raw, StandardCharsets.ISO_8859_1));
        r.type = BYTE_STRING;
        return r;
    }

    public static RuntimeList encode_utf8(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            throw new IllegalStateException("encode_utf8: need string");
        }
        RuntimeScalar s = args.get(0);
        RuntimeScalar fallback =
                args.size() > 1 && args.get(1).type == CODE ? args.get(1) : null;

        String str = s.toString();
        /*
         * SvUTF8 (STRING) with one ISO-8859-1 code unit per underlying octet — including
         * ill-formed UTF-8 left by Encode::_utf8_on (t/090_non_shortest_form encode tests).
         */
        if (s.type == STRING
                && s.utf8UncheckedOctets
                && str.length() == str.codePoints().count()
                && str.chars().allMatch(ch -> ch >= 0 && ch < 0x100)) {
            byte[] raw = new byte[str.length()];
            for (int i = 0; i < str.length(); i++) {
                raw[i] = (byte) str.charAt(i);
            }
            if (!utf8BufferValid(raw, 0, raw.length)) {
                WarnDie.die(
                        new RuntimeScalar("Can't decode ill-formed UTF-X octet sequence"),
                        new RuntimeScalar("\n"));
            }
            str = new String(raw, StandardCharsets.UTF_8);
            return new RuntimeList(encodeUtf8FromUnicodeString(str, fallback));
        }

        /*
         * Downgraded / native character strings: each character is U+00..U+FF (not SvUTF8).
         * Wider scalars (e.g. pack("U", ...) without SvUTF8) must take the Unicode path.
         */
        if (s.type != STRING && str.codePoints().allMatch(cp -> cp >= 0 && cp < 0x100)) {
            return new RuntimeList(encodeNativeUpgradable(str));
        }

        return new RuntimeList(encodeUtf8FromUnicodeString(str, fallback));
    }

    private static RuntimeScalar encodeUtf8FromUnicodeString(String str, RuntimeScalar fallback) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(str.length() * 2);
        int charPos = 0;
        for (int offset = 0; offset < str.length(); ) {
            PerlUtfString.PerlStep step = PerlUtfString.readOnePerlLogical(str, offset);
            long cp = step.codePoint();
            offset = step.nextJavaIndex();
            if (cp >= 0xD800 && cp <= 0xDFFF) {
                if (!WarningFlags.isWarningSuppressedAtRuntime("utf8")) {
                    WarnDie.die(
                            new RuntimeScalar("Can't represent surrogate code point U+"
                                    + String.format("%04X", cp).toUpperCase(Locale.ROOT)
                                    + " in position " + charPos),
                            new RuntimeScalar("\n"));
                }
                encodeUnmappableReplace(charPos, (int) cp, fallback, bo);
            } else if (cp > 0x10FFFFL) {
                if (!WarningFlags.isWarningSuppressedAtRuntime("utf8")) {
                    WarnDie.die(
                            new RuntimeScalar(
                                    "Can't represent super code point \\x{"
                                            + Long.toUnsignedString(cp, 16).toUpperCase(Locale.ROOT)
                                            + "} in position " + charPos),
                            new RuntimeScalar("\n"));
                }
                encodeUnmappableReplace(charPos, (int) cp, fallback, bo);
            } else {
                bo.writeBytes(new String(Character.toChars((int) cp)).getBytes(StandardCharsets.UTF_8));
            }
            charPos++;
        }
        return byteArrayToByteString(bo.toByteArray());
    }

    private static void encodeUnmappableReplace(
            int charPos,
            int cp,
            RuntimeScalar fallback,
            ByteArrayOutputStream bo) {
        if (fallback != null) {
            int usv = (cp <= 0x10FFFF && (cp & 0xF800) != 0xD800) ? cp : 0;
            RuntimeList fr = RuntimeCode.apply(
                    fallback,
                    new RuntimeArray(new RuntimeScalar(cp), new RuntimeScalar(usv), new RuntimeScalar(charPos)),
                    SCALAR);
            if (!fr.isEmpty()) {
                appendEncodeFallbackBytes(bo, fr.scalar());
            }
        } else {
            bo.writeBytes("\uFFFD".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void appendEncodeFallbackBytes(ByteArrayOutputStream bo, RuntimeScalar v) {
        if (v.type == STRING) {
            bo.writeBytes(v.toString().getBytes(StandardCharsets.UTF_8));
        } else {
            String s = v.toString();
            for (int i = 0; i < s.length(); i++) {
                int c = s.charAt(i) & 0xFF;
                if (c < 0x80) {
                    bo.write(c);
                } else {
                    bo.write(0xC0 | ((c >> 6) & 0x1F));
                    bo.write(0x80 | (c & 0x3F));
                }
            }
        }
    }

    private static RuntimeScalar encodeNativeUpgradable(String latin1Chars) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(latin1Chars.length() * 2);
        for (int i = 0; i < latin1Chars.length(); i++) {
            int c = latin1Chars.charAt(i) & 0xFF;
            if (c < 0x80) {
                bo.write(c);
            } else {
                bo.write(0xC0 | ((c >> 6) & 0x1F));
                bo.write(0x80 | (c & 0x3F));
            }
        }
        return byteArrayToByteString(bo.toByteArray());
    }

    public static RuntimeList valid_utf8(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            throw new IllegalStateException("valid_utf8: need octets");
        }
        RuntimeScalar in = args.get(0);
        byte[] b = inputOctetsValid(in);
        boolean ok = utf8BufferValid(b, 0, b.length);
        return new RuntimeList(new RuntimeScalar(ok));
    }
}

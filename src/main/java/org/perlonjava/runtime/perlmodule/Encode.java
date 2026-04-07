package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.BYTE_STRING;

/**
 * The Encode module for PerlOnJava.
 * Provides character encoding/decoding functionality similar to Perl's Encode module.
 */
public class Encode extends PerlModuleBase {

    private static final Map<String, Charset> CHARSET_ALIASES = new HashMap<>();

    static {
        // Initialize common charset aliases
        CHARSET_ALIASES.put("utf8", StandardCharsets.UTF_8);
        CHARSET_ALIASES.put("UTF8", StandardCharsets.UTF_8);
        CHARSET_ALIASES.put("utf-8", StandardCharsets.UTF_8);
        CHARSET_ALIASES.put("UTF-8", StandardCharsets.UTF_8);

        CHARSET_ALIASES.put("latin1", StandardCharsets.ISO_8859_1);
        CHARSET_ALIASES.put("Latin1", StandardCharsets.ISO_8859_1);
        CHARSET_ALIASES.put("iso-8859-1", StandardCharsets.ISO_8859_1);
        CHARSET_ALIASES.put("ISO-8859-1", StandardCharsets.ISO_8859_1);

        CHARSET_ALIASES.put("ascii", StandardCharsets.US_ASCII);
        CHARSET_ALIASES.put("ASCII", StandardCharsets.US_ASCII);
        CHARSET_ALIASES.put("us-ascii", StandardCharsets.US_ASCII);
        CHARSET_ALIASES.put("US-ASCII", StandardCharsets.US_ASCII);

        CHARSET_ALIASES.put("utf16", StandardCharsets.UTF_16);
        CHARSET_ALIASES.put("UTF16", StandardCharsets.UTF_16);
        CHARSET_ALIASES.put("utf-16", StandardCharsets.UTF_16);
        CHARSET_ALIASES.put("UTF-16", StandardCharsets.UTF_16);

        CHARSET_ALIASES.put("utf16be", StandardCharsets.UTF_16BE);
        CHARSET_ALIASES.put("UTF16BE", StandardCharsets.UTF_16BE);
        CHARSET_ALIASES.put("utf-16be", StandardCharsets.UTF_16BE);
        CHARSET_ALIASES.put("UTF-16BE", StandardCharsets.UTF_16BE);

        CHARSET_ALIASES.put("utf16le", StandardCharsets.UTF_16LE);
        CHARSET_ALIASES.put("UTF16LE", StandardCharsets.UTF_16LE);
        CHARSET_ALIASES.put("utf-16le", StandardCharsets.UTF_16LE);
        CHARSET_ALIASES.put("UTF-16LE", StandardCharsets.UTF_16LE);

        // UCS-2 (Perl's Encode maps "ucs2" to UCS-2BE, equivalent to UTF-16BE for BMP)
        CHARSET_ALIASES.put("ucs2", StandardCharsets.UTF_16BE);
        CHARSET_ALIASES.put("UCS2", StandardCharsets.UTF_16BE);
        CHARSET_ALIASES.put("ucs-2", StandardCharsets.UTF_16BE);
        CHARSET_ALIASES.put("UCS-2", StandardCharsets.UTF_16BE);
        CHARSET_ALIASES.put("UCS-2BE", StandardCharsets.UTF_16BE);
        CHARSET_ALIASES.put("ucs-2be", StandardCharsets.UTF_16BE);
        CHARSET_ALIASES.put("UCS-2LE", StandardCharsets.UTF_16LE);
        CHARSET_ALIASES.put("ucs-2le", StandardCharsets.UTF_16LE);

        // Shift_JIS aliases
        try {
            Charset shiftJIS = Charset.forName("Shift_JIS");
            CHARSET_ALIASES.put("sjis", shiftJIS);
            CHARSET_ALIASES.put("SJIS", shiftJIS);
            CHARSET_ALIASES.put("shiftjis", shiftJIS);
            CHARSET_ALIASES.put("shift-jis", shiftJIS);
        } catch (Exception ignored) {
            // Shift_JIS may not be available on all JVMs
        }

        // EUC-JP alias
        try {
            Charset eucJP = Charset.forName("EUC-JP");
            CHARSET_ALIASES.put("euc-jp", eucJP);
            CHARSET_ALIASES.put("eucjp", eucJP);
        } catch (Exception ignored) {
        }

        // "locale" and "locale_fs" - map to JVM's default charset.
        // Encode::Locale registers these via Encode::Alias, but the Java decode/encode
        // methods bypass Perl-side alias resolution. The JVM default charset matches
        // what Encode::Locale detects from the OS locale (e.g. UTF-8 on modern systems).
        Charset defaultCharset = Charset.defaultCharset();
        CHARSET_ALIASES.put("locale", defaultCharset);
        CHARSET_ALIASES.put("locale_fs", defaultCharset);

        // UTF-32 aliases
        try {
            Charset utf32 = Charset.forName("UTF-32");
            CHARSET_ALIASES.put("utf32", utf32);
            CHARSET_ALIASES.put("UTF32", utf32);
            CHARSET_ALIASES.put("utf-32", utf32);
            CHARSET_ALIASES.put("UTF-32", utf32);
        } catch (Exception ignored) {
        }
        try {
            Charset utf32be = Charset.forName("UTF-32BE");
            CHARSET_ALIASES.put("utf32be", utf32be);
            CHARSET_ALIASES.put("UTF32BE", utf32be);
            CHARSET_ALIASES.put("utf-32be", utf32be);
            CHARSET_ALIASES.put("UTF-32BE", utf32be);
        } catch (Exception ignored) {
        }
        try {
            Charset utf32le = Charset.forName("UTF-32LE");
            CHARSET_ALIASES.put("utf32le", utf32le);
            CHARSET_ALIASES.put("UTF32LE", utf32le);
            CHARSET_ALIASES.put("utf-32le", utf32le);
            CHARSET_ALIASES.put("UTF-32LE", utf32le);
        } catch (Exception ignored) {
        }
    }

    public Encode() {
        super("Encode", false);  // Don't set %INC - let Encode.pm run via XSLoader
    }

    public static void initialize() {
        Encode encode = new Encode();
        // Set $VERSION so CPAN.pm can detect our bundled version
        GlobalVariable.getGlobalVariable("Encode::VERSION").set(new RuntimeScalar("3.21"));
        encode.initializeExporter();
        encode.defineExport("EXPORT", "encode", "decode", "encode_utf8", "decode_utf8",
                "is_utf8", "find_encoding", "from_to");
        encode.defineExport("EXPORT_OK", "FB_CROAK", "FB_QUIET", "FB_WARN", "FB_PERLQQ",
                "FB_HTMLCREF", "FB_XMLCREF", "PERLQQ", "HTMLCREF", "XMLCREF",
                "DIE_ON_ERR", "WARN_ON_ERR", "RETURN_ON_ERR", "LEAVE_SRC",
                "ONLY_PRAGMA_WARNINGS", "STOP_AT_PARTIAL",
                "FB_DEFAULT", "encode", "decode", "encode_utf8", "decode_utf8",
                "is_utf8", "find_encoding", "from_to", "_utf8_on", "_utf8_off",
                "define_encoding", "encodings", "perlio_ok", "resolve_alias");
        encode.defineExportTag("fallbacks",
                "FB_DEFAULT", "FB_CROAK", "FB_QUIET", "FB_WARN",
                "FB_PERLQQ", "FB_HTMLCREF", "FB_XMLCREF");
        encode.defineExportTag("fallback_all",
                "FB_DEFAULT", "FB_CROAK", "FB_QUIET", "FB_WARN",
                "FB_PERLQQ", "FB_HTMLCREF", "FB_XMLCREF",
                "LEAVE_SRC", "DIE_ON_ERR", "WARN_ON_ERR", "RETURN_ON_ERR",
                "STOP_AT_PARTIAL", "ONLY_PRAGMA_WARNINGS");
        try {
            encode.registerMethod("encode", null);
            encode.registerMethod("decode", null);
            encode.registerMethod("encode_utf8", null);
            encode.registerMethod("decode_utf8", null);
            encode.registerMethod("is_utf8", null);
            encode.registerMethod("find_encoding", null);
            encode.registerMethod("from_to", null);
            encode.registerMethod("_utf8_on", null);
            encode.registerMethod("_utf8_off", null);
            // Register constants
            encode.registerMethod("FB_DEFAULT", null);
            encode.registerMethod("FB_CROAK", null);
            encode.registerMethod("FB_QUIET", null);
            encode.registerMethod("FB_WARN", null);
            encode.registerMethod("FB_PERLQQ", null);
            encode.registerMethod("FB_HTMLCREF", null);
            encode.registerMethod("FB_XMLCREF", null);
            encode.registerMethod("PERLQQ", null);
            encode.registerMethod("HTMLCREF", null);
            encode.registerMethod("XMLCREF", null);
            encode.registerMethod("DIE_ON_ERR", null);
            encode.registerMethod("WARN_ON_ERR", null);
            encode.registerMethod("RETURN_ON_ERR", null);
            encode.registerMethod("LEAVE_SRC", null);
            encode.registerMethod("ONLY_PRAGMA_WARNINGS", null);
            encode.registerMethod("STOP_AT_PARTIAL", null);
            encode.registerMethod("define_encoding", null);
            encode.registerMethod("encodings", null);
            encode.registerMethod("resolve_alias", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Encode method: " + e.getMessage());
        }

        // Register Encode::Encoding instance methods for find_encoding() objects
        try {
            java.lang.invoke.MethodHandle encodeHandle = RuntimeCode.lookup.findStatic(
                    Encode.class, "encoding_encode", RuntimeCode.methodType);
            java.lang.invoke.MethodHandle decodeHandle = RuntimeCode.lookup.findStatic(
                    Encode.class, "encoding_decode", RuntimeCode.methodType);
            java.lang.invoke.MethodHandle nameHandle = RuntimeCode.lookup.findStatic(
                    Encode.class, "encoding_name", RuntimeCode.methodType);
            RuntimeCode encodeCode = new RuntimeCode(encodeHandle, null, null);
            encodeCode.isStatic = true;
            RuntimeCode decodeCode = new RuntimeCode(decodeHandle, null, null);
            decodeCode.isStatic = true;
            RuntimeCode nameCode = new RuntimeCode(nameHandle, null, null);
            nameCode.isStatic = true;
            GlobalVariable.getGlobalCodeRef("Encode::Encoding::encode").set(
                    new RuntimeScalar(encodeCode));
            GlobalVariable.getGlobalCodeRef("Encode::Encoding::decode").set(
                    new RuntimeScalar(decodeCode));
            GlobalVariable.getGlobalCodeRef("Encode::Encoding::name").set(
                    new RuntimeScalar(nameCode));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            System.err.println("Warning: Missing Encode::Encoding method: " + e.getMessage());
        }
    }

    // Encode constants (check bits)
    private static final int FB_DEFAULT = 0;
    private static final int FB_QUIET = 1;
    private static final int FB_WARN = 2;
    private static final int FB_CROAK = 4;
    private static final int FB_PERLQQ_VAL = 256;  // PERLQQ
    private static final int FB_HTMLCREF_VAL = 512;
    private static final int FB_XMLCREF_VAL = 1024;

    public static RuntimeList FB_DEFAULT(RuntimeArray args, int ctx) {
        return new RuntimeScalar(FB_DEFAULT).getList();
    }

    public static RuntimeList FB_CROAK(RuntimeArray args, int ctx) {
        return new RuntimeScalar(FB_CROAK).getList();
    }

    public static RuntimeList FB_QUIET(RuntimeArray args, int ctx) {
        return new RuntimeScalar(FB_QUIET).getList();
    }

    public static RuntimeList FB_WARN(RuntimeArray args, int ctx) {
        return new RuntimeScalar(FB_WARN).getList();
    }

    public static RuntimeList FB_PERLQQ(RuntimeArray args, int ctx) {
        return new RuntimeScalar(FB_PERLQQ_VAL | FB_WARN).getList();  // 264
    }

    public static RuntimeList FB_HTMLCREF(RuntimeArray args, int ctx) {
        return new RuntimeScalar(FB_HTMLCREF_VAL | FB_WARN).getList();  // 514
    }

    public static RuntimeList FB_XMLCREF(RuntimeArray args, int ctx) {
        return new RuntimeScalar(FB_XMLCREF_VAL | FB_WARN).getList();  // 1026
    }

    public static RuntimeList PERLQQ(RuntimeArray args, int ctx) {
        return new RuntimeScalar(FB_PERLQQ_VAL).getList();  // 256
    }

    public static RuntimeList HTMLCREF(RuntimeArray args, int ctx) {
        return new RuntimeScalar(FB_HTMLCREF_VAL).getList();  // 512
    }

    public static RuntimeList XMLCREF(RuntimeArray args, int ctx) {
        return new RuntimeScalar(FB_XMLCREF_VAL).getList();  // 1024
    }

    public static RuntimeList DIE_ON_ERR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList WARN_ON_ERR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(2).getList();
    }

    public static RuntimeList RETURN_ON_ERR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(4).getList();
    }

    public static RuntimeList LEAVE_SRC(RuntimeArray args, int ctx) {
        return new RuntimeScalar(8).getList();
    }

    public static RuntimeList ONLY_PRAGMA_WARNINGS(RuntimeArray args, int ctx) {
        return new RuntimeScalar(16).getList();
    }

    public static RuntimeList STOP_AT_PARTIAL(RuntimeArray args, int ctx) {
        return new RuntimeScalar(32).getList();
    }

    /**
     * define_encoding($obj, $name, ...) - registers an encoding object.
     * This is a no-op in PerlOnJava since encodings are handled natively in Java.
     */
    public static RuntimeList define_encoding(RuntimeArray args, int ctx) {
        // Register the encoding object in %Encode::Encoding hash
        if (args.size() >= 2) {
            RuntimeScalar obj = args.get(0);
            // Register under all provided names
            RuntimeHash encodingHash = GlobalVariable.getGlobalHash("Encode::Encoding");
            for (int i = 1; i < args.size(); i++) {
                String name = args.get(i).toString();
                encodingHash.put(name, obj);
            }
            return obj.getList();
        }
        return new RuntimeScalar().getList();
    }

    /**
     * encodings() - returns a list of available encoding names.
     */
    public static RuntimeList encodings(RuntimeArray args, int ctx) {
        RuntimeList list = new RuntimeList();
        list.add(new RuntimeScalar("ascii"));
        list.add(new RuntimeScalar("utf8"));
        list.add(new RuntimeScalar("utf-8"));
        list.add(new RuntimeScalar("iso-8859-1"));
        list.add(new RuntimeScalar("latin1"));
        return list;
    }

    /**
     * resolve_alias($name) - resolves an encoding alias to a canonical name.
     */
    public static RuntimeList resolve_alias(RuntimeArray args, int ctx) {
        if (args.size() > 0) {
            String name = args.get(0).toString();
            Charset cs = getCharset(name);
            if (cs != null) {
                return new RuntimeScalar(cs.name()).getList();
            }
        }
        return new RuntimeScalar().getList();  // undef if not found
    }

    /**
     * encode($encoding, $string [, $check])
     * Encodes a string from Perl's internal format to the specified encoding.
     */
    public static RuntimeList encode(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new IllegalStateException("Bad number of arguments for encode");
        }

        String encodingName = args.get(0).toString();
        String string = args.get(1).toString();
        // TODO: Handle $check parameter (args.get(2)) for error handling modes

        try {
            Charset charset = getCharset(encodingName);
            byte[] bytes = string.getBytes(charset);

            // Return the encoded bytes as a byte string, inside a list
            return new RuntimeScalar(bytes).getList();
        } catch (Exception e) {
            throw new RuntimeException("Cannot encode string to " + encodingName + ": " + e.getMessage());
        }
    }

    /**
     * decode($encoding, $octets [, $check])
     * Decodes a string from the specified encoding to Perl's internal format.
     */
    public static RuntimeList decode(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new IllegalStateException("Bad number of arguments for decode");
        }

        String encodingName = args.get(0).toString();
        String octets = args.get(1).toString();
        // TODO: Handle $check parameter (args.get(2)) for error handling modes

        try {
            Charset charset = getCharset(encodingName);
            // Convert the string to bytes assuming it contains raw octets
            byte[] bytes = octets.getBytes(StandardCharsets.ISO_8859_1);
            // Trim orphan trailing bytes for fixed-width encodings
            // (Perl's Encode silently drops incomplete trailing code units)
            bytes = trimOrphanBytes(bytes, charset);
            String decoded = new String(bytes, charset);

            return new RuntimeScalar(decoded).getList();
        } catch (Exception e) {
            throw new RuntimeException("Cannot decode string from " + encodingName + ": " + e.getMessage());
        }
    }

    /**
     * encode_utf8($string)
     * Equivalent to encode("utf8", $string)
     */
    public static RuntimeList encode_utf8(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalStateException("Bad number of arguments for encode_utf8");
        }

        String string = args.get(0).toString();
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);

        // Return the encoded bytes as a string, inside a list
        return new RuntimeScalar(bytes).getList();
    }

    /**
     * decode_utf8($octets [, $check])
     * Equivalent to decode("utf8", $octets [, $check])
     */
    public static RuntimeList decode_utf8(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalStateException("Bad number of arguments for decode_utf8");
        }

        String octets = args.get(0).toString();
        // TODO: Handle $check parameter (args.get(1)) for error handling modes

        try {
            // Convert the string to bytes assuming it contains raw octets
            byte[] bytes = octets.getBytes(StandardCharsets.ISO_8859_1);
            String decoded = new String(bytes, StandardCharsets.UTF_8);

            return new RuntimeScalar(decoded).getList();
        } catch (Exception e) {
            throw new RuntimeException("Cannot decode UTF-8 string: " + e.getMessage());
        }
    }

    /**
     * is_utf8($string [, $check])
     * Tests whether the UTF8 flag is turned on in the string.
     */
    public static RuntimeList is_utf8(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            throw new IllegalStateException("Bad number of arguments for is_utf8");
        }

        RuntimeScalar arg = args.get(0);
        if (arg.type == BYTE_STRING) {
            return RuntimeScalarCache.scalarFalse.getList();
        }
        String s = arg.toString();
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 255) {
                return RuntimeScalarCache.scalarTrue.getList();
            }
        }
        return RuntimeScalarCache.scalarFalse.getList();
    }

    /**
     * find_encoding($encoding)
     * Returns a blessed Encode::Encoding object for the given encoding name.
     * The object supports ->encode($string) and ->decode($octets) methods.
     * Note: This is the Java fast path for known charsets. The Perl wrapper
     * in Encode.pm adds Encode::Alias fallback for custom aliases like "locale".
     */
    public static RuntimeList find_encoding(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            throw new IllegalStateException("Bad number of arguments for find_encoding");
        }

        String encodingName = args.get(0).toString();

        try {
            Charset charset = getCharset(encodingName);
            // Create a blessed hash with the charset name
            RuntimeHash encObj = new RuntimeHash();
            encObj.put("Name", new RuntimeScalar(charset.name()));
            RuntimeScalar ref = encObj.createReference();
            ReferenceOperators.bless(ref, new RuntimeScalar("Encode::Encoding"));
            return ref.getList();
        } catch (Exception e) {
            // Return undef if encoding not found
            return scalarUndef.getList();
        }
    }

    /**
     * Encode::Encoding->encode($string [, $check])
     * Encodes a string to octets using this encoding.
     */
    public static RuntimeList encoding_encode(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new IllegalStateException("Bad number of arguments for Encode::Encoding::encode");
        }

        RuntimeScalar self = args.get(0);
        String string = args.get(1).toString();

        // Extract charset name from the blessed hash
        RuntimeHash hash = (RuntimeHash) self.value;
        String charsetName = hash.get("Name").toString();

        try {
            Charset charset = getCharset(charsetName);
            byte[] bytes = string.getBytes(charset);
            // Return as byte string (ISO-8859-1 preserves raw bytes)
            return new RuntimeScalar(new String(bytes, StandardCharsets.ISO_8859_1)).getList();
        } catch (Exception e) {
            throw new RuntimeException("Cannot encode string with " + charsetName + ": " + e.getMessage());
        }
    }

    /**
     * Encode::Encoding->decode($octets [, $check])
     * Decodes octets to a string using this encoding.
     */
    public static RuntimeList encoding_decode(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new IllegalStateException("Bad number of arguments for Encode::Encoding::decode");
        }

        RuntimeScalar self = args.get(0);
        String octets = args.get(1).toString();

        // Extract charset name from the blessed hash
        RuntimeHash hash = (RuntimeHash) self.value;
        String charsetName = hash.get("Name").toString();

        try {
            Charset charset = getCharset(charsetName);
            byte[] bytes = octets.getBytes(StandardCharsets.ISO_8859_1);
            // Trim orphan trailing bytes for fixed-width encodings
            bytes = trimOrphanBytes(bytes, charset);
            String decoded = new String(bytes, charset);
            return new RuntimeScalar(decoded).getList();
        } catch (Exception e) {
            throw new RuntimeException("Cannot decode octets with " + charsetName + ": " + e.getMessage());
        }
    }

    /**
     * Encode::Encoding->name()
     * Returns the canonical name of this encoding.
     */
    public static RuntimeList encoding_name(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            throw new IllegalStateException("Bad number of arguments for Encode::Encoding::name");
        }

        RuntimeScalar self = args.get(0);
        RuntimeHash hash = (RuntimeHash) self.value;
        return hash.get("Name").getList();
    }

    /**
     * from_to($octets, $from_enc, $to_enc [, $check])
     * Converts in-place the octet sequence from one encoding to another.
     */
    public static RuntimeList from_to(RuntimeArray args, int ctx) {
        if (args.size() < 3) {
            throw new IllegalStateException("Bad number of arguments for from_to");
        }

        RuntimeScalar octetsRef = args.get(0);
        String fromEnc = args.get(1).toString();
        String toEnc = args.get(2).toString();
        // TODO: Handle $check parameter (args.get(3)) for error handling modes

        try {
            Charset fromCharset = getCharset(fromEnc);
            Charset toCharset = getCharset(toEnc);

            // Get the octets
            String octets = octetsRef.toString();
            byte[] bytes = octets.getBytes(StandardCharsets.ISO_8859_1);

            // Decode from source encoding
            // Trim orphan trailing bytes for fixed-width encodings
            bytes = trimOrphanBytes(bytes, fromCharset);
            String decoded = new String(bytes, fromCharset);

            // Encode to target encoding
            byte[] encoded = decoded.getBytes(toCharset);

            // Update the original scalar in-place
            octetsRef.set(new String(encoded, StandardCharsets.ISO_8859_1));

            // Return the number of characters converted
            return new RuntimeScalar(decoded.length()).getList();
        } catch (Exception e) {
            throw new RuntimeException("Cannot convert from " + fromEnc + " to " + toEnc + ": " + e.getMessage());
        }
    }

    public static RuntimeList _utf8_on(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            throw new IllegalStateException("Bad number of arguments for _utf8_on");
        }
        return scalarUndef.getList();
    }

    public static RuntimeList _utf8_off(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            throw new IllegalStateException("Bad number of arguments for _utf8_off");
        }
        RuntimeScalar arg = args.get(0);
        if (arg.type != BYTE_STRING) {
            String s = arg.toString();
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            arg.set(new String(bytes, StandardCharsets.ISO_8859_1));
            arg.type = BYTE_STRING;
        }
        return scalarUndef.getList();
    }

    /**
     * Trims orphan trailing bytes for fixed-width encodings.
     * Perl's Encode silently drops incomplete trailing code units
     * (e.g., an odd byte at the end of UTF-16 input).
     * Java's String(byte[], Charset) replaces them with U+FFFD instead.
     */
    private static byte[] trimOrphanBytes(byte[] bytes, Charset charset) {
        String name = charset.name().toLowerCase();
        int codeUnitSize = 0;
        if (name.contains("utf-16") || name.contains("utf16") || name.contains("ucs-2") || name.contains("ucs2")) {
            codeUnitSize = 2;
        } else if (name.contains("utf-32") || name.contains("utf32")) {
            codeUnitSize = 4;
        }
        if (codeUnitSize > 1) {
            int remainder = bytes.length % codeUnitSize;
            if (remainder != 0) {
                bytes = Arrays.copyOf(bytes, bytes.length - remainder);
            }
        }
        return bytes;
    }

    /**
     * Helper method to get a Charset from an encoding name.
     * Handles common aliases and Perl-style encoding names.
     */
    private static Charset getCharset(String encodingName) {
        // Check aliases first
        Charset charset = CHARSET_ALIASES.get(encodingName);
        if (charset != null) {
            return charset;
        }

        // Try to get charset by name
        try {
            return Charset.forName(encodingName);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            throw new RuntimeException("Unknown encoding: " + encodingName);
        }
    }
}
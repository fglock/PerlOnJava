package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;

/**
 * Data::UUID module implementation for PerlOnJava.
 * <p>
 * Provides RFC 4122 UUID generation and manipulation. Mirrors the interface of
 * the CPAN Data::UUID XS module, using java.util.UUID for v4 (create) and v3
 * (create_from_name) generation.
 * <p>
 * The binary UUID representation is the RFC 4122 16-byte big-endian form. This
 * is self-consistent with to_string / to_hexstring / to_b64string and their
 * from_* inverses; it differs from the host-byte-order binary produced by the
 * CPAN XS module on little-endian platforms, but the CPAN module's binary
 * format is not portable either, and round-trips within this implementation
 * work correctly.
 */
public class DataUUID extends PerlModuleBase {

    private static final String CLASS_NAME = "Data::UUID";

    // Output format codes, matching UUID.xs' ALIAS ix values.
    private static final int F_BIN = 1;
    private static final int F_STR = 2;
    private static final int F_HEX = 3;
    private static final int F_B64 = 4;

    // Namespace UUIDs from RFC 4122 Appendix C, as 16-byte big-endian strings
    // stored as ISO-8859-1 Perl byte strings.
    private static final String NS_DNS  = bytesToLatin1(uuidStringToBytes("6ba7b810-9dad-11d1-80b4-00c04fd430c8"));
    private static final String NS_URL  = bytesToLatin1(uuidStringToBytes("6ba7b811-9dad-11d1-80b4-00c04fd430c8"));
    private static final String NS_OID  = bytesToLatin1(uuidStringToBytes("6ba7b812-9dad-11d1-80b4-00c04fd430c8"));
    private static final String NS_X500 = bytesToLatin1(uuidStringToBytes("6ba7b814-9dad-11d1-80b4-00c04fd430c8"));

    public DataUUID() {
        super(CLASS_NAME, false);
    }

    public static void initialize() {
        DataUUID mod = new DataUUID();
        try {
            mod.registerMethod("new", "newInstance", null);
            mod.registerMethod("create", null);
            mod.registerMethod("create_bin", null);
            mod.registerMethod("create_str", null);
            mod.registerMethod("create_hex", null);
            mod.registerMethod("create_b64", null);
            mod.registerMethod("create_from_name", null);
            mod.registerMethod("create_from_name_bin", null);
            mod.registerMethod("create_from_name_str", null);
            mod.registerMethod("create_from_name_hex", null);
            mod.registerMethod("create_from_name_b64", null);
            mod.registerMethod("to_string", null);
            mod.registerMethod("to_hexstring", null);
            mod.registerMethod("to_b64string", null);
            mod.registerMethod("from_string", null);
            mod.registerMethod("from_hexstring", null);
            mod.registerMethod("from_b64string", null);
            mod.registerMethod("compare", null);
            mod.registerMethod("NameSpace_DNS", null);
            mod.registerMethod("NameSpace_URL", null);
            mod.registerMethod("NameSpace_OID", null);
            mod.registerMethod("NameSpace_X500", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Data::UUID method: " + e.getMessage());
        }
    }

    // --- Constructor ---------------------------------------------------------

    /**
     * Perl: Data::UUID->new
     * Registered under the Perl name 'new'; Java method is newInstance because
     * 'new' is a Java reserved word.
     */
    public static RuntimeList newInstance(RuntimeArray args, int ctx) {
        RuntimeHash state = new RuntimeHash();
        RuntimeScalar ref = state.createReference();
        ReferenceOperators.bless(ref, new RuntimeScalar(CLASS_NAME));
        return ref.getList();
    }

    // --- create() family (v1-style, but using secure random for uniqueness) -

    public static RuntimeList create(RuntimeArray args, int ctx) {
        checkSelf(args);
        return makeRet(createV4Bytes(), F_BIN);
    }

    public static RuntimeList create_bin(RuntimeArray args, int ctx) {
        checkSelf(args);
        return makeRet(createV4Bytes(), F_BIN);
    }

    public static RuntimeList create_str(RuntimeArray args, int ctx) {
        checkSelf(args);
        return makeRet(createV4Bytes(), F_STR);
    }

    public static RuntimeList create_hex(RuntimeArray args, int ctx) {
        checkSelf(args);
        return makeRet(createV4Bytes(), F_HEX);
    }

    public static RuntimeList create_b64(RuntimeArray args, int ctx) {
        checkSelf(args);
        return makeRet(createV4Bytes(), F_B64);
    }

    // --- create_from_name() family (v3, MD5-based) --------------------------

    public static RuntimeList create_from_name(RuntimeArray args, int ctx) {
        return makeRet(createFromNameBytes(args), F_BIN);
    }

    public static RuntimeList create_from_name_bin(RuntimeArray args, int ctx) {
        return makeRet(createFromNameBytes(args), F_BIN);
    }

    public static RuntimeList create_from_name_str(RuntimeArray args, int ctx) {
        return makeRet(createFromNameBytes(args), F_STR);
    }

    public static RuntimeList create_from_name_hex(RuntimeArray args, int ctx) {
        return makeRet(createFromNameBytes(args), F_HEX);
    }

    public static RuntimeList create_from_name_b64(RuntimeArray args, int ctx) {
        return makeRet(createFromNameBytes(args), F_B64);
    }

    // --- Format conversion --------------------------------------------------

    public static RuntimeList to_string(RuntimeArray args, int ctx) {
        checkSelf(args);
        byte[] bytes = toBinBytes(args.get(1));
        return makeRet(bytes, F_STR);
    }

    public static RuntimeList to_hexstring(RuntimeArray args, int ctx) {
        checkSelf(args);
        byte[] bytes = toBinBytes(args.get(1));
        return makeRet(bytes, F_HEX);
    }

    public static RuntimeList to_b64string(RuntimeArray args, int ctx) {
        checkSelf(args);
        byte[] bytes = toBinBytes(args.get(1));
        return makeRet(bytes, F_B64);
    }

    public static RuntimeList from_string(RuntimeArray args, int ctx) {
        checkSelf(args);
        String s = args.get(1).toString();
        byte[] bytes = parseStringOrHex(s);
        return makeRet(bytes, F_BIN);
    }

    public static RuntimeList from_hexstring(RuntimeArray args, int ctx) {
        checkSelf(args);
        String s = args.get(1).toString();
        byte[] bytes = parseStringOrHex(s);
        return makeRet(bytes, F_BIN);
    }

    public static RuntimeList from_b64string(RuntimeArray args, int ctx) {
        checkSelf(args);
        String s = args.get(1).toString();
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(s.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("from_b64string(" + s + ") failed: " + e.getMessage());
        }
        if (bytes.length < 16) {
            byte[] padded = new byte[16];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            bytes = padded;
        } else if (bytes.length > 16) {
            byte[] trunc = new byte[16];
            System.arraycopy(bytes, 0, trunc, 0, 16);
            bytes = trunc;
        }
        return makeRet(bytes, F_BIN);
    }

    public static RuntimeList compare(RuntimeArray args, int ctx) {
        checkSelf(args);
        byte[] a = toBinBytes(args.get(1));
        byte[] b = toBinBytes(args.get(2));
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int ai = a[i] & 0xFF;
            int bi = b[i] & 0xFF;
            if (ai < bi) return new RuntimeScalar(-1).getList();
            if (ai > bi) return new RuntimeScalar(1).getList();
        }
        if (a.length < b.length) return new RuntimeScalar(-1).getList();
        if (a.length > b.length) return new RuntimeScalar(1).getList();
        return new RuntimeScalar(0).getList();
    }

    // --- Exported NameSpace constants ---------------------------------------

    public static RuntimeList NameSpace_DNS(RuntimeArray args, int ctx) {
        return new RuntimeScalar(NS_DNS).getList();
    }

    public static RuntimeList NameSpace_URL(RuntimeArray args, int ctx) {
        return new RuntimeScalar(NS_URL).getList();
    }

    public static RuntimeList NameSpace_OID(RuntimeArray args, int ctx) {
        return new RuntimeScalar(NS_OID).getList();
    }

    public static RuntimeList NameSpace_X500(RuntimeArray args, int ctx) {
        return new RuntimeScalar(NS_X500).getList();
    }

    // --- Internal helpers ---------------------------------------------------

    /**
     * Verify that args[0] is a blessed Data::UUID reference. Croak with the
     * exact error message the CPAN XS module uses when it is not.
     */
    private static void checkSelf(RuntimeArray args) {
        if (args.isEmpty()) {
            throw new RuntimeException("self is not of type Data::UUID");
        }
        RuntimeScalar self = args.get(0);
        int blessId = RuntimeScalarType.blessedId(self);
        if (blessId == 0) {
            throw new RuntimeException("self is not of type Data::UUID");
        }
        String className = NameNormalizer.getBlessStr(blessId);
        if (!CLASS_NAME.equals(className)) {
            // Allow subclasses via @ISA? For now require exact match.
            throw new RuntimeException("self is not of type Data::UUID");
        }
    }

    /** Generate a v4 random UUID as 16 big-endian bytes. */
    private static byte[] createV4Bytes() {
        UUID u = UUID.randomUUID();
        return uuidToBytes(u);
    }

    /**
     * Generate a v3 name-based UUID as 16 big-endian bytes, following
     * RFC 4122 §4.3: MD5(namespace_bytes || name_bytes) then set version=3,
     * variant=RFC 4122.
     */
    private static byte[] createFromNameBytes(RuntimeArray args) {
        checkSelf(args);
        if (args.size() < 3) {
            throw new RuntimeException("Usage: Data::UUID::create_from_name(self, nsid, name)");
        }
        byte[] ns = toBinBytes(args.get(1));
        byte[] nameBytes = args.get(2).toString().getBytes(StandardCharsets.ISO_8859_1);

        byte[] buf = new byte[ns.length + nameBytes.length];
        System.arraycopy(ns, 0, buf, 0, ns.length);
        System.arraycopy(nameBytes, 0, buf, ns.length, nameBytes.length);

        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available: " + e.getMessage(), e);
        }
        byte[] hash = md.digest(buf);

        byte[] out = new byte[16];
        System.arraycopy(hash, 0, out, 0, 16);
        // Set version = 3 (name-based MD5)
        out[6] = (byte) ((out[6] & 0x0F) | 0x30);
        // Set variant = 10xx (RFC 4122)
        out[8] = (byte) ((out[8] & 0x3F) | 0x80);
        return out;
    }

    /**
     * Format 16 UUID bytes in the requested representation. Matches CPAN
     * Data::UUID's output format, including uppercase hex (as produced by
     * the %X specifier in the XS code).
     */
    private static RuntimeList makeRet(byte[] u, int type) {
        switch (type) {
            case F_BIN:
                return new RuntimeScalar(bytesToLatin1(u)).getList();
            case F_STR: {
                String hex = bytesToUpperHex(u);
                StringBuilder sb = new StringBuilder(36);
                sb.append(hex, 0, 8).append('-')
                  .append(hex, 8, 12).append('-')
                  .append(hex, 12, 16).append('-')
                  .append(hex, 16, 20).append('-')
                  .append(hex, 20, 32);
                return new RuntimeScalar(sb.toString()).getList();
            }
            case F_HEX:
                return new RuntimeScalar("0x" + bytesToUpperHex(u)).getList();
            case F_B64: {
                // CPAN Data::UUID produces base64 WITH padding and no trailing
                // newline; the basic.t test specifically checks that there is
                // no "\n" in the output.
                String b64 = Base64.getEncoder().encodeToString(u);
                return new RuntimeScalar(b64).getList();
            }
            default:
                throw new RuntimeException("invalid type: " + type);
        }
    }

    /**
     * Parse a UUID string in either canonical "XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX"
     * form or hex "0xXXXXXXXX..." form. Accepts hyphens anywhere and is case
     * insensitive.
     */
    private static byte[] parseStringOrHex(String s) {
        String clean = s;
        if (clean.startsWith("0x") || clean.startsWith("0X")) {
            clean = clean.substring(2);
        }
        clean = clean.replace("-", "");
        if (clean.length() != 32) {
            throw new RuntimeException("from_string(" + s + ") failed...");
        }
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            int hi = Character.digit(clean.charAt(i * 2), 16);
            int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new RuntimeException("from_string(" + s + ") failed...");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /**
     * Convert a Perl scalar holding a 16-byte binary UUID (ISO-8859-1 bytes)
     * into a Java byte array.
     */
    private static byte[] toBinBytes(RuntimeScalar s) {
        byte[] b = s.toString().getBytes(StandardCharsets.ISO_8859_1);
        if (b.length == 16) return b;
        if (b.length < 16) {
            byte[] padded = new byte[16];
            System.arraycopy(b, 0, padded, 0, b.length);
            return padded;
        }
        byte[] trunc = new byte[16];
        System.arraycopy(b, 0, trunc, 0, 16);
        return trunc;
    }

    private static byte[] uuidToBytes(UUID u) {
        long msb = u.getMostSignificantBits();
        long lsb = u.getLeastSignificantBits();
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) out[i] = (byte) ((msb >>> (56 - i * 8)) & 0xFF);
        for (int i = 0; i < 8; i++) out[8 + i] = (byte) ((lsb >>> (56 - i * 8)) & 0xFF);
        return out;
    }

    private static byte[] uuidStringToBytes(String s) {
        UUID u = UUID.fromString(s);
        return uuidToBytes(u);
    }

    private static String bytesToLatin1(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static String bytesToUpperHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString().toUpperCase();
    }
}

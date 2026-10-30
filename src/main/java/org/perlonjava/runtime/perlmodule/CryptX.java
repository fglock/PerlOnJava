package org.perlonjava.runtime.perlmodule;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.perlonjava.frontend.parser.StringParser;
import org.perlonjava.runtime.runtimetypes.*;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Security;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/**
 * First Java-backed CryptX compatibility slice. CryptX's Perl wrappers remain
 * upstream-owned; this class registers the common functional APIs used by
 * JWT-style consumers without loading the distribution's XS library.
 */
public final class CryptX extends PerlModuleBase {
    public static final String XS_VERSION = "0.091";
    private static final String MODULE = "CryptX";
    private static final Map<String, String> DIGESTS = Map.ofEntries(
            Map.entry("MD2", "MD2"), Map.entry("MD4", "MD4"),
            Map.entry("MD5", "MD5"), Map.entry("SHA1", "SHA-1"),
            Map.entry("SHA224", "SHA-224"), Map.entry("SHA256", "SHA-256"),
            Map.entry("SHA384", "SHA-384"), Map.entry("SHA512", "SHA-512"),
            Map.entry("SHA512224", "SHA-512/224"), Map.entry("SHA512256", "SHA-512/256"),
            Map.entry("SHA3224", "SHA3-224"), Map.entry("SHA3256", "SHA3-256"),
            Map.entry("SHA3384", "SHA3-384"), Map.entry("SHA3512", "SHA3-512"),
            Map.entry("KECCAK224", "Keccak-224"), Map.entry("KECCAK256", "Keccak-256"),
            Map.entry("KECCAK384", "Keccak-384"), Map.entry("KECCAK512", "Keccak-512"),
            Map.entry("RIPEMD160", "RIPEMD160"), Map.entry("RIPEMD256", "RIPEMD256"),
            Map.entry("RIPEMD320", "RIPEMD320"), Map.entry("BLAKE2B160", "BLAKE2B-160"),
            Map.entry("BLAKE2B256", "BLAKE2B-256"), Map.entry("BLAKE2B384", "BLAKE2B-384"),
            Map.entry("BLAKE2B512", "BLAKE2B-512"), Map.entry("BLAKE2S128", "BLAKE2S-128"),
            Map.entry("BLAKE2S160", "BLAKE2S-160"), Map.entry("BLAKE2S224", "BLAKE2S-224"),
            Map.entry("BLAKE2S256", "BLAKE2S-256")
    );

    public CryptX() {
        this(MODULE);
    }

    private CryptX(String perlPackage) {
        super(perlPackage, false);
    }

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public static void initialize() {
        CryptX core = new CryptX(MODULE);
        CryptX misc = new CryptX("Crypt::Misc");
        CryptX digest = new CryptX("Crypt::Digest");
        CryptX hmac = new CryptX("Crypt::Mac::HMAC");
        try {
            core.registerMethod("_ltc_build_settings", "buildSettings", null);
            core.registerMethod("_ltc_mp_name", "mpName", null);
            core.registerMethod("_ltc_mp_bits_per_digit", "mpBitsPerDigit", null);

            misc.registerMethod("encode_b64", "encodeBase64", null);
            misc.registerMethod("encode_b64u", "encodeBase64Url", null);
            misc.registerMethod("decode_b64", "decodeBase64", null);
            misc.registerMethod("decode_b64u", "decodeBase64Url", null);
            misc.registerMethod("slow_eq", "slowEquals", null);
            misc.registerMethod("increment_octets_be", "incrementBigEndian", null);
            misc.registerMethod("increment_octets_le", "incrementLittleEndian", null);
            misc.registerMethod("_bin_to_radix", "binaryToRadix", null);
            misc.registerMethod("_radix_to_bin", "radixToBinary", null);

            digest.registerMethod("digest_data", "digestData", null);
            digest.registerMethod("digest_data_hex", "digestDataHex", null);
            digest.registerMethod("digest_data_b64", "digestDataBase64", null);
            digest.registerMethod("digest_data_b64u", "digestDataBase64Url", null);

            hmac.registerMethod("hmac", "hmac", null);
            hmac.registerMethod("hmac_hex", "hmacHex", null);
            hmac.registerMethod("hmac_b64", "hmacBase64", null);
            hmac.registerMethod("hmac_b64u", "hmacBase64Url", null);

        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unable to initialize " + MODULE, e);
        }
        GlobalVariable.getGlobalVariable("CryptX::VERSION").set(new RuntimeScalar(XS_VERSION));
    }

    public static RuntimeList buildSettings(RuntimeArray args, int ctx) {
        return scalar("PerlOnJava JCA/Bouncy Castle providers");
    }

    public static RuntimeList mpName(RuntimeArray args, int ctx) {
        return scalar("Java BigInteger");
    }

    public static RuntimeList mpBitsPerDigit(RuntimeArray args, int ctx) {
        return scalar(32);
    }

    public static RuntimeList encodeBase64(RuntimeArray args, int ctx) {
        byte[] data = bytes(arg(args, 0), "Crypt::Misc::encode_b64");
        return scalar(Base64.getEncoder().encodeToString(data));
    }

    public static RuntimeList encodeBase64Url(RuntimeArray args, int ctx) {
        byte[] data = bytes(arg(args, 0), "Crypt::Misc::encode_b64u");
        return scalar(Base64.getUrlEncoder().withoutPadding().encodeToString(data));
    }

    public static RuntimeList decodeBase64(RuntimeArray args, int ctx) {
        return decodeBase64(args, false);
    }

    public static RuntimeList decodeBase64Url(RuntimeArray args, int ctx) {
        return decodeBase64(args, true);
    }

    private static RuntimeList decodeBase64(RuntimeArray args, boolean url) {
        String input = arg(args, 0).toString();
        String compact = input.replaceAll("\\s", "");
        try {
            if (url) {
                if (!compact.matches("[A-Za-z0-9_\\-=]*") || compact.indexOf('+') >= 0 || compact.indexOf('/') >= 0) {
                    return scalarUndef.getList();
                }
                return new RuntimeScalar(Base64.getUrlDecoder().decode(compact)).getList();
            }
            if (!compact.matches("[A-Za-z0-9+/=]*") || compact.indexOf('-') >= 0 || compact.indexOf('_') >= 0) {
                return scalarUndef.getList();
            }
            return new RuntimeScalar(Base64.getDecoder().decode(compact)).getList();
        } catch (IllegalArgumentException e) {
            // CryptX's sane decoders return undef for malformed input.
            return scalarUndef.getList();
        }
    }

    public static RuntimeList slowEquals(RuntimeArray args, int ctx) {
        byte[] left = bytes(arg(args, 0), "Crypt::Misc::slow_eq");
        byte[] right = bytes(arg(args, 1), "Crypt::Misc::slow_eq");
        return scalar(MessageDigest.isEqual(left, right) ? 1 : 0);
    }

    public static RuntimeList incrementBigEndian(RuntimeArray args, int ctx) {
        return increment(args, true);
    }

    public static RuntimeList incrementLittleEndian(RuntimeArray args, int ctx) {
        return increment(args, false);
    }

    private static RuntimeList increment(RuntimeArray args, boolean bigEndian) {
        byte[] data = bytes(arg(args, 0), "Crypt::Misc::increment_octets");
        byte[] result = data.clone();
        if (result.length == 0) return new RuntimeScalar(result).getList();
        if (bigEndian) {
            for (int i = result.length - 1; i >= 0; i--) {
                result[i]++;
                if (result[i] != 0) return new RuntimeScalar(result).getList();
            }
        } else {
            for (int i = 0; i < result.length; i++) {
                result[i]++;
                if (result[i] != 0) return new RuntimeScalar(result).getList();
            }
        }
        throw new PerlCompilerException("FATAL: increment_octets overflow");
    }

    private static final String RADIX_DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz+/";

    public static RuntimeList binaryToRadix(RuntimeArray args, int ctx) {
        byte[] data = bytes(arg(args, 0), "Crypt::Misc::_bin_to_radix");
        int radix = arg(args, 1).getInt();
        if (radix < 2 || radix > 64) return scalarUndef.getList();
        if (data.length == 0) return scalar("");
        BigInteger value = new BigInteger(1, data);
        if (value.signum() == 0) return scalar("");
        BigInteger base = BigInteger.valueOf(radix);
        StringBuilder encoded = new StringBuilder();
        while (value.signum() != 0) {
            BigInteger[] qr = value.divideAndRemainder(base);
            encoded.append(RADIX_DIGITS.charAt(qr[1].intValue()));
            value = qr[0];
        }
        return scalar(encoded.reverse().toString());
    }

    public static RuntimeList radixToBinary(RuntimeArray args, int ctx) {
        String input = arg(args, 0).toString();
        int radix = arg(args, 1).getInt();
        if (radix < 2 || radix > 64 || input.indexOf('\0') >= 0) return scalarUndef.getList();
        if (input.isEmpty()) return new RuntimeScalar(new byte[0]).getList();
        BigInteger value = BigInteger.ZERO;
        for (int i = 0; i < input.length(); i++) {
            int digit = RADIX_DIGITS.indexOf(input.charAt(i));
            if (digit < 0 || digit >= radix) return scalarUndef.getList();
            value = value.multiply(BigInteger.valueOf(radix)).add(BigInteger.valueOf(digit));
        }
        if (value.signum() == 0) return new RuntimeScalar(new byte[0]).getList();
        byte[] signed = value.toByteArray();
        int offset = signed.length > 1 && signed[0] == 0 ? 1 : 0;
        return new RuntimeScalar(java.util.Arrays.copyOfRange(signed, offset, signed.length)).getList();
    }

    public static RuntimeList digestData(RuntimeArray args, int ctx) {
        return digest(args, 0);
    }

    public static RuntimeList digestDataHex(RuntimeArray args, int ctx) {
        return digest(args, 1);
    }

    public static RuntimeList digestDataBase64(RuntimeArray args, int ctx) {
        return digest(args, 2);
    }

    public static RuntimeList digestDataBase64Url(RuntimeArray args, int ctx) {
        return digest(args, 3);
    }

    private static RuntimeList digest(RuntimeArray args, int output) {
        String algorithm = arg(args, 0).toString();
        try {
            MessageDigest digest = messageDigest(algorithm);
            for (int i = 1; i < args.size(); i++) digest.update(bytes(args.get(i), "Crypt::Digest::digest_data"));
            return encodeDigest(digest.digest(), output);
        } catch (PerlCompilerException e) {
            throw e;
        } catch (Exception e) {
            throw cryptoError("Crypt::Digest", algorithm, e);
        }
    }

    public static RuntimeList hmac(RuntimeArray args, int ctx) {
        return calculateHmac(args, 0);
    }

    public static RuntimeList hmacHex(RuntimeArray args, int ctx) {
        return calculateHmac(args, 1);
    }

    public static RuntimeList hmacBase64(RuntimeArray args, int ctx) {
        return calculateHmac(args, 2);
    }

    public static RuntimeList hmacBase64Url(RuntimeArray args, int ctx) {
        return calculateHmac(args, 3);
    }

    private static RuntimeList calculateHmac(RuntimeArray args, int output) {
        String algorithm = arg(args, 0).toString();
        byte[] key = bytes(arg(args, 1), "Crypt::Mac::HMAC::hmac key");
        try {
            Mac mac = mac(algorithm);
            mac.init(new RawKey(key, mac.getAlgorithm()));
            for (int i = 2; i < args.size(); i++) mac.update(bytes(args.get(i), "Crypt::Mac::HMAC::hmac data"));
            return encodeDigest(mac.doFinal(), output);
        } catch (PerlCompilerException e) {
            throw e;
        } catch (Exception e) {
            throw cryptoError("Crypt::Mac::HMAC", algorithm, e);
        }
    }

    private static MessageDigest messageDigest(String algorithm) throws Exception {
        String name = digestName(algorithm);
        try {
            return MessageDigest.getInstance(name);
        } catch (Exception first) {
            return MessageDigest.getInstance(name, "BC");
        }
    }

    private static Mac mac(String algorithm) throws Exception {
        String canonical = normalizeAlgorithm(algorithm);
        String hash = DIGESTS.get(canonical);
        if (hash == null) throw new PerlCompilerException("FATAL: Crypt::Mac::HMAC unsupported digest '" + algorithm + "'");
        String javaName = canonical.startsWith("SHA3")
                ? "HMAC" + hash.replace("SHA3-", "SHA3-")
                : "Hmac" + hash.replace("-", "");
        try {
            return Mac.getInstance(javaName);
        } catch (Exception first) {
            return Mac.getInstance(javaName, "BC");
        }
    }

    private static String digestName(String algorithm) {
        String name = DIGESTS.get(normalizeAlgorithm(algorithm));
        if (name == null) throw new PerlCompilerException("FATAL: Crypt::Digest unsupported digest '" + algorithm + "'");
        return name;
    }

    private static String normalizeAlgorithm(String algorithm) {
        return algorithm.replace("_", "").replace("-", "").toUpperCase(Locale.ROOT);
    }

    private static RuntimeList encodeDigest(byte[] data, int output) {
        return switch (output) {
            case 1 -> scalar(HexFormat.of().formatHex(data));
            case 2 -> scalar(Base64.getEncoder().encodeToString(data));
            case 3 -> scalar(Base64.getUrlEncoder().withoutPadding().encodeToString(data));
            default -> new RuntimeScalar(data).getList();
        };
    }

    private static PerlCompilerException cryptoError(String module, String algorithm, Exception cause) {
        return new PerlCompilerException("FATAL: " + module + " failed for '" + algorithm + "': " + cause.getMessage());
    }

    private static RuntimeScalar arg(RuntimeArray args, int index) {
        if (args.size() <= index) throw new PerlCompilerException("FATAL: CryptX missing argument");
        return args.get(index);
    }

    private static byte[] bytes(RuntimeScalar value, String operation) {
        String string = value.toString();
        StringParser.assertNoWideCharacters(string, operation);
        return string.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static RuntimeList scalar(Object value) {
        return new RuntimeScalar(value).getList();
    }

    private static final class RawKey implements SecretKey {
        private final byte[] encoded;
        private final String algorithm;

        private RawKey(byte[] encoded, String algorithm) {
            this.encoded = encoded.clone();
            this.algorithm = algorithm;
        }

        @Override public String getAlgorithm() { return algorithm; }
        @Override public String getFormat() { return "RAW"; }
        @Override public byte[] getEncoded() { return encoded.clone(); }
    }
}

package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.perlonjava.runtime.operators.WarnDie.die;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.JAVAOBJECT;

/**
 * Minimal Crypt::OpenSSL::Bignum implementation for PerlOnJava, backed by
 * {@link BigInteger}. Covers the API surface needed by Crypt::OpenSSL::RSA's
 * {@code new_key_from_parameters} / {@code get_key_parameters} round-trips
 * plus the common constructors and conversions used by callers.
 * <p>
 * The "pointer" that the Perl-side wrapper round-trips via
 * {@code pointer_copy} / {@code bless_pointer} is a {@link RuntimeScalar}
 * carrying the BigInteger as a JAVAOBJECT; there is no C pointer semantics
 * to emulate.
 */
public class CryptOpenSSLBignum extends PerlModuleBase {

    private static final String CLASS_NAME = "Crypt::OpenSSL::Bignum";
    private static final String VALUE_KEY = "_bn_value";
    private static final SecureRandom RNG = new SecureRandom();

    public CryptOpenSSLBignum() {
        super(CLASS_NAME, false);
    }

    public static void initialize() {
        CryptOpenSSLBignum mod = new CryptOpenSSLBignum();
        GlobalVariable.getGlobalVariable("Crypt::OpenSSL::Bignum::VERSION").set(new RuntimeScalar("0.09"));
        try {
            // Constructors (class methods)
            mod.registerMethod("new_from_bin", null);
            mod.registerMethod("new_from_decimal", null);
            mod.registerMethod("new_from_hex", null);
            mod.registerMethod("new_from_word", null);
            mod.registerMethod("zero", null);
            mod.registerMethod("one", null);
            mod.registerMethod("rand", null);
            mod.registerMethod("pseudo_rand", null);
            mod.registerMethod("bless_pointer", null);
            // Instance methods
            mod.registerMethod("pointer_copy", null);
            mod.registerMethod("to_bin", null);
            mod.registerMethod("to_decimal", null);
            mod.registerMethod("to_hex", null);
            mod.registerMethod("equals", null);
            mod.registerMethod("cmp", null);
            mod.registerMethod("is_zero", null);
            mod.registerMethod("is_one", null);
            mod.registerMethod("is_odd", null);
            mod.registerMethod("num_bits", null);
            mod.registerMethod("num_bytes", null);
            mod.registerMethod("copy", null);
            mod.registerMethod("DESTROY", null);
            // Arithmetic (static-style: take context-free args, return new Bignum)
            mod.registerMethod("add", null);
            mod.registerMethod("sub", null);
            mod.registerMethod("mul", null);
            mod.registerMethod("div", null);
            mod.registerMethod("mod", null);
            mod.registerMethod("exp", null);
            mod.registerMethod("mod_exp", null);
            mod.registerMethod("mod_inverse", null);
            mod.registerMethod("gcd", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Crypt::OpenSSL::Bignum method: " + e.getMessage());
        }
    }

    // ---- helpers ----

    /** Build a blessed hashref holding {@code $bn->{_bn_value} = <BigInteger>}. */
    public static RuntimeScalar wrap(BigInteger v) {
        RuntimeHash h = new RuntimeHash();
        h.blessId = NameNormalizer.getBlessId(CLASS_NAME);
        h.put(VALUE_KEY, new RuntimeScalar(v));
        return h.createReference();
    }

    /** Extract the BigInteger from a blessed Crypt::OpenSSL::Bignum hashref. */
    public static BigInteger unwrap(RuntimeScalar self) {
        RuntimeHash h = self.hashDeref();
        RuntimeScalar s = h.get(VALUE_KEY);
        if (s == null || s.type != JAVAOBJECT || !(s.value instanceof BigInteger bi)) {
            die(new RuntimeScalar("Crypt::OpenSSL::Bignum: invalid object (no value)"),
                    new RuntimeScalar("\n"));
            return null;
        }
        return bi;
    }

    /**
     * Unwrap a "pointer" handed to {@code bless_pointer} or produced by
     * {@code pointer_copy}. Accepts a scalar carrying a BigInteger JAVAOBJECT;
     * falls back to treating the scalar as a decimal string for robustness.
     */
    private static BigInteger unwrapPointer(RuntimeScalar ptr) {
        if (ptr.type == JAVAOBJECT && ptr.value instanceof BigInteger bi) return bi;
        try {
            return new BigInteger(ptr.toString());
        } catch (NumberFormatException e) {
            die(new RuntimeScalar("Crypt::OpenSSL::Bignum: bad pointer value"),
                    new RuntimeScalar("\n"));
            return null;
        }
    }

    // ---- constructors ----

    /** new_from_bin($class, $raw_bytes) — big-endian unsigned. */
    public static RuntimeList new_from_bin(RuntimeArray args, int ctx) {
        if (args.size() < 2) return scalarUndef.getList();
        byte[] bytes = args.get(1).toString().getBytes(StandardCharsets.ISO_8859_1);
        if (bytes.length == 0) return wrap(BigInteger.ZERO).getList();
        return wrap(new BigInteger(1, bytes)).getList();
    }

    public static RuntimeList new_from_decimal(RuntimeArray args, int ctx) {
        if (args.size() < 2) return scalarUndef.getList();
        try {
            return wrap(new BigInteger(args.get(1).toString(), 10)).getList();
        } catch (NumberFormatException e) {
            die(new RuntimeScalar("new_from_decimal: " + e.getMessage()), new RuntimeScalar("\n"));
            return scalarFalse.getList();
        }
    }

    public static RuntimeList new_from_hex(RuntimeArray args, int ctx) {
        if (args.size() < 2) return scalarUndef.getList();
        String s = args.get(1).toString();
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        try {
            return wrap(new BigInteger(s, 16)).getList();
        } catch (NumberFormatException e) {
            die(new RuntimeScalar("new_from_hex: " + e.getMessage()), new RuntimeScalar("\n"));
            return scalarFalse.getList();
        }
    }

    public static RuntimeList new_from_word(RuntimeArray args, int ctx) {
        if (args.size() < 2) return scalarUndef.getList();
        return wrap(BigInteger.valueOf(args.get(1).getLong())).getList();
    }

    public static RuntimeList zero(RuntimeArray args, int ctx) { return wrap(BigInteger.ZERO).getList(); }
    public static RuntimeList one(RuntimeArray args, int ctx)  { return wrap(BigInteger.ONE).getList(); }

    /** rand($class, $bits) — uniformly random integer of exactly $bits bits (top bit set). */
    public static RuntimeList rand(RuntimeArray args, int ctx) {
        int bits = args.size() >= 2 ? args.get(1).getInt() : 0;
        if (bits <= 0) return wrap(BigInteger.ZERO).getList();
        BigInteger v = new BigInteger(bits, RNG);
        v = v.setBit(bits - 1);     // force top bit
        return wrap(v).getList();
    }

    public static RuntimeList pseudo_rand(RuntimeArray args, int ctx) { return rand(args, ctx); }

    /** bless_pointer($class, $ptr) — wrap a scalar carrying a BigInteger back into a Bignum. */
    public static RuntimeList bless_pointer(RuntimeArray args, int ctx) {
        if (args.size() < 2) return scalarUndef.getList();
        BigInteger v = unwrapPointer(args.get(1));
        return wrap(v).getList();
    }

    // ---- instance accessors ----

    /** pointer_copy($self) — returns a scalar carrying the BigInteger value. */
    public static RuntimeList pointer_copy(RuntimeArray args, int ctx) {
        return new RuntimeScalar(unwrap(args.get(0))).getList();
    }

    public static RuntimeList to_bin(RuntimeArray args, int ctx) {
        BigInteger v = unwrap(args.get(0));
        if (v.signum() < 0) {
            die(new RuntimeScalar("to_bin: negative value"), new RuntimeScalar("\n"));
        }
        if (v.signum() == 0) {
            // OpenSSL's BN_bn2bin returns 0 bytes for zero.
            return new RuntimeScalar("").getList();
        }
        // BigInteger.toByteArray() returns two's-complement; strip any leading zero
        // byte that was added to keep the value non-negative.
        byte[] raw = v.toByteArray();
        if (raw.length > 1 && raw[0] == 0) {
            byte[] trimmed = new byte[raw.length - 1];
            System.arraycopy(raw, 1, trimmed, 0, trimmed.length);
            raw = trimmed;
        }
        return new RuntimeScalar(new String(raw, StandardCharsets.ISO_8859_1)).getList();
    }

    public static RuntimeList to_decimal(RuntimeArray args, int ctx) {
        return new RuntimeScalar(unwrap(args.get(0)).toString(10)).getList();
    }

    public static RuntimeList to_hex(RuntimeArray args, int ctx) {
        return new RuntimeScalar(unwrap(args.get(0)).toString(16).toUpperCase()).getList();
    }

    public static RuntimeList equals(RuntimeArray args, int ctx) {
        BigInteger a = unwrap(args.get(0));
        BigInteger b = unwrap(args.get(1));
        return (a.equals(b) ? scalarTrue : scalarFalse).getList();
    }

    public static RuntimeList cmp(RuntimeArray args, int ctx) {
        return new RuntimeScalar(unwrap(args.get(0)).compareTo(unwrap(args.get(1)))).getList();
    }

    public static RuntimeList is_zero(RuntimeArray args, int ctx) {
        return (unwrap(args.get(0)).signum() == 0 ? scalarTrue : scalarFalse).getList();
    }
    public static RuntimeList is_one(RuntimeArray args, int ctx) {
        return (unwrap(args.get(0)).equals(BigInteger.ONE) ? scalarTrue : scalarFalse).getList();
    }
    public static RuntimeList is_odd(RuntimeArray args, int ctx) {
        return (unwrap(args.get(0)).testBit(0) ? scalarTrue : scalarFalse).getList();
    }

    public static RuntimeList num_bits(RuntimeArray args, int ctx) {
        return new RuntimeScalar(unwrap(args.get(0)).bitLength()).getList();
    }
    public static RuntimeList num_bytes(RuntimeArray args, int ctx) {
        return new RuntimeScalar((unwrap(args.get(0)).bitLength() + 7) / 8).getList();
    }
    public static RuntimeList copy(RuntimeArray args, int ctx) {
        return wrap(unwrap(args.get(0))).getList();
    }
    public static RuntimeList DESTROY(RuntimeArray args, int ctx) {
        return scalarTrue.getList();
    }

    // ---- arithmetic ----
    // OpenSSL's Bignum API threads a third "context" argument through most ops;
    // we ignore it and return a fresh Bignum.

    public static RuntimeList add(RuntimeArray args, int ctx)  { return wrap(unwrap(args.get(0)).add(unwrap(args.get(1)))).getList(); }
    public static RuntimeList sub(RuntimeArray args, int ctx)  { return wrap(unwrap(args.get(0)).subtract(unwrap(args.get(1)))).getList(); }
    public static RuntimeList mul(RuntimeArray args, int ctx)  { return wrap(unwrap(args.get(0)).multiply(unwrap(args.get(1)))).getList(); }

    /** div($a, $b) in list context returns ($quotient, $remainder). */
    public static RuntimeList div(RuntimeArray args, int ctx) {
        BigInteger[] qr = unwrap(args.get(0)).divideAndRemainder(unwrap(args.get(1)));
        RuntimeList rl = new RuntimeList();
        rl.add(wrap(qr[0]));
        rl.add(wrap(qr[1]));
        return rl;
    }

    public static RuntimeList mod(RuntimeArray args, int ctx) {
        // mod(a, m) — always a non-negative remainder, to match OpenSSL.
        return wrap(unwrap(args.get(0)).mod(unwrap(args.get(1)).abs())).getList();
    }

    public static RuntimeList exp(RuntimeArray args, int ctx) {
        // exp(a, b) — a ** b, integer exponent.
        BigInteger b = unwrap(args.get(1));
        if (b.bitLength() > 31) {
            die(new RuntimeScalar("exp: exponent too large"), new RuntimeScalar("\n"));
        }
        return wrap(unwrap(args.get(0)).pow(b.intValueExact())).getList();
    }

    public static RuntimeList mod_exp(RuntimeArray args, int ctx) {
        return wrap(unwrap(args.get(0)).modPow(unwrap(args.get(1)), unwrap(args.get(2)))).getList();
    }

    public static RuntimeList mod_inverse(RuntimeArray args, int ctx) {
        try {
            return wrap(unwrap(args.get(0)).modInverse(unwrap(args.get(1)))).getList();
        } catch (ArithmeticException e) {
            return scalarUndef.getList();  // no inverse exists
        }
    }

    public static RuntimeList gcd(RuntimeArray args, int ctx) {
        return wrap(unwrap(args.get(0)).gcd(unwrap(args.get(1)))).getList();
    }
}

package org.perlonjava.runtime.perlmodule;

import org.bouncycastle.crypto.digests.KeccakDigest;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.JAVAOBJECT;

/**
 * Digest::SHA3 module implementation for PerlOnJava.
 * <p>
 * Mirrors the XS interface of the CPAN Digest-SHA3-1.05 distribution so the
 * unmodified CPAN shim (lib/Digest/SHA3.pm) works as-is. Backed by Bouncy
 * Castle's {@link KeccakDigest} with the SHA-3 / SHAKE domain separators
 * applied here in Java — we can't inherit from {@code SHA3Digest} /
 * {@code SHAKEDigest} directly because both call {@code absorbBits} inside
 * {@code doFinal}/{@code doOutput}, which fails after we've already flushed
 * a non-byte-aligned reservoir.
 */
public class DigestSHA3 extends PerlModuleBase {

    private static final String CLASS_NAME = "Digest::SHA3";
    private static final String DIGEST_KEY = "_digest";

    /**
     * Keccak state wrapper. Exposes BC's protected bit-level absorb/squeeze
     * methods and applies the SHA-3 / SHAKE domain-separator suffix ourselves
     * so we only ever call {@code absorbBits} once per finalize.
     */
    public static final class Keccak {
        final int alg;                // 224, 256, 384, 512, 128000, 256000
        final boolean isShake;        // true for SHAKE128/SHAKE256
        final int digestBits;         // 224/256/384/512 for SHA-3; 1344/1088 for SHAKE
        final int domainSuffix;       // SHA-3 = 0b10 (value 2, 2 bits); SHAKE = 0b1111 (value 15, 4 bits)
        final int domainBits;         // 2 for SHA-3, 4 for SHAKE
        ExposedKeccak digest;
        // Bit reservoir for multi-call non-byte-aligned add_bits():
        //   holds `reservoirBits` LSB-aligned bits not yet flushed to BC.
        int reservoir;
        int reservoirBits;
        boolean finalized; // SHAKE can squeeze more after first finalize

        Keccak(int alg) {
            this.alg = alg;
            switch (alg) {
                case 224 -> { this.isShake = false; this.digestBits = 224; }
                case 256 -> { this.isShake = false; this.digestBits = 256; }
                case 384 -> { this.isShake = false; this.digestBits = 384; }
                case 512 -> { this.isShake = false; this.digestBits = 512; }
                case 128000 -> { this.isShake = true; this.digestBits = 1344; }
                case 256000 -> { this.isShake = true; this.digestBits = 1088; }
                default -> throw new IllegalArgumentException("unsupported SHA3 alg: " + alg);
            }
            int kecRate = isShake ? (alg == 128000 ? 128 : 256) : alg;
            this.digest = new ExposedKeccak(kecRate);
            this.domainSuffix = isShake ? 0x0F : 0x02;
            this.domainBits = isShake ? 4 : 2;
        }

        Keccak(Keccak other) {
            this.alg = other.alg;
            this.isShake = other.isShake;
            this.digestBits = other.digestBits;
            this.domainSuffix = other.domainSuffix;
            this.domainBits = other.domainBits;
            this.digest = new ExposedKeccak(other.digest);
            this.reservoir = other.reservoir;
            this.reservoirBits = other.reservoirBits;
            this.finalized = other.finalized;
        }

        void rewind() {
            int kecRate = isShake ? (alg == 128000 ? 128 : 256) : alg;
            this.digest = new ExposedKeccak(kecRate);
            this.reservoir = 0;
            this.reservoirBits = 0;
            this.finalized = false;
        }

        /** Absorb `bitcnt` bits from `data`, LSB-aligned in each partial byte. */
        void write(byte[] data, long bitcnt) {
            if (bitcnt <= 0) return;
            long fullBytes = bitcnt >>> 3;
            int remBits = (int) (bitcnt & 7L);

            if (reservoirBits == 0 && fullBytes > 0) {
                if (fullBytes > Integer.MAX_VALUE) throw new IllegalArgumentException("SHA3 input too large");
                digest.absorb(data, 0, (int) fullBytes);
            } else {
                // Shift each new byte through the reservoir.
                int rbits = reservoirBits;
                int r = reservoir;
                for (long i = 0; i < fullBytes; i++) {
                    int nb = data[(int) i] & 0xFF;
                    int combined = r | (nb << rbits);
                    digest.absorbOneByte(combined & 0xFF);
                    r = (nb >>> (8 - rbits)) & ((1 << rbits) - 1);
                }
                reservoir = r;
            }

            if (remBits > 0) {
                int partial = data[(int) fullBytes] & ((1 << remBits) - 1);
                int combined = reservoir | (partial << reservoirBits);
                int total = reservoirBits + remBits;
                if (total >= 8) {
                    digest.absorbOneByte(combined & 0xFF);
                    reservoir = (combined >>> 8) & 0xFF;
                    reservoirBits = total - 8;
                } else {
                    reservoir = combined & 0xFF;
                    reservoirBits = total;
                }
            }
        }

        /**
         * Merge the SHA-3 / SHAKE domain-separator suffix into the reservoir,
         * flushing whole bytes as needed, then hand the final 1..7 bits to
         * {@code absorbBits}. Safe to call exactly once; subsequent output
         * calls for SHAKE squeeze more from BC's sponge directly.
         */
        private void applySuffixAndPad() {
            if (finalized) return;
            finalized = true;

            int combined = reservoir | (domainSuffix << reservoirBits);
            int total = reservoirBits + domainBits;
            while (total >= 8) {
                digest.absorbOneByte(combined & 0xFF);
                combined >>>= 8;
                total -= 8;
            }
            if (total > 0) {
                digest.absorbBits(combined & ((1 << total) - 1), total);
            }
            reservoir = 0;
            reservoirBits = 0;
        }

        byte[] finishDigest() {
            applySuffixAndPad();
            int n = digestBits / 8;
            byte[] out = new byte[n];
            digest.squeezeOut(out, 0, (long) n * 8);
            return out;
        }

        byte[] squeeze() {
            if (!isShake) return null;
            applySuffixAndPad();
            int n = digestBits / 8;
            byte[] out = new byte[n];
            digest.squeezeOut(out, 0, (long) n * 8);
            return out;
        }
    }

    /** KeccakDigest subclass exposing the protected absorb/squeeze methods. */
    static final class ExposedKeccak extends KeccakDigest {
        ExposedKeccak(int bitLength) { super(bitLength); }
        ExposedKeccak(ExposedKeccak other) { super(other); }
        public void absorb(byte[] data, int off, int len) { super.absorb(data, off, len); }
        public void absorbBits(int partial, int numBits) { super.absorbBits(partial, numBits); }
        public void absorbOneByte(int b) {
            byte[] one = { (byte) (b & 0xFF) };
            super.absorb(one, 0, 1);
        }
        public void squeezeOut(byte[] out, int off, long outBits) { super.squeeze(out, off, outBits); }
    }

    public DigestSHA3() {
        super(CLASS_NAME, false);
    }

    public static void initialize() {
        DigestSHA3 mod = new DigestSHA3();
        try {
            // XS-level primitives invoked by the CPAN Digest::SHA3.pm
            mod.registerMethod("newSHA3", null);
            mod.registerMethod("shainit", null);
            mod.registerMethod("sharewind", null);
            mod.registerMethod("shawrite", null);
            mod.registerMethod("add", null);
            mod.registerMethod("digest", null);
            mod.registerMethod("hexdigest", null);
            mod.registerMethod("b64digest", null);
            mod.registerMethod("squeeze", null);
            mod.registerMethod("clone", null);
            mod.registerMethod("hashsize", null);
            mod.registerMethod("algorithm", null);
            mod.registerMethod("_addfilebin", null);
            mod.registerMethod("_addfileuniv", null);
            // One-shot functional interface
            mod.registerMethod("sha3_224", null);
            mod.registerMethod("sha3_224_hex", null);
            mod.registerMethod("sha3_224_base64", null);
            mod.registerMethod("sha3_256", null);
            mod.registerMethod("sha3_256_hex", null);
            mod.registerMethod("sha3_256_base64", null);
            mod.registerMethod("sha3_384", null);
            mod.registerMethod("sha3_384_hex", null);
            mod.registerMethod("sha3_384_base64", null);
            mod.registerMethod("sha3_512", null);
            mod.registerMethod("sha3_512_hex", null);
            mod.registerMethod("sha3_512_base64", null);
            mod.registerMethod("shake128", null);
            mod.registerMethod("shake128_hex", null);
            mod.registerMethod("shake128_base64", null);
            mod.registerMethod("shake256", null);
            mod.registerMethod("shake256_hex", null);
            mod.registerMethod("shake256_base64", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Digest::SHA3 method: " + e.getMessage());
        }
    }

    // ---- helpers ----

    private static Keccak getState(RuntimeScalar self) {
        RuntimeHash h = self.hashDeref();
        RuntimeScalar d = h.get(DIGEST_KEY);
        if (d == null || d.type != JAVAOBJECT) return null;
        return (Keccak) d.value;
    }

    private static RuntimeHash newBlessedHash(String className, int alg) {
        RuntimeHash h = new RuntimeHash();
        h.blessId = NameNormalizer.getBlessId(className);
        h.put("algorithm", new RuntimeScalar(alg));
        h.put(DIGEST_KEY, new RuntimeScalar(new Keccak(alg)));
        return h;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static String toB64NoPad(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes).replaceAll("=+$", "");
    }

    private static int parseAlg(RuntimeScalar s) {
        int a = s.getInt();
        // Accept 3224/3256/etc. from the .pm's "^3?(224|...)$" regex stripping
        if (a == 3224) a = 224;
        else if (a == 3256) a = 256;
        else if (a == 3384) a = 384;
        else if (a == 3512) a = 512;
        return a;
    }

    private static boolean validAlg(int a) {
        return a == 224 || a == 256 || a == 384 || a == 512 || a == 128000 || a == 256000;
    }

    // ---- XS primitives ----

    /** newSHA3($classname, $alg) -> blessed object, or undef on failure */
    public static RuntimeList newSHA3(RuntimeArray args, int ctx) {
        if (args.size() < 2) return scalarUndef.getList();
        String className = args.get(0).toString();
        int alg = parseAlg(args.get(1));
        if (!validAlg(alg)) return scalarUndef.getList();
        RuntimeHash h = newBlessedHash(className, alg);
        return h.createReference().getList();
    }

    /** shainit($self, $alg) -> 1 on success, undef on bad alg */
    public static RuntimeList shainit(RuntimeArray args, int ctx) {
        if (args.size() < 2) return scalarUndef.getList();
        int alg = parseAlg(args.get(1));
        if (!validAlg(alg)) return scalarUndef.getList();
        RuntimeHash h = args.get(0).hashDeref();
        h.put("algorithm", new RuntimeScalar(alg));
        h.put(DIGEST_KEY, new RuntimeScalar(new Keccak(alg)));
        return new RuntimeScalar(1).getList();
    }

    /** sharewind($self) -> undef, resets state */
    public static RuntimeList sharewind(RuntimeArray args, int ctx) {
        if (args.isEmpty()) return scalarUndef.getList();
        Keccak s = getState(args.get(0));
        if (s == null) return scalarUndef.getList();
        s.rewind();
        return scalarUndef.getList();
    }

    /** shawrite($bitstr, $bitcnt, $self) -> bit count actually absorbed */
    public static RuntimeList shawrite(RuntimeArray args, int ctx) {
        if (args.size() < 3) return scalarUndef.getList();
        String bitstr = args.get(0).toString();
        long bitcnt = args.get(1).getLong();
        Keccak s = getState(args.get(2));
        if (s == null) return scalarUndef.getList();
        byte[] data = bitstr.getBytes(StandardCharsets.ISO_8859_1);
        s.write(data, bitcnt);
        return new RuntimeScalar(bitcnt).getList();
    }

    /** add($self, @data) -> $self */
    public static RuntimeList add(RuntimeArray args, int ctx) {
        if (args.isEmpty()) return scalarFalse.getList();
        Keccak s = getState(args.get(0));
        if (s == null) return scalarUndef.getList();
        for (int i = 1; i < args.size(); i++) {
            String d = args.get(i).toString();
            byte[] bytes = d.getBytes(StandardCharsets.ISO_8859_1);
            s.write(bytes, ((long) bytes.length) << 3);
        }
        return args.get(0).getList();
    }

    /** digest($self) -> raw bytes as string; auto-rewinds */
    public static RuntimeList digest(RuntimeArray args, int ctx) {
        Keccak s = args.isEmpty() ? null : getState(args.get(0));
        if (s == null) return scalarUndef.getList();
        byte[] out = s.finishDigest();
        s.rewind();
        return new RuntimeScalar(new String(out, StandardCharsets.ISO_8859_1)).getList();
    }

    /** hexdigest($self) -> hex string; auto-rewinds */
    public static RuntimeList hexdigest(RuntimeArray args, int ctx) {
        Keccak s = args.isEmpty() ? null : getState(args.get(0));
        if (s == null) return scalarUndef.getList();
        byte[] out = s.finishDigest();
        s.rewind();
        return new RuntimeScalar(toHex(out)).getList();
    }

    /** b64digest($self) -> unpadded base64; auto-rewinds */
    public static RuntimeList b64digest(RuntimeArray args, int ctx) {
        Keccak s = args.isEmpty() ? null : getState(args.get(0));
        if (s == null) return scalarUndef.getList();
        byte[] out = s.finishDigest();
        s.rewind();
        return new RuntimeScalar(toB64NoPad(out)).getList();
    }

    /** squeeze($self) -> next 168/136 bytes (SHAKE only). Does NOT rewind. */
    public static RuntimeList squeeze(RuntimeArray args, int ctx) {
        Keccak s = args.isEmpty() ? null : getState(args.get(0));
        if (s == null) return scalarUndef.getList();
        byte[] out = s.squeeze();
        if (out == null) return scalarUndef.getList();
        return new RuntimeScalar(new String(out, StandardCharsets.ISO_8859_1)).getList();
    }

    /** clone($self) -> new blessed object with duplicated state */
    public static RuntimeList clone(RuntimeArray args, int ctx) {
        if (args.isEmpty()) return scalarUndef.getList();
        RuntimeHash self = args.get(0).hashDeref();
        Keccak s = getState(args.get(0));
        if (s == null) return scalarUndef.getList();
        RuntimeHash h = new RuntimeHash();
        h.blessId = self.blessId;
        h.put("algorithm", self.get("algorithm"));
        h.put(DIGEST_KEY, new RuntimeScalar(new Keccak(s)));
        return h.createReference().getList();
    }

    /** hashsize($self) -> digest length in bits */
    public static RuntimeList hashsize(RuntimeArray args, int ctx) {
        Keccak s = args.isEmpty() ? null : getState(args.get(0));
        if (s == null) return scalarUndef.getList();
        return new RuntimeScalar(s.digestBits).getList();
    }

    /** algorithm($self) -> Perl-level algorithm code */
    public static RuntimeList algorithm(RuntimeArray args, int ctx) {
        Keccak s = args.isEmpty() ? null : getState(args.get(0));
        if (s == null) return scalarUndef.getList();
        return new RuntimeScalar(s.alg).getList();
    }

    /** _addfilebin($self, $fh) — feed a filehandle in binary mode. */
    public static RuntimeList _addfilebin(RuntimeArray args, int ctx) {
        if (args.size() < 2) return scalarUndef.getList();
        Keccak s = getState(args.get(0));
        if (s == null) return scalarUndef.getList();
        RuntimeIO fh = RuntimeIO.getRuntimeIO(args.get(1));
        if (fh == null) return scalarUndef.getList();
        fh.binmode(":raw");
        byte[] buf = new byte[8192];
        while (true) {
            RuntimeScalar r = fh.ioHandle.read(buf.length);
            if (r.type == RuntimeScalarType.UNDEF) break;
            String chunk = r.toString();
            if (chunk.isEmpty()) break;
            byte[] bytes = chunk.getBytes(StandardCharsets.ISO_8859_1);
            s.write(bytes, ((long) bytes.length) << 3);
        }
        return scalarTrue.getList();
    }

    /** _addfileuniv($self, $fh) — universal newlines; identical to bin for now. */
    public static RuntimeList _addfileuniv(RuntimeArray args, int ctx) {
        return _addfilebin(args, ctx);
    }

    // ---- one-shot functional interface ----

    private static byte[] oneShot(int alg, RuntimeArray args) {
        Keccak s = new Keccak(alg);
        for (int i = 0; i < args.size(); i++) {
            String d = args.get(i).toString();
            byte[] bytes = d.getBytes(StandardCharsets.ISO_8859_1);
            s.write(bytes, ((long) bytes.length) << 3);
        }
        return s.finishDigest();
    }

    // SHA3-224
    public static RuntimeList sha3_224(RuntimeArray a, int c) {
        return new RuntimeScalar(new String(oneShot(224, a), StandardCharsets.ISO_8859_1)).getList();
    }
    public static RuntimeList sha3_224_hex(RuntimeArray a, int c) {
        return new RuntimeScalar(toHex(oneShot(224, a))).getList();
    }
    public static RuntimeList sha3_224_base64(RuntimeArray a, int c) {
        return new RuntimeScalar(toB64NoPad(oneShot(224, a))).getList();
    }

    // SHA3-256
    public static RuntimeList sha3_256(RuntimeArray a, int c) {
        return new RuntimeScalar(new String(oneShot(256, a), StandardCharsets.ISO_8859_1)).getList();
    }
    public static RuntimeList sha3_256_hex(RuntimeArray a, int c) {
        return new RuntimeScalar(toHex(oneShot(256, a))).getList();
    }
    public static RuntimeList sha3_256_base64(RuntimeArray a, int c) {
        return new RuntimeScalar(toB64NoPad(oneShot(256, a))).getList();
    }

    // SHA3-384
    public static RuntimeList sha3_384(RuntimeArray a, int c) {
        return new RuntimeScalar(new String(oneShot(384, a), StandardCharsets.ISO_8859_1)).getList();
    }
    public static RuntimeList sha3_384_hex(RuntimeArray a, int c) {
        return new RuntimeScalar(toHex(oneShot(384, a))).getList();
    }
    public static RuntimeList sha3_384_base64(RuntimeArray a, int c) {
        return new RuntimeScalar(toB64NoPad(oneShot(384, a))).getList();
    }

    // SHA3-512
    public static RuntimeList sha3_512(RuntimeArray a, int c) {
        return new RuntimeScalar(new String(oneShot(512, a), StandardCharsets.ISO_8859_1)).getList();
    }
    public static RuntimeList sha3_512_hex(RuntimeArray a, int c) {
        return new RuntimeScalar(toHex(oneShot(512, a))).getList();
    }
    public static RuntimeList sha3_512_base64(RuntimeArray a, int c) {
        return new RuntimeScalar(toB64NoPad(oneShot(512, a))).getList();
    }

    // SHAKE128 — default output 168 bytes per CPAN module
    public static RuntimeList shake128(RuntimeArray a, int c) {
        return new RuntimeScalar(new String(oneShot(128000, a), StandardCharsets.ISO_8859_1)).getList();
    }
    public static RuntimeList shake128_hex(RuntimeArray a, int c) {
        return new RuntimeScalar(toHex(oneShot(128000, a))).getList();
    }
    public static RuntimeList shake128_base64(RuntimeArray a, int c) {
        return new RuntimeScalar(toB64NoPad(oneShot(128000, a))).getList();
    }

    // SHAKE256 — default output 136 bytes per CPAN module
    public static RuntimeList shake256(RuntimeArray a, int c) {
        return new RuntimeScalar(new String(oneShot(256000, a), StandardCharsets.ISO_8859_1)).getList();
    }
    public static RuntimeList shake256_hex(RuntimeArray a, int c) {
        return new RuntimeScalar(toHex(oneShot(256000, a))).getList();
    }
    public static RuntimeList shake256_base64(RuntimeArray a, int c) {
        return new RuntimeScalar(toB64NoPad(oneShot(256000, a))).getList();
    }
}

package org.perlonjava.runtime.perlmodule.storable;

import org.perlonjava.runtime.regex.RuntimeRegex;
import org.perlonjava.runtime.runtimetypes.NameNormalizer;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;
import org.perlonjava.runtime.runtimetypes.TieArray;
import org.perlonjava.runtime.runtimetypes.TieHash;
import org.perlonjava.runtime.runtimetypes.TieScalar;

import java.nio.charset.StandardCharsets;

/**
 * Refused / niche opcodes. Most of these can either be properly
 * implemented (CODE, REGEXP, LOBJECT) or refused with a clear message
 * matching upstream's CROAK text.
 * <p>
 * <strong>OWNER: misc-agent</strong>
 * <p>
 * Opcodes covered:
 * <ul>
 *   <li>{@link Opcodes#SX_CODE} — coderef as B::Deparse text. Refuse
 *       with upstream's "Can't retrieve code references" unless
 *       {@code $Storable::Eval} is true.</li>
 *   <li>{@link Opcodes#SX_REGEXP} — qr// regexp. See
 *       {@code retrieve_regexp}. Body: 1 byte pattern length (or 5),
 *       pattern bytes, 1 byte flags length, flag bytes.</li>
 *   <li>{@link Opcodes#SX_VSTRING} / {@link Opcodes#SX_LVSTRING} —
 *       version strings. Refuse on perls without vstring magic; we
 *       can decode-and-discard the magic and return the inner scalar.</li>
 *   <li>{@link Opcodes#SX_TIED_*} — tied containers. Refuse with
 *       upstream's "tied scalar/array/hash retrieval ..." message.</li>
 *   <li>{@link Opcodes#SX_LOBJECT} — large (&gt;2GB) string/array/hash
 *       dispatcher. Body: 1 byte sub-type ({@code SX_LSCALAR},
 *       {@code SX_LUTF8STR}, {@code SX_ARRAY}, {@code SX_HASH}) + 8
 *       byte size + body. We document support but it's unlikely to
 *       fire in practice; refuse with "Storable: oversized object" for
 *       now.</li>
 * </ul>
 */
public final class Misc {
    private Misc() {}

    public static RuntimeScalar readCode(StorableReader r, StorableContext c) {
        throw new StorableFormatException("Can't retrieve code references");
    }

    public static RuntimeScalar readRegexp(StorableReader r, StorableContext c) {
        // Body: <op_flags:u8> <re_len> <re_bytes> <flags_len:u8> <flag_bytes>
        // re_len is U32 (file byte order) when SHR_U32_RE_LEN bit is set in
        // op_flags, otherwise a single byte. flags_len is always 1 byte
        // (upstream's wire format has no large form for the flags length).
        // See Storable.xs `retrieve_regexp`.
        final int SHR_U32_RE_LEN = 0x01;
        int opFlags = c.readU8();
        long reLen;
        if ((opFlags & SHR_U32_RE_LEN) != 0) {
            reLen = c.readU32Length();
        } else {
            reLen = c.readU8();
        }
        if (reLen < 0 || reLen > Integer.MAX_VALUE) {
            throw new StorableFormatException("SX_REGEXP: pattern length out of range: " + reLen);
        }
        byte[] patBytes = c.readBytes((int) reLen);
        int flagsLen = c.readU8();
        byte[] flagBytes = c.readBytes(flagsLen);

        // Decode the pattern as UTF-8 (matches the writer's encoding and
        // upstream's behaviour for unicode patterns; ASCII patterns round-
        // trip identically). Flags are always ASCII.
        String pattern = new String(patBytes, StandardCharsets.UTF_8);
        String flags = new String(flagBytes, StandardCharsets.US_ASCII);

        RuntimeRegex regex = RuntimeRegex.compile(pattern, flags);
        RuntimeScalar sv = new RuntimeScalar(regex);
        c.recordSeen(sv);
        return sv;
    }

    public static RuntimeScalar readVString(StorableReader r, StorableContext c) {
        // Wire: 1 byte vstring magic length, that many magic bytes, then
        // a recursive scalar opcode for the underlying textual content.
        // Storable.xs L5833 retrieve_vstring.
        int magicLen = c.readU8();
        c.readBytes(magicLen);  // discard: PerlOnJava doesn't preserve the
                                // textual source form of v-strings.
        return readVStringInner(r, c);
    }

    public static RuntimeScalar readLVString(StorableReader r, StorableContext c) {
        // Same as readVString but with a U32 magic length. Storable.xs
        // L5864 retrieve_lvstring.
        long magicLen = c.readU32Length();
        if (magicLen < 0 || magicLen > Integer.MAX_VALUE) {
            throw new StorableFormatException("Storable: SX_LVSTRING length out of range: " + magicLen);
        }
        c.readBytes((int) magicLen);  // discard magic
        return readVStringInner(r, c);
    }

    /**
     * Shared body for SX_VSTRING / SX_LVSTRING after the magic blob has
     * been consumed: recurse to read the underlying scalar, mutate it
     * in place to type=VSTRING, and return it. Upstream's retrieve_vstring
     * does not allocate a fresh tag for the wrapper — it returns the
     * inner SV with v-string magic attached — so we match that here:
     * the inner scalar's recordSeen call is the one and only seen-table
     * entry for this opcode, keeping tag numbers aligned with upstream.
     */
    private static RuntimeScalar readVStringInner(StorableReader r, StorableContext c) {
        RuntimeScalar inner = r.dispatch(c);  // typically SX_SCALAR / SX_LSCALAR
        // Promote in place to a v-string. PerlOnJava's VSTRING type
        // stores the textual content directly as a String in `value`,
        // which is exactly what a fresh SX_SCALAR reader produced.
        inner.type = RuntimeScalarType.VSTRING;
        if (!(inner.value instanceof String)) {
            // Should be a String already from SX_SCALAR/SX_LSCALAR
            // (which constructs from byte[] via the bytes ctor that
            // stores ISO-8859-1 text). Be defensive in case the inner
            // dispatched to something exotic.
            inner.value = inner.toString();
        }
        return inner;
    }

    public static RuntimeScalar readTiedArray(StorableReader r, StorableContext c) {
        // Wire: SX_TIED_ARRAY <object>. Allocate a fresh placeholder
        // array and record it in the seen table BEFORE recursing to
        // retrieve the tying object (so backref tags inside the tying
        // object can resolve to the tied container). Then install the
        // tied magic and return the container ref.
        RuntimeArray av = new RuntimeArray();
        RuntimeScalar placeholder = av.createAnonymousReference();
        c.recordSeen(placeholder);
        RuntimeScalar tying = r.dispatch(c);
        installTiedArray(av, tying);
        return placeholder;
    }

    public static RuntimeScalar readTiedHash(StorableReader r, StorableContext c) {
        RuntimeHash hv = new RuntimeHash();
        RuntimeScalar placeholder = hv.createAnonymousReference();
        c.recordSeen(placeholder);
        RuntimeScalar tying = r.dispatch(c);
        installTiedHash(hv, tying);
        return placeholder;
    }

    public static RuntimeScalar readTiedScalar(StorableReader r, StorableContext c) {
        // Wire: SX_TIED_SCALAR <object>. The placeholder is a scalar
        // that, after install, has type=TIED_SCALAR and value=TieScalar.
        // The reference returned is a REFERENCE to that scalar, mirroring
        // upstream where retrieve_tied_scalar returns RV(SV-with-magic).
        RuntimeScalar inner = new RuntimeScalar();
        RuntimeScalar placeholder = inner.createReference();
        c.recordSeen(placeholder);
        RuntimeScalar tying = r.dispatch(c);
        installTiedScalar(inner, tying);
        return placeholder;
    }

    public static RuntimeScalar readTiedKey(StorableReader r, StorableContext c) {
        // Wire: SX_TIED_KEY <object> <key>. Upstream returns a tied
        // scalar whose magic dispatches FETCH/STORE on the tying
        // object using <key>. PerlOnJava has no built-in TieScalar
        // variant that takes an extra key, and synthesising one
        // would require new runtime infrastructure — so we still
        // refuse, but only after consuming both children so the
        // stream stays in sync if a caller chooses to catch and
        // continue.
        RuntimeScalar placeholder = new RuntimeScalar().createReference();
        c.recordSeen(placeholder);
        r.dispatch(c);  // tying object
        r.dispatch(c);  // key
        throw new StorableFormatException(
                "Storable: tied magic key retrieval not yet implemented");
    }

    public static RuntimeScalar readTiedIdx(StorableReader r, StorableContext c) {
        // Same shape as readTiedKey but with an integer index instead
        // of a key. See note above.
        RuntimeScalar placeholder = new RuntimeScalar().createReference();
        c.recordSeen(placeholder);
        r.dispatch(c);  // tying object
        r.dispatch(c);  // idx (a regular scalar opcode)
        throw new StorableFormatException(
                "Storable: tied magic index retrieval not yet implemented");
    }

    /** Install tied magic on a freshly allocated array, mirroring the
     *  effect of {@code TieOperators.tie} but driven by an already-
     *  constructed tying object (no TIEARRAY call is needed because
     *  the tying object was reconstructed from the wire). */
    static void installTiedArray(RuntimeArray av, RuntimeScalar tying) {
        String className = classnameOf(tying);
        RuntimeArray previousValue = new RuntimeArray(av);
        av.type = RuntimeArray.TIED_ARRAY;
        av.elements = new TieArray(className, previousValue, tying, av);
    }

    static void installTiedHash(RuntimeHash hv, RuntimeScalar tying) {
        String className = classnameOf(tying);
        RuntimeHash previousValue = RuntimeHash.createHash(hv);
        hv.type = RuntimeHash.TIED_HASH;
        hv.elements = new TieHash(className, previousValue, tying);
        hv.resetIterator();
    }

    static void installTiedScalar(RuntimeScalar scalar, RuntimeScalar tying) {
        String className = classnameOf(tying);
        RuntimeScalar previousValue = new RuntimeScalar(scalar);
        scalar.type = RuntimeScalarType.TIED_SCALAR;
        scalar.value = new TieScalar(className, previousValue, tying);
    }

    /** Best-effort class name for a tying object: prefer the bless
     *  package, fall back to "main" if the object is unblessed (which
     *  upstream would refuse, but we keep the round-trip working). */
    private static String classnameOf(RuntimeScalar tying) {
        int blessId = RuntimeScalarType.blessedId(tying);
        if (blessId == 0) return "main";
        String name = NameNormalizer.getBlessStr(blessId);
        return name == null ? "main" : name;
    }

    public static RuntimeScalar readLObject(StorableReader r, StorableContext c) {
        throw new StorableFormatException("misc-agent: SX_LOBJECT not yet implemented");
    }
}

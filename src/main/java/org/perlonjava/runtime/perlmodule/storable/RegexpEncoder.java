package org.perlonjava.runtime.perlmodule.storable;

import org.perlonjava.runtime.regex.RegexFlags;
import org.perlonjava.runtime.regex.RuntimeRegex;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.nio.charset.StandardCharsets;

/**
 * Encoder for {@code SX_REGEXP} (qr// patterns + flags).
 * <p>
 * Wire format (Storable.xs {@code store_regexp}, search for
 * {@code SX_REGEXP}):
 * <pre>
 *   SX_REGEXP &lt;op_flags:u8&gt; &lt;re_len&gt; &lt;re_bytes&gt; &lt;flags_len:u8&gt; &lt;flag_bytes&gt;
 * </pre>
 * Where {@code re_len} is 1 byte if {@code op_flags & SHR_U32_RE_LEN == 0},
 * else a 4-byte U32 (file byte order). {@code flags_len} is always a single
 * byte (upstream limits flags to a 1-byte length — flags strings are tiny
 * "msixn"-class subsets).
 * <p>
 * The corresponding read side is {@link Misc#readRegexp}.
 * <p>
 * Note: this method does NOT emit the surrounding {@link Opcodes#SX_BLESS}
 * "Regexp" wrapper (that's handled by the caller in
 * {@link StorableWriter#dispatchReferent}). It also does NOT call
 * {@link StorableContext#recordWriteSeen(Object)} — the caller has already
 * recorded the seen-tag on the enclosing {@link RuntimeScalar} so backrefs
 * resolve correctly.
 */
public final class RegexpEncoder {
    private RegexpEncoder() {}

    /** Upstream flag: re_len is encoded as U32 instead of a single byte. */
    private static final int SHR_U32_RE_LEN = 0x01;

    /** Emit {@code SX_REGEXP} followed by the pattern + flags bytes. */
    public static void write(StorableContext c, RuntimeScalar v) {
        if (!(v.value instanceof RuntimeRegex regex)) {
            throw new StorableFormatException(
                    "RegexpEncoder.write: expected RuntimeRegex, got "
                            + (v.value == null ? "null" : v.value.getClass().getName()));
        }

        String patternString = regex.patternString == null ? "" : regex.patternString;

        // Build a qr-compatible flag string. Only m, s, i, x, n are valid
        // qr// modifier characters that upstream's Storable::_make_re will
        // accept when it eval's `qr/$re/$flags`. Other RegexFlags fields
        // (g, p, r, etc.) aren't part of a qr// flag set and would either
        // fail the eval or produce different semantics on round-trip.
        // Order follows upstream's typical "msixn" canonical ordering.
        StringBuilder fb = new StringBuilder();
        RegexFlags rf = regex.getRegexFlags();
        if (rf != null) {
            if (rf.isMultiLine())       fb.append('m');
            if (rf.isDotAll())          fb.append('s');
            if (rf.isCaseInsensitive()) fb.append('i');
            if (rf.isExtended())        fb.append('x');
            if (rf.isNonCapturing())    fb.append('n');
        }
        String flagsString = fb.toString();

        byte[] patBytes = patternString.getBytes(StandardCharsets.UTF_8);
        byte[] flagBytes = flagsString.getBytes(StandardCharsets.UTF_8);

        if (flagBytes.length > 0xFF) {
            // Upstream's wire format does not support a large form for the
            // flags length; flags are always a 1-byte length. We should
            // never hit this with the msixn-only filter above but guard
            // anyway so a future addition doesn't silently truncate.
            throw new StorableFormatException(
                    "regexp flags string too long for SX_REGEXP: " + flagBytes.length);
        }

        c.writeByte(Opcodes.SX_REGEXP);

        int opFlags = (patBytes.length > 0xFF) ? SHR_U32_RE_LEN : 0;
        c.writeByte(opFlags);
        if ((opFlags & SHR_U32_RE_LEN) != 0) {
            c.writeU32Length(patBytes.length);
        } else {
            c.writeByte(patBytes.length);
        }
        c.writeBytes(patBytes);

        c.writeByte(flagBytes.length);
        c.writeBytes(flagBytes);
    }
}

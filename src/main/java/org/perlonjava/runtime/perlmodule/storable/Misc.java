package org.perlonjava.runtime.perlmodule.storable;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

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
        throw new StorableFormatException("misc-agent: SX_REGEXP not yet implemented");
    }

    public static RuntimeScalar readVString(StorableReader r, StorableContext c) {
        throw new StorableFormatException("misc-agent: SX_VSTRING not yet implemented");
    }

    public static RuntimeScalar readLVString(StorableReader r, StorableContext c) {
        throw new StorableFormatException("misc-agent: SX_LVSTRING not yet implemented");
    }

    public static RuntimeScalar readTiedArray(StorableReader r, StorableContext c) {
        throw new StorableFormatException("Storable: tied array retrieval not supported");
    }

    public static RuntimeScalar readTiedHash(StorableReader r, StorableContext c) {
        throw new StorableFormatException("Storable: tied hash retrieval not supported");
    }

    public static RuntimeScalar readTiedScalar(StorableReader r, StorableContext c) {
        throw new StorableFormatException("Storable: tied scalar retrieval not supported");
    }

    public static RuntimeScalar readTiedKey(StorableReader r, StorableContext c) {
        throw new StorableFormatException("Storable: tied magic key retrieval not supported");
    }

    public static RuntimeScalar readTiedIdx(StorableReader r, StorableContext c) {
        throw new StorableFormatException("Storable: tied magic index retrieval not supported");
    }

    public static RuntimeScalar readLObject(StorableReader r, StorableContext c) {
        throw new StorableFormatException("misc-agent: SX_LOBJECT not yet implemented");
    }
}

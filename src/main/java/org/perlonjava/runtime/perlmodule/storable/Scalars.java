package org.perlonjava.runtime.perlmodule.storable;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;

/**
 * Scalar opcode readers/writers.
 * <p>
 * <strong>OWNER: scalars-agent</strong>
 * <p>
 * Opcodes covered (Storable.xs L141-177):
 * <ul>
 *   <li>{@link Opcodes#SX_UNDEF} — undef scalar</li>
 *   <li>{@link Opcodes#SX_SV_UNDEF} — &amp;PL_sv_undef</li>
 *   <li>{@link Opcodes#SX_SV_YES} — &amp;PL_sv_yes (boolean true, legacy)</li>
 *   <li>{@link Opcodes#SX_SV_NO} — &amp;PL_sv_no (boolean false, legacy)</li>
 *   <li>{@link Opcodes#SX_BOOLEAN_TRUE} — true (modern)</li>
 *   <li>{@link Opcodes#SX_BOOLEAN_FALSE} — false (modern)</li>
 *   <li>{@link Opcodes#SX_BYTE} — signed byte (1-byte body, value = byte - 128)</li>
 *   <li>{@link Opcodes#SX_INTEGER} — native IV (8 bytes; use ctx.readNativeIV())</li>
 *   <li>{@link Opcodes#SX_NETINT} — 32-bit BE int (use ctx.readNetInt())</li>
 *   <li>{@link Opcodes#SX_DOUBLE} — native NV (use ctx.readNativeNV())</li>
 *   <li>{@link Opcodes#SX_SCALAR} — 1-byte len + bytes (binary; tag NOT utf8)</li>
 *   <li>{@link Opcodes#SX_LSCALAR} — U32 len + bytes (binary; tag NOT utf8)</li>
 *   <li>{@link Opcodes#SX_UTF8STR} — 1-byte len + bytes (tag utf8)</li>
 *   <li>{@link Opcodes#SX_LUTF8STR} — U32 len + bytes (tag utf8)</li>
 * </ul>
 * <p>
 * Each {@code read*} must call {@link StorableContext#recordSeen(RuntimeScalar)}
 * exactly once on the scalar it returns. See {@code retrieve_scalar},
 * {@code retrieve_integer}, etc. in {@code Storable.xs} (around L5750-6160).
 */
public final class Scalars {
    private Scalars() {}

    // -------- IMPLEMENTED IN STAGE A (canary tests) --------

    public static RuntimeScalar readUndef(StorableReader r, StorableContext c) {
        RuntimeScalar sv = new RuntimeScalar();      // Perl undef
        c.recordSeen(sv);
        return sv;
    }

    public static RuntimeScalar readSvUndef(StorableReader r, StorableContext c) {
        // Storable.xs returns &PL_sv_undef; we model it the same as SX_UNDEF.
        RuntimeScalar sv = new RuntimeScalar();
        c.recordSeen(sv);
        return sv;
    }

    public static RuntimeScalar readSvYes(StorableReader r, StorableContext c) {
        // Pre-boolean-opcode true. Cached singleton — but we still need a
        // distinct seen-table entry, so we cannot use the readonly singleton
        // directly if downstream tags would alias. Use a fresh scalar.
        RuntimeScalar sv = new RuntimeScalar(true);
        c.recordSeen(sv);
        return sv;
    }

    public static RuntimeScalar readSvNo(StorableReader r, StorableContext c) {
        RuntimeScalar sv = new RuntimeScalar(false);
        c.recordSeen(sv);
        return sv;
    }

    public static RuntimeScalar readBooleanTrue(StorableReader r, StorableContext c) {
        RuntimeScalar sv = new RuntimeScalar(true);
        c.recordSeen(sv);
        return sv;
    }

    public static RuntimeScalar readBooleanFalse(StorableReader r, StorableContext c) {
        RuntimeScalar sv = new RuntimeScalar(false);
        c.recordSeen(sv);
        return sv;
    }

    // -------- STUBS FOR PARALLEL AGENT --------
    // Each method should:
    //   1. Read its body using StorableContext primitives.
    //   2. Construct a RuntimeScalar (use new RuntimeScalar(int|long|double|String)).
    //   3. Call ctx.recordSeen(sv) exactly once.
    //   4. Return sv.
    // For UTF-8 vs binary distinction: SX_UTF8STR/SX_LUTF8STR set the
    // utf8-flag-equivalent on the resulting scalar. PerlOnJava strings
    // are Java String (decoded as UTF-8 if utf8-flagged, ISO-8859-1
    // otherwise). Use new String(bytes, StandardCharsets.UTF_8) for
    // utf8 and new String(bytes, StandardCharsets.ISO_8859_1) for
    // binary.

    public static RuntimeScalar readByte(StorableReader r, StorableContext c) {
        throw new StorableFormatException("scalars-agent: SX_BYTE not yet implemented");
    }

    public static RuntimeScalar readInteger(StorableReader r, StorableContext c) {
        throw new StorableFormatException("scalars-agent: SX_INTEGER not yet implemented");
    }

    public static RuntimeScalar readNetint(StorableReader r, StorableContext c) {
        throw new StorableFormatException("scalars-agent: SX_NETINT not yet implemented");
    }

    public static RuntimeScalar readDouble(StorableReader r, StorableContext c) {
        throw new StorableFormatException("scalars-agent: SX_DOUBLE not yet implemented");
    }

    public static RuntimeScalar readScalar(StorableReader r, StorableContext c) {
        throw new StorableFormatException("scalars-agent: SX_SCALAR not yet implemented");
    }

    public static RuntimeScalar readLScalar(StorableReader r, StorableContext c) {
        throw new StorableFormatException("scalars-agent: SX_LSCALAR not yet implemented");
    }

    public static RuntimeScalar readUtf8Str(StorableReader r, StorableContext c) {
        throw new StorableFormatException("scalars-agent: SX_UTF8STR not yet implemented");
    }

    public static RuntimeScalar readLUtf8Str(StorableReader r, StorableContext c) {
        throw new StorableFormatException("scalars-agent: SX_LUTF8STR not yet implemented");
    }

    // Suppress "unused import" warning until the agent fills in real impls.
    @SuppressWarnings("unused") private static final Object _keepImport = RuntimeScalarCache.scalarTrue;
}

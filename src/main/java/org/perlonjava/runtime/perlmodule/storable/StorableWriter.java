package org.perlonjava.runtime.perlmodule.storable;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;
import org.perlonjava.runtime.runtimetypes.NameNormalizer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Top-level Storable byte-stream writer. Produces output byte-compatible
 * with upstream Perl 5 Storable (see {@code store_*} family in
 * {@code perl5/dist/Storable/Storable.xs}, especially {@code store_scalar}
 * at L2393 and {@code store_ref} at L2328).
 * <p>
 * Conventions:
 * <ul>
 *   <li>The top-level entry point ({@link #writeTopLevelToFile} /
 *       {@link #writeTopLevelToMemory}) emits the appropriate header
 *       and then strips ONE outer reference, mirroring
 *       {@code do_store}'s {@code sv = SvRV(sv)}.</li>
 *   <li>{@link #dispatch} is the recursive entry — it consumes whatever
 *       value it's given (no auto-deref) and emits the right opcode
 *       sequence for it.</li>
 *   <li>Shared / cyclic substructures are detected via
 *       {@link StorableContext#lookupSeenTag(Object)} and emitted as
 *       {@code SX_OBJECT &lt;tag&gt;}.</li>
 * </ul>
 */
public final class StorableWriter {

    /** Encode {@code value} (must be a reference, like upstream's
     *  {@code do_store} requirement) as a complete file with a {@code pst0}
     *  header. Returns the encoded bytes as a string of 0..255 chars. */
    public String writeTopLevelToFile(RuntimeScalar value, boolean netorder) {
        StorableContext c = StorableContext.forWrite(netorder);
        Header.writeFile(c);
        emitTopLevel(c, value);
        return c.encoded();
    }

    /** Encode for in-memory {@code freeze}/{@code nfreeze}: same as
     *  {@link #writeTopLevelToFile} but with no {@code pst0} prefix. */
    public String writeTopLevelToMemory(RuntimeScalar value, boolean netorder) {
        StorableContext c = StorableContext.forWrite(netorder);
        Header.writeInMemory(c);
        emitTopLevel(c, value);
        return c.encoded();
    }

    private void emitTopLevel(StorableContext c, RuntimeScalar value) {
        // do_store requires the input to be a reference (Storable.xs L4593).
        // We're more permissive: accept bare scalars and emit them directly.
        // Real perl would croak with "Not a reference" — adjust later if needed.
        if (RuntimeScalarType.isReference(value)) {
            // The OUTER ref is stripped; we dispatch on the referent.
            // For ARRAYREFERENCE/HASHREFERENCE the referent is the AV/HV.
            // For REFERENCE (scalar ref) the referent is another RuntimeScalar.
            // Containers/blessed refs go through dispatchReferent which knows
            // how to record-seen the underlying value object so SX_OBJECT
            // backrefs match.
            dispatchReferent(c, value);
        } else {
            // Top-level non-ref: emit it straight.
            dispatch(c, value);
        }
    }

    /** Dispatch on a reference's referent. {@code refScalar} is the
     *  reference itself; we emit blessing wrappers, then the body, on the
     *  underlying container. */
    private void dispatchReferent(StorableContext c, RuntimeScalar refScalar) {
        // 1. If we've already emitted this referent before, write SX_OBJECT.
        Object key = sharedKey(refScalar);
        long tag = c.lookupSeenTag(key);
        if (tag >= 0) {
            c.writeByte(Opcodes.SX_OBJECT);
            c.writeU32Length(tag);
            return;
        }

        // 2. Blessed? Emit SX_BLESS / SX_IX_BLESS wrapper around the body.
        int blessId = RuntimeScalarType.blessedId(refScalar);
        if (blessId != 0) {
            String className = NameNormalizer.getBlessStr(blessId);
            int existing = c.lookupWriteClass(className);
            if (existing >= 0) {
                c.writeByte(Opcodes.SX_IX_BLESS);
                writeBlessLen(c, existing);
            } else {
                c.writeByte(Opcodes.SX_BLESS);
                byte[] cb = className.getBytes(StandardCharsets.UTF_8);
                writeBlessLen(c, cb.length);
                c.writeBytes(cb);
                c.recordWriteClass(className);
            }
            // fall through to write the body
        }

        // 3. Record seen NOW (matching upstream's SEEN order: container is
        //    registered before the body). The seen tag covers the inner
        //    container; the bless/ref wrappers don't get their own tag.
        c.recordWriteSeen(key);

        // 4. Emit body based on referent kind.
        switch (refScalar.type) {
            case RuntimeScalarType.ARRAYREFERENCE:
                writeArrayBody(c, (RuntimeArray) refScalar.value);
                break;
            case RuntimeScalarType.HASHREFERENCE:
                writeHashBody(c, (RuntimeHash) refScalar.value);
                break;
            case RuntimeScalarType.REFERENCE:
                // Scalar ref. The SX_REF byte was already written by our
                // caller (either `dispatch` for an inner ref, or
                // `emitTopLevel` which strips the outer ref entirely and
                // dispatches the referent directly). Emit only the inner.
                dispatch(c, (RuntimeScalar) refScalar.value);
                break;
            case RuntimeScalarType.CODE:
                throw new StorableFormatException("Can't store CODE items");
            case RuntimeScalarType.REGEX:
                // SX_REGEXP encoder is a real opcode; for now refuse so we
                // don't silently emit garbage.
                throw new StorableFormatException("storing regexes not yet supported by encoder");
            case RuntimeScalarType.GLOBREFERENCE:
                throw new StorableFormatException("Can't store GLOB items");
            default:
                throw new StorableFormatException("don't know how to store reference of type " + refScalar.type);
        }
    }

    /** Recursive entry: emit whatever {@code value} is. Bare scalars hit
     *  the SX_BYTE/INTEGER/DOUBLE/SCALAR/UTF8 logic. References go through
     *  {@link #dispatchReferent}. */
    public void dispatch(StorableContext c, RuntimeScalar value) {
        if (RuntimeScalarType.isReference(value)) {
            // An inner reference inside a container/scalar-ref. Emit
            // SX_REF/SX_WEAKREF/SX_OVERLOAD wrapper, then the body.
            //
            // (For now we always emit SX_REF — weak/overload detection
            //  requires extra plumbing into the runtime that's out of
            //  scope for the first encoder pass.)
            Object key = sharedKey(value);
            long tag = c.lookupSeenTag(key);
            if (tag >= 0) {
                c.writeByte(Opcodes.SX_OBJECT);
                c.writeU32Length(tag);
                return;
            }
            c.writeByte(Opcodes.SX_REF);
            dispatchReferent(c, value);
            return;
        }
        // Scalar dispatch.
        writeScalar(c, value);
    }

    /** Emit the body of a non-reference scalar. Mirrors
     *  {@code store_scalar} (Storable.xs L2393). */
    private void writeScalar(StorableContext c, RuntimeScalar v) {
        // undef
        if (v.type == RuntimeScalarType.UNDEF || !v.getDefinedBoolean()) {
            c.writeByte(Opcodes.SX_UNDEF);
            return;
        }
        // booleans
        if (v.type == RuntimeScalarType.BOOLEAN) {
            c.writeByte(v.getBoolean() ? Opcodes.SX_BOOLEAN_TRUE : Opcodes.SX_BOOLEAN_FALSE);
            return;
        }
        // integers
        if (v.type == RuntimeScalarType.INTEGER) {
            long iv = v.getLong();
            writeInteger(c, iv);
            return;
        }
        // doubles
        if (v.type == RuntimeScalarType.DOUBLE) {
            double dv = v.getDouble();
            // If the double is exactly representable as a long, upstream
            // collapses it back to integer encoding. Match that.
            long asLong = (long) dv;
            if ((double) asLong == dv && !Double.isNaN(dv)
                    && dv >= Long.MIN_VALUE && dv <= Long.MAX_VALUE) {
                writeInteger(c, asLong);
                return;
            }
            if (c.isNetorder()) {
                // Storable.xs: doubles in netorder are emitted as strings
                // for portability. Use the standard Perl-like decimal
                // representation.
                writeStringBody(c, Double.toString(dv).getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            c.writeByte(Opcodes.SX_DOUBLE);
            c.writeNativeNV(dv);
            return;
        }
        // strings
        String s = v.toString();
        if (v.type == RuntimeScalarType.BYTE_STRING) {
            writeStringBody(c, s.getBytes(StandardCharsets.ISO_8859_1), false);
        } else {
            // STRING (utf8-flagged), VSTRING, etc. Encode as UTF-8 bytes.
            writeStringBody(c, s.getBytes(StandardCharsets.UTF_8), true);
        }
    }

    private void writeInteger(StorableContext c, long iv) {
        if (iv >= -128 && iv <= 127) {
            c.writeByte(Opcodes.SX_BYTE);
            c.writeByte((int) (iv + 128) & 0xFF);
            return;
        }
        if (c.isNetorder() && iv >= Integer.MIN_VALUE && iv <= Integer.MAX_VALUE) {
            // SX_NETINT for nstore in 32-bit range.
            c.writeByte(Opcodes.SX_NETINT);
            c.writeNetInt((int) iv);
            return;
        }
        if (c.isNetorder()) {
            // Larger than 32 bits in netorder → store as decimal string,
            // matching Storable.xs's "large network order integer as
            // string" branch.
            writeStringBody(c, Long.toString(iv).getBytes(StandardCharsets.US_ASCII), false);
            return;
        }
        c.writeByte(Opcodes.SX_INTEGER);
        c.writeNativeIV(iv);
    }

    private void writeStringBody(StorableContext c, byte[] bytes, boolean utf8) {
        int small = utf8 ? Opcodes.SX_UTF8STR : Opcodes.SX_SCALAR;
        int large = utf8 ? Opcodes.SX_LUTF8STR : Opcodes.SX_LSCALAR;
        if (bytes.length <= Opcodes.LG_SCALAR) {
            c.writeByte(small);
            c.writeByte(bytes.length);
        } else {
            c.writeByte(large);
            c.writeU32Length(bytes.length);
        }
        c.writeBytes(bytes);
    }

    private void writeArrayBody(StorableContext c, RuntimeArray av) {
        c.writeByte(Opcodes.SX_ARRAY);
        List<RuntimeScalar> elems = new ArrayList<>(av.elements);  // snapshot
        c.writeU32Length(elems.size());
        for (RuntimeScalar e : elems) {
            dispatch(c, e == null ? new RuntimeScalar() : e);
        }
    }

    private void writeHashBody(StorableContext c, RuntimeHash hv) {
        c.writeByte(Opcodes.SX_HASH);
        c.writeU32Length(hv.elements.size());
        // Upstream order: VALUE first, then U32 keylen, then key bytes.
        for (var entry : hv.elements.entrySet()) {
            String key = entry.getKey();
            RuntimeScalar val = entry.getValue();
            dispatch(c, val == null ? new RuntimeScalar() : val);
            byte[] kb = key.getBytes(StandardCharsets.UTF_8);
            c.writeU32Length(kb.length);
            c.writeBytes(kb);
        }
    }

    /** {@code SX_BLESS} / {@code SX_IX_BLESS} length encoding: 1 byte for
     *  values 0..127, otherwise high bit set followed by a U32. */
    private static void writeBlessLen(StorableContext c, int n) {
        if (n <= Opcodes.LG_BLESS) {
            c.writeByte(n);
        } else {
            c.writeByte(0x80);
            c.writeU32Length(n);
        }
    }

    /** Identity key for the seen-table. For container refs the AV/HV is
     *  the natural identity; for plain scalar refs we key on the inner
     *  RuntimeScalar. */
    private static Object sharedKey(RuntimeScalar refScalar) {
        if (refScalar.value instanceof RuntimeArray
                || refScalar.value instanceof RuntimeHash
                || refScalar.value instanceof RuntimeScalar) {
            return refScalar.value;
        }
        return refScalar;
    }
}

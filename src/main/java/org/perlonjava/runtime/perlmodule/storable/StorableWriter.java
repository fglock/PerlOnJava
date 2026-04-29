package org.perlonjava.runtime.perlmodule.storable;

import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
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

    /** When true, hash keys are emitted in byte-lexicographic order so
     *  that the wire output of {@code freeze} / {@code store} is
     *  deterministic. Driven by {@code $Storable::canonical} in the
     *  Perl-level wrapper; threaded in by
     *  {@link org.perlonjava.runtime.perlmodule.Storable}. */
    private boolean canonical = false;

    /** Set the {@code $Storable::canonical} flag for this writer. Must
     *  be called before {@link #writeTopLevelToFile} /
     *  {@link #writeTopLevelToMemory}; the flag is read by every
     *  recursive {@code writeHashBody} call. */
    public void setCanonical(boolean canonical) {
        this.canonical = canonical;
    }

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
            // Strip ONE outer ref level (matching `sv = SvRV(sv)`).
            // For ARRAYREFERENCE/HASHREFERENCE the strip yields a bare
            // AV/HV — emit its body without an outer SX_REF wrapper.
            // For REFERENCE (scalar ref) the strip yields the inner
            // RuntimeScalar, which may itself be a reference. We dispatch
            // through `dispatch` which decides whether to emit
            // SX_REF/SX_OVERLOAD around the inner. That keeps the wire
            // format right for `freeze \$blessed_ref` (one ref of
            // wrapping in the output) and matches the corresponding
            // upstream `do_store` → `store` flow.
            if (value.type == RuntimeScalarType.REFERENCE) {
                dispatch(c, (RuntimeScalar) value.value);
            } else {
                dispatchReferent(c, value);
            }
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

        // 2. Blessed?
        int blessId = RuntimeScalarType.blessedId(refScalar);
        String className = blessId == 0 ? null : NameNormalizer.getBlessStr(blessId);

        // 2a. Class with STORABLE_freeze hook → emit SX_HOOK frame.
        if (className != null && tryEmitHook(c, refScalar, className)) {
            return;
        }

        // 2b. Tied container detection. If the referent's underlying
        // AV/HV/scalar carries tied magic, route through TiedEncoder
        // which emits SX_TIED_ARRAY / SX_TIED_HASH / SX_TIED_SCALAR
        // followed by the tying object. The tied agent fills in the
        // body of TiedEncoder.tryEmit; foundation just delegates.
        if (TiedEncoder.tryEmit(c, refScalar, this)) {
            return;
        }

        // 2c. Plain blessed: SX_BLESS / SX_IX_BLESS wrapper around the body.
        if (className != null) {
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
                // SX_REGEXP encoder. Foundation delegates to RegexpEncoder
                // which the regexp agent fills in.
                RegexpEncoder.write(c, refScalar);
                break;
            case RuntimeScalarType.GLOBREFERENCE:
                throw new StorableFormatException("Can't store GLOB items");
            default:
                throw new StorableFormatException("don't know how to store reference of type " + refScalar.type);
        }
    }

    /** Mirrors {@code store_hook} (Storable.xs L3574). Returns true if we
     *  emitted a {@code SX_HOOK} frame for this object; false to let the
     *  caller fall through to the normal {@code SX_BLESS} path. False is
     *  also returned when the class has no {@code STORABLE_freeze} method,
     *  or when the freeze returned an empty list (signal to skip the hook).
     *
     *  Limitations vs. upstream: we currently only handle the case where
     *  {@code STORABLE_freeze} returns a single scalar (the frozen
     *  cookie). When it returns sub-refs we still emit them (with their
     *  tag IDs) but recursion semantics around {@code SHF_NEED_RECURSE}
     *  may differ from the C path; tested only against
     *  {@code STORABLE_attach}-using classes.
     */
    private boolean tryEmitHook(StorableContext c, RuntimeScalar refScalar, String className) {
        RuntimeScalar freezeMethod = InheritanceResolver.findMethodInHierarchy(
                "STORABLE_freeze", className, null, 0, false);
        if (freezeMethod == null || freezeMethod.type != RuntimeScalarType.CODE) {
            return false;
        }

        // Call $obj->STORABLE_freeze($cloning=0) in LIST context.
        RuntimeArray callArgs = new RuntimeArray();
        RuntimeArray.push(callArgs, refScalar);
        RuntimeArray.push(callArgs, new RuntimeScalar(0));
        RuntimeList ret;
        try {
            ret = RuntimeCode.apply(freezeMethod, callArgs, RuntimeContextType.LIST);
        } catch (Exception e) {
            // Re-throw as-is so the caller's try/catch in Storable.java surfaces it.
            throw e;
        } finally {
            // Drain RuntimeArray.push refCount bumps so the original
            // blessed scalar can DESTROY when its lexical goes out
            // of scope. See Storable.releaseApplyArgs javadoc.
            org.perlonjava.runtime.perlmodule.Storable.releaseApplyArgs(callArgs);
        }
        List<RuntimeScalar> items = retList(ret);

        if (items.isEmpty()) {
            // Class has decided to opt out of the hook for this serialization.
            // Fall through to plain bless.
            return false;
        }

        // First element is the frozen cookie; rest are sub-refs.
        RuntimeScalar cookieSv = items.get(0);
        byte[] frozen = cookieSv == null
                ? new byte[0]
                : cookieSv.toString().getBytes(StandardCharsets.UTF_8);
        int subCount = items.size() - 1;

        // Determine object kind from the bless target.
        int objType;
        switch (refScalar.type) {
            case RuntimeScalarType.HASHREFERENCE:  objType = 2; break;  // SHT_HASH
            case RuntimeScalarType.ARRAYREFERENCE: objType = 1; break;  // SHT_ARRAY
            default:                               objType = 0; break;  // SHT_SCALAR
        }

        // STORABLE_attach is incompatible with sub-refs. Match upstream
        // CROAK at Storable.xs L3735.
        if (subCount > 0) {
            RuntimeScalar attachMethod = InheritanceResolver.findMethodInHierarchy(
                    "STORABLE_attach", className, null, 0, false);
            if (attachMethod != null && attachMethod.type == RuntimeScalarType.CODE) {
                throw new StorableFormatException(
                        "Freeze cannot return references if " + className
                                + " class is using STORABLE_attach");
            }
        }

        // For a hooked object we don't go through SX_BLESS; the SX_HOOK
        // frame carries the classname inline (or by index).
        // Register the seen-tag for backref resolution. Upstream stores
        // the eventual blessed object here; we use the input ref since
        // that's what's identity-shared in our model.
        c.recordWriteSeen(sharedKey(refScalar));

        // If sub-refs are present we must serialize them first (recursing
        // with SHF_NEED_RECURSE) so the receiver can resolve their tags
        // before invoking the hook. Implement the simple case (no sub-refs)
        // first; the multi-sub-ref path uses the recurse chain.
        long[] subTags = new long[subCount];
        boolean anyNew = false;
        for (int i = 0; i < subCount; i++) {
            RuntimeScalar rsv = items.get(i + 1);
            if (rsv == null || !RuntimeScalarType.isReference(rsv)) {
                throw new StorableFormatException(
                        "Item #" + (i + 1) + " returned by STORABLE_freeze for "
                                + className + " is not a reference");
            }
            Object subKey = sharedKey(rsv);
            long existing = c.lookupSeenTag(subKey);
            if (existing >= 0) {
                subTags[i] = existing;
                continue;
            }
            // Not yet stored — emit a recursion frame (SHF_NEED_RECURSE)
            // and serialize the target.
            int recurseFlags = SHF_NEED_RECURSE | objType;
            if (!anyNew) {
                c.writeByte(Opcodes.SX_HOOK);
                c.writeByte(recurseFlags);
            } else {
                c.writeByte(recurseFlags);
            }
            anyNew = true;
            // Recurse into the sub-ref. Do NOT pre-deref to its target —
            // dispatch handles refs (it'll emit the right SX_REF wrapper
            // and its inner). We pass the ref AS-IS so the receiver can
            // reconstruct the same shape.
            dispatch(c, rsv);
            long newTag = c.lookupSeenTag(subKey);
            if (newTag < 0) {
                throw new StorableFormatException(
                        "Could not serialize item #" + (i + 1) + " from hook in " + className);
            }
            subTags[i] = newTag;
        }

        // Now emit the main SX_HOOK frame (or just the trailing flags byte
        // if we already emitted SX_HOOK during the recursion phase).
        int classIdx = c.lookupWriteClass(className);
        boolean idxClass = classIdx >= 0;
        byte[] cb = className.getBytes(StandardCharsets.UTF_8);
        int flags = objType;
        if (idxClass) flags |= SHF_IDX_CLASSNAME;
        long classNumOrLen = idxClass ? classIdx : cb.length;
        if (classNumOrLen > Opcodes.LG_SCALAR) flags |= SHF_LARGE_CLASSLEN;
        if (frozen.length > Opcodes.LG_SCALAR) flags |= SHF_LARGE_STRLEN;
        if (subCount > 0) flags |= SHF_HAS_LIST;
        if (subCount > Opcodes.LG_SCALAR + 1) flags |= SHF_LARGE_LISTLEN;

        if (!anyNew) {
            c.writeByte(Opcodes.SX_HOOK);
        }
        c.writeByte(flags);

        // Classname or index
        if (idxClass) {
            if ((flags & SHF_LARGE_CLASSLEN) != 0) {
                c.writeU32Length(classIdx);
            } else {
                c.writeByte(classIdx);
            }
        } else {
            if ((flags & SHF_LARGE_CLASSLEN) != 0) {
                c.writeU32Length(cb.length);
            } else {
                c.writeByte(cb.length);
            }
            c.writeBytes(cb);
            c.recordWriteClass(className);
        }

        // Frozen string
        if ((flags & SHF_LARGE_STRLEN) != 0) {
            c.writeU32Length(frozen.length);
        } else {
            c.writeByte(frozen.length);
        }
        c.writeBytes(frozen);

        // Sub-object tag list
        if ((flags & SHF_HAS_LIST) != 0) {
            if ((flags & SHF_LARGE_LISTLEN) != 0) {
                c.writeU32Length(subCount);
            } else {
                c.writeByte(subCount);
            }
            for (long t : subTags) {
                c.writeU32Length(t);
            }
        }
        return true;
    }

    /** Drain a {@link RuntimeList} into a plain Java list of scalars. */
    private static List<RuntimeScalar> retList(RuntimeList ret) {
        List<RuntimeScalar> out = new ArrayList<>();
        if (ret == null) return out;
        for (var elem : ret.elements) {
            if (elem instanceof RuntimeScalar s) {
                out.add(s);
            } else if (elem instanceof RuntimeArray av) {
                out.addAll(av.elements);
            }
        }
        return out;
    }

    // --- SX_HOOK flag constants (mirroring Hooks.java) ---
    private static final int SHF_TYPE_MASK       = 0x03;
    private static final int SHF_LARGE_CLASSLEN  = 0x04;
    private static final int SHF_LARGE_STRLEN    = 0x08;
    private static final int SHF_LARGE_LISTLEN   = 0x10;
    private static final int SHF_IDX_CLASSNAME   = 0x20;
    private static final int SHF_NEED_RECURSE    = 0x40;
    private static final int SHF_HAS_LIST        = 0x80;

    /** Recursive entry: emit whatever {@code value} is. Bare scalars hit
     *  the SX_BYTE/INTEGER/DOUBLE/SCALAR/UTF8 logic. References go through
     *  {@link #dispatchReferent}. */
    public void dispatch(StorableContext c, RuntimeScalar value) {
        if (RuntimeScalarType.isReference(value)) {
            // An inner reference inside a container/scalar-ref. Emit
            // SX_REF (or SX_OVERLOAD when the inner is blessed into a
            // class with overload-pragma magic). Storable.xs L2350-L2354
            // makes the same choice on store_ref.
            //
            // Weak detection (SX_WEAKREF / SX_WEAKOVERLOAD) is not yet
            // wired through from the runtime — emitted as plain
            // SX_REF / SX_OVERLOAD for now.
            Object key = sharedKey(value);
            long tag = c.lookupSeenTag(key);
            if (tag >= 0) {
                c.writeByte(Opcodes.SX_OBJECT);
                c.writeU32Length(tag);
                return;
            }
            int blessId = RuntimeScalarType.blessedId(value);
            boolean isOverloaded = blessId != 0
                    && org.perlonjava.runtime.runtimetypes.OverloadContext
                            .prepare(blessId) != null;
            // Weak-ref detection: if the value (which is a reference) was
            // weakened via Scalar::Util::weaken, emit SX_WEAKREF /
            // SX_WEAKOVERLOAD instead of the strong variants. Mirrors
            // Storable.xs `store_ref` weak branch around L2362.
            boolean isWeak =
                    org.perlonjava.runtime.runtimetypes.WeakRefRegistry.weakRefsExist
                            && org.perlonjava.runtime.runtimetypes.WeakRefRegistry.isweak(value);
            int opcode;
            if (isWeak) {
                opcode = isOverloaded ? Opcodes.SX_WEAKOVERLOAD : Opcodes.SX_WEAKREF;
            } else {
                opcode = isOverloaded ? Opcodes.SX_OVERLOAD : Opcodes.SX_REF;
            }
            c.writeByte(opcode);
            // Bump the write-side tag for the SX_REF placeholder so
            // tags align with the read side, where `readRef` always
            // records its placeholder before recursing into the body
            // (Storable.xs `retrieve_ref` L5343). The key is unique
            // per emission so it does NOT participate in future
            // identity-shared lookups; outer-ref sharing falls back
            // to the inner-key check above.
            c.recordWriteSeen(new Object());
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
        // vstrings: SX_VSTRING / SX_LVSTRING + the embedded vstring magic
        // followed by a regular scalar body (Storable.xs L5833). Foundation
        // delegates to VStringEncoder which the vstring agent fills in.
        if (v.type == RuntimeScalarType.VSTRING) {
            VStringEncoder.write(c, v);
            return;
        }
        // strings
        String s = v.toString();
        if (v.type == RuntimeScalarType.BYTE_STRING) {
            writeStringBody(c, s.getBytes(StandardCharsets.ISO_8859_1), false);
        } else {
            // STRING (utf8-flagged), etc. Encode as UTF-8 bytes.
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
        // Snapshot keys, optionally sorted byte-lexicographically when
        // $Storable::canonical is in effect. Upstream sorts by the raw
        // UTF-8 byte representation (Storable.xs `store_hash` canonical
        // branch), which gives a stable order across hash randomization.
        List<String> keys = new ArrayList<>(hv.elements.keySet());
        if (canonical) {
            keys.sort((a, b) -> {
                byte[] ab = a.getBytes(StandardCharsets.UTF_8);
                byte[] bb = b.getBytes(StandardCharsets.UTF_8);
                int n = Math.min(ab.length, bb.length);
                for (int i = 0; i < n; i++) {
                    int x = ab[i] & 0xFF, y = bb[i] & 0xFF;
                    if (x != y) return x - y;
                }
                return ab.length - bb.length;
            });
        }

        // If any key carries non-ASCII characters, switch to SX_FLAG_HASH
        // with per-key SHV_K_UTF8 so the receiver knows to flag those
        // keys as utf8 (Storable.xs `store_hash` flag-hash branch).
        boolean anyUtf8 = false;
        for (String k : keys) {
            for (int i = 0; i < k.length(); i++) {
                if (k.charAt(i) >= 0x80) { anyUtf8 = true; break; }
            }
            if (anyUtf8) break;
        }

        if (anyUtf8) {
            c.writeByte(Opcodes.SX_FLAG_HASH);
            c.writeByte(0);                 // hash-flags byte (no RESTRICTED_HASH)
            c.writeU32Length(keys.size());
            for (String key : keys) {
                RuntimeScalar val = hv.elements.get(key);
                dispatch(c, val == null ? new RuntimeScalar() : val);
                byte[] kb = key.getBytes(StandardCharsets.UTF_8);
                int kf = 0;
                for (int i = 0; i < key.length(); i++) {
                    if (key.charAt(i) >= 0x80) { kf = SHV_K_UTF8; break; }
                }
                c.writeByte(kf);
                c.writeU32Length(kb.length);
                c.writeBytes(kb);
            }
            return;
        }

        c.writeByte(Opcodes.SX_HASH);
        c.writeU32Length(keys.size());
        // Upstream order: VALUE first, then U32 keylen, then key bytes.
        for (String key : keys) {
            RuntimeScalar val = hv.elements.get(key);
            dispatch(c, val == null ? new RuntimeScalar() : val);
            byte[] kb = key.getBytes(StandardCharsets.UTF_8);
            c.writeU32Length(kb.length);
            c.writeBytes(kb);
        }
    }

    /** Per-key flag bit emitted under {@code SX_FLAG_HASH} indicating the
     *  key is utf8-flagged. Mirrors upstream Storable's {@code SHV_K_UTF8}. */
    private static final int SHV_K_UTF8 = 0x01;

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

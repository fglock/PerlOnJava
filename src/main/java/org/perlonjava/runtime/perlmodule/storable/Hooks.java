package org.perlonjava.runtime.perlmodule.storable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

/**
 * STORABLE_freeze / STORABLE_thaw hook readers/writers.
 * <p>
 * <strong>OWNER: hooks-agent</strong>
 * <p>
 * Opcode covered:
 * <ul>
 *   <li>{@link Opcodes#SX_HOOK} — output of a class's STORABLE_freeze
 *       method. See {@code retrieve_hook_common} in
 *       {@code perl5/dist/Storable/Storable.xs} (around L4834).</li>
 * </ul>
 * <p>
 * Wire format (major=2, common case, no large-OID extension):
 * <pre>
 *   byte    flags F                    (SHF_* bitset, see constants)
 *   ...     zero or more recursed sub-objects, each preceded
 *           by their own flags byte read by this method (loop
 *           terminates when the last byte read does not have
 *           SHF_NEED_RECURSE set).
 *   byte/U32 classname-len OR class-index   (depending on flags)
 *   bytes   classname (only when not by index)
 *   byte/U32 frozen-string length
 *   bytes   frozen string (the cookie returned by STORABLE_freeze)
 *   byte/U32 sub-object list length         (only when SHF_HAS_LIST)
 *   U32 *   tag of each previously-seen sub-object
 * </pre>
 * <p>
 * Endianness for the U32 multi-byte length fields is dictated by
 * {@link StorableContext#readU32Length()} (network byte order if the
 * stream is netorder, otherwise the file's recorded byte order).
 * Sub-object tags are always read with {@link StorableContext#readU32Length()}
 * as well: upstream uses {@code READ_I32(tag); tag = ntohl(tag);},
 * which means the on-disk bytes are always big-endian. In native-order
 * dumps {@code READ_I32} reads native ints, but {@code ntohl} flips
 * them again on little-endian hosts, so the net result is the same as
 * "interpret 4 bytes per the file's chosen endianness" — exactly what
 * {@code readU32Length()} provides.
 */
public final class Hooks {

    // SHF_* flags (Storable.xs).
    private static final int SHF_TYPE_MASK      = 0x03;
    private static final int SHF_LARGE_CLASSLEN = 0x04;
    private static final int SHF_LARGE_STRLEN   = 0x08;
    private static final int SHF_LARGE_LISTLEN  = 0x10;
    private static final int SHF_IDX_CLASSNAME  = 0x20;
    private static final int SHF_NEED_RECURSE   = 0x40;
    private static final int SHF_HAS_LIST       = 0x80;

    // SHT_* object kinds (low 2 bits of flags).
    private static final int SHT_SCALAR = 0;
    private static final int SHT_ARRAY  = 1;
    private static final int SHT_HASH   = 2;
    private static final int SHT_EXTRA  = 3;

    private Hooks() {}

    /**
     * Read an SX_HOOK frame from {@code c} and return the resulting
     * blessed reference. Calls {@code STORABLE_thaw} on the produced
     * object via {@link RuntimeCode#apply}.
     *
     * @throws StorableFormatException for malformed frames, the SHT_EXTRA
     *         (tied) sub-type (not yet supported), and missing
     *         {@code STORABLE_thaw} methods.
     */
    public static RuntimeScalar readHook(StorableReader r, StorableContext c) {
        int flags = c.readU8();

        // Step 1: allocate the placeholder of the right kind and
        // record it in the seen-table BEFORE recursing or thawing,
        // so backref tags inside the sub-object list resolve.
        RuntimeScalar placeholder = allocatePlaceholder(flags & SHF_TYPE_MASK);
        int placeholderTag = c.recordSeen(placeholder);

        // Step 2: drain SHF_NEED_RECURSE chain. Each iteration retrieves
        // a sub-object (which records itself in the seen-table) and
        // then re-reads the flags byte. We discard the returned value:
        // the list step below references these objects by tag.
        // TODO: upstream decrements the refcount of these recursed
        // objects so that they are freed if the hook does not retain
        // them. PerlOnJava's GC handles this implicitly.
        while ((flags & SHF_NEED_RECURSE) != 0) {
            r.dispatch(c);
            flags = c.readU8();
        }

        // Step 3: classname (inline or by index).
        String classname = readClassname(c, flags);

        // Step 4: frozen string (the cookie).
        long frozenLen = (flags & SHF_LARGE_STRLEN) != 0
                ? c.readU32Length()
                : c.readU8();
        if (frozenLen < 0 || frozenLen > Integer.MAX_VALUE) {
            throw new StorableFormatException(
                    "SX_HOOK: frozen-string length " + frozenLen + " out of range");
        }
        byte[] frozenBytes = c.readBytes((int) frozenLen);
        String frozen = new String(frozenBytes, StandardCharsets.ISO_8859_1);

        // Step 5: optional list of sub-object references (by seen-tag).
        List<RuntimeScalar> extraRefs = new ArrayList<>();
        if ((flags & SHF_HAS_LIST) != 0) {
            long listLen = (flags & SHF_LARGE_LISTLEN) != 0
                    ? c.readU32Length()
                    : c.readU8();
            if (listLen < 0 || listLen > Integer.MAX_VALUE) {
                throw new StorableFormatException(
                        "SX_HOOK: list length " + listLen + " out of range");
            }
            for (long i = 0; i < listLen; i++) {
                long tag = c.readU32Length();
                extraRefs.add(c.getSeen(tag));
            }
        }

        // Step 6a: if the class defines STORABLE_attach, prefer that over
        // STORABLE_thaw. The attach hook is a CLASS method that returns a
        // fully-formed object; we replace the placeholder with the
        // returned object (preserving the tag). See retrieve_blessed in
        // Storable.xs ~L5119-5172.
        //
        // Per upstream (L5140), STORABLE_attach must NOT be called when
        // sub-refs are present — those imply the freeze hook returned
        // refs, which attach can't reconstruct. In that case we fall
        // through to STORABLE_thaw.
        RuntimeScalar attachMethod = InheritanceResolver.findMethodInHierarchy(
                "STORABLE_attach", classname, null, 0, false);
        if (attachMethod != null
                && attachMethod.type == RuntimeScalarType.CODE
                && extraRefs.isEmpty()) {
            RuntimeArray args = new RuntimeArray();
            RuntimeArray.push(args, new RuntimeScalar(classname));
            RuntimeArray.push(args, new RuntimeScalar(0)); // cloning = false
            RuntimeArray.push(args, new RuntimeScalar(frozen));
            org.perlonjava.runtime.runtimetypes.RuntimeList result =
                    RuntimeCode.apply(attachMethod, args, RuntimeContextType.SCALAR);
            RuntimeScalar attached = result.scalar();
            if (attached == null
                    || !RuntimeScalarType.isReference(attached)
                    || !isInstanceOf(attached, classname)) {
                throw new StorableFormatException(String.format(
                        "STORABLE_attach did not return a %s object", classname));
            }
            // Replace the placeholder in the seen table with the attached
            // object so any prior backref tag resolves to the right thing.
            c.replaceSeen(placeholderTag, attached);
            return attached;
        }

        // Step 6b: bless the placeholder into the class.
        ReferenceOperators.bless(placeholder, new RuntimeScalar(classname));

        // Step 7: invoke $obj->STORABLE_thaw($cloning=0, $frozen, @extraRefs).
        invokeThaw(classname, placeholder, frozen, extraRefs);

        return placeholder;
    }

    /** Returns true if {@code ref} is blessed into {@code classname} or a
     *  class derived from it. Mirrors upstream's {@code sv_derived_from}
     *  check on the value returned by STORABLE_attach. */
    private static boolean isInstanceOf(RuntimeScalar ref, String classname) {
        int blessId = RuntimeScalarType.blessedId(ref);
        if (blessId == 0) return false;
        String actual = org.perlonjava.runtime.runtimetypes.NameNormalizer.getBlessStr(blessId);
        if (actual == null) return false;
        if (actual.equals(classname)) return true;
        return InheritanceResolver.linearizeHierarchy(actual).contains(classname);
    }

    private static RuntimeScalar allocatePlaceholder(int objType) {
        switch (objType) {
            case SHT_SCALAR:
                return new RuntimeScalar().createReference();
            case SHT_ARRAY:
                return new RuntimeArray().createAnonymousReference();
            case SHT_HASH:
                return new RuntimeHash().createAnonymousReference();
            case SHT_EXTRA:
                throw new StorableFormatException(
                        "SX_HOOK: tied/SHT_EXTRA sub-type not supported");
            default:
                throw new StorableFormatException(
                        "SX_HOOK: unknown object type " + objType);
        }
    }

    private static String readClassname(StorableContext c, int flags) {
        if ((flags & SHF_IDX_CLASSNAME) != 0) {
            long idx = (flags & SHF_LARGE_CLASSLEN) != 0
                    ? c.readU32Length()
                    : c.readU8();
            return c.getClass(idx);
        }
        long len = (flags & SHF_LARGE_CLASSLEN) != 0
                ? c.readU32Length()
                : c.readU8();
        if (len < 0 || len > Integer.MAX_VALUE) {
            throw new StorableFormatException(
                    "SX_HOOK: classname length " + len + " out of range");
        }
        byte[] nameBytes = c.readBytes((int) len);
        String name = new String(nameBytes, StandardCharsets.US_ASCII);
        c.recordClass(name);
        return name;
    }

    private static void invokeThaw(String classname, RuntimeScalar self,
                                   String frozen, List<RuntimeScalar> extraRefs) {
        RuntimeScalar thawMethod = InheritanceResolver.findMethodInHierarchy(
                "STORABLE_thaw", classname, null, 0, false);
        if (thawMethod == null || thawMethod.type != RuntimeScalarType.CODE) {
            throw new StorableFormatException(
                    "Cannot retrieve via SX_HOOK: no STORABLE_thaw method "
                    + "available for class " + classname);
        }
        RuntimeArray args = new RuntimeArray();
        RuntimeArray.push(args, self);
        RuntimeArray.push(args, new RuntimeScalar(0)); // cloning = false
        RuntimeArray.push(args, new RuntimeScalar(frozen));
        for (RuntimeScalar ref : extraRefs) {
            RuntimeArray.push(args, ref);
        }
        RuntimeCode.apply(thawMethod, args, RuntimeContextType.VOID);
    }
}

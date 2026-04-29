package org.perlonjava.runtime.perlmodule.storable;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/**
 * Top-level Storable byte-stream reader. Consumes the opcode byte and
 * dispatches to one of the per-group helper classes
 * ({@link Scalars}, {@link Refs}, {@link Containers}, {@link Blessed},
 * {@link Hooks}, {@link Misc}).
 * <p>
 * <strong>Stage-A note for parallel agents:</strong> do not edit this
 * file. The dispatch table is fixed; fill in the
 * {@code read*} methods in the per-group classes you own. Each
 * group's contract:
 * <ul>
 *   <li>Read only the body (the opcode byte is already gone).</li>
 *   <li>Call {@link StorableContext#recordSeen(RuntimeScalar)} for
 *       every fresh scalar, in the same order upstream
 *       {@code SEEN_NN} would have. For containers, register the
 *       container <em>before</em> recursing into children.</li>
 *   <li>Throw {@link StorableFormatException} on malformed bodies.</li>
 *   <li>For unsupported (but legal) opcodes, throw
 *       {@link StorableFormatException} with a message that mirrors
 *       upstream {@code CROAK} text where practical.</li>
 * </ul>
 */
public final class StorableReader {

    /** Read one value from the current cursor position. Header must
     *  already have been parsed. Recursive entry point used by
     *  containers/refs/etc. */
    public RuntimeScalar dispatch(StorableContext c) {
        int op = c.readU8();
        switch (op) {
            // --- already-stored backref. NEVER calls recordSeen. ---
            case Opcodes.SX_OBJECT:        return Refs.readObject(this, c);

            // --- scalars ---
            case Opcodes.SX_UNDEF:         return Scalars.readUndef(this, c);
            case Opcodes.SX_SV_UNDEF:      return Scalars.readSvUndef(this, c);
            case Opcodes.SX_SV_YES:        return Scalars.readSvYes(this, c);
            case Opcodes.SX_SV_NO:         return Scalars.readSvNo(this, c);
            case Opcodes.SX_BOOLEAN_TRUE:  return Scalars.readBooleanTrue(this, c);
            case Opcodes.SX_BOOLEAN_FALSE: return Scalars.readBooleanFalse(this, c);
            case Opcodes.SX_BYTE:          return Scalars.readByte(this, c);
            case Opcodes.SX_INTEGER:       return Scalars.readInteger(this, c);
            case Opcodes.SX_NETINT:        return Scalars.readNetint(this, c);
            case Opcodes.SX_DOUBLE:        return Scalars.readDouble(this, c);
            case Opcodes.SX_SCALAR:        return Scalars.readScalar(this, c);
            case Opcodes.SX_LSCALAR:       return Scalars.readLScalar(this, c);
            case Opcodes.SX_UTF8STR:       return Scalars.readUtf8Str(this, c);
            case Opcodes.SX_LUTF8STR:      return Scalars.readLUtf8Str(this, c);

            // --- references ---
            case Opcodes.SX_REF:           return Refs.readRef(this, c);
            case Opcodes.SX_WEAKREF:       return Refs.readWeakRef(this, c);
            case Opcodes.SX_OVERLOAD:      return Refs.readOverload(this, c);
            case Opcodes.SX_WEAKOVERLOAD:  return Refs.readWeakOverload(this, c);

            // --- containers ---
            case Opcodes.SX_ARRAY:         return Containers.readArray(this, c);
            case Opcodes.SX_HASH:          return Containers.readHash(this, c);
            case Opcodes.SX_FLAG_HASH:     return Containers.readFlagHash(this, c);
            case Opcodes.SX_SVUNDEF_ELEM:  return Containers.readSvUndefElem(this, c);

            // --- blessed ---
            case Opcodes.SX_BLESS:         return Blessed.readBless(this, c);
            case Opcodes.SX_IX_BLESS:      return Blessed.readIxBless(this, c);

            // --- hooks ---
            case Opcodes.SX_HOOK:          return Hooks.readHook(this, c);

            // --- misc / refused ---
            case Opcodes.SX_CODE:          return Misc.readCode(this, c);
            case Opcodes.SX_REGEXP:        return Misc.readRegexp(this, c);
            case Opcodes.SX_VSTRING:       return Misc.readVString(this, c);
            case Opcodes.SX_LVSTRING:      return Misc.readLVString(this, c);
            case Opcodes.SX_TIED_ARRAY:    return Misc.readTiedArray(this, c);
            case Opcodes.SX_TIED_HASH:     return Misc.readTiedHash(this, c);
            case Opcodes.SX_TIED_SCALAR:   return Misc.readTiedScalar(this, c);
            case Opcodes.SX_TIED_KEY:      return Misc.readTiedKey(this, c);
            case Opcodes.SX_TIED_IDX:      return Misc.readTiedIdx(this, c);
            case Opcodes.SX_LOBJECT:       return Misc.readLObject(this, c);

            default:
                throw new StorableFormatException(
                        "Storable: corrupted binary image (opcode " + op + ")");
        }
    }
}

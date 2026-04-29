package org.perlonjava.runtime.perlmodule.storable;

/**
 * Storable wire-format opcodes.
 * <p>
 * Ported verbatim from {@code perl5/dist/Storable/Storable.xs} lines
 * 141&ndash;177 (current opcodes) and 182&ndash;194 (legacy in-hook /
 * pre-0.7 opcodes). Constants are kept as {@code int} rather than
 * {@code byte} so callers can compare against the unsigned read result
 * without explicit casts.
 * <p>
 * <strong>Do not change values.</strong> They are part of the on-disk
 * format that upstream {@code perl} produces and consumes.
 */
public final class Opcodes {
    private Opcodes() {}

    // --- current format (Storable >= 0.7), Storable.xs L141-177 ---
    public static final int SX_OBJECT        = 0;   // already stored object (backref tag follows)
    public static final int SX_LSCALAR       = 1;   // scalar (large binary): U32 length + bytes
    public static final int SX_ARRAY         = 2;   // array: U32 size + items
    public static final int SX_HASH          = 3;   // hash: U32 size + (value, U32 keylen, key) pairs
    public static final int SX_REF           = 4;   // reference to object
    public static final int SX_UNDEF         = 5;   // undefined scalar
    public static final int SX_INTEGER       = 6;   // native IV (8 bytes on 64-bit, byte order from header)
    public static final int SX_DOUBLE        = 7;   // native NV (8 bytes, byte order from header)
    public static final int SX_BYTE          = 8;   // 1 unsigned byte, value = byte - 128 (range [-128,127])
    public static final int SX_NETINT        = 9;   // 4-byte big-endian I32
    public static final int SX_SCALAR        = 10;  // scalar (small): 1-byte length + bytes
    public static final int SX_TIED_ARRAY    = 11;
    public static final int SX_TIED_HASH     = 12;
    public static final int SX_TIED_SCALAR   = 13;
    public static final int SX_SV_UNDEF      = 14;  // PL_sv_undef immortal
    public static final int SX_SV_YES        = 15;  // PL_sv_yes
    public static final int SX_SV_NO         = 16;  // PL_sv_no
    public static final int SX_BLESS         = 17;  // bless: classname-len + classname + body
    public static final int SX_IX_BLESS      = 18;  // bless by index into classname table
    public static final int SX_HOOK          = 19;  // STORABLE_freeze hook output
    public static final int SX_OVERLOAD      = 20;  // overloaded ref
    public static final int SX_TIED_KEY      = 21;
    public static final int SX_TIED_IDX      = 22;
    public static final int SX_UTF8STR       = 23;  // small UTF-8 string: 1-byte len + bytes
    public static final int SX_LUTF8STR      = 24;  // large UTF-8 string: U32 len + bytes
    public static final int SX_FLAG_HASH     = 25;  // hash with flags: U32 size + flags + (val, flags, U32 keylen, key) triplets
    public static final int SX_CODE          = 26;  // code ref (B::Deparse text)
    public static final int SX_WEAKREF       = 27;
    public static final int SX_WEAKOVERLOAD  = 28;
    public static final int SX_VSTRING       = 29;
    public static final int SX_LVSTRING      = 30;
    public static final int SX_SVUNDEF_ELEM  = 31;  // array slot set to &PL_sv_undef
    public static final int SX_REGEXP        = 32;
    public static final int SX_LOBJECT       = 33;  // large object (size > 2GB) -- string/array/hash dispatcher
    public static final int SX_BOOLEAN_TRUE  = 34;
    public static final int SX_BOOLEAN_FALSE = 35;
    public static final int SX_LAST          = 36;  // not a real opcode; sentinel only

    // --- pre-0.6 in-hook secondary opcodes, Storable.xs L182-186 ---
    // We don't emit these but we may see them when reading old hook output.
    public static final int SX_ITEM     = 'i';
    public static final int SX_IT_UNDEF = 'I';
    public static final int SX_KEY      = 'k';
    public static final int SX_VALUE    = 'v';
    public static final int SX_VL_UNDEF = 'V';

    // --- pre-0.7 bless variants, Storable.xs L192-194 ---
    public static final int SX_CLASS    = 'b';
    public static final int SX_LG_CLASS = 'B';
    public static final int SX_STORED   = 'X';

    // --- size limits, Storable.xs L200-201 ---
    /** Max length encodable in SX_SCALAR/SX_UTF8STR (1-byte len). */
    public static final int LG_SCALAR = 255;
    /** Max length encodable in SX_BLESS (1-byte len). Larger uses long form. */
    public static final int LG_BLESS  = 127;

    // --- file-format magic + version, Storable.xs L907-976 ---
    /** File magic: 'p','s','t','0'. */
    public static final byte[] MAGIC_BYTES = {'p', 's', 't', '0'};
    /** Legacy file magic for pre-0.6 dumps: 'perl-store'. We refuse these. */
    public static final byte[] OLD_MAGIC_BYTES = {'p','e','r','l','-','s','t','o','r','e'};
    public static final int STORABLE_BIN_MAJOR = 2;
    public static final int STORABLE_BIN_MINOR = 12;
}

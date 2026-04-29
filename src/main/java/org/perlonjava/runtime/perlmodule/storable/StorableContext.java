package org.perlonjava.runtime.perlmodule.storable;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable per-retrieve / per-store context.
 * <p>
 * Holds:
 * <ul>
 *   <li>the byte cursor (for the reader),</li>
 *   <li>the byte sink (for the writer; a writer-side method,
 *       {@link #writeByte(int)}, appends to a private {@code StringBuilder}
 *       — see {@link #encoded()}),</li>
 *   <li>the netorder flag (network byte order vs native), set by the
 *       header parser,</li>
 *   <li>the seen-table for backreferences ({@code SX_OBJECT} returns
 *       {@code seen[tag]}),</li>
 *   <li>the classname table for {@code SX_IX_BLESS}.</li>
 * </ul>
 * <p>
 * <strong>Endianness model.</strong> Network-order files are always
 * big-endian for U32 lengths and {@code I32} {@code SX_NETINT} values;
 * doubles in network-order files are still raw native bytes (Storable
 * does not byte-swap doubles for netorder, intentionally — see
 * {@code retrieve_double}). For native-order files we read multi-byte
 * scalars in the byte order recorded in the file header
 * ({@link #fileBigEndian}) and swap if it disagrees with the JVM. The
 * JVM reads with {@link java.io.DataInput}-equivalent helpers below
 * which return values as if read big-endian; we then byte-swap if the
 * file is little-endian.
 */
public final class StorableContext {

    // --- read-side state ---
    private final byte[] buf;
    private int pos;

    // --- write-side state (used by Phase 2) ---
    private final StringBuilder out;

    // --- format flags from header ---
    /** True if file/stream uses network (big-endian) byte order for
     *  multi-byte ints; false for native order. {@code SX_NETINT} is
     *  always big-endian regardless. */
    private boolean netorder;
    /** True if the native-order file's recorded byte order is big-endian
     *  ("4321" / "87654321"). Ignored when {@link #netorder} is true. */
    private boolean fileBigEndian = true;
    /** sizeof(IV) on the producing perl. We assume 8 unless told
     *  otherwise (modern perls are universally 64-bit; we refuse 32-bit
     *  IV files for now and document it). */
    private int sizeofIV = 8;
    /** sizeof(NV) on the producing perl, typically 8. Read from header
     *  when minor &gt;= 2; otherwise defaults to 8. */
    private int sizeofNV = 8;
    private int versionMajor = Opcodes.STORABLE_BIN_MAJOR;
    private int versionMinor = Opcodes.STORABLE_BIN_MINOR;

    // --- shared retrieval state ---
    /** Seen-table: every retrieved scalar is appended here in the
     *  order produced. {@code SX_OBJECT} reads a U32 tag and returns
     *  {@code seen.get(tag)}. See {@code SEEN_NN} in Storable.xs. */
    private final List<RuntimeScalar> seen = new ArrayList<>();
    /** Classname table for {@code SX_IX_BLESS}: {@code SX_BLESS}
     *  registers a classname here, {@code SX_IX_BLESS} indexes into it. */
    private final List<String> classes = new ArrayList<>();

    /** Construct a read-only context wrapping a byte array. */
    public StorableContext(byte[] data) {
        this.buf = data;
        this.pos = 0;
        this.out = null;
    }

    /** Construct a write context that accumulates into an internal buffer. */
    public StorableContext() {
        this.buf = null;
        this.pos = 0;
        this.out = new StringBuilder();
    }

    // --- header-driven setters (called by Header.parseFile) ---

    public void setNetorder(boolean netorder) { this.netorder = netorder; }
    public boolean isNetorder() { return netorder; }
    public void setFileBigEndian(boolean be) { this.fileBigEndian = be; }
    public boolean isFileBigEndian() { return fileBigEndian; }
    public void setSizeofIV(int n) { this.sizeofIV = n; }
    public int getSizeofIV() { return sizeofIV; }
    public void setSizeofNV(int n) { this.sizeofNV = n; }
    public int getSizeofNV() { return sizeofNV; }
    public void setVersion(int major, int minor) {
        this.versionMajor = major;
        this.versionMinor = minor;
    }
    public int getVersionMajor() { return versionMajor; }
    public int getVersionMinor() { return versionMinor; }

    // --- read primitives ---

    public boolean eof() { return pos >= buf.length; }
    public int remaining() { return buf.length - pos; }

    /** Read one unsigned byte (0..255) and advance. */
    public int readU8() {
        if (pos >= buf.length) throw new StorableFormatException("unexpected end of stream");
        return buf[pos++] & 0xFF;
    }

    /** Read {@code n} raw bytes (no decoding) and advance. */
    public byte[] readBytes(int n) {
        if (n < 0) throw new StorableFormatException("negative length " + n);
        if (pos + n > buf.length) {
            throw new StorableFormatException("short read: wanted " + n + " bytes, have " + (buf.length - pos));
        }
        byte[] r = new byte[n];
        System.arraycopy(buf, pos, r, 0, n);
        pos += n;
        return r;
    }

    /** Read 4-byte unsigned integer used for SX_LSCALAR / SX_LUTF8STR /
     *  SX_ARRAY / SX_HASH lengths. Network order if {@link #netorder},
     *  otherwise the file's native byte order. Returns as long to keep
     *  the unsigned value intact for sizes &gt; Integer.MAX_VALUE
     *  (which we will refuse for now in container readers). */
    public long readU32Length() {
        if (netorder || fileBigEndian) {
            return ((long)readU8() << 24)
                 | ((long)readU8() << 16)
                 | ((long)readU8() << 8)
                 |  (long)readU8();
        } else {
            int b0 = readU8(), b1 = readU8(), b2 = readU8(), b3 = readU8();
            return ((long)b3 << 24) | ((long)b2 << 16) | ((long)b1 << 8) | (long)b0;
        }
    }

    /** Read a SX_NETINT body: 4-byte big-endian signed I32, ALWAYS. */
    public int readNetInt() {
        return (readU8() << 24) | (readU8() << 16) | (readU8() << 8) | readU8();
    }

    /** Read a SX_INTEGER body: native IV (8 bytes on modern perl). Honors
     *  netorder / fileBigEndian. */
    public long readNativeIV() {
        if (sizeofIV != 8) {
            throw new StorableFormatException("unsupported sizeof(IV)=" + sizeofIV
                    + " (only 8-byte IV supported)");
        }
        long v = 0;
        if (netorder || fileBigEndian) {
            for (int i = 0; i < 8; i++) v = (v << 8) | readU8();
        } else {
            long acc = 0;
            for (int i = 0; i < 8; i++) acc |= ((long)readU8() & 0xFF) << (8 * i);
            v = acc;
        }
        return v;
    }

    /** Read a SX_DOUBLE body: native NV (8 bytes typical). Endianness
     *  follows fileBigEndian. NB: per Storable.xs, doubles are
     *  <em>not</em> byte-swapped for netorder — netorder only affects
     *  integer fields. */
    public double readNativeNV() {
        if (sizeofNV != 8) {
            throw new StorableFormatException("unsupported sizeof(NV)=" + sizeofNV);
        }
        long bits = 0;
        if (fileBigEndian) {
            for (int i = 0; i < 8; i++) bits = (bits << 8) | readU8();
        } else {
            long acc = 0;
            for (int i = 0; i < 8; i++) acc |= ((long)readU8() & 0xFF) << (8 * i);
            bits = acc;
        }
        return Double.longBitsToDouble(bits);
    }

    // --- write primitives (used by Phase 2 encoder) ---

    public void writeByte(int b) {
        if (out == null) throw new IllegalStateException("read-only context");
        out.append((char) (b & 0xFF));
    }

    public String encoded() {
        if (out == null) throw new IllegalStateException("read-only context");
        return out.toString();
    }

    // --- seen-table management ---

    /** Register a freshly retrieved scalar in the seen table at the next
     *  sequential tagnum. <em>Every</em> opcode reader that produces a
     *  new scalar (i.e. not SX_OBJECT itself) must call this exactly
     *  once before returning, in the order the original storer would
     *  have stored it — namely, at the first opportunity, which for
     *  containers means <em>before</em> recursing into children
     *  (hash/array storage records the container before its elements).
     *  See {@code SEEN_NN} in Storable.xs. */
    public int recordSeen(RuntimeScalar sv) {
        seen.add(sv);
        return seen.size() - 1;
    }

    /** Look up a previously seen scalar by tagnum (used by SX_OBJECT). */
    public RuntimeScalar getSeen(long tag) {
        if (tag < 0 || tag >= seen.size()) {
            throw new StorableFormatException(
                    "object tag " + tag + " out of range (have " + seen.size() + ")");
        }
        return seen.get((int) tag);
    }

    // --- class table for SX_IX_BLESS ---

    public int recordClass(String name) {
        classes.add(name);
        return classes.size() - 1;
    }

    public String getClass(long ix) {
        if (ix < 0 || ix >= classes.size()) {
            throw new StorableFormatException(
                    "classname tag " + ix + " out of range (have " + classes.size() + ")");
        }
        return classes.get((int) ix);
    }
}

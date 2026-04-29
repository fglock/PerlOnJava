package org.perlonjava.runtime.perlmodule.storable;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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

    // --- write-side state ---

    /** Identity-keyed seen table for the encoder. Keyed on the underlying
     *  container (RuntimeArray/RuntimeHash) for refs, on the RuntimeScalar
     *  itself for shared scalar refs. Value is the assigned tag. Mirrors
     *  upstream's hv_fetch-by-pointer in {@code store()}. */
    private final IdentityHashMap<Object, Long> writeSeen = new IdentityHashMap<>();
    /** Next write-side tag to allocate. */
    private long nextWriteTag = 0;
    /** Classname → index, for {@code SX_BLESS}/{@code SX_IX_BLESS} encoding. */
    private final Map<String, Integer> writeClasses = new HashMap<>();

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

    /** Construct a write context with the netorder flag set up front
     *  (so {@link #writeU32Length(long)} and friends pick the right
     *  byte order before {@link Header#writeFile} runs). */
    public static StorableContext forWrite(boolean netorder) {
        StorableContext c = new StorableContext();
        c.netorder = netorder;
        c.fileBigEndian = true;   // we always emit big-endian native too
        return c;
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

    /** Peek at the next unsigned byte without advancing. */
    public int peekU8() {
        if (pos >= buf.length) throw new StorableFormatException("unexpected end of stream");
        return buf[pos] & 0xFF;
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

    /** Append raw bytes (each as a 0..255 char in the underlying string). */
    public void writeBytes(byte[] bytes) {
        if (out == null) throw new IllegalStateException("read-only context");
        for (byte by : bytes) out.append((char) (by & 0xFF));
    }

    /** Emit a U32 length using the file's byte order (network or native).
     *  Mirrors {@link #readU32Length()}. */
    public void writeU32Length(long len) {
        if (len < 0 || len > 0xFFFFFFFFL) {
            throw new StorableFormatException("U32 length out of range: " + len);
        }
        if (netorder || fileBigEndian) {
            writeByte((int) ((len >>> 24) & 0xFF));
            writeByte((int) ((len >>> 16) & 0xFF));
            writeByte((int) ((len >>> 8)  & 0xFF));
            writeByte((int) ( len         & 0xFF));
        } else {
            writeByte((int) ( len         & 0xFF));
            writeByte((int) ((len >>> 8)  & 0xFF));
            writeByte((int) ((len >>> 16) & 0xFF));
            writeByte((int) ((len >>> 24) & 0xFF));
        }
    }

    /** Emit a 32-bit big-endian signed I32 (always BE — for SX_NETINT). */
    public void writeNetInt(int v) {
        writeByte((v >>> 24) & 0xFF);
        writeByte((v >>> 16) & 0xFF);
        writeByte((v >>> 8)  & 0xFF);
        writeByte( v         & 0xFF);
    }

    /** Emit a native IV (8 bytes, file byte order). Used for SX_INTEGER. */
    public void writeNativeIV(long v) {
        if (netorder || fileBigEndian) {
            for (int i = 7; i >= 0; i--) writeByte((int) ((v >>> (8 * i)) & 0xFF));
        } else {
            for (int i = 0; i < 8; i++) writeByte((int) ((v >>> (8 * i)) & 0xFF));
        }
    }

    /** Emit a native NV (8 bytes, file byte order). Used for SX_DOUBLE.
     *  Per Storable.xs, doubles are NOT byte-swapped for netorder; for
     *  netorder mode upstream actually serializes doubles as strings. We
     *  always use native byte order here (matching {@link #readNativeNV()}). */
    public void writeNativeNV(double d) {
        long bits = Double.doubleToRawLongBits(d);
        if (fileBigEndian) {
            for (int i = 7; i >= 0; i--) writeByte((int) ((bits >>> (8 * i)) & 0xFF));
        } else {
            for (int i = 0; i < 8; i++) writeByte((int) ((bits >>> (8 * i)) & 0xFF));
        }
    }

    public String encoded() {
        if (out == null) throw new IllegalStateException("read-only context");
        return out.toString();
    }

    /** Look up an object's existing write tag, or {@code -1} if not yet
     *  seen. Identity-keyed. */
    public long lookupSeenTag(Object key) {
        Long t = writeSeen.get(key);
        return t == null ? -1 : t;
    }

    /** Register an object in the write seen-table at the next sequential
     *  tag. Returns the assigned tag. Caller must call this for every
     *  fresh value emitted, in the same order upstream's
     *  {@code SEEN_NN}/{@code store()} would have. */
    public long recordWriteSeen(Object key) {
        long tag = nextWriteTag++;
        writeSeen.put(key, tag);
        return tag;
    }

    /** Look up a classname's index for {@code SX_IX_BLESS}, or {@code -1}
     *  if this class hasn't been emitted via {@code SX_BLESS} yet. */
    public int lookupWriteClass(String name) {
        Integer ix = writeClasses.get(name);
        return ix == null ? -1 : ix;
    }

    /** Register a classname, returning its assigned index. */
    public int recordWriteClass(String name) {
        int ix = writeClasses.size();
        writeClasses.put(name, ix);
        return ix;
    }

    // --- bare-container sentinel (option a in storable_binary_format.md, item 8) ---
    //
    // Container readers (SX_ARRAY / SX_HASH / SX_FLAG_HASH) return
    // already-wrapped ARRAYREFERENCE / HASHREFERENCE scalars (one ref
    // level above bare AV/HV in upstream's data model). When an SX_REF
    // wraps such a "bare-container" body the SX_REF wrapper is
    // structurally redundant and must collapse to keep the level count
    // matching upstream (see {@code retrieve_ref} in Storable.xs L5321
    // which calls {@code SvRV_set} once on top of an AV/HV). When an
    // SX_REF wraps something that already carries a real ref level
    // (the result of another SX_REF, an SX_HOOK / SX_OBJECT result,
    // etc.), the SX_REF really adds a level and must wrap.
    //
    // The flag is one-shot: {@link #markBareContainer()} sets it,
    // {@link #takeBareContainerFlag()} reads-and-clears it. Refs.readRef
    // and friends drain the flag before recursing (so it doesn't leak
    // from a sibling) and again after recursing (to learn what the body
    // was). Storable.thaw / Storable.retrieve also drain it once after
    // dispatch returns to keep state clean across calls.
    private boolean lastWasBareContainer = false;

    /** Read-and-clear the bare-container flag. Returns whatever was
     *  most recently {@linkplain #markBareContainer marked}. */
    public boolean takeBareContainerFlag() {
        boolean v = lastWasBareContainer;
        lastWasBareContainer = false;
        return v;
    }

    /** Mark the most recently produced value as a bare-container body
     *  (an ARRAYREFERENCE / HASHREFERENCE that stands in for an
     *  upstream {@code AV}/{@code HV}). The flag is consumed by the
     *  next caller of {@link #takeBareContainerFlag()}. */
    public void markBareContainer() {
        this.lastWasBareContainer = true;
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

    /** Replace an entry in the seen table. Used by {@code SX_HOOK} when
     *  {@code STORABLE_attach} returns a fresh object, replacing the
     *  placeholder we recorded earlier. The replacement must keep the
     *  tag stable so any backref tag we already emitted resolves to the
     *  new object. */
    public void replaceSeen(int tag, RuntimeScalar sv) {
        if (tag < 0 || tag >= seen.size()) {
            throw new StorableFormatException(
                    "replaceSeen: tag " + tag + " out of range (have " + seen.size() + ")");
        }
        seen.set(tag, sv);
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

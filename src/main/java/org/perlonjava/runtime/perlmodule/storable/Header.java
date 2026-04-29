package org.perlonjava.runtime.perlmodule.storable;

import java.util.Arrays;

/**
 * Parses the file-format header. See {@code magic_check} in
 * {@code perl5/dist/Storable/Storable.xs} (around L7022).
 * <p>
 * On-disk layout (current, major=2):
 * <pre>
 *   bytes  "pst0"
 *   byte   (major &lt;&lt; 1) | netorder    // bit 0 = netorder flag
 *   byte   minor
 *   if !netorder:
 *     byte  byteorder-string-length N
 *     N bytes  byteorder string ("12345678", "87654321", "1234", "4321")
 *     byte  sizeof(int)
 *     byte  sizeof(long)
 *     byte  sizeof(char *)
 *     if minor &gt;= 2: byte sizeof(NV)
 * </pre>
 * <p>
 * In-memory frozen blobs (output of {@code freeze}/{@code nfreeze}):
 * no {@code pst0} prefix — just the netorder byte then minor (and the
 * native-order tail if applicable). See {@code magic_check}'s {@code
 * !cxt->fio} branch.
 */
public final class Header {
    private Header() {}

    /** Result of parsing: just informational; mutates the context. */
    public static final class HeaderInfo {
        public final int major, minor;
        public final boolean netorder;
        public final boolean fileBigEndian;
        public final int sizeofInt, sizeofLong, sizeofPtr, sizeofNV;
        HeaderInfo(int major, int minor, boolean netorder, boolean be,
                   int sInt, int sLong, int sPtr, int sNV) {
            this.major = major; this.minor = minor;
            this.netorder = netorder; this.fileBigEndian = be;
            this.sizeofInt = sInt; this.sizeofLong = sLong;
            this.sizeofPtr = sPtr; this.sizeofNV = sNV;
        }
    }

    /** Parse a file-style header (with {@code pst0} prefix). */
    public static HeaderInfo parseFile(StorableContext c) {
        byte[] magic = c.readBytes(4);
        if (!Arrays.equals(magic, Opcodes.MAGIC_BYTES)) {
            // Try old magic. Storable.xs reads sizeof(magicstr) - sizeof(MAGICSTR_BYTES)
            // more bytes here; we just check the longer prefix.
            byte[] rest = c.readBytes(Opcodes.OLD_MAGIC_BYTES.length - 4);
            byte[] full = new byte[Opcodes.OLD_MAGIC_BYTES.length];
            System.arraycopy(magic, 0, full, 0, 4);
            System.arraycopy(rest, 0, full, 4, rest.length);
            if (!Arrays.equals(full, Opcodes.OLD_MAGIC_BYTES)) {
                throw new StorableFormatException("File is not a perl storable");
            }
            // We don't support the pre-0.6 dialect.
            throw new StorableFormatException(
                    "Storable binary image uses pre-0.6 'perl-store' magic; not supported");
        }

        int useNetorderByte = c.readU8();
        int major = useNetorderByte >> 1;
        boolean netorder = (useNetorderByte & 0x1) != 0;

        int minor = 0;
        if (major > 1) {
            minor = c.readU8();
        }

        if (major > Opcodes.STORABLE_BIN_MAJOR
                || (major == Opcodes.STORABLE_BIN_MAJOR && minor > Opcodes.STORABLE_BIN_MINOR)) {
            // Mirror upstream wording so users can grep.
            throw new StorableFormatException(String.format(
                    "Storable binary image v%d.%d more recent than I am (v%d.%d)",
                    major, minor, Opcodes.STORABLE_BIN_MAJOR, Opcodes.STORABLE_BIN_MINOR));
        }

        c.setVersion(major, minor);
        c.setNetorder(netorder);

        int sInt = 0, sLong = 0, sPtr = 0, sNV = 8;
        boolean be = true;

        if (!netorder) {
            int n = c.readU8();
            byte[] bo = c.readBytes(n);
            String boStr = new String(bo, java.nio.charset.StandardCharsets.US_ASCII);
            // Big-endian byteorder strings count up: "1234"/"12345678".
            // Little-endian counts down: "4321"/"87654321".
            if (boStr.equals("1234") || boStr.equals("12345678")) {
                be = true;
            } else if (boStr.equals("4321") || boStr.equals("87654321")) {
                be = false;
            } else {
                throw new StorableFormatException("Byte order is not compatible: '" + boStr + "'");
            }
            sInt = c.readU8();
            sLong = c.readU8();
            sPtr = c.readU8();
            // sizeof(NV) only present in major>=2 minor>=2 per magic_check
            // (check is `version_major >= 2 && version_minor >= 2`).
            if (major >= 2 && minor >= 2) {
                sNV = c.readU8();
            }
            c.setFileBigEndian(be);
            c.setSizeofIV(sLong);   // best approximation: IV ~ long on legacy perls
            c.setSizeofNV(sNV);
        } else {
            // Network order: integers are big-endian; doubles are NOT
            // byte-swapped (kept native — see retrieve_double). We have
            // no signal in netorder for the producer's NV format, so we
            // default to 8-byte little-endian-on-our-side and hope the
            // producer matches (Storable explicitly does not promise
            // double portability across architectures even in netorder).
            c.setFileBigEndian(true);
        }

        return new HeaderInfo(major, minor, netorder, be, sInt, sLong, sPtr, sNV);
    }

    /** Parse an in-memory (freeze/nfreeze) header — no {@code pst0}. */
    public static HeaderInfo parseInMemory(StorableContext c) {
        int useNetorderByte = c.readU8();
        int major = useNetorderByte >> 1;
        boolean netorder = (useNetorderByte & 0x1) != 0;
        int minor = 0;
        if (major > 1) minor = c.readU8();

        if (major > Opcodes.STORABLE_BIN_MAJOR
                || (major == Opcodes.STORABLE_BIN_MAJOR && minor > Opcodes.STORABLE_BIN_MINOR)) {
            throw new StorableFormatException(String.format(
                    "Storable binary image v%d.%d more recent than I am (v%d.%d)",
                    major, minor, Opcodes.STORABLE_BIN_MAJOR, Opcodes.STORABLE_BIN_MINOR));
        }
        c.setVersion(major, minor);
        c.setNetorder(netorder);
        c.setFileBigEndian(true);  // freeze() doesn't include byteorder; assume host
        return new HeaderInfo(major, minor, netorder, true, 0, 0, 0, 8);
    }
}

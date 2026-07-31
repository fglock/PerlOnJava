package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Java XS implementation of File::Checksum's portable 16-bit checksum. */
public class FileChecksum extends PerlModuleBase {

    public static final String XS_VERSION = "0.01";

    public FileChecksum() {
        super("File::Checksum", false);
    }

    public static void initialize() {
        FileChecksum module = new FileChecksum();
        module.defineExport("EXPORT", "Checksum");
        try {
            module.registerMethod("Checksum", null);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    /** Matches Checksum.xs: native-endian 16-bit words, folded and complemented. */
    public static RuntimeList Checksum(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return new RuntimeScalar(0).getList();
        }

        long sum = 0;
        int count = args.get(1).getInt();
        try {
            byte[] bytes = Files.readAllBytes(Path.of(args.get(0).toString()));
            int limit = Math.min(Math.max(count, 0), bytes.length);
            // PerlOnJava runs on the supported little-endian platforms. The XS
            // implementation reads an unsigned short directly from FILE*.
            for (int i = 0; i + 1 < limit; i += 2) {
                sum += (bytes[i] & 0xffL) | ((bytes[i + 1] & 0xffL) << 8);
            }
        } catch (IOException | RuntimeException ignored) {
            return new RuntimeScalar(0).getList();
        }

        sum = (sum >>> 16) + (sum & 0xffff);
        sum += sum >>> 16;
        return new RuntimeScalar((~sum) & 0xffff).getList();
    }
}

package org.perlonjava.runtime.perlmodule;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ArchiveZipUnixPermissionsTest {

    @TempDir
    Path tempDir;

    @Tag("unit")
    @Test
    void extractionPreservesExecutableBitFromUnixZipEntry() throws Exception {
        assumeTrue(Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null,
                "Unix permission bits require a POSIX filesystem");

        Path archivePath = tempDir.resolve("ninja.zip");
        Files.write(archivePath, executableZip("ninja", "binary".getBytes()));

        PerlRuntime runtime = new PerlRuntime().initialize();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray newArgs = new RuntimeArray();
            newArgs.elements.add(new RuntimeScalar("Archive::Zip"));
            RuntimeScalar archiveRef = (RuntimeScalar) ArchiveZip.newArchive(newArgs, 0)
                    .elements.getFirst();
            RuntimeArray readArgs = new RuntimeArray();
            readArgs.elements.add(archiveRef);
            readArgs.elements.add(new RuntimeScalar(archivePath.toString()));
            assertEquals(ArchiveZip.AZ_OK,
                    ArchiveZip.read(readArgs, 0).scalar().getInt(), "ZIP archive reads");

            Path extracted = tempDir.resolve("out/ninja");
            RuntimeArray extractArgs = new RuntimeArray();
            extractArgs.elements.add(archiveRef);
            extractArgs.elements.add(new RuntimeScalar("ninja"));
            extractArgs.elements.add(new RuntimeScalar(extracted.toString()));
            assertEquals(ArchiveZip.AZ_OK,
                    ArchiveZip.extractMember(extractArgs, 0).scalar().getInt(),
                    "ZIP member extracts");

            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(extracted);
            assertTrue(permissions.contains(PosixFilePermission.OWNER_EXECUTE));
            assertTrue(permissions.contains(PosixFilePermission.GROUP_EXECUTE));
            assertTrue(permissions.contains(PosixFilePermission.OTHERS_EXECUTE));
            assertEquals("binary", Files.readString(extracted));
        }

    }

    private static byte[] executableZip(String name, byte[] contents) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(contents);
            zip.closeEntry();
        }

        byte[] result = bytes.toByteArray();
        int centralDirectory = findSignature(result, 0x02014b50);
        if (centralDirectory < 0) throw new AssertionError("ZIP central directory not found");

        // Mark this entry as Unix-created and set its external mode to 100755.
        result[centralDirectory + 5] = 3;
        long externalAttributes = 0100755L << 16;
        for (int i = 0; i < 4; i++) {
            result[centralDirectory + 38 + i] = (byte) (externalAttributes >>> (8 * i));
        }
        return result;
    }

    private static int findSignature(byte[] bytes, int signature) {
        for (int i = 0; i <= bytes.length - 4; i++) {
            int found = (bytes[i] & 0xff)
                    | ((bytes[i + 1] & 0xff) << 8)
                    | ((bytes[i + 2] & 0xff) << 16)
                    | ((bytes[i + 3] & 0xff) << 24);
            if (found == signature) return i;
        }
        return -1;
    }
}

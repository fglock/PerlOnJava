package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.operators.IOOperator;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class SysopenAnonymousGlobOwnerSlotTest {
    @Test
    void newlyAssignedAnonymousHandleGetsOneScalarResourceOwner() throws Exception {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            Path path = Files.createTempFile("perlonjava-sysopen-anonymous-owner-", ".tmp");
            RuntimeScalar handle = new RuntimeScalar();
            RuntimeGlob glob = null;
            try {
                RuntimeScalar result = IOOperator.sysopen(0,
                        handle,
                        new RuntimeScalar(path.toString()),
                        new RuntimeScalar(2)); // O_RDWR

                assertTrue(result.getBoolean());
                assertEquals(RuntimeScalarType.GLOBREFERENCE, handle.type);
                glob = (RuntimeGlob) handle.value;
                assertTrue(handle.hasIoOwner());
                assertEquals(1, glob.ioHolderCount());
                assertEquals(1, glob.ownerSlotCount(PerlOwnerSlot.Kind.IO_RESOURCE_HOLDER));

                RuntimeScalar.releaseIoOwner(handle);
                assertEquals(0, glob.ioHolderCount());
            } finally {
                if (glob != null && glob.getIO().value instanceof RuntimeIO io) io.close();
                RuntimeScalar.releaseIoOwner(handle);
                Files.deleteIfExists(path);
            }
        }
    }
}

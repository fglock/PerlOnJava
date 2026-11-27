package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.perlonjava.runtime.io.CustomFileChannel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeGraphClonerIoOwnerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void clonedScalarReacquiresAnonymousIoHolderInChildRuntime() throws Exception {
        Path path = temporaryDirectory.resolve("anonymous-handle.txt");
        Files.writeString(path, "payload");
        PerlRuntime sourceRuntime = new PerlRuntime();
        PerlRuntime targetRuntime = new PerlRuntime();
        RuntimeIO sourceIO = new RuntimeIO(new CustomFileChannel(path,
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)));
        RuntimeGlob sourceGlob = new RuntimeGlob(null).setIO(sourceIO);
        RuntimeScalar sourceScalar = globReference(sourceGlob);
        try (PerlRuntime.Binding ignored = sourceRuntime.bind()) {
            RuntimeScalar.retainUnstashedIoForDurableSlot(sourceScalar);
            assertEquals(1, sourceGlob.ioHolderCount());
        }

        RuntimeScalar targetScalar = (RuntimeScalar) new RuntimeGraphCloner(
                sourceRuntime, targetRuntime).cloneGraph(sourceScalar);
        RuntimeGlob targetGlob = (RuntimeGlob) targetScalar.value;
        RuntimeIO targetIO = (RuntimeIO) targetGlob.getIO().value;

        try (PerlRuntime.Binding ignored = targetRuntime.bind()) {
            assertNotSame(sourceGlob, targetGlob);
            assertNotSame(sourceIO, targetIO);
            assertTrue(targetScalar.hasIoOwner(),
                    "the cloned scalar needs its own runtime-local IO holder");
            assertSame(targetGlob, targetScalar.ioOwnerSlot.referent());
            assertEquals(1, targetGlob.ioHolderCount());
            RuntimeScalar.releaseIoOwner(targetScalar);
            assertEquals(0, targetGlob.ioHolderCount());
            assertEquals(1, sourceGlob.ioHolderCount(),
                    "releasing the child lease must preserve the parent lease");
            targetIO.close();
        } finally {
            try (PerlRuntime.Binding ignored = sourceRuntime.bind()) {
                RuntimeScalar.releaseIoOwner(sourceScalar);
                sourceIO.close();
            }
        }
    }

    private static RuntimeScalar globReference(RuntimeGlob glob) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.GLOBREFERENCE;
        scalar.value = glob;
        return scalar;
    }
}

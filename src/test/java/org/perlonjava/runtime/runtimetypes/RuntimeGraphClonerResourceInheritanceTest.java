package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.perlonjava.runtime.io.CustomFileChannel;
import org.perlonjava.runtime.io.LayeredIOHandle;
import org.perlonjava.runtime.io.ScalarBackedIO;
import org.perlonjava.runtime.io.SharedTransportIOHandle;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeGraphClonerResourceInheritanceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void fileTransportSharesPositionAndClosesAfterLastRuntimeLease() throws Exception {
        Path path = temporaryDirectory.resolve("shared-position.txt");
        Files.writeString(path, "abcdef", StandardCharsets.ISO_8859_1);
        CustomFileChannel channel = new CustomFileChannel(path, Set.of(
                StandardOpenOption.READ, StandardOpenOption.WRITE));
        RuntimeIO parentIO = new RuntimeIO(channel);
        RuntimeScalar first = new RuntimeScalar(parentIO);
        RuntimeScalar alias = new RuntimeScalar(parentIO);

        PerlRuntime parent = new PerlRuntime();
        PerlRuntime child = new PerlRuntime();
        List<RuntimeBase> roots = new RuntimeGraphCloner(parent, child)
                .cloneRoots(List.of(first, alias));
        RuntimeIO childIO = (RuntimeIO) ((RuntimeScalar) roots.get(0)).value;

        assertSame(childIO, ((RuntimeScalar) roots.get(1)).value);
        assertNotSame(parentIO, childIO);
        assertTrue(parentIO.ioHandle instanceof SharedTransportIOHandle);
        assertEquals("ab", childIO.ioHandle.doRead(2, StandardCharsets.ISO_8859_1).toString());
        try (PerlRuntime.Binding ignored = child.bind()) {
            assertTrue(childIO.close().getBoolean());
        }
        assertEquals("cd", parentIO.ioHandle.doRead(2, StandardCharsets.ISO_8859_1).toString());
        try (PerlRuntime.Binding ignored = parent.bind()) {
            assertTrue(parentIO.close().getBoolean());
        }
    }

    @Test
    void layeredHandlesGetIndependentWrappersOverOneTransport() throws Exception {
        Path path = temporaryDirectory.resolve("layers.txt");
        Files.writeString(path, "line\n", StandardCharsets.UTF_8);
        PerlRuntime parent = new PerlRuntime();
        PerlRuntime child = new PerlRuntime();
        LayeredIOHandle parentLayer = new LayeredIOHandle(new CustomFileChannel(path,
                Set.of(StandardOpenOption.READ)));
        try (PerlRuntime.Binding ignored = parent.bind()) {
            parentLayer.binmode(":encoding(UTF-8)");
        }
        RuntimeIO parentIO = new RuntimeIO(parentLayer);

        RuntimeScalar cloned = new RuntimeGraphCloner(parent, child)
                .cloneGraph(new RuntimeScalar(parentIO));
        RuntimeIO childIO = (RuntimeIO) cloned.value;
        LayeredIOHandle childLayer = (LayeredIOHandle) childIO.ioHandle;

        assertNotSame(parentIO.ioHandle, childLayer);
        assertEquals(parentLayer.getCurrentLayers(), childLayer.getCurrentLayers());
        try (PerlRuntime.Binding ignored = child.bind()) {
            assertTrue(childIO.close().getBoolean());
        }
        assertEquals("line\n", parentIO.ioHandle.doRead(16, StandardCharsets.UTF_8).toString());
        try (PerlRuntime.Binding ignored = parent.bind()) {
            parentIO.close();
        }
    }

    @Test
    void scalarBackedHandleClonesBackingAliasAndPosition() {
        RuntimeScalar backing = new RuntimeScalar("wxyz");
        ScalarBackedIO scalarHandle = new ScalarBackedIO(backing);
        scalarHandle.doRead(1, StandardCharsets.ISO_8859_1);
        RuntimeIO parentIO = new RuntimeIO(scalarHandle);

        List<RuntimeBase> roots = new RuntimeGraphCloner(new PerlRuntime(), new PerlRuntime())
                .cloneRoots(List.of(backing, new RuntimeScalar(parentIO)));
        RuntimeScalar childBacking = (RuntimeScalar) roots.get(0);
        RuntimeIO childIO = (RuntimeIO) ((RuntimeScalar) roots.get(1)).value;

        assertSame(childBacking, ((ScalarBackedIO) childIO.ioHandle).backingScalar());
        assertEquals("x", childIO.ioHandle.doRead(1, StandardCharsets.ISO_8859_1).toString());
        assertEquals("x", parentIO.ioHandle.doRead(1, StandardCharsets.ISO_8859_1).toString());
    }
}

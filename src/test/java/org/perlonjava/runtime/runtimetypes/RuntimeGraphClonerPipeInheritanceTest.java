package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.io.InternalPipeHandle;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeGraphClonerPipeInheritanceTest {
    @Test
    void internalPipeEndpointsCrossSnapshotWithIndependentCloseLeases() throws Exception {
        PerlRuntime parent = new PerlRuntime();
        PerlRuntime child = new PerlRuntime();
        PipedInputStream input = new PipedInputStream(InternalPipeHandle.PIPE_SIZE);
        PipedOutputStream output = new PipedOutputStream(input);
        InternalPipeHandle[] pair = InternalPipeHandle.createPair(input, output);
        RuntimeIO parentReader = new RuntimeIO(pair[0]);
        RuntimeIO parentWriter = new RuntimeIO(pair[1]);

        RuntimeGraphCloner cloner = new RuntimeGraphCloner(parent, child);
        List<RuntimeBase> roots = cloner.cloneRoots(List.of(
                new RuntimeScalar(parentReader), new RuntimeScalar(parentWriter)));
        RuntimeIO childReader = (RuntimeIO) ((RuntimeScalar) roots.get(0)).value;
        RuntimeIO childWriter = (RuntimeIO) ((RuntimeScalar) roots.get(1)).value;

        assertNotSame(parentReader, childReader);
        assertNotSame(parentWriter, childWriter);
        try (PerlRuntime.Binding ignored = child.bind()) {
            assertTrue(childWriter.close().getBoolean());
        }

        try (PerlRuntime.Binding ignored = parent.bind()) {
            assertTrue(parentWriter.write("from parent\n").getBoolean());
        }
        try (PerlRuntime.Binding ignored = child.bind()) {
            assertEquals("from parent\n", childReader.ioHandle.doRead(64,
                    java.nio.charset.StandardCharsets.UTF_8).toString());
            assertTrue(childReader.close().getBoolean());
        }

        try (PerlRuntime.Binding ignored = parent.bind()) {
            assertTrue(parentWriter.write("still open\n").getBoolean());
            assertEquals("still open\n", parentReader.ioHandle.doRead(64,
                    java.nio.charset.StandardCharsets.UTF_8).toString());
            parentWriter.close();
            parentReader.close();
        }
    }
}

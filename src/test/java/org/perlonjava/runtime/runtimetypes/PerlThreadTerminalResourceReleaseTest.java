package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class PerlThreadTerminalResourceReleaseTest {
    @Test
    void joinedAliasRetainsMetadataWithoutRetainingChildRuntime() throws Exception {
        PerlRuntime parent = new PerlRuntime().initialize();
        try (PerlRuntime.Binding ignored = parent.bind()) {
            PerlThreadControlBlock thread = PerlThreadControlBlock.create(parent,
                    child -> new RuntimeScalar(7)).start();

            PerlThreadControlBlock.Completion completion = thread.join();
            assertEquals(7, ((RuntimeScalar) completion.value()).getInt());
            assertNotNull(thread.childRuntime());

            thread.releaseTerminalResources();

            assertNull(thread.childRuntime());
            assertSame(thread, parent.threadRegistry().getKnown(thread.id()));
            assertEquals(PerlThreadControlBlock.State.JOINED, thread.state());
            assertNull(thread.error());
        } finally {
            parent.close();
        }
    }

    @Test
    void detachedCompletionReleasesChildRuntimeAutomatically() throws Exception {
        PerlRuntime parent = new PerlRuntime().initialize();
        try (PerlRuntime.Binding ignored = parent.bind()) {
            PerlThreadControlBlock thread = PerlThreadControlBlock.create(parent,
                    child -> new RuntimeScalar(1)).start();
            thread.detach();
            thread.platformThread().join(TimeUnit.SECONDS.toMillis(5));

            assertFalse(thread.platformThread().isAlive());
            assertNull(thread.childRuntime());
            assertSame(thread, parent.threadRegistry().getKnown(thread.id()));
            assertEquals(PerlThreadControlBlock.State.DETACHED, thread.state());
        } finally {
            parent.close();
        }
    }
}

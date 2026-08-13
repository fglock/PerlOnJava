package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class PerlThreadApiPhase29Test {
    @Test
    void terminalControlBlockRemainsAvailableToExistingObjectAliases() throws Exception {
        PerlRuntime parent = new PerlRuntime().initialize();
        try (PerlRuntime.Binding ignored = parent.bind()) {
            PerlThreadControlBlock thread = PerlThreadControlBlock.create(parent,
                    child -> new RuntimeScalar(7)).start();
            assertEquals(7, ((RuntimeScalar) thread.join().value()).getInt());
            assertNull(parent.threadRegistry().get(thread.id()));
            assertSame(thread, parent.threadRegistry().getKnown(thread.id()));
            assertEquals(PerlThreadControlBlock.State.JOINED, thread.state());
        } finally {
            parent.close();
        }
    }

    @Test
    void virtualThreadsRejectAnUnfulfillableStackSizeRequest() {
        PerlThreadExecutionPolicy virtual = PerlThreadExecutionPolicy.resolve("virtual", null);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> virtual.unstarted(1, 1024 * 1024, () -> {}));
        assertTrue(failure.getMessage().contains("virtual threads"));
    }
}

package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class PerlThreadControlBlockTest {
    @Test
    void assignsUniqueIdsTracksParentAndCleansJoinedThreads() throws Exception {
        PerlRuntime parent = new PerlRuntime().initialize();
        try (PerlRuntime.Binding ignored = parent.bind()) {
            PerlThreadControlBlock first = PerlThreadControlBlock.create(parent,
                    child -> new RuntimeScalar(child.perlThreadId())).start();
            PerlThreadControlBlock second = PerlThreadControlBlock.create(parent,
                    child -> new RuntimeScalar(child.perlThreadId())).start();
            assertNotEquals(first.id(), second.id());
            assertEquals(0, first.parentId());
            assertEquals(first.id(), ((RuntimeScalar) first.join().value()).getLong());
            assertEquals(second.id(), ((RuntimeScalar) second.join().value()).getLong());
            assertEquals(0, parent.threadRegistry().size());
        } finally {
            parent.close();
        }
    }

    @Test
    void nestedThreadUsesOwningThreadAsParent() throws Exception {
        PerlRuntime parent = new PerlRuntime().initialize();
        try (PerlRuntime.Binding ignored = parent.bind()) {
            PerlThreadControlBlock outer = PerlThreadControlBlock.create(parent, child -> {
                PerlThreadControlBlock nested = PerlThreadControlBlock.create(child,
                        nestedRuntime -> new RuntimeScalar(nestedRuntime.perlThreadId())).start();
                assertEquals(child.perlThreadId(), nested.parentId());
                return nested.join().value();
            }).start();
            assertEquals(2, ((RuntimeScalar) outer.join().value()).getLong());
            assertEquals(0, parent.threadRegistry().size());
        } finally {
            parent.close();
        }
    }

    @Test
    void detachAndDoubleJoinTransitionsAreRejectedWithoutLeaks() throws Exception {
        PerlRuntime parent = new PerlRuntime().initialize();
        CountDownLatch release = new CountDownLatch(1);
        try (PerlRuntime.Binding ignored = parent.bind()) {
            PerlThreadControlBlock detached = PerlThreadControlBlock.create(parent, child -> {
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return new RuntimeScalar(1);
            }).start();
            detached.detach();
            assertThrows(IllegalStateException.class, detached::join);
            release.countDown();
            detached.platformThread().join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(detached.platformThread().isAlive());

            PerlThreadControlBlock joined = PerlThreadControlBlock.create(parent,
                    child -> new RuntimeScalar(2)).start();
            assertEquals(2, ((RuntimeScalar) joined.join().value()).getInt());
            assertThrows(IllegalStateException.class, joined::join);
            assertThrows(IllegalStateException.class, joined::detach);
            assertEquals(0, parent.threadRegistry().size());
        } finally {
            release.countDown();
            parent.close();
        }
    }

    @Test
    void retainsUncaughtFailureUntilJoin() throws Exception {
        PerlRuntime parent = new PerlRuntime().initialize();
        try (PerlRuntime.Binding ignored = parent.bind()) {
            PerlThreadControlBlock thread = PerlThreadControlBlock.create(parent,
                    child -> { throw new IllegalArgumentException("boom"); }).start();
            PerlThreadControlBlock.Completion completion = thread.join();
            assertNull(completion.value());
            assertInstanceOf(IllegalArgumentException.class, completion.error());
            assertEquals("boom", completion.error().getMessage());
        } finally {
            parent.close();
        }
    }
}

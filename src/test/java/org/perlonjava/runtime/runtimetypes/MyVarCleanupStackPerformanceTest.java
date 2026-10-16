package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class MyVarCleanupStackPerformanceTest {
    @Test
    void unregisteringManyContainerLexicalsDoesNotRescanTheRemainingStack() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            int initialMark = MyVarCleanupStack.pushMark();
            int lexicalCount = 300_000;
            RuntimeBase[] lexicals = new RuntimeBase[lexicalCount];
            for (int i = 0; i < lexicalCount; i++) {
                RuntimeBase lexical = new RuntimeArray();
                lexicals[i] = lexical;
                MyVarCleanupStack.register(lexical);
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            for (int i = lexicalCount - 1; i >= 0; i--) {
                MyVarCleanupStack.unregister(lexicals[i]);
                if ((i & 0xff) == 0 && System.nanoTime() > deadline) {
                    fail("scalar scope cleanup rescanned the remaining lexical stack");
                }
            }

            assertEquals(initialMark, MyVarCleanupStack.pushMark(),
                    "all scalar lexical registrations should be removed");
        }
    }

    @Test
    void registrationCountsTrackDuplicateSlotsAndReplacement() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            int mark = MyVarCleanupStack.pushMark();
            RuntimeArray original = new RuntimeArray();
            MyVarCleanupStack.register(original);
            MyVarCleanupStack.register(original);

            assertTrue(MyVarCleanupStack.isRegistered(original));
            MyVarCleanupStack.unregister(original);
            assertTrue(MyVarCleanupStack.isRegistered(original),
                    "one nested registration must remain live");
            MyVarCleanupStack.unregister(original);
            assertFalse(MyVarCleanupStack.isRegistered(original));

            RuntimeArray replaced = new RuntimeArray();
            MyVarCleanupStack.register(original);
            MyVarCleanupStack.replace(original, replaced);
            assertFalse(MyVarCleanupStack.isRegistered(original));
            assertTrue(MyVarCleanupStack.isRegistered(replaced));

            MyVarCleanupStack.popMark(mark);
            assertFalse(MyVarCleanupStack.isRegistered(replaced));
        }
    }
}

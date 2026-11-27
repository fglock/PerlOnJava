package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class GrowingLexicalOutputOwnerSlotCostTest {
    @Test
    void nestedOutputCleanupDoesNotRescanTheGrowingLexicalRoot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            final int resultCount = 256;
            RuntimeArray output = new RuntimeArray();
            RuntimeArray[] records = new RuntimeArray[resultCount];
            RuntimeScalar[] weakLeaves = new RuntimeScalar[resultCount];
            MyVarCleanupStack.register(output);

            try {
                for (int i = 0; i < resultCount; i++) {
                    RuntimeArray leaf = new RuntimeArray();
                    RuntimeScalar leafOwner = leaf.createAnonymousReference();
                    RuntimeScalar weakLeaf = leaf.createAnonymousReference();
                    leaf.markRefCountUntracked();

                    RuntimeArray record = new RuntimeArray();
                    record.elements.add(leafOwner);
                    RuntimeScalar recordOwner = record.createAnonymousReference();
                    record.markRefCountUntracked();
                    output.elements.add(recordOwner);

                    WeakRefRegistry.weaken(weakLeaf);
                    records[i] = record;
                    weakLeaves[i] = weakLeaf;
                }

                {
                    for (RuntimeArray record : records) {
                        // Model a recursive callback frame ending after its
                        // result has been appended to the live output array.
                        MortalList.scopeExitCleanupArray(record);
                    }

                    assertEquals(resultCount, output.size());
                    for (RuntimeScalar weakLeaf : weakLeaves) {
                        assertTrue(WeakRefRegistry.isweak(weakLeaf),
                                "a live output row keeps its nested aggregate alive");
                    }
                }

                for (int i = resultCount - 1; i >= 0; i--) {
                    RuntimeScalar rootOwner = output.elements.removeLast();
                    rootOwner.undefine();
                    MortalList.scopeExitCleanupArray(records[i]);
                    MortalList.flush();
                    assertFalse(WeakRefRegistry.isweak(weakLeaves[i]),
                            "the nested weak observer clears after its final output owner leaves");
                }
            } finally {
                for (RuntimeScalar weakLeaf : weakLeaves) {
                    if (weakLeaf != null) WeakRefRegistry.unweaken(weakLeaf);
                }
                MyVarCleanupStack.unregister(output);
                MortalList.scopeExitCleanupArray(output);
            }
        }
    }
}

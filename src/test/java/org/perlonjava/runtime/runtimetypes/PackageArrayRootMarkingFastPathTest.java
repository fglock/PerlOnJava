package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class PackageArrayRootMarkingFastPathTest {
    private static final class CountingElements extends ArrayList<RuntimeScalar> {
        private int snapshots;

        @Override
        public <T> T[] toArray(T[] target) {
            snapshots++;
            return super.toArray(target);
        }
    }

    @Test
    void repeatedGlobalArrayLookupDoesNotRescanPrepopulatedSlots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String name = "PackageArrayRootMarkingFastPathTest::values";
            RuntimeArray array = new RuntimeArray();
            CountingElements elements = new CountingElements();
            elements.add(new RuntimeScalar("prepopulated"));
            array.elements = elements;

            try {
                GlobalVariable.globalArrays.put(name, array);
                int snapshotsAfterRooting = elements.snapshots;
                assertEquals(1, snapshotsAfterRooting,
                        "initial package installation marks prepopulated slots");

                for (int i = 0; i < 4; i++) {
                    assertEquals(array, GlobalVariable.getGlobalArray(name));
                }

                assertEquals(snapshotsAfterRooting, elements.snapshots,
                        "reading an already-rooted array does not snapshot its slots again");
            } finally {
                GlobalVariable.globalArrays.remove(name);
            }
        }
    }
}

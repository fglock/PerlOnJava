package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("unit")
class PackageGlobOwnerSlotTest {
    @Test
    void replacementAndIteratorRemovalTransferAndReleaseTheNamedGlobOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String name = "PackageGlobOwnerSlotTest::FH";
            RuntimeGlob previous = new RuntimeGlob(name);
            RuntimeGlob replacement = new RuntimeGlob(name);

            GlobalVariable.globalIORefs.put(name, previous);
            assertEquals(1, previous.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_GLOB_SLOT));

            GlobalVariable.globalIORefs.put(name, replacement);
            assertEquals(0, previous.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_GLOB_SLOT));
            assertEquals(1, replacement.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_GLOB_SLOT));

            Iterator<Map.Entry<String, RuntimeGlob>> entries =
                    GlobalVariable.globalIORefs.entrySet().iterator();
            while (entries.hasNext()) {
                if (name.equals(entries.next().getKey())) {
                    entries.remove();
                    break;
                }
            }

            assertNull(GlobalVariable.globalIORefs.get(name));
            assertEquals(0, replacement.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_GLOB_SLOT));
        }
    }

    @Test
    void clearReleasesEveryNamedGlobOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeGlob first = new RuntimeGlob("PackageGlobOwnerSlotTest::FIRST");
            RuntimeGlob second = new RuntimeGlob("PackageGlobOwnerSlotTest::SECOND");
            GlobalVariable.globalIORefs.put(first.globName, first);
            GlobalVariable.globalIORefs.put(second.globName, second);

            GlobalVariable.globalIORefs.clear();

            assertEquals(0, first.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_GLOB_SLOT));
            assertEquals(0, second.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_GLOB_SLOT));
        }
    }
}

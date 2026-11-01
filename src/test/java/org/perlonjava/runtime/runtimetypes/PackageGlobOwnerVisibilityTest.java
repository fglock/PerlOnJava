package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class PackageGlobOwnerVisibilityTest {
    @Test
    void hiddenStashEntryReleasesItsOwnerAndVisibleEntryReacquiresIt() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String name = "PackageGlobOwnerVisibilityTest::FH";
            RuntimeGlob original = new RuntimeGlob(name);
            RuntimeGlob replacement = new RuntimeGlob(name);

            GlobalVariable.globalIORefs.put(name, original);
            assertEquals(1, original.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_GLOB_SLOT));

            GlobalVariable.hideIORefAfterStashDelete(name);
            assertEquals(0, original.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_GLOB_SLOT));

            GlobalVariable.globalIORefs.put(name, replacement);
            assertEquals(0, replacement.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_GLOB_SLOT));

            GlobalVariable.markStashEntryVisible(name);
            assertEquals(0, original.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_GLOB_SLOT));
            assertEquals(1, replacement.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_GLOB_SLOT));
        }
    }
}

package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class LocalizedHashMissingKeyOwnerReleaseTest {
    @Test
    void restoringAnAbsentHashKeyRetiresItsCountedSlotExactlyOnce() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousActive = MortalList.isActive();
            MortalList.setActive(true);

            RuntimeArray referent = new RuntimeArray();
            referent.refCount = 0;
            RuntimeScalar externalOwner = new RuntimeScalar();
            RuntimeHash parent = new RuntimeHash();
            RuntimeHashProxyEntry proxy = new RuntimeHashProxyEntry(parent, "temporary");

            try {
                externalOwner.set(referent.createAnonymousReference());
                proxy.dynamicSaveState();
                proxy.set(referent.createAnonymousReference());

                RuntimeScalar localizedCell = parent.elements.get("temporary");
                assertNotNull(localizedCell);
                assertTrue(localizedCell.refCountOwned);
                PerlOwnerSlot localizedOwner = localizedCell.countedOwnerSlot();
                assertNotNull(localizedOwner);
                assertEquals(PerlOwnerSlot.Kind.HASH_ELEMENT, localizedOwner.kind());
                assertEquals(2, referent.refCount);

                proxy.dynamicRestoreState();

                assertFalse(parent.elements.containsKey("temporary"));
                assertFalse(localizedCell.refCountOwned,
                        "removing the localized key retires the removed value cell's edge");
                assertFalse(localizedOwner.isActive(),
                        "the removed HASH_ELEMENT identity cannot outlive its storage slot");
                assertEquals(1, referent.refCount,
                        "only the external scalar cell still owns the referent");
            } finally {
                if (parent.elements.containsKey("temporary")) proxy.dynamicRestoreState();
                externalOwner.undefine();
                MortalList.flush();
                MortalList.setActive(previousActive);
            }
        }
    }
}

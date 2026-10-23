package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class MortalListAggregateOwnerSlotTest {
    @Test
    void rootedHashEntrySlotRestoresAnUnderflowedLegacyCountUntilEntryRemoval() {
        PerlRuntime runtime = new PerlRuntime();
        String className = "MortalListAggregateOwnerSlotTest::Tracked";
        String rootName = "MortalListAggregateOwnerSlotTest::root";
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousActive = MortalList.isActive();
            RuntimeHash referent = new RuntimeHash();
            referent.blessId = NameNormalizer.getBlessId(className);
            referent.refCount = 1;
            installDestroy(className);

            RuntimeScalar hashEntry = referenceTo(referent);
            hashEntry.refCountOwned = true;
            RuntimeHash root = new RuntimeHash();
            root.elements.put("value", hashEntry);
            GlobalVariable.globalHashes.put(rootName, root);

            RuntimeScalar lexicalOwner = referenceTo(referent);
            lexicalOwner.refCountOwned = true;
            referent.activateOwnerTracking();
            referent.recordActiveOwner(hashEntry);
            referent.recordActiveOwner(lexicalOwner);
            MyVarCleanupStack.register(lexicalOwner);

            RuntimeScalar unrelatedLegacyOwner = referenceTo(referent);
            unrelatedLegacyOwner.refCountOwned = true;
            // Model the legacy selective count having missed this transient
            // owner while the independently tracked package-rooted hash slot
            // remains active.
            referent.refCount = 1;

            try {
                MortalList.setActive(true);
                MortalList.deferDecrementIfTracked(unrelatedLegacyOwner);
                MortalList.flush();

                assertEquals(RuntimeBase.PerlLifecycleState.LIVE,
                        referent.perlLifecycleState(),
                        "the rooted hash entry keeps the referent alive at legacy count zero");
                assertEquals(2, referent.refCount,
                        "the active hash slot and separate lexical owner are both restored");

                RuntimeScalar removed = root.elements.remove("value");
                MortalList.deferDecrementIfTracked(removed);
                MortalList.flush();

                assertEquals(RuntimeBase.PerlLifecycleState.LIVE,
                        referent.perlLifecycleState(),
                        "removing the hash entry preserves the separate lexical owner");
                assertEquals(1, referent.refCount);

                MortalList.deferDecrementIfTracked(lexicalOwner);
                MortalList.flush();

                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        referent.perlLifecycleState(),
                        "releasing the final lexical owner permits destruction");
            } finally {
                MortalList.setActive(previousActive);
                MyVarCleanupStack.unregister(lexicalOwner);
                GlobalVariable.globalHashes.remove(rootName);
                GlobalVariable.removeGlobalCodeRefForStashDelete(className + "::DESTROY");
            }
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.HASHREFERENCE;
        scalar.value = referent;
        return scalar;
    }

    private static void installDestroy(String className) {
        RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeScalar().getList(), null);
        int separator = className.lastIndexOf("::");
        code.packageName = className.substring(0, separator);
        code.subName = "DESTROY";
        GlobalVariable.getGlobalCodeRef(className + "::DESTROY").set(new RuntimeScalar(code));
    }
}

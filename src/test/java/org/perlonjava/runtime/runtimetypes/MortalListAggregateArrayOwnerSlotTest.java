package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class MortalListAggregateArrayOwnerSlotTest {
    @Test
    void directlyRootedArrayElementRestoresItsStrongOwnerAtLegacyZero() {
        PerlRuntime runtime = new PerlRuntime();
        String className = "MortalListAggregateArrayOwnerSlotTest::Tracked";
        String rootName = "MortalListAggregateArrayOwnerSlotTest::root";
        String weakRootName = "MortalListAggregateArrayOwnerSlotTest::weak_root";
        String weakHashName = "MortalListAggregateArrayOwnerSlotTest::weak_hash";
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousActive = MortalList.isActive();
            RuntimeHash referent = new RuntimeHash();
            referent.blessId = NameNormalizer.getBlessId(className);
            referent.refCount = 1;
            installDestroy(className);

            RuntimeArray root = new RuntimeArray();
            root.push(referenceTo(referent));
            GlobalVariable.aliasGlobalArray(rootName, root);
            RuntimeArray lexicalRoot = new RuntimeArray();
            lexicalRoot.push(referenceTo(referent));
            MyVarCleanupStack.register(lexicalRoot);
            RuntimeArray weakRoot = new RuntimeArray();
            weakRoot.push(referenceTo(referent));
            RuntimeScalar weakEntry = weakRoot.elements.get(0);
            WeakRefRegistry.weaken(weakEntry);
            GlobalVariable.aliasGlobalArray(weakRootName, weakRoot);
            RuntimeHash weakHash = new RuntimeHash();
            RuntimeScalar weakHashEntry = referenceTo(referent);
            weakHash.elements.put("value", weakHashEntry);
            WeakRefRegistry.weaken(weakHashEntry);
            GlobalVariable.aliasGlobalHash(weakHashName, weakHash);

            RuntimeScalar lexicalOwner = referenceTo(referent);
            lexicalOwner.refCountOwned = true;
            referent.activateOwnerTracking();
            referent.recordActiveOwner(lexicalOwner);
            MyVarCleanupStack.register(lexicalOwner);

            RuntimeScalar unrelatedLegacyOwner = referenceTo(referent);
            unrelatedLegacyOwner.refCountOwned = true;
            referent.refCount = 1;

            try {
                MortalList.setActive(true);
                MortalList.deferDecrementIfTracked(unrelatedLegacyOwner);
                MortalList.flush();

                assertEquals(RuntimeBase.PerlLifecycleState.LIVE,
                        referent.perlLifecycleState(),
                        "a package-rooted strong array element retains its referent");
                assertEquals(3, referent.refCount,
                        "both rooted strong array elements and the lexical owner are restored");

                // Keep the weak edge in place for the count assertion, then
                // remove its diagnostic root before proving final destruction.
                weakRoot.elements.clear();
                GlobalVariable.globalArrays.remove(weakRootName);
                weakHash.elements.clear();
                GlobalVariable.globalHashes.remove(weakHashName);
                WeakRefRegistry.clearWeakRefsTo(referent);

                RuntimeScalar removed = root.elements.remove(0);
                MortalList.deferDecrementIfTracked(removed);
                MortalList.flush();

                assertEquals(RuntimeBase.PerlLifecycleState.LIVE,
                        referent.perlLifecycleState());
                assertEquals(2, referent.refCount,
                        "the registered lexical array and scalar owner remain");

                RuntimeScalar lexicalArrayEntry = lexicalRoot.elements.remove(0);
                MortalList.deferDecrementIfTracked(lexicalArrayEntry);
                MortalList.flush();
                assertEquals(1, referent.refCount,
                        "removing the lexical array element preserves the scalar owner");

                MortalList.deferDecrementIfTracked(lexicalOwner);
                MortalList.flush();

                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        referent.perlLifecycleState(),
                        "releasing the final owner permits destruction");
            } finally {
                MortalList.setActive(previousActive);
                MyVarCleanupStack.unregister(lexicalOwner);
                MyVarCleanupStack.unregister(lexicalRoot);
                GlobalVariable.globalArrays.remove(rootName);
                GlobalVariable.globalArrays.remove(weakRootName);
                GlobalVariable.globalHashes.remove(weakHashName);
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

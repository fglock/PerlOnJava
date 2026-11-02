package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class StaleStashPackageOwnerReleaseTest {
    @Test
    void rebindingStashReleasesItsPackageRootIdentity() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String source = "StaleStashPackageOwnerReleaseTest::Source::";
            String replacement = "StaleStashPackageOwnerReleaseTest::Other::";
            RuntimeStash stale = (RuntimeStash) GlobalVariable.getGlobalHash(source);
            RuntimeStash current = (RuntimeStash) GlobalVariable.getGlobalHash(replacement);
            String childKey = source + "Inner::";
            RuntimeStash child = (RuntimeStash) GlobalVariable.getGlobalHash(childKey);
            String scalarKey = source + "item";
            RuntimeScalar packageScalar = new RuntimeScalar();
            RuntimeHash value = new RuntimeHash();
            RuntimeScalar weakValue = value.createAnonymousReference();
            WeakRefRegistry.weaken(weakValue);
            packageScalar.set(value.createAnonymousReference());
            GlobalVariable.globalVariables.put(scalarKey, packageScalar);
            RuntimeScalar stashReference = stale.createReference();
            RuntimeScalar lexical = new RuntimeScalar();

            try {
                lexical.set(stashReference);
                assertEquals(1, stale.ownerSlotCount(
                        PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT));

                GlobalVariable.getGlobalIO(source).set(new RuntimeScalar(replacement));
                RuntimeGlob savedMove = stale.savedNamespaceMove;

                assertSame(current, GlobalVariable.globalHashes.get(source));
                assertNotSame(stale, current);
                assertNotNull(savedMove);
                assertEquals(1, savedMove.ownerSlotCount(
                        PerlOwnerSlot.Kind.STASH_NAMESPACE_MOVE));
                assertEquals(1, child.ownerSlotCount(
                        PerlOwnerSlot.Kind.GLOB_COMPONENT_SLOT));
                assertEquals(0, packageScalar.ownerSlotCount(
                        PerlOwnerSlot.Kind.PACKAGE_SCALAR_SLOT));
                assertEquals(1, packageScalar.ownerSlotCount(
                        PerlOwnerSlot.Kind.GLOB_COMPONENT_SLOT));
                assertFalse(stale.localBindingExists);
                assertEquals(0, stale.ownerSlotCount(
                        PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT),
                        "the replaced package hash must release its root owner identity");

                lexical.undefine();

                assertEquals(Integer.MIN_VALUE, stale.refCount,
                        "the final Perl reference must retire the stale stash");
                assertEquals(0, savedMove.ownerSlotCount(
                        PerlOwnerSlot.Kind.STASH_NAMESPACE_MOVE));
                assertEquals(0, child.ownerSlotCount(
                        PerlOwnerSlot.Kind.GLOB_COMPONENT_SLOT));
                assertEquals(0, packageScalar.ownerSlotCount(
                        PerlOwnerSlot.Kind.GLOB_COMPONENT_SLOT));
                assertEquals(RuntimeScalarType.UNDEF, weakValue.type,
                        "releasing the saved move must clean its package scalar contents");
            } finally {
                lexical.undefine();
                GlobalVariable.globalHashes.remove(source);
                GlobalVariable.globalHashes.remove(replacement);
                GlobalVariable.globalHashes.remove(childKey);
                GlobalVariable.globalVariables.remove(scalarKey);
                GlobalVariable.globalIORefs.remove(source);
                GlobalVariable.globalIORefs.remove(replacement);
                GlobalVariable.clearStashAlias(source);
            }
        }
    }
}

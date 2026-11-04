package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ActiveLexicalScalarOwnerLifecycleTest {
    @Test
    void scalarOverwriteDefersZeroCountCleanupToTheLexicalOwnerIdentity() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            RuntimeCode.pushActiveCode(code);
            int mark = MyVarCleanupStack.pushMark();
            RuntimeScalar cell = new RuntimeScalar(42);
            cell.blessId = NameNormalizer.getBlessId(
                    "ActiveLexicalScalarOwnerLifecycleTest::Cell");
            RuntimeCode.registerCurrentActiveLexical("$cell", cell);
            MyVarCleanupStack.register(cell);

            RuntimeScalar objectOwner = new RuntimeScalar();
            RuntimeScalar weakOwner = new RuntimeScalar();
            RuntimeScalar weakReference = null;
            try {
                RuntimeScalar reference = cell.createReference();
                objectOwner.set(reference);
                weakOwner.set(reference);
                WeakRefRegistry.weaken(weakOwner);
                weakReference = weakOwner;

                assertEquals(1, cell.refCount);
                objectOwner.set(new RuntimeScalar());

                assertEquals(0, cell.refCount);
                assertTrue(cell.deferredFinalReleaseForUncountedOwners,
                        "zero-count cleanup waits on the active lexical owner identity");
                assertTrue(WeakRefRegistry.isweak(weakReference));

                RuntimeScalar.scopeExitCleanup(cell);
                MyVarCleanupStack.unregister(cell);

                assertEquals(RuntimeScalarType.UNDEF, weakReference.type);
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        cell.perlLifecycleState());
            } finally {
                MyVarCleanupStack.unregister(cell);
                MyVarCleanupStack.popMark(mark);
                RuntimeCode.popActiveCode(code);
                if (weakReference != null && WeakRefRegistry.isweak(weakReference)) {
                    WeakRefRegistry.unweaken(weakReference);
                }
            }
        }
    }

    @Test
    void lexicalPadRegistrationAfterReferenceCreationBackfillsTheOwnerIdentity() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            RuntimeCode.pushActiveCode(code);
            int mark = MyVarCleanupStack.pushMark();
            RuntimeScalar cell = new RuntimeScalar(42);
            try {
                cell.createReference();
                MyVarCleanupStack.register(cell);
                RuntimeCode.registerCurrentActiveLexical("$cell", cell);

                assertEquals(1, cell.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_LEXICAL),
                        "late pad binding backfills the scalar-cell owner identity");
            } finally {
                MyVarCleanupStack.unregister(cell);
                MyVarCleanupStack.popMark(mark);
                RuntimeCode.popActiveCode(code);
            }
        }
    }
}

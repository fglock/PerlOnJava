package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class ActiveLexicalScalarOwnerSlotTest {
    @Test
    void referencedScalarCellOwnsItsLexicalLifetimeUntilScopeExit() {
        Assumptions.assumeFalse(ReachabilityWalker.correctiveSweepsEnabled(),
                "this lifecycle regression requires corrective sweeps to be disabled");

        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            RuntimeCode.pushActiveCode(code);
            int mark = MyVarCleanupStack.pushMark();
            RuntimeScalar cell = new RuntimeScalar(42);
            cell.blessId = NameNormalizer.getBlessId("Issue1649::LexicalScalarCell");
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

                assertEquals(1, cell.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_LEXICAL),
                        "the registered lexical cell has an explicit owner identity");
                assertEquals(1, cell.refCount,
                        "weakening leaves only the strong scalar-cell reference counted");

                objectOwner.set(new RuntimeScalar());

                assertEquals(0, cell.refCount,
                        "releasing the counted edge does not fabricate a positive count");
                assertTrue(WeakRefRegistry.isweak(weakReference),
                        "the lexical owner keeps the weak observer live");
                assertEquals(RuntimeBase.PerlLifecycleState.LIVE,
                        cell.perlLifecycleState());

                RuntimeScalar.scopeExitCleanup(cell);
                MyVarCleanupStack.unregister(cell);

                assertEquals(0, cell.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_LEXICAL));
                assertEquals(RuntimeScalarType.UNDEF, weakReference.type,
                        "the final lexical owner release clears the weak observer");
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
}

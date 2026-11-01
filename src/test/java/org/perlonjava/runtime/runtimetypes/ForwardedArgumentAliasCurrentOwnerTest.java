package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ForwardedArgumentAliasCurrentOwnerTest {
    @Test
    void forwardedCellAliasMakesItsCurrentCountedOwnerVerifiable() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeScalar argumentCell = new RuntimeScalar();
            argumentCell.set(target.createAnonymousReference());
            RuntimeArray outerArgs = new RuntimeArray();
            outerArgs.elementsAliased = true;
            outerArgs.elements.add(argumentCell);
            RuntimeCode.CallArguments staged = RuntimeCode.newCallArguments(1);
            boolean outerArgsActive = false;
            boolean innerArgsActive = false;

            try {
                RuntimeCode.pushArgs(outerArgs);
                outerArgsActive = true;
                assertFalse(argumentCell.hasPersistentArgumentStorageCell(),
                        "the forwarded argument is not backed by a lexical or container marker");
                assertTrue(RuntimeCode.isCurrentArgumentAlias(argumentCell),
                        "the outer argument frame still aliases the scalar cell");

                staged.add(argumentCell);
                RuntimeArray innerArgs = staged.toRuntimeArray();
                RuntimeCode.pushArgs(innerArgs);
                innerArgsActive = true;

                assertEquals(2, target.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS),
                        "the staged call and inner argument frame each own an alias identity");
                assertTrue(argumentCell.isCurrentCountedScalarOwner(
                                target, argumentCell.countedOwnerSlot()),
                        "a live forwarded alias makes its current scalar cell authoritative");

                target.refCount = 0;
                assertTrue(target.restoreVerifiedCurrentCountedOwnerSlotRefCount(),
                        "verified count repair accepts the active forwarded cell");
                assertEquals(1, target.refCount);
            } finally {
                if (innerArgsActive) RuntimeCode.popArgs();
                staged.finish();
                if (outerArgsActive) RuntimeCode.popArgs();
                argumentCell.set(new RuntimeScalar());
            }
        }
    }
}

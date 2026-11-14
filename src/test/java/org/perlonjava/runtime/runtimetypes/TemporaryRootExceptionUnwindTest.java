package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class TemporaryRootExceptionUnwindTest {
    @Test
    void failedCallDropsOnlyTemporaryRootsCreatedInsideItsFrame() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash callerValue = new RuntimeHash();
            RuntimeHash failedCallValue = new RuntimeHash();
            MortalList.pushTemporaryRoot(callerValue);
            try {
                int rootDepth = MortalList.temporaryRootDepth();
                RuntimeCode failingCode = new RuntimeCode((args, context) -> {
                    MortalList.pushTemporaryRoot(failedCallValue);
                    throw new PerlDieException(new RuntimeScalar("expected failure"));
                }, null);
                RuntimeScalar callable = new RuntimeScalar(failingCode);

                assertThrows(PerlDieException.class,
                        () -> RuntimeCode.apply(
                                callable, new RuntimeArray(), RuntimeContextType.SCALAR));

                assertEquals(rootDepth, MortalList.temporaryRootDepth(),
                        "exceptional call cleanup must preserve caller leases and drop frame leases");
                assertEquals(1, callerValue.ownerSlotCount(PerlOwnerSlot.Kind.EXPRESSION_VALUE));
                assertEquals(0, failedCallValue.ownerSlotCount(
                        PerlOwnerSlot.Kind.EXPRESSION_VALUE));
            } finally {
                MortalList.popTemporaryRoot(callerValue);
            }
        }
    }

    @Test
    void targetBoundaryDropsPropagatedRootsAfterMaterializingTheReturn() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash callerValue = new RuntimeHash();
            RuntimeHash returnedReferent = new RuntimeHash();
            MortalList.pushTemporaryRoot(callerValue);
            try {
                int rootDepth = MortalList.temporaryRootDepth();
                RuntimeScalar returnValue = returnedReferent.createAnonymousReference();
                RuntimeCode[] target = new RuntimeCode[1];
                RuntimeCode transferringCode = new RuntimeCode((args, context) -> {
                    MortalList.pushTemporaryRoot(returnValue);
                    throw new PerlNonLocalReturnException(returnValue, target[0]);
                }, null);
                RuntimeScalar transferringCallable = new RuntimeScalar(transferringCode);
                target[0] = new RuntimeCode((args, context) -> RuntimeCode.apply(
                        transferringCallable, new RuntimeArray(), RuntimeContextType.SCALAR), null);

                RuntimeList result = RuntimeCode.apply(
                        new RuntimeScalar(target[0]), new RuntimeArray(), RuntimeContextType.SCALAR);

                assertEquals(rootDepth, MortalList.temporaryRootDepth(),
                        "the target must preserve caller leases and drop transferred frame leases");
                assertEquals(1, callerValue.ownerSlotCount(PerlOwnerSlot.Kind.EXPRESSION_VALUE));
                assertEquals(0, returnValue.ownerSlotCount(
                        PerlOwnerSlot.Kind.EXPRESSION_VALUE));
                assertSame(returnedReferent, ((RuntimeScalar) result.getFirst()).value,
                        "return ownership must preserve the referent after its temporary root is removed");
            } finally {
                MortalList.popTemporaryRoot(callerValue);
            }
        }
    }
}

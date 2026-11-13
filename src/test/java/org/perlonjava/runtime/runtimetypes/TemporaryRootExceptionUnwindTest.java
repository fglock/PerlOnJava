package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

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
                List<RuntimeBase> rootsBeforeCall = MortalList.snapshotTemporaryRoots();
                RuntimeCode failingCode = new RuntimeCode((args, context) -> {
                    MortalList.pushTemporaryRoot(failedCallValue);
                    throw new PerlDieException(new RuntimeScalar("expected failure"));
                }, null);
                RuntimeScalar callable = new RuntimeScalar(failingCode);

                assertThrows(PerlDieException.class,
                        () -> RuntimeCode.apply(
                                callable, new RuntimeArray(), RuntimeContextType.SCALAR));

                assertEquals(rootsBeforeCall, MortalList.snapshotTemporaryRoots(),
                        "exceptional call cleanup must preserve caller roots and drop frame roots");
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
                List<RuntimeBase> rootsBeforeCall = MortalList.snapshotTemporaryRoots();
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

                assertEquals(rootsBeforeCall, MortalList.snapshotTemporaryRoots(),
                        "the target must preserve caller roots and drop transferred frame roots");
                assertSame(returnedReferent, ((RuntimeScalar) result.getFirst()).value,
                        "return ownership must preserve the referent after its temporary root is removed");
            } finally {
                MortalList.popTemporaryRoot(callerValue);
            }
        }
    }
}

package org.perlonjava.runtime.runtimetypes;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class RuntimeCodeStableHashMutationResultTest {

    @Test
    void freshScalarResultKeepsItsWrapperAndPopsOnlyItsTemporaryRoot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray existingRoot = new RuntimeArray();
            MortalList.pushTemporaryRoot(existingRoot);
            try {
                RuntimeList result = new RuntimeList(new RuntimeScalar(42));
                result.freshScalarResult = true;
                MortalList.pushTemporaryRoot(result);

                RuntimeList coerced = RuntimeCode.coerceStableHashMutationMethodResult(
                        result, RuntimeContextType.SCALAR, RuntimeContextType.SCALAR);

                assertSame(result, coerced);
                assertEquals(List.of(existingRoot), MortalList.snapshotTemporaryRoots());
            } finally {
                MortalList.popTemporaryRoot(existingRoot);
            }
        }
    }

    @Test
    void resultWithoutFreshScalarProofUsesGeneralCopyPath() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeList result = new RuntimeList(new RuntimeScalar(42));
            MortalList.pushTemporaryRoot(result);

            RuntimeList coerced = RuntimeCode.coerceStableHashMutationMethodResult(
                    result, RuntimeContextType.SCALAR, RuntimeContextType.SCALAR);

            assertNotSame(result, coerced);
            assertNotSame(result.elements.getFirst(), coerced.elements.getFirst());
            assertEquals(List.of(), MortalList.snapshotTemporaryRoots());
        }
    }
}

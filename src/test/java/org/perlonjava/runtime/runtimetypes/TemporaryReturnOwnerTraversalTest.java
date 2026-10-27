package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class TemporaryReturnOwnerTraversalTest {
    @Test
    void lexicalCleanupDoesNotWalkUnrelatedTemporaryReferentGraphs() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash metadata = new RuntimeHash();
            metadata.elements = new AbstractMap<>() {
                @Override
                public Set<Map.Entry<String, RuntimeScalar>> entrySet() {
                    throw new AssertionError("lexical cleanup walked an unrelated referent graph");
                }
            };
            RuntimeScalar expression = metadata.createAnonymousReference();
            RuntimeArray local = new RuntimeArray();
            local.localBindingExists = true;
            MortalList.pushTemporaryRoot(expression);
            try {
                assertDoesNotThrow(() -> MortalList.scopeExitCleanupArray(local));
                assertFalse(local.localBindingExists);
            } finally {
                MortalList.popTemporaryRoot(expression);
            }
        }
    }

    @Test
    void expressionListsStillBorrowTheirDirectAggregateReferents() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray target = new RuntimeArray();
            RuntimeList expression = new RuntimeList();
            expression.elements.add(target.createReference());
            MortalList.pushTemporaryRoot(expression);
            try {
                assertTrue(MortalList.temporaryRootDirectlyReferences(target));
            } finally {
                MortalList.popTemporaryRoot(expression);
            }
        }
    }
}

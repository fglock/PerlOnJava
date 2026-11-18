package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class CurrentAggregateElementOwnerContainerTest {
    @Test
    void liveHashElementOwnerSurvivesAnotherContainerChangingTheOwnerHint() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeHash schema = new RuntimeHash();
            schema.localBindingExists = true;
            RuntimeArray temporaryList = new RuntimeArray();

            try {
                schema.put("source", referent.createAnonymousReference());
                RuntimeScalar cell = schema.elements.get("source");
                PerlOwnerSlot owner = cell.countedOwnerSlot();
                assertEquals(PerlOwnerSlot.Kind.HASH_ELEMENT, owner.kind());

                // List-result construction can leave one scalar cell visible
                // through another aggregate without transferring its counted
                // HASH_ELEMENT edge. That aggregate updates only the fast hint.
                temporaryList.elements.add(cell);
                assertSame(temporaryList, cell.getContainerOwner());

                assertTrue(DestroyDispatch.isCurrentPerlOwnerSlot(referent, owner),
                        "the counted owner identity keeps the live schema as a Perl owner");
            } finally {
                temporaryList.elements.clear();
                schema.elements.clear();
                schema.localBindingExists = false;
            }
        }
    }
}

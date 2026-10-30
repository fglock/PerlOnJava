package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class CallArgumentsNestedAggregateOwnerSlotTest {
    @Test
    void nestedListArgumentOwnsStrongArrayAndHashElementsUntilCallFinishes() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar source = new RuntimeScalar();
            source.set(referent.createAnonymousReference());
            RuntimeScalar arrayValue = RuntimeScalar.copyToMortalReturnSlot(source);
            RuntimeScalar hashValue = RuntimeScalar.copyToMortalReturnSlot(source);

            RuntimeArray nestedArray = new RuntimeArray();
            nestedArray.elements.add(arrayValue);
            RuntimeHash nestedHash = new RuntimeHash();
            nestedHash.elements.put("value", hashValue);
            RuntimeList innerList = new RuntimeList();
            innerList.elements.add(nestedArray);
            innerList.elements.add(nestedHash);
            RuntimeList outerList = new RuntimeList();
            outerList.elements.add(innerList);

            RuntimeCode.CallArguments arguments = RuntimeCode.newCallArguments(2);
            try {
                arguments.add(outerList);
                arguments.add(new RuntimeScalar(1));

                assertEquals(2, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE),
                        "nested aggregate elements each get a staged argument owner");

                MortalList.flushAboveMark();
                assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.RETURN_VALUE));
                assertEquals(2, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE),
                        "later argument cleanup must preserve both aggregate elements");

                arguments.finish();
                MortalList.flushAboveMark();
                assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE),
                        "call completion retires every staged aggregate owner");
            } finally {
                arguments.finish();
            }
        }
    }
}

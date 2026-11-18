package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class SharedTiedAggregateContainerOwnerTest {
    @Test
    void convertedTiedArrayTracksDirectElementMutations() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray array = new RuntimeArray();
            array.type = RuntimeArray.TIED_ARRAY;
            array.elements = new TieArray("Test::TieArray", null, null, array);

            RuntimeHash target = new RuntimeHash();
            SharedPerlStorage.shareValue(target);
            RuntimeScalar value = target.createAnonymousReference();
            SharedPerlStorage.share(array.createReference());

            array.elements.add(value);
            assertSame(array, value.getContainerOwner(),
                    "a shared array element write must register its container owner");

            array.elements.remove(0);
            assertNull(value.getContainerOwner(),
                    "removing the shared array element must retire its container owner");
        }
    }

    @Test
    void convertedTiedHashTracksDirectElementMutations() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash hash = new RuntimeHash();
            hash.type = RuntimeHash.TIED_HASH;
            hash.elements = new TieHash("Test::TieHash", null, null);

            RuntimeArray target = new RuntimeArray();
            SharedPerlStorage.shareValue(target);
            RuntimeScalar value = target.createAnonymousReference();
            SharedPerlStorage.share(hash.createReference());

            hash.elements.put("value", value);
            assertSame(hash, value.getContainerOwner(),
                    "a shared hash element write must register its container owner");

            hash.elements.remove("value");
            assertNull(value.getContainerOwner(),
                    "removing the shared hash element must retire its container owner");
        }
    }
}

package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeHashDestroyableValueIndexTest {
    @Test
    void indexTracksBlessingAfterStoreAndReferenceReplacement() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash hash = new RuntimeHash();
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar input = referenceTo(referent);

            hash.put("value", input);
            assertFalse(hash.hasDestroyableValues(), "unblessed references do not need progressive clear");

            referent.setBlessId(NameNormalizer.getBlessId("Local::IndexedDestroyer"));
            assertTrue(hash.hasDestroyableValues(), "blessing a stored referent updates its owner hash");

            hash.get("value").set(new RuntimeScalar("plain"));
            assertFalse(hash.hasDestroyableValues(), "replacing the reference removes its destroyer entry");

            hash.put("aliased", referenceTo(new RuntimeHash()));
            RuntimeScalar aliasedCell = hash.elements.get("aliased");
            hash.aliasScalarCell("alias", aliasedCell);
            assertTrue(hash.hasDestroyableValues(), "aliased cells use the conservative progressive path");
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.REFERENCE;
        scalar.value = referent;
        return scalar;
    }
}

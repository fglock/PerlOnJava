package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeHashIdentityMembershipTest {
    @Test
    void identityMembershipTracksEveryOwnedSlotMutation() {
        RuntimeHash hash = new RuntimeHash();
        RuntimeScalar sharedCell = new RuntimeScalar("same");
        RuntimeScalar equalButDistinctCell = new RuntimeScalar("same");

        hash.elements.put("first", sharedCell);
        assertTrue(hash.containsElementCellIdentity(sharedCell));
        assertFalse(hash.containsElementCellIdentity(equalButDistinctCell));

        hash.elements.put("second", sharedCell);
        hash.elements.put("first", new RuntimeScalar("replacement"));
        assertTrue(hash.containsElementCellIdentity(sharedCell),
                "one of two slots still contains the same scalar cell");

        hash.elements.remove("second");
        assertFalse(hash.containsElementCellIdentity(sharedCell),
                "removing its final slot removes the identity entry");

        Map<String, RuntimeScalar> bulk = new LinkedHashMap<>();
        bulk.put("bulk-a", sharedCell);
        bulk.put("bulk-b", sharedCell);
        hash.elements.putAll(bulk);
        assertTrue(hash.containsElementCellIdentity(sharedCell));

        var values = hash.elements.values().iterator();
        while (values.hasNext()) {
            if (values.next() == sharedCell) {
                values.remove();
                break;
            }
        }
        assertTrue(hash.containsElementCellIdentity(sharedCell),
                "removing one duplicate through values() preserves the other slot");

        var entries = hash.elements.entrySet().iterator();
        while (entries.hasNext()) {
            if (entries.next().getValue() == sharedCell) {
                entries.remove();
                break;
            }
        }
        assertFalse(hash.containsElementCellIdentity(sharedCell),
                "removing the final duplicate through entrySet() clears membership");

        hash.elements.put("last", sharedCell);
        hash.elements.clear();
        assertFalse(hash.containsElementCellIdentity(sharedCell));
    }
}

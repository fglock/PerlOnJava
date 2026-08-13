package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class NameNormalizerThreadSnapshotTest {
    @Test
    void snapshotPreservesAuthoritativeClassIdsBeforeGraphCloning() {
        PerlRuntime parent = new PerlRuntime().initialize();
        int firstId;
        int secondId;
        try (PerlRuntime.Binding ignored = parent.bind()) {
            firstId = NameNormalizer.getBlessId("Snapshot::First");
            secondId = NameNormalizer.getBlessId("Snapshot::Second");
            RuntimeHash first = new RuntimeHash();
            first.blessId = firstId;
            RuntimeHash second = new RuntimeHash();
            second.blessId = secondId;
            parent.globalState.scalarValues().put("Snapshot::first", first.createReference());
            parent.globalState.scalarValues().put("Snapshot::second", second.createReference());
        }

        PerlRuntime child = parent.snapshotClone();
        try (PerlRuntime.Binding ignored = child.bind()) {
            assertEquals("Snapshot::First", NameNormalizer.getBlessStr(firstId));
            assertEquals("Snapshot::Second", NameNormalizer.getBlessStr(secondId));
            RuntimeBase first = (RuntimeBase) child.globalState.scalarValues()
                    .get("Snapshot::first").value;
            RuntimeBase second = (RuntimeBase) child.globalState.scalarValues()
                    .get("Snapshot::second").value;
            assertEquals("Snapshot::First", NameNormalizer.getBlessStr(first.blessId));
            assertEquals("Snapshot::Second", NameNormalizer.getBlessStr(second.blessId));
        }
    }
}

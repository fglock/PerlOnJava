package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PerlRuntimeRegexSnapshotTest {
    @Test
    void snapshotInheritsUserPropertyResultsWithoutSharingTheCache() {
        PerlRuntime parent = new PerlRuntime();
        parent.regexState.userUnicodePropertyCache.put(
                "main::IsPhaseTwentySeven", "\\x{41}");

        PerlRuntime child = parent.snapshotClone();

        assertNotSame(parent.regexState.userUnicodePropertyCache,
                child.regexState.userUnicodePropertyCache);
        assertEquals("\\x{41}", child.regexState.userUnicodePropertyCache.get(
                "main::IsPhaseTwentySeven"));
        child.regexState.userUnicodePropertyCache.put("main::IsChildOnly", "\\x{42}");
        assertTrue(!parent.regexState.userUnicodePropertyCache.containsKey("main::IsChildOnly"));
    }
}

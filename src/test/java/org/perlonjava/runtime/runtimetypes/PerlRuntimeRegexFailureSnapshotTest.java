package org.perlonjava.runtime.runtimetypes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlRuntimeRegexFailureSnapshotTest {
    @Test
    void snapshotInheritsSuccessfulPropertiesButNotStickyFailures() {
        PerlRuntime parent = new PerlRuntime();
        parent.regexState.userUnicodePropertyCache.put(
                "main::IsResolved\0s", "0041");
        parent.regexState.userUnicodePropertyFailureCache.put(
                "main::IsFailed\0s", "Ifailed once");

        PerlRuntime child = parent.snapshotClone();

        assertEquals("0041", child.regexState.userUnicodePropertyCache.get(
                "main::IsResolved\0s"));
        assertTrue(child.regexState.userUnicodePropertyFailureCache.isEmpty());
        child.regexState.userUnicodePropertyFailureCache.put(
                "main::IsChildFailed\0s", "Ichild only");
        assertFalse(parent.regexState.userUnicodePropertyFailureCache
                .containsKey("main::IsChildFailed\0s"));
    }
}

package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.regex.RuntimeRegex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PerlRuntimeLocaleRegexIsolationTest {
    @Test
    void sharedCompiledRegexUsesTheBoundRuntimesLocale() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        RuntimeScalar regex;
        RuntimeScalar highByte = new RuntimeScalar(new byte[]{(byte)0xe4});

        try (PerlRuntime.Binding ignored = first.bind()) {
            first.regexState.localeState.publishCtype("C");
            regex = RuntimeRegex.getQuotedRegex(
                    new RuntimeScalar("^\\w$"), new RuntimeScalar("l"));
            assertFalse(matches(regex, highByte));
        }
        try (PerlRuntime.Binding ignored = second.bind()) {
            second.regexState.localeState.publishCtype("en_US.ISO8859-1");
            assertTrue(matches(regex, highByte));
        }
        try (PerlRuntime.Binding ignored = first.bind()) {
            assertFalse(matches(regex, highByte));
        }
    }

    @Test
    void ithreadSnapshotCopiesLocaleThenDivergesIndependently() {
        PerlRuntime parent = new PerlRuntime();
        PerlRuntime child = new PerlRuntime();
        parent.regexState.localeState.publishCtype("en_US.ISO8859-1");

        parent.regexState.snapshotInto(child.regexState);
        assertEquals("en_US.ISO8859-1",
                child.regexState.localeState.currentCtype());

        child.regexState.localeState.publishCtype("C");
        assertEquals("en_US.ISO8859-1",
                parent.regexState.localeState.currentCtype());
        assertEquals("C", child.regexState.localeState.currentCtype());
    }

    @Test
    void localeMatchTaintsCapturesInTaintModeEvenForUntaintedSubject() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            GlobalContext.setThreadTaintMode(true);
            try {
                RuntimeScalar outerLocale = RuntimeRegex.getQuotedRegex(
                        new RuntimeScalar("^(\\w+)$"), new RuntimeScalar("l"));
                assertTrue(matches(outerLocale, new RuntimeScalar("abc")));
                assertTrue(runtime.regexState.lastMatchResultsTainted);

                runtime.regexState.lastMatchResultsTainted = false;
                RuntimeScalar inlineLocale = RuntimeRegex.getQuotedRegex(
                        new RuntimeScalar("^(?l:(\\w+))$"), new RuntimeScalar(""));
                assertTrue(matches(inlineLocale, new RuntimeScalar("abc")));
                assertTrue(runtime.regexState.lastMatchResultsTainted);
            } finally {
                GlobalContext.setThreadTaintMode(false);
            }
        }
    }

    private static boolean matches(RuntimeScalar regex, RuntimeScalar input) {
        return RuntimeRegex.matchRegex(regex, input, RuntimeContextType.SCALAR)
                .scalar().getBoolean();
    }
}

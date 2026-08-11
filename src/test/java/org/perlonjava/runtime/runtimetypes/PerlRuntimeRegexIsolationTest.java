package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.operators.Time;
import org.perlonjava.runtime.regex.RuntimeRegex;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class PerlRuntimeRegexIsolationTest {

    @Test
    void concurrentMatchesKeepCapturesAndOffsetsInTheirRuntime() throws Exception {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        FutureTask<MatchSignature> firstTask = matchTask(
                first, "alpha-17", ready, start);
        FutureTask<MatchSignature> secondTask = matchTask(
                second, "beta-2048", ready, start);
        Thread firstThread = Thread.ofPlatform().name("perl-regex-first").start(firstTask);
        Thread secondThread = Thread.ofPlatform().name("perl-regex-second").start(secondTask);

        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            firstThread.join(TimeUnit.SECONDS.toMillis(10));
            secondThread.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertFalse(firstThread.isAlive());
        assertFalse(secondThread.isAlive());
        assertEquals(new MatchSignature("alpha", "17", "alpha-17", 0, 8),
                firstTask.get(1, TimeUnit.SECONDS));
        assertEquals(new MatchSignature("beta", "2048", "beta-2048", 0, 9),
                secondTask.get(1, TimeUnit.SECONDS));
        assertNull(PerlRuntime.currentOrNull());
    }

    @Test
    void positionAndZeroLengthBookkeepingArePerRuntimeEvenForSameScalar() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        RuntimeScalar shared = new RuntimeScalar("abc");

        try (PerlRuntime.Binding ignored = first.bind()) {
            RuntimePosLvalue.pos(shared).set(1);
            RuntimePosLvalue.recordZeroLengthMatch(shared, 1, "a*");
        }
        try (PerlRuntime.Binding ignored = second.bind()) {
            assertFalse(RuntimePosLvalue.pos(shared).getDefinedBoolean());
            assertFalse(RuntimePosLvalue.hadZeroLengthMatchAt(shared, 1, "a*"));
            RuntimePosLvalue.pos(shared).set(2);
        }
        try (PerlRuntime.Binding ignored = first.bind()) {
            assertEquals(1, RuntimePosLvalue.pos(shared).getInt());
            assertTrue(RuntimePosLvalue.hadZeroLengthMatchAt(shared, 1, "a*"));
        }
    }

    @Test
    void optimizedAndMatchOnceCallsitesArePerRuntime() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        RuntimeScalar firstRegex;
        RuntimeScalar secondRegex;

        try (PerlRuntime.Binding ignored = first.bind()) {
            firstRegex = RuntimeRegex.getQuotedRegex(
                    new RuntimeScalar("a"), new RuntimeScalar("?"), 73);
            assertTrue(matches(firstRegex, "a"));
            assertFalse(matches(firstRegex, "a"));
        }
        try (PerlRuntime.Binding ignored = second.bind()) {
            secondRegex = RuntimeRegex.getQuotedRegex(
                    new RuntimeScalar("b"), new RuntimeScalar("?"), 73);
            assertTrue(matches(secondRegex, "b"));
            assertFalse(matches(secondRegex, "b"));
        }

        assertNotSame(firstRegex, secondRegex);
    }

    @Test
    void resetOnlyClearsMatchOnceStateInTheBoundRuntime() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        RuntimeScalar firstRegex;
        RuntimeScalar secondRegex;

        try (PerlRuntime.Binding ignored = first.bind()) {
            firstRegex = RuntimeRegex.getQuotedRegex(
                    new RuntimeScalar("x"), new RuntimeScalar("?"), 91);
            assertTrue(matches(firstRegex, "x"));
        }
        try (PerlRuntime.Binding ignored = second.bind()) {
            secondRegex = RuntimeRegex.getQuotedRegex(
                    new RuntimeScalar("x"), new RuntimeScalar("?"), 91);
            assertTrue(matches(secondRegex, "x"));
        }
        try (PerlRuntime.Binding ignored = first.bind()) {
            RuntimeRegex.reset();
            assertTrue(matches(firstRegex, "x"));
        }
        try (PerlRuntime.Binding ignored = second.bind()) {
            assertFalse(matches(secondRegex, "x"));
        }
    }

    @Test
    void dynamicSnapshotRestoresEveryMatchMetadataField() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeRegexState state = runtime.regexState;
            state.lastMatchUsedBackslashK = true;
            state.lastMatchResultsTainted = true;
            state.lastNamedCaptureGroups = java.util.Map.of("name", List.of("outer"));
            state.manualCaptureStarts = new int[]{4};
            state.manualCaptureEnds = new int[]{9};
            RegexState snapshot = new RegexState();

            state.lastMatchUsedBackslashK = false;
            state.lastMatchResultsTainted = false;
            state.lastNamedCaptureGroups = null;
            state.manualCaptureStarts = null;
            state.manualCaptureEnds = null;
            snapshot.restore();

            assertTrue(state.lastMatchUsedBackslashK);
            assertTrue(state.lastMatchResultsTainted);
            assertEquals(List.of("outer"), state.lastNamedCaptureGroups.get("name"));
            assertArrayEquals(new int[]{4}, state.manualCaptureStarts);
            assertArrayEquals(new int[]{9}, state.manualCaptureEnds);
        }
    }

    @Test
    void alarmMediatedMatchRunsInTheOwningRuntime() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            PerlRuntime runtime = new PerlRuntime();
            try (PerlRuntime.Binding ignored = runtime.bind()) {
                RuntimeScalar regex = RuntimeRegex.getQuotedRegex(
                        new RuntimeScalar("version-(?<major>\\d+)"), new RuntimeScalar(""));
                Time.alarm(RuntimeContextType.SCALAR, new RuntimeScalar(5));
                try {
                    assertTrue(matches(regex, "version-42"));
                    assertEquals("42", RuntimeRegex.captureString(1));
                    assertEquals(List.of("42"), runtime.regexState.lastNamedCaptureGroups.get("major"));
                } finally {
                    Time.alarm(RuntimeContextType.SCALAR, new RuntimeScalar(0));
                }
            }
        });
    }

    private static FutureTask<MatchSignature> matchTask(
            PerlRuntime runtime, String input, CountDownLatch ready, CountDownLatch start) {
        return new FutureTask<>(() -> {
            try (PerlRuntime.Binding ignored = runtime.bind()) {
                RuntimeScalar regex = RuntimeRegex.getQuotedRegex(
                        new RuntimeScalar("(?<word>[a-z]+)-(\\d+)"), new RuntimeScalar(""));
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                assertTrue(matches(regex, input));
                assertEquals(List.of(RuntimeRegex.captureString(1)),
                        runtime.regexState.lastNamedCaptureGroups.get("word"));
                return new MatchSignature(
                        RuntimeRegex.captureString(1),
                        RuntimeRegex.captureString(2),
                        RuntimeRegex.matchString(),
                        RuntimeRegex.matcherStart(0).getInt(),
                        RuntimeRegex.matcherEnd(0).getInt());
            }
        });
    }

    private static boolean matches(RuntimeScalar regex, String input) {
        return RuntimeRegex.matchRegex(regex, new RuntimeScalar(input), RuntimeContextType.SCALAR)
                .scalar().getBoolean();
    }

    private record MatchSignature(String first, String second, String whole, int start, int end) {
    }
}

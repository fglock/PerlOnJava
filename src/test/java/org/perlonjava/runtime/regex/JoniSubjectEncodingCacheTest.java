package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

@Tag("unit")
class JoniSubjectEncodingCacheTest {
    @Test
    void unchangedScalarReusesEncodingButMutationInvalidatesIt() {
        RuntimeScalar subject = new RuntimeScalar("a😀b");

        JoniRegexPattern.InputEncoding first = JoniRegexPattern.inputEncoding(
                subject.toString(), subject, false);
        JoniRegexPattern.InputEncoding second = JoniRegexPattern.inputEncoding(
                subject.toString(), subject, false);
        assertSame(first, second);
        assertArrayEquals(new int[] {0, 1, 1, 5, 6}, first.charToByte());

        subject.set("changed");
        JoniRegexPattern.InputEncoding changed = JoniRegexPattern.inputEncoding(
                subject.toString(), subject, false);
        assertNotSame(first, changed);
    }

    @Test
    void renderedNonStringScalarDoesNotReuseStaleEncoding() {
        RuntimeScalar subject = new RuntimeScalar(42);

        JoniRegexPattern.InputEncoding first = JoniRegexPattern.inputEncoding(
                "first", subject, false);
        JoniRegexPattern.InputEncoding second = JoniRegexPattern.inputEncoding(
                "second", subject, false);

        assertNotSame(first, second);
        assertArrayEquals("first".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                first.bytes());
        assertArrayEquals("second".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                second.bytes());
    }

    @Test
    void equalValuedScalarsAndByteModeDoNotShareEncoding() {
        RuntimeScalar firstSubject = new RuntimeScalar(new String("é"));
        RuntimeScalar secondSubject = new RuntimeScalar(new String("é"));

        JoniRegexPattern.InputEncoding first = JoniRegexPattern.inputEncoding(
                firstSubject.toString(), firstSubject, false);
        JoniRegexPattern.InputEncoding second = JoniRegexPattern.inputEncoding(
                secondSubject.toString(), secondSubject, false);
        JoniRegexPattern.InputEncoding bytes = JoniRegexPattern.inputEncoding(
                firstSubject.toString(), firstSubject, true);

        assertNotSame(first, second);
        assertNotSame(first, bytes);
    }
}

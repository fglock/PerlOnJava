/*
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.joni;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.jcodings.specific.UTF8Encoding;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestParseDebugOptIn {
    @Test
    public void ordinaryCompilationDoesNotRecordParserFacts() {
        Regex regex = compile("(?<b>\\g{c})(?<c>x)(?&b)", false);

        assertEquals(ParseDebugTrace.EMPTY, regex.getParseDebugTrace());
    }

    @Test
    public void enabledCompilationPublishesBothForwardReferenceViews() {
        ParseDebugTrace trace = compile(
                "(?<b>\\g{c})(?<c>x)(?&b)", true).getParseDebugTrace();

        assertTrue(trace.validationReparsed());
        assertEquals(2, trace.passes().size());
        assertTrue(hasReference(trace.passes().get(0), false, 0));
        assertTrue(hasReference(trace.passes().get(1), true, 2));
        assertEquals(List.of("OPEN@1", "REFERENCE@3", "CLOSE@6",
                        "OPEN@8", "EXACT@10", "CLOSE@12", "CALL@14",
                        "END@17"),
                programs(trace.passes().get(0)));
        assertEquals(17, trace.passes().get(0).programSize());
        assertEquals(3, trace.passes().get(0).firstConsumingPosition());
        assertTrue(trace.passes().get(0).events().stream()
                .filter(ParseDebugEvent.Program.class::isInstance)
                .map(ParseDebugEvent.Program.class::cast)
                .anyMatch(event -> event.kind()
                        == ParseDebugEvent.ProgramKind.CALL
                        && event.number() == 1));
        assertTrue(trace.passes().get(1).events().stream()
                .filter(ParseDebugEvent.Program.class::isInstance)
                .map(ParseDebugEvent.Program.class::cast)
                .anyMatch(event -> event.kind()
                        == ParseDebugEvent.ProgramKind.CALL
                        && event.number() == 1));
    }

    @Test
    public void failurePrefixIsRecordedOnlyWhenRequested() {
        SyntaxException ordinary = assertThrows(SyntaxException.class,
                () -> compile("(?<b>\\g{c}", false));
        SyntaxException traced = assertThrows(SyntaxException.class,
                () -> compile("(?<b>\\g{c}", true));

        assertEquals(ParseDebugTrace.EMPTY, ordinary.getParseDebugTrace());
        assertFalse(traced.getParseDebugTrace().passes().isEmpty());
        assertTrue(traced.getParseDebugTrace().passes().get(0).events().stream()
                .anyMatch(ParseDebugEvent.Reference.class::isInstance));
        assertEquals(List.of("OPEN@1", "REFERENCE@3", "CLOSE@6", "END@8"),
                programs(traced.getParseDebugTrace().passes().get(0)));
    }

    private static boolean hasReference(ParseDebugTrace.Pass pass,
            boolean resolved, int target) {
        return pass.events().stream()
                .filter(ParseDebugEvent.Reference.class::isInstance)
                .map(ParseDebugEvent.Reference.class::cast)
                .anyMatch(event -> event.name().equals("c")
                        && event.resolved() == resolved
                        && event.targetCapture() == target);
    }

    private static List<String> programs(ParseDebugTrace.Pass pass) {
        return pass.events().stream()
                .filter(ParseDebugEvent.Program.class::isInstance)
                .map(ParseDebugEvent.Program.class::cast)
                .map(event -> event.kind() + "@" + event.programPosition())
                .toList();
    }

    private static Regex compile(String source, boolean recordParseDebug) {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.CAPTURE_GROUP,
                UTF8Encoding.INSTANCE, Syntax.PerlNG, WarnCallback.DEFAULT,
                recordParseDebug);
    }
}

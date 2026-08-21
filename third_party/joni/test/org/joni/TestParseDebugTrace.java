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

import org.jcodings.specific.UTF8Encoding;
import org.joni.ast.BackRefNode;
import org.joni.ast.EncloseNode;
import org.joni.ast.ListNode;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestParseDebugTrace {
    @Test
    public void successfulForwardReferencePublishesBothNativeViews() {
        ParseDebugTrace trace = analyse("(?<b>\\g{c})(?<c>x)(?&b)");

        assertTrue(trace.validationReparsed());
        assertEquals(2, trace.passes().size());
        assertTrue(hasReference(trace.passes().get(0), false, 0));
        assertTrue(hasReference(trace.passes().get(1), true, 2));
        assertEquals(trace.passes().get(0).firstNodeId(),
                trace.passes().get(1).firstNodeId());
    }

    @Test
    public void recorderPublishesImmutableUnresolvedAndResolvedPasses() {
        ParseDebugRecorder recorder = new ParseDebugRecorder();
        EncloseNode capture = EncloseNode.newMemory(Option.NONE, true);
        capture.regNum = 2;
        byte[] name = "c".getBytes(StandardCharsets.UTF_8);
        BackRefNode reference = new BackRefNode(name, 0, name.length, null);
        capture.setTarget(reference);
        ListNode root = ListNode.newList(capture, null);

        try (ParseDebugRecorder.Scope ignored = recorder.phase(
                ParseDebugEvent.PhaseKind.REG, 0)) {
            recorder.captureOpen(capture, 0, "c");
            recorder.reference(reference, 5, "c");
        }
        recorder.freezeFirstPass(root);
        reference.back = new int[] {2};
        reference.backNum = 1;
        recorder.freezeResolvedPass(root);

        ParseDebugTrace trace = recorder.snapshot();
        assertTrue(trace.validationReparsed());
        assertEquals(2, trace.passes().size());
        assertTrue(hasReference(trace.passes().get(0), false, 0));
        assertTrue(hasReference(trace.passes().get(1), true, 2));
        assertTrue(trace.passes().get(0).nodeCount() >= 2);
        assertTrue(trace.passes().get(0).firstNodeId() > 0);
        assertThrows(UnsupportedOperationException.class,
                () -> trace.passes().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> trace.passes().get(0).events().clear());
    }

    @Test
    public void syntaxFailureRetainsTheAcceptedParserPrefix() {
        SyntaxException error = assertThrows(SyntaxException.class,
                () -> compile("(?<b>\\g{c}"));
        ParseDebugTrace trace = error.getParseDebugTrace();

        assertFalse(trace.validationReparsed());
        assertEquals(1, trace.passes().size());
        assertTrue(trace.passes().get(0).events().stream()
                .anyMatch(ParseDebugEvent.Capture.class::isInstance));
        assertTrue(trace.passes().get(0).events().stream()
                .anyMatch(ParseDebugEvent.Reference.class::isInstance));
        assertTrue(trace.passes().get(0).events().stream()
                .anyMatch(event -> event instanceof ParseDebugEvent.Phase phase
                        && phase.phase() == ParseDebugEvent.PhaseKind.ATOM));
    }

    @Test
    public void cursorPositionsRemainNativeByteOffsets() {
        SyntaxException error = assertThrows(SyntaxException.class,
                () -> compile("é(?<b>\\g{c}"));
        ParseDebugEvent.Capture capture = error.getParseDebugTrace().passes()
                .get(0).events().stream()
                .filter(ParseDebugEvent.Capture.class::isInstance)
                .map(ParseDebugEvent.Capture.class::cast)
                .filter(ParseDebugEvent.Capture::opening)
                .findFirst().orElseThrow();

        assertEquals(2, capture.bytePosition());
    }

    @Test
    public void emptyTraceAndPublicValueInvariantsAreStable() {
        assertTrue(ParseDebugTrace.EMPTY.passes().isEmpty());
        assertFalse(ParseDebugTrace.EMPTY.validationReparsed());
        assertThrows(IllegalArgumentException.class,
                () -> new ParseDebugTrace(java.util.List.of(), true));
        assertThrows(IllegalArgumentException.class,
                () -> new ParseDebugEvent.Node(-1, 0, 1,
                        ParseDebugEvent.NodeKind.OTHER));
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

    private static Regex compile(String source) {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.CAPTURE_GROUP,
                UTF8Encoding.INSTANCE, Syntax.PerlNG, WarnCallback.DEFAULT,
                true);
    }

    private static ParseDebugTrace analyse(String source) {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        Regex regex = compile("");
        Analyser analyser = new Analyser(regex, Syntax.PerlNG, bytes, 0,
                bytes.length, WarnCallback.DEFAULT);
        analyser.compile();
        return analyser.frozenParseDebugTrace();
    }
}

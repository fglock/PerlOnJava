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

import java.util.List;
import java.util.Objects;

/** Immutable native parser-pass facts; presentation must not feed matching. */
public record ParseDebugTrace(List<Pass> passes, boolean validationReparsed) {
    public static final ParseDebugTrace EMPTY = new ParseDebugTrace(List.of(), false);

    public ParseDebugTrace {
        passes = List.copyOf(passes);
        if (validationReparsed && passes.size() < 2) {
            throw new IllegalArgumentException("reparse trace requires two passes");
        }
    }

    public record Pass(List<ParseDebugEvent> events, int nodeCount,
            int firstNodeId, ParseDebugEvent.NodeKind firstNodeKind,
            int programSize, int firstConsumingPosition) {
        public Pass(List<ParseDebugEvent> events, int nodeCount,
                int firstNodeId, ParseDebugEvent.NodeKind firstNodeKind) {
            this(events, nodeCount, firstNodeId, firstNodeKind, 0, 0);
        }

        public Pass {
            events = List.copyOf(events);
            if (nodeCount < 0 || firstNodeId < 0 || programSize < 0
                    || firstConsumingPosition < 0) {
                throw new IllegalArgumentException();
            }
            if ((nodeCount == 0) != (firstNodeId == 0)) {
                throw new IllegalArgumentException("first-node fact");
            }
            firstNodeKind = nodeCount == 0 ? ParseDebugEvent.NodeKind.OTHER
                    : Objects.requireNonNull(firstNodeKind);
        }
    }
}

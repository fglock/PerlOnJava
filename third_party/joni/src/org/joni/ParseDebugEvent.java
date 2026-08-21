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

import java.util.Objects;

/** Immutable structural event accepted by one native parser pass. */
public sealed interface ParseDebugEvent permits ParseDebugEvent.Phase,
        ParseDebugEvent.Node, ParseDebugEvent.Capture,
        ParseDebugEvent.Reference, ParseDebugEvent.Edge {
    int bytePosition();
    int depth();

    enum PhaseKind { REG, BRANCH, PIECE, ATOM, TAIL, LAST_BRANCH }
    enum NodeKind { OPEN, CLOSE, EXACT, REFERENCE, CALL, END, OTHER }
    enum EdgeKind { NEXT, TARGET, CAPTURE_CLOSE, REFERENCE_TARGET, CALL_TARGET }

    record Phase(int bytePosition, int depth, PhaseKind phase,
            boolean entering) implements ParseDebugEvent {
        public Phase {
            requirePosition(bytePosition, depth);
            Objects.requireNonNull(phase);
        }
    }

    record Node(int bytePosition, int depth, int nodeId,
            NodeKind kind) implements ParseDebugEvent {
        public Node {
            requirePosition(bytePosition, depth);
            if (nodeId <= 0) throw new IllegalArgumentException("nodeId");
            Objects.requireNonNull(kind);
        }
    }

    record Capture(int bytePosition, int depth, int nodeId, boolean opening,
            int number, String name) implements ParseDebugEvent {
        public Capture {
            requirePosition(bytePosition, depth);
            if (nodeId <= 0 || number <= 0) throw new IllegalArgumentException();
            name = name == null ? "" : name;
        }
    }

    /** targetCapture is zero only while a named reference remains unresolved. */
    record Reference(int bytePosition, int depth, int nodeId, String name,
            boolean resolved, int targetCapture) implements ParseDebugEvent {
        public Reference {
            requirePosition(bytePosition, depth);
            if (nodeId <= 0 || targetCapture < 0) throw new IllegalArgumentException();
            name = Objects.requireNonNull(name);
        }
    }

    record Edge(int bytePosition, int depth, int fromNodeId, int toNodeId,
            EdgeKind kind) implements ParseDebugEvent {
        public Edge {
            requirePosition(bytePosition, depth);
            if (fromNodeId <= 0 || toNodeId <= 0) throw new IllegalArgumentException();
            Objects.requireNonNull(kind);
        }
    }

    private static void requirePosition(int bytePosition, int depth) {
        if (bytePosition < 0 || depth < 0) throw new IllegalArgumentException();
    }
}

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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.joni.ParseDebugEvent.EdgeKind;
import org.joni.ParseDebugEvent.NodeKind;
import org.joni.ParseDebugEvent.PhaseKind;
import org.joni.ast.AnchorNode;
import org.joni.ast.BackRefNode;
import org.joni.ast.CallNode;
import org.joni.ast.EncloseNode;
import org.joni.ast.ListNode;
import org.joni.ast.Node;
import org.joni.ast.QuantifierNode;
import org.joni.constants.internal.NodeType;

/** Mutable parser-local builder; only immutable snapshots leave Analyser. */
final class ParseDebugRecorder {
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private final List<ParseDebugEvent> events = new ArrayList<>();
    private final IdentityHashMap<Node, Integer> nodeIds = new IdentityHashMap<>();
    private final IdentityHashMap<Node, Integer> nodePositions = new IdentityHashMap<>();
    private final Map<Integer, String> captureNames = new java.util.HashMap<>();
    private final List<ParseDebugTrace.Pass> passes = new ArrayList<>();
    private int depth;
    private int nextNodeId = 1;
    private int firstNodeId;
    private NodeKind firstNodeKind = NodeKind.OTHER;
    private int lastStructuralNodeId;
    private boolean firstPassFrozen;
    private boolean validationReparsed;

    Scope phase(PhaseKind phase, int bytePosition) {
        int eventDepth = depth++;
        events.add(new ParseDebugEvent.Phase(bytePosition, eventDepth, phase, true));
        return () -> {
            depth = Math.max(0, depth - 1);
            events.add(new ParseDebugEvent.Phase(
                    bytePosition, eventDepth, phase, false));
        };
    }

    void captureOpen(EncloseNode node, int bytePosition, String name) {
        int nodeId = node(node, bytePosition, NodeKind.OPEN);
        String stableName = name == null ? "" : name;
        captureNames.put(node.regNum, stableName);
        events.add(new ParseDebugEvent.Capture(bytePosition, depth, nodeId,
                true, node.regNum, stableName));
    }

    void captureClose(EncloseNode node, int bytePosition) {
        int nodeId = node(node, bytePosition, NodeKind.CLOSE);
        events.add(new ParseDebugEvent.Capture(bytePosition, depth, nodeId,
                false, node.regNum, captureNames.getOrDefault(node.regNum, "")));
        if (lastStructuralNodeId != 0 && lastStructuralNodeId != nodeId) {
            events.add(new ParseDebugEvent.Edge(bytePosition, depth,
                    lastStructuralNodeId, nodeId, EdgeKind.CAPTURE_CLOSE));
        }
        lastStructuralNodeId = nodeId;
    }

    void reference(BackRefNode node, int bytePosition, String name) {
        int nodeId = node(node, bytePosition, NodeKind.REFERENCE);
        events.add(referenceEvent(node, nodeId, bytePosition, depth, name));
        lastStructuralNodeId = nodeId;
    }

    void call(CallNode node, int bytePosition) {
        node(node, bytePosition, NodeKind.CALL);
    }

    void freezeFirstPass(Node root) {
        if (firstPassFrozen) return;
        events.add(new ParseDebugEvent.Phase(0, 0,
                PhaseKind.LAST_BRANCH, true));
        snapshotTree(root, new IdentityHashMap<>());
        events.add(new ParseDebugEvent.Phase(0, 0,
                PhaseKind.LAST_BRANCH, false));
        passes.add(pass(List.copyOf(events)));
        firstPassFrozen = true;
    }

    void freezeResolvedPass(Node root) {
        freezeFirstPass(root);
        validationReparsed = true;
        List<ParseDebugEvent> resolved = new ArrayList<>(events.size());
        for (ParseDebugEvent event : events) {
            if (event instanceof ParseDebugEvent.Reference reference) {
                Node node = nodeForId(reference.nodeId());
                if (node instanceof BackRefNode backRef) {
                    resolved.add(referenceEvent(backRef, reference.nodeId(),
                            reference.bytePosition(), reference.depth(),
                            reference.name()));
                    continue;
                }
            }
            resolved.add(event);
        }
        passes.add(pass(List.copyOf(resolved)));
    }

    ParseDebugTrace snapshot() {
        if (!firstPassFrozen && !events.isEmpty()) {
            passes.add(pass(List.copyOf(events)));
            firstPassFrozen = true;
        }
        if (passes.isEmpty()) return ParseDebugTrace.EMPTY;
        return new ParseDebugTrace(List.copyOf(passes), validationReparsed);
    }

    private ParseDebugTrace.Pass pass(List<ParseDebugEvent> stableEvents) {
        return new ParseDebugTrace.Pass(stableEvents, nodeIds.size(),
                firstNodeId, firstNodeKind);
    }

    private int node(Node node, int bytePosition, NodeKind kind) {
        Integer existing = nodeIds.get(node);
        if (existing != null) return existing;
        int nodeId = nextNodeId++;
        nodeIds.put(node, nodeId);
        nodePositions.put(node, bytePosition);
        if (firstNodeId == 0) {
            firstNodeId = nodeId;
            firstNodeKind = kind;
        }
        events.add(new ParseDebugEvent.Node(bytePosition, depth, nodeId, kind));
        if (lastStructuralNodeId != 0) {
            events.add(new ParseDebugEvent.Edge(bytePosition, depth,
                    lastStructuralNodeId, nodeId, EdgeKind.NEXT));
        }
        lastStructuralNodeId = nodeId;
        events.add(new ParseDebugEvent.Phase(bytePosition, depth,
                PhaseKind.TAIL, true));
        return nodeId;
    }

    private ParseDebugEvent.Reference referenceEvent(BackRefNode node,
            int nodeId, int bytePosition, int eventDepth, String recordedName) {
        String name = recordedName;
        if ((name == null || name.isEmpty()) && node.unresolvedName != null) {
            name = new String(node.unresolvedName, node.unresolvedNameP,
                    node.unresolvedNameEnd - node.unresolvedNameP,
                    StandardCharsets.UTF_8);
        }
        if (name == null) name = "";
        int target = node.backNum == 0 ? 0 : node.back[0];
        return new ParseDebugEvent.Reference(bytePosition, eventDepth, nodeId,
                name, node.backNum != 0, target);
    }

    private Node nodeForId(int nodeId) {
        for (Map.Entry<Node, Integer> entry : nodeIds.entrySet()) {
            if (entry.getValue() == nodeId) return entry.getKey();
        }
        return null;
    }

    private void snapshotTree(Node node, IdentityHashMap<Node, Boolean> seen) {
        if (node == null || seen.put(node, Boolean.TRUE) != null) return;
        int position = nodePositions.getOrDefault(node, 0);
        int from = node(node, position, kind(node));
        switch (node.getType()) {
        case NodeType.LIST, NodeType.ALT -> {
            ListNode list = (ListNode)node;
            edgeAndSnapshot(from, list.value, position, EdgeKind.TARGET, seen);
            edgeAndSnapshot(from, list.tail, position, EdgeKind.NEXT, seen);
        }
        case NodeType.QTFR -> edgeAndSnapshot(from,
                ((QuantifierNode)node).target, position, EdgeKind.TARGET, seen);
        case NodeType.ENCLOSE -> {
            EncloseNode enclose = (EncloseNode)node;
            edgeAndSnapshot(from, enclose.assertionCondition, position,
                    EdgeKind.TARGET, seen);
            edgeAndSnapshot(from, enclose.target, position,
                    EdgeKind.TARGET, seen);
        }
        case NodeType.ANCHOR -> edgeAndSnapshot(from,
                ((AnchorNode)node).target, position, EdgeKind.TARGET, seen);
        case NodeType.CALL -> edgeAndSnapshot(from, ((CallNode)node).target,
                position, EdgeKind.CALL_TARGET, seen);
        default -> {
        }
        }
    }

    private void edgeAndSnapshot(int from, Node target, int bytePosition,
            EdgeKind edgeKind, IdentityHashMap<Node, Boolean> seen) {
        if (target == null) return;
        int to = node(target, nodePositions.getOrDefault(target, bytePosition),
                kind(target));
        events.add(new ParseDebugEvent.Edge(bytePosition, depth,
                from, to, edgeKind));
        snapshotTree(target, seen);
    }

    private static NodeKind kind(Node node) {
        return switch (node.getType()) {
        case NodeType.STR -> NodeKind.EXACT;
        case NodeType.BREF -> NodeKind.REFERENCE;
        case NodeType.CALL -> NodeKind.CALL;
        case NodeType.ENCLOSE -> ((EncloseNode)node).isMemory()
                ? NodeKind.OPEN : NodeKind.OTHER;
        default -> NodeKind.OTHER;
        };
    }
}

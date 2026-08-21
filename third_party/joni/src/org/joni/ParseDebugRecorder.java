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

import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.ParseDebugEvent.EdgeKind;
import org.joni.ParseDebugEvent.NodeKind;
import org.joni.ParseDebugEvent.PhaseKind;
import org.joni.ParseDebugEvent.ProgramKind;
import org.joni.ast.AnchorNode;
import org.joni.ast.BackRefNode;
import org.joni.ast.CallNode;
import org.joni.ast.CalloutNode;
import org.joni.ast.EncloseNode;
import org.joni.ast.ListNode;
import org.joni.ast.Node;
import org.joni.ast.QuantifierNode;
import org.joni.ast.StringNode;
import org.joni.constants.internal.NodeType;

/** Mutable parser-local builder; only immutable snapshots leave Analyser. */
final class ParseDebugRecorder {
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private static final Scope NOOP_SCOPE = () -> {};
    private static final ParseDebugRecorder DISABLED =
            new ParseDebugRecorder(false);

    private final boolean enabled;
    private final List<ParseDebugEvent> events;
    private final IdentityHashMap<Node, Integer> nodeIds;
    private final IdentityHashMap<Node, Integer> nodePositions;
    private final IdentityHashMap<Node, Boolean> programmedNodes;
    private final Map<Integer, String> captureNames;
    private final List<EncloseNode> openCaptures;
    private final List<ParseDebugTrace.Pass> passes;
    private final Encoding encoding;
    private int depth;
    private int nextNodeId = 1;
    private int firstNodeId;
    private NodeKind firstNodeKind = NodeKind.OTHER;
    private int lastStructuralNodeId;
    private int nextProgramPosition = 1;
    private int firstConsumingPosition;
    private boolean firstPassFrozen;
    private boolean validationReparsed;

    ParseDebugRecorder() {
        this(true, UTF8Encoding.INSTANCE);
    }

    private ParseDebugRecorder(boolean enabled) {
        this(enabled, UTF8Encoding.INSTANCE);
    }

    private ParseDebugRecorder(boolean enabled, Encoding encoding) {
        this.enabled = enabled;
        this.encoding = encoding;
        events = enabled ? new ArrayList<>() : null;
        nodeIds = enabled ? new IdentityHashMap<>() : null;
        nodePositions = enabled ? new IdentityHashMap<>() : null;
        programmedNodes = enabled ? new IdentityHashMap<>() : null;
        captureNames = enabled ? new java.util.HashMap<>() : null;
        openCaptures = enabled ? new ArrayList<>() : null;
        passes = enabled ? new ArrayList<>() : null;
    }

    static ParseDebugRecorder enabled(boolean enabled, Encoding encoding) {
        return enabled ? new ParseDebugRecorder(true, encoding) : DISABLED;
    }

    Scope phase(PhaseKind phase, int bytePosition) {
        if (!enabled) return NOOP_SCOPE;
        int eventDepth = depth++;
        events.add(new ParseDebugEvent.Phase(bytePosition, eventDepth, phase, true));
        return () -> {
            depth = Math.max(0, depth - 1);
            events.add(new ParseDebugEvent.Phase(
                    bytePosition, eventDepth, phase, false));
        };
    }

    void captureOpen(EncloseNode node, int bytePosition, String name) {
        if (!enabled) return;
        int nodeId = node(node, bytePosition, NodeKind.OPEN);
        String stableName = name == null ? "" : name;
        captureNames.put(node.regNum, stableName);
        openCaptures.add(node);
        events.add(new ParseDebugEvent.Capture(bytePosition, depth, nodeId,
                true, node.regNum, stableName));
        appendProgram(nodeId, bytePosition, ProgramKind.OPEN, node.regNum,
                stableName, "", true, 2);
    }

    void captureClose(EncloseNode node, int bytePosition) {
        if (!enabled) return;
        programMissing(node.target);
        int nodeId = node(node, bytePosition, NodeKind.CLOSE);
        events.add(new ParseDebugEvent.Capture(bytePosition, depth, nodeId,
                false, node.regNum, captureNames.getOrDefault(node.regNum, "")));
        if (lastStructuralNodeId != 0 && lastStructuralNodeId != nodeId) {
            events.add(new ParseDebugEvent.Edge(bytePosition, depth,
                    lastStructuralNodeId, nodeId, EdgeKind.CAPTURE_CLOSE));
        }
        lastStructuralNodeId = nodeId;
        appendProgram(nodeId, bytePosition, ProgramKind.CLOSE, node.regNum,
                captureNames.getOrDefault(node.regNum, ""), "", true, 2);
        for (int index = openCaptures.size() - 1; index >= 0; index--) {
            if (openCaptures.get(index) == node) {
                openCaptures.remove(index);
                break;
            }
        }
    }

    void reference(BackRefNode node, int bytePosition, String name) {
        if (!enabled) return;
        int nodeId = node(node, bytePosition, NodeKind.REFERENCE);
        events.add(referenceEvent(node, nodeId, bytePosition, depth, name));
        lastStructuralNodeId = nodeId;
        programmedNodes.put(node, Boolean.TRUE);
        appendProgram(nodeId, bytePosition, ProgramKind.REFERENCE,
                node.backNum == 0 ? 0 : node.back[0], name, "",
                node.backNum != 0, 3);
    }

    void call(CallNode node, int bytePosition) {
        if (!enabled) return;
        int nodeId = node(node, bytePosition, NodeKind.CALL);
        programmedNodes.put(node, Boolean.TRUE);
        String name = new String(node.name, node.nameP,
                node.nameEnd - node.nameP, encoding.getCharset());
        appendProgram(nodeId, bytePosition, ProgramKind.CALL,
                Math.max(0, node.groupNum), name, "", true, 3);
    }

    <T extends Node> T accepted(T node, int bytePosition) {
        if (!enabled || node == null || node == StringNode.EMPTY) return node;
        nodePositions.putIfAbsent(node, bytePosition);
        if (programmedNodes.containsKey(node)) return node;
        if (node instanceof CalloutNode) {
            int nodeId = node(node, bytePosition, NodeKind.OTHER);
            programmedNodes.put(node, Boolean.TRUE);
            appendProgram(nodeId, bytePosition, ProgramKind.CALLOUT, 0,
                    "", "", true, 3);
        } else if (node instanceof StringNode string && string.length() > 0) {
            int nodeId = node(node, bytePosition, NodeKind.EXACT);
            programmedNodes.put(node, Boolean.TRUE);
            String literal = new String(string.bytes, string.p,
                    string.end - string.p, encoding.getCharset());
            appendProgram(nodeId, bytePosition, ProgramKind.EXACT, 0,
                    "", literal, true, 2);
        }
        return node;
    }

    void freezeFirstPass(Node root) {
        if (!enabled) return;
        if (firstPassFrozen) return;
        programMissing(root);
        appendProgram(node(root, nodePositions.getOrDefault(root, 0), kind(root)),
                nodePositions.getOrDefault(root, 0), ProgramKind.END, 0,
                "", "", true, 1);
        events.add(new ParseDebugEvent.Phase(0, 0,
                PhaseKind.LAST_BRANCH, true));
        snapshotTree(root, new IdentityHashMap<>());
        events.add(new ParseDebugEvent.Phase(0, 0,
                PhaseKind.LAST_BRANCH, false));
        passes.add(pass(List.copyOf(events)));
        firstPassFrozen = true;
    }

    void freezeResolvedPass(Node root) {
        if (!enabled) return;
        freezeFirstPass(root);
        refreshFrozenCalls();
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
            if (event instanceof ParseDebugEvent.Program program
                    && program.kind() == ProgramKind.REFERENCE) {
                Node node = nodeForId(program.nodeId());
                if (node instanceof BackRefNode backRef) {
                    resolved.add(new ParseDebugEvent.Program(
                            program.bytePosition(), program.depth(),
                            program.nodeId(), program.programPosition(),
                            program.kind(), backRef.backNum == 0
                                    ? 0 : backRef.back[0],
                            program.name(), program.literal(),
                            backRef.backNum != 0));
                    continue;
                }
            }
            if (event instanceof ParseDebugEvent.Program program
                    && program.kind() == ProgramKind.CALL) {
                Node node = nodeForId(program.nodeId());
                if (node instanceof CallNode call) {
                    resolved.add(new ParseDebugEvent.Program(
                            program.bytePosition(), program.depth(),
                            program.nodeId(), program.programPosition(),
                            program.kind(), Math.max(0, call.groupNum),
                            program.name(), program.literal(),
                            call.groupNum > 0));
                    continue;
                }
            }
            resolved.add(event);
        }
        passes.add(pass(List.copyOf(resolved)));
    }

    private void refreshFrozenCalls() {
        ParseDebugTrace.Pass first = passes.get(0);
        List<ParseDebugEvent> refreshed = new ArrayList<>(
                first.events().size());
        for (ParseDebugEvent event : first.events()) {
            if (event instanceof ParseDebugEvent.Program program
                    && program.kind() == ProgramKind.CALL) {
                Node node = nodeForId(program.nodeId());
                if (node instanceof CallNode call) {
                    refreshed.add(new ParseDebugEvent.Program(
                            program.bytePosition(), program.depth(),
                            program.nodeId(), program.programPosition(),
                            program.kind(), Math.max(0, call.groupNum),
                            program.name(), program.literal(),
                            call.groupNum > 0));
                    continue;
                }
            }
            refreshed.add(event);
        }
        passes.set(0, new ParseDebugTrace.Pass(refreshed,
                first.nodeCount(), first.firstNodeId(), first.firstNodeKind(),
                first.programSize(), first.firstConsumingPosition()));
    }

    /** Complete only the logical display chain after a parser failure. */
    void freezeFailurePrefix(int bytePosition) {
        if (!enabled || firstPassFrozen || events.isEmpty()) return;
        for (int index = openCaptures.size() - 1; index >= 0; index--) {
            EncloseNode capture = openCaptures.get(index);
            int nodeId = nodeIds.getOrDefault(capture, lastStructuralNodeId);
            appendProgram(nodeId, bytePosition, ProgramKind.CLOSE,
                    capture.regNum,
                    captureNames.getOrDefault(capture.regNum, ""),
                    "", true, 2);
        }
        int endNodeId = lastStructuralNodeId != 0
                ? lastStructuralNodeId : firstNodeId;
        appendProgram(endNodeId, bytePosition, ProgramKind.END, 0,
                "", "", true, 1);
        passes.add(pass(List.copyOf(events)));
        firstPassFrozen = true;
    }

    ParseDebugTrace snapshot() {
        if (!enabled) return ParseDebugTrace.EMPTY;
        if (!firstPassFrozen && !events.isEmpty()) {
            passes.add(pass(List.copyOf(events)));
            firstPassFrozen = true;
        }
        if (passes.isEmpty()) return ParseDebugTrace.EMPTY;
        return new ParseDebugTrace(List.copyOf(passes), validationReparsed);
    }

    private ParseDebugTrace.Pass pass(List<ParseDebugEvent> stableEvents) {
        return new ParseDebugTrace.Pass(stableEvents, nodeIds.size(),
                firstNodeId, firstNodeKind, nextProgramPosition - 1,
                firstConsumingPosition);
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
        return nodeId;
    }

    private void appendProgram(int nodeId, int bytePosition, ProgramKind kind,
            int number, String name, String literal, boolean resolved,
            int width) {
        int position = nextProgramPosition;
        nextProgramPosition += width;
        if (firstConsumingPosition == 0
                && (kind == ProgramKind.EXACT
                        || kind == ProgramKind.REFERENCE
                        || kind == ProgramKind.CALLOUT)) {
            firstConsumingPosition = position;
        }
        events.add(new ParseDebugEvent.Program(bytePosition, depth, nodeId,
                position, kind, number, name, literal, resolved));
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

    private void programMissing(Node node) {
        if (node == null || node == StringNode.EMPTY) return;
        switch (node.getType()) {
        case NodeType.LIST, NodeType.ALT -> {
            ListNode list = (ListNode)node;
            programMissing(list.value);
            programMissing(list.tail);
        }
        case NodeType.ENCLOSE -> {
            EncloseNode enclose = (EncloseNode)node;
            programMissing(enclose.target);
        }
        case NodeType.QTFR -> programMissing(((QuantifierNode)node).target);
        case NodeType.ANCHOR -> programMissing(((AnchorNode)node).target);
        default -> accepted(node, nodePositions.getOrDefault(node, 0));
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

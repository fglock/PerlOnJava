package org.perlonjava.codegen;

import org.perlonjava.astnode.Node;

import java.util.HashMap;
import java.util.Map;

public class TempSlotPlan {
    public enum TempKind {
        INT,
        REF
    }

    private record Key(int nodeIndex, String nodeClass, String name, TempKind kind) {
    }

    public record SlotInfo(int slot, TempKind kind) {
    }

    private final Map<Key, SlotInfo> assignedSlots = new HashMap<>();

    public int getOrAssign(Node node, String name, TempKind kind, EmitterContext ctx) {
        if (node == null) {
            // Fallback: not associated with an AST node (should be rare).
            return allocate(kind, ctx);
        }
        Key key = new Key(node.getIndex(), node.getClass().getName(), name, kind);
        SlotInfo slot = assignedSlots.get(key);
        if (slot != null) {
            return slot.slot;
        }
        int newSlot = allocate(kind, ctx);
        assignedSlots.put(key, new SlotInfo(newSlot, kind));
        return newSlot;
    }

    public Iterable<SlotInfo> allAssignedSlots() {
        return assignedSlots.values();
    }

    private static int allocate(TempKind kind, EmitterContext ctx) {
        // For now, reuse symbol table allocator; key property is consistency of reuse for a given node+temp name.
        // Future improvement: split INT/REF pools.
        return ctx.symbolTable.allocateLocalVariable();
    }
}

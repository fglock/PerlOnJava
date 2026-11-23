package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Test-only diagnostic traversal for temporary-root owner-path assertions. */
final class ReachabilityWalker {
    static final int MAX_ROOT_SNAPSHOT_EDGES = 50_000;

    static final class RootReachabilitySnapshot {
        private final Set<RuntimeBase> reachable;
        private final boolean complete;
        private final int edgesVisited;

        private RootReachabilitySnapshot(Set<RuntimeBase> reachable,
                                         boolean complete,
                                         int edgesVisited) {
            this.reachable = reachable;
            this.complete = complete;
            this.edgesVisited = edgesVisited;
        }

        boolean complete() {
            return complete;
        }

        boolean isReachable(RuntimeBase target) {
            return target != null && (reachable.contains(target) || !complete);
        }

        int edgesVisited() {
            return edgesVisited;
        }
    }

    static boolean isReachableFromTemporaryRoots(RuntimeBase target) {
        if (target == null) return false;
        ArrayList<RuntimeBase> roots = new ArrayList<>();
        for (LifecycleRuntimeState.TemporaryRootLease lease
                : PerlRuntime.current().lifecycleState.temporaryRoots) {
            roots.add(lease.root());
        }
        return walk(roots).isReachable(target);
    }

    static RootReachabilitySnapshot reachableFromRootsSnapshotWithStatus() {
        ArrayList<RuntimeBase> roots = new ArrayList<>();
        roots.addAll(GlobalVariable.globalVariables.values());
        roots.addAll(GlobalVariable.globalArrays.values());
        roots.addAll(GlobalVariable.globalHashes.values());
        for (LifecycleRuntimeState.TemporaryRootLease lease
                : PerlRuntime.current().lifecycleState.temporaryRoots) {
            roots.add(lease.root());
        }
        return walk(roots);
    }

    private static RootReachabilitySnapshot walk(List<RuntimeBase> roots) {
        Set<RuntimeBase> reachable =
                Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<RuntimeBase> pending = new ArrayDeque<>();
        for (RuntimeBase root : roots) {
            if (root != null) pending.addLast(root);
        }

        int edgesVisited = 0;
        boolean complete = true;
        while (!pending.isEmpty() && complete) {
            RuntimeBase current = pending.removeFirst();
            if (current == null || !reachable.add(current)) continue;

            ArrayList<RuntimeBase> children = childrenOf(current);
            for (RuntimeBase child : children) {
                if (child == null) continue;
                if (edgesVisited == MAX_ROOT_SNAPSHOT_EDGES) {
                    complete = false;
                    break;
                }
                edgesVisited++;
                pending.addLast(child);
            }
        }
        return new RootReachabilitySnapshot(reachable, complete, edgesVisited);
    }

    private static ArrayList<RuntimeBase> childrenOf(RuntimeBase value) {
        ArrayList<RuntimeBase> children = new ArrayList<>();
        if (value instanceof RuntimeScalar scalar) {
            if (!WeakRefRegistry.isweak(scalar)
                    && (scalar.type & RuntimeScalarType.REFERENCE_BIT) != 0
                    && scalar.value instanceof RuntimeBase referent) {
                children.add(referent);
            }
            if (scalar instanceof RuntimeBaseProxy proxy && proxy.lvalue != null) {
                children.add(proxy.lvalue);
            }
        }
        if (value instanceof RuntimeArray array) {
            if (array.elements != null) children.addAll(array.elements);
            if (array.ownedAliasElements != null) children.addAll(array.ownedAliasElements);
        } else if (value instanceof RuntimeHash hash) {
            if (hash.elements != null && !(hash.elements instanceof HashSpecialVariable)) {
                for (java.util.Map.Entry<String, RuntimeScalar> entry
                        : hash.elements.entrySet()) {
                    children.add(entry.getValue());
                }
            }
            if (hash.elements instanceof TieHash tieHash) children.add(tieHash.getSelf());
        } else if (value instanceof RuntimeList list) {
            if (list instanceof RuntimeControlFlowList flow) {
                children.add(flow.getReturnValue());
            }
            children.addAll(list.elements);
        } else if (value instanceof RuntimeCode code) {
            if (code.capturedScalars != null) {
                Collections.addAll(children, code.capturedScalars);
            }
            if (code.capturedAggregates != null) {
                Collections.addAll(children, code.capturedAggregates);
            }
            if (code.stateVariable != null) children.addAll(code.stateVariable.values());
            if (code.stateArray != null) children.addAll(code.stateArray.values());
            if (code.stateHash != null) children.addAll(code.stateHash.values());
            if (code.padConstants != null) Collections.addAll(children, code.padConstants);
        }
        return children;
    }
}

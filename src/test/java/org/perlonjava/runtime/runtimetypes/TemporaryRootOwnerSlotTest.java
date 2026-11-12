package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TemporaryRootOwnerSlotTest {
    private static final class CountingHashValues extends AbstractMap<String, RuntimeScalar> {
        private final RuntimeScalar value;
        private int valuesCalls;

        private CountingHashValues() {
            this(null);
        }

        private CountingHashValues(RuntimeScalar value) {
            this.value = value;
        }

        @Override
        public Set<Entry<String, RuntimeScalar>> entrySet() {
            return Collections.emptySet();
        }

        @Override
        public Collection<RuntimeScalar> values() {
            valuesCalls++;
            return value == null ? Collections.emptyList() : List.of(value);
        }
    }

    @Test
    void duplicateAndNonLifoPopsReleaseTheMatchingRootLeases() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash first = new RuntimeHash();
            RuntimeHash second = new RuntimeHash();

            MortalList.pushTemporaryRoot(first);
            MortalList.pushTemporaryRoot(first);
            MortalList.pushTemporaryRoot(second);

            assertEquals(2, first.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));
            assertEquals(1, second.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));
            assertEquals(List.of(second, first, first), MortalList.snapshotTemporaryRoots());
            assertTrue(MortalList.temporaryRootDirectlyReferences(first));

            MortalList.popTemporaryRoot(first);
            assertEquals(1, first.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));
            assertEquals(List.of(second, first), MortalList.snapshotTemporaryRoots());

            MortalList.popTemporaryRoot(second);
            MortalList.popTemporaryRoot(first);
            assertEquals(0, first.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));
            assertEquals(0, second.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));
            assertEquals(List.of(), MortalList.snapshotTemporaryRoots());
            assertFalse(MortalList.temporaryRootDirectlyReferences(first));
        }
    }

    @Test
    void depthRestoreReleasesEveryLeaseAddedAfterTheSavedBoundary() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash callerRoot = new RuntimeHash();
            RuntimeHash failedCallRoot = new RuntimeHash();
            MortalList.pushTemporaryRoot(callerRoot);
            int depth = MortalList.temporaryRootDepth();

            try {
                MortalList.pushTemporaryRoot(failedCallRoot);
                MortalList.pushTemporaryRoot(failedCallRoot);
                throw new IllegalStateException("synthetic failed call");
            } catch (IllegalStateException expected) {
                MortalList.restoreTemporaryRootDepth(depth);
            }

            assertEquals(List.of(callerRoot), MortalList.snapshotTemporaryRoots());
            assertEquals(1, callerRoot.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));
            assertEquals(0, failedCallRoot.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));

            MortalList.popTemporaryRoot(callerRoot);
            assertEquals(0, callerRoot.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));
        }
    }

    @Test
    void clearingRuntimeStateReleasesAllTemporaryRootLeases() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash root = new RuntimeHash();
            MortalList.pushTemporaryRoot(root);
            MortalList.pushTemporaryRoot(root);

            assertEquals(2, root.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));
            MortalList.clearTemporaryRoots();
            assertEquals(0, root.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));

            MortalList.pushTemporaryRoot(root);
            MortalList.clearCurrentRuntimeState();

            assertEquals(0, root.ownerSlotCount(PerlOwnerSlot.Kind.TEMPORARY_ROOT));
            assertEquals(List.of(), MortalList.snapshotTemporaryRoots());
        }
    }

    @Test
    void scalarTemporaryRootMatchesOnlyItsDirectStrongReferent() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar reference = referent.createAnonymousReference();
            RuntimeHash unrelated = new RuntimeHash();

            MortalList.pushTemporaryRoot(reference);
            try {
                assertTrue(MortalList.temporaryRootDirectlyReferences(referent));
                assertFalse(MortalList.temporaryRootDirectlyReferences(unrelated));
            } finally {
                MortalList.popTemporaryRoot(reference);
            }
        }
    }

    @Test
    void arrayTemporaryRootMatchesDirectStrongElements() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar reference = referent.createAnonymousReference();
            RuntimeHash unrelated = new RuntimeHash();
            RuntimeArray root = new RuntimeArray();
            root.elements.add(reference);

            MortalList.pushTemporaryRoot(root);
            try {
                assertTrue(MortalList.temporaryRootDirectlyReferences(referent));
                assertFalse(MortalList.temporaryRootDirectlyReferences(unrelated));
            } finally {
                MortalList.popTemporaryRoot(root);
            }
        }
    }

    @Test
    void directArrayTemporaryRootEdgeSkipsReferentGraphWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            CountingHashValues nestedValues = new CountingHashValues();
            referent.elements = nestedValues;
            RuntimeArray root = new RuntimeArray();
            root.elements.add(referent.createReference());

            MortalList.pushTemporaryRoot(root);
            try {
                assertTrue(ReachabilityWalker.isReachableFromTemporaryRoots(referent));
                assertEquals(0, nestedValues.valuesCalls,
                        "a direct root edge must not traverse the referent's hash descendants");
            } finally {
                MortalList.popTemporaryRoot(root);
            }
        }
    }

    @Test
    void directHashTemporaryRootEdgeSkipsReferentGraphWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            CountingHashValues nestedValues = new CountingHashValues();
            referent.elements = nestedValues;
            RuntimeHash root = new RuntimeHash();
            root.elements.put("child", referent.createReference());

            MortalList.pushTemporaryRoot(root);
            try {
                assertTrue(ReachabilityWalker.isReachableFromTemporaryRoots(referent));
                assertEquals(0, nestedValues.valuesCalls,
                        "a direct hash-root edge must not traverse the referent's descendants");
            } finally {
                MortalList.popTemporaryRoot(root);
            }
        }
    }

    @Test
    void directListTemporaryRootEdgeSkipsNestedAggregateGraphWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeHash nested = new RuntimeHash();
            CountingHashValues nestedValues = new CountingHashValues(target.createReference());
            nested.elements = nestedValues;
            RuntimeList root = new RuntimeList();
            root.elements.add(nested.createReference());

            MortalList.pushTemporaryRoot(root);
            try {
                assertFalse(MortalList.temporaryRootDirectlyReferences(target));
                assertEquals(0, nestedValues.valuesCalls,
                        "a direct list-root check must not enumerate nested aggregate values");
                assertTrue(ReachabilityWalker.isReachableFromTemporaryRoots(target));
                assertTrue(nestedValues.valuesCalls > 0,
                        "the compatibility reachability query still follows nested values");
            } finally {
                MortalList.popTemporaryRoot(root);
            }
        }
    }

    @Test
    void nestedOrdinaryOwnerAncestryProvesTemporaryRootWithoutGraphWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeArray nested = new RuntimeArray();
            nested.elements.add(target.createReference());
            RuntimeHash parent = new RuntimeHash();
            parent.elements.put("nested", nested.createReference());

            RuntimeHash opaque = new RuntimeHash();
            CountingHashValues opaqueValues = new CountingHashValues();
            opaque.elements = opaqueValues;
            RuntimeArray root = new RuntimeArray();
            root.elements.add(parent.createReference());
            root.elements.add(opaque.createReference());

            MortalList.pushTemporaryRoot(root);
            try {
                assertTrue(ReachabilityWalker.isReachableFromTemporaryRoots(target));
                assertEquals(0, opaqueValues.valuesCalls,
                        "current array/hash owner cells should prove the path without BFS");
            } finally {
                MortalList.popTemporaryRoot(root);
                root.elements.clear();
                parent.elements.clear();
                nested.elements.clear();
            }
        }
    }

    @Test
    void excessiveOwnerSetFallsBackToTemporaryRootGraphWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeArray root = new RuntimeArray();
            CountingHashValues sideBranchValues = new CountingHashValues();

            for (int i = 0; i < 65; i++) {
                RuntimeArray child = new RuntimeArray();
                child.elements.add(target.createReference());
                if (i == 0) {
                    RuntimeHash sideBranch = new RuntimeHash();
                    sideBranch.elements = sideBranchValues;
                    child.elements.add(sideBranch.createReference());
                }
                root.elements.add(child.createReference());
            }

            MortalList.pushTemporaryRoot(root);
            try {
                assertTrue(ReachabilityWalker.isReachableFromTemporaryRoots(target));
                assertTrue(sideBranchValues.valuesCalls > 0,
                        "owner-snapshot overflow must retain the established BFS");
            } finally {
                MortalList.popTemporaryRoot(root);
            }
        }
    }
}

package org.perlonjava.runtime.runtimetypes;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TemporaryRootCountedCellOwnerTest {
    private static final class CountingHashValues extends AbstractMap<String, RuntimeScalar> {
        private int valuesCalls;

        @Override
        public Set<Entry<String, RuntimeScalar>> entrySet() {
            return Collections.emptySet();
        }

        @Override
        public Collection<RuntimeScalar> values() {
            valuesCalls++;
            return List.of();
        }
    }

    @Test
    void currentScalarCellOwnerProvesTemporaryRootWithoutGraphWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeScalar scalarOwner = new RuntimeScalar();
            scalarOwner.localBindingExists = true;
            scalarOwner.set(target.createReference());

            CountingHashValues opaqueValues = new CountingHashValues();
            RuntimeHash opaque = new RuntimeHash();
            opaque.elements = opaqueValues;
            RuntimeArray root = new RuntimeArray();
            root.localBindingExists = true;
            // Put the opaque sibling first so a fallback BFS would enumerate
            // it before reaching the owner cell. The owner-slot proof must
            // return without opening that graph.
            root.elements.add(opaque.createReference());
            root.elements.add(scalarOwner.createReference());

            MortalList.pushTemporaryRoot(root);
            try {
                PerlOwnerSlot scalarCellOwner = scalarOwner.countedOwnerSlot();
                assertEquals(PerlOwnerSlot.Kind.SCALAR_CELL, scalarCellOwner.kind());
                assertTrue(DestroyDispatch.isCurrentPerlOwnerSlot(target, scalarCellOwner),
                        "the scalar cell must be the current counted owner of the target");
                assertFalse(MortalList.temporaryRootDirectlyReferences(target),
                        "the root points to the scalar cell, not directly to its value");
                assertTrue(MortalList.temporaryRootDirectlyReferences(scalarOwner),
                        "the root directly references the current scalar cell");

                assertTrue(ReachabilityWalker.isReachableFromTemporaryRoots(target));
                assertEquals(0, opaqueValues.valuesCalls,
                        "the validated scalar-cell path must avoid the fallback graph walk");
            } finally {
                MortalList.popTemporaryRoot(root);
                root.elements.clear();
                root.localBindingExists = false;
                MortalList.scopeExitCleanupArray(root);
                scalarOwner.localBindingExists = false;
                RuntimeScalar.scopeExitCleanup(scalarOwner);
            }
        }
    }

    @Test
    void currentCodeSlotOwnerProvesTemporaryRootWithoutGraphWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String name = "TemporaryRootCountedCellOwnerTest::code";
            RuntimeCode code = new RuntimeCode(null, List.of());
            RuntimeScalar codeSlot = new RuntimeScalar(code);
            GlobalVariable.globalCodeRefs.put(name, codeSlot);

            CountingHashValues opaqueValues = new CountingHashValues();
            RuntimeHash opaque = new RuntimeHash();
            opaque.elements = opaqueValues;
            RuntimeArray root = new RuntimeArray();
            root.localBindingExists = true;
            root.elements.add(opaque.createReference());
            root.elements.add(codeSlot.createReference());

            MortalList.pushTemporaryRoot(root);
            try {
                PerlOwnerSlot codeOwner = code.ownerSlotSnapshot().stream()
                        .filter(slot -> slot.kind() == PerlOwnerSlot.Kind.CODE_SLOT)
                        .findFirst().orElseThrow();
                assertEquals(PerlOwnerSlot.Kind.CODE_SLOT, codeOwner.kind());
                assertTrue(DestroyDispatch.isCurrentPerlOwnerSlot(code, codeOwner),
                        "the stash scalar must be the current CODE slot owner");
                assertFalse(MortalList.temporaryRootDirectlyReferences(code),
                        "the root points to the CODE slot cell, not directly to the CV");
                assertTrue(MortalList.temporaryRootDirectlyReferences(codeSlot),
                        "the root directly references the CODE slot cell");

                assertTrue(ReachabilityWalker.isReachableFromTemporaryRoots(code));
                assertEquals(0, opaqueValues.valuesCalls,
                        "the validated CODE-slot path must avoid the fallback graph walk");
            } finally {
                MortalList.popTemporaryRoot(root);
                root.elements.clear();
                root.localBindingExists = false;
                MortalList.scopeExitCleanupArray(root);
                GlobalVariable.globalCodeRefs.remove(name);
            }
        }
    }

    @Test
    void currentTieHandlerOwnerProvesTemporaryRootWithoutGraphWalk() {
        assertTieHandlerOwnerProvesTemporaryRoot(PerlOwnerSlot.Kind.TIE_HANDLER);
    }

    @Test
    void currentArgumentTieHandlerOwnerProvesTemporaryRootWithoutGraphWalk() {
        assertTieHandlerOwnerProvesTemporaryRoot(PerlOwnerSlot.Kind.ARGUMENT_TIE_HANDLER);
    }

    @Test
    void currentArgumentContainerOwnerProvesTemporaryRootWithoutGraphWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            target.refCount = 0;
            RuntimeScalar containerOwner = RuntimeScalar.retainArgumentContainerValue(target);

            CountingHashValues opaqueValues = new CountingHashValues();
            RuntimeHash opaque = new RuntimeHash();
            opaque.elements = opaqueValues;
            RuntimeArray root = new RuntimeArray();
            root.localBindingExists = true;
            root.elements.add(opaque.createReference());
            root.elements.add(containerOwner.createReference());

            MortalList.pushTemporaryRoot(root);
            try {
                PerlOwnerSlot ownerSlot = target.ownerSlotSnapshot().stream()
                        .filter(slot -> slot.kind() == PerlOwnerSlot.Kind.ARGUMENT_CONTAINER)
                        .findFirst().orElseThrow();
                assertTrue(DestroyDispatch.isCurrentPerlOwnerSlot(target, ownerSlot),
                        "the argument-container owner must be current");
                assertFalse(MortalList.temporaryRootDirectlyReferences(target),
                        "the root points to the argument-container cell, not its value");
                assertTrue(MortalList.temporaryRootDirectlyReferences(containerOwner),
                        "the root directly references the argument-container cell");

                assertTrue(ReachabilityWalker.isReachableFromTemporaryRoots(target));
                assertEquals(0, opaqueValues.valuesCalls,
                        "the validated argument-container path must avoid the fallback graph walk");
            } finally {
                MortalList.popTemporaryRoot(root);
                root.elements.clear();
                root.localBindingExists = false;
                MortalList.scopeExitCleanupArray(root);
                RuntimeScalar.releaseArgumentContainerValue(containerOwner);
            }
        }
    }

    private void assertTieHandlerOwnerProvesTemporaryRoot(PerlOwnerSlot.Kind kind) {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeScalar handlerSource = target.createAnonymousReference();
            RuntimeScalar handlerOwner = RuntimeScalar.retainTiedHandlerValue(handlerSource, kind);

            CountingHashValues opaqueValues = new CountingHashValues();
            RuntimeHash opaque = new RuntimeHash();
            opaque.elements = opaqueValues;
            RuntimeArray root = new RuntimeArray();
            root.localBindingExists = true;
            root.elements.add(opaque.createReference());
            root.elements.add(handlerOwner.createReference());

            MortalList.pushTemporaryRoot(root);
            try {
                PerlOwnerSlot handlerSlot = target.ownerSlotSnapshot().stream()
                        .filter(slot -> slot.kind() == kind)
                        .findFirst().orElseThrow();
                assertTrue(DestroyDispatch.isCurrentPerlOwnerSlot(target, handlerSlot),
                        "the tied-handler owner must be current");
                assertFalse(MortalList.temporaryRootDirectlyReferences(target),
                        "the root points to the tied-handler cell, not its value");
                assertTrue(MortalList.temporaryRootDirectlyReferences(handlerOwner),
                        "the root directly references the tied-handler cell");

                assertTrue(ReachabilityWalker.isReachableFromTemporaryRoots(target));
                assertEquals(0, opaqueValues.valuesCalls,
                        "the validated tied-handler path must avoid the fallback graph walk");
            } finally {
                MortalList.popTemporaryRoot(root);
                root.elements.clear();
                root.localBindingExists = false;
                MortalList.scopeExitCleanupArray(root);
                RuntimeScalar.releaseTiedHandlerValue(handlerOwner);
            }
        }
    }
}

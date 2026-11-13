package org.perlonjava.runtime.runtimetypes;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TemporaryRootCountedCellOwnerTest {
    @Test
    void currentScalarCellOwnerHasCurrentOwnerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeScalar scalarOwner = new RuntimeScalar();
            scalarOwner.localBindingExists = true;
            scalarOwner.set(target.createReference());
            RuntimeArray root = new RuntimeArray();
            root.localBindingExists = true;
            // Put the opaque sibling first so a fallback BFS would enumerate
            // it before reaching the owner cell. The owner-slot proof must
            // return without opening that graph.
            root.elements.add(scalarOwner.createReference());

            MortalList.pushTemporaryRoot(root);
            try {
                PerlOwnerSlot scalarCellOwner = scalarOwner.countedOwnerSlot();
                assertEquals(PerlOwnerSlot.Kind.SCALAR_CELL, scalarCellOwner.kind());
                assertTrue(DestroyDispatch.isCurrentPerlOwnerSlot(target, scalarCellOwner),
                        "the scalar cell must be the current counted owner of the target");

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
    void currentCodeSlotOwnerHasCurrentOwnerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String name = "TemporaryRootCountedCellOwnerTest::code";
            RuntimeCode code = new RuntimeCode(null, List.of());
            RuntimeScalar codeSlot = new RuntimeScalar(code);
            GlobalVariable.globalCodeRefs.put(name, codeSlot);
            RuntimeArray root = new RuntimeArray();
            root.localBindingExists = true;
            root.elements.add(codeSlot.createReference());

            MortalList.pushTemporaryRoot(root);
            try {
                PerlOwnerSlot codeOwner = code.ownerSlotSnapshot().stream()
                        .filter(slot -> slot.kind() == PerlOwnerSlot.Kind.CODE_SLOT)
                        .findFirst().orElseThrow();
                assertEquals(PerlOwnerSlot.Kind.CODE_SLOT, codeOwner.kind());
                assertTrue(DestroyDispatch.isCurrentPerlOwnerSlot(code, codeOwner),
                        "the stash scalar must be the current CODE slot owner");

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
    void currentTieHandlerOwnerHasCurrentOwnerSlot() {
        assertTieHandlerOwnerProvesTemporaryRoot(PerlOwnerSlot.Kind.TIE_HANDLER);
    }

    @Test
    void currentArgumentTieHandlerOwnerHasCurrentOwnerSlot() {
        assertTieHandlerOwnerProvesTemporaryRoot(PerlOwnerSlot.Kind.ARGUMENT_TIE_HANDLER);
    }

    @Test
    void currentArgumentContainerOwnerHasCurrentOwnerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            target.refCount = 0;
            RuntimeScalar containerOwner = RuntimeScalar.retainArgumentContainerValue(target);
            RuntimeArray root = new RuntimeArray();
            root.localBindingExists = true;
            root.elements.add(containerOwner.createReference());

            MortalList.pushTemporaryRoot(root);
            try {
                PerlOwnerSlot ownerSlot = target.ownerSlotSnapshot().stream()
                        .filter(slot -> slot.kind() == PerlOwnerSlot.Kind.ARGUMENT_CONTAINER)
                        .findFirst().orElseThrow();
                assertTrue(DestroyDispatch.isCurrentPerlOwnerSlot(target, ownerSlot),
                        "the argument-container owner must be current");

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
            RuntimeArray root = new RuntimeArray();
            root.localBindingExists = true;
            root.elements.add(handlerOwner.createReference());

            MortalList.pushTemporaryRoot(root);
            try {
                PerlOwnerSlot handlerSlot = target.ownerSlotSnapshot().stream()
                        .filter(slot -> slot.kind() == kind)
                        .findFirst().orElseThrow();
                assertTrue(DestroyDispatch.isCurrentPerlOwnerSlot(target, handlerSlot),
                        "the tied-handler owner must be current");

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

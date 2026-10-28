package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class ArgumentAliasHandoffOwnerSlotTest {
    @Test
    void queuedArgumentReleaseKeepsExplicitFrameIdentityAcrossCopyButNotThreadClone() {
        PerlRuntime sourceRuntime = new PerlRuntime();
        PerlRuntime childRuntime = new PerlRuntime();
        RuntimeScalar sourceArgument = new RuntimeScalar(7);
        RuntimeHash pendingBase = new RuntimeHash();
        RuntimeScalar copiedArgument;
        Object sourceFrame;
        boolean frameActive = false;

        try (PerlRuntime.Binding ignored = sourceRuntime.bind()) {
            RuntimeArray arguments = new RuntimeArray();
            arguments.elements.add(sourceArgument);
            RuntimeCode.pushArgs(arguments);
            frameActive = true;
            sourceFrame = RuntimeCode.currentArgumentAliasFrame(sourceArgument);

            copiedArgument = new RuntimeScalar(sourceArgument);
            assertSame(sourceFrame,
                    RuntimeCode.argumentAliasFrameForOwner(copiedArgument));

            MortalList.deferDecrementFromArraySlot(
                    pendingBase, copiedArgument, "argument-frame identity test");
            assertSame(sourceFrame, sourceRuntime.lifecycleState.pendingArgumentFrames.get(0),
                    "the queue records the originating frame identity at enqueue time");

            RuntimeCode.popArgs();
            frameActive = false;

            RuntimeScalar clonedArgument = (RuntimeScalar) new RuntimeGraphCloner(
                    sourceRuntime, childRuntime).cloneGraph(copiedArgument);
            try (PerlRuntime.Binding childBinding = childRuntime.bind()) {
                assertNull(RuntimeCode.argumentAliasFrameForOwner(clonedArgument),
                        "a cloned scalar cannot retain a parent thread's argument frame");
            }
            MortalList.clearCurrentRuntimeState();
        } finally {
            if (frameActive) {
                try (PerlRuntime.Binding ignored = sourceRuntime.bind()) {
                    RuntimeCode.popArgs();
                    MortalList.clearCurrentRuntimeState();
                }
            }
        }
    }

    @Test
    void tailCallTargetFrameGetsAndReleasesItsOwnAliasIdentity() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar cell = new RuntimeScalar();
            cell.isPackageGlobalRoot = true;
            cell.set(referent.createAnonymousReference());
            int originalRefCount = referent.refCount;

            RuntimeArray sourceArgs = new RuntimeArray();
            sourceArgs.elements.add(cell);
            RuntimeArray targetArgs = new RuntimeArray();
            targetArgs.elements.add(cell);
            boolean sourceFrameActive = false;
            boolean targetFrameActive = false;
            try {
                RuntimeCode.pushArgs(sourceArgs);
                sourceFrameActive = true;
                RuntimeCode.pushArgs(targetArgs);
                targetFrameActive = true;

                assertEquals(2, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS),
                        "the tail-call target owns a separate active-frame identity");
                assertEquals(originalRefCount, referent.refCount,
                        "frame identities do not change Perl-visible reference counts");

                RuntimeCode.popArgs();
                targetFrameActive = false;
                assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS));

                RuntimeCode.popArgs();
                sourceFrameActive = false;
                assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS));
            } finally {
                if (targetFrameActive) RuntimeCode.popArgs();
                if (sourceFrameActive) RuntimeCode.popArgs();
            }
        }
    }

    @Test
    void sharedScalarArgumentRetiresItsAliasIdentityOnOverwriteAndFrameExit() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash sharedReferent = new RuntimeHash();
            sharedReferent.threadShared = true;
            sharedReferent.threadSharedIdentity = new Object();
            RuntimeScalar sharedCell = new RuntimeScalar();
            sharedCell.localBindingExists = true;
            sharedCell.set(sharedReferent.createAnonymousReference());
            sharedCell.threadShared = true;
            sharedCell.threadSharedIdentity = new Object();

            RuntimeArray args = new RuntimeArray();
            args.elements.add(sharedCell);
            boolean argsActive = false;
            try {
                RuntimeCode.pushArgs(args);
                argsActive = true;
                assertEquals(1, sharedReferent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS));

                sharedCell.set(new RuntimeScalar(1));
                assertEquals(0, sharedReferent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS),
                        "a shared cell transfers its frame identity when its value changes");

                RuntimeCode.popArgs();
                argsActive = false;
                assertEquals(0, sharedReferent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS),
                        "frame exit does not release the transferred identity twice");
            } finally {
                if (argsActive) RuntimeCode.popArgs();
            }
        }
    }
}

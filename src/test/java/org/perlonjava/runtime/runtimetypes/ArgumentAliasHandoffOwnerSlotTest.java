package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class ArgumentAliasHandoffOwnerSlotTest {
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

package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("unit")
class PerlDestroyFailureOwnershipTest {
    @Test
    void throwingDestructorReleasesObserversAndOwnedChildrenWithSweepsSuppressed() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            runtime.lifecycleState.weakSweepSuppressionDepth++;
            String className = "Issue1649::FailedOwnerCleanup";
            AtomicInteger calls = new AtomicInteger();
            RuntimeCode code = new RuntimeCode((args, context) -> {
                calls.incrementAndGet();
                throw new PerlDieException(new RuntimeScalar("owner cleanup failed\n"));
            }, null);
            code.packageName = className;
            code.subName = "DESTROY";
            GlobalVariable.getGlobalCodeRef(className + "::DESTROY").set(new RuntimeScalar(code));
            try {
                RuntimeHash child = new RuntimeHash();
                RuntimeHash parent = new RuntimeHash();
                parent.put("child", child.createAnonymousReference());
                parent.blessId = NameNormalizer.getBlessId(className);
                RuntimeScalar owner = new RuntimeScalar();
                owner.set(parent.createAnonymousReference());
                RuntimeScalar weakParent = new RuntimeScalar();
                weakParent.set(owner);
                WeakRefRegistry.weaken(weakParent);
                RuntimeScalar weakChild = child.createAnonymousReference();
                WeakRefRegistry.weaken(weakChild);

                owner.set(new RuntimeScalar());

                assertEquals(1, calls.get());
                assertEquals(RuntimeScalarType.UNDEF, weakParent.type);
                assertEquals(RuntimeScalarType.UNDEF, weakChild.type);
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, parent.perlLifecycleState());
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, child.perlLifecycleState());
                assertEquals(Integer.MIN_VALUE, parent.refCount);
                assertFalse(parent.currentlyDestroying);
            } finally {
                GlobalVariable.removeGlobalCodeRefForStashDelete(className + "::DESTROY");
                runtime.lifecycleState.weakSweepSuppressionDepth--;
            }
        }
    }
}

package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class DestroyDispatchWeakSlotRescueRootTest {
    @Test
    void weakSelfReplacementInTemporaryHashIsNotGlobalRescue() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar phase = GlobalVariable.getGlobalVariable(GlobalContext.GLOBAL_PHASE);
            String oldPhase = phase.toString();
            RuntimeBase previousTarget = runtime.lifecycleState.currentDestroyTarget;
            boolean previousRescued = runtime.lifecycleState.destroyTargetRescued;
            RuntimeArray target = new RuntimeArray();
            RuntimeHash temporaryHash = new RuntimeHash();
            RuntimeScalar weakCell = weakSelfCell(target, temporaryHash);
            try {
                phase.set("DESTRUCT");
                runtime.lifecycleState.currentDestroyTarget = target;
                runtime.lifecycleState.destroyTargetRescued = false;

                weakCell.set(target.createReference());

                assertFalse(runtime.lifecycleState.destroyTargetRescued,
                        "a weak slot in an unrooted temporary hash cannot rescue during global destruction");
            } finally {
                temporaryHash.elements.clear();
                runtime.lifecycleState.currentDestroyTarget = previousTarget;
                runtime.lifecycleState.destroyTargetRescued = previousRescued;
                phase.set(oldPhase);
            }
        }
    }

    @Test
    void weakSelfReplacementInGloballyReachableHashCanRescue() {
        PerlRuntime runtime = new PerlRuntime();
        String rootName = "DestroyDispatchWeakSlotRescueRootTest::root";
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar phase = GlobalVariable.getGlobalVariable(GlobalContext.GLOBAL_PHASE);
            String oldPhase = phase.toString();
            RuntimeBase previousTarget = runtime.lifecycleState.currentDestroyTarget;
            boolean previousRescued = runtime.lifecycleState.destroyTargetRescued;
            RuntimeArray target = new RuntimeArray();
            RuntimeHash nestedHash = new RuntimeHash();
            RuntimeArray globalArray = new RuntimeArray();
            globalArray.elements.add(nestedHash.createReference());
            GlobalVariable.globalArrays.put(rootName, globalArray);
            RuntimeScalar weakCell = weakSelfCell(target, nestedHash);
            try {
                phase.set("DESTRUCT");
                runtime.lifecycleState.currentDestroyTarget = target;
                runtime.lifecycleState.destroyTargetRescued = false;

                weakCell.set(target.createReference());

                assertTrue(runtime.lifecycleState.destroyTargetRescued,
                        "a weak slot in a hash reachable from a package global can rescue the target");
            } finally {
                nestedHash.elements.clear();
                GlobalVariable.globalArrays.remove(rootName);
                runtime.lifecycleState.currentDestroyTarget = previousTarget;
                runtime.lifecycleState.destroyTargetRescued = previousRescued;
                phase.set(oldPhase);
            }
        }
    }

    private static RuntimeScalar weakSelfCell(RuntimeBase target, RuntimeHash container) {
        RuntimeScalar weakCell = target.createReference();
        container.elements.put("self", weakCell);
        WeakRefRegistry.weaken(weakCell);
        return weakCell;
    }
}

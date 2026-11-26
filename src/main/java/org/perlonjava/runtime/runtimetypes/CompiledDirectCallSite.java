package org.perlonjava.runtime.runtimetypes;

import java.lang.ref.WeakReference;

/** One compiled named-call edge owned by the CV whose optree contains it. */
final class CompiledDirectCallSite {
    private final String name;
    private final RuntimeScalar cachedRef;
    private final WeakReference<RuntimeScalar> initialCodeSlot;
    private boolean compileTimePinned;
    private boolean ownsRetiredTarget;
    private boolean explicitlyUndefined;

    CompiledDirectCallSite(
            String name,
            RuntimeScalar cachedRef,
            RuntimeScalar initialCodeSlot,
            RuntimeCode initialTarget,
            boolean compileTimePinned,
            boolean retainTargetIfDeletedBeforeInstall) {
        this.name = name;
        this.cachedRef = cachedRef;
        this.compileTimePinned = compileTimePinned;
        RuntimeScalar visible = GlobalVariable.getVisibleGlobalCodeRefForDirectCall(name);
        RuntimeScalar observedInitialSlot = initialCodeSlot == null
                ? visible : initialCodeSlot;
        this.initialCodeSlot = observedInitialSlot == null
                ? null : new WeakReference<>(observedInitialSlot);
        boolean targetWasRemovedBeforeInstallation = retainTargetIfDeletedBeforeInstall
                && initialTarget != null
                && initialTarget.defined()
                && !RuntimeCode.isCodeDefined(visible);
        RuntimeCode ownedTarget = compileTimePinned || targetWasRemovedBeforeInstallation
                ? initialTarget : null;
        cachedRef.initializeCompiledDirectCallCache(
                name, cachedRef.compiledDirectCallSiteIndex(), ownedTarget,
                compileTimePinned);
        if (ownedTarget != null) {
            cachedRef.retainCodeSlotBinding();
            ownsRetiredTarget = true;
        }
        GlobalVariable.registerCompiledDirectCallSite(name, this);
    }

    String name() {
        return name;
    }

    int index() {
        return cachedRef.compiledDirectCallSiteIndex();
    }

    RuntimeScalar resolve() {
        RuntimeScalar visible = GlobalVariable.getVisibleGlobalCodeRefForDirectCall(name);
        if (explicitlyUndefined) {
            return visible == null ? new RuntimeScalar() : visible;
        }
        RuntimeScalar compiledSlot = initialCodeSlot == null ? null : initialCodeSlot.get();
        if (!hasPinnedConstantTarget()
                && (compileTimePinned || ownsRetiredTarget)
                && visible != null && visible == compiledSlot
                && !RuntimeCode.isCodeDefined(visible)) {
            // `undef *name` empties the original, still-visible CODE slot.
            // Stash deletion instead removes that slot and may later create a
            // fresh undefined placeholder; that distinct slot must not revoke
            // the compiled call site's retired target.
            compileTimePinned = false;
            releaseRetiredTarget();
            return visible;
        }
        if (compileTimePinned || ownsRetiredTarget) return cachedRef;
        return visible != null ? visible : GlobalVariable.getGlobalCodeRefForDirectCall(name);
    }

    /** Transfer the callsite's lifetime to a CV only when its stash slot is removed. */
    void visibleCodeSlotChanged(RuntimeScalar previous, RuntimeScalar replacement) {
        if (replacement != null) {
            // namespace::clean removes the imported CODE entry by deleting
            // its glob, then restores the other glob components. That restore
            // can materialize a fresh, undefined CODE slot. Existing call
            // sites still refer to the CV compiled before cleanup; an empty
            // placeholder is not a replacement CV and must not retire their
            // owner.
            if (previous == null && !RuntimeCode.isCodeDefined(replacement)) return;
            if (!RuntimeCode.isCodeDefined(replacement)) {
                if (hasPinnedConstantTarget()) return;
                // An explicitly emptied, still-visible glob invalidates
                // compiled direct calls, even when their parse-time target
                // had been pinned. Stash deletion is represented by a null
                // replacement and continues to retain the old target.
                explicitlyUndefined = true;
                compileTimePinned = false;
                releaseRetiredTarget();
                return;
            }
            explicitlyUndefined = false;
        }
        if (compileTimePinned) {
            return;
        }
        if (replacement != null) {
            releaseRetiredTarget();
            return;
        }
        if (ownsRetiredTarget || previous == null
                || previous.type != RuntimeScalarType.CODE
                || !(previous.value instanceof RuntimeCode removedTarget)) {
            return;
        }
        cachedRef.initializeCompiledDirectCallCache(name,
                cachedRef.compiledDirectCallSiteIndex(), removedTarget, false);
        cachedRef.retainCodeSlotBinding();
        ownsRetiredTarget = true;
    }

    void release() {
        GlobalVariable.unregisterCompiledDirectCallSite(name, this);
        releaseRetiredTarget();
        cachedRef.clearCompiledDirectCallCache(name, cachedRef.compiledDirectCallSiteIndex());
    }

    private void releaseRetiredTarget() {
        if (!ownsRetiredTarget) return;
        cachedRef.releaseCodeSlotBinding();
        ownsRetiredTarget = false;
        cachedRef.clearCompiledDirectCallCache(name,
                cachedRef.compiledDirectCallSiteIndex());
    }

    private boolean hasPinnedConstantTarget() {
        RuntimeCode target = cachedRef.compiledDirectCallInitialTarget();
        return compileTimePinned && target != null && target.constantValue != null;
    }

}

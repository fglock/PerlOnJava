package org.perlonjava.runtime.runtimetypes;

/** One compiled named-call edge owned by the CV whose optree contains it. */
final class CompiledDirectCallSite {
    private final String name;
    private final RuntimeScalar cachedRef;
    private final boolean compileTimePinned;
    private boolean ownsRetiredTarget;

    CompiledDirectCallSite(
            String name,
            RuntimeScalar cachedRef,
            RuntimeCode initialTarget,
            boolean compileTimePinned,
            boolean retainTargetIfDeletedBeforeInstall) {
        this.name = name;
        this.cachedRef = cachedRef;
        this.compileTimePinned = compileTimePinned;
        RuntimeScalar visible = GlobalVariable.getVisibleGlobalCodeRefForDirectCall(name);
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

    RuntimeScalar resolve() {
        if (compileTimePinned || ownsRetiredTarget) return cachedRef;
        RuntimeScalar visible = GlobalVariable.getVisibleGlobalCodeRefForDirectCall(name);
        return visible != null ? visible : GlobalVariable.getGlobalCodeRefForDirectCall(name);
    }

    /** Transfer the callsite's lifetime to a CV only when its stash slot is removed. */
    void visibleCodeSlotChanged(RuntimeScalar previous, RuntimeScalar replacement) {
        if (compileTimePinned) return;
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
        cachedRef.type = RuntimeScalarType.UNDEF;
        cachedRef.value = null;
        cachedRef.globalCodeRefFqn = null;
    }

    private void releaseRetiredTarget() {
        if (!ownsRetiredTarget) return;
        cachedRef.releaseCodeSlotBinding();
        ownsRetiredTarget = false;
        cachedRef.initializeCompiledDirectCallCache(name,
                cachedRef.compiledDirectCallSiteIndex(), null, false);
    }
}

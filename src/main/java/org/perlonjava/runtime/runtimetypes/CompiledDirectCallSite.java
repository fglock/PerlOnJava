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
            boolean compileTimePinned) {
        this.name = name;
        this.cachedRef = cachedRef;
        this.compileTimePinned = compileTimePinned;
        RuntimeCode ownedTarget = compileTimePinned ? initialTarget : null;
        cachedRef.initializeCompiledDirectCallCache(
                name, cachedRef.compiledDirectCallSiteIndex(), ownedTarget,
                compileTimePinned);
        if (ownedTarget != null) {
            cachedRef.retainCodeSlotBinding();
            ownsRetiredTarget = true;
        }
        GlobalVariable.registerCompiledDirectCallSite(name, this);
        if (name.contains("DeletedMethod")) {
            System.err.println("CDCS construct " + name + " pinned=" + compileTimePinned
                    + " initial=" + (initialTarget != null));
        }
    }

    String name() {
        return name;
    }

    RuntimeScalar resolve() {
        if (compileTimePinned || ownsRetiredTarget) return cachedRef;
        RuntimeScalar visible = GlobalVariable.getVisibleGlobalCodeRefForDirectCall(name);
        if (name.contains("DeletedMethod")) {
            System.err.println("CDCS resolve " + name + " visible="
                    + (visible == null ? "null" : visible.type + ":" + visible.value)
                    + " retired=" + ownsRetiredTarget);
        }
        return visible != null ? visible : GlobalVariable.getGlobalCodeRefForDirectCall(name);
    }

    /** Transfer the callsite's lifetime to a CV only when its stash slot is removed. */
    void visibleCodeSlotChanged(RuntimeScalar previous, RuntimeScalar replacement) {
        if (name.contains("DeletedMethod")) {
            System.err.println("CDCS notify " + name + " previous="
                    + (previous == null ? "null" : previous.type + ":" + previous.value)
                    + " replacement="
                    + (replacement == null ? "null" : replacement.type + ":" + replacement.value)
                    + " owns=" + ownsRetiredTarget);
        }
        if (compileTimePinned) return;
        if (RuntimeCode.isCodeDefined(replacement)) {
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
        cachedRef.releaseCodeSlotBindingAtExactBoundary();
        ownsRetiredTarget = false;
        cachedRef.initializeCompiledDirectCallCache(name,
                cachedRef.compiledDirectCallSiteIndex(), null, false);
    }
}

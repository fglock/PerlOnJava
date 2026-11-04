package org.perlonjava.runtime.runtimetypes;

/** One compiled named-call edge owned by the CV whose optree contains it. */
final class CompiledDirectCallSite {
    private final String name;
    private final RuntimeScalar cachedRef;
    private RuntimeCode target;

    CompiledDirectCallSite(
            String name,
            RuntimeScalar cachedRef,
            RuntimeCode initialTarget,
            boolean compileTimePinned) {
        this.name = name;
        this.cachedRef = cachedRef;
        cachedRef.initializeCompiledDirectCallCache(
                name, cachedRef.compiledDirectCallSiteIndex(), initialTarget,
                compileTimePinned);
        target = initialTarget;
        cachedRef.retainCodeSlotBinding();
        GlobalVariable.registerCompiledDirectCallSite(name, this);
    }

    String name() {
        return name;
    }

    RuntimeScalar resolve() {
        if (cachedRef.compiledDirectCallCompileTimePinned()) return cachedRef;
        RuntimeScalar visible = GlobalVariable.getVisibleGlobalCodeRefForDirectCall(name);
        if (visible != null) {
            retarget(visible);
            return cachedRef;
        }
        if (target != null) return cachedRef;

        RuntimeScalar fresh = GlobalVariable.getGlobalCodeRefForFreshLookup(name);
        retarget(fresh);
        return cachedRef;
    }

    /** A replacement or in-place CODE-slot mutation is visible to this call site. */
    void visibleCodeSlotChanged(RuntimeScalar replacement) {
        if (cachedRef.compiledDirectCallCompileTimePinned()) return;
        if (replacement != null) retarget(replacement);
    }

    void release() {
        GlobalVariable.unregisterCompiledDirectCallSite(name, this);
        cachedRef.releaseCodeSlotBindingWithoutFlush();
        target = null;
        cachedRef.type = RuntimeScalarType.UNDEF;
        cachedRef.value = null;
        cachedRef.globalCodeRefFqn = null;
    }

    private void retarget(RuntimeScalar ref) {
        if (ref == null) return;
        RuntimeCode next = ref.type == RuntimeScalarType.CODE
                && ref.value instanceof RuntimeCode code ? code : null;
        if (next == target && ref.type == cachedRef.type && ref.value == cachedRef.value) {
            return;
        }
        cachedRef.setCompiledDirectCallTarget(ref);
        target = next;
        cachedRef.updateCompiledDirectCallTarget(next);
    }
}

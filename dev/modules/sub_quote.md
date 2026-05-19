# Sub::Quote Support in PerlOnJava

## Overview
This document describes the changes made to support Sub::Quote in PerlOnJava, the investigation into OutOfMemoryError (OOM) issues encountered during testing, and the required system redesign to resolve these issues.

## PR #759: Sub::Quote Weak Reference Cleanup Fix

### Problem
The Sub::Quote `t/leaks.t` tests were failing because weak references to CODE objects were never cleared. This prevented Sub::Quote's `CLONE` method from cleaning up expired entries, causing the tests to report that quoted subs were leaking.

### Solution
Modified `WeakRefRegistry.clearWeakRefsTo()` in `src/main/java/org/perlonjava/runtime/runtimetypes/WeakRefRegistry.java` to always clear weak refs to CODE objects by passing `true` instead of `false` to the `includeCode` parameter.

**Changed line:**
```java
public static void clearWeakRefsTo(RuntimeBase referent) {
    clearWeakRefsTo(referent, true);  // Always include CODE refs for Sub::Quote compatibility
}
```

### Test Results
- **leaks.t**: All 9 tests now pass (previously failed 4 tests)
- **quotify.t**: All 2595 tests pass
- **hints.t**: 2 tests still failing due to a known limitation with `caller(0)` returning empty warning bits in main script context (documented in `dev/architecture/lexical-warnings.md`)
- **sub-defer.t and sub-quote.t**: Timeout/OutOfMemoryError - pre-existing issue

## OOM Investigation

### Symptoms
- Tests `sub-defer.t` and `sub-quote.t` pass all subtests (33 and 51 respectively) but then timeout during cleanup
- Exit code 124 (timeout) or 255 (crash)
- Error message: "Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread 'perlonjava-orphan-watchdog'"

### Investigation Steps
1. **Confirmed pre-existing issue**: OOM occurs even without any changes to WeakRefRegistry or ScalarUtil
2. **Tried optimizing clearAllBlessedWeakRefs**: Filtered during snapshot to reduce memory pressure - did not help
3. **Tried environment variable**: Added `PJ_SKIP_WEAK_CLEAR` to skip weak ref cleanup - did not help
4. **Increased heap size**: Set `JPERL_OPTS="-Xmx4g"` - still failed with OOM
5. **jstack analysis**: Revealed GC threads running continuously for ~5 minutes with high CPU usage
6. **Stack trace analysis**: Identified root cause in `ScalarRefRegistry.forceGcAndSnapshot()`

### Root Cause
The OOM occurs in `ScalarRefRegistry.forceGcAndSnapshot()` at line 130, which is called by `ReachabilityWalker.sweepWeakRefs()` at line 1120 during cleanup.

**Code location:** `src/main/java/org/perlonjava/runtime/runtimetypes/ScalarRefRegistry.java`
```java
public static java.util.List<RuntimeScalar> forceGcAndSnapshot() {
    // Multiple GC cycles are sometimes needed: the first cycle may
    // only clear one level of unreachable objects, exposing more
    // for a subsequent pass. A WeakReference sentinel tells us
    // when weak-ref processing has completed for a cycle.
    for (int pass = 0; pass < 3; pass++) {
        Object sentinel = new Object();
        WeakReference<Object> probe = new WeakReference<>(sentinel);
        sentinel = null;  // drop the only strong ref
        for (int i = 0; i < 5; i++) {
            System.gc();
            if (probe.get() == null) break;
            try {
                Thread.sleep(10);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    return snapshot();
}
```

The method runs 3 passes of GC (each calling `System.gc()` 5 times with 10ms sleep) to determine which objects are still reachable. With the many CODE refs and weak refs created by Sub::Defer/Sub::Quote, this GC forcing mechanism runs out of memory even with 4GB heap.

### Why This Happens
Sub::Defer and Sub::Quote create many CODE refs and weak refs during normal operation. During cleanup:
1. `ReachabilityWalker.sweepWeakRefs()` is called to clean up unreachable weak refs
2. It calls `forceGcAndSnapshot()` to get a snapshot of live scalars
3. The GC forcing mechanism (3 passes × 5 GC calls = 15 GC calls) is expensive
4. With many objects to process, the GC cannot keep up
5. The JVM runs out of memory during the GC cycles

## Required System Redesign

The OOM issue is a fundamental limitation of the current selective refcounting system's reachability walker. To fix this, one or more of the following redesigns are required:

### Option 1: Reduce GC Cycles in forceGcAndSnapshot
**Approach:** Reduce the number of GC cycles or make them adaptive based on object count.

**Changes needed:**
- Modify `ScalarRefRegistry.forceGcAndSnapshot()` to use fewer GC passes for large object graphs
- Add logic to detect when the object graph is large and skip or reduce GC forcing
- Consider using a single GC pass with a longer wait time instead of multiple passes

**Pros:**
- Minimal code change
- Could reduce OOM without changing the overall architecture

**Cons:**
- May reduce accuracy of reachability detection
- Could cause false positives/negatives in weak ref cleanup
- May not fully eliminate OOM for very large object graphs

### Option 2: Skip Reachability Sweeping for Memory-Intensive Tests
**Approach:** Add an environment variable to disable reachability sweeping for specific tests.

**Changes needed:**
- Add `PJ_SKIP_REACHABILITY_SWEEP` environment variable
- Check this flag in `ReachabilityWalker.sweepWeakRefs()` before calling `forceGcAndSnapshot()`
- Document which tests require this flag

**Pros:**
- Simple workaround
- Allows tests to pass without system redesign
- No impact on normal operation

**Cons:**
- Only a workaround, not a fix
- Tests with this flag may not catch real issues
- Requires manual configuration for each affected test

### Option 3: Incremental Reachability Sweeping
**Approach:** Instead of forcing GC and sweeping all objects at once, sweep incrementally in batches.

**Changes needed:**
- Modify `ReachabilityWalker.sweepWeakRefs()` to process objects in batches
- Add logic to pause between batches to allow GC to run naturally
- Track progress across multiple sweep calls

**Pros:**
- Reduces memory pressure at any given time
- More predictable memory usage
- Could handle larger object graphs

**Cons:**
- Significant redesign of reachability walker
- May increase cleanup time
- Could introduce race conditions if not careful

### Option 4: Alternative Reachability Detection
**Approach:** Use a different mechanism to detect reachable objects that doesn't require forcing GC.

**Changes needed:**
- Implement a reference counting-based reachability tracker
- Track object references as they are created/destroyed
- Use this tracker instead of GC-based detection

**Pros:**
- Eliminates GC forcing entirely
- More predictable performance
- No OOM risk from GC cycles

**Cons:**
- Major system redesign
- Requires tracking all object references
- Could have performance overhead
- Complex to implement correctly

### Option 5: Deferred Cleanup for CODE Refs
**Approach:** Delay cleanup of weak refs to CODE refs until program exit or explicit request.

**Changes needed:**
- Modify `WeakRefRegistry.clearWeakRefsTo()` to skip CODE refs during normal cleanup
- Add a separate cleanup phase for CODE refs at program exit
- Provide API for explicit CODE ref cleanup when needed

**Pros:**
- Reduces cleanup pressure during normal operation
- CODE refs are less likely to cause memory leaks (they're usually in stashes)
- Matches Perl 5 behavior more closely

**Cons:**
- Sub::Quote's CLONE method relies on weak refs being cleared promptly
- May reintroduce the leaks.t failures
- Could cause memory to accumulate over time

### Option 6: Bundle PerlOnJava-Tuned Sub::Defer/Sub::Quote
**Approach:** Instead of fixing the OOM in the general runtime, provide modified versions of Sub::Defer and Sub::Quote that work around the limitations.

**Changes needed:**
- Fork Sub::Defer and Sub::Quote into `src/main/perl/lib/Sub/Defer.pm` and `src/main/perl/lib/Sub/Quote.pm`
- Modify CLONE method to use explicit cleanup instead of relying on weak refs being cleared automatically
- Use a different data structure for %QUOTED (e.g., refaddr-based keys without weak refs)
- Reduce or eliminate reliance on weak refs for quoted subs
- Add @INC manipulation to prefer bundled versions over CPAN
- Modify or skip tests that stress the GC (sub-defer.t, sub-quote.t)
- Document differences from upstream CPAN versions

**Pros:**
- Avoids major system redesign
- Localizes changes to specific modules
- Simpler and less risky than modifying selective refcounting
- Allows Sub::Defer/Sub::Quote to work correctly on PerlOnJava
- Doesn't affect other modules or general runtime behavior

**Cons:**
- Requires maintaining forked versions of CPAN modules
- Need to track upstream changes and merge periodically
- Potential for divergence from CPAN behavior
- Adds maintenance burden

## Recommended Approach

**Short-term:** Implement Option 6 (bundle PerlOnJava-tuned Sub::Defer/Sub::Quote) as the primary solution. This is the most practical approach because:
- It avoids major system redesign
- It localizes changes to specific modules
- It's simpler and less risky than modifying the selective refcounting system
- It allows Sub::Defer/Sub::Quote to work correctly on PerlOnJava immediately
- It doesn't affect other modules or general runtime behavior

**Long-term:** Consider implementing Option 1 (reduce GC cycles) combined with Option 3 (incremental sweeping) to improve the general reachability walker, which would benefit all modules and eliminate the need for module-specific workarounds.

## References
- PR #759: https://github.com/fglock/PerlOnJava/pull/759
- WeakRefRegistry.java: `src/main/java/org/perlonjava/runtime/runtimetypes/WeakRefRegistry.java`
- ScalarRefRegistry.java: `src/main/java/org/perlonjava/runtime/runtimetypes/ScalarRefRegistry.java`
- ReachabilityWalker.java: `src/main/java/org/perlonjava/runtime/runtimetypes/ReachabilityWalker.java`
- lexical-warnings.md: `dev/architecture/lexical-warnings.md`

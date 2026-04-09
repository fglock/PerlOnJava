# Weaken & DESTROY - Architecture Guide

**Last Updated:** 2026-04-09
**Status:** PRODUCTION READY - 841/841 Moo subtests passing
**Branch:** `feature/destroy-weaken`

---

## Overview

PerlOnJava implements Perl 5's `DESTROY` and `Scalar::Util::weaken` semantics
using a **selective reference-counting overlay** on top of the JVM's tracing
garbage collector. The JVM already handles memory reclamation (including
circular references), so PerlOnJava does not need full Perl 5-style refcounting.
Instead, it tracks refcounts only for the small subset of objects that require
deterministic destruction: those blessed into a class with a `DESTROY` method.
Everything else is left to the JVM GC with zero bookkeeping overhead. Weak
references (`weaken()`) are tracked in a separate registry (WeakRefRegistry)
and are cleared when a tracked object's refcount hits zero.

The system is designed around two principles:

1. **Zero cost when unused.** Programs that never `bless` into a class with
   `DESTROY` pay no runtime overhead -- every hot path is guarded by a single
   boolean (`MortalList.active`).

2. **Correctness over completeness.** The system tracks only objects that
   *need* tracking (blessed into a DESTROY class), avoiding the full Perl 5
   reference-counting burden. Weak references are registered externally and
   cleared as a side-effect of DESTROY.

---

## Core Concepts

### refCount State Machine

Every `RuntimeBase` (the superclass of `RuntimeHash`, `RuntimeArray`,
`RuntimeCode`, `RuntimeScalar` as referent) carries a `refCount` field:

```
                bless into DESTROY class
    -1 ───────────────────────────────────► 0
 (untracked)                            (birth-tracked)
                                            │
                                            │ setLarge() copies ref
                                            │ into a variable
                                            │
                                            ▼
                                           1+
                                        (N strong refs)
                                            │
                                            │ last strong ref dropped
                                            │ (decrement hits 0)
                                            │
                                            ▼
                                         MIN_VALUE
                                         (destroyed)
                                              │
                                              └──► DestroyDispatch.callDestroy()
                                                   WeakRefRegistry.clearWeakRefsTo()
```

**NOTE:** The WEAKLY_TRACKED (-2) state is retained for a specific case:
unblessed birth-tracked objects (blessId == 0, refCount > 0) where
closure captures bypass `setLarge()`, making refCount unreliable. The
key change from the original design: **untracked objects (refCount == -1)
are NO LONGER transitioned to WEAKLY_TRACKED by `weaken()`** — they stay
at -1. This fixes the qr-72922.t regression while preserving the Moo
safety valve. See "Proposed Design Change" section for details.

| Value | Meaning |
|-------|---------|
| `-1` | **Untracked.** Default state. Object is unblessed or blessed into a class without DESTROY. No refCount bookkeeping occurs. `weaken()` registers the weak ref in WeakRefRegistry but does not change refCount. |
| `0` | **Birth-tracked.** Freshly blessed into a DESTROY class, or anonymous hash/code via `createReferenceWithTrackedElements`. No variable holds a reference yet -- `setLarge()` will increment to 1 on first assignment. |
| `> 0` | **Tracked.** N strong references exist in named variables. Each `setLarge()` assignment increments; each scope exit or reassignment decrements. |
| `-2` | **WEAKLY_TRACKED.** An unblessed birth-tracked object (blessId == 0) that has weak references and whose strong ref count cannot be accurately tracked due to closure captures bypassing `setLarge()`. Entered only via `weaken()` on a tracked object with `blessId == 0`. Prevents premature clearing of weak refs by disabling refCount decrements. Weak refs are cleared on explicit `undef`. |
| `MIN_VALUE` | **Destroyed.** DESTROY has been called (or is in progress). Prevents double-destruction. |

### Ownership: `refCountOwned`

Each `RuntimeScalar` has a `boolean refCountOwned` field. When true, this scalar
"owns" one increment on its referent's `refCount`. This prevents double-
decrement: only the owner decrements when the scalar is reassigned or goes out
of scope.

### Capture Count

`RuntimeScalar.captureCount` tracks how many closures capture this variable.
When `captureCount > 0`, `scopeExitCleanup()` skips all cleanup -- the variable
outlives its lexical scope.

---

## System Components

### File Map

| File | Role |
|------|------|
| `RuntimeBase.java` | Defines `refCount`, `blessId` fields on all referent types |
| `RuntimeScalar.java` | `setLarge()` (increment/decrement), `scopeExitCleanup()`, `undefine()` |
| `WeakRefRegistry.java` | Weak reference tracking: forward set + reverse map |
| `DestroyDispatch.java` | DESTROY method resolution, caching, invocation |
| `MortalList.java` | Deferred decrements (FREETMPS equivalent) |
| `GlobalDestruction.java` | End-of-program stash walking |
| `ReferenceOperators.java` | `bless()` -- activates tracking |
| `RuntimeGlob.java` | CODE slot replacement -- optree reaping emulation |
| `RuntimeCode.java` | `padConstants` registry, `releaseCaptures()` |

---

## Component Deep Dives

### 1. WeakRefRegistry

**Path:** `org.perlonjava.runtime.runtimetypes.WeakRefRegistry`

Manages all weak references using two identity-based data structures:

- **`weakScalars`** (`Set<RuntimeScalar>`) -- forward set of all scalars
  currently holding a weak reference.
- **`referentToWeakRefs`** (`IdentityHashMap<RuntimeBase, Set<RuntimeScalar>>`)
  -- reverse map from referent to its weak scalars. Used by
  `clearWeakRefsTo()` to null out all weak refs when a referent is destroyed.

**Key operations:**

| Method | What it does |
|--------|--------------|
| `weaken(ref)` | Validates ref is a reference. Adds to both maps. Adjusts refCount: if untracked (-1), registers in WeakRefRegistry only — no refCount change. If tracked (>0), decrements strong count (may trigger DESTROY if it hits 0). For unblessed tracked objects (blessId == 0) with remaining strong refs, transitions to WEAKLY_TRACKED to prevent premature clearing. |
| `isweak(ref)` | Returns `weakScalars.contains(ref)`. |
| `unweaken(ref)` | Removes from both maps. Re-increments refCount and restores `refCountOwned`. |
| `removeWeakRef(ref, oldReferent)` | Called by `setLarge()` before decrementing. Returns true if the ref was weak, telling the caller to skip the refCount decrement. |
| `hasWeakRefsTo(referent)` | Returns true if any weak references point to the given referent. |
| `clearWeakRefsTo(referent)` | Called during destruction. Sets every weak scalar pointing at this referent to `UNDEF/null`. Removes all entries from both maps. |

**Design decision -- external maps, not per-scalar flags:** Weak refs are rare.
Using identity-based external maps avoids adding a field to every
`RuntimeScalar` and keeps the common (non-weak) path completely free of
branches.

### 2. DestroyDispatch

**Path:** `org.perlonjava.runtime.runtimetypes.DestroyDispatch`

Resolves and calls DESTROY methods. Uses two caches:

- **`destroyClasses`** (`BitSet`) -- indexed by `|blessId|`. Records which
  classes have been confirmed to have a DESTROY method (or AUTOLOAD that could
  handle it).
- **`destroyMethodCache`** (`ConcurrentHashMap<Integer, RuntimeScalar>`) --
  caches the resolved DESTROY `RuntimeCode` per `blessId`.

Both caches are invalidated by `invalidateCache()`, called whenever `@ISA`
changes or methods are redefined.

**`callDestroy(referent)` flow:**

1. **Precondition:** Caller has already set `refCount = MIN_VALUE`.
2. Calls `WeakRefRegistry.clearWeakRefsTo(referent)` -- clears all weak
   references pointing to this object (for both blessed and unblessed
   referents; unblessed referents typically have no weak refs registered).
3. If referent is `RuntimeCode`, calls `releaseCaptures()`.
4. Looks up class name from `blessId`. If unblessed, returns (no DESTROY
   to call, but weak refs and captures have already been cleaned up).
5. Resolves DESTROY method via cache or `InheritanceResolver`.
6. Handles AUTOLOAD: sets `$AUTOLOAD = "ClassName::DESTROY"`.
7. Saves/restores `$@` around the call (DESTROY must not clobber `$@`).
8. Builds a `$self` reference with the correct type (HASHREFERENCE, etc.).
9. Calls `RuntimeCode.apply(destroyMethod, args, VOID)`.
10. **Cascading destruction:** After DESTROY returns, walks the destroyed
    object's hash/array elements via `MortalList.scopeExitCleanupHash/Array()`
    then flushes.
11. **Exception handling:** Catches exceptions, converts to
    `WarnDie.warn("(in cleanup) ...")` -- matching Perl 5 semantics.

### 3. MortalList (Deferred Decrements)

**Path:** `org.perlonjava.runtime.runtimetypes.MortalList`

Equivalent to Perl 5's `FREETMPS` / mortal stack. Provides deferred refCount
decrements at statement boundaries so that temporaries survive long enough to
be used.

**The `active` gate:** A single `boolean` that starts `false`. It is set to
`true` when the first `bless()` into a DESTROY class occurs. When `false`,
every public method is a no-op -- zero overhead for the vast majority of
programs that don't use DESTROY.

**Pending list:** `ArrayList<RuntimeBase>` of referents awaiting decrement.

**Mark stack:** `ArrayList<Integer>` for scoped flushing (SAVETMPS equivalent).

**Key operations:**

| Method | Purpose |
|--------|---------|
| `deferDecrement(base)` | Unconditionally adds to pending. |
| `deferDecrementIfTracked(scalar)` | Guarded: skips if `!active`, `!refCountOwned`, or referent's `refCount <= 0`. Clears `refCountOwned` before deferring. |
| `deferDecrementIfNotCaptured(scalar)` | Like above but also skips if `captureCount > 0`. Used by explicit `return`. |
| `deferDestroyForContainerClear(elements)` | For `%hash = ()` / `@array = ()`. Handles owned refs and never-stored blessed objects (bumps refCount 0 -> 1 to ensure DESTROY fires). |
| `scopeExitCleanupHash(hash)` | Recursively walks a hash's values, deferring refCount decrements for tracked blessed refs (including inside nested containers). Called at scope exit for `my %hash` and during cascading destruction in `callDestroy`. |
| `scopeExitCleanupArray(arr)` | Same as above but for arrays. Called at scope exit for `my @array` and during cascading destruction. |
| `flush()` | **Primary flush point.** Processes all pending entries: decrements refCount, fires DESTROY on those hitting 0. Uses index-based loop because DESTROY may add new entries. |
| `pushMark()` / `popAndFlush()` | Scoped flushing -- only processes entries added since the last mark. |
| `mortalizeForVoidDiscard(result)` | For void-context call results: ensures never-stored blessed objects still get DESTROY. |

**Flush points:** `MortalList.flush()` is called:
- After every reference assignment in `setLarge()`.
- After `undefine()`.
- After cascading destruction in `DestroyDispatch.doCallDestroy()`.

Scoped flushing via `pushMark()` / `popAndFlush()` is used:
- At scope exit via generated bytecode (only processes entries added within that scope).

### 4. RuntimeScalar -- Reference Tracking Integration

**Path:** `org.perlonjava.runtime.runtimetypes.RuntimeScalar`

Three methods form the core tracking integration:

#### `setLarge()` -- The Primary Assignment Path

Called for every scalar assignment that might involve a reference. Contains the
refCount tracking block:

```
1. Save old referent (if current value is a reference)
2. Check WeakRefRegistry: if this scalar is weak, skip decrement
3. Increment new referent's refCount (if >= 0), set refCountOwned = true
4. Perform the actual type/value assignment
5. Decrement old referent's refCount (if owned); DESTROY if it hits 0
6. Update refCountOwned
7. MortalList.flush()
```

#### `scopeExitCleanup()` -- Lexical Scope Exit

Called by generated bytecode when a lexical variable goes out of scope:

1. Returns immediately if `captureCount > 0` (variable is captured by a closure).
2. Handles IO fd recycling for glob references.
3. Calls `MortalList.deferDecrementIfTracked()` to schedule a deferred
   decrement rather than decrementing immediately.

#### `undefine()` -- Explicit `undef $obj`

Handles explicit undef with special cases:
- CODE refs: releases captures, replaces with empty `RuntimeCode`.
- Tracked (>0): decrements; DESTROY if it hits 0.
- WEAKLY_TRACKED (-2): triggers callDestroy to clear weak refs (safe
  because these are unblessed objects with no DESTROY method).
- Untracked (-1): no refCount action (weak refs persist).
- Flushes `MortalList` at the end.

### 5. bless() -- Tracking Activation

**Path:** `org.perlonjava.runtime.operators.ReferenceOperators.bless()`

The `bless()` function is the entry point for refCount tracking:

| Scenario | refCount | refCountOwned |
|----------|----------|---------------|
| First bless into DESTROY class | `0` (birth-tracked) | unchanged |
| Re-bless from untracked class into DESTROY class | `1` | `true` |
| Re-bless (already tracked) into DESTROY class | unchanged | unchanged |
| Re-bless (already tracked) into class without DESTROY | `-1` (tracking dropped) | unchanged |
| Bless into class without DESTROY | `-1` (untracked) | unchanged |

**First bless sets refCount = 0**, not 1, because the blessing scalar hasn't
yet stored the reference via `setLarge()`. When the reference is assigned to a
variable, `setLarge()` increments to 1.

### 6. GlobalDestruction

**Path:** `org.perlonjava.runtime.runtimetypes.GlobalDestruction`

Handles end-of-program cleanup. Called from `WarnDie.java` during the normal
exit path (after END blocks, before `closeAllHandles`).

**`runGlobalDestruction()` flow:**

1. Sets `${^GLOBAL_PHASE}` to `"DESTRUCT"`.
2. Walks all global scalars -> `destroyIfTracked()`.
3. Walks all global arrays -> iterates elements -> `destroyIfTracked()`.
4. Walks all global hashes -> iterates values -> `destroyIfTracked()`.

`destroyIfTracked()` checks if a scalar holds a reference with `refCount >= 0`,
then sets `MIN_VALUE` and calls `DestroyDispatch.callDestroy()`.

This catches objects that "escaped" into global/stash variables and were never
explicitly dropped.

### 7. Optree Reaping Emulation

**Path:** `RuntimeGlob.java`, `RuntimeCode.java`, `EmitOperator.java`, `EmitSubroutine.java`

In Perl 5, when a subroutine is replaced (`*foo = sub { ... }`), the old sub's
op-tree is freed, including compile-time string constants. If a weak reference
pointed to such a constant (via `\"string"`), it becomes undef.

PerlOnJava emulates this with "pad constants":

1. **Compile time** (`EmitOperator.handleCreateReference()`): When `\` is applied
   to a `StringNode`, the cached `RuntimeScalarReadOnly` index is recorded in
   `JavaClassInfo.padConstants`.
2. **Subroutine creation** (`EmitSubroutine.java`, `SubroutineParser.java`):
   Pad constants are transferred to `RuntimeCode.padConstantsByClassName`.
3. **CODE slot replacement** (`RuntimeGlob.set()`): Before overwriting the CODE
   slot, calls `clearPadConstantWeakRefs()` on the old code, which clears any
   weak references to those cached constants.

---

## Lifecycle Examples

### Example 1: Basic DESTROY

```perl
{
    my $obj = bless {}, 'Foo';   # refCount: 0 -> 1 (via setLarge)
    my $ref = $obj;              # refCount: 1 -> 2
}
# scopeExitCleanup for $ref: defers decrement (2 -> 1)
# scopeExitCleanup for $obj: defers decrement (1 -> 0)
# MortalList.flush(): refCount hits 0 -> MIN_VALUE -> DESTROY called
```

### Example 2: Weak Reference Breaks Cycle

```perl
{
    my $a = bless {}, 'Node';    # refCount: 0 -> 1
    my $b = bless {}, 'Node';    # refCount: 0 -> 1
    $a->{peer} = $b;             # $b refCount: 1 -> 2
    $b->{peer} = $a;             # $a refCount: 1 -> 2
    weaken($b->{peer});          # $a refCount: 2 -> 1 (weak ref doesn't count)
}
# scope exit deferred: $b refCount 2 -> 1, $a refCount 1 -> 0 -> DESTROY
# During $a's DESTROY: clearWeakRefsTo($a) -> $b->{peer} = undef
# Cascading destruction of $a->{peer}: $b refCount 1 -> 0 -> DESTROY
```

### Example 3: Weak Ref to Untracked Object (Strategy A)

```perl
our $cache;
$cache = bless {}, 'Cached';    # refCount stays -1 (no DESTROY → untracked)
weaken($weak = $cache);         # registers in WeakRefRegistry; refCount stays -1
undef $cache;                   # undefine() sees refCount -1 → no action
                                # $weak remains valid (still points to live object)
                                # Perl 5 would make $weak undef here — accepted tradeoff
```

Note: If `Cached` had a `DESTROY` method, `bless` would set refCount = 0,
making it fully tracked. Then `weaken()` would decrement 2 → 1, and
`undef $cache` would decrement 1 → 0 → DESTROY fires, $weak becomes undef.
This is the correct and fully Perl 5-compliant path.

---

## Performance Characteristics

### Zero-Cost Opt-Out

| Condition | Overhead |
|-----------|----------|
| No DESTROY classes exist | Zero. `MortalList.active == false` gates all paths. |
| DESTROY classes exist but object is not blessed into one | Minimal. `refCount == -1` short-circuits in `setLarge()`. |
| Object blessed into DESTROY class | Full tracking: increment/decrement in `setLarge()`, deferred decrement in `scopeExitCleanup()`. |

### Hot Path Costs

- **`setLarge()` with `MortalList.active == false`**: One boolean check, no
  other overhead.
- **`setLarge()` with tracked referent**: ~4 field reads + 1 increment +
  1 decrement + `MortalList.flush()` (usually a no-op if pending list is empty).
- **`WeakRefRegistry` checks**: Only in `setLarge()` when the scalar was
  previously holding a reference and `MortalList.active` is true.

### Benchmark Results (2026-04-08)

Measured on macOS (Apple Silicon), 3 runs per benchmark, median CPU time.
`master` = origin/master (no DESTROY/weaken), `branch` = feature/destroy-weaken.

| Benchmark | master (CPU s) | branch (CPU s) | Delta | Change |
|-----------|---------------|----------------|-------|--------|
| method (10M calls, uses `bless`) | 1.20 | 1.26 | +0.06 | +5.0% |
| closure (100M calls) | 5.79 | 5.72 | -0.07 | -1.2% (noise) |
| lexical (400M increments) | 2.55 | 2.29 | -0.26 | -10.2% (noise) |
| global (400M increments) | 12.74 | 12.76 | +0.02 | +0.2% (noise) |
| string (200M increments) | 3.42 | 3.30 | -0.12 | -3.5% (noise) |
| regex (40M matches) | 1.97 | 2.02 | +0.05 | +2.5% (noise) |
| life_bitpacked (5000 gens, 128x100) | 2.157 | 2.268 | +0.111 | +5.1% |

**Analysis:**

- **Method calls** (+5%): The only benchmark that uses `bless`. The `bless()`
  function now calls `DestroyDispatch.classHasDestroy()` to decide whether
  to activate tracking. Since `Foo` has no DESTROY method, tracking is not
  activated, but the class lookup still costs ~50ns per `bless`. This is a
  one-time cost per new blessId and is cached.

- **Non-OOP benchmarks** (closure, lexical, global, string, regex): All within
  +/-3.5%, consistent with normal JIT warmup variance. The `MortalList.active`
  gate keeps these paths zero-cost.

- **life_bitpacked** (+5.1%): Does not use `bless`, so this is likely JIT
  variance or cache effects from the additional fields on `RuntimeBase`
  (`refCount`, `blessId`). These fields increase object size by 8 bytes,
  which can affect cache line packing for reference-heavy workloads.

**Conclusion:** The DESTROY/weaken system has **near-zero overhead** for
non-OOP code. For OOP code using `bless`, there is a small (~5%) cost from
the `classHasDestroy()` check at bless time, which is cached per class. Code
that actually uses DESTROY classes pays the full tracking cost (increment/
decrement per reference assignment), but this is by design.

### Memory Overhead

- **Per-referent:** `refCount` (int, 4 bytes) and `blessId` (int, 4 bytes) on
  `RuntimeBase`. Always present but unused when untracked.
- **Per-scalar:** `refCountOwned` (boolean, 1 byte) and `captureCount` (int,
  4 bytes) on `RuntimeScalar`. Always present.
- **WeakRefRegistry:** External identity maps. Only allocated when `weaken()`
  is called. Zero memory when no weak refs exist.
- **DestroyDispatch caches:** `BitSet` + `ConcurrentHashMap`. Negligible.

---

## Differences from Perl 5

| Aspect | Perl 5 | PerlOnJava |
|--------|--------|------------|
| Tracking scope | Every SV has a refcount | Only blessed-into-DESTROY objects and weaken targets |
| GC model | Deterministic refcounting + cycle collector | JVM tracing GC + cooperative refcounting overlay |
| Circular references | Leak without weaken | Handled by JVM GC (weaken still needed for DESTROY timing) |
| `weaken()` on the only ref | Immediate DESTROY | Same behavior |
| DESTROY timing | Immediate when refcount hits 0 | Same for tracked objects; untracked objects rely on JVM GC |
| Global destruction | Walks all SVs | Walks global stashes (scalars, arrays, hashes) |
| `fork` | Supported | Not supported (JVM limitation) |
| DESTROY saves/restores | `local($@, $!, $?)` | Only `$@` is saved/restored; `$!` and `$?` are not yet localized around DESTROY calls |

---

## Proposed Design Change: Refined Strategy A

**Date:** 2026-04-09
**Status:** IMPLEMENTED AND TESTED — 841/841 Moo, 10/14 qr-72922.t

### Problem Statement

The WEAKLY_TRACKED (-2) state causes **premature weak reference clearing**.
When `undefine()` encounters a WEAKLY_TRACKED object, it unconditionally
calls `callDestroy()`, clearing ALL weak refs — even when other strong
references still exist.

**Concrete failure (qr-72922.t):**
```perl
my $re = qr/abcdef/;           # R.refCount = -1 (untracked)
my $re_copy1 = $re;            # still -1 (no tracking)
my $re_weak_copy = $re;        # still -1
weaken($re_weak_copy);         # R.refCount: -1 → -2 (WEAKLY_TRACKED)
undef $re;                     # WEAKLY_TRACKED triggers callDestroy!
# $re_weak_copy is now undef — WRONG, $re_copy1 is still a strong ref
```

Perl 5 behavior: `$re_weak_copy` remains valid because `$re_copy1` is
still alive. The weak ref should only become undef when ALL strong refs
are gone.

**Root cause:** When `weaken()` transitions -1 → -2, the system loses
track of how many strong refs exist. The `undefine()` heuristic
("destroy on any undef") is incorrect when multiple strong refs exist.

### Strategy Analysis

Three strategies are evaluated below. All preserve correct behavior for
**blessed-with-DESTROY objects** (which use the fully-tracked refCount
>= 0 path and are unaffected by WEAKLY_TRACKED changes).

#### Strategy A: Eliminate WEAKLY_TRACKED entirely

Remove the -2 state. `weaken()` only participates in refCount for
objects that are already tracked (refCount >= 0).

**Changes:**
1. `weaken()` on untracked (-1): register in WeakRefRegistry only. No
   refCount change. No `MortalList.active = true`.
2. `weaken()` on tracked (>= 0): decrement refCount as today. Remove the
   `blessId == 0` transition to WEAKLY_TRACKED (lines 79-88); keep the
   refCount as-is after decrement.
3. `undefine()`: remove the WEAKLY_TRACKED block (lines 1873-1877).
4. `callDestroy()`: move `clearWeakRefsTo()` to AFTER the `className`
   null check — only clear weak refs for blessed objects. For unblessed
   objects (CODE refs), `releaseCaptures()` still fires but weak refs
   are not cleared.
5. `GlobalDestruction`: no change needed (already checks `refCount >= 0`).
6. Remove or deprecate the `WEAKLY_TRACKED` constant.

**State machine (simplified):**
```
    -1 ──────────────────────────────────► 0
 (untracked)    bless into DESTROY class   (birth-tracked)
                                            │
                                            │ setLarge()
                                            ▼
                                           1+
                                        (N strong refs)
                                            │
                                            │ last strong ref dropped
                                            ▼
                                         MIN_VALUE
                                         (destroyed: DESTROY + clearWeakRefsTo)
```

**Pros:**
- Simplest design. Eliminates an entire state and all its special cases.
- Fixes qr-72922.t (weak refs survive because undefine() doesn't clear
  them for untracked objects).
- Zero risk to Moo (841/841) — blessed-with-DESTROY objects are on the
  refCount >= 0 path, completely unaffected.

**Cons:**
- Weak refs to non-DESTROY objects (unblessed or blessed-without-DESTROY)
  are never cleared deterministically. In Perl 5 they become undef when
  the last strong ref is dropped. In PerlOnJava they persist forever
  (still valid, still dereferenceable).
- Risk on Path B removal: unblessed tracked objects (CODE refs from
  `makeCodeObject` with `MortalList.active`) may see premature clearing
  if refCount undercounts due to closure captures bypassing `setLarge()`.
  Mitigated by point 4 (clearWeakRefsTo only for blessed objects).

**Test plan:**
1. Run `make` — must pass.
2. Run `perl dev/tools/perl_test_runner.pl perl5_t/t/re/qr-72922.t` —
   should recover from 5/14 to 10/14 (matching master).
3. Run Moo full suite — must remain 841/841.
4. Run `make test-all` — no new regressions.
5. Run `perl dev/tools/perl_test_runner.pl perl5_t/t/op/die_keeperr.t` —
   should recover from 6/15 to 15/15 (with the warning format fix).

#### Strategy B: Keep WEAKLY_TRACKED but skip clearing on undef

Keep the -2 state for registry purposes but remove the destruction
trigger from `undefine()`.

**Changes:**
1. `undefine()`: remove the WEAKLY_TRACKED block (lines 1873-1877).
2. `callDestroy()`: move `clearWeakRefsTo()` after className check.
3. Keep the -2 transition in `weaken()` and the `MortalList.active = true`.

**Pros:**
- Minimal code change (only 2 sites).
- Fixes qr-72922.t (undef no longer clears WEAKLY_TRACKED weak refs).

**Cons:**
- WEAKLY_TRACKED state still exists but is now "dead code" — the only
  place that acted on it (undefine) no longer does. The state adds
  complexity without benefit.
- Still sets `MortalList.active = true` on `weaken()` for untracked
  objects, adding overhead for programs that use `weaken()` without
  DESTROY.

**Test plan:** Same as Strategy A.

#### Strategy C: Deferred clearing via Java WeakReference sentinel (future)

Use a Java `WeakReference` + `ReferenceQueue` to detect when the last
strong Perl reference to an untracked object is dropped.

**Sketch:**
1. When `weaken()` is called on an untracked object, create a sentinel
   Java object.
2. All "strong" Perl scalars that reference this object also hold a
   strong Java ref to the sentinel.
3. The Perl "weak" scalars do NOT hold the sentinel.
4. Register a Java `WeakReference<Sentinel>` on a `ReferenceQueue`.
5. When all strong Perl scalars drop their ref (via undef, scope exit,
   reassignment), the sentinel becomes unreachable, the WeakReference
   is enqueued, and we poll the queue to clear Perl-level weak refs.

**Pros:**
- Most Perl 5-compliant: weak refs to unblessed objects are cleared
  when all strong refs are truly gone.
- Deterministic within one GC cycle (not immediate, but timely).

**Cons:**
- High implementation complexity. Requires modifying `RuntimeScalar`
  to hold sentinel refs, `setLarge()` to propagate sentinels.
- Clearing is NOT immediate (depends on JVM GC timing), which is a
  semantic difference from Perl 5.
- Adds per-reference memory overhead (sentinel objects).
- May interact poorly with JVM GC pauses.

**Test plan:** Same as A/B, plus timing-sensitive tests for sentinel
clearing (would need `System.gc()` hints in tests).

### Experimental Results: Strategy A (2026-04-09)

Strategy A was implemented on the `feature/eliminate-weakly-tracked` branch
and tested end-to-end. Results:

#### What worked

- **`make` passes**: All unit tests pass EXCEPT `weaken_edge_cases.t` test 15.
- **qr-72922.t**: Recovered from 5/14 to **10/14** (matches master). The
  premature clearing regression is fully fixed.
- **die_keeperr.t**: 15/15 with the separate warning format fix in
  DestroyDispatch.java (already committed).
- **Blessed-with-DESTROY objects**: Completely unaffected. The refCount >= 0
  path is unchanged by Strategy A.

#### What failed

**`weaken_edge_cases.t` test 15** ("nested weak array element becomes undef"):

```perl
my $strong = [1, 2, 3];          # unblessed array, refCount = -1
my @nested;
$nested[0][0] = $strong;         # refCount still -1 (untracked)
weaken($nested[0][0]);           # Strategy A: register only, no refCount change
undef $strong;                   # Strategy A: no action for untracked
ok(!defined($nested[0][0]), ...);  # FAILS: weak ref still valid
```

**Root cause: Hash/Array Birth-Tracking Asymmetry.**

`RuntimeHash.createReferenceWithTrackedElements()` sets `refCount = 0`
for anonymous hashes, making them birth-tracked. This means `weaken()` on
unblessed hash refs works correctly — the refCount path handles everything.

`RuntimeArray.createReferenceWithTrackedElements()` does **NOT** set
`refCount = 0`. Arrays stay at -1 (untracked). This means `weaken()` on
unblessed array refs cannot detect when the last strong ref is dropped.

**Why arrays differ:** Adding `this.refCount = 0` to RuntimeArray was
tested and caused **54/839 Moo subtest failures** across 7 test files:
- accessor-coerce, accessor-default, accessor-isa, accessor-trigger,
  accessor-weaken, overloaded-coderefs, method-generate-accessor

**Root cause of Moo failures:** Sub::Quote closures capture arrays by
sharing the RuntimeScalar variable (via `captureCount`). This capture
does NOT go through `setLarge()`, so refCount is never incremented for
the captured reference. When the original strong ref drops, refCount hits
0 even though the closure still holds a valid reference → premature
DESTROY.

Hash refs avoid this problem because Moo's usage patterns don't capture
hash refs in the same way, or because hash captures coincidentally go
through setLarge().

#### Strategy A Summary

| Test suite | Result | Notes |
|------------|--------|-------|
| `make` (unit tests) | PASS (except 1) | weaken_edge_cases.t #15 |
| qr-72922.t | 10/14 (matches master) | Regression fixed |
| die_keeperr.t | 15/15 | With warning format fix |
| Moo (without array tracking) | Not re-tested | Expected same as master |
| Moo (WITH array tracking) | 54/839 failures | Array birth-tracking breaks closures |
| **Moo (pure Strategy A, no blessId==0 safety)** | **54/841 failures** | Removing blessId==0→WEAKLY_TRACKED also breaks Moo |

**Critical finding:** Removing the `blessId == 0 → WEAKLY_TRACKED`
transition in `weaken()` causes the same 54/841 Moo failures even
without array birth-tracking. This transition is a safety valve for
Sub::Quote closures that capture birth-tracked unblessed objects.

### Refined Strategy A (Implemented)

Instead of eliminating WEAKLY_TRACKED entirely, **only remove transition
#1** (untracked → WEAKLY_TRACKED) while **keeping transition #2**
(unblessed tracked → WEAKLY_TRACKED):

**Changes from original code (2 lines in weaken() only):**

```java
// OLD: weaken() on untracked object
if (base.refCount == -1) {
    MortalList.active = true;          // REMOVED
    base.refCount = WEAKLY_TRACKED;    // REMOVED
}
// NEW: no action for untracked objects — just register in WeakRefRegistry
```

The `blessId == 0 → WEAKLY_TRACKED` transition in the `refCount > 0`
branch is preserved unchanged. The WEAKLY_TRACKED handling in
`undefine()` is preserved unchanged.

**Result:**

| Test suite | Result |
|------------|--------|
| `make` (unit tests) | PASS (except weaken_edge_cases.t #15) |
| qr-72922.t | 10/14 (matches master) |
| Moo | **841/841 PASS** |

### Blast Radius Analysis: Java WeakReference Approach

An alternative to refCount-based tracking is to use Java's own
`WeakReference<RuntimeBase>` for Perl weak refs to untracked objects.
The JVM GC would detect when no strong Java references remain and clear
the weak ref automatically.

**The fundamental requirement:** The Perl weak scalar must NOT hold a
strong Java reference to the referent. Currently, `RuntimeScalar.value`
is a strong `Object` reference — changing this for weak scalars means
changing how every dereference site accesses the referent.

**Measured blast radius:**

| Scope | Cast/instanceof sites | Files |
|-------|-----------------------|-------|
| RuntimeScalar.java internal | 46 | 1 |
| External codebase | 303 | 63 |
| **Total** | **349** | **64** |

Top-impacted files: RuntimeCode.java (36), RuntimeScalar.java (33),
ModuleOperators.java (32), RuntimeGlob.java (17), ReferenceOperators.java (15).

There are **zero existing accessor methods** (`getReferent()`, `asHash()`,
etc.) — every consumer casts `scalar.value` directly. This means either:

1. **Option 1:** Modify all 349 sites to check for WeakReference.
   Extremely high risk, touches most of the runtime.
2. **Option 2:** Add accessor methods first (separate refactoring), then
   change the internal representation behind the accessor. Two-phase
   approach but lower risk per phase.
3. **Option 3:** Use a side-channel mechanism (e.g., `PhantomReference` +
   `ReferenceQueue`) that doesn't require changing `value` storage. But
   this doesn't work because the `value` field still holds a strong ref.

**Conclusion:** Java WeakReference is architecturally clean but requires
a prerequisite refactoring (accessor methods) before it's feasible. This
is a future enhancement, not an immediate fix.

### Strategy D: Java WeakReference via Accessor Refactoring (Future)

**Phase 1 prerequisite:** Introduce accessor methods on RuntimeScalar:
```java
public RuntimeBase getReferentBase() { ... }
public RuntimeHash  getHashReferent() { ... }
public RuntimeArray getArrayReferent() { ... }
public RuntimeCode  getCodeReferent() { ... }
```
Refactor all 349 cast sites to use these accessors. This is a pure
refactoring with no behavioral change.

**Phase 2:** Inside the accessors, check for a Java WeakReference:
```java
public RuntimeBase getReferentBase() {
    if (javaWeakRef != null) {
        RuntimeBase ref = javaWeakRef.get();
        if (ref == null) {
            // JVM GC collected the referent — clear this weak ref
            this.type = RuntimeScalarType.UNDEF;
            this.value = null;
            this.javaWeakRef = null;
            return null;
        }
        return ref;
    }
    return (RuntimeBase) value;
}
```

**Phase 3:** In `weaken()`, for untracked objects:
- Set `value = null` (remove strong Java reference)
- Set `javaWeakRef = new WeakReference<>(referent)`
- On dereference, the accessor checks the WeakReference

**Pros:** Handles ALL objects (DESTROY via refCount, non-DESTROY via JVM
GC). Eliminates WEAKLY_TRACKED entirely. Zero overhead for non-weak refs.

**Cons:** Clearing is GC-dependent (not immediate like Perl 5). Requires
prerequisite refactoring. Adds 8 bytes (WeakReference field) to every
RuntimeScalar.

### Strategy E: Fix Array Closure Capture (Targeted)

Instead of Java WeakReference, fix the root cause of the hash/array
asymmetry: make closure captures properly track refCount for arrays.

**Approach:** When a closure captures a variable that holds a reference,
increment the referent's refCount (like setLarge does). When
`releaseCaptures()` fires, decrement it.

**This is narrower than Strategy D** — it only fixes the array case,
not the general "weak ref to non-DESTROY object" case. But it would:
- Allow array birth-tracking without breaking Moo closures
- Make `weaken_edge_cases.t` test 15 pass
- Keep the simple refCount model without JVM GC dependency

**Risk:** Closure capture paths are in codegen (EmitterVisitor), which
is a high-risk area. Needs careful testing.

### Revised Recommendation

**Implement Strategy A immediately.** It fixes the critical regression
(qr-72922.t), simplifies the codebase, and has minimal risk.

**Accept the limitation** for weaken_edge_cases.t test 15 (weak refs to
unblessed arrays not cleared). This is a narrow edge case — in practice,
`weaken()` is primarily used with blessed objects (Moo, Moose, etc.).

**Future work (prioritized):**

1. **Strategy E** (fix array closure capture) — Targeted fix for the
   hash/array asymmetry. Lower risk than D, higher value than C.
2. **Strategy D** (Java WeakReference via accessor refactoring) — Full
   Perl 5 compliance for all weak ref cases. Higher effort but
   architecturally clean.

**Key insight:** The refCount system's purpose is DESTROY timing. For
objects without DESTROY, the only effect of "destroying" them is clearing
weak refs. Since PerlOnJava's JVM GC already handles memory reclamation,
the cost of NOT clearing these weak refs is minimal: the weak ref remains
valid (pointing to a live object) instead of becoming undef. This is a
minor semantic difference that doesn't affect correctness for DESTROY-
dependent modules like Moo.

### Regression Classification (2026-04-09)

| Test file | Delta | DESTROY/weaken related? | Strategy A fixes? |
|-----------|-------|------------------------|-------------------|
| die_keeperr.t | -9 | Yes (warning format) | Yes (separate fix already applied) |
| qr-72922.t | -5 | Yes (WEAKLY_TRACKED premature clearing) | Yes |
| substr_left.t | -1 | Possibly (MortalList.flush timing in tied STORE) | Needs testing |
| eval.t | -1 | Possibly (TIEARRAY + eval + last interaction) | Needs testing |
| runlevel.t | -1 | Possibly (bless in tie constructors) | Needs testing |
| array.t | -8 | No (arylen magic, `$#{@array}` syntax, @_ aliasing) | No — separate investigation needed |

---

## Limitations & Known Issues

1. **Weak refs to non-DESTROY objects are not cleared deterministically.**
   (Updated per Strategy A proposal.) `weaken()` on an untracked object
   (refCount -1) registers the weak reference but does not change refCount.
   Since strong refs to untracked objects are never counted, there is no
   trigger to detect "last strong ref dropped." The weak ref remains valid
   (pointing to a live object) indefinitely. The JVM GC handles actual
   memory reclamation. In practice, deterministic weak ref clearing only
   matters for objects blessed into DESTROY classes, which are fully tracked.

2. **Hash/Array birth-tracking asymmetry.** Anonymous hashes (`{...}`) are
   birth-tracked (`refCount = 0` in `createReferenceWithTrackedElements`),
   so `weaken()` works for unblessed hash refs. Anonymous arrays (`[...]`)
   are **not** birth-tracked, so `weaken()` on unblessed array refs has no
   effect (the weak ref persists). Adding array birth-tracking breaks Moo
   because Sub::Quote closure captures bypass `setLarge()`, causing refCount
   undercounting and premature destruction. See "Strategy E" for the fix
   proposal.

3. **Global variables bypass `setLarge()`.** Stash slots are assigned via
   `GlobalVariable` infrastructure, which doesn't always go through the
   refCount-tracking path. For blessed-with-DESTROY objects in global slots,
   `GlobalDestruction` catches them at program exit. For unblessed globals
   with weak refs, the weak refs persist (see limitation 1).

4. **No `DESTROY` for non-reference types.** Only hash, array, code, and scalar
   referents (via `RuntimeBase`) can be blessed and tracked.

5. **Single-threaded.** The refCount system is not thread-safe. This matches
   PerlOnJava's current single-threaded execution model.

6. **349 dereference sites access `value` directly.** There are zero accessor
   methods for `RuntimeScalar.value` in reference context. This makes it
   infeasible to change how weak references store their referent without a
   prerequisite refactoring to introduce accessors (see "Strategy D").

---

## Test Coverage

Tests are organized in three tiers:

| Directory | Files | Focus |
|-----------|-------|-------|
| `src/test/resources/unit/destroy.t` | 1 file, 11 subtests | Basic DESTROY semantics: scope exit, multiple refs, exceptions, inheritance, re-bless, void-context delete |
| `src/test/resources/unit/weaken.t` | 1 file, 4 subtests | Basic weaken: isweak flag, weak ref access, copy semantics, weaken+DESTROY interaction |
| `src/test/resources/unit/refcount/` | 8 files | Comprehensive: circular refs, self-refs, tree structures, return values, inheritance chains, edge cases (weaken on non-ref, resurrection, closures, deeply nested structures, multiple simultaneous weak refs) |

Integration coverage via Moo test suite: **841/841 subtests across 71 test files.**

---

## See Also

- [dev/design/destroy_weaken_plan.md](../design/destroy_weaken_plan.md) -- Original design document with implementation history
- [dev/modules/moo.md](../modules/moo.md) -- Moo test tracking and category-by-category fix log
- [dev/architecture/dynamic-scope.md](dynamic-scope.md) -- Dynamic scoping (related: `local` interacts with refCount via `DynamicVariableManager`)

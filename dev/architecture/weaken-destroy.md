# Weaken & DESTROY - Architecture Guide

**Last Updated:** 2026-04-08
**Status:** PRODUCTION READY - 841/841 Moo subtests passing
**Branch:** `feature/destroy-weaken`

---

## Overview

PerlOnJava implements Perl 5's `DESTROY` and `Scalar::Util::weaken` semantics
using a **cooperative reference counting scheme** layered on top of the JVM's
tracing garbage collector. The system is designed around two principles:

1. **Zero cost when unused.** Programs that never `bless` into a class with
   `DESTROY` pay no runtime overhead -- every hot path is guarded by a single
   boolean (`MortalList.active`).

2. **Correctness over completeness.** The system tracks only objects that
   *need* tracking (blessed into a DESTROY class, or targeted by `weaken`),
   avoiding the full Perl 5 reference-counting burden.

---

## Core Concepts

### refCount State Machine

Every `RuntimeBase` (the superclass of `RuntimeHash`, `RuntimeArray`,
`RuntimeCode`, `RuntimeScalar` as referent) carries a `refCount` field:

```
                bless into DESTROY class
    -1 ───────────────────────────────────► 0
 (untracked)                            (birth-tracked)
    │                                       │
    │ weaken() on                          │ setLarge() copies ref
    │ untracked referent                   │ into a variable
    │                                       │
    ▼                                       ▼
   -2                                      1+
 (WEAKLY_TRACKED)                      (N strong refs)
    │                                       │
    │ explicit undef                       │ last strong ref dropped
    │ or scope exit                        │ (decrement hits 0)
    │                                       │
    ▼                                       ▼
  MIN_VALUE ◄──────────────────────────── MIN_VALUE
  (destroyed)                            (destroyed)
       │
       └──► DestroyDispatch.callDestroy()
            WeakRefRegistry.clearWeakRefsTo()
```

| Value | Meaning |
|-------|---------|
| `-1` | **Untracked.** Default state. Object is unblessed or blessed into a class without DESTROY. No refCount bookkeeping occurs. |
| `0` | **Birth-tracked.** Freshly blessed into a DESTROY class. No variable holds a reference yet -- `setLarge()` will increment to 1 on first assignment. |
| `> 0` | **Tracked.** N strong references exist in named variables. Each `setLarge()` assignment increments; each scope exit or reassignment decrements. |
| `-2` | **WEAKLY_TRACKED.** A named/global object has weak references but its strong reference count cannot be accurately tracked (e.g., the object lives in a stash slot that bypasses `setLarge()`). Explicit `undef` or global destruction will trigger DESTROY. |
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
| `weaken(ref)` | Validates ref is a reference. Adds to both maps. Adjusts refCount: if untracked (-1), transitions to WEAKLY_TRACKED (-2). If tracked (>0), decrements strong count (may trigger DESTROY if it hits 0). |
| `isweak(ref)` | Returns `weakScalars.contains(ref)`. |
| `unweaken(ref)` | Removes from both maps. Re-increments refCount and restores `refCountOwned`. |
| `removeWeakRef(ref, oldReferent)` | Called by `setLarge()` before decrementing. Returns true if the ref was weak, telling the caller to skip the refCount decrement. |
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
2. Calls `WeakRefRegistry.clearWeakRefsTo(referent)`.
3. If referent is `RuntimeCode`, calls `releaseCaptures()`.
4. Looks up class name from `blessId`. If unblessed, returns.
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
| `flush()` | **Primary flush point.** Processes all pending entries: decrements refCount, fires DESTROY on those hitting 0. Uses index-based loop because DESTROY may add new entries. |
| `pushMark()` / `popAndFlush()` | Scoped flushing -- only processes entries added since the last mark. |
| `mortalizeForVoidDiscard(result)` | For void-context call results: ensures never-stored blessed objects still get DESTROY. |

**Flush points:** `MortalList.flush()` is called:
- After every reference assignment in `setLarge()`.
- After `undefine()`.
- After cascading destruction in `DestroyDispatch.doCallDestroy()`.
- At scope exit via generated bytecode.

### 4. RuntimeScalar -- Reference Tracking Integration

**Path:** `org.perlonjava.runtime.RuntimeScalar`

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
- WEAKLY_TRACKED (-2): immediately sets MIN_VALUE and calls DESTROY.
- Tracked (>0): decrements; DESTROY if it hits 0.
- Flushes `MortalList` at the end.

### 5. bless() -- Tracking Activation

**Path:** `org.perlonjava.runtime.operators.ReferenceOperators.bless()`

The `bless()` function is the entry point for refCount tracking:

| Scenario | refCount | refCountOwned |
|----------|----------|---------------|
| First bless into DESTROY class | `0` (birth-tracked) | unchanged |
| Re-bless from untracked class into DESTROY class | `1` | `true` |
| Re-bless (already tracked) into DESTROY class | unchanged | unchanged |
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
# scope exit: $b refCount 2 -> 1 -> 0 -> DESTROY
# clearWeakRefsTo($a's referent via $b->{peer}) -> $b->{peer} = undef
# $a refCount 1 -> 0 -> DESTROY
```

### Example 3: WEAKLY_TRACKED Global

```perl
our $cache;
$cache = bless {}, 'Cached';    # refCount stays -1 (untracked global)
weaken($weak = $cache);         # refCount: -1 -> -2 (WEAKLY_TRACKED)
undef $cache;                   # WEAKLY_TRACKED -> MIN_VALUE -> DESTROY
                                # $weak becomes undef
```

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

---

## Limitations & Known Issues

1. **`weaken()` is a no-op for unblessed objects without other strong refs in
   tracked positions.** The JVM GC handles these, but DESTROY won't fire
   deterministically. In practice this only matters for objects blessed into
   DESTROY classes, which are fully tracked.

2. **Global variables bypass `setLarge()`.** Stash slots are assigned via
   `GlobalVariable` infrastructure, which doesn't always go through the
   refCount-tracking path. The `WEAKLY_TRACKED (-2)` state handles this: when
   `weaken()` is called on a referent with `refCount == -1`, it transitions to
   `-2` so that explicit `undef` or global destruction will still fire DESTROY
   and clear weak refs.

3. **No `DESTROY` for non-reference types.** Only hash, array, code, and scalar
   referents (via `RuntimeBase`) can be blessed and tracked.

4. **Single-threaded.** The refCount system is not thread-safe. This matches
   PerlOnJava's current single-threaded execution model.

---

## Test Coverage

Tests are organized in three tiers:

| Directory | Files | Focus |
|-----------|-------|-------|
| `src/test/resources/unit/destroy.t` | 1 file, 11 subtests | Basic DESTROY semantics: scope exit, multiple refs, exceptions, inheritance, re-bless, void-context delete |
| `src/test/resources/unit/weaken.t` | 1 file, 4 subtests | Basic weaken: isweak flag, weak ref access, copy semantics, weaken+DESTROY interaction |
| `src/test/resources/unit/refcount/` | 3 files, ~85 assertions | Comprehensive: circular refs, self-refs, tree structures, edge cases (weaken on non-ref, resurrection, closures, deeply nested structures, multiple simultaneous weak refs) |

Integration coverage via Moo test suite: **841/841 subtests across 71 test files.**

---

## See Also

- [dev/design/destroy_weaken_plan.md](../design/destroy_weaken_plan.md) -- Original design document with implementation history
- [dev/modules/moo.md](../modules/moo.md) -- Moo test tracking and category-by-category fix log
- [dev/architecture/dynamic-scope.md](dynamic-scope.md) -- Dynamic scoping (related: `local` interacts with refCount via `DynamicVariableManager`)

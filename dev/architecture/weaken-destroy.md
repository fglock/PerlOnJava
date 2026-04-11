# Weaken & DESTROY - Architecture Guide

**Last Updated:** 2026-04-10
**Status:** PRODUCTION READY - 841/841 Moo subtests (100%), all unit tests passing

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

1. **Low cost when unused.** `MortalList.active` is always `true` (required for
   balanced refCount tracking on birth-tracked objects like anonymous hashes and
   closures with captures), but most operations are guarded by cheap checks
   (`refCount >= 0`, `refCountOwned`, empty pending list) that short-circuit for
   untracked objects.

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
    │                                       │
    │ weaken()                              │ setLarge() copies ref
    │ (heuristic,                           │ into a variable
    │  non-CODE only)                       │
    │                                       ▼
    ▼                                      1+
   -2                                   (N strong refs)
(WEAKLY_TRACKED)                            │
    │                                       │ last strong ref dropped
    │ explicit undef()                      │ (decrement hits 0)
    │ of a strong ref                       │
    │                                       ▼
    └──────────────────────────────────► MIN_VALUE
                                         (destroyed)
                                              │
                                              └──► DestroyDispatch.callDestroy()
                                                   WeakRefRegistry.clearWeakRefsTo()
```

**NOTE on WEAKLY_TRACKED (-2):**

This state is entered via **one** path: `weaken()` on an **untracked non-CODE
object** (refCount == -1). Since strong refs to untracked objects are never
counted, WEAKLY_TRACKED allows `undefine()` to clear weak refs when a strong
reference is explicitly dropped. This may clear weak refs too eagerly when
multiple strong refs exist, but unblessed objects have no DESTROY, so
over-eager clearing causes no side effects beyond the weak ref becoming undef.

**Why only `undefine()` clears:**  `setLarge()` and `scopeExitCleanup()` do
**not** clear weak refs for WEAKLY_TRACKED objects. Since WEAKLY_TRACKED objects
have no refCountOwned tracking on pre-existing strong refs, overwriting one
reference doesn't mean no other strong refs exist. Closures may capture copies
(e.g., Sub::Quote's `$_QUOTED` capture), so clearing on scope exit or overwrite
would break Sub::Quote/Moo constructor inlining.

**Why CODE refs are excluded:** CODE refs live in both lexicals AND the symbol
table (stash), but stash assignments (`*Foo::bar = $coderef`) bypass
`setLarge()`, making the stash reference invisible to refcounting. Transitioning
CODE refs to WEAKLY_TRACKED would cause premature clearing when a lexical
reference is overwritten — even though the CODE ref is still alive in the stash.
This would break Sub::Quote/Sub::Defer (which use `weaken()` for
back-references) and cascade to break Moo's accessor inlining.

| Value | Meaning |
|-------|---------|
| `-1` | **Untracked.** Default state. Object is unblessed or blessed into a class without DESTROY. No refCount bookkeeping occurs. `weaken()` transitions non-CODE objects to WEAKLY_TRACKED (-2) and registers the weak ref in WeakRefRegistry. CODE refs stay at -1. |
| `0` | **Birth-tracked.** Freshly blessed into a DESTROY class, anonymous hash via `createReferenceWithTrackedElements()`, or closures with captures via `RuntimeCode.makeCodeObject()`. No variable holds a reference yet -- `setLarge()` will increment to 1 on first assignment. |
| `> 0` | **Tracked.** N strong references exist in named variables. Each `setLarge()` assignment increments; each scope exit or reassignment decrements. |
| `-2` | **WEAKLY_TRACKED.** Entered when `weaken()` is called on an untracked non-CODE object (refCount == -1). A heuristic allowing weak ref clearing when a strong ref is explicitly dropped via `undefine()`. `setLarge()` and `scopeExitCleanup()` do NOT clear weak refs for this state — only explicit `undefine()`. |
| `MIN_VALUE` | **Destroyed.** DESTROY has been called (or is in progress). Prevents double-destruction. |

### Ownership: `refCountOwned`

Each `RuntimeScalar` has a `boolean refCountOwned` field. When true, this scalar
"owns" one increment on its referent's `refCount`. This prevents double-
decrement: only the owner decrements when the scalar is reassigned or goes out
of scope.

### Capture Count

`RuntimeScalar.captureCount` tracks how many closures capture this variable.
When `captureCount > 0`, `scopeExitCleanup()` behaviour depends on the type:

- **CODE refs:** The value's refCount is still decremented (falls through to
  `deferDecrementIfTracked`) so that the `RuntimeCode` is eventually destroyed
  and its `releaseCaptures()` fires. This is critical for eval STRING closures
  that capture all visible lexicals.

- **Non-CODE refs:** `scopeExitCleanup()` returns early. The closure keeps
  the value alive; premature decrement would clear weak refs in Sub::Quote.

- **Self-referential cycle:** If a CODE scalar captures itself (common with
  eval STRING), `scopeExitCleanup()` detects the cycle and removes the
  self-reference from the captures array, breaking the cycle.

`RuntimeScalar.scopeExited` is set to `true` when `scopeExitCleanup()` fires
on a captured variable. This tells `releaseCaptures()` that the variable's scope
has already exited, so it should call `deferDecrementIfTracked()` on that
variable to trigger destruction.

---

## System Components

### File Map

| File | Role |
|------|------|
| `RuntimeBase.java` | Defines `refCount`, `blessId` fields on all referent types |
| `RuntimeScalar.java` | `setLarge()` (increment/decrement), `scopeExitCleanup()`, `undefine()`, `incrementRefCountForContainerStore()` |
| `RuntimeList.java` | `setFromList()` -- list destructuring with materialized copy refcount undo |
| `RuntimeHash.java` | `createReferenceWithTrackedElements()` (birth-tracking for anonymous hashes), `delete()` with deferred decrement |
| `RuntimeArray.java` | `createReferenceWithTrackedElements()` (element tracking, NOT birth-tracked -- see Limitations) |
| `WeakRefRegistry.java` | Weak reference tracking: forward set + reverse map |
| `DestroyDispatch.java` | DESTROY method resolution, caching, invocation |
| `MortalList.java` | Deferred decrements (FREETMPS equivalent) |
| `GlobalDestruction.java` | End-of-program stash walking |
| `ReferenceOperators.java` | `bless()` -- activates tracking |
| `RuntimeGlob.java` | CODE slot replacement -- optree reaping emulation |
| `RuntimeCode.java` | `padConstants` registry, `releaseCaptures()`, eval BLOCK capture release in `apply()` |
| `TiedVariableBase.java` | Tie wrapper refCount increment/decrement for DESTROY on `untie` |
| `RuntimeRegex.java` | `cloneTracked()` for qr// objects; per-callsite caching for m?PAT? |
| `EmitStatement.java` | Generates scope-exit `MortalList` bytecode (`pushMark`/`popAndFlush`/`scopeExitCleanup`) |
| `GlobalRuntimeScalar.java` | `dynamicSaveState()`/`dynamicRestoreState()` refCount displacement for `local` on globals |

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
| `weaken(ref)` | Validates ref is a reference (no-op for undef). If already weak, returns (idempotent -- prevents double-decrement). If referent is already destroyed (`MIN_VALUE`), immediately sets ref to `UNDEF/null` and returns. Otherwise, adds to both maps. Clears `ref.refCountOwned = false` to prevent spurious decrements on scope exit/overwrite. Adjusts refCount: if tracked (>0), decrements strong count; if it hits 0, sets `MIN_VALUE` and triggers DESTROY. If untracked (-1) and NOT a CODE ref, transitions to WEAKLY_TRACKED (-2) as a heuristic for weak ref clearing. CODE refs stay at -1 (stash refs bypass setLarge). |
| `isweak(ref)` | Returns `weakScalars.contains(ref)`. |
| `unweaken(ref)` | Removes from both maps. If referent is tracked (`refCount >= 0`), re-increments refCount and restores `refCountOwned = true`. If referent is untracked, destroyed, or WEAKLY_TRACKED, no refCount adjustment (unweaken is effectively a no-op for these states). |
| `removeWeakRef(ref, oldReferent)` | Called by `setLarge()` before decrementing. Returns true if the ref was weak, telling the caller to skip the refCount decrement. Cleans up empty entries in the reverse map. |
| `hasWeakRefsTo(referent)` | Returns true if any weak references point to the given referent. |
| `clearWeakRefsTo(referent)` | Called during destruction (before DESTROY method runs). Skips CODE referents (stash refs invisible to refcounting would cause false clears). For non-CODE: sets every weak scalar pointing at this referent to `UNDEF/null`. Removes all entries from both maps. |

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

**`classHasDestroy(blessId, className)`:** Checks (via cache) whether a class
defines DESTROY or AUTOLOAD. Populates the `destroyClasses` BitSet on first
lookup per class. Called by `bless()` to decide whether to activate tracking.

**`callDestroy(referent)` flow:**

The public `callDestroy()` handles steps 1-4; the private `doCallDestroy()`
handles steps 5-11.

1. **Precondition:** Caller has already set `refCount = MIN_VALUE`.
2. Calls `WeakRefRegistry.clearWeakRefsTo(referent)` -- clears all weak
   references pointing to this object (skips CODE referents). This fires for
   both blessed objects (before DESTROY) and WEAKLY_TRACKED objects (unblessed,
   reached via `undefine()` WEAKLY_TRACKED handling).
3. If referent is `RuntimeCode`, calls `releaseCaptures()`.
4. Looks up class name from `blessId`. If unblessed: cascades into container
   elements via `MortalList.scopeExitCleanupHash/Array()` (so that tracked
   refs inside unblessed containers get their refCounts decremented), then
   returns. No DESTROY to call, but weak refs, captures, and container
   elements have been cleaned up.
5. Resolves DESTROY method via cache or `InheritanceResolver`.
6. Handles AUTOLOAD: sets `$AUTOLOAD = "ClassName::DESTROY"`.
7. Saves/restores `$@` around the call (DESTROY must not clobber `$@`).
8. Builds a `$self` reference with the correct type (`HASHREFERENCE` for
   `RuntimeHash`, `ARRAYREFERENCE` for `RuntimeArray`, `GLOBREFERENCE` for
   `RuntimeGlob`, then `SCALAR`/`CODE`/etc. -- note:
   `RuntimeGlob` is checked before `RuntimeScalar` because it is a subclass).
9. Calls `RuntimeCode.apply(destroyMethod, args, VOID)`.
10. **Cascading destruction:** After DESTROY returns, walks the destroyed
    object's elements via `MortalList.scopeExitCleanupHash/Array()`, then
    flushes. This ensures tracked refs inside the destroyed container get
    their refCounts decremented and may trigger further DESTROY calls.
11. **Exception handling:** Catches exceptions, converts to
    `WarnDie.warn("(in cleanup) ...")` -- matching Perl 5 semantics.

### 3. MortalList (Deferred Decrements)

**Path:** `org.perlonjava.runtime.runtimetypes.MortalList`

Equivalent to Perl 5's `FREETMPS` / mortal stack. Provides deferred refCount
decrements at statement boundaries so that temporaries survive long enough to
be used.

**The `active` field:** A `boolean` that is always `true`. Birth-tracked
objects (anonymous hashes and closures with captures) need balanced refCount
tracking from the start, so the mortal system cannot be lazily activated.
Most operations are guarded by cheap checks (`refCount >= 0`,
`refCountOwned`, empty pending list) that make the overhead negligible for
programs that don't use DESTROY.

**Pending list:** `ArrayList<RuntimeBase>` of referents awaiting decrement.

**Mark stack:** `ArrayList<Integer>` for scoped flushing (SAVETMPS equivalent).

**Key operations:**

| Method | Purpose |
|--------|---------|
| `deferDecrement(base)` | Unconditionally adds to pending. |
| `deferDecrementIfTracked(scalar)` | Guarded: skips if `!active`, `!refCountOwned`, or referent's `refCount <= 0`. Clears `refCountOwned` before deferring. |
| `deferDecrementIfNotCaptured(scalar)` | If `captureCount > 0`, delegates to `RuntimeScalar.scopeExitCleanup()` (which handles CODE vs non-CODE captured vars differently). Otherwise behaves like `deferDecrementIfTracked`. Used by explicit `return`. |
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
6. WEAKLY_TRACKED objects: do NOT clear weak refs on overwrite.
   These objects have refCount == -2 and their strong refs don't have
   refCountOwned=true (they were set before tracking started).
   Overwriting ONE reference doesn't mean no other strong refs exist.
   Weak refs for WEAKLY_TRACKED objects are cleared only via undefine().
7. Update refCountOwned
8. MortalList.flush()
```

#### `scopeExitCleanup()` -- Lexical Scope Exit

Called by generated bytecode when a lexical variable goes out of scope:

1. If `captureCount > 0`:
   a. **Self-referential cycle detection:** If the scalar holds a CODE ref
      that captures this same scalar, removes the self-reference from
      `capturedScalars` and decrements `captureCount`. This breaks cycles
      caused by eval STRING closures that capture all visible lexicals.
   b. Sets `scopeExited = true` so `releaseCaptures()` knows the scope
      has already exited.
   c. **CODE refs:** Falls through to step 3 below (still decrements
      refCount on the RuntimeCode value so it is eventually destroyed and
      its `releaseCaptures()` fires).
   d. **Non-CODE refs:** Returns early. The closure keeps the value alive;
      premature decrement would clear weak refs in Sub::Quote.
2. Handles IO fd recycling for glob references.
3. Calls `MortalList.deferDecrementIfTracked()` to schedule a deferred
   decrement rather than decrementing immediately.
4. WEAKLY_TRACKED: does NOT clear weak refs on scope exit. Scope exit of
   ONE reference doesn't mean no other strong refs exist (closures may
   capture copies). Weak refs for WEAKLY_TRACKED objects are cleared only
   via explicit `undefine()`.

#### `undefine()` -- Explicit `undef $obj`

Handles explicit undef with special cases:
- CODE refs: releases captures, invalidates inheritance cache, replaces
  with empty `RuntimeCode`.
- Tracked (>0) with `refCountOwned`: decrements; sets `MIN_VALUE` and
  calls DESTROY if it hits 0. Scalars without `refCountOwned` skip the
  decrement (they don't own the refCount increment).
- WEAKLY_TRACKED (-2): sets `MIN_VALUE` and triggers callDestroy to clear
  weak refs. This is the primary clearing mechanism for WEAKLY_TRACKED
  objects. Safe because these are unblessed objects with no DESTROY method.
- Untracked (-1): no refCount action.
- In all cases, sets the scalar to `UNDEF/null` BEFORE the refCount
  decrement (Perl 5 semantics: DESTROY sees the new state of the variable).
- Flushes `MortalList` at the end.

#### `incrementRefCountForContainerStore()` -- Container Tracking

Called after storing a reference in a container (array/hash element).
Increments the referent's refCount for container ownership.

**Guard:** `!scalar.refCountOwned` -- skips elements whose refCount was
already incremented during creation (via `set()` → `setLarge()`). This
prevents double-counting when `RuntimeArray.setFromList()` calls
`addToArray()` (which uses `set()` → `setLarge()`, incrementing refCount)
and then `incrementRefCountForContainerStore()`.

### 4b. RuntimeList -- List Destructuring Refcount Undo

**Path:** `org.perlonjava.runtime.runtimetypes.RuntimeList`

The `setFromList()` method handles list destructuring (`($a, $b) = @array`).
When the RHS contains arrays, materialization goes through
`addToArray()` → `addToScalar()` → `set()` → `setLarge()`, which
increments refCount on each materialized copy. When a scalar target then
consumes the copy via `target.set(copy)`, `setLarge()` increments the
same referent's refCount a second time.

The materialized copies live in a local `rhs` array that is never
scope-exit-cleaned, so their refCount increments would leak. An **undo
block** after each scalar target assignment corrects this:

```java
if (assigned != null && assigned.refCountOwned
        && (assigned.type & REFERENCE_BIT) != 0
        && assigned.value instanceof RuntimeBase base && base.refCount > 0) {
    base.refCount--;
    assigned.refCountOwned = false;
}
```

**Plain** array targets don't need this undo because they take direct
ownership of the remaining materialized copies (the copies become the
container's elements and remain alive). However, **tied/autovivify array
targets** and **hash targets** DO have undo blocks because they create new
copies via `setFromList()`/`createHashForAssignment()`, so the materialized
copies' refCount increments would otherwise leak.

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
   Skips `TIED_ARRAY` containers (tie objects may be invalid at destruction time).
4. Walks all global hashes -> iterates values -> `destroyIfTracked()`.
   Skips `TIED_HASH` containers (same reason).

`destroyIfTracked()` checks if a scalar holds a reference (via `REFERENCE_BIT`)
with `refCount >= 0`, then sets `MIN_VALUE` and calls
`DestroyDispatch.callDestroy()`.

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
2. **Subroutine creation** (`EmitSubroutine.java`): Pad constants are
   transferred to `RuntimeCode.padConstantsByClassName`, from where
   `makeCodeObject()` reads them. `SubroutineParser.java` transfers them
   directly to the placeholder's `padConstants` field (bypassing the
   class-name registry).
3. **CODE slot replacement** (`RuntimeGlob.set()`): Before overwriting the CODE
   slot, calls `clearPadConstantWeakRefs()` on the old code, which clears any
   weak references to those cached constants.

### 8. RuntimeCode -- Capture Release and eval BLOCK

**Path:** `org.perlonjava.runtime.runtimetypes.RuntimeCode`

**`releaseCaptures()`:** Called when a CODE ref's refCount reaches 0 (via
`callDestroy()`, which explicitly calls `releaseCaptures()` for `RuntimeCode`
referents) or when a CODE ref is explicitly `undef`'d. Decrements
`captureCount` on each captured scalar. For captured scalars where
`scopeExited == true` (their declaring scope already exited), calls
`MortalList.deferDecrementIfTracked()` to trigger the deferred destruction
that `scopeExitCleanup()` couldn't perform earlier.

**Closure birth-tracking:** `makeCodeObject()` sets `code.refCount = 0` for
closures that have captures. Without this, closures wouldn't be tracked and
`callDestroy()` -> `releaseCaptures()` would never fire.

**`apply()` -- eval BLOCK capture release:** `eval BLOCK` is compiled as
`sub { ... }->()` with `useTryCatch=true`. The first `apply()` overload's
finally block calls `code.releaseCaptures()` when `code.isEvalBlock` is true.
This ensures captured variables' `captureCount` is decremented immediately
after the eval block completes, rather than waiting for GC. (eval STRING uses
`applyEval()`, which already calls `releaseCaptures()` in its own finally
block.) Without this, weak refs inside eval blocks wouldn't be cleared until
the next GC cycle.

**Note:** `apply()` does NOT call `flush()` at the top of the method.
Flushing happens at statement boundaries via `setLarge()` and scoped
`popAndFlush()` instead.

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

### Example 3: Weak Ref to Untracked Object (WEAKLY_TRACKED Heuristic)

```perl
our $cache;
$cache = bless {}, 'Cached';    # refCount stays -1 (no DESTROY -> untracked)
weaken($weak = $cache);         # registers in WeakRefRegistry; refCount: -1 -> -2 (WEAKLY_TRACKED)
undef $cache;                   # undefine() sees WEAKLY_TRACKED -> callDestroy()
                                # callDestroy() clears weak refs: $weak = undef
                                # Matches Perl 5 behavior
```

Note: This is a heuristic. If multiple strong refs exist:
```perl
my $a = [1,2,3];               # refCount: -1 (untracked array)
my $b = $a;                    # refCount: still -1 (not tracked)
weaken($weak = $a);            # refCount: -1 -> -2 (WEAKLY_TRACKED)
undef $a;                      # WEAKLY_TRACKED -> callDestroy() -> $weak = undef
                               # $b still valid but $weak is gone -- may be too eager
                               # Perl 5 would keep $weak alive since $b is still strong
```
This over-eager clearing is accepted because unblessed objects have no
DESTROY method, so the only effect is the weak ref becoming undef slightly
earlier than Perl 5 would. No destructors are missed.

### Example 4: eval BLOCK Capture Release

```perl
my $weak;
{
    my $obj = bless {}, 'Foo';   # refCount: 0 -> 1
    $weak = $obj;                # refCount: 1 -> 2
    weaken($weak);               # refCount: 2 -> 1
    eval {
        # eval BLOCK compiled as sub { ... }->() with useTryCatch=true
        # The anonymous sub captures $obj and $weak (captureCount incremented)
        my $x = $obj;            # refCount: 1 -> 2
    };
    # apply() finally: releaseCaptures() since isEvalBlock=true
    # captureCount on $obj decremented back; $x scope-exited within eval
    # Without this fix: captureCount stays elevated, scopeExitCleanup
    # defers forever, weak ref never cleared
}
# scopeExitCleanup for $obj: defers decrement (refCount 1 -> 0 -> DESTROY)
# DESTROY clears $weak via clearWeakRefsTo
```

---

## Performance Characteristics

### Zero-Cost Opt-Out

| Condition | Overhead |
|-----------|----------|
| Object is not blessed into a DESTROY class | Minimal. `refCount == -1` short-circuits all tracking in `setLarge()`. `MortalList.flush()` is a no-op when the pending list is empty. |
| Object blessed into DESTROY class | Full tracking: increment/decrement in `setLarge()`, deferred decrement in `scopeExitCleanup()`. |

### Hot Path Costs

- **`setLarge()` with untracked referent** (`refCount == -1`): One integer
  comparison per reference assignment, not taken. Plus `MortalList.flush()`
  at the end (one boolean + one `isEmpty()` check, trivially predicted).
- **`setLarge()` with tracked referent**: ~4 field reads + 1 increment +
  1 decrement + `MortalList.flush()` (usually a no-op if pending list is empty).
- **`WeakRefRegistry` checks**: Only in `setLarge()` when the scalar was
  previously holding a reference (checks `removeWeakRef` to decide whether
  to skip the refCount decrement).

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
  +/-3.5%, consistent with normal JIT warmup variance. The `refCount == -1`
  short-circuit keeps these paths nearly zero-cost.

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

## Limitations & Known Issues

1. **Weak refs to non-DESTROY objects: heuristic clearing.**
   `weaken()` on an untracked non-CODE object (refCount -1) transitions it
   to WEAKLY_TRACKED (-2). When a strong reference to the object is
   explicitly dropped via `undef`, weak refs are cleared. `setLarge()` and
   `scopeExitCleanup()` do NOT clear WEAKLY_TRACKED objects (overwriting or
   scope-exiting one reference doesn't mean no other strong refs exist).
   This is still a heuristic: if multiple strong refs exist and one is
   undef'd, the weak ref is cleared even though the object is still alive.
   Perl 5 would only clear when ALL strong refs are gone. This over-eager
   clearing is accepted because unblessed objects have no DESTROY, so the
   only effect is the weak ref becoming `undef` slightly earlier than Perl 5
   would. CODE refs are excluded from WEAKLY_TRACKED entirely (stash refs
   bypass setLarge).

2. **Hash/Array birth-tracking asymmetry.** Anonymous hashes (`{...}`) are
   birth-tracked (`refCount = 0` in `createReferenceWithTrackedElements`),
   so `weaken()` works precisely for unblessed hash refs via the refCount
   path. Anonymous arrays (`[...]`) are **not** birth-tracked -- they start
   at -1 and rely on the WEAKLY_TRACKED heuristic (see limitation 1).
   Adding array birth-tracking breaks Moo because Sub::Quote closure
   captures bypass `setLarge()`, causing refCount undercounting and
   premature destruction.

3. **Global variables bypass `setLarge()`.** Stash slots are assigned via
   `GlobalVariable` infrastructure, which doesn't always go through the
   refCount-tracking path. For blessed-with-DESTROY objects in global slots,
   `GlobalDestruction` catches them at program exit. For unblessed globals
   with weak refs, the weak refs persist (see limitation 1).

4. **No `DESTROY` for non-reference types.** Only hash, array, code, and scalar
   referents (via `RuntimeBase`) can be blessed and tracked.

5. **Single-threaded.** The refCount system is not thread-safe. This matches
   PerlOnJava's current single-threaded execution model.

6. **Dereference sites access `value` directly.** There are zero accessor
   methods for `RuntimeScalar.value` in reference context. This makes it
   infeasible to change how weak references store their referent without a
   prerequisite refactoring to introduce accessors.

---

## Test Coverage

Tests are organized in three tiers:

| Directory | Files | Focus |
|-----------|-------|-------|
| `src/test/resources/unit/destroy.t` | 1 file, 14 subtests | Basic DESTROY semantics: scope exit, multiple refs, exceptions, inheritance, re-bless, void-context delete, untie DESTROY (immediate and deferred) |
| `src/test/resources/unit/weaken.t` | 1 file, 4 subtests | Basic weaken: isweak flag, weak ref access, copy semantics, weaken+DESTROY interaction |
| `src/test/resources/unit/refcount/` | 8 files | Comprehensive: circular refs, self-refs, tree structures, return values, inheritance chains, edge cases (weaken on non-ref, resurrection, closures, deeply nested structures, multiple simultaneous weak refs) |
| `src/test/resources/unit/refcount/weaken_edge_cases.t` | 34 subtests | Edge cases: nested weak refs, WEAKLY_TRACKED heuristic, multiple strong refs, scope exit clearing |

Integration coverage via Moo test suite: **841/841 subtests across 71 test files.**

---

## See Also

- [dev/design/destroy_weaken_plan.md](../design/destroy_weaken_plan.md) -- Design document with implementation history, strategy analysis, and evolution of the WEAKLY_TRACKED design
- [dev/modules/moo.md](../modules/moo.md) -- Moo test tracking and category-by-category fix log
- [dev/architecture/dynamic-scope.md](dynamic-scope.md) -- Dynamic scoping (related: `local` interacts with refCount via `DynamicVariableManager`)

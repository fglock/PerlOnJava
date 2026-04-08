# DESTROY and weaken() Implementation Plan

**Status**: Implementation — type-aware weaken() for WEAKLY_TRACKED scope exit  
**Version**: 5.6  
**Created**: 2026-04-08  
**Updated**: 2026-04-08 (v5.6 — type-aware weaken transition for non-DESTROY data structures + POSIX::_do_exit)  
**Supersedes**: `object_lifecycle.md` (design proposal)  
**Related**: PR #450 (WIP, open), `dev/modules/poe.md` (DestroyManager attempt)

---

## 1. Overview

This document is the implementation plan for two tightly coupled Perl features:

1. **DESTROY** — Destructor methods called when blessed objects become unreachable
2. **weaken/isweak/unweaken** — Weak references that don't prevent destruction

Both features require knowing when the "last strong reference" to an object is gone.
Perl 5 solves this with reference counting; PerlOnJava runs on the JVM's tracing GC.
This plan bridges that gap with targeted reference counting for blessed objects,
with global destruction at shutdown as the safety net for escaped references —
matching Perl 5's own semantics for circular references and missed decrements.

### 1.1 Why This Matters

DESTROY and weaken() are among the last major Perl 5 compatibility gaps in PerlOnJava.
They are not niche features — they are load-bearing infrastructure for the CPAN ecosystem:

- **Moo/Moose** (the dominant OO frameworks) require `isweak()` for accessor validation.
  Currently 20+ Moo test failures come from `isweak()` always returning false.
- **Test2/Test::Builder** (the testing infrastructure everything depends on) uses `weaken()`
  to break circular references between contexts and hubs.
- **IO resource management** — `IO::Handle`, `File::Temp`, `Net::SMTP`, `Net::Ping` all
  define DESTROY methods to close handles and clean up resources. Without DESTROY, resources
  leak until JVM shutdown.
- **Event frameworks** — POE's event loop hangs because `POE::Wheel` DESTROY never fires,
  leaving orphan watchers registered in the kernel.
- **Scope guards** — `autodie::Scope::Guard`, `Guard`, `Scope::Guard` all rely on DESTROY.

Approximately 20+ bundled Perl modules define DESTROY methods that currently never fire,
and ~27 call `weaken()` that currently does nothing.

### 1.2 What's Blocked

| Module/Feature | Needs DESTROY | Needs weaken | Notes |
|----------------|:---:|:---:|-------|
| Moo/Moose accessors | | x | `isweak()` always false, 20+ test failures |
| IO::Handle, IO::File | x | | `close()` in DESTROY for resource cleanup |
| File::Temp | x | | Delete temp files in DESTROY |
| POE::Wheel::* | x | | Unregister watchers, causes event loop hangs |
| Test2::* | | x | Circular ref breaking in test framework |
| Net::SMTP, Net::Ping | x | | Close network connections |
| autodie::Scope::Guard | x | | Scope guard pattern |

---

## 2. Lessons from Prior Attempts

### 2.1 PR #450 — Eager DESTROY Without Refcounting

**Approach**: Call DESTROY at explicit trigger points (`undef $obj`, `delete $hash{key}`,
loop-scope exit) using a `destroyCalled` flag to prevent double-DESTROY.

**What worked**:
- `callDestroyIfNeeded()` static method — correct DESTROY dispatch via
  `InheritanceResolver.findMethodInHierarchy()`, catches exceptions as "(in cleanup)" warnings
- `destroyCalled` flag on `RuntimeBase` — prevents double-DESTROY
- Hooking `RuntimeHash.delete()` and `RuntimeScalar.undefine()` — correct trigger points

**What failed**: Extending scope-exit DESTROY beyond loop bodies to all scopes caused 20+
unit test failures. Without reference counting, DESTROY fires on the FIRST scope exit
even when the object is returned or stored elsewhere:

```perl
sub make_obj {
    my $obj = Foo->new();
    return $obj;           # $obj exits scope, but object should live
}
my $x = make_obj();       # $x receives a "destroyed" object — WRONG
```

**Lesson**: Reference counting is necessary for correct DESTROY timing.

### 2.2 DestroyManager — GC-Based with Proxy Objects

**Approach**: Used `java.lang.ref.Cleaner` to detect GC-unreachable blessed objects,
then reconstructed a proxy object to pass as `$_[0]` to DESTROY.

**Why it failed**:
1. **Proxy corruption**: `close()` inside DESTROY on a proxy hash corrupts subsequent
   hash access ("Not a HASH reference" in File::Temp)
2. **BlessId collision**: `Math.abs(blessId)` collided for overloaded classes (negative IDs)
3. **Fundamental limitation**: Cleaner's cleaning action cannot hold a strong reference to
   the tracked object. Proxy reconstruction can't replicate tied/magic/overloaded behavior.

**Lesson**: DESTROY must receive the *real* object, not a proxy. GC-based approaches
have a fundamental tension: DESTROY needs the object alive, but GC triggers when it's
dying. Refcounting avoids this by calling DESTROY deterministically before GC.

---

## 3. Alternatives Considered

| # | Approach | Pros | Cons | Verdict |
|---|----------|------|------|---------|
| A | **Full refcounting on ALL objects** (like Perl 5) | Correct Perl semantics for everything | Massive perf impact — every scalar copy needs inc/dec; JVM has no stack-local optimization | Rejected: too expensive |
| B | **GC-only (Cleaner, no refcounting)** | Simple, no tracking overhead | Non-deterministic timing breaks tests; proxy problem (see §2.2); previously attempted and failed | Rejected: fundamentally wrong timing |
| C | **Scope-based without refcounting** (PR #450 style) | Simple, deterministic for single-scope | Wrong for returned objects, objects stored in outer scopes — 20+ failures (see §2.1) | Rejected: incorrect without refcount |
| D | **Compile-time escape analysis** | Zero runtime overhead for proven-local objects | Impossible to do perfectly (dynamic dispatch, eval, closures, `push @global, $obj`) | Rejected: too incomplete |
| E | **Explicit destructor registration** (`defer { $obj->cleanup }`) | Simple, deterministic, no refcounting | Not compatible with Perl 5 semantics; breaks existing modules | Rejected: not Perl-compatible |
| **F** | **Targeted refcounting for blessed-with-DESTROY + global destruction at shutdown** | Deterministic for common cases; zero overhead for unblessed; matches Perl 5 semantics for cycles | May miss decrements in obscure paths; overcounted objects delayed to shutdown | **Chosen** |

**Why F**: It's the only approach that provides correct timing for the common case (lexical
scope, explicit undef, hash delete) while still handling escaped references via global
destruction — the same strategy Perl 5 uses. The key insight is that we don't need to
refcount ALL objects — only the small subset that are blessed AND whose class defines
DESTROY. The existing `ioHolderCount` pattern on `RuntimeGlob` proves this targeted
approach works in this codebase.

**Why not a Cleaner safety net**: v4.0 of this plan included a `java.lang.ref.Cleaner`
sentinel pattern as a GC-based fallback. Analysis revealed a fundamental flaw: the
Cleaner needs the object alive for DESTROY, but holding the object alive prevents the
sentinel from becoming phantom-reachable. The workaround (separate sentinel/trigger
indirection) adds significant complexity, 8 bytes per RuntimeBase instance, and thread
safety concerns — all for cases that Perl 5 itself handles the same way (DESTROY at
global destruction). Dropping the Cleaner eliminates Phase 4, removes threading
concerns, and reduces per-object memory overhead to +4 bytes (fits in alignment padding).

---

## 4. Optimizations

Performance is critical — refcount overhead must not regress the hot path. The design uses
several interlocking optimizations to achieve near-zero overhead for common operations.

### 4.1 Four-State refCount (Eliminates `destroyCalled` boolean)

Instead of a separate `destroyCalled` boolean, encode the destruction state in `refCount`:

```
refCount == -1                 →  Not tracked (unblessed, or blessed without DESTROY)
refCount == 0                  →  Tracked, zero counted containers (fresh from bless)
refCount > 0                   →  Being tracked; N named-variable containers exist
refCount == Integer.MIN_VALUE  →  DESTROY already called (or in progress)
```

**Why four states instead of three**: Bytecode analysis (see §4A) revealed that the
RuntimeScalar created by `bless()` is almost always a temporary — it lives in a JVM
local or interpreter register and travels through the return chain without being
explicitly cleaned up. Setting `refCount = 0` at bless time (instead of 1) means the
bless-time container is not counted. Only when the value is copied into a named `my`
variable via `setLarge()` does refCount increment to 1.

This eliminates one field from `RuntimeBase` and replaces three separate checks
(`blessId != 0`, `hasDestroy`, `destroyCalled`) with a single integer comparison.

The field is initialized as `refCount = -1` (untracked) in `RuntimeBase`. This means
all objects — unblessed references, arrays, hashes — start as untracked by default.
Only `bless()` for a class with DESTROY sets `refCount = 0` to begin tracking.

### 4.2 Zero Fast-Path Cost

The existing `set()` fast path is:
```java
public RuntimeScalar set(RuntimeScalar value) {
    if (this.type < TIED_SCALAR & value.type < TIED_SCALAR) {  // bitwise AND, no branch
        this.type = value.type;
        this.value = value.value;
        return this;
    }
    return setLarge(value);
}
```

All reference types have `type >= 0x8000` (REFERENCE_BIT), so they ALWAYS take the slow
path through `setLarge()`. **Refcount logic lives only in `setLarge()`**, meaning the hot
path (int/double/string/undef/boolean assignments) pays zero cost.

### 4.3 Unified Gate: `refCount >= 0`

In `setLarge()`, the entire refcount block is:

```java
// NEW: Track blessed-object refCount (after existing ioHolderCount block)

// Save old referent BEFORE the assignment (for correct DESTROY ordering)
RuntimeBase oldBase = null;
if ((this.type & REFERENCE_BIT) != 0 && this.value != null) {
    oldBase = (RuntimeBase) this.value;
}

// Increment new value's refCount
if ((value.type & REFERENCE_BIT) != 0) {
    RuntimeBase newBase = (RuntimeBase) value.value;
    if (newBase.refCount >= 0) newBase.refCount++;
}

// Do the assignment
this.type = value.type;
this.value = value.value;

// Decrement old value's refCount AFTER assignment (Perl 5 semantics:
// DESTROY sees the new state of the variable, not the old)
if (oldBase != null && oldBase.refCount > 0 && --oldBase.refCount == 0) {
    oldBase.refCount = Integer.MIN_VALUE;
    DestroyDispatch.callDestroy(oldBase);
}
```

**DESTROY ordering**: In Perl 5, assignment completes before the old value's refcount
drops. If DESTROY accesses the variable being assigned to, it sees the new value:
```perl
our $global;
sub DESTROY { print $global }
$global = MyObj->new;
$global = "new_value";  # Perl 5: DESTROY sees "new_value", not the old object
```
The code above ensures this by saving oldBase, performing the assignment, then
decrementing. This requires one extra local variable but is necessary for correctness.

**Cost for the common case** (unblessed reference, or blessed without DESTROY):
1. `(type & REFERENCE_BIT) != 0` — one bitwise AND, true (we're in setLarge with a ref)
2. Cast `value` to `RuntimeBase` — zero cost (type reinterpretation)
3. `refCount >= 0` — one integer comparison, **false** (untracked = -1) → branch not taken

Total overhead: **one integer comparison per reference assignment** for untracked objects.

### 4.4 Only Track Classes with DESTROY

At `bless()` time, check if the class defines DESTROY (or AUTOLOAD). If not, leave
`refCount == -1`. The `refCount >= 0` gate in `setLarge()` skips all tracking.

Use a `BitSet` indexed by `|blessId|` to cache the result per class:

```java
private static final BitSet destroyClasses = new BitSet();

static boolean classHasDestroy(int blessId, String className) {
    int idx = Math.abs(blessId);
    if (destroyClasses.get(idx)) return true;
    // First time for this class — check hierarchy
    RuntimeScalar m = InheritanceResolver.findMethodInHierarchy("DESTROY", className, null, 0);
    if (m == null) m = InheritanceResolver.findMethodInHierarchy("AUTOLOAD", className, null, 0);
    if (m != null) { destroyClasses.set(idx); return true; }
    return false;
}
```

Clear the `BitSet` on `InheritanceResolver.invalidateCache()` (when `@ISA` changes or
methods are redefined).

### 4.5 Defer Collection Cleanup

Iterating arrays/hashes at scope exit is O(n) per collection. Instead of doing this
deterministically for all collections, defer to global destruction for blessed refs
inside collections that go out of scope.

Deterministic DESTROY covers:
- Scalar lexicals going out of scope (`scopeExitCleanup`)
- `undef $obj` (explicit drop)
- `delete $hash{key}` (explicit removal)
- Scalar overwrite (`$obj = other_value`)

This handles the vast majority of real-world patterns. The remaining cases (blessed refs
stranded inside a collected array/hash) get DESTROY at global destruction — the same
behavior as Perl 5 for circular references.

**Optional future optimization**: Add a `boolean containsTrackedRef` flag to
`RuntimeArray`/`RuntimeHash`. Set on store when `refCount >= 0`. At scope exit, only
iterate if the flag is set. This makes deterministic collection cleanup cheap for the
common case (flag is false for 99%+ of collections).

### 4.6 REFERENCE_BIT Accessibility

`REFERENCE_BIT` is currently `private` in `RuntimeScalarType`. The refcount code in
`setLarge()` needs direct access to it (using `RuntimeScalarType.isReference()` adds
an unnecessary method call + READONLY_SCALAR unwrap check per call). Make it
package-private or add a public constant for the bitmask.

### 4.7 DESTROY Method Caching

Cache the resolved DESTROY method per `blessId` to avoid hierarchy traversal on every call:

```java
private static final ConcurrentHashMap<Integer, RuntimeScalar> destroyMethodCache =
    new ConcurrentHashMap<>();
```

Invalidate alongside `destroyClasses` BitSet when inheritance changes.

### 4.8 Global Destruction via Stash Walking

At shutdown, the global destruction hook walks all package stashes and global
variables to find objects with `refCount >= 0` that still need DESTROY. No
persistent tracking set is needed during program execution — the `refCount`
field on `RuntimeBase` is the sole tracking mechanism.

This avoids pinning objects in memory. Without a global set holding strong
references, overcounted objects (where `refCount` stays > 0 after all user
references are gone) are collected by the JVM's tracing GC. Their DESTROY
does not fire, but no memory leaks either. This is a deliberate trade-off:
the JVM's ability to reclaim memory for unreachable objects is preserved.

Objects that ARE reachable at shutdown (global variables, stash entries, closures
still on the call stack) get deterministic DESTROY during global destruction.

#### Alternative: Tracked-Objects Set

If testing reveals that too many DESTROY calls are missed at shutdown (objects
unreachable from stashes but with overcounted `refCount`), a `trackedObjects`
set can be reintroduced as an opt-in:

```java
private static final Set<RuntimeBase> trackedObjects =
    Collections.newSetFromMap(new IdentityHashMap<>());
```

This set would be populated at `bless()` time and drained at DESTROY time.
At shutdown, the hook walks the set instead of stashes. The cost is that every
entry is a strong reference, pinning the object and its entire reachable graph
in memory until shutdown — reintroducing Perl 5's circular-reference leak
behavior, plus leaking non-circular overcounted objects. For short-lived
programs this is fine; for long-running servers it can accumulate significantly.

The stash-walking approach is preferred as the default because it preserves
the JVM's memory management advantage over Perl 5.

### 4.9 Memory Impact: Zero

Adding `refCount` to `RuntimeBase`:

```
RuntimeScalar layout (current):              RuntimeScalar layout (with refCount):
  Object header:          12 bytes             Object header:          12 bytes
  RuntimeBase.blessId:     4 bytes             RuntimeBase.blessId:     4 bytes
  RuntimeScalar.type:      4 bytes             RuntimeBase.refCount:    4 bytes  ← NEW
  RuntimeScalar.value:     4 bytes             RuntimeScalar.type:      4 bytes
  RuntimeScalar.ioOwner:   1 byte              RuntimeScalar.value:     4 bytes
  ─────────────────────────                    RuntimeScalar.ioOwner:   1 byte
  Total: 25 bytes → pad to 32                  ─────────────────────────
                                               Total: 29 bytes → pad to 32
```

**Memory cost: zero** — `refCount` (4 bytes) fits in existing alignment padding.
No additional fields or data structures are needed during program execution.
Global destruction uses stash walking (no persistent tracking overhead).

### 4.10 Optimization Summary

| Optimization | What it avoids | Cost |
|-------------|----------------|------|
| Four-state refCount | Separate `destroyCalled` field; overcounting from bless temp | One fewer field per object |
| Fast-path bypass | Any refcount work on int/double/string/undef | Zero — refs always take slow path |
| `refCount >= 0` gate | Tracking unblessed or no-DESTROY objects | One integer comparison |
| `destroyClasses` BitSet | DESTROY lookup on every bless() | One bit check per bless() |
| Defer collection cleanup | O(n) iteration at scope exit | Global destruction for collections |
| DESTROY method cache | Hierarchy traversal on every DESTROY call | One map lookup |
| Stash walking at shutdown | Persistent tracking set that pins objects in memory | One-time stash scan at exit |
| Post-assignment DESTROY | Incorrect variable state during DESTROY | One extra local variable |
| MortalList defer-decrement | Premature DESTROY on delete return values | One boolean check per statement |
| `MortalList.active` gate | flush()/deferDecrement overhead for programs without DESTROY | One boolean (trivially predicted false) |

---

## 4A. Bytecode Trace: How Values Flow Through Function Returns

This section documents the findings from disassembling `my $x = make_obj()` through
both the JVM backend (`--disassemble`) and the interpreter backend (`--int`), and
reading the source for every method in the chain.

### 4A.1 Key Findings

1. **Both backends share the same runtime methods.** The interpreter's `MY_SCALAR` opcode
   calls `addToScalar()` → `set()` → `setLarge()`, exactly like the JVM backend's emitted
   `invokevirtual addToScalar`.

2. **No copies through the return chain.** `return $obj` wraps the SAME RuntimeScalar
   Java object in a RuntimeList (`getList()` = `new RuntimeList(this)`). `RuntimeCode.apply()`
   returns it directly. `RuntimeList.scalar()` returns the same object (`return this`).

3. **Copies happen only at `my` declarations and assignments.** `addToScalar(target)` calls
   `target.set(this)` → `setLarge()`, which copies `type` and `value` fields (shallow copy).

4. **The return epilogue does NOT call `emitScopeExitNullStores`.** The `return` statement
   jumps to `returnLabel` → `materializeSpecialVarsInResult` → `popToLocalLevel` → `ARETURN`.
   No scope cleanup for `my` variables on the return path.

5. **`emitScopeExitNullStores` IS emitted for all normal scope exits** (blocks, loops,
   if/else branches). It calls `scopeExitCleanup()` on ALL `my $`-prefixed scalars in scope,
   then nulls all `my` variable slots.

### 4A.2 The Overcounting Problem

Each function boundary creates a "traveling" RuntimeScalar container that gets a refCount
increment when its value is copied into a named variable via `setLarge()`, but the traveling
container itself is never decremented because JVM doesn't hook local variable cleanup.

**Trace for `{ my $obj = Foo->new; }` with original `refCount=1` design:**
```
Foo::new:
  createHashRef()  → rs_new (type=HASHREFERENCE, value=RuntimeHash)
  bless()          → refCount = 1       ← counts rs_new as a container
  return           → RuntimeList wraps rs_new by reference (no copy)

Caller:
  .scalar()        → extracts rs_new (same object) into temp local7
  NEW RuntimeScalar → rs_obj ($obj)
  rs_obj.setLarge(rs_new):
    increment: refCount 1 → 2          ← counts rs_obj
    old was UNDEF: no decrement
  
  scopeExitCleanup($obj) → refCount 2 → 1
  null local27

  temp local7 (rs_new) still has .value = RuntimeHash → NEVER cleaned up
  refCount = 1, but 0 reachable containers → DESTROY doesn't fire!
```

**The same trace with revised `refCount=0` design:**
```
Foo::new:
  bless()          → refCount = 0       ← rs_new NOT counted (it's a temporary)

Caller:
  rs_obj.setLarge(rs_new):
    increment: refCount 0 → 1          ← only rs_obj is counted

  scopeExitCleanup($obj) → refCount 1 → 0 → DESTROY fires! ✓
```

### 4A.3 Impact Per Function Boundary — Revised (v5.4)

With the v5.4 approach (deferred decrements + returnLabel cleanup), the overcounting
problem from v3.0 is resolved for the common single-boundary case:

| Pattern | v3.0 (init=0, no returnLabel cleanup) | v5.4 (deferred + returnLabel) | Deterministic? |
|---------|:---:|:---:|:---:|
| `{ my $o = Foo->new; }` | **0 → DESTROY** | **0 → DESTROY** | ✓ both |
| `my $x = Foo->new; undef $x;` | **0 → DESTROY** | **0 → DESTROY** | ✓ both |
| `my $x = make_obj(); undef $x;` | 1 (leak) | **0 → DESTROY** | ✓ **v5.4 fixes this** |
| `my $x = wrapper(make_obj()); undef $x;` | 2 (leak) | 1 (leak) | Global destruction |

**How v5.4 fixes the single-boundary case**: At `returnLabel`, `scopeExitCleanup` is
called for all my-scalar slots in the method (via `JavaClassInfo.allMyScalarSlots`).
With deferred decrements, the cleanup doesn't fire DESTROY immediately — the decrement
is enqueued in MortalList and flushed by the caller's `setLarge()` (which first
increments refCount for the assignment, then flushes the pending decrement).

**Rule**: Objects created and consumed in the same scope or its direct caller get
deterministic DESTROY. Objects that cross 2+ function boundaries accumulate +1 overcounting
per boundary after the first. Global destruction at shutdown handles these cases —
matching Perl 5 behavior for circular references.

### 4A.4 Why This Is Acceptable

The overwhelming majority of real-world DESTROY use cases are scope-based:
- **Scope guards** (`Guard`, `Scope::Guard`, `autodie::Scope::Guard`): object created
  and destroyed in the same scope → deterministic ✓
- **IO handles** (`IO::Handle`, `File::Temp`): typically `my $fh = IO::File->new(...)`
  → one boundary → deterministic ✓
- **POE wheels** (`delete $heap->{wheel}`): hash delete, no function boundary →
  deterministic ✓

The problematic pattern (returning objects through multiple wrappers) is less common and
is handled by global destruction at shutdown — the same way Perl 5 handles circular
references. DESTROY fires, just not immediately.

### 4A.5 Future Mitigation: Clone-on-Return (Optional)

If the delayed-until-shutdown timing proves problematic, deterministic DESTROY for returned objects can
be achieved by cloning the return value in the return epilogue:

1. Before `ARETURN`, create a new RuntimeScalar
2. Copy `type`/`value` from the return variable into the clone (`setLarge()` → refCount++)
3. Call `scopeExitCleanup()` on the original variable (refCount--)
4. Return the clone

This adds one object allocation + one `set()` per return. The infrastructure already
exists — `cloneScalars()` is already called in the return path when `usesLocal` is true.
This optimization could be applied selectively (only for functions that return blessed
references, detectable at compile time in some cases).

---

## 5. Design Overview

```
Blessed object created via bless()
        │
        ├── classHasDestroy(blessId)?
        │       │
        │    NO: leave refCount=-1 (untracked, zero overhead)
        │       │
        │    YES: set refCount=0 (tracked, zero containers — bless temp not counted)
        │         │
        │         ▼
        │   ┌─────────────────────────────────────────────────┐
        │   │  Targeted Reference Counting (setLarge, etc.)    │
        │   │                                                   │
        │   │  refCount >= 0: increment on store                │
        │   │  refCount > 0:  decrement on overwrite/undef/exit │
        │   │                                                   │
        │   │  --refCount == 0?  ──YES──►  Set MIN_VALUE        │
        │   │                              Call DESTROY          │
        │   └─────────────────────────────────────────────────┘
        │         │                            │
        │         │ refCount leaked?           │ refCount = MIN_VALUE
        │         │ (missed decrement)         │ (DESTROY already called)
        │         ▼                            ▼
        │   ┌──────────────────┐        ┌──────────────┐
        │   │ Global destruction│        │ Already done  │
        │   │ (shutdown hook    │        │ (skip)        │
        │   │  walks stashes    │        └──────────────┘
        │   │  for refCount>=0) │
        │   └──────────────────┘
        │
        └── continue (no refcount tracking)
```

**Key principles**:
- Deterministic DESTROY for single-boundary patterns (refcounting with init=0)
- Global destruction at shutdown for missed references (matching Perl 5 behavior)
- `refCount == Integer.MIN_VALUE` prevents double-DESTROY
- Zero overhead for unblessed objects and blessed objects without DESTROY
- No Cleaner, no daemon threads, no sentinel objects

---

## 6. Part 1: Reference Counting for Blessed Objects

### 6.1 The refCount Field

Add one field to `RuntimeBase`:

```java
public abstract class RuntimeBase implements DynamicState, Iterable<RuntimeScalar> {
    public int blessId;             // existing: class identity
    public int refCount = -1;       // NEW: four-state lifecycle counter (-1 = untracked)
}
```

**Important**: `refCount` MUST be explicitly initialized to `-1`. Java defaults `int`
fields to `0`, which would mean "tracked, zero containers" — silently breaking all
unblessed objects. The `= -1` initializer is load-bearing.

The field fits in the existing 8-byte alignment padding (see §4.9), so the per-object
memory cost is zero.

### 6.2 Refcount Tracking Points

#### Increment (store a tracked reference)

| Location | Code path |
|----------|-----------|
| Scalar assignment | `RuntimeScalar.setLarge()` — new value has `refCount >= 0` |
| Hash element store | Via `RuntimeScalar.set()` on the element → `setLarge()` |
| Array element store | Via `RuntimeScalar.set()` on the element → `setLarge()` |

Note: `local` restore does NOT increment the restored value (see §6.2 note on
`local` save/restore for explanation).

#### Decrement (drop a tracked reference)

| Trigger | Code path |
|---------|-----------|
| Scalar overwrite | `RuntimeScalar.setLarge()` — old value has `refCount > 0` |
| `undef $obj` | `RuntimeScalar.undefine()` |
| `delete $hash{key}` | `MortalList.deferDecrement()` — deferred to statement end (see §6.2A) |
| Scope exit (scalar lexicals) | `RuntimeScalar.scopeExitCleanup()` |
| `local` restore | `dynamicRestoreState()` — displaced current value (see §6.2 note) |
| Array `pop`/`shift`/`splice` | *(Phase 5)* `MortalList.deferDecrement()` — deferred to statement end (see §6.2A) |

#### Note on `local` save/restore

`dynamicSaveState()` copies `type`/`value` to a saved state and sets the current
scalar to UNDEF. `dynamicRestoreState()` puts the old value back, displacing the
current value.

Both methods currently do raw field copies. They need refCount adjustments:
- `dynamicSaveState()`: no-op for refCount (the referent is moving from the
  current scalar into the saved state — net zero container change). The saved
  state is created via raw field copy (not `setLarge()`), so it is *uncounted*.
  The referent's refCount remains inflated by 1 from when the original variable
  was stored via `setLarge()`. This inflation is intentional — it prevents
  premature DESTROY while the value is saved on the stack.
- `dynamicRestoreState()`: decrement refCount of the CURRENT value being
  displaced. Do NOT increment the restored value — it already has the correct
  refCount from its original counting (it was never decremented during save).
  Incrementing would permanently overcount by 1, preventing DESTROY from ever
  firing for `local`-ized globals.

**Trace showing why increment-on-restore is wrong:**
```
our $g = MyObj->new;     # setLarge: refCount 0→1
{
    local $g = MyObj2->new;
    # dynamicSaveState: MyObj moves to saved state (refCount stays 1)
    # $g = MyObj2: setLarge increments MyObj2 (0→1)
}
# dynamicRestoreState:
#   Decrement MyObj2: 1→0 → DESTROY fires ✓
#   Restore MyObj: refCount stays 1 (NOT incremented to 2) ✓
#   $g has MyObj, refCount=1, 1 container ($g) — correct
undef $g;   # refCount 1→0 → DESTROY fires ✓
```

#### Note on `GlobalRuntimeScalar` and proxy classes

Only `RuntimeScalar.dynamicSaveState/RestoreState` is discussed above, but
there are 21+ implementations of `DynamicState` across the codebase:
- `GlobalRuntimeScalar.dynamicSaveState/RestoreState` — for `local` on global scalars
- `RuntimeHashProxyEntry.dynamicSaveState/RestoreState` — for `local $hash{key}`
- `RuntimeArrayProxyEntry.dynamicSaveState/RestoreState` — for `local $array[$idx]`
- `GlobalRuntimeHash`, `GlobalRuntimeArray`, `RuntimeGlob`, etc.

The refCount displacement-decrement logic (decrement the displaced current value,
do NOT increment the restored value) must be applied consistently to all
implementations that displace scalar values:
- `RuntimeScalar` — lexical `local`
- `GlobalRuntimeScalar` — global `local`
- `RuntimeHashProxyEntry` — `local $hash{key}`
- `RuntimeArrayProxyEntry` — `local $array[$idx]`

Hash/array-level implementations (`RuntimeHash.dynamicSaveState`) swap entire
collections and don't need per-element tracking (Phase 5 scope).

#### Note on `RuntimeHash.delete()`

The current `delete()` implementation does `elements.remove(k)` and returns
`new RuntimeScalar(value)` using the copy constructor, which bypasses `setLarge()`.

**Problem**: The hash element was counted when stored (via `setLarge()`). When
removed, the refCount should eventually be decremented. But decrementing
*immediately* in `delete()` would cause premature DESTROY for patterns like
`my $v = delete $h{k}` — DESTROY fires before the caller can capture the value.
In Perl 5, `delete` returns a mortal (DESTROY deferred to statement end).

**Solution**: Use `MortalList.deferDecrement()` (see §6.2A) to schedule the
decrement for the end of the current statement. This gives the caller time to
store the return value. If stored, `setLarge()` increments, and the deferred
decrement produces the correct final refCount. If discarded, the deferred
decrement fires DESTROY.

This is critical for **POE::Wheel** patterns like `delete $heap->{wheel}` where
the deleted object needs immediate DESTROY to unregister event watchers.

#### Note on Array mutation methods (Phase 5)

`RuntimeArray.pop()` and `shift()` remove elements and return the **raw element**
directly (NOT a copy — the actual RuntimeScalar from the internal list is
returned). `splice()` is in `Operator.java` (not `RuntimeArray.java`) and returns
removed elements in a RuntimeList.

Like `delete()`, these methods remove a counted element from a container. The
removed element's refCount must be decremented, but not immediately — the
element is being returned to the caller who may store it.

**Deferred to Phase 5**: A survey of all blocked modules shows no real-world
pattern that needs deterministic DESTROY from pop/shift/splice of blessed objects.
When needed, the solution is the same as for `delete()`:

**Solution**: Use `MortalList.deferDecrement()` for each removed tracked element.

```java
// In RuntimeArray.pop() for PLAIN_ARRAY:
RuntimeScalar result = runtimeArray.elements.removeLast();
if (result != null) {
    // Schedule deferred decrement — fires at statement end
    MortalList.deferDecrementIfTracked(result);
    yield result;
}
yield scalarUndef;
```

#### Note on the copy constructor `RuntimeScalar(RuntimeScalar)`

The copy constructor (`new RuntimeScalar(scalar)`) copies `type` and `value`
fields without going through `setLarge()`. This means it does NOT increment
refCount. This is intentional and correct for temporaries (return values, method
arguments), matching the `refCount=0` design where temporaries are not counted.

However, code that uses the copy constructor to create a NAMED variable (e.g.,
`RuntimeScalar saved = new RuntimeScalar(current)` in `dynamicSaveState`) must
be audited. In `dynamicSaveState`, the saved copy replaces the current value
(which is set to UNDEF), so the net container count doesn't change — no
adjustment needed. But any new code that uses the copy constructor to create an
additional long-lived container must manually adjust refCount.

### 6.2A Mortal-Like Defer-Decrement Mechanism

Perl 5 uses "mortals" to keep values alive until the end of the current
statement (FREETMPS). Without this, `delete` would trigger DESTROY before the
caller can capture the returned value. This is critical for POE compatibility.

**Scope**: The initial implementation covers only `RuntimeHash.delete()`.
Array mutation methods (`pop`, `shift`, `splice`) are deferred to Phase 5 —
a survey of all blocked modules (POE, DBIx::Class, Moo, Template Toolkit,
Log4perl, Data::Printer, Test::Deep, etc.) shows no real-world pattern that
needs deterministic DESTROY from a popped/shifted blessed object. The POE
pattern that motivates this mechanism is specifically `delete $heap->{wheel}`.

PerlOnJava implements a lightweight equivalent: `MortalList`.

#### Design

```java
public class MortalList {
    // Global gate: false until the first bless() into a class with DESTROY.
    // When false, both deferDecrementIfTracked() and flush() are no-ops
    // (a single branch, trivially predicted). This means zero effective cost
    // for programs that never use DESTROY — which is the vast majority.
    public static boolean active = false;

    // List of RuntimeBase references awaiting decrement.
    // Populated by delete() when removing tracked elements.
    // Drained at statement boundaries (FREETMPS equivalent).
    private static final ArrayList<RuntimeBase> pending = new ArrayList<>();

    /**
     * Schedule a deferred refCount decrement for a tracked referent.
     * Called from delete() when removing a tracked blessed reference
     * from a container.
     */
    public static void deferDecrement(RuntimeBase base) {
        pending.add(base);
    }

    /**
     * Convenience: check if a RuntimeScalar holds a tracked reference
     * and schedule a deferred decrement if so.
     */
    public static void deferDecrementIfTracked(RuntimeScalar scalar) {
        if (!active) return;
        if ((scalar.type & REFERENCE_BIT) != 0
                && scalar.value instanceof RuntimeBase base
                && base.refCount > 0) {
            pending.add(base);
        }
    }

    /**
     * Process all pending decrements. Called at statement boundaries.
     * Equivalent to Perl 5's FREETMPS.
     */
    public static void flush() {
        if (!active || pending.isEmpty()) return;
        for (int i = 0; i < pending.size(); i++) {
            RuntimeBase base = pending.get(i);
            if (base.refCount > 0 && --base.refCount == 0) {
                base.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(base);
                // DESTROY may add new entries to pending — the loop
                // continues processing them (natural behavior of ArrayList).
            }
        }
        pending.clear();
    }
}
```

#### The `active` Flag

`MortalList.active` is set to `true` in `DestroyDispatch.classHasDestroy()`
the first time a class with DESTROY is seen. This means:
- Programs without DESTROY: `flush()` cost = one boolean check per statement
- Programs with DESTROY but no pending mortals: `flush()` cost = boolean + `isEmpty()`
- Programs with pending mortals: process the list (typically 0-1 entries)

#### Call Sites for `flush()` — Revised (v5.4)

**Problem with per-statement bytecode emission**: The original plan (v5.3) called for
emitting `INVOKESTATIC MortalList.flush()` at every statement boundary. Testing revealed
this causes `code_too_large.t` (a 4998-test file) to fail with `Java heap space` — the
extra 3 bytes per statement pushed the generated bytecode over heap limits.

**Revised approach**: Instead of bytecode-emitted flushes, call `MortalList.flush()` from
**runtime methods** that are naturally called at safe boundaries:

1. **`RuntimeCode.apply()`** — at the START, before executing the subroutine body.
   This ensures deferred decrements from the caller's previous statement are processed
   before the callee runs. Covers void-context function calls, `is_deeply()` assertions, etc.

2. **`RuntimeScalar.setLarge()`** — at the END, after the assignment completes.
   This ensures deferred decrements are processed when a return value or delete result
   is captured. For `my $val = delete $h{k}`, the assignment increments refCount first,
   then flush decrements — net effect: refCount unchanged (correct).

**Why this is sufficient**: Every Perl statement either assigns a value (triggers setLarge),
calls a function (triggers apply), or is a bare expression with no side effects. The only
edge case is a sequence of bare expressions with no assignments or calls between them, which
is extremely rare in practice and would be handled at the next scope exit or function call.

**Scope of flush sources**: MortalList entries come from:
- `scopeExitCleanup()` — deferred decrements for my-scalars going out of scope
- `RuntimeHash.delete()` — deferred decrements for removed tracked entries
- Future: `RuntimeArray.pop/shift/splice` (Phase 5)

#### Why This Is Needed for POE

POE::Wheel patterns use `delete $heap->{wheel}` to destroy a wheel and trigger
its DESTROY method, which unregisters event watchers from the kernel. Without
deferred decrement, two bad outcomes are possible:

- **No decrement** (overcounting): DESTROY delayed to global destruction. The
  event loop hangs because watchers are never unregistered. **This breaks POE.**
- **Immediate decrement** (premature DESTROY): For `my $w = delete $heap->{wheel}`,
  DESTROY fires before `$w` captures the value. This violates Perl 5 semantics.

The mortal mechanism gives the correct behavior: the decrement fires at statement
end, after the caller has (or hasn't) stored the return value.

#### Performance Impact

- `flush()` when `active == false`: one boolean check per statement (trivially predicted).
- `flush()` when `active == true` but empty: boolean + one `isEmpty()` call per statement.
- `pending` list: reused (clear, not reallocate). Typical size is 0-1 entries.
- No allocation in the common case (no tracked blessed refs being deleted).
- Only `RuntimeHash.delete()` populates the list initially. Array methods deferred to Phase 5.

#### The `setLarge()` Interception

Parallel to the existing `ioHolderCount` pattern at lines 832-839:

```java
private RuntimeScalar setLarge(RuntimeScalar value) {
    // ... existing: null guard, tied/readonly unwrap, ScalarSpecialVariable ...

    // Existing: ioHolderCount tracking for anonymous globs
    if (value.type == GLOBREFERENCE && value.value instanceof RuntimeGlob newGlob
            && newGlob.globName == null) {
        newGlob.ioHolderCount++;
    }
    if (this.type == GLOBREFERENCE && this.value instanceof RuntimeGlob oldGlob
            && oldGlob.globName == null) {
        oldGlob.ioHolderCount--;
    }

    // NEW: refCount tracking for blessed objects with DESTROY
    // Save old referent BEFORE assignment (for correct DESTROY ordering — see §4.3)
    RuntimeBase oldBase = null;
    if ((this.type & REFERENCE_BIT) != 0 && this.value != null) {
        oldBase = (RuntimeBase) this.value;
    }

    // Increment new value's refCount (>= 0 means tracked; -1 means untracked)
    if ((value.type & REFERENCE_BIT) != 0) {
        RuntimeBase nb = (RuntimeBase) value.value;
        if (nb.refCount >= 0) nb.refCount++;
    }

    // Do the assignment
    this.type = value.type;
    this.value = value.value;

    // Decrement old value's refCount AFTER assignment
    // (Perl 5 semantics: DESTROY sees new state of the variable)
    if (oldBase != null && oldBase.refCount > 0 && --oldBase.refCount == 0) {
        oldBase.refCount = Integer.MIN_VALUE;
        DestroyDispatch.callDestroy(oldBase);
    }

    return this;
}
```

### 6.3 Initialization at bless() Time

```java
public static RuntimeScalar bless(RuntimeScalar runtimeScalar, RuntimeScalar className) {
    if (!RuntimeScalarType.isReference(runtimeScalar)) {
        throw new PerlCompilerException("Can't bless non-reference value");
    }
    String str = className.toString();
    if (str.isEmpty()) str = "main";

    RuntimeBase referent = (RuntimeBase) runtimeScalar.value;
    int newBlessId = NameNormalizer.getBlessId(str);

    if (referent.refCount >= 0) {
        // Re-bless: update class, keep refCount
        referent.setBlessId(newBlessId);
        if (!DestroyDispatch.classHasDestroy(newBlessId, str)) {
            // New class has no DESTROY — stop tracking
            referent.refCount = -1;
        }
    } else {
        // First bless (or previously untracked)
        referent.setBlessId(newBlessId);
        if (DestroyDispatch.classHasDestroy(newBlessId, str)) {
            referent.refCount = 0;  // Start tracking (zero containers counted)
        }
        // If no DESTROY, leave refCount = -1 (untracked)
    }
    return runtimeScalar;
}
```

**Why `refCount = 0` instead of 1**: The RuntimeScalar returned by `bless()` is
typically a temporary that travels through the return chain without being explicitly
cleaned up (see §4A.2). Setting refCount=0 means the bless-time container is NOT
counted. Only when the value is copied into a named `my` variable via `setLarge()`
does refCount increment to 1. This eliminates the +1 overcounting at the first
function boundary.

### 6.4 Scope Exit Cleanup

Extend `scopeExitCleanup()` to handle blessed references:

```java
public static void scopeExitCleanup(RuntimeScalar scalar) {
    if (scalar == null) return;

    // Existing: IO fd recycling for anonymous filehandle globs
    if (scalar.ioOwner && scalar.type == GLOBREFERENCE
            && scalar.value instanceof RuntimeGlob glob
            && glob.globName == null) {
        // ... existing fd unregistration code ...
    }

    // NEW: Decrement refCount for blessed references with DESTROY
    if ((scalar.type & REFERENCE_BIT) != 0 && scalar.value instanceof RuntimeBase base
            && base.refCount > 0 && --base.refCount == 0) {
        base.refCount = Integer.MIN_VALUE;
        DestroyDispatch.callDestroy(base);
    }
}
```

### 6.5 Interpreter Backend Scope-Exit Cleanup

**CRITICAL**: The JVM backend emits `emitScopeExitNullStores()` (in
`EmitStatement.java`) which calls `RuntimeScalar.scopeExitCleanup()` on each
`my` scalar going out of scope. This is where DESTROY fires for lexical
variables at scope exit.

The interpreter backend (`BytecodeCompiler`) has **no equivalent**. On scope
exit, it resets the register counter (`exitScope()` → `nextRegister =
savedNextRegister.pop()`). The old register values are simply overwritten by
later code. No cleanup opcodes are emitted. **DESTROY will not fire at scope
exit for `my` variables in the interpreter backend without this fix.**

#### Implementation

Add a new opcode `SCOPE_EXIT_CLEANUP` that calls `scopeExitCleanup()` on each
`my` scalar register in the exiting scope:

```java
// In BytecodeCompiler, before exitScope():
// Emit cleanup for each my-scalar register going out of scope
List<Integer> scalarRegs = symbolTable.getMyScalarIndicesInScope(currentScopeIndex);
if (!scalarRegs.isEmpty()) {
    for (int reg : scalarRegs) {
        emit(Opcodes.SCOPE_EXIT_CLEANUP);
        emitReg(reg);
    }
}
exitScope();
```

```java
// In BytecodeInterpreter, handle SCOPE_EXIT_CLEANUP:
case Opcodes.SCOPE_EXIT_CLEANUP -> {
    int reg = opcodes[ip++];
    RuntimeScalar.scopeExitCleanup(registers[reg]);
    registers[reg] = null;
}
```

The `symbolTable.getMyScalarIndicesInScope()` API already exists and is used by
the JVM backend's `emitScopeExitNullStores()`.

#### Files to Modify

- `Opcodes.java` — add `SCOPE_EXIT_CLEANUP` opcode constant
- `BytecodeCompiler.java` — emit cleanup opcodes before `exitScope()`
- `BytecodeInterpreter.java` — handle `SCOPE_EXIT_CLEANUP` opcode
- `Disassemble.java` — add disassembly text for new opcode

### 6.6 Edge Case: Pre-bless Copies

```perl
my $hashref = {};
my $copy = $hashref;     # copy exists BEFORE blessing
bless $hashref, 'Foo';   # refCount set to 0, but there are 2 containers
```

The `$copy` was stored before `blessId` was set, so `refCount >= 0` was false at that time
and no increment occurred. `refCount` undercounts by the number of pre-bless copies.

**Impact**: DESTROY may fire while `$copy` still references the object.  
**Mitigation**: Global destruction at shutdown provides a safety net.  
**In practice**: The overwhelmingly common pattern is `bless {}, 'Class'` inside `new()`,
where there are no pre-bless copies.

---

## 7. Part 2: Weak References

### 7.1 Perl Semantics

```perl
use Scalar::Util qw(weaken isweak unweaken);

my $strong = { key => "value" };
my $weak = $strong;
weaken($weak);                    # $weak is now weak
print isweak($weak);              # 1
print $weak->{key};               # "value" — still works
undef $strong;                    # last strong ref gone
print defined $weak;              # 0 — $weak is now undef

my $copy = $weak;                 # BEFORE undef: copy is STRONG, not weak
```

### 7.2 External Registry (No Per-Scalar Field)

Weak ref tracking uses external maps to avoid memory overhead on every RuntimeScalar.

**Critical design constraint**: The `referentToWeakRefs` reverse map holds
strong references to the referent as keys. This is acceptable because entries are
always removed in `clearWeakRefsTo()` (called when refCount reaches 0 or during
global destruction). For additional safety, we also clean up stale entries in
`weaken()` if a referent's refCount is already `MIN_VALUE`.

```java
public class WeakRefRegistry {
    // Forward map: is this RuntimeScalar a weak ref?
    private static final Set<RuntimeScalar> weakScalars =
        Collections.newSetFromMap(new IdentityHashMap<>());

    // Reverse map: referent → set of weak RuntimeScalars pointing to it.
    // IMPORTANT: Entries are removed by clearWeakRefsTo() which is called
    // from BOTH the deterministic refcount path and the Cleaner path.
    // This ensures the referent is not pinned indefinitely.
    private static final IdentityHashMap<RuntimeBase, Set<RuntimeScalar>> referentToWeakRefs =
        new IdentityHashMap<>();

    public static void weaken(RuntimeScalar ref) {
        if (!RuntimeScalarType.isReference(ref.type)) return;
        if (!(ref.value instanceof RuntimeBase base)) return;
        if (weakScalars.contains(ref)) return;  // already weak

        // If referent was already destroyed, immediately undef the weak ref
        if (base.refCount == Integer.MIN_VALUE) {
            ref.type = RuntimeScalarType.UNDEF;
            ref.value = null;
            return;
        }

        weakScalars.add(ref);
        referentToWeakRefs
            .computeIfAbsent(base, k -> Collections.newSetFromMap(new IdentityHashMap<>()))
            .add(ref);

        // Weak ref doesn't count as strong reference
        if (base.refCount > 0) {
            if (--base.refCount == 0) {
                base.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(base);
            }
        }
    }

    public static boolean isweak(RuntimeScalar ref) {
        return weakScalars.contains(ref);
    }

    public static void unweaken(RuntimeScalar ref) {
        if (!weakScalars.remove(ref)) return;
        if (ref.value instanceof RuntimeBase base) {
            Set<RuntimeScalar> weakRefs = referentToWeakRefs.get(base);
            if (weakRefs != null) weakRefs.remove(ref);
            if (base.refCount >= 0) base.refCount++;  // restore strong count
            // Note: if MIN_VALUE, object already destroyed — unweaken is a no-op
        }
    }
}
```

### 7.3 Clearing Weak Refs on DESTROY

When `refCount` reaches 0, before calling DESTROY. This method also removes the
entry from `referentToWeakRefs` to avoid pinning the referent in the registry:

```java
public static void clearWeakRefsTo(RuntimeBase referent) {
    Set<RuntimeScalar> weakRefs = referentToWeakRefs.remove(referent);
    if (weakRefs == null) return;
    for (RuntimeScalar weak : weakRefs) {
        weak.type = RuntimeScalarType.UNDEF;
        weak.value = null;
        weakScalars.remove(weak);
    }
}
```

### 7.4 Copying Weak Refs

In `setLarge()`, the destination gets a **strong** copy (refCount incremented) regardless
of the source's weakness. The weakness is a property of the SOURCE RuntimeScalar's identity
(membership in `weakScalars`), not the value. A different RuntimeScalar is not in the set.

### 7.5 Weak Refs Without DESTROY (Unblessed Referents)

`weaken()` is useful for unblessed references too (breaking circular refs for GC).
For unblessed objects (`refCount == -1`, untracked), `weaken()` sets the flag in the
external registry but doesn't adjust refCount.

**Deferred to Phase 5 (optional)**. The "becomes undef when strong refs gone"
behavior for unblessed weak refs requires wrapping `ref.value` in a
`java.lang.ref.WeakReference` (`WeakReferenceWrapper`) and checking every
dereference path. This is high-risk: there are 15-20+ code paths that cast
`RuntimeScalar.value` to `RuntimeBase`, and missing any one causes ClassCastException.

**Why this can be deferred**: All bundled module uses of `weaken()` are on
**blessed** references (Test2::API::Context, Test2::Mock, Test2::Tools::Mock,
Test2::AsyncSubtest, Tie::RefHash). For blessed refs, the external registry
approach (§7.2-7.4) handles everything — when refCount reaches 0,
`clearWeakRefsTo()` sets all weak scalars to UNDEF. No `WeakReferenceWrapper`
needed.

For unblessed weak refs, `weaken()` registers the flag (so `isweak()` returns
true) and decrements refCount (which is -1 for untracked — no change). The
"becomes undef" behavior does not work for unblessed refs until
`WeakReferenceWrapper` is implemented. This is an acceptable limitation for
the initial implementation.

#### Future: WeakReferenceWrapper (Phase 5)

If unblessed weak refs are needed by a real module, implement
`WeakReferenceWrapper` with a centralized unwrap helper:

```java
// In weaken() for untracked referents (refCount == -1):
ref.value = new WeakReferenceWrapper(ref.value);
// On dereference, if WeakReference.get() returns null → set to undef
```

An alternative to per-site checks: add a `WeakReferenceWrapper.unwrap()` static
helper and call it at the top of each dereference path. If unwrap detects a
cleared reference, it updates the RuntimeScalar in-place to UNDEF and returns null.

Key dereference locations that would need checking:
1. `RuntimeScalar.hashDerefGet()` — `$weak_ref->{key}`
2. `RuntimeScalar.arrayDerefGet()` — `$weak_ref->[idx]`
3. `RuntimeScalar.scalarDeref()` — `$$weak_ref`
4. `RuntimeScalar.codeDeref()` — `$weak_ref->()`
5. `ReferenceOperators.ref()` — `ref($weak_ref)`
6. `RuntimeScalarType.blessedId()` — blessing check
7. `setLarge()` — when casting `this.value` to `RuntimeBase`
8. Plus: method dispatch, overload resolution, tied variable access, etc.

---

## 8. [Removed] GC Safety Net

**Note**: v4.0 included a Cleaner sentinel pattern (§8.1-8.4) as a GC-based fallback
for escaped references. This was removed in v5.0 because:

1. **Fundamental flaw**: The cleaning action must hold the referent alive for DESTROY,
   but this keeps the sentinel reachable, preventing the Cleaner from ever firing.
2. **Unnecessary complexity**: Perl 5 uses the same fallback strategy we now use —
   DESTROY fires at global destruction for objects that escape refcounting.
3. **Thread safety overhead**: The Cleaner runs on a daemon thread, requiring VarHandle
   CAS for refCount transitions. Without the Cleaner, all refcounting is single-threaded.
4. **Memory overhead**: Required +8 bytes per RuntimeBase for trigger/sentinel fields.

The replacement is simpler: stash walking at shutdown (see §4.8 and §10.2).

---

## 9. Part 4: DESTROY Dispatch

### 9.1 The `callDestroy()` Method

```java
public static void callDestroy(RuntimeBase referent) {
    // refCount is already MIN_VALUE (set by caller)
    String className = NameNormalizer.getBlessStr(referent.blessId);
    if (className == null) return;

    // Clear weak refs BEFORE calling DESTROY
    WeakRefRegistry.clearWeakRefsTo(referent);

    doCallDestroy(referent, className);
}
```

### 9.2 The Actual DESTROY Call

```java
private static void doCallDestroy(RuntimeBase referent, String className) {
    // Use cached method if available
    RuntimeScalar destroyMethod = destroyMethodCache.get(referent.blessId);
    if (destroyMethod == null) {
        destroyMethod = InheritanceResolver.findMethodInHierarchy(
            "DESTROY", className, null, 0);
    }

    if (destroyMethod == null || destroyMethod.type != RuntimeScalarType.CODE) {
        // No DESTROY — check AUTOLOAD
        RuntimeScalar autoloadRef = InheritanceResolver.findMethodInHierarchy(
            "AUTOLOAD", className, null, 0);
        if (autoloadRef == null) return;
        GlobalVariable.getGlobalVariable(className + "::AUTOLOAD")
            .set(new RuntimeScalar(className + "::DESTROY"));
        destroyMethod = autoloadRef;
    }

    try {
        // Perl requires: local($., $@, $!, $^E, $?)
        // Save and restore global status variables around the call
        RuntimeScalar savedDollarAt = GlobalVariable.getGlobalVariable("main::@");
        // ... save others ...

        RuntimeScalar self = new RuntimeScalar();
        // Determine the reference type based on the referent's runtime class
        if (referent instanceof RuntimeHash) {
            self.type = RuntimeScalarType.HASHREFERENCE;
        } else if (referent instanceof RuntimeArray) {
            self.type = RuntimeScalarType.ARRAYREFERENCE;
        } else if (referent instanceof RuntimeScalar) {
            self.type = RuntimeScalarType.REFERENCE;
        } else if (referent instanceof RuntimeGlob) {
            self.type = RuntimeScalarType.GLOBREFERENCE;
        } else if (referent instanceof RuntimeCode) {
            self.type = RuntimeScalarType.CODE;
        } else {
            self.type = RuntimeScalarType.REFERENCE; // fallback
        }
        self.value = referent;

        RuntimeArray args = new RuntimeArray();
        args.push(self);
        RuntimeCode.apply(destroyMethod, args, RuntimeContextType.VOID);

        // ... restore saved globals ...
    } catch (Exception e) {
        String msg = e.getMessage();
        if (msg == null) msg = e.getClass().getName();
        Warnings.warn(
            new RuntimeArray(new RuntimeScalar("(in cleanup) " + msg + "\n")),
            RuntimeContextType.VOID);
    }
}
```

---

## 10. Part 5: Global Destruction

### 10.1 `${^GLOBAL_PHASE}` Variable

```java
public static String globalPhase = "RUN";  // START → CHECK → INIT → RUN → END → DESTRUCT
```

### 10.2 Shutdown Hook

The shutdown hook walks all package stashes and global variables to find objects
with `refCount >= 0` that still need DESTROY. This covers globals, stash entries,
and values inside global arrays and hashes. No persistent tracking set is maintained
during execution (see §4.8 for rationale).

```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    GlobalVariable.getGlobalVariable(GlobalContext.GLOBAL_PHASE).set("DESTRUCT");

    // Helper to call DESTROY on a scalar if it holds a tracked blessed ref
    Consumer<RuntimeScalar> destroyIfTracked = (val) -> {
        if ((val.type & REFERENCE_BIT) != 0
                && val.value instanceof RuntimeBase base
                && base.refCount >= 0) {
            base.refCount = Integer.MIN_VALUE;
            DestroyDispatch.callDestroy(base);
        }
    };

    // Walk all package scalars
    for (Map.Entry<String, RuntimeScalar> entry : GlobalVariable.getAllGlobals()) {
        destroyIfTracked.accept(entry.getValue());
    }

    // Walk global arrays for blessed ref elements
    for (RuntimeArray arr : GlobalVariable.getAllGlobalArrays()) {
        for (RuntimeScalar elem : arr) {
            destroyIfTracked.accept(elem);
        }
    }

    // Walk global hashes for blessed ref values
    for (RuntimeHash hash : GlobalVariable.getAllGlobalHashes()) {
        for (RuntimeScalar elem : hash.values()) {
            destroyIfTracked.accept(elem);
        }
    }
}));
```

**What this catches**: All blessed-with-DESTROY objects reachable from package
variables, stash entries, global arrays, and global hashes.

**What this misses**: Overcounted objects that are no longer reachable from any
global. The JVM GC collects these without calling DESTROY. This is acceptable:
the alternative (a `trackedObjects` set) pins those objects in memory until
shutdown, which is worse for long-running programs. See §4.8 for discussion.

**Note**: `GlobalVariable.getAllGlobalArrays()` and `getAllGlobalHashes()` do not
exist yet — they need to be added as part of Phase 4 implementation.

**Known limitation**: Destruction order is unpredictable, matching Perl 5 behavior
where global destruction order is not guaranteed. DESTROY methods should check
`${^GLOBAL_PHASE}` if they need to handle shutdown specially.

---

## 11. Implementation Phases

### Phase 1: Infrastructure (2-4 hours)

**Goal**: Add `refCount` field, create `DestroyDispatch` class. No behavior change.

- Add `int refCount = -1` to `RuntimeBase` (MUST be explicitly `-1`, not default `0`)
- Create `DestroyDispatch.java` with `callDestroy()`, `doCallDestroy()`, `classHasDestroy()`
- Create `destroyClasses` BitSet and `destroyMethodCache`
- Hook `InheritanceResolver.invalidateCache()` to clear both caches

**Files**: `RuntimeBase.java`, `DestroyDispatch.java` (NEW), `InheritanceResolver.java`  
**Validation**: `make` passes. No behavior change.

### Phase 2: Scalar Refcounting + DESTROY + Mortal Mechanism (8-12 hours)

**Goal**: DESTROY works for the common case — single lexical, undef, hash delete,
local. Mortal mechanism provides correct semantics for `delete` which returns a
value while removing a reference (critical for POE `delete $heap->{wheel}`).

**Part 2a: Core refcounting**
- Hook `RuntimeScalar.setLarge()` — increment/decrement for `refCount >= 0`
- Hook `RuntimeScalar.undefine()` — decrement
- Hook `RuntimeScalar.scopeExitCleanup()` — decrement
- Hook `dynamicRestoreState()` — decrement displaced value only (do NOT increment
  restored value — see §6.2 note on `local` save/restore)
- Apply displacement-decrement to: `RuntimeScalar`, `GlobalRuntimeScalar`,
  `RuntimeHashProxyEntry`, `RuntimeArrayProxyEntry`
- Make `REFERENCE_BIT` accessible (package-private or public constant)
- Initialize `refCount = 0` in `ReferenceOperators.bless()` for DESTROY classes
- Handle re-bless (don't reset refCount; set to -1 if new class has no DESTROY)

**Part 2b: Mortal-like defer-decrement for hash delete (§6.2A)**
- Create `MortalList.java` with `active` flag, `deferDecrement()`, `deferDecrementIfTracked()`, `flush()`
- Set `MortalList.active = true` in `DestroyDispatch.classHasDestroy()` on first DESTROY class
- Hook `RuntimeHash.delete()` — call `MortalList.deferDecrementIfTracked()` on removed element
- Emit `MortalList.flush()` at statement boundaries in JVM backend (`EmitterVisitor`)
- Emit `MortalList.flush()` at statement boundaries in interpreter backend
- *(Phase 5: extend to `RuntimeArray.pop()`, `.shift()`, `Operator.splice()` when needed)*

**Part 2c: Interpreter scope-exit cleanup (§6.5)**
- Add `SCOPE_EXIT_CLEANUP` opcode to `Opcodes.java`
- Emit cleanup opcodes before `exitScope()` in `BytecodeCompiler.java`
- Handle `SCOPE_EXIT_CLEANUP` in `BytecodeInterpreter.java`
- Add disassembly text in `Disassemble.java`

**Files**: `RuntimeScalar.java`, `ReferenceOperators.java`, `RuntimeHash.java`,
`GlobalRuntimeScalar.java`,
`RuntimeHashProxyEntry.java`, `RuntimeArrayProxyEntry.java`,
`RuntimeScalarType.java` (REFERENCE_BIT visibility),
`MortalList.java` (NEW), `DestroyDispatch.java`, `EmitterVisitor.java`,
`BytecodeCompiler.java`, `BytecodeInterpreter.java`, `Opcodes.java`, `Disassemble.java`  
**Validation**: `make` passes + `destroy.t` unit test passes.

### Phase 3: weaken/isweak/unweaken (4-8 hours)

**Goal**: Weak reference functions return correct results.

- Create `WeakRefRegistry.java` with forward/reverse maps
- Implement `weaken()`, `isweak()`, `unweaken()` with refCount interaction
- Update `ScalarUtil.java` and `Builtin.java` to call `WeakRefRegistry`
- Add `clearWeakRefsTo()` call in `DestroyDispatch.callDestroy()`

**Files**: `WeakRefRegistry.java` (NEW), `ScalarUtil.java`, `Builtin.java`, `DestroyDispatch.java`  
**Validation**: `make` passes + `weaken.t` unit test passes.

### Phase 4: Global Destruction + Polish (4-8 hours)

**Goal**: Complete lifecycle support.

- Implement `${^GLOBAL_PHASE}` with DESTRUCT value
- Add JVM shutdown hook that walks global stashes for `refCount >= 0` objects
- Add `GlobalVariable.getAllGlobalArrays()` and `getAllGlobalHashes()` methods
- `Devel::GlobalDestruction` compatibility
- Protect global variables (`$@`, `$!`, `$?`, etc.) in DESTROY calls
- AUTOLOAD fallback for DESTROY

**Files**: `GlobalContext.java`, `GlobalVariable.java`, `Main.java`, `DestroyDispatch.java`  
**Validation**: Global destruction test passes.

### Phase 5: Collection Cleanup + Array Mortal + Unblessed Weak Refs (optional, 4-8 hours)

**Goal**: Deterministic DESTROY for blessed refs in lexical arrays/hashes at scope exit.
Extend MortalList to cover `pop`/`shift`/`splice`. Optionally, implement
`WeakReferenceWrapper` for unblessed weak refs if needed.

- Add `boolean containsTrackedRef` to `RuntimeArray`/`RuntimeHash`
- Set flag when a `refCount >= 0` element is stored
- Add `scopeExitCleanup(RuntimeArray)` and `scopeExitCleanup(RuntimeHash)`
- Extend `emitScopeExitNullStores()` to call cleanup on array/hash lexicals
- Hook `RuntimeArray.pop()`, `.shift()` — call `MortalList.deferDecrementIfTracked()`
- Hook `Operator.splice()` — call `MortalList.deferDecrementIfTracked()` on each removed element
- (Optional) Implement `WeakReferenceWrapper` for unblessed weak refs (see §7.5)

**Files**: `RuntimeArray.java`, `RuntimeHash.java`, `Operator.java`, `EmitStatement.java`  
**Validation**: Collection-DESTROY test passes. Pop/shift mortal tests pass. No performance regression.

---

## 12. Test Plan

### Unit Test: `src/test/resources/unit/destroy.t`

```perl
use Test::More;

subtest 'DESTROY called at scope exit' => sub {
    my @log;
    { package DestroyBasic;
      sub new { bless {}, shift }
      sub DESTROY { push @log, "destroyed" } }
    { my $obj = DestroyBasic->new; }
    is_deeply(\@log, ["destroyed"], "DESTROY called at scope exit");
};

subtest 'DESTROY with multiple references' => sub {
    my @log;
    { package DestroyMulti;
      sub new { bless {}, shift }
      sub DESTROY { push @log, "destroyed" } }
    my $a = DestroyMulti->new;
    my $b = $a;
    undef $a;
    is_deeply(\@log, [], "DESTROY not called with refs remaining");
    undef $b;
    is_deeply(\@log, ["destroyed"], "DESTROY called when last ref gone");
};

subtest 'DESTROY exception becomes warning' => sub {
    my $warned = 0;
    local $SIG{__WARN__} = sub { $warned++ if $_[0] =~ /in cleanup/ };
    { package DestroyException;
      sub new { bless {}, shift }
      sub DESTROY { die "oops" } }
    { my $obj = DestroyException->new; }
    ok($warned, "DESTROY exception became a warning");
};

subtest 'DESTROY on undef' => sub {
    my @log;
    { package DestroyUndef;
      sub new { bless {}, shift }
      sub DESTROY { push @log, "destroyed" } }
    my $obj = DestroyUndef->new;
    undef $obj;
    is_deeply(\@log, ["destroyed"], "DESTROY called on undef");
};

subtest 'DESTROY on hash delete' => sub {
    my @log;
    { package DestroyDelete;
      sub new { bless {}, shift }
      sub DESTROY { push @log, "destroyed" } }
    my %h;
    $h{obj} = DestroyDelete->new;
    delete $h{obj};
    is_deeply(\@log, ["destroyed"], "DESTROY called on hash delete");
};

subtest 'DESTROY not called twice' => sub {
    my $count = 0;
    { package DestroyOnce;
      sub new { bless {}, shift }
      sub DESTROY { $count++ } }
    { my $obj = DestroyOnce->new;
      undef $obj; }
    is($count, 1, "DESTROY called exactly once");
};

subtest 'DESTROY inheritance' => sub {
    my @log;
    { package DestroyParent;
      sub new { bless {}, shift }
      sub DESTROY { push @log, "parent" } }
    { package DestroyChild;
      our @ISA = ('DestroyParent');
      sub new { bless {}, shift } }
    { my $obj = DestroyChild->new; }
    is_deeply(\@log, ["parent"], "DESTROY inherited from parent");
};

subtest 'Return value not destroyed' => sub {
    my @log;
    { package DestroyReturn;
      sub new { bless {}, shift }
      sub DESTROY { push @log, "destroyed" } }
    sub make_obj { my $obj = DestroyReturn->new; return $obj }
    my $x = make_obj();
    is_deeply(\@log, [], "returned object not destroyed");
    undef $x;
    is_deeply(\@log, ["destroyed"], "destroyed when last ref gone");
};

subtest 'No DESTROY on blessed without DESTROY method' => sub {
    my $destroyed = 0;
    { package NoDESTROY;
      sub new { bless {}, shift } }
    { my $obj = NoDESTROY->new; }
    is($destroyed, 0, "no DESTROY called when class has none");
};

subtest 'DESTROY with local' => sub {
    my @log;
    { package DestroyLocal;
      sub new { bless {}, shift }
      sub DESTROY { push @log, "destroyed" } }
    our $global = DestroyLocal->new;
    {
        local $global = DestroyLocal->new;
        # At scope exit, local restore replaces the inner object
    }
    is_deeply(\@log, ["destroyed"], "DESTROY called for local-displaced object");
    undef $global;
    is(scalar @log, 2, "DESTROY called for outer object on undef");
};

subtest 'Re-bless to class without DESTROY' => sub {
    my @log;
    { package HasDestroy;
      sub new { bless {}, shift }
      sub DESTROY { push @log, "destroyed" } }
    { package NoDestroy2;
      sub new { bless {}, shift } }
    my $obj = HasDestroy->new;
    bless $obj, 'NoDestroy2';
    undef $obj;
    is_deeply(\@log, [], "DESTROY not called after re-bless to class without DESTROY");
};

subtest 'DESTROY creates new object' => sub {
    my @log;
    { package DestroyCreator;
      sub new { bless {}, shift }
      sub DESTROY { push @log, ref($_[0]); DestroyChild->new } }
    { package DestroyChild;
      sub new { my $o = bless {}, shift; push @log, "child_new"; $o }
      sub DESTROY { push @log, "child_destroyed" } }
    { my $obj = DestroyCreator->new; }
    ok(grep(/DestroyCreator/, @log), "parent DESTROY ran");
    # Child created in DESTROY should also be destroyed eventually
};

subtest 'DESTROY on hash delete returns value' => sub {
    my @log;
    { package DestroyDeleteReturn;
      sub new { bless { data => 42 }, shift }
      sub DESTROY { push @log, "destroyed" } }
    my %h;
    $h{obj} = DestroyDeleteReturn->new;
    my $val = delete $h{obj};
    is_deeply(\@log, [], "DESTROY not called while return value alive");
    is($val->{data}, 42, "deleted value still accessible");
    undef $val;
    is_deeply(\@log, ["destroyed"], "DESTROY called after return value dropped");
};

subtest 'DESTROY on hash delete in void context' => sub {
    my @log;
    { package DestroyDeleteVoid;
      sub new { bless {}, shift }
      sub DESTROY { push @log, "destroyed" } }
    my %h;
    $h{obj} = DestroyDeleteVoid->new;
    delete $h{obj};  # void context — no one captures the return value
    is_deeply(\@log, ["destroyed"],
        "DESTROY called at statement end for void-context delete (mortal mechanism)");
};

subtest 'DESTROY on pop returns value' => sub {
    my @log;
    { package DestroyPopReturn;
      sub new { bless { data => 99 }, shift }
      sub DESTROY { push @log, "destroyed" } }
    my @arr = (DestroyPopReturn->new);
    my $val = pop @arr;
    is_deeply(\@log, [], "DESTROY not called while pop return value alive");
    is($val->{data}, 99, "popped value still accessible");
    undef $val;
    is_deeply(\@log, ["destroyed"], "DESTROY called after pop return value dropped");
};

# Phase 5: uncomment when MortalList extended to pop/shift/splice
# subtest 'DESTROY on shift in void context' => sub {
#     my @log;
#     { package DestroyShiftVoid;
#       sub new { bless {}, shift }
#       sub DESTROY { push @log, "destroyed" } }
#     my @arr = (DestroyShiftVoid->new);
#     shift @arr;  # void context
#     is_deeply(\@log, ["destroyed"],
#         "DESTROY called at statement end for void-context shift (mortal mechanism)");
# };

done_testing();
```

### Unit Test: `src/test/resources/unit/weaken.t`

```perl
use Test::More;
use Scalar::Util qw(weaken isweak unweaken);

subtest 'isweak flag' => sub {
    my $ref = \my %hash;
    ok(!isweak($ref), "not weak initially");
    weaken($ref);
    ok(isweak($ref), "weak after weaken");
    unweaken($ref);
    ok(!isweak($ref), "not weak after unweaken");
};

subtest 'weak ref access' => sub {
    my $strong = { key => "value" };
    my $weak = $strong;
    weaken($weak);
    is($weak->{key}, "value", "can access through weak ref");
};

subtest 'copy of weak ref is strong' => sub {
    my $strong = { key => "value" };
    my $weak = $strong;
    weaken($weak);
    my $copy = $weak;
    ok(!isweak($copy), "copy is strong");
};

subtest 'weaken with DESTROY' => sub {
    my @log;
    { package WeakDestroy;
      sub new { bless {}, shift }
      sub DESTROY { push @log, "destroyed" } }
    my $strong = WeakDestroy->new;
    my $weak = $strong;
    weaken($weak);
    undef $strong;
    is_deeply(\@log, ["destroyed"], "DESTROY called when last strong ref gone");
    ok(!defined($weak), "weak ref is undef after DESTROY");
};

done_testing();
```

---

## 13. Risks and Mitigations

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| **Missed decrement point** — a code path drops a blessed ref without decrementing | DESTROY delayed to global destruction | Medium | Global destruction catches it; audit all assignment/drop paths |
| **Overcounting from temporaries** — function returns create transient RuntimeScalars that increment but don't decrement | DESTROY delayed to global destruction | Medium | Acceptable — matches Perl 5 behavior for circular refs |
| **Performance regression** — refCount checks slow the critical path | Throughput drop | Low | Fast-path bypass; `refCount >= 0` gate skips 99% of refs; benchmark before/after |
| **`MortalList.flush()` overhead** — called at every statement boundary | Throughput drop | Low | `active` flag gate: one boolean check (trivially predicted false) for programs without DESTROY; boolean + `isEmpty()` otherwise |
| **Interference with IO lifecycle** — refCount decrement triggers premature DESTROY on IO-blessed objects | IO corruption | Low | Test IO::Handle, File::Temp explicitly; separate code paths for IO vs DESTROY |
| **Existing test regressions** — refCount logic has a bug that breaks existing tests | Build failure | Medium | Phase 1 adds field only (no behavior change); Phase 2 is independently testable; run `make` after every change |
| **`local` save/restore bypasses refCount** — `dynamicSaveState`/`dynamicRestoreState` do raw field copies without adjusting refCount | Incorrect DESTROY timing or missed DESTROY with `local $blessed_ref` | Medium | Hook `dynamicRestoreState()` to decrement displaced value; do NOT increment restored value; see §6.2 notes |
| **Copy constructor bypasses refCount** — `new RuntimeScalar(scalar)` copies type/value without calling `setLarge()` | Undercounting from `RuntimeHash.delete()` (Phase 2), `pop()`/`shift()`/`splice()` (Phase 5) | Medium | Use `MortalList.deferDecrementIfTracked()` — initially for `delete()` only, extend to array methods in Phase 5 |
| **Interpreter scope-exit not hooked** — interpreter backend has no `scopeExitCleanup()` equivalent | DESTROY never fires for `my` vars in interpreter | High | Add `SCOPE_EXIT_CLEANUP` opcode — see §6.5 |

### Rollback Plan

Each phase is independently revertable:
- Phase 1: Remove `refCount` field (no behavior change to revert)
- Phase 2: Remove hooks in `setLarge()`/`undefine()`/`scopeExitCleanup()` and bless();
  remove `MortalList.java`; remove `SCOPE_EXIT_CLEANUP` opcode; revert statement-boundary flush calls
- Phase 3: Revert `ScalarUtil.java` to stubs, remove `WeakRefRegistry.java`
- Phase 4: Remove shutdown hook

If the whole approach fails, close PR #450 and document findings for future reference.

---

## 14. Known Limitations

1. **Pre-bless copies are undercounted**: References copied before `bless()` don't get
   counted. DESTROY may fire while those copies still exist. Global destruction provides
   a safety net.

2. **Temporary RuntimeScalar overcounting (mostly mitigated)**: With `refCount=0` at bless
   time, single-boundary returns (the common case) work correctly — the bless-time temporary
   is not counted. Multi-boundary returns (deeply nested helper chains) may still overcount
   by +1 per extra boundary. These objects get DESTROY at global destruction.

3. **Blessed refs in collections**: Without Phase 5, blessed refs inside lexical arrays/hashes
   that go out of scope get DESTROY at global destruction (not immediately at scope exit).

4. **Circular references without weaken()**: refCounts never reach 0. DESTROY fires at global
   destruction (shutdown hook). This matches Perl 5 behavior exactly.

5. **`Internals::SvREFCNT` remains inaccurate**: Returns 1 (constant). Real refCount is only
   tracked for blessed objects with DESTROY. Making `SvREFCNT` accurate for all objects
   would require Alternative A (full refcounting), which is rejected for performance.

---

## 15. Success Criteria

| Criterion | Phase | How to Verify |
|-----------|-------|---------------|
| `make` passes with zero regressions | All | `make` after every change |
| `destroy.t` unit tests pass | 2 | `perl dev/tools/perl_test_runner.pl src/test/resources/unit/destroy.t` |
| `weaken.t` unit tests pass | 3 | `perl dev/tools/perl_test_runner.pl src/test/resources/unit/weaken.t` |
| `isweak()` returns true after `weaken()` | 3 | Moo accessor-weaken tests |
| File::Temp DESTROY fires (temp file deleted) | 2 | Manual test with File::Temp |
| POE::Wheel DESTROY fires on `delete $heap->{wheel}` | 2 | POE wheel tests |
| No measurable performance regression | 2 | Benchmark `make test-unit` timing before/after (< 5% regression) |
| Returned objects not prematurely destroyed | 2 | "Return value not destroyed" test in `destroy.t` |
| Global destruction fires for tracked objects | 4 | `${^GLOBAL_PHASE}` test |

---

## 16. Files to Modify (Complete List)

### New Files
| File | Phase | Purpose |
|------|-------|---------|
| `DestroyDispatch.java` | 1 | Central DESTROY logic and caching |
| `MortalList.java` | 2 | Defer-decrement mechanism (mortal equivalent) |
| `WeakRefRegistry.java` | 3 | External registry for weak references |
| `src/test/resources/unit/destroy.t` | 2 | DESTROY unit tests |
| `src/test/resources/unit/weaken.t` | 3 | Weak reference unit tests |

### Modified Files
| File | Phase | Changes |
|------|-------|---------|
| `RuntimeBase.java` | 1 | Add `int refCount = -1` field |
| `InheritanceResolver.java` | 1 | Cache invalidation hook for `destroyClasses`/`destroyMethodCache` |
| `RuntimeScalarType.java` | 2 | Make `REFERENCE_BIT` package-private or add public constant |
| `RuntimeScalar.java` | 2 | Hook `setLarge()`, `undefine()`, `scopeExitCleanup()`, `dynamicRestoreState()` |
| `ReferenceOperators.java` | 2 | Initialize refCount in `bless()`, handle re-bless |
| `DestroyDispatch.java` | 1,2 | Central DESTROY logic (Phase 1); set `MortalList.active` on first DESTROY class (Phase 2) |
| `RuntimeHash.java` | 2 | Hook `delete()` — call `MortalList.deferDecrementIfTracked()` |
| `RuntimeArray.java` | 5 | Hook `pop()`, `shift()` — call `MortalList.deferDecrementIfTracked()` (deferred from Phase 2) |
| `Operator.java` | 5 | Hook `splice()` — call `MortalList.deferDecrementIfTracked()` (deferred from Phase 2) |
| `GlobalRuntimeScalar.java` | 2 | Hook `dynamicRestoreState()` — decrement displaced value |
| `RuntimeHashProxyEntry.java` | 2 | Hook `dynamicRestoreState()` — decrement displaced value |
| `RuntimeArrayProxyEntry.java` | 2 | Hook `dynamicRestoreState()` — decrement displaced value |
| `EmitterVisitor.java` | 2 | Emit `MortalList.flush()` at statement boundaries (JVM backend) |
| `Opcodes.java` | 2 | Add `SCOPE_EXIT_CLEANUP` opcode |
| `BytecodeCompiler.java` | 2 | Emit scope-exit cleanup opcodes + `MortalList.flush()` |
| `BytecodeInterpreter.java` | 2 | Handle `SCOPE_EXIT_CLEANUP` opcode + `MortalList.flush()` |
| `Disassemble.java` | 2 | Add disassembly text for `SCOPE_EXIT_CLEANUP` |
| `ScalarUtil.java` | 3 | Replace `weaken`/`isweak`/`unweaken` stubs |
| `Builtin.java` | 3 | Update `builtin::weaken`, `builtin::is_weak`, `builtin::unweaken` |
| `GlobalContext.java` | 4 | `${^GLOBAL_PHASE}` support |
| `GlobalVariable.java` | 4 | `getAllGlobalArrays()`, `getAllGlobalHashes()` for stash walking |
| `Main.java` | 4 | Global destruction shutdown hook |
| `EmitStatement.java` | 5 | Optional: emit cleanup calls for array/hash lexicals |

---

## 17. Edge Cases

### Object Resurrection
If DESTROY stores `$_[0]` somewhere, the object survives:
```perl
package Immortal;
our @saved;
sub DESTROY { push @saved, $_[0] }
```
After DESTROY, `refCount == Integer.MIN_VALUE`. The object won't be DESTROY'd again.
This matches Perl 5 behavior (DESTROY is called once per object).

### Circular References
Two objects pointing to each other: refCounts never reach 0.
- Without `weaken()`: DESTROY fires at global destruction (shutdown hook) — same as Perl 5
- With `weaken()`: the weak link doesn't count, so the cycle breaks correctly

### Re-bless to Different Class
```perl
bless $obj, 'Foo';  # Foo has DESTROY — refCount = 0 (at bless time)
bless $obj, 'Bar';  # Bar has no DESTROY
```
On re-bless: if new class has no DESTROY, set `refCount = -1` (stop tracking).
If new class has DESTROY, keep refCount.

### Tied Variables
Tied variables already have DESTROY via `tieCallIfExists("DESTROY")`.
The refCount-based DESTROY only fires for `refCount >= 0` objects. Tied variable types
don't get `refCount = 0` at bless time (they use separate tied DESTROY path).

### DESTROY During Global Destruction
Destruction order is unpredictable. DESTROY methods should check `${^GLOBAL_PHASE}`:
```perl
sub DESTROY {
    return if ${^GLOBAL_PHASE} eq 'DESTRUCT';
    # ... normal cleanup ...
}
```

---

## 18. Open Questions

1. **Thread safety for refCount?**
   - Without the Cleaner, all refCount operations happen on the main Perl execution thread.
   - Perl code is single-threaded (PerlOnJava doesn't support Perl threads).
   - **No thread safety mechanism needed.** Plain `--refCount` and `++refCount` are sufficient.
   - If Java threading via inline Java is used in the future, refCount operations would need
     synchronization, but that's a separate concern.

2. **Should we track refCount for ALL blessed objects or only DESTROY classes?**
   - Tracking all blessed: simpler, but overhead for classes without DESTROY.
   - Tracking only DESTROY classes: faster, but needs cache invalidation on method changes.
   - **Recommendation**: Only DESTROY classes (using `destroyClasses` BitSet).

3. **Should Phase 5 (collection cleanup) be implemented?**
   - Without it, blessed refs in collections get DESTROY at global destruction.
   - The `containsTrackedRef` flag makes it cheap for the common case.
   - **Recommendation**: Defer to Phase 5. Implement only if real-world modules need it.

---

## 19. References

- Perl `perlobj` DESTROY documentation: https://perldoc.perl.org/perlobj#Destructors
- PR #450 (WIP): https://github.com/fglock/PerlOnJava/pull/450
- `dev/modules/poe.md` — DestroyManager attempt and lessons
- `dev/design/object_lifecycle.md` — earlier design proposal

---

## Progress Tracking

### Current Status: Debugging scope-exit flush regressions (v5.5)

### Completed Phases
- [x] Phase 1: Infrastructure (2026-04-08)
  - Created `DestroyDispatch.java`, added `refCount` field to `RuntimeBase`
  - Hooked `InheritanceResolver.invalidateCache()` for DESTROY cache
- [x] Phase 2a: Core refcounting (2026-04-08)
  - Hooked `setLarge()`, `undefine()`, `scopeExitCleanup()`, `dynamicRestoreState()`
- [x] Phase 2b: MortalList initial implementation (2026-04-08)
  - Created `MortalList.java` with active gate, defer/flush mechanism
  - Hooked `RuntimeHash.delete()` for deferred decrements
- [x] Phase 2c: Interpreter scope-exit cleanup (2026-04-08)
  - Added `SCOPE_EXIT_CLEANUP` opcode (462) and `MORTAL_FLUSH` opcode
- [x] Phase 3: weaken/isweak/unweaken (2026-04-08)
  - Created `WeakRefRegistry.java`, updated `ScalarUtil.java` and `Builtin.java`
- [x] Phase 4: Global Destruction (2026-04-08)
  - Created `GlobalDestruction.java`, hooked shutdown in `PerlLanguageProvider` and `WarnDie`
- [x] Phase 5 (partial): Container operations (2026-04-08)
  - Hooked `RuntimeArray.pop()`, `RuntimeArray.shift()`, `Operator.splice()`
    with `MortalList.deferDecrementIfTracked()` for removed elements
- [x] Tests: Created `destroy.t` and `weaken.t` unit tests
- [x] Scope-exit flush: Added `MortalList.flush()` after `emitScopeExitNullStores`
  for non-subroutine blocks (JVM: `EmitBlock`, `EmitForeach`, `EmitStatement`;
  Interpreter: `BytecodeCompiler.exitScope(boolean flush)`)

### Last Commit
- `790c6842f`: "fix: weaken/refCount improvements — 178/196 sandbox tests passing"
- Branch: `feature/destroy-weaken`

### Uncommitted Changes (scope-exit flush + container ops)
Files modified since last commit:
- `EmitBlock.java`: scope-exit flush for bare blocks
- `EmitForeach.java`: scope-exit flush for foreach loops
- `EmitStatement.java`: scope-exit flush for if/while/for blocks; added `emitScopeExitNullStores(ctx, scopeIndex, boolean flush)` overload
- `BytecodeCompiler.java`: `exitScope(boolean flush)` emits `MORTAL_FLUSH` opcode
- `RuntimeArray.java`: `pop()` and `shift()` call `MortalList.deferDecrementIfTracked()`
- `Operator.java`: `splice()` calls `MortalList.deferDecrementIfTracked()` for removed elements

### Sandbox Test Results

| Test file | Before flush (commit 790c684) | After flush (uncommitted) | Delta |
|-----------|:---:|:---:|:---:|
| destroy_basic.t | 17/18 | **18/18** | +1 (scope-exit DESTROY now fires) |
| destroy_collections.t | 18/22 | 17/20* | -1 pass, -2 total (crash) |
| destroy_edge_cases.t | 17/22 | 11/12* | -6 pass, -10 total (crash after test 12) |
| destroy_inheritance.t | 8/10 | 5/6* | -3 pass, -4 total (crash after test 6) |
| destroy_return.t | 23/24 | 16/17* | -7 pass, -7 total (crash after test 17) |
| weaken_basic.t | 33/34 | **34/34** | +1 (scope-exit flush fixes weaken timing) |
| weaken_destroy.t | 20/24 | **23/24** | +3 (flush improves weak ref destruction) |
| weaken_edge_cases.t | 42/42 | 42/42 | unchanged |
| **Totals** | **178/196** | **166/173** | -12 pass, -23 total |

\* Crash = Test2 "CONTEXT_STACK" error causes premature file exit, skipping remaining tests.

**Net effect**: The scope-exit flush fixes 5 tests but causes 4 test files to crash
(losing 23 tests from the count), resulting in a net -12 passing.

### Bugs Found During Validation

#### Bug 1: DESTROY exception warning (FIXED in commit 790c684)
`DestroyDispatch.callDestroy()` used `Warnings.warn()` which bypasses `$SIG{__WARN__}`.
Fixed: use `WarnDie.warn()`.

#### Bug 2: Return value overcounting (FIXED in commit 790c684)
`return $obj` jumps to `returnLabel`, bypassing `emitScopeExitNullStores`. The
abandoned `$obj` slot never gets its refCount decremented, causing a permanent +1
overcounting. Fix: add `allMyScalarSlots` list to `JavaClassInfo`, emit cleanup at
`returnLabel`.

#### Bug 3: Hash delete premature DESTROY (FIXED in commit 790c684)
With per-statement `MortalList.flush()` removed (to fix `code_too_large.t` OOM),
immediate decrement in hash delete fires DESTROY before the caller captures the return
value. Fix: revert to `MortalList.deferDecrementIfTracked()`, flush from runtime methods.

#### Bug 4: Per-statement bytecode bloat (FIXED in commit 790c684)
Emitting `INVOKESTATIC MortalList.flush()` at every statement boundary pushes bytecode
over JVM heap limits for large test files. Fix: move flush to runtime methods
(`RuntimeCode.apply()`, `RuntimeScalar.setLarge()`).

#### Bug 5: Re-bless refCount initialization (OPEN)
**Test**: destroy_edge_cases.t test 12 — "re-bless to class with DESTROY: DESTROY fires"

**Problem**: When re-blessing from an untracked class (refCount=-1) to a class with
DESTROY, `bless()` sets `refCount = 0`. But the scalar being blessed already holds a
reference to the object, and this reference was never counted (because tracking wasn't
active when the assignment happened).

```perl
my $obj = DE_NoDestroy->new;     # bless without DESTROY → refCount = -1
                                  # setLarge: refCount < 0, no increment
bless $obj, 'DE_HasDestroy';     # re-bless with DESTROY → refCount = 0 (WRONG)
# $obj holds a reference but refCount is 0
# Scope exit: deferDecrementIfTracked checks refCount > 0 → false → no DESTROY
```

**Fix**: Set `refCount = 1` instead of `0` when re-blessing from untracked to DESTROY.
The scalar being blessed already holds a reference, so counting it as 1 is correct.
This parallels how first-bless uses refCount=0 (the bless-time temp is NOT counted),
but for re-bless the scalar IS a named variable, not a temp.

**Caveat**: If there are pre-existing copies made before re-bless, refCount will
undercount. This is the same limitation as §6.6 (Pre-bless Copies) — acceptable
because the common pattern is a single reference being re-blessed.

#### Bug 6: MortalList.flush() at scope exit causes Test2 crashes (OPEN — CRITICAL)
**Symptom**: After a test failure, Test2's `diag()` function creates a context object
(Test2::API::Context), which is blessed and has DESTROY. When Test2's internal scopes
exit, `MortalList.flush()` processes ALL pending entries (not just those from the
current scope), potentially destroying Test2 context objects at the wrong time.

**Error**: "A context appears to have been destroyed without first calling release().
... Cleaning up the CONTEXT_STACK..."

**Root cause**: `MortalList.flush()` is global — it processes ALL pending entries from
ALL scopes. In Perl 5, `FREETMPS` only frees temporaries up to the save stack mark
(created by `SAVETMPS`). Our flush is equivalent to `FREETMPS` without `SAVETMPS`
scoping — it drains everything.

**Scenario**:
1. Test function (`is_deeply`) fails → calls `diag()`
2. `diag()` calls `context()` → creates Test2::API::Context, blessed with DESTROY
3. `diag()` calls `$ctx->release()` → marks context as released
4. `diag()` returns → $ctx goes out of scope → `deferDecrementIfTracked($ctx)` → pending
5. Back in `_ok_debug()` → another internal scope exit → `flush()` fires
6. `flush()` processes $ctx AND possibly other pending objects from earlier scopes
7. A different context object (not yet released) gets DESTROY → crash

**Possible fixes**:
- **Option A: Scoped pending list** — partition pending entries by scope depth, only
  flush entries from the current scope. Matches Perl 5's SAVETMPS/FREETMPS scoping.
  Most correct but adds complexity.
- **Option B: Remove scope-exit flush** — revert to flush only at `apply()` and
  `setLarge()`. Loses scope-exit DESTROY timing but avoids the crash. The 5 tests
  fixed by scope-exit flush would regress.
- **Option C: Selective flush** — only flush at scope exits when the scope contains
  tracked blessed variables. Skip flush when pending list only has entries from outer
  scopes.

#### Bug 7: AUTOLOAD-based DESTROY dispatch (OPEN)
**Test**: destroy_inheritance.t test 6 — "AUTOLOAD catches DESTROY when no explicit
DESTROY defined"

**Status**: Not investigated yet. `DestroyDispatch.callDestroy()` has AUTOLOAD fallback
code, but it may not be working correctly.

#### Bug 8: Discarded return value not destroyed (OPEN)
**Test**: destroy_return.t test 17 — "discarded return value is destroyed"

**Problem**: When a function returns a blessed object and the caller discards the return
value (void context), DESTROY should fire but doesn't. The object was created inside
`new()` with `bless {}` → refCount=0, stored in no named variable, and returned directly.
refCount stays at 0 forever because no `setLarge()` or `scopeExitCleanup()` processes it.

In Perl 5, the return value becomes a mortal (SAVETMPS/FREETMPS), so its refcount is
decremented at the next statement boundary. PerlOnJava has no equivalent for function
return values.

**Possible fix**: In the return epilogue, call `MortalList.deferDecrementIfTracked()` on
the return value (not just on cleaned-up local variables). This would schedule a decrement
for tracked return values. If the caller captures it (via `setLarge()`), the increment
happens first; if discarded, the deferred decrement fires DESTROY at the next flush.
However, this requires bumping refCount from 0 to 1 first (a temporary "mortal" increment).

#### Bug 9: Circular refs with weaken (OPEN)
**Test**: weaken_destroy.t test 9 — "B destroyed (circular ref broken by weaken)"

**Status**: Not investigated yet. Likely related to weak ref handling in circular
reference scenarios.

### Key Design Change (v5.4): Deferred Scope-Exit Decrements

`scopeExitCleanup()` now uses `MortalList.deferDecrement()` instead of immediate
decrement. This prevents premature DESTROY when a return value aliases a variable
being cleaned up. The deferred decrement is flushed by the caller's next `setLarge()`
or `RuntimeCode.apply()` call. This also fixes the returnLabel overcounting problem
because the cleanup at returnLabel safely defers the decrement.

### Key Design Change (v5.5): Scope-Exit Flush

Added `MortalList.flush()` after scope cleanup for non-subroutine blocks. This ensures
deferred decrements from `scopeExitCleanup()` are processed at scope boundaries, not
just at the next `setLarge()` or `apply()` call.

**JVM backend**: `emitScopeExitNullStores(ctx, scopeIndex, boolean flush)` overload.
Subroutine bodies pass `flush=false` (return value protection); bare blocks, if/while/for,
foreach pass `flush=true`.

**Interpreter**: `exitScope(boolean flush)` emits `MORTAL_FLUSH` opcode when flush=true.

**Problem**: The flush is global (processes all pending entries), causing premature
DESTROY of objects from outer scopes. See Bug 6.

### Next Steps
1. **Fix Bug 5** (re-bless refCount): change `refCount = 0` to `refCount = 1` in
   `ReferenceOperators.bless()` for the untracked-to-DESTROY re-bless case
2. **Fix Bug 6** (scope-exit flush crash): implement scoped pending list (Option A)
   or revert scope-exit flush (Option B) — decision needed
3. **Investigate Bug 7** (AUTOLOAD DESTROY dispatch)
4. **Investigate Bug 8** (discarded return value) — may need mortal-increment for return values
5. **Investigate Bug 9** (circular refs with weaken)
6. Commit fixes, run `make`, push to branch

### Open Questions
- **Scope-exit flush strategy**: Should we implement scoped pending (Perl 5-like
  SAVETMPS/FREETMPS), or is the simpler approach of only flushing at `apply()`
  and `setLarge()` sufficient for real-world modules?
- Should `MortalList.flush()` also be called from `RuntimeArray.push()` or
  `RuntimeHash.put()`?
- Should the interpreter's `MORTAL_FLUSH` opcode be removed if flush becomes
  purely runtime-driven?

---

## 12. WEAKLY_TRACKED Scope-Exit Analysis (v5.6)

### 12.1 Problem Statement

WEAKLY_TRACKED (`refCount = -2`) objects have a fundamental gap: their weak refs are
never cleared when the last strong reference goes out of scope. This breaks the Perl 5
expectation that `weaken()` + scope exit should clear the weak ref.

**Failing tests** (Moo accessor-weaken*.t — 6 subtests):

| Test | Scenario | Expected |
|------|----------|----------|
| accessor-weaken.t #10 | `has two => (lazy=>1, weak_ref=>1, default=>sub{{}})` | Lazy default creates temp `{}`, weakened; no other strong ref → undef |
| accessor-weaken.t #11 | Same as #10, checking internal hash slot | `$foo2->{two}` should be undef |
| accessor-weaken.t #19 | Redefining sub frees optree constants | Weak ref to `\ 'yay'` cleared after `*mk_ref = sub {}` |
| accessor-weaken-pre-5_8_3.t #10,#11 | Same as above (pre-5.8.3 variant) | Same |
| accessor-weaken-pre-5_8_3.t #19 | Same optree reaping test | Same |

**Root cause trace** (tests 10/11):
```
1. Default sub creates {} → RuntimeHash, blessId=0, refCount=-1
2. $self->{two} = $value → setLarge: refCount=-1 (NOT_TRACKED) → no increment
3. weaken($self->{two}) → refCount: -1 → WEAKLY_TRACKED (-2)
4. Accessor returns, $value goes out of scope
   → scopeExitCleanup → deferDecrementIfTracked
   → base.refCount=-2, NOT > 0 → SKIPPED!
5. Weak ref never cleared → test expects undef, gets the hash
```

**Why WEAKLY_TRACKED exists (Phase 39 analysis):**

The WEAKLY_TRACKED sentinel was introduced to protect the Moo constructor pattern:
```perl
weaken($self->{constructor} = $constructor);
```
Here `$constructor` is a code ref also installed in the symbol table (`*ClassName::new`).
If scope-exit decremented the WEAKLY_TRACKED code ref's refCount, it would be
incorrectly cleared when `$constructor` (the local variable) goes out of scope,
even though the symbol table still holds a strong reference.

### 12.2 Key Insight: Type-Aware Tracking

The Phase 39 problem only affects `RuntimeCode` and `RuntimeGlob` objects, which can
be stored in the symbol table (stash). These stash entries are created via glob assignment
(`*Foo::bar = $code_ref`), which does NOT go through `RuntimeScalar.setLarge()` and
therefore never increments `refCount`. This means any tracking we start at `weaken()`
time would undercount for these types.

Anonymous data structures (`RuntimeHash`, `RuntimeArray`, `RuntimeScalar` referents)
can **never** be in the stash. For these types, `refCount = 1` at weaken() time is
a safe estimate (one strong ref = the originating variable), and future copies via
`setLarge()` will correctly increment/decrement.

### 12.3 Attempted Fix: Type-Aware weaken() Transition

**Approach**: Set `refCount = 1` for data structures (RuntimeHash/RuntimeArray/RuntimeScalar)
when weaken() transitions from NOT_TRACKED, while keeping WEAKLY_TRACKED for RuntimeCode
and RuntimeGlob (which may have untracked stash references).

**Result**: **FAILED** — Caused infinite recursion (StackOverflowError) in Moo/Sub::Defer.

**Root cause**: Starting refCount at 1 is an underestimate for objects with multiple
pre-existing strong refs. During routine setLarge() operations (variable assignment,
overwrite), the refCount would prematurely reach 0, triggering `callDestroy()` →
`clearWeakRefsTo()` which sets weak refs to undef mid-operation. In Sub::Defer, this
cleared a deferred sub entry, causing the next access to re-trigger undeferring →
infinite apply() → apply() → ... recursion.

**Key lesson**: Any approach that starts refCount tracking mid-flight (after refs are
already created without tracking) will undercount. The only correct approaches are:
1. Track refCount from object creation for ALL objects (expensive, Perl 5 approach)
2. Use JVM WeakReference for Perl-level weak refs (allows JVM GC to detect unreachability)
3. Accept the WEAKLY_TRACKED limitation (current approach)

**Current state**: WEAKLY_TRACKED remains for all non-DESTROY objects. The 6 accessor-weaken
subtests remain failing. The POSIX::_do_exit fix was successful (demolish-global_destruction.t
now passes).

### 12.4 Moo Test Results After This Session

| Metric | Before | After | Change |
|--------|--------|-------|--------|
| Test programs | 68/71 (95.8%) | 69/71 (97.2%) | +1 (demolish-global_destruction.t) |
| Subtests | 834/841 (99.2%) | 835/841 (99.3%) | +1 |

### 12.5 Remaining Failures (Deferred)

**Tests 10/11** (lazy + weak_ref default): Requires either full refcounting from
object creation or JVM WeakReference for Perl weak refs. Both are significant refactors.

**Test 19** (optree reaping): Requires tracking references through compiled code objects.
This is specific to Perl 5's memory model and not achievable on the JVM.

### 12.6 Other Fixes in This Session

**POSIX::_do_exit (demolish-global_destruction.t):**
- `POSIX::_exit()` calls `POSIX::_do_exit()` which was undefined
- Added `_do_exit` method to `POSIX.java` using `Runtime.getRuntime().halt(exitCode)`
- Uses `halt()` instead of `System.exit()` to bypass shutdown hooks (matches POSIX _exit(2) semantics)
- The demolish-global_destruction.t test also requires subprocess execution (`system $^X, ...`)
  and global destruction running DEMOLISH — these are already implemented

### 12.7 Files Changed

| File | Change |
|------|--------|
| `WeakRefRegistry.java` | Added analysis notes for WEAKLY_TRACKED limitation; attempted type-aware transition (reverted) |
| `POSIX.java` | Added `_do_exit` method registration and implementation |

### 12.8 Future Work: JVM WeakReference Approach

The correct long-term fix for WEAKLY_TRACKED objects requires replacing the strong Java
reference in Perl weak ref scalars with a `java.lang.ref.WeakReference<RuntimeBase>`.
This would allow the JVM GC to naturally detect when no strong Perl refs remain.

**Design sketch:**
1. In `weaken()`: replace `ref.value` with a wrapper containing a JVM WeakReference
2. In all dereference paths: check if the WeakReference is still alive
3. If collected: set the Perl ref to undef (matching Perl 5 behavior)

**Challenges:**
- `ref.value` is accessed with `instanceof` checks throughout the codebase
- Need a transparent wrapper or accessor method at ~15+ dereference points
- Performance impact of WeakReference allocation and GC interaction

### Version History
- **v5.6** (2026-04-08): WEAKLY_TRACKED scope-exit analysis + POSIX::_do_exit:
  1. Analyzed why WEAKLY_TRACKED objects' weak refs are never cleared on scope exit.
     Root cause: `deferDecrementIfTracked()` only handles `refCount > 0`; WEAKLY_TRACKED (-2)
     is skipped. Added §12 documenting the full analysis.
  2. Designed type-aware weaken() transition: `RuntimeHash`/`RuntimeArray`/`RuntimeScalar`
     referents get `refCount = 1` (start active tracking), while `RuntimeCode`/`RuntimeGlob`
     keep WEAKLY_TRACKED (-2) to protect symbol-table-stored values (Phase 39 pattern).
  3. Added `POSIX::_do_exit` implementation using `Runtime.getRuntime().halt()` for
     demolish-global_destruction.t support.
- **v5.5** (2026-04-08): Scope-exit flush + container ops + regression analysis:
  1. Added `MortalList.flush()` at non-subroutine scope exits (bare blocks, if/while/for,
     foreach). JVM backend: `emitScopeExitNullStores(..., boolean flush)` overload.
     Interpreter: `exitScope(boolean flush)` emits `MORTAL_FLUSH` opcode.
  2. Hooked `RuntimeArray.pop()`, `RuntimeArray.shift()`, `Operator.splice()` with
     `MortalList.deferDecrementIfTracked()` for removed tracked elements.
  3. Discovered Bug 5 (re-bless refCount=0 should be 1), Bug 6 (global flush causes
     Test2 context crashes), Bug 7 (AUTOLOAD DESTROY dispatch), Bug 8 (discarded return
     value), Bug 9 (circular refs with weaken). See Progress Tracking for details.
  4. Sandbox results: 166/173 (from 178/196). Flush fixes 5 tests but causes 4 test
     files to crash (Test2 context stack errors on test failure paths).
- **v5.4** (2026-04-08): Fix mortal mechanism based on implementation testing:
  1. Removed per-statement `MortalList.flush()` bytecode emission (caused OOM in
     `code_too_large.t`). Moved flush to runtime methods: `RuntimeCode.apply()` and
     `RuntimeScalar.setLarge()`.
  2. Changed `scopeExitCleanup()` from immediate decrement to deferred via MortalList.
     Prevents premature DESTROY when return value aliases the variable being cleaned up.
  3. Added `allMyScalarSlots` tracking to `JavaClassInfo` and returnLabel cleanup.
     Fixes overcounting for explicit `return` (which bypasses `emitScopeExitNullStores`).
  4. Fixed DESTROY exception handling: use `WarnDie.warn()` instead of `Warnings.warn()`
     so exceptions route through `$SIG{__WARN__}`.
  5. Revised §4A.3 table: `make_obj()` pattern now deterministic with v5.4.
- **v5.3** (2026-04-08): Simplify MortalList based on blocked-module survey:
  1. Scoped initial MortalList to `RuntimeHash.delete()` only. A survey of all
     blocked modules (POE, DBIx::Class, Moo, Template Toolkit, Log4perl,
     Data::Printer, Test::Deep, etc.) found no real-world pattern needing
     deterministic DESTROY from pop/shift/splice of blessed objects. The POE
     pattern that motivates mortal is specifically `delete $heap->{wheel}`.
  2. Added `MortalList.active` boolean gate — false until first `bless()` into
     a class with DESTROY. When false, `flush()` is a single branch (trivially
     predicted). Zero effective cost for programs without DESTROY.
  3. Moved `RuntimeArray.pop/shift` and `Operator.splice` mortal hooks to Phase 5.
  4. Updated Phase 2b, Phase 5, test plan, risks, and file list accordingly.
- **v5.2** (2026-04-08): Review corrections based on codebase analysis:
  1. Fixed `dynamicRestoreState()` — do NOT increment restored value (was causing
     permanent +1 overcounting, preventing DESTROY for `local`-ized globals).
  2. Corrected `pop()`/`shift()` — they return raw elements (not copies). Immediate
     decrement would cause premature DESTROY before caller captures return value.
  3. Added **MortalList** defer-decrement mechanism (§6.2A) — equivalent to Perl 5's
     FREETMPS. Critical for POE::Wheel `delete $heap->{wheel}` pattern. Deferred
     decrements fire at statement end, giving caller time to store return values.
  4. Added **interpreter scope-exit cleanup** (§6.5) — the interpreter backend had no
     `scopeExitCleanup()` equivalent. Without this, DESTROY would never fire for `my`
     variables in the interpreter. Added `SCOPE_EXIT_CLEANUP` opcode.
  5. Added notes on `GlobalRuntimeScalar` and proxy class `dynamicRestoreState()` —
     21+ implementations of `DynamicState` need consistent displacement-decrement.
  6. Fixed splice reference — it's in `Operator.java`, not `RuntimeArray.java`.
  7. Deferred `WeakReferenceWrapper` for unblessed weak refs to Phase 5 — all bundled
     module uses of `weaken()` are on blessed refs which work without the wrapper.
  8. Expanded Phase 2 into three parts (2a/2b/2c) and updated file list accordingly.
- **v5.1** (2026-04-08): Replaced `trackedObjects` set with stash-walking at shutdown.
  The set pinned every tracked object in memory (preventing JVM GC from collecting
  overcounted/circular objects), reintroducing Perl 5's memory leak behavior. Stash
  walking at shutdown avoids this: overcounted unreachable objects are GC'd (no DESTROY,
  but no memory leak either). The `trackedObjects` set is documented as an alternative
  in §4.8 if testing shows too many missed DESTROY calls.
- **v5.0** (2026-04-08): Removed Cleaner/sentinel mechanism entirely. Replaced with
  refcounting + global destruction at shutdown, matching Perl 5 semantics. Eliminated
  `destroyTrigger`/`destroySentinel` fields from RuntimeBase (saving +8 bytes/object).
  Removed Phase 4 (Cleaner), removed threading concerns, added `trackedObjects` set
  for efficient global destruction. Renumbered phases: old Phase 5→4, old Phase 6→5.
- **v4.0** (2026-04-08): Review fixes — Cleaner sentinel reachability, WeakRefRegistry
  pinning, missing refcount hooks, VarHandle CAS, type reconstruction in DESTROY dispatch.
- **v3.0**: Revised `refCount=0` at bless time to fix overcounting.
- **v2.0**: Initial targeted refcounting + Cleaner design.
- **v1.0**: Initial design proposal.

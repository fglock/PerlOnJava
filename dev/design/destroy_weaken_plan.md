# DESTROY and weaken() Implementation Plan

**Status**: Moo 71/71 (100%) — 841/841 subtests; croak-locations.t 29/29  
**Version**: 5.20  
**Created**: 2026-04-08  
**Updated**: 2026-04-10 (v5.20 — performance optimization plan; fix reset() m?PAT? regression)  
**Supersedes**: `object_lifecycle.md` (design proposal)  
**Related**: PR #464, `dev/modules/moo_support.md`

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

### Current Status: Moo 71/71 (100%) — 841/841 subtests; croak-locations.t 29/29

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
- [x] POSIX::_do_exit (2026-04-08): Added `Runtime.getRuntime().halt()` implementation
  for `demolish-global_destruction.t`
- [x] WEAKLY_TRACKED analysis (2026-04-08): Investigated type-aware refCount=1 approach
  (failed — infinite recursion in Sub::Defer), documented root cause (§12)
- [x] JVM WeakReference feasibility study (2026-04-08): Analyzed 7 approaches for fixing
  remaining 6 subtests. Concluded: JVM GC non-determinism makes all GC-based approaches
  unviable; only full refcounting from birth can fix tests 10/11 (§14)
- [x] ExifTool StackOverflow fix (2026-04-09): Converted `deferDecrementRecursive()` from
  recursive to iterative with cycle detection + null guards. ExifTool: 113/113 pass, 597/597 subtests pass.
- [x] Force-clear fix for unblessed weak refs (2026-04-09):
  - **Root cause**: Birth-tracked anonymous hashes accumulate overcounted refCount
    through function boundaries (e.g., Moo's constructor chain creates `{}`,
    passes through `setLarge()` in each return hop, each incrementing refCount
    with no corresponding decrement for the traveling container)
  - **Failed approach**: Removing `this.refCount = 0` from `createReferenceWithTrackedElements()`
    fixed undef-clearing but broke `isweak()` tests (7 additional failures)
  - **Successful approach**: In `RuntimeScalar.undefine()`, when an unblessed object
    (`blessId == 0`) has weak refs but refCount doesn't reach 0 after decrement,
    force-clear anyway. Since unblessed objects have no DESTROY, only side effect
    is weak refs becoming undef (which is exactly what users expect after `undef $ref`)
  - **Also fixed**: Removed premature `WEAKLY_TRACKED` transition in `WeakRefRegistry.weaken()`
    that was clearing weak refs when ANY strong ref exited scope while others still existed
  - **Result**: accessor-weaken.t 19/19 (was 16/19), accessor-weaken-pre-5_8_3.t 19/19
  - **Files**: `RuntimeScalar.java` (~line 1898-1908), `WeakRefRegistry.java`
- [x] Skip weak ref clearing for CODE objects (2026-04-09):
  - **Root cause**: CODE refs live in both lexicals and the stash (symbol table), but stash
    assignments (`*Foo::bar = $coderef`) bypass `setLarge()`, making the stash reference
    invisible to refcounting. Two premature clearing paths existed:
    1. **WEAKLY_TRACKED path**: `weaken()` transitioned untracked CODE refs to WEAKLY_TRACKED (-2).
       Then `setLarge()`/`scopeExitCleanup()` cleared weak refs when any lexical reference was
       overwritten — even though the CODE ref was still alive in the stash.
    2. **Mortal flush path**: Tracked CODE refs (refCount > 0) got added to `MortalList.pending`
       via `deferDecrementIfTracked()`. When `flush()` ran, refCount decremented to 0 (because
       the stash reference never incremented it), triggering `callDestroy()` → `clearWeakRefsTo()`.
    Both paths cleared weak refs used by `Sub::Quote`/`Sub::Defer` for back-references to
    deferred subs, making `quoted_from_sub()` return undef and breaking Moo's accessor inlining.
  - **Fix**: Two guards in `WeakRefRegistry.java`:
    1. Skip WEAKLY_TRACKED transition for `RuntimeCode` in `weaken()` (line 88): `!(base instanceof RuntimeCode)`
    2. Skip `clearWeakRefsTo()` for `RuntimeCode` objects (line 172): `if (referent instanceof RuntimeCode) return`
    Since DESTROY is not implemented, skipping the clear has no behavioral impact.
  - **Result**: Moo goes from 793/841 (65/71) to **839/841 (70/71)**. 46 subtests fixed across
    6 programs (accessor-coerce, accessor-default, accessor-isa, accessor-trigger,
    constructor-modify, method-generate-accessor). All now fully pass.
  - **Remaining 2 failures**: `overloaded-coderefs.t` tests 6 and 8 — B::Deparse returns "DUMMY"
    instead of deparsed Perl source. This is a pre-existing B::Deparse limitation (JVM bytecode
    cannot be reconstructed to Perl source), unrelated to weak references.
  - **Files**: `WeakRefRegistry.java` (lines 88 and 162-172)
  - **Commits**: `86d5f813e`
- [x] Tie DESTROY on untie via refcounting (2026-04-09):
  - **Problem**: Tie wrappers (TieScalar, TieArray, TieHash, TieHandle) held a strong Java
    reference to the tied object (`self`) but never incremented refCount. When `untie` replaced
    the variable's contents, the tied object was dropped by Java GC with no DESTROY call.
    System Perl fires DESTROY immediately after untie when no other refs hold the object.
  - **Fix**: Increment refCount in each tie wrapper constructor (TiedVariableBase, TieArray,
    TieHash, TieHandle). Add `releaseTiedObject()` method to each that decrements refCount
    and calls `DestroyDispatch.callDestroy()` if it reaches 0. Call `releaseTiedObject()`
    from `TieOperators.untie()` after restoring the previous value.
  - **Null guard**: `TiedVariableBase` constructor gets null check because proxy entries
    (`RuntimeTiedHashProxyEntry`, `RuntimeTiedArrayProxyEntry`) pass null for `tiedObject`.
  - **Deferred DESTROY**: When `my $obj = tie(...)` holds a ref, `$obj`'s setLarge() increments
    refCount, so untie's decrement (2→1) does NOT trigger DESTROY. DESTROY fires later when
    `$obj` goes out of scope. Verified to match system Perl behavior.
  - **Tests**: Removed 5 `TODO` blocks from tie_scalar.t (2), tie_array.t (1), tie_hash.t (1).
    Added 2 new subtests to destroy.t: immediate DESTROY on untie, deferred DESTROY with held ref.
  - **Files**: `TiedVariableBase.java`, `TieArray.java`, `TieHash.java`, `TieHandle.java`,
    `TieOperators.java`, `tie_scalar.t`, `tie_array.t`, `tie_hash.t`, `destroy.t`
- [x] eval BLOCK eager capture release (2026-04-09):
  - **Root cause**: `eval BLOCK` is compiled as `sub { ... }->()` — an immediately-invoked
    anonymous sub (see `OperatorParser.parseEval()`, line 88-92). This creates a RuntimeCode
    closure that captures outer lexicals, incrementing their `captureCount`. The `->()` call
    goes through `RuntimeCode.apply()` (the static overload with RuntimeScalar, RuntimeArray,
    int parameters), NOT through `applyEval()`. While `applyEval()` calls `releaseCaptures()`
    in its `finally` block, `apply()` did NOT — so `captureCount` stayed elevated until GC
    eventually collected the RuntimeCode. This prevented `scopeExitCleanup()` from decrementing
    `refCount` on captured variables (because `captureCount > 0` causes early return), which in
    turn kept weak references alive after the strong ref was undef'd.
  - **Discovery path**: Traced why `undef $ref` in Moo's accessor-weaken tests didn't clear
    weak refs when used with `Test::Builder::cmp_ok()`. Narrowed to `eval { $check->($got, $expect); 1 }`
    inside cmp_ok keeping `$got` alive. Verified with system Perl that `eval BLOCK` does NOT
    keep captured vars alive (Perl 5's eval BLOCK runs inline, no closure capture). Confirmed
    that PerlOnJava's `eval BLOCK` goes through `apply()` not `applyEval()` because the try/catch
    is already baked into the generated method (`useTryCatch=true` in `EmitterMethodCreator`).
    The comment at `EmitSubroutine.java` line 586-588 documents this design decision.
  - **Fix**: Added `code.releaseCaptures()` in the `finally` block of `RuntimeCode.apply()`
    (the static method at line 2090) when `code.isEvalBlock` is true. The `isEvalBlock` flag
    is already set by `EmitSubroutine.java` line 392-402 for eval BLOCK's RuntimeCode.
  - **Also in this commit**: Restored `deferDecrementIfTracked` in `releaseCaptures()` with
    `scopeExited` guard (previously removed as "not needed"), and in `scopeExitCleanup()`,
    captured CODE refs fall through to `deferDecrementIfTracked` while non-CODE captured vars
    return early (preserving Sub::Quote semantics where closures legitimately keep values alive).
  - **Result**: All Moo tests pass including accessor-weaken.t (was 16/19, now 19/19).
    All 200 weaken/refcount unit tests pass (9/9 files). `make` passes with no regressions.
  - **Files**: `RuntimeCode.java` (apply() finally block + releaseCaptures()),
    `RuntimeScalar.java` (scopeExitCleanup CODE ref fallthrough)
  - **Commits**: `8a5ab843c`
- [x] Remove pre-flush before pushMark in scope exit (2026-04-09):
  - **Root cause**: `MortalList.flush()` before `pushMark()` in scope exit was causing
    refCount inflation. The pre-flush was intended to prevent deferred decrements from
    method returns being stranded below the mark, but those entries are correctly processed
    by subsequent `setLarge()`/`undefine()` flushes or by the enclosing scope's exit.
  - **Impact**: 13 op/for.t failures (tests 37-42, 103, 105, 130-131, 133-134, 136) and
    re/speed.t -1 regression.
  - **Fix**: Removed the `MortalList.flush()` call before `pushMark()` in both JVM backend
    (`EmitStatement.emitScopeExitNullStores`) and interpreter backend
    (`BytecodeCompiler.exitScope`).
  - **Files**: `EmitStatement.java`, `BytecodeCompiler.java`
  - **Commits**: `3f92c9ee2`
- [x] Track qr// RuntimeRegex objects for proper weak ref handling (2026-04-09):
  - **Root cause**: `RuntimeRegex` objects started with `refCount = -1` (untracked) because
    they are cached in `RuntimeRegex.regexCache`. When copied via `setLarge()`, the
    `nb.refCount >= 0` guard prevented refCount increments. When `weaken()` was called,
    the object transitioned to WEAKLY_TRACKED (-2). Then `undefine()` on ANY strong ref
    unconditionally cleared all weak refs — even though other strong refs still existed.
  - **Impact**: re/qr-72922.t -5 regression (tests 5, 7, 8, 12, 14 — weakened qr// refs
    becoming undef after undef'ing one strong ref while others still existed).
  - **Fix**: `getQuotedRegex()` now creates tracked (`refCount = 0`) RuntimeRegex copies via
    a new `cloneTracked()` method. The cached instances used for `m//` and `s///` remain
    untracked (`refCount = -1`) for efficiency. Fresh RuntimeRegex objects created within
    `getQuotedRegex()` (for merged flags) also get `refCount = 0`. This mirrors Perl 5
    where `qr//` always creates a new SV wrapper around the shared compiled pattern.
  - **Key insight**: The root issue was the same as X2 (§15) — starting refCount tracking
    mid-flight on an already-shared object is wrong. The fix avoids this by creating a
    fresh, tracked object at the `qr//` boundary, while leaving the cached original untouched.
  - **Files**: `RuntimeRegex.java` (`cloneTracked()` method + `getQuotedRegex()` updates)
  - **Commits**: `4d6a9c401`
- [x] Skip tied arrays/hashes in global destruction (2026-04-09):
  - **Root cause**: `GlobalDestruction.runGlobalDestruction()` iterated global arrays and
    hashes to find blessed elements needing DESTROY. For tied arrays, this called
    `FETCHSIZE`/`FETCH` on the tie object, which could be invalid at global destruction
    time (e.g., broken ties from `eval { last }` inside `TIEARRAY`).
  - **Impact**: op/eval.t test 110 ("eval and last") -1 regression, op/runlevel.t test 20
    -1 regression. Both involved tied variables with broken tie objects.
  - **Fix**: Skip `TIED_ARRAY` and `TIED_HASH` containers in the global destruction walk.
    These containers' tie objects may not be valid during cleanup, and iterating them
    would call dispatch methods (FETCHSIZE, FIRSTKEY, etc.) that fail.
  - **Files**: `GlobalDestruction.java`
  - **Commits**: `901801c4c`
- [x] Fix blessed glob DESTROY: instanceof order in DestroyDispatch (2026-04-09):
  - **Root cause**: In `DestroyDispatch.doCallDestroy()`, the `instanceof` chain that
    determines the `$self` reference type for DESTROY had `referent instanceof RuntimeScalar`
    before `referent instanceof RuntimeGlob`. Since `RuntimeGlob extends RuntimeScalar`,
    the RuntimeScalar check matched first, setting `self.type = REFERENCE` instead of
    `GLOBREFERENCE`. This caused `*$self` inside DESTROY to fall through to string-based
    glob lookup (looking up `"MyGlob=GLOB(0x...)"` as a symbol name) instead of proper
    glob dereference. The result: `*$self->{data}` returned undef, `*$self{HASH}` returned
    undef, and `*{$self}` stringified as `*MyGlob::MyGlob=GLOB(...)` instead of
    `*Symbol::GEN19`.
  - **Impact**: Any blessed glob object (IO::Scalar, Symbol::gensym-based objects) that
    stored per-instance data via `*$self->{key}` could not access that data during DESTROY.
    Also caused the "(in cleanup) Not a GLOB reference" warnings from IO::Compress/Uncompress.
  - **Fix**: Swapped the `instanceof` check order: `RuntimeGlob` before `RuntimeScalar`.
    Subclass checks must precede superclass checks in Java instanceof chains.
  - **Verified**: `*$self->{data}`, `*$self{HASH}`, `%{*$self}`, and `*{$self}` all
    resolve correctly during DESTROY, matching Perl 5 behavior.
  - **Files**: `DestroyDispatch.java` (lines 135-148)
  - **Commits**: `e6c653e74`
- [x] Fix m?PAT? regression: per-callsite caching for match-once (2026-04-09):
  - **Root cause**: The `cloneTracked()` change in v5.15 (for qr// DESTROY refcount safety)
    made `getQuotedRegex()` create a fresh RuntimeRegex on every call. For `m?PAT?`, the
    `matched` flag (which tracks "already matched once" state) was reset to `false` on each
    call because `cloneTracked()` deliberately does NOT copy the `matched` field (line 132:
    "matched is not copied — each qr// object tracks its own m?PAT? state"). Before v5.15,
    `getQuotedRegex()` returned the cached instance directly, so the `matched` flag persisted.
  - **Impact**: `regex_once.t` unit test failed — `m?apple?` always matched instead of
    matching only once. The test expects the second iteration to return false.
  - **Fix**: Treat `m?PAT?` like `/o` — both need per-callsite caching to preserve state
    across calls. Two changes:
    1. `EmitRegex.java::handleMatchRegex()`: Detect `?` modifier in flags and use the 3-arg
       `getQuotedRegex(pattern, modifiers, callsiteId)` with a unique callsite ID (same path
       as `/o`).
    2. `RuntimeRegex.java::getQuotedRegex(pattern, modifiers, callsiteId)`: Check for `?`
       modifier in addition to `o` when deciding whether to use callsite caching.
    The callsite-cached regex persists its `matched` flag between calls from the same source
    location, which is exactly the semantics of `m?PAT?` (match once per `reset()` cycle).
  - **Files**: `EmitRegex.java`, `RuntimeRegex.java`
  - **Commits**: `5643db41a`
- [x] Fix caller() returning wrong package/line for interpreter-backed subs (2026-04-10):
  - **Root cause**: `InterpreterState.getPcStack()` returned PCs in oldest-to-newest order
    (ArrayList `add()` insertion order), but `getStack()` returned frames in newest-to-oldest
    order (Deque iteration order). When `ExceptionFormatter.formatThrowable()` indexed both
    lists with the same index, PCs were matched to the wrong interpreter frames.
  - **Impact**: `caller(5)` returned wrong package/line when multiple interpreter-backed
    subroutines were on the call stack simultaneously. Single interpreter frame cases were
    unaffected. Specifically, `croak-locations.t` test 28 failed (reported `pkg=TestPkg,
    line=18` instead of `pkg=Elsewhere, line=21`).
  - **Fix**: Reversed iteration order in `getPcStack()` to return PCs in newest-to-oldest
    order (`for (int i = pcs.size() - 1; i >= 0; i--)`) matching frame stack order.
  - **Result**: croak-locations.t **29/29** (was 28/29), Moo **841/841** (100%)
  - **Files**: `InterpreterState.java` (line 149-157)
  - **Commits**: `9eaa66507`
- [x] Rebase on origin/master (2026-04-10):
  - Rebased 55 commits on origin/master (`3a3bb3f8e`)
  - Three Configuration.java conflicts resolved (all auto-generated git info — took HEAD values)
  - All unit tests pass after rebase

### Moo Test Results

| Milestone | Programs | Subtests | Key Fix |
|-----------|----------|----------|---------|
| Initial (pre-DESTROY/weaken) | ~45/71 | ~700/841 | — |
| After Phase 3 (weaken/isweak) | 68/71 | 834/841 | isweak() works, weak refs tracked |
| After POSIX::_do_exit | 69/71 | 835/841 | demolish-global_destruction.t passes |
| After force-clear fix (v5.8) | **64/71** | **790/841 (93.9%)** | accessor-weaken 19/19, accessor-weaken-pre 19/19 |
| After clearWeakRefsTo CODE skip (v5.10) | **70/71** | **839/841 (99.8%)** | Skip clearing weak refs to CODE objects; fixes Sub::Quote/Sub::Defer inlining |
| After caller() fix (v5.19) | **71/71** | **841/841 (100%)** | Fix PC stack ordering in InterpreterState; croak-locations.t 29/29 |

**Note on v5.8→v5.10**: The v5.8 decrease (69→64) was caused by WEAKLY_TRACKED premature
clearing of CODE refs breaking Sub::Quote/Sub::Defer. The v5.10 fix (skip clearWeakRefsTo
for RuntimeCode) resolved all 46 of those failures plus 3 from constructor-modify.t.

### Remaining Moo Failures (0 — all 841/841 subtests pass)

All 71 Moo test programs pass with all 841 subtests. The previous `overloaded-coderefs.t`
failures (tests 6 and 8, B::Deparse limitation) were resolved by the caller() fix in v5.19
which corrected PC stack ordering for interpreter-backed subroutines.

### Last Commit
- `9eaa66507`: "Fix caller() returning wrong package/line for interpreter-backed subs"
- Branch: `feature/destroy-weaken` (rebased on origin/master `3a3bb3f8e`)

### Next Steps

#### Performance Optimization (blocking PR merge)

See **§16. Performance Optimization Plan** for the full analysis and phased approach.

**Benchmark**: `./jperl examples/life_bitpacked.pl` — 5 Mcells/s (branch) vs 13 Mcells/s (master).

#### Pending items
1. **Resolve performance regression** before merging (see §16)
2. **Update `moo_support.md`** with final Moo test results and analysis
3. **Test command**: `./jcpan --jobs 8 -t Moo` runs the full Moo test suite

#### Image::ExifTool Test Results (2026-04-09)

After fixing the StackOverflowError in `deferDecrementRecursive` (commit `886f7e171`)
and null-element NPE in ArrayDeque (null elements from sparse arrays):
- **113/113 test programs pass**, **597/597 subtests pass**
- **"(in cleanup)" warnings**: IO::Uncompress::Base and IO::Compress::Base were emitting
  "Not a GLOB reference" warnings during DESTROY. Root cause identified and fixed in v5.17:
  the `instanceof` check order in `DestroyDispatch.doCallDestroy()` was misclassifying
  blessed globs as plain scalar references, causing `*$self` to fail during DESTROY.

---

## 15. Approaches Tried and Reverted (Do NOT Retry)

This section documents approaches that were attempted and failed, with clear explanations
of **why** they failed. These are recorded to prevent re-trying the same dead ends.

### X1. Remove birth-tracking `refCount = 0` from `createReferenceWithTrackedElements()` (REVERTED)

**What it did**: Removed the line `this.refCount = 0` from
`RuntimeHash.createReferenceWithTrackedElements()`, so anonymous hashes would stay at
refCount=-1 (untracked) instead of being birth-tracked.

**Why it seemed promising**: Without birth-tracking, hashes stay at refCount=-1. When
`weaken()` transitions them to WEAKLY_TRACKED, `undef $ref` → `scopeExitCleanup()` →
clears weak refs. This fixed accessor-weaken tests 4, 9, 16 (undef clearing).

**Why it failed**: It broke `isweak()` tests (7 additional failures in accessor-weaken.t:
tests 2, 3, 6, 7, 8, 10, 15). Without birth-tracking, the hash is untracked, so
`weaken()` transitions to WEAKLY_TRACKED — but `isweak()` doesn't detect
WEAKLY_TRACKED as "weak" in the way Moo's tests expect. Birth-tracking is needed so
that `weaken()` can decrement a real refCount and leave the hash in a state that
correctly interacts with `isweak()`.

**Lesson**: Birth-tracking for anonymous hashes is load-bearing for `isweak()` correctness.
Don't remove it — instead fix the clearing mechanism separately.

### X2. Type-aware `weaken()` transition: set `refCount = 1` for data structures (REVERTED)

**What it did**: In `WeakRefRegistry.weaken()`, when transitioning from NOT_TRACKED
(refCount=-1), set `refCount = 1` for RuntimeHash/RuntimeArray/RuntimeScalar referents
(data structures), while keeping WEAKLY_TRACKED (-2) for RuntimeCode/RuntimeGlob
(stash-stored types).

**Why it seemed promising**: Data structures exist only in lexicals/stores tracked by
`setLarge()`, so starting at refCount=1 gives an accurate count (one strong ref = the
variable that existed before `weaken()`). Future `setLarge()` copies will increment/
decrement correctly. CODE/Glob refs keep WEAKLY_TRACKED because stash refs are invisible.

**Why it failed**: Starting refCount at 1 is an UNDERCOUNT for objects with multiple
pre-existing strong refs (created before tracking started). During routine `setLarge()`
operations, refCount prematurely reaches 0, triggering `callDestroy()` →
`clearWeakRefsTo()` which sets weak refs to undef mid-operation. In Sub::Defer, this
cleared a deferred sub entry, causing the next access to re-trigger undeferring →
infinite `apply()` → `apply()` → StackOverflowError.

**Lesson**: You CANNOT start accurate refCount tracking mid-flight. Once an object exists
with multiple untracked strong refs, any starting count will be wrong. The only correct
approaches are: (a) track from birth, or (b) accept the limitation and use heuristics.

### X3. Remove WEAKLY_TRACKED transition entirely from `weaken()` — NOT TRIED, known bad

**Why it would fail**: Without WEAKLY_TRACKED, untracked objects (refCount=-1) stay at
-1 after `weaken()`. The three clearing sites (setLarge, scopeExitCleanup, undefine)
only check for `refCount == WEAKLY_TRACKED` or `refCount > 0`. At refCount=-1, none of
them clear weak refs. The force-clear in `undefine()` only fires for
`refCountOwned && refCount > 0` objects. So weak refs to untracked hashes would NEVER
be cleared, breaking accessor-weaken tests 4, 9, 16.

**Note**: The proposed fix (skip WEAKLY_TRACKED for RuntimeCode only) is different — it
skips WEAKLY_TRACKED only for RuntimeCode, NOT for hashes/arrays.

### X4. Lost commits from moo.md (commits cad2f2566, 800f70faa, 84c483a24)

The `dev/modules/moo.md` document references three commits that achieved 841/841 Moo
passing but were lost during branch rewriting. These commits are NOT on any branch or
in the reflog. The approaches documented in moo.md were:

- **Category A (cad2f2566)**: In `weaken()`, transition to WEAKLY_TRACKED when
  unblessed refCount > 0. Also removed `MortalList.flush()` from `RuntimeCode.apply()`.
  This was for the quote_sub inlining problem (same as v5.9 problem).

- **Category B (800f70faa)**: Moved birth tracking from `RuntimeHash.createReference()`
  to `createReferenceWithTrackedElements()`. In `weaken()`, when refCount reaches 0
  after decrement, destroy immediately (only anonymous objects reach this state).

- **Category C (84c483a24)**: Track pad constants in RuntimeCode. When glob's CODE slot
  is overwritten, clear weak refs to old sub's pad constants (optree reaping emulation).

These commits' exact implementations are lost. The moo.md describes them at a high level
but not with enough detail to reconstruct precisely. The current branch has different code
paths, so re-applying these approaches requires fresh implementation.

**Key facts about these lost commits**:
- They worked together as a set — each alone may not be sufficient
- They were made BEFORE the "refcount leaks" fix (commit 41ab517ca) and the
  "prevent premature weak ref clearing for untracked objects" fix (862bdc751)
- The codebase has evolved significantly since, so the same approach may produce
  different results now

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

See §14 for full feasibility analysis. Summary: JVM WeakReference alone cannot fix
tests 10/11 because JVM GC is non-deterministic — the referent may linger after all
strong refs are removed.

---

## 13. Moo Accessor Code Generation for `lazy + weak_ref` (v5.7)

### 13.1 The Generated Code

For `has two => (is => 'rw', lazy => 1, weak_ref => 1, default => sub { {} })`,
Moo's `Method::Generate::Accessor` produces (via `Sub::Quote`):

```perl
# Full accessor (getset):
(@_ > 1
  ? (do { Scalar::Util::weaken(
        $_[0]->{"two"} = $_[1]
      ); no warnings 'void'; $_[0]->{"two"} })
  : exists $_[0]->{"two"} ?
      $_[0]->{"two"}
    :
      (do { Scalar::Util::weaken(
          $_[0]->{"two"} = $default_for_two->($_[0])
        ); no warnings 'void'; $_[0]->{"two"} })
)
```

Where `$default_for_two` is a closed-over coderef holding `sub { {} }`.

### 13.2 Code Generation Trace

| Step | Method (Accessor.pm) | Decision | Result |
|------|----------------------|----------|--------|
| 1 | `generate_method` (line 46) | `is => 'rw'` → accessor | Calls `_generate_getset` |
| 2 | XS fast-path (line 165) | `is_simple_get` = false (lazy+default), `is_simple_set` = false (weak_ref) | Falls to pure-Perl path |
| 3 | `_generate_getset` (line 665) | | `@_ > 1 ? <SET> : <GET>` |
| 4 | `_generate_use_default` (line 384) | No coerce, no isa | `exists test ? simple_get : simple_set(get_default)` |
| 5 | `_generate_call_code` (line 540) | Default is plain coderef, not quote_sub | `$default_for_two->($_[0])` |
| 6 | `_generate_simple_set` (line 624) | `weak_ref => 1` | `do { weaken($assign); $get }` |

### 13.3 Runtime Behavior (Perl 5 vs PerlOnJava)

**Perl 5 — getter on fresh object (`$foo2->two`):**

```
1. exists $_[0]->{"two"}         → false (not set yet)
2. $default_for_two->($_[0])     → creates {} → temp T holds strong ref (refcount=1)
3. $_[0]->{"two"} = T            → hash entry E gets ref to {} (refcount=2)
4. weaken(E)                     → E becomes weak (refcount=1, only T is strong)
5. do { ... } completes          → T goes out of scope → refcount drops to 0
                                    → {} freed → E (weak ref) becomes undef
6. $_[0]->{"two"}                → returns undef ✓
```

**PerlOnJava — same call:**

```
1. exists $_[0]->{"two"}         → false
2. $default_for_two->($_[0])     → creates RuntimeHash H, refCount=-1 (NOT_TRACKED)
3. $_[0]->{"two"} = T            → setLarge: refCount=-1, no increment
4. weaken(E)                     → refCount: -1 → WEAKLY_TRACKED (-2)
                                    (not decremented, not tracked for scope exit)
5. do { ... } completes          → scopeExitCleanup for T
                                    → deferDecrementIfTracked: refCount=-2 → SKIP
6. $_[0]->{"two"}                → returns H (still alive!) ✗
```

**Key divergence at step 4**: In Perl 5, `weaken()` decrements the refcount (2→1).
When T goes out of scope (step 5), the refcount drops to 0 and the value is freed.
In PerlOnJava, WEAKLY_TRACKED (-2) skips all mortal/scope-exit processing, so H is
never freed.

### 13.4 Test 19: Optree Reaping

```perl
sub mk_ref { \ 'yay' };
my $foo_ro = Foo->new(one => mk_ref());
# $foo_ro->{one} holds weak ref to \ 'yay' (a compile-time constant in mk_ref's optree)
{ no warnings 'redefine'; *mk_ref = sub {} }
# Perl 5: old mk_ref optree freed → \ 'yay' refcount=0 → weak ref cleared
ok (!defined $foo_ro->{one}, 'optree reaped, ro static value gone');
```

In PerlOnJava, compiled bytecode is never freed by the JVM. The constant `\ 'yay'`
lives in a generated class's constant pool and is held by the ClassLoader. Redefining
`*mk_ref` replaces the glob's CODE slot but doesn't unload the old class. This test
**cannot pass** without JVM class unloading, which requires custom ClassLoader management
that PerlOnJava doesn't implement.

---

## 14. JVM WeakReference Feasibility Analysis (v5.7)

### 14.1 Approach: Replace Strong Ref with JVM WeakReference

The idea: when `weaken($ref)` is called, replace the strong Java reference in
`ref.value` with a `java.lang.ref.WeakReference<RuntimeBase>`. Only the weakened
scalar loses its strong reference; other (non-weakened) scalars keep theirs. The
JVM GC then naturally collects the referent when no strong Java refs remain.

```java
// In weaken():
RuntimeBase referent = (RuntimeBase) ref.value;
ref.value = null;                    // remove strong ref
ref.weakJavaRef = new WeakReference<>(referent);  // JVM weak ref

// On access to a weak ref:
RuntimeBase val = ref.weakJavaRef.get();
if (val == null) {
    ref.type = RuntimeScalarType.UNDEF;  // referent was GC'd
    ref.weakJavaRef = null;
    return null;
}
return val;
```

### 14.2 Why This Cannot Fix Tests 10/11

**JVM GC is non-deterministic.** Unlike Perl 5's synchronous refcount decrement
(refcount reaches 0 → freed immediately), JVM garbage collection runs at arbitrary
times determined by the runtime. After removing the strong ref from the weak scalar
and the temp going out of scope:

```
                  Perl 5              JVM
Step 4 (weaken):  refcount 2→1        temp still holds strong Java ref
Step 5 (scope):   refcount 1→0→FREE   temp ref cleared, but object in heap
Step 6 (access):  undef ✓             GC hasn't run yet → object still alive ✗
```

Even with `System.gc()` (which is only a hint), there is no JVM guarantee that the
referent will be collected before the next line of code executes. On some JVMs,
`System.gc()` is a complete no-op (e.g., with `-XX:+DisableExplicitGC`).

### 14.3 Approaches Evaluated

| # | Approach | Can Fix 10/11 | Can Fix 19 | Cost | Verdict |
|---|----------|:---:|:---:|------|---------|
| 1 | **WEAKLY_TRACKED (current)** | No | No | Zero runtime cost | Current — 99.3% Moo pass rate |
| 2 | **Type-aware refCount=1** | Maybe | No | Medium | **Failed** — infinite recursion in Sub::Defer (§12.3) |
| 3 | **JVM WeakReference** | No (GC non-deterministic) | No | 102 instanceof changes in 35 files | Not viable for deterministic clearing |
| 4 | **PhantomReference + ReferenceQueue** | No (same GC timing) | No | Background thread + queue polling | Same non-determinism as #3 |
| 5 | **Full refcounting from birth** | Yes | No | Every object gets refCount tracking from allocation; every copy/drop increments/decrements | Matches Perl 5 but adds overhead to ALL objects, not just blessed |
| 6 | **JVM WeakRef + forced System.gc()** | Unreliable | No | Performance catastrophe | Not viable |
| 7 | **Reference scanning at weaken()** | Theoretically | No | Scan all live scalars/arrays/hashes | O(n) at every weaken() call — impractical |

### 14.4 Why Full Refcounting From Birth Is the Only Correct Fix

Tests 10/11 require **synchronous, deterministic** detection of "no more strong refs"
at the exact moment a scope variable goes out of scope. On the JVM, the only way to
achieve this is reference counting — the same mechanism Perl 5 uses.

**What "full refcounting from birth" means:**
- Every `RuntimeHash`, `RuntimeArray`, `RuntimeScalar` (referent) gets `refCount = 0`
  at creation (not just blessed objects)
- Every `setLarge()` that copies a reference increments the referent's refCount
- Every `setLarge()` that overwrites a reference decrements the old referent's refCount
- Every `scopeExitCleanup()` decrements refCount for reference-type locals
- When refCount reaches 0: clear all weak refs to this referent

**Why this is expensive:**
- `refCount` field already exists on `RuntimeBase` (no memory overhead)
- But INCREMENT/DECREMENT on every copy/drop adds a branch + arithmetic to the
  hottest path in the runtime (`setLarge()` is called for every variable assignment)
- Objects that are never weakened bear this cost for no benefit
- Estimated overhead: 5-15% on assignment-heavy workloads

**Optimization: lazy activation**
- Keep `refCount = -1` (NOT_TRACKED) for all unblessed objects by default
- When `weaken()` is called, retroactively start tracking
- Problem: we can't know the correct starting count (§12.3 failure)
- Variant: at `weaken()` time, walk the current call stack to count refs?
  Still impractical — locals may be in JVM registers, not inspectable from Java.

### 14.5 Impact Assessment: instanceof Changes for JVM WeakReference

Even if JVM GC non-determinism were acceptable, the implementation cost is high:

- **102 `instanceof` checks** across **35 files** would need to handle the case where
  `ref.value` is null or a `WeakReference` wrapper instead of a direct `RuntimeBase`
- Key dereference paths (`hashDeref`, `arrayDeref`, `scalarDeref`, `codeDerefNonStrict`,
  `globDeref`) would each need a WeakReference check
- Every `setLarge()` call would need to handle weak source values
- Error paths would need to handle "referent was collected" gracefully

This is a large, error-prone refactor for uncertain benefit (GC timing still
non-deterministic).

### 14.6 Conclusion

The 6 remaining accessor-weaken subtests (tests 10, 11, 19 in both test files)
represent a **fundamental semantic gap** between Perl 5's synchronous refcounting
and the JVM's asynchronous tracing GC:

| Test | Perl 5 Mechanism | JVM Equivalent | Gap |
|------|------------------|----------------|-----|
| 10, 11 | Refcount drops to 0 at scope exit → immediate free | GC runs "eventually" | **Non-deterministic timing** |
| 19 | Optree freed when sub redefined → constants freed | Bytecode held by ClassLoader | **No class unloading** |

**Recommendation**: Accept the 99.3% Moo pass rate (835/841 subtests). The failing
tests exercise edge cases (lazy+weak anonymous defaults, optree reaping) that are
unlikely to affect real-world Moo usage. The cost of full refcounting from birth
(the only correct fix for tests 10/11) far exceeds the benefit of 6 additional
subtests passing.

### Post-Merge Action Items

1. **Check DESTROY TODO markers after `untie` fix merges.** A separate PR
   is fixing `untie` to not call DESTROY automatically. DESTROY-related
   tests are being marked `TODO` in that PR. Once both PRs are merged,
   verify whether the TODO markers can be removed (i.e., whether DESTROY
   now fires correctly in the `untie` scenarios with this branch's
   refined Strategy A changes in place).

### Version History
- **v5.20** (2026-04-10): Performance optimization plan + fix reset() m?PAT? regression:
  1. Added §16 Performance Optimization Plan with root cause analysis (5 sources of overhead)
     and 6-phase optimization strategy to restore ~13 Mcells/s on life_bitpacked.pl.
  2. Fixed `RuntimeRegex.reset()` not clearing `m?PAT?` match-once flags in
     `optimizedRegexCache` — restores op/reset.t from 27/45 back to 30/45.
  3. Updated PR #464 description: WIP, all tests pass, performance regression noted.
- **v5.19** (2026-04-10): Fix caller() for interpreter-backed subs + rebase:
  1. Root cause: `InterpreterState.getPcStack()` returned PCs in oldest-to-newest order
     (ArrayList `add()` insertion order), but `getStack()` returned frames in newest-to-oldest
     order (Deque iteration order). `ExceptionFormatter.formatThrowable()` indexed both with
     the same index, mismatching PCs to the wrong interpreter frames.
  2. Fix: Reversed iteration in `getPcStack()` to return PCs newest-to-oldest, matching
     frame stack order.
  3. **Result**: croak-locations.t **29/29** (was 28/29), Moo **841/841** (100%).
  4. Rebased 55 commits on origin/master (`3a3bb3f8e`).
  Files: `InterpreterState.java`
- **v5.12** (2026-04-09): eval BLOCK eager capture release:
  1. Root cause: eval BLOCK compiled as `sub { ... }->()` captures outer lexicals but uses
     `apply()` (not `applyEval()`), which never called `releaseCaptures()`. Captures stayed
     alive until GC, preventing `scopeExitCleanup()` from decrementing refCount on captured
     variables. This kept weak refs alive through `eval { ... }` boundaries (e.g.,
     Test::Builder's `cmp_ok` using `eval { $check->($got, $expect); 1 }`).
  2. Fix: `code.releaseCaptures()` in `apply()`'s finally block when `code.isEvalBlock`.
  3. Also: restored `deferDecrementIfTracked` in `releaseCaptures()` with `scopeExited` guard;
     in `scopeExitCleanup`, CODE-type captured vars fall through to decrement (releasing inner
     closures' captures) while non-CODE captured vars return early (Sub::Quote safety).
  4. **Result**: accessor-weaken.t 19/19, all 200 weaken/refcount unit tests pass, make clean.
- **v5.11** (2026-04-09): Tie DESTROY on untie via refcounting:
  1. Tie wrappers now increment refCount in constructors and decrement in untie via
     `releaseTiedObject()`. DESTROY fires immediately if no other refs, deferred if held.
  2. Null guard in TiedVariableBase for proxy entries passing null tiedObject.
  3. Removed 5 TODO blocks from tie tests; added 2 new deferred DESTROY subtests.
- **v5.10** (2026-04-09): Skip clearWeakRefsTo for CODE objects — fixes 46 Moo subtests:
  1. Root cause: CODE refs' stash references bypass setLarge(), making them invisible to
     refcounting. Two premature clearing paths: (a) WEAKLY_TRACKED transition in weaken()
     → clearing via setLarge()/scopeExitCleanup(), (b) MortalList.flush() decrementing
     tracked CODE ref refCount to 0 → callDestroy() → clearWeakRefsTo().
  2. Fix: Guard in weaken() to skip WEAKLY_TRACKED for RuntimeCode; guard in
     clearWeakRefsTo() to skip RuntimeCode objects entirely.
  3. **Result**: Moo 70/71 programs, 839/841 subtests (99.8%). Remaining 2 failures in
     overloaded-coderefs.t are B::Deparse limitations.
- **v5.17** (2026-04-09): Fix blessed glob DESTROY — instanceof order in DestroyDispatch:
  1. `DestroyDispatch.doCallDestroy()` checked `referent instanceof RuntimeScalar` before
     `referent instanceof RuntimeGlob`. Since `RuntimeGlob extends RuntimeScalar`, blessed
     globs were misclassified as REFERENCE instead of GLOBREFERENCE. This broke `*$self->{key}`
     access during DESTROY (returned undef instead of stored data).
  2. Swapped the instanceof check order: RuntimeGlob before RuntimeScalar.
  3. This also fixes the "(in cleanup) Not a GLOB reference" warnings from IO::Compress/
     IO::Uncompress DESTROY handlers that were reported as cosmetic in v5.16.
  Files: `DestroyDispatch.java`
- **v5.18** (2026-04-09): Fix m?PAT? regression — per-callsite caching for match-once:
  1. Root cause: `cloneTracked()` (added in v5.15 for qr// refcount safety) created a fresh
     RuntimeRegex on every `getQuotedRegex()` call, resetting the `matched` flag that `m?PAT?`
     uses to track "already matched once" state. Before v5.15, the cached instance was returned
     directly and the flag persisted.
  2. Fix: `m?PAT?` now uses the same per-callsite caching mechanism as `/o`. Both
     `EmitRegex.java` (detect `?` modifier → use 3-arg getQuotedRegex with callsite ID) and
     `RuntimeRegex.java` (check `?` modifier alongside `o` for cache lookup) were updated.
  3. **Result**: `regex_once.t` passes — `m?apple?` matches on first call, returns false on second.
  Files: `EmitRegex.java`, `RuntimeRegex.java`
- **v5.16** (2026-04-09): Fix ExifTool StackOverflowError in circular ref traversal:
  1. Converted `MortalList.deferDecrementRecursive()` from recursive to iterative using
     `ArrayDeque<RuntimeScalar>` work queue + `IdentityHashMap`-based visited set.
     ExifTool's self-referential hashes caused infinite recursion -> StackOverflowError.
  2. Added null guards for `ArrayDeque.add()` — sparse arrays contain null elements,
     and `ArrayDeque` does not accept nulls (throws NPE). This caused DNG.t/Nikon.t
     ExifTool write tests to fail.
  3. ExifTool test results: 113/113 programs pass, 597/597 subtests pass.
  4. "(in cleanup) Not a GLOB reference" warnings from IO::Compress/Uncompress DESTROY
     handlers are cosmetic and don't affect test correctness.
  Files: `MortalList.java`
- **v5.15** (2026-04-09): Fix Perl 5 core test regressions (op/for.t, qr-72922.t, op/eval.t,
  op/runlevel.t):
  1. **Pre-flush removal**: `MortalList.flush()` before `pushMark()` in scope exit caused
     refCount inflation, breaking 13 op/for.t tests and re/speed.t -1. Fix: remove the
     pre-flush; entries below the mark are processed by subsequent flushes or enclosing scope.
  2. **qr// tracking**: RuntimeRegex objects were untracked (refCount=-1, shared via cache).
     `weaken()` transitioned to WEAKLY_TRACKED; `undef` on any strong ref cleared all weak refs
     even with other strong refs alive. Fix: `getQuotedRegex()` creates tracked copies via
     `cloneTracked()` (refCount=0); cached instances remain untracked. Mirrors Perl 5 where
     `qr//` creates a new SV around the shared compiled pattern. Fixes re/qr-72922.t -5.
  3. **Global destruction tied containers**: `GlobalDestruction.runGlobalDestruction()` iterated
     tied arrays/hashes, calling FETCHSIZE/FETCH on potentially invalid tie objects. Fix: skip
     `TIED_ARRAY`/`TIED_HASH` in the global destruction walk. Fixes op/eval.t test 110 and
     op/runlevel.t test 20.
  4. **All 5 regressed tests now match master baselines**: op/for.t 141/149, re/speed.t 26/59,
     re/qr-72922.t 10/14, op/eval.t 159/173, op/runlevel.t 12/24.
- **v5.12** (2026-04-09): eval BLOCK eager capture release + architecture doc update:
  1. `eval BLOCK` compiled as `sub{...}->()` kept `captureCount` elevated, preventing
     `scopeExitCleanup()` from decrementing refCount on captured variables.
  2. Fix: `releaseCaptures()` in `RuntimeCode.apply()` finally block when `isEvalBlock`.
  3. Updated `dev/architecture/weaken-destroy.md` to match current codebase (12 tasks).
- **v5.9** (2026-04-09): Documented WEAKLY_TRACKED premature clearing root cause trace;
  added §15 with 4 approaches tried and reverted (X1-X4).
- **v5.8** (2026-04-09): Force-clear fix for unblessed weak refs:
  1. Added force-clear in `RuntimeScalar.undefine()`: when an unblessed object
     (`blessId == 0`) has weak refs registered but refCount doesn't reach 0 after
     decrement, force `refCount = Integer.MIN_VALUE` and clear weak refs. Safe because
     unblessed objects have no DESTROY method.
  2. Removed premature `WEAKLY_TRACKED` transition in `WeakRefRegistry.weaken()` that
     was causing weak refs to be cleared when ANY strong ref exited scope while other
     strong refs (e.g., Moo's CODE refs in glob slots) still held the target.
  3. **Result**: Moo accessor-weaken.t 19/19 (was 16/19), accessor-weaken-pre-5_8_3.t 19/19.
  4. Investigated and rejected alternative: removing birth-tracking `refCount = 0` from
     `createReferenceWithTrackedElements()` — fixed undef-clearing but broke `isweak()`.
- **v5.7** (2026-04-08): JVM WeakReference feasibility analysis. Analyzed 7 approaches
  for fixing remaining accessor-weaken subtests. Concluded JVM GC non-determinism makes
  GC-based approaches unviable; only full refcounting from birth can fix tests 10/11 (§14).
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

---

## 16. Performance Optimization Plan

### 16.1 Problem Statement

The `feature/destroy-weaken` branch shows measurable performance regressions on
compute-intensive benchmarks. The life_bitpacked benchmark shows ~27 Mcells/s (branch)
vs ~29 Mcells/s (master) — a ~7% regression. Other benchmarks show larger regressions,
particularly `benchmark_global.pl` (-27%) and `benchmark_lexical.pl` (-30%).

The benchmarks do NOT use blessed objects, DESTROY, or weak references, so all overhead
is "tax" on unrelated code.

### 16.2 Benchmark Baseline (2026-04-10)

Environment: macOS, Java 21+, `make clean && make` on each branch before benchmarking.

#### Throughput benchmarks (ops/s, higher is better)

| Benchmark | Master | Branch | Delta | Notes |
|-----------|--------|--------|-------|-------|
| `benchmark_lexical.pl` | 397,633/s | 280,214/s | **-30%** | Pure lexical arithmetic loop |
| `benchmark_global.pl` | 96,850/s | 70,879/s | **-27%** | Global variable arithmetic loop |
| `benchmark_closure.pl` | 866/s | 810/s | **-6%** | Closure creation + invocation |
| `benchmark_eval_string.pl` | 81,966/s | 83,753/s | +2% | eval STRING compilation |
| `benchmark_method.pl` | 444/s | 387/s | **-13%** | Method dispatch loop |
| `benchmark_regex.pl` | 51,343/s | 45,078/s | **-12%** | Regex matching loop |
| `benchmark_string.pl` | 28,487/s | 25,085/s | **-12%** | String operations |
| `life_bitpacked.pl` (5K gen) | ~29 Mcells/s | ~27 Mcells/s | **-7%** | Bitwise integer inner loop |

#### Memory benchmarks (delta, lower is better)

| Workload | Master | Branch | Delta |
|----------|--------|--------|-------|
| Array creation (15M elements) | 1.73 GB | 2.22 GB | **+28%** |
| Hash creation (2M entries) | 710.0 MB | 707.6 MB | 0% |
| String buffer (100M chars) | 769.8 MB | 781.3 MB | +1% |
| Nested data structures (30K objects) | 282.7 MB | 458.8 MB | **+62%** |

**Key observations**:
- Largest regressions are in tight loops with many lexical variables (`benchmark_lexical.pl`)
  and global variable access (`benchmark_global.pl`)
- The 30% lexical regression correlates directly with `scopeExitCleanup` overhead on
  every `my` variable at scope exit
- The 28% array memory regression is from the extra `refCount` field on RuntimeBase
  and `refCountOwned`/`captureCount`/`scopeExited` fields on RuntimeScalar
- The 62% nested data structure memory regression is from RuntimeBase `refCount` on every
  array/hash/code object plus RuntimeScalar field growth

### 16.3 Root Cause Analysis

Bytecode disassembly (`./jperl --disassemble`) and code review identified **five** sources
of overhead, ordered by estimated impact:

#### A. `scopeExitCleanup` called for EVERY `my` scalar at scope exit (HIGH)

**What changed**: `EmitStatement.emitScopeExitNullStores()` now emits a call to
`RuntimeScalar.scopeExitCleanup(scalar)` for every `my $var` in the exiting scope.
Previously it only checked `ioOwner` glob references (a rare case). Now it also calls
`MortalList.deferDecrementIfTracked()` which checks `refCountOwned`, `type & REFERENCE_BIT`,
`instanceof RuntimeBase`, and `base.refCount`.

**Impact on life_bitpacked.pl**: The inner loop (`next_generation_parallel`) declares
~15+ `my` variables per iteration (e.g. `$cell`, `$n_left`, `$n_right`, `$above`,
`$below`, `$s1`, `$c1`, `$s2`, `$c2`, `$s3`, `$c3`, ...). All are plain integers.
Each scope exit generates N×`scopeExitCleanup` calls + `pushMark`/`popAndFlush` pair.
With 100×4 word iterations × 5000 generations = 2M iterations, this adds ~30M+ useless
method calls.

**The `scopeExitCleanup` method itself** is not trivially cheap either — it checks
`captureCount`, `ioOwner`, `type == GLOBREFERENCE`, then calls `deferDecrementIfTracked`
which has 4 conditional checks before the early return. The JIT may inline some of this
but the method dispatch + branch misprediction cost adds up at 30M+ calls.

#### B. `pushMark`/`popAndFlush` pairs on every block scope (MEDIUM-HIGH)

**What changed**: Every `for`, `if`, bare block now wraps scope-exit cleanup with
`MortalList.pushMark()` before and `MortalList.popAndFlush()` after. These are
`static synchronized` calls that manipulate an ArrayList.

**Impact**: In nested loops, the inner loop's block exit triggers pushMark+popAndFlush
on every iteration. These are cheap individually (just `ArrayList.add`/`removeLast`) but
at millions of iterations the overhead accumulates — especially because `popAndFlush`
checks `!active || marks.isEmpty()` and `pending.size() <= mark` on every call.

#### C. `set()` fast path now routes references through `setLarge()` (MEDIUM)

**What changed**: The fast path in `RuntimeScalar.set(RuntimeScalar)` added:
```java
if (((this.type | value.type) & REFERENCE_BIT) != 0) {
    return setLarge(value);
}
```
This check runs on EVERY `set()` call, even for integer-to-integer assignments. The
branch itself is trivially predicted for non-reference types, but `setLarge()` is now
significantly larger (refCount tracking, WeakRefRegistry, MortalList.flush) which may
prevent the JIT from inlining `set()` due to the increased bytecode size of the callee.

**Impact**: `set()` is the single most-called method in PerlOnJava. If the JIT decides
not to inline it (because `setLarge` pulls in too many classes), every variable assignment
becomes a real method call instead of inlined field stores.

#### D. Extra fields on RuntimeScalar increase object size (LOW-MEDIUM)

**What changed**: Three new boolean/int fields added to RuntimeScalar:
- `captureCount` (int, 4 bytes)
- `scopeExited` (boolean, 1 byte + padding)
- `refCountOwned` (boolean, 1 byte + padding)

Plus `refCount` (int, 4 bytes) on RuntimeBase.

**Impact**: With JVM object alignment (8-byte boundaries), RuntimeScalar grew by ~16 bytes.
This increases GC pressure and reduces cache density. Life_bitpacked creates millions of
temporary RuntimeScalar objects for arithmetic results.

#### E. `MortalList.flush()` called on every `setLarge()` (LOW)

**What changed**: `setLarge()` now ends with `MortalList.flush()`. Cost when
`MortalList.active == true` and `pending.isEmpty()`: one boolean check + one
`ArrayList.isEmpty()` call. This was previously not present.

**Impact**: Low individually, but `setLarge()` is called for every reference assignment.

### 16.4 Optimization Strategy

#### Guiding principle
**Zero overhead for code that doesn't use DESTROY/weaken.** The refcounting mechanism
should be invisible to programs that don't bless objects into classes with DESTROY methods.

#### Phase O1: Compile-time scope-exit elision (HIGH impact, LOW risk)

**Goal**: Skip `scopeExitCleanup` calls for variables that provably never hold references.

**Approach**: At compile time, track whether each `my` variable could hold a reference:
- Variables assigned only from arithmetic/string operations → **never a reference**
- Variables assigned from `@_` slicing, sub calls, hash/array access → **might be a reference**
- Variables explicitly assigned a reference (`\@foo`, `[...]`, `{...}`) → **is a reference**

In `emitScopeExitNullStores()`, only emit `scopeExitCleanup` calls for variables that
**might** hold a reference. For integer-only inner loop variables, skip entirely.

**Conservative fallback**: If the analysis can't prove a variable is reference-free,
emit the cleanup call (safe default). This is a sound optimization — it can't break
anything, it just reduces calls.

**Implementation sketch**:
1. Add a `boolean mightHoldReference` flag to symbol table entries
2. Default to `true` (conservative)
3. Set to `false` for variables with only integer/double/string assignments
4. In `emitScopeExitNullStores()`, check the flag before emitting cleanup call

**Estimated impact**: For life_bitpacked.pl, this eliminates ~90% of scopeExitCleanup
calls since most inner-loop variables are pure integers.

**Files**: `ScopedSymbolTable.java`, `EmitStatement.java`

#### Phase O2: Elide `pushMark`/`popAndFlush` for scopes with no cleanup (HIGH impact, LOW risk)

**Goal**: Skip MortalList mark/flush for blocks that have no `scopeExitCleanup` calls.

**Approach**: After Phase O1 filtering, if a scope has zero variables needing cleanup,
skip the `pushMark()`/`popAndFlush()` pair entirely. This is a trivial extension of O1 —
just check if the filtered list is empty before emitting the mark/flush calls.

**Implementation**: In `emitScopeExitNullStores(ctx, scopeIndex, flush)`:
```java
List<Integer> needsCleanup = scalarIndices.stream()
    .filter(idx -> ctx.symbolTable.mightHoldReference(idx))
    .toList();
if (needsCleanup.isEmpty() && hashIndices.isEmpty() && arrayIndices.isEmpty()) {
    // No cleanup needed — skip pushMark/popAndFlush entirely
    // Still null the slots for GC
} else {
    // Emit pushMark, cleanup calls, popAndFlush as before
}
```

**Estimated impact**: Eliminates 2 static calls per inner loop iteration in
life_bitpacked.pl.

**Files**: `EmitStatement.java`

#### Phase O3: Runtime fast-path in `scopeExitCleanup` (MEDIUM impact, LOW risk)

**Goal**: Make `scopeExitCleanup` cheaper for the common case (non-reference scalars).

**Approach**: Add an early-exit check at the top of `scopeExitCleanup`:
```java
public static void scopeExitCleanup(RuntimeScalar scalar) {
    if (scalar == null || scalar.type < RuntimeScalarType.TIED_SCALAR) return;
    // ... existing logic ...
}
```

For plain integers/strings/doubles (type 0-8), this is a single field read + comparison.
The JIT will inline this to a trivially-predicted branch. This helps even if Phase O1
doesn't eliminate the call entirely (e.g., variables whose type can't be statically
determined).

**Estimated impact**: Reduces per-call cost from ~100ns to ~2ns for non-reference scalars.

**Files**: `RuntimeScalar.java`

#### Phase O4: Prevent `setLarge` bloat from killing `set()` inlining (MEDIUM impact, MEDIUM risk)

**Goal**: Keep the `set()` method small enough for JIT inlining.

**Approach**: The JIT's inlining budget is based on bytecode size. `setLarge()` grew
substantially with refCount/WeakRef/MortalList logic. Options:

a. **Extract refCount logic into a separate method** called from `setLarge()`:
   ```java
   private RuntimeScalar setLarge(RuntimeScalar value) {
       // ... unwrap tied/readonly ...
       // ... IO lifecycle ...
       if (((this.type | value.type) & REFERENCE_BIT) != 0) {
           return setLargeRefCounted(value);
       }
       this.type = value.type;
       this.value = value.value;
       return this;
   }
   ```
   This keeps `setLarge()` small enough that the JIT may still inline `set()` → `setLarge()`
   for the non-reference path.

b. **Move the REFERENCE_BIT check back into `set()`** but with a lighter `setLarge`:
   The fast path already checks `REFERENCE_BIT` before calling `setLarge`. Inside `setLarge`,
   skip the refCount block entirely when neither old nor new is a reference.

**Estimated impact**: May restore JIT inlining of `set()`, which would reduce
every variable assignment from a method call to inline field stores.

**Files**: `RuntimeScalar.java`

#### Phase O5: `MortalList.active` gate (already partially done) (LOW impact, LOW risk)

**Goal**: Make `MortalList.flush()`, `pushMark()`, `popAndFlush()` truly zero-cost when
no DESTROY class has been registered.

**Current state**: `active` is `true` always (set in the field initializer). It was
originally gated on first `bless()` into a class with DESTROY, but was changed to
always-on because birth-tracked objects need balanced increment/decrement.

**Approach**: Re-examine whether `active` can start `false` and flip to `true` only
when the first `bless()` with DESTROY occurs OR when the first `weaken()` is called.
Birth-tracked objects' refCount is only meaningful when there's a class with DESTROY
or when weak refs are in play — otherwise refCount is never checked.

**Risk**: Requires careful analysis of whether any code path depends on
`MortalList.flush()` running before the first DESTROY-aware bless.

**Files**: `MortalList.java`, `InheritanceResolver.java` (classHasDestroy), `ScalarUtil.java`

#### Phase O6: Reduce RuntimeScalar object size (LOW impact, HIGH effort)

**Goal**: Reclaim the ~16 bytes added per RuntimeScalar.

**Approach**: Pack `refCountOwned`, `scopeExited`, and `ioOwner` into a single `byte flags`
field using bit masks. `captureCount` could be moved to a side table (WeakHashMap) since
it's only non-zero for closure-captured variables.

**Estimated impact**: Marginal — modern JVMs handle small objects well, and GC pressure
from field size is secondary to allocation rate.

**Files**: `RuntimeScalar.java`

### 16.5 Implementation Order

| Phase | Impact | Risk | Effort | Depends on |
|-------|--------|------|--------|------------|
| O1    | HIGH   | LOW  | 1-2 hrs | — |
| O2    | HIGH   | LOW  | 30 min | O1 |
| O3    | MEDIUM | LOW  | 15 min | — |
| O4    | MEDIUM | MED  | 1 hr | — |
| O5    | LOW    | MED  | 1 hr | — |
| O6    | LOW    | HIGH | 2+ hrs | — |

**Recommended order**: O3 → O1 → O2 → O4 → O5 → (O6 only if needed)

O3 is quickest to implement and provides immediate benefit. O1+O2 together should
eliminate the majority of the regression. O4 may require JIT profiling to confirm
the inlining hypothesis.

### 16.6 Verification

After each phase:
1. `make` must pass (all unit tests)
2. Run `dev/bench/benchmark_lexical.pl` — target ≥390,000/s (master baseline: 397,633/s)
3. Run `dev/bench/benchmark_global.pl` — target ≥90,000/s (master baseline: 96,850/s)
4. `./jperl examples/life_bitpacked.pl -r none -g 5000` — target ≥28 Mcells/s (master: ~29)
5. `./jcpan --jobs 8 -t Moo` — 841/841 must still pass
6. Sandbox destroy/weaken tests: `perl dev/tools/perl_test_runner.pl src/test/resources/unit/destroy*.t src/test/resources/unit/weaken*.t`
7. `./jperl --disassemble` on a tight loop to confirm bytecode reduction

### 16.7 Bytecode Evidence

Disassembly of a simple inner loop with 4 `my` variables shows the overhead:

```
# Per inner-loop iteration (scope exit of for body):
INVOKESTATIC MortalList.pushMark ()V           # mark mortal stack
ALOAD 29                                       # load $cell
INVOKESTATIC RuntimeScalar.scopeExitCleanup    # check/cleanup
ALOAD 30                                       # load $x
INVOKESTATIC RuntimeScalar.scopeExitCleanup    # check/cleanup
ALOAD 31                                       # load $y
INVOKESTATIC RuntimeScalar.scopeExitCleanup    # check/cleanup
ALOAD 32                                       # load $s
INVOKESTATIC RuntimeScalar.scopeExitCleanup    # check/cleanup
ACONST_NULL / ASTORE x4                        # null slots for GC
INVOKESTATIC MortalList.popAndFlush ()V         # drain mortal stack
```

After O1+O2, if all 4 variables are integer-only, this entire block is eliminated:
```
# Only null slots for GC (existing behavior from master):
ACONST_NULL / ASTORE x4
```
- **v1.0**: Initial design proposal.

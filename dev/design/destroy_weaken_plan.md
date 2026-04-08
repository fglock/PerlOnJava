# DESTROY and weaken() Implementation Plan

**Status**: Design Plan  
**Version**: 3.0  
**Created**: 2026-04-08  
**Updated**: 2026-04-08 (v3.0 — bytecode trace findings, revised refCount encoding)  
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
complemented by a GC safety net for escaped references.

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

**Lesson**: DESTROY must receive the *real* object, not a proxy. The Cleaner API can work
if we track a sentinel object instead of the referent directly (see Section 8).

---

## 3. Alternatives Considered

| # | Approach | Pros | Cons | Verdict |
|---|----------|------|------|---------|
| A | **Full refcounting on ALL objects** (like Perl 5) | Correct Perl semantics for everything | Massive perf impact — every scalar copy needs inc/dec; JVM has no stack-local optimization | Rejected: too expensive |
| B | **GC-only (Cleaner, no refcounting)** | Simple, no tracking overhead | Non-deterministic timing breaks tests; proxy problem (see §2.2); previously attempted and failed | Rejected: fundamentally wrong timing |
| C | **Scope-based without refcounting** (PR #450 style) | Simple, deterministic for single-scope | Wrong for returned objects, objects stored in outer scopes — 20+ failures (see §2.1) | Rejected: incorrect without refcount |
| D | **Compile-time escape analysis** | Zero runtime overhead for proven-local objects | Impossible to do perfectly (dynamic dispatch, eval, closures, `push @global, $obj`) | Rejected: too incomplete |
| E | **Explicit destructor registration** (`defer { $obj->cleanup }`) | Simple, deterministic, no refcounting | Not compatible with Perl 5 semantics; breaks existing modules | Rejected: not Perl-compatible |
| **F** | **Targeted refcounting for blessed-with-DESTROY + Cleaner safety net** | Deterministic for common cases; eventual for edge cases; zero overhead for unblessed | Complexity; may miss decrements in obscure paths | **Chosen** |

**Why F**: It's the only approach that provides correct timing for the common case (lexical
scope, explicit undef, hash delete) while still handling escaped references. The key insight
is that we don't need to refcount ALL objects — only the small subset that are blessed AND
whose class defines DESTROY. The existing `ioHolderCount` pattern on `RuntimeGlob` proves
this targeted approach works in this codebase.

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

### 4.5 Defer Collection Cleanup to GC Safety Net

Iterating arrays/hashes at scope exit is O(n) per collection. Instead of doing this
deterministically, rely on the Cleaner safety net (Section 8) for blessed refs inside
collections that go out of scope.

Deterministic DESTROY covers:
- Scalar lexicals going out of scope (`scopeExitCleanup`)
- `undef $obj` (explicit drop)
- `delete $hash{key}` (explicit removal)
- Scalar overwrite (`$obj = other_value`)

This handles the vast majority of real-world patterns. The remaining cases (blessed refs
stranded inside a collected array/hash) get eventual DESTROY via the Cleaner.

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

### 4.8 No Cleaner Overhead Until Needed

The Cleaner sentinel registration (Phase 4) adds overhead at `bless()` time: creating a
sentinel object, a lambda, and registering with the Cleaner thread. This is deferred to
a later phase. Phases 1-3 provide deterministic DESTROY without any Cleaner cost.

### 4.9 Memory Impact: Zero

Adding a single `int refCount` to `RuntimeBase`:

```
RuntimeScalar layout (current):              RuntimeScalar layout (proposed):
  Object header:          12 bytes             Object header:          12 bytes
  RuntimeBase.blessId:     4 bytes             RuntimeBase.blessId:     4 bytes
  RuntimeScalar.type:      4 bytes             RuntimeBase.refCount:    4 bytes  ← NEW
  RuntimeScalar.value:     4 bytes             RuntimeScalar.type:      4 bytes
  RuntimeScalar.ioOwner:   1 byte              RuntimeScalar.value:     4 bytes
  ─────────────────────────                    RuntimeScalar.ioOwner:   1 byte
  Total: 25 bytes → pad to 32                  ─────────────────────────
                                               Total: 29 bytes → pad to 32
```

**Zero additional memory cost** — the new field fits in existing alignment padding.
(Verified: RuntimeBase has only `blessId`; RuntimeScalar adds `type`, `value`, `ioOwner`.)

### 4.10 Optimization Summary

| Optimization | What it avoids | Cost |
|-------------|----------------|------|
| Four-state refCount | Separate `destroyCalled` field; overcounting from bless temp | One fewer field per object |
| Fast-path bypass | Any refcount work on int/double/string/undef | Zero — refs always take slow path |
| `refCount >= 0` gate | Tracking unblessed or no-DESTROY objects | One integer comparison |
| `destroyClasses` BitSet | DESTROY lookup on every bless() | One bit check per bless() |
| Defer collection cleanup | O(n) iteration at scope exit | Eventual via GC for collections |
| DESTROY method cache | Hierarchy traversal on every DESTROY call | One map lookup |
| No Cleaner until Phase 4 | Sentinel object + lambda + thread registration | Zero until Phase 4 |
| Post-assignment DESTROY | Incorrect variable state during DESTROY | One extra local variable |

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

### 4A.3 Impact Per Function Boundary

| Pattern | refCount at undef (v2.0, init=1) | refCount at undef (v3.0, init=0) | Deterministic? |
|---------|:---:|:---:|:---:|
| `{ my $o = Foo->new; }` | 1 (leak) | **0 → DESTROY** | ✓ v3.0 |
| `my $x = Foo->new; undef $x;` | 1 (leak) | **0 → DESTROY** | ✓ v3.0 |
| `my $x = make_obj(); undef $x;` | 2 (leak) | 1 (leak) | Cleaner needed |
| `my $x = wrapper(make_obj()); undef $x;` | 3 (leak) | 2 (leak) | Cleaner needed |

**Rule**: Objects created and consumed in the same scope or its direct caller get
deterministic DESTROY. Objects that cross 2+ function boundaries accumulate +1 overcounting
per boundary after the first. The Cleaner safety net (Phase 4) handles these cases.

### 4A.4 Why This Is Acceptable

The overwhelming majority of real-world DESTROY use cases are scope-based:
- **Scope guards** (`Guard`, `Scope::Guard`, `autodie::Scope::Guard`): object created
  and destroyed in the same scope → deterministic ✓
- **IO handles** (`IO::Handle`, `File::Temp`): typically `my $fh = IO::File->new(...)`
  → one boundary → deterministic ✓
- **POE wheels** (`delete $heap->{wheel}`): hash delete, no function boundary →
  deterministic ✓

The problematic pattern (returning objects through multiple wrappers) is less common and
is handled by the Cleaner with eventual timing.

### 4A.5 Future Mitigation: Clone-on-Return (Optional)

If the Cleaner latency proves problematic, deterministic DESTROY for returned objects can
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
        │         ├── [Phase 4] Register Cleaner sentinel
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
        │         │ (missed decrement)         │
        │         ▼                            ▼
        │   ┌──────────────┐           ┌──────────────┐
        │   │ Cleaner fires │──────────►│ Skip (already │
        │   │ (GC fallback) │ checks    │  MIN_VALUE)   │
        │   └──────────────┘ refCount  └──────────────┘
        │
        └── continue (no refcount tracking, no Cleaner)
```

**Key principles**:
- Deterministic DESTROY for single-boundary patterns (refcounting with init=0)
- Eventual DESTROY for multi-boundary escaped references (Cleaner, Phase 4)
- `refCount == Integer.MIN_VALUE` prevents double-DESTROY from either path
- Zero overhead for unblessed objects and blessed objects without DESTROY

---

## 6. Part 1: Reference Counting for Blessed Objects

### 6.1 The refCount Field

Add a single field to `RuntimeBase`:

```java
public abstract class RuntimeBase implements DynamicState, Iterable<RuntimeScalar> {
    public int blessId;             // existing: class identity
    public int refCount = -1;       // NEW: four-state lifecycle counter (-1 = untracked)
}
```

### 6.2 Refcount Tracking Points

#### Increment (store a tracked reference)

| Location | Code path |
|----------|-----------|
| Scalar assignment | `RuntimeScalar.setLarge()` — new value has `refCount >= 0` |
| Hash element store | Via `RuntimeScalar.set()` on the element → `setLarge()` |
| Array element store | Via `RuntimeScalar.set()` on the element → `setLarge()` |

#### Decrement (drop a tracked reference)

| Trigger | Code path |
|---------|-----------|
| Scalar overwrite | `RuntimeScalar.setLarge()` — old value has `refCount > 0` |
| `undef $obj` | `RuntimeScalar.undefine()` |
| `delete $hash{key}` | `RuntimeHash.delete()` |
| Scope exit (scalar lexicals) | `RuntimeScalar.scopeExitCleanup()` |

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
        // Re-bless: update class, keep refCount, update Cleaner
        referent.setBlessId(newBlessId);
        DestroyDispatch.updateCleaner(referent, str);  // Phase 4
    } else {
        // First bless (or previously untracked)
        referent.setBlessId(newBlessId);
        if (DestroyDispatch.classHasDestroy(newBlessId, str)) {
            referent.refCount = 0;  // Start tracking (zero containers counted)
            DestroyDispatch.registerCleaner(referent, str);  // Phase 4
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

### 6.5 Edge Case: Pre-bless Copies

```perl
my $hashref = {};
my $copy = $hashref;     # copy exists BEFORE blessing
bless $hashref, 'Foo';   # refCount set to 0, but there are 2 containers
```

The `$copy` was stored before `blessId` was set, so `refCount >= 0` was false at that time
and no increment occurred. `refCount` undercounts by the number of pre-bless copies.

**Impact**: DESTROY may fire while `$copy` still references the object.  
**Mitigation**: The GC safety net (Phase 4) provides eventual correctness.  
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

Weak ref tracking uses external maps to avoid memory overhead on every RuntimeScalar:

```java
public class WeakRefRegistry {
    // Forward map: is this RuntimeScalar a weak ref?
    private static final Set<RuntimeScalar> weakScalars =
        Collections.newSetFromMap(new IdentityHashMap<>());

    // Reverse map: referent → set of weak RuntimeScalars pointing to it
    private static final IdentityHashMap<RuntimeBase, Set<RuntimeScalar>> referentToWeakRefs =
        new IdentityHashMap<>();

    public static void weaken(RuntimeScalar ref) {
        if (!RuntimeScalarType.isReference(ref.type)) return;
        if (!(ref.value instanceof RuntimeBase base)) return;
        if (weakScalars.contains(ref)) return;  // already weak

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

When `refCount` reaches 0, before calling DESTROY:

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

The "becomes undef when strong refs gone" behavior for unblessed weak refs uses
`java.lang.ref.WeakReference<Object>` internally:

```java
// In weaken() for untracked referents (refCount == -1):
ref.value = new WeakReferenceWrapper(ref.value);
// On dereference, if WeakReference.get() returns null → set to undef
```

This provides non-deterministic but correct "becomes undef" behavior via GC.

---

## 8. Part 3: GC Safety Net (Cleaner Sentinel Pattern)

### 8.1 Why We Need a Safety Net

Targeted refcounting misses some cases:
- Objects stored in pre-bless copies (refCount undercounted)
- Objects passing through multiple function-return boundaries (refCount may overcount by +1 per boundary — mitigated by `refCount=0` at bless, but not fully eliminated for deeply nested returns)
- Blessed refs stranded in arrays/hashes that go out of scope without deterministic cleanup

The Cleaner provides eventual correctness: if refCount never reaches 0,
DESTROY still fires when the object becomes GC-unreachable.

### 8.2 The Sentinel Pattern

The Cleaner API constraint: the cleaning action must NOT hold a strong reference
to the registered object. We solve this with a sentinel indirection:

```
RuntimeScalar ──► RuntimeHash (referent)
                      │
                      └──► DestroyTrigger (companion object)
                               │
                               └──► sentinel (Object)
                                       │
                                    registered with Cleaner
                                       │
                                    cleaning action holds ──► DestroyTrigger
                                    (does NOT hold sentinel)
```

When all user-level RuntimeScalars pointing to the referent are GC'd:
1. RuntimeHash becomes unreachable from user code
2. DestroyTrigger becomes unreachable
3. `sentinel` becomes unreachable
4. Cleaner detects sentinel is phantom-reachable, fires the action
5. Action accesses DestroyTrigger → referent → calls DESTROY with the REAL object
6. After DESTROY, Cleaner releases the action → trigger → referent can be GC'd

**This avoids the proxy problem**: DESTROY receives the actual object, not a reconstruction.

### 8.3 Implementation

```java
public class DestroyDispatch {
    private static final Cleaner cleaner = Cleaner.create();

    static class DestroyTrigger {
        final RuntimeBase referent;
        String className;          // mutable for re-bless
        Object sentinel;
        Cleaner.Cleanable cleanable;

        DestroyTrigger(RuntimeBase referent, String className) {
            this.referent = referent;
            this.className = className;
        }
    }

    private static final IdentityHashMap<RuntimeBase, DestroyTrigger> triggers =
        new IdentityHashMap<>();

    public static void registerCleaner(RuntimeBase referent, String className) {
        DestroyTrigger trigger = new DestroyTrigger(referent, className);
        trigger.sentinel = new Object();
        triggers.put(referent, trigger);

        trigger.cleanable = cleaner.register(trigger.sentinel, () -> {
            if (trigger.referent.refCount != Integer.MIN_VALUE) {
                trigger.referent.refCount = Integer.MIN_VALUE;
                WeakRefRegistry.clearWeakRefsTo(trigger.referent);
                doCallDestroy(trigger.referent, trigger.className);
            }
        });
    }

    public static void cancelCleaner(RuntimeBase referent) {
        DestroyTrigger trigger = triggers.remove(referent);
        if (trigger != null && trigger.cleanable != null) {
            trigger.cleanable.clean();
        }
    }
}
```

### 8.4 Coordination Between Refcount and Cleaner

The `refCount == Integer.MIN_VALUE` state is the single coordination point:

| Path | What happens |
|------|--------------|
| Refcount → 0 | Set `MIN_VALUE`, call DESTROY, cancel Cleaner |
| Cleaner fires | Check `refCount != MIN_VALUE`; if true, set `MIN_VALUE` and call DESTROY; if false, skip |

No separate `destroyCalled` flag needed.

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

    // Cancel Cleaner registration (if Phase 4 active)
    cancelCleaner(referent);

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
        self.type = /* appropriate reference type for the referent */;
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

```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    GlobalContext.globalPhase = "DESTRUCT";
    // Walk all package variables containing blessed refs
    // Call DESTROY on each (order is unpredictable, matching Perl)
    for (Map.Entry<String, RuntimeScalar> entry : GlobalVariable.getAllGlobals()) {
        RuntimeScalar val = entry.getValue();
        if ((val.type & REFERENCE_BIT) != 0
                && val.value instanceof RuntimeBase base
                && base.refCount >= 0) {
            base.refCount = Integer.MIN_VALUE;
            DestroyDispatch.callDestroy(base);
        }
    }
}));
```

---

## 11. Implementation Phases

### Phase 1: Infrastructure (2-4 hours)

**Goal**: Add `refCount` field, create `DestroyDispatch` class. No behavior change.

- Add `int refCount` to `RuntimeBase` (default -1 = untracked)
- Create `DestroyDispatch.java` with `callDestroy()`, `doCallDestroy()`, `classHasDestroy()`
- Create `destroyClasses` BitSet and `destroyMethodCache`
- Hook `InheritanceResolver.invalidateCache()` to clear both caches

**Files**: `RuntimeBase.java`, `DestroyDispatch.java` (NEW), `InheritanceResolver.java`  
**Validation**: `make` passes. No behavior change.

### Phase 2: Scalar Refcounting + DESTROY (4-8 hours)

**Goal**: DESTROY works for the common case — single lexical, undef, hash delete.

- Hook `RuntimeScalar.setLarge()` — increment/decrement for `refCount >= 0`
- Hook `RuntimeScalar.undefine()` — decrement
- Hook `RuntimeScalar.scopeExitCleanup()` — decrement
- Hook `RuntimeHash.delete()` — decrement on removed blessed values
- Initialize `refCount = 0` in `ReferenceOperators.bless()` for DESTROY classes
- Handle re-bless (don't reset refCount)

**Files**: `RuntimeScalar.java`, `ReferenceOperators.java`, `RuntimeHash.java`  
**Validation**: `make` passes + `destroy.t` unit test passes.

### Phase 3: weaken/isweak/unweaken (4-8 hours)

**Goal**: Weak reference functions return correct results.

- Create `WeakRefRegistry.java` with forward/reverse maps
- Implement `weaken()`, `isweak()`, `unweaken()` with refCount interaction
- Update `ScalarUtil.java` and `Builtin.java` to call `WeakRefRegistry`
- Add `clearWeakRefsTo()` call in `DestroyDispatch.callDestroy()`

**Files**: `WeakRefRegistry.java` (NEW), `ScalarUtil.java`, `Builtin.java`, `DestroyDispatch.java`  
**Validation**: `make` passes + `weaken.t` unit test passes.

### Phase 4: GC Safety Net (4-8 hours)

**Goal**: Objects that escape scope tracking are eventually DESTROY'd.

- Implement Cleaner sentinel pattern in `DestroyDispatch`
- Register at `bless()` time for DESTROY classes
- Cancel on deterministic DESTROY
- Fire on GC for escaped references

**Files**: `DestroyDispatch.java`, `ReferenceOperators.java`  
**Validation**: Escaped-reference test passes.

### Phase 5: Global Destruction + Polish (4-8 hours)

**Goal**: Complete lifecycle support.

- Implement `${^GLOBAL_PHASE}` with DESTRUCT value
- Add JVM shutdown hook
- `Devel::GlobalDestruction` compatibility
- Protect global variables (`$@`, `$!`, `$?`, etc.) in DESTROY calls
- AUTOLOAD fallback for DESTROY

**Files**: `GlobalContext.java`, `Main.java`, `DestroyDispatch.java`  
**Validation**: Global destruction test passes.

### Phase 6: Collection Cleanup (optional, 4-8 hours)

**Goal**: Deterministic DESTROY for blessed refs in lexical arrays/hashes at scope exit.

- Add `boolean containsTrackedRef` to `RuntimeArray`/`RuntimeHash`
- Set flag when a `refCount >= 0` element is stored
- Add `scopeExitCleanup(RuntimeArray)` and `scopeExitCleanup(RuntimeHash)`
- Extend `emitScopeExitNullStores()` to call cleanup on array/hash lexicals

**Files**: `RuntimeArray.java`, `RuntimeHash.java`, `EmitStatement.java`  
**Validation**: Collection-DESTROY test passes. No performance regression.

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
| **Missed decrement point** — a code path drops a blessed ref without decrementing | DESTROY never fires (leak) | Medium | Cleaner safety net (Phase 4) catches it; audit all assignment/drop paths |
| **Overcounting from temporaries** — function returns create transient RuntimeScalars that increment but don't decrement | DESTROY delayed until Cleaner fires | Medium | Acceptable — Cleaner provides eventual correctness |
| **Performance regression** — refCount checks slow the critical path | Throughput drop | Low | Fast-path bypass; `refCount >= 0` gate skips 99% of refs; benchmark before/after |
| **Interference with IO lifecycle** — refCount decrement triggers premature DESTROY on IO-blessed objects | IO corruption | Low | Test IO::Handle, File::Temp explicitly; separate code paths for IO vs DESTROY |
| **Cleaner thread context** — DESTROY called from daemon thread has different context | Thread-local state mismatch, unexpected behavior | Low | Document; most DESTROY methods don't depend on thread-local state |
| **Double-DESTROY from race** — refcount path and Cleaner fire concurrently | Duplicate side effects | Low | `Integer.MIN_VALUE` is set atomically (single int write); consider `volatile` |
| **Existing test regressions** — refCount logic has a bug that breaks existing tests | Build failure | Medium | Phase 1 adds field only (no behavior change); Phase 2 is independently testable; run `make` after every change |

### Rollback Plan

Each phase is independently revertable:
- Phase 1: Remove `refCount` field (no behavior change to revert)
- Phase 2: Remove hooks in `setLarge()`/`undefine()`/`scopeExitCleanup()` and bless()
- Phase 3: Revert `ScalarUtil.java` to stubs, remove `WeakRefRegistry.java`
- Phase 4+: Remove Cleaner registration

If the whole approach fails, close PR #450 and document findings for future reference.

---

## 14. Known Limitations

1. **Pre-bless copies are undercounted**: References copied before `bless()` don't get
   counted. DESTROY may fire while those copies still exist. Mitigated by Cleaner safety net.

2. **Temporary RuntimeScalar overcounting (mostly mitigated)**: With `refCount=0` at bless
   time, single-boundary returns (the common case) work correctly — the bless-time temporary
   is not counted. Multi-boundary returns (deeply nested helper chains) may still overcount
   by +1 per extra boundary. The Cleaner safety net handles these cases.

3. **Blessed refs in collections**: Without Phase 6, blessed refs inside lexical arrays/hashes
   that go out of scope get non-deterministic DESTROY timing (Cleaner handles it).

4. **Circular references without weaken()**: refCounts never reach 0. DESTROY fires at global
   destruction (shutdown hook) or via Cleaner when the cycle becomes unreachable.

5. **Non-deterministic timing for Cleaner-path DESTROY**: Depends on GC pressure. Tests that
   assert immediate DESTROY timing for escaped objects will fail.

6. **DESTROY from Cleaner runs on daemon thread**: Different thread context than main Perl
   execution. Could cause issues with thread-local state, though this is rare in practice.

7. **`Internals::SvREFCNT` remains inaccurate**: Returns 1 (constant). Real refCount is only
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
| Escaped refs eventually destroyed | 4 | Test with `System.gc()` hint |
| Global destruction fires for package vars | 5 | `${^GLOBAL_PHASE}` test |

---

## 16. Files to Modify (Complete List)

### New Files
| File | Phase | Purpose |
|------|-------|---------|
| `DestroyDispatch.java` | 1 | Central DESTROY logic, caching, Cleaner registration |
| `WeakRefRegistry.java` | 3 | External registry for weak references |
| `src/test/resources/unit/destroy.t` | 2 | DESTROY unit tests |
| `src/test/resources/unit/weaken.t` | 3 | Weak reference unit tests |

### Modified Files
| File | Phase | Changes |
|------|-------|---------|
| `RuntimeBase.java` | 1 | Add `int refCount` field |
| `InheritanceResolver.java` | 1 | Cache invalidation hook for `destroyClasses`/`destroyMethodCache` |
| `RuntimeScalar.java` | 2 | Hook `setLarge()`, `undefine()`, `scopeExitCleanup()` |
| `ReferenceOperators.java` | 2 | Initialize refCount in `bless()`, handle re-bless |
| `RuntimeHash.java` | 2 | Hook `delete()` for refcount decrement |
| `ScalarUtil.java` | 3 | Replace `weaken`/`isweak`/`unweaken` stubs |
| `Builtin.java` | 3 | Update `builtin::weaken`, `builtin::is_weak`, `builtin::unweaken` |
| `GlobalVariable.java` | 5 | `${^GLOBAL_PHASE}` support |
| `Main.java` | 5 | Global destruction shutdown hook |
| `RuntimeArray.java` | 6 | Optional: `containsTrackedRef` flag, scope-exit cleanup |
| `EmitStatement.java` | 6 | Optional: emit cleanup calls for array/hash lexicals |

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
- Without `weaken()`: DESTROY fires at global destruction (shutdown hook) or via Cleaner
- With `weaken()`: the weak link doesn't count, so the cycle breaks correctly

### Re-bless to Different Class
```perl
bless $obj, 'Foo';  # Foo has DESTROY — refCount = 0 (at bless time)
bless $obj, 'Bar';  # Bar has no DESTROY
```
On re-bless: if new class has no DESTROY, set `refCount = -1` (stop tracking) and cancel
Cleaner. If new class has DESTROY, keep refCount, update Cleaner class name.

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
   - Cleaner runs on a daemon thread. `refCount` could be decremented concurrently.
   - `int` writes are atomic on JVM, but compound read-modify-write (`--refCount`) is not.
   - **Recommendation**: Use `volatile` for visibility. If races appear, upgrade to
     `AtomicInteger` (but only for tracked objects — use a wrapper or field on DestroyTrigger).

2. **Should we track refCount for ALL blessed objects or only DESTROY classes?**
   - Tracking all blessed: simpler, but overhead for classes without DESTROY.
   - Tracking only DESTROY classes: faster, but needs cache invalidation on method changes.
   - **Recommendation**: Only DESTROY classes (using `destroyClasses` BitSet).

3. **Should Phase 6 (collection cleanup) be implemented?**
   - Without it, blessed refs in collections get non-deterministic DESTROY.
   - The `containsTrackedRef` flag makes it cheap for the common case.
   - **Recommendation**: Defer to Phase 6. Implement only if real-world modules need it.

---

## 19. References

- Perl `perlobj` DESTROY documentation: https://perldoc.perl.org/perlobj#Destructors
- Java Cleaner API: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ref/Cleaner.html
- PR #450 (WIP): https://github.com/fglock/PerlOnJava/pull/450
- `dev/modules/poe.md` — DestroyManager attempt and lessons
- `dev/design/object_lifecycle.md` — earlier design proposal
- Jython FinalizeTrigger pattern: companion object with `finalize()`
- JRuby Cleaner migration: https://github.com/jruby/jruby/issues/8328

---

## Progress Tracking

### Current Status: Not started

### Completed Phases
- (none)

### Next Steps
1. Implement Phase 1 (Infrastructure)
2. Implement Phase 2 (Scalar Refcounting + DESTROY)
3. Validate with `make` and `destroy.t` unit test

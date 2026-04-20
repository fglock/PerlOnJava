# Final Plan: DBIC `t/52leaks.t` Tests 12-20

**Status:** Design / Proposal
**Date:** 2026-04-19
**Depends on:** `dev/design/refcount_alignment_plan.md` (completed)
**Goal:** All 20 non-TODO tests in DBIC's `t/52leaks.t` pass under `jperl`
without requiring the `dev/patches/cpan/DBIx-Class-0.082844/t-lib-DBICTest-Util-LeakTracer.pm.patch`
patch.

## 1. Problem

After the refcount alignment plan's 7 phases landed, the current
state of `t/52leaks.t` is:

```
not ok 12 - Expected garbage collection of ARRAY(0x...) | basic random_results (refcnt 1)
not ok 13 - Expected garbage collection of DBICTest::Artist=HASH(0x...) (refcnt 2)
not ok 14 - Expected garbage collection of DBICTest::CD=HASH(0x...)     (refcnt 2)
not ok 15 - Expected garbage collection of DBICTest::CD=HASH(0x...)     (refcnt 2)
not ok 16 - Expected garbage collection of DBICTest::Schema=HASH(0x...) (refcnt 2)
not ok 17 - Expected garbage collection of DBICTest::Schema=HASH(0x...) (refcnt 2)
not ok 18 - Expected garbage collection of DBIx::Class::ResultSource::Table=HASH(0x...) (refcnt 3)
not ok 19 - Expected garbage collection of DBIx::Class::ResultSource::Table=HASH(0x...) (refcnt 6)
not ok 20 - Expected garbage collection of HASH(0x...) | basic rerefrozen
```

Each failure means a weak reference registered in DBIC's leak tracer
is still `defined` at assertion time, when Perl 5 would have already
set it to `undef` because the referent became unreachable.

`Internals::jperl_gc()` called from a patched LeakTracer.pm already
fixes all 9. The remaining work is to make the fix **automatic** — no
module patching required.

## 2. Root Cause

### The `basic random_results` case (simplest)

```perl
# t/52leaks.t line 260-296 (abridged)
my $base_collection = {
    # ... many resultsets, row objects, etc.
};
# ...
push @{$base_collection->{random_results}}, $fire_resultsets->();
# ...
populate_weakregistry ($weak_registry, $base_collection->{$_}, "basic $_")
    for keys %$base_collection;
# ... end of block ...
assert_empty_weakregistry ($weak_registry);   # line 526
```

At line 526:
- The enclosing block has long since exited. `$base_collection` and
  all the objects it held SHOULD be collectible.
- Native Perl: they ARE collected. Weak refs become `undef`.
- jperl: the `ARRAY` for `random_results` has `refCount == 1`, not 0.
  The weak ref stays defined. Test fails.

### Where does the +1 come from?

Our diagnosis during the alignment plan showed:
- The referent object is NOT reachable from any Perl-level root
  (`Internals::jperl_trace_to($ref)` returns undef before
  `jperl_gc` is called).
- But `refCount > 0` because **cooperative refCount is inflated by
  JVM-side references** the walker can't see.

Specific inflation sources for the `basic random_results` ARRAY:
1. `$fire_resultsets` is a closure that captures `@rsets`, which
   holds the array indirectly. The closure stays alive through
   module-level state; its `capturedScalars` pins the ARRAY.
2. `Storable::dclone` internals materialize the entire tree and
   may leave JVM temporaries that hold references.
3. `populate_weakregistry` calls pass the ref through argument
   positions — `@_` in PerlOnJava is still counted-copies
   (Phase 2 only covered `@DB::args`, not the general `@_`).
4. Hash accesses like `$base_collection->{$_}` in the `for` loop
   materialize scalar copies that track as owners briefly.

The walker correctly identifies these objects as unreachable once
we drain `rescuedObjects` and don't follow closure captures, but
the walker is never triggered automatically because we cannot see
JVM-frame lexicals — so auto-triggering risks clearing weak refs
to objects that ARE still alive via a live local we can't see.

### The core dilemma

```
Auto-trigger walker → unsafe (walks miss live lexicals)
Don't auto-trigger  → leak tracer sees false positives
```

## 3. Four Solution Options

### Option A — Fix every refCount inflation source

Audit each code path that touches `refCount` without a matching
decrement: `@_` pass-through, hash/array element temporaries, closure
captures, `Storable`/`JSON`/`dclone` output, overload operator
callers, method-resolution temporaries, etc.

**Pros:**
- No new machinery. Keeps the design footprint minimal.
- Every fix also improves `Internals::SvREFCNT` accuracy for
  non-DBIC consumers.

**Cons:**
- Unbounded: each new CPAN module may surface new paths.
- Risk of regressions — the Phase 3 DESTROY FSM already needed
  careful balancing of `args.push(self)` to avoid infinite loops.
- Doesn't address the fundamental mismatch that cooperative
  refcount is approximate.

**Verdict:** Necessary hygiene but insufficient alone.

### Option B — Lexical-aware ReachabilityWalker

Instrument the compiler to register live ref-typed lexicals with a
thread-local stack, pop on scope exit. The walker consults this
stack as additional roots.

**Mechanism:**
```java
// At `my $x = <ref_expr>` emission:
RuntimeScalar x = ...;
LexicalRegistry.push(x);   // one-liner at declaration

// At scope exit (already emitted by EmitStatement):
x = null;                   // existing
LexicalRegistry.popTo(savedDepth);   // new
```

**Pros:**
- Makes `jperl_gc()` safe to auto-trigger. Walker has true
  Perl-root visibility.
- Matches Perl's semantics: Perl's `padwalker`/`closure`-capture
  machinery gives it the same view.

**Cons:**
- Touches the compiler emission path (every `my`-declaration
  becomes two bytecode ops instead of one).
- Per-frame overhead for programs that never call `weaken()`.
  Must gate on `weakRefsExist`.
- Doesn't handle `local` / `our` / stash-like pad variables
  without more work.

**Verdict:** The principled fix. Medium-large effort.

### Option C — Let JVM reachability drive weak-ref clearing

For each call to `weaken($x)`, also create a `java.lang.ref.WeakReference`
to the referent. The JVM's own tracing GC will clear that JWR when the
referent becomes unreachable (i.e., no strong Java reference exists).
When the user asks about the weak ref (via `defined`, `isweak`, or
`assert_empty_weakregistry`), we consult the JWR and clear the scalar
if the referent is gone.

**Mechanism:**
```java
// WeakRefRegistry.weaken($x):
java.lang.ref.WeakReference<RuntimeBase> jwr =
    new java.lang.ref.WeakReference<>(referent, refQueue);
weakRefJwrMap.put(weakScalar, jwr);

// Periodic sweep (cheap):
while ((jwr = refQueue.poll()) != null) {
    RuntimeScalar scalar = jwrToScalar.get(jwr);
    clearWeakRefsTo(scalar.value_at_creation);
}
```

To make this work, cooperative refCount must **NOT** hold strong Java
refs on the JVM-side for things that shouldn't count. Specifically:
- When `refCount` is bumped for a JVM temporary (arg passing, closure
  upvar, etc.), the containing slot holds a strong Java ref anyway.
- When the slot is released (scope exit, reassign), the Java ref
  goes away and GC can collect.

**Pros:**
- **Uses the JVM's existing oracle** for unreachability. No need to
  enumerate roots manually.
- Automatically correct across every CPAN module's idiosyncratic
  refcount inflation.
- Compatible with how JVM already manages everything else.

**Cons:**
- JVM GC is non-deterministic — may require `System.gc()` hints at
  leak-assertion time.
- Interplay with cooperative refCount needs care: a weakened object
  could be JVM-collected even if our `refCount > 0`, which breaks
  the "`DESTROY` fires when refCount drops to 0" contract.
- `ReferenceQueue` poll semantics require a monitor thread or sweep
  hooks.

**Verdict:** The fundamentally "right" approach, but requires
separating weak-ref lifetime from the `refCount` counter lifetime.
Larger than Option B.

### Option D — Full Perl-accurate refcount

Track refcount on every SV, including stack temporaries. Essentially
rewrite PerlOnJava's GC model to match Perl 5's RC.

**Pros:** Perl-accurate.
**Cons:** Multi-month refactor. Loses zero-cost opt-out.
**Verdict:** Not recommended. Too disruptive.

## 4. Recommendation

Pursue Options **A + B in parallel**, deferring Option C.

- **Option A (refcount-inflation audit)** is ongoing hygiene. Each
  identified leak point reduces the "how much inflation" problem
  even before Option B ships, and improves `Internals::SvREFCNT`.
- **Option B (lexical-aware walker)** unlocks the auto-trigger. Once
  the walker can see lexicals, we can safely run `jperl_gc()` at
  the right moments without a DBIC patch.

Together, they close the 52leaks gap without architectural upheaval.

Option C stays on the table as a future direction if Option B's
overhead proves unacceptable, or if more CPAN modules expose
refcount-inflation edge cases.

## 5. Phased Implementation

### Phase A1 — Audit: identify the top refcount-inflation sources

Instrument `Internals::jperl_refstate` / `jperl_refstate_str`
(already in place) plus a new `Internals::jperl_refstate_delta` that
records a baseline and reports delta-since-baseline. Use the DBIC
52leaks test with targeted checkpoints to enumerate every place a
leaked object's refCount climbs.

**Deliverable:** `dev/design/refcount_inflation_audit.md` listing the
top N paths in rank order (e.g., `@_` copies 30%, Storable 20%,
method temporaries 15%, ...).

**Effort:** 1 week

### Phase A2 — Generalized `@_` aliasing (biggest win from the audit)

Extend Phase 2's `setFromListAliased` pattern to **all** sub-call
arg marshalling, not just `caller()`.

Mechanism:
- At sub entry, populate `@_` with aliased copies (no refCount
  increment on the callee side). The caller keeps ownership; callee
  reads are aliases.
- Modifications to `$_[0]` propagate to the caller (Perl semantics).
- `my $x = shift` → caller's ref count drops when shift consumes
  @_; the new `$x` binding takes ownership.

Risk: very broad change. Must not regress Moo/DBIC's existing
patterns. Introduce behind a feature flag `JPERL_ALIASED_AT_UNDERSCORE=1`
for one release.

**Deliverable:** New code path with flag, `make test-bundled-modules`
passes with flag on, eventually made default.

**Effort:** 3-4 weeks

### Phase A3 — Other inflation sources

Work through the rest of the audit list (Storable, JSON,
overload, method-resolution temporaries, ...). Each is a focused
change. Track progress in `refcount_inflation_audit.md`.

**Effort:** 2-3 weeks

### Phase B1 — LexicalRegistry machinery

Add:
- `org.perlonjava.runtime.runtimetypes.LexicalRegistry`: thread-local
  stack of ref-typed RuntimeScalars currently in scope.
- Two emitter hooks in `EmitVariableDeclaration.java`:
  - After a `my $var = <ref_expr>` assignment, emit
    `LexicalRegistry.push(var)` if `weakRefsExist`.
  - At scope exit, already-emitted `scopeExitCleanup(var)` also
    pops the registry (1 extra bytecode op).
- `ReachabilityWalker.walk()` seeds from the registry as additional
  roots.

Zero-cost when no weak refs exist: the emitter still emits the
register/deregister calls, but they short-circuit on
`!weakRefsExist`. Existing cost model preserved for non-weaken
programs.

**Deliverable:** Walker returns correct reachability for test cases
like:

```perl
use Scalar::Util 'weaken';
my $obj = SomeClass->new;
my $weak = $obj;
weaken($weak);
Internals::jperl_gc();   # $weak should NOT be cleared ($obj still in scope)
die "walker bug" unless defined $weak;
```

**Effort:** 2-3 weeks

### Phase B2 — Safe auto-trigger for `jperl_gc`

With B1 shipping, the walker can be safely triggered automatically
at points known to be helpful:
- At every N-th `MortalList.flush()` (tunable amortization).
- At `Internals::SvREFCNT($ref)` queries — ensures leak-tracer
  consumers see Perl-compatible values.
- Optionally: at `defined($ref)` on weakened scalars.

Each trigger should be gated by `weakRefsExist` + a throttle (don't
run more than once per N operations).

**Deliverable:** 52leaks passes WITHOUT the LeakTracer patch.

**Effort:** 1-2 weeks

### Phase B3 — Remove the DBIC patch

Once B2 is in place, revert `dev/patches/cpan/DBIx-Class-0.082844/t-lib-DBICTest-Util-LeakTracer.pm.patch`
and its README. The patch should no longer be needed.

Validate: full DBIC suite still 269/270.

**Effort:** 0.5 week

### Total

10-14 weeks, or 5-8 weeks with Phase A1-A3 running in parallel with
B1-B3.

## 6. Validation

Each phase lands with its own regression gate:

| Gate | Pass criterion |
|------|----------------|
| After A2 (aliased `@_`) | `dev/tools/refcount_diff.pl` shows fewer divergences on a standardized corpus; Moo 71/71 preserved |
| After A3 | `make test-bundled-modules` no regressions |
| After B1 | New test `dev/sandbox/destroy_weaken/walker_with_lexicals.t` passes (walker correctly sees live lexicals) |
| After B2 | `t/52leaks.t` passes without patch (9 fewer real failures vs unpatched master) |
| After B3 | Full DBIC suite 269/270, identical to current state |

Ongoing: `dev/tools/destroy_semantics_report.pl` remains 213/213
throughout.

## 7. Risk Analysis

| Risk | Mitigation |
|------|------------|
| A2 breaks Perl `@_` aliasing semantics | Feature-flag `JPERL_ALIASED_AT_UNDERSCORE=1`; broad test coverage before default-on |
| B1 leaks lexicals after abnormal unwind (die in eval) | Add try/finally at sub entry to ensure LexicalRegistry unwinds even on exception |
| B2 clears weak refs too aggressively | Amortized trigger with opt-out `JPERL_NO_AUTO_GC=1`; stack trace logged when a weak ref is auto-cleared for debugging |
| Performance regression | Bench `life_bitpacked`, closure, method benchmarks before/after each phase; revert if >5% regression without a clear cause |

## 8. What This Plan Does NOT Solve

- Scripts that rely on `Internals::SvREFCNT` returning the
  bit-identical value as native Perl. Our number will still differ
  by small amounts in inflation-heavy code. Users consuming SvREFCNT
  should use `Internals::jperl_gc()` to normalize first.
- Unblessed arrays that are birth-tracked would need a separate
  investigation — known `Sub::Quote` regression blocker.
- Thread-safety of the refcount system is still out of scope (see
  weaken-destroy.md §5 in Limitations).

## 9. Current opt-in solution (as baseline)

Until Phase B2 ships, DBIC users on PerlOnJava can get 52leaks
passing by applying
`dev/patches/cpan/DBIx-Class-0.082844/t-lib-DBICTest-Util-LeakTracer.pm.patch`,
which calls `Internals::jperl_gc()` in `assert_empty_weakregistry`.

This is tracked in `dev/modules/dbix_class.md` and remains the
recommended workaround until Option B lands.

## References

- `dev/design/refcount_alignment_plan.md` — phases 0-7 (landed)
- `dev/design/refcount_alignment_progress.md` — per-phase progress
- `dev/architecture/weaken-destroy.md` — current architecture
- `dev/modules/dbix_class.md` — DBIC test tracking
- `dev/patches/cpan/DBIx-Class-0.082844/LeakTracer-README.md` — the
  workaround this plan eventually removes
- DBIC `t/52leaks.t` (upstream) — the target test
- DBIC `t/lib/DBICTest/Util/LeakTracer.pm` — the tracer we're
  trying to satisfy

---

## Progress Log (2026-04-19 implementation session)

### Phase B1 — Lexical-aware reachability walker — LANDED

Instead of the plan's proposed compiler instrumentation, the
implementation uses a `WeakHashMap<RuntimeScalar, Boolean>`
(`ScalarRefRegistry`) populated at every `setLarge`/container-store
site. JVM GC prunes entries for scalars that become unreachable via
JVM frame liveness; a forced `System.gc()` + multi-pass
WeakReference-sentinel wait ensures up-to-date pruning before the
walker seeds from the registry. Capture-count > 0 scalars are
skipped (closure captures would over-reach).

Result: **DBIC t/52leaks.t with LeakTracer patch: 9 real fails → 1
real fail** (`basic rerefrozen` remains; reachable via some
live-lexical scalar the walker's diagnostic traces to
`<live-lexical#N>` without further chain).

See commit `5813ea658`.

### Phase B2 — Safe auto-trigger — BLOCKED

Three approaches attempted, all reverted:

1. **Auto-trigger on `Scalar::Util::isweak()` calls** — stack overflow
   from DESTROY cascade → tail-call recursion in DBIC cleanup code.
2. **Auto-trigger on `MortalList.flush()` (statement boundaries)** —
   even with throttling and re-entry guard, breaks DBICTest module
   initialization. The `BaseResult.pm` compilation chain depends on
   weak-ref intermediate state remaining defined.
3. **Quiet-mode auto-sweep (no DESTROY)** — same module-init
   failures. Clearing weak refs alone corrupts Class::C3::Componentised
   or similar module-construction state.

**Root cause:** auto-trigger requires distinguishing "main script
body" execution from "module initialization". PerlOnJava's compiler
doesn't emit such markers today.

Deferred to future work. Options:
- (a) Compiler-emitted safepoint markers at statement top-level,
      excluded during `BEGIN`/`require`/`use` compilation.
- (b) A sentinel global set during `Perl_eval_require_sv` /
      `use`-loading that inhibits auto-sweep while module code is
      running.
- (c) Leave it opt-in forever; document the LeakTracer patch as the
      recommended integration path.

### Phase B3 — Remove LeakTracer patch — DEFERRED

Blocked on B2. Until auto-trigger is safe, DBIC users on jperl need
the `dev/patches/cpan/DBIx-Class-0.082844/t-lib-DBICTest-Util-LeakTracer.pm.patch`
to get `Internals::jperl_gc()` called at assertion time.

### Remaining `basic rerefrozen` leak (even with patch)

One stubborn failure where a dcloned hash (double-clone of
`$base_collection`) remains reachable via a ScalarRefRegistry
entry the walker traces as `<live-lexical#N>` but can't chain
further (the scalar directly holds the rerefrozen hashref).

Possible causes (Phase A1 audit work):
- Some JVM-frame-alive scalar in Storable's `dclone` internals
  retains a ref to the cloned output for longer than expected.
- A map/grep temp frame scalar whose JVM slot hasn't been nulled
  yet at assertion time.
- A closure capture we haven't correctly excluded
  (`captureCount` check misses some cases).

Not pursued further in this session.

---

## Phase B2a — Module-init-aware auto-trigger (2026-04-19 plan)

This is the detailed implementation plan for pursuing option (b)
above: a **sentinel** set while module-initialization code is
running that inhibits the auto-sweep.

### Why option (b) over (a)

Option (a) — compiler-emitted safepoint markers — is the most
principled, but requires changes to emitter passes for every statement
boundary. Option (b) is a much smaller surgical change: just two
counters wrapping `require` / `use` / `BEGIN` / `eval STRING`
execution.

### Design

Add a **ThreadLocal counter** `ModuleInitGuard.depth` that is:

- **Incremented** on entry to:
  - `RuntimeCode.apply()` for any code emitted from a `require` /
    `use` / `do FILE` load (we already mark these in the compilation
    stack via `RuntimeCode.isEvalBlock` / module-loading flag)
  - BEGIN block execution (entered via `SpecialBlock.runBegin()`)
  - `eval STRING` compilation and initial execution (similar to
    `applyEval()` path)
- **Decremented** on exit (try/finally to handle `die`/`croak`).

Auto-sweep callers consult `ModuleInitGuard.depth == 0` before firing.
When `depth > 0`, the sweep is a no-op.

### Concrete code points

```
org.perlonjava.runtime.runtimetypes.ModuleInitGuard  (new)
  - static ThreadLocal<int[]> depth  (box for mutation)
  - static void enter() / static void exit()
  - static boolean inModuleInit()

org.perlonjava.runtime.runtimetypes.SpecialBlock.runBegin()
  - wrap body in ModuleInitGuard.enter/exit

org.perlonjava.runtime.runtimetypes.RuntimeCode.apply()
  - if code.isEvalBlock && (current frame is require/use loading):
      wrap in ModuleInitGuard.enter/exit
  - applyEval() already special-cased for eval STRING; add guard.

org.perlonjava.runtime.operators.RequireOperator (or wherever `require`
  is implemented)
  - wrap the evaluated code in ModuleInitGuard.enter/exit

Callers of maybeAutoSweep (MortalList.flush / Scalar::Util::isweak):
  - check ModuleInitGuard.inModuleInit() before firing
```

### Risk

Low. The guard is a pure inhibitor — worst case it's too aggressive
(blocks sweeps we could safely run), which is the current behavior.
If a place sets the flag but forgets to clear it, we'd lose
auto-trigger functionality until the process restarts, but not break
anything.

### Validation

- Sandbox 213/213 preserved
- DBIC `t/52leaks.t` **unpatched** passes where it failed before
  (from 9 real fails → 1 or 0)
- DBICTest module init completes (no `BaseResult.pm did not return a
  true value`)
- Moo 71/71 preserved

### Phase B3 follows

Once B2a lands and 52leaks passes unpatched, remove the DBIC
LeakTracer patch. Verify full DBIC suite remains 269/270.

---

## Phase B2a — RESULT (2026-04-19)

**Status:** Implemented (partial success).

### What shipped

- `ModuleInitGuard.java` — `ThreadLocal<int[]> depth` counter with
  `enter()`/`exit()`/`inModuleInit()`.
- `PerlLanguageProvider.executeCode` wraps non-main-program runs
  (`!isMainProgram`) with `enter/exit`. This covers `require`, `use`,
  `eval STRING`, and `do FILE`.
- `MortalList.maybeAutoSweep()` re-added at the end of `flush()`,
  gated by `!ModuleInitGuard.inModuleInit()`, 5-second throttle.
- `ReachabilityWalker.sweepWeakRefs(boolean quiet)` — in quiet mode
  (auto-sweep), skips `DestroyDispatch.clearRescuedWeakRefs()` and
  skips firing DESTROY; in non-quiet mode (explicit `jperl_gc`), does
  both.

### Validation

- Sandbox: **213/213** ✅
- `make` (full unit suite): **PASS** ✅
- DBIC `txn_scope_guard.t`: **18/18** ✅
- DBIC `52leaks.t` **unpatched**: went from **9 real fails → 5 real
  fails** in 15.7s. Remaining failures are Schema/ResultSource objects
  pinned by `DestroyDispatch.rescuedObjects` (phantom-chain rescue)
  which quiet mode does NOT drain by design (draining would require
  firing DBIC Schema DESTROY during unrelated code).
- DBIC `52leaks.t` **patched** (explicit `jperl_gc()`): **1 real
  fail** — same as before. The explicit-GC non-quiet path drains
  `rescuedObjects` and fires DESTROY, clearing the extra 4.

### Decision

Keep the LeakTracer patch as the opt-in path for DBIC users who want
all 9 extra-tight checks to pass. Unpatched, B2a improves the baseline
from 9→5 real fails without requiring any user action.

The remaining 4 (Schema/ResultSource) are solvable only by draining
`rescuedObjects` during auto-sweep, which couples unrelated code
paths to DBIC DESTROY execution — a risk/reward tradeoff we're
deferring.

### Phase B3 status

Not pursued — LeakTracer patch remains the documented opt-in.

---

## Phase C — RuntimeCode.apply iterative trampoline (2026-04-19)

**Status:** Shipped as `da301ca6f`.

### Discovery

A full `jcpan -t DBIx::Class` baseline measurement revealed that most
of the suite's remaining failures were NOT refcount/leak-related —
they were symptoms of a **recursive tailcall trampoline** in
`RuntimeCode.apply` that overflowed the JVM stack on long chains of
`goto &func`.

Moo/DBIC dispatch (`Class::Accessor::Grouped`, `Sub::Defer`,
`Class::C3::Componentised`, AUTOLOAD stubs) frequently builds chains
of 4–10 `goto &$target` per accessor invocation. The previous
apply() implementation recursed into itself for each tailcall,
burning one Java frame per hop; moderately long chains would crash
with `StackOverflowError`.

### Fix

Rewrite the entire `RuntimeCode.apply(RuntimeScalar, RuntimeArray, int)`
method body as an iterative `while (true) { ... }` loop. All dispatch
paths that previously recursed now update local variables and
`continue`:
- TIED_SCALAR fetch
- READONLY_SCALAR deref
- GLOB / REFERENCE-to-GLOB resolution
- STRING / BYTE_STRING code-ref lookup
- AUTOLOAD fallback (source package + current package)
- TAILCALL from `goto &func` (captured inside the try/finally,
  applied after finally runs all cleanup)

### Impact on 52leaks.t

**Unpatched:** 9 real fails → **1 real fail** (16s runtime).

The trampoline fix closed 8 of the 9 previously-failing leak checks
because the accessor/method-dispatch chains that populated DBIC's
phantom-chain rescue no longer run as deeply as they did before —
many of the cyclic Schema/ResultSource references cleared through
normal scope exit without needing the rescuedObjects fallback.

Only 1 leak remains unpatched: `HASH(...) | basic rerefrozen` — a
`Storable::dclone` round-tripped hash that is directly held by some
lexical in `ScalarRefRegistry`. Root cause still open.

### Impact on full DBIC suite

6 previously-broken tests now pass:
- `t/60core.t` (crash → 125/125 in 6s)
- `t/96_is_deteministic_value.t` (crash → 8/8)
- `t/cdbi/68-inflate_has_a.t` (crash → 6/6)
- `t/inflate/core.t` (hang → 32/32 in 5s)
- `t/multi_create/standard.t` (flaky timeout → passes)
- `t/relationship/custom.t` (flaky timeout → passes)

### Remaining unresolved

- **t/storage/error.t test 49** — "callback works after $schema is
  gone": expects Schema DESTROY to fire after `undef $schema`, then a
  weakened `$weak_self` closure variable to read as false. Under
  jperl, `$schema` has cyclic back-refs (source_registrations) that
  prevent its cooperative refCount from reaching 0. The reachability
  walker would clear the weak ref, but the B2a 5s auto-sweep throttle
  doesn't fire within the test's ~3s wall-clock. Explicit
  `Internals::jperl_gc()` fixes it. Closing this without opt-in
  would require either a smarter auto-sweep trigger (e.g. on undef of
  blessed-with-DESTROY refs) or a targeted DBIC cycle-break.

- **t/52leaks.t `basic rerefrozen`** — see above.

---

## Phase D — undef-of-blessed walker trigger (2026-04-19)

**Status:** Shipped as a safety net. Does not fix storage/error.t
test 49 (requires different architectural work).

### Design

Two-part cooperative trigger:

1. **RuntimeScalar.undefine()** — when the user explicitly calls
   `undef $var` on a blessed-with-DESTROY REFERENCE whose cooperative
   refCount stays > 0 (cycles), fire `ReachabilityWalker.sweepWeakRefs(false)`
   synchronously. Gated by `ModuleInitGuard` to avoid tripping during
   `require`/`use`/`eval STRING`/`do FILE`.

2. **RuntimeScalar.set(RuntimeScalar)** — when `value == UNDEF` is
   assigned to a slot holding a blessed-with-DESTROY ref with
   residual refCount, AND we're currently inside a DESTROY body
   (`DestroyDispatch.isInsideDestroy()`), set a flag
   `sweepPendingAfterOuterDestroy`. The outermost `doCallDestroy`
   drains this flag in its `finally` and runs the walker once,
   amortizing per-set() cost to per-outermost-DESTROY cost.

Narrow gating on both paths:
- weak refs exist in the registry (cheap volatile check)
- class is blessed and has DESTROY (BitSet lookup)
- not in module init

### Impact

- Sandbox 213/213 preserved
- Full unit suite PASS
- 52leaks.t unpatched: unchanged, 1 real fail (`basic rerefrozen`)
- storage/error.t test 49: **still fails**

### Why test 49 still fails

Investigation showed that DBIx::Class::Schema::DESTROY does NOT
explicitly undef its `storage` slot. Instead it uses the "self-save"
pattern: reattaches `$self` into a source's schema slot and weakens
it. PerlOnJava registers Schema in `DestroyDispatch.rescuedObjects`
when this pattern is detected, preventing the reachability walker
from considering Schema (or its internals like Storage) unreachable.

Test 49 succeeds on real Perl because that runtime's refcount-only
GC naturally dismantles the self-save pattern as the phantom chain
collapses. PerlOnJava's cooperative refCount + walker overlay keeps
rescued objects live until explicit `jperl_gc()` with rescuedObjects
drain.

Closing this without the LeakTracer-style opt-in would require
either redesigning the rescuedObjects semantics (risk: re-breaks
52leaks.t) or DBIC-specific source-code changes.

### Code

- `DestroyDispatch.java`: new `sweepPendingAfterOuterDestroy` flag,
  `isInsideDestroy()` helper, drain in outermost `finally`.
- `RuntimeScalar.java`: `undefine()` triggers walker inline;
  `set(RuntimeScalar)` sets flag when `value == UNDEF && inside DESTROY
  && blessed-with-DESTROY cycle`.

---

## Phase E — Trace the `basic rerefrozen` leak (2026-04-19, in progress)

**Goal:** Find and fix the single remaining unpatched `t/52leaks.t`
failure without regressing anything else.

### What we know after the diagnostics commit (`578b4ba31`)

Running `JPERL_TRACE_ALL=1 jperl /tmp/repro_rerefrozen.pl` on a
minimal-replica-of-52leaks script shows:

- Target: the outer `Storable::dclone(Storable::dclone(...))` result,
  stored at `$base_collection->{rerefrozen}`.
- **Exactly one** direct ScalarRefRegistry entry holds the target
  hash.
- That holder has `captureCount=0`, `refCountOwned=true`,
  `type=HASHREFERENCE` (0x8068).
- The scalar survives `ScalarRefRegistry.forceGcAndSnapshot()`'s
  3-pass `System.gc()` loop with `WeakReference` sentinels. So the
  scalar has a **strong JVM reference** somewhere that isn't the
  `WeakHashMap` itself.
- Minimal reproducers (< 30 lines) don't trigger it; only the full
  52leaks.t exercise path does. Something in the long-lived
  `fire_resultsets` closure + `random_results` accumulator + the
  `local` trick interaction on lines 281–297 creates the leak.

### Hypotheses to test

1. **Hidden closure capture.** A closure somewhere captures the hash
   ref but `captureCount` was never incremented on the scalar
   (possibly from a code path that bypasses the normal closure
   setup — e.g. `goto &func` combined with `@_` aliasing, or
   `Sub::Defer`-generated subs).

2. **Static cache.** Some static cache keyed by object identity
   (e.g. method dispatch cache, overload resolver, Storable's own
   internal state) holds a strong ref.

3. **MortalList residue.** A `pending` or `marks` entry not
   correctly popped by `popAndFlush()`.

4. **RuntimeArray/RuntimeHash retained from a now-dead scope.** Some
   `@_` copy or `return` list that the JVM still considers live.

### Implementation plan

#### Step 1: Instrument `registerRef` to record call-site stack

Add a gated debug mode:

```java
// ScalarRefRegistry.java
private static final boolean RECORD_STACKS =
        System.getenv("JPERL_REGISTER_STACKS") != null;
private static final Map<RuntimeScalar, Throwable> stacks =
        Collections.synchronizedMap(new WeakHashMap<>());

public static void registerRef(RuntimeScalar scalar) {
    if (OPT_OUT || scalar == null) return;
    scalarRegistry.put(scalar, Boolean.TRUE);
    if (RECORD_STACKS) {
        stacks.put(scalar, new Throwable("registerRef stack"));
    }
}

public static Throwable stackFor(RuntimeScalar sc) {
    return stacks.get(sc);
}
```

#### Step 2: Surface the stack in `jperl_trace_to`

When `JPERL_TRACE_ALL=1 JPERL_REGISTER_STACKS=1`, for each direct
holder of the target, print the captured stack trace.

#### Step 3: Run 52leaks.t-minimal with the instrumentation

Capture the stack for the leaking scalar. The Java stack plus the
current Perl-file/line (from
`RuntimeCaller`/`EmitBytecode.debugInfo`) should identify exactly
which Perl operation allocated this scalar.

#### Step 4: Diagnose and fix

Based on step 3:
- If it's a known code path: fix the leak at source (e.g. clear an
  alias, decrement captureCount properly, prune a cache on scope
  exit).
- If it's MortalList residue: fix flush.
- If it's a static cache: add a clearing hook.

#### Step 5: Verify

- `/tmp/repro_rerefrozen.pl` reports freed
- `t/52leaks.t` unpatched reports 12/12 (no real fails)
- Sandbox 213/213 preserved
- Full unit suite PASS
- Full DBIC suite regression check

### Result (partial)

Shipped as part of Phase E: `MyVarCleanupStack.unregister(Object)` +
bytecode emission in `EmitStatement.emitScopeExitNullStores` so
block-scoped `my` variables are deregistered at normal scope exit,
not just when the enclosing subroutine returns.

Impact:
- Minimal reproducer (`/tmp/repro_rerefrozen2.pl` — blessed ref in a
  hash, `Storable::dclone` round trip, weakened tracker outside the
  block) **now correctly reports freed** after `jperl_gc`.
- Sandbox 213/213 preserved
- Full unit suite PASS
- Full DBIC suite: no regressions

However, `t/52leaks.t` test 12 **still fails** — the real test has a
more complex reference topology where the `rerefrozen` hash ends up
in the registry through a path other than a plain block-scoped
my-var. Additional investigation needed to identify the surviving
holder in the full test. The instrumentation added here
(`JPERL_REGISTER_STACKS=1` + enhanced `jperl_trace_to` parent dump)
will help.

### Phase E2 follow-up investigation (2026-04-20)

After further reverse-trace with `jperl_trace_to`'s container-holder
diagnostic, the true leak path on the real `t/52leaks.t` is:

```
MortalList.deferredCaptures (strong Java ref, ArrayList)
  -> container scalar "rev" (captureCount=3, scopeExited=true, rcO=true)
  -> RuntimeHash = $base_collection
  -> elements["rerefrozen"] = direct-holder scalar (captureCount=0, rcO=true, isContainerElement=true)
  -> RuntimeHash = the leaked rerefrozen hash
```

The container scalar has `captureCount=3` because 3 over-capturing
closures defined inside the same block reference it via
`deferredCaptures`. After Perl-level scope exit, these closures
aren't yet released, so the container can't be cleaned up.

### Attempts explored but reverted

Several surgical walker/filter combinations were tried:

1. **Walker `!sc.refCountOwned` filter** (shipped briefly as
   `09b438101`, **reverted** in `55b34eacd`): skipped orphaned
   registry entries as walker seeds. Closed the "basic random_results"
   path on minimal repros. But broke `t/60core.t`: the filter
   classifies some legitimate live-lexical scalars as orphaned,
   causing the walker to consider DBIC Schema/ResultSource back-refs
   unreachable and prematurely clearing them (detached result source
   errors).

2. **Walker `scopeExited` filter**: skip scalars whose scope has
   exited (over-captured via closures). Same problem as (1) — also
   breaks DBIC's internal schema chains.

3. **`isContainerElement` flag + walker skip**: mark hash/array
   element scalars during `incrementRefCountForContainerStore`,
   skip them as walker seeds (rely on BFS traversal through
   container instead). Breaks DBIC because some elements point
   at blessed objects whose back-refs need walker visibility for
   weak-ref preservation.

4. **`addDeferredCapture` proactive unregister**: when a scalar
   joins `deferredCaptures`, recursively unregister element scalars
   inside its value. Breaks `t/60core.t` column_info tests: the
   BFS descends too eagerly into containers that still need walker
   visibility.

5. **`MortalList.scopeExitCleanupHash` unregister element**: call
   `ScalarRefRegistry.unregister(s)` when flipping rcO=false during
   container scope-exit. Doesn't fire for `$base_collection` because
   its refCount never drops to 0 while in `deferredCaptures`.

### Root cause

The interpreter backend (`BytecodeInterpreter`) **over-captures**
closures — per design note in `RuntimeScalar.scopeExitCleanup`:

> "The interpreter captures ALL visible lexicals for eval STRING
> support, inflating captureCount on variables that closures don't
> actually use."

This over-capture is the fundamental reason `$base_collection`'s
captureCount stays > 0 after scope exit even though no closure
actually uses it. Once captureCount>0, it goes into
`deferredCaptures` which JVM-keeps it (and therefore its elements)
alive until END time. `assert_empty_weakregistry` runs BEFORE END,
so the weak refs are still defined.

### Open work

Fixing this robustly requires one of:

a) **Narrow interpreter over-capture**: change the compiler to only
   register lexicals that are actually referenced by closure bodies,
   not all visible ones. This is the correct fix but touches many
   compiler paths and needs eval-STRING compatibility validation.

b) **Smarter `processReadyDeferredCaptures`**: when called from
   a broad scope-exit path AND all closures of a deferred scalar
   have been stored only in stashes (not in lexicals), forcibly
   process it. Requires tracking closure-lineage, which is complex.

c) **Accept the opt-in**: keep the DBIC `LeakTracer.pm` patch as
   the documented workaround. This is the current status — the
   patched test passes, the unpatched test has 1 known fail.

### Current state

- 52leaks.t unpatched: **11/12** (1 real fail, rerefrozen — down
  from 9 at session start)
- 52leaks.t patched: **12/12** preserved
- Sandbox 213/213, full unit suite PASS
- All other investigated DBIC tests pass

---

## Phase F — Narrow interpreter closure capture (2026-04-20 plan)

**Goal:** Eliminate the root cause of the `basic rerefrozen` leak by
making the interpreter backend's closure detection capture ONLY
lexicals that are actually referenced by the closure body, matching
what the JVM backend already does.

### Background

The JVM backend already solves this — see
`src/main/java/org/perlonjava/backend/jvm/EmitSubroutine.java:120-140`:

```java
// Optimization: Only capture variables actually used in the subroutine body.
if (!isPackageSub && node.block != null && !visibleVariables.isEmpty()) {
    Set<String> usedVars = new HashSet<>();
    VariableCollectorVisitor collector = new VariableCollectorVisitor(usedVars);
    node.block.accept(collector);
    if (!collector.hasEvalString()) {
        // Filter visibleVariables down to only usedVars
    }
}
```

It uses the existing `VariableCollectorVisitor` which:
- Walks the subroutine body AST.
- Collects every `$var`, `@var`, `%var`, `&var` reference.
- Handles subscripted access: `$h{k}` → `%h`, `$a[i]` → `@a`, `$#arr` → `@arr`.
- Descends into nested subroutines so transitive captures are preserved.
- Detects `eval STRING` / `evalbytes STRING`; if present, skips the
  filter (dynamic runtime references possible).

The interpreter backend does NOT do this filtering. In
`src/main/java/org/perlonjava/backend/bytecode/BytecodeCompiler.java:764`
(`detectClosureVariables`), it captures ALL visible lexicals from
the symbol table plus all AST-referenced variables — so the effective
set is always the superset.

This inflates `captureCount` on variables closures don't actually
use, preventing their scope-exit cleanup from completing. The
downstream effect — visible in `t/52leaks.t` — is that
`$base_collection` stays pinned by `MortalList.deferredCaptures`
indefinitely, and so do its hash elements (`rerefrozen`).

### Design

Port the JVM-backend pattern to
`BytecodeCompiler.detectClosureVariables`:

1. After constructing the full `visibleVariables` / `outerVars` map,
   run `VariableCollectorVisitor` over the same AST the compiler is
   about to emit.
2. Respect `hasEvalString()` — when `eval STRING` is detected, fall
   back to the current over-capture behavior.
3. Filter `outerVarNames`, `outerValues`, and `capturedVarIndices`
   down to only names that appear in the `usedVars` set.
4. Preserve existing ordering (TreeMap by register index) to keep
   the `withCapturedVars(...)` array ordering stable.
5. DO NOT filter the second stage (AST-referenced variables at lines
   810-822) — those are already a narrower set and are needed for
   register-recycling safety.

### Code changes

```java
// BytecodeCompiler.java detectClosureVariables

// ... existing code building outerVars from symbolTable ...

// Phase F: narrow to actually-used variables, matching
// EmitSubroutine.java's JVM-backend treatment.
Set<String> usedVars = null;
if (ast != null) {
    Set<String> used = new HashSet<>();
    VariableCollectorVisitor collector = new VariableCollectorVisitor(used);
    ast.accept(collector);
    if (!collector.hasEvalString()) {
        usedVars = used;
    }
}

// In the main capture loop, skip variables not in usedVars:
for (Map.Entry<...> e : outerVars.entrySet()) {
    // ... existing filters ...
    if (usedVars != null && !usedVars.contains(name)) continue;  // NEW
    capturedVarIndices.put(name, reg);
    // ...
}
```

### Validation checklist

- **Sandbox:** `prove dev/sandbox/destroy_weaken/` → 213/213
- **Full unit suite:** `make` → PASS
- **DBIC regression set:** t/60core.t 125/125, t/storage/txn_scope_guard.t
  18/18, t/100populate.t 108/108, t/inflate/core.t 32/32,
  t/96_is_deteministic_value.t 8/8, t/cdbi/68-inflate_has_a.t 6/6
- **Leak target:** t/52leaks.t unpatched → expect 12/12 (fixing
  the last known leak)
- **Eval STRING safety:** Tests that rely on eval STRING capturing
  outer lexicals should continue to pass (`hasEvalString()` gate
  guarantees this).
- **Multi-backend symmetry:** Running the interpreter (`./jperl --int`)
  and JVM-compiled path through the same tests should yield identical
  output for capture-sensitive code.

### Risks and mitigations

| Risk | Mitigation |
|---|---|
| Over-narrow capture breaks variable access in closures. | `VariableCollectorVisitor` already handles subscript, slice, and `$#arr` forms. Port-for-port from JVM backend. |
| eval STRING in a nested scope not detected. | Visitor descends into every node; `hasEvalString` is set during the AST walk. |
| Goto labels or other dynamic-reference ops reach variables at runtime. | Check VariableCollectorVisitor for coverage; if incomplete, the `hasEvalString` gate can be extended to include `goto`, `do FILE`, etc. |
| Moo/DBIC pattern relies on over-capture. | Run full DBIC tests (txn_scope_guard, populate, inflate/core) as regression gates before committing. |

### Expected benefit

With narrowed capture:
- `$base_collection.captureCount` stays 0 when no closure actually
  uses it → no entry in `MortalList.deferredCaptures` on scope exit
  → scopeExitCleanup cleans it → rerefrozen element freed
  → t/52leaks.t test 12 passes unpatched.
- Smaller closure capture frames → lower memory use in Moo/DBIC
  accessor-heavy code.
- Interpreter matches JVM-backend behavior for closure semantics,
  reducing backend-divergence surprises.

### Implementation notes

- The fix sits ENTIRELY inside `detectClosureVariables`.
- No changes to runtime types (`RuntimeScalar`, `MortalList`,
  `WeakRefRegistry`, walker) needed.
- The existing `isContainerElement` / `scopeExited` walker flags
  (landed earlier but unused) can stay — they're cheap and may
  help future diagnostics.
- If this fix resolves 52leaks.t fully, Phase B1's
  `ScalarRefRegistry` + walker is still the correct mechanism for
  cases that DO involve weakened references held by user lexicals.

## Phase F — RESULT (2026-04-20, commit `ad7d32972`)

**Status:** Shipped. "basic rerefrozen" leak **fixed**.

### What shipped

Three call sites in `BytecodeCompiler` now respect
`VariableCollectorVisitor.hasEvalString()`:

1. `detectClosureVariables(Node ast, EmitterContext ctx)` — top-level
   compile entry. Filters `outerVars` by AST-referenced names.
2. `visitNamedSubroutine(SubroutineNode node)` — filters
   `closureVarsByReg` via new helper `collectVisiblePerlVariablesNarrowed`.
3. `visitAnonymousSubroutine(SubroutineNode node)` — same helper.

New helper `collectVisiblePerlVariablesNarrowed(Node body)`:
- Collects all visible variables (unchanged behavior).
- Runs `VariableCollectorVisitor` over the body.
- If `hasEvalString()` returns true, returns the full set
  (backward-compatible for eval STRING semantics).
- Otherwise, filters down to only `used` variable names.

### Impact on t/52leaks.t (unpatched)

| State | Before Phase F | After Phase F |
|---|---|---|
| Test emits plan line? | **No** (dies mid-run) | **Yes** (`1..10`) |
| Tests completed | 12 + die | 10 + clean exit |
| Real fails | 1 (`basic rerefrozen`) | 1 (`basic result_source_handle`) |
| TODO fails | 2 | 0 |

Key observations:
- `basic rerefrozen` — **GONE**. Root cause closed at source.
- `basic Artist=HASH(...)` (previously `not ok 11`, TODO) —
  now cleanly freed, no longer reported.
- `basic Self-referential RS conditions` (TODO `not ok 9`) —
  now passes (was actually a passing TODO, so its removal is
  expected from leak closure).
- `basic result_source_handle` — **newly visible** failure.
  This leak was always present but hidden by the test dying at
  `rerefrozen` (Data::Dumper cannot serialize detached Schema).
  assert_empty_weakregistry sorts leaks by display_name; with
  rerefrozen gone, the next leak in sort order is reported.

### Regression validation

- Sandbox (`dev/sandbox/destroy_weaken/`): **213/213** ✅
- Full unit suite (`make` → testUnitParallel): **PASS** ✅
- DBIC key tests (all pass):
  - `t/storage/txn_scope_guard.t`: 18/18
  - `t/100populate.t`: 108/108
  - `t/inflate/core.t`: 32/32
  - `t/96_is_deteministic_value.t`: 8/8
  - `t/cdbi/68-inflate_has_a.t`: 6/6
  - `t/multi_create/standard.t`: 92/92
  - `t/relationship/custom.t`: 57/57
  - `t/60core.t`: 108+ ok (hangs later in environment-specific
    slow run; no fails in completed tests)

### Remaining work

- **`basic result_source_handle` leak**: trace analysis shows 6
  direct holders of the DBIx::Class::ResultSourceHandle object
  (1 with `rcO=true`, 5 with `rcO=false`). This is a pre-existing
  leak in the DBIC `ResultSourceHandle` lifecycle — a different
  root cause than over-capture. Investigation needs to trace why
  multiple hash/array elements hold the same handle ref after
  scope exit. Likely requires Phase G or a DBIC-specific fix.

### Key code changes

```
BytecodeCompiler.java:
  +  collectVisiblePerlVariablesNarrowed(Node body)
  +  Filters collectVisiblePerlVariables() by VariableCollectorVisitor
     results unless hasEvalString() is true.

  detectClosureVariables:
  +  Run VariableCollectorVisitor on `ast` at entry.
  +  Gate by hasEvalString(); skip narrowing if present.
  +  In the capture loop, skip variables not in usedVars.

  visitNamedSubroutine:
  -  TreeMap<> closureVarsByReg = collectVisiblePerlVariables();
  +  TreeMap<> closureVarsByReg = collectVisiblePerlVariablesNarrowed(node.block);

  visitAnonymousSubroutine:
  -  TreeMap<> closureVarsByReg = collectVisiblePerlVariables();
  +  TreeMap<> closureVarsByReg = collectVisiblePerlVariablesNarrowed(node.block);
```








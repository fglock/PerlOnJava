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

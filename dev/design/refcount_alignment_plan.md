# Aligning PerlOnJava Reference Counting with Perl Semantics

**Status:** Proposal / Design Doc
**Audience:** PerlOnJava maintainers
**Author:** 2026-04-18
**Related:** `dev/modules/dbix_class.md`, `dev/architecture/weaken-destroy.md`, `dev/design/destroy_weaken_plan.md`

## 1. Motivation

Many production CPAN modules depend on Perl's documented reference-counting and
destruction semantics:

- **Deterministic `DESTROY`** when the last strong reference is dropped.
- **`weaken`**: weak references become `undef` the moment the referent is collected.
- **DESTROY resurrection**: if `DESTROY` stores `$self` somewhere, the object
  survives; when that strong ref is released, `DESTROY` is called *again*.
- **Accurate `Scalar::Util::refaddr` + `B::svref_2object(...)->REFCNT`** for
  diagnostics/leak detection.

DBIC, Moose/Moo, Sub::Quote, File::Temp, Devel::StackTrace, and many cache/ORM
modules all depend on these semantics. Today PerlOnJava's **cooperative
refcount** approximates them, but it diverges in enough places that several
real-world tests fail (DBIC t/52leaks tests 12–18, txn_scope_guard test 18,
etc.), and further real-world modules fail silently. This limits PerlOnJava's
usefulness as a drop-in Perl interpreter.

This document lays out a phased plan to close the gap so that:

1. All `t/op/*destroy*`, `t/op/*weaken*`, and equivalent Perl-core semantics
   tests pass on both backends.
2. DBIC's full leak-detection suite passes without modifications.
3. Devel::StackTrace-style `@DB::args` resurrection of destroyed objects
   behaves identically to Perl 5.
4. CPAN modules that assume accurate `REFCNT` readings get accurate readings.

## 2. Why the Current Scheme Falls Short

PerlOnJava uses **cooperative reference counting** layered on top of JVM GC:

- `RuntimeBase.refCount` is an `int` with state machine values:
  `-1` (untracked), `0` (tracked, no counted refs), `>0` (N counted refs),
  `-2` (WEAKLY_TRACKED), `Integer.MIN_VALUE` (DESTROY called).
- Increments happen at specific "hotspots" (`setLargeRefCounted`,
  `incrementRefCountForContainerStore`, etc.) when a reference is stored into
  a tracked container.
- Decrements happen at overwrite sites and at scope-exit cleanup for
  named variables (`scopeExitCleanupHash/Array/Scalar`).

### 2.1 Where accuracy is lost

| Pattern | Problem | Symptom |
|---|---|---|
| `my $self = shift` inside DESTROY | Assignment increments `refCount`; lexical destruction doesn't fire a matching decrement | DESTROY resurrection false-positives; infinite DESTROY loops |
| Function arg copies (`new RuntimeScalar(scalar)` via copy ctor) | Copies don't own a count; stores into containers must call `incrementRefCountForContainerStore` manually — sites get missed | refCount inflation in `visit_refs`, accessor chains |
| `map`/`grep`/`keys`/`values` temporaries | Temporaries hold references without counted ownership | Objects can't reach refCount 0 |
| Overloaded operators returning `$self` | Common DBIC pattern; each return copies via JVM stack | +1 per call site; compounds over accessor chains |
| `bless` + `DESTROY` + `warn` in DESTROY body | `$SIG{__WARN__}` + `caller()` populates `@DB::args` via `setFromList` which increments but scope-exit doesn't decrement | test 18 can't detect real resurrection |
| Anonymous hash/array elements (`{ foo => $obj }`) | Created via `createReferenceWithTrackedElements`; parent hash gets `localBindingExists=true` but no owning scalar | `scopeExitCleanupHash` never fires; weak refs on children never cleared |
| JSON/XML/Storable deserialization output | New anon containers born at refCount=0; outer consumer may or may not own | Storable-specific fix applied; JSON/XML uncovered |

### 2.2 Root architectural limitations

1. **No scope-exit hook for RuntimeScalar copies.** When `my $x = <ref>` assigns
   a ref, `setLargeRefCounted` increments. When the enclosing scope ends,
   JVM GC eventually collects the local `RuntimeScalar` slot, but no
   Perl-visible decrement fires. `scopeExitCleanup(RuntimeScalar)` exists but
   only runs for variables the compiler knows about — function arguments
   copied into args arrays, AST temporaries, closure captures, etc. bypass
   it.

2. **No reachability view.** Perl's mark-and-sweep-when-needed model means a
   refCount-based leak detector (`B::svref_2object->REFCNT`) can be trusted.
   In PerlOnJava, refCount is "approximate" and drifts upward over the life
   of a script.

3. **DESTROY uses `MIN_VALUE` sentinel.** Once `DESTROY` fires, refCount is
   irrecoverable. A strong ref that escapes DESTROY cannot transition the
   object back to a live state for a second DESTROY call, because increment
   paths (`nb.refCount >= 0`) refuse to touch a negative refCount.

4. **`@DB::args` is populated via `setFromList` which increments**, matching
   the copy-into-Perl-hash semantics. But Perl's `@DB::args` uses "fake"
   reference semantics — entries are aliases that don't count. This causes
   double-counting in frames that hold many references.

## 3. Design Goal

Make PerlOnJava's refcount / DESTROY / weaken behave *bit-for-bit* like
Perl 5 from the Perl programmer's perspective, without abandoning the JVM's
GC for memory reclamation.

Specifically:
- `B::svref_2object($x)->REFCNT` returns Perl's expected value for every
  common reference pattern.
- `DESTROY` fires at the right time, the right number of times, with the
  right `$self` identity semantics.
- `weaken` / `isweak` behave as in Perl 5, including clearing to `undef`
  the *moment* the referent is collected.

## 4. Strategy Overview

Keep cooperative refcounting as the *primary* mechanism, but add:

- **Scope-exit decrement completeness** — ensure every path that increments
  has a matching path that decrements when the holder goes out of scope.
- **Accurate function-call frame accounting** — `@_` entries are aliases;
  argument passing into subs does not inflate refcount.
- **Proper DESTROY state machine** — separate "actively destroying" from
  "fully dead" so that resurrection can transition back to live.
- **On-demand reachability fallback** — a mark-and-sweep walk from
  symbol-table + live-lexical roots, triggered by (a) `B::svref_2object`
  queries and (b) periodic (or cheap triggered) sweeps at scope exit.

The reachability fallback is the insurance policy: even when refCount
accounting drifts upward, weak refs still get cleared when the referent is
actually unreachable from Perl code. This is what Perl 5 does under the hood
(via refcounting, not mark-and-sweep, but with accurate counts it amounts to
the same user-visible behavior).

## 5. Phased Plan

Each phase is independently shippable and adds or refines a piece of the
story. Phases can overlap if multiple developers work in parallel, but the
tests for each phase should pass before moving on.

### Phase 0 — Diagnostic infrastructure (1–2 weeks)

Goal: be able to measure the gap.

- Add `JPERL_REFCOUNT_TRACE=<class>` env var: log every refCount transition
  for objects of the given class, with a short stack trace. Output to
  `/tmp/jperl_refcount_<pid>.log`.
- Add `JPERL_DESTROY_DEBUG` (already partially exists): log every
  `callDestroy` / `doCallDestroy` entry/exit with refCount and flags.
- Add `dev/tools/refcount_diff.pl`: runs a Perl script under both `perl` and
  `jperl`, captures `B::svref_2object->REFCNT` snapshots at user-marked
  checkpoints, and prints the diff. Relies on a new jperl built-in
  `jperl_refcount_snapshot(\@objects)` that dumps refCount, blessId,
  localBindingExists, currentlyDestroying for each.
- Port an extensive "destroy behavior" test corpus from Perl's `t/op/`
  tests (at least `destroy.t`, `weaken.t`, `Devel/Peek/*`, plus DBIC's
  `t/lib/DBICTest/Util/LeakTracer.pm`-based sub-tests) into a new
  `perl5_t/t/destroy-semantics/` directory and wire into
  `dev/tools/perl_test_runner.pl`.
- Define a **baseline report**: number of refcount/destroy-semantics tests
  passing / failing on master today. Track this report in every PR.

**Exit criteria:** Running `dev/tools/refcount_diff.pl t/anon_refcount2.pl` shows
a textual diff of where jperl and perl diverge for every reference in the
script. Baseline report committed.

### Phase 1 — Complete scope-exit decrement for scalar lexicals (3–4 weeks)

Goal: every `my $x = <ref>` increment has a matching scope-exit decrement.

- Audit every bytecode emitter path for scalar lexical scope exit in the
  compiler: `ScopeManager`, `EmitBlock`, `EmitSubroutine`, `EmitForeach`,
  `EmitReturn`, etc. Ensure each emits `RuntimeScalar.scopeExitCleanup($x)`
  before the slot goes out of scope.
- Audit closures (`capturedScalars`): when a closure's own `RuntimeCode`
  dies, every captured scalar's `captureCount` must be decremented *and*
  the captured scalar's decrement must happen if its scope already exited
  (the existing `scopeExited` flag handles this; verify all branches
  actually fire).
- Audit `@_` lifecycle: at sub entry, args are pushed; at sub exit, each
  arg's scope must end and its refCountOwned=true must trigger a decrement.
  Today `RuntimeCode.apply` handles this approximately; verify there are no
  skipped paths (`return` keyword, `die`, `goto &sub`, tail call, etc.).
- Audit `map` / `grep` / `sort` block bodies — these create implicit
  lexicals ($_, $a, $b) and temporary result slots. Each allocation must
  pair with a cleanup.
- Fix diagnosed gaps in order: (a) simple block-exit scalars first,
  (b) sub-return path, (c) closures, (d) `map/grep`, (e) `eval` cleanup.
- For each fix, add a regression test that `dev/tools/refcount_diff.pl`
  shows zero divergence vs `perl` for the pattern.

**Exit criteria:** `my $x = \@arr; { my $y = $x }` results in the exact
same refCount snapshot as Perl at every checkpoint. File::Temp's
`DESTROY` leaves refCount=0 (not 1) when called with no external references.

### Phase 2 — Function argument pass-through without inflation (2–3 weeks)

Goal: calling a sub with a reference argument does not change the
argument's net refCount once the sub returns.

- Change `@_` semantics: `@_` entries are **aliases** to the caller's args,
  not independent counted references. Implement an `ALIASED_ARRAY` mode on
  `RuntimeArray` where pushing into it does not increment, and popping/
  shifting doesn't decrement the aliased target. `@_` is set to this mode
  by `RuntimeCode.apply`.
- `shift @_` into a local: the local is a new counted reference. The
  aliased entry goes away; no deferred decrement because there was no
  increment on push.
- `@DB::args` populated from `caller()`: use the same ALIASED_ARRAY mode so
  that capturing args doesn't inflate refs. When user code does
  `push @kept, @DB::args`, *that* push into `@kept` does increment — creating
  the real strong refs Perl expects.
- `goto &sub`: replace @_ in place without inflating.
- Audit XS-equivalent entry points (`SystemOperator`, DBI, etc.): when these
  call back into Perl, they should set up `@_` as ALIASED_ARRAY too.

**Exit criteria:** `f($obj)` where `sub f { 1 }` leaves `$obj`'s refCount
unchanged across the call. `Devel::StackTrace`-style `@DB::args` capture
into a *global* array does increment refCount (because the push into the
global is a real store). Same behavior as Perl.

### Phase 3 — Proper DESTROY state machine (2–3 weeks)

Goal: support DESTROY resurrection with correct ordering.

- Replace `MIN_VALUE` sentinel with a proper state enum on `RuntimeBase`:
  `LIVE` (refCount>=0), `DESTROYING` (inside DESTROY body),
  `RESURRECTED` (DESTROY ran, new ref appeared during/after),
  `DEAD` (cleanup done, weak refs cleared).
- In `doCallDestroy`:
  - Transition state `LIVE` → `DESTROYING` at entry.
  - Reset refCount from whatever the caller set to 0 (live accounting during DESTROY).
  - Run Perl DESTROY body.
  - After body: flush pending decrements. Check refCount:
    - `== 0` → transition to `DEAD`, clear weak refs, cascade children.
    - `> 0` → transition to `RESURRECTED`; defer cleanup until next
      refCount==0 event.
- On `RESURRECTED` → next refCount==0: re-enter `doCallDestroy`
  (DESTROY fires again). DBIC's `detected_reinvoked_destructor` sees
  second invocation and emits the expected warning.
- Re-entry guard via `state == DESTROYING` instead of a
  `currentlyDestroying` boolean (cleaner semantics).
- Phase 1's scope-exit completeness is a *prerequisite*: without it, local
  lexicals inside DESTROY inflate refCount and cause false resurrection.
  This phase ships only after Phase 1.

**Exit criteria:** `/tmp/rescue_test.pl` shows 2 DESTROY calls in jperl
matching Perl's output. DBIC `t/storage/txn_scope_guard.t` test 18 passes.
No File::Temp DESTROY loops.

### Phase 4 — On-demand reachability fallback (3–5 weeks)

Goal: even when refCount drifts upward, weak refs get cleared when the
referent is actually unreachable from Perl roots.

- Implement `ReachabilityWalker`: starts from the union of:
  - `GlobalVariable.*` (symbol table: all stashes, globals, `@ISA`, etc.)
  - All live lexical scopes (walk the call stack's JVM frames; each
    lexical is a JVM local pointing to a RuntimeScalar/RuntimeArray/etc.)
  - `rescuedObjects`
  - DynamicVariable save stack
- Recursively walks references via `RuntimeBase.iterator()` / hash values
  / array elements (treating weak refs as non-edges, matching
  DBICTest's `visit_refs`).
- Produces a **reachable set**. Objects with weak refs registered but NOT
  in the reachable set are "leaked" from Perl's view; clear their weak refs.
- **Trigger points**:
  - On `Internals::SvREFCNT(\$x)` calls, if the refCount looks suspicious
    (object is in the weak-ref registry and refCount disagrees with the
    reachable set), return the reachability-based count instead.
    Optional and gated by `$ENV{JPERL_ACCURATE_REFCNT}` in v1.
  - At periodic intervals — e.g., every 1000th `MortalList.flush()` — do
    a fast partial sweep limited to objects in the weak-ref registry.
    This amortizes the cost across the script.
  - Explicit entry point `jperl_gc()` for tests that need precision.
- **Cost analysis**: a full walk is O(live object graph). For typical
  scripts this is <1ms. For DBIC tests (~100k objects), target <10ms.
  Profile and set periodic trigger frequency accordingly.
- Compare-test against Perl: for every DBIC-style leak test, after all
  Perl code runs, the reachable set from jperl must match Perl's refcount
  reachability within epsilon.

**Exit criteria:** DBIC t/52leaks.t tests 12-18 pass. The sweep overhead
at default frequency is <5% on `make test-bundled-modules` wall clock.

### Phase 5 — Accurate `B::svref_2object->REFCNT` (1–2 weeks)

Goal: `REFCNT` returns Perl-compatible values for diagnostic consumers.

- When `Internals::SvREFCNT(\$x)` is called, use the reachability walker
  to count *distinct* reference edges pointing to `$x`, not raw refCount.
  For most cases these agree; for cases where refCount is inflated, use
  the reachable-edge count.
- Audit `B::*` shim modules in `~/.perlonjava/lib` — ensure they pass
  `REFCNT` through correctly.
- Test: for every reference in a Perl script, `REFCNT` at every checkpoint
  agrees with native Perl within ±0 (not ±1 as today).

**Exit criteria:** `dev/tools/refcount_diff.pl` reports 0 divergence on
all test corpora.

### Phase 6 — Comprehensive CPAN validation (2–4 weeks)

Goal: prove the changes unlock real-world modules.

Target CPAN modules to run to completion:

| Module | Why |
|---|---|
| Moose | Accessor inlining, BUILD/DEMOLISH ordering |
| Moo, MooX::late | Sub::Quote captures, DESTROY |
| DBIx::Class | 281 test files, heavy weaken/DESTROY |
| Catalyst | Circular refs in request/response chains |
| Plack, PSGI | Streaming response cleanup |
| Mojolicious | Event loop, timers with DESTROY |
| Data::Printer, Devel::Peek | Diagnostic consumers of REFCNT |
| Devel::Cycle, Devel::FindRef, Test::LeakTrace | Leak-detection tooling |
| DateTime::TimeZone | Class-level caching interacts with DESTROY |
| File::Temp, Path::Tiny | Filesystem cleanup on DESTROY |
| Cache::LRU, Cache::FastMmap | Weak refs in eviction policy |
| JSON::XS, YAML::XS, XML::LibXML | Deserialized anon containers |
| Tie::RefHash::Weak | Pathological weak-ref case |

For each, run its full test suite on both `perl` and `jperl` and commit a
diff report. Accept only files where jperl's results match or exceed what
master jperl achieves today.

**Exit criteria:** At least 8 of the above modules achieve full-parity
test pass rates. None regress from today.

### Phase 7 — Interpreter backend parity (1–2 weeks, runs in parallel)

The interpreter backend (`./jperl --interpreter`) has different refcount
code paths (AST walker instead of bytecode) and must be updated in
lockstep. For each Phase 1–5 change:

- Apply the same semantic fix to the interpreter AST walker.
- Run `.cognition/skills/interpreter-parity/` checks.
- Cross-compare: every test that passes on the JVM backend must also pass
  on `--int`.

**Exit criteria:** interpreter-parity skill reports 0 divergences on the
destroy-semantics corpus.

## 6. Risk Analysis & Rollback

Each phase is independently shippable. Rollback is per-commit.

| Phase | Risk | Mitigation |
|---|---|---|
| 0 (Diagnostics) | None — pure tooling | — |
| 1 (Scope exit) | Could break closures/eval/goto by over-decrementing | Large test corpus from Phase 0; feature-flag behind `JPERL_STRICT_SCOPE_EXIT=1` during validation |
| 2 (`@_` aliasing) | XS / C-level assumptions could break | Feature-flag `JPERL_ALIASED_AT_UNDERSCORE=1`; keep old behavior as fallback for first release |
| 3 (DESTROY FSM) | Resurrection cycles if state machine has bugs | Loop detection (fail fast with RuntimeException above 1000 DESTROY calls on same object) |
| 4 (Reachability) | Cost; rarely-triggered edge cases (tied vars, weak refs into globs) | Profile extensively; amortize via periodic not per-op; keep current cooperative refcount as source of truth, reachability as fallback |
| 5 (REFCNT API) | CPAN modules with specific REFCNT expectations might break | Opt-in via `JPERL_ACCURATE_REFCNT=1` for one release; default-on in next |
| 6 (CPAN validation) | Modules may need small patches for their own test bugs | Apply via `dev/patches/cpan/` if module's test is jperl-unaware |
| 7 (Interpreter) | Double the work | Share semantic helpers between backends via `runtime` classes |

## 7. What Stays the Same

- JVM GC remains the memory manager. Cooperative refCount is *metadata*,
  not storage.
- `MortalList` / `DynamicState` stack discipline unchanged.
- Existing compile-time optimizations (constant folding, type propagation)
  unaffected.
- Existing weak-ref registry data structure unchanged; only clearing
  triggers and timing shift.

## 8. Open Questions

1. **Tied variables** — `tie $scalar, 'Class'` adds a magic layer. Phase 4
   reachability must treat tied scalars as strong-ref holders. Need to
   audit `RuntimeScalarType.TIED_SCALAR` / `TIED_HASH` / `TIED_ARRAY`
   paths.
2. **Signal handlers & `END` blocks** — these run after main script exit.
   Verify reachability walk includes signal-handler closures.
3. **`fork()`** — jperl doesn't implement fork. Any DESTROY cleanup that
   assumes exec-then-exit semantics needs review.
4. **Profiler overhead** — the reachability walker will dominate profiling
   for leak-detection scripts. Consider whether to expose a
   `jperl_reachability_walker_enabled(0|1)` builtin.
5. **Multi-threading** — Perl `threads` aren't supported, but JVM threads
   can run Perl-level code via inline Java. Current refCount is not
   thread-safe. Phase 4 makes it easier to become thread-safe because the
   reachability walker can be serialized at a global lock without needing
   per-op atomics. Design decision: acquire stop-the-world for sweeps,
   keep per-op refCount non-atomic.

## 9. Validation

A new `make test-destroy-semantics` target that runs:

1. `perl5_t/t/destroy-semantics/` corpus (Phase 0).
2. `dev/sandbox/destroy_weaken/` existing tests.
3. DBIC `t/52leaks.t` + `t/storage/txn_scope_guard.t` + `t/storage/txn.t`.
4. Sub-set of Perl 5's own `t/op/destruct.t`, `t/op/weaken.t`,
   `ext/Devel-Peek/t/Peek.t`.

Must pass on both JVM backend and interpreter backend. Gated in CI.

Additionally a **differential testing** job: run 100 random CPAN modules'
test suites on both `perl` and `jperl`, report any test-count regressions.

## 10. Estimated Total Effort

- Phase 0: 1–2 weeks
- Phase 1: 3–4 weeks
- Phase 2: 2–3 weeks
- Phase 3: 2–3 weeks
- Phase 4: 3–5 weeks
- Phase 5: 1–2 weeks
- Phase 6: 2–4 weeks
- Phase 7: 1–2 weeks

**Total: 15–25 weeks** of focused work for a single developer; much
less with parallelism, since Phases 2 / 3 / 4 are largely independent.

## 11. Success Metric

The project succeeds when:

```bash
# DBIC full suite
cd $DBIC_BUILD
prove -rv t/ -j 4
# All tests pass, including:
# - t/52leaks.t (28 tests)
# - t/storage/txn.t (90 tests)
# - t/storage/txn_scope_guard.t (18 tests)

# Perl core destroy semantics
make test-destroy-semantics
# All pass on both backends

# CPAN compat
make test-bundled-modules
# No regressions from today

# Diagnostic correctness
dev/tools/refcount_diff.pl dev/sandbox/destroy_weaken/*.pl
# 0 divergences from native perl
```

At that point PerlOnJava is a credible target for running the long tail of
CPAN modules that depend on deterministic destruction and accurate
reference counting — which is most of them.

## 12. References

- `dev/architecture/weaken-destroy.md` — current refCount state machine
- `dev/modules/dbix_class.md` — concrete failure modes observed
- `dev/design/destroy_weaken_plan.md` — original DESTROY/weaken plan (PR #464)
- Perl 5 source: `sv.c` `Perl_sv_free2` (refcount decrement + DESTROY dispatch)
- Perl 5 source: `pp.c` `Perl_pp_leavesub` (sub-exit @_ cleanup)
- Perl 5 `perlguts` POD (SV reference counting internals)

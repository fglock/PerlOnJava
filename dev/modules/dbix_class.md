# DBIx::Class Fix Plan

**Module**: DBIx::Class 0.082844 (installed via `jcpan`)
**Branch**: `feature/dbix-class-destroy-weaken`  |  **PR**: https://github.com/fglock/PerlOnJava/pull/485

## Documentation Policy

Every non-trivial code change MUST document: what it solves, why this approach, what would break if removed.

## Installation & Paths

| Path | Contents |
|------|----------|
| `~/.perlonjava/lib/` | Installed modules (`@INC` entry) |
| `~/.perlonjava/cpan/build/DBIx-Class-0.082844-NN/` | Build dir with tests |

```bash
DBIC_BUILD=$(ls -d ~/.perlonjava/cpan/build/DBIx-Class-0.082844-* 2>/dev/null | grep -v yml | sort -t- -k5 -n | tail -1)
```

## How to Run the Suite

```bash
cd /Users/fglock/projects/PerlOnJava3 && make
cd "$DBIC_BUILD"
JPERL=/Users/fglock/projects/PerlOnJava3/jperl
mkdir -p /tmp/dbic_suite
for t in t/*.t t/storage/*.t t/inflate/*.t t/multi_create/*.t t/prefetch/*.t \
         t/relationship/*.t t/resultset/*.t t/row/*.t t/search/*.t \
         t/sqlmaker/*.t t/sqlmaker/limit_dialects/*.t t/delete/*.t t/cdbi/*.t; do
    [ -f "$t" ] || continue
    timeout 60 "$JPERL" -Iblib/lib -Iblib/arch "$t" > /tmp/dbic_suite/$(echo "$t" | tr '/' '_' | sed 's/\.t$//').txt 2>&1
done
# Summary excluding TODO failures
for f in /tmp/dbic_suite/*.txt; do
    real=$(grep "^not ok " "$f" 2>/dev/null | grep -v "# TODO" | wc -l | tr -d ' ')
    [ "$real" -gt 0 ] && echo "FAIL($real): $(basename $f .txt)"
done | sort
```

---

## Remaining Failures

| File | Count | Status |
|------|-------|--------|
| `t/52leaks.t` | 7 (tests 12-18) | Deep — refCount inflation in DBIC LeakTracer's `visit_refs` + ResultSource back-ref chain. Needs refCount-inflation audit; hasn't reproduced in simpler tests |
| `t/52leaks.t` (line 430, harness-only) | **NEW — INVESTIGATE NEXT** | "Unable to perform storage-dependent operations with a detached result source (source 'Artist' is not associated with a schema)". Test passes standalone (11/11 in 46s) but raises this exception when run as part of `./jcpan -t DBIx::Class` after ~20 prior tests. Schema's weak ref to ResultSource (or RS's strong ref to schema?) is being cleared *prematurely* — DESTROY firing on the schema while a child resultset still expects it. Different failure mode from tests 12-18 (which is a leak, this is over-eager DESTROY). See "Investigation Plan: Schema-Detached Bug" below. |
| `t/storage/txn_scope_guard.t` | 1 (test 18) | Needs DESTROY resurrection semantics (strong ref via @DB::args after MIN_VALUE). Tried refCount-reset approach — caused infinite DESTROY loops when __WARN__ handler re-triggers captures. Needs architectural redesign (separate "destroying" state from MIN_VALUE sentinel) |

`t/storage/txn.t` — **FIXED** (90/90 pass) via Fix 10m (eq/ne fallback semantics).

---

## Investigation Plan: Schema-Detached Bug in t/52leaks.t (line 430) — IN PROGRESS

### Symptom

Under `./jcpan -t DBIx::Class` (full 314-test suite), `t/52leaks.t` fails with:

```
DBIx::Class::ResultSource::schema(): Unable to perform storage-dependent operations
with a detached result source (source 'Artist' is not associated with a schema).
at t/52leaks.t line 430
```

`Tests were run but no plan was declared and done_testing() was not seen` — i.e. the test
died mid-execution, not at the leak-detection assertion.

Standalone `../../jperl -Ilib -It/lib t/52leaks.t` passes 11/11 in ~46 s. Failure
only manifests when ~20+ prior DBIC tests have run through the same harness JVM.

### Code path that triggers it

`t/52leaks.t` lines 414–438 iterate a chain of accessor closures over `$phantom`:

```perl
for my $accessor (
    sub { shift->clone },
    sub { shift->resultset('CD') },
    sub { shift->next },
    sub { shift->artist },
    sub { shift->search_related('cds') },
    sub { shift->next },
    sub { shift->search_related('artist') },
    sub { shift->result_source },
    sub { shift->resultset },              # <── line 430 fails here
    sub { shift->create({ name => 'detached' }) },
    ...
) {
    $phantom = populate_weakregistry( $weak_registry, scalar $_->($phantom) );
}
```

Each step replaces `$phantom`. The step-before-failure produces a `ResultSource`
(via `->result_source`); the failing step calls `->resultset` on it, which
does `$self->schema or die 'detached…'`. So the `ResultSource`'s `schema`
attribute (an inflated weak ref) is empty by the time we read it.

### Hypothesis

A previous test in the harness has populated the global walker / weak-ref state
in a way that makes the schema's weak ref to itself get auto-cleared during a
mid-statement walker pass. When the row's `result_source` is later asked for
its schema, the weak ref reads as undef.

The walker-gate property fix (PR #618 / commit ce8186e89) widened the gate to
fire on every blessed object whose `storedInPackageGlobal` flag is set. The
perf-cache fix in 691f95386 keeps the BFS bounded but **doesn't change which
objects get gated**. If a Schema/ResultSource pair becomes gate-eligible mid-
test under cumulative state pressure, weak-ref clearing is over-applied.

### Diagnostic plan

1. **Pinpoint which earlier test contaminates the JVM.** Run the same DBIC
   prefix one test at a time and bisect: with [00..101], [00..52], [00..40],
   etc., find the smallest prefix whose final state makes a freestanding
   `populate_weakregistry( $weak_registry, $phantom->result_source->resultset )`
   throw.

2. **Capture the exact moment.** With the bisected prefix, instrument
   `DBIx::Class::ResultSource::schema()` (or the Java side at
   `WeakRefRegistry.clearWeakRefsTo`) to log:
   - which Schema instance is having its weak refs cleared,
   - what triggered the clear (DESTROY? walker pass? scope exit?),
   - the call stack in Perl + Java at the moment of the clear.

3. **Compare reachability**: at the moment of the clear, is the Schema
   actually unreachable (in which case the clear is correct and DBIC has a
   genuine ref-tracking gap), or is it reachable but the walker missed it?
   If walker missed it, that's a PerlOnJava bug in `ReachabilityWalker`.

4. **Verify with c4db69e8d baseline.** That commit's documented run is
   `./jcpan --jobs 1 -t DBIx::Class → 0/13858 fails`. If we can apply just
   the relevant commits (PR #618 walker-gate property change + 691f95386 perf
   cache) on top of c4db69e8d's parent and reproduce the failure, the
   property-based gate is the regression source.

### What we already know (from today's instrumentation)

- The harness *parent* JVM is **not** the bottleneck. 10 jstack samples over
  32 min show the parent in `IOOperator.selectWithNIO` `Thread.sleep(10)`
  polling 99.7 % of the time (6 s CPU in 32 min wall). It's just waiting.
- The harness uses `IPC::Open3` → `ProcessInputHandle`, which does correctly
  return `false` from `isReadReady()` when the child is silent — that's the
  intended behaviour, not the bug.
- The orphan-watchdog landed in PR #635 prevents leftover JVMs from
  contaminating subsequent runs (no more 100% CPU starvation), but does NOT
  fix the schema-detached exception itself.

### Status

- [x] Reproduced under `./jcpan -t DBIx::Class` (occasionally; today on test
      `t/52leaks.t` ~test #21 of the suite).
- [ ] Pinpoint earlier test that contaminates state.
- [ ] Capture call stack at the moment the schema's weak ref is cleared.
- [ ] Bisect c4db69e8d → master (likely PR #618 commit ce8186e89).
- [ ] Fix and verify under full DBIC suite (must hit 0/314 fails).

### Why we can't ship without this fix

A user running `jcpan DBIx::Class` will see a clean install when run alone
(passes standalone) but a failed install under the published smoke-test
infrastructure. That's a worse user experience than the current pre-PR-#635
state (where the storable bugs blocked things up front). Per
@dev/cpan-reports/cpan-compatibility.md we publish "DBIx::Class PASS" — we
can't ship a regression behind that flag.

---

## Completed Fixes

| Fix | What | Key Insight |
|-----|------|-------------|
| 1 | LIFO scope exit + rescue detection | `LinkedHashMap` for declaration order; detect `$self` rescue in DESTROY |
| 2 | Deferred weak-ref clearing for rescued objects | Sibling ResultSources still need weak back-refs |
| 3 | DBI `RootClass` attribute for CDBI compat | Re-bless handles into `${RootClass}::db/st` |
| 4 | `clearAllBlessedWeakRefs` + exit path | END-time sweep for all blessed objects; also run on `exit()` |
| 5 | Auto-finish cached statements | `prepare_cached` should `finish()` Active reused sth |
| 6 | `next::method` always uses C3 | Perl 5 always uses C3 regardless of class MRO setting |
| 7 | Stash delete weak-ref clearing + B::REFCNT fix | `deleteGlob()` triggers clearWeakRefs |
| 8 | DBI BYTE_STRING + utf8::decode conditional | Match DBD::SQLite byte-string semantics |
| 9 | DBI UTF-8 round-trip + ClosedIOHandle | Proper UTF-8 encode/decode for JDBC |
| 10a | Clear weak refs when `localBindingExists` blocks callDestroy | In `flush()` at refCount 0 |
| 10d | `clearAllBlessedWeakRefs` clears ALL objects | END-time safety net no longer blessed-only |
| 10e | `createAnonymousReference()` for Storable/deserializers | Anon hashes from dclone no longer look like named `\%h` |
| 10f | Cascade scope-exit cleanup when weak refs exist | `WeakRefRegistry.weakRefsExist` fast-path flag |
| 10g | `base.pm`: treat `@ISA` / `$VERSION` as "already loaded" | Fixes `use base 'Pkg'` on eval-created packages. DBIC t/inflate/hri.t now 193/193 |
| 10h | `flock()` allows multiple shared locks from same JVM | Per-JVM shared-lock registry keyed by canonical path. Fixes `t/cdbi/columns_as_hashes.t` hang |
| 10i | `fork()` doesn't emit `1..0 # SKIP` after tests have run | Only emits when `Test::Builder->current_test == 0`. Sets $! to numeric EAGAIN + auto-loads Errno. Fixes DBIC txn.t "Bad plan" |
| 10j | DBI stores mutable scalars for user-writable attrs | `new RuntimeScalar(bool)` instead of `scalarTrue` so `$dbh->{AutoCommit} = 0` works |
| 10k | Overload `""` self-reference falls back to default ref form | Identity check in `toStringLarge` + ThreadLocal depth guard in `Overload.stringify` |
| 10l | `@DB::args` preserves invocation args after `shift(@_)` | New `originalArgsStack` (snapshot) in RuntimeCode parallel to live `argsStack` |
| 10m | `eq`/`ne` throw "no method found" when overload fallback not permitted | Match Perl 5: blessed class with `""` overload but no `(eq`/`(ne`/`(cmp` and no `fallback=>1` → throw. Fixes DBIC t/storage/txn.t test 90 |

---

## What Didn't Work (don't re-try)

| Approach | Why it failed |
|----------|---------------|
| `System.gc()` before END assertions | Advisory; no guarantee |
| `releaseCaptures()` on ALL unblessed containers | Falsely reaches 0 via stash refs; Moo infinite recursion |
| Decrement refCount for captured blessed refs at inner scope exit | Breaks `destroy_collections.t` test 20 — outer closures legitimately keep objects alive |
| `git stash` for testing alternatives | **Lost work** — never use |
| Rescued object `refCount = 1` instead of `-1` | Infinite DESTROY loops (inflated refcounts always trigger rescue) |
| Cascading cleanup after rescue | Destroys Schema internals (Storage, DBI::db) the rescued Schema needs |
| Call `clearAllBlessedWeakRefs` earlier | Can't pick "significant" scope exits during test execution |
| `WEAKLY_TRACKED` for birth-tracked objects | Birth-tracked (refCount≥0) don't enter WEAKLY_TRACKED path in `weaken()` |
| Decrement refCount for WEAKLY_TRACKED in `setLargeRefCounted` | WEAKLY_TRACKED refcounts inaccurate; false-zero triggers |
| Hook into `assert_empty_weakregistry` via Perl code | Can't modify CPAN test code per project rules |
| `deepClearAllWeakRefs` in unblessed callDestroy | Too aggressive — clears refs for objects still alive elsewhere. Failed `destroy_anon_containers.t` test 15 |
| DESTROY resurrection via refCount=0 reset + incrementRefCountForContainerStore resurrection branch | Worked for simple cases but caused infinite DESTROY loops for the `warn` inside DESTROY pattern: each DESTROY call triggers the __WARN__ handler which pushes to @DB::args → apparent resurrection → refCount > 0 → eventual decrement → DESTROY fires again → loop. The mechanism needs a separate "being destroyed" state distinct from MIN_VALUE to avoid re-entry |

---

## Non-Bug Warnings (informational)

- **`Mismatch of versions '1.1' and '1.45'`** in `t/00describe_environment.t` for `Params::ValidationCompiler::Exception::Named::Required`: Not a PerlOnJava bug. `Exception::Class` deliberately sets `$INC{$subclass.pm} = __FILE__` on every generated subclass.
- **`Subroutine is_bool redefined at Cpanel::JSON::XS line 2429`**: Triggered when Cpanel::JSON::XS loads through `@ISA` fallback. Cosmetic only.

---

## Fix 10: t/52leaks.t tests 12-18 — IN PROGRESS

### Failure Inventory

| Test | Object | B::REFCNT | Category |
|------|--------|-----------|----------|
| 12 | `ARRAY \| basic random_results` | 1 | Unblessed, birth-tracked |
| 13-15 | `DBICTest::Artist` / `DBICTest::CD` | 2 | Blessed row objects |
| 16 | `ResultSource::Table` (artist) | 2 | Blessed ResultSource |
| 17 | `ResultSource::Table` (artist) | 5 | Blessed ResultSource |
| 18 | `HASH \| basic rerefrozen` | 0 | Unblessed, dclone output |

All 7 fail at line 526 `assert_empty_weakregistry` — weak refs still `defined`.

### Key Timing Constraint

Assertion runs **during test execution** (line 526), not in an END block. `clearAllBlessedWeakRefs()` (END-time sweep) is too late.

### Root Cause: Parent Container Inflation

`$base_collection` (parent anonymous hash) has refCount inflated by JVM temporaries from:
- `visit_refs()` deep walk (passes hashref as function arg)
- `populate_weakregistry()` + hash access temporaries
- `Storable::dclone` internals
- `$fire_resultsets->()` closures

When scope exits, scalar releases 1 reference but hash stays at refCount > 0. `callDestroy` never fires → `scopeExitCleanupHash` never walks elements → weak refs persist.

**Implication**: Fixes that hook into callDestroy/scopeExit for the parent hash are blocked because it never dies. Our minimal reproducers (`/tmp/dbic_like.pl`, `/tmp/blessed_leak.pl`, `/tmp/circular_leak.pl`) no longer leak, but the real DBIC pattern still does.

### Diagnostic Facts

- **B::REFCNT inflates by +1** vs actual: `B::svref_2object($x)->REFCNT` calls `Internals::SvREFCNT($self->{ref})` which bumps via B::SV's blessed hash slot. Failure inventory values are actual refCount + 1 (or 0 when refCount = MIN_VALUE).
- **Unicode confirmed irrelevant**: t/52leaks.t uses only ASCII data.

### Next Steps

Both remaining failures (t/52leaks.t tests 12-18 and t/storage/txn_scope_guard.t test 18) hit **fundamental limitations** of PerlOnJava's selective refCounting that can't be solved without a major architectural change:

#### Why t/52leaks.t tests 12-18 Are Blocked

`$base_collection` (parent anonymous hash) has refCount inflated by JVM temporaries created during `visit_refs`, `populate_weakregistry`, `Storable::dclone`, `$fire_resultsets->()`. When its scope exits, the scalar releases 1 reference but the hash stays at refCount > 0 → `callDestroy` never fires → `scopeExitCleanupHash` never cascades into children → weak refs persist.

Attempted fixes:
- **Orphan sweep for refCount==0 objects** (Fix 10n attempt #1): No effect because leaked objects have refCount 1-5, not 0.
- **Deep cascade from parent at scope exit**: Parent itself never triggers scope exit because its refCount > 0.
- **Reachability-based weak-ref clearing**: Would require true mark-and-sweep from symbol-table roots — a major architectural addition.

The simple reproducers (`/tmp/dbic_like.pl`, `/tmp/blessed_leak.pl`, `/tmp/anon_refcount{2,3,4}.pl`, `/tmp/dbic_like2.pl`) all pass. Only the full DBIC pattern leaks, because real DBIC code paths create JVM temporaries via overloaded comparisons, accessor chains, method resolution, etc.

#### Why t/storage/txn_scope_guard.t test 18 Is Blocked

Test requires DESTROY resurrection semantics: a strong ref to the object escapes DESTROY via `@DB::args` capture in a `$SIG{__WARN__}` handler. When that ref is later released, Perl calls DESTROY a *second* time; DBIC's `detected_reinvoked_destructor` emits `Preventing *MULTIPLE* DESTROY()` warning.

Attempted fix (Fix 10n attempt #2): Set `refCount = 0` during DESTROY body (not MIN_VALUE), track `currentlyDestroying` flag to guard re-entry, detect resurrection by checking `refCount > 0` post-DESTROY.

**Failure mode**: `my $self = shift` inside DESTROY body increments `refCount` to 1 via `setLargeRefCounted` when `$self` is assigned. When DESTROY returns, `$self` is a Java local that goes out of scope without triggering a corresponding decrement (PerlOnJava lexicals don't hook scope-exit decrements for scalar copies). Post-DESTROY `refCount=1` → false resurrection detection → loops indefinitely on File::Temp DESTROY during DBIC test loading.

Root cause: PerlOnJava's selective refCount scheme can't accurately track the net delta from a DESTROY body, because lexical assignments increment but lexical destruction doesn't always decrement.

#### What Would Fix Both

Either:
1. **True reachability-based GC** — mark from symbol-table roots on demand, clear weak refs for unreachable objects. Expensive but matches Perl's model exactly.
2. **Accurate lexical decrement at scope exit** — audit every `my $x = <ref>` path to ensure scope exit fires a matching decrement. Large, risky refactor.

See [`dev/design/refcount_alignment_plan.md`](../design/refcount_alignment_plan.md) for a phased plan that implements both.

Deferred until such architectural work becomes practical.

### Historical notes (previously attempted)

1. **visit_refs / LeakTracer instrumentation** — ran diagnostics, identified parent hash refCount inflation as the blocker.
2. **`createReference()` audit** — Fixed: Storable, DBI. Other deserializers (JSON, XML::Parser) don't appear in the DBIC leak pattern.
3. **Targeted refcount inflation sources** — function-arg copies tracked via `originalArgsStack` (Fix 10l), @DB::args preservation works; but inflation in `map`/`grep`/`keys` temporaries remains.

### Selective Refcounting Internals (reference)

**States**: `-1`=untracked; `0`=tracked, 0 counted refs; `>0`=N counted refs; `-2`=WEAKLY_TRACKED; `MIN_VALUE`=DESTROY called.

**Tracking activation**: `[...]`/`{...}` → refCount=0; `\@arr`/`\%hash` → refCount=0 + localBindingExists=true; `bless` → refCount=0; `weaken()` on untracked non-CODE → WEAKLY_TRACKED.

**Increment/decrement**: `setLargeRefCounted()` on ref assignment when refCount≥0; marks scalar `refCountOwned=true`. Decrement at overwrite or `scopeExitCleanup` → `deferDecrementIfTracked` → `flush()`.

**END-time order**: main returns → `flushDeferredCaptures` → `flush()` → `clearRescuedWeakRefs` → `clearAllBlessedWeakRefs` → END blocks.

**`Internals::SvREFCNT`**: `refCount>=0` → actual; `<0` → 1; `MIN_VALUE` → 0.

### Key Code Locations

| File | Method | Relevance |
|------|--------|-----------|
| `RuntimeScalar.java` | `setLargeRefCounted()` | Increment/decrement |
| `RuntimeScalar.java` | `scopeExitCleanup()` | Lexical cleanup at scope exit |
| `RuntimeScalar.java` | `toStringLarge()` | Overload `""` self-recursion guard |
| `MortalList.java` | `deferDecrementIfTracked()` | Defers decrement to flush |
| `MortalList.java` | `scopeExitCleanupHash()` | Hash value cascade |
| `MortalList.java` | `flush()` | Processes pending decrements |
| `DestroyDispatch.java` | `callDestroy()` | Fires DESTROY / clears weak refs |
| `WeakRefRegistry.java` | `weaken()` | WEAKLY_TRACKED transition |
| `WeakRefRegistry.java` | `clearAllBlessedWeakRefs()` | END-time sweep (all objects) |
| `RuntimeHash.java` | `createReference()` / `createAnonymousReference()` | Named vs anonymous hash ref creation |
| `RuntimeArray.java` | `createReference()` / `createAnonymousReference()` | Named vs anonymous array ref creation |
| `RuntimeCode.java` | `pushArgs` + `originalArgsStack` | @DB::args snapshot preservation |
| `Overload.java` | `stringify()` | Overload `""` recursion depth guard |
| `CustomFileChannel.java` | `flock()` + `sharedLockRegistry` | POSIX-compatible multi-shared-lock |
| `SystemOperator.java` | `fork()` | Test-safe skip + EAGAIN errno |
| `Base.java` | `importBase()` | `@ISA` / `$VERSION` loaded-check |
| `Internals.java` | `svRefcount()` | Internals::SvREFCNT impl |

---

## Architecture Reference

- `dev/architecture/weaken-destroy.md` — refCount state machine, MortalList, WeakRefRegistry
- `dev/design/destroy_weaken_plan.md` — DESTROY/weaken implementation plan (PR #464)
- `dev/sandbox/destroy_weaken/` — DESTROY/weaken test sandbox
- `dev/patches/cpan/DBIx-Class-0.082844/` — applied patches for txn_scope_guard

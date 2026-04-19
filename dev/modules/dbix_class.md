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
| `t/storage/txn_scope_guard.t` | 1 (test 18) | Needs DESTROY resurrection semantics (strong ref via @DB::args after MIN_VALUE). Tried refCount-reset approach — caused infinite DESTROY loops when __WARN__ handler re-triggers captures. Needs architectural redesign (separate "destroying" state from MIN_VALUE sentinel) |

`t/storage/txn.t` — **FIXED** (90/90 pass) via Fix 10m (eq/ne fallback semantics).

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

Both remaining failures (t/52leaks.t tests 12-18 and t/storage/txn_scope_guard.t test 18) hit **fundamental limitations** of PerlOnJava's cooperative refCounting that can't be solved without a major architectural change:

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

Root cause: PerlOnJava's cooperative refCount scheme can't accurately track the net delta from a DESTROY body, because lexical assignments increment but lexical destruction doesn't always decrement.

#### What Would Fix Both

Either:
1. **True reachability-based GC** — mark from symbol-table roots on demand, clear weak refs for unreachable objects. Expensive but matches Perl's model exactly.
2. **Accurate lexical decrement at scope exit** — audit every `my $x = <ref>` path to ensure scope exit fires a matching decrement. Large, risky refactor.

Deferred until such architectural work becomes practical.

### Historical notes (previously attempted)

1. **visit_refs / LeakTracer instrumentation** — ran diagnostics, identified parent hash refCount inflation as the blocker.
2. **`createReference()` audit** — Fixed: Storable, DBI. Other deserializers (JSON, XML::Parser) don't appear in the DBIC leak pattern.
3. **Targeted refcount inflation sources** — function-arg copies tracked via `originalArgsStack` (Fix 10l), @DB::args preservation works; but inflation in `map`/`grep`/`keys` temporaries remains.

### Cooperative Refcounting Internals (reference)

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

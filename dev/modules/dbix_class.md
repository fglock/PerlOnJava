# DBIx::Class Fix Plan

**Module**: DBIx::Class 0.082844 (installed via `jcpan`)
**Branch**: `feature/dbix-class-destroy-weaken`  |  **PR**: https://github.com/fglock/PerlOnJava/pull/485

## Documentation Policy

Every non-trivial code change MUST be documented: what problem it solves, why this approach, what would break if removed.

## Installation & Paths

| Path | Contents |
|------|----------|
| `~/.perlonjava/lib/` | Installed modules (`@INC` entry) |
| `~/.perlonjava/cpan/build/DBIx-Class-0.082844-NN/` | Build dir with tests (use latest NN) |

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
    timeout 120 "$JPERL" -Iblib/lib -Iblib/arch "$t" > /tmp/dbic_suite/$(echo "$t" | tr '/' '_' | sed 's/\.t$//').txt 2>&1
done
# Summary
for f in /tmp/dbic_suite/*.txt; do
    notok=$(grep -c "^not ok " "$f" 2>/dev/null); notok=${notok:-0}
    [ "$notok" -gt 0 ] && echo "FAIL($notok): $(basename $f .txt)"
done | sort
```

---

## Current Test Results (2026-04-13)

| Category | Count |
|----------|-------|
| Test files | **281** |
| Assertions | **12,361 OK / 3 not-ok** (excl TODO) — **99.98% pass** |
| GC-only failures | **0 files** |
| TODO failures | **37 assertions** (upstream expected) |
| Real failures | **3 assertions** in 3 files (see below) + **7 in t/52leaks.t** |

### Remaining Failures

| File | Count | Root Cause |
|------|-------|------------|
| t/52leaks.t | 7 | Tests 12-18: refCount inflation prevents DESTROY — see §Fix 10 |
| t/cdbi/02-Film.t | 2 | DESTROY timing — dirty warning doesn't fire at scope exit |
| t/60core.t | 1 | Cached statement Active — cursor DESTROY doesn't fire for method-chain temporaries |
| t/storage/txn_scope_guard.t | 1 | `@DB::args` not populated in non-debug mode (low priority) |

---

## Completed Fixes

| Fix | What | Key Insight |
|-----|------|-------------|
| 1 | LIFO scope exit + rescue detection | `LinkedHashMap` for declaration order; detect `$self` rescue in DESTROY |
| 2 | Deferred weak-ref clearing for rescued objects | Can't clear immediately — sibling ResultSources still need weak back-refs |
| 3 | DBI `RootClass` attribute for CDBI compat | Re-bless handles into `${RootClass}::db/st` |
| 4 | `clearAllBlessedWeakRefs` + exit path | END-time sweep for all blessed objects; also run on `exit()` |
| 5 | Auto-finish cached statements | `prepare_cached` should `finish()` Active reused sth |
| 6 | `next::method` always uses C3 | Perl 5 always uses C3 regardless of class MRO setting |
| 7 | Stash delete weak ref clearing + B::REFCNT fix | `deleteGlob()` triggers clearWeakRefs; B::SV subtract 1 for hash slot inflation |
| 8 | DBI BYTE_STRING + utf8::decode conditional | Match Perl 5 DBD::SQLite byte-string semantics |
| 9 | DBI UTF-8 round-trip + ClosedIOHandle | Proper UTF-8 encode/decode for JDBC; dup of closed handle returns undef |
| 10a | Clear weak refs when `localBindingExists` blocks callDestroy | In `flush()` at refCount 0 with weak refs — satisfies leak tracer |
| 10d | `clearAllBlessedWeakRefs` clears ALL objects | END-time safety net no longer restricted to blessed |
| 10e | `createAnonymousReference()` for Storable/deserializers | Storable::dclone / deserializers produced hashes with `localBindingExists=true` (like named `\%h`). Fixed to use new anonymous-ref helper. Doesn't close 52leaks gap but is semantically correct |
| 10f | Cascade scope-exit cleanup when weak refs exist | `scopeExitCleanupHash/Array` skipped walks when `!blessedObjectExists` — missed unblessed-data-with-weak-refs case. Added `WeakRefRegistry.weakRefsExist` fast-path flag so cascade runs whenever weak refs exist |

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

---

## Fix 10: t/52leaks.t tests 12-18 — IN PROGRESS

### Failure Inventory

| Test | Object | refcnt | Category |
|------|--------|--------|----------|
| 12 | `ARRAY \| basic random_results` | 1 | Unblessed, birth-tracked |
| 13-15 | `DBICTest::Artist` / `DBICTest::CD` | 2 | Blessed row objects |
| 16 | `ResultSource::Table` (artist) | 2 | Blessed ResultSource |
| 17 | `ResultSource::Table` (artist) | 5 | Blessed ResultSource |
| 18 | `HASH \| basic rerefrozen` | 0 | Unblessed, birth-tracked |

All 7 fail at line 526 `assert_empty_weakregistry` — weak refs still `defined`.

### Key Timing Constraint

The assertion runs **during test execution**, not in an END block. `clearAllBlessedWeakRefs()` (END-time sweep) is too late.

```
Line 404: }                              ← block scope closes (should release strong refs)
Line 526: assert_empty_weakregistry(...)  ← check weak refs ARE undef
...
END time: clearAllBlessedWeakRefs()      ← TOO LATE
```

### Root Cause: Parent Container Inflation

`$base_collection` (parent anonymous hash) has refCount inflated by JVM temporaries from:
- `visit_refs()` deep walk (passes hashref as function arg)
- `populate_weakregistry()` + hash access temporaries
- `Storable::dclone` internals
- `$fire_resultsets->()` with `map`/`push`

When scope exits, scalar releases 1 reference but hash stays at refCount > 0. `callDestroy` never fires → `scopeExitCleanupHash` never walks elements → weak refs persist.

**Implication**: Fixes that work at callDestroy/scopeExit time for the parent hash are blocked because it never dies.

### Diagnostics Performed (2026-04-18)

Reproducer: `/tmp/dbic_like.pl` — 4 leaks in jperl vs 0 in Perl.

**Key diagnostic finding**: `B::svref_2object($x)->REFCNT` returns `Internals::SvREFCNT($self->{ref})` which inflates by `+1` because B::SV stores `$ref` in a blessed hash slot (triggers `setLargeRefCounted` increment). So the "refcnt N" values shown in DBIC leak dumps are always real-refCount + 1 (for tracked objects):
- "refcnt 2" = real refCount 1
- "refcnt 5" = real refCount 4
- "refcnt 0" = real refCount MIN_VALUE (DESTROY called but weak ref not cleared — still live JVM object)

**Unicode angle confirmed irrelevant**: t/52leaks.t uses only ASCII column values, table names, and registry keys. No utf8/encoding issues involved. See `dev/modules/dbix_class.md` Fix 8/9 for DBI UTF-8 handling.

### Next Steps

1. **Remaining DBIC 52leaks tests 12-18** — Our minimal reproducers no longer leak, even with blessed objects + circular refs + Storable. Yet tests 12-18 still fail. The gap must be in DBIC-specific patterns not yet captured:
   - `visit_refs` in `DBICTest::Util::LeakTracer` — deep walk that may inflate refcounts
   - DBIC ResultSource's complex back-reference chain (Schema ↔ Storage ↔ DBI ↔ Source)
   - `BlockRunner` objects with Moo-generated accessors
   - `$fire_resultsets->()` closures that capture result rows

   Approach: Add temporary diagnostic logging that dumps `refCount` + `localBindingExists` + `weakRefs.size()` for each leaked object at assertion time, to see which cleanup path should have fired.

2. **Audit `createReference()` vs `createAnonymousReference()` call sites**
   - Fixed: Storable (dclone + deserializer + YAML)
   - To audit: JSON deserializer, XML parser, DBI bind/fetch, other CPAN-facing deserializers that return anonymous data

3. **Hash merge inflation investigation**: `%$base = (%$base, foo => dclone($base))` showed weirdness earlier. Re-verify now that Fix 10e/10f are in place. If still an issue, investigate `HashOperators.setFromList` / flat-list building.

4. **Refcount inflation audit**: The conceptual root cause for tests 12-18 is still that the parent `$base_collection` hash's refCount is inflated by JVM temporaries. Identify specific inflation sources (function args, map/grep temporaries) and fix — but only if we can do so without destabilizing real Perl semantics.

### Cooperative Refcounting Internals (reference)

**States**: `-1`=untracked; `0`=tracked, 0 counted refs; `>0`=N counted refs; `-2`=WEAKLY_TRACKED; `MIN_VALUE`=DESTROY called.

**Tracking activation**: `[...]`/`{...}` → refCount=0; `\@arr`/`\%hash` → refCount=0 + localBindingExists=true; `bless` → refCount=0; `weaken()` on untracked non-CODE → WEAKLY_TRACKED.

**Increment/decrement**: `setLargeRefCounted()` on ref assignment to tracked; marks scalar `refCountOwned=true`. Decrement at overwrite or at `scopeExitCleanup` → `deferDecrementIfTracked` → `flush()`.

**END-time order**: main returns → `flushDeferredCaptures` → `flush()` → `clearRescuedWeakRefs` → `clearAllBlessedWeakRefs` → END blocks.

**`Internals::SvREFCNT`**: `refCount>=0` → actual; `<0` (untracked/WEAKLY) → 1; `MIN_VALUE` → 0.

### Key Code Locations

| File | Method | Relevance |
|------|--------|-----------|
| `RuntimeScalar.java:908` | `setLargeRefCounted()` | Increment/decrement |
| `RuntimeScalar.java:2160` | `scopeExitCleanup()` | Lexical cleanup at scope exit |
| `MortalList.java:145` | `deferDecrementIfTracked()` | Defers decrement to flush |
| `MortalList.java:237` | `scopeExitCleanupHash()` | Hash value cascade at scope exit |
| `MortalList.java:482` | `flush()` | Processes pending decrements |
| `DestroyDispatch.java:82` | `callDestroy()` | Fires DESTROY / clears weak refs |
| `WeakRefRegistry.java:107` | `weaken()` | WEAKLY_TRACKED transition |
| `WeakRefRegistry.java:215` | `clearAllBlessedWeakRefs()` | END-time sweep (all objects) |
| `RuntimeBase.java:24` | `refCount` | State field |
| `RuntimeHash.java:604` | `createReference()` | For `\%named` — sets `localBindingExists=true` |
| `RuntimeHash.java:638` | `createAnonymousReference()` | For fresh anonymous hashes — no `localBindingExists` |
| `RuntimeArray.java:747` | `createReference()` | For `\@named` — sets `localBindingExists=true` |
| `RuntimeArray.java:778` | `createAnonymousReference()` | For fresh anonymous arrays — no `localBindingExists` |
| `Internals.java:79` | `svRefcount()` | Internals::SvREFCNT impl |

---

## Architecture Reference

- `dev/architecture/weaken-destroy.md` — refCount state machine, MortalList, WeakRefRegistry
- `dev/design/destroy_weaken_plan.md` — DESTROY/weaken implementation plan (PR #464)
- `dev/sandbox/destroy_weaken/` — DESTROY/weaken test sandbox
- `dev/patches/cpan/DBIx-Class-0.082844/` — applied patches for txn_scope_guard

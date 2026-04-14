# DBIx::Class Fix Plan

## Overview

**Module**: DBIx::Class 0.082844 (installed via `jcpan`)
**Branch**: `feature/dbix-class-destroy-weaken`
**PR**: https://github.com/fglock/PerlOnJava/pull/485

## IMPORTANT: Documentation Policy

**Every code change MUST be documented with detailed comments explaining WHY the code
exists.** Every non-trivial block should explain: what problem it solves, why this
approach was chosen, what would break if removed.

## Installation & Paths

| Path | Contents |
|------|----------|
| `/Users/fglock/.perlonjava/lib/` | Installed modules (`@INC` entry) |
| `/Users/fglock/.perlonjava/cpan/build/DBIx-Class-0.082844-NN/` | Build dirs with test files (use latest NN) |

Find latest build dir:
```bash
DBIC_BUILD=$(ls -d /Users/fglock/.perlonjava/cpan/build/DBIx-Class-0.082844-* 2>/dev/null | grep -v yml | sort -t- -k5 -n | tail -1)
```

## How to Run the Suite

```bash
cd /Users/fglock/projects/PerlOnJava3 && make
DBIC_BUILD=$(ls -d /Users/fglock/.perlonjava/cpan/build/DBIx-Class-0.082844-* 2>/dev/null | grep -v yml | sort -t- -k5 -n | tail -1)
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
    ok=$(grep -c "^ok " "$f" 2>/dev/null); ok=${ok:-0}
    notok=$(grep -c "^not ok " "$f" 2>/dev/null); notok=${notok:-0}
    [ "$notok" -gt 0 ] && echo "FAIL($notok): $(basename $f .txt)"
done | sort
```

---

## Current Test Results (2026-04-13)

| Category | Count | Notes |
|----------|-------|-------|
| Total test files | **281** | |
| Total assertions | **12,361 OK / 3 not-ok** (excl TODO) | **99.98% pass rate** |
| GC-only failures | **0 files** | Fixed by Fixes 1-4 |
| TODO failures | **37 assertions** | Upstream expected failures |
| Real failures | **3 assertions** in 3 files | See below |

### Remaining 3 Non-GC Failures

| File | Failures | Root Cause |
|------|----------|------------|
| t/cdbi/02-Film.t | 2 | DESTROY timing — dirty object warning doesn't fire at scope exit |
| t/60core.t | 1 | Cached statement Active — cursor DESTROY doesn't fire for method-chain temporaries |
| t/storage/txn_scope_guard.t | 1 | `@DB::args` not populated in non-debug mode (low priority) |

---

## Completed Fixes (1-9)

| Fix | What | Key Insight |
|-----|------|-------------|
| 1 | LIFO scope exit + rescue detection | `LinkedHashMap` for declaration order; detect `$self` rescue in DESTROY |
| 2 | Deferred weak-ref clearing for rescued objects | Can't clear immediately — sibling ResultSources still need weak back-refs |
| 3 | DBI `RootClass` attribute for CDBI compat | Re-bless handles into `${RootClass}::db/st` |
| 4 | `clearAllBlessedWeakRefs` + exit path | END-time sweep for all blessed objects; also run on `exit()` |
| 5 | Auto-finish cached statements (LOW PRIORITY) | `prepare_cached` should `finish()` Active reused sth |
| 6 | `next::method` always uses C3 | Perl 5 always uses C3 regardless of class MRO setting |
| 7 | Stash delete weak ref clearing + B::REFCNT fix | `deleteGlob()` triggers clearWeakRefs; B::SV subtract 1 for hash slot inflation |
| 8 | DBI BYTE_STRING + utf8::decode conditional | Match Perl 5 DBD::SQLite byte-string semantics |
| 9 | DBI UTF-8 round-trip + ClosedIOHandle | Proper UTF-8 encode/decode for JDBC; dup of closed handle returns undef |

---

## What Didn't Work (avoid re-trying)

| Approach | Why it failed |
|----------|---------------|
| `System.gc()` before END assertions | Advisory, no guarantee of collection |
| `releaseCaptures()` on ALL unblessed containers | Cooperative refCount falsely reaches 0 via stash refs; caused Moo infinite recursion |
| Decrement refCount for captured blessed refs at inner scope exit | Breaks `destroy_collections.t` test 20 — closures in outer scopes legitimately keep objects alive |
| `git stash` for testing alternatives | **Lost completed work** — never use git stash |
| Setting rescued object `refCount = 1` instead of `-1` | Causes infinite DESTROY loops: inflated refcounts mean rescue ALWAYS triggers |
| Cascading cleanup after rescue | Destroys Schema internals (Storage, DBI::db) that the rescued Schema still needs |
| Call `clearAllBlessedWeakRefs` earlier | Can't call during test execution without knowing which scope exits are "significant" |
| Use `WEAKLY_TRACKED` for birth-tracked objects | Birth-tracked objects (refCount >= 0) don't enter the WEAKLY_TRACKED path in `weaken()` |
| Decrement refCount for WEAKLY_TRACKED in `setLargeRefCounted` | WEAKLY_TRACKED objects have inaccurate refCounts; false-zero triggers |
| Hook into `assert_empty_weakregistry` via Perl code | Can't modify CPAN test code per project rules |
| `deepClearAllWeakRefs` in unblessed callDestroy path | Too aggressive — clears weak refs for objects inside dying containers even when those objects are still alive via other strong references. Failed `destroy_anon_containers.t` test 15 |

---

## Fix 10: Leak detection for t/52leaks.t tests 12-18 — IN PROGRESS

### 10.1 Failure Inventory

| Test | Object | refcnt | Category |
|------|--------|--------|----------|
| 12 | `ARRAY \| basic random_results` | 1 | Unblessed, birth-tracked |
| 13 | `DBICTest::Artist` | 2 | Blessed row object |
| 14 | `DBICTest::CD` | 2 | Blessed row object |
| 15 | `DBICTest::CD` | 2 | Blessed row object |
| 16 | `ResultSource::Table` (artist) | 2 | Blessed ResultSource |
| 17 | `ResultSource::Table` (artist) | 5 | Blessed ResultSource |
| 18 | `HASH \| basic rerefrozen` | 0 | Unblessed, birth-tracked |

All 7 fail because their weak refs are still `defined` at line 526.

### 10.2 Test Flow

```
Line 112: {                              ← block scope opens
Line 115:   my $schema = DBICTest->init_schema;
            ...                          ← populate $base_collection hash
Line 276:   push @{$base_collection->{random_results}}, $fire_resultsets->();
Line 282:     local $base_collection->{random_results};
Line 285:     %$base_collection = (..., rerefrozen => dclone(dclone($bc)), ...);
Line 314:   visit_refs(...)              ← deep-walk: register ALL nested objects
Line 402:   populate_weakregistry(... "basic $_") for keys %$base_collection;
Line 404: }                              ← $base_collection goes out of scope
Line 526: assert_empty_weakregistry($weak_registry);  ← ASSERTION (during execution, NOT END)
```

**Key timing**: The assertion runs during normal script execution, before END blocks.
The existing `clearAllBlessedWeakRefs()` runs at END time — too late.

### 10.3 Cooperative Refcounting Internals

#### refCount State Machine
```
-1                = Untracked (default for all objects)
 0                = Tracked, zero counted containers (fresh from bless or anonymous constructor)
>0                = Being tracked; N named-variable containers exist
-2 (WEAKLY_TRACKED) = Has weak refs but strong refs can't be counted accurately
Integer.MIN_VALUE = DESTROY already called (or in progress)
```

#### How Tracking Gets Activated
- `[...]` / `{...}` anonymous constructors → `createReferenceWithTrackedElements` → refCount=0
- `\@array` / `\%hash` named refs → refCount=0 + localBindingExists=true
- `bless` → refCount=0 (or retroactive tracking if already tracked)
- `weaken()` on untracked (refCount==-1) non-CODE → refCount = WEAKLY_TRACKED (-2)

#### Increment/Decrement
- **Increment**: `setLargeRefCounted()` when assigning a ref to tracked object (refCount>=0), marks scalar `refCountOwned=true`
- **Decrement**: `setLargeRefCounted()` when overwriting old ref (if `refCountOwned`); or `scopeExitCleanup()` → `deferDecrementIfTracked()` → `flush()`
- **Fast path**: `set(RuntimeScalar)` skips refcount for non-reference types; `set(int/String/...)` bypasses refcount entirely (scope exit cleanup is safety net)
- **WEAKLY_TRACKED**: Not decremented by `setLargeRefCounted` or `scopeExitCleanup`; only handled by `undefine()`

#### Scope Exit Flow
```
Block scope exits:
  → SCOPE_EXIT_CLEANUP per scalar → RuntimeScalar.scopeExitCleanup()
    → if refCountOwned: MortalList.deferDecrementIfTracked(scalar) → pending.add(base)
  → SCOPE_EXIT_CLEANUP_HASH per hash → MortalList.scopeExitCleanupHash()
    → hash.localBindingExists = false
    → if hash.refCount <= 0: walk values, deferDecrementRecursive for blessed refs
  → Null all JVM slots (makes RuntimeScalar unreachable, NOT set to undef)
  → MortalList.flush() / popAndFlush()
    → for each pending: if --refCount == 0 && !localBindingExists → callDestroy(base)
```

#### END-Time Cleanup Order
```
Main script returns
  → MortalList.flushDeferredCaptures()
    → deferDecrementIfTracked for deferred captures
    → flush() (process decrements, fire DESTROY)
    → DestroyDispatch.clearRescuedWeakRefs() (rescued objects)
    → WeakRefRegistry.clearAllBlessedWeakRefs() (final sweep, blessed only)
  → END blocks run (DBIC leak tracer sees clean state)
```

#### Internals::SvREFCNT
Returns: `refCount >= 0` → actual value; `refCount < 0` (untracked/WEAKLY_TRACKED) → 1; `MIN_VALUE` → 0.

### 10.4 Root Cause Analysis

**Mode A — Unblessed containers (tests 12, 18):**

Birth-tracked via anonymous constructors. `weaken()` sees refCount >= 0 (already tracked),
so WEAKLY_TRACKED is NOT set. They participate in normal cooperative refcounting.

- **Test 18 (HASH, refcnt 0)**: refCount reached 0, but `localBindingExists` may be `true`
  (from `createReferenceWithTrackedElements`), which blocks `callDestroy`. The hash was
  created by `Storable::dclone` — its internal named hashes get `localBindingExists=true`
  but never get `scopeExitCleanupHash` (only called for `my %hash`, not anonymous hashes
  stored in scalars). RefCount stays at 0, `callDestroy` never fires, weak refs persist.

- **Test 12 (ARRAY, refcnt 1)**: One reference not decremented. Could be hash value slot
  in `$base_collection` or a temporary from `keys %$base_collection` / argument passing.

**Mode B — Blessed objects with inflated refcounts (tests 13-17):**

Cooperative refcounts 2-5 when should be 0. Inflation sources:
1. Hash value access temporaries
2. `visit_refs` deep walk (passes objects as function args)
3. `Storable::dclone` internals
4. `$fire_resultsets->()` with `map`/`push`

The inflation prevents refCount from reaching 0, so DESTROY never fires and
`clearWeakRefsTo` is never called before the assertion at line 526.

### 10.5 Proposed Fixes

#### Fix 10a: Clear weak refs when `localBindingExists` blocks callDestroy (LOW RISK)

**Targets**: Test 18 (and potentially 12)

When `flush()` decrements refCount to 0 but `localBindingExists` blocks destruction,
clear weak refs if the object has any registered:

```java
// In MortalList.flush(), inside the localBindingExists branch:
if (base.localBindingExists) {
    if (WeakRefRegistry.hasWeakRefs(base)) {
        WeakRefRegistry.clearWeakRefsTo(base);
    }
}
```

**Risk**: Low — only fires for objects at refCount 0 with `localBindingExists` + weak refs.

#### Fix 10b: Scope exit cascade — clear weak refs for hash values (MEDIUM RISK)

**Targets**: Tests 12-17

In `scopeExitCleanupHash`, when the hash is dying (refCount ≤ 1), clear weak refs
for all values recursively:

```java
if (hash.refCount <= 1) {
    for (RuntimeScalar val : hash.elements.values()) {
        if (val is reference to RuntimeBase with weak refs) {
            WeakRefRegistry.clearWeakRefsTo(referent);
            DestroyDispatch.deepClearWeakRefs(referent); // nested blessed objects
        }
    }
}
```

**Risk**: Medium — could prematurely clear if values are reachable via other paths.

#### Fix 10c: Reduce refCount inflation at the source (HIGH IMPACT, COMPLEX)

**Targets**: Tests 13-17

Investigate specific inflation sources (function arg temporaries, hash value access,
map/grep temporaries) and fix the most impactful ones. Requires tracing with
`setLargeRefCounted` logging.

#### Fix 10d: Extend clearAllBlessedWeakRefs to ALL objects (SAFETY NET)

Remove `blessId != 0` check so END-time sweep clears unblessed containers too.
Doesn't fix the timing issue alone but catches anything else missed.

### 10.6 Implementation Order

1. Run diagnostics (§10.4 investigation) — confirm `localBindingExists` and inflation ✅ Done
2. Fix 10a — low risk, quick, fixes test 18 ✅ Done (commit 9dfe71f)
3. Fix 10d — safety net for unblessed objects ✅ Done (commit 9dfe71f)
4. Fix 10b — scope exit cascade for all tests — **BLOCKED**: see §10.8
5. Fix 10c — reduce inflation (if still needed after 10a+10b)

### 10.8 Key Finding: Parent Container Inflation (2026-04-11)

The root cause of tests 12-18 is that `$base_collection` (the parent anonymous hash)
itself has an inflated refCount from JVM temporaries created by `visit_refs()`,
`populate_weakregistry()`, and hash access operations. When the scope exits, the
scalar's reference is released (decrement by 1), but the hash's refCount remains > 0.
This means `callDestroy` never fires for the parent hash, and consequently
`scopeExitCleanupHash` never walks its elements.

**Implication**: Fixes 10a and 10b cannot help tests 12-17 because the parent container
never dies. The elements' refCounts are never decremented, and the cascade never starts.

**Fix 10a** was implemented but only helps objects that reach refCount 0 in flush() with
`localBindingExists=true`. Test 18 (HASH rerefrozen, refcnt 0) should benefit, but
testing shows it still fails — likely because the parent container's inflation prevents
the rerefrozen hash from ever entering the mortal list.

**Next approach needed**: Fix 10c (reduce refCount inflation) or a new mechanism to
detect "orphaned" containers at scope exit — i.e., containers whose only references
come from JVM temporaries (not live Perl variables). This requires changes to how
`setLargeRefCounted` tracks reference origins.

### 10.7 Key Code Locations

| File | Method | Relevance |
|------|--------|-----------|
| `RuntimeScalar.java:908` | `setLargeRefCounted()` | RefCount increment/decrement |
| `RuntimeScalar.java:2160` | `scopeExitCleanup()` | Lexical cleanup at scope exit |
| `MortalList.java:145` | `deferDecrementIfTracked()` | Defers decrement to flush() |
| `MortalList.java:237` | `scopeExitCleanupHash()` | Hash value cascade at scope exit |
| `MortalList.java:482` | `flush()` | Processes pending decrements |
| `DestroyDispatch.java:82` | `callDestroy()` | Fires DESTROY / clears weak refs |
| `WeakRefRegistry.java:107` | `weaken()` | WEAKLY_TRACKED transition |
| `WeakRefRegistry.java:215` | `clearAllBlessedWeakRefs()` | END-time sweep (all objects, not just blessed) |
| `RuntimeBase.java:24` | `refCount` | -1=untracked, 0+=tracked, -2=WEAKLY_TRACKED |
| `Internals.java:79` | `svRefcount()` | Internals::SvREFCNT impl |

---

## Architecture Reference

- `dev/architecture/weaken-destroy.md` — refCount state machine, MortalList, WeakRefRegistry
- `dev/design/destroy_weaken_plan.md` — DESTROY/weaken implementation plan (PR #464)
- `dev/sandbox/destroy_weaken/` — DESTROY/weaken test sandbox
- `dev/patches/cpan/DBIx-Class-0.082844/` — applied patches for txn_scope_guard

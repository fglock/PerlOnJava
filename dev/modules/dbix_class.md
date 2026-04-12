# DBIx::Class Fix Plan

## Overview

**Module**: DBIx::Class 0.082844  
**Test command**: `./jcpan -t DBIx::Class`  
**Branch**: `feature/dbix-class-destroy-weaken`  
**PR**: https://github.com/fglock/PerlOnJava/pull/485  
**Status**: Phase 14 — GC liveness fixes. 98.7% individual test pass rate (3,365/3,408). Phases 1-13 DONE.

## How to Run the Suite

```bash
cd /Users/fglock/projects/PerlOnJava3 && make

cd /Users/fglock/.perlonjava/cpan/build/DBIx-Class-0.082844-13
JPERL=/Users/fglock/projects/PerlOnJava3/jperl
mkdir -p /tmp/dbic_suite
for t in t/*.t t/storage/*.t t/inflate/*.t t/multi_create/*.t t/prefetch/*.t \
         t/relationship/*.t t/resultset/*.t t/row/*.t t/search/*.t \
         t/sqlmaker/*.t t/sqlmaker/limit_dialects/*.t t/delete/*.t; do
    [ -f "$t" ] || continue
    timeout 120 "$JPERL" -Iblib/lib -Iblib/arch "$t" > /tmp/dbic_suite/$(echo "$t" | tr '/' '_' | sed 's/\.t$//').txt 2>&1
done

for f in /tmp/dbic_suite/*.txt; do
    ok=$(grep -c "^ok " "$f" 2>/dev/null); ok=${ok:-0}
    notok=$(grep -c "^not ok " "$f" 2>/dev/null); notok=${notok:-0}
    [ "$notok" -gt 0 ] && echo "FAIL($notok): $(basename $f .txt)"
done | sort
```

---

## Current Test Results (2026-04-11)

| Category | Count | Notes |
|----------|-------|-------|
| Full pass | 27 | All assertions pass |
| GC-only failures | 146 | Only `Expected garbage collection` failures — real tests all pass |
| Real failures | 25 | Have non-GC `not ok` lines |
| Skip/no output | 43 | No TAP output (skipped, errored, or missing deps) |
| **Total files** | **241** | |
| Total ok assertions | 11,646 | |
| Total not-ok assertions | 746 | Most are GC-related |

**Real failure breakdown** (non-GC not-ok count):
- t/100populate.t (12), t/60core.t (12), t/85utf8.t (9), t/prefetch/count.t (7), t/sqlmaker/order_by_bindtransport.t (7)
- t/storage/dbi_env.t (6), t/row/filter_column.t (6), t/multi_create/existing_in_chain.t (4), t/prefetch/manual.t (4), t/storage/txn_scope_guard.t (4)
- 15 more files with 1-2 real failures each

---

## Phase 14: GC Liveness — Make All 146 GC-Only Tests Pass

### Goal

Every DBIx::Class test that currently passes all real tests but fails GC assertions
at END time should produce zero `not ok` lines. This is the single highest-impact
fix remaining — 146 test files, ~658 assertions.

### What Happens Now

Every test using `DBICTest` registers `$schema->storage` and `$dbh` into a weak
registry via `populate_weakregistry()`. At END time, `assert_empty_weakregistry()`
checks whether those weakrefs have become `undef` (GC'd). They haven't — 3 objects
always survive per test:

1. `DBIx::Class::Storage::DBI::SQLite` — the storage object (refcnt 1, schema => undef)
2. `DBI::db` — the database handle inside storage's `_dbh`
3. `DBIx::Class::Storage::DBI` — same storage, first-seen class name

### Root Cause Analysis

#### Bug A: `B::svref_2object($ref)->REFCNT` method chain leaks refcount

Calling `B::svref_2object($ref)->REFCNT` in a chained expression leaks a refcount
on `$ref`'s referent. The B::SV temporary is a blessed hash created via
`createReferenceWithTrackedElements()` which bumps the inner ref's refcount, but
the temporary is never cleaned up (no `scopeExitCleanup` for JVM locals).

**Impact**: `DBIx::Class::_Util::refcount()` uses this pattern in
`assert_empty_weakregistry`. The leaked refcount prevents objects from reaching 0.

**Fix approach**: Change B.pm to avoid `createReferenceWithTrackedElements()` for
the wrapper hash, or use a simpler non-hash representation.

#### Bug B: File-scoped lexicals not destroyed before END blocks

In Perl 5, file-scoped lexicals are destroyed during the "destruct" phase before
END blocks run. In PerlOnJava, `$schema` remains alive during END, keeping Storage
and DBI handles alive.

**Impact**: Even if Bug A is fixed, objects may survive if `$schema` is the last
strong ref holder and END runs before file-scope cleanup.

**Fix approach**: Implement file-scope lexical cleanup before END block dispatch.

#### Bug C (RESOLVED): Blessed objects without DESTROY skip hash cleanup

Fixed in Step 11.4 (commit `4f1ed14ab`). `doCallDestroy()` now calls
`scopeExitCleanupHash`/`Array` + `flush()` even when no DESTROY method exists.

#### Note: weaken() on temporaries works correctly

Tested `shift->clone->connection(@_)` pattern — weaken() preserves the ref correctly.
The `suppressFlush` fix in `setFromList` (Phase 11.1, commit `d34d2bc4b`) resolved the
premature DESTROY that was clearing weak refs during method chains.

### Implementation Plan

| Step | What | Impact | Difficulty |
|------|------|--------|------------|
| 14.1 | Fix B::svref_2object() refcount leak | Fix refcount() diagnostic accuracy | Easy |
| 14.2 | Implement file-scope lexical cleanup before END | Fix 146 GC-only test files | Medium |
| 14.3 | Re-run full suite | Measure improvement | - |

---

## Remaining Work Items

| # | Work Item | Impact | Status |
|---|-----------|--------|--------|
| 1 | **GC liveness at END** (Phase 14) | 146 files, 658 assertions | IN PROGRESS |
| 2 | **DBI: Statement handle finalization** | 12 assertions, t/60core.t | Investigation in progress |
| 3 | **DBI: Transaction wrapping for bulk populate** | 10 assertions, t/100populate.t | Pending |
| 4 | **DBI: Numeric formatting (10.0 vs 10)** | 6 assertions | **DONE** |
| 5 | **DBI: DBI_DRIVER env var handling** | 6 assertions | **DONE** |
| 6 | **DBI: Overloaded stringification in bind** | 1 assertion | **DONE** |
| 7 | **DBI: Table locking on disconnect** | 1 assertion | Pending |
| 8 | **DBI: HandleError callback** | 1 assertion | **DONE** |
| 9 | **Transaction/savepoint depth tracking** | 4 assertions, txn_scope_guard.t | Pending |
| 10 | **Detached ResultSource (weak ref)** | 5 assertions, order_by_bindtransport.t | Pending |
| 11 | **B::svref_2object refcount leak** | Affects GC accuracy | Part of Phase 14 |
| 12 | **UTF-8 byte-level string handling** | 8+ assertions, t/85utf8.t | Systemic JVM limitation |
| 13 | **Bless/overload performance** | 1 assertion, perf_bug.t | Hard |

### Work Item 2: DBI Statement Handle Finalization

**Impact**: 12 assertions in t/60core.t (tests 82-93) — "Unreachable cached statement still active"

**Root cause**: Prepared statements not finalized when `$sth` goes out of scope.
Cascading DESTROY works for simple cases (Step 11.4), but DBIx::Class Cursor's
DESTROY uses `detected_reinvoked_destructor` which calls `refaddr()` + `weaken()`.
During cascading cleanup, imported function lookup fails:
```
(in cleanup) Undefined subroutine &Cursor::refaddr called at -e line 16.
```
Needs investigation: namespace resolution during cascading DESTROY.

### Work Item 3: Bulk Populate Transactions

**Impact**: 10 assertions in t/100populate.t

**Symptom**: SQL trace expects `BEGIN → INSERT → COMMIT` around `populate()` calls.
`_insert_bulk` / `txn_scope_guard` interaction with transaction depth tracking.

### Work Item 10: Detached ResultSource

**Impact**: 5 assertions in t/sqlmaker/order_by_bindtransport.t

**Symptom**: `Unable to perform storage-dependent operations with a detached result source`.
Schema→Source weak ref cleared prematurely during test setup.

---

## Known Bugs

### `B::svref_2object($ref)->REFCNT` method chain leak

**Workaround**: Store intermediate: `my $sv = B::svref_2object($ref); $sv->REFCNT`

**Root cause**: Temporary blessed hash from `createReferenceWithTrackedElements()` bumps
inner ref's refcount but JVM-local temporary never gets `scopeExitCleanup`. See Phase 14.

### RowParser.pm line 260 crash (post-test cleanup)

`Not a HASH reference` in `_resolve_collapse` — occurs in END blocks with stale data.
Non-blocking: all real tests complete before the crash.

### UTF-8 byte-level strings (systemic)

JVM strings are always Unicode. PerlOnJava doesn't maintain the Perl 5 distinction
between "bytes" (Latin-1) and "characters" (UTF-8 flagged). 8+ assertions in t/85utf8.t.

---

## Tests That Are Legitimately Skipped (43 files — NO ACTION NEEDED)

| Category | Count | Reason |
|----------|-------|--------|
| Missing external DB (MySQL, PG, Oracle, etc.) | 20 | Need `$ENV{DBICTEST_*_DSN}` |
| Missing Perl modules | 14 | Need DateTime::Format::*, SQL::Translator, Moose, etc. |
| No ithread support | 3 | PerlOnJava platform limitation |
| Deliberately skipped by test design | 4 | `is_plain` check, segfault-prone, disabled upstream |
| `wait` operator not implemented | 2 | Only t/52leaks.t and t/746sybase.t |

---

## Dependency Tree

### Runtime Dependencies (ALL PASS)

DBI (>=1.57, bundled JDBC), Sub::Name (>=0.04, bundled Java), Try::Tiny (>=0.07),
Text::Balanced (>=2.00), Moo (>=2.000, v2.005005), Sub::Quote (>=2.006006),
MRO::Compat (>=0.12, v0.15), namespace::clean (>=0.24, v0.27), Scope::Guard (>=0.03),
Class::Inspector (>=1.24), Class::Accessor::Grouped (>=0.10012),
Class::C3::Componentised (>=1.0009), Config::Any (>=0.20), Context::Preserve (>=0.01),
Data::Dumper::Concise (>=2.020), Devel::GlobalDestruction (>=0.09, bundled),
Hash::Merge (>=0.12), Module::Find (>=0.07), Path::Class (>=0.18),
SQL::Abstract::Classic (>=1.91)

### Test Dependencies (ALL PASS)

Test::More (>=0.94), Test::Deep (>=0.101), Test::Warn (>=0.21),
File::Temp (>=0.22), Package::Stash (>=0.28), Test::Exception (>=0.31),
DBD::SQLite (>=1.29, JDBC shim)

---

## Completed Phases (Summary)

### Phase 1: Unblock Makefile.PL (2025-03-31)
Fixed 4 blockers: `strict::bits`, `UNIVERSAL::can` AUTOLOAD filter, `goto &sub`
wantarray + eval `@_` sharing, `%{+{@a}}` parsing.

### Phase 2: Install Dependencies (2025-03-31)
11 pure-Perl modules installed via `./jcpan -fi`.

### Phase 3: DBI Version Detection (2025-03-31)
Added `$VERSION = '1.643'` to DBI.pm.

### Phase 4: DBD::SQLite JDBC Shim (2025-03-31)
Created DSN translation shim, added sqlite-jdbc 3.49.1.0 dependency.

### Phases 4.5-4.8: Parser/Compiler Fixes (2025-03-31)
- 4.5: `CORE::GLOBAL::caller` override bug (Sub::Uplevel)
- 4.6: Stash aliasing glob vivification (Package::Stash::PP)
- 4.7: Mixed-context ternary lvalue assignment (Class::Accessor::Grouped)
- 4.8: `cp` on read-only installed files (ExtUtils::MakeMaker)

### Phase 5: Runtime Fixes (2026-03-31 — 2026-04-02)
58 individual fixes (steps 5.1-5.58) across parser, compiler, interpreter, DBI,
Storable, B module, overload, and more. Went from ~15/65 active tests passing to
96.7% individual test pass rate (8,923/9,231).

Key milestones:
- Steps 5.1-5.12: DBI core functionality (bind_columns, column_info, etc.)
- Steps 5.13-5.16: Transaction handling (AutoCommit, BEGIN/COMMIT/ROLLBACK)
- Steps 5.17-5.24: Parser/compiler fixes ($^S, MODIFY_CODE_ATTRIBUTES, @INC CODE refs)
- Steps 5.25-5.37: JDBC errors, Storable hooks, //= short-circuit, parser disambiguation
- Steps 5.38-5.56: SQL counter, multi-create FK, Storable binary, DBI Active flag lifecycle
- Steps 5.57-5.58: Post-rebase regressions, pack/unpack 32-bit

### Phase 6: DBI Statement Handle Lifecycle (2026-04-02)
Fixed sth Active flag: false after prepare, true after execute with results, false
on fetch exhaustion. t/60core.t: 45→12 cached stmt failures.

### Phases 9-11: DESTROY/weaken Integration (2026-04-10 — 2026-04-11)
- 9.1: Fixed interpreter fallback regressions (ClassCastException, ConcurrentModificationException)
- 10.1: Bundled Devel::GlobalDestruction, DBI::Const::GetInfoType
- 11.1: `suppressFlush` in `setFromList` — fixed premature DESTROY during `clone->connection` chain
- 11.4: Blessed objects without DESTROY now cascade cleanup to hash elements

### Phase 12: DBI Fixes (2026-04-11)
Work Items 4 (numeric formatting), 5 (DBI_DRIVER), 6 (overloaded stringify), 8 (HandleError) — all DONE.

### Phase 13: DESTROY-on-die (2026-04-11)
New `MyVarCleanupStack` for exception-path cleanup of `my` variables. Registers
every `my` variable at ASTORE. `RuntimeCode.apply()` catches exceptions and calls
`unwindTo()` + `flush()`. Also: void-context DESTROY flush in all 3 `apply()` overloads.
DBI lifecycle fixes: localBindingExists, finish(), circular ref break.

---

## Architecture Reference

- `dev/architecture/weaken-destroy.md` — refCount state machine, MortalList, WeakRefRegistry, scopeExitCleanup
- `dev/design/destroy_weaken_plan.md` — DESTROY/weaken implementation plan (PR #464)
- `dev/sandbox/destroy_weaken/destroy_no_destroy_method.t` — blessed-no-DESTROY cleanup test (13 tests)
- `dev/modules/moo_support.md` — Moo support
- `dev/modules/cpan_client.md` — jcpan CPAN client
- `docs/guides/database-access.md` — JDBC database guide

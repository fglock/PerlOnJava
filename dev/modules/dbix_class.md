# DBIx::Class Fix Plan

## Overview

**Module**: DBIx::Class 0.082844  
**Test command**: `./jcpan -t DBIx::Class`  
**Branch**: `feature/dbix-class-destroy-weaken`  
**PR**: https://github.com/fglock/PerlOnJava/pull/485  
**Status**: Phases 1-14 DONE. Three work directions remain: GC liveness, real test failures, `wait` operator.

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

---

## Direction 1: GC Test Failures (146 files, ~658 assertions)

### Symptom
```
not ok - Expected garbage collection of DBICTest::Schema=HASH(0x...)
not ok - Expected garbage collection of DBIx::Class::Storage::DBI::SQLite=HASH(0x...)
not ok - Expected garbage collection of DBI::db=HASH(0x...)
```

The `assert_empty_weakregistry()` END block checks that weak refs became `undef` (object was
GC'd). On PerlOnJava, objects remain alive because cooperative refcounts never reach 0.

### Root Cause Analysis

**Bug A (primary): `B::svref_2object($ref)->REFCNT` leaks refcount**

The leak chain:
1. `B::SV->new($ref)` creates `bless { ref => $ref }, 'B::SV'`
2. The `{ ref => $ref }` hash literal goes through `createReferenceWithTrackedElements()`
3. This calls `incrementRefCountForContainerStore($ref)` → bumps the **referent's** refCount
4. The B::SV hash is a JVM-local temporary, never stored in a `my` variable
5. `scopeExitCleanup()` never fires on it → the refCount increment is **never reversed**

Impact: `DBIx::Class::_Util::refcount()` calls `B::svref_2object($ref)->REFCNT` in
`assert_empty_weakregistry`. Each call leaks +1 refCount on the inspected object. Since the
leak tracer calls this on every registered object at END time, ALL objects get inflated
refcounts and ALL GC assertions fail.

**Bug B (secondary): File-scoped lexicals and shutdown ordering**

The shutdown sequence is correct: `MortalList.flush()` → `runEndBlocks()` →
`GlobalDestruction.runGlobalDestruction()`. But the flush only triggers DESTROY for objects
whose cooperative refcount reaches 0. With Bug A inflating refcounts, the flush is ineffective.

Once Bug A is fixed, this mechanism should work. If not, the infrastructure already exists:
`SCOPE_EXIT_CLEANUP` opcodes, `MyVarCleanupStack`, `ScopedSymbolTable.getMyScalarIndicesInScope()`.

### Fix Plan

| Step | What | Difficulty | Impact |
|------|------|------------|--------|
| **GC-1** | Fix `B::SV::new` to not store `$ref` in tracked hash | Easy | Fixes all 658 GC assertions |
| **GC-2** | Re-run full suite, measure improvement | - | Verify Bug A was the sole cause |
| **GC-3** | If gaps remain, investigate file-scope lexical destruction | Medium | Fix remaining GC failures |

**Step GC-1 options** (in order of preference):
1. Don't store `$ref` in the B::SV hash — capture it via closure or store as refaddr integer
2. Use a non-tracked hash (skip `createReferenceWithTrackedElements`)
3. Store REFCNT value eagerly in a plain scalar that doesn't hold a reference

**What didn't work / what to avoid**:
- The B::SV wrapper hash must NOT go through `createReferenceWithTrackedElements()` with a
  reference value as an element — this is the fundamental leak mechanism
- Workaround of storing intermediate `my $sv = B::svref_2object($ref); $sv->REFCNT` doesn't
  help because `$sv` still holds the leaking B::SV hash
- `System.gc()` is advisory — can't guarantee collection before assertion time; this is NOT
  a viable fix path

### Issue 15.1: `in_global_destruction` Bareword Error During Cleanup

**Symptom**: 278 occurrences per test of:
```
(in cleanup) Bareword "in_global_destruction" not allowed while "strict subs"
```

**Root cause**: `namespace::clean` removes `in_global_destruction` from the
`DBIx::Class::ResultSource` stash at end of compilation. The compiled bytecode should resolve
it via `pinnedCodeRefs`, but the runtime lookup fails during DESTROY in larger tests.

**Investigation needed**:
1. Check if `pinnedCodeRefs` contains `DBIx::Class::ResultSource::in_global_destruction`
2. Check if `()` prototype recognition affects parser resolution (bareword vs sub call)
3. Check if the error correlates with DESTROY firing during `MortalList.flush()` vs
   `GlobalDestruction.runGlobalDestruction()`

**Fix approach**: Add logging in `getGlobalCodeRef()` when a bareword error fires for a key
that exists in pinnedCodeRefs. If the key IS there, the bug is in bytecode instruction
selection. If NOT, the bug is in `namespace::clean` interaction with pinnedCodeRefs.

### Issue 15.2: Class::XSAccessor "Attempt to reload" Warning

**Symptom**: `Class::XSAccessor exists but failed to load` — 2 stderr lines per test.

**Root cause**: Failed `require` sets `$INC{...} = undef` instead of deleting the entry.
Second `require` sees poisoned `$INC` entry → "Attempt to reload ... aborted".

**Fix**: In `ModuleOperators.java:861`, change `set undef` to `delete`. This matches Perl 5
behavior where a failed `require` in `eval` doesn't prevent subsequent attempts.

**Difficulty**: Easy. Broadest impact — helps ALL XS modules installed via CPAN that can't load.

---

## Direction 2: Real (Non-GC) Test Failures (25 files, ~91 assertions)

### Category A: DBI Statement Handle Lifecycle — 12 failures (t/60core.t)

After `$or_rs->reset` and `$rel_rs->reset`, cached statement handles remain `Active` because
Cursor DESTROY doesn't fire deterministically on JVM. `detected_reinvoked_destructor` in
Cursor::DESTROY calls `refaddr`/`weaken` which may fail during cascading cleanup.

**Fix options**:
1. **(Proper)** Fix cooperative refcount for Cursor lifecycle — when ResultSet goes out of
   scope, Cursor refcount should reach 0, triggering `__finish_sth`
2. **(Quick)** Add explicit `$sth->finish()` in DBI `fetchrow_arrayref` when result set is
   exhausted (no more rows)
3. **(Workaround)** Auto-finish stale Active statements in `prepare_cached()`

**Priority**: HIGH — 12 failures from single root cause.

### Category B: Transaction Wrapping / TxnScopeGuard DESTROY — 16 failures (2 files)

**t/100populate.t (12)**: `_insert_bulk` uses `txn_scope_guard` for atomicity. Guard DESTROY
should trigger rollback on error, but JVM GC non-determinism means failed bulk inserts don't
rollback → `transaction_depth` stays elevated → `BEGIN`/`COMMIT` disappear from traces.

**Pre-built patches exist** at `dev/patches/cpan/DBIx-Class-0.082844/` (ResultSet.pm.patch,
Storage-DBI.pm.patch) that wrap `txn_scope_guard`-protected code in explicit
`eval { ... } or do { rollback; die }`. **NOT YET APPLIED.**

**t/storage/txn_scope_guard.t (4)**: Guard goes out of scope without `commit()` — expects
DESTROY to fire rollback + warning. Same GC non-determinism root cause.

**Priority**: HIGH for t/100populate.t (patches ready). MEDIUM for txn_scope_guard.t.

### Category C: UTF-8 Byte/Character Distinction — 9 failures (t/85utf8.t)

JVM strings are always Unicode. PerlOnJava tracks `BYTE_STRING` vs `STRING` types but:
- `cmp_ok($bytestream, 'ne', $utf8)` may not see a difference
- JDBC always returns Unicode strings (can't get raw bytes from DB)
- `utf8::is_utf8()` on DB-fetched data always returns true

**Priority**: LOW — systemic JVM limitation. UTF8Columns is deprecated upstream.

### Category D: Premature Schema Detachment — 7 failures (t/sqlmaker/order_by_bindtransport.t)

```perl
my $rs = DBICTest->init_schema->resultset('FourKeys');  # schema is a temporary
```

Schema's cooperative refcount reaches 0 at statement end → ResultSource::DESTROY weakens
`{schema}` → ResultSet finds detached source. In Perl 5, the schema stays alive through
Schema→Source registration strong refs.

**Fix options**:
1. Fix cooperative refcounting so schema temporary doesn't prematurely reach 0 while derived
   ResultSet is still in scope
2. Have ResultSet keep a strong ref to schema (PerlOnJava-specific change)

**Priority**: MEDIUM — 7 failures, broader impact on weak-ref chains.

### Category E: DBI Environment Variables — 6 failures (t/storage/dbi_env.t)

Marked **DONE** in Phase 12. The 6 failures in the snapshot may be stale. **Re-run to verify.**

### Category F: FilterColumn Callback Counting — 6 failures (t/row/filter_column.t)

Exact invocation counts of `filter_from_storage`/`filter_to_storage` callbacks don't match.
Needs investigation with `DBIC_TRACE=1` to compare callback patterns between Perl 5 and
PerlOnJava.

**Priority**: MEDIUM — 6 failures, requires investigation.

### Category G: Upstream TODO Failures — 15 failures (3 files, NO ACTION NEEDED)

- t/prefetch/count.t (7): `local $TODO = "Chaining with prefetch is fundamentally broken"`
- t/multi_create/existing_in_chain.t (4): `todo_skip $TODO_msg`
- t/prefetch/manual.t (4): `local $TODO = "...deprecated..."`

These fail in Perl 5 too. Not PerlOnJava bugs.

### Category H: Remaining 1-2 Failure Files (~20 failures, 15 files)

Need full suite re-run to identify. Many likely share root causes with Categories A-D.

### Recommended Fix Order

| Step | Category | Files | Failures Fixed | Effort |
|------|----------|-------|----------------|--------|
| 1 | B (partial) | t/100populate.t | 10 of 12 | Easy — apply existing patches |
| 2 | E | t/storage/dbi_env.t | 6 | Verify — likely already fixed |
| 3 | A | t/60core.t | 12 | Medium — Cursor DESTROY chain |
| 4 | D | t/sqlmaker/order_by_bindtransport.t | 7 | Medium — schema temporary refcount |
| 5 | B (rest) | t/storage/txn_scope_guard.t | 4 | Medium — TxnScopeGuard DESTROY |
| 6 | F | t/row/filter_column.t | 6 | Medium — investigate callbacks |
| 7 | G | 3 files | 15 | None — upstream TODOs |
| 8 | C | t/85utf8.t | 9 | Hard — systemic UTF-8 |
| 9 | H | 15 files | ~20 | TBD — run suite, triage |

**Total fixable** (steps 1-6): ~45 of ~91 real failures.  
**Upstream TODOs** (step 7): 15 — no action needed.  
**Hard/systemic** (steps 8-9): ~31 — require deep changes or investigation.

---

## Direction 3: `wait` Operator

### Current Status: FULLY IMPLEMENTED (commit dddab71e1)

The `wait` operator is implemented across all three backends:
- **Parser**: `ParserTables.java:304` — prototype `""` (no args)
- **JVM backend**: `EmitOperatorNode.java:97` → `WaitpidOperator.waitForChild()`
- **Bytecode backend**: `Opcodes.java:2249` (`WAIT_OP = 468`), `BytecodeInterpreter.java:1791`
- **Interpreter fallback**: `CoreSubroutineGenerator.java:210`
- **Runtime**: `WaitpidOperator.java` (174 lines) — waits for Java-tracked child processes,
  falls back to FFM POSIX `waitpid()` syscall, sets `$?` and `${^CHILD_ERROR_NATIVE}`

### Remaining Work

The `wait` operator itself works, but the two test files that needed it are skipped for
**other reasons**:
- `t/52leaks.t` — needs `fork` (unimplemented on JVM) for leak detection
- `t/746sybase.t` — needs `$ENV{DBICTEST_SYBASE_DSN}` (external Sybase DB)

**No further action needed** for `wait` in the DBIx::Class context.

---

## Known Bugs (reference)

### `B::svref_2object($ref)->REFCNT` method chain leak
Temporary blessed hash from `createReferenceWithTrackedElements()` bumps inner ref's refcount
but JVM-local temporary never gets `scopeExitCleanup`. See Direction 1, Step GC-1.

### RowParser.pm line 260 crash (post-test cleanup)
`Not a HASH reference` in `_resolve_collapse` — occurs in END blocks with stale data.
Non-blocking: all real tests complete before the crash.

### UTF-8 byte-level strings (systemic)
JVM strings are always Unicode. PerlOnJava doesn't maintain Perl 5's distinction between
"bytes" (Latin-1) and "characters" (UTF-8 flagged). See Direction 2, Category C.

### Stale comment in RuntimeIO.java:189
Says "PerlOnJava doesn't implement DESTROY or reference counting" — outdated since PR #464.

---

## Tests That Are Legitimately Skipped (43 files — NO ACTION NEEDED)

| Category | Count | Reason |
|----------|-------|--------|
| Missing external DB (MySQL, PG, Oracle, etc.) | 20 | Need `$ENV{DBICTEST_*_DSN}` |
| Missing Perl modules | 14 | Need DateTime::Format::*, SQL::Translator, Moose, etc. |
| No ithread support | 3 | PerlOnJava platform limitation |
| Deliberately skipped by test design | 4 | `is_plain` check, segfault-prone, disabled upstream |
| Need fork (t/52leaks.t) | 1 | PerlOnJava platform limitation |
| Need external DB (t/746sybase.t) | 1 | Need Sybase DSN |

---

## What Didn't Work (avoid re-trying)

These approaches were tried during Phases 1-14 and either failed or were superseded:

| Approach | Why it didn't work |
|----------|--------------------|
| `System.gc()` before END assertions | Advisory, no guarantee of collection |
| Store intermediate `my $sv = B::svref_2object($ref); $sv->REFCNT` | `$sv` still holds the leaking B::SV hash — doesn't fix the refcount bump |
| `releaseCaptures()` on ALL unblessed containers | False positives — unblessed containers can have cooperative refCount falsely reach 0 via stash refs; caused Moo infinite recursion |
| Using `interpreterFrameIndex` as proxy for lazy CallerStack entries | Frame count doesn't match lazy entry count; fixed with `CallerStack.countLazyFromTop()` |
| `Math.max(callerStackIndex, interpreterFrameIndex)` for caller() skipping | Wrong when interpreter frame count ≠ lazy entry count; replaced with `callerStackIndex + countLazyFromTop(callerStackIndex)` |
| `git stash` for testing alternatives | **Lost completed work** — never use git stash during active debugging |

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

## Completed Phases (1-14 Summary)

| Phase | Date | What | Key Commits |
|-------|------|------|-------------|
| 1 | 2025-03-31 | Unblock Makefile.PL — `strict::bits`, `UNIVERSAL::can`, `goto &sub`, `%{+{@a}}` | - |
| 2 | 2025-03-31 | 11 pure-Perl dependency modules installed | - |
| 3 | 2025-03-31 | DBI version detection (`$VERSION = '1.643'`) | - |
| 4 | 2025-03-31 | DBD::SQLite JDBC shim, sqlite-jdbc 3.49.1.0 | - |
| 4.5-4.8 | 2025-03-31 | Parser/compiler fixes: `CORE::GLOBAL::caller`, stash aliasing, mixed-context ternary, `cp` read-only | - |
| 5 | 2026-03-31 — 2026-04-02 | 58 runtime fixes (5.1-5.58): DBI core, transactions, parser, compiler, Storable, B module, overload. 15→96.7% pass rate (8923/9231) | - |
| 6 | 2026-04-02 | DBI statement handle lifecycle (`Active` flag): t/60core.t 45→12 failures | - |
| 9-11 | 2026-04-10 — 2026-04-11 | DESTROY/weaken integration: interpreter fallback, Devel::GlobalDestruction, `suppressFlush`, cascading cleanup | - |
| 12 | 2026-04-11 | DBI fixes: numeric formatting, DBI_DRIVER, overloaded stringify, HandleError — all DONE | - |
| 13 | 2026-04-11 | DESTROY-on-die: `MyVarCleanupStack`, void-context DESTROY flush, DBI lifecycle fixes | - |
| 14 | 2026-04-12 | stashRefCount — prevent premature weak ref clearing for stash-installed closures. `DBICTest->init_schema()` succeeds, 1275 tests pass | `db846e687`, `ef424f783` |
| — | 2026-04-12 | caller() fix: `countLazyFromTop()` for correct package in `use`/`import` through interpreter | `f08d437f5` |
| — | 2026-04-12 | `wait` operator: full implementation across JVM/bytecode/interpreter backends | `dddab71e1` |

### Phase 5 Key Milestones (for reference)
- 5.1-5.12: DBI core (bind_columns, column_info, etc.)
- 5.13-5.16: Transaction handling (AutoCommit, BEGIN/COMMIT/ROLLBACK)
- 5.17-5.24: Parser/compiler ($^S, MODIFY_CODE_ATTRIBUTES, @INC CODE refs)
- 5.25-5.37: JDBC errors, Storable hooks, //= short-circuit, parser disambiguation
- 5.38-5.56: SQL counter, multi-create FK, Storable binary, DBI Active flag lifecycle
- 5.57-5.58: Post-rebase regressions, pack/unpack 32-bit

### Phase 14 Details (stashRefCount)
- **Problem**: Moo/Sub::Quote infinite recursion — `releaseCaptures()` fired on CODE refs
  whose cooperative refCount falsely reached 0 because stash assignments
  (`*Foo::bar = $coderef`) were invisible to the cooperative refCount mechanism
- **Fix**: Added `stashRefCount` field to `RuntimeCode`; `DestroyDispatch` skips
  `releaseCaptures()` when `stashRefCount > 0`; `releaseCaptures()` only cascades
  `deferDecrementIfTracked` for blessed referents (not unblessed containers)
- **Results**: `DBICTest->init_schema()` succeeds; 1275 functional tests pass; all 42
  `weaken_edge_cases.t` pass

---

## Architecture Reference

- `dev/architecture/weaken-destroy.md` — refCount state machine, MortalList, WeakRefRegistry, scopeExitCleanup
- `dev/design/destroy_weaken_plan.md` — DESTROY/weaken implementation plan (PR #464)
- `dev/sandbox/destroy_weaken/destroy_no_destroy_method.t` — blessed-no-DESTROY cleanup test
- `dev/modules/moo_support.md` — Moo support
- `dev/modules/cpan_client.md` — jcpan CPAN client
- `dev/patches/cpan/DBIx-Class-0.082844/` — unapplied patches for t/100populate.t transaction wrapping

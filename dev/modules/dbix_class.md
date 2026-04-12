# DBIx::Class Fix Plan

## Overview

**Module**: DBIx::Class 0.082844  
**Test command**: `./jcpan -t DBIx::Class`  
**Branch**: `feature/dbix-class-destroy-weaken`  
**PR**: https://github.com/fglock/PerlOnJava/pull/485  
**Status**: Phase 15 — Cleanup noise & GC. Phases 1-13 DONE, Phase 14 stashRefCount fix DONE.

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

## Phase 14 (DONE): stashRefCount — Prevent Premature Weak Ref Clearing

**Completed 2026-04-12.** Commit `db846e687`, `ef424f783`.

### Problem
DBIx::Class/Moo/Sub::Quote infinite recursion caused by premature `releaseCaptures()`
on CODE refs whose cooperative refCount falsely reached 0. Stash assignments
(`*Foo::bar = $coderef`) were invisible to the cooperative refCount mechanism.

### Fix
- Added `stashRefCount` field to `RuntimeCode` tracking glob/stash references
- `DestroyDispatch` skips `releaseCaptures()` when `stashRefCount > 0`
- `releaseCaptures()` only cascades `deferDecrementIfTracked` for blessed referents
  (not unblessed containers whose cooperative refCount can falsely reach 0)
- Increased test JVM heap to 1g in `build.gradle`

### Results
- `DBICTest->init_schema()` succeeds (was infinite recursion)
- 1275 functional tests pass across 30+ test files
- All 42 `weaken_edge_cases.t` pass; `make` passes

---

## Phase 15: Cleanup Noise & GC Liveness

Three categories of test output noise/failures remain. They don't affect functional
correctness but do affect test pass counts and produce confusing stderr output.

### Issue 15.1: `in_global_destruction` Bareword Error During Cleanup

**Symptom**: 278 occurrences per test of:
```
(in cleanup) Bareword "in_global_destruction" not allowed while "strict subs"
  in use at blib/lib/DBIx/Class/ResultSource.pm line 2317, near ";"
```

**Affected code** (`ResultSource.pm:2312-2317`):
```perl
use Devel::GlobalDestruction;   # line 14 — imports in_global_destruction
use namespace::clean;            # line 18 — schedules removal at end of scope
# ...
my $global_phase_destroy;
sub DESTROY {
    return if $global_phase_destroy ||= in_global_destruction;  # line 2317
```

**Analysis**:
- `namespace::clean` removes `in_global_destruction` from the stash at end of compilation
- Compiled bytecode should resolve it via `pinnedCodeRefs` (see `GlobalVariable.java:407-414`)
- Simple reproduction cases work fine — the function resolves correctly
- The error only appears during larger tests (t/60core.t, etc.) with many ResultSource
  objects being destroyed, suggesting a specific interaction with cascading DESTROY,
  mortal flush, or bytecode resolution under load
- The error fires inside DESTROY (the `(in cleanup)` prefix from `DestroyDispatch`)
  meaning the bytecode IS executing the DESTROY method, but the `in_global_destruction`
  call is throwing a compile/resolution error at runtime

**Investigation needed**:
1. Check if the bytecode emitter generates `LOAD_GLOBAL_CODE` for `in_global_destruction`
   or treats it as a bareword when `namespace::clean` has already removed it from the stash
   during the same compilation unit (deferred cleanup via `B::Hooks::EndOfScope`)
2. Check if `pinnedCodeRefs` actually contains
   `DBIx::Class::ResultSource::in_global_destruction` after compilation
3. Test whether the `()` prototype on `in_global_destruction` affects how PerlOnJava's
   parser resolves it (Perl 5 treats `()` prototyped subs as known calls even without parens)
4. Check if the error correlates with DESTROY firing during `MortalList.flush()` vs
   `GlobalDestruction.runGlobalDestruction()`

**Fix approach** (in order of preference):
1. **Fix the resolution bug**: If `pinnedCodeRefs` is correctly populated but runtime
   lookup fails, the bug is in `getGlobalCodeRef()` or the bytecode instruction used.
   Add logging in `getGlobalCodeRef()` when a bareword error is about to fire for a
   key that exists in pinnedCodeRefs.
2. **Ensure prototype recognition**: If the `()` prototype isn't being recognized by
   the parser, `in_global_destruction` may compile as bareword under strict subs.
   Fix: make the parser check the prototype of imported subs before flagging bareword.
3. **Fallback**: Patch `Devel::GlobalDestruction.pm` to install `in_global_destruction`
   as a constant sub (`use constant`-style) which PerlOnJava may handle differently.

**Difficulty**: Medium. Requires understanding the exact point where the resolution fails.

### Issue 15.2: Class::XSAccessor "Attempt to reload" Warning

**Symptom**:
```
Class::XSAccessor exists but failed to load with error: Attempt to reload
  Class/XSAccessor.pm aborted.
Compilation failed in require at Class/XSAccessor.pm
  at /Users/fglock/.perlonjava/lib/Moo/_Utils.pm line 162.
```

**Root cause** — Two-phase loading failure:

1. **First load** (by `Class::Accessor::Grouped` or similar):
   - `require Class/XSAccessor.pm` finds the CPAN XS version in `~/.perlonjava/lib/`
   - `doFile()` sets `$INC{'Class/XSAccessor.pm'}` to the file path
   - Inside the file, `XSLoader::load('Class::XSAccessor')` fails (no Java XS impl)
   - `doFile()` removes `$INC` entry, but `require()` sets `$INC{...} = undef` (line 861
     of `ModuleOperators.java`)

2. **Second load** (by `Moo::_Utils::_maybe_load_module`):
   - `require 'Class/XSAccessor.pm'` finds `$INC{...}` exists with value `undef`
   - PerlOnJava throws `"Attempt to reload Class/XSAccessor.pm aborted"`
   - Moo's error check sees this isn't `"Can't locate ..."` → emits the warning

**`@INC` priority issue**: `~/.perlonjava/lib/` has higher priority than `jar:PERL5LIB`.
A bundled stub in `src/main/perl/lib/Class/XSAccessor.pm` would be shadowed by the
CPAN version.

**Fix approach** (options, in order of preference):

1. **Fix `require` to delete `$INC{...}` on failure** (not set it to `undef`):
   - In `ModuleOperators.java:861`, change `set undef` to `delete`
   - This way the second `require` doesn't see a poisoned entry
   - Moo's `_maybe_load_module` then gets `"Can't locate ..."` on retry → no warning
   - **This is the cleanest fix** — it matches Perl 5 behavior where a failed `require`
     in `eval` doesn't prevent subsequent attempts

2. **Bundled pure-Perl `Class::XSAccessor`** + `%INC` pre-registration:
   - Create `src/main/perl/lib/Class/XSAccessor.pm` implementing accessor generation
     with pure-Perl closures (getters, setters, accessors, predicates, constructors)
   - Make `XSLoader.java` check for a jar-bundled `.pm` fallback before dying
   - High effort but gives Moo/CAG actual XS-equivalent accessors

3. **Delete the CPAN XS version** from `~/.perlonjava/lib/`:
   - Quick workaround: `rm -rf ~/.perlonjava/lib/Class/XSAccessor*`
   - Then `require` genuinely fails with `"Can't locate ..."` → Moo silently falls back
   - Not durable (re-appears if user installs modules with deps on Class::XSAccessor)

**Recommendation**: Fix option 1 first (simplest, broadest impact — helps ALL XS modules
that get installed via CPAN but can't load on JVM). Option 2 as a follow-up for
performance (XSAccessor closures are faster than Moo's generated Perl accessors).

**Difficulty**: Easy (option 1), Hard (option 2).

### Issue 15.3: "Expected garbage collection" Test Failures

**Symptom**: ~658 assertions across 146 test files:
```
not ok - Expected garbage collection of DBIx::Class::Storage::DBI::SQLite=HASH(0x...)
not ok - Expected garbage collection of DBI::db=HASH(0x...)
not ok - Expected garbage collection of DBICTest::Schema=HASH(0x...)
```

**Mechanism** (`t/lib/DBICTest/Util/LeakTracer.pm`):
- `populate_weakregistry($registry, $obj)` — stores a weakened ref keyed by `hrefaddr`
- `assert_empty_weakregistry($registry, $quiet)` — at END time, checks if weak refs
  became `undef` (object was GC'd); objects still alive but not reachable from the
  symbol table are reported as leaks
- All DBICTest-based tests register `$schema->storage`, `$dbh`, and clones automatically
  in `BaseSchema::connection()` and assert in an END block

**Root cause**: Three factors combine to prevent objects from being GC'd before END:

1. **Bug A: `B::svref_2object($ref)->REFCNT` leaks refcount** (pre-existing):
   - Chained `B::svref_2object($ref)->REFCNT` creates a temporary blessed hash via
     `createReferenceWithTrackedElements()` which bumps the inner ref's refcount
   - The JVM-local temporary never gets `scopeExitCleanup` → leaked refcount
   - `DBIx::Class::_Util::refcount()` uses this pattern in `assert_empty_weakregistry`

2. **Bug B: File-scoped lexicals not destroyed before END blocks**:
   - In Perl 5, file-scoped `my $schema` is destroyed during the "destruct" phase
     before END blocks
   - In PerlOnJava, `$schema` remains alive during END → Storage/DBI handles alive
   - The shutdown sequence is: `MortalList.flush()` → `runEndBlocks()` →
     `GlobalDestruction.runGlobalDestruction()` (see `PerlLanguageProvider.java:390-407`)
   - File-scoped lexicals should be cleaned before END blocks, not after

3. **JVM GC non-determinism** (inherent limitation):
   - Even if cooperative refcounting reaches 0, JVM GC is non-deterministic
   - `System.gc()` is advisory — can't guarantee collection before assertion time
   - This is fundamentally different from Perl 5's deterministic refcounting

**Fix approach**:

| Step | What | Impact | Difficulty |
|------|------|--------|------------|
| 15.3a | Fix `B::svref_2object()` refcount leak | Fix `refcount()` accuracy for diagnostics | Easy |
| 15.3b | Implement file-scope lexical cleanup before END | Fix the root cause for most GC tests | Medium |
| 15.3c | Re-run full suite, measure improvement | - | - |

**Step 15.3a**: Change `B.pm` to avoid `createReferenceWithTrackedElements()` for the
wrapper hash, or store the REFCNT value eagerly in a plain scalar that doesn't hold
a reference to the original object.

**Step 15.3b**: In `PerlLanguageProvider.java`, add file-scope lexical cleanup BEFORE
`runEndBlocks()`. This requires tracking which `RuntimeScalar` instances are file-scoped
lexicals and decrementing their refcounts (triggering DESTROY cascades) before END fires.

**Note**: Even with 15.3a+b, some GC assertions may still fail due to JVM GC
non-determinism. In Perl 5, `undef $schema` → immediate DESTROY → immediate DBI handle
close → weak ref cleared. On JVM, even with cooperative refcount reaching 0, the actual
object deallocation happens at JVM GC's discretion.

---

## Remaining Work Items

| # | Work Item | Impact | Status |
|---|-----------|--------|--------|
| 1 | **`in_global_destruction` bareword** (15.1) | 278 stderr warnings per test | Investigation needed |
| 2 | **Class::XSAccessor reload warning** (15.2) | 2 stderr lines per test | Fix `require` %INC behavior |
| 3 | **GC liveness at END** (15.3) | 146 files, 658 assertions | Bug A + Bug B |
| 4 | **DBI: Statement handle finalization** | 12 assertions, t/60core.t | Investigation in progress |
| 5 | **DBI: Transaction wrapping for bulk populate** | 10 assertions, t/100populate.t | Pending |
| 6 | **DBI: Numeric formatting (10.0 vs 10)** | 6 assertions | **DONE** |
| 7 | **DBI: DBI_DRIVER env var handling** | 6 assertions | **DONE** |
| 8 | **DBI: Overloaded stringification in bind** | 1 assertion | **DONE** |
| 9 | **DBI: Table locking on disconnect** | 1 assertion | Pending |
| 10 | **DBI: HandleError callback** | 1 assertion | **DONE** |
| 11 | **Transaction/savepoint depth tracking** | 4 assertions, txn_scope_guard.t | Pending |
| 12 | **Detached ResultSource (weak ref)** | 5 assertions, order_by_bindtransport.t | Pending |
| 13 | **B::svref_2object refcount leak** | Affects GC accuracy | Part of 15.3a |
| 14 | **UTF-8 byte-level string handling** | 8+ assertions, t/85utf8.t | Systemic JVM limitation |
| 15 | **Bless/overload performance** | 1 assertion, perf_bug.t | Hard |
| 16 | **stashRefCount / premature weak ref clearing** | Moo infinite recursion | **DONE** (Phase 14) |

### Work Item 4: DBI Statement Handle Finalization

**Impact**: 12 assertions in t/60core.t (tests 82-93) — "Unreachable cached statement still active"

**Root cause**: Prepared statements not finalized when `$sth` goes out of scope.
Cascading DESTROY works for simple cases (Step 11.4), but DBIx::Class Cursor's
DESTROY uses `detected_reinvoked_destructor` which calls `refaddr()` + `weaken()`.
During cascading cleanup, imported function lookup fails:
```
(in cleanup) Undefined subroutine &Cursor::refaddr called at -e line 16.
```
Needs investigation: namespace resolution during cascading DESTROY.

### Work Item 5: Bulk Populate Transactions

**Impact**: 10 assertions in t/100populate.t

**Symptom**: SQL trace expects `BEGIN → INSERT → COMMIT` around `populate()` calls.
`_insert_bulk` / `txn_scope_guard` interaction with transaction depth tracking.

### Work Item 12: Detached ResultSource

**Impact**: 5 assertions in t/sqlmaker/order_by_bindtransport.t

**Symptom**: `Unable to perform storage-dependent operations with a detached result source`.
Schema→Source weak ref cleared prematurely during test setup.

---

## Known Bugs

### `B::svref_2object($ref)->REFCNT` method chain leak

**Workaround**: Store intermediate: `my $sv = B::svref_2object($ref); $sv->REFCNT`

**Root cause**: Temporary blessed hash from `createReferenceWithTrackedElements()` bumps
inner ref's refcount but JVM-local temporary never gets `scopeExitCleanup`. See Phase 15.3a.

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

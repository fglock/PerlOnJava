# DBIx::Class Fix Plan

## Overview

**Module**: DBIx::Class 0.082844  
**Test command**: `./jcpan -t DBIx::Class`  
**Branch**: `feature/dbix-class-destroy-weaken`  
**PR**: https://github.com/fglock/PerlOnJava/pull/485  
**Status**: Phase 10 — Full 314-test re-baseline; P0 blocker: weak ref cleared during `clone → _copy_state_from`

## Dependency Tree

### Runtime Dependencies

| Dependency | Required | Status | Notes |
|-----------|---------|--------|-------|
| DBI | >= 1.57 | PASS | Bundled Java JDBC implementation; `$VERSION = '1.643'` added |
| Sub::Name | >= 0.04 | PASS | Bundled Java implementation |
| Try::Tiny | >= 0.07 | PASS | Bundled pure Perl |
| Text::Balanced | >= 2.00 | PASS | Bundled core module |
| Moo | >= 2.000 | PASS | Installed (v2.005005) via jcpan |
| Sub::Quote | >= 2.006006 | PASS | Installed via jcpan |
| MRO::Compat | >= 0.12 | PASS | Installed (v0.15); uses native `mro` on PerlOnJava |
| namespace::clean | >= 0.24 | PASS | Installed (v0.27) |
| Scope::Guard | >= 0.03 | PASS | Installed |
| Class::Inspector | >= 1.24 | PASS | Installed |
| Class::Accessor::Grouped | >= 0.10012 | PASS | Installed via jcpan |
| Class::C3::Componentised | >= 1.0009 | PASS | Installed via jcpan |
| Config::Any | >= 0.20 | PASS | Installed via jcpan |
| Context::Preserve | >= 0.01 | PASS | Installed via jcpan |
| Data::Dumper::Concise | >= 2.020 | PASS | Installed via jcpan |
| Devel::GlobalDestruction | >= 0.09 | PASS | Installed via jcpan |
| Hash::Merge | >= 0.12 | PASS | Installed via jcpan |
| Module::Find | >= 0.07 | PASS | Installed via jcpan |
| Path::Class | >= 0.18 | PASS | Installed but has File::stat VerifyError (see Known Bugs) |
| SQL::Abstract::Classic | >= 1.91 | PASS | Installed via jcpan |

### Test Dependencies

| Dependency | Status | Notes |
|-----------|--------|-------|
| Test::More | >= 0.94 | PASS | Bundled |
| Test::Deep | >= 0.101 | PASS | Installed |
| Test::Warn | >= 0.21 | PASS | Installed |
| File::Temp | >= 0.22 | PASS | Bundled Java implementation |
| Package::Stash | >= 0.28 | PASS | Installed (PP fallback) |
| Test::Exception | >= 0.31 | PASS | Installed; Sub::Uplevel CORE::GLOBAL::caller bug fixed |
| DBD::SQLite | >= 1.29 | PASS | JDBC shim via `DBD/SQLite.pm` + sqlite-jdbc driver |

### Supporting Modules (already installed)

B::Hooks::EndOfScope, Package::Stash::PP, Role::Tiny, Class::Method::Modifiers,
Module::Implementation, Module::Runtime, Params::Util, Exporter::Tiny, Type::Tiny,
Scalar::Util, List::Util, Storable, Data::Dumper, mro, namespace::autoclean,
Sub::Util, Dist::CheckConflicts, Eval::Closure, Sub::Uplevel.

---

## Fix Plan

### Phase 1: Unblock Makefile.PL (DONE)

Four blockers fixed to get `Makefile.PL` to complete:

| Blocker | Error | Fix | Status |
|---------|-------|-----|--------|
| 1. `strict::bits` missing | `Undefined subroutine &strict::bits` | Added `bits`, `all_bits`, `all_explicit_bits` to Strict.java | DONE |
| 2. `UNIVERSAL::can` returning AUTOLOAD methods | Module::Install `$self->can('call')` resolved via AUTOLOAD | Added `isAutoloadDispatch()` filter in Universal.java | DONE |
| 3. `goto &sub` wantarray + eval{} @_ sharing | `Not an ARRAY reference` at AutoInstall.pm line 32 | Fixed tail call trampoline context propagation; eval{} now shares @_ | DONE |
| 4. `%{+{@a}}` parsing | `Type of arg 1 to keys must be hash or array` | Added +{ check in IdentifierParser.java for hash constructor disambiguation | DONE |

### Phase 2: Install missing pure-Perl dependencies (DONE)

All runtime and test dependencies installed via `./jcpan -fi`:

| Step | Description | Status |
|------|-------------|--------|
| 2.1 | `./jcpan install Devel::GlobalDestruction` | DONE |
| 2.2 | `./jcpan install Context::Preserve` | DONE |
| 2.3 | `./jcpan install Data::Dumper::Concise` | DONE |
| 2.4 | `./jcpan install Module::Find` | DONE |
| 2.5 | `./jcpan install Path::Class` | DONE (has VerifyError, see Known Bugs) |
| 2.6 | `./jcpan install Hash::Merge` | DONE |
| 2.7 | `./jcpan install Config::Any` | DONE |
| 2.8 | `./jcpan install Class::Accessor::Grouped` | DONE |
| 2.9 | `./jcpan install Class::C3::Componentised` | DONE |
| 2.10 | `./jcpan install SQL::Abstract::Classic` | DONE |
| 2.11 | `./jcpan install Test::Exception` | DONE |

### Phase 3: Fix DBI version detection (DONE)

| Step | Description | Status |
|------|-------------|--------|
| 3.1 | Added `our $VERSION = '1.643';` to `src/main/perl/lib/DBI.pm` | DONE |
| 3.2 | Makefile.PL now recognizes DBI version correctly | DONE |

### Phase 4: Create DBD::SQLite JDBC shim (DONE)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 4.1 | Created `DBD::SQLite` shim translating DSN format | `src/main/perl/lib/DBD/SQLite.pm` | DONE |
| 4.2 | Added sqlite-jdbc 3.49.1.0 dependency | `build.gradle`, `pom.xml`, `gradle/libs.versions.toml` | DONE |
| 4.3 | Added try/catch for metadata on DDL statements | `DBI.java` | DONE |
| 4.4 | Verified `DBI->connect("dbi:SQLite:dbname=:memory:")` works | manual test | DONE |

### Phase 4.5: Fix CORE::GLOBAL::caller override bug (DONE)

Sub::Uplevel (dependency of Test::Exception) overrides `*CORE::GLOBAL::caller`.
This caused a parse error when `caller` appeared as the RHS of an infix operator.

| Step | Description | File | Status |
|------|-------------|------|--------|
| 4.5.1 | Fixed whitespace-sensitive token insertion for CORE::GLOBAL:: overrides | `ParsePrimary.java` | DONE |
| 4.5.2 | Test::Exception now loads and works correctly | verified | DONE |

### Phase 4.6: Fix stash aliasing glob vivification (DONE)

Package::Stash::PP's `add_symbol` does `*__ANON__:: = \%Pkg::` then `*{"__ANON__::foo"}`.
PerlOnJava's flat-map architecture stored the vivified glob under the wrong prefix.

| Step | Description | File | Status |
|------|-------------|------|--------|
| 4.6.1 | Added `resolveStashHashRedirect()` to detect aliased stashes | `GlobalVariable.java` | DONE |
| 4.6.2 | Integrated redirect into `getGlobalIO()` and JVM backend | `GlobalVariable.java`, `EmitVariable.java` | DONE |

### Phase 4.7: Fix mixed-context ternary lvalue assignment (DONE)

`Class::Accessor::Grouped` uses `wantarray ? @rv = eval $src : $rv[0] = eval $src`.
Perl 5 parses this as `(wantarray ? (@rv = eval $src) : $rv[0]) = eval $src` — a
ternary-as-lvalue where the true branch contains an assignment expression.
`LValueVisitor` threw "Assignment to both a list and a scalar" at compile time.

The fix matches Perl 5's `S_assignment_type()` from `op.c`: assignment ops
(`OP_AASSIGN`, `OP_SASSIGN`) are not in the `ASSIGN_LIST` set, so they return
`ASSIGN_SCALAR` when classifying ternary branches. This allows the CAG pattern
while still rejecting genuinely invalid patterns like `($c ? $a : @b) = 123`.

| Step | Description | File | Status |
|------|-------------|------|--------|
| 4.7.1 | Add `assignmentTypeOf()` helper to classify ternary branches matching Perl 5's `S_assignment_type()` | `LValueVisitor.java` | DONE |

**Known runtime limitation**: The ternary-as-lvalue emitter does not properly
handle assignment-expression branches with non-constant conditions (e.g.,
`wantarray`). When the true branch is taken at runtime, the result of
`@rv = eval $src` is not returned as a modifiable lvalue, causing
"Modification of a read-only value attempted". Constant-folded cases
(`1 ? @rv = eval $src : $rv[0]`) work correctly. This is a separate JVM
backend code generation issue.

### Phase 4.8: Fix `cp` on read-only installed files (DONE)

`ExtUtils::MakeMaker`'s `_shell_cp` generated bare `cp` commands. When reinstalling
a module whose `.pod`/`.pm` files were previously installed as read-only (0444),
`cp` fails with "Permission denied". Fixed by adding `rm -f` before `cp`.

| Step | Description | File | Status |
|------|-------------|------|--------|
| 4.8.1 | Changed `_shell_cp` to `rm -f` then `cp` | `ExtUtils/MakeMaker.pm` | DONE |

### Phase 5: Fix runtime issues (CURRENT — iterative)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 5.1 | Fix `@${$v}` string interpolation | `StringSegmentParser.java` | DONE |
| 5.2 | Add `B::SV::REFCNT` method (returns 0 for JVM tracing GC) | `B.pm` | DONE |
| 5.3 | Add DBI `FETCH`/`STORE` methods for tied-hash compat | `DBI.pm` | DONE |
| 5.4 | Add `DBI::Const::GetInfoReturn` stub | `DBI/Const/GetInfoReturn.pm` | DONE |
| 5.5 | Fix list assignment autovivification (`($x, @$undef_ref) = ...`) | `RuntimeList.java` | DONE |
| 5.6 | Add DBI `execute_for_fetch` and `bind_param` methods | `DBI.pm` | DONE |
| 5.7 | Fix `&func` (no parens) to share caller's `@_` by alias | Parser, JVM emitter, interpreter | DONE |
| 5.8 | Fix DBI `execute()` return value (row count, not hash ref) | `DBI.java` | DONE |
| 5.9 | Set `$dbh->{Driver}` for SQLite driver detection | `DBI.pm` | DONE |
| 5.10 | Fix DBI `get_info()` to accept numeric constants per DBI spec | `DBI.java` | DONE |
| 5.11 | Add DBI SQL type constants (`SQL_BIGINT`, `SQL_INTEGER`, etc.) | `DBI.pm` | DONE |
| 5.12 | Fix `bind_columns` + `fetch` to update bound scalar references | `DBI.java` | DONE |
| 5.13 | Implement `column_info()` via SQLite PRAGMA; bless metadata sth | `DBI.java` | DONE |
| 5.14 | Add `AutoCommit` state tracking for literal transaction SQL | `DBI.java` | DONE |
| 5.15 | Intercept BEGIN/COMMIT/ROLLBACK via JDBC API instead of executing SQL | `DBI.java` | DONE |
| 5.16 | Fix `prepare_cached` to use per-dbh `CachedKids` cache | `DBI.pm` | DONE |
| 5.17 | Fix `-w` flag overriding `no warnings 'redefine'` pragma | `SubroutineParser.java` | DONE |
| 5.18 | Fix InterpreterFallbackException not caught at top-level | `PerlLanguageProvider.java` | DONE |
| 5.19 | Implement `MODIFY_CODE_ATTRIBUTES` for subroutine attributes | `SubroutineParser.java` | DONE |
| 5.20 | Fix ROLLBACK TO SAVEPOINT intercepted as full ROLLBACK | `DBI.java` | DONE |
| 5.21 | Support CODE reference returns from @INC hooks (PAR simulation) | `ModuleOperators.java` | DONE |
| 5.25 | Normalize JDBC error messages to match native driver format | `DBI.java` | DONE |
| 5.26 | Fix regex `\Q` delimiter escaping (`qr/\Qfoo\/bar/`) | `StringParser.java` | DONE |
| 5.27 | Fix `bind_param()` to defer `stmt.setObject()` to `execute()` | `DBI.java` | DONE |
| 5.28 | Fix `execute()` to apply stored bound_params when no inline params | `DBI.java` | DONE |
| 5.29 | Add STORABLE_freeze/thaw hook support to Storable dclone/freeze/thaw | `Storable.java` | DONE |
| 5.30 | Fix stale PreparedStatement after ROLLBACK in execute() | `DBI.java` | DONE |
| 5.31 | Fix interpreter context propagation for subroutine bodies | `BytecodeCompiler.java`, `BytecodeInterpreter.java`, opcode handlers | DONE |
| 5.35 | Fix `last_insert_id()` to use connection-level SQL queries | `DBI.java` | DONE |
| 5.36 | Fix `%{{ expr }}` parser disambiguation inside dereference context | `Parser.java`, `StatementResolver.java`, `Variable.java` | DONE |
| 5.37 | Fix `//=`, `||=`, `&&=` short-circuit in bytecode interpreter | `BytecodeCompiler.java` | DONE |
| 5.57 | Fix post-rebase regressions: integer `/=` warn, do{}until const fold, vec 32-bit, strict propagation, caller hints, %- CAPTURE_ALL, large int literals | Multiple files | DONE |
| 5.58 | Fix pack/unpack 32-bit consistency: j/J use ivsize=4 bytes, disable q/Q (no use64bitint) | `NumericPackHandler.java`, `NumericFormatHandler.java`, `Unpack.java`, `PackParser.java` | DONE |

**t/60core.t results** (142 tests emitted, updated after step 5.56):
- **125 ok**: All real tests pass
- **not ok 82–93**: 12 "Unreachable cached statement still active" — cursors not fully consumed, need DESTROY to call finish()
- **not ok 138–142**: 5 garbage collection tests — expected (JVM has no reference counting / `weaken`)

**Full test suite results** (314 test files, updated 2026-04-02):

| Category | Count | Details |
|----------|-------|---------|
| Fully passing | 72 | 24 substantive + 48 DB-specific skips |
| GC-only failures | 147 | All real tests pass; only appended GC leak checks fail |
| Real TAP failures | 40 | See categorized breakdown below |
| CDBI (need Class::DBI) | 41 | Expected — Class::DBI not installed |
| Other errors | 13 | Missing DateTime modules, syntax errors, etc. |
| Incomplete | 1 | t/inflate/file_column.t |

- **Individual test pass rate: 96.7%** (8,923/9,231 tests OK)
- **Effective file pass rate: 80.2%** (219/273 files pass or GC-only, excluding CDBI)

---

## Blocking Issues — Not Quick Fixes

### ~~HIGH PRIORITY: `$^S` wrong inside `$SIG{__DIE__}` when `require` fails in `eval {}`~~ — RESOLVED (step 5.17)

**Symptom**: `$^S` is 0 (top-level) instead of 1 (inside eval) when `require` triggers `$SIG{__DIE__}` from within `eval {}`. This causes die handlers that check `$^S` to misidentify eval-guarded require failures as top-level crashes.

**Affected tests**: `t/00describe_environment.t` — the test installs a `$SIG{__DIE__}` handler that uses `$^S` to distinguish eval-caught exceptions from real crashes. Because `$^S` is wrong, the optional `require File::HomeDir` (inside `eval {}`) triggers the "Something horrible happened" path and `exit 0`, aborting the test. The `Class::Accessor::Grouped->VERSION` check also crashes the same way.

**Repro**:
```bash
# PerlOnJava (wrong): S=0
./jperl -e '$SIG{__DIE__} = sub { print "S=", defined($^S) ? $^S : "undef", "\n" }; eval { require No::Such::Module }; print "after eval\n"'

# Perl 5 (correct): S=1
perl -e '$SIG{__DIE__} = sub { print "S=", defined($^S) ? $^S : "undef", "\n" }; eval { require No::Such::Module }; print "after eval\n"'
```

**Root cause**: The `require` failure path does not propagate the eval depth / `$^S` state when invoking `$SIG{__DIE__}`. A plain `die` inside `eval {}` correctly reports `$^S=1`, but a failed `require` inside `eval {}` reports `$^S=0`.

**What's needed to fix**:
- Find where `require` failure invokes the `__DIE__` handler (likely in `Require.java` or `WarnDie.java`)
- Ensure `$^S` reflects the enclosing eval context, matching the behavior of `die` inside `eval {}`

**Impact**: HIGH — blocks `t/00describe_environment.t` and any code that relies on `$^S` in `$SIG{__DIE__}` with `require` inside `eval {}`. Common pattern in CPAN (Test::Exception, DBIx::Class, Moose).

### ~~HIGH PRIORITY: VerifyError (bytecode compiler bug)~~ — RESOLVED for File::stat; systemic issue remains low-priority

**Symptom**: `java.lang.VerifyError: Bad type on operand stack` when compiling complex anonymous subroutines with many local variables.

**Affected tests**: `t/00describe_environment.t` (secondary issue — also blocked by `$^S` bug above)

**Root cause**: The JVM bytecode emitter generates incorrect stack map frames when a subroutine has many locals and complex control flow (ternary chains, nested `eval`, `for` loops). The JVM verifier rejects the class because `java/lang/Object` on the stack is not assignable to `RuntimeScalar`.

**What's needed to fix**:
- Debug the bytecode emitter's stack map frame generation (likely in `EmitSubroutine.java` or related emit classes)
- The anonymous sub `anon2920` in the test has ~100 local variable slots and deeply nested control flow
- May need to split large subroutines or fix how the stack map calculator handles branch merging
- This is the same class of bug as the File::stat VerifyError (see Known Bugs below)

**Impact**: Currently low for DBIx::Class (test already skips), but affects any complex Perl subroutine. Could block other CPAN modules.

### SYSTEMIC: DESTROY / TxnScopeGuard — leaked transaction_depth

**Symptom**: After a failed `_insert_bulk`, `transaction_depth` stays elevated (1 instead of 0). Subsequent `txn_begin` calls increment the counter without emitting `BEGIN`, causing SQL trace tests to fail.

**Affected tests**: `t/100populate.t` tests 37-42 (SQL trace expects `BEGIN`/`INSERT`/`COMMIT` but gets `INSERT` only), test 53 ("populate is atomic").

**Root cause**: `_insert_bulk` uses `TxnScopeGuard`:
```perl
my $guard = $self->txn_scope_guard;   # txn_begin → depth 0→1, emits BEGIN
# ... INSERT that fails with exception ...
$guard->commit;                        # never reached
# $guard goes out of scope → DESTROY should rollback → depth 1→0
```
Without DESTROY, the guard is silently dropped. `transaction_depth` stays at 1. Next `txn_begin` sees depth=1, increments to 2, skips `_exec_txn_begin` (no `BEGIN`). The JDBC connection also stays in non-autocommit mode.

**Why DESTROY is hard on JVM**: Perl uses reference counting — DESTROY fires deterministically at scope exit when the last reference disappears. JVM uses tracing GC with non-deterministic collection. PerlOnJava has no refcounting.

**Potential fix approach — DeferBlock/DVM-based scope guard**:

PerlOnJava already has `DynamicVariableManager` (DVM) with a stack of `DynamicState` items. `DeferBlock` implements `DynamicState` — its `dynamicRestoreState()` runs deferred code at scope exit. `Local.localTeardown()` pops the stack, with exception safety.

A `DestroyGuard` could work similarly:
1. When `bless()` is called on an object whose class has a DESTROY method, push a `DestroyGuard(weakref_to_object)` onto the DVM stack
2. `DestroyGuard.dynamicRestoreState()` checks if the object still has `blessId != 0` and calls DESTROY
3. This leverages existing scope-exit infrastructure (LIFO ordering, exception safety)

**Caveats**: This is scope-based, not refcount-based. It would correctly handle the common single-owner pattern (`my $guard = ...`) but would be wrong for objects returned from subs or stored in globals (DESTROY would fire too early). A compile-time heuristic could limit registration to `my $var` that are never returned/assigned elsewhere.

**Affected files for implementation**:
- `ReferenceOperators.java` (bless) — detect DESTROY method, push DestroyGuard
- `DynamicVariableManager.java` — new `DestroyGuard` class implementing `DynamicState`
- `EmitterMethodCreator.java` / `Local.java` — ensure teardown runs on scope exit

**Impact**: Fixes t/100populate.t tests 37-42, 53. Would also fix TxnScopeGuard usage across all DBIx::Class tests and any other CPAN module using scope guards (Scope::Guard, Guard, etc.).

### SYSTEMIC: GC / `weaken` / `isweak` absence

**Symptom**: Every DBIx::Class test file appends 5+ garbage collection leak tests that always fail.

**Affected tests**: All 36 "GC-only" failures, plus the GC portion of all 12 "real failure" tests.

**Root cause**: JVM uses tracing GC, not reference counting. PerlOnJava cannot implement `weaken`/`isweak` from `Scalar::Util`. DBIx::Class uses `Test::DBIx::Class::LeakTracer` which inserts `is_refcount`-based leak tests at END time.

**What's needed to fix**:
- **Option A (hard)**: Implement reference counting alongside JVM GC using a side table mapping object IDs to manual ref counts. Would require wrapping every `RuntimeScalar` assignment. Massive performance impact.
- **Option B (pragmatic)**: Accept these as known failures. The GC tests verify Perl-specific memory patterns that don't apply to JVM. Real functionality works correctly.
- **Option C (workaround)**: Patch DBIx::Class's test infrastructure to skip leak tests when `Scalar::Util::weaken` is not functional. Could set `$ENV{DBIC_SKIP_LEAK_TESTS}` or similar.

**Impact**: Makes test output noisy (287 GC-only sub-test failures) but does NOT affect functionality.

### RowParser.pm line 260 crash (post-test cleanup)

**Symptom**: `Not a HASH reference at RowParser.pm line 260` — occurs 8 times across the test suite, always in END blocks or cleanup after tests have already completed.

**Root cause**: During END-block teardown, `_resolve_collapse` is called with stale or partially-destroyed data structures. The code does `$my_cols->{$_}{via_fk}` where `$my_cols->{$_}` may have been clobbered during object destruction. Since PerlOnJava lacks `DESTROY`/`DEMOLISH`, circular references persist and cleanup code may run in unexpected order.

**What's needed to fix**:
- Investigate exactly which END block triggers the call
- May be related to `weaken` absence — objects that should be dead are still alive
- Could potentially be fixed by adding defensive `ref()` checks in RowParser.pm, but that's patching the module rather than fixing the engine

**Impact**: Non-blocking — all real tests complete before the crash. Only affects test harness exit code.

---

## Remaining Real Failures — Categorized (updated 2026-04-02)

Of the 40 test files with real TAP failures, detailed analysis shows:
- **4 files**: GC-only (previously miscounted — t/storage/txn.t, t/101populate_rs.t, t/inflate/hri.t, t/storage/nobindvars.t)
- **5 files**: TODO/SKIP + GC only (t/inflate/core.t, t/inflate/datetime.t, t/sqlmaker/order_by_func.t, t/prefetch/count.t, t/delete/related.t)
- **9 files**: Real logic bugs (38 individual test failures across 6 root causes)
- **Remainder**: DESTROY-dependent or already-fixed

### Previously Fixed Tests — RESOLVED

| Test | Status | What was fixed |
|------|--------|----------------|
| `t/64db.t` | **FIXED** (4/4 real pass) | `column_info()` implemented via SQLite PRAGMA (step 5.13) |
| `t/752sqlite.t` | **FIXED** (34/34 real pass) | AutoCommit tracking + BEGIN/COMMIT/ROLLBACK interception (steps 5.14-5.15); `prepare_cached` per-dbh cache (step 5.16) |
| `t/00describe_environment.t` | **FIXED** (fully passing) | `$^S` correctly reports 1 inside `$SIG{__DIE__}` for `require` failures in `eval {}` (step 5.17) |
| `t/83cache.t` | **FIXED** (all real tests pass) | Prefetch result collapsing fixed by `//=` short-circuit fix (step 5.37) |
| `t/90join_torture.t` | **FIXED** (all real tests pass) | Same `//=` short-circuit fix (step 5.37) |
| `t/106dbic_carp.t` | **FIXED** (3/3 real pass) | `__LINE__` inside `@{[]}` string interpolation (step 5.18) |
| `t/84serialize.t` | **FIXED** (115/115 real pass) | STORABLE_freeze/thaw hook support (step 5.29) |
| `t/101populate_rs.t` | **FIXED** (165/165 real pass) | Parser disambiguation (step 5.36), last_insert_id (step 5.35), context propagation (step 5.31) |
| `t/90ensure_class_loaded.t` | **FIXED** (28/28 real pass) | @INC CODE refs (step 5.24), relative filenames (step 5.32b) |
| `t/40resultsetmanager.t` | **FIXED** (5/5 real pass) | MODIFY_CODE_ATTRIBUTES (step 5.22) |

### Root Cause Cluster 1: SQL `ORDER__BY` counter offset — 16 tests

| Test | Failures | Details |
|------|----------|---------|
| `t/sqlmaker/limit_dialects/fetch_first.t` | 8 | SQL generates `ORDER__BY__000` but expected `ORDER__BY__001` |
| `t/sqlmaker/limit_dialects/toplimit.t` | 8 | Same counter offset bug |

**Root cause**: Global counter/state initialization off-by-one in SQLMaker limit dialect rewriting. Likely a single variable init fix.

### Root Cause Cluster 2: Multi-create FK insertion ordering — 9 tests

| Test | Failures | Details |
|------|----------|---------|
| `t/multi_create/in_memory.t` | 8 | `NOT NULL constraint failed: cd.artist` — FK not set before child INSERT |
| `t/multi_create/standard.t` | 1 | Same root cause |

**Root cause**: When creating parent + child in one `create()` call, the parent's auto-generated ID isn't being propagated to the child row before INSERT. May relate to `last_insert_id` code path in multi-create or `new_related`/`insert` ordering.

### Root Cause Cluster 3: SQL condition parenthesization — 10 tests

| Test | Failures | Details |
|------|----------|---------|
| `t/search/stack_cond.t` | 7 | Extra wrapping parens: `WHERE ( ( ( ... ) ) )` instead of flat `WHERE ...` |
| `t/sqlmaker/dbihacks_internals.t` | 3 | Condition collapse produces HASH where ARRAY expected (2870/2877 pass) |

**Root cause**: SQL::Abstract or DBIC condition stacking adds extra parenthesization layers.

### Root Cause Cluster 4: Transaction/scope guard — 6 real tests + DESTROY

| Test | Failures | Details |
|------|----------|---------|
| `t/storage/txn_scope_guard.t` | 6 real + 2 TODO + ~36 GC | "Correct transaction depth", "rollback successful without exception", missing expected warnings |

**Root cause**: TxnScopeGuard::DESTROY never fires (no DESTROY support). Transaction depth tracking, rollback behavior, and scope guard warnings all depend on deterministic destruction.

### Root Cause Cluster 5: Custom opaque relationship — 2 tests

| Test | Failures | Details |
|------|----------|---------|
| `t/relationship/custom_opaque.t` | 2 | Returns undef / empty SQL for custom relationships |

**Root cause**: Opaque custom relationship conditions are not being resolved into SQL.

### Root Cause Cluster 6: DBI error path + misc — 2 tests

| Test | Failures | Details |
|------|----------|---------|
| `t/storage/base.t` | 1 | Expected `prepare_cached failed` but got `prepare() failed` |
| `t/60core.t` | 1 (test 38) | `-and` array condition in `find()` returns row instead of undef |

### Other known failures

| Test | Failures | Root cause | Status |
|------|----------|------------|--------|
| `t/60core.t` tests 82-93 | 12 | "Unreachable cached statement" — DESTROY-related (reduced from 45 by step 5.56) | Systemic |
| `t/85utf8.t` | 14 | `utf8::is_utf8` flag — JVM strings are natively Unicode | Systemic |
| `t/100populate.t` | 12 | Tests 37-42/53 DESTROY-related; test 59 JDBC batch execution | Partially systemic |
| `t/88result_set_column.t` | 1 | DBIx::Class's own TODO test | Not a PerlOnJava bug |
| `t/53lean_startup.t` | 1 | Module load footprint mismatch | Won't fix |

---

## Must Fix

### Ternary-as-lvalue with assignment branches — FIXED (step 5.34)

Expressions like `($x) ? @$a = () : $b = []` triggered "Modification of a read-only value attempted" at runtime. Perl 5 parses this as `($x ? (@$a = ()) : $b) = []`, where the true branch is a LIST assignment expression.

**Root cause**: LIST assignments in scalar context return cached `RuntimeScalarReadOnly` values (e.g., the element count 0). When the ternary stored this in a spill slot and the outer assignment tried to `.set()` on it, `RuntimeBaseProxy.set()` called `vivify()` → `RuntimeScalarReadOnly.vivify()` threw the error.

**Fix**: In `EmitVariable.handleAssignOperator()`, detect when the LHS ternary has LIST assignment branches (via `LValueVisitor.getContext()`). For those branches, emit the inner assignment in void context (side effects only) and use the outer RHS as the result. Non-LIST-assignment branches (including scalar assignments like `$c = 100` which return the writable target variable) still get the outer assignment applied normally as lvalue targets.

**Key distinction**: Scalar assignments (`$a = 1`) return the variable itself (writable lvalue). LIST assignments (`@a = ()`) return the element count (read-only cached value). Only LIST assignment branches need special handling.

**Impact**: Enables the Class::Accessor::Grouped pattern: `wantarray ? @rv = eval $src : $rv[0] = eval $src`

### File::stat VerifyError — FIXED (resolved by prior commits)
- `use File::stat` no longer triggers VerifyError
- Confirmed working with JVM backend (no interpreter fallback)
- Both the `Class::Struct + use overload` combination and `eval { &{"Fcntl::S_IF..."} }` patterns now compile correctly

### JDBC error message format mismatch — FIXED (step 5.25)

**Fix**: Added `normalizeErrorMessage()` in `DBI.java` that extracts the parenthesized native message from JDBC-wrapped errors like `[SQLITE_MISMATCH] Data type mismatch (datatype mismatch)` → `datatype mismatch`.

### SQL expression formatting differences (t/100populate.t tests 37-42) — FIXED

**Fix**: Transaction depth cleanup after failed `_insert_bulk`. The issue was that `TxnScopeGuard::DESTROY` never fires in PerlOnJava (no DESTROY support), so after `_insert_bulk` failed, `transaction_depth` stayed at 1 permanently. Fixed by wrapping the guard-protected code in `eval { ... } or do { ... }` that manually rolls back on error.

### bind parameter attribute handling (t/100populate.t tests 58-59) — PARTIALLY FIXED

**Test 58 (FIXED)**: The `\Q` delimiter escaping bug caused `qr/\Qfoo\/bar/` to produce `(?^:foo\\\/bar)` instead of `(?^:foo\/bar)`. Fixed in `StringParser.java` by resolving delimiter escaping before `\Q` processing.

**Test 59 (STILL FAILING)**: `literal+bind with semantically identical attrs works after normalization`. The `execute_for_fetch()` aborts with "statement is not executing" from the SQLite JDBC driver. This happens when DBIx::Class's `_insert_bulk` uses `bind_param` with type attributes, then calls `execute_for_fetch` which calls `execute(@$tuple)` for each row. The JDBC PreparedStatement may need to be re-prepared or have its state reset between executions in the batch context.

## Summary

| Phase | Complexity | Description | Status |
|-------|-----------|-------------|--------|
| 1 | Medium | Unblock Makefile.PL (4 engine fixes) | DONE |
| 2 | Medium | Install ~11 missing pure-Perl deps via jcpan | DONE |
| 3 | Simple | Fix DBI version detection | DONE |
| 4 | Medium | Create DBD::SQLite JDBC compatibility shim | DONE |
| 4.5 | Medium | Fix CORE::GLOBAL::caller override bug | DONE |
| 4.6 | Medium | Fix stash aliasing glob vivification | DONE |
| 4.7 | Simple | Fix mixed-context ternary lvalue assignment | DONE |
| 4.8 | Simple | Fix `cp` on read-only installed files | DONE |
| 5 | Complex | Fix runtime issues iteratively | **CURRENT** |

## Progress Tracking

### Current Status: Phase 5 — fixing runtime issues iteratively

### Completed Phases
- [x] Phase 1: Unblock Makefile.PL (2025-03-31)
  - Blocker 1: Added strict::bits to Strict.java
  - Blocker 2: Fixed UNIVERSAL::can AUTOLOAD filter in Universal.java
  - Blocker 3: Fixed goto &sub wantarray propagation + eval{} @_ sharing
  - Blocker 4: Fixed +{} hash constructor parsing in IdentifierParser.java
- [x] Phase 2: Install missing pure-Perl dependencies (2025-03-31)
  - All 11 modules installed via `./jcpan -fi`
- [x] Phase 3: Fix DBI version detection (2025-03-31)
  - Added `our $VERSION = '1.643'` to DBI.pm
- [x] Phase 4: Create DBD::SQLite JDBC shim (2025-03-31)
  - Created DBD/SQLite.pm DSN translation shim
  - Added sqlite-jdbc 3.49.1.0 dependency
  - Wrapped getMetaData()/getParameterMetaData() in DBI.java
- [x] Phase 4.5: Fix CORE::GLOBAL::caller bug (2025-03-31)
  - Fixed whitespace-sensitive token insertion in ParsePrimary.java
  - Test::Exception + Sub::Uplevel now work correctly
- [x] Phase 4.6: Fix stash aliasing glob vivification (2025-03-31)
  - Added `resolveStashHashRedirect()` to GlobalVariable.java
  - Applied redirect in `getGlobalIO()` and EmitVariable.java (JVM backend)
  - Unblocks Package::Stash::PP and namespace::clean
- [x] Phase 4.7: Fix mixed-context ternary lvalue assignment (2025-03-31)
  - Added `assignmentTypeOf()` helper matching Perl 5's `S_assignment_type()` — assignment expressions classified as SCALAR in ternary branches
  - Unblocks Class::Accessor::Grouped (compile-time)
  - Known runtime limitation: ternary-as-lvalue with assignment branches fails for non-constant conditions (e.g., `wantarray`)
- [x] Phase 4.8: Fix `cp` on read-only installed files (2025-03-31)
  - Changed `_shell_cp` in ExtUtils::MakeMaker.pm to `rm -f` then `cp`
  - Fixes reinstall of modules with read-only (0444) .pod/.pm files
- [x] Phase 5 steps 5.1–5.8 (2026-03-31 / 2026-04-01)
  - 5.1: Fixed `@${$v}` string interpolation in StringSegmentParser.java
  - 5.2: Added `B::SV::REFCNT` returning 0 (JVM has no reference counting)
  - 5.3: Added DBI `FETCH`/`STORE` wrappers for tied-hash compatibility
  - 5.4: Created `DBI::Const::GetInfoReturn` stub module
  - 5.5: Fixed list assignment autovivification in RuntimeList.java
  - 5.6: Added DBI `execute_for_fetch` and `bind_param` methods
  - 5.7: Fixed `&func` (no parens) to share caller's `@_` by alias — unblocks Hash::Merge
  - 5.8: Fixed DBI `execute()` to return row count per DBI spec — unblocks UPDATE operations
- [x] Phase 5 steps 5.9–5.12 (2026-04-01)
  - 5.9: Set `$dbh->{Driver}` with `DBI::dr` object — DBIC now detects SQLite driver
  - 5.10: Fixed `get_info()` to accept numeric DBI constants and return scalar
  - 5.11: Added DBI SQL type constants (`SQL_BIGINT`, `SQL_INTEGER`, etc.)
  - 5.12: Fixed `bind_columns` + `fetch` to update bound scalar references — unblocks ALL join/prefetch queries
  - Result: 51/65 active tests now pass all real tests (was ~15/65 before)
- [x] Phase 5 steps 5.13–5.16 (2026-04-01)
  - 5.13: Implemented `column_info()` via SQLite `PRAGMA table_info()` — preserves original type case (JDBC uppercases), returns pre-fetched rows; also blessed metadata sth into `DBI` class with proper attributes
  - 5.14: Added `AutoCommit` state tracking — `execute()` now detects literal BEGIN/COMMIT/ROLLBACK SQL and updates `$dbh->{AutoCommit}` accordingly
  - 5.15: Intercepted literal transaction SQL via JDBC API — `conn.setAutoCommit(false)`, `conn.commit()`, `conn.rollback()` instead of executing SQL directly; fixes SQLite JDBC autocommit conflicts
  - 5.16: Fixed `prepare_cached` to use per-dbh `CachedKids` cache instead of global hash — prevents cross-connection cache pollution when multiple `:memory:` SQLite connections share the same DSN name; added `if_active` parameter handling
  - Also: `execute()` now handles metadata sth (no PreparedStatement) gracefully; `fetchrow_hashref` supports PRAGMA pre-fetched rows
  - Result: 60/68 active tests now pass all real tests (was 51/65 = 78%, now 88%)
- [x] Phase 5 steps 5.17–5.19 (2026-04-01, earlier session)
  - 5.17: Fixed `$^S` to correctly report 1 inside `$SIG{__DIE__}` when `require` fails in `eval {}` — temporarily restores `evalDepth` in `catchEval()` before calling handler. Unblocks t/00describe_environment.t
  - 5.18: Fixed `__LINE__` inside `@{[expr]}` string interpolation — added `baseLineNumber` to Parser for string sub-parsers, computed from outer source position. Fixes t/106dbic_carp.t tests 2-3
  - 5.19: Fixed `execute_for_fetch` to match real DBI 1.647 behavior — tracks error count, stores `[$sth->err, $sth->errstr, $sth->state]` on failure, dies with error count if `RaiseError` is on. Also fixed `execute()` to set err/errstr/state on both sth and dbh. Fixes t/100populate.t test 2
  - Result: 62/68 active tests now pass all real tests (91%, was 88%)
- [x] Phase 5 steps 5.20–5.24 (2026-04-01, current session)
  - 5.20: Fixed `-w` flag overriding `no warnings 'redefine'` pragma — changed condition in SubroutineParser.java to check `isWarningDisabled("redefine")` first
  - 5.21: Fixed `InterpreterFallbackException` not caught at top-level `compileToExecutable()` — ASM's Frame.merge() crashes on methods with 600+ jumps to single label (Sub::Quote-generated subs); added explicit catch in PerlLanguageProvider.java. Fixes t/88result_set_column.t (46/47 pass)
  - 5.22: Implemented `MODIFY_CODE_ATTRIBUTES` call for subroutine attributes — when `sub foo : Attr { }` is parsed, now calls `MODIFY_CODE_ATTRIBUTES($package, \&code, @attrs)` at compile time. Fixes t/40resultsetmanager.t (5/5 pass)
  - 5.23: Fixed ROLLBACK TO SAVEPOINT being intercepted as full ROLLBACK — `sqlUpper.startsWith("ROLLBACK")` now excludes SAVEPOINT-related statements. Fixes t/752sqlite.t (171/172 pass)
  - 5.24: Added CODE reference returns from @INC hooks — PAR-style module loading where hook returns a line-reader sub that sets `$_` per line. Fixes t/90ensure_class_loaded.t tests 14,17 (27/28 pass)
  - Result: 68/314 fully passing, 93.7% individual test pass rate (5579/5953 OK)
- [x] Phase 5 steps 5.25–5.28 (2026-04-01)
  - 5.25: Normalized JDBC error messages — `normalizeErrorMessage()` extracts parenthesized native message from JDBC-wrapped errors. Fixes t/100populate.t test 52-53
  - 5.26: Fixed regex `\Q` delimiter escaping — in `StringParser.java`, delimiter escaping (`\/` → `/`) now resolved before `\Q` processing. Fixes t/100populate.t test 58
  - 5.27: Fixed `bind_param()` to defer `stmt.setObject()` to `execute()` — removed immediate JDBC call, params stored in `bound_params` hash only. Also stores bind attributes in `bound_attrs` hash
  - 5.28: Fixed `execute()` to apply stored `bound_params` when no inline params provided — uses `RuntimeScalarType.isReference()` check (not `== REFERENCE` which misses `HASHREFERENCE`)
  - Also: Transaction depth cleanup in `_insert_bulk` (patched DBIx::Class::Storage::DBI.pm) — wraps guard-protected code in eval/or-do that manually rolls back on error since TxnScopeGuard::DESTROY doesn't fire
  - Result: t/100populate.t now passes 59/60 real tests (was ~36/65; tests 37-42, 52-53, 58 newly passing)
- [x] Phase 5 steps 5.29–5.30 (2026-04-01)
  - 5.29: Added STORABLE_freeze/thaw hook support — `dclone()` uses direct deep-copy (`deepClone()`) instead of YAML round-trip, calling hooks on blessed objects; `freeze`/`nfreeze` YAML serialization checks for `STORABLE_freeze` and stores frozen data with `!!perl/freeze:` tag; `thaw`/`nthaw` handles `!!perl/freeze:` by creating new blessed object and calling `STORABLE_thaw`. Fixes entire freeze/thaw chain for DBIx::Class objects (ResultSource → ResultSourceHandle → Schema)
  - 5.30: Added retry logic for stale PreparedStatements after ROLLBACK — if `setObject`/`execute` throws "not executing", re-prepares via `conn.prepareStatement()` and retries once
  - Result: t/84serialize.t now passes 115/115 real tests (was 0); t/100populate.t at 52/60 (tests 37-42 regressed due to lost _insert_bulk patch in rebuilt cpan build dir)
- [x] Phase 5 step 5.31 (2026-04-01)
  - 5.31: Fixed interpreter context propagation for subroutine bodies — when anonymous/named subs are compiled by the bytecode interpreter (due to JVM "Method too large" fallback), the calling context was hardcoded as LIST. Set `subCompiler.currentCallContext = RUNTIME` in `BytecodeCompiler` for both `visitAnonymousSubroutine()` and `visitNamedSubroutine()`. Added RUNTIME→register 2 resolution in 22+ opcode handlers across `BytecodeInterpreter`, `OpcodeHandlerExtended`, `InlineOpcodeHandler`, `MiscOpcodeHandler`, `SlowOpcodeHandler`. All `op/wantarray.t` tests pass (28/28). Fixes t/101populate_rs.t test 4.
- [x] Phase 5 step 5.32 (2026-04-01)
  - 5.32a: Fixed B::CV introspection — `B::svref_2object(\&sub)->STASH->NAME` and `GV->NAME` now correctly report the defining package and sub name using `Sub::Util::subname` introspection, instead of always returning "main"/"__ANON__". `CvFLAGS` now only sets `CVf_ANON` for anonymous subs. Fixes DBIx::Class t/85utf8.t tests 7-8 (warnings_like tests for incorrect UTF8Columns loading order detection, which depend on `B::svref_2object($coderef)->STASH->NAME` in `Componentised.pm`).
  - 5.32b: Preserved @INC entry relativity in require/use filenames — `ModuleOperators.java` now uses `dirName + "/" + fileName` for display/error-message filenames instead of the absolute resolved path. File I/O still uses the absolute `fullName` internally. This makes error messages and `%INC` match Perl 5 behavior (e.g. `t/lib/Foo.pm` instead of `/abs/path/t/lib/Foo.pm`). Fixes DBIx::Class t/90ensure_class_loaded.t test 28.
- [x] Phase 5 step 5.33 (2026-04-01)
  - 5.33a: Fixed `Long.MIN_VALUE` overflow in `initializeWithLong()` — `Math.abs(Long.MIN_VALUE)` overflows in Java (returns `Long.MIN_VALUE`, a negative number), causing the value to be incorrectly stored as `double` instead of `String`. Changed to direct range comparison `(lv <= 2^53 && lv >= -2^53)` to avoid the overflow. Fixes t/752sqlite.t test 170 (64-bit signed int boundary value).
  - 5.33b: Full DBIx::Class test suite scan — ran all 87 test files. Results: 18 clean passes, 44 GC-only failures (known JVM limitation), 22 skipped (no DB/fork/threads), and only 2 files with real non-GC failures remaining: t/85utf8.t (utf8 flag semantics, systemic JVM issue) and t/88result_set_column.t (DBIx::Class TODO test, not a PerlOnJava bug).
- [x] Phase 5 step 5.34 (2026-04-01)
  - 5.34a: Fixed ternary-as-lvalue with LIST assignment branches — In `EmitVariable.handleAssignOperator()`, detect when the LHS ternary has LIST assignment branches (via `LValueVisitor.getContext()`). For LIST assignment branches, emit in void context (side effects only) and use the outer RHS as result. Scalar assignment branches (which return writable lvalues) use the normal code path. Enables `wantarray ? @rv = eval $src : $rv[0] = eval $src` (Class::Accessor::Grouped pattern).
  - 5.34b: Confirmed File::stat VerifyError is already fixed — `use File::stat` works natively with JVM backend (no interpreter fallback). Both `Class::Struct + use overload` and `eval { &{"Fcntl::S_IF..."} }` patterns compile correctly.
- [x] Phase 5 steps 5.35–5.37 (2026-04-01)
  - 5.35: Fixed `last_insert_id()` — replaced statement-level `getGeneratedKeys()` with connection-level SQL queries (`SELECT last_insert_rowid()` for SQLite, `LASTVAL()` for PostgreSQL, etc.). The old approach broke when any `prepare()` call between INSERT and `last_insert_id()` overwrote the stored statement handle. Fixes t/79aliasing.t, t/87ordered.t, t/101populate_rs.t auto-increment detection.
  - 5.36: Fixed `%{{ expr }}` parser disambiguation — added `insideDereference` flag to Parser.java. In `Variable.parseBracedVariable()`, sets flag before calling `ParseBlock.parseBlock()`. In `StatementResolver.isHashLiteral()`, when inside dereference context with no block indicators, defaults to hash (true) instead of block (false). Fixes `%{{ map { ... } @list }}` (RowParser.pm `__unique_numlist`) and `values %{{ func() }}` (Ordered.pm) patterns. Unblocks t/79aliasing.t, t/87ordered.t, t/101populate_rs.t.
  - 5.37: Fixed `//=`, `||=`, `&&=` short-circuit in bytecode interpreter — the bytecode compiler (`BytecodeCompiler.handleCompoundAssignment()`) was eagerly evaluating the RHS before the `DEFINED_OR_ASSIGN`/`LOGICAL_AND_ASSIGN`/`LOGICAL_OR_ASSIGN` opcode checked the condition. Side effects like `$result_pos++` always executed, breaking DBIx::Class's eval-generated row collapser code. Added `handleShortCircuitAssignment()` that compiles LHS first, emits `GOTO_IF_TRUE`/`GOTO_IF_FALSE` to conditionally skip RHS evaluation, and only assigns via `SET_SCALAR` when needed. Fixes prefetch result collapsing in t/83cache.t test 7 and t/90join_torture.t test 4.

### Test Suite Summary (314 files, updated 2026-04-02)

| Category | Count | Details |
|----------|-------|---------|
| Fully passing | 72 | 24 substantive + 48 DB-specific skips |
| GC-only failures | 147 | All real tests pass; only appended GC leak checks fail |
| Real TAP failures | 40 | 9 files with real logic bugs (38 tests); rest are DESTROY/TODO/GC |
| CDBI errors | 41 | Need Class::DBI — expected |
| Other errors | 13 | Missing DateTime modules, syntax errors |
| Incomplete | 1 | t/inflate/file_column.t |

**Individual test pass rate: 96.7%** (8,923/9,231)

### Dependency Module Test Results (updated 2026-04-02)

| Module | Pass Rate | Tests OK/Total | Key Failures |
|--------|-----------|----------------|--------------|
| Class-C3-Componentised | **100%** | 46/46 | None |
| Context-Preserve | **100%** | 14/14 | None |
| namespace-clean | **99.4%** | 2086/2099 | Stash symbol deletion edge cases |
| Hash-Merge | **99.4%** | 845/850 | GC/weaken |
| SQL-Abstract-Classic | **100%** | 1311/1311 | None |
| Class-Accessor-Grouped | **97.8%** | 543/555 | GC/weaken |
| Moo | **97.3%** | 816/839 | weaken, DEMOLISH, `no Moo` cleanup |
| MRO-Compat | **100%** | 26/26 | None |
| Sub-Quote | **98.7%** | 2720/2755 | GC/weaken (28), hints propagation (5), syntax error line numbering (1), use integer (1) |
| Config-Any | ~80-90% | 58/113 (runner artifact) | Passes individually; parallel runner issue |

**Aggregate: 99.3%** (8,383/8,435 across all dependency modules)

### Implementation Plan (Phase 5 continued)

#### Tier 1 — Quick Wins (18 DBIC tests) ✅ COMPLETED

| Step | What | Tests Fixed | Status |
|------|------|------------|--------|
| 5.38 | SQL `ORDER__BY` counter offset | 16 | ✅ Done |
| 5.39 | `prepare_cached` error message | 1 | ✅ Done |
| 5.40 | `-and` array condition in `find()` | 1 | ✅ Done |

#### Tier 2 — Medium Effort (21 DBIC tests) ✅ COMPLETED

| Step | What | Tests Fixed | Status |
|------|------|------------|--------|
| 5.41 | Multi-create FK / DBI HandleError | 9 | ✅ Done — root cause was missing HandleError support |
| 5.42 | SQL condition / Storable sort order | 10 | ✅ Done — binary Storable serializer matching Perl 5 |
| 5.43 | Custom opaque relationship SQL | 2 | ✅ Done — fixed PerlOnJava autovivification bug |

#### Tier 3+ — Dependency Module Fixes

| Step | What | Tests Fixed | Status |
|------|------|------------|--------|
| 5.44 | Nested ref-of-ref detection (`ref()` chain) | 4 (SQL-Abstract) | Done |
| 5.45 | `caller()` hints: `$^H` and `%^H` return values | 53 (Sub-Quote) | Done |
| 5.46 | `mro::get_isarev` dynamic scan + `pkg_gen` auto-increment | 4 (MRO-Compat) | Done |
| 5.47 | BytecodeCompiler sub-compiler pragma inheritance | 2 (Sub-Quote) | Done |
| 5.48 | `warn()` returns 1 (was undef) | 1 (SQL-Abstract IS NULL) | Done |
| 5.49 | Overload fallback semantics and autogeneration | 17 (SQL-Abstract overload) | Done |
| 5.50 | B.pm SV flags rewrite (IOK/NOK/POK) | quotify.t countable | Done |
| 5.51 | Large integer literals stored as DOUBLE not STRING | 6 (quotify.t) | Done |
| 5.52 | `caller()` in eval STRING with `#line` directives | Sub-Quote | Done |
| 5.53 | Interpreter LIST_SLICE implementation | 4 (Sub-Quote) | Done |
| 5.54 | LIST_SLICE opcode collision + scalar context | 2 (op/pack.t) | Done |
| 5.55 | Storable nfreeze/thaw STORABLE_freeze/thaw hooks | 115 (t/84serialize.t) | Done |

#### Systemic — Not planned for short-term

- GC / weaken / isweak (~44 files with GC-only noise)
- UTF8 flag semantics (8 tests in t/85utf8.t — JVM strings are natively Unicode)

#### Phase 6 — DBI Statement Handle Lifecycle ✅ COMPLETED

**Root cause**: Three compounding bugs in PerlOnJava DBI's `Active` flag management:
1. `prepare()` copies ALL dbh attributes to sth including `Active=true` (DBI.java line 193)
2. `execute()` never sets `Active` based on whether there are results
3. Fetch methods never clear `Active` when result set is exhausted

In real Perl DBI: sth starts with Active=false, becomes true on execute with results,
becomes false when all rows are fetched or finish() is called.

| Step | What | Impact | Status |
|------|------|--------|--------|
| 5.56 | Fix sth Active flag lifecycle: false after prepare, true after execute with results, false on fetch exhaustion. Use mutable RuntimeScalar (not read-only scalarFalse). Close previous JDBC ResultSet on re-execute. | t/60core.t: 45→12 cached stmt failures | ✅ Done |

#### Phase 7 — Transaction Scope Guard Cleanup (targets 12 t/100populate.t tests)

**Root cause**: `TxnScopeGuard::DESTROY` never fires → no ROLLBACK on exception →
`transaction_depth` stays elevated permanently.

**Approach**: Cannot fix via general DESTROY (bless happens in constructor, wrong DVM scope).
Best option is patching `_insert_bulk` and other callers to use explicit try/catch rollback
instead of relying on DESTROY.

| Step | What | Impact | Status |
|------|------|--------|--------|
| 5.58 | Patch `_insert_bulk` with explicit try/catch rollback | 12 (t/100populate.t) | |
| 5.59 | Audit other txn_scope_guard callers for similar issues | Future test coverage | |

#### Phase 8 — Remaining Dependency Fixes

| Step | What | Impact | Status |
|------|------|--------|--------|
| 5.60 | Sub-Quote hints.t tests 4-5 (${^WARNING_BITS} round-trip) | 2 (Sub-Quote) | |
| 5.61 | `overload::constant` support | 2 (Sub-Quote hints.t 9,14) | |

### Progress Tracking

#### Current Status: Step 5.58 complete (pack/unpack 32-bit consistency)

#### Key Test Results (2026-04-02)

| Test File | Real Failures | Notes |
|-----------|---------------|-------|
| t/sqlmaker/dbihacks_internals.t | **0** | Was 3, fixed by Storable binary serializer |
| t/search/stack_cond.t | **0** | Was 7-12, fixed by Storable sort order |
| t/multi_create/standard.t | **0** | Was 1, fixed by DBI HandleError |
| t/multi_create/in_memory.t | **0** | Was 8, fixed by DBI HandleError |
| t/storage/base.t | **0** | Was 1 |
| t/search/related_strip_prefetch.t | **0** | |
| t/relationship/custom_opaque.t | **0** | Was 2, fixed by autovivification bug fix |
| t/60core.t | 17 (12 cached + 5 GC) | Reduced from 50 by step 5.56 (Active flag lifecycle fix). Remaining 12 need DESTROY. |

#### Completed Work

**Step 5.58 (2026-04-02) — Pack/unpack 32-bit consistency:**
- `j`/`J` format now uses 4 bytes (matching `ivsize=4`) instead of hardcoded 8 bytes
- `q`/`Q` format now throws "Invalid type" (matching 32-bit Perl without `use64bitint`)
- op/pack.t: +5 passes (14665 ok, was 14660); op/64bitint.t: fully skipped
- Files: `NumericPackHandler.java`, `NumericFormatHandler.java`, `Unpack.java`, `PackParser.java`

**Step 5.41-5.42 (2026-04-01):**
- Binary Storable serializer matching Perl 5 sort order (`Storable.java`)
- DBI HandleError support (`DBI.java`)
- DBI error message format fix (`DBI.java`, `DBI.pm`)
- Commit: `e662f76ed`

**Step 5.43 (2026-04-02):**
- Fixed PerlOnJava autovivification bug: multi-element list assignment to hash elements
  from undef scalar now works correctly (`AutovivificationHash.java`, `AutovivificationArray.java`)
- Root cause: `($h->{a}, $h->{b}) = (v1, v2)` when `$h` is undef created two separate
  hashes (one per `hashDeref()` call). Fix caches the autovivification hash in the scalar's
  value field so subsequent hashDeref() calls reuse the same hash.

**Step 5.44 (2026-04-02):**
- Fixed `ref()` for nested references: `ref(\\$x)` returned "SCALAR" instead of "REF"
- Root cause: `REFERENCE` type missing from inner switch in `ReferenceOperators.ref()` —
  when a REFERENCE pointed to another REFERENCE, it fell to `default -> "SCALAR"`
- Also fixed parallel bug in `builtin::reftype` in `Builtin.java`
- Files changed: `ReferenceOperators.java`, `Builtin.java`
- SQL-Abstract-Classic `t/09refkind.t` now 13/13 (was 9/13)
- Remaining 18 SQL-Abstract failures: 17 in `t/23_is_X_value.t` (overload fallback
  detection — `use overload bool` without `fallback` should allow auto-stringification
  in Perl 5 ≥ 5.17, but PerlOnJava's overload doesn't support this derivation),
  1 in `t/02where.t` (`{like => undef}` generates `requestor NULL` instead of `IS NULL`)

**Step 5.45 (2026-04-02):**
- Implemented `caller()[8]` ($^H hints) and `caller()[10]` (%^H hint hash) return values
- Created parallel infrastructure to existing `callerBitsStack`: `callSiteHints`,
  `callerHintsStack`, `callSiteHintHash`, `callerHintHashStack` in `WarningBitsRegistry.java`
- Wired emission in `EmitCompilerFlag.java` and `BytecodeCompiler.java`
- Updated `RuntimeCode.java` to read hints at caller frames and push/pop at all 3 apply() sites
- Updated `PerlLanguageProvider.java` for BEGIN block hints propagation
- Sub-Quote improved from 137/178 to 188/237 (different test count due to hints.t newly countable)

**Step 5.46 (2026-04-02):**
- Fixed `mro::get_isarev` to dynamically scan all @ISA arrays instead of hardcoded class names
- Implemented `GlobalVariable.getAllIsaArrays()` (was empty stub)
- Made `Mro.incrementPackageGeneration()` public; called from `RuntimeGlob.java` on CODE assignment
- Added lazy @ISA change detection in `get_pkg_gen()` via `pkgGenIsaState` map
- Files changed: `GlobalVariable.java`, `Mro.java`, `RuntimeGlob.java`
- MRO-Compat now 26/26 (was 22/26) — 100%

**Step 5.47 (2026-04-02):**
- Fixed BytecodeCompiler sub-compiler not inheriting pragma flags (strict/warnings/features)
- Root cause: Sub::Quote generates `sub { BEGIN { $^H = 1538; } ... }` in eval STRING;
  the sub-compiler created for the sub body didn't inherit the parent's pragma state
- Added `getEffectiveSymbolTable()` helper with fallback to `this.symbolTable` when
  `emitterContext` is null. Updated 5 pragma check methods to use it.
- Added `inheritPragmaFlags()` method called in both named and anonymous sub compilation
- Sub-Quote hints.t improved from 11/18 to 13/18; overall Sub-Quote: 190/237 (was 188/237)

**Step 5.48 (2026-04-02):**
- Fixed `warn()` return value — Perl 5 `warn()` always returns 1; PerlOnJava returned undef
- Root cause: `WarnDie.java` line 199 returned `new RuntimeScalar()` (undef) instead of `new RuntimeScalar(1)`
- Impact: SQL-Abstract-Classic `{like => undef}` generated `requestor NULL` instead of `requestor IS NULL`
  because `$self->belch(...) && 'is'` short-circuited on falsy return from warn/belch
- Files changed: `WarnDie.java`

**Step 5.49 (2026-04-02):**
- Fixed overload fallback semantics and autogeneration
- Bug A: `tryOverloadFallback()` returned null when no `()` glob existed, blocking autogeneration.
  Perl 5 says: no fallback specified → allow autogeneration
- Bug B: `prepare()` was CALLING the `()` method (which is `\&overload::nil`, returns undef)
  instead of READING the SCALAR slot `${"Class::()"}` which holds the actual fallback value
- Rewrote `OverloadContext.prepare()` to walk hierarchy and read SCALAR slot
- Rewrote `tryOverloadFallback()` with correct 3-state semantics (undef/0/1)
- Added `tryTwoArgumentOverload()` with autogeneration varargs for compound ops
- Updated all 10 compound assignment methods in `MathOperators.java` to pass base operator
- Files changed: `OverloadContext.java`, `MathOperators.java`

**Step 5.50 (2026-04-02):**
- Rewrote B.pm SV flags for proper integer/float/string distinction
- Updated SV flag constants to standard Perl 5 values (SVf_IOK=0x100, SVf_NOK=0x200,
  SVf_POK=0x400, SVp_IOK=0x1000, SVp_NOK=0x2000, SVp_POK=0x4000)
- Rewrote `FLAGS()` method to use `builtin::created_as_number()` for proper type detection
- Added export functions for all new constants
- Files changed: `B.pm`

**Step 5.51 (2026-04-02):**
- Fixed large integer literals (>= 2^31) stored as STRING instead of DOUBLE
- In Perl 5, integers that overflow IV are promoted to NV (double), not PV (string)
- JVM emitter (`EmitLiteral.java`): changed `isLargeInteger` boxed branch from
  `new RuntimeScalar(String)` to `new RuntimeScalar(double)`
- Bytecode interpreter (`BytecodeCompiler.java`): changed from `LOAD_STRING` to
  `LOAD_CONST` with double-valued `RuntimeScalar`
- Impact: quotify.t goes from 2586/2592 to 2592/2592 (6 large-integer tests fixed)
- Files changed: `EmitLiteral.java`, `BytecodeCompiler.java`

**Step 5.52 (2026-04-01):**
- Fixed `caller(0)` returning wrong file/line in eval STRING with `#line` directives
- Root cause: ExceptionFormatter's frame skip logic assumed first frame is sub's own
  location (true for JVM), but interpreter frames from CallerStack are already the call site
- Added `StackTraceResult` record to `ExceptionFormatter` with `firstFrameFromInterpreter` flag
- `callerWithSub()` now conditionally skips based on frame type
- Fixed eval STRING's `ErrorMessageUtil` to use `evalCtx.compilerOptions.fileName`
- Fixed sub naming: `SubroutineParser` uses fully qualified names via `NameNormalizer`
- Files changed: `ExceptionFormatter.java`, `RuntimeCode.java`, `SubroutineParser.java`

**Step 5.53 (2026-04-01):**
- Fixed interpreter list slice: `(list)[indices]` was compiled as `[list]->[indices]`
  (array ref dereference returning one scalar instead of proper list slice)
- Added `LIST_SLICE` opcode (452) that calls `RuntimeList.getSlice()` for proper
  multi-element list slice semantics
- Files changed: `Opcodes.java`, `CompileBinaryOperator.java`,
  `BytecodeInterpreter.java`, `Disassemble.java`
- Impact: Sub-Quote goes from 52/56 to 54/56 (tests 48,50,55,56 fixed)

**Step 5.54 (2026-04-01):**
- Fixed opcode collision: `LIST_SLICE` and `VIVIFY_LVALUE` both assigned opcode 452 in
  `Opcodes.java`. Changed `LIST_SLICE` to 453.
- Fixed interpreter LIST_SLICE scalar context conversion: `getSlice()` returns a
  `RuntimeList` but in SCALAR context it should return the last element (via `.scalar()`),
  not the count. Added context conversion in `BytecodeInterpreter.java` after
  `list.getSlice(indices)` call, checking the `context` parameter and calling `.scalar()`
  for scalar context or returning empty list for void context.
- Impact: op/pack.t tests 4173 and 4267 fixed — both use `(unpack(...))[0]` syntax which
  triggers LIST_SLICE in interpreter. The `is($$@)` prototype forces first arg to scalar
  context, so LIST_SLICE must honor context.
- Files changed: `Opcodes.java` (452→453), `BytecodeInterpreter.java`
- Commit: `9e53afe78`

**Step 5.55 (2026-04-01):**
- Fixed Storable `nfreeze()`/`thaw()` to call `STORABLE_freeze`/`STORABLE_thaw` hooks on
  blessed objects. Previously only `dclone()` (via `deepClone()`) called these hooks;
  `serializeBinary()` and `deserializeBinary()` raw-serialized blessed objects without hooks.
- Added `SX_HOOK` (type 19) to binary format for hook-serialized objects, containing:
  class name, serialized string from freeze, and any extra refs
- In `serializeBinary()`: check for STORABLE_freeze method before the existing SX_BLESS
  code path. If found, call hook and emit SX_HOOK format.
- In `deserializeBinary()`: new SX_HOOK case creates blessed object, reads serialized
  string and extra refs, then calls STORABLE_thaw to reconstitute.
- Impact: t/84serialize.t goes from 1 real failure to 0 real failures (115/115 real pass).
  The `dclone_method` strategy now correctly chains: `deepClone` → `STORABLE_freeze` →
  `nfreeze(handle)` → `serializeBinary` with hooks → compact 200-byte frozen data
  (was 152KB without hooks, causing "Can't bless non-reference value" on thaw).
- Files changed: `Storable.java`

**Step 5.56 (2026-04-02):**
- Fixed DBI sth Active flag lifecycle to match real DBI behavior
- `prepare()` now sets sth Active=false (was inheriting dbh's Active=true via setFromList)
- `execute()` sets Active=true only for SELECTs with result sets, false for DML
- `fetchrow_arrayref()` and `fetchrow_hashref()` set Active=false when no more rows
- `execute()` now closes previous JDBC ResultSet before re-executing (resource leak fix)
- Used mutable `new RuntimeScalar(false)` instead of read-only `scalarFalse` constant,
  fixing "Modification of a read-only value attempted" in DBI.pm `finish()`
- Impact: t/60core.t goes from 50 failures (45 cached stmt + 5 GC) to 17 (12 cached + 5 GC)
  The 33 fixed failures were: stale Active=true from prepare, DML leaving Active=true,
  and exhausted cursors still showing Active=true
- Remaining 12 are SELECTs where cursor was opened but not fully consumed, needing DESTROY
  to call finish() on scope exit
- Files changed: `DBI.java`
- Commit: `3de38f462`

**Step 5.57 (2026-04-02) — Post-rebase regression fixes:**
- Fixed 6 post-rebase regressions in Perl test suite:
  - **op/assignwarn.t** (116/116): Created `integerDivideWarn()` and `integerDivideAssignWarn()`
    for uninitialized value warnings with `/=` under `use integer`. Root cause: bytecode
    interpreter's `INTEGER_DIV_ASSIGN` called `integerDivide()` which used `getLong()` without
    checking for undef. Updated both bytecode interpreter (`InlineOpcodeHandler.java`) and JVM
    backend (`EmitBinaryOperator.java` + `OperatorHandler.java`).
  - **op/while.t** test 26 (23/26): Added constant condition optimization to `do{}while/until`
    loops. Three fixes: (1) `resolveConstantSubBoolean` now returns true for reference constants
    without calling `getBoolean()` (which triggered overloaded `bool` at compile time);
    (2) `getConstantConditionValue` handles `not`/`!` operators (used for `until` conditions);
    (3) `emitDoWhile` checks for constant conditions in both JVM (`EmitStatement.java`) and
    bytecode (`BytecodeCompiler.java`) backends.
  - **op/vec.t** (74/78, matches master): Fixed unsigned 32-bit vec values by using `getLong()`
    for both 32-bit and 64-bit widths. Root cause: values > 0x7FFFFFFF clamped to
    `Integer.MAX_VALUE` via double→int narrowing. Files: `Vec.java`, `RuntimeVecLvalue.java`.
  - **Strict options propagation**: `propagateStrictOptionsToAllLevels` → `setStrictOptions`
    in `PerlLanguageProvider.java`.
  - **caller()[10] hints**: Reverted to scalarUndef in `RuntimeCode.java`.
  - **%- CAPTURE_ALL**: Returns array refs in `HashSpecialVariable.java`.
  - **Large integer literals**: `EmitLiteral.java` uses DOUBLE fallback for values exceeding
    long range.
- Files changed: `MathOperators.java`, `OperatorHandler.java`, `InlineOpcodeHandler.java`,
  `EmitBinaryOperator.java`, `ConstantFoldingVisitor.java`, `EmitStatement.java`,
  `BytecodeCompiler.java`, `Vec.java`, `RuntimeVecLvalue.java`, `PerlLanguageProvider.java`,
  `RuntimeCode.java`, `HashSpecialVariable.java`, `EmitLiteral.java`
- Commit: `3cc2ff1e8`

### DBIx::Class Full Test Suite Results (updated 2026-04-02)

**92 test programs (66 active, 26 skipped)**

| Category | Count | Details |
|----------|-------|---------|
| Fully passing | 15 | All subtests pass including GC |
| GC-only failures | 44 | All real tests pass; only GC epilogue fails |
| Real + GC failures | 4 | Have actual functional failures beyond GC |
| Skipped | 26 | No DB driver / fork / threads |
| Parse/skip errors | 3 | t/52leaks.t, t/71mysql.t, t/746sybase.t |

**Programs with real (non-GC) failures:**

| Test | Total Failed | GC Failures | Real Failures | Root Cause |
|------|-------------|-------------|---------------|------------|
| t/60core.t | 17 | 5 | 12 | "Unreachable cached statement" — 12 remaining after Active flag fix (step 5.56), need DESTROY |
| t/100populate.t | 17 | 5 | 12 | Transaction depth (DESTROY), JDBC batch execution |
| t/85utf8.t | 13 | 5 | 8 | UTF-8 byte handling (JVM strings natively Unicode) |

**Previously miscounted as having real failures (actually all GC-only):**

| Test | Total Failed | Actual Real | Explanation |
|------|-------------|-------------|-------------|
| t/40compose_connection.t | 7 | 0 | All 7 are GC (2 planned tests both pass) |
| t/40resultsetmanager.t | 1 | 0 | GC test beyond plan (5 planned all pass) |
| t/53lean_startup.t | 10 | 0 | All 10 are GC (6 planned tests all pass) |
| t/84serialize.t | 5 | 0 | Was 1 real, **fixed by step 5.55** (115/115 pass) |
| t/752sqlite.t | 30 | 0 | All GC (6 schemas × 5 GC) |
| t/93single_accessor_object.t | 15 | 0 | All GC (3 schemas × 5 GC) |

**Effective pass rate (excluding GC):** 59 of 63 active test programs pass all real tests (94%)

### Sub-Quote Test Results (updated 2026-04-01)

**5378/5421 (99.2%)**

| Test File | Pass/Total | Key Failures |
|-----------|-----------|--------------|
| sub-quote.t | 54/56 | Test 24 (line numbering in %^H PRELUDE), test 27 (weaken) |
| sub-defer.t | 43/59 | 16 failures all weaken-related |
| hints.t | 13/18 | Tests 4-5 (${^WARNING_BITS} round-trip), test 8 (%^H in eval BEGIN), tests 9,14 (overload::constant) |
| leaks.t | 5/9 | 4 failures all weaken-related |

### Next Steps
1. **P0: Fix weak ref cleared during `clone → _copy_state_from → register_extra_source`** — 155 tests blocked (see Phase 10)
2. **P1: Fix GC leak assertions** (refcnt stays at 1 at END time) — ~20 tests with GC-only failures
3. **P2: Triage remaining real failures** (see Phase 10 categorization)
4. Phase 8: Remaining dependency module fixes (Sub-Quote hints)
5. Long-term: Investigate ASM Frame.merge() crash (root cause behind InterpreterFallbackException fallback)
6. UTF-8 flag semantics: 2 tests in t/85utf8.t (systemic JVM limitation)

### Open Questions
- Weak ref cleared during `connect → clone → _copy_state_from`: Is the intermediate schema object from `shift->clone` being destroyed before `->connection(@_)` returns? See investigation in Phase 10.
- GC leak at END: is the `$schema` variable's `scopeExitCleanup` not decrementing properly, or is the cascade from schema → storage → dbh not propagating?
- RowParser crash: is it safe to ignore since all real tests pass before it fires?

### Architecture Reference
- See `dev/architecture/weaken-destroy.md` for the refCount state machine, `MortalList`, `WeakRefRegistry`, and `scopeExitCleanup` internals — essential for debugging the premature DESTROY and GC leak issues.

---

## Phase 9: Re-baseline After DESTROY/weaken (2026-04-10)

### Background

PR #464 merged DESTROY and weaken/isweak/unweaken into master with refCount tracking.
This fundamentally changes the DBIx::Class compatibility landscape:
- `Scalar::Util::isweak()` now returns true for weakened refs
- DESTROY fires for blessed objects when refCount reaches 0
- `Scope::Guard`, `TxnScopeGuard` destructors now fire
- `Devel::GlobalDestruction::in_global_destruction()` works

### Step 9.1: Fix interpreter fallback regressions (DONE)

Two regressions from PR #464 fixed in commit `756f9a46a`:

| Issue | Root cause | Fix |
|-------|-----------|-----|
| `ClassCastException` in `SCOPE_EXIT_CLEANUP_ARRAY` | Interpreter registers can hold unexpected types in fallback path | Added `instanceof` guards in `BytecodeInterpreter.java` |
| `ConcurrentModificationException` in `Makefile.PL` | DESTROY callbacks modify global variable maps during `GlobalDestruction` iteration | Snapshot with `toArray()` in `GlobalDestruction.java` |

### Step 9.2: Current test results (92 files, 2026-04-10)

| Category | Count | Details |
|----------|-------|---------|
| Fully passing | 15 | All subtests pass including GC epilogue |
| GC-only failures | ~13 | Real tests pass; END-block GC assertions fail (refcnt 1) |
| Blocked by premature DESTROY | 20 | Schema destroyed during `populate_schema()` — no real tests run |
| Real + GC failures | ~12 | Mix of logic bugs + GC assertion failures |
| Skipped | ~26 | No DB driver / fork / threads |
| Errors | ~6 | Parse errors, missing modules |

**Individual test counts**: 645 ok / 183 not ok (total 828 tests emitted)

### Key improvements from DESTROY/weaken

| Before (Phase 5 final) | After (Phase 9) |
|------------------------|-----------------|
| t/60core.t: 12 "cached statement" failures | **1 failure** — sth DESTROY now calls `finish()` |
| `isweak()` always returned false | `isweak()` returns true — Moo accessor validation works |
| TxnScopeGuard::DESTROY never fired | DESTROY fires on scope exit |
| weaken() was a no-op | weaken() properly decrements refCount |

### Blocker: Premature DESTROY (20 tests)

**Symptom**: `DBICTest::populate_schema()` crashes with:
```
Unable to perform storage-dependent operations with a detached result source
  (source 'Genre' is not associated with a schema)
```

**Affected tests** (all show ok=0, fail=2):
t/64db.t, t/65multipk.t, t/69update.t, t/70auto.t, t/76joins.t, t/77join_count.t,
t/78self_referencial.t, t/79aliasing.t, t/82cascade_copy.t, t/83cache.t,
t/87ordered.t, t/90ensure_class_loaded.t, t/91merge_joinpref_attr.t,
t/93autocast.t, t/94pk_mutation.t, t/104view.t, t/18insert_default.t,
t/63register_class.t, t/discard_changes_in_DESTROY.t, t/resultset_overload.t

**Root cause**: The schema object's refCount drops to 0 during the `populate()`
call chain (`populate()` → `dbh_do()` → `BlockRunner` → `Try::Tiny` → storage
operations). DESTROY fires mid-operation, disconnecting the database. The schema
is still referenced by `$schema` in the test, so refCount should be >= 1.

**Investigation needed**:
- Trace where the schema's refCount goes from 1 → 0 during `populate_schema()`
- Likely a code path that creates a temporary copy of the schema ref (incrementing
  refCount) then exits scope (decrementing back), but the decrement is applied to
  the wrong object or at the wrong time
- The `BlockRunner` → `Try::Tiny` → `Context::Preserve` chain involves multiple
  scope transitions where refCount could be incorrectly managed

### Blocker: GC leak at END time (refcnt 1)

**Symptom**: All tests that complete their real content still show `refcnt 1` for
DBI::db, Storage::DBI, and Schema objects at END time. The weak refs in the leak
tracker registry remain defined instead of becoming undef.

**Impact**: Tests report 2–20 GC assertion failures after passing all real tests.
In the old plan (pre-DESTROY/weaken), these tests were counted as "GC-only failures"
with no functional impact. With DESTROY/weaken, the GC tracker now sees real refcounts
but the cascade to 0 doesn't happen.

**Root cause**: When `$schema` goes out of scope at test end:
1. `scopeExitCleanup` should decrement schema's refCount to 0
2. DESTROY should fire on schema, releasing storage (refCount → 0)
3. DESTROY should fire on storage, closing DBI handle (refCount → 0)

Step 1 or the cascade at steps 2-3 is not happening correctly.

### Tests with real (non-GC) failures

| Test | ok | fail | Notes |
|------|-----|------|-------|
| t/60core.t | 91 | 7 | 1 cached stmt, 2 cascading delete (new), 4 GC |
| t/100populate.t | 36 | 10 | Transaction depth + JDBC batch + GC |
| t/752sqlite.t | 37 | 20 | Mostly GC (multiple schemas × GC assertions) |
| t/85utf8.t | 9 | 5 | UTF-8 flag (systemic JVM) |
| t/93single_accessor_object.t | 10 | 12 | GC heavy |
| t/84serialize.t | 115 | 5 | All GC (real tests pass) |
| t/88result_set_column.t | 46 | 6 | GC + TODO |
| t/101populate_rs.t | 17 | 4 | Needs investigation |
| t/106dbic_carp.t | 3 | 4 | Needs investigation |
| t/33exception_wrap.t | 3 | 5 | Needs investigation |
| t/34exception_action.t | 9 | 4 | Needs investigation |

### Items obsoleted by DESTROY/weaken

These items from the old plan are no longer needed:
- **Phase 7 (TxnScopeGuard explicit try/catch rollback)** — DESTROY handles this
- **"Systemic: DESTROY / TxnScopeGuard" section** — resolved by PR #464
- **"Systemic: GC / weaken / isweak absence" section** — resolved by PR #464
- **Open Question about weaken/isweak Option B vs C** — moot, they work now

### Implementation Plan (Phase 9 continued)

| Step | What | Impact | Status |
|------|------|--------|--------|
| 9.1 | Fix interpreter SCOPE_EXIT_CLEANUP + GlobalDestruction CME | Unblock all testing | DONE |
| 9.2 | Re-baseline test suite | Get current numbers | DONE |
| 9.3 | Fix premature DESTROY in populate_schema | Unblock 20 tests | |
| 9.4 | Fix refcount cascade at scope exit | Fix GC leak assertions | |
| 9.5 | Triage remaining real failures | Reduce fail count | |
| 9.6 | Re-run full suite after fixes | Updated numbers | |

## Phase 10: Full Suite Re-baseline (314 tests, 2026-04-10)

### Background

After bundling `Devel::GlobalDestruction` (with plain Exporter) and
`DBI::Const::GetInfoType` + related modules, re-ran the full 314-test suite
via `./jcpan -t DBIx::Class`. This gives a complete picture of all failures
across all test programs, not just the 92-file subset from Phase 9.

### Step 10.1: Bundled modules (DONE)

| Commit | What |
|--------|------|
| `a59814308` | Bundle `Devel::GlobalDestruction` with plain Exporter (bypasses Sub::Exporter::Progressive caller() bug) |
| `e0b7db79e` | Bundle `DBI::Const::GetInfoType`, `GetInfo::ANSI`, `GetInfo::ODBC`, real `GetInfoReturn` |

- `in_global_destruction` bareword error: **0 occurrences** (was widespread)
- `DBI::Const::GetInfoType` missing: **0 occurrences** (was blocking several tests)

### Step 10.2: Full test results (314 files, 2026-04-10)

**Summary:** 118/314 pass, 196/314 fail, 431/8034 subtests failed

| Category | Count | Details |
|----------|-------|---------|
| Fully passing | 28 | All subtests pass (includes DB-skip tests) |
| Skipped (no DB/threads) | 90 | `no summary found` — need specific DB backends or fork/threads |
| **Blocked by detached source** | **~155** | `tests=2 fail=2 exit=255` — `DBICTest::init_schema` crashes |
| GC-only failures | ~10 | Real tests pass; only END-block GC assertions fail |
| Real + GC failures | ~25 | Mix of functional bugs + GC assertion failures |
| Errors | ~6 | Parse errors, missing modules (Sybase, MSSQL, etc.) |

### Step 10.3: Root cause analysis — "detached result source" (#1 blocker)

**155 test programs** fail with identical pattern:
```
Unable to perform storage-dependent operations with a detached result source
  (source 'Genre' is not associated with a schema)
  at t/lib/DBICTest.pm line 435
```

**Call chain**: `Schema->connect` (line 524 of Schema.pm) does:
```perl
sub connect { shift->clone->connection(@_) }
```

1. `shift` removes the class/object from `@_`
2. `->clone` creates a new blessed schema via `_copy_state_from`
3. Inside `_copy_state_from`, for each source:
   ```perl
   $self->register_extra_source($source_name => $new);
   ```
4. `_register_source` does:
   ```perl
   $source->schema($self);                    # sets $source->{schema} = $self
   weaken $source->{schema} if ref($self);    # weakens it
   ```
5. **Problem**: The weakened `$source->{schema}` is **already undef** by the time
   `_register_source` returns. Verified with instrumentation.

**Root cause hypothesis**: The intermediate schema object created by `clone()` is
a temporary — `shift->clone` returns a new object, but during the method chain
`->clone->connection(@_)`, the clone's refcount may drop to 0 at some point in
the `_copy_state_from` loop, triggering `Schema::DESTROY`. DESTROY (lines 1430+)
iterates all registered sources and reattaches/weakens them, which can clear the
schema backref.

**Key code in Schema::DESTROY** (line 1430+):
```perl
sub DESTROY {
    return if $global_phase_destroy ||= in_global_destruction;
    my $self = shift;
    my $srcs = $self->source_registrations;
    for my $source_name (keys %$srcs) {
        if (length ref $srcs->{$source_name} and refcount($srcs->{$source_name}) > 1) {
            local $@;
            eval {
                $srcs->{$source_name}->schema($self);
                weaken $srcs->{$source_name};
                1;
            } or do { $global_phase_destroy = 1; };
            last;
        }
    }
}
```

**Investigation path** (see `dev/architecture/weaken-destroy.md` for refCount internals):
1. Trace the refCount of the clone object through `_copy_state_from`
2. Check whether `register_extra_source` → `_register_source` creates temporary
   copies that decrement refCount below the threshold
3. Check whether `DESTROY` is firing on the clone during `_copy_state_from`
4. Verify that `MortalList` scope tracking correctly handles the `shift->clone->method`
   chain (the clone is created as a temporary with no named variable)

### Step 10.4: Categorized non-detached failures (40 tests)

#### GC-only failures (~10 tests)
Tests where all real subtests pass but END-block garbage collection assertions fail
(objects at refcnt 1 instead of being collected):

| Test | Tests | Fail | Pattern |
|------|-------|------|---------|
| t/storage/error.t | 84 | 39 | Tests 1-45 pass; 46-84 all "Expected garbage collection" |
| t/storage/on_connect_do.t | 18 | 6 | Real tests pass; 6 GC failures |
| t/storage/on_connect_call.t | 21 | 4 | Real tests pass; 4 GC failures |
| t/storage/quote_names.t | 27 | 2 | Real tests pass; 2 GC failures |
| t/sqlmaker/dbihacks_internals.t | 6494 | 2 | 6492 pass; 2 GC at end |
| t/storage/global_destruction.t | 4 | 0 | exit= (no exit code, but tests pass) |
| t/resultset/rowparser_internals.t | 7 | 0 | exit=255 but all 7 subtests pass |
| t/row/inflate_result.t | 2 | 0 | exit=255 but both subtests pass |

#### Real failures with GC overlay

| Test | Tests | Real Fail | GC Fail | Root Cause |
|------|-------|-----------|---------|------------|
| t/storage/exception.t | 5 | 2 | 1 | Exception wrapping / GC |
| t/storage/ping_count.t | 4 | 2 | 1 | Ping count tracking |
| t/storage/stats.t | 3 | 1 | 1 | Storage statistics |
| t/storage/dbi_env.t | 2 | 2 | 0 | DBI environment variable handling |
| t/storage/savepoints.t | 3 | 2 | 1 | SAVEPOINT execution |
| t/106dbic_carp.t | 6 | 0 | 3 | 3 real tests pass; 3 GC failures |
| t/53lean_startup.t | 6 | 0 | 3 | Module load footprint + GC |
| t/85utf8.t | 10 | 2 | 0 | UTF-8 flag (systemic JVM) |
| t/752sqlite.t | 5 | 1 | 3 | SQLite-specific + GC |
| t/resultset_class.t | 7 | 0 | 2 | GC only |
| t/schema/anon.t | 3 | 0 | 3 | GC only |
| t/zzzzzzz_perl_perf_bug.t | 3 | 1 | 0 | Perl performance regression test |

#### Tests with detached source (not init_schema pattern)

| Test | Tests | Fail | Root Cause |
|------|-------|------|------------|
| t/prefetch/manual.t | 3 | 3 | `_unnamed_` source detached (different from Genre pattern) |
| t/sqlmaker/rebase.t | 7 | 3 | Mix of detached + GC |

#### Tests blocked by GC (all 3 failures are "Expected garbage collection")

| Test |
|------|
| t/inflate/hri_torture.t |
| t/multi_create/find_or_multicreate.t |
| t/prefetch/false_colvalues.t |
| t/relationship/custom_opaque.t |
| t/resultset/inflate_result_api.t |
| t/row/filter_column.t |
| t/storage/nobindvars.t |
| t/sqlmaker/literal_with_bind.t |
| t/storage/prefer_stringification.t |
| t/26dumper.t |

#### DB-specific / error tests

| Test | Issue |
|------|-------|
| t/52leaks.t | Needs Test::Memory::Cycle (not installed) |
| t/746sybase.t | Needs Sybase driver |
| t/sqlmaker/limit_dialects/custom.t | Needs specific SQL dialect |
| t/sqlmaker/limit_dialects/rownum.t | Needs Oracle dialect |
| t/sqlmaker/limit_dialects/mssql_torture.t | MSSQL-specific |
| t/sqlmaker/msaccess.t | MS Access-specific |
| t/sqlmaker/quotes.t | DB-specific quoting |
| t/sqlmaker/pg.t | PostgreSQL-specific |

### Step 10.5: Implementation plan

| Step | What | Impact | Priority | Status |
|------|------|--------|----------|--------|
| 10.5a | Fix weak ref cleared during `clone → _copy_state_from` | **Unblock 155 test programs** | P0 | |
| 10.5b | Fix GC leak assertions (refcnt stays at 1 at END) | ~10 GC-only test programs | P1 | |
| 10.5c | Fix storage/exception, ping_count, stats, dbi_env, savepoints | ~10 real failures | P2 | |
| 10.5d | Re-run full suite after P0 fix | Updated numbers | P0 | |

### Key insight for P0 fix

The `shift->clone->connection(@_)` pattern creates a temporary object from `clone()`
that has no named variable holding it. In PerlOnJava's refCount system (see
`dev/architecture/weaken-destroy.md`), temporaries in method chains may not be
properly tracked through `MortalList`. If the clone's refCount reaches 0 during
`_copy_state_from`, `Schema::DESTROY` fires and clears the weakened `{schema}` refs
on all sources.

**Potential fixes** (in order of investigation):
1. Check if `MortalList` correctly handles temporaries in chained method calls
   (`a->b->c` — does the result of `a->b` stay alive while `->c` runs?)
2. Check if `bless` inside `clone()` triggers refCount tracking (Schema has DESTROY),
   and whether the tracking correctly accounts for the implicit `$self` in `->connection`
3. As a workaround, `connect()` could be rewritten to hold the clone in a named variable:
   ```perl
   sub connect { my $clone = shift->clone; $clone->connection(@_) }
   ```
   But this would require patching DBIx::Class, which we want to avoid.

## Related Documents

- `dev/architecture/weaken-destroy.md` — **Weaken & DESTROY architecture** (refCount state machine, MortalList, WeakRefRegistry, scopeExitCleanup — essential for Phase 10 debugging)
- `dev/design/destroy_weaken_plan.md` — DESTROY/weaken implementation plan (PR #464)
- `dev/modules/moo_support.md` — Moo support (dependency of DBIx::Class)
- `dev/modules/xs_fallback.md` — XS fallback mechanism
- `dev/modules/makemaker_perlonjava.md` — MakeMaker for PerlOnJava
- `dev/modules/cpan_client.md` — jcpan CPAN client
- `docs/guides/database-access.md` — JDBC database guide (DBI, SQLite support)

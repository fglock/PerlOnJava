# DBIx::Class Fix Plan

## Overview

**Module**: DBIx::Class 0.082844  
**Test command**: `./jcpan -t DBIx::Class`  
**Branch**: `feature/dbix-class-support`  
**PR**: https://github.com/fglock/PerlOnJava/pull/415  
**Status**: Phase 5 — Fix runtime issues iteratively

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

**t/60core.t results** (17 tests emitted):
- **ok 1–12**: Basic CRUD, update, dirty columns — all pass
- **not ok 13–17**: Garbage collection tests — expected failures (JVM has no reference counting / `weaken`)
- RowParser.pm line 260 crash still occurs in END block cleanup (non-blocking — all real tests pass first)

**Full test suite results** (92 test files, updated 2026-04-01):
- **21 fully passing** (no failures at all)
- **39 GC-only failures** (all real tests pass, only `weaken`-based GC leak tests fail)
- **8 tests with real failures** (see Blocking Issues below)
- **22 skipped** (DB-specific: Pg, Oracle, MSSQL, etc.; threads; fork)
- **2 compile-error** (t/52leaks.t — `wait` operator; t/88result_set_column.t — zero tests)

**Effective pass rate**: 60/68 active test files have all real tests passing (88%).
Previous: 51/65 (78%) → Current: 60/68 (88%) — **+10 percentage points**.

---

## Blocking Issues — Not Quick Fixes

### HIGH PRIORITY: VerifyError (bytecode compiler bug)

**Symptom**: `java.lang.VerifyError: Bad type on operand stack` when compiling complex anonymous subroutines with many local variables.

**Affected tests**: `t/00describe_environment.t` (crashes after already emitting `1..0` skip)

**Root cause**: The JVM bytecode emitter generates incorrect stack map frames when a subroutine has many locals and complex control flow (ternary chains, nested `eval`, `for` loops). The JVM verifier rejects the class because `java/lang/Object` on the stack is not assignable to `RuntimeScalar`.

**What's needed to fix**:
- Debug the bytecode emitter's stack map frame generation (likely in `EmitSubroutine.java` or related emit classes)
- The anonymous sub `anon2920` in the test has ~100 local variable slots and deeply nested control flow
- May need to split large subroutines or fix how the stack map calculator handles branch merging
- This is the same class of bug as the File::stat VerifyError (see Known Bugs below)

**Impact**: Currently low for DBIx::Class (test already skips), but affects any complex Perl subroutine. Could block other CPAN modules.

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

## Remaining Real Failures (8 tests)

### Tests needing DBI/Storage fixes — RESOLVED

| Test | Status | What was fixed |
|------|--------|----------------|
| `t/64db.t` | **FIXED** (4/4 real pass) | `column_info()` implemented via SQLite PRAGMA (step 5.13) |
| `t/752sqlite.t` | **FIXED** (34/34 real pass) | AutoCommit tracking + BEGIN/COMMIT/ROLLBACK interception (steps 5.14-5.15); `prepare_cached` per-dbh cache (step 5.16) |

### Tests needing caller/carp fixes

| Test | Failing | Root cause | Fix needed |
|------|---------|------------|------------|
| `t/106dbic_carp.t` | tests 2-3 | DBIx::Class::Carp callsite detection — `caller()` returns wrong package/line; also `__LINE__` inside `qr//` differs from Perl 5 | Fix `caller()` to return correct info through `namespace::clean`'d frames |
| `t/100populate.t` | test 2 | `execute_for_fetch()` doesn't throw on duplicate key constraint violation | DBI needs unique constraint error propagation |
| `t/101populate_rs.t` | test(s) | Similar to t/100populate.t — `execute_for_fetch` exception handling | Same as above |

### Tests needing serialization/Storable fixes

| Test | Failing | Root cause | Fix needed |
|------|---------|------------|------------|
| `t/84serialize.t` | test 2 | `Storable::dclone` fails on blessed DBI handle objects | Need `dclone` to handle Java-backed objects or provide STORABLE_freeze/thaw hooks in DBI |

### Tests needing module loading fixes

| Test | Failing | Root cause | Fix needed |
|------|---------|------------|------------|
| `t/90ensure_class_loaded.t` | tests 14,17,28 | PAR (Perl Archive) detection + `$INC{...}` manipulation edge cases | Fix `%INC` handling for modules that set `$INC{file}` without returning true |
| `t/40resultsetmanager.t` | tests 2-4 | Deprecated `ResultSetManager` uses source filtering (`Module::Pluggable` + runtime class creation) | Likely needs `Module::Pluggable` fixes or is acceptable as deprecated-feature failure |
| `t/53lean_startup.t` | test 5 | Module loading tracking — test checks exact set of loaded modules | PerlOnJava loads extra modules; would need to match exact Perl load footprint |

### Tests needing misc fixes

| Test | Failing | Root cause | Fix needed |
|------|---------|------------|------------|
| `t/85utf8.t` | test 7 | Warning about incorrect `use utf8` ordering not issued | May need to implement `utf8` pragma ordering detection |

### GC-only failures (not real failures)

| Test | GC failures | Notes |
|------|-------------|-------|
| `t/40compose_connection.t` | 7 | All real tests pass |
| `t/93single_accessor_object.t` | 15 | All real tests pass |
| `t/752sqlite.t` | 25 | All 34 real tests pass |
| 36 other files | 5 each | Standard GC leak detection tests |

---

## Known Bugs

### File::stat VerifyError
- `use File::stat` triggers `java.lang.VerifyError: Bad type on operand stack`
- Root cause: bytecode generation issue with `Class::Struct` + `use overload` (`-X` operator)
- Minimal repro: `use Class::Struct; use overload ("-X" => sub { "" }, fallback => 1); struct( 'Foo' => [dev => "\$", ino => "\$"] );`
- Impact: Path::Class cannot load; DBIx::Class works without it
- Same class of bug as the t/00describe_environment.t VerifyError (see HIGH PRIORITY above)

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

### Next Steps
1. **Medium**: Fix caller/carp callsite detection (fixes t/106dbic_carp.t)
2. **Medium**: Fix `execute_for_fetch` exception propagation on constraint violations (fixes t/100populate.t, t/101populate_rs.t)
3. **Long-term**: Investigate VerifyError bytecode compiler bug (HIGH PRIORITY for broader CPAN compat)
4. **Pragmatic**: Accept GC-only failures as known JVM limitation; consider adding skip-leak-tests env var

### Open Questions
- `weaken`/`isweak` absence causes GC test noise but no functional impact — Option B (accept) or Option C (skip env var)?
- VerifyError: is this specific to `overload`-heavy code or a general large-subroutine issue?
- RowParser crash: is it safe to ignore since all real tests pass before it fires?

## Related Documents

- `dev/modules/moo_support.md` — Moo support (dependency of DBIx::Class)
- `dev/modules/xs_fallback.md` — XS fallback mechanism
- `dev/modules/makemaker_perlonjava.md` — MakeMaker for PerlOnJava
- `dev/modules/cpan_client.md` — jcpan CPAN client
- `docs/guides/database-access.md` — JDBC database guide (DBI, SQLite support)

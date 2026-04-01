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

`Class::Accessor::Grouped` uses `wantarray ? @rv = expr : $rv[0] = expr`.
`LValueVisitor` threw "Assignment to both a list and a scalar" at compile time.

| Step | Description | File | Status |
|------|-------------|------|--------|
| 4.7.1 | Default to LIST context when ternary branches disagree | `LValueVisitor.java` | DONE |

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
| 5.9 | Fix "Not a HASH reference" in RowParser.pm (join/prefetch) | TBD | **NEXT** |

**t/60core.t results** (17 tests emitted):
- **ok 1–12**: Basic CRUD, update, dirty columns — all pass
- **not ok 13–17**: Garbage collection tests — expected failures (JVM has no reference counting / `weaken`)
- **Crash after test 17**: `Not a HASH reference at RowParser.pm line 260` — blocks remaining tests

**Result so far**: 12 / 17 real tests pass (5 GC failures are expected and acceptable).

## Known Bugs (not yet blocking)

### File::stat VerifyError
- `use File::stat` triggers `java.lang.VerifyError: Bad type on operand stack`
- Root cause: bytecode generation issue with `Class::Struct` + `use overload` (`-X` operator)
- Minimal repro: `use Class::Struct; use overload ("-X" => sub { "" }, fallback => 1); struct( 'Foo' => [dev => "\$", ino => "\$"] );`
- Impact: Path::Class cannot load; DBIx::Class may work without it depending on test requirements

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
  - Changed LValueVisitor to default to LIST context when ternary branches disagree
  - Unblocks Class::Accessor::Grouped
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

### Next Steps
1. Investigate "Not a HASH reference" at RowParser.pm line 260 (triggered by join/prefetch queries)
2. Continue triaging t/60core.t failures after fixing RowParser issue
3. Run broader DBIx::Class test suite once core tests pass

### Open Questions
- Will `weaken`/`isweak` absence cause problems beyond memory leaks?
- Does File::stat VerifyError block any DBIx::Class tests?

## Related Documents

- `dev/modules/moo_support.md` — Moo support (dependency of DBIx::Class)
- `dev/modules/xs_fallback.md` — XS fallback mechanism
- `dev/modules/makemaker_perlonjava.md` — MakeMaker for PerlOnJava
- `dev/modules/cpan_client.md` — jcpan CPAN client
- `docs/guides/database-access.md` — JDBC database guide (DBI, SQLite support)

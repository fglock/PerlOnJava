# DBIx::Class Fix Plan

## Overview

**Module**: DBIx::Class 0.082844  
**Test command**: `./jcpan -t DBIx::Class`  
**Branch**: `feature/dbix-class-support`  
**PR**: https://github.com/fglock/PerlOnJava/pull/415  
**Status**: Phase 2 — Installing missing pure-Perl dependencies

## Dependency Tree

### Runtime Dependencies

| Dependency | Required | Status | Notes |
|-----------|---------|--------|-------|
| DBI | >= 1.57 | PASS | Bundled Java JDBC implementation |
| Sub::Name | >= 0.04 | PASS | Bundled Java implementation |
| Try::Tiny | >= 0.07 | PASS | Bundled pure Perl |
| Text::Balanced | >= 2.00 | PASS | Bundled core module |
| Moo | >= 2.000 | PASS | Installed (v2.005005) via jcpan |
| Sub::Quote | >= 2.006006 | PASS | Installed via jcpan |
| MRO::Compat | >= 0.12 | PASS | Installed (v0.15); uses native `mro` on PerlOnJava |
| namespace::clean | >= 0.24 | PASS | Installed (v0.27) |
| Scope::Guard | >= 0.03 | PASS | Installed |
| Class::Inspector | >= 1.24 | PASS | Installed |
| Class::Accessor::Grouped | >= 0.10012 | **MISSING** | Pure Perl; optional XS via Class::XSAccessor |
| Class::C3::Componentised | >= 1.0009 | **MISSING** | Pure Perl; depends on Class::C3 |
| Config::Any | >= 0.20 | **MISSING** | Pure Perl |
| Context::Preserve | >= 0.01 | **MISSING** | Pure Perl |
| Data::Dumper::Concise | >= 2.020 | **MISSING** | Pure Perl; thin wrapper around Data::Dumper |
| Devel::GlobalDestruction | >= 0.09 | **MISSING** | Pure Perl fallback using `${^GLOBAL_PHASE}` |
| Hash::Merge | >= 0.12 | **MISSING** | Pure Perl |
| Module::Find | >= 0.07 | **MISSING** | Pure Perl |
| Path::Class | >= 0.18 | **MISSING** | Pure Perl |
| SQL::Abstract::Classic | >= 1.91 | **MISSING** | Pure Perl; depends on SQL::Abstract + Moo |

### Test Dependencies

| Dependency | Status | Notes |
|-----------|--------|-------|
| Test::More | >= 0.94 | PASS | Bundled |
| Test::Deep | >= 0.101 | PASS | Installed |
| Test::Warn | >= 0.21 | PASS | Installed |
| File::Temp | >= 0.22 | PASS | Bundled Java implementation |
| Package::Stash | >= 0.28 | PASS | Installed (PP fallback) |
| Test::Exception | >= 0.31 | **MISSING** | Pure Perl |
| DBD::SQLite | >= 1.29 | **MISSING** | XS; needs JDBC shim (see Phase 4) |

### Supporting Modules (already installed)

B::Hooks::EndOfScope, Package::Stash::PP, Role::Tiny, Class::Method::Modifiers,
Module::Implementation, Module::Runtime, Params::Util, Exporter::Tiny, Type::Tiny,
Scalar::Util, List::Util, Storable, Data::Dumper, mro, namespace::autoclean,
Sub::Util, Dist::CheckConflicts, Eval::Closure.

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

### Phase 2: Install missing pure-Perl dependencies (CURRENT)

| Step | Description | Status |
|------|-------------|--------|
| 2.1 | `./jcpan install Devel::GlobalDestruction` | |
| 2.2 | `./jcpan install Context::Preserve` | |
| 2.3 | `./jcpan install Data::Dumper::Concise` | |
| 2.4 | `./jcpan install Module::Find` | |
| 2.5 | `./jcpan install Path::Class` | |
| 2.6 | `./jcpan install Hash::Merge` | |
| 2.7 | `./jcpan install Config::Any` | |
| 2.8 | `./jcpan install Class::Accessor::Grouped` | |
| 2.9 | `./jcpan install Class::C3::Componentised` | |
| 2.10 | `./jcpan install SQL::Abstract::Classic` | |
| 2.11 | `./jcpan install Test::Exception` | |
| 2.12 | Re-run `./jcpan -t DBIx::Class` — expect Makefile.PL to succeed and install | |

**Result**: All runtime deps satisfied; DBIx::Class can configure and build.

### Phase 3: Fix DBI version detection

Makefile.PL reports `DBI ...too old. (undef < 1.57)`. PerlOnJava's DBI is Java-backed
and may not expose `$DBI::VERSION` correctly. Verify and fix if needed.

| Step | Description | Status |
|------|-------------|--------|
| 3.1 | Check `./jperl -e 'use DBI; print $DBI::VERSION'` | |
| 3.2 | Fix DBI.java to set VERSION if missing | |

### Phase 4: Create DBD::SQLite JDBC shim

| Step | Description | File | Status |
|------|-------------|------|--------|
| 4.1 | Create `DBD::SQLite` shim translating DSN format | `src/main/perl/lib/DBD/SQLite.pm` | |
| 4.2 | Ensure sqlite-jdbc driver is on classpath | build config | |
| 4.3 | Verify: `DBI->connect("dbi:SQLite:dbname=:memory:")` works | manual test | |
| 4.4 | Run DBIx::Class test subset against in-memory SQLite | manual test | |

**Result**: DBIx::Class tests can connect to a database and run.

### Phase 5: Fix runtime issues (iterative)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 5.1 | Run `./jcpan -t DBIx::Class` and triage failures | | |
| 5.2 | Stub or fix `B::svref_2object` for `_Util.pm::refcount()` | TBD | |
| 5.3 | Verify Storable works with DBIC's complex structures | | |
| 5.4 | Fix additional issues as discovered | TBD | |

**Result**: Maximise passing DBIx::Class tests.

## Summary

| Phase | Complexity | Description | Status |
|-------|-----------|-------------|--------|
| 1 | Medium | Unblock Makefile.PL (4 engine fixes) | DONE |
| 2 | Medium | Install ~11 missing pure-Perl deps via jcpan | **CURRENT** |
| 3 | Simple | Fix DBI version detection | |
| 4 | Medium | Create DBD::SQLite JDBC compatibility shim | |
| 5 | Complex | Fix runtime issues iteratively | |

## Progress Tracking

### Current Status: Phase 2 in progress

### Completed Phases
- [x] Phase 1: Unblock Makefile.PL (2024-03-31)
  - Blocker 1: Added strict::bits to Strict.java
  - Blocker 2: Fixed UNIVERSAL::can AUTOLOAD filter in Universal.java
  - Blocker 3: Fixed goto &sub wantarray propagation (EmitSubroutine.java, Dereference.java) + eval{} @_ sharing (EmitSubroutine.java)
  - Blocker 4: Fixed +{} hash constructor parsing in IdentifierParser.java
  - All unit tests pass, Makefile.PL completes successfully

### Next Steps
1. Install missing pure-Perl dependencies (Phase 2)
2. Fix DBI version detection (Phase 3)
3. Create DBD::SQLite shim (Phase 4)

### Open Questions
- Does `$DBI::VERSION` report correctly? (Makefile.PL says "undef < 1.57")
- Will `weaken`/`isweak` absence cause problems beyond memory leaks?

## Related Documents

- `dev/modules/moo_support.md` — Moo support (dependency of DBIx::Class)
- `dev/modules/xs_fallback.md` — XS fallback mechanism
- `dev/modules/makemaker_perlonjava.md` — MakeMaker for PerlOnJava
- `dev/modules/cpan_client.md` — jcpan CPAN client
- `docs/guides/database-access.md` — JDBC database guide (DBI, SQLite support)

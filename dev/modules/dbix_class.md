# DBIx::Class Fix Plan

## Overview

**Module**: DBIx::Class 0.082844  
**Test command**: `./jcpan -t DBIx::Class`  
**Status**: BLOCKED — Makefile.PL fails at `strict::bits()` call

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

## Error Categories

### 1. CRITICAL: `strict::bits` not implemented

**Affected**: Makefile.PL via inc::Module::Install, also bundled `vars.pm`  
**Error**:
```
Undefined subroutine &strict::bits called at Makefile.PL line 26.
```

**Root Cause**: PerlOnJava's `strict.pm` delegates to `Strict.java` which only registers
`import`/`unimport`. The `bits()` function (and `all_bits`, `all_explicit_bits`) are not
exposed to Perl code. The bitmask constants exist in Java but aren't callable.

Module::Install's `import()` calls `$^H |= strict::bits(qw(refs subs vars))` to apply
strict pragmas. PerlOnJava's own `vars.pm` line 23 also calls `strict::bits('vars')`.

**Fix**: Add `bits`, `all_bits`, `all_explicit_bits` to Strict.java, matching the
reference implementation in `perl5/lib/strict.pm`.

**Files to change**: `src/main/java/org/perlonjava/runtime/perlmodule/Strict.java`

---

### 2. HIGH: Missing pure-Perl dependencies

**Affected**: DBIx::Class runtime  
**Error**: Will manifest as `Can't locate X.pm in @INC` after Phase 1

**Root Cause**: 10 pure-Perl dependencies are not yet installed. All have pure-Perl
fallbacks or are entirely pure Perl, so they should install via `./jcpan install`
once the `strict::bits` blocker is cleared.

**Fix**: Install each via `./jcpan install --notest ModuleName`, iterating on any
failures. Expected install order (respecting transitive deps):

1. Devel::GlobalDestruction (uses `${^GLOBAL_PHASE}`, no XS needed for Perl >= 5.14)
2. Context::Preserve
3. Data::Dumper::Concise (trivial wrapper)
4. Module::Find
5. Path::Class
6. Hash::Merge
7. Config::Any
8. Class::Accessor::Grouped (optional XS via Class::XSAccessor — PP fallback)
9. Class::C3::Componentised (depends on Class::C3 + MRO::Compat)
10. SQL::Abstract + SQL::Abstract::Classic (depends on Moo, Sub::Quote — already installed)
11. Test::Exception (test dep)

---

### 3. HIGH: DBD::SQLite — XS module needs JDBC shim

**Affected**: DBIx::Class test suite  
**Error**: `Can't locate DBD/SQLite.pm in @INC`

**Root Cause**: DBD::SQLite is an XS module wrapping the C SQLite library. PerlOnJava's
DBI is JDBC-based and already supports SQLite via `jdbc:sqlite:path`. However, DBIx::Class
tests use the standard Perl DBI DSN format: `dbi:SQLite:dbname=file`.

**Fix**: Create a minimal `DBD::SQLite` compatibility shim that:
- Translates `dbi:SQLite:dbname=...` DSNs to `jdbc:sqlite:...`
- Provides the subset of DBD::SQLite API that DBIx::Class tests use
- Leverages the existing JDBC SQLite driver (sqlite-jdbc)

**Files to create**: `src/main/perl/lib/DBD/SQLite.pm`

---

### 4. MEDIUM: Potential runtime issues in DBIx::Class internals

These may surface after Phases 1-3 are complete:

- **`B::svref_2object`**: Used in `_Util.pm` for `refcount()`. PerlOnJava's B module is
  incomplete (`$INCOMPLETE = 1`). May need a stub or alternative.
- **`Storable::nfreeze`**: Used for serialization. PerlOnJava has a bundled Storable —
  needs verification with DBIC's complex structures.
- **`weaken`/`isweak`**: PerlOnJava does not implement weak references. DBIC uses these
  for row object cleanup. May cause memory leaks but shouldn't block functionality.

---

## Fix Plan

### Phase 1: Implement `strict::bits` (CRITICAL)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 1.1 | Add `strictBits()` Java method returning bitmask OR of categories | Strict.java | |
| 1.2 | Add `allBits()` returning `REFS \| SUBS \| VARS` (0x602) | Strict.java | |
| 1.3 | Add `allExplicitBits()` returning explicit bits (0xe0) | Strict.java | |
| 1.4 | Register `bits`, `all_bits`, `all_explicit_bits` methods | Strict.java | |
| 1.5 | Verify: `./jperl -e 'print strict::bits("vars")'` prints 1024 | manual test | |
| 1.6 | Verify: `./jperl -e 'use vars qw($x); print "ok"'` works | manual test | |
| 1.7 | Run `make` to ensure no regressions | build | |

**Result**: Unblocks Module::Install and any other CPAN module using `strict::bits`.

### Phase 2: Install missing pure-Perl dependencies

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
| 2.12 | Re-run `./jcpan -t DBIx::Class` — expect Makefile.PL to succeed | |

**Result**: All runtime deps satisfied; DBIx::Class can configure and build.

### Phase 3: Create DBD::SQLite JDBC shim

| Step | Description | File | Status |
|------|-------------|------|--------|
| 3.1 | Create `DBD::SQLite` shim translating DSN format | `src/main/perl/lib/DBD/SQLite.pm` | |
| 3.2 | Ensure sqlite-jdbc driver is on classpath | build config | |
| 3.3 | Verify: `DBI->connect("dbi:SQLite:dbname=:memory:")` works | manual test | |
| 3.4 | Run DBIx::Class test subset against in-memory SQLite | manual test | |

**Result**: DBIx::Class tests can connect to a database and run.

### Phase 4: Fix runtime issues (iterative)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 4.1 | Run `./jcpan -t DBIx::Class` and triage failures | | |
| 4.2 | Stub or fix `B::svref_2object` for `_Util.pm::refcount()` | TBD | |
| 4.3 | Verify Storable works with DBIC's complex structures | | |
| 4.4 | Fix additional issues as discovered | TBD | |

**Result**: Maximise passing DBIx::Class tests.

## Summary

| Phase | Complexity | Description | Status |
|-------|-----------|-------------|--------|
| 1 | Simple | Implement `strict::bits` in Strict.java | |
| 2 | Medium | Install ~11 missing pure-Perl deps via jcpan | |
| 3 | Medium | Create DBD::SQLite JDBC compatibility shim | |
| 4 | Complex | Fix runtime issues iteratively | |

## Progress Tracking

### Current Status: Phase 1 not started

### Completed Phases
(none yet)

### Next Steps
1. Implement `strict::bits` in Strict.java
2. Re-run `./jcpan -t DBIx::Class` to see next error
3. Install missing dependencies

## Related Documents

- `dev/modules/moo_support.md` — Moo support (dependency of DBIx::Class)
- `dev/modules/xs_fallback.md` — XS fallback mechanism
- `dev/modules/makemaker_perlonjava.md` — MakeMaker for PerlOnJava
- `dev/modules/cpan_client.md` — jcpan CPAN client
- `docs/guides/database-access.md` — JDBC database guide (DBI, SQLite support)

# Memoize Support Plan for PerlOnJava

## Overview

**Module:** Memoize 1.17 (CPAN: ARISTOTLE)
**Bundled in PerlOnJava:** No (available via `jcpan`, candidate for bundling)
**Test command:** `./jcpan -t Memoize`
**Type:** Pure Perl (no XS)

Memoize caches function return values, speeding up expensive computations.
It is a core Perl module since Perl 5.8. All its direct dependencies are
already satisfied in PerlOnJava.

## Current Status

**Branch:** `docs/cpan-reports-scalar-util-memoize`

### Results History

| Date | Programs Failed | Subtests Failed | Total Subtests | Key Fix |
|------|----------------|-----------------|----------------|---------|
| 2026-04-13 | 5/16 (4 skipped) | 0/206 explicit | 206 | Baseline (via jcpan) |

### Test Results Summary

| Test File | Status | Subtests | Root Cause |
|-----------|--------|----------|------------|
| t/basic.t | PASS | ok | Core memoize/unmemoize/INSTALL/NORMALIZER |
| t/cache.t | PASS | ok | SCALAR_CACHE/LIST_CACHE with MEMORY/FAULT/MERGE |
| t/correctness.t | FAIL | 16/17 | **Bug 1: StackOverflowError** in deep recursion test |
| t/expmod.t | PASS | ok | Memoize::Expire |
| t/expmod_t.t | PASS | ok | Timed expiration |
| t/flush.t | PASS | ok | flush_cache() |
| t/normalize.t | PASS | ok | NORMALIZER option |
| t/threadsafe.t | FAIL | 1/8 | Requires `threads` module (not available) |
| t/tie.t | FAIL | 0/7 | **Bug 2: DB_File infinite recursion** |
| t/tie_db.t | FAIL | 0/7 | **Bug 2: DB_File infinite recursion** |
| t/tie_gdbm.t | SKIP | — | Could not load GDBM_File |
| t/tie_ndbm.t | SKIP | — | Could not load Memoize::NDBM_File |
| t/tie_odbm.t | SKIP | — | Could not load ODBM_File |
| t/tie_sdbm.t | SKIP | — | Could not load SDBM_File |
| t/tie_storable.t | FAIL | 5/6 | 1 subtest not reached |
| t/unmemoize.t | PASS | ok | unmemoize() |

---

## Dependency Analysis

### Direct Dependencies (all satisfied)

| Dependency | Available | Location |
|-----------|-----------|----------|
| Carp | Yes | `src/main/perl/lib/Carp.pm` |
| Scalar::Util (>=1.11) | Yes (v1.63) | Java backend: `ScalarUtil.java` |
| Exporter | Yes | `src/main/perl/lib/Exporter.pm` |
| warnings | Yes | Java backend: `Warnings.java` |

### Sub-module Dependencies

| Sub-module | Dependency | Available |
|-----------|-----------|-----------|
| Memoize::Expire | Time::HiRes | Yes (Java impl) |
| Memoize::Storable | Storable (lock_store) | Yes (stub locking) |
| Memoize::AnyDBM_File | AnyDBM_File | No |
| Memoize::NDBM_File | NDBM_File | No |
| Memoize::SDBM_File | SDBM_File | No |

### Key Language Features Used

| Feature | Used For | PerlOnJava Status |
|---------|----------|------------------|
| `*{$name} = $wrapper` | Install memoized function | Implemented |
| `*{$name}{CODE}` | Extract CODE slot | Implemented |
| `Scalar::Util::set_prototype` | Preserve prototype | Implemented |
| `caller`, `wantarray` | Context detection | Implemented |
| `no strict` + symbolic refs | Dynamic installation | Implemented |
| `tied %$hash` | Check tied cache | Implemented |

---

## Bug Details

### Bug 1: StackOverflowError in correctness.t

**Impact:** t/correctness.t (1/17 tests not reached)

**Root cause:** The test at lines 90-103 probes for Perl's "Deep recursion"
warning threshold by recursing up to 100,000 times (`deep_probe()`). This
overflows the JVM default stack size.

```perl
sub deep_probe { deep_probe() if ++$limit < 100_000 and not $fail }
sub deep_test { no warnings "recursion"; deep_test() if $limit-- > 0 }
memoize "deep_test";
```

**Workaround:** Run with `JPERL_OPTS="-Xss256m"`. The `perl_test_runner.pl`
already applies this for known deeply-recursive tests.

**Status:** Won't fix in Memoize -- needs JVM stack config

### Bug 2: DB_File Infinite Recursion

**Impact:** t/tie.t (7/7), t/tie_db.t (7/7)

**Root cause:** `DB_File.pm` lines 238-240 have infinite recursion:
```
DB_File at /Users/fglock/.perlonjava/lib/DB_File.pm line 238
DB_File at /Users/fglock/.perlonjava/lib/DB_File.pm line 240
```
This is a bug in the DB_File shim, not in Memoize. These tests tie Memoize's
cache to a DB_File database.

**Status:** Deferred (fix in DB_File shim)

### Bug 3: threads Not Available

**Impact:** t/threadsafe.t (7/8)

**Root cause:** PerlOnJava does not implement Perl-style `threads`. This is
a known systemic limitation.

**Status:** Won't fix (architectural limitation)

### Bug 4: tie_storable.t Partial Failure

**Impact:** t/tie_storable.t (1/6 not reached)

**Root cause:** Likely related to `Storable::lock_store`/`lock_retrieve`
being stubs in PerlOnJava.

**Status:** Low priority

---

## Bundling Plan

Memoize source is available in the Perl 5 checkout at `perl5/cpan/Memoize/`.

### Step 1: Add to import-perl5 config

Add to `dev/import-perl5/config.yaml`:
```yaml
  # Memoize - Function return value caching (pure Perl, core since 5.8)
  - source: perl5/cpan/Memoize/lib/Memoize.pm
    target: src/main/perl/lib/Memoize.pm

  - source: perl5/cpan/Memoize/lib/Memoize
    target: src/main/perl/lib/Memoize
    type: directory
```

### Step 2: Sync
```bash
perl dev/import-perl5/sync.pl
```

### Step 3: Update docs
Add to `docs/reference/bundled-modules.md`.

### Step 4: Verify
```bash
make dev
./jperl -e 'use Memoize; memoize("fib"); sub fib { return $_[0] if $_[0] < 2; fib($_[0]-1)+fib($_[0]-2) } print fib(30), "\n"'
```

---

## Fix Order (Priority)

1. **Bundle Memoize** -- add to import config, sync, verify
2. Investigate correctness.t with `-Xss256m` -- likely just works
3. Fix DB_File shim infinite recursion -- separate issue
4. threads support -- systemic, won't fix

## Expected Results After Bundling

| Test | Current | Expected |
|------|---------|----------|
| t/basic.t | PASS | PASS |
| t/cache.t | PASS | PASS |
| t/correctness.t | FAIL | PASS (with -Xss256m) |
| t/expmod.t | PASS | PASS |
| t/expmod_t.t | PASS | PASS |
| t/flush.t | PASS | PASS |
| t/normalize.t | PASS | PASS |
| t/threadsafe.t | FAIL | FAIL (no threads) |
| t/tie.t | FAIL | FAIL (DB_File bug) |
| t/tie_db.t | FAIL | FAIL (DB_File bug) |
| t/tie_storable.t | FAIL | Investigate |
| t/unmemoize.t | PASS | PASS |

**Target: 8/12 passing** (4 skipped for missing DB backends)

## Completed Fixes

_(none yet)_

## Progress Tracking

- [ ] Bundle Memoize via import-perl5 config
- [ ] Verify core functionality works from bundled JAR
- [ ] Re-run test suite and update results

## Related Documents

- `dev/cpan-reports/Memoize.md` -- detailed test investigation

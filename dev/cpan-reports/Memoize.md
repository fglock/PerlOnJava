# Memoize Compatibility Report for PerlOnJava

> Investigated 2026-04-13 against Memoize 1.17 (CPAN, ARISTOTLE) with PerlOnJava

## Summary

| Metric | Value |
|--------|-------|
| **CPAN distribution** | Memoize-1.17 (ARISTOTLE) |
| **Bundled in PerlOnJava** | No (installed via jcpan for testing) |
| **Test files** | 16 (4 skipped) |
| **Subtests run** | 206 |
| **Subtests explicitly failed** | 0 |
| **Test programs crashed** | 5 / 12 that ran |
| **Test files passing** | 7 / 16 |
| **Overall status** | FAIL (but close to passing for core functionality) |

## Architecture

Memoize is **100% pure Perl** -- no XS required. It caches function return values
by wrapping functions via typeglob manipulation.

Source is available in the Perl 5 checkout at `perl5/cpan/Memoize/` but is
**not bundled** in PerlOnJava's JAR.

## Dependency Analysis

### Direct Dependencies (all satisfied)

| Dependency | Available | Location |
|-----------|-----------|----------|
| `Carp` | Yes | `src/main/perl/lib/Carp.pm` |
| `Scalar::Util` (>=1.11) | Yes (v1.63) | Java backend: `ScalarUtil.java` |
| `Exporter` | Yes | `src/main/perl/lib/Exporter.pm` |
| `warnings` | Yes | Java backend: `Warnings.java` |

### Sub-module Dependencies

| Sub-module | Extra Dependency | Available |
|-----------|------------------|-----------|
| `Memoize::Expire` | `Time::HiRes` | Yes (Java impl) |
| `Memoize::Storable` | `Storable` | Yes (with stub locking) |
| `Memoize::AnyDBM_File` | `AnyDBM_File` | No |
| `Memoize::NDBM_File` | `NDBM_File` | No |
| `Memoize::SDBM_File` | `SDBM_File` | No |

### Perl Language Features Used

| Feature | PerlOnJava Status |
|---------|------------------|
| `*{$name} = $wrapper` (typeglob CODE assign) | Implemented |
| `*{$name}{CODE}` (extract CODE slot) | Implemented |
| `Scalar::Util::set_prototype` | Implemented |
| `caller`, `wantarray` | Implemented |
| `no strict` + symbolic refs | Implemented |
| `prototype()` builtin | Implemented |
| `tied %$hash` | Implemented |
| `warnings::enabled('all')` | Implemented |

## Test Results by File

### Passing (7 files)

| Test File | Subtests | What it tests |
|-----------|----------|---------------|
| t/basic.t | ok | Core memoize/unmemoize, INSTALL, NORMALIZER, prototype preservation |
| t/cache.t | ok | SCALAR_CACHE, LIST_CACHE with MEMORY/FAULT/MERGE |
| t/expmod.t | ok | Memoize::Expire module |
| t/expmod_t.t | ok | Memoize::Expire with timed expiration |
| t/flush.t | ok | flush_cache() |
| t/normalize.t | ok | NORMALIZER option |
| t/unmemoize.t | ok | unmemoize() |

### Failing (5 files)

| Test File | Ran/Planned | Root Cause |
|-----------|-------------|------------|
| t/correctness.t | 16/17 | **StackOverflowError** -- deep recursion test (~100k calls) exceeds JVM stack |
| t/threadsafe.t | 1/8 | `threads` module not available (PerlOnJava limitation) |
| t/tie.t | 0/7 | **StackOverflowError** in `DB_File.pm` line 238/240 (infinite recursion) |
| t/tie_db.t | 0/7 | **StackOverflowError** in `DB_File.pm` (same as tie.t) |
| t/tie_storable.t | 5/6 | 1 subtest not reached (likely `Storable` lock_store stub issue) |

### Skipped (4 files)

| Test File | Reason |
|-----------|--------|
| t/tie_gdbm.t | Could not load `GDBM_File` |
| t/tie_ndbm.t | Could not load `Memoize::NDBM_File` |
| t/tie_odbm.t | Could not load `ODBM_File` |
| t/tie_sdbm.t | Could not load `SDBM_File` |

## Failure Analysis

### 1. StackOverflowError in correctness.t (line 93)

The test probes for the Perl "Deep recursion" warning threshold (~100 recursive calls
in standard Perl) and then verifies that Memoize's wrapper doesn't add extra stack
frames that would trigger the warning. The probe function recurses up to 100,000 times,
which overflows the JVM default stack.

**Workaround**: Run with `JPERL_OPTS="-Xss256m"` to increase JVM stack size.
This is the same workaround used for `re/pat.t` and other recursive tests.

### 2. threads not available (threadsafe.t)

PerlOnJava does not implement Perl-style `threads`. This is a known systemic limitation.
The `CLONE` method in Memoize.pm is defined but harmless.

### 3. DB_File infinite recursion (tie.t, tie_db.t)

`DB_File.pm` has an infinite recursion at lines 238-240. This is a bug in the
`DB_File` shim, not in Memoize itself. These tests tie Memoize's cache to a DB_File
database.

### 4. tie_storable.t partial failure

5 of 6 subtests pass. The final subtest likely involves `lock_store`/`lock_retrieve`
which are stub implementations in PerlOnJava's Storable.

## Core Functionality Assessment

The **core Memoize functionality works correctly**:

- `memoize()` -- caching function return values
- `unmemoize()` -- restoring original functions
- `flush_cache()` -- clearing caches
- `NORMALIZER` -- custom key normalization
- `INSTALL` -- installing under different names
- `SCALAR_CACHE` / `LIST_CACHE` -- cache configuration
- `MERGE` -- merging scalar/list caches
- `Memoize::Expire` -- time-based expiration
- Prototype preservation via `set_prototype`
- Context propagation (`wantarray`)

All failures are in **peripheral features** (threads, DB backends, deep recursion edge case).

## Recommendations

### Bundling Memoize

Memoize is an excellent candidate for bundling. All core dependencies are satisfied.

To bundle, add to `dev/import-perl5/config.yaml`:
```yaml
  # Memoize - Function return value caching (pure Perl)
  - source: perl5/cpan/Memoize/Memoize.pm
    target: src/main/perl/lib/Memoize.pm

  - source: perl5/cpan/Memoize/Memoize
    target: src/main/perl/lib/Memoize
    type: directory
```

### Improving Test Results

1. **correctness.t**: Would pass with `JPERL_OPTS="-Xss256m"` (add to perl_test_runner config)
2. **tie.t / tie_db.t**: Fix DB_File.pm infinite recursion at line 238-240
3. **tie_storable.t**: Investigate the 6th subtest failure
4. **threadsafe.t**: Will always skip/fail (no threads) -- acceptable

### Expected Results After Fixes

| Test | Current | After Fix |
|------|---------|-----------|
| t/correctness.t | FAIL (stack) | PASS (with -Xss256m) |
| t/threadsafe.t | FAIL (threads) | SKIP (acceptable) |
| t/tie.t | FAIL (DB_File) | PASS (after DB_File fix) |
| t/tie_db.t | FAIL (DB_File) | PASS (after DB_File fix) |
| t/tie_storable.t | FAIL (1/6) | Likely PASS |

With these fixes, Memoize would go from 7/16 to 11/16 passing (4 skipped, 1 threads-only).

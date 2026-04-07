# DateTime::Format::ICal — Dependency Chain Fixes

## Status: WORKING (all 134 tests pass)

```bash
./jcpan --jobs 4 -t DateTime::Format::ICal   # All 5 test files pass (134/134 subtests)
```

## Dependency Chain

```
DateTime::Format::ICal  (Module::Build)
├── DateTime::Set       (Module::Build, pure Perl)
│   ├── Params::Validate  (Module::Build, XS + PP)
│   │   ├── Devel::Peek     [test_requires] ← NEW STUB
│   │   ├── Module::Implementation [requires]
│   │   └── (XS compilation)  ← FIXED: auto-pureperl
│   └── DateTime           (already working via jcpan)
├── Params::Validate     (see above)
└── DateTime             (already working)
```

## Issues Fixed (this branch)

### 1. Parallel test execution for jcpan (`--jobs N` flag)
**Files:** `jcpan`, `jcpan.bat`, `TAP/Parser/Iterator/Process.pm`

`jcpan -t` ran CPAN module tests sequentially.  Added `--jobs N` flag that sets
`HARNESS_OPTIONS=jN`, which flows through `Test::Harness` →
`TAP::Harness(jobs=N)` → `_aggregate_parallel()`.

The parallel path uses `IO::Select` via `TAP::Parser::Multiplexer`, but
PerlOnJava's `TAP::Parser::Iterator::Process` fell back to pipe-opens
without registering handles for `IO::Select` (because `d_fork` is not set).
Fixed by trying `IO::Select` on pipe handles in the non-fork fallback path.

**Benchmark (Path::Tiny, 30 test files):**
- Sequential: 36s → Parallel `--jobs 4`: 18.5s (~2x speedup)

### 2. Module::Build `./Build` chained shebang fix
**File:** `CPAN/Distribution.pm` (`_build_command()` + install path)

`jperl` is a bash wrapper script.  Unix kernels don't support chained
shebangs (`#!/path/to/jperl` where jperl has `#!/bin/bash`), so
`./Build test` was executed by bash instead of jperl.

Fixed `_build_command()` to return `"$^X Build"` when `archname` contains
`java`.  Also fixed the install path which bypasses `_build_command()` and
uses `$CPAN::Config->{mbuild_install_build_command}` (hardcoded to
`./Build`).

### 3. Devel::Peek stub
**File:** `src/main/perl/lib/Devel/Peek.pm`

Params::Validate lists `Devel::Peek` as `test_requires`.  CPAN treats this
as a hard dependency.  Since `Devel::Peek` is a core XS module (can't be
installed separately), the dependency chain was blocked.

Created a stub that provides `SvREFCNT()` (returns 1 — JVM uses tracing GC,
not refcounting) and `Dump()` (prints a placeholder).

### 4. Module::Build auto-pureperl for XS modules
**File:** `src/main/perl/lib/Module/Build/Base.pm`

Params::Validate sets `allow_pureperl => 1` in Build.PL, but Module::Build
only skips XS when **both** `pureperl_only` AND `allow_pureperl` are true.
Since `pureperl_only` defaults to 0, XS compilation was always attempted
and died ("no compiler detected").

Fixed by overriding `process_xs_files` to auto-set `pureperl_only` when
the module declares `allow_pureperl` and no C compiler is available.

## Remaining Issues

### Params::Validate test failures (12/38 programs fail)
Not blocking — module installs and works with `jcpan -f -i`.  Failures are:
- **Glob type detection**: `*FH` detected as `scalar` instead of `glob`
- **Taint mode**: PerlOnJava doesn't fully support taint checking
- **Callbacks**: Error message format differences
- **Case sensitivity**: Some case-related validation mismatches

These are PerlOnJava runtime issues, not dependency/build issues.

### Modules that could benefit from this work
Any Module::Build module with `allow_pureperl => 1` should now build
correctly on PerlOnJava.  Examples from CPAN:
- `Params::Validate` ✅ (tested)
- `Class::XSAccessor` (has PP fallback)
- `Package::Stash` (has PP fallback via Package::Stash::PP)

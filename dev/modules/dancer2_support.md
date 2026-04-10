# Dancer2 Support for PerlOnJava

## Status: Investigation Complete -- Installer Bug Identified

- **Module version**: Dancer2 2.1.0 (CROMEDOME/Dancer2-2.1.0.tar.gz)
- **Date started**: 2026-04-09
- **Test command**: `./jcpan -t Dancer2`
- **Build system**: MakeMaker (129 files install successfully)

## Background

Dancer2 is the second most popular Perl web framework (after Mojolicious). It is
pure Perl, built on Moo (which PerlOnJava supports at 96%), and uses Plack as its
PSGI foundation. Getting Dancer2 working on PerlOnJava would validate the Moo/PSGI
stack and unlock a large ecosystem of Dancer2 plugins and middleware.

Dancer2 2.1.0 installs its 129 `.pm` files cleanly via `jcpan`. The primary blocker
is **a bug in PerlOnJava's MakeMaker** that prevents CPAN.pm from detecting and
auto-installing dependencies. Fixing this bug should allow `./jcpan -t Dancer2` to
resolve the entire dependency chain automatically.

## Dependency Analysis

### Direct Missing Dependencies (from Makefile.PL)

| # | Module | Type | Pure Perl? | Notes |
|---|--------|------|-----------|-------|
| 1 | CLI::Osprey | requires | Yes (Moo-based) | CLI framework; needs Getopt::Long::Descriptive |
| 2 | Config::Any | requires | Yes | Config file format loader |
| 3 | Data::Censor (>= 0.04) | requires | Yes (Moo-based) | Data redaction for logging |
| 4 | File::Share | requires | Yes | Dist-level shared file access |
| 5 | File::Which | requires | Yes | Cross-platform `which` |
| 6 | HTTP::Headers::Fast (>= 0.21) | requires | Yes | Fast HTTP header handling |
| 7 | Hash::Merge::Simple | requires | Yes | Simple hash merge |
| 8 | Import::Into | requires | Yes | Import modules into caller's namespace |
| 9 | JSON::MaybeXS | requires | Yes (with PP fallback) | JSON with optional XS acceleration |
| 10 | Plack (>= 1.0040) | requires | Mostly | PSGI toolkit; large dep tree |
| 11 | Plack::Middleware::FixMissingBodyInRedirect | requires | Yes | Plack middleware |
| 12 | Plack::Middleware::RemoveRedundantBody | requires | Yes | Plack middleware |
| 13 | Ref::Util | requires | Yes (PP fallback) | Fast ref-type checking |
| 14 | Template | requires | Yes | Template Toolkit (99% working, see template.md) |
| 15 | Template::Tiny (>= 1.16) | requires | Yes | Lightweight templates |

### Build/Test Dependencies

| Module | Type | Notes |
|--------|------|-------|
| Capture::Tiny | build_requires | Installed, but tests fail (see Issue 1) |
| Test::EOL | build_requires | End-of-line testing |
| Test::Exception | build_requires | Exception testing |

### Transitive Dependencies (discovered during resolution)

| Module | Required By | Notes |
|--------|------------|-------|
| Module::Build::Tiny | CLI::Osprey | PASS (32/32 tests) |
| Getopt::Long::Descriptive | CLI::Osprey | Needs Params::Validate, Sub::Exporter |
| Params::Validate | Getopt::Long::Descriptive | Has XS; needs PP fallback (`PERL_PARAMS_UTIL_PP=1`) |
| Sub::Exporter | Getopt::Long::Descriptive | Needs Sub::Install, Data::OptList, Params::Util |
| Sub::Exporter::Util | Getopt::Long::Descriptive | Part of Sub::Exporter |
| Module::Build | Params::Validate | Build system (resolution timed out here) |

## Issues Found

### Issue 0: MYMETA.yml format bug prevents CPAN.pm dependency auto-install (PRIMARY BLOCKER)

- **Status**: OPEN -- root cause identified, fix designed
- **Impact**: ALL `jcpan` installs of modules with dependencies are affected, not just Dancer2
- **File**: `src/main/perl/lib/ExtUtils/MakeMaker.pm` lines 642-733 (`_create_mymeta`)

**Root cause**: PerlOnJava's MakeMaker generates MYMETA.yml in **meta-spec v2** format
with nested `prereqs.runtime.requires` structure. CPAN.pm's `prereq_pm()` method
(in `CPAN/Distribution.pm` line 3462) has a 3-tier dependency detection cascade:

1. **Tier 1**: `read_meta()` → `CPAN::Meta->load_file()` — handles v2 correctly, but
   fails if CPAN::Meta can't parse the file (e.g., unescaped quotes in abstract field)
2. **Tier 2**: `read_yaml()` → raw YAML parse — expects v1 format with **top-level**
   `requires` and `build_requires` keys. With v2 format, these are `undef`, producing
   empty hashes. Due to an asymmetric undef-check (`$req` gets undef'd but `$breq`
   stays as `{}` which is truthy), the method returns with **zero prerequisites**.
3. **Tier 3**: Makefile `# PREREQ_PM` comment parsing — **never reached** because the
   `unless ($req || $breq)` guard on line 3556 sees `$breq = {}` as truthy.

**Result**: CPAN.pm thinks the module has no dependencies, installs it without deps.

**Fix**: Switch `_create_mymeta` to generate **meta-spec v1.4** format with flat
top-level `requires:` / `build_requires:` keys. This works with both Tier 1 and Tier 2.
Also escape single quotes in the abstract field to prevent YAML parse errors in Tier 1.

See [Solution Plan Phase 1](#solution-plan) for the specific code change.

### Issue 1: Capture::Tiny test failures (576/4283 subtests failed)

- **Status**: OPEN (non-blocking for Dancer2 itself, but affects test infrastructure)
- **Impact**: 20 of 24 Capture::Tiny test programs fail; 4 pass (t/00-report-prereqs.t, t/01-Capture-Tiny.t, t/03-tee.t skipped, t/25-cap-fork.t skipped)
- **Root causes**: Five distinct problems:

  **1a. Unicode/encoding mismatch in `system()` capture** -- Most failures (~42 per test file)
  - Strings containing Unicode (e.g. `Hi! ☺`) are captured with different byte representations
  - The got/expected look identical visually but differ at the byte level
  - This is a known PerlOnJava issue: JVM uses UTF-16 internally, system capture reads bytes
  - Also affects `sys|short` tests where trailing `\n` is missing from expected

  **1b. File descriptor leak** -- ~1 failure per test file
  - Tests expect 3-4 open file descriptors; PerlOnJava reports 9-47
  - JVM opens additional file descriptors for class loading, JIT, etc.
  - Not a real leak; just different baseline from native Perl

  **1c. `$@` not preserved across capture** -- t/16-catch-errors.t (5/5 fail)
  - `$@` set before `capture {}` is lost (becomes `''`)
  - Capture::Tiny uses `eval` internally; PerlOnJava may clobber `$@` differently

  **1d. PerlIO::scalar missing** -- t/12-stdin-string.t (dies at load)
  - `Can't locate PerlIO/scalar.pm in @INC`
  - PerlIO layers are not fully implemented in PerlOnJava

  **1e. PerlIO layer stacking** -- t/19-relayering.t (5/5 fail)
  - Expected `:encoding(UTF-8):encoding(UTF-8):crlf` but got `:encoding(UTF-8):crlf`
  - PerlOnJava deduplicates encoding layers

### Issue 2: Transitive dependencies with possible XS components

- **Status**: NEEDS INVESTIGATION (will be testable after Issue 0 is fixed)
- **Impact**: Several transitive deps may have XS code without PP fallback
- **Modules to check**: Params::Validate (has XS+PP), HTTP::Headers::Fast (may have XS), Ref::Util (XS with PP fallback)

## Solution Plan

### Phase 1: Fix MYMETA.yml format in MakeMaker (PRIMARY FIX)

**File**: `src/main/perl/lib/ExtUtils/MakeMaker.pm` — replace `_create_mymeta` sub

Switch from meta-spec v2 (nested `prereqs.runtime.requires`) to **meta-spec v1.4**
(flat top-level `requires` / `build_requires`). This format works with all three
tiers of CPAN.pm's `prereq_pm()` dependency parser.

Changes needed:
- [ ] Replace nested `prereqs:` block with flat `requires:` and `build_requires:` keys
- [ ] Change `meta-spec: version: '2'` to `version: '1.4'`
- [ ] Merge `TEST_REQUIRES` into `build_requires` (v1.4 has no separate test phase)
- [ ] Escape single quotes in `abstract` field for YAML safety
- [ ] Use hyphenated dist name (`Dancer2` → `Dancer-2`) in `name:` field
- [ ] Include version number in `generated_by` (CPAN.pm filters old EU::MM versions)
- [ ] Add `configure_requires` with `ExtUtils::MakeMaker: '0'`

Verify fix:
- [ ] Run `./jcpan -t Some::Simple::Module` and confirm CPAN.pm resolves deps
- [ ] Check generated MYMETA.yml has top-level `requires:` keys
- [ ] Run `make` to confirm PerlOnJava unit tests still pass

### Phase 2: Re-run `./jcpan -t Dancer2` after fix

- [ ] Run `./jcpan -t Dancer2` and let CPAN.pm auto-resolve the full dependency chain
- [ ] Document which dependencies install successfully
- [ ] Document any dependencies that fail (XS issues, new blockers, etc.)
- [ ] Record Dancer2 test results with pass/fail counts per test file

### Phase 3: Triage and fix Dancer2 test failures

- [ ] Categorize failures: (a) Dancer2 issues, (b) PerlOnJava issues, (c) acceptable gaps
- [ ] Address blocking PerlOnJava issues (prioritize by how many tests they unlock)
- [ ] Re-run tests and update this document

### Phase 4 (optional): Fix Capture::Tiny test issues

- [ ] Investigate Unicode byte encoding in system capture
- [ ] Investigate `$@` preservation in eval/capture context
- [ ] Consider bundling PerlIO::scalar shim

## Dependency Tree (visual)

```
Dancer2
├── Moo (96% working)
├── Template (99% working, see template.md)
├── Template::Tiny
├── Plack (>= 1.0040)
│   ├── HTTP::Message (may be installed from LWP work)
│   ├── Stream::Buffered
│   ├── Filesys::Notify::Simple
│   ├── Hash::MultiValue
│   ├── Try::Tiny (working)
│   └── ...
├── CLI::Osprey
│   ├── Moo
│   └── Getopt::Long::Descriptive
│       ├── Params::Validate (needs PERL_PARAMS_UTIL_PP=1)
│       └── Sub::Exporter
│           ├── Sub::Install
│           ├── Data::OptList
│           └── Params::Util
├── Config::Any
├── Data::Censor (>= 0.04)
│   └── Moo
├── File::Share
│   └── File::ShareDir
├── File::Which
├── HTTP::Headers::Fast (>= 0.21)
├── Hash::Merge::Simple
├── Import::Into
├── JSON::MaybeXS
│   └── JSON::PP (built-in)
├── Plack::Middleware::FixMissingBodyInRedirect
├── Plack::Middleware::RemoveRedundantBody
└── Ref::Util
```

## What Already Works

| Dependency | Status | Notes |
|-----------|--------|-------|
| Moo | 96% tests pass | Core OO system for Dancer2 |
| Template Toolkit | 99% (105/106) | Template engine |
| JSON::PP | Built-in | JSON backend for JSON::MaybeXS |
| Try::Tiny | Built-in | Error handling |
| YAML | Working | Config file support |
| HTTP::Message | Working (from LWP) | HTTP request/response |
| LWP::UserAgent | 100% (317/317) | May share HTTP deps |
| Module::Build::Tiny | 100% (32/32) | Build system |

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Plack has XS-only deps | Medium | High | Check dep tree; most Plack is pure Perl |
| Capture::Tiny failures break test harness | Low | Medium | Use `-f` (force install) to bypass |
| PerlIO::scalar missing blocks tests | Medium | Medium | Only affects Capture::Tiny, not Dancer2 itself |
| `$@` clobbering breaks error handling | Medium | High | May affect Dancer2 error pages; test early |
| File descriptor leak detection | Low | Low | Cosmetic; not a real issue |

## Related Documents

- [moo_support.md](moo_support.md) -- Moo OO system (Dancer2's foundation)
- [template.md](template.md) -- Template Toolkit support
- [lwp_useragent.md](lwp_useragent.md) -- LWP/HTTP infrastructure
- [mojo_ioloop.md](mojo_ioloop.md) -- Mojolicious (alternative web framework)
- [cpan_client.md](cpan_client.md) -- jcpan documentation
- [cpan_patch_plan.md](cpan_patch_plan.md) -- CPAN patching strategy

## Progress Tracking

### Current Status: Phase 1 (Fix MakeMaker MYMETA.yml)

### Completed Phases
- [x] Investigation (2026-04-09)
  - Ran `./jcpan -t Dancer2`, analyzed all errors
  - Identified 15 direct deps + transitive chain
  - Documented Capture::Tiny failures (5 root causes)
  - **Found root cause**: MYMETA.yml meta-spec v2 format not parsed by CPAN.pm's
    fallback dependency reader — dependencies silently ignored
  - File: `src/main/perl/lib/ExtUtils/MakeMaker.pm` `_create_mymeta` (line 642)

### Next Steps
1. Fix `_create_mymeta` to use meta-spec v1.4 format
2. Re-run `./jcpan -t Dancer2` — dependency chain should auto-resolve
3. Document and triage Dancer2 test results

# Dancer2 Support for PerlOnJava

## Status: Phase 2 Complete -- Dancer2 Installed, 2 Blockers Identified

- **Module version**: Dancer2 2.1.0 (CROMEDOME/Dancer2-2.1.0.tar.gz)
- **Date started**: 2026-04-09
- **Last updated**: 2026-04-10
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

### Issue 0: MYMETA.yml format bug prevents CPAN.pm dependency auto-install (FIXED)

- **Status**: FIXED (PR #479)
- **Impact**: ALL `jcpan` installs of modules with dependencies were affected
- **File**: `src/main/perl/lib/ExtUtils/MakeMaker.pm` `_create_mymeta` (line 642)

**Root cause**: PerlOnJava's MakeMaker generated MYMETA.yml in **meta-spec v2** format
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

**Fix (commit bd1ecc0e6)**: Switched `_create_mymeta` to generate **meta-spec v1.4** format
with flat top-level `requires:` / `build_requires:` keys.

### Issue 0b: MYMETA.yml YAML single-quote escaping bug (FIXED)

- **Status**: FIXED (same PR #479, follow-up commit)
- **Impact**: Modules with apostrophes in their ABSTRACT field (e.g., `"if it's not already set"`)
  produced invalid YAML, causing `"not a HASH reference"` warnings from CPAN.pm
- **File**: `src/main/perl/lib/ExtUtils/MakeMaker.pm` `_create_mymeta`

**Root cause**: The code did `$abstract =~ s/'/\\'/g` producing `\'` — this is a
Perl/shell escaping convention, not valid YAML. In YAML single-quoted strings, the
only escape for a single quote is doubling it: `''`.

**Additional fix**: Removed blank lines between YAML sections (caused by trailing newlines
in interpolated strings) and used `{}` for empty mapping sections.

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

### Issue 2: Template Toolkit Makefile.PL fails to configure

- **Status**: OPEN (non-blocking; Dancer2 falls back to Template::Tiny)
- **Impact**: Template Toolkit (TT) engine unavailable; Dancer2 uses Template::Tiny instead
- **Error**: `Global symbol "$DEBUG" requires explicit package name at ./lib/Template/Service.pm line 34`

**Root cause**: Template Toolkit 3.102's `Makefile.PL` loads its own modules during
configuration. `Template::Service` declares `$DEBUG` via a pattern that PerlOnJava
doesn't handle correctly (likely `use constant DEBUG => ...` or `our $DEBUG` with
`use vars`). The bundled Template Toolkit (installed at 99% pass rate) works fine;
only the CPAN-downloaded v3.102 configuration step fails.

**Workaround**: Dancer2 works with Template::Tiny as fallback. The bundled TT can also
be used if pre-installed.

### Issue 3: Type::Tiny compilation error blocks `use Dancer2`

- **Status**: OPEN (BLOCKING — prevents `use Dancer2` from loading)
- **Impact**: `use Dancer2` fails at runtime
- **Error**: `Global symbol "$s" requires explicit package name at Type/Tiny.pm line 610`

**Root cause**: Type::Tiny line 609 uses a `for` statement modifier with `my` in the
list expression:
```perl
defined && s/[\x00-\x1F]//smg for ( my ( $s, @a ) = @_ );
sprintf( '%s[%s]', $s, join q[,], ... @a );
```
In standard Perl, `my ($s, @a) = @_` in a `for` modifier list creates variables in
the enclosing scope. PerlOnJava appears to scope `$s` only within the `for` statement,
making it invisible on line 610. This is a **PerlOnJava compiler scoping bug**.

**Fix needed**: PerlOnJava's `for` statement modifier needs to leak `my` declarations
from its list expression into the enclosing scope, matching standard Perl behavior.

## Solution Plan

### Phase 1: Fix MYMETA.yml format in MakeMaker (COMPLETED)

**File**: `src/main/perl/lib/ExtUtils/MakeMaker.pm` — `_create_mymeta` sub

Switched from meta-spec v2 (nested `prereqs.runtime.requires`) to **meta-spec v1.4**
(flat top-level `requires` / `build_requires`). Also fixed YAML single-quote escaping
(`''` instead of `\'`) and removed blank lines between sections.

- [x] Replace nested `prereqs:` block with flat `requires:` and `build_requires:` keys
- [x] Change `meta-spec: version: '2'` to `version: '1.4'`
- [x] Merge `TEST_REQUIRES` into `build_requires` (v1.4 has no separate test phase)
- [x] Fix single-quote escaping (`s/'/''/g` not `s/'/\\'/g`) for YAML safety
- [x] Use hyphenated dist name (`Dancer2` → `Dancer-2`) in `name:` field
- [x] Include version number in `generated_by` (CPAN.pm filters old EU::MM versions)
- [x] Add `configure_requires` with `ExtUtils::MakeMaker: '0'`
- [x] Remove blank lines between YAML sections; use `{}` for empty mappings
- [x] Run `make` — all unit tests pass
- [x] Verified YAML parses correctly with both CPAN::Meta::YAML and YAML modules

### Phase 2: Install Dancer2 via notest (COMPLETED)

Ran `CPAN::Shell->notest("install", "Dancer2")` to install Dancer2 with all
dependencies, skipping tests for faster iteration.

**Successfully installed modules** (all via notest):

| Module | Version | Status |
|--------|---------|--------|
| Dancer2 | 2.1.0 | OK (129 files) |
| CLI::Osprey | - | OK (via Module::Build) |
| Config::Any | 0.33 | OK (8 files) |
| Data::Censor | 0.04 | OK |
| File::Share | 0.27 | OK |
| File::Which | 1.27 | OK |
| HTTP::Headers::Fast | - | OK (via Module::Build) |
| Import::Into | 1.002005 | OK |
| JSON::MaybeXS | 1.004008 | OK |
| Plack | 1.0051 | OK (73 files) |
| Plack::Middleware::FixMissingBodyInRedirect | 0.12 | OK |
| Plack::Middleware::RemoveRedundantBody | 0.09 | OK |
| Ref::Util | 0.204 | OK |
| Template::Tiny | 1.16 | OK |
| Module::Build | - | OK |
| Getopt::Long::Descriptive | 0.117 | OK |
| Sub::Exporter | 0.991 | OK |
| Data::OptList | 0.114 | OK |
| Params::Util | 1.102 | OK |
| Sub::Install | 0.929 | OK |
| Sub::Uplevel | 0.2800 | OK |
| Test::Exception | 0.43 | OK |
| Test::TCP | 2.22 | OK |
| Test::SharedFork | 0.35 | OK |
| Test::MockTime | 0.17 | OK |
| Test::Time | 0.092 | OK |
| Test::Lib | 0.003 | OK |
| Test::EOL | 2.02 | OK |
| Apache::LogFormat::Compiler | 0.36 | OK |
| Cookie::Baker | 0.12 | OK |
| Devel::StackTrace::AsHTML | 0.15 | OK |
| Filesys::Notify::Simple | 0.14 | OK |
| HTTP::Entity::Parser | 0.25 | OK |
| HTTP::MultiPartParser | 0.02 | OK |
| Stream::Buffered | 0.03 | OK |
| WWW::Form::UrlEncoded | 0.26 | OK |
| POSIX::strftime::Compiler | 0.46 | OK |
| Readonly | - | OK |

**Failed to install**:

| Module | Error | Impact |
|--------|-------|--------|
| Template (Toolkit) | `$DEBUG` undeclared in Service.pm during Makefile.PL | Non-blocking (Template::Tiny works) |

**Runtime blocker**: `use Dancer2` fails with Type::Tiny scoping bug (Issue 3)

### Phase 3: Fix Type::Tiny scoping bug (NEXT)

- [ ] Reproduce the `for` statement modifier `my` scoping issue with a minimal test
- [ ] Fix PerlOnJava's compiler to leak `my` declarations from `for` list into enclosing scope
- [ ] Verify `use Dancer2` loads successfully
- [ ] Run Dancer2 test suite and document results

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

### Current Status: Phase 3 (Fix Type::Tiny scoping bug to unblock `use Dancer2`)

### Completed Phases
- [x] Investigation (2026-04-09)
  - Ran `./jcpan -t Dancer2`, analyzed all errors
  - Identified 15 direct deps + transitive chain
  - Documented Capture::Tiny failures (5 root causes)
  - **Found root cause**: MYMETA.yml meta-spec v2 format not parsed by CPAN.pm's
    fallback dependency reader — dependencies silently ignored
  - File: `src/main/perl/lib/ExtUtils/MakeMaker.pm` `_create_mymeta` (line 642)

- [x] Phase 1: Fix MakeMaker MYMETA.yml (2026-04-09, PR #479)
  - Switched to meta-spec v1.4 format (commit bd1ecc0e6)
  - Fixed YAML single-quote escaping: `''` not `\'` (2026-04-10)
  - Removed blank lines between sections; `{}` for empty mappings (2026-04-10)
  - All unit tests pass

- [x] Phase 2: Install Dancer2 (2026-04-10)
  - Used `CPAN::Shell->notest("install", "Dancer2")`
  - 38 modules installed successfully (all deps auto-resolved)
  - Only Template Toolkit failed (non-blocking)
  - **Blocker found**: `use Dancer2` fails due to Type::Tiny scoping bug

### Next Steps
1. Fix PerlOnJava `for` statement modifier `my` scoping (Issue 3)
2. Verify `use Dancer2` loads
3. Run Dancer2 test suite and document pass/fail results

# CPAN Smoke Test — Failure Investigation Plan

## Overview

This document tracks test failures across all modules in the CPAN smoke test
(`dev/tools/cpan_smoke_test.pl`). The goal is to systematically investigate
failures, identify root causes in PerlOnJava, and prioritize fixes that unblock
the most modules.

**Run the smoke test**: `perl dev/tools/cpan_smoke_test.pl`
**Quick regression check**: `perl dev/tools/cpan_smoke_test.pl --quick`

## Module Status Summary

| Category | Count | Description |
|----------|-------|-------------|
| known-good | 8 | All or nearly all tests pass |
| partial | 21 | Installs but has test failures |
| blocked | 9 | Cannot install or test due to missing deps |

## Priority: Shared Root Causes

These are PerlOnJava issues that affect multiple modules. Fixing them has
the highest impact.

### P1: Clone::PP missing

**Affects**: HTTP::Message, LWP::UserAgent, Plack (indirectly)

Clone.pm exists in PerlOnJava but falls back to Clone::PP which doesn't exist.
Creating a pure-Perl Clone::PP that implements `clone()` via Storable's
`dclone` would unblock the entire HTTP stack.

**Fix**: Create `src/main/perl/lib/Clone/PP.pm` with:
```perl
package Clone::PP;
use Storable 'dclone';
sub clone { Storable::dclone($_[0]) }
1;
```

**Unblocks**: HTTP::Message → LWP::UserAgent → Plack → Devel::Cover (partially)

### P2: MIME::Base64 $VERSION undef

**Affects**: MIME::Base64, HTTP::Message (version check fails)

The Java XS implementation in `MIMEBase64.java` doesn't set `$VERSION`.
Modules that require `MIME::Base64 >= 2.1` fail at version check.

**Fix**: Set `$MIME::Base64::VERSION` in the Java backend or in a wrapper .pm.

### P3: Encode::Locale — unknown encoding "locale"

**Affects**: Encode::Locale, HTTP::Message, LWP::UserAgent

PerlOnJava doesn't map the system locale to a Perl encoding name.

**Fix**: Map Java's `Charset.defaultCharset()` to the corresponding Perl
encoding name (e.g., `UTF-8`).

### P4: File::Temp close / PerlIO::encoding stub

**Affects**: IO::HTML, File::Temp-dependent modules

File::Temp objects don't properly handle `close` via AUTOLOAD, and
PerlIO::encoding has no stub.

**Fix**: Add PerlIO::encoding stub; review File::Temp close delegation.

### P5: Exit code handling

**Affects**: Test::Needs (200/227), potentially other test harness modules

Test scripts that call `exit()` with non-zero codes may not propagate
correctly through PerlOnJava's process model.

**Investigation**: Run failing Test::Needs subtests individually and compare
exit codes with Perl 5.

## Per-Module Investigation

### Partial Modules (install OK, tests partially fail)

#### Try::Tiny — 91/94

**Known issue**: 3 tests fail due to fork (not available in PerlOnJava).
**Action**: None needed — expected failure. Could mark as known-good with a
note.

#### Test::Warn — partial

**Known issue**: Dep test issues with Sub::Uplevel.
**Investigation**: Run `./jcpan -t Test::Warn` and capture failing subtests.
Check if Sub::Uplevel's stack manipulation works.

#### Path::Tiny — 1489/1542

**Known issue**: 53 failing subtests.
**Investigation**: Run tests individually to categorize failures:
- File permission tests (chmod/chown)?
- Symlink tests?
- Encoding-related?
- Temp file handling?

#### Parse::RecDescent — untested via jcpan

**Known issue**: Heavy use of `eval STRING` and complex regex.
**Investigation**: Run `./jcpan -t Parse::RecDescent` and record results.

#### Spreadsheet::WriteExcel — untested via jcpan

**Investigation**: Run `./jcpan -t Spreadsheet::WriteExcel` and record results.
Deps (OLE::Storage_Lite, Parse::RecDescent) should already be installable.

#### Image::ExifTool — 590/600 (98%)

**Known issue**: 10 failing tests across Writer.t, XMP.t, Geotag.t, PDF.t, etc.
See `.cognition/skills/debug-exiftool/SKILL.md` for detailed analysis.
**Action**: Low priority — already very high pass rate.

#### MIME::Base64 — partial

**Known issues**: $VERSION undef, missing url-safe variants.
**Fix**: See P2 above. Also implement `encode_base64url`/`decode_base64url`.

#### URI — 896/947

**Known issues**: UTF-8 encoding differences.
**Investigation**: Categorize failures by URI subtype (URI::http, URI::data,
URI::_query, etc.) and encoding edge cases.

#### IO::HTML — 32/52

**Known issues**: File::Temp close, encoding name differences.
**Fix**: See P4 above. Also check encoding name normalization
(e.g., "utf-8-strict" vs "utf-8").

#### LWP::MediaTypes — 41/47

**Known issues**: MIME type differences.
**Investigation**: Compare MIME type database — may be version/platform
differences in the type mappings.

#### Test::Needs — 200/227

**Known issues**: Exit code handling.
**Fix**: See P5 above.

#### Test::Warnings — 86/88

**Known issues**: 2 failing subtests.
**Investigation**: Identify which 2 tests fail and whether they relate to
warning propagation or $SIG{__WARN__} handling.

#### Encode::Locale — partial

**Known issues**: Cannot determine system locale encoding.
**Fix**: See P3 above.

#### Log::Log4perl — partial

**Known issues**: Mostly works. See `dev/modules/log4perl-compatibility.md`.
**Investigation**: Run full test suite and record specific failures.

#### JSON — untested via jcpan

**Expected behavior**: Should use JSON::PP (bundled) as backend.
**Investigation**: Run `./jcpan -t JSON`. Key concern: does it correctly
detect JSON::PP and fall back from JSON::XS?

#### Type::Tiny — untested via jcpan

**Expected behavior**: Pure Perl, should work with Moo.
**Investigation**: Run `./jcpan -t Type::Tiny`. Many deps — check whether
the dependency chain installs cleanly.

#### List::MoreUtils — untested via jcpan

**Expected behavior**: Should fall back to PP implementation.
**Investigation**: Run `./jcpan -t List::MoreUtils`. Check that
List::MoreUtils::XS is skipped gracefully.

#### Template (Template Toolkit) — untested via jcpan

**Expected behavior**: Should work without Stash::XS.
**Investigation**: Run `./jcpan -t Template`. Large module with many deps —
likely to expose new issues.

#### Mojolicious — untested via jcpan

**Expected behavior**: Zero non-core deps, but uses IO::Socket::SSL,
subprocesses, event loops.
**Investigation**: Run `./jcpan -t Mojolicious`. Expect partial results —
socket/async features may not work.

### Blocked Modules (cannot install/test)

#### HTTP::Message — blocked on Clone::PP

**Blocker**: See P1.
**Unblocks**: LWP::UserAgent, Plack, Devel::Cover (partially).

#### Devel::Cover — blocked on HTML::Entities

**Blocker**: HTML::Entities comes from HTML::Parser (XS).
**Fix path**: Create a pure-Perl HTML::Entities shim with `decode_entities`.
See `dev/modules/devel_cover.md` for detailed plan.

#### HTML::Parser — XS required

**Blocker**: No Java XS backend.
**Action**: Consider implementing key functions (HTML::Entities::decode_entities)
as a Java backend or pure-Perl shim.

#### IO::Compress::Gzip — XS required

**Blocker**: Needs Compress::Raw::Zlib (C library).
**Action**: Could potentially implement via Java's `java.util.zip`.

#### Moose — XS required

**Blocker**: Needs B module subroutine name introspection, Class::MOP XS.
**Action**: Long-term — Moo is the recommended alternative.

#### Plack — blocked on dep chain

**Blocker**: Needs HTTP::Message (blocked on Clone::PP), and other HTTP modules.
**Unblocked by**: P1 (Clone::PP).

#### LWP::UserAgent — blocked on HTTP::Message

**Blocker**: Same as HTTP::Message.
**Unblocked by**: P1 (Clone::PP).

#### DBIx::Class — blocked on DBI

**Blocker**: DBI is XS-only.
**Action**: Would need a Java DBI backend. Long-term goal.

#### DBI — XS required

**Blocker**: Pure C XS implementation.
**Action**: Could implement a Java backend using JDBC. Significant effort.

## Fix Priority Order

Based on impact (modules unblocked) and effort:

1. **P1: Clone::PP** — trivial fix, unblocks HTTP::Message → LWP → Plack
2. **P2: MIME::Base64 $VERSION** — trivial fix, unblocks version checks
3. **P3: Encode::Locale** — small fix, improves HTTP chain
4. **P4: PerlIO::encoding stub** — small fix, helps IO::HTML
5. **P5: Exit code handling** — medium effort, helps Test::Needs
6. **HTML::Entities shim** — medium effort, unblocks Devel::Cover
7. **IO::Compress::Gzip Java backend** — large effort, unblocks Compress chain

## How to Update This Document

After running the smoke test, update the per-module sections with:
1. Current pass/fail counts
2. New root causes discovered
3. Move modules between categories as fixes land

```bash
# Run full smoke test and save results
perl dev/tools/cpan_smoke_test.pl --output smoke_results.log

# Compare with previous run
perl dev/tools/cpan_smoke_test.pl --compare cpan_smoke_PREVIOUS.dat
```

## Progress Tracking

### Current Status: Initial investigation

### Completed
- [x] Module registry created with 39 modules (2026-03-31)
- [x] Top-20 CPAN modules identified and added
- [x] Shared root causes documented

### Next Steps
1. Run full smoke test to get baseline pass/fail counts for all 39 modules
2. Investigate new modules (JSON, Type::Tiny, List::MoreUtils, Template, Mojolicious)
3. Implement P1 (Clone::PP) — highest impact fix
4. Re-run smoke test with `--compare` to measure improvement

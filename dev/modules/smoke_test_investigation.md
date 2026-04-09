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
| known-good | 11 | All or nearly all tests pass |
| partial | 19 | Installs but has test failures |
| blocked | 6 | Cannot install or test due to missing deps |

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

### P3: Encode::Alias — find_encoding bypassed Perl alias system (DONE)

**Affects**: Encode::Locale, HTTP::Message, LWP::UserAgent

Java `Encode.find_encoding()` completely bypassed the Perl `Encode::Alias` system,
only checking hardcoded `CHARSET_ALIASES` and `Charset.forName()`.

**Fix**: Modified bundled `src/main/perl/lib/Encode.pm` to wrap `find_encoding`
with Perl-level alias resolution. Removed `"Encode"` from `preloadedModules` in
`GlobalContext.java` so XSLoader executes the .pm file. The .pm saves a reference
to the Java `find_encoding` before overriding it with a wrapper that tries Java
first, then falls back to `Encode::Alias::find_alias()`. Also added `encodings()`
method to `Encode.java`.

### P4: File::Temp close / PerlIO::encoding stub

**Affects**: IO::HTML, File::Temp-dependent modules

File::Temp objects don't properly handle `close` via AUTOLOAD, and
PerlIO::encoding has no stub.

**Fix**: Add PerlIO::encoding stub; review File::Temp close delegation.

### P5: Exit code handling (DONE)

**Affects**: Test::Needs (now 227/227 — PASS), potentially other test harness modules

`WarnDie.exit()` called `runEndBlocks()` which reset `$?` to 0, ignoring END
block modifications. Also, `require` error messages didn't include
"Compilation failed" prefix.

**Fix**: Set `$?` before END blocks, use `runEndBlocks(false)` to skip reset,
read `$?` back after END blocks for final exit code. Fixed in `WarnDie.java`
and `ModuleOperators.java`. Test::Needs now passes all 227 tests.

### P6: Regex engine — `\|` quantifier error in alternations (DONE)

**Affects**: Template Toolkit (was 0/247, now 170/2072 with full test suite)

Two bugs fixed in `RegexPreprocessor.java`:
1. **Escaped pipe `\|` with quantifier**: Replaced brittle `sb` character inspection
   with `lastWasQuantifiable` flag check. Removed duplicate `lastChar == '|'` blocks.
2. **Lookaheads/lookbehinds/atomic groups**: `(?=...)`, `(?!...)`, `(?<=...)`, `(?<!...)`,
   `(?>...)`, `(?:...)` were routed through `handleRegularParentheses` which only
   appended `(` and parsed from `offset+1`, causing `?` to be treated as quantifier.
   Fixed by appending full group opener and calling `handleRegex` with correct offset.

### P7: HTML::Parser/HTML::Entities — Java XS backend (DONE — Phase 1)

**Affects**: HTML::Parser, HTTP::Message, Devel::Cover, any module using HTML::Entities

HTML::Parser is an XS module. HTML::Entities requires `HTML::Parser` to provide
`decode_entities` and `_decode_entities` as XS-accelerated functions.

**Fix**: Created `HTMLParser.java` — a single Java XS file matching the original
`Parser.xs` layout with both `PACKAGE = HTML::Parser` and `PACKAGE = HTML::Entities`.

Phase 1 provides:
- **HTML::Entities** (fully functional): `decode_entities`, `_decode_entities` (with
  numeric decimal/hex, named entities, surrogate pairs, prefix expansion),
  `UNICODE_SUPPORT`, `_probably_utf8_chunk`
- **HTML::Parser** (basic): `_alloc_pstate`, `parse`/`eof` with basic event-driven
  parsing, 13 boolean accessors, `handler` registration, tag list methods

Cross-package registration uses direct `GlobalVariable.getGlobalCodeRef()` calls
since `registerMethod` prefixes with the module name.

**Results**: HTTP::Message → PASS, Devel::Cover → PASS, HTML::Parser 190/415

**Phase 2** (future): Full hparser.c port (~1900 lines), argspec compilation,
array-ref accumulator handlers (needed for TokeParser/PullParser).

### P8: IO::Compress::Gzip — Java backend (NOT STARTED)

**Affects**: IO::Compress chain, modules needing gzip support

Needs `Compress::Raw::Zlib` (C library). Could implement via `java.util.zip`.

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
See `.agents/skills/debug-exiftool/SKILL.md` for detailed analysis.
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

#### Test::Needs — 227/227 (PASS)

**Fixed**: Exit code handling (P5). All tests pass now.

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

#### JSON — CONFIG_FAIL

**Root cause**: Bundled `src/main/perl/lib/JSON.pm` was missing `$VERSION`.
CPAN's JSON-4.11 `Makefile.PL` does `use JSON` and checks `$JSON::VERSION`,
which returned undef causing a fatal "version check failed" error.
**Fix**: Added `our $VERSION = '4.11'` to bundled JSON.pm. (DONE)

#### Type::Tiny — untested via jcpan

**Expected behavior**: Pure Perl, should work with Moo.
**Investigation**: Run `./jcpan -t Type::Tiny`. Many deps — check whether
the dependency chain installs cleanly.

#### List::MoreUtils — untested via jcpan

**Expected behavior**: Should fall back to PP implementation.
**Investigation**: Run `./jcpan -t List::MoreUtils`. Check that
List::MoreUtils::XS is skipped gracefully.

#### Template (Template Toolkit) — 170/2072

**Previously 0/247** (regex bug blocked all tests). After P6 regex fix, tests
can now run. The full test suite has 2072 subtests, of which 170 pass.
Remaining failures need investigation — likely a mix of regex edge cases,
missing features, and test infrastructure issues.

#### Mojolicious — untested via jcpan

**Expected behavior**: Zero non-core deps, but uses IO::Socket::SSL,
subprocesses, event loops.
**Investigation**: Run `./jcpan -t Mojolicious`. Expect partial results —
socket/async features may not work.

### Blocked Modules (cannot install/test)

#### HTTP::Message — PASS (unblocked)

**Previously blocked** on Clone::PP and HTML::Entities.
**Fixed by**: P1 (Clone::PP) and P7 (HTML::Parser Java XS backend).
Now passes all tests (243s runtime).

#### Devel::Cover — PASS (unblocked)

**Previously blocked** on HTML::Entities dep chain.
**Fixed by**: P7 (HTML::Parser Java XS backend). Now passes 1/1 tests.

#### HTML::Parser — 190/415 (partially working)

**Previously blocked**: No Java XS backend.
**Fixed by**: P7 (HTMLParser.java Phase 1). Basic parsing works.
190/415 tests pass. Remaining failures likely need Phase 2 (full hparser.c
port with argspec compilation and accumulator handlers for TokeParser).

#### IO::Compress::Gzip — XS required

**Blocker**: Needs Compress::Raw::Zlib (C library).
**Action**: Could potentially implement via Java's `java.util.zip`.

#### Moose — XS required (skipped from smoke tests)

**Blocker**: Needs B module subroutine name introspection, Class::MOP XS.
**Action**: Long-term — Moo is the recommended alternative.

#### Plack — blocked on dep chain (skipped from smoke tests)

**Blocker**: Needs HTTP::Message (now fixed), and other HTTP modules.
HTTP::Message is now unblocked. May be worth re-enabling in smoke tests.

#### LWP::UserAgent — may be unblocked

HTTP::Message now passes. LWP::UserAgent may work now.
**Action**: Re-test with `./jcpan -t LWP::UserAgent`.

#### DBIx::Class — blocked on DBI

**Blocker**: DBI is XS-only.
**Action**: Would need a Java DBI backend. Long-term goal.

#### DBI — XS required

**Blocker**: Pure C XS implementation.
**Action**: Could implement a Java backend using JDBC. Significant effort.

## Fix Priority Order

Based on impact (modules unblocked) and effort:

1. **P1: Clone::PP** — trivial fix, unblocks HTTP::Message → LWP → Plack **(DONE)**
2. **P2: MIME::Base64 $VERSION** — trivial fix, unblocks version checks **(DONE)**
3. **P3: Encode::Alias** — Perl-level alias resolution for find_encoding **(DONE)**
4. **P4: PerlIO::encoding stub** — small fix, helps IO::HTML **(DONE)**
5. **P5: Exit code handling** — END blocks now see correct $? **(DONE)**
6. **P6: Regex `\|` + lookaheads** — two bugs in RegexPreprocessor **(DONE)**
7. **P7: HTML::Parser Java XS** — Phase 1: entities + basic parser **(DONE)**
8. **P8: IO::Compress::Gzip Java backend** — large effort, unblocks Compress chain

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

### Current Status: P1–P7 all completed

### Completed
- [x] Module registry created with 39 modules (2026-03-31)
- [x] Top-20 CPAN modules identified and added
- [x] Shared root causes documented
- [x] P1: Clone::PP created (`src/main/perl/lib/Clone/PP.pm`) — uses Storable::dclone
- [x] P2: MIME::Base64 $VERSION set in both `.pm` (`3.16`) and `.java` backend
- [x] P4: PerlIO::encoding stub created (`src/main/perl/lib/PerlIO/encoding.pm`)
- [x] JSON $VERSION added to bundled `src/main/perl/lib/JSON.pm`
- [x] Template::Stash::XS shim created (falls back to pure-Perl Template::Stash)
- [x] P6: Regex preprocessor — lookaheads and escaped pipes fix (2026-03-31)
- [x] P3: Encode::Alias — find_encoding wrapper + XSLoader deferred load (2026-03-31)
- [x] P5: exit() lets END blocks modify $? + require shows full error (2026-03-31)
- [x] P7: HTML::Parser/HTML::Entities Java XS backend Phase 1 (2026-03-31)
  - Created `HTMLParser.java` with entity decoding + basic HTML parser
  - Cross-package registration for HTML::Entities namespace
  - Unblocked HTTP::Message (PASS), Devel::Cover (PASS)
  - HTML::Parser 190/415 tests passing

### Smoke Test Results (2026-03-31, post P1–P7)

| Module | Status | Tests |
|--------|--------|-------|
| Test::Deep | FAIL | 1266/1268 |
| Try::Tiny | FAIL | 91/94 |
| Test::Fatal | PASS | 19/19 |
| MIME::Base32 | PASS | 31/31 |
| HTML::Tagset | PASS | 33/33 |
| Path::Tiny | FAIL | 1488/1542 |
| Spreadsheet::WriteExcel | FAIL | 1124/1189 |
| Image::ExifTool | PASS | 600/600 |
| Spreadsheet::ParseExcel | PASS | 1612/1612 |
| IO::Stringy | PASS | 127/127 |
| Moo | FAIL | 809/840 |
| URI | FAIL | 844/947 |
| Test::Needs | PASS | 227/227 |
| Test::Warnings | FAIL | 86/88 |
| Log::Log4perl | FAIL | 715/719 |
| JSON | FAIL | 23683/24886 |
| Devel::Cover | PASS | 1/1 |
| HTTP::Message | PASS | (all) |
| HTML::Parser | FAIL | 190/415 |

### Next Steps
1. Investigate remaining HTML::Parser failures (Phase 2: argspec, accumulator handlers)
2. Fix P8 (IO::Compress::Gzip) — Java backend using java.util.zip
3. Investigate Template Toolkit remaining failures (170/2072)
4. Look at near-PASS modules: Test::Deep (1266/1268), Moo (809/840), Log::Log4perl (715/719)

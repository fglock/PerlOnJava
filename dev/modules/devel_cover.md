# Devel::Cover Fix Plan

## Overview

**Module**: Devel::Cover 1.52
**Test command**: `./jcpan -t Devel::Cover`
**Status**: BLOCKED — Devel::Cover's own tests never run; dependency chain fails at HTML::Entities

**Note**: Devel::Cover is an XS module (`Cover.xs`) that instruments Perl's runloop.
It cannot function as a coverage tool under PerlOnJava without a Java XS backend.
The goal of this plan is to get the installation and test suite to run, and to fix
the dependency chain issues that also affect other CPAN modules.

## Dependency Tree

```
Devel::Cover 1.52
 ├─ Storable ✅ (bundled in jar)
 ├─ Digest::MD5 ✅ (bundled in jar)
 ├─ HTML::Entities >= 3.69 ❌ BLOCKING
 │    └─ HTML::Parser 3.83 (XS — no Java impl)
 │         ├─ HTML::Tagset ✅ PASS (33/33 subtests)
 │         ├─ HTTP::Headers ❌
 │         │    └─ HTTP::Message 7.01
 │         │         ├─ Clone ❌ (needs Clone::PP — MISSING)
 │         │         ├─ Compress::Raw::Bzip2 ❌ (XS)
 │         │         ├─ Compress::Raw::Zlib ❌ (XS + syntax error)
 │         │         ├─ Encode::Locale ❌ (Unknown encoding: locale)
 │         │         ├─ IO::Compress ❌ (cascades from Raw::Zlib)
 │         │         ├─ IO::HTML ❌ (File::Temp close, encoding names)
 │         │         ├─ LWP::MediaTypes ❌ (MIME type differences)
 │         │         ├─ MIME::Base64 ❌ ($VERSION undef)
 │         │         ├─ PerlIO::encoding ❌ (core module — no stub)
 │         │         ├─ Test::Needs ❌ (exit code handling)
 │         │         ├─ URI ❌ (UTF-8 encoding issues)
 │         │         │    ├─ MIME::Base32 ✅ PASS (31/31)
 │         │         │    └─ Test::Warnings ❌ (warning propagation)
 │         │         └─ Test::Fatal ✅ PASS (19/19)
 │         ├─ URI ❌ (see above)
 │         └─ URI::URL ❌ (same dist as URI)
 └─ (optional) JSON::MaybeXS, Sereal, Template, PPI::HTML, Perl::Tidy,
     Pod::Coverage, Test::Differences, Browser::Open
```

## Dependency Test Results Summary

| Distribution | Result | Tests | Root Cause |
|---|---|---|---|
| **HTML::Tagset 3.24** | ✅ PASS | 33/33 | Pure Perl |
| **Test::Fatal 0.018** | ✅ PASS | 19/19 | Pure Perl |
| **MIME::Base32 1.303** | ✅ PASS | 31/31 | Pure Perl |
| **Compress::Raw::Bzip2 2.218** | ❌ FAIL | 1/2 | XS can't load |
| **Compress::Raw::Zlib 2.222** | ❌ FAIL | 1/6 | XS + syntax error in .pm |
| **Encode 3.21** | ❌ FAIL | 20/52 | XS not built, many skipped |
| **Encode::Locale 1.05** | ❌ FAIL | 3/11 | Unknown encoding: locale |
| **IO::Compress 2.219** | ❌ FAIL | 202/248 | Cascading from Raw::Zlib/Bzip2 |
| **IO::HTML 1.004** | ❌ FAIL | 32/52 | File::Temp close, encoding names |
| **LWP::MediaTypes 6.04** | ❌ FAIL | 41/47 | MIME type detection differences |
| **MIME::Base64 3.16** | ❌ FAIL | 320/348 | Missing XS-only exports, $VERSION undef |
| **Test::Needs 0.002010** | ❌ FAIL | 200/227 | Subprocess exit code handling |
| **Test::Warnings 0.038** | ❌ FAIL | 86/88 | Warning propagation, bareword |
| **URI 5.34** | ❌ FAIL | 896/947 | UTF-8 encoding issues |
| **HTTP::Message 7.01** | ❌ FAIL | (cut off) | Clone::PP missing — blocks ALL tests |

---

## Error Categories

### 1. FIXED: ExtUtils::MakeMaker `$VERSION` not exported

**Affected**: Devel::Cover Makefile.PL (line 17)
**Error**:
```
"$VERSION" is not exported by the ExtUtils::MakeMaker module
Can't continue after import errors at Makefile.PL line 17.
```

**Root Cause**: Devel::Cover's Makefile.PL does `use ExtUtils::MakeMaker qw( $VERSION WriteMakefile )`.
PerlOnJava's MakeMaker only had `neatvalue` in `@EXPORT_OK`.

**Fix Applied**: Added `$VERSION`, `$Verbose`, and `_sprintf562` to `@EXPORT_OK`.
Added `our $Verbose = 0` declaration and `sub _sprintf562` (positional sprintf variant).

**File changed**: `src/main/perl/lib/ExtUtils/MakeMaker.pm` (lines 5–10, 593–601)

---

### 2. Clone::PP missing — blocks HTTP::Message

**Affected**: ALL HTTP::Message tests
**Error**:
```
Can't locate Clone/PP.pm in @INC
```

**Root Cause**: `src/main/perl/lib/Clone.pm` falls back to `require Clone::PP` when XS fails,
but `Clone/PP.pm` does not exist anywhere in the project or installed modules.

**Fix**: Bundle `Clone::PP` as a pure-Perl deep clone implementation.

**File to create**: `src/main/perl/lib/Clone/PP.pm`

---

### 3. MIME::Base64 `$VERSION` is undef

**Affected**: URI, HTTP::Message, and anything requiring `MIME::Base64 >= 2.1`
**Error**:
```
MIME::Base64 version >= 2.1 required--this is only version
```

**Root Cause**: The Java implementation (`MIMEBase64.java`) never sets `$MIME::Base64::VERSION`.
Other Java modules (Encode, Scalar::Util, List::Util) do set their versions.

**Fix**: Add `GlobalVariable.getGlobalVariable("MIME::Base64::VERSION").set(new RuntimeScalar("3.16"))` to `MIMEBase64.java`.

**File to change**: `src/main/java/org/perlonjava/runtime/perlmodule/MIMEBase64.java`

---

### 4. MIME::Base64 missing exports: `encode_base64url`, `decode_base64url`, length functions

**Affected**: MIME::Base64 tests (28 subtests)
**Error**:
```
"encode_base64url" is not exported by the MIME::Base64 module
"decode_base64url" is not exported by the MIME::Base64 module
"encoded_base64_length" is not exported by the MIME::Base64 module
"decoded_base64_length" is not exported by the MIME::Base64 module
```

**Root Cause**: Java implementation only provides `encode_base64` and `decode_base64`.
URL-safe variants and length functions are not implemented.

**Fix**: Add these functions to `MIMEBase64.java` or as a Perl wrapper.

---

### 5. MIME::QuotedPrint missing subroutines

**Affected**: MIME::Base64 dist tests
**Error**:
```
Undefined subroutine &MIME::QuotedPrint::encode
```

**Root Cause**: No Java or Perl implementation of MIME::QuotedPrint exists.

**Fix**: Create `src/main/perl/lib/MIME/QuotedPrint.pm` with pure-Perl encode/decode.

---

### 6. File::Temp `close` method not found

**Affected**: IO::HTML tests (t/20-open.t, t/30-outfile.t — 107+ subtests)
**Error**:
```
Undefined method close called on File::Temp object
```

**Root Cause**: File::Temp's AUTOLOAD delegates method calls to `$self->{_fh}`,
but `UNIVERSAL::can($fh, 'close')` returns false for regular filehandles since
`close` is a CORE function, not a method.

**Fix**: Add explicit `sub close` to `src/main/perl/lib/File/Temp.pm`.

---

### 7. PerlIO::encoding stub missing

**Affected**: HTTP::Message build, Encode tests
**Error**:
```
Can't locate PerlIO/encoding.pm in @INC
```

**Root Cause**: PerlIO::encoding is a core Perl module that PerlOnJava doesn't bundle.

**Fix**: Create a minimal stub at `src/main/perl/lib/PerlIO/encoding.pm`.

---

### 8. HTML::Entities needs `_decode_entities` from HTML::Parser XS

**Affected**: Devel::Cover's direct dependency
**Error**: HTML::Parser XS fails to load; `_decode_entities` unavailable.

**Root Cause**: `HTML::Entities` line 151 does `require HTML::Parser` for its XS-implemented
`decode_entities`. `encode_entities` is pure Perl but `decode_entities` is not.
There is no Java XS backend for HTML::Parser.

**Fix**: Provide a pure-Perl `_decode_entities` implementation, either:
- (a) Patch `HTML/Entities.pm` to include a PP fallback, or
- (b) Create a minimal Java impl in `HTMLParser.java` for `_decode_entities`

---

### 9. Compress::Raw::Zlib — XS load failure + indirect object syntax

**Affected**: IO::Compress chain (248 subtests)
**Error**:
```
syntax error at Compress/Raw/Zlib.pm line 168, near "::Raw::"
```

**Root Cause**: Line 168 uses indirect object syntax: `my $p = new Compress::Raw::Zlib::Parameters()`.
The XS load fails (no Java backend), and then the parser may fail on the class name.
PerlOnJava already has `CompressZlib.java` for `Compress::Zlib`, but no `Compress::Raw::Zlib`.

**Fix**: Either fix the parser to handle this indirect object syntax after XS failure,
or ensure the bundled Java `Compress::Zlib` takes precedence over the CPAN version.

---

### 10. Encoding issues (multiple)

**Affected**: URI, IO::HTML, Encode::Locale
**Errors**:
- `Unknown encoding: locale` (Encode::Locale)
- `Unknown encoding: latin-1` (Encode from_to.t)
- Encoding name case: `UTF-8` vs `utf-8-strict`, `ISO-8859-15` vs `iso-8859-15`
- UTF-8 double-encoding in URI percent-escaping
- `utf8::downgrade` not working correctly

**Root Cause**: PerlOnJava's Encode implementation has gaps in encoding aliases
and case-normalization compared to upstream Perl.

---

## Fix Plan

### Phase 1: ExtUtils::MakeMaker export fix (COMPLETED 2025-03)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 1.1 | Add `$VERSION`, `$Verbose` to `@EXPORT_OK` | `ExtUtils/MakeMaker.pm` | DONE |
| 1.2 | Add `our $Verbose = 0` declaration | `ExtUtils/MakeMaker.pm` | DONE |
| 1.3 | Add `_sprintf562` sub and export | `ExtUtils/MakeMaker.pm` | DONE |
| 1.4 | Build and verify (`make`) | — | DONE |

**Result**: `Makefile.PL -- OK`, Devel::Cover configuration succeeds.

### Phase 2: Quick wins — unblock dependency chain

| Step | Description | File | Status |
|------|-------------|------|--------|
| 2.1 | Bundle Clone::PP (pure-Perl deep clone) | `src/main/perl/lib/Clone/PP.pm` | TODO |
| 2.2 | Set `$MIME::Base64::VERSION` in Java impl | `MIMEBase64.java` | TODO |
| 2.3 | Create PerlIO::encoding stub | `src/main/perl/lib/PerlIO/encoding.pm` | TODO |
| 2.4 | Add explicit `sub close` to File::Temp | `src/main/perl/lib/File/Temp.pm` | TODO |
| 2.5 | Build and re-test (`make && ./jcpan -t Devel::Cover`) | — | TODO |

**Expected impact**: Unblocks HTTP::Message tests (Clone::PP), fixes MIME::Base64 version checks,
fixes IO::HTML File::Temp failures (107 subtests), and PerlIO::encoding import errors.

### Phase 3: MIME module completeness

| Step | Description | File | Status |
|------|-------------|------|--------|
| 3.1 | Add `encode_base64url` / `decode_base64url` | `MIMEBase64.java` or Perl wrapper | TODO |
| 3.2 | Add `encoded_base64_length` / `decoded_base64_length` | `MIMEBase64.java` or Perl wrapper | TODO |
| 3.3 | Create `MIME::QuotedPrint` pure-Perl impl | `src/main/perl/lib/MIME/QuotedPrint.pm` | TODO |
| 3.4 | Build and verify | — | TODO |

**Expected impact**: Fixes 28+ MIME::Base64 subtests, unblocks QuotedPrint-dependent code.

### Phase 4: HTML::Entities pure-Perl decode

| Step | Description | File | Status |
|------|-------------|------|--------|
| 4.1 | Implement pure-Perl `_decode_entities` | `src/main/perl/lib/HTML/Entities.pm` (override) | TODO |
| 4.2 | Or: create minimal Java XS for HTML::Parser | `HTMLParser.java` | TODO |
| 4.3 | Verify `use HTML::Entities; decode_entities(...)` works | — | TODO |
| 4.4 | Re-run `./jcpan -t Devel::Cover` | — | TODO |

**Expected impact**: Unblocks Devel::Cover's direct blocking dependency.
This is the critical phase — after this, Devel::Cover's own tests should start running.

### Phase 5: Encoding improvements (nice-to-have)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 5.1 | Add `locale` encoding support | `Encode.java` | TODO |
| 5.2 | Add `latin-1` alias | `Encode.java` | TODO |
| 5.3 | Normalize encoding name case (Perl returns lowercase) | `Encode.java` | TODO |
| 5.4 | Fix `utf8::downgrade` | Runtime code | TODO |

**Expected impact**: Fixes Encode::Locale (8 subtests), IO::HTML encoding name tests (20 subtests),
URI encoding tests (~50 subtests).

### Phase 6: Compress chain (optional, broad impact)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 6.1 | Fix indirect object syntax for deeply-nested package names | Parser (Java) | TODO |
| 6.2 | Or: ensure bundled Java Compress::Zlib takes precedence | `CompressZlib.java` | TODO |
| 6.3 | Add `Compress::Zlib::memGunzip`, `memGzip`, etc. | `CompressZlib.java` | TODO |
| 6.4 | Set `Compress::Zlib::VERSION` | `CompressZlib.java` | TODO |

**Expected impact**: Fixes IO::Compress cascade (46+ subtests). Not strictly needed for Devel::Cover
but improves broad CPAN compatibility.

---

## Summary

| Phase | Description | Complexity | Impact | Status |
|-------|-------------|-----------|--------|--------|
| 1 | MakeMaker export fix | Simple (3 edits) | Unblocks Makefile.PL | COMPLETED |
| 2 | Quick wins (Clone::PP, versions, stubs) | Simple-Medium (4 files) | Unblocks dep chain | TODO |
| 3 | MIME module completeness | Medium (3 functions + 1 module) | 28+ subtests | TODO |
| 4 | HTML::Entities decode | Medium (1 impl) | **Critical path** — unblocks Devel::Cover tests | TODO |
| 5 | Encoding improvements | Medium (4 changes) | ~78 subtests across deps | TODO |
| 6 | Compress chain | Hard (parser or Java) | 46+ subtests, broad CPAN impact | TODO |

**Critical path to Devel::Cover tests running**: Phase 1 → Phase 2 → Phase 4

**Note**: Even after all phases, Devel::Cover itself is an XS module that instruments
Perl's runloop via `Cover.xs`. It will install its `.pm` files but cannot function as
a coverage tool without a Java XS backend for the core instrumentation code.

## Related Documents

- `dev/modules/makemaker_perlonjava.md` — MakeMaker implementation details
- `dev/modules/xs_fallback.md` — XS fallback pattern
- `dev/modules/xsloader.md` — XSLoader implementation
- `dev/modules/io_stringy.md` — IO module compatibility (File::Temp, IO::Scalar)

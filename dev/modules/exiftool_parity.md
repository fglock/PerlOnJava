# Image::ExifTool Parity Fixes for PerlOnJava

## Overview

Image::ExifTool 13.55 has 113 test programs. When run under PerlOnJava,
98 pass cleanly, 4 are false-timeout (pass when run individually), and
11 have real failures totalling ~24 broken subtests across 6 root-cause
categories.

**Branch:** `fix/http-tiny-redirect-mirror` (started with HTTP::Tiny fix)
**Module version:** Image::ExifTool 13.55 (113 test programs)

### Results History

| Date | Programs OK | Subtests Failed | Key Fix |
|------|-------------|-----------------|---------|
| Baseline (pre-fix) | 0/113 | N/A | HTTP::Tiny 301 redirect + binary mirror — `jcpan` couldn't download |
| After HTTP fix | 98/113 | ~24 | Redirect following + binary-safe mirror |

### Current Failure Summary

| Test File | Fail/Total | Category |
|-----------|-----------|----------|
| GIF.t | 3/5 | Binary write (GIF header byte corruption) |
| IPTC.t | 1/8 | `join` UTF-8 flag on byte strings |
| CanonRaw.t | 1/9 | Binary write (maker notes offsets) |
| FujiFilm.t | 1/6 | Binary write (TIFF header corruption) |
| Geolocation.t | 1/8 | GPX/XML parsing (`$/` or regex) |
| Geotag.t | 4/12 | GPX/XML parsing (`$/` or regex) |
| MIE.t | 1/6 | Binary write (thumbnail offset +26 bytes) |
| Nikon.t | 1/9 | Binary write (IFD format corruption) |
| PNG.t | 1/7 | `join` UTF-8 flag corrupts written file |
| Writer.t | 8/61 | Binary write (multiple patterns) |
| XMP.t | 2/54 | Binary write + UTF-8 encoding |

False-timeout (pass when run individually): CanonVRD, FotoStation, Olympus, Pentax

---

## Root Cause Analysis

### RC1: `join` corrupts byte-string flag (~17 failures)

**Impact:** Categories 1-4, 6a, 6b — the dominant root cause

`StringOperators.joinInternal()` has three bugs:

1. **1-element fast path** (line 650): `new RuntimeScalar(scalar.toString())` always
   creates type `STRING`, discarding `BYTE_STRING`. When ExifTool does
   `$$outfile .= join('', @data)` with binary data, the UTF-8 flag
   propagates to the output buffer. On re-read, `Encode::is_utf8()` is true
   and `Encode::encode('utf8', $$arg)` re-encodes bytes >127 as multi-byte
   sequences, destroying JPEG/TIFF/GIF/PNG signatures.

2. **0-element fast path** (line 640): `new RuntimeScalar("")` creates STRING;
   should create BYTE_STRING when separator is byte-string.

3. **2+ element path** (line 679): `isByteString` requires ALL elements to be
   `BYTE_STRING`. In Perl, `join` on a mix of integers and byte-strings
   should produce a byte-string (integers have no UTF-8 flag). The check
   should treat non-STRING types (INTEGER, DOUBLE, UNDEF) as byte-compatible.

**Reproducer:**
```bash
./jperl -e 'use Encode; my $b = "\xff\xd8"; print Encode::is_utf8(join("", $b)), "\n"'
# PerlOnJava: 1 (wrong)
# Perl:       (empty, i.e. false)
```

### RC2: GPX/XML parsing failures (5 failures)

**Impact:** Geotag.t (4 failures), Geolocation.t (1 failure)

ExifTool's `Geotag.pm` reads GPX files using `$/ = '>'` as the input
record separator, then parses each chunk with regex. The "No track points
found" error means the parser can't match `<trkpt>` elements.

Possible sub-causes:
- `File::RandomAccess::ReadLine()` not honoring custom `$/`
- Regex backreference `\3` in attribute parser not working
- Floating-point interpolation issue (Geotag test 9 — wrong coordinates)

### RC3: UTF-8 / XML encoding (1 failure)

**Impact:** XMP.t test 35

XMP structured write with `AOTitle-de=pr\xc3\xbcfung` (UTF-8 "prüfung")
differs from reference output at line 13. May be double-encoding or
incorrect byte-to-character handling.

---

## Fix Plan

### Phase 1: Fix `join` byte-string preservation (RC1)

**File:** `src/main/java/org/perlonjava/runtime/operators/StringOperators.java`

**Changes:**
1. In 1-element fast path: preserve `BYTE_STRING` type from input element
2. In 0-element fast path: return BYTE_STRING when separator is byte-string
3. In 2+ element path: treat INTEGER, DOUBLE, UNDEF as byte-compatible
   (only STRING type upgrades to UTF-8)

**Expected impact:** Should fix most binary write corruption (IPTC, PNG,
Writer, CanonRaw, FujiFilm, GIF, MIE, Nikon, XMP test 3). This is the
highest-value fix.

**Verification:**
```bash
make                    # Unit tests pass
# Individual ExifTool tests:
cd /Users/fglock/.cpan/build/Image-ExifTool-13.55-0
../jperl -Ilib t/IPTC.t
../jperl -Ilib t/GIF.t
../jperl -Ilib t/Writer.t
../jperl -Ilib t/PNG.t
```

### Phase 2: Investigate GPX/Geotag parsing (RC2)

**Files to investigate:**
- `Image::ExifTool::Geotag` — GPX parser using `$/ = '>'`
- `File::RandomAccess` — `ReadLine()` method
- PerlOnJava regex engine — `\3` backreference support

**Steps:**
1. Test `$/` with a simple script: `$/ = '>'; while (<FH>) { ... }`
2. Test regex backreference: `'key="val"' =~ /(\w+)=(['"])(.*?)\2/`
3. If `$/` is the issue, fix in the I/O layer
4. If regex backreference, fix in `RegexPreprocessor.java`

**Verification:**
```bash
cd /Users/fglock/.cpan/build/Image-ExifTool-13.55-0
../jperl -Ilib t/Geotag.t
../jperl -Ilib t/Geolocation.t
```

### Phase 3: Investigate UTF-8 / XMP encoding (RC3)

**Steps:**
1. Diff XMP.t test 35 output vs reference file
2. Determine if it's double-encoding or byte-handling issue
3. May be fixed by Phase 1 (join fix)

**Verification:**
```bash
cd /Users/fglock/.cpan/build/Image-ExifTool-13.55-0
../jperl -Ilib t/XMP.t
```

---

## Progress Tracking

### Current Status: Phase 1 in progress

### Completed
- [x] HTTP::Tiny redirect + binary mirror fix (PR #472)
- [x] Full failure analysis and categorization

### Next Steps
1. Fix `joinInternal` byte-string preservation
2. Run full ExifTool test suite to measure improvement
3. Investigate remaining failures (GPX, UTF-8)

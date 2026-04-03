# Data::Printer Fix Plan

## Overview

- **Module**: Data::Printer 1.002001
- **Test command**: `./jcpan -j 8 -t Data::Printer`
- **Status**: 15/41 test files passing (354/545 subtests pass, 191 fail)

## Test Results Summary

### Current Status: 15/41 test files passing (baseline)

| Test File | Status | Failed | Notes |
|-----------|--------|--------|-------|
| t/000-load.t | PASS | | |
| t/000.0-nsort.t | PASS | | |
| t/000.1-home.t | **FAIL** | 1/4 | `$ENV{HOME}` override not working (gets `~` instead of `/ddp-home`) |
| t/000.2-warn.t | PASS | | |
| t/001-object.t | PASS | | |
| t/002-scalar.t | **FAIL** | 68/72 | `isweak()` always true + `readonly()` always false + `charnames::viacode` empty |
| t/003-ref.t | **FAIL** | 5/7 | `isweak()` always true |
| t/004-vstring.t | **FAIL** | 1/1 | `isweak()` always true (shows `(weak)` instead of `(read-only)`) |
| t/005-lvalue.t | **FAIL** | 2/2 | `isweak()` + LVALUE ref detection |
| t/006-glob.t | PASS | | |
| t/007.format.t | **FAIL** | 1/1 | FORMAT reference returns undef |
| t/008-regex.t | PASS | | |
| t/009-array.t | **FAIL** | 18/18 | `isweak()` always true |
| t/010-hashes.t | **FAIL** | 16/16 | `isweak()` always true |
| t/011-class.t | **FAIL** | 27/38 | `isweak()` + `B::CV::ROOT` missing (11 tests didn't run) |
| t/011.1-attributes.t | PASS | | |
| t/011.2-roles.t | PASS | | |
| t/011.3-object_pad.t | PASS | | |
| t/012-code.t | **FAIL** | 4/4 | `B::CV::ROOT` missing - crash |
| t/013-refcount.t | **FAIL** | 16/17 | `B::SV::RV` missing - crash after 1 test |
| t/014-memsize.t | PASS | | |
| t/015-multiline.t | **FAIL** | 1/1 | `isweak()` always true |
| t/016-merge_options.t | PASS | | |
| t/017-rc_file.t | PASS | | |
| t/018-alias.t | **FAIL** | 1/1 | `isweak()` always true |
| t/019-output.t | **FAIL** | 0 ran | `SEEK_SET` bareword error (Fcntl not exporting) |
| t/020-return_value.t | SKIP | | Capture::Tiny not found |
| t/021-p_vs_object.t | **FAIL** | 24/26 | `B::SV::RV` missing |
| t/022-no_prototypes.t | **FAIL** | 4/7 | `isweak()` + `readonly()` |
| t/023-filters.t | **FAIL** | 11/11 | `isweak()` always true |
| t/024-tied.t | **FAIL** | 18/18 | `isweak()` + ClassCastException: `RuntimeTiedArrayProxyEntry` cast to `TieScalar` |
| t/025-profiles.t | **FAIL** | 4/34 | Dumper profile: lvalue/format reftype + glob name + `B::CV::ROOT` |
| t/026-caller_message.t | **FAIL** | 2/2 | `isweak()` always true |
| t/027-nativeperlclass.t | SKIP | | `class` keyword issue |
| t/100-filter_datetime.t | **FAIL** | 6/21 | `isweak()` always true |
| t/101-filter_db.t | **FAIL** | 23/24 | DBI not connecting |
| t/102-filter_digest.t | **FAIL** | 3/21 | Digest::SHA filter not recognizing object |
| t/103-filter_contenttype.t | **FAIL** | 6/32 | `isweak()` always true |
| t/104-filter_web.t | **FAIL** | 7/21 | `isweak()` always true |
| t/998-color.t | **FAIL** | 1/1 | crash |
| t/999-themes.t | PASS | | |

## Error Categories

### 1. CRITICAL: `isweak()` returns true for ALL references

- **Affected tests**: ~20 test files, ~150+ failures
- **Root cause**: `ScalarUtil.isweak()` (line 184-196) returns `true` for any reference type. Since `weaken()` is a no-op on the JVM, nothing is ever actually weakened, so `isweak()` should return `false`.
- **Fix**: Change `isweak()` to always return `false`
- **File**: `src/main/java/org/perlonjava/runtime/perlmodule/ScalarUtil.java`

### 2. HIGH: `Scalar::Util::readonly()` always returns false

- **Affected tests**: t/002-scalar.t (hardcoded values), t/004-vstring.t, t/022-no_prototypes.t
- **Root cause**: `ScalarUtil.readonly()` (line 308-313) is a stub returning `false`. PerlOnJava **does** have readonly scalars via `RuntimeScalarReadOnly` class.
- **Fix**: Check `args.get(0) instanceof RuntimeScalarReadOnly`
- **File**: `src/main/java/org/perlonjava/runtime/perlmodule/ScalarUtil.java`

### 3. HIGH: Missing `B::CV::ROOT` and `B::SV::RV` methods

- **Affected tests**: t/012-code.t (4 tests), t/013-refcount.t (16 tests), t/021-p_vs_object.t (24 tests), t/011-class.t (11 planned tests didn't run)
- **Root cause**: `B.pm` stub doesn't define `ROOT` on `B::CV` or `RV` on `B::SV`
- **Fix**: Add stub methods to `src/main/perl/lib/B.pm`
- **File**: `src/main/perl/lib/B.pm`

### 4. MEDIUM: `SEEK_SET` not available from Fcntl

- **Affected tests**: t/019-output.t (all)
- **Root cause**: `Bareword "SEEK_SET" not allowed while "strict subs"` - Fcntl not exporting constants
- **Fix**: Check Fcntl implementation; missing modules can be imported via `dev/import-perl5/sync.pl`

### 5. MEDIUM: `charnames::viacode` returns empty strings

- **Affected tests**: t/002-scalar.t (unicode_charnames tests) - `\N{}` instead of `\N{LATIN SMALL LETTER E WITH ACUTE}`
- **Root cause**: `charnames::viacode` not implemented or broken; also "Missing argument in sprintf" warnings
- **Fix**: Check charnames module; may need import via `dev/import-perl5/sync.pl`

### 6. MEDIUM: Tied variable ClassCastException

- **Affected tests**: t/024-tied.t (crashes after 5 tests)
- **Root cause**: `RuntimeTiedArrayProxyEntry cannot be cast to TieScalar` - unsafe casts in `TieScalar.java` (lines 42,49) and `TieOperators.java` (lines 126,190) assume `TIED_SCALAR` value is always `TieScalar`
- **Fix**: Use `instanceof TiedVariableBase` or add instanceof checks before casting

### 7. LOW: DBI not connecting

- **Affected tests**: t/101-filter_db.t
- **Root cause**: DBI `connect` returns undef; test requires working database connection
- **Notes**: Low priority, database filter is optional

### 8. LOW: `$ENV{HOME}` override

- **Affected tests**: t/000.1-home.t (1 failure)
- **Root cause**: `local $ENV{HOME} = '/ddp-home'` not properly overriding (returns `~`)

### 9. LOW: FORMAT reference, color, Digest filter

- **Affected tests**: t/007.format.t, t/998-color.t, t/102-filter_digest.t
- **Notes**: Marginal impact, investigate after main issues fixed

## Fix Plan (Recommended Order)

### Phase 1: Quick wins - isweak + readonly (easy, massive impact)

| Step | Issue | Files | Expected impact |
|------|-------|-------|-----------------|
| 1a | Fix `isweak()` to return false (#1) | ScalarUtil.java, Builtin.java | ~150 failures across ~20 test files |
| 1b | Fix `readonly()` to detect RuntimeScalarReadOnly (#2) | ScalarUtil.java | ~5 failures in t/002-scalar.t, t/022-no_prototypes.t |

### Phase 2: B module stubs (medium effort, high impact)

| Step | Issue | Files | Expected impact |
|------|-------|-------|-----------------|
| 2a | Add `B::CV::ROOT` stub | src/main/perl/lib/B.pm | t/012-code.t, t/011-class.t, t/025-profiles.t |
| 2b | Add `B::SV::RV` stub | src/main/perl/lib/B.pm | t/013-refcount.t, t/021-p_vs_object.t |

### Phase 3: Fcntl SEEK_SET + charnames::viacode

| Step | Issue | Files | Expected impact |
|------|-------|-------|-----------------|
| 3a | Fix Fcntl SEEK_SET export (#4) | Fcntl module / import | t/019-output.t |
| 3b | Fix charnames::viacode (#5) | charnames module / import via sync.pl | t/002-scalar.t unicode tests |

### Phase 4: Tied variable cast fix

| Step | Issue | Files | Expected impact |
|------|-------|-------|-----------------|
| 4a | Fix unsafe TieScalar casts (#6) | TieScalar.java, TieOperators.java | t/024-tied.t |

### Phase 5: Remaining issues (low priority)

- DBI filter (t/101-filter_db.t)
- `$ENV{HOME}` override (t/000.1-home.t)
- FORMAT reference (t/007.format.t)
- Color/ANSI (t/998-color.t)
- Digest filter (t/102-filter_digest.t)

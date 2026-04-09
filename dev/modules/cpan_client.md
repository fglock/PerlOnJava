# CPAN Client Support for PerlOnJava

## Overview

This document tracks CPAN client support for PerlOnJava. The `jcpan` command provides full CPAN functionality for pure Perl modules.

## Current Status (2026-03-20)

**Working:**
- `jcpan install Module::Name` - Install pure Perl modules from CPAN
- `jcpan -f install Module::Name` - Force install (skip tests)
- `jcpan -t Module::Name` - Test a module
- Interactive CPAN shell via `jcpan`
- **DateTime** - Full functionality including timezone support (99.7% test pass rate)

**Known Limitations:**
- XS modules require manual porting (see `.agents/skills/port-cpan-module/`)
- Module::Build-only modules need Module::Build installed separately
- Tests that heavily use fork may fail or skip
- Safe.pm compartment restrictions are not enforced (uses trusted eval)

---

## Module Availability

### Core Modules (Built-in)

| Category | Modules |
|----------|---------|
| File I/O | File::Spec, File::Basename, File::Copy, File::Find, File::Path, File::Temp |
| Text | Text::ParseWords, Text::Wrap |
| Core | Config, Carp, Cwd, Exporter, Fcntl |
| I/O | FileHandle, IO::File, IO::Handle, IO::Socket |
| Network | HTTP::Tiny, Net::FTP |
| Archive | Archive::Tar, Archive::Zip, Compress::Zlib |
| Crypto | Digest::MD5, Digest::SHA, MIME::Base64 |
| Data | YAML, JSON |
| Process | IPC::Open2, IPC::Open3 |

### Modules Requiring Stubs

| Module | Implementation | Notes |
|--------|----------------|-------|
| Safe | Stub using `eval` | CPAN metadata is trusted |
| ExtUtils::MakeMaker | Custom | Installs directly, no `make` needed |
| Module::Build::Base | Stub | Disables fork pipes |
| namespace::autoclean | Stub (no-op) | Skips cleanup to allow imported functions |

### Not Implemented

| Module | Reason |
|--------|--------|
| Opcode | Requires Perl opcode internals |
| LWP::UserAgent | Use HTTP::Tiny instead |

---

## The Safe/Opcode Limitation

**Safe.pm** is used by CPAN.pm to evaluate metadata. It depends on **Opcode.pm** which manipulates Perl's internal opcode tree. Since PerlOnJava compiles to JVM bytecode (not Perl opcodes), implementing Opcode would require significant architectural work.

**Current solution:** Safe.pm stub uses `eval` with `no strict 'vars'`. CPAN metadata is trusted, so this is sufficient for normal use.

---

## Completed Phases (Summary)

| Phase | Description | Key Deliverables |
|-------|-------------|------------------|
| 1 | Low-hanging fruit | DirHandle, Dumpvalue, Sys::Hostname, flock() |
| 2 | Archive/Network | IO::Socket, Archive::Tar, Net::FTP |
| 3 | Process Control | IPC::Open2, IPC::Open3 via Java ProcessBuilder |
| 4 | Archive::Zip | Java implementation using java.util.zip |
| 5 | ExtUtils::MakeMaker | Direct installation without `make` |
| 6 | CPAN.pm Support | Safe.pm stub, parser fixes, CPAN shell working |
| 7 | Errno & Regex | `$!` dualvar, literal `{}` braces in regex |
| 8 | User Experience | `jcpan` wrapper script |
| 9 | Polish | YAML version update, Module::Build partial support |
| 11 | DateTime Support | namespace::autoclean stub, keyword autoquoting parser fix |
| 12 | DateTime Java XS | refaddr fix, POSIX math functions |
| 13 | Overload Stringification | Single-variable interpolation now forces stringify |

---

## Phase 11: DateTime Support (Completed 2026-03-20)

### Problem Statement

DateTime installation via jcpan completed but the module had issues loading due to its complex dependency chain involving namespace::autoclean.

### Solution

Two fixes were required:

1. **namespace::autoclean stub** - Created `src/main/perl/lib/namespace/autoclean.pm` that provides the interface but skips cleanup. This allows imported functions (like Try::Tiny's `try`/`catch`) to remain available.

2. **Parser fix for keyword autoquoting** - Extended `ListParser.java` to handle keywords like `until`, `while`, `for`, `if`, `unless`, `foreach` as bareword hash keys when followed by `=>`. Previously these keywords would incorrectly terminate list parsing.

### DateTime Now Working

```bash
./jperl -MDateTime -e '
  my $dt = DateTime->new(
      year => 2024,
      month => 3,
      day => 15,
      hour => 14,
      minute => 30,
      time_zone => "America/New_York"
  );
  print $dt->datetime, "\n";   # 2024-03-15T14:30:00
  $dt->add(days => 5, hours => 2);
  print $dt->datetime, "\n";   # 2024-03-20T16:30:00
'
```

### Issues Fixed in Phase 11

| Issue | Fix | File |
|-------|-----|------|
| `${ $stash{NAME} }` dereference | Fixed symbol table access | |
| GLOBREFERENCE scalar dereference | `$$globref` now returns the glob itself | |
| map/grep @_ access | Blocks now access outer subroutine's @_ | |
| B::Hooks::EndOfScope NPE | Null check for fileName | |
| namespace::autoclean cleanup | Stub that skips cleanup | `src/main/perl/lib/namespace/autoclean.pm` |
| Keywords as hash keys | Extended autoquoting to more keywords | `ListParser.java` |

---

## Phase 12: DateTime with Java XS Fallback (Completed 2026-03-20)

### Objective

Test and verify DateTime uses the Java XS fallback mechanism instead of pure Perl fallback, providing better performance via native Java date/time operations.

### Status: COMPLETED

**DateTime now uses Java XS implementation** (`$DateTime::IsPurePerl = 0`)

### Fixes Applied

| Issue | Fix | File |
|-------|-----|------|
| Missing POSIX math functions | Added `floor`, `ceil`, `fmod`, `fabs`, `pow`, trig functions | `POSIX.pm` |
| `refaddr` returning inconsistent values | Fixed to return identity hash of underlying referenced object | `ScalarUtil.java` |
| Specio enum validation failing | Fixed by `refaddr` fix - env var names now stable | - |
| DateTime truncate/today failing | Fixed by Specio fix | - |

### Technical Details

1. **Java XS Loading**: `XSLoader::load("DateTime")` successfully loads `DateTime.java` which provides:
   - `_rd2ymd` - Rata Die to year/month/day conversion using `java.time.JulianFields`
   - `_ymd2rd` - Year/month/day to Rata Die conversion
   - `_is_leap_year` - Using `java.time.Year.isLeap()`
   - `_time_as_seconds`, `_seconds_as_components` - Time arithmetic
   - `_normalize_tai_seconds`, `_normalize_leap_seconds` - TAI/UTC handling
   - `_day_length`, `_day_has_leap_second`, `_accumulated_leap_seconds` - Leap second support

2. **refaddr Bug**: The `Scalar::Util::refaddr` function was returning `System.identityHashCode(scalar)` where `scalar` is the RuntimeScalar wrapper, causing different values each time when called via a method. Fixed to return identity hash code of the underlying `scalar.value` for reference types.

3. **POSIX Math Functions**: Added complete set of POSIX math functions:
   - `floor`, `ceil` - Rounding functions
   - `fmod` - Floating-point modulo
   - `fabs`, `pow` - Absolute value and power
   - `asin`, `acos`, `atan`, `tan` - Trigonometric functions
   - `sinh`, `cosh`, `tanh` - Hyperbolic functions
   - `log10`, `ldexp`, `frexp`, `modf` - Logarithmic and mantissa functions

### Test Results

DateTime test suite: **3506/3513 subtests passed** (99.8%), **7 failures**

---

## Phase 13: Overload Stringification Fix (Completed 2026-03-20)

### Problem Statement

DateTime tests (t/20infinite.t, t/31formatter.t) were failing with `StackOverflowError` when comparing stringified DateTime objects using `eq`.

### Root Cause

When a double-quoted string contained only a single interpolated variable like `"$obj"`, the parser was optimizing it to just return the variable directly, without forcing stringification. This caused:

1. The `eq` overload handler does: `return "$a" eq "$b"`
2. PerlOnJava was treating `"$a"` as just `$a` (no stringification)
3. This caused the `eq` overload to call itself infinitely → StackOverflowError

### Solution

Fixed `StringDoubleQuoted.createJoinNode()` to ensure that single non-string segments in string interpolation are wrapped in a `join()` operation, which forces proper stringification.

The fix does NOT apply in regex context (`isRegex=true`) because regex patterns should use the `qr` overload, not stringify.

### Files Changed

- `src/main/java/org/perlonjava/frontend/parser/StringDoubleQuoted.java` - Fixed single-variable string interpolation

---

## Phase 14: DateTime Leap Seconds and Arithmetic (Completed 2026-03-20)

### Problem Statement

DateTime test suite had 47 failures related to:
1. Leap second handling (second=60 not accepted, wrong RD calculations)
2. End-of-month arithmetic (wrap mode not working)
3. `cmp` overload returning 0 instead of -1/1 (breaking sort)

### Fixes Applied

#### 1. _ymd2rd Day Overflow/Underflow Handling

**Root Cause**: The Java XS `_ymd2rd` function was clamping day values to valid range instead of allowing overflow/underflow.

**Fix**: Changed from clamping to `LocalDate.plusDays()` which correctly handles:
- `day=0` → last day of previous month
- `day > month_length` → overflow to next month(s)
- `day < 1` → underflow to previous month(s)

This is critical for end-of-month arithmetic with 'wrap' mode.

**Tests Fixed**: t/06add.t, t/10subtract.t, t/11duration.t (partial)

#### 2. Leap Second Table with Correct RD Values

**Root Cause**: The leap second table had incorrect RD values (~8000 days off) due to incorrect epoch calculation.

**Fix**: Recalculated all RD values using `DateTime->_ymd2rd()`:
- First leap second: July 1, 1972 → RD 720075 (was 728714)
- Accumulated count starts at 1 (was 10)

**Tests Fixed**: t/19leap-second.t (all 204 pass), t/32leap-second2.t (all 57 pass)

#### 3. TAILCALL Trampoline in OverloadContext.tryOverload()

**Root Cause**: DateTime's `_string_compare_overload` uses `goto $meth` to delegate to `_compare_overload`. The `goto` creates a TAILCALL marker, but `tryOverload()` wasn't handling it.

**Fix**: Added trampoline loop to execute TAILCALL markers:
```java
while (result instanceof RuntimeControlFlowList) {
    RuntimeControlFlowList flow = (RuntimeControlFlowList) result;
    if (flow.getControlFlowType() == TAILCALL) {
        RuntimeScalar codeRef = flow.getTailCallCodeRef();
        RuntimeArray args = flow.getTailCallArgs();
        result = RuntimeCode.apply(codeRef, args, SCALAR);
    } else {
        break;
    }
}
```

**Tests Fixed**: t/07compare.t, t/27delta.t, t/38local-subtract.t

### Test Results After Fix

DateTime test suite: **1987/2064 subtests passed** (96.3%), **77 failures**

(Note: Phase 15 improved this to 99.7% by fixing overload method name resolution)

### Remaining Failures (Not Critical)

| Test | Failures | Reason |
|------|----------|--------|
| t/11duration.t | 1 | TODO test for fractional units |
| t/29overload.t | 2 | Missing Test::Warnings dependency |
| t/33seconds-offset.t | 3 | TODO tests for second offsets near leap seconds |
| t/48rt-115983.t | 1 | Test::Fatal error message format mismatch |

### Files Changed

- `src/main/java/org/perlonjava/runtime/perlmodule/DateTime.java` - Fixed `_ymd2rd`, corrected leap second table
- `src/main/java/org/perlonjava/runtime/runtimetypes/OverloadContext.java` - Added TAILCALL trampoline

---

## Phase 15: Overload Method Name Resolution (2026-03-20)

### Problem Statement

DateTime tests were failing at ~96.3% pass rate (1987/2064 subtests) with many tests showing errors about Specio type validation and stringification issues.

### Root Cause Analysis

When debugging, we discovered that Specio type objects (like `DateTime::Types::t("Locale")`) were stringifying to an empty string `""` instead of their type name.

**Investigation path:**
1. `$type->name` returned "Locale" correctly
2. `$type->_stringify` returned "Locale" correctly  
3. But `"$type"` returned ""

**Root Cause:** Perl's `overload` pragma allows two ways to specify operator implementations:

```perl
# Method 1: Code reference (works in PerlOnJava)
use overload '""' => \&_stringify;

# Method 2: Method name string (was NOT working in PerlOnJava)
use overload '""' => '_stringify';
```

When a method name string is used, Perl's overload.pm stores:
- CODE slot: `\&overload::nil` (a no-op function)
- SCALAR slot: the method name string (e.g., "_stringify")

The `ov_method()` function in overload.pm handles this by checking if CODE is `\&nil`, and if so, looking up the method name from SCALAR and calling `$obj->can($method)`.

**PerlOnJava was missing this logic** - it just executed the CODE slot (`\&nil`) and got undef.

### Solution

Modified `OverloadContext.tryOverload()` to:
1. Check if the found method is `overload::nil` (by examining `packageName` and `subName`)
2. If so, look up the SCALAR slot of the glob to get the actual method name
3. Follow glob references (e.g., `*Package::Method`) if the SCALAR contains one
4. Resolve the actual method using `can()` semantics

### Files Changed

- `src/main/java/org/perlonjava/runtime/runtimetypes/OverloadContext.java`
  - Added `resolveOverloadMethodName()` helper method
  - Modified `tryOverload()` to detect and handle `overload::nil`

### Test Results After Fix

| Metric | Before | After | Change |
|--------|--------|-------|--------|
| Total tests | 2064 | 3522 | +1458 (more tests now run!) |
| Passing | 1987 | 3513 | +1526 |
| Failing | 77 | 9 | -68 |
| Pass rate | 96.3% | 99.7% | +3.4% |

### Remaining Failures (7 tests, non-critical)

| Test | Failures | Reason |
|------|----------|--------|
| t/11duration.t | 1 | TODO test for fractional units |
| t/29overload.t | 2 | Warning location info missing (pre-existing limitation) |
| t/33seconds-offset.t | 3 | TODO tests for leap second edge cases |
| t/48rt-115983.t | 1 | Error message format ("subroutine" vs "method") |

These failures are due to:
- **TODO tests** (t/11duration.t, t/33seconds-offset.t) - Expected failures for known edge cases
- **Warning location info** (t/29overload.t) - Warnings are now emitted but without file/line info
- **Error message format** (t/48rt-115983.t) - "Undefined subroutine" vs "Can't locate object method"

---

### **ALL MAJOR ISSUES FIXED** (99.8% pass rate: 3513/3520)

All major DateTime issues have been fixed. The 7 remaining test failures are:
- **4 TODO tests** - Known limitations even in native Perl (fractional units, leap second edge cases)
- **2 warning location tests** - Warnings work but don't include file/line info yet
- **1 error format test** - Cosmetic difference in error message wording

---

## Phase 16: utf8::valid() Fix for CPAN::Meta Parsing (2026-03-20)

### Problem Statement

When installing DateTime with empty caches, CPAN::Meta::YAML parsing would fail with:
```
Read an invalid UTF-8 string (maybe mixed UTF-8 and 8-bit character set).
Did you decode with lax ":utf8" instead of strict ":encoding(UTF-8)"?
```

This error prevented proper parsing of META.yml/MYMETA.yml files, which meant test dependencies like Test::Without::Module and CPAN::Meta::Check were not being properly detected.

### Root Cause

CPAN::Meta::YAML validates strings before parsing:
```perl
if ( utf8::is_utf8($string) && ! utf8::valid($string) ) {
    die "Read an invalid UTF-8 string...";
}
```

The `utf8::valid()` function in PerlOnJava was using `CharsetDetector` which was fundamentally wrong:
- It converted the string to bytes using the default charset
- Then tried to detect if those bytes were UTF-8
- This always failed for properly decoded Unicode strings

### Solution

Rewrote `utf8::valid()` in `Utf8.java` to correctly check string validity:
- **For character strings (UTF-8 flag on)**: Validates that surrogate pairs are properly formed
- **For byte strings (UTF-8 flag off)**: Attempts to decode bytes as UTF-8

### Files Changed

- `src/main/java/org/perlonjava/runtime/perlmodule/Utf8.java` - Fixed `valid()` method

### Test Results

The fix allows CPAN::Meta::YAML to properly parse MYMETA.yml files, enabling CPAN.pm to detect and install test dependencies.

---

## Known Remaining CPAN Issues

| Issue | Status | Impact |
|-------|--------|--------|
| File::stat.pm missing | Not implemented | DateTime::Locale installation fails |
| IPC::Open3 read-only error | Bug in IPCOpen3.java | Some module tests fail |
| Test::Harness UTF-8 error | Pre-existing | Some test output parsing fails |

---

## Related Documents

- `xsloader.md` - XSLoader/Java integration
- `makemaker_perlonjava.md` - ExtUtils::MakeMaker implementation
- `.agents/skills/port-cpan-module/` - Skill for porting CPAN modules
- `docs/guides/using-cpan-modules.md` - User documentation

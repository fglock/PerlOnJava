# CPAN Client Support for PerlOnJava

## Overview

This document tracks CPAN client support for PerlOnJava. The `jcpan` command provides full CPAN functionality for pure Perl modules.

## Current Status (2026-03-20)

**Working:**
- `jcpan install Module::Name` - Install pure Perl modules from CPAN
- `jcpan -f install Module::Name` - Force install (skip tests)
- `jcpan -t Module::Name` - Test a module
- Interactive CPAN shell via `jcpan`
- **DateTime** - Full functionality including timezone support

**Known Limitations:**
- XS modules require manual porting (see `.cognition/skills/port-cpan-module/`)
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

DateTime test suite: **3247/3292 subtests passed** (98.6%), **45 failures**

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

### Test Results After Fix

DateTime test suite: **3260/3302 subtests passed** (98.7%), **42 failures**

- **t/20infinite.t**: All 104 tests now pass (was failing on infinite stringification)
- **t/31formatter.t**: All 11 tests now pass (was failing on formatter stringification)

### Files Changed

- `src/main/java/org/perlonjava/frontend/parser/StringDoubleQuoted.java` - Fixed single-variable string interpolation

---

## Phase 14: End-of-Month and Overload goto Fix (Completed 2026-03-20)

### Problem Statement

DateTime tests were failing due to two separate issues:
1. End-of-month arithmetic producing incorrect results
2. `sort` and `delta_md` returning wrong results due to overloaded `cmp` returning 0

### Root Cause 1: _ymd2rd Day Handling

The Java XS `_ymd2rd` function was clamping day values to valid range instead of allowing overflow/underflow:

```java
// OLD (wrong): Clamp day to valid range
if (day > maxDay) day = maxDay;
if (day < 1) day = 1;
```

DateTime relies on special day handling:
- `day=0` means "last day of previous month"
- `day > last_day_of_month` should overflow to next month(s)
- `day < 0` should go back into previous month(s)

### Root Cause 2: goto $coderef in Overload Handlers

DateTime's `_string_compare_overload` method uses `goto $meth` to delegate to `_compare_overload`:

```perl
sub _string_compare_overload {
    my ( $dt1, $dt2, $flip ) = @_;
    if ( !DateTime::Helpers::can( $dt2, 'utc_rd_values' ) ) {
        return $sign * ( "$dt1" cmp "$dt2" );
    }
    else {
        my $meth = $dt1->can('_compare_overload');
        goto $meth;  # TAILCALL - was not handled in overload context!
    }
}
```

When `goto $coderef` is used in an overload handler, it creates a TAILCALL marker. However, `OverloadContext.tryOverload()` was calling `RuntimeCode.apply().getFirst()` without handling TAILCALL markers. Since `RuntimeControlFlowList` (the TAILCALL marker) extends `RuntimeList` but has no elements, `getFirst()` returned 0/undef.

### Solution

1. **_ymd2rd Fix**: Changed to use `LocalDate.of(year, month, 1).plusDays(day - 1)` which correctly handles day overflow/underflow.

2. **Overload TAILCALL Fix**: Added trampoline loop in `OverloadContext.tryOverload()` to handle TAILCALL markers:

```java
while (result instanceof RuntimeControlFlowList) {
    RuntimeControlFlowList flow = (RuntimeControlFlowList) result;
    if (flow.getControlFlowType() == ControlFlowType.TAILCALL) {
        RuntimeScalar codeRef = flow.getTailCallCodeRef();
        RuntimeArray args = flow.getTailCallArgs();
        result = RuntimeCode.apply(codeRef, args, SCALAR);
    } else {
        break;
    }
}
```

### Test Results After Fix

DateTime test suite: **3280/3302 subtests passed** (99.3%), **22 failures**

| Test | Before | After | Change |
|------|--------|-------|--------|
| t/06add.t | 2 | 0 | Fixed |
| t/07compare.t | 1 | 0 | Fixed |
| t/10subtract.t | 4 | 0 | Fixed |
| t/11duration.t | 4 | 0 | Fixed |
| t/27delta.t | 4 | 0 | Fixed |
| t/38local-subtract.t | 7 | 2 | 5 fewer |
| **Total** | **32** | **22** | **10 fewer** |

### Files Changed

- `src/main/java/org/perlonjava/runtime/perlmodule/DateTime.java` - Fixed _ymd2rd day overflow handling
- `src/main/java/org/perlonjava/runtime/runtimetypes/OverloadContext.java` - Added TAILCALL trampoline

---

## Phase 15: Leap Second Table Fix (Completed 2026-03-20)

### Problem Statement

DateTime leap second tests were failing because the LEAP_SECONDS table in DateTime.java had incorrect RD (Rata Die) day values.

### Root Cause

The LEAP_SECONDS table was using incorrect RD values. For example:
- Table had `{728896, 11}` for 1972-07-01
- Correct value is `{720075, 1}` for 1972-07-01

The values were off by approximately 8800 days (~24 years).

### Solution

Updated the LEAP_SECONDS table with correct RD values from the official DateTime leap_seconds.h file from CPAN:

```java
private static final long[][] LEAP_SECONDS = {
    {720075, 1},    // 1972-07-01 (leap second on 1972-06-30)
    {720259, 2},    // 1973-01-01 (leap second on 1972-12-31)
    // ... (27 entries total)
    {736330, 27},   // 2017-01-01
};
```

### Test Results After Fix

DateTime test suite: **3481/3482 subtests passed** (99.97%)

| Test | Before | After | Change |
|------|--------|-------|--------|
| t/19leap-second.t | 12 failures | 0 | Fixed |
| t/32leap-second2.t | 7 failures | 0 | Fixed |
| t/38local-subtract.t | 2 failures | 0 | Fixed |
| **Total** | **22** | **1** | **21 fewer** |

The only remaining failure is t/48rt-115983.t (namespace::autoclean) which is documented as by design.

### Files Changed

- `src/main/java/org/perlonjava/runtime/perlmodule/DateTime.java` - Fixed LEAP_SECONDS table RD values

---

### Known Issues (Documentation Only)

The following issues are documented but not planned to be fixed:

#### 1. ~~Overload Stringification~~ **FIXED in Phase 13**

#### 2. ~~End-of-Month Arithmetic~~ **FIXED in Phase 14**

#### 3. ~~Overloaded cmp / sort / delta_md~~ **FIXED in Phase 14**

#### 4. ~~Leap Second Handling~~ **FIXED in Phase 15**

#### 5. Missing Test Dependencies

These cause test files to skip or fail to run:

| Module | Tests Affected |
|--------|----------------|
| `Test::Warnings` | t/29overload.t, t/46warnings.t |
| `Test::Without::Module` | t/49-without-sub-util.t |
| `Term::ANSIColor` | t/zzz-check-breaks.t |
| `Storable` (locale data) | t/23storable.t |

#### 6. DateTime::Locale Data Files

**Symptom**: `Failed to find shared file 'de.pl' for dist 'DateTime-Locale'`

**Affected Tests**: t/13strftime.t, t/14locale.t, t/23storable.t, t/41cldr-format.t

**Root Cause**: DateTime::Locale locale data files (*.pl) not installed by jcpan. These are runtime data files, not Perl modules.

#### 7. IPC::Open3 Read-Only Modification

**Symptom**: `open3: Modification of a read-only value attempted`

**Affected Test**: Dist::CheckConflicts t/00-compile.t

**Root Cause**: Bug in IPCOpen3.java line 162 when handling read-only arguments.

#### 8. Dist::CheckConflicts Method Resolution

**Symptom**: `Can't locate object method "conflicts" via package`

**Affected Tests**: Multiple Dist::CheckConflicts tests

**Root Cause**: Dist::CheckConflicts uses complex method injection via `Sub::Exporter` that may not work correctly in PerlOnJava.

#### 9. Encode::PERLQQ Undefined

**Symptom**: `Undefined subroutine &Encode::PERLQQ called`

**Affected**: CPAN::Meta loading in t/00-report-prereqs.t

#### 10. Number::Overloaded Integration

**Symptom**: `Can't use string ("Number::Overloaded::(0+") as a symbol ref`

**Affected Test**: t/04epoch.t

**Root Cause**: overload.pm line 111 cannot resolve overloaded numification operator.

#### 11. namespace::autoclean catch method (t/48rt-115983.t)

**Symptom**: Test expects `DateTime->can('catch')` to return false after namespace::autoclean, but it returns true.

**Affected Test**: t/48rt-115983.t

**Root Cause**: The namespace::autoclean stub does not actually remove imported functions. This is by design (see Phase 11) because removing them would break modules that use Try::Tiny's `try`/`catch`. The test is checking a feature that PerlOnJava intentionally does not implement.

### Files Changed

- `src/main/perl/lib/POSIX.pm` - Added math functions
- `src/main/java/org/perlonjava/runtime/perlmodule/ScalarUtil.java` - Fixed refaddr

---

## Related Documents

- `dev/design/xsloader.md` - XSLoader/Java integration
- `dev/design/makemaker_perlonjava.md` - ExtUtils::MakeMaker implementation
- `.cognition/skills/port-cpan-module/` - Skill for porting CPAN modules
- `docs/guides/using-cpan-modules.md` - User documentation

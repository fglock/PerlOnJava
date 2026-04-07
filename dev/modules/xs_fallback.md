# XS Fallback Mechanism for PerlOnJava

## Overview

This document describes a mechanism to allow CPAN modules with XS code to work on PerlOnJava by:
1. Installing the Perl (.pm) files unchanged from CPAN
2. Automatically falling back to pure Perl implementations when available
3. Optionally providing Java XS implementations for performance

## Problem Statement

Many popular CPAN modules contain XS (C) code for performance, but also provide pure Perl fallbacks:

| Module | XS Component | Pure Perl Fallback |
|--------|--------------|-------------------|
| DateTime | DateTime.xs | DateTime::PP |
| JSON::XS | XS.xs | JSON::PP |
| Cpanel::JSON::XS | XS.xs | JSON::PP |
| List::Util | ListUtil.xs | List::Util::PP |
| Scalar::Util | SharedHash.xs | (partial) |
| Clone | Clone.xs | Clone::PP |
| Params::Util | Util.xs | (pure Perl methods) |

Currently, when a user runs `jcpan install DateTime`:
1. MakeMaker detects XS files and refuses to install
2. User gets an error message about XS modules

**Goal**: Make `jcpan install DateTime` work out of the box.

---

## DateTime Module Analysis

### Structure

```
DateTime-1.66/
├── DateTime.xs          # XS code (performance)
├── lib/
│   ├── DateTime.pm      # Main module
│   ├── DateTime/
│   │   ├── PP.pm        # Pure Perl fallback
│   │   ├── PPExtra.pm   # Additional pure Perl code
│   │   ├── Duration.pm
│   │   ├── Helpers.pm
│   │   ├── Infinite.pm
│   │   ├── LeapSecond.pm
│   │   ├── Locale.pm
│   │   ├── TimeZone.pm
│   │   └── Types.pm
└── ...
```

### XS Loading Mechanism in DateTime.pm

```perl
our $IsPurePerl;
{
    my $loaded = 0;
    unless ( $ENV{PERL_DATETIME_PP} ) {
        try {
            require XSLoader;
            XSLoader::load( __PACKAGE__, $VERSION );
            $loaded = 1;
            $IsPurePerl = 0;
        }
        catch {
            # Key: Only die if error doesn't match expected patterns
            die $_ if $_ && $_ !~ /object version|loadable object/;
        };
    }
    if (!$loaded) {
        require DateTime::PP;  # Pure Perl fallback
    }
}
```

**Key Insight**: DateTime catches XSLoader failures and falls back to `DateTime::PP` if the error message matches `/object version|loadable object/`.

### XS Functions in DateTime.xs

The XS file provides 10 optimized functions:

| XS Function | Purpose | Pure Perl Equivalent |
|-------------|---------|---------------------|
| `_rd2ymd` | Rata Die days → year/month/day | `DateTime::PP::_rd2ymd` |
| `_ymd2rd` | year/month/day → Rata Die days | `DateTime::PP::_ymd2rd` |
| `_time_as_seconds` | h/m/s → total seconds | `DateTime::PP::_time_as_seconds` |
| `_seconds_as_components` | seconds → h/m/s | `DateTime::PP::_seconds_as_components` |
| `_normalize_tai_seconds` | Normalize TAI seconds | `DateTime::PP::_normalize_tai_seconds` |
| `_normalize_leap_seconds` | Handle leap seconds | `DateTime::PP::_normalize_leap_seconds` |
| `_is_leap_year` | Check leap year | `DateTime::PP::_is_leap_year` |
| `_day_length` | Get day length (leap seconds) | `DateTime::PP::_day_length` |
| `_day_has_leap_second` | Check for leap second | (derived) |
| `_accumulated_leap_seconds` | Get accumulated leap seconds | `DateTime::PP::_accumulated_leap_seconds` |

All functions are pure computational (no I/O, no external dependencies).

---

## Implementation Plan

### Phase 1: XSLoader Compatibility (Easy)

**Goal**: Make XSLoader die with a message that modules recognize as "XS not available".

**Current behavior** (XSLoader.java):
```java
return WarnDie.die(
    new RuntimeScalar("Can't load Java XS module: " + moduleName),
    new RuntimeScalar("\n")
).getList();
```

**New behavior**:
```java
return WarnDie.die(
    new RuntimeScalar("Can't load loadable object for module " + moduleName + 
                      ": No XS implementation available in PerlOnJava"),
    new RuntimeScalar("\n")
).getList();
```

This matches the pattern `/loadable object/` that DateTime (and many other modules) expect.

**Files to modify**:
- `src/main/java/org/perlonjava/runtime/perlmodule/XSLoader.java`

**Test**:
```perl
# Should not die, should fall back to DateTime::PP
use DateTime;
print DateTime->now->ymd, "\n";
print "IsPurePerl: $DateTime::IsPurePerl\n";  # Should print 1
```

---

### Phase 2: MakeMaker XS Handling (Medium)

**Goal**: Install .pm files from XS modules when pure Perl fallback exists.

**Current behavior**: MakeMaker refuses to install XS modules.

**New behavior**:
1. Detect if module has pure Perl fallback capability
2. If yes, install .pm files and let runtime fallback work
3. If no, show current error message

**Detection strategies**:

#### Option A: Pattern Detection (Recommended)
Check for common fallback patterns in the main .pm file:

```perl
sub _has_pure_perl_fallback {
    my ($pm_file, $module_name) = @_;
    
    return 0 unless -f $pm_file;
    
    open my $fh, '<', $pm_file or return 0;
    my $content = do { local $/; <$fh> };
    close $fh;
    
    # Pattern 1: Try/catch around XSLoader with fallback require
    # e.g., DateTime, JSON::XS
    return 1 if $content =~ /try\s*\{[^}]*XSLoader[^}]*\}[^}]*catch[^}]*require\s+[\w:]+::PP/s;
    
    # Pattern 2: eval around XSLoader
    # e.g., Params::Util
    return 1 if $content =~ /eval\s*\{[^}]*XSLoader[^}]*\}[^;]*(?:require|use)\s+[\w:]+::PP/s;
    
    # Pattern 3: Explicit PP module exists
    my $pp_module = "${module_name}::PP";
    (my $pp_file = $pp_module) =~ s{::}{/}g;
    $pp_file = "lib/$pp_file.pm";
    return 1 if -f $pp_file;
    
    return 0;
}
```

#### Option B: Registry of Known Modules
Maintain a list of modules known to have fallbacks:

```perl
my %KNOWN_FALLBACKS = (
    'DateTime'         => 'DateTime::PP',
    'JSON::XS'         => 'JSON::PP',
    'Cpanel::JSON::XS' => 'JSON::PP',
    'List::Util'       => 1,  # Built-in fallback
    'Scalar::Util'     => 1,
    'Clone'            => 'Clone::PP',
);
```

**Files to modify**:
- `src/main/perl/lib/ExtUtils/MakeMaker.pm`

**Implementation**:

```perl
sub _handle_xs_module {
    my ($name, $xs_files, $args) = @_;
    
    # Check for pure Perl fallback
    my $main_pm = _find_main_pm($name, $args);
    
    if (_has_pure_perl_fallback($main_pm, $name)) {
        print "\n";
        print "XS MODULE WITH PURE PERL FALLBACK: $name\n";
        print "=" x 60, "\n";
        print "\n";
        print "This module has XS code but includes a pure Perl fallback.\n";
        print "Installing Perl files only - XS will fall back to pure Perl.\n";
        print "\n";
        
        # Install the .pm files
        return _install_pure_perl($name, $args->{VERSION} || '0', $args);
    }
    
    # No fallback - show current error
    # ... existing code ...
}
```

---

### Phase 3: DateTime Java XS Implementation (Advanced)

**Goal**: Provide optional Java XS implementation for better performance.

#### Java Built-in Support

Java's `java.time` package provides excellent support for most DateTime calculations:

| DateTime XS Function | Java API |
|---------------------|----------|
| `_ymd2rd(y, m, d)` | `LocalDate.of(y, m, d).getLong(JulianFields.RATA_DIE)` |
| `_rd2ymd(rd)` | `LocalDate.MIN.with(JulianFields.RATA_DIE, rd)` |
| `_is_leap_year(y)` | `Year.isLeap(y)` |
| Day of week | `LocalDate.getDayOfWeek().getValue()` |
| Day of year | `LocalDate.getDayOfYear()` |

**Key Discovery**: Java has **built-in Rata Die support** via `JulianFields.RATA_DIE`!

#### Leap Seconds - Custom Table Required

Java intentionally uses UTC-SLS (smoothed leap seconds) and doesn't track actual leap seconds:

> "On days that do have a leap second, the leap second is spread equally over the last 1000 seconds of the day"

So we still need a leap seconds table for:
- `_day_length` (86400 or 86401 seconds)
- `_normalize_leap_seconds`
- `_accumulated_leap_seconds`

**File**: `src/main/java/org/perlonjava/runtime/perlmodule/DateTime.java`

```java
package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.JulianFields;

/**
 * Java XS implementation for DateTime.
 * Uses java.time APIs where possible for optimized date/time calculations.
 */
public class DateTime extends PerlModuleBase {

    private static final int SECONDS_PER_DAY = 86400;
    
    // Leap seconds table (from DateTime's leap_seconds.h)
    // Each entry: [rd_day, accumulated_leap_seconds]
    // The day BEFORE each entry has 86401 seconds
    private static final long[][] LEAP_SECONDS = {
        {728714, 10},   // 1972-01-01
        {728896, 11},   // 1972-07-01
        {729261, 12},   // 1973-01-01
        {729627, 13},   // 1974-01-01
        {729992, 14},   // 1975-01-01
        {730357, 15},   // 1976-01-01
        {730723, 16},   // 1977-01-01
        {731088, 17},   // 1978-01-01
        {731453, 18},   // 1979-01-01
        {731819, 19},   // 1980-01-01
        {732184, 20},   // 1981-07-01
        {732549, 21},   // 1982-07-01
        {732915, 22},   // 1983-07-01
        {733645, 23},   // 1985-07-01
        {734011, 24},   // 1988-01-01
        {734741, 25},   // 1990-01-01
        {735107, 26},   // 1991-01-01
        {735473, 27},   // 1992-07-01
        {735838, 28},   // 1993-07-01
        {736204, 29},   // 1994-07-01
        {736935, 30},   // 1996-01-01
        {737301, 31},   // 1997-07-01
        {737666, 32},   // 1999-01-01
        {739396, 33},   // 2006-01-01
        {740214, 34},   // 2009-01-01
        {741124, 35},   // 2012-07-01
        {741849, 36},   // 2015-07-01
        {742582, 37},   // 2017-01-01
    };

    public DateTime() {
        super("DateTime", false);
    }

    public static void initialize() {
        DateTime module = new DateTime();
        try {
            module.registerMethod("_rd2ymd", null);
            module.registerMethod("_ymd2rd", null);
            module.registerMethod("_time_as_seconds", null);
            module.registerMethod("_seconds_as_components", null);
            module.registerMethod("_normalize_tai_seconds", null);
            module.registerMethod("_normalize_leap_seconds", null);
            module.registerMethod("_is_leap_year", null);
            module.registerMethod("_day_length", null);
            module.registerMethod("_day_has_leap_second", null);
            module.registerMethod("_accumulated_leap_seconds", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing DateTime method: " + e.getMessage());
        }
    }

    /**
     * _is_leap_year(self, year)
     * Uses java.time.Year.isLeap() for accurate leap year calculation.
     */
    public static RuntimeList _is_leap_year(RuntimeArray args, int ctx) {
        long year = args.get(1).getLong();
        return new RuntimeScalar(Year.isLeap(year) ? 1 : 0).getList();
    }

    /**
     * _rd2ymd(self, rd_days, extra)
     * Convert Rata Die days to year/month/day using java.time.JulianFields.RATA_DIE.
     */
    public static RuntimeList _rd2ymd(RuntimeArray args, int ctx) {
        long rdDays = args.get(1).getLong();
        int extra = args.size() > 2 ? args.get(2).getInt() : 0;
        
        // Use Java's built-in Rata Die support
        LocalDate date = LocalDate.MIN.with(JulianFields.RATA_DIE, rdDays);
        
        int year = date.getYear();
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();
        
        RuntimeList result = new RuntimeList();
        result.add(new RuntimeScalar(year));
        result.add(new RuntimeScalar(month));
        result.add(new RuntimeScalar(day));
        
        if (extra != 0) {
            int dow = date.getDayOfWeek().getValue();  // 1=Monday to 7=Sunday
            int doy = date.getDayOfYear();
            int quarter = (month - 1) / 3 + 1;
            
            // Day of quarter calculation
            int quarterStartMonth = (quarter - 1) * 3 + 1;
            LocalDate quarterStart = LocalDate.of(year, quarterStartMonth, 1);
            int doq = (int) (date.toEpochDay() - quarterStart.toEpochDay()) + 1;
            
            result.add(new RuntimeScalar(dow));
            result.add(new RuntimeScalar(doy));
            result.add(new RuntimeScalar(quarter));
            result.add(new RuntimeScalar(doq));
        }
        
        return result;
    }

    /**
     * _ymd2rd(self, year, month, day)
     * Convert year/month/day to Rata Die days using java.time.JulianFields.RATA_DIE.
     */
    public static RuntimeList _ymd2rd(RuntimeArray args, int ctx) {
        int year = args.get(1).getInt();
        int month = args.get(2).getInt();
        int day = args.get(3).getInt();
        
        // Handle month overflow/underflow (DateTime allows month > 12 or < 1)
        while (month > 12) {
            year++;
            month -= 12;
        }
        while (month < 1) {
            year--;
            month += 12;
        }
        
        // Clamp day to valid range for the month
        LocalDate date = LocalDate.of(year, month, 1);
        int maxDay = date.lengthOfMonth();
        if (day > maxDay) day = maxDay;
        if (day < 1) day = 1;
        
        date = LocalDate.of(year, month, day);
        long rd = date.getLong(JulianFields.RATA_DIE);
        
        return new RuntimeScalar(rd).getList();
    }

    /**
     * _time_as_seconds(self, hour, minute, second)
     */
    public static RuntimeList _time_as_seconds(RuntimeArray args, int ctx) {
        long h = args.get(1).getLong();
        long m = args.get(2).getLong();
        long s = args.get(3).getLong();
        return new RuntimeScalar(h * 3600 + m * 60 + s).getList();
    }

    /**
     * _seconds_as_components(self, secs, utc_secs, secs_modifier)
     */
    public static RuntimeList _seconds_as_components(RuntimeArray args, int ctx) {
        long secs = args.get(1).getLong();
        long utcSecs = args.size() > 2 ? args.get(2).getLong() : 0;
        long secsModifier = args.size() > 3 ? args.get(3).getLong() : 0;
        
        secs -= secsModifier;
        
        long h = secs / 3600;
        secs -= h * 3600;
        long m = secs / 60;
        long s = secs - (m * 60);
        
        // Handle leap second (utc_secs >= 86400)
        if (utcSecs >= SECONDS_PER_DAY) {
            if (utcSecs >= SECONDS_PER_DAY + 2) {
                throw new RuntimeException("Invalid UTC RD seconds value: " + utcSecs);
            }
            s += (utcSecs - SECONDS_PER_DAY) + 60;
            m = 59;
            h--;
            if (h < 0) {
                h = 23;
            }
        }
        
        RuntimeList result = new RuntimeList();
        result.add(new RuntimeScalar(h));
        result.add(new RuntimeScalar(m));
        result.add(new RuntimeScalar(s));
        return result;
    }

    /**
     * _normalize_tai_seconds(self, days_ref, secs_ref)
     * Normalizes seconds to be within 0..86399, adjusting days accordingly.
     * Modifies the referenced scalars in place.
     */
    public static RuntimeList _normalize_tai_seconds(RuntimeArray args, int ctx) {
        RuntimeScalar daysRef = args.get(1);
        RuntimeScalar secsRef = args.get(2);
        
        long d = daysRef.getLong();
        long s = secsRef.getLong();
        
        // Check for infinity
        if (Double.isInfinite(d) || Double.isInfinite(s)) {
            return new RuntimeList();
        }
        
        long adj;
        if (s < 0) {
            adj = (s - (SECONDS_PER_DAY - 1)) / SECONDS_PER_DAY;
        } else {
            adj = s / SECONDS_PER_DAY;
        }
        
        d += adj;
        s -= adj * SECONDS_PER_DAY;
        
        // Modify in place
        daysRef.set(d);
        secsRef.set(s);
        
        return new RuntimeList();
    }

    /**
     * Get accumulated leap seconds for a given RD day.
     */
    private static long getAccumulatedLeapSeconds(long rdDay) {
        long leapSecs = 0;
        for (long[] entry : LEAP_SECONDS) {
            if (rdDay >= entry[0]) {
                leapSecs = entry[1];
            } else {
                break;
            }
        }
        return leapSecs;
    }

    /**
     * Get day length (86400 or 86401 for leap second days).
     */
    private static long getDayLength(long rdDay) {
        for (long[] entry : LEAP_SECONDS) {
            if (entry[0] == rdDay + 1) {
                // Day before a leap second insertion has 86401 seconds
                return 86401;
            }
        }
        return 86400;
    }

    /**
     * _normalize_leap_seconds(self, days_ref, secs_ref)
     */
    public static RuntimeList _normalize_leap_seconds(RuntimeArray args, int ctx) {
        RuntimeScalar daysRef = args.get(1);
        RuntimeScalar secsRef = args.get(2);
        
        long d = daysRef.getLong();
        long s = secsRef.getLong();
        
        if (Double.isInfinite(d) || Double.isInfinite(s)) {
            return new RuntimeList();
        }
        
        long dayLength;
        while (s < 0) {
            dayLength = getDayLength(d - 1);
            s += dayLength;
            d--;
        }
        
        dayLength = getDayLength(d);
        while (s > dayLength - 1) {
            s -= dayLength;
            d++;
            dayLength = getDayLength(d);
        }
        
        daysRef.set(d);
        secsRef.set(s);
        
        return new RuntimeList();
    }

    /**
     * _day_length(self, utc_rd)
     */
    public static RuntimeList _day_length(RuntimeArray args, int ctx) {
        long utcRd = args.get(1).getLong();
        return new RuntimeScalar(getDayLength(utcRd)).getList();
    }

    /**
     * _day_has_leap_second(self, utc_rd)
     */
    public static RuntimeList _day_has_leap_second(RuntimeArray args, int ctx) {
        long utcRd = args.get(1).getLong();
        return new RuntimeScalar(getDayLength(utcRd) > 86400 ? 1 : 0).getList();
    }

    /**
     * _accumulated_leap_seconds(self, utc_rd)
     */
    public static RuntimeList _accumulated_leap_seconds(RuntimeArray args, int ctx) {
        long utcRd = args.get(1).getLong();
        return new RuntimeScalar(getAccumulatedLeapSeconds(utcRd)).getList();
    }
}
```

#### Advantages of Using java.time

1. **Built-in Rata Die**: `JulianFields.RATA_DIE` matches DateTime's internal format exactly
2. **Accurate leap year**: `Year.isLeap()` handles proleptic Gregorian correctly
3. **Day of week/year**: Built-in methods, no manual calculation needed
4. **Immutable & thread-safe**: Java's date classes are designed for concurrency
5. **Handles edge cases**: Negative years, date normalization, etc.

#### Still Custom (leap seconds)

Java's `java.time` uses UTC-SLS (smoothed leap seconds), so we maintain our own table for:
- `_day_length()` - Returns 86401 for days with leap seconds
- `_normalize_leap_seconds()` - Proper leap second boundary handling  
- `_accumulated_leap_seconds()` - Total leap seconds since 1972

**Files to create**:
- `src/main/java/org/perlonjava/runtime/perlmodule/DateTime.java`

---

### Phase 4: Testing

#### Test 1: XSLoader Fallback Message
```perl
# test_xsloader_fallback.t
use Test::More tests => 2;

# Test that XSLoader dies with compatible message
eval {
    package TestModule;
    require XSLoader;
    XSLoader::load('NonExistent::Module', '1.00');
};
like($@, qr/loadable object/, 'XSLoader error matches fallback pattern');

# Test DateTime fallback
eval { require DateTime; };
is($@, '', 'DateTime loads without error');
```

#### Test 2: DateTime Pure Perl Fallback
```perl
# test_datetime_pp.t
use Test::More tests => 5;

use DateTime;

ok($DateTime::IsPurePerl, 'DateTime using pure Perl');

my $dt = DateTime->new(year => 2024, month => 3, day => 15);
is($dt->year, 2024, 'year correct');
is($dt->month, 3, 'month correct');
is($dt->day, 15, 'day correct');

my $now = DateTime->now;
ok($now->year >= 2024, 'now() works');
```

#### Test 3: DateTime Java XS (when implemented)
```perl
# test_datetime_xs.t
use Test::More;

BEGIN {
    # Force XS if available
    delete $ENV{PERL_DATETIME_PP};
}

use DateTime;

if ($DateTime::IsPurePerl) {
    plan skip_all => 'Java XS not available';
} else {
    plan tests => 10;
}

# Test all XS functions
my $dt = DateTime->new(year => 2024, month => 3, day => 15, 
                       hour => 12, minute => 30, second => 45);

is($dt->year, 2024, '_rd2ymd: year');
is($dt->month, 3, '_rd2ymd: month');
is($dt->day, 15, '_rd2ymd: day');
is($dt->hour, 12, '_seconds_as_components: hour');
is($dt->minute, 30, '_seconds_as_components: minute');
is($dt->second, 45, '_seconds_as_components: second');

# Test leap year
ok(!DateTime->new(year => 2023)->is_leap_year, '2023 not leap year');
ok(DateTime->new(year => 2024)->is_leap_year, '2024 is leap year');

# Test day of week
is($dt->day_of_week, 5, 'Friday');  # 2024-03-15 is Friday

# Test ymd2rd and rd2ymd roundtrip
my $dt2 = DateTime->from_epoch(epoch => $dt->epoch);
is($dt2->ymd, '2024-03-15', 'roundtrip works');
```

#### Test 4: MakeMaker XS Detection
```bash
# Create test module with XS and PP fallback
mkdir -p /tmp/Test-XSFallback/lib/Test/XSFallback
cat > /tmp/Test-XSFallback/lib/Test/XSFallback.pm << 'EOF'
package Test::XSFallback;
use strict;
our $VERSION = '1.00';
our $IsPurePerl;

eval {
    require XSLoader;
    XSLoader::load('Test::XSFallback', $VERSION);
    $IsPurePerl = 0;
};
if ($@) {
    require Test::XSFallback::PP;
    $IsPurePerl = 1;
}
1;
EOF

cat > /tmp/Test-XSFallback/lib/Test/XSFallback/PP.pm << 'EOF'
package Test::XSFallback::PP;
1;
EOF

cat > /tmp/Test-XSFallback/XSFallback.xs << 'EOF'
/* stub */
EOF

cat > /tmp/Test-XSFallback/Makefile.PL << 'EOF'
use ExtUtils::MakeMaker;
WriteMakefile(NAME => 'Test::XSFallback', VERSION_FROM => 'lib/Test/XSFallback.pm');
EOF

# Test
cd /tmp/Test-XSFallback
jperl Makefile.PL
# Should say: "XS MODULE WITH PURE PERL FALLBACK" and install .pm files
```

---

## Configuration

### Environment Variables

| Variable | Description |
|----------|-------------|
| `PERL_DATETIME_PP` | Force DateTime pure Perl (standard) |
| `PERLONJAVA_PREFER_PP` | Prefer pure Perl over Java XS |
| `PERLONJAVA_XS_DEBUG` | Debug XS loading |

### Future: Registry File

Consider a registry file listing known XS modules with fallbacks:

```yaml
# ~/.perlonjava/xs_fallbacks.yml
modules:
  DateTime:
    fallback: DateTime::PP
    java_xs: true
  JSON::XS:
    fallback: JSON::PP
    java_xs: false
  List::Util:
    fallback: built-in
    java_xs: true
```

---

## Related Documents

- `xsloader.md` - XSLoader architecture
- `makemaker_perlonjava.md` - MakeMaker implementation
- `cpan_client.md` - CPAN client support
- `.cognition/skills/port-cpan-module/` - Module porting skill

---

## Progress Tracking

### Current Status: Phase 6 pending - Fix `use constant` / `our $VAR` clash

### Completed Phases

- [x] **Phase 1: XSLoader Compatibility** (2026-03-19)
  - Modified XSLoader.java error message to match `/loadable object/` pattern
  - Enables modules like DateTime to use their built-in PP fallback
  - File: `src/main/java/org/perlonjava/runtime/perlmodule/XSLoader.java`

- [x] **Phase 2: MakeMaker XS Handling** (2026-03-19)
  - Simplified to always install .pm files for XS modules
  - Prints warning that XS cannot be compiled
  - Runtime decides: Java XS → PP fallback → error
  - File: `src/main/perl/lib/ExtUtils/MakeMaker.pm`

- [x] **Phase 3: DateTime Java XS** (2026-03-19)
  - Created DateTime.java with all 10 XS functions
  - Uses java.time.JulianFields.RATA_DIE for Rata Die calculations
  - Uses java.time.Year.isLeap() for leap year checking
  - Custom leap seconds table for _day_length, _normalize_leap_seconds
  - File: `src/main/java/org/perlonjava/runtime/perlmodule/DateTime.java`

- [x] **Phase 4: Testing** (2026-03-19)
  - Verified XSLoader error matches fallback pattern
  - Verified DateTime Java XS loads and functions correctly
  - All unit tests pass

### Phase 5: Preserve JAR Shims During CPAN XS Module Installation (2026-04-04) — COMPLETED

#### Problem

When `jcpan` installs an XS CPAN module, it copies ALL `.pm` files from the
distribution into `~/.perlonjava/lib/`.  This includes XS bootstrap `.pm` files
(e.g. `Template/Stash/XS.pm`) that call `XSLoader::load` at the top level.

PerlOnJava ships purpose-built shims for some of these modules inside the JAR
(`jar:PERL5LIB`), for example `Template/Stash/XS.pm` which gracefully inherits
from the pure-Perl `Template::Stash`.  Because `~/.perlonjava/lib/` appears
**before** `jar:PERL5LIB` in `@INC`, the CPAN-installed version shadows the
shim, and loading the module dies with:

```
Can't load loadable object for module Template::Stash::XS:
  no Java XS implementation available
```

This was discovered while investigating `./jcpan --jobs 8 -t Template` failures
(Template Toolkit 3.102).  The same issue would affect any CPAN XS module that
has a bundled PerlOnJava shim in the JAR.

#### Root Cause

```
@INC order:
  1. ~/.perlonjava/lib/       ← CPAN-installed (has broken XS bootstrap .pm)
  2. jar:PERL5LIB             ← bundled shims  (has working pure-Perl fallback)
```

The CPAN `Template/Stash/XS.pm` does:
```perl
use XSLoader;
XSLoader::load 'Template::Stash::XS', $Template::VERSION;  # dies
```

The JAR shim does:
```perl
use Template::Stash;
our @ISA = ('Template::Stash');  # works
```

#### Fix

Modified `_handle_xs_module()` in `ExtUtils/MakeMaker.pm` to skip installing
`.pm` files that already have a PerlOnJava shim in `jar:PERL5LIB`.  The check
uses `-f "jar:PERL5LIB/$rel_path"` (PerlOnJava supports file-test operators on
`jar:` paths).

Only XS modules go through `_handle_xs_module`, so pure-Perl CPAN modules are
unaffected.  For XS modules, the JAR shim is always the correct version for
PerlOnJava -- either it provides a pure-Perl fallback or it delegates to a Java
XS implementation via `XSLoader::load`.

#### Files Modified

- `src/main/perl/lib/ExtUtils/MakeMaker.pm` — `_handle_xs_module()` now
  filters `%pm` before passing to `_install_pure_perl()`

#### Related: Template Toolkit `use constant` Bug

During the same investigation, a separate PerlOnJava runtime bug was found:
`use constant ERROR => 2; our $ERROR = "";` in the same package causes
"Modification of a read-only value attempted".  This is because PerlOnJava's
`RuntimeStashEntry.set()` (line 120) incorrectly stores the read-only constant
value into the scalar glob slot (`$ERROR`), not just the code slot (`&ERROR`).

In Perl 5, `use constant` only creates a constant subroutine; it never touches
the scalar variable of the same name.  This bug blocks `Template::Parser` from
loading (and thus most Template Toolkit tests).

**Root cause location:** `RuntimeStashEntry.java` line 120:
```java
GlobalVariable.globalVariables.put(this.globName, deref);  // BUG: sets $ERROR
```

This is tracked separately and not fixed by the MakeMaker change.

### Phase 6: Fix `use constant` / `our $VAR` Clash in RuntimeStashEntry.java

#### Problem

`use constant ERROR => 2; our $ERROR = "";` in the same package causes
"Modification of a read-only value attempted".  This blocks `Template::Parser`
from loading (and thus most Template Toolkit tests).

**Minimal repro:**
```perl
./jperl -e 'package Foo; use constant ERROR => 2; our $ERROR = "hello"; print "OK\n"'
# dies: Modification of a read-only value attempted
```

In Perl 5, `use constant` only creates a constant subroutine (`&ERROR`); it
never touches the scalar variable `$ERROR`.  They coexist in independent glob
slots.

#### Root Cause Analysis

The bug is a single line in `RuntimeStashEntry.set()` (line 120):

```java
// Default: scalar slot + constant subroutine for bareword access
GlobalVariable.globalVariables.put(this.globName, deref);   // BUG
```

**What happens step by step:**

1. `use constant ERROR => 2` goes through `constant.pm`'s `_CAN_PCS` path:
   - Creates a scalar with value 2
   - Calls `Internals::SvREADONLY($scalar, 1)` — marks it `READONLY_SCALAR`
   - Does `$symtab->{ERROR} = \$scalar` — stash assignment

2. The stash assignment dispatches to `RuntimeStashEntry.set(RuntimeScalar value)`
   which enters the `REFERENCE` branch (line 90), then the default else-branch
   (line 118) for plain scalar references:
   - **Line 120**: `GlobalVariable.globalVariables.put(this.globName, deref)` —
     **REPLACES** the `$ERROR` scalar variable with the read-only constant value
   - Lines 122-125: Creates a constant subroutine `&ERROR` (correct)

3. Later, `our $ERROR = ""` calls `getGlobalVariable("Foo::ERROR")` which returns
   the **same read-only object** that was put in the map at step 2.  Attempting
   to `set()` it throws "Modification of a read-only value attempted".

**In Perl 5**, `$stash{name} = \$scalar` only creates a constant subroutine in
the CODE slot — it never writes to the SCALAR slot.  `$ERROR` and `&ERROR` are
completely independent glob slots.

#### Fix

**Remove line 120** from `RuntimeStashEntry.java`.  The constant subroutine
creation (lines 122-125) is correct and must remain; only the scalar-slot
overwrite is wrong.

**Before:**
```java
} else {
    // Default: scalar slot + constant subroutine for bareword access
    GlobalVariable.globalVariables.put(this.globName, deref);

    RuntimeCode code = new RuntimeCode("", null);
    code.constantValue = deref.getList();
    GlobalVariable.defineGlobalCodeRef(this.globName).set(
            new RuntimeScalar(code));
}
```

**After:**
```java
} else {
    // Default: constant subroutine for bareword access
    // NOTE: Do NOT set the scalar slot here.  In Perl 5, stash assignment
    // of a scalar reference ($stash{name} = \$scalar) only creates a
    // constant sub (&name); it never touches the scalar variable ($name).
    // Setting the scalar slot would cause "Modification of a read-only
    // value attempted" when both `use constant FOO => ...` and `our $FOO`
    // exist in the same package.
    RuntimeCode code = new RuntimeCode("", null);
    code.constantValue = deref.getList();
    GlobalVariable.defineGlobalCodeRef(this.globName).set(
            new RuntimeScalar(code));
}
```

#### Why This Is Safe

1. **`use constant` still works**: The constant subroutine is created via
   `defineGlobalCodeRef` (lines 122-125).  Bare `FOO` and `FOO()` will still
   return the constant value.

2. **`our $FOO` is independent**: `getGlobalVariable("Pkg::FOO")` creates/returns
   its own `RuntimeScalar`.  Without line 120 overwriting it, the scalar variable
   is a normal writable scalar — exactly like Perl 5.

3. **No other callers depend on this**: The scalar slot write at line 120 is not
   expected by `constant.pm` or any other code.  The constant sub is the only
   visible effect.

4. **Order doesn't matter**: Whether `use constant` or `our` comes first, each
   touches only its own slot (CODE vs SCALAR).

#### Files to Modify

| File | Change |
|------|--------|
| `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeStashEntry.java` | Remove `GlobalVariable.globalVariables.put(this.globName, deref)` at line 120 |
| `src/test/resources/unit/constant.t` | Add tests for `use constant` + `our $VAR` coexistence |

#### Test Plan

1. **New unit test** (add to `constant.t`):
   ```perl
   # use constant and our $VAR must coexist independently
   {
       package ConstOurTest;
       use constant STATUS_OK => 0;
       use constant STATUS_ERROR => 2;
       our $STATUS_OK = "all good";
       our $STATUS_ERROR = "something failed";

       print "ok" if STATUS_OK == 0;
       print " - use constant STATUS_OK returns 0\n";
       print "ok" if STATUS_ERROR == 2;
       print " - use constant STATUS_ERROR returns 2\n";
       print "ok" if $STATUS_OK eq "all good";
       print " - our \$STATUS_OK is writable and correct\n";
       print "ok" if $STATUS_ERROR eq "something failed";
       print " - our \$STATUS_ERROR is writable and correct\n";
   }
   ```

2. **Verify Template::Parser loads**:
   ```bash
   ./jperl -e 'use Template::Parser; print "OK\n"'
   ```

3. **Regression check**:
   ```bash
   make   # all unit tests must pass
   ```

4. **Template Toolkit retest**:
   ```bash
   ./jcpan --jobs 8 -t Template   # expect significant improvement
   ```

#### Expected Impact on Template Toolkit

Template::Parser uses both `use constant ERROR => 2` and `our $ERROR = ''`.
With this fix:

- `Template::Parser` will load correctly
- `Template::Constants` status constants will work
- All tests that `use Template::Parser` should unblock:
  args.t, binop.t, filter.t, list.t, debug.t, constants.t, vars.t, while.t,
  stop.t, fileline.t, outline_line.t, parser.t, parser2.t (and more)

### Next Steps (after Phase 6)

- [ ] Implement Phase 6 fix and verify
- [ ] Test with actual CPAN DateTime installation via jcpan
- [ ] Add more Java XS implementations for other common modules (JSON::XS, List::Util, etc.)
- [ ] Update user documentation

### Dependencies

DateTime depends on:
- `DateTime::Locale` (pure Perl)
- `DateTime::TimeZone` (pure Perl)
- `Params::ValidationCompiler` (pure Perl)
- `Specio` (pure Perl)
- `Try::Tiny` (pure Perl)
- `namespace::autoclean` (pure Perl)

All dependencies are pure Perl and should install via jcpan.

### Notes

- DateTime's pure Perl is about 2-3x slower than XS for date calculations
- Java XS should be comparable to C XS performance
- Leap seconds table needs periodic updates (last: 2017-01-01)

# Pure Perl Exporter Implementation

## Overview

Replace the Java implementation of Exporter with the pure Perl version from Perl 5 core.

## Status: Complete (2024-03-21)

### Completed

1. **Removed Java Exporter.java** - Deleted `src/main/java/org/perlonjava/runtime/perlmodule/Exporter.java`

2. **Added pure Perl Exporter** - Added to `dev/import-perl5/config.yaml`:
   - `perl5/dist/Exporter/lib/Exporter.pm` → `src/main/perl/lib/Exporter.pm`
   - `perl5/dist/Exporter/lib/Exporter/Heavy.pm` → `src/main/perl/lib/Exporter/Heavy.pm`

3. **Updated PerlModuleBase.java** - Added generic `inheritFrom(String parentModule)` method:
   - Requires the parent module if not already loaded
   - Adds parent to `@ISA`
   - `initializeExporter()` now calls `inheritFrom("Exporter")`

4. **Removed GlobalContext preload** - Exporter is loaded on-demand by `inheritFrom()`

5. **Fixed builtin override detection** - Updated `ParsePrimary.java` to check if subroutine exists (not just `isSubs` flag)

### How It Works

Java modules that need Exporter functionality:
1. Call `initializeExporter()` which calls `inheritFrom("Exporter")`
2. `inheritFrom()` does `require Exporter.pm` if not loaded
3. Adds `Exporter` to the module's `@ISA`
4. Module inherits `Exporter::import` from pure Perl

Perl modules (shims) that wrap Java implementations:
- Use `use Exporter 'import'` to get the import method
- Define `@EXPORT_OK` with exportable symbols
- Use `XSLoader::load()` to load the Java implementation

Example (Time/HiRes.pm):
```perl
package Time::HiRes;
use Exporter 'import';
our @EXPORT_OK = qw(time sleep alarm usleep nanosleep gettimeofday);
require XSLoader;
XSLoader::load('Time::HiRes');
```

## Builtin Override Detection

### Problem Solved

When importing a function that shadows a builtin (e.g., `use Time::HiRes 'time'`), the parser needs to know at compile time to treat `time` as a subroutine call, not the builtin operator.

### Solution

The parser now checks both:
1. `GlobalVariable.isSubs` - for explicit `use subs` declarations
2. `existsGlobalCodeRef(fullName)` - for subroutines that exist at parse time

Since `use` statements run at BEGIN time (before subsequent code is parsed), imported subs exist when the parser encounters calls to them.

### How It Works in Perl

Key insight from testing with system Perl: the glob assignment must happen from code compiled in a **different package**. This is why Exporter works naturally - it runs in package `Exporter` and assigns to the caller's namespace (e.g., `main::time`).

The parser checks if the sub exists at parse time. Since imports happen at BEGIN time before subsequent code is parsed, the imported sub is visible to the parser.

### Overridable Builtins

From `ParserTables.OVERRIDABLE_OP`:
- `caller`, `chdir`, `close`, `connect`
- `die`, `do`
- `exit`, `fork`
- `getpwuid`, `glob`, `hex`
- `kill`, `oct`, `open`
- `readline`, `readpipe`, `rename`, `require`
- `stat`, `time`, `uc`, `warn`

## Files Changed

- `src/main/java/org/perlonjava/runtime/perlmodule/Exporter.java` - DELETED
- `src/main/java/org/perlonjava/runtime/perlmodule/PerlModuleBase.java` - Added `inheritFrom()`
- `src/main/java/org/perlonjava/runtime/runtimetypes/GlobalContext.java` - Removed Exporter.initialize()
- `src/main/java/org/perlonjava/frontend/parser/ParsePrimary.java` - Check existsGlobalCodeRef for overrides
- `dev/import-perl5/config.yaml` - Added Exporter.pm and Heavy.pm
- `src/main/perl/lib/Exporter.pm` - NEW (from perl5)
- `src/main/perl/lib/Exporter/Heavy.pm` - NEW (from perl5)

## Test Status

- Basic exports work: `use File::Basename qw(dirname)` ✓
- Tag exports work: `use Carp qw(:DEFAULT)` ✓
- XSLoader modules work: `use Time::HiRes` ✓
- Builtin override works: `use Time::HiRes 'time'; print time` ✓
- All unit tests pass ✓

## Related

- `src/main/perl/lib/Time/HiRes.pm` - Shim pattern example
- `src/main/java/org/perlonjava/frontend/parser/ParserTables.java` - OVERRIDABLE_OP list

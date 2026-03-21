# Pure Perl Exporter Implementation

## Overview

Replace the Java implementation of Exporter with the pure Perl version from Perl 5 core.

## Status: Phase 1 Complete (2024-03-21)

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

## Known Issue: Lexical Override of Builtins

### Problem

When importing a function that shadows a builtin (e.g., `use Time::HiRes 'time'`), the parser needs to know at compile time to treat `time` as a subroutine call, not the builtin operator.

The old Java Exporter had:
```java
if (ParserTables.OVERRIDABLE_OP.contains(functionName)) {
    GlobalVariable.isSubs.put(fullName, true);
}
```

This marked the function globally, but Perl's behavior is lexical - the override should only affect the current compilation unit.

### Current Behavior

```perl
use Time::HiRes 'time';
my $t = time;      # Returns integer (builtin) - WRONG
my $t = time();    # Returns integer (builtin) - WRONG  
my $t = &time;     # Returns fractional seconds - correct
my $t = &time();   # Returns fractional seconds - correct
```

### Affected Builtins

From `ParserTables.OVERRIDABLE_OP`:
- `caller`, `chdir`, `close`, `connect`
- `die`, `do`
- `exit`, `fork`
- `getpwuid`, `glob`, `hex`
- `kill`, `oct`, `open`
- `readline`, `readpipe`, `rename`, `require`
- `stat`, `time`, `uc`, `warn`

## Phase 2: Fix Lexical Override (TODO)

### Requirements

1. Parser must track which builtins are overridden in current lexical scope
2. Override should happen when `use Module 'func'` is executed (BEGIN time)
3. Override should only affect subsequent code in same compilation unit
4. Different files should have independent override states

### Possible Approaches

#### Approach A: Lexical Hints Hash

Similar to how `strict` and `warnings` work:
- Store override info in `%^H` (hints hash)
- Parser checks hints during compilation
- Hints are lexically scoped

#### Approach B: Per-Compilation-Unit Tracking

- Track overrides per compilation unit ID
- Parser checks compilation unit's override set
- Clean up when compilation unit finishes

#### Approach C: Hook in Exporter

- Add a Java hook `Internals::mark_lexical_override($func, $pkg)`
- Exporter.pm calls it when importing overridable functions
- Parser uses current compilation context to check

### Implementation Notes

- The fix requires changes to both:
  - Runtime (to record override at import time)
  - Parser (to check override at parse time)
- Need to understand how PerlOnJava tracks compilation units/lexical scopes
- May need to extend `%^H` support if not already complete

## Files Changed

- `src/main/java/org/perlonjava/runtime/perlmodule/Exporter.java` - DELETED
- `src/main/java/org/perlonjava/runtime/perlmodule/PerlModuleBase.java` - Added `inheritFrom()`
- `src/main/java/org/perlonjava/runtime/runtimetypes/GlobalContext.java` - Removed Exporter.initialize()
- `dev/import-perl5/config.yaml` - Added Exporter.pm and Heavy.pm
- `src/main/perl/lib/Exporter.pm` - NEW (from perl5)
- `src/main/perl/lib/Exporter/Heavy.pm` - NEW (from perl5)

## Test Status

- Basic exports work: `use File::Basename qw(dirname)` ✓
- Tag exports work: `use Carp qw(:DEFAULT)` ✓
- XSLoader modules work: `use Time::HiRes` (without importing `time`) ✓
- **FAILING**: `use Time::HiRes 'time'` - lexical override not working

## Related

- `src/main/perl/lib/Time/HiRes.pm` - Shim pattern example
- `src/main/java/org/perlonjava/frontend/parser/ParserTables.java` - OVERRIDABLE_OP list

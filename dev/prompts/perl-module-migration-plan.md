# Perl Module Migration Plan: perl5/ → PerlOnJava

## Executive Summary

This document outlines a strategy for migrating standard Perl modules from the `perl5/` directory (standard Perl git clone) to PerlOnJava's module structure, ensuring proper organization, testing, and compatibility.

## Current PerlOnJava Module Structure

### 1. Pure Perl Modules
**Location:** `src/main/perl/lib/`
- Contains `.pm` files that are pure Perl implementations
- Examples: `Benchmark.pm`, `overload.pm`, `charnames.pm`, `feature.pm`
- **Current count:** ~50 modules + subdirectories

### 2. Java-Backed Modules
**Location:** `src/main/java/org/perlonjava/perlmodule/`
- Contains `.java` files that provide native implementations
- Examples: `Carp.java`, `Exporter.java`, `ListUtil.java`, `MathBigInt.java`
- **Current count:** ~50 Java modules
- **Pattern:** Java classes extend `PerlModuleBase` and implement module functionality

### 3. Test Files
**Location:** `src/test/resources/`
- Contains `.t` test files for testing
- **Current structure:** Mixed PerlOnJava and standard Perl tests
- **Planned structure:** Separate subdirectories (see Test Directory Reorganization)
  - `unit/` - PerlOnJava-specific tests
  - `lib/`, `ext/`, `dist/`, `cpan/` - Standard Perl tests
- **Current count:** ~100+ test files

## Standard Perl Module Structure (perl5/)

### Module Locations in perl5/

1. **lib/** - Core Perl modules
   - `lib/Benchmark.pm`, `lib/English.pm`, `lib/Symbol.pm`, etc.
   - Tests: `lib/Benchmark.t`, `lib/English.t` (co-located)

2. **ext/** - Extensions (often with XS components)
   - `ext/File-Find/lib/File/Find.pm`
   - `ext/Hash-Util/lib/Hash/Util.pm`
   - Tests: `ext/*/t/*.t`

3. **dist/** - Dual-life distributions
   - `dist/Cwd/lib/Cwd.pm`
   - `dist/Storable/Storable.pm`
   - Tests: `dist/*/t/*.t`

4. **cpan/** - CPAN modules bundled with Perl
   - `cpan/Test-Simple/lib/Test/More.pm`
   - `cpan/JSON-PP/lib/JSON/PP.pm`
   - Tests: `cpan/*/t/*.t`

## Prerequisites

### Test Directory Reorganization

**Before starting module migration**, reorganize the test directory structure:

1. **Run reorganization script:**
   ```bash
   ./dev/tools/reorganize_tests.sh
   ```

2. **This will:**
   - Move all current tests to `src/test/resources/unit/`
   - Create `lib/`, `ext/`, `dist/`, `cpan/` directories
   - Prepare structure for standard Perl tests

3. **See:** `dev/prompts/test-directory-reorganization.md` for details

**Why this matters:** Separates PerlOnJava-specific tests from standard Perl module tests, preventing confusion and enabling clean integration.

## Migration Strategy

### Phase 1: Inventory and Assessment

#### 1.1 Create Module Inventory
Create a script to scan perl5/ and catalog:
- All `.pm` files in `lib/`, `ext/`, `dist/`, `cpan/`
- Their dependencies (use statements)
- Associated test files
- XS/C dependencies (if any)

**Output:** `module_inventory.csv`
```csv
Module,Location,HasTests,HasXS,Dependencies,Priority
Benchmark,lib/Benchmark.pm,Yes,No,"Time::HiRes",High
File::Find,ext/File-Find/lib/File/Find.pm,Yes,No,"Cwd",High
```

#### 1.2 Identify Already Migrated Modules
Compare perl5/ modules with existing PerlOnJava modules:
- Modules in `src/main/perl/lib/` (already migrated)
- Modules with Java implementations in `src/main/java/org/perlonjava/perlmodule/`

**Output:** `migration_status.md`

#### 1.3 Prioritize Modules
Categorize by priority:
- **Critical:** Required by test suite (Carp, Exporter, Test::More)
- **High:** Commonly used utilities (File::*, List::Util, Scalar::Util)
- **Medium:** Standard library modules
- **Low:** Platform-specific or rarely used

### Phase 2: Migration Process

#### 2.1 Pure Perl Modules (No XS)

**Source:** `perl5/lib/*.pm`, `perl5/ext/*/lib/**/*.pm`
**Destination:** `src/main/perl/lib/`

**Process:**
1. **Copy module file:**
   ```bash
   cp perl5/lib/Module.pm src/main/perl/lib/Module.pm
   ```

2. **Check for PerlOnJava compatibility:**
   - Review for XS dependencies
   - Check for features not yet implemented
   - Test basic loading: `./jperl -e 'use Module;'`

3. **Copy associated tests (mirror perl5/ structure exactly):**
   ```bash
   # Single test file
   cp perl5/lib/Benchmark.t src/test/resources/lib/
   
   # Multiple tests co-located
   mkdir -p src/test/resources/lib/B
   cp perl5/lib/B/Deparse*.t src/test/resources/lib/B/
   
   # Tests in t/ subdirectory
   mkdir -p src/test/resources/lib/DBM_Filter/t
   cp perl5/lib/DBM_Filter/t/*.t src/test/resources/lib/DBM_Filter/t/
   
   # Extension tests
   mkdir -p src/test/resources/ext/File-Find/t
   cp perl5/ext/File-Find/t/*.t src/test/resources/ext/File-Find/t/
   ```

4. **Update test runner:**
   - Add new test paths to `perl_test_runner.pl`
   - Or implement automatic test discovery

5. **Verify:**
   ```bash
   ./jperl src/test/resources/lib/Module.t
   ```

#### 2.2 Modules Requiring Java Implementation

**Source:** Modules with XS or requiring native functionality
**Destination:** `src/main/java/org/perlonjava/perlmodule/`

**Process:**
1. **Analyze XS code:**
   - Identify C functions that need Java equivalents
   - Map Perl XS API to PerlOnJava runtime API

2. **Create Java implementation:**
   ```java
   package org.perlonjava.perlmodule;
   
   public class ModuleName extends PerlModuleBase {
       public static void initialize() {
           // Register module
       }
       
       // Implement native functions
   }
   ```

3. **Create Perl wrapper (if needed):**
   ```perl
   # src/main/perl/lib/Module/Name.pm
   package Module::Name;
   use strict;
   use warnings;
   
   # Load Java implementation
   require Java::ModuleName;
   
   1;
   ```

4. **Copy and adapt tests:**
   - May need to skip XS-specific tests
   - Add `SKIP` blocks for unimplemented features

### Phase 3: Directory Structure

#### 3.1 Proposed Structure

```
src/main/perl/lib/          # Pure Perl modules
  ├── Benchmark.pm
  ├── English.pm
  ├── File/
  │   ├── Find.pm
  │   └── Spec.pm
  ├── List/
  │   └── Util.pm          # Wrapper for Java implementation
  └── Test/
      └── More.pm

src/main/java/org/perlonjava/perlmodule/  # Java implementations
  ├── ListUtil.java
  ├── ScalarUtil.java
  └── FileSpec.java

src/test/resources/        # Test files
  ├── unit/                # PerlOnJava-specific tests
  │   ├── array.t
  │   ├── hash.t
  │   └── regex/
  ├── lib/                 # Tests for lib/ modules
  │   ├── Benchmark.t
  │   └── English.t
  └── ext/                 # Tests for ext/ modules
      └── File-Find/
          └── t/
              └── find.t
```

#### 3.2 Alternative: Mirror perl5/ Structure

```
src/main/perl/
  ├── lib/                 # From perl5/lib/
  ├── ext/                 # From perl5/ext/
  │   └── File-Find/
  │       └── lib/File/Find.pm
  └── dist/                # From perl5/dist/
      └── Storable/
          └── lib/Storable.pm

src/test/resources/
  ├── unit/                # PerlOnJava-specific tests
  ├── lib/                 # Tests from perl5/lib/
  ├── ext/                 # Tests from perl5/ext/
  └── dist/                # Tests from perl5/dist/
```

**Pros:** Maintains standard Perl structure, easier to sync with upstream
**Cons:** More complex directory structure

### Phase 4: Test Infrastructure

#### 4.1 Extend Test Runner

Update `perl_test_runner.pl` to discover tests from new locations:

```perl
# Add to test discovery
my @test_dirs = (
    't',                           # Core Perl tests (op/, re/, io/, etc.)
    'src/test/resources/unit',     # PerlOnJava unit tests
    'src/test/resources/lib',      # lib/ module tests
    'src/test/resources/ext',      # ext/ module tests
    'src/test/resources/dist',     # dist/ module tests
    'src/test/resources/cpan',     # cpan/ module tests
);

# Scan for *.t files recursively
for my $dir (@test_dirs) {
    next unless -d $dir;
    find_tests_recursive($dir);
}
```

#### 4.2 Test Execution Context

Ensure proper `@INC` setup for module tests:

```perl
# For lib/ tests
BEGIN {
    unshift @INC, 'src/main/perl/lib';
}

# For ext/ tests
BEGIN {
    unshift @INC, 'src/main/perl/lib';
    unshift @INC, 'src/main/perl/ext/Module-Name/lib';
}
```

### Phase 5: Automation

#### 5.1 Migration Script

Create `scripts/migrate_module.pl`:

```perl
#!/usr/bin/env perl
use strict;
use warnings;

# Usage: ./scripts/migrate_module.pl Module::Name

my $module_name = shift or die "Usage: $0 Module::Name\n";

# 1. Find module in perl5/
# 2. Check for XS dependencies
# 3. Copy to appropriate location
# 4. Copy tests
# 5. Update test runner
# 6. Run tests
# 7. Report results
```

#### 5.2 Sync Script

Create `scripts/sync_from_perl5.sh`:

```bash
#!/bin/bash
# Sync specific modules from perl5/ to PerlOnJava

PERL5_DIR="perl5"
TARGET_DIR="src/main/perl/lib"

# List of modules to sync
MODULES=(
    "Benchmark.pm"
    "English.pm"
    "Symbol.pm"
)

for module in "${MODULES[@]}"; do
    echo "Syncing $module..."
    cp "$PERL5_DIR/lib/$module" "$TARGET_DIR/$module"
done
```

## Module Categories and Migration Priority

### Critical Priority (Immediate)

These modules are required for basic functionality:

1. **Test::More** - Test framework
   - Status: Partially implemented
   - Action: Complete implementation, sync tests

2. **Carp** - Error reporting
   - Status: Java implementation exists
   - Action: Verify compatibility

3. **Exporter** - Module export mechanism
   - Status: Java implementation exists
   - Action: Verify compatibility

### High Priority (Phase 1)

Commonly used utility modules:

1. **File::Find** - Directory traversal
2. **File::Spec** - Path manipulation (has Java impl)
3. **List::Util** - List utilities (has Java impl)
4. **Scalar::Util** - Scalar utilities (has Java impl)
5. **Data::Dumper** - Data serialization
6. **Storable** - Data persistence (has Java impl)

### Medium Priority (Phase 2)

Standard library modules:

1. **English** - Readable variable names
2. **Symbol** - Symbol table manipulation
3. **SelectSaver** - Filehandle selection
4. **FileHandle** - OO filehandle interface
5. **Benchmark** - Performance testing

### Low Priority (Phase 3)

Platform-specific or specialized:

1. **Win32** - Windows-specific
2. **VMS::*** - VMS-specific
3. **OS2::*** - OS/2-specific

## Handling Special Cases

### 1. Modules with XS Components

**Options:**
1. **Pure Perl fallback:** Use if available in perl5/
2. **Java implementation:** Rewrite XS in Java
3. **Skip:** Mark as unimplemented, add to TODO

**Example: Hash::Util**
- Has XS for performance
- Can implement critical functions in Java
- Less critical functions can be pure Perl

### 2. Dual-Life Modules

Modules that exist both in core and on CPAN:

**Strategy:**
- Use core version from perl5/
- Track CPAN version for updates
- Document version in module header

### 3. Deprecated Modules

Some perl5/ modules are deprecated:

**Strategy:**
- Skip deprecated modules
- Document in `UNSUPPORTED_MODULES.md`
- Focus on actively maintained modules

## Version Tracking

### Module Version Management

Track which perl5 version modules came from:

```perl
# In each migrated module, add comment:
# Migrated from perl5 v5.38.0
# Last sync: 2025-10-18
# Source: perl5/lib/Module.pm
```

### Sync Strategy

**Options:**
1. **One-time migration:** Copy once, maintain independently
2. **Periodic sync:** Sync with perl5 releases
3. **Selective sync:** Only sync when needed

**Recommendation:** One-time migration with selective sync for critical modules

## Testing Strategy

### 1. Module Loading Tests

```perl
# test_module_loading.t
use Test::More;

my @modules = qw(
    Benchmark
    English
    Symbol
    File::Find
);

for my $module (@modules) {
    use_ok($module) or BAIL_OUT("Cannot load $module");
}

done_testing();
```

### 2. Compatibility Tests

Run perl5 test suite against PerlOnJava:

```bash
# Run specific module tests
./jperl perl5/lib/Benchmark.t
./jperl perl5/ext/File-Find/t/find.t
```

### 3. Integration Tests

Test modules work together:

```perl
# test_integration.t
use File::Find;
use File::Spec;
use Cwd;

# Test that modules interact correctly
```

## Documentation

### 1. Migration Log

Maintain `MIGRATION_LOG.md`:

```markdown
# Module Migration Log

## 2025-10-18
- Migrated Benchmark.pm from perl5/lib/
- Status: Tests passing (50/50)
- Notes: No modifications needed

## 2025-10-19
- Migrated File::Find from perl5/ext/File-Find/
- Status: Tests passing (38/40)
- Notes: 2 tests skipped (platform-specific)
```

### 2. Module Status

Maintain `MODULE_STATUS.md`:

```markdown
# PerlOnJava Module Status

## Fully Supported
- Benchmark - Pure Perl, all tests pass
- Carp - Java implementation, compatible

## Partially Supported
- File::Find - Pure Perl, 2 tests skipped

## Not Supported
- Win32 - Platform-specific
- XS::APItest - XS testing module
```

### 3. Unsupported Features

Document in `UNSUPPORTED_FEATURES.md`:

```markdown
# Unsupported Perl Features in Modules

## XS Features
- Direct C API access
- Perl internal structure manipulation

## Platform-Specific
- Windows-specific functions
- VMS-specific functions
```

## Implementation Timeline

### Week 1: Setup and Planning
- Create inventory scripts
- Analyze existing modules
- Prioritize migration list
- Set up directory structure

### Week 2-3: Critical Modules
- Migrate Test::More (if needed)
- Verify Carp, Exporter
- Migrate File::Find
- Test infrastructure updates

### Week 4-6: High Priority Modules
- Migrate File::Spec, List::Util, Scalar::Util
- Migrate Data::Dumper
- Run compatibility tests

### Week 7-8: Medium Priority Modules
- Migrate English, Symbol, etc.
- Run full test suite
- Document issues

### Ongoing: Maintenance
- Sync with perl5 updates
- Fix compatibility issues
- Add new modules as needed

## Success Criteria

### Module Migration Success
- ✅ Module loads without errors
- ✅ Basic functionality works
- ✅ At least 80% of tests pass
- ✅ Documented incompatibilities

### Overall Success
- ✅ 20+ core modules migrated
- ✅ Test infrastructure supports module tests
- ✅ Documentation complete
- ✅ Sync process established

## Risk Mitigation

### Risk 1: XS Dependencies
**Mitigation:** Identify early, implement Java alternatives, or skip

### Risk 2: Perl Version Differences
**Mitigation:** Document perl5 version, test thoroughly

### Risk 3: Breaking Changes
**Mitigation:** Maintain compatibility layer, version modules

### Risk 4: Test Failures
**Mitigation:** Document expected failures, skip platform-specific tests

## Conclusion

This migration plan provides a structured approach to bringing standard Perl modules into PerlOnJava. By prioritizing critical modules, establishing clear processes, and maintaining good documentation, we can systematically expand PerlOnJava's module ecosystem while ensuring quality and compatibility.

## Next Steps

1. **Review and approve** this migration plan
2. **Create inventory script** to catalog perl5 modules
3. **Set up directory structure** for migrated modules
4. **Begin with Test::More** as first migration target
5. **Iterate and refine** process based on learnings

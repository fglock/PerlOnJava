# How Standard Perl Executes Module Tests

## Overview
Standard Perl's test infrastructure discovers and runs tests from multiple locations:
1. Core tests in `t/` directory (op/, re/, io/, etc.)
2. Module tests co-located with `.pm` files in `lib/`
3. Extension tests in `ext/*/t/`
4. Distribution tests in `dist/*/t/`
5. CPAN module tests in `cpan/*/t/`

## How `make test` Works

### Execution Flow
```
make test
  → calls: runtests choose
  → executes: cd t && ./perl TEST
  → TEST script discovers and runs all tests
```

### Test Discovery Mechanism

#### 1. Core Tests (Direct Discovery)
The `t/TEST` script directly scans these directories:

```perl
# From perl5/t/TEST lines 562-567
foreach my $dir (qw(base comp run cmd io re opbasic op uni mro class perf test_pl)) {
    _find_tests($dir);  # Recursively finds *.t files
}
unless ($::core) {
    _find_tests('porting');
    _find_tests("lib");
}
```

#### 2. Extension/Distribution Tests (MANIFEST-Based)
For `ext/`, `dist/`, and `cpan/`, tests are discovered from the MANIFEST file:

```perl
# From perl5/t/TEST line 445
if ($file =~ m!^((?:cpan|dist|ext)/(\S+)/+(?:[^/\s]+\.t|test\.pl)|lib/\S+?(?:\.t|test\.pl))\z!) {
    # This file is a test
}
```

This pattern matches:
- `ext/Module-Name/t/*.t` - Extension tests
- `dist/Module-Name/t/*.t` - Distribution tests  
- `cpan/Module-Name/t/*.t` - CPAN module tests
- `lib/**/*.t` - Library module tests
- `**/test.pl` - Alternative test file naming

## Test File Organization Patterns

### Pattern 1: Extension Module Structure
```
ext/File-Find/
  lib/File/Find.pm        # The module
  t/find.t                # Tests in t/ subdirectory
  t/taint.t
  t/correct-absolute-path-with-follow.t
```

### Pattern 2: lib/ Module with Co-located Test
```
lib/Benchmark.pm          # The module
lib/Benchmark.t           # The test (same directory)
```

### Pattern 3: lib/ Module with Test Subdirectory
```
lib/DBM_Filter.pm         # The module
lib/DBM_Filter/t/01error.t
lib/DBM_Filter/t/02core.t
lib/DBM_Filter/t/compress.t
```

### Pattern 4: lib/ Module Package with Tests
```
lib/Tie/Array.pm
lib/Tie/Array/push.t
lib/Tie/Array/splice.t
lib/Tie/Array/std.t
```

## Test Execution Setup

### Core Tests (in t/ directory)
```perl
#!/usr/bin/perl -w

BEGIN {
    chdir 't' if -d 't';    # Change to t/ directory if it exists
    @INC = ('../lib');       # Add lib directory to search path
}

use Test::More;
```

### Extension/Distribution Tests (ext/, dist/, cpan/)
The TEST script handles path adjustments automatically:

```perl
# From perl5/t/TEST lines 265-278
if ($test =~ s!^(\.\./(cpan|dist|ext)/[^/]+)/t!t!) {
    $run_dir = $1;           # e.g., ../ext/File-Find
    $return_dir = '../../t';
    $lib = '../../lib';
    $perl = '../../t/perl';
    $testswitch = "-I../.. -MTestInit=U2T";
}
```

**What this means:**
- Tests in `ext/File-Find/t/find.t` are run from the `ext/File-Find/t/` directory
- The test harness sets up paths so `../../lib` points to the main lib directory
- `TestInit` module handles additional setup with `U2T` flag (Up 2 levels, then Test)

## Key Insights

### 1. **Two Discovery Methods**
- **Direct scanning**: For core tests in `t/` and `lib/`
- **MANIFEST parsing**: For `ext/`, `dist/`, `cpan/` tests

### 2. **Tests Must Be in MANIFEST**
Extension/distribution tests must be listed in the `MANIFEST` file to be discovered.

### 3. **Flexible Test Locations**
Tests can be:
- In `t/` subdirectory (for ext/dist/cpan modules)
- In the same directory as the module (for lib/ modules)
- In a subdirectory with `/t/` (for lib/ modules)
- Named `*.t` or `test.pl`

### 4. **Automatic Path Adjustment**
The TEST script automatically adjusts:
- Working directory (runs tests from their own directory)
- `@INC` paths (to find modules correctly)
- Perl executable path (to use the just-built perl)

### 5. **Special Test Switches**
Different test locations get different switches:
- `ext/dist/cpan`: `-I../.. -MTestInit=U2T`
- `lib/`: `-I.. -MTestInit=U1`
- Core `t/`: `-I.. -MTestInit`

## Examples from Standard Perl

### Extension Module (ext/)
```
ext/File-Find/
  lib/File/Find.pm
  t/find.t
  t/taint.t
  t/correct-absolute-path-with-follow.t
```

**How it's tested:**
1. MANIFEST lists: `ext/File-Find/t/find.t`
2. TEST script discovers it from MANIFEST
3. Test runs from `ext/File-Find/t/` directory
4. Uses `-I../.. -MTestInit=U2T` to find modules

### lib/ Module with Co-located Test
```
lib/AnyDBM_File.pm
lib/AnyDBM_File.t
```

### lib/ Module with Multiple Tests
```
lib/overload.pm
lib/overload.t
lib/overload64.t
```

### lib/ Module with Test Subdirectory
```
lib/DBM_Filter.pm
lib/DBM_Filter/t/01error.t
lib/DBM_Filter/t/02core.t
lib/DBM_Filter/t/compress.t
lib/DBM_Filter/t/encode.t
lib/DBM_Filter/t/int32.t
lib/DBM_Filter/t/null.t
lib/DBM_Filter/t/utf8.t
```

### lib/ Module Package with Tests
```
lib/Tie/Array.pm
lib/Tie/Array/push.t
lib/Tie/Array/splice.t
lib/Tie/Array/std.t
lib/Tie/Array/stdpush.t
```

## Test Harness Features

### 1. Parallel Execution
Tests can run in parallel unless marked as:
- `must_be_executed_serially` - Can't run with tests from same directory
- `must_be_executed_alone` - Must run completely alone

### 2. Special Switches
Some directories get special test switches:
```perl
my %dir_to_switch = (
    base => '',
    comp => '',
    run => '',
    '../ext/File-Glob/t' => '-I.. -MTestInit',
);
```

### 3. Environment Setup
The harness:
- Sets `PERL_CORE=1` environment variable
- Cleans up potentially interfering environment variables
- Manages `@INC` paths appropriately

## Implications for PerlOnJava

### Current PerlOnJava Test Structure
PerlOnJava currently uses:
```
t/                    # Test directory
  op/                 # Operator tests
  re/                 # Regex tests
  io/                 # IO tests
  etc.
```

This matches standard Perl's core test structure but doesn't yet include:
- `lib/` module test discovery
- `ext/` extension test discovery
- MANIFEST-based test discovery

### What PerlOnJava Needs to Support

#### 1. For Core Tests (Already Working)
- ✅ Direct scanning of `t/op/`, `t/re/`, `t/io/`, etc.
- ✅ Tests run from `t/` directory
- ✅ `@INC` includes `lib/`

#### 2. For lib/ Module Tests (Needs Implementation)
To support `lib/**/*.t` tests:
1. **Scan lib/ directory** for `*.t` files recursively
2. **Run tests from t/ directory** with proper `@INC`
3. **Use `-I.. -MTestInit=U1`** switch for lib tests
4. **Support both patterns**: co-located and `/t/` subdirectory

#### 3. For ext/ Extension Tests (Future Enhancement)
To support `ext/*/t/*.t` tests:
1. **Parse MANIFEST file** to discover extension tests
2. **Change to extension directory** before running tests
3. **Adjust paths**: `../../lib`, `../../t/perl`
4. **Use `-I../.. -MTestInit=U2T`** switch
5. **Handle return to original directory** after test

### Implementation Priority

**Phase 1: lib/ Tests (High Priority)**
- Many Perl modules have tests in `lib/`
- Simpler to implement (similar to existing core tests)
- No MANIFEST parsing needed (can use direct scanning)
- Example: `lib/Benchmark.t`, `lib/English.t`, etc.

**Phase 2: ext/ Tests (Medium Priority)**
- Requires MANIFEST parsing
- Requires directory changing logic
- More complex path management
- Example: `ext/File-Find/t/find.t`

**Phase 3: dist/ and cpan/ Tests (Lower Priority)**
- Similar to ext/ but for different module types
- May have additional dependencies
- Can wait until ext/ support is solid

### Recommended Implementation Steps

1. **Add lib/ test discovery** to `perl_test_runner.pl`:
   ```perl
   # Scan lib/ for *.t files
   find_tests('lib', '*.t');
   ```

2. **Ensure proper @INC setup** for lib tests:
   ```perl
   # Tests in lib/ should have ../lib in @INC
   # (already works if run from t/ directory)
   ```

3. **Add MANIFEST parsing** (for ext/ support):
   ```perl
   sub parse_manifest {
       open my $fh, '<', '../MANIFEST' or return;
       while (<$fh>) {
           if (m!^(ext|dist|cpan)/(\S+)/t/(\S+\.t)!) {
               push @tests, "../$1/$2/t/$3";
           }
       }
   }
   ```

4. **Add directory-aware test execution**:
   ```perl
   sub run_test_in_directory {
       my ($test, $dir) = @_;
       chdir $dir;
       # Run test with adjusted paths
       chdir $original_dir;
   }
   ```

### Benefits

Implementing this will enable:
- Testing of Perl core library modules
- Testing of extension modules
- Better compatibility with standard Perl test suite
- Ability to run `make test` equivalents in PerlOnJava

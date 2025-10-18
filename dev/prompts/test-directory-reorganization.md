# Test Directory Reorganization Plan

## Objective

Reorganize `src/test/resources/` to separate PerlOnJava-specific tests from standard Perl module tests, enabling clear integration of perl5/ test suite.

## Current Structure

```
src/test/resources/
  ├── README.md
  ├── array.t                    # PerlOnJava unit tests
  ├── hash.t
  ├── operations.t
  ├── regex/                     # PerlOnJava regex tests
  │   ├── basic.t
  │   └── ...
  ├── pack/                      # PerlOnJava pack tests
  │   └── ...
  └── ... (100+ test files)
```

**Problem:** Mixing PerlOnJava-specific tests with future standard Perl tests will cause confusion.

## Proposed New Structure

```
src/test/resources/
  ├── README.md                  # Updated to explain structure
  │
  ├── unit/                      # PerlOnJava-specific unit tests
  │   ├── README.md              # Explains these are PerlOnJava tests
  │   ├── array.t
  │   ├── hash.t
  │   ├── operations.t
  │   ├── regex/
  │   │   ├── basic.t
  │   │   └── ...
  │   ├── pack/
  │   │   └── ...
  │   └── ... (all current tests)
  │
  ├── lib/                       # Standard Perl lib/ module tests
  │   ├── Benchmark.t            # From perl5/lib/Benchmark.t
  │   ├── English.t              # From perl5/lib/English.t
  │   └── ...
  │
  ├── ext/                       # Standard Perl ext/ module tests
  │   ├── File-Find/
  │   │   └── t/
  │   │       ├── find.t         # From perl5/ext/File-Find/t/find.t
  │   │       └── taint.t
  │   └── ...
  │
  ├── dist/                      # Standard Perl dist/ module tests
  │   └── ...
  │
  └── cpan/                      # Standard Perl cpan/ module tests
      └── ...
```

## Benefits

1. **Clear Separation:** PerlOnJava tests vs standard Perl tests
2. **Standard Structure:** Mirrors perl5/ test organization
3. **Easy Sync:** Can sync entire directories from perl5/
4. **No Confusion:** Developers immediately know test origin
5. **Scalability:** Room for thousands of standard Perl tests

## Migration Steps

### Step 1: Create New Directory Structure

```bash
cd src/test/resources
mkdir -p unit
mkdir -p lib
mkdir -p ext
mkdir -p dist
mkdir -p cpan
```

### Step 2: Move Existing Tests to unit/

```bash
cd src/test/resources

# Move all current test files to unit/
for file in *.t; do
    [ -f "$file" ] && git mv "$file" unit/
done

# Move test subdirectories to unit/
for dir in */; do
    case "$dir" in
        unit/|lib/|ext/|dist/|cpan/) 
            # Skip the new directories
            ;;
        *)
            git mv "$dir" unit/
            ;;
    esac
done
```

### Step 3: Update Test Runner

Update `perl_test_runner.pl` to scan the new structure:

```perl
# Old code:
my @test_files = glob('src/test/resources/*.t');
push @test_files, glob('src/test/resources/*/*.t');

# New code:
my @test_dirs = (
    'src/test/resources/unit',      # PerlOnJava unit tests
    'src/test/resources/lib',       # Standard lib/ tests
    'src/test/resources/ext',       # Standard ext/ tests
    'src/test/resources/dist',      # Standard dist/ tests
    'src/test/resources/cpan',      # Standard cpan/ tests
);

my @test_files;
for my $dir (@test_dirs) {
    next unless -d $dir;
    push @test_files, find_tests_recursive($dir);
}

sub find_tests_recursive {
    my ($dir) = @_;
    my @tests;
    
    opendir(my $dh, $dir) or return @tests;
    while (my $entry = readdir($dh)) {
        next if $entry =~ /^\./;
        my $path = "$dir/$entry";
        
        if (-d $path) {
            push @tests, find_tests_recursive($path);
        } elsif ($entry =~ /\.t$/) {
            push @tests, $path;
        }
    }
    closedir($dh);
    
    return @tests;
}
```

### Step 4: Update Documentation

#### src/test/resources/README.md

```markdown
# PerlOnJava Test Suite

This directory contains the PerlOnJava test suite, organized into:

## Directory Structure

### unit/
PerlOnJava-specific unit tests that verify PerlOnJava implementation details,
edge cases, and features. These tests are not from the standard Perl test suite.

Examples:
- array.t - Array implementation tests
- hash.t - Hash implementation tests
- regex/ - Regex engine tests
- pack/ - Pack/unpack tests

### lib/
Tests for standard Perl core library modules (from perl5/lib/).
These tests verify compatibility with standard Perl module behavior.

Examples:
- Benchmark.t - Tests for Benchmark.pm
- English.t - Tests for English.pm

### ext/
Tests for standard Perl extensions (from perl5/ext/).
Extensions are modules that may have XS components.

Examples:
- File-Find/t/find.t - Tests for File::Find
- Hash-Util/t/*.t - Tests for Hash::Util

### dist/
Tests for dual-life distributions (from perl5/dist/).
These modules exist both in core Perl and on CPAN.

### cpan/
Tests for CPAN modules bundled with Perl (from perl5/cpan/).

## Running Tests

Run all tests:
```bash
./perl_test_runner.pl
```

Run specific test directory:
```bash
./perl_test_runner.pl unit/
./perl_test_runner.pl lib/
```

Run specific test file:
```bash
./jperl src/test/resources/unit/array.t
./jperl src/test/resources/lib/Benchmark.t
```
```

#### src/test/resources/unit/README.md

```markdown
# PerlOnJava Unit Tests

This directory contains PerlOnJava-specific unit tests that are NOT part of
the standard Perl test suite.

## Purpose

These tests verify:
- PerlOnJava implementation details
- Edge cases specific to the Java implementation
- Features that differ from standard Perl
- Integration between Perl and Java components
- Performance and optimization features

## Test Categories

### Core Data Structures
- array.t, array_*.t - Array implementation
- hash.t, hash_*.t - Hash implementation
- scalar tests - Scalar handling

### Operators
- operations.t - General operators
- compound_assignment.t - Assignment operators
- bitwise_*.t - Bitwise operators

### Regular Expressions
- regex/ - Regex engine tests specific to PerlOnJava

### Pack/Unpack
- pack/, pack.t, pack_*.t - Pack/unpack implementation

### Object System
- class_features.t - Class features
- method_*.t - Method resolution

### I/O
- io_*.t - I/O operations
- directory.t - Directory operations

### Language Features
- signatures.t - Subroutine signatures
- try_catch.t - Try/catch blocks
- state.t - State variables

## Adding New Tests

When adding new PerlOnJava-specific tests:
1. Place them in this directory
2. Use descriptive names
3. Add comments explaining what's being tested
4. Document any PerlOnJava-specific behavior
```

### Step 5: Update Build Configuration

If there are any hardcoded paths in build files, update them:

**Maven (pom.xml):**
```xml
<testResources>
    <testResource>
        <directory>src/test/resources</directory>
        <includes>
            <include>**/*.t</include>
        </includes>
    </testResource>
</testResources>
```

**Gradle (build.gradle):**
```gradle
test {
    testLogging {
        // Include all test directories
    }
}
```

### Step 6: Update CI/CD

Update any CI/CD configuration that references test paths:

**.github/workflows/test.yml:**
```yaml
- name: Run unit tests
  run: ./perl_test_runner.pl unit/

- name: Run lib tests
  run: ./perl_test_runner.pl lib/

- name: Run ext tests
  run: ./perl_test_runner.pl ext/
```

## Detailed Migration Script

Create `scripts/reorganize_tests.sh`:

```bash
#!/bin/bash
set -e

echo "Reorganizing test directory structure..."

cd src/test/resources

# Create new directories
echo "Creating new directory structure..."
mkdir -p unit
mkdir -p lib
mkdir -p ext
mkdir -p dist
mkdir -p cpan

# Move README.md temporarily
if [ -f README.md ]; then
    mv README.md README.md.backup
fi

# Move all .t files to unit/
echo "Moving test files to unit/..."
for file in *.t; do
    if [ -f "$file" ]; then
        git mv "$file" unit/ || mv "$file" unit/
        echo "  Moved $file"
    fi
done

# Move subdirectories to unit/ (except the new ones)
echo "Moving test subdirectories to unit/..."
for dir in */; do
    dirname="${dir%/}"
    case "$dirname" in
        unit|lib|ext|dist|cpan)
            echo "  Skipping $dirname (new directory)"
            ;;
        *)
            if [ -d "$dirname" ]; then
                git mv "$dirname" unit/ || mv "$dirname" unit/
                echo "  Moved $dirname/"
            fi
            ;;
    esac
done

# Restore README.md
if [ -f README.md.backup ]; then
    mv README.md.backup README.md
fi

echo "Done! Test directory reorganization complete."
echo ""
echo "Next steps:"
echo "1. Update perl_test_runner.pl to scan new structure"
echo "2. Update README.md files"
echo "3. Test that all tests still run"
echo "4. Commit changes"
```

## Testing the Migration

### Verification Steps

1. **Count tests before migration:**
   ```bash
   find src/test/resources -name "*.t" | wc -l
   ```

2. **Run migration:**
   ```bash
   bash scripts/reorganize_tests.sh
   ```

3. **Count tests after migration:**
   ```bash
   find src/test/resources/unit -name "*.t" | wc -l
   ```

4. **Verify test runner works:**
   ```bash
   ./perl_test_runner.pl unit/
   ```

5. **Run full test suite:**
   ```bash
   ./perl_test_runner.pl
   ```

### Expected Results

- Same number of tests before and after
- All tests in `unit/` subdirectory
- Empty `lib/`, `ext/`, `dist/`, `cpan/` directories (ready for standard tests)
- Test runner finds and runs all tests
- No broken test paths

## Future: Adding Standard Perl Tests

After reorganization, adding standard Perl tests is straightforward:

### Example: Adding Benchmark.t

```bash
# Copy from perl5
cp perl5/lib/Benchmark.t src/test/resources/lib/

# Test it
./jperl src/test/resources/lib/Benchmark.t

# Test runner will automatically find it
./perl_test_runner.pl lib/
```

### Example: Adding File::Find tests

```bash
# Copy entire test directory
mkdir -p src/test/resources/ext/File-Find
cp -r perl5/ext/File-Find/t src/test/resources/ext/File-Find/

# Test them
./perl_test_runner.pl ext/File-Find/
```

## Rollback Plan

If issues arise, rollback is simple:

```bash
cd src/test/resources

# Move everything back from unit/
mv unit/* .
rmdir unit

# Remove empty directories
rmdir lib ext dist cpan 2>/dev/null || true
```

## Impact Assessment

### Low Risk
- Pure directory reorganization
- No code changes required (except test runner)
- Easy to rollback
- Git preserves history

### Benefits
- Clear organization
- Scalable for thousands of tests
- Standard Perl structure
- Easy to sync with perl5/

### Effort
- **Migration:** 1-2 hours
- **Testing:** 1 hour
- **Documentation:** 1 hour
- **Total:** 3-4 hours

## Timeline

1. **Day 1 Morning:** Create and test migration script
2. **Day 1 Afternoon:** Run migration, update test runner
3. **Day 1 Evening:** Update documentation, verify all tests pass
4. **Day 2:** Add first standard Perl tests to verify structure works

## Success Criteria

- ✅ All existing tests moved to `unit/`
- ✅ Test runner updated and working
- ✅ All tests still pass
- ✅ Documentation updated
- ✅ Ready to add standard Perl tests to `lib/`, `ext/`, etc.

## Conclusion

This reorganization provides a clean foundation for integrating the standard Perl test suite while maintaining clarity about which tests are PerlOnJava-specific. The migration is low-risk, easily reversible, and sets up PerlOnJava for long-term scalability.

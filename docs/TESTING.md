# Testing Guide

PerlOnJava provides a two-level testing strategy to balance development speed with comprehensive validation.

## Quick Start

### Fast Unit Tests (Recommended for Development)

```bash
make test-unit
```

Runs only the fast unit tests from `src/test/resources/unit/`. These tests:
- ✅ Run in seconds (not minutes)
- ✅ Cover core functionality
- ✅ Use parallel execution (8 jobs)
- ✅ Provide immediate feedback during development
- ✅ Output TAP format with detailed statistics

### Comprehensive Test Suite

```bash
# First, sync external module tests (one-time or when updating)
perl dev/import-perl5/sync.pl

# Then run all tests
make test-all
```

Runs all tests including comprehensive module tests. These tests:
- 🔍 Include Benchmark.pm and other Perl core modules
- ⏱️ Take longer to complete (minutes)
- 📊 Generate detailed JSON report (`test_results.json`)
- 🎯 Identify high-priority opportunities (incomplete tests)
- 📈 Provide feature impact analysis
- 📁 Uses external `perl5_t/` directory (not in git)

## Testing Approaches

### 1. Perl-Style Testing (Default)

Uses `dev/tools/perl_test_runner.pl` - a prove-like test harness:

```bash
# Fast unit tests
make test-unit

# All tests with JSON report
make test-all

# Custom test run
perl dev/tools/perl_test_runner.pl --jobs 4 --timeout 20 src/test/resources/unit
```

**Features:**
- TAP (Test Anything Protocol) output
- Parallel test execution
- Timeout protection
- Feature impact analysis
- Incomplete test detection
- JSON reporting

**Options:**
```bash
--jobs|-j NUM      Number of parallel jobs (default: 5)
--timeout SEC      Timeout per test in seconds
--output FILE      Save detailed results to JSON file
--jperl PATH       Path to jperl executable (default: ./jperl)
```

### 2. JUnit/Gradle Testing (For CI/CD)

Uses JUnit 5 with tags for test filtering:

```bash
# Fast unit tests via Gradle
make test-gradle-unit

# All tests via Gradle
make test-gradle-all

# Direct Gradle commands
./gradlew testUnit    # Only unit tests
./gradlew testAll     # All tests
./gradlew test        # Default test task
```

**Use Cases:**
- CI/CD pipeline integration
- IDE integration (IntelliJ, VSCode)
- JUnit test reports
- Maven-style testing

## Test Organization

```
src/test/resources/
└── unit/              # Fast unit tests (seconds) - IN GIT
    ├── array.t
    ├── hash.t
    ├── regex/
    └── ...

perl5_t/               # Module tests (slower) - NOT IN GIT
├── Benchmark/         # Synced via import-perl5/sync.pl
│   └── Benchmark.t
├── cpan/              # CPAN module tests
├── dist/              # Distribution tests
└── lib/               # Library tests

t/                     # Perl 5 core test suite - NOT IN GIT
└── ...                # Synced from perl5/t/
```

### Test Categories

| Category | Location | Speed | In Git? | Purpose |
|----------|----------|-------|---------|---------|
| **Unit Tests** | `src/test/resources/unit/` | Fast (seconds) | ✅ Yes | Core functionality, operators, syntax |
| **Module Tests** | `perl5_t/` | Slow (minutes) | ❌ No | Perl core modules, CPAN compatibility |
| **Core Test Suite** | `t/` | Varies | ❌ No | Perl 5 standard test suite |

**Note:** Module tests in `perl5_t/` and the core test suite in `t/` are synced from the Perl 5 repository using `dev/import-perl5/sync.pl` and are not committed to git. This keeps the repository size manageable while still allowing comprehensive testing.

## Development Workflow

### During Development (Fast Feedback Loop)

```bash
# 1. Make changes
vim src/main/java/org/perlonjava/...

# 2. Build
make dev

# 3. Run fast tests
make test-unit
```

### Before Committing (Comprehensive Validation)

```bash
# Sync external tests (if not already done)
perl dev/import-perl5/sync.pl

# Run full test suite
make test-all

# Review test_results.json for any regressions
```

### CI/CD Pipeline

```bash
# Build and run all tests
make build
make test-gradle-all
```

## Test Output

### TAP Output (perl_test_runner.pl)

```
Finding test files in src/test/resources/unit...
Found 142 test files
Running tests with ./jperl (8 parallel jobs, 10s timeout)
------------------------------------------------------------
[  1/142] unit/array.t                     ... ✓ 15/15 ok (0.23s)
[  2/142] unit/hash.t                      ... ✓ 12/12 ok (0.18s)
[  3/142] unit/regex/basic.t              ... ✓ 25/25 ok (0.31s)
...

TEST SUMMARY:
  Total files: 142
  Passed:      140
  Failed:      2
  Errors:      0
  Timeouts:    0
  Incomplete:  0

  Total tests: 3,456
  OK:          3,421
  Not OK:      35
  Pass rate:   99.0%
```

### JUnit Output (Gradle)

```
> Task :testUnit

PerlScriptExecutionTest > Unit test: unit/array.t PASSED
PerlScriptExecutionTest > Unit test: unit/hash.t PASSED
...

BUILD SUCCESSFUL in 12s
142 tests completed, 140 succeeded, 2 failed
```

## Advanced Usage

### Running Specific Tests

```bash
# Single test file
perl dev/tools/perl_test_runner.pl src/test/resources/unit/array.t

# Specific directory
perl dev/tools/perl_test_runner.pl src/test/resources/unit/regex

# With custom settings
perl dev/tools/perl_test_runner.pl --jobs 16 --timeout 60 --output mytest.json t/
```

### Analyzing Test Results

After running `make test-all`, examine `test_results.json`:

```bash
# View summary
jq '.summary' test_results.json

# Find failing tests
jq '.results | to_entries | map(select(.value.status == "fail")) | .[].key' test_results.json

# Feature impact
jq '.feature_impact' test_results.json
```

### Debugging Failures

```bash
# Run single test with full output
./jperl src/test/resources/unit/array.t

# Run with verbose output
./jperl -d src/test/resources/unit/array.t

# Check for syntax errors
./jperl -c src/test/resources/unit/array.t
```

## Best Practices

1. **Run `test-unit` frequently** during development for fast feedback
2. **Run `test-all` before commits** to catch regressions
3. **Use parallel jobs** (`--jobs 8`) to speed up test runs
4. **Set appropriate timeouts** - short for unit tests (10s), longer for module tests (30s)
5. **Review incomplete tests** - they often indicate bugs that block many tests
6. **Save JSON reports** for trend analysis and debugging

## Performance Tips

- **Unit tests**: Should complete in < 5 minutes total
- **All tests**: May take 10-30 minutes depending on system
- **Parallel jobs**: Adjust `--jobs` based on CPU cores (typically 1-2x core count)
- **Timeouts**: Increase for slow systems, decrease for fast feedback

## Integration with IDEs

### IntelliJ IDEA / VSCode

1. Open project
2. Right-click on `PerlScriptExecutionTest.java`
3. Select "Run tests"
4. Or run specific tags: `@Tag("unit")` or `@Tag("full")`

### Command Line with Gradle

```bash
# Run specific test class
./gradlew test --tests PerlScriptExecutionTest

# Run with tags
./gradlew testUnit    # @Tag("unit")
./gradlew testAll     # @Tag("full")
```

## Troubleshooting

### Tests timing out

Increase timeout: `--timeout 60`

### Too slow

Reduce parallel jobs on low-memory systems: `--jobs 2`

### Out of memory

- Reduce parallel jobs
- Increase JVM heap: `export JAVA_OPTS="-Xmx4g"`

### Test hangs

Check for infinite loops, use timeout command:
```bash
timeout 30s ./jperl problematic_test.t
```

## Syncing External Tests

The `perl5_t/` directory and `t/` directory are not in git. To populate them:

```bash
# Clone Perl 5 source (one-time setup)
git clone https://github.com/Perl/perl5.git

# Sync tests from perl5 to perl5_t/ and t/
perl dev/import-perl5/sync.pl
```

This will:
1. Copy module tests to `perl5_t/` (e.g., `Benchmark.t`)
2. Copy the core test suite to `t/`
3. Apply any necessary patches
4. Create necessary directories

**When to sync:**
- Initial setup (after cloning PerlOnJava)
- When Perl 5 tests are updated
- When `config.yaml` changes

## See Also

- [Build Guide](BUILD.md) - Building PerlOnJava
- [Architecture](ARCHITECTURE.md) - System architecture
- [Import System](../dev/import-perl5/README.md) - Importing Perl5 tests


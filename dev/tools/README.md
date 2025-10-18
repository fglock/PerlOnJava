# Development Tools

This directory contains utility scripts and tools for PerlOnJava development.

## Test Management Tools

### perl_test_runner.pl
**Purpose:** Main test runner for PerlOnJava test suite.

**Usage:**
```bash
./dev/tools/perl_test_runner.pl [test_directory]
```

### reorganize_tests.sh
**Purpose:** Reorganize test directory structure to separate PerlOnJava unit tests from standard Perl module tests.

**Usage:**
```bash
./dev/tools/reorganize_tests.sh
```

**What it does:**
- Moves all current tests to `src/test/resources/unit/`
- Creates empty directories: `lib/`, `ext/`, `dist/`, `cpan/`
- Preserves git history with `git mv`
- Verifies all tests are accounted for

**Documentation:** See `dev/prompts/test-directory-reorganization.md`

### compare_test_results.pl
**Purpose:** Compare test results between runs to identify regressions or improvements.

### tap_test_fixer.pl
**Purpose:** Fix TAP (Test Anything Protocol) test output formatting.

## Analysis Tools

### analyze_missing_operators.pl
**Purpose:** Analyze which Perl operators are not yet implemented in PerlOnJava.

### analyze_pack_failures.pl
**Purpose:** Analyze pack/unpack test failures to identify patterns.

## Git Hooks

### install_git_hooks.sh
**Purpose:** Install git hooks for pre-commit checks.

**Usage:**
```bash
./dev/tools/install_git_hooks.sh
```

### pre_commit_check.sh
**Purpose:** Pre-commit hook that runs checks before allowing commits.

## Development Utilities

### run_with_timeout.sh
**Purpose:** Run commands with a timeout to prevent hanging tests.

**Usage:**
```bash
./dev/tools/run_with_timeout.sh <timeout_seconds> <command>
```

### safe_analysis_setup.sh
**Purpose:** Set up safe environment for analysis tasks.

### start_analysis.sh
**Purpose:** Start analysis of test results or code patterns.

## Parser Tools

### perl5_parser.pl
**Purpose:** Parse Perl 5 code for analysis.

### list_perl_prototypes.pl
**Purpose:** List Perl function prototypes.

### create_lexer_switch.pl
**Purpose:** Generate lexer switch statements.

## Code Generation/Templates

### automatic_operator_descriptor.java
**Purpose:** Template for automatic operator descriptors.

### Overload.java
**Purpose:** Template for operator overloading.

### UnaryOperatorBenchmark.java
**Purpose:** Benchmark template for unary operators.

### TTYCheck.java
**Purpose:** TTY checking utility.

### Other Java templates
- `cache_eviction_thread.java`
- `combine_set.java`
- `inline_grep.java`
- `lazy_list.java`
- `overloading_bit.java`

## Configuration

### _vimrc
**Purpose:** Vim configuration for PerlOnJava development.

## Adding New Tools

When adding new development tools:
1. Place scripts in this directory
2. Make them executable: `chmod +x tool_name.sh`
3. Add documentation to this README
4. Reference from relevant documentation in `dev/prompts/`
5. Use clear, descriptive names
6. Include usage examples and error handling

# PerlOnJava Test Suite

This directory contains the PerlOnJava test suite, organized into separate subdirectories for clarity.

## Directory Structure

### unit/
**PerlOnJava-specific unit tests** that verify PerlOnJava implementation details, edge cases, and features. These tests are NOT from the standard Perl test suite.

Examples:
- `array.t` - Array implementation tests
- `hash.t` - Hash implementation tests
- `regex/` - Regex engine tests
- `pack/` - Pack/unpack tests
- `overload/` - Operator overloading tests

### lib/
**Tests for standard Perl core library modules** (from perl5/lib/).
Structure mirrors perl5/lib/ exactly - tests are in the same relative paths.

Examples (when populated):
- `Benchmark.t` - Single test for Benchmark.pm
- `English.t` - Single test for English.pm
- `B/Deparse.t`, `B/Deparse-core.t` - Multiple tests for B::Deparse
- `DBM_Filter/t/*.t` - Tests in subdirectory for DBM_Filter.pm
- `File/Basename.t` - Test for File::Basename

### ext/
Structure mirrors perl5/ext/ exactly - preserves module directory structure.

Examples (when populated):
- `File-Find/t/find.t` - Tests for File::Find
- `Hash-Util/t/*.t` - Tests for Hash::Util

### dist/
**Tests for dual-life distributions** (from perl5/dist/).
These modules exist both in core Perl and on CPAN.
Structure mirrors perl5/dist/ exactly.

### cpan/
**Tests for CPAN modules bundled with Perl** (from perl5/cpan/).
Structure mirrors perl5/cpan/ exactly.

## Running Tests

Run all tests:
```bash
./perl_test_runner.pl
```

Run specific test directory:
```bash
./perl_test_runner.pl unit/
./perl_test_runner.pl lib/
./perl_test_runner.pl lib/B/          # Just B::Deparse tests (when added)
./perl_test_runner.pl lib/DBM_Filter/ # Just DBM_Filter tests (when added)
```

Run specific test file:
```bash
./jperl src/test/resources/unit/array.t
./jperl src/test/resources/lib/Benchmark.t  # When added
./jperl src/test/resources/lib/B/Deparse.t  # When added
./jperl src/test/resources/lib/DBM_Filter/t/01error.t  # When added
```

## Adding New Tests

### For PerlOnJava-specific tests:
Place in `unit/` with descriptive names.

### For standard Perl tests:
**Mirror the perl5/ structure exactly:**
- If perl5 has `lib/Module.t`, put it in `lib/Module.t`
- If perl5 has `lib/Module/Name.t`, put it in `lib/Module/Name.t`
- If perl5 has `lib/Module/t/*.t`, put them in `lib/Module/t/*.t`
- If perl5 has `ext/Module-Name/t/*.t`, put them in `ext/Module-Name/t/*.t`

## Test Framework

All tests use the Test::More framework and verify PerlOnJava's Perl compatibility.

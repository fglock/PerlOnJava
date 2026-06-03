# PerlOnJava Unit Tests

This directory contains unit tests for Perl behavior exercised by PerlOnJava.

## Purpose

These tests verify:
- Standard Perl behavior that PerlOnJava must match
- Edge cases that have regressed in PerlOnJava
- Compatibility with CPAN modules and core Perl APIs
- Integration between Perl and Java components
- Performance and optimization features

## Test Categories

### Core Data Structures
- `array.t`, `array_*.t` - Array implementation
- `hash.t`, `hash_*.t` - Hash implementation
- Scalar tests - Scalar handling

### Operators
- `operations.t` - General operators
- `compound_assignment.t` - Assignment operators
- `bitwise_*.t` - Bitwise operators

### Regular Expressions
- `regex/` - Regex engine compatibility tests

### Pack/Unpack
- `pack/`, `pack.t`, `pack_*.t` - Pack/unpack implementation

### Object System
- `class_features.t` - Class features
- `method_*.t` - Method resolution
- `mro_*.t` - Method resolution order

### I/O
- `io_*.t` - I/O operations
- `directory.t` - Directory operations

### Language Features
- `signatures.t` - Subroutine signatures
- `try_catch.t` - Try/catch blocks
- `state.t` - State variables
- `local.t` - Local variables

### Overloading
- `overload/` - Operator overloading tests

## Adding New Tests

When adding new unit tests:
1. Place them in this directory
2. Use descriptive names
3. Add comments explaining what's being tested
4. Verify the expected behavior with standard Perl first
5. Use Test::More framework
6. Capture the standard Perl run before relying on the test:
   `prove -r src/test/resources/unit > /tmp/prove_unit_perl.txt 2>&1; echo "EXIT: $?" >> /tmp/prove_unit_perl.txt`

## Note

These tests are maintained separately from the upstream Perl test suite, but their expectations should remain compatible with standard Perl unless a test is explicitly skipped because an optional module or platform facility is unavailable.

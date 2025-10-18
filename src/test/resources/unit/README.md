# PerlOnJava Unit Tests

This directory contains PerlOnJava-specific unit tests that are NOT part of the standard Perl test suite.

## Purpose

These tests verify:
- PerlOnJava implementation details
- Edge cases specific to the Java implementation
- Features that differ from standard Perl
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
- `regex/` - Regex engine tests specific to PerlOnJava

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

When adding new PerlOnJava-specific tests:
1. Place them in this directory
2. Use descriptive names
3. Add comments explaining what's being tested
4. Document any PerlOnJava-specific behavior
5. Use Test::More framework

## Note

These tests are maintained separately from the standard Perl test suite to clearly distinguish PerlOnJava-specific functionality from standard Perl compatibility testing.

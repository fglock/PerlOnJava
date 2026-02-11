# Interpreter Tests

This directory contains tests for the PerlOnJava interpreter (Phase 1+).

## Structure

Tests are organized separately from production tests (`src/test/`) because:
- Interpreter is experimental and incomplete
- Allows faster iteration without breaking production test suite
- Will be migrated to production tests once interpreter is stable

## Running Tests

```bash
# Individual test files
./jperl dev/interpreter/tests/basic_arithmetic.t

# All interpreter tests
find dev/interpreter/tests -name '*.t' -exec ./jperl {} \;
```

## Test Organization

- `basic_*.t` - Basic operations (arithmetic, strings, variables)
- `control_flow_*.t` - Control flow (GOTO, last/next/redo)
- `data_structures_*.t` - Arrays, hashes
- `closures_*.t` - Closure support
- `mixed_*.t` - Mixed compiled/interpreted code

Tests use Perl's Test::More TAP format for consistency with production tests.

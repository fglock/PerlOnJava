# Custom Bytecode / Interpreter Subsystem

Documentation for PerlOnJava's custom bytecode interpreter — the second
execution backend that complements the primary JVM bytecode emitter.

## Purpose

The interpreter exists for two reasons:

1. **`eval STRING`** — Evaluating Perl strings at runtime without the overhead
   of generating and loading a new JVM class for every eval.
2. **Large subroutine fallback** — When a generated `apply()` method would
   exceed the JVM 64 KB bytecode limit, the compiler falls back to the
   interpreter for that subroutine automatically.

## Key Documents

| Document | Description |
|----------|-------------|
| [BYTECODE_DOCUMENTATION.md](BYTECODE_DOCUMENTATION.md) | Opcode reference: all custom opcodes and their semantics |
| [OPCODES_ARCHITECTURE.md](OPCODES_ARCHITECTURE.md) | How the opcode set is structured |
| [CLOSURE_IMPLEMENTATION_COMPLETE.md](CLOSURE_IMPLEMENTATION_COMPLETE.md) | Closure capture and execution in the interpreter |
| [CLOSURE_IMPLEMENTATION_STATUS.md](CLOSURE_IMPLEMENTATION_STATUS.md) | Current closure support status |
| [EVAL_STRING_SPEC.md](EVAL_STRING_SPEC.md) | `eval STRING` compilation and execution spec |
| [OPTIMIZATION_RESULTS.md](OPTIMIZATION_RESULTS.md) | Measured speedups from interpreter optimizations |
| [STATUS.md](STATUS.md) | Current production-readiness status |
| [TESTING.md](TESTING.md) | How to run interpreter-specific tests |
| [architecture/](architecture/) | Deeper architecture docs (error reporting, slow opcodes, phase-2 plan) |
| [tests/](tests/) | Interpreter-specific test files |

## Quick Start

```bash
# Force interpreter mode for a script
./jperl --interpreter script.pl

# Run with interpreter fallback enabled (default)
./jperl script.pl

# Disable fallback (JVM only, fails on >64 KB methods)
JPERL_DISABLE_INTERPRETER_FALLBACK=1 ./jperl script.pl
```

## See Also

- `dev/architecture/large-code-refactoring.md` — How the compiler decides when to fall back
- `dev/design/interpreter.md` — High-level interpreter design document
- `dev/design/interpreter_benchmarks.md` — Performance comparison numbers

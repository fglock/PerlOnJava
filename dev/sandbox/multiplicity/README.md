# Multiplicity Demo

Demonstrates PerlOnJava's "multiplicity" feature: multiple independent Perl
interpreters running concurrently within a single JVM process.

Each interpreter has its own global variables, regex state, `@INC`, `%ENV`,
current working directory, process ID (`$$`), etc. They share the JVM heap;
generated classes are loaded into each runtime's own ClassLoader and become
eligible for GC once the runtime is discarded.

## Quick Start

```bash
# Build the project first
make dev

# Run with the bundled demo scripts
./dev/sandbox/multiplicity/run_multiplicity_demo.sh

# Run with custom scripts
./dev/sandbox/multiplicity/run_multiplicity_demo.sh script1.pl script2.pl

# Run all unit tests concurrently (stress test)
./dev/sandbox/multiplicity/run_multiplicity_demo.sh src/test/resources/unit/*.t
```

## Files

| File | Description |
|------|-------------|
| `MultiplicityDemo.java` | Java driver that creates N threads, each with its own `PerlRuntime` |
| `run_multiplicity_demo.sh` | Shell wrapper that compiles and runs the demo |
| `multiplicity_script1.pl` | Demo script: basic variable isolation |
| `multiplicity_script2.pl` | Demo script: regex state isolation |
| `multiplicity_script3.pl` | Demo script: module loading isolation |

## Current Status

- **122/126** unit tests pass with 126 concurrent interpreters
- Only 4 failures remain: `tie_*.t` (pre-existing `DESTROY` not implemented)

## Design Document

See [dev/design/concurrency.md](../../design/concurrency.md) for the full
concurrency design, implementation phases, and progress tracking.

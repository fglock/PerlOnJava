# Sandbox

Quick tests, proof-of-concept scripts, and one-off experiments.

## Purpose

This directory is the development scratch area. Files here are typically:

- **Exploratory scripts** written while investigating a bug or feature
- **Minimal reproductions** that preceded a proper test or design doc
- **Java snippets** extracted from larger refactoring experiments
- **Diff/patch experiments** for optimization ideas

Contents are kept indefinitely for reference but carry **no maintenance
commitment** — they may be outdated or broken.

## File Types

| Extension / Pattern | What it is |
|--------------------|------------|
| `*.pl` / `*.t` | Perl test or experiment scripts |
| `*.java` | Java implementation experiments |
| `*.java-snippet` | Java code fragments (not compilable standalone) |
| `*.diff` | Patch experiments |
| `README.*` | Context notes for a group of related files |
| `destroy_weaken/`, `multiplicity/`, `walker_blind_spot/` | Sub-directories for related experiments |

## Notable Files

| File | Purpose |
|------|---------|
| `block_refactor_safety_test.pl` | Tests for the proactive block-refactoring pass |
| `closure_capture_package_level.t` | Closure capture at package scope |
| `core_global_do_comprehensive.t` | `CORE::GLOBAL` override tests |
| `NumificationBenchmark.java` | Benchmark for scalar numification |
| `SingleLevelLookupBenchmark.java` / `TwoLevelLookupBenchmark.java` | Hash lookup benchmarks |
| `add_subtract_unbox_optimization.diff` | Bytecode unboxing optimization experiment |
| `walker_gate_dbic_minimal.t` | DBIx::Class minimal reproduction |

## See Also

- `dev/examples/` — More polished, documented example scripts
- `dev/design/` — Design documents that sometimes originate from sandbox experiments
- `dev/known-bugs/` — Minimal reproductions for *confirmed* unfixed bugs

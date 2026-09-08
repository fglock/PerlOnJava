# Benchmarks

Performance benchmark scripts for PerlOnJava.

## Purpose

These scripts measure the runtime performance of specific Perl operations
under PerlOnJava (and optionally system Perl for comparison). Use them to:

- Establish a baseline before making performance-sensitive changes
- Verify that an optimization actually improves throughput
- Detect regressions after refactoring

## Contents

| Script | What it measures |
|--------|-----------------|
| `benchmark_closure.pl` | Closure creation and invocation |
| `benchmark_eval_string.pl` | `eval STRING` compilation overhead |
| `benchmark_global.pl` | Package (global) variable access |
| `benchmark_lexical.pl` | Lexical variable access |
| `benchmark_memory.pl` | Memory usage for common data structures |
| `benchmark_memory_delta.pl` | Memory growth / leak detection |
| `benchmark_method.pl` | Method dispatch (class, inheritance) |
| `benchmark_regex.pl` | Regex compilation and matching |
| `benchmark_refcount_store.pl` | Tracked reference stores after `weaken()` activates lifecycle bookkeeping |
| `benchmark_string.pl` | String operations (concat, substr, etc.) |
| `benchmark_threads.pl` | Ithread snapshot, creation, scheduling, and join overhead |

## Running

```bash
# Single benchmark
./jperl dev/bench/benchmark_closure.pl

# Compare with system Perl
perl  dev/bench/benchmark_closure.pl
./jperl dev/bench/benchmark_closure.pl
```

## Portfolio runner

`run_performance_portfolio.pl` is the reproducible performance authority for
issue #1196.  It runs system Perl and PerlOnJava in alternating fresh-process
pairs and writes a JSON evidence bundle.  Its defaults are intentionally long:

```bash
perl dev/bench/run_performance_portfolio.pl
```

For a non-authoritative smoke test of one workload:

```bash
perl dev/bench/run_performance_portfolio.pl --workload closure --pairs 1 \
  --warmup-min 1 --warmup-max 1 --windows 1
```

See `dev/design/performance-over-perl.md` for the acceptance contract and
evidence requirements.

## See Also

- `dev/design/optimization.md` — optimization design decisions
- `dev/design/interpreter_benchmarks.md` — interpreter-mode benchmarks
- `dev/jvm_profiler/` — JFR / async-profiler skill for deeper analysis

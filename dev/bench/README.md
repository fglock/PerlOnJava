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
| `performance_over_perl.py` | Paired, warmup-aware Perl versus PerlOnJava workload measurements |
| `tests/test_performance_over_perl.py` | Unit tests for stability, checksums, eligibility, and paired statistics |

## Running

```bash
# Single benchmark
./jperl dev/bench/benchmark_closure.pl

# Compare with system Perl
perl  dev/bench/benchmark_closure.pl
./jperl dev/bench/benchmark_closure.pl
```

For the #1863 acceptance anchors, compare the original method-call loop, the
original captured-closure loop, and bit-packed Life. Pin the standard Perl
source revision (a Perl release tag or a blead commit) in the report:

The method and closure workers load their original benchmark definitions in
load-only mode. The method receiver resets between measurement windows, outside
the timed window; the inner loop and sink behavior match the original source.
Each worker verifies a fixed 20,000-call checksum and the measured-window
method result against its operation count.

```bash
python3 dev/bench/performance_over_perl.py \
  --workloads method_anchor closure_anchor life \
  --perl-source-revision v5.42.2 \
  --output /tmp/performance-over-perl.json
```

For the primary method acceptance comparison, set `--perl` to the separately
built optimized blead executable and `--perl-source-revision` to its exact
commit. Run the stable Perl release separately and report it as a second
reference. The runner records the complete Perl `-V` configuration, executable,
source revision, JVM flags, JAR hash, workload source hashes, commands, process
priority, warmup windows, measurements, checksums, and paired confidence
intervals. Launch order is randomized with a recorded seed and balanced across
both engines for each workload. A requested priority that cannot be verified
makes the pair ineligible for its summary.

To attribute a change to exact PerlOnJava artifacts, pin both JARs. The
candidate and reference JARs run in balanced randomized order in each
independent pair, and both artifact paths and hashes are recorded. This is
diagnostic attribution evidence, not Perl parity evidence:

```bash
python3 dev/bench/performance_over_perl.py \
  --workloads method_anchor closure_anchor life \
  --jperl-jar target/perlonjava-5.44.1.jar \
  --reference-jperl-jar /path/to/parent/perlonjava-5.44.1.jar \
  --output /tmp/performance-over-perl-parent-candidate.json
```

The six other diagnostic groups from #1196 remain available alongside the
method and closure anchors; Life is shared by both sets. The default run covers
all nine unique groups with seven balanced randomized fresh-process pairs, stabilized
warmup, and raw JSON samples. Use smaller window and pair counts only to
smoke-check the runner; smoke runs are not performance evidence.

## See Also

- `dev/design/optimization.md` — optimization design decisions
- `dev/design/interpreter_benchmarks.md` — interpreter-mode benchmarks
- `dev/jvm_profiler/` — JFR / async-profiler skill for deeper analysis

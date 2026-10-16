# Performance over Perl benchmark authority

## Goal

Measure the default JVM backend against the maintained system Perl on a single
reference host. The acceptance contract and portfolio are tracked in
[issue #1196](https://github.com/fglock/PerlOnJava/issues/1196). A speed claim
must come from repeatable, correctness-checked samples, not one wall-clock run.

The benchmark process excludes compilation and JVM startup from scored windows.
Each engine stays alive for its warmup and measurement windows. Inputs are
deterministic, operation counts and checksums are emitted for every window, and
the runner retains all raw samples in JSON.

## Workload portfolio

Each group has equal weight in the portfolio score.

| Group | Worker mode | Work unit |
| --- | --- | --- |
| Closure invocation | `closure` | One call to a captured closure |
| Method dispatch and blessed-hash access | `dispatch` | One method call and hash lookup |
| Lexical/global numeric loops | `numeric` | One fixed loop updating lexical and global values |
| String operations | `string` | One deterministic concat, case conversion, reverse, and substring sequence |
| Regex matching | `regex` | One fixed capture match |
| Bit-packed Life | `life` | 50 generations of the 128x100 flat64 glider workload |
| JSON encode/decode | `json` | One canonical JSON encode/decode pair |

The Life worker loads the algorithm from `examples/life_bitpacked.pl` and calls
its benchmark entry point. The normal command-line path remains unchanged.

## Measurement protocol

`dev/bench/performance_over_perl.py` uses persistent Perl and `jperl` workers.
For each workload and fresh-process pair it alternates which engine starts
first, then:

1. Runs ten one-second warmup windows.
2. Checks the last five windows for absolute throughput slope below 2% and
   coefficient of variation below 3%.
3. Continues warmup until stable or 60 windows have run. An unstable run is
   marked inconclusive and is excluded from the score.
4. Records fifteen one-second measurement windows after warmup stabilizes.
5. Compares checksums and retains window duration, process CPU time, operations,
   work units, and throughput.

The default is seven fresh-process pairs. The report calculates paired
PerlOnJava-over-Perl speedups, equal-weight geometric means, and deterministic
bootstrap 95% intervals. It also records the git commit, source/JAR hashes,
engine versions and flags, host details, load average, launch order, stderr
paths, and the full raw windows.

Run from the repository root after building the current JAR:

```sh
python3 dev/bench/performance_over_perl.py \
  --output /tmp/performance-over-perl.json
```

For a bounded smoke run, select one workload and fewer windows. Such a run is
for validating the runner only and must not be used for performance claims:

```sh
python3 dev/bench/performance_over_perl.py \
  --workloads life --pairs 1 --initial-warmup-windows 5 \
  --max-warmup-windows 5 --measurement-windows 3 \
  --window-seconds 0.1 --output /tmp/performance-over-perl-smoke.json
```

Every worker process is wrapped in `timeout` and runs at niceness 19 by default.
The output file is replaced atomically after each pair so interrupted runs keep
their completed raw samples.

## Analysis and profiler data

The runner establishes throughput and correctness evidence. JFR CPU,
allocation, GC, lock, thread, and code-cache profiles, async-profiler flame
graphs, compilation/inlining logs, and generated code are diagnostic data and
must be collected separately from scored windows so profiler overhead does not
change the comparison. Record commands and raw artifacts with the report.

On macOS, Instruments Processor Trace can report retired instruction totals,
but it requires Developer Tools tracing access to be enabled. The CPU Counters
template reports sampled bottleneck data and is not an instruction count.

## Acceptance

The benchmark is complete only when the seven-group portfolio geometric mean
is at least 1.05x Perl with a 95% interval entirely above 1.00x, both closure
and Life anchors meet that rule individually, and no scored workload is below
0.90x Perl. Behavior, JVM/interpreter parity, memory safety, and lifecycle
semantics remain separate correctness gates. See issue #1196 for the complete
acceptance contract and required diagnostic matrix.

## Progress tracking

### Current status: Phase 1 in progress

### Completed in this increment

- Added a persistent worker protocol for seven deterministic workload groups.
- Added warmup stabilization, alternating process-pair order, checksum checks,
  paired ratios, confidence intervals, metadata, and atomic JSON output.
- Added a load-only Life entry point that reuses the existing flat64 algorithm.

### Next steps

1. Run the smoke protocol on system Perl and both PerlOnJava backends.
2. Add profiler summaries for allocation rate and GC time without profiling the
   scored windows.
3. Collect Processor Trace instruction totals when the host enables tracing.
4. Run the full seven-group, seven-pair protocol and store the raw baseline.
5. Update this document with the baseline and hotspot attribution before
   selecting another production optimization.

### Open questions

- Can the reference host enable Processor Trace for the profiling account?
- Which non-macOS counter tool should the runner support for retired
  instructions when Instruments is unavailable?

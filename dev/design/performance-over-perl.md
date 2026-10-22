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

Issue [#1863](https://github.com/fglock/PerlOnJava/issues/1863) adds a separate
acceptance portfolio for measured 1:1 throughput: the original 20,000-call
method loop, the captured-closure loop, and bit-packed Life. Run it with
`--workloads method_anchor closure_anchor life`. The original method and
closure benchmark files are hashed into the report. Each worker selects a
small batch function for its workload so unrelated diagnostic branches do not
force an acceptance workload through interpreter fallback. The method receiver
is reset between batches so each batch returns a stable checksum on both
engines.

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
paths, worker commands, requested and observed process priority, reference Perl
source revision, and the full raw windows. A pair whose requested niceness
cannot be verified is retained in the raw report but excluded from summaries.
Reports also label a run `diagnostic_only` unless it requested the exact three
#1863 workloads, at least seven pairs, the full warmup and measurement windows,
niceness 19, and a pinned Perl source revision.
Pass `--perl-source-revision` with a release tag for the stable Perl reference
or the exact source commit for optimized blead. Keep the stable-release run and
blead run as separate reports.

Run from the repository root after building the current JAR:

```sh
python3 dev/bench/performance_over_perl.py \
  --output /tmp/performance-over-perl.json
```

For the #1863 acceptance anchors, pin the Perl source used by the executable:

```sh
python3 dev/bench/performance_over_perl.py \
  --workloads method_anchor closure_anchor life \
  --perl-source-revision v5.42.2 \
  --output /tmp/performance-over-perl-anchors.json
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

### Current status: Capture-cell lookup optimization is implemented and validated; acceptance measurements remain open

### Completed in this increment

- Added a persistent worker protocol for seven deterministic workload groups.
- Added warmup stabilization, alternating process-pair order, checksum checks,
  paired ratios, confidence intervals, metadata, and atomic JSON output.
- Added a load-only Life entry point that reuses the existing flat64 algorithm.
- Added the #1863 method and captured-closure acceptance anchors, kept each
  timed batch in a small selected function, and added unit coverage for anchor
  checksums, warmup stability, protocol eligibility, priority verification,
  and paired statistics.
- Found that the earlier worker put all workloads in one closure and triggered
  method-size fallback to the bytecode interpreter. The corrected worker
  matches the Perl checksums under system Perl and both PerlOnJava backends.
  Initial short-window ratios are not retained as performance evidence because
  they can stop before later JVM optimization.
- A JFR profile of the corrected method anchor samples
  `RuntimeCode.applyCachedMethod`, `ExecutionRuntimeState.callDepth`, generated
  code invocation, and scalar result coercion among dominant runtime costs.
  This points to per-call boundary work for the next optimization; it does not
  quantify inclusive costs or allocation per operation.
- Full one-second windows on the shared host did not reach the declared 2% slope
  and 3% variation limits within 30 warmup windows. Those pairs were excluded;
  no authoritative Perl ratio has been established yet. The requested process
  priority was verified as 19.
- The closure JFR profile found that the guarded captured-integer addition path
  performs repeated name lookups in `closedOverVariables`; `HashMap.getNode`,
  `LinkedHashMap.get`, and `RuntimeCode.tryDirectLeafIntegerAddition` were
  among its most frequent samples. A candidate caches the captured scalar cell
  array against the map identity and revision, invalidating it whenever capture
  bindings change. The new Perl-level regression covers in-place mutation and
  `PadWalker::set_closed_over` rebinding.
- The exact parent (`eaf1bfdb697c81999cf8bcc65b75e611fd6ead3b`) and candidate
  JARs were built successfully. Their SHA-256 hashes are respectively
  `e38b5feeda9aaa0fe26b829631f9f900618eda52cade59f757cf9a7d5e2a5bad` and
  `5bc349231238e4bcbbbd2b097f441d68a2b080c783ef3f0ad6e8f97e1f572e71`.
  Separate 20-second closure-anchor JFR recordings sampled 288,640,000 parent
  units and 323,760,000 candidate units (12.17% more in these sequential,
  profiler-instrumented runs). Parent samples included 484 `HashMap.getNode`
  and 626 `LinkedHashMap.get` frame appearances; the candidate had none. JFR
  allocation samples included 154 `RuntimeScalar[]` events for the parent and
  none for the candidate. These profiles support the cache's mechanism but are
  diagnostic only and do not establish a throughput ratio.
- The new cache regression passed system Perl, the JVM backend, and the
  interpreter; the full `nice -n 19 make` gate passed on the candidate. A full
  one-second-window comparison remains unscored because shared-host load
  prevented warmup stabilization. No 1:1 throughput acceptance result exists.

### Next steps

1. Run the #1863 anchors against pinned optimized blead and stable Perl builds,
   with seven stable pairs and verified process priority.
2. Retain unfixed-parent regression evidence for the capture-cache change and
   run anchor contract checks on both backends.
3. Repeat the parent/candidate closure comparison without profiling on a quiet
   host, then collect stable paired method, closure, and Life measurements.
4. Add allocation, GC, live-heap, and peak-RSS reports to paired evidence.
5. Continue the general call-boundary profile matrix and validate a broad
   call-path optimization before collecting another full portfolio.
6. Collect Processor Trace instruction totals when the host enables tracing.

### Open questions

- Can the reference host enable Processor Trace for the profiling account?
- Which non-macOS counter tool should the runner support for retired
  instructions when Instruments is unavailable?

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
closure benchmark files are hashed into the report. Each worker loads the
original benchmark definitions in a load-only mode so unrelated diagnostic
branches do not force an acceptance workload through interpreter fallback.
The method receiver resets only between timed windows, outside the scored
duration; each timed batch otherwise keeps the original loop and sink behavior.
A fixed 20,000-call checksum is checked on each worker, and the result checksum
for each measured window is verified against its operation count.

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

For the #1863 acceptance anchors, pin the Perl source used by the executable.
The primary comparison uses an optimized build of blead at an exact commit;
stable Perl 5.42.2 is a separate context report:

```sh
python3 dev/bench/performance_over_perl.py \
  --workloads method_anchor closure_anchor life \
  --perl /path/to/blead/bin/perl5.45.5 \
  --perl-source-revision <blead-commit> \
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

Issue #1863's 1:1 throughput criterion is met only when the method anchor's
paired speedup against optimized blead is at least 1.00x and the lower bound of
its 95% interval is at least 1.00x. Closure and Life must meet that same
criterion before claiming parity for the full #1863 portfolio. Stable Perl
5.42.2 is reported separately and does not substitute for blead. The broader
#1196 contract remains a distinct goal: its seven-group portfolio geometric
mean must be at least 1.05x with a 95% interval entirely above 1.00x, closure
and Life must meet that rule individually, and no scored workload may be below
0.90x. Behavior, JVM/interpreter parity, memory safety, and lifecycle semantics
remain separate correctness gates. See issue #1196 for its diagnostic matrix.

## Progress tracking

### Current status: The exact `origin/master` baseline is recorded; the method anchor remains well below 1:1

### Completed in this increment

- Added a persistent worker protocol for seven deterministic workload groups.
- Added warmup stabilization, balanced randomized process-pair order, checksum checks,
  paired ratios, confidence intervals, metadata, and atomic JSON output.
- Added a load-only Life entry point that reuses the existing flat64 algorithm.
- Added the #1863 method and captured-closure acceptance anchors by reusing the
  original benchmark definitions in load-only mode, and added unit coverage for anchor
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
- Earlier full-window runs were excluded when they did not reach the declared
  stability limits. The current exact-origin run completed seven stable pairs
  per workload, but macOS denied `setpriority`, so the harness marked every pair
  ineligible rather than treating the requested nice level as verified.
- Captured the directional `origin/master` baseline at
  `eaf1bfdb697c81999cf8bcc65b75e611fd6ead3b`, using JAR SHA-256
  `e38b5feeda9aaa0fe26b829631f9f900618eda52cade59f757cf9a7d5e2a5bad`, against
  optimized blead `83a6c8b26a8823eb966652414864eb07a3d2c6d4`. Recomputing paired
  ratios from the stable, checksum-matched windows while excluding only the
  unavailable priority check gives method 0.672x (95% bootstrap interval
  0.646–0.702), closure 1.309x (1.263–1.363), Life 1.051x (0.989–1.098), and
  portfolio 0.974x (0.957–0.996). These are diagnostic comparisons, not
  acceptance results; shared-host load rose during the run and priority could
  not be verified. Raw windows are retained in
  `dev/bench/reports/issue-1863-origin-master-eaf1bfdb-blead-83a6c8b.json`.
- The experimental method-fast-path candidate JAR (SHA-256
  `515d3056fee9835ed192199faeadf8881663b58a3d65f6696c43cbb4a22e4734`) measured
  0.644x on the method anchor (0.641–0.647) against blead. A same-run
  candidate-versus-origin artifact comparison measured 0.986x (0.965–1.005),
  showing no demonstrated improvement from the new method fast path. These runs
  have the same priority-verification limitation. Raw windows are retained in
  `dev/bench/reports/issue-1863-candidate-fastpath-blead-83a6c8b.json` and
  `dev/bench/reports/issue-1863-candidate-fastpath-vs-origin-method.json`.
- A 30-second profile of that experimental JAR produced 8,256 method batches
  (165,120,000 calls) with the expected checksum. Its sampled stacks still
  included `RuntimeCode.applyCachedMethod`; no experimental fast-path body frame
  appeared. That first fast-path attempt was removed because it showed no
  measurable gain. This profile does not distinguish whether the guard was not reached or
  whether its cost offset any saved work. The JFR is retained at
  `dev/bench/reports/issue-1863-method-fastpath.jfr` for follow-up diagnosis.
- Reworked the method fast path so compiler-proven hash keys propagate from the
  lazily compiled backend object to the stable named-sub placeholder. A scalar
  diagnostic call confirms that the guarded path activates. The candidate JAR
  (`4aaed76f272844d53dc6db293ccc8278d8bedee849a3eb54ae8b4ada8248784b`) measured
  0.683x against blead over three short stable pairs, versus the exact origin
  artifact at 1.007x in a same-run three-pair comparison. Since the baseline
  was 0.672x and all these runs could not verify requested process priority,
  this is only a small directional improvement, not accepted performance
  evidence. Raw samples are retained in
  `dev/bench/reports/issue-1863-candidate-registered-fastpath-blead-method.json`
  and `dev/bench/reports/issue-1863-candidate-registered-fastpath-method.json`.
  The full `nice -n 19 make` gate passed after the handoff and diagnostic-code
  cleanup.
- A 30-second JFR run of the corrected candidate completed 8,625 method
  batches (172,500,000 calls) with the expected checksum. Execution samples
  include the active `applyStableHashMutationMethodBody` path and no
  `applyCachedMethod` frames, confirming that the boundary fast path is used.
  Remaining samples include `MathOperators.addAssignIntegerInPlace`,
  `RuntimeScalar.setArithmeticIntegerResult`, `RuntimeCode.invokeCachedMethodHit`,
  and `RuntimeCode.prepareStableHashMutationMethodArguments`; these point to
  integer update/boxing and per-call method/argument guards as the next
  investigation targets. The JFR is retained at
  `dev/bench/reports/issue-1863-method-fastpath-active.jfr`.
- Cached the validated receiver's hash-dereference overload decision per runtime,
  invalidating it alongside inline method caches and bless-ID state. This
  removes repeated `NameNormalizer.getEffectiveBlessId` work from the guarded
  method path. The following full method-only comparison used seven requested
  pairs and 15 one-second windows; six checksum-matched pairs were complete.
  Recomputing those pairs while excluding only the unavailable priority check
  gives 1.047x over the exact origin JAR (95% interval 0.992–1.088). This was a
  useful check on the guard but its interval still includes parity.
- Recycled the small `RuntimeArray` and inline argument storage only after a
  compiler-proven stable-hash method completed, whose argument array cannot
  escape. A regression checks that a later ordinary method can return an
  escaped `@_` array without subsequent calls changing it. The next full
  method-only comparison had six complete stable pairs; recomputation gives
  1.113x over exact origin (1.032–1.215) and 0.720x blead (0.677–0.762).
  Requested priority remained unverifiable, the host load was high, and one
  candidate/oracle pair did not stabilize, so these are directional results,
  not acceptance evidence. The candidate was JAR SHA-256
  `1aaba79da113588d0bdd907d0a4603ca8e30529fca64ea711e078aeccd060a89`.
  Raw reports are retained in
  `dev/bench/reports/issue-1863-candidate-pooled-method-args-vs-origin-full-method.json`
  and `dev/bench/reports/issue-1863-candidate-pooled-method-args-vs-blead-full-method.json`.
- A 30-second JFR run of the pooled-argument candidate completed 7,347 batches
  (146,940,000 method calls) with the expected checksum. Its allocation samples
  had no `RuntimeArray` or `InlineMethodArgumentList` events, unlike the prior
  profile, which sampled both types repeatedly. The profile is retained at
  `dev/bench/reports/issue-1863-method-pooled-args-active.jfr`.
- Ran all three #1863 workloads with seven randomized pairs and 15 one-second
  measurement windows. Every pair was stable and checksum-matched, but macOS
  again could not verify the requested priority. Recomputing the paired
  results while excluding only that priority check gives method 0.745x blead
  (95% interval 0.721–0.776), closure 1.545x (1.498–1.603), Life 1.071x
  (1.007–1.149), and portfolio 1.072x (1.048–1.101). This is directional
  evidence only; method performance remains about 25% below blead and prevents
  a 1:1 result. Raw windows are in
  `dev/bench/reports/issue-1863-current-candidate-pooled-full-portfolio-blead.json`.
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
  `f3fe24e7f05ca4ac3147e0e244c0701cfa3198cc6bf8068d8c1c6be4e48dd13a`.
  Separate 20-second closure-anchor JFR recordings sampled 288,640,000 parent
  units and 323,760,000 candidate units (12.17% more in these sequential,
  profiler-instrumented runs). The candidate JFR used the pre-commit JAR hash
  `5bc349231238e4bcbbbd2b097f441d68a2b080c783ef3f0ad6e8f97e1f572e71`; it
  contains the same runtime source but a different injected Git build identity.
  Parent samples included 484 `HashMap.getNode` and 626 `LinkedHashMap.get`
  frame appearances; the candidate had none. JFR allocation samples included
  154 `RuntimeScalar[]` events for the parent and none for the candidate. These
  profiles support the cache's mechanism but are diagnostic only and do not
  establish a throughput ratio.
- The new cache regression passed system Perl, the JVM backend, and the
  interpreter; the full `nice -n 19 make` gate passed on the candidate. A full
  one-second-window comparison remains unscored because shared-host load
  prevented warmup stabilization. No 1:1 throughput acceptance result exists.
- The runner now randomizes launch order from a recorded seed while balancing
  which engine starts first. It can also run candidate and reference PerlOnJava
  JARs in the same paired protocol, recording both paths and hashes. Artifact
  comparisons remain attribution evidence and are explicitly ineligible for a
  Perl parity claim.

### Next steps

1. Profile the remaining method-body scalar result, arithmetic, and lifecycle
   costs; try one isolated optimization at a time and compare it with both the
   exact origin JAR and pinned blead.
2. Find a method optimization that closes the remaining method gap, then run
   the full portfolio on a host with verified priority and suitable load.
3. Meet the full seven-pair protocol before making an acceptance claim.
4. Run stable Perl 5.42.2 separately and add allocation, GC, live-heap, and
   peak-RSS reports to paired evidence.
5. Continue the general call-boundary profile matrix and validate a broad
   call-path optimization before collecting the final portfolio.
6. Collect Processor Trace instruction totals when the host enables tracing.

### Open questions

- Can the reference host enable Processor Trace for the profiling account?
- Which non-macOS counter tool should the runner support for retired
  instructions when Instruments is unavailable?

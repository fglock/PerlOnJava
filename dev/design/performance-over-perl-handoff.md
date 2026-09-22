# Performance parity with standard Perl: delivery and next steps

Issue: [#1196](https://github.com/fglock/PerlOnJava/issues/1196)

## Objective

Achieve at least 1:1 steady-state performance with standard Perl across the
scored workload portfolio, while preserving Perl behavior on both execution
backends. Startup and compilation are separate measurements.

Deliver independently justified improvements incrementally. Do not require
full parity before a useful, reviewable improvement can ship, but do not claim
acceptance from a host-noise override or a partial workload result.

## Measurement conditions

Performance measurements run on a deliberately loaded production simulation.
The production load is part of the condition being measured, rather than a
reason to wait for an idle host. Use the same load scenario, runner, standard
Perl executable, runtime options, source/JAR identity, power mode, and process
priority for parent and candidate. Capture the active-worker count, CPU load,
and process niceness at the start and end of every fresh process.

The default portfolio runner uses fresh processes, at least a twenty-second
fixed JVM warmup, and seven independent comparison blocks per workload. A
block has a balanced parent/candidate/candidate/parent order; alternate the
initial order between blocks. A fixed warmup separates JIT preparation from
measurement. Per-window variation is recorded as production-load information;
it is not by itself evidence that the JVM has failed to warm up.

Before comparing a new candidate, run the same build against itself in the
identical block order. This A/A calibration establishes the host's paired
noise envelope for every workload. Candidate evidence is usable only when its
paired improvement exceeds that envelope, survives a paired bootstrap interval,
and the checksums match. A result that overlaps the A/A envelope is
inconclusive, even when its unpaired median looks favorable. Use longer
measurement windows or more independent blocks when the calibration cannot
resolve the required gain; do not select only quiet windows.

Final throughput claims use normal, unprofiled runs. JFR is a bounded,
separate diagnostic capture: use it to identify recurring CPU, allocation, GC,
lock, and compilation costs under representative load, then validate any
change with the unprofiled paired protocol. `--allow-noisy-host` preserves
diagnostic analysis but never converts a single noisy portfolio into an
acceptance result.

## Delivery discipline

- Select broad runtime improvements, not recognition of one tiny benchmark
  method body. Prefer costs shared by ordinary programs: frames, dispatch,
  scalar results, hash access, and general regex behavior.
- Keep semantic guards explicit and retain an ordinary fallback. New Perl-level
  tests must first pass on standard Perl; runtime changes require JVM and
  interpreter coverage and a full `make` gate.
- Treat historical candidates as review leads only. Git history retains detail;
  this handoff retains current decisions, evidence, and next steps.
- Keep measured checkouts immutable until every benchmark child exits. A
  prematurely returned observation tool is not permission to restart a live
  benchmark or modify its source.
- Keep competing production jobs at the agreed low priority and record their
  actual niceness. Do not pause, kill, or otherwise reshape the production
  workload to make a benchmark look cleaner.

## Current retained change: static literal match-wrapper cache

JFR identified recurring source-resolution and compilation wrapper costs in
ordinary literal regular-expression matching, including
`RuntimeRegex.getQuotedRegex`, `compilePatternScalar`, and matcher/cache
allocation. The retained change caches a static match wrapper per call site and
`PerlRuntime`; it does not special-case a particular method implementation.

The cache applies only when the regex literal analyzer can prove a constant
pattern. Dynamic patterns retain the existing path. `qr//` identity behavior
is deliberately unchanged. Call-site identifiers are globally unique for JVM
emission so separately compiled eval strings cannot collide. Focused coverage
includes recursive/nested matching and runtime isolation, in addition to the
existing regex contract.

### Evidence

Semantic validation for the exact implementation source:

- Standard Perl: `static_match_regex_cache.t` and
  `static_match_regex_reentrancy.t` pass.
- Full project gate: `make` passes for the implementation commits.
- Production-load focused A/B/A screening showed roughly 2--3.2x higher raw
  PerlOnJava regex throughput for the candidate. The individual portfolios
  were host-noisy and are screening evidence only.
- A full seven-workload parent portfolio completed at
  `/private/tmp/perf-static-regex-cache-full-prod-parent-20260916/20260916T113036Z/portfolio.json`.
- The matching candidate portfolio completed at
  `/private/tmp/perf-static-regex-cache-full-prod-candidate-20260916/20260916T123606Z/portfolio.json`.
- Direct median comparison of their PerlOnJava raw windows measured regex at
  1,350,415 operations/s versus 510,204 for the parent (2.65x). The other
  workload medians ranged from 0.86x to 1.07x across noisy production runs;
  they do not establish unrelated regressions or gains. Both analyzer reports
  classify their inputs as `noisy-paired` and protocol-inconclusive, so this is
  an incremental, qualified delivery result rather than an official parity
  acceptance claim.

## Progress tracking

### Current status: Phase 3, production-load measurement protocol defined

### Completed phases

- [x] Select and validate a general literal-match regex cache (2026-09-16)
  - Added static call-site wrapper reuse in the JVM compiler and runtime.
  - Preserved dynamic patterns and `qr//` identity semantics.
  - Added focused recursive-cache and runtime-isolation coverage.
  - Ran standard-Perl regressions, full `make`, focused production brackets,
    and a complete parent/candidate production portfolio.
- [x] Define production-load calibration and paired-comparison policy
  (2026-09-22)
  - Treat host variability as measured uncertainty, not a blanket blocker.
  - Require A/A calibration, balanced fresh-process blocks, host-state
    capture, and unprofiled validation of profiler-selected changes.

### Next steps

1. Extend the portfolio runner and analyzer to emit the host-state snapshots,
   execute balanced parent/candidate blocks, and calculate paired A/A and
   candidate bootstrap intervals.
2. Calibrate the current delivery build under the normal production workload;
   use the result to set the smallest resolvable workload gain before selecting
   another optimization.
3. Continue profiling the remaining String and Life gaps from the delivered
   source baseline, targeting independently measured broad costs. Validate each
   retained candidate with the calibrated unprofiled protocol.

### Open questions

- What precision does A/A calibration achieve for each workload at the current
  worker count and power mode? The answer determines the required block count
  or window duration for a candidate claim.
- String and Life remain below standard Perl in the historical orientation
  portfolio and require separate cost attribution; do not widen experimental
  native-array representations without a general ownership proof.

## Completion criteria

The parity goal is complete only when the final source/JAR-matched portfolio
under the intended load demonstrates every scored workload at or above 1.00x
with a 95% lower confidence bound at least 1.00x, a portfolio geometric mean of
at least 1.05x with its interval wholly above 1.00x, Closure and Life each at
least 1.05x with intervals wholly above 1.00x, and no workload below 0.90x.
Checksums, fixed warmup, A/A-calibrated paired evidence, semantic tests, and
backend parity must also pass. JFR timing does not count toward this proof.

## References

- [Performance delivery selection](performance-delivery-selection.md)
- [Benchmark guide](../bench/README.md)
- [Profiling workflow](../../.agents/skills/profile-perlonjava/SKILL.md)

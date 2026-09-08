# Performance over Perl

Issue: [#1196](https://github.com/fglock/PerlOnJava/issues/1196)

## Goal and acceptance contract

The default JVM compiler backend must beat a pinned optimized maintained Perl
build on the reference host.  Startup, parsing, bytecode generation, and JVM
warmup are excluded.  Completion requires a portfolio geometric mean of at
least 1.05x Perl with a 95% confidence interval wholly above 1.00x, the same
result for the closure and Life anchors, no scored workload below 0.90x Perl,
and preserved Perl semantics and backend parity.

## Benchmark authority

`dev/bench/run_performance_portfolio.pl` is the versioned orchestrator and
`dev/bench/performance_workload.pl` emits deterministic per-window JSON.  The
default protocol uses seven alternating fresh-process pairs per workload, at
least ten one-second warmup windows, a maximum sixty-second stabilization
period, and fifteen one-second measurement windows.  Stability requires the
last five warmup windows to have a throughput slope below 2% and coefficient
of variation below 3%; otherwise the result is inconclusive.  Shorter runs are
allowed only for smoke testing and are marked `protocol_compliant: false`.

The scored groups are closure invocation, method dispatch/blessed-hash access,
lexical/global numeric loops, strings, regexes, bit-packed Life (word kernel),
and deterministic JSON::PP encode/decode.  Each window reports elapsed time,
iteration and operation counts, throughput, and a workload checksum.

Raw output must also identify the source/JAR, Perl/JDK versions and flags, host
state, process CPU time, allocation rate, GC time, and profiling artifacts.
The runner records source/JAR/launcher hashes, Perl/JVM identity and flags,
host state, and wall/process-CPU time.  `--jfr` emits one HotSpot profile
recording per PerlOnJava pair and hashes it into the JSON evidence.  It extracts
GC count, aggregate/longest pause, per-thread allocation counters, and sampled
allocation-event count. Async-profiler collection is still required before a
complete attribution report.

## Optimization gates

Do not merge a production shortcut based on sampling alone.  Gather JFR CPU,
allocation, GC, lock, thread, and code-cache events; async-profiler CPU and
allocation profiles; HotSpot compilation/inlining/deoptimization logs; and
generated-bytecode evidence.  Diagnostic-only call-layer ablations must report
exclusive and inclusive nanoseconds and allocated bytes per operation.

An optimization advances only when it explains at least 10% of an anchor or 5%
of portfolio time.  If call scaffolding qualifies, consolidate the general call
boundary before a closure-only fast path.  Primitive numeric specialization is
a separate later phase; preserve unsigned IV and Math::BigInt behavior.

## Progress Tracking

### Current Status: Phase 1 in progress — first candidate rejected as inconclusive

The initial runner and deterministic workload protocol are implemented. Its
JSON contract now captures wall/process-CPU window timing and execution
identity; JFR artifacts and GC/allocation summaries, plus workload and
portfolio-report contract tests, are in place. `analyze_performance_portfolio.pl`
computes paired medians, geometric means, deterministic bootstrap intervals,
and refuses to label a protocol-inconclusive input authoritative.

The first full candidate was collected at source commit `3b2da750b` on
2026-09-08 with the default 7-pair/15-window/60-second-max-warmup protocol.
It completed semantically but was **rejected as non-authoritative**: the
strict last-five-window stability rule failed in 19 engine/workload runs (CV
3–17%, slope up to 35%). Its compact analysis measured a 0.139x portfolio
geometric mean (bootstrap 95% CI 0.097–0.192) and a 0.158x closure median;
these values are diagnostic only, not acceptance evidence.

The seven closure JFR recordings nevertheless identify a qualifying general
call-boundary bottleneck: `RuntimeCode.apply` occurred in 15,771 of 15,956
sampled execution stacks (98.8%). This exceeds the 10% anchor threshold by a
wide margin. The next implementation phase must consolidate the general call
boundary, not add a closure-only shortcut.

### Completed Phases

- [ ] Phase 1: Benchmark authority (candidate protocol and analyzer complete;
  a quiet-host conclusive baseline remains required)
- [ ] Phase 2: Attribution report
- [ ] Phase 3: Call-boundary redesign
- [ ] Phase 4: Primitive numeric specialization
- [ ] Phase 5: Generated-code/JIT quality

### Next Steps

1. Repeat the complete default protocol on a quiet reference host; accept only
   a `protocol_compliant: true`, `conclusive: true` bundle through the analyzer.
2. Collect async-profiler CPU/allocation, HotSpot inlining, and bytecode
   evidence for the general `RuntimeCode.apply` boundary.
3. Add diagnostic call-layer ablations before changing `RuntimeCode.apply`.

### Open Questions

- Which reference host can be kept sufficiently quiet for the acceptance gate?
- Should Life retain the application-level flat/parallel workloads alongside
  the deterministic word-kernel score?

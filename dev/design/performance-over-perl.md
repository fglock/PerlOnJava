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

On a reference host that cannot be made quiet, the analyzer's explicit
`--allow-noisy-host` mode may classify a completed default protocol as
`noisy-paired`. It never permits an acceptance claim. It can only establish a
decisive negative baseline when the paired portfolio bootstrap interval's
upper bound is below 1.00x Perl; the report retains the host state and noisy
quality label.

The scored groups are closure invocation, method dispatch/blessed-hash access,
lexical/global numeric loops, strings, regexes, bit-packed Life (word kernel),
and deterministic JSON::PP encode/decode.  Each window reports elapsed time,
iteration and operation counts, throughput, and a workload checksum.

Raw output must also identify the source/JAR, Perl/JDK versions and flags, host
state, process CPU time, allocation rate, GC time, and profiling artifacts.
The runner records source/JAR/launcher hashes, Perl/JVM identity and flags,
host state, and wall/process-CPU time.  `--jfr` emits one HotSpot profile
recording per PerlOnJava pair and hashes it into the JSON evidence. Recordings
are capped at 32 MB by default (`--jfr-max-size` may set another bounded JFR
size); extract a compact report and remove raw recordings when the
investigation ends. It extracts GC count, aggregate/longest pause, per-thread
allocation counters, and sampled allocation-event count. Async-profiler
collection is still required before a complete attribution report.

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

### Current Status: Phase 1 complete — decisive noisy-host baseline recorded

The initial runner and deterministic workload protocol are implemented. Its
JSON contract now captures wall/process-CPU window timing and execution
identity; JFR artifacts and GC/allocation summaries, plus workload and
portfolio-report contract tests, are in place. `analyze_performance_portfolio.pl`
computes paired medians, geometric means, deterministic bootstrap intervals,
and refuses to label a protocol-inconclusive input authoritative, including
when noisy-host mode establishes a one-sided negative conclusion.

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

A second full candidate was collected at source commit `e0db10de7` on
2026-09-08 on the same loaded reference host. It was protocol-compliant,
semantically matched, and contained seven fresh pairs for each workload, but
five engine/workload samples did not stabilize (one closure Perl sample and
four regex samples). Its explicit `--allow-noisy-host` analysis is therefore
**noisy-paired, not authoritative**; it establishes only a decisive negative
result. The portfolio geometric mean was 0.146x Perl (bootstrap 95% CI
0.103–0.199; upper bound below 1.00), and every individual workload interval
was below 1.00. This is sufficient to prioritize the identified call-boundary
bottleneck, but cannot satisfy the positive 1.05x acceptance gate.

Phase 2 attribution was completed with a 47-second JFR closure capture on
2026-09-08 (source commit `5b5b69569`) recorded 2,756 execution samples, of
which 1,445 (52.4%) contained `RuntimeCode.apply`; its frames occurred 3,476
times because nested calls can put more than one facade frame on a sampled
stack. Of 13,239 weighted allocation samples (106.2 GB estimated allocation
weight), 73.0 GB (68.7%) were on stacks containing that facade. The largest
allocation classes were `RuntimeScalar` (35.9 GB), `Object[]` (25.0 GB), and
`RuntimeList` (10.4 GB). The same recording saw 164 young GCs, one monitor
enter event, no thread parks, and no code-cache-full events. The raw 2.7 MB
recording and temporary expanded files were removed after these results were
extracted.

A separate HotSpot compilation capture recorded 24 `RuntimeCode.apply` and
50 generated `anon*.apply` compilation records, including 95 deoptimizations
but no code-cache-full event. The selected compilation tasks contained 1,866
failed inline decisions, 235 because a callee was too large. A bytecode-size
probe while compiling/running the closure workload emitted 270 generated
classes; the largest generated `apply` body was 8,683 bytes, exceeding the
2 KB target in [the apply-bytecode design](reduce-apply-bytecode.md). These
independent CPU, allocation, compilation, and bytecode signals qualify the
general call boundary for redesign.

Async-profiler 4.5 became available on the host later that day. A separate
closure capture used its stack filter for `RuntimeCode.apply`, so each flat
profile below is scoped to call-boundary-inclusive stacks rather than reported
as whole-process time. The 30-second CPU profile collected 3,004 samples:
`RuntimeCode.apply` itself was 10.99% exclusive CPU, independently exceeding
the 10% anchor gate. Its direct supporting operations were also prominent:
caller-warning restoration (6.09%), frame-level cleanup (4.96%), argument
popping (4.26%), and callee-warning setup (1.90%). The allocation profile ran
until the target's normal exit (21.6 seconds of the requested 30) and collected
125,262 samples / 32.83 GB of sampled allocation on those stacks. Its leading
classes were `Object[]` (27.34%), `RuntimeScalar` (24.07%), `RuntimeList`
(9.02%), `ArrayList` (5.98%), and `RuntimeArray` (5.91%). This completes the
required async-profiler CPU/allocation evidence; all profile files and the
workload log were removed after compact extraction.

### Completed Phases

- [x] Phase 1: Benchmark authority (2026-09-08; protocol/analyzer complete,
  decisive noisy-host negative baseline recorded; a quiet-host conclusive
  acceptance baseline remains required)
- [x] Phase 2: Attribution report (2026-09-08; JFR, HotSpot, bytecode, and
  async-profiler evidence qualify the general `RuntimeCode.apply` boundary)
- [ ] Phase 3: Call-boundary redesign
- [ ] Phase 4: Primitive numeric specialization
- [ ] Phase 5: Generated-code/JIT quality

### Next Steps

1. Collect async-profiler CPU/allocation, HotSpot inlining, and bytecode
   evidence for the general `RuntimeCode.apply` boundary.
2. Add diagnostic call-layer ablations before changing `RuntimeCode.apply`.
3. Repeat the complete default protocol on a quiet reference host before
   making any positive performance-acceptance claim.

### Open Questions

- Which reference host can be kept sufficiently quiet for the acceptance gate?
- Should Life retain the application-level flat/parallel workloads alongside
  the deterministic word-kernel score?

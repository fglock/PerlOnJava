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

### Current Status: Phase 3 in progress — copy-on-write pristine arguments evaluated

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

A third full candidate at source commit `f774d3b7c` finally produced the
required **stable authoritative baseline** on 2026-09-08. All seven default
pairs for every workload completed, every warmup stabilized, and all semantic
checks matched. Its portfolio geometric mean was 0.144x Perl (bootstrap 95% CI
0.102–0.197); closure was 0.155x and Life was 0.371x. The slowest workload was
JSON at 0.0083x. The report is authoritative evidence, not a passing
acceptance result: its confidence interval lies wholly below 1.00x and it
fails the 1.05x portfolio, anchor, and minimum-workload gates. This is the
baseline against which the call-boundary redesign must be measured.

| Workload | Median ratio to Perl | Bootstrap 95% CI |
| --- | ---: | --- |
| Closure | 0.155x | 0.153–0.161x |
| Method | 0.167x | 0.156–0.175x |
| Numeric | 0.298x | 0.296–0.301x |
| String | 0.277x | 0.257–0.284x |
| Regex | 0.173x | 0.171–0.205x |
| Life | 0.371x | 0.368–0.376x |
| JSON | 0.0083x | 0.0084–0.0097x |

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

The first Phase 3 candidate, commit `91b081e17`, centralized the two general
instance paths' direct invocation, scalar coercion, closure protection, and
diagnostic mark in `RuntimeCode.invokeCallable`. Its focused permanent
boundary-semantics test passed on Perl, JVM, and interpreter, and its exact
commit passed the complete `make` gate. A subsequent full default portfolio
was semantically successful but protocol-inconclusive on the loaded host: its
geometric mean was 0.147x Perl (bootstrap 95% CI 0.105–0.200), compared with
the 0.144x authoritative baseline. It is diagnostic evidence only and cannot
support an acceptance claim.

Post-candidate async-profiler captures confirm that this safe consolidation
did not remove the dominant boundary. A 20-second unfiltered CPU capture
contained `RuntimeCode.apply` on 1,995 of 2,221 sampled stacks (89.82%). A
15-second allocation capture attributed 99.91% of its collapsed allocation
weight to stacks containing that method. The raw profiles and workload logs
were removed after extracting these compact figures. The next candidate must
reduce the frame/argument lifecycle structurally while retaining the covered
caller, warning, control-flow, context, and argument-alias semantics.

A second Phase 3 candidate, commit `5402b099a`, moved the complete general
call-frame lifecycle into one private method with an explicit fresh-versus-
shared `@_` parameter. It added permanent coverage for exceptional boundary
unwind, including argument aliasing and restored frame stacks; the test passed
on Perl, JVM, and interpreter, and the exact commit passed `make`. Its complete
seven-pair portfolio was stable and authoritative but still failed acceptance:
0.1472x Perl (bootstrap 95% CI 0.104–0.200), with a 0.00924x minimum workload.
This is only a modest change from the 0.144x baseline and is not a passing
performance result.

Post-candidate async-profiler again confirms that the general boundary remains
dominant: `RuntimeCode.apply` appeared on 1,984 of 2,125 closure CPU stacks
(93.36%) and 99.72% of the collapsed allocation weight in a separate
15-second capture. The full portfolio, analysis, CPU profile, allocation
profile, and workload logs were removed after compact extraction. Future work
must remove frame/argument lifecycle cost rather than only centralizing it;
if that structural redesign cannot materially reduce this attribution, advance
to primitive numeric specialization as the next larger phase.

A follow-up general candidate made the active lexical-pad map and JVM closure
tracking collections lazy: ordinary calls retain their stack entries but avoid
allocating empty maps/lists unless they create a closure, return one, or expose
a live lexical. Its permanent boundary tests passed on Perl, JVM, and
interpreter, and a clean `make` gate passed. The completed seven-pair
portfolio on the routinely loaded host was protocol-inconclusive and nearly
flat (0.1481x Perl; bootstrap 95% CI 0.105–0.200; minimum 0.00976x), so this
is retained only as a safe allocation reduction, not evidence of a material
speedup. The temporary portfolio directory, log, and report were deleted.

The next Phase 3 candidate, commit `c32d45d54`, made the pristine `@_`
snapshot copy-on-write. An active argument frame initially retains the live
argument array and snapshots only immediately before a mutation; the permanent
`runtime_code_pristine_args_cow.t` coverage verifies both entry-time
`@DB::args` values and its scalar-slot aliasing. The test passed on system
Perl, the JVM backend, and the interpreter, and the exact commit passed
`make`. A 49-recording JFR/diagnostic portfolio measured the closure named-
argument boundary at 1,432 ns/op inclusive, 567 ns/op exclusive, and 3,154 /
1,261 B/op inclusive/exclusive; it is attribution evidence only because JFR
perturbs timing. The corresponding default seven-pair portfolio was stable
and authoritative but still failed acceptance: 0.1457x Perl (bootstrap 95% CI
0.1036–0.1976), with a 0.00930x minimum workload. This nearly flat result
retains the change for its safe lazy-copy behavior, but it does not justify a
positive performance claim. All JFR recordings, portfolio directories, logs,
and reports were removed after compact extraction.

The next Phase 3 candidate, commit `059614214`, replaced the unconditional
per-call `JvmClosureFrame` allocation with a shared stack sentinel, creating a
real frame only when a captured closure is made. This remains a general call
boundary change: it retains nesting, returned-closure protection, and capture
cleanup rather than adding a closure-only dispatch path. The permanent
returned-closure capture-lifetime regression passed on system Perl, JVM, and
interpreter, and the exact commit passed `make`. Its 49-recording JFR and
call-layer portfolio measured the closure named-argument boundary at 1,372
ns/op inclusive, 543 ns/op exclusive, and 3,082 / 1,238 B/op
inclusive/exclusive (333 million operations); JFR timing is attribution only.
The non-JFR seven-pair portfolio was protocol-inconclusive on the loaded host,
with 0.1458x Perl (bootstrap 95% CI 0.1039–0.1973) and a 0.00954x minimum
workload. The small diagnostic change does not demonstrate the required
structural reduction, so it is retained only as a safe allocation improvement.
All profile recordings, portfolios, logs, and reports were removed after
compact extraction.

### Completed Phases

- [x] Phase 1: Benchmark authority (2026-09-08; stable authoritative
  baseline recorded, decisively below the positive performance target)
- [x] Phase 2: Attribution report (2026-09-08; JFR, HotSpot, bytecode, and
  async-profiler evidence qualify the general `RuntimeCode.apply` boundary)
- [ ] Phase 3: Call-boundary redesign (safe general-body consolidation and
  lazy argument/closure frame reductions evaluated; remaining frame lifecycle
  work)
- [ ] Phase 4: Primitive numeric specialization
- [ ] Phase 5: Generated-code/JIT quality

### Next Steps

1. Design a structural general-boundary candidate that makes inactive caller,
   context, warning, and control-flow bookkeeping lazy without changing
   caller/`@DB::args`, warning, control-flow, context, or alias semantics.
   If that cannot materially reduce `RuntimeCode.apply` exclusive cost or
   allocation, begin Phase 4 primitive numeric specialization.
2. Extend permanent boundary coverage for each lazily materialized state, then
   use the call-layer diagnostics on closure and method before and after each
   candidate; retain only compact JSON summaries and require a material
   reduction in the `RuntimeCode.apply` exclusive cost or allocation.
3. Repeat the complete default protocol after a candidate passes focused
   semantic coverage; only a stable report meeting every acceptance gate may
   make a positive claim.

### Latest candidate evidence (2026-09-09)

The plain implicit-`$_` foreach alias candidate (`b5300e777`) safely avoids
wrapper/root bookkeeping when replacing one existing plain scalar alias with
another.  Its complete default seven-pair portfolio was protocol-compliant and
conclusive, but still decisively failed the acceptance gates: closure 0.1594x,
method 0.1665x, numeric 0.3350x, string 0.2911x, regex 0.1870x, life 0.3815x,
and JSON 0.0102x Perl.  Numeric improved from the preceding 0.3021x result,
but no scored workload reached the required 0.90x floor.

The required 49-recording JFR plus call-layer-diagnostic portfolio also
completed successfully.  It confirms that general named-argument calls still
carry substantial boundary allocation and inclusive time; for the numeric
workload, the sampled named-argument category measured about 2.41 MB/op
inclusive allocation and 289 us/op inclusive time.  JFR timing is attribution
evidence only.  The 160 MB fixed temporary profile directory, ordinary
portfolio directory, logs, and commit-message scratch file were deleted after
extracting these figures.

This candidate is retained as a small safe loop improvement, but its evidence
advances the active work to Phase 4: prove and introduce primitive numeric
representation/code-generation only for statically safe scalar flows, with a
full semantic fallback for overload, taint, references, warnings, localization,
and aliasing.

### Open Questions

- Which reference host can be kept sufficiently quiet for the acceptance gate?
- Should Life retain the application-level flat/parallel workloads alongside
  the deterministic word-kernel score?

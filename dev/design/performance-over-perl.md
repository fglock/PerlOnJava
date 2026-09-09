# Performance over Perl

Issue: [#1196](https://github.com/fglock/PerlOnJava/issues/1196)

## Goal and acceptance contract

The default JVM compiler backend must beat a pinned optimized maintained Perl
build on the reference host.  Startup, parsing, bytecode generation, and JVM
warmup are excluded.  Completion requires a portfolio geometric mean of at
least 1.05x Perl with a 95% confidence interval wholly above 1.00x, the same
result for the closure and Life anchors, no scored workload below 0.90x Perl,
and preserved Perl semantics and backend parity.

This contract does not require every workload to exceed 1.00x: it allows a
0.90x minimum while requiring the portfolio and both anchors to reach 1.05x.
Meeting only the minimums is insufficient. The implementation plan below is
not evidence that these targets are attainable; feasibility remains unproven
until measured candidates satisfy the complete contract.

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

### Current Status: Phase 4 first slice — activation proven; semantic proof and
primitive-local representation outstanding

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
- [x] Phase 3: Call-boundary redesign (2026-09-09; safe general-body
  consolidation plus lazy argument, closure-frame, and foreach-alias reductions
  were semantically sound but insufficient to meet any performance gate)
- [ ] Phase 4: Primitive numeric specialization (guarded lexical-integer flow
  first slice in progress)
- [ ] Phase 5: Generated-code/JIT quality

### Next Steps

Apply the forward-only experiment policy below. Start by deriving the feasibility
budget from recorded evidence while repairing prototype correctness.
Do not defer closure, Life, and JSON attribution until the numeric optimizer
is finished. The numbered implementation steps are dependencies where stated,
not a requirement to exhaust numeric work before addressing other workloads.

1. **Preserve activation evidence while extending the prototype.** The analyzer
   now propagates loop context into direct loop bodies, and compiler/runtime
   tests demonstrate one emitted and executed closed-lexical specialization.
   Keep positive bytecode/execution assertions and negative unsupported-flow
   assertions for every extension; do not mistake selection of the current
   boxed helper for evidence of primitive-local code generation.
2. **Establish sound eligibility and fallback.** Resolve declarations by binding
   identity, in statement order, with scoped dataflow and explicit invalidation
   at calls, joins, escapes, closure capture, eval, localization, and unknown AST
   forms. Traverse argument lists and branches; reject ties, magic, debugger
   exposure, and aliases unless explicitly supported. Reanalysis must clear
   stale annotations. The current name-based set and partial escape traversal
   are insufficient proof of an unaliased lexical.
   Restrict native payloads explicitly to supported signed representations:
   `BigInteger` is a `Number`, so the current guard permits truncation through
   `getLong()`. Preserve Perl's divisor-sign remainder, checked overflow,
   unsigned IV, `Math::BigInt`, taint, lexical warnings, and `use integer`
   operator selection. Preserve assignment evaluation order and scalar-cell
   effects (including pos invalidation, observers, and returned lvalues);
   `set(long)` is not automatically equivalent to `set(RuntimeScalar)`.
   Add permanent compiler/runtime and Perl-level coverage for each condition;
   validate the Perl oracle first, demonstrate regressions on the unfixed
   parent, and require both backends plus `make` on the corrected commit.
3. **Implement actual primitive flows.** Once steps 1–2 pass, retain proven
   integers in JVM primitive locals across nested arithmetic expressions and
   loop iterations, boxing at observable boundaries. The current helper still
   loads boxed operands and stores a boxed numeric payload each assignment.
   Start with a closed lexical kernel; then separately prove safe reads of the
   foreach iterator, global accesses, and unsigned word operations needed by
   the unchanged numeric and Life workloads. Require bytecode and allocation
   evidence that the intended hot loop benefits, including bailout reentry
   without replaying side effects. Do not rewrite scored workloads to fit the
   optimizer.
4. **Resume the closure objective in issue #1196.** Phase 3 evaluated general
   boundary reductions but did not solve call overhead. Reuse the completed
   exclusive/inclusive CPU and allocation attribution, then analyze safe
   zero-argument captured-lexical calls and simple scalar returns. Use guarded
   callee identity and capability checks for direct invocation, avoiding
   argument/result containers and repeated warning setup where proven safe.
   Preserve or decline `@_`, caller/context inspection, dynamic warnings and
   hints, eval, debugger hooks, overload/ties, non-local exits, capture lifetime,
   redefinition, and returned lvalues. Add activation, fallback, and parity
   tests before comparing the closure anchor and original issue reproducer.
5. **Close the whole-portfolio gap.** Numeric specialization cannot by itself
   satisfy the acceptance contract. At the last recorded full candidate, the
   0.90x floor requires roughly 88x improvement for JSON (0.0102x), 5.6x for
   closure (0.1594x), 5.4x for method, 4.8x for regex, 3.1x for string, 2.7x for
   numeric, and 2.4x for Life. These are planning ratios, not predictions.
   Attribute JSON::PP first alongside the two issue anchors; measure how much
   shared call/scalar improvements recover, then address residual method,
   string, and regex costs. For Phase 5 inspect generated method size, inlining,
   deoptimization, and allocation elimination on the actual hot paths. Keep
   each optimization tied to measured cost rather than assuming one technique
   will solve all workloads.
6. **Measure candidates and close against the original contract.** Freeze a
   source commit and matching JAR after all workers finish. Compare parent and
   candidate with the same pinned Perl/JDK, host, checksums, and flags; record
   activation counts and hashes with compact results. Two-pair diagnostics are
   exploratory and cannot establish acceptance or a regression against an
   unrelated historical run. After semantic and focused cost-reduction gates
   pass, run the complete default seven-pair/seven-workload non-JFR portfolio
   and a separate 49-recording JFR/call-layer attribution run, with the required
   async-profiler and JIT evidence. Retain compact tracked summaries before
   removing raw artifacts. Recheck the original closure and Life reproductions
   under controlled conditions as companion evidence. Completion requires
   portfolio and closure/Life anchor geometric means at least 1.05x Perl,
   their 95% intervals wholly above 1.00x, every workload at least 0.90x, and
   unchanged semantics on both backends. Update the design, changelog, and PR
   with exact-commit evidence; issue #1196 remains open until its objective is
   demonstrated.

### Feasibility gate and performance budgets

Before committing to a larger optimization, produce a tracked budget for each
scored workload using the completed baseline and candidate summaries below.
Do not rerun baseline collection to begin this work. The following historical
ratios illustrate the size of the problem; they are not current measurements
or promised speedups. Speedup required is target ratio divided by current ratio;
time reduction required is one minus current ratio divided by target ratio.

| Workload | Recorded ratio to Perl | Minimum target | Required speedup | Required time reduction |
| --- | ---: | ---: | ---: | ---: |
| Closure | 0.1594x | 1.05x | 6.59x | 84.8% |
| Life | 0.3815x | 1.05x | 2.75x | 63.7% |
| Numeric | 0.3350x | 0.90x | 2.69x | 62.8% |
| Method | 0.1665x | 0.90x | 5.41x | 81.5% |
| String | 0.2911x | 0.90x | 3.09x | 67.7% |
| Regex | 0.1870x | 0.90x | 4.81x | 79.2% |
| JSON | 0.0102x | 0.90x | 88.24x | 98.9% |

Assign per-workload throughput budgets that also produce a portfolio geometric
mean of at least 1.05x. The table gives necessary individual thresholds only;
their geometric mean would still miss acceptance. Budget additional headroom
for measurement uncertainty and guards/fallbacks, without treating an estimate
as a confidence interval.

For every proposed optimization, record baseline time per operation, the
non-overlapping fraction of elapsed time it can affect, expected residual
cost, guard hit rate, fallback cost, allocation/GC impact, and measured result.
Use Amdahl's relation as a screening bound: if fraction `f` of time is improved
by factor `s`, overall speedup is `1 / ((1 - f) + f / s)`. Even eliminating
that fraction entirely gives only `1 / (1 - f)`. For example, removing 11% of
closure time can yield at most about 1.12x improvement, far short of the
required 6.59x. Inclusive stack occurrence is not an exclusive elapsed-time
fraction; do not substitute sampled frame presence or allocation weight for
`f`, double-count overlapping costs, or multiply gains measured against the
same parent. Profile a new structural candidate only to answer a remaining
question about its changed costs or JIT behavior, under the forward-only policy.

Use three bounded feasibility workstreams, reusing all completed attribution.
Each new experiment must test an implemented change or a previously unanswered
question, with a controlled comparison and explicit go/no-go result:

1. **Closure:** measure the removable call/argument/result machinery on the
   actual captured-lexical anchor. Demonstrate guarded direct invocation and
   scalar returns with enough coverage to approach its time budget. If the
   residual generic machinery already exceeds the budget, redesign that
   boundary before adding more small allocation reductions.
2. **Life/numeric:** demonstrate primitive values surviving the real hot loop,
   including unsigned word operations and the relevant iterator/storage
   accesses. Measure remaining scalar, container, and call costs. A fast
   isolated arithmetic expression does not qualify if the scored loop never
   selects it or still spends most of its time outside it.
3. **JSON:** explain the roughly 88x floor gap early. Check backend/fallback
   execution, generated code/JIT behavior, calls, strings, regexes, containers,
   and allocation against the same JSON::PP workload and input. Identify a
   combination of general compiler/runtime improvements whose residual time
   can fit the budget. Do not replace JSON::PP or recognize benchmark-specific
   source patterns to satisfy the score. If call/numeric specialization cannot
   account for the gap, add a separate architectural workstream before claiming
   the portfolio has a credible completion path.

After each experiment, update the budget with measured residual costs. Advance
to wider implementation when activation and semantic gates pass and the
evidence supports reaching the remaining budget. If an optimistic bound still
misses the target, revise the architecture or investigate another dominant
cost; do not repeat full portfolios on a structurally insufficient candidate.
Diagnostic ablations may estimate removable overhead but cannot validate
production semantics or count as acceptance results.

The immediate deliverable is the corrected activation/semantic test set plus
a feasibility report for closure, Life, and JSON, with a concrete next change
and quantified remaining gap for each. If no viable path emerges, report that
the objective remains unmet and identify the measured limiting cost. Do not
weaken thresholds, remove slow workloads, or mark issue #1196 complete merely
because the listed implementation phases were finished.

### Forward-only experiment policy

Completed experiments are closed. Missing raw artifacts are intentional and
are not a reason to recreate them. Read the compact evidence in this document
before planning any run; use its conclusions as inputs to the next change.
Do not re-establish known call-boundary dominance, rerun rejected Phase 3
candidates, or collect another baseline-only portfolio. Preserve evidence
quality labels: historical results support prioritization, not a controlled
claim about a new candidate.

| Completed work | Evidence to reuse | Next action enabled |
| --- | --- | --- |
| Benchmark protocol and baseline collection | `3b2da750b` and `e0db10de7` instability/noisy-host evidence; stable `f774d3b7c` baseline at 0.144x | Use the established protocol and baseline; do not rediscover host-noise behavior |
| Phase 2 attribution | `5b5b69569` JFR; completed async-profiler CPU/allocation, HotSpot compilation, and generated-bytecode analysis | Design removal of measured call/scalar machinery |
| General call-boundary consolidation | `91b081e17`, `5402b099a`, and their portfolio/profile summaries | Centralization alone is insufficient; change the representation or invocation path |
| Lazy pad/closure tracking and argument snapshots | Recorded lazy-map candidate and `c32d45d54`, including its 49-recording diagnostic portfolio | Reuse semantic coverage; do not repeat lazy-allocation variants already evaluated |
| Lazy closure-frame sentinel | `059614214` portfolio and 49-recording call-layer/JFR diagnostics | Small frame-allocation savings do not close the gap |
| Foreach scalar-alias bookkeeping | `b5300e777` full portfolio and 49-recording diagnostics | Use the recorded workload ratios and residual costs to budget the next structural change |
| Initial numeric prototype smoke measurements | Completed one-pair run and two-pair 0.3305x result; prototype activation remains unproven | Fix and prove activation/semantics before any further numeric timing |

Before launching a new experiment, record in this design document:

1. The new hypothesis and the source change or previously unanswered question.
2. Which completed result it builds on, and why that result cannot answer the
   new question.
3. The smallest required run, expected observable change, and decision rule.
4. After completion, the exact source/JAR identity, compact result, conclusion,
   and next implementation action. Mark the experiment closed before cleanup.

New correctness tests, activation/bytecode checks, and required validation of
changed code are forward progress. A parent control run is permitted only as
part of measuring a genuinely new candidate when a contemporaneous comparison
is necessary; do not restart the historical experiment sequence. Full default
portfolios and separate 49-recording attribution runs are reserved for new
candidates that pass the documented semantic and focused improvement gates.
Do not launch them solely because a new session or developer takes over.

The next execution order is: prove and repair numeric activation and semantic
gaps; derive closure/Life/JSON budgets from existing summaries; implement the
next structural candidate or investigate a specific uncovered residual cost;
then collect only the new evidence needed to decide whether it advances.

### Phase 4 initial-slice evidence (2026-09-09)

An initial guarded code-generation prototype is present in
`NumericFlowAnalyzer`, `EmitBlock`, `EmitVariable`, and
`NumericFlowOperators`. Its intended scope is a `my` scalar initialized from an
integer literal and a direct, single binary `+`, `-`, `*`, or `%` reassignment
inside a `for` loop. The helper attempts to avoid an intermediate
`RuntimeScalar`, but activation and fallback correctness have not been proven.
Review identified the loop-body traversal and semantic gaps listed in Next
Steps. Treat this as unfinished work, not a validated primitive representation.
Bitwise operations remain on the existing operator path.

`primitive_numeric_flow.t` checks results for a closed lexical loop, overload,
reference alias visibility, and overflow. Prior runs reported success on system
Perl, JVM, interpreter, and `make`; these results do not prove execution of the
specialization or its fallback. A temporary
two-pair numeric-only diagnostic was semantically conclusive but deliberately
protocol-inconclusive; it measured 0.3305x Perl. The historical 0.3350x result
is not a controlled parent comparison, so this difference proves neither an
improvement nor a regression. Its temporary
portfolio, analysis, and logs were removed. Do not run the full portfolio or
49-recording JFR suite for this slice.

The next Phase 4 increment must prove activation and fix the identified semantic
gaps before extending the analyzer. The portfolio numeric kernel cannot enter this slice yet:
its expression is nested and includes the implicitly aliased `$_` loop value.

### Phase 4 activation repair (2026-09-09)

The first-slice analyzer had a concrete activation defect: it recursively
analyzed a `for` body as an ordinary block, so body assignments always received
`insideLoop = false` and could never select the annotated JVM emission path.
`NumericFlowAnalyzer` now preserves loop context while analyzing loop and
`continue` blocks. A permanent compiler-level test constructs a closed lexical
loop and asserts that its direct addition is annotated; it also asserts that a
prior scalar reference suppresses the annotation. The positive test fails on
the immediately preceding prototype because its body assignment was never
annotated.

The runtime guard now accepts only `Integer` and `Long` payloads. `BigInteger`
is also represented as `RuntimeScalarType.INTEGER`, but using `getLong()` on it
would truncate; wide values therefore take the ordinary `MathOperators` path.
The existing Perl-level overflow, overload, and reference-alias tests passed
on system Perl, the JVM backend, and the interpreter. The repaired working
tree passed `make` (2026-09-09, 3m27s), including both compiler-level
activation/fallback tests. A bounded `--disassemble` compilation of the
Perl-level test emitted one `NumericFlowOperators.assignAdd` invocation for
the closed-loop positive case; its successful JVM execution is therefore also
an execution check of the selected path. This is an activation/correctness
gate only: the helper still boxes operands and writes a boxed payload, so it
is not allocation or bytecode evidence for primitive locals.

Feasibility remains unchanged by this repair. Recorded budgets require at
least 6.59x for Closure, 2.75x for Life, and 88.24x for JSON just to meet their
individual 1.05x/0.90x thresholds. The completed call-boundary evidence
establishes that small numeric allocation reductions cannot fund Closure or
JSON; the next bounded work must separately attribute Life's word kernel and
JSON::PP residuals while the numeric work proves a true unboxed closed lexical
flow. No portfolio or JFR attribution run is warranted for this activation-only
candidate.

### JSON feasibility experiment (planned 2026-09-09)

Hypothesis: the JSON::PP workload's 88.24x floor gap is primarily in generic
Perl call/scalar/container machinery rather than JSON text itself. The completed
closure recordings cannot answer that question because JSON has substantially
different method, hash, array, and string behavior. Run one fresh
Perl/PerlOnJava JSON pair with ten one-second warmup windows, fifteen one-second
measurement windows, JFR, and call-layer diagnostics. The expected observable
is a compact breakdown of JVM execution/allocation and general call-layer cost;
it is protocol-inconclusive by design and cannot establish a performance claim.
If call-layer-exclusive cost cannot plausibly explain most of the 98.9% required
time reduction, reject further call-boundary micro-optimizations for JSON and
investigate its highest non-call allocation/CPU path next.

### JSON feasibility experiment (completed 2026-09-09)

The fresh one-pair JFR/call-layer run at source `0f66116af` was deliberately
protocol-inconclusive. Its PerlOnJava median was 724.5 operations/s versus
67,002.9 for Perl. The recording had 1,616 execution samples; 757 (46.8%) had
`ErrorMessageUtil.extractSourceLines` as their top frame, reached through
`InterpretedCode.withCapturedVars` while interpreter closures were created.
This answered the planned question: a generic closure-copy representation cost,
not JSON text handling, was a qualifying target.

Candidate `1ba3b14ff` caches the immutable token-derived source lines and
invalidates them only when source filtering replaces tokens. Its focused cache
invalidation test and exact `make` gate passed. The same one-pair JFR diagnostic
recorded a 2,548.9 operations/s median and only 3 of 700 execution samples in
`extractSourceLines`; the artifact also recorded 7,476 allocation samples and
106 young collections. The preceding recording had 7,629 allocation samples
and 83 young collections. These JFR timings and allocation-sample counts are
attribution evidence, not a controlled performance claim, but the disappearance
of the sampled hotspot confirms the representation change took effect.

This candidate does not close JSON's 88.24x minimum gap or establish a
portfolio improvement. The call-layer diagnostics still show large inclusive
costs in shared-argument instance calls, so the next JSON experiment must
attribute the remaining non-closure body/collection costs with a profiler that
does not include JFR timing perturbation. The two temporary recording
directories and expanded reports were removed after extracting this summary.

### JSON string-offset fast path (rejected 2026-09-09)

The post-cache async-profiler CPU sample identified
`PerlUtfString.scanOffsetByPerlCodePoints` (12.85%) and
`scanCodePointCountPerl` (3.53%) as residual JSON costs. A bounded
marker-free-string candidate replaced their manual scans with
`String.offsetByCodePoints` and `String.codePointCount`, retaining the
marker-aware scanner and clamping semantics as fallbacks. The exact `make`
gate passed, but the CPU profile replaced the scanner frames with
`Character.offsetByCodePoints` at 21.15% CPU. The candidate was therefore
reverted in `fa056834e`; no performance claim is retained.

Do not retry this through Java's generic code-point helper. The next bounded
JSON experiment should instead attribute a residual with a demonstrably lower
per-operation implementation cost, beginning with repeated closure metadata
setup such as `InterpretedCode.scanMyVarRegisters` (3.01% in the same profile),
or a specialized logical-index representation that preserves Perl's U+FFFD
marker semantics.

### JSON boundary-only scanner experiment (rejected 2026-09-09)

A second string experiment kept the existing manual traversal but avoided
constructing `PerlStep` records when callers need only the next UTF-16
boundary. It preserved ordinary, supplementary, and U+FFFD-marker boundaries;
the focused test and a retry of the full `make` gate passed (the initial gate's
parallel Gradle result files vanished after the focused test had passed).

The supervised 15-second async-profiler recording nevertheless rejected the
implementation: `scanOffsetByPerlCodePoints` was 16.53% and
`scanCodePointCountPerl` 9.96% of 1,597 samples, both higher than the prior
attribution sample. The run completed with the expected semantic checksum but
was not throughput-stable, so this is diagnostic rather than a score claim.
The uncommitted implementation was removed. Future string work needs a
different representation or a call-site algorithm change; do not retry either
generic Java code-point helpers or a standalone boundary-only helper.

### JSON closure metadata cache (completed 2026-09-09)

`InterpretedCode.withCapturedVars` creates a closure instance over an unchanged
bytecode array, but previously rescanned that entire array to rediscover scope
cleanup registers. Closure copies now clone the template's already-computed
`myVarRegisters` metadata instead. A focused unit test verifies that the copy
retains the cleanup register and remains independently mutable. The full
`make` gate passed in 4m37s.

The matching 15-second async-profiler CPU sample collected 1,634 samples:
`scanMyVarRegisters`, previously 3.01%, no longer appeared in the report's hot
frames. The run completed with the expected semantic checksum, but competing
machine load changed its throughput during later windows; it is therefore
attribution evidence only, not a portfolio or acceptance result. Retain the
safe metadata cache and next investigate the still-dominant manual logical
string-offset scan (14.20% in this profile) with a Perl-semantics-preserving
specialization rather than the rejected generic Java helper.

### JSON positive substr-alias refresh (completed 2026-09-09)

Collapsed stacks traced the remaining logical offset scans through
`RuntimeScalar.refreshSubstrLvalues` and
`RuntimeSubstrLvalue.currentSubstring`. For the common positive-offset,
nonnegative-length alias, refresh previously counted the whole parent before
walking the requested two boundaries. The two existing boundary walks already
clamp to end-of-string, so refresh now omits that redundant count. Focused
tests cover parent mutation and an oversized positive offset; the full `make`
gate passed in 5m16s.

The supervised 15-second async-profiler CPU sample completed with the expected
semantic checksum. In 1,600 samples `scanCodePointCountPerl` no longer
appeared among hot frames and `scanOffsetByPerlCodePoints` was 9.62%, compared
with 16.53%/9.96% for the immediately preceding rejected boundary-helper
experiment. The unstable benchmark throughput makes this attribution evidence,
not a portfolio claim, but retain the semantically narrow traversal reduction.

### JSON deferred string-append headroom (completed 2026-09-09)

`RuntimeScalar` retains a `StringBuilder` across repeated `.=`, but its first
append previously used Java's small default growth headroom. New deferred
builders now reserve 64 characters (or the known first suffix length) while
preserving normal later growth and transfer into compound-assignment results.
Focused tests cover direct materialization and transfer; the full `make` gate
passed in 5m50s.

The supervised 15-second CPU profile completed with the expected semantic
checksum. In 1,598 samples `AbstractStringBuilder.ensureCapacityInternal` was
9.01%, down from 11.81% in the preceding retained substring-refresh profile.
This is attribution evidence under an unstable benchmark environment, not a
portfolio score claim; retain the bounded general allocation reduction.

### JSON live substr slice cache (completed 2026-09-09)

Collapsed JSON stacks attributed nearly all sampled `String.substring` work to
`RuntimeSubstrLvalue.currentSubstring`. A live alias now caches its computed
slice only for the exact immutable parent `String`; parent replacement causes a
fresh slice, while refresh and later reads share the same cached text. Focused
tests cover mutation, end clamping, and same-parent reuse. The full `make` gate
passed in 5m18s.

The supervised 15-second CPU profile completed with the expected semantic
checksum. Across 1,702 samples `String.substring` fell to 2.82%, from 11.14%
in the preceding headroom profile. This is attribution evidence rather than a
controlled portfolio score, but retain the cache because it eliminates repeated
allocation on an existing live-alias representation.

### Cumulative diagnostic portfolio (2026-09-09)

After the retained JSON source-line, closure-metadata, live-substr, and
deferred-append changes, one alternating fresh-process pair with ten fixed
warmup windows and fifteen measurement windows completed successfully. It is
explicitly non-conclusive (one pair and fixed warmup), but provides the first
current end-to-end signal: Closure 0.1611x, Method 0.1625x, Numeric 0.3481x,
String 0.3011x, Regex 0.1890x, Life 0.3790x, and JSON 0.0339x Perl. JSON is
about 3.3x the older 0.0102x portfolio result, yet still needs roughly 26.5x
to meet its 0.90x necessary floor. No acceptance threshold has been met.

The next work must be structural: the current collapsed JSON profile puts
generic `RuntimeCode.call` below the interpreter loop far ahead of the
remaining leaf operations. Continue profiling/generalizing call and closure
representation only with permanent semantic coverage; do not treat another
string micro-optimization as a plausible route to the remaining JSON gap.

### Current JSON call-boundary attribution (2026-09-09)

A fresh 15-second collapsed-stack recording after the retained string changes
confirmed that `RuntimeCode.call` is the largest named interpreter descendant
(595 sampled stack units), with closure creation next (335). The native
argument path already inserts ordinary `RuntimeScalar` arguments directly as
aliases; its unavoidable per-call allocation is the `RuntimeArray`/`@_` frame
and the associated caller, pristine-argument, lexical, and cleanup state.
Those features are observable through aliasing, `caller`, `@DB::args`, tail
calls, weak captures, and non-local returns. Therefore the next candidate must
redesign or specialize a complete call-frame representation with permanent
coverage for those semantics, rather than deleting an individual frame step.

### JSON copy-on-write argument-frame stack (completed 2026-09-09)

`PristineArgsFrame` was an unconditional wrapper allocation for every
subroutine entry, even though its `@DB::args` copy is correctly deferred until
`@_` mutates. The execution state now keeps parallel reusable lists of the
active argument arrays and their optional copy-on-write snapshots. It retains
the former LIFO ordering, shared-`@_` handling, original-argument lookup, and
per-frame snapshot timing while removing the wrapper allocation from ordinary
calls. The existing `runtime_code_pristine_args_cow.t` coverage exercises the
observable mutation contract; the full `make` gate passed in 3m46s.

One fresh JFR-backed JSON pair is diagnostic only, but confirms the intended
allocation change: no `PristineArgsFrame` allocation sample remains. Its JSON
median was 2,487.9 operations/s (0.0374x Perl) versus 2,451.8 operations/s
(0.0365x) in the immediately preceding same-shaped recording. JFR allocation
samples fell only slightly (11,950 versus 11,703) because `RuntimeArray` and
its backing list remain the much larger call-boundary allocation. Retain this
semantic-preserving reduction; investigate a safe fresh-argument representation
next, not eager removal of caller-compatible state.

### Recycled recursion-depth state (completed 2026-09-09)

JFR allocation samples also identified `ExecutionRuntimeState.CallDepthState`
as churn from normal calls. That state exists only to maintain per-runtime
depth and one-warning-per-chain behavior for deep recursion. The runtime now
recycles a released state after removing its code key, while retaining distinct
objects for concurrently active code entries. A focused Java test verifies both
properties; the full `make` gate passed in 4m40s.

The matching JFR-backed JSON diagnostic contained no `CallDepthState`
allocation samples, confirming that the pooled steady state takes effect. Its
0.0285x JSON result is not comparable to the preceding recording because the
host was heavily CPU-contended; retain it only as allocation attribution. The
next structural target remains the necessary `RuntimeArray` argument frame and
its backing storage, which dominate remaining call-boundary allocation.

### Caller-warning single-source fast path (completed 2026-09-09)

Every normal call records the caller's disabled-warning categories for
`caller()`. When exactly one lexical source was active, the runtime still
allocated a transient `LinkedHashSet` union before the existing snapshot step.
It now passes that one source directly and constructs a union only when both
sources contribute. This preserves the snapshot taken by `pushCallerBits`.
The full `make` gate passed in 4m49s.

The matching JFR-backed JSON diagnostic has no allocation sample rooted at
`RuntimeCode.callerDisabledWarningCategories`; its 0.0328x JSON result is an
allocation-attribution signal only, not an acceptance measurement. Retain the
fast path, while treating fresh argument-array storage and interpreter dispatch
as the remaining structural costs.

### Scalar-context substr proxy elimination (rejected 2026-09-09)

JSON profiling showed that `JSON::PP`'s many ordinary `substr` reads create
live `RuntimeSubstrLvalue` observers, whose eager parent refresh dominates the
remaining leaf samples. An attempted scalar-context fast path returned plain
scalars rather than registering a proxy. The full gate rejected it: lvalue
escape, taint, nested/live-alias, and `\substr` reference tests failed. In
this runtime, scalar evaluation context alone is not sufficient to prove that
a `substr` result cannot later be observed as an lvalue.

The stronger follow-up also forced direct `\\substr(...)` operands into lvalue
context on both backends, but the full gate still failed in concat assignment,
regex-eval taint, live-extent, magical-parent, taint-mode, and tied-handle
coverage. Therefore neither direct-reference handling nor call context is a
complete escape analysis; retain the proxy until a dataflow representation can
prove the result cannot cross one of those boundaries.

The restored-baseline JSON JFR diagnostic after this rejection recorded 1,250
`refreshFromParent` samples, 918 logical-offset scans, and 427
`ArrayList.removeIf` samples in observer cleanup; call dispatch was only about
70 samples. This makes lvalue-representation dataflow the next qualifying
target, but these sampling counts are attribution evidence only, not a
throughput score.

The uncommitted candidate was removed. Any future reduction must carry an
explicit non-escaping rvalue representation from parsing/code generation, or
redesign proxy reads so invalidation is lazy without exposing stale direct
scalar state. Do not retry a context-only operator shortcut.

A later standard-Perl probe also confirmed that assigning an ordinary
three-argument `substr` result to a lexical stores a snapshot: subsequent
parent replacement is not visible through string, numeric, or boolean reads.
That result rules out treating the existing universally-live proxy as the
semantic model for deferred refresh. Any pull-based observer design must first
separate ordinary rvalue `substr` at code generation from references and other
lvalue-observing forms.

### ASCII logical-offset scanner shortcut (rejected 2026-09-09)

The remaining JSON JFR samples were dominated by
`PerlUtfString.scanOffsetByPerlCodePoints`. An ASCII-only loop was tried ahead
of the existing general logical-character reader, with a fallback at the first
non-ASCII character. Differential Perl coverage included ASCII clamping plus
Unicode scalars after an ASCII prefix, and the full `make` gate passed in
3m48s. Both execution backends also passed the focused test.

The post-change one-pair JFR portfolio nevertheless regressed JSON median
throughput to 1,866.8 operations/s, from 2,090.7 in the immediately preceding
same-shaped capture. The scanner was still the leading sampled frame (1,402
samples). HotSpot already optimizes the original reader path more effectively
than the extra manual ASCII branch, so the experiment was removed. Do not
retry this shape without a controlled multi-pair score or a representation that
proves ASCII for the whole source string.

### Direct-assignment substr snapshots (completed 2026-09-09)

The first sound rvalue slice is a direct scalar-assignment RHS only. Both
backends now pass an internal snapshot context only when the RHS node itself is
`substr`; calls, references, list assignment, compound assignment, loops, and
runtime context continue to construct the live proxy. The snapshot preserves
the source byte-string kind and taint provenance. A focused test passed under
system Perl with `-T`, and the full `make` gate passed in 3m50s.

This establishes semantic coverage, not a portfolio score. Profile the JSON
workload before expanding the dataflow boundary; do not generalize it from
scalar context or an indirect expression.

The follow-up one-pair JSON JFR diagnostic was host-contended and therefore
not a score, but it did not show the expected structural reduction: it recorded
1,611 `refreshFromParent` samples and 926 logical-offset scans, versus 1,250
and 918 in the preceding baseline capture. Retain the correct snapshot
semantics, but do not expand this direct-assignment slice as a JSON optimization;
the hot calls predominantly feed other immediate consumers.

### Direct-comparison substr snapshots (completed 2026-09-09)

Direct `substr` operands of numeric and string comparisons now use the same
metadata-preserving snapshot context on both backends. This is limited to the
dedicated comparison emitters; regex binding, calls, lists, aliases, and every
indirect expression retain a live proxy. The focused standard-Perl comparison
test passed, and the full `make` gate passed in 4m04s. Measure this slice before
claiming any JSON reduction.

The one-pair JFR diagnostic is attribution-only, but the structural result is
positive: logical-offset scans fell from 918 to 548 samples and
`refreshFromParent` from 1,250 to 1,145. Its 2,345.3 operations/s result is not
comparable to the prior captures under host variation. Retain this constrained
slice and investigate the remaining proxy creation/refresh callers rather than
generalizing from comparison context.

### JSON closure deparse-source reuse (completed 2026-09-09)

An `InterpretedCode` closure copy inherits its bytecode and source metadata,
but its private constructor nevertheless rebuilt the immutable deparse source
text from `ErrorMessageUtil` before `withCapturedVars` replaced that value with
the template's copy. Closure construction now explicitly inherits the existing
text, including an intentionally absent value when it exceeded the deparse
limit. Focused Java tests verify both object identity and absent-text reuse;
the full `make` gates passed in 3m42s for the initial form and 3m37s for the
corrected absent-text form.

A fresh one-pair JFR-backed JSON diagnostic of the corrected form reduced
`sourceTextFromErrorUtil` from 144 sampled frames to one, confirming that even
absent deparse metadata is now inherited rather than rebuilt. Its 1,998.6
operations/s (0.0294x Perl) is lower than the preceding 2,519.5 operations/s
same-shaped diagnostic, so it is attribution-only host-noise data rather than a
performance score. Retain the eliminated redundant reconstruction and continue
with a structural call-frame or interpreter-dispatch target.

### Foreach alias runtime-state reuse (completed 2026-09-09)

The retained range-backed implicit-`$_` foreach fast path previously resolved
the current runtime three times per iteration through global-map facades. It
now obtains that runtime state once and updates the same two state-owned maps
directly. This leaves the pre-existing slow path intact for reference aliases,
localization, and all first-installation bookkeeping. Focused implicit-foreach
coverage passed on both JVM and interpreter backends, and the full `make` gate
passed in 5m51s.

One fresh numeric JFR pair is attribution evidence only on the contended host.
It reduced `ThreadLocalMap.getEntry` samples from 1,589 to 1,546 and
`getGlobalVariable` samples from 308 to 298. Its relative median rose from
about 0.342x to 0.348x Perl; retain the small safe reduction, but do not treat
it as a scored acceptance result or a route to the remaining 1x gap.

### Empty pos-cache invalidation guard (completed 2026-09-09)

Every scalar assignment invalidates its `pos()` state, but the common runtime
has no position entries at all. `RuntimePosLvalue.invalidatePos` now resolves
the runtime once and returns before scalar indirection or map lookup when that
per-runtime cache is empty. A populated cache retains the prior canonical
storage lookup and in-place lvalue reset. The full `make` gate passed in 3m59s,
and the focused 22-case `pos`/`\\G` test passed on both JVM and interpreter
backends.

The following one-pair numeric JFR capture is diagnostic only. It reduced
`ThreadLocalMap.getEntry` samples from 1,546 to 1,434 and `HashMap.getNode`
samples from 85 to 22. The contended relative median rose from about 0.348x to
0.374x Perl. Retain this general scalar-write reduction, while requiring a
controlled multi-pair portfolio before assigning it an acceptance score.

### Plain numeric scalar-copy shortcut (rejected 2026-09-09)

An exact-`RuntimeScalar`, non-string, non-reference branch was tried ahead of
general growing-string transfer preparation in `RuntimeScalar.set`. The full
gate and focused JVM/interpreter numeric recurrence coverage passed, and JFR
reduced sampled `RuntimeScalar.set` frames from 186 to 55. Its one-pair numeric
median nevertheless fell from about 0.374x to 0.368x Perl while
`ThreadLocalMap.getEntry` samples increased. The candidate was removed; do not
retry a duplicated plain-copy branch without a controlled multi-pair result or
a specialization that eliminates a larger operation than the preparatory
branches.

### Compile-time no-taint arithmetic dispatch (completed 2026-09-09)

The numeric profile showed that ordinary arithmetic spent most of its sampled
runtime lookups checking a taint mode which is fixed by the compiler options.
JVM emission now selects no-taint variants of `+`, `*`, and `%` (including
their uninitialized-warning variants) only when the compilation is not `-T`.
`-T`, interpreter execution, and unselected operators retain the existing
runtime taint-propagation methods. The full `make` gate passed in 3m41s; the
focused ordinary numeric recurrence and all 147 `-T` taint-mode checks passed.

One fresh numeric JFR pair is attribution evidence rather than an acceptance
score, but it removed the dominant propagated-taint lookup: `ThreadLocalMap`
samples fell from 1,434 to 188. Its contended relative median rose from about
0.374x to 0.393x Perl. Retain the dispatch split and profile the resulting
integer-result allocation path before widening it to other operators.

### Snapshot `substr` observer elision (completed 2026-09-09)

The JSON profile exposed a mismatch between the existing snapshot context and
the runtime implementation. `substrImpl` constructed and registered a live
`RuntimeSubstrLvalue` before recognizing `SNAPSHOT` context and returning a
separate scalar snapshot. The discarded proxy stayed as a weak observer of the
JSON::PP parser buffer, so every later buffer mutation refreshed otherwise
unobservable slices and repeatedly scanned their logical offsets.

Snapshot context now returns its existing value/type/taint-preserving scalar
before creating a live proxy. Four-argument replacement and ordinary lvalue
contexts still create the proxy. The existing snapshot regression passed on
system Perl and both PerlOnJava backends, and the full `make` gate passed in
4m21s. In a one-pair JFR diagnostic, `refreshSubstrLvalues` disappeared and
logical-offset scan samples fell from 760 to 2; JSON throughput was 3,577.3
operations/s versus 2,145.6 in the immediately preceding host-contended
capture. This is strong causal attribution but not an acceptance score. Keep
the source-level snapshot boundary; the remaining JSON work is interpreter and
general call/scalar cost, not another scanner micro-optimization.

### Interpreted method inline cache (completed 2026-09-09)

The JSON::PP workload executes bundled Perl through `BytecodeInterpreter`.
Its `CALL_METHOD` opcode previously used uncached `RuntimeCode.call`, unlike
generated JVM method calls, so each monomorphic parser-method call performed
normal method dispatch. The opcode now invokes the existing guarded
`callCached` implementation with a cache key derived from the interpreted code
identity and bytecode PC. Cache hits retain the regular Perl call boundary,
including caller frames, warning scopes, mortal cleanup, and the established
invalidations for method redefinition and `@ISA` changes.

The existing method-cache regression passed under system Perl and both
PerlOnJava backends; the full `make` gate passed in 5m29s. A one-pair JSON JFR
diagnostic raised throughput from 3,577.3 to 4,436.4 operations/s, and sampled
`BytecodeInterpreter.execute` frames fell from 97 to 49. This is attribution
evidence, not a portfolio acceptance result. Retain the cache and next reduce
the remaining interpreted call-frame and scalar/container work rather than
duplicating method-resolution fast paths.

### Native positive-word shifts (completed 2026-09-09)

The Life word kernel repeatedly shifts values constrained below `2^32`, but
the generic unsigned shift fast path still converted every positive native IV
to `BigInteger` before shifting and masking. Native non-negative IVs now use
Java's 64-bit `<<` and logical `>>>` operations directly; a result whose high
bit is set still follows the existing unsigned-result representation. Negative
IVs and existing wide UVs remain on the `BigInteger` path, preserving their
high-bit semantics.

The new word-shift regression passed on system Perl and both PerlOnJava
backends, as did existing 64-bit unsigned coverage. The full `make` gate
passed in 5m38s. In a one-pair Life JFR diagnostic, sampled `BigInteger`
shift frames fell from 11 to zero, and throughput rose from 1,575,997.7 to
2,026,756.0 operations/s (about 0.484x Perl in that capture). This is
attribution evidence, not acceptance evidence; retain the representation
split and profile remaining call, array, and scalar-cell work before expanding
unsigned specialization.

### Small scalar-cache range for aggregate sizes (completed 2026-09-09)

`RuntimeArray.scalar()` correctly returns the shared immutable integer cache,
but the former `-100..100` range omitted the common size `128`. The Life
kernel therefore allocated a read-only scalar every time it evaluated
`@grid` in scalar context. The shared immutable range now covers
`-256..256`; this changes neither mutability nor aliasing behavior, only
which already-read-only integer instances are reused.

The full `make` gate passed in 4m03s. A post-change Life JFR capture no longer
sampled `RuntimeArray.scalar()` through `getScalarInt(128)`. Its remaining
scalar allocations are result cells for shifts and bitwise operations, which
cannot be removed by widening this cache. A three-pair non-JFR confirmation
reported relative medians of 0.531x, 0.647x, and 0.489x Perl (median 0.531x).
This is a bounded allocation reduction, not acceptance evidence; next target
the result-cell and intermediate-expression representation rather than growing
the cache further.

### Direct absent-array-element stores (completed 2026-09-09)

Both backends previously lowered ordinary `$array[index] = value` through an
out-of-range `RuntimeArrayProxyEntry`, even though the assignment immediately
vivifies and stores the slot. `RuntimeArray.setElement` now creates the same
distinct mutable cell directly for an absent element of a non-shared plain
array and returns that cell as the assignment lvalue. Tied, readonly,
autovivifying, shared, negative, and existing-element paths retain their
established proxy/get-and-set behavior. JVM code generation and the bytecode
`ARRAY_SET` handler both use this guarded runtime entry point.

The new chained-assignment regression passed under standard Perl and both
PerlOnJava backends. The initial full gate exposed a shared-thread validation
failure, which was fixed by explicitly retaining the proxy path for shared
arrays; the corrective full `make` gate then passed in 6m. A Life JFR capture
recorded no `RuntimeArrayProxyEntry` allocation samples, versus 577 before the
JVM lowering. It still sampled 638 required `RuntimeScalar` slot creations in
`setElement`. The diagnostic pair reached 2,091,623.3 operations/s versus
3,444,690.8 for Perl (0.607x), but remains attribution evidence rather than
portfolio acceptance evidence. Next eliminate only proven intermediate result
cells; do not weaken the lvalue/store boundary.

### Constant direct-hash-key fetches (completed 2026-09-09)

Interpreted `$hash{bareword}` and `$hash{'literal'}` accesses previously
materialized a temporary read-only scalar only to stringify it for
`RuntimeHash.get`. A new `HASH_GET_CONST` opcode passes the bytecode string
pool entry directly to that API. `local $hash{key}` deliberately retains
`HASH_GET_FOR_LOCAL`, because it needs a re-resolvable lvalue proxy across
hash replacement.

The new bareword, quoted-key, writable-lvalue, and `local` regression passed
under system Perl and both PerlOnJava backends; the full `make` gate passed in
5m36s. A JSON JFR diagnostic reduced sampled literal materializations only
from 1,129 to 1,118, confirming that direct hash keys are not the major
literal source. Its relative result is attribution-only and inconclusive under
host variation. Retain this safe opcode reduction, but prioritize interpreted
call/frame and regex/literal representation work rather than expanding another
small constant-key specialization.

### Interpreted cached-method argument-array elimination (completed 2026-09-09)

`BytecodeInterpreter.CALL_METHOD` already holds its evaluated arguments in a
`RuntimeArray`, but previously copied that list into a transient
`RuntimeBase[]` before entering `RuntimeCode.callCached`. The cached-method
entry now accepts that existing argument array directly and constructs only
the required fresh aliased `@_` frame containing the invocant. Native generated
callers retain their `RuntimeBase[]` entry point. Tied invocants, cache misses,
AUTOLOAD, caller/warning scopes, cleanup marks, and argument aliasing all use
the same frame construction helper.

The expanded cache regression verifies that a warmed method cache receives its
invocant and aliases a caller scalar through `@_`; it passed under system Perl
and both PerlOnJava backends. The full `make` gate passed in 7m16s. A one-pair
JSON JFR diagnostic contained no `ArrayList.toArray` allocation stack rooted
at interpreter `CALL_METHOD`; remaining `RuntimeBase[]` samples arise from
closure creation, register frames, and generated callers. The host-contended
diagnostic's 3,091.4 versus 49,611.9 operations/s is attribution-only and not
an acceptance result. Retain the removed redundant allocation, but prioritize
the mandatory per-call `@_` frame and interpreter representation rather than
claiming it closes the structural call-cost gap.

### Direct scalar/list argument frames for interpreted calls (completed 2026-09-09)

Normal interpreted subroutine and cached-method calls formerly converted a
scalar or `RuntimeList` argument expression to a temporary `RuntimeArray`,
then immediately created the actual aliased `@_` frame from that temporary.
Both call paths now pass scalar/list expressions directly to their existing
runtime entry points, which construct the final frame once. Calls whose
arguments are already a `RuntimeArray`, and `&sub` shared-argument calls,
retain the exact pre-existing frame path.

New scalar/list alias regressions passed under system Perl and both
PerlOnJava backends; the expanded method-cache regression verifies the same
behavior for a warmed cached method. The complete `make` gate passed in
8m35s. The exact-source one-pair JSON JFR diagnostic no longer contains the
former `CALL_SUB` or `CALL_METHOD` intermediate-array allocation lines; its
remaining 112 method-site and 48 subcall-site `RuntimeArray` samples are the
final required frames. It measured 4,544.8 PerlOnJava versus 48,452.9 Perl
operations/s on a warm but single pair. This attribution result is not a
protocol-compliant acceptance measurement; retain the safe reduction and
continue with interpreter and call-frame representation work.

### Lazy interpreter caller-frame resolver reuse (completed 2026-09-09)

Every interpreted subroutine or method call keeps deferred call-site metadata so
that `caller` can resolve the exact source line only when it is observed. The
former representation allocated both that metadata record and a capturing
lambda for every call. The record now carries its code object and bytecode PC,
while a shared method reference resolves the source information on demand. This
retains lazy lookup and the existing caller-stack lifetime, while removing the
per-call lambda allocation.

System Perl caller tests and the focused direct/multiline caller cases passed;
the full `make` gate passed in 6m27s. A fresh one-pair JSON JFR diagnostic
contains `LazyCallerInfo` samples but no `BytecodeInterpreter` lambda
allocation class, confirming the intended structural removal. Its single-pair
throughput is attribution-only under the contended host and is not an
acceptance score. Keep this reduction, but prioritize the still-required
caller-frame object and the larger interpreter representation costs.

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

# Performance over Perl handoff

## Resume here — reviewed 2026-09-13

Performance parity is **not achieved**. The immediate work is a full
integration portfolio for the newly retained byte-string/integer concatenation
path, then Life, regex, and string body/representation costs. Call-boundary
attribution has already been collected; repeating that phase is not the
default next step.

Work continues on `wip/performance-preflight-20260909-133542` for issue
[#1196](https://github.com/fglock/PerlOnJava/issues/1196). Resolve the actual
branch tip, worktree, and PR before integration; historical commit IDs may
precede rebases. The literal-alternation direct-search candidate `a59f374f3`
is rejected and reverted to its retained parent `a1cb8b828` after a
reverse-order parent repeat. Do not reopen it without a different cost model.

Use this file for decisions and work order. The
[experiment archive](performance-over-perl-experiments.md) preserves the full
historical evidence, including rejected experiments and their semantic proofs.
Read the relevant linked experiment before proposing a successor. Update this
summary in place after each decision; append detailed evidence to the archive.

## Current evidence and required improvement

The latest complete portfolio is runtime `0d5af0d99` through documentation-only
successor `88a7a929c`. It is a **high-load diagnostic**: 0.95317x Perl
geometric mean, 95% interval 0.88073–1.06369x, inconclusive and
non-authoritative. It confirms the byte-string paths as local reductions but
does not establish portfolio acceptance. Its workload ratios guide priorities;
they do not certify a positive acceptance result.

The retained byte-string/integer concatenation candidate `0d5af0d99` has
exact-parent string-only high-load evidence of 1.07289x median and 1.07016x
geometric mean across seven same-index comparisons against `e5344d2b6`.
Sequential host-contended runs do not establish a causal interval, but the
stable material local reduction clears the selection threshold. Its required
full integration portfolio is next; the existing complete portfolio below is
the preceding `e5344d2b6` baseline and does not change acceptance status.

| Workload | Diagnostic ratio to Perl | Point-estimate gain needed | Priority |
| --- | ---: | ---: | --- |
| String | 0.61656x | 1.62x to 1.00x | Largest remaining deficit; isolate body allocation and representation cost |
| Life | 0.64607x | 1.63x to 1.05x anchor | Next selection: residual arithmetic/array/result transport |
| Regex | 0.68432x | 1.46x to 1.00x | Search-path candidate rejected; target general result/cursor or search body cost |
| Closure | 1.16258x | Revalidate uncertainty and 1.05x anchor | Protect retained gain |
| Method | 1.19841x | Revalidate uncertainty and 1.05x anchor | Protect retained gain |
| Numeric | 1.12967x | Revalidate uncertainty | Protect retained gain |
| JSON | 1.76084x | Revalidate uncertainty | Protect semantics and performance of selected implementation |

These necessary point-estimate gains omit confidence headroom. Improving a
single workload by factor `s` improves an equally weighted seven-workload
geometric mean by only `s^(1/7)`; a 5% local gain yields about 0.7% portfolio
gain. Favor general changes that address a large measured fraction of a
deficient workload or benefit several workloads. A JSON surplus cannot meet
another workload's floor.

Evidence: [full diagnostic](performance-over-perl-experiments.md#completed-plain-concat-source-full-high-load-portfolio-2026-09-13)
and [string/regex/Life attribution](performance-over-perl-experiments.md#completed-stringregexlife-allocation-attribution-2026-09-13).
The attribution reports outer call setup of 0.11/0.19/0.39 microseconds versus
body times of 24.78/301.47/1,043 microseconds respectively. This rules out
outer-call setup as the main lever for these workloads; it does not rule out
calls or allocations nested within their bodies. JFR weights and inclusive
stacks require measurement-window filtering and attribution before they are
treated as exclusive bytes/op or CPU budgets.

## Execute this queue

1. **Select one body-cost reduction.** Start with Life's residual arithmetic,
   range results, and array element
   transport after retained lexical-word lowering. For string, inspect the
   remaining representation/allocation cost, including
   `RuntimeArray.createReferenceWithTrackedElements`; for regex, target a
   general result/cursor or search body cost. Obtain selected generated-code
   evidence and a non-overlapping cost budget before coding.
2. **Prove and measure one reversible candidate.** Write its ownership/effect
   contract and expected end-to-end gain first. Use the experiment funnel
   below; preserve generic fallbacks and permanent semantic counterexamples.
   A smaller allocation count alone is insufficient for retention.
3. **Refresh all seven workloads at an integration checkpoint.** After a
   material local improvement, or a shared-runtime change with broad exposure,
   run the full default protocol on the committed candidate. Recompute the
   priority table and remaining gaps. A full portfolio is required before
   acceptance; it need not be repeated for every rejected experiment or
   documentation-only update.

If a candidate's maximum plausible benefit is too small to close a meaningful
part of the remaining gap, move to a broader generic representation or
compiler proof. Do not continue adding narrow guards simply because they are
easy to implement. No user priority decision is needed for this queue.

## Spend measurements where they change a decision

| Stage | Work and evidence | Decision |
| --- | --- | --- |
| Budget | Reuse current profiles; inspect selected bytecode and exclusive cost. For cost fraction `f` sped up by `s`, total gain is `1/(1-f+f/s)`. | Proceed only with a plausible material benefit; collect a short bounded profile only when attribution is missing or source changes invalidate it. |
| Prove | State selected/rejected cases, fallback and lifetime/effect invariants; validate new Perl tests on system Perl first, then JVM/interpreter and direct engine tests where owned. | Fix semantics before throughput work; do not change existing expectations. |
| Build | Commit candidate; run full immutable `make` with timeout and complete log. Record source/JAR/launcher identity. | Readers start only after the build and its workers succeed and exit. Reuse a validated immutable parent build. |
| Screen | Uninstrumented affected-workload runs; two pairs can reject a clearly poor candidate or establish whether a full local comparison is worthwhile. | Short/noisy results are diagnostic. Do not retain from a favorable outlier or claim acceptance. |
| Compare | Seven fresh Perl/PerlOnJava pairs per affected workload and comparable exact-parent evidence; preserve windows, checksums, warmup and host data. | Retain only a repeatable material gain with credible semantic scope. Reject neutral/regressive work; unresolved noise means inconclusive, not retained. |
| Integrate | Full default seven-workload portfolio on retained source; existing and stronger parity gates, provenance and correctness evidence. | Protect previously improved workloads; report remaining gaps even when aggregate throughput rises. |

Choose the practical gain threshold **before** a candidate run, based on its
complexity, risk, and measured cost budget. A useful default for new runtime
complexity is about 5% affected-workload improvement, supported by repeated
evidence, rather than a rigid retrospective cutoff. This is a selection rule,
not a relaxation of any acceptance gate. If the interval spans meaningful
benefit and regression, one predeclared reverse-order confirmation can resolve
host drift; if still unresolved, park the candidate and move to a larger
opportunity. Preserve every attempt, including failed or unstable runs.

The runner alternates **Perl and PerlOnJava**, not parent and candidate builds.
Separate candidate-then-parent portfolios remain sequential blocks under a
changing host load. Dividing same-index normalized ratios is descriptive; it
does not make the builds contemporaneously paired or establish causation.
Use independent immutable parent/candidate worktrees and interleave or reverse
their execution where practical, recording the actual schedule. Do not label
a confidence interval over arbitrary index matching as a paired A/B proof.

Keep throughput uninstrumented. Use JFR, call counters, guard counters and
JIT diagnostics only to answer a specific attribution or selection question.
One bounded diagnostic capture can be sufficient; seven instrumented pairs
are not a default prerequisite for every experiment. Filter startup/warmup
from profiles and normalize by completed operations. Re-profile after a gain
changes the limiting cost, rather than repeating unchanged attribution.

## High-load execution and provenance

The user explicitly requests the best measurements available under realistic
high load. Continue collecting them without waiting for a quiet host. Record
CPU service, load, warmup stability, raw windows, checksums and quality labels;
do not silently filter contention outliers or lower acceptance thresholds.
The [main contract](performance-over-perl.md#benchmark-authority) permits noisy
paired evidence for a decisive negative result, not positive acceptance.
Use `--allow-noisy-host` explicitly at analysis when applicable and retain its
resulting classification. Report loaded-host gains separately from any future
quiet-reference acceptance result.

Keep one task-owned heavy benchmark/build running on the host. Leave unrelated
user load intact. A file-backed run can continue in the background while
documentation or source work proceeds in a **different** worktree. Never edit,
checkout, rebase, regenerate or rebuild the measured checkout until its process
and children have exited. A different worktree must use its own built JAR.

Store the process/session handle and verify it with an authoritative process
check. A denied sandbox `ps`/`pgrep` or `kill -0` check is an observation failure,
not evidence of exit; retry with appropriate process visibility. An empty log
is also not evidence of exit. Never launch a duplicate because a polling call
failed. Stop only exact identified obsolete task-owned processes and their
children. Inspect final artifacts and exits after the run drains.

Use a fresh output directory per attempt and `timeout` around every reader.
The existing runner defaults are seven pairs, 10–60 warmup windows, and fifteen
one-second measurement windows; subset runs cannot satisfy full acceptance.
Example from an already built immutable checkout:

```bash
timeout 7200 perl dev/bench/run_performance_portfolio.pl --workload regex --output-dir /tmp/perf-EXPERIMENT-candidate > /tmp/perf-EXPERIMENT-candidate.log 2>&1
```

Replace `EXPERIMENT` with a fresh identifier; capture its exit status. Analyze
the exact emitted `portfolio.json` path with
`perl dev/bench/analyze_performance_portfolio.pl --input PATH --output REPORT`,
capturing output and exit status. A clean Git status plus a JAR hash alone
does not establish that source built that JAR. Record the successful build
source and demonstrate any intervening changes are documentation-only.

For each decision retain a compact durable record: hypothesis, exact revisions
and hashes, command/options, environment and loaded module identities, gate
results, schedule, all pair ratios/uncertainty, selection evidence, decision,
and next action. `/tmp` files do not travel with Git: preserve a compact report
and manifest in project/PR evidence storage before relying on them for handoff.
Missing artifacts mean unavailable evidence; never reconstruct measurements.

## Avoid repeating exhausted approaches

Reopen an experiment only with a changed mechanism, new cost attribution, or a
stronger ownership proof that addresses its recorded rejection.

| Boundary | Existing decision / prerequisite |
| --- | --- |
| String | Retain plain UTF-8 `STRING + STRING`, `BYTE_STRING + BYTE_STRING`, and `BYTE_STRING + INTEGER` (`0d5af0d99`). Plain `STRING + INTEGER` was reverted (`137371722`), median 0.99937x and geometric mean 0.85675x against parent. Ordinary leaf concat shortcuts and concat/substr fusion were also rejected. |
| Regex | Retain literal-alternation matching, generic exact-byte batching, lazy scalar result lists and `/g` continuation. Empty named-capture maps, captureless region allocation, published cursor pools, six/seven-byte exact instructions and batched map search have recorded rejections. Pending direct search is a separate candidate. |
| Life | Retain guarded lexical-word lowering. Direct-array-only matching, transient result-cell reuse, generic array cleanup elision, void plain-array assignment result elision, and small bitwise/store shortcuts failed selection. Broader ownership/effect proof is required before reuse. |
| Calls/methods | Retain proven closure and plain-hash method lowering. Broad frame reuse, immediate argument borrowing and lexical-cell reuse have rejected implementations. Outer setup is no longer the leading deficit. |
| Topic/effects | `doesNotObserveDynamicTopic` metadata is not a sufficient effect proof. Cover implicit topic, aliases, callbacks, overload/ties, debugger, dynamic inspection, re-entry and retained references before consuming it. |

See the [searchable decision archive](performance-over-perl-experiments.md)
for exact evidence and guarded contracts. For every successor preserve
warnings/coercions, signed/unsigned/BigInt values, byte/Unicode/taint semantics,
regex captures and `pos`, aliasing, destructor timing, exceptions and runtime
isolation as applicable. Benchmark-pattern recognition is not an optimization.

## Completion and maintenance

The [main performance contract](performance-over-perl.md#goal-and-acceptance-contract)
requires portfolio and closure/Life geometric means at least 1.05x, their 95%
confidence intervals wholly above 1.00x, and no workload below 0.90x. The
stronger handoff objective also requires **every workload's median and 95%
lower confidence bound at least 1.00x**. Preserve both; add explicit reporter
coverage for the stronger gate before declaring parity. Existing analyzer
`acceptance.passed` alone does not establish the stronger objective.

- [ ] Exact committed implementation with successful immutable `make`, focused
  semantic coverage on standard Perl and both backends, and appropriate engine
  coverage; all provenance verified.
- [ ] Complete uninstrumented seven-workload, seven-pair protocol, stable
  warmups, matching checksums, required intervals and eligible host evidence.
- [ ] Existing acceptance and explicit stronger parity gate pass; no slow
  workload excluded and no noisy-host diagnostic promoted to acceptance.
- [ ] Attribution explains retained gains; conservative fallbacks and bounded
  resources remain; diagnostic instrumentation is off by default.
- [ ] Durable evidence manifest, current handoff and main-design summary,
  changelog impact evaluated, changes delivered to the issue's feature PR;
  review before merge.

This review completes the handoff restructuring (2026-09-13), not performance
acceptance. Current open work: substantial string/regex/Life gaps, stronger
reporter gate, and durable evidence publication.
After each completed experiment update the queue, decision and remaining gap;
do not append another competing current plan. Documentation-only updates use
`make check-links`; they do not require another runtime build or portfolio.

References: [workloads](../bench/performance_workload.pl),
[runner](../bench/run_performance_portfolio.pl),
[analyzer](../bench/analyze_performance_portfolio.pl),
[profiling workflow](../../.agents/skills/profile-perlonjava/SKILL.md),
[historical evidence](performance-over-perl-experiments.md).

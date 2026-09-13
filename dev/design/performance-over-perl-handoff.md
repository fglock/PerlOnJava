# Performance over Perl handoff

## Resume here — reviewed 2026-09-13

Performance parity is **not achieved**. The immediate work is to finish the
pending exact-parent regex comparison, then attack the remaining string,
regex, and Life body/representation costs. Call-boundary attribution has
already been collected; repeating that phase is not the default next step.

Work continues on `wip/performance-preflight-20260909-133542` for issue
[#1196](https://github.com/fglock/PerlOnJava/issues/1196). Resolve the actual
branch tip, worktree, and PR before integration; historical commit IDs may
precede rebases. The last inspected implementation tip is `a59f374f3`, an
**unselected candidate**, on retained parent `a1cb8b828`. A clean checkout at
the parent is intentional while measuring it; do not mistake it for lost work.

Use this file for decisions and work order. The
[experiment archive](performance-over-perl-experiments.md) preserves the full
historical evidence, including rejected experiments and their semantic proofs.
Read the relevant linked experiment before proposing a successor. Update this
summary in place after each decision; append detailed evidence to the archive.

## Current evidence and required improvement

The latest completed full portfolio recorded runtime `19653cf32` through its
documentation-only successor `222a9ce50`. It is a **high-load diagnostic**:
portfolio geometric mean 0.98267x Perl, 95% interval 0.88524–1.06769x,
inconclusive and non-authoritative. It predates subsequent rebasing and the
pending search candidate. Its workload geometric means guide priorities;
they do not certify current-source acceptance or a candidate speedup.

| Workload | Diagnostic ratio to Perl | Point-estimate gain needed | Priority |
| --- | ---: | ---: | --- |
| String | 0.55380x | 1.81x to 1.00x | Largest remaining deficit; isolate body allocation and representation cost |
| Life | 0.60002x | 1.75x to 1.05x anchor | Preserve word lowering; target residual arithmetic/array/result transport |
| Regex | 0.62541x | 1.60x to 1.00x | Finish existing search candidate before opening another |
| Closure | 1.11943x | Revalidate uncertainty and 1.05x anchor | Protect retained gain |
| Method | 1.18735x | Revalidate uncertainty | Protect retained gain |
| Numeric | 1.20075x | Revalidate uncertainty | Protect retained gain |
| JSON | 2.59039x | Revalidate uncertainty | Protect semantics and performance of selected implementation |

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

1. **Resolve the pending regex candidate.** `a59f374f3` searches root literal
   alternations directly before generic Joni search. Its exact-source `make`,
   direct Joni coverage, and Perl `/g`/branch-order regression passed, including
   standard Perl and both backends. The seven-pair candidate artifact is
   `/tmp/perf-joni-literal-alternation-search-candidate-highload-20260913/20260913T161428Z/portfolio.json`;
   regex geometric mean is 0.673756x Perl, median 0.652497x. This is a subset
   result, not a gain against its parent. Parent `a1cb8b828` passed its exact
   build and completed its original run at
   `/tmp/perf-joni-literal-alternation-search-parent-highload-20260913/20260913T162845Z/portfolio.json`.
   Its 0.772133x geometric mean is inconclusive; a duplicate measurement
   overlapped during process-observation recovery. The background repeat
   writes under `/tmp/perf-joni-literal-alternation-search-parent-highload-20260913-retry`.
   Verify that process and its final artifact before launching anything.
   Record overlap and uncertainty; the current evidence does not justify
   retention. Complete comparison, record
   retain/reject/inconclusive, then close the experiment. Do not stack another
   candidate on an unselected optimization.
2. **Select one body-cost reduction.** Start with string's remaining
   representation/allocation cost; inspect the measured
   `RuntimeArray.createReferenceWithTrackedElements` allocation stack to
   distinguish workload work from harness/compiler work. For Life, inspect
   residual arithmetic, range results, and array element transport after
   retained lexical-word lowering. For regex, use the pending result to choose
   between further general search work and result/cursor lifecycle work.
   Obtain selected generated-code evidence and a non-overlapping cost budget
   before coding. If the apparent hotspot is not steady-state workload cost,
   discard that hypothesis and choose the next attributed cost.
3. **Prove and measure one reversible candidate.** Write its ownership/effect
   contract and expected end-to-end gain first. Use the experiment funnel
   below; preserve generic fallbacks and permanent semantic counterexamples.
   A smaller allocation count alone is insufficient for retention.
4. **Refresh all seven workloads at an integration checkpoint.** After a
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
| String | Retain plain UTF-8 `STRING + STRING`; plain `STRING + INTEGER` was reverted (`137371722`), median 0.99937x and geometric mean 0.85675x against parent. Ordinary leaf concat shortcuts and concat/substr fusion were also rejected. |
| Regex | Retain literal-alternation matching, generic exact-byte batching, lazy scalar result lists and `/g` continuation. Empty named-capture maps, captureless region allocation, published cursor pools, six/seven-byte exact instructions and batched map search have recorded rejections. Pending direct search is a separate candidate. |
| Life | Retain guarded lexical-word lowering. Direct-array-only matching, transient result-cell reuse, generic array cleanup elision and small bitwise/store shortcuts failed selection. Broader ownership/effect proof is required before reuse. |
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
acceptance. Current open work: pending regex selection, substantial string/
regex/Life gaps, stronger reporter gate, and durable evidence publication.
After each completed experiment update the queue, decision and remaining gap;
do not append another competing current plan. Documentation-only updates use
`make check-links`; they do not require another runtime build or portfolio.

References: [workloads](../bench/performance_workload.pl),
[runner](../bench/run_performance_portfolio.pl),
[analyzer](../bench/analyze_performance_portfolio.pl),
[profiling workflow](../../.agents/skills/profile-perlonjava/SKILL.md),
[historical evidence](performance-over-perl-experiments.md).

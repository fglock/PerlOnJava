# Curated performance delivery selection

Issue: [#1196](https://github.com/fglock/PerlOnJava/issues/1196)

This is the integration record for the first compiler-performance delivery.
It is intentionally a small, dependency-complete selection from
`perf/concat-substr-transport`, not a merge of that research branch.

## Sources

| Item | Value |
| --- | --- |
| Fetched master | `300954833` |
| Research head | `9362d12ff` |
| Merge base | `d90fa37f9` |
| Divergence at revalidation | master 60 commits; research 478 commits beyond merge base |
| Integration branch | `perf/curated-parity-gains` |

The historical benchmark artifacts named below are selection evidence only.
This branch must be rebuilt and measured from its own source/JAR before a PR
can claim a current gain.

## Logical changes

| Logical change | Source commits and required coverage | Current status | Benefit and evidence | Integration and decision |
| --- | --- | --- | --- | --- |
| BMP substring offset scan | `b6c2ef49f3`; `substr_bmp_offset_fastpath.t` | Retained on research; absent from master | Seven exact-parent/candidate pairs favored the candidate, median 1.0569x. Artifact: documented historical pair series in the research handoff. | Include. It is self-contained in `PerlUtfString`, preserves the general decoder for surrogate/marker leads, and has no semantic follow-up. |
| Small negative integer literal lowering | `2a83a47f3`; `unary_minus_literal_fastpath.t` | Retained on research; absent from master | Seven exact-parent/candidate pairs favored the candidate, median 1.1274x. | Include. It is an emitter-only literal specialization with the generic unary path retained for non-integers and large values. |
| Direct scalar result for guarded closure-addition leaves | Runtime/call-boundary prerequisites culminating in `243fabfe1`, `41109c96e`, `2c771b73c`, and `d4e504ae9`; `direct_closure_integer_addition.t`, `direct_closure_scalar_fallback.t`, and `direct_closure_padwalker_rebind.t` coverage | Integrated as `a8e4a8fc1` | Seven exact-parent pairs: 1.2436x median candidate/parent. The source/JAR-matched closure portfolio median was 1.0944x Perl, 95% CI 1.0785–1.1117. Artifacts: `/tmp/perf-direct-leaf-scalar-closure-vs-perl-20260912/20260912T141322Z/portfolio.json` and its sibling analysis. | Included as a bounded scalar-context fast path. It dynamically reads capture cells, falls back for debugger/lvalue/overflow/taint/overload cases, and updates the capture map during PadWalker rebinding. Current-source benchmark evidence is still required before a PR claim. |
| Guarded plain-hash integer method lowering | Call-runtime prerequisites plus `7895074a0`; `direct_plain_hash_integer_method.t` | Retained on research; absent from master | Seven alternating pairs: 4.98036x median candidate/parent; complete source portfolio method 1.11657x Perl (95% CI 1.10711–1.13806). Artifact: `/tmp/direct-plain-hash-method-parent-candidate-20260912.json`; portfolio: `/tmp/perf-direct-plain-hash-method-full-20260912/20260912T204325Z/portfolio.json`. | Defer. It recognizes a benchmark-shaped two-slot method body and adds disproportionate compiler/runtime complexity. Retain the semantic test as a baseline; investigate broadly shared method costs (argument frames, cached dispatch, scalar-result handling, and hash access) instead. |
| Lazy scalar regex whole-match materialization | `1c68f29dd`; `regex/lazy_whole_match_snapshot.t` | Retained on research; absent from master | Avoids publication allocation; the regression preserves `$&` and group-zero lifetime. No standalone current comparison is recorded in the handoff. | Defer: the source is compact, but a current focused comparison is required before inclusion because its isolated throughput benefit is not established. |
| Feature-free Joni matcher pooling | `31c364e7a`, `2b61c8b96`, `bf2a5a7d2`, later runtime-scope fix `5334a6bbf`; Joni and matcher lifetime tests | Partly superseded by later cursor experiments; absent from master | Allocation evidence exists, but multiple related cursor/pool variants were reverted. | Defer pending a dependency audit and a clean exact-parent comparison. Do not select a partial pool sequence. |
| Empty named-capture map reuse | `6f4c614bd` and prior variants | Reverted/rejected | Repeats were neutral or regressive despite allocation removal. | Reject. |
| Private native-array carrier | `09435ed53` through `3930df7d2` and associated tests | Experimental infrastructure | Does not select the scored Life recurrence. | Defer. It is not eligible for this delivery. |

## Validation ledger

Before a PR is opened, record here the integrated commit SHA, conflict
adaptations, system-Perl test logs, focused JVM/interpreter logs, immutable
`make` log, master/curated source and JAR identities, complete portfolio
artifacts, checksum status, confidence intervals, and intended-load metadata.

### Integrated String prerequisites

- System Perl: `substr_bmp_offset_fastpath.t` and
  `unary_minus_literal_fastpath.t` passed; logs
  `/tmp/perl-substr-bmp-offset-fastpath-20260915.log` and
  `/tmp/perl-unary-minus-literal-fastpath-20260915.log`.
- JVM and interpreter focused tests passed; logs
  `/tmp/perf-curated-{jvm,interp}-{substr,unary}-20260915.log`.
- Immutable full gate passed: `/tmp/make-perf-curated-string-fastpaths-20260915.log`
  (`BUILD SUCCESSFUL`, exit 0). The built source began at `5fdf4cb12` plus
  the uncommitted curated changes.

### Closure/method port baseline

- The four imported closure/method regressions pass on system Perl (17
  assertions) and on both current PerlOnJava backends. Logs:
  `/tmp/prove-curated-closure-method-perl-20260915.log` and
  `/tmp/perf-curated-{jvm,interpreter}-closure-method-20260915.log`.
- `direct_closure_padwalker_rebind.t` adds the required capture-rebinding
  guard. It passed system Perl and both current backends; logs:
  `/tmp/perl-direct-closure-padwalker-rebind-20260915.log` and
  `/tmp/perf-curated-{jvm,interpreter}-direct-closure-padwalker-rebind-20260915.log`.
- Port constraint: a direct closure path must fetch the current generated
  capture cells or invalidate and rebuild its cache through
  `Internals.rebindCapturedVariable`. It must never continue using cells that
  `PadWalker::set_closed_over` replaced.

### Integrated closure direct-scalar path

- Integrated commit: `a8e4a8fc1`.
- The compiler marks only a single-expression captured-scalar addition closure.
  The call site uses the direct result only for a scalar, zero-argument lexical
  call. `RuntimeCode` otherwise keeps the ordinary call boundary, including in
  debugger and lvalue modes and for overflow, taint, object/overload, or
  PadWalker-rebound captures.
- System Perl and focused JVM/interpreter coverage passed before integration:
  `/tmp/perf-direct-closure-stock-perl-20260915.log`,
  `/tmp/perf-direct-closure-jvm-20260915.log`, and
  `/tmp/perf-direct-closure-interpreter-20260915.log`.
- Bytecode selection was verified for a scalar assignment call; log:
  `/tmp/perf-direct-closure-disassemble-scalar-20260915.log`.
- Immutable full gates passed:
  `/tmp/make-perf-curated-direct-closure-final-20260915.log` and
  `/tmp/make-perf-curated-direct-closure-debug-guard-20260915.log`.

### Deferred method specialization

- The attempted current-master port of the two-slot plain-hash method recognizer
  was deliberately removed without commit. Its full validation log is retained
  at `/tmp/make-perf-curated-direct-method-final-20260915.log`; it is not
  evidence for shipping that specialization.
- The existing method regression remains as semantic coverage. Future method
  work must target a broadly shared, measured cost and show a meaningful
  repeatable whole-workload result before expanding compiler recognition.

### Non-authoritative source-matched pilot

- Master (`5fdf4cb12`) and curated (`5048e78ef`) were each rebuilt and passed
  immutable full gates: `/tmp/make-perf-curated-baseline-20260915.log` and
  `/tmp/make-perf-curated-final-measurement-20260915.log`.
- Both measurement worktrees received identical untracked tooling-only copies
  of the runner, worker, and analyzer from `perf/benchmark-authority`; SHA-256
  values were recorded before execution. This keeps compiler source distinct
  from the separate benchmark-tooling PR.
- A one-pair, five-warmup, three-window closure/method/string pilot completed
  with matching semantic checksums. Artifacts:
  `/tmp/perf-curated-baseline-pilot-20260915/20260915T083846Z/portfolio.json`
  and
  `/tmp/perf-curated-candidate-pilot-20260915/20260915T084028Z/portfolio.json`.
  It is explicitly non-authoritative: `protocol_compliant` and `conclusive`
  are both false, warmups did not stabilize, and host load changed from roughly
  22 to 17 while an unrelated test build drained. Do not use its directional
  throughput values to retain, reject, or advertise an optimization.

### Bracketed current-source portfolio

- The immutable current-master gate at `5fdf4cb12` and the curated gate at
  `54e2005b3` both passed. The candidate contains all retained String work and
  the guarded closure path; it does **not** contain the method recognizer.
- Full seven-pair portfolio artifacts, in execution order, are:
  `/tmp/perf-curated-baseline-full-20260915/20260915T085537Z/portfolio.json`,
  `/tmp/perf-curated-candidate-full-20260915/20260915T095050Z/portfolio.json`,
  and
  `/tmp/perf-curated-baseline-post-20260915/20260915T103838Z/portfolio.json`.
  The corresponding analyzer reports are in each artifact's parent directory
  as `report.json`; the post-control analysis log is
  `/tmp/perf-curated-baseline-post-20260915/analysis.log`.
- All runs used identical copied benchmark tooling and recorded 49 paired
  samples with zero Perl/PerlOnJava semantic-checksum mismatches. They are
  protocol-compliant but `conclusive: false`, so every analyzer report marks
  the evidence non-authoritative. Host load and warm-up stability prevent an
  acceptance or portfolio-wide throughput claim.
- The bracketing controls make the interpretation explicit. Their closure
  geometric ratios to system Perl were 0.1267 and 0.1364, versus 0.6022 for
  the candidate. Comparing each candidate pair to the geometric mean of its
  bracketing master pairs gives a 4.631x median directional closure result.
  Other workloads moved together by 1.032x--1.331x, including method at
  1.199x; those common shifts are host variation, not a result to attribute to
  this branch. This supports retaining the closure candidate for further
  controlled measurement, but does not make the portfolio authoritative.
- Master advanced after these artifacts were collected. The curated series was
  rebased onto `300954833` as `21081cf4b`; its immutable full gate passed in
  `/tmp/make-perf-curated-rebase-300954833-20260915.log` (`BUILD SUCCESSFUL`,
  exit 0). This validates compatibility with the current PR target, not
  performance: no throughput claim transfers from the pre-rebase artifacts.

## Next action

The retained String and closure changes are ready for review as a semantic-safe,
curated delivery at `21081cf4b`, with the above measurement qualification and
no advertised whole-portfolio gain. Before any performance claim, repeat the
source-matched portfolio on a stable host until the protocol is conclusive.
Keep the method recognizer deferred: next method work should begin with
profiling and a design for a cost shared across ordinary methods, such as
argument-frame allocation, cached dispatch, scalar-result handling, or hash
access.

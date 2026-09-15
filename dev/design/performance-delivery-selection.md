# Curated performance delivery selection

Issue: [#1196](https://github.com/fglock/PerlOnJava/issues/1196)

This is the integration record for the first compiler-performance delivery.
It is intentionally a small, dependency-complete selection from
`perf/concat-substr-transport`, not a merge of that research branch.

## Sources

| Item | Value |
| --- | --- |
| Fetched master | `5fdf4cb12` |
| Research head | `9362d12ff` |
| Merge base | `d90fa37f9` |
| Divergence at selection | master 8 commits; research 478 commits beyond merge base |
| Integration branch | `perf/curated-parity-gains` |

The historical benchmark artifacts named below are selection evidence only.
This branch must be rebuilt and measured from its own source/JAR before a PR
can claim a current gain.

## Logical changes

| Logical change | Source commits and required coverage | Current status | Benefit and evidence | Integration and decision |
| --- | --- | --- | --- | --- |
| BMP substring offset scan | `b6c2ef49f3`; `substr_bmp_offset_fastpath.t` | Retained on research; absent from master | Seven exact-parent/candidate pairs favored the candidate, median 1.0569x. Artifact: documented historical pair series in the research handoff. | Include. It is self-contained in `PerlUtfString`, preserves the general decoder for surrogate/marker leads, and has no semantic follow-up. |
| Small negative integer literal lowering | `2a83a47f3`; `unary_minus_literal_fastpath.t` | Retained on research; absent from master | Seven exact-parent/candidate pairs favored the candidate, median 1.1274x. | Include. It is an emitter-only literal specialization with the generic unary path retained for non-integers and large values. |
| Direct scalar result for guarded closure-addition leaves | Runtime/call-boundary prerequisites culminating in `243fabfe1`, `41109c96e`, `2c771b73c`, and `d4e504ae9`; existing closure-addition and `direct_closure_scalar_fallback.t` coverage | Retained on research; absent from master | Seven exact-parent pairs: 1.2436x median candidate/parent. The source/JAR-matched closure portfolio median was 1.0944x Perl, 95% CI 1.0785–1.1117. Artifacts: `/tmp/perf-direct-leaf-scalar-closure-vs-perl-20260912/20260912T141322Z/portfolio.json` and its sibling analysis. | Primary integration group. Its final scalar-return step depends on the prior generated-CV/direct-entry metadata and current call-runtime ownership checks, so extract and port the complete minimal group rather than cherry-picking `d4e504ae9` alone. |
| Guarded plain-hash integer method lowering | Call-runtime prerequisites plus `7895074a0`; `direct_plain_hash_integer_method.t` | Retained on research; absent from master | Seven alternating pairs: 4.98036x median candidate/parent; complete source portfolio method 1.11657x Perl (95% CI 1.10711–1.13806). Artifact: `/tmp/direct-plain-hash-method-parent-candidate-20260912.json`; portfolio: `/tmp/perf-direct-plain-hash-method-full-20260912/20260912T204325Z/portfolio.json`. | Primary integration group. A trial cherry-pick conflicted because it relies on the research branch's evolved `RuntimeCode` call and method-argument interfaces. Preserve current-master semantics while porting a minimal complete dependency group, followed by focused standard-Perl/JVM/interpreter and current-master comparisons. |
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

## Next action

Commit the validated String prerequisites, then audit and port the minimal
complete closure direct-scalar group and the guarded plain-hash method group.
Do not flatten either group into a single cherry-pick: current master must
retain its call-frame, caller, lexical-alias, debugger, lvalue, overflow, tie,
and overload fallbacks. Measure the resulting source/JAR against current
master before considering the deferred groups.

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
Use the same load scenario, runner, standard Perl executable, runtime options,
and source/JAR identity for parent and candidate. Run suites sequentially,
alternate order where possible, and preserve raw portfolios, checksums, host
metadata, and analysis output.

The default portfolio runner uses seven alternating fresh-process Perl and
PerlOnJava pairs per workload, 10--60 one-second warmup windows, and fifteen
one-second measurement windows. Final throughput claims use normal runs; JFR
is for diagnosis. `--allow-noisy-host` preserves diagnostic analysis under the
intended load but never converts noisy evidence into an acceptance result.

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

## Current retained candidate: validated regex position-cache handle

One ordinary `/g` or `\G` operation used to repeatedly rediscover the same
access-order position-cache entry for lookup, provenance, zero-length
bookkeeping, and publication. The candidate creates one `RegexPosition` view
per match. Every use revalidates the entry; a subject mutation or LRU eviction
falls back to the ordinary cache lookup. The bounded cache and all externally
observable `pos` behavior remain unchanged.

### Current-source evidence

- Current-master parent: `a91c57056`; candidate: `8f6a7158b`.
- Standard Perl position-state oracles passed (83 assertions); the focused JVM
  and interpreter runs also passed. The candidate's immutable full `make` gate
  passed at `/tmp/make-regex-position-cache-current-20260921.log`.
- Seven alternating, source/JAR-identified parent/candidate Regex pairs all
  had matching checksums and stabilized warmups. Candidate/parent median
  ratios were 1.1365, 1.0986, 1.1257, 1.1889, 0.9359, 1.0629, and 1.2287
  (1.1074x geometric mean). Raw artifacts:
  `/tmp/perf-regex-position-cache-current-seven-pair-20260921/`.
- Seven alternating candidate/system-Perl pairs also had matching checksums
  and stabilized warmups. The candidate measured 0.5539x geometric mean and
  0.5353x median versus system Perl. Raw artifacts:
  `/tmp/perf-regex-position-cache-current-vs-perl-20260921/`.

This is a material focused Regex improvement, not a parity or complete
portfolio claim. The direct Perl comparison confirms that Regex remains a
large gap and must be profiled again from this delivery baseline.

## Progress tracking

### Current status: Phase 3, position-cache candidate revalidated on current master

### Completed phases

- [x] Select and validate a general literal-match regex cache (2026-09-16)
  - Added static call-site wrapper reuse in the JVM compiler and runtime.
  - Preserved dynamic patterns and `qr//` identity semantics.
  - Added focused recursive-cache and runtime-isolation coverage.
  - Ran standard-Perl regressions, full `make`, focused production brackets,
    and a complete parent/candidate production portfolio.
- [x] Revalidate a mutation-safe position-cache handle on current master (2026-09-21)
  - Ported the focused `/g` and `\G` cache-view change onto `a91c57056`.
  - Preserved mutation and LRU-eviction fallback, with system-Perl, JVM,
    interpreter, and full-gate coverage.
  - Measured a 1.1074x focused Regex geometric-mean candidate/parent gain;
    the direct candidate/Perl result remains 0.5539x.

### Next steps

1. Publish the focused position-cache PR with its qualified current-source
   evidence; do not represent its Regex-only result as portfolio parity.
2. Profile residual Regex matcher/dispatch cost and the remaining String and
   Life gaps from this delivery baseline, targeting independently measured
   broad costs.
3. For any future retained candidate, repeat source-matched semantic gates and
   a complete portfolio under the same production-load protocol.

### Open questions

- The production-load protocol intentionally produces `noisy-paired` results.
  Establish a recorded production-load stability policy before making an
  authoritative portfolio acceptance claim.
- String and Life remain below standard Perl in the historical orientation
  portfolio and require separate cost attribution; do not widen experimental
  native-array representations without a general ownership proof.

## Completion criteria

The parity goal is complete only when the final source/JAR-matched portfolio
under the intended load demonstrates every scored workload at or above 1.00x
with a 95% lower confidence bound at least 1.00x, a portfolio geometric mean of
at least 1.05x with its interval wholly above 1.00x, Closure and Life each at
least 1.05x with intervals wholly above 1.00x, and no workload below 0.90x.
Checksums, protocol validity, warmup, semantic tests, and backend parity must
also pass.

## References

- [Performance delivery selection](performance-delivery-selection.md)
- [Benchmark guide](../bench/README.md)
- [Profiling workflow](../../.agents/skills/profile-perlonjava/SKILL.md)

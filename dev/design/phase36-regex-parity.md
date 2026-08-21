# Full Perl Regex Semantic Parity

## Goal

Implement the regex semantics of the latest imported upstream Perl on both
PerlOnJava execution backends, with the vendored Joni fork as the sole
production matcher.

The immutable no-regression baseline is:

```text
../PerlOnJava/logs/test_20260815_080000_958.log
```

An implementation PR may merge incrementally when every valid baseline test row
retains at least its PR 958 passing count and at least one row improves. That is
a merge boundary, not project completion. Completion requires every phase and
final-acceptance item below.

## Required Architecture

- Joni owns regex grammar and matcher semantics: captures, conditions,
  recursion, lookarounds, folding, control verbs, backtracking-visible state,
  byte/Unicode matching, properties, and native compile diagnostics.
- PerlOnJava owns source policy: interpolation provenance, lexical hints and
  warning masks, `use re 'eval'`, executable Perl closures, user-defined
  properties, source locations, and final Perl diagnostic rendering.
- No production regex may route to `java.util.regex`, including ordinary
  constant patterns. Historical selector-policy tests use a test-scope-only
  model that cannot enter production artifacts.
- The maintained Joni/JCodings source packages remain unchanged under
  `third_party/`; standalone packaging relocates them to
  `org.perlonjava.internal` to avoid public collisions.
- Every original Joni/JCodings copyright, authorship, and license notice is
  preserved byte-for-byte.
- Joni remains runtime-neutral. Callouts expose internal IDs and matcher-local
  handlers, never Perl source or PerlOnJava runtime objects.
- Ordinary patterns allocate no callout state. One match operation runs exactly
  one engine because regex side effects are observable.
- Imported Perl and Unicode inputs track the latest upstream `perl5/`
  checkout. Do not pin a fixed Perl SHA or release.

## Preprocessing Boundary

Every transformation must fit one category:

1. Perl source policy retained in a small frontend scanner.
2. Backend-neutral spelling normalization retained outside the matcher.
3. Matcher semantics implemented in Joni parser/compiler/matcher internals.
4. Java-only translation or workaround deleted.

Text rewriting must not emulate behavior dependent on capture close order,
backtracking, matcher regions, encoding, or callback execution. When a native
Joni fix lands, remove its corresponding preprocessor/runtime scanner in the
same tranche.

Remaining scanner-removal queue:

- [ ] Make Joni's property resolver accept every raw built-in Perl spelling
  currently expanded by `JoniRegexPattern.translateUserDefinedProperties`,
  including Script, Script_Extensions, Block, Age/Present_In, and aliases.
- [ ] Keep raw user-defined property tokens through Joni parsing and resolve or
  defer them through the mode-specific callback cache; remove never-match or
  match-all text substitutions used to represent deferred properties.
- [ ] Preserve ordinary/extended-class context and source positions in Joni so
  the adapter no longer scans bracket depth or generates replacement classes.
- [ ] Retain only runtime-neutral trusted-callout materialization and documented
  Perl source-policy checks outside Joni; delete the scanner when these gates
  pass.

## Execution Phases

### Phase 0 — Reproducible differential baseline

- [x] Derive the complete regex-bearing test ledger mechanically.
- [x] Preserve direct and `_thr.t` identities separately.
- [x] Reject zero-TAP, timeout, truncated, incomplete, or malformed records.
- [x] Emit machine-readable file/test comparison evidence against PR 958.

Exit: every implementation slice has exact rows, a standard-Perl oracle, and a
repeatable baseline.

### Phase 1 — Ordinary-pattern Joni parity

- [ ] Close all remaining PR 958 ordinary-pattern regressions.
- [ ] Prove capture, duplicate-name, branch-reset, region/bounds, zero-width
  progression, `\G`, `/g`, `/c`, `/o`, substitution, reuse, and nested
  match-state behavior.
- [ ] Prove byte/Unicode pattern and subject variant selection.
- [x] Delete the disconnected production backend selector while preserving its
  historical compatibility tests under test scope.

Exit: every ordinary regex executes in Joni with no baseline regression and
JVM/interpreter agreement.

### Phase 2 — Conditions and backtracking-visible state

- [x] Numbered, named, assertion, recursion, callback, and `DEFINE` conditions.
- [x] `(*MARK:NAME)`, named `(*SKIP:NAME)`, `(*PRUNE)`, `(*COMMIT)`,
  `(*THEN)`, `(*ACCEPT)`, `(*FAIL)`, `$REGMARK`, and `$REGERROR`.
- [x] Capture publication/clearing, recursion frames, callback unwind, and
  destructive-cut boundaries.

Exit: focused standard-Perl oracles and affected imported rows agree.

### Phase 3 — Unicode and native pattern syntax

- [x] Generate checked-in Java property, alias, range, named-character, and
  case-fold data from the latest `perl5/` tables with provenance and a
  diff-free second generation.
- [x] General Category, Script, Block, Script Extensions, POSIX, binary,
  compatibility, wildcard, user-property, signed-wide, and fold foundations.
- [x] Native `\N{name}`, extended classes, DEFINE, lookbehind, branch reset,
  plain `\N`, recursion, numeric references, false ranges, and subpattern
  calls.
- [x] Finish lexical custom-charname cache identity, source byte/Unicode mode,
  scoped user-property options, and nested class fold provenance.
- [x] Finish native POSIX grammar, recovery, warning order, and diagnostics.
- [ ] Finish remaining non-POSIX native range/parser diagnostics.
- [ ] Refresh complete Unicode, `pat.t`, and `pat_advanced.t` gates.

Exit: Unicode and native syntax corpora execute without compatibility masking.

### Phase 4 — Runtime source and diagnostics

- [x] Runtime callbacks, nested continuations, lexical warning policy, eval
  capture lifetime, thread cloning, source spelling, and callback unwind.
- [x] Native numeric, named-character, quantifier, group-name, extended-class,
  range, and selected POSIX diagnostics.
- [x] `pat_re_eval.t` semantic contract.
- [ ] Finish all remaining same-source `reg_mesg.t` diagnostic families.
- [x] Finish analyser warning-policy rows.
- [ ] Finish remaining debug-trace rows.
- [ ] Refresh complete `regexp.t`, `reg_mesg.t`, and runtime-source gates.

Exit: generated regexes, warnings, fatality, categories, text, and locations
agree with standard Perl on JVM and interpreter.

### Phase 5 — Remove migration scaffolding

- [x] Remove Java matcher storage and production routing.
- [x] Remove native-replaced extended-class, branch-reset, lookbehind, DEFINE,
  recursion, numeric-reference, and matcher-semantic adapter rewrites.
- [x] Remove obsolete imported regex patches and prove targeted sync idempotent.
- [ ] Delete remaining matcher-semantic preprocessing, source scanners, and
  unreachable adapters after their native gates pass.
- [x] Prove no environment setting can select a production Java matcher.

Exit: Joni is the sole matcher and only documented Perl source policy remains
outside it.

### Phase 6 — Release and documentation

- [ ] Pass immutable complete 622-file JVM acceptance against PR 958.
- [ ] Pass the complete regex-bearing ledger on the interpreter and reconcile
  every JVM/interpreter semantic difference.
- [ ] Pass direct/thread parity, bounded `pat_psycho*` and `speed*`,
  affected CPAN suites, packaging, notice/license, and warmed performance gates.
- [x] Pass the five-run warmed ordinary-regex comparison: candidate median
  12.23s versus exact-parent 12.68s under alternating contended runs.
- [ ] Pass warning-free `make`, Ubuntu, Windows, and complete CI.
- [ ] Reconcile `docs/reference/feature-matrix.md`,
  `dev/implementation/regex.md`, and `docs/design/joni-callout-fork.md`
  with shipped behavior.
- [ ] Delete redundant documents; retain only concise rationale summaries that
  point to canonical implementation documents.

Exit: release evidence and public/internal documentation match the code.

## Current Release Gate

The exact integrated code barrier is `d3a4bb074`. Its warning-free `make`
passes packaging, licensed Joni, and all unit shards. PR 1087 remains the next
incremental release PR; update it only from an exact warning-free integration
head whose complete comparator has no pass-count decrease from PR 958 and no
new invalid, missing, timeout, truncated, incomplete, or zero-TAP row.

The private 28-file regression sieve has no reproduced pass-count decrease and
22 improvements. The authoritative 622-file, jobs-5, timeout-300 gate is
running from a fresh private test tree at the exact code barrier. Its result,
not the earlier high-parallelism reconnaissance run, decides the PR 1087 gate.

### Execution Tracker

- [x] Reproducible PR 958 baseline and strict comparator.
- [x] Conditions, callbacks, control verbs, and backtracking-visible state.
- [x] Runtime regex source, lexical context, callback unwind, and diagnostics.
- [ ] Ordinary-pattern Joni parity: prove the full corpus with native byte and
  Unicode matching and no Java matcher fallback.
- [ ] Unicode and pattern syntax: complete nested scoped extended-class
  interpolation and remaining corpus-derived roots.
- [x] Production Java matcher/backend selector and legacy `RegexPreprocessor`
  are absent; source audit finds only test-scope backend-policy assertions.
- [x] Obsolete imported regex patches are removed and targeted sync is
  idempotent.
- [ ] Remove residual temporary source-policy scanners identified by the final
  corpus.
- [ ] Complete integration, dual-backend/direct-thread/CPAN/performance gates,
  platform CI, documentation reconciliation, and post-merge checks.

Active ownership:

- P3: finish baseline-equivalent complete acceptance from current green
  `d3a4bb074` with private writable test state, then publish every non-improving
  row and its first failure.
- P4: remove built-in property text rewriting by routing raw Script,
  Script_Extensions, Block, Age/Present_In, and alias spellings through Joni's
  runtime-neutral resolver; continue into raw deferred user properties when the
  same hook proves sufficient.
- P5: classify the complete remaining `reg_mesg.t` and adjacent strict-warning
  residuals into an ordered implementation program.
- P6: fix interpreter raw UTF-8 NEL source provenance without entering regex
  runtime or Joni; then implement nested scoped extended-class interpolation
  from a fresh post-P5 barrier.
- Coordinator: integration, conflict resolution, immutable acceptance, PR/CI,
  plan state, combined build, and release evidence.

## Ordered Next Steps

1. Finish P6 raw-NEL provenance and integrate it with the delivered P4
   byte-POSIX and P5 strict-warning tranches while P3 finishes complete private
   acceptance. Preserve vendored notices and serialize Parser ownership.
2. Integrate the next deliveries, run one combined warning-free `make`, then
   refresh focused Unicode,
   `regexp.t`, `regex_sets.t`, `charset.t`, `pat.t`,
   `pat_advanced.t`, `pat_re_eval.t`, and `reg_mesg.t` gates on that exact
   immutable head.
3. Use P3's private complete gate to route only reproduced semantic decreases.
   Refresh the corpus after integrating the next batch with the PR-958 jobs-5,
   timeout-300, environment, and cleanup contract. Push a head with no PR-958
   pass-count regression to PR 1087, run CI, and make the incremental PR
   reviewable.
4. Continue remaining native diagnostics and Unicode/runtime roots in a new WIP
   PR. Repeat focused tests per semantic tranche and one combined build per
   integration batch.
5. Delete remaining production migration scaffolding and prove all constants,
   closures, conditions, verbs, recursion, dynamic source, byte strings, and
   Unicode strings execute through Joni.
6. Run complete JVM/interpreter acceptance, direct/thread parity, affected CPAN
   suites, five warmed performance samples, packaging, notices/licenses,
   warning-free build, and platform CI.
7. Reconcile final documentation and remove redundant design material.
8. After the final implementation PR is merged to `master`, remove automatic
   regex `JPERL_UNIMPLEMENTED=warn` injection from
   `dev/tools/perl_test_runner.pl`; rerun the complete corpus; then delete the
   RuntimeRegex warning-plus-never-match downgrade and obsolete tests/docs.
9. On final `master`, review shipped behavior against
   `pod/perlreref.pod`, `pod/perlrecharclass.pod`,
   `pod/perlrequick.pod`, `pod/perlrepository.pod`, `pod/perlre.pod`,
   `pod/perlretut.pod`, and `pod/perlrebackslash.pod`. Record every
   supported, partial, divergent, and missing capability in
   `docs/reference/feature-matrix.md` with evidence.

## Test and Delivery Contract

- Never modify imported or existing tests to make them pass. Add focused
  reducers and verify every new unit fixture with system Perl first.
- Every semantic slice needs system-Perl, JVM, interpreter, direct-Joni where
  applicable, and complete affected-corpus zero-introduction evidence.
- Use `dev/tools/perl_test_runner.pl` with system `perl`, not `jperl`.
- Compare complete output with the PR 958 baseline; reject missing, invalid,
  timeout, truncated, incomplete, or newly zero-TAP records. Preserve and
  classify the baseline's platform/skip-only zero-TAP rows separately.
- Give concurrent corpus runs private writable test state. Match the baseline's
  concurrency and cleanup for release evidence; label faster variants as
  reconnaissance only.
- Run at most three expensive jobs concurrently. Workers self-monitor load;
  final timing-sensitive gates are serial.
- Wrap every `jperl`, `jcpan`, and `prove` invocation in `timeout` and
  save complete output outside the repository.
- Build only with `make`. Batch two to four deliveries per full build where
  risk permits; a worker-local full build is reserved for broad-risk changes.
- Do not push an integration head until its exact `make` is warning-free.
- Keep `perl5/` development-only. A development make rule may clone it when
  absent or pull latest upstream when present, then run
  `dev/import-perl5/sync.pl`. Sync must include required generated inputs such
  as `unicore/Name.pl`, preserve needed non-regex patches, and be idempotent.

## Final Acceptance

- [ ] Every semantic row passing in PR 958 still passes.
- [ ] Complete 622-file JVM output is compared file-by-file with PR 958.
- [ ] Complete regex-bearing JVM/interpreter and direct/thread results agree.
- [ ] Joni covers constants, closures, conditions, control verbs, recursion,
  dynamic source, byte strings, and Unicode strings.
- [x] `(*MARK:NAME)` and named control-verb state execute in Joni.
- [x] Bounded `pat_psycho*` and `speed*` semantic/performance closure.
- [ ] No supported regex test needs `JPERL_UNIMPLEMENTED=warn`.
- [x] Joni is the sole production matcher; selector compatibility code is
  test-scope-only and absent from production packaging.
- [x] Matcher-semantic `RegexPreprocessor` and production Java matcher are gone.
- [x] Obsolete regex import patches are gone.
- [ ] Temporary source-policy scaffolding is gone.
- [x] Targeted latest-upstream sync is reproducible and idempotent.
- [ ] Feature matrix and architecture/fork documents match implementation.
- [ ] Original copyright/authorship/license notices are preserved.
- [ ] Warmed performance, CPAN, packaging, warning-free build, Ubuntu, Windows,
  and CI pass.
- [ ] Post-merge warn-mode removal and POD capability review are complete.

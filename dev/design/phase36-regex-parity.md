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

- [x] Close all remaining PR 958 ordinary-pattern regressions.
- [x] Prove capture, duplicate-name, branch-reset, region/bounds, zero-width
  progression, `\G`, `/g`, `/c`, `/o`, substitution, reuse, and nested
  match-state behavior.
- [x] Prove byte/Unicode pattern and subject variant selection.
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

- [ ] Pass immutable complete latest-Perl JVM acceptance against PR 958 by
  matched path, including every newly synced test (currently 623 files versus
  the 622-file baseline).
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

PR 1087 is the next incremental release checkpoint at exact refreshed head
`5a1e0c15e`, after a fresh pull of the latest `perl5/` source and a complete
`dev/import-perl5/sync.pl` run. Those imported fixtures are authoritative and
must not be reverted or patched to recover old counts. Its combined build and
exact-head Ubuntu/Windows CI are green. The
fresh complete corpus gate is red and blocks merge. Its two `pat*` rows timed
out under resource contention: isolated single-job probes execute all 1,302
assertions with 1,249 passes each, but equivalent exact-head quiet reruns remain
required. Their isolated 399/446-second runtimes versus roughly 250 seconds at
PR958 are also a post-checkpoint optimization root: profile both revisions with
JFR and remove the measured regression rather than masking it with a larger
timeout. `op/do.t` needs two fixes to reach standard Perl's 71/71 plus an
ID-normalized disposition because PR958 over-executed it as 94/99;
`class/accessor.t` aborts before the newly imported accessor-name rows; and
`japh/abigail.t` drops one nondeterministically. Acceptance must have no new
invalid, missing, timeout, truncated, incomplete, or zero-TAP row and no
unresolved PR-958 pass-count decrease. The user owns the release checkout;
workers use private exact-head worktrees and must not mutate, rebase, or push
the user's branch.

The class candidate must also be validated with a private build of the exact
latest pulled Perl source: the available 5.43.9 executable predates the new
leading-underscore accessor rule and cannot serve as its standard-Perl oracle.

Independent implementation continues on `integrate/phase36-post1087-wip`.
Its current preserved tranche generates current-Perl InSC/InPC data and closes
all 4,240 labelled `uniprops02.t` assertions through native Joni property
resolution. It will be rebased only after the refreshed checkpoint merges.

Repair the five blocking rows with targeted, baseline-equivalent reruns before
repeating the complete corpus. Once targeted evidence is green, rerun the
complete latest-Perl corpus, strict PR-958 comparison, and additional release
checks at the same immutable head.

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

- P3: close the complete stable regex debug-trace contract and interpreter eval
  propagation, including exact compile/match/free lifecycle parity and imported
  reducers.
- P4: close the disjoint XID binary-property root, then complete nested/scoped
  extended-character-class interpolation and set-operation parity.
- P5: remove redundant property scanning after the integrated 1,048-row
  `In...` Block-alias correction and the P3 integration reference are published.
- P6: finish exact-head release repairs: both backends at `op/do.t` 71/71,
  deterministic `japh/abigail.t`, and the already delivered class accessor-name
  correction; validate all three with one combined build.
- Coordinator: integration, conflict resolution, immutable acceptance, PR/CI,
  plan state, combined build, and release evidence.

## Ordered Next Steps

1. Fix and rerun the five exact-head release-blocking rows in private worktrees;
   preserve latest-Perl tests and separate changed test cardinality from runtime
   regressions with exact assertion-ID evidence.
2. Integrate the fixes into PR 1087, run one warning-free `make`, focused
   Unicode, `regexp.t`, `regex_sets.t`, `charset.t`, `pat.t`, `pat_thr.t`,
   `pat_advanced.t`, `pat_re_eval.t`, and `reg_mesg.t` gates, then repeat the
   complete corpus, strict PR-958 comparison, additional release checks, and
   exact-head CI. Run `pat.t` and `pat_thr.t` exclusively with no external
   worker builds; the runner's 600-second minimum already exceeds their
   isolated 399/446-second measurements. Increase only that documented
   per-file minimum if a quiet exact-head run still times out.
3. Merge PR 1087 only when every checkpoint gate is green or has a documented
   baseline-equivalent environmental disposition.
4. Meanwhile integrate P3 matcher-state, P4 warning, P5 generated-property,
   and P6 diagnostic/scanner-removal deliveries only into the separate
   post-1087 WIP. Batch focused tests and one combined full build, then rebase
   this WIP onto merged refreshed `master`.
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
- Unicode generators must consume the latest checked-out `perl5/` tables and
  derive their version and source hashes as provenance. Historical source
  hashes or Perl/Unicode versions must not act as pins that block a valid latest
  upstream refresh; checked-in output hashes remain reproducibility gates.

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

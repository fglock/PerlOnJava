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

- [ ] Pass immutable 286-file JVM and interpreter acceptance against PR 958.
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

PR 1087 is saved at exact `0d652229d`, which is warning-free locally and green
on Ubuntu and Windows CI. Integration head `f5cd94899` passes warning-free
`make`, packaging, licensed Joni, and all unit shards; its repaired byte/control,
boundary, quantifier, native-warning, selector, and direct-charname fixtures are
green on JVM and interpreter. Next head `41a585bdc` integrates the independent
Unicode resolver tranche and restores `op/stat.t` baseline parity; combined
licensed Joni and focused JVM/interpreter gates pass. It needs the next batched
full gate before the PR is updated.

The current complete 286-file JVM run executes 420,748 passing assertions and
improves 72 baseline rows, but is not yet a valid release artifact: five files
time out, 19 are incomplete, and the strict comparator reports four unresolved
regex regressions (`pat_advanced_thr.t`, both `pat_special_cc` modes, and
`regex_sets.t`). The integrated subject-sensitive byte/`/d`
and fullwidth-xdigit
fix restores complete `re/charset.t` execution, and the logical-wide-scalar
`chop` fix restores `op/chop.t` to 148/148. The logical-interpreter executable
file-test correction restores `op/stat.t` to the PR 958 threshold, 107/111, on
both backends; its four residual rows are the baseline's volatile `/dev`/TTY
environment cases. The
shared default-`/d` byte-variant root is now closed in focused evidence:
dynamic source provenance, callback search, grapheme boundaries, Unicode
properties/classes, and extended sets pass on both backends while
`re/charset.t` remains 5552/5552. A combined exact-head gate and refreshed
immutable comparison are pending. The production backend selector has been
removed and its packaging/property invariance gates are awaiting the combined
build. The latest focused `reg_mesg.t` artifact is 3198/3292 on both backends.
The native parser/range/structural/runtime-warning diagnostic lease is
exhausted; the residual is redirected Unicode/property/charname and
analyser/debug rows. P5 owns the
`re/pat_advanced.t` source/debug and charname rows. The byte-property fold and
control-verb source correction, selector retirement, P4 boundary/quantifier
diagnostics, and P5 direct custom-charname correction are jointly green at
`61eb48af7`. Further P4 diagnostics and P6's independent caller fix are
integrated through `8ab138bfd`; licensed Joni and the final warning fixture are
green on both execution backends. On the exact combined jar and current private
corpus, `reg_mesg.t` has backend-identical raw TAP: 3287 `ok` and 65 `not ok`
of 3352; the runner counts ten expected TODO outcomes as effective passes
(3297/3352). The resolver tranche closes 28 of its 52 owned source rows; 24
rows await the serialized shared hook.

The interpreter comparison's 53 lower rows are classified: 48 are general
interpreter limitations, four are shared native rows, and its sole independent
regex/interpreter defect is fixed. The remaining shared rows are covered by the
active custom-charname, Unicode-property, and native-diagnostic ownership below;
they must close before dual-backend acceptance.

Active ownership:

- P4: diagnose the zero-TAP `anyof.t` timeout and continue adjacent native
  execution-completion roots; all focused native diagnostic rows are closed.
- P5: fix the a11 eager-validation regression that classifies the core
  `_charnames` bootstrap as a custom translator, then deliver immutable
  custom-charname AST/CV thread transport and close the remaining `\\N{}` /
  custom-charname analyser/debug residual with absolute-launcher corpus gates.
- P6: exhaust the remaining Unicode property diagnostic residual and zero-TAP
  `uniprops01..04.t` performance failures and the `/u` `\\w` complement root in
  both `pat_special_cc` modes; shared runtime files remain centrally serialized.
- Coordinator: backend-selector retirement, integration, conflict resolution,
  immutable acceptance, PR/CI, plan state, combined build, and release evidence.

## Ordered Next Steps

1. Integrate P5's green shared-runtime barrier, then replay only declared P4/P6
   helper contracts and integrate cohesive deliveries, preserving all vendored
   notices. Resolve shared Joni parser changes centrally and retain every
   independent semantic fix.
2. Run one combined warning-free `make`, then refresh focused Unicode,
   `regexp.t`, `regex_sets.t`, `charset.t`, `pat.t`,
   `pat_advanced.t`, `pat_re_eval.t`, and `reg_mesg.t` gates on that exact
   immutable head.
3. Repair and rerun the five comparator regressions and the regex-specific
   timeout/error/incomplete files. If they close, run the complete 286-file JVM
   acceptance and compare file/test identities with
   PR 958. Push that exact green head to PR 1087, run CI, and make the
   incremental PR reviewable.
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
  zero-TAP, timeout, truncated, or incomplete records.
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
- [ ] Complete JVM/interpreter output is compared file-by-file with PR 958.
- [ ] JVM/interpreter and direct/thread results agree.
- [ ] Joni covers constants, closures, conditions, control verbs, recursion,
  dynamic source, byte strings, and Unicode strings.
- [x] `(*MARK:NAME)` and named control-verb state execute in Joni.
- [x] Bounded `pat_psycho*` and `speed*` semantic/performance closure.
- [ ] No supported regex test needs `JPERL_UNIMPLEMENTED=warn`.
- [x] Joni is the sole production matcher; selector compatibility code is
  test-scope-only and absent from production packaging.
- [ ] Matcher-semantic preprocessing and obsolete import patches are gone.
- [ ] Targeted latest-upstream sync is reproducible and idempotent.
- [ ] Feature matrix and architecture/fork documents match implementation.
- [ ] Original copyright/authorship/license notices are preserved.
- [ ] Warmed performance, CPAN, packaging, warning-free build, Ubuntu, Windows,
  and CI pass.
- [ ] Post-merge warn-mode removal and POD capability review are complete.

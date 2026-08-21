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

- [x] Make Joni's property resolver accept raw built-in Perl Script,
  Script_Extensions, Block, Age/Present_In, and alias spellings. Production has
  no `translateUserDefinedProperties` expansion path; only a test/debug
  compatibility description can still render translated property text.
- [ ] Keep raw user-defined property tokens through Joni parsing and resolve or
  defer them through the mode-specific callback cache; remove never-match or
  match-all text substitutions used to represent deferred properties.
- [x] Preserve ordinary/extended-class context and source positions in Joni so
  the adapter no longer scans bracket depth for property policy.
- [x] Replace `hasControlVerbState(String)` with a compiled Joni fact, including
  unnamed verbs, and move the `\K`-inside-lookaround diagnostic into Joni.
- [ ] Delete test-only routing scanners (`requiresJoniBackend` and its empty-
  class/name helpers) once their assertions are replaced by direct Joni facts.
- [ ] Replace `requiresRuntimeUnicodePropertyResolution` and
  `preloadUserDefinedProperties` source scanning with Joni-compiled deferred-
  term facts/enumeration and outside-compiler-lock materialization, while
  preserving safe literal precompilation and Perl's construction-time
  callback/cache semantics. Retain only runtime-neutral trusted-callout
  materialization and documented Perl source-policy checks outside Joni.

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
- [x] Finish remaining non-POSIX native range/parser diagnostics.
- [ ] Refresh complete Unicode, `pat.t`, and `pat_advanced.t` gates.

Exit: Unicode and native syntax corpora execute without compatibility masking.

### Phase 4 — Runtime source and diagnostics

- [x] Runtime callbacks, nested continuations, lexical warning policy, eval
  capture lifetime, thread cloning, source spelling, and callback unwind.
- [x] Native numeric, named-character, quantifier, group-name, extended-class,
  range, and selected POSIX diagnostics.
- [x] `pat_re_eval.t` semantic contract.
- [x] Finish all remaining same-source `reg_mesg.t` diagnostic families.
- [x] Finish analyser warning-policy rows.
- [ ] Finish remaining debug-trace rows.
- [x] Refresh complete `regexp.t`, `reg_mesg.t`, and runtime-source gates.

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
- [x] Pass warning-free `make`, Ubuntu, Windows, and complete CI.
- [ ] Reconcile `docs/reference/feature-matrix.md`,
  `dev/implementation/regex.md`, and `docs/design/joni-callout-fork.md`
  with shipped behavior.
- [ ] Delete redundant documents; retain only concise rationale summaries that
  point to canonical implementation documents.

Exit: release evidence and public/internal documentation match the code.

## Current Release Gate

PR 1091 is merged into `master` at merge commit `4203bf811`. Its exact accepted
head `ff68371af` is the immutable 623-file release baseline: full `make`, Ubuntu,
Windows, packaging, notices/licenses, and strict PR-958 comparison are green.
Imported fixtures remain authoritative and must not be patched to recover old
counts.

The production-load run's two mandatory post-merge repairs are implemented.
DBIx::Class's unexpected uninitialized-match warnings were reduced to undefined
caller subroutine names in BEGIN/CHECK/INIT frames and fixed with a small
system-Perl/JVM/interpreter oracle. Under a representative ten-unit runner load,
direct `pat.t` completed all 1,302 rows in 364.17 seconds and `pat_thr.t` ran
alone and completed the same rows in 354.53 seconds. The scheduler gives
threaded pat the full ten-unit budget while retaining the independent
`japh/abigail.t` isolation barrier. `anyof.t` requires a separate timeout above
600 seconds even when running alone; complete maps use the measured 1,800-second
safety bound rather than conflating that duration with pat thread contention.

Development continues in draft PR 1089 on
`integrate/phase36-post1087-wip`, rebased onto merged `master`. Integrated work
includes Unicode property closure, lexical package propagation, named-character
source mode, overloaded-subject handling, typed Joni debug facts, compiled
control-verb/charset metadata, native `\K`-lookaround diagnostics, finite-high
ANYOFR/ANYOFHbbm rendering, and the special-block caller repair. The final
accepted-corpus `Regex compilation failed` downgrade is reduced to descending
plain-`\N` intervals and fixed in Joni's optimizer. The Java-owned Unicode::UCD
initialization also publishes Perl's signed-IV `MAX_CP` package scalar and
upstream export-list metadata, so imported class-debug expectations no longer
rewrite correct zero digits as `INFTY`. Published checkpoint `4314449ee`
contains these changes and passes exact warning-free `make` in 2m53s, including
all five unit shards, direct Joni tests, packaging, notices, and shadow JAR.
The complete `anyof.t` JVM map has 561 passing and 771 known residual rows,
with no timeout, incomplete record, or execution error. It closes all 35
ANYOFRb, 36 ANYOFHbbm, and 15 bounded `HIGHEST_CP` target rows. The backend map
is identical through the 546-row pre-HIGHEST boundary; the final interpreter
refresh remains part of the complete affected-map gate. The separate
unsigned-INFTY parser ceiling remains a later renderer boundary.

P5's property-specific scanner phase is complete on the post-1091 successor:
the four residual `pat_advanced.t` property rows are closed, Joni now reports
ordinary versus experimental extended-class property context to the resolver,
and the host-side `validateExtendedPropertyPolicy` source walk is removed.
Actual multi-code-point Name properties and deferred user properties are
rejected natively at the exact closing-brace position. The Age-wildcard arm in
`legacyCompatibilityDescription` remains test/debug-only because existing
description-contract tests exercise it; it never feeds compilation or matching.
Generated alias policy now also separates NFD/NFKD Quick_Check's invalid bare
spellings from valid two-valued enumerated assignments. Both backends pass the
complete 42,010-row `uniprops02.t` map, including property-value wildcards.

The post-final-merge warn-mode audit is implementation-ready. The complete
accepted PR 1091 transcript contains one runtime downgrade marker, the
descending plain-`\N` optimizer failure already fixed in this successor. The
remaining explicit warn-policy fixtures either prove native errors stay fatal
or no longer reach an unsupported path. Final removal still requires the
planned strict 623-file A/B run because test-local warning handlers may hide a
marker from the outer transcript.

Current exact-artifact diagnostic maps are also closed: both JVM and
interpreter pass all 3,390 `reg_mesg.t` rows and all 2,210 `regexp.t` rows.
Executable, wrapper, JAR, cwd, and command identities are retained with the
maps. Native diagnostics require no additional source change.

### Execution Tracker

- [x] Reproducible PR 958 baseline and strict comparator.
- [x] Conditions, callbacks, control verbs, and backtracking-visible state.
- [x] Runtime regex source, lexical context, callback unwind, and diagnostics.
- [x] Ordinary-pattern Joni parity: prove the full corpus with native byte and
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

- P3: own the active long native deferred user-property class migration,
  matcher-local resolution cache, and whole-pattern-recompiler deletion. The
  bounded HIGHEST_CP renderer and its complete zero-introduction JVM map are
  complete and integrated.
- P4: close the remaining non-property, non-class-renderer debug-trace
  semantics from complete `pat_advanced.t` and lexical debug lifecycle maps.
- P5: design the final `requiresRuntimeUnicodePropertyResolution` and
  `preloadUserDefinedProperties` scanner retirement from complete source call
  graphs and standard-Perl callback-timing/provenance oracles, ready for the
  exact post-A24 writable base. Canonical regex architecture reconciliation and
  the redundant-document inventory are integrated.
- P6: validate the final-acceptance orchestrator, ledger, direct/thread
  inventory, packaging identity, and command manifest at exact checkpoint
  `4314449ee` without starting the expensive corpus.
- Coordinator: integration, conflict resolution, immutable acceptance, PR/CI,
  plan state, combined builds, worker rebasing, release evidence, and final
  warn-mode removal execution after final integration.

## Ordered Next Steps

1. Integrate native deferred user-property matching and the remaining native
   debug-trace root, then run one combined warning-free build and affected maps.
2. Delete remaining production migration scaffolding and prove all constants,
   closures, conditions, verbs, recursion, dynamic source, byte strings, and
   Unicode strings execute through Joni.
3. Run complete JVM/interpreter acceptance, direct/thread parity, affected CPAN
   suites, five warmed performance samples, packaging, notices/licenses,
   warning-free build, and platform CI.
4. Reconcile final documentation and remove redundant design material.
5. After the final implementation PR is merged to `master`, remove automatic
   regex `JPERL_UNIMPLEMENTED=warn` injection from
   `dev/tools/perl_test_runner.pl`; rerun the complete corpus; then delete the
   RuntimeRegex warning-plus-never-match downgrade and obsolete tests/docs.
6. On final `master`, review shipped behavior against
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
- [ ] Complete latest-Perl JVM output is compared file-by-file with PR 958
  (the PR 1091 sync currently discovers 623 files).
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

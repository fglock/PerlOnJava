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
  no `translateUserDefinedProperties` expansion path, and native source
  identity is now used directly for test/debug descriptions.
- [x] Keep raw user-defined property tokens through Joni parsing and resolve or
  defer them through the mode-specific callback cache; remove never-match or
  match-all text substitutions used to represent deferred properties.
- [x] Preserve ordinary/extended-class context and source positions in Joni so
  the adapter no longer scans bracket depth for property policy.
- [x] Replace `hasControlVerbState(String)` with a compiled Joni fact, including
  unnamed verbs, and move the `\K`-inside-lookaround diagnostic into Joni.
- [x] Delete test-only routing scanners (`requiresJoniBackend` and its empty-
  class/name helpers) once their assertions are replaced by direct Joni facts.
- [x] Compile raw `\Q...\E` in Joni, preserving warning timing/category, and
  remove host `RegexQuoteMeta.escapeQ()` source rewriting.
- [x] Replace the inline-`/p` raw-source scan with immutable parser-owned Joni
  metadata while preserving match-variable retention.
- [x] Replace `hasUnicodePromotingPatternSyntax()` with a parser-owned fact and
  make byte/Unicode variant/cache selection consume the compiled result.
- [x] Delete the now-unreferenced production `CharacterClassMapper`; retain
  executable-source admission, taint/security, trusted-slot, and diagnostic
  provenance code unless a non-executing Joni fact safely replaces it.
- [x] Replace the custom-charnames raw `contains("\\N{")` cache bypass with
  parser/frontend-owned participation metadata and translator/source-mode
  identity.
- [x] Remove the exact-source Perl16894 alternate-capture correction after
  native Joni capture-history behavior passes a focused standard-Perl oracle.
- [x] Retire the exact-source XMP and XML manual matcher selectors behind
  correctness and bounded warmed-performance gates.
- [x] Replace `requiresRuntimeUnicodePropertyResolution` and
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
- [x] Treat a class term followed by a trailing literal hyphen as two members
  without a false-range warning, including POSIX/property and negated classes.
- [x] Apply Perl `Pattern_White_Space` under `/x`, including NEL and the
  Unicode line/paragraph separators, without changing character-class data.
- [x] Finish execution-time `/l` mixed classes, negation, locale switching,
  simple/full folding selection, warnings, taint, and runtime/thread isolation.
- [x] Refresh complete `pat.t`/`pat_thr.t` gates with identical complete
  direct/thread maps and no PR 958 regression.
- [ ] Close the retained `pat.t` malformed-input, recursion-diagnostic,
  overflow, and exact-diagnostic rows without repeating the map.
- [x] Refresh complete Unicode and `pat_advanced.t` gates on both backends;
  all 14 files and 413,526 assertions pass in each mode.

Exit: Unicode and native syntax corpora execute without compatibility masking.

### Phase 4 — Runtime source and diagnostics

- [x] Runtime callbacks, nested continuations, lexical warning policy, eval
  capture lifetime, thread cloning, source spelling, and callback unwind.
- [x] Native numeric, named-character, quantifier, group-name, extended-class,
  range, and selected POSIX diagnostics.
- [x] `pat_re_eval.t` semantic contract.
- [x] Finish all remaining same-source `reg_mesg.t` diagnostic families.
- [x] Finish analyser warning-policy rows.
- [x] Finish remaining debug-trace rows, including top-level fatal diagnostic
  publication before the failed-regex free lifecycle.
- [x] Refresh complete `regexp.t`, `reg_mesg.t`, and runtime-source gates.

Exit: generated regexes, warnings, fatality, categories, text, and locations
agree with standard Perl on JVM and interpreter.

### Phase 5 — Remove migration scaffolding

- [x] Remove Java matcher storage and production routing.
- [x] Remove native-replaced extended-class, branch-reset, lookbehind, DEFINE,
  recursion, numeric-reference, and matcher-semantic adapter rewrites.
- [x] Remove obsolete imported regex patches and prove targeted sync idempotent.
- [x] Delete remaining matcher-semantic preprocessing, source scanners, and
  unreachable adapters after their native gates pass.
- [x] Prove no environment setting can select a production Java matcher.

Exit: Joni is the sole matcher and only documented Perl source policy remains
outside it.

### Phase 6 — Release and documentation

- [ ] Pass immutable complete latest-Perl JVM acceptance against PR 958 by
  matched path, including every test discovered from the exact current
  checkout. The mutable upstream file count is evidence, never a requirement.
- [ ] Pass the complete regex-bearing ledger on the interpreter and reconcile
  every JVM/interpreter semantic difference.
- [ ] Pass direct/thread parity, bounded `pat_psycho*` and `speed*`,
  affected CPAN suites, packaging, notice/license, and warmed performance gates.
- [x] Pass the five-run warmed ordinary-regex comparison: candidate median
  12.23s versus exact-parent 12.68s under alternating contended runs.
- [ ] Pass warning-free `make`, Ubuntu, Windows, and complete CI on the final
  acceptance head.
- [ ] Reconcile `docs/reference/feature-matrix.md`,
  `dev/implementation/regex.md`, and `docs/design/joni-callout-fork.md`
  with shipped behavior.
- [ ] Delete redundant documents; retain only concise rationale summaries that
  point to canonical implementation documents.

Exit: release evidence and public/internal documentation match the code.

## Current Release Gate

The immutable comparison baseline is
`../PerlOnJava/logs/test_20260815_080000_958.log`. A mergeable increment must
retain every baseline-passing row, improve or preserve each test-file count,
produce complete nonzero records, pass warning-free `make`, and pass platform
CI. Imported fixtures remain authoritative and are never patched to recover
counts. `pat.t`/`pat_thr.t` use the load-aware scheduler contract;
`anyof.t` uses the measured 1,800-second per-file bound.

Open implementation blockers are the retained `pat.t` diagnostic residuals.
The synthetic-start-class optimizer inventory and Perl73464 performance
classification, complete Unicode maps, compatibility-description island,
runtime `LC_CTYPE`, fatal/free ordering, known nonlocale `anyof.t` renderer
roots, and opt-in native parser trace transport are complete.
Final
acceptance then runs the immutable latest-Perl ledger on both backends,
direct/thread parity, affected CPAN suites, warmed performance,
packaging/notices/licenses, and platform CI from one exact clean head.

The implementation already satisfies the architectural invariants in this
document: Joni is the sole production matcher, generated Perl Unicode data is
used natively, runtime callbacks and deferred properties remain runtime-local,
and matcher-semantic host preprocessing and backend routing are absent. The
post-merge warn-mode removal remains gated by a strict complete-corpus A/B run.

The strict ledger and comparator reject unresolved references, zero-TAP,
timeout, malformed, truncated, or executable-identity-mismatched records.
Acceptance artifacts retain command, wrapper, JAR, commit, cwd, raw TAP, and
machine-readable comparison identity so expensive maps are reusable.

### Execution Tracker

- [x] Reproducible PR 958 baseline and strict comparator.
- [x] Conditions, callbacks, control verbs, and backtracking-visible state.
- [x] Runtime regex source, lexical context, callback unwind, and diagnostics.
- [x] Ordinary-pattern Joni parity: prove the full corpus with native byte and
  Unicode matching and no Java matcher fallback.
- [x] Unicode and pattern syntax foundations, including nested scoped
  extended-class interpolation and the known nonlocale `anyof.t` roots.
- [x] Refresh the complete Unicode, `pat.t`, and `pat_advanced.t` maps; the
  Unicode/`pat_advanced` gate is 413,526/413,526 on both backends.
- [ ] Close the retained corpus-derived `pat.t` diagnostic roots without
  repeating the complete map.
- [x] Production Java matcher/backend selector and legacy `RegexPreprocessor`
  are absent; historical routing fixtures assert parser-owned Joni facts
  directly and no source scanner decides a backend.
- [x] Obsolete imported regex patches are removed and targeted sync is
  idempotent.
- [x] Replace backend-selection and `\\G` source scans with immutable
  parser-owned Joni program metadata; pattern descriptions now expose native
  source identity without compatibility translators or scanners.
- [ ] Complete integration, dual-backend/direct-thread/CPAN/performance gates,
  platform CI, documentation reconciliation, and post-merge checks.
- [x] Retire the final production host semantic seams, including the residual
  renderer closure and the last test/debug compatibility-description island.
  Parser-owned Unicode promotion, XMP/XML matcher-selector retirement, native
  `\Q...\E`, inline `/p`, dead mapper, custom-charname substring-probe, and
  alternate-capture correction retirement are complete; the production
  source-seam audit classifies every retained scanner by ownership and evidence.

Active ownership:

- P5 / A74: close malformed UTF-8, recursion/codeblock/overflow diagnostics,
  GH17384, and Perl133921 from the retained `pat.t` map.
- P6 / A71: finish affected-CPAN classification, including the proven JVM
  warning-scope defect and bounded Object::InsideOut failures, without repeating
  complete distribution runs.
- Coordinator: integrate deliveries, maintain the exact clean acceptance head
  and PR/CI, run combined gates, close integration regressions, and execute the
  final ledger and documentation reconciliation. The native trailing-hyphen
  warning and extended-pattern Unicode-whitespace roots are closed; their
  project tests agree with system Perl on the integration head.

## Ordered Next Steps

1. Close the retained `pat.t` diagnostic residuals.
2. Run combined focused and warning-free build gates, then verify the complete
   dual-backend Unicode and `pat_advanced.t` maps without introductions.
   Re-run complete `anyof.t` or `pat.t` only if those maps expose a root that
   the retained focused contracts cannot classify.
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

- Never modify imported tests to accommodate PerlOnJava. Existing project-owned
  tests may be corrected or strengthened only when the resulting expectations
  are first proven with system Perl; add focused reducers for new behavior.
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
- [ ] Complete latest-Perl JVM output is compared file-by-file with PR 958;
  current discovery is recorded from the exact checkout without a pinned count.
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

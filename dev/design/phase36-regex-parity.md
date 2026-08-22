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
- [x] Close the retained `pat.t` malformed-input, recursion-diagnostic,
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
- [x] Resolve the DateTime `t/46warnings.t` far-future `from_epoch` warning
  payload mismatch; the focused file and bounded 3589-test distribution pass on
  both backends.
- [x] Remove DateTime's false `Unescaped left brace` diagnostic for `%{...}`
  text inside an `/x` comment while retaining real brace diagnostics and
  single-emission construction timing.
- [x] Preserve installed constant CV identity through symbolic stash
  self-assignment; `t/not-methods.t` now classifies direct-stash CODE entries
  like glob-backed methods on both backends.
- [x] Preserve `Sub::Util` require visibility and defer interpreter Java-module
  initialization so Moo selects `Sub::Name::subname` on both backends.
- [ ] Eliminate Moo's unexpected subroutine-redefinition diagnostics, then pass
  bounded `./jcpan -t Moo` without unapproved warning shapes.
- [x] Resolve the Template Toolkit `t/compile3.t` parser failure for `[% - %]`
  with a minimal system-Perl oracle and both PerlOnJava backends.
- [x] Pass Template Toolkit `t/document_methods.t` on system Perl and both
  PerlOnJava backends after the lexical warning-precedence repair.
- [ ] Pass bounded `./jcpan -t Template` without parser, warning, or regex-state
  regressions before release.
- [x] Preserve Test::Builder's lexical numeric-warning suppression when `$^W`
  is enabled, proven by a distribution-independent system-Perl oracle on both
  PerlOnJava backends.
- [ ] Resolve any remaining unapproved warning shapes exposed by affected CPAN
  suites with system-Perl-green reducers and product fixes rather than approval
  or global suppression.
- [ ] Complete lexical `use/no re '/flags'` parity for `/d`, `/l`, `/n`, `/p`,
  `/a`, `/aa`, `/u`, ordinary flags, mixed combinations, charset cancellation,
  and nested restoration; prove the matrix on system Perl and both backends.
- [ ] Settle the remaining POD-derived edge contracts with focused three-way
  tests: non-UTF-8 `(?[...])/l` warnings, `\N{3}` disambiguation,
  `@{^CAPTURE}`, script-run plus `(*ACCEPT)`, unusual delimiters, and
  extended-class no-multifold behavior. Treat failures as implementation work,
  not documentation exceptions.
- [x] Pass the five-run warmed ordinary-regex comparison: candidate median
  12.23s versus exact-parent 12.68s under alternating contended runs.
- [ ] Pass warning-free `make`, Ubuntu, Windows, and complete CI on the final
  acceptance head.
- [ ] Reconcile the final POD capability audit into
  `docs/reference/feature-matrix.md`,
  `dev/implementation/regex.md`, and `docs/design/joni-callout-fork.md`
  with shipped behavior, including a dedicated script-run row, complete
  `re`-pragma state, `$^N`, and `@{^CAPTURE}` evidence.
- [x] Delete redundant documents; retain only concise rationale summaries that
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

The retained `pat.t` optimizer and diagnostic inventories are complete. The
synthetic-start-class optimizer inventory and Perl73464 performance
classification, complete Unicode maps, compatibility-description island,
runtime `LC_CTYPE`, fatal/free ordering, known nonlocale `anyof.t` renderer
roots, opt-in native parser trace transport, and the affected-CPAN JVM warning-
context selection defect are complete. Runtime locale state initializes from
`LC_ALL`, `LC_CTYPE`, then `LANG`, so `/l` folding and environment-locale
publication agree with Perl from the first match. Literal regexes in anonymous
thread entries are validated in the parent parser with the enclosing eval's
lexical warning bits, so fatal construction diagnostics reach the parent eval
before a child exists. User-defined property callbacks materialize outside
Joni's synchronized native compilation region, so concurrent definitions can
reach Perl's shared-property timeout without serializing the callback itself.
Generated Unicode provenance follows the current Perl checkout and all fifteen
families reproduce deterministically. The known PR 958-negative non-regex rows
are closed or classified against current source: Unicode variables and socket
rows are restored, `op/do.t` is a smaller current corpus, and JAPH's single
boundary reproduces under system Perl's environment-sensitive shell harness.
Final acceptance then runs the immutable latest-Perl ledger on both backends,
direct/thread parity, affected CPAN suites, warmed performance,
packaging/notices/licenses, and platform CI from one exact clean head.

The implementation already satisfies the architectural invariants in this
document: Joni is the sole production matcher, generated Perl Unicode data is
used natively, runtime callbacks and deferred properties remain runtime-local,
and matcher-semantic host preprocessing and backend routing are absent. The
post-merge warn-mode removal remains gated by a strict complete-corpus A/B run.

The acceptance runner produces two fail-closed views from one execution. The
136-file semantic set mechanically derived from core regex, direct/thread,
thread-only, and documented unit gates rejects unresolved references, zero-TAP,
timeout, malformed, truncated, or executable-identity-mismatched records. The
complete 622-file map, including the 286-file broad regex-bearing scope,
rejects every PR 958 passing-count regression and every newly invalid row while
retaining inherited platform, build-tree, and broad-language invalid rows as
explicit classified evidence. Acceptance artifacts retain
command, wrapper, JAR, commit, cwd, raw TAP, and machine-readable comparison
identity so expensive maps are reusable. File counts are current discovery
evidence rather than pinned requirements.

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
- [x] Close the retained corpus-derived `pat.t` diagnostic roots without
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

- Coordinator: maintain the exact clean acceptance head and PR/CI, run the
  combined build and complete latest-Perl ledger, classify integration
  regressions, and execute the post-merge warn-mode and documentation review.
- Acceptance workers: from the coordinator-published immutable head/JAR/SBOM,
  execute the old-invalid-row preflight, direct/thread and provenance gates,
  affected CPAN suites, and warmed performance checks without source mutation.

## Ordered Next Steps

1. Publish one exact warning-free build/JAR identity and freeze it for all
   remaining release evidence. Before repeating the expensive complete map,
   rerun every row that was error/incomplete in the latest immutable
   reconnaissance; require complete TAP for the strict regex subset and an
   unchanged, explicitly classified platform/build-tree/broad-language record
   elsewhere. Re-run complete `anyof.t` or `pat.t` only if this preflight
   exposes a root that retained focused contracts cannot classify.
2. From that preflight-clean identity, run complete latest-Perl
   JVM/interpreter acceptance,
   direct/thread parity, affected CPAN suites, five warmed performance samples,
   packaging, notices/licenses, and platform CI. Reject evidence from any
   earlier source or executable identity.
   Include a focused DateTime far-future `from_epoch` warning-payload reducer
   and bounded DateTime distribution run; classify it independently from the
   Test::Builder warning-scope repair.
   Include focused Moo reducers for `Sub::Name::subname`, direct stash CODE
   entries versus glob-backed methods, and warning-category handling before the
   bounded Moo distribution rerun; classify regex relevance from evidence.
   Include a focused Template Toolkit reducer for the `[% - %]` compile token
   and the failing `t/compile3.t` case before the bounded distribution rerun;
   classify whether the root is regex tokenization, match-state, or parser-only.
   Reject affected-CPAN evidence containing unapproved warning shapes even when
   its TAP rows pass; retain separate reducers for Test::Builder counter
   corruption and each warning-category or lexical-suppression defect.
3. After the final implementation PR is merged to `master`, retire the
   temporary regex warn-mode policy in fail-closed order:
   - remove every regex-file `JPERL_UNIMPLEMENTED=warn` injection from
     `dev/tools/perl_test_runner.pl` and rerun all formerly listed files on both
     backends, rejecting missing, zero-TAP, incomplete, timeout, and count
     regressions;
   - close any exposed semantic gaps, then remove RuntimeRegex's unsupported-
     feature suppression, generic unimplemented wrapper, environment lookup,
     and warning-plus-`(?!)` downgrade while retaining literal diagnostics,
     executable-source admission, trusted callout materialization, lexical
     policy, and user-property callbacks;
   - prove Object::InsideOut and Logger::Simple without warn mode, retire the
     bundled Logger::Simple distroprefs workaround, and preserve cleanup of old
     PerlOnJava-owned preferences without deleting user-owned preferences;
   - remove regex-unit warn-mode assumptions and reconcile active guidance in
     `AGENTS.md`, regex implementation/design documents, and debugging skills.
   Retain and document Perl's own fatal behavior for Unicode string properties
   inside `(?[...])`; that diagnostic is expected parity, not missing matcher
   work and not policy scaffolding.
4. On final `master`, review shipped behavior against
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
- Compare complete output with the PR 958 baseline. Reject missing rows,
  passing-count regressions, and newly invalid/timeout/truncated/incomplete/
  zero-TAP records. For the strict regex subset, reject every invalid record.
  Preserve and classify inherited platform/build-tree/broad-language invalid
  rows separately; they cannot satisfy the strict regex gate.
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
- [ ] Feature matrix and architecture/fork documents match the final
  implementation and the seven-POD capability audit.
- [x] Original copyright/authorship/license notices are preserved.
- [ ] Warmed performance, CPAN, packaging, warning-free build, Ubuntu, Windows,
  and CI pass.
- [x] DateTime far-future `from_epoch` emits Perl-compatible warning text, not
  an array-reference string, and its affected distribution test passes.
- [x] DateTime emits no false unescaped-brace warning for `/x` comment text.
- [x] Moo direct-stash CODE method classification matches Perl on both backends.
- [x] Moo's `_subname` selection matches Perl on both backends.
- [ ] Moo's unexpected redefinition diagnostics are absent under the distro's
  warning policy and the affected distribution test passes warning-free.
- [x] Template Toolkit accepts the `[% - %]` compile-token case.
- [x] Template Toolkit's `t/document_methods.t` expectations pass on both
  PerlOnJava backends.
- [ ] Template Toolkit's full affected distribution passes without parser,
  warning, or regex-state regressions.
- [x] Test::Builder descriptions do not emit numeric-conversion warnings when
  lexical suppression applies under package-global warnings.
- [ ] Affected CPAN suites emit no other unapproved warning shapes.
- [ ] Post-merge warn-mode removal and POD capability review are complete.

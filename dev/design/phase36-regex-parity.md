# Full Perl Regex Semantic Parity

## Goal

Implement the regex semantics of the latest imported upstream Perl on both
PerlOnJava execution backends, with the vendored Joni fork as the sole
production matcher.

The immutable complete-corpus no-regression baseline is:

```text
../PerlOnJava/logs/test_20260821_143000_1091.log
```

An implementation PR may merge incrementally when every valid baseline test row
retains at least its baseline passing count and at least one row improves. That is
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
- [x] Emit machine-readable file/test comparison evidence against the baseline.

Exit: every implementation slice has exact rows, a standard-Perl oracle, and a
repeatable baseline.

### Phase 1 — Ordinary-pattern Joni parity

- [x] Close all ordinary-pattern baseline regressions.
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
  direct/thread maps and no baseline regression.
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

Checked items in this phase record focused implementation milestones only.
They do not declare a distribution, corpus, or release gate complete; only the
unchecked, frozen-identity requirements in **Final Acceptance** do that.

- [ ] Pass immutable complete latest-Perl JVM acceptance against the PR 1091
  baseline by
  matched path, including every test discovered from the exact current
  checkout. The mutable upstream file count is evidence, never a requirement.
- [ ] Pass the complete regex-bearing ledger on the interpreter and reconcile
  every JVM/interpreter semantic difference.
- [ ] Pass direct/thread parity, bounded `pat_psycho*` and `speed*`,
  affected CPAN suites, packaging, notice/license, and warmed performance gates.
- [x] Resolve the DateTime t/46warnings.t far-future `from_epoch` warning
  payload mismatch with permanent system-Perl, JVM, and interpreter coverage.
- [x] Remove DateTime's false `Unescaped left brace` diagnostic for `%{...}`
  text inside an `/x` comment while retaining real brace diagnostics and
  single-emission construction timing.
- [x] Preserve installed constant CV identity through symbolic stash
  self-assignment; t/not-methods.t now classifies direct-stash CODE entries
  like glob-backed methods on both backends.
- [x] Preserve `Sub::Util` require visibility and defer interpreter Java-module
  initialization so Moo selects `Sub::Name::subname` on both backends.
- [x] Eliminate Moo's unexpected subroutine-redefinition diagnostics with
  permanent warning-policy coverage and focused unchanged files on both
  backends.
- [ ] Pass Template Toolkit as part of the sealed affected-CPAN acceptance on
  the frozen final source/JAR identity, without parser, warning, regex-state,
  timeout, or TAP-completeness failures. Its mandatory permanent focused gates
  include the `t/compile3.t` `[% - %]` parser behavior and
  `t/document_methods.t` lexical warning precedence on system Perl, JVM, and
  interpreter.
- [x] Close the remaining shared `pat.t`/`pat_thr.t` semantic roots in match
  state and capture mutability, Unicode/charset handling, interpolated `qr//`
  identity, optimizer labels, and diagnostic source location. The Perl73464
  required-tail miss now fails quickly in native Joni; retain its sole upstream
  timeout-expectation row as a classified non-semantic performance boundary.
- [x] Close `pat_rt_report.t` assertions 44, 157, 158, and 217 by semantic root
  with permanent system-Perl, both-backend, and direct-Joni coverage where
  fork-owned.
- [x] Close `pat_rt_report.t` assertion 144 at its pre-regex `pack 'U0...'`
  malformed-UTF-8 boundary. The complete file must remain 2515/2515 on both
  backends without a compensating Joni change.
- [x] Close dynamic source-policy edge parity discovered by the final peer
  matrix: inline `(?x)`/`(?-x)` executable-source scanning, nested/initial
  character-class recursion safety, overloaded plain-string executable
  provenance, escaped hash behavior under `/x`, and strict-ASCII `/iaa`
  matching and substitution. Keep these as frontend policy and native Joni
  flags; do not reintroduce pattern rewriting or matcher fallback.
- [x] Preserve Test::Builder's lexical numeric-warning suppression when `$^W`
  is enabled, proven by a distribution-independent system-Perl oracle on both
  PerlOnJava backends.
- [ ] Resolve any remaining unapproved warning shapes exposed by affected CPAN
  suites with system-Perl-green reducers and product fixes rather than approval
  or global suppression.
- [x] Resolve `Time::HiRes::time` and `cond_timedwait` onto one calibrated
  monotonic epoch clock. The permanent elapsed/timeout/relock regression and
  the existing condition test must pass on both backends without weakening the
  load-sensitive assertion.
- [x] Preserve Perl's lossless integral NV-to-IV/UV multiplication provenance
  so Regexp::Common's dependency-complete square-number suite receives exact
  decimal subjects and captures on the JVM and focused arithmetic on both
  backends.
- [x] Close the interpreter-only Test::Regexp execution boundary; unchanged
  Regexp::Common `t/number/701_squares.t` executes 834/834 on both backends.
- [x] Complete lexical `use/no re '/flags'` parity for `/d`, `/l`, `/n`, `/p`,
  `/a`, `/aa`, `/u`, ordinary flags, mixed combinations, charset cancellation,
  and nested restoration; prove the matrix on system Perl and both backends.
- [x] Settle the remaining POD-derived edge contracts with focused three-way
  tests: non-UTF-8 `(?[...])/l` warnings, `\N{3}` disambiguation,
  `@{^CAPTURE}`, script-run plus `(*ACCEPT)`, unusual delimiters, and
  extended-class no-multifold behavior. Treat failures as implementation work,
  not documentation exceptions.
- [x] Complete Perl 5.44 `feature 'enhanced_xx'` as lexical state plus native
  Joni character-class parsing and warnings. Restore both former public
  `RegexFlags` constructor contracts and Javadoc; retain NEL and the five
  non-ASCII whitespace code points inside enhanced classes; preserve lexical
  state through interpreter string `eval` with nested restoration; and pass
  the sealed exact-Perl/JVM/interpreter/direct-Joni/API reducer.
- [x] Resolve the direct `\K`-inside-lookaround POD/source conflict against an
  executable built from the exact latest upstream Perl tip. Preserve native
  Joni rejection if that oracle rejects the four forms; implement only forms
  the current executable accepts, and never freeze behavior merely described
  as undefined by stale documentation.
- [x] Pass a five-run warmed ordinary-regex preflight before final freeze;
  repeat the same gate on the frozen acceptance identity.
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
`../PerlOnJava/logs/test_20260821_143000_1091.log` (SHA-256
`9adef3dde92414bee49cbb571f65e8fcc705e034189de37d6a6136672bc67211`). A mergeable increment must
retain every baseline-passing row, improve or preserve each test-file count,
produce complete nonzero records, pass warning-free `make`, and pass platform
CI. Imported fixtures remain authoritative and are never patched to recover
counts. `pat.t`/`pat_thr.t` use the load-aware scheduler contract;
`anyof.t` uses the measured 1,800-second per-file bound.

The retained `pat.t` optimizer and diagnostic inventories are complete. The
retained Unicode and advanced-pattern maps are complete. Joni is the sole
production matcher; generated current-checkout Perl Unicode data is used
natively; runtime callbacks and deferred properties remain runtime-local; and
matcher-semantic host preprocessing and backend routing are absent. Shared
`pat.t`/`pat_thr.t` match-state, Unicode/progression, diagnostic-provenance,
strict-ASCII, and interpolated-`qr` semantic clusters are integrated. The open
dynamic source-policy matrix and Perl73464 required-tail optimizer are
integrated, and their focused/direct/thread gates pass. Final diagnostic and
runtime-source preflight has closed locale wide-fold warning/folding,
postponed runtime-source byte/Unicode identity, and unknown-width dynamic
quantifier warnings on both backends. Dependency-complete Regexp::Common is
closed: unchanged `t/number/701_squares.t` executes 834/834 on both backends.
The complete direct/thread projection is accepted with only `pat.t` and
`pat_thr.t` assertion 1149 retained under the documented Perl73464
non-semantic performance allowlist; all shared semantic differences are
closed. A two-pass latest-Perl sync processes 217/217 rows and 4,575 targets
byte-identically with zero tracked diff, including `Name.pl`; it must be
repeated only if upstream or protected inputs change before freeze.

The seven-POD audit's implementation questions are closed. The parser-owned
`enhanced_xx` implementation preserves the two legacy `RegexFlags` APIs,
retains non-ASCII whitespace inside enhanced classes, and propagates lexical
state through interpreter string `eval`; its sealed exact-Perl/JVM/interpreter,
direct-Joni, and API correction gates are independently accepted. Exact
latest-tip Perl v5.45.3 rejects all four direct
`\K` lookaround forms, agreeing with current upstream source and existing
system-Perl-grounded tests; the older POD sentence is a documented upstream
divergence, not missing Joni behavior. Post-merge
warn-mode removal remains gated by a strict complete-corpus A/B run; acceptance
tooling clears inherited warn mode and cryptographically records the explicit
unset boundary.

The acceptance runner produces two fail-closed views from one execution. The
current 146-file semantic set mechanically derived from core regex, direct/thread,
thread-only, and documented unit gates rejects unresolved references, zero-TAP,
timeout, malformed, truncated, or executable-identity-mismatched records. The
complete-corpus map (623 files in the baseline capture), including the 286-file
broad regex-bearing scope, rejects every baseline passing-count regression and
every newly invalid row while
retaining inherited platform, build-tree, and broad-language invalid rows as
explicit classified evidence. Acceptance artifacts retain
command, wrapper, JAR, commit, cwd, raw TAP, and machine-readable comparison
identity so expensive maps are reusable. File counts are current discovery
evidence rather than pinned requirements.

### Execution Tracker

- [x] Reproducible immutable baseline and strict comparator.
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
- [x] Obsolete regex-semantic import patches are removed; the current complete
  sync is idempotent and retained non-regex portability/layout patches remain
  owned by their product lanes.
- [x] Replace backend-selection and `\\G` source scans with immutable
  parser-owned Joni program metadata; pattern descriptions now expose native
  source identity without compatibility translators or scanners.
- [ ] Complete integration, the latest-Perl dual-backend differential, sealed
  CPAN/performance/packaging gates, platform CI, documentation reconciliation,
  and post-merge checks. The direct/thread projection is complete.
- [x] Retire the final production host semantic seams, including the residual
  renderer closure and the last test/debug compatibility-description island.
  Parser-owned Unicode promotion, XMP/XML matcher-selector retirement, native
  `\Q...\E`, inline `/p`, dead mapper, custom-charname substring-probe, and
  alternate-capture correction retirement are complete; the production
  source-seam audit classifies every retained scanner by ownership and evidence.

Current lanes:

- Integration: Type::Tiny's obsolete three-file callback skip and inert Moo
  preference are retired; Template foreach/continue alias parity is integrated;
  acceptance tools seal an explicitly unset warn-mode boundary. PR 1089's
  current remote snapshot fails Windows only at the forced-IPC::Run argv case
  in `cpan_tooling_runtime.t`. The next candidate contains the product-
  classpath relaunch correction and additive coverage; final Windows CI is the
  remaining platform confirmation.
- Remaining interpreter/CPAN roots: the complete Template interpreter ledger is
  closed at 121 files/3,306 tests with all prior roots reconciled, and the
  WWW::Mechanize short-circuit lexical fix is integrated. Retain both for the
  sealed final-identity CPAN gate rather than repeating intermediate suites.
- Performance: the rejected weak lifecycle index remains excluded. The bounded
  replacement removes direct-map wrapper allocation and caches reflection
  capture descriptors without changing weak-reference semantics; its focused
  tests and warning-free build are accepted, and it is reserved as the final
  product commit so its exact parent is the completed candidate. Complete and
  independently accept the authoritative benchmark producer/evaluator before
  replaying it. That producer must execute the selected launchers and exact
  artifacts itself and record timing, live heap, allocation rate, RSS,
  JFR/JDK/JFC, and Git-verified exact-parent identity; uploaded command files or
  self-declared summaries are evidence, not execution authority.
- Regex language: `enhanced_xx` is integrated and independently accepted for
  constructor/Javadoc compatibility, ASCII-only ignored class whitespace, and
  interpreter string-`eval` lexical inheritance. The sealed 47-row reducer
  passes exact Perl and both backends; direct-Joni/API and permanent companion
  gates pass. Direct `\K` inside lookaround is closed as a stale-POD divergence.
- Packaging/provenance: fork notice/generated Unicode attribution, truthful
  fork SBOM, embedded/external equality, and relocated `installDist`/Debian
  payload sources are integrated. The effective-launcher/case-insensitive-JAR
  verifier and strict release-manifest wrapper are independently accepted and
  integrated; the wrapper bounds descriptor input, confines it to the sealed
  root, rejects mutation, and publishes atomically. Before freeze, connect the
  authoritative performance producer, replace legacy summary authority with
  one final ten-gate envelope, and add structured exact-identity evidence for
  latest-Perl sync, fresh distribution packages, and CI. Existing package
  artifacts whose notices predate the provenance changes are stale and cannot
  satisfy acceptance. The CPAN TAP parser's unique-file/TODO/nested-output/
  final-summary contracts are integrated and its focused proof passes.
  The bounded CI workflow contract and authoritative CI evidence producer are
  integrated: they pin the canonical repository/workflow/check identities,
  reject job-level bypasses, derive platform timeout headroom, and revalidate
  checked-in producer bytes immediately before exclusive publication. The
  latest-Perl sync producer is likewise integrated with all five real tool
  identities bound and producer-side prerequisite authority removed. The
  package producer is independently accepted and integrated. Its strict path
  emits the authoritative package bridge; its compatibility path preserves the
  established report contract but is explicitly non-authoritative and cannot
  satisfy the final envelope. The A232 performance-wrapper bridge is also
  independently accepted and integrated, making the final release wrapper the
  sole performance authority and rejecting legacy-only or mixed authority.
  The authoritative make producer is independently accepted and integrated.
  It executes `make` itself, rejects warnings and incomplete success, binds the
  exact source/JAR/runtime/embedded identities, and publishes durable
  no-replace authority evidence. Focused tooling tests do not substitute for
  final frozen-identity execution.
- Acceptance launch wiring: the corpus producer's package/make-authenticated
  launch contract and exact `release_authority` handoff are independently
  accepted and integrated. The CPAN bridge and consumer are likewise
  independently accepted and integrated: compatibility bundles bind bytes but
  cannot execute, while release execution requires canonical producers, actual
  JAR/SBOM inspection, and exact execution authority. The final assembler must
  now consume these accepted schemas and compare the complete retained strict-
  regex JVM and interpreter result maps before source freeze.
- Documentation tooling: the feature matrix, implementation guide, and fork
  design now describe integrated enhanced `/xx`, its exact ASCII class-
  whitespace boundary, ordinary `\K` ownership/evidence, and the intentional
  direct-lookaround divergence. The current POD projection passes with 517
  mapped rows, 42 exclusions, and 15 evidence families, and all document links
  pass. One primary map row remains explicitly stale because an existing
  semantic test hard-codes enhanced-xx as missing/in-progress; do not claim the
  map fully reconciled until that test-policy conflict is resolved. Defer
  final-identity claims until implementation and release artifacts freeze.
- Freeze-fenced acceptance: the prepared 623-file JVM/interpreter differential,
  sealed eight-target CPAN matrix, warmed performance, packaging/SBOM, and final
  platform CI launch only from one immutable tuple.

### PR 1093 UAT repair progress (2026-08-23)

- [x] Restored every reported PR 1091 UAT passing-count baseline without
  changing imported tests: `op/pack.t` 14699/14726 (baseline 14694),
  `op/sub_lval.t` 176/215, `op/each.t` 64/65,
  `re/stclass_threads.t` 6/6, `op/attrs.t` 159/159, and
  `op/caller.t` 96/115; the follow-up `op/attrproto.t` regression is
  restored to 52/52 and `op/tr.t` is restored to 288/318.
- [x] Fixed U0 pack segment boundaries and zero-width strings, bytes-mode
  empty-pattern split, unresolved AUTOLOAD lvalue assignment, inherited regex
  debug state for child threads, nested warning-bit restoration, localized
  `$^W` numeric warnings, caller-authoritative attribute warning categories,
  dynamic `$^W` transliteration warnings, argv-safe `jperl.bat` child launches
  through the process service, and embedded-runtime detection in both
  `IPC::Cmd` and `PerlOnJava::Process` for the ProcessBuilder path.
- [x] Added additive regression coverage under `src/test/resources/unit/` and
  `src/test/java/`; the added Perl regression files pass system Perl, JVM, and
  interpreter coverage through the warning-free full `make` gate. The final
  scoped candidate passed `make` on 2026-08-23, and focused `op/tr.t` UAT
  confirmed 288/318.
- [ ] Push the repaired PR head and require successful Ubuntu and Windows CI
  checks before resuming the frozen-identity acceptance sequence below.

## Ordered Next Steps

1. Finish, independently accept, and integrate the final ten-gate assembler,
   binding the accepted CPAN execution authority, corpus `release_authority`,
   make, package, A231, A232, CI, and sync schemas. Require complete strict-
   regex JVM/interpreter result-map parity. Preserve the additive-only test
   delta: every
   base-existing test remains byte-identical and imported fixtures remain
   unchanged. Rejected evidence, stale artifacts, self-declared summaries, and
   the rejected weak lifecycle index are forbidden.
2. Treat the integrated assembler as command-ready, not as a passing final
   envelope; authoritative success still requires fresh evidence from every
   gate on one frozen identity.
3. Freeze every tooling, documentation, and bridge integration as
   `FINAL_PARENT`. Before naming that identity, audit every candidate-only
   commit for required AI attribution and repair metadata in one controlled
   history rewrite, then rerun the additive-only/protected-input audit so all
   recorded hashes refer to the final history. In a fresh private worktree
   replay accepted A228 commit
   `875cbf82297a649a7c22ceeec8e857c8c03adad9` as the last product change and
   sole `FINAL_A228` child. Verify exact parent equality, focused A228 tests,
   clean status, attribution, exclusion of the rejected lifecycle index, and
   one warning-free exact-replay `make`. No later product or protected-input
   commit may precede the performance comparison.
4. Produce fresh A231 evidence with baseline=`FINAL_PARENT` and
   candidate=`FINAL_A228`, using fresh exact-source artifacts for both. Never
   reuse rejected, pre-replay, or stale performance evidence.
5. On the resulting final source identity, execute the checked-in remote sync
   producer, build fresh relocated distributions, and collect source, runner,
   JAR, tool, notice, license, Unicode, Joni/JCodings, package, and SBOM
   identities. Freeze one immutable tuple. A manifest may be prepared before
   release lanes, but the authoritative ten-gate envelope is assembled only
   after every required lane record exists.
6. Run independent frozen-identity lanes in parallel, with no source mutation:
   - complete latest-Perl JVM comparison against the PR 1091 baseline, followed
     by the interpreter regex ledger; reuse direct/thread evidence only when
     the manifest proves every protected input is unchanged;
   - sealed affected-CPAN acceptance for all eight policy targets and every
     required backend, rejecting nonzero exit, timeout, malformed/zero TAP, or
     any unapproved warning even when the suite says `PASS`;
   - five warmed performance samples plus bounded `pat_psycho*` and `speed*`;
   - packaging, Joni notices/licenses, SBOM, generated-source provenance, and
     Ubuntu/Windows CI.
   Run no more than three expensive lanes concurrently; workers monitor load
   themselves. Timing-sensitive samples run serially.
7. Triage lane failures by semantic root, not by test file. Assign each root as
   one autonomous implementation tranche with its reducer, adjacent regression
   set, and expected delivery envelope. Cancel unaffected lanes only when a
   product change invalidates their identity. Batch compatible fixes into the
   next candidate and return to step 1; never patch imported or CPAN tests.
8. Mark the release PR ready and request user acceptance only when the final
   envelope verifies every required artifact and lane on one identity, the PR
   1091 comparator has no regression, exact-head `make` is warning-free, and
   Ubuntu, Windows, and complete CI are green. Preserve manifests and log hashes
   so expensive evidence is not repeated.
9. After the final implementation PR is merged to `master`, retire the
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
10. On final `master`, review shipped behavior against
   `pod/perlreref.pod`, `pod/perlrecharclass.pod`,
   `pod/perlrequick.pod`, `pod/perlrepository.pod`, `pod/perlre.pod`,
   `pod/perlretut.pod`, and `pod/perlrebackslash.pod`. Record every
   supported, partial, divergent, and missing capability in
   `docs/reference/feature-matrix.md` with evidence. Reconcile
   `dev/implementation/regex.md` and `docs/design/joni-callout-fork.md` in the
   same review, run final documentation/link checks, and close the tracker.

## Test and Delivery Contract

- Never modify or delete imported or base-existing project tests. Preserve them
  byte-for-byte against the accepted base. Put required coverage in additive
  test files, and prove Perl-language expectations with system Perl before
  relying on them.
- Every failure discovered in core, CPAN, platform CI, performance, warning, or
  backend-parity testing must produce a permanent tracked project regression
  test before its fix or tracker item is complete. Preserve system-Perl oracle
  and unfixed-parent evidence; a passing broad suite or `/tmp` reproducer alone
  is insufficient.
- Every semantic slice needs system-Perl, JVM, interpreter, direct-Joni where
  applicable, and complete affected-corpus zero-introduction evidence.
- Use `dev/tools/perl_test_runner.pl` with system `perl`, not `jperl`.
- Compare complete output with the PR 1091 baseline. Reject missing rows,
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
  The final gate is `make PERL=/path/to/modern/perl perl5-sync-check`: it must
  verify the remote-advertised default branch, fast-forward to its latest tip,
  replay the complete manifest twice, and reject any second-pass output change.
  Offline `sync.pl --verify-idempotent` may prove only the already-fetched
  frozen commit; it cannot establish remote latest-tip identity.
- Unicode generators must consume the latest checked-out `perl5/` tables and
  derive their version and source hashes as provenance. Historical source
  hashes or Perl/Unicode versions must not act as pins that block a valid latest
  upstream refresh; checked-in output hashes remain reproducibility gates.

## Final Acceptance

- [ ] Every semantic row passing in the PR 1091 baseline still passes.
- [ ] Complete latest-Perl JVM output is compared file-by-file with the PR 1091 baseline;
  current discovery is recorded from the exact checkout without a pinned count.
- [ ] Complete regex-bearing JVM/interpreter and direct/thread results agree.
- [x] Remaining direct/thread `pat` failures are either closed as matcher
  semantics or explicitly retained only as non-semantic performance/diagnostic
  boundaries in a machine-readable allowlist with permanent evidence; no
  shared semantic failure is left unowned.
- [ ] Every claimed Joni capability for constants, closures, conditions,
  control verbs, recursion, dynamic source, byte strings, and Unicode strings
  maps to permanent system-Perl and JVM/interpreter coverage plus direct-Joni
  coverage where the fork owns the behavior; no fallback or source scanner
  satisfies this gate.
- [x] `(*MARK:NAME)` and named control-verb state execute in Joni.
- [x] Bounded `pat_psycho*` and `speed*` semantic/performance closure.
- [ ] No supported regex test needs `JPERL_UNIMPLEMENTED=warn`.
- [x] Joni is the sole production matcher; selector compatibility code is
  test-scope-only and absent from production packaging.
- [x] Matcher-semantic `RegexPreprocessor` and production Java matcher are gone.
- [x] Obsolete regex import patches are gone.
- [x] Temporary source-policy compilation fallback is gone; executable-source
  admission and trusted callout materialization remain purpose-specific policy.
- [ ] The final latest-upstream sync is reproducible and idempotent on the
  frozen Perl commit, including generated Unicode inputs.
- [ ] Feature matrix and architecture/fork documents match the final
  implementation and the seven-POD capability audit.
- [ ] The frozen packaged Joni/JCodings sources preserve every original
  copyright, authorship, and license notice; the notice and SBOM audit passes.
- [ ] Warmed performance, CPAN, packaging, warning-free build, Ubuntu, Windows,
  and CI pass.
- [x] DateTime far-future `from_epoch` emits Perl-compatible warning text, not
  an array-reference string, in permanent focused coverage.
- [x] DateTime emits no false unescaped-brace warning for `/x` comment text.
- [x] Moo direct-stash CODE method classification matches Perl on both backends.
- [x] Moo's `_subname` selection matches Perl on both backends.
- [x] Moo's unexpected redefinition diagnostics are absent under its warning
  policy in permanent focused coverage on both backends.
- [ ] The sealed final-identity affected-CPAN manifest reports `pass` for every
  required mode of DBIx::Class, DateTime, Moo, Regexp::Common, String::Random,
  Template, Type::Tiny, and WWW::Mechanize, with artifact hashes and no
  unapproved warning shapes.
- [x] Test::Builder descriptions do not emit numeric-conversion warnings when
  lexical suppression applies under package-global warnings.
- [ ] Post-merge warn-mode removal and POD capability review are complete.

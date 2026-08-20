# Full Perl Regex Semantic Parity

## Goal

Implement the current imported upstream Perl regular-expression semantics on
both PerlOnJava execution backends and make the vendored Joni fork the sole
production matcher. Java
`Pattern` matcher routes and production backend selection are temporary
differential tools and must be removed before completion. A disconnected
selector-policy parser may remain solely to compile existing compatibility
tests until those tests receive explicit retirement authority; it must not
control production matching.

The contract includes matching, captures, match state, callbacks, dynamic
patterns, diagnostics, warnings, source locations, byte/Unicode behavior,
direct/thread parity, unchanged Perl core tests, and unchanged CPAN consumers.
Optimizer programs and debug transcripts are reported separately from language
semantics.

The immutable no-regression comparison point is:

```text
../PerlOnJava/logs/test_20260815_080000_958.log
```

## Final Architecture

- The forked Joni engine is implemented and maintained in this repository.
- Joni owns regex grammar and matcher semantics: captures, conditions,
  recursion, lookarounds, case folding, control verbs, backtracking-visible
  state, byte/Unicode matching, and property membership.
- PerlOnJava owns source policy: interpolation provenance, lexical hints and
  warnings, `use re 'eval'`, executable Perl closures, user-defined properties,
  source locations, and final Perl diagnostic rendering.
- The fork remains runtime-neutral. Callouts use internal IDs and a matcher-local
  handler API; Joni never receives Perl source or PerlOnJava runtime objects.
- Upstream Joni/JCodings package names and all copyright/authorship notices stay
  intact under `third_party/`. Standalone packaging relocates them to
  `org.perlonjava.internal`, avoiding public namespace collisions without
  rewriting the maintained source namespace.
- Joni is the default for every closure-bearing pattern throughout migration.
  No callback, condition, control verb, recursive program, or dynamic source may
  fall back to Java. Ordinary constants move to Joni as their native gates pass.
- Final production code has one matcher. Ordinary patterns allocate no callout
  state, and a match operation never runs two engines because regex side effects
  are observable.
- Imported Perl sources and generated Unicode inputs always follow the latest
  upstream revision in `perl5/`. The project does not pin a Perl commit SHA or
  fixed release; refresh gates derive provenance and checksums from the current
  checkout and require a byte-identical second generation/sync pass.

## Preprocessing Boundary

Classify every current regex preprocessing rule as one of:

1. Perl source policy retained in a small frontend scanner.
2. Backend-neutral spelling normalization retained outside the matcher.
3. Matcher semantics moved into Joni parser/compiler/matcher internals.
4. Java-only translation or stack workaround deleted with the Java backend.

Text rewriting must not emulate behavior dependent on capture close order,
backtracking, matcher regions, encoding, or callback execution. As native fixes
land, remove corresponding `RegexPreprocessor` code immediately and rerun the
affected corpus before taking another slice.

## Current Validated Position

- The reproducible differential baseline and conditions/control-verb phase are
  complete.
- `(*MARK:NAME)`, named `(*SKIP:NAME)`, `$REGMARK`, `$REGERROR`, callback unwind,
  and native `(*THEN)` branch boundaries are in the Joni path.
- Absolute, forward, backward, whole-pattern, and signed-relative numbered
  subpattern calls parse natively in Joni; the adapter rewrite is gone.
- Recursive callouts observe captures from the just-completed recursive frame.
- Folded exact-search candidate bounds are safe for long ASCII-strict literals,
  and native numbered-call diagnostics retain the Perl source location.
- The last complete forced-Joni `pat_advanced.t` gate executes 1,687/1,687 and
  passes 1,646 on JVM and interpreter, with identical 41-row residuals. The
  integrated signed-IV range fix removes row 1651 with zero introductions in
  exact A/B evidence; a fresh combined serial gate remains required because a
  later run stopped before the complete plan under concurrent CPAN load.
- The current imported `reg_mesg.t` passes 1,834/2,613 on each backend with an
  identical status/test-number vector. Native extended-class diagnostics now
  distinguish binary operators that have no preceding operand, adjacent
  operands without an operator, and misplaced parentheses. Overlong-lookbehind
  errors retain Perl's unmarked `m/pattern/` rendering.
- Forced-Joni Unicode property comparison is 83,648/83,648 on both backends.
  The Hyphen/IsHyphen warning correction removes the final exact 32 residuals
  with zero introductions while preserving dynamic property interpolation.
- Generated Unicode data covers all current property families plus compact Perl
  default simple/full/reverse case-fold metadata. Its transactional
  current-checkout refresh records the selected checkout's semantic version,
  full revision, exact source hashes, and generated-output hashes; strict check
  mode rejects stale provenance without treating that revision as a permanent
  target. General_Category compatibility aliases, native named-call/parser
  safety, the runtime-neutral Joni property-value matcher, signed-IV user-
  property ranges, POSIX compatibility, and generated fold data are integrated.
  The analyser fold-safety slice, final binary aliases, generated named-sequence
  lookup, property-wildcard execution, Hyphen diagnostics, native extended
  classes, property/class fold closure, and nested property-wildcard lexing are
  integrated in local staging.
- Raw `\N{name}` source now survives frontend and matcher compilation without
  the temporary `=POJSEQ=` transport. Generated and lexical multi-code-point
  names and ordinary scalar names resolve through Joni; the temporary
  generated-sequence-only routing distinction is deleted.
- `master` contains the validated named-character diagnostics plus native
  `(?(DEFINE)...)`, ordinary lookbehind, branch reset, and plain `\N`; its exact
  head passed warning-free local, Ubuntu, and Windows gates.
- Byte/Unicode provenance and fold policy, dotted-U+ diagnostics, the ordinary-
  pattern Joni default, a dynamic-pattern edge contract, and the first obsolete
  import retirement are integrated. Fold, property, resolver-cache, and the
  classified runtime-diagnostic residuals are closed.
- Native DEFINE, ordinary lookbehind, and branch reset now route through Joni;
  their feature-specific Java rewrites and branch-reset capture-map adapter are
  deleted. Plain Perl `\N` is a native Joni non-line-feed atom, including
  intervals, `/s` independence, and Perl's character-class diagnostic.
- Unknown, empty, malformed/dotted U+, and missing-brace named-character groups
  have native frontend/runtime diagnostics.
- Forced-Joni `pat_re_eval.t` now executes and passes 555/555 on both JVM and
  interpreter. Perl's release-build `-D` diagnostic is preserved without
  enabling PerlOnJava's unrelated internal compiler trace.
- Each `pat.t` variant executes 1,301/1,302 and passes 1,240 on JVM and
  interpreter. Executable callbacks introduced by overloaded scalar and array
  interpolation retain containing lexicals under large-method fallback, and
  nonstrict scalar dereference preserves first-class REGEXP source.
- Exact `re/regexp.t` executes 2,210/2,210 with 2,200 passing on the current
  acceptance artifact. Recursive capture publication reaches 2,208/2,210 on
  both execution backends at its focused barrier, leaving only the separately
  owned named-diagnostic and variable-lookbehind rows; the combined integrated
  artifact still requires an exact rerun. Non-nullable quantified captures now
  preserve the preceding
  iteration for backreferences, clear captures untouched by the successful
  final iteration, and restore state on backtracking. Lexical warning masks
  remain authoritative when undef captures are interpolated by eval STRING.
- Dynamic undef-result warnings retain the regex source's lexical policy across
  eval STRING on both execution backends. Dynamic continuations initialize
  their nested Joni option scope, preserving strict `/aa` folding.
- A condition inside an open repeated capture sees the preceding completed
  iteration, while ordinary backreference behavior and final clearing remain
  unchanged.
- Optional and conditional self-backreferences inside a repeated capture use
  the preceding completed iteration. Ordinary alternation invalidates that
  visibility, preserving all fourteen upstream false-positive guards.
- Native compile diagnostics cover relative/group-zero references, malformed
  conditions, incomplete POSIX-looking classes, reversed quantifier bounds,
  and apostrophe-delimited patterns. Eval STRING preserves disabled fatal
  warning categories; matcher warnings retain their Perl `regexp`, `syntax`,
  or `misc` category. Inactive branch-reset slots no longer become fatal under
  a caller's disabled `uninitialized` scope.
- The mechanically derived acceptance ledger covers all 80 `re/` files, every
  regex-bearing `op/` and `uni/` test, documented unit gates, and direct/thread
  pairs without pinning a Perl revision. Its current 286-file combined run is
  the release comparison boundary. An earlier 80-file artifact improved 42
  identities against PR 958 but contained 11 lower counts and 17 invalid
  execution records, so it is diagnostic evidence rather than acceptance
  evidence.
- Exact `/aa` routing/folding gates pass on native Joni, and the Java `/aa`
  workaround is removed.
- Java matcher storage, Java regex frontend compilation, matcher-semantic
  preprocessing, and the legacy extended-class adapter are deleted. The legacy
  `JPERL_REGEX_BACKEND=java` spelling cannot re-enable Java production matching.
  Its disconnected compatibility parser remains only because existing routing
  tests compile against that API; removing both is the final selector-cleanup
  item and requires explicit authority to retire those tests.
- Perl grouped nested-quantifier semantics and extended-mode quantifier
  modifiers are native Joni behavior; the exact `regexp.t` differential removes
  seven failures with no introductions.
- Named `(*ACCEPT:NAME)`, `(*FAIL:NAME)`, and `(*F:NAME)` carry control state
  through native Joni bytecode and publish Perl-compatible `$REGMARK` and
  `$REGERROR`; the exact `regexp.t` differential removes three failures with no
  introductions.
- Negative lookbehind accepts capture enclosures and uses ACCEPT-aware width
  analysis in native Joni; named cut errors remain authoritative before an
  unnamed FAIL. The combined exact `regexp.t` differential has no introductions.
- Perl `/xx` character-class whitespace and nested inline `x`/`xx` mode changes
  are native Joni lexer/parser behavior. The corresponding `regexp.t` identities
  pass without introductions on both execution backends.
- Reverse full-fold alternatives can repartition across adjacent source
  literals without changing single-literal lookbehind width. The targeted
  `regexp.t` identity and the existing literal/backreference fold contract pass
  on default and forced Joni for JVM and interpreter.
- Perl's exact `L_` General_Category compatibility spelling resolves as `LC`
  before loose alias normalization, so uncased letters no longer enter that
  class. The focused system-Perl oracle, four runtime legs, and imported
  `regexp.t` identity agree.
- Native Joni diagnostics distinguish invalid non-braced `\p`/`\P` followers,
  unterminated inline-option and comment groups, incomplete `(?` group effects,
  and empty control verbs. The focused system-Perl/direct-Joni/four-leg gates
  remove the corresponding `regexp.t` identities with no introductions.
- Runtime `(??{...})` sources execute through native Joni continuations; the
  dynamic Java fallback adapter is gone. Callback aggregate mutations unwind
  when the complete match fails, remain visible when another alternative
  succeeds, and commit when destructive control verbs cut the path, including
  across a dynamic continuation.
- Native Joni parses bounded decimal, braced, relative, and overflow numeric
  backreferences without Java-side rewriting. The focused four-backend fixture
  passes 63/63, and the exact `regexp.t` numeric tranche has zero introductions.
- Native Joni distinguishes non-ASCII unfinished ranges and accepts Perl false
  class ranges around `\d`, `\s`, and POSIX classes. The focused system-Perl,
  direct-Joni, four-backend, and imported-row gates agree.
- Physical branch-reset names bind named calls and named conditions to the
  correct definition without changing numeric condition operands. Native
  whole-pattern recursion, numeric-backreference, class-range, named-character,
  and lexer slices pass the combined full build; exact `regexp.t` is
  2,101/2,210 with zero newly failing identity against the prior boundary.
- Runtime-source scanning preserves `/aa`, ignores callback-looking syntax in
  comments and character classes, and agrees across default/Joni and JVM/
  interpreter focused vectors. Unterminated-source diagnostics and the wider
  dynamic code-array matrix remain open.
- The fail-closed PR-958 comparator rejects missing files, lost passes,
  timeout/error/unknown execution, zero TAP, truncated/incomplete TAP, and
  malformed input, while emitting JSON and retaining exact baseline-artifact
  provenance. Direct and `_thr` files remain separate identities.
- Imported `perl5/t/re` and `perl5_t/t/re` files are byte-identical. There is no
  configured regex-test patch; only a stale `pat.t.orig` artifact and the
  regex-adjacent `_charnames.pm` library patch remain for the final sync and
  Unicode-name retirement barriers.

## Execution Phases

### Phase 0 — Reproducible differential baseline

- Run the same 80 `perl5_t/t/re` files on JVM/interpreter and forced Java/Joni.
- Save complete logs and machine-readable results outside the tree.
- Reject zero-TAP, timeout, truncated, or incomplete records before comparing.
- Classify failures as matcher semantics, source policy, diagnostics, shared
  runtime behavior, or optimizer/debug-only output.

Exit: the baseline is repeatable and every next slice has exact rows and an
oracle.

### Phase 1 — Ordinary-pattern Joni parity

- Complete byte/Unicode variant selection from pattern and subject provenance.
- Close captures, duplicate names, branch reset, regions, bounds, zero-width
  search progression, `\G`, `/g`, `/c`, `/o`, substitution, reuse, and nested
  match-state restoration.
- Route each ordinary-pattern family to Joni only after focused and complete
  differential gates show zero introductions.

Exit: every semantic assertion passing on Java passes on forced Joni, with JVM
and interpreter agreement.

### Phase 2 — Conditions and backtracking-visible state

- Numbered/named/assertion/recursion/callback conditions and `(DEFINE)`.
- `(*MARK:NAME)`, named `(*SKIP:NAME)`, `(*PRUNE)`, `(*COMMIT)`, `(*THEN)`,
  `$REGMARK`, `$REGERROR`, cut boundaries, recursion limits, and unwind.
- Capture-close order, provisional match variables, dynamic locals, and callback
  side effects on the selected path.

Exit: focused Perl oracles and relevant `pat_advanced.t`, `rxcode.t`, and
`reg_eval_scope.t` sections agree.

### Phase 3 — Unicode and native pattern syntax

- Add a development generator that reads and analyzes the current latest
  upstream `perl5/` checkout's Unicode tables, then emits checked-in Java source
  for Joni property
  names, loose aliases, value families, ranges, and case-fold metadata. The same
  script emits resolver oracle fixtures plus input/output checksums. Generated
  Java is reproducible and its second consecutive generation must be diff-free;
  hand-written Java is limited to reviewed precedence and behavior that cannot
  be derived from the source tables.
- Finish Block, Script, Script_Extensions, General_Category, binary,
  compatibility, wildcard, versioned `Age`, POSIX, `\h`/`\H`, and user-property
  behavior from the current latest upstream Perl Unicode data.
- Preserve property-family and user-callback precedence, signed-IV-wide scalar
  domains, and byte/Unicode warnings; do not duplicate ICU behavior when the
  current upstream data or ICU/JCodings already provides it.
- Complete `\N{name}` single/multi/empty atoms, classes, lexical translators,
  caching, and exact extended-class diagnostics.
- Complete `/d`, `/u`, `/a`, `/aa`, literal/backreference/class/property case
  folding and optimizer search safety.
- Complete Perl escapes, subpattern calls, lookbehind width/255-character rules,
  branch-reset lexical targets, `\K`, recursion safety, and invalid-pattern
  diagnostics natively in Joni.

Exit: semantic `regexp_unicode_prop.t`, `pat.t`, and `pat_advanced.t` assertions
complete without masking supported syntax through `JPERL_UNIMPLEMENTED=warn`.

### Phase 4 — Runtime source and diagnostics

- Preserve package, lexical context, warning masks/categories, filename, line,
  syntax position, and byte/Unicode identity through literal, runtime, and eval
  compilation.
- Complete recursive/nested `(??{...})`, mixed literal/runtime executable
  source, tied/localized interpolation, object stringification, and `/g`/`/c`/
  `/o` behavior across callbacks and exceptions.
- Finish native warning collection plus exact Perl wording, markers,
  fatal-versus-warning behavior, and source suffixes.
- Close all 555 `pat_re_eval.t` assertions on both execution backends; classify
  shared non-regex eval defects separately but fix any that block regex source.

Exit: runtime-generated regexes and diagnostics agree with standard Perl.

### Phase 5 — Remove migration scaffolding

- Delete every Java matcher field, route, syntax rewrite, fallback, and the
  backend selector.
- Delete matcher-semantic preprocessing after each replacement gate is green.
- Keep only the documented Perl source-policy/frontend layer.
- Remove regex patches introduced by `dev/import-perl5/sync.pl`, then restore
  exact upstream files with targeted sync.

Exit: Joni is the only production matcher and imported regex tests are
unchanged upstream files.

### Phase 6 — Release and documentation

- Run all direct and `_thr.t` regex files on JVM/interpreter and compare the
  complete `dev/tools/perl_test_runner.pl` output file-by-file with PR 958.
- Run unchanged Type::Tiny, Regexp::Common, Object::InsideOut, and every CPAN
  suite affected by removed regex capability policy.
- Run performance, packaging, notices/licenses, warning-free build, Ubuntu,
  Windows, and full CI gates.
- Update `docs/reference/feature-matrix.md`, including the expected-Joni feature
  set around lines 390–409 and every currently missing Perl regex feature.
- Rewrite `dev/implementation/regex.md` as the clear as-implemented architecture.
- Update `docs/design/joni-callout-fork.md` for the shipped fork API, packaging,
  namespace, callback/unwind contract, and Unicode ownership.
- Delete wholly redundant documents; reduce rationale-bearing older documents
  to concise summaries pointing to the canonical implementation documents.

Exit: all semantic and release gates pass and documentation matches shipped
behavior.

## Ordered Next Steps

1. Finish the active non-overlapping Joni slices: valid `/d` byte-backed class
   fold policy; generated Perl scalar character names and aliases; native
   search/optimizer work that makes unchanged `speed*` complete without
   regressing `pat_psycho*`; and native lexer/parser diagnostic families from
   unchanged `reg_mesg.t`. Do not implement adjacent-class or variable-width
   full-fold-backreference rows that standard Perl itself rejects.
2. Integrate those commits on one immutable barrier. Run exact `regexp.t`,
   `reg_fold.t`, `reg_email*`, `regex_sets.t`, Unicode-property, callback,
   recursion, `script_run.t`, `pat.t`, `pat_advanced.t`, `pat_re_eval.t`, and
   `reg_mesg.t` gates. Classify every residual by a general native-Joni root;
   reject zero-TAP, timeout, truncated, incomplete, backend, or direct/thread
   mismatches. Prioritize variable lookbehind and any remaining runtime-source
   or extended-class residuals shown by that artifact.
3. Delete the remaining production Java selector/fallback state, duplicate
   matcher-semantic preprocessing, and unreachable adapters. Retain only
   documented Perl source policy that cannot live in runtime-neutral Joni, plus
   any disconnected compatibility parser required by immutable existing tests.
   Prove no production Java matcher construction, no environment setting that
   changes the matcher, and no supported test dependency on
   `JPERL_UNIMPLEMENTED=warn`.
4. Complete the mechanically derived 286-file acceptance run on JVM and
   interpreter, compare every file/test identity with PR 958, and separately
   record current-upstream plan-size changes and system-Perl oracle counts. Run
   bounded `pat_psycho*` and `speed*` lanes without starving implementation.
5. Pass warmed performance, CPAN smoke, warning-free `make`, packaging,
   license/notice, Ubuntu, Windows, and CI gates. Reconcile the feature matrix,
   as-implemented regex architecture, and Joni fork documents with shipped
   source; remove or summarize redundant design documents; rebase on current
   `master`; and open the final reviewable PR.
6. After the final implementation PR is integrated into `master`, review the
   shipped PerlOnJava behavior against Perl's documented regex surface in
   `pod/perlreref.pod`, `pod/perlrecharclass.pod`, `pod/perlrequick.pod`,
   `pod/perlrepository.pod`, `pod/perlre.pod`, `pod/perlretut.pod`, and
   `pod/perlrebackslash.pod`. Record every supported, partial, divergent, and
   missing capability in `docs/reference/feature-matrix.md`; add focused
   follow-up items for gaps rather than inferring support from parser syntax.

## Parallel Work

- Coordinator/integration: canonical stack, PR 958 comparison, PR/CI readiness,
  plan state, ownership, and conflict resolution.
- Native syntax/matcher: one non-overlapping Joni grammar or matcher feature per
  branch with direct fork tests and Perl reducers.
- Unicode: one classified property family per branch from the exact residual
  artifact.
- Differential/release: immutable row sets, normalized comparators, fallback
  impact ranking, import sync, CPAN and platform gates.
- Documentation: feature inventory and final architecture documents after the
  corresponding behavior is validated.

Workers use isolated worktrees and append-only handoff mailboxes. Assignments
state exact base, owned/excluded files, oracle, complete gates, correction
budget, and delivery evidence. Workers self-monitor CPU and may admit at most
three concurrent expensive jobs globally; timing-sensitive final gates are
serial. Workers normally stop after focused and complete affected-corpus gates
and deliver local commits without pushing. The coordinator batches two to four
non-overlapping deliveries, runs one warning-free full `make` on the combined
head, and only then pushes or updates the PR. A worker-local full build is
reserved for build-system changes or focused evidence of broad cross-suite risk.
`pat_psycho*` and `speed*` may use two CPU-heavy workers, while `pat*`,
`pat_advanced*`, and memory-sensitive fixtures remain one-worker exclusive.
Every `jperl`, `jcpan`, and `prove` process has a hard timeout.

## Test and Delivery Contract

- Never modify or delete existing tests. Validate every NEW Perl fixture with
  system Perl before PerlOnJava.
- Capture complete output in files. Use `perl dev/tools/perl_test_runner.pl`,
  never `jperl`, to drive the fork-based core runner.
- Compare JVM and interpreter results, direct and thread wrappers, and forced
  backends where the temporary selector still exists.
- A semantic slice needs its focused oracle, direct Joni tests where applicable,
  and complete affected corpus with zero introductions. Each combined
  integration batch needs one warning-free `make` before push or PR update.
- Preserve original Joni/JCodings notices and verify relocated packaging.
- Never push master. Use focused branches, attributed commits, PRs, and current
  master rebases after upstream merges.

## Performance Gate

Before deleting Java matching, run five warmed ordinary-pattern measurements on
each backend. Joni median runtime must be within 25% of the Java baseline, add
no timeout, and not materially increase steady-state allocation. Performance
failure blocks backend removal, not semantic fixes.

## Execution Tracker

- [x] Phase 0 — reproducible differential baseline
- [ ] Phase 1 — ordinary-pattern Joni parity
- [ ] Phase 2 — conditions and backtracking-visible state
- [ ] Phase 3 — Unicode and native pattern syntax
- [ ] Phase 4 — runtime source and diagnostics
- [ ] Phase 5 — remove migration scaffolding
- [ ] Phase 6 — release and documentation

A checked phase means its focused semantic implementation is complete. Release
gates may reopen it if a semantic regression appears.

### Active phase detail

- [x] Transactional latest-upstream Perl Unicode generator refresh and exact
      provenance/freshness gates
- [x] General Category, Script, Block, POSIX, binary-membership, and signed-wide
      property ranges
- [x] Runtime-neutral Joni property-value matcher
- [x] Replace every Java property-wildcard execution site with the Joni matcher
- [x] Parse nested property-value regex syntax in Joni and remove adapter
      materialization of the selected ranges
- [x] Complete Hyphen warning/category/source-position diagnostics
- [x] Latest-upstream Perl simple/full/reverse case-fold data
- [x] Native fold adapter and unsafe optimizer-boundary suppression
- [x] Property/class fold closure
- [x] Fold-mode and byte/Unicode provenance context
- [x] Variable-width simple-fold backreference comparison
- [ ] Forward/reverse literal and character-class fold expansion
- [x] Prevent the legacy backend selector from re-enabling Java matching
- [x] Remove Java matcher storage and empty-pattern/substitution retry branches
- [x] Remove Java matcher-semantic preprocessing and the legacy extended-class
      adapter
- [x] Generated Perl named-sequence lookup and native sequence resolution
- [x] Generated Perl scalar character-name and alias lookup
- [x] Perl `/i` folding for canonical Unicode casing-property aliases
- [x] Remove temporary named-sequence encoding from native Joni pattern source
- [x] Restore canonical multi-code-point named-sequence extended-class diagnostics
- [x] Restore Perl diagnostics for unknown/empty/malformed named sequences
- [x] Native `(?[...])` with zero diagnostic regressions
- [x] Native `(?(DEFINE)...)` and removal of its adapter rewrite
- [x] Native ordinary lookbehind and removal of its Java translation
- [x] Native branch reset and removal of its capture-map adapter
- [x] Native plain `\N` non-newline atom and interval forms
- [x] Native recursive/runtime `(??{...})` and removal of dynamic adapters
- [x] Native bounded numeric backreference parsing and removal of Joni-side
      brace-backreference rewriting
- [x] Native Perl false-class ranges and unfinished non-ASCII range diagnostics
- [x] Final-iteration, optional, alternation, DEFINE, recursive, and failed-path
      capture clearing and publication
- [x] Non-nullable quantified-capture iteration scopes and backtracking restore
- [x] Previous-iteration visibility for conditions inside repeated captures
- [x] Previous-iteration visibility for non-alternating self-backreferences
- [x] Physical branch-reset named calls and conditions
- [x] Inactive branch-reset slot publication
- [x] Native named-character whitespace and missing-brace diagnostics
- [x] Remove the duplicate Java named-character translation path
- [x] Exact named-character diagnostic-version policy
- [x] Native whole-pattern `(?R)` parsing and execution
- [x] Recursive call capture publication and recursion safety
- [x] Runtime-source comment/class masking and `/aa` propagation
- [x] Unterminated runtime-source diagnostics for regexp rows 575/576/581
- [x] Dynamic undef-warning scope and nested-continuation `/aa` state
- [x] Native compile diagnostics and exact warning-category inheritance
- [ ] Remaining native lexer/parser diagnostic families in `reg_mesg.t`
- [x] Dynamic overloaded scalar/array code-source interpolation and lexical
      capture under large-method interpreter fallback
- [x] Fail-closed PR-958 comparison with machine-readable evidence
- [x] Mechanically derived current-checkout regex acceptance ledger
- [x] Retire proven-obsolete `dev/import-perl5` regex patches
- [x] Latest-upstream import sync preserves required non-regex runtime patches
      and is transactional and idempotent
- [x] Native `(*script_run:...)`, `(*sr:...)`, `(*atomic_script_run:...)`, and
      `(*asr:...)`
- [ ] Refresh the complete Unicode, `pat.t`, `pat_advanced.t`, `reg_mesg.t`, and
      mechanically derived 286-file gates on one integrated artifact

## Final Acceptance

- [ ] Every semantic regex test passing in PR 958 still passes.
- [ ] Complete runner output is compared file-by-file with the PR 958 log.
- [ ] JVM/interpreter and direct/thread results agree.
- [ ] Forced Joni covers constants, closures, conditions, control verbs,
      recursion, dynamic source, byte strings, and Unicode strings.
- [ ] `pat_psycho*` and `speed*` pass under bounded parallelism.
- [ ] No supported regex test needs `JPERL_UNIMPLEMENTED=warn`.
- [ ] Joni is the sole production matcher; Java matcher routing is gone and any
      retained selector-policy parser is disconnected from production.
- [ ] Matcher-semantic preprocessing is gone.
- [ ] Obsolete import patches are removed and targeted sync is idempotent.
- [ ] Feature matrix and final architecture documents match implementation.
- [ ] Original copyright/authorship notices are preserved.
- [ ] Performance, warning-free `make`, packaging, license, Ubuntu, Windows,
      and CI gates pass.
- [ ] After final integration to `master`, Perl's documented regex capabilities
      in `pod/perlreref.pod`, `pod/perlrecharclass.pod`, `pod/perlrequick.pod`,
      `pod/perlrepository.pod`, `pod/perlre.pod`, `pod/perlretut.pod`, and
      `pod/perlrebackslash.pod` are reviewed against the shipped implementation;
      `docs/reference/feature-matrix.md` records all supported, partial,
      divergent, and missing features with evidence.

# Full Perl Regex Semantic Parity

## Goal

Implement Perl 5.44 regular-expression semantics on both PerlOnJava execution
backends and make the vendored Joni fork the sole production matcher. Java
`Pattern` and the backend selector are temporary differential tools and must be
removed before completion.

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
- The current imported `reg_mesg.t` passes 1,710/2,603 on each backend with an
  identical status/test-number vector.
- Forced-Joni Unicode property comparison is 83,648/83,648 on both backends.
  The Hyphen/IsHyphen warning correction removes the final exact 32 residuals
  with zero introductions while preserving dynamic property interpolation.
- The shared deterministic pinned-Perl Unicode generator covers all current
  property families plus compact Perl default simple/full/reverse case-fold
  metadata. General_Category compatibility aliases, native named-call/parser
  safety, the runtime-neutral Joni property-value matcher, signed-IV user-
  property ranges, POSIX compatibility, and generated fold data are integrated.
  The analyser fold-safety slice, final binary aliases, generated named-sequence
  lookup, property-wildcard execution, and Hyphen diagnostics are integrated in
  local staging. Native extended classes, property/class fold closure, and
  nested property-wildcard lexing are active independent slices.
- Draft PR 1078 is durable at `29a6de3fd`. Its integration source through
  `29e4a1b7e` passes warning-free `make`, all 17 tasks, Joni tests, five unit
  shards, packaging, and generated-data checks; the final isolated named-
  diagnostic commit passes its focused all-17-task make.
- Each `pat.t` variant executes 1,301/1,302 and passes 1,223.
- The current 80-file forced-Joni gate passes 363,164/391,977 and has 19
  per-file pass-count regressions against PR 958. These figures must be refreshed
  after the current native stack is integrated.
- Exact `/aa` routing/folding gates pass on native Joni, and the Java `/aa`
  workaround is removed.

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

- Add a development generator that reads and analyzes the repository's pinned
  Perl 5.44 Unicode tables, then emits checked-in Java source for Joni property
  names, loose aliases, value families, ranges, and case-fold metadata. The same
  script emits resolver oracle fixtures plus input/output checksums. Generated
  Java is reproducible and its second consecutive generation must be diff-free;
  hand-written Java is limited to reviewed precedence and behavior that cannot
  be derived from the source tables.
- Finish Block, Script, Script_Extensions, General_Category, binary,
  compatibility, wildcard, versioned `Age`, POSIX, `\h`/`\H`, and user-property
  behavior from pinned Perl 5.44 Unicode data.
- Preserve property-family and user-callback precedence, signed-IV-wide scalar
  domains, and byte/Unicode warnings; do not duplicate ICU behavior when the
  pinned data or ICU/JCodings already provides it.
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

1. Keep the canonical native-Joni PR and this plan branch durable. Require exact
   commit/file review, warning-free `make`, and green stacked CI before moving a
   PR from draft to user acceptance.
2. Finish the exact remaining binary aliases and 32 Hyphen diagnostic Unicode
   rows. Then wire every property-value wildcard family to the integrated vendored-
   Joni evaluator in one conflict-free commit and remove all temporary
   `java.util.regex.Pattern` wildcard execution, including Age/Block/Script/
   Numeric helpers. Generate the complete named-sequence lookup from Perl's
   pinned `NamedSequences.txt` in parallel and route standard `\N{name}` through
   it without reimplementing the table by hand.
3. Integrate the generated fold table through bounded native slices: package-
   local adapter and analyser optimizer safety; property/class closure; explicit
   fold/provenance context; literal forward/reverse expansion; backreferences;
   and final optimizer proof. Keep `/d`, `/u`, `/a`, `/aa`, locale, Turkic, and
   byte/Unicode provenance policy explicit and hand-reviewed.
4. Finish native Joni `(?[...])` grammar/AST/evaluation and delete the textual
   lowering. Require operand-local `/i`, scoped `^`/`a`/`aa`/`d`/`u` modifier
   isolation, wide-domain algebra, literal/comment scanning, exact-three-digit
   octal handling, nested-POSIX boundaries, empty/multi-code-point `\N{}`
   legality, nesting, and exact diagnostics with zero `reg_mesg.t`
   introductions. Then replace the `(?(DEFINE)...)` adapter rewrite with a
   native non-executing definition container.
5. Refresh `reg_mesg.t`, `pat.t`, `pat_advanced.t`, and the 83,648-record Unicode
   corpus on each combined batch. Close the largest semantically uniform
   native-Joni groups with a system-Perl-first reducer and zero-introduction
   complete gate for each; compare stable test identities when diagnostics
   contain backend-specific source-location or binary rendering.
6. Refresh all four 80-file legs on one combined artifact. Resolve all 19
   per-file PR 958 regressions; do not offer a long user acceptance run while
   any negative, zero-TAP, timeout, or incomplete file is unexplained.
7. Use the integration report to retire ordinary Java fallbacks in impact order:
   lookbehind, branch reset, alphabetic assertions, then remaining constant
   patterns. Delete each route and its semantic preprocessor rule in the same
   validated slice.
8. Complete runtime source/eval semantics and diagnostics, then close
   `pat_re_eval.t`.
9. Remove obsolete regex import patches. Run
   `perl dev/import-perl5/sync.pl --only perl5/t` twice; verify the configured
   upstream `re/pat.t` hash and require the second sync to be content-idempotent.
10. Remove Java matching and the selector, rerun the complete semantic and CPAN
   matrix, then execute performance and release gates.
11. Finish feature-matrix and as-implemented documentation, consolidate
    redundant plans, rebase the final stack onto current master, and require
    green Ubuntu/Windows CI before merge.

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
- [x] Phase 2 — conditions and backtracking-visible state
- [ ] Phase 3 — Unicode and native pattern syntax
- [ ] Phase 4 — runtime source and diagnostics
- [ ] Phase 5 — remove migration scaffolding
- [ ] Phase 6 — release and documentation

A checked phase means its focused semantic implementation is complete. Release
gates may reopen it if a semantic regression appears.

### Active phase detail

- [x] Pinned Perl Unicode property-data generators and freshness gates
- [x] General Category, Script, Block, POSIX, binary-membership, and signed-wide
      property ranges
- [x] Runtime-neutral Joni property-value matcher
- [x] Replace every Java property-wildcard execution site with the Joni matcher
- [x] Parse nested property-value regex syntax in Joni and remove adapter
      materialization of the selected ranges
- [x] Complete Hyphen warning/category/source-position diagnostics
- [x] Pinned Perl simple/full/reverse case-fold data
- [x] Native fold adapter and unsafe optimizer-boundary suppression
- [ ] Property/class fold closure
- [ ] Fold-mode and byte/Unicode provenance context
- [ ] Forward/reverse literal expansion and backreference folding
- [x] Generated Perl named-sequence lookup and native sequence resolution
- [ ] Remove temporary named-sequence encoding from native Joni pattern source
- [x] Restore canonical multi-code-point named-sequence extended-class diagnostics
- [ ] Restore remaining Perl diagnostics for unknown named sequences
- [x] Native `(?[...])` with zero diagnostic regressions
- [ ] Native `(?(DEFINE)...)` and removal of its adapter rewrite
- [ ] Refresh the complete Unicode, `pat.t`, `pat_advanced.t`, `reg_mesg.t`, and
      80-file forced-Joni gates on one integrated artifact

## Final Acceptance

- [ ] Every semantic regex test passing in PR 958 still passes.
- [ ] Complete runner output is compared file-by-file with the PR 958 log.
- [ ] JVM/interpreter and direct/thread results agree.
- [ ] Forced Joni covers constants, closures, conditions, control verbs,
      recursion, dynamic source, byte strings, and Unicode strings.
- [ ] `pat_psycho*` and `speed*` pass under bounded parallelism.
- [ ] No supported regex test needs `JPERL_UNIMPLEMENTED=warn`.
- [ ] Joni is the sole production matcher; Java routing/selector code is gone.
- [ ] Matcher-semantic preprocessing is gone.
- [ ] Obsolete import patches are removed and targeted sync is idempotent.
- [ ] Feature matrix and final architecture documents match implementation.
- [ ] Original copyright/authorship notices are preserved.
- [ ] Performance, warning-free `make`, packaging, license, Ubuntu, Windows,
      and CI gates pass.

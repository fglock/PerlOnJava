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
- The current imported `reg_mesg.t` passes 1,794/2,613 on each backend with an
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
- Each `pat.t` variant executes 1,301/1,302; JVM passes 1,225 and interpreter
  passes 1,234. The remaining JVM-only dynamic code-array rows are active work.
- The four Java/Joni × JVM/interpreter legs now have one 80-file comparison
  ledger on the pre-successor artifact. It identifies stable extended-class,
  regexp, charset, fold-grind, and bounded-speed negative clusters plus several
  zero-TAP/execution records. The complete matrix must be repeated on the exact
  successor artifact before acceptance; older aggregate figures are not release
  evidence.
- Exact `/aa` routing/folding gates pass on native Joni, and the Java `/aa`
  workaround is removed.
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

1. Integrate the native `/xx`, adjacent full-fold partition, and `L_` alias
   batch on the exact merged predecessor. Run one warning-free full build plus
   zero-introduction `regexp.t` and fold/property gates, then publish a review
   PR and require exact-head Ubuntu/Windows CI.
2. Complete recursive and runtime `(??{...})` as native nested Joni execution:
   preserve captures, `$^R`, `pos`, modes, byte/Unicode provenance, callback
   unwind, backtracking re-evaluation, and recursion safety. Route every embedded
   closure to Joni and delete constant inlining, progressive errors, and the
   dynamic Java adapter as their gates pass.
3. Complete byte/Unicode pattern provenance through runtime interpolation and
   template composition, then finish `/d`/`u`/`a`/`aa` forward/reverse literal,
   class, property, and backreference folding from generated data. Require
   direct Joni plus ordinary/forced JVM/interpreter zero-introduction gates.
4. Finish the remaining lexical `use re 'strict'`, unescaped-brace, and non-hex
   diagnostic families. Refresh complete
   `reg_mesg.t`, `pat.t`, and `pat_advanced.t` maps after each combined batch.
5. Repeat the four-leg 80-file Java/Joni × JVM/interpreter matrix on the exact
   successor artifact and compare every file with the PR 958 log. Resolve every
   regression, zero-TAP record, timeout, truncation, or incomplete file before
   user acceptance.
6. Remove each proven-obsolete regex transformation from `dev/import-perl5`
   sync sources, regenerate a private unpatched corpus twice, prove byte-for-byte
   idempotence, and run the affected upstream tests without editing them.
7. Use the refreshed impact report to move all remaining ordinary constants to
   native Joni, deleting their Java routes and matcher-semantic preprocessing in
   the same validated slices. Keep `pat_re_eval.t` at 555/555 throughout.
8. Delete Java matching, selector, fallback state, and unreachable preprocessors;
   then run direct/thread regex, CPAN, performance, packaging, notice/license,
   warning-free build, Ubuntu, Windows, and full CI gates.
9. Update the feature matrix and final as-implemented/fork documents, remove or
   summarize redundant design documents, rebase the final stack on `master`, and
   run the complete PR 958 parity audit before declaring Phase 36 complete.

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
- [x] Property/class fold closure
- [x] Fold-mode and byte/Unicode provenance context
- [ ] Forward/reverse literal expansion and backreference folding
- [x] Generated Perl named-sequence lookup and native sequence resolution
- [x] Remove temporary named-sequence encoding from native Joni pattern source
- [x] Restore canonical multi-code-point named-sequence extended-class diagnostics
- [x] Restore Perl diagnostics for unknown/empty/malformed named sequences
- [x] Native `(?[...])` with zero diagnostic regressions
- [x] Native `(?(DEFINE)...)` and removal of its adapter rewrite
- [x] Native ordinary lookbehind and removal of its Java translation
- [x] Native branch reset and removal of its capture-map adapter
- [x] Native plain `\N` non-newline atom and interval forms
- [x] Native recursive/runtime `(??{...})` and removal of dynamic adapters
- [ ] Retire proven-obsolete `dev/import-perl5` regex patches
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

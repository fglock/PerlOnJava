# Full Perl Regex Semantic Parity

## Goal

Complete Perl 5.44 regular-expression semantics on both PerlOnJava execution
backends and converge on the vendored, namespaced Joni engine for all matching.
Java `Pattern` remains available only as a temporary differential backend while
the migration is being proved.

The acceptance target includes matching, captures, match state, callbacks,
dynamic patterns, errors, warning categories and locations, byte and Unicode
behavior, direct/thread parity, and unchanged-source CPAN consumers. Assertions
that inspect Perl's internal optimizer program or debug transcript are reported
separately from language-semantic failures.

The historical comparison point is:

```text
../PerlOnJava/logs/test_20260815_080000_958.log
```

## Architecture

### Final engine boundary

- The vendored Joni fork is the sole production matcher.
- PerlOnJava owns Perl source policy: interpolation provenance, `use re 'eval'`,
  lexical warning and modifier state, executable callback closures, user-defined
  Unicode properties, source locations, and Perl diagnostics.
- Joni owns regex parsing and matcher semantics: captures, conditions,
  recursion, lookarounds, case folding, control verbs, backtracking-visible
  state, and byte/Unicode matching.
- The fork remains runtime-neutral. It receives internal callback IDs and a
  matcher-local handler API, never Perl source or PerlOnJava runtime objects.
- Upstream packages and notices stay unchanged in `third_party/joni`; standalone
  packaging relocates Joni and JCodings into `org.perlonjava.internal`.

### Migration controls

A temporary developer-only backend selector supports separate Java and Joni
corpus runs. It must never run both matchers for one operation because callbacks,
tied variables, `pos()`, and substitutions may have observable side effects.
Joni is the default matcher; explicit Java mode remains only for differential
measurement. The selector and Java matching fields are removed at the end of the
migration.

### Preprocessing boundary

Every current `RegexPreprocessor` rule is classified before it moves:

1. Perl source policy remains outside Joni.
2. Backend-neutral spelling normalization moves into a small frontend scanner.
3. Matcher semantics move into Joni's parser, compiler, and matcher.
4. Java-only syntax rewrites and stack workarounds are deleted with the Java
   matching backend.

Text rewriting must not emulate behavior that depends on backtracking, capture
close order, matcher regions, or encoding.

## Implementation Phases

### Phase 0 — Reproducible differential baseline

1. Run all 80 `perl5_t/t/re` files on JVM and interpreter backends from the same
   clean commit after confirming that no unrelated PerlOnJava builds are active.
2. Save complete output and JSON outside the source tree, compare every file
   against the PR 958 baseline, and reject any unexplained zero-TAP or timeout
   result.
3. Classify every failure as matcher semantics, source policy, diagnostics,
   shared non-regex behavior, or optimizer/debug transcript.
4. Give `pat_psycho*` and `speed*` a configurable two-worker CPU-heavy lane.
   Keep `pat*`, `pat_advanced*`, and memory-sensitive fixtures in a one-worker
   exclusive lane. Every child retains its own hard timeout and process group.

Exit criteria: the baseline is repeatable, direct/thread and JVM/interpreter
differences are visible, and the report identifies the next semantic slice.

### Phase 1 — Joni ordinary-pattern parity

1. Add the temporary forced-backend selector and route ordinary patterns through
   Joni by default in focused tests.
2. Compile separate byte and Unicode variants using the source and target scalar
   metadata; preserve raw byte offsets and convert UTF-8 offsets only at the Perl
   match-variable boundary.
3. Complete matcher regions, anchoring and transparent bounds, zero-width search
   progression, `\G`, `/g`, `/c`, `/o`, captures, duplicate names, branch reset,
   regex-object reuse, substitution, and nested match-state restoration.
4. Run the same corpus once with each forced backend. Do not hide unsupported
   Joni behavior by falling back within a match.

Exit criteria: every assertion previously passing on Java also passes on Joni,
with no direct/thread or JVM/interpreter regression.

### Phase 2 — Conditions and backtracking-visible state

1. Implement numbered and named capture conditions, assertion conditions,
   recursion conditions, `(DEFINE)`, and executable callback conditions in Joni.
2. Complete `(*MARK:name)`, named `(*SKIP:name)`, `$REGMARK`, `$REGERROR`, cut
   boundaries, recursion limits, and interactions with lookarounds, subpattern
   calls, dynamic programs, and callback unwind.
3. Preserve exact capture-close order, provisional match variables, dynamic
   locals, and callback side effects along the selected matcher path.

Exit criteria: focused standard-Perl oracles and applicable `pat_advanced.t`,
`rxcode.t`, `reg_eval_scope.t`, and callback sections agree on both backends.

### Phase 3 — Unicode and pattern syntax completion

1. Implement Perl property aliases, versioned `Age` forms, script extensions,
   `\N{name}`, extended classes, user-defined property recursion and errors, and
   byte-versus-Unicode warning behavior.
2. Complete remaining case-folding, grapheme, lookbehind, and invalid-pattern
   diagnostics in the Joni frontend and engine.
3. Derive property names and aliases from the bundled Perl 5.44 Unicode data so
   behavior does not depend on the host JDK Unicode version.

Exit criteria: semantic assertions in `regexp_unicode_prop.t`, `pat.t`, and
`pat_advanced.t` complete without `JPERL_UNIMPLEMENTED=warn` masking supported
syntax.

### Phase 4 — Runtime source and diagnostics

1. Preserve runtime-eval source names, package and lexical context, warning
   masks, line numbers, syntax errors, and Unicode/byte source identity.
2. Complete recursive and nested `(??{...})`, mixed literal/runtime executable
   source, tied and localized interpolation, regex-object stringification, and
   `/g`, `/c`, `/o` state across callback and exception boundaries.
3. Close the semantic assertions in `pat_re_eval.t`. Track shared non-regex
   `eval` failures separately, but fix them when they prevent regex source from
   executing with standard Perl behavior.

Exit criteria: all 555 `pat_re_eval.t` assertions execute and every semantic
assertion passes on both execution backends.

### Phase 5 — Remove the Java matching backend

1. Move every remaining matcher-semantic preprocessor rule into Joni.
2. Delete Java compiled-pattern variants, feature routing, Java-only rewrites,
   and the temporary backend selector.
3. Retain only the small Perl source-policy/frontend layer described above.
4. Remove stale parser and preprocessing plans or rewrite them to describe the
   final ownership boundary.

Exit criteria: Joni is the only production matcher and ordinary patterns do not
allocate callback state or callback frames.

### Phase 6 — Integration and release

1. Keep regex core tests unpatched. The canonical `perl5/t` directory import
   owns `re/pat.t`; no duplicate file row or regex-test patch may replace or
   weaken upstream assertions.
2. Run `perl dev/import-perl5/sync.pl --only perl5/t` twice, verify the imported
   `re/pat.t` hash against the configured upstream source, and require the
   second run to produce no content diff. If an upstream assertion fails after
   sync, fix PerlOnJava rather than editing the imported test.
3. Run the complete direct and `_thr.t` regex matrix on JVM and interpreter
   backends and compare it file-by-file with both the Phase 0 result and PR 958.
4. Run unchanged Type::Tiny, Regexp::Common, Object::InsideOut, and every CPAN
   suite whose regex capability policy is removed.
5. Run warning-free `make`, Joni upstream tests, packaging and license checks,
   and the thread release matrix.
6. Rewrite `dev/implementation/regex.md` to describe the final as-implemented
   matcher architecture and ownership boundaries, and update
   `docs/design/joni-callout-fork.md` to match the shipped fork API, namespace,
   packaging, callback/unwind contract, and Unicode responsibilities. Review
   both documents for a clear reader path, consistent terminology, and removal
   of superseded proposals or predictions. Audit the remaining regex/Joni
   design documents: delete only content that is wholly redundant and retains
   no useful rationale; otherwise replace historical implementation plans with
   concise summaries that preserve decisions and point to the canonical
   implementation and fork documents. Preserve copyright and authorship
   notices in every retained or consolidated third-party description.
7. Rebase each focused delivery slice onto current master. Require green Ubuntu
   and Windows CI before merging and beginning the next slice.

Exit criteria: all semantic gates pass, no previously passing file regresses,
the regex corpus is reproduced from `dev/import-perl5/sync.pl` without a regex
test patch, and documentation reports optimizer/debug-only exclusions
explicitly.

### Imported-test provenance gate

- `dev/import-perl5/config.yaml` imports `perl5/t/re/pat.t` through the
  canonical `perl5/t` directory entry, without a duplicate row or patch.
- No regex-specific import patch weakens or skips upstream assertions.
- A targeted `--only perl5/t` sync restores the exact configured upstream
  source.
- A second consecutive directory sync is content-idempotent and leaves the
  tree clean.
- The synchronized direct and thread tests run unchanged on JVM and interpreter.

## Test Contract

- Validate every new or changed Perl unit test with system `perl` or `prove`
  before running it with PerlOnJava.
- Run JVM and interpreter tests under `timeout`, capture complete output in
  files, and inspect the saved files rather than truncated terminal output.
- Use `perl dev/tools/perl_test_runner.pl`; the runner requires process `fork`
  and must not run under `jperl`.
- Run direct tests before thread wrappers. A wrapper must preserve the direct
  result and may change only resources and ownership context.
- Unsupported syntax remains fatal until its complete semantic gate passes.
- Do not alter existing Perl core tests to fit PerlOnJava behavior.
- Treat the import manifest and its patch files as temporary compatibility debt:
  remove each regex-test patch hunk as soon as its guarded behavior passes, then
  use a targeted `sync.pl` run to recover the exact upstream test source.
- `make` must pass without warning output before every push.

## Performance Gate

Before removing Java matching, run five warmed ordinary-pattern measurements on
each backend. Joni's median runtime must be within 25% of the Java baseline,
must introduce no new timeout, and must not materially increase steady-state
allocation. A failure blocks backend removal, not semantic fixes.

## Public Interfaces

No permanent public regex API or command-line option is added. The temporary
developer backend selector is removed in Phase 5. Existing Perl syntax,
variables, warning categories, and regex object behavior are the public
compatibility contract.

## Execution Tracker

This tracker records only current plan state. Implementation history, commit
identifiers, dates, and completed repair narratives belong in Git history.

### Phase status

- [x] Phase 0 — reproducible differential baseline
- [ ] Phase 1 — Joni ordinary-pattern parity
- [x] Phase 2 — conditions and backtracking-visible state
- [ ] Phase 3 — Unicode and pattern syntax completion
- [ ] Phase 4 — runtime source and diagnostics
- [ ] Phase 5 — remove the Java matching backend
- [ ] Phase 6 — integration and release

A checked phase means its focused semantic implementation is complete. Release
and no-regression gates remain Phase 6 responsibilities and may reopen a phase
if they expose a semantic defect.

### Current critical path

1. Validate and merge the rebased native-Joni delivery stack bottom-up. After
   each parent merge, rebase its child onto current master, verify the commit
   range and expected file set, and require warning-free build plus green CI.
   Preserve the negative-file manifest and normalized comparator as mandatory
   pre-acceptance gates; no unexplained negative file may be deferred to a long
   acceptance run for discovery.
2. Remove temporary ordinary-pattern Java routing as native Joni replacements
   become green:
   - remove the temporary adapter KEEP-in-lookaround guard after the native
     Joni diagnostic stack passes its combined gate;
   - remove Java routing immediately after each native reducer and combined
     corpus gate are green;
   - use the integration report to choose the next fallback whose removal moves
     the most assertions to pure Joni;
   - retire the next ordinary-pattern fallbacks in this priority order:
     1. route ordinary lookbehind through Joni and delete the Java-only
        lookbehind length analyzer after the 27-assertion four-leg reducer and
        six imported owner-file pass sets are non-regressing; before enabling
        that routing, Joni must enforce Perl's 255-character ceiling, accept
        valid folded-class and nested-assertion lookbehinds, and preserve
        `(*ACCEPT)`-reachable effective widths; do not replace these native
        prerequisites with a narrower permanent Java fallback;
     2. route branch-reset groups and delete their exclusive rewrite only after
        the named/numeric-call reducers and complete imported branch-reset gate
        pass without the temporary automatic-Java guard; relative calls such as
        `(?-1)` must retain their lexical physical target in each branch-reset
        alternative rather than resolving later through the shared logical
        capture number;
     3. route alphabetic assertions and delete their recursive Java rewrite
        after direct Joni tests and the classified `alpha_assertions.t` gate
        prove every remaining failure is understood;
   - never move callback, condition, control-verb, or dynamic-source patterns
     back to Java.
   - use the current forced-Joni JVM gate of 363,164/391,977 assertions across
     the same 80 regex files as the active migration manifest; it has 19
     per-file pass-count regressions against PR 958 that must reach zero;
   - the current combined candidate clears the loose-binary-property fatal:
     each `pat.t` variant executes 1,301/1,302 assertions and passes 1,223;
     classify and close the remaining 79 failures plus the one-test plan
     shortfall; the exact pre-named-character `pat_advanced.t` baseline executes
     all 1,687 assertions and passes 1,577 on both JVM and interpreter, with
     byte-identical 110-row residuals; refresh that complete gate after the
     named-character and `(*THEN)` slices, then close the remaining semantic
     failures without relying on the runner's status heuristic or treating
     optimizer/debug transcripts as semantic parity;
   - the current forced-Joni `reg_mesg.t` gate executes 2,595 assertions and
     passes 1,694 after malformed backreference diagnostics; finish typed
     warning collection, Perl wording/categories, source markers, strict-mode
     classification, and fatal-versus-warning behavior in the native frontend
     and source-policy renderer;
   - after those native patches are integrated, refresh all four legs (forced
     Java/Joni × JVM/interpreter) against one current artifact. Reject zero-TAP,
     timeout, incomplete, or negative-file results before a long acceptance run
     is offered for user testing.
3. Complete the remaining Unicode ownership boundary:
   - parse `\h` and `\H` natively as Perl's exact horizontal-whitespace set in
     direct and character-class forms, including scoped `/a` and `/aa`, without
     changing stock Ruby-syntax hexadecimal escapes;
   - close the leading-loose Block and Script shortcut families, then classify
     the remaining bare binary, enumerated, and General_Category aliases while
     preserving user-property and property-family precedence;
   - preserve per-member fold policy in Joni's composed-class AST through union,
     intersection, negation, and nested classes, then remove the corresponding
     adapter translation;
   - the exact `/aa` envelope passes 837/837 on JVM and interpreter, including
     strict literal, mixed-class, scoped mixed-source, and backreference folds;
     automatic and forced cells are byte-identical on native Joni, and the
     temporary Java fold route is gone; retain this as a combined-corpus guard;
   - complete native multi-character and empty lexical `\N{name}` atoms,
     character-class alternatives, compile caching, lexical scope restoration,
     stringification, and exact extended-class diagnostics without moving
     matcher semantics back into textual preprocessing;
   - represent property-value wildcard parsing and diagnostics with a dedicated
     Joni syntax node rather than flattening wildcard behavior into literal
     ranges prematurely;
   - the native Word_Break, Sentence_Break, and Vertical_Orientation alias
     families are closed with zero corpus introductions; continue from the
     pinned 1,720/83,648 residual set by closing bare binary-property aliases,
     then the remaining Block, General_Category, compatibility, and wildcard
     families;
   - close the remaining generated property/value alias gaps against the pinned
     Perl 5.44 corpus while retaining native `All` through Perl's signed-IV-wide
     scalar domain via the long-range property result rather than truncating to
     Java `int`;
   - keep Perl lexical/source policy in the adapter;
   - prefer bundled Perl Unicode data over duplicating ICU behavior or depending
     on the host JDK Unicode version.
4. Reconcile the regex rows in `docs/reference/feature-matrix.md`, including
   the expected-Joni features currently listed around lines 390–409. Add missing
   Perl regex features with an explicit reducer, owner, and acceptance gate.
5. Retire `RegexPreprocessor` rules and imported-test patches as their native
   implementations land. Rerun targeted `dev/import-perl5/sync.pl` imports and
   require an idempotent second sync before declaring the upstream tests clean.
6. Complete the full release corpus, performance, documentation, packaging,
   license, and cross-platform CI gates; then delete Java matching and the
   temporary backend selector.

### Parallel workstreams

Workstreams must use isolated branches/worktrees and communicate through the
shared handoff files. Ownership is exclusive at the file/semantic-slice level.
Completed candidates stay on their validated base; the coordinator transplants
them onto the canonical stack and verifies `range-diff`. Independent fixes
targeting master start from current master. Engineers rebase themselves only
when the coordinator assigns an exact new base before implementation begins.

1. Integration and PR 958 parity: combine validated runtime repairs, run focused
   and full comparisons, and own CI/readiness of the current integration PR.
2. Native Joni fallback removal: implement parser/compiler/matcher semantics
   that replace temporary Java routing, one independently testable feature at a
   time.
3. Differential prioritization: maintain exact Perl 5.44 oracles, map remaining
   failures to fallback triggers, and rank pure-Joni slices by recovered corpus
   impact.
4. Unicode ownership: identify duplicated ICU/Joni/adapter behavior and propose
   the smallest non-overlapping migration into forked Joni.
5. Documentation and feature inventory: keep the feature matrix and final
   as-implemented documents aligned with validated behavior, without recording
   implementation history in this plan.

At most two full builds may run concurrently. Timing-sensitive final gates run
serialized. Engineers should continue source-independent analysis and focused
work while a full build runs rather than blocking on it.

Implementation lanes run their required full `make` once the candidate is
stable. The review stack then runs one combined full build and one combined
focused reducer matrix; unchanged intermediate stacks are not rebuilt merely
for handoff. Handoff messages are event-driven: candidate ready, build started,
build finished, push complete, or blocker. Heartbeats exist only for crash
detection and do not replace implementation work.

### Delivery checkpoints

- Keep the current integration PR in draft until the PR 958 parity gate passes.
- Mark it ready only after the focused gate, fresh full comparison, warning-free
  build, and required CI are green.
- Once a stable phase is handed to review, continue the next independent slice
  on a new draft PR rather than accumulating unrelated risk in the review PR.
- The release manager owns integration-branch mutation; implementation lanes
  provide pushed, validated commits and do not modify that branch directly.

### Final acceptance checklist

- [ ] Every semantic regex test that passes in the PR 958 baseline still passes.
- [ ] The complete `dev/tools/perl_test_runner.pl` output is compared
      file-by-file with
      `../PerlOnJava/logs/test_20260815_080000_958.log`.
- [ ] JVM and interpreter results agree for direct and thread regex tests.
- [ ] Forced-Joni runs cover ordinary constants, runtime patterns, embedded
      closures, conditions, control verbs, recursion, and dynamic source.
- [ ] `pat_psycho*` and `speed*` complete with the bounded parallel policy.
- [ ] No supported regex test requires `JPERL_UNIMPLEMENTED=warn`.
- [ ] Joni is the sole production matcher; Java routing and selector code are
      removed.
- [ ] Matcher-semantic preprocessing is gone; only documented Perl source-policy
      scanning remains.
- [ ] Obsolete regex import patches are removed and targeted sync is idempotent.
- [ ] `docs/reference/feature-matrix.md` contains every known missing regex
      feature and accurately reports the backend used.
- [ ] `dev/implementation/regex.md` and
      `docs/design/joni-callout-fork.md` describe the shipped architecture
      clearly and consistently.
- [ ] Redundant design documents are removed or reduced to concise rationale
      summaries that point to canonical documentation.
- [ ] Original Joni/JCodings copyright and authorship notices are preserved.
- [ ] Performance remains within the gate defined above.
- [ ] `make` is warning-free; packaging, license checks, Ubuntu CI, and Windows
      CI pass.

### Open decisions and blockers

- Temporary backend-policy tests that assert Java routing can conflict with the
  required final Joni-only behavior. The repository forbids modifying existing
  tests, so a routing-removal slice that makes such an assertion obsolete must
  remain preserved and unmerged until the user explicitly approves a test
  replacement/update policy; implementation must not disguise the new route to
  keep a stale assertion green.
- Production diagnostics use Perl's exact trailing space after an
  end-of-pattern `<-- HERE` marker. The obsolete no-trailing-space formatter
  entry point remains only because its current unit test asserts that legacy
  rendering; deleting it requires the same explicit existing-test update
  policy.
- Optimizer/debug transcript assertions are reported separately from semantic
  behavior and must never silently alter the raw baseline comparison.
- A runtime or shared-language defect that prevents a regex test from executing
  is fixed in its owning subsystem; it is not approximated in the regex engine.
- Any proposed permanent adapter behavior must be classified as Perl source
  policy. If it depends on backtracking or match state, it belongs in Joni.
- Resource-sensitive baselines and final timing tests require a quiet,
  serialized build slot.

## Related Documents and Skills

- `docs/design/joni-callout-fork.md`
- `dev/implementation/regex.md`
- `dev/design/executable-regex-callbacks.md`
- `dev/design/regex-foreach-lexical-fix.md`
- `dev/design/regex-script-properties.md`
- `dev/design/regex-property-aliases.md`
- `.agents/skills/debug-perlonjava/SKILL.md`

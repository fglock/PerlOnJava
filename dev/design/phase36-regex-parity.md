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

1. Retire regex-test accommodations incrementally. Whenever a PerlOnJava fix
   makes a `dev/import-perl5/patches/pat.t.patch` hunk unnecessary, remove that
   hunk and rerun `perl dev/import-perl5/sync.pl --only perl5/t/re/pat.t` to
   restore the unchanged upstream assertions. Do not hand-edit the imported
   test to approximate upstream content.
2. Delete the `pat.t.patch` configuration entry and patch file once its final
   hunk is obsolete. Rerun the targeted sync twice and require the second run to
   produce no diff, proving that the checked-in test is the unpatched Perl 5.44
   source and the import is idempotent.
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

### Upstream patch retirement queue

`pat.t.patch` is reduced in place as these gates close; the corresponding
upstream hunk is restored by the targeted importer before its result is counted:

| Upstream section | Gate before restoring the hunk |
|---|---|
| `(*ACCEPT)` capture-close cases | Exact success and capture values pass without converting fatal setup failures to warnings |
| `pos` inside `(?{...})` | Callback-visible `pos`, captures, and unwind behavior pass on JVM and interpreter |
| reference stringification diagnostics | Unqualified `diag` resolves in the original lexical/package context |
| `${^LAST_SUCCESSFUL_PATTERN}` | Dynamic empty-pattern reuse, copying, matching, and substitution pass |
| `(??{...})` code blocks interpolated from arrays | All original runtime-eval and side-effect assertions pass without an enclosing compatibility `eval` |

The queue is complete only when `config.yaml` no longer names `pat.t.patch`, the
patch file is gone, and two consecutive targeted syncs leave a clean tree.

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

1. Make the current integration PR match the PR 958 baseline without weakening
   raw comparison:
   - integrate each validated runtime repair as an independently reviewable
     commit;
   - run the combined focused-file gate;
   - normalize only proven legacy baseline artifacts while always retaining raw
     counts;
   - run a fresh 622-file comparison and require zero unexplained negative
     per-file deltas;
   - require warning-free `make` and green Ubuntu and Windows CI before review.
2. Remove temporary ordinary-pattern Java routing as native Joni replacements
   become green:
   - finish native nested-lookaround admission and remove its Java routing;
   - route KEEP assertions through Joni and retire Java marker snapshots;
   - use the integration report to choose the next fallback whose removal moves
     the most assertions to pure Joni;
   - never move callback, condition, control-verb, or dynamic-source patterns
     back to Java.
3. Complete the Unicode ownership boundary:
   - audit `UnicodeResolver` against the forked Joni resolver;
   - move regex property parsing and matching into Joni;
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

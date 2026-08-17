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

The last completed 80-file differential recorded 51,002/94,829 passing
assertions, 729 more passing assertions and 58 more planned assertions than that
baseline, with no per-file pass-count regressions.

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
6. Rebase each focused delivery slice onto current master. Require green Ubuntu
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

## Progress Tracking

### Current Status: Joni default; Phase 4 callback values at 479/555

Executable callback source and literal trailing `/x` comments survive canonical
regex-object stringification on both execution backends. Recursive Joni call
frames now preserve the Perl-visible caller capture view for optimistic
callbacks and committed matches, including `$1`, `$^N`, and `$+`. Tied scalar
values returned by dynamic callbacks are materialized before callee regex state
teardown. Joni invalid-backreference errors use Perl's nonexistent-group
diagnostic. Reopened repeated groups expose their preceding closed capture to
dynamic callbacks without altering matching registers. Nested dynamic matcher
completion preserves the last successful block result in `$^R`, including a
runtime `qr` returned by an outer `(??{...})`. The focused `pat_re_eval.t` gate
executes all 555 assertions with 479 passing.

### Completed Phases

- [ ] Phase 0: Reproducible differential baseline
- [ ] Phase 1: Joni ordinary-pattern parity
- [ ] Phase 2: Conditions and backtracking-visible state
- [ ] Phase 3: Unicode and pattern syntax completion
- [ ] Phase 4: Runtime source and diagnostics
- [ ] Phase 5: Remove the Java matching backend
- [ ] Phase 6: Integration and release

### Next Steps

1. Complete the failed-path terminal capture view (tests 85-86).
2. Capture forced-Java and forced-Joni results for the full 80-file direct regex
   corpus, compare both against PR 958, and classify any newly exposed gaps.
3. Run the applicable `pat_advanced.t` control-verb and condition sections,
   then close Phase 2 if their direct and interpreter results agree.
4. Map each remaining `pat.t.patch` hunk to its semantic blocker. As each blocker
   closes, remove its hunk and rerun the targeted importer so validation uses
   the original Perl 5.44 assertions.
5. Capture the clean-branch JVM and interpreter 80-file baselines and compare
   both to PR 958 with the regression exit gate.
6. Inventory the remaining uncommon Unicode aliases and invalid-property
   diagnostics against Perl 5.44's bundled tables, then move the next
   matcher-semantic preprocessor slice into Joni.
7. Keep the warning-free whole-unit-suite gate green with Joni as the default;
   use explicit Java mode only to classify corpus regressions before Phase 5
   removes the legacy backend and selector.

### Open Questions and Blockers

- Exact optimizer/debug transcript assertions are not semantic release blockers;
  each exclusion still requires an explicit report entry.
- Resource-sensitive baselines must wait for unrelated Java builds to finish.
- The interpreter does not reliably expose the lexical package through
  `InterpreterState.currentPackage` while a regex executes. Localized
  `$REGMARK`/`$REGERROR` slots are therefore discovered by their active
  `GlobalRuntimeScalar` identities; a future regex call-site metadata field can
  make nested simultaneous localizations exact without scanning active globals.
- Shared parser or `eval` failures are fixed in focused slices when they block a
  regex semantic test, rather than being approximated inside the matcher.

## Related Documents and Skills

- `docs/design/joni-callout-fork.md`
- `dev/design/executable-regex-callbacks.md`
- `dev/design/regex_parser_integration.md`
- `dev/design/regex_preprocessing_fixes.md`
- `.agents/skills/debug-perlonjava/SKILL.md`

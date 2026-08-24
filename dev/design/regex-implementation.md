# Full Perl Regex Implementation

## Goal

Complete Perl-compatible regular-expression behavior on the native Joni path,
remove temporary migration machinery, validate the complete product surface,
and leave the implementation and documentation maintainable after release.

Joni is the sole production matcher. PerlOnJava owns Perl source policy,
runtime callbacks, lexical context, diagnostics, and conversion between Perl
and Java values. Matcher semantics belong in the Joni fork and must not be
approximated by source rewriting or backend selection.

## Current Status

PR 1093 was merged to `master` as `85d09ff082bcde8b520634f4dc9a6782def904e5`
after user acceptance testing. Its exact tested head was
`e9b4d1238d860b53706eb3bfe35712a3954e30dc`; warning-free `make`, Ubuntu CI,
and Windows CI passed on that head. Cleanup continues on a separate branch
rebased onto the merge.

All user-reported PR 1091 passing-count regressions are restored, including
`op/pack.t`, `op/sub_lval.t`, `op/each.t`, `re/stclass_threads.t`,
`op/attrs.t`, `op/caller.t`, `op/attrproto.t`, `op/tr.t`, and
`benchmark/gh7094-speed-up-keys-on-empty-hash.t`. Permanent regression tests
cover the repaired behavior.

The direct/thread regex projection is complete. Joni is the sole production
matcher, generated Unicode behavior is native, and the retained callback,
diagnostic, packaging, CPAN, and platform corrections are integrated. The
remaining work is cleanup, final validation, performance confirmation,
documentation reconciliation, and removal of temporary warning policy.

## Operating Rules

- Never modify imported Perl or CPAN tests to recover passing counts.
- Add a permanent tracked regression test for every newly discovered product
  failure before considering its fix complete.
- Validate new Perl-language tests with system Perl before PerlOnJava.
- Test interpreter-facing changes on both JVM and interpreter backends.
- Wrap every `jperl`, `jcpan`, and `prove` invocation in `timeout` and capture
  complete output outside the repository.
- Use `make`, never raw Gradle or Maven commands.
- Do not push a candidate until its exact `make` is warning-free.
- Run no more than three expensive jobs concurrently. Run timing-sensitive
  performance samples serially.
- Record the commit SHA used for validation and do not mutate its checkout while
  a gate is running.

## Implementation Plan

### 1. Complete PR 1093 UAT and merge — completed 2026-08-24

- Keep UAT on the exact published PR head while cleanup proceeds on a separate
  branch and worktree.
- If UAT fails, add a permanent unit regression, fix the semantic root, run
  warning-free `make`, republish the PR head, and require green Ubuntu and
  Windows CI before resuming UAT.
- If UAT passes, merge with a merge commit so the reviewed history remains
  available. Record the resulting `master` SHA.

### 2. Clean up project tools

- Inventory `dev/tools` and classify every entry by durable ownership.
- Keep only reusable, project-wide development utilities directly in
  `dev/tools`.
- Move regex-implementation-only tools and tests into
  `dev/regex/tools` and `dev/regex/tools/tests`.
- Move other feature-specific or diagnostic scripts to the directory owned by
  that feature. Preserve useful historical tools; do not silently delete them.
- Update Makefile targets, CI configuration, code, tests, documentation, and
  script-to-script references in the same change.
- Preserve executable modes and relative-path behavior. Run each moved tool's
  focused tests and verify that no stale paths remain.

### 3. Rename the project terminology

- Replace the legacy numbered project name with “regex implementation” in all
  current tracked prose, identifiers, paths, filenames, tools, tests, schemas,
  commands, artifact names, and documentation headings.
- Prefer `regex`, `regex_implementation`, or `regex-implementation` according
  to the surrounding naming convention.
- Update every caller and consumer atomically. Retain a compatibility alias
  only for a genuinely public interface that cannot be migrated immediately;
  document and test any such exception.
- Verify completion with a repository-wide case-insensitive search. Historical
  Git commit messages and external PR discussions are not rewritten.

### 4. Complete code cleanup

- Remove obsolete compatibility code, migration scaffolding, backend-selection
  remnants, and dead regex preprocessing.
- Remove the temporary regex warning-mode policy only after all formerly
  covered files pass without it on both backends.
- Preserve executable-source admission, trusted callback materialization,
  lexical policy, native diagnostics, and user-property callbacks.
- Remove obsolete bundled-module preferences only after their consumers pass
  without them, while preserving user-owned preferences.
- Keep cleanup changes behavior-neutral unless permanent system-Perl-grounded
  tests demonstrate the intended correction.

### 5. Complete documentation review — completed 2026-08-24

- Reconcile `docs/reference/feature-matrix.md` with shipped behavior.
- Review the selected Perl checkout's `pod/perlreref.pod`,
  `pod/perlrecharclass.pod`, `pod/perlrequick.pod`,
  `pod/perlrepository.pod`, `pod/perlre.pod`, `pod/perlretut.pod`, and
  `pod/perlrebackslash.pod` against the implementation.
- Record supported, partial, intentionally divergent, and missing behavior with
  permanent evidence.
- Reconcile `dev/implementation/regex.md` and
  `docs/design/joni-callout-fork.md` with the final architecture.
- Remove redundant or stale project documentation, retaining useful rationale
  through concise links to canonical documents.
- Run documentation and link checks.

The semantic POD audit maps 517 rows across the seven documents, excludes 42
documentation-only headings, and grounds 15 capability families. The review
corrected the stale `enhanced_xx` disposition, recorded locale-sensitive
`ANYOFL` debug presentation and alarm-interruptible pathological matching, and
retained the current-Perl direct-`\K` POD/executable divergence. No synchronized
upstream POD text was modified. All seven POD files pass `podchecker`; the
semantic map tests and offline link check pass.

### 6. Consolidate the remaining open legacy PRs

Inventory as of 2026-08-24 identified the repository's four open PRs:

- PR 1089, the legacy native Joni parity follow-up, is already an ancestor of
  this checkout through the PR 1093 integration. It is fully superseded and
  needs no duplicate pick.
- PR 1065, “docs(skill): reduce file-mailbox coordination round trips”
  (`docs/file-mailbox-bounded-envelopes` → `chore/perl5-sync-35694276`), has
  seven coordination-document commits touching `AGENTS.md` and the
  file-mailbox skill. The retained pieces are bounded outcome leases, explicit
  worktree scoping, observable gate records, activity-kind progress, and safe
  parallel read-only gates. Superseded admission rules, duplicate incident
  text, and speculative acceptance-before-build guidance are omitted.
- PR 1062, “Fix HTML literal recovery at EOF”
  (`fix/html-parser-literal-eof` → `master`), is patch-equivalent to content
  already integrated through the regex stack and needs no duplicate pick.
- PR 1061, “fix(build): distribute the Gradle wrapper”
  (`fix/track-gradle-wrapper` → `master`), contributes the wrapper scripts,
  wrapper JAR, ignore rules, and line-ending attributes. Retain the current
  Gradle 9.6.1 properties instead of downgrading to the PR's 9.2.1 pin.

For each PR, compare commits and file-level behavior against the current
cleanup head. Cherry-pick only useful nonduplicated commits into this PR,
preserving attribution, then adapt conflicts to the current regex terminology,
tool layout, and coordination rules. Run focused validation for every retained
slice followed by the complete gates below. Once the consolidated PR is
published and these dispositions are reviewable, close each superseded PR with
references to the absorbing commit or PR and a concise note stating whether it
was integrated, already patch-equivalent, or intentionally omitted.

### 7. Validate the completed implementation

Run these gates on one recorded, unchanged candidate SHA:

1. `make` must pass without warnings.
2. `make test-bundled-modules` must pass every bundled-module test.
3. The complete Perl test comparison against the PR 1091 baseline must retain
   every baseline-passing assertion and reject missing, zero-TAP, truncated,
   timed-out, malformed, or newly invalid rows.
4. The JVM and interpreter regex results must agree for the retained semantic
   set, except for documented non-semantic optimizer/debug exclusions.
5. Warmed performance benchmarks must show no material regression.
6. Bounded `pat_psycho*` and `speed*` stress tests must complete without
   timeout, incomplete TAP, or semantic regression.
7. Packaging, notices, licenses, generated-source provenance, and SBOM checks
   must pass on freshly built artifacts.
8. Ubuntu, Windows, and all required CI checks must be green.

Preserve the candidate SHA and complete logs.

### 8. Deliver and close

- Publish the cleanup and validation branch as the final implementation PR.
- Provide the exact tested SHA, gate results, known exclusions, and UAT command
  in the PR description.
- Merge only after all required tests and user acceptance pass.
- Update this document's progress tracker and close remaining implementation,
  warning-policy, documentation, and tool-layout items.

## Validation Failure Policy

- Functional or parity failure: add a permanent reducer/unit test, fix the
  semantic root, and rerun affected adjacent coverage before the full gates.
- Bundled-module failure: reproduce unchanged upstream behavior, add a focused
  project regression, and fix product code rather than module tests.
- Performance failure: preserve a reproducible benchmark or bounded stress
  case, establish the last good comparison, and fix only measured product
  causes.
- Platform failure: add portable unit coverage where possible and rerun both
  Ubuntu and Windows CI.
- A product change invalidates prior complete validation for that SHA. Pure log
  collection or external metadata work does not.

## Progress Tracking

### Current Status: PR 1101 UAT regressions fixed; CI and UAT rerun pending

### Completed

- [x] Unified regex implementation candidate published in PR 1093.
- [x] All reported PR 1091 UAT regressions restored with permanent tests.
- [x] Warning-free full build completed on the published PR head.
- [x] Ubuntu and Windows CI passed on the published PR head.
- [x] PR 1093 passed UAT and was merged to `master` (2026-08-24).
- [x] The cleanup branch was safely rebased onto the merged `master` head.
- [x] Release plan simplified to functional, parity, bundled-module,
  performance, packaging, platform, code-cleanup, and documentation gates.
- [x] Regex-specific one-off tools and their dedicated tests moved from
  `dev/tools` to `dev/regex/tools`; callers, relative repository roots, and
  executable modes were audited after the move.
- [x] Historical final-envelope and execution-authority tooling retained under
  `dev/regex/tools` for reproducibility and documented as archived rather than
  an active release gate.
- [x] Legacy numbered project terminology removed from current tracked files;
  the unrelated Moo implementation phase remains unchanged.
- [x] Code, warning-mode, roadmap, and documentation cleanup completed.
- [x] Useful nonduplicated work from PRs 1061 and 1065 was integrated; PRs
  1089 and 1062 were confirmed already absorbed.
- [x] Full `make` passed on runtime-equivalent commit `385251f76` (17 tasks,
  all five unit shards and Joni packaging/tests; 4m33s).
- [x] Full `make test-bundled-modules` passed on `908c7159a` (10 tasks; 7m19s).
- [x] JVM and interpreter core regex gates each passed `pat.t` 1302/1302,
  `pat_thr.t` 1302/1302, and `anyof.t` 1187/1187 (3791/3791 per backend).
- [x] JVM and interpreter bounded stress gates each passed 152/152 across
  `pat_psycho*` and `speed*` without timeout or incomplete TAP.
- [x] JAR/SBOM packaging, notice/license, all 18 Unicode provenance datasets,
  POD, and offline link checks passed.
- [x] Warmed alternating performance passed for the exact runtime-change pair
  `555593a4e` → `bd656aba4`: matching semantic checksums and candidate median
  0.340523 seconds versus baseline 0.341342 seconds.
- [x] The full Perl run completed 622 files and 696,571 assertions with zero
  timeouts. The strict 620-file stable-identity comparison against PR 1091 had
  zero regressions, zero new invalid rows, and 5,458 additional passing
  assertions. `porting/checkcase.t` and `win32/seekdir.t` were separated because
  their plans depend on checkout files/directory entries; the older synchronized
  corpus omits `op/hash-rt85026.t`, an inherited 0/0 row in PRs 1091 and 1093.
- [x] Consolidated PR 1095 published; reviewed disposition comments posted and
  legacy PRs 1089, 1065, 1062, and 1061 closed.
- [x] PR 1095 passed CI and UAT and was merged (2026-08-24).

### Remaining

- [x] One-off tools are moved out of `dev/tools` and stale path references are
  removed.
- [x] Legacy numbered project terminology is removed from the tracked tree.
- [x] Code and warning-mode cleanup is complete.
- [x] The consolidated PR is published and PRs 1089, 1065, 1062, and 1061 are
  closed with their reviewed disposition references.
- [x] Feature, architecture, POD, and link documentation is reconciled.
- [x] `make` passes without warnings.
- [x] `make test-bundled-modules` passes every test.
- [x] Complete Perl regression and JVM/interpreter parity gates pass with the
  three documented non-semantic corpus/environment exclusions above.
- [x] Warmed performance and bounded stress pass on both
  backends.
- [x] Ubuntu/Windows CI passes; local packaging and provenance checks already
  pass.
- [x] Final UAT passes and the implementation is merged.
- [x] Before the final release, the release branch was rebased onto
  `origin/master` at `c7b1a560d`, including the user's parallel CPAN fixes and
  report refresh; post-rebase `make` and `make test-bundled-modules` passed.
- [x] Release acceptance regressions have permanent system-Perl-validated
  coverage where reducible. The focused Catalyst gate passes 568/568, the
  upload lifecycle gate passes 105/105 at baseline speed, and scalar-context
  `sort` plus the JAPH example pass on both backends.
- [x] PR 1101 UAT follow-up restored `op/sort.t` from 180/206 to the 188/206
  reference count and `op/for.t` from 137/149 to 141/149. Permanent reducers
  pass on system Perl and both PerlOnJava backends; `make` and
  `make test-bundled-modules` pass after the fixes.
- [x] The `gh7094-speed-up-keys-on-empty-hash.t` 5/6 result was classified as
  benchmark variance: the hash implementation is unchanged, historical runs
  fluctuate on multiple timing assertions, and the same candidate passed 6/6
  in two immediate bounded reruns.
- [x] The follow-up `op/ref.t` regression is covered by permanent tests for
  read-only numeric, string, and canonical-undef foreach references. The fix
  preserves reference identity while guarding interpreter dereference
  assignment; `op/ref.t` is restored to 423/481 and `op/for.t` remains 141/149.
- [ ] Push the PR 1101 UAT fixes and require Ubuntu and Windows CI to pass.
- [ ] While CI runs, execute the complete local Perl suite with 10 jobs and a
  300-second per-file timeout, then compare its JSON-backed log to PR 1093 and
  treat every negative delta as release-blocking.
- [ ] Rerun release UAT on the resulting unchanged candidate SHA.
- [ ] After approval, rebase the latest `master` if needed and complete the
  5.44.1 release workflow.

## Related Documents and Skills

- `dev/implementation/regex.md`
- `docs/reference/feature-matrix.md`
- `docs/design/joni-callout-fork.md`
- `.agents/skills/debug-perlonjava/SKILL.md`
- `.agents/skills/interpreter-parity/SKILL.md`

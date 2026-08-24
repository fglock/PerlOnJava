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

PR 1093 is the unified implementation candidate. Its exact tested head is
`e9b4d1238d860b53706eb3bfe35712a3954e30dc`. Warning-free `make`, Ubuntu CI,
and Windows CI pass on that head. User acceptance testing is running
independently and must not be disturbed by cleanup work.

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
- A tested identity means only that the commit SHA is recorded and unchanged
  during its validation. No evidence-envelope or artifact-authority framework
  is required.

## Implementation Plan

### 1. Complete PR 1093 UAT and merge

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

### 5. Complete documentation review

- Reconcile `docs/reference/feature-matrix.md` with shipped behavior.
- Review `pod/perlreref.pod`, `pod/perlrecharclass.pod`,
  `pod/perlrequick.pod`, `pod/perlrepository.pod`, `pod/perlre.pod`,
  `pod/perlretut.pod`, and `pod/perlrebackslash.pod` against the implementation.
- Record supported, partial, intentionally divergent, and missing behavior with
  permanent evidence.
- Reconcile `dev/implementation/regex.md` and
  `docs/design/joni-callout-fork.md` with the final architecture.
- Remove redundant or stale project documentation, retaining useful rationale
  through concise links to canonical documents.
- Run documentation and link checks.

### 6. Validate the completed implementation

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

Preserve the candidate SHA and complete logs. Elaborate frozen-artifact graphs,
authority bridges, and final evidence envelopes are explicitly outside the
release requirement.

### 7. Deliver and close

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

### Current Status: PR 1093 UAT active; cleanup branch started

### Completed

- [x] Unified regex implementation candidate published in PR 1093.
- [x] All reported PR 1091 UAT regressions restored with permanent tests.
- [x] Warning-free full build completed on the published PR head.
- [x] Ubuntu and Windows CI passed on the published PR head.
- [x] Release plan simplified to functional, parity, bundled-module,
  performance, packaging, platform, code-cleanup, and documentation gates.

### Remaining

- [ ] PR 1093 UAT passes and the PR is merged.
- [ ] One-off tools are moved out of `dev/tools` and all references pass.
- [ ] Legacy numbered project terminology is removed from the tracked tree.
- [ ] Code and warning-mode cleanup is complete.
- [ ] Feature, architecture, POD, and link documentation is reconciled.
- [ ] `make` passes without warnings.
- [ ] `make test-bundled-modules` passes every test.
- [ ] Complete Perl regression and JVM/interpreter parity gates pass.
- [ ] Warmed performance and bounded stress gates pass.
- [ ] Packaging/provenance checks and Ubuntu/Windows CI pass.
- [ ] Final UAT passes and the implementation is merged.

## Related Documents and Skills

- `dev/implementation/regex.md`
- `docs/reference/feature-matrix.md`
- `docs/design/joni-callout-fork.md`
- `.agents/skills/debug-perlonjava/SKILL.md`
- `.agents/skills/interpreter-parity/SKILL.md`

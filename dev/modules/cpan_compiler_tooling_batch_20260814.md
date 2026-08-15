# CPAN compiler/tooling batch (2026-08-14)

## Scope

Investigate `Test::XML::Compare`, `Heap::Simple::Any`,
`Lingua::StanfordCoreNLP`, `CPAN::Index::API::Object::Author`,
`Chart::Sequence`, `Padre::Plugin::FormBuilder`, and
`Lingua::EN::Keywords`, preferring reusable compiler and tooling fixes over
distribution-specific preferences.

## Progress Tracking

### Current Status: PR #955 rebased and validated locally; CI pending

### Completed Phases

- [x] Phase 1: baseline and system-Perl comparison (2026-08-14)
  - Confirmed portable PerlOnJava failures in `Test::XML::Compare`,
    `CPAN::Index::API`, and the `Lingua::EN::Keywords` dependency chain.
  - Excluded `Heap::Simple::Perl` and `Chart::Sequence` because their own test
    suites or module load fail with system Perl.
  - Classified `Lingua::StanfordCoreNLP` (Inline::Java) and
    `Padre::Plugin::FormBuilder` (Wx/native XS) as unavailable native stacks.
- [x] Phase 2: reduce initial root causes (2026-08-14)
  - Reduced `Lingua::EN::Tagger` to unverifiable bytecode generated for the
    closure installed by `Memoize`.
  - Reduced `CPAN::Index::API` to zero-based virtual DATA positions preventing
    `File::Slurp` from restoring a DATA handle after reading it.
  - Preserved absolute DATA offsets both when updating the early placeholder
    handle and when a module declares DATA under a later package name.
- [x] Phase 3: compiler/runtime implementation (2026-08-14)
  - Made JVM list construction use the common `RuntimeBase` overload, avoiding
    invalid bytecode when context conversion changes a statically inferred type.
  - Made scalar-backed DATA handles expose source-absolute `tell`/`seek`
    positions, including module package handles without an early placeholder.
  - Matched Perl's caller line for a standalone first call in an `if`/`unless`
    body in both backends.
- [x] Phase 4: CPAN verification (2026-08-14)
  - `Test::XML::Compare`: PASS, 13 files / 67 tests.
  - `CPAN::Index::API`: PASS, 7 files / 74 tests.
  - `Lingua::EN::Keywords`: PASS, 2 tests; its Tagger, Memoize, and stemming
    dependency chain built successfully.
- [x] Phase 5: regression validation (2026-08-14)
  - New focused tests pass with system Perl and with both PerlOnJava backends.
  - Full `make` suite passes.
- [x] Phase 6: pull request and CI (2026-08-14)
  - Opened PR #955 from `fix/cpan-compiler-tooling-20260814`.
  - Ubuntu and Windows CI jobs passed.
- [x] Phase 7: post-PR regression repair (2026-08-14)
  - Rebased PR #955 onto `origin/master` at `4de2934e4`.
  - Replaced the broad `RuntimeBase` list-add fallback with a verifier-safe
    `RuntimeList.addList(RuntimeBase)` entry point for statically list-valued
    expressions, retaining specialized overloads and existing control-flow-list
    flattening semantics.
  - Limited conditional caller-line overrides to conditions containing lexical
    declarations, matching Perl without changing ordinary `if`/`elsif` calls.
  - Restored the rebased-master counts for `op/do.t` (94/99), `op/caller.t`
    (92/115 locally), and `op/lexsub.t` (110/160); the additional caller failure
    reported by the comparison also reproduces on current master.
  - Confirmed the keys benchmark is load-sensitive (branch results varied from
    5/6 to 6/6; paired current master scored 4/6 while the branch scored 5/6).
  - Restored watchdog-limited regex progress to 26/59 for `re/speed.t` and
    20/59 for `re/speed_thr.t`, meeting or exceeding the reported baselines.
  - Full `make` suite passes; focused caller-line behavior passes with system
    Perl and with both PerlOnJava backends.
  - Revalidated the affected CPAN paths: `Test::XML::Compare` (13 files / 67
    tests), `Lingua::EN::Keywords` (2 tests), and `CPAN::Index::API` (7 files /
    74 tests) all pass.
- [x] Phase 8: rebase onto current master (2026-08-15)
  - Rebased PR #955 onto `origin/master` at `1be14c00b`.
  - Kept master's newer full-source DATA handle implementation and dropped the
    superseded scalar-handle base-offset shim while retaining the absolute
    DATA-position regression test.
  - Full `make` suite passes after the rebase.
  - Rechecked the reported regression files: `op/do.t` 94/99,
    `op/caller.t` 93/115, `op/lexsub.t` 110/160, keys benchmark 5/6,
    `re/speed.t` 26/59, and `re/speed_thr.t` 26/59.

### Next Steps

1. Push the current-master rebase and wait for PR #955 CI.
2. Review and merge PR #955 after CI passes.

### Open Questions

- None. No additional portable failure appeared after the Tagger verifier
  boundary was fixed.

## Related References

- `.agents/skills/debug-perlonjava/SKILL.md`
- `dev/modules/cpan_compiler_tooling_suite.md`

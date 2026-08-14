# CPAN compiler/tooling batch (2026-08-14)

## Scope

Investigate `Test::XML::Compare`, `Heap::Simple::Any`,
`Lingua::StanfordCoreNLP`, `CPAN::Index::API::Object::Author`,
`Chart::Sequence`, `Padre::Plugin::FormBuilder`, and
`Lingua::EN::Keywords`, preferring reusable compiler and tooling fixes over
distribution-specific preferences.

## Progress Tracking

### Current Status: implementation and local validation complete; PR in progress

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

### Next Steps

1. Commit, push, open a PR, and monitor CI.

### Open Questions

- None. No additional portable failure appeared after the Tagger verifier
  boundary was fixed.

## Related References

- `.agents/skills/debug-perlonjava/SKILL.md`
- `dev/modules/cpan_compiler_tooling_suite.md`

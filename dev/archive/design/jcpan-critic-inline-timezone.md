# jcpan compiler and tooling compatibility

## Goal

Fix reusable PerlOnJava compiler/runtime/tooling failures encountered by
`Perl::Critic::More`, `Module::Install::InlineModule`,
`TimeZone::TimeZoneDB`, and their dependency chains without distribution
preferences. Native functionality should reuse bundled Java libraries where
possible.

## Progress Tracking

### Current Status: implementation and target validation complete (2026-08-10)

### Completed Phases

- [x] Baseline and system-Perl comparison (2026-08-10)
  - Confirmed `Perl::Critic::More` and `Module::Install::InlineModule` are
    runnable upstream targets.
  - Confirmed the `TimeZone::TimeZoneDB` distribution test suite fails under
    system Perl and its dependency metadata contains a circular chain:
    `Params::Get` -> `Test::Returns` -> `Return::Set` -> `Params::Get`.
- [x] Native dependency replacement (2026-08-10)
  - Added a Java-backed `Digest::JHash` implementation matching the upstream
    XS signed-byte and unsigned-32-bit behavior.
  - Files: `DigestJHash.java`, `Digest/JHash.pm`, bundled module tests.
- [x] CPAN and MakeMaker tooling (2026-08-10)
  - CPAN's metadata fallback now runs a separate generated Makefile.PL instead
    of overwriting a distribution's possibly read-only file.
  - MakeMaker now emits AutoSplit subprocesses only for modules that actually
    use AutoLoader, avoiding hundreds of JVM startups for POD-only modules.
- [x] Core-header and stash compatibility (2026-08-10)
  - `Config.pm` materializes the standard `CORE/keywords.h` probe required by
    B::Keywords.
  - Startup no longer exposes nonexistent `$^B`, `$^G`, `$^J`, `$^K`, `$^Q`,
    `$^U`, `$^Y`, and `$^Z` globals to stash inspection.
- [x] Weak-reference lifecycle performance (2026-08-10)
  - Deferred targeted weak sweeps until temporary assignment roots are gone.
  - Avoided redundant global walks after ordinary destruction and restricted
    rescued-object cleanup to genuine aggregate rescue cases.
- [x] Full-suite runtime isolation (2026-08-10)
  - Explicit registered-warning bits now override the broader `all` bit, so
    `no warnings 'Category'` remains effective through native warning helpers.
  - Virtual descriptor recycling now skips occupied descriptors and closing an
    older borrowed alias cannot unregister a newer live descriptor owner.

### Validation

- System Perl: Digest::JHash upstream tests pass (2 files, 6 tests).
- System Perl: B::Keywords upstream tests pass (3 files, 553 tests).
- PerlOnJava: B::Keywords upstream tests pass (3 files).
- PerlOnJava: `jcpan -t Perl::Critic::More` passes (8 files, 55 tests).
- PerlOnJava: `jcpan -t Module::Install::InlineModule` passes (2 files, 1 test).
- PerlOnJava: the PPI round-trip stress test reached assertion 2,101 without a
  failure before its 900-second guard. Before the reachability fixes it reached
  only 82 assertions in 120 seconds; this remains a long-running dependency
  test rather than a complete pass.
- Full `make` passes all compilation and unit-test shards.

### Next Steps

1. Continue profiling PPI if a sub-15-minute full round-trip run is required.

### Open Questions

- `TimeZone::TimeZoneDB` remains excluded because its upstream suite fails on
  system Perl and its published prerequisites contain the circular chain
  documented above. No distribution preference was added.

## Related documentation and skills

- `docs/reference/bundled-modules.md`
- `.agents/skills/debug-perlonjava/SKILL.md`
- `.agents/skills/profile-perlonjava/SKILL.md`
- `.agents/skills/port-cpan-module/SKILL.md`

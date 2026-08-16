# CPAN compiler/tooling batch: Perl::ToPerl6 through Devel::SlowBless

## Goal

Classify and repair the reusable compiler, regex, runtime, and CPAN-tooling
defects exposed by `jcpan -t` for Perl::ToPerl6,
Game::HeroesVsAliens::Alien, App::Greple::update, GCJ::Cni,
Data::Semantic, Text::HTML::CollapseWhitespace, DBM::Deep::Blue, and
Devel::SlowBless. Distributions that fail under the same local system Perl are
out of scope, as requested.

## System Perl classification (2026-08-16)

| Target | Classification |
|---|---|
| Perl::ToPerl6 | Ignore: system Perl also fails `t/05_utils.t` test 136. |
| Game::HeroesVsAliens::Alien | Ignore: system Perl cannot build Alien::SDL/SDL; the native dependency download/build fails. |
| App::Greple::update | In scope: system Perl passes 2 files / 4 tests. |
| GCJ::Cni | Ignore: system Perl cannot find the obsolete `gcj/cni.h` toolchain. |
| Data::Semantic | In scope: system Perl passes 15 files / 16 tests. |
| Text::HTML::CollapseWhitespace | Ignore: its Text-HTML-Turndown distribution also fails under system Perl (`t/01-turndown.t` test 46). |
| DBM::Deep::Blue | Ignore: its native source includes unavailable `malloc.h` and fails compilation on this host. |
| Devel::SlowBless | In scope: system Perl passes 1 file / 4 tests. |

Full baseline output is captured in `/tmp/jcpan_*_baseline.log` and
`/tmp/system_perl_*` logs.

## Progress Tracking

### Current Status: Complete (2026-08-16)

### Completed Phases

- [x] Phase 1: Baseline and system-Perl classification (2026-08-16)
  - Ran every requested `jcpan -t` target sequentially with hard timeouts.
  - Installed missing system-Perl prerequisites into an isolated `/tmp` tree
    and tested the exact unpacked distributions.
- [x] Phase 2: Root-cause reduction (2026-08-16)
  - App::Greple::update requires named-capture regex conditionals, which the
    vendored Joni engine supports but the backend router did not select.
  - Data::Semantic exposes a lazy generated-sub VerifyError after module
    registration side effects; file-level interpreter retry then duplicates
    those registrations.
  - Devel::SlowBless needs its two XS generation-counter functions backed by
    runtime-owned Java state.
- [x] Phase 3: Implementation (2026-08-16)
  - Routed named-capture conditionals to Joni.
  - Translated Perl brace-form named backreferences for Joni and covered
    recursive named patterns.
  - Added eager lazy-sub verification so only the invalid sub falls back.
  - Added the Devel::SlowBless Java XS bridge and runtime sub-generation
    counter.
  - Corrected string typeglob aliases and ampersand calls inside
    `delete`/`exists` subscripts.
  - Implemented registered-child handling for `wait`/`waitpid`, made pipe
    output pumps drain before close returns, and isolated replay-process STDIN
    until the fork point. These fixes make Perl's `open HANDLE, '|-'` filter
    pattern reliable without distribution-specific changes.
  - Added system-Perl-validated regression tests.
- [x] Phase 4: End-to-end validation (2026-08-16)
  - `App::Greple::update`: 2 files / 4 tests, PASS.
  - `Data::Semantic`: 15 files / 16 tests, PASS.
  - `Devel::SlowBless`: bundled upstream suite, 1 file / 4 tests, PASS.
  - All new unit tests pass under system Perl and both PerlOnJava backends.
  - Full `make` completed successfully.

### Files Changed

- Compiler/backend: `EmitSubroutine.java`, `ParseInfix.java`.
- Regex: `JoniRegexPattern.java`.
- Process and IO runtime: `PerlRuntime.java`, `RuntimeIO.java`,
  `PipeOutputChannel.java`, `WaitpidOperator.java`.
- Runtime semantics: `RuntimeGlob.java`, `MroRuntimeState.java`, `Mro.java`.
- Java module bridge: `DevelSlowBless.java`, `Devel/SlowBless.pm`.
- Regression coverage: eight focused tests in `src/test/resources/unit`.

### Next Steps

1. Merge after CI and review.

### Open Questions

- None. Native distributions excluded by the system-Perl rule remain
  documented rather than hidden behind distribution preferences.

## Related References

- `.agents/skills/debug-perlonjava/SKILL.md`
- `docs/guides/module-porting.md`

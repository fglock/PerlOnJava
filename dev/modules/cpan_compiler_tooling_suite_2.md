# CPAN compiler and tooling compatibility suite II

## Goal

Make `jcpan -t` work for Data::Collector, Pod::Query, Char::Windows1258,
Map::Metro::Plugin::Map::Oslo, DBIx::Dictionary,
Date::Holidays::Abstract, Music::Note::Role::Operators, and their
dependencies. Fix reusable compiler and CPAN tooling defects first. A target
that fails under the local system Perl may be left unsupported with the
failure recorded.

## Baseline (2026-08-14)

| Target | First actionable result |
|---|---|
| Data::Collector | Passes: 3 files / 12 tests. |
| Pod::Query | Empty `qr//` values interpolated from hashes incorrectly reuse the previous successful match. |
| Char::Windows1258 | All 210 files abort because upstream deliberately rejects an executable whose `$^X` contains `jperl`; the same suite passes 5,703 tests on system Perl. |
| Map::Metro::Plugin::Map::Oslo | Dependency failures exposed unsupported `PadWalker::var_name`, incomplete low-level `Unicode::Normalize`, missing reverse charnames lookup, and weak-reference loss for `map` temporaries. |
| DBIx::Dictionary | DBI `execute` returned `-1` for a successful `SELECT`, which failed DBI's documented truth test. |
| Date::Holidays::Abstract | Bare `SUPER::can` resolved relative to `UNIVERSAL` instead of the caller package. |
| Music::Note::Role::Operators | Required native `Math::Factor::XS`; after replacing it in Java, a dependency exposed missing `POSIX::log2`. |

Full command output is captured under `/tmp/jcpan-*.log`; every `jcpan`,
`jperl`, and `prove` run is wrapped in `timeout`.

## Progress Tracking

### Current Status: Implementation and requested-target validation complete

### Completed Phases

- [x] Repository pre-flight and feature branch (2026-08-14)
  - Confirmed the tree was clean.
  - Created `fix/jcpan-compiler-tooling-batch`.
- [x] Initial system-Perl classification (2026-08-14)
  - Char::Windows1258 passes 210 files / 5,703 tests.
  - No requested target was excluded as a system-Perl failure.
- [x] Compiler and runtime compatibility fixes (2026-08-14)
  - Preserved the construction origin of empty `qr//` values through both
    bytecode backends so they no longer acquire the previous match pattern.
  - Kept `map`/`grep` aliases alive while their temporary values are active,
    allowing weak references to those aliases to behave like Perl.
  - Resolved bare `SUPER::can` relative to the current caller package.
  - Implemented caller-aware `PadWalker::var_name` for live and captured
    lexicals, including aliases inside `map` and `grep`.
  - Added low-level Unicode normalization decomposition, canonical reordering,
    and composition using the ICU library already shipped by PerlOnJava.
  - Added ICU-backed reverse Unicode character-name lookup.
  - Made successful DBI `SELECT` execution return the true-but-zero `0E0`
    value required by DBI semantics.
  - Added the standard `POSIX::log2` helper and exports.
  - Files: bytecode compiler/interpreter and regex emitter/runtime, list and
    scalar runtimes, `RuntimeCode`, `Universal`, `Internals`, `PadWalker`,
    `UnicodeNormalize`, `_charnames`, `Charnames`, `DBI`, and `POSIX`.
- [x] Native dependency and CPAN tooling fixes (2026-08-14)
  - Replaced `Math::Factor::XS` with a Java module; its upstream suite passes
    4 files / 69 tests without loading native code.
  - Added and bootstrapped a reusable Char::Windows1258 patch that delegates
    its source-generation step to system Perl, removes its obsolete `jperl`
    rejection, and avoids regex constructs that the generated compatibility
    layer cannot safely transform itself.
  - Added and bootstrapped a MooseX BetterAnonClassNames patch that removes the
    obsolete `autobox::Core` dependency from both source and build metadata.
- [x] Regression coverage and requested-target verification (2026-08-14)
  - New regression tests were first validated with system Perl and then with
    both PerlOnJava backends.
  - Full `make` passes after the implementation changes.
  - Passing `jcpan -t` results: Data::Collector (3 files / 12 tests), Pod::Query
    (10 / 246), DBIx::Dictionary (7 / 30), Date::Holidays::Abstract (9 / 3),
    Map::Metro::Plugin::Map::Oslo (3 / 4), and
    Music::Note::Role::Operators (2 / 6).
  - Char::Windows1258 passes all 210 files / 5,703 tests, matching its system
    Perl baseline. `HARNESS_OPTIONS=j4` was used to reduce the cost of its
    many independent test files while retaining the exact `jcpan -t` path.

### Next Steps

1. Review and commit the final diff.
2. Open the pull request and monitor CI to completion.

### Open Questions

- None.

## Related References

- `dev/modules/cpan_compiler_tooling_suite.md`
- `dev/design/patch-and-cpan-prefs-layout.md`
- `.agents/skills/debug-perlonjava/SKILL.md`

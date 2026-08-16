# CPAN compiler/tooling batch: BioX and related modules

## Goal

Make the requested `jcpan -t` targets use reusable PerlOnJava compiler,
runtime, and CPAN-tooling behavior. Distribution preferences are not used as
the primary fix.

## Progress Tracking

### Current Status: implementation and local validation complete; CI verification in progress

### Completed Phases

- [x] Baseline classification (2026-08-16)
  - `Template::Plugin::Markdown` already passes.
  - `BioX::Seq` failed because overloaded `.=` replaced the blessed object.
  - `Math::Aronson` failed because boolean operands took the string XOR path.
  - `Changes` failed in the `Wanted`/locale dependency boundary.
  - `Catmandu::Importer::Parltrack` failed while `Class::XSAccessor` used
    optional `Sub::Util::set_subname` metadata.
  - `Nephia::Plugin::FormValidator::Lite` cannot resolve its `Nephia::Plugin`
    and `Plack::Test` dependencies; the isolated system Perl also lacks those
    modules, so it is unsupported under the requested rule.
- [x] Reusable compiler/runtime fixes
  - Preserve overloaded object results for compound string concatenation.
  - Treat boolean scalars as numeric operands for `^`.
  - Implement `no overloading` dispatch for arithmetic and boolean contexts.
  - Permit guarded fully-qualified optional constant names under `strict subs`.
  - Compile fully-qualified `CORE::` control-flow and infix operations.
  - Preserve caller context, including `OBJECT`, across JVM and interpreter
    wrapper frames and non-local returns.
  - Apply case folding to POSIX regex character classes.
- [x] CPAN tooling compatibility
  - Reinitialize Java-backed XS modules when an isolated CPAN worker has stale
    `%INC` state but an undefined stash entry.
  - Snapshot named coderefs at compile time instead of retaining mutable stash
    scalars, fixing generated `Eval::TypeTiny` callbacks.
  - Add reusable Java-module loading and the `Wanted` context compatibility
    surface.
  - Expose the bundled SQLite JDBC implementation through DBD::SQLite metadata
    and constants instead of introducing another native dependency.
  - Complete reusable Fcntl and POSIX constants/functions required by the
    dependency graph.
- [x] Unit validation
  - All ten new compatibility tests were validated with system Perl: eight
    passed and two correctly skipped when their optional modules were absent.
  - Final `make` passed in 3m43s after all compiler and tooling changes.
- [x] Target verification
  - `BioX::Seq`: 7 files, 127 tests, PASS.
  - `Changes`: 26 files, 375 tests, PASS.
  - `Math::Aronson`: 4 files, 55 tests, PASS.
  - `Catmandu::Importer::Parltrack`: 3 tests, PASS.
  - `Template::Plugin::Markdown`: 2 files, 3 tests, PASS.
  - `Nephia::Plugin::FormValidator::Lite`: unsupported under the requested
    system-Perl exception. Its own system-Perl suite fails, and the current
    CPAN package index no longer contains Nephia or three declared companion
    plugins. MetaCPAN identifies those releases as BackPAN-only.

### Next Steps

1. Commit and push the feature branch.
2. Update PR #979 and monitor GitHub Actions.

### Open Questions

- Whether PerlOnJava should eventually add an opt-in BackPAN resolver for
  distributions whose live CPAN metadata still declares withdrawn modules.

## Related References

- [`debug-perlonjava`](../../.agents/skills/debug-perlonjava/SKILL.md)
- [`AGENTS.md`](../../AGENTS.md)

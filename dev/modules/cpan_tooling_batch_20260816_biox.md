# CPAN compiler/tooling batch: BioX and related modules

## Goal

Make the requested `jcpan -t` targets use reusable PerlOnJava compiler,
runtime, and CPAN-tooling behavior. Distribution preferences are not used as
the primary fix.

## Progress Tracking

### Current Status: implementation complete; PR verification in progress

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
  - Permit guarded fully-qualified optional constant names under `strict subs`.
- [x] CPAN tooling compatibility
  - Make `Sub::Util::set_subname` optional for generated XS-style accessors.
  - Add the pure-Perl `Wanted` context compatibility surface and export `want`.
- [x] Unit validation
  - Final `make` passed after the optional-constant parser guard and the
    isolated Sub::Util compatibility changes.
- [x] Target verification
  - `BioX::Seq`: passes.
  - `Math::Aronson`: passes.
  - `Template::Plugin::Markdown`: passes.
  - `Changes`: reaches its locale data dependency, which requires a usable
    DBD::SQLite driver; the local system Perl has no DBD::SQLite installation.
  - `Catmandu::Importer::Parltrack`: its dependency stack still captures a
    stale `Sub::Util::set_subname` callback inside `Eval::TypeTiny` in the
    isolated CPAN test worker; this is recorded for follow-up rather than
    hiding it with a distribution preference.
  - `Nephia::Plugin::FormValidator::Lite`: unsupported because both
    `Nephia::Plugin` and `Plack::Test` are absent from the local system Perl.

### Next Steps

1. Commit and push the feature branch.
2. Open a PR and monitor GitHub Actions.
3. Follow up on the isolated `Eval::TypeTiny` callback and DBD::SQLite port.

### Open Questions

- `Changes` depends on a broad locale/data stack; any remaining failure must
  be checked against system Perl before being classified as an implementation
  defect.
- `Catmandu::Importer::Parltrack` may expose additional optional XS constants
  after its first compile blocker is removed.

## Related References

- [`debug-perlonjava`](../../.agents/skills/debug-perlonjava/SKILL.md)
- [`AGENTS.md`](../../AGENTS.md)

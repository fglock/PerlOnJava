# jcpan Compiler and Tooling Batch

## Goal

Remove reusable compiler/runtime/tooling blockers found while testing:

- `Dist::Zilla::Plugin::Manifest::Read`
- `Plack::App::Proxy::Selective`
- `Text::MetaText`
- `Test::Class::Filter::Tags`
- `Catmandu::Exporter::HTML`
- `Map::Tube::Sydney`
- `App::SQLiteUtils`
- `Char::Latin2`

Distributions which also fail under system Perl are recorded and excluded from
PerlOnJava-specific fixes.

## Progress Tracking

### Current Status: implementation complete (2026-08-16)

### Completed Phases

- [x] Phase 1: System-Perl eligibility baseline (2026-08-15)
  - `Dist::Zilla::Plugin::Manifest::Read` passes system Perl.
  - `Plack::App::Proxy::Selective`, `Text::MetaText`,
    `Test::Class::Filter::Tags`, `Catmandu::Exporter::HTML`, and
    `Map::Tube::Sydney` fail in system Perl or in their prerequisite closure and
    require no PerlOnJava-specific distribution workaround.
  - `App::SQLiteUtils` is excluded because its required system-Perl closure
    fails in Data::Sah/lib::filter and cannot provide a valid comparison.
  - `Char::Latin2` passes system Perl (210 files / 5,725 tests).

- [x] Phase 2: Shared Dist::Zilla blockers (2026-08-15)
  - Added structural translation for supported `(*PRUNE)` regex forms.
  - Made the Unix and Windows launchers cache and reuse an absolute Java path,
    allowing nested `$^X` calls after build tools replace `PATH`.
  - Preserved Perl's `((), @array)` iteration-list snapshot idiom in the parser
    and both execution backends.
  - Made direct array iteration follow the live array length when the loop body
    mutates its structure.
  - `Dist::Zilla::Plugin::Manifest::Read` now passes 2 files / 8 tests with its
    complete CPAN prerequisite path.
  - Files: `jperl`, `jperl.bat`, `RegexPreprocessor.java`, `ParseInfix.java`,
    `EmitLiteral.java`, `BytecodeCompiler.java`, `InlineOpcodeHandler.java`,
    `RuntimeArray.java`, `RuntimeList.java`, and focused tests.

- [x] Phase 3: Char::Latin2 compatibility and final validation (2026-08-16)
  - Reused the established Char::Latin7/Char::Windows1258 compatibility design
    for the historical `jperl` executable-name collision and Perl regex-code
    source transformer.
  - Registered the generic patch through CPAN bootstrap configuration; no
    installed-user preference edits are required.
  - `Char::Latin2` passes PerlOnJava (210 files / 5,725 tests).
  - A clean `jcpan -t Dist::Zilla::Plugin::Manifest::Read` passes (2 files / 8
    tests) and exits successfully.
  - Full `make` passes.

### Next Steps

1. Monitor pull-request CI.
2. Merge after review when CI is green.

### Open Questions

- None.

## Related Skills and Documents

- `.agents/skills/debug-perlonjava/SKILL.md`
- `AGENTS.md`

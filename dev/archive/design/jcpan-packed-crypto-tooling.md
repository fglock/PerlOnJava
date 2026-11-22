# jcpan packed-array and crypto dependency compatibility

## Goal

Fix reusable compiler, runtime, and CPAN-tooling failures encountered while
testing `Tie::Array::Packed`, `Image::Heatmap`,
`Log::Any::Adapter::Catalyst`, and `Git::Crypt`. Prefer shared fixes and
Java replacements for genuine XS boundaries over distribution preferences.
Ignore a target only when its unchanged suite also fails under system Perl.

## Progress Tracking

### Current Status: complete (2026-08-11)

### Completed Phases

- [x] Phase 1: Initial target and system-Perl baseline (2026-08-11)
  - `Image::Heatmap` passes its fresh isolated `jcpan -t` run: 1 file,
    7 assertions.
  - `Tie::Array::Packed` fails only at its unsupported XS load boundary under
    PerlOnJava; its unchanged upstream suite passes system Perl: 2 files,
    223 assertions.
  - The new focused packed-array regression passes system Perl: 44 assertions.
  - The new Blowfish vector regression passes system Perl: 13 assertions.

- [x] Phase 2: Java XS replacements (2026-08-11)
  - Added `TieArrayPacked.java` and the upstream-compatible Perl facade for
    packed tied-array storage, mutation, splicing, ordering, and search.
  - Added `CryptBlowfish.java` and its Perl facade. The native block operation
    reuses the BouncyCastle dependency already shipped by PerlOnJava.
  - Added bundled module regression suites for both ports.

- [x] Phase 3: Shared tooling and byte-semantics fixes (2026-08-11)
  - Fixed simplified MakeMaker's broad package-tree scan so test helpers below
    `t/` or `xt/` cannot overwrite production modules in `blib`. This restores
    IO-All's real `IO::All::Filesys` instead of staging `t/IO_Dumper.pm` over it.
  - Fixed `pack` H/h nibble folding and field padding to match system Perl,
    including non-hex input used by `Git::Crypt` salt construction.
  - Fixed raw `Digest::MD5` results to retain byte-string semantics, allowing
    Crypt::CBC to derive an eight-octet Blowfish IV under `use bytes`.
  - Added a Java `Proc::ProcessTable` bridge backed by `ProcessHandle` for the
    CLI process lookup used by `Git::Crypt`.
  - Files: `ExtUtils/MakeMaker.pm`, `PackWriter.java`, `DigestMD5.java`,
    `ProcProcessTable.java`, and their focused regression tests.

- [x] Phase 4: Target validation (2026-08-11)
  - `Tie::Array::Packed`: forced upstream suite passes, 2 files and 223 tests.
  - `Image::Heatmap`: fresh isolated suite passes, 1 file and 7 tests.
  - `Log::Any::Adapter::Catalyst`: isolated suite passes, 2 files and 75 tests.
  - `Git::Crypt`: isolated suite passes, 5 files and 71 tests.
  - Full `make` gate passes after the final runtime changes.

### Next Steps

1. No implementation work remains for these four targets.
2. Treat executable regex callbacks as a separate compiler project if an
   independently required distribution reaches that capability.

### Open Questions

- `ExtUtils::ParseXS` 3.64 uses executable `(??{ ... })` regex callbacks.
  The current callback contract is tracked by
  `docs/design/joni-callout-fork.md`; it surfaced only through
  HTTP::Message's optional Brotli recommendation during the Catalyst run and
  must not be papered over with a source patch.
- `Proc::ProcessTable`'s native upstream suite still fails under system Perl in
  this sandbox because `sysctl` and process/fork access are denied. The Java
  bridge deliberately implements the portable fields available through
  `ProcessHandle`; platform-specific native metrics remain outside this work.

## Related documentation and skills

- `docs/design/joni-callout-fork.md`
- `docs/guides/module-porting.md`
- `.agents/skills/debug-perlonjava/SKILL.md`
- `.agents/skills/port-cpan-module/SKILL.md`

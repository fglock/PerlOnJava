# CPAN compiler and tooling compatibility batch

## Goal

Make the system-Perl-compatible surfaces of the following targets work through
`jcpan`, fixing reusable compiler, runtime, and CPAN tooling behavior instead of
adding distribution preferences:

- `Catalyst::Plugin::AuthenCookie`
- `App::Timestamper::WithElapsed`
- `Chemistry::File::SMILES`
- `Net::DRI`
- `Catalyst::Action::FromPSGI`

Reuse bundled Java providers such as BouncyCastle when native CPAN dependencies
need equivalent primitives.

## Baseline

| Target | System Perl | PerlOnJava baseline |
| --- | --- | --- |
| Catalyst::Plugin::AuthenCookie | Current system `HTTP::Cookies` discards the test's already-expired March 2020 cookie, so the upstream assertion is no longer green | Outside compatibility target |
| App::Timestamper::WithElapsed | 2 files / 4 assertions pass | output subprocess spins after consuming redirected stdin because `select` never reports EOF |
| Chemistry::File::SMILES | 9 files / 82 assertions pass | 8 files pass; `t/parse.t` hits invalid JVM bytecode for `local undef $/` |
| Net::DRI | Fails under Perl 5.42, including an illegal `{1.4}` regex in the distribution itself | Outside compatibility target |
| Catalyst::Action::FromPSGI | Not needed for differential diagnosis | Passes after normal prerequisite resolution |

## Implementation phases

1. Support the legacy `local undef $var` form in both compiler backends.
2. Make standard-input readiness report EOF without spinning async event loops.
3. Re-run target suites, the full project suite, and orphan-process checks.

## Progress tracking

### Current status: Complete (2026-08-12)

### Completed phases

- [x] Baseline and system-Perl differential checks (2026-08-12)
  - Confirmed SMILES and Timestamper pass under system Perl.
  - Excluded Net::DRI because its current distribution fails under system Perl.
  - Confirmed Catalyst::Action::FromPSGI passes after dependency resolution.
- [x] Phase 1: compiler support for `local undef` (2026-08-12)
  - Both JVM and interpreter compilers now treat `local undef $var` as the
    ordinary undef expression that Perl's parser exposes.
  - Removed the invalid JVM operand-stack path that crashed ASM frame merging.
  - Files: `EmitOperatorLocal.java`, `BytecodeCompiler.java`, `local_undef.t`.
- [x] Phase 2: standard-input EOF readiness (2026-08-12)
  - Standard descriptors now participate in the common `RuntimeIO` descriptor
    lookup.
  - Unix standard-input readiness uses native `poll(2)` through the existing FFM
    layer, including the Linux/macOS `nfds_t` ABI difference.
  - Windows standard-input readiness uses the existing FFM layer to inspect CRT
    descriptors and Win32 disk, pipe, console, and EOF state without consuming
    input.
  - `StandardIO` records EOF from `sysread`, allowing async loops to stop instead
    of polling forever.
  - Files: `FFMPosixInterface.java`, `FFMPosixLinux.java`, `StandardIO.java`,
    `FileDescriptorTable.java`, `RuntimeIO.java`, `stdin_select_eof.t`.
- [x] Phase 3: end-to-end verification (2026-08-12)
  - `App::Timestamper::WithElapsed`: 2 files / 4 assertions pass.
  - `Chemistry::File::SMILES`: 9 files / 81 assertions pass; one optional POD
    test skips because Test::Pod is not installed.
  - `Catalyst::Action::FromPSGI`: 3 files / 18 assertions pass.
  - Full `make` passes; the parallel unit suite reports 399 tests completed and
    2 skipped.
  - Both new unit tests were validated under system Perl before PerlOnJava.

### Next steps

1. No remaining work for the system-Perl-compatible requested surface.

### Open questions

- `Catalyst::Plugin::AuthenCookie` has a date-sensitive upstream assertion for a
  March 2020 cookie which current system `HTTP::Cookies` discards as expired.
- `Net::DRI` 0.96 fails under current system Perl, including a distribution
  regex using the invalid quantifier `{1.4}`.

## References

- [Module porting guide](../../docs/guides/module-porting.md)
- Skill: `debug-perlonjava`

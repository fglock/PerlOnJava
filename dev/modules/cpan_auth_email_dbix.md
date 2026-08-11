# Dancer Google auth, Email::Fingerprint, and DBIx::PasswordIniFile

## Goal

Make the requested CPAN targets and their system-Perl-compatible dependency
surfaces work through `jcpan`.  Prefer reusable compiler and CPAN tooling fixes
over distribution-specific preferences, and reuse the bundled Bouncy Castle
provider for native crypto primitives.

## Baseline

| Target | System Perl | PerlOnJava baseline |
| --- | --- | --- |
| Dancer::Plugin::Auth::Google 0.08 | 2 files / 18 tests pass | IO::Socket::SSL does not export `SSL_VERIFY_PEER` by default |
| Email::Fingerprint 0.49 | 8 files pass; `t/cache.t` fails because modern Perl excludes `.` from `@INC` | MakeMaker overwrites the application module with `t/eliminate_dups.pl` and omits an explicit `PMLIBDIRS` helper |
| DBIx::PasswordIniFile 2.00 | Configures after Crypt::CBC is installed, but 3 of 6 files abort inside the current native Crypt::Blowfish/Crypt::CBC stack | Metadata fallback is never retried, so `DEFAULT_KEY` and `t/ANSWERS` are not generated; Crypt::Blowfish is native-only |

## Implementation phases

1. Correct MakeMaker library discovery and prevent test files from being
   staged as production modules.
2. Retry an original `Makefile.PL` once after fallback-discovered runtime
   prerequisites have been installed.
3. Restore IO::Socket::SSL's standard default constant exports.
4. Add a BouncyCastle-backed Crypt::Blowfish bridge and verify upstream
   vectors plus Crypt::CBC integration.
5. Make the bundled SDBM compatibility layer honor backing-file access and
   batch writeback, avoiding quadratic cache updates.
6. Re-run the requested targets, full project tests, and orphan checks.

## Progress tracking

### Current status: Complete (2026-08-11)

### Completed phases

- [x] Phase 1: MakeMaker library discovery (2026-08-11)
- [x] Phase 2: configure retry tooling (2026-08-11)
- [x] Phase 3: IO::Socket::SSL exports (2026-08-11)
- [x] Phase 4: BouncyCastle Crypt::Blowfish bridge (2026-08-11)
  - All 21 upstream-compatible vectors pass under system Perl and both
    PerlOnJava backends.
- [x] Phase 5: SDBM access and writeback behavior (2026-08-11)
  - Inaccessible backing files now fail `TIEHASH` and database writes are
    persisted at `sync`/`untie` instead of rewriting the full file per key.
- [x] Phase 6: end-to-end verification (2026-08-11)
  - `Dancer::Plugin::Auth::Google`: 2 files / 18 assertions pass.
  - `Email::Fingerprint`: 9 files / 211 assertions pass; its optional
    Test::Trap application test skips normally.
  - `DBIx::PasswordIniFile`: reaches its configured target suite with
    `DEFAULT_KEY`, test answers, and crypto dependencies present.  Its crypto
    tests abort, as do 3 of 6 target files under current system Perl, so the
    distribution result is outside the compatibility target.
  - Full `make` passes.  New tooling tests were first validated with system
    Perl; all 21 Blowfish assertions pass with system Perl and both
    PerlOnJava backends.

### Next steps

1. No remaining work for the requested compatibility surface.

### Open questions

- DBIx::PasswordIniFile's legacy Crypt::CBC integration is broken with the
  current native Crypt::Blowfish stack under system Perl; revisit only if the
  upstream distribution becomes green there.

## References

- [Module porting guide](../../docs/guides/module-porting.md)
- Skills: `debug-perlonjava`, `port-cpan-module`

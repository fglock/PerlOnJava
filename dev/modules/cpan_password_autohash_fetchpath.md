# Password::OWASP, Hash::AutoHash, and Data::FetchPath compatibility

## Goal

Make the three requested CPAN targets and their system-Perl-compatible
dependencies install and test through `jcpan`. Fix reusable compiler/runtime
semantics rather than patching module tests. Replace required XS crypto
primitives with Java implementations backed by the Bouncy Castle dependency
already shipped by PerlOnJava.

## Baseline

| Distribution | System Perl | PerlOnJava baseline |
| --- | --- | --- |
| Data::Integer 0.007 | 10 files / 6270 tests pass | 8/10 files fail; native-IV integer hints are lost during deferred emission |
| Params::Classify 0.015 | Supported behavior confirmed | 16 failures around `0 but true` and first-class regexp scalars |
| Data::FetchPath 0.02 | 20 tests pass | Dependency installation is blocked before the target test phase |
| Hash::AutoHash 1.17 | Direct run lacks optional test dependencies | Dependency installation must be completed before comparison |
| Password::OWASP | Direct run lacks crypto dependencies | Dependency tree reaches multiple XS-only password primitives |

The initial Password::OWASP dependency run was stopped after 20 minutes while
still resolving build/test prerequisites. The failures above were extracted
from its completed dependency test phases.

## Implementation phases

### Phase 1: Compiler and scalar parity

- Preserve lexical `use integer` on arithmetic, shifts, numeric bitwise
  operations, unary negation/complement, and compound assignments across the
  JVM and interpreter backends.
- Implement PerlOnJava's advertised 32-bit IV/UV wrap and signedness.
- Recognize the special numeric dual string `0 but true` without warnings.
- Preserve Perl's first-class `${qr/.../}` scalar type for `ref` and `reftype`.

### Phase 2: Pure-Perl dependency closure

- Re-run Data::Integer and Params::Classify upstream suites.
- Run Data::FetchPath and Hash::AutoHash through `jcpan`.
- Compare every remaining failure with system Perl; ignore only failures that
  reproduce there.

### Phase 3: Password crypto dependency closure

- Inspect each upstream XS interface before implementing it.
- Back the bcrypt, scrypt, and Argon2 primitives used by Password::OWASP with
  Bouncy Castle (`bcprov`), retaining the required CPAN Perl-level APIs.
- Bundle focused compatibility modules and upstream vectors so `jcpan` does
  not resolve the unrelated legacy XS algorithms in Authen::Passphrase.

### Phase 4: End-to-end verification

- Run each requested `jcpan -t` target with a hard timeout and captured logs.
- Run `make` and check for orphaned PerlOnJava JVMs.

## Progress tracking

### Current status: Complete (2026-08-07)

### Completed phases

- [x] Phase 1: Compiler and scalar parity (2026-08-07)
- [x] Phase 2: Pure-Perl dependency closure (2026-08-07)
- [x] Phase 3: Password crypto dependency closure (2026-08-07)
- [x] Phase 4: End-to-end verification (2026-08-07)

### Work completed (2026-08-07)

- Added system-Perl-validated regression tests for native-word integer
  arithmetic, Params::Classify primitives, indirect `isa`, lexical slot reuse,
  and tied-container cleanup.
- Implemented lexical integer annotations and 32-bit IV/UV arithmetic and
  bitwise paths in both backends. Data::Integer passes 10 files / 6269 tests
  under PerlOnJava (two optional POD checks skipped).
- Implemented `0 but true` numeric recognition and first-class regexp scalar
  tracking.
- Fixed disabled-feature indirect `isa` parsing, same-scope lexical pad-slot
  reuse, and `undef` dispatch for tied arrays and hashes.
- Added a targeted, fixed-point weak-reference cleanup after explicit object
  release. This gives tied wrapper graphs Perl-compatible destruction timing
  without sweeping unrelated nested-call temporaries.
- Added focused `Crypt::Argon2`, `Crypt::Eksblowfish::Bcrypt`, and
  `Authen::Passphrase::{Clear,BlowfishCrypt,Scrypt,Argon2}` compatibility
  modules. Their Java primitives reuse the existing Bouncy Castle dependency;
  no native or new Java dependency was added.
- Verified requested targets:
  - `Data::FetchPath`: 4 files / 19 tests pass.
  - `Hash::AutoHash`: 32 files / 2785 tests pass.
  - `Password::OWASP`: 4 files / 40 tests pass.
- `make test-bundled-modules` passes 370/372 module files, including all three
  new crypto vector files. The two failures are pre-existing XML::Parser tests
  caused by missing `Encode::Locale`; both reproduce on clean master.
- A full `make` was run. The branch exposes failures in
  `weaken_scalar_refs.t` and `regex_charclass.t`; both exact failures reproduce
  with the clean master runtime when invoked directly (master's parallel test
  harness currently does not promote their failing TAP to task failures).

### Next steps

1. No work remains for the three requested CPAN targets.
2. Separately repair the existing test-harness exit-status discrepancy and the
   pre-existing XML::Parser `Encode::Locale` dependency if broader suite
   cleanup is desired.

### Open questions

- Bouncy Castle enforces bcrypt's modern cost floor of 4. The compatibility
  layer accepts legacy costs 0..3 using cost 4 so Password::OWASP retains
  round-trip behavior; it does not promise byte-identical legacy cost hashes.

## References

- [Module porting guide](../../docs/guides/module-porting.md)
- Skills: `debug-perlonjava`, `port-cpan-module`

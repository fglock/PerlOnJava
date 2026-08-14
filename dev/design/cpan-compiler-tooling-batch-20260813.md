# CPAN compiler and tooling compatibility batch (2026-08-13)

## Goal

Make the system-Perl-compatible surfaces of these targets work through
`jcpan`, fixing shared compiler, runtime, and CPAN tooling behavior instead of
adding distribution preferences:

- `Net::FS::Flickr`
- `Marlin::X::Clone`
- `Sys::GetRandom::PP`
- `App::calendr`
- `App::upf`
- `Hades::Realm::OO`
- `Authen::Simple::Kerberos`

Reuse bundled Java providers where native CPAN dependencies need equivalent
primitives.

## Baseline

| Target | Initial PerlOnJava result |
| --- | --- |
| Net::FS::Flickr | dependency resolution reached Imager/Flickr::Upload, then a sandboxed download was blocked |
| Marlin::X::Clone | `Class::XSConstructor` has no Java or pure-Perl XS replacement |
| Sys::GetRandom::PP | rejects both Darwin system Perl and PerlOnJava as unsupported platforms |
| App::calendr | Moo's ithread type map cannot resolve a raw `B::SV` address |
| App::upf | long Data::Sah/Perinci dependency resolution reached a sandboxed download |
| Hades::Realm::OO | Hades compiles a self-recursive `(??{ ... })` pattern |
| Authen::Simple::Kerberos | `Authen::Krb5::Simple` has no Java XS replacement |

The isolated system-Perl comparison passed `Marlin::X::Clone`,
`App::calendr`, `Hades::Realm::OO`, and `Authen::Simple::Kerberos`.
`Net::FS::Flickr` was excluded because its upstream
`Acme::Steganography::Image::Png` dependency fails its own system-Perl tests.
`Sys::GetRandom::PP` was excluded because the distribution rejects Darwin.
The `App::upf` comparison exceeded its 20-minute bounded dependency run and
was kept in the PerlOnJava validation set.

## Progress tracking

### Current status: Complete, reconciled with current master (2026-08-14)

### Completed phases

- [x] Initial `jcpan -t` classification (2026-08-13)
  - Captured full per-target logs with hard process timeouts.
  - Excluded `Sys::GetRandom::PP` because its current distribution rejects
    Darwin under system Perl as well.
- [x] System-Perl differential (2026-08-13)
  - Excluded the two upstream failures described above.
  - Kept `App::upf` in scope after the bounded comparison timed out.
- [x] Shared runtime and CPAN tooling fixes (2026-08-13)
  - Added bounded, process-wide address-to-reference recovery for
    `B::SV::object_2svref`, including addresses exposed through
    `Scalar::Util::refaddr` across compiler/runtime threads.
  - Made `jcpan` load its targeted `Module::Build::Base` compatibility
    overlay ahead of stale site-installed copies.
  - Made explicit target failures produce a failing `jcpan` exit status.
  - Deferred unsupported dynamic-regex diagnostics from `qr//` construction
    to first match, allowing modules to define unused patterns honestly.
  - Added pure-Perl XS overlays for `Class::XSConstructor` and
    `B::Hooks::AtRuntime::OnlyCoreDependencies`.
  - Applied one-shot source filters immediately after explicit `BEGIN` blocks
    and preserved localized at-runtime callback arrays across whole-file
    tokenization.
  - Added a JDK Kerberos backend for `Authen::Krb5::Simple`.
  - Files: `B.pm`, `Module/Build/Base.pm`, `App/Cpan.pm`, `XSLoader.java`,
    `RuntimeRegex.java`, runtime reference types, and new provider overlays.
- [x] Target and dependency validation except `App::upf` (2026-08-13)
  - `Marlin::X::Clone`: 3 files, 8 tests passed.
  - `Class::XSConstructor`: 27 files, 134 tests passed (two optional suites
    skipped for unavailable dependencies).
  - `B::Hooks::AtRuntime::OnlyCoreDependencies`: 3 files, 12 tests passed.
  - `App::calendr`: 2 files, 2 tests passed; its optional
    `Calendar::Bahai` suite was skipped upstream.
  - `Hades::Realm::OO`: 5 files, 40 tests passed (three author-only suites
    skipped upstream).
  - `Authen::Simple::Kerberos`: functional load test passed; two author-only
    POD suites skipped for unavailable test dependencies.
  - Full project `make` passed after the final runtime changes.
- [x] Bounded `App::upf` validation (2026-08-13)
  - A clean run progressed through QuickJS configuration and more than 4,000
    lines of Data::Sah/Perinci dependency resolution without reaching the
    target distribution or exposing a PerlOnJava compiler/runtime failure.
  - The equivalent isolated system-Perl run also did not complete its
    dependency graph within 20 minutes, so no target-test differential was
    available. The PerlOnJava run was stopped after exceeding the intended
    bound while still resolving dependencies.
- [x] Core-suite regression follow-up (2026-08-13)
  - Rebased the branch onto current master and removed the obsolete whole-file
    source-filter pre-pass. Explicit `BEGIN` filters now run only through the
    parser-time path, fixing the double filtering seen in `op/incfilter.t`.
  - Preserved the existing `JPERL_UNIMPLEMENTED=warn` dynamic-regex fallback
    while retaining normal-mode deferred errors for unused `(??{...})`
    patterns. Regex cache entries now distinguish those modes.
  - Replaced the bounded strong B-address registry with weak entries so
    address recovery does not extend Perl value lifetimes.
  - Exact runs against an isolated current-master build matched the repaired
    branch for the remaining reported core-test counts. `op/incfilter.t`
    improved from the regressed 14 tests to 158 passing assertions.
  - Revalidated `Hades::Realm::OO` (40 tests),
    `B::Hooks::AtRuntime::OnlyCoreDependencies` (12 tests), `App::calendr`
    (2 tests), and `Authen::Simple::Kerberos` (functional suite); all passed.
  - Full project `make` passed after the final regression repairs.
- [x] Upstream reconciliation (2026-08-14)
  - Rebuilt PR #949 on current `origin/master`, which now contains the shared
    regex, warning, tied-array, and other core compatibility repairs.
  - Retained only this batch's CPAN compiler, source-filter, runtime-provider,
    and tooling changes; dropped the branch-local core regression commit so
    the upstream implementations remain authoritative.
  - Preserved the source-filter and weak-reference corrections that are part
    of the CPAN objectives.
  - Full `make` passed. The reported core-test set matched an isolated
    `origin/master` build; `re/speed.t` varied only with its process timeout
    and reproduced the upstream 25 passing assertions on a quiet repeat.
  - Revalidated `Marlin::X::Clone` (8 tests), `App::calendr` (2 tests),
    `Hades::Realm::OO` (40 tests), and `Authen::Simple::Kerberos` (1 functional
    test); all passed.

### Next steps

1. Update PR #949 and monitor CI.

### Open questions

- `App::upf` has an unusually large dependency graph; neither system Perl nor
  PerlOnJava reached its target tests in the bounded comparison.

## References

- [Module porting guide](../../docs/guides/module-porting.md)
- [Executable regex callbacks](executable-regex-callbacks.md)
- Skills: `debug-perlonjava`, `port-cpan-module`

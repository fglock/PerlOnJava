# Catalyst Support

## Goal

Run an unmodified Catalyst application on PerlOnJava, fixing CPAN tooling,
compiler, runtime, and general-purpose module compatibility instead of
patching Catalyst.

## Compatibility Strategy

1. Install upstream `Catalyst::Runtime` with `jcpan` and preserve complete logs.
2. Reduce each failure to a standard-Perl-validated regression test.
3. Prefer fixes in the compiler, runtime, CPAN tooling, or general-purpose
   bundled modules.
4. Add Java XS replacements only when an upstream dependency has no usable
   pure-Perl implementation.
5. Verify Catalyst through PSGI using `Plack::Handler::Netty` without `fork`.

## Progress Tracking

### Current Status: Phase 1 in progress

### Completed Work

- [x] Initial dependency probes (2026-08-04)
  - Probed both `Catalyst` and the narrower `Catalyst::Runtime` target.
  - Confirmed that the full target pulls developer tooling including
    `MooseX::Getopt` and `Test::Trap`.
  - Confirmed many dependencies pass unchanged, including URI, Path::Tiny,
    Params::Validate PP, MooseX::Role::Parameterized, and LWP components.
  - Logs: `/tmp/catalyst-install-1.log`, `/tmp/catalyst-install-2.log`, and
    `/tmp/catalyst-runtime-install-1.log`.
- [x] HTTP request-body compatibility (2026-08-04)
  - Added the standard `File::Temp->write($buffer, $length, $offset)` method.
  - Restored the upstream ten-character default temporary filename contract.
  - Added a system-Perl-validated unit regression test.
  - Full PerlOnJava unit suite passes.
  - Upstream HTTP::Body 1.23 passes 13/13 files and 250/250 assertions.
  - HTTP::Body installs normally with `jcpan` after the fixes.

### Current Work

- [ ] Fix inherited method-modifier handling exercised by
  `MooseX::MethodAttributes` Catalyst-style controllers.
  - 20/22 upstream test files pass and 130/131 completed assertions pass.
  - `t/catalyst.t` fails while wrapping an inherited attributed method with
    `Moose::Exception::MethodNameNotFoundInInheritanceHierarchy`.
  - `t/catalyst_role.t` has one method-list count mismatch.

### Next Steps

1. Reduce the MooseX::MethodAttributes inherited modifier failure to a local
   compiler/runtime regression test.
2. Resume installing Catalyst::Runtime after method attributes pass.
3. Boot a minimal Catalyst PSGI application under Plack::Handler::Netty.
4. Fix the Plack dependency stack's POSIX timezone and `tzset` differences.
5. Return to Catalyst developer-tool installation and Test::Trap failures.

### Open Issues

- `Encode::Locale` exposes incorrect tied `%ENV` mutation behavior.
- `AnyDBM_File` is missing from the bundled core-module inventory, causing
  CPAN to suggest installing a full Perl distribution.
- `Test::Trap` exposes non-local labeled-loop and parser failures. It is a
  developer-tool dependency rather than the first runtime blocker.
- Class::C3::Adopt::NEXT has warning-text and warning-disable differences.
- `POSIX::strftime::Compiler` exposes timezone-offset, timezone-name, and
  `POSIX::tzset` compatibility gaps. These affect Plack access-log formatting,
  not Catalyst dispatch itself.
- `MooseX::Getopt` has failures concentrated in help/usage output and trapped
  exit behavior; it was force-installed only to continue dependency discovery.

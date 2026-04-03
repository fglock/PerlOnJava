# LWP::UserAgent Support for PerlOnJava

## Status: In Progress

**Branch**: `fix/lwp-useragent-support`
**Date started**: 2026-04-03

## Background

LWP::UserAgent (libwww-perl) is a top-20 CPAN module providing the standard HTTP
client library for Perl. It was previously blocked on HTTP::Message, which has since
been fixed. Running `./jcpan -j 8 -t LWP::UserAgent` now installs and partially
works, but several issues prevent full test coverage.

## Current State

Running all 22 test files (with the TESTS pattern from Makefile.PL):
- **122 tests across 22 files**
- **119/122 subtests pass** (97.5%)
- **14/22 test programs pass** (8 fail, mostly due to missing modules or PerlOnJava limitations)

### Test Results Breakdown

| Test File | Result | Tests | Notes |
|-----------|--------|-------|-------|
| t/00-report-prereqs.t | PASS | 1/1 | |
| t/10-attrs.t | PASS | 9/9 | 6 "uninitialized value" warnings (cosmetic) |
| t/base/default_content_type.t | PASS | 2/2 | |
| t/base/protocols.t | PASS | 1/1 | |
| t/base/protocols/nntp.t | SKIP | 0/0 | nntp.perl.org unstable |
| t/base/proxy.t | **FAIL** | 3/8 | `Unknown encoding: locale` in Encode |
| t/base/proxy_request.t | PASS | 16/16 | |
| t/base/simple.t | PASS | 3/3 | |
| t/base/ua.t | **FAIL** | 37/39 | 2 header tests + Encode locale error |
| t/base/ua_handlers.t | PASS | 19/19 | |
| t/leak/no_leak.t | **FAIL** | 0/0 | Test::LeakTrace is XS-only |
| t/local/autoload-get.t | PASS | 3/3 | |
| t/local/autoload.t | PASS | 5/5 | |
| t/local/cookie_jar.t | PASS | 9/9 | |
| t/local/download_to_fh.t | **FAIL** | 0/0 | `printflush` method missing on File::Temp |
| t/local/get.t | PASS | 4/4 | |
| t/local/http.t | **FAIL** | 0/0 | IO::Socket::IP missing |
| t/local/httpsub.t | PASS | 4/4 | |
| t/local/protosub.t | **FAIL** | 6/7 | sn.no content test fails |
| t/redirect.t | SKIP | 0/0 | No socket available |
| t/robot/ua-get.t | **FAIL** | 0/0 | IO::Socket::IP missing |
| t/robot/ua.t | **FAIL** | 0/0 | IO::Socket::IP missing |

## Issues Found

### P0: MakeMaker ignores TESTS parameter (only 3 tests run via jcpan)

**Impact**: Only 3 of 22 test files are executed by `jcpan -t`
**Root cause**: `ExtUtils/MakeMaker.pm` line 406 hardcodes `t/*.t` in the generated
Makefile test target, ignoring the `test => {TESTS => "..."}` parameter from WriteMakefile.
**Fix**: Read `$args->{test}{TESTS}` and use it in the generated Makefile.

### P1: `exists(&constant_sub)` fails after constant inlining

**Impact**: IO::Socket, Net::FTP, and all modules depending on them fail to load
**Root cause**: When a subroutine is defined via `use constant` (e.g., `Errno::EINVAL`),
PerlOnJava's compiler inlines the constant value. `exists(&Errno::EINVAL)` then sees a
constant value node instead of an IdentifierNode, and falls through to the "Not implemented"
error in `EmitOperatorDeleteExists.java` line 166.
**Reproduction**:
```perl
package Foo; use constant BAR => 42;
package main; exists(&Foo::BAR);  # ERROR
```
**Fix**: In the exists/defined handler, detect when the `&Name` operand has been
constant-folded and convert it back to a subroutine existence check using the original name.

### P2: "Unknown encoding: locale" in Encode (lower priority)

**Impact**: t/base/proxy.t and t/base/ua.t fail
**Root cause**: `Encode.java` doesn't handle the "locale" encoding name.
LWP::UserAgent calls `Encode::decode('locale', ...)` at line 1193.
**Fix**: Map "locale" to the system's default charset in Encode.java.

### P3: IO::Socket::IP missing (lower priority)

**Impact**: t/local/http.t, t/robot/ua-get.t, t/robot/ua.t fail to compile
**Root cause**: IO::Socket::IP is not bundled or installed. Tests `use` it directly.
**Fix**: Either install IO::Socket::IP via jcpan or provide a minimal stub that
delegates to IO::Socket::INET.

### Cosmetic: "Use of uninitialized value in join or string"

**Impact**: 6 warnings during t/10-attrs.t (tests still pass)
**Root cause**: LWP::UserAgent::credentials() joins undef values when testing
with undef netloc/realm/username/password. Expected Perl behavior.
**Fix**: Not required; this matches standard Perl warning behavior.

### Other failures (not blocking)

| Issue | Test | Notes |
|-------|------|-------|
| Test::LeakTrace XS | t/leak/no_leak.t | XS module, cannot be supported |
| printflush missing | t/local/download_to_fh.t | File::Temp->printflush not implemented |
| protosub content | t/local/protosub.t | Custom protocol handler returns empty |

## Dependency Status

All runtime dependencies are available (bundled or CPAN-installed):

| Module | Version | Source |
|--------|---------|--------|
| IO::Socket | 1.56 | bundled (sync.pl) |
| Net::FTP | 3.15 | bundled (sync.pl) |
| Net::HTTP | 6.24 | CPAN-installed |
| HTTP::Message | 7.01 | CPAN-installed |
| URI | 5.34 | CPAN-installed |
| Try::Tiny | 0.32 | CPAN-installed |
| (all others) | OK | See prereqs report |

**Note**: sync.pl does NOT need changes. IO::Socket and Net::FTP are already
imported from perl5. The "missing dependencies" warning from jcpan is a false
positive caused by P1 (`exists(&Errno::EINVAL)` failing at load time).

## Plan

### Phase 1: Infrastructure fixes (this PR)

- [x] Investigation complete
- [ ] **P0**: Fix MakeMaker.pm to use TESTS parameter in generated Makefile
- [ ] **P1**: Fix `exists(&constant_sub)` in EmitOperatorDeleteExists.java
- [ ] Run `make` to verify no regressions
- [ ] Re-run `./jcpan -j 8 -t LWP::UserAgent` and verify improvement

### Phase 2: Polish (future PR)

- [ ] P2: Handle "locale" encoding in Encode.java
- [ ] P3: Provide IO::Socket::IP stub or install
- [ ] Update smoke test status in `dev/tools/cpan_smoke_test.pl`

## Files Changed

| File | Change |
|------|--------|
| `src/main/perl/lib/ExtUtils/MakeMaker.pm` | Use TESTS param in test target |
| `src/main/java/org/perlonjava/backend/jvm/EmitOperatorDeleteExists.java` | Handle exists for constant subs |

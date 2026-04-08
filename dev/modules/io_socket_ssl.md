# IO::Socket::SSL Test Bundling for PerlOnJava

## Overview

IO::Socket::SSL 2.098 is the standard Perl module for TLS/SSL connections.
PerlOnJava already bundles `IO::Socket::SSL` and its dependency `Net::SSLeay`
(Java-implemented via `NetSSLeay.java`). This document tracks the work to
bundle upstream CPAN tests into `src/test/resources/module/IO-Socket-SSL/`.

**Branch:** `feature/lwp-protocol-https`

## Test Suite Analysis

The upstream test suite has **45 test files**. Most (36) use a fork-based
server/client pattern via `testlib.pl`, which is incompatible with PerlOnJava
(no fork support). The remaining tests are pure-logic and can run in-process.

### Test Categories

| Category | Count | Status |
|----------|-------|--------|
| Pure-logic, already passing via jcpan | 3 | `public_suffix_lib_*.t` |
| Pure-logic, should work | 2 | `01loadmodule.t`, `verify_hostname_standalone.t` |
| Pure-logic, blocked by testlib.pl gate only | 1 | `session_cache.t` (never uses fork) |
| Fork-based (won't work) | 36 | All testlib.pl-loading + `psk.t` |
| External network required | 2 | `external/ocsp.t`, `external/usable_ca.t` |
| **Bundleable total** | **6** | |

### Why Most Tests Fail

`testlib.pl` (lines 14-20) checks `$Config{d_fork}` and exits with
`1..0 # Skipped` if fork is unavailable. Every test that loads testlib.pl
also calls `fork_sub()` to spawn a TLS server, then connects to it from
the main process. PerlOnJava cannot support this pattern.

## Phase 1: Bundle Pure-Logic Tests

### Files to copy

```
src/test/resources/module/IO-Socket-SSL/
├── t/
│   ├── 01loadmodule.t
│   ├── verify_hostname_standalone.t
│   ├── public_suffix_lib.pl
│   ├── public_suffix_lib_encode_idn.t
│   ├── public_suffix_lib_libidn.t
│   └── public_suffix_lib_uri.t
```

### Test Details

#### 01loadmodule.t (22 lines)
- Loads IO::Socket::SSL, checks `$IO::Socket::SSL::VERSION`
- Checks debug level import (`IO::Socket::SSL 'debug0'`)
- Calls `Net::SSLeay::OPENSSL_VERSION_NUMBER()` and `SSLeay_version()`
- **Risk:** Test 3 checks `can_client_sni()` — may need Net::SSLeay SNI support
- Currently fails 1/3 subtests via jcpan — needs investigation

#### verify_hostname_standalone.t (190 lines)
- Creates in-memory certificates via `IO::Socket::SSL::Utils::CERT_create()`
- Tests `verify_hostname_of_cert()` with ~80 test cases from Chromium x509 suite
- Pure logic, no fork/sockets, no testlib.pl
- **High-value test** — covers hostname verification edge cases
- **Risk:** Requires `IO::Socket::SSL::Utils` which may use Net::SSLeay cert functions

#### public_suffix_lib_*.t (3 files)
- Test `IO::Socket::SSL::PublicSuffix` domain matching
- Already pass via `./jcpan -t IO::Socket::SSL`
- Pure string logic, no dependencies beyond IO::Socket::SSL

#### session_cache.t (81 lines)
- Tests internal session cache data structure (add/get/del, room counting)
- Loads testlib.pl but never calls any fork/socket function from it
- Defines its own `ok`/`diag` subs
- **Cannot bundle as-is** because testlib.pl will skip on missing fork
- **Potential fix:** Provide a minimal testlib.pl shim that skips the fork check
  OR note this test for future when PerlOnJava sets `$Config{d_fork}` appropriately

## Phase 2: Fix Failures in Bundleable Tests

### 01loadmodule.t — Test 3 failure

Test 3 checks `IO::Socket::SSL->can_client_sni()`. This returns true if
`Net::SSLeay::OPENSSL_VERSION_NUMBER() >= 0x01000000`. Our NetSSLeay.java
returns this, so it should pass. Need to investigate the actual failure.

### verify_hostname_standalone.t — Unknown status

Need to run this test and fix any issues. It exercises:
- `IO::Socket::SSL::Utils::CERT_create()` — creates self-signed certs
- `IO::Socket::SSL::verify_hostname_of_cert()` — hostname matching logic
- Various SAN types (DNS, IP, wildcard patterns)

## Phase 3: Future Work (Not Planned)

### Fork-based tests (36 tests)
These require fundamental fork/multi-process support. Not achievable in
PerlOnJava's current architecture. Would need either:
- Java thread-based server/client simulation
- A mock `testlib.pl` that uses threads instead of fork
- External test runner (system perl) that forks and connects to jperl

### External network tests (2 tests)
Require real internet access to verify OCSP and CA store functionality.
Not suitable for CI.

---

## Progress Tracking

### Current Status: Phase 1 in progress

### Completed
- [x] Net::SSLeay bundled tests: 92/92 passing (2026-04-08)
- [x] File path resolution fix in NetSSLeay.java (RuntimeIO.resolvePath)
- [x] NetSSLeay.resetState() for test isolation
- [x] ModuleTestExecutionTest.java PerlExitException + FindBin fixes

### Next Steps
1. Copy pure-logic test files to `src/test/resources/module/IO-Socket-SSL/t/`
2. Run `make test-bundled-modules` and investigate failures
3. Fix any Net::SSLeay or IO::Socket::SSL issues found
4. Commit and push

### Not Planned
- Fork-based tests (36 tests) — requires fork support
- External network tests (2 tests) — requires internet access
- session_cache.t — blocked by testlib.pl fork gate

---

## Related Documents

- `dev/modules/lwp_protocol_https.md` — LWP::Protocol::https support
- `dev/modules/net_smtp.md` — Net::SMTP (also uses Net::SSLeay)
- `.cognition/skills/port-cpan-module/SKILL.md` — Module porting guidelines

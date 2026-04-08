# LWP::Protocol::https Support for PerlOnJava

## Status: Phase 2 complete, Net::SSLeay 99.2% pass, async framework analysis done

**Branch**: `feature/lwp-protocol-https`
**PR**: #461
**Date started**: 2026-04-08
**Last updated**: 2026-04-08

## Background

`LWP::Protocol::https` is the plug-in that enables `https://` URLs in
LWP::UserAgent.  The implementation uses a **Java-backed IO::Socket::SSL**
(`javax.net.ssl`) instead of the traditional OpenSSL/Net::SSLeay path.

### Dependency Chain

```
LWP::Protocol::https
  ├── IO::Socket::SSL  (>= 1.970)  ← Java-backed (bundled)
  │     └── Net::SSLeay             ← stub (constants + AUTOLOAD only)
  ├── Net::HTTPS  (>= 6)           ← pure Perl, installed via CPAN
  └── LWP::UserAgent               ← already working (317/317 subtests)
```

### Architecture

```
┌──────────────────────────────────────────────────┐
│  LWP::Protocol::https  /  Net::HTTPS  (pure Perl)│
├──────────────────────────────────────────────────┤
│  IO::Socket::SSL.pm  (Perl, calls Java XS)       │
│  ↳ inherits IO::Socket::IP for TCP               │
│  ↳ delegates SSL to Java via _start_ssl() etc.   │
├──────────────────────────────────────────────────┤
│  IOSocketSSL.java   (Java XS backend)             │
│  Uses: javax.net.ssl.SSLContext                    │
│        javax.net.ssl.SSLSocket                     │
│        javax.net.ssl.TrustManagerFactory           │
│        javax.net.ssl.SSLParameters                 │
│        java.security.KeyStore                      │
│        java.security.cert.X509Certificate          │
├──────────────────────────────────────────────────┤
│  SocketIO.java  (existing TCP socket layer)        │
└──────────────────────────────────────────────────┘
```

**Key design decision**: Our bundled `IO::Socket::SSL.pm` does **not call any
`Net::SSLeay::` functions**.  All SSL operations are handled directly in Java.
`Net::SSLeay` is only needed as a stub so that version/dependency checks pass
and constants are available for code that probes `defined &Net::SSLeay::FOO`.

## Current Test Results

### Net::SSLeay 1.96 — 1035/1043 subtests pass (99.2%)

```
Files=48, Tests=1043
Failed 17/48 test programs. 8/1043 subtests failed.
```

Key tests all pass: `03_use`, `04_basic`, `20_functions`, `21_constants`.

The 8 subtest failures are all in tests requiring real OpenSSL C bindings:
- **31_rsa_generate_key.t** (6 failures): RSA key generation — needs native OpenSSL
- **50_digest.t** (2 failures): EVP digest init, digest list — needs native OpenSSL

The 17 program failures are tests that bail out before running because they
need the full OpenSSL C API (CTX_new, RSA, X509, BIO, RAND, etc.) or fork.
They run 0/N subtests.

### IO::Socket::SSL 2.098 — Most tests fork-blocked

33 of 37 tests require `fork()` (for server/client pairs) and will always skip
on PerlOnJava.  The remaining tests:
- `01loadmodule.t` — should work (just loads module, checks version)
- `public_suffix_*.t` (3 tests) — need `IO::Socket::SSL::PublicSuffix`
- `external/ocsp.t` — correctly skips (`can_ocsp` returns 0)

### LWP::Protocol::https 6.15

| Test | Status | Notes |
|------|--------|-------|
| `00-report-prereqs.t` | Should pass | Just reports module versions |
| `diag.t` | Partial | Fails on `IO::Socket::SSL::Utils` import |
| `example.t` | **KEY TARGET** | HTTPS GET to httpbin.org; needs `Test::RequiresInternet` |
| `https_proxy.t` | Cannot pass | Requires fork + IO::Socket::SSL::Utils |

### Manual verification (working):

```bash
./jperl -e 'use LWP::UserAgent;
  my $r = LWP::UserAgent->new(ssl_opts => {verify_hostname => 0})
    ->get("https://httpbin.org/get");
  print $r->status_line, "\n"'
# → 200 OK

./jperl -e 'use LWP::UserAgent;
  my $r = LWP::UserAgent->new(ssl_opts => {verify_hostname => 1})
    ->get("https://www.google.com/");
  print $r->status_line, "\n"'
# → 200 OK
```

## Impact Analysis: Net::SSLeay Test Failures

### Why 22 test programs "fail" but it doesn't matter

Our bundled IO::Socket::SSL **bypasses Net::SSLeay entirely**.  It calls Java
XS methods (`_start_ssl`, `_get_cipher`, `_peer_certificate`, etc.) instead of
`Net::SSLeay::CTX_new`, `Net::SSLeay::connect`, etc.

The 22 failing test programs test the **OpenSSL C API** exposed through
Net::SSLeay's XS bindings (CTX creation, RSA key generation, X509 parsing,
BIO buffers, etc.).  These are functions our stub intentionally does not
implement because they're not on the code path.

### Test-by-test breakdown

#### Tests that bail out (0 subtests run) — inherently require OpenSSL C API

| Test | OpenSSL API | Impact on LWP | Fixable? |
|------|-------------|---------------|----------|
| `03_use.t` | Version/info functions | None — all version funcs work | **Fixed** — passes |
| `04_basic.t` | `ERR_load_crypto_strings()` | None | **Fixed** — passes |
| `05_passwd_cb.t` | `CTX_new`, `CTX_set_default_passwd_cb` | Only for mTLS (client certs) | No — needs full CTX lifecycle |
| `09_ctx_new.t` | `CTX_new`, `SSL_new`, protocol methods | None — our SSL.pm uses Java | No — needs CTX/SSL objects |
| `10_rand.t` | `RAND_bytes`, `RAND_status`, etc. | None | Possible via `SecureRandom` |
| `15_bio.t` | `BIO_new`, `BIO_s_mem`, `BIO_read/write` | None | Possible via byte arrays |
| `30_error.t` | `ERR_put_error`, `ERR_get_error` | None | Possible — thread-local queue |
| `31_rsa_generate_key.t` | `RSA_generate_key` | None | Possible via `KeyPairGenerator` |
| `32_x509_get_cert_info.t` | X509 parsing (subject, issuer, SAN) | None — our Java does this | Possible via `X509Certificate` |
| `33_x509_create_cert.t` | X509 cert creation, signing | None | **Hard** — needs Bouncy Castle |
| `34_x509_crl.t` | CRL parsing and creation | None | Partial — can parse, not create |
| `35_ephemeral.t` | `CTX_set_tmp_rsa` (deprecated) | None | No — deprecated API |
| `36_verify.t` | X509_VERIFY_PARAM, cert chain verify | None — Java handles verify | Needs fork for server |
| `37_asn1_time.t` | ASN1_TIME manipulation | None | Possible via `java.time` |
| `38_priv-key.t` | `PEM_read_bio_PrivateKey` | Only for client certs | Possible via `KeyFactory` |
| `39_pkcs12.t` | `P_PKCS12_load_file` | Only for client certs | Possible via `KeyStore("PKCS12")` |
| `40_npn_support.t` | NPN (deprecated) | None — NPN is dead | No — not in Java |
| `41_alpn_support.t` | ALPN negotiation | None — our Java does ALPN | Needs fork for server |
| `50_digest.t` | EVP digest functions, HMAC | None | Possible via `MessageDigest` |
| `66_curves.t` | EC curve selection | None — JVM auto-negotiates | Partial |
| `67_sigalgs.t` | Signature algorithm config | None | Partial |

#### The 8 remaining subtest failures — require real OpenSSL

| Test | Subtests | Issue |
|------|----------|-------|
| `31_rsa_generate_key.t` #1-6 | RSA key generation + callbacks | Needs native `RSA_generate_key` |
| `50_digest.t` #1-2 | EVP digest init, digest list | Needs native `EVP_MD_CTX_new` |

### What could realistically be implemented

Three tiers of effort:

**Tier 1 — Quick wins (DONE — 1035/1043 subtests pass)**
- ~~Add `ERR_load_crypto_strings` no-op~~ ✅
- ~~Add all ~770 constant names to `@EXPORT_OK`~~ ✅
- Added version/info functions: `OpenSSL_version`, `OpenSSL_version_num`,
  `OPENSSL_version_major/minor/patch`, `OPENSSL_version_pre_release/build_metadata`,
  `OPENSSL_info` (with all OPENSSL_INFO_* type constants)
- Added SSLeay_version type constants (`SSLEAY_VERSION`, `SSLEAY_CFLAGS`, etc.)
- Fixed `die_now`/`die_if_ssl_error` stubs to match real Net::SSLeay behavior
- *Remaining 8 failures require native OpenSSL — cannot fix with stubs.*

**Tier 2 — Useful Net::SSLeay functions (enables broader ecosystem)**
These functions have direct Java equivalents and could be useful to modules
beyond IO::Socket::SSL (e.g., if someone uses Net::SSLeay directly):
- `RAND_bytes` / `RAND_status` → `SecureRandom`
- `MD5` / `SHA1` / `SHA256` / `EVP_get_digestbyname` → `MessageDigest`
- `P_PKCS12_load_file` → `KeyStore.getInstance("PKCS12")`
- `PEM_read_bio_X509` / `X509_get_subject_name` / `X509_NAME_oneline` → `X509Certificate`
- `BIO_new` / `BIO_s_mem` / `BIO_read` / `BIO_write` → `ByteArrayOutputStream`
- Error queue (`ERR_put_error`, `ERR_get_error`, `ERR_error_string`)
- *Effort: 2-3 days.  Would pass ~5 more test programs (10_rand, 15_bio,
  30_error, 39_pkcs12, 50_digest).*

**Tier 3 — Full OpenSSL object model (diminishing returns)**
These require implementing the CTX/SSL/X509 object lifecycle, which is complex
and provides no benefit since our IO::Socket::SSL uses Java directly:
- `CTX_new` / `SSL_new` / `set_fd` / `connect` / `read` / `write` / `free`
- `X509_new` / `X509_set_pubkey` / `X509_sign` (cert creation)
- `X509_VERIFY_PARAM_*` (verify parameter tuning)
- Plus most tests need `fork()` for server/client pairs
- *Effort: 2-3 weeks.  Would still fail tests that need fork.*

**Recommendation**: Tier 1 is worth doing. Tier 2 is nice-to-have for
ecosystem breadth. Tier 3 is not justified — the effort/reward ratio is poor
and fork-dependent tests would still fail.

## IO::Socket::SSL Test Outlook

### Fork is the real blocker, not Net::SSLeay

33 of 37 IO::Socket::SSL tests create a local SSL server via `fork()`, then
connect to it as a client.  PerlOnJava doesn't support `fork()`, and our
IO::Socket::SSL is client-only.  These tests are **fundamentally incompatible**.

The `testlib.pl` helper checks for fork at load time and calls `skip_all` when
fork is unavailable, so these tests skip cleanly rather than failing.

### Tests that could work

| Test | What's needed | Effort |
|------|--------------|--------|
| `01loadmodule.t` | Need `:debug1` import tag support | Small |
| `external/ocsp.t` | Already skips correctly (`can_ocsp` = 0) | None |
| `public_suffix_*.t` | Ship `IO::Socket::SSL::PublicSuffix` (pure Perl) | Small |

### Tests that cannot work

| Category | Count | Reason |
|----------|-------|--------|
| Require fork (server/client) | 29 | No fork in PerlOnJava |
| Require IO::Socket::SSL::Utils | 5 | Cert generation needs OpenSSL |
| Require SSL_Context internals | 1 | Our impl has different internals |
| Require Net::SSLeay X509 funcs | 2 | Not implemented |

## LWP::Protocol::https Test Outlook

| Test | Prognosis | Blocker |
|------|-----------|---------|
| `00-report-prereqs.t` | **Should pass** | None |
| `diag.t` | **Partial** | `IO::Socket::SSL::Utils` import fails |
| `example.t` | **Should pass** | Needs `Test::RequiresInternet` + network |
| `https_proxy.t` | **Cannot pass** | Requires fork + SSL::Utils |

The `example.t` test is the key validation — it performs a real HTTPS GET and
checks SSL response headers.  Our implementation should handle this since
`LWP::UserAgent->get("https://...")` is already verified working.

## Files Created / Modified

### New Files
| File | Purpose |
|------|---------|
| `src/main/java/org/perlonjava/runtime/perlmodule/NetSSLeay.java` | Net::SSLeay Java stub (~80 constants, version/info API, AUTOLOAD-compatible constant()) |
| `src/main/java/org/perlonjava/runtime/perlmodule/IOSocketSSL.java` | IO::Socket::SSL Java backend (javax.net.ssl) |
| `src/main/perl/lib/Net/SSLeay.pm` | Bundled Net::SSLeay Perl stub (768+ @EXPORT_OK, AUTOLOAD, 25 utility stubs) |
| `src/main/perl/lib/IO/Socket/SSL.pm` | Bundled IO::Socket::SSL (inherits IO::Socket::IP, delegates to Java) |

### Modified Files
| File | Change |
|------|--------|
| `src/main/java/org/perlonjava/runtime/perlmodule/Socket.java` | Added MSG_PEEK, MSG_OOB, SO_RCVBUF/SNDBUF exports, CR/LF/CRLF |
| `src/main/perl/lib/Socket.pm` | Added new constants to @EXPORT |
| `src/main/java/org/perlonjava/runtime/io/SocketIO.java` | Added `replaceSocket()`, `getSocket()` for SSL wrapping |
| `src/main/java/org/perlonjava/runtime/operators/IOOperator.java` | Fixed select() for SSL sockets (null NIO channel → always-ready) |

## Progress Tracking

### Current Status: Phase 2 complete, HTTPS client working

### Completed Phases
- [x] Phase 0: Investigation (2026-04-08)
  - Ran `./jcpan -t LWP::Protocol::https`, captured full error output
  - Identified Net::SSLeay AUTOLOAD infinite recursion root cause
  - Analyzed IO::Socket::SSL's Net::SSLeay surface area (~127 functions)
  - Catalogued missing Socket constants (MSG_PEEK, MSG_OOB, etc.)
  - Designed Java-backed IO::Socket::SSL architecture
  - Created this plan document

- [x] Phase 1: Socket constants + Net::SSLeay stub (2026-04-08)
  - Added MSG_PEEK, MSG_OOB, MSG_DONTROUTE, MSG_DONTWAIT to Socket.java/Socket.pm
  - Exported SO_RCVBUF, SO_SNDBUF (were defined but not registered)
  - Added CR, LF, CRLF string constants for :crlf tag
  - Created NetSSLeay.java with ~45 constants, no-op init functions,
    working constant() lookup (prevents AUTOLOAD infinite recursion)
  - Created bundled Net/SSLeay.pm Perl stub
  - Verified: `use Net::SSLeay; Net::SSLeay::VERIFY_PEER()` works
  - All unit tests pass

- [x] Phase 2: Java IO::Socket::SSL Core Implementation (2026-04-08)
  - Created `IOSocketSSL.java` — Java XS backend with _start_ssl, _get_cipher,
    _get_sslversion, _peer_certificate_*, _stop_ssl, _is_ssl, capability queries
  - Created `IO/Socket/SSL.pm` — Perl module inheriting IO::Socket::IP
    with configure(), connect_SSL(), start_SSL(), get_cipher(), etc.
  - Added `replaceSocket(Socket)` to SocketIO.java for SSL socket swapping
  - Fixed select() in IOOperator.java for SSL sockets (null NIO channel):
    SSL sockets now report always-ready for reads/writes since NIO selector
    can't monitor SSLSocket streams
  - Worked around PerlOnJava Timeout bug: IO::Socket::IP non-blocking connect
    with io_socket_timeout fails, so SSL.pm clears the timeout field
  - SNI hostname correctly resolved from PeerAddr (not just peerhost IP)
  - Files: IOSocketSSL.java, SocketIO.java, IO/Socket/SSL.pm, IOOperator.java
  - **Verified**: `LWP::UserAgent->new->get("https://www.google.com/")` returns 200
  - **Verified**: Certificate verification (both enabled and disabled)
  - **Verified**: SSL headers (cipher, cert subject/issuer) populated correctly
  - All unit tests pass

- [x] Net::SSLeay AUTOLOAD compatibility (2026-04-08)
  - Java constant() now returns ENOENT for uppercase names (OpenSSL macros)
    and EINVAL for other names, matching real XS behavior
  - Perl AUTOLOAD mirrors real Net::SSLeay: EINVAL → AutoLoader, other → croak
    "Your vendor has not defined SSLeay macro ..."
  - Added 25 pure Perl utility function stubs (make_form, make_headers,
    get_https, post_https, sslcat, tcpcat, etc.)
  - Net::SSLeay test results: 725/807 → 2/807 subtests failed (99.8% pass)

- [x] Net::SSLeay Tier 1 — version/info API + full constant coverage (2026-04-08)
  - Added version/init functions: `ERR_load_crypto_strings`, `hello`,
    `OpenSSL_version`, `OpenSSL_version_num`, `OPENSSL_version_major/minor/patch`,
    `OPENSSL_version_pre_release/build_metadata`, `OPENSSL_info`
  - Added SSLeay_version type constants (SSLEAY_VERSION, SSLEAY_CFLAGS, etc.)
  - Added OPENSSL_* version type constants and all 8 OPENSSL_INFO_* constants
  - Expanded SSLeay_version() switch to handle type arguments 0-10
  - Expanded OPENSSL_info() switch to handle info type constants 1001-1008
  - Added all 768 OpenSSL constant names to @EXPORT_OK in Net/SSLeay.pm
  - Fixed die_now/die_if_ssl_error stubs to match real Net::SSLeay behavior
  - Net::SSLeay test results: 2/807 → 8/1043 (99.2% pass, more tests now run)
  - Files: NetSSLeay.java, Net/SSLeay.pm

### Next Steps

1. **Run `./jcpan -t LWP::Protocol::https`** to see current results
   - `example.t` is the key target — validates the full HTTPS client stack

2. **Fix Client-SSL-Version header** (returned as undef)

3. **IO::Poll implementation** — needed for async frameworks (see below)

4. **Consider Tier 2 improvements** if broader Net::SSLeay ecosystem
   compatibility is needed (RAND, digest, PKCS12, BIO, error functions)

## Async Framework Analysis

### Overview

Analysis of what's needed to support Perl async frameworks (POE, AnyEvent, Mojo)
on PerlOnJava. The key cross-cutting blockers are ranked by impact:

| # | Blocker | Impact | Status |
|---|---------|--------|--------|
| 1 | `DESTROY` | Object cleanup, resource management | **Separate PR** (feature/defer-blocks) |
| 2 | `IO::Poll` | Event loop polling primitive | **Not yet implemented** |
| 3 | SSLEngine migration | Non-blocking SSL I/O | Needs IO::Poll first |
| 4 | `weaken` / `isweak` | Circular reference prevention | No-op by design (JVM GC) |

### DESTROY is in a Separate PR

Object destructors (`DESTROY`) are being implemented in the `feature/defer-blocks`
branch as part of a broader effort. This document focuses on features that
**don't depend on DESTROY**: IO::Poll, SSLEngine migration, and non-blocking I/O.

### IO::Poll — Implementation Plan

IO::Poll is the core polling primitive used by both Mojo::Reactor::Poll and
POE::Loop::IO_Poll. Both call the XS function `IO::Poll::_poll()` directly
(bypassing the OO API) for performance.

**Architecture**: Java backend class `IOPoll.java` + copy of CPAN IO::Poll.pm

**Key insight**: The OO methods (new, mask, poll, events, remove, handles) are
already pure Perl in CPAN's IO::Poll.pm. Only `_poll()` is XS and needs a
Java implementation.

#### `_poll()` function signature

```
IO::Poll::_poll($timeout_ms, @fd_mask_pairs)
```

- Takes timeout in milliseconds, flat list of `($fd, $requested_events, ...)`
- **Modifies @fd_mask_pairs in-place**: replaces event values with revents
- Returns count of ready fds

#### POLL constants

| Constant | Value | Java NIO Mapping |
|----------|-------|------------------|
| POLLIN | 0x0001 | SelectionKey.OP_READ |
| POLLPRI | 0x0002 | (no direct mapping) |
| POLLOUT | 0x0004 | SelectionKey.OP_WRITE |
| POLLERR | 0x0008 | (implicit in NIO) |
| POLLHUP | 0x0010 | (implicit in NIO) |
| POLLNVAL | 0x0020 | (no channel → NVAL) |
| POLLRDNORM | 0x0040 | SelectionKey.OP_READ |
| POLLWRNORM | POLLOUT | SelectionKey.OP_WRITE |
| POLLRDBAND | 0x0080 | (no direct mapping) |
| POLLWRBAND | 0x0100 | (no direct mapping) |

#### Implementation approach

The `_poll` Java method should reuse the NIO Selector infrastructure from
`IOOperator.selectWithNIO()` (lines 102-357), which already handles:
- Socket handles → NIO `Selector`
- Non-socket handles → `FileDescriptorTable.isReadReady()`/`isWriteReady()`
- SSL sockets → `InputStream.available()` fallback
- ServerSocket accept readiness → `SelectionKey.OP_ACCEPT`

#### Files to create

| File | Action | Notes |
|------|--------|-------|
| `src/main/java/.../perlmodule/IOPoll.java` | **CREATE** | Java XS backend — constants + `_poll()` |
| `src/main/perl/lib/IO/Poll.pm` | **CREATE** | Copy from CPAN IO-1.55 (pure Perl OO wrapper) |

### SSL I/O Architecture — SSLEngine Migration

**Current problem**: Our `SSLSocket` approach nullifies NIO `SocketChannel`.
`select()` always reports SSL sockets as ready (busy-loop). This is fine for
blocking I/O (LWP) but breaks event-driven frameworks.

**Solution**: Migrate from `SSLSocket` to `SSLEngine`, which works with NIO
`SocketChannel` and allows proper `Selector` integration.

**Complexity**: High. Requires a state machine in SocketIO.java for:
- Handshake (NEED_WRAP / NEED_UNWRAP / NEED_TASK)
- Application data buffering
- Renegotiation handling
- Shutdown protocol

**Dependency**: Should be done after IO::Poll, since IO::Poll is needed to
test the non-blocking SSL behavior.

### Framework-Specific Assessment

#### POE — ~70% working

POE's core is pure Perl. Main gaps:
- `POE::Loop::IO_Poll` needs `IO::Poll::_poll()` (see above)
- `POE::Loop::Select` should mostly work (uses Perl `select()`)
- `POE::Wheel::SocketFactory` needs non-blocking connect (mostly works)
- `POE::Component::SSLify` needs `Net::SSLeay` CTX functions (won't work)
- `DESTROY` needed for proper session cleanup

#### AnyEvent::Loop — Basics should work

- Uses `select()` internally — should work for basic timers and I/O
- SSL needs `AnyEvent::TLS` which wraps `Net::SSLeay` (won't work)
- `DESTROY` needed for watcher cleanup

#### Mojo::Reactor — Hardest

- `Mojo::Reactor::Poll` calls `IO::Poll::_poll()` directly (needs IO::Poll)
- `Mojo::IOLoop` uses non-blocking I/O extensively
- `Mojo::IOLoop::TLS` wraps `IO::Socket::SSL` in non-blocking mode
  (needs SSLEngine migration)
- `DESTROY` needed throughout for cleanup

### Resolved Questions

- ~~Should IO::Socket::SSL::Utils be implemented?~~
  **No** — it's only needed by tests that also require fork. Not on any
  production code path.
- ~~Should we support non-blocking SSL (SSLEngine)?~~
  **Defer** — LWP uses blocking I/O. SSLEngine would only matter for async
  frameworks (POE, AnyEvent, Mojo) which are Phase 4.
- ~~Do we need full Net::SSLeay API?~~
  **No** — our bundled IO::Socket::SSL calls zero Net::SSLeay functions.
  The stub only needs constants and version info for dependency checks.
- ~~What's the impact of failing Net::SSLeay tests?~~
  **Minimal** — the 22 program failures test OpenSSL C APIs that we
  intentionally don't implement. Our IO::Socket::SSL bypasses Net::SSLeay
  entirely by delegating to Java's javax.net.ssl.

### Known Limitations
- IO::Socket::IP Timeout parameter: Non-blocking connect with Timeout causes
  "Input/output error" in PerlOnJava. Our IO::Socket::SSL::configure() works
  around this by clearing io_socket_timeout before calling SUPER::configure.
- SSL sockets always report "ready" in select(): Since SSLSocket doesn't expose
  NIO channels, select() can't accurately poll SSL sockets. This works for
  LWP (which just needs to know data is available) but may cause busy-loops
  with event-driven frameworks.
- Client-only: No server-side SSL (accept_SSL). Needs SSLServerSocket.
- No OCSP stapling, CRL checking, or session ticket control.
- CPAN IO::Socket::SSL (if loaded instead of bundled) would fail — it calls
  ~120 Net::SSLeay functions we don't implement.

## Related Documents
- `dev/modules/lwp_useragent.md` — LWP::UserAgent support (prerequisite, complete)
- `dev/modules/www_mechanize.md` — WWW::Mechanize (depends on LWP, HTTP only)
- `dev/modules/net_smtp.md` — Net::SMTP (SSL tests currently skipped)
- `dev/modules/smoke_test_investigation.md` — CPAN smoke test analysis

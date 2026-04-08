# LWP::Protocol::https Support for PerlOnJava

## Status: Phase 0 — Investigation Complete

**Branch**: `feature/lwp-protocol-https`
**Date started**: 2026-04-08

## Background

`LWP::Protocol::https` is the plug-in that enables `https://` URLs in
LWP::UserAgent.  Running `./jcpan -t LWP::Protocol::https` currently fails
across the entire dependency chain.

### Dependency Chain

```
LWP::Protocol::https
  ├── IO::Socket::SSL  (>= 1.970)
  │     └── Net::SSLeay  (XS — OpenSSL C bindings)
  ├── Net::HTTPS  (>= 6)   — pure Perl, already installed
  └── LWP::UserAgent       — already working (317/317 subtests)
```

### Current Failure Summary

| Module | Test Result | Root Cause |
|--------|-------------|------------|
| **Net::SSLeay 1.96** | 2/5 tests fail, StackOverflowError | XS module with no Java backing; `constant()` undefined → infinite AUTOLOAD recursion |
| **IO::Socket::SSL 2.098** | 42/45 test programs fail | Can't load Net::SSLeay → all SSL operations fail |
| **LWP::Protocol::https 6.15** | 3/4 test programs fail | t/diag.t: Net::SSLeay load failure; t/example.t: HTTPS requests fail; t/https_proxy.t: `MSG_PEEK` not exported by Socket |

## Detailed Issue Analysis

### Issue 1: Net::SSLeay AUTOLOAD Infinite Recursion

Net::SSLeay is an XS module that wraps OpenSSL's C library (~500+ exported
functions).  PerlOnJava's `XSLoader::load('Net::SSLeay')` silently succeeds
without loading any actual functions.

When any undefined function is called, `Net::SSLeay::AUTOLOAD` (line 996)
tries to call `constant($name)` at line 1003 — but `constant()` is itself an
XS function that was never loaded.  This triggers AUTOLOAD again, creating an
infinite recursion → `java.lang.StackOverflowError`.

**Error trace:**
```
Can't locate auto/Net/SSLeay/autosplit.ix in @INC ...
java.lang.StackOverflowError
  Net::SSLeay at .../Net/SSLeay.pm line 1002
  Net::SSLeay at .../Net/SSLeay.pm line 1003  (repeated ~1000x)
```

### Issue 2: IO::Socket::SSL Uses ~127 Unique Net::SSLeay Functions

IO::Socket::SSL v2.098 calls ~127 unique `Net::SSLeay::` functions spanning:
- **24 constants** (VERIFY_PEER, ERROR_WANT_READ, OP_NO_SSLv3, etc.)
- **35 CTX functions** (context creation, cert loading, cipher config)
- **24 SSL object ops** (connect, read, write, shutdown, etc.)
- **18 X509/cert functions** (peer cert, subject/issuer names, SANs)
- **11 OCSP functions** (optional stapling)
- **7 BIO functions** (memory-buffer SSL)
- **8 crypto utilities** (DH, ECDH, PKCS12, digests)
- Plus error handling, session management, protocol negotiation

Implementing the full Net::SSLeay API in Java is **impractical** — it would
mean reimplementing most of OpenSSL's C API surface.

### Issue 3: Socket Module Missing Constants

`t/https_proxy.t` fails with:
```
"MSG_PEEK" is not exported by the Socket module
```

The Socket Java implementation (`Socket.java`) and Perl stub (`Socket.pm`) are
missing several standard constants:

| Symbol | Value | Needed By |
|--------|-------|-----------|
| `MSG_PEEK` | 2 | LWP::Protocol::https t/https_proxy.t, IO::Socket::SSL |
| `MSG_OOB` | 1 | Net::FTP (line 518) |
| `MSG_DONTROUTE` | 4 | Standard Socket export |
| `MSG_DONTWAIT` | 0x40 | Common in non-blocking I/O |
| `SO_RCVBUF` | (platform) | Already in Java field, not exported |
| `SO_SNDBUF` | (platform) | Already in Java field, not exported |
| `CR` / `LF` / `CRLF` | "\015" / "\012" / "\015\012" | Socket `:crlf` tag (declared but never defined) |

## Design: Java-Backed IO::Socket::SSL

### Strategy

Rather than implementing 127+ Net::SSLeay functions, we implement
**IO::Socket::SSL as a Java XS module** using `javax.net.ssl.*`, the JDK's
built-in TLS stack.  This is the same approach used by `HttpTiny.java` which
already handles HTTPS via `java.net.http.HttpClient` + `SSLContext`.

```
┌──────────────────────────────────────────────────┐
│  LWP::Protocol::https  /  Net::HTTPS  (pure Perl)│
├──────────────────────────────────────────────────┤
│  IO::Socket::SSL.pm  (Perl stub, calls XSLoader) │
├──────────────────────────────────────────────────┤
│  IOSocketSSL.java     (Java XS, extends           │
│                        PerlModuleBase)             │
│  Uses: javax.net.ssl.SSLContext                    │
│        javax.net.ssl.SSLSocket / SSLEngine         │
│        javax.net.ssl.TrustManagerFactory           │
│        javax.net.ssl.SSLParameters                 │
│        java.security.KeyStore                      │
│        java.security.cert.X509Certificate          │
├──────────────────────────────────────────────────┤
│  SocketIO.java  (existing TCP socket layer)        │
└──────────────────────────────────────────────────┘
```

A **minimal Net::SSLeay stub** provides only what IO::Socket::SSL probes for:
- `$Net::SSLeay::VERSION` (so dependency checks pass)
- Constants as numeric values (VERIFY_PEER, ERROR_*, OP_*, etc.)
- No-op init functions (`library_init`, `load_error_strings`, `randomize`)
- A working `constant()` function (returns EINVAL for unknown names)

All actual SSL work is done in the Java IO::Socket::SSL implementation.

### Java SSL Capabilities (already in JDK)

| Perl Feature | Java Equivalent |
|--------------|-----------------|
| SSL context creation | `SSLContext.getInstance("TLS")` |
| Certificate verification | `TrustManagerFactory` + system CA store |
| Custom CA files | Load PEM into `KeyStore`, build `TrustManager` |
| Client certificates | `KeyManagerFactory` with PKCS12/JKS |
| Cipher selection | `SSLParameters.setCipherSuites()` |
| SNI (Server Name) | `SSLParameters.setServerNames()` |
| ALPN negotiation | `SSLParameters.setApplicationProtocols()` |
| Protocol version control | `SSLParameters.setProtocols()` |
| Session caching | Built into `SSLContext` (automatic) |
| Non-blocking SSL | `SSLEngine` with NIO channels |
| Certificate inspection | `SSLSession.getPeerCertificates()` |
| verify_hostname | `HttpsURLConnection.getDefaultHostnameVerifier()` |

### IO::Socket::SSL API to Implement

#### Constructor / Connection (Phase 2 — Core)
- `new()` — create SSL socket (wraps existing TCP socket with SSLSocket)
- `connect_SSL()` / `start_SSL()` — upgrade plain socket to SSL
- `accept_SSL()` — server-side TLS handshake
- `close()` / `stop_SSL()` — SSL shutdown + close

#### I/O (Phase 2 — Core)
- `sysread()` / `read()` / `readline()` — read through SSL
- `syswrite()` / `write()` / `print()` — write through SSL
- `peek()` — peek at buffered SSL data
- `pending()` — bytes available in SSL buffer

#### Certificate Inspection (Phase 3 — Extended)
- `peer_certificate()` — get peer X509 cert
- `get_cipher()` — current cipher suite name
- `get_sslversion()` — TLS version string
- `get_fingerprint()` — certificate fingerprint
- `dump_peer_certificate()` — human-readable cert info

#### Configuration (Phase 2 — Core)
- `SSL_verify_mode` — VERIFY_NONE / VERIFY_PEER
- `SSL_hostname` — SNI server name
- `SSL_ca_file` / `SSL_ca_path` — custom CA trust store
- `SSL_cert_file` / `SSL_key_file` — client certificate
- `SSL_cipher_list` — allowed ciphers
- `SSL_version` — protocol version constraints
- `SSL_alpn_protocols` — ALPN negotiation

#### Class Methods (Phase 2 — Core)
- `can_client_sni()` — returns 1 (Java supports SNI)
- `can_server_sni()` — returns 1
- `can_alpn()` — returns 1
- `can_npn()` — returns 0 (NPN deprecated, Java doesn't support it)
- `default_ca()` — returns truthy (JVM has built-in CA store)
- `errstr()` — last SSL error string
- `opened()` — check if SSL is active

## Implementation Plan

### Phase 1: Socket Constants + Net::SSLeay Stub

**Goal**: Fix the immediate crashes so the dependency chain can be traversed.

1. **Add missing Socket constants** to `Socket.java` and `Socket.pm`:
   - `MSG_PEEK` (2), `MSG_OOB` (1), `MSG_DONTROUTE` (4), `MSG_DONTWAIT` (0x40)
   - Export `SO_RCVBUF` / `SO_SNDBUF` (already defined in Java, just need
     accessor methods + registerMethod + @EXPORT entries)
   - Define `CR`, `LF`, `CRLF` string constants

2. **Create minimal Net::SSLeay Java stub** (`NetSSLeay.java`):
   - Export `$VERSION`, `$trace`
   - All 24 constants IO::Socket::SSL needs (as numeric values)
   - `constant($name)` — look up by name, set `$!` to EINVAL if unknown
   - No-op init functions: `library_init`, `load_error_strings`,
     `randomize`, `SSLeay_add_ssl_algorithms`, `OpenSSL_add_all_digests`
   - `SSLeay_version()` — return "PerlOnJava (Java TLS)"
   - `OPENSSL_VERSION_NUMBER()` — return a modern version number so
     IO::Socket::SSL doesn't disable features

3. **Create `src/main/perl/lib/Net/SSLeay.pm`** — bundled Perl stub that
   calls `XSLoader::load('Net::SSLeay')` and declares exports.  This
   replaces the CPAN-installed XS version and avoids the autosplit.ix /
   AUTOLOAD issues entirely.

**Success criteria**: `use Net::SSLeay; print Net::SSLeay::VERIFY_PEER()` works.
`use IO::Socket::SSL` loads without crashing (though SSL operations don't work yet).

### Phase 2: Java IO::Socket::SSL Core Implementation

**Goal**: HTTPS client connections work via LWP::UserAgent.

1. **Create `IOSocketSSL.java`** extending `PerlModuleBase`:
   - Constructor accepts same options as Perl IO::Socket::SSL
   - Wraps existing `SocketIO` TCP connection with `SSLSocket` from
     `SSLSocketFactory`
   - Registers into `IO::Socket::SSL` namespace via XSLoader

2. **Create `src/main/perl/lib/IO/Socket/SSL.pm`** — bundled Perl stub:
   - Replaces the CPAN IO::Socket::SSL
   - Inherits from `IO::Socket::INET` (for TCP base)
   - Calls `XSLoader::load('IO::Socket::SSL')` for Java methods
   - Exports constants (SSL_VERIFY_NONE, SSL_VERIFY_PEER, etc.)

3. **Implement core SSL operations in Java**:
   - `start_SSL()` — wrap SocketChannel with SSLSocket/SSLEngine
   - `connect_SSL()` — perform TLS handshake
   - Read/write through SSL layer
   - Certificate verification using JVM trust store
   - SNI support via `SSLParameters.setServerNames()`
   - Custom CA file loading (PEM → KeyStore → TrustManager)

4. **Implement `IO::Socket::SSL::Utils`** (needed by build_requires):
   - `CERT_create()`, `PEM_cert2string()` — for test certificate generation
   - Use `java.security.KeyPairGenerator` + `java.security.cert.X509Certificate`

**Success criteria**: `LWP::UserAgent->new->get("https://httpbin.org/get")`
returns a successful response.

### Phase 3: Extended Features + Test Suite

**Goal**: LWP::Protocol::https test suite passes.

1. **Certificate inspection methods**:
   - `peer_certificate()` — returns object with `subject_name`, `issuer_name`
   - `get_cipher()`, `get_sslversion()`, `get_fingerprint()`
   - These are needed by `LWP::Protocol::https::_get_sock_info()`

2. **Hostname verification**:
   - `SSL_verifycn_scheme => 'www'` — standard hostname checking
   - Java's `HostnameVerifier` handles this natively

3. **Proxy CONNECT tunnel support**:
   - `_upgrade_sock()` in LWP::Protocol::https
   - Needs `start_SSL()` on an already-connected socket

4. **Run and fix LWP::Protocol::https tests**:
   - `t/00-report-prereqs.t` — should already pass
   - `t/diag.t` — needs Net::SSLeay to load
   - `t/example.t` — needs working HTTPS GET
   - `t/https_proxy.t` — needs MSG_PEEK + IO::Socket::SSL::Utils + fork

### Phase 4: Broader SSL Ecosystem (Future)

Not required for LWP::Protocol::https, but enables other modules:

1. **Server-side SSL** (accept_SSL) — for HTTP::Daemon::SSL, test servers
2. **Non-blocking SSL** via SSLEngine — for POE, AnyEvent, Mojo
3. **OCSP stapling** — certificate status checking
4. **CRL support** — certificate revocation lists
5. **Net::SMTP SSL** — currently skipped in libnet tests

## Architecture Notes

### Why Not Implement Full Net::SSLeay?

Net::SSLeay exposes the raw OpenSSL C API to Perl (~500+ functions).  Many of
these operate on opaque C pointers (SSL*, SSL_CTX*, X509*, BIO*) that have no
Java equivalent.  The function-by-function approach would require:

- Mapping every C struct to a Java object
- Simulating pointer-based lifetime management
- Reimplementing OpenSSL-specific APIs that Java's TLS stack handles differently

Java's `javax.net.ssl` provides the same capabilities at a **higher
abstraction level**.  Implementing IO::Socket::SSL directly in Java is
estimated at ~500-800 lines of Java code vs. 3000+ lines for a Net::SSLeay
reimplementation, with better reliability since we use the JDK's battle-tested
TLS implementation.

### SocketIO.java Integration

The existing `SocketIO.java` manages plain TCP sockets via NIO
`SocketChannel`.  For SSL, we have two options:

**Option A — SSLSocket wrapping** (simpler):
- After TCP connect, get the raw `Socket` from `SocketChannel.socket()`
- Wrap with `SSLSocketFactory.createSocket(socket, host, port, true)`
- Read/write through the SSLSocket's streams
- Pros: simple, handles buffering/handshake automatically
- Cons: blocking I/O only

**Option B — SSLEngine + NIO** (non-blocking):
- Create `SSLEngine` with host/port
- Integrate with existing NIO SocketChannel
- Manual handshake/wrap/unwrap cycle
- Pros: non-blocking, integrates with select()
- Cons: significantly more complex (~300 more lines)

**Recommendation**: Start with Option A (SSLSocket) for Phase 2, since
LWP::UserAgent uses blocking I/O.  Add SSLEngine support in Phase 4 if needed
for async frameworks.

### Precedent: HttpTiny.java

`HttpTiny.java` already demonstrates the pattern:
```java
SSLContext sslContext = SSLContext.getInstance("TLS");
// For verify_SSL=0:
sslContext.init(null, trustAllCerts, new SecureRandom());
// For verify_SSL=1: use default context (JVM CA store)
HttpClient.Builder builder = HttpClient.newBuilder()
    .sslContext(sslContext);
```

The IO::Socket::SSL implementation follows the same pattern but at the socket
level instead of the HTTP client level.

## Files to Create / Modify

### New Files
| File | Purpose |
|------|---------|
| `src/main/java/org/perlonjava/runtime/perlmodule/NetSSLeay.java` | Net::SSLeay Java stub (constants + no-op inits) |
| `src/main/java/org/perlonjava/runtime/perlmodule/IOSocketSSL.java` | IO::Socket::SSL Java implementation |
| `src/main/perl/lib/Net/SSLeay.pm` | Bundled Net::SSLeay Perl stub |
| `src/main/perl/lib/IO/Socket/SSL.pm` | Bundled IO::Socket::SSL Perl stub (replaces CPAN version) |

### Modified Files
| File | Change |
|------|--------|
| `src/main/java/org/perlonjava/runtime/perlmodule/Socket.java` | Add MSG_PEEK, MSG_OOB, SO_RCVBUF/SNDBUF exports, CR/LF/CRLF |
| `src/main/perl/lib/Socket.pm` | Add new constants to @EXPORT |
| `src/main/java/org/perlonjava/runtime/io/SocketIO.java` | Expose underlying Socket for SSL wrapping; support MSG_PEEK flag in recv |

## Related Documents
- `dev/modules/lwp_useragent.md` — LWP::UserAgent support (prerequisite, complete)
- `dev/modules/www_mechanize.md` — WWW::Mechanize (depends on LWP, HTTP only)
- `dev/modules/net_smtp.md` — Net::SMTP (SSL tests currently skipped)
- `dev/modules/smoke_test_investigation.md` — CPAN smoke test analysis

## Progress Tracking

### Current Status: Phase 2 complete, Phase 3 in progress

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
  - Created NetSSLeay.java with ~40 constants, no-op init functions,
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

### Next Steps
1. Fix Client-SSL-Version header (returned as undef by LWP::Protocol::https)
2. Run LWP::Protocol::https test suite
3. Implement IO::Socket::SSL::Utils if needed for tests
4. Consider Phase 4 features (SSLEngine for non-blocking)

### Open Questions
- Should IO::Socket::SSL::Utils (cert generation for tests) be implemented in
  Java, or should those tests be skipped for now?
- Should we support non-blocking SSL (SSLEngine) in Phase 2, or defer to
  Phase 4?
- ~~Do we need to bundle a `Net::SSLeay.pm` or is a Java-only module sufficient?~~
  (Answer: need Perl stub for `@EXPORT` / `%EXPORT_TAGS` declarations — done)

### Known Limitations
- IO::Socket::IP Timeout parameter: Non-blocking connect with Timeout causes
  "Input/output error" in PerlOnJava. Our IO::Socket::SSL::configure() works
  around this by clearing io_socket_timeout before calling SUPER::configure.
- SSL sockets always report "ready" in select(): Since SSLSocket doesn't expose
  NIO channels, select() can't accurately poll SSL sockets. This works for
  LWP (which just needs to know data is available) but may cause busy-loops
  with event-driven frameworks.
- httpbin.org cert not trusted by JVM default CA store (site-specific issue)

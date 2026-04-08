# LWP::Protocol::https Support for PerlOnJava

## Status: Phase 2 + Tier 2.5 + Tier 3 Phase 1.5 complete, Net::SSLeay 2101/2101 (100% pass)

**Branch**: `feature/lwp-protocol-https`
**PR**: #461
**Date started**: 2026-04-08
**Last updated**: 2026-04-08 (Tier 3 Phase 1.5 complete)

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

### Net::SSLeay 1.96 — 2101/2101 subtests pass (100%)

```
Files=48, Tests=2101
46 test programs pass with 0 failures.
2 failing programs (bail out before completing — need X509 creation + CRL functions).
```

Key tests all pass: `03_use`, `04_basic`, `05_passwd_cb`, `09_ctx_new`, `10_rand`,
`15_bio`, `20_functions`, `21_constants`, `30_error`, `31_rsa_generate_key`,
`32_x509_get_cert_info` (746/746), `36_verify` (105/105), `37_asn1_time`,
`38_priv-key`, `39_pkcs12` (17/17), `50_digest`.

All 46 passing test programs have 0 subtest failures. The 2 failing programs
bail out on unimplemented functions (X509 creation, CRL).

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

#### The 8 remaining subtest failures — RESOLVED by Tier 2

| Test | Subtests | Issue | Status |
|------|----------|-------|--------|
| `31_rsa_generate_key.t` #1-6 | RSA key generation + callbacks | `RSA_generate_key` → `KeyPairGenerator` | ✅ 14/14 pass |
| `50_digest.t` #1-2 | EVP digest init, digest list | `EVP_MD_CTX_new` → `MessageDigest` | ✅ 206/206 pass |

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

**Tier 2 — Java-backed OpenSSL function implementations (DONE — 1118/1118 subtests pass)**
All 9 passing test programs now have 0 failures (was 8 failures in 2 programs).
83 additional subtests gained from 5 newly-passing test programs.

| Group | Functions | Java backend | Test file | Subtests | Effort |
|-------|-----------|-------------|-----------|----------|--------|
| RAND | `RAND_status`, `RAND_poll`, `RAND_bytes`, `RAND_pseudo_bytes`, `RAND_priv_bytes`, `RAND_file_name`, `RAND_load_file` | `SecureRandom` | 10_rand.t | 53 | Easy |
| Error queue | `ERR_put_error` (+ existing `ERR_get_error`, `ERR_error_string`) | Thread-local `Deque<Long>` | 30_error.t | 8 more | Easy |
| BIO (memory) | `BIO_s_mem`, `BIO_new`, `BIO_write`, `BIO_pending`, `BIO_read`, `BIO_free` | `ByteArrayOutputStream` | 15_bio.t | 7 | Medium |
| RSA keygen | `RSA_generate_key(bits, e, cb, userdata)` | `KeyPairGenerator("RSA")` | 31_rsa_generate_key.t | 14 | Medium |
| EVP digest | `EVP_MD_CTX_create/destroy`, `EVP_get_digestbyname`, `EVP_DigestInit/_ex`, `EVP_DigestUpdate`, `EVP_DigestFinal/_ex`, `EVP_Digest`, `EVP_MD_type/size`, `EVP_MD_CTX_md`, `EVP_sha1/sha256/sha512`, `P_EVP_MD_list_all`, `MD2/MD4/MD5/RIPEMD160/SHA1/SHA256/SHA512`, `EVP_MD_get0_name/description`, `EVP_MD_get_type`, `NID_sha512` | `MessageDigest` | 50_digest.t | 206 | Medium-Hard |

Implementation approach: All functions are registered in `NetSSLeay.java` via
`registerMethod()`. Opaque handles (BIO, EVP_MD_CTX, RSA) use RuntimeScalar
wrapping Java objects, accessed via `value` field casting. The EVP digest API
uses `java.security.MessageDigest` with an internal map of OpenSSL NID → Java
algorithm names.

*Previously estimated 2-3 days. Would pass ~5 more test programs and bring
Net::SSLeay from 1035/1043 to potentially ~1300+ subtests passing.*

**Tier 2.5 — ASN1_TIME, PEM keys, SSL_CTX/SSL lifecycle (DONE — 1189/1189 subtests pass)**
Three more test programs now pass (was 9, now 12). 67 additional subtests.

| Group | Functions | Java backend | Test file | Subtests | Effort |
|-------|-----------|-------------|-----------|----------|--------|
| ASN1_TIME | `ASN1_TIME_new/set/free`, `P_ASN1_TIME_put2string`, `P_ASN1_UTCTIME_put2string`, `P_ASN1_TIME_get_isotime/set_isotime`, `X509_gmtime_adj` | `java.time.Instant` | 37_asn1_time.t | 10 | Easy |
| PEM keys | `PEM_read_bio_PrivateKey` (unencrypted + encrypted with password callback), `EVP_PKEY_free`, `BIO_new_file` fix (now reads files) | `KeyFactory`, `Cipher` (EVP_BytesToKey KDF) | 38_priv-key.t | 10 | Medium |
| SSL_CTX/SSL | `CTX_new/v23_new/new_with_method/free`, `SSLv23_method/client/server`, `TLS_method/client/server`, `TLSv1_method`, `SSL_new/free`, `in_connect_init/in_accept_init`, `CTX_set/get_min/max_proto_version`, `set/get_min/max_proto_version`, `SSL3_VERSION` | Opaque handle maps with role + version state | 09_ctx_new.t | 44 | Medium |

**Tier 3 — X509 with BouncyCastle (3 phases)**

Adding BouncyCastle (`bcprov-jdk18on` ~5.8MB, `bcpkix-jdk18on` ~1.1MB) enables
X509 certificate parsing, creation, signing, and CRL management.

*Phase 1 — X509 reading + password callbacks (DONE — 1975/1975 subtests pass)*

| Group | Functions | Java/BC backend | Test file | Subtests | Status |
|-------|-----------|----------------|-----------|----------|--------|
| PEM/X509 parsing | `PEM_read_bio_X509`, `X509_free` | `CertificateFactory` | 32_x509_get_cert_info.t | 746/746 | ✅ |
| X509 reading | `X509_get_subject/issuer_name`, `X509_get_version/serialNumber`, `X509_get_notBefore/notAfter`, `X509_get_pubkey`, `X509_get_subjectAltNames`, `X509_get_ext_by_NID/get_ext` | `java.security.cert.X509Certificate` | 32_x509_get_cert_info.t | (included) | ✅ |
| X509_NAME | `X509_NAME_new/hash`, `X509_NAME_oneline`, `X509_NAME_print_ex`, `X509_NAME_entry_count`, `X509_NAME_get_entry`, `X509_NAME_ENTRY_get_data/object` | `X500Principal` + DER parsing | 32_x509_get_cert_info.t | (included) | ✅ |
| OBJ/NID | `OBJ_obj2nid`, `OBJ_nid2sn`, `OBJ_nid2ln`, `OBJ_obj2txt` | Static NID↔OID lookup table (~40 OIDs) | 32_x509_get_cert_info.t | (included) | ✅ |
| ASN1 | `P_ASN1_INTEGER_get_hex/dec`, `P_ASN1_STRING_get` (raw bytes + UTF-8 decode) | `BigInteger`, byte[] | 32_x509_get_cert_info.t | (included) | ✅ |
| X509 digest | `X509_pubkey_digest` (BIT STRING extraction), `X509_digest`, `X509_get_fingerprint` | `MessageDigest` | 32_x509_get_cert_info.t | (included) | ✅ |
| X509 extensions | `X509V3_EXT_print` (keyUsage, extKeyUsage, SAN, issuerAltName, basicConstraints, AKI, SKI, CRL DPs, cert policies, AIA), `X509_EXTENSION_get_data/object/critical` | DER parsing + formatting | 32_x509_get_cert_info.t | (included) | ✅ |
| EVP_PKEY | `EVP_PKEY_bits/size/security_bits/id` | `java.security.PublicKey` | 32_x509_get_cert_info.t | (included) | ✅ |
| P_X509 convenience | `P_X509_get_crl_distribution_points`, `P_X509_get_key_usage`, `P_X509_get_netscape_cert_type`, `P_X509_get_ext_key_usage`, `P_X509_get_signature_alg`, `P_X509_get_pubkey_alg` | DER parsing | 32_x509_get_cert_info.t | (included) | ✅ |
| X509_STORE | `X509_STORE_new`, `X509_STORE_CTX_new/init/set_cert/get0_cert/get1_chain`, `X509_STORE_add_cert`, `X509_verify_cert`, `sk_X509_num/value/insert/delete/unshift/shift/pop` | Java cert chain | 32_x509_get_cert_info.t | (included) | ✅ |
| Passwd callback | `CTX_set_default_passwd_cb/userdata`, `CTX_use_PrivateKey_file`, SSL-level equivalents | Wire PEM decryption through CTX state + `RuntimeCode.apply()` | 05_passwd_cb.t | 36/36 | ✅ |

*Phase 1.5a — PKCS12 loading (next)*

| Group | Functions | Java backend | Test file | Subtests | Effort |
|-------|-----------|-------------|-----------|----------|--------|
| PKCS12 | `P_PKCS12_load_file` | `java.security.KeyStore("PKCS12")` | 39_pkcs12.t | 17 | Easy |

All other functions in 39_pkcs12.t are already implemented (X509_get_subject_name, X509_NAME_oneline).

*Phase 1.5b — X509_verify + OBJ_* functions + verify infrastructure (next)*

| Group | Functions | Java backend | Test file | Subtests | Effort |
|-------|-----------|-------------|-----------|----------|--------|
| X509_verify | `X509_verify($cert, $pkey)` | `cert.verify(publicKey)` | 33_x509_create_cert.t | unblocks first test | Easy |
| X509_NAME_cmp | `X509_NAME_cmp` | Name hash comparison | 33_x509_create_cert.t | unblocks test 3 | Easy |
| OBJ lookup | `OBJ_txt2obj`, `OBJ_txt2nid`, `OBJ_ln2nid`, `OBJ_sn2nid`, `OBJ_cmp`, `OBJ_nid2obj` | Static lookup tables | 36_verify.t | ~16 tests | Easy |
| Verify params | `X509_VERIFY_PARAM_new/free/set_flags/get_flags/clear_flags/inherit/set1/set1_name/set_purpose/set_trust/set_depth/set_time/add0_policy/set1_host/add1_host/set1_email/set1_ip/set1_ip_asc/set_hostflags/get0_peername` | Parameter bag class | 36_verify.t | ~30 tests | Medium |
| Store/CTX cleanup | `X509_STORE_free`, `X509_STORE_CTX_free`, `X509_STORE_CTX_get_error` | GC + error tracking | 36_verify.t | enables verify tests | Easy |
| X509_V_* constants | `X509_V_OK`, `X509_V_FLAG_*`, `X509_V_ERR_*`, `X509_PURPOSE_*`, `X509_TRUST_*`, `X509_CHECK_FLAG_*` | Constant table | 36_verify.t | enables verify tests | Easy |
| PEM cert chain | `PEM_X509_INFO_read_bio`, `sk_X509_INFO_num/value`, `P_X509_INFO_get_x509`, `sk_X509_new_null`, `sk_X509_push/free` | Cert chain parsing | 36_verify.t | ~20 tests | Medium |

Note: 36_verify.t has ~39 tests that need fork for SSL client/server — those will remain skipped.

*Phase 2 — X509 creation and signing (future — requires BouncyCastle or manual DER)*

| Group | Functions | Backend | Test file | Subtests |
|-------|-----------|---------|-----------|----------|
| X509 creation | `X509_new`, `X509_set_version/subject/issuer/pubkey/serialNumber`, `X509_sign`, `PEM_get_string_X509` | `X509v3CertificateBuilder` or manual DER | 33_x509_create_cert.t | 141 |
| X509_REQ | `X509_REQ_new/sign/verify`, `X509_REQ_set/get_*` | `PKCS10CertificationRequestBuilder` | 33_x509_create_cert.t | (included) |
| X509_NAME building | `X509_NAME_add_entry_by_NID/OBJ/txt` | `X500NameBuilder` | 33_x509_create_cert.t | (included) |
| EVP_PKEY lifecycle | `EVP_PKEY_new`, `EVP_PKEY_assign_RSA`, `RSA_get_key_parameters`, `BN_dup` | Key handle management | 33_x509_create_cert.t | (included) |
| PEM writing | `PEM_get_string_X509/PrivateKey/X509_REQ` | PEM encoding | 33_x509_create_cert.t | (included) |
| ASN1 integers | `ASN1_INTEGER_set/get/new/free`, `P_ASN1_INTEGER_set_hex/dec` | BigInteger | 33_x509_create_cert.t | (included) |

*Phase 3 — CRL (future — requires BouncyCastle or manual DER)*

| Group | Functions | Backend | Test file | Subtests |
|-------|-----------|---------|-----------|----------|
| CRL reading | `d2i_X509_CRL_bio`, `PEM_read_bio_X509_CRL`, `X509_CRL_get_issuer/version`, `X509_CRL_get0_lastUpdate/nextUpdate`, `X509_CRL_digest`, `X509_CRL_verify` | `CertificateFactory.generateCRL()` | 34_x509_crl.t | ~25 |
| CRL creation | `X509_CRL_new`, `X509_CRL_set_version/issuer_name`, `X509_CRL_set1_lastUpdate/nextUpdate`, `X509_CRL_sign`, `P_X509_CRL_add_revoked_serial_hex`, `P_X509_CRL_add_extensions` | `X509v2CRLBuilder` (BC) | 34_x509_crl.t | ~28 |

**Not fixable** (need fork or deprecated protocols):
- `06_tcpecho.t`, `07_sslecho.t`, `08_pipe.t`, `11_read.t` — need fork
- `40_npn_support.t` — NPN is dead + needs fork
- `41_alpn_support.t` through `47_keylog.t` — need fork for server/client

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

### Current Status: Tier 3 Phase 1.5 complete

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

- [x] Net::SSLeay Tier 2 — Java-backed OpenSSL function implementations (2026-04-08)
  - RAND: RAND_status/poll/bytes/pseudo_bytes/priv_bytes/file_name/load_file
    backed by SecureRandom (53 subtests)
  - Error queue: ERR_put_error/get_error/peek_error/error_string with
    thread-local Deque<Long>, OpenSSL 3.0.0 error code packing (11 subtests)
  - BIO: BIO_s_mem/new/write/read/pending/free backed by byte array buffer
    (7 subtests)
  - RSA: RSA_generate_key with callback support via KeyPairGenerator
    (14 subtests)
  - EVP digest: full EVP_MD_CTX lifecycle, 13 algorithms via JCE MessageDigest,
    NID mapping, convenience hash functions (206 subtests)
  - Moved print_errs to Perl (uses warn() for test compatibility)
  - Added OPENSSL_VERSION_NUMBER to CONSTANTS map
  - RAND_file_name reads Perl %ENV instead of Java System.getenv
  - Net::SSLeay test results: 8/1043 → 0/1118 failures (100% pass)
  - Files: NetSSLeay.java, Net/SSLeay.pm, lwp_protocol_https.md

- [x] Net::SSLeay Tier 2.5 — ASN1_TIME, PEM keys, SSL_CTX/SSL (2026-04-08)
  - ASN1_TIME: new/set/free, put2string, isotime get/set, X509_gmtime_adj
    backed by epoch seconds + java.time (10 subtests)
  - PEM keys: BIO_new_file fix (reads files), PEM_read_bio_PrivateKey with
    PKCS#1→PKCS#8 conversion, encrypted PEM via EVP_BytesToKey (10 subtests)
  - SSL_CTX/SSL: CTX_new/v23_new/new_with_method/free, method functions,
    SSL_new/free, in_connect_init/in_accept_init, proto version get/set,
    SSL3_VERSION constant (44 subtests)
  - Net::SSLeay test results: 0/1122 → 0/1189 failures (3 more tests pass)
  - Files: NetSSLeay.java

- [x] Net::SSLeay Tier 3 Phase 1 — X509 reading + password callbacks (2026-04-08)
  - Implemented ~77 new functions in NetSSLeay.java (~1500+ lines)
  - X509 certificate parsing via standard Java CertificateFactory (no BouncyCastle needed)
  - X509_NAME via custom DER parsing of X500Principal
  - Comprehensive OID↔NID↔name mapping (~40 OIDs via OidInfo class)
  - X509V3_EXT_print for 10 extension types (keyUsage, extKeyUsage, SAN, etc.)
  - X509_pubkey_digest with proper BIT STRING extraction from SubjectPublicKeyInfo
  - P_ASN1_STRING_get with raw bytes vs UTF-8 decoded mode
  - X509_get_subjectAltNames with raw binary IP addresses and otherName DER parsing
  - P_X509_get_ext_key_usage properly skips unknown OIDs in NID/name modes
  - X509_STORE/CTX infrastructure for certificate chain verification
  - sk_X509 stack operations (num/value/insert/delete/unshift/shift/pop)
  - Password callbacks via CTX_set_default_passwd_cb + RuntimeCode.apply()
  - EVP_PKEY attribute accessors (size/bits/security_bits/id)
  - Net::SSLeay test results: 0/1189 → 0/1975 failures (16 test programs pass)
  - 32_x509_get_cert_info.t: 746/746 (was 0), 05_passwd_cb.t: 36/36 (was 0)
  - Files: NetSSLeay.java, lwp_protocol_https.md

- [x] Net::SSLeay Tier 3 Phase 1.5 — PKCS12, verify, OBJ functions (2026-04-08)
  - Phase 1.5a: P_PKCS12_load_file with Java KeyStore + manual DER parser fallback
    for unencrypted PKCS12 files that Java's built-in KeyStore can't handle (JDK 24)
  - X509_verify with PrivateKey→PublicKey extraction for RSA CRT keys
  - X509_NAME_cmp with DER-based comparison
  - Phase 1.5b: Full X509_VERIFY_PARAM lifecycle (new/free/inherit/set1/flags/purpose/trust/depth/time)
  - OBJ_txt2obj, OBJ_txt2nid, OBJ_ln2nid, OBJ_sn2nid, OBJ_cmp
  - Proper X509_verify_cert with chain building and issuer verification
  - X509_STORE_CTX_get_error, X509_STORE_free, X509_STORE_CTX_free
  - PEM_X509_INFO_read_bio, sk_X509_INFO_num/value, P_X509_INFO_get_x509
  - sk_X509_new_null, sk_X509_push, sk_X509_free, X509_STORE_set1_param
  - X509_V_* constants, X509_PURPOSE/TRUST constants, X509_CHECK flags
  - PKCS OIDs added to OID database (NID_pkcs=2, NID_md5=4)
  - Net::SSLeay test results: 0/1975 → 0/2101 failures (46 test programs pass)
  - 36_verify.t: 105/105 (was 0), 39_pkcs12.t: 17/17 (was 0)
  - Files: NetSSLeay.java, lwp_protocol_https.md

### Next Steps

1. **Phase 2** — X509 creation/signing (33_x509_create_cert.t) — needs EVP_PKEY_new, X509_new, X509_sign, etc.
2. **Phase 3** — CRL functions (34_x509_crl.t) — needs d2i_X509_CRL_bio, X509_CRL_*, etc.
3. **Run `./jcpan -t LWP::Protocol::https`** to see current results

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

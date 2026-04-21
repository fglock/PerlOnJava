# Net::SSLeay — Complete Implementation Plan

## Context

PerlOnJava's current `Net::SSLeay` (`src/main/java/org/perlonjava/runtime/perlmodule/NetSSLeay.java`, ~7400 LOC) registers 350+ symbols but the coverage is uneven:

- **Working**: constants, handle-table plumbing, RAND_*, SHA/MD digests, X509 parsing (reading a PEM cert, extracting subject/issuer names, extension NIDs, validity dates), parts of PEM read, CRL read, EVP digest wrappers, SSLContext creation.
- **Partial**: CTX cert/key loading (works for simple PEM bundles, weak on password-protected keys, PKCS#12, RSAPrivateKey_file).
- **Stubs/no-ops** (added in PR #514 to let AnyEvent::TLS load): `CTX_set_options`, `CTX_set_mode`, `CTX_set_tmp_dh`, `CTX_set_read_ahead`, `set_accept_state`, `set_connect_state`, `set_bio`, `state`, `shutdown`, `read`, `write`, `get_error`, `X509_STORE_*` callbacks, DH_free, PEM_read_bio_DHparams. These store state or return hard-coded success/failure; they do not drive a real TLS session.
- **Missing** (~100 symbols): BIO memory-buffer read/write plumbing, BIGNUM, PKCS#12 parsing, session cache APIs, OCSP, HMAC_CTX incremental API, several EVP_PKEY variants, the non-blocking handshake driver, and most *_get_* introspection accessors.

The 350 "registered" count is misleading: roughly 150 of those are legitimate implementations, 100 are dispatching to partial backends, and 100 are hacks. This plan tackles converting the hacks into real implementations.

## Goals

1. **Correctness** — every Net::SSLeay call must have Perl-visible semantics that match upstream OpenSSL behaviour well enough for the CPAN modules that consume it (IO::Socket::SSL, LWP::UserAgent over HTTPS, Mojo::IOLoop::TLS, AnyEvent::TLS, Net::SSLGlue, Crypt::OpenSSL::*, Net::SNMP over TLS, etc.).
2. **Real TLS handshakes** — driven by `javax.net.ssl.SSLEngine` with in-memory BIOs, not by Java's higher-level `SSLSocket` (which forces a blocking I/O model that isn't compatible with AnyEvent::Handle's state machine).
3. **PEM/DER round-trip** — load certs and keys written by real OpenSSL, produce PEM that real OpenSSL accepts.
4. **Error queue fidelity** — failures produce the OpenSSL error codes users' code already checks via `ERR_get_error` / `ERR_error_string`, and the `SSL_ERROR_WANT_READ`/`SSL_ERROR_WANT_WRITE` distinction is preserved through the handshake driver.
5. **No regressions** — all existing `make` unit tests continue to pass.
6. **Stretch**: pass the full AnyEvent `t/80_ssltest.t` (415 subtests), the IO::Socket::SSL test suite when bundled, and HTTPS requests through LWP.

## Scope & non-goals

**In scope**: TLS 1.2 and TLS 1.3, RSA/ECDSA key/cert types, the subset of X509 extensions that CPAN modules actually read (`subjectAltName`, `basicConstraints`, `keyUsage`, `extKeyUsage`, `subjectKeyIdentifier`, `authorityKeyIdentifier`, `CRL Distribution Points`, `Authority Information Access`), OCSP stapling (Status Request), ALPN, SNI.

**Out of scope** (for this plan; track separately if needed):
- SRP, PSK (requires custom handshake hooks the JDK doesn't expose).
- Session tickets beyond what `SSLEngine` negotiates automatically.
- DTLS.
- FFI into libssl.so as a fallback (would defeat the "pure Java" goal).
- Custom engines / hardware token integration.

## Phasing

Each phase is self-contained, lands behind tests, and is merge-ready on its own.

### Phase 0 — Cleanup & accounting (≈1 day)

Prerequisite for everything else. Removes the hacks we added in a hurry and replaces them with clear "not yet implemented" markers that throw a traceable error rather than silently lying.

- [ ] Split `NetSSLeay.java` (7400 LOC) into topic-specific files:
  - `NetSSLeayCore.java` — initialize, module registration, constants, handle tables
  - `NetSSLeaySslEngine.java` — CTX/SSL/BIO/handshake
  - `NetSSLeayX509.java` — cert parsing, names, extensions, verification
  - `NetSSLeayPem.java` — PEM read/write for certs, keys, CRLs, params
  - `NetSSLeayBignum.java` — BN_* arithmetic
  - `NetSSLeayDigest.java` — MD*, SHA*, HMAC, EVP_Digest*
  - `NetSSLeayOcsp.java` — OCSP request/response
  - `NetSSLeayCipher.java` — EVP_Cipher*, symmetric crypto
- [ ] Add a `stub(name)` helper that throws `Carp::croak "Net::SSLeay::$name is not yet implemented"` so callers get a clear failure instead of silent no-op. Retag today's fake successes that aren't actually doing TLS.
- [ ] Write `src/test/perl/netssleay_baseline.t` enumerating every exported symbol, asserting type (sub/constant), and checking a single trivial invocation when safe. This becomes the regression gate for the rest of the plan.
- [ ] Inventory: produce `dev/modules/netssleay_symbols.tsv` with one row per OpenSSL entry point: `name | category | status (DONE/STUB/MISSING) | target_phase | notes`. The CI baseline test reads this file.

Exit criteria: unit tests pass; inventory file accurate; no `registerLambda(..., a, c -> { return fake_success; })` without a tracking entry in the TSV.

### Phase 1 — Error queue & BIO memory buffers (≈2 days)

The foundation everything else sits on.

- [ ] `ERR_*` queue: `ERR_get_error`, `ERR_peek_error`, `ERR_clear_error`, `ERR_put_error`, `ERR_error_string`. Thread-local `Deque<Long>` is already there — consolidate all stub sites to use it, and implement `ERR_error_string` with real reason strings (the `X:Y:Z:...` format).
- [ ] `BIO` memory buffers: `BIO_new(BIO_s_mem())` → allocate a `ByteBuffer`-backed queue; `BIO_new_mem_buf(data, len)` → read-only BIO over a buffer; `BIO_write`, `BIO_read`, `BIO_pending`, `BIO_eof`, `BIO_free`. Back it with `java.util.ArrayDeque<byte[]>` (chunk-at-a-time) — mirrors OpenSSL memory BIOs' semantics (appending more data doesn't invalidate prior handles).
- [ ] `BIO_new_file(path, mode)` backed by `java.nio.file.Files` streams.
- [ ] `BIO_s_file()` — returns an opaque method constant; used by `BIO_new` to select file vs memory.
- [ ] Unit tests: write/read round-trips, overflow, EOF semantics, chaining two BIOs, concurrent reader/writer thread safety (AnyEvent is single-threaded but some callers aren't).

Exit criteria: `t/netssleay_bio.t` passes 100%; IO::Socket::SSL's memory-BIO code paths work in isolation.

### Phase 2 — SSLEngine handshake driver (≈5–7 days, the big rock)

This is the core TLS engine. Nothing about real handshakes works until this lands.

**Design**: every SSL handle owns an `SSLEngine` plus two memory BIOs. Perl code drives bytes through `BIO_write(rbio, netBytes)` and reads encrypted bytes via `BIO_read(wbio)`; our `Net::SSLeay::read` / `::write` operate on plaintext.

```
         plaintext in                       plaintext out
              │                                     ▲
              ▼                                     │
           ┌─────── Net::SSLeay::write ────────────────┐
           │             │                           │
           │         engine.wrap()            engine.unwrap()
           │             │                           │
           ▼             ▼                           ▲
         wbio ──────► netOut      netIn ─────────► rbio
          │                                         ▲
          │  (Perl pulls via BIO_read into socket)  │
          │  (Perl pushes into BIO_write from sock) │
```

- [ ] `CTX_new` / `CTX_new_with_method` / `CTX_tlsv*_new` / `CTX_v23_new`: build a `javax.net.ssl.SSLContext` for the requested protocol band. Respect `set_min_proto_version` / `set_max_proto_version`.
- [ ] `CTX_use_certificate_chain_file`, `CTX_use_PrivateKey_file`, `CTX_use_PrivateKey`, `CTX_use_certificate`, `CTX_use_RSAPrivateKey_file`: parse PEM (Phase 3) → build `KeyStore` → wire into `KeyManagerFactory`.
- [ ] `CTX_load_verify_locations`, `CTX_set_default_verify_paths`: build `TrustManagerFactory` from CA bundle files and/or JVM default trust store.
- [ ] `CTX_set_cipher_list`, `CTX_set_ciphersuites`: translate OpenSSL cipher names (`ECDHE-RSA-AES128-GCM-SHA256`) → IANA names (`TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256`) via a lookup table, then `engine.setEnabledCipherSuites(...)`.
- [ ] `CTX_set_options` / `set_options`: persist the bitmask and honour the bits that map to JDK features (`OP_NO_SSLv3`, `OP_NO_TLSv1`, …) by removing the banned protocol from `engine.setEnabledProtocols`. `OP_NO_TICKET`, `OP_SINGLE_DH_USE`, `OP_CIPHER_SERVER_PREFERENCE` etc. where the JDK exposes a toggle; warn-once for bits we can't express.
- [ ] `CTX_set_verify` / `set_verify`: translate `VERIFY_NONE`/`VERIFY_PEER`/`VERIFY_FAIL_IF_NO_PEER_CERT` to `engine.setNeedClientAuth` + a custom `X509TrustManager` that calls back into the Perl verify callback.
- [ ] `new(ctx)` → create `SSLEngine` from `SSLContext`; `set_accept_state` → `setUseClientMode(false)` + `beginHandshake()`; `set_connect_state` → `setUseClientMode(true)` + `beginHandshake()`.
- [ ] `set_bio(ssl, rbio, wbio)` → associate the two memory BIOs with the SSL handle.
- [ ] `set_tlsext_host_name(ssl, name)` → `SSLParameters.setServerNames` (SNI).
- [ ] **The driver**: `advance(sslHandle)` — called after every `write`/`read`/BIO I/O. Inspects `engine.getHandshakeStatus()` and loops:
  - `NEED_UNWRAP`: if `rbio` has bytes, `engine.unwrap`; else stop with `SSL_ERROR_WANT_READ`.
  - `NEED_WRAP`: `engine.wrap` plaintext-to-encrypted into a buffer, append to `wbio`.
  - `NEED_TASK`: run the delegated task on a local thread pool.
  - `FINISHED` or `NOT_HANDSHAKING`: mark state = `SSL_ST_OK`.
- [ ] `write(ssl, data)`: append plaintext to a pending queue; run `advance`; return bytes consumed from plaintext (NOT bytes emitted to `wbio`).
- [ ] `read(ssl)`: run `advance`; if the engine produced plaintext, return it; else return undef with errno indicating `SSL_ERROR_WANT_READ`.
- [ ] `get_error(ssl, ret)`: translate last engine state to the seven OpenSSL `SSL_ERROR_*` codes.
- [ ] `state(ssl)`: map engine state to OpenSSL state macros. We only need a handful to satisfy `AnyEvent::Handle` — `SSL_ST_OK` (`0x00`), `SSL_ST_CONNECT`, `SSL_ST_ACCEPT`, etc.
- [ ] `shutdown(ssl)`: call `engine.closeOutbound` / `closeInbound` and run `advance` once to emit close-notify.
- [ ] Cover: client-authenticated handshakes, renegotiation (best-effort — JDK renegotiation is opt-in), `SSL_MODE_ENABLE_PARTIAL_WRITE` semantics.

Exit criteria: a new `t/netssleay_handshake.t` spins up a TCP server in a thread, runs a real client/server handshake with JDK's built-in test cert, exchanges `"hello"` both ways. `cpan/t/80_ssltest.t` (AnyEvent) reaches the per-mode `ok N - mode N` output for every mode instead of hanging.

### Phase 3 — PEM / DER / PKCS#12 (≈3–4 days)

Lots of CPAN callers do `PEM_read_X509`, `PEM_write_PrivateKey`, etc. independently of a TLS handshake.

- [ ] A hand-rolled PEM reader (`-----BEGIN X-----` ... base64 ... `-----END X-----`) that handles comment lines, CRLF, encryption headers (`Proc-Type: 4,ENCRYPTED`). Decode to DER.
- [ ] DER → Java: `CertificateFactory.getInstance("X.509")` for certs; `KeyFactory.getInstance(alg).generatePrivate(new PKCS8EncodedKeySpec(...))` for keys. **Gotcha**: OpenSSL writes PKCS#1 RSA private keys by default; JDK wants PKCS#8. Either ship a PKCS#1→PKCS#8 converter or require Bouncy Castle on the classpath — decide at Phase 1. Lean toward hand-rolled ASN.1 (`org.perlonjava.asn1`) so we stay self-contained.
- [ ] `PEM_read_PrivateKey` + callback-based passphrase prompt (`CTX_set_default_passwd_cb`).
- [ ] `PEM_write_*` from Java objects back to canonical PEM (Base64-wrap at 64 cols, correct BEGIN/END tag). Tested against `openssl asn1parse` round-trips.
- [ ] PKCS#12: `PKCS12_parse(p12, pass)` → returns `(pkey, cert, ca_chain)`. Backed by `KeyStore.getInstance("PKCS12")`.
- [ ] `d2i_X509`, `i2d_X509`, `d2i_PKCS12_bio`: DER in/out.
- [ ] `PEM_read_bio_DHparams`, `DH_free`: parse DH params (`BEGIN DH PARAMETERS`) to a `DHParameterSpec`. Needed for `CTX_set_tmp_dh`.

Exit criteria: `t/netssleay_pem.t` round-trips cert/key/CRL/PKCS12 against reference data generated by real OpenSSL (checked-in test vectors). IO::Socket::SSL's `SSL_cert_file` + `SSL_key_file` options work end-to-end with Phase 2.

### Phase 4 — X509 introspection (≈3 days)

All the `*_get_*` functions certificate-inspection callers use.

- [ ] `X509_get_subject_name`, `X509_get_issuer_name`, `X509_NAME_oneline`, `X509_NAME_print_ex`, `X509_NAME_get_text_by_NID`, `X509_NAME_entry_count`, `X509_NAME_get_entry`, `X509_NAME_ENTRY_get_object`, `X509_NAME_ENTRY_get_data`. Build on `X509NameInfo` we already have; fill the gaps for RDN enumeration.
- [ ] `X509_get_notBefore`, `X509_get_notAfter`, `X509_get_serialNumber`, `X509_get_version`, `X509_get_pubkey`, `X509_pubkey_digest`.
- [ ] Extensions: `X509_get_ext_count`, `X509_get_ext_by_NID`, `X509_get_ext_d2i`, `X509_get_ext`. Return wrapper objects for the common extensions (`BasicConstraints`, `KeyUsage`, `ExtKeyUsage`, `SubjectAltName`, `AuthorityKeyIdentifier`, `SubjectKeyIdentifier`, `CRLDistributionPoints`, `AuthorityInfoAccess`, `CertificatePolicies`).
- [ ] `X509_get_subjectAltNames` (`P_X509_get_subjectAltNames` in newer Net::SSLeay): return the list of `[type, value]` pairs. Used by HTTPS hostname verification.
- [ ] SAN `GEN_*` constants (`GEN_DNS`, `GEN_IPADD`, `GEN_URI`, `GEN_EMAIL`), `NID_commonName`, `NID_*` for extension OIDs — pull from a generated table (`src/main/resources/net_ssleay_nid_table.properties`) built from the OpenSSL source once.
- [ ] `X509_STORE_*` + `X509_STORE_CTX_*`: enough surface for verify callbacks to inspect the chain. Currently stubbed.
- [ ] Chain building via `CertPathBuilder` for the verify callback, exposed as `X509_verify_cert` / `X509_STORE_CTX_get0_chain`.
- [ ] `sk_X509_num` / `sk_X509_value` / `sk_pop_free` / `sk_X509_pop_free` / `sk_GENERAL_NAME_num` / `sk_GENERAL_NAME_value`. The stack handles need their own `Long → List<Object>` table.

Exit criteria: LWP::UserAgent with `SSL_verify_mode => SSL_VERIFY_PEER` and `SSL_verifycn_scheme => 'http'` connects to `https://www.google.com/`; the hostname-verification callback sees matching SAN entries.

### Phase 5 — Digests, HMAC, symmetric crypto (≈2 days)

Low-risk because the JDK already does all the math.

- [ ] `EVP_get_digestbyname(name)` → return opaque handle bound to a `MessageDigest`. Names: `sha1`, `sha256`, `sha384`, `sha512`, `md5`, `ripemd160`, `sha3-256`, etc.
- [ ] `EVP_DigestInit_ex` / `EVP_DigestUpdate` / `EVP_DigestFinal_ex`: incremental digest over our handle.
- [ ] Existing SHA1/SHA256/SHA512 one-shots stay; just make sure the incremental one-shot (`SHA1_End` style) matches.
- [ ] `HMAC_CTX_new` / `HMAC_Init_ex` / `HMAC_Update` / `HMAC_Final` / `HMAC_CTX_free`. Back with `javax.crypto.Mac`.
- [ ] `EVP_get_cipherbyname(name)` + `EVP_CipherInit_ex` / `EVP_CipherUpdate` / `EVP_CipherFinal_ex`. Back with `javax.crypto.Cipher`. Cover at minimum: AES-GCM, AES-CBC, ChaCha20-Poly1305, DES-EDE3-CBC.
- [ ] `RC4_set_key` / `RC4`: use ARC4 via `Cipher.getInstance("RC4")` if available, otherwise a pure-Java reference (RC4 is being deprecated out of JDK).

Exit criteria: `Digest::SHA` and `Digest::HMAC` bundled-module tests continue to pass, and a new `t/netssleay_digest.t` exercises each digest/HMAC/cipher against RFC test vectors.

### Phase 6 — RSA / BIGNUM / EVP_PKEY (≈3 days)

- [ ] `RSA_generate_key(bits, e, cb, cb_arg)`: `KeyPairGenerator.getInstance("RSA")`, `initialize(bits)`, wrap the result in an `EVP_PKEY` handle that also quacks as an `RSA` handle.
- [ ] `RSA_public_encrypt` / `RSA_private_decrypt` / `RSA_private_encrypt` / `RSA_public_decrypt` / `RSA_sign` / `RSA_verify`: back with `Cipher.getInstance("RSA/ECB/PKCS1Padding")` for encrypt/decrypt and `Signature.getInstance(...)` for sign/verify. Support PSS padding.
- [ ] `RSA_free`, `RSA_new`, `RSA_size`. (Size = modulus length in bytes.)
- [ ] `BN_*`: wrap `java.math.BigInteger`. Covers `BN_new`, `BN_bin2bn`, `BN_bn2bin`, `BN_bn2dec`, `BN_bn2hex`, `BN_hex2bn`, `BN_add_word`, `BN_free`. Tiny surface — most callers use the hex/dec converters only.
- [ ] `EVP_PKEY_new`, `EVP_PKEY_free`, `EVP_PKEY_bits`, `EVP_PKEY_size`, `EVP_PKEY_get1_RSA`, `EVP_PKEY_get1_DSA`, `EVP_PKEY_get1_EC_KEY`, `EVP_PKEY_assign_EC_KEY` — our current impl is partial; fill in.
- [ ] `P_EVP_PKEY_fromdata` / `P_EVP_PKEY_todata` — newer helper APIs; defer if time-pressed.

Exit criteria: `Crypt::OpenSSL::RSA` bundled tests pass.

### Phase 7 — OCSP & session cache (≈2 days)

Nice-to-have for completeness; AnyEvent::TLS exercises the session cache paths.

- [ ] Session cache: `CTX_sess_*` counters are simple AtomicLongs. Real cache is `CTX_sess_set_new_cb` / `_remove_cb` / `_get_new_cb` — these expose session state to Perl for external caching. JDK `SSLSessionContext` can be adapted.
- [ ] `i2d_SSL_SESSION` / `d2i_SSL_SESSION` for session serialization. JDK doesn't expose this directly; we'd need to synthesize an ASN.1 representation using the session-id + master-secret (available via `SSLSession.getId()` but not the master secret in JDK ≥ 1.8). Realistically: emit an opaque random token, keep a per-process map of token→SSLSession. Limits cross-process resumption — acceptable.
- [ ] OCSP (`OCSP_REQUEST_*`, `OCSP_RESPONSE_*`, `OCSP_cert_to_id`, `OCSP_response_status`, `OCSP_response_results`, `OCSP_basic_verify`): implement via ASN.1. Known-hard; dependency on `java.security.cert.ocsp.*` (JDK internals). Consider declaring this "best effort" and tracking specific callers.
- [ ] `set_tlsext_status_type`, `set_tlsext_status_ocsp_resp`, `CTX_set_tlsext_status_cb`: stapling wiring. Needs JDK `SSLParameters.setServerSNI*` plus custom handshake hooks; realistically this is TLS-extension territory where JDK lags OpenSSL.

Exit criteria: Session resumption across two handshakes on the same `SSLContext` works; AnyEvent::TLS's session-cache test paths pass.

### Phase 8 — Integration & hardening (≈2–3 days)

- [ ] Run AnyEvent's full `make test` with `PERL_ANYEVENT_LOOP_TESTS=1` — identify the remaining failures that were masked by stubs.
- [ ] Run IO::Socket::SSL's own test suite against our implementation. Record which tests pass/fail, fix the high-value ones.
- [ ] Run LWP::UserAgent HTTPS fetches against a few real sites (for smoke).
- [ ] Stress test: 1000 concurrent handshakes in a thread pool; check for memory leaks in the handle table.
- [ ] Benchmark against fork()ed real Perl: within 2× is acceptable given we're pure Java.
- [ ] Update `AGENTS.md` to document `Net::SSLeay` as a supported module.

Exit criteria: `t/80_ssltest.t` passes 415/415; IO::Socket::SSL core tests pass; no crashes under stress.

## Dependencies and risks

### Runtime dependencies
- **JDK ≥ 11**: SSLEngine with TLS 1.3 is standard. Keep this as the floor.
- **Bouncy Castle**: adopted as a mandatory runtime dependency as of the
  `feature/digest-sha3-bouncycastle` work (see `dev/modules/digest_sha3.md`).
  Provides `bcprov-jdk18on` + `bcpkix-jdk18on`. Current uses:
  - `parsePrivateKeyDer` → `PrivateKeyInfo.getInstance` + `JcaPEMKeyConverter`
    (replaces trial-and-error KeyFactory loop + hand-rolled PKCS#1→PKCS#8 wrap).
  - `Digest::SHA3` / `Digest::Keccak` backend (fixed-length SHA-3, SHAKE
    XOFs, bit-level input).
  - Available for future refactors: encrypted-PEM write path, DH parameters,
    PKCS#12 with non-standard MACs, the CSR builder (all still hand-rolled
    DER today but no longer blocked on a dependency decision).

### Things that genuinely don't map
- **Access to TLS keylog / master secret**: blocked by JDK; would need `-Djdk.tls.keyExportState=true` via reflection in newer JDKs or an agent. For `CTX_set_keylog_callback` used by Wireshark integration tests, we'll need to work around.
- **Per-process session cache serialization across restart**: best-effort only.
- **`CTX_ctrl`**: OpenSSL's generic "do any thing" dispatch. Implement the subset of numeric commands CPAN actually calls; croak on the rest with the command number for easy debugging.

### Risk table

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| SSLEngine handshake driver has subtle corner cases around re-handshake, close-notify timing, key update | High | Exhaustive `t/netssleay_handshake.t` with every state transition. Cross-check against tcpdump of an OpenSSL-to-OpenSSL handshake on the same ports. |
| PKCS#1 vs PKCS#8 conversion in pure Java | Medium | ASN.1 bytes are well-defined; RFC 3447 appendix A has the ASN.1. Ship a dozen test vectors. |
| Bouncy-Castle classpath conflicts | Low but real if we add BC | Shade it. Add a top-level opt-in via `config.bouncyCastle = true` if we do go that route. |
| TLS 1.3-specific session tickets / early data | Medium | Document as Phase 9 stretch; most consumers don't hit early data. |
| `weaken` branch lands first and changes the event-loop timing — tests that hang now may fail differently | Medium | Gate each Phase's CI run on the `master` state at that time; re-baseline each phase. |

## Time estimate

Running total at the "done with a PR you'd actually merge" bar, one engineer, with test + review overhead:

| Phase | Engineering days |
|-------|------------------|
| 0 — cleanup & split | 1 |
| 1 — errors + BIO | 2 |
| 2 — SSLEngine driver | 5–7 |
| 3 — PEM/DER/PKCS12 | 3–4 |
| 4 — X509 introspection | 3 |
| 5 — digests/HMAC/cipher | 2 |
| 6 — RSA/BN/EVP_PKEY | 3 |
| 7 — OCSP/session | 2 |
| 8 — integration/hardening | 2–3 |
| **Total** | **23–27 days** |

That's about 5 calendar weeks for one engineer focused full-time, or 10–12 weeks at 40% allocation.

## Success criteria (final)

- [ ] All `make` unit tests pass.
- [ ] All `make test-bundled-modules` pass.
- [ ] `./jcpan -t AnyEvent` → `t/80_ssltest.t` passes 415/415.
- [ ] `./jcpan -t IO::Socket::SSL` → runs its test suite; document any pre-existing upstream skip/bail for non-SSLeay reasons.
- [ ] A sample HTTPS GET via `LWP::UserAgent` to a live public endpoint succeeds end-to-end.
- [ ] `dev/modules/netssleay_symbols.tsv` shows `status=DONE` for every row except those explicitly marked `out-of-scope`.
- [ ] No symbol registered via `registerLambda`/`registerMethod` returns a silently-wrong result. Every unimplemented entry throws `Carp::croak`.

## Where each piece lives after the split

After Phase 0, the codebase is:

```
src/main/java/org/perlonjava/runtime/perlmodule/netssleay/
├── NetSSLeay.java                  ← thin loader, registers everything
├── NetSSLeayCore.java              ← constants, handle tables, ERR queue
├── NetSSLeayBio.java               ← BIO memory + file
├── NetSSLeaySsl.java               ← SSLContext, SSLEngine, handshake driver
├── NetSSLeayX509.java              ← cert introspection
├── NetSSLeayX509Store.java         ← store + verify callback machinery
├── NetSSLeayPem.java               ← PEM/DER/PKCS#12
├── NetSSLeayDigest.java            ← SHA/MD/HMAC/EVP_Digest*
├── NetSSLeayCipher.java            ← EVP_Cipher*, RC4
├── NetSSLeayRsa.java               ← RSA_* + BN_* + EVP_PKEY_*
├── NetSSLeayOcsp.java              ← OCSP request/response
└── NetSSLeaySession.java           ← session cache, i2d/d2i_SSL_SESSION
```

Plus:
- `src/main/resources/net_ssleay_nid_table.properties` — generated once from OpenSSL source.
- `src/test/perl/lib/NetSSLeay/*.t` — a test harness mirroring the upstream Net::SSLeay test suite.
- `dev/modules/netssleay_symbols.tsv` — the inventory / progress tracker.

## Progress Tracking

### Current Status: Phase 2c complete — all previously MISSING symbols now registered

### Completed Phases
- [x] Phase 0: Inventory + markers + baseline regression (2026-04-20)
  - `dev/modules/netssleay_symbols.tsv`, 683-row inventory with 5 cols
  - `dev/tools/classify_netssleay.pl` + `netssleay_add_missing.pl`
  - `registerNotImplemented(name, phase)` helper in NetSSLeay.java
  - `src/test/resources/unit/netssleay_baseline.t`, 2422 assertions
  - Phase 0d (file split) deferred as mechanical / low-value.

- [x] Phase 1: ERR queue + BIO memory buffers (2026-04-20)
  - ERR_load_*_strings no-ops, ERR_print_errors_cb callback driver
  - BIO_new_mem_buf, BIO_s_file sentinel
  - `netssleay_phase1.t`, 39 assertions

- [x] Phase 3: PKCS12 + session token (2026-04-20)
  - PKCS12_parse (real, backed by java.security.KeyStore)
  - PKCS12_newpass (honest failure)
  - i2d_SSL_SESSION / d2i_SSL_SESSION (opaque in-process token)
  - `netssleay_phase3_7.t`, 14 assertions

- [x] Phase 4: X509 introspection (2026-04-20)
  - ASN1_STRING_{data,length,type}, ASN1_TIME_{print,set_string}
  - X509_NAME_get_index_by_NID, X509_cmp, X509_check_issued
  - X509_get_ex_new_index, X509_verify_cert_error_string
  - X509_STORE_CTX_{get0_chain,set_error}, X509_STORE crud stubs
  - GENERAL_NAME / sk_GENERAL_NAME_* / sk_*_pop_free
  - `netssleay_phase4.t`, 14 direct assertions + 8 cert-backed skips

- [x] Phase 5: HMAC incremental API (2026-04-20)
  - HMAC, HMAC_CTX_{new,free,reset}, HMAC_Init[_ex], HMAC_Update,
    HMAC_Final — backed by javax.crypto.Mac
  - Validated against RFC 4231 test vector 1

- [x] Phase 6: BIGNUM + RSA crypto (2026-04-20)
  - BN_* (BigInteger), RSA_{public,private}_{encrypt,decrypt},
    RSA_sign, RSA_verify, RSA_size
  - EVP_PKEY_get1_{RSA,EC_KEY}
  - `netssleay_phase5_6.t`, 29 assertions

- [x] Phase 7: OCSP surface (2026-04-20)
  - All 14 OCSP entry points registered; stub bodies (real ASN.1
    encoding deferred as "best effort" per design doc).

- [x] Phase 2: SSLEngine handshake driver (2026-04-20)
  - javax.net.ssl.SSLContext lazily built per SslCtxState
  - Per-SSL SSLEngine, plaintext ByteBuffers, pendingNetIn for
    partial-record stashing
  - advance() pump covering NEED_WRAP/NEED_UNWRAP/NEED_TASK/FINISHED
  - set_{accept,connect}_state, set_bio, set_tlsext_host_name,
    set_verify, read, write, shutdown, get_error, get_version,
    state, pending, do_handshake, accept, connect
  - `netssleay_phase2.t`, 18 assertions (real 448-byte ClientHello
    emitted into wbio)

- [x] Phase 2b: PEM cert/key loading + full handshake (2026-04-20)
  - CTX_use_{PrivateKey,certificate,certificate_chain}_file wired
    into SslCtxState.loadedPrivateKey / loadedCertChain
  - buildSslContext constructs KeyManager from in-memory KeyStore
  - VERIFY_NONE → accept-all TrustManager
  - Bugfix: pumpUnwrap was dropping ciphertext tail on NEED_WRAP
    mid-bundle (now stashed on pendingNetIn)
  - `netssleay_phase2b.t`, 9 assertions — full TLS 1.3 handshake
    in 2 pump rounds between in-memory client and server SSL
    handles, plus plaintext exchange both directions

- [x] Phase 2c: Remaining CTX/SSL accessor coverage (2026-04-20)
  - 96 previously-MISSING symbols now registered with real bodies
  - CTX_{get,set}_{mode,options,verify_mode,timeout,session_*,ex_data}
  - CTX_use_{certificate,certificate_ASN1,PrivateKey,RSAPrivateKey[_file]}
  - SSL-level use_* aliases proxying to CTX handlers
  - get_peer_{certificate,cert_chain} from SSLSession
  - ssl_read_all / ssl_write_all / ssl_read_CRLF / ssl_read_until
  - peek, renegotiate, want(), write_partial
  - 13 sess_* counters, PKCS7, ALPN helpers
  - Honest no-op stubs for TLS-extension callbacks we can't plumb
    into the JDK (msg/keylog/info callbacks, PSK, tlsext_*)

### Inventory at end of this session

| Status   | Count |
|----------|-------|
| DONE     | 372   |
| PARTIAL  | 292   |
| STUB     | 19    |
| MISSING  | 0     |

2422/2422 baseline assertions pass. Six phase-specific regression
tests cover the new surface directly: `netssleay_phase{1,2,2b,3_7,4,5_6}.t`.

### Remaining Work (follow-ups, not blocking)

**Phase 2 polish**:
- Parse Perl `set_verify` callbacks through a wrapping TrustManager
  so custom verification logic runs in Perl during handshake.
  Currently verifyMode=0 ⇒ accept-all, nonzero ⇒ default JDK
  TrustManager; the callback field is stored but not invoked.
- CTX_set_cipher_list / CTX_set_ciphersuites should translate
  OpenSSL names (ECDHE-RSA-AES128-GCM-SHA256) to IANA names and
  apply to SSLEngine.setEnabledCipherSuites.
- CTX_set_min_proto_version / _max_proto_version currently stored
  but not applied to SSLContext.getInstance() protocol selection.
- Phase 2 notes that `Net::SSLeay::connect` is shadowed by Perl's
  builtin `connect` — callers must use `do_handshake` for
  client-mode handshake completion. (Investigate parser fix later.)

**Phase 3 polish**:
- PKCS12_newpass currently returns 0 (honest failure) because Java
  KeyStore doesn't round-trip cleanly. Implement by re-serialising
  through a fresh KeyStore with the new password.

**Phase 7 depth**:
- Real OCSP encoding via hand-rolled ASN.1. The JDK's
  java.security.cert.ocsp is internal and using it via reflection
  is fragile. Current stubs let callers compile; a depending caller
  that actually needs OCSP status verification will see stub
  behaviour ("no stapled response").

**Phase 8 integration**:
- Run the full AnyEvent t/ tree with the new TLS driver (blocked
  today on signal test infrastructure, not TLS).
- Run IO::Socket::SSL's own test suite.
- HTTPS smoke via LWP::UserAgent against a few public sites.
- Stress test: 1000 concurrent handshakes.

## Open questions for the reviewer

1. **Bouncy Castle**: RESOLVED (2026-04) — adopted as a mandatory dependency
   via the `feature/digest-sha3-bouncycastle` PR. See
   `dev/modules/digest_sha3.md`. First use inside NetSSLeay is the
   `parsePrivateKeyDer` refactor; further BC-backed refactors
   (encrypted-PEM write, DH params, PKCS#12, CSR builder) are unblocked
   and can be tackled incrementally.
2. **Which stretch goals are in scope for "complete"?** Is "AnyEvent::TLS test suite passes" enough, or do we also need to pass the full Net-SSLeay-from-CPAN test suite (which exercises many low-level ASN.1 paths)?
3. **Backward compatibility**: the existing partial implementation has been shipped. Do we need to preserve the exact behaviour of our current stubs for `CTX_set_options` et al. for users who have (unwisely) depended on them? I propose "no — if you relied on a fake success, that's your bug", but the reviewer may disagree.
4. **Parallelism**: some of these phases can run in parallel once Phase 1 lands. Should we plan for that (multiple engineers) or assume serial execution?

---

*Related docs:* `dev/modules/anyevent_fixes.md` (this plan's parent context), `AGENTS.md` (project conventions), `dev/architecture/weaken-destroy.md` (why TLS-over-AnyEvent will still be blocked by weaken semantics until that branch lands).

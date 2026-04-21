# Digest::SHA3 port + Bouncy Castle evaluation

## Motivation

`./jcpan -t Digest::SHA3` currently installs the CPAN distribution but 11 of 14
test files fail with `Undefined subroutine &Digest::SHA3::newSHA3` (and the
related one-shots `sha3_224_hex`, `sha3_256_hex`, …, `shake128`, `shake256`).
The CPAN module is an XS distribution — the `.pm` file just re-exports symbols
that are defined in C (`SHA3.xs`, `src/sha3.c`). PerlOnJava has no C toolchain,
so every XS entry point is undefined at runtime.

Unlike `Digest::SHA` / `Digest::MD5`, there is currently **no Java backend**
for `Digest::SHA3`:

```
src/main/java/org/perlonjava/runtime/perlmodule/
    DigestMD5.java        ported
    DigestSHA.java        ported
    (no DigestSHA3.java)  <- this plan

src/main/perl/lib/Digest/
    MD5.pm  SHA.pm  base.pm  file.pm
    (no SHA3.pm)          <- this plan
```

## Why a dependency is on the table

The fixed-length SHA-3 variants (`SHA3-224/256/384/512`) are in JDK 9+
(`MessageDigest.getInstance("SHA3-256")`), but three `Digest::SHA3` features
are **not expressible on stock JDK**:

1. **SHAKE128 / SHAKE256** — extendable-output functions. The `Digest::SHA3`
   tests exercise both (`t/bit-shake128.t`, `t/bit-shake256.t`) and expose the
   implementation's pseudo-algorithms `128000` / `256000` for long output. JDK
   has no SHAKE provider.
2. **Bit-level input (`add_bits`)** — the whole `t/bit-*.t` + `t/bitorder.t`
   suite feeds non-byte-aligned bit strings. `MessageDigest.update(byte[])` is
   byte-only; there is no JDK API for "append N bits then finalize".
3. **State serialization (`shadump` / `shaload`)** — round-tripping the Keccak
   sponge state through a text blob. `MessageDigest` hides internals behind a
   `clone()`; it cannot be externalized.

Writing a correct Keccak-f[1600] sponge + SHAKE XOF in Java by hand is
possible (~500 lines) but is a crypto-grade implementation we'd have to
maintain and test for FIPS compliance forever.

## Proposed dependency: Bouncy Castle

Maven Central coordinate: **`org.bouncycastle:bcprov-jdk18on:1.78.1`** (pure
Java, ~8 MB jar, MIT-style license). Already a de-facto standard in every
JVM crypto project.

Directly relevant classes under `org.bouncycastle.crypto.digests`:

| Class | Gives us |
|-------|----------|
| `SHA3Digest(bitLength)` | SHA-3 224/256/384/512 with SHA-3 domain-separation suffix. Also exposes `doUpdate(byte[] in, int off, long databitlen)` for bit-level input. |
| `SHAKEDigest(bitLength)` | SHAKE128 / SHAKE256 as XOFs. `doOutput(byte[] out, int off, int outLen)` supports arbitrary output length (covers the `128000`/`256000` pseudo-algorithms). |
| `KeccakDigest(bitLength)` | Raw Keccak sponge if we ever need the unpadded variant. |

All three implement `org.bouncycastle.util.Memoable` (`copy()` + `reset(Memoable)`),
which maps cleanly onto `shadup` / `shacopy` and gives us free state
serialization for `shadump` / `shaload` (we just hex-encode `state[long[25]]`,
`dataQueue`, `bitsInQueue`, `fixedOutputLength`).

---

## Does Bouncy Castle simplify other bundled modules?

I audited every class that imports `java.security.MessageDigest`,
`javax.crypto.*`, or hand-rolls ASN.1/PEM. The answer is **yes**, primarily
in `NetSSLeay.java`, and in one small code-quality fix in `Digest::SHA`.

### A. Large benefit: `NetSSLeay.java` (9281 lines)

`NetSSLeay.java` contains roughly **400 lines of hand-rolled ASN.1 / PEM /
PKCS encoding**, all of which is already flagged in
`dev/modules/netssleay_complete.md` as a judgment call about whether to adopt
Bouncy Castle (see Phase 3 and the "Open questions" section: _"Phase 3 PEM
work is ~3× simpler with BC"_).

Concrete places BC would collapse or delete code:

| Current hand-rolled code | BC replacement |
|--------------------------|----------------|
| `derSequence`, `derTag`, `derLength`, `derConcat` (~40 lines of ASN.1 DER primitives near line 4636) | `org.bouncycastle.asn1.DERSequence`, `DEROctetString`, `DERTaggedObject`, `ASN1OutputStream` |
| `wrapPkcs1InPkcs8` / `parsePrivateKeyDer` trial-and-error loop across `{RSA, EC, DSA, EdDSA}` (lines 4597–4625) | `PrivateKeyInfo.getInstance(ASN1Primitive.fromByteArray(der))` + `JcaPEMKeyConverter` — picks the algorithm from the AlgorithmIdentifier in one call. Also removes the fragile "wrap PKCS#1 in PKCS#8" path. |
| `parsePemPrivateKey` + custom encrypted-PEM handling (the `BEGIN RSA PRIVATE KEY` + `Proc-Type: 4,ENCRYPTED` / DEK-Info branch) | `PEMParser` + `JceOpenSSLPKCS8DecryptorProviderBuilder` / `JcePEMDecryptorProviderBuilder` — handles both the traditional SSLeay format and PKCS#8 encrypted form, including `aes-128-cbc`, `aes-256-cbc`, `des-ede3-cbc`, which we currently can't decrypt without platform-specific OpenSSL. |
| Phase 3 TODO: `PEM_write_bio_RSAPrivateKey` with traditional SSLeay encryption (line 8334: _"Helper: encrypt private key PEM with traditional SSLeay format"_) | `JcaMiscPEMGenerator` + `JcePEMEncryptorBuilder`. Currently "stubbed" behavior. |
| Phase 3 TODO: DH parameter PEM (comment at 1523: _"BEGIN DH PARAMETERS PEM block and a javax.crypto.spec"_) | `PEMParser` reads `DHParameter` directly into `javax.crypto.spec.DHParameterSpec`. |
| Phase 3 TODO: PKCS#12 with non-standard MACs (noted as "simplifies with BC" in `netssleay_complete.md` line 192) | `org.bouncycastle.pkcs.PKCS12PfxPdu`. |
| CSR building (`X509_REQ_sign` at 8551, `buildCsrAttributesDer` at 8613, `P_X509_copy_extensions` at 8922) | `org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder`. Our current implementation signs CSRs by hand-assembling DER. |
| CRL parsing (`X509_CRL` fields at lines 760+) | `org.bouncycastle.cert.X509CRLHolder`. |

**Estimated net delete**: ~500–800 LOC of DER glue, plus unlocking ~4 TODOs
that are currently blocked waiting on "should we adopt BC or not" (Phase 3
open question in `netssleay_complete.md`). The SSLEngine driver itself
(Phase 2, ~2000 lines) does **not** change — that's pure JDK and should stay
pure JDK.

### B. Small benefit: `DigestSHA.java`

- Currently uses JDK `MessageDigest.getInstance("SHA-256")` etc. — **stays
  unchanged**. BC would add no value here.
- **One bug would be fixable**: `add_bits` at line 179 silently truncates to
  the next whole byte (`truncateToNBits`) instead of appending the partial
  final byte through the SHA compression function. Real `Digest::SHA::add_bits`
  feeds bit-granular input. With BC we can swap the digest for
  `org.bouncycastle.crypto.digests.SHA256Digest` (which has
  `doUpdate(byte[], int, long databitlen)`) only when a partial-byte call is
  detected, and fall back to JDK otherwise. Optional follow-up, not required.
- `load()` at line 364 today just "recreates from algorithm name" and loses
  state (comment at line 339: _"Java's MessageDigest doesn't provide direct
  state serialization"_). BC's `Memoable` fixes this for SHA-1/-224/-256/-384/
  -512 the same way it fixes it for SHA-3. Currently-silent breakage —
  tests for `Digest::SHA` don't exercise it deeply.

### C. No benefit: `DigestMD5.java`, `DataUUID.java`, `operators/Crypt.java`

- **DigestMD5.java** — one algorithm, no bit input, no state dump. JDK covers
  everything. Leave as-is.
- **DataUUID.java** — uses `java.util.UUID` + `MessageDigest` for v3/v5. No BC
  dependency needed.
- **operators/Crypt.java** — implements Perl's `crypt()` using SHA-256 fallback.
  No BC dependency needed.

### D. Unlocks future CPAN ports (strategic)

Adopting BC is also the enabler for a family of currently-unportable CPAN XS
modules. None of these are in scope for this plan; listed so we only make the
"add BC yes/no" decision once:

| Module | What it needs | BC class |
|--------|---------------|----------|
| `Digest::Keccak` | Raw Keccak-f[1600] | `KeccakDigest` |
| `Digest::BLAKE2` (`blake2s`, `blake2b`) | BLAKE2 (not in JDK) | `Blake2bDigest`, `Blake2sDigest` |
| `Digest::BLAKE3` | BLAKE3 | `Blake3Digest` (since BC 1.76) |
| `Digest::HMAC_SHA3_*` | HMAC over SHA-3 | `HMac(new SHA3Digest(...))` |
| `Digest::CRC` / `Digest::Whirlpool` / `Digest::Tiger` | Legacy hashes not in JDK | `WhirlpoolDigest`, `TigerDigest`, etc. |
| `Crypt::CBC` backends (`Crypt::OpenSSL::AES`, `Crypt::Blowfish`, `Crypt::Twofish`, `Crypt::IDEA`) | Block ciphers beyond `javax.crypto` | `AESEngine`, `BlowfishEngine`, `TwofishEngine`, `IDEAEngine` |
| `Crypt::RSA` | PKCS#1 v1.5 / OAEP padding, raw RSA primitives | `RSAEngine`, `OAEPEncoding`, `PKCS1Encoding` |
| `Crypt::DSA`, `Crypt::Ed25519` | DSA/EdDSA signing & custom curve params | `DSASigner`, `Ed25519Signer` |
| `Crypt::JWT` / `Authen::SASL::SCRAM` | GCM AEAD, HKDF, PBKDF2-HMAC-SHA-3 | `GCMBlockCipher`, `HKDFBytesGenerator`, `PKCS5S2ParametersGenerator` |

---

## Decision

**Adopt Bouncy Castle as a runtime dependency.** The `netssleay_complete.md`
plan already flags this as an open question; this plan resolves it. Rationale:

- Unblocks `Digest::SHA3` cleanly.
- Deletes ~500 LOC of hand-rolled DER glue in `NetSSLeay.java` and closes
  4 flagged Phase 3 TODOs.
- Fixes two latent bugs in `DigestSHA.add_bits` and `DigestSHA.load`.
- Unlocks ~10 future CPAN crypto modules without a second dependency debate.
- ~8 MB jar cost. We already ship ICU4J (~12 MB) and ASM (~0.7 MB) and the
  `jperl` fat-jar is well north of 30 MB, so the marginal bloat is acceptable.

---

## Plan — single PR

All work below lands in **one feature branch / one PR**. The "phase"
structure is retained only as a logical ordering for the implementation; there
are no intermediate commits to separate PRs.

### Step 1 — Add dependency

- [ ] Add to `gradle/libs.versions.toml`:
  ```toml
  bouncycastle = "1.78.1"
  bcprov = { module = "org.bouncycastle:bcprov-jdk18on", version.ref = "bouncycastle" }
  bcpkix = { module = "org.bouncycastle:bcpkix-jdk18on", version.ref = "bouncycastle" }
  ```
- [ ] `build.gradle`: `implementation libs.bcprov` **and** `implementation libs.bcpkix`.
- [ ] Run `make` — expect zero behavior change at this point, just a bigger jar.

### Step 2 — Port `Digest::SHA3`

- [ ] Create `src/main/java/org/perlonjava/runtime/perlmodule/DigestSHA3.java`,
      modelled on `DigestSHA.java`. Wraps `SHA3Digest` or `SHAKEDigest`
      behind `newSHA3($alg)`:
  - `$alg ∈ {224, 256, 384, 512}` → `new SHA3Digest(alg)`
  - `$alg ∈ {128, 256}` with XOF mode → `new SHAKEDigest(alg)`
  - `$alg ∈ {128000, 256000}` → `SHAKEDigest` with fixed output length 16000 bytes
- [ ] Implement XS-facing primitives:
  `shainit`, `sharewind`, `shawrite` (uses `doUpdate(..., long databitlen)`),
  `shafinish`, `shadigest`, `shahex`, `shabase64`, `shacopy`, `shadup`,
  `algorithm`, `shadsize`, `shaclose`.
- [ ] Implement `shadump` / `shaload` by serializing `state[long[25]]`,
      `dataQueue`, `bitsInQueue`, `fixedOutputLength` via `Memoable.copy()`
      and hex encoding. Round-trip test against BC's own `copy()`.
- [ ] Implement one-shot functions: `sha3_{224,256,384,512}[_hex|_base64]`,
      `shake{128,256}[_hex|_base64]`.
- [ ] Create `src/main/perl/lib/Digest/SHA3.pm` — derived from the CPAN `.pm`
      file with `XSLoader::load('Digest::SHA3')` replaced by loading the Java
      module, mirroring `src/main/perl/lib/Digest/SHA.pm`.
- [ ] Register in `ModuleBootstrap` (or wherever `DigestSHA`/`DigestMD5` are
      wired up — TBD during implementation).
- [ ] Ensure the shim is copied into `build/resources/main/lib/Digest/SHA3.pm`
      via the existing Gradle resources copy.

### Step 3 — Acceptance tests

- [ ] `./jcpan -t Digest::SHA3` must pass all 14 test files:
  - `t/allfcns.t`, `t/sha3-{224,256,384,512}.t`, `t/bit-sha3-{224,256,384,512}.t`,
    `t/bit-shake{128,256}.t`, `t/bitorder.t`, `t/pod.t`
- [ ] Add a tiny smoke test in `src/test/resources/...` that pins expected
      digests for standard NIST test vectors, so we catch regressions
      independently of CPAN install churn.
- [ ] Run `make` — no regressions in bundled tests.

### Step 4 — `Digest::SHA` improvements (DEFERRED)

Investigated during implementation and deferred:

- **Bit-level `add_bits` fix**: Bouncy Castle's SHA-1 / SHA-2 digests do
  **not** expose a bit-level absorb API (unlike `KeccakDigest`). SHA-2 by
  spec treats input as a byte stream with an explicit total bit length, and
  BC doesn't expose the internal block-processing state. Implementing true
  bit-level input for SHA-2 would require either reflection hacks into BC
  internals or a hand-rolled SHA-2 compression loop — both bigger than the
  fix is worth, given no CPAN bundled tests currently exercise this code
  path. Left as a follow-up for a dedicated PR.
- **`getstate` / `putstate` round-trip**: Java's `MessageDigest` hides its
  internal state, and BC's SHA-2 `Memoable.copy()` produces a Java object,
  not a serializable string. Shipping a text-format state dump that
  round-trips would require reflection on BC private fields. Same follow-up.

### Step 5 — NetSSLeay DER refactor

- [ ] Replace `derSequence` / `derTag` / `derLength` / `derConcat` with BC's
      `org.bouncycastle.asn1.*`.
- [ ] Replace `parsePrivateKeyDer` / `wrapPkcs1InPkcs8` / `parsePemPrivateKey`
      with `PEMParser` + `JcaPEMKeyConverter`.
- [ ] Update `dev/modules/netssleay_complete.md` — resolve the "Bouncy
      Castle yes/no" open question and mark dependencies as unblocked.

### Out of scope for this PR (future work)

- Encrypted-PEM write path with traditional SSLeay format (currently stubbed).
- DH parameter PEM parsing.
- PKCS#12 with non-standard MACs.
- CSR builder rewrite via `JcaPKCS10CertificationRequestBuilder`.
- New CPAN crypto ports (`Digest::Keccak`, `Digest::BLAKE2`, `Crypt::*`, …).

These are deferred to keep the PR focused: Step 5 only replaces existing,
tested code paths with BC equivalents (refactor, not new features).

---

## Risks

- **Reproducibility of digests at byte-granular input**: BC SHA-3 must match
  JDK SHA-3 bit-for-bit. Cross-checked by the NIST KATs in the CPAN test
  suite, so any mismatch surfaces in Phase 3.
- **Jar size**: +8 MB for `bcprov`, +3 MB for `bcpkix` if we pull it in Phase
  5. If this is unacceptable we can:
  - Use `jlink` / `jdeps --ignore-missing-deps` to strip unused BC packages
    (BC ships most algorithms; we only use a handful).
  - Or pull only `bcprov` now (covers Phase 2) and defer `bcpkix` to the
    NetSSLeay refactor PR so each PR owns its own size impact.
- **FIPS**: `bcprov-jdk18on` is **not** a FIPS-certified build. If FIPS ever
  becomes a requirement we switch to `bc-fips`. Not a concern today.
- **License**: MIT-style, compatible with PerlOnJava's Apache 2.0.

---

## Progress Tracking

### Current Status: IMPLEMENTED (2026-04-21) — all Steps 1–5 landed on `feature/digest-sha3-bouncycastle`

### Completed
- [x] Step 1: Bouncy Castle dependency (`bcprov-jdk18on` + `bcpkix-jdk18on` v1.78.1)
      in `gradle/libs.versions.toml` and `build.gradle`.
- [x] Step 2: `src/main/java/org/perlonjava/runtime/perlmodule/DigestSHA3.java`
      — BC-backed `Keccak` wrapper with bit reservoir for multi-call
      non-byte-aligned `add_bits`, custom SHA-3 / SHAKE domain-separator
      handling. Registered all XS primitives plus 18 one-shot functions.
      No Perl shim needed: the unmodified CPAN `lib/Digest/SHA3.pm` calls
      `XSLoader::load('Digest::SHA3')` which dispatches to our Java module
      via `XSLoader.java`.
- [x] Step 3: `./jcpan -t Digest::SHA3` passes all 14 test files (33 subtests),
      including the bit-level tests (`t/bit-sha3-{224,256,384,512}.t`,
      `t/bit-shake{128,256}.t`, `t/bitorder.t`).
- [x] Step 4: DEFERRED with explicit rationale in the doc — BC does not
      expose bit-level primitives for SHA-2, so the `Digest::SHA.add_bits`
      partial-byte fix would need reflection or a hand-rolled SHA-2
      compression loop. Low impact; no bundled tests exercise it today.
- [x] Step 5: `parsePrivateKeyDer` in `NetSSLeay.java` refactored to use
      `PrivateKeyInfo.getInstance` + `JcaPEMKeyConverter` (auto-detects
      RSA/EC/DSA/Ed25519/Ed448 from the DER AlgorithmIdentifier). Deleted
      `wrapPkcs1InPkcs8` + its 4 hand-rolled DER builders for that path.
      The remaining hand-rolled DER code (CSR builder, X509 extensions,
      RDNs, SAN encoding) stays: it's extensively used and has full test
      coverage; a mechanical BC port is a separate follow-up PR.
- [x] Verification: `make` green (all unit tests). `prove -e ./jperl
      src/test/resources/unit/netssleay_*.t` → 2553 tests, all pass.
      `./jcpan -t Digest::SHA3` → 33/33.
- [x] `dev/modules/netssleay_complete.md`: resolved the BC open question,
      updated the runtime-dependencies section.

### Out of scope (future PRs)
- Encrypted-PEM write path via `JcaMiscPEMGenerator` + `JcePEMEncryptorBuilder`
  (today: manual SSLeay-format traditional encryption, currently stubbed).
- Replace CSR builder / X509 extension DER with `JcaPKCS10CertificationRequestBuilder`
  and BC ASN.1 primitives.
- DH parameter PEM via `PEMParser`.
- PKCS#12 with non-standard MACs via `PKCS12PfxPdu`.
- Bit-level `Digest::SHA.add_bits` fix (Step 4) and `getstate`/`putstate`
  round-trip via reflection on BC internals.
- New CPAN crypto ports now unblocked: `Digest::Keccak`, `Digest::BLAKE2`,
  `Digest::BLAKE3`, `Digest::HMAC_SHA3_*`, `Crypt::OpenSSL::AES`,
  `Crypt::CBC` backends, `Crypt::JWT`.

### Related
- `dev/modules/netssleay_complete.md` — "Bouncy Castle" open question
  resolved by this PR.
- `.agents/skills/port-native-module/SKILL.md` — the Step 2 implementation
  follows that skill.

# CryptX Java XS Port

## Current state

The first functional API slice is implemented on branch
`feature/cryptx-java-bridge`, based on master commit `d76b95e4093b24d9af782a69b2619a52d0b5ae6c`.
It retains a pure-Perl `CryptX.pm` bootstrap and implements selected XS entry
points in `CryptX.java` using the project's existing JCA and Bouncy Castle
dependencies. PR #1220 tracks the broader port.

This slice covers `Crypt::Misc` binary/base64/base64url helpers, octet
increment, radix conversion, and equality; `Crypt::Digest` functional calls;
and `Crypt::Mac::HMAC` functional calls. Digest and HMAC algorithm support is
limited to names with a matching JCA or Bouncy Castle provider. Unsupported
names raise Perl runtime exceptions. Stateful digest/MAC objects, cipher and
mode families, public-key APIs, AEAD APIs, and remaining CryptX entry points
are follow-on work.

## Decisions

- Preserve the CPAN Perl wrappers and replace the XS boundary in Java; do not
  duplicate the wrappers or translate LibTomCrypt's C implementation
  mechanically.
- Reuse JCA and the already bundled Bouncy Castle provider. Keep provider
  failures explicit and retain Perl-compatible `undef` behavior for malformed
  base64 input.
- Add coverage first for functional APIs that directly support JWT-style
  consumers. Validate focused tests against native CryptX before relying on
  them under PerlOnJava.

## Progress

| Phase | Delivered scope | Evidence | Status |
|---|---|---|---|
| 1 | CryptX bootstrap; binary/base64url helpers; functional digests and HMAC | System Perl: 12/12 focused assertions. PerlOnJava: focused bundled-module test passed. `nice -n 19 make`: passed. | In review |

## Next work

Implement stateful digest and HMAC APIs, then add RSA signing/verification by
reusing `Crypt::OpenSSL::RSA` key parsing and signing paths. Expand to ECC/ECDH
and common AEAD APIs after the first downstream HS/RS JWT acceptance tests.

## References

- [CryptX port issue #1220](https://github.com/fglock/PerlOnJava/issues/1220)
- [XS compatibility inventory](../../docs/reference/xs-compatibility.md)
- [CPAN module porting guide](../../docs/guides/module-porting.md)

# Net::Async::HTTP UAT Blockers

**Status:** Implemented; stacked validation pending on PR #1204

## Scope

The Net::Async::HTTP 0.50 UAT run identified two compatibility failures that
are separate from the refcount-owner ledger work:

1. `HTTP::Cookies` sent `Cookie2: \\$Version="1"`, retaining a literal
   escape before the RFC 2965 version marker.
2. `Compress::Raw::Zlib::Deflate` accepted `WANT_GZIP` but emitted raw deflate
   bytes without the required RFC 1952 header and trailer. Net::Async::HTTP's
   content decoder correctly rejected those bytes as an invalid gzip header.

## Completed Work

- [x] Corrected the Cookie2 literal in `src/main/perl/lib/HTTP/Cookies.pm`.
- [x] Added gzip header/trailer emission, CRC32, size bookkeeping, and reset
  handling to `CompressRawZlib` for `WANT_GZIP` streams.
- [x] Added system-Perl-validated regressions:
  - `unit/http_cookies_cookie2.t`
  - `unit/compress_raw_zlib_gzip_wrapper.t`
- [x] Both regressions pass on JVM and interpreter; full `make` passes.

## Next Steps

1. Stack this branch on PR #1204, which supplies the socket `fileno` support
   required for Net::Async::HTTP's upstream integration tests.
2. Rerun `t/09cookies.t` and `t/18content-coding.t` on that stacked artifact.
3. Open a separate PR after the parent branch is merged or use #1204 as its
   temporary base.

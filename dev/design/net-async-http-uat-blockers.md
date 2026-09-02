# Net::Async::HTTP UAT Blockers

**Status:** Implemented and validated; PR #1213 is based on `master` and ready
for its final CI gate.

## Scope

The Net::Async::HTTP 0.50 UAT run identified three compatibility failures that
are separate from the refcount-owner ledger work:

1. `HTTP::Cookies` sent `Cookie2: \\$Version="1"`, retaining a literal
   escape before the RFC 2965 version marker.
2. `Compress::Raw::Zlib::Deflate` accepted `WANT_GZIP` but emitted raw deflate
   bytes without the required RFC 1952 header and trailer. Net::Async::HTTP's
   content decoder correctly rejected those bytes as an invalid gzip header.
3. The bundled `Compress::Bzip2` lacked its streaming
   `bzinflateInit`/`inflateInit` and `bzdeflateInit` facade over the bundled
   raw bzip2 implementation, preventing Net::Async::HTTP from decoding bzip2
   content.

## Completed Work

- [x] Corrected the Cookie2 literal in `src/main/perl/lib/HTTP/Cookies.pm`.
- [x] Added gzip header/trailer emission, CRC32, size bookkeeping, and reset
  handling to `CompressRawZlib` for `WANT_GZIP` streams.
- [x] Added `Compress::Bzip2` streaming deflate and inflate facades, including
  the `bzinflate`, `inflate`, and `bzerror` stream methods required by the
  CPAN API.
- [x] Added system-Perl-validated regressions:
  - `unit/http_cookies_cookie2.t`
  - `unit/compress_raw_zlib_gzip_wrapper.t`
  - `unit/compress_bzip2_streaming_writer.t`
  - `unit/compress_bzip2_streaming_inflater.t`
- [x] All four regressions pass on JVM and interpreter; full `make` passes.
- [x] Net::Async::HTTP `t/09cookies.t` and `t/18content-coding.t` pass on the
  stacked artifact; the latter passes all gzip, deflate, and bzip2 assertions
  on both backends.
- [x] Full `jcpan -t Net::Async::HTTP` validation passes on the stacked
  artifact: 41 files and 554 tests, with only the expected optional and
  platform-specific skips.

## Next Steps

1. Wait for the `master`-targeted CI checks for PR #1213, then merge it.
2. The prerequisite socket `fileno` support was delivered by PR #1204, merged
   as `e20a49bdcd06f1a42fc0a30492be6f414b0e8111`.

## Open Questions

None.

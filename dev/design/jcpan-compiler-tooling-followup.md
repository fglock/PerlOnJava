# jcpan compiler and tooling follow-up

## Goal

Remove shared PerlOnJava compiler/runtime/tooling blockers encountered while testing Data::Checks, POE::Component::MessageQueue, Net::Server::POP3::Skeleton, Mail::BIMI, Imager::Album, Test::Mimic::Recorder, Fuse::Filesys::Virtual, and their dependencies. Distribution preferences are deliberately avoided.

## Implementation

### Compiler and runtime semantics

- Tied `@ISA` values are fetched through the tie interface during method resolution.
- Constant subroutines preserve their scalar-reference stash proxy without losing the callable CODE slot.
- `use open` defaults are read from lexical `%^H` call-site snapshots instead of leaking through a process-global `${^OPEN}` value.

### Bundled module tooling

- `DynaLoader::dl_load_flags` is available to XS-style loaders.
- HTTP::Tiny installs each convenience verb once and returns its standard status-599 response for transport failures.
- Time::HiRes provides the requested `ualarm` export.
- XML::LibXML::RelaxNG uses Jing for schema compilation and validation.
- Sereal::Encoder and Sereal::Decoder use the official Booking.com Java codec with Snappy and Zstandard support.
- Cache::FastMmap has a JVM-backed compatibility implementation and preserves the distribution's Perl serialization, expiry, and callback layer. Its map is process-local; the share file is created for API compatibility.
- Crypt::OpenSSL::X509 and Crypt::OpenSSL::Verify use the existing Bouncy Castle/JCA stack rather than adding another crypto implementation.

The imported Sereal Java sources are based on upstream commit `9ad81cf3023ccc456c2accd83bea2c2803a82e16`.

## System-Perl exclusions

The following are not treated as PerlOnJava regressions because their current distributions or tests fail under the available system Perl or require unavailable platform facilities:

- Data::Checks: its fresh system-Perl dependency set lacks `builtin.pm`.
- Net::Server::POP3::Skeleton: the release metadata points `VERSION_FROM` at a nonexistent `lib/Tk/Carp.pm`.
- Imager::Album: its system-Perl dependency closure lacks Imager and relies on the legacy Gtk/display stack.
- Test::Mimic::Recorder: its own test fails on system Perl with a hard-coded reference-history assumption.
- Fuse::Filesys::Virtual: Fuse 0.16 refuses to configure on Darwin without OSXFUSE.
- POE::Component::MessageQueue: its remaining bind test cannot run in this sandbox; a minimal system-Perl bind fails with the same platform restriction.

## Progress Tracking

### Current Status: implementation and local verification complete

### Completed Phases

- [x] Phase 1: classify upstream/system failures (2026-08-15)
  - Reproduced each exclusion with system Perl or the relevant platform configuration step.
- [x] Phase 2: compiler/runtime fixes (2026-08-15)
  - Fixed tied inheritance, constant stash proxies, and lexical open-layer handling.
- [x] Phase 3: reusable Java module bridges (2026-08-15)
  - Added Sereal, cache, OpenSSL/X509, and RelaxNG support using existing or upstream Java libraries.
- [x] Phase 4: regression coverage (2026-08-15)
  - Added focused unit tests and validated new Perl semantics with system Perl before PerlOnJava.
- [x] Phase 5: final verification (2026-08-15)
  - Full `make` passed.
  - Mail::BIMI passed all 31 test programs and 82 assertions; network- and author-only tests skipped as expected.

### Next Steps

1. Open the pull request.
2. Verify CI.

### Open Questions

- A future Cache::FastMmap implementation could provide true cross-process mmap sharing; current requested tests only require same-process behavior.

## References

- [Sereal](https://github.com/Sereal/Sereal)
- [Jing and Trang](https://github.com/relaxng/jing-trang)
- Skills: `debug-perlonjava`, `port-cpan-module`, `port-native-module`

# PerlIO::via Module Notes

The canonical implementation design now lives in
[`dev/design/perlio-via.md`](../design/perlio-via.md).

This file is kept for module-porting notes and CPAN target status only. Do not
duplicate the runtime architecture here.

## Current Status

- `src/main/perl/lib/PerlIO/via.pm` is a loading-only stub.
- `LayeredIOHandle` deliberately fails at runtime for `:via(...)` layers.
- `./jcpan -t PerlIO::via::Timeout` currently passes because a bundled CPAN
  patch skips the unsupported runtime socket test under `jperl`.
- Functional runtime support remains pending; see the design doc for the v1
  implementation phases.

## CPAN Workaround

`PerlIO-via-Timeout-0.32` is patched during `jcpan`:

- `t/00-compile.t` still runs.
- `t/01_socket_timeout.t` skips under PerlOnJava until `:via(...)` runtime
  dispatch exists.
- `Test::TCP` is removed from `build_requires` because the skipped runtime test
  no longer uses it and it otherwise pulls in fork-only `Test::SharedFork`
  tests.

Remove or narrow this workaround after `binmode($fh, ":via(Timeout)")` works.

## Verification Baseline

Last known verification after the workaround:

```text
timeout 600 ./jcpan -t PerlIO::via::Timeout
Result: PASS
t/00-compile.t ok
t/01_socket_timeout.t skipped: PerlOnJava does not implement :via(...) PerlIO layers yet
```

## Related

- Design: `dev/design/perlio-via.md`
- Distropref: `src/main/perl/lib/PerlOnJava/CpanDistroprefs/PerlIO-via-Timeout.yml`
- CPAN patch:
  `src/main/perl/lib/PerlOnJava/CpanPatches/PerlIO-via-Timeout-0.32/SkipViaRuntimeTest.patch`

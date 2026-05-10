# OpenAI::API jcpan Plan

## Current Status

`./jcpan -t OpenAI::API` is blocked in dependency installation rather than in
OpenAI::API itself. The class/parser/runtime fixes now let the run progress
past `Struct::Dumb` and `Test::Metrics::Any`. The active blocker is
`IO::Async`; the suite now parses and runs far enough to exercise sockets,
weak-reference/refcount behavior, notifier lifetimes, stream decoding,
metrics, signal delivery, Routine/Function no-fork paths, and connect/listen
behavior.

## Completed

- Reproduced the failure with `timeout 1800 ./jcpan -t OpenAI::API`.
- Fixed class `method` parsing so attributes like `:lvalue` are accepted on
  named and lexical methods.
- Fixed method lookup for classes generated under `main::Name`, so method
  dispatch also finds equivalent `Name` package entries.
- Fixed `field $x :param(bla.foo)` parsing by accepting raw parenthesized
  attribute parameters.
- Fixed generated class constructors so the external `:param(name)` key is
  distinct from the internal field storage key.
- Enforced Perl `:lvalue` semantics for assignments through subroutine,
  method, and code-reference calls.
- Fixed anonymous `method :lvalue { ... }` parsing and self injection for
  Struct::Dumb-style generated accessors.
- Fixed special-block capture emission inside classes so class field symbols
  are not emitted as invalid `our field:...` declarations.
- Fixed method lookup for digit-named generated accessors such as method `"1"`.
- Added unit coverage for class lvalue methods, `main::` qualified class
  dispatch, dotted `:param(...)`, and Struct::Dumb-style generated lvalue
  accessors.
- Verified the Struct::Dumb-focused fixes with `make` and
  `Struct::Dumb`'s `Build test`.
- Fixed `caller()` line reporting for multi-line calls to prototyped
  subroutines whose prototype begins with `&` and includes following ordinary
  arguments, matching Perl's line selection for `(&$)` and `(&@)`.
- Added focused unit coverage for prototyped block-call caller locations.
- Verified `Test::Metrics::Any` with its `Build test`.
- Fixed parsing and normalization for `push`/`unshift` applied to
  parenthesized lexical/package/local array declarations, such as
  `push our(@A), $value`.
- Added focused unit coverage for `push my(@A)`, `push our(@A)`, and
  `push local(@A)`.
- Added Socket support for `SO_ACCEPTCONN`, `SO_TYPE`, `IN6ADDR_ANY`, and
  `IN6ADDR_LOOPBACK`, including tracked socket type metadata and listening
  socket detection for `getsockopt`.
- Fixed IPv6 `Socket::inet_ntop` formatting so loopback and all-zero addresses
  match Perl's compressed text form.
- Fixed weak-reference probe copies so temporary weakened aliases do not clear
  weak refs while a strong lexical reference still exists.
- Fixed `goto &sub` tail-call cleanup so arguments handed to the trampoline are
  released after each call instead of leaking through repeated notifier
  dispatches.
- Added missing Socket/POSIX compatibility exports used by IO::Async:
  `Socket::EAI_FAIL`, `POSIX::EWOULDBLOCK`, `POSIX::ECONNRESET`, and
  `POSIX::nice`.
- Added `POSIX::EINPROGRESS` constant generation/export so
  `use POSIX qw(EINPROGRESS)` installs the unqualified constant used by
  `IO::Async::Internals::Connector`.
- Fixed SKIP-block hash/block disambiguation for `skip "reason", $n if ...`
  followed by method calls whose named arguments include keyword-like values
  such as `model => "fork"`.
- Added IO::Async CPAN distroprefs (`.yml` plus `.dd` fallback) that set
  `IO_ASYNC_NO_FORK=1` during its test phase. PerlOnJava's `fork`
  intentionally returns undef, and IO::Async already uses this environment
  variable to skip POSIX-fork-dependent tests.
- Added an IO::Async source patch for the no-fork test path so
  `IO::Async::Routine` loads without a compile-time fork/thread model and the
  two raw-fork process tests skip when `HAVE_POSIX_FORK` is false.
- Moved the IO::Async no-fork source patch into
  `PerlOnJava/CpanPatches/IO-Async-0.805/NoFork.patch` and taught CPAN patch
  bootstrapping to refresh stale bundled patch copies, avoiding malformed
  inline patch heredocs in `CPAN::Config`.
- Fixed readline on `socketpair` handles backed by `SocketChannel`, allowing
  IO::Async readiness callbacks to read line-oriented data from socketpair
  handles.
- Added connected UDP `socketpair` support for `send`/`recv` and
  `sysread`/`syswrite`, including nonblocking `EAGAIN` handling and peer
  sockaddr reporting for datagram socketpairs.
- Extended the IO::Async no-fork patch so Routine/Function/Resolver tests that
  require a fork/thread worker model skip cleanly when neither model is
  available.
- Added connected UDP `connect()` support and mapped blocking
  `ConnectException` to `ECONNREFUSED`, allowing IO::Async UDP connect and
  refused-connect error tests to match their own baseline.
- Closed `DatagramChannel` handles correctly and added socketpair peer-error
  readiness for connected UDP pairs, clearing the exceptional-socket checks in
  `t/10loop-poll-io.t` and `t/10loop-select-io.t`.
- Fixed lazy named-sub capture metadata so package subs that close over file
  lexicals expose those scalar captures to the weak-ref reachability rescue.
  This prevents Test::Refcount-style weak probe copies from firing `DESTROY`
  while a captured file lexical still holds the object.
- Fixed `Scalar::Util::refaddr` for named glob references so repeated
  references such as `\*STDIN` report the same stable glob identity. This
  clears the IO::Async stream stdio handle identity checks that use Test2's
  reference comparison.
- Fixed `Encode::STOP_AT_PARTIAL` decoding so complete UTF-8 characters are
  returned while trailing incomplete bytes remain in the source scalar for the
  next decode call. This clears IO::Async's partial UTF-8 stream decoding test.
- Fixed weak-ref zero-count rescue for JVM-live scalar registry holders so
  Test2::Tools::Refcount probe copies do not clear IO::Async FileStream's
  weak callback captures while the caller's lexical still holds the object.
  This clears `t/28filestream.t`, including filename-following and rename
  readiness.
- Fixed scalar-lvalue `undef` for JVM and bytecode backends so
  `undef $array->[idx]`, `undef $hash->{key}`, and coderef scalar slots clear
  to real undef values while `undef &sub` keeps the code-slot path.
- Added focused unit coverage for Socket options, IPv6 constants, weak probe
  copies, captured-lexical weak probe copies, tail-call argument cleanup,
  SKIP-block disambiguation, scalar-lvalue `undef`, and the new Socket/POSIX
  constants.
- Verified `IO::Async` focused tests `t/02os.t` and `t/04notifier.t` now pass
  under escalated runs.

## Active Fixes

1. `IO::Async` is the active dependency blocker after `Struct::Dumb` and
   `Test::Metrics::Any`.
2. The parser no longer rejects `push our(@CARP_NOT), ...`, which allowed the
   `IO::Async` suite to start running its tests.
3. `IO::Async` has improved from 46 failing files after the first socket pass to
   7 failing files after weak-reference, tail-call cleanup, constant export,
   SKIP-block parser, no-fork distroprefs, socketpair readline, scalar `undef`
   lvalue, connected datagram socketpair, no-fork Routine/Function skips, UDP
   connect, datagram peer-error readiness, named-sub capture metadata, stable
   named-glob `refaddr`, `Encode::STOP_AT_PARTIAL`, and live-scalar weak-probe
   rescue fixes. The latest escalated run is
   `/tmp/jcpan_io_async_after_live_scalar_escalated.log`: `Failed 7/64 test
   programs. 16/1134 subtests failed`.
4. The previous missing constant errors are cleared. `t/24listener.t`,
   `t/25socket.t`, `t/61protocol-stream.t`, `t/62protocol-linestream.t`,
   `t/63handle-connect.t`, and `t/64handle-bind.t` now pass in the latest full
   checkpoint; `t/02os.t`, `t/10loop-poll-io.t`, `t/10loop-select-io.t`,
   `t/16loop-poll-metrics.t`, `t/16loop-select-metrics.t`, `t/20handle.t`,
   `t/25socket.t`, and `t/51loop-connect.t` pass when run focused.
5. Process/fork tests now skip under `IO_ASYNC_NO_FORK` plus the IO::Async
   no-fork patch. Routine/Function/Resolver tests that require a fork/thread
   worker model also skip when no model is available.
6. Signal, metrics, stream write, stream stdio identity, stream encoding, and
   file stream tests are now green in the latest full checkpoint. The remaining
   short-term blockers are notifier/test/protocol refcounts plus two stream
   refcount edge cases.

## Verification Targets

1. Reduced class-parser reproductions pass under `./jperl`.
2. `Struct::Dumb`'s `Build test` passes from the local CPAN build directory.
3. Reduced `caller()` reproductions for `(&$)`, `(&@)`, and `(&)` match system
   Perl.
4. `Test::Metrics::Any`'s `Build test` passes from the local CPAN build
   directory.
5. Focused `push` declaration-array unit tests pass under `make`.
6. Socket/POSIX constant smoke tests pass under `./jperl`.
7. `IO::Async` focused socket tests `t/02os.t`, `t/10loop-poll-io.t`,
   `t/10loop-select-io.t`, and `t/25socket.t` pass under escalated runs;
   `t/04notifier.t` also passes under escalated runs.
8. `IO::Async` parser checks for `t/41routine.t`, `t/42function.t`, and
   `t/51loop-connect.t` pass under `./jperl --parse`.
9. `IO::Async`'s `Build test` either passes or has remaining failures reduced
   to documented PerlOnJava gaps. Current checkpoint:
   `/tmp/jcpan_io_async_after_live_scalar_escalated.log`.
10. `make` passes.
11. `timeout 1800 ./jcpan -t OpenAI::API` progresses past `IO::Async`; any
   subsequent dependency failures should be documented here before fixing.

## Next Steps

- Investigate the notifier/test/protocol refcount mismatches that remain in
  `t/05*`, `t/06*`, `t/07*`, `t/19test.t`, `t/21stream-1read.t`,
  `t/21stream-3split.t`, and `t/60protocol.t`.
- Rerun `IO::Async` and then `OpenAI::API`.
- Keep PR #702 updated from the feature branch. After the OpenAI::API path is
  stable, evaluate bundling YAML so CPAN can read `.yml` distroprefs directly;
  keep using `.dd` fallbacks for this fix.

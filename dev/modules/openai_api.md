# OpenAI::API jcpan Plan

## Current Status

`./jcpan -t OpenAI::API` is blocked in dependency installation rather than in
OpenAI::API itself. The class/parser/runtime fixes now let the run progress
past `Struct::Dumb` and `Test::Metrics::Any`. The active blocker is
`IO::Async`; the suite now parses and runs far enough to exercise sockets,
weak-reference/refcount behavior, notifier lifetimes, stream decoding, POSIX
and Socket constants, and a few remaining parser constructs.

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
- Added focused unit coverage for Socket options, IPv6 constants, weak probe
  copies, tail-call argument cleanup, SKIP-block disambiguation, and the new
  Socket/POSIX constants.
- Verified `IO::Async` focused tests `t/02os.t` and `t/04notifier.t` now pass
  under escalated runs.

## Active Fixes

1. `IO::Async` is the active dependency blocker after `Struct::Dumb` and
   `Test::Metrics::Any`.
2. The parser no longer rejects `push our(@CARP_NOT), ...`, which allowed the
   `IO::Async` suite to start running its tests.
3. `IO::Async` has improved from 46 failing files after the first socket pass to
   36 failing files after the weak-reference, tail-call cleanup, constant
   export, and SKIP-block parser fixes. The latest run is
   `/tmp/io_async_after_skip_einprogress.log`: `Failed 36/64 test programs.
   40/969 subtests failed`.
4. The previous missing constant errors are cleared. `t/63handle-connect.t` now
   passes, and the previous parser errors in `t/41routine.t`, `t/42function.t`,
   and `t/51loop-connect.t` are gone.
5. The remaining short-term blockers are runtime gaps around signal/process
   tests, stream UTF-8 decoding, stdio handle identity, socket/listener readiness
   and sockname behavior, and notifier/future/protocol refcounts.

## Verification Targets

1. Reduced class-parser reproductions pass under `./jperl`.
2. `Struct::Dumb`'s `Build test` passes from the local CPAN build directory.
3. Reduced `caller()` reproductions for `(&$)`, `(&@)`, and `(&)` match system
   Perl.
4. `Test::Metrics::Any`'s `Build test` passes from the local CPAN build
   directory.
5. Focused `push` declaration-array unit tests pass under `make`.
6. Socket/POSIX constant smoke tests pass under `./jperl`.
7. `IO::Async` focused socket and notifier tests `t/02os.t` and
   `t/04notifier.t` pass under escalated runs.
8. `IO::Async` parser checks for `t/41routine.t`, `t/42function.t`, and
   `t/51loop-connect.t` pass under `./jperl --parse`.
9. `IO::Async`'s `Build test` either passes or has remaining failures reduced
   to documented PerlOnJava gaps.
10. `make` passes.
11. `timeout 1800 ./jcpan -t OpenAI::API` progresses past `IO::Async`; any
   subsequent dependency failures should be documented here before fixing.

## Next Steps

- Investigate the IO::Async process/fork failures; many currently report
  `Cannot fork() - Resource temporarily unavailable`, which may be a missing
  PerlOnJava process primitive, handle exhaustion, or leaked process state from
  earlier tests.
- Fix the remaining stream/socket blockers: stdio handle identity,
  partial UTF-8 decoding, listener `sockname`, and connect readiness.
- Investigate the notifier/future/protocol refcount mismatches that remain in
  `t/05*`, `t/06*`, `t/07*`, `t/21*`, `t/28*`, and `t/60protocol.t`.
- Rerun `IO::Async` and then `OpenAI::API`.
- Keep PR #702 updated from the feature branch.

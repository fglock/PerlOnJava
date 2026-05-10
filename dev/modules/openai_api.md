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
- Added focused unit coverage for Socket options, IPv6 constants, weak probe
  copies, tail-call argument cleanup, and the new Socket/POSIX constants.
- Verified `IO::Async` focused tests `t/02os.t` and `t/04notifier.t` now pass
  under escalated runs.

## Active Fixes

1. `IO::Async` is the active dependency blocker after `Struct::Dumb` and
   `Test::Metrics::Any`.
2. The parser no longer rejects `push our(@CARP_NOT), ...`, which allowed the
   `IO::Async` suite to start running its tests.
3. `IO::Async` has improved from 46 failing files after the first socket pass to
   38 failing files after the weak-reference, tail-call cleanup, and constant
   export fixes. The latest run is
   `/tmp/io_async_after_constants_parser.log`: `Failed 38/64 test programs.
   40/936 subtests failed`.
4. The previous missing constant errors are cleared. `t/63handle-connect.t` now
   gets past `POSIX::EWOULDBLOCK`, but still exposes missing unqualified import
   behavior for `EINPROGRESS`.
5. The remaining short-term blockers are parser failures in `t/41routine.t`,
   `t/42function.t`, and `t/51loop-connect.t`, plus runtime gaps around
   signal/process tests, stream UTF-8 decoding, stdio handle identity, and
   notifier/future/protocol refcounts.

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
8. `IO::Async`'s `Build test` either passes or has remaining failures reduced
   to documented PerlOnJava gaps.
9. `make` passes.
10. `timeout 1800 ./jcpan -t OpenAI::API` progresses past `IO::Async`; any
   subsequent dependency failures should be documented here before fixing.

## Next Steps

- Fix the remaining parser rejects in `IO::Async`'s routine/function/connect
  tests if they reduce to Perl grammar gaps rather than module-side issues.
- Fix POSIX import/constant generation so `use POSIX qw(EINPROGRESS)` installs
  an unqualified `EINPROGRESS` callable for IO::Async's connector.
- Recheck the process/fork failures after parser and import fixes; many are
  currently `Cannot fork() - Resource temporarily unavailable`.
- Rerun `IO::Async` and then `OpenAI::API`.
- Open a PR from the feature branch with the module plan and implementation
  commits.

# AnyEvent CPAN Module — Test Fix Plan

This document tracks the work needed to make `./jcpan -t AnyEvent` pass
all 83 test programs. The project policy requires all tests to pass,
including low-priority ones.

## Status

| Date | Failed | Passed | Subtests running | Subtests failed |
|------|--------|--------|------------------|-----------------|
| 2026-04-20 initial | 82/83 | 1/83 | 24 | 12 |
| 2026-04-20 after parser/warnings fixes | 17/83 | 66/83 | 93 | 12 |
| 2026-04-20 after ternary `:` fix | 14/83 | 69/83 | 103 | 5 |
| 2026-04-20 after `()` overload marker + `pipe` + delete chain | 13/83 | 70/83 | 157 | 13 |
| 2026-04-20 after `/gc` in list ctx keeps pos() | **12/83** | **71/83** | **157** | **8** |

## Already Fixed (PR fix/anyevent-cpan-tests)

- [x] `Unknown warnings category 'internal'` — registered aliases for
  `debugging`, `inplace`, `internal`, `malloc` → `severe::*` in
  `WarningFlags.java`.
- [x] `Bad name after Foo::Bar::::` — added `]` to the set of terminators
  accepted after a package-name bareword in `IdentifierParser.java`.
- [x] `EV:: => "val"` autoquoting left trailing `::` — stripped in
  `ParseInfix.java`.
- [x] `my $var : $fallback` misparsed as attribute inside ternary —
  check that what follows `:` is an identifier before treating it as
  an attribute introducer in `OperatorParser.java`.
- [x] `&{}` overload via glob aliasing — recognize `()` (not just `((`)
  as an overload marker in `NameNormalizer.hasOverloadMarker`, so
  classes that hand-roll overloading (as AnyEvent::CondVar does to
  avoid loading overload.pm) are properly detected.
- [x] `pipe` / `socketpair` missing from the bytecode interpreter
  (eval STRING path) — added PIPE/SOCKETPAIR opcodes and dispatch.
- [x] `delete $ref->[I][J]` with elided arrow — allowed `[` to have
  a scalar-expression left side in `CompileExistsDelete.visitDeleteArray`.
- [x] `elsif` "masks earlier declaration" — verified real Perl emits
  the same warning, no fix needed (was a red herring).
- [x] `/gc` in list context now preserves pos() — was unconditionally
  resetting pos after any list-context /g match. Now honours `/c`.
  Fixed `AnyEvent::Socket::parse_hostport` IPv6 handling; t/06_socket.t
  now 19/19 (was 14/19).

## Remaining Failures (13 test programs)

Each of the items below is one PerlOnJava bug. Items are ordered by
expected effort/impact.

### A — Investigate: ASM NegativeArraySize compiling DESTROY

**Symptom**:
```
java.lang.NegativeArraySizeException: -1
    at org.objectweb.asm.Frame.merge
    at org.perlonjava.backend.jvm.EmitterMethodCreator.getBytecode
```
then `ASM bytecode generation failed: -1`.

**Trigger**: `AnyEvent::Loop::io::DESTROY`, which combines `delete
$fds->[W][$fd]` (now fixed in compiler), lvalue `(vec $fds->[V], $fd, 1)
= 0`, `weaken`, and a `pop @$q`.

**Affects**: `t/07_io.t`, any AnyEvent watcher cleanup path.

**Approach**:
1. Bisect the DESTROY body to isolate which construct produces invalid
   stack frames.
2. Fix the root cause — probably the stack-map frame computation during
   `emitVarAttrsIfNeeded` or lvalue vec, which both push/pop values in
   unusual ways.

### B — `lvalue vec` with complex base expression

**Symptom**: Same kind of error as the delete-chain fix that was already
landed — expected to be fixed by the same pattern, but the assignment
path (`(vec $expr, $idx, $bits) = value`) has its own lvalue compile
path and is separate. Likely small.

**Affects**: `t/07_io.t`.

### C — `t/03_child.t`: fork unsupported

`fork` returns undef under jperl (per AGENTS.md). The test dies with
`unable to fork at t/03_child.t line 35` because it doesn't check
`$Config{d_fork}` first.

Options:
1. Implement a minimal `fork` using sub-JVM processes (major work —
   process state copying).
2. Make the test skip when `d_fork` is empty.
3. Patch the extracted CPAN tree before `make test` to add a SKIP block.

**Recommendation**: (2) — ship a jperl-side `$SIG{__DIE__}` convention
or a `JPERL_FORK_FAIL=skip` environment variable that the bundled
`jperl` wrapper sets so that `fork` returning failure prints the
TAP SKIP plan and exits 0. Or: detect the upstream pattern `my $pid =
fork; defined $pid or die "unable to fork"` and short-circuit. None of
these are ideal; the cleanest path is still (1) but scope-heavy.

### D — `t/80_ssltest.t` (415 subtests): Net::SSLeay not available

The test has `BEGIN { eval "use Net::SSLeay; 1" or exit 0 }`. It's
dying before the SKIP takes effect. Either our `use Net::SSLeay;` fails
outside the `eval ""` scope, or the `BEGIN` ordering is wrong. Trace to
find why the SKIP path isn't hit. Likely a PerlOnJava bug around
`eval "use Missing::Module"`.

### E — `t/handle/01_readline.t`, `t/handle/02_write.t` — socket-type detection

AnyEvent's `Handle.pm` bails out with "only stream sockets supported"
because it gets an unexpected SO_TYPE / getsockname. Verify that our
socket creation returns the correct packed type and that
`getsockopt(SOL_SOCKET, SO_TYPE)` is implemented.

### F — `t/handle/04_listen.t`, `t/08_idna.t`: OOM (exit 137)

Run with `JPERL_OPTS="-Xmx2g"`. If still OOM, profile and reduce
retention.

### G — `t/06_socket.t`: 5 subtests — IPv6 packed-address returns SCALAR ref

```
not ok 18 # 'SCALAR(0x101952da),443' => ',' eq '2002:58c6:438b::10.0.0.17,443'
```

Our `pack N*` / `inet_pton` returns a SCALAR reference instead of a
packed string in the specific path AnyEvent exercises. Narrow the
minimal reproduction in `AnyEvent::Socket::parse_address` and fix.

### H — `t/09_multi.t` and `t/02_signals.t`: signal delivery / Ctrl+C

Exit 130 is SIGINT. The test's timer/signal infrastructure is probably
reaching a deadlock and the outer harness sends SIGINT. Likely related
to AnyEvent::Base using `pipe` + signals, which now compiles but may
not actually wake up the select loop.

### I — `t/13_weaken.t`: 3 subtests — weaken semantics

```
not ok 5    # weakened timer still fires
not ok 6    # twin (expected/unexpected) of 5
```

Our `weaken` is cooperative-refcount based (per AGENTS.md) and doesn't
match Perl's eager-free semantics in this specific pattern:

```perl
Scalar::Util::weaken $t2;
print $t2 ? "not " : "", "ok 5\n";    # expects $t2 to be undef here
```

This is a known limitation. If we want 83/83, we need to make weakened
refs go to undef when the last strong ref drops, even if the referent
is still reachable from the refcount "wait list".

### J — `t/11_io_perl.t`: 1 failing subtest (#6)

Narrow after all upstream fixes land; may resolve spontaneously.

### K — `t/01_basic.t`: 2 failing subtests (#5, #6 out of sequence)

```
ok 4
not ok 5
ok 5      <-- test 5 appears twice
ok 6
```

`$cv->recv` after `croak` is emitting two lines where one is expected.
Low priority, narrow after A–I land.

## Progress Tracking

### Current status

Tier 1 fixes landed. Remaining work:

- **Quick wins likely**: B (lvalue vec), G (inet_pton), J (single
  subtest), K (single out-of-sequence).
- **Moderate**: A (ASM stack frame), D (eval vs use), E (socket type),
  F (OOM).
- **Heavy / policy-dependent**: C (fork), I (weaken).

### Completed in this PR (fix/anyevent-cpan-tests)

- caa49bf78: parser + warnings (internal/debugging/inplace/malloc,
  trailing-`::` terminators, `=>` autoquote)
- 27a31d5fc: `my $var :` inside ternary
- 20123cb85: overload detection via `()` marker
- ce11a2a96: pipe / socketpair in bytecode interpreter
- 30519d9d2: delete `$ref->[I][J]` with elided arrow

Result: 82/83 → 13/83 test-program failures; subtests 24 → 157.

### Open Questions

- `fork`: implement, or reach agreement that fork-dependent tests are
  exempt from the "all tests must pass" rule?
- `weaken` semantics: cooperative-refcount is documented in `AGENTS.md`
  — do we strengthen it for this test, or treat t/13_weaken #5–#6 as a
  known deviation?

## Appendix: Minimal reproductions (still open)

### H. Signal delivery via the pipe
```perl
use AnyEvent;
use AnyEvent::Impl::Perl;
my $cv = AnyEvent->condvar;
my $w = AnyEvent->signal(signal => "USR1", cb => sub { print "got\n"; $cv->send });
kill USR1 => $$;
$cv->recv;
```
Should print "got". Currently hangs / SIGINT-killed.

### G. IPv6 formatting
```perl
use AnyEvent::Socket;
print format_address(parse_address("2002:58c6:438b::10.0.0.17")), "\n";
```
Should print the roundtrip string. Currently prints a SCALAR(0x...) ref.

### A. ASM failure
(still to be bisected from `AnyEvent::Loop::io::DESTROY`)

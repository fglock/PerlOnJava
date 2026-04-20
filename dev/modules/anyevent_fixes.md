# AnyEvent CPAN Module — Test Fix Plan

This document tracks the work needed to make `./jcpan -t AnyEvent` pass
all 83 test programs. The project policy requires all tests to pass,
including low-priority ones.

## Status

| Date | Failed | Passed | Subtests running |
|------|--------|--------|------------------|
| 2026-04-20 initial | 82/83 | 1/83 | 24 |
| 2026-04-20 after PR #1 (commits caa49bf78, 27a31d5fc) | 14/83 | 69/83 | 103 |

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

## Remaining Failures (14 test programs)

### Tier 1 — Blocking AnyEvent's core functionality

These are the high-impact, broadly-applicable PerlOnJava fixes. Each one
unblocks multiple AnyEvent tests and almost certainly unblocks other
CPAN modules.

#### 1. `&{}` overload installed via glob aliasing (Tier 1, highest leverage)

**Symptom**: `Undefined subroutine &AnyEvent::CondVar=HASH(0x...) called`
when a `CondVar` object is invoked as a code ref.

**Root cause**: AnyEvent installs the `&{}` overload handler by directly
manipulating the symbol table (to avoid loading `overload.pm`, saving
~300KB):

```perl
*{'AnyEvent::CondVar::Base::(&{}'}  = sub { my $self = shift; sub { $self->send (@_) } };
*{'AnyEvent::CondVar::Base::()'}    = sub { };
${'AnyEvent::CondVar::Base::()'}    = 1;  # Perl 5's "is this overloaded?" marker
```

PerlOnJava's overload lookup doesn't consult these typeglob entries.
Real Perl does.

**Affects**: `t/04_condvar.t`, `t/13_weaken.t` (partially), and any
further `AnyEvent.pm`-side code path that calls `$condvar->(@args)`.

**Scope of fix**: when PerlOnJava's method-resolution / operator-dispatch
layer would call a blessed-object as a code ref, look up
`PKG::(&{}` in the target package (walking `@ISA`) and invoke it, analogously
to how `&{}` is resolved via `overload.pm`. The `PKG::()` / `${PKG::()}`
entries mark the package as "overloaded".

**Files likely touched**:
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java`
  (invocation of blessed refs as code)
- Overload plumbing (search for `overload` references in
  `runtime/perlmodule/Overload.java` or similar)

**Test**: the minimal reproduction in this doc's appendix should print
`INVOKED hello` rather than `Undefined subroutine`.

---

#### 2. Invalid bytecode / NegativeArraySizeException in ASM

**Symptom**:
```
java.lang.NegativeArraySizeException: -1
	at org.objectweb.asm.Frame.merge
	at org.perlonjava.backend.jvm.EmitterMethodCreator.getBytecode
```
followed by `ASM bytecode generation failed: -1`.

**Trigger**: compiling `AnyEvent::Loop::io::DESTROY` from
`blib/lib/AnyEvent/Loop.pm`. This is a short sub that does
`delete $fds->[W][$fd]`, `(vec $fds->[V], $fd, 1) = 0`, `weaken`,
etc. Something in the combination exercises a bug in our ASM frame
computation.

**Affects**: `t/07_io.t`, and likely anywhere else this DESTROY runs.

**Approach**:
1. Isolate the minimal subroutine that triggers the -1 negative array.
   (bisect the body of `AnyEvent::Loop::io::DESTROY`).
2. Fix the underlying bytecode generation bug (stack map frame
   miscalculation). Likely related to the `lvalue vec` path or a
   recent change to `emitVarAttrsIfNeeded` / reference emission.

---

#### 3. `lvalue vec` with complex base expression

**Symptom**: `Array exists/delete requires simple array variable at
./blib/lib/AnyEvent/Loop.pm line 302`

**Code**: `(vec $fds->[V], $fd, 1) = 0;`

**Root cause**: our parser treats `(expr) = …` as a list-assignment
candidate and then rejects the inner `vec` as a non-`exists/delete`
lvalue. We should recognise `vec(...)` as a valid lvalue operator
regardless of how its first argument is addressed.

**Affects**: `t/07_io.t`, and any user of AnyEvent's I/O watchers.

---

#### 4. `pipe` operator not implemented

**Symptom**: `Unsupported operator: pipe at (eval 114) line 19, near
"$SIGPIPE_R, "` — thrown from the `JPERL_UNIMPLEMENTED=fatal` path.

**Code** (AnyEvent/Base.pm):
```perl
pipe $SIGPIPE_R, $SIGPIPE_W;
```

**Affects**: `t/02_signals.t`.

**Approach**: implement Perl's `pipe FILEHANDLE_READ, FILEHANDLE_WRITE`
using `java.nio.channels.Pipe`. The filehandles are our normal
`RuntimeIO` instances wrapping the two ends. The same `RuntimeIO` class
already handles non-blocking socket IO, so this is small.

---

#### 5. "my variable masks earlier declaration" false positive in elsif

**Symptom**:
```
"my" variable $ipn masks earlier declaration in same scope at
./blib/lib/AnyEvent/Socket.pm line 486, near "= &parse_ipv6"
```

**Code**:
```perl
if    (my $ipn = &parse_ipv4) { ... }
elsif (my $ipn = &parse_ipv6) { ... }
```

Each branch of an `if/elsif/elsif/else` chain is its own lexical scope
in real Perl. PerlOnJava is (incorrectly) treating them as sharing the
same scope and warning about the second declaration.

**Affects**: Works around by silencing warnings, but several AnyEvent
tests have `-w` / `use warnings` enabled and the `misc` category is
fatal in some scopes.

**Approach**: verify `elsif` creates a fresh scope (it should — each
`elsif` expression is conceptually a nested `if` in Perl's grammar).
If scopes are correct, check how `VariableRedeclarationCheck` walks
them. Likely a single fix in the scope-chaining.

---

### Tier 2 — Isolated runtime / library issues

#### 6. `Not a CODE reference at AnyEvent/IO/Perl.pm line 116`

**Code**: `$_[1](unlink $_[0] or ());` — call element 1 of `@_` as a sub.

I cannot reproduce this with a minimal script; the `@_` element
appears not to be a code ref at runtime under the right conditions.
Needs to be reproduced after #1/#2 are fixed (may be a knock-on effect).

**Affects**: `t/11_io_perl.t`.

---

#### 7. IPv6 `inet_ntop` / packed-address handling in t/06_socket.t

**Symptom**:
```
not ok 18 # 'SCALAR(0x101952da),443' => ',' eq '2002:58c6:438b::10.0.0.17,443'
```

`$ip` is a `SCALAR` ref instead of the packed binary bytes that
`parse_address` / `inet_ntop` should produce.

**Affects**: `t/06_socket.t` (5/19 subtests).

**Approach**: find where `AnyEvent::Socket::parse_address` /
`AnyEvent::Socket::format_address` use `pack`/`unpack`/`inet_pton`/
`inet_ntop` and check which primitive is producing a scalar ref
instead of a packed string. Likely a bug in our `inet_ntop` /
`inet_pton` returning a ref.

---

#### 8. `fork` not implemented (project-wide limitation)

**Affects**: `t/03_child.t` (`unable to fork at t/03_child.t line 35`).

`fork` is explicitly unsupported per `AGENTS.md`. But policy says all
tests must pass. Options:
1. Implement a minimal `fork` using JVM processes (probably not feasible
   in reasonable scope).
2. Make the test skip cleanly when fork fails instead of hard-failing.
   This is aligned with how AnyEvent's upstream behaves when fork isn't
   available (Win32 without Cygwin).
3. Patch the bundled test harness to set `AE_SKIP_FORK_TESTS=1` and
   honour it in `t/03_child.t` (requires upstream behaviour).

**Recommendation**: implement fork-free behaviour — skip the test file
if `$Config{d_fork}` is false. AnyEvent's own test already has some
skip logic. The fix may end up being in `Config.pm` reporting `d_fork=0`
under jperl, or in the test runner.

---

#### 9. `08_idna.t` and `handle/04_listen.t` exit 137 (OOM killed)

**Symptom**: Exit status 137 (SIGKILL, typically OOM). The IDNA test
loads large tables.

**Approach**:
1. Run locally with `JPERL_OPTS="-Xmx2g"` and see if it then passes.
2. If it's a real leak in our lib loading, profile.

**Affects**: `t/08_idna.t`, `t/handle/04_listen.t`.

---

#### 10. `handle/01_readline.t`, `handle/02_write.t` — "only stream sockets supported"

**Symptom**: test dies at line 29 with AnyEvent's "only stream sockets
supported" error. Our socket `getsockopt(SOL_SOCKET, SO_TYPE)` probably
returns something other than `SOCK_STREAM` (or returns nothing), so
AnyEvent refuses to proceed.

**Approach**: verify our socket objects answer `SO_TYPE`
correctly (via `Socket::getsockopt`), and `getsockname`. This intersects
with the TCP server work.

---

#### 11. `t/01_basic.t` — "Tests out of sequence" at test 5/6

**Symptom**: Our output emits test 5 twice (`not ok 5` then `ok 5`),
throwing the harness off.

**Code** (line 12):
```perl
print $_[0]->ready ? "" : "not ", "ok 4\n";
```

This uses a string-concatenating trinary on `print`. Some interaction
with `$_[0]->ready` is returning the wrong value at a critical point.
Low-priority — narrow reproduction needed.

---

#### 12. `t/80_ssltest.t` (415 subtests) — needs Net::SSLeay

**Symptom**: compilation failure; Net::SSLeay is not available. AnyEvent
explicitly skips the TLS tests if `Net::SSLeay` is missing:

```perl
use Net::SSLeay;
BEGIN { eval "use Net::SSLeay; 1" or (print "1..0 # SKIP Net::SSLeay not installed\n"), exit 0 }
```

So this is solely a loading/`use` issue. Investigate why the skip branch
isn't triggered under jperl — probably we fail earlier inside the `use`
line before the `eval` can catch it. That's a real PerlOnJava bug: a
failed `use` should throw into the `eval "..."` that surrounds it.

**Affects**: `t/80_ssltest.t`.

---

## Implementation Plan

Work proceeds in priority order. Each numbered Tier-1 item gets a
focused commit / PR.

1. [ ] Tier 1.1: `&{}` overload via glob aliasing — **starting first**
   because it unblocks CondVar and indirectly other tests, and the
   overload mechanism is well-known in Perl.
2. [ ] Tier 1.2: ASM NegativeArraySize bug — serious codegen bug,
   affects more than just AnyEvent.
3. [ ] Tier 1.3: `lvalue vec` with complex base expression.
4. [ ] Tier 1.4: `pipe` operator.
5. [ ] Tier 1.5: `elsif` scope — false warning.
6. [ ] Tier 2.6–2.12 in order, each with its own minimal reproduction
   and fix.

The final deliverable is 83/83 test programs passing for
`./jcpan -t AnyEvent`, with no regressions in `make` unit tests or in
other bundled CPAN module tests (`make test-bundled-modules`).

## Progress Tracking

### Current Status: Tier 1.1 (overload) — investigating

### Completed Phases
- [x] 2026-04-20: Initial PR (caa49bf78, 27a31d5fc): 4 parser/warnings
  fixes that dropped failures from 82 → 14.

### Open Questions
- Whether to implement `fork` at all, or skip fork-dependent tests per
  AGENTS.md. Current plan: skip cleanly.
- Whether the Tier 1.2 ASM bug is the same as other reports of
  NegativeArraySize in the codebase — search existing issues.

## Appendix: Minimal reproductions

### `&{}` via glob aliasing
```perl
package Foo;
sub new { bless { }, shift }
*{"Foo::(&{}"} = sub { my $self = shift; sub { print "INVOKED @_\n" } };
*{"Foo::()"} = sub { };
${"Foo::()"} = 1;
package main;
my $f = Foo->new;
$f->("hello");   # should print "INVOKED hello"
```
Real Perl: prints `INVOKED hello`. jperl: `Undefined subroutine ...`.

### elsif false-warning
```perl
use strict; use warnings;
if    (my $x = 1) { print "a\n" }
elsif (my $x = 2) { print "b\n" }
```
Real Perl: no warning. jperl: `"my" variable $x masks earlier
declaration in same scope`.

# POE Fix Plan

## Overview

**Module**: POE 1.370 (Perl Object Environment - event-driven multitasking framework)
**Test command**: `./jcpan -t POE`
**Status**: ~15/97 test files pass (mostly skips and simple base tests), vast majority fail

## Dependency Tree

```
POE 1.370
├── POE::Test::Loops 1.360       PASS (2/2 tests)
├── IO::Pipely 0.006             FAIL (IO::Socket broken)
│   └── IO::Socket (>= 1.38)    FAIL (exists &sub in require context)
│   └── Symbol (>= 1.08)        FAIL ($VERSION not set in Java module)
├── Time::HiRes (>= 1.59)       OK (exists, POE has it commented out)
├── IO::Tty 1.08                 FAIL (XS/native, needs C compiler)
├── IO::Poll (optional)          MISSING
├── IO::Pty (optional)           MISSING (needs IO::Tty)
├── Curses (optional)            MISSING
├── Term::Cap (optional)         MISSING
├── Term::ReadKey (optional)     MISSING ($VERSION not set)
├── Socket::GetAddrInfo (opt)    MISSING
├── POSIX                        PARTIAL (missing uname, signals, errno consts)
├── Errno                        OK (pure Perl, complete)
├── Storable                     OK (XSLoader backend)
└── HTTP::Request/Response       PARTIAL (for Filter::HTTPD)
```

## Root Cause Analysis

### Bug 1: `exists(&Errno::EINVAL)` fails in require context (P0 - BLOCKER)

**Impact**: Blocks loading of IO::Socket::INET, which blocks IO::Pipely, POE::Pipe, POE::Kernel, and ~80% of all POE tests.

**Root cause**: `exists(&Errno::EINVAL)` at IO/Socket/INET.pm line 19 compiles correctly in `-e` context but fails when the file is loaded via `require`/`use`. The AST is correct (`OperatorNode: exists → ListNode → OperatorNode: & → IdentifierNode: 'Errno::EINVAL'`), and the handler at `EmitOperatorDeleteExists.java:51-54` should match it. The code path diverges between `-e` and `require` - something in the require compilation context causes the pattern match to fail.

**Evidence**:
```
# Works:
./jperl -e 'exists(&Errno::EINVAL)'                    # OK
./jperl -e 'use strict; exists(&Errno::EINVAL)'        # OK
./jperl --parse -e 'exists(&Errno::EINVAL)'             # Correct AST

# Fails:
./jperl -e 'use IO::Socket::INET'                       # FAIL
./jperl -e 'use Errno; use IO::Socket::INET'            # FAIL
```

**Key file**: `src/main/java/org/perlonjava/backend/jvm/EmitOperatorDeleteExists.java` lines 42-166
**Status**: Under investigation - need to trace the require compilation path

### Bug 2: `$poe_kernel` not visible across files under `use strict` (P0)

**Impact**: Blocks POE::Resource::Aliases.pm, all POE::Resource::*.pm files, and POE::Kernel initialization.

**Root cause**: POE::Kernel.pm declares `use vars qw($poe_kernel)` which creates a package global. POE::Resource::Aliases.pm declares `package POE::Kernel;` and uses `$poe_kernel` under `use strict`. PerlOnJava's `use strict 'vars'` check doesn't recognize package globals declared via `use vars` in a different compilation unit.

**Evidence**:
```
Global symbol "$poe_kernel" requires explicit package name (did you forget to declare
"my $poe_kernel"?) at POE/Resource/Aliases.pm line 30
```

**Note**: In standard Perl, `use vars` creates globals visible whenever code is in the same package, regardless of which file declared them. PerlOnJava's strict checking is per-compilation-unit instead of per-package.

### Bug 3: Symbol.pm `$VERSION` not set (P1)

**Impact**: IO::Pipely dependency check reports `Symbol >= 1.08, have 0`.

**Root cause**: Java `Symbol.java` initializes via `GlobalContext` and sets `%INC{"Symbol.pm"}`, preventing the Perl `Symbol.pm` (which has `$VERSION = '1.09'`) from loading. But `Symbol.java` never sets `$Symbol::VERSION`.

**Fix**: Add `GlobalVariable.getGlobalVariable("Symbol::VERSION").set("1.09")` in `Symbol.java::initialize()`.

### Bug 4: POE::Filter::Reference syntax error - indirect method call (P1)

**Impact**: Blocks POE::Filter::Reference tests (2 test files).

**Root cause**: Line 42 of POE/Filter/Reference.pm:
```perl
eval { require "$package.pm"; import $package (); };
```
`import $package ()` uses Perl's indirect object syntax (`$package->import()`). PerlOnJava's parser doesn't handle the `()` empty-args case for indirect method calls.

**Key file**: `src/main/java/org/perlonjava/parser/SubroutineParser.java` line 271

### Bug 5: POE constants as barewords under strict (P1)

**Impact**: Several test files fail with "Bareword KERNEL/HEAP/SESSION not allowed while strict subs in use".

**Root cause**: POE::Session exports constants like `KERNEL`, `HEAP`, `SESSION` via `sub KERNEL () { 0 }` etc. These are used in test files like `@_[KERNEL, HEAP]`. Since POE can't load (Bug 1), the constants are never defined, causing strict violations.

**Note**: This is a cascading failure from Bug 1 - once POE loads, these constants should be available.

### Bug 6: POSIX missing functions and constants (P2)

**Impact**: POE::Queue::Array tests (EPERM), POE::Resource::Clock (sigaction, SIGALRM).

**Root cause**: POSIX.java only implements `_const_F_OK/R_OK/W_OK/X_OK` and `_const_SEEK_*`. Missing:
- `_const_E*` (errno constants: EPERM, EINTR, ECHILD, EAGAIN, etc.)
- `_const_SIG*` (signal constants: SIGHUP, SIGINT, SIGALRM, etc.)
- `uname()` function (used by POE::Kernel line 10)
- `SigSet`, `SigAction`, `sigaction` (used by POE::Resource::Clock)

**Note**: Errno.pm (pure Perl) provides errno constants separately. The POSIX errno constants are only needed when code imports them from POSIX directly.

### Bug 7: IO::Tty / IO::Pty unavailable (P3 - JVM limitation)

**Impact**: POE::Wheel::Run, terminal-related tests. IO::Tty requires C compiler and native PTY support.

**Note**: This is inherently hard on JVM. POE::Wheel::Run needs PTY for full functionality, but many POE features work without it.

## Test Results Summary

### Current Status: ~15/97 test files pass

| Category | Pass | Fail | Skip | Total |
|----------|------|------|------|-------|
| 10_units/01_pod | 0 | 0 | 4 | 4 |
| 10_units/02_pipes | 0 | 2 | 1 | 3 |
| 10_units/03_base | 7 | 7 | 0 | 14 |
| 10_units/04_drivers | 0 | 1 | 0 | 1 |
| 10_units/05_filters | 6 | 4 | 0 | 10 |
| 10_units/06_queues | 0 | 1 | 0 | 1 |
| 10_units/07_exceptions | 0 | 3 | 0 | 3 |
| 10_units/08_loops | 3 | 5 | 0 | 8 |
| 20_resources | 0 | 8 | 0 | 8 |
| 30_loops/io_poll | 0 | 0 | 35 | 35 |
| 30_loops/select | 0 | ~25 | ~5 | ~30 |
| 90_regression | 0 | ~15 | 0 | ~15 |

### Tests that currently pass (no POE loading required):
- t/10_units/03_base/03_component.t (ok)
- t/10_units/03_base/04_driver.t (ok)
- t/10_units/03_base/05_filter.t (ok)
- t/10_units/03_base/06_loop.t (ok)
- t/10_units/03_base/07_queue.t (ok)
- t/10_units/03_base/08_resource.t (ok)
- t/10_units/03_base/10_wheel.t (ok)
- t/10_units/05_filters/01_block.t (ok)
- t/10_units/05_filters/02_grep.t (ok)
- t/10_units/05_filters/04_line.t (ok)
- t/10_units/05_filters/05_map.t (ok)
- t/10_units/05_filters/06_recordblock.t (ok)
- t/10_units/05_filters/08_stream.t (ok)
- t/10_units/05_filters/50_stackable.t (ok)
- t/10_units/08_loops/02_explicit_loop_fail.t (ok)
- t/10_units/08_loops/07_kernel_loop_fail.t (ok)
- t/10_units/08_loops/09_naive_loop_load.t (ok)
- t/10_units/08_loops/10_naive_loop_load_poll.t (ok)
- t/10_units/08_loops/11_double_loop.t (ok)

## Fix Plan (Recommended Order)

### Phase 1: Unblock POE loading (P0)

| Step | Issue | Files | Expected Impact |
|------|-------|-------|-----------------|
| 1.1 | Fix `exists(&sub)` in require context | EmitOperatorDeleteExists.java | Unblocks IO::Socket::INET → IO::Pipely → POE::Pipe → POE::Kernel |
| 1.2 | Fix cross-file `use vars` under strict | strict.pm or vars.pm or compiler | Unblocks all POE::Resource::*.pm files |
| 1.3 | Set Symbol.pm $VERSION | Symbol.java | Fixes IO::Pipely dependency warning |

### Phase 2: Core functionality (P1)

| Step | Issue | Files | Expected Impact |
|------|-------|-------|-----------------|
| 2.1 | Add POSIX errno constants | POSIX.java | Fixes POE::Queue::Array tests |
| 2.2 | Add POSIX signal constants | POSIX.java | Fixes POE::Resource::Clock |
| 2.3 | Add POSIX::uname() | POSIX.java | Fixes POE::Kernel loading |
| 2.4 | Fix indirect method `import $pkg ()` | SubroutineParser.java | Fixes POE::Filter::Reference |

### Phase 3: Extended features (P2-P3)

| Step | Issue | Files | Expected Impact |
|------|-------|-------|-----------------|
| 3.1 | Add POSIX sigaction/SigSet stubs | POSIX.java, POSIX.pm | POE::Resource::Clock timer support |
| 3.2 | IO::Poll stub | New IO/Poll.pm | Enables IO::Poll event loop |
| 3.3 | IO::Tty/IO::Pty stubs | New .pm files | POE::Wheel::Run basic support |

## Progress Tracking

### Current Status: Phase 1 investigation

### Completed Phases
- [x] Initial analysis (2026-04-04)
  - Ran `./jcpan -t POE`, identified 7 root causes
  - ~15/97 tests pass, all failures traced to root causes
  - Primary blocker: `exists(&sub)` in require context

### Next Steps
1. Debug why `exists(&Errno::EINVAL)` fails in require but works in -e
2. Fix the exists issue
3. Fix cross-file `use vars` strict checking
4. Re-run POE tests to measure progress

## Related Documents
- `dev/modules/smoke_test_investigation.md` - Symbol $VERSION pattern (P2)
- `dev/modules/io_stringy.md` - IO module porting patterns

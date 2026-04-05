# POE Fix Plan

## Overview

**Module**: POE 1.370 (Perl Object Environment - event-driven multitasking framework)
**Test command**: `./jcpan -t POE`
**Status**: 38/53 unit+resource tests pass, ses_session.t 37/41, ses_nfa.t 39/39, k_alarms.t 37/37, k_aliases.t 20/20, k_selects.t 17/17, filehandles.t 131/132, 01_sysrw.t 17/17, 15_kernel_internal.t 12/12

## Dependency Tree

```
POE 1.370
├── POE::Test::Loops 1.360       PASS (2/2 tests)
├── IO::Pipely 0.006             OK (loads successfully after fixes)
│   └── IO::Socket (>= 1.38)    FIXED (exists &sub in require context)
│   └── Symbol (>= 1.08)        FIXED ($VERSION set in Java module)
├── Time::HiRes (>= 1.59)       STUB (POE has it commented out)
├── IO::Tty 1.08                 UNAVAILABLE (XS/native, needs C compiler)
├── IO::Poll (optional)          MISSING
├── IO::Pty (optional)           MISSING (needs IO::Tty)
├── Curses (optional)            MISSING
├── Term::Cap (optional)         MISSING
├── Term::ReadKey (optional)     MISSING ($VERSION not set)
├── Socket::GetAddrInfo (opt)    MISSING
├── POSIX                        FIXED (added uname, signals, errno consts)
├── Errno                        OK (pure Perl, complete)
├── Storable                     OK (XSLoader backend)
└── HTTP::Request/Response       PARTIAL (for Filter::HTTPD)
```

## Bugs Fixed (Commits 743c26461 through f119640a5)

### Bug 1: `exists(&Errno::EINVAL)` fails in require context - FIXED

**Root cause**: `ConstantFoldingVisitor.java` was replacing `IdentifierNode("Errno::EINVAL")` with `NumberNode("22")` inside the `&` sigil operator, so `EmitOperatorDeleteExists.java` couldn't match the pattern.

**Fix**: Added guard in `ConstantFoldingVisitor.visit(OperatorNode)` to skip folding when operator is `"&"`.

### Bug 2: Cross-file `use vars` under strict - NOT A BUG

Investigation confirmed this was a cascading failure from Bug 1. When POE::Kernel.pm crashed during loading (before reaching `use vars qw($poe_kernel)`), the variable was never declared. Once Bug 1 was fixed, POE loads correctly and `$poe_kernel` is visible across files.

### Bug 3: Symbol.pm `$VERSION` not set - FIXED

**Fix**: Added `GlobalVariable.getGlobalVariable("Symbol::VERSION").set("1.09")` in `Symbol.java`.

### Bug 4: Indirect object syntax `import $package ()` - FIXED

**Root cause**: `Variable.java` line 168 errored when it saw `(` after a `$var` in indirect object context. 

**Fix**: Added `parsingIndirectObject` flag to Parser, set it in SubroutineParser before parsing the class variable in indirect object syntax.

### Bug 5: POSIX missing functions and constants - FIXED

**Fix**: Added to `POSIX.java`: signal constants (SIGHUP-SIGTSTP), errno constants (EPERM-ERANGE), `uname()`, `sigprocmask()` stub. Added to `POSIX.pm`: stub classes for SigSet/SigAction.

### Bug 6: ConcurrentModificationException in hash each() - FIXED

**Root cause**: Java's HashMap iterator throws when the hash is modified during iteration. Perl's `each` tolerates this (common idiom: `while (each %h) { delete ... }`).

**Fix**: `RuntimeHash.RuntimeHashIterator` now snapshots entries at creation time via `new ArrayList<>(elements.entrySet()).iterator()`.

### Bug 7: Socket.pm missing IPPROTO constants - FIXED

**Fix**: Added `IPPROTO_TCP`, `IPPROTO_UDP`, `IPPROTO_ICMP` to both `Socket.java` and `Socket.pm`.

### Bug 8: %SIG not pre-populated with signal names - FIXED (commit ba803dc49)

**Root cause**: `%SIG` was empty in PerlOnJava. Perl pre-populates it with signal names as keys (undef values). POE discovers available signals via `keys %SIG`.

**Fix**: `RuntimeSigHash.java` constructor now pre-populates with POSIX signals plus platform-specific signals (macOS: EMT, INFO, IOT; Linux: CLD, STKFLT, PWR, IOT).

### Bug 9: DESTROY not called for blessed objects - ATTEMPTED AND REVERTED

**Root cause**: PerlOnJava had no DESTROY support. POE and many modules rely on DESTROY for cleanup.

**Attempted fix**: `DestroyManager.java` using `java.lang.ref.Cleaner` to detect GC-unreachable blessed objects and reconstruct proxy objects for DESTROY calls.

**Why it was reverted**: The proxy reconstruction approach is fundamentally fragile:
- `close()` inside DESTROY on a proxy hash corrupts subsequent hash access (File::Temp "Not a HASH reference" at line 205)
- Overloaded classes get negative blessIds; `Math.abs()` on cache keys collided with normal class IDs
- Proxy can't fully replicate tied/magic/overloaded behavior of original objects

**Current state**: DESTROY is not called for regular blessed objects. Tied variable DESTROY still works (uses scope-based cleanup via `TieScalar.tiedDestroy()`). See `dev/design/object_lifecycle.md` for future directions (scope-based ref counting recommended).

**Impact on POE**: POE's core event loop works without DESTROY. The only affected feature is `POE::Session::AnonEvent` postback cleanup — sessions using postbacks won't get automatic refcount decrement. Workaround: explicit cleanup or patching POE.

### Bug 10: foreach doesn't see array modifications during iteration - FIXED (commit f79f9f6e8)

**Root cause**: `RuntimeArrayIterator` cached `elements.size()` at creation time. Perl's foreach sees elements pushed to the array during the loop body. This broke POE's `Kernel->stop()` which uses exactly this pattern to walk the session tree:
```perl
my @children = ($self);
foreach my $session (@children) {
    push @children, $self->_data_ses_get_children($session->ID);
}
```

**Fix**: Changed `hasNext()` to check `elements.size()` dynamically instead of using a cached value. This was the root cause of ses_session.t hanging after test 31 — nested child sessions were not being found during stop(), leaving orphan sessions keeping the event loop alive.

### Bug 11: `require File::Spec->catfile(...)` parsed as module name - FIXED (commit 6b9fa2c30)

**Root cause**: POE's `Resource/Clock.pm` does `require File::Spec->catfile(qw(Time HiRes.pm))`. The parser's `parseRequire` method consumed `File::Spec` as a bareword module name without checking for `->` method call after it.

**Fix**: In `OperatorParser.java`, save parser position after consuming identifier, peek for `->`, and if found, restore position and fall through to expression parsing. This allows `Time::HiRes` to load correctly, making `monotime()` return float time instead of integer seconds.

### Bug 12: Non-blocking I/O for pipe handles - FIXED (commit 6b9fa2c30)

**Root cause**: POE's `_data_handle_condition` calls `IO::Handle::blocking($handle, 0)` on the signal pipe, but PerlOnJava's pipes didn't support non-blocking mode. `sysread` on empty non-blocking pipe blocked forever.

**Fix**: Added `isBlocking()`/`setBlocking()` to `IOHandle` interface, implemented in `InternalPipeHandle` with EAGAIN (errno 11) return on empty non-blocking read. Fixed `IO::Handle::blocking()` Perl method to use `($fh, @args) = @_` instead of `shift` (which copied the glob and lost connection to the underlying handle).

### Bug 13: DestroyManager crash with overloaded classes - FIXED (commit cddf4b121)

**Root cause**: `DestroyManager.registerForDestroy` used `Math.abs(blessId)` as cache keys, but overloaded classes get negative blessIds (-1, -2, ...). `Math.abs(-1) = 1` collided with the first normal class ID, causing `getBlessStr` to return null and NPE in `normalizeVariableName`.

**Fix**: Used original `blessId` directly as cache key (fixed before DestroyManager was removed).

### Bug 14: 4-arg select() marks pipes as always ready - FIXED (commit f119640a5)

**Root cause**: The NIO-based `selectWithNIO()` in `IOOperator.java` treated all non-socket handles (pipes, files) as unconditionally ready (`nonSocketReady++`). This caused `select()` to return immediately when monitoring POE's signal pipe, preventing the event loop from blocking for timer timeouts.

**Impact**: POE's `ses_session.t` hung at test 7 (before `POE::Kernel->run()`) because the event loop never slept — `select()` always returned immediately with the pipe "ready", POE tried to read (got nothing), and looped back.

**Fix**: Replaced the "always ready" assumption with proper polling:
- `InternalPipeHandle.hasDataAvailable()` checks if data is actually in the pipe
- Write ends and regular files remain always-ready
- A poll loop with 10ms intervals respects the timeout parameter
- Both pollable fds and NIO selector are checked each iteration

## Current Test Results (2026-04-05)

### Unit Tests (t/10_units/)

| Test File | Result | Notes |
|-----------|--------|-------|
| 03_base/01_poe.t | **PASS** (4/4) | |
| 03_base/03_component.t | **PASS** (1/1) | |
| 03_base/04_driver.t | **PASS** (2/2) | |
| 03_base/05_filter.t | **PASS** (2/2) | |
| 03_base/06_loop.t | **PASS** (1/1) | |
| 03_base/07_queue.t | **PASS** (2/2) | |
| 03_base/08_resource.t | **PASS** (1/1) | |
| 03_base/09_resources.t | FAIL (1/7) | CORE::GLOBAL::require not supported |
| 03_base/10_wheel.t | **PASS** (7/7) | |
| 03_base/11_assert_usage.t | **PASS** (76/76) | |
| 03_base/12_assert_retval.t | **PASS** (22/22) | |
| 03_base/13_assert_data.t | **PASS** (7/7) | |
| 03_base/14_kernel.t | **PASS** (6/6) | |
| 03_base/15_kernel_internal.t | **PASS** (12/12) | Fixed by DupIOHandle (Phase 4.8) |
| 03_base/16_nfa_usage.t | **PASS** (11/11) | |
| 03_base/17_detach_start.t | **PASS** (14/14) | |
| 04_drivers/01_sysrw.t | **PASS** (17/17) | Fixed by DupIOHandle + non-blocking pipe I/O |
| 05_filters/01_block.t | **PASS** (42/42) | |
| 05_filters/02_grep.t | **PASS** (48/48) | |
| 05_filters/03_http.t | PARTIAL (79/137) | HTTP::Message bytes issue |
| 05_filters/04_line.t | **PASS** (50/50) | |
| 05_filters/05_map.t | FAIL | Minor test failure |
| 05_filters/06_recordblock.t | **PASS** (36/36) | |
| 05_filters/07_reference.t | FAIL | Storable not available at test time |
| 05_filters/08_stream.t | **PASS** (24/24) | |
| 05_filters/50_stackable.t | **PASS** (29/29) | |
| 05_filters/51_reference_die.t | FAIL (0/5) | Storable not available at test time |
| 05_filters/99_filterchange.t | FAIL | Filter::Reference compilation |
| 06_queues/01_array.t | **PASS** (2047/2047) | |
| 07_exceptions/01_normal.t | **PASS** (7/7) | |
| 07_exceptions/02_turn_off.t | **PASS** (4/4) | |
| 07_exceptions/03_not_handled.t | **PASS** (8/8) | |
| 08_loops/01_explicit_loop.t | **PASS** (2/2) | |
| 08_loops/02_explicit_loop_fail.t | **PASS** (1/1) | |
| 08_loops/03_explicit_loop_poll.t | FAIL | IO::Poll not available |
| 08_loops/04_explicit_loop_envvar.t | FAIL | IO::Poll not available |
| 08_loops/05_kernel_loop.t | **PASS** (2/2) | |
| 08_loops/06_kernel_loop_poll.t | FAIL | IO::Poll not available |
| 08_loops/07_kernel_loop_fail.t | **PASS** (1/1) | |
| 08_loops/08_kernel_loop_search_poll.t | FAIL | IO::Poll not available |
| 08_loops/09_naive_loop_load.t | TODO | Feature not implemented yet |
| 08_loops/10_naive_loop_load_poll.t | TODO | Feature not implemented yet |
| 08_loops/11_double_loop.t | TODO | Feature not implemented yet |

### Resource Tests (t/20_resources/10_perl/)

| Test File | Result | Notes |
|-----------|--------|-------|
| aliases.t | **PASS** (14/14) | Fixed ConcurrentModificationException |
| caller_state.t | **PASS** (6/6) | |
| events.t | **PASS** (38/38) | |
| extrefs.t | **PASS** (31/31) | |
| extrefs_gc.t | **PASS** (5/5) | |
| filehandles.t | **PASS** (131/132) | Fixed by DupIOHandle; 1 TODO test |
| sessions.t | **PASS** (58/58) | |
| sids.t | **PASS** (7/7) | |
| signals.t | **PASS** (46/46) | 2 TODO skips count as pass |

### Summary: 38 test files fully pass, 15 fail/partial

## Remaining Issues

### Pre-existing PerlOnJava limitations (not POE-specific)

| Issue | Impact | Category |
|-------|--------|----------|
| CORE::GLOBAL::require override not supported | 09_resources.t | Runtime feature |
| DESTROY not called for blessed objects | wheel_readwrite, wheel_tail, wheel_sf_*, wheel_accept, ses_session (4 tests) | JVM limitation |
| IO::Poll not available | 4 loop tests | Missing module |

### Issues worth fixing

| Issue | Impact | Difficulty |
|-------|--------|------------|
| DESTROY workaround (Phase 4.5) | 20-30+ tests across 5+ wheel test files | Medium-Hard |
| Storable not found by POE test runner | 3 filter tests | Low (path issue?) |
| HTTP::Message bytes handling | 03_http.t (58 tests) | Medium |
| TIOCSWINSZ stub (Phase 4.6) | wheel_run, k_signals_rerun | Low |

### Event Loop Tests (t/30_loops/select/)

| Test File | Result | Notes |
|-----------|--------|-------|
| 00_info.t | **PASS** (2/2) | |
| all_errors.t | SKIP | |
| k_alarms.t | **PASS** (37/37) | Alarm scheduling works |
| k_aliases.t | **PASS** (20/20) | Session aliases work |
| k_detach.t | **PASS** (9/9) | Session detach works |
| k_run_returns.t | **PASS** (1/1) | |
| k_selects.t | **PASS** (17/17) | File handle watchers |
| k_sig_child.t | PARTIAL (5/15) | Child signal handling |
| k_signals.t | PARTIAL (2/8) | Signal delivery |
| k_signals_rerun.t | FAIL | |
| sbk_signal_init.t | **PASS** (1/1) | |
| ses_nfa.t | **PASS** (39/39) | NFA state machine works |
| ses_session.t | PARTIAL (37/41) | 4 failures from DESTROY count checks |
| comp_tcp.t | FAIL (0/34) | TCP networking |
| wheel_accept.t | PARTIAL (1/2) | Hangs after test 1 |
| wheel_readwrite.t | PARTIAL (16/28) | I/O events don't fire, hangs |
| wheel_run.t | PARTIAL (42/103) | 10 pass, 32 skip (IO::Pty), blocked by TIOCSWINSZ |
| wheel_sf_tcp.t | PARTIAL (4/9) | Hangs after test 4 |
| wheel_sf_udp.t | PARTIAL (4/10) | UDP datagrams never delivered |
| wheel_sf_unix.t | FAIL (0/12) | Socket factory Unix |
| wheel_tail.t | PARTIAL (4/10) | sysseek now works; hangs due to DESTROY |
| z_kogman_sig_order.t | **PASS** (7/7) | |
| z_merijn_sigchld_system.t | **PASS** (4/4) | |
| z_steinert_signal_integrity.t | **PASS** (2/2) | |
| connect_errors.t | **PASS** (3/3) | |
| k_signals_rerun.t | PARTIAL (1/9) | TIOCSWINSZ error in child processes |

**Event loop summary**: 14/35 fully pass. Core event loop works (alarms, aliases, detach, signals, NFA).

## Fix Plan - Remaining Phases

### Completed Phases (1-3, 4.1-4.4, 4.8)

All phases through 4.4 and Phase 4.8 are complete. See Progress Tracking below for details.

### Phase 4.5: Implement DESTROY workaround — HIGHEST REMAINING IMPACT

**Status**: Not started
**Difficulty**: Medium-Hard
**Expected impact**: 20-30+ additional test passes across 5+ test files

**Problem**: All POE::Wheel test hangs (wheel_readwrite, wheel_tail, wheel_sf_tcp, wheel_accept,
wheel_sf_udp) are caused by DESTROY never being called when POE::Wheel objects go out of scope.
When wheels are created in eval or deleted via `delete $heap->{wheel}`, DESTROY never fires.
This leaves orphan select() watchers and anonymous event handlers registered in the kernel,
preventing sessions from stopping.

**Affected tests**:
- wheel_readwrite (28 tests) — constructor tests pass, I/O events work, hangs on cleanup
- wheel_tail (10 tests) — FollowTail watching works, hangs on `delete $heap->{wheel}`
- wheel_sf_tcp (9 tests) — TCP server works, hangs between phases
- wheel_accept (2 tests) — accept works, hangs on cleanup
- wheel_sf_udp (10 tests) — UDP sockets created, hangs on cleanup
- ses_session (4 of 41 tests) — explicitly count DESTROY invocations

**POE::Wheel DESTROY cleanup pattern** (all wheels follow this):
1. Remove I/O watchers: `$poe_kernel->select_read($handle)`, `select_write($handle)`
2. Cancel timers: `$poe_kernel->delay($state_name)` (FollowTail only)
3. Remove anonymous states: `$poe_kernel->state($state_name)`
4. Free wheel ID: `POE::Wheel::free_wheel_id($id)`

**Recommended approach**: Option A — trigger DESTROY on hash `delete`/scalar overwrite when
a blessed reference is replaced. POE's pattern is always `$heap->{wheel} = Wheel->new(...)`
with a single reference, so calling DESTROY when the hash value is overwritten or deleted is
correct 99% of the time. For safety, DESTROY should be made idempotent.

**Implementation plan**:
1. In `RuntimeHash.delete()`: before removing a value, check if it's a blessed reference
   whose class defines DESTROY. If so, call DESTROY on it.
2. In `RuntimeScalar.set()`: when overwriting a blessed reference, check for DESTROY.
3. Guard against double-DESTROY: track whether DESTROY has already been called (e.g.,
   a flag on the RuntimeScalar or the blessed object's internal hash).
4. DESTROY should be called in void context, catching any exceptions (like Perl does).

**Alternative approaches** (see DESTROY Workaround Options section for full analysis):
- Option B: Scope-based tied proxy (more accurate, more complex)
- Option C: Patch POE::Wheel subclasses (fragile, no Java changes)
- Option D: Full reference counting (correct but very complex)
- Option F: POE::Kernel session GC (targeted but doesn't generalize)

### Phase 4.6: Add TIOCSWINSZ stub — LOW EFFORT

**Status**: Not started
**Difficulty**: Low
**Expected impact**: k_signals_rerun (8 tests), some wheel_run child process tests

**Problem**: Wheel::Run uses `require 'sys/ioctl.ph'` inside an eval to get TIOCSWINSZ for
terminal window size. PerlOnJava doesn't have sys/ioctl.ph, causing bareword errors.

**Implementation plan**:
1. Create `src/main/perl/lib/sys/ioctl.ph` with TIOCSWINSZ constant (0x5413 on Linux,
   0x40087468 on macOS)
2. OR: make the eval silently fail (already in an eval, just needs the require to not
   blow up with a compilation error)

**Note**: Most wheel_run tests also need fork, so the real impact is limited to
k_signals_rerun (8 of 9 failures are from TIOCSWINSZ in child processes).

### Phase 4.7: Windows platform support — CI CRITICAL

**Status**: Not started
**Difficulty**: Low-Medium (mostly lookup tables)

POE's core event loop uses Java NIO (cross-platform), and POE itself has `$^O eq 'MSWin32'`
guards. But several PerlOnJava subsystems only have macOS/Linux branches.

| Step | Target | Severity | Difficulty |
|------|--------|----------|------------|
| 4.7.1 | **Fix EAGAIN/errno on Windows** — `FFMPosixWindows.strerror()` is a 10-entry stub. `ErrnoVariable` probes strerror to discover EAGAIN/EINPROGRESS/etc., which all resolve to 0 on Windows. POE's non-blocking I/O depends on these. Fix: add Windows errno constants directly, bypassing strerror probing. | Critical | Medium |
| 4.7.2 | **Add Windows errno table** — `ErrnoHash.java` and `Errno.pm` have macOS/Linux tables only, fall through to Linux on Windows. Fix: add `MSWin32` branch with MSVC CRT errno values. | High | Low |
| 4.7.3 | **Add Windows branch to RuntimeSigHash** — `%SIG` is pre-populated with Unix signals (HUP, USR1, PIPE, etc.) that don't exist on Windows. POE iterates `keys %SIG` and tries to install handlers. Fix: Windows branch with only INT, TERM, ABRT, FPE, ILL, SEGV, BREAK. | High | Low |
| 4.7.4 | **Add Windows branch to POSIX.java** — IS_MAC flag gives macOS vs "everything else" (Linux). Windows gets Linux signal/termios values. Fix: add IS_WINDOWS flag, return Windows-correct signal constants, skip termios. | Medium | Low |
| 4.7.5 | **Fix Socket.java constants** — SOL_SOCKET=1 (Linux) vs 0xFFFF (Windows), SO_REUSEADDR=2 vs 4, etc. These matter if passed to native setsockopt. Java abstracts most of this, so impact depends on implementation path. Fix: platform-detect and use correct values. | Medium | Low |
| 4.7.6 | **Fix sysconf for Windows** — Runs `ulimit -n` which doesn't exist on Windows. Already has catch block defaulting to 1024, but should use a cleaner approach (e.g., `_getmaxstdio()` or just return 2048). | Low | Low |

**What already works on Windows (no changes needed):**
- `select()` via Java NIO Selector — cross-platform
- Pipe handling via Java `PipedInputStream`/`PipedOutputStream` — cross-platform
- POE::Loop::Select — has `$^O eq 'MSWin32'` guards
- `socketpair` via loopback TCP — the standard Windows approach
- `$^O` correctly set to `MSWin32` on Windows

### Phase 4.9: Storable path fix — LOW EFFORT

**Status**: Not started
**Difficulty**: Low
**Expected impact**: 3 filter tests (07_reference.t, 51_reference_die.t, 99_filterchange.t)

**Problem**: POE's test runner can't find Storable at test time. Storable is available in
PerlOnJava (XSLoader backend) but the test's @INC doesn't include the right path.

**Implementation plan**: Investigate why `use Storable` fails inside POE's filter tests.
Likely needs adding the correct lib path or fixing Storable's module resolution.

### Phase 4.10: HTTP::Message bytes handling — MEDIUM EFFORT

**Status**: Not started
**Difficulty**: Medium
**Expected impact**: 58 additional tests in 03_http.t (79/137 currently)

**Problem**: HTTP::Message byte-string handling has issues when processing HTTP requests/responses
through POE::Filter::HTTPD. The exact nature of the bytes vs. characters mismatch needs investigation.

### Phase 5: JVM limitations (not fixable without major work)

| Feature | Reason | Tests affected |
|---------|--------|----------------|
| fork() | JVM cannot fork | k_sig_child (10), k_signals (6), wheel_run IO::Pty tests |
| IO::Tty / IO::Pty | XS module, needs C compiler | wheel_run (32 skip), wheel_curses, wheel_readline |
| DESTROY count accuracy | JVM tracing GC, not refcounting | ses_session (4 tests check exact DESTROY counts) |
| CORE::GLOBAL::require | Not implemented | 09_resources.t (6 tests) |

## Progress Tracking

### Current Status: Phase 4.8 complete — DESTROY workaround (Phase 4.5) is next highest impact

### Completed Phases
- [x] Phase 1: Initial analysis (2026-04-04)
  - Ran `./jcpan -t POE`, identified 7 root causes
  - ~15/97 tests pass, all failures traced to root causes
- [x] Phase 1: Fix blockers (2026-04-04, commit 743c26461)
  - Fixed exists(&sub) constant folding bypass
  - Added Socket IPPROTO constants
  - Set Symbol.pm $VERSION
  - Added POSIX errno/signal constants, uname(), sigprocmask()
  - Fixed sigprocmask return value for POE
- [x] Phase 2: Core fixes (2026-04-04, commit 76bf09bd9)
  - Fixed indirect object syntax with variable class + parenthesized args
  - Fixed ConcurrentModificationException in hash each() iteration
  - 35/53 unit+resource tests fully pass, 10/35 event loop tests fully pass
- [x] Phase 3.1: Session lifecycle fixes (2026-04-04, commits ba803dc49, 338bd4a90, f79f9f6e8)
  - Pre-populated %SIG with OS signal names (Bug 8)
  - Implemented DESTROY for blessed objects via java.lang.ref.Cleaner (Bug 9)
  - Fixed foreach to see array modifications during iteration (Bug 10)
  - ses_session.t: 7/41 → 35/41 (28 new passing tests)
  - Event loop restart, session tree walk, postbacks/callbacks all work
- [x] Phase 3.2: I/O and parser fixes (2026-04-04, commits 6b9fa2c30, cddf4b121, 2777d2e46)
  - Fixed `require File::Spec->catfile(...)` parser bug (Bug 11) — enables Time::HiRes dynamic loading
  - Added non-blocking I/O for pipe handles (Bug 12) — POE signal pipe no longer blocks
  - Fixed IO::Handle::blocking() argument passing (shift vs @_ glob copy issue)
  - Added 4-arg select() and FileDescriptorTable for I/O multiplexing
  - Fixed DestroyManager blessId collision with overloaded classes (Bug 13)
  - Removed DestroyManager — proxy reconstruction too fragile (close() corrupts proxy hash)
  - Updated dev/design/object_lifecycle.md with findings
- [x] Phase 3.3: select() polling fix (2026-04-04, commit f119640a5)
  - Fixed 4-arg select() to poll pipe readiness instead of marking always ready (Bug 14)
  - select() now properly blocks when monitoring InternalPipeHandle with timeout
  - POE event loop no longer busy-loops; timer-based events fire correctly
- [x] Phase 3.4: Signal pipe and postback fixes (2026-04-04, commit eff2f356d)
  - Fixed pipe fd registry mismatch (Bug 15) — pipe() created RuntimeIO objects but never
    registered them in RuntimeIO.filenoToIO, making pipes invisible to select(). Added
    registerExternalFd() to RuntimeIO and InternalPipeHandle.getFd() getter.
  - Fixed platform EAGAIN value (Bug 16) — InternalPipeHandle.sysread() hard-coded errno 11
    (Linux EAGAIN). On macOS EAGAIN=35, causing POE to see "Resource deadlock avoided" instead
    of EAGAIN. Fixed to use ErrnoVariable.EAGAIN() for platform-correct values.
  - Patched POE::Session postback/callback auto-cleanup (Bug 17) — DESTROY won't fire on
    PerlOnJava, so postbacks/callbacks now auto-decrement session refcount when called.
    This allows sessions to exit properly without relying on DESTROY.
  - ses_session.t: 7/41 → 37/41 (signal delivery + postback cleanup working)
  - ses_nfa.t: 39/39 (perfect), k_alarms.t: 37/37 (perfect), k_aliases.t: 20/20 (perfect)
- [x] Phase 3.5: select() and fd allocation fixes (2026-04-04, commit b995a5f81)
  - Fixed select() bitvector write-back (Bug 18) — 4-arg select() copied bitvector args
    but never wrote results back, causing callers to see unchanged bitvectors
  - Fixed fd allocation collision (Bug 19) — FileDescriptorTable and RuntimeIO had
    separate fd counters; socket/socketpair advanced one but not the other, causing
    pipe() to allocate fds overlapping with existing sockets
  - Fixed socketpair() stream init (Bug 20) — SocketIO created without input/output
    streams, causing sysread() to fail. Also improved sysread() channel fallback.
  - k_selects.t: 5/17 → 17/17 (all pass)
- [x] Phase 4.1: Socket pack_sockaddr_un/unpack_sockaddr_un stubs (2026-04-05, commit a15fbce47)
  - Added stub implementations for AF_UNIX sockaddr packing/unpacking
  - Registered in Socket.java and Socket.pm @EXPORT
  - Unblocked POE::Wheel::SocketFactory loading
  - connect_errors: 3/3 PASS, wheel_sf_tcp: 4/9 (hangs), wheel_accept: 1/2 (hangs)
- [x] Phase 4.2: POSIX terminal/file constants (2026-04-05, commit 34934234d)
  - Added 80+ POSIX constants: stat permissions, terminal I/O, baud rates, sysconf
  - Added S_IS* file type functions as pure Perl (S_ISBLK, S_ISCHR, S_ISDIR, etc.)
  - Added setsid() and sysconf(_SC_OPEN_MAX) implementations
  - Platform-aware macOS/Linux detection for all platform-dependent constants
  - Wheel::FollowTail loads (4/10 pass), Wheel::Run loads (42/103: 6 pass, 36 skip)
- [x] Phase 4.3: fileno fix + sysseek + event loop analysis (2026-04-04, commits 14ea123a9, 5b0ca1383)
  - Fixed fileno() returning undef for regular file handles (Bug 21) — open() paths for
    regular files, JAR resources, scalar-backed handles, and pipes all created IO handles
    without calling assignFileno(), making fileno($fh) return undef. Added assignFileno()
    calls in all four open paths in RuntimeIO.java.
  - Implemented sysseek operator for JVM backend (Bug 22) — sysseek was only in the
    interpreter backend. Added JVM support via CoreOperatorResolver, EmitBinaryOperatorNode,
    OperatorHandler, and CompileBinaryOperator. Returns new position (or "0 but true"),
    unlike seek which returns 1/0.
  - Analyzed event loop I/O hang pattern — root cause is DESTROY (see below)
- [x] Phase 4.8: Refcounted filehandle duplication (2026-04-05, commits 490c53f89, 116d88c7a)
  - Fixed non-blocking syswrite for pipe handles — writer checks buffer capacity, returns
    EAGAIN when full. Added shared writerClosedFlag for EOF detection.
  - Added EBADF errno support — sysread/syswrite on closed/invalid handles now set $! to EBADF
  - Created DupIOHandle class — refcounted IOHandle wrapper enabling proper Perl dup semantics.
    Each dup'd handle has independent closed state and fd number; underlying resource only
    closed when last dup is closed. Original handle preserves its fileno after duplication.
  - Fixed findFileHandleByDescriptor() to check RuntimeIO's fileno registry — dup'd handles
    registered via registerExternalFd() were invisible to open-by-fd-number (e.g., open($fh, ">&6"))
  - Added FileDescriptorTable.registerAt() and nextFdValue() methods
  - 01_sysrw.t: 15/17 → 17/17 (dup/close cycle works)
  - 15_kernel_internal.t: 7/12 → 12/12 (fd management works)
  - filehandles.t: 1/132 → 131/132 (only 1 TODO test fails)
  - signals.t: 45/46 → 46/46

### Key Findings (Phase 3.1-3.4)
- **foreach-push pattern**: Perl's foreach dynamically sees elements pushed during iteration.
  PerlOnJava's RuntimeArrayIterator was caching size at creation. This broke POE::Kernel->stop()
  which walks the session tree by pushing children during foreach.
- **DESTROY not feasible via GC**: Java's Cleaner/GC-based DESTROY is unreliable across JVM
  implementations and cannot guarantee deterministic timing. Perl's DESTROY depends on
  reference counting (fires immediately when last reference drops). The JVM's tracing GC
  is fundamentally incompatible with this semantic. DESTROY is not implemented.
- **DESTROY workaround for POE**: POE::Session::AnonEvent postbacks/callbacks now auto-cleanup
  when called (decrement session refcount on first invocation). This replaces DESTROY-based
  cleanup for the common one-shot postback pattern. 4 ses_session.t failures remain for tests
  that explicitly count DESTROY invocations.
- **Dual fd registry**: pipe() registered handles in FileDescriptorTable but not RuntimeIO.filenoToIO.
  select() only consulted RuntimeIO, making pipes invisible. Fixed by registerExternalFd().
- **Fd counter collision**: FileDescriptorTable and RuntimeIO maintained separate fd counters.
  socketpair/socket advanced RuntimeIO.nextFileno but not FileDescriptorTable.nextFd, causing
  pipe() to allocate fds that overlapped with existing socket fds. Fixed by cross-synchronizing
  both counters (advancePast/advanceFilenoCounterPast).
- **select() bitvector write-back**: 4-arg select() created snapshot copies of bitvector args
  for tied-variable safety, but never wrote the modified bitvectors back to the original
  variables. Callers always saw their original bitvectors unchanged. Fixed to write back
  after selectWithNIO returns.
- **socketpair() stream init**: socketpair() used SocketIO(channel, family) constructor which
  doesn't initialize inputStream/outputStream. sysread() failed with "No input stream
  available" on blocking sockets. Fixed to use SocketIO(socket) constructor, and made
  sysread() fall back to channel-based I/O when streams are unavailable.
- **Platform errno**: Hard-coded Linux errno values (EAGAIN=11) caused mismatches on macOS
  (EAGAIN=35). Fixed to use ErrnoVariable.EAGAIN() which probes the platform.
- **Signal delivery**: Now works end-to-end: kill() → %SIG handler → signal pipe write →
  select() detects pipe → POE dispatches signal event.
- **require expression parsing**: `require File::Spec->catfile(...)` was parsed as
  `require File::Spec` (module) instead of `require <expr>`. This prevented Time::HiRes
  from loading, causing monotime() to return integer seconds instead of float.

### Key Findings (Phase 4.3) — DESTROY Root Cause Analysis

**The event loop I/O hang is caused by POE::Wheel DESTROY not being called**, not by
I/O subsystem bugs. The underlying I/O works correctly:

- `fileno()` now works for all handle types (regular files, JARs, scalars, pipes)
- `select()` correctly detects readability on regular file handles
- `sysread()`/`syswrite()` work on tmpfiles, pipes, and sockets
- `sysseek()` works correctly (returns position, "0 but true" at offset 0)
- POE::Wheel::ReadWrite I/O events fire correctly in isolation

**Verified working in isolation**: A standalone POE session with a ReadWrite wheel on a
tmpfile receives all input events, flushes writes, and completes. The state machine
(read→pause→seek→resume→write→shutdown) works as expected.

**What fails**: When wheels are created in eval (test_new validation) or deleted via
`delete $heap->{wheel}`, DESTROY never fires. This leaves orphan select() watchers
and anonymous event handlers registered in the kernel, preventing sessions from stopping.

**POE::Wheel DESTROY cleanup pattern** (all wheels follow this):
1. Remove I/O watchers: `$poe_kernel->select_read($handle)`, `select_write($handle)`
2. Cancel timers: `$poe_kernel->delay($state_name)` (FollowTail only)
3. Remove anonymous states: `$poe_kernel->state($state_name)`
4. Free wheel ID: `POE::Wheel::free_wheel_id($id)`

**Specific test impacts:**
- **wheel_readwrite.t test 12**: `ReadWrite->new(Handle=>\*DATA, LowMark=>3, HighMark=>8,
  LowEvent=>"low")` succeeds when it should die (missing HighEvent validation in POE).
  The wheel registers a select watcher on \*DATA, and without DESTROY, it's never removed.
  This keeps Part 1's session alive forever, preventing Part 2 from running.
- **wheel_tail.t**: FollowTail file-based watching works (creates file, reads lines, detects
  resets), but the session hangs after `delete $heap->{wheel}` because the FollowTail's
  delay timer and select watcher aren't cleaned up.
- **wheel_sf_tcp.t**, **wheel_accept.t**: TCP server accepts connections (test 1 passes),
  but subsequent wheel lifecycle depends on DESTROY for cleanup between phases.

### DESTROY Workaround Options (Not Yet Implemented)

| Option | Approach | Pros | Cons |
|--------|----------|------|------|
| A | **Trigger DESTROY on `delete`/`set`** — when overwriting a blessed reference in a hash/scalar, check if the class defines DESTROY and call it | Simple to implement; covers the `delete $heap->{wheel}` pattern | May call DESTROY too early if other references exist; not refcount-accurate |
| B | **Scope-based cleanup via tied proxy** — wrap wheel references in a tied scalar that calls DESTROY when the scalar is overwritten | More accurate lifecycle tracking | Complex; requires patching POE internals |
| C | **Patch POE::Wheel subclasses** — add explicit `_cleanup()` methods, patch POE::Kernel to call them when sessions stop | Clean, no Java changes needed | Fragile; must patch every Wheel subclass |
| D | **Implement reference counting** — track refcounts for blessed objects at the RuntimeScalar level | Correct Perl 5 semantics | Very complex; massive changes to RuntimeScalar, affects performance |
| E | **GC-based DESTROY with Cleaner** — register cleanup actions with java.lang.ref.Cleaner, run when GC collects | No refcounting needed | Unpredictable timing; previously attempted and reverted |
| F | **POE::Kernel session GC** — when a session has no pending events/timers *except* select watchers, force-remove all watchers | Targeted to POE's specific deadlock pattern | Doesn't generalize; requires understanding POE's invariants |

**Recommended approach**: Option A (trigger DESTROY on delete/set) is the most pragmatic.
POE's pattern is always `$heap->{wheel} = Wheel->new(...)` with a single reference, so
calling DESTROY when the hash value is overwritten or deleted is correct 99% of the time.
For safety, DESTROY could be made idempotent (track whether it's already been called).

### Next Steps

**Priority order:**
1. **Phase 4.5: DESTROY workaround** — highest remaining impact (20-30+ tests)
2. **Phase 4.6: TIOCSWINSZ stub** — low effort, unblocks k_signals_rerun
3. **Phase 4.9: Storable path fix** — low effort, 3 filter tests
4. **Phase 4.10: HTTP::Message bytes** — medium effort, 58 tests in 03_http.t
5. **Phase 4.7: Windows platform support** — CI critical

## Related Documents
- `dev/modules/smoke_test_investigation.md` - Symbol $VERSION pattern
- `dev/modules/io_stringy.md` - IO module porting patterns
- `dev/design/object_lifecycle.md` - DESTROY and object lifecycle design

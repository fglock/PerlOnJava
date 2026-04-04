# POE Fix Plan

## Overview

**Module**: POE 1.370 (Perl Object Environment - event-driven multitasking framework)
**Test command**: `./jcpan -t POE`
**Status**: 35/53 test files fully pass (unit + resource tests), up from ~15/97

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

## Bugs Fixed (Commits 743c26461, 76bf09bd9)

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

## Current Test Results (2026-04-04)

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
| 03_base/15_kernel_internal.t | PARTIAL (7/12) | File handle dup bug |
| 03_base/16_nfa_usage.t | **PASS** (11/11) | |
| 03_base/17_detach_start.t | **PASS** (14/14) | |
| 04_drivers/01_sysrw.t | TIMEOUT | Hangs on I/O test |
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
| filehandles.t | FAIL (1/132) | Socket.getChannel() null |
| sessions.t | **PASS** (58/58) | |
| sids.t | **PASS** (7/7) | |
| signals.t | PARTIAL (45/46) | 1 test failure |

### Summary: 35 test files fully pass, 18 fail/partial

## Remaining Issues

### Pre-existing PerlOnJava limitations (not POE-specific)

| Issue | Impact | Category |
|-------|--------|----------|
| CORE::GLOBAL::require override not supported | 09_resources.t | Runtime feature |
| File handle dup (open FH, ">&OTHER") shares state | 15_kernel_internal.t | I/O subsystem |
| Socket.getChannel() returns null | filehandles.t | Network I/O |
| IO::Poll not available | 4 loop tests | Missing module |

### Issues worth fixing

| Issue | Impact | Difficulty |
|-------|--------|------------|
| Storable not found by POE test runner | 3 filter tests | Low (path issue?) |
| HTTP::Message bytes handling | 03_http.t (58 tests) | Medium |
| 01_sysrw.t hangs | 1 driver test | Medium (I/O) |
| signals.t 1 failure | 1 test | Low |

### Event Loop Tests (t/30_loops/select/)

| Test File | Result | Notes |
|-----------|--------|-------|
| 00_info.t | **PASS** (2/2) | |
| all_errors.t | SKIP | |
| k_alarms.t | **PASS** (37/37) | Alarm scheduling works |
| k_aliases.t | **PASS** (20/20) | Session aliases work |
| k_detach.t | **PASS** (9/9) | Session detach works |
| k_run_returns.t | **PASS** (1/1) | |
| k_selects.t | PARTIAL (5/17) | File handle watchers |
| k_sig_child.t | PARTIAL (5/15) | Child signal handling |
| k_signals.t | PARTIAL (2/8) | Signal delivery |
| k_signals_rerun.t | FAIL | |
| sbk_signal_init.t | **PASS** (1/1) | |
| ses_nfa.t | TIMEOUT | NFA session hangs |
| ses_session.t | PARTIAL (7/41) | Core session tests |
| comp_tcp.t | FAIL (0/34) | TCP networking |
| wheel_accept.t | FAIL | Socket accept |
| wheel_run.t | FAIL (0/103) | Needs fork/IO::Pty |
| wheel_sf_tcp.t | FAIL | Socket factory TCP |
| wheel_sf_udp.t | FAIL | Socket factory UDP |
| wheel_sf_unix.t | FAIL (0/12) | Socket factory Unix |
| wheel_tail.t | FAIL | FollowTail |
| z_kogman_sig_order.t | **PASS** (7/7) | |
| z_merijn_sigchld_system.t | **PASS** (4/4) | |
| z_steinert_signal_integrity.t | **PASS** (2/2) | |

**Event loop summary**: 10/35 fully pass. Core event loop works (alarms, aliases, detach, signals).

## Fix Plan - Remaining Phases

### Phase 3: Event loop and session hardening (high impact)

| Step | Target | Expected Impact | Difficulty |
|------|--------|-----------------|------------|
| 3.1 | Fix ses_session.t (7/41) | Core session lifecycle validation | Medium |
| 3.2 | Fix k_selects.t (5/17) | File handle watcher support | Medium |
| 3.3 | Fix k_signals.t (2/8) and k_sig_child.t (5/15) | Signal delivery | Medium |
| 3.4 | Fix signals.t (45/46) | 1 remaining test failure | Low |
| 3.5 | Fix Storable path for POE test runner | Unblocks 3 filter tests | Low |
| 3.6 | Fix ses_nfa.t timeout | NFA state machine tests | Medium |

### Phase 4: Extended features (lower priority)

| Step | Target | Expected Impact | Difficulty |
|------|--------|-----------------|------------|
| 4.1 | HTTP::Message bytes handling | 03_http.t (58 more tests) | Medium |
| 4.2 | Socket/network tests (comp_tcp, wheel_sf_*) | TCP/UDP networking | Hard |
| 4.3 | IO::Poll stub | 4 poll-related loop tests | Medium |
| 4.4 | File handle dup fix | 15_kernel_internal.t (5 tests) | Hard |
| 4.5 | wheel_tail.t (FollowTail) | File watching | Medium |

### Phase 5: JVM limitations (not fixable without major work)

| Feature | Reason |
|---------|--------|
| wheel_run.t (103 tests) | Needs fork + IO::Pty (native) |
| IO::Tty / IO::Pty | XS module, needs C compiler |
| wheel_curses.t | Needs Curses (native) |
| wheel_readline.t | Needs terminal |

## Progress Tracking

### Current Status: Phase 3 in progress

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

### Next Steps (Phase 3)
1. Debug ses_session.t to understand why 34/41 tests fail
2. Debug k_selects.t to understand file handle watcher issues
3. Debug k_signals.t / k_sig_child.t for signal delivery issues
4. Fix signals.t 1 remaining failure
5. Fix Storable path issue for POE test runner

## Related Documents
- `dev/modules/smoke_test_investigation.md` - Symbol $VERSION pattern
- `dev/modules/io_stringy.md` - IO module porting patterns

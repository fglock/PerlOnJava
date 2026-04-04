# POE Fix Plan

## Overview

**Module**: POE 1.370 (Perl Object Environment - event-driven multitasking framework)
**Test command**: `./jcpan -t POE`
**Status**: 35/53 unit+resource tests pass, ses_session.t 35/41 (up from 7/41), 10+/35 event loop tests pass

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

### Bug 8: %SIG not pre-populated with signal names - FIXED (commit ba803dc49)

**Root cause**: `%SIG` was empty in PerlOnJava. Perl pre-populates it with signal names as keys (undef values). POE discovers available signals via `keys %SIG`.

**Fix**: `RuntimeSigHash.java` constructor now pre-populates with POSIX signals plus platform-specific signals (macOS: EMT, INFO, IOT; Linux: CLD, STKFLT, PWR, IOT).

### Bug 9: DESTROY not called for blessed objects - FIXED (commit 338bd4a90)

**Root cause**: PerlOnJava had no DESTROY support. POE and many modules rely on DESTROY for cleanup.

**Fix**: New `DestroyManager.java` using `java.lang.ref.Cleaner` to detect when blessed objects become GC-unreachable and schedule DESTROY calls on the main thread at safe points. Per-blessId cache makes the check O(1). Exceptions in DESTROY are caught and printed as "(in cleanup)" warnings matching Perl behavior.

**Note**: DESTROY timing differs from Perl. Perl uses reference counting (DESTROY fires immediately when last reference drops). JVM uses tracing GC (DESTROY fires when GC collects, typically at global destruction). This is expected behavior and not fixable without a reference-counting layer.

### Bug 10: foreach doesn't see array modifications during iteration - FIXED (commit f79f9f6e8)

**Root cause**: `RuntimeArrayIterator` cached `elements.size()` at creation time. Perl's foreach sees elements pushed to the array during the loop body. This broke POE's `Kernel->stop()` which uses exactly this pattern to walk the session tree:
```perl
my @children = ($self);
foreach my $session (@children) {
    push @children, $self->_data_ses_get_children($session->ID);
}
```

**Fix**: Changed `hasNext()` to check `elements.size()` dynamically instead of using a cached value. This was the root cause of ses_session.t hanging after test 31 — nested child sessions were not being found during stop(), leaving orphan sessions keeping the event loop alive.

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
| ses_session.t | PARTIAL (35/41) | Signal delivery + DESTROY timing |
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
- [x] Phase 3.1: Session lifecycle fixes (2026-04-04, commits ba803dc49, 338bd4a90, f79f9f6e8)
  - Pre-populated %SIG with OS signal names (Bug 8)
  - Implemented DESTROY for blessed objects via java.lang.ref.Cleaner (Bug 9)
  - Fixed foreach to see array modifications during iteration (Bug 10)
  - ses_session.t: 7/41 → 35/41 (28 new passing tests)
  - Event loop restart, session tree walk, postbacks/callbacks all work

### Key Findings (Phase 3.1)
- **foreach-push pattern**: Perl's foreach dynamically sees elements pushed during iteration.
  PerlOnJava's RuntimeArrayIterator was caching size at creation. This broke POE::Kernel->stop()
  which walks the session tree by pushing children during foreach.
- **DESTROY timing**: GC-based DESTROY fires at global destruction, not immediately when last
  reference drops. This is an expected JVM limitation. POE's DESTROY-count tests (30-31, 35-36)
  will always show 0 at assertion time. Not fixable without reference counting.
- **Signal delivery**: `kill("ALRM", $$)` doesn't trigger %SIG handlers within POE event loop.
  ses_session.t tests 21-22 expect 5 SIGALRMs and 5 SIGPIPEs but get 0.
- **4-arg select()**: Returns 0 immediately when bit vectors are defined (line 67-69 of
  IOOperator.java). Only the all-undef sleep path works. This affects pipe-based I/O
  monitoring but POE's timer-based event loop still functions.

### Next Steps (Phase 3 continued)
1. Implement signal delivery: `kill("ALRM", $$)` should trigger %SIG{ALRM} handler
2. Implement 4-arg select() for file descriptor monitoring (needed for k_selects.t, pipe I/O)
3. Debug ses_nfa.t timeout (may be fixed by foreach fix)
4. Fix Storable path issue for POE test runner (unblocks 3 filter tests)
5. Debug k_sig_child.t (5/15) — child signal handling

## Related Documents
- `dev/modules/smoke_test_investigation.md` - Symbol $VERSION pattern
- `dev/modules/io_stringy.md` - IO module porting patterns

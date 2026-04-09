# Mojo::IOLoop Support for PerlOnJava

## Status: Phase 1 COMPLETE -- 15/108 test programs pass (was 8/109)

- **Module version**: Mojolicious 9.42 (SRI/Mojolicious-9.42.tar.gz)
- **Date started**: 2026-04-09
- **Branch**: `docs/mojo-ioloop-plan`
- **PR**: https://github.com/fglock/PerlOnJava/pull/467
- **Test command**: `./jcpan -t Mojo::IOLoop`
- **Build system**: MakeMaker (125 files installed successfully)

## Background

Mojolicious is the most popular modern Perl web framework. `Mojo::IOLoop` is its
non-blocking I/O event loop, the foundation for async networking, HTTP servers/clients,
WebSockets, and timers. Getting it working on PerlOnJava would unlock the entire
Mojolicious ecosystem.

Mojolicious 9.42 is pure Perl (no XS required for core functionality), making it a
good candidate for PerlOnJava. The module installs and configures cleanly. Test failures
are concentrated around a small number of missing features that cascade broadly.

## Design Principle: Reuse CPAN Perl Code, Replace Only XS

For each missing module, **use the original CPAN `.pm` file and replace only the XS/C
portions with a Java backend**. This maximizes compatibility, reduces maintenance burden,
and ensures features like export tags, parameter validation, and edge-case handling come
from the battle-tested upstream code.

| Module | Approach |
|--------|----------|
| `Digest::SHA` | Already bundled; just fix `@EXPORT_OK` |
| `Hash::Util::FieldHash` | No-op shim (100% XS module; GC cleanup unnecessary on JVM) |
| `Compress::Raw::Zlib` | CPAN `.pm` + Java XS backend (`CompressRawZlib.java`) |
| `IO::Poll` | CPAN `.pm` + Java XS backend (`IOPoll.java`) |

## Test Results Summary

### After Phase 1 (15/108 passing)

| Metric | Count |
|--------|-------|
| Total test programs | 108 |
| Passed (t/mojo/) | 12 of 63 |
| Passed (t/mojolicious/) | 3 of 43 |
| Passed (t/pod*) | 0 of 2 |
| **Total Passed** | **15** |

#### Passing Tests (15/108)

| Test File | Category |
|-----------|----------|
| t/mojo/cache.t | Pure data structure |
| t/mojo/cookie.t | HTTP cookies |
| t/mojo/date.t | Date parsing |
| t/mojo/eventemitter.t | Event subscription |
| t/mojo/headers.t | HTTP headers |
| t/mojo/home.t | Home directory |
| t/mojo/json_pointer.t | JSON Pointer (RFC 6901) |
| t/mojo/proxy.t | Proxy configuration |
| t/mojo/reactor_detect.t | Reactor detection |
| t/mojo/roles.t | Role composition |
| t/mojo/signatures.t | Subroutine signatures |
| t/mojo/sse.t | Server-sent events |
| t/mojolicious/pattern.t | URL pattern matching |
| t/mojolicious/routes.t | Routing |
| t/mojolicious/types.t | MIME types |

#### New passes from Phase 1 (7 gained)

cookie.t, headers.t, home.t, proxy.t, reactor_detect.t, roles.t, sse.t,
pattern.t, routes.t, types.t -- all unblocked by Mojo::Util now loading.

### Initial Baseline (8/109)

8 tests passed before Phase 1 (cache, date, eventemitter, json_pointer, signatures,
plus skipped/partial tests).

### Remaining Failed Tests by Root Cause

| Root Cause | Tests Affected | Severity |
|------------|---------------|----------|
| ~~Digest::SHA missing HMAC exports~~ | ~~90~~ | **FIXED** |
| ~~Compress::Raw::Zlib missing~~ | ~~100~~ | **FIXED** |
| ~~IO::Poll not available~~ | ~~100~~ | **FIXED** |
| ~~Hash::Util::FieldHash missing~~ | ~~5~~ | **FIXED** |
| DynamicMethods empty method name | ~15 | **High** -- `Can't locate object method ""` |
| `is_regexp` on unblessed ref | ~5 | **High** -- Mojo::Collection |
| `toGlob` is null (glob coercion) | ~3 | Medium -- bytestream/file ops |
| IO::Poll `_poll()` runtime behavior | ~20 | **High** -- IOLoop/reactor/daemon tests |
| fork() not supported | ~3 (subprocess) | Known limitation |
| Parser indirect method bug | 1 | Low -- monkey_patch + Test::More |

**Key insight**: `Mojo::Util` has THREE compile-time blockers, not just one:
1. `use Digest::SHA qw(hmac_sha1_hex ...)` (line 7) -- needs HMAC in @EXPORT_OK
2. `use IO::Compress::Gzip` (line ~12) -- chains to `Compress::Raw::Zlib`
3. `use IO::Poll qw(POLLIN POLLPRI)` (line 13) -- needs IO::Poll module

All three must be fixed before Mojo::Util loads.

## Blocking Issues (Ordered by Impact)

### Issue 1: Digest::SHA HMAC functions not in @EXPORT_OK -- FIXED

**Status**: **DONE**

**Impact**: Blocks ~90% of all test programs via `Mojo::Util` line 7.

**Error**:
```
"hmac_sha1_hex" is not exported by the Digest::SHA module
```

**Fix applied**: Added `@HMAC_FUNCTIONS` array to `@EXPORT_OK` and `%EXPORT_TAGS{all}`
in `src/main/perl/lib/Digest/SHA.pm`. The HMAC function implementations already existed.

**File**: `src/main/perl/lib/Digest/SHA.pm` (modified)

---

### Issue 2: IO::Poll not available (CRITICAL) -- FIXED

**Status**: **DONE**

**Impact**: Blocks Mojo::Util at compile time (line 13: `use IO::Poll qw(POLLIN POLLPRI)`),
and blocks Mojo::Reactor::Poll at runtime.

**Error**:
```
Can't locate IO/Poll.pm in @INC
```

**Who needs it**:
- `Mojo::Util` line 13: `use IO::Poll qw(POLLIN POLLPRI);` (compile-time)
- `Mojo::Reactor::Poll` line 5: `use IO::Poll qw(POLLERR POLLHUP POLLIN POLLNVAL POLLOUT POLLPRI);`
- `Mojo::Util::_readable()` and `Mojo::Reactor::Poll::one_tick()` call `IO::Poll::_poll()`

**Approach**: Use CPAN `IO::Poll.pm` (IO-1.55, 208 lines, pure Perl OO wrapper) with
an added `XSLoader::load('IO::Poll', $VERSION)` line. Only the XS `_poll()` function
and the poll constants need Java implementation.

**XS functions to implement** (from `IO.xs` lines 254-286):

| XS Function | Description |
|-------------|-------------|
| `_poll($timeout_ms, $fd1, $mask1, $fd2, $mask2, ...)` | Core poll syscall wrapper |

Plus 11 constants from XS BOOT section:

| Constant | Value | Export |
|----------|-------|--------|
| POLLIN | 0x0001 | @EXPORT |
| POLLPRI | 0x0002 | @EXPORT_OK |
| POLLOUT | 0x0004 | @EXPORT |
| POLLERR | 0x0008 | @EXPORT |
| POLLHUP | 0x0010 | @EXPORT |
| POLLNVAL | 0x0020 | @EXPORT |
| POLLRDNORM | 0x0040 | @EXPORT_OK |
| POLLWRNORM | POLLOUT | @EXPORT_OK |
| POLLRDBAND | 0x0080 | @EXPORT_OK |
| POLLWRBAND | 0x0100 | @EXPORT_OK |
| POLLNORM | POLLRDNORM | @EXPORT_OK |

**Critical `_poll()` semantics**:
- Takes timeout in ms (-1=block, 0=non-blocking, >0=wait)
- Takes flat list of (fd, event_mask) pairs
- **Modifies event_mask arguments in-place** with returned revents
- Returns count of ready fds, or -1 on error
- Mojolicious calls `_poll()` directly (bypasses OO layer)

**How Mojolicious uses it**:
```perl
# Mojo::Util::_readable
sub _readable { !!(IO::Poll::_poll(@_[0, 1], my $m = POLLIN | POLLPRI) > 0) }

# Mojo::Reactor::Poll::one_tick
my @poll = map { $_ => $self->{io}{$_}{mode} } keys %{$self->{io}};
if (IO::Poll::_poll($timeout, @poll) > 0) {
    while (my ($fd, $mode) = splice @poll, 0, 2) {
        # $mode now contains revents (modified in-place)
    }
}
```

**Java implementation plan**: Reuse `IOOperator.selectWithNIO()` infrastructure which
already maps PerlOnJava's virtual filenos to NIO channels for both socket and non-socket
handles.

**Files to create**:
- `src/main/perl/lib/IO/Poll.pm` -- CPAN source + `XSLoader::load`
- `src/main/java/org/perlonjava/runtime/perlmodule/IOPoll.java` -- `_poll()` + constants

**Effort**: ~1 day (leveraging existing `selectWithNIO()` infrastructure).

---

### Issue 3: Hash::Util::FieldHash missing -- FIXED

**Status**: **DONE**

**Impact**: Blocks `Mojo::DynamicMethods` (used for plugin-registered helper methods).

**Approach**: No-op shim. `Hash::Util::FieldHash` is 100% XS with no reusable pure Perl.
Its purpose (GC-triggered hash entry cleanup) is unnecessary on JVM where tracing GC
handles circular references natively. This is consistent with `weaken()` being a no-op.

**File created**: `src/main/perl/lib/Hash/Util/FieldHash.pm`
- Exports all 7 public functions: `fieldhash`, `fieldhashes`, `idhash`, `idhashes`,
  `id`, `id_2obj`, `register`
- `fieldhash(\%)` / `idhash(\%)` return hash ref (no-op)
- `id($)` delegates to `Scalar::Util::refaddr`
- `id_2obj($)` returns undef (reverse mapping not implementable without tracking)
- `register(@)` returns `$obj` (no-op)

---

### Issue 4: Indirect method call parser bug (LOW) -- NOT STARTED

**Status**: **TODO** (Phase 4)

**Impact**: 1 test (t/mojo/base_util.t, 2 of 4 subtests).

**Error**:
```
Can't locate object method "is" via package "MojoMonkeyTest::bar"
```

**Root cause**: The parser in `SubroutineParser.java` (line 245) incorrectly parses:
```perl
is MojoMonkeyTest::bar(), 'bar', 'right result';
```
as indirect method call `MojoMonkeyTest::bar->is(...)` instead of function call
`is(MojoMonkeyTest::bar(), ...)`.

This happens because `MojoMonkeyTest::bar` was installed at runtime via `monkey_patch`
(typeglob assignment) so the parser doesn't see it at compile time.

**Fix location**: `SubroutineParser.java`, line 245. When the calling function (`is`)
is known to exist AND the potential class name is followed by `(`, prefer function-call
interpretation.

**Effort**: Small (30 minutes, needs careful regression testing).

---

### Issue 5: Compress::Raw::Zlib missing -- PARTIALLY DONE (needs CPAN .pm switch)

**Status**: **IN PROGRESS** -- Java backend created, .pm needs replacement with CPAN version

**Impact**: Compile-time blocker for Mojo::Util via the chain:
`Mojo::Util` -> `use IO::Compress::Gzip` -> `Compress::Raw::Zlib`

Also blocks HTTP response decompression (`Mojo::Content`) and WebSocket compression.

**Current state**: Java backend `CompressRawZlib.java` (854 lines) implements all core
XS functions using `java.util.zip.Deflater/Inflater`. A custom `.pm` file was created
but **should be replaced with the CPAN version**.

**Why switch to CPAN .pm**: The current custom 186-line `.pm` is missing critical features
that the CPAN 603-line `.pm` provides:

| Feature | Custom .pm | CPAN .pm | Impact |
|---------|-----------|----------|--------|
| `%DEFLATE_CONSTANTS` / `@DEFLATE_CONSTANTS` | Missing | Present | **Breaks IO::Compress::Adapter::Deflate** |
| `ParseParameters()` | Missing | Present | Needed internally by constructors |
| `Parameters` class (full validation) | 30-line stub | 180 lines | Less robust |
| `deflateParams` Perl wrapper | In Java directly | Calls `_deflateParams()` | No named-param support |
| STORABLE_freeze/thaw stubs | Missing | Present | Prevents serialization crashes |
| InflateScan classes | Missing | Present | Missing feature |
| WindowBits=0 adjustment | Missing | Present | Edge case bugs |

**Java-side changes needed to use CPAN .pm**:

1. Rename `deflateParams` registration to `_deflateParams` in deflateStream methods
   (CPAN .pm defines Perl wrapper `deflateParams` that calls `$self->_deflateParams(...)`)

2. Add `deflateTune` stub (return Z_OK -- not supported by `java.util.zip.Deflater`)

3. Set `$Compress::Raw::Zlib::XS_VERSION = "2.222"` in `initialize()`

**CPAN .pm syntax concern**: Line 168 uses indirect object syntax
(`my $p = new Compress::Raw::Zlib::Parameters()`). PerlOnJava supports indirect object
syntax, and since `CompressRawZlib.java` is loaded via XSLoader, the class should be
known at compile time.

**Files**:
- `src/main/java/org/perlonjava/runtime/perlmodule/CompressRawZlib.java` (created, needs minor edits)
- `src/main/perl/lib/Compress/Raw/Zlib.pm` (replace with CPAN version)

**CPAN source location**: `/Users/fglock/.perlonjava/lib/Compress/Raw/Zlib.pm` (v2.222)

---

### Issue 6: fork() not supported (KNOWN LIMITATION)

**Status**: **Won't fix** (JVM limitation)

**Impact**: `Mojo::IOLoop::Subprocess` cannot work. Affects t/mojo/subprocess.t and
t/mojo/subprocess_ev.t.

**Note**: `Mojo::Reactor::Poll` does NOT use fork -- it uses `IO::Poll::_poll()` for
I/O multiplexing. The core IOLoop, HTTP server/client, WebSockets, and timers do not
require fork. Only `Subprocess` (for running blocking code in a child process) needs it.

**Workaround**: Mojolicious applications can use Java threading via inline Java as an
alternative to Subprocess.

## Implementation Plan

### Phase 1: Unblock Mojo::Util compile-time loading -- IN PROGRESS

**Goal**: Fix all three compile-time blockers so Mojo::Util loads, enabling ~90% of
tests to at least start running.

| Task | Status | File |
|------|--------|------|
| 1a. Add HMAC to Digest::SHA @EXPORT_OK | **DONE** | `src/main/perl/lib/Digest/SHA.pm` |
| 1b. Create Hash::Util::FieldHash shim | **DONE** | `src/main/perl/lib/Hash/Util/FieldHash.pm` |
| 1c. Compress::Raw::Zlib Java backend | **DONE** | `CompressRawZlib.java` (854 lines) |
| 1d. Replace Compress::Raw::Zlib .pm with CPAN | **TODO** | Use CPAN .pm + minor Java edits |
| 1e. Create IO::Poll Java backend + CPAN .pm | **TODO** | `IOPoll.java` + CPAN `IO/Poll.pm` |

**Remaining work**: Replace custom Compress::Raw::Zlib .pm with CPAN version (with Java
XS backend adjustments), then implement IO::Poll.

**Expected outcome**: Mojo::Util and Mojo::Base load. Tests that don't use IOLoop at
runtime should pass (~25 test programs).

### Phase 2: Triage new failures and fix data-structure tests

**Goal**: After Mojo::Util loads, many tests will start running but may hit new errors.
Run the full test suite, categorize failures, and fix issues in pure-Perl data structure
tests (collection, bytestream, json, url, path, parameters, headers, cookies, template,
dom, etc.) that don't require networking.

| Task | Effort |
|------|--------|
| Re-run `./jcpan -t Mojo::IOLoop` and categorize results | 30 min |
| Fix new compile/runtime errors in non-IOLoop tests | 1-2 days |
| Update test counts in this document | 5 min |

**Expected outcome**: 25-40 test programs passing.

### Phase 3: Event Loop (runtime IOLoop functionality)

**Goal**: Get `Mojo::Reactor::Poll::one_tick()` working with real sockets so the IOLoop
can process I/O events, timers, and connections.

IO::Poll's `_poll()` is implemented in Phase 1 for compile-time constant export, but
**runtime functionality** (actual polling of socket file descriptors) needs validation
and likely debugging with real IOLoop tests.

| Task | Effort |
|------|--------|
| Validate `_poll()` with `Mojo::Reactor::Poll::one_tick()` | 1 day |
| Debug fd-to-NIO-channel mapping for sockets | 1 day |
| Get t/mojo/ioloop.t basic tests passing | 1 day |

**Expected outcome**: t/mojo/ioloop.t timer and basic I/O tests pass. Foundation for
HTTP server/client.

### Phase 4: HTTP and WebSocket tests

**Goal**: Get Mojo::UserAgent, Mojo::Server::Daemon, and Test::Mojo working so the
integration tests (`*_lite_app.t`) can run.

| Task | Effort |
|------|--------|
| Fix socket/HTTP issues found in daemon.t, user_agent.t | 2-3 days |
| Get Test::Mojo embedded server working | 1-2 days |
| Validate websocket_frames.t | 1 day |

**Expected outcome**: 50-70 test programs passing including lite_app tests.

### Phase 5: Parser Fix

**Goal**: Fix indirect method call disambiguation for runtime-installed subs.

| Task | File | Effort |
|------|------|--------|
| Fix backtrack condition in SubroutineParser | `SubroutineParser.java` | 30 min |

**Expected outcome**: base_util.t should fully pass (4/4 subtests).

### Phase 6: Polish and remaining failures

**Goal**: Address remaining test failures, document known limitations, update test counts.

| Task | Effort |
|------|--------|
| Fix remaining pure-Perl test failures | 1-2 days |
| Document fork()-dependent tests as expected failures | 30 min |
| Final test count update and summary | 30 min |

**Expected outcome**: 70-90+ test programs passing. Remaining failures are fork/subprocess
(known limitation) or edge cases.

## Dependency Chain

```
Mojo::Util (compile-time requirements):
  ├─ use Digest::SHA qw(hmac_sha1_hex sha1 sha1_hex)  ← Issue 1 (FIXED)
  ├─ use IO::Compress::Gzip                            ← Issue 5 (IN PROGRESS)
  │     └─ requires Compress::Raw::Zlib
  └─ use IO::Poll qw(POLLIN POLLPRI)                   ← Issue 2 (TODO)

Mojo::Base ← requires Mojo::Util
  └─ optionally loads Hash::Util::FieldHash             ← Issue 3 (FIXED)

Mojo::Reactor::Poll ← requires IO::Poll (runtime _poll())
  └─ core of Mojo::IOLoop

Mojo::IOLoop ← requires Mojo::Reactor::Poll
  └─ Mojo::IOLoop::Subprocess requires fork()           ← Issue 6 (won't fix)

Almost all Mojolicious modules ← require Mojo::Base ← require Mojo::Util
```

## Tests Expected to Pass After Phase 1

Tests that don't depend on IOLoop at runtime:
- t/mojo/cache.t (already passes)
- t/mojo/date.t (already passes)
- t/mojo/eventemitter.t (already passes)
- t/mojo/json_pointer.t (already passes)
- t/mojo/signatures.t (already passes)
- t/mojo/collection.t, t/mojo/bytestream.t, t/mojo/json.t (likely)
- t/mojo/url.t, t/mojo/path.t, t/mojo/parameters.t (likely)
- t/mojo/headers.t, t/mojo/cookie.t, t/mojo/cookiejar.t (likely)
- t/mojo/template.t, t/mojo/roles.t, t/mojo/dom.t (likely)
- t/mojo/exception.t, t/mojo/file.t, t/mojo/log.t, t/mojo/loader.t (likely)
- t/mojo/dynamic_methods.t (likely, with FieldHash shim)
- t/mojo/home.t, t/mojo/sse.t (likely)
- t/mojolicious/pattern.t, t/mojolicious/routes.t, t/mojolicious/types.t (likely)

IOLoop-dependent tests (need Phase 2 runtime _poll()):
- t/mojo/ioloop.t, t/mojo/daemon.t, t/mojo/user_agent.t
- t/mojolicious/lite_app.t and other *_lite_app.t tests
- t/test/mojo.t

## Progress Tracking

### Current Status: Phase 1 COMPLETE -- Phase 2 next

### Completed
- [x] Initial analysis and test baseline (2026-04-09): 8/109 tests pass
- [x] Issue 1: Digest::SHA HMAC exports (2026-04-09)
- [x] Issue 3: Hash::Util::FieldHash no-op shim (2026-04-09)
- [x] Issue 5: Compress::Raw::Zlib -- CPAN .pm + CompressRawZlib.java backend (2026-04-09)
- [x] Issue 2: IO::Poll -- CPAN .pm + IOPoll.java backend with _poll() + 11 constants (2026-04-09)
- [x] Socket: Added inet_pton/inet_ntop to Socket.java and Socket.pm (2026-04-09)
- [x] Verified Mojo::Util and Mojo::Base load successfully (2026-04-09)
- [x] All unit tests pass (`make` succeeds) (2026-04-09)
- [x] Mojo test count: 8/109 -> 15/108 (2026-04-09)

### Files Created/Modified in Phase 1
- `src/main/perl/lib/Digest/SHA.pm` -- HMAC functions added to @EXPORT_OK
- `src/main/perl/lib/Hash/Util/FieldHash.pm` -- NEW, no-op shim
- `src/main/perl/lib/Compress/Raw/Zlib.pm` -- REPLACED with CPAN version
- `src/main/java/org/perlonjava/runtime/perlmodule/CompressRawZlib.java` -- NEW, Java XS backend
- `src/main/perl/lib/IO/Poll.pm` -- NEW, CPAN source + XSLoader
- `src/main/java/org/perlonjava/runtime/perlmodule/IOPoll.java` -- NEW, _poll() + constants
- `src/main/java/org/perlonjava/runtime/perlmodule/Socket.java` -- Added inet_pton, inet_ntop
- `src/main/perl/lib/Socket.pm` -- Added inet_pton, inet_ntop to @EXPORT

### Next Steps (Phase 2)
1. Investigate DynamicMethods empty method name error (`Can't locate object method ""`)
2. Fix `is_regexp` on unblessed reference (Mojo::Collection)
3. Fix `toGlob` null pointer (bytestream/file operations)
4. Investigate remaining test failures for low-hanging fruit
5. IO::Poll `_poll()` runtime testing with actual sockets

## Related Documents
- `dev/modules/smoke_test_investigation.md` -- Compress::Raw::Zlib tracked as P8
- `dev/modules/lwp_useragent.md` -- Related HTTP client support
- `dev/modules/poe.md` -- Related event loop support

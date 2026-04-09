# Mojo::IOLoop Support for PerlOnJava

## Status: Initial Analysis -- 8/109 test programs pass

- **Module version**: Mojolicious 9.42 (SRI/Mojolicious-9.42.tar.gz)
- **Date started**: 2026-04-09
- **Branch**: `docs/mojo-ioloop-plan`
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

## Test Results Summary

| Metric | Count |
|--------|-------|
| Total test programs | 109 |
| Passed | 8 (including 1 skipped) |
| Failed | 101 |
| Subtests run | 40 (all passed) |

### Passing Tests (8/109)

| Test File | Result | Notes |
|-----------|--------|-------|
| t/mojo/cache.t | **ok** | Pure data structure, no Mojo::Util dependency |
| t/mojo/date.t | **ok** | Date parsing/formatting |
| t/mojo/eventemitter.t | **ok** | Event subscription/emission |
| t/mojo/json_pointer.t | **ok** | JSON Pointer (RFC 6901) |
| t/mojo/signatures.t | **ok** | Subroutine signature support |
| t/mojo/promise_async_await.t | skipped | Developer-only test |
| t/mojo/base_util.t | partial | 2/4 subtests pass, then dies (see Issue 4) |

### Failed Tests by Root Cause

| Root Cause | Tests Affected | Severity |
|------------|---------------|----------|
| Digest::SHA missing HMAC exports | ~90 | **Critical** -- blocks Mojo::Util loading |
| IO::Poll not available | ~60+ (runtime) | **Critical** -- blocks Mojo::Reactor::Poll |
| Hash::Util::FieldHash missing | ~5 directly | **High** -- blocks Mojo::DynamicMethods |
| Compress::Raw::Zlib missing | ~2 directly | Medium -- blocks HTTP compression |
| Parser indirect method bug | 1 | Low -- affects monkey_patch + Test::More |
| fork() not supported | ~3 (subprocess) | Known limitation |

## Blocking Issues (Ordered by Impact)

### Issue 1: Digest::SHA HMAC functions not in @EXPORT_OK (CRITICAL)

**Impact**: Blocks ~90% of all test programs. Almost every Mojolicious module depends
on `Mojo::Util`, which fails to compile.

**Error**:
```
"hmac_sha1_hex" is not exported by the Digest::SHA module
Can't continue after import errors at .../Mojo/Util.pm line 7.
```

**Root cause**: PerlOnJava's `Digest::SHA.pm` defines `hmac_sha1_hex` and other HMAC
functions (lines 133-201) but does not include them in `@EXPORT_OK` (line 27). Standard
Perl 5's `Digest::SHA` exports all HMAC functions.

**What Mojo::Util imports** (line 7):
```perl
use Digest::SHA qw(hmac_sha1_hex sha1 sha1_hex);
```
- `sha1` -- already in @EXPORT_OK
- `sha1_hex` -- already in @EXPORT_OK
- `hmac_sha1_hex` -- **missing from @EXPORT_OK** (but implemented!)

**Fix**: Add HMAC functions to `@EXPORT_OK` in
`src/main/perl/lib/Digest/SHA.pm` (lines 17-27):

```perl
our @SHA_FUNCTIONS = qw(
    sha1        sha1_hex        sha1_base64
    sha224      sha224_hex      sha224_base64
    sha256      sha256_hex      sha256_base64
    sha384      sha384_hex      sha384_base64
    sha512      sha512_hex      sha512_base64
    sha512224   sha512224_hex   sha512224_base64
    sha512256   sha512256_hex   sha512256_base64
);

our @HMAC_FUNCTIONS = qw(
    hmac_sha1       hmac_sha1_hex       hmac_sha1_base64
    hmac_sha224     hmac_sha224_hex     hmac_sha224_base64
    hmac_sha256     hmac_sha256_hex     hmac_sha256_base64
    hmac_sha384     hmac_sha384_hex     hmac_sha384_base64
    hmac_sha512     hmac_sha512_hex     hmac_sha512_base64
    hmac_sha512224  hmac_sha512224_hex  hmac_sha512224_base64
    hmac_sha512256  hmac_sha512256_hex  hmac_sha512256_base64
);

our @EXPORT_OK = (@SHA_FUNCTIONS, @HMAC_FUNCTIONS);
```

**Effort**: Trivial (5 minutes). The implementations already exist and work correctly.

---

### Issue 2: IO::Poll not available (CRITICAL)

**Impact**: Blocks the entire IOLoop event reactor at runtime. Even after fixing Issue 1,
Mojo::Reactor::Poll cannot function without `IO::Poll::_poll()`.

**Error** (would appear at runtime after Issue 1 is fixed):
```
Can't locate IO/Poll.pm in @INC
```

**Who needs it**:
- `Mojo::Util` line 13: `use IO::Poll qw(POLLIN POLLPRI);`
- `Mojo::Reactor::Poll` line 5: `use IO::Poll qw(POLLERR POLLHUP POLLIN POLLNVAL POLLOUT POLLPRI);`
- `Mojo::Util::_readable()` and `Mojo::Reactor::Poll::one_tick()` call `IO::Poll::_poll()`

**What `_poll` does**: Wraps the POSIX `poll()` syscall:
```perl
# _poll($timeout_ms, $fd1, $mask1, $fd2, $mask2, ...)
# Modifies mask values in-place with returned events
# Returns count of ready file descriptors
IO::Poll::_poll($timeout, @fd_event_pairs)
```

**Fix**: Create a Java XS backend for `IO::Poll` using `java.nio.channels.Selector`:
- File: `src/main/perl/lib/IO/Poll.pm` (pure-Perl OO wrapper + constant exports)
- File: `src/main/java/org/perlonjava/runtime/perlmodule/IOPoll.java` (Java backend)
- Export constants: POLLIN=1, POLLPRI=2, POLLOUT=4, POLLERR=8, POLLHUP=16, POLLNVAL=32
- Implement `_poll()` function that maps JVM file descriptors to NIO channels

**Effort**: Significant (1-2 days). Requires mapping PerlOnJava's file descriptor model
to Java NIO selectors.

**Alternative**: A lighter approach would implement only the functional `_poll()` interface
that Mojolicious uses (not the full OO `IO::Poll` API), since Mojo bypasses the OO layer
and calls `_poll()` directly.

---

### Issue 3: Hash::Util::FieldHash missing (HIGH)

**Impact**: Blocks `Mojo::DynamicMethods` (used for plugin-registered helper methods),
which cascades to tests using Mojolicious::Lite apps with plugins.

**Error**:
```
Can't locate Hash/Util/FieldHash.pm in @INC
```

**Tests directly affected**:
- t/mojo/dynamic_methods.t
- t/mojolicious/embedded_lite_app.t
- t/mojolicious/json_config_lite_app.t (partially)
- t/mojolicious/lite_app.t
- t/mojolicious/twinkle_lite_app.t

**How Mojo::DynamicMethods uses it** (lines 4, 30-39):
```perl
use Hash::Util::FieldHash qw(fieldhash);
state %dyn_methods;
state $setup = do { fieldhash %dyn_methods; 1 };
$dyn_methods{$object}{$name} = $code;
```

The `fieldhash` function converts a hash to use object identity as keys with automatic
cleanup on garbage collection (inside-out object pattern).

**Fix**: Create a pure-Perl no-op shim at `src/main/perl/lib/Hash/Util/FieldHash.pm`:

```perl
package Hash::Util::FieldHash;
use strict;
use warnings;
our $VERSION = '1.26';
use Exporter 'import';
our @EXPORT_OK = qw(fieldhash fieldhashes);

# No-op: hash works as-is, just no GC-triggered cleanup.
# PerlOnJava's JVM GC handles circular references natively.
sub fieldhash (\%) { $_[0] }
sub fieldhashes { fieldhash($_) for @_; @_ }

1;
```

**Why a no-op is sufficient**: Mojolicious only calls `fieldhash %dyn_methods` once,
then uses the hash normally with object refs as keys. The only downside is entries won't
auto-clean on GC -- this is a minor memory leak but functionally harmless, and consistent
with PerlOnJava's approach to `weaken()` (also a no-op).

**Effort**: Trivial (5 minutes).

---

### Issue 4: Indirect method call parser bug (LOW)

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
(typeglob assignment) so the parser doesn't see it at compile time. When the potential
"class name" contains `::` and the sub is unknown at compile time, the backtrack
condition at line 245 fails to trigger.

The same code works for `MojoMonkeyTest::foo()` because `foo` was defined with `sub`
at compile time.

**Fix location**: `SubroutineParser.java`, line 245. Add `subExists` check to the
backtrack condition -- when the calling function (`is`) is known to exist AND the
potential class name is followed by `(`, prefer function-call interpretation:

```java
// Add: (subExists && token.text.equals("("))
if ((subExists && token.text.equals("("))
    || (isPackage != null && !isPackage)
    || (isPackage == null && !isKnownSub && token.text.equals("(") && !packageName.contains("::"))) {
    parser.tokenIndex = currentIndex2;
```

**Effort**: Small (30 minutes, needs careful testing to avoid regressions).

---

### Issue 5: Compress::Raw::Zlib missing (MEDIUM)

**Impact**: Blocks HTTP response decompression (`Mojo::Content`) and WebSocket
compression (`Mojo::Transaction::WebSocket`). Affects t/mojo/request.t directly.

**Error**:
```
Can't load module Compress::Raw::Zlib
```

**What Mojolicious needs**:
- `Compress::Raw::Zlib::Inflate->new(WindowBits => WANT_GZIP)` -- HTTP gunzip
- `Compress::Raw::Zlib::Deflate->new(...)` -- WebSocket compression
- Constants: `WANT_GZIP`, `Z_STREAM_END`, `Z_SYNC_FLUSH`

**Existing infrastructure**: PerlOnJava already has `Compress::Zlib` with a Java backend
(`CompressZlib.java`, 617 lines) using `java.util.zip.Deflater/Inflater/GZIPOutputStream`.
However, `Compress::Raw::Zlib` is the lower-level OO stream API that Mojolicious uses.

**Fix**: Create `CompressRawZlib.java` reusing `java.util.zip` from existing code:
- Register as `Compress::Raw::Zlib`
- Implement `_deflateInit()` / `_inflateInit()` returning blessed stream objects
- Implement stream methods: `inflate`, `deflate`, `flush`, `total_out`, etc.
- Export zlib constants

**Effort**: Significant (1-2 days). Already tracked as P8 priority in
`dev/modules/smoke_test_investigation.md`.

---

### Issue 6: fork() not supported (KNOWN LIMITATION)

**Impact**: `Mojo::IOLoop::Subprocess` cannot work. Affects t/mojo/subprocess.t and
t/mojo/subprocess_ev.t.

**Note**: `Mojo::Reactor::Poll` does NOT use fork -- it uses `IO::Poll::_poll()` for
I/O multiplexing. The core IOLoop, HTTP server/client, WebSockets, and timers do not
require fork. Only `Subprocess` (for running blocking code in a child process) needs it.

**Workaround**: Not fixable without JVM process forking. Mojolicious applications can
use Java threading via inline Java as an alternative to Subprocess.

## Implementation Plan

### Phase 1: Quick Wins (unblock module loading)

**Goal**: Get Mojo::Util and Mojo::Base to load successfully, enabling ~90% of tests
to at least start running.

| Task | File | Effort |
|------|------|--------|
| Add HMAC functions to Digest::SHA @EXPORT_OK | `src/main/perl/lib/Digest/SHA.pm` | 5 min |
| Create Hash::Util::FieldHash no-op shim | `src/main/perl/lib/Hash/Util/FieldHash.pm` | 5 min |

**Expected outcome**: Tests that don't need IO::Poll at runtime should pass (cache.t,
date.t, eventemitter.t, json_pointer.t, signatures.t already pass; expect collection.t,
bytestream.t, json.t, url.t, path.t, parameters.t, headers.t, cookie.t, template.t,
roles.t, dom.t, exception.t, file.t, log.t, loader.t, and others to start passing).

### Phase 2: Event Loop (unblock IOLoop)

**Goal**: Get `Mojo::Reactor::Poll` working so the IOLoop can process I/O events.

| Task | File | Effort |
|------|------|--------|
| Implement IO::Poll Java backend | `IOPoll.java` + `IO/Poll.pm` | 1-2 days |

**Expected outcome**: IOLoop-dependent tests should start running: ioloop.t, daemon.t,
user_agent.t, websocket.t, and all Test::Mojo-based integration tests.

### Phase 3: HTTP Compression

**Goal**: Get Compress::Raw::Zlib working for HTTP content encoding.

| Task | File | Effort |
|------|------|--------|
| Implement Compress::Raw::Zlib Java backend | `CompressRawZlib.java` | 1-2 days |

**Expected outcome**: request.t, response.t, and compressed content tests should pass.

### Phase 4: Parser Fix

**Goal**: Fix indirect method call disambiguation for runtime-installed subs.

| Task | File | Effort |
|------|------|--------|
| Fix backtrack condition in SubroutineParser | `SubroutineParser.java` | 30 min |

**Expected outcome**: base_util.t should fully pass (4/4 subtests).

## Dependency Chain

```
Mojo::Util  ← requires Digest::SHA HMAC exports (Issue 1)
  └─ uses IO::Poll for _readable() (Issue 2)

Mojo::Base  ← requires Mojo::Util
  └─ optionally loads Hash::Util::FieldHash for DynamicMethods (Issue 3)

Mojo::Reactor::Poll  ← requires IO::Poll (Issue 2)
  └─ core of Mojo::IOLoop

Mojo::IOLoop  ← requires Mojo::Reactor::Poll
  └─ Mojo::IOLoop::Subprocess requires fork() (Issue 6, known limitation)

Mojo::Content  ← requires Compress::Raw::Zlib for gzip (Issue 5)

Almost all Mojolicious modules  ← require Mojo::Base  ← require Mojo::Util
```

## Tests Likely to Pass After Each Phase

### After Phase 1 (Digest::SHA + FieldHash fixes)
Tests that don't depend on IOLoop at runtime:
- t/mojo/cache.t (already passes)
- t/mojo/date.t (already passes)
- t/mojo/eventemitter.t (already passes)
- t/mojo/json_pointer.t (already passes)
- t/mojo/signatures.t (already passes)
- t/mojo/collection.t (likely)
- t/mojo/bytestream.t (likely)
- t/mojo/json.t (likely)
- t/mojo/url.t (likely)
- t/mojo/path.t (likely)
- t/mojo/parameters.t (likely)
- t/mojo/headers.t (likely)
- t/mojo/cookie.t, t/mojo/cookiejar.t (likely)
- t/mojo/template.t (likely)
- t/mojo/roles.t (likely)
- t/mojo/dom.t (likely)
- t/mojo/exception.t (likely)
- t/mojo/file.t (likely)
- t/mojo/log.t (likely)
- t/mojo/loader.t (likely)
- t/mojo/dynamic_methods.t (likely, with FieldHash shim)
- t/mojo/home.t (likely)
- t/mojo/sse.t (likely)
- t/mojo/websocket.t (data framing tests, likely)
- t/mojolicious/pattern.t (likely)
- t/mojolicious/routes.t (likely)
- t/mojolicious/types.t (likely)

### After Phase 2 (IO::Poll)
All tests using Test::Mojo embedded server:
- t/mojo/ioloop.t
- t/mojo/daemon.t
- t/mojo/user_agent.t
- t/mojo/websocket_frames.t
- t/mojolicious/lite_app.t
- t/mojolicious/*_lite_app.t (most)
- t/mojolicious/app.t
- t/test/mojo.t

### After Phase 3 (Compress::Raw::Zlib)
- t/mojo/request.t
- t/mojo/response.t (compressed content tests)
- t/mojo/content.t

## Related Documents
- `dev/modules/smoke_test_investigation.md` -- Compress::Raw::Zlib tracked as P8
- `dev/modules/lwp_useragent.md` -- Related HTTP client support
- `dev/modules/poe.md` -- Related event loop support

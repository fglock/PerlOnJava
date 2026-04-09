# Mojo::IOLoop Support for PerlOnJava

## Status: Phase 4 IN PROGRESS -- 55/108 file-level, dom.t 107/108 (was 1/2), gzip works

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

### After Phase 2 initial fixes (47/108 passing)

| Metric | Count |
|--------|-------|
| Total test programs | 108 |
| Passed (t/mojo/) | 34 of 63 |
| Passed (t/mojolicious/) | 11 of 43 |
| Passed (t/pod*) | 2 of 2 |
| **Total Passed** | **47** |

#### New passes from Phase 2 initial (32 gained)

**t/mojo/** (21 new):
base_util.t, daemon_ipv6_tls.t, dynamic_methods.t, hypnotoad.t,
ioloop_ipv6.t, ioloop_tls.t, json_xs.t, log.t, morbo.t, prefork.t,
promise_async_await.t, reactor_ev.t, reactor_poll.t, subprocess.t, subprocess_ev.t,
tls.t, user_agent.t, user_agent_online.t, user_agent_socks.t, user_agent_tls.t,
user_agent_unix.t, websocket_proxy_tls.t

**t/mojolicious/** (7 new):
app.t, command.t, commands.t, dispatch.t, lite_app.t, renderer.t, sse_lite_app.t

**t/pod** (2 new): pod.t, pod_coverage.t

### After Phase 2 near-miss fixes (62/108 passing)

| Metric | Count |
|--------|-------|
| Total test programs | 108 |
| Passed (t/mojo/) | 45 of 63 |
| Passed (t/mojolicious/) | 15 of 43 |
| Passed (t/pod*) | 2 of 2 |
| **Total Passed** | **62** |
| Subtests (t/mojo/) | 635/880 (72.2%) |
| Subtests (t/mojolicious/) | 542/652 (83.1%) |
| **Total Subtests** | **1177/1532 (76.9%)** |

#### New passes from near-miss fixes (15 gained)

**t/mojo/** (11 new):
content.t(8/8), cookiejar.t(22/22), file_download.t(6/6), parameters.t(19/19),
path.t(15/15), psgi.t(9/9), request_cgi.t(16/16), response.t(23/23),
url.t(38/38), util.t(51/51), websocket_proxy.t(3/3)

**t/mojolicious/** (4 new):
log_lite_app.t(2/2), signatures_lite_app.t(2/2), tls_lite_app.t(0/0),
websocket_lite_app.t(14/14)

#### Fixes that flipped near-miss tests
- **`looks_like_number`** (ScalarUtil.java): content.t, cookiejar.t now fully pass
- **`Encode::decode` $check** (Encode.java): util.t now fully passes (51/51)
- **`pack Q/q` 64-bit** (NumericPackHandler.java): websocket_frames.t 14→21 subtests
- **`tie` with blessed ref** (TieOperators.java): IO::Compress properly tied
- **`url.t`**: 36/38→38/38 (parameters.t similarly fixed)

#### Remaining near-miss tests (1-2 subtests from passing)

| Test File | Subtests | Blocker |
|-----------|----------|---------|
| t/mojo/bytestream.t | 30/31 | "gzip/gunzip" subtest has no tests |
| t/mojo/collection.t | 18/19 | "TO_JSON" — JSON number encoding |
| t/mojo/cgi.t | 9/10 | 1 subtest failing |
| t/mojo/exception.t | 14/15 | 1 subtest failing |
| t/mojo/transactor.t | 21/22 | "Multipart form with real file" |
| t/mojo/websocket_frames.t | 21/22 | 1 remaining subtest |
| t/mojo/base.t | 8/9 | "Weaken" (known limitation) |
| t/mojo/request.t | 31/33 | 2 subtests failing |

#### Still-failing tests by score

**t/mojo/** (18 failing):

| Test | Score | Notes |
|------|-------|-------|
| request.t | 31/33 | Near-miss |
| bytestream.t | 30/31 | Near-miss |
| collection.t | 18/19 | Near-miss |
| transactor.t | 21/22 | Near-miss |
| websocket_frames.t | 21/22 | Near-miss |
| file.t | 17/23 | |
| template.t | 17/226 | Major failures |
| daemon.t | 16/19 | |
| exception.t | 14/15 | Near-miss |
| json.t | 14/17 | |
| asset.t | 13/17 | |
| loader.t | 12/15 | |
| cgi.t | 9/10 | Near-miss |
| ioloop.t | 9/12 | |
| base.t | 8/9 | weaken limitation |
| websocket.t | 8/10 | |
| promise.t | 5/7 | |
| dom.t | 1/2 | |

**t/mojolicious/** (28 not passing):

| Test | Score | Notes |
|------|-------|-------|
| restful_lite_app.t | 41/91 | |
| charset_lite_app.t | 38/45 | |
| static_lite_app.t | 34/37 | Near-miss |
| validation_lite_app.t | 18/22 | |
| dispatcher_lite_app.t | 11/13 | |
| rebased_lite_app.t | 6/24 | |
| static_prefix_lite_app.t | 4/10 | |
| multipath_lite_app.t | 3/7 | |
| json_config_lite_app.t | 2/3 | Near-miss |
| yaml_config_lite_app.t | 2/3 | Near-miss |
| longpolling_lite_app.t | 2/10 | |
| upload_lite_app.t | 1/2 | Near-miss |
| 6 timeouts | 0/0 | exception/group/layouted/production/tag_helper/testing |
| 10 errors | 0/0 | embedded/external/ojo/proxy/twinkle/upload_stream |

### After Phase 3 fixes (65/108 passing)

| Metric | Count |
|--------|-------|
| Total test programs | 108 |
| Passed (t/mojo/) | 46 of 63 |
| Passed (t/mojolicious/) | 17 of 43 |
| Passed (t/pod*) | 2 of 2 |
| **Total Passed** | **65** |
| Subtests (t/mojo/) | 756/835 (90.5%) |
| Subtests (t/mojolicious/) | 1173/1303 (90.0%) |
| **Total Subtests** | **1929/2138 (90.2%)** |

#### Phase 3 fixes applied
1. **Warning category aliases** (WarningFlags.java): Added `ambiguous`, `bareword`,
   `parenthesis`, `precedence`, `printf`, `semicolon` as shortcuts. Unblocked
   Mojo::Template rendering (210+ subtests), config loading, and error pages.
2. **Regex dot UNIX_LINES** (RegexFlags.java): Added `Pattern.UNIX_LINES` so `.`
   only excludes `\n`, not `\r`. Fixed HTTP chunked parsing.
3. **IO::Handle SEEK constants** (IO/Handle.pm): Added SEEK_SET/CUR/END. Fixed
   IO::Compress::Base seek operations for gzip/gunzip.
4. **Deflate/Inflate scalar context** (CompressRawZlib.java): Return only object
   (not status) in scalar context. Fixed WebSocket compression.
5. **++Boolean ClassCastException** (RuntimeScalar.java): Read Boolean value before
   changing type to INTEGER to prevent getInt() fast path from casting Boolean as Integer.

#### New file-level passes from Phase 3 (3 net gained)
**t/mojo/** (+1 net): cgi.t(10/10), websocket_frames.t(23/23) gained;
response.t regressed (23/23→28/29, more subtests exposed by template fix)

**t/mojolicious/** (+2 net): charset_lite_app.t(45/45), multipath_lite_app.t(7/7),
testing_app.t(42/42), upload_lite_app.t(8/8) gained;
lite_app.t(15/15→298/302), websocket_lite_app.t(14/14→34/35) regressed
(more subtests exposed by template fix)

#### Key subtest improvements from Phase 3
| Test | Before | After | Change |
|------|--------|-------|--------|
| template.t | 17/226 | 150/196 | +133 |
| lite_app.t | 15/15 | 298/302 | +283 |
| tag_helper_lite_app.t | 0/0 (timeout) | 78/90 | +78 |
| production_app.t | 0/0 (timeout) | 71/95 | +71 |
| testing_app.t | 0/0 (timeout) | 42/42 | +42 |
| group_lite_app.t | 0/0 (timeout) | 65/66 | +65 |
| layouted_lite_app.t | 0/0 (timeout) | 30/35 | +30 |
| websocket_lite_app.t | 14/14 | 34/35 | +20 |

### After Phase 4 fixes (55/108 passing, massive subtest gains)

| Metric | Count |
|--------|-------|
| Total test programs | 108 |
| Passed (t/mojo/) | 42 of 63 |
| Passed (t/mojolicious/) | 11 of 43 |
| Passed (t/pod*) | 2 of 2 |
| **Total Passed** | **55** |
| Timeout | 7 |

**Note on file-level count**: The file-level pass count dropped from 65 to 55 despite
significant subtest improvements. This is because Phase 4 fixes (especially the RC1
DOM/HTML fix) exposed many more subtests in previously-passing tests, causing them to
reach new code paths that crash on pre-existing issues (DESTROY not implemented,
`Unknown encoding 'Latin-1'`, `Can't write to file ""`, indirect method parsing).
Tests like request.t (41/41), path.t (15/15), log.t (8/8), reactor_poll.t (7/7),
commands.t (37/37), and renderer.t (8/8) pass ALL subtests but exit 255 due to
crashes after the last subtest.

#### Phase 4 fixes applied

1. **RC1 - Mojo::DOM HTML parsing / CSS selectors** (RuntimeRegex.java):
   Fix zero-length `/gc` match semantics. After a zero-length match, Perl retries
   at the SAME position with NOTEMPTY constraint before falling back to bumpalong.
   Added `notemptyPattern` variant that prepends `(?=[\s\S])` and converts `??` to `?`.
   This fixed Mojo::DOM::HTML `$TOKEN_RE` and CSS `>` child combinator.
   **dom.t: 1/2 -> 107/108** (+106 subtests).

2. **RC5 - IO::Compress::Gzip** (TieOperators.java):
   Remove `tiedDestroy()` calls from `untie()`. In Perl, DESTROY is only called
   during garbage collection, not during untie. The previous behavior caused
   IO::Compress::Base::DESTROY to fire prematurely, clearing glob hash entries
   before `close()` finished writing trailers. Gzip compression now works.

3. **RC6 - `re::regexp_pattern()`** (Re.java):
   Implement `re::regexp_pattern()` which extracts pattern string and modifiers
   from a compiled regex. Returns `(pattern, modifiers)` in list context,
   `(?flags:pattern)` in scalar context.

#### Key subtest improvements from Phase 4

| Test | Phase 3 | Phase 4 | Change |
|------|---------|---------|--------|
| dom.t | 1/2 | 107/108 | **+106** |
| response.t | 28/29 | 49/52 | +21 |
| request.t | 31/33 | 41/41 | +10 |
| restful_lite_app.t | 41/91 | 90/91 | **+49** |
| tag_helper_lite_app.t | 78/90 | 86/90 | +8 |
| production_app.t | 71/95 | 52/60 | (fewer subtests run) |
| bytestream.t | 30/31 | 31/32 | +1 |
| lite_app.t | 298/302 | 298/302 | (stable) |

#### Tests that pass all subtests but crash (exit 255)

These tests pass every subtest but die after the last one due to pre-existing issues
(DESTROY cleanup, encoding errors, indirect method parsing). They would be file-level
passes if DESTROY were implemented or the crash-after-tests were fixed:

| Test | Subtests | Crash Reason |
|------|----------|--------------|
| request.t | 41/41 | `Can't write to file ""` (DESTROY temp cleanup) |
| path.t | 15/15 | `Unknown encoding 'Latin-1'` |
| log.t | 8/8 | `Log messages already being captured` (DESTROY) |
| reactor_poll.t | 7/7 | Stack trace after all tests |
| renderer.t | 8/8 | `Log messages already being captured` (DESTROY) |
| commands.t | 37/37 | `Can't call method "server" on undef` |
| command.t | 3/3 | `Can't write to file ""` (DESTROY temp cleanup) |

#### Regressions from Phase 3 (tests that were passing, now fail)

Most "regressions" are tests that now run MORE subtests due to fixes, reaching
new code paths that crash on pre-existing issues. Key causes:

1. **DESTROY not implemented** (5 tests): log.t, lite_app.t, renderer.t —
   `Mojo::Log->capture` uses a DESTROY-based guard object; second `capture()`
   call on same logger croaks because the guard never ran DESTROY to reset state.
   Also: request.t, response.t, command.t — temp file DESTROY cleanup.

2. **Indirect method parsing** (2 tests): base_util.t, util.t —
   `is MojoMonkeyTest::bar(), "bar"` parsed as `MojoMonkeyTest::bar->is(...)`.

3. **`Unknown encoding 'Latin-1'`** (1 test): path.t — missing encoding alias.

4. **Timeouts** (3 tests): file_download.t, transactor.t, sse_lite_app.t —
   IO::Poll/reactor issues causing test server hangs.

### Remaining Failed Tests by Root Cause

| Root Cause | Tests Affected | Severity |
|------------|---------------|----------|
| ~~Digest::SHA missing HMAC exports~~ | ~~90~~ | **FIXED** |
| ~~Compress::Raw::Zlib missing~~ | ~~100~~ | **FIXED** |
| ~~IO::Poll not available~~ | ~~100~~ | **FIXED** |
| ~~Hash::Util::FieldHash missing~~ | ~~5~~ | **FIXED** |
| ~~DynamicMethods empty method name~~ | ~~15~~ | **FIXED** (`can('SUPER::can')` in Universal.java) |
| ~~`is_regexp` export missing~~ | ~~5~~ | **FIXED** (`re::is_regexp` export in Re.java) |
| ~~`toGlob` is null (glob coercion)~~ | ~~3~~ | **FIXED** (anonymous glob null guard in RuntimeGlob.java) |
| ~~ASCII POSIX char classes~~ | ~~2~~ | **FIXED** (13 `\p{PosixXxx}` variants in UnicodeResolver.java) |
| ~~`local *STDOUT = $fh` IO redirect~~ | ~~2~~ | **FIXED** (selectedHandle update in RuntimeGlob.java) |
| ~~`looks_like_number` broken~~ | ~~3~~ | **FIXED** (delegate to ScalarUtils in ScalarUtil.java) |
| ~~`tie` blessed ref invocant~~ | ~~2~~ | **FIXED** (TieOperators.java) |
| ~~`Encode::decode` $check param~~ | ~~1~~ | **FIXED** (Encode.java) |
| ~~`pack`/`unpack` Q/q 64-bit~~ | ~~1~~ | **FIXED** (NumericPackHandler/FormatHandler) |
| JSON number encoding | ~10 | **High** -- `[1]` becomes `["1"]`, deep scalar type issue |
| ~~DOM `/g` regex zero-length match~~ | ~~5~~ | **FIXED** (Phase 4 RC1: notempty pattern in RuntimeRegex.java) |
| ~~`re::regexp_pattern()` missing~~ | ~~3~~ | **FIXED** (Phase 4 RC6: Re.java) |
| ~~IO::Compress::Gzip `untie` calls DESTROY~~ | ~~3~~ | **FIXED** (Phase 4 RC5: TieOperators.java) |
| Mojo::Template failures | ~20 | **High** -- 150/196 subtests, blocks `*_lite_app.t` tests |
| DESTROY not implemented | ~10 | **High** -- Log capture guard, temp file cleanup, asset cleanup |
| Indirect method parsing | ~2 | **Medium** -- `is Foo::bar()` parsed as `Foo::bar->is()` |
| `Unknown encoding 'Latin-1'` | ~1 | **Low** -- missing Encode alias |
| IO::Poll timeout tests | ~6 | **Medium** -- test server hangs, 300s timeout |
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

---

### Issue 7: `local *STDOUT = $fh` IO redirection incomplete -- IN PROGRESS

**Status**: **TODO** (Phase 2)

**Impact**: ~2 tests (bytestream.t "say and autojoin", others using IO capture patterns).

**Error**: After `local *STDOUT = $fh`, bare `print "hello"` still goes to the original
STDOUT instead of the redirected file handle.

**Root cause**: `RuntimeGlob.set(RuntimeGlob value)` replaces the IO slot but **never
updates `RuntimeIO.selectedHandle`**. There are two paths for `print`:

| Path | Resolution | Status |
|------|-----------|--------|
| `print STDOUT "hi"` (explicit) | Name-based lookup via `GlobalVariable.getGlobalIO()` | **Works** |
| `print "hi"` (bare) | Static `RuntimeIO.selectedHandle` | **Broken** |

When `local *STDOUT` runs, `dynamicSaveState()` correctly saves the original
`selectedHandle` and creates a stub IO pointing `selectedHandle` to it. When the
subsequent `*STDOUT = $fh` assignment runs, `set(RuntimeGlob)` replaces `this.IO` with
the file's IO (so explicit `print STDOUT` works via name lookup), but leaves
`selectedHandle` pointing to the orphaned stub.

By contrast, `open(STDOUT, '>', $file)` works correctly because it calls `setIO()` which
has the `selectedHandle` update check (lines 568-570 and 592-596 in RuntimeGlob.java).

**Fix**: In `RuntimeGlob.set(RuntimeGlob value)`, add `selectedHandle` update logic
in both the anonymous glob path and the named glob path, mirroring what `setIO()` does:

```java
// Before replacing this.IO, save old IO for selectedHandle check
RuntimeIO oldRuntimeIO = null;
if (this.IO != null && this.IO.value instanceof RuntimeIO rio) {
    oldRuntimeIO = rio;
}
// ... existing IO replacement ...
// After replacing IO, update selectedHandle if needed
if (oldRuntimeIO != null && oldRuntimeIO == RuntimeIO.selectedHandle) {
    if (newIO.value instanceof RuntimeIO newRIO) {
        RuntimeIO.selectedHandle = newRIO;
    }
}
```

`dynamicRestoreState()` already correctly restores the original `selectedHandle`, so the
restore path needs no changes.

**File**: `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeGlob.java`, method
`set(RuntimeGlob value)`, lines ~327-335 (anonymous path) and ~367-372 (named path).

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

### Current Status: Phase 3 COMPLETE -- 65/108 (90.2% subtests)

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
- [x] Phase 2: `re::is_regexp` export in Re.java (2026-04-09)
- [x] Phase 2: `can('SUPER::can')` fix in Universal.java (2026-04-09)
- [x] Phase 2: Anonymous glob NPE fix in RuntimeGlob.java (2026-04-09)
- [x] Phase 2: 13 ASCII POSIX char classes in UnicodeResolver.java (2026-04-09)
- [x] Phase 2: Zero-length match bumpalong in RuntimeRegex.java (2026-04-09)
- [x] Phase 2: `local *STDOUT = $fh` IO redirection fix in RuntimeGlob.java (2026-04-09)
- [x] Mojo test count: 15/108 -> 47/108 (2026-04-09)
- [x] Phase 2: `looks_like_number` fix in ScalarUtil.java (2026-04-09)
- [x] Phase 2: `tie` with blessed ref invocant fix in TieOperators.java (2026-04-09)
- [x] Phase 2: `Encode::decode` $check parameter in Encode.java (2026-04-09)
- [x] Phase 2: `pack`/`unpack` Q/q 64-bit support in NumericPackHandler/FormatHandler (2026-04-09)
- [x] Mojo test count: 47/108 -> 62/108 (2026-04-09)
- [x] Phase 3: Missing warning category aliases in WarningFlags.java (2026-04-09)
- [x] Phase 3: Regex dot UNIX_LINES flag in RegexFlags.java (2026-04-09)
- [x] Phase 3: IO::Handle SEEK_SET/CUR/END constants (2026-04-09)
- [x] Phase 3: Deflate/Inflate scalar context in CompressRawZlib.java (2026-04-09)
- [x] Phase 3: ++Boolean ClassCastException fix in RuntimeScalar.java (2026-04-09)
- [x] Mojo test count: 62/108 -> 65/108, subtests 76.9% -> 90.2% (2026-04-09)

### Files Created/Modified in Phase 1
- `src/main/perl/lib/Digest/SHA.pm` -- HMAC functions added to @EXPORT_OK
- `src/main/perl/lib/Hash/Util/FieldHash.pm` -- NEW, no-op shim
- `src/main/perl/lib/Compress/Raw/Zlib.pm` -- REPLACED with CPAN version
- `src/main/java/org/perlonjava/runtime/perlmodule/CompressRawZlib.java` -- NEW, Java XS backend
- `src/main/perl/lib/IO/Poll.pm` -- NEW, CPAN source + XSLoader
- `src/main/java/org/perlonjava/runtime/perlmodule/IOPoll.java` -- NEW, _poll() + constants
- `src/main/java/org/perlonjava/runtime/perlmodule/Socket.java` -- Added inet_pton, inet_ntop
- `src/main/perl/lib/Socket.pm` -- Added inet_pton, inet_ntop to @EXPORT

### Files Modified in Phase 2
- `src/main/java/org/perlonjava/runtime/perlmodule/Re.java` -- `re::is_regexp` export
- `src/main/java/org/perlonjava/runtime/perlmodule/Universal.java` -- `SUPER::` in can()
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeGlob.java` -- anonymous glob null guard + selectedHandle fix
- `src/main/java/org/perlonjava/runtime/regex/UnicodeResolver.java` -- 13 PosixXxx properties
- `src/main/java/org/perlonjava/runtime/regex/RuntimeRegex.java` -- zero-length match bumpalong

### Files Modified in Phase 3
- `src/main/java/org/perlonjava/runtime/runtimetypes/WarningFlags.java` -- 6 missing warning category aliases
- `src/main/java/org/perlonjava/runtime/regex/RegexFlags.java` -- UNIX_LINES flag for dot behavior
- `src/main/perl/lib/IO/Handle.pm` -- SEEK_SET/SEEK_CUR/SEEK_END constants
- `src/main/java/org/perlonjava/runtime/perlmodule/CompressRawZlib.java` -- scalar context for deflateInit/inflateInit
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeScalar.java` -- ++Boolean and --Boolean fix

### Phase 4 Plan: Root Cause Analysis and Targeted Fixes

Phase 4 targets **~150+ failing subtests across 15 test files**, grouped by
root cause. Fixes are ordered by impact (most failures fixed per change).

#### Root Cause Summary (all remaining failures)

| # | Root Cause | Failures | Test Files Affected |
|---|-----------|----------|---------------------|
| RC1 | Mojo::DOM CSS selector engine broken | 49 | restful_lite_app.t |
| RC2 | Exception line-number mapping in templates | 42 | template.t, lite_app.t |
| RC3 | Double-render / reactor corruption (404 paths) | 38+ | production_app.t, tag_helper_lite_app.t, exception_lite_app.t |
| RC4 | Layout/include/content_for rendering | 5 | layouted_lite_app.t |
| RC5 | IO::Compress::Gzip broken | 3 | response.t, request.t, bytestream.t |
| RC6 | Missing `re::regexp_pattern()` | 3 | dispatcher_lite_app.t, restful_lite_app.t |
| RC7 | Mojo::Template `render_file()` returns path | 2 | template.t |
| RC8 | Config file template preprocessing | 2 | json_config_lite_app.t, yaml_config_lite_app.t |
| RC9 | JSON numbers serialized as strings | 1 | websocket_lite_app.t |
| RC10 | Cookie persistence in Test::Mojo | 1 | group_lite_app.t |
| RC11 | `continue` block not implemented | 1 | lite_app.t |
| RC12 | Bareword detection in templates | 1 | template.t |
| RC13 | transactor.t timeout regression | ? | transactor.t |

#### Detailed Failure Inventory

**t/mojo/ failures (near-miss):**

| Test File | Score | Failing Subtests | Root Cause |
|-----------|-------|------------------|------------|
| template.t | 150/196 | 39 exception line-mapping, 2 render_file, 4 code context, 1 bareword | RC2, RC7, RC12 |
| response.t | 28/29 | #29 gzip compressed response | RC5 |
| request.t | 32/33 | #33 gzip compressed request | RC5 |
| bytestream.t | 30/31 | #31 gzip/gunzip | RC5 |
| collection.t | 18/19 | JSON number encoding | RC9 |
| exception.t | 14/15 | caller() absolute paths | Minor |
| transactor.t | timeout | Was 21/22, now times out | RC13 |

**t/mojolicious/ failures:**

| Test File | Score | Failing Subtests | Root Cause |
|-----------|-------|------------------|------------|
| restful_lite_app.t | 41/91 | 49 CSS selector `undef`, 1 500-for-404 | RC1, RC6 |
| production_app.t | 71/95 | 24 double-render + timeout on 404 paths | RC3 |
| tag_helper_lite_app.t | 78/90 | 12 double-render + timeout on form POSTs | RC3 |
| layouted_lite_app.t | 30/35 | 5 layout/include/content_for issues | RC4 |
| lite_app.t | 298/302 | 3 exception annotation, 1 `continue` block | RC2, RC11 |
| dispatcher_lite_app.t | 11/13 | 2 missing `regexp_pattern()` | RC6 |
| exception_lite_app.t | timeout | Connect timeout after first subtest | RC3 |
| websocket_lite_app.t | 34/35 | 1 JSON numbers as strings | RC9 |
| json_config_lite_app.t | 2/3 | 1 template preprocessing | RC8 |
| yaml_config_lite_app.t | 2/3 | 1 template preprocessing | RC8 |
| group_lite_app.t | 65/66 | 1 cookie persistence | RC10 |

#### Fix Priority and Implementation Plan

**Tier 1 — High Impact (fixes 90+ subtests, may flip 3+ test files)**

1. **RC1: Fix Mojo::DOM CSS selector engine** (~49 failures)
   - `Mojo::DOM->at('html > body')` and `->at('just')` return `undef`
   - The HTTP response is correct; CSS selector parsing in `Mojo::DOM::CSS` fails
   - Likely a regex or string operation in the CSS parser not working in PerlOnJava
   - Debug: `./jperl -e 'use Mojo::DOM; my $d = Mojo::DOM->new("<html><body>hi</body></html>"); print $d->at("html > body")->text'`

2. **RC3: Fix double-render / reactor corruption** (~38+ failures)
   - `Mojo::Reactor::Poll: I/O watcher failed: A response has already been rendered at Controller.pm line 189`
   - Happens on 404 paths and form submissions; reactor breaks, connections hang until timeout
   - Likely PerlOnJava's exception handling differs in the Mojo dispatch pipeline
   - Affects production_app.t, tag_helper_lite_app.t, exception_lite_app.t

3. **RC2: Fix exception line-number mapping in templates** (~42 failures)
   - `Mojo::Exception->lines_before/line/lines_after` report test-file line numbers
   - The exception context reads from the wrong source (test file vs template)
   - Mojo::Template relies on `caller()` and `die` line annotations; PerlOnJava may
     not propagate eval'd source locations correctly

**Tier 2 — Medium Impact (fixes ~15 subtests, may flip 3+ test files)**

4. **RC6: Implement `re::regexp_pattern()`** (~3 failures, unblocks error pages)
   - Listed as TODO in `Re.java:34`
   - Returns `(pattern, modifiers)` in list context, `(?flags:pattern)` in scalar
   - Called by Mojo's `mojo/debug.html.ep` error template
   - Fixes dispatcher_lite_app.t (2), restful_lite_app.t 500-for-404 (1)

5. **RC5: Fix IO::Compress::Gzip** (~3 failures)
   - `IO::Compress::Gzip->new()` returns `undef`; `close()` then fails
   - Our `CompressRawZlib.java` handles Deflate/Inflate but Gzip wrapper not working
   - Fixes bytestream.t, request.t, response.t (all need just 1 more subtest)

6. **RC4: Fix layout/include/content_for rendering** (~5 failures)
   - Layout wrapping lost when templates use `include`
   - Route defaults not merged into stash before action callback
   - `content_for` blocks from hooks/inner templates don't propagate
   - Fixes layouted_lite_app.t (30/35 → 35/35)

7. **RC8: Fix config file template preprocessing** (~2 failures)
   - Mojo::Template should strip `%# comment` lines from JSON/YAML config files
   - Related to `render_file()` issue (RC7)
   - Fixes json_config_lite_app.t and yaml_config_lite_app.t (both 2/3 → 3/3)

**Tier 3 — Low Impact / Deferred**

8. **RC9: JSON number encoding** (~1 failure) — Fundamental SV type tracking issue
9. **RC10: Cookie persistence** (~1 failure) — Cookie jar or `//` operator issue
10. **RC11: `continue` block** (~1 failure) — Language feature not yet implemented
11. **RC7: `render_file()` path** (~2 failures) — `Mojo::Template::render_file` bug
12. **RC12: Bareword detection** (~1 failure) — `use strict` enforcement in sandboxes
13. **RC13: transactor.t timeout regression** — Was 21/22, investigate what changed

#### Projected Impact

If Tier 1 fixes succeed:
- restful_lite_app.t: 41/91 → ~90/91 (file passes)
- production_app.t: 71/95 → ~95/95 (file passes)
- tag_helper_lite_app.t: 78/90 → ~90/90 (file passes)
- exception_lite_app.t: timeout → passes
- template.t: 150/196 → ~190/196

If Tier 1+2 fixes succeed:
- dispatcher_lite_app.t: 11/13 → 13/13 (file passes)
- bytestream.t: 30/31 → 31/31 (file passes)
- request.t: 32/33 → 33/33 (file passes)
- response.t: 28/29 → 29/29 (file passes)
- layouted_lite_app.t: 30/35 → 35/35 (file passes)
- json_config_lite_app.t: 2/3 → 3/3 (file passes)
- yaml_config_lite_app.t: 2/3 → 3/3 (file passes)

**Estimated new total: 65/108 → ~75-80/108 test files passing**

## Related Documents
- `dev/modules/smoke_test_investigation.md` -- Compress::Raw::Zlib tracked as P8
- `dev/modules/lwp_useragent.md` -- Related HTTP client support
- `dev/modules/poe.md` -- Related event loop support

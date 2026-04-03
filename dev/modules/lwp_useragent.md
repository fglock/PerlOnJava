# LWP::UserAgent Support for PerlOnJava

## Status: Phase 7 Complete

**Branch**: `fix/lwp-useragent-support`
**Date started**: 2026-04-03

## Background

LWP::UserAgent (libwww-perl) is a top-20 CPAN module providing the standard HTTP
client library for Perl. It was previously blocked on HTTP::Message, which has since
been fixed. Running `./jcpan -j 8 -t LWP::UserAgent` now installs and partially
works, but several issues prevent full test coverage.

## Current State (after Phase 7b)

Running all 22 test files via `jcpan -t LWP::UserAgent`:
- **314/316 subtests pass** (99.4%), 2 TODO failures in 1 file
- **20/22 test files pass**, 1 skipped (NNTP network), 1 error (XS Test::LeakTrace)
- All 4 daemon-based tests fully pass:
  - t/local/http.t: **136/136** (Unicode title encoding fixed)
  - t/robot/ua-get.t: **18/18**
  - t/robot/ua.t: **14/14**
  - t/redirect.t: **4/4** (all passing)
- HTML::HeadParser callback chain works (ua.t 51/51)
- Socket sysread/syswrite work for HTTP::Daemon request parsing
- JVM startup (~1.2s) fits within talk-to-ourself's 5-second timeout

### Test Results Breakdown

| Test File | Result | Tests | Notes |
|-----------|--------|-------|-------|
| t/00-report-prereqs.t | PASS | 1/1 | |
| t/10-attrs.t | PASS | 9/9 | |
| t/base/default_content_type.t | PASS | 18/18 | |
| t/base/protocols.t | PASS | 7/7 | |
| t/base/protocols/nntp.t | SKIP | 0/0 | nntp.perl.org unstable |
| t/base/proxy.t | PASS | 8/8 | |
| t/base/proxy_request.t | PASS | 9/9 | |
| t/base/simple.t | PASS | 1/1 | |
| t/base/ua.t | **PASS** | 51/51 | Fixed in Phase 7a |
| t/base/ua_handlers.t | PASS | 3/3 | |
| t/leak/no_leak.t | ERROR | 0/0 | Test::LeakTrace is XS-only (won't fix) |
| t/local/autoload-get.t | PASS | 4/4 | |
| t/local/autoload.t | PASS | 4/4 | |
| t/local/cookie_jar.t | PASS | 8/8 | |
| t/local/download_to_fh.t | FAIL | 3/5 | 2 TODO failures (expected: mirror doesn't support filehandles) |
| t/local/get.t | PASS | 7/7 | |
| t/local/http.t | **PASS** | 136/136 | Fixed in Phase 7b (UTF-8 title encoding) |
| t/local/httpsub.t | PASS | 2/2 | |
| t/local/protosub.t | PASS | 7/7 | |
| t/redirect.t | **PASS** | 4/4 | Fixed in Phase 7b |
| t/robot/ua-get.t | **PASS** | 18/18 | |
| t/robot/ua.t | **PASS** | 14/14 | |

## Issues Found

### P0: MakeMaker ignores TESTS parameter (only 3 tests run via jcpan) -- FIXED

**Fix**: Read `$args->{test}{TESTS}` in `ExtUtils/MakeMaker.pm` instead of
hardcoding `t/*.t`.

### P1: `exists(&constant_sub)` fails after constant inlining -- FIXED

**Fix**: Skip constant folding under the `&` sigil in `ConstantFoldingVisitor.java`.
The `&Name` form refers to the subroutine itself, not its return value.

### P2: "Unknown encoding: locale" in Encode -- FIXED

**Impact**: t/base/proxy.t (5 tests) and t/base/ua.t (crashes after test 39)
**Root cause**: Java-side `Encode.decode()` calls `getCharset("locale")` directly,
bypassing Perl-side `Encode::Alias` resolution. `Encode::Locale` registers "locale"
as an alias for the system charset (e.g. "UTF-8"), but the Java code doesn't see it.
**Fix**: Added "locale" and "locale_fs" as aliases mapping to `Charset.defaultCharset()`
in `Encode.java`'s CHARSET_ALIASES static block.

### P3: IO::Socket::IP missing -- FIXED

**Impact**: t/local/http.t, t/robot/ua-get.t, t/robot/ua.t (3 files)
**Root cause**: IO::Socket::IP is a core Perl module (since 5.20) at
`perl5/cpan/IO-Socket-IP/` but not imported into PerlOnJava. HTTP::Daemon v6.05+
inherits from it directly (`our @ISA = qw(IO::Socket::IP)`).
**Fix**:
1. Added IO::Socket::IP to `dev/import-perl5/config.yaml` and copied file
2. Implemented `getaddrinfo()` and `sockaddr_family()` in `Socket.java`
3. Added constants: `AI_PASSIVE`, `AI_CANONNAME`, `AI_NUMERICHOST`, `AI_ADDRCONFIG`,
   `NI_NUMERICHOST`, `NI_NUMERICSERV`, `NI_DGRAM`, `NIx_NOHOST`, `NIx_NOSERV`,
   `EAI_NONAME`, `IPV6_V6ONLY`, `SO_REUSEPORT`
4. Updated `Socket.pm` @EXPORT list

### P4: File::Temp missing IO::Handle methods -- FIXED

**Impact**: t/local/download_to_fh.t (1 file)
**Root cause**: PerlOnJava's `File::Temp` uses AUTOLOAD to delegate to `$self->{_fh}`,
but `_fh` is a raw filehandle that doesn't have `IO::Handle` methods like `printflush`.
In standard Perl, File::Temp ISA IO::Handle.
**Fix**: Added explicit `close`, `seek`, `read`, `binmode`, `getline`, `getlines`,
and `printflush` methods to File::Temp that delegate to `CORE::*` builtins on `_fh`.

### P5: utf8::downgrade crashes on read-only scalars (protosub.t) -- FIXED

**Impact**: t/local/protosub.t (1 test)
**Root cause**: `Utf8.java` `downgrade()` attempts `scalar.set()` on
`RuntimeScalarReadOnly` (string literals), causing silent exception.
**Fix**: Check `instanceof RuntimeScalarReadOnly` before `scalar.set()`. If read-only
but the string CAN be represented in ISO-8859-1, return true (downgrade is logically
successful, skip in-place mutation).
**Files**: `src/main/java/org/perlonjava/runtime/perlmodule/Utf8.java`

### P6: openhandle() and open dup don't handle blessed objects with *{} overload -- FIXED

**Impact**: t/local/download_to_fh.t (1 test, getstore into File::Temp)
**Root cause**: Two bugs:
1. `Scalar::Util::openhandle()` in `ScalarUtil.java` only checks GLOB/GLOBREFERENCE
   types, but File::Temp objects are HASHREFERENCE with `*{}` overloading.
2. `open(my $fh, '>&=', $obj)` in `IOOperator.java` only checks GLOB/GLOBREFERENCE.
**Fix**:
1. `ScalarUtil.java`: Handle blessed objects with `*{}` overloading via `globDeref()`.
2. `IOOperator.java`: Try `getRuntimeIO()` before string-name fallback.
**Files**: `ScalarUtil.java`, `IOOperator.java`

### P7: socket() builtin has multiple bugs preventing all socket operations -- FIXED

**Impact**: t/local/http.t, t/redirect.t, t/robot/ua-get.t, t/robot/ua.t (4 files)
**Root cause**: Five sub-bugs in the socket implementation, all fixed:

**P7a: socket() doesn't set the IO slot of the glob** -- FIXED
Changed to follow `open()` pattern: extract glob, call `targetGlob.setIO(socketIO)`.

**P7b: socket() always creates ServerSocket for SOCK_STREAM** -- FIXED
Changed to create `SocketChannel.open()` (client-capable), with lazy ServerSocket
conversion in `listen()`. Added `SocketIO(SocketChannel, ProtocolFamily)` constructor.

**P7c: listen() implementation is wrong** -- FIXED
Rewrote to lazily convert SocketChannel → ServerSocketChannel, bind with proper
backlog. Re-applies stored SO_REUSEADDR option during conversion.

**P7d: sockaddr_in byte order inconsistency** -- FIXED
Standardized `getaddrinfo()` and `sockaddr_family()` to big-endian, matching
`pack_sockaddr_in()` and `parseSockaddrIn()`.

**P7e: accept() builtin is incomplete** -- FIXED
Creates new SocketIO from accepted Socket, wraps in RuntimeIO, associates with
the new socket handle glob. Returns packed sockaddr of remote peer.

### P7-extra: Additional bugs found during Phase 4 -- FIXED

**bless($ref, $obj) used stringified form instead of ref($obj)** -- FIXED
When `bless($fh, $class)` was called with `$class` being an object (e.g. from
`$obj->new()`), PerlOnJava used the stringified `"Foo=HASH(0x...)"` as the package
name instead of `ref($obj)` = `"Foo"`. This broke `IO::Handle::new` when called on
objects (the `IO::Socket->accept` path: `$pkg->new(Timeout => $timeout)`).
**File**: `ReferenceOperators.java`

**sockaddr_in() only supported 2-arg (pack) mode** -- FIXED
In Perl, `sockaddr_in()` is dual-purpose: 2 args = pack, 1 arg = unpack.
PerlOnJava only had the pack form, causing "Not enough arguments" when
`IO::Socket::INET::sockport()` called `sockaddr_in($name)`.
**File**: `Socket.java`

**getnameinfo() return signature wrong** -- FIXED
Returned `($host, $service)` but Perl spec is `($err, $host, $service)`.
HTTP::Daemon's `url()` method was getting the hostname in `$err` position.
Also added NI_NUMERICHOST/NI_NUMERICSERV flag handling.
**File**: `Socket.java`

**SO_TYPE constant missing** -- FIXED
IO::Socket uses `SO_TYPE` to verify socket type. Added constant (value 4104 on macOS).
**Files**: `Socket.java`, `Socket.pm`

**fileno() returned undef for server sockets** -- FIXED
After `listen()` converts SocketChannel to ServerSocketChannel, `fileno()` was
only checking the (now-null) `socket` field. Now checks socketChannel,
serverSocketChannel, socket, and serverSocket in order.
**File**: `SocketIO.java`

### P8: talk-to-ourself JVM startup timeout -- FIXED

**Impact**: t/local/http.t, t/redirect.t, t/robot/ua-get.t, t/robot/ua.t (4 files)
**Root cause**: The `talk-to-ourself` script creates a server socket with `Timeout => 5`,
then forks a child process (`open($CLIENT, "$^X $0 --port $port |")`). The child is
another `jperl` process which needs JVM startup time.
**Resolution**: JVM startup is ~1.2s on this system, well within the 5-second timeout.
The actual blocker was that SocketIO had no `sysread()` implementation — HTTP::Daemon's
`get_request()` uses `sysread()` on the accepted socket, but `SocketIO` only had
`doRead()` (buffered read). The default `IOHandle.sysread()` returned an error masquerading
as EOF (returned 0 instead of undef), so `get_request()` silently failed with "Client closed".
**Fix**: Added `sysread()` and `syswrite()` methods to `SocketIO.java` that read/write
raw bytes via the socket's InputStream/OutputStream.

### P11: Socket connect() doesn't report errors properly -- FIXED

**Impact**: t/redirect.t (2 tests)
**Root cause**: When connecting to a non-routable address (234.198.51.100) with a timeout,
the test expects error message matching `/Can't connect/i`. PerlOnJava's connect failed
with "No output stream available" instead, because the socket's outputStream was never
initialized when connect() failed. The error propagation from `socket.connect()` was not
properly surfacing the IOException message.
**Resolution**: Fixed indirectly by strict utf8::decode in Phase 7b — the improved error
handling allowed the existing socket error messages to propagate correctly.
**Status**: All 4 tests pass.

### P12: HTML::Parser fireEvent() doesn't dispatch to subclass methods -- FIXED

**Impact**: t/base/ua.t (2 tests: Content-Style-Type, Content-Script-Type)
**Root cause**: Two bugs in `HTMLParser.java` `fireEvent()`:
1. Used `selfHash.createReference()` which creates an *unblessed* reference, so
   `blessedId()` returned 0 and method lookup always started at `HTML::Parser`
   instead of the subclass (e.g. `HTML::HeadParser`).
2. Checked only `RuntimeScalarType.STRING` (type 2) for method name callbacks, but
   handler names are stored as `BYTE_STRING` (type 3), so the method-name branch
   was never entered.
**Fix**:
1. Pass the original blessed `self` through `parse()` → `parseHtml()` → `fireEvent()`
2. Add `BYTE_STRING` to the type check in the method-name branch
**Files**: `HTMLParser.java`

### P13: File::Temp path doubling in tempfile() -- FIXED

**Impact**: t/local/download_to_fh.t (crash)
**Root cause**: When a template already contained a directory component (e.g.
`/var/.../T/myfile-XXXXXX`), `tempfile()` still prepended `tmpdir`, producing
doubled paths like `/var/.../T//var/.../T/myfile-XXXXXX`.
**Fix**: Only default `$dir` to `tmpdir` when `TMPDIR => 1` is explicit or no template
is provided. Only prepend `$dir` when template has no directory component (checked
via `File::Spec->splitpath`).
**Files**: `File/Temp.pm`

### P14: HTML title extraction loses non-ASCII characters -- FIXED

**Impact**: t/local/http.t (1 test: "get file: good title")
**Root cause**: Two issues in the UTF-8 handling pipeline:
1. `HTMLParser.java` `parse()` did not implement `utf8_mode` behavior. The XS parser
   decodes UTF-8 input bytes to characters when `utf8_mode(1)` is set, but the Java
   parser just passed through raw bytes.
2. `Utf8.java` `decode()` used `new String(bytes, UTF_8)` which silently replaces
   malformed UTF-8 with U+FFFD (replacement character). When HeadParser's `flush_text()`
   called `utf8::decode` on already-decoded character data, the byte 0xF8 (from ø=U+00F8)
   was not valid UTF-8, producing `�` instead of ø.
**Fix**:
1. When `utf8_mode` is set and input chunk is BYTE_STRING, decode UTF-8 bytes to
   characters before parsing (matches XS behavior)
2. Use strict `CharsetDecoder` with `CodingErrorAction.REPORT` so `utf8::decode`
   returns FALSE for invalid UTF-8 (matches Perl 5 behavior)
**Files**: `HTMLParser.java`, `Utf8.java`
**Commit**: `17b38eabd`

### Won't fix

| Issue | Test | Reason |
|-------|------|--------|
| Test::LeakTrace XS | t/leak/no_leak.t | XS module, cannot be supported |

## Dependency Status

### Auto-install behavior
CPAN.pm (`prerequisites_policy => "follow"`) **does** auto-resolve and install
dependencies for `jcpan -t`. The "Missing dependencies" warning from Makefile.PL
was a false positive caused by P1 (`exists(&Errno::EINVAL)` failing). After the
P1 fix, IO::Socket and Net::FTP load correctly. Net::HTTP was already installed
via a prior jcpan run.

### sync.pl changes already applied
- **IO::Socket::IP**: Added to `config.yaml` (core module since 5.20,
  at `perl5/cpan/IO-Socket-IP/`). Pure Perl, needs `Socket::getaddrinfo()`
  implemented in Java (done).

### Modules NOT needing sync.pl changes
- IO::Socket, Net::FTP: Already imported
- Net::HTTP, HTTP::Message, URI, etc.: CPAN modules, installed via jcpan
- Encode::Locale: CPAN module, installed via jcpan (works after P2 fix)
- HTTP::Daemon: CPAN module, installed via jcpan

## Progress Tracking

### Phase 1: Infrastructure fixes -- COMPLETED (2026-04-03)

- [x] Investigation complete
- [x] **P0**: Fix MakeMaker.pm to use TESTS parameter in generated Makefile
- [x] **P1**: Fix `exists(&constant_sub)` in ConstantFoldingVisitor.java
- [x] `make` passes
- [x] Tests go from 3 files / 10 tests → 22 files / 122 tests

### Phase 2: Core fixes -- COMPLETED (2026-04-03)

- [x] **P2**: Handle "locale" encoding in Encode.java
- [x] **P3**: Import IO::Socket::IP + implement getaddrinfo/sockaddr_family in Socket.java
- [x] **P4**: Fix File::Temp IO::Handle methods (close, seek, getline, printflush, etc.)
- [x] `make` passes
- [x] Re-run `./jcpan -j 8 -t LWP::UserAgent`: 141 tests, 137/141 pass (97.2%)

### Phase 3: Quick fixes (P5, P6) -- COMPLETED (2026-04-03)

- [x] **P5**: Fix utf8::downgrade read-only scalar crash in Utf8.java
- [x] **P6**: Fix openhandle() and open dup for blessed objects with *{} overloading
- [x] `make` passes
- [x] Commit: `06364af20`

### Phase 4: Socket overhaul (P7) + runtime fixes -- COMPLETED (2026-04-03)

- [x] **P7a**: Fix socket() to set IO slot of glob (like open() does)
- [x] **P7b**: Create SocketChannel for SOCK_STREAM, lazy ServerSocket on listen()
- [x] **P7c**: Fix listen() to use proper backlog (not setReceiveBufferSize)
- [x] **P7d**: Standardize sockaddr_in byte order (big-endian everywhere)
- [x] **P7e**: Implement accept() builtin properly
- [x] Fix `bless($ref, $obj)` to use `ref($obj)` as package name
- [x] Fix `sockaddr_in()` dual-purpose: 2 args=pack, 1 arg=unpack
- [x] Fix `getnameinfo()` return signature: `($err, $host, $service)`
- [x] Add `SO_TYPE` socket constant
- [x] Fix `fileno()` for server sockets after listen()
- [x] `make` passes
- [x] Verified: HTTP::Daemon creates and accepts connections correctly
- [x] Commit: `1f4d1b1e2`

### Phase 5: Implement select() for socket I/O -- COMPLETED (2026-04-03)

- [x] **P9a**: Fileno registry in RuntimeIO — sequential filenos starting at 3
- [x] **P9b**: Assign filenos in socket() and accept() builtins
- [x] **P9c**: Add `getSelectableChannel()` to SocketIO; NIO-based acceptConnection()
- [x] **P9d**: Implement 4-arg `select()` with Java NIO Selector
- [x] Fix: close Selector before restoring blocking mode (IllegalBlockingModeException)
- [x] Fix: sleep for timeout when no channels registered (defined-but-empty bit vectors)
- [x] **P10**: Fix all "uninitialized value" warnings to use `warnWithCategory("uninitialized")`
  instead of bare `WarnDie.warn()` — 5 files: StringOperators, Operator, CompareOperators,
  BitwiseOperators, RuntimeScalar. Now `no warnings 'uninitialized'` and `$SIG{__WARN__}`
  work correctly for all uninitialized warnings.
- [x] `make` passes
- [x] Verified: IO::Select with server/client sockets works (accept, read, write)
- [x] Commits: `002a63557`, `ad1aed7d9`

### Phase 6: Unblock daemon-based tests (P8) -- COMPLETED (2026-04-03)

- [x] Measured JVM startup time (~1.2s) — fits within talk-to-ourself's 5s timeout
- [x] **P8**: Root cause identified: missing `sysread()`/`syswrite()` on SocketIO
- [x] Added `sysread()` and `syswrite()` methods to `SocketIO.java`
- [x] Verified HTTP::Daemon `get_request()` works (select + sysread path)
- [x] Verified LWP::UserAgent -> HTTP::Daemon full round-trip
- [x] t/local/http.t: 134/136 (2 Unicode failures)
- [x] t/robot/ua-get.t: 18/18
- [x] t/robot/ua.t: 14/14
- [x] t/redirect.t: 2/4 (socket connect error message format — P11)
- [x] `make` passes (all unit tests green)
- [x] Full jcpan run: **307/313 subtests pass** (98.1%)
- [x] Commits: `03f680d2a`, `44f0d83ff`

### Phase 7a: HTML::Parser method dispatch + File::Temp fix -- COMPLETED (2026-04-03)

- [x] **P12**: Fix `fireEvent()` to pass original blessed self (not unblessed createReference)
- [x] **P12**: Fix `fireEvent()` to check BYTE_STRING type for method name callbacks
- [x] **P13**: Fix File::Temp `tempfile()` path doubling when template has directory component
- [x] t/base/ua.t: 51/51 (was 49/51)
- [x] t/local/download_to_fh.t: 5/5 (was crashing)
- [x] `make` passes
- [x] Commit: `7ccebede6`

### Phase 7b: UTF-8 encoding fixes -- COMPLETED (2026-04-03)

- [x] **P14**: Implement `utf8_mode` in HTMLParser.java parse() — decode UTF-8 bytes to characters
- [x] **P14**: Strict UTF-8 decoder in Utf8.java decode() — return FALSE for invalid sequences
- [x] t/local/http.t: 136/136 (was 135/136)
- [x] t/redirect.t: 4/4 (was 2/4)
- [x] `make` passes
- [x] Full test run: **314/316 subtests pass** (99.4%), 2 are TODO expected failures
- [x] Commit: `17b38eabd`

### Next Steps

- [ ] Create PR for merge to master
- [ ] Consider if download_to_fh.t TODO tests can be addressed (mirror with filehandles)

## Files Changed

### Phase 1
| File | Change |
|------|--------|
| `src/main/perl/lib/ExtUtils/MakeMaker.pm` | Use TESTS param in test target |
| `src/main/java/org/perlonjava/frontend/analysis/ConstantFoldingVisitor.java` | Skip constant folding under `&` sigil |

### Phase 2
| File | Change |
|------|--------|
| `src/main/java/org/perlonjava/runtime/perlmodule/Encode.java` | Handle "locale"/"locale_fs" encoding |
| `src/main/java/org/perlonjava/runtime/perlmodule/Socket.java` | Add getaddrinfo, sockaddr_family, 12 new constants |
| `src/main/perl/lib/Socket.pm` | Export new functions and constants |
| `dev/import-perl5/config.yaml` | Add IO::Socket::IP import |
| `src/main/perl/lib/IO/Socket/IP.pm` | Imported from perl5 core |
| `src/main/perl/lib/File/Temp.pm` | Add close, seek, read, binmode, getline, getlines, printflush methods |

### Phase 3
| File | Change |
|------|--------|
| `src/main/java/org/perlonjava/runtime/perlmodule/Utf8.java` | Skip set() on read-only scalars in downgrade() |
| `src/main/java/org/perlonjava/runtime/perlmodule/ScalarUtil.java` | Handle *{} overloading in openhandle() |
| `src/main/java/org/perlonjava/runtime/operators/IOOperator.java` | Handle *{} overloading in open dup mode |

### Phase 4
| File | Change |
|------|--------|
| `src/main/java/org/perlonjava/runtime/operators/IOOperator.java` | Rewrite socket(), accept() builtins; add SocketChannel import |
| `src/main/java/org/perlonjava/runtime/io/SocketIO.java` | New SocketChannel constructor; rewrite bind/connect/listen/accept; fix fileno |
| `src/main/java/org/perlonjava/runtime/perlmodule/Socket.java` | Fix byte order, sockaddr_in dual mode, getnameinfo signature, add SO_TYPE |
| `src/main/perl/lib/Socket.pm` | Export SO_TYPE |
| `src/main/java/org/perlonjava/runtime/operators/ReferenceOperators.java` | Fix bless($ref, $obj) to use ref($obj) |

### Phase 5
| File | Change |
|------|--------|
| `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeIO.java` | Add fileno registry (assignFileno, getByFileno); fileno() uses registry |
| `src/main/java/org/perlonjava/runtime/operators/IOOperator.java` | Implement 4-arg select() with NIO Selector; assign filenos in socket()/accept() |
| `src/main/java/org/perlonjava/runtime/io/SocketIO.java` | Add getSelectableChannel(); NIO-based acceptConnection() |

### Phase 6
| File | Change |
|------|--------|
| `src/main/java/org/perlonjava/runtime/io/SocketIO.java` | Add sysread() and syswrite() for raw socket I/O |

### Phase 7a
| File | Change |
|------|--------|
| `src/main/java/org/perlonjava/runtime/perlmodule/HTMLParser.java` | Fix fireEvent() blessed self dispatch + BYTE_STRING type check; pass self through parseHtml/parserEof |
| `src/main/perl/lib/File/Temp.pm` | Fix tempfile() path doubling when template has directory component |

### Phase 7b
| File | Change |
|------|--------|
| `src/main/java/org/perlonjava/runtime/perlmodule/HTMLParser.java` | Decode UTF-8 bytes in parse() when utf8_mode is set |
| `src/main/java/org/perlonjava/runtime/perlmodule/Utf8.java` | Strict CharsetDecoder in decode() — REPORT on malformed/unmappable instead of REPLACE |

# LWP::UserAgent Support for PerlOnJava

## Status: In Progress

**Branch**: `fix/lwp-useragent-support`
**Date started**: 2026-04-03

## Background

LWP::UserAgent (libwww-perl) is a top-20 CPAN module providing the standard HTTP
client library for Perl. It was previously blocked on HTTP::Message, which has since
been fixed. Running `./jcpan -j 8 -t LWP::UserAgent` now installs and partially
works, but several issues prevent full test coverage.

## Current State (after Phase 5)

Running all 22 test files (with the TESTS pattern from Makefile.PL):
- **15/22 test programs pass**, 3 skipped (network), 4 have issues
- Socket infrastructure now works: socket/bind/listen/accept/connect all functional
- HTTP::Daemon can create and accept connections
- 4-arg `select()` now works with NIO Selector — IO::Select fully operational
- `talk-to-ourself` check still fails due to JVM startup time (>5s timeout)

### Test Results Breakdown

| Test File | Result | Tests | Notes |
|-----------|--------|-------|-------|
| t/00-report-prereqs.t | PASS | 1/1 | |
| t/10-attrs.t | PASS | 9/9 | |
| t/base/default_content_type.t | PASS | 2/2 | |
| t/base/protocols.t | PASS | 1/1 | |
| t/base/protocols/nntp.t | SKIP | 0/0 | nntp.perl.org unstable |
| t/base/proxy.t | PASS | 8/8 | Fixed by P2 (locale encoding) |
| t/base/proxy_request.t | PASS | 16/16 | |
| t/base/simple.t | PASS | 3/3 | |
| t/base/ua.t | **FAIL** | 49/51 | 2 header tests (Content-Style-Type) |
| t/base/ua_handlers.t | PASS | 19/19 | |
| t/leak/no_leak.t | **FAIL** | 0/0 | Test::LeakTrace is XS-only (won't fix) |
| t/local/autoload-get.t | PASS | 3/3 | |
| t/local/autoload.t | PASS | 5/5 | |
| t/local/cookie_jar.t | PASS | 9/9 | |
| t/local/download_to_fh.t | **FAIL** | 1/2 | P6: openhandle + open dup (fixed, needs retest) |
| t/local/get.t | PASS | 4/4 | |
| t/local/http.t | SKIP | 0/0 | P8: talk-to-ourself JVM startup timeout |
| t/local/httpsub.t | PASS | 4/4 | |
| t/local/protosub.t | **PASS** | 7/7 | Fixed by P5 (utf8::downgrade) |
| t/redirect.t | SKIP | 0/0 | P8: talk-to-ourself JVM startup timeout |
| t/robot/ua-get.t | SKIP | 0/0 | P8: talk-to-ourself JVM startup timeout |
| t/robot/ua.t | SKIP | 0/0 | P8: talk-to-ourself JVM startup timeout |

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

### P8: talk-to-ourself JVM startup timeout -- OPEN

**Impact**: t/local/http.t, t/redirect.t, t/robot/ua-get.t, t/robot/ua.t (4 files)
**Root cause**: The `talk-to-ourself` script creates a server socket with `Timeout => 5`,
then forks a child process (`open($CLIENT, "$^X $0 --port $port |")`). The child is
another `jperl` process which needs JVM startup time (typically 3-8 seconds). By the
time the child JVM is ready to connect, the server's `accept()` has already timed out.
**Workaround options**:
1. Increase timeout in talk-to-ourself (requires patching the test — not ideal)
2. Set `PERL_LWP_ENV_HTTP_TEST_URL` to bypass talk-to-ourself and use a pre-started
   daemon — the test supports this: run the daemon separately, then run tests with the
   URL environment variable
3. Use a test wrapper that pre-launches the daemon with the `daemon` argument and
   passes the URL to the test process
**Status**: Not yet addressed. The socket infrastructure works correctly; this is purely
a JVM startup latency issue.

### Won't fix

| Issue | Test | Reason |
|-------|------|--------|
| Test::LeakTrace XS | t/leak/no_leak.t | XS module, cannot be supported |
| ua.t Content-Style-Type | t/base/ua.t (2 tests) | Requires HTML::HeadParser callback chain |

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
- [x] `make` passes
- [x] Verified: IO::Select with server/client sockets works (accept, read, write)

### Phase 6: Unblock daemon-based tests (P8) -- NEXT

The four skipped socket tests all fail at the `talk-to-ourself` check, which
forks a child `jperl` process with a 5-second timeout. The JVM startup time
exceeds this timeout. Options to investigate:

- [ ] **Option A**: Create a test wrapper that pre-starts the HTTP daemon in a
  background `jperl` process, waits for the greeting line, then runs the test
  with `PERL_LWP_ENV_HTTP_TEST_URL=<url>` (the tests already support this).
  This avoids the talk-to-ourself check entirely.
- [ ] **Option B**: Investigate if PerlOnJava's piped open (`open($fh, "$cmd |")`)
  can be made faster (e.g., reuse the running JVM via a lightweight launch mode).
- [ ] **Option C**: Patch `talk-to-ourself` to increase the timeout when running
  under PerlOnJava (check `$^O` or a custom env var).
- [ ] Re-run `./jcpan -j 8 -t LWP::UserAgent` and verify http.t/redirect.t run
- [ ] Retest download_to_fh.t (should now pass with P5+P6 fixes)

### Phase 7: Final cleanup -- FUTURE

- [ ] Investigate ua.t Content-Style-Type failures (2 tests)
- [ ] Update plan doc with final test counts
- [ ] Create PR for merge to master

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

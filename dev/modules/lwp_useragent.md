# LWP::UserAgent Support for PerlOnJava

## Status: In Progress

**Branch**: `fix/lwp-useragent-support`
**Date started**: 2026-04-03

## Background

LWP::UserAgent (libwww-perl) is a top-20 CPAN module providing the standard HTTP
client library for Perl. It was previously blocked on HTTP::Message, which has since
been fixed. Running `./jcpan -j 8 -t LWP::UserAgent` now installs and partially
works, but several issues prevent full test coverage.

## Current State (after Phase 2)

Running all 22 test files (with the TESTS pattern from Makefile.PL):
- **141 tests across 22 files**
- **137/141 subtests pass** (97.2%)
- **15/22 test programs pass**, 3 skipped (network), 4 have issues

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
| t/local/download_to_fh.t | **FAIL** | 1/2 | P6: openhandle + open dup for blessed objects |
| t/local/get.t | PASS | 4/4 | |
| t/local/http.t | SKIP | 0/0 | P7: socket() builtin bugs |
| t/local/httpsub.t | PASS | 4/4 | |
| t/local/protosub.t | **FAIL** | 6/7 | P5: utf8::downgrade on read-only scalars |
| t/redirect.t | SKIP | 0/0 | P7: socket() builtin bugs |
| t/robot/ua-get.t | SKIP | 0/0 | P7: socket() builtin bugs |
| t/robot/ua.t | SKIP | 0/0 | P7: socket() builtin bugs |

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

### P3: IO::Socket::IP missing -- FIXED (partial)

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

**Status**: IO::Socket::IP loads, but actual socket connections fail — see P7.

### P4: File::Temp missing IO::Handle methods -- FIXED

**Impact**: t/local/download_to_fh.t (1 file)
**Root cause**: PerlOnJava's `File::Temp` uses AUTOLOAD to delegate to `$self->{_fh}`,
but `_fh` is a raw filehandle that doesn't have `IO::Handle` methods like `printflush`.
In standard Perl, File::Temp ISA IO::Handle.
**Fix**: Added explicit `close`, `seek`, `read`, `binmode`, `getline`, `getlines`,
and `printflush` methods to File::Temp that delegate to `CORE::*` builtins on `_fh`.

### P5: utf8::downgrade crashes on read-only scalars (protosub.t)

**Impact**: t/local/protosub.t (1 test)
**Root cause**: NOT an `@_` aliasing issue. The actual bug is in `Utf8.java` line 216.
`collect_once` passes a string literal `"Howdy\n"` as `$_[3]`. The collect path calls
`$response->add_content($$content)` which calls `utf8::downgrade($$chunkref, 1)`.
The Java `downgrade()` method attempts to modify the scalar in-place via `scalar.set()`,
but the scalar is a `RuntimeScalarReadOnly` (string literal), which throws
"Modification of a read-only value attempted". Since `failOk=1`, the exception is
caught silently and `false` (empty string) is returned. The caller
`_utf8_downgrade` sees the falsy return and croaks, which is caught by `collect`'s
eval block, leaving the content empty.
**Fix**: Check `instanceof RuntimeScalarReadOnly` before `scalar.set()`. If read-only
but the string CAN be represented in ISO-8859-1, return true (downgrade is logically
successful, skip in-place mutation). Mirrors existing pattern in `upgrade()`.
**Files**: `src/main/java/org/perlonjava/runtime/perlmodule/Utf8.java`

### P6: openhandle() and open dup don't handle blessed objects with *{} overload

**Impact**: t/local/download_to_fh.t (1 test, getstore into File::Temp)
**Root cause**: Two bugs:
1. `Scalar::Util::openhandle()` in `ScalarUtil.java` only checks GLOB/GLOBREFERENCE
   types, but File::Temp objects are HASHREFERENCE with `*{}` overloading. Returns
   undef for File::Temp objects, causing `collect()` to die silently.
2. `open(my $fh, '>&=', $obj)` in `IOOperator.java` line 284 only checks
   `GLOB || GLOBREFERENCE`, fails for File::Temp objects.
**Fix**:
1. `ScalarUtil.java`: Add handling for blessed objects with `*{}` overloading —
   call `arg.globDeref()` and verify the resulting glob has an open IO handle.
2. `IOOperator.java`: In 3-arg open dup mode, try `getRuntimeIO()` before
   string-name fallback (already handles `*{}` overloading internally).
**Files**: `ScalarUtil.java`, `IOOperator.java`

### P7: socket() builtin has multiple bugs preventing all socket operations

**Impact**: t/local/http.t, t/redirect.t, t/robot/ua-get.t, t/robot/ua.t (4 files),
plus LWP::UserAgent real HTTP requests fail with "syswrite() on closed filehandle"
**Root cause**: Five sub-bugs in the socket implementation:

**P7a: socket() doesn't set the IO slot of the glob** (PRIMARY)
`IOOperator.socket()` does `socketHandle.set(socketIO)` which replaces the scalar
value with a raw RuntimeIO, destroying the glob. Should follow the `open()` pattern:
extract the glob and call `targetGlob.setIO(fh)`, or create a new anonymous glob.
**File**: `IOOperator.java` lines 1331-1378

**P7b: socket() always creates ServerSocket for SOCK_STREAM**
In POSIX, `socket()` creates a generic socket usable for either connect (client)
or bind+listen (server). The Java implementation always creates a ServerSocket,
which SocketIO.connect() rejects with "No socket available to connect".
**Fix**: Create a SocketChannel (client-capable) initially. Convert to
ServerSocketChannel lazily when listen() is called.
**Files**: `IOOperator.java`, `SocketIO.java`

**P7c: listen() implementation is wrong**
`SocketIO.listen()` calls `serverSocket.setReceiveBufferSize(backlog)` which sets
SO_RCVBUF, NOT the listen backlog. Java ServerSocket starts listening when
`bind(address, backlog)` is called.
**Fix**: Store backlog and apply during bind, or bind with backlog in listen().
**File**: `SocketIO.java`

**P7d: sockaddr_in byte order inconsistency**
`getaddrinfo()` and `sockaddr_family()` use little-endian for the family field,
but `pack_sockaddr_in()`, `packSockaddrIn()`, and `parseSockaddrIn()` use
big-endian. When getaddrinfo output is passed to parseSockaddrIn, family=2 becomes
512, failing the check.
**Fix**: Standardize to little-endian everywhere (matching Linux native Perl).
**Files**: `Socket.java`, `SocketIO.java`, `IOOperator.java`

**P7e: accept() builtin is incomplete**
`IOOperator.accept()` has a placeholder that always returns false even on success.
**Fix**: Create a new SocketIO from the accepted Socket, wrap in RuntimeIO,
associate with the new socket handle glob.
**File**: `IOOperator.java`

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

### Phase 3: Quick fixes (P5, P6) -- PENDING

- [ ] **P5**: Fix utf8::downgrade read-only scalar crash in Utf8.java
- [ ] **P6**: Fix openhandle() and open dup for blessed objects with *{} overloading
- [ ] `make` passes
- [ ] Re-run `./jcpan -j 8 -t LWP::UserAgent` and verify improvement

### Phase 4: Socket overhaul (P7) -- PENDING

- [ ] **P7a**: Fix socket() to set IO slot of glob (like open() does)
- [ ] **P7b**: Create SocketChannel for SOCK_STREAM, lazy ServerSocket on listen()
- [ ] **P7c**: Fix listen() to use proper backlog (not setReceiveBufferSize)
- [ ] **P7d**: Standardize sockaddr_in byte order (little-endian everywhere)
- [ ] **P7e**: Implement accept() builtin properly
- [ ] Add SocketIO(SocketChannel, ProtocolFamily) constructor
- [ ] `make` passes
- [ ] Verify: `./jperl -e 'use IO::Socket::INET; ...'` connects successfully
- [ ] Re-run `./jcpan -j 8 -t LWP::UserAgent` and verify skipped tests now run

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

### Phase 3 (planned)
| File | Change |
|------|--------|
| `src/main/java/org/perlonjava/runtime/perlmodule/Utf8.java` | Skip set() on read-only scalars in downgrade() |
| `src/main/java/org/perlonjava/runtime/perlmodule/ScalarUtil.java` | Handle *{} overloading in openhandle() |
| `src/main/java/org/perlonjava/runtime/operators/IOOperator.java` | Handle *{} overloading in open dup mode |

### Phase 4 (planned)
| File | Change |
|------|--------|
| `src/main/java/org/perlonjava/runtime/operators/IOOperator.java` | Fix socket(), accept() builtins |
| `src/main/java/org/perlonjava/runtime/io/SocketIO.java` | Add SocketChannel constructor, fix listen(), add lazy ServerSocket |
| `src/main/java/org/perlonjava/runtime/perlmodule/Socket.java` | Standardize sockaddr_in byte order |
| `src/main/java/org/perlonjava/runtime/io/SocketIO.java` | Fix packSockaddrIn byte order |

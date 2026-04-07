# WWW::Mechanize Support for PerlOnJava

## Status: Phase 13 Complete — TAP harness fix + scopeExitCleanup fix + Clone fallback

**Branch**: `feature/www-mechanize-tap-fix`
**Date started**: 2026-04-04

## Background

WWW::Mechanize is a widely-used CPAN module for programmatic web browsing and
form handling. It depends on LWP::UserAgent (already supported at 100%), plus
HTML::Form, HTML::TreeBuilder (HTML-Tree), and Tie::RefHash. Running
`./jcpan -t WWW::Mechanize` reveals several PerlOnJava bugs that block the
module from loading and passing its dependency tests.

## Current State (before fixes)

### Dependency Test Summary

| Module | Test Results | Blocker |
|--------|-------------|---------|
| **WWW::Mechanize** | Most tests FAIL | `Bad name after Tie::RefHash::` prevents loading |
| **HTML::Form** | 10/12 test files FAIL, 6/13 subtests fail | `HTML::Form->parse()` returns undef (HTMLParser bug) |
| **HTML::Tree** | 17/23 test files FAIL, 169/910 subtests fail | `UNIVERSAL::isa(\&code, 'CODE')` returns false |
| **Capture::Tiny** | 109/331 subtests fail | `fork()` not supported on JVM |
| **Test::Memory::Cycle** | 7/7 test files FAIL | `Devel::Cycle` not installed |

### Root Cause Analysis

Six distinct PerlOnJava bugs were identified:

#### Bug 1: Parser rejects NEWLINE after trailing `::` (CRITICAL)
- **File**: `IdentifierParser.java:581`
- **Symptom**: `Bad name after Tie::RefHash::::` when parsing `tie my %c, Tie::RefHash::\n    or ...`
- **Root cause**: The validation gate after `::` doesn't allow NEWLINE or WHITESPACE tokens, but the top-of-loop check (line 543) already handles them correctly. The validation blocks the `continue` that would reach it.
- **Impact**: WWW::Mechanize cannot load at all. Every test fails.

#### Bug 2: UNIVERSAL::isa() missing CODE reference type (CRITICAL)
- **File**: `Universal.java:240-256`
- **Symptom**: `UNIVERSAL::isa(\&sub, 'CODE')` returns false
- **Root cause**: The `isa` method's switch statement handles REFERENCE, ARRAYREFERENCE, HASHREFERENCE, GLOBREFERENCE, FORMAT, REGEX — but not CODE. CODE refs fall through to the default case which stringifies them and looks up as a class name.
- **Impact**: HTML::Element::traverse() never invokes callbacks, so `as_HTML()` and `as_XML()` return empty strings. All HTML::Tree rendering tests fail (75/75 in assubs.t, 10/11 in tag-rendering.t). This is a generic bug affecting any CPAN module using `UNIVERSAL::isa($ref, 'CODE')`.

#### Bug 3: HTMLParser array-ref accumulator handlers missing (HIGH)
- **File**: `HTMLParser.java:fireEvent()` line ~466
- **Symptom**: `HTML::Form->parse(...)` returns undef/empty list
- **Root cause**: `fireEvent()` handles STRING callbacks (method names) and CODE callbacks, but not ARRAYREFERENCE callbacks. HTML::PullParser/HTML::TokeParser registers an array ref as the event handler to accumulate parsed tokens. Since the array-ref branch is missing, parsed events are silently dropped and the accumulator stays empty.
- **Also needed**: Argspec parsing — the argspec string (e.g., `"'S',tagname,attr,attrseq,text"`) is fetched but completely ignored. Need to parse it and construct proper event data arrays.
- **Impact**: HTML::Form::parse() returns no forms. Most HTML::Form tests fail.

#### Bug 4: Overload dispatch doesn't set $AUTOLOAD (MEDIUM)
- **File**: `OverloadContext.java:resolveOverloadMethodName()` line ~395
- **Symptom**: `substr outside of string` at URI/WithBase.pm line 51; `Can't locate object method "" via package "URI::http"`
- **Root cause**: When overload dispatch resolves a string method name (e.g., `as_string` for `""` overload), and `findMethodInHierarchy` returns AUTOLOAD instead of the actual method, `tryOverload` calls AUTOLOAD without setting the `$AUTOLOAD` variable. Normal method dispatch properly sets `$AUTOLOAD` but overload dispatch does not.
- **Impact**: URI::WithBase stringification fails. Affects link.t, image-new.t, link-base.t in WWW::Mechanize tests.

#### Bug 5: Devel::Cycle not available (LOW)
- **Symptom**: Test::Memory::Cycle fails because `use Devel::Cycle` fails
- **Root cause**: Devel::Cycle is not installed. Even if installed, it depends on `Scalar::Util::isweak()` which is a no-op in PerlOnJava (JVM handles cycles natively).
- **Impact**: Test::Memory::Cycle tests all fail. This is a build_requires dependency, not a runtime dependency.

#### Bug 6: Capture::Tiny fork dependency (LOW, KNOWN)
- **Symptom**: Capture::Tiny tee functions fail; basic capture functions have issues
- **Root cause**: `fork()` returns undef on JVM. Already known limitation.
- **Impact**: Test::Output tests fail (build_requires for WWW::Mechanize). Not a runtime blocker.

## Implementation Plan

### Phase 1: Parser and UNIVERSAL::isa fixes (Quick wins)

**Files to modify:**
1. `src/main/java/org/perlonjava/frontend/parser/IdentifierParser.java` — Add NEWLINE, WHITESPACE, and `,` to allowed tokens after `::` in the validation gate at line 581
2. `src/main/java/org/perlonjava/runtime/perlmodule/Universal.java` — Add CODE case to the isa() switch statement and boolean check

**Expected impact:** WWW::Mechanize can load. HTML::Tree rendering tests pass (~75+ subtests fixed).

### Phase 2: HTMLParser array-ref accumulator + argspec parsing

**Files to modify:**
1. `src/main/java/org/perlonjava/runtime/perlmodule/HTMLParser.java`
   - Add ARRAYREFERENCE branch in `fireEvent()`
   - Implement `buildEventDataFromArgspec()` method to parse argspec strings
   - Handle argspec tokens: literal strings (`'S'`, `'E'`, `'T'`), `tagname`, `attr`, `attrseq`, `text`, `is_cdata`, `self`, `token0`, `offset`, `length`, `event`, `line`, `column`, `tag`
   - For ARRAYREFERENCE callbacks: build event data array per argspec, push as array ref onto accumulator
   - Also apply argspec for existing CODE and STRING callback types (currently ignored)

**Expected impact:** HTML::Form::parse() returns forms. Most HTML::Form tests pass.

### Phase 3: Overload AUTOLOAD dispatch fix

**Files to modify:**
1. `src/main/java/org/perlonjava/runtime/runtimetypes/OverloadContext.java`
   - In `resolveOverloadMethodName()`: after `findMethodInHierarchy` returns, check if the result has `autoloadVariableName` set. If so, set the `$AUTOLOAD` global variable before returning.

**Expected impact:** URI::WithBase stringification works. link.t, image-new.t, link-base.t tests improve.

### Phase 4: Devel::Cycle stub

**Files to create:**
1. `src/main/perl/lib/Devel/Cycle.pm` — No-op stub (same pattern as Test::LeakTrace)
   - Export `find_cycle` and `find_weakened_cycle`
   - Both functions are no-ops (never call callback = no cycles found)
   - On JVM, tracing GC handles cycles natively

**Expected impact:** Test::Memory::Cycle loads and reports no cycles.

### Phase 5: Verification and cleanup

1. Run `make` — ensure no regressions in unit tests
2. Re-run `./jcpan -t WWW::Mechanize` — measure improvement
3. Update this document with final test counts

## Test Commands

```bash
# Full build + unit tests
make

# Test WWW::Mechanize and all dependencies
./jcpan -t WWW::Mechanize

# Quick smoke test for parser fix
./jperl -e 'tie my %c, Tie::RefHash:: or print "ok\n"'

# Quick smoke test for UNIVERSAL::isa fix
./jperl -e 'print UNIVERSAL::isa(sub{}, "CODE") ? "ok" : "FAIL", "\n"'

# Quick smoke test for HTMLParser fix
./jperl -e 'use HTML::TokeParser; my $p = HTML::TokeParser->new(\"<b>test</b>"); my $t = $p->get_tag("b"); print defined $t ? "ok" : "FAIL", "\n"'
```

## Progress Tracking

### Current Status: Phase 13 complete — 877/877 subtests via harness (61/63 test files, 100% pass rate)

### Completed Phases
- [x] Phase 1: Parser and UNIVERSAL::isa fixes (2026-04-04)
  - Fixed IdentifierParser.java: NEWLINE/WHITESPACE/comma after `::`
  - Fixed Universal.java: Added CODE case to isa()
- [x] Phase 2: HTMLParser array-ref accumulator + argspec (2026-04-04)
  - Added ARRAYREFERENCE handler branch in fireEvent()
  - Implemented buildEventDataFromArgspec() for argspec token parsing
  - Added argspec processing for STRING and CODE callback types
- [x] Phase 3: Overload AUTOLOAD dispatch fix (2026-04-04)
  - Fixed OverloadContext.java to set $AUTOLOAD for overload dispatch
- [x] Phase 4: Devel::Cycle stub (2026-04-04)
  - Created src/main/perl/lib/Devel/Cycle.pm no-op stub
- [x] Phase 5: Verification (2026-04-04)
  - Fixed HTMLParser skipSelf bug for method callbacks
  - `make` passes (all unit tests green)
  - WWW::Mechanize non-local tests: 431/478 pass (90.2%)
  - Local tests: 14/139 pass (need fork() for HTTP::Daemon)
- [x] Phase 6: Chunked parsing, strict subs, self-closing tags (2026-04-05)
  - Fixed HTMLParser to buffer incomplete tags across parse() chunk boundaries
  - Fixed strict subs to allow trailing `::` barewords (package name constants)
  - Fixed self-closing `/>` to emit `'/' => '/'` attribute in non-XML mode
  - `make` passes (all unit tests green)
  - WWW::Mechanize non-local tests: 513/529 pass (97.0%)
- [x] Phase 7: Labeled blocks, script/style raw text, media.types (2026-04-05)
  - Fixed EmitStatement.java: labeled blocks are valid targets for unlabeled last/next/redo
  - Added HTMLParser raw text handling for script/style/xmp/listing/plaintext/textarea/title
  - Bundled LWP/media.types data file for MIME type lookups
  - `make` passes (all unit tests green)
  - WWW::Mechanize non-local tests: 522/532 pass (98.1%)
- [x] Phase 8: Capture::Tiny fileno/dup chain fix (2026-04-05)
  - Fixed fileno() to return valid fd numbers for all file/pipe handles
  - Bridged findFileHandleByDescriptor() to RuntimeIO fileno registry
  - Added guard for empty fd in dup mode (prevents STDOUT corruption)
  - Added fileno unregistration on close() to prevent fd leaks
  - Capture::Tiny capture/capture_stdout/capture_stderr/capture_merged: WORKING
  - `make` passes (all unit tests green)
  - WWW::Mechanize non-local tests: 529/532 pass (99.4%)
  - dump.t: 1/7 -> 7/7, mech-dump/file_not_found.t: 0/1 -> 1/1
- [x] Phase 9: HTTP::Daemon pure Perl verified (2026-04-05)
  - HTTP::Daemon new/accept/get_request/send_response: all working (pure Perl, no changes needed)
  - LocalServer piped-open pattern works with jperl
  - WWW::Mechanize t/local/ tests: 17/19 pass (0 failures, 2 server-exit timeouts)
- [x] Phase 10: closeIOOnDrop for gensym'd socket globs (2026-04-05)
  - Added `closeIOOnDrop()` to RuntimeScalar.java: closes IO on `undef()` and `set()` for GLOBREFERENCE
  - Fixed RuntimeIO.java fallback to use `getExistingGlobalIO()` (non-auto-vivifying) instead of `getGlobalIO()`
  - Added `getExistingGlobalIO()` to GlobalVariable.java
  - Root cause: `getRuntimeIO()` fallback re-created stash entries that gensym had deleted, preventing socket close
  - All 18 local server tests pass with no timeouts (including redirect tests)
  - back.t: 47/47, get.t: 34/34 (both previously had server-exit timeouts)
- [x] Phase 11: CDATA marked_sections + media.types MIME fix (2026-04-05)
  - Added SGML marked_sections support to HTMLParser: CDATA, IGNORE, INCLUDE
  - Script raw content handler skips CDATA when looking for closing tag (marked_sections only)
  - Added LWP/media.types to JAR resources (build.gradle + pom.xml)
  - find_link_xhtml.t: 7/10 -> 10/10 (CDATA + legacy mode)
  - image-parse.t: 41/47 -> 47/47 (CSS MIME type detection)
  - `make` passes (all unit tests green)
- [x] Phase 12: TAP::Parser::Iterator::Process fix for no-fork platforms (2026-04-06)
  - Fixed `_next()`: guarded `$fh == $err` with `ref $err` to avoid comparing filehandle against empty string
  - Fixed `_finish()`: guarded `$err->close` with `ref $err` so cleanup doesn't call method on non-object
  - Root cause: non-open3 path (no fork) sets `$err = ''` but still creates IO::Select for `$out`; the `_next` and `_finish` methods assumed `$err` is always a real filehandle when `$sel` exists
  - Eliminates: `Argument "" isn't numeric in numeric eq (==)` warning during `make test`
  - Eliminates: `Can't call method "close" on an undefined value` error during test cleanup
  - `make` passes (all unit tests green)

### Bug 17: TAP harness warning on no-fork platforms (FIXED)
- **File**: `TAP/Parser/Iterator/Process.pm` — `_next()` and `_finish()` methods
- **Symptom**: `Argument "" isn't numeric in numeric eq (==)` warning during `make test`,
  followed by `Can't call method "close" on an undefined value` at cleanup
- **Root cause**: On no-fork platforms (PerlOnJava), `_use_open3` returns false and the
  fallback path sets `$err = ''` (empty string). A later patch added `IO::Select` for the
  `$out` pipe, but `_next()` still compared `$fh == $err` (filehandle vs empty string)
  and `_finish()` still called `$err->close` assuming `$err` was a filehandle whenever
  `$sel` was set.
- **Fix**: Guarded both comparisons with `ref $err` so they only execute when `$err` is
  an actual IO::Handle object.

### Bug 7: HTMLParser argspec "self" doubled for method callbacks (FIXED)
- **File**: `HTMLParser.java:fireEvent()` + `buildEventDataFromArgspec()`
- **Symptom**: `Not an ARRAY reference` when HTML::TreeBuilder parses HTML
- **Root cause**: For STRING (method name) callbacks, "self" in argspec was both
  pushed as the invocant AND included in the argspec args. The XS behavior is
  to use "self" only as the invocant for method dispatch, not as an argument.
  This doubled $self, shifting all other args by 1 position.
- **Fix**: Added `skipSelf` parameter to `buildEventDataFromArgspec()`.
  STRING callbacks use skipSelf=true; CODE/ARRAY use skipSelf=false.

### Bug 8: HTMLParser incomplete tag buffering across chunks (FIXED)
- **File**: `HTMLParser.java:parseHtml()`
- **Symptom**: Tags split at 512-byte chunk boundary get truncated (e.g., URL
  `http://www.bbc.co.uk/` becomes `http://www.bbc`)
- **Root cause**: `parseHtml()` buffered incomplete comments/declarations/PIs but
  NOT incomplete start or end tags. When `HTML::PullParser` feeds 512-byte chunks,
  any tag straddling a boundary was parsed with truncated content.
- **Fix**: Added incomplete tag detection after attribute parsing. If `>` not found
  before end of input, buffer from `tagStart` for next `parse()` call. Also handles
  bare `<` at end of input and incomplete end tags.

### Bug 9: Strict subs rejects trailing `::` barewords (FIXED)
- **Files**: `EmitLiteral.java:emitIdentifier()` + `BytecodeCompiler.java`
- **Symptom**: `Bareword "Tie::RefHash::" not allowed while "strict subs" in use`
- **Root cause**: The strict subs check had zero exemptions for any bareword pattern.
  In Perl, barewords ending with `::` are package name constants, always allowed.
- **Fix**: Added early return for names ending with `::` that strips the trailing `::` and
  emits as a string literal. Applied in both JVM and bytecode backends.

### Bug 10: Self-closing `/>` emits synthetic end tag in non-XML mode (FIXED)
- **File**: `HTMLParser.java:parseHtml()`
- **Symptom**: `get_phrase()` stops early at synthetic `</input>` end tag; `<area/>`
  missing `'/' => '/'` attribute expected by tests
- **Root cause**: In non-XML mode, `/>` was treated as self-closing with a synthetic
  end event. Perl's HTML::Parser treats `/` as a boolean attribute `'/' => '/'` and
  does NOT emit an end event. Synthetic end tags only happen in `xml_mode`.
- **Fix**: In non-XML mode, add `'/'` to attrs/attrseq; only emit synthetic end tag
  when `xml_mode` is true.

### Phase 7: Remaining failures — RESOLVED (2026-04-05)

**Non-local test results: 541/532+13 pass (99.8%)**

| Test File | Result | Root Cause | Status |
|-----------|--------|------------|--------|
| dump.t | 7/7 | Fixed by Capture::Tiny fileno/dup fix | ✅ FIXED |
| field.t | 15/16 (TODO fail) | Expected: HTML::TokeParser limitation | OK (TODO test) |
| find_link_xhtml.t | 10/10 | SGML marked_sections / CDATA support added | ✅ FIXED (Phase 11) |
| image-parse.t | 47/47 | media.types added to JAR resources | ✅ FIXED (Phase 11) |
| mech-dump/file_not_found.t | 1/1 | Fixed by Capture::Tiny fileno/dup fix | ✅ FIXED |

**Local server test results: 18/18 pass (0 failures, 0 timeouts)**

| Test File | Result | Notes |
|-----------|--------|-------|
| t/local/back.t | 47/47 | ✅ |
| t/local/get.t | 34/34 | ✅ (including redirect test) |
| t/local/click.t | 9/9 | ✅ |
| t/local/click_button.t | 15/15 | ✅ |
| t/local/content.t | 3/3 | ✅ |
| t/local/encoding.t | 6/6 | ✅ |
| t/local/failure.t | 15/15 | ✅ |
| t/local/follow.t | 32/32 | ✅ |
| t/local/form.t | 38/38 | ✅ |
| t/local/head.t | 8/8 | ✅ |
| t/local/nonascii.t | 5/5 | ✅ |
| t/local/overload.t | SKIP | Test skips itself |
| t/local/page_stack.t | 26/26 | ✅ |
| t/local/post.t | 5/5 | ✅ |
| t/local/referer.t | 14/14 | ✅ |
| t/local/reload.t | 15/15 | ✅ |
| t/local/select_multiple.t | 19/19 | ✅ |
| t/local/submit.t | 13/13 | ✅ |
| t/history.t | 27/27 | ✅ |
| cookies.t | TIMEOUT | Needs TestServer fork-open pattern |

### Remaining Issues

1. **cookies.t** — Uses `TestServer.pm` which requires `open FH, '-|'` fork-open pattern
   (no exec). This is a true fork dependency that can't be worked around.

2. ~~**XHTML marked_sections** — find_link_xhtml.t (2 failures).~~ ✅ FIXED in Phase 11.

3. ~~**CSS background-url extraction** — image-parse.t (1 failure).~~ ✅ FIXED in Phase 11.

4. **`getGlobHash()` auto-vivification** — `${*$gensym}{"key"}` still auto-vivifies stash
   entries via `GlobalVariable.getGlobalHash()` because the gensym'd glob's `hashSlot` is
   null (RuntimeStashEntry.createReference() stores the original stash entry, not a detached
   copy). This doesn't affect IO closing but is a correctness issue. Low priority.

---

## Phase 8: Capture::Tiny capture* Implementation — COMPLETE

### Problem

Capture::Tiny's `capture`, `capture_stdout`, `capture_stderr`, `capture_merged` functions
don't need fork — they work by dup'ing STDOUT/STDERR to temp files, running user code,
then restoring. But they fail because `fileno()` returns undef for regular file handles.

### Capture::Tiny capture* flow (non-fork path)

```
_capture_tee($do_stdout, $do_stderr, $do_merge, $do_tee=0, $code):
  1. _copy_std()     → open $save, ">&STDOUT"        (dup by name — WORKS)
  2. File::Temp->new → $stash->{new}{stdout} = tmpfile
  3. _open_std()     → open *STDOUT, ">&" . fileno($tmpfile)  ← FAILS (fileno=undef)
  4. $code->()       → user code prints to redirected STDOUT
  5. _open_std()     → open *STDOUT, ">&" . fileno($save)     ← FAILS (fileno=undef)
  6. _slurp()        → seek + readline on tmpfile
```

### Root cause: 4 bugs in fileno/dup chain

#### Bug 13: `CustomFileChannel.fileno()` returns undef
- **File**: `CustomFileChannel.java:367-368`
- **Impact**: `fileno($tmpfile)` returns undef for ALL regular file handles
- **Fix**: Remove the hardcoded undef return. `RuntimeIO.fileno()` already checks
  `getAssignedFileno()` first (which returns the registered fd), so if we assign filenos
  at file open time, `CustomFileChannel.fileno()` is never reached. But as a safety net,
  delegate to the RuntimeIO registry.

#### Bug 14: Regular file handles never get assigned filenos
- **File**: `RuntimeIO.java` — `open()` methods that create `CustomFileChannel`
- **Impact**: Even though `assignFileno()` exists, it's only called for sockets
- **Fix**: Call `this.assignFileno()` after creating any new IOHandle (CustomFileChannel,
  PipeOutputChannel, PipeInputChannel). This populates the `filenoToIO`/`ioToFileno`
  registries so `RuntimeIO.fileno()` returns a valid fd.

#### Bug 15: `findFileHandleByDescriptor()` doesn't check RuntimeIO's fileno registry
- **File**: `IOOperator.java:2473-2491`
- **Impact**: Even if fileno returns a valid number, `open *STDOUT, ">&3"` can't find the
  handle because `findFileHandleByDescriptor()` only checks its own `fileDescriptorMap`
  (which is never populated) and the hardcoded stdin/stdout/stderr cases.
- **Fix**: In the `default` case, also check `RuntimeIO.getByFileno(fd)`.

#### Bug 16: Empty fd in dup mode silently corrupts STDOUT
- **File**: `RuntimeIO.java:449-458`
- **Impact**: When fileno returns undef, `">&" . undef` becomes `">&"`. The 2-arg open
  parses this as mode=">&" with empty filename, which falls through to a code path that
  replaces the handle with `new StandardIO(System.in)` — STDOUT becomes a stdin reader!
- **Fix**: In the `-`/empty filename branch, check if mode contains `&` and return an error
  instead of silently corrupting the handle.

### Implementation steps

1. In `RuntimeIO.java`, after every `new CustomFileChannel(...)`, call `this.assignFileno()`
2. In `RuntimeIO.java`, after `openPipe()` creates pipe handles, call `assignFileno()`
3. In `IOOperator.findFileHandleByDescriptor()`, add `RuntimeIO.getByFileno(fd)` fallback
4. In `RuntimeIO.open(String)`, add guard for empty filename with dup mode
5. Also call `assignFileno()` in `duplicateFileHandle()` so dup'd handles get their own fd
6. In `RuntimeIO.unassignFileno()` (or close), unregister the fd to prevent leaks

### Test plan

```bash
# Quick validation
./jperl -e 'use Capture::Tiny qw(capture); my ($o,$e) = capture { print "hello\n"; warn "err\n" }; print "out=[$o] err=[$e]\n"'

# WWW::Mechanize dump.t
cd ~/.cpan/build/WWW-Mechanize-2.20-0 && ../../projects/PerlOnJava2/jperl t/dump.t

# Broader Capture::Tiny tests
./jcpan -t Capture::Tiny
```

---

## Phase 9: HTTP::Daemon Pure Perl Testing — COMPLETE

### Problem

The WWW::Mechanize `t/local/*.t` tests (18 files) and `t/cookies.t` need a local HTTP server.
HTTP::Daemon is pure Perl on `IO::Socket::IP` and does NOT use fork itself.

### Analysis: What HTTP::Daemon needs vs PerlOnJava support

| Feature | Status | Notes |
|---------|--------|-------|
| `IO::Socket::IP->new(Listen=>N)` | ✅ Works | socket/bind/listen chain |
| `accept($new, $sock)` builtin | ✅ Works | `IOOperator.java:1838` |
| `${*$self}{'key'}` glob hash stash | ✅ Works | `RuntimeGlob.getGlobHash()` |
| `vec($fdset, fileno, 1) = 1` | ✅ Works | lvalue vec + small sequential filenos |
| 4-arg `select()` | ✅ Works | `IOOperator.selectWithNIO()` |
| `sysread($fh, $buf, N, offset)` | ✅ Works | 4-arg sysread with offset |
| `print $fh "data"` | ✅ Works | write to socket glob |
| `fileno($sock)` | ✅ Works | small sequential integers from registry |
| `getsockopt(SO_TYPE)` | ⚠️ Returns 0 | Not mapped in `SocketIO.mapToJavaSocketOption()` |
| `getaddrinfo(undef, "0", ...)` | ⚠️ Needs test | Ephemeral port binding |

### Test harness patterns

| Harness | Pattern | PerlOnJava Support |
|---------|---------|-------------------|
| `LocalServer.pm` (18 tests) | `open $fh, qq'$^X "log-server" ...\|'` | ✅ Piped open works |
| `TestServer.pm` (cookies.t) | `open $fh, '-\|'` (fork-open, no exec) | ❌ Needs true fork |

### Implementation steps

1. Test `HTTP::Daemon->new` — does it create a listening socket?
2. Test `$d->accept` — does it return a blessed `HTTP::Daemon::ClientConn`?
3. Test glob stash `${*$sock}{'httpd_daemon'}` on the accepted connection
4. Test full `get_request()` + `send_response()` cycle
5. Test `LocalServer::spawn()` end-to-end
6. If `getsockopt(SO_TYPE)` is needed: store socket type during creation, return from
   `getSocketOption(SOL_SOCKET, SO_TYPE)` — ~10 lines in `SocketIO.java`

### Test plan

```bash
# Step 1: Basic HTTP::Daemon
./jperl -e 'use HTTP::Daemon; my $d = HTTP::Daemon->new or die $!; print $d->url, "\n"; print "OK\n"'

# Step 2: Accept + serve (manual test with curl)
./jperl -e '
  use HTTP::Daemon;
  use HTTP::Response;
  my $d = HTTP::Daemon->new or die;
  print STDERR "Listening at: ", $d->url, "\n";
  my $c = $d->accept or die;
  my $r = $c->get_request or die;
  $c->send_response(HTTP::Response->new(200, "OK", undef, "Hello World\n"));
  $c->close;
'
# Then in another terminal: curl http://localhost:<port>/

# Step 3: LocalServer spawn (the real test)
cd ~/.cpan/build/WWW-Mechanize-2.20-0 && ../../projects/PerlOnJava2/jperl t/local/get.t
```

### Bug 11: HTMLParser script/style raw text handling (FIXED)
- **File**: `HTMLParser.java:parseHtml()`
- **Symptom**: Tags inside `<script>` and `<style>` elements parsed as HTML,
  causing false link/image extraction from JavaScript strings
- **Root cause**: Content inside raw text elements (script, style, xmp, listing,
  plaintext, textarea, title) was being parsed for HTML tags instead of treated
  as raw text until the matching close tag.
- **Fix**: After emitting a start tag event for raw text elements, scan ahead for
  the case-insensitive closing tag and emit the content as a single text event.

### Bug 12: Unlabeled last/next/redo in labeled blocks (FIXED)
- **File**: `EmitStatement.java`
- **Symptom**: `LABEL: { for (1..1) {} last; }` causes program exit instead of
  exiting the labeled block
- **Root cause**: `isUnlabeledTarget` was set to `false` for labeled simple blocks
  (`isSimpleBlock=true` AND `labelName != null`). This prevented unlabeled `last`
  from finding the block as a target, causing non-local ARETURN (program exit).
- **Fix**: Set `isUnlabeledTarget = true` unconditionally. All blocks (labeled or
  not) are valid targets for unlabeled last/next/redo per Perl semantics.
- **Note**: Bytecode compiler did NOT have this bug (only checks `isTrueLoop`).

### Next Steps

#### Merge-ready
- **PR #440** — All Phase 1–11 fixes are complete and tested. Ready for review and merge.

#### Active — Phase 13: fileno for open3 handles + TAP open3 path

1. **`fileno()` returns empty for open3 handles** — `IPC::Open3` works in PerlOnJava
   (uses Java ProcessBuilder), but `fileno()` on the returned IO::Handle objects returns
   empty instead of a valid file descriptor number. This prevents `IO::Select` from
   registering the handles. Root cause: the IO::Handle objects created by open3 wrap Java
   InputStreams/OutputStreams that are not registered in the RuntimeIO fileno registry.
   Fix: ensure handles created through the open3/pipe path get proper fileno values so
   IO::Select works. Once fixed, `TAP::Parser::Iterator::Process::_use_open3` can be
   updated to also check for working open3 (not just `$Config{d_fork}`), enabling proper
   stdout+stderr separation in the TAP harness.

2. **Enable open3 path in TAP harness** — After fileno is fixed, update `_use_open3()` in
   `TAP/Parser/Iterator/Process.pm` to return true when open3 is available even without
   `d_fork`. This gives proper stderr separation and removes the need for the Phase 12
   `ref $err` guards (though they remain harmless as defense-in-depth).

#### Post-merge follow-up (lower priority)

1. **cookies.t** — Blocked on JVM `fork()` limitation. The test uses `TestServer.pm` which
   requires `open FH, '-|'` (fork-open without exec). This is a known JVM limitation shared
   with many other fork-dependent tests. No workaround available.

2. **field.t TODO test** — 1 subtest out of 16 is a known HTML::TokeParser limitation
   (marked as TODO in the test itself). Not a PerlOnJava bug.

3. **`getGlobHash()` auto-vivification** — `${*$gensym}{"key"}` auto-vivifies stash entries
   via `GlobalVariable.getGlobalHash()` because the gensym'd glob's `hashSlot` is null.
   `RuntimeStashEntry.createReference()` stores the original stash entry rather than a
   detached copy. This is a correctness issue but does not affect IO closing or any
   WWW::Mechanize functionality. Fix would require changes to `RuntimeGlob.getGlobHash()`
   to lazily initialize `hashSlot` instead of falling through to the global stash.

4. **LWP::MediaTypes test suite** — Was at 41/47 before the `media.types` JAR fix. Re-run
   `jcpan -t LWP::MediaTypes` to check whether the bundling fix improves coverage.

5. **Broader CPAN impact** — The HTMLParser improvements (marked_sections, CDATA, script
   CDATA-skipping, chunked parsing, raw text elements) and the Capture::Tiny/IO fixes may
   benefit other HTML/web CPAN modules. Consider re-running tests for HTML::TreeBuilder,
   HTML::Form, and other HTML::Parser consumers.

#### Summary of what's working

| Component | Status |
|-----------|--------|
| Non-server tests | 877/877 via harness (61 test files, excluding cookies.t and frames.t) |
| Local server tests (HTTP::Daemon) | 18/18 (0 timeouts) |
| HTML::Parser (HTMLParser.java) | Chunked parsing, argspec, marked_sections, CDATA, raw text elements |
| HTML::Form | Working (parse, submit, field access) |
| HTML::TreeBuilder | Working |
| Capture::Tiny | capture/capture_stdout/capture_stderr/capture_merged all working |
| LWP::MediaTypes | media.types bundled, MIME detection working |
| IO/socket cleanup | closeIOOnDrop for gensym'd globs |
| TAP::Harness parallel mode | Working (j4 verified) |
| Clone module | Working (Clone::PP fallback) |

---

## Phase 13: TAP Harness Fix + scopeExitCleanup + Clone Fallback — COMPLETE

**Branch**: `feature/www-mechanize-tap-fix`
**Date**: 2026-04-06

### Problem

Running `./jcpan --jobs 4 -t WWW::Mechanize` showed **all tests failing** (0% pass rate) with
the harness reporting "Failed X/X subtests" for every test file. Every test printed its
plan line (`1..N`) but no `ok` lines appeared. The root cause was a chain of three bugs.

### Root Cause Analysis

#### Bug 17: scopeExitCleanup closes IO on shared anonymous globs in foreach loops (CRITICAL)

- **Files**: `EmitForeach.java`, `EmitStatement.java`, `RuntimeScalar.java`
- **Symptom**: `Test::More::ok()` silently failed — `print $io $ok` returned false/empty
- **Root cause chain**:
  1. Test2::Formatter::TAP stores output handles in `$self->{handles}` array
  2. `Test2::Formatter::TAP::write()` and `print_optimal_pass()` copy the handle
     into a lexical: `my $io = $handles->[$hid]`
  3. Both methods iterate with `for my $set (@tap)` which creates a foreach body scope
  4. At the end of each foreach iteration, `emitScopeExitNullStores(ctx, bodyScopeIndex, true)`
     calls `scopeExitCleanup()` on ALL scalar lexicals in the body scope
  5. `scopeExitCleanup()` sees `$io` is a GLOBREFERENCE to an anonymous glob (globName == null)
     and closes the IO handle
  6. This closes the SHARED RuntimeIO object — the same IO used by `$handles->[0]`
  7. After `plan()` writes "1..N", the output handle is dead. All subsequent `ok()` calls
     silently fail (print returns empty, but `print_optimal_pass` returns 1 unconditionally)
- **Fix**: Three changes to make scopeExitCleanup safe for shared globs:
  1. `EmitForeach.java`: Track which variables in the foreach body scope were targets of
     `open()` calls using `ScopedSymbolTable.markAsOpenTarget()`. Only emit
     `scopeExitCleanup` for those variables, not for all scalars
  2. `EmitStatement.java`: Same tracking for while/do-while loop bodies
  3. `RuntimeScalar.java`: Refined `scopeExitCleanup()` to be safe for variables that
     are copies of glob references (never close IO on a variable that didn't open it)
- **Reproducer**:
  ```perl
  open(my $h, ">&", \*STDOUT) or die;
  my @a = ($h);
  for my $x (1) {
      my $copy = $a[0];  # copy of glob ref
  }
  # $h is now closed (fileno returns undef) — BUG
  # System Perl: $h still alive (fileno returns 3) — CORRECT
  ```

#### Bug 18: TAP::Parser::Iterator::Process `$err` comparison on no-fork platforms (HIGH)

- **File**: `TAP/Parser/Iterator/Process.pm` (JAR overlay)
- **Symptom**: `Argument "" isn't numeric in numeric eq (==)` warnings during parallel test runs
- **Root cause**: On no-fork platforms, `_use_open3()` returns false and the fallback path
  sets `$err` to an empty string. Later code does `$err == 0` which warns because `""` is
  not numeric.
- **Fix**: Guard `$err` comparisons with `defined($err) && length($err)` before numeric ops.

#### Bug 19: Clone.pm XSLoader fallback not triggering (MEDIUM)

- **File**: `Clone.pm` (JAR overlay)
- **Symptom**: `Undefined subroutine &Clone::clone` — LWP::UserAgent::clone() fails
- **Root cause**: `XSLoader::load('Clone', $VERSION)` returned success via `@ISA` fallback
  (Exporter) without actually providing the `clone()` function. The `$loaded = 1` line
  executed unconditionally, preventing the Clone::PP fallback.
- **Fix**: Changed `$loaded = 1` to `$loaded = 1 if defined(&clone)`.

#### Bug 20: select() EOF detection and handle lifecycle for TAP parallel mode (HIGH)

- **File**: `IOOperator.java`, `RuntimeIO.java`, `ProcessInputHandle.java`,
  `ProcessOutputHandle.java`, `IOHandle.java`
- **Symptom**: TAP harness in parallel mode (`-j N`) hung indefinitely waiting for child
  process output that had already finished
- **Root cause**: Multiple issues in the select()/IO lifecycle chain:
  1. `select()` didn't properly detect EOF on pipe handles from child processes
  2. Process input/output handles lacked proper `isEof()` implementation
  3. Handle close didn't unregister from the fileno registry, causing stale entries
- **Fix**: Added `isEof()` to IOHandle interface and implementations. Fixed select() to
  check EOF status. Added fileno unregistration on close.

### Commits

1. `81b90c789` — Guard `$err` comparisons in TAP::Parser::Iterator::Process for no-fork
2. `acc4a157b` — select() EOF detection and handle lifecycle for TAP parallel mode
3. `ba0d2daad` — DateTime module loading: strict vars, Clone fallback, XSLoader recursion

### Test Results (2026-04-06)

**Test command**: `./jcpan --jobs 4 -t WWW::Mechanize` (with cookies.t and frames.t excluded
from harness due to hangs — see below)

**Harness results**: `All tests successful. Files=61, Tests=877, 91 wallclock secs`

| Category | Files | Subtests | Pass Rate |
|----------|-------|----------|-----------|
| Non-local tests (t/*.t) | 41/43 | 565/565 | 100% |
| Local server tests (t/local/*.t) | 18/18 | 311/311 | 100% |
| mech-dump tests | 2/2 | 2/2 | 100% |
| **Total (excl. hanging)** | **61/63** | **877/877** | **100%** |

### Individual test results for hanging/failing tests

| Test | Result | Root Cause | Status |
|------|--------|------------|--------|
| cookies.t | HANG (0/14) | `TestServer.pm` requires `open FH, '-\|'` (fork-open, no exec) | Known JVM limitation |
| frames.t | HANG at test 3 (2/7) | `$mech->get(URI::file->...)` hangs on file:// URI fetch | LWP file:// handler issue |
| field.t | 15/16 (1 TODO fail) | HTML::TokeParser limitation, marked TODO in test | Not a PerlOnJava bug |
| local/overload.t | SKIP | Test skips itself | OK |

### Remaining Issues

1. **cookies.t** — Blocked on JVM `fork()` limitation. The test uses `TestServer.pm` which
   requires `open FH, '-|'` (fork-open without exec). No workaround available. LOW priority.

2. **frames.t** — Hangs on `$mech->get()` with a `file://` URI. The `URI::file->new_abs()`
   creates a local file URL and LWP's file handler appears to block. This may be related to
   how LWP::Protocol::file handles local reads, or a blocking IO issue. MEDIUM priority.

3. **field.t TODO test** — 1 subtest out of 16 is a known HTML::TokeParser limitation
   (marked as TODO in the test itself). Not a PerlOnJava bug.

### Verification

```bash
# Full build + unit tests (no regressions)
make

# Run WWW::Mechanize tests via harness (excludes hanging tests)
cd ~/.cpan/build/WWW-Mechanize-2.20-0
HARNESS_OPTIONS=j4 jperl -MTest::Harness \
  -e 'my @t = grep { !/cookies|frames/ } glob("t/*.t t/local/*.t t/mech-dump/*.t"); runtests(@t)'

# Run individual tests directly
jperl t/00-load.t          # Quick smoke test
jperl t/local/get.t        # Local server test
```

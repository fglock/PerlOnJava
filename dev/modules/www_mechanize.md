# WWW::Mechanize Support for PerlOnJava

## Status: Phase 7 Complete — 98.1% pass rate

**Branch**: `feature/www-mechanize-support`
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

### Current Status: Phase 7 complete — 522/532 subtests pass (98.1%)

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

**Non-local test results: 522/532 pass (98.1%)**

| Test File | Result | Root Cause | Status |
|-----------|--------|------------|--------|
| dump.t | 1/7 | Capture::Tiny STDOUT capture needs fork() | Won't fix (JVM limitation) |
| field.t | 15/16 (TODO fail) | Expected: HTML::TokeParser limitation | OK (TODO test) |
| find_link_xhtml.t | 8/10 | XHTML `<![CDATA[...]]>` / `marked_sections` not implemented | Low priority |
| image-parse.t | 41/42 | 1 remaining CSS background-url edge case | Low priority |
| mech-dump/file_not_found.t | 0/1 | Capture::Tiny STDERR capture needs fork() | Won't fix (JVM limitation) |
| cookies.t | TIMEOUT | Needs fork() for HTTP::Daemon | Won't fix (JVM limitation) |
| t/local/*.t | TIMEOUT | Needs fork() for HTTP::Daemon | Won't fix (JVM limitation) |

### Remaining Issues (all low priority or JVM limitations)

1. **Capture::Tiny / fork()** — dump.t (6 failures), mech-dump/file_not_found.t (1 failure), cookies.t (timeout), all t/local/ tests (timeout). These all need fork() which is not available on JVM.

2. **XHTML marked_sections** — find_link_xhtml.t (2 failures). `<![CDATA[...]]>` parsing not implemented in HTMLParser.

3. **CSS background-url extraction** — image-parse.t (1 failure). WWW::Mechanize extracts images from inline `style="background:url(...)"` on non-img elements.

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
- All actionable fixes are complete
- Remaining failures are either JVM limitations (fork/Capture::Tiny) or low-priority features (CDATA, CSS url extraction)
- PR #440 ready for review

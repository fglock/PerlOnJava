# WWW::Mechanize Support for PerlOnJava

## Status: Phase 1 In Progress

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

### Current Status: Phase 1 in progress

### Completed Phases
- (none yet)

### Next Steps
1. Fix IdentifierParser.java (Bug 1)
2. Fix Universal.java (Bug 2)
3. Build and test

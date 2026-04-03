# XML::Simple Fix Plan

## Overview

**Module**: XML::Simple 2.25 (depends on XML::SAX 1.02, XML::SAX::PurePerl)  
**Test command**: `./jcpan -j 8 -t XML::Simple`  
**Status**: 0/14 pass, 10/14 fail, 4/14 skip — caused by 3 distinct PerlOnJava bugs

## Dependency Tree

```
XML::Simple 2.25
├── XML::SAX 1.02 (ParserDetails.ini parsing)
│   └── XML::SAX::PurePerl (default parser)
│       └── XML::SAX::PurePerl::Productions (Unicode regex char classes)
│   └── XML::SAX::Base (indirect method call: throw)
│   └── XML::SAX::Exception (throw method)
├── XML::Parser (optional, not installed)
├── XML::SAX::Expat (optional, not installed)
└── Storable (lock_nstore missing)
```

## Test Results Summary

### Current Status: 0/14 test files pass (65 subtests run of ~695 planned)

| Test File | Plan | Ran | Status | Root Cause |
|-----------|------|-----|--------|------------|
| t/0_Config.t | 1 | 0 | FAIL | Bug 1 (INI blank line) → Bug 2 (PurePerl regex) |
| t/1_XMLin.t | 132 | 1 | FAIL | Bug 1 → Bug 2 (all XMLin blocked) |
| t/2_XMLout.t | 201 | 4 | FAIL | Bug 2 (XMLout works, XMLin fails) |
| t/3_Storable.t | 0 | 0 | SKIP | Missing `lock_nstore` in Storable (minor) |
| t/4_MemShare.t | 8 | 1 | FAIL | Bug 2 (XMLin blocked) |
| t/5_MemCopy.t | 7 | 1 | FAIL | Bug 2 (XMLin blocked) |
| t/6_ObjIntf.t | 37 | 6 | FAIL | Bug 2 (object creation OK, XMLin blocked) |
| t/7_SaxStuff.t | 8 | 0 | FAIL | Bug 3 (XML::SAX::Base compile error) |
| t/8_Namespaces.t | 8 | 0 | FAIL | Bug 2 (XMLin blocked) |
| t/9_Strict.t | 44 | 44 | FAIL (33/44) | Bug 2 (XMLout tests pass, XMLin tests fail) |
| t/A_XMLParser.t | 0 | 0 | SKIP | No XML::Parser (expected) |
| t/B_Hooks.t | 12 | 8 | FAIL | Bug 2 (hooks setup OK, XMLin blocked) |
| t/C_External_Entities.t | 0 | 0 | SKIP | No XML::Parser (expected) |
| t/author-pod-syntax.t | 0 | 0 | SKIP | Author tests (expected) |

### Error Cascade

Most tests fail due to a **cascade of errors**:
1. XML::Simple calls XMLin() which needs a SAX parser
2. XML::SAX reads ParserDetails.ini → Bug 1 (blank line crashes INI parser)
3. Even with fixed INI, loading XML::SAX::PurePerl → Bug 2 (regex crash in Productions.pm)
4. XML::SAX::Base can't compile → Bug 3 (indirect method `throw` syntax error)

**Fixing Bug 2 alone would unblock ~90% of failures.**

## Bug Details

### Bug 1: `/^$/m` doesn't match empty strings — Java MULTILINE quirk (HIGH)

**Impact**: XML::SAX ParserDetails.ini parsing; any Perl code using `/^$/m` on empty/blank lines  
**Root cause**: Java's `Pattern.MULTILINE` flag changes the behavior of `$` to only match "before a line terminator" — but for an empty string there IS no line terminator, so `$` never matches. Without MULTILINE, `$` matches "end of input" which works for empty strings.

**Evidence**:
```java
// Java behavior (confirmed with Java 24):
Pattern.compile("^$").matcher("").find()                          // true
Pattern.compile("^$", Pattern.MULTILINE).matcher("").find()       // false ← BUG
Pattern.compile("^$", Pattern.MULTILINE | UNICODE_CC).matcher("").find()  // false ← BUG
```

```perl
# PerlOnJava:
"" =~ /^$/     # matches (correct)
"" =~ /^$/m    # NO match (wrong)

# System Perl:
"" =~ /^$/     # matches
"" =~ /^$/m    # matches
```

**Affected code**: XML::SAX.pm `_parse_ini_file()` uses `next if $line =~ /^$/m` to skip blank lines. After whitespace stripping, blank lines become `""` which doesn't match, so they fall through to the `die "Invalid line in ini"` error.

**Fix**: Add a workaround in `RuntimeRegex.java` for the `matchRegexDirect` method — when the input is empty and MULTILINE is set, also try without MULTILINE. Or better: in the regex preprocessor, detect `^` and `$` patterns with MULTILINE and add `\z` alternatives.

**Files**:
- `src/main/java/org/perlonjava/runtime/regex/RuntimeRegex.java` (matchRegexDirect, ~line 578)

### Bug 2: `\x{NNNN}` broken in regex character classes `[...]` (CRITICAL)

**Impact**: ALL XMLin operations fail — XML::SAX::PurePerl::Productions.pm can't compile; affects any regex using `[\x{NNNN}-\x{MMMM}]`  
**Root cause**: The `handleRegexCharacterClassEscape` method in `RegexPreprocessorHelper.java` does NOT handle `\x{...}` sequences. Only `\N{...}`, `\o{...}`, `\b`, and octal/digit escapes are handled. The `\x{41}` is parsed as `\x` + literal `{41}`, making `}` the range start and `\x` the range end → invalid range error.

**Evidence**:
```perl
# PerlOnJava:
qr/[\x{41}-\x{5A}]/   # ERROR: Invalid [] range "}-x" in regex
qr/[\x41-\x5A]/        # OK (non-braced hex works)

# System Perl:
qr/[\x{41}-\x{5A}]/   # OK
```

**Three code paths handle `\x{}`**:

| Context | Method | Handles `\x{}`? |
|---------|--------|------------------|
| Outside `[...]` | `handleEscapeSequences()` | YES |
| Inside `[...]` | `handleRegexCharacterClassEscape()` | **NO — THE BUG** |
| Inside `(?[...])` | `processCharacterClass()` | YES |

**Fix**: Add `\x{...}` handling in `handleRegexCharacterClassEscape`, mirroring the existing `\o{...}` handler. Also update the `-` range validator look-ahead to parse `\x{NNNN}` endpoints.

**Files**:
- `src/main/java/org/perlonjava/runtime/regex/RegexPreprocessorHelper.java` (~line 663, after `\o{...}` handler)
- `src/main/java/org/perlonjava/runtime/regex/RegexPreprocessorHelper.java` (~line 555, range validation)

### Bug 3: Indirect method call fails with unknown method + qualified class (MEDIUM)

**Impact**: XML::SAX::Base.pm (2718 lines) can't compile; `throw ClassName::SubClass(...)` pattern used throughout  
**Root cause**: PerlOnJava's parser rejects indirect method call syntax `METHOD Package::Name(args)` when METHOD is not a previously-defined subroutine. In `SubroutineParser.java` line 236, when `isPackage == null` (class not in package cache) AND `!isKnownSub` (method not declared) AND followed by `(`, the parser backtracks and rejects the indirect method call — even when the class name contains `::` which makes it unambiguously a package name.

**Evidence**:
```perl
# PerlOnJava:
throw Foo::Bar(x => 1);       # syntax error near "::Bar("
# After defining throw:
package Foo::Bar; sub throw { ... }
throw Foo::Bar(x => 1);       # OK

# System Perl:
throw Foo::Bar(x => 1);       # runtime error "Can't locate object method" (not syntax error)
```

**Fix**: Modify the condition on line 236 of `SubroutineParser.java` to NOT reject when `packageName.contains("::")` — a `::` in the name means it's unambiguously a package-qualified identifier.

**Files**:
- `src/main/java/org/perlonjava/frontend/parser/SubroutineParser.java` (line 236)

### Bug 4: Storable `lock_nstore`/`lock_store`/`lock_retrieve` not implemented (LOW)

**Impact**: t/3_Storable.t skipped; minor — file locking stubs would suffice  
**Root cause**: These functions are listed in `@EXPORT_OK` in `Storable.pm` but have no `sub` definitions. JVM doesn't need file locking for Storable cache files in practice.

**Files**:
- `src/main/perl/lib/Storable.pm`

## Fix Plan

### Phase 1: Fix `\x{NNNN}` in regex character classes (CRITICAL — unblocks ~90% of tests)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 1.1 | Add `\x{...}` handler in `handleRegexCharacterClassEscape` | `RegexPreprocessorHelper.java` | |
| 1.2 | Update `-` range validator look-ahead for `\x{NNNN}` | `RegexPreprocessorHelper.java` | |
| 1.3 | Add unit test for `[\x{NNNN}-\x{MMMM}]` patterns | test file | |
| 1.4 | Run `make` to verify no regressions | | |

### Phase 2: Fix `/^$/m` empty string matching (HIGH — fixes INI parsing)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 2.1 | Add Java MULTILINE workaround for empty string input | `RuntimeRegex.java` | |
| 2.2 | Add unit test for `/^$/m` on empty strings | test file | |
| 2.3 | Run `make` to verify no regressions | | |

### Phase 3: Fix indirect method call with qualified class (MEDIUM — fixes XML::SAX::Base)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 3.1 | Allow indirect call when packageName contains `::` | `SubroutineParser.java` | |
| 3.2 | Add unit test for `throw Package::Name(args)` syntax | test file | |
| 3.3 | Run `make` to verify no regressions | | |

### Phase 4: Add Storable lock stubs (LOW)

| Step | Description | File | Status |
|------|-------------|------|--------|
| 4.1 | Add `lock_nstore`, `lock_store`, `lock_retrieve` stubs | `Storable.pm` | |

### Phase 5: Re-run XML::Simple tests and validate

| Step | Description | Status |
|------|-------------|--------|
| 5.1 | Fix ParserDetails.ini (populated by `XML::SAX->add_parser`) | |
| 5.2 | Run `./jcpan -j 8 -t XML::Simple` | |
| 5.3 | Analyze remaining failures (if any) | |

## Expected Results After Fixes

| Test File | Current | Expected |
|-----------|---------|----------|
| t/0_Config.t | 0/1 | 1/1 |
| t/1_XMLin.t | 1/132 | ~132/132 |
| t/2_XMLout.t | 4/201 | ~201/201 |
| t/3_Storable.t | SKIP | PASS (with Phase 4) or SKIP |
| t/4_MemShare.t | 1/8 | ~8/8 |
| t/5_MemCopy.t | 1/7 | ~7/7 |
| t/6_ObjIntf.t | 6/37 | ~37/37 |
| t/7_SaxStuff.t | 0/8 | ~8/8 (needs Phase 3) |
| t/8_Namespaces.t | 0/8 | ~8/8 |
| t/9_Strict.t | 33/44 | ~44/44 |
| t/B_Hooks.t | 8/12 | ~12/12 |

## Progress Tracking

### Current Status: Investigation complete, fixes not started

### Completed Phases
- [x] Investigation (2026-04-03)
  - Identified 3 PerlOnJava bugs + 1 minor Storable gap
  - Confirmed Java MULTILINE quirk with standalone Java test
  - Traced all code paths for each bug
  - Files: RegexPreprocessorHelper.java, RuntimeRegex.java, SubroutineParser.java, Storable.pm

### Next Steps
1. Implement Phase 1 (regex \x{} in char classes)
2. Implement Phase 2 (empty string /m matching)
3. Implement Phase 3 (indirect method call)
4. Re-run XML::Simple tests

## Related Documents
- `dev/modules/README.md` - Module porting index
- `dev/modules/cpan_patch_plan.md` - CPAN patching strategy

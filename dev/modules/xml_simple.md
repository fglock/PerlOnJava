# XML::Simple Fix Plan

## Overview

**Module**: XML::Simple 2.25 (depends on XML::SAX 1.02, XML::SAX::PurePerl)  
**Test command**: `./jcpan --jobs 8 -t XML::Simple`  
**Status**: **14/14 test files pass, 487/487 subtests (100%)** — ALL FIXED

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

### Final Status: 14/14 test files pass, 487/487 subtests (100%)

| Test File | Plan | Passed | Status |
|-----------|------|--------|--------|
| t/0_Config.t | 1 | 1 | PASS |
| t/1_XMLin.t | 132 | 132 | PASS |
| t/2_XMLout.t | 201 | 201 | PASS |
| t/3_Storable.t | 4 | 4 | PASS |
| t/4_MemShare.t | 8 | 8 | PASS |
| t/5_MemCopy.t | 7 | 7 | PASS |
| t/6_ObjIntf.t | 37 | 37 | PASS |
| t/7_SaxStuff.t | 8 | 8 | PASS |
| t/8_Namespaces.t | 8 | 8 | PASS |
| t/9_Strict.t | 44 | 44 | PASS |
| t/A_XMLParser.t | 0 | 0 | SKIP (no XML::Parser) |
| t/B_Hooks.t | 12 | 12 | PASS |
| t/C_External_Entities.t | 0 | 0 | SKIP (no XML::Parser) |
| t/author-pod-syntax.t | 0 | 0 | SKIP (author tests) |

## Bugs Fixed

### Bug 1: `/^$/m` doesn't match empty strings — Java MULTILINE quirk

**Fix**: Strip MULTILINE flag for empty input strings in `matchRegexDirect` and `replaceRegex`.  
**Files**: `RuntimeRegex.java`

### Bug 2: `\x{NNNN}` broken in regex character classes `[...]`

**Fix**: Added `\x{...}` handler in `handleRegexCharacterClassEscape`, updated range validator.  
**Files**: `RegexPreprocessorHelper.java`

### Bug 3: Indirect method call fails with unknown method + qualified class

**Fix**: Allow indirect method call when packageName contains `::`.  
**Files**: `SubroutineParser.java`

### Bug 4: Storable `lock_nstore`/`lock_store`/`lock_retrieve` not implemented

**Fix**: Added stubs delegating to non-locking variants.  
**Files**: `Storable.pm`

### Bug 5: `use vars` / Exporter-imported single-letter variables rejected under strict

**Root cause**: Single-letter globals like `$A`-`$Z` auto-vivified under `no strict` would
incorrectly bypass `use strict 'vars'` later (since `existsGlobalVariable` returned true).
A blunt fix that forced `existsGlobally = false` for all single-letter vars also blocked
legitimately imported ones (e.g., `$S` from XML::SAX::PurePerl::Productions via Exporter).

**Fix**: Added `declaredGlobalVariables/Arrays/Hashes` tracking sets in `GlobalVariable.java`
that distinguish explicitly declared globals (`use vars`, Exporter glob assignment) from
auto-vivified ones. `Vars.importVars()` and `RuntimeGlob.set()` (REFERENCE/ARRAY/HASH cases)
now call `declareGlobal*()`. The strict check for single-letter vars consults
`isDeclaredGlobalVariable()` instead of unconditionally blocking.

**Files**: `GlobalVariable.java`, `Vars.java`, `RuntimeGlob.java`, `Variable.java`, `EmitVariable.java`, `BytecodeCompiler.java`

### Bug 6: `Encode::define_alias` missing

**Fix**: Added stub delegating to `Encode::Alias::define_alias`.  
**Files**: `Encode.pm`

### Bug 7: `warnings::enabled()` broken for custom categories from `warnings::register`

**Root cause**: Two issues:
1. `warnings::enabled()` (no args) used `getCallerPackageAtLevel(0)` which added a +1 offset,
   skipping the direct caller and returning the wrong package name. Fixed to use `caller(0)` 
   directly to get the correct calling package.
2. `isEnabledInBits()` / `isFatalInBits()` failed for custom warning categories because the
   category's bit position (offset >= 128) either exceeded the standard 21-byte warning bits 
   string or wasn't set in the bits string (since the category was registered via 
   `warnings::register` after `use warnings` compiled). Fixed to fall back to checking if the
   "all" category is enabled — in Perl 5, `use warnings` (which enables "all") implicitly 
   enables all custom categories, including ones registered later.

**Files**: `Warnings.java`, `WarningFlags.java`

## Progress Tracking

### Current Status: COMPLETE — 487/487 subtests pass (100%)

### Completed Phases
- [x] Investigation (2026-04-03)
  - Identified 7 PerlOnJava bugs
  - Files analyzed: RegexPreprocessorHelper.java, RuntimeRegex.java, SubroutineParser.java, Storable.pm, Warnings.java, WarningFlags.java
- [x] Phase 1: Fix `\x{NNNN}` in regex character classes (2026-04-03)
  - Files: RegexPreprocessorHelper.java
- [x] Phase 2: Fix `/^$/m` empty string matching (2026-04-03)
  - Files: RuntimeRegex.java
- [x] Phase 3: Fix indirect method call with qualified class (2026-04-03)
  - Files: SubroutineParser.java
- [x] Phase 4: Add Storable lock stubs (2026-04-03)
  - Files: Storable.pm
- [x] Phase 5: Fix strict vars for single-letter globals (declared vs auto-vivified) (2026-04-03)
  - Added declaredGlobals tracking to GlobalVariable.java
  - Hooked Vars.importVars() and RuntimeGlob.set() to declare globals
  - Files: GlobalVariable.java, Vars.java, RuntimeGlob.java, Variable.java, EmitVariable.java, BytecodeCompiler.java
- [x] Phase 6: Add `Encode::define_alias` stub (2026-04-03)
  - Files: Encode.pm
- [x] Phase 7: Fix `warnings::enabled()` for custom categories (2026-04-03)
  - Fixed caller(0) offset in enabled()/fatalEnabled()/warnIf() methods
  - Fixed isEnabledInBits/isFatalInBits fallback for custom categories
  - Files: Warnings.java, WarningFlags.java

## Related Documents
- `dev/modules/README.md` - Module porting index
- `dev/modules/cpan_patch_plan.md` - CPAN patching strategy

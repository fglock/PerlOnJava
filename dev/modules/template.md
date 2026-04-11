# Template Toolkit Support for PerlOnJava

## Overview

Template Toolkit (TT) 3.102 is a widely-used Perl template processing system.
This document tracks the work needed to make `./jcpan --jobs 8 -t Template` pass
its test suite on PerlOnJava.

## Current Status

**Branch:** `feature/fix-template-toolkit`
**Module version:** Template Toolkit 3.102 (106 test programs, 2884 subtests)

### Results History

| Date | Programs Failed | Subtests Failed | Total Subtests | Key Fix |
|------|----------------|-----------------|----------------|---------|
| Baseline | 88/106 | 576/836 | 836 | — |
| After Fix 1 | 80/106 | ~500/~850 | ~850 | MakeMaker JAR shim |
| After Fix 2 | 70/106 | 23/842 | 842 | `use constant`/`our` clash |
| After Fix 3+4 | **19/106** | **57/2884** | **2884** | Interpreter `our` binding + XSLoader @ISA fallback |
| After Fix 5+6 | **16/106** | — | — | Error location attribution + compiled template loading |
| After Fix 7 | **3/106** | ~23/2884 | **2884** | XSLoader jar: shim overrides + stale .ttc cleanup |
| After Fix 8 | **2/106** | ~3/2884 | **2884** | `use bytes` length fix for Latin-1 strings |
| After Fix 9 | **1/106** | ~2/2884 | **2884** | Scope `packageExistsCache` sub entries to declaring package |
| After Fix 10 | **21/106** | 3/2642 | **2642** | `our`/`state` statement modifier fix (DESTROY regression: 20 tests) |

### Current: 85/106 passing (80%), 12 skipped, 21 failing (20 due to premature DESTROY — fix in progress on separate branch)

---

## Completed Fixes

### Fix 1: JAR Shim Preservation

When `jcpan` installs Template Toolkit, it copies `Template/Stash/XS.pm` from
CPAN into `~/.perlonjava/lib/`.  This shadows the PerlOnJava shim in
`jar:PERL5LIB` that provides a pure-Perl fallback via `Template::Stash`.

**Fix:** Modified `_handle_xs_module()` and `_install_pure_perl()` in
`ExtUtils/MakeMaker.pm` to skip `.pm` files that already exist in
`jar:PERL5LIB`.

**File:** `src/main/perl/lib/ExtUtils/MakeMaker.pm`

### Fix 2: `use constant` / `our $VAR` Clash

`Template::Parser` does `use constant ERROR => 2; our $ERROR = '';` —
PerlOnJava threw "Modification of a read-only value" because
`RuntimeStashEntry.set()` incorrectly wrote the constant's read-only
value into the scalar variable slot.

**Fix:** Removed the `globalVariables.put()` call in `RuntimeStashEntry.java`.
Constant subroutine creation (`&ERROR`) is unaffected; scalar variable (`$ERROR`)
is now independent.

**Files:** `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeStashEntry.java`,
`src/test/resources/unit/constant.t`

### Fix 3: Interpreter Fallback `our` Variable Binding

`Template::Grammar` has a ~6000-line BEGIN block that falls back to the
bytecode interpreter.  `detectClosureVariables()` pre-populated registers,
so `our $STATES` declarations hit `hasVariable() → true` and skipped
`LOAD_GLOBAL_SCALAR` emission.  The register held the file-scope `my` (undef)
instead of the package global.

**Fix:** In `BytecodeCompiler.java`, emit `LOAD_GLOBAL_SCALAR/ARRAY/HASH` for
`our` declarations even when `hasVariable()` returns true (the register exists
but must be rebound to the global).

**File:** `src/main/java/org/perlonjava/backend/bytecode/BytecodeCompiler.java`

### Fix 4: XSLoader @ISA Fallback

The CPAN `Template/Stash/XS.pm` sets `@ISA = ('Template::Stash')` then calls
`XSLoader::load`.  Since there's no Java XS class, XSLoader died — taking down
the entire `require`.

**Fix:** In `XSLoader.java`, when no Java XS class is found, check if the
module's `@ISA` already has a pure-Perl parent.  If so, return success — the
module works through inheritance.  This is general-purpose and applies to any
XS module with a Perl parent.

**File:** `src/main/java/org/perlonjava/runtime/perlmodule/XSLoader.java`

### Fix 10: `our`/`state` Statement Modifier Hoisting

`Template::Service` (and 15 other TT files) declare `our $DEBUG = 0 unless defined $DEBUG`.
The parser's `handleStatementModifierWithMy` transformed this to `defined($DEBUG) || (our $DEBUG = 0)`,
placing the `$DEBUG` reference before the `our` declaration.  Under `use strict`, this caused
"Global symbol requires explicit package name", preventing `Makefile.PL` from running at all.

**Root cause:** The method only checked for `my` declarations (line 1046), not `our` or `state`.

**Fix:** Extended the check in `StatementResolver.java` to handle `my`, `our`, and `state`:
```
our $X = EXPR unless COND  →  (our $X, COND || ($X = EXPR))
```
This ensures the variable is declared before the condition is evaluated.

**Files:** `src/main/java/org/perlonjava/frontend/parser/StatementResolver.java`,
`src/test/resources/unit/statement.t`

---

## Remaining Failures (21/106 programs)

### Premature DESTROY — 20 test programs failing

`Template::Context::DESTROY` sets `$self->{STASH} = undef` to break circular references.
Since DESTROY was implemented (commit `97ec12b8b`), PerlOnJava's refCount tracking triggers
DESTROY prematurely during `Template::_output()`, nullifying the stash while the Context is
still in use.  This affects any test that calls `process()` with `INCLUDE`/`PROCESS` directives.

**Status:** Fix in progress on a separate branch.

### leak.t — subtests failing (expected)

| Test | Failed | Total | Issue |
|------|--------|-------|-------|
| leak.t | varies | 11 | Premature DESTROY + original DESTROY timing tests |

---

## Next Steps

### Premature DESTROY fix (separate branch)
The 20 failing tests are all caused by `Template::Context::DESTROY` firing while the Context
is still in use.  A fix for PerlOnJava's refCount tracking is in progress on a separate branch.
Once merged, Template Toolkit should return to 105/106 passing.

### Post-merge: jcpan parallel test ordering
When running `./jcpan --jobs 8 -t Template`, the compile tests (`compile2.t`, `compile3.t`,
`compile5.t`) may spuriously fail because they depend on `compile1.t` having run first
to populate the compiled template cache. Running them sequentially (or with `-j 1`) always
passes. This is a test-harness ordering issue, not a PerlOnJava bug.

---

## Progress Tracking

### Completed
- [x] Fix 1: JAR shim preservation in MakeMaker (2025-04-04)
- [x] Fix 2: `use constant` / `our $VAR` clash in RuntimeStashEntry.java (2025-04-04)
- [x] Fix 3: Interpreter fallback `our` variable binding in BytecodeCompiler.java (2025-04-04)
- [x] Fix 4: XSLoader @ISA fallback for modules with pure-Perl parents (2025-04-04)
- [x] Unit tests pass (`make` green)
- [x] Template tests: 87/106 passing (19 failing, 11 skipped)
- [x] Fix 5: Error location attribution — PerlCompilerException, ByteCodeSourceMapper (2025-04-04)
  - compile1.t: 17/18 → **18/18**, compile4.t: 12/13 → **13/13**
- [x] Fix 6: Compiled template loading — utime, error location in .ttc, filehandle context (2025-04-04)
  - compile5.t: ~0/14 → **14/14**
  - Also fixed: `$.` counting in slurp/paragraph mode, `close()` resets `$.`, `<FH> chunk/line N` suffix
- [x] Template tests: 90/106 passing (16 failing, 11 skipped)
- [x] Fix 7: XSLoader jar: shim overrides (2025-04-05)
  - XSLoader now loads jar: PERL5LIB shim overrides after @ISA fallback succeeds
  - Fixed tiedhash.t: Template::Stash::XS `_assign()` array branch bug corrected
  - Fixed EvalStringHandler simple evalString to include @_ in symbol table
  - Many tests that appeared failing were actually passing (stale .ttc cache files were the issue)
  - Files: XSLoader.java, EvalStringHandler.java, Template/Stash/XS.pm
- [x] Template tests: **103/106 passing** (3 failing, 11 skipped) — **97% pass rate**
- [x] Fix 8: `use bytes` length fix for Latin-1 strings (2025-04-05)
  - `lengthBytes` now returns character count for strings with all chars <= 0xFF (Latin-1)
  - Previously always used UTF-8 byte count, causing BOM detection in Template::Provider to fail
  - unicode.t: 0/20 → **20/20** — all BOM formats (UTF-8/16BE/16LE/32BE/32LE) now detected and decoded
  - File: StringOperators.java
- [x] Template tests: **104/106 passing** (2 failing, 11 skipped) — **98% pass rate**
- [x] Fix 9: Scope `packageExistsCache` sub entries to declaring package (2025-04-05)
  - `sub error` in `Template::Base` was stored as bare "error" → false in the global cache
  - This prevented `error` from being used as indirect method class name in other packages
  - Fix: store qualified name (e.g., "Template::Base::error") and check qualified name at lookup
  - evalperl.t: 18/19 → **19/19** — RAWPERL blocks with illegal code now correctly eval'd
  - File: SubroutineParser.java
- [x] Template tests: **105/106 passing** (1 failing, 11 skipped) — **99% pass rate**
- [x] Fix 10: `our`/`state` statement modifier hoisting in StatementResolver.java (2026-04-11)
  - `our $DEBUG = 0 unless defined $DEBUG` now correctly hoists the declaration
  - Unblocked Makefile.PL — Template Toolkit can now configure and build
  - 20 tests regressed due to premature DESTROY (separate issue, separate branch)

### Remaining
- [ ] Premature DESTROY — 20 tests failing (fix in progress on separate branch)

---

## Related Documents

- `dev/modules/xs_fallback.md` — XS fallback mechanism (Phase 5 & 6)
- `dev/modules/makemaker_perlonjava.md` — MakeMaker implementation

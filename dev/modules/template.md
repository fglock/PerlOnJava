# Template Toolkit Support for PerlOnJava

## Overview

Template Toolkit (TT) 3.102 is a widely-used Perl template processing system.
This document tracks the work needed to make `./jcpan -j 8 -t Template` pass
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

### Current: 103/106 passing (97%), 11 skipped, 3 truly failing

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

---

## Remaining Failures (3/106 programs)

### evalperl.t — 1/19 subtests failing

| Test | Failed | Total | Issue |
|------|--------|-------|-------|
| evalperl.t | 1 | 19 | Test 11 — `INCLUDE badrawperl` expects raw Perl output but gets empty + file error. EVAL_PERL context not propagating to included templates. |

### leak.t — 2/11 subtests failing (expected)

| Test | Failed | Total | Issue |
|------|--------|-------|-------|
| leak.t | 2 | 11 | Tests 7, 11 — DESTROY not implemented in PerlOnJava (known limitation) |

### unicode.t — 20/20 subtests failing (deferred)

| Test | Failed | Total | Issue |
|------|--------|-------|-------|
| unicode.t | 20 | 20 | BOM detection/stripping not implemented (UTF-8/16/32). Deep encoding issue, deferred. |

---

## Next Steps

1. **evalperl.t test 11** — Investigate EVAL_PERL context propagation to included templates
2. **unicode.t** — BOM detection/stripping (deferred — deep encoding work)
3. **leak.t** — DESTROY not implemented (known limitation, not fixable without DESTROY support)

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

### Remaining (not planned)
- [ ] evalperl.t test 11 — EVAL_PERL context propagation
- [ ] leak.t tests 7, 11 — DESTROY not implemented
- [ ] unicode.t — BOM handling deferred

---

## Related Documents

- `dev/modules/xs_fallback.md` — XS fallback mechanism (Phase 5 & 6)
- `dev/modules/makemaker_perlonjava.md` — MakeMaker implementation

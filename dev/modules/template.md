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

### Current: 87/106 passing (82%), 11 skipped, 8 truly failing

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

## Remaining Failures (19/106 programs, 57/2884 subtests)

### Category 1: Compiled Templates (compile1–5) — 5 programs, 12 subtests

Tests that compile templates to `.ttc` files (Storable-serialized Perl code).
Issues include missing `.ttc` files and `EVAL_PERL` not being set.

| Test | Failed | Total | Issue |
|------|--------|-------|-------|
| compile1.t | **0** | 18 | **FIXED** — all passing |
| compile2.t | 2 | 4 | `foo.ttc: No such file or directory` |
| compile3.t | 3 | 14 | Cached template mtime comparison |
| compile4.t | **0** | 13 | **FIXED** — all passing |
| compile5.t | **0** | 14 | **FIXED** — all passing |

**Fixes applied (2025-04-04):**
- PerlCompilerException: use current execution location (not caller site) for error messages
- PerlCompilerException: detect interpreter vs JVM innermost execution context by scanning stack
- ByteCodeSourceMapper: honor `#line` directives via `getSourceLocationAccurate()`
- WarnDie: implement Perl 5's `, <FH> line/chunk N` filehandle context suffix
- Readline: fix `$.` counting in slurp mode (1 per read) and paragraph mode (1 per paragraph)
- RuntimeIO.close(): reset `currentLineNumber` to 0 on close
- UtimeOperator: fix timestamp argument handling for compiled template cache loading

### Category 2: Unicode/Encoding — 2 programs, 22 subtests

| Test | Failed | Total | Issue |
|------|--------|-------|-------|
| unicode.t | 20 | 20 | BOM detection (UTF-8/16/32) not stripping BOMs |
| stash-xs-unicode.t | 2 | 11 | UTF-8 key fetch/assign |

**Root cause:** `Encode` module and BOM-stripping logic in `Template::Provider`.
PerlOnJava doesn't strip BOMs or decode UTF-16/32 templates.

### Category 3: CRLF Handling — 1 program, 2 subtests

| Test | Failed | Total | Issue |
|------|--------|-------|-------|
| chomp.t | 2 | 78 | `\r\n` expected but got `\n` |

**Root cause:** Tests expect Windows CRLF output; PerlOnJava produces LF only.

### Category 4: Filters — 1 program, 8 subtests

| Test | Failed | Total | Issue |
|------|--------|-------|-------|
| filter.t | 8 | 162 | Tests 102-105, 110-113 |

**Root cause:** Needs investigation — likely specific filter implementations
(eval, redirect, or perl-based filters).

### Category 5: Tied Hashes — 1 program, 2 subtests

| Test | Failed | Total | Issue |
|------|--------|-------|-------|
| tiedhash.t | 2 | 51 | "Not a HASH reference" in Stash.pm line 281 |

**Root cause:** `DEFAULT list.5 = 80` — assigning to a list element via the
stash triggers a code path that expects a hash ref.

### Category 6: Minor/Miscellaneous — 9 programs, 11 subtests

| Test | Failed | Total | Issue |
|------|--------|-------|-------|
| context.t | 1 | 54 | Test 13 |
| evalperl.t | 1 | 19 | Test 11 — embedded Perl eval |
| fileline.t | 3 | 11 | File/line number reporting |
| html.t | 1 | 18 | HTML entity encoding |
| leak.t | 2 | 11 | DESTROY-related (expected, no DESTROY in PerlOnJava) |
| meta.t | 1 | 3 | Metadata count |
| parser.t | 1 | 37 | Test 35 |
| proc.t | 2 | 7 | PROCESS directive |
| url.t | 1 | 23 | Test 23 |

---

## Next Steps

1. **Investigate filter.t failures** (8 subtests) — highest impact fixable category
2. **Investigate compile tests** — may reveal Storable or eval issues
3. **Investigate tiedhash.t** — may reveal stash dot-notation bug
4. **Skip unicode.t** — BOM handling is a deep encoding issue, defer
5. **Skip leak.t** — DESTROY not implemented, known limitation
6. **Investigate remaining misc failures** one by one

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

### In Progress
- [ ] Analyze and fix remaining 16 failing test programs

### Pending
- [ ] Investigate compile2.t and compile3.t

---

## Related Documents

- `dev/modules/xs_fallback.md` — XS fallback mechanism (Phase 5 & 6)
- `dev/modules/makemaker_perlonjava.md` — MakeMaker implementation

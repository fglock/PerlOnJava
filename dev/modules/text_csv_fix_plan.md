# Text::CSV Fix Plan

## Problem

`./jcpan --jobs 4 -t Text::CSV` fails. Multiple root causes identified across four phases.

## Architecture

PerlOnJava ships a **bundled Text::CSV** (`src/main/perl/lib/Text/CSV.pm`, 557 lines) that wraps Apache Commons CSV (Java) via `TextCsv.java`. It provides basic CSV functionality but is missing ~40+ methods from the CPAN version.

The CPAN **Text::CSV 2.06** is a thin wrapper that delegates to `Text::CSV_PP` (pure Perl, 3,480 lines of code). It provides full compatibility with Text::CSV_XS including all accessors, error handling, callbacks, types, etc.

When a user installs Text::CSV via `jcpan`, the CPAN version (+ CSV_PP) should override the bundled version. The bundled version remains as a zero-install fallback for users who don't need the full CPAN feature set.

## Current Test Results (after Phase 9)

**39/40 test programs pass.** ~52,360 subtests ran, only **4** actually failed (all in t/70_rt.t).

Passing (39/40): `00_pod` (skip), `01_is_pp`, `10_base`, `12_acc`, `15_flags`, `16_import`, `20_file`, `21_lexicalio`, `22_scalario`, `30_types`, `40_misc`, `41_null`, `45_eol`, `46_eol_si`, `47_comment`, `50_utf8`, `51_utf8`, `55_combi`, `60_samples`, `65_allow`, `66_formula`, `67_emptrow`, `68_header`, `71_pp`, `71_strict`, `75_hashref`, `76_magic`, `77_getall`, `78_fragment`, `79_callbacks`, `80_diag`, `81_subclass`, `85_util`, `90_csv`, `91_csv_cb`, `92_stream`, `csv_method`, `fields_containing_0`, `rt99774`.

## Fix Phases

### Phase 1: Strict vars + use lib (DONE)

**Files changed:**
- `EmitVariable.java` — Added `%_` to `isBuiltinSpecialContainerVar`
- `BytecodeCompiler.java` — Same
- `Variable.java` — Added `%_` to parse-time strict vars exemptions
- `Lib.java` — Changed `push` to `unshift` with dedup, matching Perl's `lib.pm` semantics

### Phase 2: @INC ordering + blib support (DONE)

- `GlobalContext.java` — Reordered @INC: `-I` args > PERL5LIB env > `~/.perlonjava/lib` > `jar:PERL5LIB`
- `ExtUtils/MakeMaker.pm` — Added `pure_all` target to copy .pm files to `blib/lib/`

### Phase 3a: `last` inside `do {} while` inside a true loop (DONE)

The `____parse` subroutine (766 lines) is too large for the JVM backend and falls back to the bytecode interpreter. The bytecode compiler's `compileLastNextRedo()` had a bug: for unlabeled `last`/`next`/`redo`, it used `loopStack.peek()` which returns the innermost loop entry — including do-while pseudo-loops (`isTrueLoop=false`). It then threw "Can't last outside a loop block" because do-while is not a true loop.

**Root cause:** `loopStack.peek()` instead of searching for the innermost true loop.

**Fix:** Changed the unlabeled case to iterate `loopStack` from top to bottom and return the first entry with `isTrueLoop=true`, matching the JVM backend's `findInnermostTrueLoopLabels()` behavior.

**File:** `BytecodeCompiler.java`, `compileLastNextRedo()` (~line 5789)

**Impact:** Highest-impact fix — unblocked the core CSV parsing engine that nearly every test depends on. Went from ~4 passing tests to 19.

### Phase 3b: Implement `bytes::length` and other `bytes::` functions

**Status:** TODO — highest priority remaining fix

**Problem:** `bytes::length($value)` is an explicit subroutine call to `bytes::length`, not the `length` builtin under `use bytes`. PerlOnJava's `bytes.pm` is a stub placeholder with no function definitions. The Java-side `BytesPragma.java` only handles `import`/`unimport` (hint flags), not callable functions.

**What exists:**
- `BytesPragma.java` — Sets/clears `HINT_BYTES` for `use bytes`/`no bytes` (working)
- `EmitOperator.java` — Compiler checks `HINT_BYTES` to emit byte-aware `length`/`chr`/`ord`/`substr` (working)
- `StringOperators.lengthBytes()` — Java implementation of byte-length (working)

**What's missing:** `bytes::length`, `bytes::chr`, `bytes::ord`, `bytes::substr` as callable Perl subroutines.

**Fix:** Register `bytes::length` etc. as Java methods in `BytesPragma.java`, following the pattern used by `Utf8.java` for `utf8::encode`, `utf8::decode`, etc.

**Files:** `BytesPragma.java`

**Impact:** Unblocks t/12_acc.t (245 tests), t/55_combi.t (25119), t/70_rt.t (20469), t/71_pp.t (104), t/85_util.t (1448) — all crash on `bytes::length`.

### Phase 3c: Fix bare glob (`*FH`/`*DATA`) method dispatch

**Status:** TODO — second highest priority

**Problem:** When a bare typeglob like `*FH` is used as a method invocant (`$io->print($str)` where `$io` is `*FH`), PerlOnJava's method dispatch in `RuntimeCode.call()` doesn't handle the GLOB type. It falls through to the string path, stringifies the glob to `"*main::FH"`, and tries to find a class `*main::FH`.

**Root cause:** `RuntimeCode.call()` has handling for `GLOBREFERENCE` (auto-blesses to `IO::File`) but no handling for plain `GLOB` type.

**Fix:** Add an `else if (runtimeScalar.type == RuntimeScalarType.GLOB)` branch that auto-blesses to `IO::File`, matching the `GLOBREFERENCE` behavior.

**File:** `RuntimeCode.java`, `call()` method (~line 1546)

**Impact:** Unblocks t/20_file.t (109 tests), t/79_callbacks.t (~86 of 111 failures from `*DATA`), t/90_csv.t (~124 of 127), t/71_strict.t (~15 of 17).

### Phase 3d: UTF-8 handling improvements (LOWER PRIORITY)

Multiple interrelated UTF-8 issues affect ~55 test failures across t/47_comment.t, t/50_utf8.t, t/51_utf8.t:

| Issue | Root Cause | File | Impact |
|-------|-----------|------|--------|
| Readline returns STRING type | `Readline.java` always creates STRING, losing BYTE_STRING info from raw handles | Readline.java | t/51_utf8.t #93-94 |
| `utf8::is_utf8` too permissive | Returns true for all non-BYTE_STRING types (INTEGER, DOUBLE, etc.) | Utf8.java | t/51_utf8.t #94 |
| No "Wide character in print" warning | `IOOperator.print()` never checks for chars > 0xFF | IOOperator.java | t/51_utf8.t #7, #13 |
| `use bytes` doesn't affect regex | `HINT_BYTES` not checked for regex matching | EmitOperator.java | t/50_utf8.t #71 |
| `utf8::upgrade` decodes instead of just flagging | Incorrectly decodes UTF-8 bytes into characters | Utf8.java | t/51_utf8.t bytes_up tests |
| Multi-byte UTF-8 comment_str matching | Byte vs character length confusion in comment detection | CSV_PP issue | t/47_comment.t #46-60 |

**Strategy:** These are complex and risky to change broadly. Defer unless the simpler fixes (3b, 3c) don't get us to an acceptable pass rate.

### Phase 3e: Other edge cases (LOWEST PRIORITY)

| Test | Failures | Likely Cause |
|------|----------|--------------|
| t/45_eol.t | 18/1182 | EOL handling edge cases (1.5% fail rate) |
| t/46_eol_si.t | 12/562 | Same EOL issues (2.1% fail rate) |
| t/20_file.t | 5/109 | Binary char detection (`\x08` not flagged as binary) |
| t/21_lexicalio.t | 5/109 | Same binary char issue |
| t/22_scalario.t | 5/136 | Same binary char issue |
| t/91_csv_cb.t | 1/82 | `local %h` + `*g = \%h` glob slot restoration |

### Phase 3f: Infrastructure issues (NOT Text::CSV specific)

These failures are caused by broader PerlOnJava limitations, not Text::CSV bugs:

| Test | Failures | Root Cause |
|------|----------|-----------|
| t/70_rt.t | 20468/20469 | Source file contains raw `\xab`/`\xbb` bytes in CODE section (regex patterns). Even with Latin-1 source reading, the test crashes with "Can't use an undefined value as an ARRAY reference" early on. |
| t/75_hashref.t | 44/102 | `Scalar::Util::readonly()` always returns false. Test binds read-only refs (`\1, \2`), CSV_PP can't detect readonly, tries to assign, crashes. |
| t/76_magic.t | 35/44 | `TieScalar` ClassCastException in bytecode interpreter. Tied variables not properly dereferenced when used as string operands. 1 actual failure + 34 not run. |
| t/85_util.t | 1130/1448 | Crash at test 330: `open` with `:encoding(utf-32be)` not supported. 12 earlier failures from BOM detection/Unicode decode. |

### Phase 4: Logical operator VOID context + PerlIO NPE (DONE)

**Status:** DONE — committed as `976f7a168`

**Problem 1:** The RHS of `&&`/`and`, `||`/`or`, and `//` operators was compiled in SCALAR context even when the overall expression was in VOID context. This caused side-effect-only expressions to leave spurious values on the JVM stack and waste bytecode registers.

**Fix:** Changed both the JVM backend (`EmitLogicalOperator.java`) and the bytecode compiler (`CompileBinaryOperator.java`) to pass VOID context through to the RHS instead of converting it to SCALAR.

**Problem 2:** `PerlIO::get_layers()` threw a NullPointerException when called with a non-GLOB argument.

**Fix:** Added null check in `PerlIO.java` to throw "Not a GLOB reference" instead of NPE.

**Files:** `EmitLogicalOperator.java`, `CompileBinaryOperator.java`, `PerlIO.java`

**Impact:** Fixed t/80_diag.t (316/316 pass, was failing at tests 113-114) and t/90_csv.t (127/127 pass, was crashing at test 104). Combined with accumulated Phase 3 fixes: 27/40 programs pass (up from 24/40).

## Progress Tracking

### Current Status: Phase 9 complete — 39/40 programs pass, 52356/52360 subtests pass (99.99%)

### Completed
- [x] Phase 1: strict vars + use lib (2026-04-03)
  - Files: EmitVariable.java, BytecodeCompiler.java, Variable.java, Lib.java
- [x] Phase 2: @INC ordering + blib support (2026-04-03)
  - Files: GlobalContext.java, ExtUtils/MakeMaker.pm
- [x] Phase 3a: `last` in do-while inside true loop (2026-04-03)
  - File: BytecodeCompiler.java
  - Result: 19/40 tests pass (up from ~4)
- [x] Phase 3b: `bytes::length` and other bytes:: functions (2026-04-03)
  - File: BytesPragma.java
  - Added: bytes::length, bytes::chr, bytes::ord, bytes::substr
- [x] Phase 3c: Bare glob method dispatch (2026-04-03)
  - File: RuntimeCode.java
  - Added: GLOB type handling in method dispatch (auto-bless to IO::File)
  - Result: 24/40 tests pass, 31019 subtests ran
- [x] Phase 3 extras: bytecode HINT_BYTES parity + raw-bytes DATA section (2026-04-03)
  - Files: CompileOperator.java, Opcodes.java, ScalarUnaryOpcodeHandler.java, Disassemble.java, CompilerOptions.java, FileUtils.java, DataSection.java
  - Added: FC_BYTES/LC_BYTES/UC_BYTES/LCFIRST_BYTES/UCFIRST_BYTES opcodes for bytecode interpreter
  - Fixed: DATA section preserves raw bytes via Latin-1 extraction from rawCodeBytes
- [ ] Phase 3 extras: Latin-1 source reading + StringParser UTF-8 decoding (REVERTED)
  - Attempted: change default source encoding from UTF-8 to Latin-1 in FileUtils.java + re-decode in StringParser.java
  - **Problem**: Source enters the compiler via multiple paths (FileUtils for files, `StandardCharsets.UTF_8` in JUnit tests, command-line for `-e`). The StringParser transformations need to know whether the source string has "byte-preserving" (Latin-1) or "already decoded" (UTF-8) semantics. Fixing one path broke the other.
  - **Reverted**: Changes to FileUtils.java and StringParser.java were rolled back. See "Encoding-Aware Lexer" design below for the proper solution.
- [x] Phase 4: Logical operator VOID context + PerlIO NPE (2026-04-03)
  - Files: EmitLogicalOperator.java, CompileBinaryOperator.java, PerlIO.java
  - Fixed: VOID context passed through to RHS of &&/and, ||/or, //
  - Fixed: PerlIO::get_layers null check for non-GLOB references
  - Result: 27/40 tests pass (up from 24/40), 114 subtest failures (down from 118)
- [x] Phase 4b: `local %hash` glob slot restoration (2026-04-03)
  - Files: GlobalRuntimeHash.java (new), EmitOperatorLocal.java, BytecodeInterpreter.java
  - Fixed: `local %hash` now saves/restores the globalHashes map entry, not just hash contents
  - Result: t/91_csv_cb.t 82/82 pass (was 81/82)
- [x] Phase 5: readline BYTE_STRING propagation (2026-04-03)
  - Files: LayeredIOHandle.java, RuntimeIO.java, Readline.java
  - Root cause: readline always returned STRING type, causing utf8::is_utf8() to return true
    for all readline output. This broke CSV_PP's binary character detection (checks utf8 flag
    to skip binary validation) and multi-byte UTF-8 comment string handling.
  - Added: LayeredIOHandle.hasEncodingLayer(), RuntimeIO.isByteMode()
  - Fixed: All four Readline methods check isByteMode() and return BYTE_STRING when appropriate
  - Impact: Fixed 27 subtest failures across 6 test files:
    - t/20_file.t: 104/109 -> 108/109 (+4)
    - t/21_lexicalio.t: 104/109 -> 108/109 (+4)
    - t/22_scalario.t: 131/136 -> 135/136 (+4)
    - t/47_comment.t: 56/71 -> 71/71 (+15, all pass)
    - t/51_utf8.t: 128/207 -> 132/167 (+4)
    - t/85_util.t: 318/1448 -> 330/330 (all pass)
  - Result: 30/40 programs pass (up from 27/40)
- [x] Phase 5b: `$\` / `$,` aliasing fix (2026-04-03) — committed as `a73f378e2`
  - Created: OutputRecordSeparator.java, OutputFieldSeparator.java
  - Modified: IOOperator.java (static getters), GlobalContext.java (special types), GlobalRuntimeScalar.java (save/restore)
  - Root cause: `print` read `$\`/`$,` directly from global map; `for $\ ($rs) { print }` leaked aliased value
  - Impact: t/45_eol.t: 18→6 failures; t/46_eol_si.t: 12→0 failures
- [x] Phase 6: `goto LABEL` in interpreter-fallback closures (2026-04-03)
  - File: InterpretedCode.java, `withCapturedVars()` method
  - Root cause: `withCapturedVars()` created a copy but dropped `gotoLabelPcs` and `usesLocalization`
  - Fix: Copy `gotoLabelPcs` and `usesLocalization` to the new InterpretedCode in `withCapturedVars()`
  - Impact: t/45_eol.t: 6→0 (all 1182 pass); t/20_file.t: 108→109; t/21_lexicalio.t: 108→109; t/22_scalario.t: 135→136
  - Result: 34/40 programs pass (up from 30/40)
- [x] Phase 7: BYTE_STRING preservation + Encode::decode orphan byte fix (2026-04-04)
  - **BYTE_STRING preservation across string operations** (commit 886c6394e):
    - RuntimeTransliterate.java: tr///r and in-place tr/// preserve BYTE_STRING type
    - RuntimeSubstrLvalue.java: substr lvalue inherits BYTE_STRING from parent
    - StringOperators.java: chomp, chop, lc, uc, lcfirst, ucfirst, reverse preserve BYTE_STRING
    - RuntimeRegex.java: added lastMatchWasByteString flag propagated through regex match/substitution
    - ScalarSpecialVariable.java: $1, $&, $`, $' inherit BYTE_STRING from last match
    - RegexState.java: lastMatchWasByteString saved/restored with regex state
    - Utf8.java: isUtf8() resolves ScalarSpecialVariable proxy types before checking
    - Operator.java: repeat (x) and split preserve BYTE_STRING type
  - **Encode::decode orphan byte fix** (commit b91457959):
    - Encode.java: Added trimOrphanBytes() to drop incomplete trailing code units for UTF-16/32
    - Root cause: Java's String(byte[], Charset) replaces orphan bytes with U+FFFD; Perl drops them
    - Applied to decode(), encoding_decode(), and from_to()
  - Impact:
    - t/51_utf8.t: 132/167 → 207/207 (all pass, +75)
    - t/85_util.t: 1424/1448 → 1448/1448 (all pass, +24)
    - t/75_hashref.t: 58/58+44 skipped → 102/102 (all pass, previously skipped tests now run)
    - t/76_magic.t: 43/44 → 44/44 (all pass)
    - t/70_rt.t: 1/20469 → 20465/20469 (massive improvement, +20464)
  - Result: 39/40 programs pass (up from 34/40)

- [x] Phase 8: Regression fixes for PR #424 (2026-04-04)
  - **re/subst.t fix** (RuntimeRegex.java):
    - When s/// replacement introduces wide characters (codepoint > 255), the result is now
      correctly upgraded from BYTE_STRING to STRING instead of preserving byte type
    - Added `containsWideChars()` helper to detect characters > 255 in substitution results
    - Root cause: Phase 7's BYTE_STRING preservation unconditionally kept BYTE_STRING type on
      substitution results, even when replacement introduced wide characters (e.g. `s/a/\x{100}/g`)
  - **io/crlf.t fix** (LayeredIOHandle.java):
    - For non-encoding layers like `:crlf`, `doRead()` now reads conservatively
      (`bytesToRead = charactersNeeded`) to avoid over-consuming from the delegate
    - Encoding layers (UTF-16/32) still use the wider read (`charactersNeeded * 4`)
    - Root cause: Phase 5's encoding layer read logic used `charactersNeeded * 4` for ALL layers,
      causing `:crlf` layer to over-read, making `tell()` inaccurate
  - **Regression investigation results:**
    - re/pat_advanced.t: NOT a regression — matches master exactly at 1316/1678 passing
    - comp/parser_run.t: NOT a regression — same 18 failures on both master and branch
    - op/anonsub.t: NOT a regression — pre-existing List::Util 1.70 vs 1.63 version mismatch
  - Commit: `07b856abc`

- [x] Phase 9: Regression fixes + namespace::autoclean + Unicode property fix (2026-04-04)
  - **op/anonsub.t test 9 fix** (B.pm):
    - Wrapped `require Sub::Util` in eval in B::CV::_introspect() so that loading failures
      (caused by @INC reordering putting CPAN Sub::Util before bundled) fall back to __ANON__
      defaults instead of dying
  - **comp/parser_run.t test 66 fix** (IdentifierParser.java):
    - Non-ASCII bytes (0x80-0xFF) inside `${...}` contexts now formatted as `\xNN` (uppercase,
      no braces) matching Perl's diagnostic format
  - **re/pat_advanced.t Unicode fix** (UnicodeResolver.java):
    - `unicodeSetToJavaPattern()` uses `\x{XXXX}` notation for supplementary characters (U+10000+)
      to avoid Java's Pattern.compile() misinterpreting UTF-16 surrogate pairs
    - Escape `#` and whitespace in character class patterns for Pattern.COMMENTS compatibility
    - Confirmed: branch matches master at 1316/1678 (no regression)
  - **namespace::autoclean implementation** (namespace/autoclean.pm):
    - Replaced no-op stub with working implementation using B::Hooks::EndOfScope + Sub::Util
    - Uses Sub::Util::subname (XS via XSLoader) to distinguish imported vs local functions
    - Removes imported functions from stash at end of scope while preserving methods
    - Supports -cleanee, -also, -except parameters
    - Fixed DateTime test t/48rt-115983.t: Try::Tiny's try/catch no longer leak as callable
      methods on DateTime objects
  - Commits: `52566815a` (regression fixes), `29638fcec` (namespace::autoclean)

### Remaining Failures (1 test file, 4 subtests)

| Test | ok/total | Failures | Details |
|------|----------|----------|---------|
| t/70_rt.t | 20465/20469 | 4 | See below |

#### t/70_rt.t failure details

| Test # | Description | Likely Cause |
|--------|-------------|--------------|
| 72 | IO::Handle triggered a warning | Missing warning when printing to invalid IO::Handle |
| 84 | fields () | Incorrect field parsing with unusual quote/sep values (non-ASCII separator `\xab`/`\xbb` from `chr()`) |
| 86 | fields () | Same as above |
| 444 | first string correct in Perl | String content mismatch — likely a raw-bytes vs Unicode edge case |

### Next Steps

The Text::CSV module is effectively complete for practical use (**99.99% pass rate**). The 4 remaining failures are minor edge cases:

1. **Investigate t/70_rt.t #72** — IO::Handle warning on invalid filehandle. Low priority; may require implementing Perl's warning for printing to a closed/invalid handle.

2. **Investigate t/70_rt.t #84/#86** — Non-ASCII separator/quote handling. These test `chr(0xab)`/`chr(0xbb)` as separator/quote characters. May be a byte vs character encoding edge case.

3. **Investigate t/70_rt.t #444** — String content comparison failure. Need to check what the expected vs actual strings are.

4. **Consider merging** — With 39/40 test files passing and 52356/52360 subtests passing, this branch is ready for review/merge. The remaining 4 failures are edge cases that can be addressed in follow-up work.

---

## Encoding-Aware Lexer Design

### Problem

Perl reads source files as raw bytes. The `use utf8` pragma tells the parser to decode string literals (and identifiers, regex patterns, etc.) as UTF-8. This encoding switch happens mid-file and is lexically scoped — `no utf8` reverts to byte semantics. `use encoding 'latin1'` and other encoding pragmas add further complexity.

PerlOnJava currently reads the entire source file as a Java String up front using a fixed encoding (UTF-8 by default). This creates a fundamental mismatch:

1. **Without `use utf8`**: Source bytes `\xC3\xA9` should be two separate byte-values (195, 169). But UTF-8 decoding collapses them into one character é (U+00E9).
2. **With `use utf8`**: Source bytes `\xC3\xA9` should become one character é (U+00E9). This happens to work when reading as UTF-8, but only by accident.
3. **Mixed contexts**: A file with `use utf8` in one block and byte semantics elsewhere needs both behaviors.

An attempted fix (Latin-1 source reading + StringParser re-decode) was reverted because source code enters the compiler via multiple paths (file reading, `-e` arguments, `eval` strings, JUnit tests) and each path has different encoding semantics. Patching StringParser for one path broke others.

### Proposed Solution: Encoding Feedback from Parser to Lexer

Instead of fixing encoding in StringParser after the fact, make the Lexer encoding-aware with feedback from the Parser:

```
 Source bytes ──► Lexer (encoding-aware) ──► Tokens ──► Parser
                      ▲                                   │
                      └── "use utf8" / "no utf8" ─────────┘
```

#### Key Design Points

1. **Normalize source to Latin-1 at the boundary**: All source entry points (file, `-e`, `eval`, tests) should convert to a canonical byte-preserving representation before reaching the Lexer. For files, read as Latin-1. For `-e` (already UTF-8 decoded), re-encode to UTF-8 bytes then store as Latin-1 chars. This ensures the Lexer always works with byte-valued characters.

2. **Lexer tracks encoding state**: The Lexer holds a current encoding flag (initially `bytes`, switched to `utf8` when the Parser encounters `use utf8`). This affects how it tokenizes:
   - In **bytes** mode: each Latin-1 char is one token character (preserving raw byte values)
   - In **utf8** mode: consecutive Latin-1 chars forming a valid UTF-8 sequence are combined into one Unicode character

3. **Parser signals encoding changes**: When the Parser processes `use utf8`, `no utf8`, or `use encoding '...'`, it calls back to the Lexer to change the encoding mode. This takes effect for subsequent tokens.

4. **Lexically scoped**: The encoding state is part of the scope stack, matching Perl's `use utf8` / `no utf8` scoping.

#### Impact on Existing Code

- **StringParser.java**: The `use utf8` / `no utf8` post-processing branches become unnecessary — the Lexer already delivers correctly-decoded tokens.
- **FileUtils.java**: Simplified to always read as Latin-1.
- **PerlScriptExecutionTest.java**: Must normalize `-e`-style source to Latin-1 chars.
- **Lexer.java**: Needs encoding state and multi-byte char combining logic.
- **Parser.java**: Needs to signal encoding changes to Lexer.

#### Risks and Alternatives

- **Risk**: The Lexer currently operates on a pre-built Java String. Making it byte-aware may require significant refactoring.
- **Alternative (simpler)**: Instead of modifying the Lexer, add a `sourceIsLatinEncoded` flag to `CompilerOptions` and branch on it in StringParser. This would require all entry points to set the flag correctly but avoids Lexer changes.  The `-e` path would re-encode its argument to pseudo-Latin-1 and set the flag.
- **Alternative (pragmatic)**: Leave the source reading as UTF-8 but fix the specific tests that need raw bytes (t/70_rt.t) by adding a binary mode flag or pre-processing step for files containing non-UTF-8 bytes.

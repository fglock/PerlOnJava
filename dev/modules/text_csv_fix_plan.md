# Text::CSV Fix Plan

## Problem

`./jcpan -j 4 -t Text::CSV` fails. Multiple root causes identified across four phases.

## Architecture

PerlOnJava ships a **bundled Text::CSV** (`src/main/perl/lib/Text/CSV.pm`, 557 lines) that wraps Apache Commons CSV (Java) via `TextCsv.java`. It provides basic CSV functionality but is missing ~40+ methods from the CPAN version.

The CPAN **Text::CSV 2.06** is a thin wrapper that delegates to `Text::CSV_PP` (pure Perl, 3,480 lines of code). It provides full compatibility with Text::CSV_XS including all accessors, error handling, callbacks, types, etc.

When a user installs Text::CSV via `jcpan`, the CPAN version (+ CSV_PP) should override the bundled version. The bundled version remains as a zero-install fallback for users who don't need the full CPAN feature set.

## Current Test Results (after Phase 3a)

**19/40 test programs pass.** 4809 subtests ran, 99 actually failed (rest are "bad plan" from early crashes).

Passing: `01_is_pp`, `10_base`, `15_flags`, `16_import`, `30_types`, `41_null`, `60_samples`, `65_allow`, `66_formula`, `67_emptrow`, `68_header`, `77_getall`, `78_fragment`, `81_subclass`, `92_stream`, `csv_method`, `fields_containing_0`, `rt99774` (+ `00_pod` skipped).

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
| t/55_combi.t | 1/25119 | Single edge case (99.996% pass rate) |
| t/50_utf8.t | 1/93 | `use bytes` doesn't affect regex matching |
| t/80_diag.t | 2/316 | Error diagnostic edge cases |
| t/90_csv.t | 1/127 | Single failure (test 104) |
| t/91_csv_cb.t | 1/82 | `%_` restoration in callbacks |

### Phase 3f: Infrastructure issues (NOT Text::CSV specific)

These failures are caused by broader PerlOnJava limitations, not Text::CSV bugs:

| Test | Failures | Root Cause |
|------|----------|-----------|
| t/70_rt.t | 20468/20469 | Source file contains raw `\xab`/`\xbb` bytes (invalid UTF-8). PerlOnJava reads source as UTF-8, corrupting the regex pattern. DATA section regex never matches. |
| t/75_hashref.t | 44/102 | `Scalar::Util::readonly()` always returns false. Test binds read-only refs (`\1, \2`), CSV_PP can't detect readonly, tries to assign, crashes. |
| t/76_magic.t | 34/44 | `TieScalar` ClassCastException in bytecode interpreter. Tied variables not properly dereferenced when used as string operands. |
| t/85_util.t | 1118/1448 | Crash at test 330: `open` with `:encoding(utf-32be)` not supported. 12 earlier failures from BOM detection/Unicode decode. |

## Current Test Results (after Phase 3c)

**24/40 test programs pass.** 31,019 subtests ran, 118 actually failed.

Passing: `00_pod` (skip), `01_is_pp`, `10_base`, `12_acc`, `15_flags`, `16_import`, `30_types`, `40_misc`, `41_null`, `60_samples`, `65_allow`, `66_formula`, `67_emptrow`, `68_header`, `71_pp`, `71_strict`, `77_getall`, `78_fragment`, `79_callbacks`, `81_subclass`, `92_stream`, `csv_method`, `fields_containing_0`, `rt99774`.

## Progress Tracking

### Current Status: Phase 3c complete

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

### Remaining Work (by impact)
1. **t/70_rt.t** (20469 tests) — Requires source file binary reading support
2. **t/85_util.t** (1448 tests) — Requires utf-32 encoding layer support
3. **t/75_hashref.t** (102 tests) — Requires Scalar::Util::readonly
4. **UTF-8 issues** (t/47_comment, t/50_utf8, t/51_utf8) — Requires Readline BYTE_STRING, is_utf8 fix
5. **Tie handling** (t/76_magic) — Requires TieScalar string coercion fix

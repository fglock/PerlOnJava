# PerlOnJava: Non-Quick-Fix Test Failures Analysis

**Date:** 2026-03-31
**Branch:** `fix/test-pass-rate-quick-wins`
**Overall pass rate:** 92.7% (239,525/258,444 tests), 254 files pass, 274 files fail/incomplete

This document catalogs every significant non-quick-fix test failure, organized by the missing
feature or subsystem required. Each entry includes what is needed to pass the tests and an
estimated difficulty level.

---

## Table of Contents

1. [Taint Tracking](#1-taint-tracking)
2. [Format/Write System](#2-formatwrite-system)
3. [Regex Code Blocks (?{...})](#3-regex-code-blocks)
4. [delete local Construct](#4-delete-local-construct)
5. [\(LIST) Reference Creation](#5-list-reference-creation)
6. [Tied Scalar Code Deref](#6-tied-scalar-code-deref)
7. [caller() Extended Fields](#7-caller-extended-fields)
8. [Attribute System](#8-attribute-system)
9. [%^H Hints Hash (Advanced)](#9-h-hints-hash-advanced)
10. [Special Blocks Lifecycle](#10-special-blocks-lifecycle)
11. [MRO @ISA Invalidation](#11-mro-isa-invalidation)
12. [In-Place Editing ($^I / -i)](#12-in-place-editing-i---i)
13. [-C Unicode Switch](#13--c-unicode-switch)
14. [stat/lstat _ Validation](#14-statlstat-_-validation)
15. [printf Array Flattening](#15-printf-array-flattening)
16. [Duplicate Named Captures](#16-duplicate-named-captures)
17. [Closures (Advanced Edge Cases)](#17-closures-advanced-edge-cases)
18. [comp/parser.t Issues](#18-comparsert-issues)
19. [64-bit Integer Ops](#19-64-bit-integer-ops)
20. [Regex Engine Gaps](#20-regex-engine-gaps)
21. [runperl/fresh_perl Infrastructure](#21-runperlfresh_perl-infrastructure)
22. [DESTROY Destructors](#22-destroy-destructors)
23. [Class Feature (Incomplete)](#23-class-feature-incomplete)
24. [Miscellaneous](#24-miscellaneous)

---

## 1. Taint Tracking

**Status:** SKIP WORKAROUND IMPLEMENTED (2026-04-01)

`Config.pm` now has `taint_support => ''` and `ccflags => '-DSILENT_NO_TAINT_SUPPORT'`, so `op/taint.t` skips gracefully. Full taint tracking remains unimplemented (`RuntimeScalar.isTainted()` always returns `false`).

### Difficulty: Very Hard (full implementation) - skip workaround already applied

---

## 2. Format/Write System

**Tests:** `op/write.t` (0/636), `comp/form_scope.t` (0/14), `uni/write.t` (0/8)
**Blocked tests:** ~658

### What is needed

1. **Full expression evaluation in format argument lines** - `RuntimeFormat.evaluateExpression()` only handles simple global variable access (`$varName` -> `main::varName`). Lexical variables, expressions, method calls, ternary operators all produce `<unsupported_expr>`.
2. **Format declarations inside subroutines** that close over lexical scope
3. **`*GLOB{FORMAT}` access** - extracting the FORMAT slot from a glob
4. **`~~` (fill-until-blank) repeat fields**
5. **`^` (chomp) fields** with `...` truncation
6. **Multi-expression argument lines** like `{ 'i' . 's', "time\n", $good, 'to' }`
7. **Special variables**: `$~` (format name), `$^` (header format), `$-` (lines remaining), `$=` (page length)
8. **Pagination and header support**
9. **`Tie::Scalar` module** may not be loadable (write.t uses it)

### Key files

- `src/main/java/org/perlonjava/runtime/RuntimeFormat.java` (evaluateExpression method)
- `src/main/java/org/perlonjava/operators/IOOperator.java` (formline/write)
- `src/main/java/org/perlonjava/parser/FormatParser.java`

### Difficulty: Hard

---

## 3. Regex Code Blocks (?{...})

**Tests:** `re/reg_eval_scope.t` (0/49), `re/subst.t` (184/281), `re/substT.t` (184/281), `re/subst_wamp.t` (184/281), `re/alpha_assertions.t` (2188/2320), `re/pat_advanced.t` (49/83), `comp/parser.t` (crashes at test ~97)
**Blocked tests:** ~500+

### What is needed

1. **`(?{...})` code blocks** - Execute arbitrary Perl code during regex matching. Currently, `RegexPreprocessor.handleCodeBlock()` only handles simple constants. For anything else, it throws `PerlJavaUnimplementedException`.
2. **`(??{...})` recursive/dynamic regex patterns** - Explicitly throws "not implemented"
3. **`local` within `(?{})` blocks** - proper dynamic scoping with backtracking undo
4. **Lexical variable scoping inside `(?{})` blocks**
5. **`use re 'eval'`** - enabling runtime code evaluation in interpolated patterns
6. **`$^R` (last code block result)** - partially implemented but only for constant-folded blocks

### Key files

- `src/main/java/org/perlonjava/regex/RegexPreprocessor.java` (handleCodeBlock)
- `src/main/java/org/perlonjava/parser/StringSegmentParser.java` (line 723, parseBlock)
- `src/main/java/org/perlonjava/runtime/RuntimeRegex.java`

### Note

Making `(?{UNIMPLEMENTED_CODE_BLOCK})` non-fatal (replace with `(?:)` no-op) would unblock many tests that use `(?{...})` in non-critical parts. This would give ~100 more tests in parser.t alone, plus many in subst.t variants.

### Difficulty: Very Hard (full), Medium (non-fatal workaround)

---

## 4. delete local Construct

**Status:** FULLY IMPLEMENTED (2026-04-01)

`delete local` is fully implemented across all layers: parser (`OperatorParser.parseDelete`), JVM backend (`EmitOperatorDeleteExists`), bytecode compiler (`CompileExistsDelete.visitDeleteLocal`), opcodes (`HASH_DELETE_LOCAL`, `ARRAY_DELETE_LOCAL`, slices), runtime (`RuntimeHash.deleteLocal`, `RuntimeArray.deleteLocal`), and disassembler. Supports all forms: `delete local $hash{key}`, `delete local @hash{@keys}`, `delete local $array[idx]`, `delete local @array[@idx]`, and arrow-deref variants.

### Difficulty: Done

---

## 5. \(LIST) Reference Creation

**Status:** FULLY IMPLEMENTED (2026-04-01)

JVM backend works correctly: `EmitOperator.handleCreateReference` calls `flattenElements()` then `createListReference()`. `RuntimeList.flattenElements()` handles `PerlRange`, `RuntimeArray`, and `RuntimeHash`.

Bytecode interpreter (`InlineOpcodeHandler.executeCreateRef`) calls `createListReference()` directly (without `flattenElements()`) to preserve array/hash identity in declared-ref return values like `\my(\@f, @g)`. The JVM backend's `flattenElements()` is applied at a different compilation level where the distinction is maintained.

### Difficulty: Done

---

## 6. Tied Scalar Code Deref

**Status:** FULLY IMPLEMENTED (2026-04-01)

All three `RuntimeCode.apply()` overloads handle `TIED_SCALAR` by calling `tiedFetch()` before proceeding. `RuntimeScalar.codeDerefNonStrict()`, `globDeref()`, and `globDerefNonStrict()` also handle tied scalars.

### Difficulty: Done

---

## 7. caller() Extended Fields

**Test:** `op/caller.t` (46/112)
**Blocked tests:** ~66

### What is needed

The `CallerInfo` record in `CallerStack.java` only stores `(packageName, filename, line)`. Missing fields:

| Index | Field | Status | Difficulty |
|-------|-------|--------|------------|
| [5] | wantarray | Returns `undef` always | Medium - record call context in CallerStack |
| [6] | evaltext | Returns `undef` always | Medium - capture eval string at compile time |
| [7] | is_require | Returns `undef` always | Easy - add boolean flag |
| [10] | hinthash (%^H) | Returns `undef` always | Medium-Hard - snapshot %^H at compile time |

### Key files

- `src/main/java/org/perlonjava/runtime/CallerStack.java` (CallerInfo record)
- `src/main/java/org/perlonjava/runtime/RuntimeCode.java` (callerWithSub, lines 1631-1779)

### Difficulty: Medium-Hard overall

---

## 8. Attribute System

**Test:** `op/attrs.t` (44/126), `op/attrproto.t` (3/52), `op/attrhand.t` (0/4), `uni/attrs.t` (5/31)
**Blocked tests:** ~160+

### What is needed

1. **`attributes.pm` module** - entirely missing. Need to create `src/main/perl/lib/attributes.pm`
2. **`MODIFY_CODE_ATTRIBUTES` / `MODIFY_SCALAR_ATTRIBUTES` / `FETCH_CODE_ATTRIBUTES` callbacks** - user-definable hooks called when attributes are applied. Parser collects attributes into `RuntimeCode.attributes` but never dispatches these callbacks.
3. **`attributes::get()`** - retrieve attributes from a reference
4. **Variable attributes on `my` declarations** - `my $x : TieLoop = $i`
5. **`-lvalue`/`-const` attribute removal** with `-` prefix

### Key files

- `src/main/java/org/perlonjava/parser/OperatorParser.java` (attribute parsing)
- Need to create: `src/main/perl/lib/attributes.pm`

### Difficulty: Medium-Hard

---

## 9. %^H Hints Hash (Advanced)

**Test:** `comp/hints.t` (23/31)
**Blocked tests:** ~8

### What is needed

Basic `%^H` set/get/scope works (tests 1-8 pass). Missing:
1. **%^H propagation into `eval ""`** - eval should inherit compile-time %^H snapshot
2. **Tied `%^H`** - tie support on the special variable
3. **DESTROY during `%^H` freeing** - requires DESTROY support
4. **CHECK-time %^H state** - proper lifecycle during CHECK phase

### Difficulty: Medium-Hard

---

## 10. Special Blocks Lifecycle

**Test:** `op/blocks.t` (9/26)
**Blocked tests:** ~17

### What is needed

All 26 tests use `fresh_perl_is` (subprocess). Missing:
1. **Full block ordering**: BEGIN -> UNITCHECK -> CHECK -> INIT -> END, including blocks inside `eval`, regex `(?{...})`, nested compilations
2. **`exit` from special blocks**: `BEGIN{exit 0}`, `CHECK{exit 0}` should defer, not exit immediately
3. **`die` from special blocks**: END blocks must still run
4. **Blocks inside `(?{...})`** (depends on regex code blocks)
5. **Prototype/attribute warnings on BEGIN** - "Prototype on BEGIN block ignored"

### Key files

- `src/main/java/org/perlonjava/runtime/SpecialBlock.java`

### Difficulty: Medium-Hard

---

## 11. MRO @ISA Invalidation

**Tests:** `mro/isarev.t` (7/24), `mro/isarev_utf8.t` (7/24), `mro/pkg_gen.t` (3/7), `mro/pkg_gen_utf8.t` (3/7), `mro/method_caching.t` (31/36)
**Blocked tests:** ~50+

### What is needed

`mro::get_isarev` builds the reverse ISA cache lazily but **never invalidates it** when:
- Stash globs are aliased (`*Tike:: = *Dog::`)
- Stashes are deleted (`delete $::{"Dog::"}`)
- Globs are undefined (`undef *glob`)
- `%Package:: = ()` list assignment

Perl's MRO tracks these changes in real-time through magic on `@ISA` and stash entries.

### Key files

- `src/main/java/org/perlonjava/mro/Mro.java` (buildIsaRevCache, lines 277-323)
- `src/main/java/org/perlonjava/runtime/GlobalVariable.java` (stash operations)

### Difficulty: Hard

---

## 12. In-Place Editing ($^I / -i)

**Tests:** `io/argv.t` (6/53), `io/nargv.t` (0/7), `run/switches.t` (67/142), `io/inplace.t` (6/8)
**Blocked tests:** ~120+

### What is needed

`DiamondIO.java` has the in-place editing framework but needs:
1. **Runtime `$^I` lifecycle** - proper `$ARGV`, `$.`, ARGVOUT management as files transition
2. **`local *ARGV` support** - DiamondIO uses static state; needs per-glob state for `local` to save/restore
3. **File permission preservation** during in-place editing (chmod bits)
4. **Error handling** when backup rename fails
5. **Warning on `-i` without file arguments**
6. **`-i` switch** in command-line argument processing

### Key files

- `src/main/java/org/perlonjava/runtime/DiamondIO.java`
- `src/main/java/org/perlonjava/runtime/ArgumentParser.java`

### Difficulty: Hard

---

## 13. -C Unicode Switch

**Test:** `run/switchC.t` (2/15)
**Blocked tests:** ~13

### What is needed

The `-C` flags are **parsed** in `ArgumentParser.java` (lines 469-563) and **stored** in `CompilerOptions.java` (lines 77-84) but **NEVER APPLIED** anywhere in the runtime. The flags (`unicodeStdin`, `unicodeStdout`, `unicodeStderr`, `unicodeInput`, `unicodeOutput`, `unicodeArgs`) need to be applied:
- Call `binmode` with `:encoding(UTF-8)` on STDIN/STDOUT/STDERR during initialization
- Set open pragma defaults for file I/O
- Decode `@ARGV` as UTF-8 when `-CA` is set

### Difficulty: Medium

---

## 14. stat/lstat _ Validation

**Test:** `op/stat.t` (64/111)
**Blocked tests:** ~47

**Status:** Item 1 IMPLEMENTED (2026-04-01) - `lstat _` validation now checks `lastStatWasLstat` in `Stat.java` (lines 111, 129, 249). Items 2-5 remain open.

### Remaining items

1. ~~**`lstat _` validation**~~ - DONE
2. **`lstat *FOO{IO}`** - lstat on IO reference
3. **`stat *DIR{IO}`** - stat on directory handles
4. **`-T _` breaking the stat buffer**
5. **stat on filenames with `\0`**

### Difficulty: Easy-Medium (remaining items)

---

## 15. printf Array Flattening

**Status:** IMPLEMENTED (2026-04-01)

Both `printf` methods in `IOOperator.java` now flatten `RuntimeArray` elements. `printf +()` (empty list) also handled. Remaining io/print.t failures may relate to `$\` null bytes or `%n` format specifier.

### Difficulty: Done (core issue); remaining edge cases Medium

---

## 16. Duplicate Named Captures

**Test:** `re/reg_nc_tie.t` (1/37)
**Blocked tests:** ~36

### What is needed

1. **Duplicate named capture groups** - `CaptureNameEncoder.java` line 15 says "Duplicate capture group names not supported". In Perl, `(?<a>.)(?<a>.)` is valid; `$+{a}` returns the first match. Java's `Matcher.group("a")` returns the LAST match.
2. **`Tie::Hash::NamedCapture` module** - needed for direct `FETCH()` calls and `tied %+`
3. **`%+`/`%-` first-vs-last semantics** - `HashSpecialVariable.get()` must return the correct match

### Key files

- `src/main/java/org/perlonjava/regex/CaptureNameEncoder.java`
- `src/main/java/org/perlonjava/regex/RegexPreprocessor.java` (handleNamedCapture)
- `src/main/java/org/perlonjava/runtime/HashSpecialVariable.java`

### Difficulty: Hard

---

## 17. Closures (Advanced Edge Cases)

**Test:** `op/closure.t` (246/266)
**Blocked tests:** ~20

### What is needed

The first 246 tests (basic closures, combinatorial tests, eval-in-closures) pass. Remaining failures:
1. **Format + closure interaction** - formats closing over lexicals (depends on format system)
2. **`PL_cv_has_eval` cloneability** - anon subs containing `eval '1'` should be cloneable (get different code refs)
3. **DESTROY in closures** - tests that closures close over variables, not entire subs
4. **`my $x if @_`** (conditional my) - stale lexical variable edge cases
5. **Source filter + closure interaction** - requires `Filter::Util::Call` module
6. **Weak reference + closure leak** - requires `builtin::weaken`
7. **Several tests use `fresh_perl_is`** (subprocess tests)

### Difficulty: Medium-Hard (most depend on DESTROY or format system)

---

## 18. comp/parser.t Issues

**Test:** `comp/parser.t` (63/195)
**Blocked tests:** ~132

### What is needed

**Crash at test ~97**: `(?{format...write})` regex code block. Non-constant `(?{...})` throws fatal `PerlJavaUnimplementedException` outside eval.

**Workaround**: Make `(?{UNIMPLEMENTED_CODE_BLOCK})` non-fatal (replace with `(?:)` no-op in `RegexPreprocessor.java`). This alone would unlock ~90+ tests.

**Other failures** (pre-crash):
1. `${}` accepted as valid (should be syntax error) - `Variable.java` lines 679-682
2. `#line` directive handling - ~40 tests for `#line N "file"` directive to set `__FILE__`/`__LINE__`
3. VCS conflict markers (`<<<<<<<`, `=======`, `>>>>>>>`) not detected as errors
4. Identifier length validation in various sigil contexts
5. Sub declaration after compilation error should be ignored

### Key files

- `src/main/java/org/perlonjava/regex/RegexPreprocessor.java`
- `src/main/java/org/perlonjava/parser/Variable.java`
- Lexer/parser for `#line` directives

### Difficulty: Medium (workaround), Hard (full #line support)

---

## 19. 64-bit Integer Ops

**Test:** `op/64bitint.t` (161/435)
**Blocked tests:** ~274

### What is needed

Many 64-bit integer operations produce incorrect results, likely due to:
- Overflow handling differences between Perl's IV/UV types and Java's long
- Unsigned integer semantics (Java has no unsigned long)
- Hex/octal literal parsing at 64-bit boundaries
- Bit shift operations on large values

### Difficulty: Medium-Hard

---

## 20. Regex Engine Gaps

**Tests:** `re/regexp.t` (1803/2210), `re/regexp_noamp.t` (1810/2210), `re/regexp_notrie.t` (1803/2210), `re/regexp_qr.t` (1803/2210), etc., `re/reg_mesg.t` (1676/2509), `re/charset.t` (5462/5552), `re/pat.t` (1064/1298)
**Blocked tests:** ~3000+ across all regex test files

### What is needed

Major regex features still missing or incomplete:
1. **`(*sr:...)` / `(*script_run:...)` / `(*asr:...)` / `(*atomic_script_run:...)`** - script run assertions. Completely missing from `RegexPreprocessor.java`. Would require custom `ScriptRunChecker` using ICU4J's `UScript.getScriptExtensions()`.
2. **Octal escape parsing** - `\345` in patterns parsed as backreference `\3` + `45` instead of octal
3. **`(?{...})` / `(??{...})`** - code blocks (see item #3 above)
4. **Error message format differences** in `re/reg_mesg.t`
5. **Various Unicode property edge cases** in `re/charset.t`

### Difficulty: Hard to Very Hard

---

## 21. runperl/fresh_perl Infrastructure

**Tests affected:** `op/blocks.t`, `op/closure.t` (partial), `run/fresh_perl.t` (63/91), `run/switches.t`, `run/todo.t` (14/26), many others
**Blocked tests:** ~200+ across various files

### What is needed

Many tests use `fresh_perl_is`/`fresh_perl_like` which spawn a PerlOnJava subprocess via `runperl()`. Issues:
1. **Subprocess spawning** sometimes hangs (op/sort.t, op/loopctl.t timeout at 300s)
2. **Exit code handling** may differ
3. **STDERR capture** may be incomplete
4. **-w, -W, -X switches** in subprocess mode

This is cross-cutting - fixing subprocess infrastructure would improve many test files.

### Difficulty: Medium

---

## 22. DESTROY Destructors

**Tests affected:** Many (closure.t, hints.t, tie.t, bless.t, ref.t, etc.)
**Blocked tests:** ~100+ across various files

### What is needed

Object destructors (`DESTROY` method) are never called. This is documented as a known unimplemented feature. Impact:
- Modules using cleanup patterns (Moo's `no Moo`, DEMOLISH)
- Tests that verify DESTROY is called during scope exit
- Tests that verify DESTROY ordering
- Circular reference cleanup
- Tied variable cleanup

### Difficulty: Very Hard (fundamental runtime change)

---

## 23. Class Feature (Incomplete)

**Tests:** `class/accessor.t` (11/25), `class/construct.t` (8/10), `class/gh22169.t` (7/8), `class/inherit.t` (15/18), `class/phasers.t` (3/4), `class/utf8.t` (3/4)
**Blocked tests:** ~30

### What is needed

The Perl `class` feature (added in Perl 5.38) is partially implemented. Missing:
- Some accessor patterns
- Inheritance edge cases
- Phase block interactions within classes
- UTF-8 class name edge cases

### Difficulty: Medium

---

## 24. Miscellaneous

### op/override.t (0/0 crash)
**Missing:** `pop(OverridenPop->foo())` - parser error "pop requires array variable". The parser doesn't allow method call results as arguments to `pop`. **Difficulty: Medium**

### op/dor.t (29/34)
**Failing tests:**
- `getc // ...`, `readlink // ...`, `umask // ...` don't compile with `//`
- Unterminated search pattern error messages differ
**Difficulty: Easy-Medium**

### op/yadayada.t (31/34 incomplete)
**Issue:** Tests 32-34 output `1ok` instead of `ok` (extra `1` from print statement). TAP parser sees malformed output. **Difficulty: Easy** (output buffering issue)

### op/signatures.t (643/908)
**Missing:** Various signature edge cases, `:prototype()` attribute interactions. **Difficulty: Medium**

### op/tr.t (277/318), op/tr_latin1.t (1/2)
**Missing:** Various transliteration edge cases, Latin-1 specific behavior. **Difficulty: Medium**

### op/substr.t (356/400)
**Missing:** 4-arg substr as lvalue, various edge cases. **Difficulty: Medium**

### op/gv.t (198/266)
**Missing:** Glob/stash manipulation edge cases, `*glob{THING}` access for all slot types. **Difficulty: Medium**

### op/eval.t (152/173)
**Missing:** Various eval edge cases, error propagation. **Difficulty: Medium**

### op/tie.t (49/95)
**Missing:** Various tie edge cases, DESTROY interactions. **Difficulty: Medium-Hard**

### comp/use.t (46/87)
**Missing:** `use VERSION` edge cases, `use` with import lists. **Difficulty: Medium**

---

## 25. Test Failures Investigated 2026-04-01 (Status Update)

Items marked FIXED were implemented on the `feature/test-failure-fixes` branch.

### FIXED items

| Test | Before | After | Fix |
|------|--------|-------|-----|
| op/oct.t | 79/81 | **81/81** | Oct/hex overflow detection with double fallback |
| op/my.t | 52/59 | **59/59** | `my() in false conditional` detection in 3 places |
| op/push.t | 29/32 | **32/32** | Error messages + readonly array handling |
| op/unshift.t | 16/19 | **19/19** | Error messages + readonly array handling |
| op/lex_assign.t | 349/353 | **350/353** | Select 4-arg LIST context fix |
| op/while.t | 22/26 | **23/26** | While loop returns false condition value |
| op/closure.t | 0/0 | **246/266** | `my() in false conditional` only on compile-time constants |
| op/for.t | 128/149 | **141/149** | Glob read-only scalar slot replacement |
| op/not.t | 21/24 | **22/24** | `${qr//}` strict deref returns stringified regex |
| op/inc.t | 67/93 | **75/93** | Glob read-only for `++`/`--` (actual globs only) |
| op/reverse.t | 20/26 | **23/26** | Sparse array null preservation in `reverse` |
| op/die.t | 25/26 | **26/26** | `die qr{x}` appends location info |
| op/isa.t | 0/0 | **14/14** | `undef isa "Class"` parse fix in ListParser |
| op/auto.t | 39/47 | **47/47** | Glob copy `++`/`--` via `instanceof RuntimeGlob` |
| op/decl-refs.t | 310/408 | **322/408** | Removed `flattenElements()` from interpreter createRef |
| opbasic/concat.t | 248/254 | **249/254** | Removed REGEX from `scalarDerefNonStrict` |

### op/time.t (71/72) - MOSTLY FIXED
- **Remaining failure:** Test 7 `changes to $ENV{TZ} respected` - Java caches timezone on startup via `ZoneId.systemDefault()`. Changing `$ENV{TZ}` at runtime has no effect.
- **Difficulty:** Hard (would need to call `TimeZone.setDefault()` which has global side effects)

### op/cond.t (6/7) - MOSTLY FIXED
- **Remaining failure:** Test 5 - 20,000-deep nested ternary eval. StackOverflow in parser/emitter recursion.
- **Difficulty:** Hard (requires iterative parser for deeply nested expressions)

### op/not.t (21/24)
- Test 20: `${qr//}` dereference of regex ref returns empty string instead of `(?^:)`
- Tests 21-22: `not 0` / `not 1` return values not read-only (Perl returns immortal `PL_sv_yes`/`PL_sv_no`)
- **Difficulty:** Medium (regex deref), Hard (read-only return values)

### op/range.t (155/162)
- Tests 48, 57: `undef..undef` range behavior, `for -2..undef` edge case
- Tests 138-154: Tied variable fetch/store counting in range operations
- **Difficulty:** Medium

### op/reverse.t (20/26)
- **Not yet investigated in detail**
- **Difficulty:** Unknown

### op/inc.t (75/93) - PARTIALLY IMPROVED
- Score improved from 67/93 to 75/93 via glob copy `instanceof RuntimeGlob` fix
- Remaining failures: Magic variable increment, tied variable FETCH counting, read-only value errors
- **Difficulty:** Medium

### uni/upper.t (6449/6450) - NEARLY PERFECT
- **Remaining failure:** Test 1 `Verify moves YPOGEGRAMMENI` - Greek combining mark reordering during uppercase (`uc("\x{3B1}\x{345}\x{301}")` should move ypogegrammeni after accent)
- **Difficulty:** Hard (special Unicode Greek casing rule, ICU4J doesn't match Perl's reordering)

### op/oct.t (79/81)
- Tests 48, 71: Very large octal/hex numbers should overflow to float with warning. PerlOnJava truncates to long.
- **Difficulty:** Medium (need overflow detection in oct/hex with float fallback)

### op/ord.t (35/38)
- Tests 33-35: Code points beyond Unicode max (0x110000+). Java's UTF-16 can't represent these.
- **Difficulty:** Very Hard (fundamental Java UTF-16 limitation)

### op/my.t (52/59)
- Tests 53-59: `my $x if 0;` should be a compile-time error ("This use of my() in false conditional is no longer allowed")
- **Difficulty:** Medium (detect `my VAR if CONST_FALSE` pattern in parser/optimizer)

### op/while.t (22/26)
- Tests 12-14: Regex match variables (`$\``, `$&`, `$'`) scoping with redo/next/last
- Test 21: While block return value context (last statement should be void)
- **Difficulty:** Medium-Hard

### op/hash.t (489/494)
- All 5 failures relate to DESTROY/weaken (unimplemented features)
- **Difficulty:** Very Hard (depends on DESTROY implementation)

### op/push.t (29/32)
- Tests 5-6: `push` onto hashref/blessed arrayref (experimental feature)
- Test 32: Croak when pushing onto readonly array
- **Difficulty:** Easy-Medium (readonly) to Medium (ref pushing)

### op/unshift.t (18/19)
- Test 19: Croak when unshifting onto readonly array
- **Difficulty:** Easy-Medium

### op/die.t (25/26) - FIXED
- **Fixed:** `die qr{x}` now appends location info like string messages. REGEX type added to string path in WarnDie.java.
- **Score:** 25/26 → **26/26**

### op/sprintf2.t (1652/1655)
- Test 1446: `sprintf %d` overload count
- Test 1555: UTF-8 flag on sprintf format string result
- Test 1655: `sprintf("%.115g", 0.3)` full double precision rendering
- **Difficulty:** Medium-Hard

### op/lex_assign.t (349/353)
- Test 3: Object destruction via reassignment (DESTROY)
- Tests 19, 21: chop/chomp of read-only value error
- Test 107: `select undef,undef,undef,0` ClassCastException
- **Difficulty:** Easy (select fix) to Hard (DESTROY)

### op/vec.t (74/78)
- Tests 31-32: Scalar destruction with lvalue vec, read-only ref error
- Tests 38, 77: UV_MAX lvalue edge cases
- **Difficulty:** Medium

### op/join.t (38/43)
- Tied variable FETCH counting and magic delimiter issues
- **Difficulty:** Medium

### op/delete.t (50/56)
- Tests involve array delete semantics and DESTROY
- **Difficulty:** Medium

---

## Priority Ranking by Impact (Updated 2026-04-01)

### Already Implemented
| Feature | Status |
|---------|--------|
| Taint skip workaround | Done - Config.pm has `taint_support => ''` |
| Tied scalar code deref | Done - all apply() overloads handle TIED_SCALAR |
| delete local | Done - full implementation across all layers |
| \(LIST) reference creation | Done - JVM backend + interpreter (without flattenElements) |
| printf array flattening | Done |
| stat/lstat _ validation (item 1) | Done |
| op/my.t false conditional | Done - 59/59 |
| op/push.t / op/unshift.t | Done - 32/32, 19/19 |
| op/oct.t overflow | Done - 81/81 |
| op/die.t `die qr{x}` | Done - 26/26 |
| op/isa.t `undef isa` | Done - 14/14 |
| op/auto.t glob copy inc/dec | Done - 47/47 |
| op/closure.t false conditional | Done - 246/266 |

### Tier 1: Highest impact remaining
| Feature | Tests blocked | Difficulty |
|---------|--------------|------------|
| Regex code blocks (non-fatal workaround) | 500+ | Medium |
| Format/write system | 658 | Hard |

### Tier 2: High impact (100-500 tests)
| Feature | Tests blocked | Difficulty |
|---------|--------------|------------|
| 64-bit integer ops | 274 | Medium-Hard |
| Attribute system | 160+ | Medium-Hard |
| comp/parser.t (non-fatal (?{}) + #line) | 132 | Medium |
| In-place editing ($^I) | 120+ | Hard |

### Tier 3: Medium impact (30-100 tests)
| Feature | Tests blocked | Difficulty |
|---------|--------------|------------|
| caller() extended fields | 66 | Medium-Hard |
| MRO @ISA invalidation | 50+ | Hard |
| stat/lstat remaining items (2-5) | 47 | Easy-Medium |
| Duplicate named captures | 36 | Hard |
| Class feature completion | 30 | Medium |

### Tier 4: Lower impact
| Feature | Tests blocked | Difficulty |
|---------|--------------|------------|
| Closures (edge cases) | 20 | Medium-Hard |
| Special blocks lifecycle | 17 | Medium-Hard |
| -C unicode switch | 13 | Medium |
| %^H hints (advanced) | 8 | Medium-Hard |

---

## 26. Regressions Investigated 2026-04-01 (Rebase onto master)

After rebasing `feature/test-failure-fixes` onto latest master, the following regressions were reported:

### op/closure.t (246/266 → 0/0, -246) - FIXED

**Root cause:** `StatementResolver.java` line 932 unconditionally threw "This use of my() in false conditional is no longer allowed" for ALL `my VAR if COND` patterns, including runtime conditions like `my $x if @_`. Perl only errors on compile-time false constants (`my $x if 0`).

**Fix:** Added `ConstantFoldingVisitor.getConstantValue(modifierExpression)` check so the error only fires when the condition is a compile-time constant that would prevent the `my` from ever executing. Runtime conditions like `my $x if @_` now correctly fall through to normal handling.

**Files changed:** `StatementResolver.java` (lines 930-949)

### op/decl-refs.t (322/408 → 310/408, -12) - FIXED

**Root cause:** `InlineOpcodeHandler.executeCreateRef` called `flattenElements()` before `createListReference()`. This destroyed array/hash identity when processing declared-ref return values like `\my(\@f, @g)` — the `@g` array was flattened into its (empty) elements, losing the array reference.

**Fix:** Removed `flattenElements()` call from `executeCreateRef`. The JVM backend applies flattening at a higher compilation level where declared-ref vs plain `\(LIST)` can be distinguished. Verified ref.t (226/265) and local.t (137/319) maintained their scores.

**Files changed:** `InlineOpcodeHandler.java` (line 1188)

### op/isa.t (14/14 → 0/0, -14) - FIXED

**Root cause:** `undef isa "BaseClass"` caused a syntax error because `undef` as a named unary operator consumed `isa` as a bareword argument via `parseZeroOrOneList`. When the `isa` feature was enabled, `isa` should have been treated as an infix operator (list terminator), not parsed as an argument.

**Fix:** Added `isa` feature check to `parseZeroOrOneList` in `ListParser.java` — when the next token is `isa` (identifier) and the feature is enabled, treat it as a list terminator so `undef` gets no argument and `isa` becomes the infix operator.

**Files changed:** `ListParser.java` (lines 57-62)

### op/auto.t (47/47 → 39/47, -8) - FIXED

**Root cause:** Tests 40-47 test `++`/`--` on glob copies (`my $x = *foo; $x++`). The branch added `case GLOB -> throw read-only` in all 4 auto-increment/decrement methods, but this was too aggressive — it should only apply to actual `RuntimeGlob` instances (stash entries), not plain `RuntimeScalar` with GLOB type (copies).

**Fix:** Changed all 4 GLOB cases to check `this instanceof RuntimeGlob` before throwing. Glob copies fall through to integer conversion (numifies to 0, so `++` → 1, `--` → -1).

**Files changed:** `RuntimeScalar.java` (preAutoIncrement, postAutoIncrementLarge, preAutoDecrement, postAutoDecrement)

### opbasic/concat.t (249/254 → 248/254, -1) - FIXED

**Root cause:** Adding `case REGEX` to `scalarDerefNonStrict` broke `$$re = $a . $b` in non-strict mode (eval context). Without strict, `$$re` should fall through to `default` which does `GlobalVariable.getGlobalVariable(stringified_name)` — this allows lvalue assignment and consistent read-back. The REGEX case returned a new temp string, losing the assignment.

**Fix:** Removed `case REGEX` from `scalarDerefNonStrict`. The `scalarDeref` (strict) method already had the REGEX case on master, which is correct for strict-mode reads. Non-strict mode uses the global variable lookup path.

**Files changed:** `RuntimeScalar.java` (scalarDerefNonStrict)

### op/for.t (128/149 → 119/119, -9) - PRE-EXISTING (master)

**Root cause:** Test dies at line 659 with "Modification of a read-only value attempted". The test does `for $foo (0, 1) { *foo = "" }` — the loop aliases `$foo` to constant `0`, then `*foo = ""` tries glob replacement which conflicts with the read-only alias. This regression comes from master's `GlobalVariable.java` changes (commit `6a272a1cd` - DBIx::Class support), not from our branch.

**Difficulty:** Medium - glob assignment when loop variable aliases a read-only constant.

### run/switcht.t (9/13 → 0/0, -9) - DELIBERATE (master)

**Root cause:** `Config.pm` now has `taint_support => ''` which causes the test to skip all 13 tests. Previously the key didn't exist, so the skip check short-circuited and 9 tests passed by coincidence (not actually testing taint). This is a deliberate design decision from the `fix/test-pass-rate-quick-wins` PR merged into master.

**No action needed.**

### op/taint.t (4/1065 → 0/0, -4) - DELIBERATE (master)

**Root cause:** Same as run/switcht.t — `taint_support => ''` in Config.pm causes graceful skip of all 1065 tests. The 4 that previously passed were coincidental. This is the intended behavior. The same applies to `perf/taint.t` which also skips gracefully.

**No action needed.**

---

## Recommended Next Steps

1. **(?{...}) non-fatal workaround** (Medium) - change `UNIMPLEMENTED_CODE_BLOCK` from fatal to `(?:)` fallback - 500+ tests
2. **64-bit integer ops** (Medium-Hard) - unsigned semantics, overflow handling
3. **caller() extended fields** (Medium-Hard) - wantarray, evaltext, is_require
4. **Attribute system** (Medium-Hard) - attributes.pm module, MODIFY_*_ATTRIBUTES callbacks
5. **op/for.t glob/read-only regression** (Medium) - from master's GlobalVariable.java changes

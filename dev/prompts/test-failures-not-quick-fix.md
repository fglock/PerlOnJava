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

**Test:** `op/taint.t` (4/1065)
**Blocked tests:** ~1061

### What is needed

Full taint tracking system:
- A taint flag on every `RuntimeScalar` value
- Propagation of taint through string/numeric operations
- Enforcement of taint checks on dangerous ops (`kill`, `exec`, `system`, backticks, `open` with pipes)
- "Insecure dependency in X while running with -T switch" error mechanism
- The `-T` command-line switch activating taint mode

### Current state

`RuntimeScalar.isTainted()` always returns `false`. The `$^X` variable is never marked tainted. The `Config.pm` does not set `taint_support` key, so the test does not skip.

### Quick workaround

Add `taint_support => ''` to `Config.pm` so the test skips entirely.

### Difficulty: Very Hard (full implementation), Trivial (skip workaround)

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

**Test:** `op/local.t` (0/319 - crashes before any output)
**Blocked tests:** ~319

### What is needed

The `delete local` syntax:
```perl
delete local $hash{key};   # Save value, delete, restore on scope exit
delete local $array[idx];
```

Currently:
- The parser (`parseDelete` in `OperatorParser.java` line 549) does NOT check for a `local` keyword after `delete`
- No `DeleteLocalNode` or compilation path exists
- The test crashes at line 164 with "Not implemented: delete with dynamic patterns"

### Implementation plan

1. **Parser**: `parseDelete` must check for `local` keyword and produce a new AST node
2. **Compiler**: Emit save-state, delete, and scope-exit restore
3. **Runtime**: Use existing `dynamicSaveState`/`dynamicRestoreState` mechanism on hash/array elements

### Note

Many tests before line 161 in local.t don't use `delete local`. If the parser didn't crash, ~100+ tests might pass.

### Difficulty: Moderate

---

## 5. \(LIST) Reference Creation

**Test:** `op/ref.t` (97/265)
**Blocked tests:** ~155

### What is needed

`\(LIST)` should return a list of references to each element. E.g., `\(@array)` returns refs to each element; `\($a, $b)` returns `(\$a, \$b)`.

### Root cause

`RuntimeList.flattenElements()` (line 424) does not handle `PerlRange` objects. When `\(1..3)` is evaluated, the PerlRange passes through unflattened, then `createReference()` throws "Can't create reference to list".

### Fix

Add PerlRange handling to `flattenElements()` (~5 lines):
```java
} else if (element instanceof PerlRange range) {
    for (RuntimeScalar scalar : range) {
        result.elements.add(scalar);
    }
}
```

Also need to update `InlineOpcodeHandler.executeCreateRef()` for the bytecode interpreter path.

### Key files

- `src/main/java/org/perlonjava/runtime/RuntimeList.java` (flattenElements, createListReference)
- `src/main/java/org/perlonjava/runtime/PerlRange.java`
- `src/main/java/org/perlonjava/codegen/EmitOperator.java` (handleCreateReference)

### Difficulty: Easy (this is actually a quick fix, ~5 lines)

---

## 6. Tied Scalar Code Deref

**Test:** `op/tie_fetch_count.t` (64/343)
**Blocked tests:** ~279

### What is needed

`RuntimeCode.apply()` does not handle `TIED_SCALAR` type. When `$tied_var` holds a CODE ref and you call `&$tied_var`, the code falls through to "Not a CODE reference" error instead of calling `tiedFetch()` first.

### Fix

Add `TIED_SCALAR` handling in all three `RuntimeCode.apply()` overloads:
```java
if (runtimeScalar.type == RuntimeScalarType.TIED_SCALAR) {
    return apply(runtimeScalar.tiedFetch(), subroutineName, args, callContext);
}
```

Also fix `RuntimeScalar.codeDerefNonStrict()` and `globDeref()` for the same pattern.

### Key files

- `src/main/java/org/perlonjava/runtime/RuntimeCode.java` (three apply overloads)
- `src/main/java/org/perlonjava/runtime/RuntimeScalar.java` (codeDerefNonStrict, globDeref)

### Difficulty: Easy (this is actually a quick fix, ~6 lines across 3 methods)

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

### What is needed

1. **`lstat _` validation** - `Stat.lstatLastHandle()` does NOT validate `lastStatWasLstat`. Should throw "The stat preceding lstat() wasn't an lstat" when the previous call was `stat` not `lstat`. `FileTestOperator.java` already has this check for `-l _` but `Stat.java` doesn't.
2. **`lstat *FOO{IO}`** - lstat on IO reference
3. **`stat *DIR{IO}`** - stat on directory handles
4. **`-T _` breaking the stat buffer**
5. **stat on filenames with `\0`**

### Key files

- `src/main/java/org/perlonjava/operators/Stat.java` (lstatLastHandle)
- `src/main/java/org/perlonjava/operators/FileTestOperator.java`

### Difficulty: Easy-Medium (lstat validation is a 1-line fix; other items are moderate)

---

## 15. printf Array Flattening

**Test:** `io/print.t` (8/24)
**Blocked tests:** ~16

### What is needed

When `printf @array` is called, the RuntimeArray argument is not flattened before extracting the format string. `IOOperator.printf()` calls `list.add(args[i])` which adds the array as-is; then `removeFirst()` expects a RuntimeScalar but gets a RuntimeArray.

Additional issues:
- Null bytes in `$\` (output record separator)
- `%n` format specifier (writes char count via substr)
- `printf +()` (empty list)

### Key files

- `src/main/java/org/perlonjava/operators/IOOperator.java` (printf method, line 2386)

### Difficulty: Medium

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

## 25. Test Failures Investigated 2026-04-01 (Not Quick Fixes)

These were investigated during the `feature/test-failure-fixes` branch work session.

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

### op/inc.t (66/93)
- **Not yet investigated in detail**
- **Difficulty:** Unknown

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

### op/die.t (25/26)
- Test 26: `die qr{x}` TODO test about output termination
- **Difficulty:** Easy

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

## Priority Ranking by Impact

### Tier 1: Highest impact (1000+ tests unlocked)
| Feature | Tests blocked | Difficulty |
|---------|--------------|------------|
| Taint skip workaround | 1061 | Trivial |
| Regex code blocks (non-fatal workaround) | 500+ | Medium |
| Format/write system | 658 | Hard |

### Tier 2: High impact (100-500 tests)
| Feature | Tests blocked | Difficulty |
|---------|--------------|------------|
| delete local | 319 | Moderate |
| Tied scalar code deref | 279 | Easy |
| \(LIST) reference creation | 155 | Easy |
| comp/parser.t (?{} non-fatal) | 132 | Medium |
| In-place editing ($^I) | 120+ | Hard |
| 64-bit integer ops | 274 | Medium-Hard |

### Tier 3: Medium impact (30-100 tests)
| Feature | Tests blocked | Difficulty |
|---------|--------------|------------|
| caller() extended fields | 66 | Medium-Hard |
| Attribute system | 160+ | Medium-Hard |
| MRO @ISA invalidation | 50+ | Hard |
| stat/lstat validation | 47 | Easy-Medium |
| Duplicate named captures | 36 | Hard |
| Class feature completion | 30 | Medium |

### Tier 4: Lower impact but easy
| Feature | Tests blocked | Difficulty |
|---------|--------------|------------|
| -C unicode switch | 13 | Medium |
| printf array flattening | 16 | Medium |
| Closures (edge cases) | 20 | Medium-Hard |
| %^H hints (advanced) | 8 | Medium-Hard |
| Special blocks lifecycle | 17 | Medium-Hard |

---

## Recommended Implementation Order (effort vs. impact)

1. **Taint skip** (Trivial) - 1061 tests
2. **\(LIST) flattenElements fix** (Easy, ~5 lines) - 155 tests
3. **Tied scalar code deref** (Easy, ~6 lines) - 279 tests
4. **(?{...}) non-fatal workaround** (Medium) - 500+ tests
5. **stat/lstat _ validation** (Easy) - ~7 tests + unblocks others
6. **delete local** (Moderate) - 319 tests
7. **printf array flattening** (Medium) - 16 tests
8. **-C switch application** (Medium) - 13 tests
9. **caller() extended fields** (Medium-Hard) - 66 tests
10. **attributes.pm module** (Medium-Hard) - 160+ tests

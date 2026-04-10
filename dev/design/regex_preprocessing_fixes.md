# Regex Preprocessing Fixes

## Overview

This document tracks regex preprocessing issues discovered while running `re/pat.t`, `re/pat_advanced.t`, and related tests with `JPERL_UNIMPLEMENTED=warn`.

## Completed Fixes

### 1. Invalid quantifier brace handling consuming regex metacharacters

**Root cause:** `handleQuantifier()` in `RegexPreprocessor.java` used `s.indexOf('}', start)` to find the closing brace of a potential quantifier, but this search crossed character class boundaries and regex groups. For example, in `{  (?> [^{}]+ | (??{...}) )* }`, the `{` at the start was treated as a potential quantifier, and `indexOf('}')` found the `}` inside the character class `[^{}]`, consuming everything in between (including `(?>` and `[^{`) as literal text.

**Fix:** When `handleQuantifier` determines that braces don't form a valid quantifier (content contains non-numeric characters), it now only escapes the opening `{` as `\{` and returns immediately, letting the main regex loop process subsequent characters normally. Previously it consumed and escaped the entire `{...}` range.

**Files changed:** `RegexPreprocessor.java` — `handleQuantifier()` method

### 2. `\x{...}` hex escape with non-hex characters

**Root cause:** The hex escape handler used `Integer.parseInt(hexStr, 16)` which throws `NumberFormatException` for strings containing non-hex characters (e.g., `\x{9bq}`). Inside character classes, this was caught and re-thrown as a fatal `PerlCompilerException`, killing the test run. Outside character classes, the escape was passed through to Java's regex engine which also rejected it.

**Perl behavior:** `\x{9bq}` extracts the valid hex prefix `9b` (value 0x9B) and ignores the remaining characters. `\x{x9b}` has no valid prefix, so the value is 0. Underscores are allowed (removed by preprocessing) but other non-hex chars terminate the hex number.

**Fix:** All three `\x{...}` handlers now extract the valid hex prefix instead of requiring the entire content to be valid hex:
- `handleRegexCharacterClassEscape()` — inside `[...]` (was the fatal crash)
- `handleEscapeSequences()` — outside `[...]`
- Range endpoint parser — for character class ranges

**Files changed:** `RegexPreprocessorHelper.java`

### 3. Bare `\xNN` with non-hex characters

**Root cause:** Bare `\x` (without braces) was passed through to Java's regex engine, which expects exactly 2 hex digits after `\x`. Patterns like `\xk` or `\x4j` caused `PatternSyntaxException`.

**Perl behavior:** `\x` takes up to 2 hex digits. `\xk` = `\x00` followed by literal `k`. `\x4j` = `\x04` followed by literal `j`.

**Fix:** Added explicit bare `\x` handling that parses up to 2 hex digits and emits `\x{HH}` format when fewer than 2 valid hex digits are found.

**Files changed:** `RegexPreprocessorHelper.java` — `handleEscapeSequences()` method

### 4. NullPointerException when regex fails with JPERL_UNIMPLEMENTED=warn

**Root cause:** When regex compilation fails and gets downgraded to a warning, the catch block in `RuntimeRegex.compile()` set the error pattern but didn't set `regex.patternString`. Downstream code (e.g., `replaceRegex()`) checked `regex.patternString == null` and triggered recompilation with a null pattern, causing NPE in `convertPythonStyleGroups(null).replaceAll(...)`.

**Fix:**
1. Set `regex.patternString` in the catch block when downgrading to warning
2. Added null guard in `preProcessRegex()` to treat null input as empty string

**Files changed:** `RuntimeRegex.java`, `RegexPreprocessor.java`

## Known Remaining Issues

### Test Pass Rates (after all fixes)

| Test | Before fixes | After fixes | Remaining failures |
|------|-------------|-------------|-------------------|
| `re/pat.t` | 428/1298 | **1077**/1298 (all run) | 221 fail |
| `re/pat_advanced.t` | 63/1298 | **1308**/1625 | 317 fail + 53 not reached |
| `re/pat_rt_report.t` | 2397/2515 | **2431**/2515 (ran 2508) | 77 fail + 7 not reached |
| `re/regexp_unicode_prop.t` | — | **1017**/1096 | 79 fail + 14 not reached |
| `re/reg_eval_scope.t` | 6/49 | 7/49 | 42 fail |
| `uni/variables.t` | 66880/66880 | 66880/66880 | 0 |

### Early Termination (crashes blocking remaining tests)

| Test | Crash point | Cause | Tests blocked |
|------|------------|-------|---------------|
| pat.t | **No crash** — all 1298 tests now run | N/A | 0 |
| pat_advanced.t | Line 2308 (test 1625) | `\p{Is_q}` — package-scoped user property (`Some::Is_q`) | 53 tests |
| pat_rt_report.t | Line 1158 (test 2508) | `(?1)` — numbered group recursion not supported | 7 tests |
| regexp_unicode_prop.t | Line 543 (test 1096) | `\pf`/`\Pf` invalid property generates warnings instead of errors | 14 tests |

### Failure Categories

#### A. `\G` anchor (26 failures in pat.t)

The `\G` assertion (match at pos()) has significant issues:
- **Floating `\G`** patterns like `/a+\G/` fail — Java doesn't support `\G` except at pattern start
- **`\G` in loops** (`/\G.../gc` iteration) doesn't maintain position correctly
- Tests: pat.t 455-518

**Difficulty: Medium-High.** Requires custom `\G` tracking in the match engine; Java's `\G` only works at the start of a match attempt.

#### B. `(?{...})` code blocks (36 failures in pat.t, 5 in pat_advanced.t, 5 in pat_rt_report.t)

Regex embedded code blocks are replaced with no-op groups. This breaks:
- **`$^R`** — result of last `(?{...})` (tests 308-310)
- **`pos()` inside `(?{...})`** (tests 470-494)
- **Package/lexical variable access** inside `(?{...})` (tests 522-525)
- **Eval-group runtime checks** — "Eval-group not allowed at runtime" (tests 300-304)

**Difficulty: Very High.** Would require integrating the Perl compiler into the regex engine to execute code at match time.

#### C. `$^N` — last successful capture (20 failures in pat_advanced.t)

`$^N` is not updated after successful group captures. Tests 69-88 all fail.
- Both outside regex and inside `(?{...})` usage fails
- `$^N` is automatically localized — not implemented

**Difficulty: Medium.** Requires tracking the last successfully matched group in the match result.

#### D. `(??{...})` recursive patterns (5 failures in pat.t)

Non-constant recursive patterns are replaced with empty groups. Tests 293-297 (complicated backtracking, recursion with `(??{})`) all fail.

**Difficulty: Very High.** Same as `(?{...})` — requires runtime code execution.

#### E. `(*ACCEPT)`, `(*FAIL)` control verbs (5 failures in pat.t)

Regex control verbs are not supported by Java's regex engine. Tests 357-373 (ACCEPT and CLOSE buffer tests).

**Difficulty: High.** Would require a custom regex engine or post-processing layer.

#### F. `@-` / `@+` / `@{^CAPTURE}` arrays (12 failures in pat.t, 5 in pat_rt_report.t)

The match position arrays have bugs:
- **Wrong values** for capture group positions (tests 381-438)
- **Stale values** not cleared after new match (tests 439-441)
- **Read-only protection** throws wrong exception type: `UnsupportedOperationException` instead of `Modification of a read-only value attempted` (test 449)
- **Interpolation in patterns** — `@-` and `@+` should not be interpolated (pat_rt_report.t 151-154)
- **Undefined values** in `@-`/`@+` after match (pat_rt_report.t 213)

**Difficulty: Medium.** The data is available from Java's `Matcher`; needs more careful mapping to Perl semantics.

#### G. `qr//` stringification and modifiers (4 failures in pat.t)

- `qr/\b\v$/xism` stringifies as `(?^imsx:\b\v$)` but should be `(?^msix:\\b\\v$)` — backslashes not escaped in stringification (test 315)
- **`/u` modifier** not tracked: `use feature 'unicode_strings'` should add `/u` flag (tests 323-327)

**Difficulty: Low-Medium.** Stringification fix is straightforward; `/u` modifier tracking needs scope awareness.

#### H. `\N{name}` charnames (25 failures in pat_advanced.t)

Named character escapes have extensive issues:
- **Empty `\N{}`** not handled correctly (tests 794-809)
- **`\N{PLUS SIGN}`** — named characters not expanded in regex (tests 831-833)
- **`\N{U+0041}`** in character class — `[\N{SPACE}\N{U+0041}]` fails (test 836)
- **Charname validation** — leading digit, comma, latin1 symbol errors not produced (tests 821-828)
- **Charname caching** with `$1` — not implemented (tests 798-801)
- **Cedilla/NO-BREAK SPACE** in names — error handling missing (tests 816-819)

**Difficulty: Medium-High.** `\N{U+XXXX}` is partially implemented; full charnames support needs the `charnames` module.

#### I. Useless `(?c)` / `(?g)` / `(?o)` warnings (13 failures in pat_advanced.t)

Perl warns about useless regex modifiers (`/c`, `/g`, `/o` are match-operator flags, not regex flags). PerlOnJava silently ignores them without producing warnings.

**Difficulty: Low.** Add warning emission in the regex flag parser.

#### J. Bare `\x` hex escape edge cases (5 failures in pat_advanced.t)

Our fix handles the crash but the test strings don't match correctly:
- `\x4j` produces `\004j` but regex `[\x4j]{2}` doesn't match it (test 101)
- `\xk` produces `\000k` but regex `[\xk]{2}` doesn't match it (test 102)
- `\xx`, `\xxa`, `\x9_b` — regex character class expansion doesn't match the test string (tests 103-105)

The issue is that the test string and the regex pattern both use `\x` escapes, but the regex preprocessor and the string processor handle them differently. The test expects both to produce the same character.

**Difficulty: Low-Medium.** The regex-side `\x` handling needs to produce character classes that match what the string-side produces.

#### K. Conditional `(?(1)...)` with `$` anchor — Bug 41010 (48 failures in pat_rt_report.t)

The largest single failure category. Patterns like `/([ ]*$)(?(1))/` don't match correctly. This is a systematic issue with conditionals referencing a group that ends with `$` anchor.

**Difficulty: Medium.** Likely a subtle difference in how Java handles the interaction between `$` anchor in a group and conditional backreference.

#### L. `$REGMARK` / `${^PREMATCH}` etc. (6 failures in pat_rt_report.t)

`$REGMARK` (set by `(*MARK:name)`) is not implemented. Tests 2458-2463.

**Difficulty: High.** Requires `(*MARK)` verb support.

#### M. `(?1)` numbered group recursion / `(?&name)` named recursion (pat_advanced.t, pat_rt_report.t)

`(?1)` and `(?&name)` syntax for recursing into capture groups is not recognized. Now downgradable with `JPERL_UNIMPLEMENTED=warn` (no longer crashes tests), but the patterns silently fail to match.

**Difficulty: Very High.** Java's regex engine has no recursion support. Would need a custom engine or PCRE/JNI bridge.

#### N. `\p{isAlpha}` POSIX-style Unicode property (crash in pat.t)

The POSIX-style Unicode property syntax `\p{isAlpha}`, `\p{isSpace}` is not recognized. This causes the fatal error that stops pat.t at line 1247, blocking 666 remaining tests.

**Difficulty: Low-Medium.** Map POSIX-style aliases (`isAlpha` → `Alpha`, `isSpace` → `Space`, etc.) in the Unicode property handler.

#### O. Empty clause in alternation (3 failures in pat.t)

Empty alternatives in patterns like `/(|a)/` or the "0 match in alternation" test don't work correctly.

**Difficulty: Low-Medium.** Likely a regex preprocessing issue.

#### P. Miscellaneous (small counts)

| Issue | Tests | Difficulty |
|-------|-------|-----------|
| Look around edge cases | pat.t 332-333 | Medium |
| REG_INFTY (quantifier limit) | pat.t 250 | Low |
| POSIX class error message format | pat.t 348 | Low |
| Lookbehind limit (Java) | pat.t 252 | Hard (engine limit) |
| Empty pattern pmop flags | pat_rt_report.t 44 | Medium |
| Nested split | pat_rt_report.t 85 | Medium |
| Ill-formed UTF-8 in class | pat_rt_report.t 140 | Medium |
| Pattern in loop (prev success) | pat_rt_report.t 2469-2470 | Medium |
| Long string patterns | pat_advanced.t 805-813 | Medium |
| `/d` to `/u` modifier change | pat_advanced.t 807-808 | Low-Medium |

#### Q. Package-scoped user-defined Unicode properties (crash in pat_advanced.t)

`\p{Is_q}` defined in package `Some` as `Some::Is_q` is not found because user-defined property lookup only checks `main::` package. Perl uses the current package when resolving `\p{...}` names. This crashes pat_advanced.t at line 2308 (test 1625), blocking 53 tests.

**Difficulty: Medium.** Need to pass the current package context to the regex preprocessor and try the current package before falling back to `main::`.

#### R. Invalid single-char `\pX`/`\PX` properties (crash in regexp_unicode_prop.t)

Invalid single-character properties like `\pf`, `\Pq` are passed through to Java's regex engine which throws `PatternSyntaxException`. This is caught and wrapped as `PerlJavaUnimplementedException`, which under `JPERL_UNIMPLEMENTED=warn` generates warnings instead of proper errors. Test 1096 in regexp_unicode_prop.t expects 0 warnings but gets 8 (from `\pf`, `\Pf`, `\pq`, `\Pq`), then crashes.

**Fix approach:** Validate single-char properties in the preprocessor (only `\pL`, `\pM`, `\pN`, etc. are valid — single Unicode general category letters). Invalid ones should throw `PerlCompilerException` (not `PerlJavaUnimplementedException`).

**Difficulty: Low.** Add validation for single-char `\p`/`\P` properties in `RegexPreprocessorHelper`.

#### S. `/i` flag not passed to user-defined property subs (regexp_unicode_prop.t)

Perl calls user-defined property subs with `$caseless=1` when the `/i` flag is active, allowing subs to return a wider character set for case-insensitive matching. PerlOnJava always calls the sub with an empty argument list. This causes 2 test failures in regexp_unicode_prop.t (tests 1061, 1077) and several in pat_advanced.t.

**Fix approach:** Pass the `/i` flag through the regex preprocessor to `tryUserDefinedProperty`, which then passes `1` as the first argument to the property sub.

**Difficulty: Medium.** Requires threading the case-insensitive flag through several method calls in the regex preprocessing pipeline.

### Priority Recommendations

**Quick wins (Low difficulty, high impact):**
1. ~~**`\p{isAlpha}` aliases** — unblocks 666 pat.t tests (category N)~~ **DONE** — pat.t now runs all 1298 tests
2. **Invalid `\pX` single-char properties** — unblocks 14 regexp_unicode_prop.t tests (category R)
3. **Useless `(?c)`/`(?g)`/`(?o)` warnings** — fixes 13 pat_advanced.t tests (category I)
4. **POSIX class error message** — fix message format (category P)
5. **REG_INFTY error** — add quantifier limit check (category P)

**Medium effort, significant impact:**
6. **Package-scoped user properties** — unblocks 53 pat_advanced.t tests (category Q)
7. **`/i` caseless flag for user properties** — fixes ~4 tests (category S)
8. **`(?(1)...)` with `$` anchor** — fixes 48 pat_rt_report.t tests (category K)
9. **`@-`/`@+` position arrays** — fixes 17 tests across files (category F)
10. **`$^N` last capture** — fixes 20 pat_advanced.t tests (category C)
11. **Bare `\x` edge cases** — fixes 5 pat_advanced.t tests (category J)
12. **`\N{name}` charnames** — fixes 25 pat_advanced.t tests (category H)

**Hard / architectural (major work):**
13. **`\G` anchor** — 26 pat.t tests (category A)
14. **`(?{...})` code blocks** — 46 tests total (category B)
15. **`(?1)` recursion / `(?&name)` / `(*ACCEPT)` / `(*MARK)`** — engine limitations (categories E, L, M)

## Progress Tracking

### Current Status: Major user-defined property and regex cache fixes (2026-04-10)

### Completed
- [x] Fix 1: handleQuantifier brace consumption (2026-04-10)
- [x] Fix 2: \x{...} hex escape with non-hex chars (2026-04-10)
- [x] Fix 3: Bare \xNN with non-hex chars (2026-04-10)
- [x] Fix 4: NPE on failed regex with JPERL_UNIMPLEMENTED=warn (2026-04-10)
- [x] Failure analysis and categorization (2026-04-10)
- [x] Fix 5: \p{isAlpha} case-insensitive Is prefix, add Space/Alnum/Punct aliases (2026-04-10)
- [x] Fix 6: \p{Property=Value} syntax (2026-04-10)
- [x] Fix 7: Named capture groups with underscores — U95 encoding (2026-04-10)
- [x] Fix 8: User-defined property resolution — refactor resolvePropertyReference to return UnicodeSet (2026-04-10)
  - Properties using +utf8:: references (e.g., +utf8::Uppercase, &utf8::ASCII) were failing because
    the old code returned Java regex patterns that ICU4J's UnicodeSet couldn't parse
  - Created resolvePropertyReferenceAsSet() and resolveStandardPropertyAsSet() methods
- [x] Fix 9: Regex cache preventing deferred recompilation (2026-04-10)
  - ensureCompiledForRuntime() now evicts stale cache entries before recompiling
- [x] Fix 10: Cache user-defined property sub results (2026-04-10)
  - Matches Perl behavior of calling each property sub only once
  - Fixes "Called twice" errors from subs with `state` variables
- [x] Fix 11: Titlecase/TitlecaseLetter/Lt property aliases (2026-04-10)
- [x] Fix 12: (?&name) named group recursion downgraded to regexUnimplemented (2026-04-10)
- [x] Fix 13: (?digit) numbered recursion downgraded to regexUnimplemented (2026-04-10)

### Files Modified
- `src/main/java/org/perlonjava/runtime/regex/RegexPreprocessor.java`
- `src/main/java/org/perlonjava/runtime/regex/RegexPreprocessorHelper.java`
- `src/main/java/org/perlonjava/runtime/regex/RuntimeRegex.java`
- `src/main/java/org/perlonjava/runtime/regex/UnicodeResolver.java`
- `src/main/java/org/perlonjava/runtime/regex/CaptureNameEncoder.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/HashSpecialVariable.java`

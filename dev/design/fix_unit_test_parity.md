# Fix Unit Test Parity with System Perl

## Problem

Three unit test files in `src/test/resources/unit/` produce different results under jperl vs system Perl (v5.42). The goal is to fix jperl to match system Perl behavior, then update the tests accordingly.

## Failing Tests Summary

| Test File | Failing Tests | Root Cause |
|-----------|--------------|------------|
| `string_interpolation.t` | Test 4: `$\` interpolation | `"$\\"` in jperl produces only the value of `$\`, but system Perl produces `value_of($\)` + literal `\` |
| `sysread_syswrite.t` | Test 3: sysread from in-memory handle | jperl returns bytes read; system Perl returns `undef` |
| `ipc_open3.t` | Tests 3, 5, 7: stderr capture with false `$err` | jperl creates a separate stderr handle; system Perl merges stderr into stdout when `$err` is false (`""`) |

## Detailed Analysis

### 1. `$\` interpolation in double-quoted strings

**Observed behavior:**
```
# System Perl:
perl -e '$\ = "ABC"; print "[$\\]";'   # → [ABC\]ABC
# jperl:
./jperl -e '$\ = "ABC"; print "[$\\]";'  # → [ABC]ABC
```

In `"$\\"`, system Perl parses this as: interpolate `$\` + literal backslash (from `\\`). jperl only interpolates `$\` and loses the trailing `\`.

The issue is in how jperl's string interpolation scanner handles the `\\` escape sequence after recognizing the `$\` variable. After consuming `$` + `\` for the variable name, the second `\` should still be processed as part of the string (forming `\\` → `\` with the next char... but the next char is `"` which ends the string). Actually, the correct parse is: `$\` consumes only one `\`, then `\` + `"` → but that would be an escaped quote...

System Perl must be parsing `"$\\"` as: `$\` (variable, consumes one `\`) then `\"` → no, because the string would be unterminated.

**More likely**: system Perl parses `"$\\"` by recognizing `$\` as the variable AND recognizing `\\` as an escape producing `\`, with the variable consuming only the `$` + first `\`, and the escape consuming `\` + `\`. This means the variable name `$\` and the escape `\\` share the middle `\` character... which seems unlikely.

**Investigation needed:** Write additional unit tests to clarify exact parsing behavior for `$\` in various DQ string contexts.

### 2. sysread on in-memory file handles

System Perl's `sysread()` does not work on in-memory file handles (opened via `open(my $fh, '<', \$scalar)`). It returns `undef`. jperl currently supports this, returning actual bytes read.

**Fix:** jperl's sysread should return `undef` (and warn) when the filehandle is an in-memory handle, matching system Perl.

### 3. IPC::Open3 with false `$err` argument

Per IPC::Open3 documentation: "If CHLD_ERR is false, or the same file descriptor as CHLD_OUT, then STDOUT and STDERR of the child are on the same filehandle."

When `$err = ""` (which is false), system Perl merges stderr with stdout. jperl incorrectly creates a separate stderr handle.

**Fix:** jperl's IPC::Open3 implementation should check if the `$err` argument is false and merge stderr into stdout accordingly.

## Plan

For each issue:
1. Fix jperl to match system Perl behavior (jperl will then fail the same tests system Perl fails)
2. Fix the tests so they pass on both system Perl and jperl

Only when uncertain about system Perl behavior, add new unit tests to clarify before fixing jperl.

### Issue 1: `$\` interpolation in DQ strings

**Fix jperl:** In the string interpolation scanner, `$\` greedily captures the `\` as part of the variable name. After `$\` is consumed, the next characters are processed normally as string content (not as escape sequences starting with the consumed `\`). So `"$\\"` = value_of(`$\`) + literal `\` (from the remaining `\\` → escaped backslash). jperl currently drops the trailing `\`.

Key system Perl behavior (confirmed by exploratory tests):
- `"$\\"` with `$\ = "SEP"` → `SEP\`
- `"$\n"` → value_of(`$\`) + literal `n` (NOT newline — the `\` was consumed by `$\`)
- `"$\t"` → value_of(`$\`) + literal `t` (NOT tab)

**Then fix tests:** Update `string_interpolation.t` test 4 expected value.

### Issue 2: sysread on in-memory file handles

**Fix jperl:** `sysread()` on in-memory file handles (opened via `open($fh, '<', \$scalar)`) should return `undef`, matching system Perl.

**Then fix tests:** Update `sysread_syswrite.t` test 3 expected value.

### Issue 3: IPC::Open3 with false `$err` argument

**Fix jperl:** When the `$err` argument to `open3()` is false (`""`, `0`, `undef`), stderr should be merged into stdout, not captured separately. This matches system Perl and the IPC::Open3 documentation.

**Then fix tests:** Update `ipc_open3.t` tests 3, 5, 7 expected values.

### Final verification

- Run `make` to ensure no regressions
- Run `prove src/test/resources/unit` to confirm all tests pass

## Progress Tracking

### Current Status: Complete (2026-04-09)

PR: https://github.com/fglock/PerlOnJava/pull/476

### Completed Phases
- [x] Analysis: identified 3 issues via diff of perl vs jperl output
- [x] Exploratory tests: confirmed `$\` parsing behavior in system Perl
- [x] Issue 1: Fixed `$\` interpolation in `StringDoubleQuoted.java` — removed `\` from non-interpolating chars
- [x] Issue 1: Updated `string_interpolation.t` test 4
- [x] Issue 2: Fixed sysread in `IOOperator.java` — return undef for in-memory handles
- [x] Issue 2: Updated `sysread_syswrite.t` test 3
- [x] Issue 3: Fixed `IPCOpen3.java` `isUsableHandle()` — check truthiness not definedness
- [x] Issue 3: Updated `ipc_open3.t` tests 3, 5, 7
- [x] `make` passes, `prove src/test/resources/unit` all tests successful

### Files Modified
- `src/main/java/org/perlonjava/frontend/parser/StringDoubleQuoted.java`
- `src/main/java/org/perlonjava/runtime/operators/IOOperator.java`
- `src/main/java/org/perlonjava/runtime/perlmodule/IPCOpen3.java`
- `src/test/resources/unit/string_interpolation.t`
- `src/test/resources/unit/sysread_syswrite.t`
- `src/test/resources/unit/ipc_open3.t`

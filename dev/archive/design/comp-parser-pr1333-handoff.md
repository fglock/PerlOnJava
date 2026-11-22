# `comp/parser.t` PR #1333 handoff

Date: 2026-09-10  
Branch: `fix/issue-1269-core-suite-write`  
PR: #1333

## Current state

PR #1333 previously reduced the `comp/parser.t` regression from 195 to 9
failures. The last focused run was:

```sh
cd perl5_t/t && timeout 300 ../../jperl comp/parser.t
```

It reported 9 failing assertions (the test program itself exits zero). The
latest full validation, recorded in
`/tmp/make-parser-interpolation-expression-rebase.log`, completed successfully
(`BUILD SUCCESSFUL`, exit 0).

The current worktree contains uncommitted investigation changes. Do not use
stash, reset, restore, or clean. Preserve them with the repository pre-flight
procedure before switching branches or rebasing.

## Remaining failures

| Assertions | Scenario | Actual result | Expected result |
| --- | --- | --- | --- |
| 153–156 | `#line` directive with tab or many spaces and no filename | `KASHPRITZA`, line 1 | `parser.t`, lines 17 and 19 |
| 183–186 | `caller` inside two interpolated heredocs | lines 1/4 in a prior `#line` filename | lines 537/538/540/541 |
| 189 | `caller` inside multiline `qq` interpolation | line 1 | line 25 |

The relevant core-test block is near `perl5_t/t/comp/parser.t:720`.

## Changes currently in the worktree

| File | Change | Evidence |
| --- | --- | --- |
| `ErrorMessageUtil.java` | Parse `#line` directives more strictly: require a separator after the number, retain an unterminated quote through the physical newline, and reject malformed bare/glued filenames. | Fixed core assertions 167–172. |
| `Whitespace.java` | Mirror that directive validation while the parser consumes source. | Fixed the same directive/caller cases. |
| `CoreOperatorResolver.java` | Resolve direct `__LINE__` and `__FILE__` from the indexed, directive-aware source mapping. | Covered by the focused regression test. |
| `ByteCodeSourceMapper.java` | Refresh the file/line coordinate of existing parse-time mappings while preserving package/subroutine information. | No demonstrated effect on the final 9 failures; reassess. |
| `ParseHeredoc.java` | Give interpolated heredocs a directive-aware logical source line. | No demonstrated effect on the final 9 failures. |
| `StringSegmentParser.java` | Rebase nested interpolation AST token indices recursively. | No demonstrated effect on the final 9 failures; likely insufficient or on the wrong mapping path. |
| `src/test/resources/unit/line_directive_core_operators.t` | New focused, permanent regression coverage (18 assertions) for malformed `#line` directives and core operators/caller mapping. | Validated with system Perl before implementation; JVM coverage passed before the latest unproven interpolation experiments. |

The first three rows are the confirmed fix. The last three Java changes are
active experiments and should be retained only if the next investigation proves
their use. If removing them, do so by an explicit patch after preserving the
worktree; never use `git restore` on this dirty checkout.

## Mapping architecture and likely root cause

Nested interpolation is parsed with a fresh token stream whose indices begin at
zero. Its runtime debug metadata is then interpreted through the outer source
map, so line-number token indices such as 0 or 4 resolve to the outer file's
earliest/previous mapping. This explains both the inherited `KASHPRITZA`
filename and physical lines 1/4.

The attempted AST index rebasing in `StringSegmentParser` did not alter the
observed output. That suggests the debug line emitted at runtime is either not
using the rebased node index or is being resolved through another source-map
path. A robust fix will likely carry an explicit logical source coordinate
(file and line), rather than reinterpret an inner token index as an outer one.

Useful code paths:

- `StringDoubleQuoted.java` creates the nested parser and sets
  `parser.baseLineNumber` from `rawStr.sourceLine`.
- `StringSegmentParser.parseVariableInterpolation` constructs nested
  interpolation expressions.
- `ParseHeredoc.interpolateString` creates the `ParsedString` source location.
- `EmitSubroutine.java` calls
  `ByteCodeSourceMapper.setDebugInfoLineNumber` using
  `callerLineCallSiteIndex`.
- `EmitBlock.java`, `ParseBlock.java`, and `StatementCopline.java` propagate
  statement start/call-site indices.
- `ByteCodeSourceMapper.parseStackTraceElement` resolves the JVM line number
  with a `TreeMap.floorEntry` source mapping.

## Recommended continuation

1. Snapshot the dirty worktree before any checkout, rebase, or experiment.
2. Instrument or trace the exact `tokenIndex` supplied to
   `setDebugInfoLineNumber` for assertion 189 and one heredoc assertion. Trace
   its node/statement origin as well as the matching `floorEntry` result.
3. Identify the emission site for nested interpolation and add a source-location
   override that survives from the nested parser to bytecode debug metadata.
   Do not rely solely on `baseLineNumber`: it currently does not correct the
   emitted caller coordinate.
4. First make assertion 189 report `parser.t:25`; then verify the heredoc pairs
   report 537/538 and 540/541. The no-filename directive failures may share the
   `floorEntry` issue, so rerun all of `comp/parser.t` after each mapping change.
5. Keep and run `line_directive_core_operators.t` on system Perl and both JVM
   and interpreter backends for any new regression coverage. Add a permanent
   focused test for nested interpolation source coordinates before declaring the
   fix complete.
6. Run `make` from an immutable checkout before pushing. Wrap every `jperl`
   invocation with `timeout` and capture all test output to a file.

## Existing committed PR work

The branch already contains these pushed commits:

- `546fdccaf fix(runtime): preserve Unicode regex captures in interpolation`
- `e38f2634e fix(parser): reject semicolons in unprototyped calls`
- `a070695a7 fix(parser): reject aggregate regex mutations`
- `513239777 fix(parser): retain malformed braced interpolation context`

The source-map experiments described above are not committed or pushed.

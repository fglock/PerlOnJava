# write.t completion handoff

## Objective and status — 2026-09-14

Resolve **all** unexpected `not ok` results in `perl5_t/t/op/write.t`, account
for the entire 636-test plan, validate both execution backends, and push the
completed fixes to a feature-branch PR. Prepare the local directory's actual
development JAR for UAT as well; publishing source alone is insufficient.

Status: unfinished debugging, not a validated fix. This handoff does not claim
that the current source, development JAR, or PR passes `write.t`.

The user reported that UAT 1368 showed the same result as 1366:
`Blocked: 37 tests (599/636 ran)`. That is the reported UAT baseline, not a
count of explicit failures. Earlier smaller counts mixed different metrics
and local runs. Do not describe a lower `not ok` count as full-suite success.

## Checkout and publication state

- Local branch: `wip/write-t-implementation-20260914-203654`.
- HEAD: `4d7617348` — `fix: paginate write format output`.
- Parent: `daa698e01` — `fix: execute commented braced format arguments`.
- Last-known publication target: PR #1368, branch `fix/write-t`, last pushed
  commit `daa698e013336d1433ae7a91aeaa4474b46929f2`. Recheck GitHub state before
  updating it; this handoff does not reverify its current remote status.
- The working tree was clean after `4d7617348`. The implementation commits
  after the original snapshot are `d4f1c7983`, `055e7c712`, `4cf04c5da`,
  `8910a0419`, and `4d7617348`; inspect their combined diff before rebasing
  or moving the work to the PR branch.
- Earlier backups are `/tmp/wip-unstaged-20260914-185741.patch`,
  `/tmp/wip-staged-20260914-185741.patch`, and
  `/tmp/wip-status-20260914-185741.txt`. Temporary files may not survive cleanup.

On takeover, read [AGENTS.md](../../AGENTS.md), inspect status and diffs, and
follow its dirty-tree patch-backup and WIP-commit preflight before mutations.
Preserve all existing changes. Never stash, discard, or overwrite them.

## Verified local failure census

The latest direct JVM run `/tmp/write-core-pagination-page-number-jvm.log`
declares `1..636` and ends with `EXIT: 0`, but is **not passing TAP**:

- 612 numbered TAP records are emitted. Tests 604 and 608–610 now pass, but
  the child output mismatch still prevents records 613–636 from being reached.
- The first page-format implementation exposed later failures; do not report
  the old 606-record result or a lower explicit count as completion.
- Test 582 (`nested formats`) now reports `ok` in this local log. This is
  limited evidence for the experiment, not proof of correct general semantics
  or proof that the UAT build contains it.

| Explicit failing tests | Diagnostic / investigation area |
| --- | --- |
| 69, 79 | Fixed by `8910a0419`: `utf8::upgrade` now preserves tied scalar magic; JVM and interpreter core runs pass these records |
| 473 | RT #130703; inspect byte/character handling |
| 477 | Fixed by `4d7617348`: pending `$^A` records now participate in top-format pagination |
| 583 | `formats with compilation errors are not created` |
| 598, 599 | Core line 2091 and `^ format with real glob` |
| 605, 606, 607, 611, 612 | Child page/top/footer sequence at core line 2157; tests 604 and 608–610 now pass, but the child first emits blank/footer records instead of ENTRY records |

The areas above are investigation leads, not established root causes. Read
the complete surrounding diagnostics and source. Rerun with the official
runner to compare like-for-like with UAT; do not infer its classification
from this direct log. Use `LC_ALL=C` when counting this log because some
diagnostics contain invalid multibyte data.

## Current implementation experiments — review before keeping

Paths below are relative to `src/main/java/org/perlonjava/`:

1. `frontend/parser/FormatParser.java`: treats multiline braced argument
   source as an argument rather than mistaking a nested picture for the outer
   picture. Argument parsing still has exception-to-string fallback behavior
   and can stop before validating the complete statement sequence. This is
   relevant to test 583.
2. `runtime/runtimetypes/RuntimeFormat.java`: evaluates nonempty argument
   source in list context with captured lexical cells, wrapping braced source
   in `do`. The current `hoistNestedFormatDeclarations` uses a regex to move
   the first nested declaration before the first `write` line. This is an
   **ad-hoc experiment**, not a general compile-time declaration solution:
   review quoted text, comments, multiple declarations, scope and control flow.
3. `RuntimeFormat.replaceDefinition` and
   `backend/bytecode/BytecodeInterpreter.java`'s `REGISTER_FORMAT` preserve
   the target format object's identity while installing a definition and
   captures. The intent is to keep preexisting FORMAT aliases live. Verify
   JVM/interpreter behavior and localization/restore semantics together.
4. `runtime/runtimetypes/RuntimeGlob.java`: aliases format slots even when
   undefined, and adds `localizedOriginalIO` restoration for FORMAT assignment.
   These are broad semantic changes whose necessity and correctness remain
   unproven. Inspect absent-slot introspection, dynamic save/restore, and alias
   identity. Do not keep the IO workaround solely because one case passes.

The permanent focused test is
[format_argument_line_execution.t](../../src/test/resources/unit/format_argument_line_execution.t).
During investigation its newly added nested-format case was changed from an
anonymous selected handle to a named handle matching the imported core case.
The named version passed both backends, but the earlier anonymous-handle
scenario did not: system Perl produced `birds\nnest\n` with an unopened-handle
warning, while PerlOnJava lost or misdirected `birds`. Preserve coverage of
**both** scenarios; narrowing a test is not a fix. Investigate shared `$^A`
accumulation as well as handle state instead of assuming IO preservation is
the root cause. Do not modify or delete existing tests to accommodate behavior.

## Immediate next failure: invalid format registration

The focused file now contains 22 assertions. Test 13 evaluates a declaration
whose argument contains `@_ =~ s///`, then attempts to write that format.
System Perl leaves the format undefined after compilation fails. PerlOnJava
registers it and only reports `Can't modify array dereference in substitution
(s///)` when executing the argument at write time.

Required direction: validate/compile the complete argument in its proper
lexical context before installing the format, preserving Perl's declaration
failure behavior. Do not execute runtime argument side effects as validation.
`EvalStringHandler` currently combines compilation and execution; no completed
compile-only validation solution was implemented in this investigation.

Evidence files (local, ephemeral):

- `/tmp/prove-format-invalid-perl.log`: earlier system-Perl focused run, PASS.
- The latest focused system-Perl run is `/tmp/prove-format-pagination-perl.log`:
  22 assertions, PASS. The JVM suite still fails this file only at its nested
  declaration and invalid-registration assertions (now tests 12 and 13).
- `/tmp/format-nested-named-jvm.log` and
  `/tmp/format-nested-named-interpreter.log`: earlier 17-assertion version
  passed before adding invalid-registration coverage.
- `/tmp/format-preserve-io-jvm.log` and its `-interpreter.log` counterpart:
  earlier anonymous-handle nested case still failed.
- `/tmp/nested-write-source-trace.log`: the regex hoist was reached; a prior
  assertion that it was not reached was incorrect.
- `/tmp/make-write-preserve-format-io.log`: failed original nested regression;
  the gate was interrupted and its children subsequently checked as exited.
  It is **not** a successful full gate for the current source.

There is no green full `make` for the latest WIP. A working development JAR
and a passing older focused test do not establish current-tree validation.

## Resume and acceptance checklist

1. Preserve the dirty tree and review both the WIP commit and the uncommitted
   diff against the published parent. Confirm no build/test processes are
   running before changing source. Establish which commit built the JAR.
2. Fix invalid registration and review the nested-format/alias experiments.
   Retain standard-Perl regression coverage for every externally observed
   root behavior, including the anonymous-handle case. Record failure on the
   unfixed parent using a separate worktree when needed.
3. Work through every failing row above and investigate every missing TAP
   number. Recompute the complete census after each relevant fix; later tests
   may reveal further failures once execution progresses.
4. Run each new regression on system Perl first, then JVM and interpreter.
   Run the complete `write.t` on both backends and compare with the UAT runner.
   Completion requires the entire plan accounted for, no unexpected `not ok`,
   no unexplained missing records, and honest skip/TODO accounting. Do not
   change imported tests, lower the plan, or hide failures with skips.
5. Run `make` successfully on the final immutable source revision. Never
   edit/rebase the checkout while a gate or its children run, or run JAR
   readers beside a rebuilding Make target. Follow AGENTS.md over older skill
   examples: `make dev` is disabled; use `make`.
6. Update [core-suite-failure-reduction.md](core-suite-failure-reduction.md)
   with completed work and exact evidence, and add a terse compatibility entry
   under Work in progress in the [changelog](../../docs/about/changelog.md).
   Run `make check-links` for Markdown changes.
7. Inspect the existing PR's head/state and integrate the preserved WIP into
   the intended feature branch without dropping published fixes. Refresh
   against the current base and rerun gates on the resulting revision. Use
   commit-message and PR-body files with required AI attribution. Push the
   feature branch, never master; update the existing PR if appropriate rather
   than creating a duplicate. Verify its head SHA, open state and changed files.
8. Prepare the user's local directory too: ensure it contains the intended
   source and successfully rebuilt JAR, check `timeout 120 ./jperl -v`, and
   provide matching commit/build identifiers with the exact test census.
   Do not claim UAT-ready from PR publication alone. Leave merging to review.

Example commands from the repository root, after ensuring no JAR writer is
active (capture full logs; inspect results rather than trusting exit 0):

```sh
timeout 120 prove src/test/resources/unit/format_argument_line_execution.t > /tmp/write-handoff-perl.log 2>&1
timeout 180 ./jperl src/test/resources/unit/format_argument_line_execution.t > /tmp/write-handoff-jvm.log 2>&1
timeout 180 ./jperl --interpreter src/test/resources/unit/format_argument_line_execution.t > /tmp/write-handoff-interpreter.log 2>&1
timeout 600 perl dev/tools/perl_test_runner.pl perl5_t/t/op/write.t > /tmp/write-handoff-runner.log 2>&1
```

For direct core execution, change to `perl5_t/t` before invoking
`timeout 180 ../../jperl op/write.t`; the test requires its local `test.pl`.
Use `JPERL_INTERPRETER=1` when checking interpreter behavior in subprocesses
as well as the parent. Consult runner options/environment before comparing
results. Always bound `jperl`, `jcpan`, and `prove` invocations with `timeout`.

Workflow reference: [debug-perlonjava skill](../../.agents/skills/debug-perlonjava/SKILL.md).

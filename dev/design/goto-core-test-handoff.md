# Handoff: complete op/goto.t compatibility

## Objective and status

Fix `perl5_t/t/op/goto.t`, retain permanent project-owned regression coverage,
validate both execution backends, and open a PR. The user has authorized the
fix and PR. No additional authorization is needed for ordinary implementation.

As of 2026-09-15, the fix is **incomplete**. Branch: `fix/goto-core-test`.
Base/current HEAD: `1caea101363b5f12bb88b98ccf4455e16af722f3`.
Implementation experiments are uncommitted. No PR has been opened.
Preserve this working tree when resuming; follow the repository's dirty-tree
backup instructions before any checkout, rebase, or other tree mutation.

## Most important diagnostic correction

The previous investigation repeatedly treated the first fatalized-construct
assertion as an isolated loop-condition compilation failure. That conclusion
is **not established by the full-file output**.

The latest log goes directly from test 16 (nested eval STRING, source line 156)
to a reported test 17 at source line 657. Many assertions between these lines
never execute. The expected regex at line 657 is `(?^:1)`, although `$msg` is
initialized at line 642 to a descriptive error string. This strongly suggests
an earlier incorrect jump skips intervening code, including initialization.
It does not prove that the condition guard was lost during compilation.

Start by tracing the first `goto A` at line 163 and its selected destination.
Later independent blocks reuse `A`, including the body at line 653. A jump
straight to that later body would explain the skipped assertions, empty `$@`,
wrong `$msg`, and eventual register-type crash. Confirm this with emitted PCs
and source locations before changing loop-condition handling again.

## Authoritative evidence to retain and recheck

- Latest full gate: `/tmp/make-goto-for3-direct-mark.log`, `BUILD SUCCESSFUL
  in 6m 11s`, `EXIT: 0`.
- Latest exact-file runs: `/tmp/goto-jvm-for3-direct-mark.log` and
  `/tmp/goto-interpreter-for3-direct-mark.log`. Both plan 87 tests, execute
  only 39, and exit 2 with a `RuntimeArray` to `RuntimeScalar` cast failure
  after the glob assertion near source line 796.
- Tests 1–16 pass in these logs. Most subsequent construct assertions receive
  the new error message but compare it with the wrong regex, `(?^:1)`.
- An isolated loop-condition reproducer previously produced the intended
  construct-entry error with `--interpreter`; the default backend produced
  the foreach-entry diagnostic instead. Logs:
  `/tmp/goto-condition-isolated-interpreter.log` and
  `/tmp/goto-condition-isolated-jvm.log`.
- Nested eval STRING originally returned `ok=0` where system Perl returned
  `ok=1`. Preserving and resolving its GOTO marker made core test 16 pass.
- Full gates have exposed `unit/nested_eval_block_goto_missing.t` regressions
  during raw-jump experiments. Preserve this existing test unchanged.

Temporary logs may disappear; copy relevant evidence into the eventual PR
description or a durable validation record. A successful `make` alone does
not establish success for the imported core file.

## Current implementation inventory

All paths below are relative to the repository root.

| File | Uncommitted work and limitations |
| --- | --- |
| `src/main/java/org/perlonjava/backend/bytecode/BytecodeCompiler.java` | Collects construct labels and declared labels; adds condition-depth/AST markers; records unused loop-PC ranges. `gotoLabelPcs` still maps plain names to a single PC. |
| `src/main/java/org/perlonjava/backend/bytecode/CompileOperator.java` | Uses raw GOTO and pending patches for selected declared static labels; otherwise GOTO_DYNAMIC. Uses a NUL-prefixed label string to signal forbidden condition entry. |
| `src/main/java/org/perlonjava/backend/bytecode/BytecodeInterpreter.java` | Rejects marked construct destinations; recognizes the NUL marker; resolves GOTO returned from EVAL_STRING. |
| `src/main/java/org/perlonjava/backend/bytecode/SlowOpcodeHandler.java` | Preserves scalar/void eval GOTO markers when the enclosing code's label map contains their target. |
| `src/main/java/org/perlonjava/backend/bytecode/InterpretedCode.java` | Carries construct-label and loop-range metadata through closure cloning. |
| `src/main/java/org/perlonjava/backend/jvm/EmitBlock.java` | Collects expression-do-block labels by name. |
| `src/main/java/org/perlonjava/backend/jvm/EmitControlFlow.java` | Emits a runtime construct-entry error for names in that set. |
| `src/main/java/org/perlonjava/backend/jvm/JavaClassInfo.java` | Stores the construct-label set. |
| `src/test/resources/unit/goto_jump_into_construct.t` | New, untracked focused expression-do-block regression; previously passed system Perl and both project modes. |

### Concrete issues visible in the current source

1. `collectDeclaredGotoLabels` handles `LabelNode`, but does not collect
   `BlockNode.labels` and does not traverse `For3Node`, `For1Node`, `IfNode`,
   or inline eval bodies. Existing nearby collectors explicitly document
   that statement labels commonly live in `BlockNode.labels`. Thus the new
   direct-static path may not apply to the very labels it was meant to fix.
2. Direct patching still uses a global name map for backward labels and
   name-only pending patches for forward labels. It is not scope-aware merely
   because it runs at compile time. Repeated labels require correct lexical
   target selection in both directions.
3. `gotoLabelsInsideConstruct` is name-only and does not model whether the
   source is already inside the destination construct. It can reject legal
   internal jumps or conflate unrelated labels.
4. Rejecting every goto in a loop condition is too broad: a jump out to a
   legal enclosing label must be distinguished from entry into an unentered
   body. Resolve source and destination, then validate the boundary crossed.
5. `gotoLabelLoopRanges` is collected and cloned but has no runtime consumer.
   Remove this experiment if it is not part of the final solution.
6. The condition-depth, root traversal, and visitor-level annotation attempts
   are redundant. Their comments about parser rewrites are hypotheses, not
   demonstrated causes. Replace them with evidence-backed logic.
7. Default-mode execution of this large core file falls back to the
   interpreter. Passing it in both modes will still need smaller focused
   tests that exercise generated JVM code.

## Plan to finish

### Phase 1: establish the first wrong jump

1. Check process state and ensure no build/test workers are using this
   checkout before editing. Tool session IDs are not OS PIDs.
2. Read the debugging skill and applicable repository instructions.
3. Add a small new regression with two or three independent blocks reusing
   the same label and an observable statement between them. Include both
   forward and backward jumps. Run system Perl first and record current
   project failure before implementing the repair.
4. Trace label registration and resolution for source line 163: record source
   token, lexical block identity, destination PC, and destination source
   location. Use existing disassembly facilities or temporary gated tracing.
5. Verify why the declared-label collector excludes or selects this target.
   Stop treating line 657 as the first root failure until earlier assertions
   execute in order.

### Phase 2: implement correct label selection and boundary checks

Represent label identity with its containing scope and destination metadata,
rather than only a string. Resolve static jumps using Perl's enclosing-block
search rules, confirmed with system-Perl examples. Resolve dynamic/eval jumps
relative to the active caller scope. Collect labels from the actual AST
representation, including block label tables, without traversing unrelated
subroutine bodies as part of the same compilation unit.

Track the constructs containing source and destination. Permit legal jumps
within active constructs; reject entry that skips required expression or
iterator setup. Preserve valid jumps to loop labels that run initialization.
Unwind lexical cleanup, localization, eval handlers, and control-block stacks
where a jump leaves their scopes. Avoid raw PCs unless these requirements are
met. Replace the string-marker workaround with explicit metadata if needed.

Keep nested eval STRING propagation working while preserving missing-label
errors at the correct eval boundary. The current scalar preservation test
based on mere map membership is only a provisional implementation.

### Phase 3: finish compatibility and regression coverage

Once the core file executes sequentially through the earlier cases, rerun it
to expose the actual remaining failures. Revisit forbidden expression entry,
the C-style condition case, optimized-away labels, and eval propagation using
fresh output. Do not repair the cast crash by coercing register values unless
independent evidence shows a type-conversion bug; it may be a downstream
effect of the wrong jump.

Add permanent focused coverage for each repaired behavior: repeated labels,
nested eval STRING, missing targets, legal internal/outward jumps, illegal
construct entry, and correct cleanup as applicable. Validate new tests with
system Perl first. Older Perl versions warn for cases fatalized in 5.44;
the existing new test conditionally fatalizes `deprecated` warnings on those
reference versions. Keep the imported core file and all existing tests intact.

### Phase 4: validation and PR

Use `make` for the required build/unit gate, with complete output in a file.
Allow the process and its workers to finish before edits or JAR readers.
Run each project invocation under `timeout`; capture output and exit status.

```sh
timeout 1200 make > /tmp/make-goto-final.log 2>&1
```

After successful build completion, from `perl5_t/t`:

```sh
timeout 180 ../../jperl op/goto.t > /tmp/goto-final-jvm.log 2>&1
timeout 180 ../../jperl --interpreter op/goto.t > /tmp/goto-final-interpreter.log 2>&1
```

Record each command's exit status immediately. Require all 87 assertions,
correct plan completion, no failures, and zero exit status in both modes.
Run the focused regressions on both backends and retain evidence that the
new tests fail on the unfixed parent. Use an isolated worktree for parent
comparison; do not overwrite this investigative tree.

Remove redundant experiments and temporary tracing, run `git diff --check`,
add a terse changelog entry under Work in progress, and run `make check-links`
for changed Markdown. Commit on the feature branch following attribution
policy, push, and create the authorized PR with its final scope and validation.
Do not merge without the required review.

## Progress tracking

### Completed implementation, 2026-09-15

- Reproduced the imported core failure on both invocation modes.
- Added provisional expression-construct error handling and a focused test.
- Fixed the observed nested eval STRING case in current core output.
- Obtained successful full project gates, including the latest snapshot.
- Identified skipped assertions and name-only target selection as the next
  diagnostic priority in this handoff.
- Replaced name-only static resolution with block-scoped label targets, including
  forward/backward patches and first-definition handling for repeated labels.
- Restricted eval-originated loop-entry rejection to the resolved destination PC
  and restored the runtime package recorded for each destination label.
- Added system-Perl-validated project regressions for scoped/repeated labels and
  jumps across package declarations; both pass on the JVM and interpreter.
- `make` passed after the final implementation change. Both `op/goto.t` modes
  execute all 80 assertions currently present in this imported file; its fixed
  `plan tests => 87` is inconsistent with that source and emits a plan-mismatch
  diagnostic despite zero exit status. The two reported non-passes are existing
  TODO cases at source lines 368 and 535.

### Next steps

1. Determine whether the upstream core-test revision supplies the seven missing
   assertions or its plan must be corrected by its owner; do not change the
   imported test locally.
2. Complete final PR hygiene (link checks, commit, push, and draft PR) once the
   imported-test plan discrepancy is resolved or explicitly accepted.

### Open questions

- Is the 87-test plan a stale imported-test artifact? The current source has
  only 80 assertion calls, and both modes now execute all 80.

There is no demonstrated external blocker requiring user input. The unresolved
compiler behavior needs further debugging within the already authorized scope.

## Related instructions

- [Repository guidelines](../../AGENTS.md)
- [General debugging skill](../../.agents/skills/debug-perlonjava/SKILL.md)
- [Interpreter parity skill](../../.agents/skills/interpreter-parity/SKILL.md)
- [Changelog](../../docs/about/changelog.md)

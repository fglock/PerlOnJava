---
name: debug-perlonjava
description: Debug and fix test failures and regressions in PerlOnJava
argument-hint: "[test-name, error message, or Perl construct]"
triggers:
  - user
  - model
---

# Debugging PerlOnJava

You are debugging failures in PerlOnJava, a Perl-to-JVM compiler with a bytecode interpreter fallback. This skill covers debugging workflows for test failures, regressions, and parity issues between backends.

## ⚠️⚠️⚠️ CRITICAL: NEVER USE `git stash` ⚠️⚠️⚠️

**DANGER: Changes are SILENTLY LOST when using git stash/stash pop!**

- NEVER use `git stash` to temporarily revert changes
- INSTEAD: Commit to a WIP branch or use `git diff > backup.patch`
- This warning exists because completed work was lost during debugging

## Git Workflow

**IMPORTANT: Never push directly to master. Always use feature branches and PRs.**

```bash
git checkout -b fix/descriptive-name
# ... make changes ...
git push origin fix/descriptive-name
gh pr create --title "Fix: description" --body "Details"
```

### Fixing several issues at once (unified WIP PR)

When asked to fix multiple independent issues in one session, run the fixes in
parallel and collect them into a single PR. Open the PR as a **draft up front**
so no commit is ever the only copy of the work.

1. **Fan out, one isolated worktree per issue.** Give each agent its own git
   worktree so concurrent `make` runs do not fight over the shared JAR — two
   builds in one worktree corrupt each other's output. Each agent reproduces
   against system `perl` first, fixes, runs `make`, tests both backends, adds a
   regression test, and commits on its own `fix/issue-NNNN-topic` branch.

2. **Open the draft PR as soon as the first commit lands.** Do not wait for all
   of them.

   ```bash
   git checkout -b fix/issues-1119-1125-1135
   git merge --no-ff --no-edit fix/issue-1125-getcwd-taint
   git push -u origin fix/issues-1119-1125-1135
   gh pr create --draft --base master --title "WIP: ..." --body-file /tmp/prbody.md
   ```

   Keep a status table in the PR body (issue | area | landed/in progress) and a
   test-plan checklist. Update both as branches merge.

3. **Merge each branch in as it arrives** with `git merge --no-ff` and push.
   `--no-ff` keeps each issue's commits identifiable if one has to be reverted.

4. **Base dependent work on the integration branch, not master.** If a later
   fix builds on an earlier one, push the integration branch first and have that
   agent `git fetch origin && git merge --no-edit origin/<integration-branch>`.
   Tell it which files the earlier commit touched so it preserves that behavior.

5. **Verify each agent's claims yourself** before merging: read the diff, and
   re-run the reproducer against system `perl` and both backends. Agent reports
   contain real errors — in one session a report claimed system perl exits 2 on
   a taint die when it actually exits 255, i.e. the "divergence" did not exist.
   Never merge on the strength of a report alone.

6. **Fix what the investigation turns up, too.** Adjacent divergences found
   while fixing the assigned issue are part of the job. Confirm each against
   system `perl` first, then dispatch it like any other fix. If one is genuinely
   out of scope, file an issue rather than dropping it silently.

7. **Flip to ready only when every commit is in and `make` is green on the
   integration branch**, then hand off for UAT while CI runs:

   ```bash
   gh pr ready <number>
   gh pr checks <number> --watch    # or poll; wrap long waits in timeout
   ```

   Tell the user UAT can start immediately — CI and manual testing run in
   parallel, they do not need to wait for the checks.

8. **Monitor CI and fix failures on the same branch.** For every red check,
   first determine whether it also fails on master before assuming the branch
   caused it. Push fixes to the integration branch; do not open a second PR.

Never commit `.claude/` — it is untracked and holds the agent worktrees. Stage
explicit paths, or merge branches; never `git add -A` at the repo root.

## Project Layout

- **PerlOnJava source**: `src/main/java/org/perlonjava/` (compiler, bytecode interpreter, runtime)
- **Unit tests**: `src/test/resources/unit/*.t` (run via `make`)
- **Perl5 core tests**: `perl5_t/t/` (Perl 5 compatibility suite)
- **Fat JAR**: `target/perlonjava-3.0.0.jar`
- **Launcher script**: `./jperl`

## Building

**ALWAYS use `make` commands. NEVER use raw mvn/gradlew commands.**

| Command | What it does |
|---------|--------------|
| `make` | Build + run all unit tests (always use this) |

```bash
make       # Standard build - compiles and runs tests
```

`make dev` has been **disabled on purpose**: it built without running tests,
which let regressions reach commits. If you truly need a no-test build for quick
iteration, invoke Gradle directly:

```bash
./gradlew shadowJar installDist
```

Treat `make` as a shared-JAR writer: never run it beside `jperl`, `jcpan`, or a
Perl test runner using the same worktree's JAR.

## Running Tests

### Single Perl5 core test
```bash
cd perl5_t/t
../../jperl op/bop.t
```

### With environment variables (for specific tests)
```bash
# For re/pat.t and similar regex tests
JPERL_OPTS=-Xss256m PERL_SKIP_BIG_MEM_TESTS=1 ./jperl perl5_t/t/re/pat.t

# For op/sprintf2.t
./jperl perl5_t/t/op/sprintf2.t
```

### Test runner (parallel, with summary)
```bash
perl dev/tools/perl_test_runner.pl perl5_t/t/op
perl dev/tools/perl_test_runner.pl --jobs 8 --timeout 60 perl5_t/t
```

### Test runner environment variables
The test runner (`dev/tools/perl_test_runner.pl`) automatically sets environment variables for specific tests:

```perl
# JPERL_OPTS="-Xss256m" for these tests:
re/pat.t | op/repeat.t | op/list.t

# PERL_SKIP_BIG_MEM_TESTS=1 for ALL tests
```

To reproduce what the test runner does for a specific test:
```bash
# For re/pat.t:
cd perl5_t/t && JPERL_OPTS=-Xss256m PERL_SKIP_BIG_MEM_TESTS=1 ../../jperl re/pat.t

# For re/subst.t (only PERL_SKIP_BIG_MEM_TESTS):
cd perl5_t/t && PERL_SKIP_BIG_MEM_TESTS=1 ../../jperl re/subst.t

# For op/bop.t (only PERL_SKIP_BIG_MEM_TESTS):
cd perl5_t/t && PERL_SKIP_BIG_MEM_TESTS=1 ../../jperl op/bop.t
```

### Interpreter mode
```bash
./jperl --interpreter script.pl
./jperl --interpreter -e 'print "hello\n"'
JPERL_INTERPRETER=1 ./jperl script.pl   # Global (affects require/do/eval)
```

## Comparing Outputs

### PerlOnJava vs System Perl
```bash
# System Perl
perl -e 'my @a = (1,2,3); print "@a\n"'

# PerlOnJava
./jperl -e 'my @a = (1,2,3); print "@a\n"'
```

### JVM backend vs Interpreter backend
```bash
./jperl -e 'code'                      # JVM backend
JPERL_INTERPRETER=1 ./jperl -e 'code'  # Interpreter backend
```

## Environment Variables

### Compiler/Interpreter Control
| Variable | Effect |
|----------|--------|
| `JPERL_INTERPRETER=1` | Force interpreter mode globally (require/do/eval) |
| `JPERL_DISABLE_INTERPRETER_FALLBACK=1` | Disable bytecode interpreter fallback for large subs |
| `JPERL_SHOW_FALLBACK=1` | Print message when a sub falls back to interpreter |
| `JPERL_EVAL_NO_INTERPRETER=1` | Disable interpreter for `eval STRING` |
| `JPERL_OPTS="-Xss256m"` | Pass JVM options (e.g., stack size) |

### Debugging/Tracing
| Variable | Effect |
|----------|--------|
| `JPERL_DISASSEMBLE=1` | Disassemble generated bytecode |
| `JPERL_ASM_DEBUG=1` | Print JVM bytecode when ASM frame computation crashes |
| `JPERL_EVAL_VERBOSE=1` | Verbose error reporting for eval compilation |
| `JPERL_EVAL_TRACE=1` | Trace eval STRING execution path |
| `JPERL_IO_DEBUG=1` | Trace file handle open/dup/write operations |
| `JPERL_REQUIRE_DEBUG=1` | Trace `require`/`use` module loading |

### Perl-level
| Variable | Effect |
|----------|--------|
| `PERL_SKIP_BIG_MEM_TESTS=1` | Skip memory-intensive tests |

## Debugging Workflow

### 1. Identify the regression
```bash
# Compare branch vs master
git checkout master && make dev
./jperl -e 'failing code'

git checkout branch && make dev
./jperl -e 'failing code'
```

### 2. Create minimal reproducer
Reduce the failing test to the smallest code that demonstrates the bug:
```bash
./jperl -e 'my $x = 58; eval q{($x) .= "z"}; print "x=$x\n"'
```

### 3. Compare with system Perl
```bash
perl -e 'same code'
```

### 4. Use --parse to check AST
When parsing issues are suspected, compare the parse tree:
```bash
./jperl --parse -e 'code'                    # Show PerlOnJava AST
perl -MO=Deparse -e 'code'                   # Compare with Perl's interpretation
```
This helps identify operator precedence issues and incorrect parsing.

### 5. Use disassembly to understand
```bash
./jperl --disassemble -e 'minimal code'                      # JVM bytecode
./jperl --disassemble --interpreter -e 'minimal code'        # Interpreter bytecode
```

### 6. Profile with JFR (for performance issues)
```bash
# Resolve JDK tools explicitly; `jfr` is not always on PATH on macOS.
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null)}"
export JFR_BIN="${JFR_BIN:-$JAVA_HOME/bin/jfr}"
# Local fallback known to exist on this workstation:
# /Users/fglock/Library/Java/JavaVirtualMachines/temurin-24.0.2/Contents/Home/bin/jfr

# Record profile
$JAVA_HOME/bin/java -XX:StartFlightRecording=duration=10s,filename=profile.jfr \
  -jar target/perlonjava-3.0.0.jar script.pl

# Analyze hotspots
$JFR_BIN print --events jdk.ExecutionSample profile.jfr 2>&1 | \
  grep -E "^\s+[a-z].*line:" | sed 's/line:.*//' | sort | uniq -c | sort -rn | head -20
```

### 7. Add debug prints (if needed)
In Java source, add:
```java
System.err.println("DEBUG: var=" + var);
```
Then rebuild with `make dev`.

### 8. Fix and verify
```bash
# After fixing
make dev
./jperl -e 'test code'        # Verify fix
make                           # Build + run unit tests (no regressions)
```

### 9. Retain regression coverage and release notes

Every externally observed failure needs a permanent, tracked regression test
before the fix is complete. Add or strengthen the smallest project-owned test
that demonstrates the root behavior; run a new or changed Perl-level unit test
with system Perl first, then verify it on both PerlOnJava backends. An ad-hoc
reproducer or a passing broad suite is supporting evidence, not a replacement
for the permanent test.

For a user-visible compatibility or runtime fix, add an entry under
`## Work in progress` in `docs/about/changelog.md`. Follow the changelog's
existing terse bullet style; do not add it to a released version section.

### 10. Issue PR lifecycle

For an issue fix, create and push a draft WIP PR as soon as the initial safe
snapshot is committed. Continue investigation and implementation on that PR.

Before handing the PR to UAT or CI, rebase the branch onto the latest `master`
and rerun the focused regression and required validation on the rebased commit.
Then mark the draft PR ready for review with `gh pr ready <number>` and tell
the user to begin UAT.
While the user is testing, monitor the PR's CI checks and fix, validate, and
push any failures until both UAT feedback and CI are satisfactory.

## Git Workflow

**IMPORTANT**: Always work in a feature branch and create a PR for review.

### 1. Create a branch before making changes
```bash
git checkout -b fix-descriptive-name
```

### 2. Make commits with clear messages
```bash
git add -A && git commit -m "Fix <what> by <how>

<Details of the bug and fix>

Generated with [TOOL_NAME](TOOL_DOCS_URL)

Co-Authored-By: TOOL_NAME <TOOL_BOT_EMAIL>"
```

### 3. Push branch and create PR
```bash
git push -u origin fix-descriptive-name

# Create a draft WIP PR using gh CLI
gh pr create --draft --title "Fix: description" --body "## Summary
- Fixed X by Y

## Test Plan
- [ ] Unit tests pass
- [ ] Reproducer now works correctly

Generated with [TOOL_NAME](TOOL_DOCS_URL)"
```

### 4. After PR is merged, clean up
```bash
git checkout master
git pull
git branch -d fix-descriptive-name
```

## Architecture: Two Backends

```
Source → Lexer → Parser → AST ─┬─→ JVM Compiler → JVM bytecode (default)
                                └─→ BytecodeCompiler → InterpretedCode → BytecodeInterpreter
```

Both backends share the parser (same AST) and runtime (same operators, same RuntimeScalar/Array/Hash).

## Key Source Files

| Area | File | Notes |
|------|------|-------|
| **Bytecode Compiler** | `backend/bytecode/BytecodeCompiler.java` | AST → interpreter bytecode |
| **Bytecode Interpreter** | `backend/bytecode/BytecodeInterpreter.java` | Main dispatch loop |
| **Assignment (interp)** | `backend/bytecode/CompileAssignment.java` | Assignment compilation |
| **Binary ops (interp)** | `backend/bytecode/CompileBinaryOperator.java` | Binary operator compilation |
| **Unary ops (interp)** | `backend/bytecode/CompileOperator.java` | Unary operator compilation |
| **Opcodes** | `backend/bytecode/Opcodes.java` | Opcode constants |
| **eval STRING** | `backend/bytecode/EvalStringHandler.java` | eval STRING compilation |
| **JVM Compiler** | `backend/jvm/EmitterMethodCreator.java` | AST → JVM bytecode |
| **JVM Subroutine** | `backend/jvm/EmitSubroutine.java` | Sub compilation (JVM) |
| **JVM Binary ops** | `backend/jvm/EmitBinaryOperator.java` | Binary ops (JVM) |
| **Compilation router** | `app/scriptengine/PerlLanguageProvider.java` | Picks backend |
| **Runtime scalar** | `runtime/runtimetypes/RuntimeScalar.java` | Scalar values |
| **Runtime array** | `runtime/runtimetypes/RuntimeArray.java` | Array values |
| **Runtime hash** | `runtime/runtimetypes/RuntimeHash.java` | Hash values |
| **Math operators** | `runtime/operators/MathOperators.java` | +, -, *, /, etc. |
| **String operators** | `runtime/operators/StringOperators.java` | ., x, etc. |
| **Bitwise operators** | `runtime/operators/BitwiseOperators.java` | &, |, ^, etc. |
| **Regex runtime** | `runtime/regex/RuntimeRegex.java` | Regex matching |
| **Regex preprocessor** | `runtime/regex/RegexPreprocessor.java` | Perl→Java regex |

All paths relative to `src/main/java/org/perlonjava/`.

## CRITICAL: Investigate JVM Backend First

**When fixing interpreter bugs, ALWAYS investigate how the JVM backend handles the same operation before implementing a fix.**

The interpreter and JVM backends share the same runtime classes (`RuntimeScalar`, `RuntimeArray`, `RuntimeHash`, `RuntimeList`, `PerlRange`, etc.). The JVM backend is the reference implementation - if the interpreter handles something differently, it's likely wrong.

### How to investigate JVM behavior

1. **Disassemble the JVM bytecode** to see what runtime methods it calls:
   ```bash
   ./jperl --disassemble -e 'code that works'
   ```

2. **Look for the runtime method calls** in the disassembly (INVOKEVIRTUAL, INVOKESTATIC):
   ```
   INVOKEVIRTUAL org/perlonjava/runtime/runtimetypes/RuntimeList.addToArray
   INVOKEVIRTUAL org/perlonjava/runtime/runtimetypes/RuntimeBase.setFromList
   ```

3. **Read those runtime methods** to understand the correct behavior:
   - How does `setFromList()` handle different input types?
   - What methods does it call internally (`addToArray`, `getList`, etc.)?

4. **Use the same runtime methods in the interpreter** instead of reimplementing the logic with special cases.

### Example: Hash slice assignment with PerlRange

**Wrong approach** (special-casing types in interpreter):
```java
if (valuesBase instanceof RuntimeList) { ... }
else if (valuesBase instanceof RuntimeArray) { ... }
else if (valuesBase instanceof PerlRange) { ... }  // BAD: special case
else { ... }
```

**Correct approach** (use same runtime methods as JVM):
```java
// JVM calls addToArray() which handles all types uniformly
RuntimeArray valuesArray = new RuntimeArray();
valuesBase.addToArray(valuesArray);  // Works for RuntimeList, RuntimeArray, PerlRange, etc.
```

The JVM's `setFromList()` → `addToArray()` chain already handles `PerlRange` correctly via `PerlRange.addToArray()` → `toList().addToArray()`. The interpreter should use the same mechanism.

## Common Bug Patterns

### 1. Context not propagated correctly
**Symptom**: Operation returns wrong type (list vs scalar).
**Pattern**: Code uses `node.accept(this)` instead of `compileNode(node, -1, RuntimeContextType.SCALAR)`.
**Fix**: Use `compileNode()` helper with explicit context.

### 2. Missing opcode implementation
**Symptom**: "Unknown opcode" or silent wrong result.
**Fix**: Add opcode to `Opcodes.java`, handler to `BytecodeInterpreter.java`, emitter to `BytecodeCompiler.java`, disassembly to `InterpretedCode.java`.

### 3. Closure variable not accessible
**Symptom**: Variable returns undef inside eval/sub.
**Pattern**: Variable not registered in symbol table.
**Fix**: Ensure `detectClosureVariables()` registers captured variables via `addVariableWithIndex()`.

### 4. Double compilation of RHS
**Symptom**: Side effects happen twice (e.g., `shift` removes two elements).
**Pattern**: RHS compiled once at top of function, then again in specific handler.
**Fix**: Remove redundant compilation, use `valueReg` from first compilation.

### 5. Lvalue not preserved
**Symptom**: Assignment doesn't modify original variable.
**Pattern**: Expression returns copy instead of lvalue reference.
**Fix**: Ensure lvalue context is preserved through compilation chain.

### 6. LIST_TO_COUNT destroys value
**Symptom**: Numeric value instead of expected string/reference.
**Pattern**: Incorrect scalar context conversion.
**Fix**: Remove spurious `LIST_TO_COUNT` or use proper scalar coercion.

### 7. Block returns stale value when last statement has no result
**Symptom**: Block/eval returns unexpected value (e.g., 1 instead of undef).
**Pattern**: Last statement is `for` loop or similar that sets `lastResultReg = -1`.
**Fix**: In `visit(BlockNode)`, initialize `outerResultReg` to undef when `lastResultReg < 0`.

### 8. Loop list evaluated in wrong context
**Symptom**: `for` loop only iterates last element when inside `eval` in scalar context.
**Pattern**: Loop list compiled with `node.list.accept(this)` instead of explicit LIST context.
**Fix**: Use `compileNode(node.list, -1, RuntimeContextType.LIST)` for loop lists.

### 9. eval STRING context leaks into compiled code
**Symptom**: Operations inside eval behave differently based on how eval result is used.
**Pattern**: `currentCallContext` from eval propagates incorrectly to inner constructs.
**Fix**: Isolate context - loops/blocks should use their own context, not inherit from eval.

## Test File Categories

| Directory | Tests | Notes |
|-----------|-------|-------|
| `perl5_t/t/op/` | Core operators | bop.t, sprintf.t, etc. |
| `perl5_t/t/re/` | Regex | pat.t needs special env vars |
| `perl5_t/t/io/` | I/O operations | filetest.t, etc. |
| `perl5_t/t/uni/` | Unicode | |
| `perl5_t/t/mro/` | Method resolution | |

## Quick Reference Commands

```bash
# Build + test
make

# Build only (no tests)
make dev

# Run specific Perl5 test
perl dev/tools/perl_test_runner.pl perl5_t/t/op/bop.t

# Debug parsing
./jperl --parse -e 'code'
perl -MO=Deparse -e 'code'

# Debug bytecode
./jperl --disassemble -e 'code'
./jperl --disassemble --interpreter -e 'code'

# Compare output
diff <(./jperl -e 'code') <(perl -e 'code')

# Git workflow (always use branches!)
git checkout -b fix-name
# ... make changes ...
git add -A && git commit -m "Fix message"
git push -u origin fix-name
gh pr create --title "Fix: title" --body "Description"
```

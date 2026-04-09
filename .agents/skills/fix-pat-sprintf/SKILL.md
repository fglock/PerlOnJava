---
name: fix-pat-sprintf
description: Fix re/pat.t and op/sprintf2.t test regressions on fix-exiftool-cli branch
argument-hint: "[test-name or specific failure]"
triggers:
  - user
  - model
---

## ⚠️⚠️⚠️ CRITICAL: NEVER USE `git stash` ⚠️⚠️⚠️

**DANGER: Changes are SILENTLY LOST when using git stash/stash pop!**

- NEVER use `git stash` to temporarily revert changes
- INSTEAD: Commit to a WIP branch or use `git diff > backup.patch`
- This warning exists because completed work was lost during debugging

# Fix pat.t and sprintf2.t Regressions

You are fixing test regressions in `re/pat.t` (-17 tests) and `op/sprintf2.t` (-3 tests) on the `fix-exiftool-cli` branch of PerlOnJava.

## Hard Constraints

1. **No AST refactoring fallback.** The `LargeBlockRefactorer` / AST splitter must NOT be restored. This is non-negotiable.
2. **Fix the interpreter.** The bytecode interpreter must achieve feature parity with the JVM compiler. Both backends must produce identical results for all Perl constructs.
3. **Match the baseline exactly.** Target is the master baseline scores — no more, no less:
   - `re/pat.t`: 1056/1296
   - `op/sprintf2.t`: 1652/1655
4. **Do NOT modify shared runtime** (`RuntimeRegex.java`, `RegexFlags.java`, `RegexPreprocessor.java`, etc.). The runtime is shared between both backends. Fixes must be in the interpreter code.

## Why the Interpreter Is Involved

Large subroutines that exceed the JVM 64KB method limit fall back to the bytecode interpreter via `EmitterMethodCreator.createRuntimeCode()`.

- **pat.t**: The `run_tests` subroutine (lines 38-2652, ~2614 lines) falls back to interpreter. All 1296 tests run through it. Confirmed with `JPERL_SHOW_FALLBACK=1`.
- **sprintf2.t**: Same mechanism — large test body falls back to interpreter.

## Baseline vs Branch

| Test | Master baseline (397ba45d) | Branch HEAD | Delta |
|------|---------------------------|-------------|-------|
| re/pat.t | 1056/1296 | 1039/1296 | -17 |
| op/sprintf2.t | 1652/1655 | 1649/1655 | -3 |

## Methodology

For each failing test:

1. **Extract** the specific Perl code from the test file
2. **Compare** JVM vs interpreter output:
   ```bash
   ./jperl -E 'extracted code'              # JVM backend (correct behavior)
   ./jperl --interpreter -E 'extracted code' # Interpreter (may differ)
   ```
3. **When they differ**: identify the root cause in the interpreter code (BytecodeCompiler, BytecodeInterpreter, etc.) and fix it
4. **When they don't differ standalone**: the failure depends on context from earlier tests in the same large function. Investigate what prior state affects the result — look at regex state, variable scoping, match variables, pos(), etc.
5. **Verify** the fix doesn't break other tests

## Running the Tests

**ALWAYS use `make` commands. NEVER use raw mvn/gradlew commands.**

| Command | What it does |
|---------|--------------|
| `make` | Build + run all unit tests (use before committing) |
| `make dev` | Build only, skip tests (for quick iteration during debugging) |

```bash
make       # Standard build - compiles and runs tests
make dev   # Quick build - compiles only, NO tests
```

Run individual tests via test runner (sets correct ENV vars):
```bash
perl dev/tools/perl_test_runner.pl perl5_t/t/re/pat.t
perl dev/tools/perl_test_runner.pl perl5_t/t/op/sprintf2.t

# Run manually with correct ENV
cd perl5_t/t
PERL_SKIP_BIG_MEM_TESTS=1 JPERL_UNIMPLEMENTED=warn JPERL_OPTS="-Xss256m" ../../jperl re/pat.t
PERL_SKIP_BIG_MEM_TESTS=1 JPERL_UNIMPLEMENTED=warn ../../jperl op/sprintf2.t

# Compare JVM vs interpreter for a specific construct
./jperl -E 'code'
./jperl --interpreter -E 'code'

# Check if a test file uses interpreter fallback
cd perl5_t/t && JPERL_SHOW_FALLBACK=1 ../../jperl re/pat.t 2>&1 | grep 'interpreter backend'

# Get interpreter bytecodes for a construct
./jperl --interpreter --disassemble -E 'code' 2>&1
```

## pat.t: Exact Regressions (18 PASS->FAIL, 1 FAIL->PASS, net -17)

### Tests that went from PASS to FAIL

| # | Test Description | pat.t Line | Category |
|---|-----------------|------------|----------|
| 1 | Stack may be bad | 508 | regex match |
| 2 | $^N, @- and @+ are read-only | 845-851 | eval STRING special vars |
| 3-4 | \G testing (x2) | 858, 866 | \G anchor |
| 5 | \b is not special | 1089 | word boundary |
| 6-8 | \s, [[:space:]] and [[:blank:]] (x3) | 1223-1225 | POSIX classes |
| 9 | got a latin string - rt75680 | 1252 | latin/unicode |
| 10-11 | RT #3516 A, B | 1329, 1335 | \G loop |
| 12 | Qr3 bare | ~1490 | qr// overload |
| 13 | Qr3 bare - with use re eval | ~1498 | qr// eval |
| 14 | Eval-group not allowed at runtime | 524 | regex eval |
| 15-18 | Branch reset pattern 1-4 | 2392-2409 | branch reset |

### Test that went from FAIL to PASS

| Test Description | Category |
|-----------------|----------|
| 1 '', '1', '12' (Eval-group) | regex eval |

## Interpreter Architecture

```
Source -> Lexer -> Parser -> AST --+--> JVM Compiler (EmitterMethodCreator) -> JVM bytecode
                                  \--> BytecodeCompiler -> InterpretedCode -> BytecodeInterpreter
```

Both backends share the same runtime (RuntimeRegex, RuntimeScalar, etc.). The difference is ONLY in how the AST is lowered to executable form. The interpreter must handle every construct identically to the JVM compiler.

### Key interpreter files

| File | Role |
|------|------|
| `backend/bytecode/BytecodeCompiler.java` | AST -> interpreter bytecodes |
| `backend/bytecode/BytecodeInterpreter.java` | Main dispatch loop |
| `backend/bytecode/InterpretedCode.java` | Code object + disassembler |
| `backend/bytecode/Opcodes.java` | Opcode constants |
| `backend/bytecode/CompileAssignment.java` | Assignment compilation |
| `backend/bytecode/CompileBinaryOperator.java` | Binary ops compilation |
| `backend/bytecode/CompileOperator.java` | Unary/misc ops compilation |
| `backend/bytecode/SlowOpcodeHandler.java` | Rarely-used op handlers |
| `backend/bytecode/OpcodeHandlerExtended.java` | CREATE_CLOSURE, STORE_GLOB, etc. |
| `backend/bytecode/MiscOpcodeHandler.java` | Misc operations |
| `backend/bytecode/EvalStringHandler.java` | eval STRING compilation for interpreter |

All paths relative to `src/main/java/org/perlonjava/`.

### Key source files (do NOT modify)

| Area | File | Notes |
|------|------|-------|
| Regex runtime | `runtime/regex/RuntimeRegex.java` | DO NOT MODIFY |
| Regex flags | `runtime/regex/RegexFlags.java` | DO NOT MODIFY |
| Regex preprocessor | `runtime/regex/RegexPreprocessor.java` | DO NOT MODIFY |

All paths relative to `src/main/java/org/perlonjava/`.

## Verification Steps

After any fix:

```bash
# 1. Build must pass
make build

# 2. Unit tests must pass
make test-unit

# 3. Check pat.t — must match baseline (1056/1296)
perl dev/tools/perl_test_runner.pl perl5_t/t/re/pat.t

# 4. Check sprintf2.t — must match baseline (1652/1655)
perl dev/tools/perl_test_runner.pl perl5_t/t/op/sprintf2.t

# 5. No regressions in other key tests
perl dev/tools/perl_test_runner.pl perl5_t/t/op/pack.t
perl dev/tools/perl_test_runner.pl perl5_t/t/re/pat_rt_report.t
```

## Debugging Tips

### Compare raw output between baseline and branch
```bash
# Save branch output
cd perl5_t/t && PERL_SKIP_BIG_MEM_TESTS=1 JPERL_UNIMPLEMENTED=warn JPERL_OPTS="-Xss256m" ../../jperl re/pat.t > /tmp/pat_branch.txt 2>&1

# Compare by test name against saved baseline
LC_ALL=C diff \
  <(LC_ALL=C grep -E '^(ok|not ok)' /tmp/pat_base_raw.txt | LC_ALL=C sed 's/^ok [0-9]* - /PASS: /;s/^not ok [0-9]* - /FAIL: /' | LC_ALL=C sort) \
  <(LC_ALL=C grep -E '^(ok|not ok)' /tmp/pat_branch.txt | LC_ALL=C sed 's/^ok [0-9]* - /PASS: /;s/^not ok [0-9]* - /FAIL: /' | LC_ALL=C sort) \
  | grep '^[<>]'
```

### Test specific construct through both backends
```bash
./jperl -E 'my $s="abcde"; pos $s=2; say $s =~ /^\G/ ? "match" : "no"'
./jperl --interpreter -E 'my $s="abcde"; pos $s=2; say $s =~ /^\G/ ? "match" : "no"'
```

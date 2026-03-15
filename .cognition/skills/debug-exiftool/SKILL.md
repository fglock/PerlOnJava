---
name: debug-exiftool
description: Debug and fix Image::ExifTool test failures in PerlOnJava
argument-hint: "[test-name or test-file]"
triggers:
  - user
  - model
---

## ⚠️⚠️⚠️ CRITICAL: NEVER USE `git stash` ⚠️⚠️⚠️

**DANGER: Changes are SILENTLY LOST when using git stash/stash pop!**

- NEVER use `git stash` to temporarily revert changes
- INSTEAD: Commit to a WIP branch or use `git diff > backup.patch`
- This warning exists because completed work was lost during debugging

# Debugging Image::ExifTool Tests in PerlOnJava

You are debugging failures in the Image::ExifTool test suite running under PerlOnJava (a Perl-to-JVM compiler/interpreter). Failures typically stem from missing Perl features or subtle behavior differences in PerlOnJava, not bugs in ExifTool itself.

## Git Workflow

**IMPORTANT: Never push directly to master. Always use feature branches and PRs.**

**IMPORTANT: Always commit or stash changes BEFORE switching branches.** If `git stash pop` has conflicts, uncommitted changes may be lost.

```bash
git checkout -b fix/exiftool-issue-name
# ... make changes ...
git push origin fix/exiftool-issue-name
gh pr create --title "Fix: description" --body "Details"
```

## Project Layout

- **PerlOnJava source**: `src/main/java/org/perlonjava/` (compiler, bytecode interpreter, runtime)
- **ExifTool distribution**: `Image-ExifTool-13.44/` (unmodified upstream)
- **ExifTool tests**: `Image-ExifTool-13.44/t/*.t`
- **ExifTool test lib**: `Image-ExifTool-13.44/t/TestLib.pm` (exports `check`, `writeCheck`, `writeInfo`, `testCompare`, `binaryCompare`, `testVerbose`, `notOK`, `done`)
- **ExifTool test data**: `Image-ExifTool-13.44/t/images/` (reference images)
- **ExifTool reference output**: `Image-ExifTool-13.44/t/<TestName>_N.out` (expected tag output per sub-test)
- **PerlOnJava unit tests**: `src/test/resources/unit/*.t` (make suite, 154 tests)
- **Perl5 core tests**: `perl5_t/t/` (Perl 5 compatibility suite, run via `make test-gradle`)
- **Fat JAR**: `target/perlonjava-3.0.0.jar`
- **Launcher script**: `./jperl` (resolves JAR path, sets `$^X`)

## Building PerlOnJava

**ALWAYS use `make` commands. NEVER use raw mvn/gradlew commands.**

| Command | What it does |
|---------|--------------|
| `make` | Build + run all unit tests (use before committing) |
| `make dev` | Build only, skip tests (for quick iteration during debugging) |

```bash
make       # Standard build - compiles and runs tests
make dev   # Quick build - compiles only, NO tests
```

## Running ExifTool Tests

### Single test
```bash
cd Image-ExifTool-13.44
java -jar ../target/perlonjava-3.0.0.jar -Ilib t/Writer.t
# Or using the launcher:
cd Image-ExifTool-13.44
../jperl -Ilib t/Writer.t
```

### Single test with timeout (prevents infinite loops)
```bash
cd Image-ExifTool-13.44
timeout 120 java -jar ../target/perlonjava-3.0.0.jar -Ilib t/XMP.t
```

### All ExifTool tests in parallel with summary
```bash
cd Image-ExifTool-13.44
mkdir -p /tmp/exiftool_results
for t in t/*.t; do
    name=$(basename "$t" .t)
    ( output=$(timeout 120 java -jar ../target/perlonjava-3.0.0.jar -Ilib "$t" 2>&1)
      ec=$?
      if [ $ec -eq 124 ]; then echo "$name TIMEOUT"
      else
          pass=$(echo "$output" | grep -cE '^ok ')
          fail=$(echo "$output" | grep -cE '^not ok ')
          plan=$(echo "$output" | grep -oE '^1\.\.[0-9]+' | head -1)
          planned=${plan#1..}
          echo "$name pass=$pass fail=$fail planned=${planned:-?} exit=$ec"
      fi
    ) > "/tmp/exiftool_results/$name.txt" &
done
wait
echo "=== RESULTS ==="
cat /tmp/exiftool_results/*.txt | sort
echo "=== TOTALS ==="
cat /tmp/exiftool_results/*.txt | awk '{
    for(i=1;i<=NF;i++) {
        if($i~/^pass=/) p+=substr($i,6)
        if($i~/^fail=/) f+=substr($i,6)
        if($i~/^planned=/) { v=substr($i,9); if(v!="?") pl+=v }
    }
} END { printf "PASS=%d FAIL=%d PLANNED=%d RATE=%d%%\n", p, f, pl, (pl>0?p*100/pl:0) }'
```

### Running Perl5 core tests (e.g. lexsub.t)
```bash
cd perl5_t/t
../../jperl op/lexsub.t
```

### Running Perl5 core tests that use subprocess tests
Tests using `run_multiple_progs()` or `fresh_perl_is()` spawn `jperl` as a subprocess. This requires `jperl` to be in PATH:
```bash
# Using the test runner (handles PATH automatically):
perl dev/tools/perl_test_runner.pl perl5_t/t/op/eval.t

# Manual running (must set PATH):
PATH="/Users/fglock/projects/PerlOnJava2:$PATH" cd perl5_t/t && ../../jperl op/eval.t
```

## Comparing with System Perl

When debugging, compare PerlOnJava output with native Perl to isolate the difference:

```bash
# Run with system Perl
cd Image-ExifTool-13.44
perl -Ilib t/Writer.t 2>&1 | grep -E '^(not )?ok ' > /tmp/perl_results.txt

# Run with PerlOnJava
java -jar ../target/perlonjava-3.0.0.jar -Ilib t/Writer.t 2>&1 | grep -E '^(not )?ok ' > /tmp/jperl_results.txt

# Diff
diff /tmp/perl_results.txt /tmp/jperl_results.txt
```

For individual Perl constructs:
```bash
# System Perl
perl -e 'my @a = (1,2,3); $_ *= 2 foreach @a; print "@a\n"'

# PerlOnJava
java -jar target/perlonjava-3.0.0.jar -e 'my @a = (1,2,3); $_ *= 2 foreach @a; print "@a\n"'
```

For comparing `.failed` output files against `.out` reference files:
```bash
cd Image-ExifTool-13.44
diff t/Writer_11.out t/Writer_11.failed
```

## Environment Variables

### Compiler/Interpreter Control
| Variable | Effect |
|----------|--------|
| `JPERL_DISABLE_INTERPRETER_FALLBACK=1` | Disable bytecode interpreter fallback for large subs (force JVM compilation only) |
| `JPERL_SHOW_FALLBACK=1` | Print a message when a sub falls back to the bytecode interpreter |
| `JPERL_EVAL_NO_INTERPRETER=1` | Disable interpreter for `eval STRING` (force JVM compilation) |
| `JPERL_SPILL_SLOTS=N` | Set number of JVM spill slots (default 16) |

### Debugging/Tracing
| Variable | Effect |
|----------|--------|
| `JPERL_ASM_DEBUG=1` | Print JVM bytecode disassembly when ASM frame computation crashes |
| `JPERL_ASM_DEBUG_CLASS=<name>` | Filter ASM debug output to a specific generated class name |
| `JPERL_BYTECODE_SIZE_DEBUG=1` | Print bytecode size for each generated method |
| `JPERL_EVAL_VERBOSE=1` | Verbose error reporting for eval STRING compilation issues |
| `JPERL_EVAL_TRACE=1` | Trace eval STRING execution path (compile, interpret, fallback) |
| `JPERL_IO_DEBUG=1` | Trace file handle open/dup/write operations |
| `JPERL_STDIO_DEBUG=1` | Trace STDOUT/STDERR flush sequencing |
| `JPERL_REQUIRE_DEBUG=1` | Trace `require`/`use` module loading |
| `JPERL_TRACE_CONTROLFLOW=1` | Trace control flow detection (goto, return, last/next/redo safety) |
| `JPERL_DISASSEMBLE=1` | Disassemble generated bytecode (also `--disassemble` CLI flag) |

### Perl-level
| Variable | Effect |
|----------|--------|
| `JPERL_UNIMPLEMENTED=warn` | Downgrade unimplemented regex features from fatal to warning |

### Usage with jperl launcher
```bash
# Pass JVM options via JPERL_OPTS
JPERL_OPTS="-Xmx512m" ./jperl script.pl

# Combine env vars
JPERL_SHOW_FALLBACK=1 JPERL_EVAL_TRACE=1 java -jar target/perlonjava-3.0.0.jar -Ilib t/Writer.t 2>&1
```

## Test File Anatomy

ExifTool `.t` files follow a common pattern:
```perl
BEGIN { $| = 1; print "1..N\n"; require './t/TestLib.pm'; t::TestLib->import(); }
END { print "not ok 1\n" unless $loaded; }
use Image::ExifTool;
$loaded = 1;

# Read test: extract tags and compare against t/<TestName>_N.out
my $exifTool = Image::ExifTool->new;
my $info = $exifTool->ImageInfo('t/images/SomeFile.ext', @tags);
print 'not ' unless check($exifTool, $info, $testname, $testnum);
print "ok $testnum\n";

# Write test: modify tags and verify output
writeInfo($exifTool, 'src.jpg', 'tmp/out.jpg', \@setNewValue_args);

# Binary compare test: verify exact byte-for-byte match
binaryCompare('output.jpg', 't/images/original.jpg');
```

The `check()` function compares extracted tags against reference files `t/<TestName>_N.out`. Failed tests leave `t/<TestName>_N.failed` files for comparison. The `writeInfo()` function calls SetNewValue + WriteInfo.

## Debugging Workflow

1. **Run the failing test** and capture full output (stdout + stderr). Look for:
   - `not ok N` lines (which specific sub-tests fail)
   - Runtime exceptions / stack traces from Java
   - `Can't locate ...` (missing module)
   - `Undefined subroutine` / `Can't call method` errors

2. **Identify the failing sub-test number** and find it in the `.t` file. Map it to the ExifTool operation (read vs write, which image format, which tags).

3. **Check the `.out` vs `.failed` files** to understand the difference:
   ```bash
   diff t/Writer_11.out t/Writer_11.failed
   ```

4. **Compare with system Perl** to confirm it's a PerlOnJava issue, not a test environment issue.

5. **Isolate the Perl construct** causing the failure. Write a minimal reproducer:
   ```bash
   java -jar target/perlonjava-3.0.0.jar -e 'print pos("abc" =~ /b/g), "\n"'
   perl -e 'print pos("abc" =~ /b/g), "\n"'
   ```

6. **Trace into PerlOnJava source** to find the bug. Use `JPERL_SHOW_FALLBACK=1` to check if large subs are hitting the interpreter path.

7. **Fix in PerlOnJava**, rebuild (`make dev`), re-run the ExifTool test.

8. **Verify no regressions**: Run `make` (154 unit tests) and check `perl5_t/t/op/lexsub.t` (sensitive to block/sub emission changes).

## Interpreter Fallback Architecture

PerlOnJava has two compilation backends:
- **JVM backend** (default): Compiles Perl AST to JVM bytecode via ASM. Fast, but has a ~64KB method size limit.
- **Bytecode interpreter** (fallback): When a subroutine is too large for JVM (>N lines, typically ~500), it's compiled to PerlOnJava's own bytecode and interpreted. This includes `eval STRING` by default.

Key files for the interpreter:
- `BytecodeCompiler.java` — compiles AST to interpreter bytecode
- `BytecodeInterpreter.java` — executes interpreter bytecode
- `CompileAssignment.java` — assignment compilation for interpreter
- `Opcodes.java` — opcode definitions
- `InterpretedCode.java` — runtime representation of interpreter-compiled code

**Closure variables** are the main challenge for the interpreter fallback path. There are two distinct mechanisms:

1. **Inner named subs within the large sub**: These are compiled by SubroutineParser using the JVM compiler (via `compilerSupplier`). They get full closure support through `RETRIEVE_BEGIN_*` opcodes and `VariableCollectorVisitor.java`.

2. **The large sub itself accessing outer-scope `my` variables**: This is handled by `detectClosureVariables()` in `BytecodeCompiler.java`. It must:
   - Use `getAllVisibleVariables()` (TreeMap, sorted by register index) with the **exact same filtering** as `SubroutineParser` (skip `@_`, empty decl, fields, `&` refs) to ensure the capturedVars ordering matches `withCapturedVars()`.
   - Register captured variables in the compiler's **symbol table** via `addVariableWithIndex()` so that ALL variable resolution paths find them — not just `visit(IdentifierNode)`. This is critical because `handleHashElementAccess`, `handleArrayElementAccess`, hash slices, array slices, and assignment targets all have their own variable lookup logic that checks the symbol table.
   - Reserve registers (bump `nextRegister`) so local `my` declarations don't collide with captured variable registers.
   - Scan AST-referenced non-local variables and add them to `capturedVarIndices` for register recycling protection (prevents `getHighestVariableRegister()` from being too low).

**The runtime flow for captured variables in the interpreter path:**
1. `compileToInterpreter()` creates `BytecodeCompiler`, calls `compiler.compile(ast, ctx)` which runs `detectClosureVariables()` — this sets up `capturedVarIndices` (name→register mapping) used during bytecode generation
2. `compileToInterpreter()` creates placeholder `capturedVars` (all `RuntimeScalar`)
3. `SubroutineParser.withCapturedVars()` **replaces** the placeholder with actual values from `paramList` (built from `getAllVisibleVariables()` with same filtering)
4. At runtime, `BytecodeInterpreter.execute()` copies `capturedVars[i]` to `registers[3+i]` via `System.arraycopy`
5. The compiled bytecode accesses these registers for captured variable reads/writes

**Key invariant**: The ordering of variables in `detectClosureVariables()` MUST match `SubroutineParser`'s `paramList` ordering, because `capturedVars[i]` is copied to register `3+i` and the bytecode was compiled expecting specific variables at specific registers.

## Common Failure Patterns

### Infinite loops / TIMEOUT
- Often caused by `return` inside a block refactored by `LargeBlockRefactorer` into `sub { ... }->(@_)`. The `return` exits the anonymous sub instead of the enclosing function.
- Can also be caused by regex catastrophic backtracking.
- Use `timeout 120` to prevent hangs; `JPERL_SHOW_FALLBACK=1` to see if interpreter fallback is involved.

### Missing mandatory EXIF tags on write
- When creating EXIF, mandatory tags (YCbCrPositioning, ExifVersion, ComponentsConfiguration, ColorSpace) should be auto-created by `WriteExif.pl` using `%mandatory` hash.
- If these are missing, check that `%mandatory` is accessible (closure variable issue in interpreter fallback).

### Closure variable inaccessibility in interpreter
- File-scope `my %hash` / `my @array` not accessible inside large subs compiled by interpreter.
- Symptoms: tags silently missing from output, no error messages. Hash lookups return undef instead of the expected values.
- **Root cause pattern**: The bytecode compiler has MULTIPLE variable resolution paths (`visit(IdentifierNode)`, `handleHashElementAccess`, `handleArrayElementAccess`, hash/array slices, assignment LHS). If captured variables are only in `capturedVarIndices` but NOT in the compiler's symbol table, most access paths won't find them and fall through to global variable load (which returns an empty hash/array).
- **Fix**: `detectClosureVariables()` must call `symbolTable.addVariableWithIndex()` for each captured variable so all resolution paths find them.
- **Debugging**: Add `System.err.println` in `BytecodeInterpreter.execute()` after the `System.arraycopy` for capturedVars to verify the correct values are being passed at runtime. Also check the `handleHashElementAccess` code path to see if it reaches `LOAD_GLOBAL_HASH` (bad) vs `getVariableRegister` (good).

### XMP lang-alt writing failures
- Non-default language entries (`en`, `de`, `fr`) fail to be created in lang-alt lists.
- Related to `WriteXMP.pl` path tracking using `pos()` after `m//g` regex.

### pos() behavior after m//g
- `pos()` returning wrong value after global regex match can cause index tracking bugs in ExifTool's write logic.

### Foreach loop variable aliasing
- Postfix foreach (`EXPR foreach @list`) must alias `$_` to actual array elements for modification.
- Block-form and statement-modifier foreach have different code paths in `StatementParser.java` vs `StatementResolver.java`.

### Encoding / binary data issues
- ExifTool heavily uses `binmode`, `sysread`, `syswrite`, `pack`, `unpack`, `Encode::decode`/`encode`.
- BYTE_STRING vs STRING type propagation in concat operations can corrupt binary data.

### Read-only variable violations
- Operations that try to modify read-only scalars (e.g., `$_` aliased to a constant).

## Current Test Status (as of 2026-03-03)

### ExifTool Test Results: 590/600 planned (98%)

| Test | Pass/Planned | Status |
|------|-------------|--------|
| ExifTool.t | 35/35 | PASS |
| Writer.t | 59/61 | 2 fail (test 10: Pentax date fmt, test 46: XMP Audio data) |
| XMP.t | 44/54 | 10 fail |
| Geotag.t | 3/12 | 9 fail |
| PDF.t | 18/26 | 8 fail |
| QuickTime.t | 17/22 | 5 fail |
| CanonVRD.t | 19/24 | 5 fail |
| Nikon.t | 6/9 | 3 fail |
| CanonRaw.t | 5/9 | 3 fail + crash |
| Pentax.t | 1/4 | 3 fail |
| Panasonic.t | 2/5 | 3 fail |
| (72 other tests) | all pass | PASS |

### Fix Priority (by impact)

#### P1: Writer.t remaining failures (2 tests: Writer 10, 46)
- **Test 10**: Pentax MakerNotes date `2008:03:02` becomes `2008:0:0`, time `12:01:23` becomes `12:0:0`. Binary date decoding issue — likely `pack`/`unpack` or BCD decode in Pentax.pm. Also has a float rounding diff (`13.2` vs `13.3`).
- **Test 46**: Missing `[XMP, XMP-GAudio, Audio] Data - Audio Data: (Binary data 1 bytes)` in output. An XMP Audio binary data tag is not being written/preserved.

#### RESOLVED: Writer.t closure variable fix (previously P1, 15 tests fixed)
The `%mandatory` and `%crossDelete` hashes in `WriteExif.pl` are file-scope `my` variables accessed inside the large `WriteExif` sub (compiled by interpreter fallback). Fixed by registering captured variables in the compiler's symbol table via `addVariableWithIndex()` in `detectClosureVariables()`. This fixed Writer tests 6,7,11,13,19,25-28,35,38,42,48,53,55.

#### P2: Geotag date/time computation (9 tests: Geotag 2,4,6-12)
All geotag tests except module loading and 2 others fail. All use `Time::Local` for date arithmetic and GPS coordinate interpolation. Likely one root cause in date string parsing or timezone offset calculation. Compare `Geotag_2.out` vs `Geotag_2.failed` to see if GPS coordinates are wrong or dates are wrong.

#### P3: XMP lang-alt writing (5 tests: XMP 13,17,26,51,52)
Writing non-default language entries to XMP lang-alt lists fails silently. Only `x-default` works. The write path in `WriteXMP.pl` uses `pos()` after `m//g` for path tracking. Test with:
```bash
perl -e '"a/b/c" =~ m|/|g; print pos(), "\n"'  # should print 2
java -jar target/perlonjava-3.0.0.jar -e '"a/b/c" =~ m|/|g; print pos(), "\n"'
```

#### P4: XMP lang-alt Bag index tracking (3 tests: XMP 36,38,50)
Values assigned to wrong bag items; empty strings dropped from lists. Also likely `pos()` related. Test 36 specifically loses an empty string as first list element.

#### P5: PDF write/revert cycle (8 tests: PDF 7-12,25,26)
Tests 7-12 are sequential edit/revert operations on a PDF — one failure cascades. Tests 25-26 are AES encryption (require `Digest::SHA`). Investigate test 7 first as it's the cascade root.

#### P6: QuickTime write failures (5 tests: QuickTime 11-13,18,20)
HEIC write failures and VideoKeys/AudioKeys extraction. Lower priority — likely format-specific issues.

#### P7: Other write failures (CanonVRD 5, Nikon 3, Pentax 3, Panasonic 3, etc.)
Various format-specific write issues. Many may share root causes with P1 (mandatory EXIF tags).

## Key Source Files Quick Reference

| Area | File |
|------|------|
| Bytecode compiler | `backend/bytecode/BytecodeCompiler.java` |
| Bytecode interpreter | `backend/bytecode/BytecodeInterpreter.java` |
| Assignment compilation (interp) | `backend/bytecode/CompileAssignment.java` |
| Variable collector (closures) | `backend/bytecode/VariableCollectorVisitor.java` |
| Opcodes | `backend/bytecode/Opcodes.java` |
| Block emission (JVM) | `backend/jvm/EmitBlock.java` |
| Subroutine emission (JVM) | `backend/jvm/EmitSubroutine.java` |
| Foreach emission (JVM) | `backend/jvm/EmitForeach.java` |
| Eval handling (JVM) | `backend/jvm/EmitEval.java` |
| Method creator / fallback | `backend/jvm/EmitterMethodCreator.java` |
| Large block refactoring | `backend/jvm/LargeBlockRefactorer.java` |
| Control flow safety | `frontend/analysis/ControlFlowDetectorVisitor.java` |
| Statement parser (block foreach) | `frontend/parser/StatementParser.java` |
| Statement resolver (postfix foreach) | `frontend/parser/StatementResolver.java` |
| Subroutine parser | `frontend/parser/SubroutineParser.java` |
| Runtime scalar | `runtime/runtimetypes/RuntimeScalar.java` |
| Runtime array | `runtime/runtimetypes/RuntimeArray.java` |
| Runtime hash | `runtime/runtimetypes/RuntimeHash.java` |
| Dynamic variables | `runtime/runtimetypes/DynamicVariableManager.java` |
| IO operations | `runtime/runtimetypes/RuntimeIO.java` |
| IO operator (open/dup) | `runtime/operators/IOOperator.java` |
| Control flow (goto/labels) | `backend/jvm/EmitControlFlow.java` |
| Dereference / slicing | `backend/jvm/Dereference.java` |
| Variable emission (refs) | `backend/jvm/EmitVariable.java` |
| String parser (qw, heredoc) | `frontend/parser/StringParser.java` |
| String operators | `runtime/operators/StringOperators.java` |
| Pack/Unpack | `runtime/operators/PackOperator.java` |
| Regex preprocessor | `runtime/regex/RegexPreprocessor.java` |
| Regex runtime | `runtime/regex/RuntimeRegex.java` |
| Module loading | `runtime/operators/ModuleOperators.java` |

All paths relative to `src/main/java/org/perlonjava/`.

## Lessons Learned (Debugging Pitfalls)

### Register recycling inflation
The HEAD code's AST-based `detectClosureVariables` populated `capturedVarIndices` with ~321 entries, which inflated `getHighestVariableRegister()` and prevented aggressive register recycling. A no-op version (removing all capturedVarIndices) dropped Writer.t from 44/61 to 26/61 — not because of closure access, but because register recycling became too aggressive. When modifying `detectClosureVariables`, always ensure `capturedVarIndices` has enough entries to keep `getHighestVariableRegister()` high enough to prevent register corruption.

### Multiple variable resolution paths
The bytecode compiler resolves variables in MANY separate code paths:
- `visit(IdentifierNode)` — checks `capturedVarIndices` then symbol table
- `handleHashElementAccess` — checks closure vars, symbol table, then global
- `handleArrayElementAccess` — same pattern
- `handleHashSlice`, `handleArraySlice`, `handleHashKeyValueSlice` — same
- Assignment targets in `CompileAssignment.java` — same pattern
- Various places in `CompileOperator.java`

If a fix only patches ONE of these paths (e.g., `capturedVarIndices` check in `visit(IdentifierNode)`), hash/array access will still fall through to globals. The correct fix is to register captured variables in the **symbol table** so ALL paths find them.

### Ordering matters for capturedVars
`SubroutineParser` builds `paramList` by iterating `getAllVisibleVariables()` (TreeMap sorted by register index) with specific filters. `detectClosureVariables()` must use the **exact same iteration order and filters**. Any mismatch causes captured variable values to be assigned to wrong registers at runtime.

### goto LABEL across JVM scope boundaries
`EmitControlFlow.handleGotoLabel()` resolves labels at compile time within the current JVM scope. When the target label is outside the current scope (e.g., goto inside a `map` block to a label outside, or goto inside an `eval` block), the compile-time lookup fails. The fix is to emit a `RuntimeControlFlowList` marker with `ControlFlowType.GOTO` at runtime (the same mechanism used by dynamic `goto EXPR`), allowing the goto signal to propagate up the call stack. This was a blocker for both op/array.t and op/eval.t.

### List slice with range indices
In `Dereference.handleArrowArrayDeref()`, the check for single-index vs slice path must account for range expressions (`..` operator). A range like `0..5` is a single AST node but produces multiple indices. The correct condition is: use single-index path only if there's one element AND it's not a range. Otherwise, use the slice path. The old code had a complex `isArrayLiteral` check that was too restrictive.

### qw() backslash processing
`StringParser.parseWordsString()` must apply single-quote backslash rules to each word: `\\` → `\` and `\delimiter` → `delimiter`. Without this, backslashes are doubled in the output. The processing uses the closing delimiter from the qw construct.

### `\(LIST)` must flatten arrays before creating refs
`\(@array)` should create individual scalar refs to each array element (like `map { \$_ } @array`), not a single ref to the array. `EmitVariable` needs a `flattenElements()` method that detects `@` sigil nodes in the list and flattens them before creating element references.

### Squashing a diverged branch with `git diff` + `git apply`
When a feature branch has diverged far from master (thousands of commits in common history), both `git rebase` and `git merge --squash` can produce massive conflicts across dozens of files. The clean workaround:
```bash
# 1. Generate a patch of ONLY the branch's changes vs master
git diff master..feature-branch > /tmp/branch-diff.patch
# 2. Create a fresh branch from current master
git checkout master && git checkout -b feature-branch-clean
# 3. Apply the patch (no merge history = no conflicts)
git apply /tmp/branch-diff.patch
# 4. Commit as a single squashed commit
git add -A && git commit -m "Squashed: ..."
# 5. Force push to update the PR
git push --force origin feature-branch-clean
```
This works because `git diff master..branch` produces the exact file-level delta, bypassing all the intermediate merge history that causes conflicts.

### Always commit fixes before rebasing
Uncommitted working tree changes are lost when `git rebase --abort` is run. If you have a fix in progress (e.g., a BitwiseOperators change), commit it first — even as a WIP commit — before attempting any rebase. The rebase abort restores the branch to its pre-rebase state, which does NOT include uncommitted changes.

### `getInt()` vs `(int) getLong()` for 32-bit integer wrapping
`RuntimeScalar.getInt()` clamps DOUBLE values to `Integer.MAX_VALUE` (e.g., `(int) 2147483648.0 == 2147483647`). But `(int) getLong()` wraps correctly via long→int truncation (e.g., `(int) 2147483648L == -2147483648`). For `use integer` operations where Config.pm reports `ivsize=4`, always use `(int) getLong()` to get proper 32-bit wrapping behavior matching Perl's semantics.

### scalar gmtime/localtime ctime(3) format
Perl's scalar `gmtime`/`localtime` returns ctime(3) format: `"Fri Mar  7 20:13:52 881"` — NOT RFC 1123 (`"Fri, 7 Mar 0881 20:13:52 GMT"`). Use `String.format()` with explicit field widths, not `DateTimeFormatter`. Also: wday must use `getValue() % 7` (Perl: 0=Sun..6=Sat) not `getValue()` (Java: 1=Mon..7=Sun). Large years (>9999) must not crash the formatter.

### Regression testing: always compare branch vs master
Before declaring a fix complete, run the same test on both master and the branch to distinguish real regressions from pre-existing failures. Use `perl5_t/t/` (not `perl5/t/`) for running Perl5 core tests — the `perl5_t` copy has test harness files (`test.pl`, `charset_tools.pl`) that PerlOnJava can load.

## Adding Debug Instrumentation

In ExifTool Perl code (temporary, never commit):
```perl
print STDERR "DEBUG: variable=$variable\n";
```

In PerlOnJava Java code (temporary, never commit):
```java
System.err.println("DEBUG: value=" + value);
```

To trace which subs hit interpreter fallback:
```bash
JPERL_SHOW_FALLBACK=1 java -jar target/perlonjava-3.0.0.jar -Ilib t/Writer.t 2>&1 | grep FALLBACK
```

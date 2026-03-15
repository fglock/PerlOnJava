---
name: interpreter-parity
description: Debug and fix interpreter vs JVM backend parity issues in PerlOnJava
argument-hint: "[test-name, error message, or Perl construct]"
triggers:
  - user
  - model
---

## ⚠️⚠️⚠️ CRITICAL: NEVER USE `git stash` ⚠️⚠️⚠️

**DANGER: Changes are SILENTLY LOST when using git stash/stash pop!**

- NEVER use `git stash` to temporarily revert changes
- INSTEAD: Commit to a WIP branch or use `git diff > backup.patch`
- This warning exists because completed work was lost during debugging

# Interpreter/JVM Backend Parity Debugging

You are fixing cases where PerlOnJava's bytecode interpreter produces different results than the JVM compiler backend. The interpreter should be a drop-in replacement — same parsing, same runtime APIs, different execution engine.

## Git Workflow

**IMPORTANT: Never push directly to master. Always use feature branches and PRs.**

**IMPORTANT: Always commit changes BEFORE switching branches.** Use `git diff > backup.patch` to save uncommitted work, or commit to a WIP branch. Never use `git stash` — changes can be silently lost.

```bash
git checkout -b fix/interpreter-issue-name
# ... make changes ...
git push origin fix/interpreter-issue-name
gh pr create --title "Fix interpreter: description" --body "Details"
```

## Project Layout

- **PerlOnJava source**: `src/main/java/org/perlonjava/` (compiler, bytecode interpreter, runtime)
- **Unit tests**: `src/test/resources/unit/*.t` (155 tests, run via `make`)
- **Fat JAR**: `target/perlonjava-3.0.0.jar`
- **Launcher script**: `./jperl`

## Building

**ALWAYS use `make` commands. NEVER use raw mvn/gradlew commands.**

| Command | What it does |
|---------|--------------|
| `make` | Build + run all unit tests (use before committing) |
| `make dev` | Build only, skip tests (for quick iteration during debugging) |
| `make test-interpreter` | Run unit tests with interpreter backend |

```bash
make                  # Standard build - compiles and runs tests
make dev              # Quick build - compiles only, NO tests
make test-interpreter # Test interpreter backend specifically
```

## Running in Interpreter Mode

### CLI flag (top-level only without global flag)
```bash
./jperl --interpreter script.pl
./jperl --interpreter -e 'print "hello\n"'
./jperl --interpreter --disassemble -e 'code'   # Show interpreter bytecode
```

### Environment variable (global, affects require/do/eval)
```bash
JPERL_INTERPRETER=1 ./jperl script.pl
```

### Comparing backends
```bash
# JVM backend
./jperl -e 'code'
# Interpreter backend
JPERL_INTERPRETER=1 ./jperl -e 'code'
```

**CRITICAL: eval STRING uses interpreter by default!**
Even when running with JVM backend, `eval STRING` compiles code with the interpreter.
This means interpreter bugs can cause test failures even without `--interpreter`.

To trace eval STRING execution:
```bash
JPERL_EVAL_TRACE=1 ./jperl script.pl 2>&1 | grep -i interpreter
```

Fallback for large subs (`JPERL_SHOW_FALLBACK=1`) does NOT show eval STRING usage.
One-liners won't trigger fallback - test with actual test files!

## Architecture: Two Backends, Shared Everything Else

```
Source → Lexer → Parser → AST ─┬─→ JVM Compiler (EmitterMethodCreator) → JVM bytecode
                                └─→ BytecodeCompiler → InterpretedCode → BytecodeInterpreter
```

Both backends:
- Share the same parser (same AST)
- Call identical runtime methods (MathOperators, StringOperators, RuntimeScalar, etc.)
- Use GlobalVariable for package variables
- Use RuntimeCode.apply() for subroutine dispatch

The difference is ONLY in how the AST is lowered to executable form.

## Key Source Files

| Area | File | Notes |
|------|------|-------|
| Interpreter compiler | `backend/bytecode/BytecodeCompiler.java` | AST → interpreter bytecode |
| Interpreter executor | `backend/bytecode/BytecodeInterpreter.java` | Main dispatch loop |
| Interpreter code object | `backend/bytecode/InterpretedCode.java` | Extends RuntimeCode, holds bytecode + disassembler |
| Opcodes | `backend/bytecode/Opcodes.java` | Opcode constants (keep contiguous!) |
| Slow ops | `backend/bytecode/SlowOpcodeHandler.java` | Rarely-used operation handlers |
| Extended ops | `backend/bytecode/OpcodeHandlerExtended.java` | CREATE_CLOSURE, STORE_GLOB, etc. |
| JVM compiler | `backend/jvm/EmitterMethodCreator.java` | AST → JVM bytecode |
| JVM subroutine emit | `backend/jvm/EmitSubroutine.java` | Named/anon sub compilation (JVM) |
| Compilation router | `app/scriptengine/PerlLanguageProvider.java` | `compileToExecutable()` picks backend |
| Global interp flag | `runtime/runtimetypes/RuntimeCode.java` | `USE_INTERPRETER` static boolean |
| CLI flag handling | `app/cli/ArgumentParser.java` | `--interpreter` sets global flag |
| Module loading | `runtime/operators/ModuleOperators.java` | `require`/`do` propagates interpreter flag |
| Subroutine parser | `frontend/parser/SubroutineParser.java` | Named sub compilation, prototype checks |
| Special blocks | `frontend/parser/SpecialBlockParser.java` | BEGIN/END/CHECK/INIT block handling |

All paths relative to `src/main/java/org/perlonjava/`.

## How --interpreter Propagates

1. `ArgumentParser.java`: Sets `parsedArgs.useInterpreter = true` AND `RuntimeCode.setUseInterpreter(true)` (global flag)
2. `ModuleOperators.java`: When loading files via `require`/`do`, copies `RuntimeCode.USE_INTERPRETER` to new `CompilerOptions`
3. `SpecialBlockParser.java`: BEGIN blocks clone `parser.ctx.compilerOptions` (inherits `useInterpreter`)
4. `PerlLanguageProvider.compileToExecutable()`: Checks `ctx.compilerOptions.useInterpreter` to pick backend

## Common Parity Issues

### 1. Missing metadata on InterpretedCode

**Pattern**: The JVM backend sets metadata (prototype, attributes) on RuntimeCode objects via EmitSubroutine, but BytecodeCompiler doesn't.

**Example**: Anonymous sub `sub() { 1 }` — JVM backend uses `node.prototype` at EmitSubroutine.java:198. BytecodeCompiler.visitAnonymousSubroutine must also set `subCode.prototype = node.prototype`.

**Detection**: Parser disambiguation fails — e.g., `FOO ?` parsed as regex instead of ternary because `subExists` is false (requires `prototype != null`).

**Files to check**:
- `BytecodeCompiler.visitAnonymousSubroutine()` — must copy `node.prototype` and `node.attributes` to InterpretedCode
- `InterpretedCode.withCapturedVars()` — must preserve prototype/attributes/subName/packageName when creating closure copies
- `OpcodeHandlerExtended.executeCreateClosure()` — must use `withCapturedVars()` not raw constructor

### 2. Type mismatches (RuntimeList vs RuntimeScalar)

**Pattern**: Method calls (`->can()`, `->method()`) return RuntimeList. The JVM backend calls `.scalar()` on the result. The interpreter's STORE_GLOB expects RuntimeScalar.

**Detection**: `ClassCastException: RuntimeList cannot be cast to RuntimeScalar` at `BytecodeInterpreter.java` STORE_GLOB handler.

**Fix**: The BytecodeCompiler must emit a `LIST_TO_COUNT` or similar scalar-context conversion before STORE_GLOB when the RHS is a method call.

### 3. Missing opcode implementations

**Pattern**: The JVM backend handles a Perl construct via a Java method call in generated bytecode. The interpreter has no corresponding opcode or emitter case.

**Detection**: "Unknown opcode" errors, or silent wrong results.

**Fix**: Add opcode to Opcodes.java, handler to BytecodeInterpreter.java, emitter case to BytecodeCompiler.java, disassembly case to InterpretedCode.java. Keep opcodes contiguous for tableswitch optimization.

### 4. Context propagation differences

**Pattern**: The JVM backend propagates scalar/list/void context through the EmitterContext. The BytecodeCompiler may not propagate context correctly for all node types.

**Detection**: Operations return wrong type (list where scalar expected, or vice versa). Array in scalar context returns element instead of count.

### 5. BEGIN block compilation path

**Pattern**: BEGIN blocks are compiled and executed during parsing via `SpecialBlockParser` → `executePerlAST` → `compileToExecutable`. The BEGIN code runs BEFORE the rest of the file is parsed. Side effects (like registering subs via `*FOO = sub() { 1 }`) must be visible to the parser for subsequent code.

**Key flow**:
1. Parser encounters `BEGIN { ... }`
2. SpecialBlockParser clones compilerOptions (inherits useInterpreter)
3. `executePerlAST` compiles the BEGIN block code (may use interpreter)
4. BEGIN block executes — side effects are immediate
5. Parser continues parsing rest of file — sees BEGIN's side effects

**Issues**: If BEGIN creates a constant sub but the InterpretedCode has null prototype, the parser won't recognize it as a known sub, causing disambiguation failures.

## Debugging Workflow

### CRITICAL: Save Master Baselines ONCE, Don't Rebuild Repeatedly

**Save master baseline to files FIRST** (do this once per debugging session):
```bash
# Save your current work first (NEVER use git stash!)
git diff > /tmp/my-changes.patch  # Save uncommitted changes
git add -A && git commit -m "WIP: save work before baseline check"  # Or commit to WIP

# Switch to master and build
git checkout master
make dev

# Save master test output for JVM backend
cd perl5_t/t && ../../jperl re/subst.t 2>&1 > /tmp/master_subst.log
grep "^not ok" /tmp/master_subst.log > /tmp/master_subst_fails.txt

# ALSO save interpreter baseline!
cd perl5_t/t && ../../jperl --interpreter re/subst.t 2>&1 > /tmp/master_subst_interp.log

# Switch back to feature branch
git checkout feature-branch
# Restore uncommitted changes if you used patch:
# git apply /tmp/my-changes.patch
```

**After making changes**, compare against saved baselines:
```bash
make dev

# Test JVM backend
cd perl5_t/t && ../../jperl re/subst.t 2>&1 > /tmp/feature_subst.log
diff /tmp/master_subst_fails.txt <(grep "^not ok" /tmp/feature_subst.log)

# MUST ALSO test with interpreter!
cd perl5_t/t && ../../jperl --interpreter re/subst.t 2>&1 > /tmp/feature_subst_interp.log
```

### CRITICAL: Always Test with BOTH Backends

A fix that works for JVM backend may break interpreter, or vice versa.

**For quick tests (one-liners):**
```bash
./jperl -e 'test code'               # JVM backend
./jperl --interpreter -e 'test code' # Interpreter backend
```

**For test files (use env var so require/do/eval also use interpreter):**
```bash
./jperl test.t                        # JVM backend
JPERL_INTERPRETER=1 ./jperl test.t    # Interpreter backend (full)
```

### 1. Reproduce with minimal code
```bash
# Find the failing construct
JPERL_INTERPRETER=1 ./jperl -e 'failing code'
# Compare with JVM backend
./jperl -e 'failing code'
```

**CRITICAL: Save baselines to files!** When comparing test suites across branches:
```bash
# On master - save results so you don't have to rebuild later
git checkout master && make dev
cd perl5_t/t && JPERL_INTERPRETER=1 ../../jperl test.t 2>&1 | tee /tmp/test_master.log
JPERL_INTERPRETER=1 ../../jperl test.t 2>&1 | grep "^ok\|^not ok" > /tmp/test_master_results.txt
grep "^ok" /tmp/test_master_results.txt | wc -l  # Save this number!

# Return to feature branch - now you can compare without rebuilding master
git checkout feature-branch && make dev
```

### 2. Use --disassemble to see interpreter bytecode
```bash
JPERL_INTERPRETER=1 ./jperl --disassemble -e 'code' 2>&1
```

### 3. Check the bytecode around the crash
Error messages include: `[opcodes at pc-3..pc: X Y Z >>>W <<< ...]`
- Decode opcodes using `Opcodes.java` constants
- The `>>>W<<<` is the failing opcode

### 4. Add targeted debug prints
```java
// In BytecodeInterpreter.java, around the failing opcode:
System.err.println("DEBUG opcode=" + opcode + " rd=" + rd + " type=" + registers[rd].getClass().getName());
```

### 5. Trace through both backends
Compare what the JVM backend emits (via `--disassemble` without `--interpreter`) vs what the BytecodeCompiler emits (with `--interpreter --disassemble`).

## Environment Variables

| Variable | Effect |
|----------|--------|
| `JPERL_INTERPRETER=1` | Force interpreter mode globally (require/do/eval) |
| `JPERL_EVAL_USE_INTERPRETER=1` | Force interpreter only for eval STRING |
| `JPERL_EVAL_VERBOSE=1` | Verbose error reporting for eval compilation |
| `JPERL_DISASSEMBLE=1` | Disassemble generated bytecode |
| `JPERL_SHOW_FALLBACK=1` | Show when subs fall back to interpreter |

## Test Infrastructure

### make test-interpreter
Runs all 155 unit tests with `JPERL_INTERPRETER=1`. Uses `perl dev/tools/perl_test_runner.pl`.

Output categories:
- `! 0/0 ok` — Test errored out completely (no TAP output). Usually means module loading failed.
- `X/Y ok` with checkmark — All tests passed.
- `X/Y ok` with X — Some tests failed.

### Feature impact analysis
The test runner reports which "features" (modules, prototypes, regex, objects) block the most tests. This helps prioritize fixes.

### Current blockers (as of 2026-03-03)
152/155 tests fail because `use Test::More` fails to load. The chain is:
```
Test::More → Test::Builder → Test::Builder::Formatter → Test2::Formatter::TAP
```
The failure is a ClassCastException in `Test/Builder/Formatter.pm` BEGIN block where `*OUT_STD = Test2::Formatter::TAP->can('OUT_STD')` — method call result (RuntimeList) is stored to glob (expects RuntimeScalar).

## Design Decision: JVM Emitter Must Not Mutate the AST

When the JVM backend fails with `MethodTooLargeException` (or `VerifyError`, etc.), `createRuntimeCode()` in `EmitterMethodCreator.java` falls back to the interpreter via `compileToInterpreter(ast, ...)`. The same fallback exists in `PerlLanguageProvider.compileToExecutable()`.

**Problem**: The JVM emitter (EmitterVisitor and helpers) mutates the AST during code generation. If JVM compilation fails partway through, the interpreter receives a corrupted AST, producing wrong results. This is the root cause of mixed-mode failures (e.g., pack.t gets 45 extra failures when the main script falls back to interpreter after partial JVM emission).

**Rule**: The JVM emitter must NEVER permanently mutate AST nodes. All mutations must either:
1. Be avoided entirely (work on local copies), OR
2. Use save/restore in try/finally (already done in `EmitLogicalOperator.java`)

### Known AST mutation sites

| File | Line(s) | What it mutates | Status |
|------|---------|-----------------|--------|
| `EmitOperator.java` | ~373 | `operand.elements.addFirst(operand.handle)` in `handleSystemBuiltin` — adds handle to elements list, never removed | **DANGEROUS** |
| `Dereference.java` | ~347,442,511,579,911 | `nodeRight.elements.set(0, new StringNode(...))` — converts IdentifierNode to StringNode for hash autoquoting. `nodeRight` comes from `asListNode()` which creates a new ListNode but shares the same `elements` list | **DANGEROUS** — mutates shared elements list |
| `EmitLogicalOperator.java` | ~188,300,340 | Temporarily rewrites `declaration.operator`/`.operand` | **SAFE** — uses save/restore in try/finally |
| `EmitControlFlow.java` | ~280 | `argsNode.elements.add(atUnderscore)` | **SAFE** — `argsNode` is a freshly created ListNode |
| `EmitOperator.java` | ~398,410 | `handleSpliceBuiltin` removes/restores first element | **SAFE** — uses try/finally restore |
| Annotations (`setAnnotation`) | various | Sets `blockIsSubroutine`, `skipRegexSaveRestore`, `isDeclaredReference` | **Likely safe** — annotations are additive hints, but verify interpreter handles them |

### How to fix dangerous sites

**`handleSystemBuiltin` (EmitOperator.java:373)**: Wrap in try/finally to remove the added element after accept():
```java
if (operand.handle != null) {
    hasHandle = true;
    operand.elements.addFirst(operand.handle);
}
try {
    operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
} finally {
    if (hasHandle) {
        operand.elements.removeFirst();
    }
}
```

**Dereference.java autoquoting**: `asListNode()` creates a new ListNode but passes the SAME `elements` list reference. The `elements.set(0, ...)` call mutates the original HashLiteralNode's elements. Fix by either:
- Making `asListNode()` copy the elements list: `new ListNode(new ArrayList<>(elements), tokenIndex)`
- Or saving/restoring the original element in try/finally

## Lessons Learned

### InterpretedCode constructor drops metadata
The `InterpretedCode` constructor calls `super(null, new ArrayList<>())` — always null prototype. Any metadata (prototype, attributes, subName, packageName) must be set AFTER construction.

### withCapturedVars creates a new object
`InterpretedCode.withCapturedVars()` creates a fresh InterpretedCode. It must copy all metadata fields from the original. The CREATE_CLOSURE opcode at runtime uses this method.

### Closure detection is aggressive
`collectVisiblePerlVariables()` in BytecodeCompiler captures ALL visible `my` variables, even if the anonymous sub doesn't reference them. This means `sub() { 1 }` inside a scope with `my $x` will go through CREATE_CLOSURE instead of LOAD_CONST. The closure copy must preserve metadata.

### Parser disambiguation depends on RuntimeCode fields
`SubroutineParser.java:172-184` checks `existsGlobalCodeRef(fullName)` and then requires one of: `methodHandle != null`, `compilerSupplier != null`, `isBuiltin`, `prototype != null`, or `attributes != null`. In interpreter mode, InterpretedCode often has none of these set (methodHandle is null, prototype is null). The parser then treats the bareword as unknown, causing `FOO ?` to be parsed as regex instead of ternary.

### STORE_GLOB expects RuntimeScalar
`BytecodeInterpreter.java` line 1508: `((RuntimeGlob) registers[globReg]).set((RuntimeScalar) registers[valueReg])`. If the value register contains a RuntimeList (from a method call), this throws ClassCastException. The BytecodeCompiler must ensure scalar context for glob assignment RHS.

### Opcode contiguity is critical
JVM uses tableswitch (O(1)) for dense opcode ranges. Gaps cause lookupswitch (O(log n)) — 10-15% performance hit. Always use sequential opcode numbers. Run `dev/tools/check_opcodes.pl` after changes.

### Disassembly cases are mandatory
Every new opcode MUST have a disassembly case in InterpretedCode.java. Missing cases cause PC misalignment — the disassembler doesn't advance past the opcode's operands, corrupting all subsequent output.

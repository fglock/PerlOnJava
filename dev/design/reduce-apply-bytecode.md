# Design: Reduce Generated `apply()` Method Bytecode Size

## Motivation

`t/96_is_deteministic_value.t` (DBIx::Class) times out under CPU pressure from
competing JVM processes. The root cause is a cascade:

1. CPU starvation → more time in interpreted mode before methods become hot
2. SQL::Abstract `_expand_expr` closures become hot → submitted to C1 JIT
3. C1 fails: **"out of virtual registers in linear scan"** (compileIds 4980, 6224)
4. Hot path runs permanently in interpreter mode, ~50× slower
5. Test takes 200+ seconds instead of 7 seconds → TAP harness SIGKILL

The C1 failure is triggered by the SIZE and LIVE-INTERVAL COUNT of the generated
`apply()` methods. The target is to bring all generated methods under **~2,000
bytes** of bytecode, down from the current maximum of **9,377 bytes**.

See also: `dev/design/dbixclass-timeout-jit-analysis.md`

---

## Measured Baseline

Running `JPERL_BYTECODE_SIZE_DEBUG=1` on the DBIx::Class test:

```
Total apply() methods compiled: 980
Largest apply() method:          9,377 bytes
5th largest:                     6,678 bytes
20th largest:                    2,594 bytes
Median:                          ~800 bytes
```

Simple sub `sub foo { my $x = shift; return $x + 1 }`:

```
Total apply() bytecode: 886 bytes
```

ASM disassembly of that simple sub's `apply()` shows:

```
; === PROLOGUE: temp local pre-initialization ===
ACONST_NULL ; \
ASTORE 3    ;  | repeated for slots 3..258
ACONST_NULL ;  |   = 256 pairs
ASTORE 4    ; /
...         ;   (truncated)

; === INFRASTRUCTURE OVERWRITE ===
ACONST_NULL
ASTORE 3    ; tailCallCodeRefSlot
ACONST_NULL
ASTORE 4    ; tailCallArgsSlot
ACONST_NULL
ASTORE 5    ; controlFlowTempSlot
ICONST_0
ISTORE 6    ; controlFlowActionSlot
; 16 spill slots (slots 7..22): ACONST_NULL + ASTORE × 16

; === DYNAMIC VARIABLE SETUP ===
INVOKESTATIC DynamicVariableManager.getLocalLevel()
ISTORE 23                                    ; dynamicIndex

INVOKESTATIC RegexState.save()

ACONST_NULL
ASTORE 25                                    ; returnValueSlot

; === USER CODE (actual Perl logic) ===
; ... ~30 bytes for shift() + add + return

; === EPILOGUE ===
ASTORE 25                                    ; spill return value
GOTO returnLabel

; returnLabel:
ALOAD 25
INVOKEVIRTUAL RuntimeBase.getList()
ASTORE 24                                    ; returnListSlot
DUP
INVOKESTATIC RuntimeCode.materializeSpecialVarsInResult()
ALOAD 24
INVOKEVIRTUAL RuntimeList.isNonLocalGoto()
IFEQ normalReturn
; ... control flow handling ~25 bytes
normalReturn:
ILOAD 23
INVOKESTATIC DynamicVariableManager.popToLocalLevel()

ARETURN
```

**Bytecode breakdown for simple sub:**

| Section | Bytes | % |
|---------|-------|---|
| Temp local pre-init (256 slots) | ~773 | 87% |
| Infrastructure overwrites (tail call + spill) | ~65 | 7% |
| DVM + regex setup | ~15 | 2% |
| Actual Perl logic | ~25 | 3% |
| Epilogue (control flow check + teardown) | ~10 | 1% |
| **Total** | **886** | **100%** |

**The 256-slot pre-init block accounts for 87% of a simple sub's bytecode.**

For complex subs, the per-call-site TAILCALL trampoline becomes the dominant
factor: each call site emits ~80–130 bytes of control flow checking code.

---

## Root Cause Analysis of Bytecode Inflation

### Root Cause 1: Oversized Pre-Init Buffer (primary driver)

`EmitterMethodCreator.java` lines 596–604:

```java
int preInitTempLocalsStart = ctx.symbolTable.getCurrentLocalVariableIndex();
TempLocalCountVisitor tempCountVisitor = new TempLocalCountVisitor();
ast.accept(tempCountVisitor);
int preInitTempLocalsCount = tempCountVisitor.getMaxTempCount() + 256;
for (int i = preInitTempLocalsStart; i < preInitTempLocalsStart + preInitTempLocalsCount; i++) {
    mv.visitInsn(Opcodes.ACONST_NULL);
    mv.visitVarInsn(Opcodes.ASTORE, i);
}
```

`TempLocalCountVisitor` counts only 5 AST patterns:

| Pattern | Where counted |
|---------|---------------|
| `&&`, `\|\|`, `//` (short-circuit) | BinaryOperatorNode |
| `->` (dereference) | BinaryOperatorNode |
| `for` (foreach) | For1Node |
| `local` | OperatorNode |
| `eval` | OperatorNode |

It **does not** count temps allocated by dozens of other emission paths in
`EmitterVisitor`, `EmitSubroutine`, `EmitBlock`, `EmitControlFlow`, etc.
The `+ 256` is a catch-all safety buffer for those uncounted allocations.

**Each pre-init pair is 3 bytes** (ACONST_NULL + ASTORE N for N < 256).
Reducing the buffer from 256 to 32 saves **224 × 3 = 672 bytes per method**.
With 980 methods in the DBIx::Class test: **658 KB** less bytecode total.

### Root Cause 2: Per-Call-Site TAILCALL Trampoline

Every Perl sub call site (in `EmitSubroutine.java` lines 725–870) emits a full
inline trampoline for handling `goto &sub` tail calls:

```asm
; === ~80–130 bytes per call site ===
ASTORE     controlFlowTempSlot          ; save result
ALOAD      controlFlowTempSlot
INVOKEVIRTUAL RuntimeList.isNonLocalGoto()
IFEQ       notControlFlow               ; fast-path branch

ALOAD      controlFlowTempSlot
CHECKCAST  RuntimeControlFlowList
INVOKEVIRTUAL getControlFlowType()
INVOKEVIRTUAL ControlFlowType.ordinal()
ICONST_4                                ; TAILCALL.ordinal() = 4
IF_ICMPNE  notTailcall

tailcallLoop:
  ALOAD      controlFlowTempSlot
  CHECKCAST  RuntimeControlFlowList
  DUP
  INVOKEVIRTUAL getTailCallCodeRef()
  ASTORE     tailCallCodeRefSlot
  INVOKEVIRTUAL getTailCallArgs()
  ASTORE     tailCallArgsSlot
  ALOAD      tailCallCodeRefSlot
  LDC        "tailcall"
  ALOAD      tailCallArgsSlot
  ILOAD      callContextSlot
  INVOKESTATIC RuntimeCode.apply(...)
  ASTORE     controlFlowTempSlot
  ALOAD      controlFlowTempSlot
  INVOKEVIRTUAL isNonLocalGoto()
  IFEQ       notControlFlow
  ALOAD      controlFlowTempSlot
  CHECKCAST  RuntimeControlFlowList
  INVOKEVIRTUAL getControlFlowType()
  INVOKEVIRTUAL ControlFlowType.ordinal()
  ICONST_4
  IF_ICMPEQ  tailcallLoop

notTailcall:
  GOTO       blockDispatcher
notControlFlow:
  ALOAD      controlFlowTempSlot       ; reload result onto stack
```

A method with 20 sub calls carries 20 × ~100 bytes = **2,000 bytes** of trampoline
code, even though `goto &sub` is a rare operation.

### Root Cause 3: Eval Block Overhead (secondary)

`useTryCatch=true` (eval blocks) adds 130–220 bytes of fixed overhead:
- `emitEvalDepthIncrement`: 4 inline instructions (could be 1 INVOKESTATIC)
- `emitEvalDepthDecrement`: 4 inline instructions × 2–3 occurrences
- `setGlobalVariable("main::@", "")`: 3 inline instructions × 2 occurrences
- Nested teardown try-catch for `defer` blocks: ~60 bytes

---

## Optimization Plan

### Phase 1 — Extend TempLocalCountVisitor (high impact, low risk)

**Goal**: Reduce the `+ 256` buffer to `+ 32` by making the visitor comprehensive.

**Impact estimate**: 672 bytes × 980 methods = **658 KB** saved in DBIx::Class run.
Largest methods shrink from 9,377 → ~8,700 bytes (modest; Phase 2 is needed for
large-method targets). Simple methods shrink from 886 → ~214 bytes (dramatic).

#### 1a. Audit all `allocateLocalVariable()` call sites

Every place that calls `ctx.symbolTable.allocateLocalVariable()` during AST
emission must be counted in the visitor. Known sites not yet tracked:

| Emitter | Method / Context | Temps allocated per occurrence |
|---------|-----------------|-------------------------------|
| `EmitSubroutine.emitSubCall()` | `callContextSlot` | 1 |
| `EmitSubroutine.emitSubCall()` | `codeRefSlot`, `nameSlot`, `argsArraySlot`, `argSlot` | 4 per call |
| `EmitSubroutine.emitSubCall()` | TAILCALL trampoline path | 0 (reuses method-level slots) |
| `EmitterVisitor` string ops | temp for lvalue deref | 1 per `->` (already counted) |
| `EmitControlFlow.handleReturnOperator()` | `tempSlot` for map/grep RETURN | 1 per `return` in map/grep |
| `EmitControlFlow.handleGotoLabel()` | various | 2–3 per goto |
| `EmitterVisitor` ternary, given/when | branch temp | 1 per ternary |
| `EmitterVisitor` chained comparisons | intermediate result | 1 per chain |
| `EmitterVisitor` string repetition `x` | count temp | 1 per `x` op |
| `EmitterVisitor` format/write | 2–3 | per format call |

Run this command to generate a complete list:

```bash
grep -rn "allocateLocalVariable\(\)" \
  src/main/java/org/perlonjava/backend/jvm/ \
  src/main/java/org/perlonjava/frontend/analysis/ \
  | grep -v TempLocalCountVisitor \
  | grep -v "spillSlots\|tailCallCodeRefSlot\|controlFlowTempSlot\|returnListSlot\|returnValueSlot\|dynamicIndex\|evalErrorSlot"
```

For each uncounted site, find the AST node type that triggers it and add a
`countTemp()` call to the corresponding `visit(XxxNode)` method in
`TempLocalCountVisitor`.

#### 1b. Reduce the safety buffer

After extending the visitor, run the DBIx::Class test suite with
`JPERL_DISABLE_INTERPRETER_FALLBACK=1` and progressively decrease the buffer:

```bash
# Phase 1b testing loop
for N in 128 64 32 16; do
  # Set buffer to N in EmitterMethodCreator.java line ~600:
  # int preInitTempLocalsCount = tempCountVisitor.getMaxTempCount() + N;
  make && timeout 60 ./jperl ...96_is_deteministic_value.t
done
```

A `VerifyError` means the visitor underestimates for some pattern — add that
pattern to the visitor and retry. The fallback already catches VerifyErrors, so
correctness is preserved even if a buffer of 32 is sometimes too small; but the
goal is to make 32 correct for all Perl code.

**Target**: Buffer of `+ 32` passes the full perl5 test suite.

#### 1c. Implementation

In `TempLocalCountVisitor.java`, extend each relevant `visit()` method.
Example additions:

```java
@Override
public void visit(OperatorNode node) {
    // existing: local, eval
    if ("local".equals(node.operator)) countTemp();
    if ("eval".equals(node.operator)) countTemp();

    // NEW: subroutine call emits callContextSlot + codeRefSlot
    if ("call".equals(node.operator) || "method_call".equals(node.operator)) {
        countTemp(); // callContextSlot
        countTemp(); // codeRefSlot  
        countTemp(); // nameSlot
        countTemp(); // argsArraySlot
        // argSlot is reused per argument, not accumulated
    }
    // NEW: ternary allocates result temp
    // Covered by TernaryOperatorNode.visit() below
    
    if (node.operand != null) node.operand.accept(this);
}

@Override
public void visit(TernaryOperatorNode node) {
    countTemp(); // result temp for merging branches
    if (node.condition != null) node.condition.accept(this);
    if (node.trueExpr != null) node.trueExpr.accept(this);
    if (node.falseExpr != null) node.falseExpr.accept(this);
}
```

Continue until the buffer can be safely set to 32.

---

### Phase 2 — Extract Per-Call-Site TAILCALL Trampoline (high impact, medium risk)

**Goal**: Replace the 80–130 byte per-call-site trampoline with a single static
helper invocation.

**Impact estimate**: 
- Methods with 10 calls: saves ~700 bytes
- Methods with 20 calls: saves ~1,400 bytes
- Brings the 9,377-byte maximum down to ~7,600 bytes
- Combined with Phase 1: ~6,900 bytes → well under C1's problematic range

#### 2a. New static helper in `RuntimeCode.java`

Add this method:

```java
/**
 * Resolve any pending TAILCALL markers in {@code result}, looping until
 * the call chain terminates or a non-TAILCALL marker is returned.
 *
 * <p>This is the runtime companion to the inline trampoline that is currently
 * emitted at every call site. Moving the loop here reduces generated bytecode
 * by ~80 bytes per Perl sub call.
 *
 * @param result      the RuntimeList returned by the most recent apply() call
 * @param callContext the call context (SCALAR / LIST / VOID) of the original site
 * @return  the final non-TAILCALL result, or a non-TAILCALL control-flow marker
 */
public static RuntimeList resolveTailCalls(RuntimeList result, int callContext) {
    while (result instanceof RuntimeControlFlowList cfList
            && cfList.getControlFlowType() == ControlFlowType.TAILCALL) {
        RuntimeScalar codeRef = cfList.getTailCallCodeRef();
        RuntimeArray  args    = cfList.getTailCallArgs();
        result = apply(codeRef, "tailcall", args, callContext);
    }
    return result;
}
```

This is semantically identical to the current inline trampoline loop, but lives
in `RuntimeCode` rather than being inlined at every call site.

#### 2b. Modify `EmitSubroutine.emitSubCall()`

Current code at lines 725–870 (simplified):

```java
// After the INVOKESTATIC RuntimeCode.apply(...) call:

mv.visitVarInsn(ASTORE, controlFlowTempSlot);
// ... 80-130 bytes of isNonLocalGoto check + tailcall loop + dispatcher jump ...
mv.visitVarInsn(ALOAD, controlFlowTempSlot);
```

Replace with:

```java
// After the INVOKESTATIC RuntimeCode.apply(...) call:
mv.visitVarInsn(Opcodes.ILOAD, callContextSlot);
mv.visitMethodInsn(Opcodes.INVOKESTATIC,
        "org/perlonjava/runtime/runtimetypes/RuntimeCode",
        "resolveTailCalls",
        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;I)"
        + "Lorg/perlonjava/runtime/runtimetypes/RuntimeList;",
        false);

// Now check for non-TAILCALL markers (LAST/NEXT/REDO/GOTO)
mv.visitVarInsn(Opcodes.ASTORE, controlFlowTempSlot);

mv.visitVarInsn(Opcodes.ALOAD, controlFlowTempSlot);
mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
        "org/perlonjava/runtime/runtimetypes/RuntimeList",
        "isNonLocalGoto",
        "()Z",
        false);
mv.visitJumpInsn(Opcodes.IFEQ, notControlFlow);

// Dispatch non-TAILCALL marker (LAST/NEXT/REDO/GOTO/RETURN)
mv.visitJumpInsn(Opcodes.GOTO, blockDispatcher);

mv.visitLabel(notControlFlow);
mv.visitVarInsn(Opcodes.ALOAD, controlFlowTempSlot);
```

**Per-call-site bytecode**:

| | Old | New |
|---|---|---|
| isNonLocalGoto check | 8 bytes | 8 bytes |
| TAILCALL trampoline loop | ~65 bytes | 0 bytes (in helper) |
| `resolveTailCalls` call | — | 5 bytes |
| Misc (stores/loads) | ~10 bytes | ~5 bytes |
| **Total** | **~83 bytes** | **~18 bytes** |

**Savings: ~65 bytes per call site.**

#### 2c. Retain `tailCallCodeRefSlot` / `tailCallArgsSlot` allocations?

These two slots (`tailCallCodeRefSlot`, `tailCallArgsSlot`) in
`EmitterMethodCreator` lines 621–630 are currently used only by the inline
trampoline. After Phase 2, they are no longer needed at call sites.

However, they ARE still used at `returnLabel` for the method-level TAILCALL
handling (lines 785–820 of `EmitterMethodCreator`). That section handles the
case where the sub body itself returns a TAILCALL marker (from `goto &NAME`).

**Decision**: Keep the `tailCallCodeRefSlot` and `tailCallArgsSlot` allocations,
but remove them from call sites. The method-level returnLabel trampoline remains
unchanged. This avoids touching the more complex returnLabel code.

**Further optimization (later)**: The returnLabel trampoline can also be factored
out in a future phase using the same `resolveTailCalls` helper.

#### 2d. Risk and mitigation

The inline trampoline currently handles `goto &sub` by extracting the codeRef and
args from the `RuntimeControlFlowList` and re-calling `apply()`. The new static
helper does exactly the same thing, so semantics are preserved.

**Risk**: If `resolveTailCalls` is not JIT-compiled early enough, tail-call-heavy
code may be slower until it warms up. Measure with the
`dev/bench/` benchmarks before and after.

**Testing**: Run the full perl5 test suite, especially:
```bash
perl dev/tools/perl_test_runner.pl perl5_t/t/op/goto.t
perl dev/tools/perl_test_runner.pl perl5_t/t/op/sub.t
perl dev/tools/perl_test_runner.pl perl5_t/t/op/closure.t
timeout 60 ./jperl t/96_is_deteministic_value.t  # DBIx::Class
```

---

### Phase 3 — Extract Eval Block Prologue/Epilogue (medium impact, low risk)

**Goal**: Replace inline 4–7 instruction sequences in the eval prologue/epilogue
with single static calls.

**Impact estimate**: 20–35 bytes saved per eval block (modest but easy).

#### 3a. Replace `emitEvalDepthIncrement` / `emitEvalDepthDecrement`

Current (4 instructions each, inlined 2–3 times per eval block):

```java
// emitEvalDepthIncrement:
mv.visitFieldInsn(GETSTATIC, "RuntimeCode", "evalDepth", "I");
mv.visitInsn(ICONST_1);
mv.visitInsn(IADD);
mv.visitFieldInsn(PUTSTATIC, "RuntimeCode", "evalDepth", "I");
```

Replace with new public static methods in `RuntimeCode`:

```java
public static void evalDepthIncrement() { evalDepth++; }
public static void evalDepthDecrement() { evalDepth--; }
```

Emission:

```java
// Before: 4 instructions (12 bytes)
emitEvalDepthIncrement(mv);

// After: 1 instruction (3 bytes)
mv.visitMethodInsn(INVOKESTATIC, "org/perlonjava/runtime/runtimetypes/RuntimeCode",
        "evalDepthIncrement", "()V", false);
```

Savings: 3 occurrences × 9 bytes = **27 bytes per eval block**.

#### 3b. Replace $@ clear on eval entry

Current (3 instructions):

```java
mv.visitLdcInsn("main::@");
mv.visitLdcInsn("");
mv.visitMethodInsn(INVOKESTATIC, "GlobalVariable", "setGlobalVariable",
        "(Ljava/lang/String;Ljava/lang/String;)V", false);
```

This pattern appears twice per eval block (entry + success exit). Add:

```java
// RuntimeCode:
public static void evalClearError() {
    GlobalVariable.setGlobalVariable("main::@", "");
}
```

Emission (1 instruction, 3 bytes vs 3 instructions, ~10 bytes):

```java
mv.visitMethodInsn(INVOKESTATIC, "org/perlonjava/runtime/runtimetypes/RuntimeCode",
        "evalClearError", "()V", false);
```

Savings: 2 occurrences × 7 bytes = **14 bytes per eval block**.

#### 3c. Combined savings for eval blocks

| Change | Savings per eval block |
|--------|------------------------|
| evalDepthIncrement/Decrement extraction (3×) | 27 bytes |
| evalClearError extraction (2×) | 14 bytes |
| **Total** | **~41 bytes** |

---

## Combined Savings Estimate

Assuming the DBIx::Class test run (980 methods, with ~50 methods having 15+ calls,
~200 methods being eval blocks):

| Phase | Per-method | For 980 methods |
|-------|-----------|-----------------|
| Phase 1: reduce pre-init buffer (256→32) | −672 bytes | −658 KB |
| Phase 2: extract call-site trampoline (15 calls avg for large methods) | −975 bytes | −48 KB (50 methods) |
| Phase 3: eval epilogue extraction | −41 bytes | −8 KB (200 methods) |
| **Total** | | **~−714 KB** |

Most importantly: the **largest method shrinks from 9,377 → ~8,100 bytes** after
Phase 1 alone, and from **9,377 → ~6,700 bytes** after Phase 2.  The C1 register
allocator failure threshold is somewhere above the 9,377-byte mark; post-Phase-2
we expect to be well below it.

---

## Implementation Sequence

```
Phase 1a: Audit allocateLocalVariable() call sites          [~2h]
Phase 1b: Extend TempLocalCountVisitor                      [~4h]
Phase 1c: Run perl5 test suite with buffer = 128, 64, 32    [~3h]
Phase 1d: Land with safe buffer (32 or whatever passes)     [~1h]

Phase 2a: Add RuntimeCode.resolveTailCalls()                [~1h]
Phase 2b: Modify EmitSubroutine.emitSubCall()               [~3h]
Phase 2c: Run goto/sub/closure tests                        [~2h]
Phase 2d: Measure bytecode sizes and DBIx::Class timing     [~1h]

Phase 3a: Add evalDepthIncrement/Decrement helpers          [~30m]
Phase 3b: Add evalClearError helper                         [~30m]
Phase 3c: Verify eval tests pass                            [~1h]
```

---

## Verification After Each Phase

### Bytecode size check

```bash
# Before any change — record baseline
JPERL_BYTECODE_SIZE_DEBUG=1 timeout 60 ./jperl \
  /Users/fglock/.perlonjava/cpan/build/DBIx-Class-0.082844/t/96_is_deteministic_value.t \
  2>&1 | grep "method=apply" | grep -o "code_bytes=[0-9]*" \
       | sed 's/code_bytes=//' | sort -rn | head -10

# Check max bytecode after phase
# Same command — confirm max has decreased as expected
```

### Test suite

```bash
# Unit tests must pass
make

# perl5 test suite (subroutine and eval correctness)
perl dev/tools/perl_test_runner.pl perl5_t/t/op/goto.t
perl dev/tools/perl_test_runner.pl perl5_t/t/op/sub.t
perl dev/tools/perl_test_runner.pl perl5_t/t/op/closure.t
perl dev/tools/perl_test_runner.pl perl5_t/t/op/eval.t
perl dev/tools/perl_test_runner.pl perl5_t/t/op/taint.t   # uses eval
perl dev/tools/perl_test_runner.pl perl5_t/t/re/           # regex state

# DBIx::Class timing test (must finish in < 60s on a clean machine)
ps aux | awk '$3 > 20 {print $2, $3, $11}'   # ensure no orphan JVMs
timeout 60 ./jperl \
  /Users/fglock/.perlonjava/cpan/build/DBIx-Class-0.082844/t/96_is_deteministic_value.t
```

### Regression confirmation

Run `JPERL_DISABLE_INTERPRETER_FALLBACK=1` against each phase to confirm that
no new VerifyErrors or ASM crashes are introduced (the fallback would otherwise
silently hide them):

```bash
JPERL_DISABLE_INTERPRETER_FALLBACK=1 timeout 60 ./jperl -e 'sub f{my $x=shift; $x+1} print f(1),"\n"'
```

---

## Risks and Mitigations

| Risk | Severity | Mitigation |
|------|----------|------------|
| TempLocalCountVisitor underestimates → VerifyError | Low | Caught by fallback; add pattern to visitor |
| `resolveTailCalls` not inlined fast enough → perf regression | Low | Benchmark; annotate as HotSpot candidate |
| Phase 2 changes semantics of `goto &sub` in edge case | Medium | Run full `goto.t`, `sub.t` test suites |
| Phase 1 buffer too small for eval strings (string evals have many vars) | Medium | Test `t/eval.t` and `t/string_eval.t` with `JPERL_DISABLE_INTERPRETER_FALLBACK=1` |
| Interaction with `JPERL_SPILL_SLOTS` tuning | Low | After Phase 2, spill slots may be reducible from 16 to 8; test separately |

---

## Future Work (Out of Scope for This PR)

- **Reduce spill slot count**: After Phase 2, the 16 per-method spill slots (48
  bytes init overhead) may be reducible to 8. Controlled by `JPERL_SPILL_SLOTS`.
- **Method-level returnLabel trampoline**: The `tailCallCodeRefSlot` /
  `tailCallArgsSlot` at `returnLabel` in `EmitterMethodCreator` lines 785–820 can
  also be extracted to a static helper in a follow-up.
- **Lazy slot initialization**: Long-term, replacing the pre-init loop entirely
  with per-allocation initialization would require a two-pass compiler but
  could eliminate the pre-init completely (not just reduce it).

---

## Related Files

| File | Role |
|------|------|
| `EmitterMethodCreator.java` lines 596–604 | Pre-init loop (Phase 1 target) |
| `TempLocalCountVisitor.java` | Visitor to extend (Phase 1) |
| `EmitSubroutine.java` lines 725–870 | Per-call-site trampoline (Phase 2 target) |
| `RuntimeCode.java` | Home for new helpers (Phases 2 & 3) |
| `EmitterMethodCreator.java` lines 720, 894, 920 | Eval depth emit calls (Phase 3) |
| `dev/design/dbixclass-timeout-jit-analysis.md` | Root cause analysis |
| `dev/architecture/large-code-refactoring.md` | Existing 64KB strategy |

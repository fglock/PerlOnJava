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

## How to Evaluate Results

This section explains how to measure progress after completing a phase and
interpret what the numbers mean in terms of the C1 JIT failure risk.

### 1. Measure apply() method bytecode sizes

```bash
DBIX_DIR=/Users/fglock/projects/PerlOnJava2/cpan_build_dir/DBIx-Class-0.082844
JPERL=/path/to/your/jperl   # use the current build's jperl

cd "$DBIX_DIR"
JPERL_BYTECODE_SIZE_DEBUG=1 timeout 120 "$JPERL" -Ilib -It/lib \
    t/96_is_deteministic_value.t > /dev/null 2> /tmp/96_sizes.txt

# Top-10 largest apply() methods
grep 'method=apply\b' /tmp/96_sizes.txt \
  | awk -F'code_bytes=' '{print $2}' | sort -rn | head -10

# Summary statistics
grep 'method=apply\b' /tmp/96_sizes.txt \
  | awk -F'code_bytes=' '{sum+=$2; count++; if($2>max)max=$2}
    END{printf "methods=%d  total=%dKB  avg=%d  max=%d\n",
        count, sum/1024, sum/count, max}'
```

**What to look for:**

- **`max`**: The largest `apply()` method. C1 register allocation failures
  have been observed for methods above ~9,000 bytes when the JVM is
  CPU-starved. Each Phase reduces the max (Phase 2 removes ~65 bytes per
  call site, which is the dominant term for large methods).
- **`avg`**: The average method size. Dominated by the pre-init buffer before
  Phase 1; dominated by call-site trampoline overhead before Phase 2.
- **`total`**: Total class-file footprint for all Perl subs.  Drives heap
  pressure and permgen/metaspace usage.

### 2. Measure test timing

```bash
# Always kill orphaned JVMs before timing — they starve the JIT
pkill -9 -f "perlonjava-.*\.jar.*\.t\b" 2>/dev/null
ps aux | awk '$3 > 20 {print $2, $3, $11}' | grep -v WindowServer | grep -v Spotlight

cd "$DBIX_DIR"
time timeout 120 "$JPERL" -Ilib -It/lib t/96_is_deteministic_value.t
time timeout 120 "$JPERL" -Ilib -It/lib t/76joins.t
```

**Pass/fail thresholds** (clean machine, no competing JVMs):

| Test | Expected real time | Concern threshold | Failure threshold |
|------|--------------------|-------------------|-------------------|
| `t/96_is_deteministic_value.t` | < 15 s | > 30 s | > 120 s (harness kills at 300 s) |
| `t/76joins.t` | < 15 s | > 30 s | > 120 s |

If timing exceeds the concern threshold on a clean machine, the C1 JIT may
be failing on large methods. Confirm with step 4 below.

### 3. Verify correctness

```bash
cd "$DBIX_DIR"
"$JPERL" -Ilib -It/lib t/96_is_deteministic_value.t | grep -E "^(ok|not ok|1\.\.)"
"$JPERL" -Ilib -It/lib t/76joins.t                  | grep -E "^(ok|not ok|1\.\.)"
```

Expected: `1..8` with 8 `ok` lines for `96_is_det`, `1..27` with 27 `ok`
lines for `76joins`.

### 4. Check for C1 JIT failures (optional deep-dive)

```bash
# Add -XX:+PrintCompilation to jperl JVM opts
JPERL_OPTS="-XX:+PrintCompilation" timeout 120 "$JPERL" -Ilib -It/lib \
    t/96_is_deteministic_value.t 2>&1 | grep "made not entrant\|COMPILE SKIPPED\|out of virtual"

# Or use JFR (Java Flight Recorder) for full JIT event trace
JPERL_OPTS="-XX:StartFlightRecording=filename=/tmp/96det.jfr,duration=120s" \
    timeout 120 "$JPERL" -Ilib -It/lib t/96_is_deteministic_value.t 2>/dev/null
# Then open /tmp/96det.jfr in JDK Mission Control and inspect:
#   JVM Internals → JIT Compilation → Compilation Failures
#   (look for "out of virtual registers in linear scan")
```

A C1 failure shows as `COMPILE SKIPPED` or `made not entrant` for the
SQL::Abstract `_expand_expr` method in `PrintCompilation` output.

### 5. Reference benchmarks (Phase 1 complete, buffer=32)

Measured on 2026-05-01 with PerlOnJava4 after merging PR #650:

| Metric | Value |
|--------|-------|
| `t/96_is_deteministic_value.t` — real time | 11.8 s |
| `t/96_is_deteministic_value.t` — all tests | 8/8 pass |
| `t/76joins.t` — real time | 9.3 s |
| `t/76joins.t` — all tests | 27/27 pass |
| Total `apply()` methods compiled | 8,264 |
| Total `apply()` code bytes | 4.0 MB |
| Average method size | 511 bytes |
| Minimum method size | 200 bytes |
| Largest `apply()` method | 36,554 bytes |
| Estimated savings vs old buffer=256 | ~5.4 MB (~56% reduction) |

**Notes on the baseline:**
- The design doc's original baseline (Largest=9,377 bytes, 980 methods) was
  measured on an older build before the TAILCALL trampoline (~65–130 bytes
  per call site) was added to every call site. PerlOnJava4 therefore has
  larger methods per call-heavy sub.
- The 36,554-byte largest method did not cause C1 failures in this run
  because the machine was under low load. Under CPU contention from orphaned
  JVMs (the scenario that caused the original timeout incidents), C1 may
  still fail on such methods. **Phase 2 (trampoline extraction) is the key
  fix for the largest methods.**
- Phase 1 gives dramatic size reduction for simple subs (minimum dropped
  from ~648 bytes → 200 bytes) and a 672-byte reduction for every method
  regardless of complexity.

### 6. Targets for subsequent phases

| Phase | Target max method size | Expected timing |
|-------|------------------------|-----------------|
| Phase 1 complete (current) | 36,554 bytes | ~12 s (clean) |
| After Phase 2 (trampoline extraction) | ~20,000 bytes* | ~10 s (clean) |
| After Phase 3 (eval prologue) | ~20,000 bytes | ~10 s (clean) |

*Estimate: largest method has ~250+ call sites × 65 bytes saved = ~16 KB
removed. Actual number of call sites in `_expand_expr` to be measured in
Phase 2.

---

## Progress Tracking

### Current Status: Phase 2 complete (2026-05-01)

### Completed Phases

- [x] **Phase 1: Extend TempLocalCountVisitor + reduce buffer 256→32** (2026-05-01)
  - Extended `TempLocalCountVisitor` to count: sub calls (+1 each), eval (+4),
    foreach (+4), while/for (+1), flip-flop (+3), xor (+1)
  - Reduced `EmitterMethodCreator` pre-init buffer from `+256` to `+32`
  - Files: `TempLocalCountVisitor.java`, `EmitterMethodCreator.java`
  - PR: #650
  - Result: ~56% total bytecode reduction; both DBIx::Class timing tests pass
    in < 15 s; all `make` unit tests pass

- [x] **Phase 2: Extract per-call-site TAILCALL trampoline** (2026-05-01)
  - Added `RuntimeCode.resolveTailCalls(RuntimeList, int)` static helper
    that resolves TAILCALL chains; replaces the ~65-byte inline trampoline
    loop previously emitted at every JVM call site
  - Modified `EmitSubroutine.handleApplyOperator()`: `resolveTailCalls()` is
    called only inside the `isNonLocalGoto()==true` branch (rare), so the
    common path (no control flow) has **zero extra overhead** vs Phase 1 —
    it remains: `apply()` → `ASTORE` → `ALOAD` → `isNonLocalGoto()` → branch
  - Per-call-site bytecode: ~83 bytes → ~38 bytes (saves ~45 bytes per site)
  - Files: `RuntimeCode.java`, `EmitSubroutine.java`
  - All `make` tests pass; `tail_calls.t` (7/7), `subroutine.t` (39/39) pass
  - Measured on `core_subroutine_refs.t`: 1,014 methods, avg 499 bytes, max 7,992 bytes
  - `goto &sub` chains verified correct (factorial via tail calls, multi-hop chains,
    `@_` aliasing preservation, LAST/NEXT/REDO after tail-called sub return)

- [x] **Phase 2 bugfix: VerifyError from int callContextSlot** (2026-05-01)
  - Root cause: `callContextSlot` was allocated via `allocateLocalVariable()` and
    stored with `ISTORE` (int type) in four emitter sites:
    `EmitSubroutine.handleApplyOperator()`, `Dereference.java` method calls,
    `EmitOperator.handleSubstrOperator()`, `EmitOperator.handleOperator()`.
  - The pre-init loop (EmitterMethodCreator) initialises every temp slot as
    `ACONST_NULL / ASTORE` (reference type).  When a callContextSlot (int) in the
    pre-init range was written by some code paths but not others, the verifier
    found "int vs null-reference" at the `blockDispatcher` merge point and threw
    `VerifyError: Bad local variable type`.
  - This VerifyError propagated out of the compiled main body through
    `executeCodeImpl`, which transparently re-ran the script body via the
    interpreter — calling `plan tests => 23` a second time → "You tried to plan
    twice" in DBIx::Class `t/multi_create/torture.t`.
  - Fix: replace every `ISTORE callContextSlot` + `ILOAD callContextSlot` with
    inline calls to `pushCallContext()` at each use site; `pushCallContext()` emits
    either an `LDC` constant or `ILOAD 2` (no slot allocation needed).
  - `TempLocalCountVisitor`: the `->` and `(` count entries are KEPT (even though
    the int slot is removed) because they control the pre-init range — removing
    them shrank the range and caused a second VerifyError ("Type top … is not
    assignable to reference type" at slot 143 in torture.t's main body).
  - Files: `EmitSubroutine.java`, `Dereference.java`, `EmitOperator.java`,
    `TempLocalCountVisitor.java`
  - DBIx::Class `t/multi_create/torture.t` now passes all 23 tests (JVM backend)

### Next Steps

1. **Phase 3**: Extract eval prologue/epilogue sequences — small but easy win
2. Re-run the timing tests under simulated CPU pressure to confirm C1 failure
   rate has dropped (requires running ~10 orphan JVMs to create contention)
3. Consider reducing `JPERL_SPILL_SLOTS` from 16 to 8 after verifying no
   spill overflow failures; saves 24 bytes per method

### Open Questions

- The largest `apply()` method before Phase 2 was 36,554 bytes. Phase 2
  estimates ~65 bytes × N call sites removed. For a method with ~250 call
  sites, this is ~16 KB saved, bringing it to ~20 KB — still above the
  9,377-byte C1 threshold from the original analysis. However, the threshold
  may differ for PerlOnJava4's method structure; re-measure after Phase 3.
- Should `JPERL_SPILL_SLOTS=8` be tested?  Reducing the spill pool from 16
  to 8 would save 24 bytes per method; measure whether it causes overflow
  failures first.

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

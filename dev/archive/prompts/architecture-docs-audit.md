# Architecture Documentation Audit & Fix Plan

## Context
Audited all 6 architecture docs + README against the actual codebase. Found discrepancies ranging from minor (parameter names) to major (entire described architecture doesn't exist).

## Priority 1: Factually Wrong Status Labels

### 1a. inline-cache.md — IS implemented, not "NOT YET IMPLEMENTED"
- `RuntimeCode.java` has a 4096-entry inline cache (`inlineCacheBlessId`, `inlineCacheMethodHash`, `inlineCacheCode` arrays)
- Cache invalidation via `clearInlineMethodCache()` exists
- Remove "NOT YET IMPLEMENTED" from `dev/design/inline-cache.md` line 3
- Update README.md table entry (line 33) to reflect partial implementation
- Review whether `dev/design/method-call-optimization.md` Phase 1 should also be updated

### 1b. method-call-optimization.md — Phase 1 (inline cache) is implemented
- At minimum note that Phase 1 is done; Phases 2-3 remain unimplemented
- The `SpillSlotManager` and `RuntimeArrayPool` references are to non-existent classes — remove or note
- `bench_method.pl` doesn't exist — remove reference or note

## Priority 2: large-code-refactoring.md — Describes Non-Existent Architecture

The doc says: compile -> fail with MethodTooLargeException -> catch -> enable refactoring -> retry.
Reality: compile -> fail -> fall back to interpreter.

Specific issues:
- The "reactive retry" flow (lines 44-66, 97-127) does not exist
- `forceRefactorForCodegen(BlockNode node, boolean isAutoRetry)` — dead code, wrong signature (no `isAutoRetry` param)
- `IS_REFACTORING_ENABLED` field doesn't exist
- `MAX_REFACTOR_ATTEMPTS` is 3, not 1
- User messages wrong ("retrying with automatic refactoring" doesn't exist)
- Doc omits actual mechanism: interpreter fallback via `USE_INTERPRETER_FALLBACK`
- Doc omits `DepthFirstLiteralRefactorVisitor` (proactive literal refactoring)
- Doc omits `LargeBlockRefactorer.processBlock()` called from `EmitBlock` (proactive size check)

Action: Rewrite to describe what actually exists:
1. Proactive: `LargeBlockRefactorer.processBlock()` estimates size, wraps large blocks in closures
2. Proactive: `DepthFirstLiteralRefactorVisitor` splits large list/array/hash literals
3. Fallback: `MethodTooLargeException` -> interpreter backend
4. Note `forceRefactorForCodegen` as dead code candidate for removal

## Priority 3: control-flow.md — Wrong Paths, Missing Feature, Wrong Bytecode

### 3a. All 6 file paths wrong
| Doc Path | Actual Path |
|----------|-------------|
| `runtime/ControlFlowType.java` | `runtime/runtimetypes/ControlFlowType.java` |
| `runtime/ControlFlowMarker.java` | `runtime/runtimetypes/ControlFlowMarker.java` |
| `runtime/RuntimeControlFlowList.java` | `runtime/runtimetypes/RuntimeControlFlowList.java` |
| `codegen/EmitControlFlow.java` | `backend/jvm/EmitControlFlow.java` |
| `codegen/EmitSubroutine.java` | `backend/jvm/EmitSubroutine.java` |
| `codegen/EmitterMethodCreator.java` | `backend/jvm/EmitterMethodCreator.java` |

### 3b. ControlFlowType enum wrong
- Enum values don't take ordinal arguments `(0)`, `(1)` — they're plain enum values
- Missing `RETURN` (6th variant for non-local return from map/grep)

### 3c. ControlFlowMarker fields incomplete
- Missing `codeRef` (RuntimeScalar) and `args` (RuntimeArray) fields

### 3d. RuntimeControlFlowList fields wrong
- `tailCallCodeRef`/`tailCallArgs` are NOT direct fields — accessed via `marker.codeRef`/`marker.args`
- Missing `returnValue` field (carries return value for RETURN type)

### 3e. TABLESWITCH at returnLabel doesn't exist
- No TABLESWITCH anywhere in control flow dispatch
- returnLabel just checks `isNonLocalGoto()`, handles eval-specific error, returns
- Tail call trampoline is at each call site in EmitSubroutine.java, NOT at returnLabel

### 3f. Example 2 bytecode wrong
- Uses `INVOKEVIRTUAL isNonLocalGoto()`, not `INSTANCEOF RuntimeControlFlowList`
- Jump target is `blockDispatcher`, not `returnLabel`

### 3g. Missing feature: RETURN / non-local return from map/grep
- `ControlFlowType.RETURN`
- `RuntimeControlFlowList.returnValue`
- `PerlNonLocalReturnException`
- `JavaClassInfo.isMapGrepBlock`
- Special RETURN-handling in `emitBlockDispatcher` and `EmitterMethodCreator`

### 3h. Missing feature flag: `ENABLE_TAGGED_RETURNS`

### 3i. "Why Centralized TABLESWITCH" section (lines 440-452) misleading
- Actual approach uses sequential `IF_ICMPNE`/`IF_ICMPGT` chain, not TABLESWITCH

## Priority 4: block-dispatcher-optimization.md

### 4a. Per-call-site bytecode size wrong
- Doc says ~20 bytes. Actual is ~100-150 bytes (tailcall trampoline at every call site)
- All savings calculations in the table are based on wrong per-site size

### 4b. Call-site pseudocode omits tailcall trampoline
- Between `IFEQ notControlFlow` and `GOTO blockDispatcher` there are ~30 instructions for tailcall handling

### 4c. Missing implementation file
- `Dereference.java` (lines 982-1109) has a full copy of the block-dispatcher pattern — not listed

### 4d. Wrong doc reference
- `CONTROL_FLOW_IMPLEMENTATION.md` doesn't exist; actual file is `control-flow.md`

### 4e. Block dispatcher pseudocode omits RETURN handling
- Higher-ordinal handling (GOTO=3, TAILCALL=4, RETURN=5) not shown

## Priority 5: lexical-pragmas.md

### 5a. Stack types wrong
- Doc says `Deque<>` / `ArrayDeque<>`. Actual is `Stack<>`

### 5b. Missing stacks
- `warningDisabledStack` and `warningFatalStack` not documented

### 5c. StrictOptions class doesn't exist
- Constants are in `Strict.java` with names `HINT_STRICT_REFS/SUBS/VARS` and values `0x2/0x200/0x400`

### 5d. CompilerFlagNode missing fields
- Missing `warningFatalFlags`, `warningDisabledFlags`, `hintHashSnapshotId`

### 5e. enterScope() return type
- Returns `int`, not `void`

### 5f. enableWarning pseudocode wrong
- No `getCategoryBit()` or `currentFlags` field; actual delegates to `ScopedSymbolTable.enableWarningCategory()`

### 5g. warnIf() pseudocode drastically simplified
- Actual uses caller()[9] bits as primary mechanism, not just `${^WARNING_SCOPE}`

## Priority 6: dynamic-scope.md

### 6a. Missing implementors
- Add `GlobalRuntimeArray`, `GlobalRuntimeHash` to implementations list

### 6b. pushLocalVariable overloads
- Doc shows 1 overload; actual has 4 (RuntimeBase, RuntimeScalar, RuntimeGlob with special behavior, DynamicState)

### 6c. RuntimeScalar save missing blessId and reset-to-undef
- `dynamicSaveState()` also saves `blessId` and clears scalar to undef after

### 6d. DeferBlock wrong
- Field is `RuntimeScalar codeRef`, not `RuntimeCode code`
- Missing `capturedArgs` field
- Method call is `RuntimeCode.apply(codeRef, capturedArgs, VOID)`, not `code.apply(...)`

### 6e. Files table missing entries
- Add `GlobalRuntimeArray.java`, `GlobalRuntimeHash.java`, `GlobalRuntimeScalar.java`, `RuntimeBase.java`

## Priority 7: weaken-destroy.md

### 7a. RuntimeArray pop/shift claim wrong
- Doc says pop()/shift() have deferred decrement — they don't

### 7b. Code ref birth-tracking path
- Should say "closures with captures via `makeCodeObject()`", not "via `createReferenceWithTrackedElements()`"

### 7c. doCallDestroy type check order
- Actual order: Hash -> Array -> Glob -> Scalar -> Code (not Glob-first as doc implies)

### 7d. Test count
- `weaken_edge_cases.t` has 34 assertions, not 42

## Priority 8: README.md

### 8a. Package names
- Add `org.perlonjava.app` (CLI entry point, JSR-223 ScriptEngine)
- Add `org.perlonjava.core` (Configuration)

### 8b. Runtime types
- Add `RuntimeList` to the list

### 8c. LexerToken
- "LexerTokens" -> "LexerToken" (singular class name)

### 8d. Inline cache status
- Update table entry for inline-cache.md

## Priority 9: Stale Code Comments (Java source)

Found during weaken-destroy.md audit:
- `WeakRefRegistry.java:25-38` — WEAKLY_TRACKED Javadoc describes superseded Strategy A
- `RuntimeScalar.java:2210-2212` — stale comment about setLarge clearing WEAKLY_TRACKED weak refs
- `DestroyDispatch.java:162` — stale comment about apply() calling flush()

## Execution Order

1. Fix inline-cache status labels (Priority 1) — quick
2. Rewrite large-code-refactoring.md (Priority 2) — needs careful work
3. Fix control-flow.md (Priority 3) — many targeted edits
4. Fix block-dispatcher-optimization.md (Priority 4) — moderate edits
5. Fix lexical-pragmas.md (Priority 5) — moderate edits
6. Fix dynamic-scope.md (Priority 6) — moderate edits
7. Fix weaken-destroy.md (Priority 7) — small edits
8. Fix README.md (Priority 8) — small edits
9. Fix stale Java comments (Priority 9) — small edits
10. Run `make check-links` to verify
11. Run `make` to ensure nothing broken

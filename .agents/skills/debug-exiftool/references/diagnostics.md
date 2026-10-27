# ExifTool diagnostic reference

Confirm each hypothesis against the pinned upstream release and current source.
Historical pass counts and old priorities do not determine the next fix.

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

## Format-specific diagnostic leads

- Writer/Pentax malformed dates (for example `2008:0:0`) or time fields: inspect
  binary/BCD decoding and `pack`/`unpack` in Pentax.pm; compare float rounding
  separately (`13.2` versus `13.3` was an observed symptom).
- Missing XMP-GAudio Audio Data: trace preservation/writing of the binary XMP
  Audio tag rather than assuming an extraction failure.
- Mandatory EXIF tags and cross-deletes: check captured `%mandatory` and
  `%crossDelete` in WriteExif.pl before changing format-specific logic.
- Geotag date/GPS differences: compare `.out` and `.failed` to separate date/time
  parsing or timezone offsets (`Time::Local`) from coordinate interpolation.
- XMP lang-alt entries (`en`, `de`, `fr` versus `x-default`): check WriteXMP.pl path
  tracking and `pos()` after `m//g`. For `"a/b/c" =~ m|/|g`, the first position is
  2. Bag-index failures or dropped leading empty strings may share this cause.
- PDF write/revert tests are sequential: investigate the first failed edit before
  later cascades. Encryption cases also require a working `Digest::SHA`.
- QuickTime/HEIC writes and VideoKeys/AudioKeys extraction need format-specific
  reductions; compare CanonVRD, Nikon, Pentax and Panasonic writes for shared
  mandatory-tag or binary-data causes rather than assuming separate bugs.

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

## Compiler and runtime diagnostic tips

### Register recycling inflation
AST-based `detectClosureVariables` populated `capturedVarIndices` with ~321 entries, which inflated `getHighestVariableRegister()` and prevented aggressive register recycling. A no-op version (removing all capturedVarIndices) dropped Writer.t from 44/61 to 26/61 — not because of closure access, but because register recycling became too aggressive. When modifying `detectClosureVariables`, always ensure `capturedVarIndices` has enough entries to keep `getHighestVariableRegister()` high enough to prevent register corruption.

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
`EmitControlFlow.handleGotoLabel()` resolves labels at compile time within the current JVM scope. When the target label is outside the current scope (e.g., goto inside a `map` block to a label outside, or goto inside an `eval` block), the compile-time lookup fails. The fix is to emit a `RuntimeControlFlowList` marker with `ControlFlowType.GOTO` at runtime (the same mechanism used by dynamic `goto EXPR`), allowing the goto signal to propagate up the call stack. Cover both op/array.t and op/eval.t when changing this path.

### List slice with range indices
In `Dereference.handleArrowArrayDeref()`, the check for single-index vs slice path must account for range expressions (`..` operator). A range like `0..5` is a single AST node but produces multiple indices. The correct condition is: use single-index path only if there's one element AND it's not a range. Otherwise, use the slice path. The old code had a complex `isArrayLiteral` check that was too restrictive.

### qw() backslash processing
`StringParser.parseWordsString()` must apply single-quote backslash rules to each word: `\\` → `\` and `\delimiter` → `delimiter`. Without this, backslashes are doubled in the output. The processing uses the closing delimiter from the qw construct.

### `\(LIST)` must flatten arrays before creating refs
`\(@array)` should create individual scalar refs to each array element (like `map { \$_ } @array`), not a single ref to the array. `EmitVariable` needs a `flattenElements()` method that detects `@` sigil nodes in the list and flattens them before creating element references.

### Integrating a diverged branch

Preserve work in a WIP commit before any rebase. If replay is impractical, use a
separate clean worktree and a reviewed branch-only patch or selected commits.
Compute branch-only changes from the merge base, not a two-head diff that can
revert newer master changes. Verify the intended delta, retained commits and
fresh gates before updating a PR; use the repository's force-push safeguards.
Never discard uncommitted work or assume a rebase abort preserves later edits.

### `getInt()` vs `(int) getLong()` for 32-bit integer wrapping
`RuntimeScalar.getInt()` clamps DOUBLE values to `Integer.MAX_VALUE` (e.g., `(int) 2147483648.0 == 2147483647`). But `(int) getLong()` wraps correctly via long→int truncation (e.g., `(int) 2147483648L == -2147483648`). For `use integer` operations where Config.pm reports `ivsize=4`, always use `(int) getLong()` to get proper 32-bit wrapping behavior matching Perl's semantics.

### scalar gmtime/localtime ctime(3) format
Perl's scalar `gmtime`/`localtime` returns ctime(3) format: `"Fri Mar  7 20:13:52 881"` — NOT RFC 1123 (`"Fri, 7 Mar 0881 20:13:52 GMT"`). Use `String.format()` with explicit field widths, not `DateTimeFormatter`. Also: wday must use `getValue() % 7` (Perl: 0=Sun..6=Sat) not `getValue()` (Java: 1=Mon..7=Sun). Large years (>9999) must not crash the formatter.

### Regression testing: always compare branch vs master
Before declaring a fix complete, run the same test on both master and the branch to distinguish real regressions from pre-existing failures. Use `perl5_t/t/` (not `perl5/t/`) for running Perl5 core tests — the `perl5_t` copy has test harness files (`test.pl`, `charset_tools.pl`) that PerlOnJava can load.


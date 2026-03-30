# Constant Folding for User-Defined Constants

## Summary

Extend compile-time constant folding to inline user-defined constant subroutines
(`use constant`, `sub FOO () { 123 }`) into the AST, enabling cascading optimizations.
For example, `use constant PI => 3.14159; my $x = PI * 2;` should compile as if
written `my $x = 6.28318;`.

## Current State

### What works today

1. **`ConstantFoldingVisitor`** — Pure AST optimizer that folds expressions composed
   of literal `NumberNode`/`StringNode` values and a hardcoded set of built-in
   functions (`sqrt`, `sin`, `abs`, `int`, `chr`, `ord`, `length`, `atan2`, etc.).
   Example: `2 + 3` → `5`, `sqrt(4)` → `2.0`.

2. **`RuntimeCode.constantValue`** — Runtime short-circuit: when a constant subroutine
   is called, `apply()` checks `constantValue != null` and returns the value directly,
   avoiding full method invocation overhead.

3. **Dead code elimination** — `EmitStatement.getConstantConditionValue()` (JVM backend)
   and the identical method in `BytecodeCompiler` resolve constant subs at compile time
   **but only for `if`/`unless` conditions**. This enables patterns like:
   ```perl
   use constant WINDOWS => 0;
   if (WINDOWS) { ... }  # Dead code eliminated at compile time
   ```

### What does NOT work today

- **The `ConstantFoldingVisitor` is not used in the main compilation pipeline.** The
  calls in `PerlLanguageProvider.java` (line 170) and `RuntimeCode.java` (line 625) are
  **commented out**. It is only active in `StringSegmentParser` for regex `(?{...})`
  code blocks.

- **User-defined constants are not inlined in expressions.** For `use constant PI => 3.14;
  my $x = PI * 2;`, the AST contains a subroutine call node for `PI`, not a literal
  `3.14`. The multiplication happens at runtime.

- **No cascading optimization.** Even if we inlined `PI` to `3.14`, the folding visitor
  is not running, so `3.14 * 2` would not be folded to `6.28`.

- **`getConstantConditionValue` is duplicated** identically in both `EmitStatement.java`
  and `BytecodeCompiler.java` (~50 lines each).

## How Constants Are Created

### `use constant`

`constant.pm` creates constant subs by assigning to the symbol table:
```perl
# For Perl > 5.009002 (our case):
Internals::SvREADONLY($scalar, 1);
$symtab->{$name} = \$scalar;
# Fallback:
*$full_name = sub () { $scalar };
```

In PerlOnJava, this results in a `RuntimeCode` object with `constantValue` set,
stored via `GlobalVariable.getGlobalCodeRef()`.

### `sub FOO () { 123 }`

The parser creates a `SubroutineNode` with prototype `""` (empty string). After
compilation, if the subroutine body is a constant expression, it becomes a
`RuntimeCode` with `constantValue` set.

### Glob assignment

`*foo = \42` creates a constant sub via `RuntimeStashEntry`, setting `constantValue`
on a new `RuntimeCode`.

### Key property: timing

Since `use constant` runs in a `BEGIN` block, constants are defined **during parsing**.
By the time `parser.parse()` returns, all constants are available in the global symbol
table. This means a post-parse AST transformation can safely look them up.

## Implementation Plan

### Phase 1: Emit-time constant sub inlining

**Goal:** Avoid the overhead of `RuntimeCode.apply()` for constant subroutine calls.

**Where:** `EmitSubroutine.handleApplyOperator()` (JVM backend)

**How:** When the call target is a named subroutine (resolved via
`GlobalVariable.getGlobalCodeRef()`), check if it has `constantValue` set and the
argument list is empty. If so, emit the constant value as a literal (`ldc` instruction)
instead of the full call machinery (code ref resolution, argument array creation,
`RuntimeCode.apply()` invocation).

**AST patterns to recognize:**
```
BinaryOperatorNode("(", OperatorNode("&", IdentifierNode("PI")), ListNode())
```

**What to emit:** For a scalar constant value, emit the equivalent of a `NumberNode`
or `StringNode` literal. For non-scalar constants, fall back to the normal call path.

**Also apply to:** `BytecodeCompiler` for the interpreter backend.

**Files changed:**
- `src/main/java/org/perlonjava/backend/jvm/EmitSubroutine.java`
- `src/main/java/org/perlonjava/backend/bytecode/BytecodeCompiler.java`

**Benefit:** Eliminates runtime overhead for every constant sub call. No cascading
folding yet, but each constant reference becomes a simple `ldc` instead of a method
call chain.

### Phase 2: AST-level constant sub resolution in `ConstantFoldingVisitor`

**Goal:** Replace constant sub references with literal nodes in the AST, enabling
cascading constant folding (e.g., `PI * 2` → `3.14159 * 2` → `6.28318`).

**How:**

1. Add a `currentPackage` field to `ConstantFoldingVisitor` (set via constructor or
   a static method parameter).

2. In `visit(BinaryOperatorNode)` for operator `"("`:
   - Extract the subroutine name from the AST pattern
     `BinaryOperatorNode("(", OperatorNode("&", IdentifierNode(name)), ListNode())`
   - Resolve via `GlobalVariable.getGlobalCodeRef(fullName)`
   - If the sub has `constantValue` with exactly one `RuntimeScalar` element, and the
     argument list is empty, replace the entire call node with a `NumberNode` or
     `StringNode`
   - Then the parent expression (e.g., `PI * 2`) has both operands as constants and
     gets folded by the existing binary operation folding

3. In `visit(IdentifierNode)`:
   - Resolve the identifier as a potential constant sub
   - If it resolves to a scalar constant, replace with a literal node
   - Note: bare identifiers used as constants (without `()`) appear as
     `IdentifierNode` in some contexts

4. Only inline **scalar constants** — skip list constants (`WEEKDAYS`), reference
   constants (`ARRAYREF => [1,2,3]`), and non-scalar values.

**Files changed:**
- `src/main/java/org/perlonjava/frontend/analysis/ConstantFoldingVisitor.java`

**Benefit:** Cascading optimization — expressions involving constants are fully
evaluated at compile time.

### Phase 3: Enable `ConstantFoldingVisitor` in the compilation pipeline

**Goal:** Activate the folding visitor for all compiled code.

**How:**

1. Uncomment/add the `ConstantFoldingVisitor.foldConstants(ast)` call in:
   - `PerlLanguageProvider.java` (main script compilation, line ~170)
   - `RuntimeCode.java` (eval compilation, line ~625)

2. Pass the current package name to the visitor so it can resolve identifiers.

3. The visitor runs **after parsing** (so BEGIN blocks have executed and constants
   are defined) and **before code emission**.

4. For the bytecode interpreter path, apply the same transformation before
   `BytecodeCompiler` processes the AST.

**Files changed:**
- `src/main/java/org/perlonjava/app/scriptengine/PerlLanguageProvider.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java`

**Benefit:** All code paths benefit from constant folding and constant sub inlining.

### Phase 4 (optional): Extract shared constant resolution utility

**Goal:** Eliminate the duplicated `getConstantConditionValue()` method.

**How:** Extract the logic into a shared static method in a utility class (e.g.,
`ConstantFoldingVisitor.getConstantConditionValue()` or a new
`CompileTimeConstants` utility). Update both `EmitStatement` and `BytecodeCompiler`
to call the shared method.

**Files changed:**
- `src/main/java/org/perlonjava/backend/jvm/EmitStatement.java`
- `src/main/java/org/perlonjava/backend/bytecode/BytecodeCompiler.java`
- New or existing utility class

## Safety Considerations

### Redefined constants

If a constant sub is redefined after folding, the folded value will be stale:
```perl
use constant FOO => 1;
my $x = FOO + 1;  # Folded to 2 at compile time
# Later: *FOO = sub () { 99 };  # Redefinition — $x is still 2
```
This matches Perl 5 behavior. Perl 5 warns `"Constant subroutine FOO redefined"` and
the inlined values remain unchanged. We should emit the same warning if not already done.

### List constants

`use constant WEEKDAYS => qw(Sun Mon Tue ...)` creates a list constant where
`constantValue.elements.size() > 1`. These must NOT be folded to a single scalar.
Phase 2 should only inline when `constantValue.elements.size() == 1` and the element
is a `RuntimeScalar` with a simple type (INTEGER, DOUBLE, STRING).

### Reference constants

`use constant HASHREF => {a => 1}` creates a constant returning a reference. References
are mutable objects and must not be folded — each call site must get the same reference
object, not a copy.

### Side effects in constant expressions

The folding visitor must not fold expressions that have side effects. The current
implementation is safe because `isConstantNode()` only returns true for `NumberNode`
and `StringNode`, which are pure values. User-defined constant resolution should
similarly only produce these pure node types.

### Package context

The `ConstantFoldingVisitor` currently has no notion of the current package. Since
`use constant FOO => 1` defines `main::FOO` (or `CurrentPackage::FOO`), the visitor
needs package context to resolve bare identifiers. This should track correctly through
block/package boundaries in the AST, but the simplest initial approach is to use the
package at parse completion time (works for single-package scripts; multi-package
support can be refined later).

## Verification

### Existing tests
- `src/test/resources/unit/constant.t` — basic `use constant` functionality
- `src/test/resources/unit/regex/code_block_constants.t` — constant folding in regex code blocks

### New tests to add
```perl
# Constant folding in expressions
use constant X => 10;
use constant Y => 20;
is(X + Y, 30, 'constant addition folded');
is(X * 2, 20, 'constant * literal folded');
is(X + Y + 5, 35, 'cascading constant fold');

# Dead code elimination (already works, verify preserved)
use constant DEBUG => 0;
if (DEBUG) { die "should not reach here" }
pass('dead code eliminated');

# List constants NOT folded
use constant DAYS => qw(Mon Tue Wed);
is((DAYS)[0], 'Mon', 'list constant not broken by folding');

# Reference constants NOT folded
use constant AREF => [1, 2, 3];
push @{AREF()}, 4;
is(scalar @{AREF()}, 4, 'reference constant is same object');
```

### Bytecode verification
```bash
# Count bytecode instructions — should decrease for constant expressions
./jperl --disassemble -e 'use constant X=>10; my $y = X + 5' 2>&1 | wc -l
```

### Regression testing
```bash
make              # Must pass
make test-all     # Full regression
```

## Progress Tracking

### Current Status: Not started

### Phases
- [ ] Phase 1: Emit-time constant sub inlining
- [ ] Phase 2: AST-level constant sub resolution
- [ ] Phase 3: Enable visitor in compilation pipeline
- [ ] Phase 4: Extract shared utility (optional cleanup)

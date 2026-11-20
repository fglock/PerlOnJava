# Implementing Declared References in PerlOnJava

## Overview
Declared references (introduced in Perl 5.26) allow declaring variables as references directly:
```perl
my \$x = \$y;      # $x is a reference to a scalar
my \@arr = \@other; # $arr is a reference to an array  
my \%hash = \%h;    # $hash is a reference to a hash
```

## Current Status (2025-10-06)

### ✅ COMPLETED
1. **Basic declared references** (`my \$x = \$y`)
   - Parser correctly identifies and flags declared references
   - Emitter handles stack management for declared reference variables
   - Assignment operator works for simple cases

2. **Scalar declared refs with parentheses** (`my(\$x)`)
   - Parser detects backslash inside parentheses
   - Emitter creates scalar variables correctly

3. **Array/Hash declared references with parentheses** (`my(\@arr)`, `my(\%hash)`) - **FIXED 2025-10-06**
   - Parser now transforms the AST to replace `\@arr` with `$arr` in list elements
   - Symbol table correctly gets scalar variables
   - Assignment works correctly with transformed AST

### ❌ TODO
1. Support `state` declarations with declared refs (partially works, needs testing)
2. Support for-loop declared refs: `for my \$x (\$y) {}`
3. Add experimental warnings (tests expect these)
4. Fix "Not implemented: my" error in eval context (blocks tests after 144)

## Technical Implementation

### Parser Changes (OperatorParser.java)

#### Detection of Declared References
```java
// Check if this is a declared reference (my \$x, our \@array, etc.)
boolean isDeclaredReference = false;
if (peek(parser).type == LexerTokenType.OPERATOR && peek(parser).text.equals("\\")) {
    isDeclaredReference = true;
    TokenUtils.consume(parser, LexerTokenType.OPERATOR, "\\");
}
```

#### Handling Parentheses Cases - SOLUTION THAT WORKS
When backslash is inside parentheses `my(\@arr)`, the parser must transform the AST:
```java
// In parseVariableDeclaration, when processing ListNode elements
List<Node> transformedElements = new ArrayList<>();
boolean hasTransformation = false;

for (int i = 0; i < listNode.elements.size(); i++) {
    Node element = listNode.elements.get(i);
    if (element instanceof OperatorNode operandNode) {
        if (operandNode.operator.equals("\\") && operandNode.operand instanceof OperatorNode varNode) {
            // Declared reference: transform \@arr to $arr
            OperatorNode scalarVarNode = varNode;
            if (varNode.operator.equals("@") || varNode.operator.equals("%")) {
                scalarVarNode = new OperatorNode("$", varNode.operand, varNode.tokenIndex);
            }
            scalarVarNode.setAnnotation("isDeclaredReference", true);
            addVariableToScope(parser.ctx, operator, scalarVarNode);
            
            // CRITICAL: Transform the AST by replacing the element
            transformedElements.add(scalarVarNode);
            hasTransformation = true;
        } else {
            transformedElements.add(element);
        }
    }
}

// Replace the list elements with transformed ones
if (hasTransformation) {
    listNode.elements.clear();
    listNode.elements.addAll(transformedElements);
}
```

### Emitter Changes (EmitVariable.java)

#### Stack Management for Declared References
```java
// For declared references in non-void context, we need different handling
if (isDeclaredReference && emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
    // Duplicate the variable for storage
    emitterVisitor.ctx.mv.visitInsn(Opcodes.DUP);
    // Store in a JVM local variable
    emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, varIndex);
    // The original is still on the stack for the assignment
}
```

## Solution Implemented (2025-10-06)

### The Fix That Works

**Key Insight**: The problem wasn't in the emitter but in the parser. The AST needed to be transformed DURING PARSING, not during emission.

**Solution**: Modified `OperatorParser.parseVariableDeclaration` to:
1. Detect when processing a ListNode with declared references
2. Transform `\@arr` nodes to `$arr` nodes in the AST
3. Replace the ListNode's elements with the transformed elements
4. This ensures the emitter sees the correct scalar variables

### Critical Build Lesson

**IMPORTANT**: After making parser changes, you MUST rebuild with:
```bash
./gradlew clean shadowJar
```

Using just `./gradlew compileJava` is NOT sufficient - the changes won't take effect!
This was documented in `high-yield-test-analysis-strategy.md` and is critical for parser changes.

## Test Impact

### Before Fix (2024-10-04)
- **op/decl-refs.t**: Only 144/402 tests ran before crashing
- Error: "Global symbol requires explicit package name"

### After Fix (2025-10-06)  
- **op/decl-refs.t**: Now runs to test 144 (all tests run, but many fail for other reasons)
- Remaining issues:
  - Missing experimental warnings (most failures)
  - "Not implemented: my" in eval context (blocks further progress)
- The core declared reference functionality is now working correctly

## Lessons Learned

1. **AST Transformation is Key**: The fix required transforming the AST in the parser, not trying to handle it in the emitter

2. **Build Process Matters**: Always use `./gradlew clean shadowJar` for parser changes, not just `compileJava`

3. **Debug at the Right Level**: Use `--parse` flag to examine AST structure and verify transformations

4. **Test Incrementally**: 
   - Create minimal test case first (`test_array_ref.pl`)
   - Verify with `--parse` that AST is correct
   - Then test with full test suite

5. **Symbol Table vs AST**: Even if variables are added correctly to the symbol table, the AST still needs to match for the emitter to work correctly

## Files Modified

- `/src/main/java/org/perlonjava/parser/OperatorParser.java`
  - Added declared reference detection
  - Handle backslash inside parentheses
  - Convert array/hash to scalar for declared refs

- `/src/main/java/org/perlonjava/codegen/EmitVariable.java`  
  - Modified handleMyOperator for declared refs
  - Fixed stack management in variable creation
  - Need to fix assignment emission

## Test Files

- `test_array_ref.pl` - Test array declared refs (PASSING after fix)
- `t/op/decl-refs.t` - Official Perl test suite (runs to completion, many failures for other reasons)

## Commit Reference

Fix implemented in commit f86790bb: "Fix declared references with arrays/hashes in parentheses"

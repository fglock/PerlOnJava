# Implementing Declared References in PerlOnJava

## Overview
Declared references (introduced in Perl 5.26) allow declaring variables as references directly:
```perl
my \$x = \$y;      # $x is a reference to a scalar
my \@arr = \@other; # $arr is a reference to an array  
my \%hash = \%h;    # $hash is a reference to a hash
```

## Current Status (2024-10-04)

### ✅ COMPLETED
1. **Basic declared references** (`my \$x = \$y`)
   - Parser correctly identifies and flags declared references
   - Emitter handles stack management for declared reference variables
   - Assignment operator works for simple cases

2. **Scalar declared refs with parentheses** (`my(\$x)`)
   - Parser detects backslash inside parentheses
   - Emitter creates scalar variables correctly

### 🚧 IN PROGRESS
**Array/Hash declared references with parentheses** (`my(\@arr)`, `my(\%hash)`)
- **Problem**: These should create scalar variables ($arr, $hash) not array/hash variables
- **Current Bug**: Assignment tries to access @arr_ref instead of $arr_ref
- **Root Cause**: Assignment operator emits the original AST with \@arr_ref

### ❌ TODO
1. Fix array/hash declared refs in assignment
2. Support `state` declarations with declared refs
3. Support `our` declarations with declared refs  
4. Support for-loop declared refs: `for my \$x (\$y) {}`
5. Add experimental warnings (tests expect these)

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

#### Handling Parentheses Cases
When backslash is inside parentheses `my(\$x)`:
```java
if (operandNode.operator.equals("\\") && operandNode.operand instanceof OperatorNode varNode) {
    // This is a declared reference inside parentheses
    // For arrays/hashes, convert to scalar variable
    OperatorNode scalarVarNode = varNode;
    if (varNode.operator.equals("@") || varNode.operator.equals("%")) {
        scalarVarNode = new OperatorNode("$", varNode.operand, varNode.tokenIndex);
    }
    scalarVarNode.setAnnotation("isDeclaredReference", true);
    addVariableToScope(parser.ctx, operator, scalarVarNode);
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

## Current Bug Analysis

### Issue: `my(\@arr_ref) = \@arr` fails

**Error**: `Global symbol "@arr_ref" requires explicit package name`

**Analysis**:
1. Parser correctly creates `$arr_ref` variable in symbol table
2. Emitter correctly handles the declaration
3. Assignment operator still tries to emit `\@arr_ref` instead of `$arr_ref`
4. Assignment sees LIST context due to parentheses
5. Left side emission (line 360 in EmitVariable.java) emits original AST

### Proposed Solution

The assignment operator needs to recognize when a backslash operator in the left side is part of a declared reference and emit the scalar variable instead:

```java
// In handleAssignOperator, when processing LIST context
// Check if left side contains declared reference backslash operators
// If so, emit the scalar variable instead of the original AST
```

## Test Impact

- **op/decl-refs.t**: Currently 144/402 tests run before error
- Fixing array/hash declared refs would unblock ~258 tests
- This is a high-yield fix affecting fundamental Perl functionality

## How to Resume Work

1. **Run test to see current state**:
   ```bash
   ./jperl test_array_ref.pl
   ```

2. **The bug is in**: `EmitVariable.java` around line 360 where LIST assignment emits left side

3. **Key insight**: Declared references ALWAYS create scalar variables, even when the syntax includes @ or %

4. **Next steps**:
   - Fix assignment emission for declared reference arrays/hashes
   - Add support for `state` declarations
   - Add experimental warnings
   - Test with full op/decl-refs.t suite

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

- `test_array_ref.pl` - Test array declared refs (currently failing)
- `test_paren_ref.pl` - Test various declared refs with parentheses
- `t/op/decl-refs.t` - Official Perl test suite (258 tests blocked)

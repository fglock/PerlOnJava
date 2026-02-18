# Context Propagation Fixes for Logical Operators

**Date:** 2026-02-18
**Status:** ✓ Complete

## Problem

Logical operators and control flow constructs were not properly evaluating their operands in SCALAR context, causing incorrect behavior in postfix `if/unless` statements and other boolean contexts.

### Example Issues

```perl
# Postfix if with regex - was not working in interpreter mode
say "ok" if $x =~ /pattern/;

# Logical NOT with regex - was not working
say "ok" if !($x =~ /pattern/);

# Ternary operator - was not working correctly
say ($x =~ /pattern/) ? "match" : "no match";
```

## Root Cause

### Interpreter Path (BytecodeCompiler)
The BytecodeCompiler was evaluating operands of logical operators without setting the context to SCALAR. This meant:

1. In postfix `if`, the condition was evaluated in VOID context (ctx=0) instead of SCALAR context (ctx=1)
2. The regex match was not being evaluated for its boolean value
3. This only affected the interpreter bytecode path

### JVM Path (EmitterVisitor)
The EmitLogicalOperator was preserving RUNTIME context for operands when the outer context was RUNTIME. When RUNTIME context is used, the actual wantarray value is loaded at runtime, which can be VOID (0). This caused:

1. Logical operators to pass RUNTIME context to their operands
2. When the final statement is a postfix `if`, the wantarray is 0 (VOID)
3. Regex matches were being called with ctx=0 instead of ctx=1

## Solution

### 1. Fixed Logical AND/OR Operators (BytecodeCompiler.java)

Added context save/restore for `&&`, `||`, `//` operators:

```java
// Compile left operand in scalar context (need boolean value)
int savedContext = currentCallContext;
currentCallContext = RuntimeContextType.SCALAR;
node.left.accept(this);
int rs1 = lastResultReg;
currentCallContext = savedContext;
```

**Files changed:**
- Line 3363-3370: `&&` and `and` operators
- Line 3404-3411: `||` and `or` operators
- Line 3443-3450: `//` operator

### 2. Fixed Logical NOT Operators (BytecodeCompiler.java)

Added context save/restore for `!` and `not` operators:

```java
// Evaluate operand in scalar context (need boolean value)
int savedContext = currentCallContext;
currentCallContext = RuntimeContextType.SCALAR;
node.operand.accept(this);
int rs = lastResultReg;
currentCallContext = savedContext;
```

**Files changed:**
- Line 4168-4176: `!` and `not` operators

### 3. Fixed Ternary Operator (BytecodeCompiler.java)

Added context save/restore for condition evaluation:

```java
// Compile condition in scalar context (need boolean value)
int savedContext = currentCallContext;
currentCallContext = RuntimeContextType.SCALAR;
node.condition.accept(this);
int condReg = lastResultReg;
currentCallContext = savedContext;
```

**Files changed:**
- Line 6498-6505: Ternary operator `? :`

### 4. Fixed Regex Match in LIST Context (RuntimeRegex.java)

In Perl, a successful regex match with no captures returns (1) in LIST context, not an empty list:

```java
if (ctx == RuntimeContextType.LIST) {
    // In LIST context: return captured groups, or (1) for success with no captures (non-global)
    if (found && result.elements.isEmpty() && !regex.regexFlags.isGlobalMatch()) {
        // Non-global match with no captures in LIST context returns (1)
        result.elements.add(RuntimeScalarCache.getScalarInt(1));
    }
    return result;
}
```

**Files changed:**
- Line 543-549: matchRegexDirect return logic

### 5. Fixed EmitterVisitor RUNTIME Context (EmitLogicalOperator.java)

The logical operators were preserving RUNTIME context for their operands, which caused the actual wantarray value (often VOID=0) to be used instead of SCALAR context:

```java
// OLD: Preserved RUNTIME context
int operandContext = emitterVisitor.ctx.contextType == RuntimeContextType.RUNTIME
        ? RuntimeContextType.RUNTIME
        : RuntimeContextType.SCALAR;

// NEW: Always use SCALAR context for boolean evaluation
int operandContext = RuntimeContextType.SCALAR;
```

**Files changed:**
- Line 315-317: Removed RUNTIME context preservation in emitLogicalOperatorSimple

This fix ensures that even when the outer context is RUNTIME, logical operators evaluate their operands in SCALAR context to get boolean values.

## Testing

### Before Fix
```perl
# Interpreter mode (eval STRING)
eval q{
    my $x = "test";
    say "ok" if $x =~ /test/;  # No output (WRONG)
};
```

### After Fix
```perl
# Both JVM and interpreter modes now work
my $x = "test";
say "ok" if $x =~ /test/;      # Prints "ok" ✓
say "ok" if !($x =~ /fail/);   # Prints "ok" ✓
say "ok" if not ($x =~ /fail/); # Prints "ok" ✓
say ($x =~ /test/) ? "yes" : "no"; # Prints "yes" ✓

# LIST context now returns (1) for matches with no captures
say "Match: ", ($x =~ /test/); # Prints "Match: 1" ✓
```

### Test Results
- All unit tests passing ✓
- Postfix if/unless working ✓
- Logical operators working ✓
- Ternary operator working ✓
- Regex LIST context working ✓

## Files Modified

1. `src/main/java/org/perlonjava/interpreter/BytecodeCompiler.java`
   - Fixed `&&`, `||`, `//` operators to evaluate operands in SCALAR context
   - Fixed `!`, `not` operators to evaluate operands in SCALAR context
   - Fixed ternary `? :` operator to evaluate condition in SCALAR context

2. `src/main/java/org/perlonjava/codegen/EmitLogicalOperator.java`
   - Fixed logical operators to always use SCALAR context for operands, even when outer context is RUNTIME

3. `src/main/java/org/perlonjava/regex/RuntimeRegex.java`
   - Fixed regex match to return (1) in LIST context for non-global matches with no captures

## Key Lessons

1. **Context matters**: Logical operators must evaluate their operands in SCALAR context to get boolean values
2. **Two code paths**: Changes need to be made in both EmitterVisitor (JVM bytecode) and BytecodeCompiler (interpreter bytecode)
3. **RUNTIME context trap**: When outer context is RUNTIME, the actual wantarray value is loaded at runtime, which can be VOID. Logical operators must explicitly use SCALAR context, not preserve RUNTIME.
4. **Perl semantics**: Regex matches in LIST context return (1) for success when there are no captures (non-global)
5. **Pattern**: The context save/restore pattern is:
   ```java
   int savedContext = currentCallContext;
   currentCallContext = RuntimeContextType.SCALAR;
   node.operand.accept(this);
   currentCallContext = savedContext;
   ```
6. **Last statement issue**: When the postfix if is the last statement in a program, the outer context is RUNTIME (not VOID), which exposed the RUNTIME context bug in EmitterVisitor

## Known Issues

There appears to be a separate, very specific issue with certain regex patterns containing octal escapes after 4 or more repeated characters (e.g., `"bbbb\337e" =~ /bbbb\337e/` fails). This is unrelated to the context propagation fixes and requires separate investigation.

## References

- Previous work: `dev/prompts/20260218_interpreter_negated_regex_match.md`
- Skill guide: `dev/interpreter/SKILL.md`
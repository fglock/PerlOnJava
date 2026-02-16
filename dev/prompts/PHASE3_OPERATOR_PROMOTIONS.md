# Phase 3: OperatorHandler Promotions - Strategy

**Date**: 2026-02-16
**Status**: Planning
**Target**: Promote 200+ OperatorHandler operations to direct opcodes

## Overview

OperatorHandler contains **231 operators** that currently use ASM INVOKESTATIC calls (6 bytes each). Promoting these to direct opcodes (2 bytes each) provides:
- **10-100x performance improvement** (direct dispatch vs method call)
- **4 bytes saved per operation** (6 bytes INVOKESTATIC → 2 bytes opcode)
- **Better CPU i-cache** usage (fewer instructions)

## Current Architecture

```java
// OperatorHandler maps symbols to method calls
put("+", "add", "org/perlonjava/operators/MathOperators");
put("-", "subtract", "org/perlonjava/operators/MathOperators");
// ... 231 operators total
```

```java
// EmitOperatorNode emits INVOKESTATIC (6 bytes)
methodVisitor.visitMethodInsn(
    INVOKESTATIC,
    "org/perlonjava/operators/MathOperators",
    "add",
    descriptor
);
```

## Target Architecture

```java
// Direct opcode in BytecodeCompiler (2 bytes)
emit(Opcodes.OP_ADD);
emitReg(rd);
emitReg(rs1);
emitReg(rs2);
```

```java
// BytecodeInterpreter handles directly
case Opcodes.OP_ADD:
    int rd = bytecode[pc++];
    int rs1 = bytecode[pc++];
    int rs2 = bytecode[pc++];
    registers[rd] = MathOperators.add(registers[rs1], registers[rs2]);
    return pc;
```

## Opcode Space Allocation

**Opcodes 200-2999**: OperatorHandler promotions (CONTIGUOUS blocks by category)

```
200-299     Reserved (100 slots)
300-399     Comparison Operators (100 slots)
400-499     Math Operators (100 slots)
500-599     Bitwise Operators (100 slots)
600-699     String Operators (100 slots)
700-799     List Operators (100 slots)
800-899     Hash Operators (100 slots)
900-999     I/O Operators (100 slots)
1000-1099   Type/Cast Operators (100 slots)
1100-1199   Special Operators (100 slots)
1200-2999   Reserved (1800 slots)
```

**CRITICAL**: Keep each category CONTIGUOUS for JVM tableswitch optimization!

## Promotion Strategy

### Priority Tiers

**Tier 1: Hot Path Operators** (Promote First)
- Already have direct opcodes in BytecodeInterpreter
- Used in tight loops
- Examples: ADD, SUB, MUL, DIV, MOD (already done in Phase 1!)

**Tier 2: Common Operators** (Promote Next, ~20 operators)
- Frequently used in typical Perl code
- Measurable performance impact
- Easy to implement

**Tier 3: Specialized Operators** (~50 operators)
- Used in specific domains
- Moderate impact

**Tier 4: Rare Operators** (~160 operators)
- Seldom used
- Can stay as OperatorHandler calls

## Candidate Analysis: Tier 2 (Common Operators)

### Math Operators (400-419) - 20 ops
| Opcode | Symbol | Method | Priority | Notes |
|--------|--------|--------|----------|-------|
| 400 | ** | pow | HIGH | Exponentiation |
| 401 | abs | abs | HIGH | Absolute value |
| 402 | int | integer | HIGH | Integer conversion |
| 403 | sqrt | sqrt | MEDIUM | Square root |
| 404 | log | log | MEDIUM | Logarithm |
| 405 | exp | exp | MEDIUM | Exponential |
| 406 | sin | sin | LOW | Trigonometry |
| 407 | cos | cos | LOW | Trigonometry |
| 408 | atan2 | atan2 | LOW | Trigonometry |

### Bitwise Operators (500-519) - 20 ops
| Opcode | Symbol | Method | Priority | Notes |
|--------|--------|--------|----------|-------|
| 500 | & | bitwiseAnd | HIGH | Bitwise AND |
| 501 | \| | bitwiseOr | HIGH | Bitwise OR |
| 502 | ^ | bitwiseXor | HIGH | Bitwise XOR |
| 503 | ~ | bitwiseNot | HIGH | Bitwise NOT |
| 504 | << | shiftLeft | MEDIUM | Left shift |
| 505 | >> | shiftRight | MEDIUM | Right shift |

### String Operators (600-619) - 20 ops
| Opcode | Symbol | Method | Priority | Notes |
|--------|--------|--------|----------|-------|
| 600 | . | concat | HIGH | Already in BytecodeInterpreter! |
| 601 | x | repeat | HIGH | Already in BytecodeInterpreter! |
| 602 | uc | uc | MEDIUM | Uppercase |
| 603 | lc | lc | MEDIUM | Lowercase |
| 604 | ucfirst | ucfirst | MEDIUM | Uppercase first |
| 605 | lcfirst | lcfirst | MEDIUM | Lowercase first |
| 606 | quotemeta | quotemeta | LOW | Quote metacharacters |
| 607 | chr | chr | MEDIUM | Character from code |
| 608 | ord | ord | MEDIUM | Code from character |

### Comparison Operators (300-319) - 20 ops
| Opcode | Symbol | Method | Priority | Notes |
|--------|--------|--------|----------|-------|
| 300 | < | lessThan | HIGH | Already in BytecodeInterpreter! |
| 301 | <= | lessThanOrEqual | HIGH | Already in BytecodeInterpreter! |
| 302 | > | greaterThan | HIGH | Already in BytecodeInterpreter! |
| 303 | >= | greaterThanOrEqual | HIGH | Already in BytecodeInterpreter! |
| 304 | == | numericEqual | HIGH | Already in BytecodeInterpreter! |
| 305 | != | numericNotEqual | HIGH | Already in BytecodeInterpreter! |
| 306 | <=> | compareNum | HIGH | Already in BytecodeInterpreter! |
| 307 | lt | stringLessThan | MEDIUM | String comparison |
| 308 | le | stringLessThanOrEqual | MEDIUM | String comparison |
| 309 | gt | stringGreaterThan | MEDIUM | String comparison |
| 310 | ge | stringGreaterThanOrEqual | MEDIUM | String comparison |
| 311 | eq | stringEqual | HIGH | Already in BytecodeInterpreter! |
| 312 | ne | stringNotEqual | HIGH | Already in BytecodeInterpreter! |
| 313 | cmp | cmp | MEDIUM | String three-way comparison |

## Implementation Steps (Per Operator)

1. **Add opcode constant** in Opcodes.java (in CONTIGUOUS range)
2. **Update EmitOperatorNode** to emit opcode instead of INVOKESTATIC
3. **Add case in BytecodeInterpreter** (in appropriate range delegation method)
4. **Test thoroughly** with unit tests
5. **Measure performance gain** with benchmarks

## Automation Opportunity

Most operators follow the same pattern. A script could generate:
- Opcode constants (batch)
- BytecodeInterpreter case statements (batch)
- EmitOperatorNode mappings (batch)

## Milestones

**Milestone 1**: Promote 10 high-priority operators (Math + Bitwise)
- Expected: ~2-5x speedup for mathematical Perl code
- Effort: 1-2 days

**Milestone 2**: Promote 20 string/comparison operators
- Expected: ~3-10x speedup for string-heavy Perl code
- Effort: 2-3 days

**Milestone 3**: Promote 50 specialized operators
- Expected: Domain-specific speedups
- Effort: 1 week

**Milestone 4**: Complete remaining operators
- Expected: Complete coverage
- Effort: Ongoing (months)

## Benchmarking Strategy

Create microbenchmarks for each promoted operator:
```perl
# Benchmark: 10M iterations of operator
my $x = 0;
for (1..10_000_000) {
    $x = $x + 1;  # or other operator
}
```

Measure before/after promotion:
- Time (should improve 2-10x)
- Bytecode size (should decrease 4 bytes per op)
- Method sizes (must stay under 8000 bytes)

## Method Size Management

BytecodeInterpreter.execute() is at **7,517 bytes** (483 bytes from limit).

**Strategy**:
- Add operators to existing range delegation methods (executeArithmetic, etc.)
- If method approaches 7,000 bytes, split into sub-groups
- Example: Split executeArithmetic into executeBasicMath + executeAdvancedMath

## Phase 3 Recommended Start

**Start with**: 10 high-impact operators

1. **Math** (400-404): pow, abs, int, sqrt, log
2. **Bitwise** (500-505): &, |, ^, ~, <<, >>

These are:
- Frequently used in real Perl code
- Easy to implement (2-register or 3-register ops)
- Measurable performance impact
- Won't significantly increase method sizes

**Expected Result**:
- 2-5x speedup for mathematical operations
- Proof of concept for remaining promotions
- Validation of opcode space allocation strategy

---

**Next Steps**:
1. Profile real Perl code to identify actual hot operators
2. Implement Tier 2 operators (20 ops)
3. Benchmark and document gains
4. Continue gradual promotion over multiple releases

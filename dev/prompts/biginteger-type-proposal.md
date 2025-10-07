# BIGINTEGER RuntimeScalarType Proposal

## Problem Statement

PerlOnJava currently emulates 32-bit Perl behavior (ivsize=4, nv_preserves_uv_bits=32), which means:
- Integer values > 2^31-1 or < -2^31 are stored as doubles
- This causes precision loss for values > 2^53 (about 9.0e15)
- Large integer literals like 2^63-1 (9223372036854775807) lose precision when stored as doubles
- This affects pack/unpack operations, especially for Q format (64-bit unsigned)

### Current Workaround
We store large integer literals as strings to preserve precision, but this creates issues:
- Requires special handling in pack/unpack operations
- Risk of infinite recursion in getInt()/getLong()/getDouble() methods
- Inconsistent behavior between literal values and computed values

## Proposed Solution

Add a new `BIGINTEGER` type to RuntimeScalarType that uses Java's BigInteger class for arbitrary-precision integer arithmetic.

### When to Use BIGINTEGER
- Integer literals that exceed 32-bit int range but need exact precision
- Results of integer operations that overflow 32-bit range
- Unpacked values from Q/q formats that exceed double precision range

## Scope of Changes

### 1. Core Type System
- Add `BIGINTEGER` to RuntimeScalarType enum
- Add BigInteger field to RuntimeScalar (or use existing Object value field)
- Add constructor: `RuntimeScalar(BigInteger value)`
- Update initializeWithLong() to use BIGINTEGER for large values

### 2. Numeric Accessors (ALL need updates)
- getInt() - convert BigInteger to int
- getLong() - convert BigInteger to long  
- getDouble() - convert BigInteger to double
- getBigInteger() - new method to get BigInteger value
- getInteger() - handle BIGINTEGER case

### 3. Arithmetic Operations (ALL need BIGINTEGER cases)
- Addition: MathOperators.add()
- Subtraction: MathOperators.subtract()
- Multiplication: MathOperators.multiply()
- Division: MathOperators.divide()
- Modulo: MathOperators.modulo()
- Power: MathOperators.power()
- Increment/Decrement operations

### 4. Bitwise Operations
- BitwiseOperators.and()
- BitwiseOperators.or()
- BitwiseOperators.xor()
- BitwiseOperators.not()
- BitwiseOperators.leftShift()
- BitwiseOperators.rightShift()

### 5. Comparison Operations
- CompareOperators.compare()
- CompareOperators.equals()
- All numeric comparison operators (<, >, <=, >=, ==, !=, <=>)

### 6. String Conversion
- toString() - convert BigInteger to string representation
- Stringification contexts

### 7. Pack/Unpack Operations
- NumericPackHandler - handle BIGINTEGER type for Q/q formats
- NumericFormatHandler - create BIGINTEGER for large unpacked values
- Checksum calculations using BigInteger

### 8. Type Coercion
- NumberParser.parseNumber() - return BIGINTEGER for large values
- Type promotion rules (when to promote INT -> BIGINTEGER)
- Type demotion rules (when to convert BIGINTEGER -> DOUBLE)

### 9. Serialization/Deserialization
- Storable support for BIGINTEGER
- Data::Dumper representation

### 10. Performance Considerations
- BigInteger operations are slower than primitive operations
- Need to minimize unnecessary promotions
- Consider caching common BigInteger values

## Implementation Strategy

### Phase 1: Core Support
1. Add BIGINTEGER type to enum
2. Update RuntimeScalar constructors and basic accessors
3. Handle BIGINTEGER in toString() and basic conversions

### Phase 2: Arithmetic Operations
1. Update all math operators to handle BIGINTEGER
2. Implement promotion rules (INT + BIGINTEGER = BIGINTEGER)
3. Add tests for overflow scenarios

### Phase 3: Pack/Unpack Integration
1. Update pack to handle BIGINTEGER values
2. Update unpack to create BIGINTEGER for large values
3. Fix checksum calculations

### Phase 4: Full Integration
1. Update all remaining operations
2. Comprehensive testing
3. Performance optimization

## Pros
- Exact representation of large integers (true 64-bit and beyond)
- Matches Perl's behavior on 64-bit systems
- Eliminates precision loss issues
- Cleaner than string workarounds

## Cons
- Massive scope - touches hundreds of locations in codebase
- Performance overhead of BigInteger operations
- Increases complexity of type system
- Risk of introducing bugs in existing operations
- May break existing code that assumes specific type behaviors

## Alternative Approaches

### 1. Keep String Workaround
- Store large integers as strings
- Convert to BigInteger only when needed for operations
- Less invasive but more fragile

### 2. Use Long Everywhere
- Upgrade from 32-bit to 64-bit integer support
- Still loses precision for values > 2^63
- Doesn't fully solve the problem

### 3. Hybrid Approach
- Use BIGINTEGER only for pack/unpack operations
- Keep existing type system for everything else
- Limited scope but inconsistent behavior

## Test Cases

```perl
# Large integer literals
my $big = 9223372036854775807;  # 2^63-1
my $bigger = 18446744073709551615;  # 2^64-1

# Arithmetic with large values
my $sum = $big + $big;
my $product = $big * 2;

# Pack/unpack Q format
my $packed = pack("Q", $bigger);
my ($unpacked) = unpack("Q", $packed);

# Checksum calculations
my @list = (0, 1, $big, $big + 1, $bigger);
my $checksum = unpack("%Q*", pack("Q*", @list));

# Bitwise operations
my $shifted = $big << 1;
my $anded = $bigger & 0xFFFF;
```

## Decision Points

1. **When to promote to BIGINTEGER?**
   - Only when value exceeds int range?
   - Only when value exceeds long range?
   - Only when precision would be lost as double?

2. **When to demote from BIGINTEGER?**
   - Never (keep precision)?
   - When value fits in smaller type?
   - Based on context?

3. **How to handle mixed-type operations?**
   - Always promote to BIGINTEGER?
   - Promote only when result would overflow?
   - Context-dependent promotion?

## References

- Perl's SV type system and IV/UV handling
- Java BigInteger documentation
- Similar implementations in other Perl ports (Perl6/Raku, etc.)

## Next Steps

1. Prototype BIGINTEGER type addition
2. Benchmark performance impact
3. Identify all affected code locations
4. Create comprehensive test suite
5. Implement in phases with thorough testing

---

*This document created during debugging of pack/unpack Q format checksums (2024-10-07)*
*Issue: Off-by-one errors in Q format checksums due to precision loss*

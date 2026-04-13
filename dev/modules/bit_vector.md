# Bit::Vector Support for PerlOnJava

## Overview

Bit::Vector 7.4 is a CPAN module for efficient bit vector manipulation, providing
set operations, arithmetic, string conversions, and matrix operations on
arbitrary-length bit vectors. It is a dependency of `Date::Calc`, `Date::Pcalc`,
and other modules.

The module is **pure XS/C** with no pure-Perl fallback. PerlOnJava needs a Java XS
implementation (`BitVector.java`) to replace the C library.

## Current Status

**Module version:** Bit::Vector 7.4 (23 test programs)
**CPAN distribution:** `STBEY/Bit-Vector-7.4.tar.gz`

### Results

| Date | Programs Passed | Subtests | Issue |
|------|----------------|----------|-------|
| 2026-04-13 | 0/23 | 0 | `Can't load loadable object for module Bit::Vector: no Java XS implementation available` |

All 23 test files fail at `require Bit::Vector` because `DynaLoader::bootstrap`
reaches XSLoader's stage 4 (no Java class, no @ISA parent, no PP companion)
and dies.

---

## Architecture

### Module Structure

```
Bit::Vector          (Vector.pm)         - XS bootstrap, STORABLE_freeze/thaw
Bit::Vector::Overload (Overload.pm)      - Pure Perl: operator overloading via XS primitives
Bit::Vector::String   (String.pm)        - Pure Perl: Oct/String import/export via XS primitives
```

Only `Bit::Vector` itself needs a Java XS implementation. The companion `.pm` files
are pure Perl and will work automatically once the XS primitives are available.

### Object Representation

In C, a `Bit::Vector` object is a blessed scalar ref whose inner value is a
read-only integer (the C pointer address). Key behaviors the tests verify:

- `${$vec}` is a non-zero integer (read-only)
- After `DESTROY`, `${$vec}` becomes 0
- Double-`DESTROY` must not crash
- `ref($vec)` returns `"Bit::Vector"`

**Java approach:** Store a Java `BitSet` (or `long[]`) inside PerlOnJava's Java
object embedding. The blessed scalar ref holds an opaque integer ID mapped to
the Java object via a static `HashMap<Long, BitVectorData>`. The inner integer
is made read-only. `DESTROY` removes the entry and zeros the inner value.

### Backing Store: `java.util.BitSet` vs `long[]`

`java.util.BitSet` covers most operations but auto-grows and has no fixed-size
concept. Bit::Vector has a fixed size (`Size()`) and two's-complement arithmetic
semantics that depend on the exact bit width.

**Recommendation:** Use a wrapper class `BitVectorData` containing:
- `int size` — the declared bit count (immutable after creation, changed only by `Resize`)
- `java.util.BitSet bits` — the actual bit storage

All operations must respect `size` as the upper bound. Arithmetic operations
(add, subtract, multiply, divide, etc.) must mask results to `size` bits and
use two's-complement interpretation where the XS does so.

---

## XS API — Complete Method List (104 Perl-callable methods)

### Class Methods

| Method | Java Impl | Notes |
|--------|-----------|-------|
| `Version()` | Return `"7.4"` | Error if called with args |
| `Word_Bits()` | Return `64` | Java long is 64 bits |
| `Long_Bits()` | Return `64` | Java long is 64 bits |

### Constructors (return new blessed objects)

| Method | Aliases | Signature | Notes |
|--------|---------|-----------|-------|
| `Create(bits)` / `Create(bits, count)` | `new` | class + bits [+ count] | count>1 returns list of objects |
| `new_Hex(bits, string)` | — | class + bits + hex string | Create + from_Hex |
| `new_Bin(bits, string)` | — | class + bits + bin string | Create + from_Bin |
| `new_Dec(bits, string)` | — | class + bits + dec string | Create + from_Dec |
| `new_Enum(bits, string)` | — | class + bits + enum string | Create + from_Enum |
| `Shadow()` | — | self | New empty vector, same size |
| `Clone()` | — | self | New vector, copy of self |
| `Concat(other)` | — | self + other | New vector = self . other |
| `Concat_List(...)` | — | variadic | New vector = concat of all |

### Destructor / Size

| Method | Signature | Notes |
|--------|-----------|-------|
| `DESTROY()` | self | Free backing data, zero inner ref |
| `Size()` | self | Return bit count |
| `Resize(bits)` | self + bits | Change size, preserve/truncate data |
| `Unfake(bits)` | self + bits | Internal: used by Storable |

### Bulk Operations (mutate self)

| Method | Signature | Notes |
|--------|-----------|-------|
| `Copy(src)` | dest + src | dest = src (same size required) |
| `Empty()` | self | Clear all bits |
| `Fill()` | self | Set all bits |
| `Flip()` | self | Toggle all bits |
| `Primes()` | self | Sieve of Eratosthenes |
| `Reverse(src)` | dest + src | Bit reversal |

### Predicates

| Method | Signature | Return |
|--------|-----------|--------|
| `is_empty()` | self | boolean |
| `is_full()` | self | boolean |
| `equal(other)` | self + other | boolean |
| `Lexicompare(other)` | self + other | -1, 0, or 1 |
| `Compare(other)` | self + other | -1, 0, or 1 (signed) |

### String Conversion

| Method | Aliases | Signature | Notes |
|--------|---------|-----------|-------|
| `to_Hex()` | `to_String` | self | Return hex string |
| `from_Hex(str)` | `from_string` | self + str | Parse hex into self |
| `to_Bin()` | — | self | Return binary string |
| `from_Bin(str)` | — | self + str | Parse binary into self |
| `to_Dec()` | — | self | Return decimal string (signed) |
| `from_Dec(str)` | — | self + str | Parse decimal into self |
| `to_Enum()` | `to_ASCII` | self | Return enumeration string |
| `from_Enum(str)` | `from_ASCII` | self + str | Parse enumeration into self |

### Single-Bit Operations

| Method | Aliases | Signature | Return |
|--------|---------|-----------|--------|
| `Bit_Off(index)` | — | self + index | void |
| `Bit_On(index)` | — | self + index | void |
| `bit_flip(index)` | `flip` | self + index | boolean (old value) |
| `bit_test(index)` | `contains`, `in` | self + index | boolean |
| `Bit_Copy(index, bit)` | — | self + index + bool | void |
| `LSB(bit)` | — | self + bool | void (set bit 0) |
| `MSB(bit)` | — | self + bool | void (set top bit) |
| `lsb()` | — | self | boolean (read bit 0) |
| `msb()` | — | self | boolean (read top bit) |

### Shift / Rotate / Move

| Method | Signature | Return |
|--------|-----------|--------|
| `rotate_left()` | self | boolean (ejected bit) |
| `rotate_right()` | self | boolean (ejected bit) |
| `shift_left(carry)` | self + carry_in | boolean (carry_out) |
| `shift_right(carry)` | self + carry_in | boolean (carry_out) |
| `Move_Left(bits)` | self + count | void |
| `Move_Right(bits)` | self + count | void |
| `Insert(offset, count)` | self + offset + count | void (insert zero bits) |
| `Delete(offset, count)` | self + offset + count | void (delete bits) |

### Interval Operations

| Method | Aliases | Signature | Return |
|--------|---------|-----------|--------|
| `Interval_Empty(min, max)` | `Empty_Interval` | self + min + max | void |
| `Interval_Fill(min, max)` | `Fill_Interval` | self + min + max | void |
| `Interval_Flip(min, max)` | `Flip_Interval` | self + min + max | void |
| `Interval_Reverse(min, max)` | — | self + min + max | void |
| `Interval_Scan_inc(start)` | — | self + start | (min, max) or empty list |
| `Interval_Scan_dec(start)` | — | self + start | (min, max) or empty list |
| `Interval_Copy(src, Xoff, Yoff, len)` | — | dest + src + offsets + len | void |
| `Interval_Substitute(src, Xoff, Xlen, Yoff, Ylen)` | — | dest + src + offsets + lens | void (may resize) |

### Set Operations (3-operand: dest = op(y, z))

| Method | Aliases | Signature |
|--------|---------|-----------|
| `Union(y, z)` | `Or` | dest + y + z |
| `Intersection(y, z)` | `And` | dest + y + z |
| `Difference(y, z)` | `AndNot` | dest + y + z |
| `ExclusiveOr(y, z)` | `Xor` | dest + y + z |
| `Complement(src)` | `Not` | dest + src |
| `subset(other)` | `inclusion` | self + other → boolean |

### Set Metrics

| Method | Signature | Return |
|--------|-----------|--------|
| `Norm()` | self | popcount (number of set bits) |
| `Norm2()` | self | popcount variant |
| `Norm3()` | self | popcount variant |
| `Min()` | self | min set bit index, or -1 |
| `Max()` | self | max set bit index, or -1 |

### Arithmetic

| Method | Aliases | Signature | Return |
|--------|---------|-----------|--------|
| `increment()` | — | self | boolean (carry) |
| `decrement()` | — | self | boolean (borrow) |
| `add(y, z, carry)` | — | dest + y + z + carry | (carry[, overflow]) |
| `subtract(y, z, carry)` | `sub` | dest + y + z + carry | (carry[, overflow]) |
| `inc(src)` | — | dest + src | boolean |
| `dec(src)` | — | dest + src | boolean |
| `Negate(src)` | `Neg` | dest + src | void |
| `Absolute(src)` | `Abs` | dest + src | void |
| `Sign()` | — | self | -1, 0, or 1 |
| `Multiply(y, z)` | — | dest + y + z | void |
| `Divide(x, y, remainder)` | — | quot + x + y + rem | void |
| `GCD(...)` | — | 3 or 5 objects | void |
| `Power(base, exp)` | — | dest + base + exp | void |

### Block / Word / Chunk / Index List I/O

| Method | Signature | Notes |
|--------|-----------|-------|
| `Block_Store(buffer)` | self + string | Raw bytes into vector |
| `Block_Read()` | self | Raw bytes from vector |
| `Word_Size()` | self | Number of machine words |
| `Word_Store(offset, value)` | self + off + val | Store one word |
| `Word_Read(offset)` | self + off | Read one word |
| `Word_List_Store(...)` | self + values | Store all words |
| `Word_List_Read()` | self | Read all words |
| `Word_Insert(offset, count)` | self + off + cnt | Insert zero words |
| `Word_Delete(offset, count)` | self + off + cnt | Delete words |
| `Chunk_Store(chunksize, offset, value)` | self + bits + off + val | Store arbitrary chunk |
| `Chunk_Read(chunksize, offset)` | self + bits + off | Read arbitrary chunk |
| `Chunk_List_Store(chunksize, ...)` | self + bits + values | Store chunk list |
| `Chunk_List_Read(chunksize)` | self + bits | Read chunk list |
| `Index_List_Remove(...)` | self + indices | Clear listed bits |
| `Index_List_Store(...)` | self + indices | Set listed bits |
| `Index_List_Read()` | self | List of set-bit indices |

### Matrix Operations

| Method | Signature | Notes |
|--------|-----------|-------|
| `Matrix_Multiplication(X,Xr,Xc,Y,Yr,Yc,Z,Zr,Zc)` | 9 args | Boolean matrix multiply |
| `Matrix_Product(X,Xr,Xc,Y,Yr,Yc,Z,Zr,Zc)` | 9 args | Boolean matrix product |
| `Matrix_Closure(ref, rows, cols)` | self + rows + cols | Transitive closure |
| `Matrix_Transpose(X,Xr,Xc,Y,Yr,Yc)` | 6 args | Boolean matrix transpose |

---

## Implementation Plan

### Phase 1: Core Infrastructure + Constructors/Destructors

**Goal:** `t/00_____version.t` and `t/01_________new.t` and `t/02_____destroy.t` pass.

1. Create `BitVector.java` extending `PerlModuleBase`
   - Constructor: `super("Bit::Vector", false)`
   - Inner class `BitVectorData` with `int size` and `java.util.BitSet bits`
   - Static `ConcurrentHashMap<Long, BitVectorData>` for object storage
   - Atomic ID counter for assigning opaque integer IDs

2. Implement `initialize()` registering all methods

3. Implement:
   - `Version()`, `Word_Bits()`, `Long_Bits()`
   - `Create()` / `new()` — bless a read-only scalar ref holding the ID
   - `DESTROY()` — remove from map, zero inner ref
   - `Size()`

4. Object creation helper: creates a `BitVectorData`, stores it in the map,
   returns a blessed scalar ref `\$id` with `$id` read-only and non-zero.

**Files:** `src/main/java/org/perlonjava/runtime/perlmodule/BitVector.java`

### Phase 2: Bulk + Bit + Predicate Operations

**Goal:** `t/03__operations.t` passes.

1. Implement: `Empty`, `Fill`, `Flip`, `Copy`, `Reverse`, `Primes`
2. Implement: `Bit_On`, `Bit_Off`, `bit_test`/`contains`/`in`, `bit_flip`/`flip`, `Bit_Copy`
3. Implement: `LSB`, `MSB`, `lsb`, `msb`
4. Implement: `is_empty`, `is_full`, `equal`

### Phase 3: Set Operations

**Goal:** `t/04___functions.t`, `t/05______primes.t`, `t/06______subset.t`, `t/07_____compare.t` pass.

1. Implement: `Union`/`Or`, `Intersection`/`And`, `Difference`/`AndNot`, `ExclusiveOr`/`Xor`
2. Implement: `Complement`/`Not`, `subset`/`inclusion`
3. Implement: `Lexicompare`, `Compare`
4. Implement: `Norm`, `Norm2`, `Norm3`, `Min`, `Max`
5. Implement: `Shadow`, `Clone`, `Concat`, `Concat_List`

### Phase 4: String Conversions

**Goal:** `t/12______string.t` passes.

1. Implement: `to_Hex`, `from_Hex` (+ aliases `to_String`, `from_string`)
2. Implement: `to_Bin`, `from_Bin`
3. Implement: `to_Dec`, `from_Dec` — use `java.math.BigInteger` for conversion
4. Implement: `to_Enum`, `from_Enum` (+ aliases `to_ASCII`, `from_ASCII`)
5. Implement: `new_Hex`, `new_Bin`, `new_Dec`, `new_Enum`

### Phase 5: Resize, Intervals, Shifts

**Goal:** `t/08______resize.t`, `t/09__parameters.t`, `t/10___intervals.t`, `t/11_______shift.t` pass.

1. Implement: `Resize`, `Unfake`
2. Implement: `Interval_Empty/Fill/Flip/Reverse` (+ aliases)
3. Implement: `Interval_Scan_inc`, `Interval_Scan_dec`
4. Implement: `Interval_Copy`, `Interval_Substitute`
5. Implement: `shift_left`, `shift_right`, `rotate_left`, `rotate_right`
6. Implement: `Move_Left`, `Move_Right`, `Insert`, `Delete`

### Phase 6: Arithmetic

**Goal:** `t/13___increment.t`, `t/14_______empty.t`, `t/15_________add.t`, `t/16____subtract.t`, `t/17_________gcd.t` pass.

1. Implement: `increment`, `decrement`
2. Implement: `add`, `subtract`/`sub` (with carry and overflow)
3. Implement: `inc`, `dec`, `Negate`/`Neg`, `Absolute`/`Abs`, `Sign`
4. Implement: `Multiply`, `Divide`, `GCD`, `Power`

**Note:** Arithmetic is the most complex phase. The C implementation uses
fixed-width two's-complement semantics. Use `java.math.BigInteger` internally
for multiply/divide/GCD/power, converting to/from the fixed-width bit vector.

### Phase 7: Word / Chunk / Block / Index List I/O

**Goal:** `t/28___chunklist.t` passes.

1. Implement: `Block_Store`, `Block_Read`
2. Implement: `Word_Size`, `Word_Store`, `Word_Read`, `Word_List_Store`, `Word_List_Read`
3. Implement: `Word_Insert`, `Word_Delete`
4. Implement: `Chunk_Store`, `Chunk_Read`, `Chunk_List_Store`, `Chunk_List_Read`
5. Implement: `Index_List_Remove`, `Index_List_Store`, `Index_List_Read`

### Phase 8: Overloaded Operators + Matrix

**Goal:** `t/30__overloaded.t`, `t/40___auxiliary.t` pass.

1. Verify `Bit::Vector::Overload` works (pure Perl, should work once primitives exist)
2. Verify `Bit::Vector::String` works (pure Perl)
3. Implement: `Matrix_Multiplication`, `Matrix_Product`, `Matrix_Closure`, `Matrix_Transpose`

### Phase 9: Serialization + Final

**Goal:** `t/50_freeze_thaw.t`, `t/51_file_nstore.t` pass. All 23/23 tests green.

1. Verify `STORABLE_freeze` / `STORABLE_thaw` work (pure Perl in `Vector.pm`)
2. `Unfake` must work correctly for Storable deserialization
3. Full test run: `./jcpan -t Bit::Vector`

---

## Test Files (23 total)

| File | Tests | Category |
|------|-------|----------|
| `t/00_____version.t` | 15 | Version, Word_Bits, Long_Bits |
| `t/01_________new.t` | 131 | Constructor, blessed ref, read-only inner value |
| `t/02_____destroy.t` | 15 | DESTROY behavior, double-destroy |
| `t/03__operations.t` | 232 | Flip, Fill, Empty, Copy, bit ops, set ops |
| `t/04___functions.t` | — | Set functions |
| `t/05______primes.t` | — | Sieve of Eratosthenes |
| `t/06______subset.t` | — | subset/inclusion |
| `t/07_____compare.t` | — | Lexicompare, Compare |
| `t/08______resize.t` | — | Resize |
| `t/09__parameters.t` | — | Parameter validation / error handling |
| `t/10___intervals.t` | — | Interval operations |
| `t/11_______shift.t` | — | Shift, rotate, move, insert, delete |
| `t/12______string.t` | — | String conversions (hex, bin, dec, enum) |
| `t/13___increment.t` | — | increment, decrement |
| `t/14_______empty.t` | — | Empty-vector edge cases |
| `t/15_________add.t` | — | add, subtract with carry/overflow |
| `t/16____subtract.t` | — | subtract |
| `t/17_________gcd.t` | — | GCD, Multiply, Divide, Power |
| `t/28___chunklist.t` | — | Chunk/Word/Block/Index list I/O |
| `t/30__overloaded.t` | — | Operator overloading (Overload.pm) |
| `t/40___auxiliary.t` | — | Auxiliary / String.pm functions |
| `t/50_freeze_thaw.t` | — | Storable freeze/thaw |
| `t/51_file_nstore.t` | — | Storable nstore to file |

---

## Key Implementation Details

### Read-Only Inner Value

The C module stores a pointer as a read-only IV inside the blessed scalar ref.
Tests verify:
```perl
my $v = Bit::Vector->new(32);
${$v} != 0;            # true — non-zero opaque ID
eval { ${$v} = 42 };   # dies: "Modification of a read-only value attempted"
```

The Java implementation must use `RuntimeScalarReadOnly` or equivalent to make
the inner scalar non-writable. After `DESTROY`, the inner value must be set to 0
(this requires special handling since it's normally read-only).

### Error Handling

The C library throws Perl exceptions (via `croak`) for:
- Null pointer / destroyed object
- Index out of range
- Size mismatch between operands
- Division by zero
- Invalid string format

The Java implementation must replicate these error messages — the test suite
checks error text via `eval { ... }; $@ =~ /pattern/`.

### Word Size

The C implementation uses the platform's machine word size. The tests check
`Word_Bits() >= 32` and `Long_Bits() >= 32`. On Java, both should return 64
(since Java's `long` is 64 bits).

### Norm2 and Norm3

- `Norm()` = popcount (number of set bits)
- `Norm2()` = number of set bits starting from bit 0 up to and including the highest set bit, minus the set bits. In other words: `(Max() + 1) - Norm()` when non-empty, 0 when empty. (Number of clear bits below the highest set bit, plus 1.)
- `Norm3()` = `Max() + 1` when non-empty, 0 when empty. (Position of highest set bit plus 1.)

Verify exact semantics against the C source (`BitVector.c`) during implementation.

---

## Estimated Size

~2000–2500 lines of Java, comparable to POSIX.java (1481 lines) or
Storable.java (924 lines). The arithmetic phase (Phase 6) is the most complex.

---

## Dependencies

- `java.util.BitSet` — primary backing store
- `java.math.BigInteger` — for decimal string conversion and multiply/divide/GCD/power
- `java.util.concurrent.ConcurrentHashMap` — object ID → data mapping
- `java.util.concurrent.atomic.AtomicLong` — ID generation

No external dependencies. All backed by JDK standard library.

---

## Related Documents

- `dev/modules/xs_fallback.md` — XS fallback mechanism (how Java XS classes are discovered)
- `dev/design/xml_parser_xs.md` — Case study: XML::Parser Java XS (similar pattern)
- Existing Java XS implementations to use as templates:
  - `Clone.java` (simplest pattern)
  - `DigestSHA.java` (OO module wrapping Java APIs)
  - `POSIX.java` (largest, shows method aliasing)
  - `Storable.java` (complex serialization)

# Weak Reference Support Design

**Status**: Analysis complete, implementation deferred  
**Created**: 2026-03-17  
**Related**: moo_support.md (Phase 31), destroy_support.md

## ⚠️ Key Concern: Memory Impact

Adding a field to `RuntimeScalar` has significant memory implications:

- `RuntimeScalar` is one of the most frequently instantiated objects
- Every Perl variable, intermediate value, and function argument creates one
- A typical program may have millions of RuntimeScalar instances
- Adding even a `boolean` field (1 byte + alignment) could add 4-8 bytes per instance
- **Estimated impact**: 4-8 MB additional memory per million scalars

### Alternative Approaches to Explore

1. **External WeakHashMap registry**: Track weak scalars in a side table
   - `WeakHashMap<RuntimeScalar, Boolean>` for weak status
   - Only allocates for weak refs (rare), not all scalars
   - Lookup overhead on `isweak()` calls
   
2. **Sentinel value in `value` field**: Use a wrapper type
   - `value = new WeakRefWrapper(originalValue)`
   - Check `instanceof WeakRefWrapper` instead of flag
   - No new field, but type check overhead

3. **Bit-packing in `type` field**: Use unused bits
   - `type` is `int` (32 bits) but only uses ~20 enum values
   - Could reserve high bit for `isWeak` flag
   - Requires careful bit masking everywhere `type` is used

4. **RuntimeScalarWeak subclass**: Separate class for weak refs
   - Only weak refs pay the memory cost
   - But requires changing reference identity behavior

These alternatives need further analysis before implementation.

## Overview

This document analyzes how to implement Perl's weak references in PerlOnJava.

## Perl Weak Reference Semantics

### Core Functions

```perl
use Scalar::Util qw(weaken isweak unweaken);

weaken($ref);      # Make $ref weak - doesn't keep referent alive
isweak($ref);      # Returns true if $ref is weak
unweaken($ref);    # Make weak ref strong again
```

### Key Behaviors

1. **A weak reference doesn't prevent garbage collection**:
   ```perl
   my $strong = { data => "test" };
   my $weak = $strong;
   weaken($weak);
   
   undef $strong;           # Referent's refcount -> 0
   print defined $weak;     # Prints 0 - $weak is now undef
   ```

2. **Weak refs work normally while referent is alive**:
   ```perl
   my $strong = { data => "test" };
   my $weak = $strong;
   weaken($weak);
   
   print $weak->{data};     # Prints "test" - works fine
   ```

3. **Primary use case - break circular references**:
   ```perl
   package Parent;
   sub new { bless { children => [] }, shift }
   sub add_child {
       my ($self, $child) = @_;
       push @{$self->{children}}, $child;
       $child->{parent} = $self;
       weaken($child->{parent});  # Without this, circular ref!
   }
   ```

4. **isweak() returns the weak status**:
   ```perl
   my $ref = \$x;
   print isweak($ref);  # 0
   weaken($ref);
   print isweak($ref);  # 1
   ```

## Current State in PerlOnJava

Currently `weaken()`, `unweaken()`, and `isweak()` are no-ops/stubs:

```java
// ScalarUtil.java
public static RuntimeList weaken(RuntimeArray args, int ctx) {
    // Placeholder - does nothing
    return new RuntimeScalar().getList();
}

public static RuntimeList isweak(RuntimeArray args, int ctx) {
    // Always returns false
    return new RuntimeList(scalarFalse);
}
```

Test results:
- PerlOnJava: `isweak()` always returns 0, weak refs never become undef
- This causes 20+ test failures in Moo's accessor-weaken tests

## RuntimeScalar Structure

```java
public class RuntimeScalar {
    public int type;      // HASHREFERENCE, ARRAYREFERENCE, REFERENCE, etc.
    public Object value;  // RuntimeHash, RuntimeArray, RuntimeScalar, etc.
}
```

For `$ref = \%hash`:
- `type = HASHREFERENCE`
- `value = RuntimeHash instance`

## Implementation Options

### Option 1: Flag-Only Emulation (Simplest)

Just track whether a reference has been "weakened" without changing behavior.

```java
public class RuntimeScalar {
    public int type;
    public Object value;
    public boolean isWeak;  // NEW FIELD
}
```

**Implementation**:
```java
// weaken()
public static RuntimeList weaken(RuntimeArray args, int ctx) {
    RuntimeScalar ref = args.get(0);
    if (RuntimeScalarType.isReference(ref.type)) {
        ref.isWeak = true;
    }
    return new RuntimeList();
}

// isweak()
public static RuntimeList isweak(RuntimeArray args, int ctx) {
    RuntimeScalar ref = args.get(0);
    return new RuntimeList(new RuntimeScalar(ref.isWeak));
}

// unweaken()
public static RuntimeList unweaken(RuntimeArray args, int ctx) {
    RuntimeScalar ref = args.get(0);
    ref.isWeak = false;
    return new RuntimeList();
}
```

**Pros**:
- Very simple to implement
- `isweak()` returns correct values
- Moo's weak_ref attribute will "work" (set the flag)
- No performance overhead

**Cons**:
- Doesn't actually break circular references
- Weak refs never become undef when referent is "destroyed"
- May not pass tests that check for auto-undef behavior

**Tests enabled**: Many accessor-weaken tests that just check `isweak()` returns true.

---

### Option 2: Java WeakReference Wrapper (More Complete)

Use Java's `java.lang.ref.WeakReference` to actually make references weak.

```java
public class RuntimeScalar {
    public int type;
    public Object value;           // Strong reference OR WeakReference wrapper
    public boolean isWeak;         // Flag to know if value is wrapped
}
```

**Implementation**:
```java
// weaken()
public static RuntimeList weaken(RuntimeArray args, int ctx) {
    RuntimeScalar ref = args.get(0);
    if (RuntimeScalarType.isReference(ref.type) && !ref.isWeak) {
        // Wrap the value in a WeakReference
        ref.value = new WeakReference<>(ref.value);
        ref.isWeak = true;
    }
    return new RuntimeList();
}

// In RuntimeScalar - getter that unwraps
public Object getValue() {
    if (isWeak && value instanceof WeakReference<?> weakRef) {
        Object referent = weakRef.get();
        if (referent == null) {
            // Referent was collected - become undef
            this.type = UNDEF;
            this.value = null;
            this.isWeak = false;
        }
        return referent;
    }
    return value;
}

// isweak()
public static RuntimeList isweak(RuntimeArray args, int ctx) {
    RuntimeScalar ref = args.get(0);
    return new RuntimeList(new RuntimeScalar(ref.isWeak));
}

// unweaken()
public static RuntimeList unweaken(RuntimeArray args, int ctx) {
    RuntimeScalar ref = args.get(0);
    if (ref.isWeak && ref.value instanceof WeakReference<?> weakRef) {
        Object referent = weakRef.get();
        if (referent != null) {
            ref.value = referent;  // Unwrap back to strong
        }
        ref.isWeak = false;
    }
    return new RuntimeList();
}
```

**Pros**:
- Weak refs can actually become undef (after GC)
- Closer to Perl semantics
- Can help break circular references (though timing differs)

**Cons**:
- More complex implementation
- Need to modify all value access points to use getter
- GC timing is non-deterministic (may not match Perl's immediate behavior)
- Performance overhead on every value access

**Tests enabled**: More tests, but timing-sensitive tests may still fail.

---

### Option 3: Hybrid Approach (Recommended)

Combine both approaches:
1. Track `isWeak` flag for `isweak()` to work
2. Optionally use WeakReference internally
3. Check for cleared references at key points (not every access)

```java
public class RuntimeScalar {
    public int type;
    public Object value;
    public boolean isWeak;
    
    // Check and clear if weak reference was collected
    // Called at strategic points, not every access
    public void checkWeakReference() {
        if (isWeak && value instanceof WeakReference<?> weakRef) {
            if (weakRef.get() == null) {
                this.type = UNDEF;
                this.value = null;
                this.isWeak = false;
            }
        }
    }
    
    // Get actual value, unwrapping WeakReference if needed
    public Object getActualValue() {
        if (isWeak && value instanceof WeakReference<?> weakRef) {
            return weakRef.get();  // May return null
        }
        return value;
    }
}
```

**Strategic check points**:
- Before dereferencing (`$ref->{key}`, `$ref->[0]`)
- Before method calls (`$obj->method()`)
- In `defined($ref)` checks
- Not on every assignment or copy (too expensive)

---

## Memory Layout Considerations

### Current: All references are strong
```
$weak ──────────────────────┐
                            ▼
$strong ─────────────► RuntimeHash
                        blessId=42
                        elements={...}
```

### With WeakReference wrapper
```
$weak ───► WeakReference ─ ─ ─► RuntimeHash  (weak link)
                                blessId=42
$strong ─────────────────────► elements={...}
                                    ▲
                                    │ (strong link)
```

When `$strong` goes away and GC runs:
```
$weak ───► WeakReference ─ ─ ─► (null)

$weak.getValue() returns null → $weak becomes undef
```

## Interaction with DESTROY

Weak references and DESTROY are related:

1. In Perl, when refcount hits 0, DESTROY is called, then weak refs become undef
2. In PerlOnJava with WeakReference:
   - GC may collect object at unpredictable times
   - If we implement DESTROY, it would be called when GC runs
   - Weak refs would become undef around the same time

If we DON'T implement DESTROY (current state):
- Objects are never explicitly destroyed
- WeakReferences may become undef when GC runs
- This is still useful for breaking circular refs in long-running programs

## Recommended Implementation Plan

**Note**: Implementation is deferred pending resolution of memory impact concerns.
See "Key Concern: Memory Impact" section above for alternatives to explore.

### Original Phase 1: Flag-Only (Quick Win) - DEFERRED

The original plan was to add `isWeak` field to RuntimeScalar, but this has
unacceptable memory impact. Need to explore alternatives first.

~~1. Add `isWeak` field to RuntimeScalar~~
~~2. Implement `weaken()` to set flag~~
~~3. Implement `isweak()` to return flag~~
~~4. Implement `unweaken()` to clear flag~~

### Revised Phase 1: External Registry (To Be Evaluated)

1. Create `WeakRefRegistry` with `IdentityHashMap<RuntimeScalar, Object>`
2. `weaken($ref)` adds ref to registry
3. `isweak($ref)` checks if ref is in registry
4. `unweaken($ref)` removes from registry

**Pros**: No memory impact on non-weak scalars (99.9% of all scalars)
**Cons**: HashMap lookup overhead, need to handle scalar copying/assignment

### Phase 2: WeakReference Wrapper (Optional)

1. Modify `weaken()` to wrap value in WeakReference
2. Add `getActualValue()` method
3. Update dereference operations to check for cleared refs
4. Handle `unweaken()` unwrapping

**Estimated effort**: 4-8 hours (need to audit all value access points)  
**Tests enabled**: Additional tests that check weak refs become undef

### Phase 3: Integration with DESTROY (Future)

When DESTROY is implemented, coordinate:
1. DESTROY called when object becomes unreachable
2. Weak refs cleared after DESTROY completes
3. Proper ordering guarantees

## Test Cases

```perl
# Basic isweak flag
my $ref = \%hash;
ok(!isweak($ref), "not weak initially");
weaken($ref);
ok(isweak($ref), "weak after weaken");
unweaken($ref);
ok(!isweak($ref), "not weak after unweaken");

# Weak ref still works
my $strong = { key => "value" };
my $weak = $strong;
weaken($weak);
is($weak->{key}, "value", "can still access through weak ref");

# Weak ref becomes undef (requires Phase 2)
my $strong = { key => "value" };
my $weak = $strong;
weaken($weak);
undef $strong;
# Force GC somehow...
ok(!defined($weak), "weak ref is undef after strong ref gone");

# Circular reference broken (requires Phase 2 + working GC)
{
    my $parent = { children => [] };
    my $child = { parent => $parent };
    push @{$parent->{children}}, $child;
    weaken($child->{parent});
}
# Both should be collected, no memory leak
```

## Files to Modify

### Phase 1 (Flag-Only)
- `RuntimeScalar.java` - Add `isWeak` field
- `ScalarUtil.java` - Implement weaken/isweak/unweaken
- `Builtin.java` - Update builtin::weaken etc.

### Phase 2 (WeakReference)
- `RuntimeScalar.java` - Add getActualValue(), checkWeakReference()
- `RuntimeHash.java` - Check weak refs in get/exists operations
- `RuntimeArray.java` - Check weak refs in get operations  
- `RuntimeCode.java` - Check weak refs before method dispatch
- All places that access `RuntimeScalar.value` directly

## Open Questions

1. Should we use `WeakReference` or `SoftReference`? (WeakReference is more aggressive)
2. Should we add an environment variable to enable/disable weak reference behavior?
3. How do we handle `weaken()` on non-reference scalars? (Currently a no-op in Perl)
4. Should copying a weak reference preserve weakness? (In Perl: no, copy is strong)

## Related Documents

- `destroy_support.md` - DESTROY implementation (related GC concerns)
- `moo_support.md` - Test failure tracking

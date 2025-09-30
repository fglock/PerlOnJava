# Fix Hash::Util::bucket_ratio() Implementation

## Objective
Implement proper hash bucket tracking in RuntimeHash to support Hash::Util::bucket_ratio() and related functions, potentially fixing ~1400 tests in op/hash.t.

## Current Status
- **Test file:** t/op/hash.t
- **Pass rate:** 95% (25517 passing / 1425 failing)
- **Total tests:** 26942
- **Estimated impact:** ~1400 tests could be fixed with proper bucket tracking

## Problem Analysis

### Root Cause
The `Hash::Util::bucket_ratio()` function in `src/main/perl/lib/Hash/Util.pm` has a placeholder implementation that doesn't match Perl's actual hash bucket behavior:

```perl
sub bucket_ratio (\%) {
    my $hashref = shift;
    my $keys = keys %$hashref;
    
    # Simple implementation - INCORRECT
    my $buckets = $keys > 0 ? int($keys * 1.5) + 8 : 8;
    my $used = $keys > 0 ? int($keys * 0.75) : 0;
    
    return "$used/$buckets";
}
```

**Issues with current implementation:**
1. Returns `$used = 0` when hash is empty (tests expect > 0 for non-empty hashes)
2. Recalculates bucket count based on current key count
3. Doesn't maintain bucket state across operations
4. In Perl, `$total` (bucket count) doesn't shrink when keys are deleted

### Test Expectations
Tests in op/hash.t expect:
- `bucket_ratio()` returns "used/total" format
- `$total` (total buckets) remains constant after hash resizing
- `$used` (used buckets) > 0 for hashes with keys
- `$used` represents buckets that contain at least one key
- Bucket statistics persist across delete operations

### Example Failing Tests
```
not ok 22 - a .. zz (701) has same array size
# Failed test 22 - a .. zz (701) has same array size at t/op/hash.t line 174
#      got "1059"
# expected same as initial total

not ok 27 - a .. zz (+701) uses >0 heads (0)
# Failed test 27 - a .. zz (+701) uses >0 heads (0) at t/op/hash.t line 147
#      got "0"
# expected > 0
```

## Technical Investigation

### How Perl's Hash Buckets Work
1. **Hash table structure:** Array of buckets (linked lists)
2. **Total buckets:** Power of 2, grows when load factor exceeds threshold
3. **Used buckets:** Count of buckets with at least one entry
4. **Bucket ratio:** "used/total" shows hash table efficiency
5. **Resize behavior:** Table grows but rarely shrinks

### What Tests Are Checking
From t/op/hash.t lines 140-179:
```perl
sub validate_hash {
  my ($desc, $h) = @_;
  my $ratio = Hash::Util::bucket_ratio(%$h);
  my $expect = qr!\A(\d+)/(\d+)\z!;
  like($ratio, $expect, "$desc bucket_ratio matches pattern");
  my ($used, $total)= (0,0);
  ($used, $total)= ($1,$2) if $ratio =~ /$expect/;
  cmp_ok($total, '>', 0, "$desc has >0 array size ($total)");
  cmp_ok($used, '>', 0, "$desc uses >0 heads ($used)");
  cmp_ok($used, '<=', $total,
         "$desc doesn't use more heads than are available");
  return ($used, $total);
}
```

## Implementation Strategy

### Phase 1: Add Bucket Tracking to RuntimeHash

**File:** `src/main/java/org/perlonjava/runtime/RuntimeHash.java`

**Changes needed:**
1. Add fields to track bucket statistics:
   ```java
   private int totalBuckets = 8;  // Initial bucket count (power of 2)
   private int usedBuckets = 0;   // Buckets with at least one entry
   ```

2. Update bucket statistics on hash operations:
   - **Insert:** Increment `usedBuckets` if bucket was empty
   - **Delete:** Decrement `usedBuckets` if bucket becomes empty
   - **Resize:** Update `totalBuckets`, recalculate `usedBuckets`

3. Add method to expose bucket statistics:
   ```java
   public String getBucketRatio() {
       return usedBuckets + "/" + totalBuckets;
   }
   ```

### Phase 2: Implement Bucket Tracking Logic

**Challenges:**
1. RuntimeHash uses Java's HashMap internally - need to track buckets separately
2. HashMap doesn't expose bucket structure directly
3. Need to maintain statistics without impacting performance

**Approach Options:**

**Option A: Approximate Bucket Tracking (Simpler)**
- Estimate bucket count based on HashMap capacity
- Calculate used buckets from key distribution
- Pros: Simpler, no major refactoring
- Cons: Not exact, may not match Perl perfectly

**Option B: Custom Hash Implementation (Complex)**
- Implement custom hash table with explicit bucket tracking
- Replace HashMap with custom structure
- Pros: Exact bucket tracking, full control
- Cons: Major refactoring, performance risk, extensive testing needed

**Recommendation:** Start with Option A (approximate), move to Option B if needed

### Phase 3: Update Hash::Util::bucket_ratio()

**File:** `src/main/perl/lib/Hash/Util.pm`

**Changes:**
```perl
sub bucket_ratio (\%) {
    my $hashref = shift;
    
    # Call Java backend to get actual bucket statistics
    # This would need to be implemented as a native method
    # that calls RuntimeHash.getBucketRatio()
    
    # For now, improve the approximation:
    my $keys = keys %$hashref;
    
    if ($keys == 0) {
        return "0/8";  # Empty hash has 8 initial buckets
    }
    
    # Calculate bucket count (next power of 2 >= keys * 1.5)
    my $min_buckets = int($keys * 1.5);
    my $buckets = 8;
    while ($buckets < $min_buckets) {
        $buckets *= 2;
    }
    
    # Estimate used buckets (assuming good distribution)
    my $used = int($keys * 0.75) + 1;
    $used = $buckets if $used > $buckets;
    
    return "$used/$buckets";
}
```

### Phase 4: Testing Strategy

**Test progression:**
1. Create minimal test case for bucket_ratio()
2. Verify basic functionality (format, non-zero values)
3. Test bucket persistence across deletes
4. Run op/hash.t and measure improvement
5. Iterate on bucket calculation formula

**Minimal test case:**
```perl
use Hash::Util qw(bucket_ratio);

my %h = (a => 1, b => 2, c => 3);
my $ratio = bucket_ratio(%h);
print "Ratio: $ratio\n";

# Should match /\d+\/\d+/
# Both numbers should be > 0
```

## Expected Impact

### Test Improvements
- **Estimated:** ~1400 tests in op/hash.t
- **Pattern:** Most failures are "has same array size" and "uses >0 heads"
- **Bulk fix potential:** High - single root cause affects many tests

### Complexity Assessment
- **Difficulty:** High (requires core hash implementation changes)
- **Estimated effort:** 2-4 hours
- **Risk:** Medium (performance impact, need careful testing)
- **ROI:** Excellent if successful (700+ tests/hour potential)

## Files to Modify

### Primary Files
1. `src/main/java/org/perlonjava/runtime/RuntimeHash.java`
   - Add bucket tracking fields
   - Update hash operations to maintain statistics
   - Add getBucketRatio() method

2. `src/main/perl/lib/Hash/Util.pm`
   - Improve bucket_ratio() implementation
   - Consider native method integration

### Supporting Files (if needed)
3. `src/main/java/org/perlonjava/perlmodule/HashUtil.java` (may need to create)
   - Native methods for Hash::Util functions
   - Bridge between Perl and Java hash internals

## Alternative Approaches

### Quick Fix (Lower Impact)
Improve the approximation formula in Hash::Util.pm without modifying RuntimeHash:
- **Pros:** Quick, no core changes
- **Cons:** Still approximate, may not fix all tests
- **Estimated impact:** 200-400 tests

### Full Implementation (Higher Impact)
Implement complete bucket tracking with custom hash table:
- **Pros:** Exact Perl compatibility
- **Cons:** Major refactoring, high risk
- **Estimated impact:** 1000+ tests

## Recommendations

1. **Start with improved approximation** in Hash::Util.pm
2. **Measure impact** on op/hash.t
3. **If insufficient**, proceed with RuntimeHash modifications
4. **Consider performance** - add benchmarks before/after
5. **Test thoroughly** - hash operations are critical

## Related Issues

### Other Hash::Util Functions
The following functions also have placeholder implementations:
- `lock_keys()`, `unlock_keys()` - Hash key locking
- `lock_hash()`, `unlock_hash()` - Full hash locking
- `hash_seed()` - Hash seed value
- `hash_value()` - String hash calculation

These may need proper implementation for other tests.

## Success Criteria

1. ✅ `bucket_ratio()` returns "used/total" format
2. ✅ Both `used` and `total` are > 0 for non-empty hashes
3. ✅ `total` remains constant after deletes (until resize)
4. ✅ `used` decreases appropriately when keys deleted
5. ✅ op/hash.t pass rate improves significantly (target: 98%+)
6. ✅ No performance regression in hash operations

## Notes

- This is a **high-value target** with potential for massive test improvements
- Requires careful balance between accuracy and implementation complexity
- Consider starting with "good enough" approximation before full implementation
- Hash operations are performance-critical - benchmark any changes
- Document any deviations from Perl's exact behavior

## References

- Test file: `t/op/hash.t` lines 140-179 (validate_hash function)
- Current implementation: `src/main/perl/lib/Hash/Util.pm` lines 37-48
- RuntimeHash: `src/main/java/org/perlonjava/runtime/RuntimeHash.java`

---

**Created:** 2025-09-30
**Priority:** High (potential ~1400 test improvement)
**Complexity:** High (requires core hash implementation changes)
**Estimated effort:** 2-4 hours

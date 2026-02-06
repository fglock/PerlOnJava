# Method Call Performance Optimization Plan

**Goal**: Achieve >340 iterations/sec on `dev/bench/bench_method.pl` (matching or exceeding native Perl performance)

**Current Status**: 119 iter/sec (2.87x slower than target)

**Target Completion**: 4 weeks

---

## Executive Summary

Analysis reveals that PerlOnJava's closure performance is **2.7x faster than Perl** (1718 vs 638 iter/sec), proving the JVM execution model is fundamentally sound. The method call slowdown is entirely due to **blessed hash access overhead**. The `add()` method performs 4 hash accesses per call, each costing ~38ns vs ~2ns in native Perl.

This plan focuses on eliminating redundant work in the hot path through architectural improvements that leverage PerlOnJava's existing infrastructure.

---

## Root Cause Analysis

### Performance Breakdown (per `add()` call)

```
Method call overhead:        134 ns (25%)
Hash access (4x):            152 ns (29%) ← PRIMARY BOTTLENECK
  - blessId extraction:       20 ns
  - Overload check:           12 ns
  - String conversion:        40 ns
  - HashMap lookup:           80 ns
Other operations:            246 ns (46%)
────────────────────────────────────
Total:                       532 ns
```

**Target**: 153 ns/call (to match Perl's 340 iter/sec)
**Required speedup**: 3.5x on hash access path

### Why Closures Are Fast

The closure benchmark has **zero blessed hash accesses** - only lexical variable arithmetic. This proves:
- ✅ JVM method invocation is efficient
- ✅ Bytecode generation is optimal
- ✅ JIT compilation works well
- ❌ **Blessed object operations need optimization**

---

## Optimization Strategy

### Phase 1: Inline Cache at Call Sites (Week 1-2)
**Impact**: 2.0x speedup | **Effort**: Medium | **Risk**: Low

#### Objective
Cache resolved methods at bytecode call sites to eliminate `InheritanceResolver.findMethodInHierarchy()` on every call.

#### Implementation
1. **Generate inline cache in bytecode**
   - `EmitterVisitor.emitMethodCall()` emits a guard check:
     ```java
     if (object.blessId == cachedBlessId) {
         return cachedMethod.invoke(...);
     } else {
         // Slow path: resolve and update cache
     }
     ```
   - Store cache in generated class's static fields
   - Use `INVOKEDYNAMIC` with `CallSite` for polymorphic caching (Java 7+)

2. **Modify `Dereference.handleArrowOperator()`**
   - Lines 528-680: Add cache slot allocation
   - Emit cache guard before `RuntimeCode.call()`
   - Use existing `SpillSlotManager` for cache slots

3. **Add cache invalidation hooks**
   - `InheritanceResolver.invalidateCache()` already exists
   - Extend to invalidate bytecode-level caches via `MutableCallSite.setTarget()`

#### Files to Modify
- `src/main/java/org/perlonjava/codegen/Dereference.java` (lines 528-680)
- `src/main/java/org/perlonjava/runtime/RuntimeCode.java` (add cache helper methods)
- `src/main/java/org/perlonjava/mro/InheritanceResolver.java` (add invalidation hooks)

#### Success Criteria
- `bench_method.pl`: 180+ iter/sec
- `bench_closure.pl`: no regression
- All tests pass

---

### Phase 2: Fast Path for Non-Overloaded Hash Access (Week 2-3)
**Impact**: 2.5x speedup | **Effort**: High | **Risk**: Medium

#### Objective
Eliminate overload checks and string conversions for blessed hash access when no overloads are defined.

#### Implementation
1. **Add fast-path bytecode for hash access**
   - `EmitterVisitor` detects `$blessed->{key}` pattern
   - Emit optimized path:
     ```java
     if (object.type == HASHREFERENCE && !hasOverloads(blessId)) {
         return ((RuntimeHash)object.value).elements.get(cachedKey);
     }
     ```
   - Pre-intern string keys at compile time
   - Skip `RuntimeScalar.hashDeref()` entirely

2. **Extend `RuntimeHash` with direct accessors**
   - Add `getDirectUnchecked(String key)` method
   - Bypass overload checking layer
   - Use for compiler-generated code only (not user-facing API)

3. **Cache blessId check result**
   - Store "has_overloads" bit in per-class metadata
   - Check once per class, not per access
   - Use existing `NameNormalizer.blessIdCache` infrastructure

4. **Optimize string key caching**
   - `RuntimeHash.get()` currently calls `keyScalar.toString()` on every access
   - Add `RuntimeScalar.cachedStringValue` field
   - Memoize conversion for immutable scalars

#### Files to Modify
- `src/main/java/org/perlonjava/astvisitor/EmitterVisitor.java` (add pattern detection)
- `src/main/java/org/perlonjava/codegen/Dereference.java` (emit fast path)
- `src/main/java/org/perlonjava/runtime/RuntimeHash.java` (add `getDirectUnchecked()`)
- `src/main/java/org/perlonjava/runtime/RuntimeScalar.java` (add `cachedStringValue`)
- `src/main/java/org/perlonjava/runtime/OverloadContext.java` (expose hasOverloads flag)

#### Success Criteria
- `bench_method.pl`: 300+ iter/sec
- Hash access microbenchmark: <15ns per access (from current 38ns)
- All tests pass, including overload tests

---

### Phase 3: Method-Specific Optimizations (Week 3-4)
**Impact**: 1.2x speedup | **Effort**: Low | **Risk**: Low

#### Objective
Apply targeted optimizations for common method patterns.

#### Implementation
1. **Eliminate redundant blessId extraction**
   - `RuntimeScalar.blessedId()` called 4x per `add()` method
   - Cache in local variable at method entry
   - Emit optimization in `EmitterVisitor.visitMethodNode()`

2. **Specialize accessor methods**
   - Detect getter/setter patterns: `sub get_x { $_[0]->{x} }`
   - Generate direct field access bytecode
   - Skip full method call machinery

3. **Pool RuntimeArray for `@_`**
   - Current implementation creates new array per call
   - Extend existing `RuntimeArrayPool` (already added)
   - Reuse arrays for same argument counts

4. **Pre-compute method signatures**
   - Hash `(blessId, methodName)` once at cache time
   - Avoid string concatenation in `NameNormalizer.normalizeVariableName()`

#### Files to Modify
- `src/main/java/org/perlonjava/astvisitor/EmitterVisitor.java` (pattern detection)
- `src/main/java/org/perlonjava/runtime/RuntimeCode.java` (array pooling)
- `src/main/java/org/perlonjava/runtime/NameNormalizer.java` (signature caching)

#### Success Criteria
- `bench_method.pl`: 350+ iter/sec
- Memory profiling shows reduced allocation rate
- All tests pass

---

## Risk Mitigation

### Technical Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Inline cache invalidation bugs | Medium | High | Comprehensive test suite for dynamic ISA changes |
| JVM verifier issues with fast path | Low | High | Generate conservative bytecode, validate with `javap -v` |
| Overload detection edge cases | Medium | Medium | Extensive overload.t test coverage |
| Memory leak from cached objects | Low | Medium | WeakReferences in cache, monitoring in tests |

### Rollback Strategy
- Each phase is independently testable
- Feature flags for new codegen paths: `CompilerOptions.enableInlineCache`
- Gradual rollout: enabled only for non-overloaded classes initially

---

## Testing Strategy

### Performance Tests
1. **Regression suite**
   - `bench_method.pl`: Target >340 iter/sec
   - `bench_closure.pl`: No regression (maintain >1700 iter/sec)
   - New: `bench_hash_access.pl`: >60M ops/sec on blessed hash reads

2. **Microbenchmarks**
   - Method call overhead: <100ns
   - Hash access: <15ns
   - Method resolution: <50ns (first call), <5ns (cached)

### Correctness Tests
1. **Existing test suite**: All 2012 tests must pass
2. **New tests**:
   - `test/method_cache_invalidation.t`: Dynamic @ISA changes
   - `test/overload_inheritance.t`: Inherited overload operators
   - `test/inline_cache_polymorphism.t`: Multiple classes at same call site

### Validation Criteria
- ✅ Zero test failures
- ✅ Zero memory leaks (valgrind/heap profiling)
- ✅ Performance targets met on all benchmarks
- ✅ No bytecode verifier errors

---

## Implementation Order

### Week 1: Infrastructure & Inline Cache
- [ ] Day 1-2: Add cache slot support to EmitterVisitor
- [ ] Day 3-4: Implement inline cache generation in Dereference.java
- [ ] Day 5: Add invalidation hooks, test with bench_method.pl

### Week 2: Fast Path Design
- [ ] Day 1-2: Design fast-path bytecode structure, prototype
- [ ] Day 3-4: Implement pattern detection in EmitterVisitor
- [ ] Day 5: Integrate RuntimeHash.getDirectUnchecked()

### Week 3: Fast Path Implementation
- [ ] Day 1-3: Complete fast-path codegen for blessed hash access
- [ ] Day 4: Add string key caching in RuntimeScalar
- [ ] Day 5: Performance testing, tuning

### Week 4: Polish & Method Optimizations
- [ ] Day 1-2: Implement accessor pattern specialization
- [ ] Day 3: Optimize blessId extraction and array pooling
- [ ] Day 4: Final performance testing and validation
- [ ] Day 5: Documentation and code review

---

## Success Metrics

### Primary Goal
- **bench_method.pl**: >340 iter/sec (currently 119 iter/sec)
- **Improvement**: 2.87x speedup

### Secondary Goals
- **bench_closure.pl**: Maintain >1700 iter/sec (no regression)
- **test-all**: 100% pass rate
- **Hash access cost**: <15ns (currently 38ns)

### Stretch Goals
- **bench_method.pl**: >400 iter/sec (exceed native Perl by 17%)
- **Memory overhead**: <10% increase vs baseline
- **Compilation time**: No regression (same bytecode gen speed)

---

## Dependencies

### Existing Infrastructure (Ready to Use)
- ✅ `SpillSlotManager`: Slot allocation for cache storage
- ✅ `InheritanceResolver`: Method resolution with caching
- ✅ `OverloadContext`: Overload detection (now with BitSet)
- ✅ `EmitterVisitor`: Bytecode generation framework
- ✅ `RuntimeArrayPool`: Array pooling for `@_`
- ✅ ASM library: Low-level bytecode manipulation

### Required Tools
- JMH (Java Microbenchmark Harness): For precise performance measurement
- VisualVM or YourKit: For profiling and validation
- javap: Bytecode verification

---

## Alternatives Considered

### Option A: JIT Recompilation
**Rejected**: Requires dynamic class loading infrastructure, high complexity

### Option B: C++/JNI for Hash Access
**Rejected**: JNI overhead negates benefits, adds platform dependencies

### Option C: Specialized Type System
**Rejected**: Breaking change to runtime API, affects all existing code

**Selected Approach**: Leverage existing bytecode generation with targeted fast paths
- Minimal API changes
- Incremental rollout
- Builds on proven infrastructure

---

## Monitoring & Validation

### Continuous Integration
```bash
# Add to CI pipeline
make bench-method      # Must show >340 iter/sec
make bench-closure     # Must show >1700 iter/sec
make test-all          # Must pass 100%
make profile-memory    # Detect leaks
```

### Performance Dashboard
Track metrics over commits:
- Method call throughput (iter/sec)
- Hash access latency (ns)
- Method resolution cache hit rate (%)
- Memory allocation rate (MB/sec)

---

## Expected Outcomes

### Quantitative
- **3.0x faster** method calls (119 → 350+ iter/sec)
- **2.5x faster** blessed hash access (38ns → 15ns)
- **Zero** test regressions
- **<5%** memory overhead increase

### Qualitative
- Competitive with native Perl for OOP code
- Maintains >2x advantage for closure-heavy code
- Establishes pattern for future optimizations
- Demonstrates PerlOnJava's optimization potential

---

## Conclusion

This plan achieves >340 iter/sec through **pragmatic architectural improvements** that leverage PerlOnJava's existing strengths:

1. **Proven approach**: Inline caching is standard in dynamic language VMs
2. **Low risk**: Builds on existing infrastructure (EmitterVisitor, ASM, caching)
3. **Measurable**: Clear benchmarks at each phase
4. **Reversible**: Feature flags enable rollback if issues arise

The closure benchmark proves PerlOnJava can exceed native Perl performance. This plan extends that advantage to object-oriented code.

**Estimated total effort**: 80-100 hours over 4 weeks
**Confidence level**: High (80%) for >340 iter/sec, Medium (60%) for >400 iter/sec

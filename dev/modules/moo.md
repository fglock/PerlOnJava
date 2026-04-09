# Plan: Moo All Tests Passing

**Goal**: `./jcpan --jobs 8 -t Moo` → 71/71 test programs pass, 841/841 subtests pass

**Branch**: `feature/destroy-weaken`

**Current state**: 69/71 pass, 839/841 subtests pass (2 test files failing, 2 subtests)

---

## Failure Summary (Current)

| Test file | Failed/Total | Category |
|-----------|-------------|----------|
| t/accessor-weaken.t | 1/19 (test 19) | optree reaping (JVM limitation) |
| t/accessor-weaken-pre-5_8_3.t | 1/19 (test 19) | optree reaping (JVM limitation) |

### Test 19: optree reaped, ro static value gone

When `*mk_ref = sub {}` replaces a subroutine, Perl 5 frees the old sub's op-tree
including compile-time constants. Weak references to those constants become undef.
In PerlOnJava, subroutine internals are JVM bytecode — there's no op-tree to reap.
Constants are Java String objects managed by GC, not reference-counted.

This is a fundamental difference between Perl 5's reference counting and JVM's tracing GC.
Fixing this would require implementing Perl 5-style reference counting for subroutine
internal constants, which is impractical.

---

## Completed Fixes

### Category A: quote_sub Inlining — FIXED (2025-04-09)

**6 test files, ~49 subtests — all now passing.**

Root cause: When `weaken()` was called on an unblessed birth-tracked object (like
deferred coderefs from Sub::Quote/Sub::Defer) with refCount > 0, the mortal mechanism
could bring refCount to 0 and trigger `clearWeakRefsTo()` prematurely.

Fix: In `weaken()`, when an unblessed object has remaining strong refs after decrement
(`refCount > 0 && blessId == 0`), transition immediately to `WEAKLY_TRACKED` (refCount=-2).
Also removed `MortalList.flush()` from `RuntimeCode.apply()` methods to prevent flushing
pending decrements before callees capture return values.

Commit: `cad2f2566`

### Category B: Weak Ref Scope-Exit — MOSTLY FIXED (2025-04-09)

**2 test files, 4 of 6 subtests fixed (tests 10, 11 in each file).**

Root cause: Anonymous hashes created via `{}` were birth-tracked in `createReference()`
(which is also called for named hashes `\%h`). This meant named hashes got refCount=0
even though their JVM local variable isn't counted. When `weaken()` brought refCount
to 0, we couldn't distinguish "anonymous hash with truly no strong refs" from "named
hash with untracked lexical slot", so we always went to WEAKLY_TRACKED.

Fix: Moved birth tracking from `RuntimeHash.createReference()` to
`createReferenceWithTrackedElements()` (only called for anonymous `{}`). Named hashes
keep refCount=-1. In `weaken()`, when refCount reaches 0, destroy immediately — only
anonymous objects can reach this state, and their refCount is complete.

Key insight: `set()` already routes reference copies to `setLarge()` when
`MortalList.active`, so refCount IS accurate for all stored references to anonymous
objects.

Commit: `800f70faa`

---

## Architecture Notes

### RefCount States

| Value | Meaning |
|-------|---------|
| -1 | Untracked (default). Named objects, CODE refs, objects created before MortalList.active |
| -2 (WEAKLY_TRACKED) | Named/global object with weak refs. Strong refs can't be counted accurately. |
| 0 | Birth-tracked anonymous object (via createReferenceWithTrackedElements). No strong refs yet. |
| > 0 | Tracked with N strong references (via setLarge increments) |
| MIN_VALUE | Destroyed |

### Birth Tracking

Only anonymous objects (created via `createReferenceWithTrackedElements`) get birth-tracked:
- `{a => 1}` → RuntimeHash.createReferenceWithTrackedElements() → refCount=0
- `\%h` → RuntimeHash.createReference() → refCount stays -1

This distinction is critical: anonymous objects are ONLY reachable through references
(all tracked by setLarge), so refCount is complete. Named objects have their JVM local
variable as an untracked strong reference.

### WEAKLY_TRACKED Transition

When `weaken()` decrements refCount from N to M > 0 for unblessed objects, transition
to WEAKLY_TRACKED. This is necessary because:
1. Closure captures hold references not tracked in refCount
2. `new RuntimeScalar(RuntimeScalar)` copies aren't tracked
3. Without this, mortal flush can bring refCount to 0 while the object is still alive

### Files Changed

| File | Changes |
|------|---------|
| `WeakRefRegistry.java` | Simplified weaken(): destroy at refCount=0 for both blessed/unblessed; WEAKLY_TRACKED for refCount>0 unblessed |
| `RuntimeHash.java` | Moved birth tracking from createReference() to createReferenceWithTrackedElements() |
| `RuntimeCode.java` | Removed MortalList.flush() from 3 apply() methods |
| `MortalList.java` | No changes in this round |

---

## Progress Tracking

### Current Status: 839/841 subtests passing (99.8%)

### Completed
- [x] Category A fix: quote_sub inlining (2025-04-09) — commit cad2f2566
- [x] Category B fix: anonymous hash weak ref clearing (2025-04-09) — commit 800f70faa

### Remaining
- [ ] Test 19 in accessor-weaken: optree reaping — JVM limitation, not fixable without fundamental changes
- [ ] Test 19 in accessor-weaken-pre-5_8_3: same issue

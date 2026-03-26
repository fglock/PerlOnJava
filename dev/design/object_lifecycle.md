# Object Lifecycle: DESTROY and Weak References

**Status**: Design Proposal (Technically Reviewed)  
**Version**: 1.0  
**Created**: 2026-03-26  
**Supersedes**: destroy_support.md, weak_references.md, auto_close.md  
**Related**: moo_support.md (Phases 30-31)

## Overview

This document covers Perl's object lifecycle management in PerlOnJava:
1. **DESTROY** - Destructor methods called when objects become unreachable
2. **Weak References** - References that don't prevent garbage collection

These features are tightly coupled: weak references become `undef` when the referent's
last strong reference is gone, which is the same moment DESTROY is called.

## The Fundamental Challenge

Perl uses **reference counting** with deterministic destruction:
```perl
{ my $obj = Foo->new; }  # DESTROY called HERE, immediately
print "after block\n";   # Object is already destroyed
```

Java uses **garbage collection** with non-deterministic cleanup:
- Objects are collected "sometime later" (or never, if memory isn't pressured)
- `PhantomReference`/`Cleaner`: By the time we're notified, the object is GONE
- `finalize()`: Deprecated since Java 18, unreliable

This affects both DESTROY (timing) and weak references (when they become undef).

---

## Part 1: DESTROY Support

### Perl DESTROY Semantics

From `perlobj` documentation:

```perl
# Basic DESTROY - called when last reference goes away
package Foo;
sub DESTROY {
    my $self = shift;
    print "Foo destroyed\n";
}

{ my $obj = Foo->new; }  # DESTROY called at block exit
```

**Key behaviors**:
1. `$_[0]` is read-only in DESTROY
2. DESTROY exceptions are warnings with "(in cleanup)", don't propagate
3. Must localize global status variables: `local($., $@, $!, $^E, $?)`
4. If AUTOLOAD exists but no DESTROY, AUTOLOAD is called with "DESTROY"
5. `${^GLOBAL_PHASE} eq 'DESTRUCT'` detects global destruction phase
6. Global destruction order is unpredictable

### Object Model in PerlOnJava

```
$obj (RuntimeScalar)
  └── type = HASHREFERENCE
  └── value → RuntimeHash
                └── blessId = 42  (maps to "MyClass")
                └── elements = {...}
```

When `$obj` goes out of scope, the RuntimeScalar becomes unreachable. But Java's GC
doesn't tell us about this until potentially much later.

### Current State

- **Tied variables**: DESTROY already works via `TieScalar.tiedDestroy()` / `tieCallIfExists("DESTROY")`
- **Regular blessed objects**: No DESTROY support yet
- **Existing infrastructure**: `DeferBlock` provides scope-exit callbacks

### The Reference Escape Problem

Static analysis cannot always determine object lifetimes:

```perl
my $file = IO::File->new("test.txt");
push @global_array, $file;  # Now $file lives beyond its scope
```

Even though `$file` goes out of scope, it's still alive in `@global_array`. This is why
we need multiple strategies: scope-based for simple cases, GC-based for escaped references.

### Existing Infrastructure in PerlOnJava

#### 1. Local.localTeardown()

`org.perlonjava.backend.jvm.Local.localTeardown()` can run cleanup when a variable leaves scope.
This provides deterministic cleanup for simple lexical cases.

#### 2. Try-with-Resources Pattern (Java)

Java's `AutoCloseable` provides deterministic cleanup but requires explicit scope:

```java
public class MyResource implements AutoCloseable {
    @Override
    public void close() {
        // Cleanup logic
    }
}

try (MyResource resource = new MyResource()) {
    // Use resource
} // Automatically calls close() here
```

This pattern could be used for `IO::File` and similar resources internally.

#### 3. PhantomReference with Handle Tracking

The key insight: `PhantomReference.get()` always returns `null`, so we cannot access
the object directly. Instead, track cleanup state separately:

```java
// In RuntimeIO class
private static final ReferenceQueue<RuntimeIO> referenceQueue = new ReferenceQueue<>();
private static final Map<PhantomReference<RuntimeIO>, IOHandle> phantomToHandle = 
    new ConcurrentHashMap<>();

// When opening a file
public static RuntimeIO open(String fileName, String mode) {
    RuntimeIO fh = new RuntimeIO();
    // ... existing open logic ...
    
    // Create phantom reference for cleanup
    PhantomReference<RuntimeIO> phantomRef = new PhantomReference<>(fh, referenceQueue);
    phantomToHandle.put(phantomRef, fh.ioHandle);
    
    return fh;
}

// Cleanup thread or periodic check
private static void processPhantomReferences() {
    PhantomReference<? extends RuntimeIO> ref;
    while ((ref = (PhantomReference<? extends RuntimeIO>) referenceQueue.poll()) != null) {
        IOHandle handle = phantomToHandle.remove(ref);
        if (handle != null && openHandles.containsKey(handle)) {
            try {
                handle.close();
                openHandles.remove(handle);
            } catch (Exception e) {
                // Log cleanup failure
            }
        }
        ref.clear();
    }
}
```

#### 4. Hybrid Approach (Recommended)

Combine multiple strategies:
- Use `Local.localTeardown()` for deterministic cleanup in simple lexical cases
- Use PhantomReferences/Cleaner for complex cases where static analysis fails
- Keep LRU cache as safety net for resource limits (e.g., max open file handles)
- Provide explicit `close()` methods for user control

### Implementation Strategy: Scope-Based DESTROY

The key insight: **most DESTROY calls happen at predictable scope boundaries**. We can handle
these deterministically and fall back to GC-based cleanup for edge cases.

#### DestructorRegistry - Track objects registered for DESTROY

```java
public class DestructorRegistry {
    // WeakHashMap: when RuntimeHash is only reachable through here, it's eligible
    private static final WeakHashMap<RuntimeBase, String> registered = new WeakHashMap<>();
    
    public static void register(RuntimeBase obj, String packageName) {
        // Check if package has DESTROY
        RuntimeScalar destroyRef = GlobalVariable.getGlobalCodeRef(packageName + "::DESTROY");
        if (destroyRef.value != null && ((RuntimeCode)destroyRef.value).defined()) {
            registered.put(obj, packageName);
        }
    }
    
    public static void triggerDestroy(RuntimeBase obj) {
        String pkg = registered.remove(obj);
        if (pkg != null) {
            callDestroy(obj, pkg);
        }
    }
}
```

#### Modify `bless()` - Register objects with DESTROY

```java
public static RuntimeScalar bless(RuntimeScalar ref, RuntimeScalar className) {
    // ... existing code ...
    ((RuntimeBase) ref.value).setBlessId(blessId);
    
    // NEW: Register for DESTROY callback
    DestructorRegistry.register((RuntimeBase) ref.value, className.toString());
    
    return ref;
}
```

---

## Part 2: Weak Reference Support

### Perl Weak Reference Semantics

```perl
use Scalar::Util qw(weaken isweak unweaken);

my $strong = { data => "test" };
my $weak = $strong;
weaken($weak);

print $weak->{data};     # Works: "test"
undef $strong;           # Referent's refcount -> 0
print defined $weak;     # Prints 0 - $weak is now undef
```

**Primary use case - break circular references**:
```perl
package Parent;
sub add_child {
    my ($self, $child) = @_;
    push @{$self->{children}}, $child;
    $child->{parent} = $self;
    weaken($child->{parent});  # Without this, circular ref!
}
```

### Current State

`weaken()`, `unweaken()`, and `isweak()` are stubs:
```java
// ScalarUtil.java - always returns false
public static RuntimeList isweak(RuntimeArray args, int ctx) {
    return new RuntimeList(scalarFalse);
}
```

This causes 20+ test failures in Moo's accessor-weaken tests.

### ⚠️ Key Concern: Memory Impact

Adding a field to `RuntimeScalar` has significant implications:
- `RuntimeScalar` is the most frequently instantiated object
- Millions of instances in typical programs
- Adding even a `boolean` field adds 4-8 bytes per instance (alignment)
- **Estimated impact**: 4-8 MB additional memory per million scalars

**Alternative approaches** (to avoid per-scalar overhead):

1. **External WeakHashMap registry**: `WeakHashMap<RuntimeScalar, Boolean>`
   - Only allocates for weak refs (rare)
   - Lookup overhead on `isweak()` calls

2. **Sentinel wrapper type**: `value = new WeakRefWrapper(originalValue)`
   - Check `instanceof WeakRefWrapper` instead of flag
   - No new field, but type check overhead

3. **Bit-packing in `type` field**: Use unused high bits
   - `type` is `int` but only uses ~20 enum values
   - Requires careful bit masking everywhere

4. **RuntimeScalarWeak subclass**: Separate class for weak refs
   - Only weak refs pay memory cost
   - But changes reference identity behavior

### Implementation Options

#### Option A: External Registry (Recommended for Memory)

```java
public class WeakRefRegistry {
    private static final Map<RuntimeScalar, WeakReference<Object>> registry = 
        Collections.synchronizedMap(new IdentityHashMap<>());
    
    public static void weaken(RuntimeScalar ref) {
        if (RuntimeScalarType.isReference(ref.type)) {
            registry.put(ref, new WeakReference<>(ref.value));
        }
    }
    
    public static boolean isweak(RuntimeScalar ref) {
        return registry.containsKey(ref);
    }
    
    public static void unweaken(RuntimeScalar ref) {
        registry.remove(ref);
    }
}
```

#### Option B: WeakReference Wrapper (More Complete Semantics)

```java
// In RuntimeScalar - getter that unwraps
public Object getValue() {
    if (value instanceof WeakReference<?> weakRef) {
        Object referent = weakRef.get();
        if (referent == null) {
            // Referent was collected - become undef
            this.type = UNDEF;
            this.value = null;
        }
        return referent;
    }
    return value;
}
```

---

## Part 3: Unified Implementation

### Shared Infrastructure

Both DESTROY and weak references need:
1. **Reference counting** (at least for blessed objects)
2. **Scope-exit hooks** (existing `DeferBlock` infrastructure)
3. **GC integration** via `Cleaner` API

### The Cleaner API Pattern

Java's `Cleaner` (since Java 9) is the modern replacement for `finalize()`:

```java
private static final Cleaner cleaner = Cleaner.create();

// CRITICAL: cleaning action must NOT hold strong reference to object
record CleanupState(RuntimeHash data, String className) implements Runnable {
    public void run() { 
        callDestroy(data, className);  // For DESTROY
        // Also clears weak references to this object
    }
}

// Register at bless() time
Cleaner.Cleanable cleanable = cleaner.register(runtimeScalar, 
    new CleanupState(hash, packageName));
```

**Critical caveat**:
> "The cleaning action must not refer to the object being registered. If so, the object 
> will not become phantom reachable and the cleaning action will not be invoked."

This is why we use a separate `CleanupState` record, not a lambda capturing the object.

### Implementation Phases

#### Phase 1: Tied DESTROY Pattern Generalization
- Already works for tied variables
- Generalize to blessed objects using same `tieCallIfExists("DESTROY")` approach
- **Effort**: 2-4 hours

#### Phase 2: Basic Weak Reference Support
- External registry approach (no memory impact)
- `isweak()` returns correct values
- `weaken()`/`unweaken()` track status
- Weak refs DON'T auto-undef yet (flag-only)
- **Effort**: 2-4 hours
- **Tests enabled**: Moo accessor-weaken tests that check `isweak()`

#### Phase 3: Explicit undef/Reassignment Cleanup
- Hook into `RuntimeScalar.undefine()` and assignment operators
- Call DESTROY when blessed ref is overwritten
- Clear weak references to the object
- **Effort**: 4-8 hours

#### Phase 4: Scope-Exit DESTROY
- Leverage existing `DeferBlock`/`DynamicVariableManager` infrastructure
- Register blessed lexicals for scope-exit cleanup
- Ref-count tracking for blessed objects only
- **Effort**: 8-16 hours

#### Phase 5: GC-Based Fallback with Cleaner
- For objects that escape scope tracking
- Companion object pattern (like Jython's FinalizeTrigger)
- Weak refs become undef when GC runs
- **Effort**: 8-16 hours

#### Phase 6: Global Destruction Phase
- Implement `${^GLOBAL_PHASE}` special variable
- Add shutdown hook: `Runtime.getRuntime().addShutdownHook(...)`
- Handle `Devel::GlobalDestruction` compatibility
- **Effort**: 4-8 hours

### Memory Layout

#### Current: All references are strong
```
$weak ──────────────────────┐
                            ▼
$strong ─────────────► RuntimeHash
                        blessId=42
                        elements={...}
```

#### With WeakReference wrapper
```
$weak ───► WeakReference ─ ─ ─► RuntimeHash  (weak link)
                                blessId=42
$strong ─────────────────────► elements={...}
                                    ▲
                                    │ (strong link)
```

When `$strong` goes away and GC runs:
1. RuntimeHash becomes phantom-reachable
2. Cleaner triggers cleanup action
3. DESTROY is called (if defined)
4. Weak refs to this object become undef

---

## Prior Art: JVM Language Implementations

### Jython (`__del__` via FinalizeTrigger)

- **Companion object pattern**: `FinalizeTrigger` holds reference to Python object
- **Registration at construction**: Classes call `FinalizeTrigger.ensureFinalizer(this)`
- **Object resurrection**: Finalizer called once; must re-register if resurrected
- **GC-driven timing**: Non-deterministic, like Java GC

### JRuby (ObjectSpace.define_finalizer)

- **Current**: Uses `Object.finalize()` internally
- **Migration to Cleaner**: Issue #8328 (JRuby 10.1.0.0) eliminating finalization
- **Key limitation**: Finalizer receives object ID only, NOT the object itself

### Comparison Table

| Feature | Jython `__del__` | JRuby finalizer | Java `Cleaner` | Perl DESTROY |
|---------|------------------|-----------------|-----------------|-------------|
| Timing | GC-driven | GC-driven | GC-driven | Refcount (deterministic) |
| Object access | Yes (FinalizeTrigger) | No (only ID) | No (capture state separately) | Yes (`$_[0]`) |
| Resurrection | Yes (manual) | No | No | Yes (store `$_[0]`) |

**Lesson**: Use Cleaner + companion object pattern to provide object access in DESTROY.

---

## Edge Cases and Challenges

### Object Resurrection
If DESTROY stores `$_[0]` somewhere, Perl keeps the object alive:
```perl
package Immortal;
our @saved;
sub DESTROY { push @saved, $_[0] }  # Object survives!
```
Need to re-register after DESTROY returns if still reachable.

### Circular References
In Perl, circular refs prevent DESTROY until global destruction.
In our approach, they may be destroyed earlier (when scope exits) - often desired.

### Exception in DESTROY
Perl warns but continues:
```java
try {
    callDestroy(obj, pkg);
} catch (Exception e) {
    Warnings.warn("(in cleanup) " + e.getMessage());
}
```

### Copying Weak References
In Perl, copying a weak ref creates a strong ref:
```perl
my $weak = $strong;
weaken($weak);
my $copy = $weak;    # $copy is STRONG, not weak
ok(!isweak($copy));  # true
```

### Global Variables
Objects in package variables never go out of scope until global destruction.
Need shutdown hook:
```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    GlobalContext.setGlobalPhase("DESTRUCT");
    DestructorRegistry.runGlobalDestruction();
}));
```

---

## Test Plan

```perl
# src/test/resources/unit/object_lifecycle.t
use Test::More;
use Scalar::Util qw(weaken isweak unweaken);

# === DESTROY Tests ===

subtest 'Basic DESTROY' => sub {
    my @log;
    package DestroyTest {
        sub new { bless {}, shift }
        sub DESTROY { push @log, "destroyed" }
    }
    { my $obj = DestroyTest->new; }
    is_deeply(\@log, ["destroyed"], "DESTROY called at scope exit");
};

subtest 'Multiple references' => sub {
    my @log;
    package MultiRef {
        sub new { bless {}, shift }
        sub DESTROY { push @log, "destroyed" }
    }
    my $obj1 = MultiRef->new;
    my $obj2 = $obj1;
    undef $obj1;
    is_deeply(\@log, [], "DESTROY not called with refs remaining");
    undef $obj2;
    is_deeply(\@log, ["destroyed"], "DESTROY called when last ref gone");
};

subtest 'Exception in DESTROY' => sub {
    my $ran_after = 0;
    package ExceptionDestroy {
        sub new { bless {}, shift }
        sub DESTROY { die "DESTROY error" }
    }
    { my $obj = ExceptionDestroy->new; }
    $ran_after = 1;
    ok($ran_after, "Execution continues after DESTROY exception");
};

# === Weak Reference Tests ===

subtest 'isweak flag' => sub {
    my $ref = \my %hash;
    ok(!isweak($ref), "not weak initially");
    weaken($ref);
    ok(isweak($ref), "weak after weaken");
    unweaken($ref);
    ok(!isweak($ref), "not weak after unweaken");
};

subtest 'Weak ref still works' => sub {
    my $strong = { key => "value" };
    my $weak = $strong;
    weaken($weak);
    is($weak->{key}, "value", "can access through weak ref");
};

subtest 'Copy of weak ref is strong' => sub {
    my $strong = { key => "value" };
    my $weak = $strong;
    weaken($weak);
    my $copy = $weak;
    ok(!isweak($copy), "copy is strong");
};

# Phase 2+ test - weak ref becomes undef
subtest 'Weak ref becomes undef' => sub {
    plan skip_all => "Requires Phase 5 implementation";
    my $strong = { key => "value" };
    my $weak = $strong;
    weaken($weak);
    undef $strong;
    ok(!defined($weak), "weak ref is undef after strong ref gone");
};

done_testing();
```

---

## Files to Modify

### Phase 1-2 (Quick Wins)
- `ScalarUtil.java` - Implement weaken/isweak/unweaken with registry
- `ReferenceOperators.java` - Add DESTROY registration at bless()
- `DestructorRegistry.java` - NEW: Track blessed objects with DESTROY

### Phase 3-4 (Scope-Based)
- `RuntimeScalar.java` - Hook undefine() and assignment for cleanup
- `DynamicVariableManager.java` - Extend for DESTROY scope tracking
- `BytecodeCompiler.java` / `EmitBlock.java` - Emit scope-exit cleanup

### Phase 5 (GC Fallback)
- `CleanerRegistry.java` - NEW: Cleaner-based fallback
- Value access points need weak ref checking

### Phase 6 (Global)
- `GlobalContext.java` - Add `${^GLOBAL_PHASE}` support
- `Main.java` or entry point - Add shutdown hook

---

## Open Questions (with Recommendations)

1. **Should we implement reference counting for blessed objects?**
   - **Recommendation**: Yes, but only for blessed objects. Minimal overhead since most data is unblessed.

2. **External registry vs. field for weak refs?**
   - **Recommendation**: External registry. Memory impact of per-scalar field is unacceptable.

3. **Should DESTROY registration be opt-in?**
   - **Recommendation**: No. Perl semantics require automatic DESTROY. Register at bless() time.

4. **WeakReference vs SoftReference?**
   - **Recommendation**: WeakReference. More aggressive GC matches Perl's immediate semantics better.

5. **Should copying weak ref preserve weakness?**
   - **Recommendation**: No (matches Perl). Copy creates strong reference.

---

## References

- Perl perlobj documentation: https://perldoc.perl.org/perlobj#Destructors
- Java Cleaner API: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ref/Cleaner.html
- Jython FinalizablePyObject: https://www.javadoc.io/static/org.python/jython-standalone/2.7.1/org/python/core/finalization/FinalizablePyObject.html
- JRuby issue #8328 "Eliminate all uses of finalization": https://github.com/jruby/jruby/issues/8328
- JRuby issue #8465 "WeakReference updates for Java 21": https://github.com/jruby/jruby/issues/8465

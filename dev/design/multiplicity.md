# PerlOnJava Multiplicity: Multiple Independent Perl Runtimes

**Version:** 1.0  
**Date:** 2025-11-20  
**Status:** Design Proposal

---

## Executive Summary

**Multiplicity** is the ability to run multiple independent Perl interpreters within the same JVM process. Each interpreter has its own global variables, I/O state, symbol tables, and execution context.

### Why This Matters

With multiplicity, we can:

1. **Emulate `fork()`** by cloning a runtime and running it in a new thread (see [fork() implementation](#7-enabling-fork-via-clone))
2. **Implement `threads`** (ithreads) by cloning runtime state with selective sharing (see [threads implementation](#8-enabling-threads-via-clone))
3. **Run web servers** with true concurrent request handling without global state pollution (see [web server use case](#9-web-server-use-case))
4. **Provide JSR-223 compliance** with thread-isolated script engines
5. **Enable embedded use cases** where multiple scripts must be isolated

### Current Limitation

Today, PerlOnJava uses global static state (see `GlobalContext`, `GlobalVariable`, `RuntimeIO`, etc.). This means:
- Only one "Perl interpreter" exists in the JVM
- Concurrent execution requires a global lock (serialization)
- `fork()` is impossible (see `FORK.md`)
- `threads` would share all state incorrectly (see `Threads.md`)

### Implementation Approaches

We propose **two complementary approaches**:

1. **Classloader-Based Isolation** (Quick, 1-2 months)
   - Separate classloaders give fresh copies of all statics
   - Minimal code changes
   - Good for prototyping and quick wins

2. **De-static-ify Runtime** (Clean, 4-6 months)
   - Move mutable state into `PerlRuntime` instances
   - Proper OO design
   - Long-term sustainable solution

**Recommended:** Start with classloader approach for quick multiplicity, then gradually migrate to `PerlRuntime` instances.

---

## 1. What is Multiplicity?

**Multiplicity** = the ability to create and run multiple instances of a Perl interpreter within a single JVM process, where each instance is logically independent.

### Independent per Runtime

Each runtime instance must have its own:
- Global variables (`%main::`, `@INC`, `@ARGV`, etc.)
- Symbol tables (packages, subs)
- I/O handles (stdin/stdout/stderr, open files)
- Caller stack (for `caller()`)
- Error state (`$@`, `$!`, `$?`)
- Special blocks state (BEGIN/END/CHECK/INIT queues)

### Shared Across Runtimes

These can be shared:
- Compiled code (Class objects) - memory efficient
- Built-in prototypes (read-only)
- Static utility methods (pure functions like `Base64Util`)

### Analogy

Each runtime is like a separate Perl process in traditional Unix:
- Each has its own memory space (globals)
- They can run concurrently (like parallel processes)
- They can be cloned (like `fork()`)
- They can communicate via explicit channels (like IPC)

---

## 2. Current Architecture: Global State Problem

### Static State Inventory

| Class | Mutable Static State | Impact |
|-------|---------------------|--------|
| `GlobalContext` | Core globals, `@INC`, `%ENV` | All scripts share same environment |
| `GlobalVariable` | All Perl variables | Variable collision between requests |
| `RuntimeIO` | `stdout`, `stderr`, handles | I/O redirection affects all code |
| `SpecialBlock` | BEGIN/END queues | Phase blocks interfere |
| `CallerStack` | Call frame stack | `caller()` sees wrong stacks |
| `RuntimeCode` | eval cache, anon subs | Cache pollution possible |
| `PerlLanguageProvider` | `globalInitialized` | Initialization conflicts |

### Example Problem

```java
// Thread 1 (handling web request A)
RuntimeIO.stdout = new RuntimeIO(responseA.getOutputStream());
runtime.eval("print 'Hello A'");  // Outputs to response A ✓

// Thread 2 (handling web request B) - runs concurrently
RuntimeIO.stdout = new RuntimeIO(responseB.getOutputStream());
runtime.eval("print 'Hello B'");  // Outputs to response B ✓ or A? ✗

// Result: Race condition, output goes to wrong response
```

With global lock, this is "fixed" by serializing execution. But then:
- No true concurrency
- Poor performance under load
- Can't scale to multiple cores

---

## 3. Target Architecture: PerlRuntime Instances

### Core Concept

Move all mutable state into `PerlRuntime` instances:

```java
public final class PerlRuntime implements Cloneable {
    // All the state that was previously static
    private final GlobalContext globals;
    private final VariableStorage variables;
    private final IOSystem io;
    private final SpecialBlockManager blocks;
    private final CallerStackManager callStack;
    
    // Factory methods
    public static PerlRuntime create() { ... }
    
    // Execution
    public RuntimeList eval(String code) { ... }
    public RuntimeList execute(CompiledPerl compiled) { ... }
    
    // Cloning for fork/threads
    public PerlRuntime clone(CloneMode mode) { ... }
    
    // Lifecycle
    public void reset() { ... }
    public void destroy() { ... }
}
```

### Clone Modes

```java
public enum CloneMode {
    INDEPENDENT,    // Full deep copy (for fork emulation)
    THREAD,         // Selective sharing (for ithreads)
    SHARED_ALL,     // Everything shared (for lightweight workers)
    COPY_ON_WRITE   // Lazy copying (optimization)
}
```

---

## 4. Implementation Approach A: Classloader-Based

### Concept

Use separate `ClassLoader` instances to load PerlOnJava multiple times:

```java
public class IsolatedPerlRuntime {
    private final URLClassLoader loader;
    private final Object perlProvider;
    
    public IsolatedPerlRuntime() throws Exception {
        URL[] urls = getPerlOnJavaClasspath();
        this.loader = new URLClassLoader(urls, null);
        
        Class<?> providerClass = loader.loadClass(
            "org.perlonjava.scriptengine.PerlLanguageProvider");
        this.perlProvider = providerClass.getDeclaredConstructor().newInstance();
    }
    
    public Object eval(String code) throws Exception {
        Method evalMethod = perlProvider.getClass()
            .getMethod("executePerlCode", CompilerOptions.class, boolean.class);
        
        CompilerOptions options = createOptions(code);
        return evalMethod.invoke(perlProvider, options, true);
    }
}
```

### How It Works

- Java statics are **per-ClassLoader**
- Each `IsolatedPerlRuntime` loads `org.perlonjava.*` in its own loader
- Result: fresh copy of all static state per runtime

### Pros & Cons

**Pros:**
- ✅ Minimal code changes (existing code keeps using statics)
- ✅ Natural isolation (ClassLoader enforces)
- ✅ Quick to implement (~1 month)
- ✅ Proven pattern (used by Tomcat, plugin systems)

**Cons:**
- ❌ Type barriers (can't cast between loaders)
- ❌ Memory overhead (duplicate classes)
- ❌ Reflection overhead
- ❌ Complex debugging

**Effort:** 3-4 weeks

---

## 5. Implementation Approach B: De-static-ify Runtime

### Concept

Refactor to move mutable state from static fields into `PerlRuntime` instances:

```java
// Before
public class GlobalVariable {
    private static Map<String, RuntimeScalar> globalVariables = ...;
    public static RuntimeScalar getGlobalVariable(String name) { ... }
}

// After
public class VariableStorage {
    private Map<String, RuntimeScalar> globalVariables = ...;
    public RuntimeScalar getGlobalVariable(String name) { ... }
}

public class PerlRuntime {
    private final VariableStorage variables;
    public VariableStorage getVariables() { return variables; }
}
```

### Migration Strategy Using ThreadLocal

**Phase 1:** Create `PerlRuntime` with ThreadLocal accessor

```java
public class PerlRuntime {
    private static final ThreadLocal<PerlRuntime> CURRENT = new ThreadLocal<>();
    
    public static PerlRuntime current() {
        return CURRENT.get();
    }
    
    public static void setCurrent(PerlRuntime runtime) {
        CURRENT.set(runtime);
    }
}
```

**Phase 2:** Migrate subsystems incrementally

```java
// Old static method
public class RuntimeIO {
    public static RuntimeIO stdout = ...;
}

// Transition: route through PerlRuntime.current()
public class RuntimeIO {
    public static RuntimeIO stdout() {
        return PerlRuntime.current().getIO().getStdout();
    }
    
    // Instance version
    private RuntimeIO stdoutInstance = ...;
}

// Eventually: remove static entirely, use explicit runtime parameter
```

**Phase 3:** Subsystems in order

1. IOSystem (smallest)
2. ErrorState
3. CallerStack
4. SpecialBlocks
5. VariableStorage (largest)
6. GlobalContext

### Pros & Cons

**Pros:**
- ✅ Clean OO architecture
- ✅ No type barriers
- ✅ Efficient (no classloader overhead)
- ✅ Long-term sustainable

**Cons:**
- ❌ High effort (4-6 months)
- ❌ Touches many files
- ❌ Risky (easy to miss static references)
- ❌ Testing burden

**Effort:** 4-6 months

---

## 6. Hybrid Recommendation

**Start with A, migrate to B:**

1. **Month 1-2:** Implement classloader-based isolation
   - Quick win for concurrency
   - Unblocks web server use case
   - Enables fork/threads prototypes

2. **Month 3-8:** Gradual de-static-ification
   - Migrate IOSystem first (smallest)
   - Then ErrorState, CallerStack, SpecialBlocks
   - Finally VariableStorage and GlobalContext
   - Keep both approaches working during transition

**Benefits:**
- Early value delivery
- Reduced risk
- Smooth migration path

---

## 7. Enabling fork() via clone()

### Challenge

From `FORK.md`, true `fork()` is impossible in Java because:
- JVM cannot split into two processes
- No copy-on-write memory
- No way to duplicate process state

### Solution with Multiplicity

```java
public static RuntimeScalar fork() {
    PerlRuntime parent = PerlRuntime.current();
    
    // Clone the runtime (deep copy)
    PerlRuntime child = parent.clone(CloneMode.INDEPENDENT);
    
    // Run child in new thread
    Thread childThread = new Thread(() -> {
        PerlRuntime.setCurrent(child);
        // Child sees return value 0
        // Continue execution from fork point
    });
    
    childThread.start();
    
    // Parent sees child "PID" (thread ID)
    return new RuntimeScalar(childThread.getId());
}
```

### What This Achieves

✅ Execution continues from fork point (not from main)  
✅ Each "process" has independent globals  
✅ Parent and child see different return values  
✅ Changes in child don't affect parent  

❌ Not separate OS processes (still threads in JVM)  
❌ File handles can't be truly duplicated  

**But this is good enough for most Perl fork() usage!**

### Continuation Challenge

**Problem:** Java doesn't have continuations - can't "pause" and resume execution.

**V1 Solution:** Limit fork() to top-level or cooperative points:

```perl
# Supported
if (fork() == 0) {
    child_work();
    exit(0);
}

# Not supported (deep in call stack)
sub deep_function {
    fork();  # Error: fork not supported in this context
}
```

**V2 Solution:** Bytecode transformation for continuations (future work).

---

## 8. Enabling threads via clone()

### ithreads Model

From `Threads.md`, Perl's threading model requires:
- Each thread has its own interpreter
- Variables copied by default
- Variables marked `:shared` are synchronized

### Implementation

```java
public static RuntimeScalar threadsCreate(RuntimeScalar codeRef, RuntimeArray args) {
    PerlRuntime parent = PerlRuntime.current();
    
    // Clone with selective sharing
    Set<String> sharedVars = parent.getSharedVariables();
    PerlRuntime child = parent.clone(CloneMode.THREAD, sharedVars);
    
    // Run thread
    Thread javaThread = new Thread(() -> {
        PerlRuntime.setCurrent(child);
        RuntimeCode.apply(codeRef, args, RuntimeContextType.VOID);
    });
    
    javaThread.start();
    return new RuntimeScalar(new ThreadHandle(javaThread));
}
```

### Shared Variables

```java
class SharedVariable {
    private final ReentrantLock lock = new ReentrantLock();
    private RuntimeScalar value;
    
    void lock() { lock.lock(); }
    void unlock() { lock.unlock(); }
    
    RuntimeScalar get() {
        lock.lock();
        try {
            return value.clone();
        } finally {
            lock.unlock();
        }
    }
    
    void set(RuntimeScalar newValue) {
        lock.lock();
        try {
            value = newValue.clone();
        } finally {
            lock.unlock();
        }
    }
}
```

---

## 9. Web Server Use Case

### Current Problem (from jsr223-perlonjava-web.md)

With global state + global lock:
- All requests serialize through one lock
- No true concurrency
- Poor performance under load
- Can't utilize multiple cores

### With Multiplicity

**Option A:** Runtime pool

```java
class PerlWebHandler {
    private final ObjectPool<PerlRuntime> pool;
    private final CompiledPerl page;
    
    void handleRequest(HttpExchange exchange) {
        PerlRuntime runtime = pool.acquire();
        try {
            runtime.setStdout(exchange.getResponseBody());
            runtime.execute(page);
        } finally {
            runtime.reset();  // Clear request state
            pool.release(runtime);
        }
    }
}
```

**Option B:** Per-request runtime (if cheap enough)

```java
void handleRequest(HttpExchange exchange) {
    PerlRuntime runtime = baseRuntime.clone(CloneMode.COPY_ON_WRITE);
    runtime.setStdout(exchange.getResponseBody());
    runtime.execute(page);
    // runtime GC'd
}
```

### Benefits

- ✅ True concurrent execution (no global lock)
- ✅ Request isolation (independent runtimes)
- ✅ Shared compiled code (memory efficient)
- ✅ Linear scalability with cores

---

## 10. Implementation Roadmap

### Phase 1: Classloader Prototype (Weeks 1-4)

**Goal:** Prove concept, enable basic multiplicity

- Week 1: Design `IsolatedPerlRuntime` API
- Week 2: Implement classloader isolation
- Week 3: Basic tests (parallel eval, state isolation)
- Week 4: Fork prototype, threads prototype

**Deliverable:** Working multiplicity via classloaders

### Phase 2: Web Server Integration (Weeks 5-6)

**Goal:** Demonstrate value in web use case

- Week 5: Runtime pool implementation
- Week 6: HttpServer example, benchmarks

**Deliverable:** Web server with concurrent request handling

### Phase 3: De-static-ify - IOSystem (Weeks 7-9)

**Goal:** Start migration to clean architecture

- Week 7: Design `PerlRuntime` + `IOSystem`
- Week 8: Migrate RuntimeIO to instance-based
- Week 9: Tests, integration with classloader approach

**Deliverable:** IOSystem as runtime instance

### Phase 4-7: Remaining Subsystems (Weeks 10-20)

- Phase 4: ErrorState (2 weeks)
- Phase 5: CallerStack (2 weeks)
- Phase 6: SpecialBlocks (3 weeks)
- Phase 7: VariableStorage (4 weeks)
- Phase 8: GlobalContext (4 weeks)

### Phase 8: Cleanup & Optimization (Weeks 21-24)

- Remove classloader approach (if desired)
- Performance tuning
- Memory optimization
- Final testing

**Total Timeline:** 6 months

---

## 11. Testing Strategy

### Unit Tests

- Runtime isolation (no state leakage)
- Clone correctness (deep vs shallow)
- Thread safety
- Memory cleanup

### Integration Tests

- Fork emulation scenarios
- Threads with shared variables
- Web server concurrent requests
- JSR-223 compliance

### Performance Tests

- Concurrent execution speedup
- Memory usage per runtime
- Clone overhead
- Classloader vs instance comparison

### Compatibility Tests

- Existing test suite still passes
- No regression in single-threaded use

---

## 12. Open Questions

1. **Continuation implementation:** Bytecode transformation vs cooperative points?
2. **File handle duplication:** Best approach for `fork()` semantics?
3. **Signal handling:** How to isolate per-runtime?
4. **Native code:** Any JNI implications?
5. **Performance target:** What overhead is acceptable for clone()?

---

## 13. Success Criteria

- ✅ Multiple runtimes run concurrently without interference
- ✅ Fork emulation works for common Perl patterns
- ✅ Threads with shared variables work correctly
- ✅ Web server handles 1000+ concurrent requests
- ✅ JSR-223 can claim "STATELESS" or "THREAD-ISOLATED"
- ✅ No regression in existing functionality

---

## Related Documents

- `FORK.md` - Why true fork() is impossible, alternatives
- `Threads.md` - Threading implementation requirements
- `jsr223-perlonjava-web.md` - Web integration design

---

**Status:** Ready for review and implementation planning

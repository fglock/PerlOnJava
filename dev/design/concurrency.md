# PerlOnJava Concurrency: Unified Design

**Version:** 1.1  
**Date:** 2026-03-26  
**Status:** Design Proposal (Technically Reviewed)  
**Supersedes:** `threads.md`, `fork.md`, `multiplicity.md` (consolidates all three)

---

## Executive Summary

This document presents a unified, realistic plan for concurrency in PerlOnJava.
It replaces the separate `threads.md`, `fork.md`, and `multiplicity.md` documents
with a single plan grounded in what JRuby, Jython, and TruffleRuby have actually
shipped — not theoretical designs.

**Key insight from research:** Every successful JVM language implementation chose
**one** primary concurrency model and made it work well, rather than trying to
faithfully replicate every native-language concurrency mechanism.

### Recommended Strategy

1. **JRuby model (proven):** Map Perl threads directly to JVM threads; make the
   runtime thread-safe; accept that `fork()` cannot work.
2. **ThreadLocal-based runtime context** instead of classloader isolation
   (Jython learned this the hard way — ThreadLocal is simpler and faster).
3. **Incremental de-static-ification** of global state, starting with the
   smallest subsystems.
4. **No GIL/global lock** — follow JRuby's lead: make core data structures
   safe, document what isn't safe, let users synchronize where needed.

### Key Definitions

**threads (ithreads)**: Perl's official mechanism for parallel execution within a
single process. Each Perl thread runs in its own cloned interpreter (not sharing
memory by default). All variables are deep-copied at thread creation time. Shared
variables require explicit `:shared` attribute. This provides "fork-like semantics
without forking" — isolation by default. Note: The use of interpreter-based threads
is officially "discouraged" in Perl 5 documentation, but it remains the only
officially supported threading model.

**fork()**: Process-level parallelism by creating a child process with copied
memory. Uses copy-on-write semantics for efficiency. **Cannot be implemented on
the JVM** — JRuby confirmed this over 20 years of attempts. The JVM's GC can crash
between fork and exec, and JVM state cannot be safely split across processes.

**multiplicity**: Running multiple Perl interpreter instances within the same
process. In Perl 5, this is the internal mechanism (`perl_clone()`) that enables
ithreads. In PerlOnJava, this is achieved through `PerlRuntime` instances. Use
cases include web server request isolation (mod_perl model) and JSR-223 embedding.

---

## 1. Lessons from Other JVM Language Projects

### 1.1 JRuby (most relevant — 20 years of production use)

**Threading model:**
- Ruby threads map 1:1 to JVM threads (real OS threads)
- No Global VM Lock — true parallel execution
- The JRuby runtime itself is thread-safe for structural operations:
  defining methods, modifying constants, modifying global variables
- Core mutable data structures (String, Array, Hash) are **not** thread-safe;
  users must synchronize explicitly
- `volatile` writes for method tables, constant tables, class variables,
  global variables
- Non-volatile: instance variables, local variables in closures

**Fork handling:**
- `Kernel#fork` raises `NotImplementedError` on JRuby
- JRuby tried POSIX `fork+exec` via JNA for `system()`/backticks but
  abandoned it as too dangerous (JVM GC can crash between fork and exec)
- Recommendation: use `Process.spawn`, `system()`, or threads instead
- This is **universally accepted** by the JRuby community

**Key lesson:** Don't waste time on fork emulation. Focus on making threads
work well. The ecosystem adapts.

### 1.2 Jython (cautionary tale)

**Threading model:**
- Python threads map 1:1 to JVM threads
- No GIL (unlike CPython) — true parallel execution
- `dict`, `list`, `set` are thread-safe (no corruption, but updates can be lost)
- All Python attribute get/set are volatile (backed by ConcurrentHashMap)
- Sequential consistency for Python code

**ThreadState problem:**
- Jython used ThreadLocal to store execution context (`ThreadState`)
- They later recognized this was a design mistake that "slows things down
  and unnecessarily limits what a given thread can do"
- Planned refactoring to remove ThreadState but never completed it
  (project stalled before this happened)

**Key lesson:** ThreadLocal for runtime context is a pragmatic starting point
but has performance costs. Plan for eventual migration to explicit parameter
passing — but don't let that block the initial implementation.

### 1.3 TruffleRuby / GraalVM

**Threading model:**
- True parallel threads via GraalVM's managed threading
- Each `Context` is single-threaded; multi-threading requires multiple contexts
  or explicit opt-in via `allowAllAccess`
- Polyglot contexts provide natural isolation

**Key lesson:** Context-per-thread is the cleanest isolation model. PerlOnJava's
eventual `PerlRuntime` instances serve the same role.

### 1.4 Perl 5 ithreads (what we're implementing)

**How it works in CPython Perl:**
- Each thread gets a **cloned copy** of the entire interpreter
- All variables are copied by default (deep clone at thread creation)
- Shared variables require explicit `:shared` attribute
- The `threads::shared` module mediates all cross-thread access
- `CLONE` method called on objects during thread creation
- Heavy — thread creation is expensive (full interpreter clone)

**What mod_perl 2.0 does:**
- Pre-creates a pool of Perl interpreter instances
- Each Apache worker thread gets its own interpreter
- Same pattern as the multiplicity.md "runtime pool" idea

---

## 2. PerlOnJava Global State Inventory

The following mutable static state must be addressed before any concurrency:

| Class | Mutable Static Fields | Size | Risk |
|-------|----------------------|------|------|
| `GlobalVariable` | `globalVariables`, `globalArrays`, `globalHashes`, `globalCodeRefs`, `globalIORefs`, `globalFormatRefs`, `pinnedCodeRefs`, `stashAliases`, `globAliases`, `globalGlobs`, `isSubs`, `packageExistsCache`, `globalClassLoader` | ~13 maps + 1 classloader | **Critical** — all Perl variables live here |
| `RuntimeIO` | `stdout`, `stderr`, `stdin` | 3 fields | **High** — I/O misdirection under concurrency |
| `CallerStack` | `callerStack` (List) | 1 list | **High** — stack traces cross threads |
| `SpecialBlock` | `endBlocks`, `initBlocks`, `checkBlocks` | 3 arrays | **Medium** — phase blocks interfere |
| `DynamicVariableManager` | `variableStack` (Deque) | 1 deque | **High** — `local` scoping is per-execution-context |
| `RuntimeScalar` | `dynamicStateStack` (Stack) | 1 stack | **High** — dynamic state per thread |
| `RuntimeCode` | `evalBeginIds`, `methodHandleCache`, `evalRuntimeContext` (ThreadLocal), `argsStack` (ThreadLocal) | 2 maps + 2 ThreadLocals | **Medium** — some already ThreadLocal |
| `PerlLanguageProvider` | `globalInitialized` | 1 boolean | **Low** — initialization guard |
| `InheritanceResolver` | `methodCache`, `linearizedClassesCache`, `overloadContextCache`, `isaStateCache`, `packageMRO` | 5 maps | **Medium** — stale under concurrent class modification |
| `RuntimeCode` (additional) | `anonSubs`, `interpretedSubs`, `evalContext`, `methodHandleCache` | 4 maps | **Medium** — compiler/eval caches |
| `RuntimeRegex` | regex state, special vars | state | **Medium** — `$1`, `$2` etc. must be per-thread |

**Already ThreadLocal:** `RuntimeCode.evalRuntimeContext`, `RuntimeCode.argsStack`  
**Pure functions (safe):** `RuntimeScalarCache`, most operator methods, `Base64Util`  
**Read-only after init (safe):** `Configuration`, `ParserTables`

---

## 3. Architecture Decision: ThreadLocal Runtime Context

### Why not classloader isolation?

The multiplicity.md document proposed classloader-based isolation first.
After reviewing JRuby and Jython's experience, **this is not recommended** as
the primary approach:

- **Type barriers** make inter-runtime communication impossible without reflection
- **Memory overhead** — duplicate class loading for every runtime instance
- **Debugging nightmare** — class identity issues, serialization failures
- **JRuby abandoned this pattern** for user-facing isolation (only used internally
  for OSGi compatibility)
- **Jython never used it** — went straight to ThreadLocal

### Recommended: ThreadLocal + PerlRuntime instances

```java
public final class PerlRuntime {
    private static final ThreadLocal<PerlRuntime> CURRENT = new ThreadLocal<>();

    // Per-runtime mutable state (moved from static fields)
    final VariableStorage variables;    // was GlobalVariable statics
    final IOSystem io;                  // was RuntimeIO statics
    final CallerStackManager callStack; // was CallerStack statics
    final DynamicScopeManager dynamicScope; // was DynamicVariableManager + RuntimeScalar statics
    final SpecialBlockManager blocks;   // was SpecialBlock statics
    final RegexState regexState;        // $1, $2, match state

    public static PerlRuntime current() {
        return CURRENT.get();
    }

    public static void setCurrent(PerlRuntime rt) {
        CURRENT.set(rt);
    }
}
```

**Migration path:** Replace `GlobalVariable.getGlobalVariable("main::x")` with
`PerlRuntime.current().variables.getGlobalVariable("main::x")`. This can be done
incrementally, subsystem by subsystem.

---

## 4. Concurrency Models to Support

### 4.1 Primary: `threads` (ithreads) — Full Implementation

This is the **only** concurrency model that Perl 5 officially supports via CPAN.
It maps naturally to JVM threads.

```perl
use threads;
use threads::shared;

my $counter :shared = 0;

my @threads;
for (1..4) {
    push @threads, threads->create(sub {
        lock($counter);
        $counter++;
    });
}
$_->join() for @threads;
print "Counter: $counter\n";  # 4
```

**Implementation:**

```java
public static RuntimeScalar threadsCreate(RuntimeScalar codeRef, RuntimeArray args) {
    PerlRuntime parent = PerlRuntime.current();

    // Clone runtime state for new thread (deep copy by default)
    PerlRuntime child = parent.clone(CloneMode.THREAD);

    // Copy shared variable references (not values) to child
    child.linkSharedVariables(parent);

    Thread javaThread = Thread.ofVirtual().start(() -> {
        PerlRuntime.setCurrent(child);
        try {
            RuntimeCode.apply(codeRef, args, RuntimeContextType.VOID);
        } finally {
            PerlRuntime.setCurrent(null);
        }
    });

    return new RuntimeScalar(new PerlThread(javaThread, child));
}
```

**Shared variables** use a wrapper that synchronizes access:

```java
public class SharedScalar extends RuntimeScalar {
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        lock.lock();
        try {
            return super.set(value);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        lock.lock();
        try {
            return super.toString();
        } finally {
            lock.unlock();
        }
    }
}
```

**Virtual Threads (Java 21+):** Since PerlOnJava requires Java 22+, we can use
virtual threads (`Thread.ofVirtual()`) for lightweight thread creation. This is a
significant advantage over native Perl's heavy interpreter-cloning ithreads.

**Virtual Thread Pinning Caveat:** In Java 21-24, virtual threads become "pinned"
to their carrier OS thread when executing inside a `synchronized` block or native
method. This means blocking I/O inside `synchronized` blocks will block the
carrier thread rather than unmounting the virtual thread. JEP 491 ("Synchronize
Virtual Threads without Pinning") addresses this and will be available in future
Java releases. For now, **SharedScalar/SharedArray/SharedHash use `ReentrantLock`
instead of `synchronized`** — this is critical because `ReentrantLock` uses
`LockSupport.park()` which properly unmounts virtual threads.

### 4.2 Secondary: `fork()` — Stub with Documentation

Following JRuby's proven approach:

```java
public static RuntimeScalar fork() {
    // Set $! to explain why
    WarnDie.warn("fork() is not available on the JVM. " +
                 "Use threads, system(), or ProcessBuilder instead.");
    GlobalVariable.getGlobalVariable("main::!").set("Function not implemented");
    return RuntimeScalarCache.scalarUndef;  // returns undef
}
```

**Rationale:** JRuby has shipped `NotImplementedError` for fork for 20 years.
The community adapted. Most fork usage falls into patterns that have alternatives:

| Perl Pattern | JVM Alternative |
|--------------|----------------|
| `fork + exec` | `system()`, `ProcessBuilder` |
| `fork` for parallelism | `threads` |
| `fork` for daemon | `Thread` + process management |
| `fork` in test harness | Thread-based test runner |
| `open(PIPE, "-\|")` | `ProcessBuilder` with piped streams |

### 4.3 Web Server Concurrency — Runtime Pool

For JSR-223 and web server use cases (the most immediately useful):

```java
public class PerlRuntimePool {
    private final BlockingQueue<PerlRuntime> pool;
    private final PerlRuntime template;
    private final int maxPoolSize;
    private final int minPoolSize;
    private final long maxIdleTimeMs;

    public PerlRuntimePool(int size) {
        this(size, size, 0);  // Default: fixed pool, no idle timeout
    }

    public PerlRuntimePool(int minSize, int maxSize, long maxIdleTimeMs) {
        this.minPoolSize = minSize;
        this.maxPoolSize = maxSize;
        this.maxIdleTimeMs = maxIdleTimeMs;
        this.template = PerlRuntime.createDefault();
        this.pool = new ArrayBlockingQueue<>(maxSize);
        for (int i = 0; i < minSize; i++) {
            pool.add(template.clone(CloneMode.INDEPENDENT));
        }
    }

    public PerlRuntime acquire() throws InterruptedException {
        return pool.take();
    }

    public void release(PerlRuntime rt) {
        rt.reset();  // Clear request-scoped state
        pool.offer(rt);
    }
}
```

**Configuration options** (exposed via system properties or API):
- `minPoolSize`: Minimum runtimes to keep warm (default: 1)
- `maxPoolSize`: Maximum concurrent runtimes (default: CPU cores × 2)
- `maxIdleTimeMs`: Time before idle runtimes are destroyed (0 = never)

---

## 5. Thread Creation: What Gets Cloned

When `threads->create()` is called, a new `PerlRuntime` is created by deep-cloning
the parent. This section details the cloning semantics for each category of
runtime state — this is the most technically challenging part of the implementation.

### 5.1 Closure Capture Cloning

PerlOnJava has two closure representations, both of which capture lexical
variables by **Java object reference**:

**JVM backend:** Closures are compiled JVM classes where captured lexicals
become constructor parameters stored as instance fields on the generated class
object (`RuntimeCode.codeObject`). The `RuntimeCode.methodHandle` is bound to
this specific object instance.

**Interpreter backend:** Closures store captures in an explicit array:
```java
// InterpretedCode.java
public final RuntimeBase[] capturedVars; // Closure support (captured from outer scope)
```

**Cloning rules:**

| What | Clone? | Rationale |
|------|--------|----------|
| Compiled JVM bytecode / MethodHandle | **No** — share | Immutable code, safe to share across threads |
| `InterpretedCode.bytecode` / `constants` / `stringPool` | **No** — share | Immutable arrays, safe to share |
| `RuntimeCode.codeObject` (JVM backend) | **Yes** — deep copy | Holds mutable captured variable references |
| `InterpretedCode.capturedVars` (interpreter) | **Yes** — deep copy | Holds mutable captured variable references |
| Each captured `RuntimeScalar` / `RuntimeArray` / `RuntimeHash` | **Yes** — deep copy | New thread gets independent values (ithreads semantics) |
| `RuntimeCode.stateVariable` / `stateArray` / `stateHash` | **Yes** — deep copy | `state` variables are per-closure-instance |
| Variables marked `:shared` | **No** — share reference | Both threads get a reference to the same `SharedScalar` wrapper |

**Reference fixup challenge:** After deep-copying all closures, captured variables
that point to globals must be redirected to the **child's** copies of those globals,
not the parent's originals. This requires a two-pass clone:

1. **Pass 1:** Deep-copy all `VariableStorage` (globals) and all `RuntimeCode`
   objects, building an identity map: `parentObject → childObject`.
2. **Pass 2:** Walk all closure captures in the child. For each capture that
   appears in the identity map, replace it with the child's copy.

This is the same approach Perl 5 uses internally (`perl_clone()` + pointer fixup
table in `sv.c`).

### 5.2 CLONE Method and External Resources

Perl 5 calls `CLONE($package)` on every package that defines it whenever a new
thread is created. This is the mechanism by which modules handle thread safety.

**DBI example (Perl 5 behavior):**
```perl
package DBI;
sub CLONE {
    # Called in the NEW thread's context after cloning
    # Invalidate all cached database handles — they point to
    # the parent's JDBC Connection and are unusable here
    # Child must call DBI->connect() again
}
```

**Implementation plan:**

1. After cloning `PerlRuntime`, walk all packages in the child's symbol table.
2. For each package that has a `CLONE` subroutine defined, call it with the
   package name as argument.
3. The call happens **in the child thread's context** (after `PerlRuntime.setCurrent(child)`).

**External resource handling rules:**

| Resource Type | Action in Child | Rationale |
|---------------|----------------|----------|
| JDBC `Connection` (via DBI) | Invalidate → undef | Cannot share connections across threads |
| File handles (`RuntimeIO`) | Clone with independent position | Perl 5 duplicates file descriptors |
| Sockets | Invalidate → undef | OS socket FDs can't be shared |
| Java objects (generic) | Shallow copy (reference) | User's responsibility via CLONE |
| Tied variables | Clone the tie object | CLONE on tie class handles specifics |

**Safety net:** During clone, any `RuntimeScalar` whose value wraps a Java object
that implements `java.io.Closeable` or `java.sql.Connection` should be set to
`undef` in the child by default. The module's `CLONE` method can then reconnect
if needed. This prevents silent use of stale connections.

### 5.3 Method Cache Invalidation

The `InheritanceResolver` maintains several static caches that are critical for
performance but dangerous under concurrency:

```java
// InheritanceResolver.java — all static, all mutable
static final Map<String, List<String>> linearizedClassesCache;  // MRO linearization
private static final Map<String, RuntimeScalar> methodCache;     // method resolution
private static final Map<Integer, OverloadContext> overloadContextCache;
private static final Map<String, List<String>> isaStateCache;    // @ISA change detection
```

Additionally, `RuntimeCode` has:
```java
private static final Map<Class<?>, MethodHandle> methodHandleCache;  // MethodHandle lookup
```

**Thread creation policy:**

| Cache | Action at Thread Creation | Rationale |
|-------|--------------------------|----------|
| `methodCache` | **Clear in child** | Child may modify `@ISA` independently |
| `linearizedClassesCache` | **Clear in child** | Depends on `@ISA` which is cloned |
| `overloadContextCache` | **Clear in child** | Overload resolution depends on stash state |
| `isaStateCache` | **Clear in child** | Must detect changes relative to child's `@ISA` |
| `methodHandleCache` | **Share (read-only)** | Maps `Class<?>` → `MethodHandle`; immutable after creation |

**Clearing is always correct** because caches are just performance optimizations.
The child pays a cold-cache penalty on first method resolution but is guaranteed
correct behavior.

**Long-term:** Once these caches are moved into `PerlRuntime` (Phase 5), each
runtime has its own caches and no cross-thread invalidation is needed.

**JRuby parallel:** JRuby uses volatile writes for method table modifications
and call-site invalidation on structural changes. We follow the same principle:
structural changes (method definition, `@ISA` modification) invalidate caches.

### 5.4 Thread Creation Checklist

Complete sequence for `threads->create()`:

| Step | What | How |
|------|------|-----|
| 1 | Clone `VariableStorage` | Deep-copy all global scalars/arrays/hashes/code refs |
| 2 | Clone closure captures | Deep-copy `capturedVars`/`codeObject` in every `RuntimeCode` |
| 3 | Fixup capture references | Redirect cloned captures to child's globals (identity map) |
| 4 | Handle `:shared` vars | Don't clone; wrap in `SharedScalar`/`SharedArray`/`SharedHash` |
| 5 | Invalidate external resources | Set `Closeable`/`Connection`-backed scalars to undef |
| 6 | Clear method caches | Clear `methodCache`, `linearizedClassesCache`, `overloadContextCache`, `isaStateCache` |
| 7 | Clone I/O state | Fresh `stdout`/`stderr`/`stdin` per `IOSystem` |
| 8 | Clone execution context | Fresh `CallerStack`, `DynamicScope`, `SpecialBlocks`, `RegexState` |
| 9 | Start JVM thread | `Thread.ofVirtual().start(...)` with `PerlRuntime.setCurrent(child)` |
| 10 | Call `CLONE($pkg)` | Walk all packages in child, call CLONE where defined |
| 11 | Execute user code | Apply the code reference with provided arguments |

Steps 2-3 (closure capture cloning with reference fixup) are the most technically
challenging part. The identity-map approach from Perl 5's `perl_clone()` is the
proven solution.

---

## 6. Implementation Phases

### Phase 0: Thread-Safety Audit (2 weeks)

(See Section 5 for detailed thread-creation semantics that inform all phases.)

Before any new features, make the existing single-threaded runtime safe for
the case where multiple JSR-223 `eval()` calls might overlap:

- Add `synchronized` to `GlobalVariable` map operations
- Make `RuntimeIO.stdout`/`stderr`/`stdin` volatile
- Add `synchronized` to `CallerStack` operations
- Document what is and isn't thread-safe

**Deliverable:** Existing functionality works under a global lock. No new APIs.

### Phase 1: PerlRuntime Shell (4 weeks)

Create the `PerlRuntime` class as a thin shell around existing statics:

1. Create `PerlRuntime` with `ThreadLocal<PerlRuntime> CURRENT`
2. Add `PerlRuntime.current()` accessor
3. Keep all existing static fields — just route through `PerlRuntime.current()`
4. Wire up `PerlLanguageProvider` to create and set a default `PerlRuntime`

**Key constraint:** No behavior change. Every existing test must still pass.
The `PerlRuntime` is just a new entry point that delegates to existing statics.

**Deliverable:** `PerlRuntime.current()` works; JSR-223 can create isolated
contexts (even if they still share state internally).

### Phase 2: De-static-ify I/O (3 weeks)

Move `RuntimeIO.stdout/stderr/stdin` into `PerlRuntime`:

```java
// Before: RuntimeIO.stdout
// After:  PerlRuntime.current().io.stdout

public class IOSystem {
    public RuntimeIO stdout;
    public RuntimeIO stderr;
    public RuntimeIO stdin;
}
```

This is the **smallest subsystem** and provides immediate value for JSR-223:
each ScriptEngine can redirect I/O independently.

**Deliverable:** JSR-223 `ScriptContext.getWriter()` works correctly per-context.

### Phase 3: De-static-ify CallerStack + DynamicScope (4 weeks)

Move `CallerStack.callerStack` and `DynamicVariableManager.variableStack` and
`RuntimeScalar.dynamicStateStack` into `PerlRuntime`:

```java
public class ExecutionContext {
    final List<Object> callerStack = new ArrayList<>();
    final Deque<DynamicState> dynamicScope = new ArrayDeque<>();
    final Stack<RuntimeScalar> dynamicStateStack = new Stack<>();
}
```

**Deliverable:** Call stacks and `local` variables are per-runtime.

### Phase 4: De-static-ify SpecialBlocks + RegexState (2 weeks)

Move `SpecialBlock.endBlocks/initBlocks/checkBlocks` and regex match state
(`$1`, `$2`, `$&`, etc.) into `PerlRuntime`.

**Regex State Isolation Detail:**

The following regex-related variables must be per-runtime (not per-thread-global):

| Variable | Description | Storage Location |
|----------|-------------|------------------|
| `$1`, `$2`, ... `$N` | Capture groups | `PerlRuntime.regexState.captures` |
| `$&` | Entire matched string | `PerlRuntime.regexState.lastMatch` |
| `$`` | String before match | `PerlRuntime.regexState.priorMatch` |
| `$'` | String after match | `PerlRuntime.regexState.postMatch` |
| `$+` | Last bracket matched | `PerlRuntime.regexState.lastBracket` |
| `@-` | Match start positions | `PerlRuntime.regexState.matchStarts` |
| `@+` | Match end positions | `PerlRuntime.regexState.matchEnds` |

```java
public class RegexState {
    final RuntimeArray captures = new RuntimeArray();  // $1, $2, etc.
    RuntimeScalar lastMatch;     // $&
    RuntimeScalar priorMatch;    // $`
    RuntimeScalar postMatch;     // $'
    RuntimeScalar lastBracket;   // $+
    final RuntimeArray matchStarts = new RuntimeArray();  // @-
    final RuntimeArray matchEnds = new RuntimeArray();    // @+
}
```

**Implementation**: Modify `RuntimeRegex` to store results in `PerlRuntime.current().regexState`
instead of static fields. The `ScalarSpecialVariable` class for capture variables must
look up values from the current runtime's regex state.

**Deliverable:** Regex captures and END blocks don't leak between runtimes.

### Phase 5: De-static-ify GlobalVariable (8 weeks)

This is the largest and most invasive change. Move all 13+ maps from
`GlobalVariable` into `PerlRuntime.variables`:

```java
public class VariableStorage {
    final Map<String, RuntimeScalar> globalVariables = new HashMap<>();
    final Map<String, RuntimeArray> globalArrays = new HashMap<>();
    final Map<String, RuntimeHash> globalHashes = new HashMap<>();
    final Map<String, RuntimeScalar> globalCodeRefs = new HashMap<>();
    final Map<String, RuntimeGlob> globalIORefs = new HashMap<>();
    final Map<String, RuntimeFormat> globalFormatRefs = new HashMap<>();
    // ... etc
}
```

**Strategy:** Use IDE "Find Usages" on each static field. Replace with
`PerlRuntime.current().variables.xxx`. Compile, test, repeat.

This is pure mechanical refactoring but touches hundreds of call sites.

**Deliverable:** Full runtime isolation. Multiple `PerlRuntime` instances
can run concurrently without interference.

### Phase 6: threads Module (4-6 weeks)

With runtime isolation complete, implement the `threads` Perl module.
See **Section 5** for the full thread-creation cloning protocol.

**Core API:**
1. `threads->create(\&sub, @args)` — clone runtime (Section 5.4) + start JVM thread
2. `threads->join()` — wait for completion, return value
3. `threads->detach()` — fire and forget
4. `threads->list()` — list running threads
5. `threads->self()` — current thread object
6. `threads->tid()` — thread ID (unique integer, main thread = 0)
7. `threads->yield()` — hint to scheduler (maps to `Thread.yield()`)
8. `threads->exit($status)` — exit current thread (see below)

**threads::shared API:**
9. `share($var)` / `:shared` attribute — SharedScalar/SharedArray/SharedHash wrappers
10. `lock($var)` — mutex on shared variables
11. `cond_wait()`/`cond_signal()`/`cond_broadcast()` — condition variables
12. `is_shared($var)` — check if variable is shared

**Internal callbacks:**
13. `CLONE($package)` callback — called in child after cloning (Section 5.2)
14. Closure capture cloning with reference fixup (Section 5.1)
15. Method cache invalidation in child (Section 5.3)

**threads->exit() Semantics:**

In Perl 5 ithreads, `exit()` in a non-main thread exits only that thread, not the
entire process. This must be implemented carefully:

```java
public static void threadsExit(int status) {
    PerlThread current = PerlThread.current();
    if (current.isMainThread()) {
        // Main thread: exit the process
        System.exit(status);
    } else {
        // Worker thread: exit only this thread
        current.setExitStatus(status);
        throw new ThreadExitException(status);
    }
}
```

The `ThreadExitException` should be caught by the thread wrapper created in
`threads->create()` and should NOT propagate to the main thread or cause
"Perl exited with active threads" warnings.

**Virtual Threads note:** Use `Thread.ofVirtual()` by default. Perl's
ithreads are already "heavy" (full interpreter clone), so making the actual
JVM thread lightweight is a net win.

**Timeline Note:** This phase is estimated at 4 weeks but may take 6 weeks
due to the complexity of closure capture cloning with reference fixup (the
most technically challenging part of the entire implementation).

### Phase 7: Runtime Pool + JSR-223 Thread Safety (3 weeks)

1. Implement `PerlRuntimePool` for web server use cases
2. Declare `THREADING` parameter as `"THREAD-ISOLATED"` in ScriptEngineFactory
3. Update JSR-223 to use pool automatically
4. Benchmark: target 1000+ concurrent requests without interference

---

## 7. What We Explicitly Don't Implement

| Feature | Reason | Alternative |
|---------|--------|-------------|
| True `fork()` | JVM cannot split processes (JRuby proved this) | `system()`, `threads`, `ProcessBuilder` |
| Classloader isolation | Type barriers, memory overhead, debugging pain (Jython lesson) | ThreadLocal + PerlRuntime instances |
| Copy-on-write cloning | JVM has no COW memory; deep copy is the only option | Full clone at thread creation (like Perl 5 ithreads) |
| `fork()` in test harnesses | Many CPAN test suites use fork | Thread-based test runner (`jprove`) |
| `open(PIPE, "-\|")` | Requires fork | `ProcessBuilder` wrapper |

---

## 8. Performance Considerations

### ThreadLocal Overhead

ThreadLocal access is ~2-5ns per call on modern JVMs. With an estimated
10-50 ThreadLocal lookups per Perl statement, overhead is 20-250ns per
statement. Perl statement execution is typically 100ns-10µs, so overhead
is 0.25-25% — acceptable for the concurrency it enables.

**Optimization:** Hot paths (like `GlobalVariable.getGlobalVariable`) can
cache the PerlRuntime reference in a local variable at method entry.

### Thread Creation Cost

Perl 5 ithreads clone the entire interpreter (~100ms for a complex program).
PerlOnJava thread creation involves:
- Deep-copying `VariableStorage` maps (~1-10ms depending on variable count)
- Deep-copying closure captures + reference fixup (~0.5-5ms)
- Clearing method caches in child (~0.1ms)
- Calling `CLONE` on all packages (~0.1-1ms)
- Creating a virtual thread (~0.1ms)
- Linking shared variables (~0.1ms)

**Expected:** 2-15ms per thread creation — still significantly faster than Perl 5.

### Memory Per Runtime

Each `PerlRuntime` holds copies of all global variables. For a typical program
with ~1000 global variables, each runtime adds ~100KB-1MB of memory. This is
comparable to Perl 5 interpreter clones.

---

## 9. Testing Strategy

### Unit Tests
- Runtime isolation: create 2 runtimes, modify globals in one, verify other is unchanged
- SharedVariable: concurrent increment from N threads, verify final count
- CallerStack isolation: nested calls in separate runtimes don't interfere
- Closure capture isolation: modify captured lexical in child, verify parent is unchanged
- Method cache isolation: modify `@ISA` in child, verify parent's method resolution unchanged

### Integration Tests
- `threads->create()` with arguments and return values
- `threads::shared` with lock/cond_wait/cond_signal
- JSR-223 concurrent eval from multiple Java threads
- Existing test suite passes unchanged (regression)
- CLONE method called correctly: DBI handle invalidation in child
- Closure with `:shared` variable: both threads see same value
- Nested closures: captured-of-captured variables cloned correctly

### Stress Tests
- 100 concurrent threads modifying shared variables
- 1000 concurrent JSR-223 eval calls
- Thread creation/destruction churn

### Perl 5 Test Compatibility
- Run `perl5_t/t/op/threads.t` (adapted for PerlOnJava)
- Run representative CPAN module tests that use threads

---

## 10. Success Criteria

- ✅ Multiple `PerlRuntime` instances run concurrently without interference
- ✅ `threads->create()` and `threads->join()` work for basic patterns
- ✅ `threads::shared` variables are correctly synchronized
- ✅ JSR-223 can handle concurrent `eval()` calls
- ✅ `fork()` returns undef with clear error message (not crash)
- ✅ No regression in existing single-threaded functionality
- ✅ Thread creation < 10ms for typical programs
- ✅ < 5% performance overhead for single-threaded programs

---

## 11. Timeline Summary

| Phase | Duration | Deliverable | Risk |
|-------|----------|-------------|------|
| 0: Thread-Safety Audit | 2 weeks | Global lock safety | Low |
| 1: PerlRuntime Shell | 4 weeks | ThreadLocal routing | Low |
| 2: De-static I/O | 3 weeks | JSR-223 I/O isolation | Low |
| 3: De-static CallerStack + DynamicScope | 4 weeks | Per-runtime call stacks | Low |
| 4: De-static SpecialBlocks + Regex | 2 weeks | Per-runtime regex/blocks | Low |
| 5: De-static GlobalVariable | 8-12 weeks | Full runtime isolation | **High** |
| 6: threads Module | 4-6 weeks | Perl `use threads` works | **Medium** |
| 7: Runtime Pool + JSR-223 | 3 weeks | Production web server | Low |
| **Total** | **30-38 weeks** | | |

**Risk Notes:**

- **Phase 5** is the critical path bottleneck. The estimate of 8 weeks is optimistic;
  it touches "hundreds of call sites" and requires careful testing. Budget 10-12 weeks.
- **Phase 6** closure capture cloning with reference fixup is the most technically
  challenging part of the implementation. May require additional time for edge cases.
- Phases 0-2 provide immediate value for JSR-223 embedding use cases and should be
  prioritized.
- Phase 6 is only possible after Phase 5 is complete.

---

## 12. Open Questions

1. **Virtual threads vs platform threads:** Should `threads->create()` default
   to virtual threads? Virtual threads are cheap but have caveats with
   `synchronized` blocks (pinning). Test both and choose based on benchmarks.
   **Update:** JEP 491 will resolve the pinning issue in future Java releases.
   Recommend defaulting to virtual threads for I/O-bound Perl code.

2. **Clone depth:** Should thread creation clone `@INC`, `%INC`, loaded modules?
   Perl 5 clones everything. We could optimize by sharing read-only state.

3. **Signal handling:** `$SIG{INT}` etc. — should signals be per-runtime or
   process-wide? Perl 5 delivers to the main thread.

4. **Reference fixup performance:** The two-pass clone (deep copy + identity
   map fixup) may be expensive for programs with many closures. Profile
   Perl 5's `perl_clone()` for comparison benchmarks.

5. **Nested closures:** A closure that captures a variable which is itself
   captured from an outer scope creates chains. The fixup pass must follow
   these chains transitively. Verify with test cases.

6. **Runtime Pool Configuration:** For web server scenarios, should expose
   configurable limits: `maxPoolSize`, `minPoolSize`, `maxIdleTime`.

### Resolved Questions

- **CLONE method priority:** Yes, implement in Phase 6. Required for DBI,
  Storable, and any module that holds external resources. (See Section 5.2)
- **Method cache handling:** Clear all caches in child at thread creation.
  Move to per-runtime in Phase 5. (See Section 5.3)
- **DBI handles in child:** Invalidate automatically for `Closeable`/`Connection`
  objects; module's CLONE method reconnects if needed. (See Section 5.2)
- **`exit()` semantics:** Resolved in Phase 6 design. `exit()` in a non-main
  thread exits only that thread via `ThreadExitException`. (See Section 6)

---

## Related Documents

- `threads.md` — Original threading specification (superseded by this document)
- `fork.md` — Fork analysis (superseded by this document)
- `multiplicity.md` — Multiplicity design (superseded by this document)
- `jsr223-perlonjava-web.md` — Web integration (benefits from Phases 2 and 7)

## External References

- [JRuby Concurrency Wiki](https://github.com/jruby/jruby/wiki/Concurrency-in-jruby)
- [Jython Concurrency Guide](https://jython.readthedocs.io/en/latest/Concurrency/)
- [perlthrtut](https://perldoc.perl.org/perlthrtut) — Perl threading tutorial
- [threads](https://perldoc.perl.org/threads) — Perl threads module documentation
- [threads::shared](https://perldoc.perl.org/threads::shared) — Perl shared variable documentation
- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444) — Java virtual threads specification
- [JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491) — Future fix for synchronized pinning
- [Oracle Virtual Threads Guide](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html) — Java 21 virtual threads documentation

---

## Technical Review Summary

**Review Date:** 2026-03-26  
**Status:** APPROVED with minor recommendations

### Key Findings

1. **Strategy is sound:** The JRuby-inspired ThreadLocal + PerlRuntime model is correct.
2. **Global state inventory verified:** All static mutable state correctly identified.
3. **Perl 5 compatibility achievable:** ithreads semantics can be faithfully implemented.
4. **Virtual threads recommended:** Use `Thread.ofVirtual()` with `ReentrantLock` (not `synchronized`).
5. **fork() approach proven:** JRuby's 20-year track record validates returning undef.

### Performance Expectations

- **Thread creation:** 2-15ms (5-50x faster than Perl 5)
- **Single-threaded overhead:** 0.25-25% from ThreadLocal routing (acceptable)
- **Memory per runtime:** ~100KB-1MB (comparable to Perl 5)

### Recommendations Incorporated

- Added `threads->exit()` semantics (Section 6)
- Added `threads->yield()` and `threads->tid()` to API (Section 6)
- Added regex state isolation detail (Section 4, Phase 4)
- Updated virtual thread pinning caveat with JEP 491 reference
- Updated timeline with risk assessment
- Moved resolved questions out of open questions

---

## Progress Tracking

### Current Status: Phase 0 complete, full runtime isolation achieved (2026-04-10)

All mutable runtime state has been migrated from static fields into `PerlRuntime`
instance fields with ThreadLocal-based access. Multiple independent Perl interpreters
can now coexist within the same JVM process with isolated state. Compilation is
thread-safe via a global `COMPILE_LOCK` (ReentrantLock) that serializes all
parsing/emitting, while allowing concurrent execution of compiled code.

### Completed Phases

- [x] **Phase 1: PerlRuntime Shell** (2026-04-10)
  - Created `PerlRuntime.java` with `ThreadLocal<PerlRuntime> CURRENT`
  - Added `current()`, `initialize()`, `setCurrent()` API
  - Wired `PerlRuntime.initialize()` into `Main.main()` and test setUp methods
  - Added `ensureRuntimeInitialized()` safety net in `PerlLanguageProvider`

- [x] **Phase 2: De-static-ify I/O** (2026-04-10)
  - Moved `RuntimeIO.stdout/stderr/stdin` into `PerlRuntime`
  - Moved `selectedHandle`, `lastWrittenHandle`, `lastAccessedHandle`, `lastReadlineHandleName`
  - Added static getter/setter methods on `RuntimeIO`
  - Updated `EmitOperator` to use `INVOKESTATIC` instead of `PUTSTATIC`
  - Updated 15 consumer files (IOOperator, RuntimeGlob, TieOperators, etc.)

- [x] **Phase 3: De-static-ify CallerStack + DynamicScope** (2026-04-10)
  - Moved `CallerStack.callerStack` to `PerlRuntime.callerStack`
  - Moved `DynamicVariableManager.variableStack` to `PerlRuntime.dynamicVariableStack`
  - Moved `RuntimeScalar.dynamicStateStack` to `PerlRuntime.dynamicStateStack`

- [x] **Phase 4: De-static-ify SpecialBlocks** (2026-04-10)
  - Moved `SpecialBlock.endBlocks/initBlocks/checkBlocks` to PerlRuntime
  - Added public getters on SpecialBlock

- [x] **Phase 5a: De-static-ify InheritanceResolver** (2026-04-10)
  - Moved 7 static fields: linearizedClassesCache, packageMRO, methodCache,
    overloadContextCache, isaStateCache, autoloadEnabled, currentMRO
  - Updated DFS.java, C3.java, and 4 consumer files

- [x] **Phase 5b: De-static-ify GlobalVariable** (2026-04-10)
  - Moved all 17 static fields: symbol tables (globalVariables, globalArrays,
    globalHashes, globalCodeRefs), IO/Format refs, aliasing maps, caches,
    classloader, declared variable tracking
  - Added static accessor methods (getGlobalVariablesMap(), etc.)
  - Updated 20 consumer files across frontend, backend, and runtime packages

- [x] **Phase 5c: De-static-ify Regex State** (2026-04-10)
  - Moved 14 static fields from RuntimeRegex into PerlRuntime: globalMatcher,
    globalMatchString, lastMatchedString, lastMatch start/end, lastSuccessful*,
    lastSuccessfulPattern, lastMatchUsedPFlag, lastMatchUsedBackslashK,
    lastCaptureGroups, lastMatchWasByteString
  - Added static getter/setter methods on RuntimeRegex
  - Updated RegexState.java, ScalarSpecialVariable.java, HashSpecialVariable.java

- [x] **Phase 5d: De-static-ify RuntimeCode Caches** (2026-04-10)
  - Moved evalBeginIds, evalCache, methodHandleCache, anonSubs, interpretedSubs,
    evalContext, evalDepth, inline method cache arrays into PerlRuntime
  - Added static getter methods on RuntimeCode (getEvalBeginIds(), getEvalCache(),
    getAnonSubs(), getInterpretedSubs(), getEvalContext())
  - Added incrementEvalDepth()/decrementEvalDepth()/getEvalDepth() methods
  - Changed EmitterMethodCreator bytecode from GETSTATIC/PUTSTATIC to INVOKESTATIC
  - Changed EmitSubroutine bytecode from GETSTATIC to INVOKESTATIC for interpretedSubs
  - Updated 13 consumer files: ScalarSpecialVariable, BytecodeInterpreter,
    EmitterMethodCreator, EmitEval, EmitSubroutine, WarnDie, EvalStringHandler,
    SpecialBlockParser, SubroutineParser, BytecodeCompiler, EmitVariable,
    CompileAssignment
  - Decision: evalCache/methodHandleCache are per-runtime (simpler, no sharing)

### Files Created
- `src/main/java/org/perlonjava/runtime/runtimetypes/PerlRuntime.java`

### Key Design Decisions
- Kept original static method signatures on migrated classes — callers don't change
- Used public accessor methods (e.g., `GlobalVariable.getGlobalVariablesMap()`) for
  cross-package access to PerlRuntime fields
- ThreadLocal overhead is negligible (~1ns per access, JIT-optimized)
- evalCache/methodHandleCache are per-runtime (not shared) — simpler, avoids
  cross-runtime class compatibility issues

### Multiplicity Demo (2026-04-10)
- Created `dev/sandbox/multiplicity/MultiplicityDemo.java` — launches N threads, each with its
  own PerlRuntime, compiles and executes a Perl script, captures per-thread STDOUT
- Uses `PerlLanguageProvider.executePerlCode()` which handles the full lifecycle:
  initialization, compilation (under COMPILE_LOCK), and execution (no lock)
- INIT/CHECK/UNITCHECK/END blocks execute correctly for each interpreter
- Successfully tested with 126 concurrent interpreters running unit tests
- **122/126 tests pass**; remaining 4 failures are pre-existing `DESTROY` TODO:
  - `tie_array.t`, `tie_handle.t`, `tie_hash.t`, `tie_scalar.t` — object destructors not implemented
- Run with: `./dev/sandbox/multiplicity/run_multiplicity_demo.sh`

### Local Save/Restore Stack Fix (2026-04-10)

**Problem:** After Phase 3 migrated `DynamicVariableManager.variableStack` and
`RuntimeScalar.dynamicStateStack` to per-runtime, `local` still failed under
multiplicity. With 2+ interpreters, `local $x` would not restore the original value
at scope exit — all "restored" assertions failed.

**Root cause:** Phase 3 only migrated 2 of 17 dynamic state stacks. The remaining
15 were still shared static fields. The most critical was
`GlobalRuntimeScalar.localizedStack` — this is the stack used when `local` is
applied to package variables (the most common case). With 2 threads doing
`local $global_var` concurrently, they pushed/popped from the same stack, causing
each thread to restore the other thread's saved state.

**Fix (commit e2f16ec07):** Migrated all 16 remaining stacks to per-PerlRuntime
instance fields, following the same accessor-method pattern:

| Class | Stack Field(s) | Type |
|-------|----------------|------|
| `GlobalRuntimeScalar` | `localizedStack` | `Stack<Object>` (SavedGlobalState) |
| `GlobalRuntimeArray` | `localizedStack` | `Stack<Object>` (SavedGlobalArrayState) |
| `GlobalRuntimeHash` | `localizedStack` | `Stack<Object>` (SavedGlobalHashState) |
| `RuntimeArray` | `dynamicStateStack` | `Stack<RuntimeArray>` |
| `RuntimeHash` | `dynamicStateStack` | `Stack<RuntimeHash>` |
| `RuntimeStash` | `dynamicStateStack` | `Stack<RuntimeStash>` |
| `RuntimeGlob` | `globSlotStack` | `Stack<Object>` (GlobSlotSnapshot) |
| `RuntimeHashProxyEntry` | `dynamicStateStack` | `Stack<RuntimeScalar>` |
| `RuntimeArrayProxyEntry` | `dynamicStateStackInt` + `dynamicStateStack` | `Stack<Integer>` + `Stack<RuntimeScalar>` |
| `ScalarSpecialVariable` | `inputLineStateStack` | `Stack<Object>` (InputLineState) |
| `OutputAutoFlushVariable` | `stateStack` | `Stack<Object>` (State) |
| `OutputRecordSeparator` | `orsStack` | `Stack<String>` |
| `OutputFieldSeparator` | `ofsStack` | `Stack<String>` |
| `ErrnoVariable` | `errnoStack` + `messageStack` | `Stack<int[]>` + `Stack<String>` |

Each class now has a `private static Stack<T> stackName()` accessor that delegates
to `PerlRuntime.current().<field>`. Inner types (SavedGlobalState, etc.) remain
private to their classes; `PerlRuntime` stores them as `Stack<Object>` with
`@SuppressWarnings("unchecked")` casts in the accessor methods.

**Impact:** Fixed 8 previously-failing tests under multiplicity: `local.t` (74/74),
`chomp.t`, `defer.t`, `local_glob_dynamic.t`, `sysread_syswrite.t`,
`array_autovivification.t`, `vstring.t`, `nested_for_loops.t`.

### Phase 0: Compilation Thread Safety (2026-04-10)

**Problem:** The multiplicity demo serializes initial compilation with a `COMPILE_LOCK`,
but `eval "string"` at runtime goes through `EvalStringHandler` → `Lexer` → `Parser` →
emitter → class loading with **no locking**. Concurrent `eval` from multiple threads
will corrupt shared mutable static state.

Additionally, `executePerlCode()` (the main entry point for running Perl code) had no
locking at all — it was only safe for single-threaded CLI use. And `globalInitialized`
was a shared static boolean, causing thread 2+ to skip `initializeGlobals()` entirely.

**Architecture fix (commit TBD):**

1. **`globalInitialized` moved to per-PerlRuntime** — Each runtime tracks its own
   initialization state. Previously, thread 1 set the shared static to `true`,
   causing threads 2-N to skip `initializeGlobals()` and run without `$_`, `@INC`,
   built-in modules, etc.

2. **`executePerlCode()` now uses COMPILE_LOCK** — The compilation phase (tokenize,
   parse, compile) runs under the lock, then the lock is released before execution:
   ```
   COMPILE_LOCK.lock()
     savedScope = getCurrentScope()
     initializeGlobals() (per-runtime, idempotent)
     tokenize → parse → compileToExecutable()
   COMPILE_LOCK.unlock()

   executeCode() — runs UNITCHECK, CHECK, INIT, main code, END (no lock)
   ```

3. **Demo simplified** — Uses `executePerlCode()` instead of `compilePerlCode()` +
   `apply()`. No more redundant demo-level lock. INIT/CHECK/UNITCHECK blocks now
   execute correctly (previously skipped, causing begincheck.t failures).

**Audit results** — shared mutable state found in three subsystems:

#### Parser (frontend/) — 11 fields

| Severity | File | Field | Issue |
|----------|------|-------|-------|
| HIGH | `SpecialBlockParser.java:25` | `symbolTable` | Global parser scope, read/written from 27 call sites |
| HIGH | `NumberParser.java:27` | `numificationCache` | LRU LinkedHashMap; `.get()` mutates internal state |
| MEDIUM | `ScopedSymbolTable.java:38` | `nextWarningBitPosition` | Non-atomic counter for `use warnings::register` |
| MEDIUM | `StringSegmentParser.java:50` | `codeBlockCaptureCounter` | Non-atomic counter for regex code block captures |
| MEDIUM | `ScopedSymbolTable.java:18` | `warningBitPositions` | HashMap mutated by `registerCustomWarningCategory()` |
| MEDIUM | `ScopedSymbolTable.java:21` | `packageVersions` | HashMap mutated during `use` and `clear()`ed on reset |
| MEDIUM | `DataSection.java:24` | `processedPackages` | HashSet mutated during `__DATA__` parsing |
| MEDIUM | `DataSection.java:29` | `placeholderCreated` | HashSet mutated during `__DATA__` parsing |
| MEDIUM | `FieldRegistry.java:17` | `classFields` | HashMap mutated by `registerField()` |
| MEDIUM | `FieldRegistry.java:21` | `classParents` | HashMap mutated by `registerField()` |
| LOW | `Lexer.java:44` | `isOperator` | Not final but never mutated after class init |

#### Emitter (backend/) — 8 fields

| Severity | File | Field | Issue |
|----------|------|-------|-------|
| HIGH | `ByteCodeSourceMapper.java:17-29` | 7 HashMap/ArrayList collections | Source mapping; concurrent `computeIfAbsent()` corrupts HashMap internals |
| HIGH | `LargeBlockRefactorer.java:29` | `controlFlowDetector` | Single shared visitor; `reset()`/`scan()` race → wrong bytecode |
| HIGH | `EmitterMethodCreator.java:52` | `classCounter` | Non-atomic `++`; duplicate class names → `LinkageError` |
| MEDIUM | `BytecodeCompiler.java:80` | `nextCallsiteId` | Non-atomic `++`; duplicate IDs corrupt `/o` regex cache |
| MEDIUM | `EmitRegex.java:21` | `nextCallsiteId` | Non-atomic `++`; same issue for JVM path |
| MEDIUM | `Dereference.java:19` | `nextMethodCallsiteId` | Non-atomic `++`; duplicate IDs corrupt inline method cache |
| LOW | `EmitterMethodCreator.java:50` | `skipVariables` | Never mutated; should be `final` |

#### Class loader — already safe
`CustomClassLoader` is per-`PerlRuntime` (migrated in Phase 5b).

**Implementation plan (two-part):**

1. **Quick fixes (no lock needed):** ✅ Done
   - Replaced 4 counters with `AtomicInteger`: `classCounter`, `nextCallsiteId` (×2),
     `nextMethodCallsiteId`
   - Marked `skipVariables` as `final`
   - Replaced `LargeBlockRefactorer.controlFlowDetector` singleton with new instance
     per call (matches the existing `controlFlowFinderTl` ThreadLocal pattern on line 34)

2. **Global compile lock:** ✅ Done
   - Added `static final ReentrantLock COMPILE_LOCK` to `PerlLanguageProvider`
   - Acquired in `compilePerlCode()` and in both `EvalStringHandler.evalString()` overloads
   - This serializes all compilation (initial + runtime eval) but guarantees safety
   - Lock is reentrant so nested evals work without deadlock
   - Future optimization: migrate parser/emitter static state to per-runtime, remove lock

### Reentrancy Analysis (2026-04-10)

**Question:** What happens when `eval "string"` triggers a BEGIN block that itself
requires a module (nested compilation)?

**Answer:** `ReentrantLock` handles this correctly. The call chain runs entirely on
the same thread:

```
eval "use Foo"
  → EvalStringHandler.evalString() acquires COMPILE_LOCK (count=1)
    → Parser.parse() encounters `use Foo` → BEGIN block
      → SpecialBlockParser.runSpecialBlock() → executePerlAST()
        → require Foo → PerlLanguageProvider.compilePerlCode()
          → COMPILE_LOCK.lock() — same thread, count=2
          → compile module → unlock (count=1)
        → continue executing BEGIN block (execution, no lock needed — but lock
          is still held at count=1 by the outer compilation)
    → Parser continues parsing the rest of the eval string
  → unlock (count=0)
```

Same-thread reentrancy works because `ReentrantLock` increments the hold count on
each nested `lock()` and decrements on each `unlock()`.

**Bug found and fixed:** `evalStringWithInterpreter` used `isHeldByCurrentThread()`
in its `finally` block to decide whether to release the lock. This over-decrements
in nested scenarios:

```
Outer compilation holds lock (count=1)
  → BEGIN triggers inner evalStringWithInterpreter
    → lock (count=2)
    → compile OK, explicit unlock before execution (count=1)
    → execution runs
    → finally: isHeldByCurrentThread() → TRUE (outer holds it!)
    → unlock (count=0) ← BUG: released the outer's lock!
```

**Fix (commit 510106cd9):** Replaced `isHeldByCurrentThread()` with a `boolean
compileLockReleased` flag that tracks whether the success-path unlock already
happened. The finally block only unlocks if the flag is false (error path).

**Why not release the lock during BEGIN execution?** `runSpecialBlock` is called
**mid-parse** — the parser is suspended with its state intact (token position,
symbol table, scope depth). That state lives in shared statics like
`SpecialBlockParser.symbolTable` and `ByteCodeSourceMapper` collections. If the
lock were released, another thread could start compiling and corrupt this state.
Releasing the lock around BEGIN blocks is only viable after migrating parser/emitter
state from shared statics to per-compilation-context (which would eliminate the
lock entirely).

### Per-Runtime CWD Isolation (2026-04-10)

**Problem:** `chdir()` called `System.setProperty("user.dir", ...)` which is JVM-global.
When multiple interpreters called `chdir()` concurrently, they overwrote each other's
working directory. This caused `directory.t` and `glob.t` to fail under multiplicity.

**Fix (commit c30eeb487):** Added per-runtime `String cwd` field to `PerlRuntime`,
initialized from `System.getProperty("user.dir")` at construction time.

- `PerlRuntime.cwd` — per-runtime CWD field
- `PerlRuntime.getCwd()` — static accessor with fallback to `System.getProperty("user.dir")`
- `Directory.chdir()` — updates `PerlRuntime.current().cwd` instead of `System.setProperty()`
- `RuntimeIO.resolvePath()` — resolves relative paths against `PerlRuntime.getCwd()`
- Updated all 21 `System.getProperty("user.dir")` call sites across 12 files:
  `SystemOperator.java`, `FileSpec.java`, `POSIX.java`, `Internals.java`,
  `IPCOpen3.java`, `XMLParserExpat.java`, `ScalarGlobOperator.java`, `DirectoryIO.java`,
  `PipeInputChannel.java`, `PipeOutputChannel.java`, `Directory.java`, `RuntimeIO.java`
- `ArgumentParser.java` kept as-is (sets initial `user.dir` before runtime creation for `-C` flag)

**Impact:** `directory.t` (9/9) and `glob.t` (15/15) now pass under concurrent interpreters.

### Per-Runtime PID and Pipe Thread Fix (2026-04-10)

**Problem 1 — Shared `$$`:** All interpreters shared the same JVM PID via
`ProcessHandle.current().pid()`. Tests that use `$$` in temp filenames
(`io_read.t`, `io_seek.t`, `io_layers.t`) produced identical filenames across
concurrent interpreters, causing file collisions and data races.

**Problem 2 — Unbound pipe threads:** `PipeInputChannel` and `PipeOutputChannel`
spawn background daemon threads for stderr/stdout consumption. These threads had
no `PerlRuntime` bound via `ThreadLocal`, so `GlobalVariable.getGlobalIO("main::STDERR")`
calls threw `IllegalStateException` and fell back to `System.out`/`System.err`,
bypassing per-runtime STDOUT/STDERR redirection. Under concurrent multiplicity testing,
this caused `io_pipe.t` failures.

**Fix (commit 0179c888e):**

1. **Per-runtime unique PID:** Added `AtomicLong PID_COUNTER` to `PerlRuntime`, starting
   at the real JVM PID. Each runtime gets `PID_COUNTER.getAndIncrement()` — first runtime
   gets the real PID (backward compatible), subsequent runtimes get unique incrementing values.
   `GlobalContext.initializeGlobals()` sets `$$` from `PerlRuntime.current().pid`.

2. **Pipe thread runtime binding:** Both `PipeInputChannel.setupProcess()` and
   `PipeOutputChannel.setupProcess()` now capture `PerlRuntime.currentOrNull()` before
   spawning background threads, and call `PerlRuntime.setCurrent(parentRuntime)` inside
   the thread lambda. This ensures pipe stderr/stdout consumer threads can access the
   correct per-runtime IO handles.

**Impact:** Fixed 5 previously-failing tests: `io_read.t`, `io_seek.t`, `io_pipe.t`,
`io_layers.t`, `digest.t`. Multiplicity stress test improved from 117/126 to **122/126**.

### Next Steps

1. **Phase 6:** Implement `threads` module (requires runtime cloning — see Sections 5.1-5.4
   for the full cloning protocol). This is new functionality: deep-cloning runtime state,
   closure capture fixup, `CLONE($pkg)` callbacks, `threads::shared` wrappers.

2. **Phase 7: Runtime Pool** — An optimization/convenience layer, not a new capability.
   The core multiplicity infrastructure is already complete; `PerlRuntime` instances can
   be created and destroyed ad-hoc (as the `MultiplicityDemo` does). The pool amortizes
   runtime initialization cost (loading built-in modules, setting up `@INC`/`%ENV`, etc.)
   by reusing warm runtimes instead of re-creating them per request. Also provides
   concurrency limiting (cap simultaneous runtimes to prevent OOM) and clean reset
   between uses. Same pattern as JDBC connection pools or servlet thread pools. Primarily
   useful for high-throughput web server embedding (mod_perl model), not needed for CLI
   usage or the demo.

3. **Future optimization:** Migrate parser/emitter static state to per-runtime, remove COMPILE_LOCK

### Performance Baseline: master vs feature/multiplicity (2026-04-10)

Benchmarks run on both branches with `make clean ; make` before each run.
All benchmarks are in `dev/bench/`.

#### Speed Benchmarks

| Benchmark | master (ops/s) | branch (ops/s) | Change |
|-----------|---------------|-----------------|--------|
| lexical (local var loop) | 394,139 | 374,732 | **-4.9%** |
| global (global var loop) | 77,720 | 73,550 | **-5.4%** |
| eval_string (`eval "..."`) | 86,327 | 82,183 | **-4.8%** |
| closure (create + call) | 863 | 569 | **-34.1%** |
| method (dispatch) | 436 | 319 | **-26.9%** |
| regex (matching) | 50,760 | 47,219 | **-7.0%** |
| string (operations) | 28,884 | 30,752 | **+6.5%** |

#### Memory Benchmarks

Memory is essentially unchanged (within noise): ~88MB RSS startup,
identical delta ratios for arrays (15.4x), hashes (2.3x), strings (8.0x),
nested structures (2.7x).

#### Analysis

Most benchmarks show a 5-7% slowdown from ThreadLocal routing, consistent with
the design doc estimate of "0.25-25%." Two benchmarks show larger regressions:

**Closure (-34%):** The closure call path (`RuntimeCode.apply()`) has **zero**
`PerlRuntime.current()` lookups but **14-17 other ThreadLocal lookups** per
invocation from `WarningBitsRegistry` (7 ThreadLocals x push/pop),
`HintHashRegistry` (3 ops), and `argsStack` (2 ops). These are the pre-existing
ThreadLocal stacks that were already present on master. The regression likely
comes from increased ThreadLocal contention or JIT optimization interference
from the additional ThreadLocal fields on `PerlRuntime`.

**Method (-27%):** The method dispatch path has two modes:
- **Cache hit** (`callCached()`): Only 1 `PerlRuntime.current()` lookup — fast
- **Cache miss** (`findMethodInHierarchy()`): **12-14** `PerlRuntime.current()`
  lookups plus 14-17 from `apply()` = ~26-31 total ThreadLocal lookups

The regression suggests the inline cache hit rate may have decreased, or the
cache-miss path is being exercised more due to per-runtime cache isolation
(each runtime starts with a cold cache).

#### Optimization Plan

**Goal:** Reduce the closure and method dispatch regressions to under 10%
(matching the 5-7% range of other benchmarks). The general 5-7% slowdown
from ThreadLocal routing is acceptable and expected.

**Git workflow:**

```
feature/multiplicity          (this branch — known good, all tests pass)
  └── feature/multiplicity-opt  (create this — do optimization work here)
```

1. Fetch and check out this branch:
   ```bash
   git fetch origin feature/multiplicity
   git checkout feature/multiplicity
   ```
2. Create a new branch for optimization work:
   ```bash
   git checkout -b feature/multiplicity-opt
   ```
3. Do the optimization work on `feature/multiplicity-opt` (see tiers below).
   Commit after each step so progress is preserved.
4. **If the optimization succeeds** (target benchmarks within 10% of master):
   merge back into `feature/multiplicity` and push:
   ```bash
   git checkout feature/multiplicity
   git merge feature/multiplicity-opt
   git push origin feature/multiplicity
   ```
5. **If the optimization fails** (no measurable gain, or introduces regressions):
   go back to `feature/multiplicity`, document the failure in this section
   (what was tried, what the benchmark numbers were, why it did not work),
   and delete the branch:
   ```bash
   git checkout feature/multiplicity
   # Add a "Failed Attempts" subsection below with findings
   git commit -am "docs: document failed optimization attempt"
   git branch -D feature/multiplicity-opt
   ```
   This ensures the next engineer knows what was already tried and can
   avoid repeating the same work.

**Methodology for each optimization step:**

1. Create a commit on `feature/multiplicity-opt` with the optimization
2. Run `make clean ; make` to verify no test regressions
3. Run the relevant benchmark(s) 3 times, take the median:
   ```bash
   ./jperl dev/bench/benchmark_closure.pl   # target: closure
   ./jperl dev/bench/benchmark_method.pl    # target: method
   ./jperl dev/bench/benchmark_lexical.pl   # control: should not regress
   ./jperl dev/bench/benchmark_global.pl    # control: should not regress
   ```
4. Compare against the baseline numbers in the table above
5. **Revert if:** the optimization does not measurably improve the target
   benchmark AND does not improve code architecture (e.g., reducing
   unnecessary abstraction layers). Keep only if it delivers measurable
   improvement or is architecturally cleaner regardless of performance.
6. Run the 126-interpreter stress test to verify multiplicity still works:
   ```bash
   bash dev/sandbox/multiplicity/run_multiplicity_demo.sh src/test/resources/unit/*.t
   ```

Listed in order of expected impact. Each tier is independent — do not
proceed to Tier 2 unless Tier 1 has been completed and benchmarked.

---

**Tier 1: Cache `PerlRuntime.current()` in local variables (LOW RISK)**

These are mechanical changes — cache the ThreadLocal result at method entry
instead of calling `PerlRuntime.current()` multiple times. Pattern:

```java
// BEFORE: N ThreadLocal lookups
public static Foo doSomething(String key) {
    Foo a = PerlRuntime.current().mapA.get(key);  // lookup 1
    Foo b = PerlRuntime.current().mapB.get(key);  // lookup 2
    PerlRuntime.current().mapC.put(key, b);       // lookup 3
    return a;
}

// AFTER: 1 ThreadLocal lookup
public static Foo doSomething(String key) {
    PerlRuntime rt = PerlRuntime.current();        // lookup 1
    Foo a = rt.mapA.get(key);
    Foo b = rt.mapB.get(key);
    rt.mapC.put(key, b);
    return a;
}
```

**Step 1a: `GlobalVariable.getGlobalCodeRef()`**
- File: `src/main/java/org/perlonjava/runtime/runtimetypes/GlobalVariable.java`
- Currently 4 `PerlRuntime.current()` calls per invocation (pinnedCodeRefs,
  globalCodeRefs get, globalCodeRefs put, pinnedCodeRefs put)
- Called N times during method hierarchy traversal in `findMethodInHierarchy()`
- Expected savings: 3 lookups per call x N calls per method dispatch
- **Expected impact on method benchmark:** moderate (reduces cache-miss cost)
- Benchmark after this step before proceeding

**Step 1b: `InheritanceResolver.findMethodInHierarchy()`**
- File: `src/main/java/org/perlonjava/runtime/mro/InheritanceResolver.java`
- Currently 12-14 `PerlRuntime.current()` calls per cache-miss invocation
  across `getMethodCache()`, `getIsaStateCache()`, `getLinearizedClassesCache()`,
  `getPackageMROMap()`, `getCurrentMRO()`, `isAutoloadEnabled()`
- Two approaches (pick one):
  - (a) Add `PerlRuntime rt` parameter to `findMethodInHierarchy()` and its
    internal methods — cleaner but changes method signatures
  - (b) Cache `PerlRuntime rt` as a local at the top of `findMethodInHierarchy()`
    and replace each `getXxxCache()` call with direct `rt.xxxCache` access —
    fewer signature changes but less encapsulated
- **Expected impact on method benchmark:** high (this is the main cache-miss path)
- Benchmark after this step

**Step 1c: Other `GlobalVariable` accessors**
- Same file as 1a
- Apply the same pattern to `getGlobalVariable()`, `getGlobalArray()`,
  `getGlobalHash()`, `existsGlobalCodeRef()`, `resolveStashAlias()`
- Each saves 1 lookup per call; these are called pervasively
- **Expected impact:** small per-method, cumulative across all benchmarks
- Benchmark all 4 benchmarks after this step

After Tier 1, re-run all benchmarks and record results. If closure and method
are within 10% of master, the optimization work is done. If not, proceed to
Tier 2.

---

**Tier 2: Consolidate WarningBits/HintHash stacks into PerlRuntime (MEDIUM RISK)**

This tier targets the **closure** regression specifically. The closure call
path has zero `PerlRuntime.current()` lookups (Tier 1 will not help it) but
14-17 ThreadLocal lookups from 7 separate ThreadLocal stacks:

| ThreadLocal | Class | push/pop per call |
|-------------|-------|-------------------|
| `currentBitsStack` | WarningBitsRegistry | 2 (push + pop) |
| `callerBitsStack` | WarningBitsRegistry | 2 |
| `callerHintsStack` | WarningBitsRegistry | 2 |
| `callSiteBits` | WarningBitsRegistry | 1 (get) |
| `callSiteHints` | WarningBitsRegistry | 1 (get) |
| `callSiteSnapshotId` | HintHashRegistry | 2 (get + set) |
| `callerSnapshotIdStack` | HintHashRegistry | 2 |
| `argsStack` | RuntimeCode | 2 |

**Approach:** Migrate these 8 ThreadLocal stacks into `PerlRuntime` instance
fields, following the same accessor-method pattern used for all other
migrated stacks (see "Local Save/Restore Stack Fix" section above). This
turns 14-17 ThreadLocal lookups into 1 (`PerlRuntime.current()` at method
entry, then direct field access).

Concrete steps:
1. Add 8 stack fields to `PerlRuntime.java`
2. Add `private static` accessor methods in each source class that delegate
   to `PerlRuntime.current().<field>` (same pattern as `localizedStack()` etc.)
3. Replace `threadLocalField.get()` with the accessor call in each push/pop site
4. Remove the ThreadLocal field declarations from WarningBitsRegistry,
   HintHashRegistry, and RuntimeCode
5. Verify: `make` passes, then benchmark closure

**Expected impact on closure benchmark:** high — 14 fewer ThreadLocal lookups
per call. This is the dominant cost in the closure path.

**Revert criteria:** If closure benchmark does not improve by at least 15%
(i.e., does not recover at least half the 34% regression), revert unless the
migration is considered architecturally desirable for consistency with the
other stack migrations.

---

**Tier 3: Investigate inline method cache effectiveness (LOW RISK)**

Only pursue this if method dispatch is still >10% slower after Tier 1.

Each `PerlRuntime` starts with empty inline method cache arrays
(`inlineCacheBlessId`, `inlineCacheMethodHash`, `inlineCacheCode`). The
cache is indexed by `callsiteId % CACHE_SIZE`, so it relies on a warm
steady state.

**Diagnostic step (before any code change):**
```bash
# Add temporary counters to callCached() to measure hit/miss ratio:
# - Count cache hits (blessId matches AND methodHash matches)
# - Count cache misses
# Run benchmark_method.pl and report hit rate
```

If hit rate is >95%, the cache is working and the regression is from the
single `PerlRuntime.current()` lookup per call (unavoidable overhead of ~2-5ns).
If hit rate is low, investigate why — possible causes:
- `callsiteId` collisions across runtimes (IDs are global AtomicIntegers)
- Cache size too small for the workload
- BlessId instability across runtime initialization

---

**Failed Optimization Attempts**

(Document failed attempts here so future engineers know what was already tried.
For each attempt, record: what was changed, benchmark numbers before/after,
and why it was reverted.)

*None yet.*

### Open Questions
- `runtimeEvalCounter` and `nextCallsiteId` remain static (shared across runtimes) —
  acceptable for unique ID generation but may want per-runtime counters in future
- Should `ByteCodeSourceMapper` collections be migrated to per-runtime long-term?
  (Currently they're only needed during compilation, so the global lock is sufficient)

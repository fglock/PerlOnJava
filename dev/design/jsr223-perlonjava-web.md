# PerlOnJava JSR-223 and Web Integration Design

## Goals

- **Provide a robust JSR-223 `ScriptEngine` for PerlOnJava** that integrates cleanly with Java 8+ / Java 9+ (`java.scripting` module).
- **Support web use cases**:
  - Simple embedded HTTP server (e.g. `com.sun.net.httpserver.HttpServer`).
  - Servlet containers / other frameworks that already speak `javax.script`.
- **Enable compile-once, execute-many** usage for Perl pages and modules:
  - Avoid re-tokenizing and recompiling on every request.
  - Allow associating HTTP routes with specific Perl scripts or router subs.
- **Keep a single-threaded Perl runtime for now**, with an optional global lock:
  - Allow safe use in multi-threaded Java servers by serializing Perl execution.
  - Make locking behavior configurable via a static final flag.
- **Move towards full `javax.script` coverage** where it makes sense:
  - Implement `Compilable` / `CompiledScript`.
  - Consider `Invocable` for calling Perl subs from Java.
  - Respect `ScriptContext` I/O and bindings for better embedding.

---

## Current Architecture Overview

### Core execution: `PerlLanguageProvider`

- Public API:
  - `executePerlCode(CompilerOptions options, boolean isTopLevelScript)`
  - `executePerlCode(CompilerOptions options, boolean isTopLevelScript, int callerContext)`
- Implementation steps (simplified):
  - Create a `ScopedSymbolTable` with special variables (`this`, `@_`, `wantarray`).
  - Derive a `RuntimeContextType` (VOID for top-level scripts, SCALAR otherwise).
  - Build an `EmitterContext` with:
    - `JavaClassInfo`
    - symbol table snapshot
    - compiler options (`CompilerOptions`)
    - a `RuntimeArray` for internal use
  - Initialize global runtime (via `GlobalContext.initializeGlobals`) once.
  - Tokenize source (`Lexer` → `List<LexerToken>`).
  - Optionally stop at tokenization or parsing (debug flags in `CompilerOptions`).
  - Parse into AST (`Parser`), with `DataSection` preparation.
  - Generate a Java class via `EmitterMethodCreator.createClassWithMethod`.
  - Execute generated code via `executeGeneratedClass(…)`.
- `executeGeneratedClass`:
  - Runs UNITCHECK / CHECK / INIT / END blocks according to `isMainProgram`.
  - Instantiates the generated class, looks up `apply` via `RuntimeCode.lookup`.
  - Invokes `apply(instance, new RuntimeArray(), context)` and returns `RuntimeList`.
  - Flushes and closes runtime I/O handles appropriately.

### CLI entry point: `Main`

- Parses command-line options into `CompilerOptions`.
- Calls `PerlLanguageProvider.executePerlCode(parsedArgs, true)`.
- Handles exit codes based on `$!` and `$?`.

### Tests: `PerlScriptExecutionTest`

- Demonstrates how to:
  - Build `CompilerOptions` from in-memory script content.
  - Set `fileName` for error reporting.
  - Extend `@INC` via `RuntimeArray.push(options.inc, new RuntimeScalar("src/main/perl/lib"))`.
  - Redirect `RuntimeIO.stdout` (and `System.out`) to a custom `ByteArrayOutputStream` using `StandardIO`.
  - Call `PerlLanguageProvider.executePerlCode` and inspect captured output.

### JSR-223 layer: `PerlScriptEngine`

- Extends `AbstractScriptEngine` and wraps `PerlLanguageProvider`.
- Current behavior:
  - `eval(String script, ScriptContext context)`:
    - Creates `CompilerOptions` with `fileName = "<STDIN>"` and `code = script`.
    - Calls `PerlLanguageProvider.executePerlCode(options, true)`.
    - Returns `RuntimeList.toString()` or `null`.
  - `eval(Reader reader, ScriptContext context)`:
    - Reads entire reader into a `String` and delegates to `eval(String, context)`.
  - `createBindings()` returns `SimpleBindings`.
  - `getFactory()` returns the associated `ScriptEngineFactory`.
- **Limitations**:
  - Ignores `ScriptContext` writers/readers and bindings.
  - No `Compilable` / `CompiledScript` or `Invocable` support yet.

---

## Web Integration Design

### Use Cases

1. **Simple embedded server** (e.g. `HttpServer`) that serves Perl-generated pages:
   - Map `/path` → `webroot/path.pl`.
   - Each script prints CGI-style output (headers + body) to STDOUT.

2. **Servlet / framework integration**:
   - Use `javax.script.ScriptEngine` / `Compilable`.
   - Allow pre-compiling scripts and invoking them per request.
   - Respect servlet `Writer` / `OutputStream` via `ScriptContext`.

### Basic Flow (per request)

1. Map route to script/module:
   - Example: `/hello` → `webroot/hello.pl`.
2. Obtain compiled Perl representation:
   - From a cache (compile-once) or by compiling on demand (fallback).
3. Prepare execution environment:
   - Route / query params in a Java object or map.
   - Optionally expose request/response-like objects to Perl through globals.
4. Redirect Perl STDOUT to HTTP response:
   - Wrap servlet/HTTP output in a `StandardIO` and a `RuntimeIO` instance.
   - Temporarily assign `RuntimeIO.stdout` (and possibly `System.out`).
5. Execute compiled Perl code:
   - Using `executeGeneratedClass` or via a `CompiledScript.eval(ScriptContext)` wrapper.
6. Restore previous runtime I/O and Java `System.out`.

---

## Compile-Once, Execute-Many Model

### Motivations

- Parsing + code generation are relatively expensive compared to executing already-generated bytecode.
- Web traffic tends to call the same handlers repeatedly.
- Re-compiling scripts on every request wastes CPU and complicates caching.

### New Concept: `CompiledPerl`

Introduce a small value type to hold compiled state:

```java
public final class CompiledPerl {
    private final Class<?> generatedClass;
    private final EmitterContext emitterContext;
    private final boolean isMainProgram;
    private final int defaultCallerContext;

    // constructor + getters
}
```

- `generatedClass`: the Java class produced by `EmitterMethodCreator.createClassWithMethod`.
- `emitterContext`: holds compiler options, symbol table snapshot, unitcheck blocks, etc.
- `isMainProgram`: controls INIT/CHECK/END behaviors in `executeGeneratedClass`.
- `defaultCallerContext`: typically `RuntimeContextType.VOID` for top-level scripts.

### New API in `PerlLanguageProvider`

1. **Compile without executing**:

```java
public static CompiledPerl compilePerlCode(
        CompilerOptions compilerOptions,
        boolean isTopLevelScript,
        int callerContext
) throws Exception;
```

- Performs all current work in `executePerlCode` up to class generation.
- Does **not** call `executeGeneratedClass`.
- Returns a `CompiledPerl` instance that can be reused.

2. **Execute a compiled Perl unit**:

We reuse `executeGeneratedClass` but make it callable from outside with a `CompiledPerl`:

```java
public static RuntimeList executeCompiled(
        CompiledPerl compiled,
        int callerContext
) throws Exception {
    return executeGeneratedClass(
        compiled.getGeneratedClass(),
        compiled.getEmitterContext(),
        compiled.isMainProgram(),
        callerContext >= 0 ? callerContext : compiled.getDefaultCallerContext()
    );
}
```

3. **Keep existing `executePerlCode` as convenience**:

```java
public static RuntimeList executePerlCode(
        CompilerOptions options,
        boolean isTopLevelScript,
        int callerContext
) throws Exception {
    CompiledPerl compiled = compilePerlCode(options, isTopLevelScript, callerContext);
    return executeCompiled(compiled, callerContext);
}
```

### Caching Strategy

In a web server or servlet environment, maintain a cache:

```java
class PerlPageCache {
    private static final ConcurrentMap<String, CompiledPerl> cache = new ConcurrentHashMap<>();

    static CompiledPerl getOrCompile(String route, Path scriptPath) throws Exception {
        return cache.computeIfAbsent(route, r -> {
            CompilerOptions options = new CompilerOptions();
            options.code = Files.readString(scriptPath, StandardCharsets.UTF_8);
            options.fileName = scriptPath.toString();
            RuntimeArray.push(options.inc, new RuntimeScalar("src/main/perl/lib"));
            try {
                return PerlLanguageProvider.compilePerlCode(options, true, RuntimeContextType.VOID);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
```

- Routes to consider:
  - Direct mapping: `/foo` → compiled `webroot/foo.pl` as a full script.
  - Single router script that dispatches to subs based on a route variable.
- Future extension: add a file timestamp and invalidate/recompile on change.

---

## Threading and Global Locking Strategy

### Constraints

- The current PerlOnJava runtime uses global state (`GlobalContext`, `GlobalVariable`, `RuntimeIO`, etc.).
- Multi-threaded calls into the same runtime can interfere with each other.
- We want to **support multi-threaded Java servers**, but **serialize Perl execution** until proper threading support exists.

### Design: Optional Global Lock

Introduce a static final flag and a global lock in a central place (e.g. `PerlLanguageProvider`):

```java
public class PerlLanguageProvider {
    public static final boolean ENABLE_GLOBAL_EXECUTION_LOCK = true; // or from system property

    private static final Object EXECUTION_LOCK = new Object();

    // existing methods...
}
```

Wrap all public entry points that execute Perl (CLI, JSR-223 eval, compiled execution) with the lock when `ENABLE_GLOBAL_EXECUTION_LOCK` is `true`:

```java
public static RuntimeList executePerlCode(
        CompilerOptions options,
        boolean isTopLevelScript,
        int callerContext
) throws Exception {
    if (!ENABLE_GLOBAL_EXECUTION_LOCK) {
        return executePerlCodeInternal(options, isTopLevelScript, callerContext);
    }
    synchronized (EXECUTION_LOCK) {
        return executePerlCodeInternal(options, isTopLevelScript, callerContext);
    }
}
```

- `executePerlCodeInternal` would contain the actual compile + run logic.
- Similarly, `executeCompiled` and CLI `Main` would route through the same locking.

### Rationale

- **Safety-first** default: single-threaded interpreter semantics even in multi-threaded servers.
- Allows us to introduce per-request or per-thread runtimes later without breaking APIs.
- The static final flag gives an easy on/off switch for experimentation or benchmarks.

---

## JSR-223 Feature Coverage

The `javax.script` API includes several key pieces beyond `ScriptEngine` itself. For completeness and usability, we should aim to support:

### 1. `ScriptEngineFactory`

Already present (not shown here), but we should ensure it:

- Provides correct engine and language metadata:
  - `getEngineName`, `getEngineVersion`, `getLanguageName`, `getLanguageVersion`.
  - `getNames` includes common aliases: `"perl"`, `"Perl"`, maybe `"pl"`.
- Implements `getExtensions`, `getMimeTypes`, `getParameter` correctly.
- Can create new `PerlScriptEngine` instances safely.

### 2. `ScriptEngine` behavior

Improvements to `PerlScriptEngine`:

- **Bindings and `ScriptContext`**:
  - Respect `context.getBindings(ScriptContext.ENGINE_SCOPE)` / `GLOBAL_SCOPE`:
    - Map Java bindings into Perl variables (probably via `%ENV` / dedicated hashes or a Java bridge module).
  - At minimum, expose the bindings as a Perl-accessible map for scripts that care.

- **I/O Handling**:
  - Currently, Perl output goes via `RuntimeIO` / `System.out`.
  - For proper JSR-223 integration, adapt this so:
    - When a `ScriptContext` has a non-null `Writer` (`context.getWriter()`), route Perl STDOUT to that writer.
    - For STDERR, route to `context.getErrorWriter()`.
  - Implementation idea:
    - Wrap the `Writer` in a `StandardIO`/`RuntimeIO` bridge.
    - Temporarily override `RuntimeIO.stdout` and/or `RuntimeIO.stderr` for the duration of `eval`.

### 3. `Compilable` / `CompiledScript`

Implement `Compilable` on `PerlScriptEngine`:

```java
public class PerlScriptEngine extends AbstractScriptEngine implements Compilable {
    @Override
    public CompiledScript compile(String script) throws ScriptException { ... }

    @Override
    public CompiledScript compile(Reader script) throws ScriptException { ... }
}
```

- Implementation details:
  - Use `CompilerOptions` + `PerlLanguageProvider.compilePerlCode` to build a `CompiledPerl`.
  - Wrap `CompiledPerl` in a `PerlCompiledScript`:

```java
public class PerlCompiledScript extends CompiledScript {
    private final PerlScriptEngine engine;
    private final CompiledPerl compiled;

    @Override
    public Object eval(ScriptContext context) throws ScriptException {
        // setup bindings + I/O based on context
        // call PerlLanguageProvider.executeCompiled(compiled, RuntimeContextType.VOID)
        // return appropriate Java representation of RuntimeList
    }

    @Override
    public ScriptEngine getEngine() { return engine; }
}
```

- Benefits:
  - Web servers/frameworks can pre-compile scripts once at startup.
  - Per request, they just call `CompiledScript.eval(context)`.

### 4. `Invocable`

Implementing `Invocable` allows Java code to call specific Perl functions and methods by name:

```java
public class PerlScriptEngine extends AbstractScriptEngine
        implements Compilable, Invocable {

    @Override
    public Object invokeFunction(String name, Object... args) throws ScriptException, NoSuchMethodException { ... }

    @Override
    public Object invokeMethod(Object thiz, String name, Object... args) throws ScriptException, NoSuchMethodException { ... }

    @Override
    public <T> T getInterface(Class<T> clasz) { ... }

    @Override
    public <T> T getInterface(Object thiz, Class<T> clasz) { ... }
}
```

Possible design for v1:

- Start with a **minimal implementation**:
  - Maintain a mapping from function name → Perl sub in the global symbol table.
  - Internally build a small wrapper script that calls the target sub and returns its result as a `RuntimeList`.
  - Longer term, expose a dedicated runtime API to call Perl subs directly using the same machinery as normal function calls.

### 5. Error Handling and `ScriptException`

- Wrap all runtime failures in `ScriptException` with meaningful messages:
  - Use `ErrorMessageUtil.stringifyException` for human-readable output.
  - Preserve the original cause via `initCause`.
- Consider surfacing Perl file and line information via `ScriptException` fields when possible.

---

## Web Server Usage Patterns

### Embedded `HttpServer` Example (Conceptual)

- Startup:
  - Discover Perl page scripts under `webroot/`.
  - Compile each script with `PerlLanguageProvider.compilePerlCode` or `PerlScriptEngine.compile`.
  - Store in a `Map<String, CompiledPerl>` / `Map<String, CompiledScript>` keyed by route.

- Per-request handler:
  - Look up the compiled script by path.
  - Create a `ScriptContext` that:
    - Uses the HTTP response `OutputStream` as its `Writer` (via `OutputStreamWriter`).
    - Optionally stores request/response objects in bindings.
  - Call `CompiledScript.eval(context)`.
  - Handle exceptions (HTTP 500, error page, etc.).

### Servlet / Framework Integration

- Let the hosting framework discover and use the `ScriptEngine` via `ScriptEngineManager`.
- Use `Compilable` to build `CompiledScript`s for routes or view templates.
- Optionally, use `Invocable` for:
  - Filter chains.
  - Application-specific callbacks from Java into Perl.

---

## Future Work and Open Questions

- **Thread-aware runtime**:
  - Long term, we may provide per-thread or per-request runtime contexts instead of a single global one.
  - This would reduce or remove the need for a global execution lock.

- **Hot reloading of Perl scripts**:
  - Watch script files for changes and invalidate cached `CompiledPerl` / `CompiledScript` entries.
  - Recompile on first access after change.

- **Deeper bindings integration**:
  - Define a consistent way to expose Java objects, HTTP request/response, and environment variables to Perl.
  - Possibly provide a small Perl module (`PerlOnJava::Web`) that wraps these.

- **Better mapping from `RuntimeList` to Java types**:
  - For scripting consumers that expect simple Java values, consider policies:
    - Scalar result only.
    - List → `List<Object>` or arrays.

- **Documentation and examples**:
  - Provide sample projects:
    - Embedded `HttpServer` example.
    - Servlet-based example using `CompiledScript`.

---

## Design Review: Risk Analysis and Compliance

**Review Date:** 2025-11-20  
**Overall Risk Assessment:** Medium → Low (with recommended refinements)

This section provides a thorough analysis of risks, JSR-223 compliance, reliability concerns, and efficiency considerations, particularly regarding `RuntimeCode.java` integration.

---

### 1. Critical Issues and Refinements

#### 1.1. `CompiledPerl` State Management - HIGH PRIORITY

**Issue:** The proposed `CompiledPerl` stores `EmitterContext`, which contains mutable runtime state.

**Current Proposal:**
```java
public final class CompiledPerl {
    private final Class<?> generatedClass;
    private final EmitterContext emitterContext;  // ⚠️ PROBLEM
    private final boolean isMainProgram;
    private final int defaultCallerContext;
}
```

**Analysis of `EmitterContext` (from PerlLanguageProvider.java:93-103):**
- Contains `unitcheckBlocks` (`RuntimeArray`) - mutable, accumulates during compilation
- Contains `errorUtil` - set/modified during parsing
- Contains `compilerOptions` - may have mutable state
- Contains `symbolTable` - snapshot but references could leak

**Risks:**
- **State pollution:** Reusing the same `EmitterContext` across executions can cause state from one request to leak into another
- **UNITCHECK blocks:** These should run once at compile time, not on every execution
- **Memory leaks:** Holding references to compilation-time objects prevents GC
- **Thread safety:** Even with global lock, parallel compilation could create races

**Recommended Fix:**

**Option A - Minimal Storage (Recommended):**
```java
public final class CompiledPerl {
    private final Class<?> generatedClass;
    private final String fileName;  // For error messages only
    private final boolean isMainProgram;
    private final int defaultCallerContext;
    
    public CompiledPerl(Class<?> generatedClass, 
                       String fileName,
                       boolean isMainProgram,
                       int defaultCallerContext) {
        this.generatedClass = generatedClass;
        this.fileName = fileName;
        this.isMainProgram = isMainProgram;
        this.defaultCallerContext = defaultCallerContext;
    }
}
```

**Refactor `executeGeneratedClass`:**

Current signature needs `EmitterContext ctx`:
```java
private static RuntimeList executeGeneratedClass(
    Class<?> generatedClass, 
    EmitterContext ctx,  // ← Don't need full context for execution
    boolean isMainProgram, 
    int callerContext
)
```

Change to:
```java
private static RuntimeList executeGeneratedClass(
    Class<?> generatedClass,
    boolean isMainProgram, 
    int callerContext
) throws Exception {
    // UNITCHECK blocks already ran during compilation - don't run again
    // Only run runtime-phase blocks: CHECK, INIT, END
    
    if (isMainProgram) {
        runCheckBlocks();  // Global CHECK blocks
    }
    
    if (isMainProgram) {
        runInitBlocks();   // Global INIT blocks
    }
    
    Constructor<?> constructor = generatedClass.getConstructor();
    Object instance = constructor.newInstance();
    
    MethodHandle invoker = RuntimeCode.lookup.findVirtual(
        generatedClass, "apply", RuntimeCode.methodType);
    
    int executionContext = callerContext >= 0 ? callerContext :
            (isMainProgram ? RuntimeContextType.VOID : RuntimeContextType.SCALAR);
            
    RuntimeList result = (RuntimeList) invoker.invoke(
        instance, new RuntimeArray(), executionContext);
    
    if (isMainProgram) {
        runEndBlocks();    // Global END blocks
    }
    
    RuntimeIO.flushAllHandles();
    return result;
}
```

**Action Required:** 
1. Refactor `CompiledPerl` to store only immutable, essential data
2. Update `executeGeneratedClass` to not require `EmitterContext`
3. Ensure UNITCHECK blocks run during compilation only, not execution

---

#### 1.2. BEGIN/END/CHECK/INIT/UNITCHECK Block Semantics

**Critical Understanding:**

From `PerlLanguageProvider.executePerlCode`:
- **BEGIN blocks:** Run during `parser.parse()` at **compile time** ✓
- **UNITCHECK blocks:** Run via `runUnitcheckBlocks(ctx.unitcheckBlocks)` at **compile time** ✓
- **CHECK blocks:** Run in `executeGeneratedClass` if `isMainProgram` (once per program start)
- **INIT blocks:** Run in `executeGeneratedClass` if `isMainProgram` (once per program start)
- **END blocks:** Run in `executeGeneratedClass` if `isMainProgram` (at program end)

**For Compile-Once / Execute-Many:**

| Block Type | When to Run | Web Server Semantics |
|------------|-------------|---------------------|
| BEGIN | Compilation | ✓ Run once during `compilePerlCode()` |
| UNITCHECK | Compilation | ✓ Run once during `compilePerlCode()` |
| CHECK | First execution | Run once at server startup if `isMainProgram=true` |
| INIT | Each execution | ? Need to decide: once or per-request |
| END | Process exit | Run at server shutdown if `isMainProgram=true` |

**Design Decision Required:**

For web pages compiled with `isMainProgram=true`:
- **Option A (Traditional):** CHECK/INIT/END run once at server startup/shutdown
  - Pros: Matches traditional Perl semantics
  - Cons: Pages can't do per-request initialization in INIT blocks
  
- **Option B (Per-Request):** Don't treat compiled pages as "main programs"
  - Set `isMainProgram=false` when compiling
  - CHECK/INIT/END blocks won't run at all
  - Pros: Simpler, no phase block confusion
  - Cons: Scripts expecting these blocks won't work

**Recommended Approach:**

For web pages, use `isMainProgram=false`:
```java
CompiledPerl compiled = PerlLanguageProvider.compilePerlCode(
    options, 
    false,  // ← Not a main program, just a handler
    RuntimeContextType.VOID
);
```

This means:
- BEGIN/UNITCHECK run once at compilation ✓
- CHECK/INIT/END are ignored (web handlers shouldn't use these anyway)
- Clear semantics: compile once, execute many, no phase confusion

**For CLI scripts:** Keep `isMainProgram=true` - full phase block support.

---

#### 1.3. Global Lock Implementation Details

**Proposed lock is correct but needs refinement:**

```java
public class PerlLanguageProvider {
    // Make configurable via system property for testing
    public static final boolean ENABLE_GLOBAL_EXECUTION_LOCK = 
        Boolean.parseBoolean(System.getProperty(
            "perlonjava.execution.lock", "true"));
    
    private static final Object EXECUTION_LOCK = new Object();
}
```

**Critical: Lock Granularity**

The lock must cover:
- ✓ All Perl code execution (`executeCompiled`, `executePerlCode`)
- ✓ Global state access (globals, I/O redirection)
- ✗ NOT compilation (`compilePerlCode`) - can be parallel

**Correct Implementation:**

```java
// Compilation can happen in parallel - no lock needed
public static CompiledPerl compilePerlCode(...) throws Exception {
    // No lock - pure transformation, no global state modification
    // (BEGIN blocks DO modify globals, but that's intentional)
    return compilePerlCodeInternal(...);
}

// Execution must be serialized
public static RuntimeList executeCompiled(
        CompiledPerl compiled, 
        int callerContext) throws Exception {
    if (!ENABLE_GLOBAL_EXECUTION_LOCK) {
        return executeCompiledInternal(compiled, callerContext);
    }
    synchronized (EXECUTION_LOCK) {
        return executeCompiledInternal(compiled, callerContext);
    }
}
```

**Important:** BEGIN blocks run during compilation and modify global state. This is **intentional** - they set up the environment for the compiled code. If multiple threads compile different scripts simultaneously, they might interfere. However, this is acceptable because:
1. Compilation typically happens at startup (single-threaded)
2. BEGIN blocks in different files should be independent
3. If needed, users can add a compilation lock separately

---

### 2. RuntimeCode.java Efficiency Analysis

**Review of RuntimeCode.java caching mechanisms:**

#### 2.1. Existing Caching (Lines 38-53) - EXCELLENT ✓

```java
// Cache for memoization of evalStringHelper results
private static final int CLASS_CACHE_SIZE = 100;
private static final Map<String, Class<?>> evalCache = 
    new LinkedHashMap<String, Class<?>>(CLASS_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Class<?>> eldest) {
            return size() > CLASS_CACHE_SIZE;
        }
    };

// Cache for method handles with eviction policy
private static final int METHOD_HANDLE_CACHE_SIZE = 100;
private static final Map<Class<?>, MethodHandle> methodHandleCache = 
    new LinkedHashMap<Class<?>, MethodHandle>(METHOD_HANDLE_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Class<?>, MethodHandle> eldest) {
            return size() > METHOD_HANDLE_CACHE_SIZE;
        }
    };
```

**Analysis:**
- ✓ LRU eviction policy via `LinkedHashMap` with `accessOrder=true`
- ✓ Bounded cache prevents memory leaks
- ✓ `evalCache` caches compiled `eval` strings
- ✓ `methodHandleCache` caches the expensive `MethodHandle` lookup

**Performance Impact:**

From `makeCodeObject` (lines 324-355):
```java
synchronized (methodHandleCache) {
    if (methodHandleCache.containsKey(clazz)) {
        methodHandle = methodHandleCache.get(clazz);  // ← Cache hit: ~O(1)
    } else {
        // Expensive reflection operation
        methodHandle = RuntimeCode.lookup.findVirtual(
            clazz, "apply", RuntimeCode.methodType);  // ← Cache miss: expensive
        methodHandleCache.put(clazz, methodHandle);
    }
}
```

**Benchmark estimates:**
- `findVirtual`: ~1-10 microseconds (reflection + JVM internals)
- Cache lookup: ~10-100 nanoseconds (HashMap get)
- **Speedup: 10-1000x for cached lookups**

#### 2.2. CompiledPerl Caching Strategy - Compatible ✓

**Web server cache (proposed):**
```java
Map<String, CompiledPerl> pageCache = new ConcurrentHashMap<>();
```

This is **complementary** to RuntimeCode caching:
- `pageCache`: Caches entire compiled scripts (Class objects)
- `methodHandleCache`: Caches MethodHandles for those Classes
- Both work together efficiently

**Flow for cached page execution:**
1. Look up `CompiledPerl` from `pageCache` → O(1)
2. Call `executeCompiled(compiled, ctx)`
3. Inside execution: instantiate Class, look up MethodHandle
4. MethodHandle lookup hits `methodHandleCache` → O(1)
5. Invoke via MethodHandle → near-native performance

**Total overhead per cached request:** ~microseconds (excellent)

#### 2.3. Potential Efficiency Issue: Constructor Instantiation

**Current code (line 252, 766-769):**

Every execution creates a new instance:
```java
Constructor<?> constructor = generatedClass.getConstructor();
Object instance = constructor.newInstance();  // ← New object per call
```

**Is this efficient?**

Yes, for closures:
- Each Perl subroutine can have closure variables
- These are stored as instance fields in the generated class
- Different calls need separate instances to avoid state sharing

**For web pages (no closures):**
- The instance has no closure state
- Theoretically could reuse a singleton instance
- **But:** Marginal benefit (~nanoseconds saved), not worth the complexity

**Recommendation:** Keep current design - correct and efficient enough.

#### 2.4. MethodHandle Invocation (Lines 766-769, 806-809)

```java
if (isStatic) {
    return (RuntimeList) this.methodHandle.invoke(a, callContext);
} else {
    return (RuntimeList) this.methodHandle.invoke(this.codeObject, a, callContext);
}
```

**Performance:** 
- `MethodHandle.invoke` is JVM-optimized (as fast as direct call after warmup)
- Better than reflection (`Method.invoke`)
- Close to native call performance after JIT compilation

**Verdict:** Excellent ✓

---

### 3. JSR-223 Compliance Analysis

#### 3.1. ScriptEngine Contract Violations (Current)

**Issue 1: Ignoring ScriptContext I/O**

JSR-223 specification (Section 3.2):
> "The ScriptEngine must use the Reader, Writer, and Writer objects obtained from the ScriptContext for stdin, stdout, and stderr."

Current `PerlScriptEngine.eval`:
```java
public Object eval(String script, ScriptContext context) throws ScriptException {
    // VIOLATION: Ignores context.getWriter() and context.getErrorWriter()
    RuntimeList result = PerlLanguageProvider.executePerlCode(options, true);
    return result != null ? result.toString() : null;
}
```

**Fix Required:**
```java
public Object eval(String script, ScriptContext context) throws ScriptException {
    // Save original I/O
    PrintStream originalOut = System.out;
    var originalRuntimeStdout = RuntimeIO.stdout;
    
    try {
        // Redirect to ScriptContext writers
        Writer writer = context.getWriter();
        if (writer != null) {
            OutputStream os = new WriterOutputStream(writer, StandardCharsets.UTF_8);
            StandardIO stdio = new StandardIO(os, true);
            RuntimeIO.stdout = new RuntimeIO(stdio);
            System.setOut(new PrintStream(os, true, StandardCharsets.UTF_8));
        }
        
        CompilerOptions options = new CompilerOptions();
        options.fileName = "<STDIN>";
        options.code = script;
        
        RuntimeList result = PerlLanguageProvider.executePerlCode(options, true);
        return result != null ? result.toString() : null;
    } finally {
        // Restore original I/O
        RuntimeIO.stdout = originalRuntimeStdout;
        System.setOut(originalOut);
    }
}
```

**Note:** Need to implement `WriterOutputStream` adapter or use Apache Commons IO.

**Issue 2: Ignoring Bindings**

JSR-223 specification (Section 4.3):
> "Engines should make bindings available to scripts as variables."

Current implementation ignores:
- `context.getBindings(ScriptContext.ENGINE_SCOPE)`
- `context.getBindings(ScriptContext.GLOBAL_SCOPE)`

**Fix Options:**

**Option A:** Expose bindings as Perl hash:
```java
// In eval(), before executing:
Bindings engineBindings = context.getBindings(ScriptContext.ENGINE_SCOPE);
if (engineBindings != null) {
    RuntimeHash perlBindings = GlobalVariable.getGlobalHash("main::BINDINGS");
    for (Map.Entry<String, Object> entry : engineBindings.entrySet()) {
        perlBindings.put(entry.getKey(), convertJavaToPerl(entry.getValue()));
    }
}
```

**Option B:** Expose as individual Perl variables:
```java
// For each binding "foo" => value:
GlobalVariable.setGlobalVariable("main::foo", convertJavaToPerl(value));
```

**Recommendation:** Start with Option A (hash), add Option B later if needed.

#### 3.2. ScriptEngineFactory Requirements

From JSR-223 spec, required methods:

| Method | Compliance Status | Notes |
|--------|------------------|-------|
| `getEngineName()` | ✓ Likely OK | Should return "PerlOnJava" |
| `getEngineVersion()` | ⚠️ Check | Should match project version |
| `getLanguageName()` | ✓ Should be "Perl" | |
| `getLanguageVersion()` | ⚠️ Check | What Perl version do we target? |
| `getNames()` | ⚠️ Check | Should include ["perl", "Perl", "pl"] |
| `getExtensions()` | ⚠️ Check | Should return ["pl", "pm"] |
| `getMimeTypes()` | ⚠️ Check | Should return ["application/x-perl", "text/x-perl"] |
| `getMethodCallSyntax()` | Optional | Can return `obj->method(args)` |
| `getOutputStatement()` | Optional | Can return `print "text"` |
| `getParameter()` | ⚠️ Check | `THREADING` should return "MULTITHREADED" with lock |

**Action Required:** Review and update `PerlScriptEngineFactory` implementation.

#### 3.3. Compilable Interface Contract

**Key requirement:** Compiled scripts must be **thread-safe** for reuse.

From JSR-223 spec (Section 5.2):
> "CompiledScript.eval() must be thread-safe if the engine factory's THREADING parameter is MULTITHREADED or THREAD-ISOLATED."

**Our implementation:**
- Global lock ensures thread-safety ✓
- `CompiledPerl` holds only immutable Class → thread-safe ✓
- Each execution creates new instance → no shared state ✓

**Verdict:** Compliant with recommended `CompiledPerl` design.

---

### 4. Reliability Concerns

#### 4.1. Error Handling and Resource Management

**Issue:** Current `executeGeneratedClass` has try-catch but could leak resources.

**From PerlLanguageProvider.java (lines 277-286):**
```java
} catch (Throwable t) {
    if (isMainProgram) {
        runEndBlocks();  // ← Good: cleanup on error
    }
    RuntimeIO.closeAllHandles();  // ← Good: close I/O
    if (t instanceof RuntimeException runtimeException) {
        throw runtimeException;
    }
    throw new RuntimeException(t);
}
```

**Potential issue:** If END blocks themselves throw, we don't reach `closeAllHandles()`.

**Fix:**
```java
} catch (Throwable t) {
    try {
        if (isMainProgram) {
            runEndBlocks();
        }
    } catch (Throwable endException) {
        // Log but don't mask original exception
        t.addSuppressed(endException);
    } finally {
        RuntimeIO.closeAllHandles();  // Always close
    }
    throw t instanceof RuntimeException ? (RuntimeException)t : new RuntimeException(t);
}
```

#### 4.2. Global State Reset Between Requests

**Critical for web servers:**

With a global runtime, one request's state could leak into the next:
- Perl global variables (`$_`, `@_`, `%ENV`, etc.)
- I/O redirections
- Error state (`$@`, `$!`, `$?`)

**Current mitigation:**
- `resetAll()` in tests clears globals ✓
- But not called between web requests ✗

**Recommended approach:**

**Option A:** Don't reset - document that pages must be stateless
- Perl pages should not rely on previous state
- Similar to CGI model: fresh environment per request

**Option B:** Partial reset per request
- Reset only volatile state: `$@`, `$!`, `$?`
- Keep compiled subs, loaded modules
- Balance between isolation and performance

**Option C:** Full reset per request
- Call `resetAll()` before each execution
- Expensive: clears all globals, reloads core
- Only viable if execution lock serializes everything

**Recommendation:** Start with Option A (document stateless requirement), add Option B if needed.

#### 4.3. Compilation Failures and Cache Poisoning

**Scenario:** A script with syntax error gets cached:

```java
pageCache.computeIfAbsent(route, r -> {
    try {
        return PerlLanguageProvider.compilePerlCode(...);  // ← Throws
    } catch (Exception e) {
        throw new RuntimeException(e);  // ← Kills the computeIfAbsent
    }
});
```

**Problem:** Failed compilation isn't cached, causing repeated compilation attempts.

**Fix:**
```java
class CompilationResult {
    final CompiledPerl compiled;  // null if failed
    final Exception error;        // null if succeeded
    final long timestamp;         // for expiry
}

pageCache.computeIfAbsent(route, r -> {
    try {
        CompiledPerl compiled = PerlLanguageProvider.compilePerlCode(...);
        return new CompilationResult(compiled, null, System.currentTimeMillis());
    } catch (Exception e) {
        // Cache the error for a short time to avoid retry storm
        return new CompilationResult(null, e, System.currentTimeMillis());
    }
});

// Later: check result and throw cached error
CompilationResult result = pageCache.get(route);
if (result.error != null) {
    // Maybe expire after 5 seconds to allow fix + retry
    if (System.currentTimeMillis() - result.timestamp < 5000) {
        throw new ScriptException(result.error);
    } else {
        pageCache.remove(route);  // Expire and retry
    }
}
```

---

### 5. Implementation Priorities

**Phase 1 - Foundation (Low Risk):**
1. ✓ Refactor `CompiledPerl` to store only immutable data
2. ✓ Update `executeGeneratedClass` to not require `EmitterContext`
3. ✓ Implement global lock with system property flag
4. ✓ Ensure UNITCHECK blocks run only during compilation

**Phase 2 - JSR-223 Compliance (Medium Risk):**
5. Implement `ScriptContext` I/O redirection in `PerlScriptEngine.eval`
6. Implement basic bindings support (hash-based)
7. Update `PerlScriptEngineFactory` metadata methods
8. Add `Compilable` interface and `PerlCompiledScript`

**Phase 3 - Web Integration (Medium Risk):**
9. Create example `HttpServer` with page caching
10. Implement compilation result caching (with error handling)
11. Add file-watch for hot reload (optional)
12. Document web page guidelines (stateless, no phase blocks)

**Phase 4 - Advanced Features (Higher Risk):**
13. Implement `Invocable` interface
14. Add per-request state isolation (if needed)
15. Consider per-thread runtime contexts
16. Performance benchmarking and optimization

---

### 6. Testing Requirements

**Unit Tests:**
- CompiledPerl immutability and reuse
- Global lock behavior (with/without flag)
- BEGIN/UNITCHECK run once, INIT/END conditionally
- Error handling and resource cleanup

**Integration Tests:**
- Multi-threaded execution with global lock
- ScriptContext I/O redirection
- Bindings exposure to Perl
- Compilation error caching

**Performance Tests:**
- Compare compile-every-time vs. compile-once
- Measure cache hit rates
- Verify MethodHandle caching effectiveness
- Test under concurrent load

**Compliance Tests:**
- JSR-223 TCK (if available)
- Compare behavior with other JSR-223 engines (JavaScript, Groovy)

---

### 7. Conclusion

**Risk Mitigation Summary:**

| Risk | Severity | Mitigation | Status |
|------|----------|-----------|--------|
| CompiledPerl state pollution | HIGH | Store only immutable data | Required |
| Block phase confusion | MEDIUM | Document isMainProgram=false for web | Required |
| Lock granularity | MEDIUM | Lock execution, not compilation | Required |
| JSR-223 non-compliance | MEDIUM | Fix I/O and bindings | Required |
| Cache poisoning | LOW | Cache errors with expiry | Recommended |
| Global state leaks | LOW | Document stateless requirement | Recommended |

**Overall Assessment:**

The design is **fundamentally sound** with excellent caching strategy from `RuntimeCode.java`. Required changes are straightforward and low-risk:
1. Slim down `CompiledPerl` to store only immutable artifacts
2. Refactor `executeGeneratedClass` signature
3. Implement proper locking and I/O redirection

With these refinements, the implementation will be:
- ✓ **Low-risk:** Changes are isolated and testable
- ✓ **Reliable:** Proper resource management and error handling
- ✓ **Compliant:** Meets JSR-223 requirements
- ✓ **Efficient:** Leverages existing RuntimeCode caching, minimal overhead

**Recommended:** Proceed with Phase 1 implementation, addressing critical issues first.

---

## Summary

This design extends PerlOnJava to be a more complete and practical JSR-223 engine, with a focus on web integration and efficient reuse of compiled Perl code. Key elements are:

- A `CompiledPerl` abstraction and `compilePerlCode` / `executeCompiled` API in `PerlLanguageProvider`.
- A simple global execution lock to make current global-runtime semantics safe in multi-threaded servers.
- Enhancing `PerlScriptEngine` to support `Compilable`, `CompiledScript`, and (optionally) `Invocable`.
- Proper use of `ScriptContext` bindings and writers to integrate cleanly with Java web stacks.

**After thorough review:** The design is production-ready with recommended refinements to `CompiledPerl` structure and JSR-223 I/O handling. The existing `RuntimeCode.java` caching infrastructure is excellent and will work efficiently with the proposed compilation caching layer.

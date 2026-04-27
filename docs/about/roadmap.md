# Roadmap

Future plans for PerlOnJava. See [Changelog](changelog.md) for release history
and [Feature Matrix](../reference/feature-matrix.md) for detailed implementation status.

## Table of Contents

1. [Guiding Principles](#guiding-principles)
2. [Recently Completed](#recently-completed)
3. [Active Development](#active-development)
4. [Objective 1: Language Correctness & Perl5 Alignment](#objective-1-language-correctness--perl5-alignment)
5. [Objective 2: Java Platform Alignment](#objective-2-java-platform-alignment)
6. [Objective 3: Ecosystem & Module Compatibility](#objective-3-ecosystem--module-compatibility)
7. [Objective 4: Performance & Optimization](#objective-4-performance--optimization)
8. [Objective 5: Developer Tooling](#objective-5-developer-tooling)
9. [Objective 6: Concurrency & Runtime Isolation](#objective-6-concurrency--runtime-isolation)
10. [Objective 7: Distribution & Packaging](#objective-7-distribution--packaging)
11. [Exploratory / Research](#exploratory--research)
12. [Features Intentionally Deferred](#features-intentionally-deferred)

---

## Guiding Principles

1. **Perl5 compatibility first** — nail language correctness, then leverage JVM advantages
2. **Java platform alignment** — follow JDK evolution, use standard APIs, publish to Maven Central
3. **Ecosystem over features** — working CPAN modules matter more than exotic new capabilities
4. **Dual-backend architecture** — JVM bytecode for performance, interpreter for flexibility
5. **Measure progress** — track perl5 test suite pass rates as the north-star metric

---

## Recently Completed

These capabilities are implemented and available in the current release:

- **Dual Compilation Backends** — JVM bytecode (via ASM) and a fast register-based interpreter that share the same runtime. The interpreter handles large code blocks and fast eval-string compilation. See `dev/design/interpreter.md`.
- **CPAN Client (`jcpan`)** — Install, test, and manage pure-Perl CPAN modules. Working with DateTime (99.7% tests), Log::Log4perl (98.9% tests), Moo, Template, DBIx::Class, and many others. See `dev/design/cpan_client.md`.
- **Java XS Fallback Mechanism** — `XSLoader::load` transparently loads Java implementations for modules that normally use C XS code (e.g., DateTime, DBI, Digest::MD5, Digest::SHA). See `dev/design/xs_fallback.md`.
- **Perl Debugger (`-d`)** — Interactive debugger with breakpoints, step/next/return, stack traces, expression evaluation. See [Feature Matrix — Perl Debugger](../reference/feature-matrix.md#perl-debugger).
- **`class` Keyword** — Full support for `class`, `method`, `field`, `:param`, `:reader`, `:isa`, `ADJUST` blocks, lexical method calls.
- **`defer` Blocks** — `use feature 'defer'` with LIFO execution, exception safety, and all exit mechanisms. See `dev/design/defer_blocks.md`.
- **Overload Pragma** — Arithmetic, comparison, string, dereference, and regex overloading. Method name resolution for `overload::nil` pattern. See [Feature Matrix — overload](../reference/feature-matrix.md#pragmas).
- **DBI with JDBC** — Full DBI API backed by JDBC drivers. See [Feature Matrix — DBI](../reference/feature-matrix.md#dbi-module).
- **I/O Subsystem** — Sockets, I/O layers (`:raw`, `:utf8`, `:crlf`, `:encoding()`), in-memory files, pipes, file descriptor duplication, `flock`, tied handles.
- **Pack/Unpack** — Full template support for binary data manipulation. See `dev/design/pack_unpack_architecture.md`.
- **Subroutine Prototypes and Signatures** — All prototype characters supported; formal parameter signatures implemented.
- **`format`/`write`** — Report generation with `formline` and `$^A` accumulator.
- **SBOM Generation** — CycloneDX Software Bill of Materials for both Java and Perl dependencies. See `dev/design/sbom.md`.
- **JSR-223 Script Engine** — `ScriptEngine`, `ScriptEngineFactory`, and `Compilable`/`CompiledScript` interfaces implemented. Compile-once, execute-many via `PerlCompiledScript` with MethodHandle invocation. ServiceLoader auto-discovery.
- **CI/CD Pipeline** — GitHub Actions testing on Ubuntu and Windows.
- **Startup Performance** — Lazy initialization of expensive JNA calls ($( and $) variables). See `dev/design/pr328-startup-performance.md`.
- **FFM Migration** — Replaced JNR-POSIX with Java's Foreign Function & Memory API (JEP 454), eliminating `sun.misc.Unsafe` warnings on Java 24+. Migrated `chmod`, `kill`, `stat`, `lstat`, `link`, `fcntl`, `isatty`, `getpwnam`, `umask`, `waitpid`, and other POSIX calls. See `dev/design/ffm_migration.md` and PR #380.
- **Docker Image** — Multi-stage `Dockerfile` with Eclipse Temurin JDK 22. Includes `jperl`, `jcpan`, `jperldoc`, `jprove` in `/usr/local/bin`.
- **Debian Package** — `.deb` packaging via Gradle `ospackage` plugin (`make deb`). Installs to `/opt/perlonjava` with symlinks in `/usr/local/bin` and bundled SBOM.

---

## Active Development

Work currently in progress:

- **Warnings Subsystem** — Improving lexical `warnings` pragma scope handling and warning message formatting for Perl5 compatibility. See `dev/design/warnings-scope.md`.
- **Regex Improvements** — Ongoing compatibility fixes for regex edge cases, POSIX character classes, and Unicode properties.
- **Overload Completeness** — Adding missing overload operators: `++`, `--`, `=` (copy constructor), bitwise, string repeat, concatenation. See [Feature Matrix — overload](../reference/feature-matrix.md#pragmas).
- **`caller` Extended Information** — Implementing `(caller($level))[3..11]` for subroutine names, `wantarray`, `evaltext`, hints. Required for better error messages and Carp compatibility.
- **Compiler Hardening** — Automatic fallback to interpreter mode when JVM "Method too large" errors occur. Fix remaining global variable aliasing edge cases in `for` loops.
- **perl5 Test Suite** — Expanding pass rates across `perl5_t/t/` categories (op, re, uni, mro, io, lib).

---

## Objective 1: Language Correctness & Perl5 Alignment

*Priority: High — These items directly affect compatibility with existing Perl code.*

### Core Language Gaps

- ~~**`DESTROY` Support**~~ — Implemented with cooperative reference counting. Supports cascading destruction, closure capture tracking, and global destruction phase.
- ~~**Weak References**~~ — Implemented: `Scalar::Util::weaken`/`isweak`/`unweaken` with external WeakRefRegistry.
- **Taint Mode (`-T`)** — Track external data provenance using a `TAINTED` wrapper type (no extra storage for untainted scalars). Required for security-sensitive Perl applications. See `dev/design/TAINT_MODE.md`.
- **Dynamically-Scoped Regex Variables** — `$1`, `$2`, etc. should be localized per regex match in the dynamic scope.

### Missing Regex Features

- **Recursive Patterns** — `(?R)`, `(?0)`, `(??{ code })` for recursive matching.
- **Branch Reset Groups** — `(?|...)` to reset group numbering across branches.
- **Conditional Expressions** — `(?(condition)yes|no)` for conditional matching.
- **Embedded Code** — `(?{ code })` and `(??{ code })` for inline Perl execution.
- **Variable-Length Lookbehind** — Full lookbehind assertion support.
- **Extended Grapheme Clusters** — `\X` matching.
- **Named Capture Improvements** — Allow underscores in names; allow duplicate names.

### Missing Pragmas and Features

- **`no strict refs`** — Extend to work with lexical (`my`) variables, not just globals.
- **`bignum`/`bigint`/`bigrat`** — Transparent arbitrary-precision arithmetic.
- **`locale`** — Locale-aware string operations.
- **`integer`** — Force integer arithmetic.
- **`encoding`** — Source encoding pragma.
- **`re` Pragma** — `use re 'eval'`, `use re '/flags'`, `use re 'debug'`.
- **`attributes`** — Variable and subroutine attributes beyond `:lvalue` and `prototype`.
- **`overloading`** — Fine-grained overload control pragma.
- **`CORE` Operator References** — `\&CORE::push` and similar.
- **Smartmatch / `given`/`when`** — Evaluate community demand before implementing.

### Compiler Flags and Special Variables

- **`$^H` and `%^H`** — Compile-time hint variables (needed for many pragmas).
- **`${^WARNING_BITS}`** — Warning category bitmask.
- **Extended `caller` info** — Full 11-element return from `caller($level)`.

---

## Objective 2: Java Platform Alignment

*Priority: High — These items ensure PerlOnJava stays current with the Java ecosystem.*

### Maven Central Publishing

Publish PerlOnJava as a Maven artifact so Java developers can embed it as a dependency. See `dev/design/maven-central-publishing.md`.

- Add POM metadata: `<description>`, `<licenses>`, `<developers>`, `<scm>`.
- Generate sources JAR and Javadoc JAR.
- Set up GPG signing and Central Portal account.
- Claim `org.perlonjava` namespace.

### JSR-223 Compliance Improvements

The core JSR-223 `ScriptEngine`, `ScriptEngineFactory`, and `Compilable`/`CompiledScript` interfaces are implemented. Remaining gaps:

- Implement `Invocable` for calling individual Perl subs from Java (`invokeFunction`, `invokeMethod`).
- Respect `ScriptContext` I/O bindings — redirect Perl stdout/stderr to `context.getWriter()`/`context.getErrorWriter()`.
- Bridge `Bindings` to Perl variables — pass Java-side bindings into the Perl runtime as globals.
- Declare `THREADING` parameter in `ScriptEngineFactory.getParameter()` (currently returns `null`).
- Thread-safety via configurable global lock or runtime isolation (depends on Objective 6 Multiplicity).

See `dev/design/jsr223-perlonjava-web.md`.

### JDK Compatibility Matrix

PerlOnJava requires Java 22+ (FFM API). Remaining work:

- Test and document compatibility with JDK 22, 23, 24, and future LTS releases.
- Ensure CI runs against multiple JDK versions.
- Track JDK deprecations that affect PerlOnJava (e.g., `sun.misc.Unsafe` removal timeline).

---

## Objective 3: Ecosystem & Module Compatibility

*Priority: High — Working CPAN modules drive adoption more than any other factor.*

### CPAN Module Expansion

- **File::stat** — Needed for DateTime::Locale installation.
- **Safe.pm** — Move beyond stub; evaluate feasibility of compartment restrictions on JVM.
- **Module::Build** — Improve support beyond current stub for modules that don't use MakeMaker.
- **Test::Harness** — Fix UTF-8 handling for test output parsing.
- **Exporter** — Support `*glob` exports.

### Real-World Module Targets

The goal is 100% test suite pass rate for representative CPAN modules used as adoption benchmarks: DateTime, Log::Log4perl, Moo, Image::ExifTool, Try::Tiny, Path::Tiny, and JSON::PP.

### Java XS Expansion

Extend the Java XS fallback mechanism to more modules:

- **List::Util / Scalar::Util** — Java implementations for performance-critical functions.
- **Encode** — Leverage Java's `Charset` for encoding operations.
- **Storable** — Java serialization backend.
- **Clone** — Deep clone using Java reflection.

### Pure-Perl Module Ecosystem

Ensure seamless installation of key pure-Perl modules via `jcpan`:

- Text::CSV, YAML::PP, JSON, HTTP::Tiny, URI, MIME::Base64 (already working).
- Expand to: Path::Tiny, Try::Tiny, Type::Tiny, Specio, namespace::clean.

---

## Objective 4: Performance & Optimization

*Priority: Medium — Important for production use, but correctness comes first.*

### Interpreter Optimizations

- **Superoperators** — Combine frequent multi-instruction sequences into single optimized opcodes (e.g., `DEREF_HASH + LOAD_STRING + HASH_GET`). See `dev/design/superoperators.md`.
- **Inline Constant Subroutines** — Fold constant sub calls at compile time.
- **Eval-String Heuristic** — Switch to interpreter mode when the same eval site is called with different strings repeatedly.

### Compiler Optimizations

- **Inline `map`/`grep` Blocks** — Avoid subroutine call overhead for simple blocks.
- **Prefetch Named Subroutines** — Resolve frequently called subs to direct references.
- **Buffer Pooling** — Reuse `ByteBuffer` instances in I/O operations.
- **I/O Layer Optimization** — Extract buffering into a dedicated layer for better throughput.

### Shared AST Transformer

Introduce a normalization pass between parsing and code generation to eliminate parity issues between the JVM and interpreter backends. See `dev/design/shared_ast_transformer.md`.

- Context resolution, lvalue analysis, and variable resolution done once in the AST.
- Both backends consume a fully-annotated, normalized AST.
- Eliminates the class of bugs caused by duplicated compilation logic.

### Startup Time

- Profile and reduce JVM cold-start overhead for CLI scripts.
- Evaluate CDS (Class Data Sharing) and AOT caching for frequently-used modules.
- Investigate GraalVM native image when `SupportRuntimeClassLoading` becomes available.

### Benchmarking

- Maintain a benchmark suite comparing PerlOnJava vs. native Perl for key workloads.
- Track performance over time to catch regressions.

---

## Objective 5: Developer Tooling

*Priority: Medium — Improves developer experience and adoption.*

### Debugger Enhancements

- **Conditional Breakpoints** — `b line condition`.
- **Watch Expressions** — `w expr`.
- **Command History** — JLine integration for readline support.
- **Custom Debugger Modules** — `-d:Module` support.
- **`perl5db.pl` Compatibility** — Enable existing Perl debugger scripts.

### IDE Integration

- **IntelliJ IDEA Plugin** — Syntax highlighting, run configurations, debugger integration.
- **VSCode Extension** — Language server, syntax highlighting, inline diagnostics.

### REPL Improvements

- Command history and JLine-based line editing.
- Tab completion for variables, functions, and module names.
- Result history variables.

### Documentation

- **Migration Guide** — What works, what doesn't, what's different from native Perl.
- **Java Interop Cookbook** — Examples of calling Java from Perl via XSLoader.
- **Embedding Guide** — JSR-223 integration patterns for Java applications.
- **Feature Matrix** — Keep [Feature Matrix](../reference/feature-matrix.md) current as the canonical compatibility reference.

---

## Objective 6: Concurrency & Runtime Isolation

*Priority: Long-term — Major architectural work required.*

See `dev/design/concurrency.md` for the comprehensive design covering multiplicity, fork emulation, and threads.

### Multiplicity

Enable multiple independent Perl runtimes within a single JVM process.

**Why it matters:**
- Enables fork emulation, ithreads, and concurrent web request handling.
- Required for true JSR-223 thread safety.
- Unblocks production web server deployments.

**Approach (hybrid):**
1. Classloader-based isolation for quick prototyping (1-2 months).
2. Gradual de-static-ification of runtime state into `PerlRuntime` instances (4-6 months).

### Fork Emulation

Implement `fork()` via runtime cloning + thread. Currently returns `undef`.

- Support common fork patterns: `if (fork() == 0) { ... exit; }`.
- Deep-copy runtime state for child "process".
- True OS-level fork remains impossible on JVM.

### Threads (ithreads)

Implement Perl's `threads` module using JVM threads with per-thread runtime cloning.

- Variable isolation per thread (copy on creation).
- `:shared` attribute for synchronized cross-thread variables.
- Thread-local special variables, caches, and I/O handles.
- `exit` semantics: exit current thread only.

---

## Objective 7: Distribution & Packaging

*Priority: Long-term — Lowers the barrier to adoption.*

Docker image and Debian `.deb` package are already available (see [Recently Completed](#recently-completed)). Remaining work:

### Native Installers

- **Windows** — MSI installer via `jpackage` with bundled JRE, PATH setup, Start Menu shortcuts. See `dev/design/windows_installer.md`.
- **macOS** — Homebrew formula or DMG package.
- **Linux** — `.rpm` package; evaluate Snap or Flatpak.

### Container & Registry

- Publish Docker image to Docker Hub or GitHub Container Registry.
- Add variant with common CPAN modules pre-installed.

### Package Manager Integration

- Homebrew tap for macOS.
- Evaluate SDKMAN! for JVM-centric distribution.
- Chocolatey package for Windows.

---

## Exploratory / Research

*These items require further investigation before committing to implementation.*

- **GraalVM Native Image** — Currently blocked by `SupportRuntimeClassLoading` not being available. Monitor GraalVM releases. The interpreter mode may work with native image. See `dev/design/graalvm.md`.
- **Modular Extensions** — ServiceLoader-based plugin system for Java-implemented Perl modules distributed as separate JARs. See `dev/design/dynamic_loading.md`.
- **Foundation Incubation** — Evaluate Apache Software Foundation or The Perl Foundation for governance, sustainability, and enterprise credibility. See `dev/design/incubating.md`.
- **Polyglot Integration** — Investigate GraalVM Truffle for interop with JavaScript, Python, Ruby.
- **Log4j/SLF4J Integration** — Bridge Perl `warn`/`die` to Java logging frameworks for enterprise observability.
- **`Inline::Java` Equivalent** — Direct Perl-to-Java interoperability for calling arbitrary Java APIs from Perl code.

---

## Features Intentionally Deferred

These features are unlikely to be implemented due to fundamental JVM constraints or low demand:

- **True `fork()`** — JVM cannot split into two OS processes. Fork emulation via threads is planned instead.
- **Perl XS (C code)** — C extensions cannot run on JVM. Java XS fallback mechanism is the replacement strategy.
- **`dump` operator** — Core dump functionality has no JVM equivalent.
- **DBM file support** — `dbmclose`/`dbmopen` not implemented; use DBI instead.
- **Source filters** — `Filter::Util::Call` style source manipulation is not planned.
- **`Opcode.pm`** — Requires Perl opcode tree internals that don't exist in PerlOnJava's compilation model.


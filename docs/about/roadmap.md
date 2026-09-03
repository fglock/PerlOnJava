# Roadmap

Current priorities and future plans for PerlOnJava. See the
[Changelog](changelog.md) for release history and the
[Feature Matrix](../reference/feature-matrix.md) for detailed feature support.

## Table of Contents

1. [Current Project Status](#current-project-status)
2. [Guiding Principles](#guiding-principles)
3. [Recently Completed](#recently-completed)
4. [Current Priorities](#current-priorities)
5. [Objective 1: Language Correctness & Perl5 Alignment](#objective-1-language-correctness--perl5-alignment)
6. [Objective 2: Java Platform Alignment](#objective-2-java-platform-alignment)
7. [Objective 3: Ecosystem & Module Compatibility](#objective-3-ecosystem--module-compatibility)
8. [Objective 4: Performance & Optimization](#objective-4-performance--optimization)
9. [Objective 5: Developer Tooling](#objective-5-developer-tooling)
10. [Objective 6: Concurrency & Runtime Isolation](#objective-6-concurrency--runtime-isolation)
11. [Objective 7: Distribution & Packaging](#objective-7-distribution--packaging)
12. [Exploratory / Research](#exploratory--research)
13. [Features Intentionally Deferred](#features-intentionally-deferred)

---

## Current Project Status

PerlOnJava is **actively developed** and is in its **compatibility and
performance phase**. The broad Perl language implementation and JVM runtime are
in place. The project is not finished, frozen, or limited to critical fixes:
development now concentrates on the long tail of language compatibility,
real-world CPAN support, and runtime efficiency.

The latest measured snapshots are:

- **Imported upstream Perl tests:** 669,688 of 673,847 checks pass across 585
  imported test files (**99.4%**), measured on 2026-09-03. This is the imported
  compatibility corpus, not every test distributed with upstream Perl.
- **CPAN sample:** 8,315 of 16,443 tested modules pass their complete test suites
  (**50.6%**), as reported on 2026-09-02. Modules are selected randomly from the
  CPAN index, and dependencies encountered during testing are also recorded.

These figures are dated progress measurements, not guarantees that an
individual script or distribution will work. Check the
[Feature Matrix](../reference/feature-matrix.md) for language boundaries and the
[current CPAN compatibility report](../../dev/cpan-reports/cpan-compatibility.md)
for module results. The [testing guide](../reference/testing.md#interpreting-compatibility-figures)
explains how the project calculates these measurements.

Current development has three primary goals:

1. Close the remaining upstream Perl compatibility gaps and keep both execution
   backends aligned.
2. Broaden CPAN coverage and fix reusable compiler, runtime, tooling, and
   Java-backed XS blockers.
3. Reduce CPU time, memory use, and startup overhead without sacrificing
   correctness.

Bug reports, reproducible CPAN test results, benchmarks, documentation fixes,
and pull requests are welcome; see the [support guide](support.md) and
[contribution guide](../../CONTRIBUTING.md).

---

## Guiding Principles

1. **Perl5 compatibility first** — nail language correctness, then leverage JVM advantages
2. **Java platform alignment** — follow JDK evolution, use standard APIs, publish to Maven Central
3. **Ecosystem over features** — working CPAN modules matter more than exotic new capabilities
4. **Dual-backend architecture** — JVM bytecode for performance, interpreter for flexibility
5. **Measure progress** — track the imported upstream Perl suite and sampled CPAN results with dated, reproducible measurements

---

## Recently Completed

These capabilities are implemented and available in the current release:

- **Dual Compilation Backends** — JVM bytecode (via ASM) and a fast register-based interpreter that share the same runtime. The interpreter handles large code blocks and fast eval-string compilation. See `dev/design/interpreter.md`.
- **CPAN Client (`jcpan`)** — Install, test, and manage pure-Perl CPAN modules. Working with DateTime (99.7% tests), Log::Log4perl (98.9% tests), Moo, Template, DBIx::Class, and many others. See [Using CPAN modules](../guides/using-cpan-modules.md) and [Patch and CPAN prefs layout](../../dev/design/patch-and-cpan-prefs-layout.md).
- **Bundled Moose 2.4000 and Class::MOP** — the upstream Moose source tree ships in the JAR. Used as the runtime dependency for installing CPAN modules — for example `DBIx::Class` 0.082843 (which uses `Moo`, fetched from CPAN) passes 100% of its test suite under PerlOnJava. Upstream Moose's own tests pass ~99%. See [bundled modules](../reference/bundled-modules.md#moose--classmop) and `dev/modules/moose_support.md`.
- **Java XS Fallback Mechanism** — `XSLoader::load` transparently loads Java implementations for modules that normally use C XS code (e.g., DateTime, DBI, Digest::MD5, Digest::SHA). See `dev/design/xs_fallback.md`.
- **Perl Debugger (`-d`)** — Interactive debugger with breakpoints, step/next/return, stack traces, expression evaluation. See [Feature Matrix — Perl Debugger](../reference/feature-matrix.md#perl-debugger).
- **`class` Keyword** — Full support for `class`, `method`, `field`, `:param`, `:reader`, `:isa`, `ADJUST` blocks, lexical method calls.
- **`defer` Blocks** — `use feature 'defer'` with LIFO execution, exception safety, and all exit mechanisms. See `dev/design/defer_blocks.md`.
- **Overload Pragma** — Arithmetic, comparison, string, dereference, and regex overloading. Method name resolution for `overload::nil` pattern. See [Feature Matrix — overload](../reference/feature-matrix.md#pragmas).
- **DBI with JDBC** — Full DBI API backed by JDBC drivers. See [Feature Matrix — DBI](../reference/feature-matrix.md#dbi-module).
- **I/O Subsystem** — Sockets, I/O layers (`:raw`, `:utf8`, `:crlf`, `:encoding()`), in-memory files, pipes, file descriptor duplication, `flock`, tied handles.
- **Multiplicity and Perl ithreads** — Mutable interpreter state is owned by
  `PerlRuntime`; `Config` advertises `useithreads`, `usethreads`, and
  `usemultiplicity`. The bundled `threads`, `threads::shared`, `Thread::Queue`,
  and `Thread::Semaphore` distributions pass unchanged on both backends and
  both Java carrier policies. See the
  [Perl threads reference](../reference/threads.md).
- **Native regex implementation** — The maintained Joni fork is the sole
  production matcher across both backends, including dynamic regex programs,
  bounded recursion, variable-length lookbehind, grapheme clusters, advanced
  Unicode properties, control verbs, and lexical `re` policy. See the
  [regex feature matrix](../reference/feature-matrix.md#regular-expressions).
- **Native Async/Await** — The bundled `Future::AsyncAwait` implementation
  supports suspension, resumption, cancellation, signatures, `defer`, and
  `CANCEL` blocks; all 52 upstream files and 221 assertions pass.
- **Pack/Unpack** — Full template support for binary data manipulation. See `dev/design/pack_unpack_architecture.md`.
- **Subroutine Prototypes and Signatures** — All prototype characters supported; formal parameter signatures implemented.
- **`format`/`write`** — Report generation with `formline` and `$^A` accumulator.
- **SBOM Generation** — CycloneDX Software Bill of Materials for both Java and Perl dependencies. See `dev/design/sbom.md`.
- **JSR-223 Script Engine** — `ScriptEngine`, `ScriptEngineFactory`, and `Compilable`/`CompiledScript` interfaces implemented. Compile-once, execute-many via `PerlCompiledScript` with MethodHandle invocation. ServiceLoader auto-discovery.
- **CI/CD Pipeline** — GitHub Actions testing on Ubuntu and Windows.
- **Startup Performance** — Lazy initialization of expensive JNA calls ($( and $) variables). See `dev/design/pr328-startup-performance.md`.
- **FFM Migration** — Replaced JNR-POSIX with Java's Foreign Function & Memory API (JEP 454), eliminating `sun.misc.Unsafe` warnings on Java 24+. Migrated `chmod`, `kill`, `stat`, `lstat`, `link`, `fcntl`, `isatty`, `getpwnam`, `umask`, `waitpid`, and other POSIX calls. See `dev/design/ffm_migration.md` and PR #380.
- **Docker Image** — Multi-stage `Dockerfile` with Eclipse Temurin JDK 24. Includes `jperl`, `jcpan`, `jperldoc`, `jprove` in `/usr/local/bin`.
- **Debian Package** — `.deb` packaging via Gradle `ospackage` plugin (`make deb`). Installs to `/opt/perlonjava` with symlinks in `/usr/local/bin` and bundled SBOM.

---

## Current Priorities

Work currently in progress is organized around the compatibility and
performance goals above:

- **Perl compatibility** — Close incomplete and failing cases in the imported
  upstream suite, including diagnostic, compiler, runtime, and standard-library
  differences, while keeping the JVM and interpreter backends aligned. The
  [Feature Matrix](../reference/feature-matrix.md) records known boundaries.
- **CPAN compatibility** — Use sampled distribution results to find reusable
  blockers, expand Java replacements for XS dependencies, and validate
  representative applications and libraries.
- **CPU and memory performance** — Profile real workloads, remove allocation
  and dispatch hot spots, reduce startup overhead, and retain correctness gates
  for every optimization.
---

## Objective 1: Language Correctness & Perl5 Alignment

*Priority: High — These items directly affect compatibility with existing Perl code.*

### Core Language Gaps

- ~~**`DESTROY` Support**~~ — Implemented with selective reference counting. Supports cascading destruction, closure capture tracking, and global destruction phase.
- ~~**Weak References**~~ — Implemented: `Scalar::Util::weaken`/`isweak`/`unweaken` with external WeakRefRegistry.
- ~~**Taint Mode (`-T`)**~~ — Implemented on both backends. External input is
  marked tainted, taint propagates through supported operations, capture-based
  untainting works, and security-sensitive operations reject tainted values.
  Warning-mode `-t` semantics remain incomplete. See `dev/design/TAINT_MODE.md`.
### Regular Expressions

The maintained Joni fork is the sole production matcher. Current supported
capability families and their narrow diagnostic or representation boundaries
are recorded in the
[Feature Matrix](../reference/feature-matrix.md#regular-expressions); the
implementation and delivery record is preserved in the
[regex implementation plan](../../dev/design/regex-implementation.md).

### Missing Pragmas and Features

- **`no strict refs`** — Extend to work with lexical (`my`) variables, not just globals.
- **`bignum`/`bigint`** — Complete transparent arbitrary-precision arithmetic
  on both backends. `bigrat` is implemented.
- **`locale`** — Locale-aware string operations.
- **`attributes`** — Variable and subroutine attributes beyond `:lvalue` and `prototype`.
- **`overloading`** — Fine-grained overload control pragma.
- **`CORE` Operator References** — `\&CORE::push` and similar.
- **Smartmatch / `given`/`when`** — Evaluate community demand before implementing.

### Compiler Flags and Special Variables

- ~~**`$^H`, `%^H`, and `${^WARNING_BITS}` snapshots**~~ — Lexical
  compile-time state and extended `caller` fields are implemented on both
  backends. Exact compatibility for every pragma-specific mutation and warning
  bit remains ongoing.
- ~~**Extended `caller` info**~~ — The full 11-element return from
  `caller($level)` is implemented on both backends.

---

## Objective 2: Java Platform Alignment

*Priority: Ongoing platform stewardship.*

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

PerlOnJava requires Java 24+. Remaining work:

- Test and document compatibility with JDK 24 and future releases.
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

*Priority: High — Runtime efficiency is a current development focus alongside correctness.*

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

*Priority: Compatibility and ecosystem hardening.*

See `dev/design/concurrency.md` for the comprehensive design covering multiplicity, fork emulation, and threads.

### Multiplicity

Multiple independent `PerlRuntime` instances and snapshot cloning are
implemented. The bounded PSGI runtime pool is opt-in and replaces returned app
snapshots from an authoritative template; it does not partially reset and reuse
an entered runtime. Multiplicity alone does not make one JSR-223 engine or one
captured PSGI app concurrently callable.

### Fork Emulation

Implement `fork()` via runtime cloning + thread. Currently returns `undef`.

- Support common fork patterns: `if (fork() == 0) { ... exit; }`.
- Deep-copy runtime state for child "process".
- True OS-level fork remains impossible on JVM.

### Threads (ithreads)

Perl ithreads are shipped: snapshot-based variable isolation, lifecycle and
signals, nested threads and child exit, `threads::shared` graphs and proxies,
locks and conditions, `Thread::Queue`, and `Thread::Semaphore`. Java 24 virtual
threads are the default; platform carriers remain selectable.

Remaining work:

- Keep the permanent PR and release matrices green.
- Broaden CPAN/native ecosystem coverage, including opt-in Test2 and Moose
  thread suites and Net::SSLeay callback stress.
- Track direct regex-language/Joni work in the regex implementation plan while
  thread wrappers preserve the behavior of their direct companions.

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
- **Source filters beyond the supported subset** — Closure filters installed
  through `Filter::Util::Call`, `Filter::Simple::FILTER`, and `FILTER_ONLY`
  work. Object/method filters and Perl-compatible incremental streaming remain
  deferred; see `dev/design/source_filters.md`.
- **`Opcode.pm`** — Requires Perl opcode tree internals that don't exist in PerlOnJava's compilation model.

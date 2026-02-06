# Roadmap

Future plans for PerlOnJava. See [Changelog](changelog.md) for release history.

## Work in Progress

The following areas are currently under active development to enhance the functionality and performance of PerlOnJava:

- **Compiler Subsystem**
  - Implementing the `lexical_subs` feature.
  - Enhancing version control with `use VERSION` and `require VERSION`.
  - Introducing lexical warnings and strictness for improved code safety.
  - Supporting lexical UTF-8 source code.
  - Ensure that stack traces are well-formed.
  - Developing subroutine prototypes.
  - Handling `goto` special cases for `Text::Balanced`.
  - Addressing indirect object special cases for `GetOpt::Long`.
  - Localizing regex variables.
  - Fix handling of global variable aliasing in `for`.

- **Regex Subsystem**
  - Ongoing improvements and feature additions.

- **Class Subsystem**
  - Enhancements and new feature implementations.

- **DBI Subsystem**
  - Adding additional methods for database interaction.

- **Overload Subsystem**
  - Expanding with additional methods.

- **I/O Subsystem**
  - Implementing in-memory I/O operations.
  - Developing `socket` and related operators.
  - Enhancing `truncate`, `seek`, and `read` operators.
  - Introducing IO layers and experimenting with memory-mapped I/O.

- **Threads Subsystem**
  - Documenting preliminary features.
  - Planning for fork emulation and interpreter cloning.
  - Developing `multiplicity` support.
  - Adjusting `exit` semantics to exit the current thread only.
  - Adding `ThreadLocal` to global variables, special variables, and caches, with tear down hooks for cleanup.

- **CPAN Support**
  - Porting `cpan` and `prove`.
  - Adding module testing for PerlOnJava core modules.

- **Optimization**
  - Inlining `map` and related blocks.
  - Inlining constant subroutines.
  - Prefetch named subroutines to lexical (`our`).

- **Compilation with GraalVM**
  - Documenting preliminary results in [docs/GRAALVM.md](docs/GRAALVM.md).


## Upcoming Milestones

- **v5.42.3**: Next minor version

  - Non-local control flow: `last`/`next`/`redo`/`goto LABEL`
  - Tail call with trampoline for `goto &NAME` and `goto __SUB__`
  - Add modules: `TOML`.
  - Bugfix: operator override in Time::Hires now works.
  - Bugfix: internal temp variables are now pre-initialized.
  - Optimization: faster list assignment.
  - Optimization: faster type resolution in Perl scalars.
  - Optimization: `make` now runs tests in parallel.
  - Optimization: A workaround is implemented to Java 64k bytes segment limit.
  - Planned release date: 2026-02-10.

- Work in Progress
  - PerlIO
    - `get_layers`
  - Term::ReadLine
  - Term::ReadKey
  - FileHandle
  - File::Temp
  - File::Path
  - File::Copy
  - IO::File
  - IO::Handle
    - `ungetc`
    - Auto-bless filehandle into IO::Handle subclass
  - IO::Seekable
  - Filter::Simple
  - Math::BigInt
  - Text::ParseWords
  - Text::Tabs
  - Locale::Maketext::Simple
  - Params::Check
  - SelectSaver
  - locale pragma
  - utf8 pragma
  - bytes pragma
  - threads pragma
  - warnings pragma
  - vmsish pragma
  - Constant folding - in ConstantFoldingVisitor.java
  - `method` keyword
  - Overload operators: `++`, `--`.
  - String interpolation fixes.
  - Command line option `-C`

### v4.0.0 Milestone (Planned Release Date: 2026-05-10)

**Objective:** Enhance core functionality and improve developer experience with a focus on integration and performance.

1. **Concurrency and Security Enhancements**
  - **Specific:** Implement basic concurrency support with threads and async/await capabilities.
  - **Measurable:** Ensure at least 50% of core modules support concurrent execution.
  - **Achievable:** Utilize existing JVM concurrency features.
  - **Relevant:** Addresses modern application requirements for parallel processing.
  - **Time-bound:** Complete by Q1 2026.

2. **External Integration**
  - **Specific:** Integrate with popular external libraries for HTTP requests and database access.
  - **Measurable:** Provide at least two integration examples in documentation.
  - **Achievable:** Leverage existing Java libraries for integration.
  - **Relevant:** Enhances PerlOnJava's utility in web and data applications.
  - **Time-bound:** Complete by Q1 2026.

3. **Development Tools**
  - **Specific:** Develop an interactive debugger with breakpoints and variable inspection.
  - **Measurable:** Debugger should support at least 80% of core language features.
  - **Achievable:** Build on existing JVM debugging capabilities.
  - **Relevant:** Improves developer productivity and code quality.
  - **Time-bound:** Complete by Q1 2026.

4. **Distribution and Packaging**
  - **Specific:** Provide Docker containers and Kubernetes configurations for easy deployment.
  - **Measurable:** Ensure compatibility with at least two major cloud platforms.
  - **Achievable:** Use standard containerization practices.
  - **Relevant:** Facilitates deployment in modern cloud environments.
  - **Time-bound:** Complete by Q1 2026.

### v5.0.0 Milestone (Planned Release Date: 2027-04-10)

**Objective:** Expand platform capabilities and improve performance with advanced features and optimizations.

1. **GraalVM Integration**
  - **Specific:** Implement native image compilation for instant startup and reduced memory footprint.
  - **Measurable:** Achieve at least a 30% reduction in startup time and memory usage.
  - **Achievable:** Collaborate with GraalVM community for support.
  - **Relevant:** Enhances performance for cloud and serverless applications.
  - **Time-bound:** Complete by Q1 2027.

2. **Mobile Development**
  - **Specific:** Develop an Android app with native UI for Perl scripting.
  - **Measurable:** Ensure app compatibility with at least 80% of Android devices.
  - **Achievable:** Utilize existing Android development frameworks.
  - **Relevant:** Expands PerlOnJava's reach to mobile developers.
  - **Time-bound:** Complete by Q1 2027.

3. **Advanced Data Manipulation**
  - **Specific:** Add XML parsing and data transformation features.
  - **Measurable:** Provide at least three example scripts demonstrating these capabilities.
  - **Achievable:** Use existing Java libraries for data manipulation.
  - **Relevant:** Increases PerlOnJava's applicability in data-driven applications.
  - **Time-bound:** Complete by Q1 2027.

4. **Enterprise Features**
  - **Specific:** Integrate with logging frameworks and provide monitoring with Prometheus/Grafana.
  - **Measurable:** Ensure logging and monitoring support for at least 70% of core modules.
  - **Achievable:** Leverage existing enterprise tools and frameworks.
  - **Relevant:** Meets enterprise requirements for observability and security.
  - **Time-bound:** Complete by Q1 2027.


## Future Development Areas

- **Concurrency and Security Features**
  - Add support for concurrency and parallelism, such as threads and async/await.
  - Enhance security features, including sandboxing and input validation.
  - Increase test coverage.

- **External Integration and Advanced Data Manipulation**
  - Integrate with external libraries and APIs for tasks like HTTP requests and database access.
  - Add advanced data manipulation features, such as JSON/XML parsing and data transformation.
  - Allow users to define their own operators and macros for greater flexibility.

- **Mobile Development**
  - Android app with native UI for Perl scripting
  - Integration with Android's file system and permissions
  - Mobile-optimized performance settings

- **Distribution and Packaging**
  - Native installers for Windows/Linux/MacOS
  - Docker containers and Kubernetes configurations
  - Maven Central Repository publishing
  - Integration with package managers (apt, yum, chocolatey, homebrew)

- **Development Tools**
  - IDE plugins for IntelliJ IDEA, Eclipse, VSCode
  - Interactive debugger with breakpoints and variable inspection
  - Code formatting and static analysis tools
  - Performance profiling tools

- **Enterprise Features**
  - Monitoring and metrics with Prometheus/Grafana
  - Integration with logging frameworks (Log4j, SLF4J)
  - Security hardening and vulnerability scanning
  - Cloud deployment templates (AWS, Azure, GCP)

- **GraalVM Integration**
  - Native image compilation for instant startup
  - Polyglot integration with JavaScript, Python, Ruby
  - Ahead-of-time compilation optimizations
  - Reduced memory footprint for cloud deployments
  - SubstrateVM support for containerized environments

- **Native System Integration**
  - JNI/XS bridge to enable native code modules
    - Integration with existing Perl XS ecosystem
  - Core system operations
    - Process control (fork, exec)
    - Direct memory access (mmap)
    - Native sockets and file descriptors
  - Platform-specific features
    - Unix: ptrace, signals, IPC
    - Windows: Registry, COM objects
    - MacOS: FSEvents, CoreFoundation, Security framework


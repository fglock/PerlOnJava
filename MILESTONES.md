# PerlOnJava Milestones

# Table of Contents

- [Completed Milestones](#completed-milestones)
- [Work in progress](#work-in-progress)
- [Upcoming Milestones](#upcoming-milestones)
- [Future Development Areas](#future-development-areas)

## Completed Milestones

- **v3.0.0**: Performance Boost, New Modules, and Streamlined Configuration
  - Added `--upgrade` option to `Configure.pl` to upgrade dependencies.
  - Added `Dockerfile` configuration.
  - Added `Time::HiRes`, `Benchmark` modules.
  - Added `/ee` regex modifier.
  - Added no strict `vars`, `subs`.
  - Execute the code generation on demand, for faster module loading.
  - Use `int` instead of `enum` to reduce the memory overhead of scalar variables.


- **v2.3.0**: Modern Perl Features, Expanded Modules, and Developer Tools
  - Project description updated in `README.md` to "A Perl Distribution for the JVM"
  - Added module porting guide at `docs/PORTING_MODULES.md`
  - Added wrapper scripts (`jperl`/`jperl.bat`) for easier command-line usage
  - Added `YAML` and `YAML::PP` modules.
  - Added `Text::Balanced` module.
  - Added `Unicode::Normalize` module.
  - Added subroutine signatures and `signature` feature.
  - Added chained operators.
  - Added stacked file test operators.
  - Added `module_true` feature.
  - Added `<<` and `<<~` Here documents.
  - Added `/p`, `/c`, `/n` regex modifiers.
  - Added regex `(?^` clear embedded pattern-match modifier.
  - Added regex `(?'name'...)` named capture groups.
  - Added regex `\k<name>` and `\g{name}` backreferences to named groups.
  - Added regex `\p{...}` and `\P{...}` for Unicode properties.
  - Added regex `\g{-n}` for relative backreferences.
  - Added regex `*+`, `++`, `?+`, `{n,m}+` possessive quantifiers.
  - Added regex `(?>...)` for atomic groups.
  - Added overload: `""`, `0+`, `bool`, `fallback`, `nomethod`.
  - Added `class` feature and `class` keyword.
  - Library upgrades.
    Maven:  `mvn versions:use-latest-versions`.
    Gradle: `./gradlew useLatestVersions`.

- **v2.2.0**: Core modules
  - Perl version is now v5.40.0
  - `for` loop can iterate over multiple values at the same time.
  - `for` loop variables are aliased.
  - Added `DBI` module with JDBC support.
  - Added `URI::Escape` module.
  - Added `builtin` methods: `inf` `nan` `weaken` `unweaken` `is_weak` `blessed` `refaddr` `reftype` `created_as_string` `created_as_number` `stringify` `ceil` `floor` `indexed` `trim` `is_tainted`.
  - Added command line switches: `-S`.
  - Added low-precedence xor `^^` operator.
  - Added [Configure.pl](Configure.pl) to set compiler options and add JDBC drivers.
  - Added Links to Perl on JVM resources in README - https://github.com/fglock/PerlOnJava/tree/master#additional-information-and-resources
  - Added [SUPPORT.md](docs/SUPPORT.md)
 
- **v2.1.0**: Core modules and optimization
  - Added `Getopt::Long`, `JSON` modules.
  - Optimized `print` to `STDOUT`/`STDERR` performance by running in a separate thread.
  - Added `subs` pragma.
  - Added regex `$+` variable.
  - Added command line switches: `-v`, `-V` .
  - Added file test operators: `-R`, `-W`, `-X`, `-O`, `-t`.
  - Added feature flags: `evalbytes`.
  - Added `CORE::GLOBAL` and core function overrides.
  - Added hexadecimal floating point numbers.

- **v2.0.0**: Towards a Complete Perl Port on the JVM
  - Added unmodified core Perl modules `File::Basename`, `File::Find`, `Data::Dumper`, `Term::ANSIColor`, `Time::Local`, `HTTP::Date`, `HTTP::CookieJar`.
  - Added `Cwd`, `File::Spec`, `File::Spec::Functions`, `HTTP::Tiny` modules.
  - "use feature" implemented: `fc`, `say`, `current_sub`, `isa`, `state`, `try`, `bitwise`, `postderef`.
  - Stash can be accessed as a hash like `$namespace::{entry}`.
  - Added stash constants:  `$constant::{_CAN_PCS} = \$const`;
  - Added `exists &sub`, `defined &sub`.
  - Added `builtin` pragma: `true`, `false`, `is_bool`.
  - Added `re` pragma: `is_regexp`.
  - Added `vars` pragma.
  - Added `SUPER::method` method resolution.
  - Added `AUTOLOAD` default subroutine.
  - Added `stat`, `lstat` operators. Some fields are not available in JVM and return `undef`.
  - Added directory operators.
  - Added regex patterns: `[[:ascii:]]`, `[[:print:]]`, `(?#comment)`, and the `/xx` modifier.

- **v1.11.0**: Compile-time Features
  - Added `BEGIN`, `CHECK`, `UNITCHECK`, `INIT`, `END` blocks.
  - Added subroutine hoisting: Invoking subroutines before their actual declaration in the code.
  - Improved Exporter.pm, glob assignment.
  - Added modules: `constant`, `if`, `lib`, `Internals` (`SvREADONLY`), `Carp`.
  - Added `goto &name`; not a tail-call.
  - Added `state` variables.
  - Added `$SIG{ALRM}`, `${^GLOBAL_PHASE}`.
  - Added operators: `fileno`, `getc`, `prototype`.
  - Added `\N{U+hex}` operator in double quoted strings and regex.

- **v1.10.0**: Operators and Special Variables
  - Error messages mimic those in Perl for consistency.
  - Added `$.`, `$]`, `$^V`, `${^LAST_FH}`, `$SIG{__DIE__}`, `$SIG{__WARN__}` special variables.
  - Added command line switches `-E`, `-p`, `-n`, `-i`, `-0`, `-a`, `-F`, `-m`, `-M`, `-g`, `-l`, `-x`, `-?`.
  - Added `select(filehandle)` operator, `ARGVOUT` filehandle.
  - Added `~.`, `&.`, `|.`, `^.` operators.
  - Added `try catch` statement.
  - Added Scalar::Util: `blessed`, `reftype`.
  - Added UNIVERSAL: `VERSION`.
  - Added v-strings.
  - Added Infinity, -Infinity, NaN.
  - Added `\N{name}` operator for named characters in double quoted strings and in regex.
  - Added lvalue subroutines.
  - CI/CD runs in Ubuntu and Windows
 
- **v1.9.0**: Operators and Special Variables
  - Added bitwise string operators.
  - Added lvalue `substr`, lvalue `vec`
  - Fix `%b` specifier in `sprintf`
  - Emulate Perl behaviour with unsigned integers in bitwise operators.
  - Regex `m?pat?` match-once and the `reset()` operator are implemented.
  - Regex `\G` and the `pos` operator are implemented.
  - Regex `@-`, `@+`, `%+`, `%-` special variables are implemented.
  - Regex `` $` ``, `$&`, `$'` special variables are implemented.
  - Regex performance comparable to Perl; optimized regex variables.
  - Regex matching plain strings: `$var =~ "Test"`.
  - Added `__SUB__` keyword; `readpipe`.
  - Added `&$sub` call syntax.
  - Added `local` dynamic variables.
  - Tests in `src/test/resources` are executed automatically.

- **v1.8.0**: Operators
  - Added `continue` blocks and loop operators `next`, `last`, `redo`; a bare-block is a loop
  - Added bitwise operators `vec`, `pack`, `unpack`
  - Added `srand`, `crypt`, `exit`, ellipsis statement (`...`)
  - Added `readdir`, `opendir`, `closedir`, `telldir`, `seekdir`, `rewinddir`, `mkdir`, `rmdir`
  - Added file test operators like `-d`, `-f`
  - Added the variants of diamond operator `<>` and special cases of `while`
  - Completed `chomp` operator; fixed `qw//` operator, `defined-or` and `x=`
  - Added modules: `parent`, `Test::More`

- **v1.7.0**: Performance Improvements
  - Focus on optimizing the execution engine for better performance.
  - Improve error handling and debugging tools to make development easier. More detailed debugging symbols added to the bytecode. Added `Carp` module.
  - Moved Perl standard library modules into the jar file.
  - More tests and various bug fixes

- **v1.6.0**: Module System and Standard Library Enhancements
  - Module system for improved code organization and reuse
  - Core Perl module operators: `do FILE`, `require`, `caller`, `use`, `no`
  - Module special subroutines: `import`, `unimport`
  - Environment and special variables: `PERL5LIB`, `@INC`, `%INC`, `@ARGV`, `%ENV`, `$0`, `$`
  - Additional operators: `die`, `warn`, `time`, `times`, `localtime`, `gmtime`, `index`, `rindex`
  - Standard library ported modules: `Data::Dumper`, `Symbol`, `strict`
  - Expanded documentation and usage examples

- **v1.5.0**: Regex operators
  - Added Regular expressions and pattern matching: m//, pos, qr//, quotemeta, s///, split
  - More complete set of operations on strings, numbers, arrays, hashes, lists
  - More special variables
  - More tests and various bug fixes

- **v1.4.0**: I/O operators
  - File i/o operators, STDOUT, STDERR, STDIN
  - TAP (Perl standard) tests

- **v1.3.0**: Added Objects.
  - Objects and object operators, UNIVERSAL class
  - Array and List related operators
  - More tests and various bug fixes

- **v1.2.0**: Added Namespaces and named subroutines.
  - Added typeglobs
  - Added more operators

- **v1.1.0**: Established architecture and added key features. The system now supports benchmarks and tests.
  - JSR 223 integration
  - Support for closures
  - Eval-string functionality
  - Enhanced statements, data types, and call context

- **v1.0.0**: Initial proof of concept for the parser and execution engine.


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

- **v3.0.1**: Next minor version
  - Update Perl version to `5.42.0`.
  - Added overload operators: `!`, `+`, `-`, `*`, `/`, `%`, `int`, `neg`, `log`, `sqrt`, `cos`, `sin`, `exp`, `abs`, `atan2`, `**`, `@{}`, `%{}`. `${}`, `&{}`, `*{}`.
  - Subroutine prototypes are fully implemented. Added or fixed: `+`, `;`, `*`, `\@`, `\%`, `\$`, `\[@%]`.
  - Added double quoted string escapes: `\U`, `\L`, `\u`, `\l`.
  - Added star count (`C*`) in `pack`, `unpack`.
  - Added operators: `read`, `tell`, `seek`, `system`, `exec`.
  - Added operator: `select(undef,undef,undef,$time)`.
  - Added operator: `^^=`.
  - Added operator: `delete`, `exists` for array indexes.
  - Added `open` option: in-memory files.
  - Syntax: identifiers starting with `::` are in `main` package.
  - Added I/O layers support to `open`, `binmode`: `:raw`, `:bytes`, `:crlf`, `:utf8`, `:unix`, `:encoding()`.
  - Add `open` support for pipe `-|`, `|-`, `ls|`, `|sort`.
  - Added `# line` preprocessor directive.
  - `Test::More` module: added `subtest`, `use_ok`, `require_ok`.
  - `CORE::` operators have the same prototypes as in Perl.
  - Added modules: `Fcntl`, `Test`, `Text::CSV`.
  - Operator `$#` returns an lvalue.
  - Improved autovivification handling: distinguish between contexts where undefined references should automatically create data structures versus where they should throw errors.
  - Bugfix: fix a problem with Windows newlines and qw(). Also fixed `mkdir` in Windows.
  - Bugfix: `-E` switch was setting strict mode.
  - BugFix: fix calling context in operators that return list.
  - BugFix: fix rules for overriding operators.
  - Added Makefile.
  - Debian package can be created with `make deb`.
  - Planned release date: 2025-12-10.

- Work in Progress
  - Term::ReadLine
  - Term::ReadKey
  - File::Temp
  - File::Path
  - XSLoader or Dynaloader for JVM
  - Add `tie` operation

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
  - **Specific:** Add JSON/XML parsing and data transformation features.
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

# PerlOnJava Milestones

# Table of Contents

- [Completed Milestones](#completed-milestones)
- [Work in progress](#work-in-progress)
- [Upcoming Milestones](#upcoming-milestones)
- [Future Development Areas](#future-development-areas)

## Completed Milestones

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


## Work in progress

- Compiler subsystem
  - `lexical_subs` feature.
  - `use VERSION`, `require VERSION`.
  - lexical warnings.
  - lexical strictness.
  - lexical utf8 source code.
  - preprocessor `# line` directive.
  - subroutine prototypes.
  - `goto` special cases - for `Text::Balanced`.
  - indirect object special cases - for `GetOpt::Long`.
  - Implement localization of regex variables.
- Regex subsystem
- Class subsystem
- DBI subsystem
  - Additional methods.
- Overload subsystem
  - Additional methods.
- I/O subsystem
  - in-memory I/O
  - `socket` and related operators.
  - `truncate`, `seek` operators.
  - `read` operator.
  - IO layers.
  - Experiment with memory-mapped I/O.
- Threads subsystem
  - Added preliminary docs.
  - Plan for fork emulation, interpreter clone.
  - Plan for `multiplicity`.
  - Adjust `exit` semantics, to exit the current thread only.
  - Add `ThreadLocal` to global variables, special variables (like regex, current directory, current handle, ENV), and caches (like `InheritanceResolver`). Provide tear down hooks to clean up; use `remove()`.
- Feature and Warnings internals
  - Add metadata to AST nodes.
  - This can be used to add context to `EvalOperatorNode` for example.
- CPAN support
  - Port `cpan`, `prove`.
  - Add module testing for PerlOnJava core modules.
- Optimization
  - Inline `map` and related blocks.
  - Inline constant subroutines.
- Compilation with GraalVM
  - Added [docs/GRAALVM.md](docs/GRAALVM.md) with preliminary results.


## Upcoming Milestones

- **v2.4.0**: Next minor version
  - Planned release date: 2025-02-10

- **v3.0.0**: Concurrency and Security Features
  - Add support for concurrency and parallelism, such as threads and async/await.
  - Enhance security features, including sandboxing and input validation.
  - Increase test coverage.

- **v4.0.0**: External Integration and Advanced Data Manipulation
  - Integrate with external libraries and APIs for tasks like HTTP requests and database access.
  - Add advanced data manipulation features, such as JSON/XML parsing and data transformation.
  - Allow users to define their own operators and macros for greater flexibility.

## Future Development Areas

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

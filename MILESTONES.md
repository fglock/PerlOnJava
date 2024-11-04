## PerlOnJava Milestones

### Completed Milestones

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

### Upcoming Milestones

- **v1.10.0**: Concurrency and Security Features
  - Planned release date: 2024-12-10
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
  - Work in progress:
    - `state` variables.
    - `socket` and related operators.
    - `use VERSION`, `require VERSION`.
    - `BEGIN`, `END` blocks.
  - Stretch goals
    - Add support for concurrency and parallelism, such as threads and async/await.
    - Enhance security features, including sandboxing and input validation.
    - Increase test coverage.

- **v1.11.0**: External Integration and Advanced Data Manipulation
  - Integrate with external libraries and APIs for tasks like HTTP requests and database access.
  - Add advanced data manipulation features, such as JSON/XML parsing and data transformation.
  - Allow users to define their own operators and macros for greater flexibility.

- **v2.0.0**: Major Release with Breaking Changes
  - Perform comprehensive refactoring and optimization.
  - Introduce significant new features and improvements.
  - Ensure full compliance with relevant standards and best practices.

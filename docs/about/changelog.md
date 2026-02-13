# Changelog

Release history of PerlOnJava. See [Roadmap](roadmap.md) for future plans.


## v5.42.3: Unreleased - Next minor version

- Non-local control flow: `last`/`next`/`redo`/`goto LABEL`
- Tail call with trampoline for `goto &NAME` and `goto __SUB__`
- Add modules: `TOML`.
- Bugfix: operator override in Time::Hires now works.
- Bugfix: internal temp variables are now pre-initialized.
- Optimization: faster list assignment.
- Optimization: faster type resolution in Perl scalars.
- Optimization: `make` now runs tests in parallel.
- Optimization: A workaround is implemented to Java 64k bytes segment limit.
- New command line option: `--interpreter` to run PerlOnJava as an interpreter instead of JVM compiler.
  - `./jperl --interpreter --disassemble -e 'print "Hello, World!\n"'`
  - The interpreter mode excels at dynamic eval STRING operations (46x faster than compilation for unique strings, matching Perl 5 performance). For general code, it runs only 15% slower than Perl 5. It is also useful for implementing debugging, handling "Method too large" errors, and enabling Android and GraalVM compatibility.
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


## v5.42.2: 250k Tests, Class Features, System V IPC, Sockets, and More

  - Add Perl 5.38+ Class Features
    - Class keyword with block syntax fully working
    - Method declarations with automatic $self injection
    - Field declarations supporting all sigils ($, @, %)
    - Constructor parameter fields with :param attribute
    - Reader method generation with :reader attribute  
    - Automatic constructor generation with named parameters
    - Default values for fields fully functional
    - ADJUST blocks with field transformation working
    - Field transformation to $self->{field} in methods
    - Lexical method calls using $self->&priv syntax
    - Class inheritance with :isa attribute working
    - Version checking in :isa(Parent version) implemented
    - Parent class field inheritance fully functional
    - Object stringification shows OBJECT not HASH
    - ClassRegistry tracks Perl 5.38+ class instances
    - Context-aware reader methods for arrays/hashes
    - Field transformation in string interpolation works
    - __CLASS__ keyword with compile-time evaluation
  - Add System V IPC operators: `msgctl`, `msgget`, `msgrcv`, `msgsnd`, `semctl`, `semget`, `semop`, `shmctl`, `shmget`, `shmread`, `shmwrite`.
  - Add network enumeration operators: `endhostent`, `endnetent`, `endprotoent`, `endservent`, `gethostent`, `getnetbyaddr`, `getnetbyname`, `getnetent`, `getprotoent`, `getservent`, `sethostent`, `setnetent`, `setprotoent`, `setservent`.
  - Add socket operators: `socket`, `bind`, `listen`, `accept`, `connect`, `send`, `recv`, `shutdown`, `setsockopt`, `getsockopt`, `getsockname`, `getpeername`, `socketpair`.
  - Add Socket.pm module with socket constants and functions.
  - Add `alarm` operator with `$SIG{ALRM}` signal handling.
  - Fix `truncate` operator.
  - Add `pipe` operator.
  - Add `do \&subroutine`.
  - Add `formline` operator and `$^A` accumulator variable
  - Add file descriptor duplication support in `open` (`<&`, `>&`, `<&=`, `>&=`).
  - Add statement: `format`, and `write` operator
  - Add special variables: `@{^CAPTURE}`, `${^LAST_SUCCESSFUL_PATTERN}`.
  - Add pack format `x`.
  - Add `do filehandle`.
  - Add module `Storable`, `experimental`, `Unicode::UCD`.
  - Add single-quote as package separator.
  - Dereferencing using `$$var{...}` and `$$var[...]` works.
  - Add declared references: `my \$x`, `my(\@arr)`, `my(\%hash)`.
  - Add subroutines declared `my`, `state`, or `our`.
  - Bugfix in regex `/r`.
  - Bugfix in transliterate with octal values.
  - Bugfix in nested heredocs.
 

## v5.42.1: 150k Tests, Extended Operators, and More Perl 5 Features

  - Add operators: `getlogin`, `getpwnam`, `getpwuid`, `getgrnam`, `getgrgid`, `getpwent`, `getgrent`, `setpwent`, `setgrent`, `endpwent`, `endgrent`, `gethostbyname`, `gethostbyaddr`, `getservbyname`, `getservbyport`, `getprotobyname`, `getprotobynumber`, `reset`.
  - Add overload operators: `<=>`, `cmp`, `<`, `<=`, `>`, `>=`, `==`, `!=`, `lt`, `le`, `gt`, `ge`, `eq`, `ne`, `qr`.
  - Add command line switches: `-s`, `-f`.
  - Add `__CLASS__` keyword.
  - Add modules: `mro`, `version`, `List::Util`.
  - Add more `sprintf` formatters.
  - Add readline modes depending on `$/` special variable.
  - Add `PERL5OPT` environment variable.
  - Add regex extended character classes `(?[...])`
  - Bugfix: fixed vstring with codepoints above 65535.


## v5.42.0: 100k Tests Passed, Tie Support, and Total Compatibility
  - Add `tie`, `tied`, `untie` operators.
  - Add all `tie` types: scalar, array, hash, and handle.
  - Add operators: `sysread`, `syswrite`, `kill`, `utime`, `chown`, `waitpid`, `umask`, `readlink`, `link`, `symlink`, `rename`.
  - Add modules: `XSLoader`, `Encode`,`Config`, `Errno`, `Tie::Scalar`, `Tie::Array`, `Tie::Hash`, `Tie::Handle`, `Perl::OSType`, `Env`, `MIME::Base64`, `MIME::QuotedPrint`, `Digest::SHA`, `Digest::MD5`, `Digest`.
  - Add key-value slices: `%c{"1", "3"}`.
  - Add special variable: `$^X`.
  - Add `W`, `H`, `F`, `h`, `c`, `u`, `C0`, `U0` formats to `pack`, `unpack`.
  - Add dualvar.
  - Add `DATA` file handle.
  - Add Indirect method call.
  - Add regex variables: `${^PREMATCH}`, `${^MATCH}`, `${^POSTMATCH}`.
  - Add regex operators: `\N` not-newline, `\b{gcb}`, `\B{gcb}` boundary assertions.
  - Add regex properties supported by Perl but missing in Java regex.
  - Add command line switches: `-w`, `-W`, `-X`.
  - Process `\L`, `\U`, `\l`, `\u` in regex.
  - `Test::More` `skip` works.
  - UTF-16 is accepted in source code.
  - Add support for `pmc` files.
  - Bugfix: methods can be called in all blessed reference types.
  - Bugfix: more robust `sprintf` formatting.
  - Bugfix: string constants can be larger than 64k.
  - Bugfix: fixed foreach loops with global variables.


## v3.1.0: Tracks Perl 5.42.0
  - Update Perl version to `5.42.0`.
  - Added features: `keyword_all`, `keyword_any`

  - Accept input program in several ways:
    1. **Piped input**: `echo 'print "Hello\n"' | ./jperl` - reads from pipe and executes immediately
    2. **Interactive input**: `./jperl` - shows a prompt and waits for you to type code, then press Ctrl+D (on Unix/Linux/Mac) or Ctrl+Z (on Windows) to signal end of input
    3. **File redirection**: `./jperl < script.pl` - reads from the file
    4. **With arguments**: `./jperl -e 'print "Hello\n"'` or `./jperl script.pl`

  - Added overload operators: `!`, `+`, `-`, `*`, `/`, `%`, `int`, `neg`, `log`, `sqrt`, `cos`, `sin`, `exp`, `abs`, `atan2`, `**`, `@{}`, `%{}`. `${}`, `&{}`, `*{}`.
  - Subroutine prototypes are fully implemented. Added or fixed: `+`, `;`, `*`, `\@`, `\%`, `\$`, `\[@%]`.
  - Added double quoted string escapes: `\U`, `\L`, `\u`, `\l`.
  - Added star count (`C*`) in `pack`, `unpack`.
  - Added operators: `read`, `tell`, `seek`, `system`, `exec`, `sysopen`, `chmod`.
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


## v3.0.0: Performance Boost, New Modules, and Streamlined Configuration
  - Added `--upgrade` option to `Configure.pl` to upgrade dependencies.
  - Added `Dockerfile` configuration.
  - Added `Time::HiRes`, `Benchmark` modules.
  - Added `/ee` regex modifier.
  - Added no strict `vars`, `subs`.
  - Execute the code generation on demand, for faster module loading.
  - Use `int` instead of `enum` to reduce the memory overhead of scalar variables.


## v2.3.0: Modern Perl Features, Expanded Modules, and Developer Tools
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

## v2.2.0: Core modules
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
 
## v2.1.0: Core modules and optimization
  - Added `Getopt::Long`, `JSON` modules.
  - Optimized `print` to `STDOUT`/`STDERR` performance by running in a separate thread.
  - Added `subs` pragma.
  - Added regex `$+` variable.
  - Added command line switches: `-v`, `-V` .
  - Added file test operators: `-R`, `-W`, `-X`, `-O`, `-t`.
  - Added feature flags: `evalbytes`.
  - Added `CORE::GLOBAL` and core function overrides.
  - Added hexadecimal floating point numbers.

## v2.0.0: Towards a Complete Perl Port on the JVM
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

## v1.11.0: Compile-time Features
  - Added `BEGIN`, `CHECK`, `UNITCHECK`, `INIT`, `END` blocks.
  - Added subroutine hoisting: Invoking subroutines before their actual declaration in the code.
  - Improved Exporter.pm, glob assignment.
  - Added modules: `constant`, `if`, `lib`, `Internals` (`SvREADONLY`), `Carp`.
  - Added `goto &name`; not a tail-call.
  - Added `state` variables.
  - Added `$SIG{ALRM}`, `${^GLOBAL_PHASE}`.
  - Added operators: `fileno`, `getc`, `prototype`.
  - Added `\N{U+hex}` operator in double quoted strings and regex.

## v1.10.0: Operators and Special Variables
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
 
## v1.9.0: Operators and Special Variables
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

## v1.8.0: Operators
  - Added `continue` blocks and loop operators `next`, `last`, `redo`; a bare-block is a loop
  - Added bitwise operators `vec`, `pack`, `unpack`
  - Added `srand`, `crypt`, `exit`, ellipsis statement (`...`)
  - Added `readdir`, `opendir`, `closedir`, `telldir`, `seekdir`, `rewinddir`, `mkdir`, `rmdir`
  - Added file test operators like `-d`, `-f`
  - Added the variants of diamond operator `<>` and special cases of `while`
  - Completed `chomp` operator; fixed `qw//` operator, `defined-or` and `x=`
  - Added modules: `parent`, `Test::More`

## v1.7.0: Performance Improvements
  - Focus on optimizing the execution engine for better performance.
  - Improve error handling and debugging tools to make development easier. More detailed debugging symbols added to the bytecode. Added `Carp` module.
  - Moved Perl standard library modules into the jar file.
  - More tests and various bug fixes

## v1.6.0: Module System and Standard Library Enhancements
  - Module system for improved code organization and reuse
  - Core Perl module operators: `do FILE`, `require`, `caller`, `use`, `no`
  - Module special subroutines: `import`, `unimport`
  - Environment and special variables: `PERL5LIB`, `@INC`, `%INC`, `@ARGV`, `%ENV`, `$0`, `$`
  - Additional operators: `die`, `warn`, `time`, `times`, `localtime`, `gmtime`, `index`, `rindex`
  - Standard library ported modules: `Data::Dumper`, `Symbol`, `strict`
  - Expanded documentation and usage examples

## v1.5.0: Regex operators
  - Added Regular expressions and pattern matching: m//, pos, qr//, quotemeta, s///, split
  - More complete set of operations on strings, numbers, arrays, hashes, lists
  - More special variables
  - More tests and various bug fixes

## v1.4.0: I/O operators
  - File i/o operators, STDOUT, STDERR, STDIN
  - TAP (Perl standard) tests

## v1.3.0: Added Objects.
  - Objects and object operators, UNIVERSAL class
  - Array and List related operators
  - More tests and various bug fixes

## v1.2.0: Added Namespaces and named subroutines.
  - Added typeglobs
  - Added more operators

## v1.1.0: Established architecture and added key features. The system now supports benchmarks and tests.
  - JSR 223 integration
  - Support for closures
  - Eval-string functionality
  - Enhanced statements, data types, and call context

## v1.0.0: Initial proof of concept for the parser and execution engine.



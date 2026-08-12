# Changelog

Release history of PerlOnJava. See [Roadmap](roadmap.md) for future plans.

## Work in progress

- Add Perl interpreter multiplicity and the supported ithread tranche across
  the JVM and interpreter backends. Mutable execution state is owned by
  independent `PerlRuntime` instances; child threads receive identity-aware
  snapshots, while `threads::shared` preserves explicitly shared
  scalar/array/hash storage. Implement create/join/detach and lifecycle/error
  inspection, nested threads, child-only exit, `CLONE`/`CLONE_SKIP`, recursive
  locks, condition variables, and compatible imports/stringification.
  `Config` now reports `useithreads`, `usethreads`, and `usemultiplicity` as
  `define`. Platform threads remain the default and virtual threads are an
  experimental opt-in. Thread signals, effective stack sizing, blessed/tied
  shared values, and some upstream core/native-callback suites remain limited;
  see the [feature matrix](../reference/feature-matrix.md#concurrency-and-perl-threads).
- CPAN/tooling: expose tested dependency scripts through `PATH`, deduplicate
  repeated `PERL5LIB` setup, and resolve test prerequisites against tested
  `blib` trees before launching tests. Add `JSON::DWIW`, `Taint::Runtime`, and
  `String::Similarity` compatibility ports; preserve open-file identity across
  rename and Archive::Zip scalar/subclass behavior. Preserve the active `@_`
  across `goto &sub` when an outer scope localized `*_`, and avoid synthetic
  stash traversal in reachability checks used by large Moose/Dist::Zilla loads.
- CPAN/compiler tooling: unblock `Template::Lace`, `Sledge::Plugin::JSONRPC`,
  and `Catmandu::Exporter::MAB2` without distribution preferences. Add
  Java-backed `Data::Util` scalar inspection, a `YAML::Syck` compatibility
  layer, XML reader/BOM support, POSIX math defaults, and fixes for try/catch
  control flow, exception values, named-character regexes, bareword `isa`,
  cloned weak references, and temporary reference ownership.
- Bugfix: overloaded mutators skip copy constructors for unshared hash/array
  objects, preserving subclass-only fields in `Math::BigInt`, `Math::BigFloat`,
  and `Math::BigRat` subclasses.
- CPAN: add Java-backed `Tie::Array::Packed`, BouncyCastle-backed
  `Crypt::Blowfish`, and ProcessHandle-backed `Proc::ProcessTable` XS
  replacements. Fix MakeMaker test-helper staging, H/h pack semantics, and raw
  MD5 byte flags so `Tie::Array::Packed` and `Git::Crypt` pass without new
  distribution preferences.
- CPAN: add a Java-backed `Digest::JHash` XS replacement for CHI and
  `TimeZone::TimeZoneDB` dependency chains, and make CPAN's generated
  Makefile fallback work when a distribution ships a read-only Makefile.PL.
- CPAN tooling: avoid launching AutoSplit for POD-only modules, materialize
  `CORE/keywords.h` for build-time probes, and expose only real control-letter
  globals through `%main::` stash enumeration.
- Runtime: avoid redundant global reachability walks while releasing weak
  references in large object trees, substantially reducing PPI teardown cost.
- Runtime: honor explicit custom-warning mask bits and prevent stale recycled
  descriptors from replacing live borrowed-handle mappings.
- CPAN: add a Java XS replacement for `Tie::Hash::Indexed`, including its
  ordered tied-hash/object APIs, iterators, and Storable integration; nested
  tied containers now thaw without acquiring an extra scalar-reference layer.
- CPAN/tooling: bootstrap bundled distroprefs and patches for every `jcpan`
  entry path, preserve substitution `pos` inside replacement code, honor
  `CORE::GLOBAL::rand`, and fix scalar-context coderef assignment returns.
- Add Perl taint mode with `-T` on both JVM and interpreter backends, including
  external-input provenance, scalar and regex propagation, capture-based
  untainting, and security checks for process execution, code loading, file
  mutation, and other sensitive operations.
- Bugfix: targeted weak-reference sweeps preserve objects rescued by `DESTROY`
  until rescue-specific reachability cleanup runs, keeping live DBIx::Class
  storage callbacks valid after a schema self-rescue.
- CPAN: add a BouncyCastle-backed `Crypt::Twofish2` XS replacement, portable
  `B::Flags`, bounded balanced-pattern support for `Text::Markdown`, and a
  PerlOnJava-aware `Char::Latin7` launcher guard; `Text::Markdown::Slidy`,
  `Text::Fold`, and their dependency suites now pass under `jcpan`.
- CPAN: add BouncyCastle-backed `Crypt::Blowfish` block-cipher support and
  reusable noninteractive configure retry, library-staging, and SDBM
  writeback fixes for legacy distributions.
- Add a Java-backed `Scalar::Type` port, replacing its native XS scalar-flag probe.
- Index physical source line numbers so very large generated Perl modules do
  not repeatedly rescan their complete token streams while parsing strings.
- Add Java-backed `PadWalker`, `Devel::Caller`, and `Devel::LexAlias`
  compatibility, including anonymous-sub pad metadata and lexical rebinding
  across JVM and interpreter closures; `Lexical::Persistence` and
  `Test::Cookbook` now pass their installation suites.
- CPAN: supply POD::Tested's omitted `Pod::Parser` prerequisite and tolerate
  Test::Block diagnostics that also fail under current system Perl.
- Bugfix: `UNIVERSAL::DOES` honors classes that override `isa`.
- Add `Catalyst::Runtime` 5.90132 support for single-process PSGI
  applications through `Plack::Handler::Netty`, including action dispatch,
  parameters, uploads, responses, UTF-8, logging, and exception handling.
- Add localized `CORE::GLOBAL::exit` trapping and non-forking `Test::Trap`
  compatibility; the exit-dependent `MooseX::Getopt` tests now pass.
- Bugfix: byte-mode regex substitution preserves the original scalar's lvalue
  identity and `/g` position on both the JVM and interpreter backends.
- Bugfix: Catalyst class-data scalar-reference assignments retain normal stash
  aliasing without turning literal or object references into pseudo-constants.
- Bugfix: `Plack::Handler::Netty` preserves PSGI byte-string response bodies
  without double UTF-8 encoding.
- Bugfix: dynamic `Encode` aliases, `POSIX::tzset`, `utf-8-strict` PerlIO
  layers, CJK display width, and `env perl5` shebang routing work correctly.
- CPAN: add isolated-home Catalyst policies, distribution-scoped recommendation
  handling, and structured failure reporting without false status 8 results
  from informational messages.
- Add native `Future::AsyncAwait` syntax and runtime support, including
  suspended Future resumption, cancellation, async signatures and attributes,
  `defer`/`CANCEL` integration, and the Awaitable role contract.
- Add the compatibility needed to run the single-process `PAGI::Server`
  reference stack with HTTP, WebSocket, and Server-Sent Events; add a verified
  runnable HTTP example under `examples/pagi/`. Process-forking server modes
  remain unsupported.
- CPAN: load large metadata caches lazily and avoid full-catalog scans during
  dependency installation and command summaries, so `jcpan -T PAGI::Server`
  installs the reference server and its dependency chain successfully.
- Bugfix: scope interpreter warning state across JVM-compiled Perl calls and
  honor explicit `local $^W = 0` suppression; this restores the full
  14,726-assertion `op/pack.t` run and related regex/substitution baselines.
- Bugfix: constrain async callback-aggregate lifecycle bookkeeping to active
  async frames, preserve DESTROY-rescued graphs during targeted weak sweeps,
  and assign collision-free async bytecode opcodes after integer operations.
- Bugfix: async multi-value `foreach my (...)` preserves every grouped lexical
  across `await`, and the legacy `experimental::signatures` warning category
  remains accepted for compatible signature-enabled code.
- CPAN: test the bundled `Future::AsyncAwait` implementation directly without
  its replaced XS parser prerequisites; all 52 upstream files and 221
  assertions pass with `jcpan -t Future::AsyncAwait`.
- Bugfix: the unit-test harness accepts successful `plan skip_all` exits as
  clean TAP completion, allowing Unix-only socket tests to skip correctly on
  Windows CI.
- Add compatibility modules for `Socket6`, `Email::Address::XS`, and the
  JSONP-used subset of `Want`; add a Java `Net::Gen` XS bridge for Net-ext.
- Bugfix: subroutine return values are rvalue copies instead of aliases to
  reusable scalar containers.
- Bugfix: interpreter `map` now inherits the caller's scalar/list context.
- Bugfix: valid UTF-8 octets in interpolated source strings compile without
  `use utf8` while retaining byte-string semantics.
- Bugfix: inherited AutoSplit forward declarations now participate in method
  resolution, allowing parent `.al` methods to load before child `AUTOLOAD`
  fallbacks.
- Bugfix: nested typeglob hash/code dereferences and numeric or byte-string
  `AUTOLOAD` method names retain their Perl symbol-table semantics.
- Bugfix: IPv6 bind/listen/send/receive, socket-name packing, and
  `getnameinfo` work through Java NIO; UDP sends to wildcard local addresses
  are routed consistently.
- Bugfix: generated `jperl` shebang commands work in piped opens without being
  misclassified as shell syntax.
- CPAN: `HTML::Diff`, `Graph::PetriNet`, `MIME::Lite`,
  `File::Path::Tiny`, `Net::UDP`, `IO::Socket::INET6`,
  `Email::Sender`, and `JSONP` pass their installation test suites.

## v5.44.0: Named Parameters in Signatures

- Add named parameters in method and subroutine signatures.
- Security: added `SECURITY.md` and CycloneDX SBOM generation (`make sbom`)
- Tools: added `jcpan`, `jperldoc`, and `jprove`
- Perl debugger with `-d` command line option
- Add `defer` feature
- Lexical warnings with `use warnings` and FATAL support
- Non-local control flow: `last`/`next`/`redo`/`goto LABEL`/`goto $EXPR`
- Tail call with trampoline for `goto &NAME` and `goto __SUB__`
- Add modules: `CPAN`, `Time::Piece`, `TOML`, `DirHandle`, `Dumpvalue`, `Sys::Hostname`, `IO::Socket`, `IO::Socket::INET`, `IO::Socket::UNIX`, `IO::Zlib`, `Archive::Tar`, `Archive::Zip`, `Net::FTP`, `Net::Cmd`, `IPC::Open2`, `IPC::Open3`, `ExtUtils::MakeMaker`, `XML::Parser`, `Net::SSLeay`, `IO::Socket::SSL`, `Pod::Html` (+ `Pod::Html::Util`), `Cpanel::JSON::XS` (+ `Cpanel::JSON::XS::Type`, `Cpanel::JSON::XS::Boolean`; JSON::PP-backed shim).
- **Plack::Handler::Netty**: PSGI web server using Netty async I/O. Supports HTTP/HTTPS, streaming responses, 32k+ req/sec. See [examples/http_server_plack](../../examples/http_server_plack/README.md).
- Add operators: `flock`, `syscall`, `fcntl`, `ioctl`. 
- Add `\&CORE::X` subroutine references: built-in functions can be used as first-class code refs (e.g., `\&CORE::push`, `\&CORE::length`) with correct prototypes and glob aliasing.
- Support for forking patterns with `exec`:
        my $pid = open FH, "-|"; if ($pid) {...} else { exec @cmd }
        my $pid = open FH, "-|"; unless ($pid) { exec @cmd } ...
        open FH, "-|" or exec @cmd;
- Bugfix: parser now handles `@{${...}}` nested dereference in push/unshift.
- Bugfix: regex octal escapes `\10`-`\377` now work correctly.
- Bugfix: `\K` (keep left) assertion now works in `m//` and `s///`.
- Bugfix: `^` / `$` in `/m` mode under `/g` no longer produce spurious empty matches in list context (e.g. `"ab\ncd\n" =~ /^(.*)/mg` now returns 2 matches as in Perl, not 4). Restores correct behaviour for the common line-walking idiom and unblocks `Pod::Html::Util::trim_leading_whitespace`.
- Bugfix: `$Config{perladmin}`, `$Config{cf_email}`, `$Config{cf_by}`, and `$Config{myhostname}` are now populated from the running JVM's user/host info instead of being undef.
- Bugfix: operator override in Time::Hires now works.
- Bugfix: internal temp variables are now pre-initialized.
- Optimization: faster list assignment.
- Optimization: faster type resolution in Perl scalars.
- Optimization: `make` now runs tests in parallel.
- Optimization: A workaround is implemented to Java 64k bytes segment limit.
- New `interpreter` backend.
  - New command line option: `--interpreter` to run PerlOnJava as an interpreter instead of JVM compiler.
    - `./jperl --interpreter --disassemble -e 'print "Hello, World!\n"'`
  - The interpreter mode excels at dynamic eval STRING operations (46x faster than compilation for unique strings, matching Perl 5 performance). For general code, it runs only 15% slower than Perl 5. It is also useful for implementing debugging, handling "Method too large" errors, and enabling Android and GraalVM compatibility.
- Add `attributes` pragma with `MODIFY_*_ATTRIBUTES`/`FETCH_*_ATTRIBUTES` callbacks for subroutines and variables.
- Add modules: `Filter::Simple` with `FILTER` and `FILTER_ONLY` support.
- Add `DESTROY` method support with selective reference counting on blessed objects, cascading destruction, closure capture tracking, and global destruction phase.
- Add `Scalar::Util` functions: `weaken`, `isweak`, `unweaken`.
- Add `Internals::SvREFCNT` for compatibility with reference-counting introspection (e.g. Sub::Quote, Moo, DBIx::Class internals).
- **Bundled Moose 2.4000 and Class::MOP 2.4000**: the upstream Moose source tree is shipped in `src/main/perl/lib/{Moose,Class/MOP}/`. Tested by installing `DBIx::Class` 0.082843 via `jcpan` (DBIx::Class itself uses `Moo`, fetched from CPAN) and running its test suite — it passes 100% (314 files / 13858 asserts). Upstream Moose's own test suite passes ~99% (≥396/478 files, ≥13413/13550 asserts). See [bundled modules](../reference/bundled-modules.md#moose--classmop) and [dev/modules/moose_support.md](../../dev/modules/moose_support.md) for the full status and the small set of remaining failure clusters (numeric-arg warnings, anon-class GC timing, threads/fork tests).

- Work in Progress
  - [Multiplicity — per-runtime isolation for concurrent Perl interpreters](https://github.com/fglock/PerlOnJava/pull/480): `PerlRuntime` with `ThreadLocal`-based isolation; all mutable state (globals, I/O, regex, caller stack, method caches) moved to per-runtime instances; 122/126 concurrent interpreter tests pass; pending closure/method dispatch optimization
  - Moose - most tests pass
  - XML::LibXML - some tests pass
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
  - Math::BigInt
  - Text::ParseWords
  - Text::Tabs
  - Locale::Maketext::Simple
  - Params::Check
  - SelectSaver
  - locale pragma
  - utf8 pragma
  - bytes pragma
  - vmsish pragma
  - Constant folding - in ConstantFoldingVisitor.java
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
  - Added [Configure.pl](../../Configure.pl) to set compiler options and add JDBC drivers.
  - Added Links to Perl on JVM resources in README - https://github.com/fglock/PerlOnJava/tree/master#additional-information-and-resources
  - Added [SUPPORT.md](support.md)
 
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

# Perl on JVM Feature Matrix

## Status Legend

- ✅ Fully implemented
- 🚧 Partially implemented
- 🟡 Implemented with limitations
- ❌ Not implemented

---

## Table of Contents

1. [Compiler Usability](#compiler-usability)
2. [Testing](#testing)
3. [Autovivification](#autovivification)
4. [Scalars](#scalars)
5. [Objects](#objects)
6. [Operators](#operators)
7. [Arrays, Hashes, and Lists](#arrays-hashes-and-lists)
8. [Subroutines](#subroutines)
9. [Regular Expressions](#regular-expressions)
10. [Statements and Special Operators](#statements-and-special-operators)
11. [I/O Operations](#io-operations)
12. [Namespaces and Global Variables](#namespaces-and-global-variables)
13. [Perl Modules, Pragmas, Features](#perl-modules-pragmas-features)
    - [Pragmas](#pragmas)
    - [Core modules](#core-modules)
    - [Non-core modules](#non-core-modules)
    - [DBI module](#dbi-module)
14. [Concurrency and Perl Threads](#concurrency-and-perl-threads)
15. [Features Incompatible with JVM](#features-incompatible-with-jvm)
16. [Optimizations](#optimizations)

---

## Summary

PerlOnJava implements most core Perl features with some key differences:

✅ Fully Supported:
- Core language features (variables, loops, conditionals, subroutines)
- Most operators and built-in functions
- Basic OOP with packages, inheritance, and method calls
- Regular expressions (most features)
- DBI with JDBC integration
- Subroutine prototypes
- Tied variables
- Taint mode (`-T`)
- Method Resolution Order
- Perl ithreads, `threads::shared`, `Thread::Queue`, and `Thread::Semaphore`

🚧 Partially Supported:
- Warnings and strict pragma
- Some core modules and pragmas
- File operations and I/O
- Overload
- Source filters: closure filters work; method filters and true streaming remain incomplete

❌ Not Supported:
- Native C/XS binaries (documented Java replacements and pure-Perl fallbacks are supported)
- `fork`

---

## Compiler Usability
- ✅  **Wrapper scripts**: (jperl/jperl.bat) for easier command-line usage.
- ✅  **Perl-like compile-time error messages**: Error messages mimic those in Perl for consistency.
- ✅  **Perl line numbers in bytecode**: Bytecode includes line numbers for better debugging.
- ✅  **Perl-like runtime error messages**: Runtime errors are formatted similarly to Perl's.
- ✅  **Comments**: Support for comments and POD (documentation) in code is implemented.
- ✅  **Environment**: Support for `PERL5LIB`, `PERL5OPT` environment variables.
- 🚧  **Perl-like warnings**: Lexical warnings with FATAL support. Block-scoped warnings pending.

---

## Perl Debugger

The built-in Perl debugger (`perl -d`) provides interactive debugging. See [Debugger Reference](debugger.md) for full documentation.

### Execution Commands
| Command | Status | Description |
|---------|--------|-------------|
| `s` | ✅ | Step into - execute one statement, entering subroutines |
| `n` | ✅ | Next - execute one statement, stepping over subroutines |
| `r` | ✅ | Return - execute until current subroutine returns |
| `c [line]` | ✅ | Continue - run until breakpoint or specified line |
| `q` | ✅ | Quit - exit the debugger |

### Breakpoints
| Command | Status | Description |
|---------|--------|-------------|
| `b [line]` | ✅ | Set breakpoint at line |
| `b file:line` | ✅ | Set breakpoint at line in file |
| `B [line]` | ✅ | Delete breakpoint |
| `B *` | ✅ | Delete all breakpoints |
| `L` | ✅ | List all breakpoints |
| `b line condition` | ❌ | Conditional breakpoints |

### Source and Stack
| Command | Status | Description |
|---------|--------|-------------|
| `l [range]` | ✅ | List source code |
| `.` | ✅ | Show current line |
| `T` | ✅ | Stack trace |
| `w expr` | ❌ | Watch expression |
| `a line command` | ❌ | Set action at line |

### Expression Evaluation
| Command | Status | Description |
|---------|--------|-------------|
| `p expr` | ✅ | Print expression result |
| `x expr` | ✅ | Dump expression with Data::Dumper |

### Debug Variables
| Variable | Status | Description |
|----------|--------|-------------|
| `$DB::single` | ✅ | Single-step mode flag |
| `$DB::trace` | ✅ | Trace mode flag |
| `$DB::signal` | ✅ | Signal flag |
| `$DB::filename` | ✅ | Current filename |
| `$DB::line` | ✅ | Current line number |
| `%DB::sub` | ✅ | Subroutine locations (name → file:start-end) |
| `@DB::args` | ✅ | Current subroutine arguments |

### Not Implemented
- ❌  `-d:Module` - Custom debugger modules (e.g., `-d:NYTProf`)
- ❌  `perl5db.pl` compatibility
- ❌  `R` - Restart program
- ❌  History and command editing

---

## Command Line Switches

- ✅  Accept input program in several ways:
    1. **Piped input**: `echo 'print "Hello\n"' | ./jperl` - reads from pipe and executes immediately
    2. **Interactive input**: `./jperl` - shows a prompt and waits for you to type code, then press Ctrl+D (on Unix/Linux/Mac) or Ctrl+Z (on Windows) to signal end of input
    3. **File redirection**: `./jperl < script.pl` - reads from the file
    4. **With arguments**: `./jperl -e 'print "Hello\n"'` or `./jperl script.pl`
- ✅  UTF-16 is accepted in source code.

- ✅  Accept command line switches from the shebang line.
- ✅  Accept command line switches: `-c`, `-e`, `-E`, `-p`, `-n`, `-i`, `-I`, `-0`, `-a`, `-d`, `-f`, `-F`, `-m`, `-M`, `-g`, `-l`, `-h`, `-s`, `-S`, `-T`, `-x`, `-v`, `-V`, `-?`, `-w`, `-W`, `-X` are implemented.
- ❌  Missing command line switches include:
  - `-t`: Taint checks with warnings. The option is accepted, but warning-mode taint semantics are not implemented.
  - `-u`: Dumps core after compiling.
  - `-U`: Allows unsafe operations.
  - `-D[number/list]`: Sets debugging flags.
  - `-C [number/list]`: Controls Unicode features.

---

## Testing
- ✅  **TAP tests**: Running standard Perl testing protocol.
- ✅  **CI/CD**: Github testing pipeline in Ubuntu and Windows.

---

## Autovivification

Distinguish between contexts where undefined references should automatically create data structures versus where they should throw errors.

### When Autovivification Occurs:
- **Lvalue contexts**: `$arr->[0] = 'value'`, `$hash->{key} = 'value'`
- **Modifying operations**: `push @{$undef}, 'item'`, `pop @{$undef}`, `shift @{$undef}`, `unshift @{$undef}, 'item'`
- **Element access**: `$undef->[0]`, `$undef->{key}` (creates the container but not the element)
- **Operations that can modify through aliases**: `grep { $_ = uc } @{$undef}`, `map { $_ * 2 } @{$undef}`
- **Foreach loops**: `foreach (@{$undef}) { ... }`

### When Autovivification Does NOT Occur, and throws error in `strict` mode:
- **Non-modifying operations**: `sort @{$undef}`, `reverse @{$undef}`
- **Rvalue dereferencing**: `my @list = @{$undef}`, `my %hash = %{$undef}`
- **Scalar context**: `my $count = @{$undef}`

### Examples:
```perl
# These autovivify (create the data structure):
my $x;
$x->[0] = 'hello';        # $x becomes []
push @{$x}, 'world';      # works, autovivifies

my $y;
$y->{name} = 'Alice';     # $y becomes {}
$y->{age}++;              # autovivifies element

# These throw "Can't use an undefined value as an ARRAY/HASH reference":
my $z;
my @sorted = sort @{$z};  # ERROR
my @reversed = reverse @{$z};  # ERROR
my @copy = @{$z};         # ERROR
```

---

## Scalars
- ✅  **`my` variable declaration**: Local variables can be declared using `my`.
- ✅  **`our` variable declaration**: Global variables can be declared using `our`.
- ✅  **`local` variable declaration**: Dynamic variables are implemented.
- ✅  **`state` variable declaration**: State variables are implemented. State variables are initialized only once.
- ✅  **Declared references**: `my \$x`, `my(\@arr)`, `my(\%hash)` are implemented.
- ✅  **Variable assignment**: Basic variable assignment is implemented.
- ✅  **Basic types**: Integers, doubles, strings, v-strings, regex, CODE, undef, and references are supported.
- ✅  **String Interpolation**: Both array and scalar string interpolation are supported.
- ✅  **String Interpolation escapes**: Handles escape sequences like `\n`, `\N{name}`, `\Q`, `\E`, `\U`, `\L`, `\u`, `\l` within interpolated strings.
- ✅  **String numification**: Strings can be converted to numbers automatically.
- ✅  **Numbers with underscores**: Numbers with underscores (e.g., `1_000`) are supported.
- ✅  **Numbers in different bases**: Numbers in binary, hex, octal: `0b1010`, `0xAA`, `078`.
- ✅  **Infinity, -Infinity, NaN**: Special number values are implemented.
- ✅  **Hexadecimal floating point**: Numbers like 0x1.999aP-4 are supported.
- ✅  **References**: References to variables and data structures are supported.
- ✅  **Autovivification**: Autovivification is implemented.
- ✅  **File handles**: Support for file handles is implemented.
- ✅  **`local` special cases**: `local` is implemented for typeglobs and filehandles.
- ✅  **Typeglob as hash**: `*$val{$k}` for `SCALAR`, `ARRAY`, `HASH`, `CODE`, `IO` is implemented.
- ✅  **Use string as a scalar reference**: Support for scalar references from strings is implemented.
- ✅  **Tied Scalars**: Support for tying scalars to classes is implemented. See also [Tied Arrays](#arrays-hashes-and-lists), [Tied Hashes](#arrays-hashes-and-lists), [Tied Handles](#io-operations).
- ✅  **Taint checks**: `-T` marks external inputs, propagates taint through
  scalar and regular-expression operations, supports capture-based untainting,
  and rejects tainted values at security-sensitive operations. Supported by
  both JVM and interpreter backends.
- ❌  **`local` special cases**: `local *HANDLE = *HANDLE` doesn't create a new typeglob.
- 🚧  **Variable attributes**: `my $x : attr` supported via `MODIFY_SCALAR_ATTRIBUTES` etc.

---

## Objects
- ✅  **Objects**: Creating classes and method call syntax are implemented.
- ✅  **Object operators**: `ref` and `bless`
- ✅  **Special variables**: `@ISA` is implemented.
- ✅  **Multiple Inheritance**: C3 method resolution is implemented.
- ✅  **Method caching**: Method resolution is cached.
- ✅  **Version check**: Method `VERSION ( [ REQUIRE ] )` is implemented.
- ✅  **Inheritance**: `SUPER::method` is implemented.
- ✅  **Autoload**: `AUTOLOAD` mechanism is implemented; `$AUTOLOAD` variable is implemented.
- ✅  **`class`**: `class` keyword fully supported with blocks.
- ✅  **Indirect object syntax** indirect object syntax is implemented.
- ✅  **`:isa`**: Class inheritance with version checking is implemented.
- ✅  **`method`**: Method declarations with automatic `$self`.
- ✅  **`field`**: Field declarations with all sigils supported.
- ✅  **`:param`**: Constructor parameter fields fully working.
- ✅  **`:reader`**: Reader methods with context awareness.
- ✅  **`ADJUST`**: `ADJUST` blocks with field transformation work.
- ✅  **Constructor generation**: Automatic `new()` method creation.
- ✅  **Field transformation**: Fields become `$self->{field}` in methods.
- ✅  **Lexical method calls**: `$self->&priv` syntax is implemented.
- ✅  **Object stringification**: Shows OBJECT not HASH properly.
- ✅  **Field defaults**: Default values for fields work.
- ✅  **Field inheritance**: Parent class fields are inherited.
- 🟡  **`__CLASS__`**: Compile-time evaluation only, not runtime.
- 🟡  **Argument validation**: Limited by operator implementation issues.
- ✅  **Moose / Class::MOP**: Moose 2.4000 is bundled.
  Upstream Moose tests pass ~99%; DBIx::Class (installed via `jcpan`)
  passes 100%. See
  [bundled modules](bundled-modules.md#moose--classmop).
- ✅  **`DESTROY`**: Destructor methods with selective reference counting.

---

## Operators

### Arithmetic and Comparison
- ✅  **Simple arithmetic**: Operators like `+`, `-`, `*`, and `%` are supported.
- ✅  **Numeric Comparison operators**: Comparison operators such as `==`, `!=`, `>`, `<`, etc., are implemented.
- ✅  **Chained operators**: Operations like `$x < $y <= $z` are implemented.
- ✅  **defined-or**: `//` operator.
- ✅  **low-precedence-xor**: `^^` and `^^=` operator.

### String Operators
- ✅  **String concat**: Concatenation of strings using `.` is supported.
- ✅  **String Comparison operators**: String comparison operators such as `eq`, `ne`, `lt`, `gt`, etc., are implemented.
- ✅  **`q`, `qq`, `qw`, `qx` String operators**: Various string quoting mechanisms are supported.
- ✅  **Scalar string and math operators**: `quotemeta`, `ref`, `undef`, `log`, `rand`, `oct`, `hex`, `ord`, `chr`, `int`, `sqrt`, `cos`, `sin`, `exp`, `atan2`, `lc`, `lcfirst`, `uc`, `ucfirst`, `chop`, `fc`, `index`, `rindex`, `prototype`.
- ✅  **`join`**: Join operator for combining array elements into a string is supported.
- ✅  **`sprintf`**: String formatting is supported.
- ✅  **`substr`**: Substring extraction is implemented.
- ✅  **Lvalue `substr`**: Assignable Substring extraction is implemented.
- ✅  **`chomp`**: `chomp` is implemented.

### Bitwise Operators
- ✅  **Bitwise operators**: Bitwise operations like `~`, `&`, `|`, `^`, `~.`, `&.`, `|.`, `^.`, `<<`, and `>>` are supported.
- ✅  **Bitwise operators**: Bitwise integer and string operations are implemented.
- ✅  **Bitwise operators return unsigned**: Emulate unsigned integers.
- ✅  **Vectors**: `vec` is implemented.
- ✅  **Lvalue `vec`**: Assignable `vec` is implemented.

### List and Array Operators
- ✅  **`grep`, `map`, `sort`**: List processing functions are implemented.
- ✅  **`pack` and `unpack` operators**: `pack` and `unpack` are implemented.

### Other Operators
- ✅  **Autoincrement, Autodecrement; String increment**: Increment and decrement operators, including for strings, are implemented.
- ✅  **Time-related functions**: `time`, `times`, `gmtime`, `localtime` are implemented.
- ✅  **`crypt` operator**: `crypt` is implemented.
- ✅  **`study`, `srand`**: `study`, `srand` are implemented.
- ✅  **`sleep`**: `sleep` is implemented. It takes fractional seconds.
- ✅  **`alarm`**: `alarm` is implemented with `$SIG{ALRM}` signal handling support.
- ✅  **`stat`**: `stat`, `lstat` are implemented. Some fields are not available in JVM and return `undef`.

---

## Arrays, Hashes, and Lists
- ✅  **Array, Hash, and List infrastructure**: Basic infrastructure for arrays, hashes, and lists is implemented.
- ✅  **List assignment**: Supports list assignment like `($a, undef, @b) = @c`.
- ✅  **`my LIST`**: Declaration of lists using `my` is supported.
- ✅  **Autoquote before `=>`**: Autoquoting before `=>` is implemented.
- ✅  **Select an element from a list**: Indexing into lists is supported.
- ✅  **List subscripts**: like: `(stat($file))[8]`
- ✅  **Taking References of a List**: like: `\(1,2,3)`
- ✅  **List assignment in scalar context**: List assignment in scalar context returns the number of elements produced by the expression on the right side of the assignment
- ✅  **`$#array`**: Operator for array count is implemented.
- ✅  **`scalar`**: Operator to get scalar value is implemented.
- ✅  **Array dereference**: Dereferencing arrays using `@$x`.
- ✅  **Hash dereference**: Dereferencing hashes using `%$x`.
- ✅  **Dereference with $$var{...}**: Dereferencing using `$$var{...}` and `$$var[...]` is implemented.
- ✅  **Basic Array Operations**: `push`, `unshift`, `pop`, `shift`, `splice`, `reverse` are implemented.
- ✅  **Slices**: Array and Hash slices like `@array[2, 3]`, `@hash{"a", "b"}` and `%hash{"a", "b"}` are implemented.
- ✅  **Array literals**: Array literals are supported.
- ✅  **Basic Hash Operations**: `keys`, `values`, `delete`, `exists`, `each` are implemented.
- ✅  **Hash literals**: Hash literals are supported.
- ✅  **List operator `..`**: List constructors are implemented.
- ✅  **Flip-flop operator `..` and `...`**: The flip-flop operators are implemented.
- ✅  **`$#array`**: Lvalue array count is implemented: `$#{$sl} = 10`.
- ✅  **Array exists**: `exists` for array indexes is implemented.
- ✅  **Array delete**: `delete` for array indexes is implemented.
- ✅  **Tied Arrays**: Tied arrays are implemented. See also [Tied Scalars](#scalars), [Tied Hashes](#arrays-hashes-and-lists), [Tied Handles](#io-operations).
- ✅  **Tied Hashes**: Tied hashes are implemented. See also [Tied Scalars](#scalars), [Tied Arrays](#arrays-hashes-and-lists), [Tied Handles](#io-operations).
- ❌  **Restricted hashes**: `Hash::Util` lock/unlock functions (`lock_keys`, `lock_hash`, etc.) are not implemented.

---

## Subroutines
- ✅  **Subroutine hoisting**: Invoking subroutines before their actual declaration in the code.
- ✅  **Anonymous subroutines with closure variables**: Anonymous subs and closures are supported.
- ✅  **Return from inside a block**: Return statements within blocks work correctly.
- ✅  **Assigning to a closure variable mutates the variable in the original context**: Closure variable mutation is implemented.
- ✅  **`@_` contains aliases to the caller variables**: The `@_` array reflects caller arguments correctly.
- ✅  **Named subroutines**: Support for named subroutines is implemented.
- ✅  **Calling context**: `wantarray` is implemented.
- ✅  **exists**: `exists &sub` is implemented.
- ✅  **defined**: `defined &sub` is implemented.
- ✅  **CORE namespace**: `CORE` is implemented.
- ✅  **CORE::GLOBAL namespace**: `CORE::GLOBAL` and core function overrides are implemented.
- ✅  **alternate subroutine call syntax**: `&$sub`, `&$sub(args)` syntax is implemented.
- ✅  **Subroutine prototypes**: Prototypes `$`, `@`, `%`, `&`, `;`, `_`, `+`, `*`, `\@`, `\%`, `\$`, `\[@%]`, empty string and undef are supported.
- ✅  **Subroutine signatures**: Formal parameters are implemented.
- ✅  **`lvalue` subroutines**: Subroutines with attribute `:lvalue` are supported.
- ✅  **`Forcing main package`**: Identifiers starting with `::` are in `main` package.
- ✅  **Lexical subroutines**: Subroutines declared `my`, `state`, or `our` are supported.
- 🚧  **Subroutine attributes**: `:lvalue`, `:prototype`, and custom attributes via `MODIFY_CODE_ATTRIBUTES`/`FETCH_CODE_ATTRIBUTES`.
- ✅  **CORE operator references**: `\&CORE::X` returns callable CODE refs for built-in functions with correct prototypes: `my $r = \&CORE::length; $r->("hello")`

---

## Regular Expressions
- ✅  **Basic Matching**: Operators `qr//`, `m//`, `s///`, `split` are implemented.
- ✅  **Regex modifiers**: Modifiers `/p` `/i` `/m` `/s` `/n` `/g` `/c` `/r` `/e` `/ee` `/x` `/xx` are implemented.
- ✅  **Special variables**: The special variables `$1`, `$2`... are implemented.
- ✅  **Transliteration**: `tr` and `y` transliteration operators are implemented.
- ✅  **`pos`**: `pos` operator is implemented.
- ✅  **`\G`**: `\G` operator in regex is implemented.
- ✅  **`\N{name}`**: `\N{name}` and `\N{U+hex}` operator for named characters in regex is implemented.
- ✅  **`\N`**: Not-newline operator.
- ✅  **lvalue `pos`**: lvalue `pos` operator is implemented.
- ✅  **`m?pat?`** one-time match is implemented.
- ✅  **`reset`** resetting one-time match is implemented
- ✅  **`@-`, `@+`, `%+`, `%-`, `@{^CAPTURE}`, `${^LAST_SUCCESSFUL_PATTERN}` variables**: regex special variables are implemented
- ✅  **`$&` variables**: `` $` ``, `$&`, `$'`, `$+` special variables are implemented, and aliases: `${^PREMATCH}`, `${^MATCH}`, `${^POSTMATCH}`.
- ✅  **`[[:pattern:]]`**: `[[:ascii:]]`, `[[:print:]]` are implemented.
- ✅  **Matching plain strings**: `$var =~ "Test"` is implemented.
- ✅  **Inline comments**: `(?#comment)` in regex is implemented.
- ✅  **caret modifier**: `(?^` embedded pattern-match modifier, shorthand equivalent to "d-imnsx".
- ✅  **\b inside character class**: `[\b]` is supported in regex.
- 🟡  **Unicode boundary assertions**: `\b{gcb}`, `\B{gcb}`, and the `wb`/`lb`/`sb` forms are recognized, but the genuine hand-written generated-corpus preamble still has 25 semantic failures; comprehensive generated coverage is blocked by byte-mode marker normalization.
- ✅  **Variable Interpolation in Regex**: Features like `${var}` for embedding variables.
- ✅  **Non-capturing groups**: `(?:...)` is implemented.
- ✅  **Named Capture Groups**: Defining named capture groups using `(?<name>...)` or `(?'name'...)` is supported.
- ✅  **Backreferences to Named Groups**: Using `\k<name>` or `\g{name}` for backreferences to named groups is supported.
- ✅  **Relative Backreferences**: Using `\g{-n}` for relative backreferences.
- ✅  **Basic Unicode Properties**: Common `\p{...}` and `\P{...}` forms such as `\p{L}` execute through Joni.
- 🟡  **Perl Unicode Property Syntax**: Perl-specific properties and focused Script/Block/Age aliases execute through Joni, but the generated corpus still exposes loose property/value aliases and pinned acceptance/rejection gaps.
- ✅  **Possessive Quantifiers**: Quantifiers like `*+`, `++`, `?+`, and `{n,m}+`, which disable backtracking, are supported.
- ✅  **Atomic Grouping**: Use of `(?>...)` for atomic groups is supported.
- ✅  **`\K` assertion**: Keep left — in `s///`, text before `\K` is preserved; match variables reflect only the portion after `\K`.
- ✅  **Preprocessor**: `\Q`, `\L`, `\U`, `\l`, `\u`, `\E` are preprocessed in regex.
- ✅  **Overloading**: `qr` overloading is implemented. See also [overload pragma](#pragmas).
- ❌  **Python-style named groups**: `(?P<name>...)` and `(?P=name)` are accepted by Perl but are not yet parsed by Joni.
- ❌  **Alpha assertion aliases**: `(*pla:...)`, `(*plb:...)`, `(*nla:...)`, `(*nlb:...)`, and `(*atomic:...)` are not yet parsed by Joni.
- ❌  **Underscored numeric regex escapes**: Perl spellings such as `\x{0_0_4_1}` and `\o{0_0_1_0_1}` are not yet parsed by Joni; braced octal syntax also needs complete validation and diagnostics.

- ✅  **Dynamically-scoped regex variables**: Provisional captures, `$^R`, `$^N`, match positions, and callback locals follow matcher paths and unwind on backtracking.
- ✅  **Recursive and Dynamic Patterns**: `(?R)`, `(?0)`, and runtime `(??{ code })` execute through Joni. Dynamic expressions may return strings or `qr//` values, nested alternatives participate in outer backtracking without changing outer grouping or capture numbering, and callback and pure-pattern recursion have engine-owned depth ceilings.
- ✅  **Backtracking Control Verbs**: `(*ACCEPT)`, `(*FAIL)`/`(*F)`, `(*PRUNE)`, `(*SKIP)`, `(*THEN)`, and `(*COMMIT)` execute through Joni with matcher-owned cut boundaries. Atomic groups `(?>...)` are supported.
- ✅  **Marks and named skip targets**: `(*MARK:NAME)` and its `(*:NAME)` shorthand, named `(*SKIP:NAME)`, `$REGMARK`, and `$REGERROR` execute through Joni and follow the selected backtracking path.
- ✅  **Regex Definitions**: `(?(DEFINE)...)` containers and numbered or named calls to their subpatterns execute through Joni.
- ✅  **Lookbehind Assertions**: Fixed and bounded variable-length positive and negative lookbehind assertions execute through Joni.
- ✅  **Branch Reset Groups**: `(?|...)` resets capture numbering across alternatives and preserves mapped match variables.
- ✅  **Advanced Subroutine Calls**: Sub-pattern calls with numbered or named references like `(?1)` and `(?&name)` execute through Joni.
- ✅  **Conditional Expressions**: Numbered and named capture conditions, positive and negative assertion conditions, recursion conditions `(?(R))`, `(?(R1))`, and `(?(R&name))`, executable callback conditions, and optimistic predicates execute through Joni.
- 🟡  **Extended Unicode Regex Features**: Focused Script, Block, Script Extensions, `Extended_Pictographic`, `Age`, `In`/`Present_In`, and invalid-property gates execute through Joni, and `regexp_unicode_prop.t` passes 1,110/1,110. The lossless generated `uniprops*.t` matrix currently passes 220,712/290,912 identically on JVM and interpreter, but chunks 01–04 still expose broad loose property/value alias gaps and chunks 05–10 are not yet valid boundary evidence because byte-mode substitution does not normalize upgraded boundary-marker regexes against byte subjects.
- 🟡  **Extended Grapheme Clusters**: Focused `\X` combining-sequence and emoji-ZWJ cases pass, but comprehensive generated UAX verification remains blocked by byte-mode boundary-marker normalization.
- ✅  **Embedded Code in Regex**: `(?{ code })`, optimistic callbacks `(*{ code })`, executable callback conditions, and `(??{ code })` run as lexical closures in Joni with provisional captures and backtracking unwind. Callback `local` frames follow matcher paths, and escaped loop control or `goto` stops at the callback pseudo-block boundary. `$^N` follows capture-close order independently of `$+`.
- ✅  **Regex Debugging**: Lexically scoped `use/no re 'debug'` and `debugcolor` are supported, including runtime snapshot ownership.
- ✅  **Runtime Regex Evaluation**: `use re 'eval'` controls whether interpolated patterns containing eval groups may compile. Admitted runtime source is compiled into lexical callback closures and preserves its package, visible lexical cells, Unicode or byte source type, default regex modifiers, and match-once state. Literal callbacks, interpolated `qr//` values (including local, referenced, and tied arrays), raw runtime eval groups, callback conditions, and standalone `(?(DEFINE)...)` containers may be composed in one Joni pattern; dynamic callbacks may return further admitted executable source.
- ✅  **Regex Compilation Flags**: Lexically scoped default flags from `use/no re '/imsx'` are applied to literal, interpolated, and runtime-compiled regex values.
- ✅  **Perl capture and ASCII fold modifiers**: Top-level and scoped `/n` suppress unnamed captures, while `/a` and `/aa` apply Perl ASCII class and ASCII-strict case-fold semantics inside Joni, including restoration across nested modifier groups.
- ✅  **Perl Named Captures**: Names may contain underscores, and duplicate named groups preserve Perl-style `%+`/`%-` and backreference behavior.


## Statements and Special Operators
- ✅  **Context void, scalar, list**: Contexts for void, scalar, and list are supported.
- ✅  **`if`/`else`/`elsif` and `unless`**: Conditional statements are implemented.
- ✅  **3-argument `for` loop**: The `for` loop with three arguments is supported.
- ✅  **`foreach` loop**: The `foreach` loop is implemented.
- ✅  **`while` and `until` loop**: `while` and `until` loops are supported.
- ✅  **`if` `unless` Statement modifiers**: Conditional modifiers for `if` and `unless` are implemented.
- ✅  **`while` `until` Statement modifiers**: Loop modifiers for `while` and `until` are supported.
- ✅  **`while` `until` Statement modifiers**: `last`, `redo`, `next` give an error `Can't "last" outside a loop block`.
- ✅  **`for` `foreach` Statement modifiers**: Loop modifiers for `for` and `foreach` are implemented.
- ✅  **`continue` blocks**: `continue` blocks in looks are implemented.
- ✅  **`try`/`catch`** try-catch is supported.
- ✅  **`eval` string with closure variables**: `eval` in string context with closures is supported.
- ✅  **`eval` string sets `$@` on error; returns `undef`**: `eval` sets `$@` on error and returns `undef`.
- ✅  **`eval` block**: `eval` blocks are implemented.
- ✅  **`do` block**: `do` blocks are supported.
- ✅  **`do` file**: File execution using `do` is implemented.
- ✅  **`do \&subroutine`**: is implemented.
- ✅  **`print` operators**: `print`, `printf` and `say` statements are implemented, with support for file handles.
- ✅  **`printf` and `sprintf`**: String formatting is implemented.
- ✅  **Short-circuit and, or**: Short-circuit logical operators are supported.
- ✅  **Low-precedence/high precedence operators**: Logical operators like `not`, `or`, `and`, `xor` are supported.
- ✅  **Ternary operator**: The ternary conditional operator is implemented.
- ✅  **Compound assignment operators**: Compound assignment operators are implemented.
- ✅  **`package` declaration**: `package BLOCK` is also supported.
- ✅  **Typeglob operations**: Operations like `*x = sub {}`, `*x = \@a`, `*x = *y` are supported.
- ✅  **Code references**: Code references like `\&subr`, `\&$subname`, `\&{$subname}`, are implemented.
- ✅  **Special literals**: `__PACKAGE__`, `__FILE__`, `__LINE__`
- ✅  **`die`, `warn` operators**: `die`, `warn` are supported.
- ✅  **`die` related features**: `$SIG{__DIE__}`, `$SIG{__WARN__}`
- ✅  **`die` with object**: `PROPAGATE` method is supported.
- ✅  **`exit`**: `exit` is supported.
- ✅  **`kill`**: `kill` is supported.
- ✅  **`waitpid`**: `waitpid` is partially supported.
- ✅  **`utime`**: `utime` is supported.
- ✅  **`umask`**: `umask` is supported.
- ✅  **`chown`**: `chown` is supported.
- ✅  **`readlink`**: `readlink` is supported.
- ✅  **`link`, `symlink`**: link is supported.
- ✅  **`rename`**: `rename` is supported.
- ✅  **`require` operator**: The `require` operator implemented; version checks are implemented.
- ✅  **`require` operator**: `pmc` files are supported.
- ✅  **`use` and `no` statements**: Module imports and version check via `use` and `no` are implemented; version checks are implemented. `use` arguments are executed at compile-time.
- ✅  **`use version`**: `use version` enables the corresponding features, strictures, and warnings.
- ✅  **Import methods**: `import` and `unimport` are implemented.
- ✅  **`__SUB__`**: The `__SUB__` keyword is implemented.
- ✅  **`BEGIN` block**: `BEGIN` special block is implemented.
- ✅  **`END` block**: `END` special block is implemented.
- ✅  **`INIT`**: special block is implemented.
- ✅  **`CHECK`**: special block is implemented.
- ✅  **`UNITCHECK`**: special block is implemented.
- ✅  **Labels**: Labels are implemented.
- ✅  **Here-docs**: Here-docs for multiline string literals are implemented.
- ✅  **Preprocessor**: `# line` directive is implemented.
- ✅  **`glob`**: `glob` operator is implemented.
- ✅  **`<>`**: `<>` operator is implemented.
- ✅  **`<$fh>`**: `<$fh>` and `<STDIN>` operators are implemented.
- ✅  **`<ARGV>`**: `ARGV` and $ARGV are implemented.
- ✅  **`<*.*>`**: `<*.*>` glob operator is implemented.
- ✅  **End of file markers**: Source code control characters `^D` and `^Z`, and the tokens `__END__` and `__DATA__` are implemented.
- ❌  **Startup processing**: processing `$sitelib/sitecustomize.pl` at startup is not enabled.
- ✅  **Smartmatch operator**: `~~` and `given`/`when` behavior is supported on both backends. See the rerunnable [audit probe](../../dev/tools/feature-audit/remaining_semantics.t).
- ✅  **File test operators**: `-R`, `-W`, `-X`, `-O` (for real uid/gid), this implementation assumes that the real user ID corresponds to the current user running the Java application.
- ✅  **File test operators**: `-t` (tty check), this implementation assumes that the -t check is intended to determine if the program is running in a TTY-compatible environment.
- ✅  **File test operators**: `-p`, `-S`, `-b`, and `-c` are approximated using file names or paths, as Java doesn't provide direct equivalents.
- ✅  **File test operators**: `-k` (sticky bit) is approximated using the "others execute" permission, as Java doesn't have a direct equivalent.
- ✅  **File test operators**: `-T` and `-B` (text/binary check) are implemented using a heuristic similar to Perl's approach.
- ✅  **File test operators**: Time-based operators (`-M`, `-A`, `-C`) return the difference in days as a floating-point number.
- ✅  **File test operators**: Using `_` as the argument reuses the last stat result.
- ✅  **File test operators**: Support stacked file test operators.
- ✅  **Directory operators**: `readdir`, `opendir`, `closedir`, `telldir`, `seekdir`, `rewinddir`, `mkdir`, `rmdir`, `chdir`.
- ✅  **`for` loop variable**: The `for` loop variable is aliased to list elements.
- ✅  **`for` loop variable**: Iterate over multiple values at a time is implemented.
- ✅  **`for` loop variable**: You can use fully qualified global variables as the variable in a for loop.
- ✅  **loop control operators**: `next LABEL`, `last LABEL`, `redo LABEL` with literal labels are implemented, including non-local control flow (jumping from subroutines to caller's loops).
- ✅  **`goto` operator**: `goto LABEL` with literal labels and `goto EXPR` with dynamic expressions are implemented.
- ✅  **`goto &name`**: Tail call optimization with trampoline is implemented.
- ✅  **`goto __SUB__`**: Recursive tail call is implemented.
- ✅  **loop control operators**: `next EXPR`, `last EXPR`, `redo EXPR` with dynamic expressions (e.g., `$label = "OUTER"; next $label`) are implemented.
- ✅  **setting `$_` in `while` loop with `<>`**: automatic setting `$_` in `while` loops is implemented.
- ✅  **`do BLOCK while`**: `do` executes once before the conditional is evaluated.
- ✅  **`...` ellipsis statement**: `...` is supported.
- ✅  **`system` operator**: `system` is implemented.
- ✅  **`exec` operator**: `exec` is implemented.
- ✅  **User/Group operators, Network info operators**: `getlogin`, `getpwnam`, `getpwuid`, `getgrnam`, `getgrgid`, `getpwent`, `getgrent`, `setpwent`, `setgrent`, `endpwent`, `endgrent`, `gethostbyname`, `gethostbyaddr`, `getservbyname`, `getservbyport`, `getprotobyname`, `getprotobynumber`.
- ✅  **Network enumeration operators**: `endhostent`, `endnetent`, `endprotoent`, `endservent`, `gethostent`, `getnetbyaddr`, `getnetbyname`, `getnetent`, `getprotoent`, `getservent`, `sethostent`, `setnetent`, `setprotoent`, `setservent`.
- ✅  **System V IPC operators**: `msgctl`, `msgget`, `msgrcv`, `msgsnd`, `semctl`, `semget`, `semop`, `shmctl`, `shmget`, `shmread`, `shmwrite`.
- ✅  **`format` operator**: `format` and `write` functions for report generation are implemented.
- ✅  **`formline` operator**: `formline` and `$^A` accumulator variable are implemented.

---

## I/O Operations

### Basic I/O Operators
- ✅  **`open`**: File opening is implemented with support for:
  - 2-argument forms: `<-`, `-`, `>-`, `filename`
  - 3-argument forms with explicit modes
  - In-memory files
  - support for pipe input and output like: `-|`, `|-`, `ls|`, `|sort`.
    - # forking patterns with `exec`:
        my $pid = open FH, "-|"; if ($pid) {...} else { exec @cmd }
        my $pid = open FH, "-|"; unless ($pid) { exec @cmd } ...
        open FH, "-|" or exec @cmd;
  - ✅ file descriptor duplication modes: `<&`, `>&`, `<&=`, `>&=` (duplicate existing file descriptors)

- ✅  **`readline`**: Reading lines from filehandles
  - ✅  Paragraph mode ($/ = '' - empty string)
  - ✅  Record length mode ($/ = \2, $/ = \$foo where $foo is a number)
  - ✅  Slurp mode ($/ = undef)
  - ✅  Multi-character string separators ($/ = "34")

- ✅  **`sysopen`**: File opening.
- ✅  **`eof`**: End-of-file detection
- ✅  **`close`**: Closing filehandles
- ✅  **`unlink`**: File deletion
- ✅  **`readpipe`**: Command output capture
- ✅  **`fileno`**: File descriptor retrieval
- ✅  **`getc`**: Character reading
- ✅  **`read`**: Block reading with length specification
- ✅  **`tell`**: Current file position
- ✅  **`select`**: `select(filehandle)` for default output selection
- ✅  **`select`**: `select(undef,undef,undef,$time)` for sleep function
- ✅  **`seek`**: File position manipulation.
- ✅  **`chmod`**: File permissions.
- ✅  **`sysread`**
- ✅  **`syswrite`**
- ✅  **Tied Handles**: Tied file handles are implemented. See also [Tied Scalars](#scalars), [Tied Arrays](#arrays-hashes-and-lists), [Tied Hashes](#arrays-hashes-and-lists).
- ✅  **`DATA`**: `DATA` file handle is implemented.
- ✅  **`truncate`**: File truncation
- ✅  **`flock`**: File locking with LOCK_SH, LOCK_EX, LOCK_UN, LOCK_NB
- ✅  **`fcntl`**: File control operations (stub + native via jnr-posix)
- ✅  **`ioctl`**: Device control operations (stub + native via jnr-posix)
- ✅  **`syscall`**: System calls (SYS_gethostname)

### Socket Operations
- ✅  **`socket`**: Socket creation with domain, type, and protocol support
- ✅  **`bind`**: Socket binding to addresses
- ✅  **`listen`**: Socket listening for connections
- ✅  **`accept`**: Connection acceptance
- ✅  **`connect`**: Socket connection establishment
- ✅  **`send`**: Data transmission over sockets
- ✅  **`recv`**: Data reception from sockets
- ✅  **`shutdown`**: Socket shutdown
- ✅  **`setsockopt`**: Socket option configuration
- ✅  **`getsockopt`**: Socket option retrieval
- ✅  **`getsockname`**: Local socket address retrieval
- ✅  **`getpeername`**: Remote socket address retrieval
- ✅  **`socketpair`**: Connected socket pair creation

- ✅  **`pipe`**: Internal pipe creation for inter-process communication

### Unimplemented I/O Operators

### I/O Layers
- ✅  **Layer support**: `open` and `binmode` support these I/O layers:
  - `:raw` - Binary mode, no translation
  - `:bytes` - Similar to :raw, ensures byte semantics
  - `:crlf` - Convert CRLF to LF on input, LF to CRLF on output
  - `:utf8` - UTF-8 encoding/decoding
  - `:unix` - Unix-style line endings (LF only)
  - `:encoding(ENCODING)` - Specific character encoding
- ✅  **Layer stacking**: Multiple layers can be combined (e.g., `:raw:utf8`)
- ✅  **Multibyte encoding support for `seek`, `tell`, `truncate`**: Representative encoded-handle positioning and truncation pass on both backends. See the [audit probe](../../dev/tools/feature-audit/multibyte_io.t); additional platform/encoding edge cases remain suitable for follow-up coverage.

### Supported Encodings
The `:encoding()` layer supports all encodings provided by Java's `Charset.forName()` method:

**Standard Charsets (guaranteed available):**
- `US-ASCII` - Seven-bit ASCII
- `ISO-8859-1` - ISO Latin Alphabet No. 1 (Latin-1)
- `UTF-8` - Eight-bit UCS Transformation Format
- `UTF-16BE` - Sixteen-bit UCS, big-endian byte order
- `UTF-16LE` - Sixteen-bit UCS, little-endian byte order
- `UTF-16` - Sixteen-bit UCS with optional byte-order mark

**Common Extended Charsets (usually available):**
- `windows-1252` - Windows Western European
- `ISO-8859-2` through `ISO-8859-16` - Various ISO Latin alphabets
- `Shift_JIS` - Japanese
- `EUC-JP` - Japanese
- `GB2312`, `GBK`, `GB18030` - Chinese
- `Big5` - Traditional Chinese
- `EUC-KR` - Korean
- `windows-1251` - Windows Cyrillic
- `KOI8-R` - Russian

---

## Namespaces and Global Variables
- ✅  **Global variable infrastructure**: Support for global variables is implemented.
- ✅  **Namespaces**: Namespace support is implemented.
- ✅  **Stash**: Stash can be accessed as a hash, like: `$namespace::{entry}`.
- ✅  **`@_` and `$@` special variables**: Special variables like `@_` and `$@` are supported.
- ✅  **Special variables**: The special variables `%ENV`, `@ARGV`, `@INC`, `$0`, `$_`, `$.`, `$]`, `$"`, `$\\`, `$,`, `$/`, `$$`, `$a`, `$b`, `$^O`, `$^V`, `$^X` are implemented.
- ✅  **I/O symbols**: `STDOUT`, `STDERR`, `STDIN`, `ARGV`, `ARGVOUT` are implemented.
- ✅  **Stash manipulation**: Alternative ways to create constants like: `$constant::{_CAN_PCS} = \$const`.
- ✅  **`reset("A-Z")`** resetting global variables is implemented.
- ✅  **Single-quote as package separator**: Legacy `$a'b` style package separator is supported.
- ✅  **Runtime-owned `@_`, `$_`, and regex state**: Each ithread receives an isolated runtime snapshot.
- 🟡  **Compiler hints and warning bits**: `$^H`, `%^H`, and
  `${^WARNING_BITS}` are tracked as lexical compile-time state and snapshots
  are exposed through the extended `caller` tuple on both backends. Some
  pragma-specific mutation and exact bitmask compatibility remain incomplete.
- ✅  **`caller` operator**: `caller` returns `($package, $filename, $line)`.
  - ✅  **Extended call stack information**: the full 11-field `caller($level)` tuple and key subroutine metadata are supported on both backends. See the [caller audit probe](../../dev/tools/feature-audit/caller_fields.t).<br>
    Exact hint and bitmask values remain runtime- and pragma-dependent.

---

## Perl Modules, Pragmas, Features

- ❌ **No direct Perl-to-Java interoperability**: PerlOnJava does not provide Perl-side mechanisms like `Inline::Java` for directly calling Java methods or instantiating Java objects from Perl code. You cannot write Perl code that directly accesses arbitrary Java libraries or JVM languages.

- ✅ **Java-implemented Perl modules via XSLoader**: However, Perl modules can load Java-implemented subroutines using the standard `XSLoader` mechanism. This allows you to:
  - Write Perl module implementations in Java that expose a Perl API
  - Use PerlOnJava's internal API to create Java classes that register themselves as Perl subroutines
  - Load these Java implementations transparently from Perl code using `XSLoader`
  
  **Example**: The DBI module demonstrates this pattern:
  - `DBI.pm` - Standard Perl module that uses `XSLoader::load('DBI')` 
  - `DBI.java` - Java implementation that registers methods like `connect`, `prepare`, `execute` as Perl subroutines
  - From Perl's perspective, it's using a normal XS module, but the implementation is actually Java code

  See [XS Compatibility](xs-compatibility.md) for a complete list of modules with Java implementations.


### Pragmas

- 🚧  **strict** pragma:.
  - ✅ all `use strict` modes are implemented.
  - ✅ `no strict vars`, `no strict subs` are implemented.
  - 🚧 `no strict refs` is partially implemented: scalar, glob references.
  - ❌ `no strict refs` works with global variables only. `my` variables can not be accessed by name.
- ✅  **parent** pragma
- ✅  **base** pragma
- ✅  **constant** pragma
- ✅  **experimental** pragma
- ✅  **if** pragma
- ✅  **lib** pragma
- ✅  **mro** (Method Resolution Order) pragma
- ✅  **vars** pragma
- ✅  **version** pragma
- ✅  **subs** pragma
- 🚧  **utf8** pragma: utf8 is always on. Disabling utf8 might work in a future version.
- 🚧  **bytes** pragma
- 🚧  **feature** pragma
  - ✅ Features implemented: `fc`, `say`, `current_sub`, `isa`, `state`, `try`, `defer`, `bitwise`, `postderef`, `postderef_qq`, `evalbytes`, `unicode_eval`, `refaliasing`, `module_true`, `signatures`, `class`, `keyword_all`, `keyword_any`.
  - ✅ `unicode_strings` (see the [audit probe](../../dev/tools/feature-audit/unicode_strings.t)).
- 🚧  **warnings** pragma
- 🚧  **attributes** pragma: `MODIFY_*_ATTRIBUTES`/`FETCH_*_ATTRIBUTES` callbacks for subroutines and variables.
- 🚧  **bignum** and **bigint** pragmas: basic checks pass on the JVM backend; the interpreter loses `bigint` precision and does not complete the basic `bignum` probe within the audit timeout. See the [bignum](../../dev/tools/feature-audit/numeric_bignum.t) and [bigint](../../dev/tools/feature-audit/numeric_bigint.t) probes.
- ✅  **bigrat** pragma: isolated rational-arithmetic probe passes on all backends; see the [audit probe](../../dev/tools/feature-audit/numeric_bigrat.t).
- ✅  **encoding** pragma: the supported encoding pragma forms pass the native/JVM/interpreter audit batch.
- ✅  **integer** pragma: native-width arithmetic and bitwise behavior pass the native/JVM/interpreter audit batch.
- ❌  **locale** pragma
- ❌  **ops** pragma
- 🚧  **re** pragma for regular expression options: Implemented `is_regexp`.
- 🚧  **vmsish** pragma.
- ✅  **subs** pragma.
- 🚧  **builtin** pragma:
  - ✅ Implemented: `true` `false` `is_bool` `inf` `nan` `weaken` `unweaken` `is_weak` `blessed` `refaddr` `reftype` `created_as_string` `created_as_number` `stringify` `ceil` `floor` `indexed` `trim` `is_tainted`.
  - ✅ `export_lexically`.
  - ❌ Missing: `load_module`
- 🚧  **overload** pragma:
  - ✅ Implemented: `""`, `0+`, `bool`, `fallback`, `nomethod`.
  - ✅ Implemented: `!`, `+`, `-`, `*`, `/`, `%`, `int`, `neg`, `log`, `sqrt`, `cos`, `sin`, `exp`, `abs`, `atan2`, `**`.
  - ✅ Implemented: `@{}`, `%{}`, `${}`, `&{}`, `*{}`.
  - ✅ Implemented: `<=>`, `cmp`, `<`, `<=`, `>`, `>=`, `==`, `!=`, `lt`, `le`, `gt`, `ge`, `eq`, `ne`.
  - ✅ Implemented: `qr`.
  - ✅ Implemented: `+=`, `-=`, `*=`, `/=`, `%=`.
  - ✅ Implemented: `<>`.
  - ✅ `++`, `.`, and `=` copy-constructor behavior pass focused audit tests.
  - ❌ Missing: `--`, `&`, `|`, `^`, `~`, `<<`, `>>`, `&.`, `|.`, `^.`, `~.`, `x`.
  - ❌ Missing: `**=`, `<<=`, `>>=`, `x=`, `.=`, `&=`, `|=`, `^=`, `&.=`, `|.=`, `^.=`.
  - ❌ Missing: `-X`.
- ✅  **overloading** pragma: lexical enable/disable behavior passes the focused audit batch.



### Core modules

- ✅  **Benchmark** use the same version as Perl.
- ✅  **Carp**: `carp`, `cluck`, `croak`, `confess`, `longmess`, `shortmess` are implemented.
- ✅  **Config** module.
- ✅  **threads** module: isolated create/join, identity, listing, detach,
  state inspection, child exit, errors, `async`, `yield`, and supported import
  options. See the [Perl threads reference](threads.md).
- ✅  **threads::shared** module: shared scalar/array/hash storage, including
  blessed aggregate roots and supported tied-value conversions, recursive
  lexical locks, condition variables, and supported graph cloning.
- ✅  **Thread::Queue** module: blocking, timed, nonblocking, force, limit,
  insert, extract, and error behavior.
- ✅  **Thread::Semaphore** module: blocking, timed, nonblocking, force, and
  error behavior.
- ✅  **Cwd** module
- ✅  **Data::Dumper**: use the same version as Perl.
- ✅  **DirHandle** module.
- ✅  **Dumpvalue** module.
- ✅  **Digest** module
- ✅  **Digest::MD5** module
- ✅  **Digest::SHA** module
- ✅  **Encode** module.
- ✅  **Env** module
- ✅  **Errno** module.
- ✅  **Exporter**: `@EXPORT_OK`, `@EXPORT`, `%EXPORT_TAGS` are implemented.
  - ❌ Missing: export `*glob`.
- ✅  **ExtUtils::MakeMaker** module: PerlOnJava version installs pure Perl modules directly.
- ✅  **Fcntl** module
- ✅  **FileHandle** module
- 🟡  **Filter::Simple and Filter::Util::Call**: closure filters installed by
  `use`, `FILTER`, and `FILTER_ONLY` are supported. Object/method filters are
  not yet applied, and `filter_read` uses buffered line-oriented emulation
  rather than Perl's incremental source stream. See the
  [source-filter design notes](../../dev/design/source_filters.md).
- ✅  **File::Basename** use the same version as Perl.
- ✅  **File::Find** use the same version as Perl.
- ✅  **File::Spec::Functions** module.
- ✅  **File::Spec** module.
- ✅  **Getopt::Long** module.
- ✅  **HTTP::Date** module.
- 🟡  **Internals**: `Internals::SvREADONLY` enforces read-only writes.
  `Scalar::Util::readonly` recognizes compile-time read-only values but does
  not yet recognize every scalar marked read-only at runtime.
- ✅  **IO::File** module.
- ✅  **IO::Seekable** module.
- ✅  **IO::Socket** module.
- ✅  **IO::Socket::INET** module.
- ✅  **IO::Socket::UNIX** module.
- ✅  **Socket6** compatibility module backed by the core `Socket` IPv6 implementation.
- ✅  **Net::Gen** XS compatibility bridge for the Net-ext socket modules.
- ✅  **IO::Zlib** module.
- ✅  **List::Util**: module.
- ✅  **MIME::Base64** module
- ✅  **MIME::QuotedPrint** module
- ✅  **Perl::OSType** module.
- ✅  **Scalar::Util**: `blessed`, `reftype`, `set_prototype`, `dualvar` are implemented.
- ✅  **SelectSaver**: module.
- ✅  **Storable**: module. Reads and writes the native Perl Storable binary format (`pst0` magic), interoperable with system perl in both directions. `STORABLE_freeze`/`STORABLE_thaw` hooks support extra references, and nested tied arrays, hashes, and scalars retain the correct reference depth. `$Storable::canonical` is not yet implemented (see `dev/modules/storable_binary_format.md`).
- ✅  **Sys::Hostname** module.
- ✅  **Symbol**: `gensym`, `qualify` and `qualify_to_ref` are implemented.
- ✅  **Term::ANSIColor** module.
- ✅  **Test** module.
- ✅  **Test::More** module.
- ✅  **Text::Balanced** use the same version as Perl.
- ✅  **Tie::Array** module.
- ✅  **Tie::Handle** module.
- ✅  **Tie::Hash** module.
- ✅  **Tie::Hash::Indexed** module, with a Java replacement for its XS backend.
- ✅  **Tie::Scalar** module.
- ✅  **Time::HiRes** module.
- ✅  **Time::UTC::Now** module, backed by `java.time.Instant`; unbounded clocks return an undefined accuracy bound as documented.
- ✅  **Time::Local** module.
- ✅  **UNIVERSAL**: `isa`, `can`, `DOES`, `VERSION` are implemented. `isa` operator is implemented.
- ✅  **URI::Escape** module.
- ✅  **Socket** module: IPv4/IPv6 socket constants and functions, including
  sockaddr packing, address presentation conversion, `getaddrinfo`, and
  `getnameinfo`.
- ⚠️  **Want** compatibility subset: scalar/list/void and the non-lvalue
  predicates needed by JSONP; full lvalue/op-tree introspection remains planned.
- ✅  **Email::Address::XS** compatibility subset used by Email::Sender.
- ✅  **Unicode::UCD** module.
- ✅  **XSLoader** module.
- 🚧  **DynaLoader** placeholder module.
- 🚧  **HTTP::Tiny** some features untested: proxy settings.
- 🚧  **POSIX** module.
- ✅  **Unicode::Normalize**: canonical and compatibility normalization passes the focused audit batch.
- ✅  **Archive::Tar** module.
- ✅  **Archive::Zip** module.
- ✅  **IPC::Open2** module.
- ✅  **IPC::Open3** module.
- ✅  **Net::FTP** module.
- ✅  **Net::Cmd** module.
- ✅  **Safe** module: permit-only and default sandbox behavior passes the focused audit batch.

### Non-core modules
- 🟡 **Object::Pad**: core class, field, method, parameter, and inheritance
  syntax is handled by PerlOnJava's native class compiler; Object::Pad-specific
  MOP extensions are not implemented.
- ✅  **JSON::DWIW**: relaxed JSON conversion implemented over the bundled
  pure-Perl `JSON::PP` backend.
- ✅  **Taint::Runtime**: Java XS replacement for runtime taint toggling and
  scalar taint inspection.
- ✅  **String::Similarity**: Java XS replacement for Unicode-aware string
  similarity scoring.
- ✅  **Text::Markdown::Hoedown**: Java XS replacement over commonmark-java,
  including HTML, table-of-contents, extension flags, and callback renderers.
- 🟡 **Authen::PAM**: the generated CPAN Perl API loads through a Java XS
  compatibility bridge and exposes PAM constants; native conversations are
  not yet implemented and return `PAM_SYSTEM_ERR`.
- ✅  **Crypt::Blowfish**: Java XS replacement backed by the bundled
  BouncyCastle engine, including the variable key sizes and 8-byte block API
  required by `Crypt::CBC`.
- ✅  **Proc::ProcessTable**: Java XS replacement for portable process
  enumeration and common process fields through `ProcessHandle`.
- ✅  **Crypt::Twofish2**: Java XS replacement backed by BouncyCastle, with
  upstream-compatible ECB, CBC, and CFB1 modes.
- ✅  **Tie::Array::Packed**: Java XS replacement for packed tied-array
  storage, mutation, splicing, rotation, and binary search.
- 🟡 **B::Flags**: portable OP/SV flag names over the bundled partial `B`
  model; host-Perl allocation flags are intentionally unavailable.
- ✅  **Data::Util**: Java-backed `is_value` and `is_string` preserve the
  native module's non-vivifying scalar inspection; the rest of the upstream
  API is supplied by its pure-Perl fallback.
- ✅  **Scalar::Type** module backed by PerlOnJava scalar metadata (replaces native XS).
- 🟡 **PadWalker**: `peek_sub`, `closed_over`, and `set_closed_over` use
  runtime-maintained lexical metadata on both backends; caller-pad APIs are
  not implemented.
- 🟡 **Devel::Caller**: `caller_cv` and caller argument compatibility are
  implemented for lexical tooling.
- 🟡 **Devel::LexAlias**: local and captured lexical cells can be aliased on
  both the JVM and interpreter backends.
- ✅  **HTTP::CookieJar** module.
- ✅  **JSON** module.
- ✅  **Cpanel::JSON::XS** module (JSON::PP-backed shim; same bundled encoder/decoder stack as `JSON`).
- ✅  **Text::CSV** module.
- ✅  **TOML** module.
- ✅  **XML::Parser** module backed by JDK SAX (replaces native libexpat XS).
- ✅  **XML::LibXSLT** core transformation API backed by JDK JAXP (replaces native libxslt XS).
- ✅  **YAML::PP** module.
- ✅  **YAML** module.
- ✅  **YAML::Syck** compatibility module backed by bundled `YAML::PP`.
- ✅  **IO::Socket::SSL** module backed by Java `javax.net.ssl` SSLEngine.
- ✅  **Net::SSLeay** module backed by Java security APIs (2327 CPAN tests pass).
- ✅  **Plack::Handler::Netty** PSGI web server with HTTP/HTTPS, streaming, 32k+ req/sec. See [Web Server Guide](../../examples/http_server_plack/README.md).

### DBI module

#### JDBC Integration
The DBI module provides seamless integration with JDBC drivers:
- Configure JDBC drivers: See [Adding JDBC Drivers](../guides/database-access.md#adding-jdbc-drivers)
- Connect to databases: See [Database Connection Examples](../guides/database-access.md#database-connection-examples)

#### Implemented Methods
- `connect`, `prepare`, `execute`
- `fetchrow_arrayref`, `fetchrow_array`, `fetchrow_hashref`, `selectrow_array`, `selectrow_arrayref`, `selectrow_hashref`
- `fetchall_arrayref`, `selectall_arrayref`, `fetchall_hashref`, `selectall_hashref`
- `rows`, `disconnect`, `err`, `errstr`, `state`, `do`, `finish`, `last_insert_id`
- `begin_work`, `commit`, `rollback`
- `bind_param`, `bind_param_inout`, `bind_col`, `bind_columns`
- `table_info`, `column_info`, `primary_key_info`, `foreign_key_info`, `type_info`
- `clone`, `ping`, `trace`, `trace_msg`
- `available_drivers`, `data_sources`, `get_info`
- `prepare_cached`, `connect_cached`

#### Database Handle Attributes
- `RaiseError`, `PrintError`, `Username`, `Password`, `Name`, `Active`, `Type`, `ReadOnly`, `Executed`, `AutoCommit`

#### Statement Handle Attributes
- `NAME`, `NAME_lc`, `NAME_uc`, `NUM_OF_FIELDS`, `NUM_OF_PARAMS`, `Database`

---

## Concurrency and Perl Threads

PerlOnJava implements Perl interpreter multiplicity and ithreads on both the JVM
compiler and bytecode interpreter backends. The complete unchanged upstream
test distributions for `threads`, `threads::shared`, `Thread::Queue`, and
`Thread::Semaphore` pass on virtual and platform Java carriers.

| Capability | Status | Notes |
|---|---|---|
| `Config` flags | ✅ | `useithreads`, `usethreads`, and `usemultiplicity` are `define`. |
| Runtime isolation | ✅ | Mutable globals, dynamic state, hints, warnings, regex state, lifecycle queues, signals, alarms, and I/O registries are runtime-owned. |
| `threads->create`, `async`, `join`, `detach` | ✅ | A child receives a snapshot; ordinary parent and child values then evolve independently. Join results are cloned back to the caller. |
| Identity and state | ✅ | `self`, `tid`, `list`, equality, running/joinable/detached checks, errors, nested threads, and child-only `threads->exit` are supported. |
| `threads::shared` | ✅ | `share`, `is_shared`, `shared_clone`, and `:shared` support scalar/array/hash graphs. Shared aggregate writes reject private references before mutation and accept references whose referents are already shared. Nested fetches use runtime-local proxy views over common backing; blessing, ties, weak views, cycles, and final destruction follow the classified shared-storage policies. Loading `threads::shared` without `threads` retains its inactive single-thread behavior. |
| `Thread::Queue` and `Thread::Semaphore` | ✅ | The unchanged upstream distributions pass their blocking, timed, nonblocking, force, limit, insert/extract, and error tests. |
| Locks and conditions | ✅ | Recursive lexical `lock`, `cond_wait`, absolute `cond_timedwait`, `cond_signal`, and `cond_broadcast` are supported. |
| Platform threads | ✅ | Explicit compatibility mode and automatic fallback for a nonzero stack-size request. |
| Virtual threads | ✅ | Java 24 launcher default; snapshot, lifecycle, shared-storage, native-callback, DBI, and Test2 gates retain platform parity. |

The clone-versus-share rule is important: ordinary references are cloned with
aliasing and cycles preserved inside the child graph, but they are not the same
storage as their parent counterparts. Values explicitly shared through
`threads::shared` retain common backing storage.

### JVM execution and resource policies

| Policy | Effect |
|---|---|
| Thread signals | `threads->kill` targets live attached children and resolves the handler inside the child runtime. Completed and detached targets are not signalable. |
| Effective stack sizing | Platform-backed children honor supported `stack_size` create/import requests. A nonzero request under the default virtual policy transparently selects a platform child. |
| Additional introspection | `threads->object` and creation-context `wantarray` are implemented. CLI shutdown reports running and finished unjoined threads; detached children are silent. |
| Native resources and callbacks | File, socket, process, native-descriptor, scalar, layered, duplicated, borrowed, directory, and standard handles have explicit inheritance policies. Net::SSLeay handles remain runtime-owned and stored callbacks bind their registering runtime. |
| Upstream suite coverage | **Four-mode release gate:** the four bundled thread distributions pass 64 files and 1,891 assertions in each backend/carrier configuration, and the five non-regex core thread files pass 849/849 in all four modes. `make test-threads-core` runs each of the twelve regex wrappers after its same-commit direct companion and rejects lost TAP, added failures or incompleteness, timeouts, and execution errors. The callout-enabled Joni engine is integrated; remaining direct regex-language gaps are tracked separately and unchanged wrappers remain preservation tests. The ecosystem gate covers pinned Test2, Storable, and Moose thread tests on both backends, DBI ownership under both backends/carriers, Net::SSLeay 61/62, and the available DBIx::Class corpus (325 files and 43,017 assertions). |
| PSGI | The default single-runtime handler advertises `psgi.multithread => \0`. A bounded opt-in pool gives every concurrent request an independent app snapshot and advertises `\1`; pool size defaults to zero. |

See the [Perl threads reference](threads.md) for behavior and test commands and
[Concurrency and runtime isolation](../../dev/design/concurrency.md) for the
maintenance contract.

---

## Features Incompatible with JVM

- ❌  **`fork` operator**: `fork` is not implemented. Calling `fork` will always fail and return `undef`.
- ✅  **`DESTROY`**: Implemented with selective reference counting on top of JVM GC. Supports cascading destruction, closure capture tracking, `weaken`/`isweak`/`unweaken`, global destruction phase, and `Internals::SvREFCNT` introspection.
- 🟡  **Perl `XS` ecosystem**: native C/XS binaries cannot run on the JVM.
  PerlOnJava supports a documented set of Java replacements loaded through
  `XSLoader` and pure-Perl fallbacks; see [XS Compatibility](xs-compatibility.md).
- 🚧  **Auto-close files**: Lexical buffered writes and fd closure pass on the JVM backend, but the interpreter backend still permits reopening the fd after the lexical handle goes out of scope. Explicit close and program-end cleanup remain supported. See the [scope probe](../../dev/tools/feature-audit/autoclose_scope.t) and [fd probe](../../dev/tools/feature-audit/autoclose_fd.t).
- ❌  **Keywords related to the control flow of the Perl program**: `dump` operator.
- ❌  **DBM file support**: `dbmclose`, `dbmopen` are not implemented.
- ❌  **Calling a class name** `package Test; Test->()` gives `Undefined subroutine &Test::Test called`.

---

## Optimizations

- ✅  **Cached string/numeric conversions**: Numification caching is implemented.
- ✅  **Java segment size limitation**: A workaround is implemented to Java 64k bytes segment limit.
- ❌  **Inline "constant" subroutines optimization**: Optimization for inline constants is not yet implemented.
- ❌  **Overload optimization**: Preprocessing in overload should be cached.
- ❌  **I/O optimization**: Use low-level readline to optimize input.
- ❌  **I/O optimization**: Extract I/O buffering code (StandardIO.java) into a new layer, and add it at the top before other layers.

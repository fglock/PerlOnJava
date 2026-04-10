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
14. [Features Incompatible with JVM](#features-incompatible-with-jvm)
15. [Optimizations](#optimizations)

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
- Method Resolution Order

🚧 Partially Supported:
- Warnings and strict pragma
- Some core modules and pragmas
- File operations and I/O
- Overload
- `format` operator

❌ Not Supported:
- XS modules and C integration
- Threading

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
- ✅  Accept command line switches: `-c`, `-e`, `-E`, `-p`, `-n`, `-i`, `-I`, `-0`, `-a`, `-d`, `-f`, `-F`, `-m`, `-M`, `-g`, `-l`, `-h`, `-s`, `-S`, `-x`, `-v`, `-V`, `-?`, `-w`, `-W`, `-X` are implemented.
- ❌  Missing command line switches include:
  - `-T`: Taint checks.
  - `-t`: Taint checks with warnings.
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
- ❌  **Taint checks**: Support for taint checks is not implemented.
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
- ✅  **`DESTROY`**: Destructor methods with cooperative reference counting.

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
- ✅  **\b{gcb} \B{gcb}**: Boundary assertions.
- ✅  **Variable Interpolation in Regex**: Features like `${var}` for embedding variables.
- ✅  **Non-capturing groups**: `(?:...)` is implemented.
- ✅  **Named Capture Groups**: Defining named capture groups using `(?<name>...)` or `(?'name'...)` is supported.
- ✅  **Backreferences to Named Groups**: Using `\k<name>` or `\g{name}` for backreferences to named groups is supported.
- ✅  **Relative Backreferences**: Using `\g{-n}` for relative backreferences.
- ✅  **Unicode Properties**: Matching with `\p{...}` and `\P{...}` (e.g., `\p{L}` for letters).
- ✅  **Unicode Properties**: Add regex properties supported by Perl but missing in Java regex.
- ✅  **Possessive Quantifiers**: Quantifiers like `*+`, `++`, `?+`, or `{n,m}+`, which disable backtracking, are not supported.
- ✅  **Atomic Grouping**: Use of `(?>...)` for atomic groups is supported.
- ✅  **`\K` assertion**: Keep left — in `s///`, text before `\K` is preserved; match variables reflect only the portion after `\K`.
- ✅  **Preprocessor**: `\Q`, `\L`, `\U`, `\l`, `\u`, `\E` are preprocessed in regex.
- ✅  **Overloading**: `qr` overloading is implemented. See also [overload pragma](#pragmas).

### Missing Regular Expression Features

- ❌  **Dynamically-scoped regex variables**: Regex variables are not dynamically-scoped.
- ❌  **Recursive Patterns**: Features like `(?R)`, `(?0)` or `(??{ code })` for recursive matching are not supported.
- ❌  **Backtracking Control**: Features like `(?>...)`, `(?(DEFINE)...)`, or `(?>.*)` to prevent or control backtracking are not supported.
- ❌  **Lookbehind Assertions**: Variable-length negative or positive lookbehind assertions, e.g., `(?<=...)` or `(?<!...)`, are not supported.
- ❌  **Branch Reset Groups**: Use of `(?|...)` to reset group numbering across branches is not supported.
- ❌  **Advanced Subroutine Calls**: Sub-pattern calls with numbered or named references like `(?1)`, `(?&name)` are not supported.
- ❌  **Conditional Expressions**: Use of `(?(condition)yes|no)` for conditional matching is not supported.
- ❌  **Extended Unicode Regex Features**: Some extended Unicode regex functionalities are not supported.
- ❌  **Extended Grapheme Clusters**: Matching with `\X` for extended grapheme clusters is not supported.
- ❌  **Embedded Code in Regex**: Inline Perl code execution with `(?{ code })` or `(??{ code })` is not supported.
- ❌  **Regex Debugging**: Debugging patterns with `use re 'debug';` to inspect regex engine operations is not supported.
- ❌  **Regex Optimizations**: Using `use re 'eval';` for runtime regex compilation is not supported.
- ❌  **Regex Compilation Flags**: Setting default regex flags with `use re '/flags';` is not supported.
- ❌  **Stricter named captures**
  - ❌  **No underscore in named captures** `(?<test_field>test)` the name in named captures cannot have underscores.
  - ❌  **No duplicate named capture groups**: In Java regular expression engine, each named capturing group must have a unique name within a regular expression.


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
- ❌  **Smartmatch operator**: `~~` and `given`/`when` construct are not implemented.
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
- ❌  **loop control operators**: `next EXPR`, `last EXPR`, `redo EXPR` with dynamic expressions (e.g., `$label = "OUTER"; next $label`) are not implemented.
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
- ❌  **Multibyte encoding support for `seek`, `tell`, `truncate`**: These operations are not yet implemented for multibyte encodings.

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
- ❌  **Thread-safe `@_`, `$_`, and regex variables**: Thread safety for global special variables is missing.
- ❌  **Compiler flags**:  The special variables `$^H`, `%^H`, `${^WARNING_BITS}` are not implemented.
- ✅  **`caller` operator**: `caller` returns `($package, $filename, $line)`.
  - ❌  **Extended call stack information**: extra debug information like `(caller($level))[9]` is not implemented.<br>
    This means we don't include subroutine names in error messages yet.<br>
    Extra debug information: `($package, $filename, $line, $subroutine, $hasargs, $wantarray, $evaltext, $is_require, $hints, $bitmask, $hinthash)`

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
  - ✅ Features implemented: `fc`, `say`, `current_sub`, `isa`, `state`, `try`, `defer`, `bitwise`, `postderef`, `evalbytes`, `module_true`, `signatures`, `class`, `keyword_all`, `keyword_any`.
  - ❌ Features missing: `postderef_qq`, `unicode_eval`, `unicode_strings`, `refaliasing`.
- 🚧  **warnings** pragma
- 🚧  **attributes** pragma: `MODIFY_*_ATTRIBUTES`/`FETCH_*_ATTRIBUTES` callbacks for subroutines and variables.
- ❌  **bignum, bigint, and bigrat** pragmas
- ❌  **encoding** pragma
- ❌  **integer** pragma
- ❌  **locale** pragma
- ❌  **ops** pragma
- 🚧  **re** pragma for regular expression options: Implemented `is_regexp`.
- 🚧  **vmsish** pragma.
- ✅  **subs** pragma.
- 🚧  **builtin** pragma:
  - ✅ Implemented: `true` `false` `is_bool` `inf` `nan` `weaken` `unweaken` `is_weak` `blessed` `refaddr` `reftype` `created_as_string` `created_as_number` `stringify` `ceil` `floor` `indexed` `trim` `is_tainted`.
  - ❌ Missing: `export_lexically`, `load_module`
- 🚧  **overload** pragma:
  - ✅ Implemented: `""`, `0+`, `bool`, `fallback`, `nomethod`.
  - ✅ Implemented: `!`, `+`, `-`, `*`, `/`, `%`, `int`, `neg`, `log`, `sqrt`, `cos`, `sin`, `exp`, `abs`, `atan2`, `**`.
  - ✅ Implemented: `@{}`, `%{}`, `${}`, `&{}`, `*{}`.
  - ✅ Implemented: `<=>`, `cmp`, `<`, `<=`, `>`, `>=`, `==`, `!=`, `lt`, `le`, `gt`, `ge`, `eq`, `ne`.
  - ✅ Implemented: `qr`.
  - ✅ Implemented: `+=`, `-=`, `*=`, `/=`, `%=`.
  - ❌ Missing: `++`, `--`, `=`, `<>`.
  - ❌ Missing: `&`, `|`, `^`, `~`, `<<`, `>>`, `&.`, `|.`, `^.`, `~.`, `x`, `.`.
  - ❌ Missing: `**=`, `<<=`, `>>=`, `x=`, `.=`, `&=`, `|=`, `^=`, `&.=`, `|.=`, `^.=`.
  - ❌ Missing: `-X`.
  - ❌ Missing: `=` copy constructor for mutators.
- ❌  **overloading** pragma



### Core modules

- ✅  **Benchmark** use the same version as Perl.
- ✅  **Carp**: `carp`, `cluck`, `croak`, `confess`, `longmess`, `shortmess` are implemented.
- ✅  **Config** module.
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
- ✅  **Filter::Simple** module: `FILTER` and `FILTER_ONLY` for source code filtering.
- ✅  **File::Basename** use the same version as Perl.
- ✅  **File::Find** use the same version as Perl.
- ✅  **File::Spec::Functions** module.
- ✅  **File::Spec** module.
- ✅  **Getopt::Long** module.
- ✅  **HTTP::Date** module.
- ✅  **Internals**: `Internals::SvREADONLY` is implemented as a no-op.
- ✅  **IO::File** module.
- ✅  **IO::Seekable** module.
- ✅  **IO::Socket** module.
- ✅  **IO::Socket::INET** module.
- ✅  **IO::Socket::UNIX** module.
- ✅  **IO::Zlib** module.
- ✅  **List::Util**: module.
- ✅  **MIME::Base64** module
- ✅  **MIME::QuotedPrint** module
- ✅  **Perl::OSType** module.
- ✅  **Scalar::Util**: `blessed`, `reftype`, `set_prototype`, `dualvar` are implemented.
- ✅  **SelectSaver**: module.
- ✅  **Storable**: module.
- ✅  **Sys::Hostname** module.
- ✅  **Symbol**: `gensym`, `qualify` and `qualify_to_ref` are implemented.
- ✅  **Term::ANSIColor** module.
- ✅  **Test** module.
- ✅  **Test::More** module.
- ✅  **Text::Balanced** use the same version as Perl.
- ✅  **Tie::Array** module.
- ✅  **Tie::Handle** module.
- ✅  **Tie::Hash** module.
- ✅  **Tie::Scalar** module.
- ✅  **Time::HiRes** module.
- ✅  **Time::Local** module.
- ✅  **UNIVERSAL**: `isa`, `can`, `DOES`, `VERSION` are implemented. `isa` operator is implemented.
- ✅  **URI::Escape** module.
- ✅  **Socket** module: socket constants and functions (`pack_sockaddr_in`, `unpack_sockaddr_in`, `sockaddr_in`, `inet_aton`, `inet_ntoa`, `gethostbyname`).
- ✅  **Unicode::UCD** module.
- ✅  **XSLoader** module.
- 🚧  **DynaLoader** placeholder module.
- 🚧  **HTTP::Tiny** some features untested: proxy settings.
- 🚧  **POSIX** module.
- 🚧  **Unicode::Normalize** `normalize`, `NFC`, `NFD`, `NFKC`, `NFKD`.
- ✅  **Archive::Tar** module.
- ✅  **Archive::Zip** module.
- ✅  **IPC::Open2** module.
- ✅  **IPC::Open3** module.
- ✅  **Net::FTP** module.
- ✅  **Net::Cmd** module.
- ❌  **Safe** module.

### Non-core modules
- ✅  **HTTP::CookieJar** module.
- ✅  **JSON** module.
- ✅  **Text::CSV** module.
- ✅  **TOML** module.
- ✅  **XML::Parser** module backed by JDK SAX (replaces native libexpat XS).
- ✅  **YAML::PP** module.
- ✅  **YAML** module.
- ✅  **IO::Socket::SSL** module backed by Java `javax.net.ssl` SSLEngine.
- ✅  **Net::SSLeay** module backed by Java security APIs (2327 CPAN tests pass).

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

## Features Incompatible with JVM

- ❌  **`fork` operator**: `fork` is not implemented. Calling `fork` will always fail and return `undef`.
- ✅  **`DESTROY`**: Implemented with cooperative reference counting on top of JVM GC. Supports cascading destruction, closure capture tracking, `weaken`/`isweak`/`unweaken`, and global destruction phase.
- ❌  **Perl `XS` code**: XS code interfacing with C is not supported on the JVM.
- ❌  **Auto-close files**: File auto-close depends on handling of object destruction, may be incompatible with JVM garbage collection. All files are closed before the program ends.
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


# Perl on JVM Feature Matrix

# Table of Contents

1. [Compiler Usability](#compiler-usability)
2. [Testing](#testing)
3. [Autovivification](#autovivification)
3. [Scalars](#scalars)
4. [Objects](#objects)
5. [Operators](#operators)
6. [Arrays, Hashes, and Lists](#arrays-hashes-and-lists)
7. [Subroutines](#subroutines)
8. [Regular Expressions](#regular-expressions)
9. [Statements and Special Operators](#statements-and-special-operators)
10. [I/O Operations](#io-operations)
11. [Namespaces and Global Variables](#namespaces-and-global-variables)
12. [Perl Modules, Pragmas, Features](#perl-modules-pragmas-features)
- [Pragmas](#pragmas)
- [Core modules](#core-modules)
- [Non-core modules](#non-core-modules)
- [DBI module](#dbi-module)
13. [Non-strict and Obsolete Features](#non-strict-and-obsolete-features)
14. [Features Probably Incompatible with JVM](#features-probably-incompatible-with-jvm)
15. [Language Differences and Workarounds](#language-differences-and-workarounds)
16. [Optimizations](#optimizations)

## Summary

PerlOnJava implements most core Perl features with some key differences:

✅ Fully Supported:
- Core language features (variables, loops, conditionals, subroutines)
- Most operators and built-in functions
- Basic OOP with packages, inheritance, and method calls
- Regular expressions (most features)
- DBI with JDBC integration
- Subroutine prototypes

🚧 Partially Supported:
- Warnings and strict pragma
- Some core modules and pragmas
- Method Resolution Order (C3 only)
- File operations and I/O

❌ Not Supported:
- XS modules and C integration
- Threading
- Some Perl features (formats, tied variables)
- Some system-level operations (fork)

## Compiler Usability
- ✅  **Wrapper scripts**: (jperl/jperl.bat) for easier command-line usage.
- ✅  **Perl-like compile-time error messages**: Error messages mimic those in Perl for consistency.
- ✅  **Perl line numbers in bytecode**: Bytecode includes line numbers for better debugging.
- ✅  **Perl-like runtime error messages**: Runtime errors are formatted similarly to Perl's.
- ✅  **Comments**: Support for comments and POD (documentation) in code is implemented.
- ❌  **Perl-like warnings**: Internal support for most warnings is missing. Warnings need to be formatted to resemble Perl's output.
- ❌  **Perl debugger**: The built-in Perl debugger (`perl -d`) is not implemented..


### Command line switches

- ✅  Accept command line switches from the shebang line.
- ✅  Command line switches `-c`, `-e`, `-E`, `-p`, `-n`, `-i`, `-I`, `-0`, `-a`, `-F`, `-m`, `-M`, `-g`, `-l`, `-h`, `-S`, `-x`, `-v`, `-V`, `-?` are implemented.
- ❌  Missing command line switches include:
  - `-s`: Rudimentary switch parsing.
  - `-T`: Taint checks.
  - `-t`: Taint checks with warnings.
  - `-u`: Dumps core after compiling.
  - `-U`: Allows unsafe operations.
  - `-W`: Enables all warnings.
  - `-X`: Disables all warnings.
  - `-w`: Enables warnings.
  - `-d[t][:debugger]`: Runs the program under the debugger.
  - `-D[number/list]`: Sets debugging flags.
  - `-f`: Suppresses execution of `sitecustomize.pl`.
  - `-C [number/list]`: Controls Unicode features.

## Testing
- ✅  **TAP tests**: Running standard Perl testing protocol.
- ✅  **CI/CD**: Github testing pipeline in Ubuntu and Windows.


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


## Scalars
- ✅  **`my` variable declaration**: Local variables can be declared using `my`.
- ✅  **`our` variable declaration**: Global variables can be declared using `our`.
- ✅  **`local` variable declaration**: Dynamic variables are implemented.
- ✅  **`state` variable declaration**: State variables are implemented. State variables are initialized only once.
- ✅  **Variable assignment**: Basic variable assignment is implemented.
- ✅  **Basic types**: Support for integers, doubles, strings, v-strings, regex, CODE, undef, references is present.
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
- ✅  **`local` special cases**: `local` works for typeglobs and filehandles.
- ✅  **Typeglob as hash**: `*$val{$k}` for `SCALAR`, `ARRAY`, `HASH`, `CODE`, `IO` is implemented.
- ✅  **Use string as a scalar reference**: Support for scalar references from strings is implemented.
- ❌  **Tied Scalars**: Support for tying scalars to classes is missing.
- ❌  **Taint checks**: Support for taint checks is not implemented.
- ❌  **`local` special cases**: `local *HANDLE = *HANDLE` doesn't create a new typeglob.
- ❌  **Variable attributes**: Variable attributes are not yet supported.

## Objects
- ✅  **Objects**: Creating classes, method call syntax works.
- ✅  **Object operators**: `ref` and `bless`
- ✅  **Special variables**: `@ISA` is implemented.
- ✅  **Multiple Inheritance**: C3 method resolution is implemented.
- ✅  **Method caching**: Method resolution is cached.
- ✅  **Version check**: Method `VERSION ( [ REQUIRE ] )` is implemented.
- ✅  **Inheritance**: `SUPER::method` is implemented.
- ✅  **Autoload**: `AUTOLOAD` mechanism is implemented; `$AUTOLOAD` variable is implemented.
- ✅  **`class`**: `class` token is supported.
- ❌  **`__CLASS__`**: `__CLASS__` token is not yet supported.
- ❌  **`:isa`**: `:isa` class attribute is not yet supported.
- ❌  **`method`**: `method` block is not yet supported.
- ❌  **`field`**: `field` token is not yet supported.
- ❌  **`:param`**: `:param` field attribute is not yet supported.
- ❌  **`:reader`**: `:reader` field attribute is not yet supported.
- ❌  **`ADJUST`**: `ADJUST` block is not yet supported.

## Operators
- ✅  **Simple arithmetic**: Operators like `+`, `-`, `*`, and `%` are supported.
- ✅  **Numeric Comparison operators**: Comparison operators such as `==`, `!=`, `>`, `<`, etc., are implemented.
- ✅  **defined-or**: `//` operator.
- ✅  **low-precedence-xor**: `^^` and `^^=` operator.
- ✅  **String concat**: Concatenation of strings using `.` is supported.
- ✅  **String Comparison operators**: String comparison operators such as `eq`, `ne`, `lt`, `gt`, etc., are implemented.
- ✅  **`q`, `qq`, `qw`, `qx` String operators**: Various string quoting mechanisms are supported.
- ✅  **Bitwise operators**: Bitwise operations like `~`, `&`, `|`, `^`, `~.`, `&.`, `|.`, `^.`, `<<`, and `>>` are supported.
- ✅  **Bitwise operators**: Bitwise integer and string operations are implemented.
- ✅  **Bitwise operators return unsigned**: Emulate unsigned integers.
- ✅  **Autoincrement, Autodecrement; String increment**: Increment and decrement operators, including for strings, are implemented.
- ✅  **Scalar string and math operators**: `quotemeta`, `ref`, `undef`, `log`, `rand`, `oct`, `hex`, `ord`, `chr`, `int`, `sqrt`, `cos`, `sin`, `exp`, `atan2`, `lc`, `lcfirst`, `uc`, `ucfirst`, `chop`, `fc`, `index`, `rindex`, `prototype`.
- ✅  **`join`**: Join operator for combining array elements into a string is supported.
- ✅  **`sprintf`**: String formatting is supported.
- ✅  **`grep`, `map`, `sort`**: List processing functions are implemented.
- ✅  **`substr`**: Substring extraction works.
- ✅  **Time-related functions**: `time`, `times`, `gmtime`, `localtime` are implemented.
- ✅  **`pack` and `unpack` operators**: `pack` and `unpack` are implemented.
- ✅  **`crypt` operator**: `crypt` is implemented.
- ✅  **`study`, `srand`**: `study`, `srand` are implemented.
- ✅  **`chomp`**: `chomp` is implemented.
- ✅  **`sleep`**: `sleep` is implemented. It takes fractional seconds. `$SIG{ALRM}` is also implemented.
- ✅  **`stat`**: `stat`, `lstat` are implemented. Some fields are not available in JVM and return `undef`.
- ✅  **Vectors**: `vec` is implemented.
- ✅  **Lvalue `substr`**: Assignable Substring extraction is implemented.
- ✅  **Lvalue `vec`**: Assignable `vec` is implemented.
- ✅  **Chained operators**: operations like `$x < $y <= $z` are implemented.

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
- ✅  **`scalar`**: Operator to get scalar value is not implemented.
- ✅  **Array dereference**: Dereferencing arrays using `@$x`.
- ✅  **Hash dereference**: Dereferencing hashes using `%$x`.
- ✅  **Basic Array Operations**: `push`, `unshift`, `pop`, `shift`, `splice`, `reverse` are implemented.
- ✅  **Slices**: Array and Hash slices like `@array[2, 3]` and `@hash{"a", "b"}` are implemented.
- ✅  **Array literals**: Array literals are supported.
- ✅  **Basic Hash Operations**: `keys`, `values`, `delete`, `exists`, `each` are implemented.
- ✅  **Hash literals**: Hash literals are supported.
- ✅  **List operator `..`**: List constructors are implemented.
- ✅  **Flip-flop operator `..` and `...`**: The flip-flop operators are implemented.
- ✅  **`$#array`**: Lvalue array count is implemented: `$#{$sl} = 10`.
- ✅  **Array exists**: `exists` for array indexes is implemented.
- ✅  **Array delete**: `delete` for array indexes is implemented.
- ❌  **Tied Arrays**: Tied arrays are not yet implemented.
- ❌  **Tied Hashes**: Tied hashes are not yet implemented.

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
- ❌  **CORE operator references**: Taking a reference to a `CORE` operator is not implemented: `BEGIN { *shove = \&CORE::push; } shove @array, 1,2,3;`
- ✅  **CORE::GLOBAL namespace**: `CORE::GLOBAL` and core function overrides are implemented.
- ✅  **alternate subroutine call syntax**: `&$sub`, `&$sub(args)` syntax is implemented.
- ✅  **Subroutine prototypes**: Prototypes `$`, `@`, `%`, `&`, `;`, `_`, `+`, `*`, `\@`, `\%`, `\$`, `\[@%]`, empty string and undef are supported.
- ✅  **Subroutine signatures**: Formal parameters are implemented.
- 🚧  **Subroutine attributes**: `prototype` is implemented. Other subroutine attributes are not yet supported.
- ✅  **`lvalue` subroutines**: Subroutines with attribute `:lvalue` are supported.
- ✅  **`Forcing main package`**: Identifiers starting with `::` are in `main` package.
- ❌  **Lexical subroutines**: Subroutines declared `my`, `state`, or `our` are not yet supported.

## Regular Expressions
- ✅  **Basic Matching**: Operators `qr//`, `m//`, `s///`, `split` are implemented.
- ✅  **Regex modifiers**: Modifiers `/p` `/i` `/m` `/s` `/n` `/g` `/c` `/r` `/e` `/ee` `/x` `/xx` are implemented.
- ✅  **Special variables**: The special variables `$1`, `$2`... are implemented.
- ✅  **Transliteration**: `tr` and `y` transliteration operators are implemented.
- ✅  **`pos`**: `pos` operator is implemented.
- ✅  **`\G`**: `\G` operator in regex is implemented.
- ✅  **`\N{name}`**: `\N{name}` and `\N{U+hex}` operator for named characters in regex is implemented.
- ✅  **lvalue `pos`**: lvalue `pos` operator is implemented.
- ✅  **`m?pat?`** one-time match is implemented.
- ✅  **`reset`** resetting one-time match is implemented
- ✅  **`@-`, `@+`, `%+`, `%-` variables**: regex special variables are implemented
- ✅  **`$&` variables**: `` $` ``, `$&`, `$'`, `$+` special variables are implemented
- ✅  **`[[:pattern:]]`**: `[[:ascii:]]`, `[[:print:]]` are implemented.
- ✅  **Matching plain strings**: `$var =~ "Test"` is implemented.
- ✅  **Inline comments**: `(?#comment)` in regex is implemented.
- ✅  **caret modifier**: `(?^` embedded pattern-match modifier, shorthand equivalent to "d-imnsx".
- ✅  **\b inside character class**: `[\b]` is supported in regex.
- ✅  **Variable Interpolation in Regex**: Features like `${var}` for embedding variables.
- ✅  **Non-capturing groups**: `(?:...)` is implemented.
- ✅  **Named Capture Groups**: Defining named capture groups using `(?<name>...)` or `(?'name'...)` is supported.
- ✅  **Backreferences to Named Groups**: Using `\k<name>` or `\g{name}` for backreferences to named groups is supported.
- ✅  **Relative Backreferences**: Using `\g{-n}` for relative backreferences.
- ✅  **Unicode Properties**: Matching with `\p{...}` and `\P{...}` (e.g., `\p{L}` for letters).
- ✅  **Possessive Quantifiers**: Quantifiers like `*+`, `++`, `?+`, or `{n,m}+`, which disable backtracking, are not supported.
- ✅  **Atomic Grouping**: Use of `(?>...)` for atomic groups is supported.

### Missing Regular Expression Features

- ❌  **Dynamically-scoped regex variables**: Regex variables are not dynamically-scoped.
- ❌  **Underscore in named captures** `(?<test_field>test)` the name in named captures cannot have underscores.
- ❌  **Recursive Patterns**: Features like `(?R)`, `(?0)` or `(??{ code })` for recursive matching are not supported.
- ❌  **Backtracking Control**: Features like `(?>...)`, `(?(DEFINE)...)`, or `(?>.*)` to prevent or control backtracking are not supported.
- ❌  **Lookbehind Assertions**: Variable-length negative or positive lookbehind assertions, e.g., `(?<=...)` or `(?<!...)`, are not supported.
- ❌  **Branch Reset Groups**: Use of `(?|...)` to reset group numbering across branches is not supported.
- ❌  **Advanced Subroutine Calls**: Sub-pattern calls with numbered or named references like `(?1)`, `(?&name)` are not supported.
- ❌  **Conditional Expressions**: Use of `(?(condition)yes|no)` for conditional matching is not supported.
- ❌  **Extended Unicode Regex Features**: Beyond basic Unicode escape support, extended Unicode regex functionalities are not supported.
- ❌  **Extended Grapheme Clusters**: Matching with `\X` for extended grapheme clusters is not supported.
- ❌  **Regex Debugging**: Support for features like `use re 'debug';` to visualize the regex engine’s operation is not supported.
- ❌  **Embedded Code in Regex**: Inline Perl code execution with `(?{ code })` or `(??{ code })` is not supported.
- ❌  **Regex Debugging**: Debugging patterns with `use re 'debug';` to inspect regex engine operations is not supported.
- ❌  **Regex Optimizations**: Using `use re 'eval';` for runtime regex compilation is not supported.
- ❌  **Regex Compilation Flags**: Setting default regex flags with `use re '/flags';` is not supported.
- ❌  **Overloading**: `qr` overloading is not implemented.
- ❌  **Duplicate named capture groups**: Java's regular expression engine does not support duplicate named capture groups. In Java, each named capturing group must have a unique name within a regular expression.


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
- ✅  **`exit`**: `exit` is supported.
- ❌  **`PROPAGATE`**: `PROPAGATE` method is not yet supported.
- ✅  **`require` operator**: The `require` operator implemented; version checks are implemented.
- ✅  **`use` and `no` statements**: Module imports and version check via `use` and `no` are implemented; version checks are implemented. `use` arguments are executed at compile-time.
- ✅  **`use version`**: `use version` enables the corresponding features, strictures, and warnings.
- ✅  **Import methods**: `import`, `unimport` works.
- ✅  **`__SUB__`**: The `__SUB__` keyword works.
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
- ✅  **End of file markers**: Source code control characters `^D` and `^Z`, and the tokens `__END__` and `__DATA__` are implemented. There is no `DATA` file handle yet.
- ❌  **Startup processing**: processing `$sitelib/sitecustomize.pl` at startup is not enabled.
- ❌  **Smartmatch operator**: `~~` and `given`/`when` construct are not implemented.
- ✅  **File test operators**: `-R`, `-W`, `-X`, `-O` (for real uid/gid), this implementation assumes that the real user ID corresponds to the current user running the Java application.
- ✅  **File test operators**: `-t` (tty check), this implementation assumes that the -t check is intended to determine if the program is running in a TTY-compatible environment.
- ✅  **File test operators**: `-p`, `-S`, `-b`, and `-c` are approximated using file names or paths, as Java doesn't provide direct equivalents.
- ✅  **File test operators**: `-k` (sticky bit) is approximated using the "others execute" permission, as Java doesn't have a direct equivalent.
- ✅  **File test operators**: `-T` and `-B` (text/binary check) are implemented using a heuristic similar to Perl's approach.
- ✅  **File test operators**: Time-based operators (`-M`, `-A`, `-C`) return the difference in days as a floating-point number.
- ✅  **File test operators**: Using `_` as the argument reuses the last stat result.
- 🚧  **File test operators**: The current implementation only works with file paths, not filehandles or dirhandles.
- ✅  **File test operators**: Support stacked file test operators.
- ✅  **Directory operators**: `readdir`, `opendir`, `closedir`, `telldir`, `seekdir`, `rewinddir`, `mkdir`, `rmdir`, `chdir`.
- ✅  **`for` loop variable**: The `for` loop variable is aliased to list elements.
- ✅  **`for` loop variable**: Iterate over multiple values at a time is implemented.
- ❌  **`for` loop variable**: If the variable used in a `for` loop is a global variable, it gets converted to an `our` variable before being linked to the elements of the list. This change means the variable is no longer global, which can lead to issues. For instance, if you expect to use the `$_` variable within a subroutine call, this behavior might cause problems.
- ❌  **`for` loop variable**: You cannot use fully qualified global variables as the variable in a for loop.
- ✅  **loop control operators**: `next`, `last`, `redo` with labels are implemented.
- ❌  **loop control operators**: `next`, `last`, `redo` with expression are not implemented.
- ❌  **loop control operators**: `next`, `last`, `redo` going to a different place in the call stack are not implemented. Label searching in the call stack is missing.
- ✅  **`goto &name`**: `goto &name` is implemented. It is not a tail-call.
- ✅  **`goto` operator**: `goto LABEL` is implemented.
- ❌  **`goto` operator**: `goto EXPR` is not implemented.
- ❌  **`goto` operator**: `goto` going to a different place in the call stack is not implemented. Label searching in the call stack is missing.
- ✅  **setting `$_` in `while` loop with `<>`**: automatic setting `$_` in `while` loops is implemented.
- ✅  **`do BLOCK while`**: `do` executes once before the conditional is evaluated.
- ✅  **`...` ellipsis statement**: `...` is supported.
- ✅  **`system` operator**: `system` is implemented.
- ✅  **`exec` operator**: `exec` is implemented.
- ❌  **`system` operator**: Indirect object notation for `system` and `exec` is not implemented.

## I/O Operations

### Basic I/O Operators
- ✅  **`open`**: File opening is implemented with support for:
  - 2-argument forms: `<-`, `-`, `>-`, `filename`
  - 3-argument forms with explicit modes
  - In-memory files
- ✅  **`readline`**: Reading lines from filehandles
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

### Unimplemented I/O Operators
- ❌  **`socket`**: Socket creation
- ❌  **`truncate`**: File truncation
- ❌  **`bind`**: Socket binding
- ❌  **`connect`**: Socket connection
- ❌  **`accept`**: Connection acceptance
- ❌  **`listen`**: Socket listening

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


## Namespaces and Global Variables
- ✅  **Global variable infrastructure**: Support for global variables is implemented.
- ✅  **Namespaces**: Namespace support is present.
- ✅  **Stash**: Stash can be accessed as a hash, like: `$namespace::{entry}`.
- ✅  **`@_` and `$@` special variables**: Special variables like `@_` and `$@` are supported.
- ✅  **Special variables**: The special variables `%ENV`, `@ARGV`, `@INC`, `$0`, `$_`, `$.`, `$]`, `$"`, `$\\`, `$,`, `$/`, `$$`, `$a`, `$b`, `$^O`, `$^V` are implemented.
- ✅  **I/O symbols**: `STDOUT`, `STDERR`, `STDIN`, `ARGV`, `ARGVOUT` are implemented.
- ✅  **Stash manipulation**: Alternative ways to create constants like: `$constant::{_CAN_PCS} = \$const`.
- ❌  **Thread-safe `@_`, `$_`, and regex variables**: Thread safety for global special variables is missing.
- ❌  **Compiler flags**:  The special variables `$^H`, `%^H`, `${^WARNING_BITS}` are not implemented.
- ✅  **`caller` operator**: `caller` returns `($package, $filename, $line)`.
  - ❌  **Extended call stack information**: extra debug information like `(caller($level))[9]` is not implemented.<br>
    This means we don't include subroutine names in error messages yet.<br>
    Extra debug information: `($package, $filename, $line, $subroutine, $hasargs, $wantarray, $evaltext, $is_require, $hints, $bitmask, $hinthash)`

## Perl Modules, Pragmas, Features

- ❌  There is no Perl-side support for interaction with Java libraries or other JVM languages, such as `Inline::Java` or similar modules.

### Pragmas

- 🚧  **strict** pragma:.
  - ✅ all `use strict` modes are implemented.
  - ✅ `no strict vars`, `no strict subs` are implemented.
  - 🚧 `no strict refs` is partially implemented: scalar, glob references.
  - ❌ `no strict refs` works with global variables only. `my` variables can not be accessed by name.
- ✅  **parent** pragma
- ✅  **base** pragma
- ✅  **constant** pragma
- ✅  **if** pragma
- ✅  **lib** pragma
- ✅  **vars** pragma
- ✅  **subs** pragma
- 🚧  **utf8** pragma: utf8 is always on. Disabling utf8 might work in a future version.
- ✅  **feature** pragma
  - ✅ Features implemented: `fc`, `say`, `current_sub`, `isa`, `state`, `try`, `bitwise`, `postderef`, `evalbytes`, `module_true`, `signatures`, `class`.
  - ❌ Features missing: `postderef_qq`, `unicode_eval`, `unicode_strings`, `defer`.
- 🚧  **warnings** pragma
- ❌  **version** pragma: version objects are not yet supported.
- ❌  **experimental** pragma
- 🚧  **mro** (Method Resolution Order) pragma. The compiler always use `C3` to linearize the inheritance hierarchy.
- ❌  **attributes** pragma
- ❌  **bignum, bigint, and bigrat** pragmas
- ❌  **encoding** pragma
- ❌  **integer** pragma
- ❌  **locale** pragma
- ❌  **ops** pragma
- 🚧  **re** pragma for regular expression options: Implemented `is_regexp`.
- ✅  **subs** pragma.
- 🚧  **builtin** pragma:
  - ✅ Implemented: `true` `false` `is_bool` `inf` `nan` `weaken` `unweaken` `is_weak` `blessed` `refaddr` `reftype` `created_as_string` `created_as_number` `stringify` `ceil` `floor` `indexed` `trim` `is_tainted`.
- 🚧  **overload** pragma:
  - ✅ Implemented: `""`, `0+`, `bool`, `fallback`, `nomethod`.
  - ✅ Implemented: `!`, `+`, `-`, `*`, `/`, `%`, `int`, `neg`, `log`, `sqrt`, `cos`, `sin`, `exp`, `abs`, `atan2`, `**`.
  - ✅ Implemented: `@{}`, `%{}`, `${}`, `&{}`.
  - ❌ Missing: `<=>`, `cmp`, `<`, `<=`, `>`, `>=`, `==`, `!=`, `lt`, `le`, `gt`, `ge`, `eq`, `ne`.
  - ❌ Missing: `++`, `--`, `=`, `qr`, `<>`.
  - ❌ Missing: `&`, `|`, `^`, `~`, `<<`, `>>`, `&.`, `|.`, `^.`, `~.`, `x`, `.`.
  - ❌ Missing: `+=`, `-=`, `*=`, `/=`, `%=`, `**=`, `<<=`, `>>=`, `x=`, `.=`, `&=`, `|=`, `^=`, `&.=`, `|.=`, `^.=`.
  - ❌ Missing: `*{}`, `-X`.
- ❌  **overloading** pragma



### Core modules

- ✅  **UNIVERSAL**: `isa`, `can`, `DOES`, `VERSION` are implemented. `isa` operator is implemented.
- ✅  **Symbol**: `qualify` and `qualify_to_ref` are implemented.
- ✅  **Data::Dumper**: use the same version as Perl.
- ✅  **Exporter**: `@EXPORT_OK`, `@EXPORT`, `%EXPORT_TAGS` are implemented.
- ✅  **Scalar::Util**: `blessed`, `reftype`, `set_prototype` are implemented.
- ✅  **Internals**: `Internals::SvREADONLY` is implemented as a no-op.
- ✅  **Carp**: `carp`, `cluck`, `croak`, `confess`, `longmess`, `shortmess` are implemented.
- ✅  **Cwd** module
- ✅  **Fcntl** module
- ✅  **File::Basename** use the same version as Perl.
- ✅  **File::Find** use the same version as Perl.
- ✅  **File::Spec** module.
- ✅  **File::Spec::Functions** module.
- ✅  **Getopt::Long** module.
- ✅  **Term::ANSIColor** module.
- ✅  **Time::Local** module.
- ✅  **Time::HiRes** module.
- ✅  **HTTP::Date** module.
- ✅  **URI::Escape** module.
- ✅  **Test** module.
- ✅  **Text::Balanced** use the same version as Perl.
- ✅  **Benchmark** use the same version as Perl.
- 🚧  **Test::More** some features missing: `skip`, `BAIL_OUT`.
- 🚧  **HTTP::Tiny** some features untested: proxy settings.
- 🚧  **DynaLoader** placeholder module.
- 🚧  **Unicode::Normalize** `normalize`, `NFC`, `NFD`, `NFKC`, `NFKD`.
- ❌  **IO::Socket** module, and related modules or asynchronous I/O operations.
- ❌  **Safe** module.
- ❌  **Digest::MD5** module.
- ❌  **Digest::SHA** module.
- ❌  **POSIX** module.

### Non-core modules
- ✅  **HTTP::CookieJar** module.
- ✅  **JSON** module.
- ✅  **Text::CSV** module.
- ✅  **YAML::PP** module.
- ✅  **YAML** module.

### DBI module

#### JDBC Integration
The DBI module provides seamless integration with JDBC drivers:
- Configure JDBC drivers: See [Adding JDBC Drivers](JDBC_GUIDE.md#adding-jdbc-drivers)
- Connect to databases: See [Database Connection Examples](JDBC_GUIDE.md#database-connection-examples)

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


## Non-strict and Obsolete Features
- ❌  **`format` operator**: `format` and `write` functions for report generation are not implemented.
- ❌  **DBM file support**: `dbmclose`, `dbmopen` are not implemented.
- ❌  **`reset("A-Z")`** resetting global variables is not implemented.
- ❌  **Indirect object syntax** indirect object syntax is not implemented.

## Features Incompatible with JVM
- ❌  **`fork` operator**: `fork` is not implemented. Calling `fork` will always fail and return `undef`.
- ❌  **`DESTROY`**: Handling of object destruction may be incompatible with JVM garbage collection.
  - For more details see: `dev/design/auto_close.md`.
  - Some modules that depend on `DESTROY`: `SelectSaver`, `File::Temp`.
- ❌  **Perl `XS` code**: XS code interfacing with C is not supported on the JVM.
- ❌  **Auto-close files**: File auto-close depends on handling of object destruction, may be incompatible with JVM garbage collection.
- ❌  **Low-level socket functions**: accept, bind, connect, getpeername, getsockname, getsockopt, listen, recv, send, setsockopt, shutdown, socket, socketpair
- ❌  **System V interprocess communication functions**: msgctl, msgget, msgrcv, msgsnd, semctl, semget, semop, shmctl, shmget, shmread, shmwrite
- ❌  **Fetching user and group info**: endgrent, endhostent, endnetent, endpwent, getgrent, getgrgid, getgrnam, getlogin, getpwent, getpwnam, getpwuid, setgrent, setpwent
- ❌  **Fetching network info**: endprotoent, endservent, gethostbyaddr, gethostbyname, gethostent, getnetbyaddr, getnetbyname, getnetent, getprotobyname, getprotobynumber, getprotoent, getservbyname, getservbyport, getservent, sethostent, setnetent, setprotoent, setservent
- ❌  **Keywords related to the control flow of the Perl program**: `dump` operator.
- ❌  **Tail calls**: `goto` going to a different subroutine as a tail call is not supported.


## Language Differences and Workarounds

This section is being worked on.


## Optimizations

- ✅  **Cached string/numeric conversions; dualvars**: Caching is implemented, but it doesn't use the Perl "dual variable" implementation.
- ❌  **Inline "constant" subroutines optimization**: Optimization for inline constants is not yet implemented.
- ❌  **Overload optimization**: Preprocessing in overload should be cached.
- ❌  **I/O optimization**: Use low-level readline to optimize input.
- ❌  **I/O optimization**: Extract I/O buffering code (StandardIO.java) into a new layer, and add it at the top before other layers.


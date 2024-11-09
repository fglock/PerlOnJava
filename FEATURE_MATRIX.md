# Perl on JVM Feature Matrix

# Table of Contents

1. [Compiler Usability](#compiler-usability)
2. [Testing](#testing)
3. [Scalars](#scalars)
4. [Objects](#objects)
5. [Operators](#operators)
6. [Arrays, Hashes, and Lists](#arrays-hashes-and-lists)
7. [Subroutines](#subroutines)
8. [Regular Expressions](#regular-expressions)
9. [Statements and Special Operators](#statements-and-special-operators)
10. [Namespaces and Global Variables](#namespaces-and-global-variables)
11. [Perl Modules, Pragmas, Features](#perl-modules-pragmas-features)
12. [Non-strict and Obsolete Features](#non-strict-and-obsolete-features)
13. [Features Probably Incompatible with JVM](#features-probably-incompatible-with-jvm)
14. [Language Differences and Workarounds](#language-differences-and-workarounds)

## Compiler Usability
- ✔️   **Perl-like compile-time error messages**: Error messages mimic those in Perl for consistency.
- ✔️   **Perl line numbers in bytecode**: Bytecode includes line numbers for better debugging.
- ✔️   **Perl-like runtime error messages**: Runtime errors are formatted similarly to Perl's.
- ❌  **Perl-like warnings**: Internal support for most warnings is missing. Warnings need to be formatted to resemble Perl’s output.
- ✔️   **Comments**: Support for comments and POD (documentation) in code is implemented.
- ❌  **Subroutine hoisting**: Calling subroutines before they are declared is not implemented.

### Command line switches

- ✔️   Accept command line switches from the shebang line.
- ✔️   Command line switches `-c`, `-e`, `-E`, `-p`, `-n`, `-i`, `-I`, `-0`, `-a`, `-F`, `-m`, `-M`, `-g`, `-l`, `-h`, `-x`, `-?` are implemented.
- ❌  Missing command line switches include:
  - `-s`: Rudimentary switch parsing.
  - `-T`: Taint checks.
  - `-t`: Taint checks with warnings.
  - `-u`: Dumps core after compiling.
  - `-U`: Allows unsafe operations.
  - `-W`: Enables all warnings.
  - `-X`: Disables all warnings.
  - `-v`: Displays the version.
  - `-V[:configvar]`: Displays configuration information.
  - `-w`: Enables warnings.
  - `-d[t][:debugger]`: Runs the program under the debugger.
  - `-D[number/list]`: Sets debugging flags.
  - `-f`: Suppresses execution of `~/.perlrc`.
  - `-C [number/list]`: Controls Unicode features.
  - `-S`: Uses the PATH environment variable to find the script.

## Testing
- ✔️   **TAP tests**: Running standard Perl testing protocol.
- ✔️   **CI/CD**: Github testing pipeline in Ubuntu.

## Scalars
- ✔️   **`my` variable declaration**: Local variables can be declared using `my`.
- ✔️   **`our` variable declaration**: Global variables can be declared using `our`.
- ✔️   **`local` variable declaration**: Dynamic variables are implemented.
- ❌  **`state` variable declaration**: Support for state variable changes is missing.
- ✔️   **Variable assignment**: Basic variable assignment is implemented.
- ✔️   **Basic types**: Support for integers, doubles, strings, v-strings, regex, CODE, undef, references is present.
- ✔️   **String Interpolation**: Both array and scalar string interpolation are supported.
- ✔️   **String Interpolation escapes**: Handles escape sequences like `\n`, `\N{name}` within interpolated strings.
- ✔️   **String numification**: Strings can be converted to numbers automatically.
- ✔️   **Numbers with underscores**: Numbers with underscores (e.g., `1_000`) are supported.
- ✔️   **Numbers in different bases**: Numbers in binary, hex, octal: `0b1010`, `0xAA`, `078`.
- ✔️   **Infinity, -Infinity, NaN**: Special number values are implemented.
- ✔️   **References**: References to variables and data structures are supported.
- ✔️   **Autovivification**: Autovivification is implemented.
- ✔️   **File handles**: Support for file handles is implemented.
- ❌  **Tied Scalars**: Support for tying scalars to classes is missing.
- ❌  **Overload**: overloading Perl operations is missing.
- ✔️   **Cached string/numeric conversions; dualvars**: Caching is implemented, but it doesn't use the Perl "dual variable" implementation.
- ❌  **Unicode**: Support for non-Unicode strings is not implemented.
- ❌  **Taint checks**: Support for taint checks is not implemented.
- ❌  **`local` special cases**: `local` is missing for typeglobs and filehandles. Localization in for-loops is also missing.

## Objects
- ✔️   **Objects**: Creating classes, method call syntax works.
- ✔️   **Object operators**: `ref` and `bless`
- ✔️   **Special variables**: `@ISA` is implemented.
- ✔️   **Multiple Inheritance**: C3 method resolution is implemented.
- ✔️   **Method caching**: Method resolution is cached.
- ✔️   **Version check**: Method `VERSION ( [ REQUIRE ] )` is implemented.
- ❌  **Inheritance**: `SUPER` is not yet implemented.
- ❌  **Autoload**: `AUTOLOAD` mechanism is not yet implemented.

## Operators
- ✔️   **Simple arithmetic**: Operators like `+`, `-`, `*`, and `%` are supported.
- ✔️   **Numeric Comparison operators**: Comparison operators such as `==`, `!=`, `>`, `<`, etc., are implemented.
- ✔️   **defined-or**: `//` operator.
- ✔️   **String concat**: Concatenation of strings using `.` is supported.
- ✔️   **String Comparison operators**: String comparison operators such as `eq`, `ne`, `lt`, `gt`, etc., are implemented.
- ✔️   **`q`, `qq`, `qw`, `qx` String operators**: Various string quoting mechanisms are supported.
- ✔️   **Bitwise operators**: Bitwise operations like `~`, `&`, `|`, `^`, `~.`, `&.`, `|.`, `^.`, `<<`, and `>>` are supported.
- ✔️   **Bitwise operators**: Bitwise integer and string operations are implemented.
- ✔️   **Bitwise operators return unsigned**: Emulate unsigned integers.
- ✔️   **Autoincrement, Autodecrement; String increment**: Increment and decrement operators, including for strings, are implemented.
- ✔️   **Scalar string and math operators**: `quotemeta`, `ref`, `undef`, `log`, `rand`, `oct`, `hex`, `ord`, `chr`, `int`, `sqrt`, `cos`, `sin`, `exp`, `atan2`, `lc`, `lcfirst`, `uc`, `ucfirst`, `chop`, `fc`, `index`, `rindex`.
- ✔️   **`join`**: Join operator for combining array elements into a string is supported.
- ✔️   **`sprintf`**: String formatting is supported.
- ✔️   **`grep`, `map`, `sort`**: List processing functions are implemented.
- ✔️   **`substr`**: Substring extraction works.
- ✔️   **Time-related functions**: `time`, `times`, `gmtime`, `localtime` are implemented.
- ✔️   **`pack` and `unpack` operators**: `pack` and `unpack` are implemented.
- ✔️   **`crypt` operator**: `crypt` is implemented.
- ✔️   **`study`, `srand`**: `study`, `srand` are implemented.
- ✔️   **`chomp`**: `chomp` is implemented.
- ✔️   **`sleep`**: `sleep` is implemented. It takes fractional seconds. `$SIG{ALRM}` is also implemented.
- ✔️   **Vectors**: `vec` is implemented.
- ❌  **Chained operators**: operations like `$x < $y <= $z` not yet implemented.
- ✔️   **Lvalue `substr`**: Assignable Substring extraction is implemented.
- ✔️   **Lvalue `vec`**: Assignable `vec` is implemented.

## Arrays, Hashes, and Lists
- ✔️   **Array, Hash, and List infrastructure**: Basic infrastructure for arrays, hashes, and lists is implemented.
- ✔️   **List assignment**: Supports list assignment like `($a, undef, @b) = @c`.
- ✔️   **`my LIST`**: Declaration of lists using `my` is supported.
- ✔️   **Autoquote before `=>`**: Autoquoting before `=>` is implemented.
- ✔️   **Select an element from a list**: Indexing into lists is supported.
- ✔️   **List subscripts**: like: `(stat($file))[8]`
- ✔️   **Taking References of a List**: like: `\(1,2,3)`
- ✔️   **List assignment in scalar context**: List assignment in scalar context returns the number of elements produced by the expression on the right side of the assignment
- ✔️   **`$#array`**: Operator for array count is implemented.
- ✔️   **`scalar`**: Operator to get scalar value is not implemented.
- ✔️   **Array dereference**: Dereferencing arrays using `@$x`.
- ✔️   **Hash dereference**: Dereferencing hashes using `%$x`.
- ✔️   **Basic Array Operations**: `push`, `unshift`, `pop`, `shift`, `splice`, `reverse` are implemented.
- ✔️   **Slices**: Array and Hash slices like `@array[2, 3]` and `@hash{"a", "b"}` are implemented.
- ✔️   **Array literals**: Array literals are supported.
- ✔️   **Basic Hash Operations**: `keys`, `values`, `delete`, `exists`, `each` are implemented.
- ✔️   **Hash literals**: Hash literals are supported.
- ✔️   **List operator `..`**: List constructors are implemented.
- ✔️   **Flip-flop operator `..` and `...`**: The flip-flop operators are implemented.
- ❌  **Tied Arrays**: Tied arrays are not yet implemented.
- ❌  **Tied Hashes**: Tied hashes are not yet implemented.

## Subroutines
- ✔️   **Anonymous subroutines with closure variables**: Anonymous subs and closures are supported.
- ✔️   **Return from inside a block**: Return statements within blocks work correctly.
- ✔️   **Assigning to a closure variable mutates the variable in the original context**: Closure variable mutation is implemented.
- ✔️   **`@_` contains aliases to the caller variables**: The `@_` array reflects caller arguments correctly.
- ✔️   **Named subroutines**: Support for named subroutines is implemented.
- ✔️   **Calling context**: `wantarray` is implemented.
- 🚧  **Subroutine prototypes**: Partial implementation of prototypes; some features are supported.
- ❌  **Subroutine signatures**: Formal parameters are not implemented.
- ❌  **Inline "constant" subroutines optimization**: Optimization for inline constants is not yet implemented.
- ❌  **Subroutine attributes**: Subroutine attributes are not yet supported.
- ✔️   **`lvalue` subroutines**: Subroutines with attribute `:lvalue` are supported.
- ❌  **Lexical subroutines**: Subroutines declared `my`, `state`, or `our` are not yet supported.
- ✔️   **CORE namespace**: `CORE` is implemented.
- ❌  **CORE::GLOBAL namespace**: `CORE::GLOBAL` is not implemented.
- ✔️   **alternate subroutine call syntax**: `&$sub`, `&$sub(args)` syntax is implemented.

## Regular Expressions
- ✔️   **Basic Matching**: Operators `qr//`, `m//`, `s///`, `split` are implemented.
- ✔️   **Regex modifiers**: Modifiers `/i` `/m` `/s` `/g` `/r` `/e` `/x` are implemented.
- ✔️   **Special variables**: The special variables `$1`, `$2`... are implemented.
- ✔️   **Transliteration**: `tr` and `y` transliteration operators are implemented.
- ✔️   **`pos`**: `pos` operator is implemented.
- ✔️   **`\G`**: `\G` operator in regex is implemented.
- ✔️   **`\N{name}`**: `\N{name}` and `\N{U+hex}` operator for named characters in regex is implemented.
- ✔️   **lvalue `pos`**: lvalue `pos` operator is implemented.
- ✔️   **`m?pat?`** one-time match is implemented.
- ✔️   **`reset`** resetting one-time match is implemented
- ✔️   **`@-`, `@+`, `%+`, `%-` variables**: regex special variables are implemented
- ✔️   **`$&` variables**: `` $` ``, `$&`, `$'` special variables are implemented
- ✔️   **Matching plain strings**: `$var =~ "Test"` is implemented.
- ❌  **Perl-specific Regex Features**: Some features like `/xx` `/ee` are missing.
- ❌  **Dynamically-scoped regex variables**: Regex variables are not dynamically-scoped.
- ❌  **Code blocks**: `(?{ code })` in regex is not implemented.
- ❌  Missing regex features include:
  - `(?^` embedded pattern-match modifier, shorthand equivalent to "d-imnsx"

## Statements and Special Operators
- ✔️   **Context void, scalar, list**: Contexts for void, scalar, and list are supported.
- ✔️   **`if`/`else`/`elsif` and `unless`**: Conditional statements are implemented.
- ✔️   **3-argument `for` loop**: The `for` loop with three arguments is supported.
- ✔️   **`foreach` loop**: The `foreach` loop is implemented.
- ✔️   **`while` and `until` loop**: `while` and `until` loops are supported.
- ✔️   **`if` `unless` Statement modifiers**: Conditional modifiers for `if` and `unless` are implemented.
- ✔️   **`while` `until` Statement modifiers**: Loop modifiers for `while` and `until` are supported.
- ✔️   **`while` `until` Statement modifiers**: `last`, `redo`, `next` give an error `Can't "last" outside a loop block`.
- ✔️   **`for` `foreach` Statement modifiers**: Loop modifiers for `for` and `foreach` are implemented.
- ✔️   **`continue` blocks**: `continue` blocks in looks are implemented.
- ✔️   **`try`/`catch`** try-catch is supported.
- ✔️   **`eval` string with closure variables**: `eval` in string context with closures is supported.
- ✔️   **`eval` string sets `$@` on error; returns `undef`**: `eval` sets `$@` on error and returns `undef`.
- ✔️   **`eval` block**: `eval` blocks are implemented.
- ✔️   **`do` block**: `do` blocks are supported.
- ✔️   **`do` file**: File execution using `do` is implemented.
- ✔️   **`print` operators**: `print`, `printf` and `say` statements are implemented, with support for file handles.
- ✔️   **`printf` and `sprintf`**: String formatting is implemented.
- ✔️   **I/O operators**: `open`, `readline`, `eof`, `close`, `unlink`, `readpipe`, `fileno`, `getc` are implemented.
- ❌  **I/O operators**: `read`, `socket`, `seek` are not implemented.
- ✔️   **`open`**: 2-argument `open` supported forms are: `<-`, `-`, `>-`, `filename`.
- ✔️   **`select`**: `select(filehandle)` is implemented.
- ✔️   **Short-circuit and, or**: Short-circuit logical operators are supported.
- ✔️   **Low-precedence/high precedence operators**: Logical operators like `not`, `or`, `and` are supported.
- ✔️   **Ternary operator**: The ternary conditional operator is implemented.
- ✔️   **Compound assignment operators**: Compound assignment operators are implemented.
- ✔️   **`package` declaration**: `package BLOCK` is also supported.
- ✔️   **Typeglob operations**: Operations like `*x = sub {}`, `*x = \@a`, `*x = *y` are supported.
- ✔️   **Code references**: Code references like `\&subr`, `\&$subname`, `\&{$subname}`, are implemented.
- ✔️   **Special literals**: `__PACKAGE__`, `__FILE__`, `__LINE__`
- ✔️   **`die` related operators**: `die`, `warn`, `exit` are supported.
- ✔️   **`die` related features**: `$SIG{__DIE__}`, `$SIG{__WARN__}`
- ❌  **`PROPAGATE`**: `PROPAGATE` method is not yet supported.
- ✔️   **`require` operator**: The `require` operator implemented; version checks are implemented.
- ✔️   **`use` and `no` statements**: Module imports and version check via `use` and `no` are implemented; version checks are implemented.
- ❌  **`use` and `no` statements**: Complex expressions in `use` are missing: `use constant PI => 4 * atan2(1, 1);`.
- ✔️   **`caller` operator**: `caller` returns ($package, $filename, $line). The remaining results are undef. This means we don't include subroutine names in error messages yet.
- ✔️   **Import methods**: `import`, `unimport` works.
- ✔️   **`__SUB__`**: The `__SUB__` keyword works.
- 🚧  **`BEGIN` block**: `BEGIN` special block is implemented. `my` variable capture in BEGIN is not implemented.
- 🚧  **`END` block**: `END` special block is implemented. `my` variable capture in END is not implemented.
- 🚧  **`INIT`**: special block is implemented. `my` variable capture in INIT is not implemented.
- ❌  **`CHECK`**: special block is missing.
- ❌  **`UNITCHECK`**: special block is missing.
- ✔️   **Labels**: Labels are implemented.
- ❌  **Here-docs**: Here-docs for multiline string literals are not yet implemented.
- ✔️   **`glob`**: `glob` operator is implemented.
- ✔️   **`<>`**: `<>` operator is implemented.
- ✔️   **`<$fh>`**: `<$fh>` and `<STDIN>` operators are implemented.
- ✔️   **`<ARGV>`**: `ARGV` and $ARGV are implemented.
- ✔️   **`<*.*>`**: `<*.*>` glob operator is implemented.
- ✔️   **End of file markers**: Source code control characters `^D` and `^Z`, and the tokens `__END__` and `__DATA__` are implemented. There is no `DATA` file handle yet.
- ❌  **Startup processing**: processing `$sitelib/sitecustomize.pl` at startup is not enabled.
- ❌  **Smartmatch operator**: `~~` and `given`/`when` construct are not implemented.
- ❌  **File test operators**: `-R`, `-W`, `-X`, `-O` (for real uid/gid) are not implemented due to lack of straightforward Java equivalents.
- ❌  **File test operators**: `-t` (tty check) is not implemented.
- ✔️   **File test operators**: `-p`, `-S`, `-b`, and `-c` are approximated using file names or paths, as Java doesn't provide direct equivalents.
- ✔️   **File test operators**: `-k` (sticky bit) is approximated using the "others execute" permission, as Java doesn't have a direct equivalent.
- ✔️   **File test operators**: `-T` and `-B` (text/binary check) are implemented using a heuristic similar to Perl's approach.
- ✔️   **File test operators**: Time-based operators (`-M`, `-A`, `-C`) return the difference in days as a floating-point number.
- ✔️   **File test operators**: Using `_` as the argument reuses the last stat result.
- 🚧  **File test operators**: The current implementation only works with file paths, not filehandles or dirhandles.
- ❌  **File test operators**: Add support for stacked file test operators.
- ✔️   **Directory operators**: `readdir`, `opendir`, `closedir`, `telldir`, `seekdir`, `rewinddir`, `mkdir`, `rmdir`.
- ❌  **`for` loop variable**: The `for` loop variable is not an alias to a list element.
- ✔️   **loop control operators**: `next`, `last`, `redo` with labels are implemented.
- ❌  **loop control operators**: `next`, `last`, `redo` with expression are not implemented.
- ❌  **loop control operators**: `next`, `last`, `redo` going to a different place in the call stack are not implemented. Label searching in the call stack is missing.
- ✔️   **`goto &name`**: `goto &name` is implemented. It is not a tail-call.
- ❌  **`goto` operator**: `goto EXPR` and `goto LABEL` are not implemented.
- ✔️   **setting `$_` in `while` loop with `<>`**: automatic setting `$_` in `while` loops is implemented.
- ✔️   **`do BLOCK while`**: `do` executes once before the conditional is evaluated.
- ✔️   **`...` ellipsis statement**: `...` is supported.

## Namespaces and Global Variables
- ✔️   **Global variable infrastructure**: Support for global variables is implemented.
- ✔️   **Namespaces**: Namespace support is present.
- ✔️   **`@_` and `$@` special variables**: Special variables like `@_` and `$@` are supported.
- ✔️   **Special variables**: The special variables `%ENV`, `@ARGV`, `@INC`, `$0`, `$_`, `$.`, `$]`, `$"`, `$\\`, `$,`, `$/`, `$$`, `$a`, `$b`, `$^O`, `$^V` are implemented.
- ✔️   **I/O symbols**: `STDOUT`, `STDERR`, `STDIN`, `ARGV`, `ARGVOUT` are implemented.
- ❌  **Thread-safe `@_`, `$_`, and regex variables**: Thread safety for global special variables is missing.

## Perl Modules, Pragmas, Features
- ✔️   **UNIVERSAL**: `isa`, `can`, `DOES`, `VERSION` are implemented. `isa` operator is implemented.
- ✔️   **Symbol**: `qualify` and `qualify_to_ref` are implemented.
- ✔️   **Data::Dumper**: Data::Dumper is ported with small adjustments, to work in `strict` mode.
- ✔️   **Exporter**: `@EXPORT_OK`, `@EXPORT`, `%EXPORT_TAGS` are implemented.
- 🚧  **strict**: `strict` pragma is set to ignore `no strict`, the compiler works always in `strict` mode. `no strict` might work in a future version.
- ✔️   **Scalar::Util**: `blessed`, `reftype`, `set_prototype` are implemented.
- ✔️   **Internals**: `Internals::SvREADONLY` is implemented as a no-op.
- ✔️   **Carp**: `carp`, `cluck`, `croak`, `confess`, `longmess`, `shortmess` are implemented.
- ✔️   **parent** pragma
- ✔️   **constant** pragma
- ✔️   **if** pragma
- ✔️   **lib** pragma
- 🚧  **utf8** pragma: utf8 is always on. Disabling utf8 might work in a future version.
- 🚧  **feature** pragma
- 🚧  **warnings** pragma
- ❌  **version** pragma: version objects are not yet supported.
- ❌  **experimental** pragma
- ❌  **mro** (Method Resolution Order) pragma. The compiler always use `C3` to linearize the inheritance hierarchy.
- ❌  **attributes** pragma
- ❌  **bignum, bigint, and bigrat** pragmas
- ❌  **encoding** pragma
- ❌  **integer** pragma
- ❌  **re** pragma for regular expression options.
- ❌  **subs** pragma
- ❌  **defer** feature: `defer` blocks are not implemented.
- ❌  **postderef** feature


## Non-strict and Obsolete Features
- ❌  **Use string as a scalar reference**: Support for scalar references from strings is not yet implemented.
- ❌  **`format` operator**: Format is not implemented.
- ❌  **DBM file support**: `dbmclose`, `dbmopen` are not implemented.
- ❌  **`reset("A-Z")`** resetting global variables is not implemented

## Features Probably Incompatible with JVM
- ❌  **`DESTROY`**: Handling of object destruction may be incompatible with JVM garbage collection.
- ❌  **Perl `XS` code**: XS code interfacing with C is not supported on the JVM.
- ❌  **Auto-close files**: File auto-close depends on handling of object destruction, may be incompatible with JVM garbage collection.
- ❌  **Low-level socket functions**: accept, bind, connect, getpeername, getsockname, getsockopt, listen, recv, send, setsockopt, shutdown, socket, socketpair
- ❌  **System V interprocess communication functions**: msgctl, msgget, msgrcv, msgsnd, semctl, semget, semop, shmctl, shmget, shmread, shmwrite
- ❌  **Fetching user and group info**: endgrent, endhostent, endnetent, endpwent, getgrent, getgrgid, getgrnam, getlogin, getpwent, getpwnam, getpwuid, setgrent, setpwent
- ❌  **Fetching network info**: endprotoent, endservent, gethostbyaddr, gethostbyname, gethostent, getnetbyaddr, getnetbyname, getnetent, getprotobyname, getprotobynumber, getprotoent, getservbyname, getservbyport, getservent, sethostent, setnetent, setprotoent, setservent
- ❌  **Keywords related to the control flow of the Perl program**: `dump` operator.
- ❌  **Tail calls**: `goto` going to a different subroutine as a tail call is not supported.
- ❌  **Regex differences**:
    - Java's regular expression engine does not support duplicate named capture groups. In Java, each named capturing group must have a unique name within a regular expression.

## Language Differences and Workarounds


### Using `strict` mode everywhere

To ensure Perl strict is used everywhere and avoid disabling it with `no strict 'refs'`, 
you can refactor the code using Perl's `Symbol` module, 
which allows for dynamic symbol table manipulation in a safer way. 

The goal here is to avoid symbolic references (`*{ $callpkg . "::Dumper" }`) while keeping `strict` mode enabled.

```perl
    sub import {
        no strict 'refs';
        my $pkg     = shift;
        my $callpkg = caller(0);
        *{ $callpkg . "::Dumper" } = \&Dumper;
        return;
    }
```

Here’s an alternative approach using the Exporter module:

```perl
    use Exporter 'import';
    our @EXPORT = qw(Dumper);
```

Here’s an alternative approach using the Symbol module:

```perl
    use Symbol;
    
    sub import {
        my $pkg     = shift;
        my $callpkg = caller(0);
    
        # Dynamically assign the Dumper function to the caller's namespace
        my $sym_ref = qualify_to_ref('Dumper', $callpkg);
        *$sym_ref = \&Dumper;
    
        return;
    }
```


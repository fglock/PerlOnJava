# Perl on JVM Feature Matrix

# Table of Contents

1. [Compiler Usability](#compiler-usability)
2. [Scalars](#scalars)
2. [Objects](#objects)
3. [Operators](#operators)
4. [Arrays, Hashes, and Lists](#arrays-hashes-and-lists)
5. [Subroutines](#subroutines)
6. [Regular Expressions](#regular-expressions)
7. [Statements and Special Operators](#statements-and-special-operators)
8. [Namespaces and Global Variables](#namespaces-and-global-variables)
9. [Perl Modules](#perl-modules)
10. [Non-strict Features](#non-strict-features)
11. [Features Probably Incompatible with JVM](#features-probably-incompatible-with-jvm)
12. [Language Differences and Workarounds](#language-differences-and-workarounds)

## Compiler Usability
- ✔️   **Perl-like compile-time error messages**: Error messages mimic those in Perl for consistency.
- ✔️   **Perl line numbers in bytecode**: Bytecode includes line numbers for better debugging.
- ❌  **Perl-like runtime error messages**: Runtime errors are not yet formatted similarly to Perl's.
- ❌  **Perl-like warnings**: Internal support for most warnings is missing. Warnings need to be formatted to resemble Perl’s output.
- ✔️   **Comments**: Support for comments in code is implemented.

## Testing
- ✔️   **TAP tests**: Running standard Perl testing protocol.
- ✔️   **CI/CD**: Github testing pipeline in Ubuntu.

## Scalars
- ✔️   **`my` variable declaration**: Local variables can be declared using `my`.
- ✔️   **`our` variable declaration**: Global variables can be declared using `our`.
- ❌  **`local` variable declaration**: Support for temporary local variable changes is missing. `local` is also missing for typeglobs.
- ❌  **`state` variable declaration**: Support for state variable changes is missing.
- ✔️   **Variable assignment**: Basic variable assignment is implemented.
- ✔️   **Basic types**: Support for integers, doubles, strings, CODE, and undef is present.
- ✔️   **String Interpolation**: Both array and scalar string interpolation are supported.
- ✔️   **String Interpolation escapes**: Handles escape sequences like `\n` within interpolated strings.
- ✔️   **String numification**: Strings can be converted to numbers automatically.
- ✔️   **Numbers with underscores**: Numbers with underscores (e.g., `1_000`) are supported.
- ✔️   **Numbers in different bases**: Numbers in binary, hex, octal: `0b1010`, `0xAA`, `078`.
- ✔️   **References**: References to variables and data structures are supported.
- ✔️   **Autovivification**: Autovivification is implemented.
- ✔️   **File handles**: Support for file handles is implemented.
- ❌  **Tied Scalars**: Support for tying scalars to classes is missing.
- ❌  **Overload**: overloading Perl operations is missing.
- ❌  **Cached string/numeric conversions; dualvars**: Caching and dual variable support are not implemented.
- ❌  **Unicode**: Support for non-Unicode strings is not implemented.
- ❌  **Taint checks**: Support for taint checks is not implemented.

## Objects
- ✔️   **Objects**: Creating classes, method call syntax works.
- ✔️   **Object operators**: `ref` and `bless`
- ✔️   **Special variables**: `@ISA` is implemented.
- ✔️   **Multiple Inheritance**: C3 method resolution is implemented.
- ✔️   **Method caching**: Method resolution is cached.
- ❌  **Version check**: Method `VERSION ( [ REQUIRE ] )` is not yet implemented.
- ❌  **Inheritance**: `SUPER` is not yet implemented.
- ❌  **Autoload**: `AUTOLOAD` is not yet implemented.

## Operators
- ✔️   **Simple arithmetic**: Operators like `+`, `-`, `*`, and `%` are supported.
- ✔️   **Numeric Comparison operators**: Comparison operators such as `==`, `!=`, `>`, `<`, etc., are implemented.
- ✔️   **String concat**: Concatenation of strings using `.` is supported.
- ✔️   **String Comparison operators**: String comparison operators such as `eq`, `ne`, `lt`, `gt`, etc., are implemented.
- ✔️   **`q`, `qq`, `qw`, `qx` String operators**: Various string quoting mechanisms are supported.
- ✔️   **Bitwise operators**: Bitwise operations like `&`, `|`, `^`, `<<`, and `>>` are supported.
- ✔️   **Autoincrement, Autodecrement; String increment**: Increment and decrement operators, including for strings, are implemented.
- ✔️   **Scalar string and math operators**: `quotemeta`, `ref`, `undef`, `log`, `rand`, `oct`, `hex`, `ord`, `chr`, `int`, `sqrt`, `cos`, `sin`, `exp`, `atan2`, `lc`, `lcfirst`, `uc`, `ucfirst`, `chop`, `fc`, `index`, `rindex`.
- ✔️   **`join`**: Join operator for combining array elements into a string is supported.
- ✔️   **`sprintf`**: String formatting is supported.
- ✔️   **`grep`, `map`, `sort`**: List processing functions are implemented.
- ✔️   **`substr`**: Substring extraction works.
- ✔️   **Time-related functions**: `time`, `times`, `gmtime`, `localtime` are implemented.
- ❌  **`chomp`**: `chomp` is partially implemented.
- ❌  **Missing operators**: `crypt`, `pack`, `study`, `srand`, `unpack` are not implemented.
- ❌  **Chained operators**: operations like `$x < $y <= $z` not yet implemented.
- ❌  **Lvalue `substr`**: Assignable Substring extraction is not yet implemented.
- ❌  **Vectors**: `vec` is not yet implemented.

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
- ❌  **List operator `..` and `...`**: List constructors are partially implemented.
- ❌  **Tied Arrays**: Tied arrays are not yet implemented.
- ❌  **Tied Hashes**: Tied hashes are not yet implemented.

## Subroutines
- ✔️   **Anonymous subroutines with closure variables**: Anonymous subs and closures are supported.
- ✔️   **Return from inside a block**: Return statements within blocks work correctly.
- ✔️   **Assigning to a closure variable mutates the variable in the original context**: Closure variable mutation is implemented.
- ✔️   **`@_` contains aliases to the caller variables**: The `@_` array reflects caller arguments correctly.
- ✔️   **Named subroutines**: Support for named subroutines is implemented.
- ✔️   **Calling context**: `wantarray` is implemented.
- ❌  **Subroutine prototypes**: Partial implementation of prototypes; some features are supported.
- ❌  **Inline "constant" subroutines optimization**: Optimization for inline constants is not yet implemented.
- ❌  **Subroutine attributes**: Subroutine attributes are not yet supported.
- ❌  **`lvalue` subroutines**: Subroutines with attribute `:lvalue` are not yet supported.
- ❌  **Lexical subroutines**: Subroutines declared `my`, `state`, or `our` are not yet supported.
- ✔️   **CORE namespace**: `CORE` is implemented.
- ❌  **CORE::GLOBAL namespace**: `CORE::GLOBAL` is not implemented.

## Regular Expressions
- ✔️   **Basic Matching**: Operators `qr//`, `m//`, `s///`, `split` are implemented.
- ✔️   **Regex modifiers**: Modifiers `/i` `/m` `/s` `/g` `/r` `/e` are implemented.
- ✔️   **Special variables**: The special variables `$1`, `$2`... are implemented.
- ✔️   **Transliteration**: `tr` and `y` transliteration operators are implemented.
- ❌  **`pos`**: `pos` operator is not implemented.
- ❌  **Perl-specific Regex Features**: Some features like `/x` (formatted regex) are missing.

## Statements and Special Operators
- ✔️   **Context void, scalar, list**: Contexts for void, scalar, and list are supported.
- ✔️   **`if`/`else`/`elsif` and `unless`**: Conditional statements are implemented.
- ✔️   **3-argument `for` loop**: The `for` loop with three arguments is supported.
- ✔️   **`foreach` loop**: The `foreach` loop is implemented.
- ✔️   **`while` and `until` loop**: `while` and `until` loops are supported.
- ✔️   **`if` `unless` Statement modifiers**: Conditional modifiers for `if` and `unless` are implemented.
- ✔️   **`while` `until` Statement modifiers**: Loop modifiers for `while` and `until` are supported.
- ✔️   **`for` `foreach` Statement modifiers**: Loop modifiers for `for` and `foreach` are implemented.
- ✔️   **`eval` string with closure variables**: `eval` in string context with closures is supported.
- ✔️   **`eval` string sets `$@` on error; returns `undef`**: `eval` sets `$@` on error and returns `undef`.
- ✔️   **`eval` block**: `eval` blocks are implemented.
- ✔️   **`do` block**: `do` blocks are supported.
- ✔️   **`do` file**: File execution using `do` is implemented.
- ✔️   **`print` operators**: `print`, `printf` and `say` statements are implemented, with support for file handles.
- ✔️   **`printf` and `sprintf`**: String formatting is implemented.
- ✔️   **`I/O operators**: `open`, `readline`, `eof`, `close` are implemented.
- ✔️   **Short-circuit and, or**: Short-circuit logical operators are supported.
- ✔️   **Low-precedence/high precedence operators**: Logical operators like `not`, `or`, `and` are supported.
- ✔️   **Ternary operator**: The ternary conditional operator is implemented.
- ✔️   **Compound assignment operators**: Compound assignment operators are implemented.
- ✔️   **`package` declaration**: `package BLOCK` is also supported.
- ✔️   **Typeglob operations**: Operations like `*x = sub {}` are supported.
- ✔️   **Code references**: Code references like `&subr` are implemented.
- ✔️   **Special literals**: `__PACKAGE__`, `__FILE__`, `__LINE__`
- ✔️   **`die` related operators**: `die`, `warn` are supported.
- ❌  **`die` related features**: `$SIG{__DIE__}`, `$SIG{__WARN__}`, `PROPAGATE` are not yet supported.
- ❌  **`version` objects**: Version objects are not yet supported.
- ❌  **`require` operator**: The `require` operator is partially implemented.
- ❌  **`use` and `no` statements**: Module imports and version check via `use` and `no` are partially implemented.
- ❌  **`caller` operator**: `caller` works in the context of `import` subroutine, but not in the general case..
- ✔️   **Import methods**: `import`, `unimport` works.
- ❌  **`__SUB__`**: The `__SUB__` special variable is not yet supported.
- ❌  **`BEGIN` block**: `BEGIN`, `END` and other special blocks are missing.
- ❌  **Labels**: Labels and their usage are not supported.
- ❌  **Search for labels in call stack**: Label searching in the call stack is missing.
- ❌  **Here-docs**: Here-docs for multiline string literals are not yet implemented.
- ❌  **`<>` and `glob`**: support for the `glob` operator is missing.
- ❌  **End of file markers**: Source code control characters `^D` and `^Z`, and the tokens `__END__` and `__DATA__` are not implemented.
- ❌  **Startup processing**: processing `$sitelib/sitecustomize.pl` at startup is not enabled.

## Namespaces and Global Variables
- ✔️   **Global variable infrastructure**: Support for global variables is implemented.
- ✔️   **Namespaces**: Namespace support is present.
- ✔️   **`@_` and `$@` special variables**: Special variables like `@_` and `$@` are supported.
- ✔️   **Special variables**: The special variables `%ENV`, `@ARGV`, `@INC`, `$0`, `$_`, `$"`, `$\\`, `$,`, `$/`, `$$`, `$a`, `$b`, `$^O` are implemented.
- ✔️   **I/O symbols**: `STDOUT`, `STDERR`, `STDIN` are implemented.
- ❌  **Thread-safe `@_`, `$_`, and regex variables**: Thread safety for global special variables is missing.

## Perl Modules
- ✔️   **UNIVERSAL**: `isa`, `can`, `DOES` are implemented. `VERSION` is not implemented yet.
- ✔️   **Symbol**: `qualify` and `qualify_to_ref` are implemented. `Symbol::import` is not implemented yet.
- ✔️   **Data::Dumper**: Data::Dumper is ported with small adjustments, to work in `strict` mode.
- ✔️   **strict**: `strict` is set to ignore `no strict`, to work always in `strict` mode. `no strict` might work in a future version.

## Non-strict and Obsolete Features
- ❌  **Use string as a scalar reference**: Support for scalar references from strings is not yet implemented.
- ❌  **`format` operator**: Format is not implemented.
- ❌  **DBM file support**: `dbmclose`, `dbmopen` are not implemented.

## Features Probably Incompatible with JVM
- ❌  **`DESTROY`**: Handling of object destruction may be incompatible with JVM garbage collection.
- ❌  **Perl `XS` code**: XS code interfacing with C is not supported on the JVM.
- ❌  **Auto-close files**: File auto-close depends on handling of object destruction, may be incompatible with JVM garbage collection.
- ❌  **Low-level socket functions**: accept, bind, connect, getpeername, getsockname, getsockopt, listen, recv, send, setsockopt, shutdown, socket, socketpair
- ❌  **System V interprocess communication functions**: msgctl, msgget, msgrcv, msgsnd, semctl, semget, semop, shmctl, shmget, shmread, shmwrite
- ❌  **Fetching user and group info**: endgrent, endhostent, endnetent, endpwent, getgrent, getgrgid, getgrnam, getlogin, getpwent, getpwnam, getpwuid, setgrent, setpwent
- ❌  **Fetching network info**: endprotoent, endservent, gethostbyaddr, gethostbyname, gethostent, getnetbyaddr, getnetbyname, getnetent, getprotobyname, getprotobynumber, getprotoent, getservbyname, getservbyport, getservent, sethostent, setnetent, setprotoent, setservent
- ❌  **Keywords related to the control flow of the Perl program**: `dump`, `caller`.


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

Here’s an alternative approach using the Symbol module:

```perl
    use Symbol 'qualify_to_ref';
    
    sub import {
        my $pkg     = shift;
        my $callpkg = caller(0);
    
        # Dynamically assign the Dumper function to the caller's namespace
        my $sym_ref = qualify_to_ref('Dumper', $callpkg);
        *$sym_ref = \&Dumper;
    
        return;
    }
```

`qualify_to_ref` from the `Symbol` module returns a reference to the fully qualified symbol in the caller’s package.
This avoids having to use the symbolic reference (`no strict 'refs'`), so you can keep strict and warnings enabled.

This approach is safer and aligns with the practice of using strict mode throughout the codebase.


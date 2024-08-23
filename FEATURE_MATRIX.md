# Perl on JVM Feature Matrix

# Table of Contents

1. [Compiler Usability](#compiler-usability)
2. [Scalars](#scalars)
3. [Operators](#operators)
4. [Arrays, Hashes, and Lists](#arrays-hashes-and-lists)
5. [Subroutines](#subroutines)
6. [Regular Expressions](#regular-expressions)
7. [Statements and Special Operators](#statements-and-special-operators)
8. [Namespaces and Global Variables](#namespaces-and-global-variables)
9. [Non-strict Features](#non-strict-features)
10. [Features Probably Incompatible with JVM](#features-probably-incompatible-with-jvm)

## Compiler Usability
- [x] **Perl-like compile-time error messages**: Error messages mimic those in Perl for consistency.
- [x] **Perl line numbers in bytecode**: Bytecode includes line numbers for better debugging.
- [ ] **Perl-like runtime error messages**: Runtime errors are not yet formatted similarly to Perl's.
- [ ] **Perl-like warnings**: Internal support for most warnings is missing. Warnings need to be formatted to resemble Perl’s output.
- [x] **Comments**: Support for comments in code is implemented.

## Scalars
- [x] **`my` variable declaration**: Local variables can be declared using `my`.
- [x] **`our` variable declaration**: Global variables can be declared using `our`.
- [ ] **`local` variable declaration**: Support for temporary local variable changes is missing.
- [x] **Variable assignment**: Basic variable assignment is implemented.
- [x] **Basic types**: Support for integers, doubles, strings, CODE, and undef is present.
- [x] **String Interpolation**: Both array and scalar string interpolation are supported.
- [ ] **String Interpolation escapes**: Escapes within interpolated strings are incomplete.
- [x] **Expand backslash-n and simple sequences**: Handling of basic escape sequences like `\n` is implemented.
- [x] **String numification**: Strings can be converted to numbers automatically.
- [x] **Numbers with underscores**: Numbers with underscores (e.g., `1_000`) are supported.
- [x] **References**: References to variables and data structures are supported.
- [ ] **Autovivification**: Some aspects of autovivification are supported, but not fully implemented.
- [ ] **File handles**: Support for file handles is missing.
- [ ] **Objects**: Object-oriented features are not yet implemented.
- [ ] **Tied Scalars**: Support for tying scalars to classes is missing.
- [ ] **Cached string/numeric conversions; dualvars**: Caching and dual variable support are not implemented.

## Operators
- [x] **Simple arithmetic**: Operators like `+`, `-`, `*`, and `%` are supported.
- [x] **Numeric Comparison operators**: Comparison operators such as `==`, `!=`, `>`, `<`, etc., are implemented.
- [x] **String concat**: Concatenation of strings using `.` is supported.
- [ ] **`substr`**: Substring extraction is not yet implemented.
- [x] **String Comparison operators**: String comparison operators such as `eq`, `ne`, `lt`, `gt`, etc., are implemented.
- [x] **`q`, `qq`, `qw` String operators**: Various string quoting mechanisms are supported.
- [x] **Bitwise operators**: Bitwise operations like `&`, `|`, `^`, `<<`, and `>>` are supported.
- [x] **Autoincrement, Autodecrement; String increment**: Increment and decrement operators, including for strings, are implemented.
- [x] **`join`**: Join operator for combining array elements into a string is supported.
- [ ] **`split`**: Splitting strings into arrays is not yet implemented.
- [ ] **`grep`, `map`, `sort`**: List processing functions are missing.

## Arrays, Hashes, and Lists
- [x] **Array, Hash, and List infrastructure**: Basic infrastructure for arrays, hashes, and lists is implemented.
- [x] **List assignment**: Supports list assignment like `($a, undef, @b) = @c`.
- [x] **`my LIST`**: Declaration of lists using `my` is supported.
- [x] **Autoquote before `=>`**: Autoquoting before `=>` is implemented.
- [x] **Select an element from a list**: Indexing into lists is supported.
- [x] **`keys`, `values` operators**: Operators for hash keys and values are implemented.
- [ ] **Array dereference**: Dereferencing arrays using `@$x` is not yet implemented.
- [ ] **Hash dereference**: Dereferencing hashes using `%$x` is not yet implemented.
- [ ] **Basic Array Operations**: Some basic array operations are implemented; others are missing.
- [ ] **Array Slices**: Array slices like `@array[2, 3]` are not yet implemented.
- [ ] **Hash Slices**: Hash slices like `@hash{"a", "b"}` are missing.
- [x] **Array literals**: Array literals are supported.
- [ ] **Tied Arrays**: Tied arrays are not yet implemented.
- [ ] **Basic Hash Operations**: Some basic hash operations are implemented; others are missing.
- [x] **Hash literals**: Hash literals are supported.
- [ ] **Tied Hashes**: Tied hashes are not yet implemented.

## Subroutines
- [x] **Anonymous subroutines with closure variables**: Anonymous subs and closures are supported.
- [x] **Return from inside a block**: Return statements within blocks work correctly.
- [x] **Assigning to a closure variable mutates the variable in the original context**: Closure variable mutation is implemented.
- [x] **`@_` contains aliases to the caller variables**: The `@_` array reflects caller arguments correctly.
- [x] **Named subroutines**: Support for named subroutines is implemented.
- [ ] **Subroutine prototypes**: Partial implementation of prototypes; some features are supported.
- [ ] **Inline "constant" subroutines optimization**: Optimization for inline constants is not yet implemented.
- [ ] **Subroutine attributes**: Subroutine attributes are not yet supported.

## Regular Expressions
- [ ] **Basic Matching**: Basic regex matching is not yet implemented.
- [ ] **Advanced Regex Features**: Features like lookaheads, non-greedy matching, etc., are missing.

## Statements and Special Operators
- [x] **Context void, scalar, list**: Contexts for void, scalar, and list are supported.
- [x] **`if`/`else`/`elsif` and `unless`**: Conditional statements are implemented.
- [x] **3-argument `for` loop**: The `for` loop with three arguments is supported.
- [x] **`foreach` loop**: The `foreach` loop is implemented.
- [x] **`while` and `until` loop**: `while` and `until` loops are supported.
- [x] **`if` `unless` Statement modifiers**: Conditional modifiers for `if` and `unless` are implemented.
- [x] **`while` `until` Statement modifiers**: Loop modifiers for `while` and `until` are supported.
- [x] **`for` `foreach` Statement modifiers**: Loop modifiers for `for` and `foreach` are implemented.
- [x] **`eval` string with closure variables**: `eval` in string context with closures is supported.
- [x] **`eval` string sets `$@` on error; returns `undef`**: `eval` sets `$@` on error and returns `undef`.
- [x] **`eval` block**: `eval` blocks are implemented.
- [x] **`do` block**: `do` blocks are supported.
- [ ] **`do` file**: File execution using `do` is not yet implemented.
- [ ] **`print` statement**: Basic `print` and `say` statements are implemented, but support for file handles is missing.
- [x] **Short-circuit and, or**: Short-circuit logical operators are supported.
- [x] **Low-precedence/high precedence operators**: Logical operators like `not`, `or`, `and` are supported.
- [x] **Ternary operator**: The ternary conditional operator is implemented.
- [ ] **Compound assignment operators**: Most compound assignment operators are implemented; specifics needed.
- [ ] **`package` declaration**: Partial implementation; some features are supported.
- [ ] **`version` objects**: Version objects are not yet supported.
- [x] **`__PACKAGE__`**: `__PACKAGE__` is implemented.
- [x] **Typeglob operations**: Operations like `*x = sub {}` are supported.
- [x] **Code references**: Code references like `&subr` are implemented.
- [ ] **`require` operator**: The `require` operator is not yet implemented.
- [ ] **`use` and `no` statements**: Module imports and version changes via `use` and `no` are missing.
- [ ] **`__SUB__`**: The `__SUB__` special variable is not yet supported.
- [ ] **`BEGIN` block**: `BEGIN` blocks are missing.
- [ ] **Labels**: Labels and their usage are not supported.
- [ ] **Search for labels in call stack**: Label searching in the call stack is missing.
- [ ] **Here-docs**: Here-docs for multiline string literals are not yet implemented.
- [ ] **`<>` and `glob`**: support for the `glob` operator is missing.

## Namespaces and Global Variables
- [x] **Global variable infrastructure**: Support for global variables is implemented.
- [x] **Namespaces**: Namespace support is present.
- [x] **`@_` and `$@` special variables**: Special variables like `@_` and `$@` are supported.
- [x] **`$"` special variable**: The special variable `$"` is implemented.
- [x] **`$_` special variable**: The special variable `$_` is supported.
- [ ] **Thread-safe `@_`, `$_`, and regex variables**: Thread safety for global special variables is missing.

## Non-strict Features
- [ ] **Use string as a scalar reference**: Support for scalar references from strings is not yet implemented.

## Features Probably Incompatible with JVM
- [ ] **`DESTROY`**: Handling of object destruction may be incompatible with JVM garbage collection.
- [ ] **Perl `XS` code**: XS code interfacing with C is not supported on the JVM.

# Perl on JVM Feature Matrix

## Compiler usability
- [x] Perl-like compile-time error messages
- [x] Perl line numbers in bytecode
- [ ] Perl-like runtime error messages
- [ ] Perl-like warnings
- [x] Comments

## Scalars
- [x] `my` variable declaration
- [x] `our` variable declaration
- [ ] `local` variable declaration
- [x] variable assignment
- [x] Basic types: integer, double, string, CODE, undef
- [x] String Interpolation Array and Scalar
- [ ] String Interpolation escapes (Incomplete)
- [x] Expand backslash-n and simple sequences
- [x] String numification
- [x] Numbers with underscores: `1_000`
- [x] References
- [ ] Autovivification (Some are implemented)
- [ ] File handles
- [ ] Objects
- [ ] Tied Scalars
- [ ] Cached string/numeric conversions; dualvars

## Operators
- [x] Simple arithmetic, `%`, `**`
- [x] Numeric Comparison operators
- [x] String concat
- [ ] `substr`
- [x] String Comparison operators
- [x] `q`, `qq`, `qw` String operators
- [x] Bitwise operators
- [x] Autoincrement, Autodecrement; String increment
- [x] `join`
- [ ] `split`
- [ ] `grep`, `map`, `sort`

## Arrays, Hashes and Lists
- [x] Array, Hash and List infrastructure
- [x] List assignment like: `($a, undef, @b) = @c`
- [x] `my LIST` like: `my ($a, @b)`
- [x] Autoquote before `=>`
- [x] Select an element from a list: `("a","b", %x)[2]`
- [x] `keys`, `values` operators
- [ ] Array dereference: `@$x`
- [ ] Hash dereference: `%$x`
- [ ] Basic Array Operations (Some are implemented)
- [ ] Array Slices: `@array[2, 3]`
- [ ] Hash Slices: `@hash{"a", "b"}`
- [x] Array literals
- [ ] Tied Arrays
- [ ] Basic Hash Operations (Some are implemented)
- [x] Hash literals
- [ ] Tied Hashes

## Subroutines
- [x] Anonymous subroutines with closure variables
- [x] return from inside a block
- [x] assigning to a closure variable mutates the variable in the original context
- [ ] `@_` contains aliases to the caller variables
- [ ] Named subroutines
- [ ] Inline "constant" subroutines optimization

## Regular Expressions
- [ ] Basic Matching
- [ ] Advanced Regex Features

## Statements and special operators
- [x] context void, scalar, list
- [x] `if`/`else`/`elsif` and `unless`
- [x] 3-argument `for` loop
- [x] `foreach` loop
- [x] `while` and `until` loop
- [x] `if` `unless` Statement modifiers
- [x] `while` `until` Statement modifiers
- [x] `for` `foreach` Statement modifiers
- [x] `eval` string with closure variables
- [x] `eval` string sets $@ on error; returns undef
- [x] `eval` block
- [x] `do` block
- [ ] `do` file
- [ ] `print` statement (Simple `print` and `say` statement implemented)
- [x] short-circuit and, or
- [x] low-precedence/high precedence operators: `not`, `or`, `and`
- [x] ternary operator
- [ ] compound assignment operators (most are implemented)
- [ ] `package` declaration (partially implemented)
- [ ] `version` objects
- [x] `__PACKAGE__`
- [x] typeglob operations like: `*x = sub {}`
- [ ] `require` operator
- [ ] `use` and `no` statements
- [ ] `BEGIN` block
- [ ] `DESTROY` method keyword
- [ ] labels
- [ ] search for labels in call stack
- [ ] Here-docs

## Namespaces and global variables
- [x] Global variable infrastructure
- [ ] Namespaces
- [x] `@_` and `$@` special variables; `$_[0]` parameter variable
- [x] `$"` special variable
- [x] `$_` special variable
- [ ] Thread-safe `@_`, `$_` and regex variables like `$1`


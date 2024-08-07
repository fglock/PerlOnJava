# Perl on JVM Feature Matrix

## Compiler usability
- [x] Perl-like compile-time error messages
- [ ] Perl-like runtime error messages
- [ ] Perl-like warnings
- [x] Comments

## Scalars
- [x] `my` variable declaration
- [ ] `our` variable declaration
- [ ] `local` variable declaration
- [x] variable assignment
- [x] Basic types: integer, double, string, CODE, undef
- [ ] Basic Operations (Some are implemented)
- [x] Simple arithmetic
- [ ] Numeric Comparison operators (Some are implemented)
- [ ] String Comparison operators
- [ ] String Interpolation (Incomplete)
- [x] Interpolate simple scalars
- [x] Expand backslash-n and simple sequences
- [x] String numification
- [x] Autoincrement, Autodecrement; String increment
- [x] References
- [ ] Autovivification (Some are implemented)
- [ ] File handles
- [ ] Objects
- [ ] Tied Scalars
- [ ] Cached string/numeric conversions; dualvars

## Arrays, Hashes and Lists
- [x] Array, Hash and List infrastructure
- [x] List assignment like: `($a, undef, @b) = @c`
- [x] `my LIST` like: `my ($a, @b)`
- [x] Autoquote before `=>`
- [ ] Basic Array Operations (Some are implemented)
- [ ] Array Slices
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
- [ ] Inline "constant" subroutines

## Regular Expressions
- [ ] Basic Matching
- [ ] Advanced Features

## Statements and special operators
- [x] context void, scalar, list
- [x] `if`/`else`/`elsif` and `unless`
- [x] 3-argument `for` loop
- [ ] `foreach` loop
- [ ] Statement modifiers
- [x] `eval` string with closure variables
- [x] `eval` string sets $@ on error; returns undef
- [ ] `eval` block
- [x] `do` block
- [ ] `do` file
- [ ] `print` statement (Simple `print` and `say` statement implemented)
- [x] short-circuit and, or
- [x] low-precedence/high precedence operators: `not`, `or`, `and`
- [x] ternary operator
- [ ] `wantarray` operator
- [ ] `require` operator
- [ ] `use` and `no` statements
- [ ] `caller` operator
- [ ] `die` operator
- [ ] `BEGIN` block
- [ ] `DESTROY` method keyword
- [ ] `goto` operator
- [ ] labels
- [ ] search for labels in call stack

## Namespaces and global variables
- [x] Global variable infrastructure
- [ ] Global variables
- [x] `@_` and `$@` special variables; `$_[0]` parameter variable
- [ ] `$_` special variable
- [ ] `@_`, `$_` and regex variables like `$1` are thread-safe


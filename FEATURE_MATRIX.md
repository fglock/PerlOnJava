# Perl on JVM Feature Matrix

## Compiler usability
- [x] Perl-like compile-time error messages
- [ ] Perl-like runtime error messages
- [ ] Perl-like warnings

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
- [ ] References
- [ ] File handles
- [ ] Tied Scalars
- [ ] Cached string/numeric conversions; dualvars

## Arrays, Hashes and Lists
- [x] Array, Hash and List infrastructure
- [x] List assignment like: `($a, undef, @b) = @c`
- [x] `my LIST` like: `my ($a, @b)`
- [ ] Basic Array Operations
- [ ] Array Slices
- [ ] Array literals
- [ ] Tied Arrays
- [ ] Basic Hash Operations
- [ ] Hash literals
- [ ] Tied Hashes

## Subroutines
- [x] Anonymous subroutines with closure variables
- [x] return from inside a block
- [x] assigning to a closure variable mutates the variable in the original context

## Regular Expressions
- [ ] Basic Matching
- [ ] Advanced Features

## Statements and special operators
- [x] context void, scalar, list
- [ ] wantarray
- [ ] BEGIN block
- [x] if/else/elsif
- [x] 3-argument for loop
- [x] eval string with closure variables
- [x] eval string sets $@ on error; returns undef
- [ ] eval block
- [x] do block
- [ ] do file
- [ ] print statement (Simple `print` and `say` statement implemented)
- [x] short-circuit and, or
- [x] low-precedence/high precedence operators: `not`, `or`, `and`
- [x] ternary operator

## Namespaces and global variables
- [x] Global variable infrastructure
- [ ] Global variables
- [x] @_ and $@ special variables
- [ ] $_ special variable


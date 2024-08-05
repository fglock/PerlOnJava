# Perl on JVM Feature Matrix

## Scalars
- [x] `my` variable declaration
- [x] variable assignment
- [x] Basic types: integer, double, string, CODE
- [ ] Basic Operations
- [x] Simple arithmetic
- [ ] Comparison operators (Some are implemented)
- [ ] String Interpolation
- [x] Interpolate simple scalars
- [x] Expand backslash-n and simple sequences
- [x] String numification
- [ ] String increment
- [ ] Tied Scalars

## Arrays
- [ ] Basic Array Operations
- [ ] Array Slices
- [ ] Tied Arrays

## Hashes
- [ ] Basic Hash Operations
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
- [ ] print statement
- [x] simple print statement

## Namespaces and global variables
- [ ] Global variables
- [x] Global variable infrastructure
- [x] @_ and $@ special variables



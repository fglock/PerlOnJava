# TODO

## Harder to Implement
- `BEGIN` block

## More Difficult, and Low Impact
- `goto()`
- Thread
- Optimizations
- implement subroutine using JVM Function instead of Method

## Cleanup
- Cleanup the closure code to only add the lexical variables mentioned in the AST
- Refactor anonymous subroutines to use Function instead of Method

## Runtime Format Error Messages and Warnings
- catch and reformat errors like division by zero

## Test Different Perl Data Types
- Experiment with `Perlito` runtime

## Local Variables
- Set up restoring the `local` value before `RETURN`

## Implement Thread-Safety
- It may need locking when calling ASM

## GC
- Ensure GC works for classes

## Implement __SUB__


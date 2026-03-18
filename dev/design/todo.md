# TODO

## Warnings to Implement
- `"my" variable $x masks earlier declaration in same scope` warning

## More Difficult, and Low Impact
- `goto()` to jump to a label in the call stack
- Thread
- Optimizations
- implement subroutine using JVM Function instead of Method

## Cleanup
- Cleanup the closure code to only add the lexical variables mentioned in the AST
- Refactor ScalarSpecialVariable: Override `scalar()` to return `getValueAsScalar()`,
  then change `ReferenceOperators.ref()` to call `.scalar()` instead of the
  `instanceof ScalarSpecialVariable` check. This keeps special handling in the
  special variable class where it belongs.

## Local Variables
- Set up localization in for-loop

## Implement Thread-Safety
- It may need locking when calling ASM

## GC
- Ensure GC works for classes


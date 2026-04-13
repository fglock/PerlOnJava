# TODO

## Warnings to Implement
- `"my" variable $x masks earlier declaration in same scope` warning

## Typed Lexical Declarations (`my TYPE $var`)

Perl supports typed lexical declarations: `my Foo $x`, `my Foo::Bar $x`.
The type annotation is stored in the AST and currently ignored at runtime
(in Perl 5, it is used by the deprecated `fields` pragma for compile-time
hash-key checking via `use fields` / pseudo-hashes).

### Status
- Simple types work when the package is loaded: `my Foo $x` ✓
- Qualified types work when the package is loaded: `my Foo::Bar $x` ✓
- Types accepted without requiring package to be loaded ✓
  (the type is saved as an AST annotation `"varType"` on the declaration node)
- `__PACKAGE__` and `__CLASS__` as type annotations ✓

### Future Work
- Validate the type at runtime (emit a warning/error if the class doesn't exist)
- Support `use fields` pragma for compile-time hash-key checking
- Use type annotations for optional JVM type hints or optimization

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


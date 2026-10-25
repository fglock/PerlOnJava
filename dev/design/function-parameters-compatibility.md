# Function::Parameters compatibility

## Goal

Implement a documented, PerlOnJava-native subset of `Function::Parameters`
using lexical parser keyword providers and the shared AST consumed by both
execution backends. The work follows issues #1292 and #1361; it does not load
the upstream XS parser plugin.

## Design

- A compile-time module import registers statement keywords through
  `ParserExtensionRegistry`.
- `ScopedSymbolTable` owns plugin registrations in lexical scope stacks, so
  nested blocks and eval snapshots inherit or remove syntax with Perl pragma
  scope.
- A plugin consumes its declaration and returns ordinary Perl AST nodes. The
  first provider delegates positional signature lowering to the existing core
  signature implementation.
- Providers must preserve ordinary identifier parsing when their module is
  inactive and issue a source-located parser diagnostic for unsupported forms.

## Progress tracking

### Current status: Phase 1 in progress

### Completed

- [x] Add a lexical parser keyword plugin interface and module-provider
  registry.
- [x] Add `Function::Parameters` module loading and named `fun` and explicit-
  invocant `method` providers, backed by core positional signatures.
- [x] Add regression coverage for required/default parameters, slurpy arrays,
  explicit method invocants, and lexical `no` removal.

### Next steps

1. Validate import and `no` scope behavior across nested blocks, eval, and
   require on both execution backends.
2. Add anonymous `fun` expressions, richer defaults, ignored parameters, and
   source-location diagnostics.
3. Add configured invocants and `Function::Parameters::Info` for the
   documented subset.
4. Document unsupported type constraints, modifiers, and named-parameter
   behavior, then run the full CPAN dependency reproducer.

### Open questions

- Which configured keyword aliases should be part of the first public subset?
- How should provider-specific metadata map onto the existing signature
  annotations without creating a second backend representation?

## Related references

- GitHub issues #1292 and #1361.
- `src/main/java/org/perlonjava/frontend/parser/ParserKeywordPlugin.java`.

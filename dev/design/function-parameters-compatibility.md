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

### Current status: Phase 1 complete; Phase 2 in progress (2026-10-25)

### Completed

- [x] Add a lexical parser keyword plugin interface and module-provider
  registry.
- [x] Add `Function::Parameters` module loading and named `fun` and explicit-
  invocant `method` providers, backed by core positional signatures.
- [x] Add regression coverage for required/default parameters, slurpy arrays,
  explicit method invocants, and lexical `no` removal.
- [x] Support anonymous `fun` expressions, lexical blocks, nested `eval`, and
  source parsing with both execution backends.
- [x] Validate the project unit gate and system Perl behavior where the
  upstream module is available.

### Next steps

1. Add ignored parameters and richer default operators, with both-backend
   regression coverage.
2. Add configured invocants and `Function::Parameters::Info` for the
   documented subset.
3. Document unsupported type constraints, modifiers, and named-parameter
   behavior, then run the full CPAN dependency reproducer.

### Open questions

- Which configured keyword aliases should be part of the first public subset?
- How should provider-specific metadata map onto the existing signature
  annotations without creating a second backend representation?
- The current `fun` and explicit-invocant `method` subset does not yet provide
  the upstream module's import configuration, named parameters, type checks,
  modifiers, or `Function::Parameters::Info` API. The project test skips under
  system Perl when the upstream distribution is not installed.

## Related references

- GitHub issues #1292 and #1361.
- `src/main/java/org/perlonjava/frontend/parser/ParserKeywordPlugin.java`.

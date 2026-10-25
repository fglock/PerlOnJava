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
- [x] Add `Function::Parameters` module loading and named `fun` plus implicit-
  or explicit-invocant `method` providers, backed by core signatures.
- [x] Preserve parameter names and required/optional categories as shared
  runtime signature metadata, and implement `Function::Parameters::info` for
  the supported subset.
- [x] Add regression coverage for required/default parameters, slurpy arrays,
  explicit and default `$self` method invocants, and lexical `no` removal.
- [x] Support anonymous `fun` expressions, lexical blocks, nested `eval`, and
  source parsing with both execution backends.
- [x] Cover `//=`/`||=` defaults, ignored parameters, slurpy hashes, named
  parameters with a rest hash, and recursive named functions.
- [x] Validate the project unit gate and system Perl behavior where the
  upstream module is available.

### Current follow-up

- The default `method` keyword now injects `$self` as its invocant when a
  declaration does not provide an explicit `($name: ...)` invocant. Explicit
  invocants remain supported. An empty user parameter list is supported too.
- The 32-case syntax and introspection regression passes on JVM and
  interpreter backends; the full repository `make` gate passed on this
  candidate.
- `Function::Parameters::Info` now exposes invocants, required/optional
  positional and named parameters, slurpy metadata, parameter name/type
  accessors, and minimum/maximum argument counts. Its 12-case pure Perl API
  test passes on system Perl and both PerlOnJava backends.

### Next steps

1. Add configurable keyword imports and configured/class/multiple invocants.
2. Add prototypes and relevant subroutine attributes, then document
   unsupported types, coercions, and method modifiers.
3. Run the full CPAN dependency reproducer and classify remaining failures.

### Open questions

- Which configured keyword aliases should be part of the first public subset?
- How should provider-specific metadata map onto the existing signature
  annotations without creating a second backend representation? The current
  `Function::Parameters::Info` implementation reuses these annotations.
- The system Perl integration test skips when upstream `Function::Parameters`
  is not installed; the pure Perl Info API has independent system Perl tests.

## Related references

- GitHub issues #1292 and #1361.
- `src/main/java/org/perlonjava/frontend/parser/ParserKeywordPlugin.java`.
- [Parser keyword plugins](../../docs/reference/parser-keyword-plugins.md).

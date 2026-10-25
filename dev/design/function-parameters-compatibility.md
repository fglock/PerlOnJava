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

### Current status: Phases 1 and 2 subset complete; Phase 3 in progress (2026-10-25)

### Completed

- [x] Add a lexical parser keyword plugin interface and module-provider
  registry.
- [x] Add `Function::Parameters` module loading and named `fun` plus implicit-
  or explicit-invocant named and anonymous `method` providers, backed by core
  signatures.
- [x] Honor selective `fun`/`method` imports and removals, while retaining
  default imports when no keyword list is supplied.
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
- [x] Parse scalar type constraints and run their `check`/`get_message`
  methods when a declared function is called.
- [x] Recognize `GraphQL::MaybeTypeCheck`'s delegated import as a provider for
  the same lexical `fun`/`method` keywords.
- [x] Parse Type::Tiny bare and composite signature constraints, post-signature
  attributes, and method `@_` behavior after invocant binding.

### Current follow-up

- The default `method` keyword now injects `$self` as its invocant when a
  declaration does not provide an explicit `($name: ...)` invocant. Explicit
  invocants remain supported. An empty user parameter list is supported too.
- The syntax and introspection regressions pass on JVM and
  interpreter backends; the full repository `make` gate passed on this
  candidate.
- `Function::Parameters::Info` now exposes invocants, required/optional
  positional and named parameters, slurpy metadata, parameter name/type
  accessors, keyword, invocant count, and minimum/maximum argument counts. The
  object layout matches the upstream Info class for this supported subset.
- Scalar type constraints pass focused JVM/interpreter coverage, including
  bare Type::Tiny names and bracket-form expressions. Constraint objects are
  evaluated at declaration time and checked at call time; `Info::Param->type`
  remains undefined.
- GraphQL's `GraphQL::MaybeTypeCheck` wrapper delegates its import of
  `Function::Parameters` into the caller. The parser provider models that
  wrapper import. Its CPAN compile test and the GraphQL POST/route-handler
  integration cases now pass. A direct call to
  `GraphQL::Language::Receiver::got_value` also passes after method bodies
  began seeing `@_` without the bound invocant. The full WebSocket/promise test
  run has not yet been rerun after this fix.

### Next steps

1. Add hash-based keyword configuration and configured/class/multiple
   invocants.
2. Rerun the full GraphQL CPAN suite and address any remaining WebSocket,
   promise, or return-type integration failures.
3. Add coercions, prototypes and relevant subroutine attributes, then document
   unsupported type and method-modifier behavior.

### Open questions

- Which configured keyword aliases should be part of the first public subset?
- How should provider-specific metadata map onto the existing signature
  annotations without creating a second backend representation? The current
  `Function::Parameters::Info` implementation reuses these annotations.
- The system Perl syntax integration test skips when upstream
  `Function::Parameters` is not installed; the pure Perl Info API has
  independent system Perl tests.

## Related references

- GitHub issues #1292 and #1361.
- `src/main/java/org/perlonjava/frontend/parser/ParserKeywordPlugin.java`.
- [Parser keyword plugins](../../docs/reference/parser-keyword-plugins.md).

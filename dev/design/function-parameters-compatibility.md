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

### Current status: Core subset implemented; upstream compatibility work remains (2026-11-20)

Current checkpoint (2026-11-20, branch `feature/cpan-parser-compat-1264-2112`,
PR #2132, source candidate based on `68f2b8e34`): automatic Type::Tiny parameterized and quoted
composite expressions reify through overloaded code references; recursive
Function::Parameters declarations preserve block prototypes and trailing
arguments. Anonymous `fun` expression statements retain immediate postfix
calls. Configured `shift` entries for ordinary function keywords now become
leading invocants, preserve their type objects, and validate them at call time.
Configured `install_sub` code references are now called directly instead of
being stringified into `CODE(...)` package names. The interpreter now uses an
`our` array or hash declaration's package when lowering global reads, and both
eval parser paths seed aliases captured at the eval site. The immediate-call,
configured-shift, runtime-installer, and eval package-alias regressions pass
on system Perl, JVM, and interpreter. `nice -n 19 make` passed in 9m03 and
`nice -n 19 make check-links` passed. Upstream `types_auto.t` (12/12),
`bonus.t` (13/13), `lineno.t`, `lineno-torture.t`, and `eval.t` (5/5) pass.
The compile-time `Function::Parameters->import(...)` bridge now registers
configured parser keywords when called from a `BEGIN` block, including through
a wrapper. The `elsewhere.t` regression passes on system Perl, JVM, and
interpreter. Compile-time `install_sub` CVs are released at the synthetic
`BEGIN` boundary, and malformed `$^H` configuration values warn once using
the lexical warning category; the project regressions and upstream
`lifetime.t` non-TODO assertions and `stringy_h.t` pass on both backends.
`nice -n 19 make` passes on this candidate. Upstream `lifetime.t` retains its
existing TODO for runtime-installed CV retention. Next: continue closing the
remaining #2112 and #1264 compatibility gaps and rerun the complete upstream
Function::Parameters suite after that work. Keep linked issues open while
gaps remain.

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
- [x] Honor configured `check_argument_types => 0`; when enabled, apply a
  constraint's `coerce` method when `has_coercion` returns true before checking
  the coerced parameter value.
  Simple quoted type names are reified as type functions before validation,
  matching Function::Parameters' `('TypeName')` form.
- [x] Recognize `GraphQL::MaybeTypeCheck`'s delegated import as a provider for
  the same lexical `fun`/`method` keywords.
- [x] Parse Type::Tiny bare and composite signature constraints, post-signature
  attributes, and method `@_` behavior after invocant binding.
- [x] Register hash-configured function aliases, class methods, and methods
  with multiple configured or explicit invocants; support optional explicit
  invocants and lax arity configurations; preserve the configured keyword and
  invocant list in `Function::Parameters::info` metadata.

### Current follow-up

- The default `method` keyword now injects `$self` as its invocant when a
  declaration does not provide an explicit `($name: ...)` invocant. Explicit
  invocants remain supported. An empty user parameter list is supported too.
- The syntax and introspection regressions pass on JVM and
  interpreter backends; the full repository `make` gate passed on this
  candidate.
- `Function::Parameters::Info` now exposes invocants, required/optional
  positional and named parameters, slurpy metadata, parameter name/type
  accessors, keyword, invocant count, and minimum/maximum argument counts.
  Named declarations publish this metadata before their first invocation, and
  anonymous closures retain it when cloned. Ordinary subs return `undef` from
  `info()`. The object layout matches the upstream Info class for this subset.
- Function keyword configurations validate supported options, honor
  `check_argument_count`, and apply configured attributes such as `:lvalue`.
  Strict slurpy diagnostics identify both the later parameter and the earlier
  slurpy parameter. Calls that resolve to a core operator after a same-named
  Function::Parameters declaration report the ambiguous-core warning.
- Named argument extraction now starts after positional parameters, guards
  empty tails when positional defaults are omitted, and preserves distinct
  too-few, missing-name, and odd-pair diagnostics. Required named parameters
  are validated with named slurpy arrays as well as ordinary signatures.
- The upstream `named_params.t` suite passes all 134 assertions. The expanded
  project unit file passes 107 assertions under system Perl, JVM, and
  interpreter; the system Perl oracle uses the cached CPAN XS build in
  `/private/tmp/fp-542-source/blib`.
- Configured `reify_type => 'moose'` handles bare/composite type source,
  parenthesized expressions, and typed slurpy arrays. The three selected
  upstream `types_moose*.t` files pass 49 assertions each on system Perl and
  both PerlOnJava backends. JVM anonymous code objects now retain parameter
  type-name metadata as well as names. Callback-based `reify_type`, caller
  types, and inline checks pass their selected upstream tests on both backends.
  A focused project regression covers an anonymous typed slurpy parameter.
- Unparenthesized recursive calls with fat-comma named arguments now have a
  project regression and pass both backends. Recursive positional calls are
  also recognized as direct calls while the declaration body is compiled.
  The upstream `recursion.t` now passes all 26 assertions, including the
  `:lax` case where an omitted named parameter has no explicit default.
- Lax Function::Parameters declarations no longer record no-default named
  parameters as required. The new omission regression passes under system Perl
  and both PerlOnJava backends.
- The latest serialized 56-file upstream run had 45 passing files, 4 errors,
  and 7 incomplete files (1,592 planned assertions; no timeouts). Subsequent
  focused runs fixed and passed `gorn.t`, `precedence.t`, `types_parse.t`,
  `types_custom_2.t`, `types_caller.t`, `types_inline.t`, and all three
  `types_moose*.t` files on both backends. Remaining gaps are `types_auto.t`
  composite expressions; runtime errors in `elsewhere.t` and
  `lineno-torture.t`; the warning-category mismatch in `stringy_h.t`; and
  incomplete coverage in `defaults_bare.t`, `bonus.t`, `types_msg.t`,
  `lifetime.t`, `lineno.t`, and `eval.t`. These are not claimed as passing.
- Upstream `unicode.t` and `unicode2.t` now pass all 19 and 25 assertions. The
  parser recognizes expression-position qualified declarations and reports
  invalid Unicode and numeric declaration names with the configured keyword.
- Upstream `info.t`, `strict.t`, `prototype.t`, `types_coerce.t`, and
  `attributes.t` pass.
- Scalar type constraints pass focused JVM/interpreter coverage, including
  bare Type::Tiny names and bracket-form expressions. Constraint expressions
  and checks run as part of signature setup for each call; coercion is applied
  before checking when supported, and `check_argument_types => 0` skips those
  checks. `Info::Param->type` remains undefined.
- GraphQL's `GraphQL::MaybeTypeCheck` wrapper delegates its import of
  `Function::Parameters` into the caller. The parser provider models that
  wrapper import. Its CPAN compile test and the GraphQL POST/route-handler
  integration cases now pass, including the promise and error request cases.
  A direct call to `GraphQL::Language::Receiver::got_value` also passes after
  method bodies began seeing `@_` without the bound invocant. The full
  `t/basic.t` suite completes. Two WebSocket assertions still differ under
  PerlOnJava: `subs response` receives a queued data packet where the test
  expects the stop completion, and `subs keepalive` receives a keepalive
  packet where it expects the subscription response. A diagnostic run measured
  0.39–7.8 second gaps between resolver callbacks scheduled 100 ms apart on this
  busy host; isolated timer and WebSocket probes pass on both Perl and
  PerlOnJava. The cached CPAN test with native Function::Parameters under
  system Perl passes all nine top-level subtests. The timing failures need
  verification on a less-contended runner before attributing them to a runtime
  defect.
- Function::Parameters calls now report module-compatible arity, odd-pair,
  missing-name, unknown-name, and typed-parameter diagnostics at the captured
  call site. Empty positional defaults lower to `undef`. The focused diagnostic
  and empty-default tests pass on JVM and interpreter, and system Perl with the
  upstream XS module. The current full upstream suite passes 47/56 programs;
  remaining parser and warning/lifetime gaps are still open.
- Register the `Function::Parameters` warnings category in the bundled module.
  A focused test verifies that `no warnings 'Function::Parameters'` compiles on
  JVM, interpreter, and system Perl with the upstream XS module. The malformed
  `$^H` config warning in upstream `stringy_h.t` remains unresolved.
- Typed method invocants with an explicit type prefix and `:` are now recognized
  as explicit, so the method provider does not inject a second `$self`. Runtime
  type failures identify the invocant separately from positional parameters
  and report the invocation line.
  The focused regression passes on JVM, interpreter, and system Perl with the
  upstream XS module. Typed slurpy parameters now validate and coerce each
  element, with a focused ordinal regression passing on JVM, interpreter, and
  system Perl. The upstream `types_msg.t` passes all 13 assertions. `types_auto.t`
  now parses bracket applications and composite operators in project unit
  coverage. Its full upstream expression still has two failures: a bare
  unknown type diagnostic and metadata for a deeply composed type.
- Required named-argument validation now reports Function::Parameters' arity
  error before an unknown key when the supplied key/value count is below the
  required minimum. The focused regression passes on the JVM and interpreter.
  The upstream suite improved from 12 failing programs / 16 failed assertions
  to 11 / 15; the remaining failures are still under investigation.

### Next steps

1. Fix dynamic import visibility in `elsewhere.t`.
2. Investigate the compile-time CV lifetime and malformed `$^H` warning in
   `lifetime.t` and `stringy_h.t`.
3. Continue method-modifier coverage and verify GraphQL WebSocket ordering on
   an uncongested runner before assigning that timing difference to runtime
   behavior.

### Open questions

- How should provider-specific metadata map onto the existing signature
  annotations without creating a second backend representation? The current
  `Function::Parameters::Info` implementation reuses these annotations.

## Related references

- GitHub issues #1292 and #1361.
- `src/main/java/org/perlonjava/frontend/parser/ParserKeywordPlugin.java`.
- [Parser keyword plugins](../../docs/reference/parser-keyword-plugins.md).

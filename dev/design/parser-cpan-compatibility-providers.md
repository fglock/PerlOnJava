# Parser CPAN compatibility providers

## Current state (2026-11-20)

This change adds parser providers for three compile-time module imports that
otherwise require XS/declaration hooks: `Syntax::Keyword::Try`,
`Syntax::Feature::Method`, and the `syntax qw(method)` dispatcher. The try
provider enables the existing try AST and permits `catch { ... }` without an
exception variable only while that provider is active. Core `feature 'try'`
retains its required catch variable and diagnostic. The method providers reuse
the Function::Parameters method parser and synthesize the implicit `$self`
signature for the no-parentheses forms used by `Data::Page::Pagination`.

The bundled `Object::Pad` shim reports syntax compatibility level 0.800 for
the implemented class, role, field, initializer, method, signature,
ADJUST/BUILD, inheritance, `:repr(HASH)`, `:does`, and `:Struct(readonly)`
subset. It also provides a small `Object::Pad::MOP::Class` method-registration
API and package metadata helpers. This does not implement custom field
attributes or Object::Pad's XS APIs. The `Device::Chip` 0.27 syntax and
compatibility path covers its roles, hash-backed fields, `:Struct(readonly)`,
MOP method registration, signatures, async methods, and unit-class `ADJUST`
initialization across later field declarations.

The autobox work now adds parser-time `use autobox` and `no autobox` bindings,
captures immutable mappings on method-call sites, and routes them through both
the JVM emitter and bytecode interpreter. The runtime supports scalar and
reference type mappings, virtual `SCALAR`/`NUMBER` expansion, merged class
lookup, `autobox_class`, and a pure-Perl `autobox::universal::type` helper.
Direct `@array->method` and `%hash->method` invocants become references only
while a lexical mapping is active; ordinary lookup resumes under `no autobox`
and outside that scope. These lexical call-site bindings do not mutate or bless
the original invocant.

## Evidence and remaining scope

- `Data::Page::Pagination` 0.006 upstream tests pass (18 tests).
- `Data::Structure::Deserialize::Auto` 1.01 upstream tests pass (10 files,
  11 tests), including unbound `catch` blocks.
- The new focused provider tests pass on JVM and interpreter backends, and
  system Perl skips syntax unavailable in its core try implementation.
- The full `nice -n 19 make` gate passes on the candidate source.
- The Object::Pad role/MOP/Struct regression passes 10 assertions on JVM and
  interpreter. The system-Perl test skips because Object::Pad is not installed.
- The unchanged `Device::Chip::ADT7470` upstream suite passes all four test
  programs (27 tests) using its fake adapter.
- The focused autobox regression passes 39 assertions on the JVM and
  interpreter backends. A six-assertion system-Perl oracle built against
  autobox 3.0.2's XS implementation passes the aggregate-invocant scope cases.
- The autobox 3.0.2 upstream suite now passes 19 of 23 test programs (674
  assertions executed). `autoref.t` passes, including direct aggregate
  invocants and lexical disabling. Four programs remain: the upstream hints-bit
  check, three method-error wording assertions, a destructor-time undefined
  method case with no TAP result, and two numeric type assertions where the Perl
  helper cannot see PerlOnJava's internal scalar flags.
- The Object::Pad compatibility layer remains a documented subset; custom
  field attributes and upstream XS APIs are unsupported. Function::Parameters'
  opt-in upstream suite has 12/56 failing files. `autobox::Core` compatibility
  remains incomplete while the upstream autobox suite has these gaps.

These results deliver the two reported syntax-provider cases, pass the tested
Object::Pad consumer path, and add broader autobox dispatch coverage while
leaving the documented Object::Pad subset, Function::Parameters residual
parity, and autobox's remaining upstream behaviors unresolved. Follow those
independent API contracts before treating issues #2112, #1361, or #1264 as
complete.

## Related references

- [Issue queue](issue-queue.md)
- [PerlOnJava debugging workflow](../../.agents/skills/debug-perlonjava/SKILL.md)

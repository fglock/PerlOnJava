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
the implemented class, field, initializer, method, signature, ADJUST/BUILD,
inheritance, and field-attribute subset. This does not implement Object::Pad's
MOP, roles, custom field attributes, or XS APIs. In particular, `Device::Chip`
0.27 also uses `:repr(HASH)` and `:does`, so passing its version check does not
make that distribution compatible yet.

## Evidence and remaining scope

- `Data::Page::Pagination` 0.006 upstream tests pass (18 tests).
- `Data::Structure::Deserialize::Auto` 1.01 upstream tests pass (10 files,
  11 tests), including unbound `catch` blocks.
- The new focused provider tests pass on JVM and interpreter backends, and
  system Perl skips syntax unavailable in its core try implementation.
- The full `nice -n 19 make` gate passes on the candidate source.
- `Device::Chip::ADT7470` still fails on unsupported Object::Pad `:repr` and
  `:does` attributes. Function::Parameters' opt-in upstream suite has 12/56
  failing files. `autobox::Core` cannot load because its base `autobox` module
  has no Java XS implementation.

These results deliver the two reported syntax-provider cases while leaving
Object::Pad's wider distribution compatibility, Function::Parameters residual
parity, and autobox native dispatch unresolved. Follow those independent API
contracts before treating issues #2112, #1361, or #1264 as complete.

## Related references

- [Issue queue](issue-queue.md)
- [PerlOnJava debugging workflow](../../.agents/skills/debug-perlonjava/SKILL.md)

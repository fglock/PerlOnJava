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

The autobox work now adds parser-time `use autobox` and `no autobox` bindings,
captures immutable mappings on method-call sites, and routes them through both
the JVM emitter and bytecode interpreter. The runtime supports scalar and
reference type mappings, virtual `SCALAR`/`NUMBER` expansion, merged class
lookup, `autobox_class`, and a pure-Perl `autobox::universal::type` helper.
These lexical call-site bindings do not mutate or bless the original invocant.

## Evidence and remaining scope

- `Data::Page::Pagination` 0.006 upstream tests pass (18 tests).
- `Data::Structure::Deserialize::Auto` 1.01 upstream tests pass (10 files,
  11 tests), including unbound `catch` blocks.
- The new focused provider tests pass on JVM and interpreter backends, and
  system Perl skips syntax unavailable in its core try implementation.
- The full `nice -n 19 make` gate passes on the candidate source.
- The focused autobox regression passes 21 assertions on the JVM and
  interpreter backends and passes under system Perl.
- A forced clean-home autobox 3.0.2 run passes 586 of 592 executed assertions;
  16 of 23 test programs pass. The remaining failures include aggregate
  `@array`/`%hash` invocants that do not parse, the upstream hints-bit check,
  three method-error wording assertions, and two numeric type assertions where
  the Perl helper cannot see PerlOnJava's internal scalar flags.
- `Device::Chip::ADT7470` still fails on unsupported Object::Pad `:repr` and
  `:does` attributes. Function::Parameters' opt-in upstream suite has 12/56
  failing files. `autobox::Core` compatibility remains incomplete while the
  upstream autobox suite has these gaps.

These results deliver the two reported syntax-provider cases and an autobox
dispatch foundation while leaving Object::Pad's wider distribution
compatibility, Function::Parameters residual parity, and autobox's remaining
upstream behaviors unresolved. Follow those independent API contracts before
treating issues #2112, #1361, or #1264 as complete.

## Related references

- [Issue queue](issue-queue.md)
- [PerlOnJava debugging workflow](../../.agents/skills/debug-perlonjava/SKILL.md)

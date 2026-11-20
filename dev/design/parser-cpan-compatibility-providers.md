# Parser CPAN compatibility providers

## Current state (2026-11-20)

Source base commit: `f9bc8e763`; parser/runtime follow-up: `a35e57b4e`,
rebased onto `origin/master` at `94cc21a9c`. The full `nice -n 19 make` gate
passes on the current candidate. The autobox 3.0.2 upstream suite previously
passed all 23 files and 675 assertions; `Function::Parameters` 2.002006 passes
all 56 files and 1,619 assertions. `Moose::Autobox` 0.16 now passes all 15
upstream files and 208 tests, including CODE fixed-point calls and scalar-ref
flattening. `autobox::Core` 1.33 is bundled and its focused tests pass on both
backends; its upstream JPerl run still has unrelated `crypt.t` and two `Want`
BOOL-context failures. `Object::Pad` 0.825 remains a partial compatibility
layer: 15 of 68 upstream files pass, with custom syntax, fields, and MOP APIs
still outside the implemented surface.

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

The autobox work adds parser-time `use autobox` and `no autobox` bindings,
captures immutable mappings on method-call sites, and routes them through both
the JVM emitter and bytecode interpreter. The runtime supports scalar and
reference type mappings, virtual `SCALAR`/`NUMBER` expansion that retains the
`SCALAR` mapping for scalar references, merged class lookup, `autobox_class`,
and an `autobox::universal::type` helper that reads integer and floating-point
flags through `B`. Parser providers model the delegated imports from
`autobox::Core` and `Moose::Autobox`; `autobox::Core` 1.33 is bundled.
Direct `@array->method` and `%hash->method` invocants become references only
while a lexical mapping is active; ordinary lookup resumes under `no autobox`
and outside that scope. These lexical call-site bindings do not mutate or bless
the original invocant. `use/no autobox` also sets or clears its Perl `$^H` bits.
Process-level global destruction tracks objects with `DESTROY` through weak
identity references and invokes destructors for reference cycles that the
ordinary global-root walk cannot find. The cycle scan is restricted to objects
that the reachability walker proves unrooted; child-thread shutdown skips it so
shared objects remain owned by the parent.

## Evidence and remaining scope

- `Data::Page::Pagination` 0.006 upstream tests pass (18 tests).
- `Data::Structure::Deserialize::Auto` 1.01 upstream tests pass (10 files,
  11 tests), including unbound `catch` blocks.
- The new focused provider tests pass on JVM and interpreter backends, and
  system Perl skips syntax unavailable in its core try implementation.
- The full `nice -n 19 make` gate passes on the candidate source after rebasing
  onto `origin/master`.
- The Object::Pad role/MOP/Struct regression passes 10 assertions on JVM and
  interpreter. The system-Perl test skips because Object::Pad is not installed.
- The unchanged `Device::Chip::ADT7470` upstream suite passes all four test
  programs (27 tests) using its fake adapter.
- The focused autobox regression passes 44 assertions on the JVM and
  interpreter backends. A six-assertion system-Perl oracle built against
  autobox 3.0.2's XS implementation passes the focused test. The system-Perl
  upstream suite passes all 23 test programs (675 tests).
- `autobox::Core` focused dispatch (8 assertions), scalar-reference dispatch
  (1 assertion), and Moose::Autobox consumer dispatch (9 assertions) pass on
  both the JVM and interpreter. The system-Perl oracles pass with official
  autobox XS; the full system-Perl `autobox::Core` 1.33 suite passes 63 files
  and 301 assertions.
- The unchanged autobox 3.0.2 system-Perl and PerlOnJava upstream suites pass
  all 23 test programs (675 assertions). `rt_71777.t` verifies destructor-time
  dispatch while a method call on `undef` throws during global teardown; it
  passes on both PerlOnJava backends. The project-owned cycle regression also
  passes with system Perl, JVM, and interpreter.
- Function::Parameters 2.002006 upstream tests now pass all 56 files and 1,619
  assertions, with two optional MooseX::Types files skipped and upstream TODOs
  preserved. The added multiline empty method/classmethod regressions pass on
  system Perl, JVM, and interpreter.
- The Object::Pad compatibility layer remains a documented subset; custom
  field attributes and upstream XS APIs are unsupported. Function::Parameters'
  upstream suite is covered by the full passing run above. The `autobox::Core`
  upstream JPerl failures remain in `crypt.t` and two `Want` BOOL-context
  assertions; its system-Perl suite passes.

The two method-syntax providers and `Syntax::Keyword::Try` provider are covered
by focused consumer tests. Broader Object::Pad 0.825 compatibility remains the
next parser-provider phase; do not claim complete Object::Pad compatibility.

## Related references

- [Issue queue](issue-queue.md)
- [PerlOnJava debugging workflow](../../.agents/skills/debug-perlonjava/SKILL.md)

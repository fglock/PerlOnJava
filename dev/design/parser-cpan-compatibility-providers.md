# Parser CPAN compatibility providers

## Current state (2026-11-20)

Source commit: `bb93d3984`. The full project gate passed on that source tree;
the autobox 3.0.2 upstream suite passed all 675 assertions, and Function::Parameters
2.002006 passed all 56 upstream files and 1,619 assertions. The Object::Pad
0.825 suite currently passes 15 of 68 files; failures identify substantial
unsupported syntax, roles, and MOP surface. Next, extend that compatibility
subset. Issue #1264's claim names `autobox::Core` and consumer coverage as
remaining acceptance. Current local work adds the `autobox::Core` 1.33 module,
parser providers for its delegated import and `Moose::Autobox`, and focused
system-Perl-passing tests. The full project gate has not yet run on these new
changes because Gradle cannot complete TLS connections from its Java client to
the plugin portal; verification is pending.

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
lookup, `autobox_class`, and an `autobox::universal::type` helper that reads
integer and floating-point flags through `B`.
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
- The full `nice -n 19 make` gate passes on the candidate source.
- The Object::Pad role/MOP/Struct regression passes 10 assertions on JVM and
  interpreter. The system-Perl test skips because Object::Pad is not installed.
- The unchanged `Device::Chip::ADT7470` upstream suite passes all four test
  programs (27 tests) using its fake adapter.
- The focused autobox regression passes 44 assertions on the JVM and
  interpreter backends. A six-assertion system-Perl oracle built against
  autobox 3.0.2's XS implementation passes the focused test. The system-Perl
  upstream suite passes all 23 test programs (675 tests).
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
  upstream suite is covered by the full passing run above. The separate
  `autobox::Core` distribution, which depends on `Want`, is not bundled or
  covered by this provider change.

The preceding implementation delivered the two reported syntax-provider cases,
passed the tested Object::Pad consumer path, and passed the full autobox 3.0.2
distribution suite.
The documented Object::Pad subset remains unresolved. Function::Parameters
passes its complete 2.002006 upstream suite. The separate `autobox::Core`
distribution remains outside this scope;
assess it independently before claiming compatibility beyond the autobox
3.0.2 API and tests. The latest autobox delegated-import change is not yet
validated on PerlOnJava; its required full build and tests remain pending.

## Related references

- [Issue queue](issue-queue.md)
- [PerlOnJava debugging workflow](../../.agents/skills/debug-perlonjava/SKILL.md)

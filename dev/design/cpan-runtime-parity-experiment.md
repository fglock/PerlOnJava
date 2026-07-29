# CPAN Runtime Parity Experiment

## Objective

Replace distribution-specific CPAN patches with reusable PerlOnJava runtime
semantics wherever the original distribution is expressing valid Perl
behavior. A patch is removed only after the unmodified upstream test suite and
new standard-Perl-validated unit tests pass on both PerlOnJava backends.

## Current Compatibility Patches

### Test::FailWarnings

The removed patch skipped the synthetic `Warnings.java` frame while the module
walked `caller()`. Standard Perl attributes that frame to the `warnings`
package, allowing ordinary caller filters to identify it without knowing about
the Java implementation.

Runtime target:

- Java-backed Perl routines must invoke warning handlers with the same visible
  caller stack as an ordinary Perl subroutine.
- Direct `warn`, `warnings::warn`, categorized warnings, anonymous handlers,
  named handlers, JVM code, and interpreter code must agree.

Success criterion:

- Remove `Test-FailWarnings.yml` and `CallerOrigin.patch`.
- Run Test::FailWarnings 0.008 unmodified.

Outcome:

- `ExceptionFormatter` now derives the Perl package for Java-backed module
  frames from the active `RuntimeCode`.
- The obsolete extracted preference and patch are retired on upgrade.
- The unmodified distribution passes all 8 tests.

### DateTime::Format::CLDR

The removed patch converted non-ASCII regex literals to explicit `\x{...}`
escapes.
Standard Perl preserves the byte/UTF-8 distinction while dynamically
constructing and compiling the pattern.

Runtime target:

- Regex interpolation must retain whether each pattern fragment is a byte
  string or a character string.
- Backslash quoting a byte above `0x7f` must not create an invalid or
  semantically different pattern.

Success criterion:

- Remove `DateTime-Format-CLDR.yml` and `ByteSafePatternLiterals.patch`.
- Run DateTime::Format::CLDR 1.19 unmodified, including all 13,000+ assertions.

Outcome:

- Warning-aware concatenation now preserves byte semantics for proxy operands
  such as `$1`, matching the ordinary concatenation path and interpreter.
- The obsolete extracted preference and patch are retired on upgrade.
- The unmodified distribution passes 17 test files and 13,054 assertions.

### Term::ANSIColor::Markup

The patch replaces lvalue accessors with ordinary setters because
`Class::Accessor::Lvalue::Fast` depends on the XS-only `Want` module. It also
adds an undeclared `Test::Base` test prerequisite.

Runtime target:

- Determine the exact `Want` API and lvalue-subroutine behavior exercised by
  Class::Accessor::Lvalue.
- Prefer a reusable native `Want` implementation over modifying the consumer.
- Treat genuinely missing upstream prerequisite metadata separately from
  runtime parity.

Success criterion:

- Run Class::Accessor::Lvalue and Term::ANSIColor::Markup without changing
  their runtime source.
- Retain metadata-only routing only if the upstream test prerequisite cannot
  be discovered generically.

Experiment result:

- A native Want bridge over PerlOnJava's existing call-context stack was
  prototyped and validated against standard Perl.
- Unmodified Class::Accessor::Lvalue::Fast passed 12/12 and unmodified
  Term::ANSIColor::Markup passed 11/11 with that bridge.
- The prototype was not shipped in this change because eager or bundled
  integration exposed an unrelated readonly-pad weak-reference regression.
- The existing source compatibility patch remains until Want can be integrated
  without changing unrelated startup or compilation behavior.

### LRU::Cache

The distribution is XS-only. The current patch replaces it with a pure-Perl
implementation.

Runtime target:

- Evaluate a built-in native `LRU::Cache` module with the same object and
  functional APIs.
- Distinguish normal module availability from the special request to test the
  original XS distribution itself.

Success criterion:

- Ordinary `use LRU::Cache` needs no distribution source patch.
- Document whether `jcpan -t LRU::Cache` can reasonably bypass the upstream XS
  build or must retain explicit compatibility routing.

Experiment result:

- XSLoader's bundled-shim fallback can run an unmodified upstream loader, but
  packaging the fallback globally was coupled to the same regression observed
  during the Want prototype.
- The distribution patch remains the scoped solution.
- The experiment found and fixed its constructor parity bug: both
  `LRU::Cache::new($capacity)` and `LRU::Cache->new($capacity)` now work.
- The patched distribution passes 14 test files and 190 assertions.

### Test::Class

The removed distropref ignored upstream test failures caused by three runtime
parity gaps: stale reverse inheritance after `@ISA` mutation, anonymous CODE
attributes dispatched after later named-sub attributes, and caller lines on
the right side of multi-line boolean expressions.

Runtime outcome:

- Package arrays named `@ISA` are marked as inheritance-sensitive. Structural
  mutations increment a monotonic generation and invalidate method/MRO caches;
  `mro::get_isarev` rebuilds only when that generation changes.
- Anonymous subs with non-builtin attributes receive a compile-time CODE
  placeholder, so `MODIFY_CODE_ATTRIBUTES` runs in Perl source order. Both
  backends attach the executable definition to that same CODE reference.
- Calls inside `&&`, `and`, `||`, and `or` report the outer logical
  expression's first line through scoped compiler metadata. Constant folding
  carries that metadata onto the surviving AST without changing runtime
  execution. Defined-or (`//`) retains its distinct RHS-line behavior.
- The interpreter unwraps one-element parenthesized lvalues before compiling
  increment/decrement operations. This fixes Test2::Hub's
  `++($self->{+COUNT})` pattern and lets the MRO regression use Test::More on
  both backends.
- `mro::get_linear_isa` hides the implicit `UNIVERSAL` fallback and its parents
  when introspecting other classes, while preserving explicit introspection of
  `UNIVERSAL` itself.
- The signed extracted Test::Class preference is retired on upgrade; user-owned
  CPAN preferences are not removed.
- The unmodified Test::Class 0.52 suite passes 57 files and 191 assertions.

## Guardrails

- Never modify upstream test files.
- Validate every new Perl unit test with standard Perl first.
- Test the JVM and interpreter backends independently.
- Capture all test output in `/tmp`.
- Wrap every `jperl`, `jcpan`, and `prove` invocation in `timeout`.
- Run full `make` before updating the PR.

## Progress Tracking

### Current Status: Test::Class runtime parity complete (2026-07-29)

### Completed Phases

- [x] Baseline compatibility patches established (2026-07-28)
  - All seven requested CPAN distributions pass.
  - PR #873 passed Ubuntu and Windows CI.
- [x] Phase 1: Differential runtime boundaries (2026-07-28)
  - Added standard-Perl-validated caller and byte-replacement unit tests.
  - Identified Java-backed caller package attribution and warning-aware
    concatenation as the reusable fixes.
- [x] Phase 2: Remove obsolete runtime-workaround patches (2026-07-28)
  - Removed Test::FailWarnings caller filtering.
  - Removed DateTime::Format::CLDR byte-pattern rewriting.
  - Added upgrade cleanup for their extracted YAML and patch files.
- [x] Phase 3: Evaluate remaining source patches (2026-07-28)
  - Validated native Want and bundled LRU prototypes.
  - Kept them experimental after detecting unrelated global-state coupling.
  - Fixed the existing LRU fallback's function-style constructor.
- [x] Phase 4: Remove the Test::Class failure override (2026-07-29)
  - Added mutation-aware `@ISA` generation tracking and reverse-MRO refresh.
  - Moved anonymous CODE attribute dispatch to compile-time source order.
  - Preserved Perl caller lines across boolean short-circuit compilation.
  - Fixed interpreter increment/decrement of parenthesized lvalues used by
    Test2::Hub.
  - Added standard-Perl-validated units for all runtime behaviors.
  - Removed and retired `Test-Class.yml`.
  - Verified unmodified Test::Class: 57 files, 191 tests.

### Next Steps

1. Design lazy native-module registration that cannot perturb unrelated
   compilation or readonly pad-constant identity.
2. Implement the remaining optree-dependent Want APIs (`BOOL`, `REF`,
   `ASSIGN`, `rreturn`, and `lnoreturn`) before advertising full Want parity.
3. Revisit a bundled LRU fallback after native-module isolation is in place.
4. Consider a manifest-based cleanup mechanism for all retired generated CPAN
   prefs and patches.

### Open Questions

- Should all Java-backed Perl frames use a formal native call-site abstraction
  rather than the current active-code package lookup?
- How should optree ownership and native-module loading be isolated from
  global readonly literal caches?
- Should bundled replacements satisfy `jcpan -t`, or only ordinary dependency
  resolution and module loading?

## Related Documents and Skills

- `dev/design/caller_line_number_fix.md`
- `dev/design/utf8_flag_parity.md`
- `dev/design/regex_jruby_joni.md`
- `.agents/skills/debug-perlonjava/SKILL.md`

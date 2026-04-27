# Moose Support for PerlOnJava

## Overview

This document outlines the path to supporting Moose on PerlOnJava. Moose is
Perl's most popular object system, providing a rich meta-object protocol (MOP)
for defining classes, attributes, roles, and method modifiers.

## Current Status

**Goal**: pass **477 / 478** Moose 2.4000 test files, i.e. everything
except `t/todo_tests/moose_and_threads.t` (already TODO upstream;
PerlOnJava does not implement `threads`).

**Today**: 71 / 478 fully-green via the Moose-as-Moo shim (after Phase 3).

The path from 56 to 477:

1. **Phases 3 → 6** (incremental shim widening, ~1 week total) take
   us to ~110–130 fully-green files. Ships immediate value to
   real-world Moose-using CPAN modules without bundling upstream
   Moose.
2. **Phase D** (bundle pure-Perl Moose, ~5 days) takes us from
   ~110–130 to **477 / 478**. Replaces the shim with the real
   upstream Moose distribution plus a single new file
   (`Class::MOP::PurePerl`, ~500 lines) that implements what
   Moose's 710 lines of XS would have provided.

The original "Quick path vs. real path" framing in earlier revisions
of this doc is now obsolete: we **did** the Quick path, **and** we
will do the real port — they're complementary, not alternatives.

### Out of scope

- **`threads`-only Moose tests** (1 file: `t/todo_tests/moose_and_threads.t`,
  already TODO upstream). PerlOnJava does not implement Perl threads;
  this test will be added to the distroprefs skip list during Phase D.
- **`fork` semantics**. Zero Moose tests use `fork`; not relevant here.
- Real JVM-level class generation (Byte Buddy / Javassist / additional
  ASM use beyond what PerlOnJava already does). `Class::MOP` operates
  on Perl stashes, not `java.lang.Class`, so no third-party bytecode
  library is required for correctness. The optional "make_immutable
  inlining" optimization can reuse the existing ASM infrastructure
  if/when pursued.

### Already covered in core PerlOnJava

These are listed only because they were "out of scope" / "blockers" in
earlier revisions of this document; they no longer are:

- **`weaken` / `isweak`** — implemented in core (cooperative reference
  counting on top of JVM GC). See `dev/architecture/weaken-destroy.md`.
- **`DESTROY` / `DEMOLISH` timing** — implemented in core; fires
  deterministically for tracked blessed objects. Moose's `DEMOLISH`
  chain falls out of `DESTROY` working correctly; nothing
  Moose-specific is needed.
- **`B` module subroutine name/stash introspection** — done (Phase 1).

### Verified status (run on master, Apr 2026)

| Component | Status | Verification |
|-----------|--------|--------------|
| `B::CV->GV->NAME` | **Works** | `./jperl -e 'sub f{} use B; print B::svref_2object(\&f)->GV->NAME'` → `f` |
| `Sub::Identify::get_code_info` | **Works** | Returns `("main","f")` for `\&f` |
| Moo | **Works** | `use Moo; has ...; ->new(...)` (~96% of upstream test suite) |
| `Try::Tiny` | Works | `use Try::Tiny` succeeds |
| `Module::Runtime` | Works | `use Module::Runtime` succeeds |
| `Devel::GlobalDestruction` | Works | `use Devel::GlobalDestruction` succeeds |
| `Devel::StackTrace` | Works | `use Devel::StackTrace` succeeds |
| `Devel::OverloadInfo` | Works | `use Devel::OverloadInfo` succeeds |
| `Sub::Exporter` | Works | `use Sub::Exporter` succeeds |
| `Sub::Install` | Works | `use Sub::Install` succeeds |
| `Sub::Identify` | Works | `use Sub::Identify` succeeds |
| `Data::OptList` | Works | `use Data::OptList` succeeds |
| `Class::Load` | Works | `use Class::Load` succeeds |
| `Package::Stash` | Works | `use Package::Stash` succeeds |
| `Eval::Closure` | Works | `use Eval::Closure` succeeds |
| `Params::Util` | Works (no env var needed) | `_CLASS("Foo")` returns truthy |
| `B::Hooks::EndOfScope` | Works | `use B::Hooks::EndOfScope` succeeds |
| `Package::DeprecationManager` | Loads, requires `-deprecations => {...}` import args (normal upstream behavior) | — |
| `Class::MOP` | **Missing** | Not bundled |
| `Moose` | **Missing** | Not bundled; CPAN install fails (XS) |
| `ExtUtils::HasCompiler` | Returns false in practice | Returns undef early because `$Config{usedl}` is empty |

### Real blockers

| Blocker | Severity | Description |
|---------|----------|-------------|
| `Class::MOP` not bundled | **Critical** | Moose can't load; even simple `use Moose` fails |
| Moose's `Makefile.PL` builds 13 `.xs` files | **Critical** | Compiler-check bypass alone is insufficient; MM still tries to compile |
| `Moose.pm` not bundled | **Critical** | No alternative entry point on disk |
| MAGIC-based export tracking in `Moose::Exporter` | Low | Affects re-export warnings only |

---

## Why Phase 1 was the prerequisite

Moose uses `Class::MOP::get_code_info($coderef)` (and `Sub::Identify`'s
identical helper) to:

1. Decide whether a method belongs to a class or was imported.
2. Track method origins during role composition.
3. Tell defined subs from re-exported ones.
4. Build method maps and override tables.

PerlOnJava now stores `subName`/`packageName` on `RuntimeCode`
(`src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java`), and
`B.pm`'s `B::CV`/`B::GV` accessors read them. This is the foundation on which
either path below can build.

---

## Quick path: pure-Perl Moose shim via Moo

**Goal**: get `use Moose;` working for the common subset (attributes,
inheritance, roles, method modifiers) by delegating to Moo.

### Deliverables

- `src/main/perl/lib/Moose.pm` — shim that translates `Moose` API into Moo
  calls.
- `src/main/perl/lib/Moose/Role.pm` — delegates to `Moo::Role`.
- `src/main/perl/lib/Moose/Util/TypeConstraints.pm` — minimal stub providing
  `subtype`, `enum`, `as`, `where`, `coerce`, `class_type`, `role_type`.
- `src/main/perl/lib/Moose/Object.pm` — base class with `new`, `BUILD`,
  `BUILDARGS`, `meta` (returning a stub metaclass).

Reference implementations to mine: `Mo::Moose`, `Any::Moose`, and
`MouseX::Types::Moose` (already present in `~/.perlonjava/lib/`).

### Acceptance criteria

```bash
./jcpan -t ANSI::Unicode      # currently FAIL → must PASS
./jcpan -t Test::Class::Moose # smoke test
```

### Limitations of this path

- No real metaclass introspection (`$class->meta->get_all_attributes` etc.).
- Type constraints are name-only; no `MooseX::Types`.
- `Moose::Exporter`-based modules that call deep MOP APIs won't work.

This is enough for the long tail of CPAN modules that just declare attributes
and method modifiers.

---

## Real path: bundle pure-Perl `Class::MOP` + `Moose`

### Phase A — `ExtUtils::HasCompiler` deterministic stub

The upstream module currently lives at `~/.perlonjava/lib/ExtUtils/HasCompiler.pm`
and returns false only because `$Config{usedl}` happens to be empty. Make this
explicit and authoritative:

```perl
# src/main/perl/lib/ExtUtils/HasCompiler.pm
package ExtUtils::HasCompiler;
our $VERSION = '0.025';
use strict; use warnings;
use base 'Exporter';
our @EXPORT_OK = qw/can_compile_loadable_object can_compile_static_library can_compile_extension/;
our %EXPORT_TAGS = (all => \@EXPORT_OK);

sub can_compile_loadable_object { 0 }
sub can_compile_static_library  { 0 }
sub can_compile_extension       { 0 }
1;
```

Verify with:

```bash
./jperl -MExtUtils::HasCompiler=can_compile_loadable_object \
        -e 'print can_compile_loadable_object(quiet=>1) ? "yes" : "no"'
# → no
```

**Important**: this alone does **not** unblock Moose 2.4000. Its generated
`Makefile.PL` contains:

```perl
OBJECT => "xs/Attribute\$(OBJ_EXT) ... mop\$(OBJ_EXT)",
XS => { "xs/Attribute.xs" => "xs/Attribute.c", ... },  # 13 .xs files
C  => [ ... ],
```

After the compiler-check bypass, `WriteMakefile` will still attempt to compile
those. We need Phase B too.

### Phase B — strip XS in `WriteMakefile` on PerlOnJava

Two sub-options:

**B1.** Patch `src/main/perl/lib/ExtUtils/MakeMaker.pm` (already PerlOnJava's
own copy) so it scrubs `OBJECT`, `XS`, `C`, `H`, `XSPROTOARG`, `XSOPT` from
the args before generating the Makefile. Gate behind a config flag so other
modules with optional XS keep working.

**B2.** Bundle our own `Moose.pm` (Phase D) so the upstream
`Moose-2.4000/Makefile.PL` never runs.

Preferred: **B1** — it's a one-time investment that helps every XS module
that ships pure-Perl fallbacks.

### Phase C — Java `Class::MOP` helpers

Create `src/main/java/org/perlonjava/runtime/perlmodule/ClassMOP.java`. Most
of `Class::MOP` is already pure Perl upstream and runs unmodified once the
helpers exist. We only need Java for the irreducible pieces:

| Function | Trivial after Phase 1? | Notes |
|----------|------------------------|-------|
| `get_code_info($cv)` | Yes | Read `RuntimeCode.packageName`/`subName` |
| `is_stub($cv)` | Yes | Check that the code body is empty |
| `_definition_context()` | Yes | Capture `caller(1)` |
| `_flag_as_reexport($glob)` | No | Needs MAGIC; defer to Phase E |
| `_export_is_flagged($glob)` | No | Needs MAGIC; defer to Phase E |
| `INSTALL_SIMPLE_READER` etc. | Optional | Pure-Perl version is fine; Java only for speed |

Skeleton:

```java
package org.perlonjava.runtime.perlmodule;

public class ClassMOP extends PerlModuleBase {
    public ClassMOP() { super("Class::MOP", false); }

    public static void initialize() {
        ClassMOP m = new ClassMOP();
        try { m.registerMethod("get_code_info", null); }
        catch (NoSuchMethodException e) { throw new RuntimeException(e); }
    }

    public static RuntimeList get_code_info(RuntimeArray args, int ctx) {
        RuntimeScalar cv = args.get(0);
        if (cv.type != RuntimeScalarType.CODE) return new RuntimeList();
        RuntimeCode code = (RuntimeCode) cv.value;
        RuntimeList r = new RuntimeList();
        r.add(new RuntimeScalar(code.packageName != null ? code.packageName : "main"));
        r.add(new RuntimeScalar(code.subName     != null ? code.subName     : "__ANON__"));
        return r;
    }
}
```

Wire it up in `org.perlonjava.runtime.perlmodule` initialization next to
`Mro`, `Internals`, etc.

### Phase D — bundle pure-Perl `Class::MOP` and `Moose`

Drop the upstream `.pm` tree (without the `xs/` and `mop.c`) into
`src/main/perl/lib/Class/MOP*` and `src/main/perl/lib/Moose*`. Add a small
`PERL_CLASSMOP_PP=1`-style wrapper that forces every `Class::MOP::*` module
to skip `Class::Load::XS`/XS-only branches.

### Phase E — export-flag magic (optional)

Lower priority; only affects `Moose::Exporter` re-export tracking. Implement
as a `WeakHashMap<GlobReference, ExportFlags>` on the Java side, exposed
through helper subs `Moose::Exporter::_set_flag`/`_get_flag`.

---

## Verification matrix

```bash
# Phase 1 sanity (already passes)
./jperl -e 'sub f{} use B; print B::svref_2object(\&f)->GV->NAME'   # → f

# After Quick path (shim)
./jcpan -t ANSI::Unicode                                            # → PASS
./jperl -MMoose -e 'package P { use Moose; has x => (is=>"rw") } P->new(x=>1)'

# Run upstream Moose's full test suite against the shim (no install)
./jcpan -t Moose                                                    # → see baseline below

# After Phase A (HasCompiler stub)
./jperl -MExtUtils::HasCompiler=can_compile_loadable_object \
        -e 'print can_compile_loadable_object(quiet=>1) ? "yes" : "no"'  # → no

# After Phase B (XS-strip in WriteMakefile)
./jcpan -t Moose                                                    # → install OK, more tests pass

# After Phase C+D (real Class::MOP / Moose)
./jperl -MClass::MOP -e 'print Class::MOP::get_code_info(\&Class::MOP::class_of)'
./jcpan -t Moose
./jcpan -t MooseX::Types
```

### Running upstream Moose's test suite against the shim

`./jcpan -t Moose` is wired up via a CPAN distroprefs entry shipped from
`src/main/perl/lib/CPAN/Config.pm` (auto-bootstrapped to
`~/.perlonjava/cpan/prefs/Moose.yml` on first run). It:

- ensures `Moo` is installed before testing — the shim delegates to Moo,
  so the `pl:` step calls a tiny Perl helper
  (`PerlOnJava::Distroprefs::Moose::bootstrap_pl_phase`) that does
  `require Moo` and falls back to `system $ENV{JCPAN_BIN}, "Moo"` if
  Moo isn't loadable;
- creates a stub `Makefile` so CPAN.pm's "no Makefile created" fallback
  path doesn't kick in (also done by the same helper);
- skips `make` and `install` (`PerlOnJava::Distroprefs::Moose::noop`,
  cross-platform replacement for POSIX `true`);
- runs `prove --exec jperl -r t/` against the unpacked tarball.

`jcpan` / `jcpan.bat` prepend the project directory to `PATH` so
shell-spawned subprocesses (CPAN's distroprefs commandlines, prove's
child processes) find `jperl` on both Unix and Windows. They also
export `JCPAN_BIN` for the helper to recursively call jcpan when Moo
needs installing.

This design avoids POSIX-only shell constructs — `||`, `;`, `touch`,
`/dev/null`, `$VAR` — that don't work in Windows `cmd.exe`. Each phase
is a single `jperl -MPerlOnJava::Distroprefs::Moose -e '...'` (or
`prove --exec jperl ...`) invocation, parsed identically by `bash`,
`sh`, `cmd.exe`, and PowerShell.

We deliberately avoid a CPAN `depends:` block — it would force CPAN to
resolve Moose's full upstream prereq tree (`Package::Stash::XS`,
`MooseX::NonMoose`, …), most of which is XS and unsatisfiable. The
helper installs only `Moo`, the real runtime dependency of the shim.

Because `prove --exec` invokes `jperl` per test file without adding
`lib/` or `blib/lib/` to `@INC`, the **bundled shim from the jar** wins
over the unpacked upstream `lib/Moose.pm`. So you can run the entire
upstream suite end-to-end and see honestly which tests pass, without
patching Moose's `Makefile.PL` or shipping a fragile diff.

The same recipe is the model for any future "test against shim, don't
install" scenario — define a distroprefs entry that overrides `pl` /
`make` / `install` with no-ops and `test` with a `prove --exec` line.

### Quick-path baseline (Moose 2.4000)

Snapshot history from `./jcpan -t Moose` against the current shim:

| Metric | Initial shim | After refcount/DESTROY (Apr 2026) | After Phase A + C-mini (Apr 2026) | After Phase 2 stubs (Apr 2026) | After Phase 3 (Apr 2026) |
|---|---|---|---|---|---|
| Test files executed | 478 | 478 | 478 | 478 | 478 |
| Individual assertions executed | 616 | 616 | 667 | 1419 | **2450** |
| Fully passing files | ~29 | 35 | 36 | 56 | **71** |
| Partially passing files | ~44 | 94 | 98 | 184 | **259** |
| Compile/load fail (missing `Class::MOP::*`, `Moose::Meta::*`) | ~405 | ~349 | ~344 | ~238 | **~148** |
| Assertions ok | 370 | 372 | 419 | 953 | **1562** |
| Assertions fail | 246 | 244 | 248 | 466 | **888** |

The initial 29 fully-passing files covered BUILDARGS / BUILD chains,
immutable round-trips, anonymous role creation, several Moo↔Moose bug
regressions, the cookbook recipes for basic attribute / inheritance /
subtype use, and the Type::Tiny integration test. The 44 partials
included high-value chunks such as `basics/import_unimport.t` (31/48),
`basics/wrapped_method_cxt_propagation.t` (6/7), and
`recipes/basics_point_attributesandsubclassing.t` (28/31).

The refcount/DESTROY merge (PRs #565, #566, plus weaken/destroy work)
moved the structural picture meaningfully even though the assertion
total only nudged: ~56 files that previously failed at compile/load
time now run subtests. Most ended up partial rather than fully green
(partials roughly doubled, 44 → 94), but six more files are fully
passing (29 → 35). The shim's per-test infrastructure (BUILD chains,
DEMOLISH ordering, weak refs) is now solid; the remaining failures
are dominated by missing `Class::MOP::*` and `Moose::Meta::*`
introspection APIs.

**Phase A + Phase C-mini** (this PR) added two pieces:

- `ExtUtils::HasCompiler` deterministic stub
  (`src/main/perl/lib/ExtUtils/HasCompiler.pm`) — always reports "no
  compiler", instead of relying on `$Config{usedl}` happening to be
  empty.
- `Class::MOP` shim (`src/main/perl/lib/Class/MOP.pm`) — provides
  `class_of`, `get_metaclass_by_name`, `store_metaclass_by_name`,
  `remove_metaclass_by_name`, `does_metaclass_exist`,
  `get_all_metaclasses` (and friends), `get_code_info`,
  `is_class_loaded`, `load_class`, `load_first_existing_class`. Returns
  "no metaclass" everywhere, which is the correct answer under the
  Moose-as-Moo shim. The previous behavior was a hard "Undefined
  subroutine &Class::MOP::class_of called" the moment Moo's
  `_Utils::_load_module` hit a not-installed dependency on a class
  that already had `Moose.pm` loaded.

Net effect of Phase A + C-mini: **+51 individual assertions now
execute** (616 → 667), **+47 newly pass** (372 → 419), and one more
file goes fully green (35 → 36). The four extra failures are
upstream tests that previously bailed before reaching their assertion
phase and now reach it; none are real regressions.

**Phase 2 stubs** (a follow-up PR) added the next batch of
compile-time blockers and a bailout fix:

- `Moose.pm` / `Moose::Role` now `use Class::MOP ()` at top-level so
  Moo's runtime calls to `Class::MOP::class_of` (made whenever
  `$INC{"Moose.pm"}` is set) are always defined. This was the cause of
  ~50+ "Undefined subroutine &Class::MOP::class_of" runtime errors on
  the previous baseline.
- `metaclass.pm` stub — installs a no-op `meta` method on the caller.
- `Test::Moose.pm` — covers `meta_ok`, `does_ok`, `has_attribute_ok`,
  `with_immutable`. Falls back to `$class->can($attr)` when no real
  metaclass is available.
- `Moose::Util.pm` — covers `find_meta`, `is_role`, `does_role`,
  `apply_all_roles`, `english_list`, `throw_exception`, plus
  trait/metaclass alias passes-through.
- Skeleton stubs for `Class::MOP::Class`, `Class::MOP::Attribute`,
  `Moose::Meta::Class`, `Moose::Meta::TypeConstraint::Parameterized`,
  `Moose::Meta::Role::Application::RoleSummation`, and
  `Moose::Exporter` — enough surface that `require X` succeeds and
  `X->new(...)` returns something with the methods upstream tests
  inspect.
- Pre-populated standard type-constraint stubs in
  `Moose::Util::TypeConstraints` (`Any`, `Item`, `Defined`, `Bool`,
  `Str`, `Num`, `Int`, `ArrayRef`, `HashRef`, `Object`, …). Without
  these, `t/type_constraints/util_std_type_constraints.t` would
  `BAIL_OUT("No such type ...")` and prove would stop, losing every
  test file that followed alphabetically (≈7 files / 50+ assertions).

Net effect of Phase 2: **+752 individual assertions now execute**
(667 → 1419), **+534 newly pass** (419 → 953), **+20 fully-green
files** (36 → 56), and -106 files now compile that previously
errored out at compile time. The +218 newly failing assertions are
mostly tests that hadn't reached their assertion phase before (so
"failure" is the honest answer); they include real shortcomings of
the stub (e.g. `Test::Moose::has_attribute_ok` doesn't know about
inherited Moo attributes) which would only be fixed by Phase D
(real Class::MOP / Moose port).

**Phase 3** (this PR's third step) added:

- Rich `Moose::_FakeMeta`: `@ISA` now includes `Class::MOP::Class`
  and `Moose::Meta::Class`, so `isa_ok($meta, ...)` checks pass.
  Implements `add_attribute`, `get_attribute`, `find_attribute_by_name`,
  `has_attribute`, `remove_attribute`, `get_attribute_list`,
  `get_all_attributes` (walks @ISA), `get_method` (returns a
  `Class::MOP::Method`), `has_method`, `get_method_list`,
  `new_object`, `superclasses`, `linearized_isa`, `is_immutable`,
  `is_mutable`, `roles`, `does_role`, plus a per-class meta cache so
  `$class->meta` returns the same object each call.
- `Moose.pm` and `Moose/Role.pm` now record each `has` declaration on
  the target's `_FakeMeta`, so `$meta->get_attribute_list` and
  `find_attribute_by_name` actually return useful data.
- New compile-time stubs: `Class::MOP::Method`, `Class::MOP::Instance`,
  `Class::MOP::Method::Accessor`, `Class::MOP::Package`,
  `Moose::Meta::Method`, `Moose::Meta::Attribute`, `Moose::Meta::Role`
  (with `create_anon_role`), `Moose::Meta::Role::Composite`,
  `Moose::Meta::TypeConstraint`, `Moose::Meta::TypeConstraint::Enum`,
  `Moose::Util::MetaRole` (with `apply_metaroles` no-op),
  `Moose::Exception` (with overloaded stringification + `throw`).
- `Moose::Util::TypeConstraints::_Stub` now `@ISA`-inherits from
  `Moose::Meta::TypeConstraint`, so type stubs pass `isa_ok($t,
  'Moose::Meta::TypeConstraint')`.
- `Moose::Util::TypeConstraints::_store` now blesses results into
  `_Stub` (was returning unblessed hashrefs, which produced "Can't
  call method 'check' on unblessed reference" failures).
- New: `find_or_parse_type_constraint` (handles `Maybe[Foo]`,
  `Foo|Bar`, `ArrayRef[Foo]`, `HashRef[Foo]`,
  `ScalarRef[Foo]`).
- New: `export_type_constraints_as_functions`.
- `Moose.pm` pre-loads `Moose::Util::MetaRole` so MooseX::* extensions
  that call `apply_metaroles` without an explicit `use` line don't
  fail with "Undefined subroutine".

Net effect of Phase 3: **+1031 individual assertions now execute**
(1419 → 2450), **+609 newly pass** (953 → 1562), **+15 fully-green
files** (56 → 71), and -90 files now compile that previously errored
out at compile time. The +422 newly-failing assertions are again
mostly tests that hadn't reached their assertion phase before.

Cumulative across this PR (master baseline → end of Phase 3):
**+36 fully-green files (35 → 71)**, **+1834 assertions executed**
(616 → 2450), **+1190 newly passing** (372 → 1562).

Phase 3 hit clear diminishing returns toward the end (the last
iteration added only +2 fully-green files while +90 files now compile
that previously didn't), confirming the doc's call: shim widening is
losing leverage, and Phase D (bundle pure-Perl Moose) is the next
move that meaningfully advances the pass count.

Phases 4 → 6 (more shim widening) and Phase D (bundle pure-Perl
Moose) should move these numbers further; record the new totals
here whenever they shift.

---

### Lock in progress as bundled-module tests

`src/test/resources/module/{Distribution}/` is reserved for **unmodified
upstream test files** of CPAN distributions we actually bundle. Use it
**only** when both apply:

1. The distribution itself is bundled (its `.pm` files live in
   `src/main/perl/lib/`, or the test directory ships its own `lib/`).
2. The tests being copied are the upstream tests for **that** distribution.

So:

- When we eventually bundle a pure-Perl `Moose` (Phase D), copy
  `Moose-2.4000/t/...` into `src/test/resources/module/Moose/t/`.
- Same for `Class::MOP`, `MooseX::Types`, etc., as each gets bundled.
- Do **not** snapshot tests for downstream consumers we don't bundle
  (e.g. ANSI::Unicode). Those stay as `./jcpan -t` smoke checks.
- Do **not** put shim-specific or PerlOnJava-specific tests under
  `module/`. Shim coverage belongs in `src/test/resources/unit/` if it's
  needed beyond the `jcpan -t` smoke.

Conventions for bundled-distribution snapshots (see existing layouts under
`src/test/resources/module/`, e.g. `Clone-PP/`, `Math-BigInt/`,
`XML-Parser/`):

- One directory per CPAN distribution (`Moose/`, `Class-MOP/`, …); use the
  dist name with `::` replaced by `-`.
- Mirror the upstream `t/` layout exactly. Don't edit the test files; if a
  test is genuinely incompatible, prefer fixing the runtime over editing the
  test (per AGENTS.md).
- Tests are picked up automatically by the Gradle `testModule` task —
  no JUnit wiring is needed.

Verify with:

```bash
make test-bundled-modules
```

This gives us a regression net: every newly-passing upstream Moose-ecosystem
test we vendor in becomes guarded against regressions, and `git log
src/test/resources/module/Moose*` becomes the historical record of progress.

For the **current PR (Quick path / shim only)** there are no bundled
upstream distributions yet, so nothing is snapshotted under `module/`.
The regression net for the shim is `make` plus the `./jcpan -t
ANSI::Unicode` smoke check.

---

## Dependency graph (verified)

```
Moose                              ← MISSING
└── Class::MOP                     ← MISSING (Phase C+D)
    ├── MRO::Compat                ← upstream copy works
    ├── Class::Load                ← works
    │   ├── Module::Runtime        ← works
    │   ├── Data::OptList          ← works
    │   │   ├── Params::Util       ← works (no env var)
    │   │   └── Sub::Install       ← works
    │   └── Try::Tiny              ← works
    ├── Devel::GlobalDestruction   ← works
    ├── Devel::OverloadInfo        ← works
    ├── Devel::StackTrace          ← works
    ├── Dist::CheckConflicts       ← works
    ├── Eval::Closure              ← works
    ├── Package::DeprecationManager← works (normal import-arg requirement)
    ├── Package::Stash             ← works
    ├── Sub::Exporter              ← works
    ├── Sub::Identify              ← works (Phase 1)
    ├── List::Util                 ← built-in
    ├── Scalar::Util               ← built-in
    └── B::Hooks::EndOfScope       ← works
```

The whole "needs investigation" / "needs PP flag" column from the previous
revision of this doc is gone — every `Class::MOP` runtime dependency that
isn't `Class::MOP` itself loads cleanly today.

---

## Progress Tracking

### Current Status

Goal: pass **477 / 478** Moose 2.4000 test files (everything except
`t/todo_tests/moose_and_threads.t`, which is already TODO upstream
and PerlOnJava doesn't implement `threads`). Today: **56 / 478**.

- **Phase 1 — DONE.** B-module subroutine name/stash introspection works.
- **Quick path — DONE.** `Moose.pm` shim ships, ANSI::Unicode-class modules unblocked.
- **Phase A — DONE.** `ExtUtils::HasCompiler` deterministic stub ships at `src/main/perl/lib/ExtUtils/HasCompiler.pm`.
- **Phase C-mini — DONE.** `Class::MOP` shim with `class_of` / `get_metaclass_by_name` / `get_code_info` / `is_class_loaded` and friends; ships at `src/main/perl/lib/Class/MOP.pm`.
- **Phase 2 stubs — DONE.** `metaclass.pm`, `Test::Moose.pm`, `Moose::Util.pm`, plus skeleton `Class::MOP::Class` / `Class::MOP::Attribute` / `Moose::Meta::Class` / `Moose::Meta::TypeConstraint::Parameterized` / `Moose::Meta::Role::Application::RoleSummation` / `Moose::Exporter`. Pre-populated standard type-constraint stubs to avoid `BAIL_OUT` in upstream test suite.
- **Phase 3 — DONE.** Rich `Moose::_FakeMeta` (with `@ISA` and full method surface), attribute-tracking via `has` wrapper, plus the next batch of compile-time stubs (`Class::MOP::Method` / `::Instance` / `::Method::Accessor` / `::Package`, `Moose::Meta::Method` / `::Method::Constructor` / `::Method::Destructor` / `::Method::Accessor` / `::Method::Delegation` / `::Attribute` / `::Role` / `::Role::Composite` / `::TypeConstraint` / `::TypeConstraint::Enum`, `Moose::Util::MetaRole`, `Moose::Exception`), `_Stub` blessed into `Moose::Meta::TypeConstraint`, `find_or_parse_type_constraint` + `export_type_constraints_as_functions` + `_parse_parameterized_type_constraint` + `get_type_constraint_registry`, method-modifier hooks on `_FakeMeta`, `Class::MOP.pm` pre-loads its submodules, `Moose.pm` pre-loads `Moose::Meta::*` and `Moose::Util*`. **71 / 478 fully-green.**
- **Phases 4 / 5 / 6 — not started.** Incremental shim widening. Ship value (~110–130 fully-green) but do not pass all tests on their own.
- **Phase D — not started.** Bundle pure-Perl Moose. **This is the phase that gets us to 477 / 478.** Now sized at ~5 days (was previously framed as much larger). See "Phase D plan" below.
- **Phase B — deferred.** Strip XS keys in `WriteMakefile`. Not on the Moose pass-all-tests critical path; the bundled Moose ships from the JAR.
- **Phase E — deferred.** Export-flag MAGIC. Affects warnings only.

### Completed

- [x] Phase 1: B-module subroutine name introspection
- [x] Verified working dependency tree (Apr 2026)
- [x] Quick path: `Moose.pm` / `Moose::Role` / `Moose::Object` / `Moose::Util::TypeConstraints` shims
- [x] Phase A: `ExtUtils::HasCompiler` deterministic stub
- [x] Phase C-mini: `Class::MOP` shim (no metaclass instances; just enough surface to keep Moo happy)
- [x] Phase 2 stubs: `metaclass.pm`, `Test::Moose.pm`, `Moose::Util.pm`, skeleton `Class::MOP::Class` / `Class::MOP::Attribute` / `Moose::Meta::Class` / `Moose::Exporter` / friends, and standard-type stubs in `Moose::Util::TypeConstraints` to suppress upstream `BAIL_OUT`.

### Lessons learned (post-Phase-2)

The two iterative shim PRs (#570, #572) turned the formal phase plan
above on its head: paths C-full / D were originally framed as the
"real fix", but in practice **incremental shim widening has paid out
much faster than a full pure-Perl port** would have. Concrete
takeaways:

1. **Compile-time stubs are the highest-leverage move.** Each round
   of "let `require X` succeed" cleared dozens of files at once
   (Phase 2 alone: 344 → 238 files that fail before any subtest).
2. **Pre-loading is as important as having the stub.** Once
   `Moose.pm` set `$INC{Moose.pm}`, Moo's runtime probes called
   `Class::MOP::class_of` from random call sites. Adding
   `use Class::MOP ()` at the top of `Moose.pm` / `Moose/Role.pm`
   killed ~50+ runtime errors that would otherwise have masked any
   shim widening.
3. **One BAIL_OUT can hide an arbitrary number of test files.**
   `t/type_constraints/util_std_type_constraints.t` calling
   `BAIL_OUT("No such type ...")` was costing us ~7 trailing files
   per run. Pre-populating standard type-constraint stubs cleanly
   contained that — but the lesson is general: any new failure mode
   that hits `BAIL_OUT` should be treated as a high-priority block.
4. **The Moose-as-Moo gap is mostly method surface, not metaclass
   semantics.** A large fraction of upstream tests just want
   `$meta->add_attribute`, `$meta->get_method`, `$meta->is_mutable`
   to exist and return a sensible-shaped value. They rarely care
   that the metaclass is "real". Ergo: enriching `Moose::_FakeMeta`
   is high-leverage and low-risk.
5. **Stub objects must `isa` the right things.** Upstream tests do
   `isa_ok($meta, 'Moose::Meta::Class')` and
   `isa_ok($attr, 'Moose::Meta::Attribute')`. Returning a plain
   blessed hashref isn't enough; the stub needs `@ISA` set to the
   real upstream class names so `isa` checks pass.

### Recommended next phases

The goal is to **pass all 478 Moose 2.4000 test files except the
threads-only test** (`t/todo_tests/moose_and_threads.t`, already a
TODO upstream). PerlOnJava does not implement `fork` or `threads`, but
the Moose suite is forgiving: zero tests use `fork`, and only that one
file uses `threads`. Everything else is in scope.

#### Strategy: incremental shim now, real port for the long tail

Phases 3 → 6 below are incremental shim widening. They ship value
quickly and are projected to take us from today's 56 / 478 fully-green
files to roughly 110–130 / 478 (~25–28%) — covering ordinary Moose
consumers (attributes, roles, method modifiers).

To pass the **rest** of the suite (immutable inlining, MOP self-tests,
role conflict messages, native traits, type-constraint coercion
graphs, `Class::MOP` self-bootstrap, ...) we then do **Phase D — bundle
pure-Perl Moose**. With weaken/DESTROY now in core PerlOnJava and only
710 lines of XS to replace (most of it generic hashref accessors),
Phase D is much smaller than its earlier "the real fix" framing
suggested. See "Phase D plan" below for the concrete breakdown.

Target outcome:
- **Phases 3 → 6**: ~110–130 / 478 fully-green files. Ships value
  to real-world Moose-using CPAN modules immediately.
- **Phase D (bundle + XS replacement)**: 477 / 478 fully-green files
  (everything except `moose_and_threads.t`). Anything still failing
  is a real bug in PerlOnJava core, not in the Moose port.

Phases in priority order:

#### Phase 3 — Rich `Moose::_FakeMeta` and the next batch of stubs

Estimated payoff: similar to Phase 2 (+15–25 fully-green files,
+200–500 newly-passing assertions). Estimated effort: ~1 day.

3a. **Enrich `Moose::_FakeMeta`** so `isa_ok($meta, 'Moose::Meta::Class')`
    passes and the methods upstream tests reach for actually exist:

    | Method                  | Failure count in last run |
    |-------------------------|---------------------------|
    | `add_attribute`         | 24                        |
    | `get_attribute`         | 8                         |
    | `new_object`            | 4                         |
    | `is_mutable`            | 3                         |
    | `get_method`            | 3                         |
    | `meta`                  | 4                         |
    | (FakeMeta isa Class::MOP::Class) | 6                |
    | (FakeMeta isa Moose::Meta::Class) | 4               |

    Fix: add `our @ISA = ('Class::MOP::Class', 'Moose::Meta::Class');`
    to `Moose::_FakeMeta`, and implement the missing methods either
    as pass-throughs to the underlying Moo metaclass (via
    `Moo->_constructor_maker_for($class)->all_attribute_specs`) or as
    minimal "remember what `has` declared" tracking inside
    `Moose.pm`'s `import`.

3b. **Add the next batch of compile-time `.pm` stubs** for the most
    common "Can't locate" failures:

    | Stub                                      | Errors |
    |-------------------------------------------|--------|
    | `Moose::Meta::Attribute`                  | 8      |
    | `Moose::Meta::Role`                       | 6      |
    | `Moose::Meta::Role::Composite`            | 7      |
    | `Class::MOP::Method`                      | 7      |
    | `Class::MOP::Instance`                    | 4      |
    | `Moose::Util::MetaRole` (with `apply_metaroles` no-op) | 4 + 9 calls |
    | `Moose::Meta::TypeConstraint`             | 3      |
    | `Moose::Exception` (and the most-thrown subclasses) | 3 + many `throw_exception` calls |

    Each is the same shape as the existing skeleton stubs:
    `package X; require Y; our @ISA = (Y); sub new { bless {...} } 1;`.

3c. **Bless `Moose::Util::TypeConstraints::_Stub` into
    `Moose::Meta::TypeConstraint`** so `isa_ok($t,
    'Moose::Meta::TypeConstraint')` passes (5 errors today).

3d. **Add the missing methods on `Moose::Util::TypeConstraints`**:

    | Method                           | Errors |
    |----------------------------------|--------|
    | `export_type_constraints_as_functions` | 5  |
    | `find_or_parse_type_constraint`        | 3  |

3e. **`Moose::Meta::Role->create_anon_role`** as a no-op returning
    a `_FakeRole` (4 errors).

#### Phase 4 — Real attribute introspection on top of Moo

Estimated payoff: medium-high (+100–300 newly-passing assertions,
mostly under `t/basics/`, `t/attributes/`, `t/cmop/attribute*`).
Estimated effort: ~2 days.

By Phase 3, `$meta->add_attribute(name => ..., is => 'rw')` exists
but is a no-op. To make `$meta->get_attribute_list` / `$meta->get_attribute(...)`
return useful values, hook into Moo's actual attribute store:

```perl
sub get_attribute_list {
    my $self = shift;
    my $name = $self->name;
    require Moo::_Utils;
    return keys %{ Moo->_constructor_maker_for($name)->all_attribute_specs // {} };
}
```

Same trick for `get_attribute`, `find_attribute_by_name`, etc.; wrap
each Moo attribute spec in a `Class::MOP::Attribute` stub. This makes
`Test::Moose::has_attribute_ok` actually test what users mean.

#### Phase 5 — `Moose::Util::MetaRole` real apply

Estimated payoff: low-medium (most MooseX::* extensions need it; few
upstream Moose tests do). Estimated effort: ~1 day.

`Moose::Util::MetaRole::apply_metaroles` is what
`MooseX::*` extensions use to install custom metaclass roles. A real
implementation needs to compose roles into the metaclass at
install-time — under the shim, "compose into Moo metaclass" is a no-op
that just records the role list, which is enough for most consumers.

#### Phase 6 — `Moose::Exporter` proper sugar installation

Estimated payoff: medium (unlocks every "extends Moose with custom
sugar" module: `MooseX::SimpleConfig`, `MooseX::Getopt`, ...).
Estimated effort: ~2–3 days.

The current `Moose::Exporter` stub only forwards to `Moose->import`.
A more complete version would install the caller's `with_caller` /
`with_meta` / `as_is` exports onto consumers.

#### Phase D — Bundle pure-Perl Moose (the destination)

This is the phase that gets us to **477 / 478 passing** (everything
except the threads-only TODO test).

**Phase D status (Apr 2026)**: started but **paused on a core PerlOnJava
refcount bug**. Findings recorded here so the next attempt picks up
where this one left off.

##### Pre-Phase-D plan-review findings

Before trying to bundle Moose, the plan was reviewed for hidden
problems. Two real issues surfaced; one is fixed, one remains.

1. **`*GLOB{SCALAR}` returned the value instead of a SCALAR reference.**
   PerlOnJava core bug: `*x{SCALAR}` yielded the scalar's value where
   real Perl yields a SCALAR ref. Fixed in the same PR
   (`RuntimeGlob.java` line 554-565: now calls `createReference()` like
   the ARRAY/HASH/GLOB cases). Regression test in
   `src/test/resources/unit/typeglob.t`. This unblocked
   `Class::Load::PP::_is_class_loaded`, `Package::Stash::PP::get_symbol`,
   and many other modules that read `$VERSION` via the symbol table.

2. **`prove --not` does not exist.** Workaround when Phase D resumes:
   use a small `--exec` wrapper that returns
   `1..0 # SKIP threads not implemented` for
   `t/todo_tests/moose_and_threads.t` and runs `jperl` for every other
   file. ~10 lines of Perl, easy to ship.

##### Resolved blocker: weaken refcount bug — DONE

When weaken was called on a hash slot inside a sub, with the target
also held by other strong refs in the caller, the slot became undef
immediately. Minimal reproduction:

```perl
require Scalar::Util;
my $m = bless {}, "M";
my %REG = (x => $m);

sub attach {
    my ($attr, $class) = @_;
    $attr->{ac} = $class;
    Scalar::Util::weaken($attr->{ac});
}

my @arr = ({}, {}, {});
for my $attr (@arr) {
    attach($attr, $REG{x});
}

# Real Perl: all three $arr[i]->{ac} are still defined (weak refs to
# $m, which has a strong ref via %REG).
# PerlOnJava (was, before fix): all three became undef immediately.
```

Class::MOP's bootstrap relies on this pattern pervasively
(`weaken($self->{associated_class} = $class)` in
`Class::MOP::Attribute::attach_to_class`, called for every attribute
during `Class::MOP.pm`'s self-bootstrap). Without the fix
`use Class::MOP;` itself died in the bootstrap.

###### Root cause

The auto-sweep (`MortalList.maybeAutoSweep` →
`ReachabilityWalker.sweepWeakRefs(true)`) was clearing weak refs to
blessed objects whose cooperative `refCount > 0` simply because the
walker couldn't see them as reachable. The walker only seeds from
package globals and `ScalarRefRegistry`; it doesn't seed from `my`
lexical hashes or arrays. A blessed object held only by a `my %REG`
in the caller's scope is therefore invisible to the walker —
"unreachable" — and got its weak refs cleared on every flush.

###### Fix

`ReachabilityWalker.sweepWeakRefs(quiet=true)` now skips clearing weak
refs whose referent has `refCount > 0`. Reasoning: PerlOnJava's
cooperative refCount can drift due to JVM temporaries, but a positive
refCount means at least one tracked container thinks it's holding a
strong reference. Auto-sweep should be conservative; explicit
`Internals::jperl_gc()` (non-quiet) still clears, since the user
opted in to aggressive cleanup.

Single-line change in
`src/main/java/org/perlonjava/runtime/runtimetypes/ReachabilityWalker.java`,
guarded by `quiet`. See the surrounding comment for the full
reproduction and rationale.

###### Verification (Step W6)

DBIx::Class is the most refcount-heavy CPAN distribution we test.
Before / after the fix:

| Metric | Baseline | After fix |
|---|---|---|
| Files executed | 314 | 314 |
| Assertions executed | 878 | 878 |
| Fully passing files | 11 | 11 |
| Failed files | 303 | 303 |
| Assertions failing | 2 | 2 |

**Zero regressions in DBIC.**

###### Verification (Step W7)

```bash
./jperl -e 'use Class::MOP; print "ok\n"'   # → ok
./jperl -e 'use Moose; print "ok\n"'        # → ok (still via shim)
```

Phase D resumption is now unblocked.

##### Phase D resumption checklist (when weaken is fixed)

The previous Phase D attempt got as far as:

- D1 (bundle upstream `.pm` files): branch `feature/moose-phase-d`
  copied `Moose-2.4000/lib/{Class,Moose,Test/Moose.pm,metaclass.pm,oose.pm}`
  into `src/main/perl/lib/`. **Branch was deleted** when Phase D paused;
  redo from `~/.cpan/build/Moose-2.4000-*/lib/`.
- D2 (`Class::MOP.pm` XSLoader patch): exact patch worked out.
  Replace the `XSLoader::load('Moose', $VERSION)` block at
  `Class::MOP.pm` line 31 with:
  ```perl
  XSLoader::load('Moose', $VERSION) if 0;
  {
      require Config;
      if ($ENV{MOOSE_PUREPERL} || !$Config::Config{usedl}) {
          require Class::MOP::PurePerl;
      }
      else {
          require XSLoader;
          XSLoader::load('Moose', $VERSION);
      }
  }
  ```
- D3 (`Class::MOP::PurePerl` skeleton): drafted in this attempt.
  Replicates the simple-reader installation from each of the 13 .xs
  files plus mop.c. Survived as a dead file in the deleted branch but
  the design is now well-known. The full inventory of what each .xs
  installs is documented above. Total replacement is < 500 lines.
- D4 (prereq verification), D5 (distroprefs), D6 (snapshot tests):
  not yet attempted.

##### Phase D — sub-phases (unchanged from earlier draft)

##### D1 — Bundle the upstream `.pm` files

Drop `Moose-2.4000/lib/Class/MOP*` and `Moose-2.4000/lib/Moose*` and
`Moose-2.4000/lib/metaclass.pm` and `Moose-2.4000/lib/Test/Moose.pm`
into `src/main/perl/lib/`. Replace our existing shim files (`Moose.pm`,
`Moose/Role.pm`, `Moose/Object.pm`, `Moose/Util/TypeConstraints.pm`,
`Class/MOP.pm`, `Test/Moose.pm`, `metaclass.pm`, and the various
skeleton `.pm` stubs from Phase 2). Snapshot upstream `Moose-2.4000/t/`
into `src/test/resources/module/Moose/t/` for regression coverage
(this is what AGENTS.md's "lock in progress" rule asks for).

Effort: ~½ day (mostly mechanical).

##### D2 — Patch `Class::MOP.pm` to skip `XSLoader::load`

Upstream `Class::MOP.pm` does an unconditional
`XSLoader::load('Moose', $VERSION)` at line 31. On PerlOnJava the
loader fails with "Can't load shared library on this platform" and
the whole module won't compile. Replace the `XSLoader::load` block
with:

```perl
if ($ENV{MOOSE_PUREPERL} || !$Config{usedl}) {
    require Class::MOP::PurePerl;
}
else {
    XSLoader::load('Moose', $VERSION);
}
```

PerlOnJava's `Config::usedl` is empty, so this routes to the
PurePerl module unconditionally. (The env var is for forcing PP on
real Perl during development.)

This is the only modification to upstream Moose code. Document it
prominently so future sync-ups with upstream don't drop it.

Effort: ~½ day.

##### D3 — Implement `Class::MOP::PurePerl`

The XS provides accessor methods on a handful of mixin classes.
None of them do anything clever — they all read/write hash slots
on the metaclass / attribute / method instances. The breakdown
(by `xs/*.xs` file):

| .xs file              | Lines | What it provides | PP replacement |
|-----------------------|-------|------------------|----------------|
| `Attribute.xs`        | 9     | BOOT only — pulls in shared accessor table | trivial |
| `AttributeCore.xs`    | 18    | Mixin readers: name / accessor / reader / writer / predicate / clearer / builder / init_arg / initializer / definition_context / insertion_order | one-liners over `$_[0]->{...}` |
| `Class.xs`            | 12    | BOOT only | trivial |
| `Generated.xs`        | 9     | BOOT only | trivial |
| `HasAttributes.xs`    | 9     | Mixin: `_attribute_map` reader | one-liner |
| `HasMethods.xs`       | 89    | `_method_map`, `add_package_symbol`-tied method install | pure-Perl `Package::Stash`-based |
| `Inlined.xs`          | 8     | BOOT only | trivial |
| `Instance.xs`         | 8     | BOOT only | trivial |
| `MOP.xs`              | 22    | `is_class_loaded`, `_inline_check_constraint`, etc. | already in our shim |
| `Method.xs`           | 23    | `body`, `name`, `package_name` accessors | one-liners |
| `Moose.xs`            | 148   | `Moose::Util::throw_exception_class_callback` and friends, init_meta hooks | most can defer to existing pure-Perl |
| `Package.xs`          | 8     | BOOT only | trivial |
| `ToInstance.xs`       | 63    | `Class::MOP::class_of` fast path, blessed-arg checks | one-liner with blessed/ref |
| `mop.c`               | 284   | Shared accessor-generation framework: `mop_install_simple_accessor`, `mop_class_check`, `mop_check_package_cache_flag` | ~150 lines of pure Perl |

Total Perl replacement: well under 500 lines. Most of it is
literally `sub name { $_[0]->{name} }`-shaped.

The actual implementation lives in **one new file**:
`src/main/perl/lib/Class/MOP/PurePerl.pm`. It walks the mixin packages
(`Class::MOP::Mixin::AttributeCore`, `Class::MOP::Mixin::HasAttributes`,
`Class::MOP::Mixin::HasMethods`, `Class::MOP::Method`, `Class::MOP::Package`,
`Class::MOP::Class`, `Class::MOP::Attribute`, `Class::MOP::Instance`)
and installs the accessors that upstream's XS would have installed,
plus the few non-accessor helpers (`_inline_check_constraint`,
`Class::MOP::class_of` PP version, etc.).

Reference: this is exactly what `Class::MOP::PurePerl.pm` would have
been before XS was added. The upstream commit that introduced the
XS (`bf38c2e9`, 2010) is a useful guide — its diff shows exactly
which Perl was replaced.

Effort: ~3 days. Most time goes to implementing & testing the
accessor packs, not architectural decisions.

##### D4 — Bundle pure-Perl Package::Stash and other prereqs

`Class::MOP::Package` does `use Package::Stash;`. Upstream
`Package::Stash` tries `Package::Stash::XS` first, falls back to
`Package::Stash::PP` if XS unavailable — this works as-is on
PerlOnJava (we've verified `use Package::Stash` succeeds today).

Other prereqs already verified working on PerlOnJava (per the
existing dependency-graph table earlier in this doc):
`Try::Tiny`, `Module::Runtime`, `Devel::GlobalDestruction`,
`Devel::StackTrace`, `Devel::OverloadInfo`, `Sub::Exporter`,
`Sub::Install`, `Sub::Identify`, `Data::OptList`, `Class::Load`,
`Eval::Closure`, `Params::Util`, `B::Hooks::EndOfScope`,
`Package::DeprecationManager`, `Dist::CheckConflicts`.

Effort: ~½ day to verify nothing regressed when we move from shim
to real `Class::MOP`.

##### D5 — Update distroprefs to skip the threads-only TODO test

Today's `Moose.yml` distropref runs `prove --exec jperl -r t/`. Add
an exclusion for `t/todo_tests/moose_and_threads.t`:

```yaml
test:
  commandline: 'prove --exec jperl -r t/ --not t/todo_tests/moose_and_threads.t'
```

(or, equivalent, use a `prove` ignore-file.)

Effort: ~10 minutes.

##### D6 — Snapshot tests under `module/Moose/t/`

Per AGENTS.md's bundled-modules rule, copy `Moose-2.4000/t/` (minus
the threads file) into `src/test/resources/module/Moose/t/`. Add the
new directory to `make test-bundled-modules`. From then on,
regressions in any of the 477 passing files are caught by `make`.

Effort: ~½ day.

##### Phase D total

| Sub-phase | Effort |
|-----------|--------|
| D1: bundle upstream `.pm` files | ½ day |
| D2: patch `Class::MOP.pm` XSLoader skip | ½ day |
| D3: implement `Class::MOP::PurePerl` | 3 days |
| D4: prereq verification | ½ day |
| D5: distroprefs threads-test exclusion | 10 min |
| D6: snapshot tests under `module/Moose/` | ½ day |
| **Total** | **~5 days** |

**Outcome**: 477 / 478 fully-green files
(everything except `t/todo_tests/moose_and_threads.t`). Anything
still failing after Phase D is a real bug in PerlOnJava core (not in
the Moose port) and gets fixed in core.

#### Phase B (deferred) — strip XS in `WriteMakefile`

After Phase D the bundled Moose ships from the JAR; users don't run
`cpan -i Moose`. Phase B becomes useful only when somebody wants to
install a *different* XS distribution that has a pure-Perl fallback
the way Moose does. Not part of the Moose plan.

#### Phase E (deferred) — Export-flag MAGIC

Affects `Moose::Exporter` re-export-tracking warnings only. The
real Moose's `Moose::Exporter` will surface a warning instead of
hard-failing when this magic is missing — acceptable. Not part of
the Moose pass-all-tests plan.

### Open work items

Optimistic order (Phases 3 → 6 ship value incrementally; D is the
destination):

- [x] **Phase 3a**: enriched `Moose::_FakeMeta` (`@ISA` includes
      `Class::MOP::Class` + `Moose::Meta::Class`; added
      `add_attribute` / `get_attribute` / `find_attribute_by_name` /
      `has_attribute` / `remove_attribute` / `get_attribute_list` /
      `get_all_attributes` / `get_method` / `has_method` /
      `get_method_list` / `new_object` / `is_mutable`).
- [x] **Phase 3b**: added next batch of compile-time `.pm` stubs
      (`Class::MOP::Method`, `Class::MOP::Instance`,
      `Class::MOP::Method::Accessor`, `Class::MOP::Package`,
      `Moose::Meta::Method`, `Moose::Meta::Attribute`,
      `Moose::Meta::Role`, `Moose::Meta::Role::Composite`,
      `Moose::Meta::TypeConstraint`, `Moose::Meta::TypeConstraint::Enum`,
      `Moose::Util::MetaRole`, `Moose::Exception`).
- [x] **Phase 3c**: blessed `Moose::Util::TypeConstraints::_Stub`
      into `Moose::Meta::TypeConstraint`.
- [x] **Phase 3d**: added `export_type_constraints_as_functions` and
      `find_or_parse_type_constraint` to
      `Moose::Util::TypeConstraints`.
- [x] **Phase 3e**: added `Moose::Meta::Role->create_anon_role`.
- [ ] **Phase 4**: hook into Moo's attribute store from
      `Moose::_FakeMeta->get_attribute*` methods.
- [ ] **Phase 5**: real-ish `Moose::Util::MetaRole::apply_metaroles`.
- [ ] **Phase 6**: full `Moose::Exporter` sugar installation.
- [ ] **Phase D1**: drop upstream `Moose-2.4000/lib/{Class/MOP*,Moose*,
      metaclass.pm,Test/Moose.pm}` into `src/main/perl/lib/`,
      replacing the shim files.
- [ ] **Phase D2**: patch `src/main/perl/lib/Class/MOP.pm`'s
      `XSLoader::load` block to fall back to
      `Class::MOP::PurePerl` when `!$Config{usedl}`.
- [ ] **Phase D3**: implement `src/main/perl/lib/Class/MOP/PurePerl.pm`
      (~500 lines pure Perl; replaces `xs/*.xs` + `mop.c`). Mining
      reference: upstream Moose pre-XS commit `bf38c2e9`.
- [ ] **Phase D4**: verify all `Class::MOP` runtime dependencies
      still load cleanly with the bundled (vs shim) `Class::MOP`.
- [ ] **Phase D5**: edit `src/main/perl/lib/CPAN/Config.pm`'s
      `Moose.yml` distropref to skip
      `t/todo_tests/moose_and_threads.t`.
- [ ] **Phase D6**: snapshot `Moose-2.4000/t/` (minus the threads
      test) into `src/test/resources/module/Moose/t/` so
      `make test-bundled-modules` enforces no regressions.
- [ ] After Phase D: write a one-line note at the top of this doc
      saying "passes 477/478 of upstream Moose 2.4000". Update
      `dev/modules/cpan_compatibility.md` if it tracks Moose.

Phases B / E remain deferred as before — they're not on the Moose
"pass all tests" critical path.

---

## Related Documents

- [xs_fallback.md](xs_fallback.md) — XS fallback mechanism
- [makemaker_perlonjava.md](makemaker_perlonjava.md) — MakeMaker implementation
- [cpan_client.md](cpan_client.md) — CPAN client support
- `.agents/skills/port-cpan-module/` — Module porting skill

## References

- [Moose Manual](https://metacpan.org/pod/Moose::Manual)
- [Class::MOP](https://metacpan.org/pod/Class::MOP)
- [Moo](https://metacpan.org/pod/Moo)
- [B module](https://perldoc.perl.org/B)

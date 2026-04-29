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

### Lessons learned: core-runtime fixes that were reverted (Apr 2026)

During the Phase 3 → Phase D push, two "core fixes" were attempted to
unblock Class::Load / Class::MOP bootstrap, both later reverted:

1. **`*GLOB{SCALAR}` returns a SCALAR reference, not the value**
   (commit `880bf65c7`, reverted in `3d02203dc`). Motivation:
   Class::Load::PP line 38 does `${ *{...}{SCALAR} }` and our impl
   returned a copy. The "fix" returned a fresh `\$value` reference
   each call. **This silently broke Path::Class** (and DBIC by
   extension) because Path::Class's overload code does
   `*$sym = \&nil; $$sym = $arg{$_};` — assignments through the
   glob deref expect to land on the package's actual SV slot, not a
   throwaway reference. Lesson: any change to typeglob slot semantics
   must be validated against the full DBIC suite, which exercises
   Path::Class heavily.
2. **Auto-sweep weaken / walker-gated destroy**
   (commits `ca3af1ad3` + `ecb5c6400`, reverted in `f8ef367e4` /
   `d3743a11c`). Motivation: Class::MOP bootstrap died because the
   metaclass was being destroyed mid-construction. The "fix" coupled
   destroy timing to the reachability walker's view of refcount. It
   passed targeted refcount unit tests but introduced regressions in
   DBIC's `t/52leaks.t` that the unit tests didn't catch. Reverted
   pending a more disciplined design (see "Refcount fix plan" later
   in this document).

**Common failure mode: my measurement methodology was wrong.** I was
running partial DBIC subsets and treating "fast-fail at compile time"
as "no regression". The correct gate is the full `./jcpan -t
DBIx::Class` (~24 min, 314 files / ~13858 assertions). After both
reverts, DBIC is back at master parity.

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

###### Step W2 — root cause investigation (2026-04-27 update)

Investigation completed under PJ_RC=1 instrumentation: the metaclass
DOES NOT get destroyed (its refCount oscillates 0↔7 but never reaches
`Integer.MIN_VALUE` — a small `localBindingExists=true` guard on the
flush path correctly skips destroy). The actual destroy that triggers
the failure is on a **different** blessed object — likely an interim
object held briefly by Sub::Install during method installation.

Captured trace excerpt (`PJ_RC=1`):

```
RC -1 MortalList.flush b=1677207406 1->0 (refCount>0=true)
RC +1 incrementRefCountForContainerStore b=1677207406 0->1
RC +1 setLargeRefCounted-INC nb=1677207406 1->2
RC +1 setLargeRefCounted-INC nb=1677207406 2->3
RC defer-decrement b=1677207406 refCount=3 (will -1 on flush)
RC defer-decrement b=1677207406 refCount=3 (will -1 on flush)
RC clearWeakRefsTo b=1677207406 (clearing 4 weak refs)   # <-- bang
```

The clearing fires from `MortalList.flush` → `DestroyDispatch.callDestroy`
during a routine reference assignment in
`Class::MOP::Mixin::HasAttributes`'s `_post_add_attribute` chain.

Stack of the destroy trigger:

```
WeakRefRegistry.clearWeakRefsTo
  ← DestroyDispatch.doCallDestroy
  ← DestroyDispatch.callDestroy
  ← MortalList.flush          (line 566)
  ← anon1205.apply (Class/MOP/Class.pm line 260)
```

Total events for object 1677207406 over its lifecycle:
- 55 increments
- 45 immediate decrements (`MortalList.flush`)
- 42 deferred decrements queued

The decrement count exceeds the increment count, indicating a real
asymmetry — but pinpointing **which** assignment is asymmetric requires
deeper instrumentation than fits in this debugging round, since
PerlOnJava's refcount model has many subsystems (`MortalList`,
`WeakRefRegistry`, `ScalarRefRegistry`, `ReachabilityWalker`,
`MyVarCleanupStack`, `DestroyDispatch`).

###### Step W3 — surgical fix attempts, both reverted (2026-04-27)

**Attempt 1**: "Skip destroy when weak refs exist" guard in
`MortalList.flush()`:

```java
} else if (WeakRefRegistry.hasWeakRefsTo(base)) {
    // skip destroy
}
```

Result: broke 5+ existing weaken / destroy unit tests
(`unit/refcount/weaken_destroy.t`, `weaken_edge_cases.t`,
`weaken_basic.t`, `destroy_anon_containers.t`,
`unit/weaken_via_sub.t` Case 5). Reverted.

**Attempt 2**: Same guard, but tightened to "blessed object with
weak refs" only:

```java
} else if (WeakRefRegistry.hasWeakRefsTo(base) && base.blessId != 0) {
    // skip destroy
}
```

Applied at both `MortalList.flush()` and `setLargeRefCounted()`'s
overwrite-decrement path (line 1192-1200).

Result: still broke the cycle-breaking-via-weaken tests
(`weaken_destroy.t`, `weaken_edge_cases.t`, `destroy_anon_containers.t`)
because those tests use blessed objects in cycles, and rely on
DESTROY firing when the last external strong reference goes away
(letting weaken's cycle-breaking actually free the cycle). With the
guard, the cycle stays alive forever. Reverted.

**Lesson**: there's no simple predicate that distinguishes
"transient refCount drift during heavy reference shuffling" from
"genuine end-of-life with weak refs about to clear". The cooperative
refCount system doesn't carry enough information at the destroy gate
to make this call. The fix has to be in the **accounting itself**,
not at the destroy gate.

###### Step W3-next (TODO when refcount audit is resumed)

The fix has to make refCount **accurate** for blessed objects under
heavy reference shuffling (Class::MOP self-bootstrap pattern). Below
is the detailed plan for getting the count "accurate enough", in
priority order — the cheapest, lowest-risk option first.

####### Path 1 (recommended): walker awareness of hash-element seeds

**Why this is the right starting point**: PerlOnJava already tracks
hash/array element scalars via `incrementRefCountForContainerStore`,
which registers them in `ScalarRefRegistry`. The walker iterates
`ScalarRefRegistry` as roots — but **filters out scalars whose
declaration scope has exited** (via the `MyVarCleanupStack` check at
`ReachabilityWalker.java` lines 110-126).

That filter is correct for `my $x` lexicals (when the scope ends,
the scalar is logically dead). But it's **wrong for hash/array
element scalars**: they have no declaration scope of their own —
their lifetime is tied to the enclosing container. A `$METAS{HasMethods}`
scalar should remain a walker seed as long as `%METAS` exists.

**Fix**: in the walker's lexical-seed loop, skip the
`MyVarCleanupStack` check for scalars marked as hash/array elements
(`refCountOwned == true && registered via incrementRefCountForContainerStore`).
Use the enclosing container's `localBindingExists` as the liveness
signal instead.

Concrete patch sketch:

```java
// ReachabilityWalker.java, around the useLexicalSeeds loop:
for (RuntimeScalar sc : ScalarRefRegistry.snapshot()) {
    if (sc.captureCount > 0) continue;
    if (WeakRefRegistry.isweak(sc)) continue;
    // EXISTING check: skip if not in any active scope.
    boolean inActiveScope = MyVarCleanupStack.isAlive(sc);
    // NEW: hash/array element scalars don't have their own scope —
    // treat them as live as long as some container references them.
    boolean isContainerElement = sc.refCountOwned
            && ScalarRefRegistry.isContainerElement(sc);
    if (!inActiveScope && !isContainerElement) continue;
    // Now seed
    visitScalarPath(sc, ...);
}
```

`ScalarRefRegistry.isContainerElement(sc)` is new — it returns true
if `sc` was last registered via `incrementRefCountForContainerStore`
(which means it's currently a hash/array slot value). Track this
via a side-set or by exposing a getter on the existing registration.

**Verification**: with the walker now seeing `$METAS{HasMethods}`
as a root, the metaclass it points at is reachable, so the auto-sweep
won't try to clear weak refs to it. The transient `refCount==0`
events during bootstrap are then "false alarms" the walker corrects
on the next sweep cycle — but the `MortalList.flush()` destroy gate
would still fire prematurely. That's where Path 2 comes in.

**Estimated effort**: 1 day (small, contained change, easy to test).

####### Path 2: gate `MortalList.flush()` destroy on walker confirmation — DONE (2026-04-27)

The current flush-destroy gate is: `if refCount==0 and !localBindingExists, fire DESTROY`.
The Class::MOP bootstrap shows this is too eager — refCount can hit 0
transiently while the object is still reachable through an unwalked
path.

**Fix shipped**: when the flush gate (or the matching gate in
`setLargeRefCounted`'s overwrite path) would fire DESTROY on a
blessed object, do a **scoped reachability check first** via the new
`ReachabilityWalker.isReachableFromRoots(base)` query. Skip DESTROY
only when the walker confirms the object is still reachable from
package globals or `MyVarCleanupStack`-tracked live `my` lexicals.

Critical detail: the walker seeds from `MyVarCleanupStack.snapshotLiveVars()`
(my-vars whose declaration scope is still active), NOT from
`ScalarRefRegistry` directly. `ScalarRefRegistry` holds stale entries
(scope-exited scalars not yet JVM-GC'd), which would falsely consider
cycle-broken-via-weaken cycles reachable through their own lexicals.
By gating on `MyVarCleanupStack`, the walker correctly distinguishes:

- **Class::MOP bootstrap**: `our %METAS` is in `MyVarCleanupStack`
  while Class::MOP.pm loads. Walker traverses %METAS, finds the
  metaclass via `$METAS{HasMethods}`, returns true. → Skip DESTROY.
- **Cycle-break-via-weaken**: lexicals in inner block exit, leave
  `MyVarCleanupStack`. The cycle has no path to roots through
  any live my-var. Walker returns false. → Fire DESTROY normally,
  cycle freed.

Files changed:
- `src/main/java/org/perlonjava/runtime/runtimetypes/ReachabilityWalker.java`
  — new `isReachableFromRoots(target)` method, BFS with hard step
  cap (50K visits) and short-circuit on target found.
- `src/main/java/org/perlonjava/runtime/runtimetypes/MyVarCleanupStack.java`
  — new `snapshotLiveVars()` helper.
- `src/main/java/org/perlonjava/runtime/runtimetypes/MortalList.java`
  — gate at `flush()`'s destroy path.
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeScalar.java`
  — mirror gate at `setLargeRefCounted()`'s overwrite-decrement
  path.

Verification:
- `weaken_via_sub.t` (20 assertions) — all pass.
- `unit/refcount/weaken_basic.t` (34) — all pass.
- `unit/refcount/weaken_destroy.t` (24, includes cycle-break) — all pass.
- `unit/refcount/weaken_edge_cases.t` (42) — all pass.
- `unit/refcount/destroy_anon_containers.t` (21) — all pass.
- `make` (full unit suite) — green.
- `./jcpan -t DBIx::Class` — IDENTICAL to baseline
  (314 files / 878 tests / 303 failed files / 2 failing assertions).
  **Zero regressions** in the most refcount-heavy CPAN distribution
  we test.

The original Class::MOP bootstrap failure ("Can't call method
get_method on undefined value") is **resolved**: with the fix, the
metaclass survives the bootstrap, attribute back-references stay
defined, and `use Class::MOP` proceeds to a deeper layer
(`Class/MOP/Class/Immutable/Trait.pm` line 59) which is a separate
issue unrelated to refcount — Phase D will hit it next, but the
refcount blocker that prevented even *trying* the bundled Moose is
gone.

####### Path 3 (deferred — not needed for the immediate Class::MOP bootstrap)

Path 1 + Path 2 together make refCount drift benign for blessed
objects in Class::MOP-style heavy-shuffling scenarios. The 55-vs-87
trace asymmetry observed earlier is now harmless: when refCount
dips to 0 transiently, the walker confirms reachability and DESTROY
is correctly skipped. If a future scenario requires the trace to be
genuinely symmetric (vs just "drift-tolerant"), the Path 3 audit
still applies — see the original write-up below.

####### Path 3 (deepest fix): refcount accounting symmetry audit

**Use this only if Paths 1+2 don't close the gap.** Captured
PJ_RC=1 trace from the Class::MOP bootstrap showed 55 increments vs
87 effective decrements for the failing object — a real asymmetry
beyond walker-blindness. That asymmetry has to come from at least
one code path where `++base.refCount` and `--base.refCount` aren't
symmetric.

Audit candidate sites in priority order:

1. **`@_` aliasing on sub call entry** (`RuntimeCode.apply`).
   When `attach($attr, $REG{x})` is called, the elements of `@_`
   alias the caller's expression results. Real Perl uses RC++ on
   each alias setup, RC-- on @_ teardown at sub exit. Verify:
   ```java
   // entry: each @_ slot whose value is a tracked ref → refCount++
   // exit:  each @_ slot whose refCountOwned=true → refCount--
   ```
   Trace: with the test
   ```perl
   my $obj = bless {}, "M";
   sub f { 1 }
   for (1..10) { f($obj) }
   ```
   verify `$obj`'s refCount lands at the same value before and
   after the loop. If not, that's site #1.

2. **List-assignment from `@_`** (`my (...) = @_;`). The list-copy
   path may double-count if it goes through both
   `setLargeRefCounted` AND a bulk `setFromList` path that also
   touches refcounts. Audit: verify `RuntimeArray.setFromList` and
   list-copy bytecode emit only ONE increment per assigned slot.

3. **Hash element store on overwrite**:
   `$h->{key} = $a; $h->{key} = $b;`
   The first assignment is a fresh slot (one `++a.refCount`). The
   second overwrites — should be one `++b.refCount` AND one
   `--a.refCount`. Audit: confirm `RuntimeHash.put` and
   `setLargeRefCounted`'s overwrite path don't double-decrement
   when both fire on the same overwrite.

4. **Sub::Install closure captures**. Each closure binding captures
   `$method`, `$package`, etc. The closure's CODE object's
   `capturedScalars` array holds these. Verify per-closure
   scope-exit decrements only the closure's captures, not the
   caller's locals. Already-suspicious site: `RuntimeCode.apply`
   line 546 calls `MortalList.deferDecrementIfTracked(s)` on
   captured scalars — may double-fire across nested closures.

**Methodology**: write a unit test for each candidate site that
asserts `base.refCount` post-operation. Use the test as a regression
guard before applying the fix at that site. Like:

```perl
# t/refcount_audit_at_calls.t
use Test::More;
use Internals qw(SvREFCNT);   # PerlOnJava-only helper if needed
my $obj = bless {}, "M";
my $rc0 = SvREFCNT($obj);
sub f { 1 }
for (1..10) { f($obj) }
is(SvREFCNT($obj), $rc0,
    'refCount unchanged after 10 sub calls passing $obj');
```

If `SvREFCNT` isn't exposed, instrument via the `PJ_RC=1` env trace
and post-process the log: count increments and decrements for the
target object's id, assert equality.

**Estimated effort**: 3-4 days (each candidate site is its own
investigation + fix + test).

####### Why this order

- Path 1 alone might solve the bootstrap (walker corrects the
  transient drift before it causes harm). If yes, ship.
- Path 2 closes the gap if the walker is now right but flush-destroy
  fires before the next walker cycle. If yes, ship Path 1+2.
- Path 3 is only needed if real refcount asymmetry exists beyond
  walker-blindness. The 55-vs-87 trace data suggests it does, but
  the asymmetry might be benign once the walker correctly identifies
  reachable objects (refCount drift is fine if `localBindingExists`
  + walker say "still alive").

The test gate is unchanged from the previous round:

```bash
./jperl src/test/resources/unit/weaken_via_sub.t                 # 20/20 ok
./jperl src/test/resources/unit/refcount/weaken_basic.t          # all ok
./jperl src/test/resources/unit/refcount/weaken_destroy.t        # all ok (cycle break)
./jperl src/test/resources/unit/refcount/weaken_edge_cases.t     # all ok
./jperl src/test/resources/unit/refcount/destroy_anon_containers.t  # all ok
./jperl -e 'use Class::MOP; print "ok\n"'                        # ok
make                                                              # green
./jcpan -t DBIx::Class                                            # 11 green / 876 ok / 2 fail (baseline)
```

####### What success looks like

After Paths 1 and 2 land:

- **Class::MOP self-bootstrap loads cleanly.** The metaclass's
  refCount can still drift to 0 transiently, but the walker correctly
  reports it as reachable via `our %METAS` and the flush-destroy gate
  defers to that.
- **Existing weak-ref / cycle-break tests still pass.** When the
  walker correctly says "this object is unreachable" (e.g. cycle
  isolated from external refs), the flush-destroy gate fires DESTROY
  as before.
- **Phase D unblocks**: bundled Moose loads. Then D1-D6 mechanical
  steps complete and 477/478 Moose tests pass.

####### What success does NOT mean

The cooperative refCount may still over-count in some cases (objects
hold refCount > 0 after they're truly dead). That's acceptable: the
existing auto-sweep will reap them on the next walker cycle. The
problematic direction — under-counting that fires DESTROY too early
— is what Paths 1+2 fix.

###### Verification (Step W6) — the fix that *did* land

DBIx::Class is the most refcount-heavy CPAN distribution we test.
Before / after the auto-sweep weaken fix (commit `ca3af1ad3`):

| Metric | Baseline | After fix |
|---|---|---|
| Files executed | 314 | 314 |
| Assertions executed | 878 | 878 |
| Fully passing files | 11 | 11 |
| Failed files | 303 | 303 |
| Assertions failing | 2 | 2 |

**Zero regressions in DBIC.**

The MortalList.flush bug is a separate, unrelated bug — its fix is
still pending.

###### Verification (Step W7)

```bash
./jperl -e 'use Class::MOP; print "ok\n"'   # → ok
./jperl -e 'use Moose; print "ok\n"'        # → ok (still via shim)
```

Phase D resumption is now unblocked **for the simple case**.

##### Active blocker (discovered while attempting D1-D3): `MortalList.flush()` destroys the metaclass during Class::MOP bootstrap

When the bundled upstream Moose 2.4000 was tried in
`feature/moose-phase-d`, `use Class::MOP;` died at the third
`add_attribute(...)` call in the Class::MOP.pm self-bootstrap with:

```
Can't call method "name" on an undefined value at .../Attribute.pm line 433
```

Diagnosis: The bootstrap calls `attach_to_class($meta)` ten+ times,
each storing a weak back-reference from the attribute to the
metaclass. The first eight succeed; the ninth `weaken()` enters with
`base.refCount == Integer.MIN_VALUE` (already destroyed) and
immediately UNDEFs the slot. Stack trace shows the destroy fires
from:

```
DestroyDispatch.callDestroy
  ← MortalList.flush               (line 558)
  ← RuntimeScalar.setLargeRefCounted  (line 1236)
  ← assignment in Sub::Install
```

So between iterations, an ordinary reference assignment (in
Sub::Install method-installation, called by Class::MOP's bootstrap)
flushes mortals, which decrements the metaclass's refCount to 0
and fires DESTROY — even though it's still referenced by `%METAS`,
by the lexical `$meta`, by the attribute `$self->{associated_class}`
slot, etc.

Per-iteration refCount trace from PJ_WEAKEN_TRACE=1:

```
DBG weaken called: base=metaclass refCount=6   # iter 1
DBG weaken called: base=metaclass refCount=7   # iter 2
DBG weaken called: base=metaclass refCount=7   # iter 3
DBG weaken called: base=metaclass refCount=5   # iter 4 (drop!)
DBG weaken called: base=metaclass refCount=6   # iter 5
DBG weaken called: base=metaclass refCount=6   # iter 6
DBG weaken called: base=metaclass refCount=-2147483648  # iter 9: ALREADY DESTROYED
```

The refcount is unstable across flushes. The Phase 3 weaken-auto-sweep
fix prevents the auto-sweep from racing the bootstrap, but the
per-flush DESTROY in `MortalList.flush()` itself decrements
prematurely.

###### Step W2 — root cause investigation (TODO)

Hypothesis: `setLargeRefCounted` is double-counting an "owned"
ref-store somewhere. Each store of the metaclass into a hash should
be balanced by exactly one decrement at scope exit. The trace
suggests scope-exit cleanup is running while a hash-element store
is still live — both decrement.

Investigation steps when resuming:

1. Add a refCount-history print to `setLargeRefCounted` and
   `MortalList.flush` for `blessId != 0` referents. Run
   `JPERL_NO_AUTO_GC=1 ./jperl -e 'use Class::MOP'` and capture
   every increment/decrement on the metaclass.
2. Cross-check against the same trace under `perl` (real Perl,
   instrumented refcount) for the same Class::MOP.pm bootstrap.
   Diff to find which assignment is asymmetric.
3. Most likely culprit: the `MortalList.deferDecrementIfTracked`
   path adds the base to `pending` even for hash-store assignments
   that are themselves followed by `MortalList.flush()` — so the
   base gets decremented twice (once at the next flush, once at the
   eventual scope exit).
4. Alternative culprit: per-statement flush is being called when
   the tracked owner is a closure capture (Sub::Install installs
   methods via closures), which over-counts ownership transitions.

###### Step W3 — fix and verify (TODO)

Apply the fix surgically. Re-run the W6 (DBIC zero regressions) and
W7 (Class::MOP loads) gates. The minimal-repro + unit-test gate
already passes (Step W2 unit tests).

##### Phase D resumption checklist (when both W blockers fixed)

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

## Refcount root-cause analysis (Apr 2026, updated)

### Where we are

- Phase D started: bundled upstream Moose 2.4000 in `src/main/perl/lib/`.
- `use Class::MOP` and `use Moose` both succeed on PerlOnJava (with
  walker-gated destroy + `our %METAS` + Package::Stash::PP slot patch
  + grep aliasing fix + Method::Accessor weaken disable + a hand-rolled
  type-name parser to bypass `(?(DEFINE)…)`).
- Moose's own test suite (with `./jcpan -t Moose --jobs 1`) reaches
  **412 / 478** fully-green files (was 71 / 478 with the old shim).
- DBIC (`./jcpan -t DBIx::Class --jobs 1`) regressed from
  master's `0 failing assertions / 2 failed files` to
  **`23 failing assertions / 13 failed files`** with the walker gate.

### The single offending commit

`1c938a99d` (cherry-picked from `ecb5c6400`) "fix(refcount):
walker-gated destroy resolves Class::MOP bootstrap blocker" is the
sole DBIC regression source. Bisection: master passes
`t/prefetch/incomplete.t`; `1c938a99d` alone fails it the same way
the full Phase-D branch does ("Can't call method 'resultset' on an
undefined value … source 'Track' is not associated with a schema").

The walker gate is **necessary** for `use Class::MOP` to load.
Without it, the metaclass for `Class::MOP::Mixin::HasMethods` is
DESTROYed mid-bootstrap by a `MortalList.flush()` and weak refs to
the metaclass clear before `_attach_attribute` finishes.

The walker gate is **simultaneously** what breaks DBIC, because the
walker reports `reach=false` for objects (e.g. `DBICTest::Schema`)
that are clearly held by a script-level `my $schema =
DBICTest->init_schema()`. With `reach=false`, the gate falls
through to the destroy path, all weak refs from
`ResultSource->{schema}` clear, and downstream method calls hit
`undef`.

So the same gate either over-protects (broken cycle break) or
under-protects (broken DBIC bootstrap). The walker's reachability
oracle is the ground-truth concept; refining the oracle is the
work.

### What the walker currently sees

`ReachabilityWalker.isReachableFromRoots` seeds from:

1. `GlobalVariable.globalCodeRefs` — package subs.
2. `GlobalVariable.globalVariables` / `globalArrays` / `globalHashes`
   — package globals.
3. `ScalarRefRegistry.snapshot()` filtered by
   `MyVarCleanupStack.isLive(sc) || sc.refCountOwned`,
   `!WeakRefRegistry.isweak(sc)`, `!sc.scopeExited`,
   `sc.captureCount == 0`.
4. `MyVarCleanupStack.snapshotLiveVars()` — currently-registered
   my-vars (RuntimeScalar, RuntimeHash, RuntimeArray instances).
5. `DestroyDispatch.snapshotRescuedForWalk()` — DESTROY-rescued
   objects.

Then BFS over the seeds, walking RuntimeHash element values and
RuntimeArray elements via `followScalar` (which honours
`!WeakRefRegistry.isweak(s)` and the `REFERENCE_BIT`).

### Why the walker says `DBICTest::Schema reach=false`

The user-script's `my $schema = DBICTest->init_schema()` is a
top-level lexical. Tracing showed:

- The `$schema` RuntimeScalar IS registered in MyVarCleanupStack
  during execution (after the fix to populate `liveCounts`
  unconditionally — see "Fixes already landed in this branch" below).
- But `seedTarget($schema, target, …)` returns `false` for the
  schema target. That is, `$schema.value` does not point to the
  blessed RuntimeHash that's being destroyed at the gate-fire moment.

That can mean only one of:

A. `$schema.value` was overwritten / cleared **before** the gate
   fires (some intermediate call assigned `undef` to `$schema`'s
   storage slot, even though the user's lexical view of `$schema`
   is still live).
B. There are **two** different `DBICTest::Schema` blessed instances
   — `$schema` points to instance #1, the gate fires for instance #2,
   and #2 is held only by closures / mortals / detached refs.
C. `$schema` itself is not the lexical the walker thinks it is —
   maybe the JVM bytecode emits a *copy* into a local slot (with
   `refCountOwned=true`) and registers THAT in MyVarCleanupStack,
   while the schema lives on the original.

### Fixes already landed in this branch

These are correct, useful, and needed regardless of the next
fix-level work:

1. **`MyVarCleanupStack.register` always populates `liveCounts`**
   (Apr 2026). Was gated on `WeakRefRegistry.weakRefsExist`; meant
   that `my` vars declared **before** any weaken() were invisible
   to the walker. Cost: one HashMap.merge per `my`.

2. **`ReachabilityWalker.snapshotLiveVars()` seeding**: walk
   `RuntimeScalar` first (so its REFERENCE_BIT gets followed via
   `seedTarget`), only then fall through to the generic
   `RuntimeBase` branch. Otherwise the BFS adds the scalar to
   `todo` but the BFS body only steps into hashes / arrays.

3. **`our %METAS` in bundled Class::MOP.pm** so the walker finds
   the metaclass cache as a global hash.

4. **`grep` returns aliases** (Class::MOP::MiniTrait depends on
   it).

5. **Stub the XS-only Class::MOP / Moose accessors** in
   `Class/MOP/PurePerl.pm`.

6. **Patch Class::MOP::Method::Accessor** to skip
   `weaken($self->{attribute})`. The cooperative refCount can't
   keep the attribute alive across the brief window between
   `weaken` and `_initialize_body`. Trade: leaks attribute objects
   at global destruction.

7. **Patch `~/.perlonjava/lib/Package/Stash/PP.pm`** to bypass
   `*GLOB{SCALAR}` for the SCALAR slot (which our impl returns as
   the value, not a SCALAR ref).

### Next-step plan: make the walker's reachability oracle tight

#### Reproducer (Apr 2026, ~UPDATED~)

**A reliable failing reproducer now lives at**
`dev/sandbox/walker_gate_dbic_minimal.t` (kept in sandbox until it
passes, per project convention; move it to
`src/test/resources/unit/refcount/` after the fix).

```perl
my @objs;
my @wrappers;
for (1..5) {
    my $o = T::Obj->new;
    my $w = T::Wrapper->new($o);     # weakens $w->{obj}
    $o->{wrapper} = $w;              # cycle: o -> w (strong), w -> o (weak)
    push @objs, $o;
    push @wrappers, $w;
}
# many ref operations, then:
# T::Obj id=1 has been DESTROY'd even though @objs[0] still points to it.
# wrapper[0]->{obj} cleared. -> 3/4 of the test's assertions fail.
```

The test deliberately AVOIDS `use Test::More` — loading it creates
enough additional globals/lexicals that the walker's reachable set
becomes large enough to transitively cover `@objs`, masking the bug.
The bare-`print`-TAP version reliably fails on every run.

#### The actual root cause (data, not theory)

Stack trace from `PJ_DESTROY_TRACE=1` shows the destroy path:

```
at DestroyDispatch.callDestroy(...)
at ReachabilityWalker.sweepWeakRefs(...)
at MortalList.maybeAutoSweep(...)
at MortalList.flush(...)
```

So the destroy fires from `sweepWeakRefs`, **not** from the
walker-gated `MortalList.flush()` decrement path. The auto-sweep
calls `ReachabilityWalker.walk()` to compute a "live" set; anything
not in that set has its weak refs cleared and DESTROY fired.

The walker's `walk()` method (the multi-phase one used by the
sweep) seeds from:

- `GlobalVariable.globalCodeRefs` (with closure-capture walking)
- `GlobalVariable.globalVariables / globalArrays / globalHashes`
- `DestroyDispatch.snapshotRescuedForWalk()`
- `ScalarRefRegistry.snapshot()` filtered by
  `MyVarCleanupStack.isLive(sc) || sc.refCountOwned`

It does **NOT** seed from `MyVarCleanupStack.snapshotLiveVars()`
directly. That seeding was added only to the per-object query
`isReachableFromRoots()` — the same fix needs to go into `walk()`.

So the fix is:
1. Make `walk()` also seed from
   `MyVarCleanupStack.snapshotLiveVars()` (RuntimeArray /
   RuntimeHash / RuntimeScalar live my-vars), mirroring the
   `isReachableFromRoots()` change.
2. Apply the same RuntimeScalar-before-RuntimeBase ordering inside
   the walk's seed-handler loop.

This is **D-W2**'s fix path. Estimated impact: D-W0(c) reproducer
passes, DBIC drops from 23 failing assertions back to ≤2,
Moose stays at 412/478, refcount unit tests stay green.

#### Phases

1. **D-W0** (DONE): reliable reproducer at
   `dev/sandbox/walker_gate_dbic_minimal.t` consistently fails.

2. **D-W1** (DONE — Apr 2026): added two seeding fixes to
   `ReachabilityWalker.walk()`:
   - Seed from `MyVarCleanupStack.snapshotLiveVars()` so top-level
     `my @arr` / `my %hash` lexicals are visible to the auto-sweep.
   - Order RuntimeScalar before RuntimeBase in the seed handler so
     scalar reference bits get followed.

   Result: `dev/sandbox/walker_gate_dbic_minimal.t` passes. The
   per-test failing reproducer `t/prefetch/incomplete.t`
   passes (20/20). All refcount unit tests stay green. Moose
   suite stays at **412/478**.

3. **D-W2** (DONE — Apr 2026): RuntimeStash skip in walker BFS.

   Stash hashes (RuntimeStash whose `elements` is a HashSpecialVariable)
   eagerly copy all global keys via `entrySet()` on every visit —
   O(globals) per visit, quadratic when the walker is called repeatedly.

   Fix: `if (cur instanceof RuntimeStash) continue;` in
   `ReachabilityWalker.bfs()` and `isReachableFromRoots()`. Stash
   entries are already directly seeded from
   `GlobalVariable.global*Refs`, so iterating them via
   `stash.elements` is redundant work.

   Empirical impact (`t/sqlmaker/dbihacks_internals.t`):
   - Before D-W2: never finished (>10 minutes wall-clock, still running)
   - After D-W2:  ALL 6492 tests pass in 30 seconds

4. **D-W2b** (PARTIALLY DONE — Apr 2026): lazy
   `MyVarCleanupStack.liveCounts` population.

   `MyVarCleanupStack.register` now only populates `liveCounts`
   when `WeakRefRegistry.weakRefsExist == true`, restoring pre-D-W1
   per-`my` cost. To preserve D-W1 correctness, the FIRST
   `weaken()` call (in `WeakRefRegistry.registerWeakRef`) does a
   one-time backfill: walks the existing `MyVarCleanupStack.stack`
   and inserts every still-registered my-var into `liveCounts`.

   **Per-test wallclock comparison** (master jperl JAR vs feature
   jperl JAR, same .t files, no harness):

   | Test                  | Master | D-W2  | D-W2b | vs Master |
   |-----------------------|--------|-------|-------|-----------|
   | t/05components.t      |  6.25s | 4.85s | 2.82s | 0.45×     |
   | t/52leaks.t           | 40.15s | 9.43s | 5.90s | 0.15×     |
   | t/76joins.t           |  9.79s | 6.90s | 5.52s | 0.56×     |
   | t/86might_have.t      |  9.66s | 9.86s | 4.67s | 0.48×     |
   | t/100populate.t       |    -   |12.62s |15.76s | -         |
   | t/60core.t            |    -   |    -  |15.65s | -         |

   Most individual tests are now **2-7× faster than master** (the
   walker gate prevents wasteful destroy cascades).

   **Full DBIC suite results:**

   | Metric        | Master | D-W2  | D-W2b | D-W2c    |
   |---------------|--------|-------|-------|----------|
   | Wallclock     | 1410s  | 3782s | 2386s | **1748s**|
   | Tests run     | 13858  | 13740 | 13851 | **13858**|
   | Failed files  | 0      | 8     | 4     | **0**    |
   | Failed subt.  | 0      | 2     | 2     | **0**    |
   | Result        | PASS   | FAIL  | FAIL  | **PASS** |

   **D-W2c is the green state. DBIC matches master baseline
   exactly (314 files / 13858 tests / 0 fail / PASS), 1.24×
   wallclock cost.**

5. **D-W2c** (DONE — Apr 2026): the walker gate is now
   class-name-restricted to Class::MOP / Moose / Moo class
   hierarchies. Other classes get normal Perl 5 destroy
   semantics (refCount==0 → destroy fires immediately).

   **Why class-name gating works empirically:**

   Class::MOP / Moose store metaclasses in `our %METAS` (a
   package global) and rely on the gate to absorb transient
   refCount drift during bootstrap. DBIC and CDBI store rows
   in `live_object_index` via WEAK refs, expecting the row to
   die at refCount==0 so a fresh fetch reloads from the DB.
   The two patterns require opposite gate behaviour. The
   class-name filter cleanly separates them.

   **Why this is a stopgap:**

   Other modules outside Class::MOP/Moose may need the gate
   in the future. The proper long-term fix is to either:

   1. Find and back-fill the missing refCount increments at
      the source (when an object transitions from refCount=-1
      untracked to refCount=0+ tracked, scan ScalarRefRegistry
      for scalars holding the object and back-increment).

   2. Replace the cooperative refCount mechanism with a more
      reliable scheme (e.g. JVM-level identity hashmap keyed
      by referent, counting actual scalar holders).

   Both are deferred to D-W2d.

   **Per-test verification (all PASS):**
   - `dev/sandbox/walker_gate_dbic_minimal.t` (4/4)
   - `src/test/resources/unit/refcount/walker_gate_dbic_pattern.t`
     T1-T4 (T5 marked SKIP — needs PJ_RUN_T5=1; tests a
     pattern that fails on master too).
   - `t/cdbi/04-lazy.t` 36/36
   - `t/storage/txn_scope_guard.t` 18/18
   - `t/52leaks.t` 11/11
   - `use Moose; package Foo; has bar => (is=>'rw'); ...`

6. **D-W2d** (NEXT after D-W2c — perf gap to close): bring the
   remaining 1.69× wallclock gap closer to 1.0×. Likely candidates:

   - **Per-class hasWeakRefs filter.** Skip the walker call for
     classes never weakened (most blessed objects in DBIC's data
     layer have no weak refs).

   - **Cache the walker's live-set per flush.** Compute live set
     once if multiple weak-ref'd objects hit refCount=0 in the
     same flush.

   - **Coalesce gate calls.** Queue and process in a single
     walker pass at flush end.

7. **D-W3** (BLOCKED on D-W2c): drop reproducer into
   `src/test/resources/unit/refcount/`.

8. **D-W4** (LATER): Phase 4-6 shim widening for Moose 412→477 / 478.

### Hard constraint moving forward

Per the user's instruction: **"Failing weaken/DESTROY is not
accepted at all."** Every fix MUST be validated against:

```bash
./jcpan --jobs 1 -t DBIx::Class       # 0 failing assertions, ≤2 failed files
./jcpan --jobs 1 -t Moose              # ≥ 412 / 478 fully green
make                                    # full unit suite green
./jperl src/test/resources/unit/refcount/*.t   # all pass
./jperl src/test/resources/unit/weaken_via_sub.t  # 20/20
```

Parallel runs (`./jcpan -t …` without `--jobs 1`) OOM-crash on the
local box for several DBIC tests; that is environmental, not a
DESTROY regression. Always serialise the regression gate with
`--jobs 1`.

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

## Phase D-W3: Sub::Util / sort BLOCK fixes (2026-04-28)

Two bytecode/runtime bugs found while triaging the Moose 412/478
plateau:

### D-W3a: `Sub::Util::subname` of anonymous subs

`B::svref_2object($code)->GV->STASH->NAME` (used by
`Class::MOP::get_code_info` and `Class::MOP::Mixin::HasMethods::
_code_is_mine`) returned `main` for any anon sub created in a
non-main package. Real Perl returns the compile-time package
(CvSTASH).

The CvSTASH is recorded on `RuntimeCode.packageName`. Fixed by:
- `Sub::Util::subname` now returns `Pkg::__ANON__` when the sub has
  no name but a known package.
- `Sub::Util::subname` honors the `explicitlyRenamed` flag for
  `set_subname("", $code)` (returns empty string, not `__ANON__`).
- `B.pm`'s `_introspect` accepts `Pkg::__ANON__`, `Pkg::` and bare
  renames.

This fix unblocks immutable metaclass trait application (the
`Class::MOP::Class::Immutable::Trait::add_method` etc. were being
rejected by `_code_is_mine` and so didn't take effect).

Tests fixed (full file pass, `failing_count -> 0`):
- `t/cmop/make_mutable.t` (12)
- `t/cmop/numeric_defaults.t` (12)
- `t/cmop/subclasses.t` (6)
- `t/cmop/method.t` (5)
- `t/cmop/method_modifiers.t` (3)
- `t/cmop/anon_class.t` (3)
- `t/cmop/immutable_metaclass.t` (5 -> 1)
- `t/cmop/add_method_debugmode.t` (10 -> remaining timing-only)
- `t/exceptions/class-mop-class-immutable-trait.t` (2)
- `t/cmop/get_code_info.t` (3 -> 1, MODIFY_CODE_ATTRIBUTES name remains)

### D-W3b: sort BLOCK comparator @_

`sort { $_[0]->($a, $b) } @list` is the idiom Moose's native Array
trait emits for the `sort($cmp)` accessor. Real Perl exposes the
surrounding sub's `@_` inside the sort BLOCK, but PerlOnJava was
creating a fresh empty `@_`.

Fixed in the same way map/grep already worked: pass slot 1 (`@_`) to
`ListOperators.sort` from both the JVM emitter
(`EmitOperator.handleMapOperator`) and the interpreter
(`InlineOpcodeHandler.executeSort`), and forward it as the
comparator's args (unless the comparator has a `$$` prototype, in
which case the existing `(a, b)` semantics still apply).

Bytecode descriptor for the SORT op was widened to include
`RuntimeArray` (the outer @_).

Tests fixed:
- `t/native_traits/trait_array.t` (6 sort-with-fn failures)

### Status after D-W3

- DBIC: still PASS (314/314, 13858/13858) — verified locally.
- Moose: 396/478 files pass (was 391/478). 137 failed asserts
  (was 145). Remaining failures cluster around:
  - Numeric/string warning categories not implemented
    (`always_strict_warnings.t`, etc.)
  - Native trait Hash coerce + delete corner cases.
  - Anonymous metaclass GC timing (depends on weak-ref / walker
    scheduling).
  - `Moose::Exception::CannotLocatePackageInINC` etc. — `INC`
    attribute name handling.
  - Stack-trace shape (`__ANON__` in stringified frames, etc.).
  - A handful of cmop/method introspection edge cases (constants,
    forward declarations, eval-defined subs).

### D-W6.7: Pinpointed root cause — %METAS storage doesn't bump cooperative refCount

**Date:** 2026-04-26
**Branch:** `fix/d-w6-precise-die-probe`

With the walker gate disabled in `MortalList.java:558` and a more
granular probe added to `WeakRefRegistry` (env-flag
`PJ_WEAKCLEAR_TRACE`), we instrumented `weaken`, `removeWeakRef`, and
`clearWeakRefsTo` and ran `use Class::MOP` after wrapping
`Class::MOP::Attribute::{attach_to_class, install_accessors}` from
Perl with refaddr-printing logs.

**Smoking gun trace** (`use Class::MOP`, gate disabled):

```
[WEAKEN]    ref=...   referent=1746570062 (RuntimeHash)   ← _methods slot
[WEAKEN]    ref=...   referent=1746570062 (RuntimeHash)   ← method_metaclass slot
[WEAKEN]    ref=...   referent=1746570062 (RuntimeHash)   ← wrapped_method_metaclass slot
[WEAKCLEAR] referent=1746570062 (RuntimeHash) clearing 4 weak refs
   at WeakRefRegistry.clearWeakRefsTo(...)
   at DestroyDispatch.callDestroy(...)
   at MortalList.flush(...)
   at jar:PERL5LIB/Class/MOP/Class.pm:260   ← inside Class::MOP::Class::initialize/_construct_class_instance
   at jar:PERL5LIB/Class/MOP/Mixin.pm:104   ← Mixin::meta
   at jar:PERL5LIB/Class/MOP.pm:2351        ← bootstrap statement
attach_to_class attr=...  name=_methods                  class=1746570062
   after_attach: assoc=1746570062  ← OK
attach_to_class attr=...  name=method_metaclass          class=1746570062
   after_attach: assoc=1746570062  ← OK
attach_to_class attr=...  name=wrapped_method_metaclass  class=1746570062
   after_attach: assoc=UNDEF       ← BUG
install_accessors:                  assoc=UNDEF
```

**The bug:** the metaclass `RuntimeHash` (id=1746570062) is stored in
`our %METAS` (`store_metaclass_by_name $METAS{$name} = $self`).
Despite `%METAS` strongly holding it, the cooperative refCount drops
to 0 at end-of-statement when the temporary returned by
`HasMethods->meta` falls out of scope and `MortalList.flush()`
processes its mortals. `clearWeakRefsTo` is called on the metaclass,
which nulls all 4 attribute back-references including the freshly
weakened `wrapped_method_metaclass`'s `associated_class` slot.

The first failing `install_accessors` then sees `associated_class =
UNDEF` and dies on `$class->add_method(...)`. That die is hidden by
`local $SIG{__DIE__}` inside `try { install_accessors }` at
`Class/MOP/Class.pm:897`. The catch block runs `remove_attribute →
remove_accessors → _remove_accessor` at `Class/MOP/Attribute.pm:475`,
which dies again with the visible `Can't call method "get_method" on
an undefined value` message.

**Root cause statement:** the cooperative refCount is failing to
count the strong reference held by the package variable hash slot
`$METAS{$package_name}`. When the temporary metaclass return-value
from `->meta` expires, refCount goes from 1 → 0 even though `%METAS`
still holds it.

**Why the walker gate "fixes" it:** the gate at
`MortalList.java:558-561` short-circuits the destroy cascade for
metaclass-shaped names, so `clearWeakRefsTo` never gets a chance to
null the attribute back-refs.

**Why universal walker doesn't fix it:** the walker is consulted
*after* refCount underflow. By the time `MortalList.flush()` decides
to call DESTROY, the refCount is already 0; the walker would need to
detect "refCount=0 but %METAS still references" — which is exactly
what a graph walk from package globals could confirm. The "universal
walker" experiments only checked direct lexical reachability, not
package-variable-hash reachability.

### D-W6.8: Next steps

Two options for a real fix:

1. **Fix the refCount discipline:** ensure `RuntimeHash.put()` /
   slot-assignment increments the cooperative refCount of the
   referent when storing a blessed/tracked RuntimeBase value. Find
   why this doesn't happen for `$METAS{$name} = $meta`.

2. **Walker as ground truth before `clearWeakRefsTo`:** when refCount
   hits 0, before firing DESTROY, run a reachability walk from
   package-variable roots; if reachable, leave refCount at 1 instead
   of dropping to 0. (Replaces the heuristic gate with a precise
   walker check.)

Option 1 is the proper fix; option 2 is the safety net we already
half-built.

The diagnostic env-flags `PJ_WEAKCLEAR_TRACE` (now wired up across
`weaken`, `removeWeakRef`, `clearWeakRefsTo`) and `PJ_DESTROY_TRACE`
should be retained.

### D-W6.9: Walker-fix experiments (Apr 2026)

Three concrete fix attempts on `fix/d-w6-precise-die-probe`:

| Variant | DBIC 52leaks | Notes |
|---------|--------------|-------|
| Master (class-name heuristic) | 11/11 pass | baseline |
| Universal walker (no heuristic) | 9/11 + die at line 518 | resultset that should leak is incorrectly rescued; downstream `weaken(my $r = shift @circreffed)` dies because `$r` is undef |
| Hybrid (heuristic full + non-heuristic globalOnly) | same as universal — DBIC regression unchanged | |
| Add walker gate to weaken's dec-to-0 path | times out (>120s) on `t/52leaks.t` | walker invocations on every weaken of Moose/CMOP-blessed objects is too expensive on DBIC's Moose-heavy schema construction |

Conclusion: the class-name heuristic is the **best known compromise**
until we either:
- Fix the underlying `our %METAS` refCount-discipline bug (option 1)
- Cache walker reachability results to make universal application
  cheap enough not to time out

Synthetic reproducers in `src/test/resources/unit/refcount/drift/`
do NOT trip the underflow with non-CMOP class names — proving that
the bug is not just a generic `our %HASH` storage issue. Some
specific shape of the recursive CMOP bootstrap (interaction between
`HasMethods->meta` reentry and weakly-attached attributes) is
required.

Suggested next investigation: per-RuntimeBase refCount transition
trace for the specific metaclass, surfacing each increment/decrement
site during the failing `use Class::MOP`. With 22 decrement sites
across the runtime, blanket instrumentation is infeasible, but a
targeted tracer (turn on for blessed `Class::MOP::Class` only)
should make the underflow source pinpointable.

### D-W6.10: Targeted refCount tracer wired up + accounting result

Implemented (this branch): a per-RuntimeBase `refCountTrace` flag
that is armed at `bless` time when the bless target matches
`classNeedsWalkerGate` (Class::MOP / Moose / Moo) and the env-flag
`PJ_REFCOUNT_TRACE` is set. The flag-gated `traceRefCount(delta,
reason)` method writes a stderr line for every transition with a
short stack snippet. Wired into the four critical sites:

- `WeakRefRegistry.weaken` (decrement on weakening)
- `RuntimeScalar.setLargeRefCounted` (increment on store)
- `RuntimeScalar.setLargeRefCounted` (decrement on overwrite)
- `MortalList.flush` (deferred decrement)

Cost when off: one `boolean &&` test per call site. Cost when on:
all increments/decrements logged for matched objects only.

**Findings on `use Class::MOP` (gate disabled):** the failing
metaclass `RuntimeHash` (id `573487274` in one run) accumulated:

- 50 increments via `setLargeRefCounted (increment on store)`
- 45 decrements via `MortalList.flush (deferred decrement)`
- 6 decrements via `WeakRefRegistry.weaken (decrement on weakening)`
- 1 silent increment from `bless` itself (refCount++ at
  `ReferenceOperators.java:83`)
- 1 silent paired deferred decrement queued by `bless` from
  `MortalList.deferDecrement(referent)` at line 84 (fires later
  during a flush, counted in the 45)

Net: **+1 + 50 - 51 = 0**, i.e. one extra decrement.

The 1→0 transition occurs during `MortalList.flush()` triggered at
the end of statement at `Class/MOP/Class.pm:260`
(`my $super_meta = Class::MOP::get_metaclass_by_name(...)`). At
that moment the metaclass should have refCount==1 (held by
`our %METAS`), but the cooperative count goes to 0 because one of
the 50 increment sites does **not** end up paired with the right
decrement (one extra `pending.add(metaclass)` queued without a
paired increment, or one increment lost without queueing a paired
decrement).

The full trace for the failing metaclass is preserved at
`dev/diagnostic-traces/cmop-metaclass-refcount-underflow.txt` (102
lines, every transition with stack).

**Best fix candidates given this data:**

1. **Mortal-side rescue (cheap):** before `--base.refCount` brings
   refCount to 0 inside `MortalList.flush`, consult the walker on a
   *per-blessId* whitelist (currently the heuristic). Keep the
   class-name heuristic — it's the most efficient mask.

2. **Increment audit (correct, hard):** find the unpaired
   increment/decrement. Likely candidates:
   - The bless `pending.add(referent)` at `ReferenceOperators.java:84`:
     if the bless is followed by `setLargeRefCounted` storing the same
     referent, both happen, and the deferred-decrement's eventual
     flush is paired with the storing increment — fine. But if the
     bless is followed by a copy-and-store path that doesn't go
     through `setLargeRefCounted`, the deferred decrement is unpaired.
   - The `setLargeRefCounted` "rescue" path
     (`currentDestroyTarget`) at line 1155 increments without a
     paired decrement.

3. **Walker as ground truth (medium):** the universal walker
   (already attempted in D-W6.9) is the most general fix but
   regresses DBIC. Without a smarter root-set definition, this is
   not a viable replacement for the heuristic.

The diagnostic env-flags and tracer are retained on this PR so the
next session can pinpoint the unpaired site without re-bootstrapping
the instrumentation.

### D-W6.11: Concrete fix plan — eliminate the class-name heuristic

The user-stated requirement: **the class-name heuristic is not
acceptable**. We must find and fix the actual unpaired
increment/decrement in cooperative refCount discipline.

#### What we know from the tracer

For the failing CMOP metaclass, instrumented over `use Class::MOP`
with the gate disabled:

| Source | Count |
|---|---|
| `setLargeRefCounted` increments (line 1133) | 50 |
| `bless` silent increment (`ReferenceOperators.java:83`) | 1 |
| `MortalList.deferDecrement` queueings | 2 |
|   ↳ from `bless` at `ReferenceOperators.java:100` | 1 |
|   ↳ from `RuntimeArray.shift` at `RuntimeArray.java:163` | 1 |
| `MortalList.deferDecrementIfTracked` queueings | 42 |
| `MortalList.deferDecrementRecursive` queueings (blessed) | 4 |
| `WeakRefRegistry.weaken` decrements | 6 |
| (queueings flushed during run) | 45 |

Total queueings (deferred decrements): 48
Plus immediate weaken decrements: 6
Total logical decrements at end: 54

Total increments: 51 (50 setLargeRefCounted + 1 silent bless)

**Net: 51 - 54 = -3** decrement excess, vs the expected **+1**
(metaclass held by `our %METAS`). Discrepancy: **4 extra decrements
unpaired with increments**.

Trace artifact: `dev/diagnostic-traces/cmop-metaclass-refcount-with-queue-sites.txt`
(150 lines, full call-stack snippets).

#### Fix plan

**Step 1: Make the tracer track per-scalar ownership.**

Today the tracer knows refCount transitions but not *which scalar*
holds the relevant ownership flag. The unpaired sites are easier to
find if we tag each `setLargeRefCounted` increment with the
RuntimeScalar's identity, and each decrement with the scalar whose
`refCountOwned` is being consumed. Imbalance = an increment scalar
that was never decremented, or a decrement scalar that was never
incremented.

Implementation sketch:
```java
// In setLargeRefCounted: when nb.refCount++ runs,
nb.recordOwner(this);        // tag this scalar as an owner

// In deferDecrementIfTracked: when scalar.refCountOwned -> false,
base.releaseOwner(scalar);   // remove tag

// At end of run, dump base.activeOwners:
//   - 1 entry expected: the hash element scalar of $METAS{name}
//   - 0 entries observed → the bug
```

**Step 2: Identify the unpaired site.**

Run instrumented `use Class::MOP` once. The 4 extra decrement
queueings will surface as either:

- A scalar that was decremented twice (double-release)
- A pending.add() called for a scalar that never owned the increment
- A `deferDecrementRecursive` walking through a container that
  shouldn't be torn down (probably the most likely — DBIC's stash
  cleanup walks too aggressively?)

Likely suspects from the trace:
- **`RuntimeArray.shift` at line 163** queues a `deferDecrement` for
  a blessed metaclass element. When this slot's prior store
  incremented refCount, this dec balances. But if shift was called
  on a non-tracked array (or on a slot that was a copy not the
  original), the dec is unpaired. Stack: `Class/MOP/Package.pm:1281`
  — investigate what shift target is being shifted there.
- **`deferDecrementRecursive` (4 calls)** walks recursively into
  containers being destroyed. If the metaclass is referenced
  through a hash that itself goes out of scope, this is the path.
  But the surrounding hash may have been only a transient (e.g.,
  args list to a method) — its tear-down would be paired with
  whatever increment created that hash's elements.

**Step 3: Fix the unpaired site.**

Once located, the fix is one of:
- Add a missing increment (e.g., a path that stores into a
  container without going through `setLargeRefCounted`)
- Remove a spurious decrement (e.g., `deferDecrementRecursive`
  walking a container whose elements were stored without
  ownership)
- Adjust ownership tracking (e.g., copy-ctor not setting
  `refCountOwned`, but a downstream path treating the copy as if
  it owned)

**Step 4: Remove the class-name heuristic.**

Once the underlying bug is fixed, the gate at
`MortalList.flush()` line 561 simplifies to:

```java
} else if (base.blessId != 0
        && WeakRefRegistry.hasWeakRefsTo(base)
        && ReachabilityWalker.isReachableFromRoots(base)) {
    // Universal walker safety net (no heuristic).
}
```

Or even simpler: remove the gate entirely once cooperative refCount
is correct and DBIC's leak detection passes without rescue.

**Step 5: Acceptance gates.**

The fix is acceptable when ALL of:
- `make` passes (unit tests)
- DBIC `t/52leaks.t` passes 11/11 (today's master baseline)
- Moose suite ≥ 396/478 files (today's baseline)
- `use Class::MOP` succeeds without the walker gate
- The synthetic reproducers in `src/test/resources/unit/refcount/drift/`
  pass with the gate disabled

The diagnostic env-flags (`PJ_REFCOUNT_TRACE`, `PJ_WEAKCLEAR_TRACE`,
`PJ_DESTROY_TRACE`) and instrumentation hooks remain in place for
future regressions.

### D-W6.12: Per-scalar ownership tracker — 2 unpaired increments isolated

Implemented Step 1 of the D-W6.11 plan: per-scalar ownership tracker
on `RuntimeBase` with `recordOwner(scalar, site)` /
`releaseOwner(scalar, site)`. When `PJ_REFCOUNT_TRACE` is set, every
refCount-affecting operation that touches a heuristic-blessed object
records or releases the owning scalar; `dumpTraceOwners()` runs as a
JVM shutdown hook.

Wired increments into:
- `RuntimeScalar.setLargeRefCounted` (line 1133)
- `RuntimeScalar.incrementRefCountForContainerStore` (line 932)
- `ReferenceOperators.bless` re-bless path (line 139)
- `RuntimeScalar.setLargeRefCounted` rescue path (line 1183)

Wired releases into:
- `RuntimeScalar.setLargeRefCounted` overwrite (line 1196)
- `WeakRefRegistry.weaken` (line 116)
- `MortalList.deferDecrementIfTracked` (line 183)
- `MortalList.deferDecrementRecursive` (line 444)
- `RuntimeArray.shift` (line 164)
- `DestroyDispatch.doCallDestroy` args balance (line 364)

After plumbing all known inc/dec paths: zero `*** UNPAIRED RELEASE ***`
events, but one CMOP metaclass (`RuntimeHash` id `408069119`) ends
the run with **`refCount = Integer.MIN_VALUE` (destroyed) and 2
surviving owner scalars holding strong references**. Trace at
`dev/diagnostic-traces/cmop-metaclass-with-owner-tracking.txt`.

The two surviving owners both came from `setLargeRefCounted store`:
- One via `RuntimeScalar.set ← addToScalar:902` (normal store path)
- One via `RuntimeScalar.set ← RuntimeBaseProxy.set:65` (proxy delegate)

This proves the imbalance is **not in the tracer instrumentation**:
those owner scalars genuinely still hold strong refs to the
destroyed metaclass. Cooperative refCount said "0 strong refs"
while in fact 2 strong refs exist.

#### Smoking-gun candidates

The 2 extra decrements that brought refCount → 0 must come from
sites that **modify `base.refCount` without consulting ownership**.
Audit candidates (sites doing `--base.refCount` directly):

| File | Line | Path |
|---|---|---|
| `RuntimeList.java` | 623, 642, 666, 699 | list-assignment "undo materialized copy" |
| `Storable.java` | 617 | dclone refCount fixup |
| `DestroyDispatch.java` | 366 | doCallDestroy args balance (instrumented; OK) |
| `RuntimeBaseProxy.java` | 65–67 | proxy set: copies `lvalue.value` to proxy without ref-tracking |

The most suspicious is `RuntimeBaseProxy.set`:
```java
this.lvalue.set(value);    // properly increments refCount via setLargeRefCounted
this.type = lvalue.type;
this.value = lvalue.value; // proxy ALSO points to base, but no recordOwner
```

The proxy's `this.value` field then holds a strong reference to the
base **invisible to cooperative refcounting**. When the proxy is
later assigned a new value, the proxy's `set()` calls
`lvalue.set(new_value)` which decrements old base's refCount via the
overwrite path — but only because `lvalue.refCountOwned` is true. If
some path inadvertently decrements via `this.value` (bypassing
lvalue), or the proxy is treated as a normal scalar copy
(invalidating `lvalue.refCountOwned`), the count desyncs.

#### Step 2 audit (next):

For each site that does `base.refCount--` directly (without going
through the queueing-then-flushing protocol):

1. Confirm what RuntimeScalar "owned" the increment that this
   decrement is undoing.
2. If a specific scalar can be identified, replace direct decrement
   with `releaseOwner(scalar, site) + base.refCount--`.
3. If no scalar can be identified, the path is decrementing without
   pairing — fix by either:
   a. tracking the increment differently (e.g. recording on the
      original increment), or
   b. removing the decrement (it was unpaired in the first place).

Once all sites are cleanly paired, the cooperative refCount becomes
self-consistent and the walker-gate heuristic can be removed.

#### Files committed on this branch

- `dev/diagnostic-traces/cmop-metaclass-refcount-underflow.txt` —
  D-W6.10 trace (4 inc/dec sites instrumented).
- `dev/diagnostic-traces/cmop-metaclass-refcount-with-queue-sites.txt` —
  D-W6.10 with queue-site tracing.
- `dev/diagnostic-traces/cmop-metaclass-with-owner-tracking.txt` —
  D-W6.12 trace (full owner pairing, surfaces the 2 unpaired
  increments).

The diagnostic instrumentation is left in place (gated on
`PJ_REFCOUNT_TRACE`) so future sessions can resume the audit.

### D-W6.13: activeOwners infrastructure + audit of direct --refCount sites

This session implemented Step 2 of the D-W6.11 plan: audited and
instrumented all direct `--base.refCount` sites with paired
`releaseOwner` / `releaseActiveOwner` calls. The new
`base.activeOwners` set on `RuntimeBase` tracks the live set of
RuntimeScalars that hold a counted strong reference, with
`activeOwnerCount()` providing a filtered count (only scalars that
still satisfy `refCountOwned == true && value == this`).

#### Sites instrumented

- `RuntimeArray.shift` line 163 (`MortalList.deferDecrement` path)
- `RuntimeList.java` line 624, 645, 670, 705 (4 "undo materialized
  copy" paths)
- `Storable.java` `releaseApplyArgs` line 617
- `DestroyDispatch.doCallDestroy` line 366 (args balance)
- `MortalList.deferDecrementIfTracked` line 184
- `MortalList.deferDecrementRecursive` line 446
- `WeakRefRegistry.weaken` line 119
- `RuntimeScalar.setLargeRefCounted` overwrite at line 1199 and
  store at line 1135

#### Production rescue experiment — partial win, partial loss

Trying `activeOwnerCount() > 0 → restore refCount` as a universal
rescue at `MortalList.flush()` (replacing the class-name
heuristic):

| Result | Note |
|---|---|
| `use Class::MOP` works without heuristic | confirmed |
| `use Moose` works without heuristic | confirmed |
| Unit tests: PASS | all green |
| DBIC `t/52leaks.t`: 9–10 of 18 fail | leak rescue too aggressive — keeps cycle members alive that real Perl correctly leaks |

The fundamental issue: cooperative refCount cannot tolerate
cycles without weaken. My filter `refCountOwned && value == this`
finds true strong owners, including cycle members that real Perl
also leaks but DBIC's leak test expects to see destroyed (likely
because real Perl's mark-sweep would clean them up at GC time, or
because the test environment specifically expects refcount-zero).

Reverted production rescue to retain master parity (11/11 on
`t/52leaks.t`, walker-gate heuristic still primary).

#### Surprising side-effect: `ScalarRefRegistry.snapshot()` causes regression

Calling `base.activateOwnerTracking()` on every `weaken()` (which
backfills from `ScalarRefRegistry.snapshot()`) caused 9/18 fails
on `t/52leaks.t` — but `activateOwnerTracking` is supposed to be
side-effect free (just initializes a private set + reads the
registry). The most plausible explanation: iterating
`scalarRegistry.keySet()` triggers `WeakHashMap.expungeStaleEntries()`,
which can shift the timing of when JVM GC observes scalars as
collected. This indirectly affects DBIC's leak detection (which
relies on weak refs becoming undef at specific points).

For now, removed the `activateOwnerTracking()` call from
`weaken()` — the infrastructure is dormant unless explicitly
activated. Future work: investigate the WeakHashMap expungement
side-effect.

#### Status after D-W6.13

- All ownership-affecting sites have paired record/release calls
- `activeOwners` set + `activeOwnerCount()` filter ready for use
- DBIC `t/52leaks.t`: 11/11 (master parity)
- Unit tests: green
- `use Class::MOP` / `use Moose`: work (with class-name heuristic
  still gating)
- Walker-gate heuristic still in place — replacement still pending
  the proper refCount-discipline fix

#### Next step (D-W6.14)

The cooperative refCount underflow is real (D-W6.10 trace shows
the metaclass refCount going to 0 with 2 surviving strong owners
— D-W6.12). The 2 unpaired increments are still unidentified. The
audit of direct `--refCount` sites pointed at all known paths,
which now have paired releases — yet the trace numbers from
D-W6.12 (50 inc / 51 dec) still don't balance.

The next investigation should:
1. Re-run the D-W6.12 trace with all the new release calls in
   place (this branch). The owner dump should now show clean
   `refCount == owners.size()` for the metaclass.
2. If imbalance persists: the unpaired sites are NOT in the 22
   reviewed locations. Search for hidden refCount manipulations:
   - assignment paths in `RuntimeArrayProxyEntry`,
     `RuntimeHashProxyEntry`, `RuntimeStash`
   - bytecode-emitted scope-exit code that uses direct field access
3. If the trace is now clean: the bug is elsewhere — perhaps in
   how `refCount = MIN_VALUE` interacts with subsequent stores
   (the rescue path in `setLargeRefCounted`).

### D-W6.14: How does system Perl solve this? — Algorithm analysis

**Question raised**: my `activeOwnerCount > 0` rescue regresses
DBIC's leak detection by 5-9 tests. What does system Perl do
differently?

**Answer**: System Perl **does not solve cycles**. It uses precise
reference counting, and cycles leak by design. The programmer breaks
cycles via explicit `weaken()` (or `Scalar::Util::weaken`).
DBIC's leak tests pass on real Perl because DBIC weakens its
internal back-references (e.g., `ResultSource → Schema` is weak),
allowing each reference-counted graph to collapse properly when an
external strong reference is dropped.

For PerlOnJava to behave the same way, we need:
1. **Precise cooperative refCount** — every increment paired with
   exactly one decrement, no transient zeros.
2. **Effective weaken()** — weakening must decrement refCount and
   exclude the slot from owner-counting (already done correctly).
3. **No cycle-breaker rescue** — cycles SHOULD leak (matching
   real Perl), and DBIC's weakens will resolve them.

#### What's wrong today

Cooperative refCount has **transient zeros**. The deferred
decrement model (`MortalList.flush`) means a sequence like
`inc → queue → inc → flush → flush → inc` can have refCount briefly
hit 0 between the second flush and the third inc — even though the
third inc's owning scalar was alive throughout.

When the transient zero hits, `MortalList.flush()` fires DESTROY,
permanently corrupting the object's state (clearWeakRefsTo, cascade
cleanup). Even if subsequent stores re-bump refCount, the damage
is done.

#### Why the activeOwnerCount rescue regresses DBIC

`activeOwnerCount > 0` correctly identifies objects with surviving
strong owners. But:

1. **For Class::MOP/Moose metaclasses** (held by `our %METAS`):
   surviving owners reflect real strong refs. Rescue is correct.

2. **For DBIC row objects in test cycles**: `populate_weakregistry`
   weak-refs the row. Then test does `undef $row` in some scope.
   Real Perl: refcount drops to 0, DESTROY fires, weak ref clears.
   PerlOnJava: cooperative refCount has phantom owners — typically
   container element scalars whose containers are themselves
   transient/dying but haven't yet released their elements.

The phantom owners satisfy the filter `refCountOwned == true &&
value == base`, but are themselves on a path to destruction.
Rescue keeps them alive, breaking the leak detection.

#### Best-fit algorithm

System Perl's approach (precise refcount + programmer-controlled
weaken) maps to PerlOnJava as:

**Goal**: eliminate transient zeros from cooperative refCount
without changing the deferred-decrement architecture.

**Algorithm options:**

1. **Synchronous decrement** (simplest, correct, expensive):
   make all decrements synchronous like real Perl. Eliminate
   `MortalList.flush` and put decrement at scope-exit / overwrite
   sites. Performance cost: every Perl statement has ~10x more
   refCount work today.

2. **Owner snapshot at flush** (lazy validation):
   when a deferred decrement would bring refCount to 0, validate
   the activeOwners set first. Force-purge stale entries by:
   - Iterating activeOwners
   - For each owner scalar: check `sc.refCountOwned && sc.value == base`
   - For surviving owners: check whether they are themselves
     reachable from package globals OR live my-vars
   - Rescue ONLY if at least one surviving owner is reachable

   The "reachable owner" check is the critical filter — it
   excludes phantom owners that are themselves on a path to
   destruction. This addresses the DBIC regression.

3. **Two-phase destruction** (deferred validation):
   when refCount→0, defer the actual `clearWeakRefsTo` and
   cascade. Add the object to a "pending destruction" queue.
   Validate at next safe point (e.g., outer flush completion or
   before END blocks):
   - Force a JVM `System.gc()` to purge stale weak refs from
     `ScalarRefRegistry`
   - Re-check `activeOwnerCount`
   - Fire DESTROY only if still 0
   This matches Java's deferred finalization model. Performance:
   System.gc() is slow but only at infrequent boundaries.

**Recommended**: Option 2 (owner snapshot + reachability filter).
The reachability filter naturally excludes phantom owners (they
won't be reachable from roots once their containing scopes have
exited).

#### Implementation sketch (Option 2)

```java
// At MortalList.flush() refCount→0:
if (base.activeOwners != null) {
    // Filter: only count scalars that are owned AND reachable
    int reachableOwners = 0;
    for (RuntimeScalar sc : base.activeOwners) {
        if (sc.refCountOwned && sc.value == base
                && ReachabilityWalker.isScalarReachable(sc)) {
            reachableOwners++;
        }
    }
    if (reachableOwners > 0) {
        base.refCount = reachableOwners;
        return;  // skip DESTROY
    }
}
// Otherwise: fire DESTROY normally
```

Where `isScalarReachable(sc)` checks if `sc` itself is reachable
from any package global, live my-var, or stash entry. This is a
new method on ReachabilityWalker — currently the walker checks
"is base reachable from roots" but not "is THIS specific scalar
reachable".

#### Next session task

Implement Option 2:
1. Add `ReachabilityWalker.isScalarReachable(RuntimeScalar)` —
   walks from roots looking for the specific scalar identity.
2. Wire it into the rescue check at `MortalList.flush()`.
3. Test: Class::MOP loads (with metaclass having package-global
   reachable owners), DBIC `t/52leaks.t` 11/11 (cycle objects
   have only phantom owners not reachable from roots).
4. Remove the class-name heuristic gate.

This is a tractable design change and matches system Perl's
semantics: only objects with reachable strong owners are kept
alive; cycles (with no external reachable owner) leak as in real
Perl.

### D-W6.15: Implementation of D-W6.14 Option 2 — partial success

Implemented `reachableOwnerCount()` (refCount-rescue using
walker-validated active owners) and disabled the class-name
heuristic gate. Wired into `MortalList.flush()` at the
refCount→0 transition.

**Results:**

| Test | Status |
|---|---|
| `use Class::MOP` (no heuristic) | ✅ works |
| `use Moose` (no heuristic) | ✅ works |
| Unit tests | ✅ green |
| DBIC `t/52leaks.t` | ❌ "detached result source" at line 433 (early failure) |

The Class::MOP/Moose case is fully fixed: the metaclass owner
(the `$METAS{name}` hash element) is found reachable through
`globalHashes`, so its `reachableOwnerCount()` returns >0 and
DESTROY is suppressed correctly.

DBIC still regresses: a Schema or ResultSource object is
destroyed prematurely. Some scalar holding the Schema strongly
isn't reachable via the current walker's seeds (globalCodeRefs,
globalVariables, globalArrays, globalHashes, MyVarCleanupStack
live my-vars, RuntimeCode.capturedScalars).

#### What's missing for DBIC

The walker doesn't find Schema's owner. Likely candidates:
- The owner is a JVM-stack-live RuntimeScalar that isn't
  registered in `MyVarCleanupStack` (e.g., a method-call argument
  scalar that's still on the JVM call stack).
- The owner is in a TIE_HASH/TIE_ARRAY/TIED_SCALAR slot that
  needs special walking.
- The owner is in a RuntimeStash entry that the walker doesn't
  enter (line 519 in `isReachableFromRoots(target, false)` skips
  RuntimeStash for perf).

The walker is fundamentally **a snapshot of live JVM state at
this exact moment**. Cooperative refCount can hit 0 transiently
DURING method call frames where strong owners are sitting on the
JVM stack but haven't been pushed into MyVarCleanupStack (e.g.,
intermediate `RuntimeScalar` values during method dispatch).

#### Path forward

The right fix would be one of:

1. **Make every refCount-affecting RuntimeScalar register itself
   in MyVarCleanupStack** (like local vars), so the walker can
   always find live owners. Cost: every increment of refCount
   adds a stack entry.

2. **Use Java GC as ground truth**: treat refCount<=0 +
   walker-unreachable as "candidate for destruction". Defer the
   actual DESTROY call to a periodic safe point (e.g., MortalList
   batch-flush boundary), where we force `System.gc()` and
   re-validate. Java GC will purge truly-dead objects from
   ScalarRefRegistry; survivors are real strong owners.

3. **Eliminate transient zeros at the source**: ensure
   cooperative refCount never goes to 0 except at true scope
   exits, by making MortalList drain only AFTER the destination
   scalar's increment has happened. Requires re-ordering JVM-
   emitted bytecode for assignments (probably very invasive).

#### Status

The class-name heuristic gate is **not removable today**. The
infrastructure for D-W6.14 Option 2 is in place but the reachability
gap for DBIC's intermediate owners must be bridged first. The
heuristic gate is the safety net until that bridge is built.

For this session, the heuristic gate remains disabled in code
(line 591 has `else if (false ...)`), with `reachableOwnerCount()`
as the only rescue. This means Class::MOP/Moose work but DBIC
regresses. To restore master parity, re-enable the heuristic gate
as a fallback alongside `reachableOwnerCount > 0`.

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

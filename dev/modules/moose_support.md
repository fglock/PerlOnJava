# Moose Support for PerlOnJava

## Overview

This document tracks the path to passing **477 / 478** Moose 2.4000 test
files (everything except `t/todo_tests/moose_and_threads.t`, which is
already TODO upstream — PerlOnJava does not implement `threads`).

Two complementary tracks:

1. **Phases 3 → 6** — incremental shim widening (Moose-as-Moo). Ships
   value to Moose-using CPAN modules without bundling upstream Moose.
2. **Phase D** — bundle the upstream pure-Perl `Moose` distribution
   plus `Class::MOP::PurePerl` (~500 lines that replace the 710 lines
   of XS / `mop.c`). This is the phase that gets us to 477 / 478.

### Out of scope

- `t/todo_tests/moose_and_threads.t` (uses Perl `threads`; already
  TODO upstream). Skipped via distroprefs in Phase D.
- `fork` semantics — zero Moose tests use `fork`.
- Real JVM-level class generation (Byte Buddy / Javassist / extra ASM).
  `Class::MOP` operates on Perl stashes, not `java.lang.Class`.

### Already covered in core PerlOnJava

(Listed because they were "blockers" in earlier revisions and no longer are.)

- `weaken` / `isweak` — cooperative refcount on top of JVM GC.
  See `dev/architecture/weaken-destroy.md`.
- `DESTROY` / `DEMOLISH` — fires deterministically for tracked
  blessed objects.
- `B` module subroutine name/stash introspection
  (`RuntimeCode.subName` / `packageName`, surfaced via
  `B::CV` / `B::GV`). This was the original Phase 1 prerequisite.

### Current dependency status

`Class::MOP` and `Moose` are the only missing pieces; all runtime
prereqs work today: `Try::Tiny`, `Module::Runtime`,
`Devel::GlobalDestruction`, `Devel::StackTrace`, `Devel::OverloadInfo`,
`Sub::Exporter`, `Sub::Install`, `Sub::Identify`, `Data::OptList`,
`Class::Load`, `Package::Stash`, `Eval::Closure`, `Params::Util`,
`B::Hooks::EndOfScope`, `Package::DeprecationManager`,
`Dist::CheckConflicts`, `MRO::Compat`. `ExtUtils::HasCompiler`
deterministically returns false (Phase A stub at
`src/main/perl/lib/ExtUtils/HasCompiler.pm`).

---

## Phase plan summary

### Done

- **Phase 1** — B-module subroutine introspection.
- **Quick path** — Moose-as-Moo shim (`Moose.pm`, `Moose::Role`,
  `Moose::Object`, `Moose::Util::TypeConstraints`).
- **Phase A** — `ExtUtils::HasCompiler` deterministic stub.
- **Phase C-mini** — `Class::MOP` shim (`class_of`, `get_metaclass_by_name`,
  `get_code_info`, `is_class_loaded`, …).
- **Phase 2 / Phase 3 stubs** — `metaclass.pm`, `Test::Moose`,
  `Moose::Util`, rich `Moose::_FakeMeta` (with `@ISA` = (`Class::MOP::Class`,
  `Moose::Meta::Class`) so `isa_ok` passes), per-class meta cache,
  attribute tracking via `has` wrapper, plus skeleton stubs for
  `Class::MOP::{Class,Attribute,Method,Method::Accessor,Instance,Package}`,
  `Moose::Meta::{Class,Method,Attribute,Role,Role::Composite,
  TypeConstraint,TypeConstraint::Enum,TypeConstraint::Parameterized,
  Role::Application::RoleSummation}`, `Moose::Util::MetaRole` (with
  `apply_metaroles` no-op), `Moose::Exception` (overloaded stringify
  + `throw`), `Moose::Exporter`. Pre-populated standard type-constraint
  stubs (`Any`, `Item`, `Defined`, `Bool`, `Str`, `Num`, `Int`, `ArrayRef`,
  `HashRef`, `Object`, …) so `t/type_constraints/util_std_type_constraints.t`
  doesn't `BAIL_OUT`. Added `find_or_parse_type_constraint` (handles
  `Maybe[Foo]`, `Foo|Bar`, `ArrayRef[Foo]`, `HashRef[Foo]`,
  `ScalarRef[Foo]`), `export_type_constraints_as_functions`,
  `Moose::Meta::Role->create_anon_role`. Pre-loading
  `use Class::MOP ()` in `Moose.pm` / `Moose/Role.pm` so Moo's runtime
  probes for `Class::MOP::class_of` always resolve.

### Not started

- **Phase 4** — hook `Moose::_FakeMeta->get_attribute*` into Moo's
  attribute store via `Moo->_constructor_maker_for($class)->all_attribute_specs`.
- **Phase 5** — `Moose::Util::MetaRole::apply_metaroles` real-ish apply.
- **Phase 6** — full `Moose::Exporter` sugar installation.
- **Phase D** — bundle upstream Moose + `Class::MOP::PurePerl`.
  See "Phase D plan" below.

### Deferred

- **Phase B** — strip `OBJECT`/`XS`/`C`/`H`/`XSPROTOARG`/`XSOPT` keys
  in `WriteMakefile`. Not on the Moose critical path; bundled Moose
  ships from the JAR.
- **Phase E** — export-flag MAGIC for `Moose::Exporter` re-export
  tracking. Affects warnings only.

---

## Lessons learned (do NOT repeat these mistakes)

### Core-runtime fixes that were attempted and reverted

1. **`*GLOB{SCALAR}` returning a fresh `\$value` reference** (commit
   `880bf65c7`, reverted in `3d02203dc`). Motivation: Class::Load::PP
   does `${ *{...}{SCALAR} }` and our impl returned a copy. The "fix"
   silently broke Path::Class (and DBIC by extension) because
   Path::Class's overload code does `*$sym = \&nil; $$sym = $arg{$_};`
   — assignments through the glob-deref expect to land on the
   package's actual SV slot, not a throwaway reference.
   **Lesson**: any change to typeglob slot semantics must be validated
   against the full DBIC suite, which exercises Path::Class heavily.
   The eventual real fix was different (see below: PerlOnJava's actual
   `*x{SCALAR}` bug returned the value instead of a SCALAR ref;
   `RuntimeGlob.java` line 554-565 now calls `createReference()` like
   the ARRAY/HASH/GLOB cases). Regression test in
   `src/test/resources/unit/typeglob.t`.

2. **Auto-sweep weaken / walker-gated destroy, version 1**
   (commits `ca3af1ad3` + `ecb5c6400`, reverted in `f8ef367e4` /
   `d3743a11c`). Motivation: Class::MOP bootstrap died because the
   metaclass was being destroyed mid-construction. The "fix" coupled
   destroy timing to the reachability walker's view of refcount. It
   passed targeted refcount unit tests but introduced regressions in
   DBIC's `t/52leaks.t` that the unit tests didn't catch.
   **Lesson on measurement**: running partial DBIC subsets and
   treating "fast-fail at compile time" as "no regression" is wrong.
   The correct gate is the full `./jcpan --jobs 1 -t DBIx::Class`
   (~24 min, 314 files / ~13858 assertions).

3. **"Skip destroy when weak refs exist" guard in `MortalList.flush()`**
   (D-W3 step W3, attempt 1). Broke 5+ existing weaken/destroy unit
   tests (`weaken_destroy.t`, `weaken_edge_cases.t`, `weaken_basic.t`,
   `destroy_anon_containers.t`, `weaken_via_sub.t` Case 5). Reverted.

4. **Same guard tightened to `blessId != 0 && hasWeakRefsTo`** (D-W3
   step W3, attempt 2). Still broke cycle-breaking-via-weaken tests
   because cycles of blessed objects rely on DESTROY firing when the
   last external strong ref disappears. With the guard, the cycle
   stays alive forever. Reverted.
   **Lesson**: there is no simple predicate at the destroy gate that
   distinguishes "transient refCount drift during heavy ref shuffling"
   from "genuine end-of-life with weak refs about to clear". The fix
   has to live in the **accounting**, not at the gate.

5. **Class-name-restricted walker gate** (PR #572, commit `0c90da3fe`,
   D-W2c). Restricted the walker gate to objects blessed into
   `Class::MOP*` / `Moose*` / `Moo*`. Made DBIC + Moose both pass, but
   violated the "language behaves the same regardless of which module
   you use" rule. Replaced by the universal walker (commit `2f5490771`,
   D-W5), then both replaced by D-W6 (see below).

6. **`globalOnly=true` walker gate** (commit `d769faceb`, D-W5
   intermediate). Skipped my-var seeding so only package-global
   reachability counted. Major Moose regression: anonymous metaclasses
   are held *strongly* only via my-vars and *weakly* in `our %METAS`
   — without my-var seeding the walker thought they were unreachable
   and DESTROY fired. `t/type_constraints/util_std_type_constraints.t`
   alone went from 0 to 556 failing assertions. Replaced.

7. **Universal walker gate** (commit `2f5490771`, D-W5 final).
   Removes the class-name list. Strictly improves Moose vs the
   heuristic but four DBIC patterns regress because the walker is
   "too correct":
   - `t/cdbi/04-lazy.t` test 11 (`_attribute_exists('opop')`) — a
     transient blessed object's DESTROY fires during DBIC's
     `_construct_results` row-build path and takes column data with it.
   - `t/storage/txn_scope_guard.t` test 18 — relies on Perl 5's exact
     refcount timing (`@DB::args` capture creates a second strong ref
     after the first DESTROY fires, triggering a second DESTROY +
     warning). The walker sees the capture and defers the first
     DESTROY, so only one fires.
   - `t/52leaks.t` — relies on self-referential cycles **leaking**
     (Perl 5 cannot collect cycles). Our walker collects them, so
     `$r` is undef when the test tries to register it.
   - `util_std_type_constraints.t` "no plan" tail.
   The walker gate is the wrong abstraction: it either over-protects
   (broken cycle break) or under-protects (broken DBIC bootstrap).
   **D-W6 below is the principled replacement.**

### Empirical comparison of the gate variants (D-W5)

| Discriminator | DBIC files / asserts failed | Moose files / asserts failed |
|---|---|---|
| Class-name heuristic (PR #572 baseline) | **0 / 0** ✅ | 82 / 137 |
| No gate at all | 7 / 2 ❌ | 77 / 134 |
| `isReachableFromRoots(target, globalOnly=true)` | 3 / 1 ❌ | 63 / 691 |
| Universal walker (default seeding) | 4 / 2 | **61 / 133** ✅ |

### Lessons learned about stub design (post-Phase-2)

1. **Compile-time stubs are the highest-leverage move.** Each round
   of "let `require X` succeed" cleared dozens of files at once.
2. **Pre-loading is as important as having the stub.** Once
   `Moose.pm` set `$INC{Moose.pm}`, Moo's runtime probes called
   `Class::MOP::class_of` from random call sites; without
   `use Class::MOP ()` at the top of `Moose.pm` / `Moose/Role.pm`
   ~50+ runtime errors mask any further shim widening.
3. **One `BAIL_OUT` can hide an arbitrary number of trailing test
   files** (alphabetical order). `util_std_type_constraints.t`'s
   `BAIL_OUT("No such type ...")` was costing ~7 trailing files per
   run. Pre-populating standard type-constraint stubs contained it.
4. **Stub objects must `isa` the right things.** `isa_ok($meta,
   'Moose::Meta::Class')` etc. need the stub's `@ISA` set to the
   real upstream class names; a plain blessed hashref isn't enough.
5. **Bless return values, don't return raw hashrefs.**
   `Moose::Util::TypeConstraints::_store` originally returned
   unblessed hashrefs and produced "Can't call method 'check' on
   unblessed reference" failures. Now blesses into `_Stub`
   (`@ISA` = `Moose::Meta::TypeConstraint`).
6. **The Moose-as-Moo gap is mostly method surface, not metaclass
   semantics.** Most upstream tests want `$meta->add_attribute`,
   `$meta->get_method`, `$meta->is_mutable` to exist and return
   sensible-shaped values; they rarely care that the metaclass is
   "real". Enriching `Moose::_FakeMeta` is high-leverage.

---

## Lock in progress as bundled-module tests

`src/test/resources/module/{Distribution}/` is reserved for
**unmodified upstream test files** of CPAN distributions we actually
bundle. Use it only when both apply:

1. The distribution itself is bundled (its `.pm` files live in
   `src/main/perl/lib/`, or the test directory ships its own `lib/`).
2. The tests being copied are the upstream tests for **that** distribution.

Conventions:
- One directory per CPAN distribution (`Moose/`, `Class-MOP/`, …);
  use the dist name with `::` replaced by `-`.
- Mirror upstream `t/` exactly. Don't edit the test files; if a test
  is incompatible, fix the runtime per AGENTS.md.
- Tests are picked up automatically by Gradle's `testModule` task.
- Verify with `make test-bundled-modules`.

For any "test against shim, don't install" scenario, define a CPAN
distroprefs entry that overrides `pl` / `make` / `install` with no-ops
and `test` with `prove --exec jperl ...`. The current Moose distropref
is shipped from `src/main/perl/lib/CPAN/Config.pm`
(auto-bootstrapped to `~/.perlonjava/cpan/prefs/Moose.yml`). It:
- ensures `Moo` is installed before testing (the shim delegates to
  Moo) via a Perl helper
  `PerlOnJava::Distroprefs::Moose::bootstrap_pl_phase`;
- creates a stub `Makefile` (CPAN.pm's "no Makefile created" fallback);
- skips `make` and `install` (`PerlOnJava::Distroprefs::Moose::noop`);
- runs `prove --exec jperl -r t/`.

`jcpan` / `jcpan.bat` prepend the project directory to `PATH` and
export `JCPAN_BIN` so subprocesses on Unix and Windows find `jperl`
and can recursively invoke jcpan. The design avoids POSIX-only shell
constructs (`||`, `;`, `touch`, `/dev/null`, `$VAR`) that don't work
in `cmd.exe`. Each phase is a single
`jperl -MPerlOnJava::Distroprefs::Moose -e '...'` invocation.

We deliberately avoid a CPAN `depends:` block — it would force CPAN
to resolve Moose's full upstream prereq tree
(`Package::Stash::XS`, `MooseX::NonMoose`, …), most of which is XS
and unsatisfiable.

Because `prove --exec` invokes `jperl` per test file without adding
`lib/` or `blib/lib/` to `@INC`, the **bundled shim from the jar**
wins over the unpacked upstream `lib/Moose.pm`.

---

## Phase D plan

### D1 — Bundle upstream `.pm` files

Drop `Moose-2.4000/lib/{Class/MOP*,Moose*,metaclass.pm,Test/Moose.pm,
oose.pm}` into `src/main/perl/lib/`, replacing the shim files. Also
snapshot upstream `t/` into `src/test/resources/module/Moose/t/` for
regression coverage (per AGENTS.md). Effort: ~½ day.

### D2 — Patch `Class::MOP.pm` to skip `XSLoader::load`

Upstream `Class::MOP.pm` line 31 has an unconditional
`XSLoader::load('Moose', $VERSION)`. PerlOnJava fails with
"Can't load shared library on this platform". Replace with:

```perl
if ($ENV{MOOSE_PUREPERL} || !$Config{usedl}) {
    require Class::MOP::PurePerl;
}
else {
    XSLoader::load('Moose', $VERSION);
}
```

PerlOnJava's `$Config{usedl}` is empty, so this routes to PurePerl.
This is the only modification to upstream Moose code. Document it
prominently so future sync-ups don't drop it. Effort: ~½ day.

### D3 — Implement `Class::MOP::PurePerl`

The XS provides accessor methods on a handful of mixin classes; none
of them do anything clever — all read/write hash slots on the
metaclass / attribute / method instances.

| .xs file              | Lines | What it provides |
|-----------------------|-------|------------------|
| `Attribute.xs`        | 9     | BOOT only |
| `AttributeCore.xs`    | 18    | mixin readers (name / accessor / reader / writer / predicate / clearer / builder / init_arg / initializer / definition_context / insertion_order) |
| `Class.xs`            | 12    | BOOT only |
| `Generated.xs`        | 9     | BOOT only |
| `HasAttributes.xs`    | 9     | `_attribute_map` reader |
| `HasMethods.xs`       | 89    | `_method_map`, tied method install |
| `Inlined.xs`          | 8     | BOOT only |
| `Instance.xs`         | 8     | BOOT only |
| `MOP.xs`              | 22    | `is_class_loaded`, `_inline_check_constraint`, … |
| `Method.xs`           | 23    | `body` / `name` / `package_name` accessors |
| `Moose.xs`            | 148   | `Moose::Util::throw_exception_class_callback`, init_meta hooks |
| `Package.xs`          | 8     | BOOT only |
| `ToInstance.xs`       | 63    | `Class::MOP::class_of` fast path |
| `mop.c`               | 284   | `mop_install_simple_accessor`, `mop_class_check`, `mop_check_package_cache_flag` |

Total Perl replacement: < 500 lines, mostly
`sub name { $_[0]->{name} }`-shaped one-liners. Lives in one new
file: `src/main/perl/lib/Class/MOP/PurePerl.pm`. It walks the mixin
packages and installs accessors plus the few non-accessor helpers.
Reference: upstream Moose pre-XS commit `bf38c2e9` shows exactly
which Perl was replaced. Effort: ~3 days.

### D4 — Verify prereqs still load

All Class::MOP runtime prereqs work today (see "Current dependency
status" above). Re-verify with the bundled (vs shim) `Class::MOP`.
Effort: ~½ day.

### D5 — Distroprefs threads-test exclusion

`prove --not` does not exist — known issue. Workaround: a small
`--exec` wrapper that returns `1..0 # SKIP threads not implemented`
for `t/todo_tests/moose_and_threads.t` and runs `jperl` for every
other file. ~10 lines of Perl. Effort: ~10 min.

### D6 — Snapshot tests

Per AGENTS.md, copy `Moose-2.4000/t/` (minus the threads file) into
`src/test/resources/module/Moose/t/` and add it to
`make test-bundled-modules`. Effort: ~½ day.

### Phase D total: ~5 days

**Outcome**: 477 / 478 fully-green files. Anything still failing
after Phase D is a real bug in PerlOnJava core (not in the Moose
port) and gets fixed in core.

### Previous Phase D attempt status

The previous Phase D attempt got as far as bundling the upstream
files in branch `feature/moose-phase-d` (since deleted) and worked
out the D2 XSLoader patch and a D3 skeleton. It was paused on the
walker-gate / refcount issues now superseded by D-W6.

---

## Phase D-W3: Sub::Util / sort BLOCK fixes (DONE)

Two bytecode/runtime bugs found while triaging the Moose plateau.

### D-W3a: `Sub::Util::subname` of anonymous subs

`B::svref_2object($code)->GV->STASH->NAME` (used by
`Class::MOP::get_code_info` and `Class::MOP::Mixin::HasMethods::
_code_is_mine`) returned `main` for any anon sub created in a
non-main package. Real Perl returns the compile-time package
(CvSTASH), recorded on `RuntimeCode.packageName`.

Fix:
- `Sub::Util::subname` returns `Pkg::__ANON__` when the sub has no
  name but a known package.
- Honors the `explicitlyRenamed` flag for `set_subname("", $code)`
  (returns empty string, not `__ANON__`).
- `B.pm`'s `_introspect` accepts `Pkg::__ANON__`, `Pkg::` and bare
  renames.

This unblocks immutable metaclass trait application (the
`Class::MOP::Class::Immutable::Trait::add_method` etc. were being
rejected by `_code_is_mine`).

### D-W3b: sort BLOCK comparator @_

`sort { $_[0]->($a, $b) } @list` (Moose's native Array trait `sort`
accessor) — real Perl exposes the surrounding sub's `@_` inside the
sort BLOCK; PerlOnJava was creating a fresh empty `@_`.

Fix: pass slot 1 (`@_`) to `ListOperators.sort` from the JVM
emitter (`EmitOperator.handleMapOperator`) and the interpreter
(`InlineOpcodeHandler.executeSort`); forward as the comparator's
args (unless the comparator has a `$$` prototype). SORT op
descriptor widened to include `RuntimeArray` (the outer @_).

---

## Phase D-W6: replicate Perl 5 destroy semantics exactly

### Why

The user's directive: *PerlOnJava should not "improve on" Perl 5's
DESTROY semantics; it should match them.* The two remaining DBIC
regressions in D-W5 are exactly the cases where PerlOnJava
"diverges by being more correct":

- **`52leaks.t`**: tests that self-referential cycles **leak**.
  Real Perl 5 cannot collect cycles; an object holding `$self->{me}
  = $self` lives forever even after the user drops their last
  external reference. PerlOnJava's walker currently *detects* the
  cycle and destroys it, which is technically nicer but breaks
  any code that relies on the leak as a feature (the entire
  intentional-leak diagnostic in DBIC's `t/52leaks.t`, plus any
  user code that uses the leak to keep a per-instance state alive
  through "I'll fix this later" cycle constructs).

- **`txn_scope_guard.t`**: tests that DESTROY fires *each* time
  refCount transitions to 0 — including the transient transition
  where a `Devel::StackTrace`-style `@DB::args` capture briefly
  holds the object after the user-visible `undef $g`. Real Perl 5
  fires DESTROY immediately at the first refCount=0, then again
  when the captures are dropped, and DBIC's TxnScopeGuard guards
  against the second one with a warning. PerlOnJava's walker sees
  the capture and defers the first DESTROY, so only one fires
  (no warning).

The right fix is to make cooperative refCount the **single source
of truth** for DESTROY firing, and use the walker only for explicit
`jperl_gc()` calls. Cycles will leak (matching Perl 5). DESTROY
will fire at every refCount=0 transition (matching Perl 5).

### What was wrong with the walker-gate approach

The walker gate exists because cooperative refCount is known to
drift — it transiently drops to 0 in MOP-style code (Moose,
Class::MOP, Moo, DBIx::Class::Schema bootstrap) where blessed
objects bounce through hash slots, closures, and method-modifier
wrappers. The walker absorbs the drift at the cost of incorrect
behaviour for the two patterns above.

The proper long-term fix (already noted in D-W2c "Why this is a
stopgap") is to **find and back-fill the missing refCount
increments at the source**, not paper over them with the walker.

### Concrete plan

#### Step 1 — instrument the refCount-drift sources

For each Moose-bootstrap pattern that currently relies on the
walker gate, identify *where* the cooperative refCount drift
happens. Candidates (from prior D-W investigations):

1. **Hash slot stores in MOP-style code** — e.g.
   `$METAS{$pkg} = $meta`. Does `RuntimeHash.put` always
   increment `$meta`'s refCount before discarding the previous
   slot value? Audit `RuntimeHash.put`, `RuntimeHash.put` via
   `set`, hash-slice stores (`@h{@keys} = @vals`), and the
   special-case paths in `RuntimeScalar.set`.
2. **Closure captures** — `RuntimeCode.capturedScalars`. When a
   blessed scalar is captured by a closure, the refCount must
   reflect that. Today some capture paths use `setLargeRefCounted`
   / `addToCapturedScalars` correctly but others may go through
   `MortalList` deferred-decrement before the capture is
   established.
3. **Argument promotion in method calls** — `@_` is built from
   the caller's arg list. Each scalar in `@_` should hold its
   own refCount on its referent. Audit `RuntimeArray.push`,
   `RuntimeArray.set` for the per-element refCount path.
4. **Glob assignment** — `*Foo::bar = sub {...}` stores a
   blessed CV. Audit `GlobalVariable.setGlobalCodeRef` and the
   typeglob-overlay path in `RuntimeGlob`.
5. **Stash / package-global writes** — `our %FOO = ...`,
   `${"Pkg::var"} = ...`. Audit `GlobalVariable.set*`.

For each source, write a tiny standalone reproducer that drops
the refCount to 0 transiently while a strong reference is still
held. Add it to `src/test/resources/unit/refcount/drift/`.

#### Step 2 — fix each drift source

For each reproducer, add the missing
increment/decrement so cooperative refCount stays consistent.
Verify each with `make` + the reproducer.

#### Step 3 — remove the walker gate

Once Steps 1 and 2 are complete, the walker gate at
`MortalList.flush` and `RuntimeScalar.set` can be removed
entirely:

```java
// Today:
} else if (base.blessId != 0
        && WeakRefRegistry.hasWeakRefsTo(base)
        && ReachabilityWalker.isReachableFromRoots(base)) {
    // defer
}

// After Step 3:
} else {
    base.refCount = Integer.MIN_VALUE;
    DestroyDispatch.callDestroy(base);
}
```

The walker continues to exist for explicit `jperl_gc()` calls
(opt-in cycle collection), but is **not** consulted on every
refCount→0 transition.

#### Step 4 — verify both Perl 5 semantics tests pass

- `52leaks.t`: the test populates `@circreffed` with cycles and
  then weakens them. With cooperative refCount alone, the cycle
  members keep each other alive; weaken doesn't drop a strong
  ref; the cycle leaks; `$r` stays defined; the test runs to
  completion.

- `txn_scope_guard.t` test 18: `undef $g` triggers DESTROY
  immediately (refCount→0). The handler captures @DB::args
  refs, raising the captured object's refCount back to ≥1 after
  DESTROY exits. `@arg_capture = ()` drops to 0 again, firing
  DESTROY a second time. DBIC's `_finalized` guard emits the
  expected warning. The test passes.

#### Step 5 — confirm Moose still works

Run `./jcpan -t Moose` and confirm pass rate is *at least* equal
to the current universal-walker number (61 failing files / 133
asserts). If any Moose tests regress, that points to a
not-yet-fixed drift source (back to Step 1 for that pattern).

### Acceptance criteria

- `MortalList.flush` and `RuntimeScalar.set` contain no walker
  call at all (gate is removed, not replaced with another
  heuristic).
- `52leaks.t` passes (cycles leak as expected).
- `txn_scope_guard.t` test 18 passes (double-DESTROY warning
  fires).
- `cdbi/04-lazy.t` passes (the SELECT result no longer loses
  `opop` because no transient destroy clobbers row construction).
- Moose: ≥396/478 (no regression vs current branch).
- DBIC: 0 / 0 PASS.
- All existing refcount unit tests still pass.

### Risk and rollback

This is a refactor of the cooperative refCount contract, not just
a gate change. Each Step 2 fix should be its own commit so any
regression can be bisected. The walker code itself is left in
place (only the gate call sites are simplified), so reintroducing
the gate as a safety net during debugging is a one-line revert.

---

## Hard regression gate (every refcount/destroy change)

Per the user's instruction: **"Failing weaken/DESTROY is not
accepted at all."** Every fix MUST be validated against:

```bash
./jcpan --jobs 1 -t DBIx::Class       # 0 failing assertions, ≤2 failed files
./jcpan --jobs 1 -t Moose              # ≥ 396 / 478 fully green
make                                    # full unit suite green
./jperl src/test/resources/unit/refcount/*.t   # all pass
./jperl src/test/resources/unit/weaken_via_sub.t  # 20/20
```

Parallel runs (`./jcpan -t …` without `--jobs 1`) OOM-crash on the
local box for several DBIC tests; that is environmental, not a
DESTROY regression. Always serialise the regression gate with
`--jobs 1`.

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

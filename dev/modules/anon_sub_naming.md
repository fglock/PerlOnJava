# Anonymous subroutine naming via `*__ANON__`

## Problem

`local *__ANON__ = 'name'` is a Perl idiom for giving an anonymous
subroutine a temporary name visible to `caller()`, `Carp`, and
`Sub::Util::subname()`. It is used by `SUPER.pm`, `Try::Tiny`,
`namespace::clean`, and several Moose internals to make stack traces
and SUPER-dispatch work correctly when subs are installed under
unusual names (or not installed at all).

In PerlOnJava the idiom is silently lost:

```perl
my $s = sub { local *__ANON__ = 'myname'; (caller(0))[3] };
$s->();
# real perl: main::myname
# jperl:     main::__ANON__
```

The root cause: `caller()` and `Sub::Util::subname` read the cached
`RuntimeCode.subName`, which for anonymous subs is always `null` (and
falls back to `"__ANON__"`). Real perl resolves the name dynamically
through `CvGV(cv)->NAME`, so a `local`-scoped alias of the package's
`*__ANON__` glob is observed as a rename.

This blocks `SUPER` (3 of 6 tests fail in `t/bugs.t`), which in turn
blocks `Test::MockModule`, which in turn blocks `DBIx::Retry` and
others.

## Goal

Make `caller()` and `Sub::Util::subname` honor `local *PKG::__ANON__
= 'name'` for the dynamic scope of the local, without regressing
existing behavior or `Sub::Name`/`Sub::Util::set_subname`.

## Design — pragmatic glob indirection (Option A)

### Data model

1. `RuntimeGlob` gains an optional field
   ```java
   public String nameOverride; // null by default
   ```
   This represents the dynamic "name override" of the glob — i.e. the
   string most recently assigned via `*foo = $string`.

2. `RuntimeCode` does **not** need a new field. Anonymous subs
   already carry `packageName` (the CvSTASH equivalent), and that's
   enough to locate the relevant `*PKG::__ANON__` glob via
   `GlobalVariable.globalIORefs.get(packageName + "::__ANON__")`.

### Write path: `*PKG::FOO = $string`

In `RuntimeGlob.set(RuntimeScalar value)`, the scalar-value cases
(STRING / BYTE_STRING / INTEGER / DOUBLE / BOOLEAN / VSTRING /
DUALVAR) currently store `value` into the SCALAR slot. We add:

```java
RuntimeGlob current = GlobalVariable.globalIORefs.getOrDefault(
    this.globName, this);
current.nameOverride = value.toString();
```

We update `current` rather than `this` because `local *FOO` swaps in
a new RuntimeGlob in `globalIORefs`; the lvalue captured before
`local` still references the old RuntimeGlob, but the override must
be visible to readers that look up the *current* glob by name. The
existing SCALAR-slot write already follows this "look up by name"
pattern (see `getGlobalVariable(this.globName)` in `set(STRING)`).

The override is **only** set by glob-as-scalar assignment; plain
`$PKG::__ANON__ = $x` continues to write the SCALAR slot without
touching `nameOverride`. This matches real Perl's distinction
between glob assignment (which does the stash-alias trick) and
scalar assignment.

### Local-scope handling

`RuntimeGlob.dynamicSaveState()` already creates a fresh
`RuntimeGlob` for the local scope and installs it in `globalIORefs`.
A fresh glob has `nameOverride == null`, so the local scope starts
clean. `dynamicRestoreState()` restores the original glob, whose
`nameOverride` was never mutated, so no extra save/restore is
needed.

### Read path: `caller()` and `Sub::Util::subname`

For anonymous subs (where `code.subName` is null/empty and
`code.explicitlyRenamed` is false), consult the override:

```java
String name = null;
if (!code.explicitlyRenamed
        && (code.subName == null || code.subName.isEmpty())
        && code.packageName != null) {
    RuntimeGlob anonGlob = GlobalVariable.globalIORefs.get(
            code.packageName + "::__ANON__");
    if (anonGlob != null && anonGlob.nameOverride != null
            && !anonGlob.nameOverride.isEmpty()) {
        name = code.packageName + "::" + anonGlob.nameOverride;
    }
}
if (name == null) {
    // existing fallback: "Pkg::__ANON__" or stack-trace info
}
```

Lookup order:

1. `code.explicitlyRenamed` (`Sub::Name`, `Sub::Util::set_subname`)
   wins outright — this matches real Perl, where a CV whose `CvGV`
   has been repointed by `Sub::Name` is no longer affected by
   `local *__ANON__` higher up.
2. Anonymous-sub override via `*PKG::__ANON__`'s `nameOverride`.
3. Fallback: `Pkg::__ANON__` (current behavior).

### Interaction with `Sub::Name` / `Sub::Util::set_subname`

`Sub::Name::subname` and `Sub::Util::set_subname` mutate
`RuntimeCode.subName`/`packageName` and set
`explicitlyRenamed = true`. The new lookup explicitly checks
`explicitlyRenamed` first, so:

- Sub::Name on a sub that's also under `local *__ANON__`: the
  Sub::Name name wins (matches real perl).
- Plain anon sub under `local *__ANON__`: the override wins.
- Plain anon sub outside any local: falls back to
  `Pkg::__ANON__` as today.

`B::CV->GV->NAME` and the `_is_renamed` shim in `Sub::Name` are not
touched in this change. A future cleanup could fold the
`explicitlyRenamed` mechanism into the same glob-indirection model
(repointing the anon-glob link on `set_subname`), letting us delete
`_is_renamed`. That's left for follow-up.

## Tests

### Regression baseline (must keep passing)

Captured in `dev/modules/anon_sub_naming_baseline.txt`. Highlights:

| Idiom                                  | Expected name    |
|----------------------------------------|------------------|
| `Sub::Name::subname('My::r', $s)`      | `My::r`          |
| `Sub::Util::set_subname('O::n', $s)`   | `O::n`           |
| Plain `sub { ... }` in `main`          | `main::__ANON__` |
| Plain `sub { ... }` in `Foo::Bar`      | `Foo::Bar::__ANON__` |
| `B::svref_2object(set_subname'd)->GV->NAME` | `n`         |

### New behavior (must start passing)

| Idiom                                          | Expected name |
|------------------------------------------------|---------------|
| `local *__ANON__ = 'myname'` in `sub { caller }` | `main::myname` |
| `local *Foo::__ANON__ = 'x'` in `Foo` package  | `Foo::x` |
| Sub::Name'd sub also under `local *__ANON__`   | Sub::Name's name (unchanged) |
| Carp longmess from sub under `local *__ANON__` | reflects override |
| SUPER.pm `t/bugs.t`                            | 6/6 pass       |

### End-to-end

`./jcpan -i SUPER` should pass tests; `./jcpan -i Test::MockModule`
should follow; `./jcpan -t DBIx::Retry` should at least get past
the "Test::MockModule not found" stage.

## Out of scope

- Making `*foo = "string"` do the full Perl stash-alias dance for
  arbitrary glob names. We only honor the override for naming
  purposes; the SCALAR slot semantics of glob-string assignment are
  unchanged.
- Rebuilding `Sub::Name` on top of glob indirection.
- `B::CV->GV->NAME` reflecting the override dynamically (currently
  always reports `__ANON__` for anon subs; not consulted by SUPER).

## Status

- [x] Design
- [x] Baseline captured (`anon_sub_naming_baseline.txt`)
- [x] Implementation
  - `RuntimeGlob.nameOverride` field
  - `RuntimeGlob.set(scalar)` records override on the live glob via
    `peekGlobalIO(globName)`
  - `GlobalVariable.peekGlobalIO(name)` non-vivifying lookup
  - `RuntimeCode.callerWithSub` consults override for both
    innermost (via `currentSub`) and deeper (via stack-trace
    `Pkg::__ANON__` frame) anon frames
  - `SubUtil.subname` consults override
- [x] SUPER `t/bugs.t` passes 6/6
- [x] `Test::MockModule` installs and tests pass 103/103
- [x] `DBIx::Retry` test chain unblocked (17 subtests run, 1
      remaining unrelated DBD::ExampleP failure)
- [x] Sub::Name baseline diff is empty (no regressions)
- [x] `make` passes

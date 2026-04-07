# Perl::Critic Support Plan

## Goal

Enable `./jcpan Perl::Critic` to install cleanly on PerlOnJava.

## Current Status

`./jcpan Perl::Critic` fails at the configure step because `Build.PL` loads
`inc/Perl/Critic/BuildUtilities.pm` which does `use English qw(-no_match_vars)` —
and `English.pm` is not shipped with PerlOnJava.

Three blockers were identified during the initial install attempt:

## Blockers

### Blocker 1: Missing `English.pm` (Critical — blocks Build.PL)

**Symptom:**
```
Can't locate English.pm in @INC
```

**Root cause:** English.pm is a core Perl module that provides human-readable
aliases for punctuation special variables (e.g. `$ERRNO` for `$!`, `$PID` for
`$$`). It is not currently shipped with PerlOnJava.

**Impact:** Fatal — Build.PL cannot even run. English.pm is used by 28 files
within Perl::Critic itself.

**Fix:** Copy English.pm (v1.11, 237 lines, pure Perl) into
`src/main/perl/lib/English.pm`. It depends only on `Exporter` (already present).
It works via typeglob aliasing (`*LONG_NAME = *short`). Almost all underlying
special variables are already implemented in PerlOnJava.

**Technical risk:** The `*-{ARRAY}` and `*+{ARRAY}` glob-slot access syntax for
`@LAST_MATCH_START`/`@LAST_MATCH_END` may need testing. If these don't parse,
only those 3 aliases are affected — the other 35+ aliases will work.

**Effort:** Low — file copy + smoke test.

### Blocker 2: `foreach my @array` Scoping Bug (Moderate — blocks List::SomeUtils tests)

**Symptom:**
```
Global symbol "@values" requires explicit package name
  at List/SomeUtils/PP.pm line 230
```

**Root cause:** In `List::SomeUtils::PP::apply()`:
```perl
sub apply (&@) {
    my $action = shift;
    &$action foreach my @values = @_;    # line 229
    wantarray ? @values : $values[-1];   # line 230 — @values not visible!
}
```

In standard Perl, `my @array` declared in a `foreach` modifier's list expression
is scoped to the **enclosing block** (the subroutine). In PerlOnJava's JVM
backend, `EmitForeach.emitFor1()` calls `enterScope()` (line 115) BEFORE
evaluating the list expression (lines 368/404), trapping the `my @values`
declaration inside the for loop's scope. When the scope exits at line 740,
`@values` disappears.

The interpreter backend (`BytecodeCompiler.visit(For1Node)`) is correct — it
evaluates the list before entering the loop scope.

**Fix:** In `EmitForeach.emitFor1()`, restructure so the list expression is
evaluated BEFORE `enterScope()`. Store the result in a temporary local, then
enter the scope and create the iterator from the stored result.

**Verification:**
```perl
# System perl output: "2 3 4"
# PerlOnJava current: "" (empty)
perl -e 'sub test { $_++ foreach my @v = @_; print "@v\n" } test(1,2,3)'
```

**Effort:** Medium — requires careful restructure of emitFor1().

### Blocker 3: B::Keywords Missing `CORE/keywords.h` (Non-blocking)

**Symptom:**
```
Can't open any of .../CORE/keywords.h at t/11keywords.t line 54.
```

B::Keywords installed successfully (1 .pm file). One test (`t/11keywords.t`)
skips because it looks for a C header file from Perl's source tree. The module
itself is functional. This does NOT block Perl::Critic.

**Fix:** None needed — the module works, the test is irrelevant on JVM.

## Dependency Tree

### Already Available

| Module | Location |
|--------|----------|
| B::Keywords | `~/.perlonjava/lib/` (installed) |
| List::SomeUtils | `~/.perlonjava/lib/` (installed, needs Blocker 2 fix) |
| Module::Build | `~/.perlonjava/lib/` (installed, with PerlOnJava override) |
| Exception::Class | `~/.perlonjava/lib/` (installed) |
| Carp | `src/main/perl/lib/` |
| Exporter | `src/main/perl/lib/` |
| File::Basename | `src/main/perl/lib/` |
| File::Find | `src/main/perl/lib/` |
| File::Path | `src/main/perl/lib/` |
| File::Spec | `src/main/perl/lib/` |
| File::Temp | `src/main/perl/lib/` |
| File::Which | `~/.perlonjava/lib/` |
| Getopt::Long | `src/main/perl/lib/` |
| List::Util | `src/main/perl/lib/` |
| Module::Pluggable | `~/.perlonjava/lib/` |
| Pod::Usage | `src/main/perl/lib/` |
| Scalar::Util | `src/main/perl/lib/` |
| Term::ANSIColor | `src/main/perl/lib/` |
| Test::Builder | `src/main/perl/lib/` |
| Text::ParseWords | `src/main/perl/lib/` |
| charnames | `src/main/perl/lib/` |
| overload | `src/main/perl/lib/` |
| parent / base | `src/main/perl/lib/` |
| version | `src/main/perl/lib/` |
| strict / warnings | `src/main/perl/lib/` |

### Must Be Installed via jcpan (pure Perl, should auto-install)

| Module | Notes |
|--------|-------|
| Config::Tiny | INI-style config parser, pure Perl |
| Readonly | Read-only variable declarations, pure Perl |
| String::Format | String formatting with named args, pure Perl |
| PPI (1.277) | Full Perl parser — the heart of Perl::Critic. ~15K lines, pure Perl |
| PPIx::QuoteLike | PPI extension for quote-like constructs |
| PPIx::Regexp | PPI extension for regular expressions |
| PPIx::Utils | PPI utility functions |
| Pod::PlainText | From deprecated Pod-Parser distribution |
| Pod::Select | From deprecated Pod-Parser distribution |
| Perl::Tidy | Optional — only for RequireTidyCode policy |
| Pod::Spell | Optional — only for PodSpelling policy |

### Potential Risk: PPI

PPI is a full Perl parser written in pure Perl (~15,000 lines). It exercises
many Perl language features and is the biggest risk for unexpected
incompatibilities. It should be tested incrementally:

1. Install PPI with `./jcpan PPI`
2. Run its test suite with `./jcpan -t PPI`
3. Fix any issues that arise before attempting full Perl::Critic install

### Blockers Discovered in Phase 2 Testing (2026-04-07)

The following blockers were identified during `./jcpan Perl::Critic` after
Phase 1 fixes were applied:

| Blocker | Impact | Details |
|---------|--------|---------|
| **Readonly** | Critical | Needs Module::Build::Tiny, which needs DynaLoader (core, but CPAN can't find it). Fix: `force install Module::Build::Tiny` or provide a stub. |
| **Params::Util XS** | Critical | PPI requires Params::Util, which tries XSLoader::load and dies. PP fallback exists via `PERL_PARAMS_UTIL_PP=1` env var. |
| **Pod-Parser make** | Moderate | podselect.PL script generation fails. Pod::PlainText/Pod::Select .pm files are installed, but `make` step errors. Fix: `force install Pod-Parser` or skip script generation. |
| **Pod::Spell Encode** | Low | `Encode::encode` undefined. Only needed for PodSpelling policy. |

#### Workarounds

```bash
# Params::Util PP fallback
PERL_PARAMS_UTIL_PP=1 ./jperl -e 'use Params::Util; print "OK\n"'

# Force install Module::Build::Tiny (DynaLoader is available but not on CPAN)
# Then install Readonly
```

## Implementation Plan

### Phase 1: Fix Known Blockers (DONE)

1. **Add English.pm** to `src/main/perl/lib/English.pm`
   - Copied from perl5/ tree via dev/import-perl5/sync.pl (v1.12)
   - Test: `./jperl -e 'use English qw(-no_match_vars); print "$PID\n"'`
   - Test: `./jperl -e 'use English; "hello" =~ /ell/; print "$MATCH\n"'`

2. **Fix `foreach my @array` scoping** in EmitForeach + StatementResolver
   - StatementResolver: hoists `my` declarations from foreach list expression
   - EmitForeach: pre-evaluates list before `enterScope()`
   - Verify: `./jperl -e 'sub t { $_++ foreach my @v = @_; print "@v\n" } t(1,2,3)'`
     prints `2 3 4`

3. **`make`** passes — no regressions

### Phase 2: Fix Dependency Blockers

Four blockers were identified when `./jcpan Perl::Critic` progressed past Build.PL.
Each is detailed below with root cause analysis and the recommended fix.

---

#### Phase 2a: Add `DynaLoader.pm` stub (unblocks Readonly)

**Dependency chain:**
```
Perl::Critic → Readonly 2.05
  → configure_requires: Module::Build::Tiny ≥ 0.035
    → configure_requires: DynaLoader ≥ 0   ← BLOCKER
    → runtime_requires:   DynaLoader ≥ 0   ← BLOCKER
```

**Root cause:** DynaLoader is implemented in Java (`DynaLoader.java`, 44 lines,
initialized at startup via `GlobalContext.java`). It registers `bootstrap` and
`boot_DynaLoader` in the Perl namespace. However, there is **no `DynaLoader.pm`
file**, so:
- `require DynaLoader` fails (no file to load, no `%INC` entry)
- CPAN.pm's `_file_in_path()` can't find it → considers the dependency unmet
- Module::Build::Tiny builds and passes tests, but CPAN refuses to mark it
  installed because "one dependency not OK (DynaLoader)"

**Contrast:** `XSLoader` works because it has a stub at `src/main/perl/lib/XSLoader.pm`
(67 lines) that sets `$VERSION` and delegates to the Java implementation.

**Fix — create `src/main/perl/lib/DynaLoader.pm`:**
```perl
package DynaLoader;
our $VERSION = "1.56";
# Java-backed stub. bootstrap() and boot_DynaLoader() are registered
# at startup by DynaLoader.java. This file exists so that:
#   1. `require DynaLoader` succeeds and populates %INC
#   2. CPAN can find $DynaLoader::VERSION for dependency checking
our @ISA = ();
our @EXPORT = qw(bootstrap);
BEGIN {
    unless (defined &bootstrap) {
        *bootstrap = sub { my $m = $_[0] || caller; die "Can't load module $m\n" };
    }
}
1;
```

Also set `$DynaLoader::VERSION` in `DynaLoader.java`'s `initialize()`, matching
what `XSLoader.java` does:
```java
GlobalVariable.getGlobalVariable("DynaLoader::VERSION").set("1.56");
```

**Verification:**
```bash
./jperl -e 'require DynaLoader; print "$DynaLoader::VERSION\n"'  # → 1.56
./jcpan Module::Build::Tiny   # should install cleanly
./jcpan Readonly               # should install cleanly
```

**Effort:** Low — stub file + one-line Java change.

---

#### Phase 2b: Fix `XSLoader::load` for PP-fallback modules (unblocks PPI)

**Dependency chain:**
```
Perl::Critic → PPI → Params::Util   ← BLOCKER
```

**Root cause:** `Params::Util` (already installed in `~/.perlonjava/lib/`) loads like this:
```perl
use parent qw{Exporter XSLoader};           # line 61
use Params::Util::PP qw();                  # line 63  — loads PP fallback
XSLoader::load("Params::Util", $VERSION)    # line 68  — DIES HERE
    unless $ENV{PERL_PARAMS_UTIL_PP};
# PP wiring at line 86 — never reached:
Params::Util->can($_) or *$_ = Params::Util::PP->can($_) for (@EXPORT_OK);
```

In `XSLoader.java`, `load("Params::Util")`:
1. Tries `Class.forName("org.perlonjava...ParamsUtil")` → ClassNotFoundException
2. Checks `@Params::Util::ISA` = `("Exporter", "XSLoader")` — both are in the
   `NON_FUNCTIONAL_ISA` exclusion set → `hasFunctionalParent()` returns false
3. Dies: `"Can't load loadable object for module Params::Util"`

The die is **not** inside an eval in `Params/Util.pm`, so it fatally aborts the
entire `require`.

**Fix — detect PP companion in `XSLoader.java`:**

Before dying (around line 136), check if `${moduleName}::PP` is already loaded
in `%INC`. This handles the common CPAN pattern where modules pre-load a PP
fallback before calling `XSLoader::load`:

```java
// In XSLoader.java, after hasFunctionalParent() check fails, before die:
String ppKey = moduleName.replace("::", "/") + "/PP.pm";
RuntimeHash inc = GlobalVariable.getGlobalHash("main::INC");
if (inc != null && inc.exists(ppKey).getBoolean()) {
    // PP companion is loaded — let the module's own fallback wiring handle it
    return scalarTrue.getList();
}
```

This also fixes `List::MoreUtils` and any future module using the same pattern.

**Why not env vars?** Setting `PERL_PARAMS_UTIL_PP=1` in `jcpan` only helps
during installation. Users running `./jperl -e 'use Params::Util'` would still
hit the error. The XSLoader fix is the right general solution.

**Verification:**
```bash
./jperl -e 'use Params::Util qw(_STRING); print _STRING("hello"), "\n"'  # → hello
./jcpan PPI   # should install cleanly
```

**Effort:** Low — ~5 lines in XSLoader.java.

---

#### Phase 2c: Make PL_FILES failures non-fatal in MakeMaker (unblocks Pod-Parser)

**Dependency chain:**
```
Perl::Critic → Pod::PlainText → Pod::Select → Pod::Parser
                (all from the Pod-Parser distribution)
```

**Root cause:** Pod-Parser's `Makefile.PL` declares `PL_FILES` to generate the
`podselect` CLI tool via `scripts/podselect.PL`. PerlOnJava's MakeMaker puts
`pl_files` in the `all` target's dependency chain:

```makefile
all: pm_to_blib pure_all pl_files config    # line 549
```

When `make` runs:
1. `pm_to_blib` succeeds — all `.pm` files are installed
2. `pl_files` fails — `podselect.PL` crashes under jperl
3. `make` returns non-zero → CPAN.pm halts

Perl::Critic only needs the `.pm` files (`Pod::PlainText`, `Pod::Select`,
`Pod::Parser`, `Pod::InputObjects`). It does **not** need the `podselect` CLI.

**Note:** The `.pm` files may already be present in `~/.perlonjava/lib/` from a
prior `force install`. If so, this blocker is not currently active — but it will
hit any fresh installation.

**Fix — prefix PL_FILES commands with `-` (Make ignore-error):**

In `src/main/perl/lib/ExtUtils/MakeMaker.pm`, change the PL_FILES command
generation (around line 510) to prefix each command with `-` so Make treats
failures as non-fatal:

```perl
# Before:
push @pl_cmds, "\t$perl $pl $t";
# After:
push @pl_cmds, "\t-$perl $pl $t";
```

This matches how Perl's own MakeMaker handles optional script generation. The
`.pm` files still get installed via `pm_to_blib`, and CPAN.pm sees a successful
`make`.

**Alternative:** Bundle the Pod-Parser `.pm` files directly in
`src/main/perl/lib/` to eliminate the CPAN dependency entirely. This is cleaner
but adds 6 files to the distribution.

**Verification:**
```bash
./jcpan Pod::Parser   # should install cleanly (or be already present)
./jperl -e 'use Pod::PlainText; print "OK\n"'
```

**Effort:** Low — one-character change per line in MakeMaker.pm.

---

#### Phase 2d: Pod::Spell / Encode (low priority, optional)

**Dependency chain:**
```
Perl::Critic → Pod::Spell (optional, only for PodSpelling policy)
  → Encode::encode   ← undefined
```

`Encode::encode` is not implemented in PerlOnJava. This only affects the
`PodSpelling` policy, which most users don't enable.

**Fix:** Skip for now. If needed later, either:
- Implement `Encode::encode` / `Encode::decode` in Java
- Or ship a stub that passes through UTF-8 unchanged

**Effort:** N/A for now.

---

### Phase 2 Implementation Order

The fixes should be applied in this sequence because of dependency ordering:

1. **DynaLoader.pm stub** (2a) — no dependencies, unlocks Module::Build::Tiny → Readonly
2. **XSLoader PP fallback** (2b) — no dependencies, unlocks Params::Util → PPI
3. **MakeMaker PL_FILES** (2c) — no dependencies, unlocks Pod-Parser (fresh installs)
4. Run `make` — verify no regressions
5. Install the dependency chain:
   ```bash
   ./jcpan Module::Build::Tiny   # needs 2a
   ./jcpan Readonly               # needs Module::Build::Tiny
   ./jcpan Params::Util           # needs 2b (or already installed)
   ./jcpan PPI                    # needs Params::Util — biggest risk
   ./jcpan Config::Tiny String::Format
   ./jcpan PPIx::QuoteLike PPIx::Regexp PPIx::Utils
   ./jcpan Pod::Parser            # needs 2c (or force install)
   ```
6. Test PPI independently — it's 15K lines of pure Perl and the biggest risk:
   ```bash
   ./jperl -MPPI -e 'my $doc = PPI::Document->new(\q{print "hello\n"}); print $doc, "\n"'
   ```

### Phase 3: Full Perl::Critic Install (future)

1. `./jcpan Perl::Critic`
2. Run Perl::Critic tests: `./jcpan -t Perl::Critic`
3. Smoke test: `./jperl -MPerl::Critic -e 'print Perl::Critic->VERSION'`

## Progress Tracking

### Current Status: Phase 2 implementation in progress

### Completed Phases
- [x] Investigation (2026-04-07)
  - Identified 3 blockers from `./jcpan Perl::Critic` attempt
  - Mapped full dependency tree (40+ modules)
  - Root-caused `foreach my @array` scoping bug to EmitForeach.java
- [x] Phase 1: Fix Known Blockers (2026-04-07)
  - Added English.pm via dev/import-perl5/sync.pl (v1.12 from perl5/)
  - Fixed foreach my @array scoping in StatementResolver + EmitForeach
  - Build.PL now succeeds; dependency resolution proceeds
  - New Phase 2 blockers discovered: Readonly, Params::Util XS, Pod-Parser
- [x] Phase 2a: DynaLoader.pm stub (2026-04-07)
  - Created `src/main/perl/lib/DynaLoader.pm` stub (matches XSLoader.pm pattern)
  - Set `$DynaLoader::VERSION` in `DynaLoader.java` initialize()
  - Module::Build::Tiny now installs cleanly
- [x] Phase 2b: XSLoader PP-companion detection (2026-04-07)
  - Added `::PP` companion check in `XSLoader.java` before dying
  - Params::Util now loads successfully; PPI works
- [x] Phase 2c: MakeMaker PL_FILES non-fatal (2026-04-07)
  - Prefixed PL_FILES commands with `-` in MakeMaker.pm
- [x] Config.pm: Added `startperl` key (2026-04-07)
  - `$Config{startperl}` was undefined; Module::Build::Tiny's `make_executable()`
    died under `FATAL => 'all'` warnings. Fixed: `startperl => "#!$^X"`
- [x] Fatal.pm / autodie: Imported from perl5/ tree (2026-04-07)
  - Added Fatal.pm, autodie.pm, autodie/*, Tie/RefHash.pm via sync.pl
  - `use Fatal qw(open close)` loads successfully
- [x] Dependency chain installed (2026-04-07)
  - Module::Build::Tiny: installed cleanly
  - Readonly: force-installed (181/188 tests pass; 7 failures in prototype/clone)
  - PPI 1.284: loads and parses code correctly
  - PPIx::QuoteLike, PPIx::Regexp, PPIx::Utilities: force-installed
  - Config::Tiny, String::Format: already installed
- [x] Perl::Critic installed (2026-04-07)
  - Build step failed due to Fatal.pm runtime bug (ClassCastException in
    interpreter when `open` is wrapped by Fatal). Worked around by generating
    `.run.PL` test data files with system perl, then re-running `jperl Build`
    and `jperl Build install`

### Current Blocker: Readonly::Array tied iteration bug

**Symptom:** `use Perl::Critic` fails:
```
Global symbol "$EMPTY" requires explicit package name
  at Perl/Critic/Exception/AggregateConfiguration.pm line 95
```

**Root cause:** Perl::Critic::Utils declares:
```perl
Readonly::Hash our %EXPORT_TAGS => (characters => [qw($EMPTY ...)], ...);
```

Exporter reads `%EXPORT_TAGS` to expand `:characters` into individual symbols.
However, **assigning a Readonly tied array to a regular array returns empty**:

```perl
use Readonly;
Readonly::Array my @arr => qw($FOO $BAR);
print scalar @arr;    # 2  (FETCHSIZE works)
print "@arr";         # "$FOO $BAR"  (string interpolation works)
my @copy = @arr;      # EMPTY!  (list-context flatten is broken)
```

The tied array's `FETCH(index)` and `FETCHSIZE()` methods work, but iterating
in list context (which PerlOnJava does via a different code path than individual
FETCH calls) returns no elements.

**Impact:** All `Readonly::Array` and `Readonly::Hash` values are invisible to
Exporter's tag expansion. This blocks `use Perl::Critic::Utils qw(:characters)`,
which blocks nearly every Perl::Critic module.

**Fix location:** This is a PerlOnJava runtime bug in tied array list-context
iteration. The relevant code is likely in `RuntimeArray.java` — the path that
converts a tied array to a list needs to call `FETCH(0)`, `FETCH(1)`, ...,
`FETCH(FETCHSIZE-1)` rather than reading the underlying storage directly.

**Verification:**
```perl
use Readonly;
Readonly::Array my @arr => qw(a b c);
my @copy = @arr;
print "@copy\n";  # Should print "a b c", currently prints ""
```

### Next Steps
1. Fix Readonly::Array tied iteration in RuntimeArray.java
2. Verify `use Perl::Critic` loads without errors
3. Smoke-test: `./jperl -MPerl::Critic -e 'print Perl::Critic->VERSION'`
4. Test: `echo 'print "hello"' | ./jperl -MPerl::Critic -e '...'`

### Additional Issues Found (not blocking, for later)
- **Fatal.pm runtime ClassCastException**: `use Fatal qw(open)` loads OK but
  calling the wrapped `open` crashes with `InterpretedCode cannot be cast to
  RuntimeScalar` at `InlineOpcodeHandler.java:392`. This blocks Perl::Critic's
  `.run.PL` test data generation but not core functionality.
- **PPIx::QuoteLike**: `(??{...})` recursive regex not supported — Utils.pm
  fails to compile. Module is force-installed; may affect some Perl::Critic
  policies that use it.
- **Readonly test failures**: 7/188 tests fail — prototype checking (`001_assign.t`),
  deep clone (`clone.t`), and read-only modification detection (`readonly.t`)

# `jcpan -t YAML::Any` — Investigation and Fix Plan

## Summary

`jcpan -t YAML::Any` (i.e. `make test` for the `YAML-1.31` distribution) fails
on PerlOnJava in two distinct ways:

1. **Source-filter leak** — a `Filter::Util::Call` filter installed by
   `use Spiffy -Base;` inside `Test::Base.pm` is consumed by a *nested*
   `require Exporter::Heavy` instead of being applied to the rest of
   `Test::Base.pm`.  Result: `Test::Base.pm` fails to compile with
   `syntax error … near "=> [qw"` at line 53, and ~14 of the 30 test
   files (`basic-tests.t`, `bugs-emailed.t`, `bugs-rt.t`, `dump-*.t`,
   `load-*.t`, …) fail with `Compilation failed in require`.

2. **`t/2-scalars.t`: 5/12 sub-tests fail** because PerlOnJava's
   "bundled `YAML.pm`" has output semantics that diverge from
   YAML.pm 1.31 (the version under test).

Of the two, (1) is by far the bigger problem: it's a generic
PerlOnJava bug that breaks **every** module that uses `Spiffy -Base`,
`Switch`, `Filter::Simple`, or any other source filter that installs
during a `use` whose `import()` transitively `require`s another file.

---

## Bug 1: source-filter state leaks across nested compilations

### Symptom

```
$ ./jperl -e 'package Foo; use Spiffy -Base; field _filters => [qw(a b)];'
"my" variable $self masks earlier declaration in same scope at
    jar:PERL5LIB/Exporter/Heavy.pm line 237, near ", $wanted"
syntax error at -e line 1, near "=> [qw"
```

The first warning ("`my $self` masks earlier declaration … `Exporter/Heavy.pm`
line 237") is the smoking gun: Spiffy's filter — which injects
`my $self = shift;` after every `sub …{` — was applied to
`Exporter::Heavy.pm`, not to the user's source.

### Root cause

`Filter::Util::Call`'s Java implementation in
`org.perlonjava.runtime.perlmodule.FilterUtilCall` keeps two pieces of
state in a `ThreadLocal`:

| field | purpose |
|---|---|
| `filterContext.filterStack` | stack of currently-installed source filters |
| `filterInstalledDuringUse`  | one-shot flag set by `real_import()` and consumed by `wasFilterInstalled()` so the parser knows to re-tokenize after a `use` |

The parser uses these in
`StatementParser.applySourceFilterToRemainingTokens()`: after every
`use Foo …;`, it calls `wasFilterInstalled()`, and if true rejoins the
remaining tokens, runs them through the filter chain, re-tokenizes,
and finally calls `clearFilters()`.

Both pieces of state are **process-global per thread** — they are
**not** scoped to the file currently being compiled.  So the following
sequence misbehaves:

```
parse Test::Base.pm
  parse "use Spiffy -Base;"
    Spiffy::import:
      Filter::Util::Call::filter_add(...)         # push filter; flag := true
      Exporter::export(...)
        require Exporter::Heavy.pm                 # nested compilation
          parse "use warnings;" (etc.)
            wasFilterInstalled() == true           # ← consumed here!
            applyFilters(remaining-of-Heavy.pm)    # ← Heavy.pm gets rewritten
            clearFilters()                         # ← stack emptied
  back in Test::Base.pm:
    wasFilterInstalled() == false                  # parent never sees it
    → rest of Test::Base.pm parsed unfiltered
    → "field _filters => [qw(...)]" → syntax error
```

### Minimal repro / proof

```perl
# fails
package Foo;
use Spiffy -Base;
field _filters => [qw(a b)];

# works (Exporter::Heavy already loaded → no nested require)
package Foo;
use Exporter::Heavy;
use Spiffy -Base;
field _filters => [qw(a b)];
```

The only difference is whether `Exporter::Heavy` is loaded *during*
`Spiffy::import` or *before* it.

### Does this match real Perl semantics?

**Yes — and the current behaviour does not.**

Real Perl associates source filters with the *compilation unit*
(`PL_compiling` / `PL_rsfp_filters`).  Each `require`/`do FILE` opens
a new compilation state; the new file gets its own (initially empty)
filter chain.  When the nested compilation finishes, the original
state is restored, including any filters the outer file installed.
That's why on real Perl, Spiffy's filter is applied to the
`*.pm` file in which `use Spiffy -Base;` literally appears, regardless
of what other files Spiffy's import happens to load.

Spiffy itself relies on this: line 82 of `Spiffy.pm` reads

```perl
spiffy_filter()
  if ($args->{-selfless} or $args->{-Base}) and
     not $filtered_files->{(caller($stack_frame))[1]}++;
```

i.e. it asks "have I already filtered *this caller's file*?".  The
filter is intended to be scoped to that file — which is exactly the
semantics PerlOnJava is missing.

### Proposed fix

Treat the filter state as part of the per-compilation-unit context and
save/restore it at every entry to `executePerlCode`.

#### Step 1 — add save/restore primitives to `FilterUtilCall`

```java
public static final class FilterState {
    final RuntimeList stack;
    final boolean installedFlag;
    FilterState(RuntimeList s, boolean f) { stack = s; installedFlag = f; }
}

/** Save current filter state and reset to empty. Returns token to pass back to restore(). */
public static FilterState saveAndReset() {
    FilterContext ctx = filterContext.get();
    FilterState saved = new FilterState(ctx.filterStack, filterInstalledDuringUse.get());
    ctx.filterStack    = new RuntimeList();
    ctx.sourceLines    = null;
    ctx.currentLine    = 0;
    filterInstalledDuringUse.set(false);
    return saved;
}

public static void restore(FilterState saved) {
    FilterContext ctx = filterContext.get();
    ctx.filterStack = saved.stack;
    filterInstalledDuringUse.set(saved.installedFlag);
}
```

#### Step 2 — wrap `executePerlCode`

In `org.perlonjava.app.scriptengine.PerlLanguageProvider.executePerlCode`,
at the very top:

```java
FilterUtilCall.FilterState savedFilterState = FilterUtilCall.saveAndReset();
try {
    // ... existing body ...
} finally {
    FilterUtilCall.restore(savedFilterState);
}
```

`executePerlCode` is the single funnel for every nested compilation
(`require`, `do FILE`, eval-string, the recursive call inside
`preprocessWithBeginFilters`), so this one wrap covers all cases.

#### Step 3 — keep `clearFilters()` in `applySourceFilterToRemainingTokens`

That call still makes sense: once the parent file's filter has been
applied to the remaining tokens, the filter has done its job for that
compilation unit.  After save/restore is in place, clearing only
affects the current (parent) frame, never the caller's frame.

### Does the fix behave exactly like system Perl?

For the cases that matter for `jcpan -t`, **yes**:

- **Filter scoped to outer file.**  Spiffy's filter is applied to the
  rest of `Test::Base.pm` only, never to `Exporter::Heavy.pm`. ✓
- **Nested file gets a clean chain.**  A `require`d file may install
  its own filters (e.g. its own `BEGIN { … filter_add … }`), and
  those apply only to its own remaining source. ✓
- **Outer file's filter survives the nested load.**  When control
  returns from `require Exporter::Heavy`, the parent's filter is
  still active, so the next `wasFilterInstalled()` check fires
  correctly. ✓
- **`BEGIN { … filter_add … }`-style filters** (handled by
  `preprocessWithBeginFilters`) keep working: that path also goes
  through `executePerlCode` for the synthetic compile, so its filter
  installation is scoped to that synthetic compile and doesn't leak.
  ✓

Known small differences from real Perl that the fix does **not**
change (and that don't matter for YAML::Any):

- PerlOnJava applies the filter once to the entire remaining token
  buffer (rejoin → filter → re-tokenize), whereas real Perl drives
  the filter line-by-line through the lexer.  Filters that depend on
  fine-grained interaction with the lexer (very rare; Spiffy doesn't)
  still behave differently.  This is a pre-existing limitation
  documented in `FilterUtilCall.java`.
- `__DATA__` / `__END__` handling is approximated by a regex in
  `applyFilters`; real Perl just stops feeding the filter at the
  end-of-source marker.  Adequate for Spiffy/Switch/Filter::Simple.

The save/restore fix is independent of those limitations and matches
real Perl on the property "a source filter installed by `use Foo;`
applies to the file containing that `use`, and only to that file".

### Test plan

1. Add a regression test under `src/test/resources/unit/`
   that mirrors the minimal repro — `package Foo; use Spiffy -Base; field _filters => [qw(a b)]; ...` — and asserts it compiles
   and runs.
2. `make test-bundled-modules` (covers anything that already exercises
   `Filter::Util::Call`, e.g. `Switch`).
3. Re-run `jcpan -t YAML::Any`.  Expectation: every `t/*.t` that
   currently dies with `syntax error at .../Test/Base.pm line 53,
   near "=> [qw"` now reaches its real test body.  Pre-existing YAML
   semantic failures (Bug 2, below) remain.
4. Spot-check that no previously-passing module regresses:
   - `make` (full unit-test run).
   - `jcpan -t Switch` if available — Switch is the canonical
     `Filter::Util::Call` user.

### Risks / open questions

- **Outer-`use` Spiffy filter when the file has *no* further code.**
  Trivial — `applySourceFilterToRemainingTokens` is a no-op on an
  empty buffer.
- **`eval STRING` inside a filtered file.**  After the fix, the eval
  starts with an empty filter chain (just like real Perl, where the
  string-eval gets its own compilation unit and inherits no source
  filter from the surrounding file).  This is the correct behaviour
  but it's a small change for any caller that currently happens to
  inherit a leaked filter.  None known.
- **Threading.**  `filterContext` and `filterInstalledDuringUse` are
  already `ThreadLocal`, so the save/restore is per-thread.  Fine.

---

## Bug 2: `YAML.pm` semantics differ from upstream YAML 1.31

### What's actually bundled

There is **no** Perl `YAML.pm` shipped that mirrors the real
`YAML-1.31` distribution.  Instead PerlOnJava ships:

| File | Role |
|---|---|
| `src/main/java/org/perlonjava/runtime/perlmodule/YAMLPP.java` | Java implementation of `YAML::PP` built on top of `org.snakeyaml.engine.v2`. Registers `new`, `load_string`, `load_file`, `dump_string`, `dump_file`. |
| `src/main/perl/lib/YAML.pm` | 12-line **Perl wrapper** that re-exports `Load`/`Dump`/`LoadFile`/`DumpFile` from `YAML::PP` and sets `$YAML::VERSION = '1.31'`. |

So "bundled YAML" is **YAML::PP (Java) wrapped by a tiny Perl
shim that calls itself `YAML 1.31`**.  Functionally adequate for
*using* YAML, but the shim impersonates `YAML.pm`'s version number
without matching its serialization quirks.

### Test failures in `t/2-scalars.t`

```
got: '--- null\n'        expected: '--- ~\n'
got: '1'                 expected: 'true'
got: ''                  expected: 'false'
error: 'while scanning a quoted scalar … found unexpected end of stream'
                         expected: 'Can't parse single' / 'Can't parse double'
```

These all stem from snakeyaml-engine emitting **JSON / YAML 1.2**
syntax (`null`, `true`, `false`) and producing libyaml-style error
messages, whereas YAML.pm 1.0/1.1 emits `~` for undef, `true`/`false`
as plain strings, and dies with the classic `Can't parse single
quoted string` / `Can't parse double quoted string` messages.

### Concrete behavioural deltas

Probed on the current build:

| input | YAML.pm 1.31 expects | bundled `YAML.pm` (= `YAML::PP`) returns |
|---|---|---|
| `Dump(undef)`           | `--- ~\n`          | `--- null\n` |
| `Load("--- true\n")`    | string `"true"`    | boolean true → stringifies to `"1"` |
| `Load("--- false\n")`   | string `"false"`   | boolean false → stringifies to `""` |
| malformed `'…\n…`       | error matches `Can't parse single` | `while scanning a quoted scalar … found unexpected end of stream` |
| malformed `"…\n…`       | error matches `Can't parse double` | same as above |

Five mismatches, all in `t/2-scalars.t` — exactly the 5 failures we
observed.  Everything else (`Dump(42)`, `Load("--- 42\n")`, `Load("--- ~\n")`,
the round-trip on a hash, the giant-string round-trip) already works.

### Recommended fix

Surgical, contained in the **YAML.pm shim only** — the Java
`YAML::PP` keeps emitting faithful YAML 1.2 output for direct
`use YAML::PP;` consumers, and we add a thin "yaml-pm compat"
post/pre-processing layer on top.

#### Change `src/main/perl/lib/YAML.pm`

Replace the current 12-line wrapper with something like:

```perl
package YAML;
use strict;
use warnings;
use YAML::PP;
use Scalar::Util qw(blessed reftype);
use Exporter 'import';

our @EXPORT    = qw(Load Dump);
our @EXPORT_OK = qw(LoadFile DumpFile freeze thaw);
our $VERSION  = '1.31';

my $YPP = YAML::PP->new;   # Core schema, indent 2 — matches YAML.pm defaults

# ----- Dump: post-process so undef serialises as `~` --------------------
sub _undef_to_tilde {
    # YAML.pm 1.x emits `~` for undef; YAML::PP emits `null`.
    # Only replace `null` when it appears as a YAML scalar token
    # (after `: `, `- `, `--- `), never inside quoted strings.
    my $s = shift;
    $s =~ s/^(\s*-\s+)null$/$1~/mg;             # sequence item
    $s =~ s/^(\s*[^"\s][^"]*?:\s+)null$/$1~/mg; # mapping value
    $s =~ s/^(---\s+)null$/$1~/mg;              # top-level scalar
    $s;
}

sub Dump     { _undef_to_tilde($YPP->dump_string(@_))      }
sub DumpFile { my $f = shift; _spew($f, Dump(@_))          }

# ----- Load: stringify booleans, rewrite parser errors ------------------
sub _stringify_bools {
    my $node = shift;
    if (blessed($node) && $node->isa('JSON::PP::Boolean')) {
        return $node ? 'true' : 'false';
    }
    my $rt = ref $node ? reftype($node) : '';
    if    ($rt eq 'HASH')  { _stringify_bools(\$_) for values %$node }
    elsif ($rt eq 'ARRAY') { _stringify_bools(\$_) for @$node }
    elsif ($rt eq 'SCALAR' || $rt eq 'REF') { ... }
    return $node;
}

sub Load {
    my ($yaml) = @_;
    my @docs = eval { $YPP->load_string($yaml) };
    if (my $e = $@) {
        # Translate snakeyaml-engine messages into YAML.pm-style errors.
        if ($e =~ /scanning a quoted scalar/ && $e =~ /unexpected end/) {
            # Inspect the quoting style of the offending line.
            my $kind = ($yaml =~ /:\s*'/) ? 'single'
                     : ($yaml =~ /:\s*"/) ? 'double'
                     : 'quoted';
            die "Can't parse $kind quoted string\n";
        }
        die $e;
    }
    @docs = map { _bool_to_string($_) } @docs;
    return wantarray ? @docs : $docs[0];
}
sub LoadFile { Load(_slurp(shift)) }

*freeze = \&Dump;
*thaw   = \&Load;

1;
```

(The above is illustrative — the actual patch needs the full
`_bool_to_string` walker and proper `_slurp` / `_spew` helpers; see
the existing 12-line file for the entry-point signature contract.)

#### What changes for direct `YAML::PP` users

Nothing.  `YAML::PP->load_string`/`dump_string` remain
JSON-flavoured (YAML 1.2): `null`, `true`/`false` as
`JSON::PP::Boolean`, snakeyaml error messages.  Only the `YAML::*`
entry points get the compat layer.

#### Why post-process instead of changing `YAMLPP.java`?

- `YAML::PP` on real Perl already emits `null` and JSON-style
  booleans — it's the YAML 1.2 schema, not a PerlOnJava bug.  Modules
  that explicitly use `YAML::PP` expect that.
- Adding a "YAML.pm compat" mode to the Java side would either:
  (a) require a new schema in `YAMLPP.java`, ~50 lines of
  resolver/representer plumbing, or
  (b) add a per-instance flag that the load/dump paths inspect — a
  pile of conditional code in hot paths.
  Doing it in the 12-line Perl shim keeps the fix in **one** file
  and 100 % out of the JVM hot path.
- The compat layer is the kind of thing real `YAML::Any` does too
  when it picks a backend.

### Does this fix match real YAML.pm 1.31 semantics?

For `t/2-scalars.t` and the typical `use YAML;` consumer, **yes**:

- `Dump(undef)` → `--- ~\n` ✓
- `Load("--- ~\n")`, `Load("---\n")`, `Load("--- ''\n")` already work ✓
- `Load("--- true\n")`, `Load("--- false\n")` → strings `"true"`/`"false"` ✓
- malformed quoted strings → die with `Can't parse single`/`Can't parse double` ✓
- numbers, strings, refs, hashes, arrays — unchanged from current
  (already passing) behaviour ✓

Caveats not addressed by this fix (and not exercised by the test
suite):

- YAML.pm 1.x emits some scalar-styling decisions (line wrapping
  thresholds, when to single-quote vs plain) that YAML::PP differs
  on.  Out of scope; nothing in `t/2-scalars.t` tests it.
- YAML.pm 1.x has its own anchor/alias numbering scheme (`&1`, `*1`)
  whereas YAML::PP uses `&a001`.  Already different, no regression.
- Other YAML modules that depend on YAML.pm's error message text
  (e.g. `YAML::Tiny`-derived test suites) may need similar
  translations; address case-by-case.

---

## Path to making **all** YAML-1.31 tests pass

The fixes above unblock `t/2-scalars.t` and the ~14 test files that
currently die with `Compilation failed in require` because of the
Spiffy filter leak.  Getting **every** one of the 54 `t/*.t` files
green is a different conversation: the test suite was written against
one specific Perl implementation (`YAML.pm` 1.x) and probes its
*exact* output, error text, anchor numbering, blessing protocol,
B::Deparse code dumping, etc.  PerlOnJava ships a YAML 1.2 engine
under that name, so a small wrapper can never satisfy all of
those tests.

Realistically, three approaches scale to "all tests pass":

### Approach A — install the *real* `YAML.pm` from CPAN (recommended)

`YAML-1.31` is **pure Perl** (only deps: `YAML::Mo` — bundled with
the dist — `Scalar::Util`, `B`).  No XS.  Its tests are written to
exercise its own implementation.  The clean path is:

1. Land the **Spiffy filter-leak fix** (Bug 1) so `Test::Base.pm`
   compiles.  Without this, no YAML test even starts.
2. **Stop bundling `YAML.pm`** as an unconditional shim.  Two
   sub-options:
   - **A1.** Remove `src/main/perl/lib/YAML.pm` from the JAR
     entirely.  Users who want `Load`/`Dump` either `use YAML::PP;`
     (Java-backed, works) or run `cpan YAML` (real Perl, works).
   - **A2.** Keep the shim as a *fallback*: change the
     `MakeMaker.pm` SKIP logic at line 357 to allow `YAML.pm`,
     `YAML/Loader*.pm`, `YAML/Dumper*.pm`, `YAML/Mo.pm`, etc. to be
     overwritten by the CPAN version.  A1 is cleaner; A2 keeps the
     "out of the box `use YAML;` works" property.
3. Run `jcpan -t YAML`.  Tests now run against real `YAML.pm` 1.31.
4. Triage the residual failures.  Real `YAML.pm` exercises a fair
   amount of Perl machinery that can hit PerlOnJava limitations:
   - `B::Deparse` (for `Dump` of code refs — `dump-code.t`,
     `load-code.t`)
   - Glob serialisation (`dump-blessed-glob.t`)
   - Tied hashes / iterating restored objects
     (`pugs-objects.t`, `marshall.t`)
   - `${\ \my $x}` style refs of refs, `weaken`-tracked cycles
     (`references.t`, `bugs-rt.t`)
   - Regex stringification for `qr//` (`regexp.t`)
   - Numification edge cases (`numify.t`, `dump-stringy-numbers.t`)
   - `local $/` slurp + UTF-8 BOM handling
     (`dump-file-utf8.t`, `io-handle.t`)
   - Symbol-table magic in `YAML::Mo` (`*{$M.'Object::new'} = sub{...}`,
     `${$P.':E'}`, etc.)
5. Each residual failure becomes one of:
   - a real PerlOnJava bug (fix it — likely small per failure),
   - an unsupportable feature (e.g. `DESTROY`-based teardown in a
     test — skip with `SKIP` block, document in this file),
   - a YAML.pm bug already known upstream (skip).

Cost: medium.  Most fixes are one-liners or small parser/runtime
patches.  Reward: future YAML.pm-derived tests "just work".

### Approach B — make `YAML::PP` faithful to YAML.pm 1.x in the shim

Extend the YAML.pm shim (the strategy in this document so far) to
cover **everything** the test suite checks, not just the 5 mismatches
in `t/2-scalars.t`.  In practice that means re-implementing
YAML.pm's output format on top of `YAML::PP`'s parser/emitter:

- A custom Dumper that emits `~`, plain `true`/`false`,
  YAML.pm-style anchors `&1`/`*1`, YAML.pm's quoting heuristics
  (when to single-quote, when to fold), YAML.pm's `Indent`/`UseHeader`/
  `UseAliases`/`UseBlock`/`UseFold`/`SortKeys` semantics.
- A custom Loader that respects `$YAML::LoadCode`,
  `$YAML::LoadBlessed`, the "perl/code", "perl/glob", "perl/regexp",
  "perl/hash:Class" tag families used by `Marshall`/`Bless`.
- Error-message translation table for every snakeyaml exception that
  the test suite pattern-matches (`load-fails.t`, `errors.t`).
- B::Deparse-based code-ref serialisation for `dump-code.t`.
- `YAML::Node`/`YAML::Tag` shims so tests that introspect node info
  (`node-info.t`, `preserve.t`) see the right object structure.

Cost: high.  Probably 1000–2000 lines of compatibility Perl plus
non-trivial Java work in `YAMLPP.java` (custom representer/resolver
chains, listener for raw-token style preservation).  Reward: a
single self-contained shim with no CPAN bootstrap dependency.
Downside: every future YAML.pm release re-creates the diff.

### Approach C — port real `YAML.pm` 1.31 into the bundled JAR

Replace the 12-line shim with the actual `YAML.pm` / `YAML/Loader.pm`
/ `YAML/Dumper.pm` / `YAML/Mo.pm` / `YAML/Marshall.pm` /
`YAML/Node.pm` / `YAML/Types.pm` / `YAML/Error.pm` / `YAML/Tag.pm`
files, vendored under `src/main/perl/lib/`.  Do NOT load `YAML::PP`
from `YAML`.

Same end state as A but the user doesn't need to run `cpan YAML` to
get YAML.pm semantics.  Mechanics are identical to A from then on —
the same residual triage list, the same per-failure PerlOnJava bug
fixes.

Cost: low to vendor + same triage work as A.  Reward: matches
upstream out of the box.  Risk: one more pure-Perl module that we
re-sync on each upstream release.

### Recommendation

Approach **A2** (or **C**, which is materially the same thing
delivered as a vendor copy).  Concrete sequence:

1. **Land the source-filter save/restore fix** in
   `PerlLanguageProvider.executePerlCode`.  This alone unblocks the
   bulk of the suite.
2. **Whitelist** the YAML distribution's `.pm` files in
   `MakeMaker.pm`'s SKIP logic so `cpan YAML` can install over the
   shim.  Or vendor real `YAML.pm` (Approach C).
3. **Run** `jcpan -t YAML` and capture the new failure set in this
   document.  Each remaining failure becomes its own bullet here
   plus, where applicable, a small ticket / PR.
4. **Stop using the wrapper for tests.**  When PerlOnJava sees a
   distribution whose own name is `YAML`, it should use the
   user-installed real `YAML.pm`, not the bundled shim — the test
   harness should test what's about to be released, not what
   PerlOnJava ships.

Approach B is reserved for the case where bootstrapping CPAN-`YAML`
is itself a hard problem.  Today it isn't: `cpan YAML` works on
PerlOnJava once Bug 1 is fixed (its only real dependency is its own
`YAML::Mo`, vendored inside the dist).

### What "all tests pass" probably looks like in practice

After steps 1–3 above, an honest expectation is:

| outcome | rough % of files | what to do |
|---|---|---|
| pass cleanly                             | ~60–75% | nothing |
| pass after a small PerlOnJava fix        | ~15–25% | file as targeted bugs (regex/B::Deparse/etc.) |
| skip because of `fork`/`threads`/DESTROY | ~5%     | `SKIP` block, document |
| genuine YAML.pm-1.x quirks too costly to match | <5% | document as known mismatch |

There is no single PR that lands "all YAML tests pass".  But there
is a well-defined sequence — Spiffy fix → switch to real YAML.pm →
triage residue — that converges on it.

---

## Suggested order of work

1. **Fix Bug 1** (source-filter save/restore in `executePerlCode`).
   This is the high-leverage change: it unblocks YAML::Any, all of
   `Switch`, all of `Filter::Simple`, and any future `Spiffy`-based
   module — for a ~30-line change in one file.
2. Re-run `jcpan -t YAML::Any` and record the new pass/fail set.
3. **Pick an option for Bug 2** based on what's still failing after
   step 2 and how much real YAML.pm-1.31 PerlOnJava can tolerate.
4. Add a unit-test regression test under `src/test/resources/unit/`
   for the Spiffy/source-filter scenario so this exact failure mode
   can't silently come back.

## References

- `src/main/java/org/perlonjava/runtime/perlmodule/FilterUtilCall.java`
- `src/main/java/org/perlonjava/frontend/parser/StatementParser.java`
  (`applySourceFilterToRemainingTokens`, ~line 1290)
- `src/main/java/org/perlonjava/app/scriptengine/PerlLanguageProvider.java`
  (`executePerlCode`, ~line 71)
- `src/main/java/org/perlonjava/runtime/perlmodule/YAMLPP.java`
- `src/main/perl/lib/YAML.pm`
- `~/.cpan/build/Net-IPv4Addr-0.10-1` — unrelated, but the previous
  `jcpan -t Net::IPv4Addr` investigation lives in the session log.

## Progress Tracking

### Current Status: Bug 1 + Bug 2 fixed; long-tail residue remains

### Completed Phases

- [x] **Bug 1 — source-filter state save/restore** (2026-04-28)
  - Added `FilterUtilCall.saveAndReset()` / `restore(FilterState)`
    so the per-thread filter stack and the `filterInstalledDuringUse`
    flag are scoped to a compilation unit instead of leaking across
    nested `require`/`do FILE`/string-`eval`.
  - Wired into `PerlLanguageProvider.executePerlCode` via an outer
    `try { … } finally { FilterUtilCall.restore(savedFilterState); }`.
  - Regression test: `src/test/resources/unit/source_filter_scope.t`
    (Spiffy `field …` minimal repro + `use Test::Base` smoke test).
  - Effect on the YAML-1.31 distribution: 27 of the 35 previously
    blocked test files now pass cleanly.  All 34 tests added to
    `src/test/resources/module/YAML/t/` are green under
    `make test-bundled-modules`.

- [x] **Bug 2 — vendor real YAML.pm 1.31** (2026-04-28)
  - Replaced `src/main/perl/lib/YAML.pm` (the 12-line YAML::PP shim)
    with the actual pure-Perl `YAML.pm` 1.31 distribution.
  - Added `src/main/perl/lib/YAML/{Any,Dumper,Error,Loader,Marshall,
    Mo,Node,Tag,Types}.pm` plus `YAML/Loader/Base.pm` and
    `YAML/Dumper/Base.pm`.
  - Kept `src/main/java/.../YAMLPP.java` and `src/main/perl/lib/YAML/PP.pm`
    untouched — `use YAML::PP;` (Java-backed) is a separately
    documented bundled module with its own unit test (`yaml_pp.t`).
  - `make` is green.
  - Verified directly against the YAML-1.31 distribution's tests
    (Test::Base-using tests still blocked on Bug 1):

    | test                  | before  | after   |
    |-----------------------|---------|---------|
    | `t/000-compile-modules.t` | n/a (couldn't run) | 12/12 |
    | `t/2-scalars.t`       | 7/12    | 12/12 |
    | `t/dump-synopsis.t`   | n/a     | 1/1   |
    | `t/issue-149.t`       | n/a     | 1/1   |
    | `t/issue-69.t`        | n/a     | 2/2   |
    | `t/numify.t`          | n/a     | 6/6   |
    | `t/roundtrip.t`       | n/a     | 1/1   |
    | `t/rt-90593.t`        | n/a     | 2/2   |
    | `t/preserve.t`        | n/a     | 0/1   ← real PerlOnJava issue, see below |

    35 of the remaining 54 files still die at compile time with
    `syntax error at .../Test/Base.pm line 53, near "=> [qw"` —
    that's exactly the Spiffy filter-leak (Bug 1).

### Next Steps

1. **Land Bug 1 (Spiffy filter leak).**  Implement
   `FilterUtilCall.saveAndReset()` / `restore()` and wire them into
   `PerlLanguageProvider.executePerlCode`.  Add a regression test
   under `src/test/resources/unit/` mirroring the Spiffy minimal
   repro (`package Foo; use Spiffy -Base; field _filters => [qw(a b)];`).
   This single change should unblock all 35 currently-blocked
   `t/*.t` files.
2. After Bug 1 lands, re-run the full YAML test sweep and triage
   the residual failures.  Expected long-tail issues:
   - `t/preserve.t` — `Preserve` option: hash key ordering. PerlOnJava
     hashes are not insertion-ordered by default, so the round-trip
     produces sorted output instead of the original key order.
     Either fix in `RuntimeHash` (preserve insertion order) or skip.
   - `t/dump-code.t` / `t/load-code.t` — B::Deparse round-trips for
     code refs.
   - `t/dump-blessed-glob.t` — glob serialisation.
   - `t/regexp.t` — `qr//` stringification differences.
   - `t/dump-file-utf8.t` / `t/io-handle.t` — UTF-8 / BOM handling.
3. Document any tests that hit unsupportable Perl features
   (`fork`, `threads`, deterministic `DESTROY`) with `SKIP` blocks
   plus a note in this file.

### Open Questions

- `t/2-scalars.t` test 10 (a 600 KB string round-trip through real
  YAML.pm) takes ~99 s on PerlOnJava — almost all of it inside
  YAML::Loader's regex-driven parser.  Acceptable for `make test`
  but worth profiling later.
- Is there any module that *intentionally* relies on the current
  filter leak?  None known on CPAN, but worth a sanity sweep on the
  bundled-modules suite once the fix is in.

### Why YAMLPP.java was kept (not removed)

`YAML::PP` is a separately bundled module:

- documented in `docs/reference/bundled-modules.md` and
  `docs/reference/feature-matrix.md`,
- has a dedicated unit test (`src/test/resources/unit/yaml_pp.t`),
- exposes a different API surface (`YAML::PP->new(schema => …)`,
  `cyclic_refs`, `boolean`, …) that real CPAN consumers expect.

Direct callers of `use YAML::PP;` would regress if we deleted it.
`Storable.java` already uses `org.snakeyaml.engine` directly and
doesn't go through `YAMLPP.java`, so it's unaffected either way —
but `YAML::PP` itself remains a valid bundled module on its own
merits.

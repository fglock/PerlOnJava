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

## Implementation Plan

### Phase 1: Fix Known Blockers (this PR)

1. **Add English.pm** to `src/main/perl/lib/English.pm`
   - Copy from system Perl (v1.11, pure Perl, 237 lines)
   - Test: `./jperl -e 'use English qw(-no_match_vars); print "$PID\n"'`
   - Test: `./jperl -e 'use English; "hello" =~ /ell/; print "$MATCH\n"'`

2. **Fix `foreach my @array` scoping** in `EmitForeach.emitFor1()`
   - Move list evaluation before `enterScope()`
   - Store RuntimeList in a temp local variable
   - Create iterator from stored result after scope entry
   - Verify: `./jperl -e 'sub t { $_++ foreach my @v = @_; print "@v\n" } t(1,2,3)'`
     should print `2 3 4`

3. **Run `make`** to ensure no regressions

### Phase 2: Install and Test Dependencies (future)

1. Install PPI: `./jcpan PPI` — fix any issues
2. Install remaining deps: `./jcpan Config::Tiny Readonly String::Format`
3. Install PPIx modules: `./jcpan PPIx::QuoteLike PPIx::Regexp PPIx::Utils`
4. Install Pod-Parser: `./jcpan Pod::Parser`

### Phase 3: Full Perl::Critic Install (future)

1. `./jcpan Perl::Critic`
2. Run Perl::Critic tests: `./jcpan -t Perl::Critic`
3. Smoke test: `./jperl -MPerl::Critic -e 'print Perl::Critic->VERSION'`

## Progress Tracking

### Current Status: Phase 1 in progress

### Completed Phases
- [x] Investigation (2026-04-07)
  - Identified 3 blockers from `./jcpan Perl::Critic` attempt
  - Mapped full dependency tree (40+ modules)
  - Root-caused `foreach my @array` scoping bug to EmitForeach.java

### Next Steps
1. Add English.pm
2. Fix foreach scoping bug
3. Run make, create PR

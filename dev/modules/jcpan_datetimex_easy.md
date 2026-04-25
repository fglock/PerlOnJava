# jcpan DateTimeX::Easy Fix Plan

## Overview

Running `jcpan -t DateTimeX::Easy` fails because `DateTimeX::Easy` depends
(transitively) on three CPAN dists whose tests fail under PerlOnJava. The
`DateTimeX::Easy` distribution itself is fine — its `t/00-load.t` only fails
because `DateTime::Format::Natural` never finishes installing.

```
DateTimeX::Easy
├── DateTime::Format::DateManip   ← FAILS  (Issue A: regex translator)
├── DateTime::Format::ICal         ← passes
├── DateTime::Set                  ← passes
└── DateTime::Format::Natural      ← FAILS  (Issues B + cascade)
    ├── Module::Util               ← FAILS  (Issue C: stray warning)
    └── Test::MockTime::HiRes      ← FAILS  (Issue D: sleep override)
```

This document tracks all three root-cause issues and the plan to fix them.

---

## Issue A — Duplicate named capture groups in alternation

### Symptom

`DateTime::Format::DateManip` test `t/01conversions.t` exits 255 with no
subtests run because `Date::Manip`'s regex fails to compile:

```
Regex compilation failed: Named capturing group <y> is already defined near index 186
```

Reproducer:

```sh
$ ./jperl -e 'qr/(?<y>foo)|(?<y>bar)/'
Regex compilation failed: Named capturing group <y> is already defined
```

### Root cause

Date::Manip generates patterns like:

```
(?:(?<y>[-+]?\d+(?:\.\d*)?|\.\d+)|(?<y>twenty-one|two|...))
```

A single named capture used in alternation branches. Perl 5.10+ accepts this
implicitly (one logical group, two textual occurrences). Java's regex engine
always rejects duplicate names.

### Fix plan

Detect the duplicate-name case in the regex translator
(`src/main/java/org/perlonjava/regex/`) and emit a Java-acceptable form that
still produces the right `%+` / `%-` matches. Two viable approaches:

1. **Rename + capture map**: rewrite the second occurrence to a unique synthetic
   name (e.g. `__pjdup0_y`) and remember the alias so `%+`/`%-`/`$+{name}`
   merges all branches under the original name.
2. **Branch reset**: where the duplicates sit at sibling alternation branches,
   wrap them in `(?|...)`. Simpler when applicable but doesn't cover every
   shape Date::Manip produces, so option 1 is the reliable target.

Tests to add: a unit test in `src/test/resources/unit/` covering
`(?<x>foo)|(?<x>bar)` matching either branch, and a multi-name case.

### Status

Pending — biggest effort of the three.

---

## Issue B — `t/11-parse_success.t` failures in DateTime::Format::Natural

### Symptom

5/19 subtests fail; phrases ending in implicit hour are not parsed:
`feb 28 at 3`, `28 feb at 3`, `may 22nd 2011 at 9`, `22nd may 2011 at 9`,
`saturday 3 months ago at 5`. Accompanied by:
`Use of uninitialized value in hash element at .../DateTime/Format/Natural/Lang/EN.pm line 251`.

### Status

Out of scope for this iteration — needs a small repro before deciding root
cause (could be lookbehind/lookahead semantics, hash-key coercion, or
something else). Tracked here for follow-up; not a blocker for installing
`DateTimeX::Easy`.

---

## Issue C — Spurious `Can't stat` warning in `Module::Util`

### Symptom

`Module-Util-1.09 t/01..module.t` test 44 fails:

```
not ok 44 - no warnings generated when searching in missing path
# Can't stat /Users/.../fake/path: No such file or directory
```

The test does:

```perl
local $SIG{__WARN__} = sub { push @warnings, @_ };
find_in_namespace('', catdir(qw( fake path )));
ok !@warnings, 'no warnings generated when searching in missing path';
```

Real Perl is silent when `find_in_namespace` is given a non-existent path.
PerlOnJava emits a `Can't stat` warning.

### Fix plan

`Module::Util::find_in_namespace` ultimately calls `File::Find::find` /
`File::Find::finddepth`. The warning comes from PerlOnJava's `File::Find`
(or one level deeper, `opendir`/`stat`). Reproduce:

```sh
$ ./jperl -e 'use File::Find; find(sub {}, "/no/such/dir")'
```

Compare with system perl:

```sh
$ perl -e 'use File::Find; find(sub {}, "/no/such/dir")'
```

If real Perl is silent and we warn, locate the warning source in our
`File::Find` (`src/main/perl/lib/File/Find.pm`) or in the underlying
`opendir`/`stat` operator and either:

- guard the `Can't stat` warning behind `$^W`/`use warnings`-style check
  matching real Perl's behaviour, or
- skip the warning when the path does not exist (real Perl's `File::Find`
  silently skips non-existent top-level dirs by default; only `no_chdir`
  variants warn, controlled by `$File::Find::dont_use_nlink` etc.).

Add a unit test asserting silence for `find(sub {}, "/no/such/path")`.

### Status

Pending.

---

## Issue D — `*CORE::GLOBAL::sleep` override is not honored

### Symptom

`Test::MockTime::HiRes` fails 7 subtests across `t/01_core.t`, `t/02_hires.t`,
`t/03_anyevent.t` because `mock_time { ... } $now;` cannot intercept `sleep`
to advance the mocked clock without actually waiting.

Reproducer:

```sh
$ ./jperl -e 'BEGIN { *CORE::GLOBAL::sleep = sub { print "mocked $_[0]\n" } }
              sleep 2; print "done\n"'
done                       # waits 2s; should print "mocked 2" then "done" instantly
```

`*CORE::GLOBAL::time` is honored ✅; `*CORE::GLOBAL::sleep` is not ❌. Same
applies to `Time::HiRes::sleep` — note the **plain symbol-table override**
(`*Time::HiRes::sleep = sub { ... }`) **does** work; the test failures involve
`CORE::GLOBAL::sleep`.

### Fix plan

Find where `time` looks up its `CORE::GLOBAL::*` override and replicate it for
`sleep`, plus `usleep`/`nanosleep`/`gettimeofday` if needed by the test suite.

Search: `grep -rn "CORE::GLOBAL" src/main/java/org/perlonjava/operators/`
and `grep -rn "\"sleep\"" src/main/java/org/perlonjava/`.

### Status

Pending.

---

## Issue E — DateTimeX::Easy `t/00-load.t` cascade

Pure consequence of Issues A and B preventing `DateTime::Format::Natural`
from being installed. No direct work item.

---

## Implementation order

1. **Issue D** (CORE::GLOBAL::sleep) — small, contained, unblocks
   Test::MockTime::HiRes immediately.
2. **Issue C** (silent missing-path) — small, unblocks Module::Util.
3. **Issue A** (duplicate named captures) — larger; unblocks Date::Manip and
   many other CPAN modules.
4. **Issue B** (Natural parse failures) — defer; investigate after A is done
   so the dependency chain is healthy.

Each fix lands in its own commit on a feature branch so it can be reviewed
independently.

## Progress Tracking

### Current Status: COMPLETE — `jcpan -t DateTimeX::Easy` exits 0

### Completed Phases
- [x] Plan written (2026-04-25)
- [x] Issue D (`CORE::GLOBAL::sleep`) fixed (2026-04-25)
  - `src/main/java/org/perlonjava/frontend/parser/ParserTables.java`: added `sleep` to `OVERRIDABLE_OP`.
  - Test: `src/test/resources/unit/operator_overrides.t` — new "sleep operator override" subtest.
- [x] Issue C (silent missing-path) fixed (2026-04-25)
  - `src/main/java/org/perlonjava/frontend/parser/SpecialBlockParser.java`: emit a `CompilerFlagNode` after a BEGIN block so `BEGIN { unimport warnings ... }` propagates the runtime warning scope (`local ${^WARNING_SCOPE} = N`) the same way `parseUseDeclaration` does for `use`/`no` declarations.
  - Test: `src/test/resources/unit/warnings.t` — new test covering the `File::Find` use case.
- [x] Issue A (duplicate named captures) fixed (2026-04-25)
  - `src/main/java/org/perlonjava/runtime/regex/RegexPreprocessor.java`: track named captures already emitted in the current pattern and suffix subsequent occurrences with `ZpjdupZ<N>`. Also skip name text inside `(?<name>` / `(?'name'` / `(?P<name>` / `(?P=name)` when applying multi-char fold expansion (otherwise `(?<off>...)/i` becomes `(?<o(?:ff|ﬁ)>...)`).
  - `src/main/java/org/perlonjava/runtime/regex/CaptureNameEncoder.java`: `decodeGroupName` strips the duplicate marker; new `stripDuplicateMarker`/`isDuplicateMarkerName` helpers.
  - `src/main/java/org/perlonjava/runtime/runtimetypes/HashSpecialVariable.java`: group duplicate captures by decoded Perl name in `entrySet`/`get`/`containsKey` so `$+{name}` returns the matched alternative and `$-{name}` returns an arrayref of all alternatives.
  - Test: `src/test/resources/unit/regex/regex_named_capture.t` — new test cases 10 & 11.
- [x] Issue B (`DateTime::Format::Natural` parse_success) fixed (2026-04-25)
  - `src/main/java/org/perlonjava/runtime/regex/RuntimeRegex.java`: `replaceRegex()` now honors `pos()` when the pattern uses `\G`, so `s/\G/.../` after a previous `/g` match anchors at the previous match-end instead of offset 0. This is what `DateTime::Format::Natural::Rewrite::_rewrite_conditional` relies on to turn "feb 28 at 3" into "feb 28 at 3:00".
  - Test: `src/test/resources/unit/regex/regex_g_pos.t` — new "\\G in s/// honors pos()" subtest.
- [x] `Time::HiRes::gettimeofday` scalar context (2026-04-25)
  - `src/main/java/org/perlonjava/runtime/perlmodule/TimeHiRes.java`: scalar/void context returns `seconds + micros/1_000_000` instead of just microseconds.
- [x] `Time::Piece->strptime` lenient parsing (2026-04-25)
  - `src/main/java/org/perlonjava/runtime/perlmodule/TimePiece.java`: switched to `DateTimeFormatterBuilder.parseLenient().appendPattern(...)` so non-zero-padded fields (e.g. "1:13:8" against `%H:%M:%S`) parse successfully.

### Verification

`jcpan -t DateTimeX::Easy` exit code: **0**. Per-distribution results:

| Dist | Tests | Result |
|---|---|---|
| DateTime::Format::DateManip | 7 | PASS |
| DateTime::Set | 959 | PASS |
| Module::Util | 47 | PASS |
| Test::MockTime::HiRes | 216 | PASS |
| DateTime::Format::Natural | 11077 | PASS |
| DateTimeX::Easy | 107 | PASS |

`make` (full build + parallel unit tests) is green on the feature branch.

### Notes / Possible follow-ups
- All seven items above land on the same feature branch / single PR; consider splitting if reviewers prefer smaller commits per fix (each is already its own commit).
- The duplicate-name marker `ZpjdupZ` chosen for capture names is unlikely but theoretically collidable with a user name ending in `ZpjdupZ\d+`. If that ever becomes a concern, the encoder could escape literal `ZpjdupZ` sequences before suffixing — analogous to the existing `U95` underscore round-trip.

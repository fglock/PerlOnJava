# jcpan -t FormValidator::Simple::Plugin::Number::Phone::JP — Fix Plan

## Overview

This document tracks the problems uncovered while running

```
jcpan -t FormValidator::Simple::Plugin::Number::Phone::JP
```

and the plan to make the test target pass under PerlOnJava.

The leaf module's own code is trivial (~15 lines that wrap
`Number::Phone::JP->new($data)->is_valid_number`). All real
problems live in its transitive dependency graph.

## Dependency chain

```
FormValidator::Simple::Plugin::Number::Phone::JP   (MIZZY/0.04)
└── Number::Phone::JP                              (TANIGUCHI/0.20190521)
    └── parent: Number::Phone                       (DCANTRELL/4.0010)
        ├── Data::CompactReadonly  v0.1.0           ← FAILS Makefile.PL
        ├── I18N::LangTags         (any)            ← treated as core
        └── I18N::LangTags::Detect (any)            ← treated as core
```

Test entry point: `t/01_number_phone_jp.t` exercises 26 phone numbers
via `FormValidator::Simple->check`. The plugin only ever calls
`Number::Phone::JP->new` / `->is_valid_number`, but `Number::Phone::JP`
inherits from `Number::Phone`, so loading it requires the whole
Number::Phone bootstrap (which is what pulls in `Data::CompactReadonly`
and the I18N pair).

## Error Categories

### 1. CRITICAL: Data::CompactReadonly fails Makefile.PL ("OS unsupported")

**Root cause**: `Data-CompactReadonly-0.1.1/Makefile.PL` gates on a
64-bit-Perl-only check:

```perl
# 32 bit ints aren't supported (that's 0xffffffffffffffff)
die("OS unsupported\n") if(~0 < 18446744073709551615);
```

On real 64-bit Perl, `~0 == 18446744073709551615`. Under jperl:

```
$ ./jperl -e 'printf "~0=%s\n", ~0'
~0=4294967295
```

This is intentional in PerlOnJava today:

- `BitwiseOperators.bitwiseNotBinary` masks operands and result
  to 32 bits:
  ```java
  long masked32bit = value & 0xFFFFFFFFL;
  long result = (~masked32bit) & 0xFFFFFFFFL;
  ```
- `Config.pm` correspondingly reports `ivsize=4`, `uvsize=4`,
  `use64bitint=` (empty).

The internals already use Java `long` (64-bit) for integers, so the
32-bit story is effectively a *reporting* choice that exists so that
`bop.t` and similar tests keep matching reference output.

**Impact**: Any CPAN module that gates on "64-bit Perl" rejects
PerlOnJava — including Data::CompactReadonly, which Number::Phone
hard-requires. So this single check kills the entire `Number::Phone`
chain (and therefore this test target).

**Options**:

1. **Switch PerlOnJava to 64-bit semantics globally**
   - Change `bitwiseNotBinary` (and review `&`, `|`, `^`, `<<`, `>>`)
     to use 64-bit unsigned (UV) semantics, matching 64-bit Perl.
   - Update `Config.pm` to advertise `ivsize=8`, `uvsize=8`,
     `use64bitint=define`, `longsize=8`, `nvsize=8`, etc.
   - Audit `bop.t` / `op/bop.t` and any test that hard-codes 32-bit
     output. Many of these will *start* matching reference Perl
     output, not break.
   - Pros: fixes a whole class of modules, removes a long-standing
     foot-gun, aligns with what the JVM backend actually does.
   - Cons: largest change; cross-cutting; needs careful test sweep.

2. **Bundle a patched Data::CompactReadonly**
   - Ship `lib/Data/CompactReadonly*.pm` under
     `src/main/perl/lib/Data/` with the `die("OS unsupported")` line
     removed (or its check inverted to use the actual integer width).
   - Pros: surgical; unblocks this specific chain; preserves the
     current 32-bit-bitwise contract.
   - Cons: doesn't fix the underlying mismatch; the next module that
     does the same gate (and there are several on CPAN) will fail
     again.

3. **Hybrid**: do (2) now to unblock this PR, file (1) as a follow-up
   tracked in this doc.

### 2. CRITICAL: I18N::LangTags and I18N::LangTags::Detect not bundled

**Root cause**: Both modules are part of the upstream Perl core
distribution (`perl-5.42.2`). PerlOnJava advertises a 5.42-class
version, so when CPAN can't find them locally it tries to install
`SHAY/perl-5.42.2.tar.gz`, which fails (`make => NO isa perl`).

The sources already exist in this repository under
`perl5/dist/I18N-LangTags/lib/`:

```
perl5/dist/I18N-LangTags/lib/I18N/LangTags.pm
perl5/dist/I18N-LangTags/lib/I18N/LangTags/Detect.pm
perl5/dist/I18N-LangTags/lib/I18N/LangTags/List.pm
```

…they just aren't copied into `src/main/perl/lib/I18N/` (which today
only contains `Langinfo.pm`). They load and run cleanly under jperl
when added with `-I`:

```
$ ./jperl -Iperl5/dist/I18N-LangTags/lib \
    -e 'use I18N::LangTags; use I18N::LangTags::Detect; print "OK\n";'
OK
```

**Impact**: Without these, anything that touches `Number::Phone`
fails with:

```
Can't locate I18N/LangTags/Detect.pm in @INC ...
Compilation failed in require
```

…which is what crashes essentially every test under `t/` for
`Number::Phone-4.0010` (~250 test files), and would also fail for any
locale-aware module that depends on the I18N pair (Locale::Maketext
extensions, HTTP::Message language negotiation, etc.).

**Solution**: Copy the three files into `src/main/perl/lib/I18N/` so
they ship inside `perlonjava.jar`. This is the same mechanism already
used for `Locale::Maketext`, `Pod::*`, etc.

**Priority**: HIGH (cheap, unblocks many modules).

### 3. (Informational) Number::Phone test harness

Even after Problems 1 + 2 are fixed, the Number::Phone *test* harness
(`t/*.t`, ~250 files) is not what we are trying to make green —
`jcpan -t FormValidator::...JP` only requires Number::Phone::JP to
*load*. Number::Phone's own test suite is large and known to be
flaky under non-CPAN-installed environments; it should be triaged
separately if/when we want a clean `jcpan -t Number::Phone`.

For the FormValidator test target, what matters is that:

- `use Number::Phone::JP` succeeds (which today requires #1 and #2).
- `Number::Phone::JP->new($num)->is_valid_number` returns the
  expected boolean for the 26 fixture numbers in
  `t/01_number_phone_jp.t`.

## Plan

### Phase 1 — Bundle the I18N::LangTags pair (HIGH, low risk)

- [ ] Copy the three files from `perl5/dist/I18N-LangTags/lib/I18N/`
      into `src/main/perl/lib/I18N/` (preserving directory layout).
- [ ] Run `make` (full unit-test suite must pass).
- [ ] Run `./jperl -e 'use I18N::LangTags::Detect; print "ok\n"'`
      to confirm the bundled copy is found via `@INC`.

### Phase 2 — Decide on the 64-bit story (CRITICAL, design choice)

Pick one of:

- [ ] **Option A — Go 64-bit**
  - [ ] Update `BitwiseOperators.bitwiseNotBinary` to 64-bit UV
        semantics (no `& 0xFFFFFFFFL` masking).
  - [ ] Audit `bitwise{And,Or,Xor}` and shift operators for the
        same 32-bit masking pattern; align with 64-bit semantics.
  - [ ] Update `Config.pm`: `ivsize=8`, `uvsize=8`,
        `use64bitint=define`, `longsize=8`, `nvsize=8` (where the
        JVM `double` agrees with reference Perl), `byteorder` etc.
  - [ ] Re-run `bop.t`, `op/bop.t`, `op/pack.t`, `op/sprintf.t` and
        compare against reference Perl output; fix divergences.
  - [ ] Re-run `make` + `make test-bundled-modules`.

- [ ] **Option B — Bundle a patched Data::CompactReadonly**
  - [ ] Vendor the runtime `.pm` files under
        `src/main/perl/lib/Data/CompactReadonly*.pm`.
  - [ ] Strip / invert the `die("OS unsupported")` gate.
  - [ ] Make sure the runtime path that uses `unpack`/`pack` 64-bit
        formats actually works under PerlOnJava (this module's
        whole point is 64-bit packed integer offsets — even if the
        load-time gate is skipped, runtime `pack "Q"` etc. must
        work).

- [ ] **Option C — Hybrid**: do Option B in this PR; file Option A
      as a follow-up issue/skill referencing this doc.

### Phase 3 — Wire up the FormValidator test target

- [ ] With Phases 1 + 2 done, re-run:
      ```
      ./jcpan -t FormValidator::Simple::Plugin::Number::Phone::JP
      ```
- [ ] Triage any remaining failures in
      `t/01_number_phone_jp.t` (regex / table loading from
      `Number::Phone::JP::Table::*`).
- [ ] Update `dev/cpan-reports/` with the new pass status.

## Progress Tracking

### Current Status: Plan written, no code changes yet.

### Completed Phases
- [x] Investigation (2026-04-28)
  - Identified the dependency chain.
  - Located the 32-bit `~0` mismatch as root cause for
    Data::CompactReadonly.
  - Confirmed I18N::LangTags pair is present in
    `perl5/dist/I18N-LangTags/lib/` but unbundled.

### Next Steps
1. User to choose Option A / B / C for Phase 2.
2. Implement Phase 1 (bundle I18N::LangTags) — safe, can land
   independently.
3. Implement chosen Phase 2 option on a feature branch.
4. Verify `jcpan -t FormValidator::Simple::Plugin::Number::Phone::JP`.

### Open Questions
- Are we ready to flip PerlOnJava to advertise 64-bit Perl semantics
  globally, or do we want to keep the 32-bit `Config.pm` story for
  reference-output compatibility? See Phase 2.
- Is there appetite for a small `JPERL_BITWIDTH=64` opt-in flag as
  a stepping stone, or should we just commit to one mode?

## References

- Source of the 32-bit gate:
  `src/main/java/org/perlonjava/runtime/operators/BitwiseOperators.java`
  (`bitwiseNotBinary`, ~line 260).
- Bundled module layout: `src/main/perl/lib/`.
- Build wiring: `build.gradle` `sourceSets.main.resources` (line ~360).
- Related plan: `dev/modules/JCPAN_DATETIME_FIXES.md` (similar
  pattern: missing-core-module + Makefile.PL gate failures).

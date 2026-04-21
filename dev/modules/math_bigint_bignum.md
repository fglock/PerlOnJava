# Math::BigInt / Math::BigFloat / Math::BigRat / bignum Support

## Overview

PerlOnJava previously shipped a thin `Math::BigInt` shim that wrapped Java's
`java.math.BigInteger`. That shim was removed and the upstream pure-Perl
`Math::BigInt` family (including `Math::BigInt::Lib`, `Math::BigInt::Calc`,
`Math::BigFloat`, `Math::BigRat`) plus the `bignum` pragma family
(`bigint`, `bignum`, `bigfloat`, `bigrat`) were imported via
`dev/import-perl5/sync.pl`.

## Current Status

**Branch:** `feature/math-bigint-upstream`

### Test counts in `make test-bundled-modules`

| Bucket                | Before shim→upstream | After upstream | Delta |
|-----------------------|---------------------:|---------------:|------:|
| Total tests discovered|                  228 |            279 |   +51 |
| Passing               |                  180 |            242 |   +62 |
| Failing               |                   48 |             37 |   −11 |
| Math-BigInt upstream  |               4 / 52 |        40 / 52 |   +36 |
| bignum upstream       |                    — |        26 / 51 |  +26  |
| Regressions elsewhere |                    — |             0 |     0 |

### Google::ProtocolBuffers (`./jcpan -t Google::ProtocolBuffers`)

|                              | Before |   After |
|------------------------------|-------:|--------:|
| Failing `.t` files           |  4 / 14|  2 / 14 |
| Failing subtests             |29 / 397|0 / 408  |

Remaining protobuf failures are the `*encode_uint = \&encode_int` typeglob
alias pattern (unrelated to Math::BigInt — tracked separately).

## Architecture

Upstream ships the following layers:

```
bignum.pm / bigint.pm / bigfloat.pm / bigrat.pm   ← user-facing pragmas
      ↓ activate overload::constant, set up exports
Math::BigInt / Math::BigFloat / Math::BigRat      ← high-level OO API
      ↓ $LIB->_mul($a, $b), etc.
Math::BigInt::Lib                                 ← backend API contract
      ↓
Math::BigInt::Calc (pure Perl, default)           ← "unsigned int is an array
                                                    of base-10**N digits"
(optional future: Math::BigInt::Java,             ← wraps java.math.BigInteger
                  Math::BigInt::GMP, ::Pari, …)
```

Design goal: keep upstream untouched; add a `Math::BigInt::Java` Lib subclass
*later* if/when performance measurements justify it.

## Remaining Failures (37)

Five root-cause buckets, grouped and prioritised.

### A. `overload::constant` / `:constant` import hook  — ~14 tests

Triggered whenever code does `use bigint;`, `use bignum;`, `use Math::BigInt
":constant"`, etc. These pragmas install callbacks via
`overload::constant(integer => ..., float => ..., binary => ...)` so that
numeric literals at compile time become `Math::BigInt` / `Math::BigFloat`
objects instead of doubles. Without this hook, `my $x = 2 ** 255` silently
overflows to a double (`1.60693804425899e+76`) before `bigint` can wrap it.

Failing tests in this bucket:
- `bignum/t/bigint.t`, `bigfloat.t`, `bignum.t`, `bigrat.t` (basic pragma
  tests: "$x = 5 makes $x a Math::BigInt")
- `bignum/t/const-{bigint,bigfloat,bignum,bigrat}.t`
- `Math-BigInt/t/calling-constant.t`
- partial failures in scope-*.t

Root cause: **PerlOnJava does not currently honour
`overload::constant`** callbacks. Numeric literals are always emitted as
native doubles/integers.

Plan:
1. Audit the current `overload` module implementation
   (`src/main/java/org/perlonjava/runtime/perlmodule/OverloadModule.java`
   and `src/main/perl/lib/overload.pm`) to see whether `constant()` is a
   stub.
2. Add recognition of `overload::constant` entries attached to the current
   lexical scope to `NumberParser` / literal emission paths — when an
   `integer`/`float`/`binary` handler is registered, rewrite the emitted
   literal as a method call on the handler.
3. Store the callbacks per-scope (so `{ no bigint; 5 }` sees the native
   literal), wired through the existing feature-scope mechanism.
4. Verify with `bignum/t/bigint.t` first — it's the smallest canary.

Estimate: **large** (core parser/emit change). Unblocks ≥14 tests.

### B. Lexical `no bigint` / pragma off-scoping — 4 tests

Tests: `bignum/t/scope-{bigint,bignum,bigfloat,bigrat}.t`.

Symptom: `no bigint` inside a `{}` block fails to disable the already-installed
`hex` / `oct` / `:constant` overrides. Failures match the pattern
`"hex is not overloaded"` — got `'Math::BigInt'`, expected `''`.

These pragmas override `CORE::GLOBAL::hex`, `CORE::GLOBAL::oct`, and set
`overload::constant`. Disabling requires unwinding those on scope exit.

Plan:
1. Investigate how `strict` / `warnings` / `integer` implement lexical
   scoping in PerlOnJava — there must be a scope-exit hook.
2. Route `bigint`'s `unimport` through the same mechanism so the CORE::GLOBAL
   aliases and constant handlers are restored on block exit.
3. Handles cases A and B together once constant-handler scoping lands.

Estimate: **medium**, mostly piggy-backs on bucket A.

### C. `Inf` / `NaN` object handling in Math::BigInt — 5 tests

Tests: `bignum/t/infnan-{bigint,bigfloat,bignum-mbf,bignum-mbr,bigrat}.t`.

Example failure:
```
Can't locate object method "bstr" via package "NaN"
```

Meaning: somewhere an op that should return a `Math::BigInt` NaN/Inf object
returns the bare string `"NaN"` / `"inf"`. The test then calls `->bstr`
on that string and blows up.

Candidates for the regression:
- Our `RuntimeScalar` string→number coercion for `"NaN"`/`"Inf"` may yield a
  plain `RuntimeScalar` rather than letting Math::BigInt take over.
- `Math::BigInt::Calc::_is_inf` / `_is_nan` logic may depend on `use integer`
  overflow semantics that behave differently on the JVM.
- Our overload fall-through on `*`/`+` with one `"NaN"` operand might bypass
  the BigInt overload and return a numeric NaN.

Plan:
1. Reproduce with a 5-line repro (`Math::BigInt->binf() * 2`).
2. Trace which op returns the bare string — likely one of `+`/`*`/`/`.
3. Fix in upstream's Calc.pm only as a last resort (prefer fixing our
   coercion). If the fix must live in `.pm`, register a sync.pl `patch:`
   entry rather than editing the file in place.

Estimate: **small to medium**.

### D. `$AUTOLOAD` edge case / Math::BigFloat AUTOLOAD — 8 tests

Tests: `Math-BigInt/t/{upgrade2,downgrade-mbi-mbr,hang-mbr,bigrat,bigratpm,bare_mbr,sub_mbr,mbr_ali}.t`
and several `bignum/t/down-*` / `upgrade*.t`.

Symptom:
```
Can't call Math::BigFloat->(), not a valid method
```

Here the AUTOLOAD path at `Math/BigFloat.pm:302` croaks with an **empty**
method name. The upstream code is:

```perl
my $name = $AUTOLOAD;
$name =~ s/^(.*):://;
```

A trailing `::` with no method name after it would strip to empty; also a
`$AUTOLOAD` left stale from a previous call would explain it.

`$AUTOLOAD` works fine in isolation (verified with a tiny Foo::AUTOLOAD
test), so this is not a blanket bug. Suspect:
- `$AUTOLOAD` inheriting the previous call's value when invoked via an
  indirect dispatch like `&{"${class}::${method}"}` with an empty
  `$method`.
- The upstream pattern `$class->import()` recursively re-entering AUTOLOAD
  during a partially-initialised BEGIN.

Plan:
1. Add a targeted repro using `Math::BigRat->new(...)` where we see the
   failure, narrow to whether `$AUTOLOAD` is unset or stale.
2. If stale: fix in `GlobalContext` / MethodResolution so `$AUTOLOAD` is
   reset before each AUTOLOAD invocation.
3. If the real call is `$class->$method` with `$method` empty: emit a
   clearer diagnostic *and* trace back to whatever call site passes empty
   method.

Estimate: **small** (once narrowed — it's a scalar-magic bug, not a
feature gap). Likely unblocks most of this cluster.

### E. GMP backend tests being attempted despite missing module — 4 tests

Tests: `bignum/t/backend-gmp-{bigint,bigfloat,bignum,bigrat}.t`.

**SKIPPED per user instruction** — these explicitly require
`Math::BigInt::GMP`, which we have no plans to port (relies on libgmp).

However, there is a minor PerlOnJava wart worth recording: the corresponding
`backend-pari-*` tests correctly run `plan skip_all` via the same
`eval { require Math::BigInt::Pari; }` pattern, while the GMP tests run
`plan tests => 1` instead of skipping. The working hypothesis is that
`require Math::BigInt::GMP` spuriously returns true on PerlOnJava (perhaps
because our XSLoader fallback or a stub returns ok for unknown packages),
so `$@` is empty and the test proceeds. Worth a 30-min look — if true,
the fix is one-line in XSLoader / require error-propagation.

Plan: re-enable the skip path (so these tests show as **skip** rather
than **fail**) by ensuring `require Math::BigInt::GMP` actually dies on a
missing module. That's it — no feature work required.

Estimate: **tiny** (15–30 min).

## Suggested Order of Attack

1. **D (AUTOLOAD)** — small, high-yield: fixes ~8 tests with one change.
2. **E (GMP skip path)** — tiny, purely cosmetic: 4 tests move from FAIL
   to SKIP.
3. **C (Inf/NaN)** — medium; likely a small string-coercion fix.
4. **A + B (`:constant` hook + lexical scoping)** — large, but the
   best-bang-for-buck since it unblocks the whole `bignum` user-facing story.

## Out of Scope

- **Math::BigInt::GMP** (libgmp binding) — not planned, per user guidance.
- **Math::BigInt::Pari** (libpari binding) — same reasoning; already skips cleanly.
- **Native-speed BigInteger backend** (`Math::BigInt::Java`) — deferred until a
  workload benchmark shows Math::BigInt is hot. Would be a ~150-line subclass
  of `Math::BigInt::Lib` delegating to a Java class that wraps
  `java.math.BigInteger`.

## Related

- `dev/import-perl5/config.yaml` — sync.pl entries for the imported files.
- `dev/import-perl5/sync.pl` — the import/sync script.
- `src/test/resources/unit/math_bigint.t` — PerlOnJava-specific regression
  tests (underscore hex parsing, shift/bit overloads, varint encoding) that
  run on every `make`.
- `src/test/resources/module/Math-BigInt/t/` and
  `src/test/resources/module/bignum/t/` — upstream CPAN test trees, run by
  `make test-bundled-modules`.
- Core fixes landed alongside the upstream import:
  - `src/main/java/.../runtimetypes/TieScalar.java` — reentrancy guard for
    tied-scalar `FETCH` / `STORE` (required by `tie $rnd_mode, ...`).
  - `src/main/java/.../perlmodule/Universal.java` — dropped overly strict
    `$$` prototype on `UNIVERSAL::isa` / `can` / `DOES` (blocked
    `Math::BigRat`'s `UNIVERSAL::isa(@_)` call).
  - `src/main/java/.../operators/BitwiseOperators.java` — `<<` / `>>` now
    dispatch to overloaded operators on blessed operands.

## Progress Tracking

### Completed
- [x] Delete `MathBigInt.java` + shim `Math::BigInt.pm` (2026-04-21)
- [x] Import upstream Math::BigInt / Lib / Calc / BigFloat / BigRat via sync.pl
- [x] Import bignum pragma family + their upstream test trees
- [x] Fix `TieScalar` reentrancy guard so `tie $rnd_mode, 'Math::BigInt'`
      doesn't infinite-recurse in STORE
- [x] Remove strict `$$` prototype on `UNIVERSAL::isa`/`can`/`DOES`/`VERSION`
- [x] Add `<<` / `>>` overload dispatch in `BitwiseOperators`
- [x] Add PerlOnJava-specific regression tests to `unit/math_bigint.t`
      (underscore hex, shift/bit overloads, varint round-trip)

### Next Steps
1. Bucket D: diagnose the empty-`$AUTOLOAD` / `Math::BigFloat->()` path.
2. Bucket E: confirm `require Math::BigInt::GMP` properly dies, so those
   4 tests show as SKIP instead of FAIL.
3. Bucket C: fix Inf/NaN bare-string → object leak.
4. Buckets A + B: implement `overload::constant` + lexical unwind.

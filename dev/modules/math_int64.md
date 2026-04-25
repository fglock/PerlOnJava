# Math::Int64 / Math::UInt64 Support for PerlOnJava

## Status

**Not started.** This document is a plan only; no code has been written.

`./jcpan -t Math::Int64` currently fails at `Makefile.PL` because
`Config::AutoConf->check_default_headers()` shells out to
`ExtUtils::CBuilder`, which in PerlOnJava is configured with
`Config{cc} = javac`, so every C-header probe fails. Even if those probes
were taught to short-circuit, `Math::Int64` is a pure-XS module
(`Int64.xs`, ~2,000 lines) with no Perl fallback for the heavy
operations, so a CPAN install can never succeed without a Java-backed
implementation. See `dev/modules/math_int64_jcpan_failure.md` (not yet
written) for the raw investigation; the summary is in this document.

## Goal

Bundle `Math::Int64` 0.57 with PerlOnJava so that:

1. `use Math::Int64 qw(int64 uint64 ...)` works without any CPAN install.
2. The upstream test suite (`./jcpan -t Math::Int64` after bundling, plus
   the in-tree `src/test/resources/module/Math-Int64/t/`) passes.
3. Common downstream consumers — anything that pulls `Math::Int64` in for
   64-bit arithmetic, BER/network/native byte-order conversions, or
   `Storable` round-trips — work unmodified.

We do **not** want to introduce a new Maven dependency. The JDK
(`java.lang.Long`, `java.nio.ByteBuffer`, `java.math.BigInteger`,
`java.security.SecureRandom`) already provides everything `Int64.xs`
needs.

## Why no Maven dependency

`Math::Int64`'s entire C surface maps to operations already in
`java.lang.Long`:

| C / XS need                          | JDK equivalent                                                |
|--------------------------------------|---------------------------------------------------------------|
| `int64_t` storage / arithmetic       | primitive `long` (signed, two's complement)                   |
| `uint64_t` storage / arithmetic      | primitive `long` reinterpreted unsigned                       |
| signed `+ - * / %` `<=> == != < > <= >=` | native `long` operators; `Long.compare`                    |
| unsigned `/ %`                       | `Long.divideUnsigned`, `Long.remainderUnsigned`               |
| unsigned compare                     | `Long.compareUnsigned`                                        |
| `string_to_int64(str, base)`         | `Long.parseLong(str, base)` (with `0x`/`0b`/`0` autodetect)   |
| `string_to_uint64(str, base)`        | `Long.parseUnsignedLong(str, base)`                           |
| `int64_to_string`, `..._to_hex`      | `Long.toString(v, base)`, `Long.toHexString`                  |
| `uint64_to_string`, `..._to_hex`     | `Long.toUnsignedString(v, base)`                              |
| `net_to_int64` / `int64_to_net`      | `ByteBuffer.order(BIG_ENDIAN).getLong/putLong`                |
| `le_to_int64` / `int64_to_le`        | `ByteBuffer.order(LITTLE_ENDIAN).getLong/putLong`             |
| `native_to_int64` / `int64_to_native`| platform-endian `ByteBuffer` (`ByteOrder.nativeOrder()`)      |
| `BER_to_int64` / `int64_to_BER`      | hand-rolled (Math::Int64's own BER variant), trivial loop     |
| `int64_rand` / `uint64_rand`         | `SecureRandom.nextLong()`                                     |
| pow                                  | hand-rolled exponentiation by squaring on `long`              |
| die_on_overflow checks               | `Math.addExact` / `Math.multiplyExact` (signed only) + manual unsigned checks |
| `Storable` `STORABLE_freeze/thaw`    | pure-Perl using existing primitives                           |

Guava (`UnsignedLong`, `UnsignedLongs`) duplicates the JDK methods and
adds ~3 MB without functional gain. Not worth a dependency.

## Architecture

### Module shape

`Math::Int64` ships:

```
Math::Int64                          (lib/Math/Int64.pm)        - XS bootstrap, exports, MAX/MIN constants
Math::UInt64                         (registered by XS only)    - UInt64 class, no .pm
Math::Int64::die_on_overflow         (lib/.../die_on_overflow.pm)- Pure Perl pragma (lexical %^H hint)
Math::Int64::native_if_available     (lib/.../native_if_available.pm)- Pure Perl pragma
```

Three XS `MODULE = Math::Int64` blocks register subs into three
packages, controlled by `PREFIX=`:

| Block | Prefix    | Package         | Purpose                                     |
|-------|-----------|-----------------|---------------------------------------------|
| 1     | `miu64_`  | `Math::Int64`   | Free-standing helpers and conversions       |
| 2     | `mi64`    | `Math::Int64`   | Overload methods on `Math::Int64` objects   |
| 3     | `mu64`    | `Math::UInt64`  | Overload methods on `Math::UInt64` objects  |

Tests we need to satisfy (already in upstream tarball, ~9 `.t` files):

```
t/Math-Int64.t            t/Math-UInt64.t
t/Math-Int64-Native.t     t/Math-UInt64-Native.t
t/MSC.t                   t/as_int64.t
t/die_on_overflow.t       t/pow.t            t/storable.t
```

### Object representation in PerlOnJava

`Math::Int64` and `Math::UInt64` objects must:

- Stringify via `""` overload to a decimal representation (signed for
  `Int64`, unsigned for `UInt64`).
- Numify via `0+` overload to a Perl number — lossy when the value
  doesn't fit a `double`, matching upstream behaviour.
- Round-trip through `Storable::freeze/thaw` (handled by pure-Perl
  `STORABLE_freeze/STORABLE_thaw`, which use the XS conversion helpers).
- Be blessed into the right class so `ref()` returns
  `"Math::Int64"` / `"Math::UInt64"`.

Concretely, model them as `RuntimeScalarReference`s pointing at a small
Java holder. Two options:

- **Option A — blessed scalar holding a `RuntimeScalar` of type
  `INTEGER` whose 64-bit value is the long.** Smallest footprint, but
  PerlOnJava `RuntimeScalar` integers may already coerce to double and
  lose precision in some paths. Needs verification.
- **Option B — blessed scalar reference to a Java holder
  (`Int64Holder { long value; }`), modelled like the `Bit::Vector` port
  (`dev/modules/bit_vector.md`).** Robust against any internal coercion
  in `RuntimeScalar`. A bit more code (mapping ID → holder), but the
  pattern is already proven in PerlOnJava.

**Recommended: Option B.** Match what `Bit::Vector` does:

- `Math::Int64` instance = blessed scalar whose inner value is an opaque
  long (the holder ID).
- A static `Long2ObjectMap<Int64Holder>` (or
  `ConcurrentHashMap<Long, Int64Holder>`) keeps the holder alive.
- `DESTROY` / `weaken` interplay follows the existing
  `dev/architecture/weaken-destroy.md` model.

Optimisation we should skip in v1: representing values that fit in a
plain `RuntimeScalar` integer without a holder (the `:native_if_available`
pragma's job in upstream). v1 keeps everything boxed.

### Java implementation layout

```
src/main/java/org/perlonjava/runtime/perlmodule/MathInt64.java
    - registers Math::Int64::xxx subs (signed)
    - registers Math::UInt64::xxx subs (unsigned, mostly forwarders that
      flip signed↔unsigned semantics)
    - public static helpers:
        Int64Holder allocSigned(long v)
        Int64Holder allocUnsigned(long v)
        long get(RuntimeScalar self)
        RuntimeScalar bless(Int64Holder h, String pkg)
```

XS → Java mapping (signed `mi64_*` block; unsigned `mu64_*` is
mechanically symmetric using `Long.*Unsigned` variants):

| XS sub                     | Java implementation                                       |
|----------------------------|-----------------------------------------------------------|
| `miu64__backend`           | `return new RuntimeScalar("IV")` (we always behave like the IV backend)|
| `miu64__set_may_die_on_overflow(v)` | toggle a static `volatile boolean`              |
| `miu64__set_may_use_native(v)`      | accepted, ignored (no native fast path)         |
| `miu64_int64(value)`       | parse number / string / int64 obj → `Int64Holder`         |
| `miu64_uint64(value)`      | same, unsigned                                            |
| `miu64_int64_to_number`    | `(double) holder.value`                                   |
| `miu64_uint64_to_number`   | unsigned-to-double via `Long.toUnsignedString` + `Double.parseDouble` (or BigInteger fast path) |
| `miu64_string_to_int64(s,base=0)` | `parseSigned(s, base)` with `0x`/`0b`/`0` detection|
| `miu64_string_to_uint64(s,base=0)`| `parseUnsigned(s, base)`                           |
| `miu64_hex_to_int64`/`..._uint64` | `Long.parseLong/Unsigned` with base 16             |
| `miu64_int64_to_string(self,base=10)` | `Long.toString(v, base)`                       |
| `miu64_uint64_to_string(self,base=10)`| `Long.toUnsignedString(v, base)`                |
| `miu64_int64_to_hex` / `..._uint64_to_hex` | `Long.toHexString(v)` zero-padded to 16 chars |
| `miu64_net_to_*` / `*_to_net` | `ByteBuffer.allocate(8).order(BIG_ENDIAN)`             |
| `miu64_le_to_*` / `*_to_le`   | `ByteBuffer.allocate(8).order(LITTLE_ENDIAN)`          |
| `miu64_native_to_*` / `*_to_native` | `ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())` |
| `miu64_BER_to_int64`/`uint64`, `miu64_int64_to_BER`/`uint64` | port the BER loop from `c_api.h` (it's ~30 lines of bit-shifting) |
| `miu64_int64_rand` / `uint64_rand` | `SecureRandom.nextLong()` (or shared `Random` to match `int64_srand`) |
| `miu64_int64_srand(seed)`  | `random = seed.isUndef() ? new Random() : new Random(seed)` |
| `mi64_add/sub/mul/div/rest/left/right/pow/...` | trivial; honour `_set_may_die_on_overflow` for the signed variants |
| `mi64_spaceship`, `mi64_eqn/nen/gtn/ltn/gen/len` | `Long.compare(...)`             |
| `mi64_and/or/xor/not/bnot/neg/bool/number/clone/string/inc/dec` | bitwise / unary on `long` |
| `mu64_*` block             | as `mi64_*`, but use `Long.divideUnsigned`, `Long.remainderUnsigned`, `Long.compareUnsigned` |

Overload semantics: since the signed/unsigned arithmetic logic lives
inside our Java methods (one per XS sub), the upstream XS-level
`use overload` table in `lib/Math/Int64.pm` is reused verbatim — it just
forwards `+` to `mi64_add`, etc. No new overload bookkeeping in Java.

### Perl side (`lib/Math/Int64.pm`)

Reuse upstream `lib/Math/Int64.pm` (603 lines) **as-is**, replacing only
the `XSLoader::load` line conceptually — once `MathInt64.java` is
registered, `XSLoader::load('Math::Int64', $VERSION)` resolves to the
Java module and the rest of the file works unchanged.

Likewise `lib/Math/Int64/die_on_overflow.pm` and
`lib/Math/Int64/native_if_available.pm` are pure-Perl pragmas that twiddle
`%^H`; they should drop in unmodified. The XS sub
`miu64__set_may_die_on_overflow` is the bridge.

## Edge cases / things to double-check

- **Signed overflow detection.** Upstream's `die_on_overflow` mode
  expects `MAX_INT64 + 1` to die. Use `Math.addExact`,
  `Math.subtractExact`, `Math.multiplyExact`. For unsigned, no
  built-in; check operands manually
  (`Long.compareUnsigned(a, MAX - b) > 0`, etc.).
- **`int64(double)` rounding.** Upstream uses C `(int64_t)d`, which
  truncates toward zero. Java `(long) d` does the same. ✅
- **`int64('0x...')` parsing.** Upstream auto-detects `0x` / `0b` /
  octal `0…`. `Long.parseLong` does not — we need to strip the prefix
  manually.
- **`int64_to_BER`.** Math::Int64 BER is **not** ASN.1 BER; it's
  Math::Int64's own variable-length encoding from `c_api.h`. Port the
  exact bit pattern.
- **`Storable` integration.** Tested by `t/storable.t`. Upstream defines
  `STORABLE_freeze` / `STORABLE_thaw` in `lib/Math/Int64.pm` already;
  they call XS helpers we'll have. Should "just work" once the XS subs
  exist.
- **`MSC.t`.** Tests Microsoft Compiler C compatibility — about
  `__int64` parsing rules. On JVM most of this is irrelevant; review the
  test once we have a passing baseline.
- **Big-int interop.** Some downstream code passes `Math::BigInt` into
  `int64()`. PerlOnJava already ships an upstream `Math::BigInt`
  (`dev/modules/math_bigint_bignum.md`), so `int64($big)` should accept
  a BigInt and call `->numify` / `->bstr` to get a value.
- **Pragma scope.** `:die_on_overflow` and `:native_if_available` are
  lexical (`%^H` hints). The Java side reads
  `Math::Int64::_get_overflow_die_flag()` once per call, just like
  upstream. Make sure PerlOnJava's `%^H` propagation works for these
  hints (it does for `strict`, so this should be fine; verify with a
  quick sanity test before relying on it).

## Phasing

**Phase 0 — Preparation (no XS port yet).**
- [ ] Land a small fix making `ExtUtils::CBuilder` fail loudly when
      `Config{cc} eq 'javac'` (instead of silently invoking `javac` on a
      `.c` file). This is independent of Math::Int64 and benefits any
      CPAN module that probes the C compiler.
- [ ] Fix `Config{archlibexp}`/`privlibexp` to be absolute paths and set
      a non-empty `obj_ext` (`.o`) to silence the `uninitialized value`
      warning at `ExtUtils/CBuilder/Base.pm:117`.
- [ ] Track these in their own design doc/PR; do not bundle with the
      Math::Int64 port.

**Phase 1 — Skeleton + signed Int64.**
- [ ] Create `MathInt64.java` with the holder, registration, and the
      `miu64_*` block 1 helpers (`int64`, `int64_to_number`,
      `*_to_string`, `string_to_int64`, `*_to_hex`, `hex_to_int64`).
- [ ] Bundle `lib/Math/Int64.pm` from upstream.
- [ ] Smoke test: `./jperl -e 'use Math::Int64 qw(int64); my $x =
      int64("12345678901234"); print "$x\n"'` matches system Perl.

**Phase 2 — Signed arithmetic + overload (`mi64_*` block).**
- [ ] All `mi64_*` operators.
- [ ] Run `t/Math-Int64.t`, `t/as_int64.t`, `t/pow.t`.

**Phase 3 — Unsigned UInt64 (`mu64_*` block).**
- [ ] Mirror Phase 2 with `Long.*Unsigned`.
- [ ] Run `t/Math-UInt64.t`.

**Phase 4 — Byte-order / encoding helpers.**
- [ ] `*_to_net`, `*_to_le`, `*_to_native`, `*_to_BER` and their
      inverses, `BER_length`.
- [ ] Run `t/Math-Int64-Native.t`, `t/Math-UInt64-Native.t`.

**Phase 5 — Pragmas, RNG, Storable.**
- [ ] `int64_rand`, `uint64_rand`, `int64_srand`.
- [ ] `:die_on_overflow` honoured by `mi64_*` / `mu64_*` arithmetic.
- [ ] `:native_if_available` accepted (no-op fast path is fine).
- [ ] Run `t/storable.t`, `t/die_on_overflow.t`, `t/MSC.t`.

**Phase 6 — Integration.**
- [ ] Copy upstream `t/` into `src/test/resources/module/Math-Int64/t/`.
- [ ] `make test-bundled-modules` green.
- [ ] `./jcpan -t Math::Int64` green (after bundling, jcpan should detect
      the module is already provided and skip configure).
- [ ] Update `docs/reference/bundled-modules.md`,
      `docs/about/changelog.md`, `docs/reference/feature-matrix.md`.

## Open questions

1. Does PerlOnJava's `%^H` already round-trip through `eval STRING`
   correctly enough for the `:die_on_overflow` / `:native_if_available`
   pragmas? If not, this needs a small parser-level fix before Phase 5.
2. `RuntimeScalar`'s `INTEGER` type — does it preserve a full 64-bit
   `long` losslessly across all numeric ops, or does it sometimes get
   downgraded to `double`? Answer determines whether Option A
   (plain-scalar storage) is viable as a later optimisation. v1 uses the
   safer Option B (Java holder).
3. Should we add a `Long2ObjectMap` from a third-party (Eclipse
   Collections / fastutil) for the holder map, or stick with
   `ConcurrentHashMap<Long, Int64Holder>`? Both work; ConcurrentHashMap
   keeps zero new dependencies and matches `Bit::Vector`'s approach.

## Progress Tracking

### Current Status: Plan only — no implementation yet.

### Completed Phases
None.

### Next Steps
1. Decide on Phase 0 (CBuilder/Config cleanup) — ship as its own PR.
2. Start Phase 1 on a feature branch `feature/math-int64`.

### Open Questions
See "Open questions" section above.

## References

- Upstream source (cached): `~/.cpan/build/Math-Int64-0.57-5/`
  - `Int64.xs` — three `MODULE = Math::Int64` blocks
  - `lib/Math/Int64.pm` — Perl façade, exports, overload table
  - `lib/Math/Int64/{die_on_overflow,native_if_available}.pm` — pragmas
  - `c_api.h`, `strtoint64.h`, `isaac64.h` — BER + RNG helpers
  - `t/*.t` — 9 upstream test files
- PerlOnJava reference ports of XS modules:
  - `src/main/java/org/perlonjava/runtime/perlmodule/CryptOpenSSLBignum.java`
  - `src/main/java/org/perlonjava/runtime/perlmodule/MIMEBase64.java`
  - `dev/modules/bit_vector.md` (object-with-Java-holder pattern)
- Skill: `.agents/skills/port-cpan-module/SKILL.md`
- Authoritative porting guide: `docs/guides/module-porting.md`
- Background investigation (ad-hoc, not yet a doc): output of
  `./jcpan -t Math::Int64` and
  `~/.cpan/build/Math-Int64-0.57-5/config.log`.

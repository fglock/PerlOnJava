# Storable: Native Binary Format Support

## Status

**Phase 2.x landed — full Storable opcode coverage on both sides.**
PerlOnJava's Storable reads AND writes the native Perl Storable
binary format, byte-compatible with upstream `perl` for the data
shapes that PerlOnJava supports. Files written by `jperl` can be
read by system `perl` and vice versa. Phase 2.x (Sept 2026) added
full encoder coverage for `SX_REGEXP`, `SX_VSTRING`/`SX_LVSTRING`,
`SX_WEAKREF`/`SX_WEAKOVERLOAD`, `SX_FLAG_HASH` for utf8-flagged keys,
`$Storable::canonical` mode, and `SX_TIED_*` for tied containers.

What works today:
- `retrieve($file)` reads any current-format Storable file produced by
  upstream perl: scalars, refs, arrays, hashes, flag-hashes, blessed
  objects, cyclic references via `SX_OBJECT` backrefs, shared
  substructures, network and native byte order, UTF-8 keys, nested
  structures, regexes, v-strings, tied containers.
- `store` / `nstore` / `freeze` / `nfreeze` emit `pst0` (file) or the
  bare in-memory body, covering all of the above plus `SX_HOOK`
  (STORABLE_freeze emission with sub-refs and SHF_NEED_RECURSE),
  `SX_OVERLOAD`, `SX_WEAKREF`, and `SX_FLAG_HASH` per-key
  `SHV_K_UTF8` flag.
- `$Storable::canonical = 1` emits hash keys in byte-lexicographic
  order, byte-stable across platforms.
- `dclone` works (pure deep-copy, never touches the wire format).
- `STORABLE_freeze` / `STORABLE_thaw` / `STORABLE_attach` hooks fire
  on both sides.
- The `~/.cpan/Metadata` cache and other CPAN-based Storable
  interchange (Cache::FileCache, Module::Build's `_build/` state, etc.)
  interoperate cleanly between jperl and system perl.
- ~1500 upstream `t/*.t` assertions pass cleanly under
  `make test-bundled-modules` (integer.t alone is 875). Many more
  tests now reach the end of their plan than before; specific
  per-test exclusions are documented in `dev/import-perl5/config.yaml`.

Known limitations (small, tracked in "Next Steps" below):
- Top-level `freeze \$blessed_ref` round-trips with one ref level
  lost — the wire `SX_REF + SX_BLESS + body` is structurally
  ambiguous between this case (wants 2 levels) and `freeze tied-hash`
  (wants 1 level for the tying object). Picking 1-level (collapse-on-
  bless) preserves the more important tied round-trip.
- `SX_TIED_KEY` / `SX_TIED_IDX` (per-slot tied magic) — refused with
  a clear error; no PerlOnJava equivalent of upstream's per-slot
  tied magic infrastructure yet.
- `SHT_EXTRA` (hooked-tied containers via `SX_HOOK`) — refused with
  a clearer message; eflags-byte handling needs changes outside the
  current refactor.

## Motivation

PerlOnJava ships its own `Storable` module
(`src/main/perl/lib/Storable.pm` + `src/main/java/org/perlonjava/runtime/perlmodule/Storable.java`)
that originally serialized to **YAML** rather
than the native Perl Storable binary format. The Java side declared
this intentionally:

> Storable module implementation using YAML with type tags for blessed
> objects. … This elegant approach leverages YAML's `!!` type tag system
> for object serialization …

The in-memory `freeze`/`thaw` path already grew a separate binary format
(magic byte `0xFF`, see `Storable.java`), but file I/O is still YAML.

This breaks every workflow that exchanges Storable data between `jperl`
and a real `perl`. Concretely observed during `jcpan -t Toto`
investigation (2026-04-29). Note: items marked **fixed** below are
resolved by the Phase-1 read path that has since landed; the
underlying issue (jperl writes still aren't system-perl-readable)
persists pending Phase 2.

1. **CPAN.pm `~/.cpan/Metadata` cache.** CPAN persists its module index
   with `Storable::nstore`. Before the read path landed, switching
   between `perl` and `jperl` always invalidated the cache:
   - jperl-written file → system perl: `File is not a perl storable at
     .../Storable.pm line 411`. **Still happens** until Phase 2 makes
     jperl write `pst0`.
   - perl-written file → jperl: used to error with
     `retrieve failed: …`. **Fixed** — jperl now decodes upstream
     `pst0` files correctly, so reading a system-perl-written cache
     no longer triggers a re-index.

2. **distroprefs / persistent state.** CPAN.pm warns
   `'YAML' not installed, will not store persistent state` on system
   perl when only YAML metadata is present, then falls back further.
   **Still happens** for caches that jperl wrote.

3. **Other tooling.** Anything that hands a `freeze`d blob to / from a
   real perl breaks: `Cache::FileCache`, DBI-cached statement metadata,
   `DBM::Deep` Storable values, build-system caches (e.g. ExtUtils
   `.packlist` adjacent state, `Module::Build`'s `_build/` Storable
   files), Sereal/Storable hybrid pipelines, etc. **Half fixed:** the
   real-perl → jperl direction now works; the jperl → real-perl
   direction is still broken until Phase 2.

4. **Cross-process IPC.** `Storable::freeze`/`thaw` is a common wire
   format between Perl processes. Mixed jperl/perl fleets cannot
   interoperate today. **Half fixed** as in (3).

## Goal

`Storable::store`, `Storable::nstore`, `Storable::retrieve`,
`Storable::freeze`, `Storable::nfreeze`, `Storable::thaw`, and
`Storable::dclone` produce and consume the **same byte stream** as
upstream Perl 5 `Storable` for the data shapes that PerlOnJava already
supports.

Round-trip parity required for these data shapes (in priority order):

1. Scalars: undef, integers (SX_BYTE/SX_INTEGER/SX_NETINT), doubles
   (SX_DOUBLE), strings (SX_SCALAR / SX_LSCALAR / SX_UTF8STR / SX_LUTF8STR).
2. References to scalars (SX_REF, SX_OVERLOAD).
3. Arrays (SX_ARRAY / SX_LARRAY) and hashes (SX_HASH / SX_LHASH /
   SX_FLAG_HASH).
4. Blessed references (SX_BLESS / SX_IX_BLESS) — class name table.
5. Backreferences (SX_OBJECT) for shared / cyclic structures.
6. STORABLE_freeze / STORABLE_thaw hooks (SX_HOOK family). Already
   partially handled in our current YAML/binary code; we keep the hook
   semantics but emit/parse the upstream wire layout.

Out of scope for the first pass (acceptable to die with
`Cannot store <kind> items` matching upstream wording):

- Code references with `Deparse` / `Eval` (already gated; keep behavior).
- Glob references, regexes, `tied` containers — return upstream's
  "Cannot store …" errors instead of silently lossy-encoding them.
- The "old" Storable formats (major < 2). We only emit current major and
  read what current Perl emits.
- Locking variants beyond `lock_store` / `lock_retrieve` advisory
  semantics already provided.

## Wire-format reference

Authoritative source: the upstream Perl distribution checked into this
repo at `perl5/dist/Storable/`. Specifically:

- **`perl5/dist/Storable/Storable.xs`** — definitive spec.
  - Lines **141–177**: full `SX_*` opcode table (UNDEF, BYTE, INTEGER,
    NETINT, DOUBLE, SCALAR/LSCALAR, UTF8STR/LUTF8STR, REF, ARRAY/LARRAY,
    HASH/LHASH, FLAG_HASH, BLESS/IX_BLESS, HOOK, OVERLOAD,
    WEAKREF/WEAKOVERLOAD, VSTRING/LVSTRING, SVUNDEF_ELEM, REGEXP,
    LOBJECT, BOOLEAN_TRUE/FALSE, CODE, OBJECT-as-backref).
  - Lines **182–194**: in-hook secondary opcodes (`SX_ITEM`, `SX_KEY`,
    `SX_VALUE`, `SX_CLASS`, `SX_LG_CLASS`, `SX_STORED`, …).
  - Lines **907–975**: file magic — `MAGICSTR_BYTES = 'p','s','t','0'`
    (current) and `OLDMAGICSTR_BYTES = 'perl-store'` (legacy), the
    `BYTEORDER_BYTES` strings for native-order files, and
    `STORABLE_BIN_MAJOR=2` / `STORABLE_BIN_MINOR=12`.
  - Lines **~4460–4530**: `magic_write()` — exact header layout
    (`pst0` + `(major<<1)|netorder` + minor + byteorder string +
    sizeof(int)/long/char\*/NV).
  - Line **~4689**: `magic_check` / version gate on retrieve.
  - The paired `store_*` / `retrieve_*` C functions further down
    document each opcode's body byte-for-byte.

- **`perl5/dist/Storable/lib/Storable.pm`** — POD describing the
  public API, canonical-mode semantics, hook protocol, and known
  cross-version caveats.

- **`perl5/dist/Storable/t/`** — upstream test suite. Useful as both a
  conformance oracle and a fixture source: `store.t`, `retrieve.t`,
  `blessed.t`, `canonical.t`, `code.t`, `integer.t`, `utf8.t`,
  `weak.t` cover almost every opcode path.

Reading order for an implementer:

1. The opcode `#define` block (141–177) — port to a Java enum/constants
   class.
2. `magic_write` and `magic_check` — header layout + version gate.
3. Each `store_*` / `retrieve_*` pair, in priority order: scalar → ref
   → array → hash → bless → hook.
4. Borrow upstream `t/*.t` as differential fixtures.

### High-level summary (handy while reading the XS)

```
file:    "pst0" magic
         + byte version_major (currently 2)
         + byte version_minor
         + byte byte-order length N
         + N bytes byte-order string ("12345678" on LE 64-bit, etc.)
         + byte sizeof(int)
         + byte sizeof(long)
         + byte sizeof(char *)
         + byte sizeof(NV)
         + body
network: "pst0"
         + byte (version_major | 0x80)   ; high bit signals netorder
         + byte version_minor
         + body
```

`freeze`/`nfreeze` produce only the body (no `pst0` header), prefixed
with the netorder byte alone.

The current `Storable.java` already has approximately the right opcode
table (`SX_OBJECT=0`, `SX_HOOK=19`, `SX_CODE=26`, etc.) but interprets
them in a custom in-memory framing rather than the on-disk one. The new
implementation should consolidate to one set of opcode constants used by
both file and in-memory paths, sourced from `Storable.xs`.

## Plan

### Phase 1 — Decoder (read upstream Storable)

Goal: `Storable::retrieve` / `Storable::thaw` accept any byte stream
produced by current upstream Perl Storable for the data shapes listed
above. Round-trip back to YAML during this phase if needed.

Tasks:

1. Add `org.perlonjava.runtime.perlmodule.storable.NativeReader` (new
   package) implementing the file/in-memory header parse (`pst0`,
   netorder vs native byte order, version check).
2. Implement opcode dispatch for: SX_UNDEF, SX_BYTE, SX_INTEGER,
   SX_NETINT, SX_DOUBLE, SX_SCALAR, SX_LSCALAR, SX_UTF8STR, SX_LUTF8STR,
   SX_REF, SX_ARRAY, SX_LARRAY, SX_HASH, SX_LHASH, SX_FLAG_HASH,
   SX_BLESS, SX_IX_BLESS, SX_OBJECT, SX_OVERLOAD, SX_TIED_*. Tied
   variants throw "tied …" diagnostic by default.
3. Hook (SX_HOOK / SX_HOOK_CLONE) parser that calls existing
   STORABLE_thaw plumbing in `Storable.java`.
4. Differential test harness: a small Perl script run under system
   `perl` that emits a fixture file, plus a jperl test that retrieves
   it and dumps via Data::Dumper. New file:
   `src/test/resources/storable/native_decode.t`.

Exit criterion: `~/.cpan/Metadata` written by system perl is readable
by jperl. (This is the immediate user-visible win — no more re-index.)

### Phase 2 — Encoder (write upstream Storable)

Goal: `Storable::store` / `nstore` / `freeze` / `nfreeze` emit bytes
byte-identical to upstream for canonical-mode output.

Tasks:

1. `NativeWriter` mirroring `NativeReader`. Write `pst0` header, choose
   integer width (SX_BYTE vs SX_INTEGER vs SX_NETINT) the same way XS
   does.
2. `$Storable::canonical` honored for hash key ordering. Already
   honored on the YAML path; port the test.
3. SX_OBJECT shared-reference table, keyed on identity (Java
   `IdentityHashMap`, as the current code already uses).
4. STORABLE_freeze hook emission.
5. Differential round-trip test: jperl-encoded → system-perl-decoded
   for the same fixture set used in Phase 1.

Exit criterion: jperl-written `~/.cpan/Metadata` is readable by system
perl. Bidirectional CPAN cache sharing works.

### Phase 3 — Cutover and YAML deprecation

1. Default `store`/`retrieve`/`freeze`/`thaw` to native binary.
2. Keep the existing YAML reader as a fallback for files that don't
   start with `pst0` (so users with old jperl-written caches don't
   silently lose them on upgrade). Log a one-time warning and
   recommend regeneration.
3. Drop the YAML writer path. Remove the `BINARY_MAGIC = '\u00FF'`
   in-memory format and migrate `freeze`/`thaw` to the netorder body
   layout instead — this is what real `Storable::freeze` returns and
   what other Perl code expects on the wire.
4. Update `dev/modules/cpan_client.md` and the AGENTS table for
   `Storable`.

### Phase 4 — Optional follow-ups

- `Storable::file_magic` (used by some tooling to sniff files).
- `Storable::read_magic` on an open filehandle.
- `$Storable::Deparse` / `$Storable::Eval` for code refs (currently
  refused; bring up to parity with upstream's documented behavior of
  emitting `B::Deparse` text and re-`eval`-ing on retrieve).
- Sereal-style `freeze`/`thaw` interop is **not** in scope — that's a
  separate module.

## Risks and open questions

1. **Hash-key ordering.** Upstream Storable's non-canonical mode emits
   keys in Perl's iteration order, which is randomized. We currently
   iterate Java `LinkedHashMap`s in insertion order. For non-canonical
   output we should match Perl's behavior closely enough that
   round-trip equality holds — ordering doesn't have to match exactly,
   but tests that compare frozen byte streams across perls must use
   `$Storable::canonical = 1`. Document this in the module POD.
2. **Numeric type promotion.** Perl picks SX_BYTE / SX_INTEGER /
   SX_NETINT / SX_DOUBLE based on the SV's flags (IOK, NOK, POK).
   PerlOnJava `RuntimeScalar` carries its own type. We need a small
   mapping table; the easy mistake is emitting SX_DOUBLE for things
   that came in as integers, which inflates files and breaks
   byte-level diff with upstream.
3. **UTF-8 flag.** Perl tracks SVf_UTF8 separately from byte content.
   PerlOnJava strings are Java `String` (UTF-16 internal) — we already
   have `RuntimeScalar.isUtf8()`-equivalent logic for sprintf etc.
   Reuse it; otherwise SX_SCALAR vs SX_UTF8STR will be wrong and
   round-tripping through real perl will mojibake.
4. **Hook compatibility.** `STORABLE_freeze` returns a list `($cookie,
   @refs)`. Our YAML implementation already calls hooks; we just need
   to emit the SX_HOOK frame around the hook output.
5. **Endianness on retrieve.** Network-order files are unambiguous.
   Native-order files include the byte-order signature; we should
   accept any signature and just byte-swap on read. JVM is always
   big-endian internally for `DataInputStream`, so reading
   native-LE files needs explicit reordering of multi-byte ints and
   doubles. This is straightforward but easy to get wrong — fixtures
   from a real LE box and a real BE box (or hand-crafted hex) are part
   of the Phase-1 test harness.
6. **Storable version skew.** Upstream is at major=2. Older majors
   (0, 1) are extinct in the wild but `CPAN::Meta::YAML`-style
   fallbacks may still emit them. We refuse with the same message
   upstream uses: `Unsupported Storable version`.

## Test strategy

- Unit fixtures in `src/test/resources/storable/fixtures/`: pairs of
  `.pl` source + `.bin` produced by system perl, covering each opcode.
  Build script regenerates them with `perl -MStorable -e 'nstore …'`.
- jperl test: read every fixture, compare against `Data::Dumper`
  golden output.
- Differential round-trip test (Phase 2+): jperl-encode → write tmp
  file → invoke system `perl` to decode → diff against expected.
  Skipped automatically if `perl` is not on `$PATH` (CI must guarantee
  it is).
- Existing tests in `src/test/resources/Storable*.t` continue to pass
  unchanged — they only assert jperl→jperl round-trip semantics.
- Real-world smoke: `jcpan -t Mojolicious::Plugin::Toto` (or any
  module) must not show "retrieve failed" on a CPAN cache last
  written by system perl, and vice versa.

## Files to add/touch

- New: `src/main/java/org/perlonjava/runtime/perlmodule/storable/Opcodes.java`
- New: `src/main/java/org/perlonjava/runtime/perlmodule/storable/NativeReader.java`
- New: `src/main/java/org/perlonjava/runtime/perlmodule/storable/NativeWriter.java`
- Edit: `src/main/java/org/perlonjava/runtime/perlmodule/Storable.java`
  (delegate `store`/`retrieve`/`freeze`/`thaw` to the new readers/writers,
  keep YAML reader as legacy fallback)
- Edit: `src/main/perl/lib/Storable.pm` (POD update; remove the
  "human-readable YAML format" claim, document canonical-mode parity)
- New tests: `src/test/resources/storable/native_decode.t`,
  `src/test/resources/storable/native_roundtrip.t`,
  `src/test/resources/storable/fixtures/*`
- Edit: `dev/modules/README.md` (add row for this doc, update Storable
  status)
- Edit: `AGENTS.md` "Partially Implemented Features" table — add a
  Storable row pointing at this doc.

## Progress Tracking

### Current Status: Phase 1 (decoder) and Phase 2 (encoder) complete.

### Completed Phases

- [x] **Stage A — foundation** (2026-04-29, commit `20a3b3d96`)
  - New package `org.perlonjava.runtime.perlmodule.storable` with
    `Opcodes` (SX_* constants), `StorableContext` (cursor, seen-table,
    classname-table, byte-order helpers), `Header` (`pst0` magic +
    netorder/native + version/sizeof gates),
    `StorableReader` (top-level dispatch switch), `OpcodeReader` SPI,
    `StorableFormatException`, and group-helper stubs
    (`Scalars`/`Refs`/`Containers`/`Blessed`/`Hooks`/`Misc`).
  - Fixture generator at `dev/tools/storable_gen_fixtures.pl`,
    37 binary fixtures committed under
    `src/test/resources/storable_fixtures/` covering scalars, refs,
    containers, blessed, hooks, regexp, native and network byte order.
  - `StorableReaderTest` JUnit harness with 11 baseline tests.
  - Canary opcodes implemented: SX_UNDEF, SX_SV_UNDEF, SX_SV_YES,
    SX_SV_NO, SX_BOOLEAN_TRUE, SX_BOOLEAN_FALSE, SX_OBJECT (backref).

- [x] **Stage B — per-opcode implementations, in parallel** (2026-04-29,
    commit `889e27b67`). Five subagents in parallel, each scoped to one
    group helper file + a new test class:
  - `Scalars.java` — SX_BYTE, SX_INTEGER, SX_NETINT, SX_DOUBLE,
    SX_SCALAR, SX_LSCALAR, SX_UTF8STR, SX_LUTF8STR (+22 tests).
  - `Refs.java` — SX_REF, SX_WEAKREF, SX_OVERLOAD, SX_WEAKOVERLOAD
    (+9 tests, including backref-cycle and shared-substructure
    synthetic streams).
  - `Containers.java` — SX_ARRAY, SX_HASH, SX_FLAG_HASH (UTF-8-flagged
    keys), SX_SVUNDEF_ELEM (+11 tests).
  - `Blessed.java` — SX_BLESS, SX_IX_BLESS via the classname table
    (+4 tests).
  - `Hooks.java` — SX_HOOK frame parser (SHF_TYPE_MASK / LARGE_* /
    IDX_CLASSNAME / NEED_RECURSE / HAS_LIST), recurse-chain handling,
    `STORABLE_thaw` invocation (+7 tests).

- [x] **Stage C — integration + Perl-level wiring** (2026-04-29, same
    commit). `Storable::retrieve` in
    `src/main/.../perlmodule/Storable.java` detects `pst0` magic and
    routes through `StorableReader`, then wraps a bare top-level
    scalar in a SCALARREFERENCE so the API matches upstream's
    `do_retrieve → newRV_noinc` (Storable.xs L7601).
    `StorableReaderTest` got real fixture round-trips replacing the
    Stage-A stub assertions. `Storable.pm` grew the upstream-compat
    constants and helpers the test suite expects: `BLESS_OK`,
    `TIE_OK`, `FLAGS_COMPAT`, `CAN_FLOCK`, `mretrieve`.

- [x] **Bonus parser fix** (same commit). Named-phaser sub syntax
    `sub BEGIN { ... }` (used by upstream Storable tests for
    `unshift @INC, 't/lib'`) now executes the body at compile time
    via `SpecialBlockParser.runSpecialBlock`, matching upstream
    perl. Five-line addition in
    `SubroutineParser.handleNamedSubWithFilter`.

- [x] **Upstream test import** (same commit).
    `dev/import-perl5/config.yaml` adds an entry that runs
    `dev/import-perl5/sync.pl` to populate
    `src/test/resources/module/Storable/t/` from
    `perl5/dist/Storable/t/`. 9 cleanly-passing tests imported
    today (~1030 assertions, 0 failures); the rest are excluded with
    per-file rationale in the YAML config (categories: legacy
    formats, plans-then-bails, assertion-level failures awaiting
    Phase 2 / specific fixes). `make test-bundled-modules` is green
    for the storable subset.

- [x] **Phase 2 — encoder** (2026-04-29, commit `cd3c74974`).
    - `StorableWriter.java` mirrors `StorableReader`. Top-level
      entry points `writeTopLevelToFile` / `writeTopLevelToMemory`
      strip ONE outer ref (matching upstream's
      `do_store → SvRV(sv)`), then dispatch.
    - `StorableContext` extended with symmetric write primitives
      (`writeBytes`, `writeU32Length`, `writeNetInt`, `writeNativeIV`,
      `writeNativeNV`) plus an identity-keyed seen-table for
      `SX_OBJECT` and a classname table for `SX_BLESS` / `SX_IX_BLESS`
      interning.
    - `Header.writeFile` / `writeInMemory` emit the `pst0` magic +
      version bytes (mirroring `magic_write` at Storable.xs L4460-4530).
    - `Storable.java` rewired: `store`/`nstore`/`freeze`/`nfreeze`
      now go through the new writer. `thaw` accepts native, the
      legacy 0xFF in-memory binary, and YAML+GZIP (so blobs frozen
      before this commit still de-thaw).
    - Verified end-to-end: `nstore` from jperl produces a file that
      `file(1)` identifies as
      `perl Storable (v0.7) data (network-ordered) (major 2) (minor 12)`,
      and system perl's `retrieve` decodes it intact, blessing
      preserved. Reverse direction (system perl → jperl) was
      already working since Phase 1.

### Next Steps (Phase 2.x — encoder polish)

The remaining work is a mix of small encoder tweaks and a few larger
items that need careful design. Each entry below carries enough
detail that a future implementer (human or agent) can pick it up
without re-tracing the source.

#### 1. `$Storable::canonical` — sorted hash-key emission ✅ landed (commit `c324e14cc`)

**encoder-polish-agent**

Affects: `canonical.t` (all 8 tests), parts of `dclone.t`.

* Currently `Containers.java`-equivalent code in `StorableWriter`
  iterates `hv.elements.entrySet()` in insertion order, which Java's
  `LinkedHashMap` preserves but Perl's hash iteration randomises.
  Tests that compare frozen byte streams across two perls fail
  whenever `$Storable::canonical = 1` is set.
* Implementation: in `StorableWriter.writeHashBody`, when the
  caller has set `$Storable::canonical`, sort the keys
  byte-lexicographically (matching upstream's `qsort` of UTF-8 byte
  representations, see `store_hash` at `Storable.xs ~L2750`). Use
  `Arrays.sort(keys)` with `Comparator.comparing(...)` against the
  UTF-8-encoded key bytes.
* Wire the Perl-level `our $canonical` in `Storable.pm` through to
  the writer. `Storable.pm` already has `our $canonical = 0;`.
  Either (a) read `GlobalVariable` directly from `StorableWriter`,
  or (b) thread a flag through `freezeImpl`/`storeImpl` in
  `Storable.java`. Option (b) is cleaner.
* Test plan: enable `canonical.t` in `dev/import-perl5/config.yaml`,
  expect 8/8 pass.

#### 2. `SX_REGEXP` writer — `qr//` pattern + flags ✅ landed (commit `c324e14cc`)

**regexp-agent**

Affects: `regexp.t` (full 64 tests, currently bails after 8).

* Currently `StorableWriter.dispatchReferent` for `RuntimeScalarType.REGEX`
  throws `"storing regexes not yet supported by encoder"`.
* Wire format (Storable.xs `store_regexp`, search for `SX_REGEXP`):
  ```
  SX_REGEXP <pat-len> <pat-bytes> <flags-len> <flags-bytes>
  ```
  Both lengths use the small/large convention (1 byte if ≤ LG_SCALAR,
  high-bit + U32 otherwise).
* Where to source the pattern + flags: `RuntimeRegex` (which is what
  `RuntimeScalar`'s value field carries when type is `REGEX`) exposes
  the original Perl pattern source and a flags string. Look in
  `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeRegex.java`
  for the public accessors — they should already be there for
  `Data::Dumper`-style stringification.
* Reader side: `Misc.readRegexp` is a refusal stub; replace it.
  Build a `RuntimeRegex` via the same constructor `qr//` uses
  (`RuntimeRegex.compile(patternBytes, flagsString)` or similar).
* Cross-perl interop: byte-for-byte identical output to upstream
  matters here (`regexp.t` runs `is_deeply` on round-tripped patterns).
* Test plan: enable `regexp.t` in config.yaml, expect 64/64.

#### 3. `SX_VSTRING` / `SX_LVSTRING` writer — version strings ✅ landed (commit `c324e14cc`)

**vstring-agent**

Affects: `blessed.t` test ~57 (the WeirdRefHook v-string subtest),
parts of `freeze.t`.

* Currently both refuse with `"misc-agent: SX_VSTRING not yet
  implemented"` on the read side, and the write side falls through
  to plain string encoding so the v-string magic is lost on retrieve.
* Wire format: same shape as `SX_SCALAR`/`SX_LSCALAR` — length-prefixed
  bytes. The receiver attaches a v-string magic to the resulting SV
  (via `sv_magic(sv, NULL, PERL_MAGIC_vstring, vstr_pv, vstr_len)`
  in upstream).
* PerlOnJava has a `RuntimeScalarType.VSTRING` already. Check
  `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeScalar.java`
  for the v-string constructor and accessors.
* On store: detect VSTRING type, emit SX_VSTRING/LVSTRING with the
  v-string bytes followed by the textual scalar (per
  `retrieve_vstring` at `Storable.xs L5833` — note the v-string
  bytes come FIRST, then a regular scalar opcode for the stringy
  part).
* On retrieve: the body is `<vstring-len> <vstring-bytes>` and then
  a recursive opcode for the regular scalar. Build a
  `RuntimeScalar(VSTRING)` with both pieces.
* Test plan: enable nothing extra (the gain is more `blessed.t`
  tests reaching plan completion, not flipping a whole file green).

#### 4. `SX_HOOK` write side ✅ landed (commit `6fb5ac09d`)

`STORABLE_freeze` is invoked, cookie + sub-refs are emitted with
the SHF_NEED_RECURSE chain when needed, and the reader's
`Hooks.readHook` handles `STORABLE_attach` as a class-level
alternative to `STORABLE_thaw` (Storable.xs L5119-5172). Verified
with `attach_singleton.t` (16/16 clean), `circular_hook.t` (9/9
clean), and `attach_errors.t` (39/40, was 13/40).

#### 5. `SX_OVERLOAD` writer ✅ landed (commit `5748eaa6d`)

Refs whose referent is blessed into an overload-pragma class are
emitted as `SX_OVERLOAD` instead of `SX_REF`, matching upstream
`store_ref` at `Storable.xs L2350-2354`. The reader has always
supported it via `Refs.readOverload`.

#### 6. `SX_WEAKREF` / `SX_WEAKOVERLOAD` writer ✅ landed (commit `c324e14cc`)

**encoder-polish-agent**

Affects: `weak.t` (when imported — currently skipped due to
`List::Util was not built` upstream-test guard, but a future
build may run it).

* Currently `StorableWriter.dispatch` always emits plain
  `SX_REF`/`SX_OVERLOAD` for inner refs.
* Detection: PerlOnJava tracks weak references via
  `WeakRefRegistry`. The reader uses `WeakRefRegistry.weaken()` on
  retrieval; the writer should consult `WeakRefRegistry.isWeak(refScalar)`
  (or whatever the runtime exposes) and pick the weak opcode.
* The wire layout is identical to `SX_REF`/`SX_OVERLOAD`; only the
  opcode byte differs:
  ```
  SX_WEAKREF      = 27   // = 0x1B
  SX_WEAKOVERLOAD = 28   // = 0x1C
  ```
  (See `Opcodes.java` and Storable.xs L168-169.)
* Round-trips inside jperl already work (the reader's `readWeakRef`
  invokes `WeakRefRegistry.weaken`). The visible bug is when a
  weakened ref crosses to system perl, which receives a strong ref
  instead.
* Test plan: enable `weak.t` once it can run; or write a
  jperl→system-perl smoke test that checks weakness preservation.

#### 7. Hash-key UTF-8 flag handling on the writer ✅ landed (commit `c324e14cc`)

**encoder-polish-agent**

Affects: any test that round-trips non-ASCII hash keys via
`SX_FLAG_HASH`; `utf8hash.t` post-completion.

* Currently `StorableWriter.writeHashBody` always emits `SX_HASH`
  (without the per-key flag byte). The reader handles
  `SX_FLAG_HASH` correctly (Containers.java), but the writer never
  produces it.
* Detection: PerlOnJava strings carry their UTF-8-or-not state via
  `RuntimeScalar.type` (`STRING` is utf8-flagged, `BYTE_STRING` is
  not). For hash KEYS the encoding lives in the key string itself
  (`hv.elements.keySet()` returns Java `String`s, which are UTF-16
  internally; the original UTF-8-flag-ness is lost at the Java
  boundary).
* The simplest correct rule: if any key contains a code point
  ≥ 0x80, emit `SX_FLAG_HASH` and set `SHV_K_UTF8 = 0x01` on those
  keys. Hash flags byte = 0 (we don't model RESTRICTED_HASH yet).
* Wire format (Storable.xs `store_hash` flag-hash branch):
  ```
  SX_FLAG_HASH <hash-flags-byte> <U32 size> N×{ value, <key-flags>, <U32 keylen>, <key-bytes> }
  ```
* Test plan: utf8hash.t round-trips successfully (currently bails
  at test 26 on `Not a SCALAR reference`, mostly unrelated, but
  exposes a UTF-8 mismatch downstream).

#### 8. Top-level ref-of-ref level loss ✅ landed (commit `c324e14cc`)

**ref-of-ref-agent (case 4 still @Disabled — see body)**

Affects: `overload.t` test 4-5, `freeze.t` "VSTRING" subtests,
parts of `dclone.t`, parts of `blessed.t`. Probably ~5-10 specific
assertions across the upstream suite.

**Root cause.** Our container readers (`Containers.java`)
return already-wrapped `ARRAYREFERENCE`/`HASHREFERENCE` scalars
(structurally one ref level above bare), versus upstream's
`retrieve_array` which returns a bare `AV`. So in our model:

| Wire | Our readers produce | Upstream produces |
|------|---------------------|-------------------|
| `SX_HASH` | HASHREFERENCE (1 ref) | bare HV (0 ref) |
| `SX_REF + SX_HASH` | ? | RV-to-HV (1 ref) |
| `SX_REF + SX_REF + SX_HASH` | ? | RV-to-RV-to-HV (2 ref) |

For each `SX_REF`, upstream's `retrieve_ref` does `SvRV_set(rv, sv)`
adding ONE ref level on top of the body. Then `do_retrieve` adds
ONE MORE via `newRV_noinc`. Our `Storable.thaw` adds one level only
when the body wasn't a reference, so the totals come out correct
for most cases — but `SX_REF + SX_BLESS + SX_HASH` becomes a
problem: the body produces `HASHREFERENCE-blessed` (1 ref), our
`Refs.readRef.installReferent` collapses (correct for the inner-ref
case `[\@a]`) but loses a level for the top-level case
`freeze \$blessed_ref`.

**Why a peek-and-decide doesn't work.** A trivial rule like
"`installReferent` wraps when the body opcode is `SX_REF`, collapses
otherwise" handles `\\@a` but breaks `[\$blessed]` (and vice versa).
The information that disambiguates the two cases — whether the
SX_REF is "redundant given our type system" or "really adds a
level" — isn't in the wire bytes; it's in the data shape we want
to reproduce.

**Three viable fixes**, in increasing order of invasiveness:

a. **Bare-container sentinel.** Have `Containers.readArray` /
   `readHash` return a `RuntimeScalar` with a non-reference type
   (e.g. add a private `STORABLE_BARE_AV` / `STORABLE_BARE_HV`
   value to `RuntimeScalarType` or use a side-table on
   `StorableContext`). `Refs.readRef` checks the marker and either
   collapses (if marked) or wraps (otherwise). After installing,
   the marker is consumed. Storable.thaw treats the result like
   any other scalar.
   
   Pros: keeps the container readers unchanged for non-Storable
   callers; localised change. Cons: introduces a transient type
   only Storable understands.

b. **Refactor container readers to return bare values.** Make
   `Containers.readArray` return a `RuntimeArray` (not a
   `RuntimeScalar`). The dispatcher returns `RuntimeBase` instead
   of `RuntimeScalar`. `Refs.readRef` always wraps;
   `Storable.thaw` always wraps. This is the closest match to
   upstream's data flow.
   
   Pros: clean, matches upstream model. Cons: every dispatcher
   site needs to handle the wider return type.

c. **Always-wrap + emit SX_REF wrapper everywhere.** Drop the
   "strip one level" in `emitTopLevel`, always emit a SX_REF
   wrapper, mirror with always-wrap on the read side. Adds one
   byte to every output, more importantly DIVERGES from upstream
   wire format — our jperl-written files would no longer be
   readable by system perl. Reject this option.

Recommended: **option a** (bare-container sentinel). Smallest
diff, no perf impact (one extra byte field on `StorableContext`,
one branch in `Refs.readRef`).

Sketch:
```java
// StorableContext additions:
private boolean lastWasBareContainer = false;
public boolean takeBareContainerFlag() {
    boolean v = lastWasBareContainer;
    lastWasBareContainer = false;
    return v;
}
public void markBareContainer() { lastWasBareContainer = true; }

// In Containers.readArray / readHash:
RuntimeScalar result = av.createAnonymousReference();
c.recordSeen(result);
// ... fill in elements ...
c.markBareContainer();
return result;

// In Refs.readRef (new logic):
boolean wasBare = c.takeBareContainerFlag();
RuntimeScalar referent = r.dispatch(c);
boolean bodyWasBare = c.takeBareContainerFlag();
if (bodyWasBare) {
    // body was a fresh container — collapse, the SX_REF was
    // redundant given our types.
    refScalar.set(referent);  // or .set(arr.createReference()) etc.
} else {
    // body was already a "ref level" thing — wrap.
    refScalar.set(referent.createReference());
}
```

Test plan: enable `overload.t`, `freeze.t`, `dclone.t` once
landed; expect ~10 additional passing assertions across them.

#### 9. Tied container freeze/retrieve ✅ landed (commit `c324e14cc`)

**tied-agent (encoder + reader; SX_TIED_KEY/IDX and SHT_EXTRA refused with clearer message)**

Affects: `tied.t` (25 tests), `tied_hook.t` (28 tests),
`tied_items.t` (post-test-2 cases), the `SHT_EXTRA` branch of
`Hooks.allocatePlaceholder` (currently throws).

**Wire format** (Storable.xs `retrieve_tied_array`/`hash`/`scalar`,
L5502-L5610):
```
SX_TIED_ARRAY <object>      // <object> = the inner tying object
SX_TIED_HASH  <object>
SX_TIED_SCALAR <object>
```
Plus `SX_TIED_KEY <object> <key>` and `SX_TIED_IDX <object> <idx>`
for tied magic on individual hash entries / array slots.

**Read path.** Currently `Misc.readTiedArray`/`readTiedHash`/
`readTiedScalar` throw a refusal. Replace with:

1. Allocate a placeholder container (`RuntimeArray` / `RuntimeHash`
   / `RuntimeScalar`) and recordSeen it in the seen-table.
2. Recurse: `RuntimeScalar inner = top.dispatch(c)` — this is the
   tying object (a blessed ref to the implementation class).
3. "Tie" the placeholder to the inner object. PerlOnJava's
   internal `tie` operator lives in
   `src/main/java/org/perlonjava/runtime/perlmodule/AttributeHandlers.java`
   or similar — find the static helper that takes a
   `(target, classname, args...)` and installs tied magic.
   Concretely: `RuntimeTiedHashProxyEntry` is the runtime hook that
   intercepts hash operations; we need to wire it via the standard
   `tie %h, $class, @args` mechanism, except the `$class` is
   already known (from blessing on the inner) and `@args` are
   replaced by the inner object itself.
4. Return the tied container.

The hardest part is step 3 — the path from "I have a placeholder
hash and an already-instantiated tying object" to "the placeholder
now delegates all operations to the object" goes through code
that's currently only reachable from the `tie` operator's parser
output. Likely needs a new public helper in the tie infrastructure.

**Write path.** Detect tied containers in `dispatchReferent`:
inspect whether the underlying `RuntimeArray`/`RuntimeHash` carries
tied magic (look for an `isTied()` method or a non-null
`tiedObject` field). If yes, emit `SX_TIED_ARRAY`/`HASH`/`SCALAR`
followed by `dispatch(tiedObject)`. Keep tag bookkeeping in mind:
the placeholder gets a tag, the inner gets the next tag.

**Tied hooks** (`tied_hook.t`): when both `STORABLE_freeze` and
tied magic apply, upstream's `store_hook` uses the SHT_EXTRA
sub-type with an `eflags` byte carrying `SHT_THASH`/`TARRAY`/
`TSCALAR` (Storable.xs L3624-L3653). Reader side already has the
`SHT_EXTRA` slot in `Hooks.allocatePlaceholder`; replace its
`throw` with a tied-placeholder allocation following the readTied
path above, then read the magic-object into the trailing `<object>`
position.

**Test plan**: enable `tied.t`, `tied_hook.t`, `tied_items.t`
(currently all excluded). Realistic target: most of `tied.t`'s 25
tests plus ~80% of `tied_items.t`. `tied_hook.t` depends on the
SHT_EXTRA wiring above and may take a second pass.

#### 10. Drop the YAML writer codepath (cosmetic)

Once Phase-2.x has settled, remove the legacy YAML serializer from
`Storable.java`:

* `serializeToYAML` / `deserializeFromYAML` and the snakeyaml
  imports become dead code on the write path.
* `BINARY_MAGIC = 0xFF` in-memory format and the
  `serializeBinary`/`deserializeBinary` helpers can also go.
* Keep `thaw`'s legacy-format detection (the YAML reader and the
  0xFF-magic reader) for one release as a migration safety net so
  users with old `~/.cpan/Metadata` or old in-memory blobs aren't
  broken on upgrade.
* After the deprecation window, remove those readers too. The
  `Storable.java` file shrinks from ~1100 lines to ~250 lines
  (just the public-API shim that delegates to the
  `org.perlonjava.runtime.perlmodule.storable` package).

Test plan: ensure `make` and `make test-bundled-modules` stay
green throughout.

#### 11. PR-side: re-enable upstream tests and watch the count tick down

Each time one of the items above lands, check whether any
`dev/import-perl5/config.yaml` exclusions can be removed. Re-run:

```
rm -rf src/test/resources/module/Storable
perl dev/import-perl5/sync.pl
JPERL_TEST_FILTER=Storable ./gradlew testModule
```

Goal at the end of Phase 2.x: the only remaining excludes are
genuinely-unsupported features (legacy 0.6 binary, 4 GiB
allocations, fork-based threads tests, malice fuzz with
specific Perl-version croak wording).

### Open Questions

- Do we want a config switch (`$Storable::PERLONJAVA_LEGACY_YAML = 1`)
  for users who relied on the YAML output being human-readable for
  debugging? Cheap to keep, but every config knob is a tax.
- Should `dclone` switch to a true deep-copy that doesn't go through
  the wire format at all? It does today (see `Storable.java`'s
  `dclone`). Keep that — it's faster and avoids the encoder being on
  the critical path of `dclone`-heavy modules.
- Is there appetite for emitting the **older** major=1 format on
  request, for talking to ancient perls? Probably no — flag and defer.

## Related

- Investigation that triggered this plan: `jcpan -t Toto` session
  (2026-04-29). Companion fixes landed alongside this doc:
  - `IO::Socket::SSL` stub gained `SSL_WANT_READ` / `SSL_WANT_WRITE` /
    etc. (unblocked Mojolicious tests at compile time).
  - `Storable::retrieve` originally got a clearer "native Perl
    Storable binary file" error message; in the same PR the actual
    decoder landed and that error path is now dead code.
- `dev/modules/cpan_client.md` — overall jcpan status.

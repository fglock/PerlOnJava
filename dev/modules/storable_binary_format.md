# Storable: Native Binary Format Support

## Status

**Both directions land — Phase 1 (decoder) and Phase 2 (encoder) complete.**
PerlOnJava's Storable now reads AND writes the native Perl Storable
binary format, byte-compatible with what upstream `perl` produces.
Files written by `jperl` can be read by system `perl` and vice versa.

What works today:
- `retrieve($file)` reads any current-format Storable file produced by
  upstream perl: scalars, refs, arrays, hashes, flag-hashes, blessed
  objects, cyclic references via `SX_OBJECT` backrefs, shared
  substructures, network and native byte order, UTF-8 keys, nested
  structures.
- `store` / `nstore` / `freeze` / `nfreeze` emit `pst0` (file) or the
  bare in-memory body, with all of the above shapes plus
  `SX_BLESS` / `SX_IX_BLESS` for blessed refs.
- `dclone` works (pure deep-copy, never touches the wire format).
- Native `STORABLE_thaw` hooks fire on read (`SX_HOOK` frame parser is
  complete).
- The `~/.cpan/Metadata` cache is fully shareable between jperl and
  system perl in either direction. CPAN-based tooling that exchanges
  Storable blobs (Cache::FileCache, Module::Build's `_build/` state,
  etc.) interoperates.
- ~889 upstream `t/*.t` assertions pass cleanly under
  `make test-bundled-modules` (integer.t alone is 875). Many more
  tests now reach the end of their plan than before; specific
  per-test exclusions are documented in `dev/import-perl5/config.yaml`.

What does NOT yet work (Phase 2.x follow-ups):
- `$Storable::canonical` — we currently emit hash keys in insertion
  order, not the canonical sort. Affects byte-level output equality
  with upstream when canonical mode is requested. Tests:
  `canonical.t`, `dclone.t`.
- `SX_REGEXP` encoding — refuses with a clear error today.
- `SX_VSTRING` / `SX_LVSTRING` encoding — same.
- `STORABLE_freeze` hook emission (read side works; write side treats
  hooked objects as plain blessed containers, which loses the cookie
  representation).
- `SX_WEAKREF` / `SX_OVERLOAD` — currently emitted as plain `SX_REF`.
  Round-trips internally but loses the magic for upstream consumers.

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

These are narrow follow-ups that don't block any major use case but
will turn more upstream `t/*.t` tests green:

1. `$Storable::canonical` — emit hash keys in sorted order. Currently
   we use insertion order. Affects `canonical.t` and parts of
   `dclone.t`.
2. `SX_REGEXP` writer — pattern + flags. Currently refuses with a
   clear error. `regexp.t` bails on this.
3. `SX_VSTRING` / `SX_LVSTRING` writer — version strings. Same shape.
4. `SX_HOOK` write side: `STORABLE_freeze` hook emission. Currently
   write side treats hooked objects as plain blessed containers, which
   loses the cookie representation.
5. `SX_WEAKREF` / `SX_WEAKOVERLOAD` writer — currently emits plain
   `SX_REF`. Round-trips inside jperl but loses the magic when the
   blob crosses to upstream perl.
6. Hash-key UTF-8 flag handling: emit `SX_FLAG_HASH` with `SHV_K_UTF8`
   when keys carry the UTF-8 flag, so non-ASCII keys round-trip
   exactly through upstream.
7. (Cosmetic) Drop the YAML writer codepath entirely, remove the
   `BINARY_MAGIC = 0xFF` legacy in-memory format. Only the legacy
   readers stay for one release as a migration safety net.

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

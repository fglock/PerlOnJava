# Storable: Native Binary Format Support

## Status

**Not started.** This document is the plan for replacing PerlOnJava's
YAML-based `Storable` implementation with one that reads/writes the
native Perl Storable binary format.

## Motivation

PerlOnJava ships its own `Storable` module
(`src/main/perl/lib/Storable.pm` + `src/main/java/org/perlonjava/runtime/perlmodule/Storable.java`)
that, for `store`/`nstore`/`retrieve`, serializes data to **YAML** rather
than the native Perl Storable binary format. The Java side declares this
intentionally:

> Storable module implementation using YAML with type tags for blessed
> objects. … This elegant approach leverages YAML's `!!` type tag system
> for object serialization …

The in-memory `freeze`/`thaw` path already grew a separate binary format
(magic byte `0xFF`, see `Storable.java`), but file I/O is still YAML.

This breaks every workflow that exchanges Storable data between `jperl`
and a real `perl`. Concretely observed during `jcpan -t Toto`
investigation (2026-04-29):

1. **CPAN.pm `~/.cpan/Metadata` cache.** CPAN persists its module index
   with `Storable::nstore`. Switching between `perl` and `jperl`
   always invalidates the cache:
   - jperl-written file → system perl: `File is not a perl storable at
     .../Storable.pm line 411`.
   - perl-written file → jperl: `retrieve failed: …` (now improved to a
     specific "native Perl Storable binary file" message).
   Each side then re-reads `02packages.details.txt.gz` and overwrites
   the cache, so a user alternating between the two perls pays the full
   index-rebuild cost on every invocation.

2. **distroprefs / persistent state.** CPAN.pm warns
   `'YAML' not installed, will not store persistent state` on system
   perl when only YAML metadata is present, then falls back further.

3. **Other tooling.** Anything that hands a `freeze`d blob to / from a
   real perl breaks: `Cache::FileCache`, DBI-cached statement metadata,
   `DBM::Deep` Storable values, build-system caches (e.g. ExtUtils
   `.packlist` adjacent state, `Module::Build`'s `_build/` Storable
   files), Sereal/Storable hybrid pipelines, etc.

4. **Cross-process IPC.** `Storable::freeze`/`thaw` is a common wire
   format between Perl processes. Mixed jperl/perl fleets cannot
   interoperate today.

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

### Current Status: Plan only — no implementation yet.

### Completed Phases
_(none)_

### Next Steps

1. Phase 1 task 1: `NativeReader` skeleton + `pst0` header parse.
2. Decide on package layout: nest under
   `org.perlonjava.runtime.perlmodule.storable` vs keep flat next to
   `Storable.java`. Lean toward the subpackage so the existing
   1100-line file doesn't keep growing.
3. Build the fixture-generation harness so Phase 1 has real bytes to
   parse from day one (don't write the parser blind against the XS
   source).

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
  - `Storable::retrieve` now identifies native-binary input and
    explains the incompatibility instead of returning a generic
    YAML-parser error.
- `dev/modules/cpan_client.md` — overall jcpan status.

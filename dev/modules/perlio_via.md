# PerlIO::via — functional implementation plan

## Motivation

`./jcpan -t Redis` cascades into a dependency chain:

```
Redis → IO::Socket::Timeout → PerlIO::via::Timeout → PerlIO::via
```

`PerlIO::via` in upstream perl is an XS bootstrap (just
`XSLoader::load()`). The real work lives in `ext/PerlIO-via/via.xs`,
which teaches the C layer-dispatch core to route IO operations through
Perl methods (`PUSHED`, `POPPED`, `OPEN`, `FDOPEN`, `SYSOPEN`,
`FILENO`, `READ`, `WRITE`, `FILL`, `CLOSE`, `SEEK`, `TELL`, `UNREAD`,
`FLUSH`, `SETLINEBUF`, `CLEARERR`, `ERROR`, `EOF`, `BINMODE`, `UTF8`)
on the class named inside `:via(Foo)`.

PerlOnJava does not ship `PerlIO::via` at all today. CPAN's
resolver therefore tries to "install" it from
`SHAY/perl-5.42.2.tar.gz`, fails, marks the whole chain `NA`, and
`Redis`'s `t/00-compile.t` can't even `require` itself.

**Near-term (separate PR)** — ship a stub `src/main/perl/lib/PerlIO/via.pm`
(mirroring the existing `PerlIO::encoding` stub) so the dependency
chain resolves. At the same time, make the layer parser in Java throw
a clear error when `:via(Foo)` is actually used at `open`/`binmode`
time. That gives us a loud failure when a real call site needs the
layer, without breaking modules that only `use PerlIO::via` at compile
time.

This document is about the **follow-up**: a real, functional
`PerlIO::via` that actually dispatches IO through a user-supplied Perl
class.

## Current layer infrastructure in PerlOnJava

Relevant files:

```
src/main/java/org/perlonjava/runtime/io/
    IOLayer.java              interface: processInput / processOutput / reset
    LayeredIOHandle.java      parseAndSetLayers / splitLayers / addLayer
    EncodingLayer.java        :encoding(...) / :utf8
    CrlfLayer.java            :crlf
    IOHandle.java             read / write / eof / tell / seek / close / flush / sync
    RuntimeIO.java            ties a LayeredIOHandle to a filehandle
```

Key properties of the existing design:

1. **Layers are pure string transforms.** `IOLayer` exposes
   `processInput(String)` / `processOutput(String)` where each char is
   one byte (0-255). Layers are composed into two `Function<String,
   String>` pipelines (`inputPipeline`, `outputPipeline`) inside
   `LayeredIOHandle`.
2. **Layers see bytes only after the delegate returns them.** They
   cannot intercept `open`, `seek`, `tell`, `eof`, `close`, or
   `fileno`. Those calls go straight through to the delegate handle.
3. **Unknown layer names currently throw**
   `IllegalArgumentException("Unknown layer: " + layerSpec)` in
   `addLayer` — *but* `splitLayers` only special-cases `encoding(...)`,
   so `via(Foo)` is parsed as a single token and reaches `addLayer`,
   where it falls into the default arm and (should) throw. In practice
   an `open(..., "<:via(Foo)", ...)` today returns success because of a
   separate path in open-mode parsing; the near-term loud-fail work
   plugs that hole.

## What upstream PerlIO::via expects

A layer class implements some subset of:

| Method | Direction | Return | Notes |
|--------|-----------|--------|-------|
| `PUSHED($class, $mode, $fh)` | on layer push | blessed obj or `$class` or `-1` | always; gets called before open |
| `POPPED($obj, $fh)` | on layer pop | ignored | cleanup |
| `OPEN($obj, $path, $mode, $fh)` | on open | truthy = we opened it | if absent, lower layer opens |
| `FDOPEN($obj, $fd, $fh)` | on fdopen | truthy | optional |
| `SYSOPEN($obj, $path, $imode, $perm, $fh)` | | truthy | optional |
| `BINMODE($obj, $fh)` | on binmode | 0 / -1 / undef | undef = pop me |
| `UTF8($obj, $belowFlag, $fh)` | just after PUSHED | bool | |
| `FILENO($obj, $fh)` | | int | default = `fileno($fh)` |
| `READ($obj, $buffer, $len, $fh)` | read | octets placed | default = use FILL |
| `WRITE($obj, $buffer, $fh)` | write | octets written | required for writers |
| `FILL($obj, $fh)` | read | string or undef | default read path |
| `CLOSE($obj, $fh)` | close | 0 / -1 | |
| `SEEK($obj, $posn, $whence, $fh)` | seek | 0 / -1 | |
| `TELL($obj, $fh)` | tell | pos | |
| `UNREAD($obj, $buffer, $fh)` | | octets | default = temp push-back layer |
| `FLUSH($obj, $fh)` | flush | 0 / -1 | |
| `SETLINEBUF($obj, $fh)` | | — | |
| `CLEARERR($obj, $fh)` | | — | |
| `ERROR($obj, $fh)` | | bool | |
| `EOF($obj, $fh)` | | bool | default derived from FILL/READ |

There are two important semantic rules:

1. **`$fh` is the handle *below* this layer**, given as a glob. The
   callback reads/writes through that glob to reach the next layer
   down. This implies layers form a linked list at runtime.
2. **`READ`/`WRITE` return octet counts, not transformed strings.** The
   callback mutates `$buffer` in place via an aliased argument for
   `READ`. The existing PerlOnJava `IOLayer.processInput/Output`
   pipeline model does not match this shape.

## Gap analysis

Mapping upstream semantics onto PerlOnJava:

| Concern | Current state | Gap for `:via` |
|---------|---------------|---------------|
| Name lookup `via(Foo)` → class | Not parsed | Add `splitLayers` case for `via(...)`; resolve class name (prefixing `PerlIO::via::` if bare class not loaded, matching upstream). |
| Lifecycle (PUSHED/POPPED) | `IOLayer.reset()` only | Need a Java class that holds the layer's Perl object, invokes PUSHED on creation, POPPED on removal. |
| Layer-below handle | `IOLayer` has no access to delegate | Need to expose an "inner handle" glob to the Perl callback. Requires turning a `LayeredIOHandle` slice into a Perl `GLOB` on demand. |
| READ via FILL | `processInput(String)` is a pure transform | Need a `ViaLayer` that repeatedly calls `FILL` (or `READ`) on the Perl side and feeds the result into the pipeline's byte stream. This is a *pull* model, whereas existing layers are *push* transforms. |
| WRITE | `processOutput(String)` returns transformed bytes | Rework so `ViaLayer.processOutput` calls `WRITE($obj, $buf, $fh_below)` and returns `""` (since the callback itself writes downward), or short-circuit to bypass the downstream pipeline entirely. |
| SEEK/TELL/EOF/CLOSE/FLUSH | Pass-through to delegate | Need per-op hooks on `IOLayer` (new interface methods with default no-op implementations) so `LayeredIOHandle` can consult the topmost `ViaLayer` before delegating. |
| FILENO | `IOHandle.fileno()` goes straight to delegate | Add optional layer override. |
| BINMODE | `binmode` reparses layers | On `:raw` or pop, must call `POPPED` on the Via layer. |
| UTF8 flag | Not modeled | Probably skip for first cut; document as known gap. |
| Error propagation | Layers don't have errno | Map `die` inside callbacks to `$!`/`$@` on the outer op, with a reasonable default (ETIMEDOUT / EIO) on `-1` returns. |
| Push-back (UNREAD) | No support | Document as unimplemented; default upstream behavior uses a temp layer — out of scope for v1. |

## Proposed design

### 1. Perl side — `src/main/perl/lib/PerlIO/via.pm`

Minimal module. `use PerlIO::via;` has no args in upstream; the
package's job is just to exist so the `:via(...)` layer parser can
lazy-load classes. Keep the stub from the near-term PR, bump VERSION
to match upstream (`0.19`). All real logic lives in Java.

### 2. Java side — `ViaLayer`

New file `src/main/java/org/perlonjava/runtime/io/ViaLayer.java`
implementing `IOLayer` *plus* new optional hooks (see §3). Holds:

```
RuntimeScalar perlObject;   // blessed ref returned by PUSHED
String className;           // e.g. "PerlIO::via::Timeout"
RuntimeScalar belowGlob;    // tied to the layer below, passed as $fh
EnumSet<Method> implemented;// which callbacks the class defines
```

Construction flow inside `LayeredIOHandle.addLayer("via(Foo)")`:

1. Resolve class — if `Foo::` has no symbol table, try
   `PerlIO::via::Foo`; if both fail, throw.
2. `require` the class via the existing module-loader entry point.
3. Introspect which methods exist (`can(...)`) and cache the
   `EnumSet` so hot paths don't re-lookup.
4. Build a `belowGlob` that mirrors the current inner handle (a
   `BorrowedIOHandle` wrapped in a `RuntimeIO` wrapped in a `GLOB`).
5. Call `Foo->PUSHED($mode, $belowGlob)` and stash the returned
   blessed ref as `perlObject`. `-1` return must propagate as an
   `open` failure with `$!` set.

### 3. Extend `IOLayer` with optional hooks

Add default methods so existing `CrlfLayer` / `EncodingLayer` don't
need to change:

```java
default boolean isViaLayer() { return false; }
default RuntimeScalar onOpen(String path, String mode) { /* let
    LayeredIOHandle open normally */ return null; }
default int onRead(byte[] buf, int len) { return -2; } // -2 = "not
    handled, use processInput pipeline"
default int onWrite(byte[] buf, int len) { return -2; }
default int onSeek(long off, int whence) { return -2; }
default long onTell() { return -2; }
default boolean onEof() { return false; }
default int onClose() { return -2; }
default int onFlush() { return -2; }
default int onFileno() { return -2; }
default void onBinmode() {}
default void onPopped() {}
```

`LayeredIOHandle.read / write / seek / tell / eof / close / flush /
fileno / binmode` check the topmost layer's hook first; `-2` means
"fall through to the existing pipeline", any other value is the
result.

### 4. Below-handle wrapper

The callback's `$fh` argument must let the user call
`sysread($fh, ...)` / `syswrite($fh, ...)` / `sysseek($fh, ...)` and
have those go to the layer *below* the Via layer. Introduce a new
`BelowLayerIOHandle` (thin adapter over `LayeredIOHandle` that skips
the topmost N layers) and expose it through a glob created at layer-
push time. `BorrowedIOHandle` already exists — extend it, or add a
sibling, to carry the "start at layer N" offset.

### 5. Open / read / write flow

```
open(FH, "<:via(Foo)", $path)
  1. parse layers  → [":raw", ":via(Foo)"]
  2. open raw handle at bottom
  3. wrap in LayeredIOHandle
  4. push ViaLayer(Foo):
       require Foo
       $obj = Foo->PUSHED("<", $fh_below)
       if ($obj == -1) return open failure
       if (Foo->can("OPEN")) {
           $obj->OPEN($path, "<", $fh_below) or return open failure
       } else {
           // lower layer already opened it in step 2
       }
       if (Foo->can("UTF8") && $obj->UTF8($below_utf8, $fh_below)) {
           push :utf8 on top
       }

read:  prefer $obj->READ  → fallback $obj->FILL  → fallback delegate
write: prefer $obj->WRITE                        → fallback delegate
seek:  prefer $obj->SEEK                         → fallback delegate
close: call $obj->CLOSE if present, then POPPED, then close delegate
```

### 6. Hot-path cost

Every `read` / `write` becomes a Perl method call. That is
intrinsically slow. Two mitigations:

- Cache `can(...)` lookups at push time — no repeated symbol-table
  probes.
- For layers that implement `FILL` (the common case), read in larger
  chunks (e.g. 8 KiB) and amortize the call across the pipeline's
  `processInput` consumers.

This is acceptable because `:via` users are opting into a
Perl-implemented layer on purpose.

### 7. Errors

- Any `die` in a callback is caught inside the `ViaLayer` adapter,
  stashed into `$@`, and turned into the documented failure mode
  (`-1` / `undef` / false depending on which method).
- An un-caught `die` in PUSHED is a propagated error; the `open` call
  returns false and `$!` is set to `EIO` (matching perl's behavior
  when PUSHED returns `-1` from an XS layer).

## Test strategy

1. **PerlIO-via's own tests** — once functional, enable
   `perl5/ext/PerlIO-via/t/via.t` under `perl5_t/ext/PerlIO-via/`.
   Fork-heavy parts skip.
2. **PerlIO::via::QuotedPrint** (core since 5.8) — smallest realistic
   user. Round-trip a fixture file.
3. **PerlIO::via::Timeout** — what Redis actually loads. Without a
   live Redis, exercise `t/00-compile.t` and the `setsockopt`-based
   path, which doesn't require the layer to *do* anything — but does
   require it to load.
4. **`jcpan -t Redis`** — the motivating end-to-end. Target is
   `Result: PASS` on the compile-only tests; live-server tests will
   still skip because PerlOnJava has no fork-based test harness for
   spinning up a Redis instance.
5. Add a unit test under `src/test/perl/` that writes a tiny Perl
   layer class and asserts PUSHED/READ/WRITE/CLOSE all fire in order.

## Scope boundaries (v1 explicitly excludes)

- `UNREAD` (push-back layer synthesis)
- `UTF8` flag propagation into the PerlIO core's SvUTF8 state
- `FDOPEN` / `SYSOPEN` (return false → caller falls back to lower
  layer, which works fine for the common cases)
- Stackable `:via(...)` (N>1) in a single open — legal in upstream
  but rare; first cut may reject if it's non-trivial in the pipeline
  model
- Binmode-induced re-push (`binmode $fh, ":via(Foo)"` on an already-
  open handle) — acceptable to defer if the push path is only wired
  into `open`

Each of these should either fall back cleanly or throw a clear error
with JPERL_UNIMPLEMENTED honored.

## Effort estimate

Rough sizing, assuming the near-term stub + Java loud-fail PR has
already landed:

| Piece | Size |
|-------|------|
| `IOLayer` interface extension + existing layers adjusting to defaults | XS |
| `ViaLayer.java` (PUSHED/POPPED, READ/FILL, WRITE, CLOSE) | M |
| `BelowLayerIOHandle` / glob wrapper | S |
| `LayeredIOHandle` hook dispatch (read/write/seek/tell/eof/close/flush/fileno/binmode) | M |
| `splitLayers` + addLayer plumbing for `via(...)` | S |
| Error mapping / `$!` integration | S |
| Tests (unit + `perl5_t` enablement + `jcpan -t Redis` check) | M |

Expect ~1-2 days of focused work, the biggest risk being the
glob-wrapping "below handle" — that has to interoperate cleanly with
`sysread` / `syswrite` inside the Perl callback.

## Progress tracking

### Current status: planning only

### Completed phases

_none yet_

### Next steps

1. Land the near-term PR: stub `PerlIO/via.pm` + Java loud-fail for
   `:via(...)` layer-parse (separate, small PR — unblocks `jcpan -t
   Redis` compile phase).
2. Spike `ViaLayer` against `PerlIO::via::QuotedPrint` to validate
   the hook shape before doing the full `LayeredIOHandle` surgery.
3. Full implementation per §3-§5.
4. Re-run `jcpan -t Redis`, capture result in this doc.

### Open questions

- Should the below-handle be a real `GLOB` (so `readline($fh)` works
  inside callbacks), or a minimal `IO::Handle`-shaped thing? Upstream
  passes a glob; mimicking it keeps existing CPAN layers happy but
  requires a bit more plumbing.
- How do we surface `$!` from Java-level IO failures to a Perl-level
  callback that uses `die`? Probably the same mechanism the rest of
  PerlOnJava's IO uses (look at `IOOperator.java` for precedent).

## Related

- Near-term stub: `src/main/perl/lib/PerlIO/via.pm` (TBD)
- Similar stub precedent: `src/main/perl/lib/PerlIO/encoding.pm`
- Motivating module: `dev/modules/...` (Redis — no doc yet; add one
  alongside this when the near-term PR lands)
- Layer plumbing: `src/main/java/org/perlonjava/runtime/io/LayeredIOHandle.java`

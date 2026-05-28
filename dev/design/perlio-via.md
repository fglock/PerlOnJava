# PerlIO::via Runtime Layers

**Status:** Design ready (2026-05-28). Implementation pending.

## Summary

PerlOnJava can currently load `PerlIO::via`, but it cannot execute a
runtime `:via(...)` layer. `LayeredIOHandle` recognizes `via(...)` syntax
and deliberately throws a clear unimplemented error instead of treating the
layer as a silent no-op.

Functional support requires bridging Java-side layered I/O back into Perl
callbacks such as `PUSHED`, `READ`, `FILL`, `WRITE`, `CLOSE`, and `FILENO`.
The first supported version should target common CPAN layers and the concrete
modules already blocked by this gap: `PerlIO::via::QuotedPrint`,
`PerlIO::via::Timeout`, `IO::Socket::Timeout`, Redis, and DBI trace-style
handles.

## Current State

Relevant runtime pieces:

```text
src/main/perl/lib/PerlIO/via.pm
src/main/java/org/perlonjava/runtime/io/IOLayer.java
src/main/java/org/perlonjava/runtime/io/LayeredIOHandle.java
src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeIO.java
src/main/java/org/perlonjava/runtime/operators/IOOperator.java
```

Today:

- `PerlIO/via.pm` is a loading-only stub so CPAN prerequisites can resolve.
- `LayeredIOHandle.splitLayers()` preserves `via(Foo::Bar)` as one layer token.
- `LayeredIOHandle.addLayer()` throws `PerlJavaUnimplementedException` for
  `via(...)`.
- Existing layers are push-style string transforms: `processInput(String)` and
  `processOutput(String)`.
- `RuntimeIO.binmode()` unwraps any existing `LayeredIOHandle`, builds a new
  one, calls `LayeredIOHandle.binmode()`, and currently ignores that method's
  success/failure value.

Upstream `PerlIO::via` has different semantics:

- A layer object is created by `PUSHED($class, $mode, $below_fh)`.
- The callback's `$below_fh` is a real handle below the via layer, usable with
  `sysread`, `syswrite`, `fileno`, and friends.
- `READ` mutates an aliased buffer scalar and returns an octet count.
- `WRITE` writes through the below handle and returns an octet count.
- Optional lifecycle and metadata methods include `POPPED`, `CLOSE`, `FLUSH`,
  `EOF`, `SEEK`, `TELL`, `FILENO`, `BINMODE`, and `UTF8`.

## Target Semantics

V1 must support:

- `open my $fh, "<:via(Foo)", $path` and `open my $fh, ">:via(Foo)", $path`.
- `binmode($fh, ":via(Foo)")` on an existing handle. This is required by
  `PerlIO::via::Timeout`.
- Class resolution for `via(Foo)` by trying `Foo` first, then
  `PerlIO::via::Foo` if the exact package cannot be loaded or resolved.
- Exactly one `:via(...)` layer per handle.
- Existing non-via layers below the via layer when they already work through
  the current `LayeredIOHandle` pipeline.
- A clear unimplemented error for a second `:via(...)` layer or a layer above a
  via layer that cannot be represented correctly yet.

V1 callback support:

| Callback | V1 behavior |
|----------|-------------|
| `PUSHED` | Required. Called when the layer is pushed. Store the returned object. `-1` or false fails the layer push. |
| `POPPED` | Optional. Called when the layer is removed or the handle closes. |
| `READ` | Optional. Preferred read callback. Pass an aliased mutable buffer scalar, requested length, and below handle. |
| `FILL` | Optional fallback when `READ` is absent. Buffer returned data internally. |
| `WRITE` | Optional. Preferred write callback. If absent, write directly to the below handle. |
| `CLOSE` | Optional. Called before popping the layer and closing the delegate. |
| `FLUSH` | Optional. Called before delegate flush when present. |
| `FILENO` | Optional. Falls back to the below handle's `fileno`. |
| `EOF` | Optional. Falls back to delegate EOF. |
| `SEEK` / `TELL` | Optional. Falls back to delegate behavior. |
| `BINMODE` | Optional. Called when an existing via layer is replaced by a new binmode operation. |

V1 explicitly defers:

- `UNREAD` and synthesized push-back layers.
- Full UTF8 flag propagation into Perl scalar internals.
- Multiple stacked `:via(...)` layers.
- `FDOPEN` and `SYSOPEN` callbacks.
- Support for arbitrary layers above a via layer.

## Runtime Design

### IOLayer hooks

Extend `IOLayer` with default optional hooks. Existing `CrlfLayer` and
`EncodingLayer` keep using `processInput` and `processOutput` unchanged.
Returning `null` means "not handled; use the existing path".

```java
default RuntimeScalar onRead(int maxBytes, Charset charset) { return null; }
default RuntimeScalar onWrite(String data) { return null; }
default RuntimeScalar onFlush() { return null; }
default RuntimeScalar onClose() { return null; }
default RuntimeScalar onFileno() { return null; }
default RuntimeScalar onEof() { return null; }
default RuntimeScalar onTell() { return null; }
default RuntimeScalar onSeek(long pos, int whence) { return null; }
default void onPopped() {}
default boolean interceptsIO() { return false; }
```

`LayeredIOHandle` consults the topmost intercepting layer before falling back
to the current transform pipeline and delegate operations.

### ViaLayer

Add `ViaLayer` under `org.perlonjava.runtime.io`. It implements `IOLayer` and
owns:

- resolved package name, for example `PerlIO::via::Timeout`;
- Perl object returned by `PUSHED`;
- cached method table flags for supported callbacks;
- below-layer `RuntimeIO` glob;
- small read buffer for `FILL` results that exceed the current read request;
- a popped/closed guard so `POPPED` runs at most once.

Construction:

1. Resolve and `require` the class.
2. Build a below-layer handle for the current stack below this via layer.
3. Call `PUSHED($class, $mode, $below_glob)`.
4. Treat `undef`, false, and numeric `-1` as push failure.
5. Cache which callback methods exist using `InheritanceResolver` and ignore
   AUTOLOAD-only hits for optional callback discovery.

Method invocation should use the same call machinery as tied handles:
`RuntimeCode.call()` for object method calls and `RuntimeContextType.SCALAR`
unless the callback explicitly needs list context.

### Below-layer handle

Add a non-owning below-layer wrapper rather than passing the original handle
directly. The wrapper must expose a real `RuntimeIO`/glob because upstream via
callbacks expect a filehandle, not an arbitrary object.

The wrapper:

- delegates reads, writes, `sysread`, `syswrite`, `fileno`, `seek`, `tell`,
  `eof`, and socket operations to the layer stack below the via layer;
- never closes the real delegate when the below handle is closed by callback
  code;
- shares the same file descriptor number for `fileno` and select/poll support;
- prevents recursive calls back into the same `ViaLayer`.

Implementation options are acceptable only if they preserve those semantics:
extend `BorrowedIOHandle` with a layer-depth aware delegate, or add a sibling
`BelowLayerIOHandle`.

### Layer parsing and stack rules

Keep `splitLayers()` preserving both `encoding(...)` and `via(...)`.

When `addLayer("via(Foo)")` runs:

- reject if the current stack already contains a `ViaLayer`;
- create `ViaLayer` with the current lower stack snapshot;
- append it to `activeLayers`;
- do not add it to the string transform pipeline;
- mark the handle as having an intercepting top layer.

If later layers are added after a `ViaLayer`, accept only no-op layers
(`raw`, `bytes`, `unix`) in v1. Reject other layers with
`PerlJavaUnimplementedException` so unsupported stacking does not silently
mis-handle data.

### Read/write behavior

Read path:

1. If the topmost layer intercepts I/O, call `onRead(maxBytes, charset)`.
2. `ViaLayer.onRead()` prefers `READ`.
3. For `READ`, pass a mutable buffer scalar initialized to `""`, the requested
   length, and the below glob. Return the first `count` bytes/chars from the
   buffer, matching PerlOnJava's existing byte-string convention.
4. If `READ` is absent and `FILL` exists, call `FILL($obj, $below_glob)` and
   buffer any excess data.
5. If neither exists, fall back to the below handle and then existing lower
   transforms.

Write path:

1. If the topmost layer intercepts I/O, call `onWrite(data)`.
2. `ViaLayer.onWrite()` calls `WRITE($obj, $data, $below_glob)` when present.
3. If `WRITE` is absent, write to the below handle directly.
4. Return truthy success when the callback writes the complete data; set `$!`
   and return false on callback failure.

### Binmode behavior

Change `RuntimeIO.binmode()` to return a `RuntimeScalar` status and update
`IOOperator.binmode()` to return false/undef on layer push failure instead of
always returning the filehandle.

Before replacing an existing `LayeredIOHandle`, call `onPopped()` on active
layers from top to bottom. Then build the new `LayeredIOHandle` over the raw
base handle and apply the requested layers. If applying the new layers fails,
leave the original handle installed and return false.

This preserves current successful behavior while making failed `:via(...)`
pushes observable and safe.

### Error handling

- `die` from a callback sets `$@` and makes the outer operation fail.
- Callback false / `-1` results set `$!` to `EIO` unless callback code already
  set `$!`.
- Timeout modules that set `$! = ETIMEDOUT` must preserve that value.
- Layer parser unsupported cases should still use `PerlJavaUnimplementedException`
  and honor the existing `JPERL_UNIMPLEMENTED` warning path.

## Implementation Phases

1. **Hook scaffolding**
   - Add optional `IOLayer` hooks and route `LayeredIOHandle` read, write,
     close, flush, fileno, eof, tell, and seek through the topmost intercepting
     layer.
   - Add no behavior change for existing layers.

2. **ViaLayer lifecycle**
   - Implement class resolution, `require`, method discovery, `PUSHED`, cached
     callback flags, and `POPPED`.
   - Convert the current `via(...)` error path into `ViaLayer` construction.

3. **Below-handle wrapper**
   - Create the real glob-backed below handle.
   - Verify `sysread`, `syswrite`, `fileno`, and close isolation from a via
     callback.

4. **Read/write callbacks**
   - Implement `READ`, `FILL`, `WRITE`, `CLOSE`, `FLUSH`, `FILENO`, `EOF`,
     `SEEK`, and `TELL` handling.
   - Add error and `$!` propagation.

5. **Binmode and CPAN integration**
   - Make `binmode($fh, ":via(Foo)")` work on existing handles.
   - Re-enable the skipped `PerlIO::via::Timeout` runtime test by removing the
     skip from the CPAN patch or deleting the patch if the upstream tests pass
     without it.

## Test Plan

Unit tests should live under `src/test/resources/unit/` and use `timeout` for
any direct `jperl` verification.

Required scenarios:

- `use PerlIO::via` still loads.
- Unknown via class fails clearly.
- `open my $fh, ">:via(TestLayer)", $file` calls `PUSHED`, `WRITE`, `CLOSE`,
  and `POPPED` in order.
- `open my $fh, "<:via(TestLayer)", $file` supports both `READ` and `FILL`.
- `binmode($socket_or_file, ":via(TestLayer)")` pushes a layer onto an existing
  handle.
- Callback `$below_fh` works with `sysread`, `syswrite`, `fileno`, `seek`, and
  `tell`.
- Callback `die` and `-1` returns fail the outer operation and preserve useful
  `$!` / `$@`.
- Existing `io_layers.t`, encoding, crlf, raw, tied handle, dup, and socket
  tests keep passing.

CPAN/end-to-end checks:

- `timeout 600 ./jcpan -t PerlIO::via::Timeout`
- `timeout 600 ./jcpan -t PerlIO::via::QuotedPrint`
- `timeout 1200 ./jcpan -t Redis` once the immediate via targets pass
- `timeout 1200 make`

## Progress Tracking

### Current Status: design ready, implementation pending

### Completed Phases

- [x] Near-term loading stub and loud `:via(...)` failure (2026-05-28)
  - Added `src/main/perl/lib/PerlIO/via.pm`.
  - Updated `LayeredIOHandle` to fail clearly when `:via(...)` is used.
- [x] `PerlIO::via::Timeout` CPAN install/test workaround (2026-05-28)
  - Added a bundled CPAN distropref and patch for `PerlIO-via-Timeout-0.32`.
  - The patch skips the unsupported runtime socket test under `jperl` and
    removes the unused `Test::TCP` build prerequisite while via runtime support
    is pending.

### Next Steps

1. Implement hook scaffolding with no behavior change for existing layers.
2. Implement `ViaLayer` lifecycle and below-handle creation.
3. Implement read/write/lifecycle callbacks.
4. Enable `binmode($fh, ":via(...)")` on existing handles.
5. Remove or narrow the `PerlIO::via::Timeout` CPAN skip once its runtime test
   passes.
6. Record final verification results here.

### Open Questions

None for v1. The design chooses a real glob-backed below handle, supports
single via layers, includes binmode push, and defers full PerlIO parity until
the common CPAN path is working.

## Related

- Module note: `dev/modules/perlio_via.md`
- Stub module: `src/main/perl/lib/PerlIO/via.pm`
- Layer plumbing: `src/main/java/org/perlonjava/runtime/io/LayeredIOHandle.java`
- Tied handle method dispatch precedent:
  `src/main/java/org/perlonjava/runtime/runtimetypes/TieHandle.java`

# Fix IPC::Open2/IPC::Open3 — fileno, sysread, IO::Select Compatibility

## Status: IMPLEMENTED

**Branch**: `feature/fix-ipc-open3`
**PR**: https://github.com/fglock/PerlOnJava/pull/452
**Date**: 2026-04-06
**Motivation**: Net::SSH `ssh_cmd()` (and other CPAN modules) use `IPC::Open3` with
`IO::Select` + `sysread()`. These currently fail silently because open3 handles
lack `fileno()` and `sysread()` support.

## Problem Summary

IPC::Open2/Open3 spawn child processes via `ProcessBuilder` and wrap the
child's I/O streams in `ProcessInputHandle` / `ProcessOutputHandle`.
These handle types are missing several capabilities that standard Perl code
expects:

| Capability | PipeInputChannel (pipe-open) | ProcessInputHandle (open3) | Impact |
|---|---|---|---|
| `fileno()` | returns undef | returns error | IO::Select can't add handle |
| `sysread()` | implemented | **not implemented** | sysread returns 0 / error |
| `syswrite()` | n/a (read-only) | n/a (ProcessOutputHandle) | syswrite fails on write handle |
| FileDescriptorTable registration | not registered | **not registered** | 4-arg select() can't find handle |
| `isReadReady()` support | not handled | **not handled** | select poll loop skips handle |
| `close()` sets `$?` | yes (via waitFor) | **no** | waitpid semantics incomplete |

## Confirmed Bugs (with reproduction)

### Bug 1: `fileno()` returns undef/error on open3 handles

`ProcessInputHandle` and `ProcessOutputHandle` inherit the default `fileno()`
from `IOHandle`, which calls `handleIOError("fileno operation is not supported.")`.

```perl
my ($wtr, $rdr);
open3($wtr, $rdr, undef, "echo", "hello");
fileno($rdr);  # Returns undef — should return a valid fd number
```

**Root cause**: `IPCOpen3.setupReadHandle()` / `setupWriteHandle()` create a
RuntimeGlob + RuntimeIO wrapping the ProcessInputHandle/ProcessOutputHandle,
but never register the handle in `FileDescriptorTable` or assign a fileno to the
`RuntimeIO`.

**Consequence**: `IO::Select->add($rdr)` calls `fileno($rdr)`, gets undef, and
silently drops the handle — `$sel->count` is 0 instead of 1.

### Bug 2: `sysread()` not implemented on ProcessInputHandle

```perl
my ($wtr, $rdr);
open3($wtr, $rdr, undef, "echo", "hello");
close($wtr);
sysread($rdr, my $buf, 4096);  # Returns 0, $buf empty — should return 6
```

**Root cause**: `ProcessInputHandle` does not override `sysread()`.
The default `IOHandle.sysread()` calls `handleIOError(...)`, which the
`IOOperator.sysread()` error path catches and returns 0.

Note: `readline` (`<$rdr>`) works fine because it uses `IOHandle.read()` →
`doRead()`, which IS implemented.

### Bug 3: IO::Select never fires for open3 handles

```perl
my ($wtr, $rdr);
open3($wtr, $rdr, undef, "echo hello");
close($wtr);
my $sel = IO::Select->new($rdr);
$sel->count;       # 0 — should be 1
$sel->can_read(5); # () — should return ($rdr)
```

This is a direct consequence of Bug 1. `IO::Select._fileno()` calls
`fileno($rdr)` which returns undef, so the handle is silently rejected by
`_update()`.

### Bug 4: 4-arg `select()` can't find open3 handles

Even if fileno were assigned, the Java-side `selectWithNIO()` method in
`IOOperator.java` looks up handles via `RuntimeIO.getByFileno(fd)`. Since open3
handles are never registered in `RuntimeIO.filenoToIO`, they would not be found.

### Bug 5: `syswrite()` not implemented on ProcessOutputHandle

Similar to Bug 2, but for writing. `ProcessOutputHandle` doesn't override
`syswrite()`, so code like:

```perl
syswrite($wtr, "data");  # Fails
```

Note: `print $wtr "data"` works fine because it uses `write()`.

### Bug 6 (minor): `close()` on process handles doesn't interact with `$?`

`PipeInputChannel.close()` calls `process.waitFor()` and sets `$?`.
`ProcessInputHandle.close()` just closes the stream — it has no reference to
the `Process` object and can't set `$?`. This is acceptable for most use cases
since the caller typically calls `waitpid($pid, 0)` explicitly, but it's a
difference from pipe-open behavior.

## Fix Plan

### Phase 1: fileno + FileDescriptorTable registration (fixes Bugs 1, 3, 4)

**Files to modify**:
- `src/main/java/org/perlonjava/runtime/perlmodule/IPCOpen3.java`
- `src/main/java/org/perlonjava/runtime/io/ProcessInputHandle.java`
- `src/main/java/org/perlonjava/runtime/io/ProcessOutputHandle.java`
- `src/main/java/org/perlonjava/runtime/io/FileDescriptorTable.java`

**Changes**:

1. In `ProcessInputHandle` and `ProcessOutputHandle`, add a `fileno` field and
   override `fileno()`:

   ```java
   private int fd = -1;

   public void setFd(int fd) { this.fd = fd; }

   @Override
   public RuntimeScalar fileno() {
       return fd >= 0 ? new RuntimeScalar(fd) : RuntimeScalarCache.scalarUndef;
   }
   ```

2. In `IPCOpen3.setupReadHandle()` and `setupWriteHandle()`, register the
   IOHandle in `FileDescriptorTable` and assign the fd to the `RuntimeIO`:

   ```java
   private static void setupReadHandle(RuntimeScalar handleRef, InputStream in) {
       ProcessInputHandle pih = new ProcessInputHandle(in);
       int fd = FileDescriptorTable.register(pih);
       pih.setFd(fd);

       RuntimeIO io = new RuntimeIO();
       io.ioHandle = pih;
       io.assignFileno(fd);  // Register in RuntimeIO.filenoToIO too

       // ... rest unchanged (create glob, set handleRef)
   }
   ```

3. In `FileDescriptorTable.isReadReady()`, add a case for `ProcessInputHandle`:

   ```java
   if (handle instanceof ProcessInputHandle pih) {
       try {
           return pih.getInputStream().available() > 0;
       } catch (IOException e) {
           return true;  // Treat errors as "ready" to unblock
       }
   }
   ```

   This requires adding a `getInputStream()` accessor to `ProcessInputHandle`.

4. Similarly, `close()` on these handles should unregister from FileDescriptorTable:

   ```java
   @Override
   public RuntimeScalar close() {
       if (!isClosed) {
           if (fd >= 0) FileDescriptorTable.unregister(fd);
           // ... existing close logic
       }
   }
   ```

### Phase 2: sysread / syswrite (fixes Bugs 2, 5)

**Files to modify**:
- `src/main/java/org/perlonjava/runtime/io/ProcessInputHandle.java`
- `src/main/java/org/perlonjava/runtime/io/ProcessOutputHandle.java`

**Changes**:

1. Add `sysread()` to `ProcessInputHandle`:

   ```java
   @Override
   public RuntimeScalar sysread(int length) {
       if (isClosed || isEOF) return new RuntimeScalar();
       try {
           byte[] buffer = new byte[length];
           int bytesRead = inputStream.read(buffer, 0, length);
           if (bytesRead == -1) {
               isEOF = true;
               return new RuntimeScalar();
           }
           return new RuntimeScalar(new String(buffer, 0, bytesRead,
                   StandardCharsets.ISO_8859_1));
       } catch (IOException e) {
           isEOF = true;
           return new RuntimeScalar();
       }
   }
   ```

2. Add `syswrite()` to `ProcessOutputHandle`:

   ```java
   @Override
   public RuntimeScalar syswrite(String data) {
       if (isClosed) return RuntimeScalarCache.scalarFalse;
       try {
           byte[] bytes = data.getBytes(charset);
           outputStream.write(bytes);
           outputStream.flush();
           return RuntimeScalarCache.getScalarInt(bytes.length);
       } catch (IOException e) {
           return RuntimeScalarCache.scalarFalse;
       }
   }
   ```

### Phase 3: Process reference in handles (fixes Bug 6, optional)

**Files to modify**:
- `src/main/java/org/perlonjava/runtime/io/ProcessInputHandle.java`
- `src/main/java/org/perlonjava/runtime/io/ProcessOutputHandle.java`
- `src/main/java/org/perlonjava/runtime/perlmodule/IPCOpen3.java`

**Changes**:

Pass the `Process` object to the handle constructors so that `close()` can
optionally call `process.waitFor()` and set `$?`. This brings behavior closer
to pipe-open handles.

This is low priority since callers typically use `waitpid()` explicitly.

### Phase 4: Tests

**New file**: `src/test/resources/ipc_open3.t` (or appropriate test location)

Test cases:
1. `open3` basic stdout capture with `readline`
2. `open3` basic stdout capture with `sysread`
3. `open3` stderr capture (separate handle)
4. `open3` stderr merged with stdout (undef err)
5. `open3` with `IO::Select` — single handle
6. `open3` with `IO::Select` — stdout + stderr (Net::SSH pattern)
7. `open2` basic round-trip (write stdin, read stdout)
8. `open2` with `IO::File` objects as handles
9. `fileno()` returns defined value for open3 handles
10. `syswrite` to open3 write handle
11. `waitpid` after open3

## Implementation Order

1. **Phase 2 first** (sysread/syswrite) — simplest change, biggest impact
2. **Phase 1** (fileno + registration) — enables IO::Select
3. **Phase 4** (tests) — after each phase
4. **Phase 3** (optional) — only if needed

## Modules Unblocked by This Fix

- **Net::SSH** — `ssh_cmd()` uses open3 + IO::Select + sysread
- **TAP::Parser** (partially) — could use open3 for test execution if `d_fork` were set
- **Any module** using `IPC::Open3` with `sysread()` or `IO::Select`

## Related Files

| File | Role |
|---|---|
| `src/main/perl/lib/IPC/Open2.pm` | Perl wrapper → `_open2` XS |
| `src/main/perl/lib/IPC/Open3.pm` | Perl wrapper → `_open3` XS |
| `src/main/java/.../perlmodule/IPCOpen3.java` | Java XS backend |
| `src/main/java/.../io/ProcessInputHandle.java` | Read handle for child stdout/stderr |
| `src/main/java/.../io/ProcessOutputHandle.java` | Write handle for child stdin |
| `src/main/java/.../io/FileDescriptorTable.java` | Synthetic fd allocation |
| `src/main/java/.../io/IOHandle.java` | Base interface (default fileno/sysread) |
| `src/main/java/.../operators/IOOperator.java` | 4-arg select(), sysread() dispatch |
| `src/main/perl/lib/IO/Select.pm` | IO::Select (relies on fileno) |
| `src/main/java/.../io/PipeInputChannel.java` | Reference impl — pipe-open handles with sysread+fileno |

## Known Pre-existing Issues (not in scope)

- **`IPC::Open3` read-only modification error**: Documented in `JCPAN_DATETIME_FIXES.md`.
  When `open3()` receives read-only handle parameters (e.g., string constants),
  `setupWriteHandle`/`setupReadHandle` fails trying to modify them. This is a
  separate issue from the fileno/sysread bugs.
- **`ProcessInputHandle.eof()` mark/reset**: Uses `mark(1)/read()/reset()` peek
  pattern. May fail if the underlying InputStream doesn't support marking.
  Process streams on most JDKs do support it, but this is not guaranteed.

## Bug 7: Typeglob handles (`\*FOO`) not populated correctly

When passing typeglob references like `\*WTR`, `\*RDR` to `open3()`, the IO slot
was not being set on the existing glob. Instead, `setupWriteHandle()` /
`setupReadHandle()` would create a new RuntimeGlob and call `inner.set(newHandle)`,
which replaced the inner scalar but left the original glob's IO slot empty.

**Root cause**: When the inner value is already a `GLOBREFERENCE` (i.e., `\*FOO`),
the code needs to set the IO slot on the existing `RuntimeGlob` rather than
creating a new one. Otherwise, bareword reads like `<RDR_GLOB>` can't find the
IO handle because they look at the glob in the symbol table, not the scalar
reference.

**Fix**: Added detection for `RuntimeScalarType.GLOBREFERENCE` in both
`setupWriteHandle()` and `setupReadHandle()` in `IPCOpen3.java`. When the inner
value is already a glob reference, call `((RuntimeGlob) inner.value).setIO(io)`
directly instead of creating a new glob.

This pattern is used by Net::SSH's `sshopen2()` / `sshopen3()`:
```perl
open3(\*WRITER, \*READER, \*ERROR, "ssh", @args);
print WRITER "command\n";
my $output = <READER>;
```

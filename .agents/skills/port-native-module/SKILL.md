# Port Native/FFM CPAN Module to PerlOnJava

## When to Use This Skill

Use this skill when porting a CPAN module that requires **native system calls**
(POSIX functions, ioctl, terminal control, etc.) via Java's Foreign Function &
Memory (FFM) API. This extends the standard `port-cpan-module` skill with the
additional FFM layer.

Examples of modules that need this approach:
- IO::Tty / IO::Pty (pty allocation, ioctl, termios)
- Term::ReadKey (terminal mode control via tcgetattr/tcsetattr)
- IPC::SysV (shared memory, semaphores, message queues)
- Sys::Syslog (syslog system calls)
- Any module whose XS code calls libc/POSIX functions directly

For modules that only need Java standard library equivalents (crypto, time,
encoding), use the standard `port-cpan-module` skill instead.

## Key Principle: Windows Support

**Always support Windows when possible. When not possible, match the original
CPAN module's behavior on Windows.**

Concretely:

1. **If the original module works on Windows** (e.g., Term::ReadKey uses
   `Win32::Console`), provide a Windows implementation in `FFMPosixWindows.java`
   using either Windows API calls via FFM or pure Java emulation.

2. **If the original module explicitly rejects Windows** (e.g., IO::Tty dies
   with `"OS unsupported"` on `$^O eq 'MSWin32'`), replicate that same behavior
   at each layer:
   - `FFMPosixWindows.java`: throw `UnsupportedOperationException` (Java-internal,
     matches existing convention for unimplemented FFM functions)
   - Java perlmodule: catch `UnsupportedOperationException` and translate to a
     Perl-visible error via `WarnDie.die()` or `PerlCompilerException`
   - Perl `.pm` shim: `die` with the same message the original module uses, so
     users see identical behavior

3. **If a partial Windows implementation is feasible** (e.g., some functions
   work via ConPTY or Java APIs but others don't), implement what you can and
   clearly document which functions are Windows-only stubs.

The goal: a user switching from `perl` + CPAN to `jperl` + `jcpan` should see
**identical platform support** — if the module worked on their OS before, it
should work on PerlOnJava too; if it didn't, it should fail the same way.

## Prerequisites

- Read the standard `port-cpan-module` skill first for general porting patterns
- Read `docs/guides/module-porting.md` for naming conventions and checklists
- Familiarity with Java FFM API (java.lang.foreign.*)

## Architecture Overview

PerlOnJava calls native C functions via a layered FFM architecture:

```
Perl code (use IO::Pty; $pty->new)
        |
        v
Java perlmodule (IOTty.java)          <-- Perl API in Java
        |
        v
NativeUtils / ExtendedNativeUtils     <-- Cross-platform dispatch
        |
        v
PosixLibrary  (thin facade)
        |
        v
FFMPosixInterface                     <-- Java interface (contract)
        |
        v
FFMPosix.get()                        <-- Factory, detects OS
        |
        v
FFMPosixLinux | FFMPosixMacOS | FFMPosixWindows   <-- Platform impls
```

### Key Files

| File | Role |
|------|------|
| `src/main/java/org/perlonjava/runtime/nativ/ffm/FFMPosixInterface.java` | Interface defining all POSIX function signatures |
| `src/main/java/org/perlonjava/runtime/nativ/ffm/FFMPosix.java` | Factory: detects OS, returns correct implementation |
| `src/main/java/org/perlonjava/runtime/nativ/ffm/FFMPosixLinux.java` | Core FFM implementation (Linux + base for macOS) |
| `src/main/java/org/perlonjava/runtime/nativ/ffm/FFMPosixMacOS.java` | macOS overrides (extends Linux; override only what differs) |
| `src/main/java/org/perlonjava/runtime/nativ/ffm/FFMPosixWindows.java` | Windows emulation or UnsupportedOperationException |
| `src/main/java/org/perlonjava/runtime/nativ/NativeUtils.java` | High-level: symlink, link, getppid, getuid, etc. |
| `src/main/java/org/perlonjava/runtime/nativ/ExtendedNativeUtils.java` | High-level: user/group info, network, SysV IPC |
| `src/main/java/org/perlonjava/runtime/nativ/PosixLibrary.java` | Facade: `PosixLibrary.getFFM()` returns `FFMPosix.get()` |

### FFM Configuration

PerlOnJava targets Java 21+ with FFM enabled:
- `build.gradle` / `pom.xml` includes `--enable-native-access=ALL-UNNAMED`
- FFM is enabled by default; disable with `-Dperlonjava.ffm.enabled=false`
- The `jperl` wrapper script passes the required JVM flags

## Step-by-Step Process

### Phase 1: Analysis (same as port-cpan-module, plus)

1. **Study the XS source** to identify which C/POSIX functions are called
2. **Classify each function call:**

   | Category | Example | FFM Complexity |
   |----------|---------|----------------|
   | Simple (int→int) | `getuid()`, `setsid()`, `umask()` | Trivial |
   | String arg | `chmod(path, mode)`, `open(path, flags)` | Easy (Arena string alloc) |
   | String return | `strerror(errno)`, `ptsname(fd)` | Easy (readCString) |
   | Struct I/O | `stat()`, `tcgetattr()` | Medium (struct layout) |
   | Variadic | `ioctl(fd, req, ...)` | Medium (firstVariadicArg) |
   | Callback | `signal handlers` | Hard (upcall stubs) |

3. **Check what's already implemented** in `FFMPosixInterface.java`
4. **Identify platform differences** (macOS vs Linux struct layouts, constant values)
5. **Check if existing IOHandle implementations** can be reused or if a new one is needed

### Phase 2: Add FFM Bindings

Follow this pattern for each new native function:

#### Step 2a: Add method to FFMPosixInterface.java

```java
// In the appropriate section (Terminal Functions, Process Functions, etc.)

/**
 * Brief description of what the function does.
 * @param fd File descriptor
 * @return 0 on success, -1 on error (check errno)
 */
int myFunction(int fd);
```

#### Step 2b: Add MethodHandle + implementation in FFMPosixLinux.java

```java
// 1. Declare at class level:
private static MethodHandle myFunctionHandle;

// 2. Initialize in ensureInitialized():
myFunctionHandle = linker.downcallHandle(
    stdlib.find("myFunction").orElseThrow(),
    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    captureErrno  // include if errno capture needed
);

// 3. Implement the interface method:
@Override
public int myFunction(int fd) {
    ensureInitialized();
    try (Arena arena = Arena.ofConfined()) {
        MemorySegment capturedState = arena.allocate(
            Linker.Option.captureStateLayout());
        int result = (int) myFunctionHandle.invokeExact(capturedState, fd);
        if (result == -1) {
            int err = capturedState.get(ValueLayout.JAVA_INT, errnoOffset);
            setErrno(err);
        }
        return result;
    } catch (Throwable e) {
        setErrno(1);
        return -1;
    }
}
```

#### Step 2c: Override in FFMPosixMacOS.java (only if macOS differs)

Most functions are identical on Linux and macOS. Override only when:
- Struct layouts differ (different field offsets or sizes)
- Constants differ (ioctl request codes)
- Function signatures differ

#### Step 2d: Handle Windows in FFMPosixWindows.java

Check how the **original CPAN module** behaves on Windows, then match it:

- **If the original module supports Windows**: provide a real implementation
  using Windows API calls via FFM (e.g., `kernel32.dll` functions) or pure
  Java emulation.
- **If the original module rejects Windows** (e.g., `die "OS unsupported"`):
  throw `UnsupportedOperationException` with a message matching the original.
  The Perl `.pm` shim should also replicate the original's Windows guard.
- **If partial support is feasible**: implement what you can, document the rest
  as unsupported, and throw for the gaps.

Never silently return success for a function that isn't actually working.

### Common FFM Patterns

#### Simple function (no args, returns int)
```java
// getuid() — no errno needed
getuidHandle = linker.downcallHandle(
    stdlib.find("getuid").orElseThrow(),
    FunctionDescriptor.of(ValueLayout.JAVA_INT)
);

public int getuid() {
    ensureInitialized();
    try { return (int) getuidHandle.invokeExact(); }
    catch (Throwable e) { return -1; }
}
```

#### Function with string argument
```java
// chmod(path, mode) — needs Arena for string, captures errno
public int chmod(String path, int mode) {
    ensureInitialized();
    try (Arena arena = Arena.ofConfined()) {
        MemorySegment pathSegment = arena.allocateFrom(path);
        MemorySegment capturedState = arena.allocate(
            Linker.Option.captureStateLayout());
        int result = (int) chmodHandle.invokeExact(
            capturedState, pathSegment, mode);
        if (result == -1) {
            setErrno(capturedState.get(ValueLayout.JAVA_INT, errnoOffset));
        }
        return result;
    } catch (Throwable e) { setErrno(5); return -1; }
}
```

#### Function returning C string
```java
// ptsname(fd) — returns char*
public String ptsname(int fd) {
    ensureInitialized();
    try {
        MemorySegment result = (MemorySegment) ptsnameHandle.invokeExact(fd);
        if (result.address() == 0) return null;
        return result.reinterpret(1024).getString(0);
    } catch (Throwable e) { return null; }
}
```

#### Struct I/O (reading fields at offsets)
```java
// Platform-specific struct offsets (set in init method)
private static long FIELD_OFFSET;

private static void initStructOffsets() {
    if (IS_MACOS) {
        FIELD_OFFSET = 8;  // macOS layout
    } else {
        FIELD_OFFSET = 4;  // Linux layout
    }
}

// Read struct from native memory
MyRecord readStruct(MemorySegment ptr) {
    MemorySegment s = ptr.reinterpret(STRUCT_SIZE);
    int field1 = s.get(ValueLayout.JAVA_INT, FIELD1_OFFSET);
    String field2 = readCString(s.get(ValueLayout.ADDRESS, FIELD2_OFFSET));
    return new MyRecord(field1, field2);
}
```

#### Variadic function (ioctl)
```java
// ioctl(fd, request, ...) — variadic after arg index 2
ioctlHandle = linker.downcallHandle(
    stdlib.find("ioctl").orElseThrow(),
    FunctionDescriptor.of(
        ValueLayout.JAVA_INT,     // return
        ValueLayout.JAVA_INT,     // fd
        ValueLayout.JAVA_LONG,    // request (unsigned long)
        ValueLayout.ADDRESS       // variadic arg: pointer
    ),
    Linker.Option.firstVariadicArg(2),
    captureErrno
);
```

### Phase 3: Bridge Native FDs to PerlOnJava I/O (if needed)

Some native modules produce raw POSIX file descriptors (e.g., `posix_openpt()`
returns an int fd). PerlOnJava's I/O system uses Java `IOHandle` objects. To
bridge the gap:

1. **Create a new IOHandle implementation** (e.g., `NativeFdIOHandle`) that:
   - Stores the raw POSIX fd
   - Implements `read()` / `write()` / `close()` via FFM `read()`/`write()`/`close()`
   - Implements `fileno()` returning the native fd
   - Implements `sysread()` / `syswrite()` for unbuffered I/O
   - Registers in `FileDescriptorTable` for `select()` support

2. **Register the handle** so Perl code can use it:
   ```java
   int fd = FileDescriptorTable.register(nativeHandle);
   // Perl's fdopen($fd, "r+") can now find it
   ```

3. **Key classes to understand:**
   - `IOHandle` interface — base contract for all I/O handles
   - `FileDescriptorTable` — maps simulated fd numbers to IOHandle objects
   - `RuntimeIO` — wraps IOHandle for the Perl runtime
   - `DupIOHandle` — wraps handles for dup/dup2 operations
   - `IOOperator.findFileHandleByDescriptor()` — looks up handles by fd number

### Phase 4: Create Java perlmodule

Follow the standard `port-cpan-module` pattern:
- File: `src/main/java/org/perlonjava/runtime/perlmodule/ModuleName.java`
- Extends `PerlModuleBase`
- Static `initialize()` method called by XSLoader
- Methods call through to FFM layer via `PosixLibrary.getFFM()` or directly

### Phase 5: Create Perl shim (.pm)

Follow the standard `port-cpan-module` pattern:
- File: `src/main/perl/lib/Module/Name.pm`
- Calls `XSLoader::load('Module::Name', $VERSION)`
- Pure Perl helper methods wrap Java-backed functions
- Preserve original CPAN module's API exactly

### Phase 6: Testing

Same as `port-cpan-module`, plus:
- Test on both macOS and Linux if struct layouts differ
- Test error paths (invalid fd, permission denied, etc.)
- Test errno propagation
- Verify `isatty()` returns correct results for new handle types

## Checklist (extends port-cpan-module checklist)

### FFM Layer
- [ ] Identify all C/POSIX functions needed
- [ ] Check which are already in `FFMPosixInterface.java`
- [ ] Add new methods to `FFMPosixInterface.java` with Javadoc
- [ ] Implement in `FFMPosixLinux.java` with errno capture
- [ ] Override in `FFMPosixMacOS.java` if platform-specific
- [ ] Handle Windows in `FFMPosixWindows.java` (emulate or throw)
- [ ] Add any new data records to `FFMPosixInterface.java`

### I/O Bridge (if raw fds are involved)
- [ ] Create new `IOHandle` implementation if needed
- [ ] Register handles in `FileDescriptorTable`
- [ ] Wire into `IOOperator.findFileHandleByDescriptor()`
- [ ] Test `fileno()`, `sysread()`, `syswrite()`, `close()`
- [ ] Test `select()` integration if applicable

### Platform Constants
- [ ] Identify platform-specific constants (ioctl codes, struct sizes)
- [ ] Use `IS_MACOS` flag for conditional initialization
- [ ] Document constant values and their sources

## Existing FFM Functions (reference)

Already implemented in `FFMPosixInterface`:
- Process: `kill`, `getppid`, `waitpid`
- User/Group: `getuid`, `geteuid`, `getgid`, `getegid`, `getpwnam`, `getpwuid`, `getpwent`, `setpwent`, `endpwent`
- File: `stat`, `lstat`, `chmod`, `link`, `utimes`
- Terminal: `isatty`
- File control: `fcntl`, `umask`
- Error: `errno`, `setErrno`, `strerror`

Also available outside FFM:
- `symlink` (NativeUtils, via Java NIO)
- `ioctl` (IOOperator, currently a stub returning false)

## References

- `port-cpan-module` skill — standard XS→Java porting workflow
- `docs/guides/module-porting.md` — authoritative naming/layout guide
- `dev/modules/` — per-module implementation plans
- Java FFM tutorial: https://docs.oracle.com/en/java/javase/22/core/foreign-function-and-memory-api.html
- `src/main/java/org/perlonjava/runtime/nativ/ffm/` — existing FFM code (best reference)
- `src/main/java/org/perlonjava/runtime/io/` — I/O handle implementations

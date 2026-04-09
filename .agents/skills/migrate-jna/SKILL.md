---
name: migrate-jna
description: Migrate from JNA to a modern native access library (eliminate sun.misc.Unsafe warnings)
argument-hint: "[library choice or file to migrate]"
triggers:
  - user
---

# Migrate JNA to Modern Native Access Library

## Problem

JNA 5.18.1 uses `sun.misc.Unsafe::staticFieldBase` internally, which produces deprecation warnings on Java 21+ and will break in future JDK releases. The project needs to migrate to a library that uses supported APIs.

## Candidate Replacement Libraries

The choice of replacement library is TBD. Evaluate these options:

### Option A: jnr-posix
- **Maven**: `com.github.jnr:jnr-posix`
- **Pros**: Purpose-built for POSIX ops, used by JRuby (production-proven), clean high-level API (`FileStat`, `kill()`, `waitpid()`, `umask()`, `utime()`), built on jnr-ffi (no `sun.misc.Unsafe`)
- **Cons**: Third-party dependency, may not cover Windows-specific calls

### Option B: Java Foreign Function & Memory API (FFM)
- **Module**: `java.lang.foreign` (JDK built-in)
- **Pros**: No third-party dependency, official JDK solution, no deprecated APIs
- **Cons**: Stable only since Java 22 (preview in 21), verbose low-level API, requires manual struct layout definitions
- **Note**: If the project bumps minimum to Java 22, this becomes viable without preview flags

### Option C: jnr-ffi (without jnr-posix)
- **Maven**: `com.github.jnr:jnr-ffi`
- **Pros**: Modern JNA alternative, no `sun.misc.Unsafe`, flexible
- **Cons**: Lower-level than jnr-posix, requires manual bindings (similar effort to FFM)

## Current JNA Usage

10 files use JNA. All paths relative to `src/main/java/org/perlonjava/`.

### Native interface definitions

| File | JNA Usage |
|------|-----------|
| `runtime/nativ/PosixLibrary.java` | POSIX C library bindings: `stat`, `lstat`, `chmod`, `chown`, `getpid`, `getppid`, `setpgid`, `getpgid`, `setsid`, `tcsetpgrp`, `tcgetpgrp`, `getpgrp`, `setpgrp` |
| `runtime/nativ/WindowsLibrary.java` | Windows kernel32 bindings: `GetCurrentProcessId`, `_getpid` |
| `runtime/nativ/NativeUtils.java` | JNA Platform utilities: `getpid()`, `getuid()`, `geteuid()`, `getgid()`, `getegid()`, plus `CLibrary` for `getpriority`/`setpriority`/`alarm`/`getlogin` |
| `runtime/nativ/ExtendedNativeUtils.java` | Additional POSIX: `getpwuid`, `getpwnam`, `getgrnam`, `getgrgid` (passwd/group lookups) |

### Consumers (files that call native operations)

| File | Operations Used |
|------|----------------|
| `runtime/operators/Stat.java` | `PosixLibrary.stat()`, `PosixLibrary.lstat()` — all 13 stat fields (dev, ino, mode, nlink, uid, gid, rdev, size, atime, mtime, ctime, blksize, blocks) |
| `runtime/operators/Operator.java` | `PosixLibrary.chmod()`, `PosixLibrary.chown()`, `NativeUtils` for pid/uid/gid |
| `runtime/operators/KillOperator.java` | `PosixLibrary.kill()` for sending signals, `NativeUtils.getpid()` |
| `runtime/operators/WaitpidOperator.java` | JNA `CLibrary.waitpid()` with `WNOHANG`/`WUNTRACED` flags, macros `WIFEXITED`/`WEXITSTATUS`/`WIFSIGNALED`/`WTERMSIG`/`WIFSTOPPED`/`WSTOPSIG` |
| `runtime/operators/UmaskOperator.java` | JNA `CLibrary.umask()` |
| `runtime/operators/UtimeOperator.java` | JNA `CLibrary.utimes()` with `timeval` struct |

## Migration Strategy

### Phase 1: Replace native interface definitions
1. Create new interface files using the chosen library
2. Keep the same method signatures where possible
3. Ensure struct mappings (stat, timeval, passwd, group) are complete

### Phase 2: Update consumers one by one
Migrate in this order (least to most complex):
1. `UmaskOperator.java` — single `umask()` call
2. `KillOperator.java` — `kill()` + `getpid()`
3. `UtimeOperator.java` — `utimes()` with struct
4. `Operator.java` — `chmod()`, `chown()`, pid/uid/gid
5. `WaitpidOperator.java` — `waitpid()` with flag macros
6. `Stat.java` — `stat()`/`lstat()` with 13-field struct
7. `NativeUtils.java` / `ExtendedNativeUtils.java` — passwd/group lookups

### Phase 3: Remove JNA dependency
1. Remove JNA imports from all files
2. Remove JNA from `build.gradle` and `pom.xml`
3. Remove `--enable-native-access=ALL-UNNAMED` from `jperl` launcher (if no longer needed)
4. Verify the `sun.misc.Unsafe` warning is gone

## Testing

**ALWAYS use `make` commands. NEVER use raw mvn/gradlew commands.**

| Command | What it does |
|---------|--------------|
| `make` | Build + run all unit tests (use before committing) |
| `make dev` | Build only, skip tests (for quick iteration) |
| `make test-all` | Run extended test suite |

After each file migration:
```bash
make          # Build + unit tests (must pass)
make test-all # Check for regressions in extended tests
```

Key tests that exercise native operations:
- `perl5_t/t/op/stat.t` — stat/lstat fields
- `perl5_t/t/io/fs.t` — chmod, chown, utime
- `perl5_t/t/op/fork.t` — kill, waitpid
- `src/test/resources/unit/glob.t` — readdir (uses stat internally)

## Build Configuration

### Current JNA in gradle
```
# gradle/libs.versions.toml
jna = "5.18.1"
jna = { module = "net.java.dev.jna:jna", version.ref = "jna" }
jna-platform = { module = "net.java.dev.jna:jna-platform", version.ref = "jna" }
```

### Current JNA in pom.xml
```xml
<dependency>
    <groupId>net.java.dev.jna</groupId>
    <artifactId>jna</artifactId>
</dependency>
<dependency>
    <groupId>net.java.dev.jna</groupId>
    <artifactId>jna-platform</artifactId>
</dependency>
```

## Platform Considerations

- **macOS/Linux**: Full POSIX support required (stat, lstat, kill, waitpid, chmod, chown, umask, utime, passwd/group lookups)
- **Windows**: Limited support via `kernel32` (`GetCurrentProcessId`), `msvcrt` (`_getpid`, stat)
- The replacement must handle both platforms, or gracefully degrade on Windows (as JNA currently does)

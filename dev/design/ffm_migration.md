# FFM Migration: Replace JNR-POSIX with Java Foreign Function & Memory API

## Overview

This document outlines the plan to migrate PerlOnJava from JNR-POSIX to Java's Foreign Function & Memory (FFM) API (JEP 454), eliminating the `sun.misc.Unsafe` warnings that appear on Java 24+.

### Problem Statement

JNR-POSIX depends on JFFI, which uses `sun.misc.Unsafe` for native memory access. Starting with Java 24, warnings are issued by default:

```
WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
WARNING: sun.misc.Unsafe::putLong has been called by com.kenai.jffi.UnsafeMemoryIO$UnsafeMemoryIO64
```

The `sun.misc.Unsafe` memory-access methods are scheduled for removal in a future JDK release (JEP 471/498).

### Solution

Migrate to Java's FFM API (finalized in Java 22), which provides a safe, supported replacement for native function calls.

### Requirements

- **Minimum Java version**: 22 (FFM finalized)
- **Backwards compatibility**: Maintain all existing Perl functionality
- **Windows support**: Provide Windows-specific implementations where POSIX is unavailable

## Current JNR-POSIX Usage Inventory

### Functions Used

| Function | Location | Windows Alternative |
|----------|----------|---------------------|
| `chmod(path, mode)` | `Operator.java` | `Files.setPosixFilePermissions()` or ACL |
| `kill(pid, signal)` | `KillOperator.java` | `ProcessHandle.destroy()` / `destroyForcibly()` |
| `errno()` | Multiple | Thread-local errno storage |
| `isatty(fd)` | `FileTestOperator.java`, `DebugHooks.java` | `System.console() != null` |
| `strerror(errno)` | `POSIX.java` | Java error message mapping |
| `fcntl(fd, cmd, arg)` | `IOOperator.java` | Limited support via NIO |
| `stat(path)` / `lstat(path)` | `Stat.java` | `Files.readAttributes()` |
| `link(old, new)` | `NativeUtils.java` | `Files.createLink()` |
| `getppid()` | `NativeUtils.java` | `ProcessHandle.current().parent()` |
| `getuid()` / `geteuid()` | `NativeUtils.java` | Hash-based simulation |
| `getgid()` / `getegid()` | `NativeUtils.java` | Hash-based simulation |
| `getpwnam(name)` | `ExtendedNativeUtils.java` | User property simulation |
| `getpwuid(uid)` | `ExtendedNativeUtils.java` | User property simulation |
| `getpwent()` / `setpwent()` / `endpwent()` | `ExtendedNativeUtils.java` | `/etc/passwd` parsing or simulation |
| `umask(mask)` | `UmaskOperator.java` | Simulation with default values |
| `utimes(path, atime, mtime)` | `UtimeOperator.java` | `Files.setLastModifiedTime()` + custom |
| `waitpid(pid, status, flags)` | `WaitpidOperator.java` | `Process.waitFor()` |

### Data Structures Used

- `FileStat` - stat structure (dev, ino, mode, nlink, uid, gid, rdev, size, times, blocks)
- `Passwd` - password entry (name, passwd, uid, gid, gecos, home, shell)

## FFM API Overview

### Key Classes (Java 22+)

```java
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
```

### Basic Pattern

```java
// 1. Get the native linker and symbol lookup
Linker linker = Linker.nativeLinker();
SymbolLookup stdlib = linker.defaultLookup();

// 2. Find the native function
MemorySegment killSymbol = stdlib.find("kill").orElseThrow();

// 3. Create a method handle with the function descriptor
MethodHandle kill = linker.downcallHandle(
    killSymbol,
    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
);

// 4. Call the function
int result = (int) kill.invokeExact(pid, signal);
```

### Memory Management

```java
try (Arena arena = Arena.ofConfined()) {
    // Allocate native memory for structs
    MemorySegment statBuf = arena.allocate(STAT_LAYOUT);
    
    // Call native function
    int result = (int) statHandle.invokeExact(pathSegment, statBuf);
    
    // Read struct fields
    long size = statBuf.get(ValueLayout.JAVA_LONG, ST_SIZE_OFFSET);
}
// Memory automatically freed when arena closes
```

## Architecture

### New Package Structure

```
src/main/java/org/perlonjava/runtime/nativ/
├── PosixLibrary.java          # Current JNR-POSIX wrapper (to be replaced)
├── NativeUtils.java           # Existing utility class
├── ExtendedNativeUtils.java   # Existing utility class
└── ffm/                       # New FFM-based implementations
    ├── FFMPosix.java          # Main FFM POSIX interface
    ├── FFMPosixLinux.java     # Linux-specific implementations
    ├── FFMPosixMacOS.java     # macOS-specific implementations
    ├── FFMPosixWindows.java   # Windows-specific implementations
    ├── StatStruct.java        # stat structure wrapper
    ├── PasswdStruct.java      # passwd structure wrapper
    └── ErrnoHandler.java      # errno management
```

### Platform Detection

```java
public class FFMPosix {
    private static final FFMPosixInterface INSTANCE = createInstance();
    
    private static FFMPosixInterface createInstance() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return new FFMPosixWindows();
        } else if (os.contains("mac")) {
            return new FFMPosixMacOS();
        } else {
            return new FFMPosixLinux();  // Default for Linux/Unix
        }
    }
    
    public static FFMPosixInterface get() {
        return INSTANCE;
    }
}
```

## Windows Compatibility Strategy

Windows doesn't have POSIX APIs. For each function, we need a Windows-specific approach:

### Already Implemented (Pure Java)

These already have Windows fallbacks in the codebase:

| Function | Windows Implementation |
|----------|----------------------|
| `getppid()` | `ProcessHandle.current().parent().pid()` |
| `getuid()` / `geteuid()` | Hash of `user.name` property |
| `getgid()` / `getegid()` | Hash of `COMPUTERNAME` env var |
| `link()` | `Files.createLink()` |
| `kill()` | `ProcessHandle.destroy()` / `destroyForcibly()` |

### Requires Windows API Calls

| POSIX Function | Windows API | Notes |
|----------------|-------------|-------|
| `isatty()` | `GetConsoleMode()` | Check if handle is console |
| `chmod()` | `SetFileAttributes()` | Limited (read-only flag only) |
| `stat()` | `GetFileAttributesEx()` | Different struct layout |
| `umask()` | N/A | Simulate with thread-local default |
| `fcntl()` | Various | Partial support only |

### Windows FFM Example: isatty()

```java
public class FFMPosixWindows implements FFMPosixInterface {
    private static final MethodHandle GetStdHandle;
    private static final MethodHandle GetConsoleMode;
    
    static {
        Linker linker = Linker.nativeLinker();
        SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
        
        GetStdHandle = linker.downcallHandle(
            kernel32.find("GetStdHandle").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        );
        
        GetConsoleMode = linker.downcallHandle(
            kernel32.find("GetConsoleMode").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );
    }
    
    private static final int STD_INPUT_HANDLE = -10;
    private static final int STD_OUTPUT_HANDLE = -11;
    private static final int STD_ERROR_HANDLE = -12;
    
    @Override
    public boolean isatty(int fd) {
        try (Arena arena = Arena.ofConfined()) {
            int stdHandle = switch (fd) {
                case 0 -> STD_INPUT_HANDLE;
                case 1 -> STD_OUTPUT_HANDLE;
                case 2 -> STD_ERROR_HANDLE;
                default -> throw new IllegalArgumentException("Invalid fd: " + fd);
            };
            
            MemorySegment handle = (MemorySegment) GetStdHandle.invokeExact(stdHandle);
            MemorySegment mode = arena.allocate(ValueLayout.JAVA_INT);
            int result = (int) GetConsoleMode.invokeExact(handle, mode);
            return result != 0;
        } catch (Throwable e) {
            return false;
        }
    }
}
```

## Implementation Phases

### Phase 1: Infrastructure (Week 1)

**Goal**: Set up FFM framework without changing existing behavior

1. Create `ffm/` package structure
2. Implement `FFMPosixInterface` with all method signatures
3. Create platform-specific stub implementations
4. Add feature flag to switch between JNR-POSIX and FFM:
   ```java
   // System property to enable FFM (default: false initially)
   boolean useFFM = Boolean.getBoolean("perlonjava.ffm.enabled");
   ```

**Files to create**:
- `FFMPosixInterface.java`
- `FFMPosix.java` (factory)
- `FFMPosixLinux.java` (stubs)
- `FFMPosixMacOS.java` (stubs)
- `FFMPosixWindows.java` (stubs)

### Phase 2: Simple Functions (Week 2)

**Goal**: Implement functions that don't require complex structs

Implement in order of complexity:
1. `kill(pid, signal)` - Linux/macOS only
2. `isatty(fd)` - All platforms
3. `errno()` / `strerror()` - All platforms
4. `getuid()`, `geteuid()`, `getgid()`, `getegid()` - Linux/macOS
5. `getppid()` - Linux/macOS
6. `umask(mask)` - Linux/macOS
7. `chmod(path, mode)` - Linux/macOS

### Phase 3: Struct-Based Functions (Week 3)

**Goal**: Implement functions requiring native struct handling

1. Define struct layouts:
   ```java
   // Linux stat struct (x86_64)
   public static final MemoryLayout STAT_LAYOUT = MemoryLayout.structLayout(
       ValueLayout.JAVA_LONG.withName("st_dev"),
       ValueLayout.JAVA_LONG.withName("st_ino"),
       ValueLayout.JAVA_LONG.withName("st_nlink"),
       ValueLayout.JAVA_INT.withName("st_mode"),
       ValueLayout.JAVA_INT.withName("st_uid"),
       // ... etc
   );
   ```

2. Implement:
   - `stat(path)` / `lstat(path)`
   - `getpwnam(name)` / `getpwuid(uid)`
   - `getpwent()` / `setpwent()` / `endpwent()`
   - `utimes(path, times)`
   - `fcntl(fd, cmd, arg)`
   - `waitpid(pid, status, flags)`

### Phase 4: Windows Support (Week 4)

**Goal**: Complete Windows implementations

1. `isatty()` via `GetConsoleMode()`
2. `stat()` via `GetFileAttributesEx()` or existing Java NIO
3. Verify all existing Java fallbacks work correctly

### Phase 5: Testing & Migration (Week 5)

**Goal**: Enable FFM by default, update minimum Java version

1. **Update minimum Java version to 22**:
   - Update `build.gradle` sourceCompatibility/targetCompatibility
   - Update `pom.xml` maven.compiler.source/target
   - Update CI workflows (`.github/workflows/`)
   - Update documentation (README.md, QUICKSTART.md, installation.md)
   - Update presentations
2. Run full test suite with `perlonjava.ffm.enabled=true`
3. Fix any discrepancies
4. Change default to FFM (flip feature flag)
5. Mark JNR-POSIX code as deprecated
6. Remove `--sun-misc-unsafe-memory-access` flag logic from jperl/jperl.bat

### Phase 6: Cleanup (Week 6)

**Goal**: Remove JNR-POSIX dependency

1. Remove JNR-POSIX from dependencies
2. Delete deprecated code
3. Update documentation

## Struct Layouts Reference

### Linux x86_64 stat

```java
// sizeof(struct stat) = 144 bytes on Linux x86_64
public static final StructLayout STAT_LAYOUT_LINUX_X64 = MemoryLayout.structLayout(
    ValueLayout.JAVA_LONG.withName("st_dev"),      // offset 0
    ValueLayout.JAVA_LONG.withName("st_ino"),      // offset 8
    ValueLayout.JAVA_LONG.withName("st_nlink"),    // offset 16
    ValueLayout.JAVA_INT.withName("st_mode"),      // offset 24
    ValueLayout.JAVA_INT.withName("st_uid"),       // offset 28
    ValueLayout.JAVA_INT.withName("st_gid"),       // offset 32
    MemoryLayout.paddingLayout(4),                 // padding
    ValueLayout.JAVA_LONG.withName("st_rdev"),     // offset 40
    ValueLayout.JAVA_LONG.withName("st_size"),     // offset 48
    ValueLayout.JAVA_LONG.withName("st_blksize"),  // offset 56
    ValueLayout.JAVA_LONG.withName("st_blocks"),   // offset 64
    ValueLayout.JAVA_LONG.withName("st_atime"),    // offset 72
    ValueLayout.JAVA_LONG.withName("st_atime_ns"), // offset 80
    ValueLayout.JAVA_LONG.withName("st_mtime"),    // offset 88
    ValueLayout.JAVA_LONG.withName("st_mtime_ns"), // offset 96
    ValueLayout.JAVA_LONG.withName("st_ctime"),    // offset 104
    ValueLayout.JAVA_LONG.withName("st_ctime_ns"), // offset 112
    MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_LONG) // reserved
);
```

### macOS stat

```java
// macOS uses different struct layout
public static final StructLayout STAT_LAYOUT_MACOS = MemoryLayout.structLayout(
    ValueLayout.JAVA_INT.withName("st_dev"),
    ValueLayout.JAVA_SHORT.withName("st_mode"),
    ValueLayout.JAVA_SHORT.withName("st_nlink"),
    ValueLayout.JAVA_LONG.withName("st_ino"),
    ValueLayout.JAVA_INT.withName("st_uid"),
    ValueLayout.JAVA_INT.withName("st_gid"),
    ValueLayout.JAVA_INT.withName("st_rdev"),
    // ... timespec structs for atime, mtime, ctime
    ValueLayout.JAVA_LONG.withName("st_size"),
    ValueLayout.JAVA_LONG.withName("st_blocks"),
    ValueLayout.JAVA_INT.withName("st_blksize"),
    // ... flags, gen, etc
);
```

## Error Handling

### errno Management

FFM doesn't automatically capture `errno`. We need to use the `Linker.Option.captureCallState()` option:

```java
MethodHandle kill = linker.downcallHandle(
    killSymbol,
    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    Linker.Option.captureCallState("errno")
);

try (Arena arena = Arena.ofConfined()) {
    MemorySegment capturedState = arena.allocate(Linker.Option.captureStateLayout());
    int result = (int) kill.invokeExact(capturedState, pid, signal);
    if (result == -1) {
        int errno = capturedState.get(ValueLayout.JAVA_INT, 0);
        // Handle error
    }
}
```

## Testing Strategy

### Unit Tests

Create tests for each FFM function comparing output with system Perl:

```java
@Test
void testStat() {
    // Create test file
    Path testFile = Files.createTempFile("test", ".txt");
    Files.writeString(testFile, "content");
    
    // Get stat via FFM
    StatResult ffmStat = FFMPosix.get().stat(testFile.toString());
    
    // Verify against Java NIO
    BasicFileAttributes attrs = Files.readAttributes(testFile, BasicFileAttributes.class);
    assertEquals(attrs.size(), ffmStat.size());
    
    // Verify against Perl
    String perlResult = runPerl("my @s = stat('" + testFile + "'); print $s[7]");
    assertEquals(perlResult, String.valueOf(ffmStat.size()));
}
```

### Integration Tests

Run existing Perl test suite:
```bash
PERLONJAVA_FFM_ENABLED=true perl dev/tools/perl_test_runner.pl perl5_t/t
```

### Platform-Specific CI

Ensure CI runs on:
- Linux x86_64 (Ubuntu)
- macOS arm64 (Apple Silicon)
- macOS x86_64 (Intel)
- Windows x64

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Struct layout varies by platform/arch | Create platform-specific layouts, detect at runtime |
| FFM API changes in future Java | FFM is finalized in Java 22, API is stable |
| Performance regression | Benchmark critical paths, FFM should be similar to JNR |
| Windows functionality gaps | Accept limitations, document differences |
| Breaking existing behavior | Feature flag allows gradual rollout |

## Dependencies

### Remove
```groovy
// build.gradle - REMOVE after migration
implementation libs.jnr.posix
```

### Add (none - FFM is part of JDK)

No new dependencies required. FFM is part of the Java standard library since Java 22.

## Documentation Updates

1. Update `README.md` with Java 22+ requirement
2. Update `QUICKSTART.md` with Java 22+ requirement
3. Update `docs/getting-started/installation.md` with Java 22+ requirement
4. Update `dev/presentations/` slides with Java 22+ requirement
5. Remove `--sun-misc-unsafe-memory-access` documentation from jperl scripts
6. Document any Windows-specific limitations

### Files to Update for Java 22

| File | Change |
|------|--------|
| `build.gradle` | `sourceCompatibility = JavaVersion.VERSION_22` |
| `pom.xml` | `<maven.compiler.source>22</maven.compiler.source>` |
| `.github/workflows/*.yml` | `java-version: '22'` |
| `README.md` | "Requires Java 22+" |
| `QUICKSTART.md` | "JDK 22 or later" |
| `docs/getting-started/installation.md` | "Java 22 or higher" |
| `dev/presentations/**/*.md` | "Requires: Java 22+" |

## Progress Tracking

### Current Status: Phase 3 in progress

### Completed Phases
- [x] Phase 1: Infrastructure (2026-03-26)
  - Created FFMPosixInterface with all method signatures
  - Created FFMPosix factory with platform detection
  - Created stub implementations for Linux, macOS, Windows
  - Added feature flag (perlonjava.ffm.enabled)
  - Windows implementation includes Java/ProcessHandle fallbacks
- [x] Phase 2: Simple Functions (2026-03-26)
  - Implemented FFM-based getuid, geteuid, getgid, getegid, getppid, umask, chmod, kill, isatty for Linux/macOS
  - Added $< (REAL_UID) and $> (EFFECTIVE_UID) special variables with lazy evaluation
  - Fixed getppid JVM bytecode emission (was missing explicit handler in EmitOperatorNode)
  - Updated Java version requirement to 22 (FFM finalized in Java 22)
  - Files: FFMPosixLinux.java, ScalarSpecialVariable.java, GlobalContext.java, EmitOperatorNode.java, EmitOperator.java, build.gradle
- [x] Phase 3: Struct-Based Functions (2026-03-26) - partial
  - Implemented stat() and lstat() with FFM
  - Added platform-specific struct stat layouts for Linux x86_64 and macOS x86_64/arm64
  - Proper errno capture using Linker.Option.captureCallState()
  - Tested: stat values match native Perl exactly
  - TODO: getpwnam, getpwuid, getpwent, setpwent, endpwent
- [ ] Phase 4: Windows Support
- [ ] Phase 5: Testing & Migration
- [ ] Phase 6: Cleanup

### Next Steps
1. Implement passwd functions (getpwnam, getpwuid, getpwent, etc.)
2. Define struct passwd layout for Linux and macOS
3. Test on Linux CI

### Resolved Questions
- **macOS vs FFMPosixLinux**: Sharing implementation in FFMPosixLinux works well since both are POSIX-compliant. Platform detection handles struct layout differences.
- **Struct size differences**: Using platform-specific offsets with `IS_MACOS` flag successfully handles different field sizes and layouts.

## References

- [JEP 454: Foreign Function & Memory API](https://openjdk.org/jeps/454)
- [JEP 471: Deprecate Memory-Access Methods in sun.misc.Unsafe](https://openjdk.org/jeps/471)
- [JEP 498: Warn upon Use of Memory-Access Methods in sun.misc.Unsafe](https://openjdk.org/jeps/498)
- [FFM API Documentation](https://docs.oracle.com/en/java/javase/22/core/foreign-function-and-memory-api.html)
- [JNR-POSIX GitHub](https://github.com/jnr/jnr-posix)

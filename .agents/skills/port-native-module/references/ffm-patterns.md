# FFM binding patterns

Read when implementing string, struct, variadic or simple downcalls. Confirm ABI,
platform sizes, errno and memory lifetime against current bindings.

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


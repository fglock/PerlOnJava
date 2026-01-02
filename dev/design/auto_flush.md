# PerlOnJava Handle Auto-Flush Implementation Specification

## Overview

This document specifies the implementation of automatic handle flushing in PerlOnJava when switching between different I/O handles, which prevents output misalignment caused by buffering between STDOUT, STDERR, and other file handles.

## Problem Statement

When output is written to different file handles (STDOUT, STDERR, tied handles) without explicit flushing, buffering can cause output to appear out of order. This is particularly problematic in:

1. **Test frameworks** - Test output and diagnostics may appear misaligned
2. **Interactive programs** - Prompts may not appear before input is requested
3. **Mixed output streams** - Error messages may appear before regular output
4. **Tied handles** - Custom I/O implementations may have inconsistent buffering

## Architecture

### Current State

The `RuntimeIO` class already tracks:
- `lastAccessedHandle` - The last file handle used for I/O operations
- `needFlush` - Boolean flag indicating unflushed output

### Proposed Enhancement

Add automatic flushing when switching between handles:

```java
// New field to track if auto-flush is enabled globally
private static boolean autoFlushOnSwitch = true;

// Method to update last accessed handle with auto-flush
private static void updateLastAccessedHandle(RuntimeIO newHandle) {
    if (autoFlushOnSwitch && 
        lastAccessedHandle != null && 
        lastAccessedHandle != newHandle && 
        lastAccessedHandle.needFlush) {
        lastAccessedHandle.flush();
    }
    lastAccessedHandle = newHandle;
}
```

## Implementation Pattern

### Core Mechanism

1. **Track handle switches**: Before any I/O operation, check if the handle has changed
2. **Flush on switch**: If the previous handle has unflushed data, flush it
3. **Update tracking**: Set the new handle as the last accessed

### Integration Points

All I/O operations must call `updateLastAccessedHandle()`:

```java
public RuntimeScalar write(String data) {
    updateLastAccessedHandle(this);
    needFlush = true;
    return ioHandle.write(data);
}

public RuntimeScalar readline() {
    updateLastAccessedHandle(this);
    // For input operations, also flush stdout/stderr for prompts
    flushFileHandles();
    return ioHandle.readline();
}
```

## Required Method Updates

### RuntimeIO Methods

Update all I/O methods to include handle tracking:

```java
// Write operations
public RuntimeScalar write(String data)
public RuntimeScalar printf(String format, RuntimeList args)
public RuntimeScalar print(RuntimeList args)

// Read operations  
public RuntimeScalar readline()
public RuntimeScalar read(int length)
public RuntimeScalar getc()

// Position operations
public RuntimeScalar seek(long pos)
public RuntimeScalar tell()
public RuntimeScalar eof()

// Binary operations
public RuntimeScalar syswrite(String data, int length, int offset)
public RuntimeScalar sysread(RuntimeScalar buffer, int length, int offset)
```

### Operator Methods

Update print operators to track handle usage:

```java
// In Operator.java
public static RuntimeScalar print(RuntimeList runtimeList, RuntimeScalar fileHandle) {
    // ... existing code ...
    RuntimeIO fh = fileHandle.getRuntimeIO();
    RuntimeIO.updateLastAccessedHandle(fh);  // Add this
    return fh.write(sb.toString());
}
```

### Special Cases

#### Input Operations

For input operations, flush output handles before reading:

```java
public RuntimeScalar readline() {
    updateLastAccessedHandle(this);
    // Flush output handles to ensure prompts appear
    if (this != stdout && this != stderr) {
        flushFileHandles();
    }
    currentLineNumber++;
    return ioHandle.readline();
}
```

#### Select Operations

When changing the default output handle:

```java
public static RuntimeIO select(RuntimeIO newHandle) {
    RuntimeIO oldHandle = selectedHandle;
    if (oldHandle != null && oldHandle.needFlush) {
        oldHandle.flush();
    }
    selectedHandle = newHandle;
    return oldHandle;
}
```

## Performance Considerations

### Optimization Strategies

1. **Conditional flushing**: Only flush when `needFlush` is true
2. **Handle comparison**: Use reference equality for fast comparison
3. **Configurable behavior**: Allow disabling auto-flush for performance-critical code
4. **Batch operations**: Don't flush within tight loops

### Configuration Options

```java
// Global control
public static void setAutoFlushOnSwitch(boolean enabled) {
    autoFlushOnSwitch = enabled;
}

// Per-handle control
public void setAutoFlush(boolean enabled) {
    this.autoFlush = enabled;
}
```

## Buffer Management

### Flush Triggers

Automatic flushing occurs when:
1. Switching to a different handle
2. Before any input operation
3. On explicit `flush()` calls
4. When handle is closed
5. At program termination

### Buffer Size Considerations

```java
// Consider flushing based on buffer size
private static final int AUTO_FLUSH_THRESHOLD = 8192; // 8KB

private boolean shouldAutoFlush() {
    return needFlush && (
        autoFlush ||
        bufferSize > AUTO_FLUSH_THRESHOLD ||
        buffer.contains('\n')  // Line-buffered mode
    );
}
```

## Error Handling

### Flush Failures

Handle flush errors gracefully:

```java
private static void safeFlush(RuntimeIO handle) {
    try {
        if (handle != null && handle.needFlush) {
            handle.flush();
        }
    } catch (Exception e) {
        // Log but don't propagate - avoid disrupting program flow
        System.err.println("Warning: flush failed: " + e.getMessage());
    }
}
```

### Closed Handles

Check handle state before operations:

```java
private static void updateLastAccessedHandle(RuntimeIO newHandle) {
    if (lastAccessedHandle != null && 
        lastAccessedHandle != newHandle &&
        !lastAccessedHandle.isClosed() &&
        lastAccessedHandle.needFlush) {
        lastAccessedHandle.flush();
    }
    lastAccessedHandle = newHandle;
}
```

## Testing Requirements

### Unit Tests

1. **Handle switching**: Verify flush occurs when switching handles
2. **Output ordering**: Ensure output appears in correct sequence
3. **Performance**: Measure overhead of auto-flush mechanism
4. **Error handling**: Test flush failures don't break program flow
5. **Configuration**: Test enabling/disabling auto-flush

### Integration Tests

```perl
# Test output ordering
print STDOUT "1. stdout\n";
print STDERR "2. stderr\n";
print STDOUT "3. stdout\n";
# Should appear in order: 1, 2, 3

# Test with tied handles
tie *FH, 'TestHandle';
print STDOUT "Before tied\n";
print FH "Tied output\n";
print STDOUT "After tied\n";
# All output should appear in order
```

### Performance Tests

```java
// Benchmark handle switching overhead
@Test
public void benchmarkHandleSwitching() {
    long withAutoFlush = timeHandleSwitching(true);
    long withoutAutoFlush = timeHandleSwitching(false);
    
    double overhead = (double)(withAutoFlush - withoutAutoFlush) / withoutAutoFlush;
    assertTrue("Overhead should be less than 5%", overhead < 0.05);
}
```

## Migration Notes

### Backward Compatibility

- Default behavior enables auto-flush for compatibility
- Existing code continues to work unchanged
- Explicit flush() calls remain functional
- No API changes required

### Migration Path

1. **Phase 1**: Implement core auto-flush mechanism
2. **Phase 2**: Update all I/O methods
3. **Phase 3**: Add configuration options
4. **Phase 4**: Optimize based on profiling
5. **Phase 5**: Document best practices

### Potential Issues

1. **Subtle timing changes**: Programs relying on buffering behavior may see different timing
2. **Performance impact**: Frequent handle switching may show slight overhead
3. **Thread safety**: Consider synchronization for multi-threaded programs

## Example Usage

### Basic Handle Switching

```perl
# Auto-flush ensures correct output order
print STDOUT "Enter name: ";    # Appears immediately
my $name = <STDIN>;             # Prompt visible before input
print STDERR "Processing...\n"; # Error appears in sequence
print STDOUT "Hello, $name";    # Final output in order
```

### Tied Handle Integration

```perl
tie *LOG, 'LogFile', 'app.log';
print STDOUT "Starting process\n";
print LOG "Process initiated\n";
print STDERR "Warning: Low memory\n";
print LOG "Memory warning issued\n";
# All output appears in chronological order
```

### Performance-Critical Code

```perl
# Disable auto-flush for bulk operations
RuntimeIO::setAutoFlushOnSwitch(0);

for (1..1000000) {
    print FILE1 "Data $_\n" if $_ % 2;
    print FILE2 "Data $_\n" if $_ % 3;
}

# Re-enable and flush manually
RuntimeIO::setAutoFlushOnSwitch(1);
close FILE1;
close FILE2;
```

## Future Enhancements

1. **Smart buffering**: Detect patterns and optimize flush frequency
2. **Async flushing**: Non-blocking flush for better performance
3. **Buffer pooling**: Reuse buffers to reduce allocation overhead
4. **Metrics**: Track flush frequency and buffer utilization
5. **Handle groups**: Flush related handles together


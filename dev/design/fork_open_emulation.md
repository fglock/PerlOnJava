# Fork-Open Emulation for PerlOnJava

## Problem Statement

Perl's `open FH, "-|"` (2-arg piped open) uses fork() to create a child process:

```perl
my $pid = open *FH, "-|";
if ($pid) {
    # Parent: read from FH (child's stdout)
    my $output = <FH>;
    close FH;
} else {
    # Child: exec the command
    exec @cmd;
}
```

The JVM cannot support `fork()` - there's no way to split the JVM into two identical processes.
However, the 3-arg form `open FH, "-|", @cmd` works fine because it just spawns a process and
pipes its output - no fork needed.

## Solution: Runtime Fork-Open Emulation

Instead of complex AST transformations, we detect the fork-open pattern at runtime and
emulate it by deferring the pipe creation until `exec` is called.

### How It Works

1. **When `open FH, "-|"` is called without a command:**
   - Don't fail immediately
   - Store a "pending fork-open" state with the filehandle reference
   - Return 0 (child PID) to make the code take the "child" branch

2. **When `exec @cmd` is called:**
   - Check for pending fork-open state
   - If pending: create the pipe using 3-arg semantics with @cmd
   - Return to the "parent" code path (after the if/else) with pipe ready
   - The "parent" branch code will then read from FH normally

3. **Reset the pending state on:**
   - Any successful `open` call (new filehandle operation)
   - Any `close` call
   - End of the current statement/block (safety)

### State Machine

```
                    open FH, "-|"
    [NORMAL] ─────────────────────────> [PENDING_FORK_OPEN]
       │                                        │
       │ open/close                             │ exec @cmd
       │ (reset)                                │
       ▼                                        ▼
    [NORMAL] <────────────────────────── [PIPE_READY]
                   continue execution      (return to parent path)
```

### Execution Flow Example

```perl
# Original code:
my $pid = open *FH, "-|";      # Returns 0, sets PENDING state
if ($pid) {                     # False, skip parent branch
    return <FH>;
} else {
    exec "ls", "-la";           # Detects PENDING, creates pipe, 
                                # throws special "return to parent" signal
}
# After exec's special return, $pid is now truthy, FH is ready
# Code continues after the if/else with the pipe working
```

### Implementation Details

#### 1. Pending State Storage (thread-local)

```java
// In a new class or IOOperator
public class ForkOpenState {
    private static final ThreadLocal<PendingForkOpen> pendingState = new ThreadLocal<>();
    
    public static class PendingForkOpen {
        public RuntimeScalar fileHandle;
        public int tokenIndex;  // For error messages
    }
    
    public static void setPending(RuntimeScalar fh, int tokenIndex) { ... }
    public static PendingForkOpen getPending() { ... }
    public static void clear() { ... }
    public static boolean hasPending() { ... }
}
```

#### 2. Modified `open` (IOOperator.java)

```java
// In openPipe or open method:
if (mode.equals("-|") && commandList.isEmpty()) {
    // Fork-open mode without command
    ForkOpenState.setPending(fileHandle, tokenIndex);
    return new RuntimeScalar(0);  // Return 0 = "child" branch
}
```

#### 3. Modified `exec` (SystemOperator.java)

```java
public static RuntimeScalar exec(RuntimeList args, ...) {
    if (ForkOpenState.hasPending()) {
        PendingForkOpen pending = ForkOpenState.getPending();
        ForkOpenState.clear();
        
        // Create the pipe using 3-arg semantics
        RuntimeList openArgs = new RuntimeList();
        openArgs.add(pending.fileHandle);
        openArgs.add(new RuntimeScalar("-|"));
        openArgs.addAll(args);  // The command from exec
        
        RuntimeIO fh = RuntimeIO.openPipe(openArgs);
        // ... set up the filehandle ...
        
        // Throw special exception to return to "parent" path
        throw new ForkOpenCompleteException(processId);
    }
    
    // Normal exec behavior
    ...
}
```

#### 4. Exception Handling

```java
public class ForkOpenCompleteException extends RuntimeException {
    public final int pid;
    public ForkOpenCompleteException(int pid) { this.pid = pid; }
}
```

The calling code needs to catch this and return the PID to make the "parent" branch execute.

#### 5. Reset Points

Add `ForkOpenState.clear()` calls to:
- `IOOperator.open()` - at the start, before any operation
- `IOOperator.close()` - when closing any filehandle
- Potentially in error handlers

### Supported Patterns

This approach handles various fork-open idioms:

```perl
# Classic if/else pattern
my $pid = open FH, "-|";
if ($pid) { ... } else { exec @cmd }

# unless pattern  
my $pid = open FH, "-|";
unless ($pid) { exec @cmd }
...parent code...

# or-exec pattern (common idiom)
open FH, "-|" or exec @cmd;

# Defined-or pattern
my $pid = open FH, "-|";
exec @cmd unless defined $pid;
```

### Limitations

1. **Code between open and exec**: If there's significant code between `open` and `exec`
   in the "child" branch, it will execute. This matches Perl behavior where the child
   does run that code before exec.

2. **Multiple fork-opens**: Only one pending fork-open at a time per thread. Nested
   fork-opens would need stack-based state (future enhancement if needed).

3. **Non-exec child code**: If the child branch does something other than exec (like
   `exit` or complex processing), it won't work. This is a limitation of not having
   real fork.

### Testing

```perl
# Test 1: Basic fork-open pattern
my $pid = open my $fh, "-|";
if ($pid) {
    my $line = <$fh>;
    print "Got: $line";
    close $fh;
} else {
    exec "echo", "hello";
}

# Test 2: or-exec pattern
open my $fh, "-|" or exec "echo", "hello";
my $line = <$fh>;
print "Got: $line";
close $fh;
```

### Related Files

- `src/main/java/org/perlonjava/runtime/operators/IOOperator.java` - open implementation
- `src/main/java/org/perlonjava/runtime/operators/SystemOperator.java` - exec implementation
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeIO.java` - pipe handling

### References

- Perl open documentation: https://perldoc.perl.org/functions/open
- Module::Build `_backticks` method uses this pattern
- IPC::Open2/Open3 also use fork-open patterns

# Implementing Perl's `alarm` Operator in PerlOnJava

## Overview

Perl's `alarm` function arranges for a SIGALRM signal to be delivered after a specified number of seconds. This document describes the challenges and implementation approach for simulating this behavior in Java.

## Perl alarm Behavior

```perl
#!/usr/bin/perl
use strict;
use warnings;

$SIG{ALRM} = sub { die "Time's up!\n" };

print "Enter your name within 5 seconds: ";
alarm(5);

eval {
    my $name = <STDIN>;
    chomp $name;
    alarm(0);  # Cancel the alarm
    print "Hello, $name!\n";
};

if ($@) {
    print "You took too long to respond.\n";
}
```

Key requirements:
- Only one timer can be active at a time
- Setting a new alarm cancels the previous one
- An argument of 0 cancels the timer without starting a new one
- Returns the remaining seconds from the previous timer
- Must interrupt both blocking I/O and CPU-bound operations

## Implementation Challenges

### Challenge 1: Interrupting Blocking I/O

In Perl, SIGALRM can interrupt system calls like `readline`. In Java:
- Thread interruption only works for certain blocking operations
- Not all I/O operations are interruptible
- Requires explicit handling in the I/O code

### Challenge 2: Interrupting CPU-bound Operations

```perl
# This should be interrupted after 2 seconds
$SIG{ALRM} = sub { die "alarm\n" };
alarm(2);
$x++ for 0..1E10;  # Long-running computation
```

In Java:
- Cannot interrupt arbitrary code execution
- Thread.interrupt() doesn't affect CPU-bound operations
- POSIX signals aren't available in pure Java

## Implementation Approach

### 1. Basic Alarm Infrastructure

Add to `TimeHiRes.java`:

```java
private static ScheduledExecutorService alarmScheduler;
private static ScheduledFuture<?> currentAlarmTask;
private static long alarmStartTime;
private static int alarmDuration;
private static volatile boolean alarmFired = false;
private static volatile RuntimeScalar pendingAlarmHandler = null;

static {
    // Initialize the alarm scheduler
    alarmScheduler = Executors.newScheduledThreadPool(1);
}
```

### 2. Alarm Method Implementation

```java
public static RuntimeList alarm(RuntimeArray args, int ctx) {
    int seconds = args.size() > 0 ? args.get(0).toInt() : 0;
    
    // Calculate remaining time on previous timer
    int remainingTime = 0;
    if (currentAlarmTask != null && !currentAlarmTask.isDone()) {
        long elapsedTime = (System.currentTimeMillis() - alarmStartTime) / 1000;
        remainingTime = Math.max(0, alarmDuration - (int)elapsedTime);
        currentAlarmTask.cancel(false);
    }
    
    // Clear any pending alarm
    alarmFired = false;
    pendingAlarmHandler = null;
    
    if (seconds == 0) {
        currentAlarmTask = null;
        return new RuntimeScalar(remainingTime).getList();
    }
    
    // Set up new alarm
    alarmStartTime = System.currentTimeMillis();
    alarmDuration = seconds;
    
    currentAlarmTask = alarmScheduler.schedule(() -> {
        RuntimeScalar sig = getGlobalHash("main::SIG").get("ALRM");
        if (sig.getDefinedBoolean()) {
            pendingAlarmHandler = sig;
            alarmFired = true;
            // Interrupt main thread for blocking I/O
            Thread.currentThread().interrupt();
        }
    }, seconds, TimeUnit.SECONDS);
    
    return new RuntimeScalar(remainingTime).getList();
}
```

### 3. Alarm Checking Mechanism

```java
public static void checkAlarm() {
    if (alarmFired && pendingAlarmHandler != null) {
        alarmFired = false;
        RuntimeScalar handler = pendingAlarmHandler;
        pendingAlarmHandler = null;
        
        // Execute the alarm handler
        RuntimeArray args = new RuntimeArray();
        RuntimeCode.apply(handler, args, RuntimeContextType.SCALAR);
    }
}
```

### 4. Interpreter Integration Points

The PerlOnJava interpreter must call `TimeHiRes.checkAlarm()` at regular intervals:

#### a. Loop Operations
```java
// In for/while/foreach loop implementations
public RuntimeScalar executeForLoop(...) {
    while (condition) {
        TimeHiRes.checkAlarm();  // Check before each iteration
        // ... loop body ...
    }
}
```

#### b. Method Calls
```java
// In method dispatch
public RuntimeScalar callMethod(...) {
    TimeHiRes.checkAlarm();  // Check before method call
    RuntimeScalar result = method.invoke(...);
    TimeHiRes.checkAlarm();  // Check after method call
    return result;
}
```

#### c. Statement Boundaries
```java
// In statement execution
public void executeStatement(Statement stmt) {
    TimeHiRes.checkAlarm();  // Check before each statement
    stmt.execute();
}
```

### 5. I/O Operation Updates

Update blocking I/O operations to handle interrupts:

```java
// In readline implementation
public RuntimeScalar readline() {
    try {
        String line = bufferedReader.readLine();
        return new RuntimeScalar(line);
    } catch (InterruptedIOException e) {
        // Check for pending alarm
        TimeHiRes.checkAlarm();
        throw new PerlCompilerException("Interrupted system call");
    }
}
```

## Implementation Steps

1. **Add alarm infrastructure to TimeHiRes.java**
   - Import required concurrent utilities
   - Add static fields for alarm state
   - Implement checkAlarm() method

2. **Implement the alarm method**
   - Handle timer cancellation and remaining time calculation
   - Schedule alarm callback
   - Set volatile flags for handler execution

3. **Update interpreter core**
   - Add checkAlarm() calls at loop boundaries
   - Add checkAlarm() calls at method entry/exit
   - Add checkAlarm() calls between statements

4. **Update I/O operations**
   - Catch InterruptedException in blocking operations
   - Call checkAlarm() when interrupted
   - Propagate interruption as Perl exception

5. **Test the implementation**
   - Test interrupting blocking I/O (readline)
   - Test interrupting CPU-bound loops
   - Test alarm cancellation and nesting

## Limitations

1. **Timing Precision**: Alarm checks only happen at specific points, so the actual interruption may be delayed
2. **Performance Impact**: Frequent checkAlarm() calls add overhead
3. **Not True Signals**: This is a simulation, not real POSIX signal handling

## Alternative Approaches Considered

1. **Thread.stop()**: Deprecated and unsafe, can leave objects in inconsistent state
2. **Bytecode Instrumentation**: Too complex and performance-intensive
3. **Separate Alarm Thread**: Cannot safely interrupt the main thread's execution

## Conclusion

While we cannot perfectly replicate Perl's signal-based alarm in Java, this cooperative checking approach provides a reasonable approximation that handles both blocking I/O and CPU-bound operations, with acceptable performance overhead for most use cases.

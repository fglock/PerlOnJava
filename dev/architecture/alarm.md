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
- Signal handlers must execute in the original thread's context

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

### Challenge 3: Signal Handler Context

Signal handlers must execute in the context of the interrupted thread, not the timer thread, to properly throw exceptions that can be caught by `eval`.

## Implementation Approach

### 1. Signal Queue Infrastructure

Create a thread-safe queue for pending signals:

```java
// PerlSignalQueue.java
public class PerlSignalQueue {
    private static final Queue<SignalEvent> signalQueue = new ConcurrentLinkedQueue<>();
    
    static class SignalEvent {
        String signal;
        RuntimeScalar handler;
        
        SignalEvent(String signal, RuntimeScalar handler) {
            this.signal = signal;
            this.handler = handler;
        }
    }
    
    public static void enqueue(String signal, RuntimeScalar handler) {
        signalQueue.offer(new SignalEvent(signal, handler));
    }
    
    public static void processSignals() {
        SignalEvent event;
        while ((event = signalQueue.poll()) != null) {
            // Execute the handler - this may throw PerlDieException
            RuntimeArray args = new RuntimeArray();
            args.push(new RuntimeScalar(event.signal));
            RuntimeCode.apply(event.handler, args, RuntimeContextType.VOID);
        }
    }
}
```

### 2. Enhanced Alarm Infrastructure

Update `TimeHiRes.java` or create `NativeUtils.java`:

```java
private static final ScheduledExecutorService alarmScheduler = 
    Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("PerlAlarmTimer");
        return t;
    });

private static ScheduledFuture<?> currentAlarmTask;
private static long alarmStartTime;
private static int alarmDuration;
private static Thread alarmTargetThread;

public static RuntimeScalar alarm(RuntimeBase... args) {
    int seconds = args.length > 0 ? args[0].getInt() : 0;
    
    // Calculate remaining time on previous timer
    int remainingTime = 0;
    if (currentAlarmTask != null && !currentAlarmTask.isDone()) {
        long elapsedTime = (System.currentTimeMillis() - alarmStartTime) / 1000;
        remainingTime = Math.max(0, alarmDuration - (int)elapsedTime);
        currentAlarmTask.cancel(false);
    }
    
    if (seconds == 0) {
        currentAlarmTask = null;
        return new RuntimeScalar(remainingTime);
    }
    
    // Set up new alarm
    alarmStartTime = System.currentTimeMillis();
    alarmDuration = seconds;
    alarmTargetThread = Thread.currentThread();
    
    currentAlarmTask = alarmScheduler.schedule(() -> {
        RuntimeScalar sig = getGlobalHash("main::SIG").get("ALRM");
        if (sig.getDefinedBoolean()) {
            // Queue the signal for processing in the target thread
            PerlSignalQueue.enqueue("ALRM", sig);
            // Interrupt the target thread to break out of blocking operations
            alarmTargetThread.interrupt();
        }
    }, seconds, TimeUnit.SECONDS);
    
    return new RuntimeScalar(remainingTime);
}
```

### 3. Signal Processing Integration

The PerlOnJava interpreter must process signals at safe points:

```java
public static void checkPendingSignals() {
    // Process any queued signals
    PerlSignalQueue.processSignals();
    
    // Clear interrupt flag if it was set by alarm
    if (Thread.interrupted()) {
        // The interrupt was handled via signal processing
    }
}
```

### 4. Interpreter Integration Points

#### a. Loop Operations
```java
// In for/while/foreach loop implementations
public RuntimeScalar executeForLoop(...) {
    while (condition) {
        checkPendingSignals();  // Check before each iteration
        // ... loop body ...
    }
}
```

#### b. Statement Boundaries
```java
// In statement execution
public void executeStatement(Statement stmt) {
    checkPendingSignals();  // Check before each statement
    stmt.execute();
}
```

#### c. Method Entry/Exit
```java
// In method dispatch
public RuntimeScalar callMethod(...) {
    checkPendingSignals();  // Check before method call
    RuntimeScalar result = method.invoke(...);
    checkPendingSignals();  // Check after method call
    return result;
}
```

### 5. I/O Operation Updates

Update blocking I/O operations to handle interrupts properly:

```java
// In readline implementation
public RuntimeScalar readline() {
    try {
        String line = bufferedReader.readLine();
        checkPendingSignals();  // Check after successful read
        return new RuntimeScalar(line);
    } catch (InterruptedIOException e) {
        // Process pending signals (which may throw)
        checkPendingSignals();
        // If no exception thrown, return undef
        return RuntimeScalar.undef();
    } catch (IOException e) {
        if (Thread.interrupted()) {
            // Clear interrupt and process signals
            checkPendingSignals();
            return RuntimeScalar.undef();
        }
        throw new PerlCompilerException("I/O error: " + e.getMessage());
    }
}

// In sysread implementation
public RuntimeScalar sysread(RuntimeBase... args) {
    try {
        // Use interruptible NIO channels when possible
        int bytesRead = channel.read(buffer);
        checkPendingSignals();
        return new RuntimeScalar(bytesRead);
    } catch (ClosedByInterruptException e) {
        checkPendingSignals();
        return new RuntimeScalar(-1);
    }
}
```

### 6. Native Implementation Option

For better compatibility on POSIX systems, consider JNA-based implementation:

```java
// In NativeUtils.java
public interface PosixLibrary extends Library {
    PosixLibrary INSTANCE = Native.load("c", PosixLibrary.class);
    
    int alarm(int seconds);
    SignalHandler signal(int signum, SignalHandler handler);
    
    interface SignalHandler extends Callback {
        void invoke(int signal);
    }
}

public static RuntimeScalar alarmNative(RuntimeBase... args) {
    if (!IS_WINDOWS) {
        int seconds = args.length > 0 ? args[0].getInt() : 0;
        
        // Set up SIGALRM handler
        PosixLibrary.SignalHandler handler = (signal) -> {
            RuntimeScalar sig = getGlobalHash("main::SIG").get("ALRM");
            if (sig.getDefinedBoolean()) {
                PerlSignalQueue.enqueue("ALRM", sig);
            }
        };
        
        PosixLibrary.INSTANCE.signal(14 /* SIGALRM */, handler);
        int remaining = PosixLibrary.INSTANCE.alarm(seconds);
        return new RuntimeScalar(remaining);
    } else {
        // Fall back to Java implementation
        return alarm(args);
    }
}
```

## Implementation Steps

1. **Create PerlSignalQueue class**
   - Thread-safe queue for signal events
   - Process signals in original thread context

2. **Add alarm infrastructure**
   - Scheduled executor for timers
   - Thread tracking for interruption
   - Signal queuing instead of direct execution

3. **Update interpreter core**
   - Add checkPendingSignals() method
   - Call it at safe execution points
   - Handle Thread.interrupted() flag

4. **Update I/O operations**
   - Use NIO channels where possible for better interruption
   - Catch specific interrupted exceptions
   - Process signals when interrupted

5. **Optional: Add native implementation**
   - Use JNA for real SIGALRM on POSIX systems
   - Provides better compatibility

6. **Test the implementation**
   - Test interrupting blocking I/O (readline, sysread)
   - Test interrupting CPU-bound loops
   - Test alarm cancellation and nesting
   - Test die propagation from signal handlers

## Advantages of This Approach

1. **Proper Context**: Signal handlers execute in the interrupted thread
2. **Better I/O Handling**: Specific handling for different interruption types
3. **Native Option**: Can use real signals on POSIX systems
4. **Thread Safety**: Signal queue prevents race conditions

## Limitations

1. **Timing Precision**: Signal checks only happen at specific points
2. **Performance Impact**: Frequent signal checks add overhead
3. **Platform Differences**: Windows vs POSIX behavior may differ
4. **Not All I/O Interruptible**: Some Java I/O operations cannot be interrupted

## Conclusion

This hybrid approach provides the best compromise between compatibility and implementation complexity. The signal queue ensures handlers execute in the proper context, while strategic checkpoints provide reasonable interruption granularity. The optional native implementation offers better POSIX compatibility when needed.

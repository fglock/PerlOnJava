
# To simulate the behavior of Perl's `alarm`

Perl example:

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

1. **Add Required Imports:**

   Add the necessary imports for using `ScheduledExecutorService` and related classes.

   ```java
   import java.util.concurrent.Executors;
   import java.util.concurrent.ScheduledExecutorService;
   import java.util.concurrent.TimeUnit;
   ```

2. **Modify the `alarm` Method:**

   Update the `alarm` method in `TimeHiRes.java` to schedule a task that simulates the alarm functionality.

   ```java:src/main/java/org/perlonjava/perlmodule/TimeHiRes.java
   public static RuntimeList alarm(RuntimeArray args, int ctx) {
       int seconds = args.get(0).toInt(); // Get the number of seconds from the arguments
       ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

       scheduler.schedule(() -> {
           // Simulate the alarm by throwing an exception or executing a callback
           System.err.println("Time's up!");
           // You can also throw an exception here if needed
       }, seconds, TimeUnit.SECONDS);

       return new RuntimeScalar(0).getList();
   }
   ```

3. **Usage Example:**

   Here's how you might use the modified `alarm` method in a Java application to simulate the Perl script's behavior:

   ```java
   public class AlarmExample {
       public static void main(String[] args) {
           TimeHiRes.initialize();
           RuntimeArray alarmArgs = new RuntimeArray();
           alarmArgs.add(new RuntimeScalar(5)); // Set alarm for 5 seconds

           TimeHiRes.alarm(alarmArgs, 0);

           try {
               System.out.print("Enter your name within 5 seconds: ");
               // Simulate user input with a delay
               Thread.sleep(6000);
               System.out.println("Hello, User!");
           } catch (InterruptedException e) {
               System.out.println("You took too long to respond.");
           }
       }
   }
   ```

## Explanation

- **ScheduledExecutorService:** This Java utility is used to schedule tasks to run after a delay. It is a suitable replacement for Perl's `alarm` function.
- **RuntimeArray and RuntimeScalar:** These classes are used to handle arguments in the PerlOnJava environment.
- **Simulating Alarm:** The scheduled task prints a message to simulate the alarm. You could also throw an exception or execute a callback to handle the alarm event.




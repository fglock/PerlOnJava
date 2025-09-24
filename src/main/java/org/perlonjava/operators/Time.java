package org.perlonjava.operators;

import org.perlonjava.runtime.*;
import org.perlonjava.runtime.DynamicVariableManager;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;

import static org.perlonjava.runtime.ErrorMessageUtil.stringifyException;
import static org.perlonjava.runtime.GlobalVariable.getGlobalHash;
import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;

/**
 * The Time class provides utility methods for retrieving and formatting time-related information.
 */
public class Time {

    // Static scheduler for alarm functionality
    private static final ScheduledExecutorService alarmScheduler = Executors.newSingleThreadScheduledExecutor();
    private static ScheduledFuture<?> currentAlarm = null;

    /**
     * Returns the current time in seconds since the Unix epoch with second precision.
     *
     * @return a RuntimeScalar representing the current time in seconds.
     */
    public static RuntimeScalar time() {
        return new RuntimeScalar(System.currentTimeMillis() / 1000L);
    }

    /**
     * Returns a list of CPU time statistics for the current thread.
     * The list contains user CPU time and system CPU time in seconds.
     *
     * @return a RuntimeList containing user and system CPU time.
     */
    public static RuntimeList times() {
        RuntimeList res = new RuntimeList();

        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        long cpu = bean.isCurrentThreadCpuTimeSupported() ?
                bean.getCurrentThreadCpuTime() : 0L;
        long user = bean.isCurrentThreadCpuTimeSupported() ?
                bean.getCurrentThreadUserTime() : 0L;
        long system = cpu - user;

        res.add(user / 1.0E9); // user CPU time
        res.add(system / 1.0E9); // System CPU time
        res.add(0); // we don't have this information
        res.add(0); // we don't have this information
        return res;
    }

    /**
     * Converts a given epoch time to local time and returns a formatted list of time components.
     *
     * @param args a RuntimeList containing the epoch time as the first element.
     * @param ctx  the context type, which determines the format of the result.
     * @return a RuntimeList containing formatted local time components.
     */
    public static RuntimeList localtime(RuntimeList args, int ctx) {
        ZonedDateTime date;
        if (args.isEmpty()) {
            date = ZonedDateTime.now();
        } else {
            long arg = args.getFirst().getInt();
            date = Instant.ofEpochSecond(arg).atZone(ZoneId.systemDefault());
        }
        return getTimeComponents(ctx, date);
    }

    /**
     * Converts a given epoch time to UTC time and returns a formatted list of time components.
     *
     * @param args a RuntimeList containing the epoch time as the first element.
     * @param ctx  the context type, which determines the format of the result.
     * @return a RuntimeList containing formatted UTC time components.
     */
    public static RuntimeList gmtime(RuntimeList args, int ctx) {
        ZonedDateTime date;
        if (args.isEmpty()) {
            date = ZonedDateTime.now(ZoneOffset.UTC);
        } else {
            long arg = args.getFirst().getInt();
            date = Instant.ofEpochSecond(arg).atZone(ZoneId.of("UTC"));
        }
        return getTimeComponents(ctx, date);
    }

    private static RuntimeList getTimeComponents(int ctx, ZonedDateTime date) {
        RuntimeList res = new RuntimeList();
        if (ctx == RuntimeContextType.SCALAR) {
            res.add(date.format(DateTimeFormatter.RFC_1123_DATE_TIME));
            return res;
        }
        //      0    1    2     3     4    5     6     7     8
        //   ($sec,$min,$hour,$mday,$mon,$year,$wday,$yday,$isdst)
        res.add(date.getSecond());
        res.add(date.getMinute());
        res.add(date.getHour());
        res.add(date.getDayOfMonth());
        res.add(date.getMonth().getValue() - 1);
        res.add(date.getYear() - 1900);
        res.add(date.getDayOfWeek().getValue());
        res.add(date.getDayOfYear() - 1);
        res.add(date.getZone().getRules().isDaylightSavings(date.toInstant()) ? 1 : 0);
        return res;
    }

    public static RuntimeScalar sleep(RuntimeScalar runtimeScalar) {
        RuntimeIO.flushAllHandles();

        long s = (long) (runtimeScalar.getDouble() * 1000);

        if (s < 0) {
            getGlobalVariable("main::!").set("Invalid argument");
            WarnDie.warn(
                    new RuntimeScalar(stringifyException(
                            new PerlCompilerException("sleep() with negative argument")
                    )),
                    new RuntimeScalar());
            return getScalarInt(0);
        }

        long startTime = System.nanoTime();
        try {
            TimeUnit.MILLISECONDS.sleep(s);
        } catch (InterruptedException e) {
            // Handle interruption if needed
            RuntimeScalar alarmHandler = getGlobalHash("main::SIG").get("ALRM");
            if (alarmHandler.getDefinedBoolean()) {
                RuntimeArray args = new RuntimeArray();
                RuntimeCode.apply(alarmHandler, args, RuntimeContextType.SCALAR);
            }
        }
        long endTime = System.nanoTime();
        long actualSleepTime = endTime - startTime;
        return new RuntimeScalar(actualSleepTime / 1_000_000_000.0);
    }

    /**
     * Sets an alarm to go off after the specified number of seconds.
     * Returns the number of seconds remaining from a previous alarm, if any.
     *
     * @param ctx the runtime context
     * @param args the arguments (seconds to wait)
     * @return a RuntimeScalar representing the seconds remaining from previous alarm
     */
    public static RuntimeScalar alarm(int ctx, RuntimeBase... args) {
        int seconds = 0;
        if (args.length > 0) {
            seconds = args[0].scalar().getInt();
        }

        // Get the remaining time from any previous alarm
        int remainingSeconds = 0;
        if (currentAlarm != null && !currentAlarm.isDone()) {
            // Cancel the previous alarm and get remaining time
            remainingSeconds = (int) Math.ceil(currentAlarm.getDelay(TimeUnit.SECONDS));
            currentAlarm.cancel(false);
            currentAlarm = null;
        }

        // Set new alarm if seconds > 0
        if (seconds > 0) {
            currentAlarm = alarmScheduler.schedule(() -> {
                // Trigger SIGALRM handler using WarnDie pattern
                RuntimeScalar alarmHandler = getGlobalHash("main::SIG").get("ALRM");
                if (alarmHandler.getDefinedBoolean()) {
                    try {
                        RuntimeScalar sigHandler = new RuntimeScalar(alarmHandler);
                        
                        // Undefine $SIG{ALRM} before calling the handler to avoid infinite recursion
                        int level = DynamicVariableManager.getLocalLevel();
                        DynamicVariableManager.pushLocalVariable(alarmHandler);
                        
                        RuntimeArray alarmArgs = new RuntimeArray();
                        alarmArgs.add(new RuntimeScalar("ALRM"));  // Signal name as argument
                        RuntimeCode.apply(sigHandler, alarmArgs, RuntimeContextType.SCALAR);
                        
                        // Restore $SIG{ALRM}
                        DynamicVariableManager.popToLocalLevel(level);
                    } catch (Exception e) {
                        // If no handler or handler fails, just continue
                        System.err.println("Alarm signal handler error: " + e.getMessage());
                    }
                }
            }, seconds, TimeUnit.SECONDS);
        }

        return new RuntimeScalar(remainingSeconds);
    }
}

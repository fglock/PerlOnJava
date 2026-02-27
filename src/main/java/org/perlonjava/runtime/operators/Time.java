package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.runtimetypes.*;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.perlonjava.runtime.runtimetypes.ErrorMessageUtil.stringifyException;
import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalHash;
import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.getScalarInt;

/**
 * The Time class provides utility methods for retrieving and formatting time-related information.
 */
public class Time {

    // Static scheduler for alarm functionality - using daemon threads to allow JVM exit
    private static final ScheduledExecutorService alarmScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "PerlAlarmTimer");
        t.setDaemon(true);  // Daemon thread won't prevent JVM shutdown
        return t;
    });
    private static ScheduledFuture<?> currentAlarmTask = null;
    private static long alarmStartTime;
    private static int alarmDuration;
    private static Thread alarmTargetThread;

    /**
     * Returns the current time in seconds since the Unix epoch with second precision.
     *
     * @return a RuntimeScalar representing the current time in seconds.
     */
    public static RuntimeScalar time() {
        return new RuntimeScalar(System.currentTimeMillis() / 1000L);
    }

    /**
     * Returns CPU time statistics for the current thread.
     * In list context, returns (user, system, cuser, csys) times in seconds.
     * In scalar context, returns only the user CPU time in seconds.
     *
     * @param ctx the context (RuntimeContextType.SCALAR or RuntimeContextType.LIST)
     * @return a RuntimeList containing CPU time values
     */
    public static RuntimeList times(int ctx) {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        long cpu = bean.isCurrentThreadCpuTimeSupported() ?
                bean.getCurrentThreadCpuTime() : 0L;
        long user = bean.isCurrentThreadCpuTimeSupported() ?
                bean.getCurrentThreadUserTime() : 0L;
        long system = cpu - user;

        double userTime = user / 1.0E9;  // user CPU time in seconds
        double systemTime = system / 1.0E9;  // system CPU time in seconds

        RuntimeList res = new RuntimeList();
        if (ctx == RuntimeContextType.SCALAR) {
            // In scalar context, return just the user CPU time
            res.add(userTime);
        } else {
            // In list context, return all four values
            res.add(userTime);  // user CPU time
            res.add(systemTime);  // system CPU time
            res.add(0);  // child user CPU time (not available in Java)
            res.add(0);  // child system CPU time (not available in Java)
        }
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
            long epoch = (long) Math.floor(args.getFirst().getDouble());
            date = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault());
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
            long epoch = (long) Math.floor(args.getFirst().getDouble());
            date = Instant.ofEpochSecond(epoch).atZone(ZoneId.of("UTC"));
        }
        return getTimeComponents(ctx, date);
    }

    private static RuntimeList getTimeComponents(int ctx, ZonedDateTime date) {
        RuntimeList res = new RuntimeList();
        if (ctx == RuntimeContextType.SCALAR) {
            String[] days = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
            String[] months = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
                               "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
            int dow = date.getDayOfWeek().getValue(); // Mon=1..Sun=7
            String dayStr = days[dow - 1];
            String monStr = months[date.getMonth().getValue() - 1];
            int mday = date.getDayOfMonth();
            String timeStr = String.format("%02d:%02d:%02d", date.getHour(), date.getMinute(), date.getSecond());
            res.add(new RuntimeScalar(String.format("%s %s %2d %s %d", dayStr, monStr, mday, timeStr, date.getYear())));
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
        int dow = date.getDayOfWeek().getValue();  // Mon=1..Sun=7
        res.add(dow == 7 ? 0 : dow);               // Sun=0, Mon=1..Sat=6
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
            // Sleep was interrupted (likely by alarm())
            // Process any pending signals through the signal queue
            PerlSignalQueue.checkPendingSignals();
            // If the signal handler threw an exception (die), it will propagate from checkPendingSignals()
        }
        long endTime = System.nanoTime();
        long actualSleepTime = endTime - startTime;
        return new RuntimeScalar(actualSleepTime / 1_000_000_000.0);
    }

    /**
     * Sets an alarm to go off after the specified number of seconds.
     * Returns the number of seconds remaining from a previous alarm, if any.
     *
     * @param ctx  the runtime context
     * @param args the arguments (seconds to wait)
     * @return a RuntimeScalar representing the seconds remaining from previous alarm
     */
    public static RuntimeScalar alarm(int ctx, RuntimeBase... args) {
        int seconds = 0;
        if (args.length > 0) {
            seconds = args[0].scalar().getInt();
        }

        // Calculate remaining time on previous timer
        int remainingTime = 0;
        if (currentAlarmTask != null && !currentAlarmTask.isDone()) {
            long elapsedTime = (System.currentTimeMillis() - alarmStartTime) / 1000;
            remainingTime = Math.max(0, alarmDuration - (int) elapsedTime);
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

    /**
     * Check and process any pending signals.
     * This method should be called at safe execution points in the interpreter.
     */
    public static void checkPendingSignals() {
        // Process any queued signals
        PerlSignalQueue.processSignals();

        // Clear interrupt flag if it was set by alarm
        if (Thread.interrupted()) {
            // The interrupt was handled via signal processing
        }
    }

    /**
     * Check if an alarm is currently active.
     * Used by regex engine to decide whether to use timeout wrapper.
     *
     * @return true if alarm is active, false otherwise
     */
    public static boolean hasActiveAlarm() {
        return currentAlarmTask != null && !currentAlarmTask.isDone();
    }

    /**
     * Get the remaining time in seconds until the alarm fires.
     *
     * @return seconds remaining, or 0 if no alarm active
     */
    public static int getAlarmRemainingSeconds() {
        if (!hasActiveAlarm()) {
            return 0;
        }
        long elapsedTime = (System.currentTimeMillis() - alarmStartTime) / 1000;
        return Math.max(0, alarmDuration - (int) elapsedTime);
    }

    /**
     * Shuts down the alarm scheduler to allow clean JVM exit.
     * This method is called by the shutdown hook and can also be called manually.
     */
    public static void shutdownAlarmScheduler() {
        if (currentAlarmTask != null && !currentAlarmTask.isDone()) {
            currentAlarmTask.cancel(false);
            currentAlarmTask = null;
        }

        if (!alarmScheduler.isShutdown()) {
            alarmScheduler.shutdown();
            try {
                // Wait a reasonable time for existing tasks to terminate
                if (!alarmScheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                    alarmScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                // Re-cancel if current thread also interrupted
                alarmScheduler.shutdownNow();
                // Preserve interrupt status
                Thread.currentThread().interrupt();
            }
        }
    }
}

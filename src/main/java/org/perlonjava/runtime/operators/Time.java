package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.runtimetypes.*;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.Locale;
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
            double dval = args.getFirst().getDouble();
            if (Double.isNaN(dval) || Double.isInfinite(dval)) {
                return returnUndefOrEmptyList(ctx);
            }
            long arg = args.getFirst().getLong();
            try {
                date = Instant.ofEpochSecond(arg).atZone(ZoneId.systemDefault());
            } catch (DateTimeException e) {
                emitTimeOverflowWarnings("localtime", arg);
                return returnUndefOrEmptyList(ctx);
            }
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
            double dval = args.getFirst().getDouble();
            if (Double.isNaN(dval) || Double.isInfinite(dval)) {
                return returnUndefOrEmptyList(ctx);
            }
            long arg = args.getFirst().getLong();
            try {
                date = Instant.ofEpochSecond(arg).atZone(ZoneId.of("UTC"));
            } catch (DateTimeException e) {
                emitTimeOverflowWarnings("gmtime", arg);
                return returnUndefOrEmptyList(ctx);
            }
        }
        return getTimeComponents(ctx, date);
    }

    // Perl's scalar gmtime/localtime returns ctime(3) format: "Sun Jan  1 00:00:00 1970"
    // Do NOT use DateTimeFormatter.RFC_1123_DATE_TIME — it produces "Sun, 1 Jan 1970 00:00:00 GMT"
    // which has wrong field order/format, and crashes with DateTimeException for years > 4 digits.
    private static String formatCtime(ZonedDateTime date) {
        String dow = date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        String mon = date.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        int day = date.getDayOfMonth();
        String dayStr = day < 10 ? " " + day : String.valueOf(day);
        int h = date.getHour(), m = date.getMinute(), s = date.getSecond();
        int year = date.getYear();
        return String.format("%s %s %s %02d:%02d:%02d %d", dow, mon, dayStr, h, m, s, year);
    }

    private static void emitTimeOverflowWarnings(String funcName, long arg) {
        String direction = arg > 0 ? "too large" : "too small";
        WarnDie.warn(
                new RuntimeScalar(funcName + "(" + arg + ") " + direction),
                new RuntimeScalar("\n")
        );
        WarnDie.warn(
                new RuntimeScalar(funcName + "(" + arg + ") failed"),
                new RuntimeScalar("\n")
        );
    }

    private static RuntimeList returnUndefOrEmptyList(int ctx) {
        RuntimeList res = new RuntimeList();
        if (ctx == RuntimeContextType.SCALAR) {
            res.add(new RuntimeScalar()); // undef
        }
        return res;
    }

    private static RuntimeList getTimeComponents(int ctx, ZonedDateTime date) {
        RuntimeList res = new RuntimeList();
        if (ctx == RuntimeContextType.SCALAR) {
            res.add(formatCtime(date));
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
        // Java DayOfWeek: 1=Mon..7=Sun; Perl wday: 0=Sun..6=Sat. The % 7 maps 7(Sun)->0, 1(Mon)->1, etc.
        res.add(date.getDayOfWeek().getValue() % 7);
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
            try {
                RuntimeScalar sig = getGlobalHash("main::SIG").get("ALRM");
                if (sig.getDefinedBoolean()) {
                    // Queue the signal for processing in the target thread
                    PerlSignalQueue.enqueue("ALRM", sig);
                    // Interrupt the target thread to break out of blocking operations
                    alarmTargetThread.interrupt();
                }
            } finally {
            }
        }, seconds, TimeUnit.SECONDS);

        return new RuntimeScalar(remainingTime);
    }

    /**
     * Check and process any pending signals.
     * This method should be called at safe execution points in the interpreter.
     */
    public static void checkPendingSignals() {
        PerlSignalQueue.processSignals();
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

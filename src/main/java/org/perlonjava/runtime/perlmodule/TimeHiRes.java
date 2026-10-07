package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.MathOperators;
import org.perlonjava.runtime.operators.Time;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.time.Instant;

public class TimeHiRes extends PerlModuleBase {

    private static final long MONOTONIC_EPOCH_OFFSET_NANOS;

    static {
        MONOTONIC_EPOCH_OFFSET_NANOS = calibrateMonotonicEpochOffset();
    }

    public TimeHiRes() {
        super("Time::HiRes", false);
    }

    public static void initialize() {
        TimeHiRes module = new TimeHiRes();
        try {
            module.registerMethod("usleep", null);
            module.registerMethod("nanosleep", null);
            module.registerMethod("gettimeofday", null);
            // getLong() can fetch tied scalars or run numeric overload code.
            // Keep call-depth tracking so those callbacks remain visible.
            module.registerMethodWithReadOnlyArgsWithoutJvmClosureFrame("clock_gettime", null);
            module.registerLeafMethodWithReadOnlyArgs("time", "");
            module.registerMethod("sleep", null);
            module.registerMethod("alarm", null);
            module.registerMethod("ualarm", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Time::HiRes method: " + e.getMessage());
        }
    }

    public static RuntimeList usleep(RuntimeArray args, int ctx) {
        return Time.sleepPrecise(
                MathOperators.divide(args.get(0), new RuntimeScalar(1E6))
        ).getList();
    }

    public static RuntimeList nanosleep(RuntimeArray args, int ctx) {
        return Time.sleepPrecise(
                MathOperators.divide(args.get(0), new RuntimeScalar(1E9))
        ).getList();
    }

    public static RuntimeList gettimeofday(RuntimeArray args, int ctx) {
        Instant now = Instant.now();
        long seconds = now.getEpochSecond();
        long micros = now.getNano() / 1000L;
        // In SCALAR/VOID context Time::HiRes::gettimeofday returns a single
        // floating-point number `seconds + micros/1_000_000`. In LIST context
        // it returns the (seconds, microseconds) pair as integers.
        if (ctx != RuntimeContextType.LIST) {
            double preciseEpochTime = seconds + micros / 1_000_000.0;
            return new RuntimeScalar(preciseEpochTime).getList();
        }
        RuntimeList result = new RuntimeList();
        result.add(new RuntimeScalar(seconds));
        result.add(new RuntimeScalar(micros));
        return result;
    }

    /** Return a high-resolution clock reading in seconds. */
    public static RuntimeList clock_gettime(RuntimeArray args, int ctx) {
        long clockId = args.get(0).getLong();
        double value = switch ((int) clockId) {
            case 0 -> {
                Instant now = Instant.now();
                yield now.getEpochSecond() + now.getNano() / 1_000_000_000.0;
            }
            // CLOCK_MONOTONIC is 1 on Linux and 6 on Darwin. Accept both so
            // Perl code that passes the host's POSIX identifier keeps working.
            case 1, 6 -> System.nanoTime() / 1_000_000_000.0;
            default -> throw new IllegalArgumentException("Unsupported clock id: " + clockId);
        };
        return new RuntimeScalar(value).getList();
    }

    /**
     * Returns the current time in seconds since the Unix epoch with high precision.
     *
     * @return a RuntimeScalar representing the current time in seconds.
     */
    public static RuntimeList time(RuntimeArray args, int ctx) {
        return new RuntimeScalar(monotonicEpochSeconds()).getList();
    }

    /**
     * Returns the current epoch time from the monotonic clock calibrated once
     * against wall time. Timed shared-condition waits use this same source so
     * they compare an absolute Perl deadline without crossing clock domains.
     */
    public static double monotonicEpochSeconds() {
        return monotonicEpochNanos() / 1_000_000_000.0;
    }

    /** Convert an absolute Perl epoch deadline into a monotonic wait budget. */
    public static long nanosUntilEpoch(double deadlineSeconds) {
        double remainingSeconds = deadlineSeconds - monotonicEpochSeconds();
        return Math.max(0L, (long) (remainingSeconds * 1_000_000_000L));
    }

    private static long monotonicEpochNanos() {
        return System.nanoTime() + MONOTONIC_EPOCH_OFFSET_NANOS;
    }

    /**
     * Calibrate with several nano-before/wall/nano-after samples. The narrowest
     * bracket has the least uncertainty about when the wall clock was read.
     */
    private static long calibrateMonotonicEpochOffset() {
        long bestBracket = Long.MAX_VALUE;
        long bestOffset = 0L;
        for (int sample = 0; sample < 8; sample++) {
            long before = System.nanoTime();
            long wallNanos = System.currentTimeMillis() * 1_000_000L;
            long after = System.nanoTime();
            long bracket = after - before;
            if (bracket < bestBracket) {
                bestBracket = bracket;
                bestOffset = wallNanos - before - bracket / 2;
            }
        }
        return bestOffset;
    }

    public static RuntimeList sleep(RuntimeArray args, int ctx) {
        return Time.sleepPrecise(args.get(0)).getList();
    }

    public static RuntimeList alarm(RuntimeArray args, int ctx) {
        // Implement alarm functionality if needed
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList ualarm(RuntimeArray args, int ctx) {
        // Match the existing alarm compatibility behavior. Registering the
        // function is important even where JVM signal delivery is unavailable:
        // callers commonly use ualarm(0) to cancel an optional timeout.
        return new RuntimeScalar(0).getList();
    }
}

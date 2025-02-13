package org.perlonjava.perlmodule;

import org.perlonjava.operators.MathOperators;
import org.perlonjava.operators.Time;
import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.time.Instant;

public class TimeHiRes extends PerlModuleBase {

    public TimeHiRes() {
        super("Time::HiRes", false);
    }

    public static void initialize() {
        TimeHiRes module = new TimeHiRes();
        try {
            module.registerMethod("usleep", null);
            module.registerMethod("nanosleep", null);
            module.registerMethod("gettimeofday", null);
            module.registerMethod("time", null);
            module.registerMethod("sleep", null);
            module.registerMethod("alarm", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Time::HiRes method: " + e.getMessage());
        }
    }

    public static RuntimeList usleep(RuntimeArray args, int ctx) {
        return Time.sleep(
                MathOperators.divide(args.get(0), new RuntimeScalar(1E6))
        ).getList();
    }

    public static RuntimeList nanosleep(RuntimeArray args, int ctx) {
        return Time.sleep(
                MathOperators.divide(args.get(0), new RuntimeScalar(1E9))
        ).getList();
    }

    public static RuntimeList gettimeofday(RuntimeArray args, int ctx) {
        Instant now = Instant.now();
        long seconds = now.getEpochSecond();
        long microseconds = now.getNano() / 1000;
        RuntimeList result = new RuntimeList();
        result.add(new RuntimeScalar(seconds));
        result.add(new RuntimeScalar(microseconds));
        return result;
    }

    public static RuntimeList time(RuntimeArray args, int ctx) {
        return new RuntimeScalar(System.currentTimeMillis() / 1000.0).getList();
    }

    public static RuntimeList sleep(RuntimeArray args, int ctx) {
        return Time.sleep(args.get(0)).getList();
    }

    public static RuntimeList alarm(RuntimeArray args, int ctx) {
        // Implement alarm functionality if needed
        return new RuntimeScalar(0).getList();
    }
}

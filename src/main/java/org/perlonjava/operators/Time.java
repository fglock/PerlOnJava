package org.perlonjava.operators;

import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The Time class provides utility methods for retrieving and formatting time-related information.
 */
public class Time {

    /**
     * Returns the current time in seconds since the Unix epoch.
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
        RuntimeList res = new RuntimeList();
        ZonedDateTime date;
        if (args.elements.isEmpty()) {
            date = ZonedDateTime.now();
        } else {
            long arg = ((RuntimeScalar) args.elements.getFirst()).getInt();
            date = Instant.ofEpochSecond(arg).atZone(ZoneId.systemDefault());
        }
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

    /**
     * Converts a given epoch time to UTC time and returns a formatted list of time components.
     *
     * @param args a RuntimeList containing the epoch time as the first element.
     * @param ctx  the context type, which determines the format of the result.
     * @return a RuntimeList containing formatted UTC time components.
     */
    public static RuntimeList gmtime(RuntimeList args, int ctx) {
        RuntimeList res = new RuntimeList();
        ZonedDateTime date;
        if (args.elements.isEmpty()) {
            date = ZonedDateTime.now(ZoneOffset.UTC);
        } else {
            long arg = ((RuntimeScalar) args.elements.getFirst()).getInt();
            date = Instant.ofEpochSecond(arg).atZone(ZoneId.of("UTC"));
        }
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
}

package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.nativ.NativeUtils;
import org.perlonjava.runtime.nativ.PosixLibrary;
import org.perlonjava.runtime.operators.Time;
import org.perlonjava.runtime.runtimetypes.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class POSIX extends PerlModuleBase {

    private static final Pattern FORMAT_PATTERN = Pattern.compile("%([EO])?([%aAbBcCdDeFgGhHIjmMnpPrRsStTuUVwWxXyYzZ])");

    public POSIX() {
        super("POSIX", false);
    }

    public static void initialize() {
        POSIX module = new POSIX();
        try {
            module.registerMethod("_strftime", "strftime", null);
            module.registerMethod("_mktime", "mktime", null);
            module.registerMethod("_time", "posix_time", null);
            module.registerMethod("_sleep", "posix_sleep", null);
            module.registerMethod("_alarm", "posix_alarm", null);
            module.registerMethod("_getpid", "getpid", null);
            module.registerMethod("_getppid", "getppid", null);
            module.registerMethod("_getuid", "getuid", null);
            module.registerMethod("_geteuid", "geteuid", null);
            module.registerMethod("_getgid", "getgid", null);
            module.registerMethod("_getegid", "getegid", null);
            module.registerMethod("_getcwd", "getcwd", null);
            module.registerMethod("_strerror", "strerror", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing POSIX method: " + e.getMessage());
        }
    }

    /**
     * POSIX strftime - convert date and time to string.
     * Arguments: fmt, sec, min, hour, mday, mon, year, [wday, yday, isdst]
     */
    public static RuntimeList strftime(RuntimeArray args, int ctx) {
        if (args.size() < 7) {
            throw new IllegalArgumentException("strftime requires at least 7 arguments");
        }

        String format = args.get(0).toString();
        int sec = args.get(1).getInt();
        int min = args.get(2).getInt();
        int hour = args.get(3).getInt();
        int mday = args.get(4).getInt();
        int mon = args.get(5).getInt();      // 0-based
        int year = args.get(6).getInt();     // years since 1900

        // wday, yday, isdst are ignored as per POSIX spec - they're computed from other values
        int actualYear = year + 1900;
        int actualMon = mon + 1;  // Convert to 1-based for Java

        // Create LocalDateTime
        LocalDateTime dateTime;
        try {
            dateTime = LocalDateTime.of(actualYear, actualMon, mday, hour, min, sec);
        } catch (Exception e) {
            return new RuntimeScalar("").getList();
        }

        // Get timezone info for %z and %Z
        ZonedDateTime zonedDateTime = dateTime.atZone(ZoneId.systemDefault());

        String result = formatStrftime(format, zonedDateTime);
        return new RuntimeScalar(result).getList();
    }

    /**
     * Format a ZonedDateTime using strftime format codes.
     */
    public static String formatStrftime(String format, ZonedDateTime dt) {
        StringBuffer result = new StringBuffer();
        Matcher m = FORMAT_PATTERN.matcher(format);

        while (m.find()) {
            String modifier = m.group(1);  // E or O modifier (ignored for now)
            String code = m.group(2);
            String replacement = formatCode(code, dt);
            m.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(result);

        return result.toString();
    }

    /**
     * Calculate POSIX-style week number where days before the first occurrence
     * of the specified day are week 0.
     * %U: Sunday as first day of week
     * %W: Monday as first day of week
     */
    private static int calculateWeekNumber(ZonedDateTime dt, DayOfWeek firstDayOfWeek) {
        int dayOfYear = dt.getDayOfYear();  // 1-based
        
        // Find the day of week of Jan 1
        LocalDate jan1 = LocalDate.of(dt.getYear(), 1, 1);
        DayOfWeek jan1Dow = jan1.getDayOfWeek();
        
        // Calculate day of year (1-based) of the first occurrence of firstDayOfWeek
        // Java DayOfWeek: MONDAY=1, TUESDAY=2, ... SUNDAY=7
        int jan1DowValue = jan1Dow.getValue();  // 1-7
        int firstDowValue = firstDayOfWeek.getValue();  // 1-7
        
        int daysUntilFirstWeekStart = (firstDowValue - jan1DowValue + 7) % 7;
        // If Jan 1 is the firstDayOfWeek, daysUntilFirstWeekStart is 0
        int firstWeekStartDoy = 1 + daysUntilFirstWeekStart;  // day of year when week 1 starts
        
        if (dayOfYear < firstWeekStartDoy) {
            return 0;  // Before the first occurrence of firstDayOfWeek
        }
        
        // Week 1 starts on firstWeekStartDoy
        return (dayOfYear - firstWeekStartDoy) / 7 + 1;
    }

    private static String formatCode(String code, ZonedDateTime dt) {
        Locale locale = Locale.getDefault();
        
        switch (code) {
            case "%": return "%";
            case "a": return dt.getDayOfWeek().getDisplayName(TextStyle.SHORT, locale);
            case "A": return dt.getDayOfWeek().getDisplayName(TextStyle.FULL, locale);
            case "b":
            case "h": return dt.getMonth().getDisplayName(TextStyle.SHORT, locale);
            case "B": return dt.getMonth().getDisplayName(TextStyle.FULL, locale);
            case "c": return dt.format(DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", locale));
            case "C": return String.format("%02d", dt.getYear() / 100);
            case "d": return String.format("%02d", dt.getDayOfMonth());
            case "D": return dt.format(DateTimeFormatter.ofPattern("MM/dd/yy", locale));
            case "e": return String.format("%2d", dt.getDayOfMonth());
            case "F": return dt.format(DateTimeFormatter.ISO_LOCAL_DATE);
            case "g": {
                // ISO week-based year, last 2 digits
                int weekYear = dt.get(WeekFields.ISO.weekBasedYear());
                return String.format("%02d", weekYear % 100);
            }
            case "G": {
                // ISO week-based year, 4 digits
                return String.format("%04d", dt.get(WeekFields.ISO.weekBasedYear()));
            }
            case "H": return String.format("%02d", dt.getHour());
            case "I": {
                int hour12 = dt.getHour() % 12;
                return String.format("%02d", hour12 == 0 ? 12 : hour12);
            }
            case "j": return String.format("%03d", dt.getDayOfYear());
            case "m": return String.format("%02d", dt.getMonthValue());
            case "M": return String.format("%02d", dt.getMinute());
            case "n": return "\n";
            case "p": return dt.getHour() < 12 ? "AM" : "PM";
            case "P": return dt.getHour() < 12 ? "am" : "pm";
            case "r": {
                int hour12 = dt.getHour() % 12;
                hour12 = hour12 == 0 ? 12 : hour12;
                return String.format("%02d:%02d:%02d %s", hour12, dt.getMinute(), dt.getSecond(),
                        dt.getHour() < 12 ? "AM" : "PM");
            }
            case "R": return String.format("%02d:%02d", dt.getHour(), dt.getMinute());
            case "s": return String.valueOf(dt.toEpochSecond());
            case "S": return String.format("%02d", dt.getSecond());
            case "t": return "\t";
            case "T": return String.format("%02d:%02d:%02d", dt.getHour(), dt.getMinute(), dt.getSecond());
            case "u": {
                // Monday=1 .. Sunday=7
                int dow = dt.getDayOfWeek().getValue();
                return String.valueOf(dow);
            }
            case "U": {
                // Week number (Sunday as first day), 00-53
                // Days before first Sunday of year are week 0
                int weekNum = calculateWeekNumber(dt, DayOfWeek.SUNDAY);
                return String.format("%02d", weekNum);
            }
            case "V": {
                // ISO week number
                int weekNum = dt.get(WeekFields.ISO.weekOfWeekBasedYear());
                return String.format("%02d", weekNum);
            }
            case "w": {
                // Sunday=0 .. Saturday=6
                int dow = dt.getDayOfWeek().getValue() % 7;
                return String.valueOf(dow);
            }
            case "W": {
                // Week number (Monday as first day), 00-53
                // Days before first Monday of year are week 0
                int weekNum = calculateWeekNumber(dt, DayOfWeek.MONDAY);
                return String.format("%02d", weekNum);
            }
            case "x": return dt.format(DateTimeFormatter.ofPattern("MM/dd/yy", locale));
            case "X": return dt.format(DateTimeFormatter.ofPattern("HH:mm:ss", locale));
            case "y": return String.format("%02d", dt.getYear() % 100);
            case "Y": return String.format("%04d", dt.getYear());
            case "z": {
                ZoneOffset offset = dt.getOffset();
                int totalSeconds = offset.getTotalSeconds();
                int hours = totalSeconds / 3600;
                int minutes = Math.abs((totalSeconds % 3600) / 60);
                return String.format("%+03d%02d", hours, minutes);
            }
            case "Z": {
                return dt.getZone().getDisplayName(TextStyle.SHORT, locale);
            }
            default: return "%" + code;
        }
    }

    /**
     * POSIX mktime - convert time structure to epoch.
     * Arguments: sec, min, hour, mday, mon, year, [wday, yday, isdst]
     */
    public static RuntimeList mktime(RuntimeArray args, int ctx) {
        if (args.size() < 6) {
            return new RuntimeScalar(-1).getList();
        }

        int sec = args.get(0).getInt();
        int min = args.get(1).getInt();
        int hour = args.get(2).getInt();
        int mday = args.get(3).getInt();
        int mon = args.get(4).getInt();
        int year = args.get(5).getInt();

        int actualYear = year + 1900;
        int actualMon = mon + 1;

        try {
            LocalDateTime ldt = LocalDateTime.of(actualYear, actualMon, mday, hour, min, sec);
            ZonedDateTime zdt = ldt.atZone(ZoneId.systemDefault());
            return new RuntimeScalar(zdt.toEpochSecond()).getList();
        } catch (Exception e) {
            return new RuntimeScalar(-1).getList();
        }
    }

    public static RuntimeList posix_time(RuntimeArray args, int ctx) {
        return Time.time().getList();
    }

    public static RuntimeList posix_sleep(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeScalar(0).getList();
        }
        return Time.sleep(args.get(0)).getList();
    }

    public static RuntimeList posix_alarm(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return Time.alarm(ctx).getList();
        }
        return Time.alarm(ctx, args.get(0)).getList();
    }

    public static RuntimeList getpid(RuntimeArray args, int ctx) {
        return new RuntimeScalar(ProcessHandle.current().pid()).getList();
    }

    public static RuntimeList getppid(RuntimeArray args, int ctx) {
        return NativeUtils.getppid(ctx).getList();
    }

    public static RuntimeList getuid(RuntimeArray args, int ctx) {
        return NativeUtils.getuid(ctx).getList();
    }

    public static RuntimeList geteuid(RuntimeArray args, int ctx) {
        return NativeUtils.geteuid(ctx).getList();
    }

    public static RuntimeList getgid(RuntimeArray args, int ctx) {
        return NativeUtils.getgid(ctx).getList();
    }

    public static RuntimeList getegid(RuntimeArray args, int ctx) {
        return NativeUtils.getegid(ctx).getList();
    }

    public static RuntimeList getcwd(RuntimeArray args, int ctx) {
        return new RuntimeScalar(System.getProperty("user.dir")).getList();
    }

    public static RuntimeList strerror(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeScalar("").getList();
        }
        int errno = args.get(0).getInt();
        // Return a basic error message - could be enhanced with actual errno mapping
        String msg = "Error " + errno;
        try {
            msg = org.perlonjava.runtime.nativ.PosixLibrary.INSTANCE.strerror(errno);
        } catch (Exception e) {
            // Fall back to generic message
        }
        return new RuntimeScalar(msg).getList();
    }
}

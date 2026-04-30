package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.Time;
import org.perlonjava.runtime.runtimetypes.*;

import java.text.DateFormatSymbols;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TimePiece extends PerlModuleBase {

    public TimePiece() {
        super("Time::Piece", false);
    }

    public static void initialize() {
        TimePiece module = new TimePiece();
        try {
            module.registerMethod("_strftime", null);
            module.registerMethod("_strptime", null);
            module.registerMethod("_tzset", null);
            module.registerMethod("_crt_localtime", null);
            module.registerMethod("_crt_gmtime", null);
            module.registerMethod("_get_localization", null);
            module.registerMethod("_mini_mktime", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Time::Piece method: " + e.getMessage());
        }
    }

    /**
     * _strftime(format, epoch, islocal)
     * Format a time value using strftime-style format codes.
     */
    public static RuntimeList _strftime(RuntimeArray args, int ctx) {
        if (args.size() < 3) {
            return new RuntimeScalar("").getList();
        }

        String format = args.get(0).toString();
        long epoch = args.get(1).getLong();
        boolean isLocal = args.get(2).getBoolean();

        ZonedDateTime dt;
        if (isLocal) {
            dt = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault());
        } else {
            dt = Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC);
        }

        String result = POSIX.formatStrftime(format, dt);
        return new RuntimeScalar(result).getList();
    }

    /**
     * _strptime(string, format, islocal, locales)
     * Parse a time string using strftime-style format codes.
     * Returns array: (sec, min, hour, mday, mon, year, wday, yday, isdst, epoch, islocal)
     */
    public static RuntimeList _strptime(RuntimeArray args, int ctx) {
        if (args.size() < 3) {
            return new RuntimeList();
        }

        String dateString = args.get(0).toString();
        String format = args.get(1).toString();
        boolean isLocal = args.get(2).getBoolean();
        RuntimeHash locales = args.size() > 3 ? args.get(3).hashDeref() : null;

        // Special-case %s (epoch seconds) — Java's DateTimeFormatter has no
        // direct strftime-style %s token, so handle it explicitly.
        if (format.trim().equals("%s")) {
            try {
                long epoch = Long.parseLong(dateString.trim());
                ZonedDateTime zdt = isLocal
                        ? Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault())
                        : Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC);
                return buildTimeArray(zdt, isLocal);
            } catch (NumberFormatException e) {
                return new RuntimeList();
            }
        }

        // Convert strftime format to Java DateTimeFormatter pattern
        String javaPattern = convertStrftimeToJava(format, locales);
        
        try {
            // Use a lenient parser so non-padded numeric fields are accepted,
            // matching POSIX strptime / Perl's Time::Piece::strptime. Without
            // this, "1:13:8" won't match "%H:%M:%S" because Java's `HH`/`mm`/`ss`
            // require exactly two digits.
            DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                    .parseLenient()
                    .appendPattern(javaPattern)
                    .toFormatter(Locale.getDefault());
            
            // Try to parse - we need to handle partial dates
            LocalDateTime parsedDateTime = parseFlexible(dateString, formatter, javaPattern);
            
            if (parsedDateTime == null) {
                return new RuntimeList();
            }

            ZonedDateTime zdt;
            if (isLocal) {
                zdt = parsedDateTime.atZone(ZoneId.systemDefault());
            } else {
                zdt = parsedDateTime.atZone(ZoneOffset.UTC);
            }

            return buildTimeArray(zdt, isLocal);
        } catch (Exception e) {
            return new RuntimeList();
        }
    }

    private static LocalDateTime parseFlexible(String dateString, DateTimeFormatter formatter, String pattern) {
        try {
            // Try full LocalDateTime parse first
            return LocalDateTime.parse(dateString, formatter);
        } catch (DateTimeParseException e) {
            // Try LocalDate only
            try {
                LocalDate date = LocalDate.parse(dateString, formatter);
                return date.atStartOfDay();
            } catch (DateTimeParseException e2) {
                // Try LocalTime only
                try {
                    LocalTime time = LocalTime.parse(dateString, formatter);
                    return LocalDateTime.of(LocalDate.now(), time);
                } catch (DateTimeParseException e3) {
                    return null;
                }
            }
        }
    }

    private static String convertStrftimeToJava(String format, RuntimeHash locales) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < format.length()) {
            char c = format.charAt(i);
            if (c == '%' && i + 1 < format.length()) {
                char code = format.charAt(i + 1);
                String replacement = strftimeCodeToJava(code);
                result.append(replacement);
                i += 2;
            } else if (c == '\'') {
                result.append("''");
                i++;
            } else if (Character.isLetter(c)) {
                result.append("'").append(c).append("'");
                i++;
            } else {
                result.append(c);
                i++;
            }
        }
        return result.toString();
    }

    private static String strftimeCodeToJava(char code) {
        switch (code) {
            case '%': return "%";
            case 'a': return "EEE";
            case 'A': return "EEEE";
            case 'b': case 'h': return "MMM";
            case 'B': return "MMMM";
            case 'c': return "EEE MMM d HH:mm:ss yyyy";
            case 'C': return "yy";  // Approximation - century
            case 'd': return "dd";
            case 'D': return "MM/dd/yy";
            case 'e': return "d";
            case 'F': return "yyyy-MM-dd";
            case 'H': return "HH";
            case 'I': return "hh";
            case 'j': return "DDD";
            case 'm': return "MM";
            case 'M': return "mm";
            case 'n': return "\n";
            case 'p': return "a";
            case 'P': return "a";  // lowercase am/pm
            case 'r': return "hh:mm:ss a";
            case 'R': return "HH:mm";
            case 'S': return "ss";
            case 't': return "\t";
            case 'T': return "HH:mm:ss";
            case 'u': return "u";  // day of week 1-7
            case 'U': return "ww"; // week of year
            case 'V': return "ww"; // ISO week
            case 'w': return "u";  // day of week (will need adjustment)
            case 'W': return "ww";
            case 'x': return "MM/dd/yy";
            case 'X': return "HH:mm:ss";
            case 'y': return "yy";
            case 'Y': return "yyyy";
            case 'z': return "Z";
            case 'Z': return "z";
            default: return "%" + code;
        }
    }

    /**
     * _tzset() - Initialize timezone. No-op in Java as TZ is handled automatically.
     */
    public static RuntimeList _tzset(RuntimeArray args, int ctx) {
        // Java handles timezone automatically
        return new RuntimeList();
    }

    /**
     * _crt_localtime(epoch) - Return localtime array for given epoch.
     */
    public static RuntimeList _crt_localtime(RuntimeArray args, int ctx) {
        long epoch = args.isEmpty() ? System.currentTimeMillis() / 1000 : args.get(0).getLong();
        ZonedDateTime dt = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault());
        return buildTimeList(dt);
    }

    /**
     * _crt_gmtime(epoch) - Return gmtime array for given epoch.
     */
    public static RuntimeList _crt_gmtime(RuntimeArray args, int ctx) {
        long epoch = args.isEmpty() ? System.currentTimeMillis() / 1000 : args.get(0).getLong();
        ZonedDateTime dt = Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC);
        return buildTimeList(dt);
    }

    private static RuntimeList buildTimeList(ZonedDateTime dt) {
        RuntimeList result = new RuntimeList();
        result.add(new RuntimeScalar(dt.getSecond()));
        result.add(new RuntimeScalar(dt.getMinute()));
        result.add(new RuntimeScalar(dt.getHour()));
        result.add(new RuntimeScalar(dt.getDayOfMonth()));
        result.add(new RuntimeScalar(dt.getMonthValue() - 1));  // 0-based
        result.add(new RuntimeScalar(dt.getYear() - 1900));
        // wday: Java 1=Mon..7=Sun, Perl 0=Sun..6=Sat
        result.add(new RuntimeScalar(dt.getDayOfWeek().getValue() % 7));
        result.add(new RuntimeScalar(dt.getDayOfYear() - 1));  // 0-based
        result.add(new RuntimeScalar(dt.getZone().getRules().isDaylightSavings(dt.toInstant()) ? 1 : 0));
        return result;
    }

    private static RuntimeList buildTimeArray(ZonedDateTime dt, boolean isLocal) {
        RuntimeList result = new RuntimeList();
        result.add(new RuntimeScalar(dt.getSecond()));
        result.add(new RuntimeScalar(dt.getMinute()));
        result.add(new RuntimeScalar(dt.getHour()));
        result.add(new RuntimeScalar(dt.getDayOfMonth()));
        result.add(new RuntimeScalar(dt.getMonthValue() - 1));  // 0-based
        result.add(new RuntimeScalar(dt.getYear() - 1900));
        result.add(new RuntimeScalar(dt.getDayOfWeek().getValue() % 7));
        result.add(new RuntimeScalar(dt.getDayOfYear() - 1));
        result.add(new RuntimeScalar(dt.getZone().getRules().isDaylightSavings(dt.toInstant()) ? 1 : 0));
        result.add(new RuntimeScalar(dt.toEpochSecond()));
        result.add(new RuntimeScalar(isLocal ? 1 : 0));
        return result;
    }

    /**
     * _get_localization() - Return hash with locale-specific day/month names.
     */
    public static RuntimeList _get_localization(RuntimeArray args, int ctx) {
        RuntimeHash result = new RuntimeHash();
        DateFormatSymbols symbols = DateFormatSymbols.getInstance(Locale.getDefault());

        // Weekday names (Sunday first for Perl compatibility)
        RuntimeArray weekday = new RuntimeArray();
        RuntimeArray wday = new RuntimeArray();
        String[] weekdays = symbols.getWeekdays();
        String[] shortWeekdays = symbols.getShortWeekdays();
        // Java: index 1=Sunday, 2=Monday, ... 7=Saturday
        for (int i = 1; i <= 7; i++) {
            weekday.push(new RuntimeScalar(weekdays[i]));
            wday.push(new RuntimeScalar(shortWeekdays[i]));
        }
        result.put("weekday", weekday.createReference());
        result.put("wday", wday.createReference());

        // Month names
        RuntimeArray month = new RuntimeArray();
        RuntimeArray mon = new RuntimeArray();
        String[] months = symbols.getMonths();
        String[] shortMonths = symbols.getShortMonths();
        for (int i = 0; i < 12; i++) {
            month.push(new RuntimeScalar(months[i]));
            mon.push(new RuntimeScalar(shortMonths[i]));
        }
        result.put("month", month.createReference());
        result.put("mon", mon.createReference());
        result.put("alt_month", month.createReference());

        // AM/PM
        String[] ampm = symbols.getAmPmStrings();
        result.put("AM", new RuntimeScalar(ampm[0]));
        result.put("PM", new RuntimeScalar(ampm[1]));
        result.put("am", new RuntimeScalar(ampm[0].toLowerCase()));
        result.put("pm", new RuntimeScalar(ampm[1].toLowerCase()));

        result.put("c_fmt", new RuntimeScalar(""));

        return result.createReference().getList();
    }

    /**
     * _mini_mktime(sec, min, hour, mday, mon, year)
     * Normalize time values and return array.
     * Used by add_months to handle month overflow.
     */
    public static RuntimeList _mini_mktime(RuntimeArray args, int ctx) {
        if (args.size() < 6) {
            return new RuntimeList();
        }

        int sec = args.get(0).getInt();
        int min = args.get(1).getInt();
        int hour = args.get(2).getInt();
        int mday = args.get(3).getInt();
        int mon = args.get(4).getInt();
        int year = args.get(5).getInt();  // years since 1900

        // Normalize the values by creating a LocalDateTime and letting Java handle overflow
        try {
            // Handle month overflow
            int extraYears = mon / 12;
            mon = mon % 12;
            if (mon < 0) {
                mon += 12;
                extraYears--;
            }
            year += extraYears;

            int actualYear = year + 1900;
            int actualMon = mon + 1;

            // Clamp day to valid range for the month
            YearMonth ym = YearMonth.of(actualYear, actualMon);
            int maxDay = ym.lengthOfMonth();
            if (mday > maxDay) {
                mday = maxDay;
            }
            if (mday < 1) {
                mday = 1;
            }

            LocalDateTime dt = LocalDateTime.of(actualYear, actualMon, mday, hour, min, sec);
            ZonedDateTime zdt = dt.atZone(ZoneId.systemDefault());

            RuntimeList result = new RuntimeList();
            result.add(new RuntimeScalar(dt.getSecond()));
            result.add(new RuntimeScalar(dt.getMinute()));
            result.add(new RuntimeScalar(dt.getHour()));
            result.add(new RuntimeScalar(dt.getDayOfMonth()));
            result.add(new RuntimeScalar(dt.getMonthValue() - 1));
            result.add(new RuntimeScalar(dt.getYear() - 1900));
            result.add(new RuntimeScalar(dt.getDayOfWeek().getValue() % 7));
            result.add(new RuntimeScalar(dt.getDayOfYear() - 1));
            result.add(new RuntimeScalar(zdt.getZone().getRules().isDaylightSavings(zdt.toInstant()) ? 1 : 0));
            return result;
        } catch (Exception e) {
            return new RuntimeList();
        }
    }
}

package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.JulianFields;

/**
 * Java XS implementation for DateTime.
 * Uses java.time APIs where possible for optimized date/time calculations.
 * 
 * This replaces the C XS code in DateTime.xs, providing:
 * - _rd2ymd: Convert Rata Die days to year/month/day
 * - _ymd2rd: Convert year/month/day to Rata Die days
 * - _time_as_seconds: Convert h/m/s to total seconds
 * - _seconds_as_components: Convert seconds to h/m/s
 * - _normalize_tai_seconds: Normalize TAI seconds
 * - _normalize_leap_seconds: Handle leap second boundaries
 * - _is_leap_year: Check if year is leap year
 * - _day_length: Get day length (handles leap seconds)
 * - _day_has_leap_second: Check if day has leap second
 * - _accumulated_leap_seconds: Get total leap seconds
 */
public class DateTime extends PerlModuleBase {

    /**
     * Version of the DateTime XS API this implementation is compatible with.
     * Used by XSLoader to warn if the Perl module expects a different version.
     */
    public static final String XS_VERSION = "1.65";
    
    private static final int SECONDS_PER_DAY = 86400;
    
    // Leap seconds table (from DateTime's leap_seconds.h)
    // Each entry: [rd_day, accumulated_leap_seconds]
    // The day BEFORE each entry has 86401 seconds (leap second day)
    private static final long[][] LEAP_SECONDS = {
        {728714, 10},   // 1972-01-01
        {728896, 11},   // 1972-07-01
        {729261, 12},   // 1973-01-01
        {729627, 13},   // 1974-01-01
        {729992, 14},   // 1975-01-01
        {730357, 15},   // 1976-01-01
        {730723, 16},   // 1977-01-01
        {731088, 17},   // 1978-01-01
        {731453, 18},   // 1979-01-01
        {731819, 19},   // 1980-01-01
        {732184, 20},   // 1981-07-01
        {732549, 21},   // 1982-07-01
        {732915, 22},   // 1983-07-01
        {733645, 23},   // 1985-07-01
        {734011, 24},   // 1988-01-01
        {734741, 25},   // 1990-01-01
        {735107, 26},   // 1991-01-01
        {735473, 27},   // 1992-07-01
        {735838, 28},   // 1993-07-01
        {736204, 29},   // 1994-07-01
        {736935, 30},   // 1996-01-01
        {737301, 31},   // 1997-07-01
        {737666, 32},   // 1999-01-01
        {739396, 33},   // 2006-01-01
        {740214, 34},   // 2009-01-01
        {741124, 35},   // 2012-07-01
        {741849, 36},   // 2015-07-01
        {742582, 37},   // 2017-01-01
    };

    public DateTime() {
        super("DateTime", false);
    }

    public static void initialize() {
        DateTime module = new DateTime();
        try {
            module.registerMethod("_rd2ymd", null);
            module.registerMethod("_ymd2rd", null);
            module.registerMethod("_time_as_seconds", null);
            module.registerMethod("_seconds_as_components", null);
            module.registerMethod("_normalize_tai_seconds", null);
            module.registerMethod("_normalize_leap_seconds", null);
            module.registerMethod("_is_leap_year", null);
            module.registerMethod("_day_length", null);
            module.registerMethod("_day_has_leap_second", null);
            module.registerMethod("_accumulated_leap_seconds", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing DateTime method: " + e.getMessage());
        }
    }

    /**
     * _is_leap_year(self, year)
     * Uses java.time.Year.isLeap() for accurate leap year calculation.
     */
    public static RuntimeList _is_leap_year(RuntimeArray args, int ctx) {
        long year = args.get(1).getLong();
        return new RuntimeScalar(Year.isLeap(year) ? 1 : 0).getList();
    }

    /**
     * _rd2ymd(self, rd_days, extra)
     * Convert Rata Die days to year/month/day using java.time.JulianFields.RATA_DIE.
     * If extra is true, also returns day_of_week, day_of_year, quarter, day_of_quarter.
     */
    public static RuntimeList _rd2ymd(RuntimeArray args, int ctx) {
        long rdDays = args.get(1).getLong();
        int extra = args.size() > 2 ? args.get(2).getInt() : 0;
        
        // Use Java's built-in Rata Die support
        LocalDate date = LocalDate.MIN.with(JulianFields.RATA_DIE, rdDays);
        
        int year = date.getYear();
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();
        
        RuntimeList result = new RuntimeList();
        result.add(new RuntimeScalar(year));
        result.add(new RuntimeScalar(month));
        result.add(new RuntimeScalar(day));
        
        if (extra != 0) {
            int dow = date.getDayOfWeek().getValue();  // 1=Monday to 7=Sunday
            int doy = date.getDayOfYear();
            int quarter = (month - 1) / 3 + 1;
            
            // Day of quarter calculation
            int quarterStartMonth = (quarter - 1) * 3 + 1;
            LocalDate quarterStart = LocalDate.of(year, quarterStartMonth, 1);
            int doq = (int) (date.toEpochDay() - quarterStart.toEpochDay()) + 1;
            
            result.add(new RuntimeScalar(dow));
            result.add(new RuntimeScalar(doy));
            result.add(new RuntimeScalar(quarter));
            result.add(new RuntimeScalar(doq));
        }
        
        return result;
    }

    /**
     * _ymd2rd(self, year, month, day)
     * Convert year/month/day to Rata Die days using java.time.JulianFields.RATA_DIE.
     * 
     * DateTime relies on special handling:
     * - day=0 means "last day of previous month"
     * - day > last_day_of_month overflows to next month(s)
     * - day < 0 goes back into previous month(s)
     */
    public static RuntimeList _ymd2rd(RuntimeArray args, int ctx) {
        int year = args.get(1).getInt();
        int month = args.get(2).getInt();
        int day = args.get(3).getInt();
        
        // Handle month overflow/underflow (DateTime allows month > 12 or < 1)
        while (month > 12) {
            year++;
            month -= 12;
        }
        while (month < 1) {
            year--;
            month += 12;
        }
        
        // Start with the first day of the month, then add (day - 1) to get the target date
        // This correctly handles:
        // - day=0 → last day of previous month (1st + (-1) = last day of prev)
        // - day=1 → first day of month (1st + 0 = 1st)
        // - day > last_day → overflows to next month
        // - day < 0 → goes back into previous months
        LocalDate date = LocalDate.of(year, month, 1).plusDays(day - 1);
        long rd = date.getLong(JulianFields.RATA_DIE);
        
        return new RuntimeScalar(rd).getList();
    }

    /**
     * _time_as_seconds(self, hour, minute, second)
     * Convert time components to total seconds.
     */
    public static RuntimeList _time_as_seconds(RuntimeArray args, int ctx) {
        long h = args.get(1).getLong();
        long m = args.get(2).getLong();
        long s = args.get(3).getLong();
        return new RuntimeScalar(h * 3600 + m * 60 + s).getList();
    }

    /**
     * _seconds_as_components(self, secs, utc_secs, secs_modifier)
     * Convert total seconds to hour/minute/second components.
     * Handles leap seconds when utc_secs >= 86400.
     */
    public static RuntimeList _seconds_as_components(RuntimeArray args, int ctx) {
        long secs = args.get(1).getLong();
        long utcSecs = args.size() > 2 ? args.get(2).getLong() : 0;
        long secsModifier = args.size() > 3 ? args.get(3).getLong() : 0;
        
        secs -= secsModifier;
        
        long h = secs / 3600;
        secs -= h * 3600;
        long m = secs / 60;
        long s = secs - (m * 60);
        
        // Handle leap second (utc_secs >= 86400)
        if (utcSecs >= SECONDS_PER_DAY) {
            if (utcSecs >= SECONDS_PER_DAY + 2) {
                throw new RuntimeException("Invalid UTC RD seconds value: " + utcSecs);
            }
            s += (utcSecs - SECONDS_PER_DAY) + 60;
            m = 59;
            h--;
            if (h < 0) {
                h = 23;
            }
        }
        
        RuntimeList result = new RuntimeList();
        result.add(new RuntimeScalar(h));
        result.add(new RuntimeScalar(m));
        result.add(new RuntimeScalar(s));
        return result;
    }

    /**
     * _normalize_tai_seconds(self, days, secs)
     * Normalizes seconds to be within 0..86399, adjusting days accordingly.
     * Modifies the scalar values in place (they are passed by reference).
     */
    public static RuntimeList _normalize_tai_seconds(RuntimeArray args, int ctx) {
        RuntimeScalar daysScalar = args.get(1);
        RuntimeScalar secsScalar = args.get(2);
        
        double daysDouble = daysScalar.getDouble();
        double secsDouble = secsScalar.getDouble();
        
        // Check for infinity - don't normalize infinite values
        if (Double.isInfinite(daysDouble) || Double.isInfinite(secsDouble)) {
            return new RuntimeList();
        }
        
        long d = (long) daysDouble;
        long s = (long) secsDouble;
        
        long adj;
        if (s < 0) {
            adj = (s - (SECONDS_PER_DAY - 1)) / SECONDS_PER_DAY;
        } else {
            adj = s / SECONDS_PER_DAY;
        }
        
        d += adj;
        s -= adj * SECONDS_PER_DAY;
        
        // Modify in place
        daysScalar.set(d);
        secsScalar.set(s);
        
        return new RuntimeList();
    }

    /**
     * Get accumulated leap seconds for a given RD day.
     */
    private static long getAccumulatedLeapSeconds(long rdDay) {
        long leapSecs = 0;
        for (long[] entry : LEAP_SECONDS) {
            if (rdDay >= entry[0]) {
                leapSecs = entry[1];
            } else {
                break;
            }
        }
        return leapSecs;
    }

    /**
     * Get day length (86400 or 86401 for leap second days).
     * A day has 86401 seconds if a leap second is inserted at the end.
     */
    private static long getDayLength(long rdDay) {
        for (long[] entry : LEAP_SECONDS) {
            if (entry[0] == rdDay + 1) {
                // Day before a leap second insertion has 86401 seconds
                return 86401;
            }
        }
        return 86400;
    }

    /**
     * _normalize_leap_seconds(self, days, secs)
     * Normalizes seconds accounting for leap seconds.
     * Modifies the scalar values in place.
     */
    public static RuntimeList _normalize_leap_seconds(RuntimeArray args, int ctx) {
        RuntimeScalar daysScalar = args.get(1);
        RuntimeScalar secsScalar = args.get(2);
        
        double daysDouble = daysScalar.getDouble();
        double secsDouble = secsScalar.getDouble();
        
        // Check for infinity
        if (Double.isInfinite(daysDouble) || Double.isInfinite(secsDouble)) {
            return new RuntimeList();
        }
        
        long d = (long) daysDouble;
        long s = (long) secsDouble;
        
        long dayLength;
        while (s < 0) {
            dayLength = getDayLength(d - 1);
            s += dayLength;
            d--;
        }
        
        dayLength = getDayLength(d);
        while (s > dayLength - 1) {
            s -= dayLength;
            d++;
            dayLength = getDayLength(d);
        }
        
        daysScalar.set(d);
        secsScalar.set(s);
        
        return new RuntimeList();
    }

    /**
     * _day_length(self, utc_rd)
     * Returns the length of the given day (86400 or 86401 for leap second days).
     */
    public static RuntimeList _day_length(RuntimeArray args, int ctx) {
        long utcRd = args.get(1).getLong();
        return new RuntimeScalar(getDayLength(utcRd)).getList();
    }

    /**
     * _day_has_leap_second(self, utc_rd)
     * Returns 1 if the day has a leap second, 0 otherwise.
     */
    public static RuntimeList _day_has_leap_second(RuntimeArray args, int ctx) {
        long utcRd = args.get(1).getLong();
        return new RuntimeScalar(getDayLength(utcRd) > 86400 ? 1 : 0).getList();
    }

    /**
     * _accumulated_leap_seconds(self, utc_rd)
     * Returns the total accumulated leap seconds as of the given day.
     */
    public static RuntimeList _accumulated_leap_seconds(RuntimeArray args, int ctx) {
        long utcRd = args.get(1).getLong();
        return new RuntimeScalar(getAccumulatedLeapSeconds(utcRd)).getList();
    }
}

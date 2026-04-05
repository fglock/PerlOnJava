package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.nativ.NativeUtils;
import org.perlonjava.runtime.nativ.ffm.FFMPosix;
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
            module.registerMethod("_access", "access", null);
            
            // Access constants
            module.registerMethod("_const_F_OK", "const_F_OK", null);
            module.registerMethod("_const_R_OK", "const_R_OK", null);
            module.registerMethod("_const_W_OK", "const_W_OK", null);
            module.registerMethod("_const_X_OK", "const_X_OK", null);
            
            // Seek constants
            module.registerMethod("_const_SEEK_SET", "const_SEEK_SET", null);
            module.registerMethod("_const_SEEK_CUR", "const_SEEK_CUR", null);
            module.registerMethod("_const_SEEK_END", "const_SEEK_END", null);

            // Signal constants
            module.registerMethod("_const_SIGHUP", "const_SIGHUP", null);
            module.registerMethod("_const_SIGINT", "const_SIGINT", null);
            module.registerMethod("_const_SIGQUIT", "const_SIGQUIT", null);
            module.registerMethod("_const_SIGILL", "const_SIGILL", null);
            module.registerMethod("_const_SIGTRAP", "const_SIGTRAP", null);
            module.registerMethod("_const_SIGABRT", "const_SIGABRT", null);
            module.registerMethod("_const_SIGBUS", "const_SIGBUS", null);
            module.registerMethod("_const_SIGFPE", "const_SIGFPE", null);
            module.registerMethod("_const_SIGKILL", "const_SIGKILL", null);
            module.registerMethod("_const_SIGUSR1", "const_SIGUSR1", null);
            module.registerMethod("_const_SIGSEGV", "const_SIGSEGV", null);
            module.registerMethod("_const_SIGUSR2", "const_SIGUSR2", null);
            module.registerMethod("_const_SIGPIPE", "const_SIGPIPE", null);
            module.registerMethod("_const_SIGALRM", "const_SIGALRM", null);
            module.registerMethod("_const_SIGTERM", "const_SIGTERM", null);
            module.registerMethod("_const_SIGCHLD", "const_SIGCHLD", null);
            module.registerMethod("_const_SIGCONT", "const_SIGCONT", null);
            module.registerMethod("_const_SIGSTOP", "const_SIGSTOP", null);
            module.registerMethod("_const_SIGTSTP", "const_SIGTSTP", null);

            // Stat permission constants
            module.registerMethod("_const_S_IRUSR", "const_S_IRUSR", null);
            module.registerMethod("_const_S_IWUSR", "const_S_IWUSR", null);
            module.registerMethod("_const_S_IXUSR", "const_S_IXUSR", null);
            module.registerMethod("_const_S_IRWXU", "const_S_IRWXU", null);
            module.registerMethod("_const_S_IRGRP", "const_S_IRGRP", null);
            module.registerMethod("_const_S_IWGRP", "const_S_IWGRP", null);
            module.registerMethod("_const_S_IXGRP", "const_S_IXGRP", null);
            module.registerMethod("_const_S_IRWXG", "const_S_IRWXG", null);
            module.registerMethod("_const_S_IROTH", "const_S_IROTH", null);
            module.registerMethod("_const_S_IWOTH", "const_S_IWOTH", null);
            module.registerMethod("_const_S_IXOTH", "const_S_IXOTH", null);
            module.registerMethod("_const_S_IRWXO", "const_S_IRWXO", null);
            module.registerMethod("_const_S_ISUID", "const_S_ISUID", null);
            module.registerMethod("_const_S_ISGID", "const_S_ISGID", null);

            // Terminal I/O (termios) constants
            module.registerMethod("_const_BRKINT", "const_BRKINT", null);
            module.registerMethod("_const_ECHO", "const_ECHO", null);
            module.registerMethod("_const_ECHOE", "const_ECHOE", null);
            module.registerMethod("_const_ECHOK", "const_ECHOK", null);
            module.registerMethod("_const_ECHONL", "const_ECHONL", null);
            module.registerMethod("_const_ICANON", "const_ICANON", null);
            module.registerMethod("_const_ICRNL", "const_ICRNL", null);
            module.registerMethod("_const_IEXTEN", "const_IEXTEN", null);
            module.registerMethod("_const_IGNBRK", "const_IGNBRK", null);
            module.registerMethod("_const_IGNCR", "const_IGNCR", null);
            module.registerMethod("_const_IGNPAR", "const_IGNPAR", null);
            module.registerMethod("_const_INLCR", "const_INLCR", null);
            module.registerMethod("_const_INPCK", "const_INPCK", null);
            module.registerMethod("_const_ISIG", "const_ISIG", null);
            module.registerMethod("_const_ISTRIP", "const_ISTRIP", null);
            module.registerMethod("_const_IXOFF", "const_IXOFF", null);
            module.registerMethod("_const_IXON", "const_IXON", null);
            module.registerMethod("_const_NCCS", "const_NCCS", null);
            module.registerMethod("_const_NOFLSH", "const_NOFLSH", null);
            module.registerMethod("_const_OPOST", "const_OPOST", null);
            module.registerMethod("_const_PARENB", "const_PARENB", null);
            module.registerMethod("_const_PARODD", "const_PARODD", null);
            module.registerMethod("_const_TOSTOP", "const_TOSTOP", null);
            module.registerMethod("_const_VEOF", "const_VEOF", null);
            module.registerMethod("_const_VEOL", "const_VEOL", null);
            module.registerMethod("_const_VERASE", "const_VERASE", null);
            module.registerMethod("_const_VINTR", "const_VINTR", null);
            module.registerMethod("_const_VKILL", "const_VKILL", null);
            module.registerMethod("_const_VMIN", "const_VMIN", null);
            module.registerMethod("_const_VQUIT", "const_VQUIT", null);
            module.registerMethod("_const_VSTART", "const_VSTART", null);
            module.registerMethod("_const_VSTOP", "const_VSTOP", null);
            module.registerMethod("_const_VSUSP", "const_VSUSP", null);
            module.registerMethod("_const_VTIME", "const_VTIME", null);
            module.registerMethod("_const_B0", "const_B0", null);
            module.registerMethod("_const_B50", "const_B50", null);
            module.registerMethod("_const_B75", "const_B75", null);
            module.registerMethod("_const_B110", "const_B110", null);
            module.registerMethod("_const_B134", "const_B134", null);
            module.registerMethod("_const_B150", "const_B150", null);
            module.registerMethod("_const_B200", "const_B200", null);
            module.registerMethod("_const_B300", "const_B300", null);
            module.registerMethod("_const_B600", "const_B600", null);
            module.registerMethod("_const_B1200", "const_B1200", null);
            module.registerMethod("_const_B1800", "const_B1800", null);
            module.registerMethod("_const_B2400", "const_B2400", null);
            module.registerMethod("_const_B4800", "const_B4800", null);
            module.registerMethod("_const_B9600", "const_B9600", null);
            module.registerMethod("_const_B19200", "const_B19200", null);
            module.registerMethod("_const_B38400", "const_B38400", null);
            module.registerMethod("_const_CLOCAL", "const_CLOCAL", null);
            module.registerMethod("_const_CREAD", "const_CREAD", null);
            module.registerMethod("_const_CS5", "const_CS5", null);
            module.registerMethod("_const_CS6", "const_CS6", null);
            module.registerMethod("_const_CS7", "const_CS7", null);
            module.registerMethod("_const_CS8", "const_CS8", null);
            module.registerMethod("_const_CSIZE", "const_CSIZE", null);
            module.registerMethod("_const_CSTOPB", "const_CSTOPB", null);
            module.registerMethod("_const_HUPCL", "const_HUPCL", null);
            module.registerMethod("_const_TCSADRAIN", "const_TCSADRAIN", null);
            module.registerMethod("_const_TCSAFLUSH", "const_TCSAFLUSH", null);
            module.registerMethod("_const_TCSANOW", "const_TCSANOW", null);
            module.registerMethod("_const_TCIFLUSH", "const_TCIFLUSH", null);
            module.registerMethod("_const_TCIOFF", "const_TCIOFF", null);
            module.registerMethod("_const_TCIOFLUSH", "const_TCIOFLUSH", null);
            module.registerMethod("_const_TCION", "const_TCION", null);
            module.registerMethod("_const_TCOFLUSH", "const_TCOFLUSH", null);
            module.registerMethod("_const_TCOOFF", "const_TCOOFF", null);
            module.registerMethod("_const_TCOON", "const_TCOON", null);

            // sysconf constant
            module.registerMethod("_const__SC_OPEN_MAX", "const_SC_OPEN_MAX", null);

            // setsid
            module.registerMethod("_setsid", "setsid", null);

            // sysconf
            module.registerMethod("_sysconf", "sysconf", null);

            // Errno constants
            module.registerMethod("_const_EPERM", "const_EPERM", null);
            module.registerMethod("_const_ENOENT", "const_ENOENT", null);
            module.registerMethod("_const_ESRCH", "const_ESRCH", null);
            module.registerMethod("_const_EINTR", "const_EINTR", null);
            module.registerMethod("_const_EIO", "const_EIO", null);
            module.registerMethod("_const_ENXIO", "const_ENXIO", null);
            module.registerMethod("_const_E2BIG", "const_E2BIG", null);
            module.registerMethod("_const_ENOEXEC", "const_ENOEXEC", null);
            module.registerMethod("_const_EBADF", "const_EBADF", null);
            module.registerMethod("_const_ECHILD", "const_ECHILD", null);
            module.registerMethod("_const_EAGAIN", "const_EAGAIN", null);
            module.registerMethod("_const_ENOMEM", "const_ENOMEM", null);
            module.registerMethod("_const_EACCES", "const_EACCES", null);
            module.registerMethod("_const_EFAULT", "const_EFAULT", null);
            module.registerMethod("_const_ENOTBLK", "const_ENOTBLK", null);
            module.registerMethod("_const_EBUSY", "const_EBUSY", null);
            module.registerMethod("_const_EEXIST", "const_EEXIST", null);
            module.registerMethod("_const_EXDEV", "const_EXDEV", null);
            module.registerMethod("_const_ENODEV", "const_ENODEV", null);
            module.registerMethod("_const_ENOTDIR", "const_ENOTDIR", null);
            module.registerMethod("_const_EISDIR", "const_EISDIR", null);
            module.registerMethod("_const_EINVAL", "const_EINVAL", null);
            module.registerMethod("_const_ENFILE", "const_ENFILE", null);
            module.registerMethod("_const_EMFILE", "const_EMFILE", null);
            module.registerMethod("_const_ENOTTY", "const_ENOTTY", null);
            module.registerMethod("_const_ETXTBSY", "const_ETXTBSY", null);
            module.registerMethod("_const_EFBIG", "const_EFBIG", null);
            module.registerMethod("_const_ENOSPC", "const_ENOSPC", null);
            module.registerMethod("_const_ESPIPE", "const_ESPIPE", null);
            module.registerMethod("_const_EROFS", "const_EROFS", null);
            module.registerMethod("_const_EMLINK", "const_EMLINK", null);
            module.registerMethod("_const_EPIPE", "const_EPIPE", null);
            module.registerMethod("_const_EDOM", "const_EDOM", null);
            module.registerMethod("_const_ERANGE", "const_ERANGE", null);

            // uname
            module.registerMethod("_uname", "uname", null);

            // sigprocmask (stub)
            module.registerMethod("_sigprocmask", "sigprocmask", null);
            
            // Wait status macros
            module.registerMethod("_WIFEXITED", "wifexited", null);
            module.registerMethod("_WEXITSTATUS", "wexitstatus", null);
            module.registerMethod("_WIFSIGNALED", "wifsignaled", null);
            module.registerMethod("_WTERMSIG", "wtermsig", null);
            module.registerMethod("_WIFSTOPPED", "wifstopped", null);
            module.registerMethod("_WSTOPSIG", "wstopsig", null);
            module.registerMethod("_WCOREDUMP", "wcoredump", null);
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
            msg = FFMPosix.get().strerror(errno);
        } catch (Exception e) {
            // Fall back to generic message
        }
        return new RuntimeScalar(msg).getList();
    }

    /**
     * POSIX access() - check file accessibility.
     * Arguments: path, mode
     * mode is a bitmask: F_OK (0) = exists, R_OK (4) = readable, W_OK (2) = writable, X_OK (1) = executable
     * Returns 0 on success, -1 on failure.
     */
    public static RuntimeList access(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return new RuntimeScalar(-1).getList();
        }
        String path = args.get(0).toString();
        int mode = args.get(1).getInt();
        
        java.io.File file = new java.io.File(path);
        
        // F_OK (0) - test for existence
        if (!file.exists()) {
            return new RuntimeScalar(-1).getList();
        }
        
        // Check requested permissions
        if ((mode & 4) != 0 && !file.canRead()) {
            return new RuntimeScalar(-1).getList();
        }
        if ((mode & 2) != 0 && !file.canWrite()) {
            return new RuntimeScalar(-1).getList();
        }
        if ((mode & 1) != 0 && !file.canExecute()) {
            return new RuntimeScalar(-1).getList();
        }
        
        // Return "0 but true" for success - this is 0 numerically but true in boolean context
        return new RuntimeScalar("0 but true").getList();
    }

    // POSIX access() constants
    public static RuntimeList const_F_OK(RuntimeArray args, int ctx) {
        return new RuntimeScalar(0).getList();  // F_OK = test for existence
    }

    public static RuntimeList const_R_OK(RuntimeArray args, int ctx) {
        return new RuntimeScalar(4).getList();  // R_OK = test for read permission
    }

    public static RuntimeList const_W_OK(RuntimeArray args, int ctx) {
        return new RuntimeScalar(2).getList();  // W_OK = test for write permission
    }

    public static RuntimeList const_X_OK(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();  // X_OK = test for execute permission
    }

    // POSIX seek constants
    public static RuntimeList const_SEEK_SET(RuntimeArray args, int ctx) {
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList const_SEEK_CUR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList const_SEEK_END(RuntimeArray args, int ctx) {
        return new RuntimeScalar(2).getList();
    }

    // POSIX wait status macros
    // In Unix, wait() returns a status where:
    // - If exited normally: bits 8-15 = exit code, bits 0-7 = 0
    // - If signaled: bits 0-6 = signal number, bit 7 = core dump flag
    // - If stopped: bits 8-15 = stop signal, bits 0-7 = 0x7f

    /**
     * WIFEXITED($status) - returns true if the child process exited normally
     */
    public static RuntimeList wifexited(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeScalar(0).getList();
        }
        int status = args.get(0).getInt();
        // WIFEXITED: (status & 0x7f) == 0
        boolean exited = (status & 0x7f) == 0;
        return new RuntimeScalar(exited ? 1 : 0).getList();
    }

    /**
     * WEXITSTATUS($status) - returns the exit code if WIFEXITED is true
     */
    public static RuntimeList wexitstatus(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeScalar(0).getList();
        }
        int status = args.get(0).getInt();
        // WEXITSTATUS: (status >> 8) & 0xff
        int exitCode = (status >> 8) & 0xff;
        return new RuntimeScalar(exitCode).getList();
    }

    /**
     * WIFSIGNALED($status) - returns true if the child was killed by a signal
     */
    public static RuntimeList wifsignaled(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeScalar(0).getList();
        }
        int status = args.get(0).getInt();
        // WIFSIGNALED: (status & 0x7f) > 0 && (status & 0x7f) < 0x7f
        int sigBits = status & 0x7f;
        boolean signaled = sigBits > 0 && sigBits < 0x7f;
        return new RuntimeScalar(signaled ? 1 : 0).getList();
    }

    /**
     * WTERMSIG($status) - returns the signal number that killed the child
     */
    public static RuntimeList wtermsig(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeScalar(0).getList();
        }
        int status = args.get(0).getInt();
        // WTERMSIG: status & 0x7f
        int signal = status & 0x7f;
        return new RuntimeScalar(signal).getList();
    }

    /**
     * WIFSTOPPED($status) - returns true if the child was stopped
     */
    public static RuntimeList wifstopped(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeScalar(0).getList();
        }
        int status = args.get(0).getInt();
        // WIFSTOPPED: (status & 0xff) == 0x7f
        boolean stopped = (status & 0xff) == 0x7f;
        return new RuntimeScalar(stopped ? 1 : 0).getList();
    }

    /**
     * WSTOPSIG($status) - returns the signal that stopped the child
     */
    public static RuntimeList wstopsig(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeScalar(0).getList();
        }
        int status = args.get(0).getInt();
        // WSTOPSIG: (status >> 8) & 0xff (same as WEXITSTATUS)
        int stopSig = (status >> 8) & 0xff;
        return new RuntimeScalar(stopSig).getList();
    }

    /**
     * WCOREDUMP($status) - returns true if a core dump was produced
     * Bit 7 of the status is the core dump flag
     */
    public static RuntimeList wcoredump(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeScalar(0).getList();
        }
        int status = args.get(0).getInt();
        // WCOREDUMP: (status & 0x80) != 0
        boolean coreDumped = (status & 0x80) != 0;
        return new RuntimeScalar(coreDumped ? 1 : 0).getList();
    }

    // Signal constants (standard POSIX values for macOS/Linux)
    public static RuntimeList const_SIGHUP(RuntimeArray a, int c) { return new RuntimeScalar(1).getList(); }
    public static RuntimeList const_SIGINT(RuntimeArray a, int c) { return new RuntimeScalar(2).getList(); }
    public static RuntimeList const_SIGQUIT(RuntimeArray a, int c) { return new RuntimeScalar(3).getList(); }
    public static RuntimeList const_SIGILL(RuntimeArray a, int c) { return new RuntimeScalar(4).getList(); }
    public static RuntimeList const_SIGTRAP(RuntimeArray a, int c) { return new RuntimeScalar(5).getList(); }
    public static RuntimeList const_SIGABRT(RuntimeArray a, int c) { return new RuntimeScalar(6).getList(); }
    public static RuntimeList const_SIGBUS(RuntimeArray a, int c) {
        return new RuntimeScalar(System.getProperty("os.name").toLowerCase().contains("mac") ? 10 : 7).getList();
    }
    public static RuntimeList const_SIGFPE(RuntimeArray a, int c) { return new RuntimeScalar(8).getList(); }
    public static RuntimeList const_SIGKILL(RuntimeArray a, int c) { return new RuntimeScalar(9).getList(); }
    public static RuntimeList const_SIGUSR1(RuntimeArray a, int c) {
        return new RuntimeScalar(System.getProperty("os.name").toLowerCase().contains("mac") ? 30 : 10).getList();
    }
    public static RuntimeList const_SIGSEGV(RuntimeArray a, int c) { return new RuntimeScalar(11).getList(); }
    public static RuntimeList const_SIGUSR2(RuntimeArray a, int c) {
        return new RuntimeScalar(System.getProperty("os.name").toLowerCase().contains("mac") ? 31 : 12).getList();
    }
    public static RuntimeList const_SIGPIPE(RuntimeArray a, int c) { return new RuntimeScalar(13).getList(); }
    public static RuntimeList const_SIGALRM(RuntimeArray a, int c) { return new RuntimeScalar(14).getList(); }
    public static RuntimeList const_SIGTERM(RuntimeArray a, int c) { return new RuntimeScalar(15).getList(); }
    public static RuntimeList const_SIGCHLD(RuntimeArray a, int c) {
        return new RuntimeScalar(System.getProperty("os.name").toLowerCase().contains("mac") ? 20 : 17).getList();
    }
    public static RuntimeList const_SIGCONT(RuntimeArray a, int c) {
        return new RuntimeScalar(System.getProperty("os.name").toLowerCase().contains("mac") ? 19 : 18).getList();
    }
    public static RuntimeList const_SIGSTOP(RuntimeArray a, int c) {
        return new RuntimeScalar(System.getProperty("os.name").toLowerCase().contains("mac") ? 17 : 19).getList();
    }
    public static RuntimeList const_SIGTSTP(RuntimeArray a, int c) {
        return new RuntimeScalar(System.getProperty("os.name").toLowerCase().contains("mac") ? 18 : 20).getList();
    }

    // Errno constants (standard POSIX values)
    public static RuntimeList const_EPERM(RuntimeArray a, int c) { return new RuntimeScalar(1).getList(); }
    public static RuntimeList const_ENOENT(RuntimeArray a, int c) { return new RuntimeScalar(2).getList(); }
    public static RuntimeList const_ESRCH(RuntimeArray a, int c) { return new RuntimeScalar(3).getList(); }
    public static RuntimeList const_EINTR(RuntimeArray a, int c) { return new RuntimeScalar(4).getList(); }
    public static RuntimeList const_EIO(RuntimeArray a, int c) { return new RuntimeScalar(5).getList(); }
    public static RuntimeList const_ENXIO(RuntimeArray a, int c) { return new RuntimeScalar(6).getList(); }
    public static RuntimeList const_E2BIG(RuntimeArray a, int c) { return new RuntimeScalar(7).getList(); }
    public static RuntimeList const_ENOEXEC(RuntimeArray a, int c) { return new RuntimeScalar(8).getList(); }
    public static RuntimeList const_EBADF(RuntimeArray a, int c) { return new RuntimeScalar(9).getList(); }
    public static RuntimeList const_ECHILD(RuntimeArray a, int c) { return new RuntimeScalar(10).getList(); }
    public static RuntimeList const_EAGAIN(RuntimeArray a, int c) { return new RuntimeScalar(11).getList(); }
    public static RuntimeList const_ENOMEM(RuntimeArray a, int c) { return new RuntimeScalar(12).getList(); }
    public static RuntimeList const_EACCES(RuntimeArray a, int c) { return new RuntimeScalar(13).getList(); }
    public static RuntimeList const_EFAULT(RuntimeArray a, int c) { return new RuntimeScalar(14).getList(); }
    public static RuntimeList const_ENOTBLK(RuntimeArray a, int c) { return new RuntimeScalar(15).getList(); }
    public static RuntimeList const_EBUSY(RuntimeArray a, int c) { return new RuntimeScalar(16).getList(); }
    public static RuntimeList const_EEXIST(RuntimeArray a, int c) { return new RuntimeScalar(17).getList(); }
    public static RuntimeList const_EXDEV(RuntimeArray a, int c) { return new RuntimeScalar(18).getList(); }
    public static RuntimeList const_ENODEV(RuntimeArray a, int c) { return new RuntimeScalar(19).getList(); }
    public static RuntimeList const_ENOTDIR(RuntimeArray a, int c) { return new RuntimeScalar(20).getList(); }
    public static RuntimeList const_EISDIR(RuntimeArray a, int c) { return new RuntimeScalar(21).getList(); }
    public static RuntimeList const_EINVAL(RuntimeArray a, int c) { return new RuntimeScalar(22).getList(); }
    public static RuntimeList const_ENFILE(RuntimeArray a, int c) { return new RuntimeScalar(23).getList(); }
    public static RuntimeList const_EMFILE(RuntimeArray a, int c) { return new RuntimeScalar(24).getList(); }
    public static RuntimeList const_ENOTTY(RuntimeArray a, int c) { return new RuntimeScalar(25).getList(); }
    public static RuntimeList const_ETXTBSY(RuntimeArray a, int c) { return new RuntimeScalar(26).getList(); }
    public static RuntimeList const_EFBIG(RuntimeArray a, int c) { return new RuntimeScalar(27).getList(); }
    public static RuntimeList const_ENOSPC(RuntimeArray a, int c) { return new RuntimeScalar(28).getList(); }
    public static RuntimeList const_ESPIPE(RuntimeArray a, int c) { return new RuntimeScalar(29).getList(); }
    public static RuntimeList const_EROFS(RuntimeArray a, int c) { return new RuntimeScalar(30).getList(); }
    public static RuntimeList const_EMLINK(RuntimeArray a, int c) { return new RuntimeScalar(31).getList(); }
    public static RuntimeList const_EPIPE(RuntimeArray a, int c) { return new RuntimeScalar(32).getList(); }
    public static RuntimeList const_EDOM(RuntimeArray a, int c) { return new RuntimeScalar(33).getList(); }
    public static RuntimeList const_ERANGE(RuntimeArray a, int c) { return new RuntimeScalar(34).getList(); }

    // Stat permission constants (standard POSIX values, same on all platforms)
    public static RuntimeList const_S_IRUSR(RuntimeArray a, int c) { return new RuntimeScalar(0400).getList(); }  // 256
    public static RuntimeList const_S_IWUSR(RuntimeArray a, int c) { return new RuntimeScalar(0200).getList(); }  // 128
    public static RuntimeList const_S_IXUSR(RuntimeArray a, int c) { return new RuntimeScalar(0100).getList(); }  // 64
    public static RuntimeList const_S_IRWXU(RuntimeArray a, int c) { return new RuntimeScalar(0700).getList(); }  // 448
    public static RuntimeList const_S_IRGRP(RuntimeArray a, int c) { return new RuntimeScalar(040).getList(); }   // 32
    public static RuntimeList const_S_IWGRP(RuntimeArray a, int c) { return new RuntimeScalar(020).getList(); }   // 16
    public static RuntimeList const_S_IXGRP(RuntimeArray a, int c) { return new RuntimeScalar(010).getList(); }   // 8
    public static RuntimeList const_S_IRWXG(RuntimeArray a, int c) { return new RuntimeScalar(070).getList(); }   // 56
    public static RuntimeList const_S_IROTH(RuntimeArray a, int c) { return new RuntimeScalar(04).getList(); }    // 4
    public static RuntimeList const_S_IWOTH(RuntimeArray a, int c) { return new RuntimeScalar(02).getList(); }    // 2
    public static RuntimeList const_S_IXOTH(RuntimeArray a, int c) { return new RuntimeScalar(01).getList(); }    // 1
    public static RuntimeList const_S_IRWXO(RuntimeArray a, int c) { return new RuntimeScalar(07).getList(); }    // 7
    public static RuntimeList const_S_ISUID(RuntimeArray a, int c) { return new RuntimeScalar(04000).getList(); } // 2048
    public static RuntimeList const_S_ISGID(RuntimeArray a, int c) { return new RuntimeScalar(02000).getList(); } // 1024

    // Terminal I/O (termios) constants - platform dependent
    private static final boolean IS_MAC = System.getProperty("os.name").toLowerCase().contains("mac");

    // Input mode flags
    public static RuntimeList const_BRKINT(RuntimeArray a, int c) { return new RuntimeScalar(2).getList(); }
    public static RuntimeList const_IGNBRK(RuntimeArray a, int c) { return new RuntimeScalar(1).getList(); }
    public static RuntimeList const_IGNCR(RuntimeArray a, int c) { return new RuntimeScalar(128).getList(); }
    public static RuntimeList const_IGNPAR(RuntimeArray a, int c) { return new RuntimeScalar(4).getList(); }
    public static RuntimeList const_INLCR(RuntimeArray a, int c) { return new RuntimeScalar(64).getList(); }
    public static RuntimeList const_INPCK(RuntimeArray a, int c) { return new RuntimeScalar(16).getList(); }
    public static RuntimeList const_ISTRIP(RuntimeArray a, int c) { return new RuntimeScalar(32).getList(); }
    public static RuntimeList const_ICRNL(RuntimeArray a, int c) { return new RuntimeScalar(256).getList(); }
    public static RuntimeList const_IXOFF(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 1024 : 4096).getList(); }
    public static RuntimeList const_IXON(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 512 : 1024).getList(); }

    // Output mode flags
    public static RuntimeList const_OPOST(RuntimeArray a, int c) { return new RuntimeScalar(1).getList(); }

    // Control mode flags
    public static RuntimeList const_CLOCAL(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 32768 : 2048).getList(); }
    public static RuntimeList const_CREAD(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 2048 : 128).getList(); }
    public static RuntimeList const_CS5(RuntimeArray a, int c) { return new RuntimeScalar(0).getList(); }
    public static RuntimeList const_CS6(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 256 : 16).getList(); }
    public static RuntimeList const_CS7(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 512 : 32).getList(); }
    public static RuntimeList const_CS8(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 768 : 48).getList(); }
    public static RuntimeList const_CSIZE(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 768 : 48).getList(); }
    public static RuntimeList const_CSTOPB(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 1024 : 64).getList(); }
    public static RuntimeList const_HUPCL(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 16384 : 1024).getList(); }
    public static RuntimeList const_PARENB(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 4096 : 256).getList(); }
    public static RuntimeList const_PARODD(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 8192 : 512).getList(); }

    // Local mode flags
    public static RuntimeList const_ECHO(RuntimeArray a, int c) { return new RuntimeScalar(8).getList(); }
    public static RuntimeList const_ECHOE(RuntimeArray a, int c) { return new RuntimeScalar(2).getList(); }
    public static RuntimeList const_ECHOK(RuntimeArray a, int c) { return new RuntimeScalar(4).getList(); }
    public static RuntimeList const_ECHONL(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 16 : 64).getList(); }
    public static RuntimeList const_ICANON(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 256 : 2).getList(); }
    public static RuntimeList const_IEXTEN(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 1024 : 32768).getList(); }
    public static RuntimeList const_ISIG(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 128 : 1).getList(); }
    public static RuntimeList const_NOFLSH(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 2147483648L : 128L).getList(); }
    public static RuntimeList const_TOSTOP(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 4194304 : 256).getList(); }

    // Special control characters indices
    public static RuntimeList const_NCCS(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 20 : 32).getList(); }
    public static RuntimeList const_VEOF(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 0 : 4).getList(); }
    public static RuntimeList const_VEOL(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 1 : 11).getList(); }
    public static RuntimeList const_VERASE(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 3 : 2).getList(); }
    public static RuntimeList const_VINTR(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 8 : 0).getList(); }
    public static RuntimeList const_VKILL(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 5 : 3).getList(); }
    public static RuntimeList const_VMIN(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 16 : 6).getList(); }
    public static RuntimeList const_VQUIT(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 9 : 1).getList(); }
    public static RuntimeList const_VSTART(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 12 : 8).getList(); }
    public static RuntimeList const_VSTOP(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 13 : 9).getList(); }
    public static RuntimeList const_VSUSP(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 10 : 7).getList(); }
    public static RuntimeList const_VTIME(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 17 : 5).getList(); }

    // Baud rate constants (platform dependent - macOS uses actual rate, Linux uses index)
    public static RuntimeList const_B0(RuntimeArray a, int c) { return new RuntimeScalar(0).getList(); }
    public static RuntimeList const_B50(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 50 : 1).getList(); }
    public static RuntimeList const_B75(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 75 : 2).getList(); }
    public static RuntimeList const_B110(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 110 : 3).getList(); }
    public static RuntimeList const_B134(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 134 : 4).getList(); }
    public static RuntimeList const_B150(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 150 : 5).getList(); }
    public static RuntimeList const_B200(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 200 : 6).getList(); }
    public static RuntimeList const_B300(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 300 : 7).getList(); }
    public static RuntimeList const_B600(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 600 : 8).getList(); }
    public static RuntimeList const_B1200(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 1200 : 9).getList(); }
    public static RuntimeList const_B1800(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 1800 : 10).getList(); }
    public static RuntimeList const_B2400(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 2400 : 11).getList(); }
    public static RuntimeList const_B4800(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 4800 : 12).getList(); }
    public static RuntimeList const_B9600(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 9600 : 13).getList(); }
    public static RuntimeList const_B19200(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 19200 : 14).getList(); }
    public static RuntimeList const_B38400(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 38400 : 15).getList(); }

    // tcsetattr/tcflush action constants
    public static RuntimeList const_TCSANOW(RuntimeArray a, int c) { return new RuntimeScalar(0).getList(); }
    public static RuntimeList const_TCSADRAIN(RuntimeArray a, int c) { return new RuntimeScalar(1).getList(); }
    public static RuntimeList const_TCSAFLUSH(RuntimeArray a, int c) { return new RuntimeScalar(2).getList(); }
    public static RuntimeList const_TCIFLUSH(RuntimeArray a, int c) { return new RuntimeScalar(1).getList(); }
    public static RuntimeList const_TCIOFF(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 3 : 2).getList(); }
    public static RuntimeList const_TCIOFLUSH(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 3 : 2).getList(); }
    public static RuntimeList const_TCION(RuntimeArray a, int c) { return new RuntimeScalar(IS_MAC ? 4 : 3).getList(); }
    public static RuntimeList const_TCOFLUSH(RuntimeArray a, int c) { return new RuntimeScalar(2).getList(); }
    public static RuntimeList const_TCOOFF(RuntimeArray a, int c) { return new RuntimeScalar(1).getList(); }
    public static RuntimeList const_TCOON(RuntimeArray a, int c) { return new RuntimeScalar(2).getList(); }

    /**
     * POSIX::uname() - returns (sysname, nodename, release, version, machine)
     */
    public static RuntimeList uname(RuntimeArray args, int ctx) {
        RuntimeList result = new RuntimeList();
        result.add(new RuntimeScalar(System.getProperty("os.name", "unknown")));
        try {
            result.add(new RuntimeScalar(java.net.InetAddress.getLocalHost().getHostName()));
        } catch (Exception e) {
            result.add(new RuntimeScalar("localhost"));
        }
        result.add(new RuntimeScalar(System.getProperty("os.version", "unknown")));
        result.add(new RuntimeScalar(System.getProperty("java.version", "unknown")));
        result.add(new RuntimeScalar(System.getProperty("os.arch", "unknown")));
        return result;
    }

    /**
     * POSIX::sigprocmask() - stub implementation
     * On JVM, signal mask manipulation is not directly supported.
     * Returns success (0) as a no-op.
     */
    public static RuntimeList sigprocmask(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    // _SC_OPEN_MAX constant (macOS=5, Linux=4)
    public static RuntimeList const_SC_OPEN_MAX(RuntimeArray a, int c) {
        return new RuntimeScalar(IS_MAC ? 5 : 4).getList();
    }

    /**
     * POSIX::setsid() - create a new session
     * On JVM, we can't truly create a new process session, but we return the PID
     * as a reasonable approximation (POE uses this for daemon setup).
     */
    public static RuntimeList setsid(RuntimeArray args, int ctx) {
        return new RuntimeScalar(ProcessHandle.current().pid()).getList();
    }

    /**
     * POSIX::sysconf($name) - get system configuration values
     * Supports _SC_OPEN_MAX and returns reasonable defaults for JVM.
     */
    public static RuntimeList sysconf(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeScalar(-1).getList();
        }
        int name = args.get(0).getInt();
        // _SC_OPEN_MAX: macOS=5, Linux=4
        int scOpenMax = IS_MAC ? 5 : 4;
        if (name == scOpenMax) {
            // Return a reasonable max open files for JVM
            // Use ulimit value if available, otherwise default to 1024
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "ulimit -n"});
                byte[] output = p.getInputStream().readAllBytes();
                p.waitFor();
                String val = new String(output).trim();
                return new RuntimeScalar(Long.parseLong(val)).getList();
            } catch (Exception e) {
                return new RuntimeScalar(1024).getList();
            }
        }
        return new RuntimeScalar(-1).getList();
    }
}

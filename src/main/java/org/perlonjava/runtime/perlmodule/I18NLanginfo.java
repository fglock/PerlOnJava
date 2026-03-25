package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.text.DateFormatSymbols;
import java.text.DecimalFormatSymbols;
import java.nio.charset.Charset;
import java.util.Locale;

/**
 * Java XS implementation for I18N::Langinfo.
 * Provides locale information similar to the POSIX nl_langinfo() function.
 */
public class I18NLanginfo extends PerlModuleBase {

    // Constants for langinfo() - these match the values used by the Perl module
    // Day names (Sunday = 1)
    public static final int ABDAY_1 = 1;   // Sun
    public static final int ABDAY_2 = 2;   // Mon
    public static final int ABDAY_3 = 3;   // Tue
    public static final int ABDAY_4 = 4;   // Wed
    public static final int ABDAY_5 = 5;   // Thu
    public static final int ABDAY_6 = 6;   // Fri
    public static final int ABDAY_7 = 7;   // Sat
    
    public static final int DAY_1 = 8;     // Sunday
    public static final int DAY_2 = 9;     // Monday
    public static final int DAY_3 = 10;    // Tuesday
    public static final int DAY_4 = 11;    // Wednesday
    public static final int DAY_5 = 12;    // Thursday
    public static final int DAY_6 = 13;    // Friday
    public static final int DAY_7 = 14;    // Saturday
    
    // Month names
    public static final int ABMON_1 = 15;  // Jan
    public static final int ABMON_2 = 16;  // Feb
    public static final int ABMON_3 = 17;  // Mar
    public static final int ABMON_4 = 18;  // Apr
    public static final int ABMON_5 = 19;  // May
    public static final int ABMON_6 = 20;  // Jun
    public static final int ABMON_7 = 21;  // Jul
    public static final int ABMON_8 = 22;  // Aug
    public static final int ABMON_9 = 23;  // Sep
    public static final int ABMON_10 = 24; // Oct
    public static final int ABMON_11 = 25; // Nov
    public static final int ABMON_12 = 26; // Dec
    
    public static final int MON_1 = 27;    // January
    public static final int MON_2 = 28;    // February
    public static final int MON_3 = 29;    // March
    public static final int MON_4 = 30;    // April
    public static final int MON_5 = 31;    // May
    public static final int MON_6 = 32;    // June
    public static final int MON_7 = 33;    // July
    public static final int MON_8 = 34;    // August
    public static final int MON_9 = 35;    // September
    public static final int MON_10 = 36;   // October
    public static final int MON_11 = 37;   // November
    public static final int MON_12 = 38;   // December
    
    // Time/Date formats
    public static final int D_T_FMT = 39;  // Date and time format
    public static final int D_FMT = 40;    // Date format
    public static final int T_FMT = 41;    // Time format
    public static final int T_FMT_AMPM = 42; // 12-hour time format
    public static final int AM_STR = 43;   // AM string
    public static final int PM_STR = 44;   // PM string
    
    // Numeric
    public static final int RADIXCHAR = 45; // Decimal point character
    public static final int THOUSEP = 46;   // Thousands separator
    
    // Yes/No
    public static final int YESEXPR = 47;  // Regex for yes
    public static final int NOEXPR = 48;   // Regex for no
    public static final int YESSTR = 49;   // Yes string
    public static final int NOSTR = 50;    // No string
    
    // Character set
    public static final int CODESET = 51;  // Character encoding
    
    // Currency
    public static final int CRNCYSTR = 52; // Currency symbol
    
    // Era (for locales with era-based dating)
    public static final int ERA = 53;
    public static final int ERA_D_FMT = 54;
    public static final int ERA_D_T_FMT = 55;
    public static final int ERA_T_FMT = 56;
    
    // Alternate digits
    public static final int ALT_DIGITS = 57;

    public I18NLanginfo() {
        super("I18N::Langinfo", false);
    }

    public static void initialize() {
        I18NLanginfo module = new I18NLanginfo();
        try {
            // Main function
            module.registerMethod("langinfo", null);
            
            // Day name constants
            module.registerMethod("ABDAY_1", "const_ABDAY_1", "");
            module.registerMethod("ABDAY_2", "const_ABDAY_2", "");
            module.registerMethod("ABDAY_3", "const_ABDAY_3", "");
            module.registerMethod("ABDAY_4", "const_ABDAY_4", "");
            module.registerMethod("ABDAY_5", "const_ABDAY_5", "");
            module.registerMethod("ABDAY_6", "const_ABDAY_6", "");
            module.registerMethod("ABDAY_7", "const_ABDAY_7", "");
            module.registerMethod("DAY_1", "const_DAY_1", "");
            module.registerMethod("DAY_2", "const_DAY_2", "");
            module.registerMethod("DAY_3", "const_DAY_3", "");
            module.registerMethod("DAY_4", "const_DAY_4", "");
            module.registerMethod("DAY_5", "const_DAY_5", "");
            module.registerMethod("DAY_6", "const_DAY_6", "");
            module.registerMethod("DAY_7", "const_DAY_7", "");
            
            // Month name constants
            module.registerMethod("ABMON_1", "const_ABMON_1", "");
            module.registerMethod("ABMON_2", "const_ABMON_2", "");
            module.registerMethod("ABMON_3", "const_ABMON_3", "");
            module.registerMethod("ABMON_4", "const_ABMON_4", "");
            module.registerMethod("ABMON_5", "const_ABMON_5", "");
            module.registerMethod("ABMON_6", "const_ABMON_6", "");
            module.registerMethod("ABMON_7", "const_ABMON_7", "");
            module.registerMethod("ABMON_8", "const_ABMON_8", "");
            module.registerMethod("ABMON_9", "const_ABMON_9", "");
            module.registerMethod("ABMON_10", "const_ABMON_10", "");
            module.registerMethod("ABMON_11", "const_ABMON_11", "");
            module.registerMethod("ABMON_12", "const_ABMON_12", "");
            module.registerMethod("MON_1", "const_MON_1", "");
            module.registerMethod("MON_2", "const_MON_2", "");
            module.registerMethod("MON_3", "const_MON_3", "");
            module.registerMethod("MON_4", "const_MON_4", "");
            module.registerMethod("MON_5", "const_MON_5", "");
            module.registerMethod("MON_6", "const_MON_6", "");
            module.registerMethod("MON_7", "const_MON_7", "");
            module.registerMethod("MON_8", "const_MON_8", "");
            module.registerMethod("MON_9", "const_MON_9", "");
            module.registerMethod("MON_10", "const_MON_10", "");
            module.registerMethod("MON_11", "const_MON_11", "");
            module.registerMethod("MON_12", "const_MON_12", "");
            
            // Time/Date format constants
            module.registerMethod("D_T_FMT", "const_D_T_FMT", "");
            module.registerMethod("D_FMT", "const_D_FMT", "");
            module.registerMethod("T_FMT", "const_T_FMT", "");
            module.registerMethod("T_FMT_AMPM", "const_T_FMT_AMPM", "");
            module.registerMethod("AM_STR", "const_AM_STR", "");
            module.registerMethod("PM_STR", "const_PM_STR", "");
            
            // Numeric constants
            module.registerMethod("RADIXCHAR", "const_RADIXCHAR", "");
            module.registerMethod("THOUSEP", "const_THOUSEP", "");
            
            // Yes/No constants
            module.registerMethod("YESEXPR", "const_YESEXPR", "");
            module.registerMethod("NOEXPR", "const_NOEXPR", "");
            module.registerMethod("YESSTR", "const_YESSTR", "");
            module.registerMethod("NOSTR", "const_NOSTR", "");
            
            // Other constants
            module.registerMethod("CODESET", "const_CODESET", "");
            module.registerMethod("CRNCYSTR", "const_CRNCYSTR", "");
            module.registerMethod("ERA", "const_ERA", "");
            module.registerMethod("ERA_D_FMT", "const_ERA_D_FMT", "");
            module.registerMethod("ERA_D_T_FMT", "const_ERA_D_T_FMT", "");
            module.registerMethod("ERA_T_FMT", "const_ERA_T_FMT", "");
            module.registerMethod("ALT_DIGITS", "const_ALT_DIGITS", "");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing I18N::Langinfo method: " + e.getMessage());
        }
    }

    /**
     * langinfo(item)
     * Returns locale information for the specified item.
     */
    public static RuntimeList langinfo(RuntimeArray args, int ctx) {
        int item;
        if (args.isEmpty()) {
            // Use $_ if no argument provided
            item = org.perlonjava.runtime.runtimetypes.GlobalVariable
                    .getGlobalVariable("main::_").getInt();
        } else {
            item = args.get(0).getInt();
        }
        
        Locale locale = Locale.getDefault();
        DateFormatSymbols dateSymbols = DateFormatSymbols.getInstance(locale);
        DecimalFormatSymbols decimalSymbols = DecimalFormatSymbols.getInstance(locale);
        
        String result;
        
        // Abbreviated day names (Sunday = index 1 in Java's array, but we use 0-based)
        if (item >= ABDAY_1 && item <= ABDAY_7) {
            String[] shortWeekdays = dateSymbols.getShortWeekdays();
            // Java: index 1=Sunday, 2=Monday, etc.
            result = shortWeekdays[item - ABDAY_1 + 1];
        }
        // Full day names
        else if (item >= DAY_1 && item <= DAY_7) {
            String[] weekdays = dateSymbols.getWeekdays();
            result = weekdays[item - DAY_1 + 1];
        }
        // Abbreviated month names
        else if (item >= ABMON_1 && item <= ABMON_12) {
            String[] shortMonths = dateSymbols.getShortMonths();
            result = shortMonths[item - ABMON_1];
        }
        // Full month names
        else if (item >= MON_1 && item <= MON_12) {
            String[] months = dateSymbols.getMonths();
            result = months[item - MON_1];
        }
        // Date/Time formats
        else if (item == D_T_FMT) {
            result = "%c";  // Standard strftime format
        }
        else if (item == D_FMT) {
            result = "%x";
        }
        else if (item == T_FMT) {
            result = "%X";
        }
        else if (item == T_FMT_AMPM) {
            result = "%r";
        }
        else if (item == AM_STR) {
            String[] ampm = dateSymbols.getAmPmStrings();
            result = ampm.length > 0 ? ampm[0] : "AM";
        }
        else if (item == PM_STR) {
            String[] ampm = dateSymbols.getAmPmStrings();
            result = ampm.length > 1 ? ampm[1] : "PM";
        }
        // Numeric
        else if (item == RADIXCHAR) {
            result = String.valueOf(decimalSymbols.getDecimalSeparator());
        }
        else if (item == THOUSEP) {
            result = String.valueOf(decimalSymbols.getGroupingSeparator());
        }
        // Yes/No
        else if (item == YESEXPR) {
            result = "^[yY]";
        }
        else if (item == NOEXPR) {
            result = "^[nN]";
        }
        else if (item == YESSTR) {
            result = "yes";
        }
        else if (item == NOSTR) {
            result = "no";
        }
        // Character set
        else if (item == CODESET) {
            result = Charset.defaultCharset().name();
        }
        // Currency
        else if (item == CRNCYSTR) {
            String symbol = decimalSymbols.getCurrencySymbol();
            // Prefix with '-' to indicate it precedes the value
            result = "-" + symbol;
        }
        // Era (not commonly used in Western locales)
        else if (item == ERA || item == ERA_D_FMT || item == ERA_D_T_FMT || item == ERA_T_FMT) {
            result = "";
        }
        // Alternate digits
        else if (item == ALT_DIGITS) {
            result = "";
        }
        else {
            result = "";
        }
        
        return new RuntimeScalar(result).getList();
    }

    // Constant methods
    public static RuntimeList const_ABDAY_1(RuntimeArray args, int ctx) { return new RuntimeScalar(ABDAY_1).getList(); }
    public static RuntimeList const_ABDAY_2(RuntimeArray args, int ctx) { return new RuntimeScalar(ABDAY_2).getList(); }
    public static RuntimeList const_ABDAY_3(RuntimeArray args, int ctx) { return new RuntimeScalar(ABDAY_3).getList(); }
    public static RuntimeList const_ABDAY_4(RuntimeArray args, int ctx) { return new RuntimeScalar(ABDAY_4).getList(); }
    public static RuntimeList const_ABDAY_5(RuntimeArray args, int ctx) { return new RuntimeScalar(ABDAY_5).getList(); }
    public static RuntimeList const_ABDAY_6(RuntimeArray args, int ctx) { return new RuntimeScalar(ABDAY_6).getList(); }
    public static RuntimeList const_ABDAY_7(RuntimeArray args, int ctx) { return new RuntimeScalar(ABDAY_7).getList(); }
    
    public static RuntimeList const_DAY_1(RuntimeArray args, int ctx) { return new RuntimeScalar(DAY_1).getList(); }
    public static RuntimeList const_DAY_2(RuntimeArray args, int ctx) { return new RuntimeScalar(DAY_2).getList(); }
    public static RuntimeList const_DAY_3(RuntimeArray args, int ctx) { return new RuntimeScalar(DAY_3).getList(); }
    public static RuntimeList const_DAY_4(RuntimeArray args, int ctx) { return new RuntimeScalar(DAY_4).getList(); }
    public static RuntimeList const_DAY_5(RuntimeArray args, int ctx) { return new RuntimeScalar(DAY_5).getList(); }
    public static RuntimeList const_DAY_6(RuntimeArray args, int ctx) { return new RuntimeScalar(DAY_6).getList(); }
    public static RuntimeList const_DAY_7(RuntimeArray args, int ctx) { return new RuntimeScalar(DAY_7).getList(); }
    
    public static RuntimeList const_ABMON_1(RuntimeArray args, int ctx) { return new RuntimeScalar(ABMON_1).getList(); }
    public static RuntimeList const_ABMON_2(RuntimeArray args, int ctx) { return new RuntimeScalar(ABMON_2).getList(); }
    public static RuntimeList const_ABMON_3(RuntimeArray args, int ctx) { return new RuntimeScalar(ABMON_3).getList(); }
    public static RuntimeList const_ABMON_4(RuntimeArray args, int ctx) { return new RuntimeScalar(ABMON_4).getList(); }
    public static RuntimeList const_ABMON_5(RuntimeArray args, int ctx) { return new RuntimeScalar(ABMON_5).getList(); }
    public static RuntimeList const_ABMON_6(RuntimeArray args, int ctx) { return new RuntimeScalar(ABMON_6).getList(); }
    public static RuntimeList const_ABMON_7(RuntimeArray args, int ctx) { return new RuntimeScalar(ABMON_7).getList(); }
    public static RuntimeList const_ABMON_8(RuntimeArray args, int ctx) { return new RuntimeScalar(ABMON_8).getList(); }
    public static RuntimeList const_ABMON_9(RuntimeArray args, int ctx) { return new RuntimeScalar(ABMON_9).getList(); }
    public static RuntimeList const_ABMON_10(RuntimeArray args, int ctx) { return new RuntimeScalar(ABMON_10).getList(); }
    public static RuntimeList const_ABMON_11(RuntimeArray args, int ctx) { return new RuntimeScalar(ABMON_11).getList(); }
    public static RuntimeList const_ABMON_12(RuntimeArray args, int ctx) { return new RuntimeScalar(ABMON_12).getList(); }
    
    public static RuntimeList const_MON_1(RuntimeArray args, int ctx) { return new RuntimeScalar(MON_1).getList(); }
    public static RuntimeList const_MON_2(RuntimeArray args, int ctx) { return new RuntimeScalar(MON_2).getList(); }
    public static RuntimeList const_MON_3(RuntimeArray args, int ctx) { return new RuntimeScalar(MON_3).getList(); }
    public static RuntimeList const_MON_4(RuntimeArray args, int ctx) { return new RuntimeScalar(MON_4).getList(); }
    public static RuntimeList const_MON_5(RuntimeArray args, int ctx) { return new RuntimeScalar(MON_5).getList(); }
    public static RuntimeList const_MON_6(RuntimeArray args, int ctx) { return new RuntimeScalar(MON_6).getList(); }
    public static RuntimeList const_MON_7(RuntimeArray args, int ctx) { return new RuntimeScalar(MON_7).getList(); }
    public static RuntimeList const_MON_8(RuntimeArray args, int ctx) { return new RuntimeScalar(MON_8).getList(); }
    public static RuntimeList const_MON_9(RuntimeArray args, int ctx) { return new RuntimeScalar(MON_9).getList(); }
    public static RuntimeList const_MON_10(RuntimeArray args, int ctx) { return new RuntimeScalar(MON_10).getList(); }
    public static RuntimeList const_MON_11(RuntimeArray args, int ctx) { return new RuntimeScalar(MON_11).getList(); }
    public static RuntimeList const_MON_12(RuntimeArray args, int ctx) { return new RuntimeScalar(MON_12).getList(); }
    
    public static RuntimeList const_D_T_FMT(RuntimeArray args, int ctx) { return new RuntimeScalar(D_T_FMT).getList(); }
    public static RuntimeList const_D_FMT(RuntimeArray args, int ctx) { return new RuntimeScalar(D_FMT).getList(); }
    public static RuntimeList const_T_FMT(RuntimeArray args, int ctx) { return new RuntimeScalar(T_FMT).getList(); }
    public static RuntimeList const_T_FMT_AMPM(RuntimeArray args, int ctx) { return new RuntimeScalar(T_FMT_AMPM).getList(); }
    public static RuntimeList const_AM_STR(RuntimeArray args, int ctx) { return new RuntimeScalar(AM_STR).getList(); }
    public static RuntimeList const_PM_STR(RuntimeArray args, int ctx) { return new RuntimeScalar(PM_STR).getList(); }
    
    public static RuntimeList const_RADIXCHAR(RuntimeArray args, int ctx) { return new RuntimeScalar(RADIXCHAR).getList(); }
    public static RuntimeList const_THOUSEP(RuntimeArray args, int ctx) { return new RuntimeScalar(THOUSEP).getList(); }
    
    public static RuntimeList const_YESEXPR(RuntimeArray args, int ctx) { return new RuntimeScalar(YESEXPR).getList(); }
    public static RuntimeList const_NOEXPR(RuntimeArray args, int ctx) { return new RuntimeScalar(NOEXPR).getList(); }
    public static RuntimeList const_YESSTR(RuntimeArray args, int ctx) { return new RuntimeScalar(YESSTR).getList(); }
    public static RuntimeList const_NOSTR(RuntimeArray args, int ctx) { return new RuntimeScalar(NOSTR).getList(); }
    
    public static RuntimeList const_CODESET(RuntimeArray args, int ctx) { return new RuntimeScalar(CODESET).getList(); }
    public static RuntimeList const_CRNCYSTR(RuntimeArray args, int ctx) { return new RuntimeScalar(CRNCYSTR).getList(); }
    public static RuntimeList const_ERA(RuntimeArray args, int ctx) { return new RuntimeScalar(ERA).getList(); }
    public static RuntimeList const_ERA_D_FMT(RuntimeArray args, int ctx) { return new RuntimeScalar(ERA_D_FMT).getList(); }
    public static RuntimeList const_ERA_D_T_FMT(RuntimeArray args, int ctx) { return new RuntimeScalar(ERA_D_T_FMT).getList(); }
    public static RuntimeList const_ERA_T_FMT(RuntimeArray args, int ctx) { return new RuntimeScalar(ERA_T_FMT).getList(); }
    public static RuntimeList const_ALT_DIGITS(RuntimeArray args, int ctx) { return new RuntimeScalar(ALT_DIGITS).getList(); }
}

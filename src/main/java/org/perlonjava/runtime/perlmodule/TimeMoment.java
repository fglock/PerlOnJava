package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.IsoFields;
import java.time.temporal.JulianFields;
import java.time.temporal.TemporalAdjusters;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Java XS provider for Time::Moment 0.46.
 *
 * A moment is represented as a blessed hash containing an Instant and the
 * fixed offset in minutes.  java.time provides the proleptic Gregorian
 * calendar, nanosecond precision, ISO week fields, and overflow-safe instant
 * arithmetic that the original XS implementation supplies in C.
 */
public class TimeMoment extends PerlModuleBase {
    private static final String MODULE = "Time::Moment";
    private static final long RD_UNIX_EPOCH_SECONDS = 62135683200L;
    private static final long SECONDS_PER_DAY = 86400;

    public TimeMoment() { super(MODULE, false); }

    public static void initialize() {
        TimeMoment m = new TimeMoment();
        try {
            m.registerMethod("new", "newInstance", null);
            m.registerMethod("now", null); m.registerMethod("now_utc", null);
            m.registerMethod("from_epoch", null); m.registerMethod("from_string", null);
            m.registerMethod("from_rd", null); m.registerMethod("from_jd", null); m.registerMethod("from_mjd", null);
            m.registerMethod("with_offset_same_instant", null); m.registerMethod("with_offset_same_local", null);
            m.registerMethod("with_year", null); m.registerMethod("with_month", null); m.registerMethod("with_week", null); m.registerMethod("with_day_of_month", null);
            m.registerMethod("with_quarter", null); m.registerMethod("with_day_of_year", null); m.registerMethod("with_day_of_quarter", null); m.registerMethod("with_day_of_week", null); m.registerMethod("with_rdn", null);
            m.registerMethod("with_hour", null); m.registerMethod("with_minute", null); m.registerMethod("with_second", null);
            m.registerMethod("with_minute_of_day", null); m.registerMethod("with_second_of_day", null);
            m.registerMethod("with_millisecond", null); m.registerMethod("with_millisecond_of_day", null); m.registerMethod("with_microsecond", null); m.registerMethod("with_microsecond_of_day", null); m.registerMethod("with_nanosecond_of_day", null);
            m.registerMethod("with_nanosecond", null); m.registerMethod("with_precision", null);
            m.registerMethod("precision", null);
            m.registerMethod("plus_years", null); m.registerMethod("plus_months", null); m.registerMethod("plus_weeks", null); m.registerMethod("plus_days", null);
            m.registerMethod("plus_hours", null); m.registerMethod("plus_minutes", null); m.registerMethod("plus_seconds", null); m.registerMethod("plus_milliseconds", null); m.registerMethod("plus_microseconds", null); m.registerMethod("plus_nanoseconds", null);
            m.registerMethod("minus_years", null); m.registerMethod("minus_months", null); m.registerMethod("minus_weeks", null); m.registerMethod("minus_days", null);
            m.registerMethod("minus_hours", null); m.registerMethod("minus_minutes", null); m.registerMethod("minus_seconds", null); m.registerMethod("minus_milliseconds", null); m.registerMethod("minus_microseconds", null); m.registerMethod("minus_nanoseconds", null);
            m.registerMethod("year", null); m.registerMethod("quarter", null); m.registerMethod("month", null); m.registerMethod("week", null);
            m.registerMethod("day_of_year", null); m.registerMethod("day_of_quarter", null); m.registerMethod("day_of_month", null); m.registerMethod("day_of_week", null);
            m.registerMethod("hour", null); m.registerMethod("minute", null); m.registerMethod("second", null); m.registerMethod("millisecond", null); m.registerMethod("microsecond", null); m.registerMethod("nanosecond", null);
            m.registerMethod("minute_of_day", null); m.registerMethod("second_of_day", null); m.registerMethod("millisecond_of_day", null); m.registerMethod("microsecond_of_day", null); m.registerMethod("nanosecond_of_day", null);
            m.registerMethod("epoch", null); m.registerMethod("offset", null); m.registerMethod("rdn", null); m.registerMethod("rd", null); m.registerMethod("jd", null); m.registerMethod("mjd", null);
            m.registerMethod("utc_rd_as_seconds", null); m.registerMethod("local_rd_as_seconds", null); m.registerMethod("utc_rd_values", null); m.registerMethod("local_rd_values", null);
            m.registerMethod("length_of_year", null); m.registerMethod("length_of_month", null); m.registerMethod("length_of_quarter", null); m.registerMethod("length_of_week_year", null);
            m.registerMethod("at_utc", null); m.registerMethod("at_midnight", null); m.registerMethod("at_noon", null); m.registerMethod("at_last_day_of_month", null); m.registerMethod("at_last_day_of_year", null); m.registerMethod("at_last_day_of_quarter", null);
            m.registerMethod("compare", null); m.registerMethod("is_equal", null); m.registerMethod("is_before", null); m.registerMethod("is_after", null); m.registerMethod("is_leap_year", null);
            m.registerMethod("delta_years", null); m.registerMethod("delta_months", null); m.registerMethod("delta_weeks", null); m.registerMethod("delta_days", null);
            m.registerMethod("delta_hours", null); m.registerMethod("delta_minutes", null); m.registerMethod("delta_seconds", null); m.registerMethod("delta_milliseconds", null); m.registerMethod("delta_microseconds", null); m.registerMethod("delta_nanoseconds", null);
            m.registerMethod("to_string", null); m.registerMethod("strftime", null);
        } catch (NoSuchMethodException e) { throw new IllegalStateException(e); }
    }

    private static OffsetDateTime value(RuntimeScalar self) {
        RuntimeHash h = self.hashDeref();
        return Instant.ofEpochSecond(h.get("epoch").getLong(), h.get("nanosecond").getLong())
                .atOffset(ZoneOffset.ofTotalSeconds(h.get("offset").getInt() * 60));
    }
    private static RuntimeList result(OffsetDateTime time, RuntimeScalar klass) {
        return result(time, klass, Integer.MIN_VALUE);
    }
    private static RuntimeList result(OffsetDateTime time, RuntimeScalar klass, int precision) {
        if (time.getYear() < 1 || time.getYear() > 9999) throw new IllegalArgumentException("Time::Moment is out of range");
        RuntimeHash h = new RuntimeHash();
        h.put("epoch", new RuntimeScalar(time.toEpochSecond()));
        h.put("nanosecond", new RuntimeScalar(time.getNano()));
        h.put("offset", new RuntimeScalar(time.getOffset().getTotalSeconds() / 60));
        if (precision != Integer.MIN_VALUE) h.put("precision", new RuntimeScalar(precision));
        RuntimeScalar ref = h.createReference();
        ReferenceOperators.bless(ref, klass);
        return ref.getList();
    }
    private static RuntimeScalar classOf(RuntimeArray args) { return args.get(0); }
    private static RuntimeScalar classOfSelf(RuntimeScalar self) {
        int blessId = RuntimeScalarType.blessedId(self);
        return new RuntimeScalar(blessId == 0 ? MODULE : NameNormalizer.getBlessStr(blessId));
    }
    private static int namedInt(RuntimeArray args, String name, int fallback) {
        for (int i = 1; i + 1 < args.size(); i += 2) if (name.equals(args.get(i).toString())) return args.get(i + 1).getInt();
        return fallback;
    }
    private static int namedInt(RuntimeArray args, int first, String name, int fallback) {
        for (int i = first; i + 1 < args.size(); i += 2) if (name.equals(args.get(i).toString())) return args.get(i + 1).getInt();
        return fallback;
    }
    private static double namedDouble(RuntimeArray args, int first, String name, double fallback) {
        for (int i = first; i + 1 < args.size(); i += 2) if (name.equals(args.get(i).toString())) return args.get(i + 1).getDouble();
        return fallback;
    }
    private static boolean hasNamed(RuntimeArray args, int first, String name) {
        for (int i = first; i + 1 < args.size(); i += 2) if (name.equals(args.get(i).toString())) return true;
        return false;
    }

    public static RuntimeList newInstance(RuntimeArray a, int c) {
        int year=namedInt(a,"year",1), month=namedInt(a,"month",1), day=namedInt(a,"day",1), hour=namedInt(a,"hour",0), minute=namedInt(a,"minute",0), second=namedInt(a,"second",0), nano=namedInt(a,"nanosecond",0), offset=namedInt(a,"offset",0);
        return result(OffsetDateTime.of(year,month,day,hour,minute,second,nano,ZoneOffset.ofTotalSeconds(offset*60)), classOf(a));
    }
    public static RuntimeList now(RuntimeArray a, int c) { return result(OffsetDateTime.now(), classOf(a)); }
    public static RuntimeList now_utc(RuntimeArray a, int c) { return result(OffsetDateTime.now(ZoneOffset.UTC), classOf(a)); }
    public static RuntimeList from_epoch(RuntimeArray a, int c) {
        double epoch=a.get(1).getDouble(); long sec=(long)Math.floor(epoch); int nanos=(int)Math.round((epoch-sec)*1_000_000_000L);
        if(nanos==1_000_000_000) { sec++; nanos=0; }
        if(a.size()>2 && !"nanosecond".equals(a.get(2).toString()) && !"precision".equals(a.get(2).toString())) nanos=a.get(2).getInt();
        else nanos=namedInt(a,2,"nanosecond",nanos);
        int precision=namedInt(a,2,"precision",epoch==Math.rint(epoch)?9:6); if(precision<9) { int factor=(int)Math.pow(10,9-precision); nanos=((nanos+factor/2)/factor)*factor; if(nanos==1_000_000_000) { sec++; nanos=0; } }
        // Time::Moment derives a six-digit display precision for fractional
        // epoch input, including trailing zeroes (0.6 becomes ".600000").
        // Keep that metadata rather than trimming it in to_string().
        return result(Instant.ofEpochSecond(sec,nanos).atOffset(ZoneOffset.UTC), classOf(a), precision);
    }
    private static final DateTimeFormatter BASIC_WEEK_DATE = new DateTimeFormatterBuilder().appendPattern("YYYY'W'wwe").toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter BASIC_ORDINAL_DATE = new DateTimeFormatterBuilder().appendPattern("uuuuDDD").toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private static LocalDate parseDate(String date) {
        if (date.matches("\\d{8}")) return LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE);
        if (date.matches("\\d{4}W\\d{3}")) return LocalDate.parse(date.substring(0,4)+"-W"+date.substring(5,7)+"-"+date.substring(7), DateTimeFormatter.ISO_WEEK_DATE);
        if (date.matches("\\d{7}")) return LocalDate.parse(date, BASIC_ORDINAL_DATE);
        if (date.matches("\\d{4}-W\\d{2}-\\d")) return LocalDate.parse(date, DateTimeFormatter.ISO_WEEK_DATE);
        if (date.matches("\\d{4}-\\d{3}")) return LocalDate.parse(date, DateTimeFormatter.ISO_ORDINAL_DATE);
        return LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);
    }
    private static OffsetDateTime parseMoment(String text, boolean lenient) {
        try {
            String value=text;
            if (lenient) {
                value=value.replace('t','T').replace('z','Z').replaceAll("(?i)\\b(?:UTC|GMT)(?=\\s*$)", "Z").replaceAll("(?i)\\b(?:UTC|GMT)(?=[+-])", "");
                value=value.replaceFirst("^(\\d{4}(?:-\\d{2}-\\d{2}|\\d{4}|-W\\d{2}-\\d|-\\d{3}))\\s+", "$1T");
                value=value.replaceAll("\\s+", "");
                value=value.replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2").replaceAll("([+-])(\\d)$", "$10$2:00").replaceAll("([+-]\\d{2})$", "$1:00");
            }
            // Compact ISO-8601 offsets are accepted in strict form too; this
            // is needed for compact date/time strings emitted by callers.
            value=value.replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2");
            // Time::Moment's canonical reduced form omits a zero offset-minute
            // field (for example, "-14"), and strict parsing accepts it too.
            value=value.replaceAll("([+-]\\d{2})$", "$1:00");
            int separator=value.indexOf('T'); if(separator<0) throw new DateTimeException("missing T");
            String date=value.substring(0,separator), clockZone=value.substring(separator+1);
            java.util.regex.Matcher zone=java.util.regex.Pattern.compile("(Z|[+-]\\d{2}:\\d{2})$").matcher(clockZone);
            if(!zone.find()) throw new DateTimeException("missing offset");
            String clock=clockZone.substring(0,zone.start()).replace(',', '.');
            // Strict compact ISO permits a compact clock with a compact date
            // (20121224T1530+0100), but not with an extended date.
            if(!lenient && date.contains("-") && clock.matches("\\d{4}")) throw new DateTimeException("basic time requires lenient mode");
            // Strict mode also requires compact dates to use compact clocks
            // and offsets.  Time::Moment accepts these mixed spellings only
            // with lenient => 1.
            boolean basicDate=date.matches("\\d{8}");
            boolean extendedSourceOffset=text.substring(separator + 1).matches(".*[+-]\\d{2}:\\d{2}$");
            if(!lenient && basicDate && (clock.contains(":") || extendedSourceOffset)) throw new DateTimeException("mixed ISO styles require lenient mode");
            if(clock.matches("\\d{6}(?:\\.\\d+)?")) clock=clock.substring(0,2)+":"+clock.substring(2,4)+":"+clock.substring(4);
            if(clock.matches("\\d{4}")) clock=clock.substring(0,2)+":"+clock.substring(2);
            if(clock.matches("\\d{2}")) clock += ":00:00";
            int dot=clock.indexOf('.'); if(dot>=0 && clock.length()-dot-1>9) clock=clock.substring(0,dot+10);
            if(clock.length()==5) clock += ":00";
            if(clock.startsWith("24:00")) { if(!clock.matches("24:00(?::00(?:\\.0*)?)?")) throw new DateTimeException("invalid 24 hour"); return parseDate(date).plusDays(1).atStartOfDay().atOffset(ZoneOffset.of(zone.group(1))); }
            return parseDate(date).atTime(LocalTime.parse(clock,DateTimeFormatter.ISO_LOCAL_TIME)).atOffset(ZoneOffset.of(zone.group(1)));
        } catch (RuntimeException e) { throw new IllegalArgumentException("Could not parse Time::Moment string: " + text, e); }
    }
    public static RuntimeList from_string(RuntimeArray a, int c) { return result(parseMoment(a.get(1).toString(),namedInt(a,2,"lenient",0)!=0), classOf(a)); }
    private static RuntimeList fromRd(RuntimeArray a, double rd) {
        double epoch=namedDouble(a,2,"epoch",0); int offset=namedInt(a,2,"offset",0); int precision=namedInt(a,2,"precision",3);
        BigDecimal total=BigDecimal.valueOf(rd).add(BigDecimal.valueOf(epoch)); long days=total.setScale(0, RoundingMode.FLOOR).longValueExact(); long nanos=total.subtract(BigDecimal.valueOf(days)).multiply(BigDecimal.valueOf(86_400_000_000_000L)).setScale(0, RoundingMode.DOWN).longValueExact();
        if(nanos==86_400_000_000_000L) { days++; nanos=0; }
        if(precision<9) { long factor=(long)Math.pow(10,9-precision); nanos=((nanos+factor/2)/factor)*factor; }
        LocalDateTime local=LocalDate.ofEpochDay(days-719163).atStartOfDay().plusNanos(nanos);
        return result(local.atOffset(ZoneOffset.ofTotalSeconds(offset*60)),classOf(a),precision==6?-1:precision);
    }
    public static RuntimeList from_rd(RuntimeArray a,int c){return fromRd(a,a.get(1).getDouble());}
    public static RuntimeList from_jd(RuntimeArray a,int c){double input=a.get(1).getDouble(); return fromRd(a,hasNamed(a,2,"epoch")?input:input-1721424.5);}
    public static RuntimeList from_mjd(RuntimeArray a,int c){double input=a.get(1).getDouble(); return fromRd(a,hasNamed(a,2,"epoch")?input:input+678576);}
    public static RuntimeList with_offset_same_instant(RuntimeArray a,int c) { return result(value(a.get(0)).withOffsetSameInstant(ZoneOffset.ofTotalSeconds(a.get(1).getInt()*60)), classOfSelf(a.get(0))); }
    public static RuntimeList with_offset_same_local(RuntimeArray a,int c) { return result(value(a.get(0)).withOffsetSameLocal(ZoneOffset.ofTotalSeconds(a.get(1).getInt()*60)), classOfSelf(a.get(0))); }
    private static RuntimeList changed(RuntimeArray a, OffsetDateTime v) { return result(v,classOfSelf(a.get(0))); }
    public static RuntimeList with_year(RuntimeArray a,int c){return changed(a,value(a.get(0)).withYear(a.get(1).getInt()));}
    public static RuntimeList with_quarter(RuntimeArray a,int c){OffsetDateTime x=t(a); return changed(a,x.withMonth((a.get(1).getInt()-1)*3+(x.getMonthValue()-1)%3+1));}
    public static RuntimeList with_month(RuntimeArray a,int c){return changed(a,value(a.get(0)).withMonth(a.get(1).getInt()));}
    public static RuntimeList with_week(RuntimeArray a,int c){return changed(a,t(a).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR,a.get(1).getInt()));}
    public static RuntimeList with_day_of_year(RuntimeArray a,int c){return changed(a,t(a).withDayOfYear(a.get(1).getInt()));}
    public static RuntimeList with_day_of_quarter(RuntimeArray a,int c){OffsetDateTime x=t(a); LocalDate d=LocalDate.of(x.getYear(),(x.get(IsoFields.QUARTER_OF_YEAR)-1)*3+1,1).plusDays(a.get(1).getInt()-1); return changed(a,d.atTime(x.toLocalTime()).atOffset(x.getOffset()));}
    public static RuntimeList with_day_of_month(RuntimeArray a,int c){return changed(a,value(a.get(0)).withDayOfMonth(a.get(1).getInt()));}
    public static RuntimeList with_day_of_week(RuntimeArray a,int c){OffsetDateTime x=t(a); return changed(a,x.plusDays(a.get(1).getInt()-x.getDayOfWeek().getValue()));}
    public static RuntimeList with_rdn(RuntimeArray a,int c){OffsetDateTime x=t(a); LocalDate d=LocalDate.ofEpochDay(a.get(1).getLong()-719163); return changed(a,d.atTime(x.toLocalTime()).atOffset(x.getOffset()));}
    public static RuntimeList with_hour(RuntimeArray a,int c){return changed(a,value(a.get(0)).withHour(a.get(1).getInt()));}
    public static RuntimeList with_minute(RuntimeArray a,int c){return changed(a,value(a.get(0)).withMinute(a.get(1).getInt()));}
    public static RuntimeList with_second(RuntimeArray a,int c){return changed(a,value(a.get(0)).withSecond(a.get(1).getInt()));}
    private static RuntimeList withTime(RuntimeArray a, LocalTime time){OffsetDateTime x=t(a); return changed(a,x.toLocalDate().atTime(time).atOffset(x.getOffset()));}
    public static RuntimeList with_minute_of_day(RuntimeArray a,int c){OffsetDateTime x=t(a); return withTime(a,LocalTime.ofSecondOfDay(a.get(1).getInt()*60L+x.getSecond()).withNano(x.getNano()));}
    public static RuntimeList with_second_of_day(RuntimeArray a,int c){OffsetDateTime x=t(a); return withTime(a,LocalTime.ofSecondOfDay(a.get(1).getInt()).withNano(x.getNano()));}
    public static RuntimeList with_millisecond(RuntimeArray a,int c){return changed(a,t(a).withNano(a.get(1).getInt()*1_000_000));}
    public static RuntimeList with_microsecond(RuntimeArray a,int c){return changed(a,t(a).withNano(a.get(1).getInt()*1_000));}
    private static RuntimeList withNanosOfDay(RuntimeArray a, long nanos) { OffsetDateTime x=t(a); return changed(a,x.toLocalDate().atStartOfDay().plusNanos(nanos).atOffset(x.getOffset())); }
    public static RuntimeList with_millisecond_of_day(RuntimeArray a,int c){return withNanosOfDay(a,Math.multiplyExact(a.get(1).getLong(),1_000_000));}
    public static RuntimeList with_microsecond_of_day(RuntimeArray a,int c){return withNanosOfDay(a,Math.multiplyExact(a.get(1).getLong(),1_000));}
    public static RuntimeList with_nanosecond_of_day(RuntimeArray a,int c){return withNanosOfDay(a,a.get(1).getLong());}
    public static RuntimeList with_nanosecond(RuntimeArray a,int c){return changed(a,value(a.get(0)).withNano(a.get(1).getInt()));}
    public static RuntimeList with_precision(RuntimeArray a,int c){int p=a.get(1).getInt(); OffsetDateTime x=value(a.get(0)); if(p>=0) { int factor=(int)Math.pow(10,9-p); x=x.withNano(x.getNano()/factor*factor); } else if(p==-1) x=x.withSecond(0).withNano(0); else if(p==-2) x=x.withMinute(0).withSecond(0).withNano(0); else x=x.withHour(0).withMinute(0).withSecond(0).withNano(0); return result(x,classOfSelf(a.get(0)),p);}
    public static RuntimeList precision(RuntimeArray a,int c){RuntimeHash h=a.get(0).hashDeref(); return n(h.containsKey("precision")?h.get("precision").getInt():9);}
    private static RuntimeList plus(RuntimeArray a, String unit, int sign) { OffsetDateTime t=value(a.get(0)); long n=a.get(1).getLong()*sign; return changed(a,switch(unit){case "years"->t.plusYears(n);case "months"->t.plusMonths(n);case "weeks"->t.plusWeeks(n);case "days"->t.plusDays(n);case "hours"->t.plusHours(n);case "minutes"->t.plusMinutes(n);case "seconds"->t.plusSeconds(n);case "milliseconds"->t.plusNanos(Math.multiplyExact(n,1_000_000));case "microseconds"->t.plusNanos(Math.multiplyExact(n,1_000));default->t.plusNanos(n);}); }
    public static RuntimeList plus_years(RuntimeArray a,int c){return plus(a,"years",1);} public static RuntimeList plus_months(RuntimeArray a,int c){return plus(a,"months",1);} public static RuntimeList plus_weeks(RuntimeArray a,int c){return plus(a,"weeks",1);} public static RuntimeList plus_days(RuntimeArray a,int c){return plus(a,"days",1);} public static RuntimeList plus_hours(RuntimeArray a,int c){return plus(a,"hours",1);} public static RuntimeList plus_minutes(RuntimeArray a,int c){return plus(a,"minutes",1);} public static RuntimeList plus_seconds(RuntimeArray a,int c){return plus(a,"seconds",1);} public static RuntimeList plus_milliseconds(RuntimeArray a,int c){return plus(a,"milliseconds",1);} public static RuntimeList plus_microseconds(RuntimeArray a,int c){return plus(a,"microseconds",1);} public static RuntimeList plus_nanoseconds(RuntimeArray a,int c){return plus(a,"nanoseconds",1);}
    public static RuntimeList minus_years(RuntimeArray a,int c){return plus(a,"years",-1);} public static RuntimeList minus_months(RuntimeArray a,int c){return plus(a,"months",-1);} public static RuntimeList minus_weeks(RuntimeArray a,int c){return plus(a,"weeks",-1);} public static RuntimeList minus_days(RuntimeArray a,int c){return plus(a,"days",-1);} public static RuntimeList minus_hours(RuntimeArray a,int c){return plus(a,"hours",-1);} public static RuntimeList minus_minutes(RuntimeArray a,int c){return plus(a,"minutes",-1);} public static RuntimeList minus_seconds(RuntimeArray a,int c){return plus(a,"seconds",-1);} public static RuntimeList minus_milliseconds(RuntimeArray a,int c){return plus(a,"milliseconds",-1);} public static RuntimeList minus_microseconds(RuntimeArray a,int c){return plus(a,"microseconds",-1);} public static RuntimeList minus_nanoseconds(RuntimeArray a,int c){return plus(a,"nanoseconds",-1);}
    private static RuntimeList n(long v){return new RuntimeScalar(v).getList();} private static OffsetDateTime t(RuntimeArray a){return value(a.get(0));}
    public static RuntimeList year(RuntimeArray a,int c){return n(t(a).getYear());} public static RuntimeList quarter(RuntimeArray a,int c){return n(t(a).get(IsoFields.QUARTER_OF_YEAR));} public static RuntimeList month(RuntimeArray a,int c){return n(t(a).getMonthValue());} public static RuntimeList week(RuntimeArray a,int c){return n(t(a).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));} public static RuntimeList day_of_year(RuntimeArray a,int c){return n(t(a).getDayOfYear());} public static RuntimeList day_of_quarter(RuntimeArray a,int c){return n(t(a).get(IsoFields.DAY_OF_QUARTER));} public static RuntimeList day_of_month(RuntimeArray a,int c){return n(t(a).getDayOfMonth());} public static RuntimeList day_of_week(RuntimeArray a,int c){return n(t(a).getDayOfWeek().getValue());} public static RuntimeList hour(RuntimeArray a,int c){return n(t(a).getHour());} public static RuntimeList minute(RuntimeArray a,int c){return n(t(a).getMinute());} public static RuntimeList second(RuntimeArray a,int c){return n(t(a).getSecond());} public static RuntimeList millisecond(RuntimeArray a,int c){return n(t(a).getNano()/1_000_000);} public static RuntimeList microsecond(RuntimeArray a,int c){return n(t(a).getNano()/1_000);} public static RuntimeList nanosecond(RuntimeArray a,int c){return n(t(a).getNano());} public static RuntimeList epoch(RuntimeArray a,int c){return n(t(a).toEpochSecond());} public static RuntimeList offset(RuntimeArray a,int c){return n(t(a).getOffset().getTotalSeconds()/60);} public static RuntimeList rdn(RuntimeArray a,int c){return n(t(a).getLong(JulianFields.RATA_DIE));} private static double rdValue(OffsetDateTime x){return x.toLocalDate().getLong(JulianFields.RATA_DIE)+(x.toLocalTime().toSecondOfDay()+x.getNano()/1_000_000_000.0)/SECONDS_PER_DAY;} private static OffsetDateTime precisionValue(OffsetDateTime x,int p){if(p>=9)return x; if(p>=0){int factor=(int)Math.pow(10,9-p);return x.withNano(x.getNano()/factor*factor);} if(p==-1)return x.withSecond(0).withNano(0); if(p==-2)return x.withMinute(0).withSecond(0).withNano(0); return x.withHour(0).withMinute(0).withSecond(0).withNano(0);} public static RuntimeList rd(RuntimeArray a,int c){return new RuntimeScalar(rdValue(precisionValue(t(a),namedInt(a,1,"precision",3)))).getList();} public static RuntimeList jd(RuntimeArray a,int c){return new RuntimeScalar(rdValue(precisionValue(t(a),namedInt(a,1,"precision",3)))+1721424.5).getList();} public static RuntimeList mjd(RuntimeArray a,int c){return new RuntimeScalar(rdValue(precisionValue(t(a),namedInt(a,1,"precision",3)))-678576).getList();}
    public static RuntimeList minute_of_day(RuntimeArray a,int c){return n(t(a).toLocalTime().toSecondOfDay()/60);} public static RuntimeList second_of_day(RuntimeArray a,int c){return n(t(a).toLocalTime().toSecondOfDay());} public static RuntimeList millisecond_of_day(RuntimeArray a,int c){return n(t(a).toLocalTime().toNanoOfDay()/1_000_000);} public static RuntimeList microsecond_of_day(RuntimeArray a,int c){return n(t(a).toLocalTime().toNanoOfDay()/1_000);} public static RuntimeList nanosecond_of_day(RuntimeArray a,int c){return n(t(a).toLocalTime().toNanoOfDay());}
    public static RuntimeList utc_rd_as_seconds(RuntimeArray a,int c){return n(t(a).toEpochSecond()+RD_UNIX_EPOCH_SECONDS);} public static RuntimeList local_rd_as_seconds(RuntimeArray a,int c){return n((t(a).toLocalDate().toEpochDay()+719163)*SECONDS_PER_DAY+t(a).toLocalTime().toSecondOfDay());}
    private static RuntimeList rdValues(OffsetDateTime t, boolean local){ OffsetDateTime x=local?t:t.withOffsetSameInstant(ZoneOffset.UTC); RuntimeList r=new RuntimeList();r.add(new RuntimeScalar(x.toLocalDate().getLong(JulianFields.RATA_DIE)));r.add(new RuntimeScalar(x.toLocalTime().toSecondOfDay()));r.add(new RuntimeScalar(x.getNano()));return r;} public static RuntimeList utc_rd_values(RuntimeArray a,int c){return rdValues(t(a),false);} public static RuntimeList local_rd_values(RuntimeArray a,int c){return rdValues(t(a),true);}
    public static RuntimeList length_of_year(RuntimeArray a,int c){return n(t(a).toLocalDate().lengthOfYear());} public static RuntimeList length_of_month(RuntimeArray a,int c){return n(t(a).toLocalDate().lengthOfMonth());} public static RuntimeList length_of_quarter(RuntimeArray a,int c){OffsetDateTime x=t(a);int q=x.get(IsoFields.QUARTER_OF_YEAR),m=(q-1)*3+1;return n(LocalDate.of(x.getYear(),m,1).lengthOfMonth()+LocalDate.of(x.getYear(),m+1,1).lengthOfMonth()+LocalDate.of(x.getYear(),m+2,1).lengthOfMonth());} public static RuntimeList length_of_week_year(RuntimeArray a,int c){int y=t(a).get(IsoFields.WEEK_BASED_YEAR);return n(LocalDate.of(y,12,28).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));}
    public static RuntimeList at_utc(RuntimeArray a,int c){return changed(a,t(a).withOffsetSameInstant(ZoneOffset.UTC));} public static RuntimeList at_midnight(RuntimeArray a,int c){return changed(a,t(a).withHour(0).withMinute(0).withSecond(0).withNano(0));} public static RuntimeList at_noon(RuntimeArray a,int c){return changed(a,t(a).withHour(12).withMinute(0).withSecond(0).withNano(0));} public static RuntimeList at_last_day_of_month(RuntimeArray a,int c){return changed(a,t(a).with(TemporalAdjusters.lastDayOfMonth()));} public static RuntimeList at_last_day_of_year(RuntimeArray a,int c){return changed(a,t(a).withDayOfYear(t(a).toLocalDate().lengthOfYear()));} public static RuntimeList at_last_day_of_quarter(RuntimeArray a,int c){OffsetDateTime x=t(a); return changed(a,x.withMonth(x.get(IsoFields.QUARTER_OF_YEAR)*3).with(TemporalAdjusters.lastDayOfMonth()));}
    private static int cmp(RuntimeArray a){int p=namedInt(a,2,"precision",9);return precisionValue(t(a),p).toInstant().compareTo(precisionValue(value(a.get(1)),p).toInstant());}
    private static RuntimeList perlBoolean(boolean value) { return new RuntimeScalar(value ? 1 : "").getList(); }
    public static RuntimeList compare(RuntimeArray a,int c){return n(cmp(a));}
    public static RuntimeList is_equal(RuntimeArray a,int c){return perlBoolean(cmp(a)==0);}
    public static RuntimeList is_before(RuntimeArray a,int c){return perlBoolean(cmp(a)<0);}
    public static RuntimeList is_after(RuntimeArray a,int c){return perlBoolean(cmp(a)>0);}
    public static RuntimeList is_leap_year(RuntimeArray a,int c){return perlBoolean(t(a).toLocalDate().isLeapYear());}
    private static long deltaMonths(OffsetDateTime first, OffsetDateTime second) {
        LocalDate start = first.toLocalDate(), end = second.toLocalDate();
        long months = (long) (end.getYear() - start.getYear()) * 12 + end.getMonthValue() - start.getMonthValue();
        if (start.isAfter(end)) return months + (end.getDayOfMonth() > start.getDayOfMonth() ? 1 : 0);
        return months - (end.getDayOfMonth() < start.getDayOfMonth() ? 1 : 0);
    }
    private static long deltaDuration(RuntimeArray a, long divisor) {
        Duration duration = Duration.between(t(a).toInstant(), value(a.get(1)).toInstant());
        return duration.getSeconds() / divisor;
    }
    public static RuntimeList delta_years(RuntimeArray a,int c){return n(deltaMonths(t(a),value(a.get(1)))/12);}
    public static RuntimeList delta_months(RuntimeArray a,int c){return n(deltaMonths(t(a),value(a.get(1))));}
    public static RuntimeList delta_weeks(RuntimeArray a,int c){return n(java.time.temporal.ChronoUnit.DAYS.between(t(a).toLocalDate(),value(a.get(1)).toLocalDate())/7);}
    public static RuntimeList delta_days(RuntimeArray a,int c){return n(java.time.temporal.ChronoUnit.DAYS.between(t(a).toLocalDate(),value(a.get(1)).toLocalDate()));}
    public static RuntimeList delta_hours(RuntimeArray a,int c){return n(deltaDuration(a,3600));}
    public static RuntimeList delta_minutes(RuntimeArray a,int c){return n(deltaDuration(a,60));}
    public static RuntimeList delta_seconds(RuntimeArray a,int c){return n(deltaDuration(a,1));}
    public static RuntimeList delta_milliseconds(RuntimeArray a,int c){Duration d=Duration.between(t(a).toInstant(),value(a.get(1)).toInstant()); return n(Math.addExact(Math.multiplyExact(d.getSeconds(),1_000),d.getNano()/1_000_000));}
    public static RuntimeList delta_microseconds(RuntimeArray a,int c){Duration d=Duration.between(t(a).toInstant(),value(a.get(1)).toInstant()); return n(Math.addExact(Math.multiplyExact(d.getSeconds(),1_000_000),d.getNano()/1_000));}
    public static RuntimeList delta_nanoseconds(RuntimeArray a,int c){Duration d=Duration.between(t(a).toInstant(),value(a.get(1)).toInstant()); return n(Math.addExact(Math.multiplyExact(d.getSeconds(),1_000_000_000L),d.getNano()));}
    public static RuntimeList to_string(RuntimeArray a,int c){
        OffsetDateTime value=t(a); boolean reduced=namedInt(a,"reduced",0)!=0;
        String base=String.format(Locale.ROOT,"%04d-%02d-%02dT%02d:%02d",value.getYear(),value.getMonthValue(),value.getDayOfMonth(),value.getHour(),value.getMinute());
        if(!reduced || value.getSecond()!=0 || value.getNano()!=0) base+=String.format(Locale.ROOT,":%02d",value.getSecond());
        RuntimeHash storage=a.get(0).hashDeref(); int precision=storage.containsKey("precision") ? storage.get("precision").getInt() : -1;
        if(value.getNano()!=0) {
            String fraction=String.format(Locale.ROOT,"%09d",value.getNano());
            if(precision>=0) { int digits=precision>=7?9:precision>=4?6:3; fraction=fraction.substring(0,digits); }
            // Time::Moment displays fractional seconds in millisecond groups:
            // retain at least three digits, while eliding wholly zero trailing
            // groups (0.600000 => 0.600, 0.123000000 => 0.123).
            while(fraction.length()>3 && fraction.endsWith("000")) fraction=fraction.substring(0,fraction.length()-3);
            base+='.'+fraction;
        }
        int offset=value.getOffset().getTotalSeconds()/60;
        if(offset==0) return new RuntimeScalar(base+"Z").getList();
        String suffix=Math.abs(offset)%60==0 && reduced ? String.format(Locale.ROOT,"%c%02d",offset<0?'-':'+',Math.abs(offset)/60) : String.format(Locale.ROOT,"%c%02d:%02d",offset<0?'-':'+',Math.abs(offset)/60,Math.abs(offset)%60);
        return new RuntimeScalar(base+suffix).getList();
    }
    /** Time::Moment extends POSIX strftime with fractional seconds and padding modifiers. */
    public static RuntimeList strftime(RuntimeArray a,int c){
        OffsetDateTime value=t(a); String format=a.get(1).toString(); StringBuilder out=new StringBuilder();
        for(int i=0;i<format.length();i++) {
            if(format.charAt(i)!='%') { out.append(format.charAt(i)); continue; }
            if(++i==format.length()) { out.append('%'); break; }
            boolean colon=false; char pad='0', modifier=0; int width=-1;
            if(format.charAt(i)==':') { colon=true; i++; }
            if(i<format.length() && (format.charAt(i)=='-'||format.charAt(i)=='_'||format.charAt(i)=='0')) { modifier=format.charAt(i++); pad=modifier=='_'?' ':modifier=='0'?'0':' '; }
            int start=i; while(i<format.length() && Character.isDigit(format.charAt(i))) i++;
            if(i>start) width=Integer.parseInt(format.substring(start,i));
            if(i>=format.length()) break; char code=format.charAt(i);
            int nano=value.getNano();
            if(code=='N'||code=='f') { int digits=width<0?6:width; String fraction=String.format("%09d",nano).substring(0, Math.min(digits,9)); if(digits>9) fraction += "0".repeat(digits-9); if(width<0) fraction=fraction.replaceFirst("0{1,3}$", ""); if(code=='f' && nano!=0) out.append('.'); if(code=='N'||nano!=0) out.append(fraction); continue; }
            if(code=='z') { int offset=value.getOffset().getTotalSeconds()/60; out.append(colon ? String.format("%c%02d:%02d", offset<0?'-':'+',Math.abs(offset)/60,Math.abs(offset)%60) : String.format("%c%02d%02d", offset<0?'-':'+',Math.abs(offset)/60,Math.abs(offset)%60)); continue; }
            if(code=='k'||code=='l') { int n=code=='k'?value.getHour():(value.getHour()%12==0?12:value.getHour()%12); int digits=width<0?2:width; if(modifier=='-') out.append(n); else out.append(String.valueOf(modifier=='0'?'0':' ').repeat(Math.max(0,digits-String.valueOf(n).length()))).append(n); continue; }
            if(code=='c') { out.append(String.format(Locale.US,"%s %s %2d %02d:%02d:%02d %04d",value.getDayOfWeek().getDisplayName(TextStyle.SHORT,Locale.US),value.getMonth().getDisplayName(TextStyle.SHORT,Locale.US),value.getDayOfMonth(),value.getHour(),value.getMinute(),value.getSecond(),value.getYear())); continue; }
            if(code=='Z' && value.getOffset().getTotalSeconds()==0) { out.append('Z'); continue; }
            if(code=='e'||code=='j') { int number=code=='e'?value.getDayOfMonth():value.getDayOfYear(); int digits=width<0?(code=='e'?2:3):width; if(modifier=='-') out.append(number); else { char fill=modifier=='0'||(modifier==0&&code=='j')?'0':' '; out.append(String.valueOf(fill).repeat(Math.max(0,digits-String.valueOf(number).length()))).append(number); } continue; }
            String rendered=POSIX.formatStrftime("%"+code,value.toZonedDateTime());
            if(modifier=='_' && "YGy gCmVWUdHMSI".replace(" ","").indexOf(code)>=0) {
                int digits=width<0?rendered.length():width;
                String unpadded=rendered.replaceFirst("^0+(?!$)", "");
                out.append(" ".repeat(Math.max(0,digits-unpadded.length()))).append(unpadded); continue;
            }
            if(modifier=='-' && "YGy gCmVWUdHMSI".replace(" ","").indexOf(code)>=0) {
                out.append(rendered.replaceFirst("^0+(?!$)", "")); continue;
            }
            if(width>=0 && rendered.length()<width) out.append(String.valueOf(pad).repeat(width-rendered.length()));
            out.append(rendered);
        }
        return new RuntimeScalar(out.toString()).getList();
    }
}

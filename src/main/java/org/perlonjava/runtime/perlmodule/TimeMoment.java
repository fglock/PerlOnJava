package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.IsoFields;
import java.time.temporal.JulianFields;
import java.time.temporal.TemporalAdjusters;

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
            m.registerMethod("with_offset_same_instant", null); m.registerMethod("with_offset_same_local", null);
            m.registerMethod("with_year", null); m.registerMethod("with_month", null); m.registerMethod("with_day_of_month", null);
            m.registerMethod("with_hour", null); m.registerMethod("with_minute", null); m.registerMethod("with_second", null);
            m.registerMethod("with_nanosecond", null); m.registerMethod("with_precision", null);
            m.registerMethod("plus_years", null); m.registerMethod("plus_months", null); m.registerMethod("plus_weeks", null); m.registerMethod("plus_days", null);
            m.registerMethod("plus_hours", null); m.registerMethod("plus_minutes", null); m.registerMethod("plus_seconds", null); m.registerMethod("plus_milliseconds", null); m.registerMethod("plus_microseconds", null); m.registerMethod("plus_nanoseconds", null);
            m.registerMethod("minus_years", null); m.registerMethod("minus_months", null); m.registerMethod("minus_weeks", null); m.registerMethod("minus_days", null);
            m.registerMethod("minus_hours", null); m.registerMethod("minus_minutes", null); m.registerMethod("minus_seconds", null); m.registerMethod("minus_milliseconds", null); m.registerMethod("minus_microseconds", null); m.registerMethod("minus_nanoseconds", null);
            m.registerMethod("year", null); m.registerMethod("quarter", null); m.registerMethod("month", null); m.registerMethod("week", null);
            m.registerMethod("day_of_year", null); m.registerMethod("day_of_quarter", null); m.registerMethod("day_of_month", null); m.registerMethod("day_of_week", null);
            m.registerMethod("hour", null); m.registerMethod("minute", null); m.registerMethod("second", null); m.registerMethod("millisecond", null); m.registerMethod("microsecond", null); m.registerMethod("nanosecond", null);
            m.registerMethod("epoch", null); m.registerMethod("offset", null); m.registerMethod("rdn", null);
            m.registerMethod("utc_rd_as_seconds", null); m.registerMethod("local_rd_as_seconds", null); m.registerMethod("utc_rd_values", null); m.registerMethod("local_rd_values", null);
            m.registerMethod("length_of_year", null); m.registerMethod("length_of_month", null); m.registerMethod("length_of_quarter", null);
            m.registerMethod("at_utc", null); m.registerMethod("at_midnight", null); m.registerMethod("at_noon", null); m.registerMethod("at_last_day_of_month", null);
            m.registerMethod("compare", null); m.registerMethod("is_equal", null); m.registerMethod("is_before", null); m.registerMethod("is_after", null);
            m.registerMethod("to_string", null); m.registerMethod("strftime", null);
        } catch (NoSuchMethodException e) { throw new IllegalStateException(e); }
    }

    private static OffsetDateTime value(RuntimeScalar self) {
        RuntimeHash h = self.hashDeref();
        return Instant.ofEpochSecond(h.get("epoch").getLong(), h.get("nanosecond").getLong())
                .atOffset(ZoneOffset.ofTotalSeconds(h.get("offset").getInt() * 60));
    }
    private static RuntimeList result(OffsetDateTime time, RuntimeScalar klass) {
        if (time.getYear() < 1 || time.getYear() > 9999) throw new IllegalArgumentException("Time::Moment is out of range");
        RuntimeHash h = new RuntimeHash();
        h.put("epoch", new RuntimeScalar(time.toEpochSecond()));
        h.put("nanosecond", new RuntimeScalar(time.getNano()));
        h.put("offset", new RuntimeScalar(time.getOffset().getTotalSeconds() / 60));
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

    public static RuntimeList newInstance(RuntimeArray a, int c) {
        int year=namedInt(a,"year",1), month=namedInt(a,"month",1), day=namedInt(a,"day",1), hour=namedInt(a,"hour",0), minute=namedInt(a,"minute",0), second=namedInt(a,"second",0), nano=namedInt(a,"nanosecond",0), offset=namedInt(a,"offset",0);
        return result(OffsetDateTime.of(year,month,day,hour,minute,second,nano,ZoneOffset.ofTotalSeconds(offset*60)), classOf(a));
    }
    public static RuntimeList now(RuntimeArray a, int c) { return result(OffsetDateTime.now(), classOf(a)); }
    public static RuntimeList now_utc(RuntimeArray a, int c) { return result(OffsetDateTime.now(ZoneOffset.UTC), classOf(a)); }
    public static RuntimeList from_epoch(RuntimeArray a, int c) {
        long sec=a.get(1).getLong(); int nanos=a.size()>2 && !"nanosecond".equals(a.get(2).toString()) ? a.get(2).getInt() : namedInt(a,"nanosecond",0);
        return result(Instant.ofEpochSecond(sec,nanos).atOffset(ZoneOffset.UTC), classOf(a));
    }
    public static RuntimeList from_string(RuntimeArray a, int c) { return result(OffsetDateTime.parse(a.get(1).toString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME), classOf(a)); }
    public static RuntimeList with_offset_same_instant(RuntimeArray a,int c) { return result(value(a.get(0)).withOffsetSameInstant(ZoneOffset.ofTotalSeconds(a.get(1).getInt()*60)), classOfSelf(a.get(0))); }
    public static RuntimeList with_offset_same_local(RuntimeArray a,int c) { return result(value(a.get(0)).withOffsetSameLocal(ZoneOffset.ofTotalSeconds(a.get(1).getInt()*60)), classOfSelf(a.get(0))); }
    private static RuntimeList changed(RuntimeArray a, OffsetDateTime v) { return result(v,classOfSelf(a.get(0))); }
    public static RuntimeList with_year(RuntimeArray a,int c){return changed(a,value(a.get(0)).withYear(a.get(1).getInt()));}
    public static RuntimeList with_month(RuntimeArray a,int c){return changed(a,value(a.get(0)).withMonth(a.get(1).getInt()));}
    public static RuntimeList with_day_of_month(RuntimeArray a,int c){return changed(a,value(a.get(0)).withDayOfMonth(a.get(1).getInt()));}
    public static RuntimeList with_hour(RuntimeArray a,int c){return changed(a,value(a.get(0)).withHour(a.get(1).getInt()));}
    public static RuntimeList with_minute(RuntimeArray a,int c){return changed(a,value(a.get(0)).withMinute(a.get(1).getInt()));}
    public static RuntimeList with_second(RuntimeArray a,int c){return changed(a,value(a.get(0)).withSecond(a.get(1).getInt()));}
    public static RuntimeList with_nanosecond(RuntimeArray a,int c){return changed(a,value(a.get(0)).withNano(a.get(1).getInt()));}
    public static RuntimeList with_precision(RuntimeArray a,int c){int p=a.get(1).getInt(); int n=value(a.get(0)).getNano(); int factor=(int)Math.pow(10,9-p); return changed(a,value(a.get(0)).withNano(n/factor*factor));}
    private static RuntimeList plus(RuntimeArray a, String unit, int sign) { OffsetDateTime t=value(a.get(0)); long n=a.get(1).getLong()*sign; return changed(a,switch(unit){case "years"->t.plusYears(n);case "months"->t.plusMonths(n);case "weeks"->t.plusWeeks(n);case "days"->t.plusDays(n);case "hours"->t.plusHours(n);case "minutes"->t.plusMinutes(n);case "seconds"->t.plusSeconds(n);case "milliseconds"->t.plusNanos(Math.multiplyExact(n,1_000_000));case "microseconds"->t.plusNanos(Math.multiplyExact(n,1_000));default->t.plusNanos(n);}); }
    public static RuntimeList plus_years(RuntimeArray a,int c){return plus(a,"years",1);} public static RuntimeList plus_months(RuntimeArray a,int c){return plus(a,"months",1);} public static RuntimeList plus_weeks(RuntimeArray a,int c){return plus(a,"weeks",1);} public static RuntimeList plus_days(RuntimeArray a,int c){return plus(a,"days",1);} public static RuntimeList plus_hours(RuntimeArray a,int c){return plus(a,"hours",1);} public static RuntimeList plus_minutes(RuntimeArray a,int c){return plus(a,"minutes",1);} public static RuntimeList plus_seconds(RuntimeArray a,int c){return plus(a,"seconds",1);} public static RuntimeList plus_milliseconds(RuntimeArray a,int c){return plus(a,"milliseconds",1);} public static RuntimeList plus_microseconds(RuntimeArray a,int c){return plus(a,"microseconds",1);} public static RuntimeList plus_nanoseconds(RuntimeArray a,int c){return plus(a,"nanoseconds",1);}
    public static RuntimeList minus_years(RuntimeArray a,int c){return plus(a,"years",-1);} public static RuntimeList minus_months(RuntimeArray a,int c){return plus(a,"months",-1);} public static RuntimeList minus_weeks(RuntimeArray a,int c){return plus(a,"weeks",-1);} public static RuntimeList minus_days(RuntimeArray a,int c){return plus(a,"days",-1);} public static RuntimeList minus_hours(RuntimeArray a,int c){return plus(a,"hours",-1);} public static RuntimeList minus_minutes(RuntimeArray a,int c){return plus(a,"minutes",-1);} public static RuntimeList minus_seconds(RuntimeArray a,int c){return plus(a,"seconds",-1);} public static RuntimeList minus_milliseconds(RuntimeArray a,int c){return plus(a,"milliseconds",-1);} public static RuntimeList minus_microseconds(RuntimeArray a,int c){return plus(a,"microseconds",-1);} public static RuntimeList minus_nanoseconds(RuntimeArray a,int c){return plus(a,"nanoseconds",-1);}
    private static RuntimeList n(long v){return new RuntimeScalar(v).getList();} private static OffsetDateTime t(RuntimeArray a){return value(a.get(0));}
    public static RuntimeList year(RuntimeArray a,int c){return n(t(a).getYear());} public static RuntimeList quarter(RuntimeArray a,int c){return n(t(a).get(IsoFields.QUARTER_OF_YEAR));} public static RuntimeList month(RuntimeArray a,int c){return n(t(a).getMonthValue());} public static RuntimeList week(RuntimeArray a,int c){return n(t(a).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));} public static RuntimeList day_of_year(RuntimeArray a,int c){return n(t(a).getDayOfYear());} public static RuntimeList day_of_quarter(RuntimeArray a,int c){return n(t(a).get(IsoFields.DAY_OF_QUARTER));} public static RuntimeList day_of_month(RuntimeArray a,int c){return n(t(a).getDayOfMonth());} public static RuntimeList day_of_week(RuntimeArray a,int c){return n(t(a).getDayOfWeek().getValue());} public static RuntimeList hour(RuntimeArray a,int c){return n(t(a).getHour());} public static RuntimeList minute(RuntimeArray a,int c){return n(t(a).getMinute());} public static RuntimeList second(RuntimeArray a,int c){return n(t(a).getSecond());} public static RuntimeList millisecond(RuntimeArray a,int c){return n(t(a).getNano()/1_000_000);} public static RuntimeList microsecond(RuntimeArray a,int c){return n(t(a).getNano()/1_000);} public static RuntimeList nanosecond(RuntimeArray a,int c){return n(t(a).getNano());} public static RuntimeList epoch(RuntimeArray a,int c){return n(t(a).toEpochSecond());} public static RuntimeList offset(RuntimeArray a,int c){return n(t(a).getOffset().getTotalSeconds()/60);} public static RuntimeList rdn(RuntimeArray a,int c){return n(t(a).getLong(JulianFields.RATA_DIE));}
    public static RuntimeList utc_rd_as_seconds(RuntimeArray a,int c){return n(t(a).toEpochSecond()+RD_UNIX_EPOCH_SECONDS);} public static RuntimeList local_rd_as_seconds(RuntimeArray a,int c){return n((t(a).toLocalDate().toEpochDay()+719163)*SECONDS_PER_DAY+t(a).toLocalTime().toSecondOfDay());}
    private static RuntimeList rdValues(OffsetDateTime t, boolean local){ OffsetDateTime x=local?t:t.withOffsetSameInstant(ZoneOffset.UTC); RuntimeList r=new RuntimeList();r.add(new RuntimeScalar(x.toLocalDate().getLong(JulianFields.RATA_DIE)));r.add(new RuntimeScalar(x.toLocalTime().toSecondOfDay()));r.add(new RuntimeScalar(x.getNano()));return r;} public static RuntimeList utc_rd_values(RuntimeArray a,int c){return rdValues(t(a),false);} public static RuntimeList local_rd_values(RuntimeArray a,int c){return rdValues(t(a),true);}
    public static RuntimeList length_of_year(RuntimeArray a,int c){return n(t(a).toLocalDate().lengthOfYear());} public static RuntimeList length_of_month(RuntimeArray a,int c){return n(t(a).toLocalDate().lengthOfMonth());} public static RuntimeList length_of_quarter(RuntimeArray a,int c){OffsetDateTime x=t(a);int q=x.get(IsoFields.QUARTER_OF_YEAR),m=(q-1)*3+1;return n(LocalDate.of(x.getYear(),m,1).lengthOfMonth()+LocalDate.of(x.getYear(),m+1,1).lengthOfMonth()+LocalDate.of(x.getYear(),m+2,1).lengthOfMonth());}
    public static RuntimeList at_utc(RuntimeArray a,int c){return changed(a,t(a).withOffsetSameInstant(ZoneOffset.UTC));} public static RuntimeList at_midnight(RuntimeArray a,int c){return changed(a,t(a).withHour(0).withMinute(0).withSecond(0).withNano(0));} public static RuntimeList at_noon(RuntimeArray a,int c){return changed(a,t(a).withHour(12).withMinute(0).withSecond(0).withNano(0));} public static RuntimeList at_last_day_of_month(RuntimeArray a,int c){return changed(a,t(a).with(TemporalAdjusters.lastDayOfMonth()));}
    private static int cmp(RuntimeArray a){return t(a).toInstant().compareTo(value(a.get(1)).toInstant());} public static RuntimeList compare(RuntimeArray a,int c){return n(cmp(a));} public static RuntimeList is_equal(RuntimeArray a,int c){return n(cmp(a)==0?1:0);} public static RuntimeList is_before(RuntimeArray a,int c){return n(cmp(a)<0?1:0);} public static RuntimeList is_after(RuntimeArray a,int c){return n(cmp(a)>0?1:0);}
    public static RuntimeList to_string(RuntimeArray a,int c){return new RuntimeScalar(t(a).toString()).getList();}
    public static RuntimeList strftime(RuntimeArray a,int c){ return new RuntimeScalar(POSIX.formatStrftime(a.get(1).toString(),t(a).toZonedDateTime())).getList(); }
}

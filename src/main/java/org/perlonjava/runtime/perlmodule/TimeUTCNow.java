package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ModuleOperators;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.time.Instant;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/** Java backend for the XS portion of Time::UTC::Now. */
public class TimeUTCNow extends PerlModuleBase {
    public static final String XS_VERSION = "0.013";
    private static final long UNIX_EPOCH_DAYNO = 40587L - 36204L;

    public TimeUTCNow() {
        super("Time::UTC::Now", false);
    }

    public static void initialize() {
        TimeUTCNow module = new TimeUTCNow();
        try {
            module.registerMethod("CLONE", null);
            module.registerMethod("now_utc_rat", ";$");
            module.registerMethod("now_utc_sna", ";$");
            module.registerMethod("now_utc_flt", ";$");
            module.registerMethod("now_utc_dec", ";$");
            module.registerMethod("_try_all", "");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing Time::UTC::Now Java method", e);
        }
    }

    public static RuntimeList CLONE(RuntimeArray args, int ctx) {
        return new RuntimeList();
    }

    public static RuntimeList now_utc_rat(RuntimeArray args, int ctx) {
        Now now = currentOrDie(args);
        if (now == null) return new RuntimeList();
        RuntimeList result = new RuntimeList();
        result.add(bigRat(Long.toString(now.dayno)));
        result.add(bigRat(now.decimalSeconds()));
        result.add(scalarUndef);
        return result;
    }

    public static RuntimeList now_utc_sna(RuntimeArray args, int ctx) {
        Now now = currentOrDie(args);
        if (now == null) return new RuntimeList();
        RuntimeArray tod = new RuntimeArray();
        RuntimeArray.push(tod, new RuntimeScalar(now.seconds));
        RuntimeArray.push(tod, new RuntimeScalar(now.nanos));
        RuntimeArray.push(tod, new RuntimeScalar(0));
        RuntimeList result = new RuntimeList();
        result.add(new RuntimeScalar(now.dayno));
        result.add(tod.createReference());
        result.add(scalarUndef);
        return result;
    }

    public static RuntimeList now_utc_flt(RuntimeArray args, int ctx) {
        Now now = currentOrDie(args);
        if (now == null) return new RuntimeList();
        RuntimeList result = new RuntimeList();
        result.add(new RuntimeScalar(now.dayno));
        result.add(new RuntimeScalar(now.seconds + now.nanos / 1_000_000_000.0));
        result.add(scalarUndef);
        return result;
    }

    public static RuntimeList now_utc_dec(RuntimeArray args, int ctx) {
        Now now = currentOrDie(args);
        if (now == null) return new RuntimeList();
        RuntimeList result = new RuntimeList();
        result.add(new RuntimeScalar(now.dayno));
        result.add(new RuntimeScalar(now.decimalSeconds()));
        result.add(scalarUndef);
        return result;
    }

    public static RuntimeList _try_all(RuntimeArray args, int ctx) {
        Now now = Now.current();
        RuntimeHash mechanism = new RuntimeHash();
        mechanism.put("name", new RuntimeScalar("java.time.Instant"));
        mechanism.put("max_got", new RuntimeScalar(1));
        mechanism.put("got", new RuntimeScalar(1));
        mechanism.put("dayno", new RuntimeScalar(now.dayno));
        mechanism.put("tod", new RuntimeScalar(now.decimalSeconds()));
        RuntimeArray mechanisms = new RuntimeArray();
        RuntimeArray.push(mechanisms, mechanism.createReference());
        return mechanisms.createReference().getList();
    }

    private static Now currentOrDie(RuntimeArray args) {
        if (!args.isEmpty() && args.get(0).getBoolean()) {
            WarnDie.die(new RuntimeScalar("can't find time accurately"), new RuntimeScalar("\n"));
            return null;
        }
        return Now.current();
    }

    private static RuntimeScalar bigRat(String value) {
        if (!GlobalVariable.getGlobalHash("main::INC").exists("Math/BigRat.pm").getBoolean()) {
            ModuleOperators.require(new RuntimeScalar("Math/BigRat.pm"));
        }
        RuntimeArray constructorArgs = new RuntimeArray();
        RuntimeArray.push(constructorArgs, new RuntimeScalar("Math::BigRat"));
        RuntimeArray.push(constructorArgs, new RuntimeScalar(value));
        return RuntimeCode.apply(
                GlobalVariable.getGlobalCodeRef("Math::BigRat::new"),
                constructorArgs,
                RuntimeContextType.SCALAR).scalar();
    }

    private record Now(long dayno, long seconds, int nanos) {
        static Now current() {
            Instant instant = Instant.now();
            long unixDay = Math.floorDiv(instant.getEpochSecond(), 86400L);
            long seconds = Math.floorMod(instant.getEpochSecond(), 86400L);
            return new Now(UNIX_EPOCH_DAYNO + unixDay, seconds, instant.getNano());
        }

        String decimalSeconds() {
            if (nanos == 0) return Long.toString(seconds);
            String decimal = String.format(java.util.Locale.ROOT, "%d.%09d", seconds, nanos);
            int end = decimal.length();
            while (decimal.charAt(end - 1) == '0') end--;
            return decimal.substring(0, end);
        }
    }
}

package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.util.ArrayList;
import java.util.List;

/** Pure-Java implementation of the Math::Factor::XS native API. */
public class MathFactorXS extends PerlModuleBase {
    private static final String CLASS_NAME = "Math::Factor::XS";

    public MathFactorXS() {
        super(CLASS_NAME, false);
    }

    public static void initialize() {
        MathFactorXS module = new MathFactorXS();
        try {
            module.registerMethod("factors", "$");
            module.registerMethod("xs_matches", "$\\@");
            module.registerMethod("prime_factors", null);
            module.registerMethod("count_prime_factors", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unable to initialize " + CLASS_NAME, e);
        }
    }

    public static RuntimeList factors(RuntimeArray args, int ctx) {
        long number = unsignedLongArgument(args.get(0), "factors");
        RuntimeList result = new RuntimeList();
        List<Long> upper = new ArrayList<>();
        for (long divisor = 2; divisor <= number / divisor; divisor++) {
            if (number % divisor == 0) {
                result.add(new RuntimeScalar(divisor));
                long quotient = number / divisor;
                if (quotient > divisor) upper.add(quotient);
            }
        }
        for (int i = upper.size() - 1; i >= 0; i--) {
            result.add(new RuntimeScalar(upper.get(i)));
        }
        return result;
    }

    public static RuntimeList xs_matches(RuntimeArray args, int ctx) {
        long number = unsignedLongArgument(args.get(0), "matches");
        RuntimeArray factors = args.get(1).arrayDeref();
        boolean skipMultiples = false;
        if (args.size() > 2 && args.get(2).type == RuntimeScalarType.HASHREFERENCE) {
            RuntimeHash options = args.get(2).hashDeref();
            skipMultiples = options.containsKey("skip_multiples")
                    && options.get("skip_multiples").getBoolean();
        }

        RuntimeList result = new RuntimeList();
        List<Long> previousBases = new ArrayList<>();
        for (RuntimeScalar baseScalar : factors.elements) {
            long base = baseScalar.getLong();
            for (RuntimeScalar comparisonScalar : factors.elements) {
                long comparison = comparisonScalar.getLong();
                if (comparison < base || base == 0
                        || number % base != 0 || number / base != comparison) {
                    continue;
                }
                boolean skip = false;
                if (skipMultiples) {
                    for (long previousBase : previousBases) {
                        if (previousBase != 0 && base % previousBase == 0) {
                            skip = true;
                            break;
                        }
                    }
                }
                if (!skip) {
                    RuntimeArray pair = new RuntimeArray();
                    pair.push(new RuntimeScalar(base));
                    pair.push(new RuntimeScalar(comparison));
                    result.add(pair.createAnonymousReference());
                    if (skipMultiples) previousBases.add(base);
                }
            }
        }
        return result;
    }

    public static RuntimeList prime_factors(RuntimeArray args, int ctx) {
        long number = unsignedLongArgument(args.get(0), "prime_factors");
        return primeFactors(number);
    }

    public static RuntimeList count_prime_factors(RuntimeArray args, int ctx) {
        long number = unsignedLongArgument(args.get(0), "prime_factors");
        return new RuntimeScalar(primeFactors(number).size()).getList();
    }

    private static RuntimeList primeFactors(long number) {
        RuntimeList result = new RuntimeList();
        while (number > 0 && (number & 1) == 0) {
            result.add(new RuntimeScalar(2));
            number >>= 1;
        }
        while (number > 0 && number % 3 == 0) {
            result.add(new RuntimeScalar(3));
            number /= 3;
        }
        long increment = 2;
        for (long divisor = 5; divisor <= number / divisor; divisor += increment, increment = 6 - increment) {
            while (number % divisor == 0) {
                result.add(new RuntimeScalar(divisor));
                number /= divisor;
            }
        }
        if (number > 1) result.add(new RuntimeScalar(number));
        return result;
    }

    private static long unsignedLongArgument(RuntimeScalar argument, String function) {
        double numeric = argument.getDouble();
        if (!Double.isFinite(numeric) || numeric < 0 || numeric > Long.MAX_VALUE) {
            throw new PerlCompilerException("Cannot " + function + "() on " + numeric);
        }
        return argument.getLong();
    }
}

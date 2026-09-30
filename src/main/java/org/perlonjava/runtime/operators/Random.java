package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.DualVar;
import org.perlonjava.runtime.operators.WarnDie;

import java.math.BigInteger;

/**
 * The Random class provides methods to generate random numbers and manage random seeds.
 */
public class Random {
    private static final BigInteger UV_MAX = BigInteger.ONE.shiftLeft(Long.SIZE).subtract(BigInteger.ONE);
    /**
     * The current seed used for random number generation.
     */
    public static final class State {
        long currentSeed = System.currentTimeMillis();
        final java.util.Random random = new java.util.Random(currentSeed);
    }

    private static State state() {
        return PerlRuntime.current().randomState;
    }

    /**
     * Sets a new seed for the random number generator. If a seed is provided via the
     * {@link RuntimeScalar} parameter, it is used as the new seed. Otherwise, a semi-random
     * seed is generated.
     *
     * @param runtimeScalar A {@link RuntimeScalar} object that may contain a seed value.
     * @return A dual-valued scalar containing the seed used by this call.
     */
    public static RuntimeScalar srand(RuntimeScalar runtimeScalar) {
        State state = state();
        BigInteger seed;
        if (runtimeScalar.type != RuntimeScalarType.UNDEF) {
            seed = runtimeScalar.getBigint();
            boolean overflow = seed.signum() >= 0 && seed.compareTo(UV_MAX) > 0;
            if (runtimeScalar.type == RuntimeScalarType.DOUBLE
                    && runtimeScalar.getDouble() >= Math.scalb(1.0, Long.SIZE)) {
                overflow = true;
            }
            if (overflow) {
                WarnDie.warnWithCategory(new RuntimeScalar("Integer overflow in srand"),
                        new RuntimeScalar(""), "overflow");
                seed = UV_MAX;
            }
            seed = seed.mod(BigInteger.ONE.shiftLeft(Long.SIZE));
            state.currentSeed = seed.longValue();
        } else {
            // Semi-randomly choose a seed if no argument is provided
            state.currentSeed = System.nanoTime() ^ System.identityHashCode(Thread.currentThread());
            seed = unsignedLong(state.currentSeed);
        }
        state.random.setSeed(state.currentSeed);
        RuntimeScalar result = new RuntimeScalar();
        result.type = RuntimeScalarType.DUALVAR;
        RuntimeScalar numeric = new RuntimeScalar(seed);
        RuntimeScalar string = new RuntimeScalar(seed.signum() == 0 ? "0 but true" : seed.toString());
        result.value = new DualVar(numeric, string);
        return result;
    }

    private static BigInteger unsignedLong(long value) {
        BigInteger signed = BigInteger.valueOf(value);
        return value >= 0 ? signed : signed.add(BigInteger.ONE.shiftLeft(Long.SIZE));
    }

    /**
     * Generates a random number scaled by the value provided in the {@link RuntimeScalar} parameter.
     *
     * @param runtimeScalar A {@link RuntimeScalar} object that provides the scaling factor.
     * @return A {@link RuntimeScalar} containing the scaled random number.
     */
    public static RuntimeScalar rand(RuntimeScalar runtimeScalar) {
        return new RuntimeScalar(state().random.nextDouble() * runtimeScalar.getDouble());
    }
}

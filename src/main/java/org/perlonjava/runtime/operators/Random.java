package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

/**
 * The Random class provides methods to generate random numbers and manage random seeds.
 */
public class Random {
    /**
     * The current seed used for random number generation.
     */
    public static long currentSeed = System.currentTimeMillis();

    private static final java.util.Random random = new java.util.Random(currentSeed);

    /**
     * Sets a new seed for the random number generator. If a seed is provided via the
     * {@link RuntimeScalar} parameter, it is used as the new seed. Otherwise, a semi-random
     * seed is generated.
     *
     * @param runtimeScalar A {@link RuntimeScalar} object that may contain a seed value.
     * @return A {@link RuntimeScalar} containing the old seed value.
     */
    public static RuntimeScalar srand(RuntimeScalar runtimeScalar) {
        long oldSeed = currentSeed;
        if (runtimeScalar.type != RuntimeScalarType.UNDEF) {
            currentSeed = runtimeScalar.getInt();
        } else {
            // Semi-randomly choose a seed if no argument is provided
            currentSeed = System.nanoTime() ^ System.identityHashCode(Thread.currentThread());
        }
        random.setSeed(currentSeed);
        return new RuntimeScalar(oldSeed);
    }

    /**
     * Generates a random number scaled by the value provided in the {@link RuntimeScalar} parameter.
     *
     * @param runtimeScalar A {@link RuntimeScalar} object that provides the scaling factor.
     * @return A {@link RuntimeScalar} containing the scaled random number.
     */
    public static RuntimeScalar rand(RuntimeScalar runtimeScalar) {
        return new RuntimeScalar(random.nextDouble() * runtimeScalar.getDouble());
    }
}

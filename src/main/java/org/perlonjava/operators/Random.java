package org.perlonjava.operators;

import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarType;

public class Random {
    public static long currentSeed = System.currentTimeMillis();
    private static final java.util.Random random = new java.util.Random(currentSeed);

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

    public static RuntimeScalar rand(RuntimeScalar runtimeScalar) {
        return new RuntimeScalar(random.nextDouble() * runtimeScalar.getDouble());
    }
}

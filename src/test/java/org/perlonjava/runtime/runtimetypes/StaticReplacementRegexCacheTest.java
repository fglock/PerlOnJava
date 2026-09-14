package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.regex.RuntimeRegex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class StaticReplacementRegexCacheTest {

    @Test
    void refreshesDynamicReplacementWithoutReplacingThePrivateWrapper() {
        PerlRuntime runtime = new PerlRuntime();

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar first = RuntimeRegex.getReplacementRegex(
                    new RuntimeScalar("a"), new RuntimeScalar("left"),
                    new RuntimeScalar("g"), new RuntimeArray(), 75);
            RuntimeScalar second = RuntimeRegex.getReplacementRegex(
                    new RuntimeScalar("a"), new RuntimeScalar("right"),
                    new RuntimeScalar("g"), new RuntimeArray(), 75);
            RuntimeScalar target = new RuntimeScalar("a-a");

            assertSame(first, second);
            assertEquals("2", RuntimeRegex.matchRegex(second, target,
                    RuntimeContextType.SCALAR).toString());
            assertEquals("right-right", target.toString());
        }
    }
}

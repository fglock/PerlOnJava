package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class CleanupTargetProbeReuseTest {
    @Test
    void probeReusesScratchAcrossShortCircuitsAndCycles() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            MortalList.CleanupTargetProbe probe = new MortalList.CleanupTargetProbe();
            assertFalse(probe.mayContainCleanupTargets(new RuntimeHash()));

            RuntimeArray withTarget = new RuntimeArray();
            RuntimeHash blessed = new RuntimeHash();
            blessed.setBlessId(NameNormalizer.getBlessId(
                    "CleanupTargetProbeReuseTest::Target"));
            withTarget.push(blessed.createAnonymousReference());
            assertTrue(probe.mayContainCleanupTargets(withTarget));

            RuntimeArray cycleWithoutTargets = new RuntimeArray();
            cycleWithoutTargets.push(cycleWithoutTargets.createAnonymousReference());
            assertFalse(probe.mayContainCleanupTargets(cycleWithoutTargets));
            assertFalse(probe.mayContainCleanupTargets(new RuntimeArray()));

            MortalList.scopeExitCleanupArray(cycleWithoutTargets);
            MortalList.flush();
        }
    }
}

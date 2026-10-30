package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PerlClosureArgumentOwnerTest {
    @Test
    void temporaryClosureArgumentHasOneCountedOwnerUntilTheCallFrameExits() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode closure = new RuntimeCode((args, context) -> new RuntimeList(), null);
            closure.refCount = 0;
            closure.capturedScalars = new RuntimeScalar[0];
            RuntimeScalar argument = new RuntimeScalar(closure);
            RuntimeCode.CallArguments staged = RuntimeCode.newCallArguments(1);

            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                MortalList.pushMark();
                boolean markActive = true;
                boolean argsActive = false;
                RuntimeArray perlArgs = null;
                try {
                    staged.add(argument);
                    perlArgs = staged.toRuntimeArray();
                    assertTrue(perlArgs.ownsElement(argument));
                    assertEquals(1, closure.refCount);
                    assertEquals(1, closure.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
                    assertEquals(PerlOwnerSlot.Kind.ARGUMENT_VALUE,
                            argument.countedOwnerSlot().kind());

                    RuntimeCode.pushArgs(perlArgs);
                    argsActive = true;
                    RuntimeCode.popArgs();
                    argsActive = false;
                    staged.finish();
                    MortalList.popAndFlush();
                    markActive = false;

                    assertEquals(0, closure.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
                    assertFalse(perlArgs.ownsElement(argument));
                    assertNull(closure.capturedScalars,
                            "the final argument release retires the temporary CV's captures");

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(0, stats.rootQueries,
                            "argument release does not query Java graph reachability");
                    assertEquals(0, stats.snapshotsBuilt,
                            "argument release does not build a reachability snapshot");
                } finally {
                    if (argsActive) RuntimeCode.popArgs();
                    staged.finish();
                    if (markActive) MortalList.popAndFlush();
                }
            }
        }
    }

    @Test
    void abandonedMaterializedArgumentsReleaseTheirCountedClosureOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode closure = new RuntimeCode((args, context) -> new RuntimeList(), null);
            closure.refCount = 0;
            closure.capturedScalars = new RuntimeScalar[0];
            RuntimeScalar argument = new RuntimeScalar(closure);
            RuntimeCode.CallArguments staged = RuntimeCode.newCallArguments(1);

            MortalList.pushMark();
            boolean markActive = true;
            try {
                staged.add(argument);
                RuntimeArray perlArgs = staged.toRuntimeArray();
                assertEquals(1, closure.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));

                RuntimeException failure = new RuntimeException("call rejected before entry");
                assertSame(failure, staged.abandon(failure));
                MortalList.popAndFlush();
                markActive = false;

                assertEquals(0, closure.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
                assertFalse(perlArgs.ownsElement(argument));
                assertNull(closure.capturedScalars,
                        "abandoning the call retires the temporary CV's captures");
            } finally {
                staged.finish();
                if (markActive) MortalList.popAndFlush();
            }
        }
    }
}

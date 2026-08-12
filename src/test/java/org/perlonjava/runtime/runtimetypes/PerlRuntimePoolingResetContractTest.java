package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Guards the negative reset contract until fresh-equivalent pooling exists. */
@Tag("unit")
class PerlRuntimePoolingResetContractTest {

    @Test
    void closeIsTerminalRatherThanAReusableResetTransition() {
        PerlRuntime runtime = new PerlRuntime().initialize();

        runtime.close();

        assertTrue(runtime.isClosed());
        assertThrows(IllegalStateException.class, runtime::bind);
        assertThrows(IllegalStateException.class, runtime::initialize);
        assertThrows(IllegalStateException.class, () -> runtime.execute(() -> "reused"));
    }

    @Test
    void closeDoesNotMakeRetainedStateFreshEquivalent() {
        PerlRuntime used = new PerlRuntime();
        PerlRuntime fresh = new PerlRuntime();

        used.globalState.scalarValues().put(
                "PoolingContract::tenant", new RuntimeScalar("first"));
        used.regexState.optimizedRegexCache.put(7, new RuntimeScalar("compiled"));
        used.executionState.taintMode = true;

        used.close();

        assertTrue(used.globalState.scalarValues().containsKey("PoolingContract::tenant"));
        assertFalse(fresh.globalState.scalarValues().containsKey("PoolingContract::tenant"));
        assertFalse(used.regexState.optimizedRegexCache.isEmpty());
        assertTrue(fresh.regexState.optimizedRegexCache.isEmpty());
        assertTrue(used.executionState.taintMode);
        assertFalse(fresh.executionState.taintMode);
    }
}

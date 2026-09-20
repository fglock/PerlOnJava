package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeScalarPrimitivePayloadTieCopyTest {
    @Test
    void tiedFetchCacheCopiesTheActivePayload() {
        RuntimeScalar fetched = new RuntimeScalar(0);
        fetched.setPrimitiveFlowInteger(47);
        FetchProbe probe = new FetchProbe(fetched);

        probe.vivify();

        assertEquals(47L, probe.getLong());
        assertTrue(fetched.hasPrimitiveFlowInteger());
    }

    private static final class FetchProbe extends TiedVariableBase {
        private final RuntimeScalar fetched;

        private FetchProbe(RuntimeScalar fetched) {
            super(null, null);
            this.fetched = fetched;
        }

        @Override
        public RuntimeScalar tiedStore(RuntimeScalar value) {
            return value;
        }

        @Override
        public RuntimeScalar tiedFetch() {
            return fetched;
        }
    }
}

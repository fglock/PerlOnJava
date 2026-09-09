package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class ExecutionRuntimeStateCallDepthTest {
    @Test
    void releasedCallDepthStateIsReusedButActiveStatesStayDistinct() {
        ExecutionRuntimeState state = new ExecutionRuntimeState();
        RuntimeCode firstCode = new RuntimeCode("first", java.util.List.of());
        RuntimeCode secondCode = new RuntimeCode("second", java.util.List.of());

        ExecutionRuntimeState.CallDepthState first = state.callDepth(firstCode);
        ExecutionRuntimeState.CallDepthState second = state.callDepth(secondCode);
        assertNotSame(first, second);

        state.releaseCallDepth(firstCode);
        RuntimeCode thirdCode = new RuntimeCode("third", java.util.List.of());
        assertSame(first, state.callDepth(thirdCode));
        assertSame(second, state.callDepth(secondCode));
    }
}

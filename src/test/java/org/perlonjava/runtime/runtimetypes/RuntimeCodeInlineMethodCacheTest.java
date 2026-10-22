package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class RuntimeCodeInlineMethodCacheTest {

    @Test
    void packedGuardMatchesBothClassAndMethodAndPreservesSlotReplacement() {
        RuntimeCodeRuntimeState state = new RuntimeCodeRuntimeState();
        RuntimeCode firstMethod = new RuntimeCode(null, List.of());
        RuntimeCode replacement = new RuntimeCode(null, List.of());
        int callsite = 13;
        int blessId = 0x1234_5678;
        int methodHash = 0x89ab_cdef;

        state.cacheInlineMethod(callsite, blessId, methodHash, firstMethod);

        assertSame(firstMethod, state.cachedInlineMethod(callsite, blessId, methodHash));
        assertNull(state.cachedInlineMethod(callsite, blessId + 1, methodHash));
        assertNull(state.cachedInlineMethod(callsite, blessId, methodHash + 1));

        // Callsite IDs alias the fixed-size cache by design. A newer entry
        // must replace both halves of the packed key and the cached code.
        state.cacheInlineMethod(callsite + RuntimeCodeRuntimeState.METHOD_CALL_CACHE_SIZE,
                blessId + 1, methodHash + 1, replacement);
        assertNull(state.cachedInlineMethod(callsite, blessId, methodHash));
        assertSame(replacement, state.cachedInlineMethod(
                callsite + RuntimeCodeRuntimeState.METHOD_CALL_CACHE_SIZE,
                blessId + 1, methodHash + 1));

        state.clearInlineMethodCache();
        assertNull(state.cachedInlineMethod(callsite, blessId + 1, methodHash + 1));
    }

    @Test
    void packedGuardPreservesSignedBoundariesInBothKeyFields() {
        RuntimeCodeRuntimeState state = new RuntimeCodeRuntimeState();
        RuntimeCode method = new RuntimeCode(null, List.of());
        int callsite = 29;
        int blessId = Integer.MIN_VALUE;
        int methodHash = Integer.MAX_VALUE;

        state.cacheInlineMethod(callsite, blessId, methodHash, method);

        assertSame(method, state.cachedInlineMethod(callsite, blessId, methodHash));
        assertNull(state.cachedInlineMethod(callsite, Integer.MAX_VALUE, methodHash));
        assertNull(state.cachedInlineMethod(callsite, blessId, Integer.MIN_VALUE));
    }
}

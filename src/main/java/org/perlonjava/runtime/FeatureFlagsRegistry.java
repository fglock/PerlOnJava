package org.perlonjava.runtime;

import org.perlonjava.runtime.runtimetypes.PerlRuntime;

import java.util.Deque;

/** Tracks lexical feature flags at runtime call sites and across subroutine calls. */
public final class FeatureFlagsRegistry {
    private FeatureFlagsRegistry() {}

    private static CompilationRuntimeState state() {
        return PerlRuntime.current().compilationState;
    }

    public static void setCallSiteFeatureFlags(int flags) {
        state().callSiteFeatureFlags = flags;
    }

    public static int getCallSiteFeatureFlags() {
        return state().callSiteFeatureFlags;
    }

    public static void pushCallerFeatureFlags(CompilationRuntimeState state) {
        state.callerFeatureFlagsStack.push(state.callSiteFeatureFlags);
        state.callSiteFeatureFlags = 0;
    }

    public static void popCallerFeatureFlags(CompilationRuntimeState state) {
        Deque<Integer> stack = state.callerFeatureFlagsStack;
        if (!stack.isEmpty()) state.callSiteFeatureFlags = stack.pop();
    }

    public static int getCallerFeatureFlagsAtFrame(int frame) {
        if (frame < 0) return 0;
        int index = 0;
        for (int flags : state().callerFeatureFlagsStack) {
            if (index++ == frame) return flags;
        }
        return 0;
    }
}

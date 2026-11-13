package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class RuntimeBaseRefCountTrackingStateTest {
    @Test
    void trackingModeIsSeparateFromTheNonnegativeCount() {
        RuntimeScalar referent = new RuntimeScalar();

        assertEquals(0, referent.refCount);
        assertEquals(RuntimeBase.RefCountTrackingState.UNTRACKED,
                referent.refCountTrackingState());

        referent.setTrackedRefCount(2);
        assertEquals(2, referent.refCount);
        assertEquals(RuntimeBase.RefCountTrackingState.TRACKED,
                referent.refCountTrackingState());

        referent.markRefCountWeaklyTracked();
        assertEquals(0, referent.refCount);
        assertEquals(RuntimeBase.RefCountTrackingState.WEAKLY_TRACKED,
                referent.refCountTrackingState());

        referent.markRefCountUntracked();
        assertEquals(0, referent.refCount);
        assertEquals(RuntimeBase.RefCountTrackingState.UNTRACKED,
                referent.refCountTrackingState());
    }

    @Test
    void trackingStateCannotRestoreAnInvalidCount() {
        RuntimeScalar referent = new RuntimeScalar();

        assertThrows(IllegalArgumentException.class,
                () -> referent.setTrackedRefCount(-1));
        assertThrows(IllegalArgumentException.class,
                () -> referent.restoreRefCountState(-1,
                        RuntimeBase.RefCountTrackingState.TRACKED));
        assertThrows(IllegalArgumentException.class,
                () -> referent.restoreRefCountState(1,
                        RuntimeBase.RefCountTrackingState.UNTRACKED));
        assertThrows(IllegalArgumentException.class,
                () -> referent.restoreRefCountState(1,
                        RuntimeBase.RefCountTrackingState.WEAKLY_TRACKED));
    }
}

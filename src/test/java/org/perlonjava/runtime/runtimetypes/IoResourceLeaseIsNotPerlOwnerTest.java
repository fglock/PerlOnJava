package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.io.StandardIO;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class IoResourceLeaseIsNotPerlOwnerTest {
    private PerlRuntime.Binding runtimeBinding;

    @BeforeEach
    void bindRuntime() {
        runtimeBinding = new PerlRuntime().bind();
    }

    @AfterEach
    void unbindRuntime() {
        runtimeBinding.close();
    }

    @Test
    void ioResourceLeaseDoesNotKeepPerlGlobAlive() {
        RuntimeGlob glob = new RuntimeGlob(null).setIO(new RuntimeIO(
                new StandardIO(new ByteArrayOutputStream(), true)));
        RuntimeScalar handle = new RuntimeScalar();
        handle.type = RuntimeScalarType.GLOBREFERENCE;
        handle.value = glob;

        RuntimeScalar.retainUnstashedIoForDurableSlot(handle);

        assertEquals(1, glob.ioHolderCount());
        assertFalse(glob.hasPerlStrongOwnerIdentity(),
                "a resource lease must not stand in for a Perl reference");
        assertFalse(glob.hasPerlStrongOwner(),
                "the runtime lease alone must not postpone Perl destruction");
        assertFalse(glob.hasOwnerSlots(),
                "runtime resource slots must not masquerade as Perl owner slots");
        assertFalse(glob.hasUncountedPerlOwnerSlotIdentity(),
                "the runtime lease must not be mistaken for an uncounted Perl edge");
        assertFalse(DestroyDispatch.hasCurrentPerlOwnerSlot(glob),
                "a runtime resource lease must not be treated as destructor resurrection");

        RuntimeScalar.releaseIoOwner(handle);
        assertEquals(0, glob.ioHolderCount());
    }
}

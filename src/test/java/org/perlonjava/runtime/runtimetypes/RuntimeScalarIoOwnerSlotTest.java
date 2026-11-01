package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.io.StandardIO;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class RuntimeScalarIoOwnerSlotTest {
    private PerlRuntime runtime;
    private PerlRuntime.Binding runtimeBinding;

    @BeforeEach
    void bindRuntime() {
        runtime = new PerlRuntime();
        runtimeBinding = runtime.bind();
    }

    @AfterEach
    void unbindRuntime() {
        runtimeBinding.close();
    }

    @Test
    void durableIoOwnerHasOneIdentityAndReleasesExactlyOnce() {
        RuntimeGlob glob = anonymousGlob();
        RuntimeScalar owner = handle(glob);

        RuntimeScalar.retainUnstashedIoForDurableSlot(owner);
        PerlOwnerSlot ownerSlot = owner.ioOwnerSlot;

        assertTrue(owner.hasIoOwner());
        assertNotNull(ownerSlot);
        assertEquals(PerlOwnerSlot.Kind.IO_RESOURCE_HOLDER, ownerSlot.kind());
        assertTrue(ownerSlot.isNativeOwner());
        assertSame(glob, ownerSlot.referent());
        assertEquals(1, glob.ioHolderCount());
        assertEquals(1, glob.ownerSlotCount(PerlOwnerSlot.Kind.IO_RESOURCE_HOLDER));

        RuntimeScalar.releaseIoOwner(owner);
        RuntimeScalar.releaseIoOwner(owner);

        assertFalse(owner.hasIoOwner());
        assertNull(owner.ioOwnerSlot);
        assertFalse(ownerSlot.isActive());
        assertEquals(0, glob.ioHolderCount());
        assertEquals(0, glob.ownerSlotCount(PerlOwnerSlot.Kind.IO_RESOURCE_HOLDER));
    }

    @Test
    void ownerTransferPreservesIdentityAndHolderCount() {
        RuntimeGlob glob = anonymousGlob();
        RuntimeScalar source = handle(glob);
        RuntimeScalar destination = handle(glob);

        RuntimeScalar.retainUnstashedIoForDurableSlot(source);
        PerlOwnerSlot ownerSlot = source.ioOwnerSlot;
        RuntimeScalar.transferIoOwner(source, destination);

        assertFalse(source.hasIoOwner());
        assertNull(source.ioOwnerSlot);
        assertTrue(destination.hasIoOwner());
        assertSame(ownerSlot, destination.ioOwnerSlot);
        assertEquals(1, glob.ioHolderCount());
        assertEquals(1, glob.ownerSlotCount(PerlOwnerSlot.Kind.IO_RESOURCE_HOLDER));

        RuntimeScalar.releaseIoOwner(destination);
        assertEquals(0, glob.ioHolderCount());
    }

    @Test
    void distinctScalarAliasesHaveDistinctOwnerIdentities() {
        RuntimeGlob glob = anonymousGlob();
        RuntimeScalar first = handle(glob);
        RuntimeScalar second = handle(glob);

        RuntimeScalar.retainUnstashedIoForDurableSlot(first);
        RuntimeScalar.retainUnstashedIoForDurableSlot(second);

        assertNotSame(first.ioOwnerSlot, second.ioOwnerSlot);
        assertEquals(2, glob.ioHolderCount());
        assertEquals(2, glob.ownerSlotCount(PerlOwnerSlot.Kind.IO_RESOURCE_HOLDER));

        RuntimeScalar.releaseIoOwner(first);
        assertEquals(1, glob.ioHolderCount());
        assertTrue(second.ioOwnerSlot.isActive());
        RuntimeScalar.releaseIoOwner(second);
        assertEquals(0, glob.ioHolderCount());
    }

    @Test
    void expressionCopyMovesOwnerButPackageScalarCopyKeepsItsOwner() {
        RuntimeGlob expressionGlob = anonymousGlob();
        RuntimeScalar expression = handle(expressionGlob);
        RuntimeScalar.retainUnstashedIoForDurableSlot(expression);
        PerlOwnerSlot expressionOwner = expression.ioOwnerSlot;

        RuntimeScalar expressionCopy = new RuntimeScalar(expression);

        assertFalse(expression.hasIoOwner());
        assertSame(expressionOwner, expressionCopy.ioOwnerSlot);
        assertEquals(1, expressionGlob.ioHolderCount());

        RuntimeGlob packageGlob = anonymousGlob();
        RuntimeScalar packageScalar = handle(packageGlob);
        packageScalar.isPackageGlobalRoot = true;
        RuntimeScalar.retainUnstashedIoForDurableSlot(packageScalar);
        PerlOwnerSlot packageOwner = packageScalar.ioOwnerSlot;

        RuntimeScalar packageCopy = new RuntimeScalar(packageScalar);

        assertTrue(packageScalar.hasIoOwner());
        assertFalse(packageCopy.hasIoOwner());
        assertSame(packageOwner, packageScalar.ioOwnerSlot);
        assertEquals(1, packageGlob.ioHolderCount());

        RuntimeScalar.transferIoOwner(packageScalar, packageCopy);
        assertSame(packageOwner, packageCopy.ioOwnerSlot);
        RuntimeScalar.releaseIoOwner(expressionCopy);
        RuntimeScalar.releaseIoOwner(packageCopy);
    }

    @Test
    void selfAssignmentPreservesOwnerAndReplacementAcquiresBeforeRetiringOldSlot() {
        RuntimeGlob firstGlob = anonymousGlob();
        RuntimeGlob replacementGlob = anonymousGlob();
        RuntimeScalar owner = handle(firstGlob);
        RuntimeScalar.retainUnstashedIoForDurableSlot(owner);
        PerlOwnerSlot originalOwnerSlot = owner.ioOwnerSlot;

        owner.set(owner);
        assertSame(originalOwnerSlot, owner.ioOwnerSlot);
        assertEquals(1, firstGlob.ioHolderCount());

        RuntimeScalar replacement = handle(replacementGlob);
        owner.set(replacement);

        assertFalse(originalOwnerSlot.isActive());
        assertEquals(0, firstGlob.ioHolderCount());
        assertEquals(0, firstGlob.ownerSlotCount(PerlOwnerSlot.Kind.IO_RESOURCE_HOLDER));
        assertTrue(owner.hasIoOwner());
        assertSame(replacementGlob, owner.ioOwnerSlot.referent());
        assertEquals(1, replacementGlob.ioHolderCount());
        assertEquals(1, replacementGlob.ownerSlotCount(
                PerlOwnerSlot.Kind.IO_RESOURCE_HOLDER));
    }

    private static RuntimeScalar handle(RuntimeGlob glob) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.GLOBREFERENCE;
        scalar.value = glob;
        return scalar;
    }

    private static RuntimeGlob anonymousGlob() {
        return new RuntimeGlob(null).setIO(new RuntimeIO(
                new StandardIO(new ByteArrayOutputStream(), true)));
    }
}

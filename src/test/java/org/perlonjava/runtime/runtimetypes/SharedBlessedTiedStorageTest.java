package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class SharedBlessedTiedStorageTest {
    @Test
    void blessedContainerRetainsSharedStorageIdentity() {
        PerlRuntime sourceRuntime = new PerlRuntime();
        PerlRuntime targetRuntime = new PerlRuntime();
        RuntimeHash object = new RuntimeHash();
        object.elements.put("value", new RuntimeScalar(1));

        try (PerlRuntime.Binding ignored = sourceRuntime.bind()) {
            object.blessId = NameNormalizer.getBlessId("Shared::Object");
            SharedPerlStorage.shareValue(object);
        }
        sourceRuntime.nameNormalizerState.snapshotInto(targetRuntime.nameNormalizerState);

        RuntimeHash child = (RuntimeHash) new RuntimeGraphCloner(sourceRuntime, targetRuntime)
                .cloneGraph(object);

        assertNotSame(object, child);
        assertSame(object.elements, child.elements);
        child.elements.get("value").set(7);
        assertEquals(7, object.elements.get("value").getInt());
        try (PerlRuntime.Binding ignored = targetRuntime.bind()) {
            assertEquals("Shared::Object", NameNormalizer.getBlessStr(child.blessId));
            child.setBlessId(NameNormalizer.getBlessId("Child::Object"));
            assertEquals("Child::Object", NameNormalizer.getBlessStr(child.blessId));
        }
        try (PerlRuntime.Binding ignored = sourceRuntime.bind()) {
            assertEquals("Shared::Object", NameNormalizer.getBlessStr(object.blessId));
        }
    }

    @Test
    void tiedScalarClonesCallbackViewButRetainsSynchronizationIdentity() {
        PerlRuntime sourceRuntime = new PerlRuntime();
        PerlRuntime targetRuntime = new PerlRuntime();
        RuntimeHash tieObject = new RuntimeHash();
        tieObject.elements.put("value", new RuntimeScalar(1));
        RuntimeScalar tieObjectReference = tieObject.createAnonymousReference();
        RuntimeScalar source = new RuntimeScalar();
        source.type = RuntimeScalarType.TIED_SCALAR;
        source.value = new TieScalar("LocalTie", new RuntimeScalar(1), tieObjectReference);

        source.threadShared = true;
        source.threadSharedIdentity = new Object();
        RuntimeScalar child = (RuntimeScalar) new RuntimeGraphCloner(sourceRuntime, targetRuntime)
                .cloneGraph(source);

        assertNotSame(source, child);
        assertTrue(child.threadShared);
        assertSame(source.threadSharedIdentity, child.threadSharedIdentity);
        assertSame(SharedPerlStorage.lockForTesting(source),
                SharedPerlStorage.lockForTesting(child));

        TieScalar sourceTie = assertInstanceOf(TieScalar.class, source.value);
        TieScalar childTie = assertInstanceOf(TieScalar.class, child.value);
        assertNotSame(sourceTie, childTie);
        assertNotSame(sourceTie.getSelf().value, childTie.getSelf().value);
    }
}

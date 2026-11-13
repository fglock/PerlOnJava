package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeHashClonedElementReplacementOwnerTest {
    @Test
    void clonedElementReplacementReleasesTheDisplacedOwnerAfterAcquiringTheNewOne() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash displaced = new RuntimeHash();
            displaced.activateOwnerTracking();
            RuntimeScalar displacedValue = countedValue(displaced);
            RuntimeHash destination = new RuntimeHash();
            destination.putClonedElement("slot", displacedValue);

            RuntimeScalar weakObserver = displaced.createAnonymousReference();
            RuntimeHash replacement = new RuntimeHash();
            replacement.activateOwnerTracking();
            RuntimeScalar replacementValue = countedValue(replacement);
            try {
                WeakRefRegistry.weaken(weakObserver);
                assertTrue(WeakRefRegistry.isweak(weakObserver));

                destination.putClonedElement("slot", replacementValue);

                assertSame(replacementValue, destination.elements.get("slot"));
                assertEquals(PerlOwnerSlot.Kind.HASH_ELEMENT,
                        replacementValue.countedOwnerSlot().kind());
                assertEquals(1, replacement.refCount,
                        "the replacement is acquired before the displaced value is drained");

                MortalList.flush();

                assertFalse(WeakRefRegistry.isweak(weakObserver),
                        "replacing the final hash owner clears the displaced weak observer");
                assertEquals(Integer.MIN_VALUE, displaced.refCount);

                destination.putClonedElement("slot", null);
                MortalList.flush();
                assertEquals(Integer.MIN_VALUE, replacement.refCount);
            } finally {
                if (WeakRefRegistry.isweak(weakObserver)) WeakRefRegistry.unweaken(weakObserver);
                weakObserver.set(new RuntimeScalar());
                destination.putClonedElement("slot", null);
                MortalList.flush();
            }
        }
    }

    private static RuntimeScalar countedValue(RuntimeHash referent) {
        RuntimeScalar value = new RuntimeScalar();
        value.set(referent.createAnonymousReference());
        return value;
    }
}

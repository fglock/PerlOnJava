package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class PrivateNativeArrayCarrierTest {
    @Test
    void materializesWordsAndHolesAsAnOrdinaryArray() {
        PrivateNativeArrayCarrier carrier = new PrivateNativeArrayCarrier();
        carrier.setWord(0, 42);
        carrier.setWord(2, -1L);

        RuntimeArray array = carrier.materialize();

        assertEquals(3, array.countElements());
        assertEquals(42, array.elements.get(0).getLong());
        assertEquals(null, array.elements.get(1));
        assertEquals(new BigInteger("18446744073709551615"), array.elements.get(2).value);
        assertSame(array, carrier.materialize());
        assertThrows(IllegalStateException.class, () -> carrier.setWord(0, 7));
    }

    @Test
    void rejectsAnUninitializedNativeRead() {
        PrivateNativeArrayCarrier carrier = new PrivateNativeArrayCarrier();

        assertThrows(IllegalStateException.class, () -> carrier.wordAt(0));
    }

    @Test
    void retainsAnAliasResolvedOrdinaryArray() {
        RuntimeArray array = new RuntimeArray();
        PrivateNativeArrayCarrier carrier = new PrivateNativeArrayCarrier();
        carrier.retainOrdinary(array);

        assertSame(array, carrier.materialize());
        assertThrows(IllegalStateException.class, () -> carrier.setWord(0, 7));
    }

    @Test
    void exposesPerlLastIndexWithoutMaterializing() {
        PrivateNativeArrayCarrier carrier = new PrivateNativeArrayCarrier();
        assertEquals(-1, carrier.lastIndex());

        carrier.setWord(2, 7);
        assertEquals(2, carrier.lastIndex());
        assertFalse(carrier.isMaterialized());
    }
}

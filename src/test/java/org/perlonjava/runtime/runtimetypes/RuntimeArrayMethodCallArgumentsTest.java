package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeArrayMethodCallArgumentsTest {

    @Test
    void oneArgumentAndInvocantUseInlineListStorage() {
        RuntimeArray arguments = RuntimeArray.methodCallArguments(2);

        assertFalse(arguments.elements instanceof ArrayList,
                "common method argument arrays should not allocate ArrayList backing storage");
    }

    @Test
    void inlineArgumentsSupportInsertionMutationRemovalAndSelfAppend() {
        RuntimeArray arguments = RuntimeArray.methodCallArguments(2);
        RuntimeScalar invocant = new RuntimeScalar("object");
        RuntimeScalar first = new RuntimeScalar("first");
        RuntimeScalar replacement = new RuntimeScalar("replacement");

        arguments.elements.add(invocant);
        arguments.elements.add(first);
        assertSame(invocant, arguments.elements.get(0));
        assertSame(first, arguments.elements.set(1, replacement));
        assertEquals("replacement", arguments.elements.get(1).toString());

        arguments.elements.add(1, first);
        assertEquals(List.of(invocant, first, replacement), arguments.elements);
        arguments.elements.addAll(arguments.elements);
        assertEquals(List.of(invocant, first, replacement, invocant, first, replacement),
                arguments.elements);

        assertSame(invocant, arguments.elements.remove(0));
        assertEquals(List.of(first, replacement, invocant, first, replacement), arguments.elements);
        arguments.elements.clear();
        assertEquals(0, arguments.elements.size());
    }

    @Test
    void largerInitialArgumentListsKeepOrdinaryArrayStorage() {
        RuntimeArray arguments = RuntimeArray.methodCallArguments(3);

        assertTrue(arguments.elements instanceof ArrayList,
                "larger argument lists should keep the existing storage implementation");
    }
}

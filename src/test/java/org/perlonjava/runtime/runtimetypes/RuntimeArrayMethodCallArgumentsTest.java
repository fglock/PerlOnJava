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
    void methodInvocantPrependPreservesAliasedArgumentOrderForBothStorageModes() {
        RuntimeScalar invocant = new RuntimeScalar("object");
        RuntimeScalar explicit = new RuntimeScalar("explicit");

        RuntimeArray inlineArguments = RuntimeArray.methodCallArguments(2);
        explicit.setArrayOfAlias(inlineArguments);
        inlineArguments.prependMethodCallInvocant(invocant);
        assertSame(invocant, inlineArguments.elements.get(0));
        assertSame(explicit, inlineArguments.elements.get(1));

        RuntimeArray ordinaryArguments = RuntimeArray.methodCallArguments(3);
        explicit.setArrayOfAlias(ordinaryArguments);
        ordinaryArguments.prependMethodCallInvocant(invocant);
        assertSame(invocant, ordinaryArguments.elements.get(0));
        assertSame(explicit, ordinaryArguments.elements.get(1));
    }

    @Test
    void stableMethodInvocantPrependHonorsRootTrackingSuppression() {
        RuntimeArray arguments = RuntimeArray.methodCallArguments(2);
        RuntimeScalar invocant = new RuntimeScalar("object");
        RuntimeScalar explicit = new RuntimeScalar("explicit");
        explicit.setArrayOfAlias(arguments);

        assertTrue(arguments.suppressRootTrackingForStableMethodArguments());
        arguments.prependMethodCallInvocant(invocant);

        assertSame(invocant, arguments.elements.get(0));
        assertSame(explicit, arguments.elements.get(1));
        arguments.resumeMethodArgumentRootTracking();
    }

    @Test
    void largerInitialArgumentListsKeepOrdinaryArrayStorage() {
        RuntimeArray arguments = RuntimeArray.methodCallArguments(3);

        assertTrue(arguments.elements instanceof ArrayList,
                "larger argument lists should keep the existing storage implementation");
    }

    @Test
    void recycledMethodArgumentsAreReusedAfterTheirValuesAreCleared() {
        RuntimeArray arguments = RuntimeArray.methodCallArguments(2);
        new RuntimeScalar("stale").setArrayOfAlias(arguments);
        assertTrue(arguments.elementsAliased);
        arguments.recycleMethodCallArguments();

        RuntimeArray reused = RuntimeArray.methodCallArguments(2);

        assertSame(arguments, reused);
        assertTrue(reused.elements.isEmpty());
        assertFalse(reused.elementsAliased);
    }

    @Test
    void generatedMethodArgumentsReuseTheOwningExecutionStatePool() {
        ExecutionRuntimeState state = new ExecutionRuntimeState(new PerlRuntime());
        RuntimeArray arguments = RuntimeArray.methodCallArguments(2, state);
        new RuntimeScalar(1).setArrayOfAlias(arguments);
        assertTrue(arguments.elementsAliased);

        arguments.recycleMethodCallArguments(state);
        RuntimeArray reused = RuntimeArray.methodCallArguments(2, state);

        assertSame(arguments, reused);
        assertTrue(reused.elements.isEmpty());
        assertFalse(reused.elementsAliased);
    }
}
